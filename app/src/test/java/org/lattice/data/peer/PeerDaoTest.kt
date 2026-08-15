package org.lattice.data.peer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lattice.data.RoomDbTest

/** Real-SQL coverage for the peer table — the TOFU pin/verified flag + the avatar-blob ref count for GC. */
class PeerDaoTest : RoomDbTest() {
    private val dao get() = db.peerDao()

    @Test
    fun `upsert then findByNodeId round-trips the pinned key and profile fields`() =
        runTest {
            dao.upsert(
                PeerEntity(
                    nodeId = "a",
                    name = "Ada",
                    status = "hi",
                    avatarHash = "av",
                    pubKey = "KEY",
                    verified = true,
                    deviceTag = "tag",
                    updatedAt = 5L,
                ),
            )
            val got = dao.findByNodeId("a")!!
            assertEquals("Ada", got.name)
            assertEquals("KEY", got.pubKey)
            assertTrue(got.verified)
            assertEquals("tag", got.deviceTag)
        }

    @Test
    fun `setVerified flips only the verified flag`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", pubKey = "KEY", verified = false))
            dao.setVerified("a", true)
            assertTrue(dao.findByNodeId("a")!!.verified)
            dao.setVerified("a", false)
            assertFalse(dao.findByNodeId("a")!!.verified)
        }

    @Test
    fun `setPhoneNumber attaches and, with null, removes the number without touching pubKey or verified`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", pubKey = "KEY", verified = true))
            dao.setPhoneNumber("a", "+15551234567")
            dao.findByNodeId("a")!!.let {
                assertEquals("+15551234567", it.phoneNumber)
                assertEquals("KEY", it.pubKey) // untouched
                assertTrue(it.verified) // untouched — attaching a number is not a trust event
            }
            dao.setPhoneNumber("a", null)
            assertNull(dao.findByNodeId("a")!!.phoneNumber)
        }

    @Test
    fun `setProfileSentAt records when we last sent our own profile without touching pubKey or verified`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", pubKey = "KEY", verified = true, phoneNumber = "+15551234567"))
            assertNull("starts unset", dao.findByNodeId("a")!!.profileSentAt)
            dao.setProfileSentAt("a", 1_700_000_000_000L)
            dao.findByNodeId("a")!!.let {
                assertEquals(1_700_000_000_000L, it.profileSentAt)
                assertEquals("KEY", it.pubKey) // untouched
                assertTrue(it.verified) // untouched
            }
        }

    @Test
    fun `observeWithPhoneNumber emits only peers that have one attached`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", phoneNumber = "+15551234567"))
            dao.upsert(PeerEntity(nodeId = "b", phoneNumber = null))
            assertEquals(listOf("a"), dao.observeWithPhoneNumber().first().map { it.nodeId })
        }

    @Test
    fun `observePendingSmsRequests emits only peers with a phoneNumber and pubKey but no profileSentAt`() =
        runTest {
            // Pending: phoneNumber + pubKey, haven't reciprocated yet -- the one row that should show.
            dao.upsert(PeerEntity(nodeId = "pending", phoneNumber = "+15551234567", pubKey = "KEY"))
            // Already reciprocated: same shape but profileSentAt set -- no longer pending.
            dao.upsert(
                PeerEntity(nodeId = "sent", phoneNumber = "+15559876543", pubKey = "KEY", profileSentAt = 1L),
            )
            // No pubKey yet (shouldn't be reachable via SmsTransport's own pinning, but guards the query).
            dao.upsert(PeerEntity(nodeId = "no-key", phoneNumber = "+15551112222", pubKey = null))
            // Mesh-only, no phoneNumber at all.
            dao.upsert(PeerEntity(nodeId = "mesh-only", phoneNumber = null, pubKey = "KEY"))

            assertEquals(listOf("pending"), dao.observePendingSmsRequests().first().map { it.nodeId })
        }

    @Test
    fun `countByAvatarHash counts peers referencing that avatar blob`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", avatarHash = "h1"))
            dao.upsert(PeerEntity(nodeId = "b", avatarHash = "h1"))
            dao.upsert(PeerEntity(nodeId = "c", avatarHash = "h2"))
            assertEquals(2, dao.countByAvatarHash("h1"))
            assertEquals(1, dao.countByAvatarHash("h2"))
            assertEquals(0, dao.countByAvatarHash("none"))
        }

    @Test
    fun `upsert replaces the row on the same nodeId (a profile update)`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada", updatedAt = 1L))
            dao.upsert(PeerEntity(nodeId = "a", name = "Ada Renamed", updatedAt = 2L))
            assertEquals("Ada Renamed", dao.findByNodeId("a")!!.name)
            assertEquals(1, dao.observeAll().first().size)
        }

    @Test
    fun `observeAll orders by name ascending`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "a", name = "Zed"))
            dao.upsert(PeerEntity(nodeId = "b", name = "Amy"))
            assertEquals(listOf("Amy", "Zed"), dao.observeAll().first().map { it.name })
        }

    @Test
    fun `verifiedNodeIds returns only verified peers`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "v", pubKey = "K", verified = true))
            dao.upsert(PeerEntity(nodeId = "u", pubKey = "K", verified = false))
            assertEquals(listOf("v"), dao.verifiedNodeIds())
        }

    @Test
    fun `countCappable and evictOldestCappable spare verified and protected peers`() =
        runTest {
            dao.upsert(PeerEntity(nodeId = "verified", verified = true, updatedAt = 1L))
            dao.upsert(PeerEntity(nodeId = "protected", verified = false, updatedAt = 2L))
            dao.upsert(PeerEntity(nodeId = "old", verified = false, updatedAt = 3L))
            dao.upsert(PeerEntity(nodeId = "new", verified = false, updatedAt = 4L))

            val protectedIds = listOf("protected")
            assertEquals(2, dao.countCappable(protectedIds)) // "old" + "new" (verified + protected excluded)

            dao.evictOldestCappable(protectedIds, over = 1) // evict the single oldest cappable → "old"
            assertNull(dao.findByNodeId("old"))
            assertNotNull(dao.findByNodeId("new"))
            assertNotNull(dao.findByNodeId("verified"))
            assertNotNull(dao.findByNodeId("protected"))
        }
}
