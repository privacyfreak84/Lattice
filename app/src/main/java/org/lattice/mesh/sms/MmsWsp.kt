package org.lattice.mesh.sms

/**
 * WAP/MMS binary-encoding constants and a **minimal** parser for the `M-Notification.ind` PDU that arrives
 * in a `WAP_PUSH_DELIVER_ACTION` broadcast when an MMS is waiting at the carrier.
 *
 * *** HIGHEST-RISK, LEAST-VERIFIED CODE IN THIS PROJECT — READ BEFORE RELYING ON IT. ***
 * This hand-decodes a WAP Session Protocol (WSP) binary header block per OMA-WAP-209-MMS-Encapsulation /
 * OMA-WAP-230-WSP. There is no compiler or real device/carrier in this sandbox to test it against actual
 * MMS traffic, and unlike the rest of this codebase's low-level work, WSP header encoding genuinely can't
 * be skip-robust for header types this parser doesn't know about (see [parseNotificationInd] doc). Needs
 * real-device testing against live carrier MMSC traffic before this is trusted. If it proves unreliable in
 * practice, the fallback is a battle-tested library (e.g. `com.klinkerapps:android-smsmms`, Apache-2.0, on
 * Maven Central) or vendoring AOSP's own PDU parser, rather than continuing to harden a hand-rolled one blind.
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

    // A signed Kotlin Byte -> unsigned octet value mask, and the short-integer encoding's low-7-bits mask
    // (the top bit is the "this is a short-integer" flag per WSP, not part of the value).
    private const val BYTE_MASK = 0xFF
    private const val SHORT_INTEGER_VALUE_MASK = 0x7F
    private const val SHORT_INTEGER_TOP_BIT = 0x80

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
        val cursor = Cursor(pdu)
        val state = ParseState()
        while (cursor.hasNext() && !state.isDone()) {
            if (!state.apply(readField(cursor))) break
        }
        return state.result()
    }

    /**
     * Accumulates [FieldOutcome]s across the loop in [parseNotificationInd] — kept as its own tiny class so
     * that function stays a plain "loop until done" shape and this is where the actual field-by-field
     * branching (and the eventual success/failure verdict) lives.
     */
    private class ParseState {
        private var messageType: Int? = null
        private var transactionId: String? = null
        private var contentLocation: String? = null
        private var failed = false

        fun isDone(): Boolean = failed || (transactionId != null && contentLocation != null)

        /** Applies one field outcome; returns false once parsing should stop (a failure was hit). */
        fun apply(outcome: FieldOutcome): Boolean {
            when (outcome) {
                is FieldOutcome.MessageType -> messageType = outcome.value
                is FieldOutcome.TransactionId -> transactionId = outcome.value
                is FieldOutcome.ContentLocation -> contentLocation = outcome.value
                FieldOutcome.Skip -> Unit
                FieldOutcome.Failed -> failed = true
            }
            return !failed
        }

        fun result(): MmsNotification? {
            if (failed || messageType != MESSAGE_TYPE_NOTIFICATION_IND) return null
            val tid = transactionId ?: return null
            val loc = contentLocation ?: return null
            return MmsNotification(tid, loc)
        }
    }

    /** One header field's decode result — keeps [ParseState.apply]'s own branching to a single `when`. */
    private sealed interface FieldOutcome {
        data class MessageType(
            val value: Int,
        ) : FieldOutcome

        data class TransactionId(
            val value: String,
        ) : FieldOutcome

        data class ContentLocation(
            val value: String,
        ) : FieldOutcome

        // MMS-Version or any other field this parser reads but doesn't need the value of.
        data object Skip : FieldOutcome

        data object Failed : FieldOutcome
    }

    private fun readField(cursor: Cursor): FieldOutcome {
        val field = cursor.nextByte() ?: return FieldOutcome.Failed
        val outcome: FieldOutcome? =
            when (field) {
                FIELD_MESSAGE_TYPE -> {
                    cursor.nextByte()?.let { value ->
                        FieldOutcome.MessageType((value and SHORT_INTEGER_VALUE_MASK) or SHORT_INTEGER_TOP_BIT)
                    }
                }

                FIELD_MMS_VERSION -> {
                    cursor.nextByte()?.let { FieldOutcome.Skip }
                }

                FIELD_TRANSACTION_ID -> {
                    cursor.readTextString()?.let { FieldOutcome.TransactionId(it) }
                }

                FIELD_CONTENT_LOCATION -> {
                    cursor.readTextString()?.let { FieldOutcome.ContentLocation(it) }
                }

                // A field type this parser doesn't know how to skip safely — see class doc. Fail closed
                // rather than guess a length and risk misreading the rest of the PDU.
                else -> {
                    null
                }
            }
        return outcome ?: FieldOutcome.Failed
    }

    /** Tiny byte-cursor over [pdu] — the only place offset bookkeeping happens. */
    private class Cursor(
        private val pdu: ByteArray,
    ) {
        private var offset = 0

        fun hasNext(): Boolean = offset < pdu.size

        fun nextByte(): Int? {
            if (!hasNext()) return null
            return (pdu[offset++].toInt() and BYTE_MASK)
        }

        /** WSP text-string: an optional Quote octet, then bytes up to a null terminator (consumed). */
        fun readTextString(): String? {
            if (!hasNext()) return null
            if ((pdu[offset].toInt() and BYTE_MASK) == TEXT_STRING_QUOTE) offset++
            val start = offset
            while (offset < pdu.size && pdu[offset].toInt() != 0) offset++
            if (offset >= pdu.size) return null // no terminator found — malformed, fail closed
            val value = String(pdu, start, offset - start, Charsets.US_ASCII)
            offset++ // consume the null terminator
            return value
        }
    }
}
