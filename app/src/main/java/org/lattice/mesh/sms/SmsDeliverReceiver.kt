package org.lattice.mesh.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Manifest-declared receiver for `SMS_DELIVER_ACTION` — delivered only to the default SMS app (unlike
 * batch 2's dynamic `SMS_RECEIVED_ACTION` registration, which any `RECEIVE_SMS`-holding app gets regardless
 * of default status). See `.agents/context/sms-transport.md` batch 3.
 *
 * **The safety net this batch adds:** now that Lattice can become the default SMS app, every plain SMS the
 * user's contacts send — not just Lattice's own wire-protocol traffic — arrives here first. Android's
 * contract for the default SMS app is that it persists everything it's handed to the shared
 * `Telephony.Sms` provider (other system components depend on that: carrier spam protection, any
 * OEM backup/restore, etc.) — silently dropping a non-Lattice message would both violate that contract and
 * make the user's normal texts vanish with no notice. So: try decoding as a Lattice wire envelope first: if
 * it's not one, persist it to the standard `Sms.Inbox` table exactly as any default SMS app must. **There
 * is still no in-app UI to read a plain SMS thread** — the message is safely stored (visible to any other
 * app/tool that reads the standard provider), not lost, but not yet shown anywhere in Lattice itself. That
 * gap needs a real conversation-list screen before this is a substitute for a normal messaging app.
 */
class SmsDeliverReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val smsTransport: SmsTransport by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val sender = messages[0].originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }

        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            runCatching {
                if (!smsTransport.handleIncomingSms(sender, body)) {
                    persistAsPlainSms(appContext, sender, body, messages[0].timestampMillis)
                }
            }.onFailure { Log.w(TAG, "SMS handling failed", it) }
            pending.finish()
        }
    }

    private fun persistAsPlainSms(
        context: Context,
        sender: String,
        body: String,
        timestampMillis: Long,
    ) {
        val values =
            ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestampMillis)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }

    private companion object {
        const val TAG = "SmsDeliverReceiver"
    }
}
