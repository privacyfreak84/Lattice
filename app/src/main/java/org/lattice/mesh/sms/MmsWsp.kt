package org.lattice.mesh.sms

/**
 * WAP/MMS binary-encoding constants and a **minimal** parser for the `M-Notification.ind` PDU that arrives
 * in a `WAP_PUSH_DELIVER_ACTION` broadcast when an MMS is waiting at the carrier.
 *
 * *** HIGHEST-RISK, LEAST-VERIFIED CODE IN THIS PROJECT — READ BEFORE RELYING ON IT. ***
 * This hand-decodes a WAP Session Protocol (WSP) binary header block per OMA-WAP-209-MMS-Encapsulation /
 * OMA-WAP-230-WSP. There is no compiler or real device/carrier in this sandbox to test it against actual
 * MMS traffic, and unlike the rest of this codebase's low-level work, WSP header encoding genuinely can't
 * be skip-robust for header types this parser doesn't know about (see [parse] doc). Needs real-device
 * testing against live carrier MMSC traffic before this is trusted. If it proves unreliable in practice,
 * the fallback is a battle-tested library (e.g. `com.klinkerapps:android-smsmms`, Apache-2.0, on Maven
 * Central) or vendoring AOSP's own PDU parser, rather than continuing to harden a hand-rolled one blind.
 *
 * Deliberately parses ONLY the four fields [MmsNotification] needs and nothing else. `M-Notification.ind`
 * headers appear in a fixed order per spec (Message-Type first, Transaction-Id and MMS-Version early), so a
 * well-formed PDU is very likely to yield all four before this parser hits a field type it doesn't know how
 * to skip — at which point it deliberately **stops and fails** rather than guess a length and desync
 * (fail-closed: drop the notification, don't risk misreading and either crashing or fabricating a wrong
 * `Content-Location` that would fetch the wrong thing over the network).
 */
object MmsWsp {
    // WSP well-known header field-name octets (top bit set), from the MMS field-name assignments table —
    // only the four this parser reads.
    private const val FIELD_MESSAGE_TYPE = 0x8C
    private const val FIELD_TRANSACTION_ID = 0x98
    private const val FIELD_MMS_VERSION = 0x8D
    private const val FIELD_CONTENT_LOCATION = 0x83

    // X-Mms-Message-Type token value for m-notification-ind (short-integer encoded).
    const val MESSAGE_TYPE_NOTIFICATION_IND = 0x82

    // X-Mms-Message-Type token value for m-send-req — what MmsSender writes for an outgoing message.
    const val MESSAGE_TYPE_SEND_REQ = 0x80

    // Text-string values may be prefixed with this WSP Quote octet before the string bytes.
    private const val TEXT_STRING_QUOTE = 0x7F

    /** The subset of `M-Notification.ind` fields [MmsWapPushReceiver] needs to download the real MMS. */
    data class MmsNotification(
        val transactionId: String,
        val contentLocation: String,
    )

    /**
     * Parses [pdu] far enough to extract [MmsNotification], or null if it isn't a well-formed
     * `m-notification-ind` PDU parseable by this narrow decoder — see the class doc for why "null" (not a
     * best-effort partial result) is the only safe failure mode here.
     */
    fun parseNotificationInd(pdu: ByteArray): MmsNotification? {
        var offset = 0
        var messageType: Int? = null
        var transactionId: String? = null
        var contentLocation: String? = null

        fun readTextString(): String? {
            if (offset >= pdu.size) return null
            if ((pdu[offset].toInt() and 0xFF) == TEXT_STRING_QUOTE) offset++
            val start = offset
            while (offset < pdu.size && pdu[offset].toInt() != 0) offset++
            if (offset >= pdu.size) return null // no terminator found — malformed, fail closed
            val value = String(pdu, start, offset - start, Charsets.US_ASCII)
            offset++ // consume the null terminator
            return value
        }

        while (offset < pdu.size && (transactionId == null || contentLocation == null)) {
            val field = pdu[offset].toInt() and 0xFF
            offset++
            when (field) {
                FIELD_MESSAGE_TYPE, FIELD_MMS_VERSION -> {
                    // Short-integer: exactly one further byte, value in its low 7 bits.
                    if (offset >= pdu.size) return null
                    val value = pdu[offset].toInt() and 0xFF
                    offset++
                    if (field == FIELD_MESSAGE_TYPE) messageType = value and 0x7F or 0x80
                }

                FIELD_TRANSACTION_ID -> {
                    transactionId = readTextString() ?: return null
                }

                FIELD_CONTENT_LOCATION -> {
                    contentLocation = readTextString() ?: return null
                }

                else -> {
                    // A field type this parser doesn't know how to skip safely — see class doc. Fail closed
                    // rather than guess a length and risk misreading the rest of the PDU.
                    return null
                }
            }
        }

        if (messageType != MESSAGE_TYPE_NOTIFICATION_IND) return null
        val tid = transactionId ?: return null
        val loc = contentLocation ?: return null
        return MmsNotification(tid, loc)
    }
}
