package org.lattice.mesh.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import org.lattice.mesh.protocol.WireCodec
import org.lattice.mesh.protocol.WireEnvelope
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [MeshTransport] over the SMS carrier — see `.agents/context/sms-transport.md` for the design rationale
 * and this batch's open questions.
 *
 * Scope: **text-only, single-part-and-concatenated SMS**, addressed to peers who already have a
 * [org.lattice.data.peer.PeerEntity.phoneNumber] attached (mesh-bootstrapped, out-of-band-verified
 * contacts). Explicitly NOT in scope (see design notes' "open questions" and the batch 4 reversal note):
 *  - MMS / [sendFile] — always returns false. Tried claiming the Android default-SMS-app role for this in
 *    batch 3 and reverted it in batch 4: that role would let Lattice become the default SMS app, but with
 *    no compose or conversation UI for plain (non-Lattice) SMS/MMS, the user's actual texting experience
 *    would break — no way to send a normal text, no way to read one they receive. Reverted rather than ship
 *    that. This transport now deliberately stays a **non-default** `RECEIVE_SMS` holder: a plain
 *    dynamically-registered receiver gets a courtesy copy of every incoming SMS broadcast (what any app
 *    holding `RECEIVE_SMS` gets, default or not) while the phone's actual default SMS app keeps working
 *    completely untouched, in parallel — no persistence contract, no safety net, no risk to the user's
 *    normal texting. If MMS support and a real "use it like an SMS app" experience get built later, that's
 *    a substantial, deliberate UI project (conversation list, compose screen) — see design notes.
 *  - SMS-only contact bootstrapping (a peer with a phone number but no `pubKey` yet). [neighbors]/[reachable]
 *    only ever surface peers that already have both, same as every other transport's trust model.
 *
 * [health] reflects permission + SIM presence only — there's no "radio" to be degraded the way Bluetooth/
 * Wi-Fi Aware can be (see design notes: "not anything dynamic"), so it's computed once in [start] rather
 * than tracked continuously.
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

    // No file transfer over this transport (see class doc) — always empty.
    override val incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 1).asSharedFlow()

    private var receiver: BroadcastReceiver? = null

    override fun start() {
        _health.value = computeHealth()
        peers
            .observeWithPhoneNumber()
            .onEach { rows ->
                phoneNumberFor.clear()
                val region = defaultRegion()
                rows.forEach { row ->
                    val number = row.phoneNumber ?: return@forEach
                    val normalized = PhoneNumberNormalizer.normalize(number, region)
                    if (normalized == null) {
                        Log.w(TAG, "stored phoneNumber for ${row.nodeId} failed to normalize; excluding from SMS routing")
                    } else {
                        phoneNumberFor[row.nodeId] = normalized
                    }
                }
                _neighbors.value = phoneNumberFor.keys.map { Peer(it) }.toSet()
            }.launchIn(scope)

        if (receiver == null && _health.value != TransportHealth.Unavailable) {
            val r =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context,
                        intent: Intent,
                    ) = onSmsReceived(intent)
                }
            ContextCompat.registerReceiver(
                appContext,
                r,
                IntentFilter(SMS_RECEIVED_ACTION),
                ContextCompat.RECEIVER_EXPORTED, // system broadcast, permission-gated by RECEIVE_SMS itself
            )
            receiver = r
        }
    }

    override fun stop() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    // No live radio to re-probe — health is permission/SIM state, recomputed only on start(). See class doc.
    override fun heal() = Unit

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        val manager = smsManager ?: return
        val targets = if (to == null) phoneNumberFor.values.toList() else listOfNotNull(phoneNumberFor[to.nodeId])
        if (targets.isEmpty()) return
        val text = SmsWireCodec.encode(WireCodec.encodeWire(wire))
        targets.forEach { number ->
            runCatching {
                val parts = manager.divideMessage(text)
                manager.sendMultipartTextMessage(number, null, parts, null, null)
            }.onFailure { Log.w(TAG, "send failed", it) }
        }
    }

    // MMS/large-payload transfer is out of scope — see class doc for why (default-SMS-app role reverted).
    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean = false

    private fun onSmsReceived(intent: Intent) {
        val messages = getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val sender = messages[0].originatingAddress ?: return
        // The framework batches a multipart message's segments into one delivery when they arrive together;
        // concatenating bodies in order reassembles the original base64 text. A segment that's late/out of a
        // separate broadcast is dropped here (see design notes' open questions) rather than partially decoded.
        val text = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val bytes = SmsWireCodec.decode(text) ?: return
        val wire = WireCodec.decodeWire(bytes) ?: return
        val envelope = WireCodec.decodeEnvelope(wire.signed) ?: return
        // Both sides of this comparison are normalized to E.164 through the same PhoneNumberNormalizer path
        // (phoneNumberFor's values, set in start()'s observeWithPhoneNumber collector) — see that class doc
        // for why. A sender address that fails to normalize (alphanumeric sender ID, malformed) can never
        // match a real peer, so it falls through to the same "unrecognized number" outcome as a normalized
        // sender with no matching entry.
        val normalizedSender = PhoneNumberNormalizer.normalize(sender, defaultRegion())
        val fromNodeId =
            normalizedSender
                ?.let { ns -> phoneNumberFor.entries.firstOrNull { it.value == ns }?.key }
                ?: return
        scope.launch { _inbound.emit(InboundFrame(wire, envelope, fromNodeId)) }
    }

    // SIM country as the default region for parsing national-format (no leading '+') numbers — both the
    // stored peer number and an incoming originatingAddress. Not persisted/cached: telephonyManager.simCountryIso
    // is a cheap synchronous read (no IPC), and re-reading it live means a SIM swap takes effect without needing
    // to restart the transport. Empty/absent (no SIM, or a CDMA phone with no ISO country code available) falls
    // through to null, which PhoneNumberNormalizer.normalize then can only resolve for already-international
    // ('+'-prefixed) numbers — see its doc.
    private fun defaultRegion(): String? = telephonyManager?.simCountryIso?.takeIf { it.isNotBlank() }?.uppercase()

    private fun computeHealth(): TransportHealth {
        val hasPermissions =
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
        val hasSim = telephonyManager?.simState == TelephonyManager.SIM_STATE_READY
        return if (hasPermissions && hasSim && smsManager != null) {
            TransportHealth.Healthy
        } else {
            TransportHealth.Unavailable
        }
    }

    companion object {
        private const val TAG = "SmsTransport"
        private const val SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED"

        /** True if this device has cellular telephony at all — gates whether [SmsTransport] is even built. */
        fun isSupported(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        @Suppress("DEPRECATION") // Telephony.Sms.Intents.getMessagesFromIntent requires API 34-only overload otherwise
        private fun getMessagesFromIntent(intent: Intent) =
            android.provider.Telephony.Sms.Intents
                .getMessagesFromIntent(intent)
    }
}
