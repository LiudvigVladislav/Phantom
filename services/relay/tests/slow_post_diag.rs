// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Integration tests for the T2 slow-POST byte-threshold diagnostic.
//!
//! Coverage:
//!
//!   1. Route NOT registered when `slow_post_diag_enabled = false` → 404.
//!      (Default-off discipline per Vladislav 2026-06-06 hard gate B.)
//!   2. Missing `X-Phantom-Diag` header → 400 with `event=slow_post_aborted
//!      reason=missing_or_wrong_x_phantom_diag_header`.
//!      (Anti-stray-POST guard per Vladislav 2026-06-06 hard gate 4.)
//!   3. Wrong `Content-Type` → 400 with `event=slow_post_aborted
//!      reason=wrong_content_type`.
//!   4. Wrong `X-Phantom-Diag` value → 400 (header value must match exactly).
//!   5. Cap exceeded — body > 64 KB → 413 with `event=slow_post_aborted
//!      reason=cap_exceeded`.
//!   6. Happy path — small body with correct headers → 200 with
//!      `total_received` matching body size.
//!
//! Uses the `tower::ServiceExt::oneshot` pattern (no TCP, hermetic).

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use serde_json::Value;
use std::sync::Arc;
use tower::ServiceExt;

fn build_app_with_diag_enabled(enabled: bool) -> axum::Router {
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.slow_post_diag_enabled = enabled;
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    phantom_relay::routes::router(state)
}

#[tokio::test]
async fn route_returns_404_when_flag_off() {
    let app = build_app_with_diag_enabled(false);
    let req = Request::builder()
        .method("POST")
        .uri("/diag/slow-post")
        .header("Content-Type", "application/octet-stream")
        .header("X-Phantom-Diag", "slow-post-v1")
        .body(Body::from(vec![0u8; 100]))
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(
        resp.status(),
        StatusCode::NOT_FOUND,
        "route must be unregistered when slow_post_diag_enabled = false (Vladislav hard gate B 2026-06-06)",
    );
}

#[tokio::test]
async fn missing_x_phantom_diag_header_returns_400() {
    let app = build_app_with_diag_enabled(true);
    let req = Request::builder()
        .method("POST")
        .uri("/diag/slow-post")
        .header("Content-Type", "application/octet-stream")
        // intentionally omitted: X-Phantom-Diag
        .body(Body::from(vec![0u8; 100]))
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn wrong_x_phantom_diag_value_returns_400() {
    let app = build_app_with_diag_enabled(true);
    let req = Request::builder()
        .method("POST")
        .uri("/diag/slow-post")
        .header("Content-Type", "application/octet-stream")
        .header("X-Phantom-Diag", "slow-post-v2") // wrong version suffix
        .body(Body::from(vec![0u8; 100]))
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn wrong_content_type_returns_400() {
    let app = build_app_with_diag_enabled(true);
    let req = Request::builder()
        .method("POST")
        .uri("/diag/slow-post")
        .header("Content-Type", "application/json") // wrong CT
        .header("X-Phantom-Diag", "slow-post-v1")
        .body(Body::from(vec![0u8; 100]))
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn cap_exceeded_returns_413() {
    let app = build_app_with_diag_enabled(true);
    // Build a body strictly above the 64 KB cap. The global
    // RequestBodyLimitLayer (~65 KB = config.max_payload_bytes + 1024 =
    // 65 536 + 1024 = 66 560) sits ABOVE the per-handler 64 KB cap, so
    // anything in (64 KB, 66 560 KB) hits the handler-level guard and
    // is rejected with 413 + `event=slow_post_aborted reason=cap_exceeded`.
    // 64 KB + 1 byte = 65 537.
    let body = vec![0u8; 64 * 1024 + 1];
    let req = Request::builder()
        .method("POST")
        .uri("/diag/slow-post")
        .header("Content-Type", "application/octet-stream")
        .header("X-Phantom-Diag", "slow-post-v1")
        .body(Body::from(body))
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::PAYLOAD_TOO_LARGE);
}

#[tokio::test]
async fn happy_path_returns_200_with_total_received() {
    let app = build_app_with_diag_enabled(true);
    // 8 chunks × 5120 bytes = 40 960 bytes (the production T2 body shape).
    // axum's Body::from(Vec<u8>) sends this as a single chunk to the
    // handler in unit tests — that's fine for the happy-path counter
    // verification. The field test exercises the multi-chunk streaming
    // path via the Android `T2SlowPostDiag` class which calls
    // `sink.flush()` per chunk over a real TCP socket.
    let body = vec![0u8; 8 * 5120];
    let req = Request::builder()
        .method("POST")
        .uri("/diag/slow-post")
        .header("Content-Type", "application/octet-stream")
        .header("X-Phantom-Diag", "slow-post-v1")
        .body(Body::from(body))
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    let body_bytes = to_bytes(resp.into_body(), 1024).await.unwrap();
    let json: Value = serde_json::from_slice(&body_bytes).unwrap();
    assert_eq!(
        json.get("total_received").and_then(Value::as_u64),
        Some(40_960),
        "relay must echo back the exact byte count it received — this is the verdict counter (Vladislav hard gate 2 2026-06-06)",
    );
    assert!(
        json.get("duration_ms").and_then(Value::as_u64).is_some(),
        "relay must report duration_ms",
    );
}
