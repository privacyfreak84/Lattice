package org.lattice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lattice.data.message.MentionStore
import org.lattice.mesh.protocol.Mention
import org.lattice.mesh.protocol.mention

class MentionTest {
    @Test
    fun detectsSelfMentionByNodeId() {
        val mentions = listOf(Mention("aa11bb22", "Al"), Mention("cc33dd44", "Cee"))
        assertTrue(mentions.mention("cc33dd44"))
        assertFalse(mentions.mention("ee55ff66"))
        assertFalse(emptyList<Mention>().mention("cc33dd44"))
    }

    @Test
    fun mentionStoreRoundTrips() {
        val mentions = listOf(Mention("aa11bb22", "Al Pine"), Mention("cc33dd44", "Cee"))
        assertEquals(mentions, MentionStore.decode(MentionStore.encode(mentions)))
    }

    @Test
    fun mentionStoreEncodesEmptyAsEmptyArray() {
        assertEquals("[]", MentionStore.encode(emptyList()))
        assertEquals(emptyList<Mention>(), MentionStore.decode("[]"))
    }

    @Test
    fun mentionStoreDecodesMalformedToEmptyList() {
        assertEquals(emptyList<Mention>(), MentionStore.decode("not json"))
        assertEquals(emptyList<Mention>(), MentionStore.decode(""))
    }
}
