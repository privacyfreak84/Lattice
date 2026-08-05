package org.lattice.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ported from DresSecureComms (Apache-2.0 — see NOTICE.md) with the package updated; logic unchanged.
 *
 * Gates app entry behind an AndroidKeyStore-backed key that requires biometric/device-credential
 * authentication to use ([KeyGenParameterSpec.Builder.setUserAuthenticationRequired]) — the unlock
 * itself is the only thing this key is for, it never touches message content. "Enrolled" means a
 * marker value has been sealed with that key; unlocking means successfully unsealing it, which the
 * OS only allows after a fresh biometric/credential check. [reset] drops both the key and the sealed
 * marker, so a caller can silently re-enroll after [android.security.keystore.KeyPermanentlyInvalidatedException]
 * (e.g. the user added or removed a fingerprint) instead of getting stuck locked out.
 */
object AppLockManager {
    private const val TAG = "AppLockManager"
    private const val KEY_ALIAS = "lattice_unlock_key"
    private const val TOKEN_FILE = "applock.bin"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private val MARKER = byteArrayOf(0x4C, 0x41, 0x54, 0x2D, 0x4C, 0x4F, 0x43, 0x4B) // "LAT-LOCK"

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keystore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    fun isEnrolled(ctx: Context): Boolean = tokenFile(ctx).exists()

    fun newEncryptCipher(): Cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }

    fun newDecryptCipher(ctx: Context): Cipher {
        val (iv, _) = readToken(ctx)
        return Cipher
            .getInstance(TRANSFORM)
            .apply { init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
    }

    fun finishEnroll(
        ctx: Context,
        cipher: Cipher,
    ) {
        val ct = cipher.doFinal(MARKER)
        writeToken(ctx, cipher.iv, ct)
    }

    fun verifyUnlock(
        ctx: Context,
        cipher: Cipher,
    ): Boolean =
        try {
            val (_, ct) = readToken(ctx)
            cipher.doFinal(ct).contentEquals(MARKER)
        } catch (e: GeneralSecurityException) {
            // Wrong key, tampered/corrupted ciphertext, or a bad auth tag -- this *is* "not a valid
            // unlock", not a bug, but still worth a log line if it ever needs investigating.
            Log.w(TAG, "verifyUnlock: cipher rejected the sealed token", e)
            false
        } catch (e: IOException) {
            Log.w(TAG, "verifyUnlock: could not read the sealed token file", e)
            false
        } catch (e: IndexOutOfBoundsException) {
            Log.w(TAG, "verifyUnlock: sealed token file is truncated/corrupted", e)
            false
        }

    fun reset(ctx: Context) {
        runCatching { keystore().deleteEntry(KEY_ALIAS) }
        runCatching { tokenFile(ctx).delete() }
    }

    private fun tokenFile(ctx: Context) = File(ctx.filesDir, TOKEN_FILE)

    private fun writeToken(
        ctx: Context,
        iv: ByteArray,
        ct: ByteArray,
    ) {
        tokenFile(ctx).writeBytes(byteArrayOf(iv.size.toByte()) + iv + ct)
    }

    private fun readToken(ctx: Context): Pair<ByteArray, ByteArray> {
        val all = tokenFile(ctx).readBytes()
        val n = all[0].toInt()
        return all.copyOfRange(1, 1 + n) to all.copyOfRange(1 + n, all.size)
    }
}
