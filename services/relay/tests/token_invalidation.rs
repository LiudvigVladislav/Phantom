// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Trek 2 Stage 1.x Lock-3 — `RestTokenStore::issue()` invalidation
//! invariant contract tests.
//!
//! The Lock-3 contract is the invariant, not a pre-locked structural
//! design: after concurrent `issue()` calls for the same identity,
//! exactly one returned token validates; the others return `None`.
//! Initial analysis suggests the existing `issue()` already delivers
//! this via write-lock removal of the prior token from `by_token`
//! before inserting the new token. These tests pin the contract; if
//! the concurrent-issue test fails, a structural refactor enters
//! scope.
//!
//! In-flight poll behavior is locked as b2:
//! - An in-flight long-poll on T1 (when T2 is issued mid-stream)
//!   COMPLETES NATURALLY on T1 and returns the envelope.
//! - The returned envelope is NOT ackable via T1 — ack is a separate
//!   HTTP request that re-validates the bearer against the current
//!   `TokenStore`. T1 was removed from `by_token` when T2 was issued,
//!   so any T1 ack returns 401.
//! - Ack must use T2. The legitimate client with T2 can drain the
//!   envelope; a stolen-T1 attacker reads it but cannot drain.
//!
//! These tests reuse the same `from_env_for_test()` helper that
//! poll_hold.rs uses; the deterministic test fixture key keeps the
//! Stage 1.x A1/A2 surfaces unchanged across runs.

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use ed25519_dalek::{Signature, Signer, SigningKey};
use rand::rngs::OsRng;
use serde_json::{json, Value};
use std::sync::Arc;
use tower::ServiceExt;

// ── Helpers — mirrored from poll_hold.rs to keep this file self-contained ───

fn build_app() -> axum::Router {
    let cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    phantom_relay::routes::router(state)
}

fn build_app_with_hold(
    hold_secs: u32,
) -> (axum::Router, Arc<phantom_relay::state::AppState>) {
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.poll_hold_secs = hold_secs;
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    let router = phantom_relay::routes::router(Arc::clone(&state));
    (router, state)
}

/// Distinct seed range from other test files to avoid challenge-cache
/// cross-contamination.
fn identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        *b = seed.wrapping_add(i as u8).wrapping_add(0xC0);
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

/// Force a fresh `issue()` by going through a NEW challenge fetch + sign + session call.
///
/// The challenge cache keys on the full identity, challenge, pubkey, signature
/// tuple, so a fresh challenge produces a fresh token even for the same identity
/// and signing key.
async fn obtain_fresh_token_via_new_challenge(
    app: axum::Router,
    identity: &str,
    signing_kp: &SigningKey,
) -> (axum::Router, String) {
    // Different challenge each call — fetch_challenge generates a fresh
    // 32-byte nonce per request, so the cache cannot hit.
    obtain_token(app, identity, signing_kp).await
}

async fn call_poll_raw(
    app: axum::Router,
    token: &str,
) -> (axum::Router, StatusCode) {
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

// ── Lock-3 contract tests ────────────────────────────────────────────────────

/// Issuing T2 for an identity invalidates T1 for that same identity.
/// This is the primary Lock-3 contract — without it, the inverse-DoS
/// window is wide open.
#[tokio::test]
async fn issue_new_token_invalidates_prior_for_same_identity() {
    let app = build_app();
    let identity = identity_hex(1);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);

    let (app, token_1) = obtain_token(app, &identity, &signing_kp).await;
    let (app, token_2) = obtain_fresh_token_via_new_challenge(app, &identity, &signing_kp).await;
    assert_ne!(token_1, token_2, "fresh challenge must produce a new token");

    // T1 must NOT validate after T2 is issued.
    let (app, status_t1) = call_poll_raw(app, &token_1).await;
    assert_eq!(
        status_t1,
        StatusCode::UNAUTHORIZED,
        "T1 must return 401 after T2 issued for same identity"
    );

    // T2 must validate.
    let (_app, status_t2) = call_poll_raw(app, &token_2).await;
    assert_eq!(
        status_t2,
        StatusCode::OK,
        "T2 must still validate (it is the current token)"
    );
}

/// Issuing T2 for identity X does NOT touch identity Y's token. Pins
/// per-identity isolation in the `by_identity` map.
#[tokio::test]
async fn issue_for_identity_x_does_not_affect_identity_y() {
    let app = build_app();
    let identity_x = identity_hex(2);
    let identity_y = identity_hex(3);
    let mut csprng = OsRng;
    let signing_x = SigningKey::generate(&mut csprng);
    let signing_y = SigningKey::generate(&mut csprng);

    let (app, token_y) = obtain_token(app, &identity_y, &signing_y).await;
    let (app, _token_x_1) = obtain_token(app, &identity_x, &signing_x).await;
    // Cycle X — issue X again, which invalidates the FIRST X token.
    let (app, _token_x_2) = obtain_fresh_token_via_new_challenge(app, &identity_x, &signing_x).await;

    // Y must STILL validate — the X reissue did not touch Y.
    let (_app, status_y) = call_poll_raw(app, &token_y).await;
    assert_eq!(
        status_y,
        StatusCode::OK,
        "Y token must survive an X-identity reissue"
    );
}

/// Replaying the SAME (challenge, signature) tuple within the
/// session-cache TTL returns the SAME token — the cache hit short-
/// circuits the `issue()` call entirely, so no invalidation happens.
/// This pins the retry-safety contract: a client that resends the same
/// /auth/session request (e.g. after a network blip mid-response) gets
/// its original token back, not 401.
#[tokio::test]
async fn retry_safety_cache_hit_does_not_invalidate_existing_token() {
    let app = build_app();
    let identity = identity_hex(4);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);

    // First session call — fresh issue.
    let (app, nonce_hex) = fetch_challenge(app, &identity).await;
    let (app, status_1, v_1) = call_session(app, &identity, &signing_kp, &nonce_hex).await;
    assert_eq!(status_1, StatusCode::OK);
    let token_1 = v_1["token"].as_str().unwrap().to_string();

    // Replay the SAME (challenge, signature) tuple — cache hit.
    let (app, status_2, v_2) = call_session(app, &identity, &signing_kp, &nonce_hex).await;
    assert_eq!(status_2, StatusCode::OK);
    let token_2 = v_2["token"].as_str().unwrap().to_string();
    assert_eq!(token_1, token_2, "cache replay must return the SAME token");

    // T1 must STILL validate — no invalidation happened on the replay
    // path because no fresh `issue()` ran.
    let (_app, status_poll) = call_poll_raw(app, &token_1).await;
    assert_eq!(
        status_poll,
        StatusCode::OK,
        "replayed token must continue to validate (cache hit, no reissue)"
    );
}

/// The concurrent-issue invariant test. Spawns N tasks that each call
/// `obtain_token` with a fresh challenge for the SAME identity, syncs
/// them on a barrier so they race into `issue()` as tightly as
/// possible, then asserts exactly ONE of the returned tokens validates
/// after `join_all`. If this test ever fails, the implementation
/// strategy moves from invariant-driven (doc-comment only) to
/// structural refactor of `RestTokenStore::issue()`.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn concurrent_issue_for_same_identity_is_serialised() {
    use tokio::sync::Barrier;

    let app = build_app();
    let identity = identity_hex(5);
    let mut csprng = OsRng;
    let signing_kp = Arc::new(SigningKey::generate(&mut csprng));

    let n_racers: usize = 3;
    let barrier = Arc::new(Barrier::new(n_racers));
    let mut handles = Vec::with_capacity(n_racers);
    for _ in 0..n_racers {
        let app_clone = app.clone();
        let identity_clone = identity.clone();
        let signing_clone = Arc::clone(&signing_kp);
        let barrier_clone = Arc::clone(&barrier);
        handles.push(tokio::spawn(async move {
            // Sync all racers on the barrier so they enter the session
            // round-trip at the same time. Each one fetches its OWN
            // fresh challenge so the cache cannot hit; the race is in
            // the underlying `issue()` calls.
            barrier_clone.wait().await;
            let (_app, token) =
                obtain_token(app_clone, &identity_clone, &signing_clone).await;
            token
        }));
    }
    let mut tokens = Vec::with_capacity(n_racers);
    for h in handles {
        tokens.push(h.await.expect("racer task panicked"));
    }

    // Now check which tokens validate. Exactly one MUST validate.
    let mut valid_count = 0usize;
    for token in &tokens {
        let (_app, status) = call_poll_raw(app.clone(), token).await;
        if status == StatusCode::OK {
            valid_count += 1;
        }
    }
    assert_eq!(
        valid_count, 1,
        "after {} concurrent issues for the same identity, exactly one \
         token must validate — got {} valid; tokens={:?}",
        n_racers, valid_count, tokens
    );
}

/// b2 in-flight contract — half 1. An in-flight long-poll on T1
/// completes naturally and returns 200, even when T2 is issued mid-
/// stream. The poll's bearer was validated at request start; the
/// long-held connection state does not re-validate, so the request
/// finishes on the original token.
#[tokio::test]
async fn t1_in_flight_poll_returns_200_then_t1_ack_returns_401() {
    let (app, _state) = build_app_with_hold(2); // 2 s hold so we have time to issue T2
    let identity = identity_hex(6);
    let mut csprng = OsRng;
    let signing_kp = Arc::new(SigningKey::generate(&mut csprng));

    let (app, token_1) = obtain_token(app, &identity, &signing_kp).await;

    // Spawn T1 poll WITH the opt-in header so it engages the hold loop.
    let app_for_poll = app.clone();
    let token_1_clone = token_1.clone();
    let t1_poll = tokio::spawn(async move {
        let res = app_for_poll
            .clone()
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/relay/poll")
                    .header("authorization", format!("Bearer {}", token_1_clone))
                    .header("x-phantom-long-poll", "1")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        res.status()
    });

    // Brief wait so the poll actually enters the hold loop before we
    // race the reissue. 300 ms is comfortably past auth + phase-1
    // checks on slow CI runners.
    tokio::time::sleep(tokio::time::Duration::from_millis(300)).await;

    // Issue T2 — invalidates T1 immediately in the by_token map.
    let (app, token_2) =
        obtain_fresh_token_via_new_challenge(app, &identity, &signing_kp).await;
    assert_ne!(token_1, token_2);

    // T1 poll must still return 200 — the hold completes naturally on
    // its already-validated request frame.
    let t1_poll_status = t1_poll.await.expect("T1 poll task panicked");
    assert_eq!(
        t1_poll_status,
        StatusCode::OK,
        "in-flight T1 poll must complete naturally with 200, NOT 401 mid-stream"
    );

    // But T1 ack must return 401 — `rest_ack_deliver` re-validates the
    // bearer against the current TokenStore, and T1 is no longer in
    // by_token.
    let (_app, ack_status) = call_ack_deliver(app, &token_1, "any-id").await;
    assert_eq!(
        ack_status,
        StatusCode::UNAUTHORIZED,
        "T1 ack after T2 issuance must return 401 — Lock-3 b2 contract"
    );
}

/// b2 in-flight contract — half 2. After T2 is issued, T2 can ack the
/// same envelope the in-flight T1 poll could have returned. Confirms
/// guardrail A (delivery never lost): the legitimate client with T2
/// can still complete the round trip. This is what closes the inverse-
/// DoS attack — a stolen-T1 attacker reads the envelope but cannot
/// drain it from the queue; the legitimate client with T2 can.
#[tokio::test]
async fn t2_ack_succeeds_after_token_rotation() {
    let app = build_app();
    let identity = identity_hex(7);
    let mut csprng = OsRng;
    let signing_kp = Arc::new(SigningKey::generate(&mut csprng));

    let (app, token_1) = obtain_token(app, &identity, &signing_kp).await;
    let (app, token_2) =
        obtain_fresh_token_via_new_challenge(app, &identity, &signing_kp).await;
    assert_ne!(token_1, token_2);

    // T2 ack must succeed. The envelope id does not have to exist in
    // the store — `rest_ack_deliver` validates the token at request
    // start, and on a missing envelope the handler simply no-ops and
    // returns 200. The contract this test pins is the auth gate, not
    // the dedup gate.
    let (_app, status) = call_ack_deliver(app, &token_2, "any-id").await;
    assert_eq!(
        status,
        StatusCode::OK,
        "T2 ack must succeed — guardrail A: legitimate client retains \
         ability to drain envelopes after token rotation"
    );
}

/// Tight-race regression for the `RestTokenStore::issue()` atomicity
/// invariant. Bypasses the HTTP layer so the only thing being raced
/// is the `issue()` method itself; the previous HTTP-level test passed
/// because the cache-fetch + signature-verify path serialised racers
/// before they reached `issue()`.
///
/// Without atomic acquisition of both `by_token` and `by_identity`
/// inside one critical section, the following interleave leaks an
/// extra valid token:
///
/// ```text
/// Thread A: by_token.write() → by_identity.read() (None) →
///           by_token.insert(token_A) → release by_token
/// Thread B: by_token.write() → by_identity.read() (still None) →
///           by_token.insert(token_B) → release by_token
/// Thread A: by_identity.write() → set X → token_A
/// Thread B: by_identity.write() → set X → token_B
/// ```
///
/// Result: BOTH tokens validate. The Lock-3 invariant ("only one
/// current token per identity") is violated. The fix is to hold both
/// write locks across the entire mutation.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn issue_is_atomic_under_concurrent_calls_for_same_identity_no_http() {
    use phantom_relay::rest_fallback::RestTokenStore;
    use tokio::sync::Barrier;
    let store = Arc::new(RestTokenStore::new());
    let identity = "f".repeat(64);

    // Many concurrent racers maximise the chance of hitting the
    // interleave window. Multiple rounds raise it further.
    const N_RACERS: usize = 50;
    const N_ROUNDS: usize = 20;

    for _ in 0..N_ROUNDS {
        let barrier = Arc::new(Barrier::new(N_RACERS));
        let mut handles = Vec::with_capacity(N_RACERS);
        for _ in 0..N_RACERS {
            let s = Arc::clone(&store);
            let b = Arc::clone(&barrier);
            let id = identity.clone();
            handles.push(tokio::spawn(async move {
                b.wait().await;
                s.issue(&id).await
            }));
        }
        let mut tokens = Vec::with_capacity(N_RACERS);
        for h in handles {
            tokens.push(h.await.unwrap());
        }

        // After this round, AT MOST ONE token may validate for the
        // identity. Every issue() must structurally remove the prior
        // token from `by_token` before inserting its own, atomically
        // with the `by_identity` update. Any leaked token from a
        // partial interleave would validate alongside the round's
        // winner.
        let mut valid_count = 0;
        for (t, _) in &tokens {
            if store.validate(t).await.is_some() {
                valid_count += 1;
            }
        }
        assert_eq!(
            valid_count, 1,
            "exactly one token must validate after a {}-racer round; \
             got {} valid",
            N_RACERS, valid_count
        );
    }
}

/// Cache-hit regression. After a token rotation, a replay of the
/// ORIGINAL `(challenge, signature)` tuple must NOT mint or return
/// the new current token — that would defeat Lock-3 invalidation,
/// because anyone who captured the original tuple could keep
/// retrieving whichever token the identity is currently bound to.
///
/// The correct behaviour is: the cache hit returns the originally
/// issued token if (and only if) that exact token is still live in
/// the token store. Once a fresh challenge issues a new token, the
/// original is removed from `by_token`, the cached pointer is stale,
/// and the replay must not return the new token.
#[tokio::test]
async fn auth_session_replay_after_rotation_does_not_return_new_token() {
    let app = build_app();
    let identity = identity_hex(9);
    let mut csprng = OsRng;
    let signing_kp = SigningKey::generate(&mut csprng);

    // Original session — establish T1 + populate the challenge cache
    // with this exact (identity, challenge_1, sig_1) tuple.
    let (app, nonce_1) = fetch_challenge(app, &identity).await;
    let (app, status_1, v_1) = call_session(app, &identity, &signing_kp, &nonce_1).await;
    assert_eq!(status_1, StatusCode::OK);
    let token_1 = v_1["token"].as_str().unwrap().to_string();

    // Fresh challenge → fresh issue → T2 rotation. T1 is now invalid.
    let (app, token_2) =
        obtain_fresh_token_via_new_challenge(app, &identity, &signing_kp).await;
    assert_ne!(token_1, token_2);

    // T1 must NOT validate after rotation.
    let (app, status_t1) = call_poll_raw(app, &token_1).await;
    assert_eq!(status_t1, StatusCode::UNAUTHORIZED);

    // Now the attacker replays the ORIGINAL (challenge_1, sig_1) tuple.
    // The challenge cache hits — it was populated by the original
    // session call — but it MUST NOT return T2 (the post-rotation
    // current token). The thing that MUST NOT happen is that the
    // replay successfully retrieves a working session token after
    // the original one was rotated away.
    let (_app, status_replay, v_replay) =
        call_session(app, &identity, &signing_kp, &nonce_1).await;

    if status_replay == StatusCode::OK {
        let replay_token = v_replay["token"].as_str().unwrap_or("").to_string();
        assert_ne!(
            replay_token, token_2,
            "replay of original (challenge, sig) MUST NOT return the post-rotation token T2"
        );
        // If the replay returns the cached T1, the cached T1 itself
        // must not validate (rotation removed it).
        assert_eq!(replay_token, token_1, "replay must not mint a fresh token");
    }
    // Non-200 status is also acceptable — we do not pin the exact
    // code (410 Gone / 401 Unauthorized are both reasonable).
}

/// Inverse-DoS regression. A stolen T1 attacker triggering T2 issuance
/// (via the legitimate client's reauth flow) cannot then race to ack
/// an envelope on T1 — `rest_ack_deliver` validates against the
/// current TokenStore at request start. Pins the contract against
/// future drift, e.g. a caching optimization in ack-deliver that
/// preserved T1's auth state.
#[tokio::test]
async fn inverse_dos_ack_blocked_after_token_rotation() {
    let app = build_app();
    let identity = identity_hex(8);
    let mut csprng = OsRng;
    let signing_kp = Arc::new(SigningKey::generate(&mut csprng));

    let (app, token_1) = obtain_token(app, &identity, &signing_kp).await;
    // Simulate the legitimate client doing reauth — T2 issued.
    let (app, _token_2) =
        obtain_fresh_token_via_new_challenge(app, &identity, &signing_kp).await;

    // Attacker attempts ack via stolen T1 — must be blocked.
    let (_app, status) = call_ack_deliver(app, &token_1, "any-envelope").await;
    assert_eq!(
        status,
        StatusCode::UNAUTHORIZED,
        "T1 ack after T2 issuance must return 401 — inverse-DoS regression"
    );
}
