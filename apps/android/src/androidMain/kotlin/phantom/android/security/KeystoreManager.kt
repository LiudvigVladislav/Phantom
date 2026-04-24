package phantom.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps Android Keystore AES-GCM encryption.
 * Used to encrypt DH private key before storing in SQLite.
 *
 * The AES key never leaves the secure hardware — only ciphertext is stored in DB.
 */
object KeystoreManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "phantom_identity_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // TODO Beta: add setUnlockedDeviceRequired(true) once we gate on
                // BiometricManager.canAuthenticate() at first launch — without a
                // secure lock screen the key generation throws InvalidAlgorithmParameterException.
                .build()
        )
        return keyGen.generateKey()
    }

    /** Returns iv + ciphertext as a single ByteArray (first 12 bytes = IV). */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv          // 12 bytes for GCM
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /** Expects iv + ciphertext format produced by encrypt(). */
    fun decrypt(ivAndCiphertext: ByteArray): ByteArray {
        val iv = ivAndCiphertext.copyOfRange(0, 12)
        val ciphertext = ivAndCiphertext.copyOfRange(12, ivAndCiphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Delete the Keystore key (called on Sign Out / identity destroy). */
    fun deleteKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }
}
