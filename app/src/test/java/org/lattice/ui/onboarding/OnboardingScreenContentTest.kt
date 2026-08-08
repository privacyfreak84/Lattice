package org.lattice.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.lattice.ui.theme.KnitTheme
import org.robolectric.annotation.GraphicsMode

/** The app's front door: Start is gated until permissions are granted. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        meshSupported: Boolean = true,
        granted: Boolean = false,
        onGrantPermissions: () -> Unit = {},
        onAllowBattery: () -> Unit = {},
        smsSupported: Boolean = false,
        smsPermissionsGranted: Boolean = false,
        onGrantSmsPermissions: () -> Unit = {},
        onReady: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                OnboardingScreenContent(
                    meshSupported = meshSupported,
                    granted = granted,
                    onGrantPermissions = onGrantPermissions,
                    onAllowBattery = onAllowBattery,
                    smsSupported = smsSupported,
                    smsPermissionsGranted = smsPermissionsGranted,
                    onGrantSmsPermissions = onGrantSmsPermissions,
                    onReady = onReady,
                )
            }
        }
    }

    @Test
    fun startIsDisabledUntilPermissionsGranted() {
        var grants = 0
        setContent(granted = false, onGrantPermissions = { grants++ })

        compose.onNodeWithTag("onboarding_start").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding_grant").performClick()
        assertEquals(1, grants)
    }

    @Test
    fun startIsEnabledAndProceedsOnceGranted() {
        var ready = 0
        setContent(granted = true, onReady = { ready++ })

        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
        compose.onNodeWithTag("onboarding_start").performClick()
        assertEquals(1, ready)
    }

    @Test
    fun unsupportedHardwareDoesNotBlockStart() {
        setContent(meshSupported = false, granted = true)

        // No mesh radio hardware (the radio-less Firebase Test Lab / single-radio-missing reality), yet
        // Start gates only on permissions (enabled = granted, independent of meshSupported) — the app
        // degrades gracefully rather than dead-ending, so the user can still reach the app.
        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
    }

    @Test
    fun smsSectionHiddenOnDeviceWithoutTelephony() {
        setContent(smsSupported = false)

        compose.onNodeWithTag("onboarding_sms_grant").assertDoesNotExist()
    }

    @Test
    fun smsSectionShownWhenTelephonySupported() {
        setContent(smsSupported = true)

        // The onboarding Column scrolls (see OnboardingScreen.kt) — a node further down than the viewport
        // needs scrolling into view before "is displayed" is meaningful; that's what performScrollTo is for.
        compose.onNodeWithTag("onboarding_sms_grant").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun smsGrantButtonClickable() {
        var grants = 0
        setContent(smsSupported = true, smsPermissionsGranted = false, onGrantSmsPermissions = { grants++ })

        compose.onNodeWithTag("onboarding_sms_grant").performScrollTo().performClick()
        assertEquals(1, grants)
    }
}
