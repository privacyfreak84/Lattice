package org.lattice.mesh.sms

import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Normalizes phone numbers to E.164 for [SmsTransport]'s `originatingAddress` ↔ stored
 * [org.lattice.data.peer.PeerEntity.phoneNumber] comparison. Neither side is guaranteed to already be in
 * that form: `originatingAddress` formatting varies by carrier/locale (spacing, a missing or extra country
 * code, sometimes an alphanumeric sender ID for non-carrier senders — see [normalize] returning null), and
 * nothing currently enforces E.164 at the point a peer's number is entered either. Normalizing both sides
 * through the same path means a formatting difference can never cause a false negative, which would look
 * to the user like a message from a real, known peer silently vanishing.
 */
object PhoneNumberNormalizer {
    private val util = PhoneNumberUtil.getInstance()

    /**
     * Returns [raw] in E.164 form (e.g. `+15551234567`), or null if it can't be parsed as a plausible
     * phone number against [defaultRegion] — including alphanumeric sender IDs, which some carriers use
     * for A2P/short-code traffic and which can never be a Lattice peer's number.
     *
     * [defaultRegion] is only consulted for numbers without a leading `+`/international prefix (a
     * national-format number needs *some* region to resolve its country code against) — see
     * [SmsTransport.defaultRegion] for where that comes from. A number already in international form
     * normalizes correctly regardless of region.
     */
    fun normalize(
        raw: String,
        defaultRegion: String?,
    ): String? =
        runCatching {
            val parsed = util.parse(raw, defaultRegion)
            if (!util.isValidNumber(parsed)) return null
            util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        }.getOrNull()
}
