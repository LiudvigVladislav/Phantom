// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [PrivateKeyStorageCodec] — the on-disk encoding the
 * F22 prekey-wrap landed in. Two cipher behaviours are exercised:
 *
 *  - [IdentityCipher] — pass-through. Lets us assert the codec wires
 *    cleanly even when the wrap is a no-op (the round-trip property
 *    still has to hold).
 *  - [SequentialCipher] — an in-test deterministic cipher that does
 *    NOT pass through (it XORs every byte with a fixed key). Lets us
 *    assert the stored value is genuinely the cipher's output, not
 *    just the original hex.
 *
 * The Android-Keystore-backed cipher itself is exercised in an
 * `androidInstrumentedTest` (lands in F22 PR-2) because Robolectric
 * cannot fake the Keystore provider end-to-end.
 */
class PrivateKeyStorageCodecTest {

    private val sampleHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    private val emptyHex = ""

    /** A non-trivial deterministic cipher that lets us assert the codec actually wraps. */
    private class XorCipher(private val key: Byte = 0x5A) : KeystoreBlobCipher {
        override fun wrap(plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size) { i -> (plaintext[i].toInt() xor key.toInt()).toByte() }
        override fun unwrap(wrappedBlob: ByteArray): ByteArray = wrap(wrappedBlob) // XOR is its own inverse
    }

    // ── Round-trip ───────────────────────────────────────────────────────────

    @Test
    fun encode_then_decode_returns_original_hex_with_identity_cipher() {
        val stored = PrivateKeyStorageCodec.encodeForStorage(sampleHex, IdentityCipher)
        val recovered = PrivateKeyStorageCodec.decodeFromStorage(stored, IdentityCipher)
        assertEquals(sampleHex, recovered)
    }

    @Test
    fun encode_then_decode_returns_original_hex_with_xor_cipher() {
        val cipher = XorCipher()
        val stored = PrivateKeyStorageCodec.encodeForStorage(sampleHex, cipher)
        val recovered = PrivateKeyStorageCodec.decodeFromStorage(stored, cipher)
        assertEquals(sampleHex, recovered)
    }

    @Test
    fun encode_then_decode_handles_empty_plaintext() {
        val stored = PrivateKeyStorageCodec.encodeForStorage(emptyHex, IdentityCipher)
        val recovered = PrivateKeyStorageCodec.decodeFromStorage(stored, IdentityCipher)
        assertEquals(emptyHex, recovered)
    }

    // ── Wrapping is observable ──────────────────────────────────────────────

    @Test
    fun stored_value_carries_v1_prefix() {
        val stored = PrivateKeyStorageCodec.encodeForStorage(sampleHex, IdentityCipher)
        assertTrue(stored.startsWith("v1:"), "stored value must begin with the v1 marker; got: $stored")
    }

    @Test
    fun stored_value_is_not_the_raw_hex_when_cipher_actually_wraps() {
        val cipher = XorCipher()
        val stored = PrivateKeyStorageCodec.encodeForStorage(sampleHex, cipher)
        assertNotEquals(sampleHex, stored, "stored value must not be the raw hex when wrapping")
        assertTrue(stored.startsWith("v1:"))
    }

    // ── Legacy fallback ─────────────────────────────────────────────────────

    @Test
    fun decode_treats_unprefixed_value_as_legacy_raw_hex() {
        // Simulate a row written before F22 landed: the column value is
        // the plain hex, no v1: marker. The codec must accept it and
        // return it unchanged.
        val recovered = PrivateKeyStorageCodec.decodeFromStorage(sampleHex, IdentityCipher)
        assertEquals(sampleHex, recovered)
    }

    @Test
    fun decode_legacy_does_not_invoke_cipher() {
        // CountingCipher records whether unwrap was called. Legacy
        // values must not invoke the cipher because doing so would
        // turn benign legacy-hex into a hex-byte-array and try to
        // decrypt it as a wrapped blob, which would either fail
        // (good — but loud) or silently return garbage (bad).
        var unwrapCount = 0
        val countingCipher = object : KeystoreBlobCipher {
            override fun wrap(plaintext: ByteArray) = plaintext
            override fun unwrap(wrappedBlob: ByteArray): ByteArray {
                unwrapCount++; return wrappedBlob
            }
        }
        val recovered = PrivateKeyStorageCodec.decodeFromStorage(sampleHex, countingCipher)
        assertEquals(sampleHex, recovered)
        assertEquals(0, unwrapCount, "legacy path must skip the cipher entirely")
    }

    // ── Tamper detection ────────────────────────────────────────────────────

    @Test
    fun decode_propagates_cipher_tamper_exception() {
        // A wrapped value whose ciphertext bytes have been flipped
        // must surface the underlying cipher's exception, not return
        // garbage. Identity cipher cannot tamper-detect, so we use a
        // strict cipher that throws on any mismatch.
        val strictCipher = object : KeystoreBlobCipher {
            override fun wrap(plaintext: ByteArray) = plaintext + 0xCA.toByte()
            override fun unwrap(wrappedBlob: ByteArray): ByteArray {
                require(wrappedBlob.isNotEmpty() && wrappedBlob.last() == 0xCA.toByte()) {
                    "tag check failed"
                }
                return wrappedBlob.copyOfRange(0, wrappedBlob.size - 1)
            }
        }
        val stored = PrivateKeyStorageCodec.encodeForStorage(sampleHex, strictCipher)
        val tampered = stored.dropLast(4) + "AAAA"  // mutate the base64 tail
        assertFails {
            PrivateKeyStorageCodec.decodeFromStorage(tampered, strictCipher)
        }
    }
}
