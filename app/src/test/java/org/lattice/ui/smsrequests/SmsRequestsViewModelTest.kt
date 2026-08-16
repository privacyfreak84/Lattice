package org.lattice.ui.smsrequests

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.lattice.data.PeerRepository
import org.lattice.data.peer.PeerEntity
import org.lattice.data.settings.SettingsStore
import org.lattice.mesh.sms.InitiateResult
import org.lattice.mesh.sms.SmsBootstrap

/**
 * No Robolectric needed (unlike [org.lattice.ui.requests.MessageRequestsViewModelTest]) — this VM never
 * touches `Context.getString`, so a plain JVM unit test suffices.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsRequestsViewModelTest {
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val bootstrap = mockk<SmsBootstrap>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)

    private val pendingFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { peers.observePendingSmsRequests() } returns pendingFlow
        every { settings.blockedNodeIds } returns blockedFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = SmsRequestsViewModel(peers, bootstrap, settings)

    private fun peer(
        nodeId: String,
        name: String = "",
        phoneNumber: String = "+15551234567",
    ) = PeerEntity(nodeId = nodeId, name = name, pubKey = "KEY", phoneNumber = phoneNumber)

    @Test
    fun `requests maps pending peers into rows, sorted by display name`() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            pendingFlow.value = listOf(peer("b", name = "Bob", phoneNumber = "+15551112222"), peer("a", name = "Ada"))
            advanceUntilIdle()

            val rows = vm.requests.value
            assertEquals(listOf("Ada", "Bob"), rows.map { it.title })
            assertEquals("+15551112222", rows.single { it.nodeId == "b" }.phoneNumber)
        }

    @Test
    fun `requests excludes a peer that's since been blocked`() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            pendingFlow.value = listOf(peer("bob"), peer("carol"))
            advanceUntilIdle()
            assertEquals(2, vm.requests.value.size)

            blockedFlow.value = setOf("bob")
            advanceUntilIdle()

            assertEquals(listOf("carol"), vm.requests.value.map { it.nodeId })
        }

    @Test
    fun `requests falls back to the alias when the peer has no stored name`() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            pendingFlow.value = listOf(peer("bob", name = ""))
            advanceUntilIdle()

            val title = vm.requests.value.single().title
            assertTrue(title.isNotBlank())
        }

    @Test
    fun `accept delegates to SmsBootstrap`() =
        runTest {
            vm().accept("bob")
            advanceUntilIdle()

            coVerify(exactly = 1) { bootstrap.accept("bob") }
        }

    @Test
    fun `block delegates to SettingsStore with the peer's deviceTag`() =
        runTest {
            coEvery { peers.find("bob") } returns PeerEntity(nodeId = "bob", deviceTag = "tag-1")

            vm().block("bob")
            advanceUntilIdle()

            coVerify(exactly = 1) { settings.block("bob", "tag-1") }
        }

    @Test
    fun `initiate maps SENT through to initiateResult`() =
        runTest {
            coEvery { bootstrap.initiate("+12015550123") } returns InitiateResult.SENT
            val vm = vm()

            vm.initiate("+12015550123")
            advanceUntilIdle()

            assertEquals(SmsInitiateResult.SENT, vm.initiateResult.value)
        }

    @Test
    fun `initiate maps INVALID_NUMBER through to initiateResult, distinctly from SEND_FAILED`() =
        runTest {
            coEvery { bootstrap.initiate("garbage") } returns InitiateResult.INVALID_NUMBER
            val vm = vm()

            vm.initiate("garbage")
            advanceUntilIdle()

            assertEquals(SmsInitiateResult.INVALID, vm.initiateResult.value)
        }

    @Test
    fun `initiate maps SEND_FAILED through to initiateResult`() =
        runTest {
            coEvery { bootstrap.initiate("+12015550123") } returns InitiateResult.SEND_FAILED
            val vm = vm()

            vm.initiate("+12015550123")
            advanceUntilIdle()

            assertEquals(SmsInitiateResult.SEND_FAILED, vm.initiateResult.value)
        }

    @Test
    fun `consumeInitiateResult clears the one-shot result`() =
        runTest {
            coEvery { bootstrap.initiate(any()) } returns InitiateResult.SENT
            val vm = vm()
            vm.initiate("+12015550123")
            advanceUntilIdle()
            assertEquals(SmsInitiateResult.SENT, vm.initiateResult.value)

            vm.consumeInitiateResult()

            assertEquals(null, vm.initiateResult.value)
        }
}
