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

// ── R2-1: first-create rollback — absent file, fault, still absent ───────────
//
// Round-2 architect P1: pre-round-2 shape collapsed every metadata
// error into `prev_len=0`, so a first-create persist that failed left
// the freshly-created empty file on disk. Post-round-2 tracks
// `PreOpSnapshot::Absent` and `remove_file`s during rollback so the
// on-disk state matches the pre-call "absent" shape.

#[serial_test::serial]
#[tokio::test]
async fn persist_first_create_failure_leaves_file_absent() {
    prekeys::test_fault::clear();

    let dir = tempfile::tempdir().expect("tempdir");
    let path = dir.path().join("prekeys.jsonl");
    // Precondition: no prior file.
    assert!(
        !path.exists(),
        "test precondition: prekeys.jsonl must be absent before the first persist"
    );

    let store = PreKeyStore::new(dir.path());
    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
    let identity = unique_identity_hex(0xC0);
    let spk = make_signed_spk(&signing_kp, 1, 1_700_000_000_000);

    // Arm AFTER_WRITE so persist enters open (creating the file), then
    // writeln, then the fault fires and rollback runs with snap=Absent.
    prekeys::test_fault::inject(prekeys::test_fault::AFTER_WRITE);

    let result = store
        .publish(&identity, &signing_hex, spk, vec![], 0)
        .await;
    assert!(
        matches!(result, Err(PublishError::PersistFailed(_))),
        "first-create publish under AFTER_WRITE must surface PersistFailed, got {:?}",
        result
    );

    // Round-2 P1 contract: rollback must have REMOVED the file, not
    // left it as a 0-byte artefact. The on-disk state must exactly
    // match the pre-call "absent" shape.
    assert!(
        !path.exists(),
        "prekeys.jsonl MUST be absent after a first-create persist failure — \
         rollback must remove the freshly-created file (round-2 P1)"
    );

    // RAM must NOT have the identity (nothing committed).
    let bundle = store
        .consume_bundle(&identity)
        .await
        .expect("consume should succeed");
    assert!(
        bundle.is_none(),
        "identity must not appear in the RAM map after a failed first publish"
    );

    prekeys::test_fault::clear();
}

// ── R2-2: rollback failure terminates the whole process via abort() ─────────
//
// Round-2 architect P0: `panic!` in release builds with default
// `panic = "unwind"` only unwinds the current thread — a persist call
// inside an axum request task would unwind ONLY that task while Tokio
// catches the panic and keeps serving other requests with a store
// whose RAM and disk are out of sync. The mini-lock §14 rule 11
// requirement is UNCONDITIONAL process termination. Post-round-2 the
// rollback path uses `std::process::abort()` which raises SIGABRT
// (Unix) / calls Windows's ExitProcess-equivalent and cannot be
// caught. This test spawns the current test binary as a subprocess
// with an env var that flips the test into "child mode" and:
//   1. Arms `AFTER_WRITE` to route into rollback.
//   2. Arms `arm_rollback_failure()` so the rollback returns to
//      `abort()` before restoring the file.
//   3. Calls `publish` and prints `CHILD_REACHED_END` if we somehow
//      return (which is a bug).
// The parent then asserts the child exited non-zero (abort) and that
// the CHILD_REACHED_END sentinel is NOT in the child's output.

const ABORT_CHILD_ENV: &str = "PR1B_ROUND2_ABORT_CHILD";

#[test]
fn rollback_failure_calls_abort_and_terminates_process() {
    if std::env::var(ABORT_CHILD_ENV).is_ok() {
        run_abort_child_body();
        // If we reach here the child DIDN'T abort — that's the bug.
        eprintln!("CHILD_REACHED_END_WITHOUT_ABORTING");
        std::process::exit(99);
    }

    // ── Parent mode: spawn the current test binary as a child with
    //    the env var set + args that select this exact test function.
    let exe = std::env::current_exe().expect("current_exe for child spawn");
    let out = std::process::Command::new(&exe)
        .env(ABORT_CHILD_ENV, "1")
        .args([
            "--exact",
            "rollback_failure_calls_abort_and_terminates_process",
            "--nocapture",
            "--test-threads=1",
        ])
        .output()
        .expect("spawn child");

    let stdout = String::from_utf8_lossy(&out.stdout);
    let stderr = String::from_utf8_lossy(&out.stderr);

    // `abort()` never returns Ok. Success would mean the test framework
    // wrapped the child call and reported OK — that's the failure mode.
    assert!(
        !out.status.success(),
        "child MUST terminate non-zero on abort(); got status={:?}, stdout={}, stderr={}",
        out.status,
        stdout,
        stderr
    );
    assert!(
        !stdout.contains("CHILD_REACHED_END_WITHOUT_ABORTING")
            && !stderr.contains("CHILD_REACHED_END_WITHOUT_ABORTING"),
        "child body reached the end without aborting — rollback failure did NOT terminate the process. \
         stdout={} stderr={}",
        stdout,
        stderr
    );
    // Belt-and-braces: the `FATAL:` prefix from `rollback_or_abort`
    // must appear in the child's stderr so an operator would see it.
    assert!(
        stderr.contains("FATAL: prekey persist rollback")
            || stdout.contains("FATAL: prekey persist rollback"),
        "child stderr must carry the FATAL prefix from rollback_or_abort. \
         stdout={} stderr={}",
        stdout,
        stderr
    );
}

// Extracted so the child mode does not have to be `async`: the abort
// happens synchronously inside `publish`'s persist call, so we drive
// it via a fresh single-threaded Tokio runtime rather than an
// `#[tokio::test]` (which the child re-enters through libtest and
// isn't ideal for a call that must NEVER return).
fn run_abort_child_body() {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("child tokio runtime");
    rt.block_on(async {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = PreKeyStore::new(dir.path());
        let signing_kp = SigningKey::generate(&mut OsRng);
        let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let identity = unique_identity_hex(0xC1);
        let spk = make_signed_spk(&signing_kp, 1, 1_700_000_000_000);

        // Arm BOTH: AFTER_WRITE routes control into rollback;
        // arm_rollback_failure() then aborts inside rollback.
        prekeys::test_fault::clear();
        prekeys::test_fault::arm_rollback_failure();
        prekeys::test_fault::inject(prekeys::test_fault::AFTER_WRITE);

        // The `publish` call MUST NOT return.
        let _ = store.publish(&identity, &signing_hex, spk, vec![], 0).await;
    });
}
