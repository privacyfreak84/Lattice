package org.lattice.ui.profile

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.lattice.R
import org.lattice.ui.theme.KnitTheme
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the stateless `ProfileDetailsScreenContent` (a peer's contact-details view). Its body is a
 * `verticalScroll` Column, so every child composes regardless of viewport — we can assert on the status
 * and safety-number text and click the Message action (found by its icon contentDescription).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileDetailsScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun state(
        hasKey: Boolean = true,
        phoneNumber: String? = null,
    ) = ProfileDetailsUiState(
        nodeId = "8f3a2b1c9d4e",
        displayName = "Ada Lovelace",
        status = "Hiking this weekend",
        avatarHash = null,
        online = true,
        isBlocked = false,
        hasKey = hasKey,
        verified = true,
        safetyNumber = "12345 67890 12345 67890 12345 67890",
        myQrPayload = null,
        phoneNumber = phoneNumber,
    )

    private fun setContent(
        onMessage: (String) -> Unit = {},
        hasKey: Boolean = true,
        phoneNumber: String? = null,
        onSavePhoneNumber: (String) -> Unit = {},
        onRemovePhoneNumber: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                ProfileDetailsScreenContent(
                    state = state(hasKey = hasKey, phoneNumber = phoneNumber),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onMessage = onMessage,
                    onScan = {},
                    onBlock = {},
                    onUnblock = {},
                    onMarkVerified = {},
                    onClearVerification = {},
                    onSavePhoneNumber = onSavePhoneNumber,
                    onRemovePhoneNumber = onRemovePhoneNumber,
                )
            }
        }
    }

    @Test
    fun rendersStatusAndSafetyNumber() {
        setContent()
        compose.onNodeWithText("Hiking this weekend").assertIsDisplayed()
        // The safety number lives in the verification section below the fold; scroll it into view.
        compose.onNodeWithText("12345 67890 12345 67890 12345 67890").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingMessageForwardsTheNodeId() {
        var messaged: String? = null
        setContent(onMessage = { messaged = it })

        compose.onNodeWithContentDescription(context.getString(R.string.profile_details_message)).performClick()
        assertEquals("8f3a2b1c9d4e", messaged)
    }

    @Test
    fun phoneNumberSectionIsHiddenWithoutAKeyEvenThoughVerifiedIsUnrelated() {
        // hasKey = false — a phone number with no key to encrypt to could never route over SmsTransport,
        // so the section shouldn't even offer the option. This is deliberately independent of `verified`.
        setContent(hasKey = false)
        compose.onNodeWithText(context.getString(R.string.profile_details_phone_title)).assertDoesNotExist()
    }

    @Test
    fun phoneNumberSectionIsShownWithAKeyRegardlessOfPhoneNumberBeingSetYet() {
        setContent(hasKey = true, phoneNumber = null)
        compose
            .onNodeWithText(context.getString(R.string.profile_details_phone_title))
            .performScrollTo()
            .assertIsDisplayed()
        // No number attached yet — nothing to remove.
        compose.onNodeWithText(context.getString(R.string.profile_details_phone_remove)).assertDoesNotExist()
    }

    @Test
    fun typingAndSavingAPhoneNumberForwardsTheTypedText() {
        var saved: String? = null
        setContent(hasKey = true, phoneNumber = null, onSavePhoneNumber = { saved = it })

        compose.onNodeWithTag("phone_number_field").performScrollTo().performTextInput("+12015550123")
        compose.onNodeWithTag("phone_number_save").performClick()

        assertEquals("+12015550123", saved)
    }

    @Test
    fun removeButtonIsShownOnceANumberIsAttachedAndForwardsTheTap() {
        var removed = false
        setContent(hasKey = true, phoneNumber = "+12015550123", onRemovePhoneNumber = { removed = true })

        compose.onNodeWithTag("phone_number_remove").performScrollTo().performClick()

        assertEquals(true, removed)
    }
}
