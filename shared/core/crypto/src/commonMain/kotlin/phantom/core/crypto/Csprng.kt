// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/**
 * Single source of cryptographically-secure pseudo-random values for
 * Trek 2 Stage 2 — Vladislav OQ7 lock 2026-06-09.
 *
 * Backed by `LibsodiumRandom.buf(...)` (the same primitive used today
 * by [SealedSender], [LibsodiumDoubleRatchet], [MediaCrypto], and
 * [SenderKey] for nonces, keys, and media id derivation). The helper
 * exists as a single, injectable interface so:
 *
 *   1. **Type-safety**: future call sites cannot accidentally reach
 *      for `kotlin.random.Random.Default` for security-sensitive
 *      values. The JVM `Random.Default` is a linear-congruential PRNG
 *      seeded at process start — adequate for non-security-critical
 *      shuffling, INadequate for `EnvelopeId.random()` (Stage 2A A5)
 *      and the long-poll jitter sites (Stage 2B B4). The security
 *      review (zones Z3 + Z8) flagged this explicitly.
 *
 *   2. **Test seam**: jitter and id-generation tests need deterministic
 *      randomness to assert distribution / uniqueness contracts. The
 *      interface lets `commonTest` swap in a seeded fake without
 *      reaching into libsodium internals.
 *
 *   3. **Single audit point**: a future cryptographer can grep
 *      `LibsodiumRandom` to find every primary RNG draw site in the
 *      app; before this helper they had to grep three modules and
 *      remember the difference between security-purpose and
 *      diagnostics-purpose randomness calls.
 *
 * STAGE 2A SCOPE (this commit)
 *
 * This commit introduces the interface + the production
 * `LibsodiumCsprng` object. No call site consumes it yet — A5
 * (`EnvelopeId.random()`) will be the first consumer in a follow-up
 * commit; B4 (Stage 2B jitter) will be the second. The Stage 2A
 * guardrail "no runtime behaviour change" holds because no existing
 * code path is rewired to go through this helper in this commit.
 */
interface Csprng {

    /**
     * Returns [byteCount] cryptographically-random bytes. Backed by
     * libsodium's `randombytes_buf` (`getrandom(2)` on Linux/Android;
     * BCryptGenRandom on Windows for the desktop targets).
     */
    fun bytes(byteCount: Int): ByteArray

    /**
     * Convenience — returns [byteCount] random bytes hex-encoded as
     * lowercase characters. Output length is `byteCount * 2`. Used by
     * Stage 2A A5 (`EnvelopeId.random()`) where the wire format is
     * already hex.
     */
    fun hex(byteCount: Int): String

    /**
     * Returns a uniformly-distributed `Long` in `[0, boundExclusive)`.
     *
     * Implementation uses **rejection sampling** so the bias is
     * mathematically zero, not just "negligible" as the original
     * modulo-based draft was. Vladislav PR #298 round-2 review P3
     * (2026-06-09): `uniformLong` is positioned as the single source
     * of bounded-random draws for every Trek 2 security-purpose RNG
     * site, and the name itself promises mathematical uniformity —
     * the helper now keeps that promise even for bounds approaching
     * `Long.MAX_VALUE` where modulo bias would otherwise be visible.
     *
     * Performance: rejection sampling re-draws when the underlying
     * 63-bit draw falls in the trailing partial bucket. For typical
     * Stage 2B jitter bounds (hold base 0..5000 ms, next-request
     * 200..1000 ms) the rejection probability is `≤ boundExclusive /
     * Long.MAX_VALUE` ≈ `5 × 10⁻¹⁶`, so the expected call cost is
     * one libsodium draw and an integer divide.
     *
     * The worst-case rejection is ~50% and occurs when `boundExclusive`
     * sits just above half of the 63-bit positive space (around
     * `Long.MAX_VALUE / 2 + 1`): there the floor division
     * `Long.MAX_VALUE / boundExclusive` collapses from 2 down to 1, so
     * `maxValid = boundExclusive` covers only the lower half of the
     * draw space. Bounds at or very near `Long.MAX_VALUE` itself, by
     * contrast, give `maxValid = boundExclusive` over nearly the full
     * 63-bit space, with rejection probability close to zero. Even at
     * the ~50% point the loop terminates almost surely.
     *
     * Throws on `boundExclusive <= 0`.
     */
    fun uniformLong(boundExclusive: Long): Long
}

/**
 * Production [Csprng] implementation backed by libsodium.
 *
 * Consumers should depend on the [Csprng] interface, not this object
 * directly — `commonTest` can then substitute a seeded `Random`-backed
 * fake without touching libsodium initialisation in tests.
 */
object LibsodiumCsprng : Csprng {

    override fun bytes(byteCount: Int): ByteArray {
        require(byteCount > 0) { "byteCount must be positive, was $byteCount" }
        return LibsodiumRandom.buf(byteCount).toByteArray()
    }

    override fun hex(byteCount: Int): String =
        bytes(byteCount).joinToString("") { byte ->
            // Lowercase hex — matches the codebase convention used by
            // SenderKey.toHexString and existing identity-hex formats.
            "%02x".format(byte.toInt() and 0xFF)
        }

    override fun uniformLong(boundExclusive: Long): Long {
        require(boundExclusive > 0) {
            "boundExclusive must be positive, was $boundExclusive"
        }
        // Rejection-sampling threshold per PR #298 round-2 review P3
        // (Vladislav 2026-06-09): after the strip-sign mask below,
        // the draw inhabits `[0, Long.MAX_VALUE]`. Define `maxValid`
        // as the largest multiple of `boundExclusive` that fits in
        // that range — `[0, maxValid)` is then perfectly partitioned
        // into equal-size buckets of width `boundExclusive`, so
        // `value % boundExclusive` is provably unbiased on accepted
        // draws. Values in `[maxValid, Long.MAX_VALUE]` sit in the
        // trailing partial bucket; rejecting them removes the modulo
        // bias entirely.
        //
        // For typical Stage 2 bounds (≤ 60_000 ms) the rejection
        // probability is ≤ `boundExclusive / Long.MAX_VALUE` =
        // ~6.5 × 10⁻¹⁵, indistinguishable from a single-draw path
        // in practice. The worst-case rejection (~50%) sits at
        // `boundExclusive ≈ Long.MAX_VALUE/2 + 1`, where the floor
        // division collapses from 2 to 1; bounds at or near
        // `Long.MAX_VALUE` itself are close to 0% rejection. See
        // the KDoc for the full breakdown.
        val maxValid = (Long.MAX_VALUE / boundExclusive) * boundExclusive
        while (true) {
            val raw = bytes(Long.SIZE_BYTES)
            var value = 0L
            for (b in raw) {
                value = (value shl 8) or (b.toLong() and 0xFF)
            }
            // Strip the sign bit so the result is unambiguously
            // non-negative on every JVM implementation.
            value = value and Long.MAX_VALUE
            if (value < maxValid) {
                return value % boundExclusive
            }
            // else re-draw — see KDoc + inline comment for the
            // (vanishing) probability budget.
        }
    }
}
