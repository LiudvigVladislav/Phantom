// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates and persists a random 32-byte SQLCipher passphrase, encrypted with
 * an Android Keystore AES-256-GCM key that never leaves secure hardware.
 *
 * Layout in SharedPreferences: Base64(iv[12] || ciphertext[32+16])
 */
internal object DatabasePassphraseManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS    = "phantom_db_passphrase_key"
    private const val TRANSFORMATION    = "AES/GCM/NoPadding"
    private const val GCM_TAG_LEN       = 128
    private const val PREFS_NAME        = "phantom_db_secure"
    private const val PREFS_KEY         = "db_passphrase_enc"

    @Synchronized
    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREFS_KEY, null)

        if (stored != null) {
            val ivAndCipher = Base64.decode(stored, Base64.NO_WRAP)
            try {
                return decrypt(ivAndCipher)
            } catch (_: KeyPermanentlyInvalidatedException) {
                // Biometric/PIN change permanently invalidated the Keystore key.
                // The old passphrase is unrecoverable — wipe the entry and fall
                // through to generate a fresh one. History will be empty after this
                // but the app stays functional. AppContainer should show a one-time
                // warning to the user explaining the data loss.
                wipeEncryptedEntry(prefs)
            } catch (_: BadPaddingException) {
                // Pre-API-28 firmware: KeyPermanentlyInvalidatedException may surface
                // as BadPaddingException. Same recovery path applies.
                wipeEncryptedEntry(prefs)
            }
        }

        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encrypted  = encrypt(passphrase)
        prefs.edit()
            .putString(PREFS_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
        return passphrase
    }

    private fun wipeEncryptedEntry(prefs: SharedPreferences) {
        prefs.edit().remove(PREFS_KEY).apply()
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
        }
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
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv         = cipher.iv
        val ciphertext = cipher.doFinal(data)
        return iv + ciphertext
    }

    private fun decrypt(ivAndCipher: ByteArray): ByteArray {
        val iv         = ivAndCipher.copyOfRange(0, 12)
        val ciphertext = ivAndCipher.copyOfRange(12, ivAndCipher.size)
        val cipher     = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LEN, iv))
        return cipher.doFinal(ciphertext)
    }
}
