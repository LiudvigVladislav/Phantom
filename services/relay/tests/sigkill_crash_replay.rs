// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M5a-3 — SIGKILL crash-replay tests.
//!
//! **Architect scope-lock (2026-07-30 M5a-2 GREEN + M5a-3 authorised):**
//! "M5a-3 разрешён с зафиксированной матрицей ровно 12 SIGKILL child
//! runs. Atomic cases вызывают `write_atomic` напрямую; sweep
//! проверяется отдельно для Queued и Tombstone; каждый parent
//! подтверждает `signal() == Some(9)`."
//!
//! Twelve subprocess child runs across 8 distinct failpoint names
//! (M5a-2 placements) — each parent test:
//!   1. Sets up per-scenario state on disk (empty, pre-seeded old
//!      bytes, pre-seeded expired Queued, pre-seeded past-horizon
//!      AckedTombstone, …).
//!   2. Spawns a child via `Command::new(current_exe())` targeting
//!      the dispatch `#[test]` `m5a3_child_dispatch`, arming the
//!      corresponding failpoint via `PHANTOM_FAILPOINT=<name>:block`
//!      + a scenario key via `PHANTOM_M5A3_SCENARIO=<key>`.
//!   3. Streams the child's stderr on a background thread, waits
//!      (bounded) for the wire-contract marker
//!      `FAILPOINT_REACHED name=<name> pid=<pid>`.
//!   4. `child.kill()` — sends SIGKILL on Unix (per `std` docs).
//!   5. `child.wait()` + `ExitStatusExt::signal() == Some(9)`
//!      proves it was a real SIGKILL (not an internal panic /
//!      exit).
//!   6. Runs scenario-specific durable-invariant assertions.
//!
//! **Platform + config gate**: the whole file is
//! `#![cfg(all(unix, debug_assertions))]`. Windows target compiles
//! it as empty; release builds also elide it — and MUST, because the
//! `failpoints` mechanism itself is `#[cfg(debug_assertions)]`. A
//! child built with `--release` never reads `PHANTOM_FAILPOINT`, so
//! the marker never fires, the parent times out, and every test
//! goes RED. Gating the file the same way as the mechanism guarantees
//! `cargo test --release` skips these tests entirely (leaving the
//! rest of the release suite green) while `cargo test` (debug)
//! runs all 12.
//!
//! **No power-loss claim.** These tests prove ONLY the absence of
//! user-space teardown at each barrier — they do NOT emulate
//! kernel cache loss / power failure. That distinction is enforced
//! at the M5a-2 placement comments.

#![cfg(all(unix, debug_assertions))]

use std::io::{BufRead, BufReader};
use std::os::unix::process::ExitStatusExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use tempfile::TempDir;
use tokio::sync::broadcast;

use phantom_relay::atomic_write::{self, write_atomic};
use phantom_relay::body_hash::compute_body_hash_hex;
use phantom_relay::boot_loader::{
    boot, BootConfig, OwnershipExpectation, PreflightCaps,
};
use phantom_relay::capacity_ledger::CapacityCaps;
use phantom_relay::persistence::{record_path, PersistedRecord, RECORD_VERSION};
use phantom_relay::queue_meta::{self, Phase, QueueMeta, META_VERSION};
use phantom_relay::rest_workers::{
    spawn_worker_runtime, FatalReason, RestOp, SendCandidate, SendDisposition,
    WorkerRuntimeSpec,
};
use phantom_relay::seq_mac::SeqMacRootKey;
use phantom_relay::tombstone_config::TombstoneConfig;

// ─── constants ─────────────────────────────────────────────────────────

const TEST_MAC_KEY_BYTES: [u8; 32] = [0x77u8; 32];

/// Max wall-clock the parent gives a child to reach its armed
/// failpoint. Ten seconds is generous — cold-boot for the runtime
/// scenarios (bootstrap + fatal subscriber + runtime spawn +
/// try_send) fits comfortably inside 1s on a warm cargo cache;
/// this budget survives a cold `cargo test` invocation and a
/// Docker Desktop-linux slow-tier host.
const MARKER_WAIT: Duration = Duration::from_secs(10);

/// A 64-char lowercase-hex recipient the tests use uniformly.
/// The `worker_for` hash routing is stable per boot but not
/// deterministic across boots; the child does one op only so
/// per-shard routing doesn't need to be pinned.
const TEST_RECIPIENT: &str =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

// ─── helpers: state-dir + record shape ─────────────────────────────────

fn caps() -> CapacityCaps {
    CapacityCaps {
        max_envelopes: 100,
        max_bytes: 1_000_000,
        ram_budget: 1_000_000,
    }
}

/// Create a fresh state_dir with a valid `phase=Ready` queue-meta.
/// Returns the owned `TempDir` (dropped by the caller) plus the path.
fn build_state_dir() -> TempDir {
    let dir = TempDir::new().unwrap();
    std::fs::create_dir_all(dir.path().join("queue")).unwrap();
    let root_key = SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES);
    let meta = QueueMeta {
        version: META_VERSION,
        phase: Phase::Ready,
        boot_generation: 1,
        seq_mac_key_fingerprint: root_key.fingerprint(),
    };
    queue_meta::write_meta(dir.path(), &meta).unwrap();
    dir
}

fn boot_cfg(state_dir: &Path, tombstone_horizon_secs: u32) -> BootConfig {
    let root_key = SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES);
    BootConfig {
        state_dir: state_dir.to_path_buf(),
        caps: PreflightCaps::for_tests(),
        tombstone: TombstoneConfig::from_secs(tombstone_horizon_secs).unwrap(),
        current_seq_mac_key_fingerprint: root_key.fingerprint(),
        ownership: OwnershipExpectation::permissive_for_tests(),
    }
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

/// Pre-seed a `PersistedRecord::Queued` at the canonical path.
/// `expires_at` is caller-supplied so the seed can be "far future"
/// (Ack scenario) or "already expired" (sweep-Queued scenario).
fn seed_queued_on_disk(state_dir: &Path, id: &str, expires_at: u64) {
    let sealed_sender = format!("s-{id}");
    let payload = format!("p-{id}");
    let body_hash = compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
    let record = PersistedRecord::Queued {
        version: RECORD_VERSION,
        id: id.to_string(),
        sealed_sender,
        payload,
        sequence_ts: 1_720_000_000_000,
        seq: 42,
        expires_at,
        // boot_loader validates `seq_mac_key_fingerprint` at the
        // queue-meta level, not per-record — a placeholder MAC of
        // the correct 64-hex shape passes structural validation.
        // Same pattern as the pre-existing subprocess reboot-replay
        // tests inside `rest_workers::tests`.
        seq_mac: "a".repeat(64),
        body_hash,
    };
    write_record_bytes_direct(state_dir, id, &record);
}

/// Pre-seed a `PersistedRecord::AckedTombstone`. `dedup_until` is
/// caller-supplied — past-horizon for sweep-Tombstone, "far future"
/// for scenarios that need a persistent tombstone. `allow(dead_code)`
/// because the current M5a-3 matrix only pre-seeds via
/// [`seed_queued_on_disk`] for T12 boot-compaction; the tombstone
/// helper stays available for future scenario extensions.
#[allow(dead_code)]
fn seed_tombstone_on_disk(state_dir: &Path, id: &str, dedup_until: u64) {
    let sealed_sender = format!("s-{id}");
    let payload = format!("p-{id}");
    let body_hash = compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
    let record = PersistedRecord::AckedTombstone {
        version: RECORD_VERSION,
        id: id.to_string(),
        seq: 43,
        body_hash,
        acked_at: 1_720_000_000,
        dedup_until,
    };
    write_record_bytes_direct(state_dir, id, &record);
}

fn write_record_bytes_direct(state_dir: &Path, id: &str, record: &PersistedRecord) {
    let path = record_path(state_dir, TEST_RECIPIENT, id);
    atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
    let bytes = serde_json::to_vec(record).expect("record serialises");
    std::fs::write(&path, &bytes).unwrap();
}

/// Build valid serialized `PersistedRecord::Queued` bytes for the
/// given envelope id. Used by both parent (pre-seed under canonical
/// path) and child (write via `write_atomic`) so a post-SIGKILL
/// real `boot()` can parse either the OLD or NEW bytes cleanly.
///
/// Body content is deterministic per `(id, seq)` — parent + child
/// distinguish OLD vs NEW by seq alone (100 = OLD, 200 = NEW), and
/// `body_hash` is computed over the sealed_sender + payload text
/// derived from `(id, seq)` so byte-for-byte compare in T02/T04
/// detects a partial overwrite.
fn build_queued_record_bytes(id: &str, seq: u64, expires_at: u64) -> Vec<u8> {
    let sealed_sender = format!("s-{id}-seq{seq}");
    let payload = format!("p-{id}-seq{seq}");
    let body_hash = compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
    let record = PersistedRecord::Queued {
        version: RECORD_VERSION,
        id: id.to_string(),
        sealed_sender,
        payload,
        sequence_ts: 1_720_000_000_000,
        seq,
        expires_at,
        seq_mac: "a".repeat(64),
        body_hash,
    };
    serde_json::to_vec(&record).expect("record serialises")
}

/// Ensure the canonical `queue/<shard>/<recipient>/` path exists so
/// `write_atomic` can create its staging tempfile in the correct
/// production directory. Returns the full canonical record path
/// (which may or may not exist depending on scenario pre-seed).
fn canonical_record_path(state_dir: &Path, envelope_id: &str) -> PathBuf {
    let path = record_path(state_dir, TEST_RECIPIENT, envelope_id);
    atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
    path
}

// ─── helpers: subprocess spawn + SIGKILL-at-marker ─────────────────────

struct ChildOutcome {
    stderr_lines: Vec<String>,
    signal: Option<i32>,
    marker_line: Option<String>,
    child_pid: u32,
}

/// Spawn the dispatch child, arm the given failpoint, wait for the
/// wire-contract marker on stderr, then SIGKILL the child.
///
/// `env_extra` lets each scenario pass per-child parameters (target
/// path, state_dir, envelope id, …). The dispatch key
/// `PHANTOM_M5A3_SCENARIO` is added automatically.
///
/// Returns a `ChildOutcome` carrying the full stderr transcript,
/// the observed marker line (if any), the signal exit-code, and
/// the child's PID (parsed out of the marker for scenario-specific
/// cross-checks).
fn spawn_and_kill_at_marker(
    scenario: &'static str,
    failpoint_name: &'static str,
    env_extra: &[(&str, &str)],
) -> ChildOutcome {
    let exe = std::env::current_exe().expect("current_exe");
    let mut cmd = Command::new(&exe);
    cmd.args(["--exact", "m5a3_child_dispatch", "--nocapture"]);
    cmd.env("PHANTOM_M5A3_SCENARIO", scenario);
    cmd.env(
        "PHANTOM_FAILPOINT",
        format!("{failpoint_name}:block"),
    );
    for (k, v) in env_extra {
        cmd.env(k, v);
    }
    cmd.env("RUST_TEST_NOCAPTURE", "1");
    cmd.stdout(Stdio::piped());
    cmd.stderr(Stdio::piped());

    let mut child = cmd.spawn().expect("failed to spawn child test process");
    let child_pid = child.id();
    let stderr = child.stderr.take().expect("child stderr piped");

    // Round-1 REDLINE P2: exact marker equality. Compute the
    // expected marker string from the KNOWN child PID before we
    // start reading; treat any stderr line that trims to exactly
    // this string as the marker. `contains`-based matching accepted
    // prefix/suffix noise and a longer-name / longer-pid match.
    let expected_marker = format!("FAILPOINT_REACHED name={failpoint_name} pid={child_pid}");

    let (tx, rx) = mpsc::channel::<String>();
    let reader_handle = thread::spawn(move || {
        let reader = BufReader::new(stderr);
        for line in reader.lines() {
            match line {
                Ok(l) => {
                    let _ = tx.send(l);
                }
                Err(_) => return,
            }
        }
    });

    let mut collected: Vec<String> = Vec::new();
    let mut marker_line: Option<String> = None;
    let deadline = Instant::now() + MARKER_WAIT;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match rx.recv_timeout(remaining) {
            Ok(line) => {
                let is_marker = line.trim() == expected_marker;
                collected.push(line.clone());
                if is_marker {
                    marker_line = Some(line);
                    break;
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => break,
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }

    // Send SIGKILL regardless of marker outcome; if the marker was
    // missed the assertions below will flag it.
    let _ = child.kill();
    let status = child.wait().expect("child wait");
    let signal = status.signal();

    // Drain any remaining stderr lines (child may have flushed a
    // couple of extras before dying).
    let drain_deadline = Instant::now() + Duration::from_millis(200);
    while let Ok(line) = rx.recv_timeout(drain_deadline.saturating_duration_since(Instant::now()))
    {
        collected.push(line);
    }
    let _ = reader_handle.join();

    ChildOutcome {
        stderr_lines: collected,
        signal,
        marker_line,
        child_pid,
    }
}

/// Assert every M5a-3 wire contract on the child outcome:
///   * marker line was observed AND matches the exact string
///     `FAILPOINT_REACHED name=<expected_failpoint> pid=<child_pid>`
///     (round-1 REDLINE P2 — no `contains`-based fuzzy match);
///   * `ExitStatusExt::signal() == Some(9)` — real SIGKILL;
///   * `CHILD_REACHED_END` sentinel NOT present — barrier fired
///     before the child could complete normally.
fn assert_sigkill_at_marker(outcome: &ChildOutcome, expected_failpoint: &str) {
    let expected_marker = format!(
        "FAILPOINT_REACHED name={expected_failpoint} pid={}",
        outcome.child_pid
    );
    let marker = outcome.marker_line.as_ref().unwrap_or_else(|| {
        panic!(
            "child did not print the marker {expected_marker:?} within {MARKER_WAIT:?}; \
             stderr:\n{}",
            outcome.stderr_lines.join("\n")
        )
    });
    assert_eq!(
        marker.trim(),
        expected_marker,
        "marker line does not exactly equal wire contract {expected_marker:?}; \
         got {marker:?}; stderr:\n{}",
        outcome.stderr_lines.join("\n")
    );
    assert_eq!(
        outcome.signal,
        Some(9),
        "child was not SIGKILLed (expected signal=9); status.signal()={:?}; stderr:\n{}",
        outcome.signal,
        outcome.stderr_lines.join("\n")
    );
    assert!(
        !outcome
            .stderr_lines
            .iter()
            .any(|l| l.trim() == "CHILD_REACHED_END"),
        "child reached CHILD_REACHED_END sentinel — the barrier did not fire; \
         stderr:\n{}",
        outcome.stderr_lines.join("\n")
    );
}

// ─── dispatch: single child #[test], scenario dispatch by env ──────────

/// One `#[test]` that dispatches to per-scenario child bodies via
/// `PHANTOM_M5A3_SCENARIO`. When the env is unset (regular parent
/// test run), the function returns immediately. Every parent test
/// invokes this same child function with a distinct scenario key.
///
/// Using a single dispatch avoids twelve near-identical `#[test]`
/// declarations while keeping child bodies short and per-scenario
/// side-effect-explicit. The child's `Ok`-path prints
/// `CHILD_REACHED_END` — parents assert this line is NEVER present
/// in the stderr transcript (would prove the barrier failed to
/// fire).
#[test]
fn m5a3_child_dispatch() {
    let scenario = match std::env::var("PHANTOM_M5A3_SCENARIO") {
        Ok(v) => v,
        Err(_) => return,
    };
    match scenario.as_str() {
        // Atomic scenarios — call `write_atomic` DIRECTLY per
        // scope-lock (boot() writes queue-meta via write_atomic
        // and would trip the barrier BEFORE reaching the target).
        "atomic_after_write_absent" => child_atomic_write_direct(),
        "atomic_after_write_exists" => child_atomic_write_direct(),
        "atomic_after_fsync_absent" => child_atomic_write_direct(),
        "atomic_after_fsync_exists" => child_atomic_write_direct(),
        "atomic_after_rename" => child_atomic_write_direct(),

        // Runtime scenarios — real production boot + spawn +
        // try_send.
        "send_after_disk" => child_runtime_send(),
        "ack_after_tombstone_disk" => child_runtime_ack(),
        "sweep_after_unlink_queued" => child_runtime_sweep(),
        "sweep_after_unlink_tombstone" => child_runtime_sweep(),
        "sweep_after_parent_fsync_queued" => child_runtime_sweep(),
        "sweep_after_parent_fsync_tombstone" => child_runtime_sweep(),

        // Boot compaction — barrier fires inside from_boot.
        "boot_after_compaction_unlink" => child_boot_compaction(),

        other => panic!("m5a3_child_dispatch: unknown scenario {other:?}"),
    }
}

// ── child bodies ──

fn child_atomic_write_direct() {
    let target = std::env::var("PHANTOM_M5A3_TARGET_PATH").expect("target");
    let new_id = std::env::var("PHANTOM_M5A3_NEW_ID").expect("new_id");
    // Round-1 REDLINE P1: write REAL serialized PersistedRecord::Queued
    // bytes so a post-SIGKILL `boot()` on the state_dir either finds
    // nothing (pre-commit barriers) or parses the record cleanly
    // (post-rename barrier). Boot's replay validates that
    // `filename == sha256_hex(record.id) + ".json"`; parent + child
    // agree on `new_id` via env so the canonical filename matches.
    let expires_at = now_secs() + 3600;
    let bytes = build_queued_record_bytes(&new_id, /* seq */ 200, expires_at);
    let _ = write_atomic(&PathBuf::from(target), &bytes);
    eprintln!("CHILD_REACHED_END");
}

fn child_runtime_send() {
    let state_dir = std::env::var("PHANTOM_M5A3_STATE_DIR").expect("state_dir");
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    rt.block_on(async {
        let boot_result = boot(&boot_cfg(&PathBuf::from(&state_dir), 172_800))
            .expect("child boot OK");
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(64);
        let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES));
        let spec = WorkerRuntimeSpec::from_boot(boot_result, 8, key, caps(), fatal_tx)
            .expect("child from_boot OK");
        let runtime = spawn_worker_runtime(spec).expect("child spawn OK");
        let expires_at = now_secs() + 3600;
        let candidate = SendCandidate {
            id: "env-m5a3-send".into(),
            sealed_sender: "s-m5a3-send".into(),
            payload: "p-m5a3-send".into(),
            sequence_ts: 1_720_000_000_000,
            expires_at,
        };
        let (reply_tx, _reply_rx) = tokio::sync::oneshot::channel();
        runtime
            .try_send(RestOp::Send {
                recipient: TEST_RECIPIENT.into(),
                candidate,
                reply: reply_tx,
            })
            .expect("child try_send OK");
        // Block indefinitely waiting for the reply — the failpoint
        // parks inside do_send BEFORE the reply is sent, so the
        // parent SIGKILLs us instead of the reply arriving.
        let _ = tokio::time::sleep(Duration::from_secs(30)).await;
    });
    eprintln!("CHILD_REACHED_END");
}

fn child_runtime_ack() {
    let state_dir = std::env::var("PHANTOM_M5A3_STATE_DIR").expect("state_dir");
    let envelope_id = std::env::var("PHANTOM_M5A3_ENVELOPE_ID").expect("envelope_id");
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    rt.block_on(async {
        let boot_result = boot(&boot_cfg(&PathBuf::from(&state_dir), 172_800))
            .expect("child boot OK");
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(64);
        let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES));
        let spec = WorkerRuntimeSpec::from_boot(boot_result, 8, key, caps(), fatal_tx)
            .expect("child from_boot OK");
        let runtime = spawn_worker_runtime(spec).expect("child spawn OK");

        // Self-seed a Queued record in-runtime so do_ack has
        // something to transition. Pre-seeding on disk is not
        // an option: the Ack barrier is armed for THIS child,
        // and the barrier name (`ack.*`) is disjoint from Send,
        // so do_send completes normally.
        let expires_at = now_secs() + 3600;
        let candidate = SendCandidate {
            id: envelope_id.clone(),
            sealed_sender: format!("s-{envelope_id}"),
            payload: format!("p-{envelope_id}"),
            sequence_ts: 1_720_000_000_000,
            expires_at,
        };
        let (send_tx, send_rx) = tokio::sync::oneshot::channel();
        runtime
            .try_send(RestOp::Send {
                recipient: TEST_RECIPIENT.into(),
                candidate,
                reply: send_tx,
            })
            .expect("child try_send(Send) OK");
        let _ = tokio::time::timeout(Duration::from_secs(5), send_rx)
            .await
            .expect("send reply within 5s")
            .expect("send reply channel not dropped")
            .expect("send must succeed");

        // Now fire Ack — do_ack transitions Queued→AckedTombstone
        // via write_atomic, then hits the ack.*_ram_commit
        // barrier and parks. Parent will SIGKILL momentarily.
        let (ack_tx, _ack_rx) = tokio::sync::oneshot::channel();
        runtime
            .try_send(RestOp::Ack {
                recipient: TEST_RECIPIENT.into(),
                envelope_id,
                reply: ack_tx,
            })
            .expect("child try_send(Ack) OK");
        let _ = tokio::time::sleep(Duration::from_secs(30)).await;
    });
    eprintln!("CHILD_REACHED_END");
}

fn child_runtime_sweep() {
    // Scenario carries the shape: whether we sweep a Queued or a
    // Tombstone. Barrier name is set separately via
    // PHANTOM_FAILPOINT; both sweep names share the same child
    // flow — only the pre-Sweep setup differs.
    let state_dir = std::env::var("PHANTOM_M5A3_STATE_DIR").expect("state_dir");
    let scenario = std::env::var("PHANTOM_M5A3_SCENARIO").expect("scenario");
    let want_tombstone = scenario.contains("tombstone");

    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    rt.block_on(async {
        // Short tombstone horizon so a self-seeded Ack ages past
        // its dedup window inside the child's own wall-clock window.
        let boot_result =
            boot(&boot_cfg(&PathBuf::from(&state_dir), 1)).expect("child boot OK");
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(64);
        let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES));
        let spec = WorkerRuntimeSpec::from_boot(boot_result, 8, key, caps(), fatal_tx)
            .expect("child from_boot OK");
        let runtime = spawn_worker_runtime(spec).expect("child spawn OK");

        // Step 1: Send a record. Short TTL so it expires quickly
        // for the Queued-sweep variant; the same short TTL is
        // fine for the Tombstone-sweep variant because the
        // record will be Acked before its TTL matters.
        let envelope_id = format!("env-m5a3-sweep-{scenario}");
        let expires_at = now_secs() + 1;
        let candidate = SendCandidate {
            id: envelope_id.clone(),
            sealed_sender: format!("s-{envelope_id}"),
            payload: format!("p-{envelope_id}"),
            sequence_ts: 1_720_000_000_000,
            expires_at,
        };
        let (send_tx, send_rx) = tokio::sync::oneshot::channel();
        runtime
            .try_send(RestOp::Send {
                recipient: TEST_RECIPIENT.into(),
                candidate,
                reply: send_tx,
            })
            .expect("child try_send(Send) OK");
        let _ = tokio::time::timeout(Duration::from_secs(5), send_rx)
            .await
            .expect("send reply")
            .expect("send reply channel not dropped")
            .expect("send must succeed");

        // Step 2 (Tombstone variant only): Ack the record so
        // do_sweep::ExpiredTombstone branch is what fires the
        // barrier. Tombstone gets `dedup_until = ack_now + 1`.
        if want_tombstone {
            let (ack_tx, ack_rx) = tokio::sync::oneshot::channel();
            runtime
                .try_send(RestOp::Ack {
                    recipient: TEST_RECIPIENT.into(),
                    envelope_id: envelope_id.clone(),
                    reply: ack_tx,
                })
                .expect("child try_send(Ack) OK");
            let _ = tokio::time::timeout(Duration::from_secs(5), ack_rx)
                .await
                .expect("ack reply")
                .expect("ack reply channel not dropped")
                .expect("ack must succeed");
        }

        // Step 3: wait for the record to age past its
        // `expires_at` (Queued variant) or `dedup_until`
        // (Tombstone variant). Real wall-clock sleep; horizon
        // was 1s so 2s gives comfortable slack.
        tokio::time::sleep(Duration::from_secs(2)).await;

        // Step 4: fire Sweep — the appropriate branch of
        // sweep_one enters, hits the armed barrier, parks. The
        // parent will SIGKILL momentarily.
        let (sweep_tx, _sweep_rx) = tokio::sync::oneshot::channel();
        runtime
            .try_send(RestOp::Sweep {
                recipient: TEST_RECIPIENT.into(),
                reply: sweep_tx,
            })
            .expect("child try_send(Sweep) OK");
        let _ = tokio::time::sleep(Duration::from_secs(30)).await;
    });
    eprintln!("CHILD_REACHED_END");
}

fn child_boot_compaction() {
    let state_dir = std::env::var("PHANTOM_M5A3_STATE_DIR").expect("state_dir");
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    rt.block_on(async {
        let boot_result =
            boot(&boot_cfg(&PathBuf::from(&state_dir), 1)).expect("child boot OK");
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(64);
        let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES));
        // The barrier fires inside `from_boot`'s
        // `boot_compact_expired` — before we even reach spawn.
        let _spec = WorkerRuntimeSpec::from_boot(boot_result, 8, key, caps(), fatal_tx)
            .expect("child from_boot OK");
    });
    eprintln!("CHILD_REACHED_END");
}

// ─── T01 · atomic.after_write_before_file_fsync — target ABSENT ────────

#[test]
fn t01_atomic_after_write_before_file_fsync_target_absent() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    // Round-1 REDLINE P1: canonical state_dir layout so a real
    // `boot()` call after SIGKILL exercises production
    // `sweep_staging_tempfiles` (not a local reimplementation).
    let dir = build_state_dir();
    let envelope_id = "env-m5a3-t01";
    let target = canonical_record_path(dir.path(), envelope_id);
    assert!(!target.exists(), "T01 pre-check: canonical path must be absent");

    let outcome = spawn_and_kill_at_marker(
        "atomic_after_write_absent",
        "atomic.after_write_before_file_fsync",
        &[
            ("PHANTOM_M5A3_TARGET_PATH", target.to_str().unwrap()),
            ("PHANTOM_M5A3_NEW_ID", envelope_id),
        ],
    );
    assert_sigkill_at_marker(&outcome, "atomic.after_write_before_file_fsync");
    assert!(
        !target.exists(),
        "canonical path must remain ABSENT post-SIGKILL for the pre-fsync barrier"
    );

    // Real production `boot()` — runs the production
    // `sweep_staging_tempfiles` inside boot's preflight, so any
    // regression in that sweep would surface here.
    let boot_result = boot(&boot_cfg(dir.path(), 172_800)).expect("post-SIGKILL boot OK");
    assert_eq!(
        boot_result.records().len(),
        0,
        "canonical record MUST NOT surface after boot replay — write never committed"
    );
    assert!(
        !target.exists(),
        "canonical path still absent after boot's staging sweep"
    );
}

// ─── T02 · atomic.after_write_before_file_fsync — target EXISTS ────────

#[test]
fn t02_atomic_after_write_before_file_fsync_target_exists_preserves_old_bytes() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    let envelope_id = "env-m5a3-t02";
    let target = canonical_record_path(dir.path(), envelope_id);
    let old_bytes = build_queued_record_bytes(envelope_id, /* seq */ 100, now_secs() + 3600);
    std::fs::write(&target, &old_bytes).unwrap();

    let outcome = spawn_and_kill_at_marker(
        "atomic_after_write_exists",
        "atomic.after_write_before_file_fsync",
        &[
            ("PHANTOM_M5A3_TARGET_PATH", target.to_str().unwrap()),
            ("PHANTOM_M5A3_NEW_ID", envelope_id),
        ],
    );
    assert_sigkill_at_marker(&outcome, "atomic.after_write_before_file_fsync");
    let on_disk = std::fs::read(&target).expect("canonical file survives SIGKILL");
    assert_eq!(
        on_disk, old_bytes,
        "PRE-EXISTING canonical bytes must remain BYTE-IDENTICAL post-SIGKILL \
         at the pre-fsync barrier"
    );

    // Real boot() — parses OLD record cleanly AND sweeps the
    // abandoned staging tempfile in the same pass.
    let boot_result = boot(&boot_cfg(dir.path(), 172_800)).expect("post-SIGKILL boot OK");
    let recs = boot_result.records();
    assert_eq!(recs.len(), 1);
    assert_eq!(recs[0].record.seq(), 100, "boot recovered the OLD record");
    assert_eq!(recs[0].record.id(), envelope_id);
    // Byte-identical guarantee holds AFTER boot's staging sweep too.
    let after_boot = std::fs::read(&target).unwrap();
    assert_eq!(after_boot, old_bytes);
}

// ─── T03 · atomic.after_file_fsync_before_rename — target ABSENT ───────

#[test]
fn t03_atomic_after_file_fsync_before_rename_target_absent() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    let envelope_id = "env-m5a3-t03";
    let target = canonical_record_path(dir.path(), envelope_id);

    let outcome = spawn_and_kill_at_marker(
        "atomic_after_fsync_absent",
        "atomic.after_file_fsync_before_rename",
        &[
            ("PHANTOM_M5A3_TARGET_PATH", target.to_str().unwrap()),
            ("PHANTOM_M5A3_NEW_ID", envelope_id),
        ],
    );
    assert_sigkill_at_marker(&outcome, "atomic.after_file_fsync_before_rename");
    assert!(
        !target.exists(),
        "canonical path must remain ABSENT — the rename has not committed"
    );

    let boot_result = boot(&boot_cfg(dir.path(), 172_800)).expect("post-SIGKILL boot OK");
    assert_eq!(boot_result.records().len(), 0);
    assert!(!target.exists(), "canonical still absent after boot's staging sweep");
}

// ─── T04 · atomic.after_file_fsync_before_rename — target EXISTS ───────

#[test]
fn t04_atomic_after_file_fsync_before_rename_target_exists_preserves_old_bytes() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    let envelope_id = "env-m5a3-t04";
    let target = canonical_record_path(dir.path(), envelope_id);
    let old_bytes = build_queued_record_bytes(envelope_id, /* seq */ 100, now_secs() + 3600);
    std::fs::write(&target, &old_bytes).unwrap();

    let outcome = spawn_and_kill_at_marker(
        "atomic_after_fsync_exists",
        "atomic.after_file_fsync_before_rename",
        &[
            ("PHANTOM_M5A3_TARGET_PATH", target.to_str().unwrap()),
            ("PHANTOM_M5A3_NEW_ID", envelope_id),
        ],
    );
    assert_sigkill_at_marker(&outcome, "atomic.after_file_fsync_before_rename");
    let on_disk = std::fs::read(&target).unwrap();
    assert_eq!(
        on_disk, old_bytes,
        "OLD canonical bytes must remain BYTE-IDENTICAL — rename has not committed"
    );

    let boot_result = boot(&boot_cfg(dir.path(), 172_800)).expect("post-SIGKILL boot OK");
    let recs = boot_result.records();
    assert_eq!(recs.len(), 1);
    assert_eq!(recs[0].record.seq(), 100);
    assert_eq!(recs[0].record.id(), envelope_id);
    let after_boot = std::fs::read(&target).unwrap();
    assert_eq!(after_boot, old_bytes);
}

// ─── T05 · atomic.after_rename_before_parent_fsync ────────────────────

#[test]
fn t05_atomic_after_rename_before_parent_fsync_new_bytes_parse_cleanly() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    let envelope_id = "env-m5a3-t05";
    let target = canonical_record_path(dir.path(), envelope_id);
    // Pre-seed OLD bytes so a false-positive guard fires if the
    // child somehow skipped the write: we'd see OLD seq=100 in boot
    // recovery and fail the "seq=200 NEW" assertion below.
    let old_bytes = build_queued_record_bytes(envelope_id, /* seq */ 100, now_secs() + 3600);
    std::fs::write(&target, &old_bytes).unwrap();

    let outcome = spawn_and_kill_at_marker(
        "atomic_after_rename",
        "atomic.after_rename_before_parent_fsync",
        &[
            ("PHANTOM_M5A3_TARGET_PATH", target.to_str().unwrap()),
            ("PHANTOM_M5A3_NEW_ID", envelope_id),
        ],
    );
    assert_sigkill_at_marker(&outcome, "atomic.after_rename_before_parent_fsync");
    assert!(target.exists(), "target present — rename committed");

    // Real production `boot()` — parses NEW bytes cleanly. If the
    // rename half-committed / bytes are torn, replay would produce
    // `BootError::ParsePanic` (exit 10) and boot() would Err here.
    let boot_result = boot(&boot_cfg(dir.path(), 172_800))
        .expect("post-SIGKILL boot parses NEW bytes cleanly");
    let recs = boot_result.records();
    assert_eq!(recs.len(), 1, "exactly one Queued record recovered");
    assert_eq!(
        recs[0].record.seq(),
        200,
        "boot recovered NEW seq — rename committed before SIGKILL"
    );
    assert_eq!(recs[0].record.id(), envelope_id);
}

// ─── T06 · send.after_disk_commit_before_ram_commit ────────────────────

#[test]
fn t06_send_after_disk_commit_recovered_as_queued_on_reboot() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    let outcome = spawn_and_kill_at_marker(
        "send_after_disk",
        "send.after_disk_commit_before_ram_commit",
        &[("PHANTOM_M5A3_STATE_DIR", dir.path().to_str().unwrap())],
    );
    assert_sigkill_at_marker(&outcome, "send.after_disk_commit_before_ram_commit");
    // Reboot replay via a fresh boot() on the same state_dir.
    let boot_result = boot(&boot_cfg(dir.path(), 172_800))
        .expect("post-SIGKILL boot recovers cleanly");
    let records = boot_result.records();
    let queued: Vec<_> = records
        .iter()
        .filter(|r| {
            matches!(
                &r.record,
                PersistedRecord::Queued { id, .. } if id == "env-m5a3-send"
            )
        })
        .collect();
    assert_eq!(
        queued.len(),
        1,
        "post-SIGKILL boot must recover exactly one Queued record for env-m5a3-send"
    );
}

// ─── T07 · ack.after_tombstone_disk_commit_before_ledger_and_ram_commit ─

#[tokio::test]
async fn t07_ack_after_tombstone_disk_commit_closes_tombstone_replay_contract() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    // Child self-seeds via in-runtime Send + wait for reply, then
    // Ack. Pre-seed on disk would be at risk of
    // boot_compact_expired timing.
    let envelope_id = "env-m5a3-ack";
    let outcome = spawn_and_kill_at_marker(
        "ack_after_tombstone_disk",
        "ack.after_tombstone_disk_commit_before_ledger_and_ram_commit",
        &[
            ("PHANTOM_M5A3_STATE_DIR", dir.path().to_str().unwrap()),
            ("PHANTOM_M5A3_ENVELOPE_ID", envelope_id),
        ],
    );
    assert_sigkill_at_marker(
        &outcome,
        "ack.after_tombstone_disk_commit_before_ledger_and_ram_commit",
    );

    // Reboot replay — canonical path holds AckedTombstone.
    let boot_result = boot(&boot_cfg(dir.path(), 172_800))
        .expect("post-SIGKILL boot recovers cleanly");
    let tombstoned: Vec<_> = boot_result
        .records()
        .iter()
        .filter(|r| {
            matches!(
                &r.record,
                PersistedRecord::AckedTombstone { id, .. } if id == envelope_id
            )
        })
        .collect();
    assert_eq!(tombstoned.len(), 1, "AckedTombstone recovered after reboot");
    let queued_survivor: Vec<_> = boot_result
        .records()
        .iter()
        .filter(|r| {
            matches!(
                &r.record,
                PersistedRecord::Queued { id, .. } if id == envelope_id
            )
        })
        .collect();
    assert!(
        queued_survivor.is_empty(),
        "Queued form must NOT surface post-SIGKILL (durable-commit boundary)"
    );

    // Round-1 REDLINE P1-F3: close the locked TombstoneReplay
    // contract via full runtime spawn + Send(same id, same body).
    // Records()-shape alone doesn't prove the boot → runtime path
    // seeds the tombstone dedup table correctly.
    let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(64);
    let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES));
    let spec = WorkerRuntimeSpec::from_boot(boot_result, 8, key, caps(), fatal_tx)
        .expect("from_boot OK post-SIGKILL");
    let runtime = spawn_worker_runtime(spec).expect("spawn OK");
    // Send with the SAME id and SAME (sealed_sender, payload) as the
    // child's in-runtime seed — the child used the constant shape
    // `s-{id}` / `p-{id}` (see `child_runtime_ack`), so
    // `compute_body_hash_hex` here matches the pre-SIGKILL body.
    let candidate = SendCandidate {
        id: envelope_id.to_string(),
        sealed_sender: format!("s-{envelope_id}"),
        payload: format!("p-{envelope_id}"),
        sequence_ts: 1_720_000_000_000,
        expires_at: now_secs() + 3600,
    };
    let (reply_tx, reply_rx) = tokio::sync::oneshot::channel();
    runtime
        .try_send(RestOp::Send {
            recipient: TEST_RECIPIENT.into(),
            candidate,
            reply: reply_tx,
        })
        .expect("post-reboot try_send OK");
    let send_outcome = tokio::time::timeout(Duration::from_secs(5), reply_rx)
        .await
        .expect("send reply within 5s")
        .expect("send reply channel not dropped")
        .expect("send must succeed");
    assert!(
        matches!(send_outcome.disposition, SendDisposition::TombstoneReplay),
        "post-SIGKILL reboot must seed tombstone_dedup so a same-id/same-body \
         Send closes the TombstoneReplay contract; got disposition {:?}",
        send_outcome.disposition
    );
    runtime.close();
    let _ = runtime.drain_handles(Duration::from_secs(5)).await;
}

// ─── T08 · sweep.after_unlink_before_parent_fsync — Queued branch ──────

#[test]
fn t08_sweep_after_unlink_queued_record_absent_on_reboot() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    // Child self-seeds via Send with a 1s TTL and sleeps 2s
    // before firing Sweep. Pre-seeding on disk is not viable:
    // the fresh child boot would compact any expired-Queued record
    // via `boot_compact_expired` BEFORE reaching the runtime, so
    // the sweep barrier would never fire. The scenario key
    // (`sweep_after_unlink_queued`) determines that the child
    // does Send + sleep + Sweep with NO intervening Ack — so the
    // Sweep enters the `ExpiredQueued` branch.
    let envelope_id = "env-m5a3-sweep-sweep_after_unlink_queued";
    let outcome = spawn_and_kill_at_marker(
        "sweep_after_unlink_queued",
        "sweep.after_unlink_before_parent_fsync",
        &[("PHANTOM_M5A3_STATE_DIR", dir.path().to_str().unwrap())],
    );
    assert_sigkill_at_marker(&outcome, "sweep.after_unlink_before_parent_fsync");
    // Post-SIGKILL: file must be absent (unlink already ran) —
    // ledger seeds from disk truth on next boot.
    let path = record_path(dir.path(), TEST_RECIPIENT, envelope_id);
    assert!(
        !path.exists(),
        "record file must be absent post-SIGKILL after `remove_record_file` Ok"
    );
    let boot_result = boot(&boot_cfg(dir.path(), 172_800))
        .expect("post-SIGKILL boot recovers cleanly");
    let records = boot_result.records();
    assert!(
        !records
            .iter()
            .any(|r| matches!(&r.record, PersistedRecord::Queued { id, .. } if id == envelope_id)),
        "no Queued surfaces post-SIGKILL — record was durably unlinked"
    );
}

// ─── T09 · sweep.after_unlink_before_parent_fsync — Tombstone branch ───

#[test]
fn t09_sweep_after_unlink_tombstone_record_absent_on_reboot() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    // Child self-seeds via Send + Ack + sleep before firing
    // Sweep. Ack transitions Queued→AckedTombstone with
    // `dedup_until = ack_now + 1` (child's boot_cfg horizon).
    // 2s sleep ages the tombstone past its horizon; Sweep enters
    // the `ExpiredTombstone` branch and hits the armed barrier.
    let envelope_id = "env-m5a3-sweep-sweep_after_unlink_tombstone";
    let outcome = spawn_and_kill_at_marker(
        "sweep_after_unlink_tombstone",
        "sweep.after_unlink_before_parent_fsync",
        &[("PHANTOM_M5A3_STATE_DIR", dir.path().to_str().unwrap())],
    );
    assert_sigkill_at_marker(&outcome, "sweep.after_unlink_before_parent_fsync");
    let path = record_path(dir.path(), TEST_RECIPIENT, envelope_id);
    assert!(!path.exists(), "tombstone file must be absent post-SIGKILL");
    let boot_result = boot(&boot_cfg(dir.path(), 172_800))
        .expect("post-SIGKILL boot recovers cleanly");
    let records = boot_result.records();
    assert!(
        !records
            .iter()
            .any(|r| matches!(&r.record, PersistedRecord::AckedTombstone { id, .. } if id == envelope_id)),
        "no AckedTombstone surfaces post-SIGKILL"
    );
}

// ─── T10 · sweep.after_parent_fsync_before_ledger_and_ram_release — Queued

#[test]
fn t10_sweep_after_parent_fsync_queued_record_absent_on_reboot() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    // Child self-seeds via Send + sleep + Sweep. Different sweep
    // barrier from T08 but same seeding shape.
    let envelope_id = "env-m5a3-sweep-sweep_after_parent_fsync_queued";
    let outcome = spawn_and_kill_at_marker(
        "sweep_after_parent_fsync_queued",
        "sweep.after_parent_fsync_before_ledger_and_ram_release",
        &[("PHANTOM_M5A3_STATE_DIR", dir.path().to_str().unwrap())],
    );
    assert_sigkill_at_marker(
        &outcome,
        "sweep.after_parent_fsync_before_ledger_and_ram_release",
    );
    let path = record_path(dir.path(), TEST_RECIPIENT, envelope_id);
    assert!(!path.exists(), "record file absent post-SIGKILL");
    let boot_result = boot(&boot_cfg(dir.path(), 172_800))
        .expect("post-SIGKILL boot recovers cleanly");
    let records = boot_result.records();
    assert!(
        !records
            .iter()
            .any(|r| matches!(&r.record, PersistedRecord::Queued { id, .. } if id == envelope_id))
    );
}

// ─── T11 · sweep.after_parent_fsync_... — Tombstone branch ────────────

#[test]
fn t11_sweep_after_parent_fsync_tombstone_record_absent_on_reboot() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    // Child self-seeds via Send + Ack + sleep + Sweep — different
    // barrier from T09 but same Tombstone-branch seeding shape.
    let envelope_id = "env-m5a3-sweep-sweep_after_parent_fsync_tombstone";
    let outcome = spawn_and_kill_at_marker(
        "sweep_after_parent_fsync_tombstone",
        "sweep.after_parent_fsync_before_ledger_and_ram_release",
        &[("PHANTOM_M5A3_STATE_DIR", dir.path().to_str().unwrap())],
    );
    assert_sigkill_at_marker(
        &outcome,
        "sweep.after_parent_fsync_before_ledger_and_ram_release",
    );
    let path = record_path(dir.path(), TEST_RECIPIENT, envelope_id);
    assert!(!path.exists());
    let boot_result = boot(&boot_cfg(dir.path(), 172_800))
        .expect("post-SIGKILL boot recovers cleanly");
    let records = boot_result.records();
    assert!(
        !records
            .iter()
            .any(|r| matches!(&r.record, PersistedRecord::AckedTombstone { id, .. } if id == envelope_id))
    );
}

// ─── T12 · boot.after_compaction_unlink_before_parent_fsync ────────────

#[tokio::test]
async fn t12_boot_after_compaction_unlink_full_runtime_indices_empty() {
    if std::env::var("PHANTOM_M5A3_SCENARIO").is_ok() {
        return;
    }
    let dir = build_state_dir();
    let envelope_id = "env-m5a3-boot-comp";
    // Pre-seed an expired Queued record. The child's `from_boot`
    // calls `boot_compact_expired` — barrier fires between
    // `remove_file` and `fsync_dir` on that path.
    let past_expiry = now_secs().saturating_sub(3600);
    seed_queued_on_disk(dir.path(), envelope_id, past_expiry);

    let outcome = spawn_and_kill_at_marker(
        "boot_after_compaction_unlink",
        "boot.after_compaction_unlink_before_parent_fsync",
        &[("PHANTOM_M5A3_STATE_DIR", dir.path().to_str().unwrap())],
    );
    assert_sigkill_at_marker(
        &outcome,
        "boot.after_compaction_unlink_before_parent_fsync",
    );

    // Round-1 REDLINE P1-F4: `boot_compact_expired` is called INSIDE
    // `WorkerRuntimeSpec::from_boot`, not inside `boot()`. Running
    // only `boot()` here would prove the walker's behaviour but
    // NOT the compaction idempotence claim. Do the full boot →
    // from_boot → spawn cycle and assert runtime indices are empty.
    let boot_result = boot(&boot_cfg(dir.path(), 172_800))
        .expect("second boot's walker succeeds");
    // A well-formed second-boot walker MAY still see the record if
    // the fsync did not durably remove it before SIGKILL — either
    // way, from_boot's `boot_compact_expired` must sweep it under
    // the same `expires_at <= now` predicate.
    let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(64);
    let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES));
    let spec = WorkerRuntimeSpec::from_boot(boot_result, 8, key, caps(), fatal_tx)
        .expect("second boot's from_boot compacts idempotently");
    let runtime = spawn_worker_runtime(spec).expect("spawn OK");
    assert_eq!(
        runtime.active_entry_count(),
        0,
        "runtime active_index MUST be empty — expired record either was durably \
         unlinked before SIGKILL or was re-compacted by from_boot's second pass"
    );
    assert_eq!(
        runtime.tombstone_dedup_count(),
        0,
        "runtime tombstone_dedup MUST be empty — no tombstone was ever seeded"
    );
    runtime.close();
    let _ = runtime.drain_handles(Duration::from_secs(5)).await;
}
