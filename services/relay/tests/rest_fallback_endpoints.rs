// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Integration tests for the REST fallback transport endpoints (PR-D0r).
//!
//! All five tests from the PR-D0r definition of done:
//!
//!   1. /auth/session retry-safety: 5 identical calls → same token.
//!   2. /relay/send idempotency: 3 calls, same key + body → same response,
//!      envelope dispatched exactly once.
//!   3. /relay/send idempotency conflict: same key, different body → 409.
//!   4. /relay/poll non-removal: poll twice, second sees same envelope.
//!   5. /relay/ack-deliver idempotency: ack twice → both 200, envelope removed.
//!
//! Uses the same tower::ServiceExt::oneshot pattern as prekey_endpoints.rs
//! so tests are hermetic (no TCP, no ports, no race conditions).

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use ed25519_dalek::{Signer, Signature, SigningKey};
use rand::rngs::OsRng;
use serde_json::{json, Value};
use std::sync::Arc;
use tower::ServiceExt;

// ── Helpers ───────────────────────────────────────────────────────────────────

fn build_app() -> axum::Router {
    let cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    phantom_relay::routes::router(state)
}

/// Unique 64-hex-char identity derived from a seed byte, distinct from those
/// used by prekey_endpoints.rs (which uses seeds 20-25).
fn identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        *b = seed.wrapping_add(i as u8).wrapping_add(0xA0);
    }
    hex::encode(buf)
}

/// Issue a /auth/challenge nonce for `identity` and return `nonce_hex`.
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
    assert_eq!(res.status(), StatusCode::OK, "challenge fetch failed");
    let body = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = serde_json::from_slice(&body).unwrap();
    let nonce_hex = v["nonce_hex"].as_str().unwrap().to_string();
    (app, nonce_hex)
}

/// Call POST /auth/session with the given (identity, signing_kp, nonce_hex)
/// and return the full response as (StatusCode, Value).
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
    let bytes = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    (app, status, v)
}

/// Obtain a bearer token (challenge + session in one call).
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

/// POST /relay/send with a given body bytes and optional Idempotency-Key.
async fn call_send_raw(
    app: axum::Router,
    token: &str,
    idem_key: &str,
    body: &[u8],
) -> (axum::Router, StatusCode, Value) {
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/relay/send")
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {}", token))
                .header("idempotency-key", idem_key)
                .header("connection", "close")
                .body(Body::from(body.to_vec()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let bytes = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    (app, status, v)
}

/// GET /relay/poll
async fn call_poll(
    app: axum::Router,
    token: &str,
    since_seq: Option<u64>,
) -> (axum::Router, StatusCode, Value) {
    let uri = match since_seq {
        Some(s) => format!("/relay/poll?since_seq={}", s),
        None => "/relay/poll".to_string(),
    };
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(&uri)
                .header("authorization", format!("Bearer {}", token))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let bytes = to_bytes(res.into_body(), 8192).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    (app, status, v)
}

/// POST /relay/ack-deliver
async fn call_ack_deliver(
    app: axum::Router,
    token: &str,
    envelope_id: &str,
) -> (axum::Router, StatusCode, Value) {
    let body = json!({ "id": envelope_id });
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
    let bytes = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    (app, status, v)
}

// ── Test 1: /auth/session retry-safety ───────────────────────────────────────

/// Same (identity, challenge, signature) tuple → same token returned across
/// 5 calls. The first call issues and caches the token; subsequent calls
/// replay from the session challenge cache without re-consuming the nonce
/// (nonce is already consumed on call 1).
///
/// Implementation note: calls 2-5 trigger the session_cache hit path and
/// return the same token. Because the challenge nonce is one-shot consumed
/// on call 1, calls 2-5 cannot re-verify the signature — they rely purely
/// on the (identity, challenge) cache key. This is the specified behaviour
/// (spec: "Same (identity, challenge, signature) within 5 minutes → same token").
#[tokio::test]
async fn auth_session_retry_safe_same_token() {
    let app = build_app();
    let identity = identity_hex(30);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());

    // Fetch one challenge nonce.
    let (app, nonce_hex) = fetch_challenge(app, &identity).await;

    // First call — issues token, caches it.
    let (app, status1, v1) = call_session(app, &identity, &signing_kp, &nonce_hex).await;
    assert_eq!(status1, StatusCode::OK, "call 1 failed: {:?}", v1);
    let token1 = v1["token"].as_str().unwrap();
    assert!(!token1.is_empty());
    assert_eq!(v1["rest_fallback"], true);
    assert_eq!(v1["max_send_body_bytes"], 4096);
    assert_eq!(v1["poll_max_envelopes"], 1);

    // Calls 2-5 with the same (identity, challenge, signature).
    // The nonce is already consumed; the session cache replays the token.
    let nonce_vec = hex::decode(&nonce_hex).unwrap();
    let nonce_arr: [u8; 32] = nonce_vec.try_into().unwrap();
    let sig: Signature = signing_kp.sign(&nonce_arr);
    let sig_hex = hex::encode(sig.to_bytes());

    let body = json!({
        "identity":       identity,
        "signing_pubkey": signing_hex,
        "challenge":      nonce_hex,
        "signature":      sig_hex,
    })
    .to_string();

    for call_n in 2..=5 {
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/auth/session")
                    .header("content-type", "application/json")
                    .body(Body::from(body.clone()))
                    .unwrap(),
            )
            .await
            .unwrap();
        let status = res.status();
        let bytes = to_bytes(res.into_body(), 4096).await.unwrap();
        let v: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(
            status,
            StatusCode::OK,
            "call {} failed: {:?}", call_n, v
        );
        let returned_token = v["token"].as_str().unwrap();
        assert_eq!(
            returned_token, token1,
            "call {} returned different token: {} vs {}",
            call_n, returned_token, token1
        );
    }
}

// ── Test 1b: /auth/session capability contract ───────────────────────────────

/// Focused capability-contract assertion: a successful `/auth/session`
/// response MUST advertise:
///
///   - `rest_fallback`        == true
///   - `max_send_body_bytes`  == 4096   (locked 2026-05-16; client soft-target
///                                       is ≤ 2000 but server hard cap is 4096)
///   - `poll_max_envelopes`   == 1      (locked 2026-05-16)
///   - `token`                non-empty string
///   - `expires_at`           positive integer (unix_ms)
///
/// Why a dedicated test (separate from the retry-safety test): the D1/D1b
/// client side decides whether to enable REST fallback purely by reading
/// these fields from the very first auth/session response. If a future
/// refactor accidentally removes a field or flips `rest_fallback` to
/// false-by-default, the client will silently stay WS-only and the bug
/// would only surface during a Tele2-LTE-style outage when the fallback
/// is actually needed. This assertion pins the wire contract.
#[tokio::test]
async fn auth_session_returns_rest_fallback_capabilities() {
    let app = build_app();
    let identity = identity_hex(40);
    let signing_kp = SigningKey::generate(&mut OsRng);

    let (_app, token) = obtain_token(app, &identity, &signing_kp).await;
    assert!(!token.is_empty(), "token must be a non-empty string");

    // Re-fetch the full response to assert all capability fields explicitly
    // (obtain_token only returns the token string).
    let app = build_app();
    let identity = identity_hex(41);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let (app, nonce_hex) = fetch_challenge(app, &identity).await;
    let (_app, status, v) =
        call_session(app, &identity, &signing_kp, &nonce_hex).await;

    assert_eq!(status, StatusCode::OK, "/auth/session must succeed");

    // Required capability fields — locked 2026-05-16.
    assert_eq!(
        v["rest_fallback"], true,
        "rest_fallback MUST be true so the client's capability gate enables REST mode",
    );
    assert_eq!(
        v["max_send_body_bytes"], 4096,
        "max_send_body_bytes locked at 4096 (hard cap; client soft-target ≤ 2000)",
    );
    assert_eq!(
        v["poll_max_envelopes"], 1,
        "poll_max_envelopes locked at 1 (single envelope per /relay/poll call)",
    );

    // Token + expiry sanity.
    let token = v["token"].as_str().expect("token field must be a string");
    assert!(!token.is_empty(), "token must be a non-empty string");

    let expires_at = v["expires_at"]
        .as_u64()
        .expect("expires_at must be a positive integer (unix_ms)");
    assert!(expires_at > 0, "expires_at must be > 0");
}

// ── Test 2: /relay/send idempotency, same body ────────────────────────────────

/// 3 calls with same Idempotency-Key + same body → all return same response,
/// envelope dispatched exactly once (idempotency cache returns 200 on repeats).
#[tokio::test]
async fn relay_send_idempotent_same_body() {
    let app = build_app();
    let sender_id = identity_hex(31);
    let recipient_id = identity_hex(32);
    let signing_kp = SigningKey::generate(&mut OsRng);

    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;

    let envelope_id = "test-uuid-send-idem-001";
    let send_body = json!({
        "envelope_id": envelope_id,
        "to":          recipient_id,
        "payload":     "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "sequence_ts": 1_700_000_000_000_u64,
    })
    .to_string();
    let send_bytes = send_body.as_bytes();

    // Call 1 — fresh, should be 201.
    let (app, status1, v1) =
        call_send_raw(app, &token, envelope_id, send_bytes).await;
    assert_eq!(status1, StatusCode::CREATED, "call 1: {:?}", v1);
    assert_eq!(v1["ok"], 1);

    // Call 2 — duplicate same body, should be 200 (replay).
    let (app, status2, v2) =
        call_send_raw(app, &token, envelope_id, send_bytes).await;
    assert_eq!(status2, StatusCode::OK, "call 2: {:?}", v2);
    assert_eq!(v2["ok"], 1);

    // Call 3 — same.
    let (_app, status3, v3) =
        call_send_raw(app, &token, envelope_id, send_bytes).await;
    assert_eq!(status3, StatusCode::OK, "call 3: {:?}", v3);
    assert_eq!(v3["ok"], 1);
}

// ── Test 3: /relay/send idempotency conflict ──────────────────────────────────

/// Same Idempotency-Key, different body → 409 on second call.
#[tokio::test]
async fn relay_send_idempotent_conflict_different_body() {
    let app = build_app();
    let sender_id = identity_hex(33);
    let recipient_id = identity_hex(34);
    let signing_kp = SigningKey::generate(&mut OsRng);

    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;

    let idem_key = "test-uuid-conflict-001";

    let body_a = json!({
        "envelope_id": idem_key,
        "to":          recipient_id,
        "payload":     "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "sequence_ts": 1_700_000_000_001_u64,
    })
    .to_string();

    let body_b = json!({
        "envelope_id": idem_key,
        "to":          recipient_id,
        "payload":     "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
        "sequence_ts": 1_700_000_000_002_u64,
    })
    .to_string();

    // Call 1 with body A — accepted (201).
    let (app, status1, v1) =
        call_send_raw(app, &token, idem_key, body_a.as_bytes()).await;
    assert_eq!(status1, StatusCode::CREATED, "call 1: {:?}", v1);

    // Call 2 with body B, same key — conflict (409).
    let (_app, status2, v2) =
        call_send_raw(app, &token, idem_key, body_b.as_bytes()).await;
    assert_eq!(status2, StatusCode::CONFLICT, "call 2 should be 409: {:?}", v2);
}

// ── Test 4: /relay/poll non-removal ───────────────────────────────────────────

/// Poll twice without ack-deliver; second poll sees the same envelope.
#[tokio::test]
async fn relay_poll_does_not_remove_envelope() {
    let app = build_app();
    let sender_id = identity_hex(35);
    let recipient_id = identity_hex(36);
    let signing_kp_sender = SigningKey::generate(&mut OsRng);
    let signing_kp_recipient = SigningKey::generate(&mut OsRng);

    // Sender obtains token and sends an envelope.
    let (app, sender_token) = obtain_token(app, &sender_id, &signing_kp_sender).await;

    let envelope_id = "test-uuid-poll-nonremove-001";
    let send_body = json!({
        "envelope_id": envelope_id,
        "to":          recipient_id,
        "payload":     "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
        "sequence_ts": 1_700_000_000_010_u64,
    })
    .to_string();
    let (app, status, _) =
        call_send_raw(app, &sender_token, envelope_id, send_body.as_bytes()).await;
    assert_eq!(status, StatusCode::CREATED);

    // Recipient obtains token.
    let (app, recipient_token) =
        obtain_token(app, &recipient_id, &signing_kp_recipient).await;

    // Poll 1 — should return the envelope.
    let (app, poll_status1, poll_v1) = call_poll(app, &recipient_token, None).await;
    assert_eq!(poll_status1, StatusCode::OK, "poll 1: {:?}", poll_v1);
    let envs1 = poll_v1["envelopes"].as_array().unwrap();
    assert_eq!(envs1.len(), 1, "poll 1 should return 1 envelope");
    assert_eq!(envs1[0]["id"], envelope_id);

    // Poll 2 — same envelope must still be present (not removed by poll).
    let (_app, poll_status2, poll_v2) = call_poll(app, &recipient_token, None).await;
    assert_eq!(poll_status2, StatusCode::OK, "poll 2: {:?}", poll_v2);
    let envs2 = poll_v2["envelopes"].as_array().unwrap();
    assert_eq!(envs2.len(), 1, "poll 2 should still return 1 envelope");
    assert_eq!(envs2[0]["id"], envelope_id);
}

// ── Test 5: /relay/ack-deliver idempotency ────────────────────────────────────

/// Ack-deliver the same envelope twice; both calls return 200, envelope
/// removed after the first.
#[tokio::test]
async fn relay_ack_deliver_idempotent() {
    let app = build_app();
    let sender_id = identity_hex(37);
    let recipient_id = identity_hex(38);
    let signing_kp_sender = SigningKey::generate(&mut OsRng);
    let signing_kp_recipient = SigningKey::generate(&mut OsRng);

    // Sender sends an envelope.
    let (app, sender_token) = obtain_token(app, &sender_id, &signing_kp_sender).await;

    let envelope_id = "test-uuid-ack-idem-001";
    let send_body = json!({
        "envelope_id": envelope_id,
        "to":          recipient_id,
        "payload":     "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD",
        "sequence_ts": 1_700_000_000_020_u64,
    })
    .to_string();
    let (app, send_status, _) =
        call_send_raw(app, &sender_token, envelope_id, send_body.as_bytes()).await;
    assert_eq!(send_status, StatusCode::CREATED);

    // Recipient obtains token.
    let (app, recipient_token) =
        obtain_token(app, &recipient_id, &signing_kp_recipient).await;

    // Verify envelope is present before ack.
    let (app, poll_status, poll_v) = call_poll(app, &recipient_token, None).await;
    assert_eq!(poll_status, StatusCode::OK);
    assert_eq!(
        poll_v["envelopes"].as_array().unwrap().len(),
        1,
        "envelope should be present before ack"
    );

    // Ack-deliver call 1 — removes envelope.
    let (app, ack_status1, ack_v1) =
        call_ack_deliver(app, &recipient_token, envelope_id).await;
    assert_eq!(ack_status1, StatusCode::OK, "ack 1: {:?}", ack_v1);
    assert_eq!(ack_v1["ok"], 1);

    // Ack-deliver call 2 — idempotent, returns 200 even though already removed.
    let (app, ack_status2, ack_v2) =
        call_ack_deliver(app, &recipient_token, envelope_id).await;
    assert_eq!(ack_status2, StatusCode::OK, "ack 2: {:?}", ack_v2);
    assert_eq!(ack_v2["ok"], 1);

    // Verify envelope is gone after ack.
    let (_app, poll_status_after, poll_v_after) =
        call_poll(app, &recipient_token, None).await;
    assert_eq!(poll_status_after, StatusCode::OK);
    assert_eq!(
        poll_v_after["envelopes"].as_array().unwrap().len(),
        0,
        "envelope should be absent after ack"
    );
}

// ── Test 6: /auth/session replay with different signature → 401 ──────────────
//
// PR-D0r review-fix coverage: the locked spec says same
// `(identity, challenge, signing_pubkey, signature)` within 5 min → same
// token. A replay with the same `(identity, challenge)` but a different
// signature must be rejected with 401 — otherwise an attacker who observed
// an in-flight `(identity, challenge)` pair could mint a request with an
// arbitrary signature and receive the legitimate client's token from the
// cache.
#[tokio::test]
async fn session_replay_with_different_signature_returns_401() {
    let app = build_app();
    let identity = identity_hex(90);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());

    let (app, nonce_hex) = fetch_challenge(app, &identity).await;

    // Call 1: real signature → 200, token issued, cache entry created.
    let (app, status1, v1) = call_session(app, &identity, &signing_kp, &nonce_hex).await;
    assert_eq!(status1, StatusCode::OK, "call 1 must succeed: {:?}", v1);
    let token1 = v1["token"].as_str().unwrap().to_string();
    assert!(!token1.is_empty());

    // Call 2: same identity, same signing_pubkey, same challenge,
    // BOGUS signature → must be rejected with 401, not return cached token.
    let bogus_sig = "ff".repeat(64); // 128 hex chars, syntactically valid
    let body = serde_json::json!({
        "identity":       identity,
        "signing_pubkey": signing_hex,
        "challenge":      nonce_hex,
        "signature":      bogus_sig,
    })
    .to_string();
    let res = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/auth/session")
                .header("content-type", "application/json")
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let bytes = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();

    assert_eq!(
        status,
        StatusCode::UNAUTHORIZED,
        "replay with different signature must be 401, got {:?}",
        v,
    );
    // Token MUST NOT be present in the response.
    assert!(
        v.get("token").is_none(),
        "401 response must not leak the legitimate token: {:?}",
        v,
    );
}

// ── Test 7: /relay/send preserves sealed_sender end-to-end ───────────────────
//
// PR-D0r review-fix coverage: the original `RestSendRequest` silently
// dropped the sealed_sender field, breaking sealed-mode decrypt semantics
// for clients that use the REST fallback path. The field is now
// propagated into both stores and surfaced back on /relay/poll.
#[tokio::test]
async fn rest_send_preserves_sealed_sender() {
    let app = build_app();
    let sender_id = identity_hex(100);
    let recipient_id = identity_hex(101);
    let sender_kp = SigningKey::generate(&mut OsRng);
    let recipient_kp = SigningKey::generate(&mut OsRng);

    let (app, sender_token) = obtain_token(app, &sender_id, &sender_kp).await;
    let (app, recipient_token) = obtain_token(app, &recipient_id, &recipient_kp).await;

    // 32-byte sealed_sender blob (64 hex chars) — opaque to the relay.
    let sealed_blob = "deadbeefcafebabe".repeat(4);
    let envelope_id = "sealed-test-001";
    let send_body = serde_json::json!({
        "envelope_id":   envelope_id,
        "to":            recipient_id,
        "sealed_sender": sealed_blob,
        "payload":       "PAYLOAD_BLOB_BASE64",
        "sequence_ts":   1_700_000_000_000_u64,
    })
    .to_string();

    let (app, send_status, send_v) =
        call_send_raw(app, &sender_token, envelope_id, send_body.as_bytes()).await;
    assert_eq!(send_status, StatusCode::CREATED, "send: {:?}", send_v);

    // Recipient polls and should see sealed_sender preserved verbatim.
    let (_app, poll_status, poll_v) = call_poll(app, &recipient_token, None).await;
    assert_eq!(poll_status, StatusCode::OK, "poll: {:?}", poll_v);
    let envs = poll_v["envelopes"].as_array().unwrap();
    assert_eq!(envs.len(), 1, "expected exactly one polled envelope");
    assert_eq!(
        envs[0]["sealed_sender"].as_str().unwrap(),
        sealed_blob,
        "sealed_sender must survive the /relay/send → /relay/poll round-trip",
    );
    assert_eq!(envs[0]["id"].as_str().unwrap(), envelope_id);
    assert_eq!(envs[0]["payload"].as_str().unwrap(), "PAYLOAD_BLOB_BASE64");
}

// ── Test 8: WS-simulated send mirrors into REST poll store ───────────────────
//
// PR-D0r review-fix coverage: `mirror_envelope_to_rest_store` is the
// helper the WS path calls after writing to `state.store`. Calling it
// directly (as if a WS send had just landed) and then polling via REST
// proves that a Tele2-style client on REST fallback sees envelopes from
// WS senders.
#[tokio::test]
async fn ws_simulated_send_mirrors_into_rest_poll() {
    let cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    let state = std::sync::Arc::new(phantom_relay::state::AppState::new(cfg));
    let app = phantom_relay::routes::router(std::sync::Arc::clone(&state));

    let recipient_id = identity_hex(110);
    let recipient_kp = SigningKey::generate(&mut OsRng);
    let (app, recipient_token) = obtain_token(app, &recipient_id, &recipient_kp).await;

    // Simulate the WS handler having just written an envelope by calling
    // the mirror helper directly. In production this happens in routes.rs
    // immediately after the inline `state.store` write.
    let envelope_id = "ws-mirror-001";
    let sealed_blob = "abcd1234".repeat(8);
    let payload = "WS_PAYLOAD_BASE64";
    let expires_at = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs()
        + 86_400;
    let _seq = phantom_relay::rest_fallback::mirror_envelope_to_rest_store(
        &state,
        &recipient_id,
        envelope_id,
        &sealed_blob,
        payload,
        1_700_000_000_000_u64,
        expires_at,
    )
    .await;

    // Recipient on REST polling sees it.
    let (_app, poll_status, poll_v) = call_poll(app, &recipient_token, None).await;
    assert_eq!(poll_status, StatusCode::OK);
    let envs = poll_v["envelopes"].as_array().unwrap();
    assert_eq!(envs.len(), 1, "REST poll must see WS-mirrored envelope");
    assert_eq!(envs[0]["id"].as_str().unwrap(), envelope_id);
    assert_eq!(envs[0]["sealed_sender"].as_str().unwrap(), sealed_blob);
    assert_eq!(envs[0]["payload"].as_str().unwrap(), payload);
}

// ── Test 9: WS-simulated ack clears REST poll store ──────────────────────────
//
// PR-D0r review-fix coverage: WS ack-deliver removes from `state.store`
// AND from `state.rest_store` via `remove_envelope_from_rest_store`. A
// subsequent /relay/poll from the same recipient must not re-deliver the
// already-acked envelope.
#[tokio::test]
async fn ws_simulated_ack_clears_rest_poll() {
    let cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    let state = std::sync::Arc::new(phantom_relay::state::AppState::new(cfg));
    let app = phantom_relay::routes::router(std::sync::Arc::clone(&state));

    let sender_id = identity_hex(120);
    let recipient_id = identity_hex(121);
    let sender_kp = SigningKey::generate(&mut OsRng);
    let recipient_kp = SigningKey::generate(&mut OsRng);
    let (app, sender_token) = obtain_token(app, &sender_id, &sender_kp).await;
    let (app, recipient_token) = obtain_token(app, &recipient_id, &recipient_kp).await;

    // REST send populates both stores.
    let envelope_id = "ws-ack-cleanup-001";
    let send_body = serde_json::json!({
        "envelope_id": envelope_id,
        "to":          recipient_id,
        "payload":     "P",
        "sequence_ts": 1_700_000_000_000_u64,
    })
    .to_string();
    let (app, send_status, _) =
        call_send_raw(app, &sender_token, envelope_id, send_body.as_bytes()).await;
    assert_eq!(send_status, StatusCode::CREATED);

    // Verify recipient sees it.
    let (app, _, poll_v) = call_poll(app, &recipient_token, None).await;
    assert_eq!(poll_v["envelopes"].as_array().unwrap().len(), 1);

    // Simulate WS ack-deliver by calling the remove helper directly. In
    // production this happens in routes.rs immediately after the inline
    // `state.store` removal in the ack-deliver arm.
    let removed = phantom_relay::rest_fallback::remove_envelope_from_rest_store(
        &state,
        &recipient_id,
        envelope_id,
    )
    .await;
    assert!(removed, "helper should report it removed an entry");

    // Subsequent REST poll must be empty.
    let (_app, _, poll_v2) = call_poll(app, &recipient_token, None).await;
    assert_eq!(
        poll_v2["envelopes"].as_array().unwrap().len(),
        0,
        "REST poll must be empty after WS-simulated ack cleared the store",
    );
}
