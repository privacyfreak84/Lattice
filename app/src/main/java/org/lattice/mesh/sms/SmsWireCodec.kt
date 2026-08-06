package org.lattice.mesh.sms

import java.util.Base64

/**
 * Frames a [org.lattice.mesh.protocol.WireEnvelope]'s encoded bytes for the SMS/MMS carrier transport.
 * Pure logic — no Android dependency — so it runs under a JVM unit test.
 *
 * Wire bytes go over SMS as base64 text rather than raw PDU user-data: `SmsManager.sendMultipartTextMessage`
 * already handles concatenation (UDH ref/seq/total) and delivery-batching for text, so hand-rolling a binary
 * segmenter here would duplicate what the platform does for free, for no benefit — the framing this class
 * owns is only "bytes <-> transport text", not segmentation.
 *
 * Base64's alphabet (`A-Za-z0-9+/=`) is entirely within the GSM-7 default alphabet, which is what determines
 * segment budget: GSM-7 gives 160 chars for a single (non-concatenated) SMS, or 153 chars per part once
 * concatenated (7 septets reserved for the UDH). A UCS-2 payload would instead cap at 70 / 67 — but nothing
 * here produces UCS-2 text, so the GSM-7 budget is what [estimatePartCount] uses.
 */
object SmsWireCodec {
    /** Chars per part for a single-segment (non-concatenated) GSM-7 SMS. */
    const val GSM7_SINGLE_PART_CHARS: Int = 160

    /** Chars per part once concatenated (UDH reserves 7 septets). */
    const val GSM7_CONCAT_PART_CHARS: Int = 153

    /** Encodes [wireBytes] (an encoded `WireEnvelope`) as SMS-transportable base64 text. */
    fun encode(wireBytes: ByteArray): String = Base64.getEncoder().encodeToString(wireBytes)

    /** Decodes SMS text back to the original wire bytes, or null if it isn't valid base64. */
    fun decode(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()

    /**
     * How many SMS parts [wireBytes] will occupy once base64-encoded, per the GSM-7 concatenated budget.
     * Used to decide, per message, whether it's cheap enough for SMS or should route over MMS instead —
     * see the "wire size" open question in `.agents/context/sms-transport.md`.
     */
    fun estimatePartCount(wireBytes: ByteArray): Int {
        val encodedLen = encode(wireBytes).length
        if (encodedLen <= GSM7_SINGLE_PART_CHARS) return 1
        return (encodedLen + GSM7_CONCAT_PART_CHARS - 1) / GSM7_CONCAT_PART_CHARS
    }
}
