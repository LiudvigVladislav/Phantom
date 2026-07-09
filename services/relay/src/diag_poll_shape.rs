// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! B2-K11 §5B — REST poll-shape echo diagnostic (`GET /diag/poll-shape`).
//!
//! Purpose. Discriminate whether the Tele2 LTE downlink drops the Phantom
//! `/relay/poll` response due to (a) raw HTTP/2 body byte size alone, or
//! (b) the specific *response shape* that Round 14 produces: padded 4608
//! bytes emitted as 4 × 1152 chunks with 300 ms `tokio::time::sleep`
//! pauses between chunks (mirrors `rest_fallback::build_chunked_poll_body_stream`).
//!
//! K11 v1 field probe on 2026-07-08 showed 24/24 body loss on Tele2 LTE
//! against production `/relay/poll` (HTTP/2, size ≈ 4608). A 20-hour-later
//! probe on 2026-07-09 against stunnel-served static files showed 21/21
//! PASS across 2048–16384 bytes — refuting a naive byte-cutoff and
//! pointing at shape, TLS fingerprint, or app request signature as the
//! surviving axes. This endpoint isolates shape from the other two.
//!
//! Contract.
//!
//!   * No authentication. Zero queue mutation. Zero cursor mutation. Zero
//!     delivery state. Read-only.
//!   * Route is only mounted when `state.config.diag_poll_shape_echo_enabled`
//!     is `true` (default `false`); when `false` the URL returns 404.
//!   * Query parameters are all optional. Malformed values fall through
//!     to the defaults — the endpoint never returns HTTP 400 for a bad
//!     parse. Same fallthrough discipline as `PollQuery.hold` in
//!     `rest_fallback.rs` (see K8 hold-override comment there).
//!   * All numeric parameters are clamped after parsing. Clamped bounds
//!     are pinned by unit tests below so a future refactor cannot
//!     silently widen them.
//!   * Response is `application/octet-stream` with an explicit
//!     `Content-Length: <clamped_size>` header. Body is streamed via
//!     `Body::from_stream(...)` in `chunk`-byte slices with `pause_ms`
//!     of `tokio::time::sleep` between chunks (no pause before first
//!     chunk, no pause after last chunk — matches
//!     `rest_fallback::build_chunked_poll_body_stream`).
//!   * Body bytes are deterministic: `byte[i] = (i % 256) as u8`. This
//!     lets the K11 operator confirm the exact tail bytes made it to the
//!     client by inspecting the last received byte value.
//!   * The route is mounted AFTER the 30 s `TimeoutLayer` (same as
//!     `/diag/slow-post`) so pathological parameter combinations
//!     (e.g. `size=16384 chunk=1 pause_ms=1000`) do not run into a 408
//!     mid-response. This is a diagnostic path; the operator sets both
//!     the env flag and the query parameters and is trusted to pick
//!     reasonable combinations.
//!
//! Locked design in `C:\temp\direct-wss-fix-family-2026-07-08\
//! k11-design-note.md` §5B.

use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::{
    body::{Body, Bytes},
    extract::{Query, State},
    http::StatusCode,
    response::Response,
};
use serde::Deserialize;

use crate::state::AppState;

// ── Clamp bounds ──────────────────────────────────────────────────────────

/// Default response size when `?size=` is absent or malformed.
/// Matches `rest_fallback::POLL_RESPONSE_CANONICAL_BYTES` — the Round 14
/// canonical padded poll body length — so the default invocation
/// reproduces the exact Phantom poll body size.
pub const POLL_SHAPE_SIZE_DEFAULT: usize = 4608;

/// Minimum accepted `size` after clamp. `0` would produce an empty body
/// which is uninteresting for a wire-shape probe; `1` is the smallest
/// meaningful lower bound.
pub const POLL_SHAPE_SIZE_MIN: usize = 1;

/// Maximum accepted `size` after clamp. `16384` is the ceiling of the
/// 2026-07-09 progressive size sweep that showed 21/21 PASS on Tele2 LTE
/// against stunnel-served static files; retaining that ceiling keeps
/// this endpoint in the same regime.
pub const POLL_SHAPE_SIZE_MAX: usize = 16_384;

/// Default chunk stride when `?chunk=` is absent or malformed.
/// Matches `rest_fallback::POLL_RESPONSE_CANONICAL_BYTES /
/// POLL_CHUNKED_FLUSH_CHUNK_COUNT = 4608 / 4 = 1152` — the Round 14
/// per-chunk byte count.
pub const POLL_SHAPE_CHUNK_DEFAULT: usize = 1152;

/// Minimum accepted `chunk` after clamp.
pub const POLL_SHAPE_CHUNK_MIN: usize = 1;

/// Maximum accepted `chunk` after clamp. `4096` lets `size=16384 chunk=4096`
/// produce a 4-chunk emit — matching the Round 14 chunk count at the max
/// size ceiling.
pub const POLL_SHAPE_CHUNK_MAX: usize = 4096;

/// Default inter-chunk pause when `?pause_ms=` is absent or malformed.
/// Matches `rest_fallback::POLL_CHUNKED_FLUSH_PAUSE_MS = 300`.
pub const POLL_SHAPE_PAUSE_MS_DEFAULT: u64 = 300;

/// Minimum accepted `pause_ms` after clamp — `0` is meaningful (single-flush
/// discriminator: same size, same chunking, no pauses).
pub const POLL_SHAPE_PAUSE_MS_MIN: u64 = 0;

/// Maximum accepted `pause_ms` after clamp. `1000` bounds a single
/// full-max invocation (`size=16384 chunk=1 pause_ms=1000`) to a
/// still-observable duration for the operator.
pub const POLL_SHAPE_PAUSE_MS_MAX: u64 = 1000;

// ── Query parameters ──────────────────────────────────────────────────────

/// Query parameters as `Option<String>` so that a malformed value
/// (`?size=abc`) does not make axum's `Query` extractor return HTTP 400
/// before the handler runs — the handler parses each field with
/// `.parse().ok()` and falls back to the default on failure. Matches the
/// K8 `PollQuery.hold` fallthrough discipline in `rest_fallback.rs`.
#[derive(Deserialize, Debug, Default)]
pub struct PollShapeQuery {
    pub size: Option<String>,
    pub chunk: Option<String>,
    pub pause_ms: Option<String>,
}

/// Parse-and-clamp the `?size=` query parameter. Returns
/// `POLL_SHAPE_SIZE_DEFAULT` on absent-or-malformed, otherwise clamps to
/// `[POLL_SHAPE_SIZE_MIN, POLL_SHAPE_SIZE_MAX]`.
pub fn clamp_size(raw: Option<&str>) -> usize {
    raw.and_then(|s| s.parse::<usize>().ok())
        .unwrap_or(POLL_SHAPE_SIZE_DEFAULT)
        .clamp(POLL_SHAPE_SIZE_MIN, POLL_SHAPE_SIZE_MAX)
}

/// Parse-and-clamp the `?chunk=` query parameter. Returns
/// `POLL_SHAPE_CHUNK_DEFAULT` on absent-or-malformed, otherwise clamps to
/// `[POLL_SHAPE_CHUNK_MIN, POLL_SHAPE_CHUNK_MAX]`.
pub fn clamp_chunk(raw: Option<&str>) -> usize {
    raw.and_then(|s| s.parse::<usize>().ok())
        .unwrap_or(POLL_SHAPE_CHUNK_DEFAULT)
        .clamp(POLL_SHAPE_CHUNK_MIN, POLL_SHAPE_CHUNK_MAX)
}

/// Parse-and-clamp the `?pause_ms=` query parameter. Returns
/// `POLL_SHAPE_PAUSE_MS_DEFAULT` on absent-or-malformed, otherwise clamps
/// to `[POLL_SHAPE_PAUSE_MS_MIN, POLL_SHAPE_PAUSE_MS_MAX]`.
pub fn clamp_pause_ms(raw: Option<&str>) -> u64 {
    raw.and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(POLL_SHAPE_PAUSE_MS_DEFAULT)
        .clamp(POLL_SHAPE_PAUSE_MS_MIN, POLL_SHAPE_PAUSE_MS_MAX)
}

// ── Deterministic body ────────────────────────────────────────────────────

/// Fill a `Vec<u8>` of length `size` with the deterministic pattern
/// `byte[i] = (i % 256) as u8`. The final byte of an `N`-byte response
/// is therefore `((N - 1) % 256) as u8`; the K11 operator can confirm
/// the last received byte value to prove the tail arrived intact.
pub fn build_deterministic_body(size: usize) -> Vec<u8> {
    (0..size).map(|i| (i % 256) as u8).collect()
}

// ── Streaming ─────────────────────────────────────────────────────────────

/// Build a chunked body stream. Yields the input `body` in `chunk_size`
/// byte slices; the last slice carries the remainder if `body.len() %
/// chunk_size != 0`. Emits `chunk_size` bytes at index 0 immediately (no
/// pre-pause) and inserts a `pause` `tokio::time::sleep` before each
/// subsequent chunk. No pause after the last chunk.
///
/// Design note: the sleep-BEFORE-chunk placement mirrors
/// `rest_fallback::build_chunked_poll_body_stream` so the wire-shape
/// timing produced by this diagnostic is byte-for-byte identical to the
/// Round 14 chunked poll response at the default parameter set
/// (`size=4608 chunk=1152 pause_ms=300` → 4 chunks with 3 × 300 ms
/// pauses between them).
///
/// The tracing `event="diag_poll_shape_echo_chunk"` log line is emitted
/// from inside this stream so it appears in the log at approximately
/// the wall-clock moment the chunk is passed to the body layer.
fn build_body_stream(
    body: Vec<u8>,
    chunk_size: usize,
    pause: Duration,
) -> impl futures_util::Stream<Item = Result<Bytes, std::io::Error>> + Send + 'static {
    let body = Arc::new(body);
    let total_bytes = body.len();

    futures_util::stream::unfold(0usize, move |offset| {
        let body = body.clone();
        async move {
            if offset >= total_bytes {
                return None;
            }
            if offset > 0 {
                tokio::time::sleep(pause).await;
            }
            let end = (offset + chunk_size).min(total_bytes);
            let chunk = Bytes::copy_from_slice(&body[offset..end]);
            let bytes_sent = chunk.len();
            let cumulative = end;
            let index = offset / chunk_size;
            tracing::info!(
                event       = "diag_poll_shape_echo_chunk",
                index       = index,
                bytes_sent  = bytes_sent,
                cumulative  = cumulative,
                "diag",
            );
            Some((Ok::<_, std::io::Error>(chunk), end))
        }
    })
}

// ── Handler ───────────────────────────────────────────────────────────────

/// `GET /diag/poll-shape` handler. See module docs for the full contract.
pub async fn diag_poll_shape_echo(
    State(state): State<Arc<AppState>>,
    Query(q): Query<PollShapeQuery>,
) -> Response {
    // Defence-in-depth: even though the route is only registered when
    // the flag is on (see `routes.rs`), the handler asserts the flag
    // here as well. If a future refactor accidentally exposes this
    // handler without the gate, it still fails closed.
    if !state.config.diag_poll_shape_echo_enabled {
        return Response::builder()
            .status(StatusCode::NOT_FOUND)
            .body(Body::empty())
            .expect("empty 404 body always builds");
    }

    let size = clamp_size(q.size.as_deref());
    let chunk = clamp_chunk(q.chunk.as_deref());
    let pause_ms = clamp_pause_ms(q.pause_ms.as_deref());

    let started = Instant::now();
    tracing::info!(
        event    = "diag_poll_shape_echo_start",
        size     = size,
        chunk    = chunk,
        pause_ms = pause_ms,
        "diag",
    );

    let body_bytes = build_deterministic_body(size);
    let done_size = size;
    let stream = build_body_stream(body_bytes, chunk, Duration::from_millis(pause_ms));

    // Wrap the stream so we can emit a `done` log line after the last
    // chunk is yielded. `futures_util::stream::StreamExt::chain` lets us
    // insert a terminal-side-effect step without altering the last real
    // chunk. The chained item yields an empty `Bytes` so the total body
    // length on the wire is unchanged (empty final chunk is a no-op for
    // `Content-Length`-framed HTTP/1.1 responses and for HTTP/2 DATA
    // frame accounting).
    let stream = futures_util::StreamExt::chain(
        stream,
        futures_util::stream::once(async move {
            let elapsed_ms = started.elapsed().as_millis() as u64;
            tracing::info!(
                event       = "diag_poll_shape_echo_done",
                total_bytes = done_size,
                elapsed_ms  = elapsed_ms,
                "diag",
            );
            Ok::<_, std::io::Error>(Bytes::new())
        }),
    );

    Response::builder()
        .status(StatusCode::OK)
        .header(axum::http::header::CONTENT_TYPE, "application/octet-stream")
        .header(axum::http::header::CONTENT_LENGTH, size.to_string())
        .body(Body::from_stream(stream))
        .expect("static header values always build")
}

// ── Unit tests ────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── clamp_size ────────────────────────────────────────────────────

    #[test]
    fn clamp_size_default_when_absent() {
        assert_eq!(clamp_size(None), POLL_SHAPE_SIZE_DEFAULT);
    }

    #[test]
    fn clamp_size_default_when_malformed() {
        assert_eq!(clamp_size(Some("abc")), POLL_SHAPE_SIZE_DEFAULT);
        assert_eq!(clamp_size(Some("")), POLL_SHAPE_SIZE_DEFAULT);
        assert_eq!(clamp_size(Some(" 4608 ")), POLL_SHAPE_SIZE_DEFAULT);
        assert_eq!(clamp_size(Some("-1")), POLL_SHAPE_SIZE_DEFAULT);
    }

    #[test]
    fn clamp_size_clamps_to_min() {
        assert_eq!(clamp_size(Some("0")), POLL_SHAPE_SIZE_MIN);
    }

    #[test]
    fn clamp_size_clamps_to_max() {
        assert_eq!(clamp_size(Some("99999")), POLL_SHAPE_SIZE_MAX);
    }

    #[test]
    fn clamp_size_accepts_in_range() {
        assert_eq!(clamp_size(Some("1")), 1);
        assert_eq!(clamp_size(Some("4608")), 4608);
        assert_eq!(clamp_size(Some("16384")), POLL_SHAPE_SIZE_MAX);
    }

    // ── clamp_chunk ───────────────────────────────────────────────────

    #[test]
    fn clamp_chunk_default_when_absent() {
        assert_eq!(clamp_chunk(None), POLL_SHAPE_CHUNK_DEFAULT);
    }

    #[test]
    fn clamp_chunk_default_when_malformed() {
        assert_eq!(clamp_chunk(Some("xyz")), POLL_SHAPE_CHUNK_DEFAULT);
    }

    #[test]
    fn clamp_chunk_clamps_bounds() {
        assert_eq!(clamp_chunk(Some("0")), POLL_SHAPE_CHUNK_MIN);
        assert_eq!(clamp_chunk(Some("99999")), POLL_SHAPE_CHUNK_MAX);
        assert_eq!(clamp_chunk(Some("1152")), 1152);
    }

    // ── clamp_pause_ms ────────────────────────────────────────────────

    #[test]
    fn clamp_pause_ms_default_when_absent() {
        assert_eq!(clamp_pause_ms(None), POLL_SHAPE_PAUSE_MS_DEFAULT);
    }

    #[test]
    fn clamp_pause_ms_default_when_malformed() {
        assert_eq!(clamp_pause_ms(Some("later")), POLL_SHAPE_PAUSE_MS_DEFAULT);
    }

    #[test]
    fn clamp_pause_ms_accepts_zero() {
        // `pause_ms=0` is the single-flush discriminator arm — must not
        // fall back to default.
        assert_eq!(clamp_pause_ms(Some("0")), 0);
    }

    #[test]
    fn clamp_pause_ms_clamps_to_max() {
        assert_eq!(clamp_pause_ms(Some("99999")), POLL_SHAPE_PAUSE_MS_MAX);
    }

    // ── build_deterministic_body ──────────────────────────────────────

    #[test]
    fn deterministic_body_pattern_is_modulo_256() {
        let body = build_deterministic_body(300);
        assert_eq!(body.len(), 300);
        for (i, b) in body.iter().enumerate() {
            assert_eq!(*b, (i % 256) as u8, "byte {} mismatch", i);
        }
    }

    #[test]
    fn deterministic_body_of_4608_matches_expected_tail() {
        // The K11 operator uses the last-byte value to confirm the tail
        // arrived. Pin `((4608 - 1) % 256) = 255` so a future refactor
        // that changes the pattern function breaks this test loudly.
        let body = build_deterministic_body(POLL_SHAPE_SIZE_DEFAULT);
        assert_eq!(body.len(), POLL_SHAPE_SIZE_DEFAULT);
        assert_eq!(*body.last().unwrap(), 255);
    }

    #[test]
    fn deterministic_body_of_1_is_single_zero() {
        assert_eq!(build_deterministic_body(1), vec![0u8]);
    }

    #[test]
    fn deterministic_body_of_zero_is_empty() {
        // Direct call with `0` should produce an empty Vec; `clamp_size`
        // upstream prevents `size=0` from reaching the handler, but the
        // helper itself must not panic on `0` (defence-in-depth).
        assert!(build_deterministic_body(0).is_empty());
    }
}
