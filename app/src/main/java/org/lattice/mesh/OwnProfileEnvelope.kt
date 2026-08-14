package org.lattice.mesh

import kotlinx.coroutines.flow.first
import org.lattice.TextLimits
import org.lattice.data.settings.SettingsStore
import org.lattice.identity.Identity
import org.lattice.mesh.crypto.MessageCrypto
import org.lattice.mesh.protocol.FrameType
import org.lattice.mesh.protocol.ProfileContent
import org.lattice.mesh.protocol.Protocol
import org.lattice.mesh.protocol.RelayEnvelope
import org.lattice.mesh.protocol.WireCodec
import org.lattice.mesh.protocol.WireEnvelope
import org.lattice.normalizeSingleLine

/**
 * Builds and signs this device's own [FrameType.PROFILE] frame — the single source of truth for what our
 * profile looks like on the wire, shared by [MeshManager] (which floods it to every mesh neighbor) and the
 * SMS-only-contact bootstrap flow (which sends it point-to-point to one phone number on explicit user
 * accept — see `.agents/context/sms-transport.md`, batch 5). Extracted from what used to be private
 * `MeshManager` logic (`currentProfileEnvelope()`/`sign()`) so the two paths can't drift on which fields
 * a profile carries; [sign] itself (CBOR-encode + raw Ed25519 signature) is a trivial, frozen-forever wire primitive
 * (see [org.lattice.mesh.protocol.WireEnvelope]'s doc) that MeshManager still keeps its own copy of for
 * signing non-profile frame types, so it isn't pulled through here.
 */
class OwnProfileEnvelope(
    private val identity: Identity,
    private val settings: SettingsStore,
    private val messageCrypto: MessageCrypto,
) {
    /**
     * The current profile as an unsigned [RelayEnvelope]. `id`/`sentAt` are derived from the persisted
     * `settings.profileVersion`, not the wall clock, so an unchanged profile re-broadcasts as the *same*
     * custodied frame across restarts instead of a new one (see [MeshManager.pushProfileTo]'s custody
     * comment for why that matters for store-and-forward convergence).
     */
    suspend fun current(): RelayEnvelope {
        val me = identity.nodeId()
        val version = settings.profileVersion.first()
        val content =
            ProfileContent(
                // Normalize/cap defensively: covers legacy values stored before the field gained a cap and
                // the rare process-death-before-the-blur-commit case, so peers never receive an oversized name.
                name = normalizeSingleLine(settings.displayName.first()).take(TextLimits.DISPLAY_NAME),
                status = normalizeSingleLine(settings.status.first()).take(TextLimits.STATUS),
                avatarHash = settings.ownAvatarHash.first(),
                pubKey = identity.publicKeyBundle(),
                deviceTag = identity.deviceTag(),
                protoVersion = Protocol.VERSION,
                capabilities = Protocol.LOCAL_CAPABILITIES,
            )
        return RelayEnvelope(
            type = FrameType.PROFILE,
            id = "profile-$me-$version",
            senderId = me,
            sentAt = version,
            payload = WireCodec.encodePayload(content),
        )
    }

    /** Wraps [env] in a signed [WireEnvelope]: the canonical bytes plus our raw Ed25519 signature over them. */
    fun sign(
        env: RelayEnvelope,
        relay: Boolean = true,
    ): WireEnvelope {
        val signed = WireCodec.encodeEnvelope(env)
        return WireEnvelope(relay = relay, sig = messageCrypto.signRaw(signed), signed = signed)
    }

    /** Convenience for a caller that only needs the signed frame, not the intermediate [RelayEnvelope]. */
    suspend fun signed(): WireEnvelope = sign(current())
}
