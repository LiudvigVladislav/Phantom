// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Trek 2 Stage 1.x sequence-MAC primitives.
//!
//! The `seq_mac` field on `PollEnvelope` is an integrity tag that lets a
//! client detect non-MAC-aware corruption / row mismatch in the relay's
//! REST store. It is computed at envelope store time and persisted as a
//! column on `RestEnvelope`, then read verbatim by the poll-response path
//! — never recomputed at response time. The threat model is in
//! `docs/tracks/trek2-stage1x-server-prereq.md` and is repeated verbatim
//! on the `PollEnvelope.seq_mac` field doc-comment in `rest_fallback.rs`.
//!
//! Two domain tags are in use, and they are distinct so the same root-key
//! bytes cannot be misused in a cross-domain forgery:
//!
//! - [`SEQ_MAC_KEY_DOMAIN_TAG`] — used to derive a per-identity verify key
//!   from the relay-side root key. The derived key is published to the
//!   client in `SessionResponse.seq_mac_verify_key` so the client can
//!   verify MACs for envelopes addressed to itself, and to itself only.
//! - [`SEQ_MAC_DOMAIN_TAG`] — used by both the server (to compute MAC at
//!   store time) and the client (to verify on receive) over the canonical
//!   `(identity_hex, seq, envelope_id, sequence_ts)` tuple.
//!
//! The root key never leaves the relay process. Server-side MAC
//! computation always goes through the per-identity derived key, never
//! the root directly.

use hmac::{Hmac, Mac};
use sha2::Sha256;
use zeroize::Zeroizing;

type HmacSha256 = Hmac<Sha256>;

/// Domain separation tag for MAC computation. Identifies the v1 MAC scheme.
/// Future scheme rotations bump the version to `v2\x00`, etc., without
/// changing the field layout.
pub const SEQ_MAC_DOMAIN_TAG: &[u8] = b"phantom-seq-mac-v1\x00";

/// Domain separation tag for per-identity key derivation. Distinct from
/// [`SEQ_MAC_DOMAIN_TAG`] so the same root-key bytes cannot be misused in
/// cross-domain forgery.
pub const SEQ_MAC_KEY_DOMAIN_TAG: &[u8] = b"phantom-seq-mac-key-v1\x00";

/// Maximum byte length of `envelope_id` accepted in the canonical MAC
/// input. Bounded by the `u16-BE` length prefix in the encoding spec.
/// Production sizes are ~32 bytes; the 65535 ceiling is a defensive cap
/// on the MAC-compute path (kept for compute-layer defense-in-depth).
///
/// The ingress-layer validator [`is_valid_envelope_id`] enforces a much
/// tighter practical ceiling [`ENVELOPE_ID_MAX_PRACTICAL`] (128 bytes)
/// + canonical character-class shape. This constant remains the outer
/// bound the encoding format can express; the ingress bound is what
/// production traffic actually sees.
pub const ENVELOPE_ID_MAX_BYTES: usize = u16::MAX as usize;

/// PR-0 M-1: ingress-layer practical ceiling for `envelope_id` byte
/// length. Production real values are ~32 bytes (UUIDs / ULIDs / hex);
/// 128 bytes is 4× headroom plus defense against payload-abuse. Kept
/// distinct from [`ENVELOPE_ID_MAX_BYTES`] so the MAC-compute path
/// (which still tolerates the full u16-BE range for pre-existing
/// records) is not narrowed by this ingress-only change.
pub const ENVELOPE_ID_MAX_PRACTICAL: usize = 128;

/// Shape check for a recipient identity-hex used at REST `/relay/send`
/// and WS Send entry. The recipient flows into the canonical input of
/// `compute_seq_mac` via [`SeqMacVerifyKey::compute_seq_mac`]; rejecting
/// malformed inputs at the request boundary keeps a malformed identity
/// from reaching the MAC path and also keeps short log-prefix slicing
/// safe.
///
/// PR-0 A-6 — LOWERCASE-STRICT. True iff `s` is exactly 64 characters
/// drawn from `[0-9a-f]`. Uppercase and mixed case are rejected.
///
/// Rationale: `derive_verify_key` at line 118 feeds `identity_hex` bytes
/// directly into the HMAC-SHA-256 domain input, so two clients addressing
/// the same identity with different letter case would derive DIFFERENT
/// `SeqMacVerifyKey`s — breaking MAC verification for whichever party
/// sent the non-canonical form. Under PR-2's per-recipient sub-directory
/// layout `<hex[0..2]>/<recipient_hex>/`, the two casings would also
/// create parallel directories and split-queue the same recipient.
///
/// Server-side auto-lowercase (`req.to = req.to.to_ascii_lowercase()`)
/// was CONSIDERED AND REJECTED — hiding sender bugs behind normalisation
/// makes the split-queue / MAC-mismatch class hard to observe. Fail-loud
/// at 400 forces the client to send the canonical form.
pub fn is_valid_recipient_identity_hex(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// PR-0 M-1: canonical shape check for `envelope_id` / WS `msg_id` at
/// the request boundary. Rejects everything that could cause harm on
/// the log-injection, idempotency-cache, or (future PR-2) on-disk
/// per-envelope-file paths.
///
/// True iff `s` is non-empty, at most [`ENVELOPE_ID_MAX_PRACTICAL`]
/// bytes, and contains only URL-safe ASCII bytes drawn from
/// `[a-zA-Z0-9._-]` (letters, digits, dot, underscore, hyphen).
///
/// Explicitly REJECTED:
/// - Empty string (unchanged from prior behaviour).
/// - Length > 128 bytes (tightened from u16::MAX = 65535).
/// - Any C0 control char (`0x00..=0x1F`) or `DEL` (`0x7F`) — log
///   injection via `\r\n` / CSI sequences.
/// - Any high-bit / non-ASCII byte (`0x80..=0xFF`) — bounds every
///   multi-byte UTF-8 code point; lookalike Unicode idempotency-cache
///   confusion.
/// - `/`, `\`, `\0`, whitespace — path separators + null-byte
///   filesystem edge cases (future PR-2 uses `sha256_hex(envelope_id)`
///   as an on-disk filename, but the raw id still flows through
///   idempotency + log paths; keeping the ingress class pure removes
///   the risk at the source).
///
/// Preserves lookup compatibility with the production envelope-id
/// alphabet (UUID / ULID / hex).
pub fn is_valid_envelope_id(s: &str) -> bool {
    if s.is_empty() || s.len() > ENVELOPE_ID_MAX_PRACTICAL {
        return false;
    }
    s.bytes().all(|b| matches!(b,
        b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' |
        b'.' | b'_' | b'-'
    ))
}

/// Fixed-size, zeroized-on-drop wrapper for the relay-side root MAC key.
///
/// The root key NEVER leaves the relay process. It is sourced from the
/// `RELAY_SEQ_MAC_KEY` environment variable at startup, stored on the
/// stack/struct embedding (not the heap), and wiped on drop by
/// [`Zeroizing`]. The `Debug` impl elides the bytes as `[REDACTED]` so
/// no logging path can accidentally leak them.
///
/// Server-side MAC computation does NOT use this type directly — it
/// goes through a per-identity [`SeqMacVerifyKey`] derived via
/// [`Self::derive_verify_key`].
pub struct SeqMacRootKey(Zeroizing<[u8; 32]>);

impl SeqMacRootKey {
    /// Construct from raw 32-byte material. Used by `from_env_for_test()`
    /// in `RelayConfig` and by any unit tests with byte-pinned vectors.
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(Zeroizing::new(bytes))
    }

    /// Parse from a 64-character hex string (case-insensitive). Returns
    /// an error if the input is not valid hex or does not decode to
    /// exactly 32 bytes.
    pub fn from_hex(s: &str) -> Result<Self, SeqMacKeyParseError> {
        let bytes = hex::decode(s).map_err(|_| SeqMacKeyParseError::InvalidHex)?;
        if bytes.len() != 32 {
            return Err(SeqMacKeyParseError::WrongLength(bytes.len()));
        }
        let mut arr = [0u8; 32];
        arr.copy_from_slice(&bytes);
        Ok(Self(Zeroizing::new(arr)))
    }

    /// Derive the per-identity verify key:
    ///
    /// ```text
    /// seq_mac_verify_key = HMAC-SHA-256(
    ///     key = root_key,
    ///     msg = b"phantom-seq-mac-key-v1\x00" || identity_hex
    /// )
    /// ```
    ///
    /// The output is the only key material the client sees. Even with a
    /// leaked verify key, the client can verify MACs only for their own
    /// identity — they cannot forge MACs for any other identity because
    /// derivation requires the root key, which never leaves the relay.
    pub fn derive_verify_key(&self, identity_hex: &str) -> SeqMacVerifyKey {
        let mut mac = HmacSha256::new_from_slice(&self.0[..])
            .expect("HMAC-SHA-256 accepts any key length");
        mac.update(SEQ_MAC_KEY_DOMAIN_TAG);
        mac.update(identity_hex.as_bytes());
        let result = mac.finalize().into_bytes();
        let mut arr = [0u8; 32];
        arr.copy_from_slice(&result);
        SeqMacVerifyKey(Zeroizing::new(arr))
    }
}

impl std::fmt::Debug for SeqMacRootKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_tuple("SeqMacRootKey").field(&"[REDACTED]").finish()
    }
}

/// Per-identity verify key, derived from [`SeqMacRootKey`]. Distributed
/// to the client in `SessionResponse.seq_mac_verify_key` (64-char hex).
///
/// The server computes envelope MACs using this same derived key; the
/// root key is touched only in the derivation step. This is what bounds
/// the blast radius of a verify-key leak to one identity.
pub struct SeqMacVerifyKey(Zeroizing<[u8; 32]>);

impl SeqMacVerifyKey {
    /// Hex-encode the 32 raw bytes for wire publication in
    /// `SessionResponse.seq_mac_verify_key`. Lowercase, 64 chars.
    pub fn to_hex(&self) -> String {
        hex::encode(self.0.as_slice())
    }

    /// Compute the `seq_mac` integrity tag over the canonical input for
    /// one envelope. Returns the 32 raw HMAC-SHA-256 output bytes; use
    /// [`seq_mac_to_hex`] to convert to the 64-char wire form.
    ///
    /// Canonical input (variable length, total = 101 + envelope_id_len bytes):
    ///
    /// ```text
    /// SEQ_MAC_DOMAIN_TAG               19 bytes  b"phantom-seq-mac-v1\x00"
    /// identity_hex                     64 bytes  lowercase ASCII
    /// seq                               8 bytes  u64 big-endian
    /// envelope_id_len                   2 bytes  u16 big-endian, byte length
    /// envelope_id_bytes                variable  exact UTF-8 bytes
    /// sequence_ts                       8 bytes  u64 big-endian (post-quantize)
    /// ```
    ///
    /// Returns [`SeqMacComputeError::EnvelopeIdTooLong`] if the UTF-8
    /// byte length of `envelope_id` exceeds [`ENVELOPE_ID_MAX_BYTES`]
    /// (65535). The caller (request handler) is expected to validate the
    /// length at the request boundary; this check is defensive.
    pub fn compute_seq_mac(
        &self,
        identity_hex: &str,
        seq: u64,
        envelope_id: &str,
        sequence_ts: u64,
    ) -> Result<[u8; 32], SeqMacComputeError> {
        let envelope_id_bytes = envelope_id.as_bytes();
        if envelope_id_bytes.len() > ENVELOPE_ID_MAX_BYTES {
            return Err(SeqMacComputeError::EnvelopeIdTooLong {
                len: envelope_id_bytes.len(),
            });
        }
        let envelope_id_len = envelope_id_bytes.len() as u16;

        let mut mac = HmacSha256::new_from_slice(&self.0[..])
            .expect("HMAC-SHA-256 accepts any key length");
        mac.update(SEQ_MAC_DOMAIN_TAG);
        mac.update(identity_hex.as_bytes());
        mac.update(&seq.to_be_bytes());
        mac.update(&envelope_id_len.to_be_bytes());
        mac.update(envelope_id_bytes);
        mac.update(&sequence_ts.to_be_bytes());
        let result = mac.finalize().into_bytes();
        let mut arr = [0u8; 32];
        arr.copy_from_slice(&result);
        Ok(arr)
    }
}

impl std::fmt::Debug for SeqMacVerifyKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_tuple("SeqMacVerifyKey").field(&"[REDACTED]").finish()
    }
}

/// Convert the 32-byte HMAC output to the 64-char lowercase hex wire
/// form stored in the `seq_mac` column / wire field.
pub fn seq_mac_to_hex(mac: &[u8; 32]) -> String {
    hex::encode(mac)
}

/// Errors from [`SeqMacRootKey::from_hex`].
#[derive(Debug)]
pub enum SeqMacKeyParseError {
    /// The input was not valid hex.
    InvalidHex,
    /// The input was valid hex but did not decode to exactly 32 bytes.
    WrongLength(usize),
}

impl std::fmt::Display for SeqMacKeyParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidHex => {
                f.write_str("RELAY_SEQ_MAC_KEY must be valid hex (64 lowercase chars)")
            }
            Self::WrongLength(n) => write!(
                f,
                "RELAY_SEQ_MAC_KEY must decode to exactly 32 bytes; got {n}"
            ),
        }
    }
}

impl std::error::Error for SeqMacKeyParseError {}

/// Errors from [`SeqMacVerifyKey::compute_seq_mac`].
#[derive(Debug)]
pub enum SeqMacComputeError {
    /// The `envelope_id` UTF-8 byte length exceeded
    /// [`ENVELOPE_ID_MAX_BYTES`] (65535). The caller must reject the
    /// request before reaching this point.
    EnvelopeIdTooLong { len: usize },
}

impl std::fmt::Display for SeqMacComputeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::EnvelopeIdTooLong { len } => write!(
                f,
                "envelope_id UTF-8 byte length {len} exceeds maximum {max}",
                max = ENVELOPE_ID_MAX_BYTES
            ),
        }
    }
}

impl std::error::Error for SeqMacComputeError {}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_root_key() -> SeqMacRootKey {
        SeqMacRootKey::from_bytes([0u8; 32])
    }

    fn test_identity() -> String {
        "a".repeat(64)
    }

    #[test]
    fn root_key_debug_redacts_bytes() {
        let key = test_root_key();
        let debug_str = format!("{key:?}");
        assert!(debug_str.contains("REDACTED"));
        assert!(!debug_str.contains("00 00"));
    }

    #[test]
    fn verify_key_debug_redacts_bytes() {
        let root = test_root_key();
        let derived = root.derive_verify_key(&test_identity());
        let debug_str = format!("{derived:?}");
        assert!(debug_str.contains("REDACTED"));
    }

    #[test]
    fn from_hex_rejects_invalid_hex() {
        assert!(matches!(
            SeqMacRootKey::from_hex("zzz"),
            Err(SeqMacKeyParseError::InvalidHex)
        ));
    }

    #[test]
    fn from_hex_rejects_wrong_length() {
        let short = "00".repeat(16);
        assert!(matches!(
            SeqMacRootKey::from_hex(&short),
            Err(SeqMacKeyParseError::WrongLength(16))
        ));
    }

    #[test]
    fn from_hex_accepts_64_char_lowercase() {
        let s = "00".repeat(32);
        assert!(SeqMacRootKey::from_hex(&s).is_ok());
    }

    #[test]
    fn derive_verify_key_is_deterministic() {
        let root = test_root_key();
        let a = root.derive_verify_key(&test_identity());
        let b = root.derive_verify_key(&test_identity());
        assert_eq!(a.to_hex(), b.to_hex());
    }

    #[test]
    fn derive_verify_key_differs_per_identity() {
        let root = test_root_key();
        let id_a = "a".repeat(64);
        let id_b = "b".repeat(64);
        let a = root.derive_verify_key(&id_a);
        let b = root.derive_verify_key(&id_b);
        assert_ne!(a.to_hex(), b.to_hex());
    }

    #[test]
    fn verify_key_to_hex_is_64_lowercase_chars() {
        let root = test_root_key();
        let derived = root.derive_verify_key(&test_identity());
        let hex_str = derived.to_hex();
        assert_eq!(hex_str.len(), 64);
        assert!(hex_str.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
    }

    #[test]
    fn compute_seq_mac_is_deterministic() {
        let root = test_root_key();
        let key = root.derive_verify_key(&test_identity());
        let a = key
            .compute_seq_mac(&test_identity(), 1, "envelope-id", 60_000)
            .unwrap();
        let b = key
            .compute_seq_mac(&test_identity(), 1, "envelope-id", 60_000)
            .unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn compute_seq_mac_changes_with_seq() {
        let root = test_root_key();
        let key = root.derive_verify_key(&test_identity());
        let a = key
            .compute_seq_mac(&test_identity(), 1, "envelope-id", 60_000)
            .unwrap();
        let b = key
            .compute_seq_mac(&test_identity(), 2, "envelope-id", 60_000)
            .unwrap();
        assert_ne!(a, b);
    }

    #[test]
    fn compute_seq_mac_changes_with_envelope_id() {
        let root = test_root_key();
        let key = root.derive_verify_key(&test_identity());
        let a = key
            .compute_seq_mac(&test_identity(), 1, "envelope-a", 60_000)
            .unwrap();
        let b = key
            .compute_seq_mac(&test_identity(), 1, "envelope-b", 60_000)
            .unwrap();
        assert_ne!(a, b);
    }

    #[test]
    fn compute_seq_mac_changes_with_sequence_ts() {
        let root = test_root_key();
        let key = root.derive_verify_key(&test_identity());
        let a = key
            .compute_seq_mac(&test_identity(), 1, "envelope-id", 60_000)
            .unwrap();
        let b = key
            .compute_seq_mac(&test_identity(), 1, "envelope-id", 120_000)
            .unwrap();
        assert_ne!(a, b);
    }

    #[test]
    fn compute_seq_mac_changes_with_identity() {
        let root = test_root_key();
        let key_a = root.derive_verify_key(&"a".repeat(64));
        let key_b = root.derive_verify_key(&"b".repeat(64));
        let a = key_a
            .compute_seq_mac(&"a".repeat(64), 1, "envelope-id", 60_000)
            .unwrap();
        let b = key_b
            .compute_seq_mac(&"b".repeat(64), 1, "envelope-id", 60_000)
            .unwrap();
        assert_ne!(a, b);
    }

    #[test]
    fn compute_seq_mac_rejects_envelope_id_over_u16_max() {
        let root = test_root_key();
        let key = root.derive_verify_key(&test_identity());
        let huge = "x".repeat(ENVELOPE_ID_MAX_BYTES + 1);
        let result = key.compute_seq_mac(&test_identity(), 1, &huge, 60_000);
        assert!(matches!(
            result,
            Err(SeqMacComputeError::EnvelopeIdTooLong { len })
                if len == ENVELOPE_ID_MAX_BYTES + 1
        ));
    }

    #[test]
    fn compute_seq_mac_accepts_envelope_id_at_u16_max() {
        let root = test_root_key();
        let key = root.derive_verify_key(&test_identity());
        let at_limit = "x".repeat(ENVELOPE_ID_MAX_BYTES);
        let result = key.compute_seq_mac(&test_identity(), 1, &at_limit, 60_000);
        assert!(result.is_ok());
    }

    #[test]
    fn compute_seq_mac_handles_empty_envelope_id() {
        let root = test_root_key();
        let key = root.derive_verify_key(&test_identity());
        let result = key.compute_seq_mac(&test_identity(), 1, "", 60_000);
        assert!(result.is_ok());
    }

    #[test]
    fn compute_seq_mac_handles_multi_byte_utf8_envelope_id() {
        // Verifies length is counted in bytes, not characters. The MAC
        // encoding is exact UTF-8 bytes per the locked spec.
        let root = test_root_key();
        let key = root.derive_verify_key(&test_identity());
        let result = key.compute_seq_mac(&test_identity(), 1, "Ω 4-byte 𓂀", 60_000);
        assert!(result.is_ok());
    }

    #[test]
    fn seq_mac_to_hex_is_64_lowercase_chars() {
        let root = test_root_key();
        let key = root.derive_verify_key(&test_identity());
        let mac = key
            .compute_seq_mac(&test_identity(), 1, "envelope-id", 60_000)
            .unwrap();
        let s = seq_mac_to_hex(&mac);
        assert_eq!(s.len(), 64);
        assert!(s.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
    }

    // ── Boundary-shape helpers (Trek 2 Stage 1.x review fix) ──────────

    #[test]
    fn is_valid_recipient_identity_hex_accepts_canonical_64_hex() {
        assert!(is_valid_recipient_identity_hex(&test_identity()));
        assert!(is_valid_recipient_identity_hex(&"0".repeat(64)));
        assert!(is_valid_recipient_identity_hex(&"f".repeat(64)));
    }

    // PR-0 A-6: pre-existing test asserted uppercase-64-hex as accepted;
    // that behaviour was the defect. Under A-6 the validator is strict
    // lowercase, so the assertion inverts. Kept as a distinct test so
    // the intent is explicit and the flip is visible in review.
    #[test]
    fn is_valid_recipient_identity_hex_rejects_uppercase_and_mixed_case() {
        // All uppercase.
        assert!(!is_valid_recipient_identity_hex(&"A".repeat(64)));
        // All uppercase digits/letters (some digits, some upper letters).
        assert!(!is_valid_recipient_identity_hex(&"F".repeat(64)));
        // Mixed case — even ONE uppercase letter must reject.
        let mut mixed = "a".repeat(63);
        mixed.push('A');
        assert!(!is_valid_recipient_identity_hex(&mixed));
        // Full lowercase hex alphabet remains accepted.
        assert!(is_valid_recipient_identity_hex(&"0123456789abcdef".repeat(4)));
    }

    #[test]
    fn is_valid_recipient_identity_hex_rejects_wrong_length() {
        assert!(!is_valid_recipient_identity_hex(""));
        assert!(!is_valid_recipient_identity_hex(&"a".repeat(63)));
        assert!(!is_valid_recipient_identity_hex(&"a".repeat(65)));
        assert!(!is_valid_recipient_identity_hex(&"a".repeat(128)));
    }

    #[test]
    fn is_valid_recipient_identity_hex_rejects_non_hex_chars() {
        // 64-char strings with non-hex bytes — common attack shapes.
        let mut bad = "a".repeat(63);
        bad.push('g'); // not a hex digit
        assert!(!is_valid_recipient_identity_hex(&bad));
        let mut bad2 = "a".repeat(63);
        bad2.push('!');
        assert!(!is_valid_recipient_identity_hex(&bad2));
        let mut bad3 = "a".repeat(63);
        bad3.push(' ');
        assert!(!is_valid_recipient_identity_hex(&bad3));
        // Multi-byte UTF-8: even 32 chars worth of 2-byte Cyrillic gives
        // 64 bytes total — but as a char count it isn't 64, AND the
        // bytes aren't hex. Either rejection path is correct.
        assert!(!is_valid_recipient_identity_hex(&"ё".repeat(32)));
    }

    // PR-0 M-1: pre-existing bounds test rewritten around the tightened
    // ingress ceiling (128 bytes) + canonical character-class shape.
    // The old test asserted acceptance up to ENVELOPE_ID_MAX_BYTES
    // (65535) — under M-1 that ceiling is only the compute-path defense
    // bound; the ingress ceiling is ENVELOPE_ID_MAX_PRACTICAL (128).
    #[test]
    fn is_valid_envelope_id_bounds_match_ingress_ceiling() {
        assert!(is_valid_envelope_id("e"));
        assert!(is_valid_envelope_id(&"x".repeat(ENVELOPE_ID_MAX_PRACTICAL)));
        assert!(!is_valid_envelope_id(""));
        assert!(!is_valid_envelope_id(&"x".repeat(ENVELOPE_ID_MAX_PRACTICAL + 1)));
        // Ingress rejects the old compute-path ceiling — proves the two
        // bounds are intentionally distinct.
        assert!(!is_valid_envelope_id(&"x".repeat(ENVELOPE_ID_MAX_BYTES)));
    }

    // PR-0 M-1: canonical character-class shape. Positive alphabet is
    // [a-zA-Z0-9._-]; everything else rejected at the ingress boundary.
    #[test]
    fn is_valid_envelope_id_accepts_canonical_shapes() {
        // 32-char lowercase hex (typical Alpha-0 shape).
        assert!(is_valid_envelope_id(&"a".repeat(32)));
        // 36-char UUID with hyphens.
        assert!(is_valid_envelope_id("550e8400-e29b-41d4-a716-446655440000"));
        // 26-char ULID (Crockford base32 alphabet is a subset of [a-zA-Z0-9]).
        assert!(is_valid_envelope_id("01HZY6BQPWX7ABCDEF0123456K"));
        // Mixed alphanumeric + dot/underscore/hyphen.
        assert!(is_valid_envelope_id("my.envelope_id-42"));
    }

    #[test]
    fn is_valid_envelope_id_rejects_control_chars() {
        assert!(!is_valid_envelope_id("a\rb"));
        assert!(!is_valid_envelope_id("a\nb"));
        assert!(!is_valid_envelope_id("a\tb"));
        assert!(!is_valid_envelope_id("a\x00b"));
        assert!(!is_valid_envelope_id("a\x1fb"));
        assert!(!is_valid_envelope_id("a\x7fb")); // DEL
    }

    #[test]
    fn is_valid_envelope_id_rejects_path_separators() {
        assert!(!is_valid_envelope_id("foo/bar"));
        assert!(!is_valid_envelope_id("foo\\bar"));
        assert!(!is_valid_envelope_id("/absolute"));
        assert!(!is_valid_envelope_id("../etc/passwd"));
    }

    #[test]
    fn is_valid_envelope_id_rejects_whitespace() {
        assert!(!is_valid_envelope_id("foo bar"));
        assert!(!is_valid_envelope_id(" leading"));
        assert!(!is_valid_envelope_id("trailing "));
    }

    #[test]
    fn is_valid_envelope_id_rejects_high_bit_and_multibyte() {
        // Cyrillic ё is 2-byte UTF-8; validator must reject any byte
        // outside the [a-zA-Z0-9._-] class.
        assert!(!is_valid_envelope_id("ёlo"));
        // Isolated high byte through non-canonical construction:
        // build the string via unchecked conversion so the compiler
        // does not statically catch the invariant we are testing.
        // (Safe: we own the bytes and never expose them outside.)
        let bad = String::from_utf8(vec![b'a', 0xC2, 0xA0, b'b']).unwrap(); // NBSP
        assert!(!is_valid_envelope_id(&bad));
    }
}
