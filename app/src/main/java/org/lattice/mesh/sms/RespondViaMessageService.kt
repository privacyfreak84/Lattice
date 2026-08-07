package org.lattice.mesh.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager

/**
 * Handles `ACTION_RESPOND_VIA_MESSAGE` — one of the components Android requires a default-SMS-app
 * candidate to declare (fired from, e.g., the incoming-call screen's "decline with message" action). See
 * `.agents/context/sms-transport.md` batch 3.
 *
 * Lattice has no "quick response" UI concept of its own, and the target here is an arbitrary `sms:`/`smsto:`
 * URI — not necessarily an existing Lattice peer with a pinned key — so this can't route through
 * [SmsTransport]'s encrypted wire protocol. Sending the plain text via [SmsManager] directly (the same
 * fallback a minimal-but-compliant default SMS app uses) satisfies the OS contract without pretending this
 * is an encrypted Lattice message. A real "quick response" UI/UX for this is still open — this is
 * deliberately just enough to be a valid default-SMS-app candidate, not a designed feature.
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val data = intent?.data
        val message = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val phoneNumber = data?.schemeSpecificPart?.substringBefore('?')
        if (phoneNumber != null && !message.isNullOrEmpty()) {
            runCatching { SmsManager.getDefault().sendTextMessage(phoneNumber, null, message, null, null) }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
