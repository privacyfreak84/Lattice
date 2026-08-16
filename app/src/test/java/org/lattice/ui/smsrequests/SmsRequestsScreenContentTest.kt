package org.lattice.ui.smsrequests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.lattice.ui.theme.KnitTheme
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-on-Robolectric: drives the stateless [SmsRequestsScreenContent] on the JVM (mirrors
 * `MessageRequestsScreenContentTest`/`ChatListScreenContentTest`). Covers row rendering, Accept/Block per
 * row, the add-by-phone-number FAB dialog, and the empty state. Doesn't cover the snackbar wiring
 * ([SmsRequestsScreen]'s `LaunchedEffect`s) -- that's state-holder plumbing exercised at the ViewModel
 * level ([SmsRequestsViewModelTest]), not this stateless content composable's job.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsRequestsScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(
        nodeId: String = "n1",
        title: String = "Sam",
        phoneNumber: String = "+15551234567",
    ) = SmsRequestRow(nodeId = nodeId, title = title, avatarHash = null, phoneNumber = phoneNumber)

    @Test
    fun rowsRenderAndAcceptRoutesTheNodeId() {
        var accepted: String? = null
        compose.setContent {
            KnitTheme {
                SmsRequestsScreenContent(
                    requests = listOf(row(nodeId = "stranger-1", title = "Stranger")),
                    onAccept = { accepted = it },
                    onBlock = {},
                    onInitiate = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Stranger").assertIsDisplayed()
        compose.onNodeWithText("+15551234567").assertIsDisplayed()
        compose.onNodeWithTag("sms_request_accept_stranger-1").performClick()
        assertEquals("stranger-1", accepted)
    }

    @Test
    fun blockButtonOpensConfirmDialogAndConfirmingRoutesTheNodeId() {
        var blocked: String? = null
        compose.setContent {
            KnitTheme {
                SmsRequestsScreenContent(
                    requests = listOf(row(nodeId = "stranger-1")),
                    onAccept = {},
                    onBlock = { blocked = it },
                    onInitiate = {},
                    onBack = {},
                )
            }
        }

        // Nothing routed yet -- the row's Block button only opens the confirm dialog.
        compose.onNodeWithTag("sms_request_block_stranger-1").performClick()
        assertNull(blocked)

        compose.onNodeWithTag("sms_request_block_confirm_stranger-1").performClick()
        assertEquals("stranger-1", blocked)
    }

    @Test
    fun addFabOpensDialogAndSendRoutesTheTypedNumber() {
        var initiated: String? = null
        compose.setContent {
            KnitTheme {
                SmsRequestsScreenContent(
                    requests = emptyList(),
                    onAccept = {},
                    onBlock = {},
                    onInitiate = { initiated = it },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("sms_requests_add_fab").performClick()
        compose.onNodeWithTag("sms_requests_add_field").performTextInput("+12015550123")
        compose.onNodeWithTag("sms_requests_add_send").performClick()

        assertEquals("+12015550123", initiated)
    }

    @Test
    fun emptyStateShownWhenNoRequests() {
        compose.setContent {
            KnitTheme {
                SmsRequestsScreenContent(
                    requests = emptyList(),
                    onAccept = {},
                    onBlock = {},
                    onInitiate = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("No pending SMS requests").assertIsDisplayed()
    }
}
