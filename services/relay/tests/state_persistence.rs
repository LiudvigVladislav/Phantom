// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! RC-RELAY-STATE-DIR-REPAIR PR-1a §7.1 — integration tests pinning the
//! `RELAY_STATE_DIR` / `RelayConfig.state_dir` contract for the four
//! append-log state files (reports.jsonl, blocklist.txt,
//! push_tokens.jsonl, prekeys.jsonl).
//!
//! Two goals:
//!
//! 1. `write_then_reload_round_trip` proves that when we point the config
//!    at a fresh `tempfile::tempdir()` state_dir, drive each handler once
//!    to trigger the write, drop the whole `AppState`, and re-construct a
//!    new `AppState` against the SAME dir, the loader replays the record
//!    back into RAM. This exercises the injected-path contract end-to-end
//!    for every file the persistence family owns.
//!
//! 2. `state_dir_config_not_env` is the meta-test locked in the mini-lock
//!    §7.1: no persistence-tier test file may reach for
//!    `env::set_var("RELAY_STATE_DIR")` — that route races between
//!    parallel cargo test workers. The check greps the test tree and
//!    fails loudly if anyone reintroduces the shape. Config-parse unit
//!    tests that legitimately exercise env-var parsing (e.g. inside
//!    `services/relay/src/config.rs`'s own `#[cfg(test)]` block) are out
//!    of grep scope because we only scan `services/relay/tests/`.
//!
//! Silent-swallow shape is intentionally still in place for PR-1a — a
//! write that fails EROFS/EACCES will not surface here. What we verify is
//! that a write that SHOULD succeed against the injected path does
//! succeed and replays cleanly on next boot. PR-1b flips the failure
//! semantics and adds the boot preflight.

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use ed25519_dalek::{Signature, Signer, SigningKey};
use rand::rngs::OsRng;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::Arc;
use tower::ServiceExt;

use phantom_relay::config::RelayConfig;
use phantom_relay::routes;
use phantom_relay::state::AppState;

// ── Helpers ───────────────────────────────────────────────────────────────────

fn cfg_with_state_dir(dir: &std::path::Path) -> RelayConfig {
    let mut cfg = RelayConfig::from_env_for_test();
    cfg.state_dir = dir.to_path_buf();
    // The admin token gate on /report /admin/block requires a shared
    // secret. Setting it to a known value lets these tests exercise the
    // endpoints without env-var mutation.
    cfg.secret_token = Some("test-admin-secret".to_string());
    cfg
}

fn build_router(cfg: RelayConfig) -> (axum::Router, Arc<AppState>) {
    let state = Arc::new(AppState::new(cfg));
    let router = routes::router(Arc::clone(&state));
    (router, state)
}

fn unique_identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        *b = seed.wrapping_add(i as u8).wrapping_mul(3);
    }
    hex::encode(buf)
}

// ── report write → reload ─────────────────────────────────────────────────────

async fn post_report(router: axum::Router, reporter: &str, reported: &str) -> StatusCode {
    let body = json!({
        "reporter_key": reporter,
        "reported_key": reported,
        "category":     "spam",
        "timestamp_ms": 1_700_000_000_000u64,
    });
    let res = router
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/report")
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    res.status()
}

// ── admin/block write → reload ────────────────────────────────────────────────

async fn post_admin_block(router: axum::Router, key: &str) -> StatusCode {
    let body = json!({ "key": key });
    let res = router
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/admin/block?token=test-admin-secret")
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    res.status()
}

// ── push/register write → reload ──────────────────────────────────────────────

async fn post_push_register(router: axum::Router, identity: &str, topic_url: &str) -> StatusCode {
    let body = json!({ "identity": identity, "topic_url": topic_url });
    let res = router
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/push/register")
                .header("content-type", "application/json")
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    res.status()
}

// ── prekeys/publish write → reload ────────────────────────────────────────────

fn build_spk_and_signing_kp() -> (SigningKey, serde_json::Value, i64) {
    // Signing key (Ed25519).
    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_pubkey_hex = hex::encode(signing_kp.verifying_key().to_bytes());

    let created_at_ms: i64 = 1_700_000_000_000;

    // Deterministic 32-byte X25519 pubkey blob for the SPK payload. The
    // relay treats this as opaque cipher-agnostic bytes at publish time;
    // only the Ed25519 signature over (label || pubkey || ts) is verified.
    let spk_pubkey = [7u8; 32];
    let spk_pubkey_hex = hex::encode(spk_pubkey);
    let key_id: i64 = 42;

    let mut payload = Vec::new();
    payload.extend_from_slice(b"phantom-spk-v1");
    payload.extend_from_slice(&spk_pubkey);
    payload.extend_from_slice(&created_at_ms.to_be_bytes());
    let sig: Signature = signing_kp.sign(&payload);
    let signature_hex = hex::encode(sig.to_bytes());

    let spk = json!({
        "key_id":         key_id,
        "public_key_hex": spk_pubkey_hex,
        "created_at_ms":  created_at_ms,
        "signature_hex":  signature_hex,
    });
    let _ = signing_pubkey_hex; // consumed inline in caller
    (signing_kp, spk, created_at_ms)
}

async fn post_prekeys_publish(
    router: axum::Router,
    identity: &str,
    signing_pubkey_hex: &str,
    spk: &serde_json::Value,
    opks: &[serde_json::Value],
) -> StatusCode {
    let body = json!({
        "identity_pubkey_hex":  identity,
        "signing_pubkey_hex":   signing_pubkey_hex,
        "signed_pre_key":       spk,
        "one_time_pre_keys":    opks,
    });
    let res = router
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
    res.status()
}

async fn get_prekeys_bundle(router: axum::Router, identity: &str, requester: &str) -> (StatusCode, Value) {
    let res = router
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/prekeys/bundle/{}?requester={}", identity, requester))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let status = res.status();
    let bytes = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).unwrap_or(Value::Null)
    };
    (status, v)
}

// ── Test 1: end-to-end write → drop → reload for all four files ───────────────

#[tokio::test]
async fn write_then_reload_round_trip() {
    // Fresh temp state dir shared across both `AppState` incarnations.
    let dir = tempfile::tempdir().expect("tempdir for state_dir");
    let reporter = "aa".repeat(16); // 32 chars — accepted by /report
    let reported = "bb".repeat(16);
    let block_key = "cc".repeat(16);
    let push_identity = "dd".repeat(16);
    let push_topic = "https://ntfy.example.com/topic-abc";
    let prekey_identity = unique_identity_hex(0x11);
    let requester = unique_identity_hex(0x22);

    // Round 1 — construct AppState, drive one write per file, drop.
    {
        let cfg = cfg_with_state_dir(dir.path());
        let (router, _state) = build_router(cfg);

        assert_eq!(
            post_report(router.clone(), &reporter, &reported).await,
            StatusCode::OK,
            "/report must return 200"
        );
        assert_eq!(
            post_admin_block(router.clone(), &block_key).await,
            StatusCode::OK,
            "/admin/block must return 200"
        );
        assert_eq!(
            post_push_register(router.clone(), &push_identity, push_topic).await,
            StatusCode::OK,
            "/push/register must return 200"
        );

        // Prekeys publish requires a signed SPK. Build one and publish it
        // with an empty OPK pool (the fetch below only needs the SPK
        // signing binding to replay).
        let (signing_kp, spk, _created) = build_spk_and_signing_kp();
        let signing_pubkey_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let publish_status = post_prekeys_publish(
            router.clone(),
            &prekey_identity,
            &signing_pubkey_hex,
            &spk,
            &[],
        )
        .await;
        assert!(
            matches!(publish_status, StatusCode::OK | StatusCode::CREATED),
            "/prekeys/publish must accept the bundle (got {})",
            publish_status
        );
    }

    // Assert each on-disk file exists inside the injected state_dir.
    let expect_files = [
        "reports.jsonl",
        "blocklist.txt",
        "push_tokens.jsonl",
        "prekeys.jsonl",
    ];
    for f in expect_files.iter() {
        let full = dir.path().join(f);
        let meta = std::fs::metadata(&full)
            .unwrap_or_else(|e| panic!("expected {} to exist under state_dir: {}", f, e));
        assert!(meta.len() > 0, "{} exists but is empty", f);
    }

    // The load-bearing assertion for PR-1a is the round-2 replay below —
    // that state written under the injected state_dir loads back on a
    // fresh AppState pointed at the same dir. A parallel test binary that
    // still writes to workspace CWD (existing pre-fix behavior — mini-lock
    // §3.7) races the file layout here, so we deliberately do NOT assert
    // "no CWD spill" from THIS test. The `services/relay/prekeys.jsonl`
    // workspace artefact is separately handled by the .gitignore entry
    // added in PR-1a and by the deletion of the tracked file (mini-lock
    // §7.4). PR-1b's grep-gate is what enforces the shape long-term.

    // Round 2 — fresh AppState pointed at the SAME dir, assert replay.
    let cfg = cfg_with_state_dir(dir.path());
    let (router, state) = build_router(cfg);

    // Reports replay via `AppState::new` -> `load_reports_from_disk`.
    {
        let loaded = state.reports.read().await;
        assert!(
            loaded.iter().any(|r| r.reporter_key == reporter && r.reported_key == reported),
            "report record must replay from disk into AppState.reports (found {} entries)",
            loaded.len()
        );
    }
    // Blocklist replay via `load_blocklist_from_disk`.
    {
        let loaded = state.blocklist.read().await;
        assert!(loaded.contains(&block_key), "blocklist entry must replay");
    }
    // Push-tokens replay via `load_push_tokens_from_disk`.
    {
        let loaded = state.push_tokens.read().await;
        assert_eq!(
            loaded.get(&push_identity).map(String::as_str),
            Some(push_topic),
            "push_tokens entry must replay to the same topic URL"
        );
    }
    // Prekeys replay via `PreKeyStore::new` -> `load_from_disk`.
    let (status, body) = get_prekeys_bundle(router, &prekey_identity, &requester).await;
    assert_eq!(status, StatusCode::OK, "prekey bundle fetch must succeed post-reload; body={:?}", body);
    assert_eq!(
        body["identity_pubkey_hex"].as_str(),
        Some(prekey_identity.as_str()),
        "returned bundle must carry the persisted identity"
    );
}

// ── Test 2: meta-tests for the injection contract ────────────────────────────

/// Files that exercise handlers writing to the persistence family
/// (reports.jsonl, blocklist.txt, push_tokens.jsonl, prekeys.jsonl).
/// Each entry MUST inject its own `tempfile::TempDir` via
/// `RelayConfig.state_dir` — the negative check bans `env::set_var
/// ("RELAY_STATE_DIR", ...)`, the positive check requires both a
/// `tempfile::tempdir` construction and a `state_dir` field assignment
/// in the same file so parallel test workers cannot share on-disk
/// state (mini-lock §7.1).
///
/// Adding a new file that publishes prekeys, submits abuse reports,
/// blocks keys, or registers push tokens? Add it here AND wire it into
/// its own `tempfile::TempDir` fixture before the test suite catches
/// the regression at CI.
const PERSISTENCE_TIER_TEST_FILES: &[&str] = &[
    "state_persistence.rs",
    "prekey_endpoints.rs",
];

/// PR-1a §7.1 meta-test (negative) — enumerates every `.rs` file under
/// `services/relay/tests/` and asserts none of them uses
/// `env::set_var("RELAY_STATE_DIR")` (or the compound `std::env::set_var`
/// form). Persistence-tier tests MUST inject through
/// `RelayConfig.state_dir` field. Config-parse unit tests (in
/// `services/relay/src/config.rs`'s own `#[cfg(test)]` block) are out of
/// scope because we only scan `tests/`.
#[test]
fn state_dir_config_not_env() {
    let tests_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests");
    let entries = std::fs::read_dir(&tests_dir)
        .unwrap_or_else(|e| panic!("cannot read {}: {}", tests_dir.display(), e));
    let mut offenders: Vec<String> = Vec::new();
    for entry in entries {
        let entry = entry.expect("read_dir entry");
        let path = entry.path();
        if path.extension().and_then(|s| s.to_str()) != Some("rs") {
            continue;
        }
        // Skip THIS file — it names RELAY_STATE_DIR in comments (the ban
        // itself) and in a false-positive-suppression string below.
        if path.file_name() == Some(std::ffi::OsStr::new("state_persistence.rs")) {
            continue;
        }
        let content = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("read {} failed: {}", path.display(), e));
        // The literal offending shapes; add more if a future contributor
        // finds a creative bypass. `set_var` with `RELAY_STATE_DIR` in the
        // same source line is the load-bearing shape (the two tokens on
        // separate lines is a legitimate documentation pattern).
        for line in content.lines() {
            if line.contains("set_var")
                && line.contains("RELAY_STATE_DIR")
            {
                offenders.push(format!(
                    "{}: {}",
                    path.file_name().unwrap().to_string_lossy(),
                    line.trim()
                ));
            }
        }
    }
    assert!(
        offenders.is_empty(),
        "persistence-tier tests MUST inject `RelayConfig.state_dir` \
         directly rather than calling env::set_var(\"RELAY_STATE_DIR\", ...) \
         — that shape races between parallel cargo test workers. \
         Offenders:\n  {}",
        offenders.join("\n  ")
    );
}

/// PR-1a §7.1 meta-test (positive) — every file in
/// `PERSISTENCE_TIER_TEST_FILES` MUST include both a
/// `tempfile::tempdir` construction AND a `state_dir` field
/// assignment. This is the load-bearing contract: without the
/// positive-injection shape, a `from_env_for_test()` call falls back
/// to `PathBuf::from(".")` and the parallel-worker race on the
/// workspace `./prekeys.jsonl` reintroduces the pre-fix flake.
///
/// The check is textual, not semantic, so a contributor that
/// SPECIFICALLY tries to bypass it (e.g. via macro indirection)
/// could subvert this. But the check catches the shape any honest
/// migration produces, which is the point.
#[test]
fn persistence_tier_tests_inject_state_dir_via_tempdir() {
    let tests_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests");
    let mut missing: Vec<String> = Vec::new();
    for file in PERSISTENCE_TIER_TEST_FILES {
        let path = tests_dir.join(file);
        let content = std::fs::read_to_string(&path).unwrap_or_else(|e| {
            panic!(
                "cannot read {} (add it to PERSISTENCE_TIER_TEST_FILES only \
                 when the file actually exists): {}",
                path.display(),
                e
            )
        });
        // Both tokens must appear at least once in the file body. A
        // brand-new persistence-tier test that only exercises the READ
        // path could technically get by without either; add it here
        // only when it writes.
        let has_tempdir = content.contains("tempfile::tempdir");
        let has_state_dir_assign = content.contains("state_dir");
        if !(has_tempdir && has_state_dir_assign) {
            missing.push(format!(
                "{}: tempfile::tempdir={}, state_dir={}",
                file, has_tempdir, has_state_dir_assign
            ));
        }
    }
    assert!(
        missing.is_empty(),
        "persistence-tier test files MUST inject `RelayConfig.state_dir` \
         from a per-fixture `tempfile::tempdir()` (positive contract). \
         Files missing the shape:\n  {}",
        missing.join("\n  ")
    );
}
