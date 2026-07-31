// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M2 — canonical body hash.
//!
//! Locked design `v4.1-amendments.md` §1 V-P0-5: length-prefixed
//! SHA-256 over the stable content of an envelope. The hash IS
//! the dedup identity paired with `envelope_id` — two envelopes
//! that share `id` and share `body_hash` are treated as
//! idempotent retries; two envelopes that share `id` but differ
//! in `body_hash` are rejected with HTTP 409 at the send path.
//!
//! Length prefixes prevent the concatenation-collision attack:
//! without them, an attacker could pick `(sender', payload')`
//! such that `sender || payload == sender' || payload'` by
//! splitting a valid pair at a different byte offset. With a
//! 4-byte big-endian prefix before each field the boundary is
//! bound into the digest, so a moved boundary yields a different
//! hash.
//!
//! Design v4.1 V-P0-5 **excludes** `expires_at`, `sequence_ts`,
//! `seq`, `id`, and `seq_mac` from the hash input. Rationale:
//!   * `expires_at` is server-computed and regenerated per send
//!     (see recon in v4.2.2 §1); including it would break the
//!     retry-idempotency contract.
//!   * `sequence_ts` is server-quantised.
//!   * `seq` is server-assigned.
//!   * `id` is already the dedup key.
//!   * `seq_mac` binds `seq + id + recipient` under the server's
//!     MAC key and would circularly depend on `body_hash` if
//!     included.

use sha2::{Digest, Sha256};

/// Byte-shape prefix for length encoding. 4 bytes big-endian
/// covers up to 4 GiB per field which is far beyond any relay
/// envelope size; the sender-facing body cap sits at O(MiB).
const FIELD_LEN_PREFIX_BYTES: usize = 4;

/// Compute the canonical body hash (locked v4.1 V-P0-5).
///
/// Input layout, concatenated then digested with SHA-256:
///
/// ```text
///   u32_be(sealed_sender.len())  || sealed_sender_bytes
///   u32_be(payload.len())        || payload_bytes
/// ```
///
/// Both fields are treated as opaque bytes — the relay never
/// decodes their contents. The hash is 32 bytes and callers
/// serialise it as 64-char lowercase hex for on-disk storage.
///
/// Panics if either field length exceeds `u32::MAX` (~4 GiB) —
/// realistically impossible at production shape but a paranoia
/// guard against a future ingress bug feeding a giant payload.
pub fn compute_body_hash(sealed_sender: &[u8], payload: &[u8]) -> [u8; 32] {
    assert!(
        sealed_sender.len() <= u32::MAX as usize,
        "sealed_sender length {} exceeds u32::MAX; ingress bug",
        sealed_sender.len(),
    );
    assert!(
        payload.len() <= u32::MAX as usize,
        "payload length {} exceeds u32::MAX; ingress bug",
        payload.len(),
    );
    let mut hasher = Sha256::new();
    hasher.update(&(sealed_sender.len() as u32).to_be_bytes());
    hasher.update(sealed_sender);
    hasher.update(&(payload.len() as u32).to_be_bytes());
    hasher.update(payload);
    hasher.finalize().into()
}

/// Convenience: hex-encoded body hash for on-disk / log emission.
pub fn compute_body_hash_hex(sealed_sender: &[u8], payload: &[u8]) -> String {
    hex::encode(compute_body_hash(sealed_sender, payload))
}

/// Length of the prefix each field consumes in the hash pre-image.
/// Callers that need to reason about the hash input size can use
/// this constant instead of the literal `4`.
pub const FIELD_PREFIX_LEN: usize = FIELD_LEN_PREFIX_BYTES;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_of_empty_pair_matches_expected_preimage() {
        // The hash of the (empty, empty) pair MUST equal
        // SHA-256 of the eight zero bytes carrying the two
        // u32-be length prefixes. Computing the reference in-
        // line (rather than hard-coding a hex constant) proves
        // the length-prefix encoding is applied correctly without
        // committing to a magic constant that a future edit
        // could silently invalidate.
        let h = compute_body_hash(&[], &[]);
        let mut reference = Sha256::new();
        reference.update(&[0u8; 8]); // two u32-be zero prefixes
        let expected: [u8; 32] = reference.finalize().into();
        assert_eq!(h, expected);
    }

    #[test]
    fn hash_is_deterministic_across_calls() {
        let a = compute_body_hash(b"phantom-sender", b"phantom-ciphertext");
        let b = compute_body_hash(b"phantom-sender", b"phantom-ciphertext");
        assert_eq!(a, b);
    }

    #[test]
    fn hash_diverges_on_different_sender() {
        let a = compute_body_hash(b"sender-a", b"payload");
        let b = compute_body_hash(b"sender-b", b"payload");
        assert_ne!(a, b);
    }

    #[test]
    fn hash_diverges_on_different_payload() {
        let a = compute_body_hash(b"sender", b"payload-a");
        let b = compute_body_hash(b"sender", b"payload-b");
        assert_ne!(a, b);
    }

    #[test]
    fn length_prefix_prevents_concatenation_collision() {
        // Without a length prefix these two pairs would produce
        // the same digest because their concatenations match:
        //   "abcd" || "efgh"  ==  "abcdef" || "gh"
        // The 4-byte length prefix binds the field boundary into
        // the digest so the two hashes MUST differ.
        let h1 = compute_body_hash(b"abcd", b"efgh");
        let h2 = compute_body_hash(b"abcdef", b"gh");
        assert_ne!(h1, h2);
    }

    #[test]
    fn empty_field_pair_diverges_from_single_byte_field() {
        // Guard against a hypothetical hasher that ignored empty
        // slices: `("", b"")` MUST differ from `("\x00", "")`.
        let h1 = compute_body_hash(b"", b"");
        let h2 = compute_body_hash(b"\x00", b"");
        assert_ne!(h1, h2);
    }

    #[test]
    fn hash_hex_shape_is_64_lowercase_chars() {
        let hex = compute_body_hash_hex(b"any", b"input");
        assert_eq!(hex.len(), 64);
        assert!(hex.chars().all(|c| c.is_ascii_hexdigit()));
        assert!(hex.chars().all(|c| !c.is_ascii_uppercase()));
    }

    #[test]
    fn field_prefix_len_constant_matches_impl() {
        assert_eq!(FIELD_PREFIX_LEN, 4);
    }
}
