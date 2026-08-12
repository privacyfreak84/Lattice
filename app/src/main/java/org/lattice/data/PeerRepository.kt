package org.lattice.data

import kotlinx.coroutines.flow.Flow
import org.lattice.data.peer.PeerDao
import org.lattice.data.peer.PeerEntity

/** Single source of truth for cached peer profiles. */
class PeerRepository(
    private val dao: PeerDao,
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
) {
    fun observePeers(): Flow<List<PeerEntity>> = dao.observeAll()

    /** Peers reachable over the SMS/MMS carrier transport — see [org.lattice.mesh.sms.SmsTransport]. */
    fun observeWithPhoneNumber(): Flow<List<PeerEntity>> = dao.observeWithPhoneNumber()

    fun observe(nodeId: String): Flow<PeerEntity?> = dao.observeByNodeId(nodeId)

    suspend fun find(nodeId: String): PeerEntity? = dao.findByNodeId(nodeId)

    suspend fun upsert(peer: PeerEntity) = dao.upsert(peer)

    /** Marks (or clears) the user's out-of-band verification of this peer's pinned key. */
    suspend fun setVerified(
        nodeId: String,
        verified: Boolean,
    ) = dao.setVerified(nodeId, verified)

    /**
     * Attaches (or, with null, removes) this peer's SMS routing number — see [PeerEntity.phoneNumber].
     * Callers are expected to have already normalized [phoneNumber] to E.164 (see
     * [org.lattice.mesh.sms.PhoneNumberNormalizer]); this method itself does no validation.
     */
    suspend fun setPhoneNumber(
        nodeId: String,
        phoneNumber: String?,
    ) = dao.setPhoneNumber(nodeId, phoneNumber)

    /** Records that we just sent our own profile directly to this peer — see [PeerEntity.profileSentAt]. */
    suspend fun setProfileSentAt(
        nodeId: String,
        sentAt: Long,
    ) = dao.setProfileSentAt(nodeId, sentAt)

    /** Node ids the user has out-of-band verified — exempt from [sweepCap] and the message-request queue. */
    suspend fun verifiedNodeIds(): List<String> = dao.verifiedNodeIds()

    /**
     * Bounds the `peers` table (any valid inbound profile upserts a row, uncapped) against a Sybil profile
     * flood: evicts the oldest-by-`updatedAt` **unverified** peers beyond [cap] that aren't [protected]
     * (verified, an accepted/known conversation id, or a peer the user has messaged — group ids / the Nearby id
     * in that set simply match no peer row, harmlessly). A dropped row only sheds a cached profile + pinned key,
     * which re-arrives / re-fetches (KeyExchange) on the peer's next frame — cheap, so keep [cap] high.
     */
    suspend fun sweepCap(
        protected: Set<String>,
        cap: Int = maxPeers,
    ) {
        val over = dao.countCappable(protected) - cap
        if (over > 0) dao.evictOldestCappable(protected, over)
    }

    private companion object {
        /** High, conservative ceiling on cached peer rows — a Sybil profile-flood backstop, not a routine bound. */
        const val DEFAULT_MAX_PEERS = 2_000
    }
}
