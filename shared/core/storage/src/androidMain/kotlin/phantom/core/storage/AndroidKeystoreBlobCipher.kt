// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-Keystore-backed [KeystoreBlobCipher] for secret blobs the
 * storage layer must persist locally.
 *
 * Mirrors the [DatabasePassphraseManager] pattern: AES-256-GCM master
 * key generated under a stable alias inside the AndroidKeyStore
 * provider, never exported to userspace, used through the standard
 * JCE `Cipher` API. The wrap output layout is
 * `iv[12] || ciphertext[plaintext_len] || tag[16]` — self-contained,
 * versioned at a higher layer by the caller's prefix convention.
 *
 * Two production aliases are in use:
 *  - `phantom_prekey_wrap_v1` — local prekey private bytes (SPK/OPK).
 *    Created with `setUnlockedDeviceRequired(true)`: prekey operations
 *    (bootstrap, weekly rotate) happen while the user is active.
 *  - `phantom_ratchet_wrap_v1` — Double Ratchet session state.
 *    Created WITHOUT `setUnlockedDeviceRequired`: ratchet state is
 *    written on every incoming message including background push; if
 *    the key were locked-device-only, a background receive would break
 *    the session by failing to persist the updated state.
 *
 * Threading: each [wrap] / [unwrap] call obtains a fresh `Cipher`
 * instance. The key handle is cached lazily after first lookup.
 */
internal class AndroidKeystoreBlobCipher(
    private val keystoreAlias: String,
    private val requireUnlockedDevice: Boolean = true,
) : KeystoreBlobCipher {

    private var cachedKey: SecretKey? = null

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        // GCM in JCE returns ciphertext || tag concatenated. Layout:
        // iv[12] || ciphertext[N] || tag[16].
        return iv + ciphertext
    }

    override fun unwrap(wrappedBlob: ByteArray): ByteArray {
        require(wrappedBlob.size >= IV_LEN + GCM_TAG_LEN_BYTES) {
            "wrapped blob too short to contain iv + tag (got ${wrappedBlob.size})"
        }
        val iv = wrappedBlob.copyOfRange(0, IV_LEN)
        val ciphertextWithTag = wrappedBlob.copyOfRange(IV_LEN, wrappedBlob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
        }
        // doFinal verifies the GCM tag and throws AEADBadTagException
        // on tamper / wrong key.
        return cipher.doFinal(ciphertextWithTag)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(keystoreAlias, null) as? SecretKey
        if (existing != null) {
            cachedKey = existing
            return existing
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val specBuilder = KeyGenParameterSpec.Builder(
            keystoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (requireUnlockedDevice) specBuilder.setUnlockedDeviceRequired(true)
        gen.init(specBuilder.build())
        return gen.generateKey().also { cachedKey = it }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val GCM_TAG_LEN_BITS = 128
        const val GCM_TAG_LEN_BYTES = GCM_TAG_LEN_BITS / 8
    }
}

/**
 * Cipher for local prekey private bytes (SPK / OPK).
 * Key requires device to be unlocked — prekey operations happen while
 * the user is actively using the app.
 */
fun createAndroidPrekeyKeystoreCipher(): KeystoreBlobCipher =
    AndroidKeystoreBlobCipher(
        keystoreAlias = "phantom_prekey_wrap_v1",
        requireUnlockedDevice = true,
    )

/**
 * Cipher for Double Ratchet session state blobs.
 * Key does NOT require unlocked device — ratchet state is written on
 * every incoming message, including background delivery while the
 * screen is off. Requiring unlock here would break session state on
 * any background receive.
 */
fun createAndroidRatchetKeystoreCipher(): KeystoreBlobCipher =
    AndroidKeystoreBlobCipher(
        keystoreAlias = "phantom_ratchet_wrap_v1",
        requireUnlockedDevice = false,
    )
