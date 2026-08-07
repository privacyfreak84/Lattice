package org.lattice.mesh.sms

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.lattice.mesh.protocol.RelayEnvelope
import org.lattice.mesh.protocol.WireCodec
import org.lattice.mesh.protocol.WireEnvelope

/**
 * Manifest-declared receiver for `WAP_PUSH_DELIVER_ACTION` — the OS only delivers this to the default SMS
 * app, so it only ever fires once [DefaultSmsRole.isDefaultSmsApp] is true (see `.agents/context/
 * sms-transport.md` batch 3). The intent carries the raw `M-Notification.ind` PDU bytes; [MmsWsp] extracts
 * just enough to call [SmsManager.downloadMultimediaMessage], which does the real MMS-body fetch (over the
 * carrier's MMS network) and writes it into the `Telephony.Mms` provider itself — this receiver never
 * parses a full multipart MMS body by hand, only the small notification PDU.
 *
 * Mirrors [org.lattice.mesh.BootReceiver]'s `KoinComponent` + `goAsync()` pattern (a manifest receiver is a
 * fresh instance the OS creates per broadcast, so DI resolution — not a stored transport reference — is how
 * it reaches the running [SmsTransport] singleton).
 */
class MmsWapPushReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val smsTransport: SmsTransport by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra("data") ?: return
        val notification = MmsWsp.parseNotificationInd(pdu) ?: return

        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            runCatching { downloadAndRoute(appContext, notification) }
                .onFailure { Log.w(TAG, "MMS download failed", it) }
            pending.finish()
        }
    }

    /**
     * Inserts a draft "downloading" row (mirrors what [MmsSender] writes for an outgoing message — the
     * download call needs a destination content URI the same way sending needs a source one), downloads via
     * [SmsManager.downloadMultimediaMessage], then hands off to [routeIfDecodable]. A downloaded MMS with no
     * Lattice wire part is left exactly as the platform wrote it — a normal MMS in the Inbox, per the
     * "safety net" batch 3 note in the design doc (a non-Lattice message must not silently vanish now that
     * Lattice is the default SMS app).
     */
    private suspend fun downloadAndRoute(
        context: Context,
        notification: MmsWsp.MmsNotification,
    ) {
        val messageId = insertDownloadingRow(context, notification) ?: return
        val messageUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId)
        SmsManager.getDefault().downloadMultimediaMessage(context, notification.contentLocation, messageUri, Bundle(), null)
        // downloadMultimediaMessage is itself async (it returns once the HTTP fetch is queued, not once it's
        // done) — see the design notes' known gap: this doesn't yet wait for/listen to that completion
        // before reading parts back, so a wire-envelope part written by a slow download can be missed here.
        // Needs a real DOWNLOADED_ACTION PendingIntent listener, not a same-call read, to close that gap.
        routeIfDecodable(context, messageId)
    }

    private fun insertDownloadingRow(
        context: Context,
        notification: MmsWsp.MmsNotification,
    ): Long? {
        val values =
            ContentValues().apply {
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.TRANSACTION_ID, notification.transactionId)
                put(Telephony.Mms.MESSAGE_TYPE, MmsWsp.MESSAGE_TYPE_NOTIFICATION_IND)
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
            }
        val messageUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return null
        return ContentUris.parseId(messageUri)
    }

    /** Reads back whichever wire-envelope part (if any) got written and routes it into [smsTransport]. */
    private fun routeIfDecodable(
        context: Context,
        messageId: Long,
    ) {
        val wirePart = readWirePart(context, messageId) ?: return
        val decoded = decodeWireEnvelope(wirePart) ?: return
        val fromNodeId = nodeIdForMessage(context, messageId) ?: return
        smsTransport.handleDecodedInbound(decoded.first, decoded.second, fromNodeId)
    }

    private fun decodeWireEnvelope(text: String): Pair<WireEnvelope, RelayEnvelope>? {
        val bytes = SmsWireCodec.decode(text) ?: return null
        val wire = WireCodec.decodeWire(bytes) ?: return null
        val envelope = WireCodec.decodeEnvelope(wire.signed) ?: return null
        return wire to envelope
    }

    /** Reads the MMS `Addr` row with `TYPE = ADDRESS_TYPE_FROM` for [messageId] and resolves it to a nodeId. */
    private fun nodeIdForMessage(
        context: Context,
        messageId: Long,
    ): String? {
        val sender = readFromAddress(context, messageId) ?: return null
        return smsTransport.nodeIdForPhoneNumber(sender)
    }

    /** Reads the MMS `Addr` row with `TYPE = ADDRESS_TYPE_FROM` for [messageId] — the sender's number. */
    private fun readFromAddress(
        context: Context,
        messageId: Long,
    ): String? {
        val addrUri = Uri.withAppendedPath(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId), "addr")
        val projection = arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE)
        context.contentResolver.query(addrUri, projection, null, null, null)?.use { cursor ->
            val addressCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)
            val typeCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getInt(typeCol) == ADDRESS_TYPE_FROM) return cursor.getString(addressCol)
            }
        }
        return null
    }

    private fun readWirePart(
        context: Context,
        messageId: Long,
    ): String? {
        val projection = arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT)
        val selection = "${Telephony.Mms.Part.MSG_ID} = ? AND ${Telephony.Mms.Part.CONTENT_TYPE} = ?"
        val args = arrayOf(messageId.toString(), WIRE_PART_CONTENT_TYPE)
        context.contentResolver.query(Telephony.Mms.Part.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT))
            }
        }
        return null
    }

    private companion object {
        const val TAG = "MmsWapPushReceiver"
        const val WAP_PUSH_DELIVER_ACTION = "android.provider.Telephony.WAP_PUSH_DELIVER"
        const val WIRE_PART_CONTENT_TYPE = "application/x-lattice-wire"

        // MMS spec address-type value for "From" — see MmsSender's ADDRESS_TYPE_TO doc for why this isn't a
        // public Telephony constant (PduHeaders is a hidden AOSP class); this is the well-known spec value.
        const val ADDRESS_TYPE_FROM = 137
    }
}
