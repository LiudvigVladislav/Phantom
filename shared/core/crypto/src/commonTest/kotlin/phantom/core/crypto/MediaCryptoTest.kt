// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.hash.Hash
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [MediaCrypto].
 *
 * All tests call [LibsodiumInitializer.initialize] in [setup] — the JVM
 * target resolves this to the JNA-backed native libsodium, which runs in
 * commonTest without needing an Android device. Android instrumented tests
 * would also call initialize() but on the JNI-backed path.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class MediaCryptoTest {

    private lateinit var crypto: MediaCrypto

    @BeforeTest
    fun setup() {
        LibsodiumInitializer.initialize()
        crypto = MediaCrypto()
    }

    // ── round-trip ─────────────────────────────────────────────────────────────

    @Test
    fun encryptThenDecrypt_returnOriginalBytes() {
        val plainAudio = "hello phantom voice".encodeToByteArray()

        val result = crypto.encryptVoice(plainAudio)
        val recovered = crypto.decryptVoice(
            mediaId = result.mediaId,
            mediaKey = result.mediaKey,
            nonce = result.nonce,
            ciphertext = result.ciphertext,
        )

        assertTrue(plainAudio.contentEquals(recovered), "decrypted bytes do not match original")
    }

    @Test
    fun encryptThenDecrypt_largerBlob_roundTrips() {
        val plainAudio = ByteArray(22_000) { (it % 256).toByte() }

        val result = crypto.encryptVoice(plainAudio)
        val recovered = crypto.decryptVoice(
            mediaId = result.mediaId,
            mediaKey = result.mediaKey,
            nonce = result.nonce,
            ciphertext = result.ciphertext,
        )

        assertTrue(plainAudio.contentEquals(recovered))
    }

    // ── ciphertext size ────────────────────────────────────────────────────────

    @Test
    fun ciphertext_isPlainSizePlusTagBytes() {
        val plainAudio = ByteArray(1684) { 0x42 }
        val result = crypto.encryptVoice(plainAudio)
        val expectedCiphertextSize = plainAudio.size + MediaCrypto.TAG_BYTES
        assertTrue(
            result.ciphertext.size == expectedCiphertextSize,
            "ciphertext size ${result.ciphertext.size} != expected $expectedCiphertextSize"
        )
    }

    // ── sha256 ─────────────────────────────────────────────────────────────────

    @Test
    fun sha256InResult_matchesSha256OfInput() {
        val plainAudio = "voice note content for sha test".encodeToByteArray()
        val result = crypto.encryptVoice(plainAudio)

        val expected = Hash.sha256(plainAudio.toUByteArray()).toByteArray()
        assertTrue(
            result.plaintextSha256.contentEquals(expected),
            "sha256 mismatch in EncryptResult"
        )
    }

    // ── AAD binding — wrong mediaId fails authentication ───────────────────────

    @Test
    fun decryptWithWrongMediaId_throwsIllegalStateException() {
        val plainAudio = "aad binding test".encodeToByteArray()
        val result = crypto.encryptVoice(plainAudio)

        assertFailsWith<IllegalStateException> {
            crypto.decryptVoice(
                mediaId = "wrong-media-id-that-was-not-used-during-encryption",
                mediaKey = result.mediaKey,
                nonce = result.nonce,
                ciphertext = result.ciphertext,
            )
        }
    }

    // ── wrong key fails authentication ─────────────────────────────────────────

    @Test
    fun decryptWithWrongKey_throwsIllegalStateException() {
        val plainAudio = "wrong key test".encodeToByteArray()
        val result = crypto.encryptVoice(plainAudio)

        val wrongKey = ByteArray(MediaCrypto.KEY_BYTES) { 0xFF.toByte() }

        assertFailsWith<IllegalStateException> {
            crypto.decryptVoice(
                mediaId = result.mediaId,
                mediaKey = wrongKey,
                nonce = result.nonce,
                ciphertext = result.ciphertext,
            )
        }
    }

    // ── corrupted ciphertext fails authentication ──────────────────────────────

    @Test
    fun decryptWithCorruptedCiphertext_throwsIllegalStateException() {
        val plainAudio = "corrupted ciphertext test".encodeToByteArray()
        val result = crypto.encryptVoice(plainAudio)

        val corrupted = result.ciphertext.copyOf()
        corrupted[0] = (corrupted[0].toInt() xor 0xFF).toByte()

        assertFailsWith<IllegalStateException> {
            crypto.decryptVoice(
                mediaId = result.mediaId,
                mediaKey = result.mediaKey,
                nonce = result.nonce,
                ciphertext = corrupted,
            )
        }
    }

    // ── mediaId uniqueness ────────────────────────────────────────────────────

    @Test
    fun twoEncryptCalls_produceDistinctMediaIds() {
        val plainAudio = "uniqueness test".encodeToByteArray()
        val a = crypto.encryptVoice(plainAudio)
        val b = crypto.encryptVoice(plainAudio)

        assertTrue(a.mediaId != b.mediaId, "mediaId should be unique per call")
    }

    @Test
    fun twoEncryptCalls_produceDistinctCiphertexts() {
        val plainAudio = "determinism test".encodeToByteArray()
        val a = crypto.encryptVoice(plainAudio)
        val b = crypto.encryptVoice(plainAudio)

        // Different nonces → different ciphertexts, even for the same plaintext
        assertTrue(!a.ciphertext.contentEquals(b.ciphertext), "ciphertext must not be deterministic")
    }
}
