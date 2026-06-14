// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! M2-B diagnostic endpoint: tests whether deliberately pacing the
//! response body emission across multiple chunks survives a hostile
//! mobile carrier path that has been observed to silently drop bytes
//! past ~3986 in a single response body delivery.
//!
//! NOT a production behaviour. Gated by
//! [`crate::config::RelayConfig::diag_shape_enabled`]; when `false` the
//! route is not mounted and any request returns 404 from axum's default
//! not-found handler. Mirrors the
//! [`crate::config::RelayConfig::slow_post_diag_enabled`] env-gate
//! pattern.
//!
//! Wire-behaviour caveat: this handler REQUESTS the tokio runtime to
//! emit chunks with deliberate `tokio::time::sleep` pauses between
//! yields. Whether those pauses survive Caddy / TLS / the kernel TCP
//! stack onto the wire is precisely what we are measuring — that
//! wire-level behaviour MUST be proven by pcap on the VPS side and
//! cannot be inferred from this code alone.
//!
//! Total body size is held constant at 4608 bytes across all modes —
//! we test ONLY the time-spreading axis, not the size axis. The Round
//! 12 M1 evidence already characterized the size axis (1437-byte body
//! passes, ~3986-byte point in the same response stalls deterministically
//! on the target carrier). `Content-Length` is set explicitly so the
//! HTTP framing matches a real padded poll response and not chunked
//! transfer encoding (which would itself be a different wire-shape
//! test).

use axum::{
    body::{Body, Bytes},
    extract::Path,
    http::{HeaderValue, StatusCode},
    response::Response,
};
use futures_util::stream;
use std::time::Duration;

/// Total response body bytes. Matches the production padded poll target
/// (`POLL_RESPONSE_CANONICAL_BYTES = 4608` in `rest_fallback.rs`). Held
/// constant across all modes by design — this endpoint isolates the
/// time-spreading axis only.
const RESPONSE_TOTAL_BYTES: usize = 4608;

/// Number of chunks in the `chunked-*` modes. With `RESPONSE_TOTAL_BYTES
/// = 4608` and `CHUNK_COUNT = 4`, each chunk carries
/// `RESPONSE_TOTAL_BYTES / CHUNK_COUNT = 1152` bytes. 1152 bytes per
/// chunk is below the typical IPv4 MSS (~1460) so a chunk fits in a
/// single TCP segment without IP fragmentation.
const CHUNK_COUNT: usize = 4;

/// Bytes per chunk in the `chunked-*` modes. Computed at compile time
/// so the invariant `CHUNK_COUNT * CHUNK_BYTES == RESPONSE_TOTAL_BYTES`
/// is structurally enforced.
const CHUNK_BYTES: usize = RESPONSE_TOTAL_BYTES / CHUNK_COUNT;

/// Single byte repeated for the entire body. Content is irrelevant —
/// we measure only size and timing. `b'A'` chosen as a neutral
/// printable byte that is easy to spot in a hex dump.
const PAYLOAD_BYTE: u8 = b'A';

/// Handle a request to `/diag-shape/{mode}`.
///
/// Supported modes (matched as exact path segment values):
///
/// - `mono` — single buffered emission of all 4608 bytes. Runtime
///   decides how to write the body; no deliberate pacing.
/// - `chunked-100` — four 1152-byte chunks with 100 ms `sleep` between yields.
/// - `chunked-200` — four 1152-byte chunks with 200 ms `sleep` between yields.
/// - `chunked-500` — four 1152-byte chunks with 500 ms `sleep` between yields.
///
/// Any other value of `{mode}` returns 404.
pub async fn diag_shape_handler(Path(mode): Path<String>) -> Response {
    let pause_ms: Option<u64> = match mode.as_str() {
        "mono" => None,
        "chunked-100" => Some(100),
        "chunked-200" => Some(200),
        "chunked-500" => Some(500),
        _ => {
            return Response::builder()
                .status(StatusCode::NOT_FOUND)
                .body(Body::empty())
                .expect("static 404 response builds");
        }
    };

    tracing::info!(
        event = "diag_shape_response",
        mode = %mode,
        total_bytes = RESPONSE_TOTAL_BYTES,
        chunks = if pause_ms.is_some() { CHUNK_COUNT } else { 1 },
        pause_ms = pause_ms.unwrap_or(0),
        "diag-shape request",
    );

    let body = match pause_ms {
        None => Body::from(vec![PAYLOAD_BYTE; RESPONSE_TOTAL_BYTES]),
        Some(ms) => {
            let pause = Duration::from_millis(ms);
            let stream = stream::unfold(0usize, move |idx| async move {
                if idx >= CHUNK_COUNT {
                    return None;
                }
                if idx > 0 {
                    tokio::time::sleep(pause).await;
                }
                let chunk = Bytes::from(vec![PAYLOAD_BYTE; CHUNK_BYTES]);
                Some((Ok::<_, std::io::Error>(chunk), idx + 1))
            });
            Body::from_stream(stream)
        }
    };

    Response::builder()
        .status(StatusCode::OK)
        .header("content-type", "application/octet-stream")
        .header(
            "content-length",
            HeaderValue::from(RESPONSE_TOTAL_BYTES as u64),
        )
        .body(body)
        .expect("diag-shape response builds")
}
