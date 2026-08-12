// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M4-4 — unified shutdown classifier.
//!
//! The M4 shutdown contract (locked, corrected-split memo 2026-07-28):
//!
//!   * On signal → set draining flag → axum listeners graceful-shut →
//!     `runtime.close()` → `runtime.drain_handles(45 s)`.
//!   * ANY `WorkerDrainOutcome` that is not [`is_clean`] → FATAL +
//!     `process::exit(1)`.
//!   * ANY [`DrainError`] (`NotClosing`, `AlreadyAttempted`,
//!     `DeadlineExceeded`) → FATAL + `process::exit(1)`.
//!   * A clean drain returns clean; `main.rs` returns from `main()`.
//!
//! The exit code is **1** — the same code M4-2b's fatal subscriber uses.
//! Architect corrected-split explicitly refuses a new exit code for
//! shutdown deadline (v4.2.1 §1 stays in force).
//!
//! This module contains the pure classifier so tests can drive every
//! branch without spawning workers or calling `process::exit`. `main.rs`
//! wraps the classifier with the actual `eprintln!` + `std::process::
//! exit(code)` calls.
//!
//! [`is_clean`]: crate::worker_pool::WorkerDrainOutcome::is_clean

use std::time::Duration;

use crate::worker_pool::{DrainError, ShutdownDeadlineExceeded, WorkerDrainOutcome};

/// Reserved exit code for every M4 shutdown fatal branch. Matches the
/// M4-2b fatal subscriber (`SubscriberEvent::Lagged` / `Closed`) and
/// the M3b `fatal_*_invariant` primitives. Locked design v4.2.1 §1:
/// "No new exit code for shutdown deadline."
pub const SHUTDOWN_FATAL_EXIT_CODE: i32 = 1;

/// **Round-2 REDLINE P1-2**: hard upper bound the runtime accepts for
/// `RELAY_SHUTDOWN_DEADLINE_SECS`. Structurally coupled to
/// `docker-compose.yml`'s `stop_grace_period: 60s`: any accepted
/// deadline must leave the Compose default-SIGKILL comfortably
/// outside the drain window. 45 s is the locked-design value from
/// the corrected-split memo; anything above that (up to 60 s) risks
/// racing SIGKILL for the final few pool workers, above 60 s
/// unconditionally loses the race.
///
/// An operator who genuinely needs a larger budget must raise
/// Compose's `stop_grace_period` FIRST, then raise this cap in a
/// deliberate code change — no env-only escape hatch.
pub const SHUTDOWN_DEADLINE_MAX_SECS: u64 = 45;

/// **Round-2 REDLINE P1-2**: refusal shapes for
/// [`validate_shutdown_deadline_secs`]. Split so the caller can pick
/// a distinct FATAL message per case; unit-testable without ever
/// invoking `process::exit`.
#[derive(Debug, PartialEq, Eq)]
pub enum ShutdownDeadlineError {
    /// Deadline `0` — the pool would be immediately fail-closed with
    /// no time to drain in-flight work.
    Zero,
    /// Deadline above [`SHUTDOWN_DEADLINE_MAX_SECS`] — would race
    /// Compose's 60 s SIGKILL and reintroduce the pre-round-2
    /// "SIGKILL-before-contract" defect.
    AboveCap { got: u64, cap: u64 },
}

/// **Round-2 REDLINE P1-2**: pure validator for the shutdown
/// deadline env value. Returns the accepted value or a typed refusal.
/// `main.rs` wraps it with the actual FATAL emit + exit(11).
pub fn validate_shutdown_deadline_secs(n: u64) -> Result<u64, ShutdownDeadlineError> {
    if n == 0 {
        return Err(ShutdownDeadlineError::Zero);
    }
    if n > SHUTDOWN_DEADLINE_MAX_SECS {
        return Err(ShutdownDeadlineError::AboveCap {
            got: n,
            cap: SHUTDOWN_DEADLINE_MAX_SECS,
        });
    }
    Ok(n)
}

// ── Round-1 REDLINE P1-1: absolute-deadline phase-budget helpers ──────

/// Named phases of the M4-4 shutdown sequence. Used by
/// [`phase_deadline_fatal`] so a grep of production logs picks up
/// every deadline breach with a stable string.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShutdownPhase {
    /// Public + loopback listeners draining in-flight requests.
    ListenerDrain,
    /// Sweep scheduler exiting (either between ticks or mid-tick).
    SweepJoin,
    /// `WorkerPool::drain_handles` after `runtime.close()`.
    WorkerDrain,
}

impl ShutdownPhase {
    fn label(self) -> &'static str {
        match self {
            ShutdownPhase::ListenerDrain => "listener drain",
            ShutdownPhase::SweepJoin => "sweep scheduler join",
            ShutdownPhase::WorkerDrain => "worker drain",
        }
    }
}

/// Round-1 REDLINE P1-1: pure budget math. The M4-4 shutdown deadline
/// is measured from the moment the signal fires (`elapsed`) — NOT from
/// the moment any one phase starts. This helper returns whatever
/// budget remains against a total, or `Duration::ZERO` if the total
/// is exhausted.
///
/// Saturating semantics: an `elapsed` larger than `total` yields
/// `Duration::ZERO`, never underflows.
///
/// Pure — takes injected `elapsed` for deterministic tests.
pub fn remaining_budget(elapsed: Duration, total: Duration) -> Duration {
    total.saturating_sub(elapsed)
}

/// Round-1 REDLINE P1-1: build a [`ShutdownVerdict::Fatal`] for a
/// phase that consumed the whole absolute deadline. Consistent phrasing
/// so ops can grep `"shutdown deadline exceeded"` across all three
/// phases.
pub fn phase_deadline_fatal(phase: ShutdownPhase, total: Duration) -> ShutdownVerdict {
    ShutdownVerdict::Fatal {
        reason: format!(
            "{} did not complete within {}s absolute shutdown deadline",
            phase.label(),
            total.as_secs(),
        ),
        exit_code: SHUTDOWN_FATAL_EXIT_CODE,
    }
}

/// The verdict a call to `WorkerPool::drain_handles` produces, ready to
/// hand back to `main.rs`.
#[derive(Debug, PartialEq, Eq)]
pub enum ShutdownVerdict {
    /// Every worker exited via `Ok(Ok(()))`. Return from `main()` and
    /// let the process wind down normally.
    Clean {
        /// Total worker outcomes observed. Info-logged as the last
        /// line before `main` returns.
        clean_count: usize,
    },
    /// At least one worker returned dirty, OR the drain call itself
    /// refused / timed out. `main.rs` MUST emit the `FATAL:` line and
    /// call `std::process::exit(SHUTDOWN_FATAL_EXIT_CODE)`.
    Fatal {
        /// Human-readable failure string. `main.rs` prints this
        /// verbatim after a `FATAL: shutdown` prefix.
        reason: String,
        /// Exit code the caller must feed to `std::process::exit`.
        /// Always [`SHUTDOWN_FATAL_EXIT_CODE`] today; carried
        /// explicitly so a future exit-code change is one edit.
        exit_code: i32,
    },
}

/// Turn a `drain_handles` return into a [`ShutdownVerdict`]. Pure — no
/// side effects, no allocations beyond `format!` strings, no timers,
/// no I/O. Safe to unit-test every branch.
///
/// Behaviour:
///
///   * `Ok(outcomes)` with EVERY outcome `is_clean()` →
///     `ShutdownVerdict::Clean { clean_count }`.
///   * `Ok(outcomes)` with ONE OR MORE dirty →
///     `ShutdownVerdict::Fatal { reason: "worker drain returned N
///     dirty outcome(s): ...", ..}`. The reason lists at most the
///     first `MAX_DIRTY_LISTED` dirty workers so a fleet-wide fault
///     doesn't produce an unbounded FATAL line.
///   * `Err(DrainError::DeadlineExceeded(inner))` → fatal, reason
///     names the unresolved count and the completed count.
///   * `Err(DrainError::NotClosing { current })` → fatal, reason
///     names the observed state (indicates the caller forgot to
///     `runtime.close()` first — hard invariant break).
///   * `Err(DrainError::AlreadyAttempted)` → fatal, reason names
///     the double-drain (indicates a shutdown-path bug — the drain
///     already fired once and the second attempt cannot safely
///     re-close a partially-detached pool).
pub fn classify_drain_result(
    result: Result<Vec<WorkerDrainOutcome>, DrainError>,
) -> ShutdownVerdict {
    match result {
        Ok(outcomes) => classify_outcomes(outcomes),
        Err(DrainError::DeadlineExceeded(inner)) => classify_deadline(inner),
        Err(DrainError::NotClosing { current }) => ShutdownVerdict::Fatal {
            reason: format!(
                "drain refused: pool state {current:?}, expected Closing \
                 (missed runtime.close() before drain_handles)"
            ),
            exit_code: SHUTDOWN_FATAL_EXIT_CODE,
        },
        Err(DrainError::AlreadyAttempted) => ShutdownVerdict::Fatal {
            reason:
                "drain refused: a prior drain already claimed the handle vector \
                 (double-drain — pool cannot be safely re-closed)"
                    .into(),
            exit_code: SHUTDOWN_FATAL_EXIT_CODE,
        },
    }
}

/// Cap on the number of dirty worker ids listed in the fatal reason
/// string. Protects log lines from unbounded fleet-wide fault output;
/// the aggregate count is always reported.
const MAX_DIRTY_LISTED: usize = 8;

fn classify_outcomes(outcomes: Vec<WorkerDrainOutcome>) -> ShutdownVerdict {
    let total = outcomes.len();
    let dirty: Vec<&WorkerDrainOutcome> =
        outcomes.iter().filter(|o| !o.is_clean()).collect();
    if dirty.is_empty() {
        return ShutdownVerdict::Clean { clean_count: total };
    }
    let dirty_count = dirty.len();
    let sample: Vec<String> = dirty
        .iter()
        .take(MAX_DIRTY_LISTED)
        .map(|o| format!("worker_id={} result={:?}", o.worker_id, o.result))
        .collect();
    let ellipsis = if dirty_count > MAX_DIRTY_LISTED {
        format!(" (+{} more)", dirty_count - MAX_DIRTY_LISTED)
    } else {
        String::new()
    };
    ShutdownVerdict::Fatal {
        reason: format!(
            "worker drain returned {dirty_count} dirty outcome(s) of {total} total: [{sample}]{ellipsis}",
            sample = sample.join(", "),
        ),
        exit_code: SHUTDOWN_FATAL_EXIT_CODE,
    }
}

fn classify_deadline(inner: ShutdownDeadlineExceeded) -> ShutdownVerdict {
    let ShutdownDeadlineExceeded {
        unresolved_worker_count,
        completed_outcomes,
    } = inner;
    let completed = completed_outcomes.len();
    let dirty_completed = completed_outcomes.iter().filter(|o| !o.is_clean()).count();
    ShutdownVerdict::Fatal {
        reason: format!(
            "shutdown deadline exceeded: {unresolved_worker_count} worker(s) unresolved, \
             {completed} completed ({dirty_completed} dirty)"
        ),
        exit_code: SHUTDOWN_FATAL_EXIT_CODE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rest_workers::FatalReason;
    use crate::worker_pool::PoolStateKind;

    fn clean(worker_id: usize) -> WorkerDrainOutcome {
        WorkerDrainOutcome {
            worker_id,
            result: Ok(Ok(())),
        }
    }

    fn fatal_reason_outcome(worker_id: usize, reason: FatalReason) -> WorkerDrainOutcome {
        WorkerDrainOutcome {
            worker_id,
            result: Ok(Err(reason)),
        }
    }

    #[test]
    fn clean_all_returns_clean_with_full_count() {
        let outcomes = vec![clean(0), clean(1), clean(2)];
        let v = classify_drain_result(Ok(outcomes));
        assert_eq!(v, ShutdownVerdict::Clean { clean_count: 3 });
    }

    #[test]
    fn empty_ok_is_clean_with_zero() {
        let v = classify_drain_result(Ok(Vec::new()));
        assert_eq!(v, ShutdownVerdict::Clean { clean_count: 0 });
    }

    #[test]
    fn one_dirty_amongst_clean_is_fatal_with_count_and_worker_id() {
        let outcomes = vec![
            clean(0),
            fatal_reason_outcome(1, FatalReason::UnsupportedOperation { worker_id: 0, op_kind: "test" }),
            clean(2),
        ];
        let v = classify_drain_result(Ok(outcomes));
        match v {
            ShutdownVerdict::Fatal { reason, exit_code } => {
                assert_eq!(exit_code, SHUTDOWN_FATAL_EXIT_CODE);
                assert!(reason.contains("1 dirty outcome(s) of 3 total"));
                assert!(reason.contains("worker_id=1"));
            }
            other => panic!("expected Fatal, got {other:?}"),
        }
    }

    #[test]
    fn many_dirty_truncates_sample_but_names_full_count() {
        let mut outcomes = Vec::new();
        for id in 0..20usize {
            outcomes.push(fatal_reason_outcome(
                id,
                FatalReason::UnsupportedOperation { worker_id: 0, op_kind: "test" },
            ));
        }
        let v = classify_drain_result(Ok(outcomes));
        match v {
            ShutdownVerdict::Fatal { reason, .. } => {
                assert!(reason.contains("20 dirty outcome(s) of 20 total"));
                assert!(reason.contains("(+12 more)"));
            }
            other => panic!("expected Fatal, got {other:?}"),
        }
    }

    #[test]
    fn deadline_exceeded_names_unresolved_and_completed_counts() {
        let inner = ShutdownDeadlineExceeded {
            unresolved_worker_count: 7,
            completed_outcomes: vec![clean(0), clean(1), clean(2)],
        };
        let v = classify_drain_result(Err(DrainError::DeadlineExceeded(inner)));
        match v {
            ShutdownVerdict::Fatal { reason, exit_code } => {
                assert_eq!(exit_code, SHUTDOWN_FATAL_EXIT_CODE);
                assert!(reason.contains("shutdown deadline exceeded"));
                assert!(reason.contains("7 worker(s) unresolved"));
                assert!(reason.contains("3 completed"));
                assert!(reason.contains("0 dirty"));
            }
            other => panic!("expected Fatal, got {other:?}"),
        }
    }

    #[test]
    fn deadline_exceeded_counts_dirty_completed() {
        let inner = ShutdownDeadlineExceeded {
            unresolved_worker_count: 5,
            completed_outcomes: vec![
                clean(0),
                fatal_reason_outcome(1, FatalReason::UnsupportedOperation { worker_id: 0, op_kind: "test" }),
            ],
        };
        let v = classify_drain_result(Err(DrainError::DeadlineExceeded(inner)));
        match v {
            ShutdownVerdict::Fatal { reason, .. } => {
                assert!(reason.contains("2 completed (1 dirty)"));
            }
            other => panic!("expected Fatal, got {other:?}"),
        }
    }

    #[test]
    fn not_closing_is_fatal_with_state_name() {
        let v = classify_drain_result(Err(DrainError::NotClosing {
            current: PoolStateKind::Running,
        }));
        match v {
            ShutdownVerdict::Fatal { reason, .. } => {
                assert!(reason.contains("drain refused"));
                assert!(reason.contains("Running"));
                assert!(reason.contains("missed runtime.close()"));
            }
            other => panic!("expected Fatal, got {other:?}"),
        }
    }

    #[test]
    fn already_attempted_is_fatal_with_double_drain_note() {
        let v = classify_drain_result(Err(DrainError::AlreadyAttempted));
        match v {
            ShutdownVerdict::Fatal { reason, exit_code } => {
                assert_eq!(exit_code, SHUTDOWN_FATAL_EXIT_CODE);
                assert!(reason.contains("double-drain"));
            }
            other => panic!("expected Fatal, got {other:?}"),
        }
    }

    // ── Round-1 REDLINE P1-1: absolute-deadline helpers ──────────────

    #[test]
    fn remaining_budget_returns_positive_when_elapsed_below_total() {
        let r = remaining_budget(Duration::from_secs(20), Duration::from_secs(45));
        assert_eq!(r, Duration::from_secs(25));
    }

    #[test]
    fn remaining_budget_returns_zero_when_elapsed_equals_total() {
        let r = remaining_budget(Duration::from_secs(45), Duration::from_secs(45));
        assert_eq!(r, Duration::ZERO);
    }

    #[test]
    fn remaining_budget_saturates_when_elapsed_exceeds_total() {
        let r = remaining_budget(Duration::from_secs(60), Duration::from_secs(45));
        assert_eq!(r, Duration::ZERO, "must saturate — never underflow");
    }

    #[test]
    fn phase_deadline_fatal_listener_drain_names_phase_and_seconds() {
        let v = phase_deadline_fatal(ShutdownPhase::ListenerDrain, Duration::from_secs(45));
        match v {
            ShutdownVerdict::Fatal { reason, exit_code } => {
                assert_eq!(exit_code, SHUTDOWN_FATAL_EXIT_CODE);
                assert!(reason.contains("listener drain"));
                assert!(reason.contains("45s absolute shutdown deadline"));
            }
            other => panic!("expected Fatal, got {other:?}"),
        }
    }

    #[test]
    fn phase_deadline_fatal_sweep_join_names_phase() {
        let v = phase_deadline_fatal(ShutdownPhase::SweepJoin, Duration::from_secs(45));
        match v {
            ShutdownVerdict::Fatal { reason, .. } => {
                assert!(reason.contains("sweep scheduler join"));
            }
            other => panic!("expected Fatal, got {other:?}"),
        }
    }

    #[test]
    fn phase_deadline_fatal_worker_drain_names_phase() {
        let v = phase_deadline_fatal(ShutdownPhase::WorkerDrain, Duration::from_secs(45));
        match v {
            ShutdownVerdict::Fatal { reason, .. } => {
                assert!(reason.contains("worker drain"));
            }
            other => panic!("expected Fatal, got {other:?}"),
        }
    }

    // ── Round-2 REDLINE P1-2: shutdown-deadline validator boundaries ─

    #[test]
    fn validate_shutdown_deadline_rejects_zero() {
        assert_eq!(
            validate_shutdown_deadline_secs(0),
            Err(ShutdownDeadlineError::Zero)
        );
    }

    #[test]
    fn validate_shutdown_deadline_accepts_one() {
        assert_eq!(validate_shutdown_deadline_secs(1), Ok(1));
    }

    #[test]
    fn validate_shutdown_deadline_accepts_default_45() {
        assert_eq!(validate_shutdown_deadline_secs(45), Ok(45));
        assert_eq!(SHUTDOWN_DEADLINE_MAX_SECS, 45);
    }

    #[test]
    fn validate_shutdown_deadline_rejects_46() {
        assert_eq!(
            validate_shutdown_deadline_secs(46),
            Err(ShutdownDeadlineError::AboveCap { got: 46, cap: 45 })
        );
    }

    #[test]
    fn validate_shutdown_deadline_rejects_60_the_compose_grace() {
        // Boundary the review flagged: env == compose grace = SIGKILL
        // races the drain final tick. Refuse.
        assert_eq!(
            validate_shutdown_deadline_secs(60),
            Err(ShutdownDeadlineError::AboveCap { got: 60, cap: 45 })
        );
    }

    #[test]
    fn validate_shutdown_deadline_rejects_arbitrary_large_value() {
        // Whatever a hostile operator sets, we cap at the same
        // structural boundary.
        assert_eq!(
            validate_shutdown_deadline_secs(u64::MAX),
            Err(ShutdownDeadlineError::AboveCap {
                got: u64::MAX,
                cap: 45,
            })
        );
    }
}
