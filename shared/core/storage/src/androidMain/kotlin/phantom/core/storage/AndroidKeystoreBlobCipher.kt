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
 * Android-Keystore-backed [KeystoreBlobCipher] for the local prekey
 * private bytes (and any other small secret blob the storage layer
 * later wants to wrap with the same key-property profile).
 *
 * Mirrors the [DatabasePassphraseManager] pattern: AES-256-GCM master
 * key generated under a stable alias inside the AndroidKeyStore
 * provider, never exported to userspace, used through the standard
 * JCE `Cipher` API. The wrap output layout is
 * `iv[12] || ciphertext[plaintext_len] || tag[16]` — self-contained,
 * versioned at a higher layer by [PrivateKeyStorageCodec]'s `v1:`
 * prefix.
 *
 * Key properties:
 *  - 256-bit AES under GCM with no padding (GCM handles its own
 *    framing). Same primitive the database-passphrase wrapper uses.
 *  - `setUnlockedDeviceRequired(true)` — the master key is only
 *    accessible when the device is in an unlocked state. This is the
 *    weaker tier (since-boot, not since-last-unlock) and is consistent
 *    with [DatabasePassphraseManager]. UX cost: an X3DH bootstrap on
 *    a brand-new incoming conversation while the phone is locked
 *    fails until the user next unlocks; subsequent messages in the
 *    same conversation use the Double Ratchet state and do not need
 *    the prekey wrap key.
 *  - Distinct alias `phantom_prekey_wrap_v1` — the prekey wrap is its
 *    own master key, not a reuse of the DB passphrase wrap. Two
 *    independent rotation paths, two independent KeyProperties policies
 *    going forward (e.g. a future hardening pass could add
 *    `setUserAuthenticationRequired(true)` on the prekey wrap without
 *    disturbing the database layer).
 *
 * The class is `internal` to the `:shared:core:storage` module —
 * production code constructs it through `AppContainer` and hands it
 * to the prekey repositories as a plain [KeystoreBlobCipher]; only
 * `AppContainer` knows the concrete type.
 *
 * Threading: each [wrap] / [unwrap] call obtains a fresh `Cipher`
 * instance (the JCE Cipher object is single-threaded by spec). The
 * underlying Keystore key handle is safe to look up concurrently via
 * `KeyStore.getInstance(...)`.
 */
internal class AndroidKeystoreBlobCipher : KeystoreBlobCipher {

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        // GCM in JCE returns ciphertext || tag concatenated. Layout
        // therefore: iv[12] || ciphertext[N] || tag[16].
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
        // on tamper / wrong key — that is the authenticated-encryption
        // contract from KeystoreBlobCipher.unwrap surfacing.
        return cipher.doFinal(ciphertextWithTag)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        ks.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "phantom_prekey_wrap_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val GCM_TAG_LEN_BITS = 128
        const val GCM_TAG_LEN_BYTES = GCM_TAG_LEN_BITS / 8
    }
}

/**
 * Public construction helper so `AppContainer` does not have to refer
 * to the `internal` class name. Keeps the security-critical concrete
 * type internal to this module.
 */
fun createAndroidPrekeyKeystoreCipher(): KeystoreBlobCipher = AndroidKeystoreBlobCipher()
