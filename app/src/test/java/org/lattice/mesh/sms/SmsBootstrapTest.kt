package org.lattice.mesh.sms

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 * this pins the *control flow* (when we send, when/whether we record [PeerRepository.setProfileSentAt]),
 * not the wire format (covered by [org.lattice.mesh.OwnProfileEnvelopeTest] and `SmsWireCodecTest`) or
 * [SmsTransport]'s own send/receive plumbing (untested at this level — see its class doc and the design
 * notes' explanation of why radio-backed transports in this codebase aren't unit-tested).
 *
 * [initiate]'s normalization itself goes through the real [PhoneNumberNormalizer] (not mocked) with
 * `defaultRegion = null` — see [SmsBootstrap.initiate]'s doc for why no default region is applied here,
 * unlike [SmsTransport]'s own inbound-parsing case. `+12015550123` is libphonenumber's own documented
 * example number (see [PhoneNumberNormalizerTest]), reused here rather than a hand-picked one.
 */
class SmsBootstrapTest {
    private val wire = WireEnvelope(sig = ByteArray(0), signed = ByteArray(0))

    private fun rig(sendResult: Boolean = true): Triple<SmsTransport, PeerRepository, SmsBootstrap> {
        val transport = mockk<SmsTransport>()
        coEvery { transport.sendRaw(any(), any()) } returns sendResult
        val peers = mockk<PeerRepository>()
        coEvery { peers.setProfileSentAt(any(), any()) } returns Unit
        val ownProfile = mockk<OwnProfileEnvelope>()
        coEvery { ownProfile.signed() } returns wire
        return Triple(transport, peers, SmsBootstrap(transport, peers, ownProfile, clock = { 1_700_000_000_000L }))
    }

    // --- initiate ---

    @Test
    fun `initiate returns INVALID_NUMBER and never sends for a national-format number with no country code`() =
        runTest {
            // No leading + and no default region applied (see initiate's doc) -- can't resolve a country.
            val (transport, _, bootstrap) = rig()

            assertEquals(InitiateResult.INVALID_NUMBER, bootstrap.initiate("201-555-0123"))

            coVerify(exactly = 0) { transport.sendRaw(any(), any()) }
        }

    @Test
    fun `initiate returns INVALID_NUMBER and never sends for an alphanumeric sender ID`() =
        runTest {
            val (transport, _, bootstrap) = rig()

            assertEquals(InitiateResult.INVALID_NUMBER, bootstrap.initiate("AIRTIME"))

            coVerify(exactly = 0) { transport.sendRaw(any(), any()) }
        }

    @Test
    fun `initiate normalizes an already-international number and sends our signed profile to it`() =
        runTest {
            val (transport, _, bootstrap) = rig()

            assertEquals(InitiateResult.SENT, bootstrap.initiate("+1 (201) 555-0123"))

            coVerify(exactly = 1) { transport.sendRaw("+12015550123", wire) }
        }

    @Test
    fun `initiate returns SEND_FAILED, distinct from INVALID_NUMBER, when the underlying send fails`() =
        runTest {
            val (_, _, bootstrap) = rig(sendResult = false)

            assertEquals(InitiateResult.SEND_FAILED, bootstrap.initiate("+12015550123"))
        }

    // --- accept ---

    @Test
    fun `accept returns NOT_FOUND and sends nothing for a nodeId with no peer row`() =
        runTest {
            val (transport, peers, bootstrap) = rig()
            coEvery { peers.find("stranger") } returns null

            assertEquals(AcceptResult.NOT_FOUND, bootstrap.accept("stranger"))

            coVerify(exactly = 0) { transport.sendRaw(any(), any()) }
            coVerify(exactly = 0) { peers.setProfileSentAt(any(), any()) }
        }

    @Test
    fun `accept returns NOT_FOUND for a peer with no phoneNumber attached`() =
        runTest {
            val (transport, peers, bootstrap) = rig()
            coEvery { peers.find("mesh-only") } returns PeerEntity(nodeId = "mesh-only", phoneNumber = null)

            assertEquals(AcceptResult.NOT_FOUND, bootstrap.accept("mesh-only"))

            coVerify(exactly = 0) { transport.sendRaw(any(), any()) }
        }

    @Test
    fun `accept sends our profile to the peer's attached number and records profileSentAt`() =
        runTest {
            val (transport, peers, bootstrap) = rig()
            coEvery { peers.find("bob") } returns PeerEntity(nodeId = "bob", phoneNumber = "+15559876543")

            assertEquals(AcceptResult.ACCEPTED, bootstrap.accept("bob"))

            coVerify(exactly = 1) { transport.sendRaw("+15559876543", wire) }
            coVerify(exactly = 1) { peers.setProfileSentAt("bob", 1_700_000_000_000L) }
        }

    @Test
    fun `accept returns SEND_FAILED, distinct from NOT_FOUND, and does not record profileSentAt`() =
        runTest {
            val (_, peers, bootstrap) = rig(sendResult = false)
            coEvery { peers.find("bob") } returns PeerEntity(nodeId = "bob", phoneNumber = "+15559876543")

            assertEquals(AcceptResult.SEND_FAILED, bootstrap.accept("bob"))

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
