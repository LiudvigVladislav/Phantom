// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M5a-4 — concurrency + deadlock stress.
//!
//! **Architect scope-lock (2026-07-30) + round-1 REDLINE (2026-07-30):**
//!   - 32 producers × 500 operations each; overall wall-clock cap 60 s.
//!   - Deterministic per-task RNG seed (test-repeatable choices; tokio
//!     scheduling remains non-deterministic — that is what we stress).
//!   - Unique `(recipient, envelope_id)` pairs plus a modelled
//!     collision-group (same id + same body from multiple tasks so
//!     `SendDisposition::QueuedReplay` fires) plus a modelled
//!     tombstone-replay set (pre-tombstoned before the producers so
//!     `SendDisposition::TombstoneReplay` fires deterministically).
//!   - Recipient pool crafted to span multiple shards; the actual
//!     shard-spread invariant is asserted after a small warmup.
//!   - Expiry / dedup horizons huge so nothing expires mid-test.
//!   - The 60-second budget covers every phase: warmup, producer ops,
//!     runtime + disk audit, ordered shutdown / drain, and the final
//!     fresh-boot cross-check. A **process-wide OS-thread watchdog**
//!     with `std::process::abort()` fires at exactly 60 s of wall
//!     clock, so a hang anywhere — including inside blocking `boot()`
//!     / `read_dir()` — is caught even when the tokio runtime is
//!     wedged.
//!   - Before the fresh boot the runtime is closed, drained, dropped.
//!   - Runtime ↔ disk audit: queued / tombstone counts AND bytes,
//!     RAM estimate as a lower bound, active_index membership,
//!     durable record set, no `.staging-*` residue.
//!   - Fresh boot compares `(variant, recipient, id, seq, body_hash)`
//!     tuples — set equality, not just id-set equality.
//!
//! **Round-1 REDLINE amendments:**
//!   - P0: `producer_task` now returns a structured `OpStats` and
//!     fail-loud panics on every unexpected outcome (per-op timeout,
//!     dropped oneshot, unexpected typed error variant). Success is
//!     no longer "we counted the try_send call"; success is
//!     "reply arrived AND matched an expected classification".
//!   - P1-F2: `sent_unique` stores `(recipient, id)` pairs (not just
//!     ids). Ack fires against the ORIGINAL recipient — otherwise
//!     11/12 acks land on the wrong shard and return `NotFound`
//!     silently.
//!   - P1-F3: a deterministic `TOMBSTONE_REPLAY_IDS` set is
//!     tombstoned before the producers start; producers send against
//!     the same `(recipient, id, body)` so `TombstoneReplay` is
//!     guaranteed to fire. Post-run asserts both
//!     `send_queued_replay > 0` AND `send_tombstone_replay > 0`.
//!   - P1-F4: OS-thread watchdog with `std::process::abort()`
//!     replaces the async-only `tokio::time::timeout` guard around
//!     the producer join. The watchdog covers ALL phases —
//!     shutdown, fresh boot, disk walk — none of which are async
//!     futures the tokio timer could interrupt.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};
use tempfile::TempDir;
use tokio::sync::broadcast;
use tokio::task::JoinSet;

use phantom_relay::atomic_write;
use phantom_relay::boot_loader::{
    boot, BootConfig, OwnershipExpectation, PreflightCaps,
};
use phantom_relay::capacity_ledger::CapacityCaps;
use phantom_relay::persistence::PersistedRecord;
use phantom_relay::queue_meta::{self, Phase, QueueMeta, META_VERSION};
use phantom_relay::rest_workers::{
    spawn_worker_runtime, AckOutcome, FatalReason, RestOp, RuntimeSendError,
    SendCandidate, SendDisposition, SendError, WorkerRuntime, WorkerRuntimeSpec,
};
use phantom_relay::seq_mac::SeqMacRootKey;
use phantom_relay::tombstone_config::{TombstoneConfig, MAX_HORIZON_SECS};
use phantom_relay::worker_pool::PoolStateKind;

// ── locked constants ──────────────────────────────────────────────────

const N_PRODUCERS: usize = 32;
const M_OPS: usize = 500;
const WALL_CLOCK_CAP_SECS: u64 = 60;
const WALL_CLOCK_CAP: Duration = Duration::from_secs(WALL_CLOCK_CAP_SECS);
const MIN_SHARDS_COVERED: usize = 5;

/// Per-op wait for `try_send`'s reply. Higher than the M5a-3 tests'
/// 5 s because 16 000 concurrent ops share 64 shard-workers and
/// occasional queueing at the mpsc buffer is expected.
const OP_REPLY_TIMEOUT: Duration = Duration::from_secs(10);

/// TTL well beyond the 60-second wall-clock cap so no record expires
/// during the run; Sweep operations become well-typed no-ops and
/// still exercise the shard-worker actor loop.
const FAR_FUTURE_TTL_SECS: u64 = 365 * 24 * 3600; // 1 year

const RECIPIENT_PREFIX_BYTES: [u8; 12] = [
    0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc,
];

/// Fixed collision group. Any producer may Send with one of these
/// ids using the same `(sealed_sender, payload)` from
/// [`collision_body`]; collision ids are NEVER acked, so subsequent
/// same-id/same-body Sends produce
/// [`SendDisposition::QueuedReplay`].
const COLLISION_IDS: [&str; 8] = [
    "coll-e01", "coll-e02", "coll-e03", "coll-e04",
    "coll-e05", "coll-e06", "coll-e07", "coll-e08",
];

/// Pre-tombstoned scenario (round-1 REDLINE P1-F3). The test's own
/// task Sends+Acks each id under [`TOMBSTONE_REPLAY_RECIPIENT_INDEX`]
/// BEFORE spawning the producers. Producers then Send with the same
/// (recipient, id, body) tuple — the runtime returns
/// [`SendDisposition::TombstoneReplay`] deterministically.
const TOMBSTONE_REPLAY_IDS: [&str; 4] = ["tr-e01", "tr-e02", "tr-e03", "tr-e04"];
const TOMBSTONE_REPLAY_RECIPIENT_INDEX: usize = 0;

/// Producer ops split: 60 % Send / 30 % Ack / 10 % Sweep.
const SEND_THRESHOLD: u32 = 60;
const ACK_THRESHOLD: u32 = 90;

/// Fraction of Send ops that pull from the collision group
/// (QueuedReplay) resp. the tombstone-replay set (TombstoneReplay).
const COLLISION_SEND_PROB_PCT: u32 = 5;
const TOMBSTONE_REPLAY_SEND_PROB_PCT: u32 = 3;

const TEST_MAC_KEY_BYTES: [u8; 32] = [0x99u8; 32];

// ── helpers ───────────────────────────────────────────────────────────

fn caps() -> CapacityCaps {
    CapacityCaps {
        max_envelopes: 100_000,
        max_bytes: 100 * 1024 * 1024,
        ram_budget: 100 * 1024 * 1024,
    }
}

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

fn boot_cfg(state_dir: &Path) -> BootConfig {
    let root_key = SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES);
    BootConfig {
        state_dir: state_dir.to_path_buf(),
        caps: PreflightCaps::for_tests(),
        tombstone: TombstoneConfig::from_secs(MAX_HORIZON_SECS).unwrap(),
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

fn build_recipient_pool() -> Vec<String> {
    RECIPIENT_PREFIX_BYTES
        .iter()
        .map(|b| {
            let mut s = String::with_capacity(64);
            for _ in 0..32 {
                s.push_str(&format!("{b:02x}"));
            }
            s
        })
        .collect()
}

fn collision_body(id: &str) -> (String, String) {
    (format!("s-coll-{id}"), format!("p-coll-{id}"))
}

fn tombstone_replay_body(id: &str) -> (String, String) {
    (format!("s-tr-{id}"), format!("p-tr-{id}"))
}

fn unique_body(task_id: usize, op_index: usize) -> (String, String) {
    (
        format!("s-t{task_id:02}-op{op_index:04}"),
        format!("p-t{task_id:02}-op{op_index:04}"),
    )
}

fn unique_envelope_id(task_id: usize, op_index: usize) -> String {
    format!("t{task_id:02}-op{op_index:04}")
}

fn build_send_candidate(id: &str, sealed_sender: &str, payload: &str, expires_at: u64) -> SendCandidate {
    SendCandidate {
        id: id.to_string(),
        sealed_sender: sealed_sender.to_string(),
        payload: payload.to_string(),
        sequence_ts: 1_720_000_000_000,
        expires_at,
    }
}

/// Fingerprint for the fresh-boot compare — architect scope-lock
/// forbids id-set-only equality.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
struct RecordFingerprint {
    variant: &'static str,
    recipient: String,
    id: String,
    seq: u64,
    body_hash: String,
}

fn fingerprint_of(recipient: &str, rec: &PersistedRecord) -> RecordFingerprint {
    let (variant, seq, body_hash) = match rec {
        PersistedRecord::Queued { seq, body_hash, .. } => ("queued", *seq, body_hash.clone()),
        PersistedRecord::AckedTombstone { seq, body_hash, .. } => {
            ("tombstone", *seq, body_hash.clone())
        }
    };
    RecordFingerprint {
        variant,
        recipient: recipient.to_string(),
        id: rec.id().to_string(),
        seq,
        body_hash,
    }
}

fn walk_state_dir(
    state_dir: &Path,
) -> (Vec<(String, PathBuf, PersistedRecord, u64)>, Vec<PathBuf>) {
    let mut records: Vec<(String, PathBuf, PersistedRecord, u64)> = Vec::new();
    let mut staging: Vec<PathBuf> = Vec::new();
    let queue_dir = state_dir.join("queue");
    if !queue_dir.exists() {
        return (records, staging);
    }
    for shard_entry in std::fs::read_dir(&queue_dir).unwrap().flatten() {
        let shard_path = shard_entry.path();
        if !shard_path.is_dir() {
            continue;
        }
        for recipient_entry in std::fs::read_dir(&shard_path).unwrap().flatten() {
            let recipient_path = recipient_entry.path();
            if !recipient_path.is_dir() {
                continue;
            }
            let recipient_hex = recipient_entry.file_name().to_string_lossy().into_owned();
            for file_entry in std::fs::read_dir(&recipient_path).unwrap().flatten() {
                let file_path = file_entry.path();
                let file_name = file_entry.file_name();
                let name_str = file_name.to_string_lossy();
                if atomic_write::is_staging_tempfile(&name_str) {
                    staging.push(file_path);
                    continue;
                }
                if !file_path.is_file() {
                    continue;
                }
                let bytes = std::fs::read(&file_path).unwrap_or_default();
                let disk_bytes = bytes.len() as u64;
                match serde_json::from_slice::<PersistedRecord>(&bytes) {
                    Ok(rec) => {
                        records.push((recipient_hex.clone(), file_path, rec, disk_bytes));
                    }
                    Err(e) => panic!("unparseable record at {file_path:?}: {e}"),
                }
            }
        }
    }
    (records, staging)
}

// ── round-1 REDLINE P1-F4: process-wide watchdog ─────────────────────

/// OS-thread watchdog that fires `std::process::abort()` if the test
/// does not clear its `shutdown` flag within `WALL_CLOCK_CAP`.
/// Async-only guards cannot cover blocking phases (`boot`, `read_dir`)
/// nor a wedged tokio runtime; this abort-on-timeout does.
///
/// The `Drop` impl on the guard sets the shutdown flag (Release),
/// `unpark()`s the OS thread for prompt wakeup, then `join()`s it.
/// The watchdog thread uses `park_timeout` so `unpark` wakes it
/// immediately AND does a final `Acquire` load of the shutdown
/// flag right before `abort()` — closes the last-200ms race the
/// round-0 shape had (Drop store during final sleep → loop
/// condition expired → abort without re-checking).
///
/// Only a genuine hang past the deadline (Drop never fired)
/// results in `process::abort()`.
struct WatchdogGuard {
    shutdown: Arc<AtomicBool>,
    thread_handle: std::thread::Thread,
    join: Option<std::thread::JoinHandle<()>>,
}

impl WatchdogGuard {
    fn new(cap: Duration) -> Self {
        let shutdown = Arc::new(AtomicBool::new(false));
        let ws = Arc::clone(&shutdown);
        let join = std::thread::spawn(move || {
            let deadline = Instant::now() + cap;
            loop {
                // Acquire pairs with Drop's Release below.
                if ws.load(Ordering::Acquire) {
                    return;
                }
                let now = Instant::now();
                if now >= deadline {
                    // Round-1 REDLINE P1-F3 fix: FINAL flag load
                    // after loop exit and BEFORE abort. Closes the
                    // race where Drop set the flag during the last
                    // sleep — pre-round-1 the loop-exit branch went
                    // straight to abort() without a re-check.
                    if ws.load(Ordering::Acquire) {
                        return;
                    }
                    eprintln!(
                        "FATAL: M5a-4 wall-clock watchdog fired at {}s — hard deadlock guard",
                        cap.as_secs()
                    );
                    std::process::abort();
                }
                let remaining = deadline - now;
                // Bounded park; `unpark` from Drop wakes early.
                std::thread::park_timeout(remaining.min(Duration::from_secs(1)));
            }
        });
        let thread_handle = join.thread().clone();
        Self {
            shutdown,
            thread_handle,
            join: Some(join),
        }
    }
}

impl Drop for WatchdogGuard {
    fn drop(&mut self) {
        // Release pairs with the watchdog's Acquire load; set flag
        // FIRST so any observer that wakes from unpark sees it.
        self.shutdown.store(true, Ordering::Release);
        // Wake watchdog immediately — don't wait for the next
        // park_timeout tick.
        self.thread_handle.unpark();
        if let Some(h) = self.join.take() {
            let _ = h.join();
        }
    }
}

// ── round-1 REDLINE P0: structured OpStats + fail-loud producer ──────

/// Producer per-op outcome counters. Round-1 REDLINE removed
/// `send_capacity_exceeded` / `send_per_recipient_full` because
/// those SendError variants now panic under the deliberately
/// generous caps — they are regressions, not runtime outcomes.
/// Kept `*_dispatch_full` because `RuntimeSendError::Full` is
/// legitimate mpsc backpressure and its ZERO invariant is
/// asserted post-run as a stricter check.
#[derive(Debug, Default, Clone)]
struct OpStats {
    // Send outcomes
    send_fresh: usize,
    send_queued_replay: usize,
    send_tombstone_replay: usize,
    send_dispatch_full: usize,
    // Ack outcomes
    ack_acked: usize,
    ack_idempotent: usize,
    ack_not_found: usize,
    ack_dispatch_full: usize,
    // Sweep outcomes
    sweep_ok: usize,
    sweep_dispatch_full: usize,
    // Cross-op counter
    ops_attempted: usize,
}

impl OpStats {
    fn merge(&mut self, other: &OpStats) {
        self.send_fresh += other.send_fresh;
        self.send_queued_replay += other.send_queued_replay;
        self.send_tombstone_replay += other.send_tombstone_replay;
        self.send_dispatch_full += other.send_dispatch_full;
        self.ack_acked += other.ack_acked;
        self.ack_idempotent += other.ack_idempotent;
        self.ack_not_found += other.ack_not_found;
        self.ack_dispatch_full += other.ack_dispatch_full;
        self.sweep_ok += other.sweep_ok;
        self.sweep_dispatch_full += other.sweep_dispatch_full;
        self.ops_attempted += other.ops_attempted;
    }
    fn total_classified(&self) -> usize {
        self.send_fresh
            + self.send_queued_replay
            + self.send_tombstone_replay
            + self.send_dispatch_full
            + self.ack_acked
            + self.ack_idempotent
            + self.ack_not_found
            + self.ack_dispatch_full
            + self.sweep_ok
            + self.sweep_dispatch_full
    }
    fn total_dispatch_full(&self) -> usize {
        self.send_dispatch_full + self.ack_dispatch_full + self.sweep_dispatch_full
    }
}

// ── the test ──────────────────────────────────────────────────────────

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn concurrency_stress_32_producers_500_ops_within_60s_no_drift() {
    let start = Instant::now();
    // Round-1 REDLINE P1-F4: hard OS-thread watchdog. Fires
    // `std::process::abort()` if the test scope hasn't dropped it
    // within 60 s — covers hangs in blocking `boot()`, `read_dir`,
    // `drain_handles`, and even a wedged tokio runtime.
    let _watchdog = WatchdogGuard::new(WALL_CLOCK_CAP);

    let dir = build_state_dir();

    // 1. Boot + spawn runtime.
    let boot_result = boot(&boot_cfg(dir.path())).expect("boot OK");
    let (fatal_tx, mut fatal_rx) = broadcast::channel::<FatalReason>(64);
    let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY_BYTES));
    let spec = WorkerRuntimeSpec::from_boot(
        boot_result,
        5_000, // max_envelopes_per_recipient — generous
        key,
        caps(),
        fatal_tx,
    )
    .expect("from_boot OK");
    let runtime = Arc::new(spawn_worker_runtime(spec).expect("spawn OK"));
    assert!(matches!(runtime.state_kind(), PoolStateKind::Running));

    let recipients = build_recipient_pool();
    let expires_far = now_secs() + FAR_FUTURE_TTL_SECS;

    // 2. Warmup: one Send per recipient — assert multi-shard spread.
    for recipient in &recipients {
        let candidate = build_send_candidate(
            &format!("warmup-{recipient}"),
            &format!("s-warmup-{recipient}"),
            &format!("p-warmup-{recipient}"),
            expires_far,
        );
        let (tx, rx) = tokio::sync::oneshot::channel();
        runtime
            .try_send(RestOp::Send {
                recipient: recipient.clone(),
                candidate,
                reply: tx,
            })
            .expect("warmup try_send OK");
        let outcome = tokio::time::timeout(Duration::from_secs(5), rx)
            .await
            .expect("warmup reply within 5s")
            .expect("warmup reply channel not dropped")
            .expect("warmup send must succeed");
        assert!(matches!(outcome.disposition, SendDisposition::Fresh));
    }
    let buckets = runtime.recipient_snapshot_by_shard();
    let non_empty_shards = buckets.iter().filter(|b| !b.is_empty()).count();
    assert!(
        non_empty_shards >= MIN_SHARDS_COVERED,
        "recipient pool spans only {non_empty_shards} shards (need >= {MIN_SHARDS_COVERED})",
    );

    // 3. Round-1 REDLINE P1-F3: pre-tombstone the TombstoneReplay
    //    scenario. Send + Ack every id in TOMBSTONE_REPLAY_IDS on
    //    a fixed recipient BEFORE spawning producers. Producers then
    //    Send with the same (recipient, id, body) and the runtime
    //    returns SendDisposition::TombstoneReplay deterministically.
    let tombstone_replay_recipient = recipients[TOMBSTONE_REPLAY_RECIPIENT_INDEX].clone();
    for id in TOMBSTONE_REPLAY_IDS {
        let (s, p) = tombstone_replay_body(id);
        let candidate = build_send_candidate(id, &s, &p, expires_far);
        let (tx, rx) = tokio::sync::oneshot::channel();
        runtime
            .try_send(RestOp::Send {
                recipient: tombstone_replay_recipient.clone(),
                candidate,
                reply: tx,
            })
            .expect("tombstone-replay Send try_send OK");
        let send_out = tokio::time::timeout(Duration::from_secs(5), rx)
            .await
            .expect("tombstone Send reply within 5s")
            .expect("tombstone Send channel not dropped")
            .expect("tombstone Send must succeed");
        assert!(matches!(send_out.disposition, SendDisposition::Fresh));

        let (tx, rx) = tokio::sync::oneshot::channel();
        runtime
            .try_send(RestOp::Ack {
                recipient: tombstone_replay_recipient.clone(),
                envelope_id: id.to_string(),
                reply: tx,
            })
            .expect("tombstone-replay Ack try_send OK");
        let ack_out = tokio::time::timeout(Duration::from_secs(5), rx)
            .await
            .expect("tombstone Ack reply within 5s")
            .expect("tombstone Ack channel not dropped")
            .expect("tombstone Ack must succeed");
        assert!(
            matches!(ack_out, AckOutcome::Acked { .. }),
            "pre-tombstone Ack must fire Acked; got {ack_out:?}"
        );
    }

    // 4. Spawn 32 producer tasks × 500 ops.
    let mut set: JoinSet<OpStats> = JoinSet::new();
    for task_id in 0..N_PRODUCERS {
        let rt = Arc::clone(&runtime);
        let recipients = recipients.clone();
        let tombstone_recipient = tombstone_replay_recipient.clone();
        set.spawn(producer_task(rt, task_id, recipients, tombstone_recipient));
    }
    let mut aggregate = OpStats::default();
    while let Some(res) = set.join_next().await {
        let stats = res.expect("producer task panicked");
        aggregate.merge(&stats);
    }

    // 5. Sanity: every op is classified exactly once.
    let expected_ops = N_PRODUCERS * M_OPS;
    assert_eq!(
        aggregate.ops_attempted, expected_ops,
        "producer ops_attempted {} != {}",
        aggregate.ops_attempted, expected_ops
    );
    assert_eq!(
        aggregate.total_classified(),
        expected_ops,
        "sum of per-outcome counters ({}) != expected ops ({})",
        aggregate.total_classified(),
        expected_ops,
    );

    // 6. Round-1 REDLINE P0: assert non-vacuous coverage of the
    //    contracts the test claims to stress. Warmup + pre-tombstone
    //    guarantee non-empty disk state on their own, so these
    //    counters are the ONLY evidence that producer ops did
    //    real work.
    assert!(
        aggregate.send_fresh > 0,
        "no successful Fresh Send from producers — producer work vanished"
    );
    // Round-1 REDLINE P1-F3: both replay dispositions must fire.
    assert!(
        aggregate.send_queued_replay > 0,
        "collision group produced ZERO QueuedReplay dispositions"
    );
    assert!(
        aggregate.send_tombstone_replay > 0,
        "pre-tombstoned scenario produced ZERO TombstoneReplay dispositions"
    );
    // Round-1 REDLINE P1-F2: acks must hit real (recipient, id)
    // pairs at least sometimes — otherwise the "30 % of ops are
    // Acks" claim is fiction.
    assert!(
        aggregate.ack_acked + aggregate.ack_idempotent > 0,
        "acks never hit a real (recipient, id) pair — Ack ownership tracking broke"
    );
    // Sanity on Sweep: with 12 recipients and 1600 sweep ops,
    // every reasonably-scheduled run reaches at least one success.
    assert!(
        aggregate.sweep_ok > 0,
        "sweep never returned a successful reply — pool routing broke"
    );
    // Round-1 REDLINE strict backpressure invariant: the 128-slot
    // per-shard mpsc buffer is not supposed to overflow under
    // 16 000 ops distributed across 64 shards. `Full` fires ⇒
    // shard-worker was throttled harder than expected ⇒
    // observability / capacity assumption drifted. Assert zero
    // rather than counting silently.
    assert_eq!(
        aggregate.total_dispatch_full(),
        0,
        "dispatch_full fired {} time(s) — RuntimeSendError::Full is a legit \
         mpsc backpressure signal but under this test's load shape it MUST NOT \
         fire (128 slots × 64 shards vs 16 000 ops = comfortable margin); \
         breakdown: send={} ack={} sweep={}",
        aggregate.total_dispatch_full(),
        aggregate.send_dispatch_full,
        aggregate.ack_dispatch_full,
        aggregate.sweep_dispatch_full,
    );

    // 7. No fatal fired.
    assert!(
        matches!(runtime.state_kind(), PoolStateKind::Running),
        "runtime transitioned out of Running mid-run: {:?}",
        runtime.state_kind()
    );
    match fatal_rx.try_recv() {
        Err(broadcast::error::TryRecvError::Empty) => {}
        other => panic!("unexpected fatal broadcast during stress: {other:?}"),
    }

    // 8. Runtime ↔ disk audit.
    let runtime_active_count = runtime.active_entry_count();
    let runtime_tombstone_count = runtime.tombstone_dedup_count();
    let capacity_snapshot = runtime.capacity().snapshot();

    let (disk_records, staging_paths) = walk_state_dir(dir.path());
    assert!(
        staging_paths.is_empty(),
        "found {} residual `.staging-*` file(s) after producers completed: {:?}",
        staging_paths.len(),
        staging_paths
    );

    let mut disk_queued_count = 0u64;
    let mut disk_tombstone_count = 0u64;
    let mut disk_active_bytes = 0u64;
    let mut disk_tombstone_bytes = 0u64;
    for (_recipient, _path, rec, disk_bytes) in &disk_records {
        match rec {
            PersistedRecord::Queued { .. } => {
                disk_queued_count = disk_queued_count.saturating_add(1);
                disk_active_bytes = disk_active_bytes.saturating_add(*disk_bytes);
            }
            PersistedRecord::AckedTombstone { .. } => {
                disk_tombstone_count = disk_tombstone_count.saturating_add(1);
                disk_tombstone_bytes = disk_tombstone_bytes.saturating_add(*disk_bytes);
            }
        }
    }
    assert_eq!(runtime_active_count as u64, disk_queued_count);
    assert_eq!(runtime_tombstone_count as u64, disk_tombstone_count);
    assert_eq!(capacity_snapshot.active_envelopes, disk_queued_count);
    assert_eq!(capacity_snapshot.tombstone_records, disk_tombstone_count);
    assert_eq!(capacity_snapshot.active_bytes, disk_active_bytes);
    assert_eq!(capacity_snapshot.tombstone_bytes, disk_tombstone_bytes);
    assert!(
        capacity_snapshot.ram_bytes >= disk_active_bytes + disk_tombstone_bytes,
        "capacity.ram_bytes {} must dominate disk bytes {} + {}",
        capacity_snapshot.ram_bytes,
        disk_active_bytes,
        disk_tombstone_bytes,
    );

    let pre_drain_fingerprints: HashSet<RecordFingerprint> = disk_records
        .iter()
        .map(|(recipient, _path, rec, _bytes)| fingerprint_of(recipient, rec))
        .collect();
    assert!(
        !pre_drain_fingerprints.is_empty(),
        "producer phase left ZERO records on disk — invariant chain is untestable"
    );

    // 9. Ordered shutdown INSIDE the watchdog budget.
    runtime.close();
    let drain = runtime
        .drain_handles(Duration::from_secs(10))
        .await
        .expect("drain_handles clean under stress");
    for outcome in &drain {
        assert!(
            outcome.is_clean(),
            "worker {} drained dirty: {:?}",
            outcome.worker_id,
            outcome.result
        );
    }
    drop(runtime);

    // 10. Fresh boot on the same state_dir.
    let fresh = boot(&boot_cfg(dir.path())).expect("fresh boot OK");
    let (fresh_records, fresh_staging) = walk_state_dir(dir.path());
    assert!(
        fresh_staging.is_empty(),
        "fresh boot's staging sweep left residuals: {:?}",
        fresh_staging
    );
    let post_boot_fingerprints: HashSet<RecordFingerprint> = fresh
        .records()
        .iter()
        .map(|lr| fingerprint_of(&lr.recipient, &lr.record))
        .collect();
    assert_eq!(
        post_boot_fingerprints, pre_drain_fingerprints,
        "fresh boot recovered a different (variant, recipient, id, seq, body_hash) \
         set than the pre-drain disk audit"
    );
    let post_boot_walked_fingerprints: HashSet<RecordFingerprint> = fresh_records
        .iter()
        .map(|(recipient, _path, rec, _bytes)| fingerprint_of(recipient, rec))
        .collect();
    assert_eq!(post_boot_walked_fingerprints, pre_drain_fingerprints);

    // 11. Wall-clock cap final assertion (documentary; the watchdog
    //     is the real guard).
    let elapsed = start.elapsed();
    assert!(
        elapsed <= WALL_CLOCK_CAP,
        "test exceeded the 60-second wall-clock cap: elapsed = {elapsed:?}"
    );

    eprintln!(
        "M5a-4 concurrency stress: elapsed={:?} recipients={} \
         producer stats: fresh={} queued_replay={} tombstone_replay={} \
         dispatch_full[send/ack/sweep]={}/{}/{} \
         ack_acked={} ack_idem={} ack_notfound={} sweep_ok={} \
         disk queued={} tombstones={} shards>= {}",
        elapsed,
        recipients.len(),
        aggregate.send_fresh,
        aggregate.send_queued_replay,
        aggregate.send_tombstone_replay,
        aggregate.send_dispatch_full,
        aggregate.ack_dispatch_full,
        aggregate.sweep_dispatch_full,
        aggregate.ack_acked,
        aggregate.ack_idempotent,
        aggregate.ack_not_found,
        aggregate.sweep_ok,
        disk_queued_count,
        disk_tombstone_count,
        MIN_SHARDS_COVERED,
    );
}

// ── producer body ─────────────────────────────────────────────────────

async fn producer_task(
    runtime: Arc<WorkerRuntime>,
    task_id: usize,
    recipients: Vec<String>,
    tombstone_replay_recipient: String,
) -> OpStats {
    let seed = 0xB00D_5EED_C0FF_EE00u64.wrapping_add(task_id as u64);
    let mut rng = StdRng::seed_from_u64(seed);

    // Round-1 REDLINE P1-F2: track `(recipient, id)` pairs so Ack
    // fires against the ORIGINAL recipient. Storing bare ids and
    // then re-drawing the recipient produced ~11/12 NotFound acks
    // in the round-0 shape.
    let mut sent_unique: Vec<(String, String)> = Vec::with_capacity(M_OPS);
    let expires_far = now_secs() + FAR_FUTURE_TTL_SECS;
    let mut stats = OpStats::default();

    for op_index in 0..M_OPS {
        stats.ops_attempted += 1;
        let pick: u32 = rng.gen_range(0..100);
        if pick < SEND_THRESHOLD {
            // Decide sub-flavour of Send:
            //   * TombstoneReplay (against tombstone_replay_recipient)
            //   * QueuedReplay (collision group, any recipient)
            //   * Fresh unique
            let sub: u32 = rng.gen_range(0..100);
            let (recipient, id, sealed_sender, payload, expected_class) =
                if sub < TOMBSTONE_REPLAY_SEND_PROB_PCT {
                    let id = TOMBSTONE_REPLAY_IDS[rng.gen_range(0..TOMBSTONE_REPLAY_IDS.len())];
                    let (s, p) = tombstone_replay_body(id);
                    (
                        tombstone_replay_recipient.clone(),
                        id.to_string(),
                        s,
                        p,
                        SendClass::TombstoneReplay,
                    )
                } else if sub < TOMBSTONE_REPLAY_SEND_PROB_PCT + COLLISION_SEND_PROB_PCT {
                    let coll = COLLISION_IDS[rng.gen_range(0..COLLISION_IDS.len())];
                    let (s, p) = collision_body(coll);
                    let recipient = recipients[rng.gen_range(0..recipients.len())].clone();
                    (
                        recipient,
                        coll.to_string(),
                        s,
                        p,
                        SendClass::QueuedOrReplay, // Fresh on first send per (r, id), QueuedReplay after
                    )
                } else {
                    let recipient = recipients[rng.gen_range(0..recipients.len())].clone();
                    let id = unique_envelope_id(task_id, op_index);
                    let (s, p) = unique_body(task_id, op_index);
                    (recipient, id, s, p, SendClass::FreshUnique)
                };
            let candidate = build_send_candidate(&id, &sealed_sender, &payload, expires_far);
            let (tx, rx) = tokio::sync::oneshot::channel();
            match runtime.try_send(RestOp::Send {
                recipient: recipient.clone(),
                candidate,
                reply: tx,
            }) {
                Ok(()) => {}
                Err(RuntimeSendError::Full) => {
                    stats.send_dispatch_full += 1;
                    continue;
                }
                Err(other) => panic!("unexpected try_send error on Send: {other:?}"),
            }
            let reply = tokio::time::timeout(OP_REPLY_TIMEOUT, rx).await.unwrap_or_else(
                |_| panic!("Send reply timeout ({OP_REPLY_TIMEOUT:?}) for id={id}"),
            );
            let outcome = reply.unwrap_or_else(|e| {
                panic!("Send reply oneshot dropped for id={id}: {e:?}")
            });
            match outcome {
                Ok(send_outcome) => {
                    // Round-1 REDLINE P1-F2 fix: exhaustive
                    // `(expected_class, disposition)` match. Every
                    // legal pair increments its counter; every
                    // illegal pair — a pre-tombstoned id that
                    // came back QueuedReplay, a Fresh unique that
                    // came back TombstoneReplay, etc. — panics
                    // fail-loud. Aggregate `> 0` assertions in
                    // the parent could otherwise hide a single
                    // wrong-class reply amongst thousands of
                    // right-class ones.
                    match (expected_class, send_outcome.disposition) {
                        (SendClass::FreshUnique, SendDisposition::Fresh) => {
                            stats.send_fresh += 1;
                            sent_unique.push((recipient, id));
                        }
                        (SendClass::QueuedOrReplay, SendDisposition::Fresh) => {
                            stats.send_fresh += 1;
                        }
                        (SendClass::QueuedOrReplay, SendDisposition::QueuedReplay) => {
                            stats.send_queued_replay += 1;
                        }
                        (SendClass::TombstoneReplay, SendDisposition::TombstoneReplay) => {
                            stats.send_tombstone_replay += 1;
                        }
                        (expected, got) => panic!(
                            "Send class mismatch: expected {expected:?} but got \
                             {got:?} for id={id} recipient={recipient}"
                        ),
                    }
                }
                // Round-1 REDLINE P1-F1: under the deliberately
                // generous caps (100 k envelopes, 100 MiB,
                // 5 000/recipient — see `caps()`), the ledger
                // MUST NOT refuse a Send. If it does, that is a
                // regression, not a legitimate runtime outcome —
                // fail loudly instead of counting the way the
                // round-0 shape did.
                Err(SendError::CapacityExceeded(inner)) => panic!(
                    "SendError::CapacityExceeded fired under generous caps — ledger \
                     regression for id={id}: {inner:?}"
                ),
                Err(SendError::PerRecipientQueueFull { observed, cap }) => panic!(
                    "SendError::PerRecipientQueueFull fired under 5 000/recipient cap — \
                     regression for id={id}: observed={observed} cap={cap}"
                ),
                Err(other) => panic!(
                    "unexpected typed SendError for id={id} recipient={recipient}: {other:?}"
                ),
            }
        } else if pick < ACK_THRESHOLD {
            // Round-1 REDLINE P1-F2: draw from own (recipient, id)
            // pairs. If sent_unique is empty (early ops), draw a
            // synthesised (recipient, id) that will NotFound cleanly.
            let (recipient, envelope_id) = if !sent_unique.is_empty() {
                sent_unique[rng.gen_range(0..sent_unique.len())].clone()
            } else {
                (
                    recipients[rng.gen_range(0..recipients.len())].clone(),
                    format!("ack-nop-t{task_id:02}-op{op_index:04}"),
                )
            };
            let (tx, rx) = tokio::sync::oneshot::channel();
            match runtime.try_send(RestOp::Ack {
                recipient,
                envelope_id: envelope_id.clone(),
                reply: tx,
            }) {
                Ok(()) => {}
                Err(RuntimeSendError::Full) => {
                    stats.ack_dispatch_full += 1;
                    continue;
                }
                Err(other) => panic!("unexpected try_send error on Ack: {other:?}"),
            }
            let reply = tokio::time::timeout(OP_REPLY_TIMEOUT, rx).await.unwrap_or_else(
                |_| panic!("Ack reply timeout ({OP_REPLY_TIMEOUT:?}) for id={envelope_id}"),
            );
            let outcome = reply.unwrap_or_else(|e| {
                panic!("Ack reply oneshot dropped for id={envelope_id}: {e:?}")
            });
            match outcome {
                Ok(AckOutcome::Acked { .. }) => stats.ack_acked += 1,
                Ok(AckOutcome::Idempotent { .. }) => stats.ack_idempotent += 1,
                Ok(AckOutcome::NotFound) => stats.ack_not_found += 1,
                // Round-1 REDLINE P1-F1: `AckError::Persistence` is
                // an I/O infrastructure failure, NOT a
                // NotFound-adjacent outcome. Under a `TempDir` on a
                // healthy dev host it must never fire; if it does,
                // that is a regression the round-0 shape hid by
                // silently counting it as NotFound.
                Err(other) => panic!(
                    "unexpected typed AckError for id={envelope_id}: {other:?} — \
                     Persistence / ShardMismatch / Serialize under a TempDir stress \
                     run is a regression, not a legitimate runtime outcome"
                ),
            }
        } else {
            let recipient = recipients[rng.gen_range(0..recipients.len())].clone();
            let (tx, rx) = tokio::sync::oneshot::channel();
            match runtime.try_send(RestOp::Sweep {
                recipient,
                reply: tx,
            }) {
                Ok(()) => {}
                Err(RuntimeSendError::Full) => {
                    stats.sweep_dispatch_full += 1;
                    continue;
                }
                Err(other) => panic!("unexpected try_send error on Sweep: {other:?}"),
            }
            let reply = tokio::time::timeout(OP_REPLY_TIMEOUT, rx).await.unwrap_or_else(
                |_| panic!("Sweep reply timeout ({OP_REPLY_TIMEOUT:?})"),
            );
            let outcome = reply.unwrap_or_else(|e| {
                panic!("Sweep reply oneshot dropped: {e:?}")
            });
            match outcome {
                Ok(_sweep_outcome) => stats.sweep_ok += 1,
                Err(other) => panic!("unexpected typed SweepError: {other:?}"),
            }
        }
    }
    stats
}

/// Tag on which Send flavour a producer just dispatched. Used
/// exclusively for the "pre-tombstoned id came back Fresh" fail-loud
/// sanity check inside the producer body — a Fresh reply on a
/// tombstone-replay id would mean the pre-tombstone scenario was
/// undermined between test setup and producer start (impossible in
/// this test's shape, but the invariant is worth guarding).
#[derive(Debug, Clone, Copy)]
enum SendClass {
    FreshUnique,
    QueuedOrReplay,
    TombstoneReplay,
}
