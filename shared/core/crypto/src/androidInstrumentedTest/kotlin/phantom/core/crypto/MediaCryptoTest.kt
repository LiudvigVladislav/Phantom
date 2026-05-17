// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.hash.Hash
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Instrumented tests for [MediaCrypto]. Runs on a connected Android device or
 * emulator — libsodium native binding ships in the test APK and the
 * `LibsodiumInitializer` suspend init loads it. Invoke with:
 *   ./gradlew :shared:core:crypto:connectedDebugAndroidTest
 */
@OptIn(ExperimentalUnsignedTypes::class)
@RunWith(AndroidJUnit4::class)
class MediaCryptoTest {

    @Test
    fun encryptThenDecrypt_returnOriginalBytes() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = MediaCrypto()
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
    fun encryptThenDecrypt_largerBlob_roundTrips() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = MediaCrypto()
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

    @Test
    fun ciphertext_isPlainSizePlusTagBytes() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = MediaCrypto()
        val plainAudio = ByteArray(1684) { 0x42 }
        val result = crypto.encryptVoice(plainAudio)
        val expectedCiphertextSize = plainAudio.size + MediaCrypto.TAG_BYTES
        assertTrue(
            result.ciphertext.size == expectedCiphertextSize,
            "ciphertext size ${result.ciphertext.size} != expected $expectedCiphertextSize",
        )
    }

    @Test
    fun sha256InResult_matchesSha256OfInput() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = MediaCrypto()
        val plainAudio = "voice note content for sha test".encodeToByteArray()
        val result = crypto.encryptVoice(plainAudio)

        val expected = Hash.sha256(plainAudio.toUByteArray()).toByteArray()
        assertTrue(
            result.plaintextSha256.contentEquals(expected),
            "sha256 mismatch in EncryptResult",
        )
    }

    @Test
    fun decryptWithWrongMediaId_throwsIllegalStateException() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = MediaCrypto()
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

    @Test
    fun decryptWithWrongKey_throwsIllegalStateException() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = MediaCrypto()
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

    @Test
    fun decryptWithCorruptedCiphertext_throwsIllegalStateException() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = MediaCrypto()
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

    @Test
    fun twoEncryptCalls_produceDistinctMediaIds() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = MediaCrypto()
        val plainAudio = "uniqueness test".encodeToByteArray()
        val a = crypto.encryptVoice(plainAudio)
        val b = crypto.encryptVoice(plainAudio)

        assertTrue(a.mediaId != b.mediaId, "mediaId should be unique per call")
    }

    @Test
    fun twoEncryptCalls_produceDistinctCiphertexts() = runTest {
        LibsodiumInitializer.initialize()
        val crypto = MediaCrypto()
        val plainAudio = "determinism test".encodeToByteArray()
        val a = crypto.encryptVoice(plainAudio)
        val b = crypto.encryptVoice(plainAudio)

        assertTrue(!a.ciphertext.contentEquals(b.ciphertext), "ciphertext must not be deterministic")
    }
}
