package org.lattice.mesh.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.lattice.data.PeerRepository
import org.lattice.mesh.FileMeta
import org.lattice.mesh.InboundFrame
import org.lattice.mesh.MeshTransport
import org.lattice.mesh.Peer
import org.lattice.mesh.ReceivedFile
import org.lattice.mesh.TransportHealth
import org.lattice.mesh.protocol.RelayEnvelope
import org.lattice.mesh.protocol.WireCodec
import org.lattice.mesh.protocol.WireEnvelope
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [MeshTransport] over the SMS/MMS carrier — see `.agents/context/sms-transport.md` for the design
 * rationale and this batch's open questions.
 *
 * Addressed to peers who already have a [org.lattice.data.peer.PeerEntity.phoneNumber] attached
 * (mesh-bootstrapped, out-of-band-verified contacts) — [neighbors]/[reachable] never surface a
 * phone-number-only contact with no pinned key; that's still an open question (see design notes).
 *
 * As of **batch 3** this transport claims the Android default-SMS-app role (see [DefaultSmsRole]), which
 * unlocks real MMS for oversized text [send] payloads (a multi-recipient group message that overflows the
 * SMS segment ceiling — see the design notes' wire-size measurement) — [MmsSender]/[MmsWapPushReceiver] take
 * over once a message is too big for concatenated SMS. [sendFile] (real file attachments) is still not
 * implemented — see its doc for why that's a distinct, deliberately-deferred task, not an MMS limitation.
 *
 * [health] now requires [DefaultSmsRole.isDefaultSmsApp] in addition to permissions + SIM presence: without
 * the role, MMS can't work at all (only the default app can write the `Telephony.Mms` provider or receive
 * `WAP_PUSH_DELIVER_ACTION`), so this transport treats "granted permissions but no role" the same as
 * "no permissions" — Unavailable — rather than silently degrading to SMS-only. Requesting the role itself is
 * a UI flow this batch doesn't wire up (see design notes): nothing here prompts the user for it.
 *
 * Inbound routing is now manifest-receiver-driven ([SmsDeliverReceiver], [MmsWapPushReceiver]) rather than
 * this class's own dynamically-registered `BroadcastReceiver` (batch 2) — `SMS_DELIVER_ACTION`/
 * `WAP_PUSH_DELIVER_ACTION` are only delivered to the default app in the first place, so the dynamic
 * registration batch 2 used is both redundant and, if left in place, would double-process every message.
 * [handleIncomingSms] and [handleDecodedInbound] are what those manifest receivers call into this running
 * singleton (resolved via Koin — see `di/MeshModule.kt`'s comment on why this is a standalone `single`).
 */
class SmsTransport(
    context: Context,
    private val peers: PeerRepository,
    private val scope: CoroutineScope,
) : MeshTransport {
    private val appContext = context.applicationContext
    private val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)

    // SmsManager.getDefault() (not Context.getSystemService(SmsManager::class.java), which is API 31+ only —
    // minSdk here is 29) is a static accessor that works on every supported API level, single-SIM only. A
    // dual-SIM subscription-specific manager is out of scope for this batch.
    private val smsManager: SmsManager? = runCatching { SmsManager.getDefault() }.getOrNull()

    // nodeId -> phoneNumber, refreshed from PeerRepository.observeWithPhoneNumber(). The routing table for
    // send()/neighbors/reachable; see the class doc for why this transport's notion of "neighbor" is
    // "has a number attached", not a live sighting.
    private val phoneNumberFor = ConcurrentHashMap<String, String>()

    private val _neighbors = MutableStateFlow<Set<Peer>>(emptySet())
    override val neighbors = _neighbors.asStateFlow()
    override val reachable = _neighbors.asStateFlow()

    private val _health = MutableStateFlow(TransportHealth.Unavailable)
    override val health = _health.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 256)
    override val inbound = _inbound.asSharedFlow()

    // No file-transfer *receive* surface yet — an inbound MMS attachment would land in the Telephony.Mms
    // provider (if MmsWapPushReceiver ever downloads one), but isn't re-surfaced as a ReceivedFile here.
    // Symmetric with sendFile not being implemented outbound either — see its doc.
    override val incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 1).asSharedFlow()

    override fun start() {
        _health.value = computeHealth()
        peers
            .observeWithPhoneNumber()
            .onEach { rows ->
                phoneNumberFor.clear()
                rows.forEach { row -> row.phoneNumber?.let { phoneNumberFor[row.nodeId] = it } }
                _neighbors.value = phoneNumberFor.keys.map { Peer(it) }.toSet()
            }.launchIn(scope)
    }

    // Nothing to unregister — inbound routing is manifest receivers (SmsDeliverReceiver, MmsWapPushReceiver)
    // now, not a receiver this instance owns. See class doc.
    override fun stop() = Unit

    // No live radio to re-probe — health is permission/role/SIM state, recomputed only on start(). See class doc.
    override fun heal() = Unit

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        val targets = if (to == null) phoneNumberFor.values.toList() else listOfNotNull(phoneNumberFor[to.nodeId])
        if (targets.isEmpty()) return
        val wireBytes = WireCodec.encodeWire(wire)
        // Oversized payloads (e.g. a multi-recipient group message — see the design notes' wire-size
        // measurement) route through MMS instead of an ever-longer concatenated-SMS chain. Same encrypted,
        // signed WireEnvelope bytes either way — MMS just carries them as a bigger base64 part instead of a
        // long chain of SMS segments.
        if (SmsWireCodec.estimatePartCount(wireBytes) > SMS_PART_CEILING) {
            targets.forEach { number -> MmsSender.send(appContext, number, wireBytes) }
            return
        }
        val manager = smsManager ?: return
        val text = SmsWireCodec.encode(wireBytes)
        targets.forEach { number ->
            runCatching {
                val parts = manager.divideMessage(text)
                manager.sendMultipartTextMessage(number, null, parts, null, null)
            }.onFailure { Log.w(TAG, "send failed", it) }
        }
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean {
        // NOT YET IMPLEMENTED — deliberately, not an oversight. MmsSender/MmsWapPushReceiver (this batch)
        // give this transport a real MMS pipe, but every other transport's file path rides an already
        // -established per-peer encrypted session (FramedLink's own handshake — see mesh/link/FramedLink.kt)
        // before any bytes move. SMS/MMS has no equivalent session; sending [file] here would mean either
        // shipping it in the clear over the carrier (a real regression, not an acceptable shortcut) or first
        // threading MessageCrypto.seal-style per-recipient encryption through this path, which needs the
        // recipient's PublicKeyBundle and is a distinct, non-trivial correctness task in its own right —
        // worth its own dedicated pass rather than rushing it into this batch. Text sends (see [send]) are
        // safe as-is: they carry the same signed, sealed WireEnvelope bytes as every other transport, just
        // base64'd for the SMS/MMS wire part instead of sent raw.
        return false
    }

    /**
     * Called by [SmsDeliverReceiver] for every plain-SMS delivery. Returns true if [body] decoded as a
     * Lattice wire envelope from a known peer (and was routed into [inbound]) — false means the receiver
     * should fall back to persisting it as an ordinary SMS (see [SmsDeliverReceiver]'s "safety net" doc).
     */
    suspend fun handleIncomingSms(
        sender: String,
        body: String,
    ): Boolean {
        val bytes = SmsWireCodec.decode(body) ?: return false
        val wire = WireCodec.decodeWire(bytes) ?: return false
        val envelope = WireCodec.decodeEnvelope(wire.signed) ?: return false
        // KNOWN GAP (not resolved this batch): exact string match against the stored E.164 phoneNumber.
        // originatingAddress's formatting isn't guaranteed to match what was stored (spacing, a missing/extra
        // country code) — a real implementation needs number normalization before this comparison, or inbound
        // frames from a correctly-configured peer can silently fail to match here. Flagging rather than
        // guessing at a normalization scheme without a concrete carrier/locale case to test it against.
        val fromNodeId = nodeIdForPhoneNumber(sender) ?: return false
        _inbound.emit(InboundFrame(wire, envelope, fromNodeId))
        return true
    }

    /** Called by [MmsWapPushReceiver] once it's decoded a Lattice wire envelope out of a downloaded MMS. */
    fun handleDecodedInbound(
        wire: WireEnvelope,
        envelope: RelayEnvelope,
        fromNodeId: String,
    ) {
        scope.launch { _inbound.emit(InboundFrame(wire, envelope, fromNodeId)) }
    }

    /** Reverse lookup on [phoneNumberFor] — same exact-match caveat as [handleIncomingSms]'s doc. */
    fun nodeIdForPhoneNumber(phoneNumber: String): String? = phoneNumberFor.entries.firstOrNull { it.value == phoneNumber }?.key

    private fun computeHealth(): TransportHealth {
        val hasPermissions =
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
        val hasSim = telephonyManager?.simState == TelephonyManager.SIM_STATE_READY
        val isDefault = DefaultSmsRole.isDefaultSmsApp(appContext)
        val isReady = hasPermissions && hasSim && isDefault
        return if (isReady && smsManager != null) {
            TransportHealth.Healthy
        } else {
            TransportHealth.Unavailable
        }
    }

    companion object {
        private const val TAG = "SmsTransport"

        // Above this many concatenated-SMS parts, send() routes through MMS instead — see the design notes'
        // wire-size measurement (a single-recipient DM is ~5 parts; this leaves real headroom before forcing
        // every larger group message through MMS too).
        private const val SMS_PART_CEILING = 10

        /** True if this device has cellular telephony at all — gates whether [SmsTransport] is even built. */
        fun isSupported(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }
}
