// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! **PR-2 M4-1**: plumbing-only adapter surface between the HTTP
//! handlers ([`crate::rest_fallback`], [`crate::routes`]) and the
//! shard-worker runtime ([`crate::rest_workers::WorkerRuntime`]).
//!
//! Every function in this module is a **pure translator** — no I/O,
//! no `.await`, no runtime state. Unit-tested against synthetic
//! `SendError` / `AckError` / `SweepError` / `AckOutcome` /
//! `FatalReason` values without spawning any actor.
//!
//! ### Milestone split
//!
//! * **M4-1 (this commit)** — introduce the adapter surface + tests.
//!   `main.rs` has NOT been changed; no handler is calling any of
//!   these functions yet. All symbols are `pub` so M4-2's atomic
//!   cutover can reach them, and `#[allow(dead_code)]` while
//!   nothing calls them.
//! * **M4-2** — atomic production cutover: `main.rs` calls
//!   [`crate::boot_loader::boot`] +
//!   [`crate::rest_workers::WorkerRuntimeSpec::from_boot`] +
//!   [`crate::rest_workers::spawn_worker_runtime`]; `AppState.runtime`
//!   flips from `None` → `Some(_)`; REST/WS `send` and `ack-deliver`
//!   are rewritten to call `runtime.try_send(RestOp::...)` and use
//!   the functions here to translate results back to HTTP; poll paths
//!   become read-only (mutation deleted); the 5-min `Vec::retain`
//!   sweep block is deleted (M4-3 replaces).
//! * **M4-3** — sweep scheduler.
//! * **M4-4** — internal `/live` + `/ready` + `/status` on a loopback
//!   health-listener; unified shutdown via `runtime.close()` +
//!   `drain_handles(45s)` with FATAL + `process::exit(1)` on
//!   deadline or dirty outcome.
//!
//! ### Why every function is `#[allow(dead_code)]` in M4-1
//!
//! Zero callsites this milestone by design. The atomic cutover
//! contract forbids a shadow-mode where handlers touch the legacy
//! store while a runtime also mutates it: the two stores are the
//! SAME `Arc<RwLock<...>>` handles once M4-2 completes the handoff,
//! and split ownership would break every ledger / index / disk
//! invariant M3b spent six sub-milestones establishing. M4-2 lands
//! callers atomically with the field flip.

use crate::rest_workers::{
    AckError, AckOutcome, FatalReason, RuntimeSendError, SendCandidate, SendError,
    SweepError,
};

// ─── Fatal-reason → exit-code classifier ─────────────────────────────

/// **PR-2 M4-1** (mandatory for M4-2 fail-closed subscriber):
/// classify a [`FatalReason`] into the process exit code that will
/// follow the FATAL stderr line.
///
/// **All values return `1`** per the locked M4 shutdown contract:
/// every runtime-fatal condition (subscriber trip, dirty
/// drain outcome, shutdown deadline exceeded) exits with `1` after
/// a `FATAL:` marker. The classifier exists as a discrete function
/// so future contract changes have one place to touch and one place
/// to test, and so the subscriber code path is provably total
/// (`match` on `FatalReason` with no wildcard).
///
/// Extraction to a pure fn also lets the M4-2 subscriber tests
/// verify the classification of each `FatalReason` variant without
/// actually invoking `std::process::exit`.
#[allow(dead_code)] // M4-2 wires the subscriber that calls this
pub fn fatal_reason_to_exit_code(reason: &FatalReason) -> u8 {
    match reason {
        FatalReason::WorkerPanic { .. }
        | FatalReason::WorkerUnexpectedExit { .. }
        | FatalReason::ShardRoutingInvariantViolation { .. }
        | FatalReason::UnsupportedOperation { .. }
        | FatalReason::SeqSpaceExhausted { .. }
        | FatalReason::SeqNamespaceInvalid { .. }
        | FatalReason::IngressBypassAtBoundary { .. }
        | FatalReason::ActiveIndexInvariantViolation { .. } => 1,
    }
}

/// **PR-2 M4-1** — one-line stderr rendering of a fatal for the
/// M4-2 subscriber. Prefix `FATAL: worker-runtime` mirrors the
/// crash-dump collector's grep target that M3b established for
/// `fatal_ack_invariant` / `fatal_sweep_invariant` /
/// `fatal_active_index_invariant`.
///
/// Exposed as a pure `-> String` so tests can inspect the exact
/// bytes without stubbing stderr.
#[allow(dead_code)] // M4-2 subscriber uses this
pub fn render_fatal_line(reason: &FatalReason) -> String {
    format!(
        "FATAL: worker-runtime terminated — reason={reason:?} \
         (M4-2 fatal subscriber): process cannot continue."
    )
}

// ─── HTTP-status mapping ─────────────────────────────────────────────

/// **PR-2 M4-1**: HTTP status + body mapping for a
/// [`crate::rest_workers::SendError`] returned from
/// `runtime.try_send(RestOp::Send)`.
///
/// Preserves the pre-M4 wire contract of the legacy `rest_send`
/// handler wherever the pre-M4 code had a matching branch (400 for
/// unsealed sender, 429 for per-recipient cap / capacity), and
/// slots the M3b-new variants into the shape a REST client already
/// expects (`Persistence` / `Serialize` / `Internal` → 500).
///
/// `ShardMismatch` at the handler layer is a 500 — the worker-side
/// dispatch already escalates the same condition to
/// `FatalReason::ShardRoutingInvariantViolation` (fatal), so this
/// mapping fires only if the response oneshot beat the fatal
/// broadcast.
#[allow(dead_code)] // M4-2 wires rest_send + ws_send callers
pub fn send_error_to_http(err: &SendError) -> HttpErrorShape {
    use SendError::*;
    match err {
        UnsealedSenderRejected => HttpErrorShape::new(400, "unsealed sender rejected"),
        EnvelopeIdReusedWithDivergentBody { .. } => HttpErrorShape::new(
            409,
            "envelope_id reused with divergent body",
        ),
        PerRecipientQueueFull { .. } => {
            HttpErrorShape::new(429, "per-recipient queue full")
        }
        CapacityExceeded(_) => HttpErrorShape::new(429, "capacity exceeded"),
        Serialize { .. } => HttpErrorShape::new(500, "record serialise failed"),
        Persistence(_) => HttpErrorShape::new(500, "persistence failure"),
        SeqSaturation { .. } => HttpErrorShape::new(500, "seq space exhausted"),
        BootGenerationOutOfRange { .. } => {
            HttpErrorShape::new(500, "boot generation invalid")
        }
        SeqMacCompute(_) => HttpErrorShape::new(500, "seq-mac compute failed"),
        ShardMismatch { .. } => HttpErrorShape::new(500, "shard routing invariant"),
        Internal(_) => HttpErrorShape::new(500, "internal invariant"),
    }
}

/// **PR-2 M4-1**: HTTP status + body mapping for the success
/// outcome of `runtime.try_send(RestOp::Ack)`.
///
/// Preserves the pre-M4 `rest_ack_deliver` contract: every success
/// (fresh ack, idempotent replay, unknown envelope) maps to 200 —
/// the pre-M4 handler never distinguished the three at the wire.
/// M4 could differentiate later; kept identical for cutover safety.
#[allow(dead_code)] // M4-2 wires rest_ack + ws_ack callers
pub fn ack_outcome_to_http(_outcome: &AckOutcome) -> HttpOkShape {
    HttpOkShape::new(200, "{\"ok\":1}")
}

/// **PR-2 M4-1**: HTTP status + body mapping for a
/// [`crate::rest_workers::AckError`] returned from
/// `runtime.try_send(RestOp::Ack)`. Same rationale as
/// [`send_error_to_http`].
#[allow(dead_code)] // M4-2 wires rest_ack + ws_ack callers
pub fn ack_error_to_http(err: &AckError) -> HttpErrorShape {
    use AckError::*;
    match err {
        Persistence(_) => HttpErrorShape::new(500, "persistence failure"),
        Serialize { .. } => HttpErrorShape::new(500, "record serialise failed"),
        SystemClockBeforeEpoch => HttpErrorShape::new(500, "system clock invalid"),
        DedupHorizonOverflow { .. } => {
            HttpErrorShape::new(500, "dedup horizon overflow")
        }
        ShardMismatch { .. } => HttpErrorShape::new(500, "shard routing invariant"),
        Internal(_) => HttpErrorShape::new(500, "internal invariant"),
    }
}

/// **PR-2 M4-1**: HTTP status + body mapping for a
/// [`crate::rest_workers::SweepError`]. Sweep is not called from
/// an HTTP handler in the current wire (M4-3 dispatches from the
/// scheduler), but ops tooling / future admin endpoints may want
/// the same translator.
#[allow(dead_code)] // M4-3 wires the sweep scheduler
pub fn sweep_error_to_http(err: &SweepError) -> HttpErrorShape {
    use SweepError::*;
    match err {
        Persistence(_) => HttpErrorShape::new(500, "persistence failure"),
        SystemClockBeforeEpoch => HttpErrorShape::new(500, "system clock invalid"),
        ShardMismatch { .. } => HttpErrorShape::new(500, "shard routing invariant"),
    }
}

// ─── SendCandidate construction ──────────────────────────────────────

/// **PR-2 M4-1**: build the [`SendCandidate`] the M4-2 REST/WS
/// handlers will hand to `RestOp::Send`. Extracted as a pure
/// translator so:
///   1. Both REST and WS callers share one shape (today the two
///      code paths duplicate the field-copy — see the survey at
///      `rest_fallback.rs:2093` and `routes.rs:1083`).
///   2. Server-side `expires_at` derivation
///      (`envelope_ttl_secs + now`) is expressed exactly once,
///      matching the pre-M4 `Envelope::new(..., ttl_secs)` path.
///   3. Unit tests can pin `now_epoch_secs` explicitly instead of
///      relying on `SystemTime::now()`.
///
/// The five inputs are the client's request fields; `now_epoch_secs`
/// is the caller's wall-clock snapshot; `envelope_ttl_secs` is
/// `RelayConfig.envelope_ttl_secs`. Returns a fully-populated
/// `SendCandidate` ready to hand to `runtime.try_send(...)`.
#[allow(dead_code)] // M4-2 wires rest_send + ws_send callers
pub fn build_send_candidate(
    envelope_id: String,
    sealed_sender: String,
    payload: String,
    sequence_ts: u64,
    now_epoch_secs: u64,
    envelope_ttl_secs: u64,
) -> SendCandidate {
    // Trek 2 Stage 1 Q5 lock, preserved through the M4-2b
    // cutover: `sequence_ts` (client-supplied ms since epoch)
    // is quantized to the nearest 60-second boundary here so
    // both REST and WS senders produce byte-identical stored
    // records regardless of how precisely the client clock
    // reports send time. The pre-M4 quantization site was
    // `mirror_envelope_to_rest_store::quantize_sequence_ts_to_60s`
    // (deleted in M4-2b); centralising it in the send-candidate
    // builder keeps the invariant on the single production
    // path.
    let quantized_sequence_ts =
        crate::rest_fallback::quantize_sequence_ts_to_60s(sequence_ts);
    SendCandidate {
        id: envelope_id,
        sealed_sender,
        payload,
        sequence_ts: quantized_sequence_ts,
        expires_at: now_epoch_secs.saturating_add(envelope_ttl_secs),
    }
}

// ─── HTTP shape types ────────────────────────────────────────────────

/// **PR-2 M4-1**: minimal (status, body) pair returned by the error
/// mappers. Kept as a plain struct (not `axum::response::Response`)
/// so the adapter module has zero HTTP framework dependency —
/// unit tests can assert on the fields directly, and M4-2's handler
/// call sites do the final wrapping into `axum` types.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpErrorShape {
    pub status: u16,
    pub message: &'static str,
}

impl HttpErrorShape {
    #[inline]
    pub(crate) const fn new(status: u16, message: &'static str) -> Self {
        Self { status, message }
    }
}

/// **PR-2 M4-1**: success counterpart of [`HttpErrorShape`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpOkShape {
    pub status: u16,
    pub body: &'static str,
}

impl HttpOkShape {
    #[inline]
    pub(crate) const fn new(status: u16, body: &'static str) -> Self {
        Self { status, body }
    }
}

// ─── M4-2a: RuntimeSendError translation ─────────────────────────────

/// **PR-2 M4-2a** (architect Gate #2): classification of an
/// immediate [`RuntimeSendError`] returned from
/// `runtime.try_send(...)`. The Send/Ack dispatch helpers use
/// this to decide whether the caller should surface a 503, a
/// 400, or a fail-stop.
///
/// * `BackpressureOrShutdown` — `Full` (mpsc at capacity) or
///   `ShuttingDown` (pool `Closing`/`Closed`). Handler surface:
///   HTTP 503 (REST) / an error frame (WS). Neither is a fatal
///   condition for the process — the caller may retry.
/// * `IngressBypass` — `EmptyRecipient` (worker_for panics on
///   empty). The runtime already broadcasts
///   `FatalReason::IngressBypassAtBoundary` BEFORE returning
///   this variant, so the fatal subscriber will trip and the
///   process will exit shortly. Handler surface: 400 to the
///   client (validation was bypassed) + rely on the fatal
///   subscriber to exit.
/// * `Internal` — `WorkerIdOutOfBounds`. Unreachable via public
///   API (runtime computes worker_id itself). Handler surface:
///   500 for the immediate reply + treated as a hard invariant
///   break by the caller.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuntimeSendClassification {
    BackpressureOrShutdown,
    IngressBypass,
    Internal,
}

/// **PR-2 M4-2a** (Gate #2): pure classifier for
/// [`RuntimeSendError`]. Exhaustive `match` — the compiler
/// prevents drift when M3b evolves the enum.
#[allow(dead_code)] // M4-2b wires callers
pub fn classify_runtime_send_error(err: &RuntimeSendError) -> RuntimeSendClassification {
    match err {
        RuntimeSendError::Full | RuntimeSendError::ShuttingDown => {
            RuntimeSendClassification::BackpressureOrShutdown
        }
        RuntimeSendError::EmptyRecipient => RuntimeSendClassification::IngressBypass,
        RuntimeSendError::WorkerIdOutOfBounds { .. } => RuntimeSendClassification::Internal,
    }
}

/// **PR-2 M4-2a** (Gate #2): HTTP status + body for a
/// [`RuntimeSendError`] surfaced immediately from
/// `runtime.try_send(...)`. Distinct from the typed
/// `SendError` / `AckError` returned via the reply oneshot —
/// this is the pre-dispatch, immediate-Err surface.
#[allow(dead_code)] // M4-2b wires callers
pub fn runtime_send_error_to_http(err: &RuntimeSendError) -> HttpErrorShape {
    match classify_runtime_send_error(err) {
        RuntimeSendClassification::BackpressureOrShutdown => {
            HttpErrorShape::new(503, "backpressure or shutting down")
        }
        RuntimeSendClassification::IngressBypass => {
            HttpErrorShape::new(400, "empty recipient (ingress bypass)")
        }
        RuntimeSendClassification::Internal => {
            HttpErrorShape::new(500, "worker id invariant")
        }
    }
}

// ─── M4-2a: dispatch outcome shape ───────────────────────────────────

/// **PR-2 M4-2a**: unified outcome the Send/Ack dispatch
/// helpers return. Wraps the four disjoint outcomes a
/// `runtime.try_send(...) + reply_rx.await` roundtrip can
/// produce:
///
/// * `Ok(T)` — reply oneshot returned `Ok(outcome)`.
/// * `TypedErr(E)` — reply oneshot returned `Err(E)` (typed
///   business error, e.g. `SendError::UnsealedSenderRejected`).
/// * `Immediate(RuntimeSendError)` — `try_send` itself failed
///   pre-dispatch.
/// * `Timeout` — the reply oneshot didn't land within the
///   caller's bounded deadline (Gate #2: bounded reply
///   timeout).
/// * `Dropped` — the reply oneshot's sender was dropped
///   without sending. Should be impossible in production;
///   surfaced as an invariant-level 500.
#[derive(Debug)]
#[allow(dead_code)] // M4-2b wires callers
pub enum DispatchOutcome<T, E> {
    Ok(T),
    TypedErr(E),
    Immediate(RuntimeSendError),
    Timeout,
    Dropped,
}

// ─── M4-2a: read-only poll helper (Gate #5 preview) ──────────────────

/// **PR-2 M4-2a** (Gate #5 preview): read-only version of
/// today's `rest_fallback::drain_eligible`. The M4-2b atomic
/// cutover replaces the current `drain_eligible` (which
/// mutates via `queue.retain(|e| !e.is_expired())`) with a
/// call to this helper; TTL expiry moves to
/// `runtime.try_send(RestOp::Sweep)` (M4-3).
///
/// This helper is NOT called by any route in M4-2a — routes
/// continue to invoke `drain_eligible` unchanged. Landing it
/// here means the M4-2b diff is a single-line swap at each
/// caller.
///
/// Contract: given a snapshot slice of a recipient's queue,
/// return the same `Vec<PollEnvelope>` + `more: bool` shape
/// `drain_eligible` returns — but without touching the
/// underlying storage. Expired records are filtered out of
/// the returned batch; they remain in `queue` until sweep
/// removes them.
#[allow(dead_code)] // M4-2b wires callers
pub fn read_eligible_from_snapshot(
    queue: &[crate::rest_fallback::RestEnvelope],
    since_seq: u64,
    now_epoch_secs: u64,
    max_envelopes: usize,
) -> Option<(Vec<crate::rest_fallback::PollEnvelope>, bool)> {
    let eligible: Vec<&crate::rest_fallback::RestEnvelope> = queue
        .iter()
        .filter(|e| e.expires_at > now_epoch_secs) // not-yet-expired
        .filter(|e| e.seq > since_seq)
        .collect();
    if eligible.is_empty() {
        return None;
    }
    let more = eligible.len() > max_envelopes;
    let batch: Vec<crate::rest_fallback::PollEnvelope> = eligible
        .into_iter()
        .take(max_envelopes)
        .map(|e| crate::rest_fallback::PollEnvelope {
            id: e.id.clone(),
            from: e.from.clone(),
            sealed_sender: e.sealed_sender.clone(),
            payload: e.payload.clone(),
            sequence_ts: e.sequence_ts,
            seq: e.seq,
            seq_mac: e.seq_mac.clone(),
        })
        .collect();
    Some((batch, more))
}

// ─── M4-2a: WS ack frame builder (Gate #3) ───────────────────────────

/// **PR-2 M4-2a** (Gate #3): the pre-M4 WS ack frame that the
/// live-delivery / queued paths emit today. Preserved
/// byte-identically for the M4-2b migration so no client
/// protocol change is visible.
///
/// Existing wire shape (`routes.rs:1187-1195` today):
/// ```json
/// {"type":"ack","messageId":"<id>","status":"delivered"|"relayed"}
/// ```
///
/// `WsAckStatus::Delivered` — the recipient mpsc send
/// succeeded (immediate live delivery landed).
/// `WsAckStatus::Relayed` — the persist succeeded but no
/// live client; the envelope is queued for later poll.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)] // M4-2b wires callers
pub enum WsAckStatus {
    Delivered,
    Relayed,
}

impl WsAckStatus {
    #[inline]
    #[allow(dead_code)]
    fn as_wire_str(&self) -> &'static str {
        match self {
            WsAckStatus::Delivered => "delivered",
            WsAckStatus::Relayed => "relayed",
        }
    }
}

/// **PR-2 M4-2a** (Gate #3): build the WS ack JSON frame the
/// send handler returns to the sender. Returned as a
/// `serde_json::Value` so the caller can `.to_string()` it
/// directly onto the WS sink.
#[allow(dead_code)] // M4-2b wires callers
pub fn build_ws_ack_frame(message_id: &str, status: WsAckStatus) -> serde_json::Value {
    serde_json::json!({
        "type": "ack",
        "messageId": message_id,
        "status": status.as_wire_str(),
    })
}

/// **PR-2 M4-2b round-1 REDLINE P1-6**: exhaustive mapping
/// from a typed [`SendError`] returned via the reply oneshot
/// to a [`WsErrorKind`] the WS handler emits. Prior shape
/// collapsed every typed error to `WsErrorKind::Internal`,
/// hiding client-side validation faults (empty
/// sealed_sender, divergent-body 409) behind a generic
/// server-error surface.
#[allow(dead_code)] // routes.rs wires callers
pub fn send_error_to_ws_kind(err: &SendError) -> WsErrorKind {
    use SendError::*;
    match err {
        // Client-side validation issues: the sender should
        // learn "your request was structurally bad".
        UnsealedSenderRejected | EnvelopeIdReusedWithDivergentBody { .. } => {
            WsErrorKind::Validation
        }
        // Capacity pressure — same slug as the immediate
        // backpressure surface so ops dashboards aggregate
        // cleanly.
        PerRecipientQueueFull { .. } | CapacityExceeded(_) => WsErrorKind::Backpressure,
        // Internal invariant / infra failures.
        ShardMismatch { .. }
        | Persistence(_)
        | Serialize { .. }
        | SeqSaturation { .. }
        | BootGenerationOutOfRange { .. }
        | SeqMacCompute(_)
        | Internal(_) => WsErrorKind::Internal,
    }
}

/// **PR-2 M4-2b round-1 REDLINE P1-5**: exhaustive mapping
/// from a typed [`AckError`] to a [`WsErrorKind`] for the WS
/// ack-deliver handler. Prior shape ignored the reply
/// entirely.
#[allow(dead_code)] // routes.rs wires callers
pub fn ack_error_to_ws_kind(err: &AckError) -> WsErrorKind {
    use AckError::*;
    match err {
        ShardMismatch { .. }
        | Persistence(_)
        | Serialize { .. }
        | SystemClockBeforeEpoch
        | DedupHorizonOverflow { .. }
        | Internal(_) => WsErrorKind::Internal,
    }
}

/// **PR-2 M4-2a** (Gate #3, error branch): typed `kind`
/// slug for the WS error frame. Round-1 REDLINE fix
/// (architect P2): the round-0 shape accepted
/// `kind: &'static str`, so a typo like `"backpresure"`
/// would compile silently and reach clients. Making the
/// argument an enum forces the compiler to enumerate the
/// wire slug at exactly one site (`WsErrorKind::as_wire_str`).
///
/// Variants map 1:1 to the failure modes an M4-2b WS
/// handler can produce:
///   * `Backpressure` — worker mpsc full or pool draining
///     ([`RuntimeSendClassification::BackpressureOrShutdown`]).
///   * `IngressBypass` — empty recipient
///     ([`RuntimeSendClassification::IngressBypass`]). The
///     process will exit shortly via the fatal subscriber;
///     the frame is a courtesy signal to the peer.
///   * `Timeout` — the bounded reply-oneshot deadline
///     elapsed before `runtime` produced an outcome
///     ([`DispatchOutcome::Timeout`]).
///   * `Internal` — dropped reply oneshot or
///     `WorkerIdOutOfBounds`. Should not be reachable in
///     production — surfaced so the peer sees the shape.
///   * `Validation` — handler-layer rejection (e.g., empty
///     `sealed_sender`, Gate #4) BEFORE dispatch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)] // M4-2b wires callers
pub enum WsErrorKind {
    Backpressure,
    IngressBypass,
    Timeout,
    Internal,
    Validation,
}

impl WsErrorKind {
    /// Wire-slug rendering. Exhaustive `match` — a new
    /// variant added without a case here fails to compile.
    #[inline]
    #[allow(dead_code)]
    pub fn as_wire_str(&self) -> &'static str {
        match self {
            WsErrorKind::Backpressure => "backpressure",
            WsErrorKind::IngressBypass => "ingress_bypass",
            WsErrorKind::Timeout => "timeout",
            WsErrorKind::Internal => "internal",
            WsErrorKind::Validation => "validation",
        }
    }
}

/// **PR-2 M4-2a** (Gate #3, error branch): build the WS
/// error frame. Shape is a NEW frame type (`"error"`), not
/// the existing `"ack"` frame — clients unaware of the new
/// type simply ignore it.
///
/// ```json
/// {"type":"error","messageId":"<id>","kind":"<slug>"}
/// ```
///
/// Kinds mirror the REST-side classification so ops
/// dashboards can grep both transports the same way.
#[allow(dead_code)] // M4-2b wires callers
pub fn build_ws_error_frame(
    message_id: &str,
    kind: WsErrorKind,
) -> serde_json::Value {
    serde_json::json!({
        "type": "error",
        "messageId": message_id,
        "kind": kind.as_wire_str(),
    })
}

// ─── M4-2a: REST send response builder (Gate #3) ─────────────────────

/// **PR-2 M4-2a** (Gate #3): the pre-M4 REST `/relay/send`
/// contract distinguished a fresh commit (**201 Created** +
/// `{"ok":1}`) from an idempotency-cache replay (**200 OK** +
/// cached `{"ok":1}`). M4-2b's migrated handler preserves
/// both semantics. This builder encapsulates the fresh-vs-
/// replay decision so future edits to either status code
/// touch one place.
///
/// `RestSendResponseKind::Fresh` — first successful commit.
/// M4-2b handler ALSO calls `IdempotencyCache::put(...)` with
/// this result so a subsequent identical send hits the
/// replay branch.
/// `RestSendResponseKind::Replay` — cache hit on identical
/// `(idem_key, body_hash)`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)] // M4-2b wires callers
pub enum RestSendResponseKind {
    Fresh,
    Replay,
}

/// **PR-2 M4-2a** (Gate #3): return the correct (status,
/// body) pair for a REST send success. Body is fixed
/// `{"ok":1}` in both variants — status is the sole
/// differentiator, matching the pre-M4 wire contract.
#[allow(dead_code)] // M4-2b wires callers
pub fn rest_send_success_response(kind: RestSendResponseKind) -> (u16, &'static str) {
    match kind {
        RestSendResponseKind::Fresh => (201, "{\"ok\":1}"),
        RestSendResponseKind::Replay => (200, "{\"ok\":1}"),
    }
}

// ─── M4-2a: fatal subscriber helper (Gate #6) ────────────────────────

/// **PR-2 M4-2a** (Gate #6): the fatal-subscriber's decision
/// step, extracted as a pure classifier. Runs on every
/// broadcast event the subscriber observes. The M4-2b
/// `main.rs` wiring spawns the subscriber task that calls this
/// on every recv() outcome; M4-2a lands only the classifier +
/// tests.
///
/// * `Ok(reason)` from `broadcast::Receiver::recv()` — a
///   fatal was published. Classify and emit `FATAL:` + exit
///   with the code from `fatal_reason_to_exit_code(&reason)`
///   (always `1` under the locked M4 contract).
/// * `Err(Lagged(n))` — the subscriber missed `n` fatals
///   because the channel filled before it could poll. Since
///   ANY missed fatal is unacceptable while `Running`, this
///   MUST also emit `FATAL:` + exit(1). The
///   `subscriber_state` argument lets tests inject the
///   pool-state check without spawning a real runtime.
/// * `Err(Closed)` while `subscriber_state == Running` — the
///   fatal channel was dropped without the pool moving to
///   `Closing` first. Impossible under production wiring
///   (main.rs owns the sender for the whole process
///   lifetime); observing it means the wiring itself broke.
///   Emit `FATAL:` + exit(1).
/// * `Err(Closed)` while `subscriber_state != Running` — the
///   pool is already draining or drained. Clean exit
///   (`None` returned from this classifier — the subscriber
///   task returns normally).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)] // M4-2b spawns the subscriber
pub enum FatalSubscriberAction {
    /// Emit FATAL + exit(1). The subscriber MUST perform this
    /// action synchronously — the process is dead once we
    /// observe any of these conditions.
    EmitFatalAndExit,
    /// Clean shutdown observed — subscriber loop returns
    /// normally.
    CleanExit,
}

/// **PR-2 M4-2a** (Gate #6): classification of a
/// `broadcast::Receiver::recv()` result inside the fatal
/// subscriber. Pure fn — tests exercise every branch without
/// running a real broadcast channel.
///
/// `is_pool_running` — snapshot of `runtime.state_kind() ==
/// PoolStateKind::Running` taken by the subscriber right
/// before / after the recv. Test callers pass `true` /
/// `false` directly.
#[allow(dead_code)] // M4-2b spawns the subscriber
pub fn classify_fatal_subscriber_event(
    event: &FatalSubscriberEvent,
    is_pool_running: bool,
) -> FatalSubscriberAction {
    match event {
        FatalSubscriberEvent::FatalReceived => FatalSubscriberAction::EmitFatalAndExit,
        FatalSubscriberEvent::Lagged => FatalSubscriberAction::EmitFatalAndExit,
        FatalSubscriberEvent::Closed => {
            if is_pool_running {
                FatalSubscriberAction::EmitFatalAndExit
            } else {
                FatalSubscriberAction::CleanExit
            }
        }
    }
}

/// **PR-2 M4-2a** (Gate #6): the three shapes a fatal-
/// subscriber broadcast `recv()` can take. Broken out from
/// `tokio::sync::broadcast::error::RecvError` so
/// `classify_fatal_subscriber_event` stays a pure branch on
/// a domain-typed input.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)] // M4-2b spawns the subscriber
pub enum FatalSubscriberEvent {
    /// `Ok(FatalReason)` — a real fatal landed.
    FatalReceived,
    /// `Err(Lagged(n))` — buffer overflowed before the
    /// subscriber polled. Any missed fatal is fatal.
    Lagged,
    /// `Err(Closed)` — the sender dropped. Whether this is
    /// fatal depends on whether the pool was still Running.
    Closed,
}

// ─── Tests ───────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capacity_ledger::CapacityError;

    // ─── fatal_reason_to_exit_code ────────────────────────────

    #[test]
    fn fatal_reason_worker_panic_maps_to_exit_1() {
        let r = FatalReason::WorkerPanic {
            worker_id: 5,
            panic_payload: "boom".into(),
        };
        assert_eq!(fatal_reason_to_exit_code(&r), 1);
    }

    #[test]
    fn fatal_reason_worker_unexpected_exit_maps_to_exit_1() {
        let r = FatalReason::WorkerUnexpectedExit { worker_id: 1 };
        assert_eq!(fatal_reason_to_exit_code(&r), 1);
    }

    #[test]
    fn fatal_reason_shard_routing_maps_to_exit_1() {
        let r = FatalReason::ShardRoutingInvariantViolation {
            worker_id: 2,
            expected_worker: 7,
        };
        assert_eq!(fatal_reason_to_exit_code(&r), 1);
    }

    #[test]
    fn fatal_reason_unsupported_operation_maps_to_exit_1() {
        let r = FatalReason::UnsupportedOperation {
            worker_id: 0,
            op_kind: "sweep",
        };
        assert_eq!(fatal_reason_to_exit_code(&r), 1);
    }

    #[test]
    fn fatal_reason_seq_space_exhausted_maps_to_exit_1() {
        let r = FatalReason::SeqSpaceExhausted {
            worker_id: 3,
            counter: u64::MAX,
        };
        assert_eq!(fatal_reason_to_exit_code(&r), 1);
    }

    #[test]
    fn fatal_reason_seq_namespace_invalid_maps_to_exit_1() {
        let r = FatalReason::SeqNamespaceInvalid {
            worker_id: 4,
            generation: 1 << 24,
        };
        assert_eq!(fatal_reason_to_exit_code(&r), 1);
    }

    #[test]
    fn fatal_reason_ingress_bypass_maps_to_exit_1() {
        let r = FatalReason::IngressBypassAtBoundary {
            at: "test-site",
        };
        assert_eq!(fatal_reason_to_exit_code(&r), 1);
    }

    #[test]
    fn fatal_reason_active_index_invariant_maps_to_exit_1() {
        let r = FatalReason::ActiveIndexInvariantViolation {
            worker_id: 8,
            recipient: "rcpt".into(),
            id: "env".into(),
        };
        assert_eq!(fatal_reason_to_exit_code(&r), 1);
    }

    #[test]
    fn render_fatal_line_starts_with_fatal_prefix_and_includes_reason_debug() {
        let r = FatalReason::WorkerPanic {
            worker_id: 42,
            panic_payload: "boom".into(),
        };
        let line = render_fatal_line(&r);
        assert!(
            line.starts_with("FATAL: worker-runtime"),
            "line must be greppable by crash-dump collector: {line}"
        );
        assert!(line.contains("worker_id: 42"), "line must include reason payload: {line}");
    }

    // ─── send_error_to_http ───────────────────────────────────

    #[test]
    fn send_error_unsealed_sender_maps_to_400() {
        let shape = send_error_to_http(&SendError::UnsealedSenderRejected);
        assert_eq!(shape.status, 400);
        assert!(shape.message.contains("unsealed"));
    }

    #[test]
    fn send_error_divergent_body_maps_to_409() {
        let shape = send_error_to_http(&SendError::EnvelopeIdReusedWithDivergentBody {
            existing_body_hash: "a".repeat(64),
            incoming_body_hash: "b".repeat(64),
        });
        assert_eq!(shape.status, 409);
    }

    #[test]
    fn send_error_per_recipient_full_maps_to_429() {
        let shape = send_error_to_http(&SendError::PerRecipientQueueFull {
            observed: 500,
            cap: 500,
        });
        assert_eq!(shape.status, 429);
    }

    #[test]
    fn send_error_capacity_maps_to_429() {
        let shape = send_error_to_http(&SendError::CapacityExceeded(
            CapacityError::EnvelopeCapExceeded {
                observed: 100,
                cap: 100,
            },
        ));
        assert_eq!(shape.status, 429);
    }

    #[test]
    fn send_error_persistence_maps_to_500() {
        let shape = send_error_to_http(&SendError::Persistence(
            std::io::Error::new(std::io::ErrorKind::Other, "disk fail"),
        ));
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn send_error_seq_saturation_maps_to_500() {
        let shape = send_error_to_http(&SendError::SeqSaturation { counter: u64::MAX });
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn send_error_shard_mismatch_maps_to_500() {
        let shape = send_error_to_http(&SendError::ShardMismatch {
            expected_worker: 5,
            actual_worker: 3,
        });
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn send_error_internal_maps_to_500() {
        let shape = send_error_to_http(&SendError::Internal("test".into()));
        assert_eq!(shape.status, 500);
    }

    // M4-2a P2 cleanup: five variants the M4-1 round missed.
    // Exhaustive `match` in the mapper already prevented drift at
    // compile time; these tests pin the mapping so a future
    // status-code edit is caught by CI.

    #[test]
    fn send_error_serialize_maps_to_500() {
        let shape = send_error_to_http(&SendError::Serialize {
            observed_bytes: 999_999,
            cap_bytes: 65_536,
            source: "TooLarge{observed:999999,cap:65536}".into(),
        });
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn send_error_boot_generation_out_of_range_maps_to_500() {
        let shape = send_error_to_http(&SendError::BootGenerationOutOfRange {
            generation: (1u32 << 24) + 5,
        });
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn send_error_seq_mac_compute_maps_to_500() {
        let shape = send_error_to_http(&SendError::SeqMacCompute(
            crate::seq_mac::SeqMacComputeError::EnvelopeIdTooLong { len: 999_999 },
        ));
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn ack_error_serialize_maps_to_500() {
        let shape = ack_error_to_http(&AckError::Serialize {
            observed_bytes: 999_999,
            cap_bytes: 65_536,
            source: "TooLarge{observed:999999,cap:65536}".into(),
        });
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn ack_error_internal_maps_to_500() {
        let shape = ack_error_to_http(&AckError::Internal("test".into()));
        assert_eq!(shape.status, 500);
    }

    // ─── ack_outcome / ack_error ─────────────────────────────

    #[test]
    fn ack_outcome_acked_maps_to_200() {
        let shape = ack_outcome_to_http(&AckOutcome::Acked { seq: 42 });
        assert_eq!(shape.status, 200);
        assert_eq!(shape.body, "{\"ok\":1}");
    }

    #[test]
    fn ack_outcome_idempotent_maps_to_200() {
        let shape = ack_outcome_to_http(&AckOutcome::Idempotent { seq: 42 });
        assert_eq!(shape.status, 200);
    }

    #[test]
    fn ack_outcome_not_found_maps_to_200_preserving_pre_m4_shape() {
        // Pre-M4 handler returned 200 for any successful ack call,
        // including "envelope wasn't there". Preserved for cutover
        // safety; a future PR may distinguish this at the wire.
        let shape = ack_outcome_to_http(&AckOutcome::NotFound);
        assert_eq!(shape.status, 200);
    }

    #[test]
    fn ack_error_persistence_maps_to_500() {
        let shape = ack_error_to_http(&AckError::Persistence(std::io::Error::new(
            std::io::ErrorKind::Other,
            "disk fail",
        )));
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn ack_error_dedup_overflow_maps_to_500() {
        let shape = ack_error_to_http(&AckError::DedupHorizonOverflow {
            now: u64::MAX,
            horizon_secs: 172_800,
        });
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn ack_error_shard_mismatch_maps_to_500() {
        let shape = ack_error_to_http(&AckError::ShardMismatch {
            expected_worker: 5,
            actual_worker: 3,
        });
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn ack_error_clock_before_epoch_maps_to_500() {
        let shape = ack_error_to_http(&AckError::SystemClockBeforeEpoch);
        assert_eq!(shape.status, 500);
    }

    // ─── sweep_error_to_http ─────────────────────────────────

    #[test]
    fn sweep_error_persistence_maps_to_500() {
        let shape = sweep_error_to_http(&SweepError::Persistence(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            "gone",
        )));
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn sweep_error_shard_mismatch_maps_to_500() {
        let shape = sweep_error_to_http(&SweepError::ShardMismatch {
            expected_worker: 1,
            actual_worker: 2,
        });
        assert_eq!(shape.status, 500);
    }

    #[test]
    fn sweep_error_clock_before_epoch_maps_to_500() {
        let shape = sweep_error_to_http(&SweepError::SystemClockBeforeEpoch);
        assert_eq!(shape.status, 500);
    }

    // ─── build_send_candidate ────────────────────────────────

    #[test]
    fn build_send_candidate_derives_expires_at_from_now_plus_ttl() {
        let candidate = build_send_candidate(
            "env-1".into(),
            "sealed".into(),
            "payload".into(),
            1_720_000_000_000, // exact 60s boundary in ms
            1_720_000_000,
            3600,
        );
        assert_eq!(candidate.id, "env-1");
        assert_eq!(candidate.sealed_sender, "sealed");
        assert_eq!(candidate.payload, "payload");
        // 1_720_000_000_000 % 60_000 == 40_000, so quantized
        // down to 1_719_999_960_000.
        assert_eq!(candidate.sequence_ts, 1_719_999_960_000);
        assert_eq!(candidate.expires_at, 1_720_000_000 + 3600);
    }

    #[test]
    fn build_send_candidate_quantizes_sequence_ts_to_60s_boundary() {
        // Trek 2 Stage 1 Q5 lock preserved through M4-2b cutover:
        // sub-minute ms values round DOWN to the nearest 60s
        // boundary (60_000 ms).
        let cand = build_send_candidate(
            "env".into(),
            "s".into(),
            "p".into(),
            20_001, // 20.001s
            0,
            60,
        );
        assert_eq!(cand.sequence_ts, 0, "20_001 ms quantizes to 0");
        let cand2 = build_send_candidate(
            "env".into(),
            "s".into(),
            "p".into(),
            123_456, // 2m 3.456s
            0,
            60,
        );
        assert_eq!(
            cand2.sequence_ts, 120_000,
            "123_456 ms quantizes down to 120_000 (2 min)"
        );
    }

    #[test]
    fn build_send_candidate_saturates_on_u64_overflow_instead_of_panicking() {
        // Round-trip through the adapter must never panic even on
        // adversarial input — saturation is safer than wrap or
        // panic for a server-side derivation.
        let candidate = build_send_candidate(
            "env-max".into(),
            "s".into(),
            "p".into(),
            0,
            u64::MAX - 1,
            u64::MAX,
        );
        assert_eq!(candidate.expires_at, u64::MAX);
    }

    // ─── HttpErrorShape / HttpOkShape ────────────────────────

    #[test]
    fn http_error_shape_holds_status_and_message_verbatim() {
        let s = HttpErrorShape::new(418, "teapot");
        assert_eq!(s.status, 418);
        assert_eq!(s.message, "teapot");
    }

    #[test]
    fn http_ok_shape_holds_status_and_body_verbatim() {
        let s = HttpOkShape::new(201, "created");
        assert_eq!(s.status, 201);
        assert_eq!(s.body, "created");
    }

    // ─── M4-2a: RuntimeSendError translation ─────────────────

    #[test]
    fn runtime_send_full_classifies_as_backpressure() {
        assert_eq!(
            classify_runtime_send_error(&RuntimeSendError::Full),
            RuntimeSendClassification::BackpressureOrShutdown
        );
    }

    #[test]
    fn runtime_send_shutting_down_classifies_as_backpressure() {
        assert_eq!(
            classify_runtime_send_error(&RuntimeSendError::ShuttingDown),
            RuntimeSendClassification::BackpressureOrShutdown
        );
    }

    #[test]
    fn runtime_send_empty_recipient_classifies_as_ingress_bypass() {
        assert_eq!(
            classify_runtime_send_error(&RuntimeSendError::EmptyRecipient),
            RuntimeSendClassification::IngressBypass
        );
    }

    #[test]
    fn runtime_send_worker_id_oob_classifies_as_internal() {
        assert_eq!(
            classify_runtime_send_error(&RuntimeSendError::WorkerIdOutOfBounds {
                worker_id: 9999
            }),
            RuntimeSendClassification::Internal
        );
    }

    #[test]
    fn runtime_send_full_http_shape_is_503() {
        let s = runtime_send_error_to_http(&RuntimeSendError::Full);
        assert_eq!(s.status, 503);
    }

    #[test]
    fn runtime_send_shutting_down_http_shape_is_503() {
        let s = runtime_send_error_to_http(&RuntimeSendError::ShuttingDown);
        assert_eq!(s.status, 503);
    }

    #[test]
    fn runtime_send_empty_recipient_http_shape_is_400() {
        let s = runtime_send_error_to_http(&RuntimeSendError::EmptyRecipient);
        assert_eq!(s.status, 400);
    }

    #[test]
    fn runtime_send_worker_id_oob_http_shape_is_500() {
        let s = runtime_send_error_to_http(&RuntimeSendError::WorkerIdOutOfBounds {
            worker_id: 9999,
        });
        assert_eq!(s.status, 500);
    }

    // ─── M4-2a: read-only poll helper ────────────────────────

    fn synthetic_rest_envelope(
        id: &str,
        seq: u64,
        expires_at: u64,
    ) -> crate::rest_fallback::RestEnvelope {
        crate::rest_fallback::RestEnvelope {
            id: id.into(),
            from: String::new(),
            sealed_sender: format!("s-{id}"),
            payload: format!("p-{id}"),
            sequence_ts: 1_720_000_000_000,
            seq,
            expires_at,
            seq_mac: "a".repeat(64),
        }
    }

    #[test]
    fn read_eligible_empty_queue_returns_none() {
        let out = read_eligible_from_snapshot(&[], 0, 1_000, 1);
        assert!(out.is_none());
    }

    #[test]
    fn read_eligible_filters_expired_records_without_removing_them() {
        let queue = vec![
            synthetic_rest_envelope("live", 1, 2_000),
            synthetic_rest_envelope("expired", 2, 500), // < now
        ];
        let out = read_eligible_from_snapshot(&queue, 0, 1_000, 5);
        let (batch, more) = out.expect("live record must be returned");
        assert_eq!(batch.len(), 1);
        assert_eq!(batch[0].id, "live");
        assert!(!more);
        // The helper takes an immutable slice — original queue
        // stays byte-identical (proves the read-only property
        // structurally).
        assert_eq!(queue.len(), 2, "read-only helper must not remove expired");
    }

    #[test]
    fn read_eligible_respects_since_seq_filter() {
        let queue = vec![
            synthetic_rest_envelope("old", 1, 2_000),
            synthetic_rest_envelope("new", 5, 2_000),
        ];
        let out = read_eligible_from_snapshot(&queue, 3, 1_000, 5);
        let (batch, _) = out.expect("seq=5 exceeds since_seq=3");
        assert_eq!(batch.len(), 1);
        assert_eq!(batch[0].seq, 5);
    }

    #[test]
    fn read_eligible_respects_max_envelopes_cap_and_sets_more_true() {
        let queue = vec![
            synthetic_rest_envelope("a", 1, 2_000),
            synthetic_rest_envelope("b", 2, 2_000),
        ];
        let out = read_eligible_from_snapshot(&queue, 0, 1_000, 1);
        let (batch, more) = out.expect("at least one eligible");
        assert_eq!(batch.len(), 1);
        assert!(more, "more must be true when eligible > max_envelopes");
    }

    // ─── M4-2a: WS ack + error frames ────────────────────────

    #[test]
    fn ws_ack_delivered_frame_matches_pre_m4_wire_shape() {
        let v = build_ws_ack_frame("msg-1", WsAckStatus::Delivered);
        assert_eq!(v["type"], "ack");
        assert_eq!(v["messageId"], "msg-1");
        assert_eq!(v["status"], "delivered");
    }

    #[test]
    fn ws_ack_relayed_frame_matches_pre_m4_wire_shape() {
        let v = build_ws_ack_frame("msg-2", WsAckStatus::Relayed);
        assert_eq!(v["type"], "ack");
        assert_eq!(v["messageId"], "msg-2");
        assert_eq!(v["status"], "relayed");
    }

    #[test]
    fn ws_error_frame_uses_new_type_slug_not_ack_shape() {
        let v = build_ws_error_frame("msg-3", WsErrorKind::Backpressure);
        assert_eq!(v["type"], "error");
        assert_eq!(v["messageId"], "msg-3");
        assert_eq!(v["kind"], "backpressure");
        // Old clients that only understand `"ack"` see this as
        // an unknown frame type and skip it — no protocol break.
    }

    // ─── send_error_to_ws_kind (P1-6) ────────────────────────

    #[test]
    fn send_error_unsealed_sender_maps_to_ws_validation() {
        assert_eq!(
            send_error_to_ws_kind(&SendError::UnsealedSenderRejected),
            WsErrorKind::Validation
        );
    }

    #[test]
    fn send_error_divergent_body_maps_to_ws_validation() {
        assert_eq!(
            send_error_to_ws_kind(&SendError::EnvelopeIdReusedWithDivergentBody {
                existing_body_hash: "a".repeat(64),
                incoming_body_hash: "b".repeat(64),
            }),
            WsErrorKind::Validation
        );
    }

    #[test]
    fn send_error_per_recipient_full_maps_to_ws_backpressure() {
        assert_eq!(
            send_error_to_ws_kind(&SendError::PerRecipientQueueFull {
                observed: 500,
                cap: 500,
            }),
            WsErrorKind::Backpressure
        );
    }

    #[test]
    fn send_error_capacity_maps_to_ws_backpressure() {
        assert_eq!(
            send_error_to_ws_kind(&SendError::CapacityExceeded(
                CapacityError::EnvelopeCapExceeded { observed: 100, cap: 100 }
            )),
            WsErrorKind::Backpressure
        );
    }

    #[test]
    fn send_error_infra_variants_map_to_ws_internal() {
        for err in [
            SendError::Persistence(std::io::Error::new(std::io::ErrorKind::Other, "x")),
            SendError::SeqSaturation { counter: u64::MAX },
            SendError::ShardMismatch { expected_worker: 1, actual_worker: 2 },
            SendError::Internal("test".into()),
        ] {
            assert_eq!(
                send_error_to_ws_kind(&err),
                WsErrorKind::Internal,
                "{err:?}"
            );
        }
    }

    // ─── ack_error_to_ws_kind (P1-5) ─────────────────────────

    #[test]
    fn ack_error_variants_all_map_to_ws_internal() {
        for err in [
            AckError::Persistence(std::io::Error::new(std::io::ErrorKind::Other, "x")),
            AckError::SystemClockBeforeEpoch,
            AckError::DedupHorizonOverflow { now: 0, horizon_secs: 0 },
            AckError::ShardMismatch { expected_worker: 1, actual_worker: 2 },
            AckError::Internal("test".into()),
        ] {
            assert_eq!(ack_error_to_ws_kind(&err), WsErrorKind::Internal, "{err:?}");
        }
    }

    #[test]
    fn ws_error_kind_wire_slugs_are_stable_and_exhaustive() {
        // Pin every slug so a rename lands as a test failure
        // instead of a silent client-side incompatibility. The
        // exhaustive `match` inside `as_wire_str` guarantees a
        // new variant added without a case here fails to
        // compile.
        assert_eq!(WsErrorKind::Backpressure.as_wire_str(), "backpressure");
        assert_eq!(WsErrorKind::IngressBypass.as_wire_str(), "ingress_bypass");
        assert_eq!(WsErrorKind::Timeout.as_wire_str(), "timeout");
        assert_eq!(WsErrorKind::Internal.as_wire_str(), "internal");
        assert_eq!(WsErrorKind::Validation.as_wire_str(), "validation");
    }

    // ─── M4-2a: REST send fresh vs replay ────────────────────

    #[test]
    fn rest_send_fresh_response_is_201() {
        let (status, body) = rest_send_success_response(RestSendResponseKind::Fresh);
        assert_eq!(status, 201);
        assert_eq!(body, "{\"ok\":1}");
    }

    #[test]
    fn rest_send_replay_response_is_200() {
        let (status, body) = rest_send_success_response(RestSendResponseKind::Replay);
        assert_eq!(status, 200);
        assert_eq!(body, "{\"ok\":1}");
    }

    // ─── M4-2a: fatal subscriber classifier (Gate #6) ────────

    #[test]
    fn fatal_subscriber_fatal_received_always_emits_fatal_and_exits() {
        let out = classify_fatal_subscriber_event(
            &FatalSubscriberEvent::FatalReceived,
            true,
        );
        assert_eq!(out, FatalSubscriberAction::EmitFatalAndExit);
        // Same result whether pool is Running or not — a real
        // fatal is a real fatal.
        let out2 = classify_fatal_subscriber_event(
            &FatalSubscriberEvent::FatalReceived,
            false,
        );
        assert_eq!(out2, FatalSubscriberAction::EmitFatalAndExit);
    }

    #[test]
    fn fatal_subscriber_lagged_always_emits_fatal_and_exits() {
        // Missing a fatal is worse than no subscriber. Same
        // decision regardless of pool state.
        for is_running in [true, false] {
            let out = classify_fatal_subscriber_event(
                &FatalSubscriberEvent::Lagged,
                is_running,
            );
            assert_eq!(
                out,
                FatalSubscriberAction::EmitFatalAndExit,
                "Lagged must be fatal even with is_running={is_running}"
            );
        }
    }

    #[test]
    fn fatal_subscriber_closed_while_running_is_fatal() {
        let out = classify_fatal_subscriber_event(
            &FatalSubscriberEvent::Closed,
            true, // is_pool_running
        );
        assert_eq!(out, FatalSubscriberAction::EmitFatalAndExit);
    }

    #[test]
    fn fatal_subscriber_closed_while_draining_is_clean_exit() {
        let out = classify_fatal_subscriber_event(
            &FatalSubscriberEvent::Closed,
            false, // is_pool_running
        );
        assert_eq!(out, FatalSubscriberAction::CleanExit);
    }
}
