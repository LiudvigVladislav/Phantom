// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Integration tests for the B2-K11 §5B poll-shape echo diagnostic.
//!
//! Coverage:
//!
//!   1. Route NOT registered when `diag_poll_shape_echo_enabled = false`
//!      → 404. (Default-off "fails closed" discipline, same as
//!      `/diag/slow-post` / `/diag-upstream-shape`.)
//!   2. Enabled: default parameters → 200 with exact
//!      `POLL_SHAPE_SIZE_DEFAULT` bytes + `Content-Length` matches +
//!      `Content-Type: application/octet-stream`.
//!   3. Enabled: explicit `size` in-range → 200 with exact requested
//!      byte count.
//!   4. Enabled: `size` clamps to min when 0.
//!   5. Enabled: `size` clamps to max when above ceiling.
//!   6. Enabled: malformed `size` falls back to default (never 400).
//!   7. Enabled: `pause_ms=0 chunk=1152` full-flush arm — deterministic
//!      bytes match the (i % 256) pattern.
//!   8. Enabled: chunk larger than size — single chunk carries full body.
//!
//! All tests use the `tower::ServiceExt::oneshot` pattern (no TCP,
//! hermetic). We keep `pause_ms=0` on every enabled test to avoid slow
//! test runs; the streaming pause behaviour is covered by the pure-
//! helper unit test `deterministic_body_pattern_is_modulo_256` and by
//! the real-world field probe after deploy.

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use std::sync::Arc;
use tower::ServiceExt;

use phantom_relay::diag_poll_shape::{
    POLL_SHAPE_CHUNK_MAX, POLL_SHAPE_PAUSE_MS_MAX, POLL_SHAPE_SIZE_DEFAULT, POLL_SHAPE_SIZE_MAX,
    POLL_SHAPE_SIZE_MIN,
};

fn build_app_with_flag(enabled: bool) -> axum::Router {
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.diag_poll_shape_echo_enabled = enabled;
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    phantom_relay::routes::router(state)
}

async fn collect_body(resp: axum::response::Response) -> Vec<u8> {
    // 64 KB ceiling for tests (endpoint max is `POLL_SHAPE_SIZE_MAX =
    // 16 384`); use a generous cap so a future `size` widening does not
    // silently truncate the assertion.
    to_bytes(resp.into_body(), 64 * 1024)
        .await
        .unwrap()
        .to_vec()
}

// ── Flag-off ──────────────────────────────────────────────────────────────

#[tokio::test]
async fn route_returns_404_when_flag_off() {
    let app = build_app_with_flag(false);
    let req = Request::builder()
        .method("GET")
        .uri("/diag/poll-shape")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(
        resp.status(),
        StatusCode::NOT_FOUND,
        "route must be unregistered when diag_poll_shape_echo_enabled = false (fails-closed default)",
    );
}

#[tokio::test]
async fn route_returns_404_when_flag_off_even_with_params() {
    // Even with valid-looking query params, the URL must return 404
    // when the flag is off. This pins the "no side channel around the
    // flag" contract.
    let app = build_app_with_flag(false);
    let req = Request::builder()
        .method("GET")
        .uri("/diag/poll-shape?size=1024&chunk=256&pause_ms=0")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::NOT_FOUND);
}

// ── Flag-on happy paths ───────────────────────────────────────────────────

#[tokio::test]
async fn default_params_return_canonical_size() {
    let app = build_app_with_flag(true);
    // No query — endpoint applies POLL_SHAPE_SIZE_DEFAULT. Force
    // `pause_ms=0` in-URL to keep the test fast (default is 300 ms,
    // which would take ~900 ms across the 4-chunk default emit).
    let req = Request::builder()
        .method("GET")
        .uri("/diag/poll-shape?pause_ms=0")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
    let content_type = resp
        .headers()
        .get(axum::http::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    assert_eq!(content_type, "application/octet-stream");
    let content_length: usize = resp
        .headers()
        .get(axum::http::header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse().ok())
        .expect("Content-Length must be set");
    assert_eq!(content_length, POLL_SHAPE_SIZE_DEFAULT);

    let body = collect_body(resp).await;
    assert_eq!(body.len(), POLL_SHAPE_SIZE_DEFAULT);
    // Last byte must match the deterministic pattern — this is the
    // K11-operator confirmation invariant.
    assert_eq!(
        *body.last().unwrap(),
        ((POLL_SHAPE_SIZE_DEFAULT - 1) % 256) as u8,
    );
}

#[tokio::test]
async fn explicit_in_range_size_returns_that_size() {
    let app = build_app_with_flag(true);
    let req = Request::builder()
        .method("GET")
        .uri("/diag/poll-shape?size=2048&chunk=1152&pause_ms=0")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body = collect_body(resp).await;
    assert_eq!(body.len(), 2048);
    assert_eq!(*body.last().unwrap(), ((2048 - 1) % 256) as u8);
}

// ── Clamping ──────────────────────────────────────────────────────────────

#[tokio::test]
async fn size_zero_clamps_to_min() {
    let app = build_app_with_flag(true);
    let req = Request::builder()
        .method("GET")
        .uri("/diag/poll-shape?size=0&pause_ms=0")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body = collect_body(resp).await;
    assert_eq!(
        body.len(),
        POLL_SHAPE_SIZE_MIN,
        "size=0 must clamp UP to POLL_SHAPE_SIZE_MIN, never emit 0-byte body",
    );
}

#[tokio::test]
async fn size_above_ceiling_clamps_to_max() {
    let app = build_app_with_flag(true);
    let req = Request::builder()
        .method("GET")
        .uri("/diag/poll-shape?size=99999&pause_ms=0")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body = collect_body(resp).await;
    assert_eq!(
        body.len(),
        POLL_SHAPE_SIZE_MAX,
        "size must clamp DOWN to POLL_SHAPE_SIZE_MAX",
    );
}

#[tokio::test]
async fn malformed_size_falls_back_to_default_not_400() {
    // Same fallthrough discipline as K8's `?hold=` parse — malformed
    // values MUST NOT cause axum's Query extractor to return HTTP 400.
    // The handler parses each field with `.parse().ok()` on
    // `Option<String>` and applies the default on failure.
    let app = build_app_with_flag(true);
    let req = Request::builder()
        .method("GET")
        .uri("/diag/poll-shape?size=abc&chunk=xyz&pause_ms=0")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(
        resp.status(),
        StatusCode::OK,
        "malformed query params must fall through to defaults, not 400",
    );
    let body = collect_body(resp).await;
    // `chunk=xyz` fell back to POLL_SHAPE_CHUNK_DEFAULT = 1152;
    // `size=abc` fell back to POLL_SHAPE_SIZE_DEFAULT = 4608.
    assert_eq!(body.len(), POLL_SHAPE_SIZE_DEFAULT);
}

#[tokio::test]
async fn pause_ms_above_ceiling_clamps_but_still_serves() {
    // A `pause_ms` above the ceiling clamps down, but the request still
    // succeeds. Uses `size=chunk` so the emit is a single chunk with no
    // inter-chunk pauses, keeping the test fast even under a
    // clamped-max pause_ms.
    let app = build_app_with_flag(true);
    let req = Request::builder()
        .method("GET")
        .uri(format!(
            "/diag/poll-shape?size=1152&chunk=1152&pause_ms={}",
            POLL_SHAPE_PAUSE_MS_MAX + 1000
        ))
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body = collect_body(resp).await;
    assert_eq!(body.len(), 1152);
}

// ── Streaming shape ───────────────────────────────────────────────────────

#[tokio::test]
async fn chunk_larger_than_size_still_serves_exact_size() {
    // `chunk > size` collapses to a single chunk carrying the full
    // body — the stream's `end.min(total_bytes)` clamp handles this.
    let app = build_app_with_flag(true);
    let req = Request::builder()
        .method("GET")
        .uri(format!(
            "/diag/poll-shape?size=100&chunk={}&pause_ms=0",
            POLL_SHAPE_CHUNK_MAX
        ))
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body = collect_body(resp).await;
    assert_eq!(body.len(), 100);
    assert_eq!(*body.last().unwrap(), 99u8);
}

#[tokio::test]
async fn multi_chunk_body_matches_deterministic_pattern() {
    // `size=1200 chunk=300 pause_ms=0` — 4 chunks × 300 bytes with no
    // pauses. The reassembled body must match the byte-i-mod-256 pattern.
    let app = build_app_with_flag(true);
    let req = Request::builder()
        .method("GET")
        .uri("/diag/poll-shape?size=1200&chunk=300&pause_ms=0")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body = collect_body(resp).await;
    assert_eq!(body.len(), 1200);
    for (i, b) in body.iter().enumerate() {
        assert_eq!(
            *b,
            (i % 256) as u8,
            "byte {} of reassembled multi-chunk body must match (i % 256)",
            i
        );
    }
}
