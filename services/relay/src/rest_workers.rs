// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 M1 — shard-worker fundamentals.
//!
//! Locked design: `mini-lock-draft-v4.md` §2 + `v4.1-amendments.md`
//! §1 V-P0-1..V-P0-8 + `v4.2-amendments.md` §5 B-5 + §6 B-6 +
//! `v4.2.1-amendments.md` §5 canonical lock invariant.
//!
//! This module ships the type surface and pure helpers only. Wiring
//! into HTTP handlers (M3a/b), on-disk persistence (M2), health
//! listener (M4), and failpoint tests (M5) lands in later milestones.
//! Nothing in this file is invoked from `main.rs`, `rest_fallback.rs`,
//! or `routes.rs` at M1; the existing in-memory HashMap path remains
//! authoritative until M3.

use std::hash::{Hash, Hasher};
use std::sync::Arc;

use siphasher::sip::SipHasher13;
use tokio::sync::{broadcast, mpsc, oneshot};

use crate::error::RelayError;
use crate::rest_fallback::RestEnvelope;

/// Fixed shard-worker fleet size (locked design v4 §2).
///
/// Every recipient is served by exactly one worker across the entire
/// process lifetime — `worker_id = worker_for(recipient) % REST_WORKER_COUNT`.
/// The value is a locked constant; changing it would resharded every
/// recipient mid-flight, which the design explicitly forbids.
pub const REST_WORKER_COUNT: usize = 64;

/// Bounded per-worker mpsc buffer (locked design v4 §2).
///
/// The buffer is small on purpose: a full channel is a legitimate
/// backpressure signal that HTTP handlers surface as `HTTP 503
/// error="worker_backpressure"` (v4.1 §3 V-M1). Unbounded queues
/// would let a slow consumer accumulate arbitrary memory.
pub const REST_WORKER_MPSC_BUFFER: usize = 128;

/// Operations a shard-worker executes on behalf of a recipient's queue.
///
/// Every variant carries the `recipient` explicitly (v4.1 §1 V-P0-2):
/// a worker handles many recipients on the same channel, so the
/// dispatch loop needs to know which recipient owns the operation
/// before running the shard-ownership invariant check (v4.2 §6 B-6).
///
/// `reply` is a `oneshot` so the awaiting HTTP handler learns the
/// outcome. Dropping the receiver before the worker fires the reply
/// is not a bug — the handler may have been cancelled — but the
/// worker still commits state to disk and RAM in that case.
pub enum RestOp {
    /// Send an envelope on behalf of `recipient`. Result is the
    /// server-assigned `seq` or the appropriate `RelayError`.
    Send {
        recipient: String,
        env: RestEnvelope,
        reply: oneshot::Sender<Result<u64, RelayError>>,
    },
    /// Acknowledge delivery of `envelope_id` from `recipient`'s queue.
    /// Locked design v4.1 §1 V-P0-4: this replaces the on-disk
    /// `PersistedRecord::Queued` with `PersistedRecord::AckedTombstone`
    /// via atomic tempfile+rename; not a plain unlink.
    Ack {
        recipient: String,
        envelope_id: String,
        reply: oneshot::Sender<Result<(), RelayError>>,
    },
    /// TTL-sweep the recipient's queue: unlink expired `Queued`
    /// records and any `AckedTombstone` records past `dedup_until`.
    /// Reply is the count of unlinked records (both kinds combined).
    Sweep {
        recipient: String,
        reply: oneshot::Sender<usize>,
    },
}

impl RestOp {
    /// Return the recipient this operation is bound to. Used by the
    /// dispatch loop's shard-ownership invariant check (M3a).
    #[inline]
    pub fn recipient(&self) -> &str {
        match self {
            RestOp::Send { recipient, .. }
            | RestOp::Ack { recipient, .. }
            | RestOp::Sweep { recipient, .. } => recipient,
        }
    }
}

/// Reasons a worker may terminate that MUST page (locked design v4 §4
/// + v4.1 §1 V-P0-8 + v4.2 §6 B-6).
///
/// Clean shutdown via observed `rx.recv() == None` while the pool is
/// `Closing`/`Closed` is NOT a fatal reason — see
/// [`crate::worker_pool::PoolState`].
#[derive(Debug, Clone)]
pub enum FatalReason {
    /// A worker `catch_unwind` caught a panic. Payload is captured
    /// via `format!("{:?}", panic_payload)`.
    WorkerPanic {
        worker_id: u8,
        panic_payload: String,
    },
    /// A worker's mpsc receiver returned `None` while
    /// [`crate::worker_pool::PoolState`] was still `Running`. Some
    /// other code path dropped a sender out of band — invariant
    /// violation.
    WorkerUnexpectedExit { worker_id: u8 },
    /// A worker received a `RestOp` whose recipient hashed to a
    /// different worker id under `worker_for` — the dispatch table
    /// disagrees with the router. Runtime-enforced (v4.2 §6 B-6);
    /// release build cannot silently continue.
    ShardRoutingInvariantViolation {
        worker_id: u8,
        expected_worker: usize,
    },
}

/// Boot-random keys backing [`worker_for`].
///
/// Locked design v4.1 §2 answer to Q1: generated via `rand::random`
/// at process start, immutable for the process lifetime. Boot-random
/// closes the shard-flood attack surface without coupling shard
/// assignment to a key-rotation event.
#[derive(Debug, Clone, Copy)]
pub struct WorkerHashKey {
    pub k0: u64,
    pub k1: u64,
}

impl WorkerHashKey {
    /// Draw a fresh pair from the process CSPRNG. Called exactly once
    /// during boot (locked design v4.1 §2 Q1).
    pub fn from_process_rng() -> Self {
        Self {
            k0: rand::random::<u64>(),
            k1: rand::random::<u64>(),
        }
    }

    /// Construct from explicit values. Used by unit tests to pin
    /// deterministic distributions; MUST NOT be used from production
    /// boot paths (which own [`WorkerHashKey::from_process_rng`]).
    pub const fn from_raw(k0: u64, k1: u64) -> Self {
        Self { k0, k1 }
    }
}

/// Pure shard router (locked design v4 §2).
///
/// Panics on an empty recipient: an empty recipient at this point
/// indicates an ingress-hardening bypass upstream (PR-0 A-6 rejects
/// empty at parse). The panic is caught by the worker's
/// `catch_unwind` and surfaces as a `WorkerPanic` fatal.
#[inline]
pub fn worker_for(recipient: &str, key: WorkerHashKey) -> usize {
    assert!(
        !recipient.is_empty(),
        "worker_for called with empty recipient — ingress-hardening bypass",
    );
    let mut hasher = SipHasher13::new_with_keys(key.k0, key.k1);
    recipient.hash(&mut hasher);
    (hasher.finish() as usize) % REST_WORKER_COUNT
}

/// A worker's view of shared state (locked design v4 §2).
///
/// Deliberately narrower than `Arc<AppState>` to break the potential
/// Arc-cycle where a worker held a sender to itself via `AppState`.
/// M1 ships the type with a minimal field set that later milestones
/// extend without a source-level break; unused fields stay
/// `#[allow(dead_code)]` at M1 because no worker loop dispatches yet.
///
/// Field additions expected in later milestones:
/// - M2: `Arc<Persistence>` handle for on-disk `PersistedRecord` writes.
/// - M2: `Arc<IdempotencyDedup>` (in-memory tombstone table).
/// - M3: shared references to `rest_store` and WS `store`.
#[allow(dead_code)]
pub struct ActorContext {
    pub worker_id: u8,
    pub worker_hash_key: WorkerHashKey,
    /// Global capacity gate — see [`crate::capacity_ledger`].
    pub capacity: Arc<crate::capacity_ledger::GlobalCapacityGate>,
    /// Fatal broadcast channel — populated at boot; every worker
    /// clones a sender at spawn time.
    pub fatal: broadcast::Sender<FatalReason>,
    /// Read-only pool-state accessor (round-1 P1 #3). The worker
    /// consults this on `rx.recv() == None` to classify its exit
    /// as planned (`Closing`/`Closed`) vs unexpected (`Running`).
    /// Handed to the worker at spawn time via
    /// [`crate::worker_pool::WorkerPoolBuilder::state_reader`].
    pub pool_state_reader: crate::worker_pool::PoolStateReader,
}

/// Placeholder worker loop for M1.
///
/// The real dispatch body (`Send`/`Ack`/`Sweep` handling, panic
/// classification via [`FatalReason`], canonical two-store lock
/// order per v4.2 §6 B-6) lands in M3. At M1 this loop simply
/// drains the channel to termination without doing any state
/// mutation; it is not spawned from anywhere in `main.rs` yet.
///
/// The loop is written so that the M3 body can slot in without
/// changing its outer shape — a `while let Some(op) = rx.recv()`
/// scaffold with the shard-ownership check placed at the exact
/// site the M3 code will use.
pub async fn worker_loop(
    ctx: ActorContext,
    mut rx: mpsc::Receiver<RestOp>,
) -> Result<(), FatalReason> {
    while let Some(op) = rx.recv().await {
        // Shard-ownership invariant (v4.2 §6 B-6). Runtime-enforced
        // so a future dispatch bug that sent a mis-routed op does
        // not silently corrupt cross-recipient ordering.
        let expected = worker_for(op.recipient(), ctx.worker_hash_key);
        if expected != usize::from(ctx.worker_id) {
            let reason = FatalReason::ShardRoutingInvariantViolation {
                worker_id: ctx.worker_id,
                expected_worker: expected,
            };
            let _ = ctx.fatal.send(reason.clone());
            return Err(reason);
        }
        // M3 will replace this drop with real Send/Ack/Sweep dispatch.
        // At M1 we intentionally consume the op without processing so
        // that any future test that accidentally invokes this loop
        // does not block forever waiting for a reply.
        drop(op);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn worker_for_is_deterministic_under_fixed_key() {
        let key = WorkerHashKey::from_raw(0x0123_4567_89ab_cdef, 0xfedc_ba98_7654_3210);
        let a = worker_for("recipient-a", key);
        let b = worker_for("recipient-a", key);
        assert_eq!(a, b);
        assert!(a < REST_WORKER_COUNT);
    }

    #[test]
    fn worker_for_diverges_across_recipients() {
        let key = WorkerHashKey::from_raw(1, 2);
        let mut seen = std::collections::HashSet::new();
        for i in 0..10_000_u32 {
            let recipient = format!("phantom-recipient-{i:08x}");
            seen.insert(worker_for(&recipient, key));
        }
        // With 10k distinct recipients across 64 buckets we should
        // hit every bucket at least once with overwhelming
        // probability (Pigeonhole × birthday); refuse silent
        // regressions where the hash degenerates to a constant.
        assert_eq!(seen.len(), REST_WORKER_COUNT);
    }

    #[test]
    fn worker_for_bucket_distribution_within_2x_of_expected() {
        let key = WorkerHashKey::from_raw(0x1000, 0x2000);
        let mut counts = [0_usize; REST_WORKER_COUNT];
        let sample = 64_000_usize;
        for i in 0..sample {
            let recipient = format!("recipient-{i}");
            counts[worker_for(&recipient, key)] += 1;
        }
        let expected = sample / REST_WORKER_COUNT;
        for (bucket, count) in counts.iter().enumerate() {
            // 2x band is generous defence-in-depth — SipHash-1-3 in
            // practice sits well under 1.5x.
            assert!(
                *count > expected / 2 && *count < expected * 2,
                "bucket {bucket} count {count} outside {expected}/2..{expected}*2 band",
            );
        }
    }

    #[test]
    #[should_panic(expected = "empty recipient")]
    fn worker_for_panics_on_empty_recipient() {
        let key = WorkerHashKey::from_raw(0, 0);
        let _ = worker_for("", key);
    }

    #[test]
    fn worker_hash_key_from_process_rng_yields_nonzero_pair() {
        let key = WorkerHashKey::from_process_rng();
        // 128 bits of entropy — probability of an all-zero pair is
        // 2^-128, which we treat as impossible in practice.
        assert!(key.k0 != 0 || key.k1 != 0);
    }

    #[test]
    fn rest_op_recipient_accessor_matches_ack_variant() {
        let (reply_tx_ack, _reply_rx_ack) = tokio::sync::oneshot::channel();
        let ack_op = RestOp::Ack {
            recipient: "ack-target".into(),
            envelope_id: "envelope-01".into(),
            reply: reply_tx_ack,
        };
        assert_eq!(ack_op.recipient(), "ack-target");
    }

    #[test]
    fn rest_op_recipient_accessor_matches_sweep_variant() {
        let (reply_tx_sweep, _reply_rx_sweep) = tokio::sync::oneshot::channel();
        let sweep_op = RestOp::Sweep {
            recipient: "sweep-target".into(),
            reply: reply_tx_sweep,
        };
        assert_eq!(sweep_op.recipient(), "sweep-target");
    }

    // `RestOp::Send` construction requires a full `RestEnvelope`
    // value; that constructor lives across `rest_fallback.rs`
    // routes and pulls in seq/MAC state that M1 does not initialise.
    // The Send-variant accessor path is covered indirectly by the
    // pattern match in `RestOp::recipient` above (all three variants
    // share the same match arm).

    #[test]
    fn constants_match_locked_design() {
        // Defence against an accidental edit that would silently
        // reshard the fleet mid-lifecycle. Locked at v4 §2.
        assert_eq!(REST_WORKER_COUNT, 64);
        assert_eq!(REST_WORKER_MPSC_BUFFER, 128);
    }
}
