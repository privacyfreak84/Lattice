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
import org.lattice.identity.NodeId
import org.lattice.mesh.FileMeta
import org.lattice.mesh.InboundFrame
import org.lattice.mesh.MeshTransport
import org.lattice.mesh.Peer
import org.lattice.mesh.ReceivedFile
import org.lattice.mesh.TransportHealth
import org.lattice.mesh.protocol.FrameType
import org.lattice.mesh.protocol.ProfileContent
import org.lattice.mesh.protocol.WireCodec
import org.lattice.mesh.protocol.WireEnvelope
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [MeshTransport] over the SMS carrier — see `.agents/context/sms-transport.md` for the design rationale
 * and this batch's open questions.
 *
 * Scope: **text-only, single-part-and-concatenated SMS**. A frame from a peer who already has a
 * [org.lattice.data.peer.PeerEntity.phoneNumber] attached routes normally. A frame from an *unknown*
 * number is not rejected at this layer, though — [onSmsReceived] resolves `fromNodeId` from the wire
 * envelope's self-asserted `senderId`, the same as every other transport, and lets the shared
 * `InboundPipeline.verifyInbound`/`handleProfile` decide: a genuine self-certifying `FrameType.PROFILE`
 * (its key actually hashes to the claimed nodeId) gets trust-on-first-use pinned as a brand-new,
 * unverified peer — this transport's half of SMS-only contact bootstrapping (see `SmsBootstrap` for the
 * other half: sending our own profile to a stranger's number in the first place, and the explicit-accept
 * gate before replying to one who contacted us). Anything else from an unknown number — a CHAT frame, a
 * forged PROFILE — fails verification downstream exactly as it always has and is dropped there, not here.
 * [pendingPhoneNumberFor] is this transport's own contribution to that bootstrap: attaching the SMS
 * number a first-contact PROFILE arrived from to the peer row once (and only once) it gets pinned.
 *
 * Explicitly NOT in scope (see design notes' "open questions" and the batch 4 reversal note):
 *  - MMS / [sendFile] — always returns false, **permanently, by design** (not deferred — see design notes'
 *    "sendFile (permanently out of scope)" for the decision). SMS itself tops out around ~1600 characters
 *    via concatenated-SMS UDH, nowhere near enough for a real file; actual binary transfer over the carrier
 *    means MMS, which is hard-gated behind the Android default-SMS-app role. That role was claimed in
 *    batch 3 and reverted in batch 4: with no compose or conversation UI for plain (non-Lattice) SMS/MMS,
 *    the user's actual texting experience would break — no way to send a normal text, no way to read one
 *    they receive. This transport now deliberately stays a **non-default** `RECEIVE_SMS` holder: a plain
 *    dynamically-registered receiver gets a courtesy copy of every incoming SMS broadcast (what any app
 *    holding `RECEIVE_SMS` gets, default or not) while the phone's actual default SMS app keeps working
 *    completely untouched, in parallel — no persistence contract, no safety net, no risk to the user's
 *    normal texting. Chunking a file across many concatenated SMS segments instead of MMS was considered
 *    and rejected: slow, per-segment carrier cost, and a real risk of tripping carrier spam/abuse filters.
 *    SMS transport is text-only; a file sent to a peer reachable only over SMS silently doesn't go — same
 *    as it does today.
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

    // nodeId -> phoneNumber, refreshed from PeerRepository.observePeers(). The routing table for
    // send()/neighbors/reachable; see the class doc for why this transport's notion of "neighbor" is
    // "has a number attached", not a live sighting.
    private val phoneNumberFor = ConcurrentHashMap<String, String>()

    // nodeId -> the SMS number a first-contact PROFILE frame arrived from, for a sender that passed the
    // cheap self-certification pre-check (key hashes to the claimed nodeId — see onSmsReceived) but whose
    // peer row doesn't exist yet (pinning itself happens asynchronously, downstream, in handleProfile).
    // Drained by the observeAll() collector below the moment that row appears: setPhoneNumber(nodeId, ...)
    // then, not here, since attaching a number to a row that doesn't exist yet would be a silent no-op.
    // Bounded + oldest-first-evicted like SeenSet/KeyExchange's `missing`: an entry here only ever required
    // a cheap hash check, not a full signature verify, so it's not a free attack surface for someone who
    // can send real SMS claiming arbitrary (self-consistent) keypairs — bounding it caps that cost regardless.
    private val pendingPhoneNumberFor =
        object : LinkedHashMap<String, String>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_PENDING_PHONE_NUMBERS
        }

    @Synchronized
    private fun recordPendingPhoneNumber(
        nodeId: String,
        phoneNumber: String,
    ) {
        pendingPhoneNumberFor[nodeId] = phoneNumber
    }

    /**
     * Detaches and returns the pending number for [nodeId], or null if none — a fresh row isn't
     * necessarily one we're bootstrapping.
     */
    @Synchronized
    private fun takePendingPhoneNumber(nodeId: String): String? = pendingPhoneNumberFor.remove(nodeId)

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
            .observePeers()
            .onEach { rows ->
                phoneNumberFor.clear()
                val region = defaultRegion()
                rows.forEach { row ->
                    val number = row.phoneNumber
                    if (number == null) {
                        // No number yet — if this is a peer we're bootstrapping via a first-contact SMS
                        // PROFILE (see onSmsReceived/pendingPhoneNumberFor), attach it now that the row
                        // exists. A peer that simply has no number (mesh-only, or the user removed one via
                        // the profile screen's Remove button) has nothing pending and this is a no-op —
                        // deliberately: re-attaching a number the user explicitly removed would override
                        // their choice, so this only ever fires for a brand-new row's first attachment.
                        val pending = takePendingPhoneNumber(row.nodeId)
                        if (pending != null) {
                            Log.d(TAG, "attaching pending $pending to newly-observed peer ${row.nodeId}")
                            scope.launch { peers.setPhoneNumber(row.nodeId, pending) }
                        }
                        return@forEach
                    }
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
        // Re-encodes per target via sendRaw rather than once up front — a deliberate small tradeoff for
        // sharing the encode+send path with sendRaw's single-recipient case below; targets here are this
        // device's own SMS contact list (not a mesh-wide broadcast), realistically single digits to low
        // tens, so re-running CBOR+base64 encoding per number is negligible.
        val targets = if (to == null) phoneNumberFor.values.toList() else listOfNotNull(phoneNumberFor[to.nodeId])
        targets.forEach { number -> sendRaw(number, wire) }
    }

    /**
     * Encodes and sends [wire] directly to [phoneNumber], bypassing [phoneNumberFor] — [send]'s per-nodeId
     * path funnels through this too. The public entry point [SmsBootstrap] needs: by definition there's no
     * peer row (and so no [phoneNumberFor] entry) for a number it's initiating contact with, and arguably
     * not yet for one it's accepting either (that peer is pinned, unverified, but this is exactly the send
     * that reciprocates). Returns false only when there's no working [SmsManager] or the platform send call
     * itself throws; there's no delivery acknowledgment either way — SMS has none in this transport, same
     * as every other frame that isn't itself a [FrameType.RECEIPT].
     */
    suspend fun sendRaw(
        phoneNumber: String,
        wire: WireEnvelope,
    ): Boolean {
        val manager = smsManager ?: return false
        val text = SmsWireCodec.encode(WireCodec.encodeWire(wire))
        return runCatching {
            val parts = manager.divideMessage(text)
            manager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            true
        }.getOrElse {
            Log.w(TAG, "sendRaw failed", it)
            false
        }
    }

    /**
     * Always false. Permanently out of scope for this transport, not deferred — see the class doc and
     * design notes for why (MMS needs the default-SMS-app role, reverted in batch 4 for UX reasons; SMS
     * itself can't carry a real file; chunking over concatenated SMS was considered and rejected).
     */
    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean = false

    private fun onSmsReceived(intent: Intent) {
        val messages = getMessagesFromIntent(intent)
        if (messages == null || messages.isEmpty()) {
            Log.w(TAG, "onSmsReceived: no SmsMessages in intent")
            return
        }
        val sender = messages[0].originatingAddress
        if (sender == null) {
            Log.w(TAG, "onSmsReceived: null originatingAddress, dropping")
            return
        }
        // The framework batches a multipart message's segments into one delivery when they arrive together;
        // concatenating bodies in order reassembles the original base64 text. A segment that's late/out of a
        // separate broadcast is dropped here (see design notes' open questions) rather than partially decoded.
        val text = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val bytes = SmsWireCodec.decode(text)
        if (bytes == null) {
            Log.w(TAG, "onSmsReceived from $sender: SmsWireCodec.decode failed (${text.length} chars, ${messages.size} parts)")
            return
        }
        val wire = WireCodec.decodeWire(bytes)
        if (wire == null) {
            Log.w(TAG, "onSmsReceived from $sender: WireCodec.decodeWire failed (${bytes.size} bytes)")
            return
        }
        val envelope = WireCodec.decodeEnvelope(wire.signed)
        if (envelope == null) {
            Log.w(TAG, "onSmsReceived from $sender: WireCodec.decodeEnvelope failed")
            return
        }
        // The claimed sender, same as every other transport — NOT cross-checked against phoneNumberFor
        // (a first-contact PROFILE, by definition, comes from a sender we don't have a routing entry for
        // yet). Real authentication happens downstream in InboundPipeline.verifyInbound, which drops
        // anything whose signature doesn't verify against a key that actually hashes to this claimed id —
        // exactly how Bluetooth/Wi-Fi Aware already trust a self-asserted senderId. This transport adds
        // no new trust; it just stops gating receipt on a number it can't yet know.
        val fromNodeId = envelope.senderId
        Log.d(TAG, "onSmsReceived from $sender: decoded ${envelope.type} ${envelope.id} claiming nodeId=$fromNodeId")
        // Sequenced in one coroutine, not raced across two: maybeRecordPendingPhoneNumber's peers.find()
        // check must complete before the emit below reaches InboundPipeline.handleProfile, which pins the
        // peer row. Launching these as two independent coroutines (the original shape here) raced this
        // check against its own downstream effect -- if handleProfile won, peers.find(fromNodeId) would
        // find the row it had *just* created and skip recording the pending number, so the peer got pinned
        // correctly but its phone number silently never attached (confirmed on a real device: a
        // successfully decoded, correctly-signed profile frame arrived, but nothing showed up in
        // observePendingSmsRequests(), which requires phoneNumber IS NOT NULL). Sequencing removes the
        // self-race; a peer already pinned from *before* this frame arrived (mesh sighting, an earlier
        // message) is unaffected -- that's the intentional "already-known fromNodeId" no-op below, not
        // the bug this fixes.
        scope.launch {
            maybeRecordPendingPhoneNumber(fromNodeId, envelope.type, envelope.payload, sender)
            _inbound.emit(InboundFrame(wire, envelope, fromNodeId))
        }
    }

    /**
     * If [type]/[payload] is a genuine first-contact [FrameType.PROFILE] — its key actually hashes to
     * [fromNodeId] (the free, cheap half of what InboundPipeline's handleProfile will separately,
     * asynchronously verify in full via signature) — and no peer row exists for [fromNodeId] yet, remembers
     * [sender]'s normalized number so [start]'s collector can attach it the moment that row is pinned.
     * Deliberately does nothing for an already-known [fromNodeId] (see [pendingPhoneNumberFor]'s
     * doc for why re-attaching to an existing row would be wrong) or a sender address that fails to
     * normalize (alphanumeric sender ID, malformed — can never be a real callback number anyway). Must be
     * called (and awaited) strictly before the same frame reaches `InboundPipeline.handleProfile`
     * downstream — see the race-condition comment at this method's call site in [onSmsReceived].
     */
    private suspend fun maybeRecordPendingPhoneNumber(
        fromNodeId: String,
        type: String,
        payload: ByteArray,
        sender: String,
    ) {
        if (type != FrameType.PROFILE) {
            Log.d(TAG, "maybeRecordPendingPhoneNumber: $type from $fromNodeId is not a PROFILE, skipping")
            return
        }
        val pubKey = WireCodec.decodePayload<ProfileContent>(payload)?.pubKey
        if (pubKey == null) {
            Log.w(TAG, "maybeRecordPendingPhoneNumber: PROFILE from $fromNodeId has no decodable pubKey")
            return
        }
        val derived = NodeId.fromPublicKeyBundle(pubKey)
        if (derived != fromNodeId) {
            Log.w(TAG, "maybeRecordPendingPhoneNumber: self-cert mismatch, claimed=$fromNodeId derived=$derived")
            return
        }
        val normalizedSender = PhoneNumberNormalizer.normalize(sender, defaultRegion())
        if (normalizedSender == null) {
            Log.w(TAG, "maybeRecordPendingPhoneNumber: sender '$sender' failed to normalize (region=${defaultRegion()})")
            return
        }
        if (peers.find(fromNodeId) == null) {
            Log.d(TAG, "maybeRecordPendingPhoneNumber: recording pending $normalizedSender for new peer $fromNodeId")
            recordPendingPhoneNumber(fromNodeId, normalizedSender)
        } else {
            Log.d(TAG, "maybeRecordPendingPhoneNumber: $fromNodeId already has a peer row, not attaching a number")
        }
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

        // Cap on pendingPhoneNumberFor — see its doc. Generous relative to a realistic burst of genuine
        // first-contact attempts, small relative to what it'd cost an attacker to fill (each entry requires
        // a real SMS plus a passing (if cheap) hash check).
        private const val MAX_PENDING_PHONE_NUMBERS = 64

        /** True if this device has cellular telephony at all — gates whether [SmsTransport] is even built. */
        fun isSupported(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        @Suppress("DEPRECATION") // Telephony.Sms.Intents.getMessagesFromIntent requires API 34-only overload otherwise
        private fun getMessagesFromIntent(intent: Intent) =
            android.provider.Telephony.Sms.Intents
                .getMessagesFromIntent(intent)
    }
}
