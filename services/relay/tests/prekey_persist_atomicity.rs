// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-STATE-DIR-REPAIR PR-1b Round-1 architect verdict P0 —
//! fault-injection tests for `persist_prekey_state`'s file-level
//! rollback contract.
//!
//! **Why a separate binary.**
//!
//! `prekeys::test_fault` is a `pub static AtomicU8` — the seam is
//! process-global by construction because integration tests do NOT
//! see `#[cfg(test)]` on library items, so the seam has to live in
//! every build. That means any test in the SAME binary that runs a
//! `publish`/`consume`/`delete_opk` call concurrently with an
//! injected fault will consume that fault (its persist's
//! `take_if(...)` matches and fires the rollback path). Marking each
//! fault test `#[serial]` only serialises the fault tests against
//! EACH OTHER — sibling tests without the `#[serial]` mark still race.
//!
//! `cargo test` runs each `tests/*.rs` binary in a SEPARATE process by
//! default, so putting these tests in their own file gives them their
//! own copy of the atomic and eliminates all cross-test races without
//! having to annotate every persist-touching test elsewhere.

use ed25519_dalek::{Signature, Signer, SigningKey};
use rand::rngs::OsRng;
use std::sync::atomic::Ordering;

use phantom_relay::prekeys::{
    self, ConsumeError, OneTimePreKeyPublicBundle, PreKeyStore, PublishError, SignedPreKeyPublicBundle,
};

fn unique_identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        *b = seed.wrapping_add(i as u8).wrapping_mul(3);
    }
    hex::encode(buf)
}

fn make_signed_spk(
    signing_kp: &SigningKey,
    key_id: i64,
    created_at_ms: i64,
) -> SignedPreKeyPublicBundle {
    let pubkey = [key_id as u8; 32];
    let mut payload = Vec::new();
    payload.extend_from_slice(b"phantom-spk-v1");
    payload.extend_from_slice(&pubkey);
    payload.extend_from_slice(&created_at_ms.to_be_bytes());
    let sig: Signature = signing_kp.sign(&payload);
    SignedPreKeyPublicBundle {
        key_id,
        public_key_hex: hex::encode(pubkey),
        created_at_ms,
        signature_hex: hex::encode(sig.to_bytes()),
    }
}

fn make_opk(seed: u8) -> OneTimePreKeyPublicBundle {
    let mut id = [0u8; 16];
    id[0] = seed;
    let mut pk = [0u8; 32];
    pk[0] = seed;
    OneTimePreKeyPublicBundle {
        key_id_hex: hex::encode(id),
        public_key_hex: hex::encode(pk),
    }
}

// ── AFTER_WRITE fault — bytes on disk, rollback restores prev_len ────────────
//
// Fires after `writeln!(file, ...)` succeeds (so the new line is IN
// the file's OS write buffer) but before `sync_data`. The persist
// function must roll back to prev_len and re-sync so disk stays
// byte-identical to its pre-call state.

#[serial_test::serial]
#[tokio::test]
async fn persist_write_failure_rolls_back_file() {
    prekeys::test_fault::clear();

    let dir = tempfile::tempdir().expect("tempdir");
    let store = PreKeyStore::new(dir.path());
    let path = dir.path().join("prekeys.jsonl");

    // Seed one valid record so the file has a known prev_len > 0.
    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
    let identity = unique_identity_hex(0xA0);
    let spk_1 = make_signed_spk(&signing_kp, 1, 1_700_000_000_000);
    store
        .publish(&identity, &signing_hex, spk_1, vec![], 0)
        .await
        .expect("seed publish should succeed");
    assert_eq!(store.persist_success.load(Ordering::Relaxed), 1);

    let content_before = std::fs::read(&path).expect("read seed content");
    assert!(!content_before.is_empty(), "seed must have landed");

    prekeys::test_fault::inject(prekeys::test_fault::AFTER_WRITE);

    let spk_2 = make_signed_spk(&signing_kp, 2, 1_700_000_001_000);
    let result = store
        .publish(&identity, &signing_hex, spk_2, vec![], 1_000)
        .await;
    assert!(
        matches!(result, Err(PublishError::PersistFailed(_))),
        "publish under injected AFTER_WRITE fault must surface PersistFailed, got {:?}",
        result
    );

    let content_after = std::fs::read(&path).expect("read post-fault content");
    assert_eq!(
        content_after, content_before,
        "AFTER_WRITE fault must roll back the JSONL to byte-identical prev state"
    );

    assert_eq!(store.persist_success.load(Ordering::Relaxed), 1);
    assert_eq!(store.persist_failed.load(Ordering::Relaxed), 1);

    // RAM must still hold the FIRST publish's SPK.
    let bundle = store
        .consume_bundle(&identity)
        .await
        .expect("consume should succeed after restore")
        .expect("bundle must exist");
    assert_eq!(
        bundle.signed_pre_key.key_id, 1,
        "RAM must retain the first publish's SPK — the failed second publish's \
         rotation must NOT have been committed"
    );

    prekeys::test_fault::clear();
}

// ── AFTER_SYNC_DATA fault — full write + fsync succeeded, rollback still holds ─
//
// Fires after `sync_data` returns Ok (so the new line is durable) but
// before the caller returns Ok. Simulates the pathological case where
// the disk succeeds but a downstream step (e.g. metadata fsync on some
// filesystems, or a defensive check we may add later) reports Err.
// Rollback must still restore prev_len byte-for-byte.

#[serial_test::serial]
#[tokio::test]
async fn persist_sync_data_failure_rolls_back_file() {
    prekeys::test_fault::clear();

    let dir = tempfile::tempdir().expect("tempdir");
    let store = PreKeyStore::new(dir.path());
    let path = dir.path().join("prekeys.jsonl");

    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
    let identity = unique_identity_hex(0xA1);
    let spk = make_signed_spk(&signing_kp, 1, 1_700_000_000_000);
    let opk = make_opk(0xB2);
    let opk_key_id = opk.key_id_hex.clone();
    store
        .publish(&identity, &signing_hex, spk, vec![opk], 0)
        .await
        .expect("seed publish should succeed");
    let content_before = std::fs::read(&path).expect("read seed content");

    prekeys::test_fault::inject(prekeys::test_fault::AFTER_SYNC_DATA);

    let result = store.consume_bundle(&identity).await;
    assert!(
        matches!(result, Err(ConsumeError::PersistFailed(_))),
        "consume under injected AFTER_SYNC_DATA fault must surface PersistFailed"
    );

    let content_after = std::fs::read(&path).expect("read post-fault content");
    assert_eq!(
        content_after, content_before,
        "AFTER_SYNC_DATA fault must roll back the JSONL byte-for-byte"
    );

    let bundle = store
        .consume_bundle(&identity)
        .await
        .expect("consume after restore should succeed")
        .expect("bundle must exist");
    let re_opk = bundle
        .one_time_pre_key
        .expect("OPK must still be present after the failed consume");
    assert_eq!(
        re_opk.key_id_hex, opk_key_id,
        "the SAME OPK must be re-returned — failed consume must not burn it"
    );

    prekeys::test_fault::clear();
}
