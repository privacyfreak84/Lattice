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
import org.lattice.mesh.ReceivedDigest
import org.lattice.mesh.ReceivedFile
import org.lattice.mesh.TransportHealth
import org.lattice.mesh.protocol.WireCodec
import org.lattice.mesh.protocol.WireEnvelope
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [MeshTransport] over the SMS carrier — see `.agents/context/sms-transport.md` for the design rationale
 * and the open questions this batch deliberately leaves unresolved.
 *
 * Scope of this batch: **text-only, single-part-and-concatenated SMS**, addressed to peers who already have
 * a [org.lattice.data.peer.PeerEntity.phoneNumber] attached (mesh-bootstrapped, out-of-band-verified
 * contacts). Explicitly NOT in scope here (see design notes' "open questions"):
 *  - MMS / [sendFile] — always returns false. A large payload (attachments, or a DM that overflows the
 *    practical concatenated-SMS ceiling) has nowhere to go yet.
 *  - Claiming the Android default-SMS-app role. This transport only registers a **dynamic**
 *    [BroadcastReceiver] for `SMS_RECEIVED_ACTION`, which any app holding [Manifest.permission.RECEIVE_SMS]
 *    gets delivered regardless of default-app status — deliberately the smaller ask, per the design notes'
 *    "needs an explicit decision, not a default fallen into".
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

    // No file transfer over this transport yet (see class doc) — always empty.
    override val incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 1).asSharedFlow()

    private var receiver: BroadcastReceiver? = null

    override fun start() {
        _health.value = computeHealth()
        peers
            .observeWithPhoneNumber()
            .onEach { rows ->
                phoneNumberFor.clear()
                rows.forEach { row -> row.phoneNumber?.let { phoneNumberFor[row.nodeId] = it } }
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

    // MMS/large-payload transfer is an open question the design notes explicitly defer (see class doc).
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
        // KNOWN GAP (not resolved this batch): exact string match against the stored E.164 phoneNumber.
        // originatingAddress's formatting isn't guaranteed to match what was stored (spacing, a missing/extra
        // country code) — a real implementation needs number normalization before this comparison, or inbound
        // frames from a correctly-configured peer can silently fail to match here. Flagging rather than
        // guessing at a normalization scheme without a concrete carrier/locale case to test it against.
        val fromNodeId = phoneNumberFor.entries.firstOrNull { it.value == sender }?.key ?: return
        scope.launch { _inbound.emit(InboundFrame(wire, envelope, fromNodeId)) }
    }

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
