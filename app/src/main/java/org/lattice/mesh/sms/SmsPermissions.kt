package org.lattice.mesh.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Runtime permissions [SmsTransport] needs — separate from `ui/Permissions.kt`'s [org.lattice.ui.
 * requiredMeshPermissions], which is scoped to the two mesh radio planes only. SMS is an opt-in carrier
 * fallback (see `.agents/context/sms-transport.md`), not something onboarding should force on a device
 * without telephony at all — callers should gate on [SmsTransport.isSupported] first.
 *
 * Just `SEND_SMS`/`RECEIVE_SMS` — no `RECEIVE_MMS`/`RECEIVE_WAP_PUSH`/`READ_SMS`. Those were added in batch 3
 * for MMS support via the default-SMS-app role, which batch 4 reverted (see the design notes' reversal
 * note): without that role none of them do anything for this transport.
 */
fun requiredSmsPermissions(): Array<String> =
    arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
    )

fun hasAllSmsPermissions(context: Context): Boolean =
    requiredSmsPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
