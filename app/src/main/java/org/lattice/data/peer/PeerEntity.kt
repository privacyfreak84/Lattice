package org.lattice.data.peer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached profile of a peer learned from the mesh, keyed by its [nodeId]. [avatarHash] is the content
 * hash of the peer's avatar; the bytes live in the encrypted `blobs` table (see
 * [org.lattice.data.blob.BlobEntity]) and are only set once the avatar payload has arrived, so a
 * non-null hash always implies the blob is present.
 *
 * [pubKey] is the peer's pinned end-to-end public-key bundle (base64; see
 * [org.lattice.mesh.crypto.PublicKeyBundle]), learned from their profile frame on a
 * trust-on-first-use basis. [verified] is true once the local user has confirmed that key out of band
 * (safety number / QR). The pinned key is immutable once set — a profile advertising a different key
 * for the same nodeId is refused (it could only arise from a nodeId hash collision), so [verified]
 * stays bound to that key and is never silently inherited by a swapped-in key.
 *
 * [deviceTag] is the peer's key-independent device tag (see [org.lattice.identity.DeviceTag]),
 * used only to keep a block sticky when the peer regenerates its key (and thus its nodeId).
 *
 * [protoVersion]/[capabilities] are the peer's advertised protocol version and feature bits (see
 * [org.lattice.mesh.protocol.Protocol]), learned (authenticated) from their profile frame; null
 * until a profile carrying them arrives. Recorded for diagnostics; not yet consumed for routing.
 *
 * [phoneNumber] is the peer's number for the SMS/MMS carrier transport (E.164, e.g. `+15551234567`),
 * set only when the local user has explicitly attached one — nothing populates it from mesh discovery,
 * since a nodeId carries no phone number and one should never be inferred. Null means "mesh-only": that
 * transport has no route to this peer and skips it. Unlike [pubKey], attaching or changing a number is
 * not itself a trust event; the existing [verified] flag (safety number / QR, tied to [pubKey]) is what
 * carries authentication, and continues to gate it regardless of which transport a message rides.
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val name: String = "",
    val status: String = "",
    val avatarHash: String? = null,
    val pubKey: String? = null,
    val verified: Boolean = false,
    val deviceTag: String? = null,
    val protoVersion: Int? = null,
    val capabilities: Long? = null,
    val updatedAt: Long = 0L,
    val phoneNumber: String? = null,
)
