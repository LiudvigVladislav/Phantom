// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! End-to-end integration tests for the X3DH prekey endpoints.
//!
//! Runs the real axum router in-memory via `tower::ServiceExt::oneshot`,
//! covering the path the production relay actually serves: HTTP request →
//! handler → PreKeyStore → response. Distinct from `prekeys::tests` which
//! exercise the store in isolation.
//!
//! Each test owns its own `tempfile::TempDir` used as `RelayConfig
//! .state_dir`, so parallel-running tests cannot see each other's
//! `prekeys.jsonl` (RC-RELAY-STATE-DIR-REPAIR PR-1a §7.1). The
//! `state_dir_config_not_env` meta-test in `state_persistence.rs`
//! guards this positive-injection contract against regression.

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use ed25519_dalek::{Signer, Signature, SigningKey};
use rand::rngs::OsRng;
use serde_json::{json, Value};
use std::sync::Arc;
use tempfile::TempDir;
use tower::ServiceExt;

const SPK_DOMAIN_LABEL: &[u8] = b"phantom-spk-v1";

/// Build a fresh AppState + Router pair for one test rooted at a
/// dedicated `tempfile::TempDir`. Callers MUST bind BOTH returned values
/// (`let (app, _tmp) = build_app();`) so the `TempDir` handle stays
/// alive for the duration of the test — dropping it early removes the
/// state files mid-run and breaks the persistence contract we exercise.
fn build_app() -> (axum::Router, TempDir) {
    let tmp = tempfile::tempdir().expect("tempdir for state_dir");
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.state_dir = tmp.path().to_path_buf();
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    let router = phantom_relay::routes::router(state);
    (router, tmp)
}

fn sign_spk_payload(
    signing: &SigningKey,
    spk_pub: &[u8; 32],
    created_at_ms: i64,
) -> [u8; 64] {
    let mut payload = Vec::new();
    payload.extend_from_slice(SPK_DOMAIN_LABEL);
    payload.extend_from_slice(spk_pub);
    payload.extend_from_slice(&created_at_ms.to_be_bytes());
    let sig: Signature = signing.sign(&payload);
    sig.to_bytes()
}

/// Per ADR-009 the X25519 routing identity is independent from the Ed25519
/// signing identity. Tests use a synthetic 32-byte hex for the X25519 slot
/// (relay only checks shape) and the actual Ed25519 verifying key for the
/// signing slot.
fn synthetic_identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        *b = seed.wrapping_add(i as u8);
    }
    hex::encode(buf)
}

#[tokio::test]
async fn publish_then_fetch_round_trip_via_http() {
    let (app, _tmp) = build_app();

    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
    let identity_hex = synthetic_identity_hex(20);
    let spk_pub = [0xAAu8; 32];
    let created_at_ms: i64 = 1_700_000_000_000;
    let sig = sign_spk_payload(&signing_kp, &spk_pub, created_at_ms);

    let publish_body = json!({
        "identity_pubkey_hex": identity_hex,
        "signing_pubkey_hex": signing_hex,
        "signed_pre_key": {
            "key_id": 1,
            "public_key_hex": hex::encode(spk_pub),
            "created_at_ms": created_at_ms,
            "signature_hex": hex::encode(sig),
        },
        "one_time_pre_keys": [
            {
                "key_id_hex": "00112233445566778899aabbccddeeff",
                "public_key_hex": hex::encode([0x11u8; 32]),
            },
            {
                "key_id_hex": "ffeeddccbbaa99887766554433221100",
                "public_key_hex": hex::encode([0x22u8; 32]),
            }
        ],
    });

    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/prekeys/publish")
                .header("content-type", "application/json")
                .body(Body::from(publish_body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::CREATED);
    let body = to_bytes(res.into_body(), 64 * 1024).await.unwrap();
    let v: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(v["stored_opks"], 2);

    // Fetch the bundle — must include the SPK, the signing pubkey, and
    // one of the OPKs.
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/prekeys/bundle/{}", identity_hex))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = to_bytes(res.into_body(), 64 * 1024).await.unwrap();
    let bundle: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(bundle["identity_pubkey_hex"], identity_hex);
    assert_eq!(bundle["signing_pubkey_hex"], signing_hex);
    assert_eq!(bundle["signed_pre_key"]["key_id"], 1);
    assert!(bundle["one_time_pre_key"].is_object());

    // Status should report 1 OPK remaining (we published 2, consumed 1).
    let res = app
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/prekeys/status/{}", identity_hex))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let body = to_bytes(res.into_body(), 64 * 1024).await.unwrap();
    let st: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(st["remaining_opks"], 1);
}

#[tokio::test]
async fn bad_signature_returns_400() {
    let (app, _tmp) = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
    let identity_hex = synthetic_identity_hex(21);
    let spk_pub = [0x33u8; 32];
    let mut sig = sign_spk_payload(&signing_kp, &spk_pub, 1_000);
    sig[0] ^= 0x01; // flip a bit

    let body = json!({
        "identity_pubkey_hex": identity_hex,
        "signing_pubkey_hex": signing_hex,
        "signed_pre_key": {
            "key_id": 1,
            "public_key_hex": hex::encode(spk_pub),
            "created_at_ms": 1_000,
            "signature_hex": hex::encode(sig),
        },
        "one_time_pre_keys": [],
    });
    let res = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/prekeys/publish")
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn empty_pool_bundle_omits_opk() {
    let (app, _tmp) = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
    let identity_hex = synthetic_identity_hex(22);
    let spk_pub = [0x44u8; 32];
    let sig = sign_spk_payload(&signing_kp, &spk_pub, 5_000);
    let body = json!({
        "identity_pubkey_hex": identity_hex,
        "signing_pubkey_hex": signing_hex,
        "signed_pre_key": {
            "key_id": 1,
            "public_key_hex": hex::encode(spk_pub),
            "created_at_ms": 5_000,
            "signature_hex": hex::encode(sig),
        },
        "one_time_pre_keys": [],
    });
    let _ = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/prekeys/publish")
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    let res = app
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/prekeys/bundle/{}", identity_hex))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = to_bytes(res.into_body(), 64 * 1024).await.unwrap();
    let bundle: Value = serde_json::from_slice(&body).unwrap();
    assert!(bundle["one_time_pre_key"].is_null());
    assert!(bundle["signed_pre_key"].is_object());
}

#[tokio::test]
async fn publish_rate_limit_returns_429_after_quota() {
    let (app, _tmp) = build_app();
    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
    let identity_hex = synthetic_identity_hex(23);

    // 10 successful publishes, then the 11th must be 429.
    for n in 0..11 {
        let spk_pub = {
            let mut p = [0u8; 32];
            p[0] = n as u8;
            p
        };
        let created_at_ms: i64 = 10_000 + (n as i64);
        let sig = sign_spk_payload(&signing_kp, &spk_pub, created_at_ms);
        let body = json!({
            "identity_pubkey_hex": identity_hex,
            "signing_pubkey_hex": signing_hex,
            "signed_pre_key": {
                "key_id": (n as i64) + 1,
                "public_key_hex": hex::encode(spk_pub),
                "created_at_ms": created_at_ms,
                "signature_hex": hex::encode(sig),
            },
            "one_time_pre_keys": [],
        });
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/prekeys/publish")
                    .header("content-type", "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        if n < 10 {
            assert_eq!(res.status(), StatusCode::CREATED, "publish #{} should succeed", n);
        } else {
            assert_eq!(
                res.status(),
                StatusCode::TOO_MANY_REQUESTS,
                "publish #{} should be rate-limited",
                n
            );
        }
    }
}

#[tokio::test]
async fn fetch_unpublished_identity_returns_404() {
    let (app, _tmp) = build_app();
    let identity_hex = synthetic_identity_hex(24);
    let res = app
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/prekeys/bundle/{}", identity_hex))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn signing_key_rotation_returns_409_conflict() {
    // Once an X25519 identity registers an Ed25519 signing key, a subsequent
    // publish for the same X25519 with a DIFFERENT Ed25519 must be rejected
    // with 409 Conflict (relay enforces 1:1 binding).
    let (app, _tmp) = build_app();
    let identity_hex = synthetic_identity_hex(25);

    let kp_a = SigningKey::generate(&mut OsRng);
    let kp_b = SigningKey::generate(&mut OsRng);
    let signing_a = hex::encode(kp_a.verifying_key().to_bytes());
    let signing_b = hex::encode(kp_b.verifying_key().to_bytes());

    let spk_pub = [0x55u8; 32];
    let sig_a = sign_spk_payload(&kp_a, &spk_pub, 100);
    let body_a = json!({
        "identity_pubkey_hex": identity_hex,
        "signing_pubkey_hex": signing_a,
        "signed_pre_key": {
            "key_id": 1,
            "public_key_hex": hex::encode(spk_pub),
            "created_at_ms": 100,
            "signature_hex": hex::encode(sig_a),
        },
        "one_time_pre_keys": [],
    });
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/prekeys/publish")
                .header("content-type", "application/json")
                .body(Body::from(body_a.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::CREATED);

    // Now try publishing with a DIFFERENT signing key for the same identity.
    let spk_pub_b = [0x66u8; 32];
    let sig_b = sign_spk_payload(&kp_b, &spk_pub_b, 200);
    let body_b = json!({
        "identity_pubkey_hex": identity_hex,
        "signing_pubkey_hex": signing_b,
        "signed_pre_key": {
            "key_id": 2,
            "public_key_hex": hex::encode(spk_pub_b),
            "created_at_ms": 200,
            "signature_hex": hex::encode(sig_b),
        },
        "one_time_pre_keys": [],
    });
    let res = app
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/prekeys/publish")
                .header("content-type", "application/json")
                .body(Body::from(body_b.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::CONFLICT);
}
