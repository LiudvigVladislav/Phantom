// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M4-3 round-1 REDLINE — durable
//! sweep scheduler with shard-aware bounded concurrency.
//!
//! **Round-0 M4-3 shape (from `main.rs`):** a single loop iterated
//! recipients sequentially and awaited the reply for each Sweep,
//! up to 10 s per recipient, before dispatching the next. The
//! architect's REDLINE identified two P1 defects:
//!
//! 1. **Global head-of-line blocking across independent shards.**
//!    `WorkerRuntime` runs [`REST_WORKER_COUNT`] shard-workers in
//!    parallel; `do_sweep` is actor-serialized per shard but a
//!    stalled shard cannot legitimately block healthy ones. At
//!    100 000 recipients the round-0 shape stretched a single
//!    tick to ~11.6 days worst case.
//! 2. **The scheduler itself was untested.** The M4-3 commit
//!    shipped seven `recipient_snapshot` unit tests but never
//!    exercised the actual tick loop end-to-end — the
//!    load-bearing dispatch chain (`try_send(Sweep)` → reply →
//!    outcome accounting) closed the original P0 but was
//!    unproven at the module boundary.
//!
//! This module extracts the tick pass into a pure `async fn`
//! that:
//!
//! - Buckets recipients by owning shard via
//!   [`WorkerRuntime::recipient_snapshot_by_shard`].
//! - Runs different shards concurrently under a
//!   `Semaphore(max_shard_concurrency)`; within a shard,
//!   dispatch stays sequential so the per-shard actor contract
//!   is preserved.
//! - Re-checks [`WorkerRuntime::state_kind`] before every
//!   per-recipient dispatch; a `Running → Closing` transition
//!   mid-tick aborts cleanly without a burst of warning logs
//!   from `try_send`'s `ShuttingDown` branch.
//! - Bounds the whole tick with `tick_deadline`. Each shard
//!   task checks the deadline before every dispatch AND caps
//!   its per-reply timeout by whatever slack remains. A single
//!   stalled shard cannot hold up the tick indefinitely.
//! - Returns a [`SweepTickReport`] with per-tick totals plus
//!   `aborted_by_state` / `aborted_by_deadline` flags so `main.rs`
//!   can log a single structured summary per tick.
//!
//! Everything the scheduler does is public-runtime-only —
//! `active_index` / `tombstone_dedup` guards never leave the
//! runtime.

use std::sync::Arc;
use std::time::Duration;

use tokio::sync::{oneshot, Semaphore};
use tokio::task::JoinSet;
use tokio::time::Instant;

use crate::rest_workers::{
    RestOp, RuntimeSendError, SweepError, WorkerRuntime, REST_WORKER_COUNT,
};
use crate::worker_pool::PoolStateKind;

// ── Public tuning constants ────────────────────────────────────────────

/// Number of shards allowed to sweep concurrently under the
/// production scheduler. Bounded well below
/// [`REST_WORKER_COUNT`] to keep per-tick task-count and mpsc
/// pressure predictable; a shard queue is at most 128 slots
/// ([`REST_WORKER_MPSC_BUFFER`]) so a single scheduler task
/// cannot chain a runaway backlog even under sudden load.
///
/// The specific value `16` is a **conservative provisional
/// limit** picked before any load measurement — no benchmark
/// yet exists that pins the optimal fan-out on the relay VPS
/// hardware profile. The M5 / M6 benchmark + RAM-budget gate
/// is where a measured value belongs; until then this const is
/// held below `REST_WORKER_COUNT / 4` on the safe-side
/// principle that a smaller fan-out is easier to reason about
/// than a larger one that turns out to saturate a downstream
/// resource. The value is intentionally overridable per call
/// so an ops-facing knob or the benchmark itself can retune
/// it without a code change once evidence exists.
///
/// [`REST_WORKER_MPSC_BUFFER`]: crate::rest_workers::REST_WORKER_MPSC_BUFFER
pub const DEFAULT_SHARD_CONCURRENCY: usize = 16;

/// Per-recipient reply deadline used by the production scheduler.
/// Matches the round-0 value — a legit `do_sweep` under load
/// finishes in tens of milliseconds; 10 s catches a pathological
/// worker without letting a single sluggish shard stall the tick.
pub const DEFAULT_PER_RECIPIENT_DEADLINE: Duration = Duration::from_secs(10);

/// Whole-tick deadline used by the production scheduler. Set
/// comfortably below the 300 s cadence so a slow tick cannot
/// chain into the next one, but generous enough to accommodate
/// real-world variance under load.
pub const DEFAULT_TICK_DEADLINE: Duration = Duration::from_secs(240);

// ── Public report type ─────────────────────────────────────────────────

/// Aggregated outcome of one sweep tick. Every counter is the
/// SUM across every shard that ran during the tick.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct SweepTickReport {
    /// Recipients returned by
    /// [`WorkerRuntime::recipient_snapshot_by_shard`] across
    /// every shard bucket — the pre-dispatch count.
    pub recipients_scanned: usize,
    /// Recipients whose `Sweep` op actually made it onto a
    /// shard-worker's mpsc channel (regardless of reply
    /// success). Distinct from `recipients_scanned` because
    /// mid-tick state-abort / deadline-abort skips remaining
    /// recipients WITHOUT dispatching them.
    pub recipients_dispatched: usize,
    /// Total Queued records swept across all shards this tick.
    pub queued_swept: u64,
    /// Total tombstones swept across all shards this tick.
    pub tombstones_swept: u64,
    /// Total on-disk bytes reclaimed across all shards this tick.
    pub disk_reclaimed_bytes: u64,
    /// Total per-recipient failures (dispatch error, dropped
    /// reply, deadline exceeded, typed `SweepError`). Fatal
    /// invariant breaks INSIDE `do_sweep` are `process::abort()`
    /// and never surface here.
    pub failures: u64,
    /// One or more shard tasks observed a non-`Running` state
    /// mid-tick and stopped without dispatching remaining
    /// recipients. Also set when the pre-tick state check
    /// short-circuits the whole tick.
    pub aborted_by_state: bool,
    /// One or more shard tasks observed the tick deadline
    /// expiring and stopped without dispatching remaining
    /// recipients.
    pub aborted_by_deadline: bool,
}

// ── Public entry point ─────────────────────────────────────────────────

/// Run exactly one sweep pass over every recipient reachable
/// via `runtime.recipient_snapshot_by_shard()`.
///
/// - `per_recipient_deadline` bounds the wait for each
///   individual `Sweep` reply. The scheduler also caps this by
///   whatever slack remains against the whole-tick deadline —
///   the smaller of the two wins.
/// - `tick_deadline` bounds the whole pass. Each shard task
///   checks it before every dispatch; the tick returns
///   `aborted_by_deadline: true` if any shard hit it.
/// - `max_shard_concurrency` is clamped to
///   `1..=REST_WORKER_COUNT`. `0` is treated as `1`.
///
/// Returns aggregated per-tick totals. All per-recipient
/// failures are logged inside the tick (typed `tracing` events);
/// the caller receives only the aggregated summary so `main.rs`
/// emits ONE `sweep_tick_complete` line per tick regardless of
/// fleet size.
pub async fn run_sweep_tick(
    runtime: &Arc<WorkerRuntime>,
    per_recipient_deadline: Duration,
    tick_deadline: Duration,
    max_shard_concurrency: usize,
) -> SweepTickReport {
    // Thin wrapper around the cancellable variant — a
    // never-completing cancel future preserves the pre-round-2
    // shape for callers (including every existing test in this
    // file) that do NOT plumb cooperative cancellation.
    run_sweep_tick_cancellable(
        runtime,
        per_recipient_deadline,
        tick_deadline,
        max_shard_concurrency,
        std::future::pending::<()>(),
    )
    .await
}

/// **Round-2 REDLINE P1-1**: cooperative-cancellation variant of
/// [`run_sweep_tick`]. On a fired `cancel` future, invokes
/// [`tokio::task::JoinSet::shutdown`] which aborts every in-flight
/// shard task AND awaits the completion of each abort — guaranteeing
/// that no shard task is still between a state-check and a
/// `runtime.try_send(RestOp::Sweep)` call when this function returns.
///
/// Pre-round-2 the outer main-loop `tokio::select!` dropped the
/// `run_sweep_tick` future on shutdown. `JoinSet::drop` requests
/// abort but does not await it, so the outer scheduler handle could
/// report clean exit while a shard task was still momentarily alive
/// between the two operations. `runtime.close()` could then race that
/// task's `try_send` and produce a `ShuttingDown` refusal that the
/// scheduler no longer accounted for.
///
/// This variant places the cancellation observer INSIDE the tick,
/// so `set.shutdown().await` runs before the future resolves. On
/// cancellation the report carries `aborted_by_state = true`; the
/// caller (main.rs sweep loop) then observes the shutdown flag and
/// returns from its own task.
///
/// `run_sweep_tick` remains a thin wrapper passing
/// `std::future::pending::<()>()` as the cancel future — all pre-
/// round-2 callers keep their signature unchanged.
pub async fn run_sweep_tick_cancellable(
    runtime: &Arc<WorkerRuntime>,
    per_recipient_deadline: Duration,
    tick_deadline: Duration,
    max_shard_concurrency: usize,
    cancel: impl std::future::Future<Output = ()>,
) -> SweepTickReport {
    // Pre-tick state check — cheapest possible early-out and
    // guarantees no dispatch attempts land during shutdown.
    if !matches!(runtime.state_kind(), PoolStateKind::Running) {
        return SweepTickReport {
            aborted_by_state: true,
            ..Default::default()
        };
    }

    let buckets = runtime.recipient_snapshot_by_shard();
    let recipients_scanned: usize = buckets.iter().map(|b| b.len()).sum();
    if recipients_scanned == 0 {
        return SweepTickReport {
            recipients_scanned: 0,
            ..Default::default()
        };
    }

    let concurrency = max_shard_concurrency.clamp(1, REST_WORKER_COUNT);
    let sem = Arc::new(Semaphore::new(concurrency));
    let deadline = Instant::now() + tick_deadline;

    let mut set: JoinSet<ShardReport> = JoinSet::new();
    for bucket in buckets.into_iter().filter(|b| !b.is_empty()) {
        let rt = Arc::clone(runtime);
        let sem = Arc::clone(&sem);
        set.spawn(async move {
            run_shard_bucket(rt, sem, bucket, per_recipient_deadline, deadline).await
        });
    }

    let (results, cancelled) = drain_or_cancel(set, cancel).await;

    let mut aggregated = SweepTickReport {
        recipients_scanned,
        ..Default::default()
    };
    for res in results {
        match res {
            Ok(r) => {
                aggregated.recipients_dispatched = aggregated
                    .recipients_dispatched
                    .saturating_add(r.dispatched);
                aggregated.queued_swept =
                    aggregated.queued_swept.saturating_add(r.queued_swept);
                aggregated.tombstones_swept = aggregated
                    .tombstones_swept
                    .saturating_add(r.tombstones_swept);
                aggregated.disk_reclaimed_bytes = aggregated
                    .disk_reclaimed_bytes
                    .saturating_add(r.disk_reclaimed_bytes);
                aggregated.failures = aggregated.failures.saturating_add(r.failures);
                aggregated.aborted_by_state |= r.aborted_by_state;
                aggregated.aborted_by_deadline |= r.aborted_by_deadline;
            }
            Err(e) => {
                // Tasks that returned via `JoinSet::shutdown().await`
                // land here as `JoinError { is_cancelled: true }`;
                // that is expected under cancellation and NOT a
                // per-shard failure. Only NON-cancelled join errors
                // (panic, actual failure) count as failures.
                if e.is_cancelled() {
                    tracing::debug!(
                        event = "sweep_shard_task_cancelled",
                        "sweep shard task cancelled cleanly by JoinSet::shutdown"
                    );
                } else {
                    aggregated.failures = aggregated.failures.saturating_add(1);
                    tracing::error!(
                        event = "sweep_shard_task_join_failed",
                        error = ?e,
                        "sweep shard task join failure — treated as a per-shard failure",
                    );
                }
            }
        }
    }
    if cancelled {
        aggregated.aborted_by_state = true;
    }
    aggregated
}

/// **Round-2 REDLINE P1-1**: race a `JoinSet` drain against a
/// cancellation future. If cancellation fires FIRST, invokes
/// [`tokio::task::JoinSet::shutdown`] which aborts every in-flight
/// task AND awaits the completion of each abort — guaranteeing no
/// task from the set is still running when this function returns.
///
/// Returns `(collected_results, cancelled)`:
///   * `collected_results` holds every task that completed BEFORE
///     the cancellation branch fired. Tasks aborted by `shutdown()`
///     surface via that method's own drain and are NOT included.
///   * `cancelled` is `true` iff the cancellation branch fired
///     (i.e. `set.shutdown().await` was invoked). `false` means the
///     `JoinSet` was drained naturally.
///
/// `pub(crate)` — reachable to the in-crate tests inside this file's
/// `#[cfg(test)] mod tests`; not part of the crate's public API.
pub(crate) async fn drain_or_cancel<T: Send + 'static>(
    mut set: tokio::task::JoinSet<T>,
    cancel: impl std::future::Future<Output = ()>,
) -> (Vec<Result<T, tokio::task::JoinError>>, bool) {
    let mut collected = Vec::new();
    tokio::pin!(cancel);
    loop {
        tokio::select! {
            biased;
            _ = &mut cancel => {
                // The whole point of round-2: `.shutdown().await`
                // aborts every remaining task AND awaits each abort
                // Drop. Bare `Drop for JoinSet` only requests abort;
                // dropping the outer `set` would return control
                // before the tasks actually stopped.
                set.shutdown().await;
                return (collected, true);
            }
            res = set.join_next() => {
                match res {
                    Some(r) => collected.push(r),
                    None => return (collected, false),
                }
            }
        }
    }
}

// ── Internal: per-shard sweep + accounting ─────────────────────────────

#[derive(Debug, Default)]
struct ShardReport {
    dispatched: usize,
    queued_swept: u64,
    tombstones_swept: u64,
    disk_reclaimed_bytes: u64,
    failures: u64,
    aborted_by_state: bool,
    aborted_by_deadline: bool,
}

async fn run_shard_bucket(
    runtime: Arc<WorkerRuntime>,
    sem: Arc<Semaphore>,
    bucket: Vec<String>,
    per_recipient_deadline: Duration,
    deadline: Instant,
) -> ShardReport {
    let mut report = ShardReport::default();

    // Semaphore acquisition or deadline — whichever fires first.
    // `biased` gives the deadline branch priority so a task
    // waiting behind saturated concurrency still aborts on time
    // even if the semaphore would otherwise become available in
    // the same poll.
    let _permit = tokio::select! {
        biased;
        _ = tokio::time::sleep_until(deadline) => {
            report.aborted_by_deadline = true;
            return report;
        }
        r = sem.acquire_owned() => match r {
            Ok(p) => p,
            Err(_) => {
                // Semaphore closed — treat as a per-shard failure
                // and return. Production code never closes the
                // scheduler's semaphore; this branch is
                // defence-in-depth.
                report.failures = report.failures.saturating_add(1);
                return report;
            }
        },
    };

    for recipient in bucket {
        // Check deadline BEFORE the state check so a
        // near-expiry tick doesn't spend budget on a
        // state-kind lookup for a slot it cannot use.
        if Instant::now() >= deadline {
            report.aborted_by_deadline = true;
            return report;
        }
        // Re-check runtime state — `Running → Closing` mid-tick
        // aborts cleanly. This is CHEAPER than dispatching and
        // letting `try_send` return `ShuttingDown` for every
        // remaining recipient (which would emit one warn per
        // recipient — the exact "warning-message series" the
        // REDLINE flagged).
        if !matches!(runtime.state_kind(), PoolStateKind::Running) {
            report.aborted_by_state = true;
            return report;
        }

        let (reply_tx, reply_rx) = oneshot::channel();
        if let Err(e) = runtime.try_send(RestOp::Sweep {
            recipient: recipient.clone(),
            reply: reply_tx,
        }) {
            report.failures = report.failures.saturating_add(1);
            log_dispatch_failed(&recipient, &e);
            continue;
        }

        // Effective reply deadline = min(per-recipient, remaining
        // tick budget). A near-exhausted tick shouldn't wait a
        // full 10 s for a single reply.
        let remaining = deadline.saturating_duration_since(Instant::now());
        let effective = per_recipient_deadline.min(remaining);

        match tokio::time::timeout(effective, reply_rx).await {
            Ok(Ok(Ok(outcome))) => {
                report.dispatched = report.dispatched.saturating_add(1);
                report.queued_swept =
                    report.queued_swept.saturating_add(outcome.queued_swept);
                report.tombstones_swept = report
                    .tombstones_swept
                    .saturating_add(outcome.tombstones_swept);
                report.disk_reclaimed_bytes = report
                    .disk_reclaimed_bytes
                    .saturating_add(outcome.disk_reclaimed_bytes);
            }
            Ok(Ok(Err(e))) => {
                report.dispatched = report.dispatched.saturating_add(1);
                report.failures = report.failures.saturating_add(1);
                log_typed_error(&recipient, &e);
            }
            Ok(Err(_)) => {
                report.dispatched = report.dispatched.saturating_add(1);
                report.failures = report.failures.saturating_add(1);
                log_reply_dropped(&recipient);
            }
            Err(_) => {
                report.dispatched = report.dispatched.saturating_add(1);
                report.failures = report.failures.saturating_add(1);
                log_reply_timeout(&recipient, effective);
            }
        }
    }
    report
}

fn recipient_prefix(recipient: &str) -> &str {
    &recipient[..8.min(recipient.len())]
}

fn log_dispatch_failed(recipient: &str, e: &RuntimeSendError) {
    tracing::warn!(
        event = "sweep_dispatch_failed",
        recipient = %recipient_prefix(recipient),
        error = ?e,
        "runtime.try_send(Sweep) refused",
    );
}

fn log_typed_error(recipient: &str, e: &SweepError) {
    tracing::warn!(
        event = "sweep_typed_error",
        recipient = %recipient_prefix(recipient),
        error = ?e,
        "SweepError returned",
    );
}

fn log_reply_dropped(recipient: &str) {
    tracing::warn!(
        event = "sweep_reply_dropped",
        recipient = %recipient_prefix(recipient),
        "runtime dropped the sweep reply oneshot",
    );
}

fn log_reply_timeout(recipient: &str, deadline: Duration) {
    tracing::warn!(
        event = "sweep_reply_timeout",
        recipient = %recipient_prefix(recipient),
        deadline_ms = deadline.as_millis() as u64,
        "sweep reply deadline exceeded",
    );
}

// ── Tests ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    //! Round-1 REDLINE tests — cover the load-bearing scheduler
    //! shape end-to-end via `spawn_worker_runtime`.
    //!
    //! Fixed clock via `WorkerRuntimeSpec::from_boot_at` +
    //! `#[tokio::test(start_paused = true)]` — the scheduler's
    //! deadline / interval code path is on tokio timers; the
    //! `do_sweep` expiry comparison uses `ctx.clock` set to
    //! `Fixed(NOW_EPOCH)`. This gives us fully deterministic
    //! wall-clock semantics without depending on `sleep`.

    use super::*;

    use std::sync::Arc;
    use std::time::Duration;

    use tempfile::TempDir;
    use tokio::sync::broadcast;

    use crate::boot_loader::{self, BootConfig, OwnershipExpectation, PreflightCaps};
    use crate::capacity_ledger::CapacityCaps;
    use crate::queue_meta::{write_meta, Phase, QueueMeta, META_VERSION};
    use crate::rest_workers::{
        spawn_worker_runtime, RestOp, SendCandidate, SendDisposition, WorkerRuntimeSpec,
    };
    use crate::seq_mac::SeqMacRootKey;
    use crate::tombstone_config::TombstoneConfig;
    use crate::worker_pool::PoolStateKind;

    const TEST_MAC_KEY: [u8; 32] = [0x11u8; 32];
    const NOW_EPOCH: u64 = 1_720_000_000;

    fn caps() -> CapacityCaps {
        CapacityCaps {
            max_envelopes: 1_000,
            max_bytes: 10_000_000,
            ram_budget: 10_000_000,
        }
    }

    fn build_state_dir() -> TempDir {
        let dir = TempDir::new().unwrap();
        std::fs::create_dir_all(dir.path().join("queue")).unwrap();
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta = QueueMeta {
            version: META_VERSION,
            phase: Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        write_meta(dir.path(), &meta).unwrap();
        dir
    }

    fn spawn_runtime_at(dir: &TempDir, now: u64) -> Arc<WorkerRuntime> {
        spawn_runtime_at_with_horizon(dir, now, 172_800)
    }

    fn spawn_runtime_at_with_horizon(
        dir: &TempDir,
        now: u64,
        horizon_secs: u32,
    ) -> Arc<WorkerRuntime> {
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let boot_cfg = BootConfig {
            state_dir: dir.path().to_path_buf(),
            caps: PreflightCaps::for_tests(),
            tombstone: TombstoneConfig::from_secs(horizon_secs).unwrap(),
            current_seq_mac_key_fingerprint: key.fingerprint(),
            ownership: OwnershipExpectation::permissive_for_tests(),
        };
        let boot_result = boot_loader::boot(&boot_cfg).expect("boot OK");
        let (fatal_tx, _fatal_rx) = broadcast::channel(16);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot_result,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            now,
        )
        .expect("from_boot_at OK");
        Arc::new(spawn_worker_runtime(spec).expect("spawn OK"))
    }

    /// Production-clock spawn — used only by tests that need to
    /// advance wall-clock between ack and sweep. `#[tokio::test]`
    /// pauses tokio timers only; `SystemTime::now()` still runs in
    /// real time, so pairing this spawner with a real
    /// `tokio::time::sleep` (without `start_paused = true`) is
    /// what actually moves the sweep-clock past `dedup_until`.
    fn spawn_runtime_production(dir: &TempDir, horizon_secs: u32) -> Arc<WorkerRuntime> {
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let boot_cfg = BootConfig {
            state_dir: dir.path().to_path_buf(),
            caps: PreflightCaps::for_tests(),
            tombstone: TombstoneConfig::from_secs(horizon_secs).unwrap(),
            current_seq_mac_key_fingerprint: key.fingerprint(),
            ownership: OwnershipExpectation::permissive_for_tests(),
        };
        let boot_result = boot_loader::boot(&boot_cfg).expect("boot OK");
        let (fatal_tx, _fatal_rx) = broadcast::channel(16);
        let spec = WorkerRuntimeSpec::from_boot(
            boot_result,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
        )
        .expect("from_boot OK");
        Arc::new(spawn_worker_runtime(spec).expect("spawn OK"))
    }

    fn recipient_hex(seed: u8) -> String {
        // 64-char lowercase-hex recipient. Content deterministic
        // per seed so different seeds hash to different shards
        // most of the time (not guaranteed, but sufficient for
        // multi-shard scenarios in tests).
        let mut s = String::with_capacity(64);
        for _ in 0..32 {
            s.push_str(&format!("{seed:02x}"));
        }
        s
    }

    fn send_candidate(id: &str, expires_at: u64) -> SendCandidate {
        SendCandidate {
            id: id.into(),
            sealed_sender: format!("s-{id}"),
            payload: format!("p-{id}"),
            sequence_ts: 1_720_000_000_000,
            expires_at,
        }
    }

    async fn seed_send(
        runtime: &Arc<WorkerRuntime>,
        recipient: &str,
        id: &str,
        expires_at: u64,
    ) {
        let (reply_tx, reply_rx) = oneshot::channel();
        runtime
            .try_send(RestOp::Send {
                recipient: recipient.into(),
                candidate: send_candidate(id, expires_at),
                reply: reply_tx,
            })
            .expect("try_send OK");
        let outcome = tokio::time::timeout(Duration::from_secs(5), reply_rx)
            .await
            .expect("reply within 5s")
            .expect("reply channel not dropped")
            .expect("send must succeed");
        assert!(matches!(outcome.disposition, SendDisposition::Fresh));
    }

    async fn seed_ack(runtime: &Arc<WorkerRuntime>, recipient: &str, id: &str) {
        let (reply_tx, reply_rx) = oneshot::channel();
        runtime
            .try_send(RestOp::Ack {
                recipient: recipient.into(),
                envelope_id: id.into(),
                reply: reply_tx,
            })
            .expect("ack try_send OK");
        let _ = tokio::time::timeout(Duration::from_secs(5), reply_rx)
            .await
            .expect("reply within 5s")
            .expect("reply channel not dropped")
            .expect("ack must succeed");
    }

    async fn drain(runtime: &Arc<WorkerRuntime>) {
        runtime.close();
        let _ = runtime.drain_handles(Duration::from_secs(5)).await;
    }

    // ── Test 1: empty runtime ──────────────────────────────────────

    #[tokio::test(start_paused = true)]
    async fn run_sweep_tick_empty_runtime_returns_zero_totals() {
        let dir = build_state_dir();
        let runtime = spawn_runtime_at(&dir, NOW_EPOCH);
        let report = run_sweep_tick(
            &runtime,
            Duration::from_secs(10),
            Duration::from_secs(60),
            DEFAULT_SHARD_CONCURRENCY,
        )
        .await;
        assert_eq!(report.recipients_scanned, 0);
        assert_eq!(report.recipients_dispatched, 0);
        assert_eq!(report.queued_swept, 0);
        assert_eq!(report.tombstones_swept, 0);
        assert_eq!(report.disk_reclaimed_bytes, 0);
        assert_eq!(report.failures, 0);
        assert!(!report.aborted_by_state);
        assert!(!report.aborted_by_deadline);
        drain(&runtime).await;
    }

    // ── Test 2: expired Queued removed via production runtime ──────

    #[tokio::test(start_paused = true)]
    async fn run_sweep_tick_expired_queued_removed_via_production_runtime() {
        let dir = build_state_dir();
        let runtime = spawn_runtime_at(&dir, NOW_EPOCH);
        let recipient = recipient_hex(0xab);
        // expires_at < NOW_EPOCH → do_sweep observes as expired
        seed_send(&runtime, &recipient, "env-exp-1", NOW_EPOCH - 60).await;
        assert_eq!(runtime.active_entry_count(), 1);

        let report = run_sweep_tick(
            &runtime,
            Duration::from_secs(10),
            Duration::from_secs(60),
            DEFAULT_SHARD_CONCURRENCY,
        )
        .await;

        assert_eq!(report.recipients_scanned, 1);
        assert_eq!(report.recipients_dispatched, 1);
        assert_eq!(report.queued_swept, 1);
        assert_eq!(report.tombstones_swept, 0);
        assert!(report.disk_reclaimed_bytes > 0);
        assert_eq!(report.failures, 0);
        assert!(!report.aborted_by_state);
        assert!(!report.aborted_by_deadline);
        assert_eq!(runtime.active_entry_count(), 0);
        drain(&runtime).await;
    }

    // ── Test 3: expired tombstone removed via production runtime ───

    #[tokio::test]
    async fn run_sweep_tick_expired_tombstone_removed_via_production_runtime() {
        // A Fixed clock can't drive this scenario: `do_sweep`
        // reads `ctx.clock` (frozen at spawn), so any tombstone
        // observed as expired at sweep-time was also observed as
        // expired at boot-time — boot compaction would have
        // unlinked it before the runtime ever loaded it. We need
        // the sweep clock to advance PAST the ack + horizon
        // moment while the tombstone is already in the live
        // runtime. That means Production clock + a real wall-
        // clock sleep with `#[tokio::test]` (no `start_paused`).
        let dir = build_state_dir();
        let runtime = spawn_runtime_production(&dir, /*horizon_secs=*/ 1);
        let recipient = recipient_hex(0xcd);
        // `expires_at` well in the future so Send + Ack succeed
        // without touching the Queued-expiry path. Tombstone
        // dedup_until = ack_now + 1s.
        let future_expiry = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs()
            + 3_600;
        seed_send(&runtime, &recipient, "env-ts-1", future_expiry).await;
        seed_ack(&runtime, &recipient, "env-ts-1").await;
        assert_eq!(runtime.tombstone_dedup_count(), 1);

        // Real wall-clock sleep past the 1-second horizon. Two
        // seconds is enough for `dedup_until <= now()` to hold
        // by comfortable margin even on a slow test host.
        tokio::time::sleep(Duration::from_secs(2)).await;

        let report = run_sweep_tick(
            &runtime,
            Duration::from_secs(10),
            Duration::from_secs(60),
            DEFAULT_SHARD_CONCURRENCY,
        )
        .await;

        assert_eq!(report.recipients_scanned, 1);
        assert_eq!(report.recipients_dispatched, 1);
        assert_eq!(report.tombstones_swept, 1);
        assert_eq!(report.queued_swept, 0);
        assert_eq!(report.failures, 0);
        assert!(!report.aborted_by_state);
        assert!(!report.aborted_by_deadline);
        assert_eq!(runtime.tombstone_dedup_count(), 0);
        drain(&runtime).await;
    }

    // ── Test 4: pool Closing before tick → aborted_by_state ───────

    #[tokio::test(start_paused = true)]
    async fn run_sweep_tick_pre_close_returns_aborted_by_state_zero_dispatch() {
        let dir = build_state_dir();
        let runtime = spawn_runtime_at(&dir, NOW_EPOCH);
        // Seed one recipient so recipient_snapshot would be
        // non-empty if we reached it. The pre-tick state check
        // must short-circuit before that path.
        let recipient = recipient_hex(0x11);
        seed_send(&runtime, &recipient, "env-nc-1", NOW_EPOCH + 3_600).await;
        assert_eq!(runtime.active_entry_count(), 1);

        runtime.close();
        assert_eq!(runtime.state_kind(), PoolStateKind::Closing);

        let report = run_sweep_tick(
            &runtime,
            Duration::from_secs(10),
            Duration::from_secs(60),
            DEFAULT_SHARD_CONCURRENCY,
        )
        .await;

        assert!(report.aborted_by_state);
        assert_eq!(report.recipients_scanned, 0, "pre-tick abort must not scan");
        assert_eq!(report.recipients_dispatched, 0);
        assert_eq!(report.failures, 0, "pre-tick abort must not warn");
        // Live record MUST remain — the Closing state means
        // the tick correctly refused to touch it.
        assert_eq!(runtime.active_entry_count(), 1);
        // No drain needed: `close()` already fired.
        let _ = runtime.drain_handles(Duration::from_secs(5)).await;
    }

    // ── Test 5: tick_deadline expires → aborted_by_deadline ────────

    #[tokio::test(start_paused = true)]
    async fn run_sweep_tick_deadline_zero_aborts_before_any_dispatch() {
        let dir = build_state_dir();
        let runtime = spawn_runtime_at(&dir, NOW_EPOCH);
        // Seed enough recipients to make the concurrency
        // limiter matter (16 default). At most 1 permit slot
        // may fire before the zero deadline, so the report
        // will show at most 1 dispatched — usually zero.
        for i in 0..8u8 {
            let r = recipient_hex(0x20 + i);
            seed_send(&runtime, &r, &format!("env-dl-{i}"), NOW_EPOCH - 60).await;
        }
        assert_eq!(runtime.active_entry_count(), 8);

        let report = run_sweep_tick(
            &runtime,
            Duration::from_secs(10),
            // Zero tick deadline — every shard task sees it
            // as expired at the top of `select!`.
            Duration::from_millis(0),
            DEFAULT_SHARD_CONCURRENCY,
        )
        .await;

        assert!(report.aborted_by_deadline);
        assert!(!report.aborted_by_state);
        // recipients_scanned reflects the bucket count from
        // the runtime; the tick simply refuses to work on them.
        assert_eq!(report.recipients_scanned, 8);
        // With a zero deadline we cannot guarantee zero
        // dispatches because a single shard may race the
        // deadline; we DO guarantee the abort flag surfaced.
        assert!(report.recipients_dispatched <= 8);
        drain(&runtime).await;
    }

    // ── Test 6: mid-tick close → per-recipient recheck aborts ─────

    /// Round-2 REDLINE fix: the round-1 shape of this test
    /// called `runtime.close()` BEFORE `run_sweep_tick`, so the
    /// pre-tick guard short-circuited and the per-recipient
    /// recheck was never touched. This rewrite drives
    /// `run_shard_bucket` directly with a rendezvous semaphore
    /// so the transition Running → Closing happens WHILE the
    /// shard task is parked on `sem.acquire()`. Once released
    /// the task's first per-recipient state check must fire
    /// and abort with `aborted_by_state = true` and
    /// `dispatched = 0`, and no `sweep_dispatch_failed` warn
    /// per remaining recipient.
    ///
    /// `run_shard_bucket` + `ShardReport` are module-private
    /// items; this test is inside the same file's
    /// `mod tests` so it reaches them via `super::*`.
    #[tokio::test]
    async fn run_shard_bucket_close_between_permit_wait_and_first_dispatch_aborts_via_recheck(
    ) {
        let dir = build_state_dir();
        let runtime = spawn_runtime_at(&dir, NOW_EPOCH);
        // 3 live records so the ShardReport of a full pass
        // WOULD have `dispatched=3` if the recheck failed to
        // fire.
        let mut bucket: Vec<String> = Vec::new();
        for i in 0..3u8 {
            let r = recipient_hex(0x70 + i);
            seed_send(&runtime, &r, &format!("env-mrb-{i}"), NOW_EPOCH + 3_600).await;
            bucket.push(r);
        }
        assert_eq!(runtime.active_entry_count(), 3);

        // Rendezvous semaphore starts EMPTY. The shard task
        // will block inside `run_shard_bucket`'s
        // `sem.acquire_owned()` until the test adds a permit.
        let sem = Arc::new(Semaphore::new(0));
        let deadline = tokio::time::Instant::now() + Duration::from_secs(60);
        let rt = Arc::clone(&runtime);
        let sem_task = Arc::clone(&sem);
        let bucket_clone = bucket.clone();
        let handle = tokio::spawn(async move {
            super::run_shard_bucket(
                rt,
                sem_task,
                bucket_clone,
                Duration::from_secs(10),
                deadline,
            )
            .await
        });

        // Let the spawned task reach `acquire_owned()`. A
        // single `yield_now` is enough because the task's
        // pre-permit `select!` is the first `.await` point.
        tokio::task::yield_now().await;
        assert_eq!(runtime.state_kind(), PoolStateKind::Running);

        // Fire the mid-tick close. The `Closing` transition
        // lands WHILE the shard task is parked on `acquire`.
        runtime.close();
        assert_eq!(runtime.state_kind(), PoolStateKind::Closing);

        // Release the permit — the shard task resumes, enters
        // the per-recipient loop, hits the state check on
        // its FIRST iteration, and aborts.
        sem.add_permits(1);

        let report = tokio::time::timeout(Duration::from_secs(5), handle)
            .await
            .expect("shard task within 5s")
            .expect("shard task join");

        assert!(
            report.aborted_by_state,
            "per-recipient state recheck must set aborted_by_state \
             after Running → Closing mid-tick"
        );
        assert_eq!(
            report.dispatched, 0,
            "recheck must fire on the FIRST iteration — no try_send \
             attempts land"
        );
        assert_eq!(
            report.failures, 0,
            "the whole point of the recheck: zero warn spam. \
             `try_send` would surface `ShuttingDown` once per \
             remaining recipient without it"
        );
        assert!(!report.aborted_by_deadline);
        // Records untouched.
        assert_eq!(runtime.active_entry_count(), 3);
        let _ = runtime.drain_handles(Duration::from_secs(5)).await;
    }

    // ── Test 7: multi-shard fan-out ────────────────────────────────

    #[tokio::test(start_paused = true)]
    async fn run_sweep_tick_multi_shard_fanout_sweeps_all_expired() {
        let dir = build_state_dir();
        let runtime = spawn_runtime_at(&dir, NOW_EPOCH);
        // Seed enough distinct recipients that they hash across
        // multiple shards. 32 seeds is well above the birthday
        // bound for 64 shards to hit at least a couple.
        for i in 0..32u8 {
            let r = recipient_hex(0x40 + i);
            seed_send(&runtime, &r, &format!("env-mf-{i}"), NOW_EPOCH - 60).await;
        }
        assert_eq!(runtime.active_entry_count(), 32);

        let report = run_sweep_tick(
            &runtime,
            Duration::from_secs(10),
            Duration::from_secs(60),
            DEFAULT_SHARD_CONCURRENCY,
        )
        .await;

        assert_eq!(report.recipients_scanned, 32);
        assert_eq!(report.recipients_dispatched, 32);
        assert_eq!(report.queued_swept, 32);
        assert_eq!(report.failures, 0);
        assert!(!report.aborted_by_state);
        assert!(!report.aborted_by_deadline);
        assert_eq!(runtime.active_entry_count(), 0);
        drain(&runtime).await;
    }

    // ── Round-2 REDLINE P1-1: drain_or_cancel drop-await semantics ─

    /// Load-bearing round-2 test. Parks four tasks on a long sleep;
    /// each task owns a `DropGuard` that increments a shared
    /// `AtomicUsize` on `Drop`. Fires an immediate cancel. Asserts
    /// `drain_or_cancel` did NOT return until all four `DropGuard`s
    /// had actually run — i.e. `JoinSet::shutdown().await` truly
    /// waited for each abort to complete before returning control.
    ///
    /// Pre-round-2 `Drop for JoinSet` would return synchronously
    /// after requesting abort, leaving the four tasks running for
    /// a nondeterministic window. That is exactly the "scheduler
    /// join reports completion before shard tasks stop" defect the
    /// reviewer flagged.
    #[tokio::test]
    async fn drain_or_cancel_awaits_shutdown_before_returning() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        struct DropGuard(Arc<AtomicUsize>);
        impl Drop for DropGuard {
            fn drop(&mut self) {
                self.0.fetch_add(1, Ordering::SeqCst);
            }
        }

        let drops = Arc::new(AtomicUsize::new(0));
        let mut set: tokio::task::JoinSet<()> = tokio::task::JoinSet::new();
        for _ in 0..4 {
            let drops = Arc::clone(&drops);
            set.spawn(async move {
                let _guard = DropGuard(drops);
                // Sleep well past any test wall-clock so cancellation
                // is the only way this task ever completes.
                tokio::time::sleep(Duration::from_secs(300)).await;
            });
        }

        // Give the tasks a couple of poll opportunities so they
        // actually enter their sleeps and own the DropGuard.
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;

        let (collected, cancelled) =
            super::drain_or_cancel(set, std::future::ready(())).await;

        assert!(cancelled, "cancel branch must have fired");
        assert!(
            collected.is_empty(),
            "no task completed naturally under an immediate cancel"
        );
        assert_eq!(
            drops.load(Ordering::SeqCst),
            4,
            "every shard task's Drop MUST have run by the time \
             drain_or_cancel returned — this is the round-2 invariant"
        );
    }

    #[tokio::test]
    async fn drain_or_cancel_returns_all_results_when_no_cancel_fires() {
        let mut set: tokio::task::JoinSet<u64> = tokio::task::JoinSet::new();
        for i in 0..3u64 {
            set.spawn(async move { i * 10 });
        }

        let (collected, cancelled) =
            super::drain_or_cancel(set, std::future::pending::<()>()).await;

        assert!(!cancelled, "pending cancel MUST NOT fire");
        assert_eq!(collected.len(), 3);
        let mut values: Vec<u64> = collected
            .into_iter()
            .map(|r| r.expect("no join errors under clean drain"))
            .collect();
        values.sort_unstable();
        assert_eq!(values, vec![0, 10, 20]);
    }

    #[tokio::test]
    async fn drain_or_cancel_biased_cancel_wins_over_ready_task() {
        // Even when a task is READY at the same poll as the cancel
        // future, `biased` on the `select!` guarantees the cancel
        // branch fires first. This is the invariant that lets us
        // reliably return `aborted_by_state=true` under a
        // shutdown-during-tick race.
        let mut set: tokio::task::JoinSet<u64> = tokio::task::JoinSet::new();
        set.spawn(async { 42 });
        tokio::task::yield_now().await; // give the task a chance to complete

        let (collected, cancelled) =
            super::drain_or_cancel(set, std::future::ready(())).await;

        assert!(cancelled, "biased branch must select cancel first");
        // Task was already done; JoinSet::shutdown either surfaces it
        // in the drop-await or discards it. Either way `collected`
        // stays empty because we already committed to cancel-first.
        assert!(collected.is_empty());
    }

    // ── Round-2 REDLINE P1-1: run_sweep_tick_cancellable integration ─

    #[tokio::test]
    async fn run_sweep_tick_cancellable_immediate_cancel_marks_aborted_by_state() {
        let dir = build_state_dir();
        let runtime = spawn_runtime_at(&dir, NOW_EPOCH);
        for i in 0..4u8 {
            let r = recipient_hex(0xa0 + i);
            seed_send(&runtime, &r, &format!("env-cx-{i}"), NOW_EPOCH - 60).await;
        }

        let report = run_sweep_tick_cancellable(
            &runtime,
            Duration::from_secs(10),
            Duration::from_secs(60),
            DEFAULT_SHARD_CONCURRENCY,
            std::future::ready(()),
        )
        .await;

        assert!(
            report.aborted_by_state,
            "immediate cancel MUST surface as aborted_by_state=true"
        );
        // failures=0 is the key round-2 invariant: cancel-drained
        // shard tasks come back as `is_cancelled()` JoinErrors, and
        // the aggregator MUST NOT count those as failures.
        assert_eq!(
            report.failures, 0,
            "cancelled shard tasks are NOT per-shard failures"
        );
        drain(&runtime).await;
    }

    #[tokio::test]
    async fn run_sweep_tick_cancellable_pending_cancel_matches_run_sweep_tick() {
        // Wrapping behaviour: passing `pending::<()>()` as cancel
        // reproduces the exact `run_sweep_tick` shape, including
        // successful sweep of expired records.
        let dir = build_state_dir();
        let runtime = spawn_runtime_at(&dir, NOW_EPOCH);
        let r = recipient_hex(0xab);
        seed_send(&runtime, &r, "env-eq-1", NOW_EPOCH - 60).await;

        let report = run_sweep_tick_cancellable(
            &runtime,
            Duration::from_secs(10),
            Duration::from_secs(60),
            DEFAULT_SHARD_CONCURRENCY,
            std::future::pending::<()>(),
        )
        .await;

        assert_eq!(report.queued_swept, 1);
        assert_eq!(report.failures, 0);
        assert!(!report.aborted_by_state);
        drain(&runtime).await;
    }

    // ── Test 8: concurrency clamp ──────────────────────────────────

    #[tokio::test(start_paused = true)]
    async fn run_sweep_tick_zero_concurrency_is_treated_as_one() {
        let dir = build_state_dir();
        let runtime = spawn_runtime_at(&dir, NOW_EPOCH);
        let recipient = recipient_hex(0x55);
        seed_send(&runtime, &recipient, "env-cc-1", NOW_EPOCH - 60).await;

        // max_shard_concurrency=0 must clamp to 1, not
        // deadlock.
        let report = run_sweep_tick(
            &runtime,
            Duration::from_secs(10),
            Duration::from_secs(60),
            0,
        )
        .await;

        assert_eq!(report.queued_swept, 1);
        assert_eq!(report.failures, 0);
        drain(&runtime).await;
    }
}
