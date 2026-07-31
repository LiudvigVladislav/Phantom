// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M1 — `WorkerPool` state machine.
//!
//! Locked design: `v4.2-amendments.md` §5 B-5 (state machine +
//! cleanup lifecycle) + `v4.2.1-amendments.md` §1 (unified 45s
//! shutdown deadline + step-9 crash-equivalent).
//!
//! Round-1 review amendments (2026-07-20):
//! - Two-phase construction via [`WorkerPoolBuilder`] so worker
//!   tasks can hold a state handle at spawn time (round-1 P1 #3).
//! - Read-only [`PoolStateReader`] replaces the raw
//!   `state_handle()` accessor so external code cannot mutate the
//!   pool state (round-1 P1 #3 tail).
//! - Concurrent `drain_handles` via `FuturesUnordered` with
//!   preserved `JoinError` / `FatalReason` outcomes and an
//!   accurate `unresolved_worker_count` at deadline expiry
//!   (round-1 P1 #4).

use std::sync::Arc;
use std::time::Duration;

use futures_util::stream::{FuturesUnordered, StreamExt};
use parking_lot::RwLock;
use tokio::sync::{mpsc, Mutex};
use tokio::task::{JoinError, JoinHandle};

use crate::rest_workers::{FatalReason, RestOp};

/// Lifecycle of the shard-worker fleet.
///
/// Locked design v4.2 §5 B-5: the pool moves strictly forward
/// through `Running → Closing → Closed`. Transitions are single-
/// mutation writes under a `parking_lot::RwLock`, so worker exit
/// classification (planned vs unexpected) is atomic with `close`.
pub enum PoolState {
    /// Fleet is up. Handlers may take short-lived `try_send`
    /// borrows against the vector of senders.
    Running(Vec<mpsc::Sender<RestOp>>),
    /// `close()` has taken the senders. In-flight handlers observe
    /// `TrySendError::Closed`; workers observe `rx.recv() == None`
    /// once every in-flight clone drops.
    Closing,
    /// Every `JoinHandle` has been awaited to completion.
    Closed,
}

/// Error kinds a caller of [`WorkerPool::try_send`] must translate
/// into the appropriate HTTP status.
#[derive(Debug)]
pub enum TrySendError {
    /// The bounded channel is full (locked design v4 §2). Callers
    /// map this to `HTTP 503 error="worker_backpressure"` per v4.1
    /// §3 V-M1 without unbounded await.
    Full,
    /// The pool is `Closing` / `Closed`. Callers map this to
    /// `HTTP 503 error="shutting_down"`.
    ShuttingDown,
    /// The provided `worker_id` is outside `[0, REST_WORKER_COUNT)`.
    /// This is a router bug, not a runtime backpressure condition;
    /// callers surface it as a `500` and it MUST page.
    WorkerIdOutOfBounds { worker_id: usize },
}

impl std::fmt::Display for TrySendError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TrySendError::Full => write!(f, "worker channel full (backpressure)"),
            TrySendError::ShuttingDown => write!(f, "worker pool shutting down"),
            TrySendError::WorkerIdOutOfBounds { worker_id } => {
                write!(f, "worker id {worker_id} out of bounds")
            }
        }
    }
}

impl std::error::Error for TrySendError {}

/// Outcome of a single worker's drain (locked design v4.2 §5 B-5
/// tail + round-1 P1 #4).
///
/// Preserves everything a caller may need for shutdown logging:
/// the panic payload (via `JoinError::is_panic`), a
/// [`FatalReason`] returned from `worker_loop` (mis-routing,
/// unexpected-exit, or panic that `catch_unwind` observed), or a
/// clean `Ok(())` planned exit.
#[derive(Debug)]
pub struct WorkerDrainOutcome {
    pub worker_id: usize,
    pub result: Result<Result<(), FatalReason>, JoinError>,
}

impl WorkerDrainOutcome {
    /// True iff the worker exited cleanly (planned close, no
    /// panic, no `FatalReason`).
    pub fn is_clean(&self) -> bool {
        matches!(&self.result, Ok(Ok(())))
    }
}

/// Error kind returned by [`WorkerPool::drain_handles`] on
/// deadline expiry.
///
/// Carries the completed outcomes observed BEFORE the deadline
/// fired, plus a precise `unresolved_worker_count` (round-1 P1 #4:
/// the pre-amendment code always returned `total`, even when many
/// workers had already completed).
#[derive(Debug)]
pub struct ShutdownDeadlineExceeded {
    pub unresolved_worker_count: usize,
    pub completed_outcomes: Vec<WorkerDrainOutcome>,
}

impl std::fmt::Display for ShutdownDeadlineExceeded {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "shutdown deadline exceeded with {} unresolved worker(s); {} outcome(s) collected",
            self.unresolved_worker_count,
            self.completed_outcomes.len(),
        )
    }
}

impl std::error::Error for ShutdownDeadlineExceeded {}

/// Outcome kinds for [`WorkerPool::drain_handles`] (round-2 P1).
///
/// Pre-round-2 the method returned `Result<_, ShutdownDeadlineExceeded>`
/// which conflated three distinct failure modes:
///   * pool not in `Closing` — invariant break (drain called from
///     `Running` or after a prior `Closed`);
///   * a prior drain already attempted — second drain would falsely
///     transition to `Closed` on empty handles even though the
///     first drain detached its `FuturesUnordered` workers on
///     timeout;
///   * genuine deadline expiry.
/// The unified [`DrainError`] surfaces each explicitly so the
/// caller (M4 shutdown sequence) can pick the right recovery path.
#[derive(Debug)]
pub enum DrainError {
    /// Called with `current_state_kind()` != `Closing`. Locked
    /// design v4.2 §5 B-5 §6-7: `drain_handles` is a Closing-only
    /// operation. `close()` MUST have been observed first.
    NotClosing { current: PoolStateKind },
    /// A prior `drain_handles` already claimed the handles vector
    /// (either completed cleanly or timed out). Re-entry is
    /// refused because after a timeout the detached
    /// `FuturesUnordered` workers may still be running — the pool
    /// cannot be safely transitioned to `Closed`.
    AlreadyAttempted,
    /// Genuine timeout — see [`ShutdownDeadlineExceeded`] payload.
    /// The pool state remains `Closing`; caller should log the
    /// snapshot and perform `std::process::exit(1)` per v4.2.1 §1.
    DeadlineExceeded(ShutdownDeadlineExceeded),
}

impl std::fmt::Display for DrainError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DrainError::NotClosing { current } => {
                write!(
                    f,
                    "drain_handles refused: current pool state is {current:?}, expected Closing"
                )
            }
            DrainError::AlreadyAttempted => {
                write!(
                    f,
                    "drain_handles refused: prior drain already claimed the handle vector"
                )
            }
            DrainError::DeadlineExceeded(inner) => write!(f, "{inner}"),
        }
    }
}

impl std::error::Error for DrainError {}

/// Read-only accessor to the pool state (round-1 P1 #3).
///
/// Handed to worker tasks at spawn time so they can classify
/// clean-exit vs unexpected-exit against the CURRENT state
/// without holding a write-capable handle. `Clone`-able so
/// multiple workers share it.
#[derive(Clone)]
pub struct PoolStateReader {
    state: Arc<RwLock<PoolState>>,
}

impl PoolStateReader {
    /// Nanosecond-scoped read of the current lifecycle
    /// discriminant.
    pub fn kind(&self) -> PoolStateKind {
        match &*self.state.read() {
            PoolState::Running(_) => PoolStateKind::Running,
            PoolState::Closing => PoolStateKind::Closing,
            PoolState::Closed => PoolStateKind::Closed,
        }
    }
}

/// Two-phase construction handle (round-1 P1 #3 + round-2 P1).
///
/// The workers need an `Arc<RwLock<PoolState>>` at spawn time so
/// they can query it on exit. Building the state Arc INSIDE
/// `WorkerPool::new()` after `spawn` would give the workers no
/// way to observe the state — hence the split:
///
/// 1. Caller creates channels + a builder.
/// 2. Caller obtains a [`PoolStateReader`] from the builder and
///    passes it into each worker task at spawn.
/// 3. After every worker is spawned, caller invokes
///    [`WorkerPoolBuilder::finalize`] with the `JoinHandle`s to
///    produce the fully-constructed pool.
///
/// The builder OWNS the sender vector until `finalize` — no
/// external code can observe or mutate the state between step 1
/// and step 3.
///
/// **Cardinality invariant (round-2 P1)**: the builder pins the
/// original sender count. `finalize()` panics if the caller passes
/// a handle vector of a different length — that mismatch would
/// silently detach either a worker (fewer handles than senders)
/// or leave a phantom slot with no worker to await (more handles
/// than senders), and either breaks the `Closed` invariant that
/// "every real worker handle was awaited".
pub struct WorkerPoolBuilder {
    state: Arc<RwLock<PoolState>>,
    /// Captured sender count from [`WorkerPoolBuilder::new`].
    /// Consumed by [`WorkerPoolBuilder::finalize`] as the
    /// authoritative fleet-size invariant.
    senders_count: usize,
}

impl WorkerPoolBuilder {
    /// Build a fresh pool builder with the given worker channels.
    /// State starts at `Running(senders)`. `senders_count` is
    /// snapshotted at construction and cross-checked on
    /// [`WorkerPoolBuilder::finalize`].
    pub fn new(senders: Vec<mpsc::Sender<RestOp>>) -> Self {
        let senders_count = senders.len();
        Self {
            state: Arc::new(RwLock::new(PoolState::Running(senders))),
            senders_count,
        }
    }

    /// Clone a read-only state accessor for a worker task.
    pub fn state_reader(&self) -> PoolStateReader {
        PoolStateReader {
            state: Arc::clone(&self.state),
        }
    }

    /// Snapshot of the sender count captured at
    /// [`WorkerPoolBuilder::new`]. Useful for the caller to sanity-
    /// check its spawn loop against the same invariant
    /// [`WorkerPoolBuilder::finalize`] enforces.
    pub fn senders_count(&self) -> usize {
        self.senders_count
    }

    /// Consume the builder and produce a fully-constructed pool.
    ///
    /// **Panics** if `handles.len() != self.senders_count`. Locked
    /// design v4.2 §5 B-5 tail (round-2 P1): the Vec index is the
    /// stable `worker_id` binding; a mismatch is a boot-time
    /// invariant violation, not a runtime error. Fail-loud so the
    /// bug is caught at the earliest point.
    pub fn finalize(self, handles: Vec<JoinHandle<Result<(), FatalReason>>>) -> WorkerPool {
        assert_eq!(
            handles.len(),
            self.senders_count,
            "WorkerPoolBuilder::finalize cardinality violation: handles.len()={} != senders_count={} (worker_id ↔ JoinHandle binding is broken)",
            handles.len(),
            self.senders_count,
        );
        WorkerPool {
            state: self.state,
            handles: Mutex::new(handles),
            expected_worker_count: self.senders_count,
            drain_attempted: std::sync::atomic::AtomicBool::new(false),
        }
    }
}

/// The shard-worker fleet handle held by `AppState` (wired in M4).
///
/// Locked design v4.2 §5 B-5: `state` is `parking_lot::RwLock`
/// (synchronous, no `.await`) so a worker exiting via
/// `rx.recv() == None` can classify itself against the CURRENT
/// pool state without racing the shutdown broadcast.
///
/// Construction: use [`WorkerPoolBuilder`]. Direct field access is
/// deliberately unavailable outside the module — the pool state
/// machine is one-way, and the builder pattern is the sole entry.
///
/// Round-2 P1 additions:
/// - `expected_worker_count` captures the fleet size at
///   construction. `drain_handles()` cross-checks its handle
///   vector against this so a stale/torn Mutex value cannot
///   silently trigger a false `Closed` transition.
/// - `drain_attempted` is a one-shot AtomicBool guarding
///   `drain_handles()` re-entry. After a timeout the pool's
///   `FuturesUnordered` detached its workers; a second drain
///   would see an empty handles vec and (before this amendment)
///   falsely transition to `Closed`. Now the second call returns
///   [`DrainError::AlreadyAttempted`] and the pool stays
///   `Closing`.
pub struct WorkerPool {
    state: Arc<RwLock<PoolState>>,
    handles: Mutex<Vec<JoinHandle<Result<(), FatalReason>>>>,
    expected_worker_count: usize,
    drain_attempted: std::sync::atomic::AtomicBool,
}

impl WorkerPool {
    /// Clone a read-only state accessor from an already-constructed
    /// pool. Prefer [`WorkerPoolBuilder::state_reader`] at boot —
    /// this variant exists for late spawns (M3/M4 cleanup task).
    pub fn state_reader(&self) -> PoolStateReader {
        PoolStateReader {
            state: Arc::clone(&self.state),
        }
    }

    /// Try to enqueue `op` on the given worker's channel.
    ///
    /// Locked design v4.1 V-P0-1 + v4.2 §5 B-5: the handler MUST
    /// NOT hold an owned `Sender` clone across `.await` boundaries.
    /// The pool holds the vector; this method borrows the sender
    /// synchronously (`parking_lot` read lock, nanoseconds), calls
    /// `try_send`, and drops the borrow before returning.
    pub fn try_send(&self, worker_id: usize, op: RestOp) -> Result<(), TrySendError> {
        let state = self.state.read();
        match &*state {
            PoolState::Running(senders) => {
                let sender = senders
                    .get(worker_id)
                    .ok_or(TrySendError::WorkerIdOutOfBounds { worker_id })?;
                sender.try_send(op).map_err(|err| match err {
                    mpsc::error::TrySendError::Full(_) => TrySendError::Full,
                    mpsc::error::TrySendError::Closed(_) => TrySendError::ShuttingDown,
                })
            }
            PoolState::Closing | PoolState::Closed => Err(TrySendError::ShuttingDown),
        }
    }

    /// Return the current lifecycle discriminant. Cheap
    /// nanosecond-scoped read; safe to call from any task.
    pub fn current_state_kind(&self) -> PoolStateKind {
        match &*self.state.read() {
            PoolState::Running(_) => PoolStateKind::Running,
            PoolState::Closing => PoolStateKind::Closing,
            PoolState::Closed => PoolStateKind::Closed,
        }
    }

    /// Transition `Running → Closing`. The sender vector is dropped
    /// inside this call; every `Sender` clone held by handlers is a
    /// separate reference — those close when the last handler
    /// awaiting the reply exits.
    ///
    /// Locked design v4.2 §5 B-5 §6-7: called ONLY after
    /// (1) axum main-server graceful shutdown returned and
    /// (2) the cleanup task JoinHandle has been awaited to
    /// completion. Calling out of order lets a stray Sweep enqueue
    /// against a closed pool.
    ///
    /// Idempotent: repeated `close()` while already `Closing` or
    /// `Closed` is a no-op.
    pub fn close(&self) {
        let mut state = self.state.write();
        match &*state {
            PoolState::Running(_) => *state = PoolState::Closing,
            PoolState::Closing | PoolState::Closed => {}
        }
    }

    /// Fleet size captured at construction. Never mutates; safe to
    /// call from any thread.
    pub fn expected_worker_count(&self) -> usize {
        self.expected_worker_count
    }

    /// Await every worker `JoinHandle` under a shutdown deadline
    /// (round-1 P1 #4 + round-2 P1).
    ///
    /// - **State-guard (round-2 P1)**: refuses entry unless the
    ///   pool is currently `Closing`. `close()` MUST have been
    ///   called first; calling from `Running` returns
    ///   [`DrainError::NotClosing`] and does NOT mutate anything.
    ///   Calling from `Closed` is likewise rejected — the pool
    ///   already reached its terminal state via a prior drain.
    /// - **Single-attempt guard (round-2 P1)**: a one-shot
    ///   `AtomicBool` claim ensures no second call can succeed.
    ///   After a timeout the `FuturesUnordered` was dropped which
    ///   detached still-pending workers; a re-drain that saw an
    ///   empty handle vec would (before this amendment) falsely
    ///   transition the pool to `Closed`. Second call now returns
    ///   [`DrainError::AlreadyAttempted`] and the pool stays
    ///   `Closing`.
    /// - Runs the drains **concurrently** via `FuturesUnordered`
    ///   — a slow worker no longer keeps a fast worker's outcome
    ///   from being observed.
    /// - Preserves every worker's outcome ([`WorkerDrainOutcome`]),
    ///   including a `JoinError` from a panicked task or a
    ///   returned [`FatalReason`].
    /// - On deadline expiry returns
    ///   [`DrainError::DeadlineExceeded`] carrying the exact
    ///   count of still-pending workers PLUS the outcomes already
    ///   collected before the deadline fired. Pool state remains
    ///   `Closing`.
    /// - **Cardinality invariant (round-2 P1)**: on entry the
    ///   handle vector length is asserted equal to
    ///   `expected_worker_count`. Torn state (someone mutated the
    ///   Mutex out-of-band) triggers a panic rather than a silent
    ///   Closed transition.
    ///
    /// Locked design v4.2.1 §1: on deadline the caller performs
    /// `std::process::exit(1)` (crash-equivalent). This method
    /// itself does not exit the process; M4 wires the process-
    /// exit path around the error return.
    pub async fn drain_handles(
        &self,
        deadline: Duration,
    ) -> Result<Vec<WorkerDrainOutcome>, DrainError> {
        use std::sync::atomic::Ordering;

        // (a) State guard — round-2 P1. Read once, no `.await`
        // between the read and the outcome so the transition
        // Running → Closing → Closed cannot slip past mid-decision.
        let kind = self.current_state_kind();
        if kind != PoolStateKind::Closing {
            return Err(DrainError::NotClosing { current: kind });
        }

        // (b) One-shot claim — round-2 P1. compare_exchange makes
        // the "first drain wins" invariant race-free without
        // holding an additional mutex.
        if self
            .drain_attempted
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
        {
            return Err(DrainError::AlreadyAttempted);
        }

        // (c) Cardinality invariant. If someone mutated the Mutex
        // out of band (impossible via public API, but the check
        // costs nothing and catches a future refactor bug), we
        // fail-loud rather than silently transition to `Closed`.
        let mut handles = self.handles.lock().await;
        let taken = std::mem::take(&mut *handles);
        drop(handles);
        assert_eq!(
            taken.len(),
            self.expected_worker_count,
            "WorkerPool::drain_handles cardinality violation: handles={} but expected={} — pool has been torn",
            taken.len(),
            self.expected_worker_count,
        );
        let total = taken.len();

        if total == 0 {
            // Legitimate empty-fleet close. State was already
            // Closing (guard above); safe to transition to Closed.
            *self.state.write() = PoolState::Closed;
            return Ok(Vec::new());
        }

        let mut set: FuturesUnordered<_> = taken
            .into_iter()
            .enumerate()
            .map(|(worker_id, handle)| async move { (worker_id, handle.await) })
            .collect();

        let mut outcomes: Vec<WorkerDrainOutcome> = Vec::with_capacity(total);
        let deadline_at = tokio::time::Instant::now() + deadline;

        loop {
            if outcomes.len() == total {
                *self.state.write() = PoolState::Closed;
                return Ok(outcomes);
            }
            tokio::select! {
                biased;
                _ = tokio::time::sleep_until(deadline_at) => {
                    // Unresolved count = FuturesUnordered's still-pending
                    // future count (accurate, unlike the pre-amendment
                    // `total` fallback). State stays Closing.
                    let unresolved = set.len();
                    return Err(DrainError::DeadlineExceeded(ShutdownDeadlineExceeded {
                        unresolved_worker_count: unresolved,
                        completed_outcomes: outcomes,
                    }));
                }
                item = set.next() => {
                    match item {
                        Some((worker_id, result)) => {
                            outcomes.push(WorkerDrainOutcome { worker_id, result });
                        }
                        None => {
                            // Set drained — loop head condition will
                            // catch this on next iteration.
                        }
                    }
                }
            }
        }
    }
}

/// Copy of [`PoolState`] discriminant without carrying senders.
/// Handy for tests + tracing snapshots.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PoolStateKind {
    Running,
    Closing,
    Closed,
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::mpsc;

    /// Build a pool with `size` channels + `size` no-op worker
    /// tasks (each immediately returns `Ok(())`). Post-round-2 the
    /// builder enforces `handles.len() == senders.len()`, so tests
    /// need real handles even at zero-op scale.
    fn build_pool(size: usize) -> (WorkerPool, Vec<mpsc::Receiver<RestOp>>) {
        let mut senders = Vec::with_capacity(size);
        let mut receivers = Vec::with_capacity(size);
        for _ in 0..size {
            let (tx, rx) = mpsc::channel::<RestOp>(2);
            senders.push(tx);
            receivers.push(rx);
        }
        let builder = WorkerPoolBuilder::new(senders);
        let mut handles: Vec<JoinHandle<Result<(), FatalReason>>> = Vec::with_capacity(size);
        for _ in 0..size {
            handles.push(tokio::spawn(async { Ok::<(), FatalReason>(()) }));
        }
        let pool = builder.finalize(handles);
        (pool, receivers)
    }

    fn dummy_ack(recipient: &str) -> RestOp {
        let (reply, _) = tokio::sync::oneshot::channel();
        RestOp::Ack {
            recipient: recipient.into(),
            envelope_id: "test-envelope".into(),
            reply,
        }
    }

    #[tokio::test]
    async fn builder_state_reader_observes_running_before_finalize() {
        // Round-1 P1 #3: the reader handed to workers pre-finalize
        // reports `Running` — matching the state that will be in
        // effect once finalize returns.
        let (tx, _rx) = mpsc::channel::<RestOp>(1);
        let builder = WorkerPoolBuilder::new(vec![tx]);
        let reader = builder.state_reader();
        assert_eq!(reader.kind(), PoolStateKind::Running);
        // Finalize with one no-op handle — round-2 P1 cardinality
        // invariant requires handles.len() == senders_count.
        let handles = vec![tokio::spawn(async { Ok::<(), FatalReason>(()) })];
        let _pool = builder.finalize(handles);
        assert_eq!(reader.kind(), PoolStateKind::Running);
    }

    #[tokio::test]
    async fn builder_state_reader_observes_close_transition() {
        let (tx, _rx) = mpsc::channel::<RestOp>(1);
        let builder = WorkerPoolBuilder::new(vec![tx]);
        let reader = builder.state_reader();
        let handles = vec![tokio::spawn(async { Ok::<(), FatalReason>(()) })];
        let pool = builder.finalize(handles);
        pool.close();
        assert_eq!(reader.kind(), PoolStateKind::Closing);
    }

    #[tokio::test]
    async fn new_pool_starts_running() {
        let (pool, _rxs) = build_pool(4);
        assert_eq!(pool.current_state_kind(), PoolStateKind::Running);
    }

    #[tokio::test]
    async fn try_send_delivers_when_running() {
        let (pool, mut rxs) = build_pool(2);
        pool.try_send(0, dummy_ack("test-a")).expect("send OK");
        let op = rxs[0].recv().await.expect("op arrived");
        assert_eq!(op.recipient(), "test-a");
    }

    #[tokio::test]
    async fn try_send_reports_full_when_channel_saturated() {
        let (pool, _rxs) = build_pool(1);
        pool.try_send(0, dummy_ack("first")).expect("first OK");
        pool.try_send(0, dummy_ack("second")).expect("second OK");
        let third = pool.try_send(0, dummy_ack("third"));
        assert!(matches!(third, Err(TrySendError::Full)));
    }

    #[tokio::test]
    async fn try_send_returns_worker_id_out_of_bounds() {
        let (pool, _rxs) = build_pool(4);
        let err = pool
            .try_send(9999, dummy_ack("target"))
            .expect_err("out-of-bounds should error");
        assert!(matches!(
            err,
            TrySendError::WorkerIdOutOfBounds { worker_id: 9999 }
        ));
    }

    #[tokio::test]
    async fn close_transitions_running_to_closing_and_is_idempotent() {
        let (pool, _rxs) = build_pool(2);
        pool.close();
        assert_eq!(pool.current_state_kind(), PoolStateKind::Closing);
        pool.close();
        assert_eq!(pool.current_state_kind(), PoolStateKind::Closing);
    }

    #[tokio::test]
    async fn try_send_after_close_reports_shutting_down() {
        let (pool, _rxs) = build_pool(2);
        pool.close();
        let err = pool
            .try_send(0, dummy_ack("late"))
            .expect_err("post-close send must fail");
        assert!(matches!(err, TrySendError::ShuttingDown));
    }

    #[tokio::test]
    async fn drain_handles_transitions_closing_to_closed_when_empty() {
        let (pool, _rxs) = build_pool(0);
        pool.close();
        let outcomes = pool
            .drain_handles(Duration::from_secs(1))
            .await
            .expect("empty drain returns Ok");
        assert!(outcomes.is_empty());
        assert_eq!(pool.current_state_kind(), PoolStateKind::Closed);
    }

    #[tokio::test]
    async fn drain_handles_returns_all_outcomes_on_clean_drain() {
        // Round-1 P1 #4: outcomes are preserved for logging.
        let (tx0, _rx0) = mpsc::channel::<RestOp>(1);
        let (tx1, _rx1) = mpsc::channel::<RestOp>(1);
        let (tx2, _rx2) = mpsc::channel::<RestOp>(1);
        let handles = vec![
            tokio::spawn(async { Ok::<(), FatalReason>(()) }),
            tokio::spawn(async { Err(FatalReason::WorkerUnexpectedExit { worker_id: 1 }) }),
            tokio::spawn(async { panic!("simulated panic in worker 2") }),
        ];
        let pool = WorkerPoolBuilder::new(vec![tx0, tx1, tx2]).finalize(handles);
        pool.close();
        let outcomes = pool
            .drain_handles(Duration::from_secs(2))
            .await
            .expect("clean drain");
        assert_eq!(outcomes.len(), 3);
        // Every outcome carries its worker_id.
        let mut ids: Vec<_> = outcomes.iter().map(|o| o.worker_id).collect();
        ids.sort();
        assert_eq!(ids, vec![0, 1, 2]);
        // Exactly one clean, one FatalReason, one JoinError panic.
        let clean = outcomes.iter().filter(|o| o.is_clean()).count();
        let fatal = outcomes
            .iter()
            .filter(|o| matches!(&o.result, Ok(Err(_))))
            .count();
        let join_err = outcomes.iter().filter(|o| o.result.is_err()).count();
        assert_eq!(clean, 1);
        assert_eq!(fatal, 1);
        assert_eq!(join_err, 1);
        assert_eq!(pool.current_state_kind(), PoolStateKind::Closed);
    }

    #[tokio::test(start_paused = true)]
    async fn drain_handles_returns_accurate_unresolved_count_on_deadline() {
        // Round-1 P1 #4: pre-amendment always returned `total` here;
        // this test asserts fast finishers are counted as completed
        // and only the slow ones remain unresolved.
        let handles: Vec<JoinHandle<Result<(), FatalReason>>> = vec![
            tokio::spawn(async { Ok(()) }),
            tokio::spawn(async { Ok(()) }),
            tokio::spawn(async {
                tokio::time::sleep(Duration::from_secs(60)).await;
                Ok(())
            }),
        ];
        let (tx0, _rx0) = mpsc::channel::<RestOp>(1);
        let (tx1, _rx1) = mpsc::channel::<RestOp>(1);
        let (tx2, _rx2) = mpsc::channel::<RestOp>(1);
        let pool = WorkerPoolBuilder::new(vec![tx0, tx1, tx2]).finalize(handles);
        pool.close();
        tokio::task::yield_now().await;

        let err = pool
            .drain_handles(Duration::from_millis(100))
            .await
            .expect_err("slow worker exceeds deadline");
        match err {
            DrainError::DeadlineExceeded(inner) => {
                assert_eq!(inner.unresolved_worker_count, 1);
                assert_eq!(inner.completed_outcomes.len(), 2);
                assert!(inner.completed_outcomes.iter().all(|o| o.is_clean()));
            }
            other => panic!("expected DeadlineExceeded, got {other:?}"),
        }
        assert_eq!(pool.current_state_kind(), PoolStateKind::Closing);
    }

    #[tokio::test]
    async fn state_reader_from_pool_matches_pool_state() {
        let (pool, _rxs) = build_pool(1);
        let reader = pool.state_reader();
        assert_eq!(reader.kind(), PoolStateKind::Running);
        pool.close();
        assert_eq!(reader.kind(), PoolStateKind::Closing);
    }

    // ────────────────────────────────────────────────────────────
    // Round-2 P1 amendments — cardinality + lifecycle guards.
    // ────────────────────────────────────────────────────────────

    #[tokio::test]
    #[should_panic(expected = "cardinality violation")]
    async fn finalize_panics_when_handles_fewer_than_senders() {
        // Round-2 P1: mismatched fleet size is a boot-time
        // invariant break and MUST be fail-loud.
        let (tx0, _rx0) = mpsc::channel::<RestOp>(1);
        let (tx1, _rx1) = mpsc::channel::<RestOp>(1);
        let builder = WorkerPoolBuilder::new(vec![tx0, tx1]);
        let handles = vec![tokio::spawn(async { Ok::<(), FatalReason>(()) })];
        let _pool = builder.finalize(handles);
    }

    #[tokio::test]
    #[should_panic(expected = "cardinality violation")]
    async fn finalize_panics_when_handles_more_than_senders() {
        let (tx, _rx) = mpsc::channel::<RestOp>(1);
        let builder = WorkerPoolBuilder::new(vec![tx]);
        let handles = vec![
            tokio::spawn(async { Ok::<(), FatalReason>(()) }),
            tokio::spawn(async { Ok::<(), FatalReason>(()) }),
        ];
        let _pool = builder.finalize(handles);
    }

    #[tokio::test]
    async fn builder_senders_count_matches_input() {
        let (tx0, _rx0) = mpsc::channel::<RestOp>(1);
        let (tx1, _rx1) = mpsc::channel::<RestOp>(1);
        let (tx2, _rx2) = mpsc::channel::<RestOp>(1);
        let builder = WorkerPoolBuilder::new(vec![tx0, tx1, tx2]);
        assert_eq!(builder.senders_count(), 3);
    }

    #[tokio::test]
    async fn expected_worker_count_survives_finalize() {
        let (pool, _rxs) = build_pool(5);
        assert_eq!(pool.expected_worker_count(), 5);
    }

    #[tokio::test]
    async fn drain_handles_refuses_when_pool_still_running() {
        // Round-2 P1: drain_handles is a Closing-only operation.
        // From Running it MUST return NotClosing without mutating
        // any state.
        let (pool, _rxs) = build_pool(1);
        assert_eq!(pool.current_state_kind(), PoolStateKind::Running);
        let err = pool
            .drain_handles(Duration::from_secs(1))
            .await
            .expect_err("drain from Running must fail");
        assert!(matches!(
            err,
            DrainError::NotClosing {
                current: PoolStateKind::Running
            }
        ));
        assert_eq!(pool.current_state_kind(), PoolStateKind::Running);
    }

    #[tokio::test]
    async fn drain_handles_refuses_when_pool_already_closed() {
        // First drain transitions empty pool to Closed; second
        // must refuse — state stays Closed.
        let (pool, _rxs) = build_pool(0);
        pool.close();
        let _first = pool
            .drain_handles(Duration::from_secs(1))
            .await
            .expect("first drain OK");
        assert_eq!(pool.current_state_kind(), PoolStateKind::Closed);
        let err = pool
            .drain_handles(Duration::from_secs(1))
            .await
            .expect_err("second drain must fail");
        assert!(matches!(
            err,
            DrainError::NotClosing {
                current: PoolStateKind::Closed
            }
        ));
    }

    #[tokio::test(start_paused = true)]
    async fn drain_handles_repeat_after_timeout_returns_already_attempted() {
        // Round-2 P1 (the exact scenario the reviewer called out):
        // first drain times out (workers detached); second drain
        // sees an empty handle vec and — before this amendment —
        // would have falsely transitioned the pool to `Closed`.
        // Now the second call returns AlreadyAttempted and the
        // pool stays `Closing`.
        let handles: Vec<JoinHandle<Result<(), FatalReason>>> = vec![tokio::spawn(async {
            tokio::time::sleep(Duration::from_secs(60)).await;
            Ok(())
        })];
        let (tx, _rx) = mpsc::channel::<RestOp>(1);
        let pool = WorkerPoolBuilder::new(vec![tx]).finalize(handles);
        pool.close();

        // First drain — times out.
        let first = pool
            .drain_handles(Duration::from_millis(100))
            .await
            .expect_err("first drain times out");
        assert!(matches!(first, DrainError::DeadlineExceeded(_)));
        // Critical: state must remain Closing.
        assert_eq!(pool.current_state_kind(), PoolStateKind::Closing);

        // Second drain — MUST refuse (AlreadyAttempted), NOT
        // transition to Closed on empty handles.
        let second = pool
            .drain_handles(Duration::from_millis(100))
            .await
            .expect_err("second drain must be refused");
        assert!(matches!(second, DrainError::AlreadyAttempted));
        // State still Closing — invariant preserved.
        assert_eq!(pool.current_state_kind(), PoolStateKind::Closing);
    }
}
