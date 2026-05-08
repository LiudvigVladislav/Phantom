// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [RatchetStateStorageCodec].
 *
 * Exercises the same two cipher behaviours as [PrivateKeyStorageCodecTest]:
 *  - [IdentityCipher] — pass-through; asserts the codec wires cleanly.
 *  - [XorCipher] — deterministic non-trivial transform; asserts the
 *    stored value is genuinely the cipher's output.
 *
 * The Android-Keystore-backed cipher is exercised in the instrumented
 * test suite (requires a real device / emulator).
 */
class RatchetStateStorageCodecTest {

    private val sampleJson = """{"rootKey":"AAEC","sendingChainKey":"BAUG","sendCount":3}"""

    private class XorCipher(private val key: Byte = 0x5A) : KeystoreBlobCipher {
        override fun wrap(plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size) { i -> (plaintext[i].toInt() xor key.toInt()).toByte() }
        override fun unwrap(wrappedBlob: ByteArray): ByteArray = wrap(wrappedBlob)
    }

    @Test
    fun encode_then_decode_with_identity_cipher_round_trips() {
        val encoded = RatchetStateStorageCodec.encodeForStorage(sampleJson, IdentityCipher)
        val decoded = RatchetStateStorageCodec.decodeFromStorage(encoded, IdentityCipher)
        assertEquals(sampleJson, decoded)
    }

    @Test
    fun encoded_value_carries_rs1_prefix() {
        val encoded = RatchetStateStorageCodec.encodeForStorage(sampleJson, IdentityCipher)
        assertTrue(encoded.startsWith("rs1:"),
            "encoded blob must start with 'rs1:'; got: ${encoded.take(10)}…")
    }

    @Test
    fun encode_then_decode_with_xor_cipher_round_trips() {
        val cipher = XorCipher()
        val encoded = RatchetStateStorageCodec.encodeForStorage(sampleJson, cipher)
        val decoded = RatchetStateStorageCodec.decodeFromStorage(encoded, cipher)
        assertEquals(sampleJson, decoded)
    }

    @Test
    fun stored_value_with_xor_cipher_differs_from_plaintext() {
        val cipher = XorCipher()
        val encoded = RatchetStateStorageCodec.encodeForStorage(sampleJson, cipher)
        assertNotEquals(sampleJson, encoded,
            "cipher must transform the blob — stored form must not equal plaintext JSON")
    }

    @Test
    fun legacy_plaintext_row_decoded_without_cipher_call() {
        val spy = object : KeystoreBlobCipher {
            var unwrapCalled = false
            override fun wrap(plaintext: ByteArray) = plaintext
            override fun unwrap(wrappedBlob: ByteArray): ByteArray {
                unwrapCalled = true
                return wrappedBlob
            }
        }
        val decoded = RatchetStateStorageCodec.decodeFromStorage(sampleJson, spy)
        assertEquals(sampleJson, decoded, "legacy row must decode to the same JSON")
        assertEquals(false, spy.unwrapCalled, "cipher.unwrap must not be called for legacy rows")
    }

    @Test
    fun tampered_rs1_blob_propagates_cipher_exception() {
        val cipher = XorCipher()
        val encoded = RatchetStateStorageCodec.encodeForStorage(sampleJson, cipher)
        val tamperCipher = object : KeystoreBlobCipher {
            override fun wrap(plaintext: ByteArray) = cipher.wrap(plaintext)
            override fun unwrap(wrappedBlob: ByteArray): ByteArray =
                throw SecurityException("tampered")
        }
        assertFails {
            RatchetStateStorageCodec.decodeFromStorage(encoded, tamperCipher)
        }
    }
}
