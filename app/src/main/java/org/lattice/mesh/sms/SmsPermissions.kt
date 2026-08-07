package org.lattice.mesh.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Runtime permissions [SmsTransport] needs — separate from `ui/Permissions.kt`'s [org.lattice.ui.
 * requiredMeshPermissions], which is scoped to the two mesh radio planes only. SMS/MMS is an opt-in carrier
 * fallback (see `.agents/context/sms-transport.md`), not something onboarding should force on a device
 * without telephony at all — callers should gate on [SmsTransport.isSupported] first.
 *
 * `RECEIVE_WAP_PUSH` is here (not just `SEND_SMS`/`RECEIVE_SMS`/`RECEIVE_MMS`/`READ_SMS`) because it's a
 * dangerous-level, runtime-requestable permission in its own right — without it, [MmsWapPushReceiver] would
 * be declared but never actually invoked even after the default-SMS-app role is granted.
 */
fun requiredSmsPermissions(): Array<String> =
    arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.RECEIVE_WAP_PUSH,
        Manifest.permission.READ_SMS,
    )

fun hasAllSmsPermissions(context: Context): Boolean =
    requiredSmsPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
