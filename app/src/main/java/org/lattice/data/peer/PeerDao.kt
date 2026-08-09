package org.lattice.data.peer

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY name ASC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun findByNodeId(nodeId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    fun observeByNodeId(nodeId: String): Flow<PeerEntity?>

    @Query("UPDATE peers SET verified = :verified WHERE nodeId = :nodeId")
    suspend fun setVerified(
        nodeId: String,
        verified: Boolean,
    )

    /**
     * Attaches (or, with null, removes) a peer's SMS routing number. Not a trust event — deliberately
     * separate from [setVerified] and never touches [PeerEntity.pubKey]/[PeerEntity.verified] — see
     * [PeerEntity.phoneNumber]'s doc.
     */
    @Query("UPDATE peers SET phoneNumber = :phoneNumber WHERE nodeId = :nodeId")
    suspend fun setPhoneNumber(
        nodeId: String,
        phoneNumber: String?,
    )

    /** How many peers reference avatar blob [hash] — part of the orphaned-blob garbage-collection check. */
    @Query("SELECT COUNT(*) FROM peers WHERE avatarHash = :hash")
    suspend fun countByAvatarHash(hash: String): Int

    /** Node ids the user has out-of-band verified — exempt from the cap and the message-request queue. */
    @Query("SELECT nodeId FROM peers WHERE verified = 1")
    suspend fun verifiedNodeIds(): List<String>

    /** Count of unverified peers not in [protected] — the pool [evictOldestCappable] may trim. */
    @Query("SELECT COUNT(*) FROM peers WHERE verified = 0 AND nodeId NOT IN (:protected)")
    suspend fun countCappable(protected: Collection<String>): Int

    /** Evicts the [over] oldest-by-`updatedAt` unverified peers not in [protected]. */
    @Query(
        "DELETE FROM peers WHERE nodeId IN " +
            "(SELECT nodeId FROM peers WHERE verified = 0 AND nodeId NOT IN (:protected) ORDER BY updatedAt ASC LIMIT :over)",
    )
    suspend fun evictOldestCappable(
        protected: Collection<String>,
        over: Int,
    )

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    /**
     * Peers with a phone number attached — [org.lattice.mesh.sms.SmsTransport]'s entire routing table
     * (see [PeerEntity.phoneNumber]: null means mesh-only, so those peers are never candidates here).
     */
    @Query("SELECT * FROM peers WHERE phoneNumber IS NOT NULL")
    fun observeWithPhoneNumber(): Flow<List<PeerEntity>>
}
