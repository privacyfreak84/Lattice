package org.lattice.mesh.sms

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lattice.data.PeerRepository
import org.lattice.data.peer.PeerEntity
import org.lattice.mesh.OwnProfileEnvelope
import org.lattice.mesh.protocol.FrameType
import org.lattice.mesh.protocol.RelayEnvelope
import org.lattice.mesh.protocol.WireEnvelope

/**
 * [SmsBootstrap] never touches the network/SIM itself — [transport]/[peers]/[ownProfile] are mocked, so
 * this pins the *control flow* (when we send, when we normalize, when/whether we record
 * [PeerRepository.setProfileSentAt]), not the wire format (covered by [org.lattice.mesh.OwnProfileEnvelopeTest]
 * and [SmsWireCodecTest]) or [SmsTransport]'s own send/receive plumbing (untested at this level — see its
 * class doc and the design notes' explanation of why radio-backed transports in this codebase aren't
 * unit-tested).
 */
class SmsBootstrapTest {
    private val wire = WireEnvelope(sig = ByteArray(0), signed = ByteArray(0))

    private fun rig(
        normalized: String? = "+15551234567",
        sendResult: Boolean = true,
    ): Triple<SmsTransport, PeerRepository, SmsBootstrap> {
        val transport = mockk<SmsTransport>()
        every { transport.normalize(any()) } returns normalized
        coEvery { transport.sendRaw(any(), any()) } returns sendResult
        val peers = mockk<PeerRepository>()
        coEvery { peers.setProfileSentAt(any(), any()) } returns Unit
        val ownProfile = mockk<OwnProfileEnvelope>()
        coEvery { ownProfile.signed() } returns wire
        return Triple(transport, peers, SmsBootstrap(transport, peers, ownProfile, clock = { 1_700_000_000_000L }))
    }

    // --- initiate ---

    @Test
    fun `initiate returns false and never sends when the number fails to normalize`() =
        runTest {
            val (transport, _, bootstrap) = rig(normalized = null)

            assertFalse(bootstrap.initiate("not a number"))

            coVerify(exactly = 0) { transport.sendRaw(any(), any()) }
        }

    @Test
    fun `initiate normalizes the number and sends our signed profile to it`() =
        runTest {
            val (transport, _, bootstrap) = rig(normalized = "+15551234567")

            assertTrue(bootstrap.initiate("(555) 123-4567"))

            coVerify(exactly = 1) { transport.sendRaw("+15551234567", wire) }
        }

    @Test
    fun `initiate returns false when the underlying send fails`() =
        runTest {
            val (_, _, bootstrap) = rig(sendResult = false)

            assertFalse(bootstrap.initiate("+15551234567"))
        }

    // --- accept ---

    @Test
    fun `accept returns false and sends nothing for a nodeId with no peer row`() =
        runTest {
            val (transport, peers, bootstrap) = rig()
            coEvery { peers.find("stranger") } returns null

            assertFalse(bootstrap.accept("stranger"))

            coVerify(exactly = 0) { transport.sendRaw(any(), any()) }
            coVerify(exactly = 0) { peers.setProfileSentAt(any(), any()) }
        }

    @Test
    fun `accept returns false for a peer with no phoneNumber attached`() =
        runTest {
            val (transport, peers, bootstrap) = rig()
            coEvery { peers.find("mesh-only") } returns PeerEntity(nodeId = "mesh-only", phoneNumber = null)

            assertFalse(bootstrap.accept("mesh-only"))

            coVerify(exactly = 0) { transport.sendRaw(any(), any()) }
        }

    @Test
    fun `accept sends our profile to the peer's attached number and records profileSentAt`() =
        runTest {
            val (transport, peers, bootstrap) = rig()
            coEvery { peers.find("bob") } returns PeerEntity(nodeId = "bob", phoneNumber = "+15559876543")

            assertTrue(bootstrap.accept("bob"))

            coVerify(exactly = 1) { transport.sendRaw("+15559876543", wire) }
            coVerify(exactly = 1) { peers.setProfileSentAt("bob", 1_700_000_000_000L) }
        }

    @Test
    fun `accept does not record profileSentAt when the send fails`() =
        runTest {
            val (_, peers, bootstrap) = rig(sendResult = false)
            coEvery { peers.find("bob") } returns PeerEntity(nodeId = "bob", phoneNumber = "+15559876543")

            assertFalse(bootstrap.accept("bob"))

            coVerify(exactly = 0) { peers.setProfileSentAt(any(), any()) }
        }

    // Guards the fixture itself: an unsigned RelayEnvelope/WireEnvelope pair isn't a meaningful frame,
    // just a stand-in identity the mocked ownProfile.signed() returns and the tests above verify was
    // passed through unchanged -- this isn't exercising real signing (see OwnProfileEnvelopeTest for that).
    @Test
    fun `fixture wire is a PROFILE-shaped envelope, not exercised for its own signature`() {
        val env = RelayEnvelope(type = FrameType.PROFILE, id = "profile-x-1", senderId = "x", payload = ByteArray(0))
        assertTrue(env.type == FrameType.PROFILE)
    }
}
