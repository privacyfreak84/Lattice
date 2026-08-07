package org.lattice.mesh.sms

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import java.io.File

/**
 * Builds and sends an outgoing MMS carrying a [org.lattice.mesh.protocol.WireEnvelope] (optionally with a
 * file attachment) — see `.agents/context/sms-transport.md` batch 3.
 *
 * *** SECOND-HIGHEST-RISK CODE IN THIS PROJECT (after [MmsWsp]) — see that class's doc for why. *** There's
 * no public "just hand me bytes" MMS-send API; the platform only exposes `SmsManager.sendMultimediaMessage`,
 * which expects you to have **already written a valid PDU as rows in the `Telephony.Mms` content provider**
 * (message row + address rows + part rows) — the platform reads those rows back and serializes the real
 * wire PDU itself. That row schema is real, stable, public API (`Telephony.Mms`/`Telephony.Mms.Addr`/
 * `Telephony.Mms.Part`, unchanged since API 19) but writing to it as a non-default app throws
 * `SecurityException` — this only works once [DefaultSmsRole.isDefaultSmsApp] is true. Needs real-device
 * testing against a live carrier MMSC before this is trusted; there is no compiler or device in this
 * sandbox to verify the row shape actually produces a PDU a carrier accepts.
 */
object MmsSender {
    // Our own MIME type for the part carrying the raw wire-envelope bytes (base64 text, reusing SmsWireCodec)
    // — distinguishes "the Lattice payload" from the SMIL layout part and an optional real attachment part.
    private const val WIRE_PART_CONTENT_TYPE = "application/x-lattice-wire"
    private const val SMIL_CONTENT_TYPE = "application/smil"
    private const val ATTACHMENT_PART_NAME = "attachment"

    /**
     * Writes the draft PDU rows and sends. Returns true if the provider write + `sendMultimediaMessage`
     * call both succeeded (not a delivery guarantee — MMS send is async; see `MMS_SENT_ACTION` for the
     * real outcome, not tracked here yet, matching batch 2's SMS `send()` not tracking delivery either).
     */
    fun send(
        context: Context,
        toPhoneNumber: String,
        wireBytes: ByteArray,
        attachment: Pair<File, String>? = null, // file + its MIME type
    ): Boolean {
        val resolver = context.contentResolver
        val messageId = insertMessageRow(resolver, attachment != null) ?: return false
        insertAddrRow(resolver, messageId, toPhoneNumber)
        insertParts(resolver, messageId, wireBytes, attachment)
        return sendPdu(context, resolver, messageId)
    }

    private fun insertMessageRow(
        resolver: ContentResolver,
        hasAttachment: Boolean,
    ): Long? {
        val now = System.currentTimeMillis() / 1000
        val messageValues =
            ContentValues().apply {
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
                put(Telephony.Mms.DATE, now)
                put(Telephony.Mms.READ, 1)
                put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, MmsWsp.MESSAGE_TYPE_SEND_REQ)
                put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_0)
                put(Telephony.Mms.TEXT_ONLY, if (hasAttachment) 0 else 1)
            }
        val messageUri = resolver.insert(Telephony.Mms.CONTENT_URI, messageValues) ?: return null
        return ContentUris.parseId(messageUri)
    }

    private fun insertAddrRow(
        resolver: ContentResolver,
        messageId: Long,
        toPhoneNumber: String,
    ) {
        val addrUri = Uri.withAppendedPath(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId), "addr")
        resolver.insert(
            addrUri,
            ContentValues().apply {
                put(Telephony.Mms.Addr.ADDRESS, toPhoneNumber)
                put(Telephony.Mms.Addr.TYPE, ADDRESS_TYPE_TO)
                put(Telephony.Mms.Addr.CHARSET, CHARSET_UTF_8)
            },
        )
    }

    private fun insertParts(
        resolver: ContentResolver,
        messageId: Long,
        wireBytes: ByteArray,
        attachment: Pair<File, String>?,
    ) {
        val partUri = Uri.withAppendedPath(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId), "part")
        // SMIL part: minimal single-region, single-slide layout referencing the parts below. Real MMS clients
        // expect a SMIL part to exist even for a "just text" message; a well-formed but trivial one here.
        insertPart(resolver, partUri, messageId, SMIL_CONTENT_TYPE, name = "smil.xml", text = SMIL_TEMPLATE)
        // The Lattice wire-envelope payload itself, as base64 text (reuses SmsWireCodec's framing).
        insertPart(resolver, partUri, messageId, WIRE_PART_CONTENT_TYPE, name = "wire", text = SmsWireCodec.encode(wireBytes))
        if (attachment != null) {
            val (file, mimeType) = attachment
            insertPart(resolver, partUri, messageId, mimeType, name = ATTACHMENT_PART_NAME, dataPath = file.absolutePath)
        }
    }

    private fun sendPdu(
        context: Context,
        resolver: ContentResolver,
        messageId: Long,
    ): Boolean {
        val messageUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId)
        return runCatching {
            SmsManager.getDefault().sendMultimediaMessage(context, messageUri, null, Bundle(), null as PendingIntent?)
            true
        }.onFailure { Log.w(TAG, "sendMultimediaMessage failed", it) }.getOrDefault(false)
    }

    private fun insertPart(
        resolver: ContentResolver,
        partUri: Uri,
        messageId: Long,
        contentType: String,
        name: String,
        text: String? = null,
        dataPath: String? = null,
    ) {
        val values =
            ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, messageId)
                put(Telephony.Mms.Part.CONTENT_TYPE, contentType)
                put(Telephony.Mms.Part.NAME, name)
                put(Telephony.Mms.Part.CHARSET, CHARSET_UTF_8)
                if (text != null) put(Telephony.Mms.Part.TEXT, text)
            }
        val insertedUri = resolver.insert(partUri, values) ?: return
        if (dataPath != null) {
            // Binary attachment content is written via the provider's own file-backed stream, not a `_DATA`
            // column value directly (writable apps don't get raw filesystem path access into the provider's
            // storage) — openOutputStream on the row's own URI is the standard, documented pattern.
            runCatching {
                resolver.openOutputStream(insertedUri)?.use { out ->
                    File(dataPath).inputStream().use { it.copyTo(out) }
                }
            }.onFailure { Log.w(TAG, "attachment part write failed", it) }
        }
    }

    // MMS spec address-type value for "To" (OMA-WAP-209-MMSEncapsulation PduHeaders.TO). Not part of the
    // public Telephony API (PduHeaders is a hidden AOSP class) — this is the same well-known spec constant
    // any third-party MMS implementation reproduces (e.g. klinkerapps/android-smsmms's PduHeaders.java).
    private const val ADDRESS_TYPE_TO = 151
    private const val CHARSET_UTF_8 = 106 // MIBEnum for UTF-8, per the IANA charset registry MMS uses
    private const val MMS_VERSION_1_0 = 0x90 // short-integer form

    // A minimal single-slide SMIL layout: one region, this part's text + (if present) the attachment shown
    // together. Real MMS viewers expect a SMIL part; this is deliberately as simple as the format allows.
    private val SMIL_TEMPLATE =
        """
        <smil><head><layout>
        <root-layout/><region id="Text" top="0" left="0" height="100%" width="100%"/>
        </layout></head><body><par dur="5000ms">
        <text src="wire" region="Text"/>
        </par></body></smil>
        """.trimIndent()

    private const val TAG = "MmsSender"
}
