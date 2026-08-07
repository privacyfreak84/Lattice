package org.lattice.mesh.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * The Android default-SMS-app role, which [SmsTransport]'s MMS path requires — see
 * `.agents/context/sms-transport.md` batch 3: only the default SMS app can write to the `Telephony.Mms`/
 * `Telephony.Sms` content provider (needed to construct an outgoing MMS PDU) or receive
 * `WAP_PUSH_DELIVER_ACTION` for an incoming one. Plain SMS send/receive (batch 2) doesn't need this —
 * [isDefaultSmsApp] gates MMS specifically, not the whole transport.
 *
 * `minSdk` here is 29 (Android Q), which is also `RoleManager`'s own minimum — there's no need for the
 * pre-Q `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` fallback every other guide covers.
 */
object DefaultSmsRole {
    /**
     * Whether this app currently holds the role. Checked via [Telephony.Sms.getDefaultSmsPackage] (stable
     * since KitKat, doesn't require a `RoleManager` round-trip) rather than `RoleManager.isRoleHeld` — the
     * two are kept in sync by the platform, and this is the simpler/more-established check.
     */
    fun isDefaultSmsApp(context: Context): Boolean = context.packageName == Telephony.Sms.getDefaultSmsPackage(context)

    /**
     * An [Intent] that prompts the user to make this app the default SMS app, for
     * `ActivityResultContracts.StartActivityForResult()`. Null if the platform can't offer the role here
     * (no [RoleManager], or the role isn't available on this device/build — mirrors [isRoleAvailable]).
     */
    fun requestRoleIntent(context: Context): Intent? {
        val roleManager = ContextCompat.getSystemService(context, RoleManager::class.java) ?: return null
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) return null
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
    }
}
