package org.lattice.ui.applock

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.lattice.R
import org.lattice.data.settings.SettingsStore
import org.lattice.security.AppLockManager
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher

private const val TAG = "AppLockGate"

private enum class LockState { CHECKING, LOCKED, UNLOCKED }

/**
 * Gates [content] behind biometric/device-credential authentication when App Lock is enabled (see
 * [SettingsStore.appLockEnabled], toggled from the profile screen). Ported from Dres's MainActivity
 * gate (`promptUnlock`/`cryptoUnlock`/`credentialUnlock`), adapted from an Activity-lifecycle gate to a
 * Compose one: [LockState] lives in this composable's memory, so it re-locks on process death /
 * fresh MainActivity creation but stays unlocked across configuration changes within the same process,
 * matching Dres's behavior (its Activity-scoped fields had the same property).
 *
 * If the device has neither biometric nor a device credential enrolled, this falls through to
 * [content] unlocked — matching Dres's `else -> wireUi()` branch. An app lock nobody can pass because
 * the device itself has no lock configured would just be a permanent lockout, not security.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = koinInject<SettingsStore>()
    val appLockEnabled by settings.appLockEnabled.collectAsStateWithLifecycle(initialValue = true)

    var state by remember { mutableStateOf(LockState.CHECKING) }
    var failed by remember { mutableStateOf(false) }

    // Re-evaluate whenever the setting changes (e.g. the user just enabled/disabled it in Profile):
    // decide once, synchronously, whether this session needs to show the lock screen at all.
    LaunchedEffect(appLockEnabled) {
        if (state == LockState.UNLOCKED) return@LaunchedEffect // already past the gate this session
        val activity = context as? FragmentActivity
        val bm = activity?.let { BiometricManager.from(it) }
        val canAuthenticate =
            bm != null &&
                (
                    bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS ||
                        bm.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
                )
        state = if (appLockEnabled && canAuthenticate) LockState.LOCKED else LockState.UNLOCKED
    }

    when (state) {
        // Nothing rendered yet — avoids a locked-screen flash for the common (App Lock off, or the
        // device has no biometric/credential enrolled) unlocked case.
        LockState.CHECKING -> {
            Unit
        }

        LockState.UNLOCKED -> {
            content()
        }

        LockState.LOCKED -> {
            val activity = context as FragmentActivity
            val onUnlockResult: (Boolean) -> Unit = { ok ->
                if (ok) {
                    state = LockState.UNLOCKED
                } else {
                    failed = true
                }
            }
            LaunchedEffect(Unit) { promptUnlock(activity, onUnlockResult) }
            LockedScreen(
                failed = failed,
                onUnlock = {
                    failed = false
                    promptUnlock(activity, onUnlockResult)
                },
            )
        }
    }
}

@Composable
private fun LockedScreen(
    failed: Boolean,
    onUnlock: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.applock_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(if (failed) R.string.applock_failed else R.string.applock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onUnlock) { Text(stringResource(R.string.applock_unlock_action)) }
        }
    }
}

/**
 * Runs the enroll-or-verify cipher flow against [AppLockManager], then shows the system biometric
 * prompt (falling back to device-credential-only if strong biometric enrollment is unavailable, matching
 * Dres's `promptUnlock` order of preference). [onResult] fires with `true` on a real unlock/enroll,
 * `false` on failure — a user cancel invokes [FragmentActivity.finish] directly (there is nothing useful
 * to show behind a cancelled lock screen), same as Dres.
 */
private fun promptUnlock(
    activity: FragmentActivity,
    onResult: (Boolean) -> Unit,
) {
    val bm = BiometricManager.from(activity)
    if (bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
        cryptoUnlock(activity, onResult)
    } else if (bm.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) {
        credentialUnlock(activity, onResult)
    } else {
        onResult(true) // neither available — AppLockGate already filtered this case, but stay safe
    }
}

/** Strong-biometric path: the cipher itself is the authentication factor (see [AppLockManager]). */
private fun cryptoUnlock(
    activity: FragmentActivity,
    onResult: (Boolean) -> Unit,
) {
    val enrolling = !AppLockManager.isEnrolled(activity)
    val cipher: Cipher =
        try {
            if (enrolling) AppLockManager.newEncryptCipher() else AppLockManager.newDecryptCipher(activity)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "cryptoUnlock: key invalidated (e.g. biometric enrollment changed), re-enrolling", e)
            reEnrollOrFallBack(activity, onResult) ?: return
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "cryptoUnlock: could not mint a cipher, re-enrolling", e)
            reEnrollOrFallBack(activity, onResult) ?: return
        } catch (e: IOException) {
            Log.w(TAG, "cryptoUnlock: could not read the sealed token, re-enrolling", e)
            reEnrollOrFallBack(activity, onResult) ?: return
        } catch (e: IndexOutOfBoundsException) {
            Log.w(TAG, "cryptoUnlock: sealed token file is truncated/corrupted, re-enrolling", e)
            reEnrollOrFallBack(activity, onResult) ?: return
        }
    val reEnrolling = enrolling || !AppLockManager.isEnrolled(activity)

    val prompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher ?: return onResult(false)
                    val ok =
                        try {
                            if (reEnrolling) {
                                AppLockManager.finishEnroll(activity, c)
                                true
                            } else {
                                AppLockManager.verifyUnlock(activity, c)
                            }
                        } catch (e: GeneralSecurityException) {
                            Log.w(TAG, "onAuthenticationSucceeded: enroll cipher rejected the marker", e)
                            false
                        } catch (e: IOException) {
                            Log.w(TAG, "onAuthenticationSucceeded: could not write the sealed token", e)
                            false
                        }
                    onResult(ok)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        activity.finish()
                    } else {
                        onResult(false)
                    }
                }
            },
        )
    val info =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(activity.getString(R.string.applock_title))
            .setSubtitle(activity.getString(R.string.applock_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(activity.getString(android.R.string.cancel))
            .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

/**
 * The key was invalidated (e.g. the user changed their enrolled fingerprints) or some other cipher
 * error occurred: drop the stale key/token and try to mint a fresh encrypt cipher (i.e. re-enroll from
 * scratch). Returns `Unit` and never calls [onResult] itself when re-enrollment starts fine (the caller
 * proceeds with the fresh cipher); returns `null` and calls [onResult]/falls back to device-credential
 * unlock when even a fresh cipher can't be minted, signaling the caller to `return` immediately.
 */
private fun reEnrollOrFallBack(
    activity: FragmentActivity,
    onResult: (Boolean) -> Unit,
): Cipher? {
    AppLockManager.reset(activity)
    return try {
        AppLockManager.newEncryptCipher()
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "reEnrollOrFallBack: fresh cipher still failed, falling back to device credential", e)
        credentialUnlock(activity, onResult)
        null
    } catch (e: IOException) {
        Log.w(TAG, "reEnrollOrFallBack: keystore I/O failed, falling back to device credential", e)
        credentialUnlock(activity, onResult)
        null
    }
}

/** Device-credential-only path (PIN/pattern/password) — no cipher/AndroidKeyStore involved. */
private fun credentialUnlock(
    activity: FragmentActivity,
    onResult: (Boolean) -> Unit,
) {
    val prompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) = activity.finish()
            },
        )
    val info =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(activity.getString(R.string.applock_title))
            .setAllowedAuthenticators(DEVICE_CREDENTIAL)
            .build()
    prompt.authenticate(info)
}
