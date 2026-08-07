package org.lattice.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.lattice.R
import org.lattice.mesh.sms.DefaultSmsRole
import org.lattice.mesh.sms.SmsTransport
import org.lattice.mesh.sms.hasAllSmsPermissions
import org.lattice.mesh.sms.requiredSmsPermissions
import org.lattice.ui.hasAllMeshPermissions
import org.lattice.ui.hasBleHardware
import org.lattice.ui.hasWifiAwareHardware
import org.lattice.ui.preview.KnitPreview
import org.lattice.ui.requestIgnoreBatteryOptimizations
import org.lattice.ui.requiredMeshPermissions

/**
 * First-run gate: explains why the mesh needs its nearby-Wi-Fi + Bluetooth + notification permissions
 * and (optionally) battery exemption, requests them, then hands off to the chat once granted. The mesh
 * can start even if a permission was denied — it just degrades. Only on hardware with **neither** Wi-Fi
 * Aware nor Bluetooth LE does it show a clear "unsupported" notice (but still lets the user in).
 *
 * This stateful wrapper owns the Android-only pieces (hardware/permission probes, the permission
 * launcher, the battery-exemption intent); [OnboardingScreenContent] is the previewable layout.
 */
@Composable
fun OnboardingScreen(onReady: () -> Unit) {
    val context = LocalContext.current
    // The mesh runs on either radio plane, so a device with Wi-Fi Aware OR Bluetooth LE can participate.
    val meshSupported = remember { hasWifiAwareHardware(context) || hasBleHardware(context) }
    var granted by remember { mutableStateOf(hasAllMeshPermissions(context)) }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            granted = hasAllMeshPermissions(context)
        }

    // SMS/MMS carrier fallback (see .agents/context/sms-transport.md) is opt-in, gated on telephony hardware
    // — a Wi-Fi-only device just never shows this section, not an "unsupported" error the way meshSupported
    // gets one, since the mesh itself works fine without it.
    val smsSupported = remember { SmsTransport.isSupported(context) }
    var smsPermissionsGranted by remember { mutableStateOf(hasAllSmsPermissions(context)) }
    var isDefaultSmsApp by remember { mutableStateOf(DefaultSmsRole.isDefaultSmsApp(context)) }

    val smsPermissionsLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            smsPermissionsGranted = hasAllSmsPermissions(context)
        }
    val defaultSmsRoleLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            // Ignore resultCode — RoleManager's request intent doesn't reliably distinguish "granted" from
            // "denied" across OEMs the way a permission result does; re-checking the actual role state via
            // getDefaultSmsPackage (DefaultSmsRole.isDefaultSmsApp) is the one source of truth.
            isDefaultSmsApp = DefaultSmsRole.isDefaultSmsApp(context)
        }

    OnboardingScreenContent(
        meshSupported = meshSupported,
        granted = granted,
        onGrantPermissions = { launcher.launch(requiredMeshPermissions()) },
        onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
        smsSupported = smsSupported,
        smsPermissionsGranted = smsPermissionsGranted,
        onGrantSmsPermissions = { smsPermissionsLauncher.launch(requiredSmsPermissions()) },
        isDefaultSmsApp = isDefaultSmsApp,
        onRequestDefaultSmsRole = {
            DefaultSmsRole.requestRoleIntent(context)?.let { defaultSmsRoleLauncher.launch(it) }
        },
        onReady = onReady,
    )
}

@Composable
internal fun OnboardingScreenContent(
    meshSupported: Boolean,
    granted: Boolean,
    onGrantPermissions: () -> Unit,
    onAllowBattery: () -> Unit,
    smsSupported: Boolean,
    smsPermissionsGranted: Boolean,
    onGrantSmsPermissions: () -> Unit,
    isDefaultSmsApp: Boolean,
    onRequestDefaultSmsRole: () -> Unit,
    onReady: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.onboarding_blurb),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 24.dp),
            )

            if (!meshSupported) {
                Text(
                    text = stringResource(R.string.onboarding_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            Button(
                onClick = onGrantPermissions,
                modifier = Modifier.fillMaxWidth().testTag("onboarding_grant"),
            ) {
                Text(
                    if (granted) {
                        stringResource(R.string.onboarding_permissions_granted)
                    } else {
                        stringResource(R.string.onboarding_grant_permissions)
                    },
                )
            }

            OutlinedButton(
                onClick = onAllowBattery,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.battery_allow_button))
            }

            // SMS/MMS carrier fallback — optional, so this section doesn't block onReady the way the mesh
            // permissions do; a device can start meshing without ever touching it.
            if (smsSupported) {
                Text(
                    text = stringResource(R.string.onboarding_sms_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )

                OutlinedButton(
                    onClick = onGrantSmsPermissions,
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_sms_grant"),
                ) {
                    Text(
                        if (smsPermissionsGranted) {
                            stringResource(R.string.onboarding_sms_permissions_granted)
                        } else {
                            stringResource(R.string.onboarding_sms_grant_permissions)
                        },
                    )
                }

                OutlinedButton(
                    onClick = onRequestDefaultSmsRole,
                    enabled = smsPermissionsGranted && !isDefaultSmsApp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .testTag("onboarding_sms_default"),
                ) {
                    Text(
                        if (isDefaultSmsApp) {
                            stringResource(R.string.onboarding_sms_is_default)
                        } else {
                            stringResource(R.string.onboarding_sms_make_default)
                        },
                    )
                }
            }

            Button(
                onClick = onReady,
                enabled = granted,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .testTag("onboarding_start"),
            ) {
                Text(stringResource(R.string.onboarding_start))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OnboardingScreenPreview() =
    KnitPreview {
        OnboardingScreenContent(
            meshSupported = true,
            granted = false,
            onGrantPermissions = {},
            onAllowBattery = {},
            smsSupported = true,
            smsPermissionsGranted = false,
            onGrantSmsPermissions = {},
            isDefaultSmsApp = false,
            onRequestDefaultSmsRole = {},
            onReady = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun OnboardingScreenGrantedPreview() =
    KnitPreview {
        OnboardingScreenContent(
            meshSupported = true,
            granted = true,
            onGrantPermissions = {},
            onAllowBattery = {},
            smsSupported = true,
            smsPermissionsGranted = true,
            onGrantSmsPermissions = {},
            isDefaultSmsApp = true,
            onRequestDefaultSmsRole = {},
            onReady = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun OnboardingScreenUnsupportedPreview() =
    KnitPreview {
        OnboardingScreenContent(
            meshSupported = false,
            granted = false,
            onGrantPermissions = {},
            onAllowBattery = {},
            smsSupported = false,
            smsPermissionsGranted = false,
            onGrantSmsPermissions = {},
            isDefaultSmsApp = false,
            onRequestDefaultSmsRole = {},
            onReady = {},
        )
    }
