package org.lattice.mesh.sms

import org.lattice.data.PeerRepository
import org.lattice.mesh.OwnProfileEnvelope

/**
 * Outcome of [SmsBootstrap.initiate] — the UI needs to distinguish these, since "fix the number" and
 * "try again later" are different asks of the user.
 */
enum class InitiateResult { SENT, INVALID_NUMBER, SEND_FAILED }

/**
 * Outcome of [SmsBootstrap.accept]. NOT_FOUND shouldn't happen for a real SMS-pinned peer (see
 * [SmsBootstrap]'s class doc) but is kept distinct from SEND_FAILED rather than collapsed into it — a
 * caller passing a stale/mesh-only nodeId (e.g. the row vanished between the UI reading it and the user
 * tapping Accept) deserves "nothing to accept" rather than "try again," which would just repeat the no-op.
 */
enum class AcceptResult { ACCEPTED, NOT_FOUND, SEND_FAILED }

/**
 * The send-side half of SMS-only-contact bootstrapping — see [SmsTransport]'s class doc and
 * `.agents/context/sms-transport.md` (batch 5) for the design. Sending our own profile to a stranger's
 * number only ever happens on an explicit user action funneled through here: [SmsTransport]'s receive
 * side ([SmsTransport.onSmsReceived]/`pendingPhoneNumberFor`) only ever *pins* an unverified peer from an
 * inbound first-contact `PROFILE` — it never replies. Auto-replying would hand our profile to anyone who
 * can send an SMS claiming a self-consistent keypair, no user decision involved; that's the whole reason
 * this is a separate class rather than a line inside [SmsTransport.onSmsReceived].
 *
 * Two entry points, same underlying action (sign-and-send-our-profile):
 *  - [initiate] — cold start. The user already has a phone number out of band (texted, business card,
 *    etc.) and wants to begin contact with someone they've never met over mesh. No peer row exists yet,
 *    so there's nothing to record locally beyond the send itself; the row is created only once (if) their
 *    reply `PROFILE` arrives and [SmsTransport] pins it, same as any other first sighting.
 *  - [accept] — the reverse. Someone texted *us* first; [SmsTransport] already pinned them (unverified,
 *    `profileSentAt == null`). The user reviews and explicitly accepts, which sends our profile to their
 *    already-attached number and records [PeerRepository.setProfileSentAt].
 */
class SmsBootstrap(
    private val transport: SmsTransport,
    private val peers: PeerRepository,
    private val ownProfile: OwnProfileEnvelope,
    // Injectable wall clock, mirroring the house convention (MeshManager, ForwardSync, AckSync,
    // KeyExchange) so setProfileSentAt's timestamp is deterministic under test.
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Sends our profile to [phoneNumber] to begin an SMS-only contact. No default region is applied when
     * resolving [phoneNumber] to E.164 — this is a specific *other* person's number, possibly in a
     * different country than this device's own SIM (unlike [SmsTransport.onSmsReceived]'s inbound-parsing
     * case, where the local SIM region is at least a plausible guess for a carrier's `originatingAddress`)
     * — so a bare national-format number without a `+` fails to normalize rather than silently resolving
     * against the wrong country (same reasoning as [org.lattice.ui.profile.ProfileDetailsViewModel
     * .setPhoneNumber], attaching a number to an existing peer).
     */
    suspend fun initiate(phoneNumber: String): InitiateResult {
        val normalized =
            PhoneNumberNormalizer.normalize(phoneNumber, defaultRegion = null)
                ?: return InitiateResult.INVALID_NUMBER
        return if (sendOwnProfile(normalized)) InitiateResult.SENT else InitiateResult.SEND_FAILED
    }

    /**
     * Accepts a pending inbound first-contact request from [nodeId]: sends our profile to their attached
     * number and records [PeerRepository.setProfileSentAt] on success. NOT_FOUND — nothing sent, nothing
     * recorded — for a [nodeId] with no row or no [org.lattice.data.peer.PeerEntity.phoneNumber] attached;
     * see [AcceptResult]'s doc for why that's kept distinct from SEND_FAILED.
     */
    suspend fun accept(nodeId: String): AcceptResult {
        val number = peers.find(nodeId)?.phoneNumber ?: return AcceptResult.NOT_FOUND
        if (!sendOwnProfile(number)) return AcceptResult.SEND_FAILED
        peers.setProfileSentAt(nodeId, clock())
        return AcceptResult.ACCEPTED
    }

    private suspend fun sendOwnProfile(phoneNumber: String): Boolean = transport.sendRaw(phoneNumber, ownProfile.signed())
}
