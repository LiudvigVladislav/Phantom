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

// ── Test 2: negative meta-test on env::set_var ───────────────────────────────

/// PR-1a §7.1 positive-injection contract is enforced BEHAVIORALLY, not
/// textually, by tests that publish state and assert the resulting file
/// appears at the configured `state_dir` path:
///
/// * `write_then_reload_round_trip` above — publishes to all four state
///   files, then reloads a fresh `AppState` pointed at the same
///   `tempfile::tempdir()` and asserts each record replays. If the
///   config-plumbing regressed (state_dir field not threaded through),
///   the reloader would see an empty dir and the test fails.
/// * `publish_lands_in_configured_state_dir` in `prekey_endpoints.rs` —
///   creates its own `TempDir`, drives `POST /prekeys/publish`, and
///   asserts `<TempDir>/prekeys.jsonl` exists and is non-empty. If
///   `build_app()` in that file regressed to workspace CWD, the file
///   would land elsewhere and the assertion would fail.
///
/// A textual positive check was tried in round 2/3 but self-satisfied
/// via string literals in this very file (unit-test helper assertions
/// containing patterns like `"tempfile::tempdir()"` and
/// `"cfg.state_dir = tmp.path();"`), so a contributor could delete the
/// real injection call and leave the textual patterns intact in
/// literals. Behavioral gates are the load-bearing check.

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
        // PR-0 A-5: config_boot.rs deliberately mutates RELAY_STATE_DIR
        // to exercise `RelayConfig::from_env` — the WHOLE POINT of that
        // fixture is that it reads the env var. The persistence-tier
        // ban does not apply because config_boot.rs never writes state
        // files; it validates the env-parse contract only. Cross-file
        // races are contained by `#[serial]` + the file-local
        // `ENV_LOCK` mutex inside `with_state_dir` (see the file's
        // docstring).
        if path.file_name() == Some(std::ffi::OsStr::new("config_boot.rs")) {
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

// The textual positive-injection meta-test and its two helper functions
// were removed in round 4: string literals inside this very file (unit-
// test helper assertions) satisfied the pattern check independently of
// whether the real injection call existed, so a contributor could delete
// the real call and leave the string literals intact and CI would stay
// green. The behavioral gates in `write_then_reload_round_trip` (this
// file) and `publish_lands_in_configured_state_dir` (in
// `prekey_endpoints.rs`) prove the wiring end-to-end instead: if
// `state_dir` isn't threaded through the AppState / PreKeyStore
// construction, the state file lands in workspace CWD rather than the
// tempdir, and the file-existence assertion fails.

// ─────────────────────────────────────────────────────────────────────────────
// RC-RELAY-STATE-DIR-REPAIR PR-1b §8 acceptance-gate tests.
//
// Every test below either:
//   (a) drives the boot-preflight / lock helper directly, or
//   (b) exercises the fail-loud persistence path via a portable
//       "file-as-directory" seam: after a valid write succeeds, the
//       target file is deleted and replaced with a same-name directory,
//       so the NEXT `OpenOptions::new().create(true).append(true).open(path)`
//       call returns EISDIR / ENOTDIR on Unix or ERROR_ACCESS_DENIED on
//       Windows. This gives a deterministic persist-failure trigger that
//       works on every OS in CI without touching global env, workspace
//       state, or process-wide chmod. Unix-only `chmod` variants are
//       additional-only for the preflight tests where the design doc
//       explicitly gates them with `#[cfg(unix)]`.
// ─────────────────────────────────────────────────────────────────────────────

use phantom_relay::prekeys::PreKeyStore;
use phantom_relay::state::state_dir_preflight;
use std::sync::atomic::Ordering;

/// Replace `target` with an empty directory of the same name. Panics on
/// any I/O error — tests use this to seed the fail-loud seam and any
/// panic here is a hard test-fixture bug, not a runtime code fault.
fn replace_file_with_dir(target: &std::path::Path) {
    let _ = std::fs::remove_file(target);
    std::fs::create_dir(target)
        .unwrap_or_else(|e| panic!("cannot replace {} with a directory: {}", target.display(), e));
}

// ── Test §8.1: preflight fails on a read-only state_dir (Unix-only) ──────────

#[cfg(unix)]
#[test]
fn preflight_fails_on_readonly_state_dir() {
    use std::os::unix::fs::PermissionsExt;
    let dir = tempfile::tempdir().expect("tempdir");
    // Make the directory readable + traversable but NOT writable.
    let mut perms = std::fs::metadata(dir.path()).unwrap().permissions();
    perms.set_mode(0o500);
    std::fs::set_permissions(dir.path(), perms).unwrap();

    // catch_unwind — preflight panics under fail-loud.
    let cfg = cfg_with_state_dir(dir.path());
    let result = std::panic::catch_unwind(|| {
        let _guard = state_dir_preflight(&cfg);
    });

    // Restore permissions so TempDir Drop can clean up.
    let mut perms = std::fs::metadata(dir.path()).unwrap().permissions();
    perms.set_mode(0o700);
    let _ = std::fs::set_permissions(dir.path(), perms);

    assert!(
        result.is_err(),
        "preflight should panic-loud when the state_dir is not writable"
    );
}

// ── Test §8.2: preflight fails when the target dir does not exist AND the
//    parent is read-only (Unix-only) ─────────────────────────────────────────

#[cfg(unix)]
#[test]
fn preflight_fails_when_parent_is_readonly() {
    use std::os::unix::fs::PermissionsExt;
    let parent = tempfile::tempdir().expect("tempdir");
    // Make the PARENT read-only. The nested target does not yet exist,
    // so `create_dir_all(state_dir)` inside the preflight must fail —
    // that's the load-bearing shape (bare "target missing" alone is a
    // no-op because preflight itself creates it).
    let mut perms = std::fs::metadata(parent.path()).unwrap().permissions();
    perms.set_mode(0o500);
    std::fs::set_permissions(parent.path(), perms).unwrap();

    let nested = parent.path().join("phantom-state");
    let cfg = cfg_with_state_dir(&nested);
    let result = std::panic::catch_unwind(|| {
        let _guard = state_dir_preflight(&cfg);
    });

    // Restore parent perms for TempDir cleanup.
    let mut perms = std::fs::metadata(parent.path()).unwrap().permissions();
    perms.set_mode(0o700);
    let _ = std::fs::set_permissions(parent.path(), perms);

    assert!(
        result.is_err(),
        "preflight should panic-loud when create_dir_all cannot make the target"
    );
}

// ── Test §8.3: singleton .lock rejects a second acquisition (portable) ──────

#[test]
fn state_dir_lock_prevents_second_instance() {
    use fs2::FileExt;
    let dir = tempfile::tempdir().expect("tempdir");
    let cfg = cfg_with_state_dir(dir.path());

    // First preflight succeeds and returns the held lock. We keep it
    // alive by NOT dropping `_first_lock` until the end of the test.
    let _first_lock = state_dir_preflight(&cfg);

    // Second attempt to lock the same file. Do it directly against the
    // fs2 API rather than re-invoking `state_dir_preflight` (which would
    // `process::exit(2)` inside the test binary). This proves the
    // singleton semantics without terminating the test runner.
    let lock_path = dir.path().join(".lock");
    let second_handle = std::fs::OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .truncate(false)
        .open(&lock_path)
        .expect("open lock file for second contender");
    let contended = second_handle.try_lock_exclusive();
    assert!(
        contended.is_err(),
        "second try_lock_exclusive must fail while the first lock is held"
    );

    // Explicit drop to prove the first lock's lifetime spanned the
    // whole contention window (would also happen at end-of-scope).
    drop(_first_lock);
}

// ── Test §8.4: prekey publish persist failure preserves prior state ─────────

#[tokio::test]
async fn prekey_publish_persist_failure_preserves_previous_state() {
    let dir = tempfile::tempdir().expect("tempdir for state_dir");
    let store = PreKeyStore::new(dir.path());

    // First publish — persists successfully.
    let signing_kp_1 = SigningKey::generate(&mut OsRng);
    let signing_hex_1 = hex::encode(signing_kp_1.verifying_key().to_bytes());
    let identity_hex = unique_identity_hex(0x40);
    let spk_1 = make_signed_spk(&signing_kp_1, 1, 1_700_000_000_000);
    store
        .publish(&identity_hex, &signing_hex_1, spk_1.clone(), vec![], 0)
        .await
        .expect("first publish should succeed");
    assert_eq!(store.persist_success.load(Ordering::Relaxed), 1);
    assert_eq!(store.persist_failed.load(Ordering::Relaxed), 0);

    // Break the persist path: replace prekeys.jsonl with a directory of
    // the same name. `OpenOptions::append(true).open(...)` on a
    // directory returns EISDIR/ENOTDIR on Unix and ERROR_ACCESS_DENIED
    // on Windows — either way the persist step fails.
    replace_file_with_dir(&dir.path().join("prekeys.jsonl"));

    // Second publish — SAME signing key (the 1:1 X25519↔Ed25519 binding
    // is already registered from the first publish and MUST NOT change,
    // or we'd hit SigningKeyMismatch (409) before reaching persist —
    // that would exercise a different code path from the one this test
    // is trying to pin). Different SPK key_id triggers a legitimate
    // rotation, whose persist step is the load-bearing surface here.
    let spk_2 = make_signed_spk(&signing_kp_1, 2, 1_700_000_001_000);
    let result = store
        .publish(&identity_hex, &signing_hex_1, spk_2, vec![], 1_000)
        .await;
    assert!(
        matches!(result, Err(phantom_relay::prekeys::PublishError::PersistFailed(_))),
        "publish under broken persist path must surface PersistFailed, got {:?}",
        result
    );
    assert_eq!(store.persist_success.load(Ordering::Relaxed), 1);
    assert_eq!(store.persist_failed.load(Ordering::Relaxed), 1);

    // Companion check: NO_PRIOR_STATE case. Publish for a fresh
    // identity under the same broken path — persist fails → the
    // identity MUST NOT appear in the RAM map (no partial insertion).
    let identity_fresh = unique_identity_hex(0x41);
    let signing_kp_fresh = SigningKey::generate(&mut OsRng);
    let signing_hex_fresh = hex::encode(signing_kp_fresh.verifying_key().to_bytes());
    let spk_fresh = make_signed_spk(&signing_kp_fresh, 3, 1_700_000_002_000);
    let fresh_result = store
        .publish(&identity_fresh, &signing_hex_fresh, spk_fresh, vec![], 2_000)
        .await;
    assert!(
        matches!(fresh_result, Err(phantom_relay::prekeys::PublishError::PersistFailed(_))),
        "fresh-identity publish under broken persist path must surface PersistFailed"
    );

    // Restore the file so we can read RAM state (via consume_bundle,
    // which does not need to persist for a bundle with 0 OPKs).
    let _ = std::fs::remove_dir(dir.path().join("prekeys.jsonl"));

    // The identity's SPK must STILL be the FIRST publish's key_id (1),
    // not the second publish's key_id (2) — the rotation must NOT have
    // been committed to RAM because persist failed.
    let bundle = store
        .consume_bundle(&identity_hex)
        .await
        .expect("consume after restore should succeed");
    let bundle = bundle.expect("previously-published identity must still be present");
    assert_eq!(
        bundle.signing_pubkey_hex, signing_hex_1,
        "signing key binding must survive"
    );
    assert_eq!(
        bundle.signed_pre_key.key_id, 1,
        "RAM must retain the first publish's SPK (persist-first atomicity — \
         the failed rotation must NOT have replaced it)"
    );

    // The fresh identity must NOT be in the map (404 → Ok(None)).
    let fresh_bundle = store
        .consume_bundle(&identity_fresh)
        .await
        .expect("consume after restore should succeed");
    assert!(
        fresh_bundle.is_none(),
        "fresh identity must NOT be partially inserted after failed publish (persist-first atomicity)"
    );
}

// ── Test §8.5: prekey consume persist failure preserves the OPK ─────────────

#[tokio::test]
async fn prekey_consume_persist_failure_preserves_opk() {
    let dir = tempfile::tempdir().expect("tempdir");
    let store = PreKeyStore::new(dir.path());

    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
    let identity_hex = unique_identity_hex(0x50);
    let spk = make_signed_spk(&signing_kp, 1, 1_700_000_000_000);
    let opk = make_opk_for_test(0xC5);
    let opk_key_id = opk["key_id_hex"].as_str().unwrap().to_string();
    store
        .publish(
            &identity_hex,
            &signing_hex,
            spk,
            vec![serde_json::from_value(opk).unwrap()],
            0,
        )
        .await
        .expect("publish should succeed");

    // Break the persist path.
    replace_file_with_dir(&dir.path().join("prekeys.jsonl"));

    // Consume must fail with PersistFailed. The OPK must NOT be popped.
    let result = store.consume_bundle(&identity_hex).await;
    assert!(
        matches!(result, Err(phantom_relay::prekeys::ConsumeError::PersistFailed(_))),
        "consume under broken persist path must surface PersistFailed"
    );

    // Restore the file. The next consume must return the SAME OPK we
    // published — proving the earlier failed consume did NOT burn it.
    let _ = std::fs::remove_dir(dir.path().join("prekeys.jsonl"));

    let bundle = store
        .consume_bundle(&identity_hex)
        .await
        .expect("consume after restore should succeed")
        .expect("bundle must exist");
    let returned_opk = bundle
        .one_time_pre_key
        .expect("OPK must still be present after the failed consume");
    assert_eq!(
        returned_opk.key_id_hex, opk_key_id,
        "the same OPK must be re-returned — the failed consume must not have popped it"
    );
}

// ── Test §8.6: prekey write failure returns HTTP 500 with sanitised body ────

#[tokio::test]
async fn prekey_write_failure_returns_500() {
    let dir = tempfile::tempdir().expect("tempdir");
    let cfg = cfg_with_state_dir(dir.path());
    let (router, _state) = build_router(cfg);

    // Break the persist path by making `prekeys.jsonl` a directory
    // BEFORE the first publish so `create(true).append(true).open` fails
    // straight to EISDIR / ERROR_ACCESS_DENIED.
    std::fs::create_dir(dir.path().join("prekeys.jsonl"))
        .expect("seed prekeys.jsonl as a directory");

    let identity = unique_identity_hex(0x60);
    let (signing_kp, spk, _created) = build_spk_and_signing_kp();
    let signing_pubkey_hex = hex::encode(signing_kp.verifying_key().to_bytes());

    let body = json!({
        "identity_pubkey_hex":  identity,
        "signing_pubkey_hex":   signing_pubkey_hex,
        "signed_pre_key":       spk,
        "one_time_pre_keys":    Vec::<serde_json::Value>::new(),
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

    assert_eq!(
        res.status(),
        StatusCode::INTERNAL_SERVER_ERROR,
        "prekey publish under broken persist path must return 500"
    );
    let body_bytes = to_bytes(res.into_body(), 4096).await.unwrap();
    let v: Value = serde_json::from_slice(&body_bytes).expect("500 response body must be JSON");
    assert_eq!(
        v["error"].as_str(),
        Some("prekey_persist_failed"),
        "500 body must carry the stable error tag"
    );
    let reason = v["reason"].as_str().expect("reason field must be present");
    assert!(
        matches!(reason, "permission" | "not_found" | "storage_full" | "io"),
        "reason must be from the sanitised vocabulary, got {:?}",
        reason
    );
    // Belt-and-braces: the response body MUST NOT leak the state_dir path.
    let body_str = String::from_utf8_lossy(&body_bytes);
    let path_str = dir.path().display().to_string();
    assert!(
        !body_str.contains(&path_str),
        "500 body must NOT leak the state_dir path"
    );
}

// ── Test §8.7: audit-tier persist failure preserves 2xx + bumps failure counter

#[tokio::test]
async fn report_write_failure_preserves_2xx_and_logs_error() {
    let dir = tempfile::tempdir().expect("tempdir");
    let cfg = cfg_with_state_dir(dir.path());
    let (router, state) = build_router(cfg);

    // Seed the reports file as a directory so the first `/report` call
    // hits EISDIR on open.
    std::fs::create_dir(dir.path().join("reports.jsonl"))
        .expect("seed reports.jsonl as a directory");

    let reporter = "aa".repeat(16);
    let reported = "bb".repeat(16);
    let status = post_report(router, &reporter, &reported).await;

    // Audit tier — HTTP semantics unchanged.
    assert_eq!(
        status,
        StatusCode::OK,
        "audit-tier /report must preserve 2xx even when persist fails"
    );

    // But the failure counter MUST have fired.
    assert_eq!(
        state.reports_persist_failed.load(Ordering::Relaxed),
        1,
        "reports_persist_failed must increment on the failed audit-tier write"
    );
    assert_eq!(
        state.reports_persist_success.load(Ordering::Relaxed),
        0,
        "reports_persist_success must NOT fire when the write failed"
    );
    // RAM state DOES accept the report because the audit tier is
    // "best-effort persist, always mutate RAM". This is intentional per
    // §4.2 audit tier and distinguishes it from the correctness tier.
    let ram_reports = state.reports.read().await;
    assert_eq!(
        ram_reports.len(),
        1,
        "audit-tier RAM mutation happens regardless of persist outcome"
    );
}

// ── Test §8.8: loader skips torn last line + emits count ────────────────────

#[test]
fn load_skips_torn_last_line_and_logs() {
    let dir = tempfile::tempdir().expect("tempdir");
    let path = dir.path().join("prekeys.jsonl");

    // Write ONE valid record + a torn (truncated) second line.
    let signing_kp = SigningKey::generate(&mut OsRng);
    let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
    let identity_hex = unique_identity_hex(0x70);
    let created_at_ms: i64 = 1_700_000_000_000;
    let spk_pub = [0x77u8; 32];
    let mut payload = Vec::new();
    payload.extend_from_slice(b"phantom-spk-v1");
    payload.extend_from_slice(&spk_pub);
    payload.extend_from_slice(&created_at_ms.to_be_bytes());
    let sig: Signature = signing_kp.sign(&payload);
    let good_record = json!({
        "identity_pubkey_hex": identity_hex,
        "signing_pubkey_hex":  signing_hex,
        "current_spk": {
            "key_id":         1,
            "public_key_hex": hex::encode(spk_pub),
            "created_at_ms":  created_at_ms,
            "signature_hex":  hex::encode(sig.to_bytes()),
        },
        "previous_spk":            null,
        "previous_retired_at_ms":  null,
        "one_time_prekeys":        Vec::<serde_json::Value>::new(),
    });
    let mut file_body = String::new();
    file_body.push_str(&good_record.to_string());
    file_body.push('\n');
    // Torn: partial JSON, missing closing brace.
    file_body.push_str(r#"{"identity_pubkey_hex":"abcd"#);
    std::fs::write(&path, file_body).expect("seed prekeys.jsonl");

    // Load via PreKeyStore::new — the torn line must be skipped and
    // the counter must reflect exactly one skip.
    let store = PreKeyStore::new(dir.path());
    assert_eq!(
        store.load_torn_lines_skipped.load(Ordering::Relaxed),
        1,
        "torn last line must count as one skip"
    );

    // The valid record must still be reachable through the store —
    // consume_bundle on the persisted identity returns Ok(Some(...)).
    // We do this on a Tokio runtime because consume_bundle is async.
    let bundle = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async { store.consume_bundle(&identity_hex).await })
        .expect("consume should succeed")
        .expect("valid record must be reachable after torn-line skip");
    assert_eq!(bundle.identity_pubkey_hex, identity_hex);
}

// ── Test-only helpers used by §8 tests ──────────────────────────────────────

fn make_signed_spk(
    signing_kp: &SigningKey,
    key_id: i64,
    created_at_ms: i64,
) -> phantom_relay::prekeys::SignedPreKeyPublicBundle {
    let pubkey = [key_id as u8; 32];
    let mut payload = Vec::new();
    payload.extend_from_slice(b"phantom-spk-v1");
    payload.extend_from_slice(&pubkey);
    payload.extend_from_slice(&created_at_ms.to_be_bytes());
    let sig: Signature = signing_kp.sign(&payload);
    phantom_relay::prekeys::SignedPreKeyPublicBundle {
        key_id,
        public_key_hex: hex::encode(pubkey),
        created_at_ms,
        signature_hex: hex::encode(sig.to_bytes()),
    }
}

fn make_opk_for_test(seed: u8) -> serde_json::Value {
    let mut id = [0u8; 16];
    id[0] = seed;
    let mut pk = [0u8; 32];
    pk[0] = seed;
    json!({
        "key_id_hex":     hex::encode(id),
        "public_key_hex": hex::encode(pk),
    })
}
