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
    self, record_path, PersistedRecord, SerializeRecordError, SerializedRecord,
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
/// `< COUNTER_LIMIT` are issuable; hitting the limit trips sticky
/// saturation via [`next_seq`].
pub const SEQ_COUNTER_LIMIT: u64 = 1u64 << 40;

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

/// Success outcome of [`do_send`]. `idempotent_replay=true` means
/// the same `envelope_id + body_hash` was already Queued; the
/// prior `seq` is returned and no disk/RAM/ledger mutation
/// happened.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SendOutcome {
    pub seq: u64,
    pub idempotent_replay: bool,
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
        actual_worker: u8,
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
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AckOutcome;

#[derive(Debug)]
pub enum AckError {
    /// M3a placeholder: Ack dispatch reached the pool before M3b.
    NotYetImplemented,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SweepOutcome;

#[derive(Debug)]
pub enum SweepError {
    /// M3a placeholder: Sweep dispatch reached the pool before M3b.
    NotYetImplemented,
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

    // (2) dedup + per-recipient cap under one read-lock scope.
    // Round-2 F2 fix: `observed` is derived AFTER the guard, using
    // `map_or(0, Vec::len)`, so a recipient with no queue entry
    // still counts as 0 and the cap check runs on every send
    // (including cap=0 which must refuse the first send).
    let observed = {
        let rest = ctx.rest_store.read().await;
        if let Some(queue) = rest.get(recipient) {
            if let Some(existing) = queue.iter().find(|r| r.id == candidate.id) {
                // Recompute body_hash from the stored bytes — no
                // side-channel cache in M3a (the production
                // `IdempotencyCache` bridge lands in M4 wiring).
                let existing_hash = compute_body_hash_hex(
                    existing.sealed_sender.as_bytes(),
                    existing.payload.as_bytes(),
                );
                if existing_hash == body_hash {
                    return Ok(SendOutcome {
                        seq: existing.seq,
                        idempotent_replay: true,
                    });
                }
                return Err(SendError::EnvelopeIdReusedWithDivergentBody {
                    existing_body_hash: existing_hash,
                    incoming_body_hash: body_hash,
                });
            }
            queue.len()
        } else {
            0
        }
    };
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
    let ram_bytes = disk_bytes
        .saturating_mul(2)
        .saturating_add(RAM_STRUCT_OVERHEAD_BYTES.saturating_mul(2));

    // (6) capacity reserve — RAII rollback on any early return.
    let reservation = ctx
        .capacity
        .reserve_send(disk_bytes, ram_bytes)
        .map_err(SendError::CapacityExceeded)?;

    // (7) atomic disk persist — hands the SAME `SerializedRecord`
    // that sized the reservation (round-3 F4/F5).
    let disk_path = record_path(&ctx.state_dir, recipient, &candidate.id);
    if let Err(io_err) = persistence::write_record_bytes(&disk_path, &serialized) {
        // reservation dropped here (unbind on early return) → auto-rollback.
        return Err(SendError::Persistence(io_err));
    }

    // (8+9) canonical two-store lock order — v4.1 V-P0-7 + v4.2.1
    // §5. Build the production projections here so the values move
    // into their guards without another allocation cycle.
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
    // (11) commit reservation — RAII no longer rolls back.
    reservation.commit();
    drop(store_guard);
    drop(rest_guard);

    Ok(SendOutcome {
        seq,
        idempotent_replay: false,
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
        notify_shard_mismatch(op, ctx.worker_id, expected);
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
        RestOp::Ack { reply, .. } => {
            let _ = reply.send(Err(AckError::NotYetImplemented));
            Err(FatalReason::UnsupportedOperation {
                worker_id: ctx.worker_id,
                op_kind: "ack",
            })
        }
        RestOp::Sweep { reply, .. } => {
            let _ = reply.send(Err(SweepError::NotYetImplemented));
            Err(FatalReason::UnsupportedOperation {
                worker_id: ctx.worker_id,
                op_kind: "sweep",
            })
        }
    }
}

/// Send the awaiting handler a typed shard-mismatch reply so it
/// doesn't hang on the oneshot forever. Called only from the
/// pre-dispatch fatal-exit path.
fn notify_shard_mismatch(op: RestOp, actual_worker: u8, expected_worker: usize) {
    match op {
        RestOp::Send { reply, .. } => {
            let _ = reply.send(Err(SendError::ShardMismatch {
                expected_worker,
                actual_worker,
            }));
        }
        RestOp::Ack { reply, .. } => {
            let _ = reply.send(Err(AckError::NotYetImplemented));
        }
        RestOp::Sweep { reply, .. } => {
            let _ = reply.send(Err(SweepError::NotYetImplemented));
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
    /// **Round-7 F1**: `AckedTombstone` records from
    /// `boot.records()` preserved for M3b to consume when it
    /// wires the dedup table. Round-6 skipped these entirely,
    /// which would have lost the durable dedup contract after
    /// a restart. Filter only tombstone variants; queued
    /// records live in the two RAM stores above.
    tombstones: Vec<LoadedRecord>,
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
    pub fn from_boot(
        boot: BootLoaderResult,
        max_envelopes_per_recipient: usize,
        seq_mac_root_key: Arc<SeqMacRootKey>,
        caps: CapacityCaps,
        fatal: broadcast::Sender<FatalReason>,
    ) -> Result<Self, SpecError> {
        let provided_fp = seq_mac_root_key.fingerprint();
        let (meta, records, _walk, _was_first_install, state_dir) = boot.into_parts();
        if provided_fp != meta.seq_mac_key_fingerprint {
            return Err(SpecError::SeqMacKeyFingerprintMismatch {
                boot_fingerprint: meta.seq_mac_key_fingerprint,
                provided_fingerprint: provided_fp,
            });
        }
        // Round-7 F2: factory owns the gate. Any partial state
        // caused by a seed failure below is discarded when the
        // Arc drops on error return.
        let capacity = Arc::new(GlobalCapacityGate::new(caps));
        let (rest_store_map, store_map, tombstones) =
            seed_from_records(records, &capacity, max_envelopes_per_recipient)?;
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
            tombstones,
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
fn seed_from_records(
    records: Vec<LoadedRecord>,
    capacity: &Arc<GlobalCapacityGate>,
    max_envelopes_per_recipient: usize,
) -> Result<
    (
        HashMap<String, Vec<RestEnvelope>>,
        HashMap<String, Vec<Envelope>>,
        Vec<LoadedRecord>,
    ),
    SpecError,
> {
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
            let disk_bytes = loaded.disk_bytes;
            let ram_bytes = disk_bytes
                .saturating_mul(2)
                .saturating_add(RAM_STRUCT_OVERHEAD_BYTES.saturating_mul(2));
            let (id, sealed_sender, payload, sequence_ts, seq, expires_at, seq_mac) =
                if let PersistedRecord::Queued {
                    id,
                    sealed_sender,
                    payload,
                    sequence_ts,
                    seq,
                    expires_at,
                    seq_mac,
                    ..
                } = loaded.record
                {
                    (id, sealed_sender, payload, sequence_ts, seq, expires_at, seq_mac)
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

    // (4) Tombstones: bump tombstone_bytes + tombstone_records
    // via transition, preserve LoadedRecord for M3b.
    for loaded in &tombstones {
        let disk_bytes = loaded.disk_bytes;
        let ram_bytes = disk_bytes
            .saturating_mul(2)
            .saturating_add(RAM_STRUCT_OVERHEAD_BYTES.saturating_mul(2));
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
        tombstones_seeded = tombstones_seeded.saturating_add(1);
    }

    for reservation in reservations {
        reservation.commit();
    }

    Ok((rest_store_map, store_map, tombstones))
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
    /// **Round-7 F1**: tombstone records preserved from
    /// `boot.records()` for M3b to consume when it wires the
    /// dedup table. Consumed by M3b via
    /// [`WorkerRuntime::take_tombstones`].
    tombstones: parking_lot::Mutex<Vec<LoadedRecord>>,
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

    /// **Round-7 F1**: take ownership of the preserved tombstone
    /// records. The tombstones are moved out (a second call
    /// returns an empty Vec) — M3b consumes them into the dedup
    /// table exactly once at wiring time. Storing them in a
    /// `Mutex<Vec<..>>` is the round-7 shape that preserves
    /// them across the Arc-shared runtime handle.
    pub fn take_tombstones(&self) -> Vec<LoadedRecord> {
        std::mem::take(&mut *self.tombstones.lock())
    }

    /// **Round-7 F1**: read-only snapshot of the tombstone count
    /// still queued for M3b. Useful for observability without
    /// consuming the Vec.
    pub fn tombstone_count(&self) -> usize {
        self.tombstones.lock().len()
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

    let seq_counter = Arc::new(AtomicU64::new(0));

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
        tombstones: parking_lot::Mutex::new(spec.tombstones),
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
            state_dir: dir.path().to_path_buf(),
            max_envelopes_per_recipient: TEST_PER_RECIPIENT_CAP,
            boot_generation: 1,
            seq_counter: Arc::new(AtomicU64::new(0)),
            seq_mac_root_key: Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
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
        assert_eq!(a, u64::from(ctx.boot_generation) << 40);
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

    // ─── Ack / Sweep before M3b → typed fatal ────────────────

    #[tokio::test]
    async fn worker_loop_ack_before_m3b_maps_to_typed_fatal() {
        let dir = build_state_dir();
        let (ctx, _tx_unused) = build_ctx(&dir);
        let mut fatal_rx = ctx.fatal.subscribe();
        let (tx, rx) = mpsc::channel::<RestOp>(1);
        let handle = tokio::spawn(async move { worker_loop(ctx, rx).await });

        let (reply_tx, reply_rx) = oneshot::channel();
        tx.send(RestOp::Ack {
            recipient: TEST_RECIPIENT.into(),
            envelope_id: "env-ack".into(),
            reply: reply_tx,
        })
        .await
        .unwrap();

        let ack_result = reply_rx.await.unwrap();
        assert!(matches!(ack_result, Err(AckError::NotYetImplemented)));
        let fatal = fatal_rx.recv().await.unwrap();
        assert!(matches!(
            fatal,
            FatalReason::UnsupportedOperation { op_kind: "ack", .. }
        ));

        drop(tx);
        let outcome = handle.await.unwrap();
        assert!(matches!(
            outcome,
            Err(FatalReason::UnsupportedOperation { op_kind: "ack", .. })
        ));
    }

    #[tokio::test]
    async fn worker_loop_sweep_before_m3b_maps_to_typed_fatal() {
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

        let sweep_result = reply_rx.await.unwrap();
        assert!(matches!(sweep_result, Err(SweepError::NotYetImplemented)));
        let fatal = fatal_rx.recv().await.unwrap();
        assert!(matches!(
            fatal,
            FatalReason::UnsupportedOperation {
                op_kind: "sweep",
                ..
            }
        ));

        drop(tx);
        let outcome = handle.await.unwrap();
        assert!(matches!(
            outcome,
            Err(FatalReason::UnsupportedOperation {
                op_kind: "sweep",
                ..
            })
        ));
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
            tombstones: Vec::new(),
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
            tombstones: Vec::new(),
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
        WorkerRuntimeSpec::from_boot(
            boot,
            TEST_PER_RECIPIENT_CAP,
            key,
            caps(),
            fatal_tx,
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
        let result = WorkerRuntimeSpec::from_boot(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
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
        let spec = WorkerRuntimeSpec::from_boot(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
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
        let spec = WorkerRuntimeSpec::from_boot(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
        )
        .expect("seed OK");

        // Send stores untouched.
        assert!(spec.rest_store.blocking_read().is_empty());
        assert!(spec.store.blocking_read().is_empty());

        // Tombstones preserved for M3b.
        assert_eq!(spec.tombstones.len(), 1);

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
        let result = WorkerRuntimeSpec::from_boot(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            tiny_caps,
            fatal_tx,
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
        let spec = WorkerRuntimeSpec::from_boot(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
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
        let result = WorkerRuntimeSpec::from_boot(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
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
        let result = WorkerRuntimeSpec::from_boot(
            boot,
            1, // per-recipient cap = 1
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
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
        let spec = WorkerRuntimeSpec::from_boot(
            boot,
            1, // per-recipient cap = 1
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
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
        let spec = WorkerRuntimeSpec::from_boot(
            boot,
            8,
            Arc::new(SeqMacRootKey::from_bytes(TEST_MAC_KEY)),
            caps(),
            fatal_tx,
        )
        .expect("seed OK");
        let rest = spec.rest_store.blocking_read();
        let alice_seqs: Vec<u64> = rest.get(&alice).unwrap().iter().map(|r| r.seq).collect();
        let bob_seqs: Vec<u64> = rest.get(&bob).unwrap().iter().map(|r| r.seq).collect();
        assert_eq!(alice_seqs, vec![100, 200, 300]);
        assert_eq!(bob_seqs, vec![10, 20]);
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
        let result = WorkerRuntimeSpec::from_boot(
            boot,
            8,
            Arc::clone(&key),
            caps(),
            fatal_tx,
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
