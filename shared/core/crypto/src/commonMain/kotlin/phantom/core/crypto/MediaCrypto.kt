// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Symmetric AEAD wrapper for media blobs (voice notes, future attachments).
 *
 * Cipher: XChaCha20-Poly1305-IETF (libsodium crypto_aead_xchacha20poly1305_ietf_*).
 *   Key:   32 bytes
 *   Nonce: 24 bytes (extended — safe for random generation without nonce-reuse risk)
 *   Tag:   16 bytes appended to ciphertext
 *
 * AAD binding:
 *   AAD = mediaId bytes (UTF-8). Ciphertext is bound to the capability token
 *   that grants access to a specific media_id on the relay. A chunk from
 *   media_id "A" cannot authenticate under media_id "B" — relay-side chunk
 *   swap attacks fail at the AEAD tag verification step on the receiver.
 *
 * This class has no platform-specific code. The ionspin
 * AuthenticatedEncryptionWithAssociatedData object is multiplatform
 * and delegates to libsodium's native binding on each target.
 *
 * Usage: call LibsodiumInitializer.initialize() once before any method.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
class MediaCrypto {

    /** Result of a single [encryptVoice] call. */
    data class EncryptResult(
        /** base64url(32 random bytes) — unique media identity, used as AEAD AAD. */
        val mediaId: String,
        /** 32-byte XChaCha20 symmetric key. Caller embeds this in the manifest. */
        val mediaKey: ByteArray,
        /** 24-byte random nonce. Caller embeds this in the manifest. */
        val nonce: ByteArray,
        /** Ciphertext including the 16-byte Poly1305 authentication tag. */
        val ciphertext: ByteArray,
        /** SHA-256 of the original plaintext audio — receiver verifies after decrypt. */
        val plaintextSha256: ByteArray,
    )

    /**
     * Encrypts [plainAudio] with a freshly generated key, nonce, and mediaId.
     * Returns all material the sender needs to build [VoiceManifestV2].
     */
    fun encryptVoice(plainAudio: ByteArray): EncryptResult {
        val key = LibsodiumRandom.buf(KEY_BYTES).toByteArray()
        val nonce = LibsodiumRandom.buf(NONCE_BYTES).toByteArray()
        val mediaId = generateMediaId()

        val ciphertext = encryptWithAad(
            plaintext = plainAudio,
            key = key,
            nonce = nonce,
            aad = mediaId.encodeToByteArray(),
        )

        val sha256 = Hash.sha256(plainAudio.toUByteArray()).toByteArray()

        return EncryptResult(
            mediaId = mediaId,
            mediaKey = key,
            nonce = nonce,
            ciphertext = ciphertext,
            plaintextSha256 = sha256,
        )
    }

    /**
     * Decrypts a ciphertext produced by [encryptVoice].
     *
     * [mediaId] MUST be the same value used during encryption — it is
     * the AEAD additional data. Passing a different mediaId causes tag
     * verification to fail and this function throws [IllegalStateException].
     *
     * @throws IllegalStateException when the authentication tag fails
     *   (wrong key, wrong nonce, wrong mediaId, or corrupted ciphertext).
     */
    fun decryptVoice(
        mediaId: String,
        mediaKey: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        return try {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                ciphertextAndTag = ciphertext.toUByteArray(),
                associatedData = mediaId.encodeToByteArray().toUByteArray(),
                nonce = nonce.toUByteArray(),
                key = mediaKey.toUByteArray(),
            ).toByteArray()
        } catch (e: Exception) {
            throw IllegalStateException("Media AEAD tag verification failed: ${e.message}", e)
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun encryptWithAad(
        plaintext: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        return AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            message = plaintext.toUByteArray(),
            associatedData = aad.toUByteArray(),
            nonce = nonce.toUByteArray(),
            key = key.toUByteArray(),
        ).toByteArray()
    }

    /** Generates a base64url-encoded 32-byte random media identifier. */
    private fun generateMediaId(): String {
        val raw = LibsodiumRandom.buf(MEDIA_ID_BYTES).toByteArray()
        // base64url — no padding, url-safe alphabet
        return Base64.UrlSafe.encode(raw).trimEnd('=')
    }

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val MEDIA_ID_BYTES = 32
        /** Size of the Poly1305 authentication tag appended to every ciphertext. */
        const val TAG_BYTES = 16
    }
}
