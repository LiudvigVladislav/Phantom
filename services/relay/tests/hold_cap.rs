// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Trek 2 Stage 1.x Lock-4 — per-identity hold-cap + bounded hold-secs
//! contract tests.
//!
//! Covers:
//! - PER_IDENTITY_HOLD_CAP (3) concurrent holds for the same identity
//!   all succeed.
//! - The 4th concurrent hold returns 429 + Retry-After: 30.
//! - A hold for a different identity is NOT blocked by another
//!   identity's cap (per-identity isolation in the HoldSlot).
//! - The hold-count counter retires on poll completion (via the
//!   RAII `Drop` guard).
//! - The hold-count counter retires on handler cancellation — tests
//!   abort the spawned `JoinHandle` then AWAIT its completion before
//!   asserting `hold_count`, per the precise Drop contract.
//! - `MAX_POLL_HOLD_SECS_CAP` (480 s) clamps `RELAY_POLL_HOLD_SECS` at
//!   config-parse time, and `tokio::time::timeout` clamps the runtime
//!   per-hold duration as the second enforcement layer.

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use ed25519_dalek::{Signature, Signer, SigningKey};
use rand::rngs::OsRng;
use serde_json::{json, Value};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tower::ServiceExt;

// ── Helpers ──────────────────────────────────────────────────────────────────

fn build_app_with_hold(
    hold_secs: u32,
) -> (axum::Router, Arc<phantom_relay::state::AppState>) {
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.poll_hold_secs = hold_secs;
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    let router = phantom_relay::routes::router(Arc::clone(&state));
    (router, state)
}

fn identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        // Distinct seed range from other test files.
        *b = seed.wrapping_add(i as u8).wrapping_add(0xB0);
    }
    hex::encode(buf)
}

async fn fetch_challenge(app: axum::Router, identity: &str) -> (axum::Router, String) {
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/auth/challenge?identity={}", identity))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = serde_json::from_slice(&body).unwrap();
    let nonce_hex = v["nonce_hex"].as_str().unwrap().to_string();
    (app, nonce_hex)
}

async fn obtain_token(
    app: axum::Router,
    identity: &str,
    signing_kp: &SigningKey,
) -> (axum::Router, String) {
    let (app, nonce_hex) = fetch_challenge(app, identity).await;
    let nonce_vec = hex::decode(&nonce_hex).unwrap();
    let nonce_arr: [u8; 32] = nonce_vec.try_into().unwrap();
    let sig: Signature = signing_kp.sign(&nonce_arr);
    let body = json!({
        "identity":       identity,
        "signing_pubkey": hex::encode(signing_kp.verifying_key().to_bytes()),
        "challenge":      nonce_hex,
        "signature":      hex::encode(sig.to_bytes()),
    });
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/auth/session")
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let bytes = to_bytes(res.into_body(), 8192).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(status, StatusCode::OK, "session failed: {:?}", v);
    let token = v["token"].as_str().unwrap().to_string();
    (app, token)
}

/// Spawn a long-poll request (with opt-in) on the given identity. The
/// returned `JoinHandle` resolves to the response status when the
/// hold completes.
fn spawn_long_poll(
    app: axum::Router,
    token: String,
) -> tokio::task::JoinHandle<StatusCode> {
    tokio::spawn(async move {
        let res = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/relay/poll")
                    .header("authorization", format!("Bearer {}", token))
                    .header("x-phantom-long-poll", "1")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        res.status()
    })
}

// ── Contract tests ────────────────────────────────────────────────────────────

/// Three concurrent holds for the same identity all succeed — they sit
/// in the hold loop until either an envelope arrives or the timeout
/// fires. With `hold_secs = 1` they all return 200 after the timeout
/// re-check.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn three_concurrent_holds_for_same_identity_all_succeed() {
    let (app, _state) = build_app_with_hold(1);
    let identity = identity_hex(1);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let h1 = spawn_long_poll(app.clone(), token.clone());
    let h2 = spawn_long_poll(app.clone(), token.clone());
    let h3 = spawn_long_poll(app.clone(), token.clone());

    let s1 = h1.await.unwrap();
    let s2 = h2.await.unwrap();
    let s3 = h3.await.unwrap();
    assert_eq!(s1, StatusCode::OK);
    assert_eq!(s2, StatusCode::OK);
    assert_eq!(s3, StatusCode::OK);
}

/// The 4th concurrent hold for the same identity returns 429 with the
/// `Retry-After: 30` header set. Pins the per-identity hold-cap
/// contract and the response shape Stage 2B-A client backoff depends
/// on.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn fourth_concurrent_hold_returns_429_with_retry_after_30() {
    let (app, _state) = build_app_with_hold(2); // 2 s hold so racers stay in
    let identity = identity_hex(2);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    // Start three holds that will tie up the cap.
    let h1 = spawn_long_poll(app.clone(), token.clone());
    let h2 = spawn_long_poll(app.clone(), token.clone());
    let h3 = spawn_long_poll(app.clone(), token.clone());

    // Brief wait so the three racers enter the hold loop before the 4th
    // attempt. 300 ms is comfortably past auth + phase-1 + phase-3 on
    // slow CI runners.
    tokio::time::sleep(tokio::time::Duration::from_millis(300)).await;

    // 4th attempt — must receive 429 + Retry-After: 30.
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/relay/poll")
                .header("authorization", format!("Bearer {}", token))
                .header("x-phantom-long-poll", "1")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let retry_after = res
        .headers()
        .get("retry-after")
        .map(|v| v.to_str().unwrap_or("").to_string())
        .unwrap_or_default();
    let body_bytes = to_bytes(res.into_body(), 4096).await.unwrap();
    let body_value: Value = serde_json::from_slice(&body_bytes).unwrap();

    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(retry_after, "30");
    assert_eq!(body_value["error"], "too_many_concurrent_holds");

    // Let the three in-flight holds finish so the test doesn't leak.
    let _ = h1.await;
    let _ = h2.await;
    let _ = h3.await;
}

/// A hold for identity Y is NOT blocked by identity X's cap saturation.
/// Per-identity isolation in the HoldSlot map.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn concurrent_holds_for_identity_y_not_blocked_by_x_cap() {
    let (app, _state) = build_app_with_hold(2);
    let mut csprng = OsRng;

    let identity_x = identity_hex(3);
    let signing_x = SigningKey::generate(&mut csprng);
    let (app, token_x) = obtain_token(app, &identity_x, &signing_x).await;
    let identity_y = identity_hex(4);
    let signing_y = SigningKey::generate(&mut csprng);
    let (app, token_y) = obtain_token(app, &identity_y, &signing_y).await;

    // Saturate X's cap.
    let _hx1 = spawn_long_poll(app.clone(), token_x.clone());
    let _hx2 = spawn_long_poll(app.clone(), token_x.clone());
    let _hx3 = spawn_long_poll(app.clone(), token_x.clone());
    tokio::time::sleep(tokio::time::Duration::from_millis(300)).await;

    // Y must succeed even with X saturated.
    let hy = spawn_long_poll(app, token_y.clone());
    let sy = hy.await.unwrap();
    assert_eq!(
        sy,
        StatusCode::OK,
        "identity Y hold must NOT be blocked by identity X's saturated cap"
    );
}

/// After a hold completes (via the timeout path), the per-identity
/// counter retires — a subsequent 3-concurrent burst succeeds again.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn hold_count_retires_on_poll_completion() {
    let (app, state) = build_app_with_hold(1);
    let identity = identity_hex(5);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    // First burst — 3 concurrent.
    let h1 = spawn_long_poll(app.clone(), token.clone());
    let h2 = spawn_long_poll(app.clone(), token.clone());
    let h3 = spawn_long_poll(app.clone(), token.clone());
    let _ = h1.await;
    let _ = h2.await;
    let _ = h3.await;

    // hold_count must have decremented back to 0.
    let map = state.notifiers.read().await;
    let slot = map.get(&identity).expect("HoldSlot must remain in map");
    assert_eq!(
        slot.hold_count.load(Ordering::Acquire),
        0,
        "hold_count must retire to 0 after all three holds completed"
    );
    drop(map);

    // Second burst — must also succeed (cap not stuck at saturated).
    let h4 = spawn_long_poll(app.clone(), token.clone());
    let h5 = spawn_long_poll(app.clone(), token.clone());
    let h6 = spawn_long_poll(app.clone(), token.clone());
    let s4 = h4.await.unwrap();
    let s5 = h5.await.unwrap();
    let s6 = h6.await.unwrap();
    assert_eq!(s4, StatusCode::OK);
    assert_eq!(s5, StatusCode::OK);
    assert_eq!(s6, StatusCode::OK);
}

/// The per-identity counter retires on handler cancellation. Spawns a
/// hold, aborts it, awaits the JoinHandle completion (so tokio has
/// processed the cancellation and dropped the future), THEN asserts
/// the counter is 0. Crucially does NOT check the counter immediately
/// after abort() — that races with tokio's cancellation processing.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn hold_count_retires_on_client_disconnect() {
    // 5 s hold so the abort triggers before timeout.
    let (app, state) = build_app_with_hold(5);
    let identity = identity_hex(6);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let handle = spawn_long_poll(app, token);

    // Wait for the spawned task to enter the hold loop (past auth +
    // phase 1/3).
    tokio::time::sleep(tokio::time::Duration::from_millis(300)).await;

    // Counter must be 1 at this point.
    {
        let map = state.notifiers.read().await;
        let slot = map.get(&identity).expect("HoldSlot must exist");
        assert_eq!(
            slot.hold_count.load(Ordering::Acquire),
            1,
            "hold_count must be 1 while the spawned hold is in-flight"
        );
    }

    // Abort + AWAIT the JoinHandle so the future is observed dropped
    // by tokio before we read the counter. abort().await returning is
    // the synchronisation point per Lock-4 Drop semantics.
    handle.abort();
    let _join_err = handle.await; // returns Err(cancelled), but the
                                   // future has now been dropped — the
                                   // HoldGuard's Drop has run.

    // Counter must have retired to 0.
    let map = state.notifiers.read().await;
    let slot = map.get(&identity).expect("HoldSlot must still exist");
    assert_eq!(
        slot.hold_count.load(Ordering::Acquire),
        0,
        "hold_count must retire to 0 after the future is dropped"
    );
}

/// Constant sanity — pins the `MAX_POLL_HOLD_SECS_CAP` value at 480 s
/// (Tor circuit rotation alignment per Trek 2 mini-lock). Any future
/// change to the ceiling has to update this assertion, the
/// `poll_hold_loop` runtime clamp, and the `RelayConfig::from_env`
/// config-parse clamp — three load-bearing call-sites the test name
/// surfaces in `cargo test` output.
#[test]
fn max_poll_hold_secs_cap_constant_is_480() {
    assert_eq!(phantom_relay::rest_fallback::MAX_POLL_HOLD_SECS_CAP, 480);
}

/// Constant sanity — `PER_IDENTITY_HOLD_CAP` at 3. Pins the value the
/// hold-cap CAS loop checks against and the docstring on
/// `PER_IDENTITY_HOLD_CAP` claims.
#[test]
fn per_identity_hold_cap_constant_is_3() {
    assert_eq!(phantom_relay::rest_fallback::PER_IDENTITY_HOLD_CAP, 3);
}
