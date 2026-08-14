package org.lattice.mesh

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lattice.TextLimits
import org.lattice.data.settings.SettingsStore
import org.lattice.identity.Identity
import org.lattice.identity.NodeId
import org.lattice.mesh.crypto.MessageCrypto
import org.lattice.mesh.crypto.PublicKeyBundle
import org.lattice.mesh.crypto.TinkInit
import org.lattice.mesh.protocol.FrameType
import org.lattice.mesh.protocol.ProfileContent
import org.lattice.mesh.protocol.RelayEnvelope
import org.lattice.mesh.protocol.WireCodec
import org.lattice.normalizeSingleLine

/**
 * Extracted from what used to be private [MeshManager] logic (`currentProfileEnvelope`/`sign`), so this
 * pins the actual behavior MeshManager relied on, not just the new call site — a real Tink keypair (same
 * pattern as MeshManagerTest's `party()`) for [MessageCrypto], mocked [Identity]/[SettingsStore] for the
 * profile fields.
 */
class OwnProfileEnvelopeTest {
    private fun crypto(): Pair<MessageCrypto, PublicKeyBundle> {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519_RAW"))
        return MessageCrypto(hybrid, sig) to PublicKeyBundle.fromPrivate(hybrid, sig)
    }

    private fun identity(
        bundle: PublicKeyBundle,
        nodeId: String = NodeId.fromPublicKeyBundle(bundle.encoded),
        deviceTag: String? = "tag-1",
    ): Identity {
        val id = mockk<Identity>()
        coEvery { id.nodeId() } returns nodeId
        every { id.publicKeyBundle() } returns bundle.encoded
        every { id.deviceTag() } returns deviceTag
        return id
    }

    private fun settings(
        displayName: String = "Ada",
        status: String = "hi",
        avatarHash: String? = "av-hash",
        profileVersion: Long = 7L,
    ): SettingsStore {
        val s = mockk<SettingsStore>()
        every { s.displayName } returns flowOf(displayName)
        every { s.status } returns flowOf(status)
        every { s.ownAvatarHash } returns flowOf(avatarHash)
        every { s.profileVersion } returns flowOf(profileVersion)
        return s
    }

    @Test
    fun `current builds a self-consistent PROFILE envelope from identity and settings`() =
        runTest {
            val (crypto, bundle) = crypto()
            val id = identity(bundle)
            val own = OwnProfileEnvelope(id, settings(), crypto)

            val env = own.current()

            val nodeId = NodeId.fromPublicKeyBundle(bundle.encoded)
            assertEquals(FrameType.PROFILE, env.type)
            assertEquals("profile-$nodeId-7", env.id)
            assertEquals(nodeId, env.senderId)
            assertEquals(7L, env.sentAt)
            val content = WireCodec.decodePayload<ProfileContent>(env.payload)!!
            assertEquals("Ada", content.name)
            assertEquals("hi", content.status)
            assertEquals("av-hash", content.avatarHash)
            assertEquals(bundle.encoded, content.pubKey)
            assertEquals("tag-1", content.deviceTag)
        }

    @Test
    fun `current normalizes and caps an overlong or whitespace-mangled name and clears a null avatar`() =
        runTest {
            val (crypto, bundle) = crypto()
            val id = identity(bundle, deviceTag = null)
            val overlongName = "  A".repeat(20) + "  " // trims/collapses to well over DISPLAY_NAME chars
            val own = OwnProfileEnvelope(id, settings(displayName = overlongName, avatarHash = null), crypto)

            val content = WireCodec.decodePayload<ProfileContent>(own.current().payload)!!

            assertTrue(content.name.length <= TextLimits.DISPLAY_NAME)
            assertEquals(normalizeSingleLine(overlongName).take(TextLimits.DISPLAY_NAME), content.name)
            assertNull(content.avatarHash)
            assertNull(content.deviceTag)
        }

    @Test
    fun `sign produces a WireEnvelope whose signed bytes decode back to the same RelayEnvelope`() {
        val (crypto, bundle) = crypto()
        val own = OwnProfileEnvelope(identity(bundle), settings(), crypto)
        val env = RelayEnvelope(type = FrameType.PROFILE, id = "profile-x-1", senderId = "x", payload = ByteArray(0))

        val wire = own.sign(env)

        assertTrue(wire.relay)
        assertArrayEquals(WireCodec.encodeEnvelope(env), wire.signed)
        val decoded = WireCodec.decodeEnvelope(wire.signed)!!
        assertEquals(env.id, decoded.id)
        assertEquals(env.senderId, decoded.senderId)
    }

    @Test
    fun `sign honors relay = false for a point-to-point frame`() {
        val (crypto, bundle) = crypto()
        val own = OwnProfileEnvelope(identity(bundle), settings(), crypto)
        val env = RelayEnvelope(type = FrameType.PROFILE, id = "profile-x-1", senderId = "x", payload = ByteArray(0))

        val wire = own.sign(env, relay = false)

        assertFalse(wire.relay)
    }

    @Test
    fun `signed builds and signs current() in one call`() =
        runTest {
            val (crypto, bundle) = crypto()
            val id = identity(bundle)
            val own = OwnProfileEnvelope(id, settings(), crypto)

            val wire = own.signed()

            val decoded = WireCodec.decodeEnvelope(wire.signed)!!
            assertEquals(FrameType.PROFILE, decoded.type)
            assertEquals(NodeId.fromPublicKeyBundle(bundle.encoded), decoded.senderId)
        }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
    }
}
