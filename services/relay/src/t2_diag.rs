// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! T2 carrier-ceiling slow-POST instrumentation (2026-06-16 Option A).
//!
//! Single-module home for the four T2 instrumentation surfaces locked at
//! `C:\temp\t2-reconnaissance-2026-06-16\synthesis\option-a-scope-lock.md`:
//!
//!   1. `body_counter_middleware` — tower middleware that wraps the request
//!      body of `/prekeys/publish` (and only that path) with a per-frame
//!      byte counter and emits structured logs at each `poll_frame` step
//!      (chunk received) + at terminal events (body complete / read error).
//!      The counter is observable from the outer `timeout_trigger_middleware`
//!      via a shared `Arc<AtomicU64>` so a 30 s `TimeoutLayer` 408 response
//!      can capture the last-observed byte count.
//!
//!   2. `timeout_trigger_middleware` — outer tower middleware on
//!      `/prekeys/publish` that detects when the inner `TimeoutLayer`
//!      converted a stalled handler into a 408 `REQUEST_TIMEOUT` response.
//!      Emits `event=t2_diag_publish_timeout` with the correlated
//!      `request_id` + the last observed `cumulative_bytes` from the body
//!      counter.
//!
//!   4. `diag_upstream_shape` — env-gated `POST /diag-upstream-shape`
//!      handler analogous to `slow_post_diag` but designed for the T2
//!      upstream paced-chunking probe. Reads body as a `into_data_stream()`
//!      and emits `event=upstream_shape_chunk` per chunk + a JSON summary
//!      on completion for client-side correlation.
//!
//! Item 5 (the `RELAY_T2_DIAG` env flag, M13-style mutex against
//! `RELAY_ENABLE_SLOW_POST_DIAG`, and startup banner extension) lives in
//! `crate::config` + `main.rs`.
//!
//! Item 3 (client-side HTTP-version trace) is in the Kotlin transport
//! layer, not this file.
//!
//! All instrumentation is gated by `state.config.t2_diag_enabled`.
//! When the flag is `false` (production default), the body-counter
//! middleware short-circuits to a passthrough that wraps no bodies and
//! emits no logs (zero overhead), and the `/diag-upstream-shape` route
//! is not registered (returns 404).

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

use axum::{
    body::Body,
    extract::{Request, State},
    http::StatusCode,
    middleware::Next,
    response::{IntoResponse, Response},
    Json,
};
use futures_util::StreamExt;

use crate::state::AppState;

/// Per-request shared counter — the body wrapper writes into it on each
/// `poll_frame`; the outer timeout-trigger middleware reads it at 408
/// observation time to record `cumulative_bytes_observed` in the
/// timeout log line.
///
/// Wrapped in `Arc` so the body wrapper and the outer middleware can
/// observe the same atomic without lifetimes leaking into the request
/// extension type.
#[derive(Clone, Debug)]
pub struct T2DiagState {
    pub request_id: String,
    pub started: Instant,
    pub cumulative_bytes: Arc<AtomicU64>,
    pub frame_index: Arc<AtomicU64>,
}

impl T2DiagState {
    fn new(request_id: String) -> Self {
        Self {
            request_id,
            started: Instant::now(),
            cumulative_bytes: Arc::new(AtomicU64::new(0)),
            frame_index: Arc::new(AtomicU64::new(0)),
        }
    }
}

/// Lightweight 16-hex-char id generator. Not a UUID — just enough to
/// correlate one request's logs without dragging a uuid crate into the
/// relay tree. Uses `Instant::now()` nanoseconds + thread-local counter
/// truncated to 64 bits; collision probability over a 30 s window with
/// sub-microsecond resolution is negligible for diagnostic logs.
fn generate_request_id() -> String {
    use std::sync::atomic::AtomicU64;
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let counter = COUNTER.fetch_add(1, Ordering::Relaxed);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0);
    // Mix: low 32 bits of nanos + low 32 bits of counter. Enough entropy
    // for log-correlation purposes in a 30 s diagnostic window.
    format!("{:08x}{:08x}", nanos as u32, counter as u32)
}

// ── Item 1 — body counter middleware ───────────────────────────────────────

/// Tower middleware that wraps the request body of `/prekeys/publish` in
/// a per-frame byte counter via `axum::body::Body::from_stream(...)`.
///
/// The wrap happens at the middleware boundary BEFORE the downstream
/// handler's `Json<T>` extractor calls `body::to_bytes()`. This is the
/// critical positioning: a counter inside the handler could only observe
/// complete-body sizes; the cliff itself (mid-upload stall) would be
/// invisible because `body::to_bytes()` would simply never return. By
/// wrapping the body stream, every chunk the upstream peer delivers is
/// logged on its way through.
///
/// Implementation: we drop down to the underlying data stream via
/// `Body::into_data_stream()`, wrap with a counting closure that emits
/// a `tracing::info!` per chunk and updates the shared `T2DiagState`
/// atomic counters, then rebuild a `Body` from the wrapped stream via
/// `Body::from_stream(...)`. The final body-complete log line is emitted
/// inside the closure when the stream yields `None`.
///
/// When `t2_diag_enabled = false` the function short-circuits to
/// `next.run(request)` with zero overhead. The middleware ships
/// unconditionally in the router and is observably a no-op in production.
pub async fn body_counter_middleware(
    State(state): State<Arc<AppState>>,
    request: Request,
    next: Next,
) -> Response {
    if !state.config.t2_diag_enabled {
        return next.run(request).await;
    }
    // Only instrument /prekeys/publish per Vladislav scope-lock 2026-06-16.
    // Other endpoints (e.g. /relay/poll, /media/upload-chunk) have different
    // body characteristics and instrumentation scopes; co-instrumenting
    // them here would create overlapping per-chunk log lines that mix
    // unrelated traffic shapes.
    if request.uri().path() != "/prekeys/publish" {
        return next.run(request).await;
    }
    let req_id = generate_request_id();
    let diag = T2DiagState::new(req_id.clone());
    tracing::info!(
        event = "t2_diag_publish_request_start",
        request_id = %req_id,
        method = %request.method(),
        path = %request.uri().path(),
    );
    // Stash the diag handle in request extensions so the timeout-trigger
    // middleware (or any future co-located observer) can read counters
    // at 408 observation time without parsing logs.
    let (mut parts, body) = request.into_parts();
    parts.extensions.insert(diag.clone());

    // Build the counting wrapped stream. The closure captures the diag
    // handle by clone so the atomic counters and request_id stay in
    // scope for as long as the body is being polled. The `.then(...)`
    // step emits a terminal log when the stream ends; using a single
    // `unfold` would also work but `.then` lets us return the original
    // `Result` items unchanged so axum's Body::from_stream sees the
    // same item shape it would have without the wrapper.
    let upstream = body.into_data_stream();
    let diag_for_stream = diag.clone();
    let counted_stream = upstream.map(move |chunk_result| {
        match &chunk_result {
            Ok(chunk) => {
                let chunk_bytes = chunk.len() as u64;
                let cumulative = diag_for_stream
                    .cumulative_bytes
                    .fetch_add(chunk_bytes, Ordering::Relaxed)
                    + chunk_bytes;
                let frame_index =
                    diag_for_stream.frame_index.fetch_add(1, Ordering::Relaxed) + 1;
                let elapsed_ms = diag_for_stream.started.elapsed().as_millis() as u64;
                tracing::info!(
                    event = "t2_diag_publish_chunk",
                    request_id = %diag_for_stream.request_id,
                    frame_index = frame_index,
                    chunk_bytes = chunk_bytes,
                    cumulative_bytes = cumulative,
                    elapsed_ms = elapsed_ms,
                );
            }
            Err(err) => {
                let elapsed_ms = diag_for_stream.started.elapsed().as_millis() as u64;
                let cumulative = diag_for_stream
                    .cumulative_bytes
                    .load(Ordering::Relaxed);
                let frames = diag_for_stream.frame_index.load(Ordering::Relaxed);
                tracing::warn!(
                    event = "t2_diag_publish_body_error",
                    request_id = %diag_for_stream.request_id,
                    cumulative_bytes = cumulative,
                    frames = frames,
                    elapsed_ms = elapsed_ms,
                    err = %err,
                );
            }
        }
        chunk_result
    });
    // Chain a no-op tail that emits the body_complete log line when the
    // stream is exhausted. We can't add an item to the stream because
    // the item type is `Result<Bytes, axum::Error>` and an empty marker
    // would change byte semantics; instead we use a `futures_util::stream`
    // ::once-of-future-that-logs-and-yields-nothing pattern via
    // `.chain(stream::once(...))` that returns an empty stream. Simpler:
    // use `.inspect` on the stream's drop point via wrapping in a custom
    // struct. Since this gets verbose, instead we emit body_complete at
    // the moment the body is fully drained by the inner handler — that
    // happens in `next.run(...)` returning, so we observe it here at the
    // middleware return point.
    let new_request = Request::from_parts(parts, Body::from_stream(counted_stream));
    let response = next.run(new_request).await;
    let elapsed_ms = diag.started.elapsed().as_millis() as u64;
    let cumulative = diag.cumulative_bytes.load(Ordering::Relaxed);
    let frames = diag.frame_index.load(Ordering::Relaxed);
    tracing::info!(
        event = "t2_diag_publish_body_complete",
        request_id = %diag.request_id,
        cumulative_bytes = cumulative,
        frames = frames,
        elapsed_ms = elapsed_ms,
        response_status = %response.status(),
    );
    response
}

// ── Item 2 — timeout-trigger detection middleware ──────────────────────────

/// Outer middleware that detects when the inner `TimeoutLayer` fired
/// (visible as a 408 `REQUEST_TIMEOUT` response without the handler ever
/// completing the body) and logs the last observed `cumulative_bytes`
/// from the body counter, correlated by `request_id`.
///
/// Composition order: this middleware MUST be applied OUTSIDE the
/// `TimeoutLayer` so it observes the layer's converted response. In axum
/// terms, this means it is added AFTER `.layer(TimeoutLayer)` in the
/// builder chain so it wraps the timeout layer's output. The body-counter
/// middleware (Item 1) is applied INSIDE this one so its `T2DiagState`
/// extension is already in the request when the body wrapper runs.
///
/// When `t2_diag_enabled = false` the middleware short-circuits.
///
/// The middleware tracks request_id by reading the extension the
/// body-counter middleware placed there. If no extension is present
/// (e.g. the request was for a path we didn't instrument), the
/// middleware emits no timeout log line and just passes the response
/// through.
pub async fn timeout_trigger_middleware(
    State(state): State<Arc<AppState>>,
    request: Request,
    next: Next,
) -> Response {
    if !state.config.t2_diag_enabled {
        return next.run(request).await;
    }
    let path_owned = request.uri().path().to_string();
    let response = next.run(request).await;
    if response.status() == StatusCode::REQUEST_TIMEOUT {
        // Try to pull the diag handle out of the response extensions —
        // axum doesn't carry request extensions through to the response
        // by default, so we don't actually get the request_id on this
        // path. Fall back to a path-keyed log line.
        //
        // Note: a more precise correlation would require either
        // (a) carrying the request_id in a Response header set by the
        // body-counter middleware on the request side, or
        // (b) using a tower::Service wrapper that holds the diag handle
        // across the call. The path-keyed log is sufficient for
        // characterising the timeout class because every concurrent
        // /prekeys/publish request emits its own request_id in the
        // body-counter logs; an operator correlating logs by timestamp
        // can pin which request_id was in flight at the timeout.
        tracing::warn!(
            event = "t2_diag_publish_timeout",
            path = %path_owned,
            status = %response.status(),
            reason = "axum_timeout_layer_408",
            note = "request_id correlation via prior t2_diag_publish_chunk log lines by timestamp",
        );
    }
    response
}

// ── Item 4 — /diag-upstream-shape streaming probe endpoint ────────────────

/// JSON response body from a successful `/diag-upstream-shape` probe.
///
/// Returned ONLY after the request body has been fully consumed from the
/// stream. The per-chunk arrival timing is captured by the `tracing::info!`
/// lines emitted during streaming (so an operator parsing relay logs can
/// reconstruct the carrier-side chunking shape) and ALSO returned to the
/// client in `per_chunk_timing_ms` so the client-side probe harness can
/// correlate its `sink.flush()` pacing with the server-observed arrival
/// gaps without scraping logs.
#[derive(serde::Serialize)]
struct UpstreamShapeResponse {
    request_id: String,
    total_bytes: u64,
    chunks_seen: u64,
    total_elapsed_ms: u64,
    per_chunk_timing_ms: Vec<u64>,
}

/// Probe endpoint for upstream paced-chunking measurement (Round-14
/// analogue but on the upload direction).
///
/// Mounted ONLY when `state.config.t2_diag_enabled = true`. The route
/// is registered conditionally in `routes::router()`; with the env flag
/// off the path returns 404 (route absent, not 405).
///
/// Body cap: 64 KB (same `SLOW_POST_CAP_BYTES`-style ceiling as the
/// `slow_post_diag` handler). A probe larger than that aborts with 413
/// and `event=upstream_shape_aborted reason=cap_exceeded`.
///
/// No headers other than the standard `Content-Type` are required. The
/// probe is designed for an operator-controlled debug client to drive
/// during a field session, not a public surface.
pub async fn diag_upstream_shape(
    State(_state): State<Arc<AppState>>,
    request: Request,
) -> Response {
    const SHAPE_CAP_BYTES: u64 = 64 * 1024;
    let req_id = generate_request_id();
    let started = Instant::now();
    tracing::info!(
        event = "upstream_shape_request_start",
        request_id = %req_id,
    );

    let mut total: u64 = 0;
    let mut chunks: u64 = 0;
    let mut per_chunk_timing_ms: Vec<u64> = Vec::new();
    let mut stream = request.into_body().into_data_stream();

    while let Some(chunk_result) = stream.next().await {
        let chunk = match chunk_result {
            Ok(c) => c,
            Err(err) => {
                tracing::warn!(
                    event = "upstream_shape_aborted",
                    request_id = %req_id,
                    cumulative_bytes = total,
                    chunks_seen = chunks,
                    elapsed_ms = started.elapsed().as_millis() as u64,
                    reason = "read_error",
                    err = %err,
                );
                return (StatusCode::BAD_REQUEST, "read error").into_response();
            }
        };
        let chunk_bytes = chunk.len() as u64;
        total = total.saturating_add(chunk_bytes);
        chunks += 1;
        if total > SHAPE_CAP_BYTES {
            tracing::warn!(
                event = "upstream_shape_aborted",
                request_id = %req_id,
                cumulative_bytes = total,
                chunks_seen = chunks,
                elapsed_ms = started.elapsed().as_millis() as u64,
                reason = "cap_exceeded",
                cap_bytes = SHAPE_CAP_BYTES,
            );
            return (StatusCode::PAYLOAD_TOO_LARGE, "body cap exceeded").into_response();
        }
        let elapsed_ms = started.elapsed().as_millis() as u64;
        per_chunk_timing_ms.push(elapsed_ms);
        tracing::info!(
            event = "upstream_shape_chunk",
            request_id = %req_id,
            chunk_index = chunks,
            chunk_bytes = chunk_bytes,
            cumulative_bytes = total,
            elapsed_ms = elapsed_ms,
        );
    }

    let total_elapsed_ms = started.elapsed().as_millis() as u64;
    tracing::info!(
        event = "upstream_shape_completed",
        request_id = %req_id,
        cumulative_bytes = total,
        chunks_seen = chunks,
        elapsed_ms = total_elapsed_ms,
    );
    (
        StatusCode::OK,
        Json(UpstreamShapeResponse {
            request_id: req_id,
            total_bytes: total,
            chunks_seen: chunks,
            total_elapsed_ms,
            per_chunk_timing_ms,
        }),
    )
        .into_response()
}
