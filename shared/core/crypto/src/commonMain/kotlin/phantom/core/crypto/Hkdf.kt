// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.auth.Auth

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
    /**
     * HKDF-SHA256 with output length L = 32 (single Expand block).
     *
     *   PRK = HMAC-SHA256(salt, ikm)
     *   OKM = HMAC-SHA256(PRK, info || 0x01)
     *
     * For L ≤ 32 bytes the Expand stage is a single HMAC call with counter
     * byte 0x01.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun sha256L32(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = Auth.authHmacSha256(
            message = ikm.toUByteArray(),
            key     = salt.toUByteArray(),
        ).toByteArray()
        val expandInput = info + byteArrayOf(0x01)
        val okm = Auth.authHmacSha256(
            message = expandInput.toUByteArray(),
            key     = prk.toUByteArray(),
        ).toByteArray()
        prk.zeroize()
        return okm
    }
}
