// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-QUEUE-DURABILITY PR-2 — shard-worker types + dispatch.
//!
//! **M1** shipped the type surface: [`RestOp`], [`FatalReason`],
//! [`WorkerHashKey`], [`worker_for`], [`ActorContext`] skeleton.
//!
//! **M3a** ships the full [`worker_loop`] dispatch body PLUS
//! the send transaction ([`do_send`], private to this module) as
//! a free function. Ack/Sweep dispatch is deliberately routed to
//! a fatal exit (`FatalReason::UnsupportedOperation`) until M3b
//! ships their real implementations — the pool would otherwise
//! silently accept ops it cannot serve.
//!
//! **M3a round-3 amendments** (this revision):
//! - F1: `ActorContext` / `worker_loop` / `next_seq` are now
//!   truly private. The public entry is
//!   [`spawn_worker_runtime`], a narrow factory that constructs
//!   the fleet internally and returns a public [`WorkerRuntime`]
//!   handle. `main.rs` (a separate binary crate) can call it;
//!   the boot-generation cap and every per-worker invariant is
//!   enforced inside the factory, so no external caller can
//!   stand up a context that bypasses them. A cross-crate
//!   integration test exercises the public shape.
//! - F2: Documentation for [`SendError::UnsealedSenderRejected`]
//!   no longer mentions any "pre-PR-2 direct-store path" —
//!   bypassing the actor is forbidden and M6 will grep-gate it.
//!   The only supported legacy options are ingress rejection or
//!   widening [`PersistedRecord`] to carry `from` (out of PR-2
//!   scope).
//! - F3: Out-of-range boot generation surfaces via a dedicated
//!   [`FatalReason::SeqNamespaceInvalid`] carrying the actual
//!   generation, rather than aliasing the counter-saturation
//!   fatal via a `u64::MAX` sentinel. A misinitialised counter at
//!   `u64::MAX` can no longer collide with the discriminator.
//! - F4/F5: The send path uses
//!   [`crate::persistence::SerializedRecord`] — an opaque
//!   canonical-byte carrier that only that module can construct.
//!   `write_record_bytes` is `pub(crate)` and takes only the
//!   opaque type, so an arbitrary buffer cannot reach disk. The
//!   send test now asserts `ledger.active_bytes == disk_len`, a
//!   structural byte-identity check that round-2's double
//!   `serde_json::to_vec` would have failed against.
//! - F6: The module-wide `#![allow(dead_code)]` is removed; every
//!   remaining item is reachable from the public entry point.
//!
//! **M3a round-1 amendments** (prior revision):
//! - F1: RAM stores hold production [`crate::envelope::Envelope`]
//!   / [`crate::rest_fallback::RestEnvelope`] — no shadow types
//!   that M4 wiring would have to reconcile.
//! - F2: Seq assembly caps `boot_generation` at 24 bits, refuses
//!   past the ceiling at boot ([`queue_meta::MAX_BOOT_GENERATION`]),
//!   makes counter saturation sticky via `fetch_update`, and
//!   surfaces the exhausted-namespace as a fatal
//!   ([`FatalReason::SeqSpaceExhausted`]).
//! - F3: Shard-ownership check runs INSIDE `catch_unwind` so a
//!   malformed op (`recipient=""`) still surfaces as
//!   [`FatalReason::WorkerPanic`] rather than tearing down the
//!   task without a broadcast.
//! - F4: RAM footprint estimator is conservative — two RAM
//!   projections + per-record struct overhead.
//! - F5: Runtime `max_envelopes_per_recipient` cap re-enforced in
//!   the worker (typed refusal in [`SendError`]).
//! - F6: [`do_send`] is module-private — the ONLY entry path is
//!   `RestOp::Send → worker_loop → dispatch_one → do_send`, so
//!   actor serialization is enforced structurally.
//!
//! Wiring into `AppState`, handlers, and `main.rs` remains
//! deferred to **M4** (per reviewer's scope split); nothing in
//! this module is invoked from the running relay yet.
//!
//! Locked design references:
//! - `mini-lock-draft-v4.md` §2 (shard pool + routing)
//! - `mini-lock-draft-v4.md` §5 (send flow shape)
//! - `mini-lock-draft-v4.md` §13 Q1 (seq = generation<<40 | counter)
//! - `v4.1-amendments.md` §1 V-P0-4 (single-file PersistedRecord)
//! - `v4.1-amendments.md` §1 V-P0-5 (canonical body_hash)
//! - `v4.1-amendments.md` §1 V-P0-7 (canonical two-store lock)
//! - `v4.1-amendments.md` §1 V-P0-8 (unexpected worker exit fatal)
//! - `v4.2-amendments.md` §5 B-5 (worker pool state machine)
//! - `v4.2-amendments.md` §6 B-6 (runtime shard-ownership check)
//! - `v4.2.1-amendments.md` §5 (canonical lock invariant text)

use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use siphasher::sip::SipHasher13;
use tokio::sync::{broadcast, mpsc, oneshot, RwLock};

use crate::body_hash::compute_body_hash_hex;
use crate::capacity_ledger::{
    CapacityCaps, CapacityError, GlobalCapacityGate, RecordFootprint, RecordKind,
};
use crate::envelope::Envelope;
use crate::boot_loader::{BootLoaderResult, LoadedRecord};
use crate::persistence::{
    self, record_path, PersistedRecord, RecordReadError, SerializeRecordError, SerializedRecord,
    MAX_RECORD_BYTES, RECORD_VERSION,
};
use crate::rest_fallback::RestEnvelope;
use crate::seq_mac::{seq_mac_to_hex, SeqMacComputeError, SeqMacRootKey};
use crate::worker_pool::{PoolStateKind, PoolStateReader, WorkerPool, WorkerPoolBuilder};

// ─── Fleet constants (locked design v4 §2) ─────────────────────────────

/// Fixed shard-worker fleet size (locked design v4 §2).
pub const REST_WORKER_COUNT: usize = 64;

/// Bounded per-worker mpsc buffer (locked design v4 §2).
pub const REST_WORKER_MPSC_BUFFER: usize = 128;

// ─── Seq assembly constants (locked v4 §13 Q1 + F2 amendment) ─────────

/// Counter half of the seq assembly (locked v4 §13 Q1). Values
/// `1..COUNTER_LIMIT` are issuable; counter `0` is reserved because
/// REST clients use `since_seq=0` as the initial cursor and polling
/// returns only records whose `seq > since_seq`.
pub const SEQ_COUNTER_LIMIT: u64 = 1u64 << 40;

/// First issuable counter value. Reserving zero prevents the first send
/// of a fresh-install generation (`boot_generation == 0`) from becoming
/// permanently invisible to `/relay/poll?since_seq=0`.
const SEQ_COUNTER_INITIAL: u64 = 1;

/// Conservative additive per-projection struct overhead used by
/// the RAM estimator (F4 amendment). Covers `Vec`/`String` headers,
/// per-recipient `HashMap` node overhead amortised over the queue
/// depth, and small heap allocations we do not measure directly.
/// The value is intentionally generous so a benchmark refinement
/// under M4 can only lower it.
const RAM_STRUCT_OVERHEAD_BYTES: u64 = 512;

// ─── Send DTOs ─────────────────────────────────────────────────────────

/// Operator input to a send operation. M4 handlers construct this
/// from the incoming HTTP request; do_send never sees the raw HTTP
/// shape.
#[derive(Debug, Clone)]
pub struct SendCandidate {
    /// Client-supplied envelope id (already validated by PR-0 M-1
    /// ingress hardening upstream).
    pub id: String,
    /// Sealed-sender blob — opaque to the relay.
    pub sealed_sender: String,
    /// Ciphertext payload — opaque to the relay.
    pub payload: String,
    /// Client's 60s-quantised send timestamp (ms since epoch).
    pub sequence_ts: u64,
    /// Server-computed absolute expiry (unix seconds). M4 handlers
    /// derive this from `RelayConfig.envelope_ttl_secs + now`.
    pub expires_at: u64,
}

/// **PR-2 M4-2b round-3 REDLINE**: typed disposition for a
/// `do_send` success. Splits the round-2 boolean
/// `idempotent_replay` into three cases so handlers can pick
/// distinct delivery paths for each:
///
///   * `Fresh` — brand-new record. Handler MUST fire
///     notify + live-delivery + push.
///   * `QueuedReplay` — same `(recipient, envelope_id, body_hash)`
///     was already Queued (still awaiting ack). Handler MUST
///     re-fire notify + live-delivery + push best-effort: the
///     runtime already persisted, but a prior handler timeout
///     or dropped-oneshot means the recipient may never have
///     been notified. Return HTTP 200 (replay status).
///   * `TombstoneReplay` — same id was already Acked. Do NOT
///     re-fire delivery — the recipient has confirmed receipt.
///     Return HTTP 200 (replay status) so the sender's
///     idempotency contract is satisfied.
///
/// The pre-M4 shape treated both replay cases identically
/// (never re-delivering) which created a liveness hole for
/// the queued case: a worker that persisted + missed the
/// 5s reply deadline left the message durable but the
/// recipient's live WS session unaware and offline push
/// unfired. `QueuedReplay` closes that hole while still
/// preserving replay-idempotence at the disk layer (the
/// runtime returns the prior `seq`, no double-persist).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SendDisposition {
    Fresh,
    QueuedReplay,
    TombstoneReplay,
}

/// Success outcome of [`do_send`]. `disposition` carries the
/// typed intent; `idempotent_replay` is derived
/// (`disposition != Fresh`) and kept as a public field so
/// pre-round-3 callers don't need to change.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SendOutcome {
    pub seq: u64,
    pub idempotent_replay: bool,
    pub disposition: SendDisposition,
}

/// Domain-layer error surface of [`do_send`]. M4 handlers map each
/// variant to the appropriate HTTP status:
///   * `CapacityExceeded` → 429
///   * `PerRecipientQueueFull` → 429
///   * `EnvelopeIdReusedWithDivergentBody` → 409
///   * `ShardMismatch` / `SeqSaturation` / `Internal` /
///     `Persistence` (I/O) → 500 (+ page)
///   * `Serialize` → 400 (client sent oversize payload)
#[derive(Debug)]
pub enum SendError {
    /// The op reached a worker whose shard does not own the
    /// recipient. Locked v4.2 §6 B-6 — runtime-enforced invariant.
    /// Worker broadcasts fatal in the same code path.
    ShardMismatch {
        expected_worker: usize,
        /// **M3b-2a nit**: widened from `u8` to `usize` for
        /// symmetry with `expected_worker`. The narrower type
        /// forced call sites to cast between `ctx.worker_id: u8`
        /// and `expected_worker: usize` — the two now share a
        /// single width.
        actual_worker: usize,
    },
    /// [`GlobalCapacityGate::reserve_send`] refused.
    CapacityExceeded(CapacityError),
    /// Recipient's queue already at `max_envelopes_per_recipient`
    /// (F5 amendment). M4 maps to HTTP 429.
    PerRecipientQueueFull { observed: usize, cap: usize },
    /// On-disk persistence failed. Reservation auto-rollback via
    /// `CapacityReservation::Drop`.
    Persistence(std::io::Error),
    /// Serialised record size exceeds [`MAX_RECORD_BYTES`] or the
    /// serialiser itself refused. Reservation auto-rollback.
    Serialize {
        observed_bytes: u64,
        cap_bytes: u64,
        source: String,
    },
    /// Same `envelope_id` already present in `rest_store` with a
    /// DIFFERENT canonical body. Locked v4.1 V-P0-4.
    EnvelopeIdReusedWithDivergentBody {
        existing_body_hash: String,
        incoming_body_hash: String,
    },
    /// Per-boot seq counter exhausted (F2 amendment — sticky).
    /// [`worker_loop`] translates this into a
    /// [`FatalReason::SeqSpaceExhausted`] broadcast and fail-closes
    /// so the operator restarts (a fresh generation reclaims the
    /// 40-bit space).
    SeqSaturation { counter: u64 },
    /// **Round-2 F4**: `ActorContext.boot_generation` sits at or
    /// above [`queue_meta::MAX_BOOT_GENERATION`], so `<< 40` would
    /// alias a lower generation's seq namespace. Escalated to
    /// [`FatalReason::SeqSpaceExhausted`] like a counter saturation
    /// — the process cannot make forward progress.
    BootGenerationOutOfRange { generation: u32 },
    /// The per-identity verify key refused to MAC (envelope id
    /// too long).
    SeqMacCompute(SeqMacComputeError),
    /// **Round-2 F3 + round-3 F2**: the send candidate carries
    /// an empty `sealed_sender`. Phantom's persistence contract
    /// is sealed-sender-only (ADR-006, PR-2 locked design v4 §5)
    /// — the [`PersistedRecord::Queued`] schema has no `from`
    /// column, and the round-3 review closed the "pre-PR-2
    /// direct-store fallback" loophole because bypassing the
    /// actor would skip worker serialization, persistence, and
    /// the capacity ledger (M6 grep-gate will additionally
    /// enforce that stores mutate ONLY inside
    /// `rest_workers.rs`). The only supported legacy paths are
    /// (a) reject at PR-0 ingress before ops reach this actor,
    /// or (b) widen `PersistedRecord` to carry `from` — the
    /// latter is out of PR-2 scope. Handlers map this variant to
    /// HTTP 400.
    UnsealedSenderRejected,
    /// Internal invariant break — logic bug or hostile mutation of
    /// AppState behind our back. `Display` carries the specifics.
    Internal(String),
}

impl std::fmt::Display for SendError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SendError::ShardMismatch {
                expected_worker,
                actual_worker,
            } => write!(
                f,
                "shard mismatch: op routed to worker {actual_worker}, expected {expected_worker}"
            ),
            SendError::CapacityExceeded(cap) => write!(f, "capacity exceeded: {cap}"),
            SendError::PerRecipientQueueFull { observed, cap } => write!(
                f,
                "per-recipient queue full: observed {observed} >= cap {cap}"
            ),
            SendError::Persistence(e) => write!(f, "persistence io error: {e}"),
            SendError::Serialize {
                observed_bytes,
                cap_bytes,
                source,
            } => write!(
                f,
                "serialised record size {observed_bytes} exceeds cap {cap_bytes} (source: {source})"
            ),
            SendError::EnvelopeIdReusedWithDivergentBody {
                existing_body_hash,
                incoming_body_hash,
            } => write!(
                f,
                "envelope_id reused with divergent body_hash: existing={existing_body_hash}, incoming={incoming_body_hash}"
            ),
            SendError::SeqSaturation { counter } => {
                write!(f, "per-boot seq counter saturated at {counter} (limit 2^40)")
            }
            SendError::BootGenerationOutOfRange { generation } => write!(
                f,
                "boot_generation {generation} exceeds the 24-bit seq namespace cap 2^24-1 (would collide via <<40)"
            ),
            SendError::UnsealedSenderRejected => write!(
                f,
                "sealed_sender is empty — PR-2 pipeline is sealed-sender-only (ADR-006). Reject at PR-0 ingress before ops reach this actor, or widen PersistedRecord to carry `from` (out of PR-2 scope); no direct-store fallback exists."
            ),
            SendError::SeqMacCompute(e) => write!(f, "seq_mac compute failed: {e:?}"),
            SendError::Internal(s) => write!(f, "internal invariant break: {s}"),
        }
    }
}

impl std::error::Error for SendError {}

// ─── RestOp ────────────────────────────────────────────────────────────

/// Operations a shard-worker executes on behalf of a recipient's queue.
///
/// M3a wires `Send` end-to-end. `Ack`/`Sweep` variants exist so M4
/// wiring code can express intent, but the dispatch loop routes
/// them to a typed fatal ([`FatalReason::UnsupportedOperation`])
/// until M3b ships their implementations.
pub enum RestOp {
    /// Send a new envelope on behalf of `recipient`.
    Send {
        recipient: String,
        candidate: SendCandidate,
        reply: oneshot::Sender<Result<SendOutcome, SendError>>,
    },
    /// Acknowledge delivery of `envelope_id` from `recipient`'s
    /// queue. **M3a-scope**: routed to fatal exit; real body M3b.
    Ack {
        recipient: String,
        envelope_id: String,
        reply: oneshot::Sender<Result<AckOutcome, AckError>>,
    },
    /// TTL-sweep the recipient's queue. **M3a-scope**: routed to
    /// fatal exit; real body M3b.
    Sweep {
        recipient: String,
        reply: oneshot::Sender<Result<SweepOutcome, SweepError>>,
    },
}

impl RestOp {
    /// Return the recipient this operation is bound to.
    #[inline]
    pub fn recipient(&self) -> &str {
        match self {
            RestOp::Send { recipient, .. }
            | RestOp::Ack { recipient, .. }
            | RestOp::Sweep { recipient, .. } => recipient,
        }
    }
}

/// Placeholder types kept for the RestOp shape so M4 handlers can
/// wire against a stable enum. Real bodies land in M3b.
/// Success outcome of `do_ack` (M3b-2b).
///
/// The three-state ACK matrix:
///   * [`AckOutcome::Acked`] — a `Queued` record transitioned to
///     `AckedTombstone`; disk, both RAM stores, active index,
///     tombstone dedup, and the capacity ledger are all in
///     sync.
///   * [`AckOutcome::Idempotent`] — the envelope was already
///     tombstoned by a prior ack; the previously-assigned seq
///     is returned so the caller sees identical replies for
///     retries.
///   * [`AckOutcome::NotFound`] — no active or tombstoned
///     record for `(recipient, envelope_id)`; there is nothing
///     to ack.
///
/// M3b-2a defines the shape; M3b-2b's `do_ack` populates it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AckOutcome {
    Acked { seq: u64 },
    Idempotent { seq: u64 },
    NotFound,
}

/// Domain-layer error surface of `do_ack`.
///
/// **M3b-2a**: shape locked; **M3b-2b** wires the do_ack
/// transaction that surfaces these variants. Persistence /
/// serialize errors stay TYPED — reviewer's corrective #1 says
/// they may only be returned BEFORE the durable disk commit;
/// any error observed after `write_record_bytes` has succeeded
/// is a fail-stop invariant, not an `AckError`.
#[derive(Debug)]
pub enum AckError {
    /// Runtime shard-ownership check failed (defense-in-depth
    /// mirror of [`SendError::ShardMismatch`]).
    ShardMismatch {
        expected_worker: usize,
        actual_worker: usize,
    },
    /// Atomic disk transition to tombstone failed BEFORE the
    /// rename committed. Reservation / ledger untouched.
    Persistence(std::io::Error),
    /// Tombstone serialisation refused the record shape
    /// (should be unreachable — tombstone JSON is small and
    /// well-bounded). Kept for symmetry with
    /// [`SendError::Serialize`].
    Serialize {
        observed_bytes: u64,
        cap_bytes: u64,
        source: String,
    },
    /// **Round-6 F2** clock guard: `SystemTime::now()` returned
    /// a pre-epoch value. `do_ack` refuses rather than compute
    /// `dedup_until` from an unusable "now".
    SystemClockBeforeEpoch,
    /// **Reviewer corrective #2**: `now + horizon_secs` would
    /// overflow `u64`. `checked_add` catches this BEFORE the
    /// disk write.
    DedupHorizonOverflow { now: u64, horizon_secs: u32 },
    /// Internal invariant break surfaced BEFORE any disk
    /// commit. Any post-commit invariant is fail-stop
    /// (`fatal_ack_invariant`), not this variant.
    Internal(String),
}

impl std::fmt::Display for AckError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AckError::ShardMismatch {
                expected_worker,
                actual_worker,
            } => write!(
                f,
                "ack shard mismatch: op routed to worker {actual_worker}, expected {expected_worker}"
            ),
            AckError::Persistence(e) => write!(f, "ack persistence io error: {e}"),
            AckError::Serialize {
                observed_bytes,
                cap_bytes,
                source,
            } => write!(
                f,
                "ack serialised tombstone size {observed_bytes} exceeds cap {cap_bytes} ({source})"
            ),
            AckError::SystemClockBeforeEpoch => write!(
                f,
                "ack: system clock returned pre-epoch value; refusing to compute dedup_until"
            ),
            AckError::DedupHorizonOverflow { now, horizon_secs } => write!(
                f,
                "ack: dedup_until = now({now}) + horizon({horizon_secs}) overflows u64"
            ),
            AckError::Internal(s) => write!(f, "ack internal invariant break: {s}"),
        }
    }
}

impl std::error::Error for AckError {}

/// **M3b-2b-i corrective #6**: dedicated mapper from
/// `SerializeRecordError` to `AckError`. Kept separate from
/// `AckError::Persistence` so unit tests can exercise the
/// serialize failure path without an fs seam.
impl From<SerializeRecordError> for AckError {
    fn from(e: SerializeRecordError) -> Self {
        match e {
            SerializeRecordError::TooLarge { observed, cap } => AckError::Serialize {
                observed_bytes: observed,
                cap_bytes: cap,
                source: "serde output exceeds MAX_RECORD_BYTES".to_string(),
            },
            SerializeRecordError::Serde(err) => AckError::Serialize {
                observed_bytes: 0,
                cap_bytes: MAX_RECORD_BYTES,
                source: err.to_string(),
            },
        }
    }
}

/// **M3b-2b-i corrective #2**: dedicated mapper from
/// `ClockError` to `AckError`. Keeps the mapping trivial and
/// unit-testable without touching `SystemTime`.
impl From<ClockError> for AckError {
    fn from(e: ClockError) -> Self {
        match e {
            ClockError::BeforeEpoch => AckError::SystemClockBeforeEpoch,
        }
    }
}

/// **M3b-3a**: success outcome of `do_sweep`. All three fields
/// are cumulative for THIS invocation only — the ledger snapshot
/// captured before the call is a better source for absolute
/// state.
///
/// * `queued_swept` — count of `Queued` records whose
///   `expires_at <= now` and that were durably unlinked +
///   released from `rest_store` / `store` / `active_index` /
///   ledger.
/// * `tombstones_swept` — count of tombstone dedup entries
///   whose `dedup_until <= now` and that were durably unlinked +
///   released from `tombstone_dedup` / ledger.
/// * `disk_reclaimed_bytes` — sum of `disk_bytes` across every
///   swept record.
///
/// **Round-1 REDLINE P1**: fields widened `u32 → u64` and all
/// increments use `u64::checked_add` — silent saturation
/// diverges from the fail-loud ledger policy (a truncated count
/// looks plausible to an operator and hides the invariant
/// break). Overflow after a successful sweep is fail-stop via
/// `fatal_sweep_invariant("post_unlink")`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SweepOutcome {
    pub queued_swept: u64,
    pub tombstones_swept: u64,
    pub disk_reclaimed_bytes: u64,
}

/// **M3b-3a**: domain-layer error surface of `do_sweep`. Only
/// pre-durable-unlink conditions surface here; any invariant
/// break AFTER a successful `remove_file` is fail-stop via
/// [`fatal_sweep_invariant`] → `std::process::abort()`
/// (matches the M3b-2b-ii `do_ack` durability contract).
#[derive(Debug)]
pub enum SweepError {
    /// Runtime shard-ownership check failed — the recipient
    /// hashed to a different worker than the one dispatching.
    /// Mirrors [`SendError::ShardMismatch`] / [`AckError::ShardMismatch`].
    ShardMismatch {
        expected_worker: usize,
        actual_worker: usize,
    },
    /// `ctx.clock.now_epoch_secs()` returned
    /// [`ClockError::BeforeEpoch`]. Refuses to proceed rather
    /// than run compaction with a garbage "now".
    SystemClockBeforeEpoch,
    /// A durable-unlink primitive (`fs::remove_file` /
    /// `atomic_write::fsync_dir`) returned `io::Error` BEFORE
    /// the record was durably removed. No state mutation on
    /// this branch — the caller may retry.
    Persistence(std::io::Error),
}

impl From<ClockError> for SweepError {
    fn from(e: ClockError) -> Self {
        match e {
            ClockError::BeforeEpoch => SweepError::SystemClockBeforeEpoch,
        }
    }
}

// ─── Fatal broadcast ──────────────────────────────────────────────────

/// Reasons a worker may terminate that MUST page (locked design v4 §4
/// + v4.1 §1 V-P0-8 + v4.2 §6 B-6).
#[derive(Debug, Clone)]
pub enum FatalReason {
    /// A worker `catch_unwind` caught a panic.
    WorkerPanic {
        worker_id: u8,
        panic_payload: String,
    },
    /// A worker's mpsc receiver returned `None` while
    /// [`crate::worker_pool::PoolState`] was still `Running`.
    WorkerUnexpectedExit { worker_id: u8 },
    /// A worker received a `RestOp` whose recipient hashed to a
    /// different worker id under `worker_for`.
    ShardRoutingInvariantViolation {
        worker_id: u8,
        expected_worker: usize,
    },
    /// **M3a**: worker received a `RestOp` variant it cannot execute
    /// yet (Ack/Sweep before M3b).
    UnsupportedOperation {
        worker_id: u8,
        /// `"ack"` or `"sweep"`.
        op_kind: &'static str,
    },
    /// **M3a F2**: per-boot seq counter exhausted (sticky). The
    /// process cannot issue any more seqs for this boot generation;
    /// operator must restart so the next boot's generation bump
    /// reclaims a fresh 40-bit space (up to
    /// [`queue_meta::MAX_BOOT_GENERATION`]).
    SeqSpaceExhausted { worker_id: u8, counter: u64 },
    /// **M3a round-3 F3**: the running `boot_generation` sits at
    /// or above [`queue_meta::MAX_BOOT_GENERATION`], so its
    /// `<< 40` shift would alias a lower generation's namespace.
    /// Dedicated variant (not aliased under `SeqSpaceExhausted`)
    /// so ops paging can distinguish "counter ran out at
    /// generation N" from "generation itself is invalid" — and so
    /// a corrupted seq counter of `u64::MAX` cannot masquerade as
    /// this cause.
    SeqNamespaceInvalid { worker_id: u8, generation: u32 },
    /// **M3a round-6 F2**: an ingress-hardening bypass was
    /// detected at the runtime's public send boundary — a
    /// malformed op reached [`WorkerRuntime::try_send`] with an
    /// empty recipient. The fatal is broadcast INDEPENDENTLY of
    /// any worker mpsc capacity (a full or closed worker channel
    /// no longer swallows the guarantee), so ops paging observes
    /// the bypass every time regardless of pool state.
    IngressBypassAtBoundary { at: &'static str },
    /// **M3b-1 round-1 F2**: `do_send` completed its durable
    /// disk write for `(recipient, id)` but discovered the
    /// active index already carries an entry under the same
    /// key. That means the ledger footprint recorded there is
    /// no longer trustworthy — a later ACK would release the
    /// wrong `path` / `disk_bytes` / `ram_bytes`. Reviewer's
    /// corrective #4: post-commit inconsistency cannot be a
    /// regular `SendError`; the worker broadcasts this fatal
    /// and follows the M2 `fatal_post_rename_failure` shape by
    /// calling `std::process::abort()`.
    ActiveIndexInvariantViolation {
        worker_id: u8,
        recipient: String,
        id: String,
    },
    // NOTE (M3b-2b-ii amendment round-1, REDLINE P2-1):
    // no `AckInvariantViolation` variant. `fatal_ack_invariant`
    // is a `-> !` helper that writes a `FATAL: ack invariant`
    // stderr line immediately before `std::process::abort()`;
    // it never observes a subscriber, so a broadcast variant
    // would only be dead information. Matches the
    // fatal_active_index_invariant shape (also stderr + abort,
    // no broadcast). The stderr line is the durable evidence.
}

// ─── Worker hash routing ──────────────────────────────────────────────

/// Boot-random keys backing [`worker_for`].
#[derive(Debug, Clone, Copy)]
pub struct WorkerHashKey {
    pub k0: u64,
    pub k1: u64,
}

impl WorkerHashKey {
    pub fn from_process_rng() -> Self {
        Self {
            k0: rand::random::<u64>(),
            k1: rand::random::<u64>(),
        }
    }

    pub const fn from_raw(k0: u64, k1: u64) -> Self {
        Self { k0, k1 }
    }
}

/// Pure shard router (locked design v4 §2).
///
/// Panics on an empty recipient: an empty recipient at this point
/// indicates an ingress-hardening bypass upstream (PR-0 A-6 rejects
/// empty at parse). The panic is caught by the worker's
/// `catch_unwind` (F3 amendment moved the shard check INSIDE the
/// caught scope) and surfaces as a `WorkerPanic` fatal.
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

// ─── ActorContext ─────────────────────────────────────────────────────

/// A worker's view of shared state (locked design v4 §2).
///
/// **Round-3 F1**: FULLY PRIVATE. The only path to construct one
/// is [`spawn_worker_runtime`], which validates every invariant
/// (generation cap, cardinality) before spawning. External
/// crates — including `main.rs`, which is a separate binary
/// crate — reach the actor exclusively through the public
/// [`WorkerRuntime`] handle.
struct ActorContext {
    worker_id: u8,
    worker_hash_key: WorkerHashKey,
    capacity: Arc<GlobalCapacityGate>,
    fatal: broadcast::Sender<FatalReason>,
    pool_state_reader: PoolStateReader,
    rest_store: Arc<RwLock<HashMap<String, Vec<RestEnvelope>>>>,
    store: Arc<RwLock<HashMap<String, Vec<Envelope>>>>,
    state_dir: PathBuf,
    max_envelopes_per_recipient: usize,
    boot_generation: u32,
    seq_counter: Arc<AtomicU64>,
    seq_mac_root_key: Arc<SeqMacRootKey>,
    /// **M3b-1**: active-record index. `do_send` inserts here
    /// atomically with the two RAM stores; M3b-2 `do_ack`
    /// removes on ack.
    active_index: Arc<ActiveRecordIndex>,
    /// **M3b-1**: tombstone dedup table. M3b-2 `do_ack` inserts
    /// after a Queued → Tombstone transition; M3b-3 `do_sweep`
    /// removes past-`dedup_until` entries.
    #[allow(dead_code)] // M3b-2/3 wire this in
    tombstone_dedup: Arc<TombstoneDedupTable>,
    /// **M3b-2a**: dedup horizon captured at boot from
    /// [`crate::boot_loader::BootConfig::tombstone`]. M3b-2's
    /// `do_ack` computes `dedup_until = now + horizon_secs()`
    /// using this value.
    #[allow(dead_code)] // M3b-2b wires do_ack against this
    tombstone_config: crate::tombstone_config::TombstoneConfig,
    /// **M3b-2b-i**: per-context clock. Production sends
    /// `ClockSource::Production`; unit tests use
    /// `Fixed(u64)` / `BeforeEpoch`.
    #[allow(dead_code)] // M3b-2b wires do_ack against this
    clock: ClockSource,
    /// **M3b-2b-ii amendment**: per-context test seams. Was a
    /// global `AtomicBool` in `persistence.rs` — but tokio
    /// tests run in parallel and one test's `store(true)`
    /// window bled into another test's `write_record_bytes`
    /// call and tripped its ACK. Per-context isolation kills
    /// the race by construction.
    #[cfg(test)]
    test_seams: Arc<TestSeams>,
}

/// **M3b-2b-ii amendment**: per-`ActorContext` test-only
/// fault-injection points. Only checked from inside the
/// `do_ack` / `do_send` transactions — never observed
/// outside a test context.
///
/// **Round-1 REDLINE reshape**: seams now emit
/// [`crate::persistence::WriteFault`] values that flow into
/// the persistence wrapper as a normal input. The wrapper
/// still returns `io::Result::Err`, callers still hit their
/// normal `.map_err(AckError::Persistence)?` branch — the
/// only difference vs. a real disk failure is that the Err
/// originates from the seam instead of a syscall. The prior
/// shape short-circuited the wrapper entirely, hiding the
/// production error-mapping edge from tests.
#[cfg(test)]
#[derive(Default)]
pub(crate) struct TestSeams {
    /// When `true`, [`Self::write_fault`] returns
    /// [`crate::persistence::WriteFault::ForceIoError`],
    /// causing [`crate::persistence::write_record_bytes`] to
    /// fail with `io::ErrorKind::Other` — the same shape a
    /// real fs error would produce. Callers exercise the
    /// production Err mapping.
    pub force_write_bytes_fail: std::sync::atomic::AtomicBool,
    /// **M3b-3a round-2** (REDLINE P1): when `true`,
    /// [`Self::remove_fault`] returns
    /// [`crate::persistence::RemoveFault::ForceIoError`],
    /// causing [`crate::persistence::remove_record_file`] to
    /// fail before touching the fs. Enables the
    /// `SweepError::Persistence` unit tests to exercise the
    /// exact typed-error path a real `fs::remove_file` I/O
    /// failure would take, without needing a real fs-level
    /// fault injector.
    pub force_remove_file_fail: std::sync::atomic::AtomicBool,
}

#[cfg(test)]
impl TestSeams {
    /// Snapshot the current seam settings as a
    /// [`crate::persistence::WriteFault`] value the caller can
    /// pass straight into
    /// [`crate::persistence::write_record_bytes`].
    #[inline]
    pub(crate) fn write_fault(&self) -> crate::persistence::WriteFault {
        if self
            .force_write_bytes_fail
            .load(std::sync::atomic::Ordering::SeqCst)
        {
            crate::persistence::WriteFault::ForceIoError
        } else {
            crate::persistence::WriteFault::None
        }
    }

    /// **M3b-3a round-2** (REDLINE P1): snapshot the current
    /// seam settings as a [`crate::persistence::RemoveFault`]
    /// value the caller can pass straight into
    /// [`crate::persistence::remove_record_file`].
    #[inline]
    pub(crate) fn remove_fault(&self) -> crate::persistence::RemoveFault {
        if self
            .force_remove_file_fail
            .load(std::sync::atomic::Ordering::SeqCst)
        {
            crate::persistence::RemoveFault::ForceIoError
        } else {
            crate::persistence::RemoveFault::None
        }
    }
}

/// **M3b-2b-ii amendment round-1**: cfg-dispatched helper that
/// resolves the `WriteFault` value for a
/// [`crate::persistence::write_record_bytes`] call. In prod
/// builds this is always
/// [`crate::persistence::WriteFault::None`] — the whole helper
/// is one instruction that inlines away. Under `#[cfg(test)]`
/// it delegates to [`TestSeams::write_fault`] so the ack-side
/// persistence-failure test can inject a fault WITHOUT
/// short-circuiting the production error-mapping path.
#[cfg(not(test))]
#[inline(always)]
fn resolve_write_fault(_ctx: &ActorContext) -> crate::persistence::WriteFault {
    crate::persistence::WriteFault::None
}

#[cfg(test)]
#[inline(always)]
fn resolve_write_fault(ctx: &ActorContext) -> crate::persistence::WriteFault {
    ctx.test_seams.write_fault()
}

/// **M3b-3a round-2** (REDLINE P1): cfg-dispatched helper that
/// resolves the `RemoveFault` value for a
/// [`crate::persistence::remove_record_file`] call. Same shape
/// + inlining behaviour as [`resolve_write_fault`].
#[cfg(not(test))]
#[inline(always)]
fn resolve_remove_fault(_ctx: &ActorContext) -> crate::persistence::RemoveFault {
    crate::persistence::RemoveFault::None
}

#[cfg(test)]
#[inline(always)]
fn resolve_remove_fault(ctx: &ActorContext) -> crate::persistence::RemoveFault {
    ctx.test_seams.remove_fault()
}

// ─── Seq assembly ─────────────────────────────────────────────────────

/// Assemble the next server-assigned seq per locked v4 §13 Q1:
/// `seq = (u64::from(boot_generation) << 40) | counter`.
///
/// **F2** (round-1): uses `fetch_update` so counter saturation is
/// sticky — once the counter reaches [`SEQ_COUNTER_LIMIT`] the
/// closure returns `None` on every subsequent call, `fetch_update`
/// returns `Err`, and every future `next_seq` call also returns
/// [`SendError::SeqSaturation`]. There is no `fetch_add` wrap.
///
/// **F4** (round-2): the boot-generation cap
/// ([`queue_meta::MAX_BOOT_GENERATION`]) is checked as a
/// hard runtime guard, not just `debug_assert!`. Combined with
/// [`ActorContext`]'s `pub(crate)` visibility this means neither
/// a boot-loader bypass NOR a same-crate wiring accident can
/// produce a generation whose `<< 40` shift collides with a
/// lower generation's namespace.
fn next_seq(ctx: &ActorContext) -> Result<u64, SendError> {
    if u64::from(ctx.boot_generation) >= (1u64 << 24) {
        return Err(SendError::BootGenerationOutOfRange {
            generation: ctx.boot_generation,
        });
    }
    match ctx
        .seq_counter
        .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |c| {
            if c >= SEQ_COUNTER_LIMIT {
                None
            } else {
                Some(c + 1)
            }
        }) {
        Ok(prev) => Ok((u64::from(ctx.boot_generation) << 40) | prev),
        Err(observed) => Err(SendError::SeqSaturation { counter: observed }),
    }
}

// ─── do_send (module-private per F6) ──────────────────────────────────

/// The transactional send path — the ONLY entry point permitted to
/// mutate `rest_store` / `store` for a Queued record. **Private to
/// this module (F6 amendment)** so the sole call path is
/// `RestOp::Send → worker_loop → dispatch_one → do_send`; that
/// closes the door on parallel-caller TOCTOU (two concurrent
/// callers both seeing the id absent, both reserving, both
/// pushing) by structural typing rather than convention.
///
/// **Order** (locked v4.1 §1 V-P0-7 + v4.2.1 §5, round-1 review
/// M3a corrective #5):
///
/// 1. shard-ownership check inside `catch_unwind` (F3);
/// 2. compute `body_hash` + read-lock check `rest_store` for
///    same-id dedup + per-recipient cap;
/// 3. allocate seq (sticky-saturating `next_seq`, F2) + compute
///    per-identity `seq_mac`;
/// 4. build [`PersistedRecord::Queued`] and its serialised
///    footprint (`disk_bytes`);
/// 5. RAM estimator = `2 * disk_bytes + 2 * struct_overhead`
///    (F4 conservative);
/// 6. `capacity.reserve_send(disk_bytes, ram_bytes)` — RAII
///    rollback on drop;
/// 7. atomic disk persistence via
///    [`crate::persistence::write_record`] — on failure the
///    reservation drops uncommitted and rolls back;
/// 8. acquire `rest_store.write().await`;
/// 9. acquire `store.write().await` — **the ONE allowed `.await`**
///    while the first lock is held;
/// 10. synchronous `Vec::push` on both stores;
/// 11. `reservation.commit()` — converts reserved → committed;
/// 12. drop both guards.
async fn do_send(
    ctx: &ActorContext,
    recipient: &str,
    candidate: SendCandidate,
) -> Result<SendOutcome, SendError> {
    // (0) round-2 F3: sealed-sender-only invariant is structural,
    // not commentary. Reject empty sealed_sender BEFORE any state
    // read so a legacy caller cannot land a `from`-less record
    // and then discover at delivery time that PersistedRecord has
    // no `from` column.
    if candidate.sealed_sender.is_empty() {
        return Err(SendError::UnsealedSenderRejected);
    }

    // (1) canonical body hash from the raw sealed_sender + payload
    // bytes (locked v4.1 V-P0-5).
    let body_hash = compute_body_hash_hex(
        candidate.sealed_sender.as_bytes(),
        candidate.payload.as_bytes(),
    );

    // (2) dedup + per-recipient cap + **M3b-1 round-2 F1 +
    // round-3 F1** pre-write consistency gate under one
    // read-lock scope. Canonical lock order:
    //     rest_store.read().await
    //   → store.read().await     (only allowed .await after
    //                              first lock)
    //   → active_index.get()     (sync `parking_lot::RwLock`)
    //
    // Round-3 F1 extends the gate to include the WS store
    // projection AND the canonical path / expires_at / on-disk
    // footprint checks — see [`check_pre_write_consistency`].
    // **M3b-2a** canonical 4-way read order (extends round-3
    // 3-way): `tombstone_dedup.get()` joins the same read-lock
    // scope so a `do_send` retry after an ack observes the
    // tombstone entry consistently with the other RAM state.
    let (rest_hit, store_hit, active_hit, tombstone_hit, observed) = {
        let rest = ctx.rest_store.read().await;
        let store = ctx.store.read().await;
        let queue = rest.get(recipient);
        let rest_hit = queue
            .and_then(|q| q.iter().find(|r| r.id == candidate.id).cloned());
        let observed = queue.map_or(0, Vec::len);
        let store_hit = store
            .get(recipient)
            .and_then(|q| q.iter().find(|e| e.id == candidate.id).cloned());
        let active_hit = ctx.active_index.get(recipient, &candidate.id);
        let tombstone_hit = ctx.tombstone_dedup.get(recipient, &candidate.id);
        (rest_hit, store_hit, active_hit, tombstone_hit, observed)
    };
    // **M3b-1 round-2 F2** test seam: pretend active_index has
    // a diverging entry so the subprocess test can drive the
    // fail-stop branch on a real process.
    #[cfg(test)]
    let active_hit = if FORCE_ACTIVE_INDEX_DRIFT_FATAL
        .load(std::sync::atomic::Ordering::SeqCst)
    {
        Some(ActiveEntryMeta {
            path: record_path(&ctx.state_dir, recipient, &candidate.id),
            seq: u64::MAX,
            body_hash: "phantom-forced-drift".to_string(),
            expires_at: candidate.expires_at,
            disk_bytes: 0,
            ram_bytes: 0,
        })
    } else {
        active_hit
    };
    match check_pre_write_consistency(
        rest_hit.as_ref(),
        store_hit.as_ref(),
        active_hit.as_ref(),
        tombstone_hit.as_ref(),
        recipient,
        &candidate.id,
        &ctx.state_dir,
    ) {
        Ok(None) => { /* new send — proceed */ }
        Ok(Some(prior)) => {
            if body_hash == prior.stored_body_hash {
                let disposition = match prior.source {
                    PriorRecordSource::Queued => SendDisposition::QueuedReplay,
                    PriorRecordSource::Tombstoned => SendDisposition::TombstoneReplay,
                };
                return Ok(SendOutcome {
                    seq: prior.seq,
                    idempotent_replay: true,
                    disposition,
                });
            }
            return Err(SendError::EnvelopeIdReusedWithDivergentBody {
                existing_body_hash: prior.stored_body_hash,
                incoming_body_hash: body_hash,
            });
        }
        Err(drift) => {
            fatal_active_index_invariant(
                ctx.worker_id,
                recipient,
                &candidate.id,
                "pre_write_gate",
                drift.detail,
            );
        }
    }
    // Actor serialization (F6: do_send is private + only reachable
    // through worker_loop) guarantees no other op for THIS
    // recipient is in flight during the read→write window, so a
    // TOCTOU on the cap check is impossible.
    if observed >= ctx.max_envelopes_per_recipient {
        return Err(SendError::PerRecipientQueueFull {
            observed,
            cap: ctx.max_envelopes_per_recipient,
        });
    }

    // (3) allocate seq + compute seq_mac (derive per-identity
    // verify key first — one HMAC).
    let seq = next_seq(ctx)?;
    let verify_key = ctx.seq_mac_root_key.derive_verify_key(recipient);
    let seq_mac_bytes = verify_key
        .compute_seq_mac(recipient, seq, &candidate.id, candidate.sequence_ts)
        .map_err(SendError::SeqMacCompute)?;
    let seq_mac_hex = seq_mac_to_hex(&seq_mac_bytes);

    // (4) build PersistedRecord and produce the opaque
    // SerializedRecord (round-3 F4/F5). Only serialize::* can
    // create one — a call site cannot slip an unchecked buffer
    // into the persistence layer. The same object feeds both the
    // capacity reservation sizing AND the on-disk write via
    // `write_record_bytes`, so the ledger and the disk see the
    // byte-identical footprint by type-level construction.
    let record = PersistedRecord::Queued {
        version: RECORD_VERSION,
        id: candidate.id.clone(),
        sealed_sender: candidate.sealed_sender.clone(),
        payload: candidate.payload.clone(),
        sequence_ts: candidate.sequence_ts,
        seq,
        expires_at: candidate.expires_at,
        seq_mac: seq_mac_hex.clone(),
        body_hash,
    };
    // Round-4 F3: preserve the actual observed byte count on the
    // TooLarge branch. Round-3 hard-coded `observed_bytes: 0`,
    // erasing the only number the HTTP/log surface would want.
    let serialized = SerializedRecord::serialize(&record).map_err(|e| match e {
        SerializeRecordError::TooLarge { observed, cap } => SendError::Serialize {
            observed_bytes: observed,
            cap_bytes: cap,
            source: "serde output exceeds MAX_RECORD_BYTES".to_string(),
        },
        SerializeRecordError::Serde(err) => SendError::Serialize {
            observed_bytes: 0,
            cap_bytes: MAX_RECORD_BYTES,
            source: err.to_string(),
        },
    })?;
    let disk_bytes = serialized.len() as u64;

    // (5) RAM footprint estimate (F4 round-1). Two RAM projections
    // carry the same payload/sealed-sender bytes; add a per-
    // projection struct overhead so the ledger cannot under-count
    // the actual heap footprint. Deliberately conservative — an
    // M4 benchmark can refine it downward.
    // M3b-2b-i corrective #4: use shared `record_ram_estimate`
    // so boot seed and future ACK transitions can't drift.
    let ram_bytes = record_ram_estimate(disk_bytes);

    // (6) capacity reserve — RAII rollback on any early return.
    let reservation = ctx
        .capacity
        .reserve_send(disk_bytes, ram_bytes)
        .map_err(SendError::CapacityExceeded)?;

    // (7) atomic disk persist — hands the SAME `SerializedRecord`
    // that sized the reservation (round-3 F4/F5).
    let disk_path = record_path(&ctx.state_dir, recipient, &candidate.id);
    if let Err(io_err) = persistence::write_record_bytes(
        &disk_path,
        &serialized,
        resolve_write_fault(ctx),
    ) {
        // reservation dropped here (unbind on early return) → auto-rollback.
        return Err(SendError::Persistence(io_err));
    }

    // **M5a-2 SIGKILL failpoint** — record has passed through
    // `write_atomic` (staging → fsync → rename → parent-fsync all
    // returned Ok); RAM projections (rest_store, store,
    // active_index) + ledger commit are NOT yet applied. SIGKILL
    // invariant: the record is present at `disk_path` after
    // SIGKILL and boot replay recovers it as
    // `PersistedRecord::Queued`; the ledger seeds from disk
    // truth so no drift. SIGKILL only proves the absence of
    // user-space teardown — no power-loss claim.
    crate::failpoint!("send.after_disk_commit_before_ram_commit");

    // (8+9+10) canonical two-store lock order — v4.1 V-P0-7 +
    // v4.2.1 §5 + M3b-1 round-8 corrective #6 lock order
    // `rest_store → store → active_index → tombstones`. `do_send`
    // touches all except tombstones (that's M3b-2 territory).
    //
    // **M3b-1 round-1 F3**: the `body_hash` computed at step (2)
    // is REUSED here structurally rather than recomputed —
    // round-1 review flagged the second call to
    // `compute_body_hash_hex` as fragile (two independent
    // computes should always agree but the equality is a
    // guarantee of `sha2::Sha256`, not of our own code). The
    // active-index entry gets its `body_hash` via a `.clone()`
    // out of the record built below — one compute site, one
    // source of truth, no second hash call.
    let id_for_index = candidate.id.clone();
    let ws_envelope = Envelope {
        id: candidate.id.clone(),
        to: recipient.to_string(),
        // Sealed-sender messages carry no visible sender identity
        // (locked ADR-006). Legacy senders never enter this path.
        from: String::new(),
        sealed_sender: candidate.sealed_sender.clone(),
        payload: candidate.payload.clone(),
        expires_at: candidate.expires_at,
    };
    let rest_envelope = RestEnvelope {
        id: candidate.id,
        from: String::new(),
        sealed_sender: candidate.sealed_sender,
        payload: candidate.payload,
        sequence_ts: candidate.sequence_ts,
        seq,
        expires_at: candidate.expires_at,
        seq_mac: seq_mac_hex,
    };
    // Round-1 F3: extract the record's own body_hash so it can
    // move into the index — no second `compute_body_hash_hex`
    // call.
    let record_body_hash = match &record {
        PersistedRecord::Queued { body_hash, .. } => body_hash.clone(),
        // Unreachable: we build a Queued record two blocks above.
        _ => unreachable!("do_send record is Queued by construction"),
    };
    let mut rest_guard = ctx.rest_store.write().await;
    let mut store_guard = ctx.store.write().await;
    // From here: NO .await. Synchronous mutations only.
    rest_guard
        .entry(recipient.to_string())
        .or_default()
        .push(rest_envelope);
    store_guard
        .entry(recipient.to_string())
        .or_default()
        .push(ws_envelope);
    // M3b-1 + round-1 F2 + round-2 F1/F2: register the new
    // record in the active index. The pre-write consistency gate
    // above already caught any drift, so this call MUST succeed;
    // an Err here is a final defensive assertion — the same
    // invariant break, discovered too late. Route through the
    // durable-evidence [`fatal_active_index_invariant`] helper.
    if let Err(existing) = ctx.active_index.try_insert_new(
        recipient,
        id_for_index.clone(),
        ActiveEntryMeta {
            path: disk_path.clone(),
            seq,
            body_hash: record_body_hash,
            expires_at: candidate.expires_at,
            disk_bytes,
            ram_bytes,
        },
    ) {
        // Broadcast so any listening supervisor learns before the
        // process abort. The eprintln + abort inside
        // fatal_active_index_invariant provides the durable
        // evidence.
        let _ = ctx.fatal.send(FatalReason::ActiveIndexInvariantViolation {
            worker_id: ctx.worker_id,
            recipient: recipient.to_string(),
            id: id_for_index.clone(),
        });
        fatal_active_index_invariant(
            ctx.worker_id,
            recipient,
            &id_for_index,
            "post_write_assertion",
            format!(
                "try_insert_new refused at post-persist; existing.path={:?} existing.seq={} rejected.path={:?} rejected.seq={}",
                existing.existing_meta.path,
                existing.existing_meta.seq,
                existing.rejected_meta.path,
                existing.rejected_meta.seq,
            ),
        );
    }
    // (11) commit reservation — RAII no longer rolls back.
    reservation.commit();
    drop(store_guard);
    drop(rest_guard);

    Ok(SendOutcome {
        seq,
        idempotent_replay: false,
        disposition: SendDisposition::Fresh,
    })
}

// ─── worker_loop ──────────────────────────────────────────────────────

/// The dispatch body a spawned worker task runs.
///
/// **F3 amendment**: the shard-ownership check now runs INSIDE
/// `catch_unwind` (via `dispatch_one`), so a malformed op with
/// `recipient=""` — which would panic the router — surfaces as
/// `WorkerPanic` fatal instead of tearing down the task silently.
async fn worker_loop(
    ctx: ActorContext,
    mut rx: mpsc::Receiver<RestOp>,
) -> Result<(), FatalReason> {
    use futures_util::FutureExt;
    while let Some(op) = rx.recv().await {
        // Per-op catch_unwind wraps EVERYTHING per-op — the shard
        // check, the do_send transaction, and reply routing. A
        // panic in any of these becomes a WorkerPanic broadcast
        // rather than aborting the runtime.
        let dispatch_outcome =
            std::panic::AssertUnwindSafe(dispatch_one(&ctx, op)).catch_unwind().await;

        match dispatch_outcome {
            Ok(Ok(())) => continue,
            Ok(Err(fatal)) => {
                let _ = ctx.fatal.send(fatal.clone());
                return Err(fatal);
            }
            Err(panic_payload) => {
                let payload = extract_panic_message(&panic_payload);
                let reason = FatalReason::WorkerPanic {
                    worker_id: ctx.worker_id,
                    panic_payload: payload,
                };
                let _ = ctx.fatal.send(reason.clone());
                return Err(reason);
            }
        }
    }

    // rx.recv() == None. Classify via PoolStateReader (v4.2 §5 B-5
    // + v4.1 V-P0-8).
    match ctx.pool_state_reader.kind() {
        PoolStateKind::Running => {
            let reason = FatalReason::WorkerUnexpectedExit {
                worker_id: ctx.worker_id,
            };
            let _ = ctx.fatal.send(reason.clone());
            Err(reason)
        }
        PoolStateKind::Closing | PoolStateKind::Closed => Ok(()),
    }
}

// ─── do_ack (M3b-2b-ii) ───────────────────────────────────────────────

/// **M3b-2b-ii**: the transactional ACK path.
///
/// Order — per the M3b-2b scope-approval review's corrective
/// #4 with the round-1 amendments:
///
/// ```text
///  1. shard-ownership defense-in-depth
///  2. optimistic classify (rest.read + store.read
///     + active_index.get + tombstone_dedup.get)
///       → Absent      → return NotFound (no mutation)
///       → Tombstoned  → return Idempotent (no mutation)
///       → Queued      → clone active meta, proceed
///       → Err(drift)  → fatal_ack_invariant("pre_ack_gate")
///  3. clock (ctx.clock.now_epoch_secs) — typed AckError
///     on failure (pre-durable)
///  4. dedup_until = now.checked_add(horizon) — typed
///     AckError::DedupHorizonOverflow on None (pre-durable)
///  5. build PersistedRecord::AckedTombstone + serialize
///     ONCE — typed AckError::Serialize via From on failure
///  6. rest_store.write().await
///  7. store.write().await                (only .await
///                                         between step 2 and
///                                         step 12)
///  8. cardinality — for each store, exactly one match on
///     `id`. Save positions. Zero-or-many → FATAL
///     "pre_commit_reverify".
///  9. prepare_active_to_tombstone_transition — holds
///     active+tombstone guards. Any Err (ActiveMissing /
///     ActiveDiverged / TombstoneAlreadyPresent) →
///     FATAL "pre_commit_reverify" (corrective #1: RAM
///     invariant break; disk is still unchanged but the
///     process cannot continue).
/// 10. classifier revalidation under all 4 guards using the
///     positions found in step 8 + `prepared.active()`.
///     Anything but `Queued` → FATAL "pre_commit_reverify".
/// 11. write_record_bytes(active.path, &serialized) —
///     durable commit. Err → drop `prepared` → typed
///     AckError::Persistence (nothing committed yet).
/// 12. --- from here NO .await, NO AckError; failures →
///     fatal_ack_invariant("post_commit") ---
/// 13. capacity.transition(Some(queued_fp), Some(tomb_fp))
///     — any Err → FATAL "post_commit".
/// 14. rest_store[recipient].remove(rest_pos) + id check;
///     store[recipient].remove(store_pos) + id check.
///     Any mismatch → FATAL "post_commit".
/// 15. prepared.commit(new_tombstone_entry) — synchronous
///     active→tombstone swap under still-held index guards.
/// 16. Drop all guards, return AckOutcome::Acked { seq }.
/// ```
async fn do_ack(
    ctx: &ActorContext,
    recipient: &str,
    envelope_id: &str,
) -> Result<AckOutcome, AckError> {
    // (1) Shard-ownership check — defense-in-depth. The
    // worker_loop pre-dispatch check catches most, but a
    // late M4 wiring bug could still route wrong.
    let expected = worker_for(recipient, ctx.worker_hash_key);
    if expected != usize::from(ctx.worker_id) {
        return Err(AckError::ShardMismatch {
            expected_worker: expected,
            actual_worker: usize::from(ctx.worker_id),
        });
    }

    // (2) Optimistic read gate.
    let (rest_hit, store_hit, active_hit, tombstone_hit) = {
        let rest = ctx.rest_store.read().await;
        let store = ctx.store.read().await;
        let rest_hit = rest
            .get(recipient)
            .and_then(|q| q.iter().find(|r| r.id == envelope_id).cloned());
        let store_hit = store
            .get(recipient)
            .and_then(|q| q.iter().find(|e| e.id == envelope_id).cloned());
        let active_hit = ctx.active_index.get(recipient, envelope_id);
        let tombstone_hit = ctx.tombstone_dedup.get(recipient, envelope_id);
        (rest_hit, store_hit, active_hit, tombstone_hit)
    };
    let queued_active = match classify_record_state(
        rest_hit.as_ref(),
        store_hit.as_ref(),
        active_hit.as_ref(),
        tombstone_hit.as_ref(),
        recipient,
        envelope_id,
        &ctx.state_dir,
    ) {
        Ok(ConsistentRecordState::Absent) => return Ok(AckOutcome::NotFound),
        Ok(ConsistentRecordState::Tombstoned { entry }) => {
            return Ok(AckOutcome::Idempotent { seq: entry.seq });
        }
        Ok(ConsistentRecordState::Queued { active, .. }) => active.clone(),
        Err(drift) => {
            fatal_ack_invariant(
                ctx.worker_id,
                recipient,
                envelope_id,
                "pre_ack_gate",
                drift.detail,
            );
        }
    };

    // (3) Clock — typed pre-commit error.
    let now = ctx.clock.now_epoch_secs()?;
    // (4) dedup_until with checked_add — typed pre-commit
    // error on overflow.
    let horizon = u64::from(ctx.tombstone_config.horizon_secs());
    let dedup_until = now
        .checked_add(horizon)
        .ok_or(AckError::DedupHorizonOverflow {
            now,
            horizon_secs: ctx.tombstone_config.horizon_secs(),
        })?;

    // (5) Build the tombstone + serialize once.
    let acked_record = PersistedRecord::AckedTombstone {
        version: RECORD_VERSION,
        id: envelope_id.to_string(),
        seq: queued_active.seq,
        body_hash: queued_active.body_hash.clone(),
        acked_at: now,
        dedup_until,
    };
    let serialized = SerializedRecord::serialize(&acked_record)?;
    let new_disk_bytes = serialized.len() as u64;
    let new_ram_bytes = record_ram_estimate(new_disk_bytes);
    let new_tombstone_entry = TombstoneEntry {
        path: queued_active.path.clone(),
        seq: queued_active.seq,
        body_hash: queued_active.body_hash.clone(),
        dedup_until,
        disk_bytes: new_disk_bytes,
        ram_bytes: new_ram_bytes,
    };

    // (6-7) Async two-store write lock. The `store.write()`
    // .await is the ONE and only .await after step (2)'s read
    // gate.
    let mut rest_guard = ctx.rest_store.write().await;
    let mut store_guard = ctx.store.write().await;

    // (8) Under-guards cardinality (corrective #2). For each
    // Vec, exactly one match on `id`. Save the positions for
    // step (14) removal by index.
    let rest_positions: Vec<usize> = match rest_guard.get(recipient) {
        Some(v) => v
            .iter()
            .enumerate()
            .filter_map(|(i, r)| (r.id == envelope_id).then_some(i))
            .collect(),
        None => Vec::new(),
    };
    if rest_positions.len() != 1 {
        fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "pre_commit_reverify",
            format!(
                "rest_store cardinality {} != 1 for (recipient, id)",
                rest_positions.len()
            ),
        );
    }
    let rest_pos = rest_positions[0];
    let store_positions: Vec<usize> = match store_guard.get(recipient) {
        Some(v) => v
            .iter()
            .enumerate()
            .filter_map(|(i, e)| (e.id == envelope_id).then_some(i))
            .collect(),
        None => Vec::new(),
    };
    if store_positions.len() != 1 {
        fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "pre_commit_reverify",
            format!(
                "store cardinality {} != 1 for (recipient, id)",
                store_positions.len()
            ),
        );
    }
    let store_pos = store_positions[0];

    // (9) Prepare index transition — acquires active+tombstone
    // write guards + validates preconditions. Any Err
    // (ActiveMissing / ActiveDiverged / TombstoneAlreadyPresent)
    // means the RAM invariant is broken. Disk is still
    // untouched, but per corrective #1 we cannot recover —
    // fail-stop.
    let prepared = match prepare_active_to_tombstone_transition(
        &ctx.active_index,
        &ctx.tombstone_dedup,
        recipient,
        envelope_id,
        &queued_active,
    ) {
        Ok(p) => p,
        Err(e) => {
            fatal_ack_invariant(
                ctx.worker_id,
                recipient,
                envelope_id,
                "pre_commit_reverify",
                format!("prepare_active_to_tombstone_transition refused: {e:?}"),
            );
        }
    };

    // (10) Full classifier revalidation under all 4 guards
    // (corrective #2). Uses the unique positions found in
    // step (8) plus `prepared.active()`. Any drift from the
    // optimistic phase → fail-stop.
    {
        let rest_ref = &rest_guard.get(recipient).expect("bucket per step 8")[rest_pos];
        let store_ref = &store_guard.get(recipient).expect("bucket per step 8")[store_pos];
        match classify_record_state(
            Some(rest_ref),
            Some(store_ref),
            Some(prepared.active()),
            // Tombstone must be Vacant per prepare's contract
            // AND we hold the tombstone guard, so no other
            // writer can have inserted since.
            None,
            recipient,
            envelope_id,
            &ctx.state_dir,
        ) {
            Ok(ConsistentRecordState::Queued { .. }) => {}
            Ok(other) => {
                fatal_ack_invariant(
                    ctx.worker_id,
                    recipient,
                    envelope_id,
                    "pre_commit_reverify",
                    format!(
                        "under-guards classifier returned unexpected state: {other:?}"
                    ),
                );
            }
            Err(drift) => {
                fatal_ack_invariant(
                    ctx.worker_id,
                    recipient,
                    envelope_id,
                    "pre_commit_reverify",
                    format!("under-guards classifier drift: {}", drift.detail),
                );
            }
        }
    }

    // (11) Durable commit. Err drops `prepared` → guards
    // release → no mutation. Typed pre-commit error.
    //
    // **M3b-2b-ii amendment round-1** (REDLINE P1-2): the fault
    // input is a per-call `WriteFault` value resolved from the
    // ctx test-seams. In production it's always
    // `WriteFault::None` and inlines away; under `#[cfg(test)]`
    // an armed seam produces an `io::Error::Other` from
    // `write_record_bytes` — the SAME shape a real disk failure
    // would produce — so the test exercises this exact
    // `.map_err(AckError::Persistence)?` branch instead of
    // short-circuiting before it.
    persistence::write_record_bytes(
        &queued_active.path,
        &serialized,
        resolve_write_fault(ctx),
    )
    .map_err(AckError::Persistence)?;

    // (12) --- durable commit boundary ---
    // From here: NO .await, NO AckError, NO Result-based
    // recovery. Any failure → fatal_ack_invariant("post_commit").

    // **M5a-2 SIGKILL failpoint** — tombstone bytes have passed
    // through `write_atomic` (the Queued record's canonical file
    // now holds AckedTombstone bytes on disk); ledger transition
    // + dedup insert + active/RAM removal have NOT yet fired.
    // SIGKILL invariant: after SIGKILL the on-disk record at the
    // canonical path parses as `PersistedRecord::AckedTombstone`;
    // boot replay recovers the tombstone into `tombstone_dedup`,
    // and a subsequent Send with the same envelope_id gets
    // `TombstoneReplay` disposition (idempotent). The USER-SPACE
    // abort variant of this same boundary is already covered by
    // the `TEST_FORCE_ACK_POST_COMMIT_FATAL` seam below; this
    // failpoint adds the KERNEL-level SIGKILL variant. SIGKILL
    // only proves the absence of user-space teardown — no
    // power-loss claim.
    crate::failpoint!("ack.after_tombstone_disk_commit_before_ledger_and_ram_commit");

    // Test seam for the reboot-replay subprocess test — fires
    // AFTER write_record_bytes and BEFORE the ledger / RAM
    // mutation, so the parent test can inspect the durable
    // AckedTombstone on disk without any RAM update having
    // landed.
    #[cfg(test)]
    if TEST_FORCE_ACK_POST_COMMIT_FATAL.load(std::sync::atomic::Ordering::SeqCst) {
        fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "post_commit",
            "TEST_FORCE_ACK_POST_COMMIT_FATAL fault seam fired".to_string(),
        );
    }

    // (13) Ledger transition.
    let queued_footprint = crate::capacity_ledger::RecordFootprint {
        kind: crate::capacity_ledger::RecordKind::Queued,
        disk_bytes: queued_active.disk_bytes,
        ram_bytes: queued_active.ram_bytes,
    };
    let tombstone_footprint = crate::capacity_ledger::RecordFootprint {
        kind: crate::capacity_ledger::RecordKind::AckedTombstone,
        disk_bytes: new_disk_bytes,
        ram_bytes: new_ram_bytes,
    };
    if let Err(e) = ctx
        .capacity
        .transition(Some(queued_footprint), Some(tombstone_footprint))
    {
        fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "post_commit",
            format!("capacity.transition failed post-durable: {e:?}"),
        );
    }

    // (14) Checked removals by saved positions. Every panic
    // primitive (HashMap::get_mut(...).expect(...), unchecked
    // Vec::remove) is replaced with an explicit branch that
    // routes to fatal_ack_invariant("post_commit") — the
    // architect REDLINE flagged the old shape as unwind-into-
    // catch_unwind → WorkerPanic, which after the durable
    // write violates the post-commit contract of process abort.
    let rest_bucket = match rest_guard.get_mut(recipient) {
        Some(b) => b,
        None => fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "post_commit",
            format!(
                "rest_store bucket for recipient missing at removal time \
                 (rest_pos was {rest_pos})"
            ),
        ),
    };
    if rest_pos >= rest_bucket.len() {
        fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "post_commit",
            format!(
                "rest_pos {rest_pos} out of bounds for rest_store bucket \
                 len {}",
                rest_bucket.len()
            ),
        );
    }
    let removed_rest = rest_bucket.remove(rest_pos);
    if removed_rest.id != envelope_id {
        fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "post_commit",
            format!(
                "rest_store removal id mismatch: removed={} expected={envelope_id}",
                removed_rest.id
            ),
        );
    }
    let store_bucket = match store_guard.get_mut(recipient) {
        Some(b) => b,
        None => fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "post_commit",
            format!(
                "store bucket for recipient missing at removal time \
                 (store_pos was {store_pos})"
            ),
        ),
    };
    if store_pos >= store_bucket.len() {
        fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "post_commit",
            format!(
                "store_pos {store_pos} out of bounds for store bucket \
                 len {}",
                store_bucket.len()
            ),
        );
    }
    let removed_store = store_bucket.remove(store_pos);
    if removed_store.id != envelope_id {
        fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "post_commit",
            format!(
                "store removal id mismatch: removed={} expected={envelope_id}",
                removed_store.id
            ),
        );
    }

    // (15) Atomic index swap under the still-held guards.
    // `PreparedTransition::commit` was hardened round-1 to
    // return `Result<_, CommitError>` — no internal `.expect`
    // that could unwind past the post-commit boundary. Any
    // `Err` routes to `fatal_ack_invariant("post_commit")`
    // without partial index mutation (see docstring on
    // `PreparedTransition::commit`).
    let prior_seq = queued_active.seq;
    match prepared.commit(new_tombstone_entry) {
        Ok(_removed_active) => {}
        Err(e) => fatal_ack_invariant(
            ctx.worker_id,
            recipient,
            envelope_id,
            "post_commit",
            format!("PreparedTransition::commit refused post-durable: {e:?}"),
        ),
    }

    // (16) Drop all guards implicitly at end of scope.
    drop(store_guard);
    drop(rest_guard);

    Ok(AckOutcome::Acked { seq: prior_seq })
}

// ─── M3b-3a: do_sweep ─────────────────────────────────────────────────

/// **M3b-3a round-1** (REDLINE P0): result of classifying a
/// candidate `(recipient, id)` under all four write guards.
/// Every `sweep_one` iteration reaches a `SweepClassification`
/// before touching disk.
enum SweepClassification {
    /// The record is not present, or is present but not yet
    /// expired. Skip silently — no mutation, no error, no
    /// counter increment.
    Skip,
    /// A strict `Queued` record whose `expires_at <= now`.
    /// The path + durable identity + all 3 RAM projections
    /// have already been verified.
    ExpiredQueued {
        meta: ActiveEntryMeta,
        rest_pos: usize,
        store_pos: usize,
    },
    /// A strict `AckedTombstone` whose `dedup_until <= now`.
    /// Path + durable identity already verified via
    /// `verify_disk_footprint` + `verify_durable_tombstone`
    /// inside `classify_record_state`.
    ExpiredTombstone { entry: TombstoneEntry },
}

/// **M3b-3a**: durable-first TTL sweep for one recipient.
///
/// Contract enforced (from M3b-3 scope approval + round-1
/// REDLINE):
///   * Runs only on the recipient-owned worker
///     (`worker_for(recipient, ctx.worker_hash_key) == ctx.worker_id`);
///     shard mismatch surfaces as [`SweepError::ShardMismatch`]
///     (caller escalates to
///     [`FatalReason::ShardRoutingInvariantViolation`]).
///   * ONE production-clock snapshot per invocation via
///     `ctx.clock.now_epoch_secs()`; expiry boundary is
///     `expires_at <= now` (Queued) / `dedup_until <= now`
///     (tombstones). Non-expired records stay
///     byte-identical and no counter is touched.
///   * **Round-1 REDLINE P0** — unified 4-lock transaction per
///     record. `sweep_one` acquires the FULL canonical lock
///     order `rest_store → store → active_index →
///     tombstone_dedup` and passes all four projections
///     through [`classify_record_state`]. Only strict
///     `ConsistentRecordState::Queued` (all 3 queued
///     projections present, tombstone absent, durable Queued
///     verified) or strict
///     `ConsistentRecordState::Tombstoned` (only tombstone
///     present, durable tombstone verified) proceed to unlink;
///     any partial or double state → `Err(DriftDetail)` →
///     `fatal_sweep_invariant("pre_unlink_reverify")` BEFORE
///     any filesystem mutation.
///   * **Round-1 REDLINE P1a** — durable identity re-verified
///     via [`verify_durable_queued`] (symmetric to
///     [`verify_durable_tombstone`]) as an EXTRA check right
///     before unlink. Combined with `classify_record_state`'s
///     `verify_disk_footprint` this closes all six pre-unlink
///     concerns the reviewer named (canonical path, symlink /
///     directory, actual size, schema version, record variant,
///     durable identity match).
///   * **Round-1 REDLINE P1b** — counters use `u64::checked_add`
///     and any overflow AFTER a successful sweep aborts via
///     `fatal_sweep_invariant("post_unlink")`. Silent
///     saturation is incompatible with the fail-loud ledger
///     policy.
///
/// Returns [`SweepOutcome`] with counts + reclaimed disk bytes.
async fn do_sweep(
    ctx: &ActorContext,
    recipient: &str,
) -> Result<SweepOutcome, SweepError> {
    // (1) Shard defense-in-depth.
    let expected = worker_for(recipient, ctx.worker_hash_key);
    let actual = usize::from(ctx.worker_id);
    if expected != actual {
        return Err(SweepError::ShardMismatch {
            expected_worker: expected,
            actual_worker: actual,
        });
    }

    // (2) Single production-clock snapshot (per scope
    // constraint #2). Clock-before-epoch surfaces as typed
    // `SweepError::SystemClockBeforeEpoch` via `From<ClockError>`.
    let now = ctx.clock.now_epoch_secs()?;

    let mut queued_swept: u64 = 0;
    let mut tombstones_swept: u64 = 0;
    let mut disk_reclaimed_bytes: u64 = 0;

    // (3) Collect candidate ids from BOTH projections under a
    // short read guard on each. Under the actor model no other
    // op on this worker runs concurrently, so the snapshot
    // captures a stable view. Duplicates (same id in both
    // projections) are OK — the per-id 4-lock transaction
    // will classify the double-state as drift and fatal.
    //
    // **Round-2 REDLINE P2**: membership check backed by a
    // `HashSet<String>` — the round-1 shape used a linear
    // `Vec::iter().any(...)` per tombstone candidate, so a
    // recipient with N Queued + M tombstones paid O(N*M)
    // during dedup. `HashSet::insert` amortises to O(1);
    // total collection is O(N+M) with the sort at the end
    // costing O((N+M) log (N+M)).
    //
    // **Round-2 P2 nit**: sort ids inside each group before
    // combining. `active_index` and `tombstone_dedup` are
    // both `HashMap` — iteration order is not stable across
    // processes (or across `HashMap` growth events within one
    // process). The round-2 comment claimed "deterministic
    // per-record order"; sorting each group after collection
    // makes that claim true. Cross-group ordering is still
    // "Queued first, then tombstones".
    let mut candidate_set: std::collections::HashSet<String> =
        std::collections::HashSet::new();
    let mut queued_ids: Vec<String> = Vec::new();
    let mut tombstone_ids: Vec<String> = Vec::new();
    {
        let guard = ctx.active_index.inner.read();
        if let Some(bucket) = guard.get(recipient) {
            for (id, meta) in bucket {
                if meta.expires_at <= now && candidate_set.insert(id.clone()) {
                    queued_ids.push(id.clone());
                }
            }
        }
    }
    {
        let guard = ctx.tombstone_dedup.inner.read();
        if let Some(bucket) = guard.get(recipient) {
            for (id, entry) in bucket {
                if entry.dedup_until <= now && candidate_set.insert(id.clone()) {
                    tombstone_ids.push(id.clone());
                }
            }
        }
    }
    queued_ids.sort();
    tombstone_ids.sort();
    let mut candidates: Vec<String> = queued_ids;
    candidates.extend(tombstone_ids);

    // (4) Per candidate: unified 4-lock transaction.
    for id in candidates {
        if let Some(swept) = sweep_one(ctx, recipient, &id, now).await? {
            match swept {
                SweptRecord::Queued { disk_bytes } => {
                    queued_swept = queued_swept.checked_add(1).unwrap_or_else(|| {
                        fatal_sweep_invariant(
                            ctx.worker_id,
                            recipient,
                            &id,
                            "post_unlink",
                            format!(
                                "queued_swept u64 overflow after successful sweep \
                                 (prior={queued_swept})"
                            ),
                        );
                    });
                    disk_reclaimed_bytes =
                        disk_reclaimed_bytes.checked_add(disk_bytes).unwrap_or_else(|| {
                            fatal_sweep_invariant(
                                ctx.worker_id,
                                recipient,
                                &id,
                                "post_unlink",
                                format!(
                                    "disk_reclaimed_bytes u64 overflow: \
                                     prior={disk_reclaimed_bytes} add={disk_bytes}"
                                ),
                            );
                        });
                }
                SweptRecord::Tombstone { disk_bytes } => {
                    tombstones_swept = tombstones_swept.checked_add(1).unwrap_or_else(|| {
                        fatal_sweep_invariant(
                            ctx.worker_id,
                            recipient,
                            &id,
                            "post_unlink",
                            format!(
                                "tombstones_swept u64 overflow after successful sweep \
                                 (prior={tombstones_swept})"
                            ),
                        );
                    });
                    disk_reclaimed_bytes =
                        disk_reclaimed_bytes.checked_add(disk_bytes).unwrap_or_else(|| {
                            fatal_sweep_invariant(
                                ctx.worker_id,
                                recipient,
                                &id,
                                "post_unlink",
                                format!(
                                    "disk_reclaimed_bytes u64 overflow: \
                                     prior={disk_reclaimed_bytes} add={disk_bytes}"
                                ),
                            );
                        });
                }
            }
        }
    }

    Ok(SweepOutcome {
        queued_swept,
        tombstones_swept,
        disk_reclaimed_bytes,
    })
}

/// **M3b-3a round-1**: outcome of a single successful record
/// sweep. Distinguishes Queued vs Tombstone so `do_sweep`'s
/// counters land in the right bucket.
enum SweptRecord {
    Queued { disk_bytes: u64 },
    Tombstone { disk_bytes: u64 },
}

/// **M3b-3a round-1** (REDLINE P0 + P1a): unified per-record
/// sweep transaction. Holds ALL FOUR write guards for the
/// duration of the classification + verification steps.
///
/// Returns:
///   * `Ok(Some(SweptRecord))` — record swept end-to-end.
///   * `Ok(None)` — classifier saw `Absent` OR the record
///     exists but is no longer expired under the fresh
///     `now` snapshot. No mutation, no error.
///   * `Err(SweepError::Persistence)` — pre-durable I/O error
///     from `fs::remove_file`. No mutation.
///
/// Any drift (`classify_record_state` Err), any post-classify
/// durable identity failure (`verify_durable_queued`), or any
/// post-`remove_file` failure aborts via
/// [`fatal_sweep_invariant`].
async fn sweep_one(
    ctx: &ActorContext,
    recipient: &str,
    id: &str,
    now: u64,
) -> Result<Option<SweptRecord>, SweepError> {
    // (1) Canonical lock order — ALL FOUR guards:
    //   rest_store → store → active_index → tombstone_dedup.
    // parking_lot guards are sync and cannot cross `.await`; no
    // await between here and the durable unlink boundary.
    let mut rest_guard = ctx.rest_store.write().await;
    let mut store_guard = ctx.store.write().await;
    let mut active_guard = ctx.active_index.inner.write();
    let mut tombstone_guard = ctx.tombstone_dedup.inner.write();

    // (2) Materialise the four projections into references
    // classify_record_state expects. Cardinality of the RAM
    // buckets is verified below; here we just look up the id.
    let rest_hit = {
        let bucket_ref = rest_guard.get(recipient);
        find_single_or_fatal(
            bucket_ref,
            |e: &RestEnvelope| e.id.as_str(),
            ctx.worker_id,
            recipient,
            id,
            "rest_store",
        )
    };
    let store_hit = {
        let bucket_ref = store_guard.get(recipient);
        find_single_or_fatal(
            bucket_ref,
            |e: &Envelope| e.id.as_str(),
            ctx.worker_id,
            recipient,
            id,
            "store",
        )
    };
    let active_hit = active_guard.get(recipient).and_then(|b| b.get(id));
    let tombstone_hit = tombstone_guard.get(recipient).and_then(|b| b.get(id));

    // (3) Classify — REDLINE P0 fix. All partial / double
    // states surface as DriftDetail → fatal_sweep_invariant
    // BEFORE any filesystem mutation.
    let classification = match classify_record_state(
        rest_hit,
        store_hit,
        active_hit,
        tombstone_hit,
        recipient,
        id,
        &ctx.state_dir,
    ) {
        Ok(ConsistentRecordState::Absent) => SweepClassification::Skip,
        Ok(ConsistentRecordState::Queued { active, .. }) => {
            if active.expires_at > now {
                SweepClassification::Skip
            } else {
                // Cardinality is guaranteed by find_single_or_fatal
                // above returning Some; positions are re-derived
                // below since the classifier only takes references.
                // Under held guards, positions cannot change.
                let rest_pos = index_of_single(
                    rest_guard.get(recipient),
                    |e: &RestEnvelope| e.id.as_str(),
                    id,
                );
                let store_pos = index_of_single(
                    store_guard.get(recipient),
                    |e: &Envelope| e.id.as_str(),
                    id,
                );
                SweepClassification::ExpiredQueued {
                    meta: active.clone(),
                    rest_pos,
                    store_pos,
                }
            }
        }
        Ok(ConsistentRecordState::Tombstoned { entry }) => {
            if entry.dedup_until > now {
                SweepClassification::Skip
            } else {
                SweepClassification::ExpiredTombstone {
                    entry: entry.clone(),
                }
            }
        }
        Err(drift) => fatal_sweep_invariant(
            ctx.worker_id,
            recipient,
            id,
            "pre_unlink_reverify",
            format!("classify_record_state drift: {}", drift.detail),
        ),
    };

    match classification {
        SweepClassification::Skip => {
            drop(tombstone_guard);
            drop(active_guard);
            drop(store_guard);
            drop(rest_guard);
            Ok(None)
        }
        SweepClassification::ExpiredQueued {
            meta,
            rest_pos,
            store_pos,
        } => {
            // (4) REDLINE P1a — durable Queued identity
            // re-verify BEFORE unlink. `verify_disk_footprint`
            // (inside classify) already covered path / is_file /
            // size; this closes the record-variant + durable
            // id/seq/body_hash/expires_at gaps.
            if let Err(drift) = verify_durable_queued(&meta.path, id, &meta) {
                fatal_sweep_invariant(
                    ctx.worker_id,
                    recipient,
                    id,
                    "pre_unlink_reverify",
                    format!("verify_durable_queued rejected: {}", drift.detail),
                );
            }

            let footprint = crate::capacity_ledger::RecordFootprint {
                kind: crate::capacity_ledger::RecordKind::Queued,
                disk_bytes: meta.disk_bytes,
                ram_bytes: meta.ram_bytes,
            };
            let disk_bytes = meta.disk_bytes;
            let record_path = meta.path.clone();

            // (5) Pre-durable unlink via the persistence
            // wrapper. Err → typed SweepError::Persistence, no
            // mutation. The wrapper accepts a per-call
            // `RemoveFault` (round-1 REDLINE amendment
            // symmetric to write_record_bytes / WriteFault),
            // so the ack-side persistence-branch tests can
            // drive this exact path via `ctx.test_seams`
            // without a global static and without a fault
            // injector at the fs level.
            if let Err(e) = persistence::remove_record_file(
                &record_path,
                resolve_remove_fault(ctx),
            ) {
                // Guards drop implicitly on the return path.
                return Err(SweepError::Persistence(e));
            }

            // **M5a-2 SIGKILL failpoint** — file unlinked from
            // canonical path via `remove_record_file` Ok; the
            // paired parent-dir fsync has NOT yet issued, and
            // neither has the ledger release / RAM removal.
            // SIGKILL invariant here (Queued branch):
            //   * `path` is absent after SIGKILL, and the ledger
            //     seeds from disk truth on the next boot so no
            //     drift lands.
            // SIGKILL only proves the absence of user-space
            // teardown; no claim about kernel-level power-loss
            // durability. The paired `fsync_dir` below is what
            // closes that window, and SIGKILL cannot emulate it.
            crate::failpoint!("sweep.after_unlink_before_parent_fsync");

            // (6) Parent-dir fsync — post remove_file Ok.
            if let Some(parent) = record_path.parent() {
                if let Err(e) = crate::atomic_write::fsync_dir(parent) {
                    fatal_sweep_invariant(
                        ctx.worker_id,
                        recipient,
                        id,
                        "post_unlink",
                        format!("fsync_dir failed after remove_file Ok: {e}"),
                    );
                }
            }

            // --- durable-unlink boundary ---

            // **M5a-2 SIGKILL failpoint** — disk state has
            // committed to the unlink (post-`fsync_dir`); ledger
            // release + RAM store removals have NOT yet applied.
            // SIGKILL invariant here (Queued branch): `path` is
            // absent after SIGKILL; boot replay observes the
            // missing record and the ledger seeds from disk
            // truth so no drift. The USER-SPACE abort variant of
            // this same boundary is covered by
            // `TEST_FORCE_SWEEP_POST_UNLINK_FATAL` below; this
            // failpoint adds the KERNEL-level SIGKILL variant.
            // SIGKILL only proves the absence of user-space
            // teardown — no power-loss claim.
            crate::failpoint!("sweep.after_parent_fsync_before_ledger_and_ram_release");

            // M3b-3b test seam for the reboot-replay subprocess
            // test — fires AFTER fsync_dir (durably unlinked)
            // and BEFORE ledger + RAM mutations, so the parent
            // test can inspect a state where disk has committed
            // to the unlink but RAM invariants would still show
            // the pre-sweep view had the process not aborted.
            #[cfg(test)]
            if TEST_FORCE_SWEEP_POST_UNLINK_FATAL
                .load(std::sync::atomic::Ordering::SeqCst)
            {
                fatal_sweep_invariant(
                    ctx.worker_id,
                    recipient,
                    id,
                    "post_unlink",
                    "TEST_FORCE_SWEEP_POST_UNLINK_FATAL fault seam fired".to_string(),
                );
            }

            // (7) Ledger release.
            if let Err(e) = ctx.capacity.transition(Some(footprint), None) {
                fatal_sweep_invariant(
                    ctx.worker_id,
                    recipient,
                    id,
                    "post_unlink",
                    format!("capacity.transition failed post-durable: {e:?}"),
                );
            }

            // (8) RAM store removals — checked bounds + id.
            remove_pos_or_fatal(
                rest_guard.get_mut(recipient),
                rest_pos,
                |e: &RestEnvelope| e.id.as_str(),
                id,
                ctx.worker_id,
                recipient,
                "rest_store",
            );
            remove_pos_or_fatal(
                store_guard.get_mut(recipient),
                store_pos,
                |e: &Envelope| e.id.as_str(),
                id,
                ctx.worker_id,
                recipient,
                "store",
            );

            // (9) Active index removal — checked.
            let bucket_after = match active_guard.get_mut(recipient) {
                Some(b) => b,
                None => fatal_sweep_invariant(
                    ctx.worker_id,
                    recipient,
                    id,
                    "post_unlink",
                    "active_index bucket missing at removal time".to_string(),
                ),
            };
            if bucket_after.remove(id).is_none() {
                fatal_sweep_invariant(
                    ctx.worker_id,
                    recipient,
                    id,
                    "post_unlink",
                    "active_index entry missing at removal time".to_string(),
                );
            }

            drop(tombstone_guard);
            drop(active_guard);
            drop(store_guard);
            drop(rest_guard);
            Ok(Some(SweptRecord::Queued { disk_bytes }))
        }
        SweepClassification::ExpiredTombstone { entry } => {
            // Durable identity for tombstones was already
            // verified inside classify_record_state via
            // verify_durable_tombstone.
            let footprint = crate::capacity_ledger::RecordFootprint {
                kind: crate::capacity_ledger::RecordKind::AckedTombstone,
                disk_bytes: entry.disk_bytes,
                ram_bytes: entry.ram_bytes,
            };
            let disk_bytes = entry.disk_bytes;
            let record_path = entry.path.clone();

            // Pre-durable unlink via the persistence wrapper —
            // round-1 REDLINE amendment. Same rationale as the
            // Queued branch above.
            if let Err(e) = persistence::remove_record_file(
                &record_path,
                resolve_remove_fault(ctx),
            ) {
                return Err(SweepError::Persistence(e));
            }

            // **M5a-2 SIGKILL failpoint** — Tombstone branch;
            // shares the name with the Queued branch's
            // pre-parent-fsync barrier. Two separate M5a-3
            // subprocess runs are required (Queued variant +
            // Tombstone variant) — one child cannot prove both
            // branches, since a single seeding shape only reaches
            // one of them. Same replacement-safe invariant as
            // the Queued branch: `path` is absent after SIGKILL;
            // ledger seeds from disk truth on next boot. No
            // power-loss claim.
            crate::failpoint!("sweep.after_unlink_before_parent_fsync");

            if let Some(parent) = record_path.parent() {
                if let Err(e) = crate::atomic_write::fsync_dir(parent) {
                    fatal_sweep_invariant(
                        ctx.worker_id,
                        recipient,
                        id,
                        "post_unlink",
                        format!("fsync_dir failed after remove_file Ok: {e}"),
                    );
                }
            }

            // --- durable-unlink boundary ---

            // **M5a-2 SIGKILL failpoint** — Tombstone branch;
            // shares the name with the Queued branch's
            // post-parent-fsync barrier. Two separate M5a-3
            // subprocess runs are required (Queued variant +
            // Tombstone variant). Invariant: `path` is absent
            // after SIGKILL; boot replay observes the missing
            // record; ledger + tombstone_dedup seed from disk
            // truth so no drift. No power-loss claim.
            crate::failpoint!("sweep.after_parent_fsync_before_ledger_and_ram_release");

            // M3b-3b test seam — symmetric copy of the Queued
            // branch's seam. Round-1 REDLINE flagged that the
            // tombstone branch had its own independent post-
            // durable sequence (`transition` + `dedup_removal`)
            // and no seam meant an invariant break there would
            // ship unnoticed. Fires at the same conceptual
            // point (post-fsync_dir, pre-mutation) so the
            // reboot-replay subprocess test exercises the exact
            // durable boundary for tombstones too.
            #[cfg(test)]
            if TEST_FORCE_SWEEP_POST_UNLINK_FATAL
                .load(std::sync::atomic::Ordering::SeqCst)
            {
                fatal_sweep_invariant(
                    ctx.worker_id,
                    recipient,
                    id,
                    "post_unlink",
                    "TEST_FORCE_SWEEP_POST_UNLINK_FATAL fault seam fired \
                     (tombstone branch)"
                        .to_string(),
                );
            }

            if let Err(e) = ctx.capacity.transition(Some(footprint), None) {
                fatal_sweep_invariant(
                    ctx.worker_id,
                    recipient,
                    id,
                    "post_unlink",
                    format!("capacity.transition failed post-durable: {e:?}"),
                );
            }

            let bucket_after = match tombstone_guard.get_mut(recipient) {
                Some(b) => b,
                None => fatal_sweep_invariant(
                    ctx.worker_id,
                    recipient,
                    id,
                    "post_unlink",
                    "tombstone_dedup bucket missing at removal time".to_string(),
                ),
            };
            if bucket_after.remove(id).is_none() {
                fatal_sweep_invariant(
                    ctx.worker_id,
                    recipient,
                    id,
                    "post_unlink",
                    "tombstone_dedup entry missing at removal time".to_string(),
                );
            }

            drop(tombstone_guard);
            drop(active_guard);
            drop(store_guard);
            drop(rest_guard);
            Ok(Some(SweptRecord::Tombstone { disk_bytes }))
        }
    }
}

/// **M3b-3a round-1**: find the sole entry in `bucket` whose
/// id matches. Returns `None` if the bucket is missing OR the
/// id is absent. Cardinality `> 1` is a RAM invariant break
/// → `fatal_sweep_invariant("pre_unlink_reverify")`.
///
/// Used by `sweep_one` to convert the raw RAM bucket into the
/// single-reference shape `classify_record_state` expects.
fn find_single_or_fatal<'a, T>(
    bucket: Option<&'a Vec<T>>,
    id_of: impl Fn(&T) -> &str,
    worker_id: u8,
    recipient: &str,
    id: &str,
    store_name: &'static str,
) -> Option<&'a T> {
    let Some(b) = bucket else {
        return None;
    };
    let count = b.iter().filter(|e| id_of(e) == id).count();
    if count > 1 {
        fatal_sweep_invariant(
            worker_id,
            recipient,
            id,
            "pre_unlink_reverify",
            format!("{store_name} cardinality {count} > 1 for (recipient, id)"),
        );
    }
    b.iter().find(|e| id_of(e) == id)
}

/// **M3b-3a round-1**: position of the sole matching entry.
/// Precondition: caller has already verified cardinality via
/// [`find_single_or_fatal`], so this cannot fail — a mismatch
/// here would indicate lock discipline broken between the two
/// calls, which under held write guards is impossible.
fn index_of_single<T>(
    bucket: Option<&Vec<T>>,
    id_of: impl Fn(&T) -> &str,
    id: &str,
) -> usize {
    bucket
        .and_then(|b| b.iter().position(|e| id_of(e) == id))
        .expect("caller verified single occurrence under held guard")
}

/// **M3b-3a**: post-durable `Vec::remove` with checked bounds
/// and id verification. Any mismatch →
/// `fatal_sweep_invariant("post_unlink")`.
fn remove_pos_or_fatal<T>(
    bucket_opt: Option<&mut Vec<T>>,
    pos: usize,
    id_of: impl Fn(&T) -> &str,
    id: &str,
    worker_id: u8,
    recipient: &str,
    store_name: &'static str,
) {
    let bucket = match bucket_opt {
        Some(b) => b,
        None => fatal_sweep_invariant(
            worker_id,
            recipient,
            id,
            "post_unlink",
            format!("{store_name} bucket missing at removal time"),
        ),
    };
    if pos >= bucket.len() {
        fatal_sweep_invariant(
            worker_id,
            recipient,
            id,
            "post_unlink",
            format!(
                "{store_name} pos {pos} out of bounds for bucket len {}",
                bucket.len()
            ),
        );
    }
    let removed = bucket.remove(pos);
    if id_of(&removed) != id {
        fatal_sweep_invariant(
            worker_id,
            recipient,
            id,
            "post_unlink",
            format!(
                "{store_name} removal id mismatch: removed={} expected={id}",
                id_of(&removed)
            ),
        );
    }
}

/// Dispatch a single op. Returns `Ok(())` on success, `Err(fatal)`
/// when the worker must exit fail-closed. Reply oneshots are
/// consumed inside each arm. Runs INSIDE the caller's
/// `catch_unwind` (F3 amendment).
async fn dispatch_one(ctx: &ActorContext, op: RestOp) -> Result<(), FatalReason> {
    // (F3) Shard-ownership check FIRST — inside caught scope. If
    // the recipient is empty (`worker_for` panics) or hashes to a
    // different worker, both surface as fatal (WorkerPanic caught
    // by worker_loop for the empty case, ShardRoutingInvariant for
    // the mismatch case here).
    let expected = worker_for(op.recipient(), ctx.worker_hash_key);
    if expected != usize::from(ctx.worker_id) {
        let reason = FatalReason::ShardRoutingInvariantViolation {
            worker_id: ctx.worker_id,
            expected_worker: expected,
        };
        notify_shard_mismatch(op, usize::from(ctx.worker_id), expected);
        return Err(reason);
    }

    match op {
        RestOp::Send {
            recipient,
            candidate,
            reply,
        } => {
            let outcome = do_send(ctx, &recipient, candidate).await;
            // Some SendErrors escalate to worker fatal (F2 for
            // SeqSaturation; ShardMismatch as defence-in-depth
            // even though the pre-dispatch check catches it).
            let fatal = match &outcome {
                Err(SendError::ShardMismatch { .. }) => {
                    Some(FatalReason::ShardRoutingInvariantViolation {
                        worker_id: ctx.worker_id,
                        expected_worker: worker_for(&recipient, ctx.worker_hash_key),
                    })
                }
                Err(SendError::SeqSaturation { counter }) => {
                    Some(FatalReason::SeqSpaceExhausted {
                        worker_id: ctx.worker_id,
                        counter: *counter,
                    })
                }
                // Round-3 F3: a runtime-detected out-of-range
                // generation is a same-severity fatal as counter
                // saturation, but ops paging needs to distinguish
                // "counter exhausted" from "generation itself is
                // invalid" — and a corrupted seq_counter loaded
                // as u64::MAX can no longer alias this cause via a
                // sentinel value. Use the dedicated variant
                // carrying the actual generation.
                Err(SendError::BootGenerationOutOfRange { generation }) => {
                    Some(FatalReason::SeqNamespaceInvalid {
                        worker_id: ctx.worker_id,
                        generation: *generation,
                    })
                }
                _ => None,
            };
            let _ = reply.send(outcome);
            if let Some(reason) = fatal {
                return Err(reason);
            }
            Ok(())
        }
        RestOp::Ack {
            recipient,
            envelope_id,
            reply,
        } => {
            let outcome = do_ack(ctx, &recipient, &envelope_id).await;
            // AckError::ShardMismatch (defense-in-depth) is a
            // fatal for the worker — mirror the SendError
            // handling above.
            let fatal = match &outcome {
                Err(AckError::ShardMismatch { .. }) => {
                    Some(FatalReason::ShardRoutingInvariantViolation {
                        worker_id: ctx.worker_id,
                        expected_worker: worker_for(&recipient, ctx.worker_hash_key),
                    })
                }
                _ => None,
            };
            let _ = reply.send(outcome);
            if let Some(reason) = fatal {
                return Err(reason);
            }
            Ok(())
        }
        RestOp::Sweep { recipient, reply } => {
            let outcome = do_sweep(ctx, &recipient).await;
            // SweepError::ShardMismatch (defense-in-depth) is a
            // fatal for the worker — mirror the Send/Ack shape.
            let fatal = match &outcome {
                Err(SweepError::ShardMismatch { .. }) => {
                    Some(FatalReason::ShardRoutingInvariantViolation {
                        worker_id: ctx.worker_id,
                        expected_worker: worker_for(&recipient, ctx.worker_hash_key),
                    })
                }
                _ => None,
            };
            let _ = reply.send(outcome);
            if let Some(reason) = fatal {
                return Err(reason);
            }
            Ok(())
        }
    }
}

/// Send the awaiting handler a typed shard-mismatch reply so it
/// doesn't hang on the oneshot forever. Called only from the
/// pre-dispatch fatal-exit path.
fn notify_shard_mismatch(op: RestOp, actual_worker: usize, expected_worker: usize) {
    match op {
        RestOp::Send { reply, .. } => {
            let _ = reply.send(Err(SendError::ShardMismatch {
                expected_worker,
                actual_worker,
            }));
        }
        RestOp::Ack { reply, .. } => {
            // The pre-dispatch shard-check has already fired
            // fatal above; give the awaiting handler a typed
            // shape rather than hanging on the oneshot.
            let _ = reply.send(Err(AckError::ShardMismatch {
                expected_worker,
                actual_worker,
            }));
        }
        RestOp::Sweep { reply, .. } => {
            let _ = reply.send(Err(SweepError::ShardMismatch {
                expected_worker,
                actual_worker,
            }));
        }
    }
}

/// Downcast the panic payload to a readable message.
fn extract_panic_message(payload: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&'static str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "<non-string panic payload>".to_string()
    }
}

// ─── M3b-1 round-2 F2: fail-stop helper for index invariant breaks ────

/// **M3b-1 round-2 F2**: single fail-stop entry point for
/// active-index invariant breaks.
///
/// Round-1 review flagged that the pre-round-2 shape (broadcast
/// fatal → `std::process::abort()`) did not leave a durable
/// diagnostic: the receiver might not run before abort, and the
/// [`ExistingEntry`] diagnostics were discarded. This helper
/// writes the FATAL line directly to stderr with the full
/// footprint payload BEFORE the abort so a crash-dump collector
/// always has evidence.
///
/// The `stage` argument names WHERE the invariant fired:
///   * `"pre_write_gate"` — cross-check between rest_store and
///     active_index caught drift BEFORE any disk mutation.
///   * `"post_write_assertion"` — belt-and-braces post-persist
///     insert (should be unreachable given the pre-write gate,
///     but kept as a final assertion).
///
/// Never returns.
fn fatal_active_index_invariant(
    worker_id: u8,
    recipient: &str,
    id: &str,
    stage: &'static str,
    detail: String,
) -> ! {
    // Reviewer's F2 corrective: eprintln! bypasses the tracing
    // subscriber (which buffers and may drop on abort). The FATAL:
    // prefix is what the crash-dump collector greps for.
    eprintln!(
        "FATAL: active_index invariant violation | worker_id={worker_id} | \
         recipient={recipient} | id={id} | stage={stage} | detail={detail}. \
         Aborting — post-detection state cannot be reasoned about."
    );
    std::process::abort();
}

/// Test-only fault seam: when set to true, `do_send`'s pre-write
/// consistency gate skips the natural `rest_store` lookup and
/// pretends the active_index is populated with an entry that
/// disagrees. Used by
/// [`tests::active_index_drift_pre_write_fatal_aborts_child_process`]
/// to exercise the fail-stop path in a subprocess.
#[cfg(test)]
pub(crate) static FORCE_ACTIVE_INDEX_DRIFT_FATAL: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

/// **M3b-2b-ii**: single fail-stop entry point for
/// `do_ack`-side invariant breaks. Same shape as
/// [`fatal_active_index_invariant`]: eprintln FATAL BEFORE the
/// abort so a crash-dump collector always has evidence, then
/// `std::process::abort()`.
///
/// `stage` values:
///   * `"pre_ack_gate"` — the optimistic consistency
///     classifier surfaced a drift.
///   * `"pre_commit_reverify"` — `prepare_active_to_tombstone_transition`
///     refused, OR the under-guards cardinality / classifier
///     revalidation disagreed with the optimistic phase.
///     Disk is UNCHANGED at this stage (nothing has been
///     durably committed yet) but the RAM invariant is broken
///     — continuing risks silent data confusion.
///   * `"post_commit"` — a step after `write_record_bytes`
///     succeeded failed. Disk is committed to the new
///     tombstone; the process cannot rebuild consistent RAM
///     without a full restart.
///
/// Never returns.
fn fatal_ack_invariant(
    worker_id: u8,
    recipient: &str,
    id: &str,
    stage: &'static str,
    detail: String,
) -> ! {
    eprintln!(
        "FATAL: ack invariant violation | worker_id={worker_id} | \
         recipient={recipient} | id={id} | stage={stage} | detail={detail}. \
         Aborting — post-detection state cannot be reasoned about."
    );
    std::process::abort();
}

/// **M3b-2b-ii** test-only fault seam: when true, `do_ack`
/// forces a `fatal_ack_invariant("post_commit", ...)` after
/// `write_record_bytes` returns Ok and BEFORE
/// `prepared.commit(...)` runs. The subprocess reboot-replay
/// test uses this to exercise the durable-commit boundary.
#[cfg(test)]
pub(crate) static TEST_FORCE_ACK_POST_COMMIT_FATAL: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

/// **M3b-3b** test-only fault seam: when true, `sweep_one`
/// forces a `fatal_sweep_invariant("post_unlink", ...)` AFTER
/// `remove_record_file` returns Ok and BEFORE
/// `capacity.transition` / RAM removal run. The subprocess
/// reboot-replay test uses this to exercise the durable-unlink
/// boundary (aborted mid-transaction, disk state committed to
/// the unlink, RAM never mutated).
///
/// Global static (not per-ctx) because the child process runs
/// the REAL production pipeline (`boot` → `from_boot` →
/// `spawn_worker_runtime`) — no shared `ActorContext` reaches
/// across the process boundary. Same rationale as
/// `TEST_FORCE_ACK_POST_COMMIT_FATAL`. Only checked from the
/// subprocess child, which is a fresh process with no parallel
/// test load.
#[cfg(test)]
pub(crate) static TEST_FORCE_SWEEP_POST_UNLINK_FATAL: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

/// **M3b-3a**: single fail-stop entry point for `do_sweep`-side
/// invariant breaks. Same shape as [`fatal_ack_invariant`] and
/// [`fatal_active_index_invariant`]: eprintln FATAL BEFORE the
/// abort so a crash-dump collector always has evidence, then
/// `std::process::abort()`.
///
/// `stage` values:
///   * `"pre_unlink_reverify"` — under-guards revalidation
///     found an inconsistency (missing bucket, cardinality
///     `!= 1`, meta divergence from the snapshot). Disk is
///     UNCHANGED at this stage but the RAM invariant is broken.
///   * `"post_unlink"` — `fs::remove_file` returned Ok and a
///     subsequent step (`fsync_dir`, ledger `transition`, RAM
///     bucket removal, index entry removal) failed. The record
///     file is durably gone (or transiently visible pending
///     directory fsync); RAM cleanup cannot proceed without
///     silent divergence, so the process aborts.
///
/// Never returns.
fn fatal_sweep_invariant(
    worker_id: u8,
    recipient: &str,
    id: &str,
    stage: &'static str,
    detail: String,
) -> ! {
    eprintln!(
        "FATAL: sweep invariant violation | worker_id={worker_id} | \
         recipient={recipient} | id={id} | stage={stage} | detail={detail}. \
         Aborting — post-detection state cannot be reasoned about."
    );
    std::process::abort();
}

/// **M3b-1 round-3 F1** + **M4-2b round-3 REDLINE**: prior-
/// record identity a positive consistency check returns to
/// `do_send`. `source` carries which projection the record
/// came from so `do_send` can surface the right
/// [`SendDisposition`] to the handler.
#[derive(Debug)]
struct PriorRecord {
    seq: u64,
    stored_body_hash: String,
    source: PriorRecordSource,
}

/// **PR-2 M4-2b round-3 REDLINE**: which durable projection a
/// `PriorRecord` was pulled from. Passes through to
/// [`SendDisposition`] so the handler can distinguish "still
/// awaiting recipient ack" from "already acked".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PriorRecordSource {
    Queued,
    Tombstoned,
}

/// **M3b-1 round-3 F1**: opaque drift diagnostic. Rendered into
/// the `detail` argument of [`fatal_active_index_invariant`] so
/// stderr carries the exact mismatch shape that fired.
#[derive(Debug)]
struct DriftDetail {
    detail: String,
}

/// **M3b-2a round-1 F1 + round-2 F1**: verify the on-disk
/// record at `path` is actually a valid `AckedTombstone`
/// whose fields match `entry`. Without this,
/// `verify_disk_footprint` alone would accept a queued record
/// left at the canonical path as "the tombstone" — the
/// round-1 reviewer's exact defect.
///
/// Refuses on:
///   * read failure (I/O, missing file);
///   * on-disk file exceeds `MAX_RECORD_BYTES`
///     (`persistence::read_record` metadata-checks BEFORE
///     allocating the buffer);
///   * parse failure (malformed JSON at the canonical path);
///   * unknown `version` (`persistence::read_record` refuses
///     `version != RECORD_VERSION` per locked v4 §8);
///   * variant is `Queued` (or anything other than
///     `AckedTombstone`);
///   * `id` / `seq` / `body_hash` / `dedup_until` disagree
///     with the RAM `TombstoneEntry`.
///
/// **Round-2 F1**: routed through
/// [`crate::persistence::read_record`] so the read is truly
/// bounded by `MAX_RECORD_BYTES` (round-1 called `fs::read`
/// which allocates before the cap check) AND the schema
/// version is validated. Reusing the persistence primitive
/// keeps this branch and the boot-loader path bound to the
/// same durable-record contract.
fn verify_durable_tombstone(
    path: &std::path::Path,
    expected_id: &str,
    entry: &TombstoneEntry,
) -> Result<(), DriftDetail> {
    let record = persistence::read_record(path).map_err(|e| DriftDetail {
        detail: match e {
            RecordReadError::Io(err) => {
                format!("tombstone durable read failed: {err} path={path:?}")
            }
            RecordReadError::Json(err) => {
                format!("tombstone durable parse failed: {err} path={path:?}")
            }
            RecordReadError::OversizeBytes { observed, cap } => format!(
                "tombstone durable file oversize: observed={observed} cap={cap} path={path:?}"
            ),
            RecordReadError::UnknownVersion { found, expected } => format!(
                "tombstone durable unknown schema version: found={found} expected={expected} path={path:?}"
            ),
        },
    })?;
    match record {
        PersistedRecord::AckedTombstone {
            id,
            seq,
            body_hash,
            dedup_until,
            ..
        } => {
            if id != expected_id {
                return Err(DriftDetail {
                    detail: format!(
                        "tombstone durable id mismatch: on_disk={id} expected={expected_id}"
                    ),
                });
            }
            if seq != entry.seq {
                return Err(DriftDetail {
                    detail: format!(
                        "tombstone durable seq mismatch: on_disk={seq} entry={}",
                        entry.seq
                    ),
                });
            }
            if body_hash != entry.body_hash {
                return Err(DriftDetail {
                    detail: format!(
                        "tombstone durable body_hash mismatch: on_disk={body_hash} entry={}",
                        entry.body_hash
                    ),
                });
            }
            if dedup_until != entry.dedup_until {
                return Err(DriftDetail {
                    detail: format!(
                        "tombstone durable dedup_until mismatch: on_disk={dedup_until} entry={}",
                        entry.dedup_until
                    ),
                });
            }
            Ok(())
        }
        PersistedRecord::Queued { .. } => Err(DriftDetail {
            detail: format!(
                "durable record at tombstone path is Queued, not AckedTombstone: path={path:?}"
            ),
        }),
    }
}

/// **M3b-3a round-1** (REDLINE P1): symmetric to
/// [`verify_durable_tombstone`] — reads the record at `path`
/// via [`persistence::read_record`] (bounded + version-checked)
/// and asserts it parses as `PersistedRecord::Queued` whose
/// `id / seq / body_hash / expires_at` fields match `meta`. Used
/// by `do_sweep` immediately before `remove_file` so a corrupted
/// `ActiveEntryMeta.path` (pointing at unrelated bytes, at a
/// tombstone, or at a schema-mismatched file) cannot direct the
/// sweep at the wrong target.
///
/// Callers convert `Err(DriftDetail)` into
/// `fatal_sweep_invariant("pre_unlink_reverify")` — disk is
/// still intact at that point (the check runs BEFORE
/// `remove_file`), so aborting preserves the bytes.
fn verify_durable_queued(
    path: &std::path::Path,
    expected_id: &str,
    meta: &ActiveEntryMeta,
) -> Result<(), DriftDetail> {
    let record = persistence::read_record(path).map_err(|e| DriftDetail {
        detail: match e {
            RecordReadError::Io(err) => {
                format!("queued durable read failed: {err} path={path:?}")
            }
            RecordReadError::Json(err) => {
                format!("queued durable parse failed: {err} path={path:?}")
            }
            RecordReadError::OversizeBytes { observed, cap } => format!(
                "queued durable file oversize: observed={observed} cap={cap} path={path:?}"
            ),
            RecordReadError::UnknownVersion { found, expected } => format!(
                "queued durable unknown schema version: found={found} expected={expected} path={path:?}"
            ),
        },
    })?;
    match record {
        PersistedRecord::Queued {
            id,
            seq,
            expires_at,
            body_hash,
            ..
        } => {
            if id != expected_id {
                return Err(DriftDetail {
                    detail: format!(
                        "queued durable id mismatch: on_disk={id} expected={expected_id}"
                    ),
                });
            }
            if seq != meta.seq {
                return Err(DriftDetail {
                    detail: format!(
                        "queued durable seq mismatch: on_disk={seq} meta={}",
                        meta.seq
                    ),
                });
            }
            if expires_at != meta.expires_at {
                return Err(DriftDetail {
                    detail: format!(
                        "queued durable expires_at mismatch: on_disk={expires_at} meta={}",
                        meta.expires_at
                    ),
                });
            }
            if body_hash != meta.body_hash {
                return Err(DriftDetail {
                    detail: format!(
                        "queued durable body_hash mismatch: on_disk={body_hash} meta={}",
                        meta.body_hash
                    ),
                });
            }
            Ok(())
        }
        PersistedRecord::AckedTombstone { .. } => Err(DriftDetail {
            detail: format!(
                "durable record at queued path is AckedTombstone, not Queued: path={path:?}"
            ),
        }),
    }
}

/// **M3b-2a**: reusable disk-footprint verifier for both
/// active-record paths (queued replay branch) and tombstone
/// paths (post-ack idempotent branch). Enforces reviewer's
/// corrective #3 — the on-disk file at `path` must match the
/// canonical record path AND be a regular file (not a symlink
/// or directory) AND its length must equal `expected_disk_bytes`.
///
/// Kept as a plain function so both consistency branches share
/// one contract; a future ACK sweep code path can call it too.
fn verify_disk_footprint(
    path: &std::path::Path,
    expected_disk_bytes: u64,
    state_dir: &std::path::Path,
    recipient: &str,
    candidate_id: &str,
) -> Result<(), DriftDetail> {
    let expected_path = record_path(state_dir, recipient, candidate_id);
    if path != expected_path {
        return Err(DriftDetail {
            detail: format!(
                "canonical path mismatch: entry.path={path:?} expected={expected_path:?}"
            ),
        });
    }
    match std::fs::symlink_metadata(path) {
        Err(e) => Err(DriftDetail {
            detail: format!("path stat failed: {e} path={path:?}"),
        }),
        Ok(md) => {
            let ft = md.file_type();
            if !ft.is_file() {
                return Err(DriftDetail {
                    detail: format!(
                        "path is not a regular file: file_type={ft:?} path={path:?}"
                    ),
                });
            }
            if md.len() != expected_disk_bytes {
                return Err(DriftDetail {
                    detail: format!(
                        "disk footprint drift: stat.len={} expected_disk_bytes={} path={path:?}",
                        md.len(),
                        expected_disk_bytes,
                    ),
                });
            }
            Ok(())
        }
    }
}

/// **M3b-2b-i corrective #3**: shared classified state of the
/// on-disk + RAM representations for a given
/// `(recipient, id)`, used by BOTH `do_send`'s dedup gate and
/// M3b-2b-ii's `do_ack`.
///
/// The classifier does ONE parse-and-validate pass; callers
/// pattern-match the outcome:
///   * `Absent` — no RAM or on-disk state → `do_send` new-send
///     path / `do_ack` NotFound.
///   * `Queued { rest, store, active }` — three RAM
///     representations agree on `seq` / `body_hash` /
///     `path` / `expires_at` / WS projection AND the on-disk
///     file's canonical path + regular-file + byte-length
///     footprint all match. `do_send` uses `active.seq` and
///     the derived body hash for idempotent replay vs
///     divergent-body 409; `do_ack` uses `active` for the
///     transition footprint.
///   * `Tombstoned { entry }` — only the tombstone dedup table
///     carries the id AND the on-disk record parses as a
///     valid `AckedTombstone` whose fields match the entry.
///     `do_send` returns idempotent replay; `do_ack` returns
///     `Idempotent { seq }`.
///
/// Borrowed references (`'a`) let callers keep ownership of
/// their `read` guards while inspecting the classified state.
#[derive(Debug)]
pub(crate) enum ConsistentRecordState<'a> {
    Absent,
    Queued {
        rest: &'a RestEnvelope,
        /// M3b-2b-ii `do_ack` uses the WS-store handle for the
        /// post-guard re-verification.
        #[allow(dead_code)]
        store: &'a Envelope,
        /// M3b-2b-ii `do_ack` uses the active meta as the
        /// `expected_active` argument to
        /// [`try_transition_active_to_tombstone`].
        #[allow(dead_code)]
        active: &'a ActiveEntryMeta,
    },
    Tombstoned {
        entry: &'a TombstoneEntry,
    },
}

/// **M3b-2b-i corrective #3**: single classifier reused by
/// `do_send` and (future M3b-2b-ii) `do_ack`.
///
/// Encapsulates every consistency check for
/// `(recipient, candidate_id)`:
///   * 4-way partition (`rest`, `store`, `active`, `tombstone`)
///     — all-absent → `Absent`; only-tombstone → `Tombstoned`;
///     three-queued → `Queued`; any other mix → `DriftDetail`;
///   * for the `Queued` branch: seq / body_hash / expires_at /
///     WS projection cross-check + on-disk footprint via
///     [`verify_disk_footprint`];
///   * for the `Tombstoned` branch: [`verify_disk_footprint`]
///     + [`verify_durable_tombstone`] (variant + id + seq +
///     body_hash + dedup_until match on-disk).
fn classify_record_state<'a>(
    rest_hit: Option<&'a RestEnvelope>,
    store_hit: Option<&'a Envelope>,
    active_hit: Option<&'a ActiveEntryMeta>,
    tombstone_hit: Option<&'a TombstoneEntry>,
    recipient: &str,
    candidate_id: &str,
    state_dir: &std::path::Path,
) -> Result<ConsistentRecordState<'a>, DriftDetail> {
    // (0) All absent.
    if rest_hit.is_none()
        && store_hit.is_none()
        && active_hit.is_none()
        && tombstone_hit.is_none()
    {
        return Ok(ConsistentRecordState::Absent);
    }
    // (1) Only tombstone present → verify durable, classify as
    // Tombstoned.
    if rest_hit.is_none()
        && store_hit.is_none()
        && active_hit.is_none()
        && tombstone_hit.is_some()
    {
        let tomb = tombstone_hit.expect("checked above");
        verify_disk_footprint(
            &tomb.path,
            tomb.disk_bytes,
            state_dir,
            recipient,
            candidate_id,
        )?;
        verify_durable_tombstone(&tomb.path, candidate_id, tomb)?;
        return Ok(ConsistentRecordState::Tombstoned { entry: tomb });
    }
    // (2) tombstone + any queued-side → double-state drift.
    if tombstone_hit.is_some() {
        return Err(DriftDetail {
            detail: format!(
                "tombstone_dedup + queued-side entries present simultaneously: rest_hit={} store_hit={} active_hit={} tombstone_hit=true",
                rest_hit.is_some(),
                store_hit.is_some(),
                active_hit.is_some(),
            ),
        });
    }
    // (3) rest+store+active OR partial (queued-side only, no
    // tombstone).
    match (rest_hit, store_hit, active_hit) {
        (Some(rest), Some(store), Some(active)) => {
            let stored_body_hash = compute_body_hash_hex(
                rest.sealed_sender.as_bytes(),
                rest.payload.as_bytes(),
            );
            if rest.seq != active.seq {
                return Err(DriftDetail {
                    detail: format!(
                        "seq mismatch: rest.seq={} active.seq={}",
                        rest.seq, active.seq
                    ),
                });
            }
            if stored_body_hash != active.body_hash {
                return Err(DriftDetail {
                    detail: format!(
                        "body_hash mismatch: rest_derived={} active={}",
                        stored_body_hash, active.body_hash
                    ),
                });
            }
            if active.expires_at != rest.expires_at {
                return Err(DriftDetail {
                    detail: format!(
                        "expires_at mismatch: rest.expires_at={} active.expires_at={}",
                        rest.expires_at, active.expires_at
                    ),
                });
            }
            if store.id != rest.id
                || store.to != recipient
                || store.from != rest.from
                || store.sealed_sender != rest.sealed_sender
                || store.payload != rest.payload
                || store.expires_at != rest.expires_at
            {
                return Err(DriftDetail {
                    detail: format!(
                        "WS store projection diverges from REST: store.id={} store.to={} store.from={:?} store.expires_at={}",
                        store.id, store.to, store.from, store.expires_at
                    ),
                });
            }
            verify_disk_footprint(
                &active.path,
                active.disk_bytes,
                state_dir,
                recipient,
                candidate_id,
            )?;
            Ok(ConsistentRecordState::Queued { rest, store, active })
        }
        // Any partial queued-side combination → drift.
        (r, s, a) => Err(DriftDetail {
            detail: format!(
                "partial state — rest_hit={} store_hit={} active_hit={}",
                r.is_some(),
                s.is_some(),
                a.is_some()
            ),
        }),
    }
}

/// **M3b-1 round-3 F1**: full-projection consistency check.
///
/// Round-2 shipped a gate that only compared `rest_store` and
/// `active_index`. Round-3 review flagged that the WS `store`
/// projection was NOT checked — a retry against a state where
/// only rest+active existed would return an idempotent replay
/// and permanently entrench the drift. This helper cross-checks
/// all three RAM representations plus the canonical path and
/// on-disk footprint.
///
/// Return shape:
///   * `Ok(None)` — none of the three carry the id → new-send
///     path proceeds.
///   * `Ok(Some(prior))` — all three agree; `do_send` compares
///     `prior.stored_body_hash` against the incoming candidate
///     for idempotent replay vs 409 divergent body.
///   * `Err(drift)` — any partial state OR any consistency
///     violation. `do_send` routes into
///     [`fatal_active_index_invariant`] with
///     `stage="pre_write_gate"` and the drift detail.
///
/// Extracted as a plain function so the coverage tests can
/// exercise every drift shape WITHOUT triggering a subprocess
/// abort.
fn check_pre_write_consistency(
    rest_hit: Option<&RestEnvelope>,
    store_hit: Option<&Envelope>,
    active_hit: Option<&ActiveEntryMeta>,
    tombstone_hit: Option<&TombstoneEntry>,
    recipient: &str,
    candidate_id: &str,
    state_dir: &std::path::Path,
) -> Result<Option<PriorRecord>, DriftDetail> {
    // **M3b-2b-i corrective #3**: delegate to the shared
    // `classify_record_state` classifier — same 4-way parse
    // + durable checks used by (M3b-2b-ii) `do_ack`. Map the
    // classified state to the two-outcome shape `do_send` needs
    // (`Option<PriorRecord>`).
    match classify_record_state(
        rest_hit,
        store_hit,
        active_hit,
        tombstone_hit,
        recipient,
        candidate_id,
        state_dir,
    )? {
        ConsistentRecordState::Absent => Ok(None),
        ConsistentRecordState::Queued { rest, .. } => {
            let stored_body_hash = compute_body_hash_hex(
                rest.sealed_sender.as_bytes(),
                rest.payload.as_bytes(),
            );
            Ok(Some(PriorRecord {
                seq: rest.seq,
                stored_body_hash,
                source: PriorRecordSource::Queued,
            }))
        }
        ConsistentRecordState::Tombstoned { entry } => Ok(Some(PriorRecord {
            seq: entry.seq,
            stored_body_hash: entry.body_hash.clone(),
            source: PriorRecordSource::Tombstoned,
        })),
    }
}

// ─── M3b-1 indices + boot compaction types ────────────────────────────

/// **M3b-1 round-1 F2**: returned by `try_insert_new` on both
/// indices when the `(recipient, id)` slot was already
/// occupied. Carries both the rejected pair and the
/// pre-existing meta so the caller can log the drift
/// diagnostically before fail-stopping.
#[derive(Debug)]
pub struct ExistingEntry<M> {
    pub rejected_id: String,
    pub rejected_meta: M,
    pub existing_meta: M,
}

/// **PR-2 M3b-1**: precise footprint of an active Queued record.
///
/// The boot loader gives us [`crate::boot_loader::LoadedRecord`]
/// but that shape does not carry the ledger-side accounting the
/// ACK / sweep paths need (RAM estimator, canonical body hash
/// on-disk). Round-8 reviewer flagged this: without an index,
/// M3b-2 / M3b-3 cannot precisely release the ledger footprint
/// for boot-loaded records — they would have to re-derive from
/// on-disk state or approximate.
#[derive(Debug, Clone)]
pub struct ActiveEntryMeta {
    pub path: PathBuf,
    pub seq: u64,
    pub body_hash: String,
    pub expires_at: u64,
    pub disk_bytes: u64,
    pub ram_bytes: u64,
}

/// **PR-2 M3b-1**: precise footprint of a surviving tombstone.
/// Mirrors [`ActiveEntryMeta`] but carries `dedup_until`
/// instead of `expires_at` (tombstones live to their
/// dedup horizon, not the original envelope expiry).
#[derive(Debug, Clone)]
pub struct TombstoneEntry {
    pub path: PathBuf,
    pub seq: u64,
    pub body_hash: String,
    pub dedup_until: u64,
    pub disk_bytes: u64,
    pub ram_bytes: u64,
}

/// **PR-2 M3b-1**: recipient → id → active meta.
///
/// Populated in [`WorkerRuntimeSpec::from_boot`] from the
/// surviving (non-expired) Queued records and extended by
/// [`do_send`] on every new send. M3b-2's `do_ack` will
/// consult it to know whether an envelope is currently Queued;
/// M3b-3's `do_sweep` will consult it for expiry checks. Uses
/// `parking_lot::RwLock` (sync, no `.await`) so the two-store
/// send transaction can hold `rest_store.write().await` +
/// `store.write().await` + this write guard together without
/// crossing another `.await` boundary (locked v4.2.1 §5 +
/// round-8 lock order `rest_store → store → active_index →
/// tombstones`).
pub struct ActiveRecordIndex {
    inner: parking_lot::RwLock<HashMap<String, HashMap<String, ActiveEntryMeta>>>,
}

impl ActiveRecordIndex {
    pub(crate) fn new() -> Self {
        Self {
            inner: parking_lot::RwLock::new(HashMap::new()),
        }
    }

    /// **M3b-1 round-1 F2**: checked insert that refuses to
    /// overwrite an existing `(recipient, id)` entry. Returns
    /// the rejected `(id, meta)` pair via
    /// [`ExistingEntry`] so the caller can report or fail-stop.
    ///
    /// Used by both the boot seed path (any `Err` surfaces as
    /// [`SpecError::DuplicateIdAtBootIndex`]) and by `do_send`
    /// (any `Err` after the disk write is a post-commit
    /// invariant break and triggers
    /// [`FatalReason::ActiveIndexInvariantViolation`] +
    /// `std::process::abort()` — reviewer's corrective #4:
    /// inconsistent committed state cannot be a regular
    /// `SendError`).
    pub(crate) fn try_insert_new(
        &self,
        recipient: &str,
        id: String,
        meta: ActiveEntryMeta,
    ) -> Result<(), ExistingEntry<ActiveEntryMeta>> {
        let mut guard = self.inner.write();
        let bucket = guard.entry(recipient.to_string()).or_default();
        match bucket.entry(id) {
            std::collections::hash_map::Entry::Vacant(v) => {
                v.insert(meta);
                Ok(())
            }
            std::collections::hash_map::Entry::Occupied(o) => Err(ExistingEntry {
                rejected_id: o.key().clone(),
                rejected_meta: meta,
                existing_meta: o.get().clone(),
            }),
        }
    }

    /// Read-only count of entries across all recipients. Used
    /// for observability and tests.
    pub fn count(&self) -> usize {
        self.inner.read().values().map(|m| m.len()).sum()
    }

    /// Read-only count for a single recipient.
    pub fn count_for_recipient(&self, recipient: &str) -> usize {
        self.inner
            .read()
            .get(recipient)
            .map(|m| m.len())
            .unwrap_or(0)
    }

    /// **PR-2 M4-3**: snapshot the set of recipients that have
    /// at least one entry in the index. Used by the sweep
    /// scheduler's per-recipient dispatch loop. Cloning happens
    /// under a short read guard — the returned `Vec` is owned
    /// by the caller and the guard is released before the
    /// caller iterates.
    pub(crate) fn recipient_keys(&self) -> Vec<String> {
        self.inner.read().keys().cloned().collect()
    }

    /// Snapshot the meta for one `(recipient, id)`. Used by
    /// M3b-2 `do_ack`.
    #[allow(dead_code)] // M3b-2 wires this in
    pub(crate) fn get(&self, recipient: &str, id: &str) -> Option<ActiveEntryMeta> {
        self.inner
            .read()
            .get(recipient)
            .and_then(|m| m.get(id).cloned())
    }
}

/// **PR-2 M3b-1**: recipient → id → tombstone meta.
///
/// Populated in [`WorkerRuntimeSpec::from_boot`] from the
/// surviving (past `dedup_until`) tombstones. M3b-2's `do_ack`
/// consults it for the idempotent-ack case and inserts new
/// tombstones after Queued → Tombstone transition; M3b-3's
/// `do_sweep` removes past-horizon entries. Sync lock: same
/// justification as [`ActiveRecordIndex`].
pub struct TombstoneDedupTable {
    inner: parking_lot::RwLock<HashMap<String, HashMap<String, TombstoneEntry>>>,
}

impl TombstoneDedupTable {
    pub(crate) fn new() -> Self {
        Self {
            inner: parking_lot::RwLock::new(HashMap::new()),
        }
    }

    /// **M3b-1 round-1 F2**: same shape as
    /// [`ActiveRecordIndex::try_insert_new`] — refuses to
    /// overwrite. Boot-seed duplicates surface as
    /// [`SpecError::DuplicateIdAtBootIndex`]; runtime
    /// duplicates (M3b-2 `do_ack`) will fail-stop.
    pub(crate) fn try_insert_new(
        &self,
        recipient: &str,
        id: String,
        entry: TombstoneEntry,
    ) -> Result<(), ExistingEntry<TombstoneEntry>> {
        let mut guard = self.inner.write();
        let bucket = guard.entry(recipient.to_string()).or_default();
        match bucket.entry(id) {
            std::collections::hash_map::Entry::Vacant(v) => {
                v.insert(entry);
                Ok(())
            }
            std::collections::hash_map::Entry::Occupied(o) => Err(ExistingEntry {
                rejected_id: o.key().clone(),
                rejected_meta: entry,
                existing_meta: o.get().clone(),
            }),
        }
    }

    pub fn count(&self) -> usize {
        self.inner.read().values().map(|m| m.len()).sum()
    }

    pub fn count_for_recipient(&self, recipient: &str) -> usize {
        self.inner
            .read()
            .get(recipient)
            .map(|m| m.len())
            .unwrap_or(0)
    }

    /// **PR-2 M4-3**: snapshot the set of recipients that have
    /// at least one tombstone entry. Symmetric with
    /// [`ActiveRecordIndex::recipient_keys`]. Used by the sweep
    /// scheduler.
    pub(crate) fn recipient_keys(&self) -> Vec<String> {
        self.inner.read().keys().cloned().collect()
    }

    #[allow(dead_code)] // M3b-2 wires this in
    pub(crate) fn get(&self, recipient: &str, id: &str) -> Option<TombstoneEntry> {
        self.inner
            .read()
            .get(recipient)
            .and_then(|m| m.get(id).cloned())
    }
}

/// **PR-2 M3b-1**: observable output of the boot seed phase.
///
/// Reviewer's corrective #3: a single `now_epoch_secs` snapshot
/// drives every expiry decision in one pass; the counters below
/// tell the operator exactly how much of the on-disk queue was
/// compacted before the process resumed serving traffic.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct BootSeedStats {
    pub queued_seeded: u64,
    pub queued_expired_swept: u64,
    pub tombstones_seeded: u64,
    pub tombstones_expired_swept: u64,
    pub disk_reclaimed_bytes: u64,
}

// ─── M3b-2b-i cross-index transition primitive (corrective #5) ────────

/// Errors returned by [`try_transition_active_to_tombstone`].
///
/// **M3b-2b-i corrective #5**: this is the ONLY refusal
/// surface — after a `Ok(...)` return, the two indices are
/// atomically transitioned. There is no rollback path; if
/// M3b-2b-ii's `do_ack` calls this after the durable disk
/// commit and gets `Err`, the caller MUST `fatal_ack_invariant`
/// / abort, not attempt to rewrite state.
#[allow(dead_code)] // M3b-2b-ii wires do_ack against this
#[derive(Debug)]
pub(crate) enum CrossIndexTransitionError {
    /// `active_index` did not carry the expected `(recipient,
    /// id)` entry. Either the entry disappeared between the
    /// read-gate and this call (impossible under actor
    /// serialization) or was never present.
    ActiveMissing,
    /// `active_index` had a `(recipient, id)` entry but its
    /// fields disagree with `expected`. Carries both sides for
    /// diagnostics.
    ActiveDiverged {
        existing: ActiveEntryMeta,
        expected: ActiveEntryMeta,
    },
    /// `tombstone_dedup` already carries a `(recipient, id)`
    /// entry — inserting the new tombstone would overwrite it.
    /// The transition refuses; the caller decides whether to
    /// fail-stop (post-commit) or return a typed error
    /// (pre-commit).
    TombstoneAlreadyPresent { existing: TombstoneEntry },
}

/// **M3b-2b-i round-1 F1**: two-phase primitive for the atomic
/// active → tombstone transition. Held across the durable
/// ACK transaction.
///
/// Round-0 shipped a single-shot `try_transition_active_to_tombstone`
/// that acquired guards, validated, and mutated in one call.
/// Round-1 reviewer flagged that this shape is incompatible
/// with the approved M3b-2b-ii transaction order:
///   * calling before `write_record_bytes` mutates RAM before
///     durable state;
///   * calling after `write_record_bytes` re-acquires locks
///     AFTER the disk commit, leaving a TOCTOU gap between
///     revalidation and durable replacement.
///
/// The two-phase shape closes both: `prepare_active_to_tombstone_transition`
/// acquires the write guards on BOTH indices AND validates
/// preconditions. The returned [`PreparedTransition`] holds
/// both guards for its lifetime — the caller performs
/// serialize / disk write / ledger transition WITH the guards
/// still held, then calls `commit(new_tombstone)` which
/// synchronously swaps the indices and drops the guards.
///
/// Between `prepare` and `commit` the caller MAY do sync work
/// (serialize, `write_record_bytes`, `capacity.transition`)
/// but MUST NOT `.await` — `parking_lot` guards cannot cross
/// suspend points.
///
/// Dropping the prepared object without `commit` releases both
/// guards without any index mutation — safe fail-close on any
/// error path between prepare and commit.
#[allow(dead_code)] // M3b-2b-ii wires do_ack against this
pub(crate) struct PreparedTransition<'a> {
    active_guard: parking_lot::RwLockWriteGuard<
        'a,
        HashMap<String, HashMap<String, ActiveEntryMeta>>,
    >,
    tombstone_guard: parking_lot::RwLockWriteGuard<
        'a,
        HashMap<String, HashMap<String, TombstoneEntry>>,
    >,
    recipient: String,
    id: String,
    validated_active: ActiveEntryMeta,
}

impl<'a> PreparedTransition<'a> {
    /// Copy of the active meta the primitive validated at
    /// prepare time. `do_ack` uses this for the ledger
    /// transition's `from` footprint and for the tombstone
    /// entry's `seq` / `body_hash`.
    #[allow(dead_code)] // M3b-2b-ii wires do_ack against this
    pub(crate) fn active(&self) -> &ActiveEntryMeta {
        &self.validated_active
    }

    /// Consume the prepared transition and synchronously swap
    /// the two indices under the still-held write guards.
    /// Returns the removed active meta on success.
    ///
    /// Round-1 F1: no `.await`, no fallible I/O — the guards
    /// have never been released since `prepare`, so this call
    /// cannot race a competing writer.
    ///
    /// **M3b-2b-ii amendment round-1** (REDLINE P1-1): shape is
    /// `Result<ActiveEntryMeta, CommitError>`, NOT
    /// `-> ActiveEntryMeta` with internal `.expect(...)` panics.
    /// The prior shape's expects would unwind into the caller's
    /// `catch_unwind` → `FatalReason::WorkerPanic`; after the
    /// durable write that violates the M3b-2 contract of
    /// "post-commit invariant break → `abort()`". `do_ack`
    /// routes any `Err` from here directly to
    /// `fatal_ack_invariant("post_commit", ...)`.
    ///
    /// Mutation order is chosen so a returned `Err` guarantees
    /// no partial mutation: `active_bucket.remove(&self.id)`
    /// returning `None` is a no-op on the bucket; only after we
    /// observe `Some(removed)` do we insert into
    /// `tombstone_guard`. `HashMap::insert` is infallible, so
    /// once past the `None` check both mutations complete.
    #[allow(dead_code)] // M3b-2b-ii wires do_ack against this
    pub(crate) fn commit(
        mut self,
        new_tombstone: TombstoneEntry,
    ) -> Result<ActiveEntryMeta, CommitError> {
        let active_bucket = match self.active_guard.get_mut(&self.recipient) {
            Some(b) => b,
            None => return Err(CommitError::ActiveBucketMissing),
        };
        let removed = match active_bucket.remove(&self.id) {
            Some(m) => m,
            None => return Err(CommitError::ActiveEntryMissing),
        };
        // Post-remove: `HashMap::insert` is infallible; no
        // partial-mutation window between here and function
        // return.
        self.tombstone_guard
            .entry(self.recipient)
            .or_default()
            .insert(self.id, new_tombstone);
        // Guards drop with `self` at end of scope.
        Ok(removed)
    }
}

/// **M3b-2b-ii amendment round-1** (REDLINE P1-1): typed
/// invariant-break surface for [`PreparedTransition::commit`].
/// Both variants describe a state the prepare-time validation
/// contract already ruled out; observing one means RAM has
/// been mutated out from under us between prepare and commit
/// despite the still-held write guards, which is impossible
/// under the current locking discipline. `do_ack` routes both
/// to `fatal_ack_invariant("post_commit", ...)` → abort.
#[derive(Debug)]
#[allow(dead_code)] // fields read via Debug in fatal_ack_invariant
pub(crate) enum CommitError {
    /// The recipient bucket was gone from the active index at
    /// commit time.
    ActiveBucketMissing,
    /// The recipient bucket existed but did not contain the
    /// expected id at commit time.
    ActiveEntryMissing,
}

/// **M3b-2b-i round-1 F1**: acquire both index write guards
/// simultaneously AND validate preconditions. On `Ok` the
/// caller owns a [`PreparedTransition`] that keeps the guards
/// alive through the durable disk write and ledger transition.
///
/// Failure semantics:
///   * `ActiveMissing` / `ActiveDiverged` /
///     `TombstoneAlreadyPresent` — no mutation. Guards drop
///     without any index change.
///
/// **Correctives from the M3b-2b scope approval that this
/// primitive locks in:**
///   * #4 (`No .await after durable commit`): the guards are
///     `parking_lot::RwLock` and cannot cross `.await`, so a
///     `do_ack` that tries to await between `prepare` and
///     `commit` fails at compile time.
///   * #5 (`Cross-index transition without rollback`): the
///     mutation happens inside `commit` under the still-held
///     guards — post-commit invariant violations go to
///     `fatal_ack_invariant` in M3b-2b-ii, never back to a
///     rollback attempt.
#[allow(dead_code)] // M3b-2b-ii wires do_ack against this
pub(crate) fn prepare_active_to_tombstone_transition<'a>(
    active_index: &'a ActiveRecordIndex,
    tombstone_dedup: &'a TombstoneDedupTable,
    recipient: &str,
    id: &str,
    expected_active: &ActiveEntryMeta,
) -> Result<PreparedTransition<'a>, CrossIndexTransitionError> {
    let active_guard = active_index.inner.write();
    let tombstone_guard = tombstone_dedup.inner.write();

    // (1) Active must be Occupied and match `expected`
    // field-for-field.
    let active_bucket = match active_guard.get(recipient) {
        Some(b) => b,
        None => return Err(CrossIndexTransitionError::ActiveMissing),
    };
    let current_active = match active_bucket.get(id) {
        Some(m) => m,
        None => return Err(CrossIndexTransitionError::ActiveMissing),
    };
    if current_active.path != expected_active.path
        || current_active.seq != expected_active.seq
        || current_active.body_hash != expected_active.body_hash
        || current_active.disk_bytes != expected_active.disk_bytes
        || current_active.ram_bytes != expected_active.ram_bytes
        || current_active.expires_at != expected_active.expires_at
    {
        return Err(CrossIndexTransitionError::ActiveDiverged {
            existing: current_active.clone(),
            expected: expected_active.clone(),
        });
    }

    // (2) Tombstone must be Vacant.
    if let Some(existing) = tombstone_guard.get(recipient).and_then(|m| m.get(id)) {
        return Err(CrossIndexTransitionError::TombstoneAlreadyPresent {
            existing: existing.clone(),
        });
    }

    let validated_active = current_active.clone();
    Ok(PreparedTransition {
        active_guard,
        tombstone_guard,
        recipient: recipient.to_string(),
        id: id.to_string(),
        validated_active,
    })
}

// ─── M3b-2b-i shared RAM estimator (corrective #4) ────────────────────

/// **M3b-2b-i corrective #4**: single source of truth for the
/// per-record RAM footprint estimate.
///
/// Round-1 review flagged that if M3b-1 boot seed and future
/// M3b-2b-ii ACK transition computed RAM independently, a
/// restart could yield different ledger totals for the same
/// record — `active.ram_bytes` seeded at boot would not equal
/// the tombstone `ram_bytes` M3b-2b-ii inserts after ACK.
///
/// One shared helper eliminates the divergence. Formula
/// mirrors the round-4 F4 conservative estimator: two RAM
/// projections carry the disk bytes + a per-projection
/// struct-overhead pad.
pub(crate) fn record_ram_estimate(disk_bytes: u64) -> u64 {
    disk_bytes
        .saturating_mul(2)
        .saturating_add(RAM_STRUCT_OVERHEAD_BYTES.saturating_mul(2))
}

// ─── M3b-2b-i clock abstraction (corrective #2) ───────────────────────

/// Error surface of [`ClockSource::now_epoch_secs`].
///
/// A distinct type from [`AckError`] so the mapping site
/// (M3b-2b-ii's `do_ack`) turns it into the right variant
/// while unit tests exercise the clock directly.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClockError {
    /// System clock returned a value earlier than `UNIX_EPOCH`.
    /// `do_ack` refuses rather than compute `dedup_until` from
    /// an unusable "now".
    BeforeEpoch,
}

impl std::fmt::Display for ClockError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ClockError::BeforeEpoch => write!(f, "system clock returned pre-epoch value"),
        }
    }
}

impl std::error::Error for ClockError {}

/// **M3b-2b-i corrective #2**: per-context typed clock seam.
///
/// Round-1 review flagged the round-0 plan for using an
/// `AtomicU64` with `0` as a "use real clock" sentinel — but
/// unix epoch `0` (1970-01-01) is a legitimate wall-clock
/// value that would silently override to the real clock, AND
/// a global atomic races across parallel tests.
///
/// This enum:
///   * `Production` — always calls `SystemTime::now()`; a
///     pre-epoch return is a typed error, NOT a fallback;
///   * `Fixed(u64)` — test-only, hands back the fixed value
///     including `0`, no ambiguity;
///   * `BeforeEpoch` — test-only, always returns the typed
///     error so tests exercise the fail-stop branch without
///     mocking `SystemTime`.
///
/// The value is `Copy` (single variant tag + up to 8 bytes),
/// so `ActorContext` stores it inline and workers clone
/// cheaply.
#[derive(Debug, Clone, Copy)]
pub enum ClockSource {
    Production,
    #[cfg(test)]
    Fixed(u64),
    #[cfg(test)]
    BeforeEpoch,
}

impl ClockSource {
    pub fn now_epoch_secs(&self) -> Result<u64, ClockError> {
        match self {
            ClockSource::Production => std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .map_err(|_| ClockError::BeforeEpoch),
            #[cfg(test)]
            ClockSource::Fixed(v) => Ok(*v),
            #[cfg(test)]
            ClockSource::BeforeEpoch => Err(ClockError::BeforeEpoch),
        }
    }
}

// ─── Public runtime factory (round-3 F1, round-4 F1/F5) ───────────────

/// Narrow, typed inputs the shard-worker fleet needs to boot.
///
/// **Round-4 F1** (this revision): FIELDS ARE PRIVATE. External
/// callers construct the spec via [`WorkerRuntimeSpec::from_boot`],
/// which takes a [`BootLoaderResult`] as an opaque token
/// proving M2 preflight + replay ran, plus the AppState-owned
/// shared references. The old `pub` fields let a hostile M4
/// wiring accident pass an arbitrary `state_dir` + generation +
/// empty ledger + mismatched RAM stores past the factory's
/// per-field checks — the private-field shape closes that
/// vector at the type level.
///
/// The `worker_hash_key` is generated INSIDE the constructor via
/// [`WorkerHashKey::from_process_rng`]; callers cannot pass one
/// in, so they cannot retain a copy or feed a predictable/mis-
/// keyed hasher.
pub struct WorkerRuntimeSpec {
    rest_store: Arc<RwLock<HashMap<String, Vec<RestEnvelope>>>>,
    store: Arc<RwLock<HashMap<String, Vec<Envelope>>>>,
    state_dir: PathBuf,
    max_envelopes_per_recipient: usize,
    boot_generation: u32,
    seq_mac_root_key: Arc<SeqMacRootKey>,
    capacity: Arc<GlobalCapacityGate>,
    fatal: broadcast::Sender<FatalReason>,
    worker_hash_key: WorkerHashKey,
    /// **M3b-1**: precise per-record accounting for surviving
    /// Queued records. Populated in `from_boot` from boot
    /// records; extended by [`do_send`] on every new send.
    /// M3b-2 `do_ack` and M3b-3 `do_sweep` will consult it.
    active_index: Arc<ActiveRecordIndex>,
    /// **M3b-1**: dedup table for surviving tombstones.
    /// Populated in `from_boot`; consumed by M3b-2 `do_ack` for
    /// the idempotent-ack case and by M3b-3 `do_sweep` for
    /// past-`dedup_until` removal.
    tombstone_dedup: Arc<TombstoneDedupTable>,
    /// **M3b-1**: observability output — how many records were
    /// seeded vs compacted at boot. Frozen once `from_boot`
    /// returns.
    boot_seed_stats: BootSeedStats,
    /// **M3b-2a**: tombstone-dedup horizon carried through
    /// from the boot config. Plumbed into every `ActorContext`
    /// so M3b-2b's `do_ack` sees the same horizon the operator
    /// pinned at boot.
    tombstone_config: crate::tombstone_config::TombstoneConfig,
    /// **M3b-2b-i**: clock source. Production creates the spec
    /// with `ClockSource::Production`; tests select fixed or
    /// pre-epoch variants.
    clock: ClockSource,
}

/// Errors produced by [`WorkerRuntimeSpec::from_boot`] before
/// any worker is spawned.
#[derive(Debug)]
pub enum SpecError {
    /// **Round-5 F2**: the provided `seq_mac_root_key` does not
    /// match `boot.meta.seq_mac_key_fingerprint`.
    SeqMacKeyFingerprintMismatch {
        boot_fingerprint: String,
        provided_fingerprint: String,
    },
    /// **Round-6 F1 + round-7 F1**: seeding the capacity ledger
    /// from `boot.records` overflowed the running gate's caps.
    /// The persisted queue on disk is larger than the caps the
    /// running process was configured with — operator must raise
    /// caps or archive/prune before restart. Every reservation
    /// taken during THIS call rolls back automatically via RAII
    /// drop when the newly-created gate is discarded (round-7
    /// F2: the factory owns the gate, so partial mutations
    /// never leak to an outside Arc).
    CapacitySeedFailed {
        cause: CapacityError,
        kind: RecordKind,
        queued_seeded: u64,
        tombstones_seeded: u64,
    },
    /// **Round-8 F2**: a recipient's persisted queue size
    /// exceeds the running `max_envelopes_per_recipient`. Round-7
    /// checked only the global `CapacityCaps.max_envelopes`
    /// bound — a persisted recipient with per-recipient count
    /// above the running cap would silently load. Operator
    /// must raise the cap or archive the recipient before
    /// restart.
    PerRecipientCapExceededAtBoot {
        recipient: String,
        observed: usize,
        cap: usize,
    },
    /// **Round-8 F1**: two `Queued` records for the same
    /// recipient share a `seq` value. This should be impossible
    /// under a correct boot loader + the per-boot monotonic
    /// counter (locked design v4 §13 Q1) — treat it as a hard
    /// corruption signal and refuse the seed rather than let
    /// `since_seq`-based poll semantics silently lose a
    /// record.
    DuplicateSeqAtBoot { recipient: String, seq: u64 },
    /// **M3b-1**: boot compaction failed to durably unlink an
    /// expired record before seeding. The reviewer's corrective
    /// #1 says expired records cannot merely be filtered out of
    /// RAM — the file must be removed with a durable
    /// unlink+parent-fsync before boot resumes, or the record
    /// will still occupy disk and never enter a future sweep.
    /// Failure here aborts boot; the operator resolves the disk
    /// issue and restarts.
    BootCompactionFailed {
        path: PathBuf,
        cause: std::io::Error,
    },
    /// **M3b-1 round-1 F1**: `SystemTime::now()` returned a
    /// value earlier than `UNIX_EPOCH` — the host clock is
    /// misconfigured. Rather than run compaction with an
    /// arbitrary "now" (which the round-1 reviewer flagged as
    /// destructive: `now = 0` classifies nothing expired,
    /// `now = u64::MAX` classifies everything expired and would
    /// unlink the entire durable queue), refuse boot with a
    /// typed error so the operator fixes the clock first.
    SystemClockBeforeEpoch,
    /// **M3b-1 round-1 F2**: two records collided on
    /// `(recipient, id)` at boot seed — either the persisted
    /// queue is corrupt or the boot loader path-uniqueness
    /// invariant broke. `HashMap::insert` would have silently
    /// overwritten the earlier footprint; the checked
    /// `try_insert_new` refuses fail-closed so a later ACK
    /// cannot release the wrong on-disk path.
    DuplicateIdAtBootIndex { recipient: String, id: String },
}

impl std::fmt::Display for SpecError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SpecError::SeqMacKeyFingerprintMismatch {
                boot_fingerprint,
                provided_fingerprint,
            } => write!(
                f,
                "seq_mac_key fingerprint mismatch: boot meta pinned {boot_fingerprint}, provided root key hashes to {provided_fingerprint}"
            ),
            SpecError::CapacitySeedFailed {
                cause,
                kind,
                queued_seeded,
                tombstones_seeded,
            } => write!(
                f,
                "capacity seed from boot.records failed on {kind:?} record after {queued_seeded} queued + {tombstones_seeded} tombstone(s) processed: {cause}"
            ),
            SpecError::PerRecipientCapExceededAtBoot {
                recipient,
                observed,
                cap,
            } => write!(
                f,
                "recipient {recipient} carries {observed} persisted queued records but max_envelopes_per_recipient={cap}"
            ),
            SpecError::DuplicateSeqAtBoot { recipient, seq } => write!(
                f,
                "recipient {recipient} has two Queued records at seq={seq} — persisted queue is corrupt"
            ),
            SpecError::BootCompactionFailed { path, cause } => write!(
                f,
                "boot compaction failed to durably unlink expired record {path:?}: {cause}"
            ),
            SpecError::SystemClockBeforeEpoch => write!(
                f,
                "system clock returned a value before UNIX_EPOCH — refusing compaction with an unusable now"
            ),
            SpecError::DuplicateIdAtBootIndex { recipient, id } => write!(
                f,
                "boot seed found two records for (recipient={recipient}, id={id}) — persisted queue is corrupt"
            ),
        }
    }
}

impl std::error::Error for SpecError {}

impl WorkerRuntimeSpec {
    /// Build a spec from a completed M2 boot result.
    ///
    /// **Round-6 F1** amendments layered on the round-5 shape:
    ///   * `rest_store` / `store` are NO LONGER PARAMETERS. The
    ///     factory constructs them internally, populates them
    ///     from `boot.records()`, and hands the resulting Arcs
    ///     to the [`WorkerRuntime`] (accessible via
    ///     [`WorkerRuntime::rest_store`] +
    ///     [`WorkerRuntime::store`] so M4 wiring can share them
    ///     with the rest of `AppState`). Callers can no longer
    ///     hand the factory empty stores while the on-disk
    ///     queue holds records — the persisted queue is
    ///     visible in-memory by construction.
    ///   * The `capacity` gate is SEEDED from the boot's Queued
    ///     records inside the same call. Each record's
    ///     `disk_bytes` is turned into a
    ///     `capacity.reserve_send(...)` + `commit()` so the
    ///     ledger reflects the on-disk truth before the first
    ///     new send lands. Seeding failure (caps too small for
    ///     the persisted queue) returns
    ///     [`SpecError::CapacitySeedFailed`] with the reservations-
    ///     so-far auto-rolled back via RAII.
    ///   * `boot: BootLoaderResult` is consumed by value via
    ///     [`BootLoaderResult::into_parts`] (round-5 F2). Fields
    ///     are private on `BootLoaderResult`, so this is the
    ///     ONLY way to reach them.
    ///
    /// The fingerprint check, generation binding, `state_dir`
    /// binding, and boot-generated `WorkerHashKey` from round-5
    /// F2 all remain.
    /// **Round-7 F1 + F2** signature changes:
    ///   * `capacity: Arc<GlobalCapacityGate>` is replaced with
    ///     `caps: CapacityCaps`. The factory creates the gate
    ///     itself so its counter set starts at zero — a caller
    ///     cannot slip a dirty ledger past the seeding step
    ///     (round-6 accepted a preformed gate and had no way
    ///     to know it was clean).
    ///   * Tombstones from `boot.records()` are seeded into the
    ///     ledger's `tombstone_bytes` counter AND preserved in
    ///     [`WorkerRuntime::tombstones`] for M3b to consume when
    ///     it wires the dedup table. Round-6 dropped tombstones
    ///     silently — a restart could have lost the durable
    ///     dedup contract.
    ///
    /// The fingerprint check, generation binding, state_dir
    /// binding, boot-random `WorkerHashKey`, and RAII rollback
    /// of failed queued reservations all remain from prior
    /// revisions.
    /// **M3b-1** compaction shape + **round-1 F1** clock seam:
    ///   * The seed phase runs a compaction step FIRST: expired
    ///     Queued records and past-`dedup_until` tombstones are
    ///     durably unlinked (unlink + parent-fsync) before the
    ///     cap-check / ledger-seed phase sees them. An unlink
    ///     failure aborts boot with
    ///     [`SpecError::BootCompactionFailed`] — reviewer's
    ///     M3b-1 corrective #1.
    ///   * **Round-1 F1**: the wall-clock snapshot is captured
    ///     INSIDE this call via `SystemTime::now()`, once. The
    ///     round-1 shape exposed `now_epoch_secs` as a public
    ///     parameter — a hostile or mis-configured caller
    ///     could pass milliseconds or `u64::MAX` and destroy
    ///     the durable queue in one call. Public callers no
    ///     longer supply the clock; deterministic unit tests
    ///     use [`WorkerRuntimeSpec::from_boot_at`] which is
    ///     `#[cfg(test)]`-gated.
    ///   * A pre-`UNIX_EPOCH` system clock returns
    ///     [`SpecError::SystemClockBeforeEpoch`] instead of
    ///     running compaction with an arbitrary "now".
    pub fn from_boot(
        boot: BootLoaderResult,
        max_envelopes_per_recipient: usize,
        seq_mac_root_key: Arc<SeqMacRootKey>,
        caps: CapacityCaps,
        fatal: broadcast::Sender<FatalReason>,
    ) -> Result<Self, SpecError> {
        let now_epoch_secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|_| SpecError::SystemClockBeforeEpoch)?
            .as_secs();
        Self::from_boot_impl(
            boot,
            max_envelopes_per_recipient,
            seq_mac_root_key,
            caps,
            fatal,
            now_epoch_secs,
            ClockSource::Production,
        )
    }

    /// **M3b-1 round-1 F1**: same as [`WorkerRuntimeSpec::from_boot`]
    /// but the clock snapshot is supplied by the caller.
    /// `#[cfg(test)] pub(crate)` — reachable ONLY from the
    /// library's own tests; a production binary cannot even
    /// name the function. Integration tests exercise the real
    /// [`WorkerRuntimeSpec::from_boot`] path against a fresh
    /// state dir (empty records → no compaction touches the
    /// clock).
    #[cfg(test)]
    pub(crate) fn from_boot_at(
        boot: BootLoaderResult,
        max_envelopes_per_recipient: usize,
        seq_mac_root_key: Arc<SeqMacRootKey>,
        caps: CapacityCaps,
        fatal: broadcast::Sender<FatalReason>,
        now_epoch_secs: u64,
    ) -> Result<Self, SpecError> {
        Self::from_boot_impl(
            boot,
            max_envelopes_per_recipient,
            seq_mac_root_key,
            caps,
            fatal,
            now_epoch_secs,
            // Tests that call from_boot_at directly get the
            // Fixed clock matching their compaction snapshot,
            // so `do_ack` (M3b-2b-ii) sees the same value.
            ClockSource::Fixed(now_epoch_secs),
        )
    }

    fn from_boot_impl(
        boot: BootLoaderResult,
        max_envelopes_per_recipient: usize,
        seq_mac_root_key: Arc<SeqMacRootKey>,
        caps: CapacityCaps,
        fatal: broadcast::Sender<FatalReason>,
        now_epoch_secs: u64,
        clock: ClockSource,
    ) -> Result<Self, SpecError> {
        let provided_fp = seq_mac_root_key.fingerprint();
        let (meta, records, _walk, _was_first_install, state_dir, tombstone_config) =
            boot.into_parts();
        if provided_fp != meta.seq_mac_key_fingerprint {
            return Err(SpecError::SeqMacKeyFingerprintMismatch {
                boot_fingerprint: meta.seq_mac_key_fingerprint,
                provided_fingerprint: provided_fp,
            });
        }
        // Round-7 F2: factory owns the gate.
        let capacity = Arc::new(GlobalCapacityGate::new(caps));
        let active_index = Arc::new(ActiveRecordIndex::new());
        let tombstone_dedup = Arc::new(TombstoneDedupTable::new());
        let mut boot_seed_stats = BootSeedStats::default();
        let (rest_store_map, store_map) = seed_from_records(
            records,
            &capacity,
            max_envelopes_per_recipient,
            now_epoch_secs,
            &active_index,
            &tombstone_dedup,
            &mut boot_seed_stats,
        )?;
        Ok(Self {
            rest_store: Arc::new(RwLock::new(rest_store_map)),
            store: Arc::new(RwLock::new(store_map)),
            state_dir,
            max_envelopes_per_recipient,
            boot_generation: meta.boot_generation,
            seq_mac_root_key,
            capacity,
            fatal,
            worker_hash_key: WorkerHashKey::from_process_rng(),
            active_index,
            tombstone_dedup,
            boot_seed_stats,
            tombstone_config,
            clock,
        })
    }
}

/// Round-7 F1/F2 + **round-8 F1/F2** helper: seed both RAM stores
/// and the capacity ledger from `records`. Preserves tombstones
/// for M3b via the returned `Vec<LoadedRecord>`.
///
/// **Round-8 F1 pre-flight** (per recipient):
///   * partition records into `Queued` (per-recipient buckets)
///     vs `AckedTombstone`;
///   * check `bucket.len() <= max_envelopes_per_recipient`
///     BEFORE any ledger mutation;
///   * sort each recipient's bucket by `seq` ascending so RAM
///     store ordering matches the poll's since_seq contract —
///     `fs::read_dir` order is undefined, and a mis-ordered
///     bucket would let a poll advance `since_seq` past a
///     lower-seq record that then becomes permanently
///     ineligible;
///   * refuse boot fail-closed on duplicate `(recipient, seq)`
///     — the per-boot monotonic counter cannot legitimately
///     produce these, so a duplicate is a hard corruption
///     signal.
///
/// Only after every recipient passes preflight do we start
/// reserving capacity. Failure semantics:
///   * Queued `reserve_send` returning `CapacityError` drops
///     every queued reservation for THIS call via RAII.
///   * Tombstone `transition` mutations that already applied
///     remain in the gate — but the caller (`from_boot`)
///     discards the newly-created gate Arc on error, so the
///     partial state cannot escape.
/// **M3b-1** compaction pass: for every expired record, run
/// `fs::remove_file(path)` followed by a parent-directory fsync
/// so the unlink is durable before the process resumes. Returns
/// the survivor set to the caller; expired records are counted
/// into `stats` and never reach the seeding phase.
///
/// Failure semantics: any I/O error during unlink or
/// parent-fsync aborts boot fail-closed via
/// [`SpecError::BootCompactionFailed`]. Reviewer's corrective
/// #5: per-record transactions here mean a partial success is
/// bounded — later records are not processed if one fails, but
/// the ones already unlinked remain unlinked (which is safe
/// because we haven't touched ledger / RAM yet).
fn boot_compact_expired(
    records: Vec<LoadedRecord>,
    now_epoch_secs: u64,
    stats: &mut BootSeedStats,
) -> Result<Vec<LoadedRecord>, SpecError> {
    let mut survivors: Vec<LoadedRecord> = Vec::with_capacity(records.len());
    for loaded in records {
        let is_expired = match &loaded.record {
            PersistedRecord::Queued { expires_at, .. } => *expires_at <= now_epoch_secs,
            PersistedRecord::AckedTombstone { dedup_until, .. } => {
                *dedup_until <= now_epoch_secs
            }
        };
        if !is_expired {
            survivors.push(loaded);
            continue;
        }
        // Durable unlink: fs::remove_file → parent-dir fsync.
        // Fail-closed on either step.
        std::fs::remove_file(&loaded.path).map_err(|e| {
            SpecError::BootCompactionFailed {
                path: loaded.path.clone(),
                cause: e,
            }
        })?;

        // **M5a-2 SIGKILL failpoint** — an expired record has
        // been `remove_file`'d during boot compaction; the paired
        // parent-dir fsync has NOT yet issued. SIGKILL invariant:
        // the boot process dies before it can seed anything into
        // the runtime, so no user-visible drift is possible. On
        // the FOLLOWING boot the compaction pass is idempotent
        // by construction — either the entry is already absent
        // (no-op) or it re-appears in the walker set and is
        // swept again by the same `expires_at <= now` /
        // `dedup_until <= now` predicate. SIGKILL only proves
        // the absence of user-space teardown — no power-loss
        // claim; the paired `fsync_dir` below closes the
        // kernel-level window and SIGKILL cannot emulate it.
        crate::failpoint!("boot.after_compaction_unlink_before_parent_fsync");

        if let Some(parent) = loaded.path.parent() {
            crate::atomic_write::fsync_dir(parent).map_err(|e| {
                SpecError::BootCompactionFailed {
                    path: loaded.path.clone(),
                    cause: e,
                }
            })?;
        }
        match &loaded.record {
            PersistedRecord::Queued { .. } => {
                stats.queued_expired_swept = stats.queued_expired_swept.saturating_add(1);
            }
            PersistedRecord::AckedTombstone { .. } => {
                stats.tombstones_expired_swept =
                    stats.tombstones_expired_swept.saturating_add(1);
            }
        }
        stats.disk_reclaimed_bytes = stats
            .disk_reclaimed_bytes
            .saturating_add(loaded.disk_bytes);
    }
    Ok(survivors)
}

fn seed_from_records(
    records: Vec<LoadedRecord>,
    capacity: &Arc<GlobalCapacityGate>,
    max_envelopes_per_recipient: usize,
    now_epoch_secs: u64,
    active_index: &Arc<ActiveRecordIndex>,
    tombstone_dedup: &Arc<TombstoneDedupTable>,
    stats: &mut BootSeedStats,
) -> Result<
    (
        HashMap<String, Vec<RestEnvelope>>,
        HashMap<String, Vec<Envelope>>,
    ),
    SpecError,
> {
    // (0) M3b-1: durable-unlink every expired record BEFORE we
    // touch the cap check or ledger. Reviewer's corrective #1.
    let records = boot_compact_expired(records, now_epoch_secs, stats)?;
    // (1) Partition into per-recipient queued buckets +
    // tombstone list.
    let mut queued_by_recipient: HashMap<String, Vec<LoadedRecord>> = HashMap::new();
    let mut tombstones: Vec<LoadedRecord> = Vec::new();
    for loaded in records {
        match &loaded.record {
            PersistedRecord::Queued { .. } => {
                queued_by_recipient
                    .entry(loaded.recipient.clone())
                    .or_default()
                    .push(loaded);
            }
            PersistedRecord::AckedTombstone { .. } => {
                tombstones.push(loaded);
            }
        }
    }

    // (2) Preflight per recipient: per-recipient cap, sort by
    // seq, refuse duplicates.
    for (recipient, bucket) in queued_by_recipient.iter_mut() {
        if bucket.len() > max_envelopes_per_recipient {
            return Err(SpecError::PerRecipientCapExceededAtBoot {
                recipient: recipient.clone(),
                observed: bucket.len(),
                cap: max_envelopes_per_recipient,
            });
        }
        bucket.sort_by_key(|loaded| loaded.record.seq());
        for pair in bucket.windows(2) {
            if pair[0].record.seq() == pair[1].record.seq() {
                return Err(SpecError::DuplicateSeqAtBoot {
                    recipient: recipient.clone(),
                    seq: pair[0].record.seq(),
                });
            }
        }
    }

    // (3) Seed queued records in per-recipient seq order.
    let mut rest_store_map: HashMap<String, Vec<RestEnvelope>> = HashMap::new();
    let mut store_map: HashMap<String, Vec<Envelope>> = HashMap::new();
    let mut reservations = Vec::new();
    let mut queued_seeded: u64 = 0;
    let mut tombstones_seeded: u64 = 0;

    for (recipient, bucket) in queued_by_recipient {
        for loaded in bucket {
            let path = loaded.path.clone();
            let disk_bytes = loaded.disk_bytes;
            let ram_bytes = record_ram_estimate(disk_bytes);
            let (id, sealed_sender, payload, sequence_ts, seq, expires_at, seq_mac, body_hash) =
                if let PersistedRecord::Queued {
                    id,
                    sealed_sender,
                    payload,
                    sequence_ts,
                    seq,
                    expires_at,
                    seq_mac,
                    body_hash,
                    ..
                } = loaded.record
                {
                    (
                        id,
                        sealed_sender,
                        payload,
                        sequence_ts,
                        seq,
                        expires_at,
                        seq_mac,
                        body_hash,
                    )
                } else {
                    // Partition step above only pushes Queued records into
                    // this bucket. Non-Queued here would be a partitioning bug.
                    return Err(SpecError::CapacitySeedFailed {
                        cause: CapacityError::ArithmeticOverflow,
                        kind: RecordKind::Queued,
                        queued_seeded,
                        tombstones_seeded,
                    });
                };
            let reservation = capacity
                .reserve_send(disk_bytes, ram_bytes)
                .map_err(|e| SpecError::CapacitySeedFailed {
                    cause: e,
                    kind: RecordKind::Queued,
                    queued_seeded,
                    tombstones_seeded,
                })?;
            reservations.push(reservation);
            // M3b-1 + round-1 F2: register the surviving Queued
            // record in the active index. `try_insert_new` refuses
            // to overwrite — a duplicate `(recipient, id)` at boot
            // means the persisted queue is corrupt (the boot
            // loader's path uniqueness should have prevented it)
            // and refusing fail-closed keeps ACK / sweep from
            // later releasing the wrong footprint.
            active_index
                .try_insert_new(
                    &recipient,
                    id.clone(),
                    ActiveEntryMeta {
                        path: path.clone(),
                        seq,
                        body_hash: body_hash.clone(),
                        expires_at,
                        disk_bytes,
                        ram_bytes,
                    },
                )
                .map_err(|_| SpecError::DuplicateIdAtBootIndex {
                    recipient: recipient.clone(),
                    id: id.clone(),
                })?;
            rest_store_map
                .entry(recipient.clone())
                .or_default()
                .push(RestEnvelope {
                    id: id.clone(),
                    from: String::new(),
                    sealed_sender: sealed_sender.clone(),
                    payload: payload.clone(),
                    sequence_ts,
                    seq,
                    expires_at,
                    seq_mac,
                });
            store_map
                .entry(recipient.clone())
                .or_default()
                .push(Envelope {
                    id,
                    to: recipient.clone(),
                    from: String::new(),
                    sealed_sender,
                    payload,
                    expires_at,
                });
            queued_seeded = queued_seeded.saturating_add(1);
        }
    }

    // (4) Tombstones (already compaction-filtered): bump
    // tombstone_bytes + tombstone_records via transition and
    // populate the dedup table. M3b-2 `do_ack` will look up
    // entries here for the idempotent-ack case; M3b-3
    // `do_sweep` will remove past-`dedup_until` entries.
    for loaded in tombstones {
        let path = loaded.path.clone();
        let disk_bytes = loaded.disk_bytes;
        let ram_bytes = record_ram_estimate(disk_bytes);
        let (id, seq, body_hash, dedup_until) = if let PersistedRecord::AckedTombstone {
            id,
            seq,
            body_hash,
            dedup_until,
            ..
        } = loaded.record
        {
            (id, seq, body_hash, dedup_until)
        } else {
            return Err(SpecError::CapacitySeedFailed {
                cause: CapacityError::ArithmeticOverflow,
                kind: RecordKind::AckedTombstone,
                queued_seeded,
                tombstones_seeded,
            });
        };
        capacity
            .transition(
                None,
                Some(RecordFootprint {
                    kind: RecordKind::AckedTombstone,
                    disk_bytes,
                    ram_bytes,
                }),
            )
            .map_err(|e| SpecError::CapacitySeedFailed {
                cause: e,
                kind: RecordKind::AckedTombstone,
                queued_seeded,
                tombstones_seeded,
            })?;
        tombstone_dedup
            .try_insert_new(
                &loaded.recipient,
                id.clone(),
                TombstoneEntry {
                    path,
                    seq,
                    body_hash,
                    dedup_until,
                    disk_bytes,
                    ram_bytes,
                },
            )
            .map_err(|_| SpecError::DuplicateIdAtBootIndex {
                recipient: loaded.recipient.clone(),
                id,
            })?;
        tombstones_seeded = tombstones_seeded.saturating_add(1);
    }

    // (5) Emit observable counts for M3b-1 (round-8 corrective
    // #3): the operator sees exactly how many records survived
    // vs were compacted at boot.
    stats.queued_seeded = queued_seeded;
    stats.tombstones_seeded = tombstones_seeded;

    for reservation in reservations {
        reservation.commit();
    }

    Ok((rest_store_map, store_map))
}

/// Errors returned by [`WorkerRuntime::try_send`]. A superset
/// of [`crate::worker_pool::TrySendError`] that adds the
/// runtime-boundary [`RuntimeSendError::EmptyRecipient`] variant
/// so callers see the ingress bypass as a typed error rather
/// than a silent forward.
#[derive(Debug)]
pub enum RuntimeSendError {
    /// The worker's mpsc buffer is at
    /// [`REST_WORKER_MPSC_BUFFER`] capacity. HTTP 503.
    Full,
    /// The pool has entered `Closing`/`Closed`.
    ShuttingDown,
    /// Internal invariant break — `worker_id` fell outside
    /// `0..REST_WORKER_COUNT`. Never observable from public API
    /// because the runtime computes `worker_id` itself.
    WorkerIdOutOfBounds { worker_id: usize },
    /// **Round-6 F2**: the op's recipient was empty — an
    /// ingress-hardening bypass. The runtime broadcasted
    /// [`FatalReason::IngressBypassAtBoundary`] on the fatal
    /// channel BEFORE returning this variant, independently of
    /// any worker mpsc capacity. Caller should map to a hard
    /// 400 (client-facing) OR page and exit (internal caller).
    EmptyRecipient,
}

impl From<crate::worker_pool::TrySendError> for RuntimeSendError {
    fn from(e: crate::worker_pool::TrySendError) -> Self {
        match e {
            crate::worker_pool::TrySendError::Full => RuntimeSendError::Full,
            crate::worker_pool::TrySendError::ShuttingDown => RuntimeSendError::ShuttingDown,
            crate::worker_pool::TrySendError::WorkerIdOutOfBounds { worker_id } => {
                RuntimeSendError::WorkerIdOutOfBounds { worker_id }
            }
        }
    }
}

impl std::fmt::Display for RuntimeSendError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RuntimeSendError::Full => write!(f, "worker mpsc buffer full — back-pressure"),
            RuntimeSendError::ShuttingDown => write!(f, "worker pool is Closing/Closed"),
            RuntimeSendError::WorkerIdOutOfBounds { worker_id } => {
                write!(f, "internal invariant: worker_id {worker_id} out of bounds")
            }
            RuntimeSendError::EmptyRecipient => write!(
                f,
                "empty recipient at try_send boundary — ingress-hardening bypass; fatal broadcasted"
            ),
        }
    }
}

impl std::error::Error for RuntimeSendError {}

/// Fully-constructed shard-worker fleet handle handed back to
/// `main.rs`.
///
/// **Round-6 F1 + F2**:
///   * Now owns `rest_store` and `store` (created + seeded by
///     [`WorkerRuntimeSpec::from_boot`]). M4 wiring reads them
///     via [`WorkerRuntime::rest_store`] and
///     [`WorkerRuntime::store`] so `AppState` and the workers
///     share a single Arc — never two disconnected instances.
///   * Owns a fatal broadcast sender. `try_send` emits
///     [`FatalReason::IngressBypassAtBoundary`] directly on
///     empty-recipient input, independent of any worker mpsc
///     capacity — the round-5 shape depended on
///     `pool.try_send` succeeding to reach the catch_unwind, so
///     a full or closed worker channel swallowed the guarantee.
pub struct WorkerRuntime {
    pool: WorkerPool,
    worker_hash_key: WorkerHashKey,
    fatal: broadcast::Sender<FatalReason>,
    rest_store: Arc<RwLock<HashMap<String, Vec<RestEnvelope>>>>,
    store: Arc<RwLock<HashMap<String, Vec<Envelope>>>>,
    /// **Round-7 F2**: the capacity gate the runtime seeded from
    /// `boot.records()`. Exposed via
    /// [`WorkerRuntime::capacity`] so M4 can share the Arc with
    /// other paths (health snapshots, admin endpoints).
    capacity: Arc<GlobalCapacityGate>,
    /// **M3b-1**: active index — replaces the round-7 raw
    /// `Vec<LoadedRecord>` tombstones stash. Populated at boot
    /// from surviving Queued records and extended by
    /// [`do_send`]. M3b-2 `do_ack` and M3b-3 `do_sweep` consume
    /// it internally.
    active_index: Arc<ActiveRecordIndex>,
    /// **M3b-1**: tombstone dedup table populated at boot from
    /// surviving tombstones. Round-7's raw preservation Vec is
    /// superseded — the structured shape supports the
    /// idempotent-ack lookup path M3b-2 needs.
    tombstone_dedup: Arc<TombstoneDedupTable>,
    /// **M3b-1**: observable boot seed counters. Frozen once
    /// [`spawn_worker_runtime`] returns; M4 exposes via
    /// [`WorkerRuntime::boot_seed_stats`].
    boot_seed_stats: BootSeedStats,
    /// **M3b-2a**: dedup horizon (Copy-cheap
    /// `TombstoneConfig`). Bound to the boot proof so M4 cannot
    /// substitute a different horizon at spawn time.
    tombstone_config: crate::tombstone_config::TombstoneConfig,
    /// **M3b-2b-i**: `ClockSource` carried through spawn.
    /// Read-only observability via
    /// [`WorkerRuntime::clock_source`].
    clock: ClockSource,
}

impl WorkerRuntime {
    /// Route `op` to its owning shard-worker and enqueue it on
    /// that worker's mpsc channel.
    ///
    /// **Round-6 F2**: an empty-recipient `RestOp` is REJECTED
    /// at this boundary — the runtime broadcasts
    /// [`FatalReason::IngressBypassAtBoundary`] directly on the
    /// fatal channel (which is independent of the workers' mpsc
    /// buffers) and returns [`RuntimeSendError::EmptyRecipient`].
    /// The op never enters the pool, so a full or closed worker
    /// channel cannot swallow the fatal guarantee.
    pub fn try_send(&self, op: RestOp) -> Result<(), RuntimeSendError> {
        if op.recipient().is_empty() {
            let _ = self.fatal.send(FatalReason::IngressBypassAtBoundary {
                at: "WorkerRuntime::try_send: empty recipient",
            });
            return Err(RuntimeSendError::EmptyRecipient);
        }
        let worker_id = worker_for(op.recipient(), self.worker_hash_key);
        self.pool.try_send(worker_id, op).map_err(RuntimeSendError::from)
    }

    /// Transition the pool `Running → Closing`. Idempotent while
    /// already `Closing` or `Closed`.
    pub fn close(&self) {
        self.pool.close();
    }

    /// Await every worker `JoinHandle` under a shutdown deadline.
    pub async fn drain_handles(
        &self,
        deadline: std::time::Duration,
    ) -> Result<Vec<crate::worker_pool::WorkerDrainOutcome>, crate::worker_pool::DrainError>
    {
        self.pool.drain_handles(deadline).await
    }

    /// Current lifecycle discriminant.
    pub fn state_kind(&self) -> PoolStateKind {
        self.pool.current_state_kind()
    }

    /// Fleet size captured at construction.
    pub fn expected_worker_count(&self) -> usize {
        self.pool.expected_worker_count()
    }

    /// Cloned Arc handle to the REST-fallback store seeded from
    /// `boot.records()`. M4 wiring shares this with `AppState`
    /// so REST poll handlers see the seeded queue.
    pub fn rest_store(&self) -> Arc<RwLock<HashMap<String, Vec<RestEnvelope>>>> {
        Arc::clone(&self.rest_store)
    }

    /// Cloned Arc handle to the WS store seeded from
    /// `boot.records()`. M4 wiring shares this with `AppState`
    /// so WS delivery handlers see the seeded queue.
    pub fn store(&self) -> Arc<RwLock<HashMap<String, Vec<Envelope>>>> {
        Arc::clone(&self.store)
    }

    /// **Round-7 F2**: cloned Arc handle to the capacity gate the
    /// runtime seeded from `boot.records()`. M4 shares this with
    /// snapshot / admin paths.
    pub fn capacity(&self) -> Arc<GlobalCapacityGate> {
        Arc::clone(&self.capacity)
    }

    /// **M3b-1**: cloned Arc to the active-record index. Read
    /// paths use it for observability; M3b-2 `do_ack` +
    /// M3b-3 `do_sweep` mutate through the same Arc that the
    /// workers already hold.
    pub fn active_index(&self) -> Arc<ActiveRecordIndex> {
        Arc::clone(&self.active_index)
    }

    /// **M3b-1**: cloned Arc to the tombstone dedup table.
    /// Same sharing shape as [`WorkerRuntime::active_index`].
    pub fn tombstone_dedup(&self) -> Arc<TombstoneDedupTable> {
        Arc::clone(&self.tombstone_dedup)
    }

    /// **M3b-1**: observable boot seed counters. Round-8
    /// corrective #3 — a single `now_epoch_secs` snapshot drove
    /// every expiry decision; these counters report the
    /// resulting compaction outcome.
    pub fn boot_seed_stats(&self) -> BootSeedStats {
        self.boot_seed_stats.clone()
    }

    /// **M3b-1**: read-only count of the active-index entries
    /// across all recipients.
    pub fn active_entry_count(&self) -> usize {
        self.active_index.count()
    }

    /// **M3b-1**: read-only count of the tombstone dedup table
    /// across all recipients.
    pub fn tombstone_dedup_count(&self) -> usize {
        self.tombstone_dedup.count()
    }

    /// **PR-2 M4-3**: union of recipient keys across the two
    /// durable projections (`active_index` + `tombstone_dedup`),
    /// deduplicated. Used by the sweep scheduler's per-recipient
    /// dispatch loop so raw indices never leave the runtime.
    ///
    /// Cloning happens under each index's short read guard —
    /// both guards drop before this fn returns. The returned
    /// `Vec` is owned by the caller; the sweep scheduler
    /// iterates over it without holding any runtime lock.
    ///
    /// A recipient with zero live queue + zero tombstones is
    /// absent from the result — nothing to sweep. A recipient
    /// with entries in ONE projection but not the other still
    /// appears exactly once.
    pub fn recipient_snapshot(&self) -> Vec<String> {
        let mut set: std::collections::HashSet<String> = std::collections::HashSet::new();
        for r in self.active_index.recipient_keys() {
            set.insert(r);
        }
        for r in self.tombstone_dedup.recipient_keys() {
            set.insert(r);
        }
        set.into_iter().collect()
    }

    /// **PR-2 M4-3 round-1 REDLINE**: same union as
    /// [`WorkerRuntime::recipient_snapshot`] but the recipients
    /// are bucketed by owning shard, so the sweep scheduler can
    /// run different shards in parallel without accidentally
    /// stacking two ops on the same shard-worker's mpsc queue.
    ///
    /// The returned `Vec` has exactly [`REST_WORKER_COUNT`]
    /// entries; index `i` carries every recipient whose
    /// [`worker_for`] hash resolves to shard `i`. Empty inner
    /// vecs are legal — that shard currently has no queued or
    /// tombstoned recipients.
    ///
    /// Rationale: `do_sweep` is actor-serialized *per shard*,
    /// but different shards are independent workers. The
    /// round-0 M4-3 loop dispatched sequentially across ALL
    /// recipients, giving the whole scheduler a
    /// head-of-line-blocking shape — a single wedged shard held
    /// up healthy shards, and a 100 k-recipient population
    /// implied an ~11-day per-tick worst case. Bucketing by
    /// shard lets the scheduler fan out to bounded concurrent
    /// shards while preserving the per-shard sequential
    /// contract `do_sweep` relies on.
    pub fn recipient_snapshot_by_shard(&self) -> Vec<Vec<String>> {
        let mut set: std::collections::HashSet<String> = std::collections::HashSet::new();
        for r in self.active_index.recipient_keys() {
            set.insert(r);
        }
        for r in self.tombstone_dedup.recipient_keys() {
            set.insert(r);
        }
        let mut buckets: Vec<Vec<String>> =
            (0..REST_WORKER_COUNT).map(|_| Vec::new()).collect();
        for recipient in set {
            let shard = worker_for(&recipient, self.worker_hash_key);
            buckets[shard].push(recipient);
        }
        buckets
    }

    /// **M3b-2a**: dedup horizon captured at boot. Read-only —
    /// M4 wiring cannot substitute a different horizon after
    /// spawn.
    pub fn tombstone_config(&self) -> crate::tombstone_config::TombstoneConfig {
        self.tombstone_config
    }

    /// **M3b-2b-i**: clock source. Read-only accessor for
    /// observability and reboot-scenario tests. Production
    /// always sees `ClockSource::Production`.
    pub fn clock_source(&self) -> ClockSource {
        self.clock
    }
}

/// Errors returned by [`spawn_worker_runtime`] before any worker
/// is spawned.
#[derive(Debug)]
pub enum SpawnError {
    /// `spec.boot_generation` is strictly greater than
    /// [`queue_meta::MAX_BOOT_GENERATION`] — i.e. `> 2^24 - 1`,
    /// so the `<< 40` shift would wrap. No workers are spawned;
    /// caller should fail-boot exit 4.
    ///
    /// **Round-4 F5**: doc string tightened — the check is
    /// `> MAX_BOOT_GENERATION`, not `>=`. `boot_generation ==
    /// MAX_BOOT_GENERATION` (the highest legal namespace) is
    /// still accepted.
    BootGenerationOutOfRange { generation: u32 },
}

impl std::fmt::Display for SpawnError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SpawnError::BootGenerationOutOfRange { generation } => write!(
                f,
                "spawn refused: boot_generation {generation} exceeds the 24-bit cap {} (would collide via <<40); values 0..={} are accepted",
                crate::queue_meta::MAX_BOOT_GENERATION,
                crate::queue_meta::MAX_BOOT_GENERATION,
            ),
        }
    }
}

impl std::error::Error for SpawnError {}

/// Construct and spawn the shard-worker fleet.
///
/// **Round-3 F1 + Round-4 F1**: the sole public path from
/// `main.rs` (a separate binary crate) to a live shard-worker
/// pool. The factory:
///
/// 1. rejects `spec.boot_generation > MAX_BOOT_GENERATION`
///    (i.e. `>= 2^24`) up front — values `0..=MAX_BOOT_GENERATION`
///    inclusive are accepted;
/// 2. creates [`REST_WORKER_COUNT`] mpsc channels of buffer
///    [`REST_WORKER_MPSC_BUFFER`];
/// 3. builds a [`WorkerPoolBuilder`] and lifts a
///    [`PoolStateReader`];
/// 4. for each worker: constructs an [`ActorContext`] with a
///    fresh `worker_id`, `Arc::clone`s of every shared resource,
///    and spawns [`worker_loop`] on the current tokio runtime;
/// 5. finalises the pool with the `JoinHandle`s and returns the
///    [`WorkerRuntime`] handle.
///
/// The per-boot `seq_counter` is created inside the factory and
/// `Arc`-shared across every worker so the seq sequence is
/// process-monotonic.
///
/// Must be called from a tokio runtime context (uses
/// `tokio::spawn`).
pub fn spawn_worker_runtime(spec: WorkerRuntimeSpec) -> Result<WorkerRuntime, SpawnError> {
    // Round-4 F5: reject strictly-greater-than the cap. Values
    // at the cap itself are legal (highest generation before the
    // <<40 shift would collide).
    if u64::from(spec.boot_generation) > u64::from(crate::queue_meta::MAX_BOOT_GENERATION) {
        return Err(SpawnError::BootGenerationOutOfRange {
            generation: spec.boot_generation,
        });
    }

    let seq_counter = Arc::new(AtomicU64::new(SEQ_COUNTER_INITIAL));

    let mut senders = Vec::with_capacity(REST_WORKER_COUNT);
    let mut receivers = Vec::with_capacity(REST_WORKER_COUNT);
    for _ in 0..REST_WORKER_COUNT {
        let (tx, rx) = mpsc::channel::<RestOp>(REST_WORKER_MPSC_BUFFER);
        senders.push(tx);
        receivers.push(rx);
    }

    let builder = WorkerPoolBuilder::new(senders);
    let state_reader = builder.state_reader();

    let mut handles: Vec<tokio::task::JoinHandle<Result<(), FatalReason>>> =
        Vec::with_capacity(REST_WORKER_COUNT);
    for (worker_id, rx) in receivers.into_iter().enumerate() {
        let ctx = ActorContext {
            worker_id: worker_id as u8,
            worker_hash_key: spec.worker_hash_key,
            capacity: Arc::clone(&spec.capacity),
            fatal: spec.fatal.clone(),
            pool_state_reader: state_reader.clone(),
            rest_store: Arc::clone(&spec.rest_store),
            store: Arc::clone(&spec.store),
            state_dir: spec.state_dir.clone(),
            max_envelopes_per_recipient: spec.max_envelopes_per_recipient,
            boot_generation: spec.boot_generation,
            seq_counter: Arc::clone(&seq_counter),
            seq_mac_root_key: Arc::clone(&spec.seq_mac_root_key),
            active_index: Arc::clone(&spec.active_index),
            tombstone_dedup: Arc::clone(&spec.tombstone_dedup),
            tombstone_config: spec.tombstone_config,
            clock: spec.clock,
            #[cfg(test)]
            test_seams: Arc::new(TestSeams::default()),
        };
        let handle = tokio::spawn(async move { worker_loop(ctx, rx).await });
        handles.push(handle);
    }

    let pool = builder.finalize(handles);
    Ok(WorkerRuntime {
        pool,
        worker_hash_key: spec.worker_hash_key,
        fatal: spec.fatal,
        rest_store: spec.rest_store,
        store: spec.store,
        capacity: spec.capacity,
        active_index: spec.active_index,
        tombstone_dedup: spec.tombstone_dedup,
        boot_seed_stats: spec.boot_seed_stats,
        tombstone_config: spec.tombstone_config,
        clock: spec.clock,
    })
}

// ─── Tests ─────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capacity_ledger::CapacityCaps;
    use crate::queue_meta::{self, QueueMeta, META_VERSION};
    use crate::worker_pool::WorkerPoolBuilder;
    use tempfile::TempDir;

    // ─── helpers ────────────────────────────────────────────────

    const TEST_RECIPIENT: &str =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    const TEST_KEY: WorkerHashKey = WorkerHashKey::from_raw(0xdead_beef_cafe_babe, 0x1337_9001);
    const TEST_MAC_KEY: [u8; 32] = [0x11u8; 32];
    const TEST_PER_RECIPIENT_CAP: usize = 500;
    /// **M3b-1**: fixed wall-clock snapshot used by every
    /// in-module test that calls `from_boot`. Well below the
    /// `expires_at = 1_720_600_000` in `sample_candidate`, so
    /// non-expired records survive compaction cleanly; tests
    /// that want to exercise the expired path set an
    /// `expires_at` less than this value.
    const TEST_NOW_EPOCH_SECS: u64 = 1_720_000_000;

    fn caps() -> CapacityCaps {
        CapacityCaps {
            max_envelopes: 100,
            max_bytes: 1_000_000,
            ram_budget: 1_000_000,
        }
    }

    fn build_state_dir() -> TempDir {
        let dir = TempDir::new().unwrap();
        std::fs::create_dir_all(dir.path().join("queue")).unwrap();
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: "0123456789abcdef".into(),
        };
        queue_meta::write_meta(dir.path(), &meta).unwrap();
        dir
    }

    fn build_ctx(dir: &TempDir) -> (ActorContext, mpsc::Sender<RestOp>) {
        build_ctx_at(dir.path())
    }

    /// **M3b-1 round-3 F2**: parent-owned state_dir variant for
    /// the subprocess test. The subprocess reuses a directory
    /// the parent test owns so the parent can inspect its
    /// filesystem state after the abort.
    fn build_ctx_at(dir_path: &std::path::Path) -> (ActorContext, mpsc::Sender<RestOp>) {
        let (tx, _rx) = mpsc::channel::<RestOp>(REST_WORKER_MPSC_BUFFER);
        let builder = WorkerPoolBuilder::new(vec![tx.clone()]);
        let reader = builder.state_reader();
        let handles: Vec<tokio::task::JoinHandle<Result<(), FatalReason>>> =
            vec![tokio::spawn(async { Ok(()) })];
        let _pool = builder.finalize(handles);

        let expected = worker_for(TEST_RECIPIENT, TEST_KEY);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(16);
        let ctx = ActorContext {
            worker_id: expected as u8,
            worker_hash_key: TEST_KEY,
            capacity: Arc::new(GlobalCapacityGate::new(caps())),
            fatal: fatal_tx,
            pool_state_reader: reader,
            rest_store: Arc::new(RwLock::new(HashMap::new())),
            store: Arc::new(RwLock::new(HashMap::new())),
            state_dir: dir_path.to_path_buf(),
            max_envelopes_per_recipient: TEST_PER_RECIPIENT_CAP,
            boot_generation: 1,
            seq_counter: Arc::new(AtomicU64::new(SEQ_COUNTER_INITIAL)),
            seq_mac_root_key: Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            active_index: Arc::new(ActiveRecordIndex::new()),
            tombstone_dedup: Arc::new(TombstoneDedupTable::new()),
            tombstone_config: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
            clock: ClockSource::Fixed(TEST_NOW_EPOCH_SECS),
            test_seams: Arc::new(TestSeams::default()),
        };
        (ctx, tx)
    }

    fn sample_candidate(id: &str) -> SendCandidate {
        SendCandidate {
            id: id.into(),
            // Round-2 F3: PR-2 pipeline is sealed-sender-only;
            // every candidate factory MUST supply a non-empty
            // sealed_sender.
            sealed_sender: format!("sender-{id}"),
            payload: format!("payload-{id}"),
            sequence_ts: 1_720_000_000_000,
            expires_at: 1_720_600_000,
        }
    }

    // ─── existing M1 tests preserved ───────────────────────────

    #[test]
    fn worker_for_is_deterministic_under_fixed_key() {
        let a = worker_for("recipient-a", TEST_KEY);
        let b = worker_for("recipient-a", TEST_KEY);
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
            assert!(
                *count > expected / 2 && *count < expected * 2,
                "bucket {bucket} count {count} outside {expected}/2..{expected}*2 band",
            );
        }
    }

    #[test]
    #[should_panic(expected = "empty recipient")]
    fn worker_for_panics_on_empty_recipient() {
        let _ = worker_for("", TEST_KEY);
    }

    #[test]
    fn worker_hash_key_from_process_rng_yields_nonzero_pair() {
        let key = WorkerHashKey::from_process_rng();
        assert!(key.k0 != 0 || key.k1 != 0);
    }

    #[test]
    fn constants_match_locked_design() {
        assert_eq!(REST_WORKER_COUNT, 64);
        assert_eq!(REST_WORKER_MPSC_BUFFER, 128);
    }

    #[test]
    fn seq_counter_limit_matches_24_bit_generation_cap() {
        // The counter must fit in exactly 40 bits so
        // `(u64::from(generation) << 40) | counter` uses the whole
        // u64 without colliding across generations. Any change to
        // either constant needs both updated in lockstep — this
        // test codifies the lockstep so a future edit cannot
        // silently widen one and shrink the other.
        assert_eq!(SEQ_COUNTER_LIMIT, 1u64 << 40);
        assert_eq!(
            u64::from(queue_meta::MAX_BOOT_GENERATION) + 1,
            1u64 << 24
        );
        // Shift math: the highest permitted generation shifted by
        // 40 lands exactly at bit 63; anything beyond that would
        // wrap.
        let hi = u64::from(queue_meta::MAX_BOOT_GENERATION) << 40;
        let expected = ((1u64 << 24) - 1) << 40;
        assert_eq!(hi, expected);
        assert_eq!(hi.leading_zeros(), 0, "top bit set");
    }

    // ─── seq assembly (F2) ─────────────────────────────────────

    #[tokio::test]
    async fn next_seq_composes_generation_and_counter() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let a = next_seq(&ctx).unwrap();
        let b = next_seq(&ctx).unwrap();
        assert_eq!(
            a,
            (u64::from(ctx.boot_generation) << 40) | SEQ_COUNTER_INITIAL
        );
        assert_eq!(b, a + 1);
    }

    #[tokio::test]
    async fn next_seq_saturation_is_sticky() {
        // F2 amendment: once the counter reaches the limit, EVERY
        // subsequent call MUST also return SeqSaturation (no
        // fetch_add wrap that would reuse an already-issued seq
        // after u64 rolls over).
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        ctx.seq_counter.store(SEQ_COUNTER_LIMIT, Ordering::SeqCst);
        for _ in 0..8 {
            let err = next_seq(&ctx).unwrap_err();
            assert!(matches!(err, SendError::SeqSaturation { .. }));
        }
        // Counter did not advance past the limit — otherwise a
        // subsequent u64 wrap would silently reuse namespace.
        assert_eq!(ctx.seq_counter.load(Ordering::SeqCst), SEQ_COUNTER_LIMIT);
    }

    // ─── do_send happy path — production projections (F1) ─────

    #[tokio::test]
    async fn do_send_happy_path_writes_disk_and_both_production_stores_and_ledger() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let candidate = sample_candidate("env-happy");
        let outcome = do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .expect("happy path");
        assert!(!outcome.idempotent_replay);

        // Disk file exists at expected path.
        let expected_path = record_path(dir.path(), TEST_RECIPIENT, &candidate.id);
        assert!(expected_path.exists(), "record file not on disk");

        // WS store carries an Envelope with matching id + empty
        // `from` (sealed-sender only, ADR-006).
        let ws = ctx.store.read().await;
        let ws_queue = ws.get(TEST_RECIPIENT).expect("store recipient");
        assert_eq!(ws_queue.len(), 1);
        assert_eq!(ws_queue[0].id, candidate.id);
        assert_eq!(ws_queue[0].to, TEST_RECIPIENT);
        assert!(ws_queue[0].from.is_empty(), "sealed-sender-only");
        assert_eq!(ws_queue[0].sealed_sender, candidate.sealed_sender);
        assert_eq!(ws_queue[0].payload, candidate.payload);

        // REST store carries a RestEnvelope with the seq + seq_mac
        // populated (production projection, F1).
        let rest = ctx.rest_store.read().await;
        let rest_queue = rest.get(TEST_RECIPIENT).expect("rest_store recipient");
        assert_eq!(rest_queue.len(), 1);
        assert_eq!(rest_queue[0].id, candidate.id);
        assert_eq!(rest_queue[0].seq, outcome.seq);
        assert_eq!(rest_queue[0].sequence_ts, candidate.sequence_ts);
        assert_eq!(rest_queue[0].expires_at, candidate.expires_at);
        assert_eq!(rest_queue[0].seq_mac.len(), 64, "seq_mac hex is 32 bytes");

        // Capacity ledger reflects one queued record.
        let snap = ctx.capacity.snapshot();
        assert_eq!(snap.active_envelopes, 1);
        assert!(snap.active_bytes > 0);
        // F4: ram estimate is strictly greater than disk_bytes.
        assert!(
            snap.ram_bytes > snap.active_bytes,
            "ram estimator must exceed disk_bytes (F4 amendment)"
        );
    }

    // ─── shard-ownership fatal (F3) ────────────────────────────

    #[tokio::test]
    async fn do_send_wrong_shard_no_disk_no_ram_no_ledger_mutation() {
        // NOTE: F3 relocated the shard check to `dispatch_one`.
        // `do_send` itself no longer includes the check (F6 makes
        // it private and only reachable through dispatch_one, so
        // the mutation guarantee comes from the caller path). This
        // test validates that a caller-visible mismatch produces
        // NO side effects via the worker_loop path.
        let dir = build_state_dir();
        let (mut ctx, _tx_unused) = build_ctx(&dir);
        let expected = worker_for(TEST_RECIPIENT, ctx.worker_hash_key);
        ctx.worker_id = ((expected + 1) % REST_WORKER_COUNT) as u8;
        let mut fatal_rx = ctx.fatal.subscribe();

        let capacity = Arc::clone(&ctx.capacity);
        let rest_store = Arc::clone(&ctx.rest_store);
        let store = Arc::clone(&ctx.store);
        let snap_before = capacity.snapshot();
        let (tx, rx) = mpsc::channel::<RestOp>(1);
        let loop_h = tokio::spawn(async move { worker_loop(ctx, rx).await });

        let (reply_tx, reply_rx) = oneshot::channel();
        tx.send(RestOp::Send {
            recipient: TEST_RECIPIENT.into(),
            candidate: sample_candidate("env-shard"),
            reply: reply_tx,
        })
        .await
        .unwrap();

        let err = reply_rx.await.unwrap().unwrap_err();
        assert!(matches!(err, SendError::ShardMismatch { .. }));
        let fatal = fatal_rx.recv().await.unwrap();
        assert!(matches!(
            fatal,
            FatalReason::ShardRoutingInvariantViolation { .. }
        ));

        // No disk / no RAM / no ledger drift.
        assert_eq!(capacity.snapshot(), snap_before);
        assert!(rest_store.read().await.is_empty());
        assert!(store.read().await.is_empty());
        assert!(!record_path(dir.path(), TEST_RECIPIENT, "env-shard").exists());

        drop(tx);
        let outcome = loop_h.await.unwrap();
        assert!(matches!(
            outcome,
            Err(FatalReason::ShardRoutingInvariantViolation { .. })
        ));
    }

    // ─── F3: empty recipient panic is caught ───────────────────

    #[tokio::test]
    async fn worker_loop_empty_recipient_panic_is_caught_as_worker_panic() {
        // F3 amendment: `worker_for("")` panics, and pre-amendment
        // that panic tore down the task WITHOUT a broadcast because
        // the shard check ran outside catch_unwind. After F3 the
        // panic is caught inside dispatch_one and surfaces as
        // FatalReason::WorkerPanic.
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        let mut fatal_rx = ctx.fatal.subscribe();
        let (tx, rx) = mpsc::channel::<RestOp>(1);
        let handle = tokio::spawn(async move { worker_loop(ctx, rx).await });

        let (reply_tx, _reply_rx) = oneshot::channel();
        tx.send(RestOp::Send {
            recipient: "".into(),
            candidate: sample_candidate("env-empty"),
            reply: reply_tx,
        })
        .await
        .unwrap();
        // The awaiting handler observes reply drop (RecvError) —
        // no need to assert since the reply oneshot is dropped
        // inside the panic path. The fatal broadcast is what
        // matters:
        let fatal = fatal_rx.recv().await.unwrap();
        assert!(matches!(fatal, FatalReason::WorkerPanic { .. }));
        drop(tx);
        let outcome = handle.await.unwrap();
        assert!(matches!(outcome, Err(FatalReason::WorkerPanic { .. })));
    }

    // ─── capacity refusal ─────────────────────────────────────

    #[tokio::test]
    async fn do_send_capacity_refusal_no_mutation_no_drift() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let tiny_gate = Arc::new(GlobalCapacityGate::new(CapacityCaps {
            max_envelopes: 0,
            max_bytes: 1_000_000,
            ram_budget: 1_000_000,
        }));
        let ctx = ActorContext {
            capacity: Arc::clone(&tiny_gate),
            ..ctx
        };
        let snap_before = ctx.capacity.snapshot();

        let err = do_send(&ctx, TEST_RECIPIENT, sample_candidate("env-cap"))
            .await
            .unwrap_err();
        assert!(matches!(err, SendError::CapacityExceeded(_)));

        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert!(ctx.rest_store.read().await.is_empty());
        assert!(ctx.store.read().await.is_empty());
        assert!(!record_path(dir.path(), TEST_RECIPIENT, "env-cap").exists());
    }

    // ─── F5: per-recipient cap refusal ─────────────────────────

    #[tokio::test]
    async fn do_send_per_recipient_cap_refuses_and_leaves_ledger_intact() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        // Shrink cap so 2nd send trips it.
        let ctx = ActorContext {
            max_envelopes_per_recipient: 1,
            ..ctx
        };
        do_send(&ctx, TEST_RECIPIENT, sample_candidate("env-1"))
            .await
            .unwrap();
        let snap_before_second = ctx.capacity.snapshot();

        let err = do_send(&ctx, TEST_RECIPIENT, sample_candidate("env-2"))
            .await
            .unwrap_err();
        assert!(matches!(
            err,
            SendError::PerRecipientQueueFull { observed: 1, cap: 1 }
        ));

        // Ledger unchanged — no reservation was taken because the
        // cap check happens BEFORE reserve.
        assert_eq!(ctx.capacity.snapshot(), snap_before_second);
        // RAM store still holds exactly one record.
        assert_eq!(
            ctx.rest_store.read().await.get(TEST_RECIPIENT).unwrap().len(),
            1
        );
    }

    // ─── persistence failure + rollback ───────────────────────

    #[tokio::test]
    async fn do_send_persistence_failure_rolls_back_reservation() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        // Block shard dir creation by planting a FILE where the
        // shard subdir must be created.
        let queue_dir = dir.path().join("queue");
        std::fs::write(queue_dir.join("01"), b"blocking-file").unwrap();
        let snap_before = ctx.capacity.snapshot();

        let err = do_send(&ctx, TEST_RECIPIENT, sample_candidate("env-persist"))
            .await
            .unwrap_err();
        assert!(matches!(err, SendError::Persistence(_)));

        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert!(ctx.rest_store.read().await.is_empty());
        assert!(ctx.store.read().await.is_empty());
    }

    // ─── idempotent replay (same-id + same-body) ──────────────

    #[tokio::test]
    async fn do_send_same_id_same_body_returns_prior_seq_no_double_accounting() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let candidate = sample_candidate("env-dup");
        let first = do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .unwrap();
        let snap_after_first = ctx.capacity.snapshot();

        let second = do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .unwrap();
        assert_eq!(second.seq, first.seq);
        assert!(second.idempotent_replay);

        assert_eq!(ctx.capacity.snapshot(), snap_after_first);
        assert_eq!(
            ctx.rest_store.read().await.get(TEST_RECIPIENT).unwrap().len(),
            1
        );
        assert_eq!(
            ctx.store.read().await.get(TEST_RECIPIENT).unwrap().len(),
            1
        );
    }

    // ─── same-id divergent-body conflict ─────────────────────

    #[tokio::test]
    async fn do_send_same_id_divergent_body_returns_conflict() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let mut candidate = sample_candidate("env-conflict");
        do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .unwrap();
        candidate.payload = "different-payload".into();
        let err = do_send(&ctx, TEST_RECIPIENT, candidate)
            .await
            .unwrap_err();
        assert!(matches!(
            err,
            SendError::EnvelopeIdReusedWithDivergentBody { .. }
        ));
    }

    // ─── F2: seq saturation escalates to fatal ────────────────

    #[tokio::test]
    async fn worker_loop_seq_saturation_fails_closed_with_dedicated_fatal() {
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        // Pre-saturate the counter so the first send trips it.
        ctx.seq_counter.store(SEQ_COUNTER_LIMIT, Ordering::SeqCst);
        let mut fatal_rx = ctx.fatal.subscribe();
        let (tx, rx) = mpsc::channel::<RestOp>(1);
        let handle = tokio::spawn(async move { worker_loop(ctx, rx).await });

        let (reply_tx, reply_rx) = oneshot::channel();
        tx.send(RestOp::Send {
            recipient: TEST_RECIPIENT.into(),
            candidate: sample_candidate("env-sat"),
            reply: reply_tx,
        })
        .await
        .unwrap();

        let err = reply_rx.await.unwrap().unwrap_err();
        assert!(matches!(err, SendError::SeqSaturation { .. }));
        let fatal = fatal_rx.recv().await.unwrap();
        assert!(matches!(fatal, FatalReason::SeqSpaceExhausted { .. }));

        drop(tx);
        let outcome = handle.await.unwrap();
        assert!(matches!(
            outcome,
            Err(FatalReason::SeqSpaceExhausted { .. })
        ));
    }

    // ─── worker_loop Send dispatch ────────────────────────────

    #[tokio::test]
    async fn worker_loop_dispatches_send_and_returns_outcome() {
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        let rest_store = Arc::clone(&ctx.rest_store);
        let store = Arc::clone(&ctx.store);
        let capacity = Arc::clone(&ctx.capacity);

        let (tx, rx) = mpsc::channel::<RestOp>(4);
        let loop_handle = tokio::spawn(async move { worker_loop(ctx, rx).await });

        let (reply_tx, reply_rx) = oneshot::channel();
        tx.send(RestOp::Send {
            recipient: TEST_RECIPIENT.into(),
            candidate: sample_candidate("env-loop"),
            reply: reply_tx,
        })
        .await
        .unwrap();

        let outcome = reply_rx.await.unwrap().unwrap();
        assert!(!outcome.idempotent_replay);
        assert_eq!(rest_store.read().await.get(TEST_RECIPIENT).unwrap().len(), 1);
        assert_eq!(store.read().await.get(TEST_RECIPIENT).unwrap().len(), 1);
        assert_eq!(capacity.snapshot().active_envelopes, 1);

        drop(tx);
        let outcome = loop_handle.await.unwrap();
        assert!(matches!(
            outcome,
            Err(FatalReason::WorkerUnexpectedExit { .. })
        ));
    }

    // ─── worker_loop channel-close classification ─────────────

    #[tokio::test]
    async fn worker_loop_channel_close_while_running_is_fatal() {
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        let (tx, rx) = mpsc::channel::<RestOp>(1);
        drop(tx);
        let outcome = worker_loop(ctx, rx).await;
        assert!(matches!(
            outcome,
            Err(FatalReason::WorkerUnexpectedExit { .. })
        ));
    }

    #[tokio::test]
    async fn worker_loop_channel_close_while_closing_is_clean() {
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        let (pool_tx, _pool_rx) = mpsc::channel::<RestOp>(1);
        let builder = WorkerPoolBuilder::new(vec![pool_tx]);
        let reader = builder.state_reader();
        let handles: Vec<tokio::task::JoinHandle<Result<(), FatalReason>>> =
            vec![tokio::spawn(async { Ok(()) })];
        let pool = builder.finalize(handles);
        pool.close();

        let ctx = ActorContext {
            pool_state_reader: reader,
            ..ctx
        };
        let (tx, rx) = mpsc::channel::<RestOp>(1);
        drop(tx);
        let outcome = worker_loop(ctx, rx).await;
        assert!(matches!(outcome, Ok(())));
    }

    // ─── M3b-2b-ii: do_ack unit tests ────────────────────────

    /// Seed a `Queued` on-disk record + populate the active
    /// index accordingly so `do_ack` runs its full happy path.
    /// Also reserves the ledger footprint for a matching
    /// `record_ram_estimate(disk_bytes)`.
    async fn seed_queued_active_for_ack(
        ctx: &ActorContext,
        id: &str,
    ) -> (ActiveEntryMeta, PersistedRecord) {
        let sealed_sender = format!("s-{id}");
        let payload = format!("p-{id}");
        let sequence_ts = 1_720_000_000_000;
        let seq = 42;
        let expires_at = TEST_NOW_EPOCH_SECS + 3_600;
        let body_hash = compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: sealed_sender.clone(),
            payload: payload.clone(),
            sequence_ts,
            seq,
            expires_at,
            seq_mac: "a".repeat(64),
            body_hash: body_hash.clone(),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(&ctx.state_dir, TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let disk_bytes = bytes.len() as u64;
        let ram_bytes = record_ram_estimate(disk_bytes);
        let meta = ActiveEntryMeta {
            path: path.clone(),
            seq,
            body_hash: body_hash.clone(),
            expires_at,
            disk_bytes,
            ram_bytes,
        };
        // Populate all three RAM projections so classify_record_state
        // returns Queued on the optimistic gate.
        {
            let mut rest = ctx.rest_store.write().await;
            rest.entry(TEST_RECIPIENT.to_string())
                .or_default()
                .push(RestEnvelope {
                    id: id.into(),
                    from: String::new(),
                    sealed_sender: sealed_sender.clone(),
                    payload: payload.clone(),
                    sequence_ts,
                    seq,
                    expires_at,
                    seq_mac: "a".repeat(64),
                });
        }
        {
            let mut store = ctx.store.write().await;
            store
                .entry(TEST_RECIPIENT.to_string())
                .or_default()
                .push(Envelope {
                    id: id.into(),
                    to: TEST_RECIPIENT.into(),
                    from: String::new(),
                    sealed_sender,
                    payload,
                    expires_at,
                });
        }
        ctx.active_index
            .try_insert_new(TEST_RECIPIENT, id.into(), meta.clone())
            .expect("first insert");
        // Reserve capacity to match production seed.
        let reservation = ctx
            .capacity
            .reserve_send(disk_bytes, ram_bytes)
            .expect("reserve");
        reservation.commit();
        (meta, record)
    }

    #[tokio::test]
    async fn do_ack_happy_queued_to_tombstone_committed_end_to_end() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let (meta, _rec) = seed_queued_active_for_ack(&ctx, "env-happy-ack").await;
        let snap_before = ctx.capacity.snapshot();
        let seq = meta.seq;

        let outcome = do_ack(&ctx, TEST_RECIPIENT, "env-happy-ack")
            .await
            .expect("Ack OK");
        assert!(matches!(outcome, AckOutcome::Acked { seq: s } if s == seq));

        // Both RAM stores lose the id; active_index empty;
        // tombstone_dedup gains it.
        assert!(ctx.rest_store.read().await.get(TEST_RECIPIENT).unwrap().is_empty());
        assert!(ctx.store.read().await.get(TEST_RECIPIENT).unwrap().is_empty());
        assert_eq!(ctx.active_index.count(), 0);
        assert_eq!(ctx.tombstone_dedup.count(), 1);
        let tomb = ctx.tombstone_dedup.get(TEST_RECIPIENT, "env-happy-ack").unwrap();
        assert_eq!(tomb.seq, seq);
        // Ledger: active released, tombstone added.
        let snap_after = ctx.capacity.snapshot();
        assert_eq!(snap_after.active_envelopes, snap_before.active_envelopes - 1);
        assert_eq!(snap_after.active_bytes, snap_before.active_bytes - meta.disk_bytes);
        assert!(snap_after.tombstone_bytes > snap_before.tombstone_bytes);
        // Disk: same canonical path now holds AckedTombstone bytes.
        let disk = std::fs::read(&meta.path).unwrap();
        let parsed: PersistedRecord = serde_json::from_slice(&disk).unwrap();
        assert!(matches!(parsed, PersistedRecord::AckedTombstone { .. }));
    }

    #[tokio::test]
    async fn do_ack_tombstone_only_returns_idempotent_with_prior_seq_no_mutation() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let (acked_seq, _bh) = seed_tombstone_for_test(
            &ctx,
            "env-idem-ack",
            "sender-env-idem-ack",
            "payload-env-idem-ack",
            999_888,
        )
        .await;
        let snap_before = ctx.capacity.snapshot();
        let outcome = do_ack(&ctx, TEST_RECIPIENT, "env-idem-ack")
            .await
            .expect("Ack OK");
        assert!(matches!(outcome, AckOutcome::Idempotent { seq } if seq == acked_seq));
        // No mutation.
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert_eq!(ctx.tombstone_dedup.count(), 1);
    }

    #[tokio::test]
    async fn do_ack_absent_returns_notfound_no_mutation() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let snap_before = ctx.capacity.snapshot();
        let outcome = do_ack(&ctx, TEST_RECIPIENT, "env-nothing").await.unwrap();
        assert!(matches!(outcome, AckOutcome::NotFound));
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert_eq!(ctx.active_index.count(), 0);
        assert_eq!(ctx.tombstone_dedup.count(), 0);
    }

    #[tokio::test]
    async fn do_ack_wrong_shard_returns_typed_shard_mismatch() {
        let dir = build_state_dir();
        let (mut ctx, _tx) = build_ctx(&dir);
        let expected = worker_for(TEST_RECIPIENT, ctx.worker_hash_key);
        ctx.worker_id = ((expected + 1) % REST_WORKER_COUNT) as u8;
        let err = do_ack(&ctx, TEST_RECIPIENT, "env-shard").await.unwrap_err();
        assert!(matches!(err, AckError::ShardMismatch { .. }));
    }

    #[tokio::test]
    async fn do_ack_clock_before_epoch_returns_typed_no_mutation() {
        let dir = build_state_dir();
        let (mut ctx, _tx) = build_ctx(&dir);
        let (_meta, _rec) = seed_queued_active_for_ack(&ctx, "env-clock").await;
        let snap_before = ctx.capacity.snapshot();
        ctx.clock = ClockSource::BeforeEpoch;
        let err = do_ack(&ctx, TEST_RECIPIENT, "env-clock").await.unwrap_err();
        assert!(matches!(err, AckError::SystemClockBeforeEpoch));
        // No state mutation — clock check fires between step 2
        // (optimistic gate) and step 6 (serialize).
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert_eq!(ctx.active_index.count(), 1);
        assert_eq!(ctx.tombstone_dedup.count(), 0);
    }

    #[tokio::test]
    async fn do_ack_dedup_horizon_overflow_returns_typed_no_mutation() {
        let dir = build_state_dir();
        let (mut ctx, _tx) = build_ctx(&dir);
        let (_meta, _rec) = seed_queued_active_for_ack(&ctx, "env-overflow").await;
        // Clock at u64::MAX so any positive horizon overflows.
        ctx.clock = ClockSource::Fixed(u64::MAX);
        let snap_before = ctx.capacity.snapshot();
        let err = do_ack(&ctx, TEST_RECIPIENT, "env-overflow").await.unwrap_err();
        match err {
            AckError::DedupHorizonOverflow { now, horizon_secs: _ } => {
                assert_eq!(now, u64::MAX);
            }
            other => panic!("expected DedupHorizonOverflow, got: {other:?}"),
        }
        assert_eq!(ctx.capacity.snapshot(), snap_before);
    }

    #[tokio::test]
    async fn do_ack_persistence_failure_via_seam_returns_typed_no_mutation() {
        // Corrective #3 (M3b-2b-ii amendment round-1, REDLINE
        // P1-2): exercise the AckError::Persistence branch via
        // the per-context `test_seams.force_write_bytes_fail`
        // fault seam. The seam is now a
        // `crate::persistence::WriteFault` value passed as a
        // normal per-call input to `write_record_bytes` — Err
        // comes out of the wrapper's real return path, and the
        // production `.map_err(AckError::Persistence)?` mapping
        // is exercised. A canonical-path blocker would trip the
        // optimistic classifier's on-disk footprint check
        // earlier and never reach `write_record_bytes`, so this
        // seam is the ONLY way to drive the persistence branch
        // end-to-end.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let (meta, _rec) = seed_queued_active_for_ack(&ctx, "env-persist").await;
        let original_bytes = std::fs::read(&meta.path).unwrap();
        let snap_before = ctx.capacity.snapshot();

        ctx.test_seams
            .force_write_bytes_fail
            .store(true, std::sync::atomic::Ordering::SeqCst);
        let err = do_ack(&ctx, TEST_RECIPIENT, "env-persist").await.unwrap_err();
        ctx.test_seams
            .force_write_bytes_fail
            .store(false, std::sync::atomic::Ordering::SeqCst);

        assert!(matches!(err, AckError::Persistence(_)));
        // Disk bytes untouched — the seam refused before
        // atomic_write::write_atomic touched the tempfile.
        let after = std::fs::read(&meta.path).unwrap();
        assert_eq!(
            after, original_bytes,
            "durable record must be unchanged on persistence-seam refusal"
        );
        // Ledger + all three indices untouched.
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert_eq!(ctx.active_index.count(), 1);
        assert_eq!(ctx.tombstone_dedup.count(), 0);
        assert_eq!(ctx.rest_store.read().await.get(TEST_RECIPIENT).unwrap().len(), 1);
        assert_eq!(ctx.store.read().await.get(TEST_RECIPIENT).unwrap().len(), 1);
        // No staging tempfiles left behind.
        let staging = collect_staging_tempfiles(&dir.path().join("queue"))
            .expect("walk must not io-fail");
        assert!(staging.is_empty(), "found staging tempfiles: {staging:?}");
    }

    #[tokio::test]
    async fn do_ack_retry_after_successful_ack_returns_idempotent() {
        // End-to-end: first ack succeeds → second identical ack
        // finds only the tombstone → returns Idempotent with
        // the same seq. No double-count, no double disk write.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let (meta, _rec) = seed_queued_active_for_ack(&ctx, "env-retry").await;
        let first = do_ack(&ctx, TEST_RECIPIENT, "env-retry")
            .await
            .expect("first Ack OK");
        let second = do_ack(&ctx, TEST_RECIPIENT, "env-retry")
            .await
            .expect("second Ack OK");
        match (first, second) {
            (AckOutcome::Acked { seq: a }, AckOutcome::Idempotent { seq: b }) => {
                assert_eq!(a, meta.seq);
                assert_eq!(b, meta.seq);
            }
            other => panic!("unexpected pair: {other:?}"),
        }
    }

    // ─── M3b-3a: do_sweep unit tests ─────────────────────────

    /// Seed an `AckedTombstone` on-disk record + populate the
    /// tombstone dedup table + reserve capacity so `do_sweep`
    /// has a full-shape tombstone to sweep.
    async fn seed_tombstone_for_sweep(
        ctx: &ActorContext,
        id: &str,
        dedup_until: u64,
    ) -> (TombstoneEntry, PersistedRecord) {
        let sealed_sender = format!("s-{id}");
        let payload = format!("p-{id}");
        let seq = 100u64;
        let body_hash =
            compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
        let record = PersistedRecord::AckedTombstone {
            version: RECORD_VERSION,
            id: id.into(),
            seq,
            body_hash: body_hash.clone(),
            acked_at: TEST_NOW_EPOCH_SECS,
            dedup_until,
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(&ctx.state_dir, TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let disk_bytes = bytes.len() as u64;
        let ram_bytes = record_ram_estimate(disk_bytes);
        let entry = TombstoneEntry {
            path: path.clone(),
            seq,
            body_hash,
            dedup_until,
            disk_bytes,
            ram_bytes,
        };
        ctx.tombstone_dedup
            .try_insert_new(TEST_RECIPIENT, id.into(), entry.clone())
            .expect("first insert");
        let footprint = crate::capacity_ledger::RecordFootprint {
            kind: crate::capacity_ledger::RecordKind::AckedTombstone,
            disk_bytes,
            ram_bytes,
        };
        ctx.capacity
            .transition(None, Some(footprint))
            .expect("tombstone footprint fits caps");
        (entry, record)
    }

    #[tokio::test]
    async fn do_sweep_happy_queued_single_expired_full_state_transition() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        // expires_at strictly less than TEST_NOW_EPOCH_SECS so
        // `<= now` holds.
        let expired_at = TEST_NOW_EPOCH_SECS - 10;
        let (meta, _rec) = seed_queued_active_for_ack_with_expires_at(
            &ctx,
            "env-sweep-q",
            expired_at,
        )
        .await;
        let snap_before = ctx.capacity.snapshot();
        // Baseline: state populated.
        assert!(std::fs::metadata(&meta.path).is_ok());
        assert_eq!(ctx.active_index.count(), 1);
        assert_eq!(
            ctx.rest_store.read().await.get(TEST_RECIPIENT).unwrap().len(),
            1
        );

        let outcome = do_sweep(&ctx, TEST_RECIPIENT)
            .await
            .expect("sweep must succeed");
        assert_eq!(outcome.queued_swept, 1);
        assert_eq!(outcome.tombstones_swept, 0);
        assert_eq!(outcome.disk_reclaimed_bytes, meta.disk_bytes);

        // Disk unlinked.
        assert!(
            std::fs::metadata(&meta.path).is_err(),
            "record file must be gone after sweep"
        );
        // RAM projections drained.
        assert_eq!(
            ctx.rest_store
                .read()
                .await
                .get(TEST_RECIPIENT)
                .map(|b| b.len())
                .unwrap_or(0),
            0
        );
        assert_eq!(
            ctx.store
                .read()
                .await
                .get(TEST_RECIPIENT)
                .map(|b| b.len())
                .unwrap_or(0),
            0
        );
        // Index cleared.
        assert_eq!(ctx.active_index.count(), 0);
        assert_eq!(ctx.tombstone_dedup.count(), 0);
        // Ledger released.
        let snap_after = ctx.capacity.snapshot();
        assert_eq!(snap_after.active_envelopes, 0);
        assert_eq!(
            snap_after.active_bytes,
            snap_before.active_bytes - meta.disk_bytes
        );
        assert_eq!(
            snap_after.ram_bytes,
            snap_before.ram_bytes - meta.ram_bytes
        );
    }

    #[tokio::test]
    async fn do_sweep_happy_tombstone_single_expired_full_state_transition() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let expired_dedup = TEST_NOW_EPOCH_SECS - 5;
        let (entry, _rec) = seed_tombstone_for_sweep(&ctx, "env-sweep-t", expired_dedup).await;
        let snap_before = ctx.capacity.snapshot();
        assert_eq!(ctx.tombstone_dedup.count(), 1);
        assert!(std::fs::metadata(&entry.path).is_ok());

        let outcome = do_sweep(&ctx, TEST_RECIPIENT).await.expect("sweep OK");
        assert_eq!(outcome.queued_swept, 0);
        assert_eq!(outcome.tombstones_swept, 1);
        assert_eq!(outcome.disk_reclaimed_bytes, entry.disk_bytes);

        assert!(std::fs::metadata(&entry.path).is_err());
        assert_eq!(ctx.tombstone_dedup.count(), 0);
        let snap_after = ctx.capacity.snapshot();
        assert_eq!(snap_after.tombstone_records, 0);
        assert_eq!(
            snap_after.tombstone_bytes,
            snap_before.tombstone_bytes - entry.disk_bytes
        );
        assert_eq!(
            snap_after.ram_bytes,
            snap_before.ram_bytes - entry.ram_bytes
        );
    }

    #[tokio::test]
    async fn do_sweep_mixed_state_expired_queued_and_tombstone_both_swept() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let (q_meta, _) = seed_queued_active_for_ack_with_expires_at(
            &ctx,
            "env-mix-q",
            TEST_NOW_EPOCH_SECS - 1,
        )
        .await;
        let (t_entry, _) =
            seed_tombstone_for_sweep(&ctx, "env-mix-t", TEST_NOW_EPOCH_SECS - 1).await;

        let outcome = do_sweep(&ctx, TEST_RECIPIENT).await.expect("sweep OK");
        assert_eq!(outcome.queued_swept, 1);
        assert_eq!(outcome.tombstones_swept, 1);
        assert_eq!(
            outcome.disk_reclaimed_bytes,
            q_meta.disk_bytes + t_entry.disk_bytes
        );

        assert!(std::fs::metadata(&q_meta.path).is_err());
        assert!(std::fs::metadata(&t_entry.path).is_err());
        assert_eq!(ctx.active_index.count(), 0);
        assert_eq!(ctx.tombstone_dedup.count(), 0);
        let snap = ctx.capacity.snapshot();
        assert_eq!(snap.active_envelopes, 0);
        assert_eq!(snap.tombstone_records, 0);
    }

    #[tokio::test]
    async fn do_sweep_equality_boundary_expires_at_equals_now_is_swept() {
        // Scope constraint #2: expiry boundary is `<= now`.
        // An entry with `expires_at == now` is expired and
        // MUST be swept.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let (meta, _) = seed_queued_active_for_ack_with_expires_at(
            &ctx,
            "env-eq",
            TEST_NOW_EPOCH_SECS, // exactly at boundary
        )
        .await;
        let outcome = do_sweep(&ctx, TEST_RECIPIENT).await.expect("sweep OK");
        assert_eq!(outcome.queued_swept, 1);
        assert!(std::fs::metadata(&meta.path).is_err());
    }

    #[tokio::test]
    async fn do_sweep_non_expired_records_preserved_byte_identical_no_counters_touched() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let live_expires = TEST_NOW_EPOCH_SECS + 3_600;
        let (q_meta, _) = seed_queued_active_for_ack_with_expires_at(
            &ctx,
            "env-live-q",
            live_expires,
        )
        .await;
        let (t_entry, _) =
            seed_tombstone_for_sweep(&ctx, "env-live-t", TEST_NOW_EPOCH_SECS + 7_200).await;

        let original_q_bytes = std::fs::read(&q_meta.path).unwrap();
        let original_t_bytes = std::fs::read(&t_entry.path).unwrap();
        let snap_before = ctx.capacity.snapshot();

        let outcome = do_sweep(&ctx, TEST_RECIPIENT).await.expect("sweep OK");
        assert_eq!(outcome.queued_swept, 0);
        assert_eq!(outcome.tombstones_swept, 0);
        assert_eq!(outcome.disk_reclaimed_bytes, 0);

        // Byte-identical disk state.
        assert_eq!(std::fs::read(&q_meta.path).unwrap(), original_q_bytes);
        assert_eq!(std::fs::read(&t_entry.path).unwrap(), original_t_bytes);
        // Counters untouched.
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert_eq!(ctx.active_index.count(), 1);
        assert_eq!(ctx.tombstone_dedup.count(), 1);
        assert_eq!(
            ctx.rest_store.read().await.get(TEST_RECIPIENT).unwrap().len(),
            1
        );
        assert_eq!(
            ctx.store.read().await.get(TEST_RECIPIENT).unwrap().len(),
            1
        );
    }

    #[tokio::test]
    async fn do_sweep_repeat_after_full_sweep_is_zero_count_no_op() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let _ = seed_queued_active_for_ack_with_expires_at(
            &ctx,
            "env-once-q",
            TEST_NOW_EPOCH_SECS - 100,
        )
        .await;
        let _ = seed_tombstone_for_sweep(&ctx, "env-once-t", TEST_NOW_EPOCH_SECS - 100).await;

        let first = do_sweep(&ctx, TEST_RECIPIENT).await.expect("first sweep");
        assert_eq!(first.queued_swept, 1);
        assert_eq!(first.tombstones_swept, 1);
        let snap_after_first = ctx.capacity.snapshot();

        let second = do_sweep(&ctx, TEST_RECIPIENT).await.expect("second sweep");
        assert_eq!(second.queued_swept, 0);
        assert_eq!(second.tombstones_swept, 0);
        assert_eq!(second.disk_reclaimed_bytes, 0);
        assert_eq!(ctx.capacity.snapshot(), snap_after_first);
    }

    // ─── Round-2 REDLINE P1: SweepError::Persistence via ────
    // per-context RemoveFault seam ─────────────────────────────
    //
    // Round-1 REDLINE closed the classifier-level "missing
    // file" scenario (see the adversarial pre-unlink tests
    // above). The pre-unlink → fatal path is safe. What was
    // LOST was direct coverage of `SweepError::Persistence`
    // as a return surface — the classifier catches missing
    // files before `remove_record_file` is called, so the
    // typed error is unreachable via the natural
    // out-of-band-delete pattern.
    //
    // The round-2 fix re-establishes coverage via a per-call
    // `RemoveFault` seam in the `remove_record_file` wrapper,
    // exposed through `ctx.test_seams.force_remove_file_fail`
    // (per-`ActorContext`, not a global static — same rationale
    // as the M3b-2b-ii `WriteFault` reshape). Tests arm the
    // seam and drive the sweep through classify + verify
    // (which both accept a real, on-disk-consistent state),
    // then observe the typed `SweepError::Persistence` at the
    // wrapper boundary. Bytes on disk are preserved (the
    // wrapper refuses BEFORE calling `fs::remove_file`), and
    // every RAM projection + ledger counter remains unchanged.

    #[tokio::test]
    async fn do_sweep_queued_persistence_failure_via_ctx_seam_returns_typed_no_mutation() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let (meta, _rec) = seed_queued_active_for_ack_with_expires_at(
            &ctx,
            "env-q-persist",
            TEST_NOW_EPOCH_SECS - 1,
        )
        .await;
        let original_bytes = std::fs::read(&meta.path).unwrap();
        let snap_before = ctx.capacity.snapshot();

        // Arm the per-ctx seam.
        ctx.test_seams
            .force_remove_file_fail
            .store(true, std::sync::atomic::Ordering::SeqCst);
        let err = do_sweep(&ctx, TEST_RECIPIENT)
            .await
            .expect_err("sweep must surface Persistence Err");
        ctx.test_seams
            .force_remove_file_fail
            .store(false, std::sync::atomic::Ordering::SeqCst);

        // Typed error.
        match err {
            SweepError::Persistence(io) => {
                assert_eq!(io.kind(), std::io::ErrorKind::Other);
            }
            other => panic!("expected SweepError::Persistence, got: {other:?}"),
        }

        // Disk bytes byte-identical.
        assert_eq!(
            std::fs::read(&meta.path).unwrap(),
            original_bytes,
            "canonical file bytes must be unchanged"
        );
        // All four RAM projections + ledger untouched.
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert_eq!(ctx.active_index.count(), 1);
        assert_eq!(ctx.tombstone_dedup.count(), 0);
        assert_eq!(
            ctx.rest_store.read().await.get(TEST_RECIPIENT).unwrap().len(),
            1
        );
        assert_eq!(
            ctx.store.read().await.get(TEST_RECIPIENT).unwrap().len(),
            1
        );
    }

    #[tokio::test]
    async fn do_sweep_tombstone_persistence_failure_via_ctx_seam_returns_typed_no_mutation() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let (entry, _rec) =
            seed_tombstone_for_sweep(&ctx, "env-t-persist", TEST_NOW_EPOCH_SECS - 1).await;
        let original_bytes = std::fs::read(&entry.path).unwrap();
        let snap_before = ctx.capacity.snapshot();

        ctx.test_seams
            .force_remove_file_fail
            .store(true, std::sync::atomic::Ordering::SeqCst);
        let err = do_sweep(&ctx, TEST_RECIPIENT)
            .await
            .expect_err("sweep must surface Persistence Err");
        ctx.test_seams
            .force_remove_file_fail
            .store(false, std::sync::atomic::Ordering::SeqCst);

        match err {
            SweepError::Persistence(io) => {
                assert_eq!(io.kind(), std::io::ErrorKind::Other);
            }
            other => panic!("expected SweepError::Persistence, got: {other:?}"),
        }
        // Disk bytes byte-identical.
        assert_eq!(
            std::fs::read(&entry.path).unwrap(),
            original_bytes,
            "tombstone canonical file bytes must be unchanged"
        );
        // All four RAM projections + ledger untouched (rest_store
        // + store are always empty for a tombstone-only recipient
        // but assert nonetheless for completeness).
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert_eq!(ctx.active_index.count(), 0);
        assert_eq!(ctx.tombstone_dedup.count(), 1);
        assert_eq!(
            ctx.rest_store
                .read()
                .await
                .get(TEST_RECIPIENT)
                .map(|b| b.len())
                .unwrap_or(0),
            0
        );
        assert_eq!(
            ctx.store
                .read()
                .await
                .get(TEST_RECIPIENT)
                .map(|b| b.len())
                .unwrap_or(0),
            0
        );
    }

    #[tokio::test]
    async fn do_sweep_wrong_shard_returns_typed_shard_mismatch() {
        let dir = build_state_dir();
        let (mut ctx, _tx) = build_ctx(&dir);
        // Corrupt the worker_id to something that cannot own
        // TEST_RECIPIENT under TEST_KEY. worker_for is
        // deterministic so we pick a value guaranteed to differ.
        let owning = worker_for(TEST_RECIPIENT, TEST_KEY);
        ctx.worker_id = ((owning + 1) % REST_WORKER_COUNT) as u8;
        let err = do_sweep(&ctx, TEST_RECIPIENT)
            .await
            .expect_err("shard-mismatch expected");
        match err {
            SweepError::ShardMismatch {
                expected_worker,
                actual_worker,
            } => {
                assert_eq!(expected_worker, owning);
                assert_eq!(actual_worker, usize::from(ctx.worker_id));
            }
            other => panic!("expected ShardMismatch, got: {other:?}"),
        }
    }

    #[tokio::test]
    async fn do_sweep_clock_before_epoch_returns_typed_no_mutation() {
        let dir = build_state_dir();
        let (mut ctx, _tx) = build_ctx(&dir);
        ctx.clock = ClockSource::BeforeEpoch;
        let (meta, _) = seed_queued_active_for_ack_with_expires_at(
            &ctx,
            "env-clock",
            TEST_NOW_EPOCH_SECS - 1,
        )
        .await;
        let snap_before = ctx.capacity.snapshot();
        let err = do_sweep(&ctx, TEST_RECIPIENT)
            .await
            .expect_err("expected SystemClockBeforeEpoch");
        assert!(matches!(err, SweepError::SystemClockBeforeEpoch));
        // Nothing touched.
        assert!(std::fs::metadata(&meta.path).is_ok());
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert_eq!(ctx.active_index.count(), 1);
    }

    #[tokio::test]
    async fn do_sweep_empty_recipient_returns_zero_no_error() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let snap_before = ctx.capacity.snapshot();
        let outcome = do_sweep(&ctx, TEST_RECIPIENT).await.expect("sweep OK");
        assert_eq!(outcome.queued_swept, 0);
        assert_eq!(outcome.tombstones_swept, 0);
        assert_eq!(outcome.disk_reclaimed_bytes, 0);
        assert_eq!(ctx.capacity.snapshot(), snap_before);
    }

    // ─── M3b-3a round-1 REDLINE: adversarial pre-unlink gate ─
    //
    // These tests drive `classify_record_state` and
    // `verify_durable_queued` DIRECTLY with adversarial inputs
    // and assert `Err(DriftDetail)`. Under `do_sweep` such an
    // Err triggers `fatal_sweep_invariant("pre_unlink_reverify")`
    // → `std::process::abort()` BEFORE any filesystem
    // mutation, so the "bytes preserved" property holds by
    // construction: the classifier catches the drift, and if
    // it hadn't, `verify_durable_queued` would; if BOTH
    // somehow passed, `remove_file` still hasn't been called.
    // The end-to-end fatal proof lands with the subprocess
    // test in M3b-3b (dispatch wiring).

    #[tokio::test]
    async fn sweep_pre_unlink_classifier_rejects_queued_plus_tombstone_double_state() {
        // Double state: id present in BOTH queued-side
        // (rest+store+active) AND tombstone_dedup. The old
        // per-projection sweep would let the tombstone path
        // silently unlink the file the Queued path still points
        // at. Classifier now catches this pre-unlink.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let id = "env-double";
        // Seed Queued side.
        let (meta, _) = seed_queued_active_for_ack_with_expires_at(
            &ctx,
            id,
            TEST_NOW_EPOCH_SECS - 1,
        )
        .await;
        // Manually add a tombstone dedup entry for the same id.
        let bogus_tombstone = TombstoneEntry {
            path: meta.path.clone(),
            seq: meta.seq,
            body_hash: meta.body_hash.clone(),
            dedup_until: TEST_NOW_EPOCH_SECS - 1,
            disk_bytes: meta.disk_bytes,
            ram_bytes: meta.ram_bytes,
        };
        ctx.tombstone_dedup
            .try_insert_new(TEST_RECIPIENT, id.into(), bogus_tombstone.clone())
            .expect("insert bogus tombstone for adversarial setup");

        let original_bytes = std::fs::read(&meta.path).expect("real file exists");

        // Directly invoke classify_record_state — sweep_one
        // would wrap this Err in fatal_sweep_invariant.
        let rest_guard = ctx.rest_store.read().await;
        let store_guard = ctx.store.read().await;
        let active_guard = ctx.active_index.inner.read();
        let tombstone_guard = ctx.tombstone_dedup.inner.read();
        let rest_hit = rest_guard.get(TEST_RECIPIENT).and_then(|b| b.iter().find(|e| e.id == id));
        let store_hit = store_guard.get(TEST_RECIPIENT).and_then(|b| b.iter().find(|e| e.id == id));
        let active_hit = active_guard.get(TEST_RECIPIENT).and_then(|b| b.get(id));
        let tombstone_hit = tombstone_guard.get(TEST_RECIPIENT).and_then(|b| b.get(id));

        let result = classify_record_state(
            rest_hit,
            store_hit,
            active_hit,
            tombstone_hit,
            TEST_RECIPIENT,
            id,
            &ctx.state_dir,
        );
        match result {
            Err(drift) => {
                assert!(
                    drift.detail.contains("tombstone_dedup + queued-side"),
                    "unexpected drift detail: {}",
                    drift.detail
                );
            }
            Ok(other) => panic!("expected drift, got: {other:?}"),
        }

        // Bytes preserved — classifier caught the drift before
        // any destructive action.
        let bytes_after = std::fs::read(&meta.path).expect("file still present");
        assert_eq!(bytes_after, original_bytes);
    }

    #[tokio::test]
    async fn sweep_pre_unlink_classifier_rejects_partial_state_rest_without_active() {
        // Partial state: rest_store has the entry but
        // active_index does not. Classifier rejects.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let id = "env-partial-r";
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: format!("s-{id}"),
            payload: format!("p-{id}"),
            sequence_ts: 1_720_000_000_000,
            seq: 42,
            expires_at: TEST_NOW_EPOCH_SECS - 1,
            seq_mac: "a".repeat(64),
            body_hash: compute_body_hash_hex(
                format!("s-{id}").as_bytes(),
                format!("p-{id}").as_bytes(),
            ),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(&ctx.state_dir, TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        // Populate rest_store only.
        {
            let mut rest = ctx.rest_store.write().await;
            rest.entry(TEST_RECIPIENT.into()).or_default().push(RestEnvelope {
                id: id.into(),
                from: String::new(),
                sealed_sender: format!("s-{id}"),
                payload: format!("p-{id}"),
                sequence_ts: 1_720_000_000_000,
                seq: 42,
                expires_at: TEST_NOW_EPOCH_SECS - 1,
                seq_mac: "a".repeat(64),
            });
        }

        let original_bytes = std::fs::read(&path).unwrap();
        let rest_guard = ctx.rest_store.read().await;
        let store_guard = ctx.store.read().await;
        let active_guard = ctx.active_index.inner.read();
        let tombstone_guard = ctx.tombstone_dedup.inner.read();
        let rest_hit = rest_guard.get(TEST_RECIPIENT).and_then(|b| b.iter().find(|e| e.id == id));
        let store_hit = store_guard.get(TEST_RECIPIENT).and_then(|b| b.iter().find(|e| e.id == id));
        let active_hit = active_guard.get(TEST_RECIPIENT).and_then(|b| b.get(id));
        let tombstone_hit = tombstone_guard.get(TEST_RECIPIENT).and_then(|b| b.get(id));

        let result = classify_record_state(
            rest_hit,
            store_hit,
            active_hit,
            tombstone_hit,
            TEST_RECIPIENT,
            id,
            &ctx.state_dir,
        );
        match result {
            Err(drift) => {
                assert!(
                    drift.detail.contains("partial state"),
                    "unexpected drift detail: {}",
                    drift.detail
                );
            }
            Ok(other) => panic!("expected drift, got: {other:?}"),
        }
        assert_eq!(std::fs::read(&path).unwrap(), original_bytes);
    }

    #[tokio::test]
    async fn sweep_pre_unlink_verify_disk_footprint_rejects_wrong_canonical_path() {
        // ActiveEntryMeta.path points at a file OUTSIDE the
        // canonical record_path(...) for (recipient, id). The
        // disk-footprint verifier catches this. If it didn't,
        // sweep would unlink the wrong file.
        let dir = build_state_dir();
        let id = "env-wrong-path";
        let canonical = record_path(dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(canonical.parent().unwrap()).unwrap();
        let canonical_bytes = b"canonical sentinel bytes".to_vec();
        std::fs::write(&canonical, &canonical_bytes).unwrap();
        // Bogus path different from canonical.
        let bogus = dir.path().join("bogus-target");
        std::fs::write(&bogus, b"bogus content").unwrap();

        let result = verify_disk_footprint(
            &bogus, // wrong path
            13,
            dir.path(),
            TEST_RECIPIENT,
            id,
        );
        assert!(matches!(result, Err(_)));

        // Canonical bytes preserved: verify_disk_footprint is a
        // check, never touches the filesystem destructively.
        assert_eq!(std::fs::read(&canonical).unwrap(), canonical_bytes);
    }

    #[tokio::test]
    async fn sweep_pre_unlink_verify_disk_footprint_rejects_directory_at_canonical_path() {
        // Cross-platform "not a regular file" test — a
        // directory at the canonical path. Symlinks are gated
        // by admin on Windows so we exercise the directory
        // branch of `verify_disk_footprint`.
        let dir = build_state_dir();
        let id = "env-dir";
        let canonical = record_path(dir.path(), TEST_RECIPIENT, id);
        // Make canonical be a directory.
        std::fs::create_dir_all(&canonical).unwrap();

        let result = verify_disk_footprint(
            &canonical,
            0,
            dir.path(),
            TEST_RECIPIENT,
            id,
        );
        match result {
            Err(drift) => assert!(
                drift.detail.contains("not a regular file"),
                "unexpected drift: {}",
                drift.detail
            ),
            Ok(_) => panic!("verify_disk_footprint accepted a directory"),
        }
        // Directory still there.
        assert!(std::fs::metadata(&canonical).unwrap().is_dir());
    }

    #[tokio::test]
    async fn sweep_pre_unlink_verify_durable_queued_rejects_wrong_variant_on_disk() {
        // On-disk record parses as AckedTombstone but RAM
        // says it's Queued. verify_durable_queued rejects.
        let dir = build_state_dir();
        let id = "env-variant";
        let tomb_record = PersistedRecord::AckedTombstone {
            version: RECORD_VERSION,
            id: id.into(),
            seq: 7,
            body_hash: "a".repeat(64),
            acked_at: TEST_NOW_EPOCH_SECS - 100,
            dedup_until: TEST_NOW_EPOCH_SECS + 1_000,
        };
        let bytes = serde_json::to_vec(&tomb_record).unwrap();
        let path = record_path(dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let original = std::fs::read(&path).unwrap();
        let meta = ActiveEntryMeta {
            path: path.clone(),
            seq: 7,
            body_hash: "a".repeat(64),
            expires_at: TEST_NOW_EPOCH_SECS + 1_000,
            disk_bytes: bytes.len() as u64,
            ram_bytes: record_ram_estimate(bytes.len() as u64),
        };
        let result = verify_durable_queued(&path, id, &meta);
        match result {
            Err(drift) => assert!(
                drift.detail.contains("is AckedTombstone, not Queued"),
                "unexpected drift: {}",
                drift.detail
            ),
            Ok(_) => panic!("accepted AckedTombstone as Queued"),
        }
        assert_eq!(std::fs::read(&path).unwrap(), original);
    }

    #[tokio::test]
    async fn sweep_pre_unlink_verify_durable_queued_rejects_id_mismatch() {
        let dir = build_state_dir();
        let path = record_path(dir.path(), TEST_RECIPIENT, "expected-id");
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: "different-id".into(), // MISMATCH
            sealed_sender: "s".into(),
            payload: "p".into(),
            sequence_ts: 1_720_000_000_000,
            seq: 1,
            expires_at: TEST_NOW_EPOCH_SECS + 10,
            seq_mac: "a".repeat(64),
            body_hash: "b".repeat(64),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let original = std::fs::read(&path).unwrap();
        let meta = ActiveEntryMeta {
            path: path.clone(),
            seq: 1,
            body_hash: "b".repeat(64),
            expires_at: TEST_NOW_EPOCH_SECS + 10,
            disk_bytes: bytes.len() as u64,
            ram_bytes: 0,
        };
        let result = verify_durable_queued(&path, "expected-id", &meta);
        match result {
            Err(drift) => assert!(drift.detail.contains("id mismatch"), "{}", drift.detail),
            Ok(_) => panic!("accepted id mismatch"),
        }
        assert_eq!(std::fs::read(&path).unwrap(), original);
    }

    #[tokio::test]
    async fn sweep_pre_unlink_verify_durable_queued_rejects_seq_mismatch() {
        let dir = build_state_dir();
        let id = "env-seq";
        let path = record_path(dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: "s".into(),
            payload: "p".into(),
            sequence_ts: 1_720_000_000_000,
            seq: 100, // ← disk
            expires_at: TEST_NOW_EPOCH_SECS + 10,
            seq_mac: "a".repeat(64),
            body_hash: "b".repeat(64),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let original = std::fs::read(&path).unwrap();
        let meta = ActiveEntryMeta {
            path: path.clone(),
            seq: 200, // ← RAM (mismatch)
            body_hash: "b".repeat(64),
            expires_at: TEST_NOW_EPOCH_SECS + 10,
            disk_bytes: bytes.len() as u64,
            ram_bytes: 0,
        };
        let result = verify_durable_queued(&path, id, &meta);
        match result {
            Err(drift) => assert!(drift.detail.contains("seq mismatch"), "{}", drift.detail),
            Ok(_) => panic!("accepted seq mismatch"),
        }
        assert_eq!(std::fs::read(&path).unwrap(), original);
    }

    #[tokio::test]
    async fn sweep_pre_unlink_verify_durable_queued_rejects_body_hash_mismatch() {
        let dir = build_state_dir();
        let id = "env-bh";
        let path = record_path(dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: "s".into(),
            payload: "p".into(),
            sequence_ts: 1_720_000_000_000,
            seq: 1,
            expires_at: TEST_NOW_EPOCH_SECS + 10,
            seq_mac: "a".repeat(64),
            body_hash: "b".repeat(64),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let original = std::fs::read(&path).unwrap();
        let meta = ActiveEntryMeta {
            path: path.clone(),
            seq: 1,
            body_hash: "c".repeat(64), // mismatch
            expires_at: TEST_NOW_EPOCH_SECS + 10,
            disk_bytes: bytes.len() as u64,
            ram_bytes: 0,
        };
        let result = verify_durable_queued(&path, id, &meta);
        match result {
            Err(drift) => {
                assert!(drift.detail.contains("body_hash mismatch"), "{}", drift.detail)
            }
            Ok(_) => panic!("accepted body_hash mismatch"),
        }
        assert_eq!(std::fs::read(&path).unwrap(), original);
    }

    #[tokio::test]
    async fn sweep_pre_unlink_verify_durable_queued_rejects_expires_at_mismatch() {
        let dir = build_state_dir();
        let id = "env-ea";
        let path = record_path(dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: "s".into(),
            payload: "p".into(),
            sequence_ts: 1_720_000_000_000,
            seq: 1,
            expires_at: TEST_NOW_EPOCH_SECS + 100, // disk
            seq_mac: "a".repeat(64),
            body_hash: "b".repeat(64),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let original = std::fs::read(&path).unwrap();
        let meta = ActiveEntryMeta {
            path: path.clone(),
            seq: 1,
            body_hash: "b".repeat(64),
            expires_at: TEST_NOW_EPOCH_SECS + 200, // RAM (mismatch)
            disk_bytes: bytes.len() as u64,
            ram_bytes: 0,
        };
        let result = verify_durable_queued(&path, id, &meta);
        match result {
            Err(drift) => assert!(
                drift.detail.contains("expires_at mismatch"),
                "{}",
                drift.detail
            ),
            Ok(_) => panic!("accepted expires_at mismatch"),
        }
        assert_eq!(std::fs::read(&path).unwrap(), original);
    }

    #[tokio::test]
    async fn sweep_pre_unlink_verify_durable_queued_rejects_unknown_schema_version() {
        // read_record refuses `version != META_VERSION`. Written
        // directly as JSON with a bogus version field.
        let dir = build_state_dir();
        let id = "env-ver";
        let path = record_path(dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        let bogus = serde_json::json!({
            "Queued": {
                "version": 9999,
                "id": id,
                "sealed_sender": "s",
                "payload": "p",
                "sequence_ts": 1_720_000_000_000_u64,
                "seq": 1_u64,
                "expires_at": TEST_NOW_EPOCH_SECS + 10,
                "seq_mac": "a".repeat(64),
                "body_hash": "b".repeat(64),
            }
        });
        let bytes = serde_json::to_vec(&bogus).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let original = std::fs::read(&path).unwrap();
        let meta = ActiveEntryMeta {
            path: path.clone(),
            seq: 1,
            body_hash: "b".repeat(64),
            expires_at: TEST_NOW_EPOCH_SECS + 10,
            disk_bytes: bytes.len() as u64,
            ram_bytes: 0,
        };
        let result = verify_durable_queued(&path, id, &meta);
        // read_record produces either UnknownVersion or Json
        // depending on schema; both must reject.
        match result {
            Err(drift) => {
                assert!(
                    drift.detail.contains("unknown schema version")
                        || drift.detail.contains("parse failed"),
                    "unexpected drift: {}",
                    drift.detail
                );
            }
            Ok(_) => panic!("accepted bogus schema version"),
        }
        assert_eq!(std::fs::read(&path).unwrap(), original);
    }

    #[tokio::test]
    async fn sweep_pre_unlink_verify_durable_queued_accepts_matching_record() {
        // Positive control — a real Queued record whose disk
        // bytes agree with the meta passes verify_durable_queued.
        let dir = build_state_dir();
        let id = "env-good";
        let sealed_sender = format!("s-{id}");
        let payload = format!("p-{id}");
        let body_hash =
            compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
        let expires_at = TEST_NOW_EPOCH_SECS + 500;
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: sealed_sender.clone(),
            payload: payload.clone(),
            sequence_ts: 1_720_000_000_000,
            seq: 5,
            expires_at,
            seq_mac: "a".repeat(64),
            body_hash: body_hash.clone(),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let meta = ActiveEntryMeta {
            path: path.clone(),
            seq: 5,
            body_hash,
            expires_at,
            disk_bytes: bytes.len() as u64,
            ram_bytes: record_ram_estimate(bytes.len() as u64),
        };
        let result = verify_durable_queued(&path, id, &meta);
        assert!(result.is_ok(), "verify_durable_queued rejected valid record: {result:?}");
    }

    /// Seed helper that takes an explicit `expires_at` so
    /// sweep tests can build both live and expired records
    /// against a fixed `TEST_NOW_EPOCH_SECS`. Mirrors
    /// [`seed_queued_active_for_ack`] which pins
    /// `expires_at = TEST_NOW_EPOCH_SECS + 3600`.
    async fn seed_queued_active_for_ack_with_expires_at(
        ctx: &ActorContext,
        id: &str,
        expires_at: u64,
    ) -> (ActiveEntryMeta, PersistedRecord) {
        let sealed_sender = format!("s-{id}");
        let payload = format!("p-{id}");
        let sequence_ts = 1_720_000_000_000;
        let seq = 42;
        let body_hash =
            compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: sealed_sender.clone(),
            payload: payload.clone(),
            sequence_ts,
            seq,
            expires_at,
            seq_mac: "a".repeat(64),
            body_hash: body_hash.clone(),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(&ctx.state_dir, TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let disk_bytes = bytes.len() as u64;
        let ram_bytes = record_ram_estimate(disk_bytes);
        let meta = ActiveEntryMeta {
            path: path.clone(),
            seq,
            body_hash: body_hash.clone(),
            expires_at,
            disk_bytes,
            ram_bytes,
        };
        {
            let mut rest = ctx.rest_store.write().await;
            rest.entry(TEST_RECIPIENT.to_string())
                .or_default()
                .push(RestEnvelope {
                    id: id.into(),
                    from: String::new(),
                    sealed_sender: sealed_sender.clone(),
                    payload: payload.clone(),
                    sequence_ts,
                    seq,
                    expires_at,
                    seq_mac: "a".repeat(64),
                });
        }
        {
            let mut store = ctx.store.write().await;
            store
                .entry(TEST_RECIPIENT.to_string())
                .or_default()
                .push(Envelope {
                    id: id.into(),
                    to: TEST_RECIPIENT.into(),
                    from: String::new(),
                    sealed_sender,
                    payload,
                    expires_at,
                });
        }
        ctx.active_index
            .try_insert_new(TEST_RECIPIENT, id.into(), meta.clone())
            .expect("first insert");
        let reservation = ctx
            .capacity
            .reserve_send(disk_bytes, ram_bytes)
            .expect("reserve");
        reservation.commit();
        (meta, record)
    }

    // ─── M3b-2b-ii corrective #4: subprocess reboot-replay ─

    /// Child sub-test entry for the ACK post-commit subprocess
    /// test. When `PHANTOM_M3B2B_II_FORCE_POST_COMMIT_FATAL`
    /// is set:
    ///   1. Load state_dir from `PHANTOM_M3B2B_II_STATE_DIR`;
    ///   2. Run REAL production flow: `boot()` +
    ///      `WorkerRuntimeSpec::from_boot()` +
    ///      `spawn_worker_runtime()`;
    ///   3. Set `TEST_FORCE_ACK_POST_COMMIT_FATAL` seam;
    ///   4. `runtime.try_send(RestOp::Ack { .. })` for the
    ///      pre-seeded envelope;
    ///   5. Wait briefly for reply — abort should fire before
    ///      it lands.
    ///
    /// Prints `CHILD_REACHED_END` if the abort never fires
    /// (parent asserts this string is NOT in stderr).
    #[test]
    fn ack_post_commit_forced_abort_child() {
        if std::env::var("PHANTOM_M3B2B_II_FORCE_POST_COMMIT_FATAL").is_err() {
            return;
        }
        let state_dir = std::env::var_os("PHANTOM_M3B2B_II_STATE_DIR")
            .map(std::path::PathBuf::from)
            .expect("PHANTOM_M3B2B_II_STATE_DIR must be provided by parent");
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async {
            let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
            let boot_cfg = crate::boot_loader::BootConfig {
                state_dir: state_dir.clone(),
                caps: crate::boot_loader::PreflightCaps::for_tests(),
                tombstone: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
                current_seq_mac_key_fingerprint: key.fingerprint(),
                ownership: crate::boot_loader::OwnershipExpectation::permissive_for_tests(),
            };
            let boot_result = crate::boot_loader::boot(&boot_cfg).expect("boot OK");
            let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(16);
            let spec = WorkerRuntimeSpec::from_boot(
                boot_result,
                8,
                Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
                caps(),
                fatal_tx,
            )
            .expect("from_boot OK");
            let runtime = spawn_worker_runtime(spec).expect("spawn OK");
            TEST_FORCE_ACK_POST_COMMIT_FATAL
                .store(true, std::sync::atomic::Ordering::SeqCst);

            let (reply_tx, reply_rx) = oneshot::channel();
            runtime
                .try_send(RestOp::Ack {
                    recipient: TEST_RECIPIENT.into(),
                    envelope_id: "env-subproc-ack".into(),
                    reply: reply_tx,
                })
                .expect("try_send OK");
            let _ = tokio::time::timeout(std::time::Duration::from_secs(5), reply_rx).await;
        });
        eprintln!("CHILD_REACHED_END");
    }

    #[test]
    fn ack_post_commit_fatal_aborts_child_and_reboot_replay_shows_tombstone() {
        // **M3b-2b-ii corrective #4**: full production-flow
        // subprocess.
        //
        // Parent seeds a Queued record on disk; child boots the
        // real production pipeline (boot → from_boot → spawn →
        // runtime.try_send(Ack)) with the post_commit fault
        // seam armed. do_ack aborts AFTER durable disk commit
        // but BEFORE index/ledger mutation.
        //
        // Parent verifies:
        //   * non-zero exit + FATAL: ack invariant + stage=post_commit;
        //   * CHILD_REACHED_END absent;
        //   * canonical record file now holds AckedTombstone
        //     bytes (durable commit landed);
        //   * no `.staging-*` files anywhere in queue/;
        //   * REBOOT REPLAY: real `boot()` on the same state_dir
        //     surfaces this record as PersistedRecord::AckedTombstone.
        let parent_dir = TempDir::new().unwrap();
        std::fs::create_dir_all(parent_dir.path().join("queue")).unwrap();
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        crate::queue_meta::write_meta(parent_dir.path(), &meta).unwrap();

        let id = "env-subproc-ack";
        let sealed_sender = format!("s-{id}");
        let payload = format!("p-{id}");
        let body_hash =
            compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: sealed_sender.clone(),
            payload: payload.clone(),
            sequence_ts: 1_720_000_000_000,
            seq: 42,
            expires_at: 2_000_000_000, // far future
            seq_mac: "a".repeat(64),
            body_hash: body_hash.clone(),
        };
        let queued_bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(parent_dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &queued_bytes).unwrap();

        let exe = std::env::current_exe()
            .expect("current_exe unavailable — cannot verify FATAL path");
        let output = std::process::Command::new(&exe)
            .args([
                "--exact",
                "rest_workers::tests::ack_post_commit_forced_abort_child",
                "--nocapture",
            ])
            .env("PHANTOM_M3B2B_II_FORCE_POST_COMMIT_FATAL", "1")
            .env("PHANTOM_M3B2B_II_STATE_DIR", parent_dir.path())
            .env("RUST_TEST_NOCAPTURE", "1")
            .output()
            .expect("failed to spawn child test process");

        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);

        assert!(
            !output.status.success(),
            "child unexpectedly succeeded (abort did not fire)\nstatus: {:?}\nstdout: {stdout}\nstderr: {stderr}",
            output.status
        );
        assert!(
            stderr.contains("FATAL: ack invariant"),
            "child stderr missing FATAL: marker\nstderr: {stderr}"
        );
        assert!(
            stderr.contains("stage=post_commit"),
            "child stderr missing stage=post_commit marker\nstderr: {stderr}"
        );
        assert!(
            !stderr.contains("CHILD_REACHED_END"),
            "child reached end — abort did not fire\nstderr: {stderr}"
        );

        // Durable-commit assertions.
        let on_disk = std::fs::read(&path).expect("record file must still exist");
        assert_ne!(
            on_disk, queued_bytes,
            "canonical path should no longer hold Queued bytes — durable commit fired"
        );
        let parsed: PersistedRecord =
            serde_json::from_slice(&on_disk).expect("valid JSON on disk");
        match parsed {
            PersistedRecord::AckedTombstone {
                id: parsed_id,
                seq,
                body_hash: parsed_bh,
                ..
            } => {
                assert_eq!(parsed_id, id);
                assert_eq!(seq, 42);
                assert_eq!(parsed_bh, body_hash);
            }
            other => panic!("expected AckedTombstone on disk, got: {other:?}"),
        }

        let staging = collect_staging_tempfiles(&parent_dir.path().join("queue"))
            .expect("staging walk must not io-fail");
        assert!(
            staging.is_empty(),
            "found staging tempfile(s) after aborted ack: {:?}",
            staging
        );

        // REBOOT REPLAY PROOF.
        let reboot_cfg = crate::boot_loader::BootConfig {
            state_dir: parent_dir.path().to_path_buf(),
            caps: crate::boot_loader::PreflightCaps::for_tests(),
            tombstone: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
            current_seq_mac_key_fingerprint: key.fingerprint(),
            ownership: crate::boot_loader::OwnershipExpectation::permissive_for_tests(),
        };
        let reboot_result = crate::boot_loader::boot(&reboot_cfg).expect("reboot boot OK");
        let recovered = reboot_result
            .records()
            .iter()
            .find(|r| r.record.id() == id)
            .expect("record should be recovered on reboot");
        match &recovered.record {
            PersistedRecord::AckedTombstone {
                seq,
                body_hash: bh,
                ..
            } => {
                assert_eq!(*seq, 42);
                assert_eq!(bh, &body_hash);
            }
            other => panic!("reboot replay should see AckedTombstone, got: {other:?}"),
        }
    }

    // ─── Ack dispatch via worker_loop (M3b-2b-ii) ────────────

    #[tokio::test]
    async fn worker_loop_ack_for_unknown_envelope_returns_notfound_without_fatal() {
        // M3b-2b-ii: Ack is no longer a NotYetImplemented stub.
        // An ack for an envelope no store carries returns the
        // typed AckOutcome::NotFound with zero state mutation
        // and no fatal broadcast.
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        let (tx, rx) = mpsc::channel::<RestOp>(1);
        let handle = tokio::spawn(async move { worker_loop(ctx, rx).await });

        let (reply_tx, reply_rx) = oneshot::channel();
        tx.send(RestOp::Ack {
            recipient: TEST_RECIPIENT.into(),
            envelope_id: "env-ack-unknown".into(),
            reply: reply_tx,
        })
        .await
        .unwrap();

        let ack_result = reply_rx.await.unwrap();
        assert!(matches!(ack_result, Ok(AckOutcome::NotFound)));

        // Close the sender to let worker_loop exit cleanly via
        // rx.recv() == None classification. Since the pool
        // state reader is still `Running` (we never closed it),
        // the loop exits fatal via WorkerUnexpectedExit — same
        // shape the round-2 send-loop tests observe.
        drop(tx);
        let outcome = handle.await.unwrap();
        assert!(matches!(
            outcome,
            Err(FatalReason::WorkerUnexpectedExit { .. })
        ));
    }

    /// **M3b-2b-ii amendment round-1** (REDLINE P1-3): end-to-end
    /// happy-path dispatch of `RestOp::Ack` through
    /// `worker_loop`. Complements the direct `do_ack` unit tests
    /// by proving the FULL wire path — reply oneshot,
    /// AckOutcome::Acked mapping, and all four durable-state
    /// projections (disk / two RAM stores / active index /
    /// tombstone dedup / capacity ledger) — is exercised by the
    /// production dispatch surface, not just the internal
    /// function.
    #[tokio::test]
    async fn worker_loop_ack_happy_path_returns_acked_and_transitions_all_state() {
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        let (meta, _rec) = seed_queued_active_for_ack(&ctx, "env-loop-ack").await;

        // Clone the Arcs / state_dir BEFORE moving ctx into the
        // spawned worker_loop so post-Ack assertions can read
        // them.
        let rest_store = Arc::clone(&ctx.rest_store);
        let store = Arc::clone(&ctx.store);
        let capacity = Arc::clone(&ctx.capacity);
        let active_index = Arc::clone(&ctx.active_index);
        let tombstone_dedup = Arc::clone(&ctx.tombstone_dedup);
        let disk_path = meta.path.clone();
        let expected_seq = meta.seq;

        let (tx, rx) = mpsc::channel::<RestOp>(1);
        let handle = tokio::spawn(async move { worker_loop(ctx, rx).await });

        let (reply_tx, reply_rx) = oneshot::channel();
        tx.send(RestOp::Ack {
            recipient: TEST_RECIPIENT.into(),
            envelope_id: "env-loop-ack".into(),
            reply: reply_tx,
        })
        .await
        .unwrap();

        // Reply must be Acked with the seed's seq — no NotFound,
        // no Idempotent, no typed AckError.
        let ack_result = reply_rx.await.expect("reply channel not dropped");
        match ack_result {
            Ok(AckOutcome::Acked { seq }) => assert_eq!(seq, expected_seq),
            other => panic!("expected Acked({expected_seq}), got: {other:?}"),
        }

        // Disk: canonical record is now AckedTombstone with the
        // same seq / body_hash the Queued record carried.
        let on_disk = std::fs::read(&disk_path).expect("record still on disk");
        let parsed: PersistedRecord =
            serde_json::from_slice(&on_disk).expect("canonical bytes parse");
        match parsed {
            PersistedRecord::AckedTombstone { seq, body_hash, .. } => {
                assert_eq!(seq, expected_seq);
                assert_eq!(body_hash, meta.body_hash);
            }
            other => panic!("expected AckedTombstone on disk, got: {other:?}"),
        }

        // RAM projections: rest_store + store buckets are empty
        // for TEST_RECIPIENT.
        let rest_len = rest_store
            .read()
            .await
            .get(TEST_RECIPIENT)
            .map(|b| b.len())
            .unwrap_or(0);
        assert_eq!(rest_len, 0, "rest_store bucket should be drained by ack");
        let store_len = store
            .read()
            .await
            .get(TEST_RECIPIENT)
            .map(|b| b.len())
            .unwrap_or(0);
        assert_eq!(store_len, 0, "store bucket should be drained by ack");

        // Active index: entry removed; tombstone dedup: entry
        // inserted for the same (recipient, id).
        assert_eq!(
            active_index.count(),
            0,
            "active_index should have zero entries after happy-path ack"
        );
        assert_eq!(
            tombstone_dedup.count(),
            1,
            "tombstone_dedup should now hold the acked (recipient, id)"
        );

        // Capacity ledger: envelope count returned to zero (Queued
        // decrement + AckedTombstone increment cancel out), byte
        // footprints reflect the transition.
        let snap = capacity.snapshot();
        assert_eq!(
            snap.active_envelopes, 0,
            "active_envelopes should be zero — Queued released"
        );

        drop(tx);
        let outcome = handle.await.unwrap();
        assert!(matches!(
            outcome,
            Err(FatalReason::WorkerUnexpectedExit { .. })
        ));
    }

    /// **M3b-2b-ii amendment round-2** (REDLINE narrow): full
    /// production-shape happy-path ACK dispatch that goes
    /// through the WHOLE runtime pipeline:
    ///
    /// ```text
    /// real boot()
    ///   → WorkerRuntimeSpec::from_boot
    ///   → spawn_worker_runtime
    ///   → runtime.try_send(RestOp::Ack)   ← real routing:
    ///     worker_for + pool.try_send
    ///   → reply Ok(AckOutcome::Acked)
    ///   → disk/RAM/index/ledger assertions
    ///   → runtime.close + drain_handles
    /// ```
    ///
    /// The prior round-1 happy-path test spun a raw
    /// `mpsc::channel` and called `worker_loop(ctx, rx)`
    /// directly, so `WorkerRuntime::try_send` — including
    /// `worker_for` routing and `pool.try_send` — was NOT on
    /// the path. The reviewer flagged the gap. This test
    /// closes it end-to-end without a fault seam and without
    /// a child process.
    #[tokio::test]
    async fn ack_happy_path_via_worker_runtime_try_send_end_to_end() {
        // ─── 1. State dir with QueueMeta ───────────────────
        let dir = TempDir::new().unwrap();
        std::fs::create_dir_all(dir.path().join("queue")).unwrap();
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        crate::queue_meta::write_meta(dir.path(), &meta).unwrap();

        // ─── 2. Seed a Queued record on disk ───────────────
        // `expires_at = u64::MAX` — real `SystemTime::now()`
        // inside `from_boot()` never trips the "expired"
        // compaction branch. Round-2 review flagged the prior
        // literal (`2_000_000_000` = 2033-05-18) as a
        // date-bomb; `u64::MAX` moves it beyond any
        // wall-clock this test will observe.
        let id = "env-runtime-ack";
        let sealed_sender = format!("s-{id}");
        let payload = format!("p-{id}");
        let seq = 42u64;
        let body_hash = compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: sealed_sender.clone(),
            payload: payload.clone(),
            sequence_ts: 1_720_000_000_000,
            seq,
            expires_at: u64::MAX,
            seq_mac: "a".repeat(64),
            body_hash: body_hash.clone(),
        };
        let record_bytes = serde_json::to_vec(&record).unwrap();
        let record_disk_path = record_path(dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(record_disk_path.parent().unwrap()).unwrap();
        std::fs::write(&record_disk_path, &record_bytes).unwrap();

        // ─── 3. Real boot() ────────────────────────────────
        let boot_cfg = crate::boot_loader::BootConfig {
            state_dir: dir.path().to_path_buf(),
            caps: crate::boot_loader::PreflightCaps::for_tests(),
            tombstone: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
            current_seq_mac_key_fingerprint: key.fingerprint(),
            ownership: crate::boot_loader::OwnershipExpectation::permissive_for_tests(),
        };
        let boot_result = crate::boot_loader::boot(&boot_cfg).expect("boot must succeed");

        // ─── 4. Real WorkerRuntimeSpec::from_boot ──────────
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(16);
        let spec = WorkerRuntimeSpec::from_boot(
            boot_result,
            8, // max_envelopes_per_recipient
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
        )
        .expect("from_boot must succeed");

        // ─── 5. Real spawn_worker_runtime ──────────────────
        let runtime = spawn_worker_runtime(spec).expect("spawn must succeed");

        // Sanity: boot seeded exactly one Queued into the
        // active index (proves setup landed before ACK runs).
        assert_eq!(
            runtime.active_entry_count(),
            1,
            "boot should have seeded exactly one active record"
        );
        assert_eq!(
            runtime.tombstone_dedup_count(),
            0,
            "boot should have seeded zero tombstones"
        );

        // ─── 6. runtime.try_send(RestOp::Ack) ──────────────
        //   This is the code path the reviewer flagged as
        //   uncovered: it exercises `worker_for` routing +
        //   `pool.try_send` + worker_loop dispatch, in the
        //   same shape a production HTTP handler would use.
        let (reply_tx, reply_rx) = oneshot::channel();
        runtime
            .try_send(RestOp::Ack {
                recipient: TEST_RECIPIENT.into(),
                envelope_id: id.into(),
                reply: reply_tx,
            })
            .expect("try_send must succeed");

        // ─── 7. Reply must be Acked ────────────────────────
        let ack_result = tokio::time::timeout(std::time::Duration::from_secs(5), reply_rx)
            .await
            .expect("ack reply within 5s")
            .expect("reply channel must not drop");
        match ack_result {
            Ok(AckOutcome::Acked { seq: replied_seq }) => {
                assert_eq!(replied_seq, seq, "reply seq must match seeded record seq");
            }
            other => panic!("expected AckOutcome::Acked, got: {other:?}"),
        }

        // ─── 8. Assertions across every projection ─────────
        // Disk: canonical path now holds AckedTombstone.
        let on_disk = std::fs::read(&record_disk_path).expect("record still on disk");
        let parsed: PersistedRecord =
            serde_json::from_slice(&on_disk).expect("canonical bytes parse");
        match parsed {
            PersistedRecord::AckedTombstone {
                seq: on_disk_seq,
                body_hash: on_disk_bh,
                ..
            } => {
                assert_eq!(on_disk_seq, seq);
                assert_eq!(on_disk_bh, body_hash);
            }
            other => panic!("expected AckedTombstone on disk, got: {other:?}"),
        }

        // RAM stores: the acked recipient's buckets are empty.
        let rest_store = runtime.rest_store();
        let store = runtime.store();
        let rest_len = rest_store
            .read()
            .await
            .get(TEST_RECIPIENT)
            .map(|b| b.len())
            .unwrap_or(0);
        assert_eq!(rest_len, 0, "rest_store bucket must be drained by ack");
        let store_len = store
            .read()
            .await
            .get(TEST_RECIPIENT)
            .map(|b| b.len())
            .unwrap_or(0);
        assert_eq!(store_len, 0, "store bucket must be drained by ack");

        // Indices: active removed, dedup inserted.
        assert_eq!(
            runtime.active_entry_count(),
            0,
            "active_entry_count must be 0 after happy-path ack"
        );
        assert_eq!(
            runtime.tombstone_dedup_count(),
            1,
            "tombstone_dedup_count must be 1 after happy-path ack"
        );

        // Ledger: transitioned Queued → AckedTombstone.
        let snap = runtime.capacity().snapshot();
        assert_eq!(
            snap.active_envelopes, 0,
            "active_envelopes must return to 0 — Queued released"
        );

        // No staging tempfiles left behind.
        let staging = collect_staging_tempfiles(&dir.path().join("queue"))
            .expect("staging walk must not io-fail");
        assert!(
            staging.is_empty(),
            "found staging tempfile(s) after happy-path ack: {staging:?}"
        );

        // ─── 9. Clean shutdown ─────────────────────────────
        runtime.close();
        let drain = runtime
            .drain_handles(std::time::Duration::from_secs(5))
            .await
            .expect("drain must not error");
        // Every worker handle must have returned cleanly
        // (planned close, no panic, no FatalReason). If any
        // worker's join returned Err or produced a fatal, the
        // shutdown was not clean.
        for outcome in &drain {
            assert!(
                outcome.is_clean(),
                "worker {} drain not clean: {:?}",
                outcome.worker_id,
                outcome.result
            );
        }
    }

    /// **M3b-3b**: `RestOp::Sweep` is no longer routed to a
    /// `NotYetImplemented` / `UnsupportedOperation` stub. A
    /// sweep against an empty recipient state returns
    /// `Ok(SweepOutcome { 0, 0, 0 })` with zero mutation and
    /// no fatal broadcast — replaces the M3a placeholder test
    /// (`worker_loop_sweep_before_m3b_maps_to_typed_fatal`)
    /// whose stub-shape assertion no longer applies.
    #[tokio::test]
    async fn worker_loop_sweep_for_empty_recipient_returns_zero_no_fatal() {
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        let mut fatal_rx = ctx.fatal.subscribe();
        let (tx, rx) = mpsc::channel::<RestOp>(1);
        let handle = tokio::spawn(async move { worker_loop(ctx, rx).await });

        let (reply_tx, reply_rx) = oneshot::channel();
        tx.send(RestOp::Sweep {
            recipient: TEST_RECIPIENT.into(),
            reply: reply_tx,
        })
        .await
        .unwrap();

        let sweep_result = reply_rx.await.expect("reply channel not dropped");
        match sweep_result {
            Ok(outcome) => {
                assert_eq!(outcome.queued_swept, 0);
                assert_eq!(outcome.tombstones_swept, 0);
                assert_eq!(outcome.disk_reclaimed_bytes, 0);
            }
            Err(other) => panic!("expected Ok(SweepOutcome::zero), got: {other:?}"),
        }

        // No fatal broadcast for a clean sweep.
        assert!(fatal_rx.try_recv().is_err(), "unexpected fatal broadcast");

        // Close the sender to let worker_loop exit cleanly via
        // rx.recv() == None classification. Since the pool
        // state reader is still `Running`, the loop exits fatal
        // via WorkerUnexpectedExit — same shape prior dispatch
        // tests observe.
        drop(tx);
        let outcome = handle.await.unwrap();
        assert!(matches!(
            outcome,
            Err(FatalReason::WorkerUnexpectedExit { .. })
        ));
    }

    // ─── M3b-3b: Sweep dispatch happy path + subprocess proof ─

    /// **M3b-3b**: end-to-end production-shape happy-path for
    /// `RestOp::Sweep` through the WHOLE runtime pipeline:
    ///
    /// ```text
    /// real boot()
    ///   → WorkerRuntimeSpec::from_boot
    ///   → spawn_worker_runtime
    ///   → runtime.try_send(RestOp::Sweep)   ← real routing:
    ///     worker_for + pool.try_send
    ///   → reply Ok(SweepOutcome { queued_swept=1, .. })
    ///   → disk/RAM/index/ledger assertions
    ///   → runtime.close + drain_handles
    /// ```
    ///
    /// **Round-1 REDLINE P1-2 rewrite**: seed via
    /// `runtime.try_send(RestOp::Send)` AFTER boot completes,
    /// then compute `expires_at` relative to a wall-clock
    /// snapshot taken JUST BEFORE the send. This eliminates
    /// the boot-speed dependency the round-0 shape carried:
    /// the only wall-clock window that matters now is the gap
    /// between the pre-send snapshot and the sweep call, which
    /// we control directly via `tokio::time::sleep`.
    #[tokio::test]
    async fn sweep_happy_path_via_worker_runtime_try_send_end_to_end() {
        // (1) State dir with QueueMeta — empty state.
        let dir = TempDir::new().unwrap();
        std::fs::create_dir_all(dir.path().join("queue")).unwrap();
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        crate::queue_meta::write_meta(dir.path(), &meta).unwrap();

        // (2) Real boot + from_boot + spawn_worker_runtime.
        // Zero records on disk → boot compaction is a no-op.
        let boot_cfg = crate::boot_loader::BootConfig {
            state_dir: dir.path().to_path_buf(),
            caps: crate::boot_loader::PreflightCaps::for_tests(),
            tombstone: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
            current_seq_mac_key_fingerprint: key.fingerprint(),
            ownership: crate::boot_loader::OwnershipExpectation::permissive_for_tests(),
        };
        let boot_result = crate::boot_loader::boot(&boot_cfg).expect("boot OK");
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(16);
        let spec = WorkerRuntimeSpec::from_boot(
            boot_result,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
        )
        .expect("from_boot OK");
        let runtime = spawn_worker_runtime(spec).expect("spawn OK");
        assert_eq!(runtime.active_entry_count(), 0);

        // (3) Snapshot wall clock AFTER boot completes, then
        // seed the Queued record via `runtime.try_send(Send)`.
        // No boot-speed dependency: `expires_at` is anchored to
        // POST-boot `now`, so the record is guaranteed live at
        // the moment it lands in RAM/disk.
        let now_post_boot = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let id = "env-sweep-runtime";
        let sealed_sender = format!("s-{id}");
        let payload = format!("p-{id}");
        let expires_at = now_post_boot + 3;
        let candidate = SendCandidate {
            id: id.into(),
            sealed_sender: sealed_sender.clone(),
            payload: payload.clone(),
            sequence_ts: 1_720_000_000_000,
            expires_at,
        };
        let (send_reply_tx, send_reply_rx) = oneshot::channel();
        runtime
            .try_send(RestOp::Send {
                recipient: TEST_RECIPIENT.into(),
                candidate,
                reply: send_reply_tx,
            })
            .expect("send try_send OK");
        let send_outcome = tokio::time::timeout(
            std::time::Duration::from_secs(5),
            send_reply_rx,
        )
        .await
        .expect("send reply within 5s")
        .expect("send reply channel not dropped")
        .expect("send must succeed");
        assert!(!send_outcome.idempotent_replay);
        let record_disk_path = record_path(dir.path(), TEST_RECIPIENT, id);
        assert!(
            std::fs::metadata(&record_disk_path).is_ok(),
            "record file must be present after send"
        );
        let seeded_disk_bytes = std::fs::metadata(&record_disk_path).unwrap().len();

        // (4) Wait past the expires_at boundary. 3s window + 1s
        // slack for scheduler jitter → 4s total.
        tokio::time::sleep(std::time::Duration::from_secs(4)).await;

        // (5) runtime.try_send(RestOp::Sweep).
        let (reply_tx, reply_rx) = oneshot::channel();
        runtime
            .try_send(RestOp::Sweep {
                recipient: TEST_RECIPIENT.into(),
                reply: reply_tx,
            })
            .expect("sweep try_send OK");

        // (6) Reply must be Ok with queued_swept == 1.
        let sweep_result = tokio::time::timeout(std::time::Duration::from_secs(5), reply_rx)
            .await
            .expect("sweep reply within 5s")
            .expect("sweep reply channel not dropped");
        match sweep_result {
            Ok(outcome) => {
                assert_eq!(outcome.queued_swept, 1, "expected 1 Queued swept");
                assert_eq!(outcome.tombstones_swept, 0);
                assert_eq!(
                    outcome.disk_reclaimed_bytes, seeded_disk_bytes,
                    "disk_reclaimed_bytes must equal seeded record size"
                );
            }
            Err(other) => panic!("expected Ok(SweepOutcome), got: {other:?}"),
        }

        // (7) Disk unlinked.
        assert!(
            std::fs::metadata(&record_disk_path).is_err(),
            "record file must be unlinked after sweep"
        );
        // RAM projections drained.
        let rest_store = runtime.rest_store();
        let store = runtime.store();
        assert_eq!(
            rest_store
                .read()
                .await
                .get(TEST_RECIPIENT)
                .map(|b| b.len())
                .unwrap_or(0),
            0
        );
        assert_eq!(
            store
                .read()
                .await
                .get(TEST_RECIPIENT)
                .map(|b| b.len())
                .unwrap_or(0),
            0
        );
        assert_eq!(runtime.active_entry_count(), 0);
        assert_eq!(runtime.tombstone_dedup_count(), 0);
        let snap = runtime.capacity().snapshot();
        assert_eq!(snap.active_envelopes, 0);

        // (8) Clean shutdown.
        runtime.close();
        let drain = runtime
            .drain_handles(std::time::Duration::from_secs(5))
            .await
            .expect("drain OK");
        for outcome in &drain {
            assert!(
                outcome.is_clean(),
                "worker {} drain not clean: {:?}",
                outcome.worker_id,
                outcome.result
            );
        }
    }

    /// **M3b-3b corrective — round-1 REDLINE P1-2 rewrite**:
    /// child seeds state via `runtime.try_send(RestOp::Send)`
    /// AFTER boot completes so boot compaction is a no-op and
    /// there is no boot-speed dependency. Waits past the
    /// post-send `expires_at`, arms
    /// `TEST_FORCE_SWEEP_POST_UNLINK_FATAL`, sends
    /// `RestOp::Sweep`. Abort should fire AFTER durable unlink
    /// and BEFORE ledger/RAM mutation. Prints `SEED_OK` on
    /// successful send (parent asserts) and `CHILD_REACHED_END`
    /// if abort never fires (parent asserts NOT present).
    #[test]
    fn sweep_post_unlink_forced_abort_child_queued() {
        if std::env::var("PHANTOM_M3B3B_FORCE_SWEEP_POST_UNLINK_FATAL_QUEUED").is_err() {
            return;
        }
        let state_dir = std::env::var_os("PHANTOM_M3B3B_STATE_DIR")
            .map(std::path::PathBuf::from)
            .expect("PHANTOM_M3B3B_STATE_DIR must be provided by parent");
        let id = std::env::var("PHANTOM_M3B3B_ENV_ID")
            .expect("PHANTOM_M3B3B_ENV_ID must be provided by parent");
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async {
            let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
            let boot_cfg = crate::boot_loader::BootConfig {
                state_dir: state_dir.clone(),
                caps: crate::boot_loader::PreflightCaps::for_tests(),
                tombstone: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
                current_seq_mac_key_fingerprint: key.fingerprint(),
                ownership: crate::boot_loader::OwnershipExpectation::permissive_for_tests(),
            };
            let boot_result = crate::boot_loader::boot(&boot_cfg).expect("boot OK");
            let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(16);
            let spec = WorkerRuntimeSpec::from_boot(
                boot_result,
                8,
                Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
                caps(),
                fatal_tx,
            )
            .expect("from_boot OK");
            let runtime = spawn_worker_runtime(spec).expect("spawn OK");

            // Seed AFTER boot — `expires_at` anchored to
            // post-boot wall clock eliminates any boot-speed
            // dependency (the only wall-clock gap that matters
            // is between send and sweep, which we control).
            let now_post_boot = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs();
            let expires_at = now_post_boot + 3;
            let candidate = SendCandidate {
                id: id.clone(),
                sealed_sender: format!("s-{id}"),
                payload: format!("p-{id}"),
                sequence_ts: 1_720_000_000_000,
                expires_at,
            };
            let (send_reply_tx, send_reply_rx) = oneshot::channel();
            runtime
                .try_send(RestOp::Send {
                    recipient: TEST_RECIPIENT.into(),
                    candidate,
                    reply: send_reply_tx,
                })
                .expect("send try_send OK");
            let _ = tokio::time::timeout(std::time::Duration::from_secs(5), send_reply_rx)
                .await
                .expect("send reply within 5s")
                .expect("send reply channel not dropped")
                .expect("send must succeed");
            eprintln!("SEED_OK");

            // Wait past the post-send `expires_at` boundary.
            // 4s = 3s window + 1s slack.
            tokio::time::sleep(std::time::Duration::from_secs(4)).await;

            TEST_FORCE_SWEEP_POST_UNLINK_FATAL
                .store(true, std::sync::atomic::Ordering::SeqCst);

            let (reply_tx, reply_rx) = oneshot::channel();
            runtime
                .try_send(RestOp::Sweep {
                    recipient: TEST_RECIPIENT.into(),
                    reply: reply_tx,
                })
                .expect("sweep try_send OK");
            let _ = tokio::time::timeout(std::time::Duration::from_secs(5), reply_rx).await;
        });
        eprintln!("CHILD_REACHED_END");
    }

    /// **M3b-3b corrective — round-1 REDLINE P1-1**: tombstone
    /// analog of the Queued child. Uses `TombstoneConfig::from_secs(1)`
    /// so `dedup_until = now + 1`, then sweeps ~2s later
    /// (past the boundary). Send + Ack create a real
    /// AckedTombstone via the runtime; sweep then unlinks it.
    #[test]
    fn sweep_post_unlink_forced_abort_child_tombstone() {
        if std::env::var("PHANTOM_M3B3B_FORCE_SWEEP_POST_UNLINK_FATAL_TOMBSTONE").is_err() {
            return;
        }
        let state_dir = std::env::var_os("PHANTOM_M3B3B_STATE_DIR")
            .map(std::path::PathBuf::from)
            .expect("PHANTOM_M3B3B_STATE_DIR must be provided by parent");
        let id = std::env::var("PHANTOM_M3B3B_ENV_ID")
            .expect("PHANTOM_M3B3B_ENV_ID must be provided by parent");
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async {
            let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
            let boot_cfg = crate::boot_loader::BootConfig {
                state_dir: state_dir.clone(),
                caps: crate::boot_loader::PreflightCaps::for_tests(),
                // 1s horizon so an Ack'd record's dedup_until
                // lands at now + 1s — sweepable after a short
                // wait.
                tombstone: crate::tombstone_config::TombstoneConfig::from_secs(1).unwrap(),
                current_seq_mac_key_fingerprint: key.fingerprint(),
                ownership: crate::boot_loader::OwnershipExpectation::permissive_for_tests(),
            };
            let boot_result = crate::boot_loader::boot(&boot_cfg).expect("boot OK");
            let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(16);
            let spec = WorkerRuntimeSpec::from_boot(
                boot_result,
                8,
                Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
                caps(),
                fatal_tx,
            )
            .expect("from_boot OK");
            let runtime = spawn_worker_runtime(spec).expect("spawn OK");

            // Long expires_at so the Queued phase never gets
            // swept by expiry — the tombstone is what we care
            // about. now_post_boot + 3600s = way beyond any
            // test wall-clock window.
            let now_post_boot = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs();
            let expires_at = now_post_boot + 3_600;
            let candidate = SendCandidate {
                id: id.clone(),
                sealed_sender: format!("s-{id}"),
                payload: format!("p-{id}"),
                sequence_ts: 1_720_000_000_000,
                expires_at,
            };
            let (send_reply_tx, send_reply_rx) = oneshot::channel();
            runtime
                .try_send(RestOp::Send {
                    recipient: TEST_RECIPIENT.into(),
                    candidate,
                    reply: send_reply_tx,
                })
                .expect("send try_send OK");
            let _ = tokio::time::timeout(std::time::Duration::from_secs(5), send_reply_rx)
                .await
                .expect("send reply within 5s")
                .expect("send reply channel not dropped")
                .expect("send must succeed");

            // Ack to convert Queued → AckedTombstone.
            let (ack_reply_tx, ack_reply_rx) = oneshot::channel();
            runtime
                .try_send(RestOp::Ack {
                    recipient: TEST_RECIPIENT.into(),
                    envelope_id: id.clone(),
                    reply: ack_reply_tx,
                })
                .expect("ack try_send OK");
            let ack_out = tokio::time::timeout(
                std::time::Duration::from_secs(5),
                ack_reply_rx,
            )
            .await
            .expect("ack reply within 5s")
            .expect("ack reply channel not dropped")
            .expect("ack must succeed");
            match ack_out {
                AckOutcome::Acked { .. } => {}
                other => panic!("expected AckOutcome::Acked, got: {other:?}"),
            }
            eprintln!("SEED_OK");

            // Wait past dedup_until (now + 1s). 3s = 1s + slack.
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;

            TEST_FORCE_SWEEP_POST_UNLINK_FATAL
                .store(true, std::sync::atomic::Ordering::SeqCst);

            let (reply_tx, reply_rx) = oneshot::channel();
            runtime
                .try_send(RestOp::Sweep {
                    recipient: TEST_RECIPIENT.into(),
                    reply: reply_tx,
                })
                .expect("sweep try_send OK");
            let _ = tokio::time::timeout(std::time::Duration::from_secs(5), reply_rx).await;
        });
        eprintln!("CHILD_REACHED_END");
    }

    /// **Round-1 REDLINE P1-1 + P1-2 rewrite**: Queued
    /// subprocess post-unlink abort + reboot-replay proof.
    /// Parent no longer pre-seeds disk — child seeds via
    /// `runtime.try_send(RestOp::Send)` AFTER boot completes,
    /// eliminating the boot-speed dependency. Parent chooses
    /// the envelope id, computes the canonical path
    /// deterministically, and asserts after the child aborts:
    ///
    ///   * non-zero exit + `FATAL: sweep invariant` +
    ///     `stage=post_unlink` in stderr;
    ///   * `SEED_OK` present (proves the child actually
    ///     seeded the record — otherwise "no record on disk"
    ///     would be a vacuous "proof");
    ///   * `CHILD_REACHED_END` absent;
    ///   * canonical file is GONE (durable unlink landed);
    ///   * no `.staging-*` tempfiles;
    ///   * REBOOT REPLAY: real boot() on the same state_dir
    ///     recovers ZERO records for TEST_RECIPIENT — the
    ///     unlink is durable across restart.
    #[test]
    fn sweep_post_unlink_queued_fatal_aborts_child_and_reboot_replay_shows_record_absent() {
        let parent_dir = TempDir::new().unwrap();
        std::fs::create_dir_all(parent_dir.path().join("queue")).unwrap();
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta_shape = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        crate::queue_meta::write_meta(parent_dir.path(), &meta_shape).unwrap();

        let id = "env-sweep-abort-q";
        let expected_path = record_path(parent_dir.path(), TEST_RECIPIENT, id);

        let exe = std::env::current_exe()
            .expect("current_exe unavailable — cannot verify FATAL path");
        let output = std::process::Command::new(&exe)
            .args([
                "--exact",
                "rest_workers::tests::sweep_post_unlink_forced_abort_child_queued",
                "--nocapture",
            ])
            .env("PHANTOM_M3B3B_FORCE_SWEEP_POST_UNLINK_FATAL_QUEUED", "1")
            .env("PHANTOM_M3B3B_STATE_DIR", parent_dir.path())
            .env("PHANTOM_M3B3B_ENV_ID", id)
            .env("RUST_TEST_NOCAPTURE", "1")
            .output()
            .expect("failed to spawn child test process");

        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);

        assert!(
            !output.status.success(),
            "child unexpectedly succeeded (abort did not fire)\n\
             status: {:?}\nstdout: {stdout}\nstderr: {stderr}",
            output.status
        );
        assert!(
            stderr.contains("SEED_OK"),
            "child stderr missing SEED_OK — record was never actually seeded, \
             so \"file absent after abort\" would be vacuous\nstderr: {stderr}"
        );
        assert!(
            stderr.contains("FATAL: sweep invariant"),
            "child stderr missing FATAL: sweep invariant marker\nstderr: {stderr}"
        );
        assert!(
            stderr.contains("stage=post_unlink"),
            "child stderr missing stage=post_unlink marker\nstderr: {stderr}"
        );
        assert!(
            !stderr.contains("CHILD_REACHED_END"),
            "child reached end — abort did not fire\nstderr: {stderr}"
        );

        // Durable-unlink assertion: canonical file is gone.
        assert!(
            std::fs::metadata(&expected_path).is_err(),
            "record file must be unlinked (durable) after aborted sweep — \
             file still exists at {expected_path:?}"
        );

        // No staging tempfiles left behind.
        let staging = collect_staging_tempfiles(&parent_dir.path().join("queue"))
            .expect("staging walk must not io-fail");
        assert!(
            staging.is_empty(),
            "found staging tempfile(s) after aborted sweep: {staging:?}"
        );

        // REBOOT REPLAY PROOF.
        let reboot_cfg = crate::boot_loader::BootConfig {
            state_dir: parent_dir.path().to_path_buf(),
            caps: crate::boot_loader::PreflightCaps::for_tests(),
            tombstone: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
            current_seq_mac_key_fingerprint: key.fingerprint(),
            ownership: crate::boot_loader::OwnershipExpectation::permissive_for_tests(),
        };
        let reboot_result = crate::boot_loader::boot(&reboot_cfg).expect("reboot boot OK");
        let recovered = reboot_result
            .records()
            .iter()
            .find(|r| r.record.id() == id);
        assert!(
            recovered.is_none(),
            "reboot replay recovered a record that was durably unlinked: {recovered:?}"
        );
    }

    /// **Round-1 REDLINE P1-1**: Tombstone subprocess
    /// post-unlink abort + reboot-replay proof. Symmetric to
    /// the Queued variant but exercises the tombstone branch
    /// of `sweep_one` (which was completely uncovered by the
    /// round-0 test).
    ///
    /// Child boot uses `TombstoneConfig::from_secs(1)` so
    /// `dedup_until = now + 1s` on the AckedTombstone. Child
    /// waits ~3s past that boundary before the sweep.
    #[test]
    fn sweep_post_unlink_tombstone_fatal_aborts_child_and_reboot_replay_shows_record_absent() {
        let parent_dir = TempDir::new().unwrap();
        std::fs::create_dir_all(parent_dir.path().join("queue")).unwrap();
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta_shape = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        crate::queue_meta::write_meta(parent_dir.path(), &meta_shape).unwrap();

        let id = "env-sweep-abort-t";
        let expected_path = record_path(parent_dir.path(), TEST_RECIPIENT, id);

        let exe = std::env::current_exe()
            .expect("current_exe unavailable — cannot verify FATAL path");
        let output = std::process::Command::new(&exe)
            .args([
                "--exact",
                "rest_workers::tests::sweep_post_unlink_forced_abort_child_tombstone",
                "--nocapture",
            ])
            .env(
                "PHANTOM_M3B3B_FORCE_SWEEP_POST_UNLINK_FATAL_TOMBSTONE",
                "1",
            )
            .env("PHANTOM_M3B3B_STATE_DIR", parent_dir.path())
            .env("PHANTOM_M3B3B_ENV_ID", id)
            .env("RUST_TEST_NOCAPTURE", "1")
            .output()
            .expect("failed to spawn child test process");

        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);

        assert!(
            !output.status.success(),
            "child unexpectedly succeeded (abort did not fire)\n\
             status: {:?}\nstdout: {stdout}\nstderr: {stderr}",
            output.status
        );
        assert!(
            stderr.contains("SEED_OK"),
            "child stderr missing SEED_OK — tombstone was never seeded\n\
             stderr: {stderr}"
        );
        assert!(
            stderr.contains("FATAL: sweep invariant"),
            "child stderr missing FATAL: sweep invariant marker\nstderr: {stderr}"
        );
        assert!(
            stderr.contains("stage=post_unlink"),
            "child stderr missing stage=post_unlink marker\nstderr: {stderr}"
        );
        assert!(
            stderr.contains("tombstone branch"),
            "child stderr missing tombstone-branch discriminator — \
             abort may have fired from Queued branch\nstderr: {stderr}"
        );
        assert!(
            !stderr.contains("CHILD_REACHED_END"),
            "child reached end — abort did not fire\nstderr: {stderr}"
        );

        // Durable-unlink assertion: canonical file gone.
        assert!(
            std::fs::metadata(&expected_path).is_err(),
            "tombstone file must be unlinked (durable) after aborted sweep — \
             file still exists at {expected_path:?}"
        );

        // No staging tempfiles left behind.
        let staging = collect_staging_tempfiles(&parent_dir.path().join("queue"))
            .expect("staging walk must not io-fail");
        assert!(
            staging.is_empty(),
            "found staging tempfile(s) after aborted sweep: {staging:?}"
        );

        // REBOOT REPLAY: real boot() on the same state_dir
        // recovers ZERO records for TEST_RECIPIENT — the
        // durable tombstone unlink survived the aborted child.
        let reboot_cfg = crate::boot_loader::BootConfig {
            state_dir: parent_dir.path().to_path_buf(),
            caps: crate::boot_loader::PreflightCaps::for_tests(),
            // Reboot uses default horizon; doesn't matter for
            // this proof — the record file is gone.
            tombstone: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
            current_seq_mac_key_fingerprint: key.fingerprint(),
            ownership: crate::boot_loader::OwnershipExpectation::permissive_for_tests(),
        };
        let reboot_result = crate::boot_loader::boot(&reboot_cfg).expect("reboot boot OK");
        let recovered = reboot_result
            .records()
            .iter()
            .find(|r| r.record.id() == id);
        assert!(
            recovered.is_none(),
            "reboot replay recovered a tombstone that was durably unlinked: {recovered:?}"
        );
    }

    // ─── Round-2 F2: per-recipient cap=0 refuses first send ──

    #[tokio::test]
    async fn do_send_per_recipient_cap_zero_refuses_first_send() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let ctx = ActorContext {
            max_envelopes_per_recipient: 0,
            ..ctx
        };
        let snap_before = ctx.capacity.snapshot();
        let err = do_send(&ctx, TEST_RECIPIENT, sample_candidate("env-zero"))
            .await
            .unwrap_err();
        assert!(matches!(
            err,
            SendError::PerRecipientQueueFull { observed: 0, cap: 0 }
        ));
        // No side effects — ledger, stores, disk all untouched.
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert!(ctx.rest_store.read().await.is_empty());
        assert!(ctx.store.read().await.is_empty());
        assert!(!record_path(dir.path(), TEST_RECIPIENT, "env-zero").exists());
    }

    // ─── Round-2 F3: unsealed sender rejected structurally ────

    #[tokio::test]
    async fn do_send_empty_sealed_sender_is_rejected_before_any_state_access() {
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let snap_before = ctx.capacity.snapshot();
        let candidate = SendCandidate {
            id: "env-legacy".into(),
            sealed_sender: String::new(), // legacy from-carrying sender
            payload: "payload".into(),
            sequence_ts: 0,
            expires_at: 0,
        };
        let err = do_send(&ctx, TEST_RECIPIENT, candidate)
            .await
            .unwrap_err();
        assert!(matches!(err, SendError::UnsealedSenderRejected));
        assert_eq!(ctx.capacity.snapshot(), snap_before);
        assert!(ctx.rest_store.read().await.is_empty());
        assert!(ctx.store.read().await.is_empty());
    }

    // ─── Round-2 F4: runtime generation guard is fail-stop ────

    #[tokio::test]
    async fn worker_loop_boot_generation_above_cap_fails_closed_via_seq_space_exhausted() {
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        // Plant a runtime generation ABOVE the 24-bit cap. The
        // boot-loader normally refuses this at read time, but a
        // wiring bug in the same crate could still construct one
        // — the runtime guard in next_seq must catch it.
        let ctx = ActorContext {
            boot_generation: queue_meta::MAX_BOOT_GENERATION + 1,
            ..ctx
        };
        let mut fatal_rx = ctx.fatal.subscribe();
        let (tx, rx) = mpsc::channel::<RestOp>(1);
        let handle = tokio::spawn(async move { worker_loop(ctx, rx).await });

        let (reply_tx, reply_rx) = oneshot::channel();
        tx.send(RestOp::Send {
            recipient: TEST_RECIPIENT.into(),
            candidate: sample_candidate("env-gen"),
            reply: reply_tx,
        })
        .await
        .unwrap();

        let err = reply_rx.await.unwrap().unwrap_err();
        assert!(matches!(
            err,
            SendError::BootGenerationOutOfRange { generation } if generation == queue_meta::MAX_BOOT_GENERATION + 1
        ));
        // Round-3 F3: dedicated fatal variant carries the actual
        // generation. Cannot be confused with a counter-corruption
        // that landed at u64::MAX.
        let fatal = fatal_rx.recv().await.unwrap();
        let expected_gen = queue_meta::MAX_BOOT_GENERATION + 1;
        assert!(matches!(
            fatal,
            FatalReason::SeqNamespaceInvalid { generation, .. }
                if generation == expected_gen
        ));
        drop(tx);
        let outcome = handle.await.unwrap();
        assert!(matches!(
            outcome,
            Err(FatalReason::SeqNamespaceInvalid { generation, .. })
                if generation == expected_gen
        ));
    }

    // ─── Round-3 F4: serialize-once BYTE-IDENTITY contract ────

    #[tokio::test]
    async fn do_send_ledger_active_bytes_equals_on_disk_length() {
        // Round-4 F4: this test does NOT catch a deterministic
        // second `serde_json::to_vec` — both runs would produce
        // the same byte count and the equality would still hold.
        // The "serialize exactly once" guarantee is now
        // STRUCTURAL: the [`SerializedRecord`] opaque type
        // (persistence.rs) is the sole path from
        // `PersistedRecord` to a byte buffer, and `do_send`
        // threads a single instance from the capacity reservation
        // down into `write_record_bytes`. This test is the
        // matching accounting check — it locks in that the
        // ledger's `active_bytes` counter is drawn from the same
        // `.len()` that reaches disk, so any drift between the
        // ledger and the on-disk footprint (e.g. a stray append
        // or truncation) is caught at the byte-count layer.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let candidate = sample_candidate("env-ledger-identity");
        let _ = do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .unwrap();
        let disk_path = record_path(dir.path(), TEST_RECIPIENT, &candidate.id);
        let disk_len = std::fs::metadata(&disk_path).unwrap().len();
        let snap = ctx.capacity.snapshot();
        assert_eq!(
            snap.active_bytes, disk_len,
            "ledger.active_bytes ({}) must equal on-disk size ({}) — same-bytes contract (round-3 F4)",
            snap.active_bytes, disk_len,
        );
    }

    #[tokio::test]
    async fn do_send_persisted_bytes_equal_serialized_record_bytes() {
        // Kept from round-2 as a companion check: the raw serde
        // representation on disk still matches a fresh
        // reconstruction. Round-3 F4 (above) is the stronger
        // structural guarantee.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let candidate = sample_candidate("env-identity");
        let outcome = do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .unwrap();
        let disk_path = record_path(dir.path(), TEST_RECIPIENT, &candidate.id);
        let disk_bytes = std::fs::read(&disk_path).expect("read disk record");
        let rest = ctx.rest_store.read().await;
        let queued = &rest.get(TEST_RECIPIENT).unwrap()[0];
        let body_hash = compute_body_hash_hex(
            queued.sealed_sender.as_bytes(),
            queued.payload.as_bytes(),
        );
        let recomputed = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: queued.id.clone(),
            sealed_sender: queued.sealed_sender.clone(),
            payload: queued.payload.clone(),
            sequence_ts: queued.sequence_ts,
            seq: outcome.seq,
            expires_at: queued.expires_at,
            seq_mac: queued.seq_mac.clone(),
            body_hash,
        };
        let recomputed_bytes = serde_json::to_vec(&recomputed).unwrap();
        assert_eq!(
            disk_bytes, recomputed_bytes,
            "disk bytes must equal a fresh serde-derived serialisation"
        );
    }

    // ─── Round-3 F1: spawn_worker_runtime shape smoke ────────
    // The public integration test in
    // `tests/rest_workers_public_api.rs` proves the API is
    // reachable from an EXTERNAL crate (which is the failure
    // mode `pub(crate)` had). This in-module test just walks
    // the same shape through a same-crate call so the factory
    // stays testable without a heavier fixture.

    #[tokio::test]
    async fn spawn_worker_runtime_refuses_boot_generation_above_cap() {
        let dir = build_state_dir();
        let (_fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let (fatal_tx, _fatal_rx2) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec {
            rest_store: Arc::new(RwLock::new(HashMap::new())),
            store: Arc::new(RwLock::new(HashMap::new())),
            state_dir: dir.path().to_path_buf(),
            max_envelopes_per_recipient: 1,
            boot_generation: queue_meta::MAX_BOOT_GENERATION + 5,
            seq_mac_root_key: Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            capacity: Arc::new(GlobalCapacityGate::new(caps())),
            fatal: fatal_tx,
            worker_hash_key: TEST_KEY,
            active_index: Arc::new(ActiveRecordIndex::new()),
            tombstone_dedup: Arc::new(TombstoneDedupTable::new()),
            boot_seed_stats: BootSeedStats::default(),
            tombstone_config: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
            clock: ClockSource::Fixed(TEST_NOW_EPOCH_SECS),
        };
        // Note: WorkerRuntime does not derive Debug (it wraps a
        // WorkerPool which cannot). Match the result directly
        // instead of calling `unwrap_err`.
        match spawn_worker_runtime(spec) {
            Err(SpawnError::BootGenerationOutOfRange { generation }) => {
                assert_eq!(generation, queue_meta::MAX_BOOT_GENERATION + 5);
            }
            _ => panic!("expected BootGenerationOutOfRange"),
        }
    }

    // ─── M4-3: recipient_snapshot() unit tests ───────────────

    #[test]
    fn recipient_keys_empty_returns_empty_vec() {
        let active = ActiveRecordIndex::new();
        let tomb = TombstoneDedupTable::new();
        assert!(active.recipient_keys().is_empty());
        assert!(tomb.recipient_keys().is_empty());
    }

    #[test]
    fn recipient_keys_returns_populated_recipients() {
        let active = ActiveRecordIndex::new();
        let meta = ActiveEntryMeta {
            path: std::path::PathBuf::from("/tmp/x"),
            seq: 1,
            body_hash: "a".repeat(64),
            expires_at: 1_720_000_000,
            disk_bytes: 100,
            ram_bytes: record_ram_estimate(100),
        };
        active
            .try_insert_new("recipient-a", "id-1".into(), meta.clone())
            .expect("insert a");
        active
            .try_insert_new("recipient-b", "id-2".into(), meta.clone())
            .expect("insert b");
        let mut keys = active.recipient_keys();
        keys.sort();
        assert_eq!(keys, vec!["recipient-a".to_string(), "recipient-b".to_string()]);
    }

    fn spawn_test_runtime_for_snapshot() -> WorkerRuntime {
        let dir = TempDir::new().unwrap();
        std::fs::create_dir_all(dir.path().join("queue")).unwrap();
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        crate::queue_meta::write_meta(dir.path(), &meta).unwrap();
        let boot_cfg = crate::boot_loader::BootConfig {
            state_dir: dir.path().to_path_buf(),
            caps: crate::boot_loader::PreflightCaps::for_tests(),
            tombstone: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
            current_seq_mac_key_fingerprint: key.fingerprint(),
            ownership: crate::boot_loader::OwnershipExpectation::permissive_for_tests(),
        };
        let boot_result = crate::boot_loader::boot(&boot_cfg).expect("boot OK");
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(16);
        let spec = WorkerRuntimeSpec::from_boot(
            boot_result,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
        )
        .expect("from_boot OK");
        let runtime = spawn_worker_runtime(spec).expect("spawn OK");
        // Leak the TempDir intentionally — this is a small
        // synthetic runtime for a synchronous snapshot test; it
        // doesn't do any disk I/O beyond boot, and the OS-level
        // cleanup on process exit reclaims the dir.
        std::mem::forget(dir);
        runtime
    }

    #[tokio::test]
    async fn recipient_snapshot_empty_runtime_returns_empty_vec() {
        let runtime = spawn_test_runtime_for_snapshot();
        assert!(runtime.recipient_snapshot().is_empty());
        runtime.close();
        let _ = runtime.drain_handles(std::time::Duration::from_secs(5)).await;
    }

    #[tokio::test]
    async fn recipient_snapshot_only_queued_projection_returns_recipient_once() {
        let runtime = spawn_test_runtime_for_snapshot();
        let meta = ActiveEntryMeta {
            path: std::path::PathBuf::from("/tmp/x"),
            seq: 1,
            body_hash: "a".repeat(64),
            expires_at: 1_720_000_000,
            disk_bytes: 100,
            ram_bytes: record_ram_estimate(100),
        };
        runtime
            .active_index()
            .try_insert_new("recipient-q", "id".into(), meta)
            .expect("insert");
        let snap = runtime.recipient_snapshot();
        assert_eq!(snap, vec!["recipient-q".to_string()]);
        runtime.close();
        let _ = runtime.drain_handles(std::time::Duration::from_secs(5)).await;
    }

    #[tokio::test]
    async fn recipient_snapshot_only_tombstone_projection_returns_recipient_once() {
        let runtime = spawn_test_runtime_for_snapshot();
        let entry = TombstoneEntry {
            path: std::path::PathBuf::from("/tmp/x"),
            seq: 1,
            body_hash: "a".repeat(64),
            dedup_until: 1_720_000_000,
            disk_bytes: 100,
            ram_bytes: record_ram_estimate(100),
        };
        runtime
            .tombstone_dedup()
            .try_insert_new("recipient-t", "id".into(), entry)
            .expect("insert");
        let snap = runtime.recipient_snapshot();
        assert_eq!(snap, vec!["recipient-t".to_string()]);
        runtime.close();
        let _ = runtime.drain_handles(std::time::Duration::from_secs(5)).await;
    }

    #[tokio::test]
    async fn recipient_snapshot_union_dedupes_recipients_present_in_both_projections() {
        let runtime = spawn_test_runtime_for_snapshot();
        // Same recipient key in both projections — snapshot MUST
        // return it exactly once (no duplicate sweep dispatch).
        let meta = ActiveEntryMeta {
            path: std::path::PathBuf::from("/tmp/x"),
            seq: 1,
            body_hash: "a".repeat(64),
            expires_at: 1_720_000_000,
            disk_bytes: 100,
            ram_bytes: record_ram_estimate(100),
        };
        let entry = TombstoneEntry {
            path: std::path::PathBuf::from("/tmp/y"),
            seq: 2,
            body_hash: "b".repeat(64),
            dedup_until: 1_720_000_000,
            disk_bytes: 200,
            ram_bytes: record_ram_estimate(200),
        };
        runtime
            .active_index()
            .try_insert_new("shared-recipient", "id-a".into(), meta)
            .expect("active insert");
        runtime
            .tombstone_dedup()
            .try_insert_new("shared-recipient", "id-b".into(), entry)
            .expect("tomb insert");
        let snap = runtime.recipient_snapshot();
        assert_eq!(
            snap,
            vec!["shared-recipient".to_string()],
            "shared recipient must appear exactly once"
        );
        runtime.close();
        let _ = runtime.drain_handles(std::time::Duration::from_secs(5)).await;
    }

    #[tokio::test]
    async fn recipient_snapshot_disjoint_projections_returns_union() {
        let runtime = spawn_test_runtime_for_snapshot();
        let meta = ActiveEntryMeta {
            path: std::path::PathBuf::from("/tmp/x"),
            seq: 1,
            body_hash: "a".repeat(64),
            expires_at: 1_720_000_000,
            disk_bytes: 100,
            ram_bytes: record_ram_estimate(100),
        };
        let entry = TombstoneEntry {
            path: std::path::PathBuf::from("/tmp/y"),
            seq: 2,
            body_hash: "b".repeat(64),
            dedup_until: 1_720_000_000,
            disk_bytes: 200,
            ram_bytes: record_ram_estimate(200),
        };
        runtime
            .active_index()
            .try_insert_new("only-queued", "id-a".into(), meta)
            .expect("active insert");
        runtime
            .tombstone_dedup()
            .try_insert_new("only-tomb", "id-b".into(), entry)
            .expect("tomb insert");
        let mut snap = runtime.recipient_snapshot();
        snap.sort();
        assert_eq!(
            snap,
            vec!["only-queued".to_string(), "only-tomb".to_string()],
            "both disjoint recipients must appear once each"
        );
        runtime.close();
        let _ = runtime.drain_handles(std::time::Duration::from_secs(5)).await;
    }

    #[tokio::test]
    async fn spawn_worker_runtime_creates_pool_and_closes_cleanly() {
        let dir = build_state_dir();
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(64);
        let spec = WorkerRuntimeSpec {
            rest_store: Arc::new(RwLock::new(HashMap::new())),
            store: Arc::new(RwLock::new(HashMap::new())),
            state_dir: dir.path().to_path_buf(),
            max_envelopes_per_recipient: 1,
            boot_generation: 1,
            seq_mac_root_key: Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            capacity: Arc::new(GlobalCapacityGate::new(caps())),
            fatal: fatal_tx,
            worker_hash_key: TEST_KEY,
            active_index: Arc::new(ActiveRecordIndex::new()),
            tombstone_dedup: Arc::new(TombstoneDedupTable::new()),
            boot_seed_stats: BootSeedStats::default(),
            tombstone_config: crate::tombstone_config::TombstoneConfig::from_secs(172_800).unwrap(),
            clock: ClockSource::Fixed(TEST_NOW_EPOCH_SECS),
        };
        let runtime = match spawn_worker_runtime(spec) {
            Ok(r) => r,
            Err(_) => panic!("spawn failed"),
        };
        assert_eq!(runtime.expected_worker_count(), REST_WORKER_COUNT);
        assert!(matches!(runtime.state_kind(), PoolStateKind::Running));
        runtime.close();
        assert!(matches!(runtime.state_kind(), PoolStateKind::Closing));
    }

    // ─── Round-6 F2: try_send empty → boundary fatal + typed err ─

    #[tokio::test]
    async fn worker_runtime_try_send_empty_recipient_is_boundary_fatal() {
        // Round-6 F2: an empty-recipient op is REJECTED at the
        // runtime boundary — no worker_for panic, no worker 0
        // dispatch. `FatalReason::IngressBypassAtBoundary` is
        // broadcast directly on the fatal channel and try_send
        // returns `RuntimeSendError::EmptyRecipient`.
        let dir = build_state_dir();
        let (fatal_tx, mut fatal_rx) = broadcast::channel::<FatalReason>(16);
        let spec = build_test_spec(&dir, 1, TEST_MAC_KEY, fatal_tx);
        let runtime = match spawn_worker_runtime(spec) {
            Ok(r) => r,
            Err(_) => panic!("spawn failed"),
        };

        let (reply_tx, _reply_rx) = oneshot::channel();
        let op = RestOp::Send {
            recipient: String::new(),
            candidate: sample_candidate("env-empty"),
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
        let _ = runtime.drain_handles(std::time::Duration::from_secs(5)).await;
    }

    #[tokio::test]
    async fn worker_runtime_try_send_empty_recipient_fatal_survives_closing_pool() {
        // Round-6 F2 core guarantee: fatal fires INDEPENDENT of
        // whether pool.try_send would succeed. Close the pool
        // first (so pool.try_send would return ShuttingDown for
        // any op), then submit an empty-recipient op. The
        // boundary check runs BEFORE the pool call, so the
        // fatal broadcast still fires.
        let dir = build_state_dir();
        let (fatal_tx, mut fatal_rx) = broadcast::channel::<FatalReason>(16);
        let spec = build_test_spec(&dir, 1, TEST_MAC_KEY, fatal_tx);
        let runtime = match spawn_worker_runtime(spec) {
            Ok(r) => r,
            Err(_) => panic!("spawn failed"),
        };
        runtime.close();
        assert!(matches!(runtime.state_kind(), PoolStateKind::Closing));

        let (reply_tx, _reply_rx) = oneshot::channel();
        let op = RestOp::Send {
            recipient: String::new(),
            candidate: sample_candidate("env-empty-closing"),
            reply: reply_tx,
        };
        match runtime.try_send(op) {
            Err(RuntimeSendError::EmptyRecipient) => {}
            _ => panic!("expected EmptyRecipient even when Closing"),
        }
        let fatal = fatal_rx.recv().await.expect("fatal channel");
        assert!(matches!(fatal, FatalReason::IngressBypassAtBoundary { .. }));

        let _ = runtime.drain_handles(std::time::Duration::from_secs(5)).await;
    }

    // ─── Round-5 F2 + round-6 F1: from_boot fingerprint + seed ────

    fn build_test_spec(
        dir: &TempDir,
        boot_generation: u32,
        mac_key_bytes: [u8; 32],
        fatal_tx: broadcast::Sender<FatalReason>,
    ) -> WorkerRuntimeSpec {
        use crate::boot_loader::BootLoaderResult;
        let key = Arc::new(SeqMacRootKey::from_bytes(mac_key_bytes));
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        let boot = BootLoaderResult::for_lib_test(
            meta,
            Vec::new(),
            crate::persistence::WalkStats::default(),
            false,
            dir.path().to_path_buf(),
        );
        WorkerRuntimeSpec::from_boot_at(
            boot,
            TEST_PER_RECIPIENT_CAP,
            key,
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("matching fingerprint")
    }

    #[test]
    fn from_boot_refuses_seq_mac_key_fingerprint_mismatch() {
        use crate::boot_loader::BootLoaderResult;
        let dir = build_state_dir();
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: "0123456789abcdef".into(),
        };
        let boot = BootLoaderResult::for_lib_test(
            meta,
            Vec::new(),
            crate::persistence::WalkStats::default(),
            false,
            dir.path().to_path_buf(),
        );
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let result = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        );
        match result {
            Err(SpecError::SeqMacKeyFingerprintMismatch {
                boot_fingerprint,
                provided_fingerprint,
            }) => {
                assert_eq!(boot_fingerprint, "0123456789abcdef");
                assert_ne!(provided_fingerprint, "0123456789abcdef");
                assert_eq!(provided_fingerprint.len(), 16);
            }
            _ => panic!("expected fingerprint mismatch refusal"),
        }
    }

    // ─── Round-7 F1/F2 coverage: seed with real records ────────

    fn queued_loaded(recipient: &str, id: &str, seq: u64) -> LoadedRecord {
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: format!("sender-{id}"),
            payload: format!("payload-{id}"),
            sequence_ts: 1_720_000_000_000,
            seq,
            expires_at: 1_720_600_000,
            seq_mac: "a".repeat(64),
            body_hash: "b".repeat(64),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        LoadedRecord {
            recipient: recipient.into(),
            path: std::path::PathBuf::from("/tmp/x"),
            disk_bytes: bytes.len() as u64,
            record,
        }
    }

    fn tombstone_loaded(recipient: &str, id: &str, seq: u64) -> LoadedRecord {
        let record = PersistedRecord::AckedTombstone {
            version: RECORD_VERSION,
            id: id.into(),
            seq,
            body_hash: "b".repeat(64),
            acked_at: 1_720_100_000,
            dedup_until: 1_720_800_000,
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        LoadedRecord {
            recipient: recipient.into(),
            path: std::path::PathBuf::from("/tmp/x"),
            disk_bytes: bytes.len() as u64,
            record,
        }
    }

    fn build_boot_with_records(
        dir: &TempDir,
        boot_generation: u32,
        records: Vec<LoadedRecord>,
    ) -> crate::boot_loader::BootLoaderResult {
        use crate::boot_loader::BootLoaderResult;
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        BootLoaderResult::for_lib_test(
            meta,
            records,
            crate::persistence::WalkStats::default(),
            false,
            dir.path().to_path_buf(),
        )
    }

    #[test]
    fn from_boot_seeds_queued_record_into_both_projections_and_ledger() {
        // Round-7 F1: a Queued record from boot lands in both
        // RAM stores AND the capacity ledger's active_bytes.
        let dir = build_state_dir();
        let recipient = TEST_RECIPIENT.to_string();
        let rec = queued_loaded(&recipient, "env-a", 42);
        let expected_disk = rec.disk_bytes;
        let boot = build_boot_with_records(&dir, 1, vec![rec]);

        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("seed OK");

        // Both stores populated with matching id.
        let (rest_len, ws_len) = {
            let rest = spec.rest_store.blocking_read();
            let ws = spec.store.blocking_read();
            (
                rest.get(&recipient).unwrap().len(),
                ws.get(&recipient).unwrap().len(),
            )
        };
        assert_eq!(rest_len, 1);
        assert_eq!(ws_len, 1);

        // Ledger reflects exactly one queued record with the same
        // disk bytes we serialised.
        let snap = spec.capacity.snapshot();
        assert_eq!(snap.active_envelopes, 1);
        assert_eq!(snap.active_bytes, expected_disk);
        assert_eq!(snap.tombstone_bytes, 0);
    }

    #[test]
    fn from_boot_preserves_tombstones_and_seeds_tombstone_ledger() {
        // Round-7 F1: an AckedTombstone from boot does NOT land
        // in the send stores but IS preserved for M3b and bumps
        // the ledger's tombstone_bytes counter.
        let dir = build_state_dir();
        let recipient = TEST_RECIPIENT.to_string();
        let rec = tombstone_loaded(&recipient, "env-t", 7);
        let expected_disk = rec.disk_bytes;
        let boot = build_boot_with_records(&dir, 1, vec![rec]);

        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("seed OK");

        // Send stores untouched.
        assert!(spec.rest_store.blocking_read().is_empty());
        assert!(spec.store.blocking_read().is_empty());

        // M3b-1: tombstone preserved in the structured dedup
        // table (superseded the round-7 raw Vec preservation).
        assert_eq!(spec.tombstone_dedup.count(), 1);

        // Ledger: no queued, tombstone_bytes = disk_bytes.
        let snap = spec.capacity.snapshot();
        assert_eq!(snap.active_envelopes, 0);
        assert_eq!(snap.active_bytes, 0);
        assert_eq!(snap.tombstone_bytes, expected_disk);
        assert_eq!(snap.tombstone_records, 1);
    }

    #[test]
    fn from_boot_capacity_seed_failed_when_records_exceed_caps() {
        // Round-7 F1/F2: two Queued records with disk_bytes >
        // tiny cap → second reserve fails, ledger stays at
        // zero (the newly-created gate is dropped on error).
        let dir = build_state_dir();
        let recipient = TEST_RECIPIENT.to_string();
        // Very small max_envelopes so second reserve refuses.
        let tiny_caps = CapacityCaps {
            max_envelopes: 1,
            max_bytes: 1_000_000,
            ram_budget: 1_000_000,
        };
        let boot = build_boot_with_records(
            &dir,
            1,
            vec![
                queued_loaded(&recipient, "env-a", 1),
                queued_loaded(&recipient, "env-b", 2),
            ],
        );

        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let result = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            tiny_caps,
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        );
        match result {
            Err(SpecError::CapacitySeedFailed {
                kind: RecordKind::Queued,
                queued_seeded: 1,
                tombstones_seeded: 0,
                ..
            }) => {}
            _ => panic!("expected CapacitySeedFailed on second queued record"),
        }
    }

    // ─── Round-8 F1: seq-order preflight ─────────────────────

    #[test]
    fn from_boot_orders_queued_records_by_seq_within_recipient() {
        // Round-8 F1: records arriving in reverse order (fs walk
        // can produce any permutation) must land in the RAM
        // stores sorted by seq ascending. Poll's since_seq
        // semantics require this — a reverse-ordered Vec would
        // let a poll advance past a lower-seq record that would
        // then become permanently ineligible.
        let dir = build_state_dir();
        let recipient = TEST_RECIPIENT.to_string();
        let records = vec![
            queued_loaded(&recipient, "env-3", 30),
            queued_loaded(&recipient, "env-1", 10),
            queued_loaded(&recipient, "env-2", 20),
        ];
        let boot = build_boot_with_records(&dir, 1, records);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("seed OK");
        let rest = spec.rest_store.blocking_read();
        let ws = spec.store.blocking_read();
        let rest_seqs: Vec<u64> = rest.get(&recipient).unwrap().iter().map(|r| r.seq).collect();
        assert_eq!(rest_seqs, vec![10, 20, 30]);
        // WS Envelope has no seq field, but id order must
        // match the sorted seq order.
        let ws_ids: Vec<String> = ws.get(&recipient).unwrap().iter().map(|e| e.id.clone()).collect();
        assert_eq!(ws_ids, vec!["env-1", "env-2", "env-3"]);
    }

    #[test]
    fn from_boot_refuses_duplicate_seq_within_recipient() {
        // Round-8 F1: two records for the same recipient sharing
        // a seq value is a hard corruption signal — refuse.
        let dir = build_state_dir();
        let recipient = TEST_RECIPIENT.to_string();
        let records = vec![
            queued_loaded(&recipient, "env-a", 7),
            queued_loaded(&recipient, "env-b", 7),
        ];
        let boot = build_boot_with_records(&dir, 1, records);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let result = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        );
        match result {
            Err(SpecError::DuplicateSeqAtBoot {
                recipient: r,
                seq: 7,
            }) if r == recipient => {}
            _ => panic!("expected DuplicateSeqAtBoot at seq 7"),
        }
    }

    // ─── Round-8 F2: per-recipient cap at boot seed ──────────

    #[test]
    fn from_boot_refuses_when_per_recipient_cap_exceeded() {
        // Round-8 F2: cap = 1, recipient has 2 persisted queued
        // records → refuse.
        let dir = build_state_dir();
        let recipient = TEST_RECIPIENT.to_string();
        let records = vec![
            queued_loaded(&recipient, "env-a", 10),
            queued_loaded(&recipient, "env-b", 20),
        ];
        let boot = build_boot_with_records(&dir, 1, records);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let result = WorkerRuntimeSpec::from_boot_at(
            boot,
            1, // per-recipient cap = 1
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        );
        match result {
            Err(SpecError::PerRecipientCapExceededAtBoot {
                recipient: r,
                observed: 2,
                cap: 1,
            }) if r == recipient => {}
            _ => panic!("expected PerRecipientCapExceededAtBoot(2 > 1)"),
        }
    }

    #[test]
    fn from_boot_accepts_when_per_recipient_within_cap_across_recipients() {
        // Round-8 F2 companion: cap = 1, TWO different recipients
        // each holding one record → both accepted. Verifies the
        // check is scoped per-recipient, not global.
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        let bob = "b".repeat(64);
        let records = vec![
            queued_loaded(&alice, "env-a", 10),
            queued_loaded(&bob, "env-b", 11),
        ];
        let boot = build_boot_with_records(&dir, 1, records);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            1, // per-recipient cap = 1
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("two recipients at cap=1 each → accepted");
        let rest = spec.rest_store.blocking_read();
        assert_eq!(rest.get(&alice).unwrap().len(), 1);
        assert_eq!(rest.get(&bob).unwrap().len(), 1);
    }

    #[test]
    fn from_boot_orders_and_refuses_dup_across_multi_recipient_reverse_input() {
        // Round-8 F1 combined: mixed recipients, reverse-ordered
        // input, must sort per-recipient AND catch a duplicate
        // limited to one recipient. Also exercises a load larger
        // than POLL_MAX_ENVELOPES=1 so the ordering invariant
        // actually matters at delivery.
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        let bob = "c".repeat(64);
        let records = vec![
            queued_loaded(&alice, "env-a3", 300),
            queued_loaded(&bob, "env-b2", 20),
            queued_loaded(&alice, "env-a2", 200),
            queued_loaded(&bob, "env-b1", 10),
            queued_loaded(&alice, "env-a1", 100),
        ];
        let boot = build_boot_with_records(&dir, 1, records);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("seed OK");
        let rest = spec.rest_store.blocking_read();
        let alice_seqs: Vec<u64> = rest.get(&alice).unwrap().iter().map(|r| r.seq).collect();
        let bob_seqs: Vec<u64> = rest.get(&bob).unwrap().iter().map(|r| r.seq).collect();
        assert_eq!(alice_seqs, vec![100, 200, 300]);
        assert_eq!(bob_seqs, vec![10, 20]);
    }

    // ─── M3b-1 coverage: boot compaction + indices ───────────

    /// Plant a Queued record on disk under the state_dir so
    /// boot compaction can unlink it. Returns a `LoadedRecord`
    /// carrying the real path.
    fn queued_on_disk(
        state_dir: &std::path::Path,
        recipient: &str,
        id: &str,
        seq: u64,
        expires_at: u64,
    ) -> LoadedRecord {
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: format!("sender-{id}"),
            payload: format!("payload-{id}"),
            sequence_ts: 1_720_000_000_000,
            seq,
            expires_at,
            seq_mac: "a".repeat(64),
            body_hash: "b".repeat(64),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(state_dir, recipient, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        LoadedRecord {
            recipient: recipient.into(),
            path,
            disk_bytes: bytes.len() as u64,
            record,
        }
    }

    fn tombstone_on_disk(
        state_dir: &std::path::Path,
        recipient: &str,
        id: &str,
        seq: u64,
        dedup_until: u64,
    ) -> LoadedRecord {
        let record = PersistedRecord::AckedTombstone {
            version: RECORD_VERSION,
            id: id.into(),
            seq,
            body_hash: "b".repeat(64),
            acked_at: 1_720_100_000,
            dedup_until,
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(state_dir, recipient, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        LoadedRecord {
            recipient: recipient.into(),
            path,
            disk_bytes: bytes.len() as u64,
            record,
        }
    }

    #[test]
    fn from_boot_compaction_unlinks_expired_queued_and_bumps_stats() {
        // M3b-1 F1: an expired Queued record must be durably
        // unlinked at boot AND counted in BootSeedStats.
        // Non-expired records seed normally.
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        let expired = queued_on_disk(dir.path(), &alice, "env-old", 1, 1_500_000_000);
        let alive = queued_on_disk(
            dir.path(),
            &alice,
            "env-new",
            2,
            TEST_NOW_EPOCH_SECS + 3_600,
        );
        let expired_path = expired.path.clone();
        let alive_path = alive.path.clone();
        let expired_bytes = expired.disk_bytes;
        let boot = build_boot_with_records(&dir, 1, vec![expired, alive]);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);

        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("seed OK");
        // Expired file is gone from disk.
        assert!(!expired_path.exists(), "expired queued file not unlinked");
        // Alive file remains and is indexed.
        assert!(alive_path.exists());
        assert_eq!(spec.active_index.count(), 1);
        assert!(spec.active_index.get(&alice, "env-new").is_some());
        // Stats reflect the compaction outcome.
        assert_eq!(spec.boot_seed_stats.queued_seeded, 1);
        assert_eq!(spec.boot_seed_stats.queued_expired_swept, 1);
        assert_eq!(spec.boot_seed_stats.tombstones_seeded, 0);
        assert_eq!(spec.boot_seed_stats.tombstones_expired_swept, 0);
        assert_eq!(spec.boot_seed_stats.disk_reclaimed_bytes, expired_bytes);
        // Ledger reflects the surviving Queued only.
        let snap = spec.capacity.snapshot();
        assert_eq!(snap.active_envelopes, 1);
    }

    #[test]
    fn from_boot_compaction_unlinks_past_dedup_tombstone_and_bumps_stats() {
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        let expired_tomb = tombstone_on_disk(dir.path(), &alice, "env-t", 5, 1_500_000_000);
        let expired_path = expired_tomb.path.clone();
        let expired_bytes = expired_tomb.disk_bytes;
        let boot = build_boot_with_records(&dir, 1, vec![expired_tomb]);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);

        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("seed OK");
        assert!(!expired_path.exists());
        assert_eq!(spec.tombstone_dedup.count(), 0);
        assert_eq!(spec.boot_seed_stats.tombstones_expired_swept, 1);
        assert_eq!(spec.boot_seed_stats.disk_reclaimed_bytes, expired_bytes);
        let snap = spec.capacity.snapshot();
        assert_eq!(snap.tombstone_bytes, 0);
    }

    #[test]
    fn from_boot_compaction_fails_boot_on_unlink_error() {
        // M3b-1 F1: if the on-disk file is missing entirely,
        // fs::remove_file returns NotFound → BootCompactionFailed.
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        // Construct a LoadedRecord whose path doesn't exist.
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: "env-gone".into(),
            sealed_sender: "s".into(),
            payload: "p".into(),
            sequence_ts: 1_720_000_000_000,
            seq: 1,
            expires_at: 1_500_000_000, // expired
            seq_mac: "a".repeat(64),
            body_hash: "b".repeat(64),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let phantom_path = dir.path().join("queue/does/not/exist.json");
        let loaded = LoadedRecord {
            recipient: alice,
            path: phantom_path.clone(),
            disk_bytes: bytes.len() as u64,
            record,
        };
        let boot = build_boot_with_records(&dir, 1, vec![loaded]);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);

        let result = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        );
        match result {
            Err(SpecError::BootCompactionFailed { path, cause: _ })
                if path == phantom_path => {}
            _ => panic!("expected BootCompactionFailed"),
        }
    }

    #[test]
    fn from_boot_active_index_and_tombstone_dedup_populated_from_survivors() {
        // M3b-1: surviving Queued lands in active_index with
        // precise footprint; surviving tombstone lands in
        // tombstone_dedup.
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        let queued = queued_on_disk(
            dir.path(),
            &alice,
            "env-q",
            42,
            TEST_NOW_EPOCH_SECS + 3_600,
        );
        let tomb = tombstone_on_disk(
            dir.path(),
            &alice,
            "env-t",
            17,
            TEST_NOW_EPOCH_SECS + 7_200,
        );
        let expected_q_disk = queued.disk_bytes;
        let expected_t_disk = tomb.disk_bytes;
        let boot = build_boot_with_records(&dir, 1, vec![queued, tomb]);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);

        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("seed OK");
        // Active index: one entry with the queued footprint.
        assert_eq!(spec.active_index.count(), 1);
        let active = spec.active_index.get(&alice, "env-q").expect("active");
        assert_eq!(active.seq, 42);
        assert_eq!(active.disk_bytes, expected_q_disk);
        assert!(active.ram_bytes > active.disk_bytes);
        // Dedup: one entry with the tombstone footprint.
        assert_eq!(spec.tombstone_dedup.count(), 1);
        let tomb = spec.tombstone_dedup.get(&alice, "env-t").expect("dedup");
        assert_eq!(tomb.seq, 17);
        assert_eq!(tomb.disk_bytes, expected_t_disk);
    }

    #[tokio::test]
    async fn do_send_inserts_into_active_index_after_success() {
        // M3b-1: every successful new send registers in
        // active_index alongside the two RAM stores.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let candidate = sample_candidate("env-index");
        let outcome = do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .expect("happy path");
        assert!(!outcome.idempotent_replay);
        assert_eq!(ctx.active_index.count(), 1);
        let meta = ctx.active_index.get(TEST_RECIPIENT, &candidate.id).expect("indexed");
        assert_eq!(meta.seq, outcome.seq);
        assert_eq!(meta.expires_at, candidate.expires_at);
        assert!(meta.disk_bytes > 0);
        assert!(meta.ram_bytes > meta.disk_bytes);
    }

    // ─── M3b-1 round-1: boundary + fail-stop + structural identity ─

    #[test]
    fn from_boot_compaction_treats_expires_at_equal_to_now_as_expired() {
        // Round-1 test-additions: the `<=` comparator classifies
        // `expires_at == now` as expired; verify by planting a
        // record whose expiry equals TEST_NOW_EPOCH_SECS.
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        let boundary = queued_on_disk(
            dir.path(),
            &alice,
            "env-boundary",
            1,
            TEST_NOW_EPOCH_SECS,
        );
        let path = boundary.path.clone();
        let boot = build_boot_with_records(&dir, 1, vec![boundary]);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("compaction OK");
        assert!(!path.exists());
        assert_eq!(spec.boot_seed_stats.queued_expired_swept, 1);
        assert_eq!(spec.active_index.count(), 0);
    }

    #[test]
    fn from_boot_compaction_treats_dedup_until_equal_to_now_as_expired() {
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        let boundary = tombstone_on_disk(
            dir.path(),
            &alice,
            "env-tomb-boundary",
            7,
            TEST_NOW_EPOCH_SECS,
        );
        let path = boundary.path.clone();
        let boot = build_boot_with_records(&dir, 1, vec![boundary]);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("compaction OK");
        assert!(!path.exists());
        assert_eq!(spec.boot_seed_stats.tombstones_expired_swept, 1);
        assert_eq!(spec.tombstone_dedup.count(), 0);
    }

    #[test]
    fn from_boot_expired_plus_live_recipient_at_cap_1_succeeds_after_compaction() {
        // Round-1 test-additions: cap=1 with two records
        // (expired + live) SUCCEEDS because compaction removes
        // the expired one BEFORE the per-recipient cap check.
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        let expired = queued_on_disk(dir.path(), &alice, "env-old", 1, 1_500_000_000);
        let alive = queued_on_disk(
            dir.path(),
            &alice,
            "env-new",
            2,
            TEST_NOW_EPOCH_SECS + 3_600,
        );
        let boot = build_boot_with_records(&dir, 1, vec![expired, alive]);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec::from_boot_at(
            boot,
            1, // per-recipient cap = 1
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        )
        .expect("compaction reduces to 1 record → passes cap=1");
        assert_eq!(spec.active_index.count(), 1);
        assert!(spec.active_index.get(&alice, "env-new").is_some());
    }

    #[test]
    fn active_index_try_insert_new_refuses_duplicate_at_boot() {
        // Round-1 F2: seed a synthetic corruption — two records
        // sharing `(recipient, id)` (which the boot loader path
        // uniqueness normally forbids). The checked
        // `try_insert_new` refuses fail-closed.
        //
        // Different `seq` values so the earlier duplicate-seq
        // check does not trip first.
        let dir = build_state_dir();
        let alice = TEST_RECIPIENT.to_string();
        let a = queued_on_disk(dir.path(), &alice, "env-dup", 1, TEST_NOW_EPOCH_SECS + 3_600);
        // Same id but different seq. record_path is the same →
        // rewriting the same file is fine for the test purposes.
        let b_record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: "env-dup".into(),
            sealed_sender: "s".into(),
            payload: "p".into(),
            sequence_ts: 1_720_000_000_000,
            seq: 2,
            expires_at: TEST_NOW_EPOCH_SECS + 3_600,
            seq_mac: "a".repeat(64),
            body_hash: "b".repeat(64),
        };
        let b_bytes = serde_json::to_vec(&b_record).unwrap();
        let b = LoadedRecord {
            recipient: alice.clone(),
            path: a.path.clone(),
            disk_bytes: b_bytes.len() as u64,
            record: b_record,
        };
        let boot = build_boot_with_records(&dir, 1, vec![a, b]);
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let result = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        );
        match result {
            Err(SpecError::DuplicateIdAtBootIndex { recipient, id })
                if recipient == alice && id == "env-dup" => {}
            _ => panic!("expected DuplicateIdAtBootIndex"),
        }
    }

    #[tokio::test]
    async fn do_send_active_index_entry_matches_persisted_record_exactly() {
        // Round-1 F3: verify the entry landed with the same
        // path, body_hash, and disk_bytes that reached disk —
        // structurally, not via a second body_hash computation.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let candidate = sample_candidate("env-identity-check");
        let outcome = do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .expect("send OK");
        let expected_path = record_path(dir.path(), TEST_RECIPIENT, &candidate.id);
        let disk_bytes_actual = std::fs::metadata(&expected_path).unwrap().len();
        let expected_body_hash = compute_body_hash_hex(
            candidate.sealed_sender.as_bytes(),
            candidate.payload.as_bytes(),
        );
        let meta = ctx
            .active_index
            .get(TEST_RECIPIENT, &candidate.id)
            .expect("indexed");
        assert_eq!(meta.path, expected_path);
        assert_eq!(meta.disk_bytes, disk_bytes_actual);
        assert_eq!(meta.body_hash, expected_body_hash);
        assert_eq!(meta.seq, outcome.seq);
    }

    #[test]
    fn active_record_index_try_insert_new_direct_reports_existing_meta() {
        // Round-1 F2 unit: direct exercise of the checked insert
        // API — the returned ExistingEntry carries the previous
        // meta so callers can log drift diagnostics before
        // fail-stopping.
        let idx = ActiveRecordIndex::new();
        let first = ActiveEntryMeta {
            path: std::path::PathBuf::from("/tmp/a"),
            seq: 10,
            body_hash: "a".repeat(64),
            expires_at: 1_720_600_000,
            disk_bytes: 100,
            ram_bytes: 300,
        };
        let second = ActiveEntryMeta {
            path: std::path::PathBuf::from("/tmp/b"),
            seq: 20,
            body_hash: "b".repeat(64),
            expires_at: 1_720_700_000,
            disk_bytes: 200,
            ram_bytes: 400,
        };
        idx.try_insert_new("alice", "env-1".into(), first.clone())
            .expect("first accepted");
        match idx.try_insert_new("alice", "env-1".into(), second.clone()) {
            Err(existing) => {
                assert_eq!(existing.rejected_id, "env-1");
                assert_eq!(existing.rejected_meta.path, second.path);
                assert_eq!(existing.existing_meta.path, first.path);
            }
            Ok(_) => panic!("duplicate must be refused"),
        }
    }

    #[test]
    fn tombstone_dedup_try_insert_new_reports_existing_entry() {
        let table = TombstoneDedupTable::new();
        let first = TombstoneEntry {
            path: std::path::PathBuf::from("/tmp/a"),
            seq: 10,
            body_hash: "a".repeat(64),
            dedup_until: 1_720_600_000,
            disk_bytes: 100,
            ram_bytes: 300,
        };
        let second = TombstoneEntry {
            path: std::path::PathBuf::from("/tmp/b"),
            seq: 20,
            body_hash: "b".repeat(64),
            dedup_until: 1_720_700_000,
            disk_bytes: 200,
            ram_bytes: 400,
        };
        table
            .try_insert_new("alice", "env-1".into(), first.clone())
            .expect("first accepted");
        match table.try_insert_new("alice", "env-1".into(), second) {
            Err(existing) => {
                assert_eq!(existing.rejected_id, "env-1");
                assert_eq!(existing.existing_meta.seq, first.seq);
            }
            Ok(_) => panic!("duplicate must be refused"),
        }
    }

    // ─── M3b-1 round-2: pre-write drift consistency gate ─────

    #[tokio::test]
    async fn do_send_consistent_prior_record_returns_idempotent_replay_without_fatal() {
        // Round-2 F1 companion (positive path): rest_store and
        // active_index both agree on the prior record → normal
        // replay result, no fatal, no disk mutation.
        //
        // M4-2b round-3 REDLINE addition: the outcome must
        // surface `SendDisposition::QueuedReplay` (not Tombstone
        // replay) so the handler re-runs notify + live-delivery
        // + push best-effort — the message is still awaiting
        // recipient ack.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let candidate = sample_candidate("env-idem");
        let first = do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .unwrap();
        let snap_after_first = ctx.capacity.snapshot();
        let second = do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .unwrap();
        assert!(second.idempotent_replay);
        assert_eq!(second.seq, first.seq);
        assert_eq!(
            second.disposition,
            SendDisposition::QueuedReplay,
            "still-Queued record must surface QueuedReplay so handler re-runs delivery"
        );
        assert_eq!(ctx.capacity.snapshot(), snap_after_first);
    }

    // ─── M3b-2a: do_send consults tombstone_dedup ────────────

    /// Seed a tombstone entry for `(TEST_RECIPIENT, id)` on
    /// `ctx.tombstone_dedup`, plant a matching file at the
    /// canonical record path so the consistency check's
    /// on-disk footprint succeeds, and return the entry's
    /// (seq, body_hash) for the caller to assert against.
    async fn seed_tombstone_for_test(
        ctx: &ActorContext,
        id: &str,
        candidate_sealed_sender: &str,
        candidate_payload: &str,
        acked_seq: u64,
    ) -> (u64, String) {
        let body_hash = compute_body_hash_hex(
            candidate_sealed_sender.as_bytes(),
            candidate_payload.as_bytes(),
        );
        // Plant a plausible AckedTombstone on disk so the
        // canonical-path + regular-file + footprint check
        // passes.
        let record = PersistedRecord::AckedTombstone {
            version: RECORD_VERSION,
            id: id.into(),
            seq: acked_seq,
            body_hash: body_hash.clone(),
            acked_at: 1_720_500_000,
            dedup_until: 1_720_800_000,
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(&ctx.state_dir, TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let ram_bytes = record_ram_estimate(bytes.len() as u64);
        ctx.tombstone_dedup
            .try_insert_new(
                TEST_RECIPIENT,
                id.into(),
                TombstoneEntry {
                    path,
                    seq: acked_seq,
                    body_hash: body_hash.clone(),
                    dedup_until: 1_720_800_000,
                    disk_bytes: bytes.len() as u64,
                    ram_bytes,
                },
            )
            .expect("first insert");
        (acked_seq, body_hash)
    }

    #[tokio::test]
    async fn do_send_after_tombstoned_ack_returns_idempotent_replay_with_prior_seq() {
        // M3b-2a: a retry for an envelope that was previously
        // acked (tombstone_dedup has the entry, no queued state)
        // must return the PRIOR seq with `idempotent_replay=true`.
        // The client sees an identical reply — no double delivery,
        // no new send.
        //
        // M4-2b round-3 REDLINE addition: the outcome must
        // surface `SendDisposition::TombstoneReplay` (distinct
        // from QueuedReplay) so the handler skips re-delivery —
        // the recipient has already acked.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let candidate = sample_candidate("env-tomb-replay");
        let (acked_seq, _bh) = seed_tombstone_for_test(
            &ctx,
            &candidate.id,
            &candidate.sealed_sender,
            &candidate.payload,
            424242,
        )
        .await;
        let snap_before = ctx.capacity.snapshot();
        let outcome = do_send(&ctx, TEST_RECIPIENT, candidate)
            .await
            .expect("tombstoned replay is Ok");
        assert!(outcome.idempotent_replay);
        assert_eq!(outcome.seq, acked_seq);
        assert_eq!(
            outcome.disposition,
            SendDisposition::TombstoneReplay,
            "already-Acked record must surface TombstoneReplay so handler skips re-delivery"
        );
        // No disk mutation, no ledger mutation — same shape as
        // the queued-replay idempotent path.
        assert_eq!(ctx.capacity.snapshot(), snap_before);
    }

    #[tokio::test]
    async fn do_send_after_tombstoned_ack_divergent_body_returns_409() {
        // M3b-2a: a retry with a DIFFERENT body against a
        // tombstoned envelope must surface as
        // `EnvelopeIdReusedWithDivergentBody` — same treatment
        // as the queued-replay divergent case.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let mut candidate = sample_candidate("env-tomb-diverge");
        // Seed a tombstone under the ORIGINAL body.
        seed_tombstone_for_test(
            &ctx,
            &candidate.id,
            &candidate.sealed_sender,
            &candidate.payload,
            123,
        )
        .await;
        // Retry with a divergent payload.
        candidate.payload = "different-payload".into();
        let err = do_send(&ctx, TEST_RECIPIENT, candidate)
            .await
            .unwrap_err();
        assert!(matches!(
            err,
            SendError::EnvelopeIdReusedWithDivergentBody { .. }
        ));
    }

    #[tokio::test]
    async fn do_send_consistent_prior_record_divergent_body_returns_409_without_fatal() {
        // Round-2 F1 companion (409 path): stores agree, incoming
        // body differs → normal `EnvelopeIdReusedWithDivergentBody`
        // error, no fatal.
        let dir = build_state_dir();
        let (ctx, _tx) = build_ctx(&dir);
        let mut candidate = sample_candidate("env-diverge");
        do_send(&ctx, TEST_RECIPIENT, candidate.clone())
            .await
            .unwrap();
        candidate.payload = "different-payload".into();
        let err = do_send(&ctx, TEST_RECIPIENT, candidate)
            .await
            .unwrap_err();
        assert!(matches!(
            err,
            SendError::EnvelopeIdReusedWithDivergentBody { .. }
        ));
    }

    // ─── M3b-1 round-2 F2: subprocess FATAL test ─────────────

    /// Child sub-test entry: when the `PHANTOM_M3B1_R3_FORCE_DRIFT`
    /// env var is set, seed the fault seam and drive `do_send`
    /// to trigger the pre-write drift FATAL path. Uses the
    /// PARENT-OWNED state_dir (via `PHANTOM_M3B1_R3_STATE_DIR`)
    /// so the parent can inspect filesystem state after abort.
    #[test]
    fn active_index_drift_pre_write_forced_abort_child() {
        if std::env::var("PHANTOM_M3B1_R3_FORCE_DRIFT").is_err() {
            return; // Not the spawned child.
        }
        let state_dir = std::env::var_os("PHANTOM_M3B1_R3_STATE_DIR")
            .map(std::path::PathBuf::from)
            .expect("PHANTOM_M3B1_R3_STATE_DIR must be provided by parent");
        // `build_ctx` calls `tokio::spawn` internally to plant a
        // sentinel pool JoinHandle, so the whole subprocess flow
        // must run under a Tokio runtime.
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async {
            FORCE_ACTIVE_INDEX_DRIFT_FATAL
                .store(true, std::sync::atomic::Ordering::SeqCst);
            let (ctx, _tx) = build_ctx_at(&state_dir);
            let candidate = sample_candidate("env-drift");
            let _ = do_send(&ctx, TEST_RECIPIENT, candidate).await;
        });
        // Sentinel: must NOT reach stderr if abort fired.
        eprintln!("CHILD_REACHED_END");
    }

    /// Walk `dir` recursively and collect every `.staging-*` file
    /// name. Used by the subprocess test to assert no
    /// half-written tempfile survives the pre-write abort.
    ///
    /// **Round-4 F2**: returns `io::Result` — round-3 discarded
    /// walk errors via `let _ = walk(...)`, which meant an
    /// unreadable subtree could yield an empty list and let the
    /// no-staging assertion pass fail-open. The parent test now
    /// `.expect(...)`s the walk so any traversal error is a
    /// hard test failure.
    fn collect_staging_tempfiles(
        dir: &std::path::Path,
    ) -> std::io::Result<Vec<std::path::PathBuf>> {
        let mut out = Vec::new();
        fn walk(
            dir: &std::path::Path,
            out: &mut Vec<std::path::PathBuf>,
        ) -> std::io::Result<()> {
            for entry in std::fs::read_dir(dir)? {
                let entry = entry?;
                let path = entry.path();
                let ty = entry.file_type()?;
                if ty.is_dir() {
                    walk(&path, out)?;
                } else if ty.is_file() {
                    if let Some(name) = path.file_name().and_then(|s| s.to_str()) {
                        if name.starts_with(".staging-") {
                            out.push(path.clone());
                        }
                    }
                }
            }
            Ok(())
        }
        walk(dir, &mut out)?;
        Ok(out)
    }

    #[test]
    fn active_index_drift_pre_write_fatal_aborts_child_process_and_preserves_disk() {
        // Round-3 F2: parent-owned state_dir + sentinel
        // preservation.
        //
        // Parent-side setup:
        //   * TempDir owned by this test (survives child abort);
        //   * meta.json planted so `build_ctx_at` is a plausible
        //     production shape;
        //   * SENTINEL_BYTES planted at the canonical record path
        //     `do_send` would target if the abort failed to fire.
        //
        // Subprocess: forced-drift `do_send` → fatal_active_index_invariant → abort.
        //
        // Post-abort assertions:
        //   * non-zero exit;
        //   * FATAL + stage=pre_write_gate + drift detail in stderr;
        //   * CHILD_REACHED_END absent;
        //   * sentinel bytes on disk BYTE-IDENTICAL (proves the
        //     abort fired before any atomic write touched the
        //     canonical path — the round-2 review flagged that
        //     the previous test only checked exit/stderr and left
        //     the disk-mutation guarantee unproven);
        //   * no `.staging-*` tempfile anywhere in the queue
        //     subtree (a partial atomic-write attempt would have
        //     left one).
        let parent_dir = TempDir::new().unwrap();
        std::fs::create_dir_all(parent_dir.path().join("queue")).unwrap();
        let key = SeqMacRootKey::from_bytes(TEST_MAC_KEY);
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        crate::queue_meta::write_meta(parent_dir.path(), &meta).unwrap();

        // Plant a sentinel at the exact canonical path the child
        // would write to. The pre-write abort MUST leave these
        // bytes untouched.
        let sentinel_path = record_path(parent_dir.path(), TEST_RECIPIENT, "env-drift");
        crate::atomic_write::create_dir_all_durable(sentinel_path.parent().unwrap())
            .unwrap();
        let sentinel_bytes = b"SENTINEL_ORIGINAL_BYTES_MUST_SURVIVE_ABORT";
        std::fs::write(&sentinel_path, sentinel_bytes).unwrap();

        let exe = std::env::current_exe()
            .expect("current_exe unavailable — cannot verify FATAL path");
        let output = std::process::Command::new(&exe)
            .args([
                "--exact",
                "rest_workers::tests::active_index_drift_pre_write_forced_abort_child",
                "--nocapture",
            ])
            .env("PHANTOM_M3B1_R3_FORCE_DRIFT", "1")
            .env("PHANTOM_M3B1_R3_STATE_DIR", parent_dir.path())
            .env("RUST_TEST_NOCAPTURE", "1")
            .output()
            .expect("failed to spawn child test process");

        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);

        assert!(
            !output.status.success(),
            "child unexpectedly succeeded (abort did not fire)\nstatus: {:?}\nstdout: {stdout}\nstderr: {stderr}",
            output.status
        );
        assert!(
            stderr.contains("FATAL: active_index invariant"),
            "child stderr missing FATAL: marker\nstderr: {stderr}"
        );
        assert!(
            stderr.contains("stage=pre_write_gate"),
            "child stderr missing pre_write_gate stage marker\nstderr: {stderr}"
        );
        assert!(
            !stderr.contains("CHILD_REACHED_END"),
            "child reached end — abort did not fire\nstderr: {stderr}"
        );

        // Round-3 F2 DISK PRESERVATION assertions.
        let on_disk = std::fs::read(&sentinel_path)
            .expect("sentinel path missing — abort failed to prevent unlink or overwrite");
        assert_eq!(
            on_disk, sentinel_bytes,
            "sentinel bytes changed — pre-write gate failed to prevent atomic write"
        );
        let staging = collect_staging_tempfiles(&parent_dir.path().join("queue"))
            .expect("staging walk must not io-fail (round-4 F2: fail-open closed)");
        assert!(
            staging.is_empty(),
            "found staging tempfile(s) left by aborted send: {:?}",
            staging
        );
    }

    // ─── M3b-1 round-3 F1: pre-write gate helper direct coverage ───

    fn make_prior_triple(
        state_dir: &std::path::Path,
        id: &str,
    ) -> (RestEnvelope, Envelope, ActiveEntryMeta) {
        // Plant a real on-disk file so the metadata stat check
        // inside `check_pre_write_consistency` sees a matching
        // footprint.
        let record = PersistedRecord::Queued {
            version: RECORD_VERSION,
            id: id.into(),
            sealed_sender: format!("s-{id}"),
            payload: format!("p-{id}"),
            sequence_ts: 1_720_000_000_000,
            seq: 42,
            expires_at: 1_720_600_000,
            seq_mac: "a".repeat(64),
            body_hash: "b".repeat(64),
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(state_dir, TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let body_hash = compute_body_hash_hex(
            format!("s-{id}").as_bytes(),
            format!("p-{id}").as_bytes(),
        );
        let rest = RestEnvelope {
            id: id.into(),
            from: String::new(),
            sealed_sender: format!("s-{id}"),
            payload: format!("p-{id}"),
            sequence_ts: 1_720_000_000_000,
            seq: 42,
            expires_at: 1_720_600_000,
            seq_mac: "a".repeat(64),
        };
        let store = Envelope {
            id: id.into(),
            to: TEST_RECIPIENT.into(),
            from: String::new(),
            sealed_sender: format!("s-{id}"),
            payload: format!("p-{id}"),
            expires_at: 1_720_600_000,
        };
        let active = ActiveEntryMeta {
            path: path.clone(),
            seq: 42,
            body_hash: body_hash.clone(),
            expires_at: 1_720_600_000,
            disk_bytes: bytes.len() as u64,
            ram_bytes: (bytes.len() as u64) * 2 + 1024,
        };
        (rest, store, active)
    }

    #[test]
    fn check_pre_write_all_absent_permits_new_send() {
        let dir = build_state_dir();
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            None,
            TEST_RECIPIENT,
            "env-x",
            dir.path(),
        );
        assert!(matches!(out, Ok(None)));
    }

    #[test]
    fn check_pre_write_all_present_and_consistent_returns_prior_record() {
        let dir = build_state_dir();
        let (rest, store, active) = make_prior_triple(dir.path(), "env-ok");
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-ok",
            dir.path(),
        );
        let prior = out.expect("consistent state must return Ok");
        let prior = prior.expect("Ok(Some) expected on consistent hit");
        assert_eq!(prior.seq, 42);
        assert_eq!(prior.stored_body_hash.len(), 64);
    }

    #[test]
    fn check_pre_write_partial_only_rest_is_drift() {
        let dir = build_state_dir();
        let (rest, _store, _active) = make_prior_triple(dir.path(), "env-p1");
        let out = check_pre_write_consistency(
            Some(&rest),
            None,
            None,
            None,
            TEST_RECIPIENT,
            "env-p1",
            dir.path(),
        );
        let drift = out.expect_err("partial state must be drift");
        assert!(drift.detail.contains("partial state"));
    }

    #[test]
    fn check_pre_write_partial_only_store_is_drift() {
        let dir = build_state_dir();
        let (_rest, store, _active) = make_prior_triple(dir.path(), "env-p2");
        let out = check_pre_write_consistency(
            None,
            Some(&store),
            None,
            None,
            TEST_RECIPIENT,
            "env-p2",
            dir.path(),
        );
        assert!(out.is_err());
    }

    #[test]
    fn check_pre_write_partial_only_active_is_drift() {
        let dir = build_state_dir();
        let (_rest, _store, active) = make_prior_triple(dir.path(), "env-p3");
        let out = check_pre_write_consistency(
            None,
            None,
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-p3",
            dir.path(),
        );
        assert!(out.is_err());
    }

    #[test]
    fn check_pre_write_all_present_but_seq_mismatch_is_drift() {
        let dir = build_state_dir();
        let (rest, store, mut active) = make_prior_triple(dir.path(), "env-seq");
        active.seq = 999; // diverge
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-seq",
            dir.path(),
        );
        let drift = out.expect_err("seq mismatch is drift");
        assert!(drift.detail.contains("seq mismatch"));
    }

    #[test]
    fn check_pre_write_all_present_but_body_hash_mismatch_is_drift() {
        let dir = build_state_dir();
        let (rest, store, mut active) = make_prior_triple(dir.path(), "env-bh");
        active.body_hash = "phantom-different-hash".to_string();
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-bh",
            dir.path(),
        );
        let drift = out.expect_err("body_hash mismatch is drift");
        assert!(drift.detail.contains("body_hash mismatch"));
    }

    #[test]
    fn check_pre_write_all_present_but_path_mismatch_is_drift() {
        let dir = build_state_dir();
        let (rest, store, mut active) = make_prior_triple(dir.path(), "env-path");
        active.path = std::path::PathBuf::from("/tmp/wrong-path");
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-path",
            dir.path(),
        );
        let drift = out.expect_err("path mismatch is drift");
        assert!(drift.detail.contains("canonical path mismatch"));
    }

    #[test]
    fn check_pre_write_all_present_but_expires_at_mismatch_is_drift() {
        let dir = build_state_dir();
        let (rest, store, mut active) = make_prior_triple(dir.path(), "env-exp");
        active.expires_at = rest.expires_at + 1;
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-exp",
            dir.path(),
        );
        let drift = out.expect_err("expires_at mismatch is drift");
        assert!(drift.detail.contains("expires_at mismatch"));
    }

    #[test]
    fn check_pre_write_all_present_but_ws_projection_diverges_is_drift() {
        let dir = build_state_dir();
        let (rest, mut store, active) = make_prior_triple(dir.path(), "env-ws");
        store.payload = "phantom-diverging-ws-payload".into();
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-ws",
            dir.path(),
        );
        let drift = out.expect_err("WS payload divergence is drift");
        assert!(drift.detail.contains("WS store projection"));
    }

    #[test]
    fn check_pre_write_all_present_but_disk_footprint_mismatch_is_drift() {
        let dir = build_state_dir();
        let (rest, store, mut active) = make_prior_triple(dir.path(), "env-fp");
        active.disk_bytes += 1; // lie about the footprint
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-fp",
            dir.path(),
        );
        let drift = out.expect_err("disk footprint mismatch is drift");
        assert!(drift.detail.contains("disk footprint drift"));
    }

    #[test]
    fn check_pre_write_active_path_is_directory_not_regular_file_is_drift() {
        // Round-4 F1: `symlink_metadata` + `is_file()` refuses a
        // directory at the canonical record path. Length-only
        // stat would have accepted this on some platforms.
        let dir = build_state_dir();
        let (rest, store, active) = make_prior_triple(dir.path(), "env-isdir");
        std::fs::remove_file(&active.path).unwrap();
        std::fs::create_dir(&active.path).unwrap();
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-isdir",
            dir.path(),
        );
        let drift = out.expect_err("directory at record path is drift");
        assert!(
            drift.detail.contains("not a regular file"),
            "expected non-regular-file drift, got: {}",
            drift.detail
        );
    }

    #[cfg(unix)]
    #[test]
    fn check_pre_write_active_path_is_symlink_is_drift() {
        // Round-4 F1: even a symlink to a file with matching
        // length is refused — round-3 code called
        // `fs::metadata` which followed the symlink and only
        // checked length. `symlink_metadata` + `is_file()`
        // returns `false` for symlinks, closing the vector.
        let dir = build_state_dir();
        let (rest, store, active) = make_prior_triple(dir.path(), "env-symlink");
        // Move the real file aside and drop a symlink at the
        // canonical path pointing back at it — matching length,
        // matching content.
        let real_target = dir.path().join("real_target.bin");
        std::fs::rename(&active.path, &real_target).unwrap();
        std::os::unix::fs::symlink(&real_target, &active.path).unwrap();
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-symlink",
            dir.path(),
        );
        let drift = out.expect_err("symlink at record path is drift");
        assert!(
            drift.detail.contains("not a regular file"),
            "expected symlink-rejected drift, got: {}",
            drift.detail
        );
    }

    // ─── M3b-2a: tombstone_dedup extension coverage ─────────

    /// **Round-1 F1 fix**: plant an ACTUAL `AckedTombstone`
    /// record at the canonical path and return a
    /// `TombstoneEntry` whose fields match the on-disk record
    /// exactly. The pre-round-1 helper reused
    /// `make_prior_triple`, which wrote a `Queued` record —
    /// the reviewer's cited demonstration of the durable-vs-RAM
    /// trust bug.
    ///
    /// Body-hash is derived from the same sealed_sender/payload
    /// shape `make_prior_triple` uses so a matching send retry
    /// hashes to the same value.
    fn make_prior_tombstone(
        state_dir: &std::path::Path,
        id: &str,
    ) -> TombstoneEntry {
        let sealed_sender = format!("s-{id}");
        let payload = format!("p-{id}");
        let body_hash =
            compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
        let seq = 777;
        let dedup_until = 1_720_800_000;
        let record = PersistedRecord::AckedTombstone {
            version: RECORD_VERSION,
            id: id.into(),
            seq,
            body_hash: body_hash.clone(),
            acked_at: 1_720_500_000,
            dedup_until,
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(state_dir, TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let ram_bytes = record_ram_estimate(bytes.len() as u64);
        TombstoneEntry {
            path,
            seq,
            body_hash,
            dedup_until,
            disk_bytes: bytes.len() as u64,
            ram_bytes,
        }
    }

    #[test]
    fn check_pre_write_tombstone_only_returns_prior_from_tombstone() {
        // M3b-2a: post-ack idempotent replay — only
        // tombstone_dedup carries the id; queued RAM
        // representations are all empty. Consistency check
        // returns the tombstone's seq/body_hash as the prior
        // record so `do_send` can compare against the incoming
        // candidate body.
        let dir = build_state_dir();
        let tomb = make_prior_tombstone(dir.path(), "env-tomb-only");
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-tomb-only",
            dir.path(),
        );
        let prior = out
            .expect("tombstone-only path is consistent")
            .expect("Ok(Some) on tombstone hit");
        assert_eq!(prior.seq, 777);
        assert_eq!(prior.stored_body_hash, tomb.body_hash);
    }

    #[test]
    fn check_pre_write_tombstone_plus_active_is_double_state_drift() {
        // M3b-2a: a record cannot be both Queued and Tombstoned.
        // Presence of BOTH → double-state drift → FATAL.
        let dir = build_state_dir();
        let (rest, store, active) = make_prior_triple(dir.path(), "env-dual");
        let body_hash = compute_body_hash_hex(
            rest.sealed_sender.as_bytes(),
            rest.payload.as_bytes(),
        );
        let tomb = TombstoneEntry {
            path: active.path.clone(),
            seq: active.seq,
            body_hash,
            dedup_until: 1_720_800_000,
            disk_bytes: active.disk_bytes,
            ram_bytes: active.ram_bytes,
        };
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            Some(&tomb),
            TEST_RECIPIENT,
            "env-dual",
            dir.path(),
        );
        let drift = out.expect_err("double-state must be drift");
        assert!(
            drift.detail.contains("tombstone_dedup + queued-side entries present"),
            "expected double-state drift, got: {}",
            drift.detail
        );
    }

    #[test]
    fn check_pre_write_tombstone_plus_only_rest_is_drift() {
        // M3b-2a: any partial state (tombstone + subset of
        // rest/store/active) is drift.
        let dir = build_state_dir();
        let (rest, _store, _active) = make_prior_triple(dir.path(), "env-t-r");
        let tomb = make_prior_tombstone(dir.path(), "env-t-r");
        let out = check_pre_write_consistency(
            Some(&rest),
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-t-r",
            dir.path(),
        );
        assert!(out.is_err(), "tombstone + only rest is drift");
    }

    #[test]
    fn check_pre_write_tombstone_at_wrong_canonical_path_is_drift() {
        // M3b-2a: tombstone.path != record_path(...) → drift via
        // shared verify_disk_footprint.
        let dir = build_state_dir();
        let mut tomb = make_prior_tombstone(dir.path(), "env-t-path");
        tomb.path = std::path::PathBuf::from("/tmp/wrong-tomb-path");
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-t-path",
            dir.path(),
        );
        let drift = out.expect_err("tombstone at wrong path is drift");
        assert!(drift.detail.contains("canonical path mismatch"));
    }

    #[test]
    fn check_pre_write_tombstone_disk_footprint_mismatch_is_drift() {
        let dir = build_state_dir();
        let mut tomb = make_prior_tombstone(dir.path(), "env-t-fp");
        tomb.disk_bytes = tomb.disk_bytes + 42;
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-t-fp",
            dir.path(),
        );
        let drift = out.expect_err("tombstone footprint drift");
        assert!(drift.detail.contains("disk footprint drift"));
    }

    // ─── M3b-2a round-1 F1: durable tombstone identity ────

    #[test]
    fn check_pre_write_tombstone_on_disk_is_queued_variant_is_drift() {
        // Round-1 F1: the reviewer's cited defect — a `Queued`
        // record left at the canonical path where a tombstone
        // is claimed. `verify_durable_tombstone` must refuse.
        let dir = build_state_dir();
        // Plant a Queued record at the canonical path.
        let (rest, _store, active) = make_prior_triple(dir.path(), "env-t-var");
        // Fabricate a TombstoneEntry pointing at the same path,
        // claiming it's a tombstone.
        let body_hash = compute_body_hash_hex(
            rest.sealed_sender.as_bytes(),
            rest.payload.as_bytes(),
        );
        let tomb = TombstoneEntry {
            path: active.path.clone(),
            seq: active.seq,
            body_hash,
            dedup_until: 1_720_800_000,
            disk_bytes: active.disk_bytes,
            ram_bytes: active.ram_bytes,
        };
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-t-var",
            dir.path(),
        );
        let drift = out.expect_err("Queued at tombstone path is drift");
        assert!(
            drift.detail.contains("Queued, not AckedTombstone"),
            "expected wrong-variant drift, got: {}",
            drift.detail
        );
    }

    #[test]
    fn check_pre_write_tombstone_on_disk_id_mismatch_is_drift() {
        // Plant a tombstone for id=A at path(id=A). Fabricate a
        // TombstoneEntry that expects id=B at the SAME path.
        let dir = build_state_dir();
        let real = make_prior_tombstone(dir.path(), "env-real");
        // Move the file to the canonical path for "env-fake" and
        // build a TombstoneEntry pointing at that new path.
        let fake_path = record_path(dir.path(), TEST_RECIPIENT, "env-fake");
        crate::atomic_write::create_dir_all_durable(fake_path.parent().unwrap()).unwrap();
        std::fs::copy(&real.path, &fake_path).unwrap();
        let entry = TombstoneEntry {
            path: fake_path,
            ..real
        };
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&entry),
            TEST_RECIPIENT,
            "env-fake",
            dir.path(),
        );
        let drift = out.expect_err("id mismatch on disk is drift");
        assert!(
            drift.detail.contains("tombstone durable id mismatch"),
            "expected id-mismatch drift, got: {}",
            drift.detail
        );
    }

    #[test]
    fn check_pre_write_tombstone_on_disk_seq_mismatch_is_drift() {
        let dir = build_state_dir();
        let mut tomb = make_prior_tombstone(dir.path(), "env-t-seq");
        tomb.seq += 1; // RAM disagrees with disk
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-t-seq",
            dir.path(),
        );
        let drift = out.expect_err("seq mismatch on disk is drift");
        assert!(
            drift.detail.contains("tombstone durable seq mismatch"),
            "got: {}",
            drift.detail
        );
    }

    #[test]
    fn check_pre_write_tombstone_on_disk_body_hash_mismatch_is_drift() {
        let dir = build_state_dir();
        let mut tomb = make_prior_tombstone(dir.path(), "env-t-bh");
        tomb.body_hash = "phantom-diverging-hash".to_string();
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-t-bh",
            dir.path(),
        );
        let drift = out.expect_err("body_hash mismatch on disk is drift");
        assert!(
            drift.detail.contains("tombstone durable body_hash mismatch"),
            "got: {}",
            drift.detail
        );
    }

    #[test]
    fn check_pre_write_tombstone_on_disk_dedup_until_mismatch_is_drift() {
        let dir = build_state_dir();
        let mut tomb = make_prior_tombstone(dir.path(), "env-t-du");
        tomb.dedup_until += 3_600;
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-t-du",
            dir.path(),
        );
        let drift = out.expect_err("dedup_until mismatch on disk is drift");
        assert!(
            drift.detail.contains("tombstone durable dedup_until mismatch"),
            "got: {}",
            drift.detail
        );
    }

    #[test]
    fn check_pre_write_tombstone_on_disk_oversize_refused_before_read() {
        // Round-2 F1: `persistence::read_record` metadata-checks
        // BEFORE allocating the read buffer. A file (and matching
        // `disk_bytes`) larger than `MAX_RECORD_BYTES` must be
        // refused via the OversizeBytes RecordReadError without
        // pulling the payload into memory.
        let dir = build_state_dir();
        let tomb = make_prior_tombstone(dir.path(), "env-t-huge");
        // Overwrite the tombstone with a bigger blob at the same
        // path, and lie about disk_bytes to match.
        let oversize = MAX_RECORD_BYTES + 1024;
        let payload = vec![b'X'; oversize as usize];
        std::fs::write(&tomb.path, &payload).unwrap();
        let entry = TombstoneEntry {
            disk_bytes: oversize,
            ..tomb
        };
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&entry),
            TEST_RECIPIENT,
            "env-t-huge",
            dir.path(),
        );
        let drift = out.expect_err("oversize tombstone file is drift");
        assert!(
            drift.detail.contains("oversize"),
            "expected oversize-based drift, got: {}",
            drift.detail
        );
    }

    #[test]
    fn check_pre_write_tombstone_on_disk_unknown_version_is_drift() {
        // Round-2 F1: a valid AckedTombstone with a schema
        // version other than `RECORD_VERSION` must be refused
        // via UnknownVersion, per locked v4 §8. Round-1's raw
        // `serde_json::from_slice` bypass would have accepted
        // the record silently.
        let dir = build_state_dir();
        let id = "env-t-badver";
        let sealed_sender = format!("s-{id}");
        let payload = format!("p-{id}");
        let body_hash =
            compute_body_hash_hex(sealed_sender.as_bytes(), payload.as_bytes());
        let seq = 555;
        let dedup_until = 1_720_800_000;
        // Craft a tombstone with an unrecognised version.
        let bad_version = RECORD_VERSION + 42;
        let record = PersistedRecord::AckedTombstone {
            version: bad_version,
            id: id.into(),
            seq,
            body_hash: body_hash.clone(),
            acked_at: 1_720_500_000,
            dedup_until,
        };
        let bytes = serde_json::to_vec(&record).unwrap();
        let path = record_path(dir.path(), TEST_RECIPIENT, id);
        crate::atomic_write::create_dir_all_durable(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let ram_bytes = record_ram_estimate(bytes.len() as u64);
        let entry = TombstoneEntry {
            path,
            seq,
            body_hash,
            dedup_until,
            disk_bytes: bytes.len() as u64,
            ram_bytes,
        };
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&entry),
            TEST_RECIPIENT,
            id,
            dir.path(),
        );
        let drift = out.expect_err("unknown schema version is drift");
        assert!(
            drift.detail.contains("unknown schema version")
                && drift.detail.contains(&bad_version.to_string()),
            "expected unknown-version drift with version={bad_version}, got: {}",
            drift.detail
        );
    }

    #[test]
    fn check_pre_write_tombstone_on_disk_parse_failure_is_drift() {
        // Plant garbage bytes at the canonical path. serde parse
        // fails → drift.
        let dir = build_state_dir();
        let tomb = make_prior_tombstone(dir.path(), "env-t-parse");
        // Overwrite the (correct-length) tombstone with garbage
        // of the SAME length so verify_disk_footprint passes and
        // verify_durable_tombstone fires on parse.
        let garbage = vec![b'X'; tomb.disk_bytes as usize];
        std::fs::write(&tomb.path, &garbage).unwrap();
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-t-parse",
            dir.path(),
        );
        let drift = out.expect_err("garbage at tombstone path is drift");
        assert!(
            drift.detail.contains("tombstone durable parse failed"),
            "got: {}",
            drift.detail
        );
    }

    // ─── M3b-2b-i primitives coverage ──────────────────────────

    #[test]
    fn clock_source_fixed_returns_supplied_value_including_zero() {
        // Corrective #2: `Fixed(0)` must return `Ok(0)` — unix
        // epoch is a legitimate value, not a "use real clock"
        // sentinel.
        assert_eq!(ClockSource::Fixed(0).now_epoch_secs(), Ok(0));
        assert_eq!(ClockSource::Fixed(1_720_000_000).now_epoch_secs(), Ok(1_720_000_000));
        assert_eq!(ClockSource::Fixed(u64::MAX).now_epoch_secs(), Ok(u64::MAX));
    }

    #[test]
    fn clock_source_before_epoch_returns_typed_error() {
        assert_eq!(
            ClockSource::BeforeEpoch.now_epoch_secs(),
            Err(ClockError::BeforeEpoch),
        );
    }

    #[test]
    fn clock_source_production_returns_recent_value() {
        // Sanity: SystemTime should be > 2020 (>= 1_577_836_800) in
        // any reasonable test environment.
        let now = ClockSource::Production
            .now_epoch_secs()
            .expect("production clock must succeed");
        assert!(now > 1_577_836_800, "clock returned suspicious value {now}");
    }

    #[test]
    fn record_ram_estimate_produces_conservative_upper_bound() {
        // Corrective #4: the same helper is used by boot seed
        // AND future ACK insertion. Test the formula and edge
        // cases.
        let disk = 1000u64;
        let expected = disk * 2 + RAM_STRUCT_OVERHEAD_BYTES * 2;
        assert_eq!(record_ram_estimate(disk), expected);
        assert_eq!(record_ram_estimate(0), RAM_STRUCT_OVERHEAD_BYTES * 2);
        // Saturation: u64::MAX doesn't panic or wrap.
        assert_eq!(record_ram_estimate(u64::MAX), u64::MAX);
    }

    #[test]
    fn ack_error_from_serialize_record_error_maps_variants() {
        // Corrective #6: mapper is unit-testable without an fs
        // seam.
        let too_large = SerializeRecordError::TooLarge {
            observed: 200_000,
            cap: MAX_RECORD_BYTES,
        };
        match AckError::from(too_large) {
            AckError::Serialize {
                observed_bytes: 200_000,
                cap_bytes,
                ..
            } if cap_bytes == MAX_RECORD_BYTES => {}
            other => panic!("wrong mapping for TooLarge: {other:?}"),
        }
        // Manually craft a serde error via a bad JSON parse.
        let serde_err = serde_json::from_slice::<PersistedRecord>(b"not json").unwrap_err();
        let ack = AckError::from(SerializeRecordError::Serde(serde_err));
        match ack {
            AckError::Serialize {
                observed_bytes: 0,
                cap_bytes,
                ..
            } if cap_bytes == MAX_RECORD_BYTES => {}
            other => panic!("wrong mapping for Serde: {other:?}"),
        }
    }

    #[test]
    fn ack_error_from_clock_error_maps_before_epoch() {
        match AckError::from(ClockError::BeforeEpoch) {
            AckError::SystemClockBeforeEpoch => {}
            other => panic!("wrong mapping for ClockError: {other:?}"),
        }
    }

    // ─── Classifier direct coverage (parity with check_pre_write) ─

    #[test]
    fn classify_record_state_absent_returns_absent() {
        let dir = build_state_dir();
        let out = classify_record_state(
            None,
            None,
            None,
            None,
            TEST_RECIPIENT,
            "env-abs",
            dir.path(),
        );
        assert!(matches!(out, Ok(ConsistentRecordState::Absent)));
    }

    #[test]
    fn classify_record_state_queued_returns_queued_handles() {
        let dir = build_state_dir();
        let (rest, store, active) = make_prior_triple(dir.path(), "env-cls-q");
        let out = classify_record_state(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-cls-q",
            dir.path(),
        );
        match out {
            Ok(ConsistentRecordState::Queued { rest: r, active: a, .. }) => {
                assert_eq!(r.seq, active.seq);
                assert_eq!(a.path, active.path);
            }
            other => panic!("expected Queued, got: {other:?}"),
        }
    }

    #[test]
    fn classify_record_state_tombstoned_returns_tombstoned_entry() {
        let dir = build_state_dir();
        let tomb = make_prior_tombstone(dir.path(), "env-cls-t");
        let out = classify_record_state(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-cls-t",
            dir.path(),
        );
        match out {
            Ok(ConsistentRecordState::Tombstoned { entry }) => {
                assert_eq!(entry.seq, tomb.seq);
                assert_eq!(entry.body_hash, tomb.body_hash);
            }
            other => panic!("expected Tombstoned, got: {other:?}"),
        }
    }

    // ─── Cross-index transition primitive ─────────────────────

    fn seed_active_entry(active: &ActiveRecordIndex, id: &str) -> ActiveEntryMeta {
        let meta = ActiveEntryMeta {
            path: std::path::PathBuf::from(format!("/tmp/xi-{id}")),
            seq: 42,
            body_hash: "a".repeat(64),
            expires_at: 1_720_600_000,
            disk_bytes: 128,
            ram_bytes: record_ram_estimate(128),
        };
        active
            .try_insert_new(TEST_RECIPIENT, id.into(), meta.clone())
            .expect("first insert");
        meta
    }

    fn build_tombstone_for(active: &ActiveEntryMeta) -> TombstoneEntry {
        TombstoneEntry {
            path: active.path.clone(),
            seq: active.seq,
            body_hash: active.body_hash.clone(),
            dedup_until: 1_720_800_000,
            disk_bytes: active.disk_bytes,
            ram_bytes: active.ram_bytes,
        }
    }

    // ─── Round-1 F1: prepare/commit two-phase transition ────

    #[test]
    fn prepare_transition_happy_path_holds_guards_and_commit_swaps() {
        // Round-1 F1: prepare acquires guards + validates; the
        // commit call under the same guards swaps the indices.
        // NB: any `.read()`-based observability call
        // (`.count()`, `.get()`) would DEADLOCK against the
        // held write guards. Use `try_write()` / `try_read()`
        // to observe held-ness without blocking.
        let active = ActiveRecordIndex::new();
        let tombstone = TombstoneDedupTable::new();
        let seeded = seed_active_entry(&active, "env-happy");
        let new_tomb = build_tombstone_for(&seeded);
        let prepared = prepare_active_to_tombstone_transition(
            &active,
            &tombstone,
            TEST_RECIPIENT,
            "env-happy",
            &seeded,
        )
        .unwrap_or_else(|e| panic!("prepare failed: {e:?}"));
        // prepare exposes the validated active meta.
        assert_eq!(prepared.active().seq, seeded.seq);
        // Guards still held: try_read is refused on both.
        assert!(active.inner.try_read().is_none());
        assert!(tombstone.inner.try_read().is_none());
        // commit swaps atomically and drops guards.
        // **M3b-2b-ii amendment round-1**: commit now returns
        // `Result<_, CommitError>` for post-durable-commit
        // fail-stop routing; the happy path returns Ok.
        let removed = prepared
            .commit(new_tomb.clone())
            .expect("commit must succeed on happy path");
        assert_eq!(removed.seq, seeded.seq);
        // Now safe to observe.
        assert_eq!(active.count(), 0);
        assert_eq!(tombstone.count(), 1);
        let stored_tomb = tombstone.get(TEST_RECIPIENT, "env-happy").expect("inserted");
        assert_eq!(stored_tomb.seq, new_tomb.seq);
    }

    #[test]
    fn prepare_transition_active_missing_refuses_without_mutation() {
        let active = ActiveRecordIndex::new();
        let tombstone = TombstoneDedupTable::new();
        let unrelated = ActiveEntryMeta {
            path: std::path::PathBuf::from("/tmp/x"),
            seq: 1,
            body_hash: "a".repeat(64),
            expires_at: 1_720_600_000,
            disk_bytes: 100,
            ram_bytes: record_ram_estimate(100),
        };
        let out = prepare_active_to_tombstone_transition(
            &active,
            &tombstone,
            TEST_RECIPIENT,
            "env-nope",
            &unrelated,
        );
        assert!(matches!(out, Err(CrossIndexTransitionError::ActiveMissing)));
        // No mutation; guards released.
        assert_eq!(active.count(), 0);
        assert_eq!(tombstone.count(), 0);
        // Confirm guards actually released — a fresh writer can
        // acquire them.
        assert!(active.inner.try_write().is_some());
        assert!(tombstone.inner.try_write().is_some());
    }

    #[test]
    fn prepare_transition_active_diverged_refuses_without_mutation() {
        let active = ActiveRecordIndex::new();
        let tombstone = TombstoneDedupTable::new();
        let seeded = seed_active_entry(&active, "env-div");
        let expected = ActiveEntryMeta { seq: seeded.seq + 1, ..seeded.clone() };
        let out = prepare_active_to_tombstone_transition(
            &active,
            &tombstone,
            TEST_RECIPIENT,
            "env-div",
            &expected,
        );
        match out {
            Err(CrossIndexTransitionError::ActiveDiverged { existing, expected: exp }) => {
                assert_eq!(existing.seq, seeded.seq);
                assert_eq!(exp.seq, seeded.seq + 1);
            }
            Ok(_) => panic!("expected ActiveDiverged, got Ok(prepared)"),
            Err(other) => panic!("expected ActiveDiverged, got: {other:?}"),
        }
        assert_eq!(active.count(), 1);
        assert_eq!(tombstone.count(), 0);
    }

    #[test]
    fn prepare_transition_refuses_when_tombstone_already_present() {
        let active = ActiveRecordIndex::new();
        let tombstone = TombstoneDedupTable::new();
        let seeded = seed_active_entry(&active, "env-dup-t");
        let existing_tomb = build_tombstone_for(&seeded);
        tombstone
            .try_insert_new(TEST_RECIPIENT, "env-dup-t".into(), existing_tomb.clone())
            .expect("seed tomb");
        let out = prepare_active_to_tombstone_transition(
            &active,
            &tombstone,
            TEST_RECIPIENT,
            "env-dup-t",
            &seeded,
        );
        match out {
            Err(CrossIndexTransitionError::TombstoneAlreadyPresent { existing }) => {
                assert_eq!(existing.seq, existing_tomb.seq);
            }
            Ok(_) => panic!("expected TombstoneAlreadyPresent, got Ok(prepared)"),
            Err(other) => panic!("expected TombstoneAlreadyPresent, got: {other:?}"),
        }
        assert_eq!(active.count(), 1);
        let stored = tombstone.get(TEST_RECIPIENT, "env-dup-t").expect("still there");
        assert_eq!(stored.seq, existing_tomb.seq);
    }

    #[test]
    fn prepared_transition_holds_both_guards_blocking_competing_writers() {
        // Round-1 F1: while `prepared` is alive, competing
        // writers on EITHER index must be blocked. Uses
        // `try_write()` to observe the block without a real
        // thread race.
        let active = ActiveRecordIndex::new();
        let tombstone = TombstoneDedupTable::new();
        let seeded = seed_active_entry(&active, "env-hold");
        let prepared = prepare_active_to_tombstone_transition(
            &active,
            &tombstone,
            TEST_RECIPIENT,
            "env-hold",
            &seeded,
        )
        .unwrap_or_else(|e| panic!("prepare failed: {e:?}"));
        // Both guards are held → try_write() returns None on
        // both underlying RwLocks.
        assert!(
            active.inner.try_write().is_none(),
            "active_index write should be blocked while prepared is alive"
        );
        assert!(
            tombstone.inner.try_write().is_none(),
            "tombstone_dedup write should be blocked while prepared is alive"
        );
        // Even try_read blocks under a write guard.
        assert!(active.inner.try_read().is_none());
        assert!(tombstone.inner.try_read().is_none());
        // Drop-without-commit releases both guards without any
        // mutation.
        drop(prepared);
        assert_eq!(active.count(), 1);
        assert_eq!(tombstone.count(), 0);
        assert!(active.inner.try_write().is_some());
        assert!(tombstone.inner.try_write().is_some());
    }

    #[test]
    fn prepared_transition_drop_without_commit_leaves_indices_unchanged() {
        // Round-1 F1: if the M3b-2b-ii transaction fails between
        // `prepare` and `commit` (e.g. serialize returns Err),
        // dropping the prepared object MUST release guards
        // WITHOUT touching either index.
        let active = ActiveRecordIndex::new();
        let tombstone = TombstoneDedupTable::new();
        let seeded = seed_active_entry(&active, "env-abandon");
        {
            let _prepared = prepare_active_to_tombstone_transition(
                &active,
                &tombstone,
                TEST_RECIPIENT,
                "env-abandon",
                &seeded,
            )
            .unwrap_or_else(|e| panic!("prepare failed: {e:?}"));
            // Simulate `do_ack` bailing before commit (e.g.
            // serialize fails).
            // _prepared drops here at scope end.
        }
        assert_eq!(active.count(), 1);
        assert_eq!(tombstone.count(), 0);
        let still_there = active.get(TEST_RECIPIENT, "env-abandon").expect("kept");
        assert_eq!(still_there.seq, seeded.seq);
    }

    #[cfg(unix)]
    #[test]
    fn check_pre_write_tombstone_path_is_symlink_is_drift() {
        let dir = build_state_dir();
        let tomb = make_prior_tombstone(dir.path(), "env-t-symlink");
        // Move the real file aside; replace with a symlink of
        // matching length.
        let real_target = dir.path().join("real_tomb_target.bin");
        std::fs::rename(&tomb.path, &real_target).unwrap();
        std::os::unix::fs::symlink(&real_target, &tomb.path).unwrap();
        let out = check_pre_write_consistency(
            None,
            None,
            None,
            Some(&tomb),
            TEST_RECIPIENT,
            "env-t-symlink",
            dir.path(),
        );
        let drift = out.expect_err("symlink tombstone path is drift");
        assert!(drift.detail.contains("not a regular file"));
    }

    #[test]
    fn check_pre_write_active_path_missing_from_disk_is_drift() {
        let dir = build_state_dir();
        let (rest, store, active) = make_prior_triple(dir.path(), "env-nofile");
        // Delete the planted file so stat fails, but leave
        // active.path pointing at the canonical record path so
        // the earlier "canonical path mismatch" check does not
        // trip first.
        std::fs::remove_file(&active.path).unwrap();
        let out = check_pre_write_consistency(
            Some(&rest),
            Some(&store),
            Some(&active),
            None,
            TEST_RECIPIENT,
            "env-nofile",
            dir.path(),
        );
        let drift = out.expect_err("missing on-disk file is drift");
        assert!(
            drift.detail.contains("stat failed"),
            "expected stat-failure drift, got: {}",
            drift.detail
        );
    }

    #[test]
    fn from_boot_public_captures_own_clock_and_seeds_empty_records() {
        // Round-1 F1: the public `from_boot` — NO clock parameter
        // — must succeed on an empty boot result and produce
        // zero-filled BootSeedStats. This is the same path
        // integration tests exercise from a separate crate.
        let dir = build_state_dir();
        let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY));
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 1,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        let boot = crate::boot_loader::BootLoaderResult::for_lib_test(
            meta,
            Vec::new(),
            crate::persistence::WalkStats::default(),
            false,
            dir.path().to_path_buf(),
        );
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let spec = WorkerRuntimeSpec::from_boot(boot, 8, key, caps(), fatal_tx)
            .expect("empty boot succeeds under real clock");
        assert_eq!(spec.boot_seed_stats, BootSeedStats::default());
        assert_eq!(spec.active_index.count(), 0);
    }

    #[test]
    fn from_boot_accepts_matching_seq_mac_key_fingerprint() {
        use crate::boot_loader::BootLoaderResult;
        let dir = build_state_dir();
        let key = Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY));
        let meta = QueueMeta {
            version: META_VERSION,
            phase: crate::queue_meta::Phase::Ready,
            boot_generation: 3,
            seq_mac_key_fingerprint: key.fingerprint(),
        };
        let boot = BootLoaderResult::for_lib_test(
            meta,
            Vec::new(),
            crate::persistence::WalkStats::default(),
            false,
            dir.path().to_path_buf(),
        );
        let (fatal_tx, _fatal_rx) = broadcast::channel::<FatalReason>(4);
        let result = WorkerRuntimeSpec::from_boot_at(
            boot,
            8,
            Arc::clone(&key),
            caps(),
            fatal_tx,
            TEST_NOW_EPOCH_SECS,
        );
        assert!(result.is_ok(), "matching fingerprint must accept");
    }

    // ─── RestOp accessor tests (M1 legacy) ────────────────────

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
}
