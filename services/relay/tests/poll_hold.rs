// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Integration tests for Trek 2 Stage 1 long-poll backbone changes.
//!
//! Coverage (locked in `project_trek2_stage1_locks_2026_06_09.md`):
//!   * Q1 — `SessionResponse` carries `poll_hold_secs` field (default `0`)
//!     + backward-compat: an old-shaped struct without the field still
//!     deserializes a Stage 1 server's response.
//!   * Q2 — `/relay/ack-deliver` is rate-limited at 120/window and uses a
//!     SEPARATE bucket from `/relay/send` (a sender that filled the send
//!     bucket can still call ack-deliver; an ack-deliver flood does not
//!     touch the send bucket).
//!   * Q4 — `PollResponse` body is padded to EXACTLY
//!     `POLL_RESPONSE_CANONICAL_BYTES` (4608) regardless of whether
//!     envelopes are present, so empty + envelope-bearing responses are
//!     byte-indistinguishable on the wire.
//!   * Q5 — `sequence_ts` is quantized to the nearest 60-second boundary
//!     server-side before storage, regardless of what the client sent.
//!   * Q7 — kill switch: `poll_hold_secs=0` (default) makes `/relay/poll`
//!     return immediately, byte-padded but with no Notify entry created.
//!
//! Hold-loop wake-up via `tokio::sync::Notify` is exercised by the inline
//! unit tests in `state.rs` and indirectly via `rest_fallback.rs::tests`.
//! Driving the FULL HTTP handler with `tokio::time::pause()` requires a
//! custom `RelayConfig` builder; this file targets the wire contract and
//! E2E shape, where the deterministic path (`poll_hold_secs=0`) is the
//! one that ships to production by default.

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use ed25519_dalek::{Signature, Signer, SigningKey};
use phantom_relay::rest_fallback::{
    ACK_DELIVER_RATE_LIMIT_PER_WINDOW, POLL_RESPONSE_CANONICAL_BYTES,
};
use rand::rngs::OsRng;
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;
use tower::ServiceExt;

// ── Helpers (mirrored from rest_fallback_endpoints.rs) ───────────────────────

fn build_app() -> axum::Router {
    let cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    phantom_relay::routes::router(state)
}

fn identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        // Distinct seed range from other test files to avoid challenge-cache
        // cross-contamination (rest_fallback_endpoints uses 0xA0+, prekey
        // uses 20-25, this file uses 0xD0+).
        *b = seed.wrapping_add(i as u8).wrapping_add(0xD0);
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

async fn call_session(
    app: axum::Router,
    identity: &str,
    signing_kp: &SigningKey,
    nonce_hex: &str,
) -> (axum::Router, StatusCode, Value) {
    let nonce_vec = hex::decode(nonce_hex).unwrap();
    let nonce_arr: [u8; 32] = nonce_vec.try_into().unwrap();
    let sig: Signature = signing_kp.sign(&nonce_arr);
    let body = json!({
        "identity":     identity,
        "signing_pubkey": hex::encode(signing_kp.verifying_key().to_bytes()),
        "challenge":    nonce_hex,
        "signature":    hex::encode(sig.to_bytes()),
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
    (app, status, v)
}

async fn obtain_token(
    app: axum::Router,
    identity: &str,
    signing_kp: &SigningKey,
) -> (axum::Router, String) {
    let (app, nonce_hex) = fetch_challenge(app, identity).await;
    let (app, status, v) = call_session(app, identity, signing_kp, &nonce_hex).await;
    assert_eq!(status, StatusCode::OK, "session failed: {:?}", v);
    let token = v["token"].as_str().unwrap().to_string();
    (app, token)
}

async fn call_poll_raw(app: axum::Router, token: &str) -> (axum::Router, StatusCode, Vec<u8>) {
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/relay/poll")
                .header("authorization", format!("Bearer {}", token))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let bytes = to_bytes(res.into_body(), 16_384).await.unwrap();
    (app, status, bytes.to_vec())
}

/// `/relay/poll` with the `X-Phantom-Long-Poll: 1` opt-in header — the
/// gate that lets the server actually apply `RelayConfig.poll_hold_secs`.
/// Without this header, the server returns immediately (short-poll)
/// regardless of env, so old Android clients with 10 s call/read
/// timeout never see a hold longer than their socket budget. Stage 2
/// client work raises the timeout AND sets this header in one atomic
/// upgrade.
async fn call_poll_with_long_poll_optin(
    app: axum::Router,
    token: &str,
) -> (axum::Router, StatusCode, Vec<u8>) {
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
    let bytes = to_bytes(res.into_body(), 16_384).await.unwrap();
    (app, status, bytes.to_vec())
}

/// Build a test app with `poll_hold_secs` overridden to a specific
/// value. 1 s is enough for deterministic E2E tests of the hold path
/// without making the suite slow.
fn build_app_with_hold(hold_secs: u32) -> axum::Router {
    let (router, _state) = build_app_with_hold_and_state(hold_secs);
    router
}

/// Variant that also returns the `Arc<AppState>` so a test can
/// directly manipulate `rest_store` / `notifiers` / `rest_seq` for
/// white-box scenarios (e.g. injecting an envelope without going
/// through `/relay/send` so the `notify_recipient` wake is NOT
/// triggered — used to exercise the `poll_hold_loop` timeout-branch
/// re-check path that catches a missed notify).
fn build_app_with_hold_and_state(
    hold_secs: u32,
) -> (axum::Router, Arc<phantom_relay::state::AppState>) {
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.poll_hold_secs = hold_secs;
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    let router = phantom_relay::routes::router(Arc::clone(&state));
    (router, state)
}

async fn call_send_with_ts(
    app: axum::Router,
    token: &str,
    idem_key: &str,
    to: &str,
    sequence_ts: u64,
) -> (axum::Router, StatusCode) {
    let body = json!({
        "envelope_id": idem_key,
        "to": to,
        "sealed_sender": "",
        "payload": "AAAA",
        "sequence_ts": sequence_ts,
    });
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/relay/send")
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {}", token))
                .header("idempotency-key", idem_key)
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    (app, status)
}

async fn call_ack_deliver(
    app: axum::Router,
    token: &str,
    id: &str,
) -> (axum::Router, StatusCode) {
    let body = json!({ "id": id });
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/relay/ack-deliver")
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {}", token))
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    (app, status)
}

// ── Q1: SessionResponse carries poll_hold_secs field ─────────────────────────

#[tokio::test]
async fn session_response_includes_poll_hold_secs_field() {
    let app = build_app();
    let identity = identity_hex(1);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (_app, token) = obtain_token(app, &identity, &signing_kp).await;
    assert!(!token.is_empty(), "should issue token");
    // Re-issue to inspect the JSON shape directly (idempotent for same
    // challenge/signature pair).
    let app2 = build_app();
    let (app2, nonce_hex) = fetch_challenge(app2, &identity).await;
    let (_app2, status, v) = call_session(app2, &identity, &signing_kp, &nonce_hex).await;
    assert_eq!(status, StatusCode::OK);
    assert!(
        v.get("poll_hold_secs").is_some(),
        "poll_hold_secs must always be present in SessionResponse JSON, got: {}",
        v
    );
    assert_eq!(
        v["poll_hold_secs"].as_u64(),
        Some(0),
        "default config has poll_hold_secs=0 (short-poll / kill-switch)"
    );
}

/// Backward-compat: an Android client that locally defines `SessionResponse`
/// WITHOUT the new `poll_hold_secs` field MUST still deserialize a Stage 1
/// server's response (serde tolerates extra unknown fields by default unless
/// `deny_unknown_fields` is set). This proves Stage 1's wire contract is
/// purely additive.
#[tokio::test]
async fn old_struct_session_response_deserializes_without_poll_hold_secs() {
    #[derive(Deserialize)]
    #[allow(dead_code)]
    struct OldSessionResponse {
        token: String,
        expires_at: u64,
        rest_fallback: bool,
        max_send_body_bytes: usize,
        poll_max_envelopes: usize,
        media_capabilities: serde_json::Value,
    }
    let app = build_app();
    let identity = identity_hex(2);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (app, nonce_hex) = fetch_challenge(app, &identity).await;
    let nonce_vec = hex::decode(&nonce_hex).unwrap();
    let nonce_arr: [u8; 32] = nonce_vec.try_into().unwrap();
    let sig = signing_kp.sign(&nonce_arr);
    let body = json!({
        "identity":     identity,
        "signing_pubkey": hex::encode(signing_kp.verifying_key().to_bytes()),
        "challenge":    &nonce_hex,
        "signature":    hex::encode(sig.to_bytes()),
    });
    let res = app
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
    assert_eq!(res.status(), StatusCode::OK);
    let bytes = to_bytes(res.into_body(), 8192).await.unwrap();
    let parsed: Result<OldSessionResponse, _> = serde_json::from_slice(&bytes);
    assert!(
        parsed.is_ok(),
        "old client struct must deserialize Stage 1 server response: {:?}",
        parsed.err()
    );
}

// ── Q4: padded body exact-byte invariant on the wire (opt-in tier) ──────────

/// Opt-in path: `X-Phantom-Long-Poll: 1` triggers canonical padding.
/// This is the Stage 2+ client shape — empty and envelope-bearing
/// responses are byte-indistinguishable per security invariant 1.
#[tokio::test]
async fn opt_in_poll_response_body_is_exactly_canonical_size_when_empty() {
    let app = build_app();
    let identity = identity_hex(3);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;
    let (_app, status, body) = call_poll_with_long_poll_optin(app, &token).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        body.len(),
        POLL_RESPONSE_CANONICAL_BYTES,
        "opt-in empty poll body must equal canonical 4608 bytes on the wire"
    );
}

/// Legacy / no-header path: old Android clients (no opt-in header)
/// receive the original small JSON body, NOT the 4608-byte padded
/// shape. This proves Stage 1 does not silently regress bandwidth on
/// existing clients (Vladislav PR #297 round-3 review P1 load-bearing).
#[tokio::test]
async fn no_opt_in_poll_response_body_is_legacy_small_when_empty() {
    let app = build_app();
    let identity = identity_hex(40);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;
    let (_app, status, body) = call_poll_raw(app, &token).await;
    assert_eq!(status, StatusCode::OK);
    // Legacy shape is `{"envelopes":[],"more":false}` — exactly 28
    // bytes. We assert "under 200" to give serde some flexibility but
    // catch any regression where the canonical 4608-byte padding
    // accidentally activates for an unopted-in caller.
    assert!(
        body.len() < 200,
        "no-header poll body must be the legacy small shape, not 4608; \
         body.len()={}",
        body.len()
    );
    // And the `pad` field MUST be absent (not present-but-empty).
    let body_str = std::str::from_utf8(&body).unwrap();
    assert!(
        !body_str.contains("\"pad\""),
        "no-header response must NOT include `pad` field; body={}",
        body_str
    );
}

// ── Q7: kill switch — poll_hold_secs=0 returns immediately ──────────────────

/// Kill switch fires regardless of opt-in: with `RELAY_POLL_HOLD_SECS=0`
/// the server returns immediately on both tiers. Opt-in clients still
/// get the padded canonical body (server distinguishes by tier, not by
/// hold). No-header clients still get the legacy small body.
#[tokio::test]
async fn opt_in_poll_returns_immediately_when_hold_secs_zero_with_padded_body() {
    let app = build_app(); // default cfg: hold=0
    let identity = identity_hex(4);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;
    let start = std::time::Instant::now();
    let (_app, status, body) = call_poll_with_long_poll_optin(app, &token).await;
    let elapsed = start.elapsed();
    assert_eq!(status, StatusCode::OK);
    assert!(
        elapsed.as_millis() < 1_000,
        "opt-in poll with hold_secs=0 must return immediately (elapsed={:?})",
        elapsed
    );
    assert_eq!(body.len(), POLL_RESPONSE_CANONICAL_BYTES);
}

// ── Q2: ack-deliver rate-limit at 120/window ─────────────────────────────────

#[tokio::test]
async fn ack_deliver_returns_429_after_120_calls_in_window() {
    // Constant sanity (also asserted in unit tests).
    assert_eq!(ACK_DELIVER_RATE_LIMIT_PER_WINDOW, 120);

    let app = build_app();
    let identity = identity_hex(5);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (mut app, token) = obtain_token(app, &identity, &signing_kp).await;

    // First 120 calls allowed; ID does not need to exist — ack-deliver is
    // idempotent and the rate limit fires AFTER `id` validation but BEFORE
    // store mutation, so any non-empty `id` exercises the limiter.
    for i in 0..120u32 {
        let id = format!("fake-id-{}", i);
        let (app2, status) = call_ack_deliver(app, &token, &id).await;
        app = app2;
        assert_eq!(
            status,
            StatusCode::OK,
            "ack-deliver call #{} should succeed, got {}",
            i + 1,
            status
        );
    }

    // 121st call must be 429 — rate limit triggered.
    let (_app, status) = call_ack_deliver(app, &token, "fake-id-121").await;
    assert_eq!(
        status,
        StatusCode::TOO_MANY_REQUESTS,
        "121st ack-deliver in window must be 429"
    );
}

// ── Q2: ack-deliver is independent from /relay/send rate-limiter ─────────────

#[tokio::test]
async fn send_rate_limit_does_not_block_ack_deliver() {
    // The same identity sends a few envelopes (consuming send-side rate)
    // and then makes many ack-deliver calls. Independence means: the
    // ack-deliver bucket is unaffected by the send bucket, even when the
    // sender and recipient identity are the same address.
    let app = build_app();
    let identity = identity_hex(6);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);
    let (mut app, token) = obtain_token(app, &identity, &signing_kp).await;
    let to_recipient = identity_hex(99);

    // Send a couple of envelopes to consume some send-bucket slots.
    for i in 0..5 {
        let idem = format!("indep-send-{}", i);
        let (app2, status) =
            call_send_with_ts(app, &token, &idem, &to_recipient, 1_700_000_000_000).await;
        app = app2;
        // Send to a different recipient — should succeed (201).
        assert_eq!(status, StatusCode::CREATED, "send {} failed", i);
    }

    // Now hammer ack-deliver. The first 120 must succeed independently of
    // how many sends we just did.
    for i in 0..120 {
        let (app2, status) = call_ack_deliver(app, &token, &format!("indep-ack-{}", i)).await;
        app = app2;
        assert_eq!(
            status,
            StatusCode::OK,
            "ack-deliver #{} blocked despite separate bucket: status={}",
            i + 1,
            status
        );
    }
    // And the 121st still triggers the ack-specific limit.
    let (_app, status) = call_ack_deliver(app, &token, "indep-ack-121").await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
}

// ── Q5: sequence_ts is quantized to 60s on the server ────────────────────────

#[tokio::test]
async fn sequence_ts_is_quantized_to_60s_when_stored_and_returned_via_poll() {
    let app = build_app();
    // Sender + recipient identities.
    let sender_id = identity_hex(7);
    let recipient_id = identity_hex(8);
    let mut csprng = OsRng;
    let sender_kp = SigningKey::generate(&mut csprng);
    let recipient_kp = SigningKey::generate(&mut csprng);

    // Sender obtains its token, sends one envelope with a non-round
    // sequence_ts (e.g. 1_700_000_000_001 = 1 ms past the minute).
    let (app, sender_token) = obtain_token(app, &sender_id, &sender_kp).await;
    let non_round_ts = 1_700_000_000_001u64;
    let (app, status) =
        call_send_with_ts(app, &sender_token, "ts-quantize-1", &recipient_id, non_round_ts).await;
    assert_eq!(status, StatusCode::CREATED);

    // Recipient obtains its own token and polls — the returned envelope's
    // sequence_ts must be quantized to the 60s boundary BELOW the input.
    let (app, recipient_token) = obtain_token(app, &recipient_id, &recipient_kp).await;
    let (_app, status, body) = call_poll_raw(app, &recipient_token).await;
    assert_eq!(status, StatusCode::OK);
    let v: Value = serde_json::from_slice(&body).unwrap();
    let envelopes = v["envelopes"].as_array().expect("envelopes array");
    assert_eq!(envelopes.len(), 1, "exactly one envelope expected");
    let returned_ts = envelopes[0]["sequence_ts"].as_u64().unwrap();
    assert_eq!(returned_ts % 60_000, 0, "sequence_ts must be quantized to 60s boundary");
    assert_eq!(
        returned_ts,
        non_round_ts - (non_round_ts % 60_000),
        "quantized value must be the 60s-boundary floor of the input"
    );
}

// ── Padded body byte-equality on the wire (Q4 load-bearing, opt-in tier) ────

/// Security invariant 1 of the Trek 2 mini-lock, evaluated on the
/// opt-in tier where padding is engaged. Empty-poll and envelope-
/// bearing poll responses MUST be byte-identical on the wire so a
/// passive observer cannot tell from response size whether a message
/// arrived. (For no-header legacy clients the invariant is degraded
/// to its pre-Stage-1 baseline — no regression, just no upgrade.)
#[tokio::test]
async fn opt_in_empty_and_envelope_bearing_responses_have_identical_wire_size() {
    let app = build_app();
    // Recipient identity.
    let recipient_id = identity_hex(9);
    let mut csprng = OsRng;
    let recipient_kp = SigningKey::generate(&mut csprng);
    let (app, recipient_token) = obtain_token(app, &recipient_id, &recipient_kp).await;

    // First opt-in poll: queue is empty.
    let (app, status_empty, body_empty) =
        call_poll_with_long_poll_optin(app, &recipient_token).await;
    assert_eq!(status_empty, StatusCode::OK);
    assert_eq!(body_empty.len(), POLL_RESPONSE_CANONICAL_BYTES);

    // Now sender enqueues one envelope.
    let sender_id = identity_hex(10);
    let sender_kp = SigningKey::generate(&mut csprng);
    let (app, sender_token) = obtain_token(app, &sender_id, &sender_kp).await;
    let (app, status_send) = call_send_with_ts(
        app,
        &sender_token,
        "byte-eq-1",
        &recipient_id,
        1_700_000_000_000,
    )
    .await;
    assert_eq!(status_send, StatusCode::CREATED);

    // Opt-in poll again — now carrying one envelope.
    let (_app, status_full, body_full) =
        call_poll_with_long_poll_optin(app, &recipient_token).await;
    assert_eq!(status_full, StatusCode::OK);

    // Load-bearing assertion: empty and envelope-bearing opt-in
    // responses are byte-identical in length on the wire.
    assert_eq!(
        body_empty.len(),
        body_full.len(),
        "empty-poll and envelope-bearing opt-in bodies must have identical wire size"
    );
    assert_eq!(body_full.len(), POLL_RESPONSE_CANONICAL_BYTES);
}

// ── P1 fix: request-level opt-in via X-Phantom-Long-Poll: 1 ──────────────────

/// Backward-compatibility load-bearing test (Vladislav PR #297 review P1):
/// existing Android clients on `master` have `CALL_TIMEOUT_MS = 10_000`
/// and do NOT send the `X-Phantom-Long-Poll: 1` header. If the operator
/// flips `RELAY_POLL_HOLD_SECS=20` globally, those clients MUST still
/// see short-poll behaviour — otherwise an empty poll would hold for
/// 20 s server-side, well past the client's 10 s socket budget, breaking
/// the "Stage 1 = zero client risk" contract.
#[tokio::test]
async fn poll_without_opt_in_header_returns_immediately_even_with_hold_configured() {
    // Server configured with poll_hold_secs=5 (a value that would
    // exceed an old client's 10 s socket budget on any non-trivial
    // hold path, but cheap for the test to wait through if it did
    // accidentally engage).
    let app = build_app_with_hold(5);
    let recipient_id = identity_hex(30);
    let mut csprng = OsRng;
    let recipient_kp = SigningKey::generate(&mut csprng);
    let (app, token) = obtain_token(app, &recipient_id, &recipient_kp).await;

    // Call WITHOUT the opt-in header (simulates old Android client).
    let start = std::time::Instant::now();
    let (_app, status, body) = call_poll_raw(app, &token).await;
    let elapsed = start.elapsed();

    assert_eq!(status, StatusCode::OK);
    assert!(
        elapsed.as_millis() < 500,
        "old client (no opt-in header) must NOT be held even when \
         server poll_hold_secs=5; elapsed={:?}",
        elapsed
    );
    // Round-3 fix (PR #297 P1): no-header path MUST return the legacy
    // small body, NOT the 4608-byte padded shape. Otherwise old
    // clients silently pay ~4.5 KB per poll on metered cellular.
    assert!(
        body.len() < 200,
        "no-header path must keep legacy small body shape even when \
         server hold>0 is configured; body.len()={}",
        body.len()
    );
}

/// Hold path with opt-in header but no envelope arriving — confirms the
/// server actually waits to the timeout and returns padded empty body
/// (Guardrail A: no message loss, no hang).
#[tokio::test]
async fn poll_with_opt_in_header_and_no_send_returns_at_hold_timeout() {
    let hold_secs = 1u32;
    let app = build_app_with_hold(hold_secs);
    let recipient_id = identity_hex(31);
    let mut csprng = OsRng;
    let recipient_kp = SigningKey::generate(&mut csprng);
    let (app, token) = obtain_token(app, &recipient_id, &recipient_kp).await;

    let start = std::time::Instant::now();
    let (_app, status, body) = call_poll_with_long_poll_optin(app, &token).await;
    let elapsed = start.elapsed();

    assert_eq!(status, StatusCode::OK);
    // Must have waited approximately hold_secs (1 s) before returning.
    // Generous lower bound (800 ms) to tolerate scheduling jitter on
    // slow CI runners; upper bound (2 s) catches a regression where
    // the loop's `tokio::time::timeout` is bypassed.
    assert!(
        elapsed.as_millis() >= 800,
        "hold must wait close to hold_secs ({} s); elapsed={:?}",
        hold_secs,
        elapsed
    );
    assert!(
        elapsed.as_millis() < 2_000,
        "hold must not exceed 60 s TimeoutLayer ceiling significantly; \
         elapsed={:?}",
        elapsed
    );
    assert_eq!(body.len(), POLL_RESPONSE_CANONICAL_BYTES);
}

/// LOAD-BEARING E2E (P3 fix from Vladislav PR #297 review): hold path
/// with opt-in header AND a concurrent `/relay/send` to the recipient.
/// The waiting poll MUST wake on `notify_one` from the send path and
/// return the envelope well before the hold timeout.
///
/// This is the production-critical happy path that Stage 1 actually
/// delivers — empty-poll wake-up under ~50 ms (plus coalescing).
#[tokio::test]
async fn poll_with_opt_in_header_wakes_on_send_before_hold_timeout() {
    let hold_secs = 3u32;
    let app = build_app_with_hold(hold_secs);

    // Recipient (does the poll).
    let recipient_id = identity_hex(32);
    let mut csprng = OsRng;
    let recipient_kp = SigningKey::generate(&mut csprng);
    let (app, recipient_token) = obtain_token(app, &recipient_id, &recipient_kp).await;

    // Sender (separate identity to avoid sender==recipient confusion
    // in the rate-limit bucket, even though they are independent).
    let sender_id = identity_hex(33);
    let sender_kp = SigningKey::generate(&mut csprng);
    let (app, sender_token) = obtain_token(app, &sender_id, &sender_kp).await;

    // Launch poll in the background — it will register on the
    // recipient's Notify and await.
    let poll_app = app.clone();
    let poll_token = recipient_token.clone();
    let poll_handle = tokio::spawn(async move {
        let start = std::time::Instant::now();
        let (_app, status, body) =
            call_poll_with_long_poll_optin(poll_app, &poll_token).await;
        (start.elapsed(), status, body)
    });

    // Give the poll handler enough head start to reach the
    // `notifier.notified().await` phase. 200 ms is comfortable on
    // slow CI runners and well under the 3 s hold timeout.
    tokio::time::sleep(tokio::time::Duration::from_millis(200)).await;

    // Send: this writes the envelope to rest_store AND calls
    // `notify_recipient`, which should wake the poll.
    let (_app2, send_status) = call_send_with_ts(
        app,
        &sender_token,
        "hold-wake-1",
        &recipient_id,
        1_700_000_000_000,
    )
    .await;
    assert_eq!(send_status, StatusCode::CREATED, "send must succeed");

    // Poll should return WELL before the 3 s hold timeout — somewhere
    // in the ~200 ms (initial sleep) + ~50 ms (coalesce) + handler
    // overhead range. Upper bound 1500 ms is generous.
    let (elapsed, status, body) = poll_handle
        .await
        .expect("poll task must not panic");
    assert_eq!(status, StatusCode::OK);
    assert!(
        elapsed.as_millis() < 1_500,
        "poll must wake on notify well before {} s timeout; elapsed={:?}",
        hold_secs,
        elapsed
    );
    assert!(
        elapsed.as_millis() >= 200,
        "elapsed must include the initial pre-send delay; got={:?}",
        elapsed
    );

    // Body MUST contain the envelope we just sent.
    let v: Value = serde_json::from_slice(&body).unwrap();
    let envelopes = v["envelopes"]
        .as_array()
        .expect("envelopes array present");
    assert_eq!(envelopes.len(), 1, "poll must return exactly one envelope");
    assert_eq!(envelopes[0]["id"].as_str(), Some("hold-wake-1"));

    // Padding invariant still holds even on the wake path.
    assert_eq!(body.len(), POLL_RESPONSE_CANONICAL_BYTES);
}

/// Pre-enqueue smoke (formerly misnamed
/// `poll_recheck_after_notifier_registration_catches_concurrent_send` —
/// rename honest per Vladislav PR #297 round-3 review P3): pre-enqueue
/// is caught by `poll_hold_loop` PHASE 1 (the initial `drain_eligible`
/// before any Notify registration), NOT by the phase 3.5 re-check.
/// This test proves the loop short-circuits when the queue is already
/// non-empty on entry; the phase 3.5 race path is exercised separately
/// by `poll_hold_loop_timeout_recheck_catches_envelope_without_notify`
/// below (which uses direct state injection to bypass `notify_recipient`).
#[tokio::test]
async fn pre_enqueued_envelope_short_circuits_hold_loop_at_phase_1() {
    let hold_secs = 3u32;
    let app = build_app_with_hold(hold_secs);

    let recipient_id = identity_hex(34);
    let mut csprng = OsRng;
    let recipient_kp = SigningKey::generate(&mut csprng);
    let (app, recipient_token) = obtain_token(app, &recipient_id, &recipient_kp).await;

    let sender_id = identity_hex(35);
    let sender_kp = SigningKey::generate(&mut csprng);
    let (app, sender_token) = obtain_token(app, &sender_id, &sender_kp).await;

    // Pre-enqueue via the normal send path. By the time the poll
    // starts, the envelope is already in `rest_store` and phase 1's
    // initial `drain_eligible` catches it — the loop never reaches
    // phase 3 or 3.5.
    let (app, send_status) = call_send_with_ts(
        app,
        &sender_token,
        "phase1-1",
        &recipient_id,
        1_700_000_000_000,
    )
    .await;
    assert_eq!(send_status, StatusCode::CREATED);

    let start = std::time::Instant::now();
    let (_app, status, body) = call_poll_with_long_poll_optin(app, &recipient_token).await;
    let elapsed = start.elapsed();

    assert_eq!(status, StatusCode::OK);
    assert!(
        elapsed.as_millis() < 500,
        "pre-enqueued envelope must return at phase 1 without engaging \
         the hold wait; elapsed={:?}",
        elapsed
    );
    let v: Value = serde_json::from_slice(&body).unwrap();
    let envelopes = v["envelopes"].as_array().expect("envelopes array");
    assert_eq!(envelopes.len(), 1);
    assert_eq!(envelopes[0]["id"].as_str(), Some("phase1-1"));
}

/// LOAD-BEARING phase 3.5 / timeout-branch re-check coverage (Vladislav
/// PR #297 round-3 review P3 honest fix): directly inject an envelope
/// into `state.rest_store` AFTER the poll has started — bypassing
/// `notify_recipient` so the notifier never fires. The
/// `poll_hold_loop` MUST re-check the queue on hold-timeout and
/// return the envelope, NOT discard it.
///
/// Sequence:
///   1. Build app with `poll_hold_secs = 1` and grab the `AppState` Arc.
///   2. Start an opt-in `/relay/poll` in a background task. It runs
///      phase 1 (empty), phase 3 (notifier registered), phase 3.5
///      (still empty), then `notified().await`.
///   3. After ~300 ms, inject an envelope DIRECTLY into
///      `state.rest_store` for the recipient. Critically: do NOT call
///      `state.notify_recipient` — this simulates the race where
///      cleanup or a concurrent path silently dropped the wake.
///   4. The `notified().await` hits the 1 s timeout. The
///      timeout-branch re-check (`drain_eligible` after `Err(Elapsed)`)
///      MUST see the injected envelope and return it.
///   5. Poll returns with 1 envelope, NOT empty.
#[tokio::test]
async fn poll_hold_loop_timeout_recheck_catches_envelope_without_notify() {
    use phantom_relay::rest_fallback::RestEnvelope;

    let hold_secs = 1u32;
    let (app, state) = build_app_with_hold_and_state(hold_secs);

    let recipient_id = identity_hex(36);
    let mut csprng = OsRng;
    let recipient_kp = SigningKey::generate(&mut csprng);
    let (app, recipient_token) = obtain_token(app, &recipient_id, &recipient_kp).await;

    // Background poll task — opt-in so the hold engages.
    let poll_app = app.clone();
    let poll_token = recipient_token.clone();
    let poll_handle = tokio::spawn(async move {
        let (_app, status, body) =
            call_poll_with_long_poll_optin(poll_app, &poll_token).await;
        (status, body)
    });

    // Wait for the poll to settle into the hold wait (~300 ms is a
    // comfortable buffer past auth + phase 1 + notifier_for + phase 3.5
    // checks on slow CI runners; well under the 1 s hold timeout).
    tokio::time::sleep(tokio::time::Duration::from_millis(300)).await;

    // Inject envelope DIRECTLY into rest_store. No notify_recipient
    // call — this is the explicit "send happened, notify silently
    // dropped" scenario the re-check guards against.
    let injected = RestEnvelope {
        id: "timeout-recheck-1".to_string(),
        from: String::new(),
        sealed_sender: String::new(),
        payload: "AAAA".to_string(),
        sequence_ts: 1_700_000_000_000,
        seq: 1,
        // Far future so the queue retain in drain_eligible does not
        // purge it as expired during the test.
        expires_at: u64::MAX / 2,
        // Trek 2 Stage 1.x — this test bypasses `mirror_envelope_to_rest_store`
        // (the only production path that computes the real MAC), so a
        // placeholder hex string is sufficient for the queue-rescan
        // assertion. Real-MAC contract tests live in the new
        // `seq_mac_vectors.rs` integration suite and in the
        // mirror-path integration tests below.
        seq_mac: "0".repeat(64),
    };
    {
        let mut rest_store = state.rest_store.write().await;
        rest_store
            .entry(recipient_id.clone())
            .or_default()
            .push(injected);
    }

    // Poll MUST return WITH the envelope after the 1 s hold timeout.
    // We allot up to 2.5 s wall-clock so the timeout itself plus the
    // re-check overhead complete on slow CI.
    let result = tokio::time::timeout(
        tokio::time::Duration::from_millis(2_500),
        poll_handle,
    )
    .await
    .expect("poll task did not complete in time")
    .expect("poll task panicked");

    let (status, body) = result;
    assert_eq!(status, StatusCode::OK);

    let v: Value = serde_json::from_slice(&body).unwrap();
    let envelopes = v["envelopes"].as_array().expect("envelopes array");
    assert_eq!(
        envelopes.len(),
        1,
        "timeout-branch re-check MUST return the injected envelope; \
         body was {}",
        std::str::from_utf8(&body).unwrap_or("<non-utf8>")
    );
    assert_eq!(envelopes[0]["id"].as_str(), Some("timeout-recheck-1"));
    // Opt-in body is still padded.
    assert_eq!(body.len(), POLL_RESPONSE_CANONICAL_BYTES);
}
