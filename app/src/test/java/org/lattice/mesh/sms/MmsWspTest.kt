package org.lattice.mesh.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class MmsWspTest {
    /**
     * Builds a synthetic `M-Notification.ind` PDU byte-for-byte per this parser's own encoding
     * understanding (see [MmsWsp]'s class doc — there's no real carrier payload available in this sandbox
     * to test against, so this only proves the parser's logic is internally consistent with its own model
     * of the spec, not that the model itself matches real carrier traffic).
     */
    private fun buildNotificationInd(
        transactionId: String,
        contentLocation: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x8C) // X-Mms-Message-Type
        out.write(0x82) // m-notification-ind (short-integer)
        out.write(0x98) // X-Mms-Transaction-Id
        out.write(transactionId.toByteArray(Charsets.US_ASCII))
        out.write(0x00)
        out.write(0x8D) // X-Mms-MMS-Version
        out.write(0x90) // version 1.0 (short-integer, value unused by the parser)
        out.write(0x83) // X-Mms-Content-Location
        out.write(contentLocation.toByteArray(Charsets.US_ASCII))
        out.write(0x00)
        return out.toByteArray()
    }

    @Test
    fun `parses transaction id and content location from a well-formed PDU`() {
        val pdu = buildNotificationInd("T-1234", "http://mmsc.example.test/x")
        val result = MmsWsp.parseNotificationInd(pdu)
        assertEquals("T-1234", result?.transactionId)
        assertEquals("http://mmsc.example.test/x", result?.contentLocation)
    }

    @Test
    fun `parses fields out of the usual order too, as long as all four are recognized`() {
        val out = ByteArrayOutputStream()
        out.write(0x8C)
        out.write(0x82)
        out.write(0x83) // Content-Location before Transaction-Id this time
        out.write("loc".toByteArray(Charsets.US_ASCII))
        out.write(0x00)
        out.write(0x98)
        out.write("tid".toByteArray(Charsets.US_ASCII))
        out.write(0x00)
        val result = MmsWsp.parseNotificationInd(out.toByteArray())
        assertEquals("tid", result?.transactionId)
        assertEquals("loc", result?.contentLocation)
    }

    @Test
    fun `fails closed on an unrecognized field before all four are found`() {
        val out = ByteArrayOutputStream()
        out.write(0x8C)
        out.write(0x82)
        out.write(0x89) // From — not decoded by this parser
        out.write(0x05) // some length/value bytes this parser doesn't know how to interpret
        out.write(0x01)
        out.write(0x02)
        out.write(0x03)
        out.write(0x04)
        out.write(0x98)
        out.write("tid".toByteArray(Charsets.US_ASCII))
        out.write(0x00)
        assertNull(MmsWsp.parseNotificationInd(out.toByteArray()))
    }

    @Test
    fun `fails closed on truncated input`() {
        assertNull(MmsWsp.parseNotificationInd(byteArrayOf(0x8C.toByte())))
        assertNull(MmsWsp.parseNotificationInd(byteArrayOf(0x8C.toByte(), 0x82.toByte(), 0x98.toByte())))
    }

    @Test
    fun `rejects a message type that is not notification-ind`() {
        val out = ByteArrayOutputStream()
        out.write(0x8C)
        out.write(0x84) // m-retrieve-conf, not m-notification-ind
        out.write(0x98)
        out.write("tid".toByteArray(Charsets.US_ASCII))
        out.write(0x00)
        out.write(0x83)
        out.write("loc".toByteArray(Charsets.US_ASCII))
        out.write(0x00)
        assertNull(MmsWsp.parseNotificationInd(out.toByteArray()))
    }
}
