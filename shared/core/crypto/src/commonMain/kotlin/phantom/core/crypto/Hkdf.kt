// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

/**
 * RFC 5869 HKDF using SHA-256, exposed as a shared utility for crypto modules
 * that need a standard KDF primitive (X3DH root-key derivation, SenderKey
 * advance, etc.). Replaces ad-hoc `H(chainKey || tag)` constructions that fail
 * external review.
 *
 * libsodium's `Auth.authHmacSha256` is HMAC-SHA-256 directly; we use it for
 * both Extract and Expand phases.
 */
object Hkdf {
    private const val LIBSODIUM_HMAC_SHA256_KEY_BYTES = 32

    /**
     * HKDF-SHA256 with output length L = 32 (single Expand block).
     *
     *   PRK = HMAC-SHA256(salt, ikm)
     *   OKM = HMAC-SHA256(PRK, info || 0x01)
     *
     * For L ≤ 32 bytes the Expand stage is a single HMAC call with counter
     * byte 0x01.
     */
    fun sha256L32(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        // The ionspin wrapper calls libsodium's fixed-width
        // crypto_auth_hmacsha256 primitive without passing a key length.
        // HMAC itself right-pads short keys with zeroes, so doing that here
        // preserves RFC 2104/5869 semantics while ensuring native code always
        // receives the 32 bytes it will read. Longer salts are rejected
        // explicitly instead of being silently truncated or read unsafely.
        require(salt.size <= LIBSODIUM_HMAC_SHA256_KEY_BYTES) {
            "HKDF-SHA256 salt exceeds the supported 32-byte HMAC key width"
        }
        val fixedSalt = ByteArray(LIBSODIUM_HMAC_SHA256_KEY_BYTES)
        salt.copyInto(fixedSalt)
        val prk = try {
            Hmac.sha256(key = fixedSalt, message = ikm)
        } finally {
            fixedSalt.zeroize()
        }
        val expandInput = info + byteArrayOf(0x01)
        return try {
            Hmac.sha256(key = prk, message = expandInput)
        } finally {
            prk.zeroize()
        }
    }
}
