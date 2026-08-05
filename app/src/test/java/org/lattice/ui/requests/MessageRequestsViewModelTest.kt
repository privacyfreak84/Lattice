package org.lattice.ui.requests

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lattice.data.GroupRepository
import org.lattice.data.MessageRepository
import org.lattice.data.PeerRepository
import org.lattice.data.group.GroupEntity
import org.lattice.data.message.MessageEntity
import org.lattice.data.peer.PeerEntity
import org.lattice.data.settings.SettingsStore
import org.lattice.identity.Identity
import org.lattice.ui.group
import org.lattice.ui.msg
import org.lattice.ui.peer

/**
 * Robolectric-hosted (the request list calls `context.getString` for group titles + previews). Covers the
 * request/accepted partition (mirroring the notify gate's [org.lattice.data.message.Conversations.isAccepted])
 * for both DMs and groups, and the Accept / Block / Delete actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MessageRequestsViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val messages = mockk<MessageRepository>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val groups = mockk<GroupRepository>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    private val messagesFlow = MutableStateFlow(emptyList<MessageEntity>())
    private val acceptedFlow = MutableStateFlow(emptySet<String>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())
    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val groupsFlow = MutableStateFlow(emptyList<GroupEntity>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { messages.observeMessages() } returns messagesFlow
        every { settings.acceptedConversations } returns acceptedFlow
        every { settings.blockedNodeIds } returns blockedFlow
        every { peers.observePeers() } returns peersFlow
        every { groups.observeGroups() } returns groupsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = MessageRequestsViewModel(messages, settings, peers, groups, identity, context)

    @Test
    fun aStrangerDmIsAPendingRequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            messagesFlow.value = listOf(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me", body = "hi there"))
            advanceUntilIdle()

            val row = vm.requests.value.single()
            assertEquals("b", row.conversationId)
            assertFalse(row.isGroup)
            assertEquals("hi there", row.lastPreview)
        }

    @Test
    fun aStrangerGroupInviteIsAPendingRequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), name = "Hikers"))
            messagesFlow.value = listOf(msg(senderId = "x", sentAt = 100, conversationId = "g-1", body = "welcome"))
            advanceUntilIdle()

            val row = vm.requests.value.single()
            assertEquals("g-1", row.conversationId)
            assertTrue(row.isGroup)
            assertEquals("Hikers", row.title)
        }

    @Test
    fun aGroupAKnownPeerHasPostedInIsNotARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            // Verified peer "b" speaks in the group — a known sender, so it isn't a cold request.
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x", "b"), name = "Hikers"))
            messagesFlow.value = listOf(msg(senderId = "b", sentAt = 100, conversationId = "g-1", body = "welcome"))
            peersFlow.value = listOf(peer("b", verified = true))
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aGroupWhereAKnownPeerIsAMemberButOnlyAStrangerHasPostedIsARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            // Verified peer "b" is in the roster but hasn't spoken; only stranger "x" has. Membership alone
            // doesn't bypass the inbox — the sender must be known.
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x", "b"), name = "Hikers"))
            messagesFlow.value = listOf(msg(senderId = "x", sentAt = 100, conversationId = "g-1", body = "welcome"))
            peersFlow.value = listOf(peer("b", verified = true))
            advanceUntilIdle()

            assertEquals(
                "g-1",
                vm.requests.value
                    .single()
                    .conversationId,
            )
        }

    @Test
    fun anExplicitlyAcceptedConversationIsNotARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            messagesFlow.value = listOf(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me"))
            acceptedFlow.value = setOf("b")
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aVerifiedPeerIsNotARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            messagesFlow.value = listOf(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me"))
            peersFlow.value = listOf(peer("b", verified = true))
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aThreadWeHaveRepliedInIsNotARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            messagesFlow.value =
                listOf(
                    msg(senderId = "c", sentAt = 100, conversationId = "c", recipientId = "me"),
                    msg(senderId = "me", sentAt = 200, conversationId = "c", recipientId = "c"),
                )
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aBlockedSenderIsNotShownAsARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            messagesFlow.value = listOf(msg(senderId = "b", sentAt = 100, conversationId = "b", recipientId = "me"))
            blockedFlow.value = setOf("b")
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun theNearbyRoomIsNeverARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            messagesFlow.value = listOf(msg(senderId = "b", sentAt = 100))
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun aLeftGroupIsNotShownAsARequest() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.requests.collect {} }
            groupsFlow.value = listOf(group(groupId = "g-1", members = listOf("me", "x"), left = true))
            messagesFlow.value = listOf(msg(senderId = "x", sentAt = 100, conversationId = "g-1"))
            advanceUntilIdle()

            assertTrue(vm.requests.value.isEmpty())
        }

    @Test
    fun acceptPersistsTheConversationToTheAcceptedSet() =
        runTest {
            val vm = vm()
            vm.accept("b")
            advanceUntilIdle()

            coVerify { settings.accept("b") }
        }

    @Test
    fun blockPassesTheCachedDeviceTagForTheDmPeer() =
        runTest {
            coEvery { peers.find("b") } returns PeerEntity(nodeId = "b", deviceTag = "tag-b")
            val vm = vm()

            vm.block("b")
            advanceUntilIdle()

            coVerify { settings.block("b", "tag-b") }
        }

    @Test
    fun deleteClearsADmThreadButHardDeletesAGroup() =
        runTest {
            val vm = vm()

            vm.delete("b") // a DM (bare node id)
            vm.delete("g-1") // a group
            advanceUntilIdle()

            coVerify { messages.deleteByConversation("b") }
            coVerify { groups.delete("g-1") }
        }
}
