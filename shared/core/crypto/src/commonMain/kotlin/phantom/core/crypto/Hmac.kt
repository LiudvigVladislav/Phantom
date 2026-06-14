// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.auth.Auth

/**
 * Public HMAC-SHA-256 wrapper around the libsodium binding.
 *
 * Exposed from `:shared:core:crypto` so cross-module consumers (e.g.
 * `:shared:core:transport`'s `SeqMacVerifier`) can compute HMAC-SHA-256
 * without taking a direct compile-time dependency on the libsodium
 * Kotlin bindings.
 *
 * Trek 2 Stage 2B-B (L1 + L5): `SeqMacVerifier` calls [sha256] with
 * the canonical input bytes (101 + envelope_id_byte_length) and the
 * 32-byte raw verify key. The implementation routes to libsodium's
 * `Auth.authHmacSha256`, the same primitive used by [Hkdf]; we keep
 * the wrapper thin so any future migration to a different HMAC
 * primitive (e.g. a pure-Kotlin fallback for non-libsodium targets)
 * lands behind this single interface.
 */
object Hmac {

    /** Output size in bytes (HMAC-SHA-256 emits 32 bytes). */
    const val SHA256_OUTPUT_BYTES: Int = 32

    /**
     * Compute `HMAC-SHA-256(key, message)`.
     *
     * @param key   the HMAC key. Width is unconstrained at the
     *   primitive boundary (RFC 2104 allows any key length and pads
     *   internally); callers that need a specific width must enforce
     *   it themselves.
     * @param message the message bytes to authenticate.
     * @return the 32-byte HMAC-SHA-256 output.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun sha256(key: ByteArray, message: ByteArray): ByteArray =
        Auth.authHmacSha256(
            message = message.toUByteArray(),
            key     = key.toUByteArray(),
        ).toByteArray()
}
