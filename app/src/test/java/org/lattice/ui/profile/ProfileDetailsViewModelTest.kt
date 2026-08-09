package org.lattice.ui.profile

import io.mockk.any
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.lattice.data.PeerRepository
import org.lattice.data.peer.PeerEntity
import org.lattice.data.settings.SettingsStore
import org.lattice.identity.Identity
import org.lattice.mesh.FakeMeshController
import org.lattice.mesh.Peer
import org.lattice.mesh.crypto.VerifyPayload
import org.lattice.ui.peer

/**
 * Covers the flow-derived state projection and the QR-scan verify logic. The identity fields
 * (`safetyNumber`/`myQrPayload`) are set on [Dispatchers.IO] in the VM's init and are covered separately by
 * `SafetyNumberTest`; these tests assert only the deterministic, flow-driven surface — presence/block/key
 * state and `onScanned` — none of which depends on that background load resolving.
 */
class ProfileDetailsViewModelTest {
    private val nodeId = "peer-1"
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { identity.publicKeyBundle() } returns "MYBUNDLE"
        every { peers.observePeers() } returns peersFlow
        every { settings.blockedNodeIds } returns blockedFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = ProfileDetailsViewModel(nodeId, peers, mesh, settings, identity)

    @Test
    fun stateReflectsProfilePresenceBlockAndKeyState() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, name = "Ada", pubKey = "PBUNDLE", verified = true))
            mesh.neighbors.value = setOf(Peer(nodeId))
            blockedFlow.value = setOf(nodeId)
            advanceUntilIdle()

            val s = vm.state.value
            assertEquals("Ada", s.displayName)
            assertTrue("in the neighbor set → online", s.online)
            assertTrue("id is in the blocked set", s.isBlocked)
            assertTrue("a pinned pubKey → hasKey", s.hasKey)
            assertTrue("peer.verified true → verified", s.verified)
        }

    @Test
    fun onScannedMatchingPinnedKeyMarksVerified() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, pubKey = "PBUNDLE"))
            advanceUntilIdle()

            vm.onScanned(VerifyPayload.encode(nodeId, "PBUNDLE"))
            advanceUntilIdle()

            assertEquals(VerifyScanResult.MATCH, vm.scanResult.value)
            coVerify { peers.setVerified(nodeId, true) }
        }

    @Test
    fun onScannedWrongKeyReportsMismatchAndDoesNotVerify() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, pubKey = "PBUNDLE"))
            advanceUntilIdle()

            vm.onScanned(VerifyPayload.encode(nodeId, "SOMEONE-ELSES-KEY"))
            advanceUntilIdle()

            assertEquals(VerifyScanResult.MISMATCH, vm.scanResult.value)
            coVerify(exactly = 0) { peers.setVerified(nodeId, true) }
        }

    @Test
    fun acceptPersistsThePeersConversationSoTappingMessageClearsAnyRequest() =
        runTest {
            val vm = vm()

            vm.accept()
            advanceUntilIdle()

            // A DM's conversationId is the peer's node id, so accepting adds exactly that.
            coVerify { settings.accept(nodeId) }
        }

    @Test
    fun blockUsesTheCapturedDeviceTagSoItSticksAcrossAKeyReset() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(PeerEntity(nodeId = nodeId, deviceTag = "tag-1"))
            advanceUntilIdle()

            vm.block()
            advanceUntilIdle()

            coVerify { settings.block(nodeId, "tag-1") }
            assertFalse("scan result starts empty", vm.scanResult.value == VerifyScanResult.MATCH)
        }

    @Test
    fun setPhoneNumberWithAValidNumberNormalizesPersistsAndReportsSaved() =
        runTest {
            val vm = vm()

            // 2015550123 is libphonenumber's own documented example number for a US fixed line (see
            // PhoneNumberNormalizerTest for why NOT to use 555 as the area code itself).
            vm.setPhoneNumber("+1 (201) 555-0123")
            advanceUntilIdle()

            coVerify { peers.setPhoneNumber(nodeId, "+12015550123") }
            assertEquals(PhoneNumberEditResult.SAVED, vm.phoneNumberResult.value)
        }

    @Test
    fun setPhoneNumberWithNoCountryCodeIsRejectedRatherThanGuessingARegion() =
        runTest {
            val vm = vm()

            // No default region is applied (see setPhoneNumber's doc) — a national-format number with no
            // leading '+' can't be resolved to a country and must be rejected, not silently guessed.
            vm.setPhoneNumber("201-555-0123")
            advanceUntilIdle()

            coVerify(exactly = 0) { peers.setPhoneNumber(any(), any()) }
            assertEquals(PhoneNumberEditResult.INVALID, vm.phoneNumberResult.value)
        }

    @Test
    fun clearPhoneNumberPersistsNull() =
        runTest {
            val vm = vm()

            vm.clearPhoneNumber()
            advanceUntilIdle()

            coVerify { peers.setPhoneNumber(nodeId, null) }
        }

    @Test
    fun consumePhoneNumberResultClearsTheOneShotFlag() =
        runTest {
            val vm = vm()

            vm.setPhoneNumber("not a number")
            advanceUntilIdle()
            assertEquals(PhoneNumberEditResult.INVALID, vm.phoneNumberResult.value)

            vm.consumePhoneNumberResult()
            assertNull(vm.phoneNumberResult.value)
        }
}
