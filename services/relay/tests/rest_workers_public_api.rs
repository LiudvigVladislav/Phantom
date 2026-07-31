// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M3a — external-crate smoke test
//! for the public shard-worker runtime API.
//!
//! Integration tests in `services/relay/tests/*.rs` compile as
//! SEPARATE binary crates that depend on `phantom_relay` as an
//! external library — the same visibility boundary that
//! `main.rs` sits behind.
//!
//! **Round-7 F3**: this file constructs its `BootLoaderResult`
//! by calling the real production
//! [`phantom_relay::boot_loader::boot`] against a temp state
//! directory. Round-6 kept a `#[doc(hidden)] pub` /
//! feature-gated `__for_test_only` factory that the round-7
//! review flagged as (a) still linkable from production if the
//! feature was enabled and (b) requiring
//! `cargo test --features test-support` to reach — the
//! standard `cargo test` skipped the target. The real-boot
//! shape removes both concerns: no test-only symbol survives in
//! any binary, and the default `cargo test` run exercises the
//! cross-crate boundary end-to-end.

use std::sync::Arc;
use std::time::Duration;

use phantom_relay::boot_loader::{
    boot, BootConfig, OwnershipExpectation, PreflightCaps,
};
use phantom_relay::capacity_ledger::CapacityCaps;
use phantom_relay::queue_meta::{self, Phase, QueueMeta, META_VERSION};
use phantom_relay::rest_workers::{
    spawn_worker_runtime, FatalReason, RestOp, RuntimeSendError, SendCandidate, SpawnError,
    SpecError, WorkerRuntimeSpec, REST_WORKER_COUNT,
};
use phantom_relay::seq_mac::SeqMacRootKey;
use phantom_relay::tombstone_config::TombstoneConfig;
use phantom_relay::worker_pool::PoolStateKind;
use tempfile::TempDir;
use tokio::sync::broadcast;

const TEST_MAC_KEY_BYTES: [u8; 32] = [0x77u8; 32];

fn caps() -> CapacityCaps {
    CapacityCaps {
        max_envelopes: 100,
        max_bytes: 1_000_000,
        ram_budget: 1_000_000,
    }
}

/// Prepare a valid state dir with a `phase=Ready` meta whose
/// `seq_mac_key_fingerprint` matches the running root key so
/// [`boot`] returns cleanly.
fn build_state_dir_with_meta(boot_generation: u32) -> TempDir {
    let dir = TempDir::new().unwrap();
    let root_key = SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES);
    std::fs::create_dir_all(dir.path().join("queue")).unwrap();
    let meta = QueueMeta {
        version: META_VERSION,
        phase: Phase::Ready,
        boot_generation,
        seq_mac_key_fingerprint: root_key.fingerprint(),
    };
    queue_meta::write_meta(dir.path(), &meta).unwrap();
    dir
}

fn boot_config(dir: &TempDir) -> BootConfig {
    let root_key = SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES);
    BootConfig {
        state_dir: dir.path().to_path_buf(),
        caps: PreflightCaps::for_tests(),
        tombstone: TombstoneConfig::from_secs(172_800).unwrap(),
        current_seq_mac_key_fingerprint: root_key.fingerprint(),
        ownership: OwnershipExpectation::permissive_for_tests(),
    }
}

fn build_spec_via_real_boot(
    dir: &TempDir,
    fatal_tx: broadcast::Sender<FatalReason>,
) -> Result<WorkerRuntimeSpec, SpecError> {
    let boot_result = boot(&boot_config(dir)).expect("boot must succeed");
    let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES));
    WorkerRuntimeSpec::from_boot(boot_result, 8, key, caps(), fatal_tx)
}

#[tokio::test]
async fn spawn_worker_runtime_is_reachable_from_external_crate_via_real_boot() {
    let dir = build_state_dir_with_meta(1);
    let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(64);
    let spec = build_spec_via_real_boot(&dir, fatal_tx).expect("matching fingerprint");
    let runtime = match spawn_worker_runtime(spec) {
        Ok(r) => r,
        Err(_) => panic!("spawn failed"),
    };
    assert_eq!(runtime.expected_worker_count(), REST_WORKER_COUNT);
    assert!(matches!(runtime.state_kind(), PoolStateKind::Running));
    // Fresh boot: no seeded records, no tombstones.
    assert!(runtime.rest_store().read().await.is_empty());
    assert!(runtime.store().read().await.is_empty());
    assert_eq!(runtime.tombstone_count(), 0);
    let snap = runtime.capacity().snapshot();
    assert_eq!(snap.active_envelopes, 0);
    assert_eq!(snap.active_bytes, 0);
    assert_eq!(snap.tombstone_bytes, 0);
    runtime.close();
    let _ = runtime.drain_handles(Duration::from_secs(5)).await;
}

#[tokio::test]
async fn spawn_worker_runtime_rejects_out_of_range_boot_generation_from_external_crate() {
    // For this one we need a meta whose boot_generation is out of
    // range. `boot()` would refuse it directly (via
    // BootError::GenerationSaturation) BEFORE returning a
    // BootLoaderResult, so the spawn-level check can't fire
    // through the real-boot path. Instead we verify `boot()`'s
    // own refusal — the runtime layer's guard is still exercised
    // by the in-module `mod tests` suite (which has same-crate
    // access to for_lib_test).
    let dir = TempDir::new().unwrap();
    let root_key = SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES);
    std::fs::create_dir_all(dir.path().join("queue")).unwrap();
    let meta = QueueMeta {
        version: META_VERSION,
        phase: Phase::Ready,
        boot_generation: queue_meta::MAX_BOOT_GENERATION + 1,
        seq_mac_key_fingerprint: root_key.fingerprint(),
    };
    queue_meta::write_meta(dir.path(), &meta).unwrap();
    // boot() refuses right here.
    assert!(boot(&boot_config(&dir)).is_err());

    // Companion assertion: the SpawnError type IS visible from
    // an external crate and matches the runtime shape — proves
    // the API surface still compiles from a separate binary
    // crate (round-3 F1 regression guard).
    let _ = std::mem::size_of::<SpawnError>();
}

#[tokio::test]
async fn spawn_worker_runtime_accepts_boot_generation_at_cap() {
    // boot() bumps the stored generation by 1, so to reach exactly
    // MAX_BOOT_GENERATION we plant MAX-1 and let boot bump it to MAX.
    let dir = build_state_dir_with_meta(queue_meta::MAX_BOOT_GENERATION - 1);
    let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
    let spec = build_spec_via_real_boot(&dir, fatal_tx).expect("matching fingerprint");
    let runtime = match spawn_worker_runtime(spec) {
        Ok(r) => r,
        Err(_) => panic!("spawn refused a legal generation at MAX_BOOT_GENERATION"),
    };
    runtime.close();
    let _ = runtime.drain_handles(Duration::from_secs(5)).await;
}

#[tokio::test]
async fn worker_runtime_full_lifecycle_close_drain_all_clean_closed() {
    let dir = build_state_dir_with_meta(1);
    let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(64);
    let spec = build_spec_via_real_boot(&dir, fatal_tx).expect("matching fingerprint");
    let runtime = match spawn_worker_runtime(spec) {
        Ok(r) => r,
        Err(_) => panic!("spawn failed"),
    };
    assert_eq!(runtime.expected_worker_count(), REST_WORKER_COUNT);
    runtime.close();
    assert!(matches!(runtime.state_kind(), PoolStateKind::Closing));

    let outcomes = runtime
        .drain_handles(Duration::from_secs(5))
        .await
        .expect("drain must succeed within 5s");

    assert_eq!(outcomes.len(), REST_WORKER_COUNT);
    let mut worker_ids: Vec<usize> = outcomes.iter().map(|o| o.worker_id).collect();
    worker_ids.sort_unstable();
    let expected_ids: Vec<usize> = (0..REST_WORKER_COUNT).collect();
    assert_eq!(worker_ids, expected_ids);
    for outcome in &outcomes {
        assert!(outcome.is_clean(), "worker {} not clean", outcome.worker_id);
    }
    assert!(matches!(runtime.state_kind(), PoolStateKind::Closed));
}

#[tokio::test]
async fn from_boot_refuses_seq_mac_key_fingerprint_mismatch_from_external_crate() {
    let dir = TempDir::new().unwrap();
    let root_key = SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES);
    std::fs::create_dir_all(dir.path().join("queue")).unwrap();
    let meta = QueueMeta {
        version: META_VERSION,
        phase: Phase::Ready,
        boot_generation: 1,
        seq_mac_key_fingerprint: root_key.fingerprint(),
    };
    queue_meta::write_meta(dir.path(), &meta).unwrap();
    let boot_result = boot(&boot_config(&dir)).expect("boot");
    let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
    // Provide a DIFFERENT root key than the one the boot meta
    // was written with — fingerprint mismatch MUST refuse.
    let wrong_key = Arc::new(SeqMacRootKey::from_bytes([0x22u8; 32]));
    let expected_boot_fp = root_key.fingerprint();
    let expected_wrong_fp = wrong_key.fingerprint();
    let result = WorkerRuntimeSpec::from_boot(boot_result, 8, wrong_key, caps(), fatal_tx);
    match result {
        Err(SpecError::SeqMacKeyFingerprintMismatch {
            boot_fingerprint,
            provided_fingerprint,
        }) => {
            assert_eq!(boot_fingerprint, expected_boot_fp);
            assert_eq!(provided_fingerprint, expected_wrong_fp);
        }
        _ => panic!("expected fingerprint mismatch"),
    }
}

#[tokio::test]
async fn worker_runtime_try_send_empty_recipient_boundary_fatal_from_external_crate() {
    let dir = build_state_dir_with_meta(1);
    let (fatal_tx, mut fatal_rx) = broadcast::channel::<FatalReason>(16);
    let spec = build_spec_via_real_boot(&dir, fatal_tx).expect("matching fingerprint");
    let runtime = match spawn_worker_runtime(spec) {
        Ok(r) => r,
        Err(_) => panic!("spawn failed"),
    };

    let (reply_tx, _reply_rx) = tokio::sync::oneshot::channel();
    let op = RestOp::Send {
        recipient: String::new(),
        candidate: SendCandidate {
            id: "env-empty".into(),
            sealed_sender: "sender".into(),
            payload: "payload".into(),
            sequence_ts: 0,
            expires_at: 0,
        },
        reply: reply_tx,
    };
    match runtime.try_send(op) {
        Err(RuntimeSendError::EmptyRecipient) => {}
        _ => panic!("expected RuntimeSendError::EmptyRecipient"),
    }
    let fatal = fatal_rx.recv().await.expect("fatal channel");
    assert!(matches!(
        fatal,
        FatalReason::IngressBypassAtBoundary { at }
            if at.contains("empty recipient")
    ));

    runtime.close();
    let _ = runtime.drain_handles(Duration::from_secs(5)).await;
}

#[tokio::test]
async fn worker_runtime_try_send_empty_recipient_boundary_fatal_survives_closing_from_external_crate(
) {
    let dir = build_state_dir_with_meta(1);
    let (fatal_tx, mut fatal_rx) = broadcast::channel::<FatalReason>(16);
    let spec = build_spec_via_real_boot(&dir, fatal_tx).expect("matching fingerprint");
    let runtime = match spawn_worker_runtime(spec) {
        Ok(r) => r,
        Err(_) => panic!("spawn failed"),
    };
    runtime.close();
    assert!(matches!(runtime.state_kind(), PoolStateKind::Closing));

    let (reply_tx, _reply_rx) = tokio::sync::oneshot::channel();
    let op = RestOp::Send {
        recipient: String::new(),
        candidate: SendCandidate {
            id: "env-empty-closing".into(),
            sealed_sender: "sender".into(),
            payload: "payload".into(),
            sequence_ts: 0,
            expires_at: 0,
        },
        reply: reply_tx,
    };
    match runtime.try_send(op) {
        Err(RuntimeSendError::EmptyRecipient) => {}
        _ => panic!("expected EmptyRecipient even when Closing"),
    }
    let fatal = fatal_rx.recv().await.expect("fatal channel");
    assert!(matches!(fatal, FatalReason::IngressBypassAtBoundary { .. }));

    let _ = runtime.drain_handles(Duration::from_secs(5)).await;
}
