package org.lattice.mesh.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class SmsWireCodecTest {
    @Test
    fun `encode then decode round-trips arbitrary bytes`() {
        val bytes = Random(seed = 1).nextBytes(496) // ~ a single-DM WireEnvelope, see design notes
        val text = SmsWireCodec.encode(bytes)
        assertEquals(bytes.toList(), SmsWireCodec.decode(text)!!.toList())
    }

    @Test
    fun `encode then decode round-trips empty bytes`() {
        val text = SmsWireCodec.encode(ByteArray(0))
        assertEquals(0, SmsWireCodec.decode(text)!!.size)
    }

    @Test
    fun `decode rejects non-base64 text`() {
        assertNull(SmsWireCodec.decode("not valid base64 !!! @@@"))
    }

    @Test
    fun `part count is 1 under the single-segment GSM-7 budget`() {
        // 120 raw bytes base64-encodes to 160 chars, exactly at the single-part ceiling.
        val bytes = ByteArray(120)
        assertEquals(1, SmsWireCodec.estimatePartCount(bytes))
    }

    @Test
    fun `part count crosses into concatenated once over the single-segment budget`() {
        // 121 bytes encodes to 164 chars: one over the single-part ceiling, so it must concatenate.
        val bytes = ByteArray(121)
        assertEquals(2, SmsWireCodec.estimatePartCount(bytes))
    }

    @Test
    fun `part count for a representative single-recipient DM envelope`() {
        // See .agents/context/sms-transport.md batch 2 log: a hand-computed ~496-byte WireEnvelope for a
        // short single-recipient DM. Encoded to base64 that's ~661 chars, which needs 5 concatenated parts
        // (153 chars each) -- comfortably inside the ~10-segment practical ceiling.
        val bytes = ByteArray(496)
        assertEquals(5, SmsWireCodec.estimatePartCount(bytes))
    }
}
