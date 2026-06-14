// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.crypto

/**
 * Trek 2 Stage 2B-B (L1) — constant-time equality of two [ByteArray]
 * values.
 *
 * Time taken is independent of the position of the first differing
 * byte, given equal lengths: every byte is XOR-accumulated into a
 * single accumulator and the result is checked once at the end. The
 * loop bound is the input length, never short-circuited on a
 * mismatching byte.
 *
 * A length mismatch returns `false` before the loop runs. The lengths
 * of the inputs are NOT secret in any code path that uses this helper
 * (the receiver knows the expected MAC width is 32 bytes), so the
 * early return does not leak.
 *
 * Used by [phantom.core.transport.SeqMacVerifier] to compare a wire-
 * decoded `seq_mac` (32 raw bytes after hex-decode) against the
 * locally-computed HMAC-SHA-256 output (32 raw bytes). A standard XOR-
 * accumulate-then-check-zero pattern is sufficient for the threat model
 * — the verifier does not run on attacker-controlled hot paths and the
 * messenger does not need timing-attack resistance below the
 * primitive-call level.
 *
 * NEVER compare hex `String` representations directly; both inputs are
 * hex-decoded to 32-byte `ByteArray` instances before this call. The
 * difference is load-bearing: comparing the 64-char ASCII hex values
 * with `String.equals` would (a) short-circuit on the first differing
 * character, leaking the matching prefix length through timing, and
 * (b) cost twice the byte count.
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or ((a[i].toInt() and 0xFF) xor (b[i].toInt() and 0xFF))
    }
    return diff == 0
}
