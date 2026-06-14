// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Smoke tests for the M2-B diag-shape diagnostic endpoint.
//!
//! Coverage scope is INTENTIONALLY narrow — these tests cover the safety
//! gate only, not the wire-level chunk-timing behaviour that the
//! diagnostic is built to measure. Wire-level timing is verifiable only
//! by pcap on a real hostile-carrier field run; the hermetic
//! `ServiceExt::oneshot` pattern collapses the streamed body into a
//! single received buffer regardless of the server-side
//! `tokio::time::sleep` pauses, so a unit test cannot distinguish
//! mono from chunked emission on the wire.
//!
//! What this file pins:
//!
//!   1. Route NOT registered when `diag_shape_enabled = false` → 404.
//!      (Default-off discipline matches the slow-post diagnostic
//!      precedent — `RELAY_ENABLE_DIAG_SHAPE` unset means route is
//!      structurally absent from the router, not just live-405.)
//!   2. Mono mode: 200 OK with body of exactly 4608 bytes.
//!      (Total-size invariant: M2-B holds size constant across modes
//!      so the diagnostic isolates only the time-spreading axis.)
//!   3. Chunked-100 mode: 200 OK with body of exactly 4608 bytes.
//!      (Same total-size invariant — chunked emission still produces
//!      4608 cumulative bytes; the 100 ms inter-chunk pauses elapse
//!      in real time during this test.)
//!   4. Unknown mode value: 404.
//!      (The path param `{mode}` is matched by the router but the
//!      handler rejects any value outside the four canonical modes.)

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use std::sync::Arc;
use tower::ServiceExt;

fn build_app(diag_shape_enabled: bool) -> axum::Router {
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.diag_shape_enabled = diag_shape_enabled;
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    phantom_relay::routes::router(state)
}

#[tokio::test]
async fn route_returns_404_when_flag_off() {
    let app = build_app(false);
    let req = Request::builder()
        .method("GET")
        .uri("/diag-shape/mono")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(
        resp.status(),
        StatusCode::NOT_FOUND,
        "route must be unregistered when diag_shape_enabled = false — \
         RELAY_ENABLE_DIAG_SHAPE unset means structurally absent route, \
         not a live endpoint returning 405 / 400.",
    );
}

#[tokio::test]
async fn mono_returns_200_with_4608_bytes() {
    let app = build_app(true);
    let req = Request::builder()
        .method("GET")
        .uri("/diag-shape/mono")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body_bytes = to_bytes(resp.into_body(), 8192).await.unwrap();
    assert_eq!(
        body_bytes.len(),
        4608,
        "mono body must be exactly 4608 bytes — total-size invariant matches \
         the production padded poll target so the diagnostic isolates only \
         the time-spreading axis.",
    );
}

#[tokio::test]
async fn chunked_100_returns_200_with_4608_bytes() {
    let app = build_app(true);
    let req = Request::builder()
        .method("GET")
        .uri("/diag-shape/chunked-100")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body_bytes = to_bytes(resp.into_body(), 8192).await.unwrap();
    assert_eq!(
        body_bytes.len(),
        4608,
        "chunked-100 body must be exactly 4608 bytes — total-size invariant \
         holds across modes; the 100 ms inter-chunk pauses do not change \
         the cumulative byte count.",
    );
}

#[tokio::test]
async fn unknown_mode_returns_404() {
    let app = build_app(true);
    let req = Request::builder()
        .method("GET")
        .uri("/diag-shape/unknown")
        .body(Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(
        resp.status(),
        StatusCode::NOT_FOUND,
        "the handler rejects any {{mode}} value outside the four canonical \
         modes (`mono` / `chunked-100` / `chunked-200` / `chunked-500`).",
    );
}
