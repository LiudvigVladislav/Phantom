// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! PR-0 ingress hardening — M-1 envelope_id shape + Idempotency-Key
//! header shape + header/body id equality. Integration tests exercise
//! the REST `/relay/send` path end-to-end so the validator wiring
//! (rest_fallback.rs:1907 header shape + :1998 body shape + :2016
//! header/body match) is proven, not just the pure functions in
//! `seq_mac.rs`.
//!
//! Layer discipline:
//!   * Pure-function unit tests for `is_valid_envelope_id` live in
//!     `src/seq_mac.rs`.
//!   * This file drives the actual REST handler through
//!     `tower::ServiceExt::oneshot`, mirroring
//!     `rest_fallback_endpoints.rs`.
//!
//! WS-side coverage: the WS Send arm at `routes.rs:1007` uses the same
//! shared validator, so a WS-layer duplicate suite is superfluous. The
//! REST cases here plus the pure-function unit tests provide full
//! coverage of the M-1 ingress guards.
//!
//! A-6 recipient-hex tests land in a follow-up commit (this file's
//! sibling additions there) so per-commit revertability is preserved.

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use ed25519_dalek::{Signer, Signature, SigningKey};
use rand::rngs::OsRng;
use serde_json::{json, Value};
use std::sync::Arc;
use tower::ServiceExt;

// ── Helpers (mirrors rest_fallback_endpoints.rs) ────────────────────────────

fn build_app() -> axum::Router {
    let cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    phantom_relay::routes::router(state)
}

/// 64-hex-char identity derived from a seed byte. Seeds 40-79 reserved
/// for this file so they never collide with rest_fallback_endpoints.rs
/// (which uses 20-39) or prekey_endpoints.rs (20-25).
fn identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        *b = seed.wrapping_add(i as u8).wrapping_add(0xA0);
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
    assert_eq!(res.status(), StatusCode::OK, "challenge fetch failed");
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
    assert_eq!(status, StatusCode::OK, "session failed");
    let bytes = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    let token = v["token"].as_str().unwrap().to_string();
    (app, token)
}

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

/// Build a `/relay/send` JSON body with the given envelope_id + `to`.
fn send_body(envelope_id: &str, to: &str) -> String {
    json!({
        "envelope_id": envelope_id,
        "to":          to,
        "payload":     "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "sequence_ts": 1_700_000_000_000_u64,
    })
    .to_string()
}

// ═══════════════════════════════════════════════════════════════════════
// M-1 envelope_id (body) ingress tests
// ═══════════════════════════════════════════════════════════════════════

/// Reject a body envelope_id that fails the canonical shape. Uses a
/// static valid Idempotency-Key header so the header-shape guard
/// passes; cache lookup misses because the header value is fresh
/// each run of `cargo test`; then the body parser reaches the
/// envelope_id validator which rejects.
async fn assert_rejects_envelope_id(app: axum::Router, envelope_id: &str) -> axum::Router {
    let sender_id = identity_hex(40);
    let recipient_id = identity_hex(41);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;

    let body = send_body(envelope_id, &recipient_id);
    let idem_key = "test-idem-static-header";
    let (app, status, v) = call_send_raw(app, &token, idem_key, body.as_bytes()).await;
    assert_eq!(
        status,
        StatusCode::BAD_REQUEST,
        "expected 400 for envelope_id={:?}, got status={:?} body={:?}",
        envelope_id, status, v,
    );
    let err = v["error"].as_str().unwrap_or("");
    assert!(
        err.starts_with("envelope_id"),
        "expected error string starting with `envelope_id`, got {:?}",
        err,
    );
    app
}

#[tokio::test]
async fn envelope_id_reject_control_chars_cr_lf() {
    let app = build_app();
    let _ = assert_rejects_envelope_id(app, "a\r\nb").await;
}

#[tokio::test]
async fn envelope_id_reject_path_separator_forward_slash() {
    let app = build_app();
    let _ = assert_rejects_envelope_id(app, "foo/bar").await;
}

#[tokio::test]
async fn envelope_id_reject_path_separator_backslash() {
    let app = build_app();
    let _ = assert_rejects_envelope_id(app, "foo\\bar").await;
}

#[tokio::test]
async fn envelope_id_reject_nul_byte() {
    let app = build_app();
    let _ = assert_rejects_envelope_id(app, "foo\0bar").await;
}

#[tokio::test]
async fn envelope_id_reject_dot_dot_path_traversal() {
    let app = build_app();
    let _ = assert_rejects_envelope_id(app, "../etc/passwd").await;
}

#[tokio::test]
async fn envelope_id_reject_utf8_multibyte() {
    let app = build_app();
    let _ = assert_rejects_envelope_id(app, "ёlo").await;
}

#[tokio::test]
async fn envelope_id_reject_over_practical_ceiling_129() {
    let app = build_app();
    let bad = "a".repeat(129);
    let _ = assert_rejects_envelope_id(app, &bad).await;
}

async fn assert_accepts_envelope_id(app: axum::Router, envelope_id: &str, seed_base: u8) -> axum::Router {
    let sender_id = identity_hex(seed_base);
    let recipient_id = identity_hex(seed_base.wrapping_add(1));
    let signing_kp = SigningKey::generate(&mut OsRng);
    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;
    let body = send_body(envelope_id, &recipient_id);
    // Header must equal body envelope_id (M-1 match rule); passing the
    // same value satisfies both surfaces.
    let (app, status, v) = call_send_raw(app, &token, envelope_id, body.as_bytes()).await;
    assert_eq!(
        status,
        StatusCode::CREATED,
        "expected 201 for envelope_id={:?}, got status={:?} body={:?}",
        envelope_id, status, v,
    );
    app
}

#[tokio::test]
async fn envelope_id_accept_32_hex_lowercase() {
    let app = build_app();
    let _ = assert_accepts_envelope_id(app, &"a".repeat(32), 50).await;
}

#[tokio::test]
async fn envelope_id_accept_uuid_with_hyphens() {
    let app = build_app();
    let _ = assert_accepts_envelope_id(app, "550e8400-e29b-41d4-a716-446655440000", 52).await;
}

#[tokio::test]
async fn envelope_id_accept_ulid_alphanumeric() {
    let app = build_app();
    let _ = assert_accepts_envelope_id(app, "01HZY6BQPWX7ABCDEF0123456K", 54).await;
}

#[tokio::test]
async fn envelope_id_accept_at_practical_ceiling_128() {
    let app = build_app();
    let ok = "a".repeat(128);
    let _ = assert_accepts_envelope_id(app, &ok, 56).await;
}

// ─────────────────────────────────────────────────────────────────────
// M-1 Idempotency-Key HEADER shape guard.
//
// The header is used in the idempotency cache key AND in tracing log
// lines BEFORE the body is parsed. A `\r\n`-laden header would leak
// straight into logs without the validator; header shape rejection
// must fire independently of body content.
// ─────────────────────────────────────────────────────────────────────

/// Reject a header value that is HTTP-legal but envelope-canonical
/// INVALID. The body carries a fresh valid envelope_id; the test
/// exists to prove the header validator fires BEFORE the body reaches
/// the parser.
async fn assert_rejects_idem_header(app: axum::Router, idem_key: &str) -> axum::Router {
    let sender_id = identity_hex(58);
    let recipient_id = identity_hex(59);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;

    let body_env_id = "valid-body-envelope-id";
    let body = send_body(body_env_id, &recipient_id);
    let (app, status, v) = call_send_raw(app, &token, idem_key, body.as_bytes()).await;
    assert_eq!(
        status,
        StatusCode::BAD_REQUEST,
        "expected 400 for Idempotency-Key={:?}, got status={:?} body={:?}",
        idem_key, status, v,
    );
    let err = v["error"].as_str().unwrap_or("");
    assert!(
        err.starts_with("Idempotency-Key"),
        "expected error string starting with `Idempotency-Key`, got {:?}",
        err,
    );
    app
}

#[tokio::test]
async fn idem_header_reject_path_separator_forward_slash() {
    let app = build_app();
    let _ = assert_rejects_idem_header(app, "foo/bar").await;
}

#[tokio::test]
async fn idem_header_reject_path_separator_backslash() {
    let app = build_app();
    let _ = assert_rejects_idem_header(app, "foo\\bar").await;
}

#[tokio::test]
async fn idem_header_reject_whitespace() {
    let app = build_app();
    let _ = assert_rejects_idem_header(app, "foo bar").await;
}

#[tokio::test]
async fn idem_header_reject_dot_dot_path_traversal() {
    let app = build_app();
    let _ = assert_rejects_idem_header(app, "../etc/passwd").await;
}

#[tokio::test]
async fn idem_header_reject_over_practical_ceiling_129() {
    let app = build_app();
    let bad = "a".repeat(129);
    let _ = assert_rejects_idem_header(app, &bad).await;
}

// ─────────────────────────────────────────────────────────────────────
// M-1 Idempotency-Key ↔ body envelope_id equality pin. If the header
// names a different id than the body, one Idempotency-Key value would
// end up gating multiple distinct envelopes across body substitutions
// — breaking the dedup contract operators grep by envelope_id.
// ─────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn idem_header_body_mismatch_rejected() {
    let app = build_app();
    let sender_id = identity_hex(80);
    let recipient_id = identity_hex(81);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;

    // Both values individually pass the canonical shape guard —
    // rejection MUST come from the mismatch check specifically.
    let header_id = "valid-header-id-alpha";
    let body_env_id = "valid-body-id-bravo";
    let body = send_body(body_env_id, &recipient_id);
    let (_app, status, v) = call_send_raw(app, &token, header_id, body.as_bytes()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "expected 400, got {:?} {:?}", status, v);
    let err = v["error"].as_str().unwrap_or("");
    assert!(
        err.starts_with("Idempotency-Key") && err.contains("equal"),
        "expected error indicating header-body mismatch, got {:?}",
        err,
    );
}

#[tokio::test]
async fn idem_header_body_match_accepted() {
    // Positive-shape guard: when header == body id AND both pass the
    // canonical shape, the send lands normally (201 CREATED).
    let app = build_app();
    let sender_id = identity_hex(82);
    let recipient_id = identity_hex(83);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;
    let env_id = "test-idem-match-happy-path";
    let body = send_body(env_id, &recipient_id);
    let (_app, status, v) = call_send_raw(app, &token, env_id, body.as_bytes()).await;
    assert_eq!(status, StatusCode::CREATED, "expected 201, got {:?} {:?}", status, v);
}

// ═══════════════════════════════════════════════════════════════════════
// A-6 recipient hex ingress tests
//
// `identity_hex(seed)` already emits lowercase, so the accept cases
// exercise the production path. The reject cases build uppercase /
// mixed-case strings explicitly.
// ═══════════════════════════════════════════════════════════════════════

async fn assert_rejects_to(app: axum::Router, to: &str) -> axum::Router {
    let sender_id = identity_hex(60);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;

    let envelope_id = "test-a6-reject";
    let body = send_body(envelope_id, to);
    let (app, status, v) = call_send_raw(app, &token, envelope_id, body.as_bytes()).await;
    assert_eq!(
        status,
        StatusCode::BAD_REQUEST,
        "expected 400 for to={:?}, got status={:?} body={:?}",
        to, status, v,
    );
    let err = v["error"].as_str().unwrap_or("");
    assert!(
        err.starts_with("to"),
        "expected error string starting with `to`, got {:?}",
        err,
    );
    app
}

#[tokio::test]
async fn recipient_hex_reject_uppercase_all() {
    let app = build_app();
    let _ = assert_rejects_to(app, &"A".repeat(64)).await;
}

#[tokio::test]
async fn recipient_hex_reject_mixed_case() {
    let app = build_app();
    let _ = assert_rejects_to(app, &"aA".repeat(32)).await;
}

#[tokio::test]
async fn recipient_hex_reject_wrong_length_63() {
    // Regression check: shape rejection still fires when length is
    // wrong, regardless of case. Pre-existing behaviour preserved.
    let app = build_app();
    let _ = assert_rejects_to(app, &"a".repeat(63)).await;
}

#[tokio::test]
async fn recipient_hex_accept_lowercase_64_hex() {
    let app = build_app();
    let sender_id = identity_hex(70);
    let recipient_id = identity_hex(71); // already lowercase from hex::encode
    let signing_kp = SigningKey::generate(&mut OsRng);
    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;
    let envelope_id = "test-a6-lower-happy";
    let body = send_body(envelope_id, &recipient_id);
    let (_app, status, v) = call_send_raw(app, &token, envelope_id, body.as_bytes()).await;
    assert_eq!(status, StatusCode::CREATED, "expected 201, got {:?} {:?}", status, v);
}

#[tokio::test]
async fn recipient_hex_accept_all_zero_lowercase() {
    let app = build_app();
    let sender_id = identity_hex(72);
    let recipient_id = "0".repeat(64);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let (app, token) = obtain_token(app, &sender_id, &signing_kp).await;
    let envelope_id = "test-a6-zero-recipient";
    let body = send_body(envelope_id, &recipient_id);
    let (_app, status, v) = call_send_raw(app, &token, envelope_id, body.as_bytes()).await;
    assert_eq!(status, StatusCode::CREATED, "expected 201, got {:?} {:?}", status, v);
}
