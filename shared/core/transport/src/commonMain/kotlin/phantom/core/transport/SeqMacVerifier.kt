// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import phantom.core.crypto.Hmac
import phantom.core.crypto.constantTimeEquals

/**
 * Trek 2 Stage 2B-B (L1 + L5) — client-side HMAC-SHA-256 verifier for
 * the `seq_mac` integrity tag attached to each `PollEnvelope` over the
 * REST poll path.
 *
 * Single source of truth for the canonical input encoding mirrored
 * verbatim from `services/relay/src/seq_mac.rs` (lines 155-164 at scope
 * time, master `f8cdc91a`). A change to either side without a matching
 * change here is wire-incompatible and the M8 / M-B7 vectors surface
 * the drift at test time.
 *
 * Canonical input layout (total length = 101 + envelope_id_byte_length):
 *
 * ```text
 * SEQ_MAC_DOMAIN_TAG             19 bytes  b"phantom-seq-mac-v1\x00" (ASCII + NUL)
 * identity_hex                   64 bytes  lowercase ASCII (receiving identity)
 * seq                             8 bytes  u64 big-endian
 * envelope_id_len                 2 bytes  u16 big-endian (UTF-8 BYTE length)
 * envelope_id_bytes          variable      exact UTF-8 bytes (no NUL, no padding)
 * sequence_ts                     8 bytes  u64 big-endian (post-quantize, 60s floor)
 * ```
 *
 * Encoding load-bearing points:
 *
 *   * Multi-byte integers are encoded via manual `ushr` bit shifts.
 *     `java.nio.ByteBuffer` is JVM-only and breaks the moment iOS
 *     targets land — the verifier sits in `commonMain` so it must
 *     stay buffer-free.
 *   * `envelope_id_len` is `envelopeId.encodeToByteArray().size`,
 *     i.e. the UTF-8 BYTE length. `String.length` returns UTF-16
 *     char count, which is wrong for any multi-byte UTF-8 envelope_id
 *     (M-B7 multi-byte UTF-8 golden vector is the regression pin).
 *   * `verifyKeyHex` is hex-decoded to a 32-byte `ByteArray` BEFORE
 *     being passed as the HMAC key. Passing the hex `String` as ASCII
 *     bytes silently verifies wrong against every server-generated
 *     MAC; M-B9 pins this.
 *   * Both 32-byte MAC values (computed + wire) are compared via
 *     [constantTimeEquals], NEVER via hex-string equality (L1).
 *
 * Stateless by design: the orchestrator owns the verify-key state
 * machine (L2) and only calls the verifier on the `KeyPresent(hex)`
 * branch with a validated 64-char lowercase-hex key. Malformed verify
 * keys are filtered at the L2 refresh-outcome classifier; the verifier
 * itself trusts the key shape and `require`-asserts the length only as
 * a defence-in-depth.
 */
object SeqMacVerifier {

    /**
     * Result of a verify call. The orchestrator maps these to the
     * structured-log outcome classes (`verified`, `mac_mismatch`,
     * `no_mac_field`) and to the L7 bad-MAC posture (counter +
     * latched refresh + suspension).
     */
    sealed class Outcome {
        /** MAC matches the canonical-input-derived HMAC-SHA-256 output. */
        object Verified : Outcome()
        /**
         * Wire `seq_mac` is well-formed 64-char lowercase hex but the
         * decoded bytes do NOT match the computed MAC. The orchestrator
         * logs `reason=mac_mismatch` and feeds the L7 bad-MAC counter.
         */
        object MacMismatch : Outcome()
        /**
         * Wire `seq_mac` is empty, not 64 characters long, or contains
         * non-lowercase-hex characters. The Stage 1.x server contract
         * always emits a 64-char lowercase-hex MAC, so a malformed
         * observation indicates an old relay (Stage 1.x not deployed
         * → field absent → empty string) or a hostile injection. The
         * orchestrator logs `reason=no_mac_field` and (per L7) feeds
         * the bad-MAC counter when verify-key state is `KeyPresent`.
         * In `KeyAbsent` state the verifier is not called at all; the
         * orchestrator uses the legacy unverified pass-through.
         */
        object MalformedSeqMac : Outcome()
    }

    /**
     * Domain tag `b"phantom-seq-mac-v1\x00"` — 18 ASCII bytes of the
     * tag followed by one NUL byte for a total of 19 bytes. Built via
     * concatenation of the ASCII prefix and an explicit zero byte so
     * the source file stays clean ASCII (no raw NUL in source) and
     * matches the relay's `SEQ_MAC_DOMAIN_TAG` constant byte-for-byte.
     */
    private val DOMAIN_TAG_BYTES: ByteArray =
        "phantom-seq-mac-v1".encodeToByteArray() + byteArrayOf(0)

    /** Width of the identity hex projection (64 lowercase ASCII bytes). */
    internal const val IDENTITY_HEX_LENGTH: Int = 64

    /** Width of a wire / computed HMAC-SHA-256 hex value (32 bytes → 64 chars). */
    internal const val SEQ_MAC_HEX_LENGTH: Int = 64

    /** Raw HMAC-SHA-256 output width in bytes. */
    internal const val SEQ_MAC_RAW_LENGTH: Int = 32

    /**
     * Canonical-input fixed-prefix length: 19 (domain tag) + 64
     * (identity_hex) + 8 (seq) + 2 (envelope_id_len) + 8 (sequence_ts).
     * The wire layout adds `envelope_id_bytes.size` between
     * `envelope_id_len` and `sequence_ts`, so the total is
     * `CANONICAL_INPUT_FIXED_BYTES + envelopeIdBytes.size`.
     */
    internal const val CANONICAL_INPUT_FIXED_BYTES: Int = 19 + 64 + 8 + 2 + 8 // = 101

    /**
     * Upper bound on `envelope_id` UTF-8 byte length so the `u16-BE`
     * length prefix is sufficient. Server contract enforces this at
     * the request boundary; the verifier returns
     * [Outcome.MalformedSeqMac] for the (impossible-from-the-server)
     * over-cap case as a defence-in-depth.
     */
    internal const val ENVELOPE_ID_MAX_BYTES: Int = 0xFFFF // u16::MAX

    /**
     * Verify a single envelope's `seq_mac` against the canonical input.
     *
     * @param identityHex 64-char lowercase ASCII hex of the receiving
     *   identity (`RestFallbackOrchestrator.identityHex`).
     * @param seq the `PollEnvelope.seq` value, u64 big-endian.
     * @param envelopeId the `PollEnvelope.id` value; UTF-8 byte length
     *   is hashed, not UTF-16 char count.
     * @param sequenceTs the post-quantize `PollEnvelope.sequenceTs`
     *   value (60-second floor applied server-side at store time).
     * @param seqMacHex the wire `PollEnvelope.seqMac` value, expected
     *   to be 64-char lowercase hex.
     * @param verifyKeyHex the per-identity verify key as 64-char
     *   lowercase hex (state-machine-validated upstream).
     */
    fun verify(
        identityHex: String,
        seq: Long,
        envelopeId: String,
        sequenceTs: Long,
        seqMacHex: String,
        verifyKeyHex: String,
    ): Outcome {
        require(identityHex.length == IDENTITY_HEX_LENGTH) {
            "identityHex must be $IDENTITY_HEX_LENGTH chars; was ${identityHex.length}"
        }
        require(verifyKeyHex.length == IDENTITY_HEX_LENGTH) {
            "verifyKeyHex must be $IDENTITY_HEX_LENGTH chars; was ${verifyKeyHex.length}"
        }

        // Wire MAC shape check — fail-closed without an exception so
        // the L7 bad-MAC posture can absorb it through the orchestrator.
        // The server contract emits only well-formed values; an empty
        // / non-hex / wrong-length observation is either an old relay
        // (KeyAbsent path; the verifier is not called there) or a
        // hostile injection.
        if (!isWellFormedLowercaseHex(seqMacHex, SEQ_MAC_HEX_LENGTH)) {
            return Outcome.MalformedSeqMac
        }

        val envelopeIdBytes = envelopeId.encodeToByteArray()
        if (envelopeIdBytes.size > ENVELOPE_ID_MAX_BYTES) {
            // The server caps envelope_id at the request boundary; this
            // branch should be unreachable in production but stays
            // here so a regression cannot produce an over-large input
            // and crash the HMAC primitive.
            return Outcome.MalformedSeqMac
        }

        val canonicalInput = buildCanonicalInput(
            identityHex = identityHex,
            seq = seq,
            envelopeIdBytes = envelopeIdBytes,
            sequenceTs = sequenceTs,
        )

        // Hex-decode-before-use: the HMAC key is the RAW 32-byte
        // output of the server-side derivation, NOT the 64-byte ASCII
        // hex string. Passing the hex string would silently verify
        // wrong against every server-generated MAC. M-B9 pins this.
        val keyBytes = hexDecode(verifyKeyHex)

        val computedMacBytes: ByteArray = Hmac.sha256(keyBytes, canonicalInput)
        val wireMacBytes = hexDecode(seqMacHex)

        return if (constantTimeEquals(computedMacBytes, wireMacBytes)) {
            Outcome.Verified
        } else {
            Outcome.MacMismatch
        }
    }

    // ── Internal helpers (visible for direct tests in commonTest) ─────────────

    /**
     * Compose the canonical input bytes for the given envelope. Exposed
     * for direct round-trip tests that compute the MAC without the
     * verify path; production callers go through [verify].
     */
    internal fun buildCanonicalInput(
        identityHex: String,
        seq: Long,
        envelopeIdBytes: ByteArray,
        sequenceTs: Long,
    ): ByteArray {
        val totalSize = CANONICAL_INPUT_FIXED_BYTES + envelopeIdBytes.size
        val out = ByteArray(totalSize)
        var offset = 0

        // 19 bytes: domain tag.
        DOMAIN_TAG_BYTES.copyInto(out, offset)
        offset += DOMAIN_TAG_BYTES.size

        // 64 bytes: identity_hex as lowercase ASCII.
        val identityBytes = identityHex.encodeToByteArray()
        // Length is asserted by the caller (`verify` requires 64 chars);
        // identityBytes.size therefore equals 64 for pure ASCII input.
        // `require` here is a defence-in-depth against a future caller
        // that constructs the canonical input directly and passes a
        // non-ASCII identity_hex.
        require(identityBytes.size == IDENTITY_HEX_LENGTH) {
            "identityHex encodes to ${identityBytes.size} bytes; must be ASCII-only"
        }
        identityBytes.copyInto(out, offset)
        offset += IDENTITY_HEX_LENGTH

        // 8 bytes: seq, u64 big-endian.
        writeU64BigEndian(out, offset, seq)
        offset += 8

        // 2 bytes: envelope_id_len, u16 big-endian.
        writeU16BigEndian(out, offset, envelopeIdBytes.size)
        offset += 2

        // N bytes: envelope_id UTF-8 bytes.
        envelopeIdBytes.copyInto(out, offset)
        offset += envelopeIdBytes.size

        // 8 bytes: sequence_ts, u64 big-endian.
        writeU64BigEndian(out, offset, sequenceTs)
        offset += 8

        check(offset == totalSize) {
            "canonical input encoding produced $offset bytes; expected $totalSize"
        }
        return out
    }

    /**
     * Compute the HMAC-SHA-256 of the canonical input under the
     * provided 32-byte raw verify key. Exposed for direct golden-vector
     * tests; production callers go through [verify].
     */
    internal fun computeMac(
        identityHex: String,
        seq: Long,
        envelopeId: String,
        sequenceTs: Long,
        verifyKeyBytes: ByteArray,
    ): ByteArray {
        require(verifyKeyBytes.size == SEQ_MAC_RAW_LENGTH) {
            "verifyKeyBytes must be $SEQ_MAC_RAW_LENGTH bytes; was ${verifyKeyBytes.size}"
        }
        val canonicalInput = buildCanonicalInput(
            identityHex = identityHex,
            seq = seq,
            envelopeIdBytes = envelopeId.encodeToByteArray(),
            sequenceTs = sequenceTs,
        )
        return Hmac.sha256(verifyKeyBytes, canonicalInput)
    }

    internal fun writeU64BigEndian(dst: ByteArray, offset: Int, value: Long) {
        dst[offset]     = (value ushr 56).toByte()
        dst[offset + 1] = (value ushr 48).toByte()
        dst[offset + 2] = (value ushr 40).toByte()
        dst[offset + 3] = (value ushr 32).toByte()
        dst[offset + 4] = (value ushr 24).toByte()
        dst[offset + 5] = (value ushr 16).toByte()
        dst[offset + 6] = (value ushr 8).toByte()
        dst[offset + 7] = (value).toByte()
    }

    internal fun writeU16BigEndian(dst: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xFFFF) {
            "u16 value out of range: $value"
        }
        dst[offset]     = (value ushr 8).toByte()
        dst[offset + 1] = (value).toByte()
    }

    /**
     * Returns true iff [s] has exactly [expectedLength] characters and
     * every character is in `[0-9a-f]`. Uppercase hex is rejected so
     * the strict server-contract shape is the only accepted form;
     * accepting uppercase would let a verify path silently succeed
     * against a hostile injection that uses the same bytes under a
     * different casing.
     */
    internal fun isWellFormedLowercaseHex(s: String, expectedLength: Int): Boolean {
        if (s.length != expectedLength) return false
        for (c in s) {
            val lowerOk = c in '0'..'9' || c in 'a'..'f'
            if (!lowerOk) return false
        }
        return true
    }

    /**
     * Hex-decode a known-well-formed (caller-checked) lowercase-hex
     * string into the raw byte array. Length is asserted via the
     * `out` allocation; caller is expected to have validated the
     * input via [isWellFormedLowercaseHex] first.
     */
    internal fun hexDecode(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2].digitToInt(16)
            val lo = hex[i * 2 + 1].digitToInt(16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
