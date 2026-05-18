// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Integration tests for PR-M1r: encrypted media upload/download endpoints.
//!
//! All nine cases from the acceptance criteria:
//!
//!   1. Upload + fetch happy path: POST chunks 0/1/2 → 201 each; GET → 200.
//!   2. Idempotent retry: same idx + same body → 200 duplicate.
//!   3. Conflict: same (media_id, idx), different ciphertext → 409.
//!   4. Body too large: body > 3072 → 413 body_too_large.
//!   5. Too many chunks: total=257 → 413 too_many_chunks.
//!   6. Media quota exceeded: chunks summing to > 1 MiB → 413.
//!   7. GET 404: non-existent (media_id, idx) → 404.
//!   8. Auth: POST/GET without Bearer → 401.
//!   9. idx out of range: idx >= total → 400.
//!
//! Uses the `tower::ServiceExt::oneshot` pattern (no TCP, hermetic).

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use base64::Engine as _;
use ed25519_dalek::{Signer, Signature, SigningKey};
use rand::rngs::OsRng;
use serde_json::{json, Value};
use std::sync::Arc;
use tower::ServiceExt;

// ── Helpers ───────────────────────────────────────────────────────────────────

fn build_app() -> axum::Router {
    build_app_with_config(phantom_relay::config::RelayConfig::from_env_for_test())
}

fn build_app_with_config(cfg: phantom_relay::config::RelayConfig) -> axum::Router {
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    phantom_relay::routes::router(state)
}

/// Unique 64-hex-char identity (seeds chosen to not clash with other test files).
fn identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        *b = seed.wrapping_add(i as u8).wrapping_add(0xD0);
    }
    hex::encode(buf)
}

/// A deterministic media_id string of exactly 32 characters.
fn media_id(seed: u8) -> String {
    // 32 hex chars = 16 bytes, well under the 64-char cap.
    let mut buf = [0u8; 16];
    for (i, b) in buf.iter_mut().enumerate() {
        *b = seed.wrapping_add(i as u8).wrapping_add(0xCC);
    }
    hex::encode(buf)
}

/// Obtain a bearer token: challenge → sign → session.
async fn obtain_token(app: axum::Router, identity: &str, signing_kp: &SigningKey) -> (axum::Router, String) {
    // GET /auth/challenge
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

    // POST /auth/session
    let nonce_vec = hex::decode(&nonce_hex).unwrap();
    let nonce_arr: [u8; 32] = nonce_vec.try_into().unwrap();
    let sig: Signature = signing_kp.sign(&nonce_arr);
    let req_body = json!({
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
                .body(Body::from(req_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = serde_json::from_slice(&body).unwrap();
    let token = v["token"].as_str().unwrap().to_string();
    (app, token)
}

/// POST /media/upload-chunk. Returns (app, status, response_body).
///
/// N4 / forwards-compat: the helper still sends `idempotency_key` in the
/// JSON body to confirm that the server accepts-and-ignores unknown fields
/// (serde's default when `deny_unknown_fields` is not set on the struct).
async fn upload_chunk(
    app: axum::Router,
    token: &str,
    media_id: &str,
    idx: u32,
    total: u32,
    ciphertext_b64: &str,
) -> (axum::Router, StatusCode, Value) {
    let idem_key = format!("{}:{}", media_id, idx);
    let req_body = json!({
        "media_id":        media_id,
        "idx":             idx,
        "total":           total,
        "ciphertext_b64":  ciphertext_b64,
        // Kept intentionally — server must accept-and-ignore (N4 forwards-compat).
        "idempotency_key": idem_key,
    });
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {}", token))
                .body(Body::from(req_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let bytes = to_bytes(res.into_body(), 8192).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    (app, status, v)
}

/// GET /media/chunk/{media_id}/{idx}. Returns (app, status, response_body).
async fn download_chunk(
    app: axum::Router,
    token: &str,
    media_id: &str,
    idx: u32,
) -> (axum::Router, StatusCode, Value) {
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/media/chunk/{}/{}", media_id, idx))
                .header("authorization", format!("Bearer {}", token))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let bytes = to_bytes(res.into_body(), 65_536).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    (app, status, v)
}

/// Encode arbitrary bytes as standard base64.
fn b64(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

// ── Test 1: upload + fetch happy path ────────────────────────────────────────

#[tokio::test]
async fn test_upload_and_fetch_happy_path() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x01);
    let mid = media_id(0x01);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    // Upload chunks 0, 1, 2 with total=3.
    let ct0 = b64(b"encrypted-chunk-zero");
    let ct1 = b64(b"encrypted-chunk-one");
    let ct2 = b64(b"encrypted-chunk-two");

    let (app, status, v) = upload_chunk(app.clone(), &token, &mid, 0, 3, &ct0).await;
    assert_eq!(status, StatusCode::CREATED, "chunk 0: {:?}", v);
    assert_eq!(v["status"], "stored");
    assert_eq!(v["idx"], 0);

    let (app, status, v) = upload_chunk(app.clone(), &token, &mid, 1, 3, &ct1).await;
    assert_eq!(status, StatusCode::CREATED, "chunk 1: {:?}", v);
    assert_eq!(v["status"], "stored");
    assert_eq!(v["idx"], 1);

    let (app, status, v) = upload_chunk(app.clone(), &token, &mid, 2, 3, &ct2).await;
    assert_eq!(status, StatusCode::CREATED, "chunk 2: {:?}", v);
    assert_eq!(v["status"], "stored");
    assert_eq!(v["idx"], 2);

    // Fetch each chunk and verify content + total.
    let (app, status, v) = download_chunk(app.clone(), &token, &mid, 0).await;
    assert_eq!(status, StatusCode::OK, "GET chunk 0: {:?}", v);
    assert_eq!(v["ciphertext_b64"], ct0);
    assert_eq!(v["total"], 3);

    let (app, status, v) = download_chunk(app.clone(), &token, &mid, 1).await;
    assert_eq!(status, StatusCode::OK, "GET chunk 1: {:?}", v);
    assert_eq!(v["ciphertext_b64"], ct1);
    assert_eq!(v["total"], 3);

    let (_app, status, v) = download_chunk(app, &token, &mid, 2).await;
    assert_eq!(status, StatusCode::OK, "GET chunk 2: {:?}", v);
    assert_eq!(v["ciphertext_b64"], ct2);
    assert_eq!(v["total"], 3);
}

// ── Test 2: idempotent retry ──────────────────────────────────────────────────

#[tokio::test]
async fn test_idempotent_retry() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x02);
    let mid = media_id(0x02);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let ct = b64(b"idempotent-ciphertext");

    // First upload → 201 stored.
    let (app, status, v) = upload_chunk(app.clone(), &token, &mid, 0, 1, &ct).await;
    assert_eq!(status, StatusCode::CREATED, "{:?}", v);
    assert_eq!(v["status"], "stored");

    // Second call with same body → 200 duplicate.
    let (_app, status, v) = upload_chunk(app, &token, &mid, 0, 1, &ct).await;
    assert_eq!(status, StatusCode::OK, "{:?}", v);
    assert_eq!(v["status"], "duplicate");
    assert_eq!(v["idx"], 0);
}

// ── Test 3: conflict — same (media_id, idx), different ciphertext ─────────────

#[tokio::test]
async fn test_ciphertext_mismatch_conflict() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x03);
    let mid = media_id(0x03);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let ct_a = b64(b"ciphertext-version-A");
    let ct_b = b64(b"ciphertext-version-B");

    // First upload — stored.
    let (app, status, _) = upload_chunk(app.clone(), &token, &mid, 0, 2, &ct_a).await;
    assert_eq!(status, StatusCode::CREATED);

    // Second upload with different ciphertext → 409 ciphertext_mismatch.
    let (app, status, v) = upload_chunk(app.clone(), &token, &mid, 0, 2, &ct_b).await;
    assert_eq!(status, StatusCode::CONFLICT, "{:?}", v);
    assert_eq!(v["error"], "ciphertext_mismatch");

    // N6: verify the 409 path did NOT overwrite the stored chunk — original
    // ciphertext must still be present.
    let (_app, status, v) = download_chunk(app, &token, &mid, 0).await;
    assert_eq!(status, StatusCode::OK, "GET after conflict: {:?}", v);
    assert_eq!(
        v["ciphertext_b64"], ct_a,
        "stored ciphertext must be the original (ct_a), not the rejected ct_b"
    );
}

// ── Test 4: body too large ────────────────────────────────────────────────────

#[tokio::test]
async fn test_body_too_large() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x04);
    let mid = media_id(0x04);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    // Build a ciphertext_b64 whose JSON body will exceed 3072 bytes.
    // The JSON wrapper is roughly 120 bytes; we need ciphertext_b64 to push it over.
    // 3072 - 120 = 2952 bytes of base64 → a raw byte sequence of ~2214 bytes
    // encodes to ~2952 base64 chars. Use 2300 raw bytes to be comfortably over.
    let big_ct = b64(&vec![0xAB_u8; 2300]);
    let idem_key = format!("{}:0", mid);
    let req_body = json!({
        "media_id":        mid,
        "idx":             0_u32,
        "total":           1_u32,
        "ciphertext_b64":  big_ct,
        "idempotency_key": idem_key,
    });
    let body_str = req_body.to_string();
    assert!(
        body_str.len() > 3072,
        "test setup: body must be > 3072 bytes, got {}",
        body_str.len()
    );

    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {}", token))
                .body(Body::from(body_str))
                .unwrap(),
        )
        .await
        .unwrap();
    // With the route-level DefaultBodyLimit in place (B2), the HTTP framing
    // layer rejects the body before the handler runs — so the response body
    // may not be JSON (axum emits a plain 413 from the extractor middleware).
    // We assert only the status code here; Test 11 (test_http_layer_body_limit)
    // separately verifies the HTTP-layer behaviour vs. the in-handler path.
    assert_eq!(res.status(), StatusCode::PAYLOAD_TOO_LARGE);
}

// ── Test 5: too many chunks ───────────────────────────────────────────────────

#[tokio::test]
async fn test_too_many_chunks() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x05);
    let mid = media_id(0x05);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let ct = b64(b"small-chunk");
    // total=257 exceeds the 256-chunk cap.
    let (_app, status, v) = upload_chunk(app, &token, &mid, 0, 257, &ct).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE, "{:?}", v);
    assert_eq!(v["error"], "too_many_chunks", "{:?}", v);
}

// ── Test 6: media quota exceeded ─────────────────────────────────────────────

#[tokio::test]
async fn test_media_quota_exceeded() {
    // Use a very small max_media_bytes so we can trigger the quota without
    // hitting the body-size cap. Each chunk below carries 400 raw bytes;
    // 4 chunks = 1600 bytes > 1000-byte quota.
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.max_media_bytes = 1_000;
    let app = build_app_with_config(cfg);

    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x06);
    let mid = media_id(0x06);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    // 400 raw bytes → ~536 base64 chars; JSON body well under 3072.
    let chunk_raw = vec![0xCD_u8; 400];
    let chunk_b64 = b64(&chunk_raw);

    let mut last_status = StatusCode::CREATED;
    let mut last_v = Value::Null;

    // Upload chunks until the quota fires. With max_media_bytes=1000 and 400
    // bytes/chunk, chunk 0+1 = 800 bytes (ok), chunk 2 would push to 1200 → rejected.
    for idx in 0u32..4 {
        let req_body = json!({
            "media_id":        mid,
            "idx":             idx,
            "total":           10_u32,
            "ciphertext_b64":  chunk_b64,
            "idempotency_key": format!("{}:{}", mid, idx),
        });
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/media/upload-chunk")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", token))
                    .body(Body::from(req_body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        last_status = res.status();
        let bytes = to_bytes(res.into_body(), 8192).await.unwrap();
        last_v = serde_json::from_slice(&bytes).unwrap();
        if last_status == StatusCode::PAYLOAD_TOO_LARGE {
            break;
        }
    }

    assert_eq!(
        last_status,
        StatusCode::PAYLOAD_TOO_LARGE,
        "expected quota rejection, got {:?}",
        last_v
    );
    assert_eq!(last_v["error"], "media_quota_exceeded", "{:?}", last_v);
}

// ── Test 7: GET 404 ───────────────────────────────────────────────────────────

#[tokio::test]
async fn test_get_not_found() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x07);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let nonexistent = media_id(0xEE);
    let (_app, status, v) = download_chunk(app, &token, &nonexistent, 0).await;
    assert_eq!(status, StatusCode::NOT_FOUND, "{:?}", v);
    assert_eq!(v["error"], "not_found");
}

// ── Test 8: auth required ─────────────────────────────────────────────────────

#[tokio::test]
async fn test_auth_required() {
    let app = build_app();
    let mid = media_id(0x08);
    let ct = b64(b"payload");
    let idem_key = format!("{}:0", mid);

    // POST without token → 401.
    let req_body = json!({
        "media_id":        mid,
        "idx":             0_u32,
        "total":           1_u32,
        "ciphertext_b64":  ct,
        "idempotency_key": idem_key,
    });
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                // no Authorization header
                .body(Body::from(req_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::UNAUTHORIZED, "POST without token should be 401");

    // GET without token → 401.
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/media/chunk/{}/0", mid))
                // no Authorization header
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::UNAUTHORIZED, "GET without token should be 401");
}

// ── Test 9: idx out of range ──────────────────────────────────────────────────

#[tokio::test]
async fn test_idx_out_of_range() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x09);
    let mid = media_id(0x09);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let ct = b64(b"chunk-data");
    // idx == total is out of range (valid range is 0..total-1).
    let (_app, status, v) = upload_chunk(app, &token, &mid, 3, 3, &ct).await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "{:?}", v);
    // The error text should mention the constraint.
    assert!(
        v["error"].as_str().map(|s| s.contains("idx")).unwrap_or(false),
        "expected idx error, got {:?}",
        v
    );
}

// ── Test 10 (B1): total mismatch across chunks ────────────────────────────────

#[tokio::test]
async fn test_total_mismatch_conflict() {
    // B1: upload chunk 0 with total=3, then chunk 1 with total=10.
    // The relay must reject the second upload with 409 total_mismatch and
    // leave chunk 0 intact with its original total=3.
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x0A);
    let mid = media_id(0x0A);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let ct0 = b64(b"chunk-zero-total3");
    let ct1 = b64(b"chunk-one-total10");

    // Upload chunk 0 with total=3 — must succeed.
    let (app, status, v) = upload_chunk(app.clone(), &token, &mid, 0, 3, &ct0).await;
    assert_eq!(status, StatusCode::CREATED, "chunk 0 should be stored: {:?}", v);

    // Upload chunk 1 with total=10 — must be rejected with 409 total_mismatch.
    let (app, status, v) = upload_chunk(app.clone(), &token, &mid, 1, 10, &ct1).await;
    assert_eq!(status, StatusCode::CONFLICT, "total mismatch must be 409: {:?}", v);
    assert_eq!(v["error"], "total_mismatch", "wrong error key: {:?}", v);

    // Verify chunk 0 is still present with total=3 and original ciphertext.
    let (_app, status, v) = download_chunk(app, &token, &mid, 0).await;
    assert_eq!(status, StatusCode::OK, "chunk 0 must survive the rejected upload: {:?}", v);
    assert_eq!(v["total"], 3, "total must be original 3, not 10: {:?}", v);
    assert_eq!(v["ciphertext_b64"], ct0, "ciphertext must be original ct0: {:?}", v);
}

// ── Test 11 (B2): HTTP-layer body limit fires before handler ──────────────────

#[tokio::test]
async fn test_http_layer_body_limit() {
    // B2: the route-level DefaultBodyLimit::max(3072) must reject oversized
    // bodies at the HTTP framing layer, BEFORE the handler buffers them.
    // The HTTP-layer 413 has no JSON body (it is produced by axum's extractor
    // middleware, not by the handler), so we only assert the status code.
    // This is distinct from the in-handler 413 (which carries {"error":"body_too_large"}).
    //
    // We send a raw body of 3073 bytes (exactly 1 byte over the limit) with a
    // valid-ish content-type but NO auth header — the body limit layer runs
    // before auth. The response must be 413 regardless of auth state.
    let app = build_app();

    let oversized_body = vec![b'X'; 3073];

    let res = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                // No auth header — body limit fires first.
                .body(Body::from(oversized_body))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(
        res.status(),
        StatusCode::PAYLOAD_TOO_LARGE,
        "HTTP-layer body limit must fire for bodies > 3072 bytes",
    );
    // The HTTP-layer rejection does NOT produce JSON — confirm the body is
    // NOT the in-handler JSON error so we know the layer fired, not the handler.
    let bytes = to_bytes(res.into_body(), 512).await.unwrap();
    let is_handler_json = serde_json::from_slice::<Value>(&bytes)
        .ok()
        .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(|s| s == "body_too_large"))
        .unwrap_or(false);
    assert!(
        !is_handler_json,
        "expected HTTP-layer 413 (no JSON body), got handler 413 instead — \
         route-level DefaultBodyLimit may not be wired correctly",
    );
}

// ── Test 12: config-driven body cap (PR-M2c.0 prerequisite) ────────────────────

/// Both the route-level DefaultBodyLimit middleware AND the in-handler body cap
/// check must source the limit from `state.config.max_media_upload_body_bytes`,
/// so the env var `RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES` actually moves the ceiling.
///
/// Before the PR-M2c.0 fix, the axum layer was hard-coded to the constant
/// `MAX_MEDIA_UPLOAD_BODY_BYTES = 3072`, silently overriding any env override.
/// PR-M2c.0 cap probe (Test #66, 2026-05-18) caught this: env was set to 9000,
/// the in-handler check accepted bodies up to 9000, but the axum layer still
/// rejected them at 3072. The fix routes both layers through the same config
/// field.
#[tokio::test]
async fn test_http_body_limit_respects_config_override() {
    // Build a custom config with an elevated cap.
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.max_media_upload_body_bytes = 9_000;
    let app = build_app_with_config(cfg);

    // 5500-byte body — would have failed under the old hard-coded 3072 layer,
    // must now reach the handler (which will then 400/401 for auth/format,
    // but the key point is: NOT 413 PAYLOAD_TOO_LARGE).
    let payload = vec![b'X'; 5_500];

    let res = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                // No auth header — body limit (if it were going to fire) runs first.
                .body(Body::from(payload))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_ne!(
        res.status(),
        StatusCode::PAYLOAD_TOO_LARGE,
        "HTTP-layer body limit must respect config override; \
         got 413 even though config.max_media_upload_body_bytes = 9000 \
         (axum layer is probably still hard-coded to the constant)",
    );
}

// ── Test 14-16: PR-M2d.1 — Prefer: return=minimal → 204 No Content ───────────

/// PR-M2d.1: when the client sends `Prefer: return=minimal`, successful
/// upload returns 204 No Content with chunk-status in headers instead of
/// the legacy 201 + JSON body. This removes the response body from the
/// upload roundtrip on Tele2 LTE where Layer B reliably drops bodies > 2400
/// bytes (Test #66.2 result).
#[tokio::test]
async fn test_upload_prefer_minimal_returns_204_on_stored() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x14);
    let mid = media_id(0x14);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let ct = vec![0x11_u8; 64];
    let body = json!({
        "media_id":       mid,
        "idx":            0_u32,
        "total":          1_u32,
        "ciphertext_b64": b64(&ct),
    });
    let res = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {}", token))
                .header("prefer", "return=minimal")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(res.status(), StatusCode::NO_CONTENT, "expected 204 on minimal");
    let headers = res.headers().clone();
    assert_eq!(
        headers.get("x-chunk-stored").and_then(|v| v.to_str().ok()),
        Some("1"),
        "X-Chunk-Stored header must be set",
    );
    assert_eq!(
        headers.get("x-chunk-duplicate").and_then(|v| v.to_str().ok()),
        Some("0"),
        "X-Chunk-Duplicate must be 0 on a fresh store",
    );
    assert_eq!(
        headers.get("x-chunk-idx").and_then(|v| v.to_str().ok()),
        Some("0"),
        "X-Chunk-Idx must echo the stored index",
    );
    // 204 mandates no body content per RFC 9110 §15.3.5.
    let bytes = to_bytes(res.into_body(), 32).await.unwrap();
    assert!(bytes.is_empty(), "204 response must have empty body, got {} bytes", bytes.len());
}

/// Same media_id+idx with same ciphertext POSTed twice with Prefer: return=minimal
/// — second call must return 204 with X-Chunk-Duplicate: 1.
#[tokio::test]
async fn test_upload_prefer_minimal_returns_204_on_duplicate() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x15);
    let mid = media_id(0x15);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let ct = vec![0x22_u8; 80];
    let body = json!({
        "media_id":       mid,
        "idx":            0_u32,
        "total":          1_u32,
        "ciphertext_b64": b64(&ct),
    });

    // First upload
    let res1 = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {}", token))
                .header("prefer", "return=minimal")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res1.status(), StatusCode::NO_CONTENT, "first upload 204");
    let dup1 = res1.headers().get("x-chunk-duplicate").and_then(|v| v.to_str().ok())
        .unwrap_or("?").to_string();
    assert_eq!(dup1, "0", "first upload must NOT be a duplicate");

    // Second upload, same bytes
    let res2 = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {}", token))
                .header("prefer", "return=minimal")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res2.status(), StatusCode::NO_CONTENT, "second upload 204 too");
    let dup2 = res2.headers().get("x-chunk-duplicate").and_then(|v| v.to_str().ok())
        .unwrap_or("?").to_string();
    assert_eq!(dup2, "1", "second upload must be flagged as duplicate");
}

/// Backward-compat: a client that does NOT send Prefer: return=minimal must
/// still receive the legacy 201/200 + JSON body. Guards against accidentally
/// breaking old APKs that rely on the JSON shape.
#[tokio::test]
async fn test_upload_no_prefer_keeps_legacy_201_json() {
    let app = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(0x16);
    let mid = media_id(0x16);

    let (app, token) = obtain_token(app, &identity, &signing_kp).await;

    let ct = vec![0x33_u8; 96];
    let body = json!({
        "media_id":       mid,
        "idx":            0_u32,
        "total":          1_u32,
        "ciphertext_b64": b64(&ct),
    });
    let res = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {}", token))
                // No Prefer header.
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::CREATED, "legacy clients still get 201");
    let bytes = to_bytes(res.into_body(), 512).await.unwrap();
    let v: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(v.get("status").and_then(|v| v.as_str()), Some("stored"));
    assert_eq!(v.get("idx").and_then(|v| v.as_u64()), Some(0));
}

// ── Test 13: config override still gates beyond its value ──────────────────────

/// Symmetric to Test 12: confirm a body that exceeds the elevated cap STILL
/// gets 413. This guards against accidentally removing the layer entirely.
#[tokio::test]
async fn test_http_body_limit_fires_above_config_override() {
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.max_media_upload_body_bytes = 5_000;
    let app = build_app_with_config(cfg);

    // 5001 bytes — one byte over the elevated cap.
    let payload = vec![b'X'; 5_001];

    let res = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/media/upload-chunk")
                .header("content-type", "application/json")
                .body(Body::from(payload))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(
        res.status(),
        StatusCode::PAYLOAD_TOO_LARGE,
        "HTTP-layer body limit must still fire above the configured cap",
    );
}
