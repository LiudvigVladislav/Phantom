// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Trek 2 Stage 1.x golden vectors for the `seq_mac` integrity tag and
//! the per-identity verify-key derivation.
//!
//! Each test computes the MAC / derived key via the production code
//! path (`SeqMacRootKey::derive_verify_key` and
//! `SeqMacVerifyKey::compute_seq_mac`) and asserts properties against
//! byte-pinned expected values. A future change to the canonical input
//! encoding, the domain tag, or the HMAC scheme produces a hex
//! mismatch that surfaces under the named test in `cargo test` output.
//!
//! All vectors use the all-zero root key `[0u8; 32]` so the expected
//! hex values are reproducible from outside the test binary by anyone
//! with the canonical input spec.

use phantom_relay::seq_mac::{
    seq_mac_to_hex, SeqMacRootKey, ENVELOPE_ID_MAX_BYTES, SEQ_MAC_DOMAIN_TAG,
    SEQ_MAC_KEY_DOMAIN_TAG,
};

// ── Domain tag pin ───────────────────────────────────────────────────────────

/// Pins the byte content of the MAC-computation domain tag. Any change
/// to the tag bytes is a wire-incompatible MAC scheme rotation and
/// must update the tag to `v2\x00`, not silently mutate the v1 bytes.
#[test]
fn seq_mac_domain_tag_is_phantom_seq_mac_v1_null() {
    assert_eq!(SEQ_MAC_DOMAIN_TAG, b"phantom-seq-mac-v1\x00");
    assert_eq!(SEQ_MAC_DOMAIN_TAG.len(), 19);
}

/// Pins the byte content of the key-derivation domain tag. Distinct
/// from the MAC tag so the same root-key bytes cannot be misused in a
/// cross-domain forgery.
#[test]
fn seq_mac_key_domain_tag_is_phantom_seq_mac_key_v1_null() {
    assert_eq!(SEQ_MAC_KEY_DOMAIN_TAG, b"phantom-seq-mac-key-v1\x00");
    assert_eq!(SEQ_MAC_KEY_DOMAIN_TAG.len(), 23);
}

/// Pins the `envelope_id` length cap at `u16::MAX` so a future widening
/// is an explicit decision, not a silent encoding change.
#[test]
fn envelope_id_max_bytes_is_u16_max() {
    assert_eq!(ENVELOPE_ID_MAX_BYTES, u16::MAX as usize);
}

// ── Verify-key derivation determinism ────────────────────────────────────────

/// Deriving the verify key for the same `(root, identity)` pair is
/// deterministic. Pins the HMAC-SHA-256 scheme + the
/// `b"phantom-seq-mac-key-v1\x00" || identity_hex` canonical input.
#[test]
fn derive_verify_key_is_deterministic_for_zero_root() {
    let root = SeqMacRootKey::from_bytes([0u8; 32]);
    let identity = "a".repeat(64);
    let key_a = root.derive_verify_key(&identity);
    let key_b = root.derive_verify_key(&identity);
    assert_eq!(key_a.to_hex(), key_b.to_hex());
    // Output is 32 raw bytes → 64-char lowercase hex.
    assert_eq!(key_a.to_hex().len(), 64);
    assert!(key_a.to_hex().chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
    // M8 parity pin — the Kotlin test fixture's `deriveVerifyKey(zero,
    // "a"x64)` MUST produce the same 32-byte HMAC output. Pinning the
    // derived-key hex here closes the upstream side of the parity
    // chain: a Kotlin drift in the key-domain-tag encoding produces a
    // mismatch on THIS hex; a drift in `compute_seq_mac` produces a
    // mismatch on the per-vector hexes below.
    assert_eq!(key_a.to_hex(), "86e76fe25be8adb09a65d25b62f758da783b47e8f7dc3359676fcdf1d777ad50");
}

/// Deriving for two distinct identities under the same root produces
/// distinct verify keys. The HMAC output collision probability for
/// 32-byte outputs is `2^-128`; any test-time collision indicates the
/// identity input was silently dropped from the canonical input.
#[test]
fn derive_verify_key_differs_per_identity() {
    let root = SeqMacRootKey::from_bytes([0u8; 32]);
    let id_a = "a".repeat(64);
    let id_b = "b".repeat(64);
    let key_a = root.derive_verify_key(&id_a);
    let key_b = root.derive_verify_key(&id_b);
    assert_ne!(key_a.to_hex(), key_b.to_hex());
}

// ── MAC computation vectors ──────────────────────────────────────────────────

fn zero_root() -> SeqMacRootKey {
    SeqMacRootKey::from_bytes([0u8; 32])
}

fn all_a_identity() -> String {
    "a".repeat(64)
}

/// Vector 1 — all-zero inputs. Smallest representable envelope: zero
/// seq, all-zero UUID-shaped envelope_id, zero sequence_ts. Pins the
/// MAC over the lowest field values.
#[test]
fn vector_1_all_zero_inputs_round_trips() {
    let root = zero_root();
    let key = root.derive_verify_key(&all_a_identity());
    let mac_a = key
        .compute_seq_mac(
            &all_a_identity(),
            0,
            "00000000-0000-0000-0000-000000000000",
            0,
        )
        .unwrap();
    let mac_b = key
        .compute_seq_mac(
            &all_a_identity(),
            0,
            "00000000-0000-0000-0000-000000000000",
            0,
        )
        .unwrap();
    assert_eq!(mac_a, mac_b, "vector 1 must be deterministic");
    let hex_str = seq_mac_to_hex(&mac_a);
    assert_eq!(hex_str.len(), 64);
    // M8 parity pin — the Kotlin commonTest
    // `SeqMacVerifierTest::vector_1_byte_pinned_oracle` asserts the
    // SAME hex against the SAME inputs. A canonical-encoding drift on
    // either side breaks both tests.
    assert_eq!(hex_str, "e8c5cba064c06a63452ae44d4d54552e447c9f08d1ed179271fbf447454c3761");
}

/// Vector 2 — all-ones inputs. Largest representable u64 fields,
/// all-`f` envelope_id, all-`f` identity. Pins the MAC at the highest
/// field-value corner, surfacing any unsigned-overflow regressions in
/// the canonical encoding.
#[test]
fn vector_2_all_ones_inputs_round_trips() {
    let root = zero_root();
    let identity = "f".repeat(64);
    let key = root.derive_verify_key(&identity);
    let post_quantize_ts = u64::MAX - (u64::MAX % 60_000);
    let mac_a = key
        .compute_seq_mac(
            &identity,
            u64::MAX,
            "ffffffff-ffff-ffff-ffff-ffffffffffff",
            post_quantize_ts,
        )
        .unwrap();
    let mac_b = key
        .compute_seq_mac(
            &identity,
            u64::MAX,
            "ffffffff-ffff-ffff-ffff-ffffffffffff",
            post_quantize_ts,
        )
        .unwrap();
    assert_eq!(mac_a, mac_b, "vector 2 must be deterministic");
    // M8 parity pin. The `post_quantize_ts` value here is
    // `u64::MAX - (u64::MAX % 60_000) = 18446744073709500000` =
    // `0xFFFFFFFFFFFF3660` BE. The Kotlin commonTest must encode this
    // same u64 byte pattern (via ULong arithmetic, NOT signed-Long mod
    // which produces a different floor-mod value at the high end).
    assert_eq!(seq_mac_to_hex(&mac_a), "8d6f630d534871aeb6fee10e5c091537b44d9016f275911e8f741e6b4daad3cc");
    // Also pin the post-quantize timestamp value so a Kotlin
    // implementation that uses signed-Long mod fails noisily rather
    // than silently producing a different MAC.
    assert_eq!(post_quantize_ts, 18_446_744_073_709_500_000u64);
}

/// Vector 3 — realistic mid-range inputs. Production-shaped values
/// (32-char hex envelope_id from Stage 2A `EnvelopeId.random()`).
#[test]
fn vector_3_realistic_mid_range_inputs_round_trips() {
    let root = zero_root();
    let identity = "a1".repeat(32);
    let key = root.derive_verify_key(&identity);
    let envelope_id_32_char_hex = "0123456789abcdef0123456789abcdef";
    let mac_a = key
        .compute_seq_mac(&identity, 1_234, envelope_id_32_char_hex, 1_700_000_000_000)
        .unwrap();
    let mac_b = key
        .compute_seq_mac(&identity, 1_234, envelope_id_32_char_hex, 1_700_000_000_000)
        .unwrap();
    assert_eq!(mac_a, mac_b);
    // M8 parity pin — Kotlin oracle equivalent.
    assert_eq!(seq_mac_to_hex(&mac_a), "b75e744b3f00b017f98ff9cf935519a8fed2c62ac1af380144fcb3c0079b1ec2");
}

/// Vector 4 — quantization-boundary canary. The same envelope under
/// pre-quantize `sequence_ts` (1_700_000_000_001) versus post-quantize
/// (1_700_000_000_000) MUST produce DIFFERENT MACs. This pins the
/// "MAC is computed over the STORED, post-quantize value" contract
/// from the locked Lock-1 spec. A future regression that accidentally
/// hashed the raw client-supplied `sequence_ts` (pre-quantize) would
/// break this test, surfacing the bug before any client cursor goes
/// wrong on the wire.
#[test]
fn vector_4_quantization_boundary_canary_pre_vs_post_produces_different_macs() {
    let root = zero_root();
    let identity = "c".repeat(64);
    let key = root.derive_verify_key(&identity);

    let mac_pre = key
        .compute_seq_mac(&identity, 1, "envelope-id", 1_700_000_000_001)
        .unwrap();
    let mac_post = key
        .compute_seq_mac(&identity, 1, "envelope-id", 1_700_000_000_000)
        .unwrap();

    assert_ne!(
        mac_pre, mac_post,
        "pre-quantize and post-quantize sequence_ts MUST hash to \
         different MACs — pins the store-time computation contract"
    );
    // M8 parity pins — both pre- and post-quantize sides.
    assert_eq!(seq_mac_to_hex(&mac_pre), "206f4915a664b5f6c70f77cb16447f11fef23e76c2cd735ca1ff57b03816c81b");
    assert_eq!(seq_mac_to_hex(&mac_post), "5586e31412e0cdec746585ea4181f7e019f0249ffc9acf8287f43297b720e1d3");

    // The MAC computation function does not quantize — it hashes
    // whatever sequence_ts is passed. The quantization happens
    // upstream in `mirror_envelope_to_rest_store::quantize_sequence_ts_to_60s`.
    // The store-time contract is therefore: (a) the mirror quantizes
    // BEFORE computing the MAC, and (b) `drain_eligible` reads the
    // stored MAC verbatim (no recomputation). This unit-level vector
    // demonstrates the necessary half — distinct timestamp inputs
    // hash to distinct MACs — so an integration test that sends a
    // non-quantized timestamp and polls back the MAC can verify the
    // mirror applied the quantize step before signing.
}

/// Vector 5 — sanity vector for routine drift detection. Mid-range
/// realistic inputs with a different `envelope_id` than vector 3,
/// crossing the cross-input differentiation already covered by
/// the inline unit tests in `seq_mac.rs`.
#[test]
fn vector_5_drift_detector_different_envelope_id_changes_mac() {
    let root = zero_root();
    let identity = "a1".repeat(32);
    let key = root.derive_verify_key(&identity);
    let mac_a = key
        .compute_seq_mac(&identity, 1_234, "0123456789abcdef0123456789abcdef", 1_700_000_000_000)
        .unwrap();
    let mac_b = key
        .compute_seq_mac(&identity, 1_234, "fedcba9876543210fedcba9876543210", 1_700_000_000_000)
        .unwrap();
    assert_ne!(mac_a, mac_b);
    // M8 parity pins — both envelope_id sides.
    assert_eq!(seq_mac_to_hex(&mac_a), "b75e744b3f00b017f98ff9cf935519a8fed2c62ac1af380144fcb3c0079b1ec2");
    assert_eq!(seq_mac_to_hex(&mac_b), "0391e1014a03ed6792dec2b6c4cef6d15ef780867d4abcbbd6a2d09a2cda235e");
}

/// Boundary vector — empty `envelope_id` does not panic.
/// `compute_seq_mac` writes a `u16-BE 0` length prefix and zero bytes
/// of envelope_id content. Verifies the encoding handles the zero-
/// length case without overflow.
#[test]
fn boundary_empty_envelope_id_does_not_panic() {
    let root = zero_root();
    let key = root.derive_verify_key(&all_a_identity());
    let result = key.compute_seq_mac(&all_a_identity(), 1, "", 60_000);
    assert!(result.is_ok());
    let mac = result.unwrap();
    // M8 parity pin — empty envelope_id produces a 101-byte canonical
    // input (no envelope_id bytes between the u16-BE `0x00 0x00`
    // length prefix and the sequence_ts). The Kotlin oracle test
    // pins the SAME hex.
    assert_eq!(seq_mac_to_hex(&mac), "ba9c33aaec37bc6add50a5577db82f81b220541accc2548e19ab22650ca6e7b1");
}

/// Boundary vector — `envelope_id` at exactly `u16::MAX` UTF-8 bytes
/// is accepted (the length prefix fits exactly). One byte over rejects.
#[test]
fn boundary_envelope_id_at_u16_max_accepted_one_over_rejected() {
    let root = zero_root();
    let key = root.derive_verify_key(&all_a_identity());

    let at_limit = "x".repeat(ENVELOPE_ID_MAX_BYTES);
    assert!(key
        .compute_seq_mac(&all_a_identity(), 1, &at_limit, 60_000)
        .is_ok());

    let one_over = "x".repeat(ENVELOPE_ID_MAX_BYTES + 1);
    assert!(key
        .compute_seq_mac(&all_a_identity(), 1, &one_over, 60_000)
        .is_err());
}

/// Boundary vector — multi-byte UTF-8 characters in `envelope_id`.
/// Pins that the byte length is computed via `String::len()` (which
/// returns byte length on UTF-8) and that the canonical input
/// commits to UTF-8 bytes, not Unicode codepoints.
#[test]
fn boundary_multi_byte_utf8_envelope_id_is_encoded_by_byte_length() {
    let root = zero_root();
    let key = root.derive_verify_key(&all_a_identity());
    // 'Ω' is 2 UTF-8 bytes. Two distinct envelope_ids of the same
    // character length but different byte length MUST hash differently.
    let mac_omega = key
        .compute_seq_mac(&all_a_identity(), 1, "Ω", 60_000)
        .unwrap();
    let mac_x = key
        .compute_seq_mac(&all_a_identity(), 1, "x", 60_000)
        .unwrap();
    assert_ne!(mac_omega, mac_x);
    // M8 parity pins for the multi-byte UTF-8 boundary. The Kotlin
    // oracle equivalent pins the same hex values; a Kotlin
    // implementation that used `String.length` (UTF-16 char count)
    // instead of `encodeToByteArray().size` for envelope_id_len would
    // produce the same length-prefix bytes for "Ω" and "x" but
    // different envelope_id payload bytes — so this distinctness
    // assertion alone does NOT catch the regression, but the
    // byte-pinned hex assertions HERE do.
    assert_eq!(seq_mac_to_hex(&mac_omega), "24c56db55112bfcc53e3dae709c3d84764415659398a43b9926c4fba1877adda");
    assert_eq!(seq_mac_to_hex(&mac_x), "5d1e5ff6287b141be2fe8d6fc3eaa6833488e579c7eea545e75d1d5a9096d2ae");
}

// ── M-B7 oracle pin (Stage 2B-B C1) ──────────────────────────────────────────

/// Trek 2 Stage 2B-B M-B7 — byte-pinned hex oracle for a multi-byte
/// UTF-8 envelope_id. The Kotlin commonTest equivalent at
/// `shared/core/transport/src/commonTest/kotlin/phantom/core/transport/SeqMacVerifierTest.kt::mb7_multi_byte_utf8_byte_pinned_oracle_matches_rust`
/// pins the SAME hex value computed against the SAME inputs. A change
/// to the canonical encoding on either side breaks both tests
/// simultaneously, surfacing the drift before any wire packet ships.
///
/// Inputs:
///
///   root_key      = [0u8; 32]
///   identity_hex  = "a" × 64
///   verify_key    = derive_verify_key(root_key, identity)
///   seq           = 42
///   envelope_id   = "Ω"   (Greek capital omega, 2 UTF-8 bytes 0xCE 0xA9)
///   sequence_ts   = 1_700_000_000_000  (already on the 60s quantize boundary)
#[test]
fn vector_mb7_multi_byte_utf8_oracle_pin() {
    let root = zero_root();
    let identity = all_a_identity();
    let key = root.derive_verify_key(&identity);
    let mac = key
        .compute_seq_mac(&identity, 42, "Ω", 1_700_000_000_000)
        .unwrap();
    let hex = seq_mac_to_hex(&mac);
    // Pinned hex — captured by running this test once with a placeholder
    // (`assert_eq!(hex, "FAILME")`) and pasting the observed value into
    // both this file AND the Kotlin commonTest pin. Re-generating
    // requires editing both files together.
    assert_eq!(
        hex,
        "9173956a76ba212e35989fee7768defd962c5fdff610b12ba5c584adda2af3dd",
        "M-B7 oracle pin must match the Kotlin commonTest pin verbatim",
    );
}
