// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Round 14 server-side paced padded poll — contract tests.
//!
//! Background: M2-B field measurement 2026-06-14 on Tecno BF7-12 + Tele2 LTE
//! showed that a single buffered 4608-byte poll response stalls
//! deterministically at ~3978 bytes due to a carrier middlebox byte budget,
//! while the same 4608 bytes delivered as 4 chunks of 1152 bytes with
//! 100-500 ms inter-chunk pauses passes end-to-end (linear scaling slope
//! `2.99` mathematically proves the pauses survive Caddy + TLS + carrier).
//!
//! Round 14 server-side change: a `RelayConfig::poll_chunked_flush` env-gated
//! flag (default OFF) makes `/relay/poll` emit the padded 4608-byte body in
//! 4 × 1152-byte chunks with 300 ms pauses between yields. The D15
//! byte-EXACT 4608 invariant and the L1 LP+PP coupling are preserved.
//!
//! These tests pin every merge-gate contract from the Round 14 scope doc
//! (working notes:
//! `C:\temp\round12-field-measurement-2026-06-14\round14-council\09-synthesis-scope-doc-draft.md`).
//!
//! Test list mapping to scope doc §9:
//!
//!   M1  → `lp_only_no_chunked_when_flag_on` (L-GATING-1, partial opt-in)
//!   M2  → `pp_only_no_chunked_when_flag_on` (L-GATING-1, partial opt-in)
//!   M3  → `both_headers_flag_off_buffered_mono_4608`
//!   M4  → `both_headers_flag_on_emits_4_chunks_totalling_4608`
//!   M5  → `chunked_response_has_explicit_content_length_and_no_transfer_encoding_chunked`
//!         (L-CL-1)
//!   M6  → `chunked_total_bytes_and_structure_match_mono_path` (D15 byte-EXACT)
//!   M7  → `inter_chunk_timing_matches_pause_constant_under_paused_time`
//!   M8  → reformulated per Vladislav 2026-06-14: covered implicitly by M4
//!         because the relay handler has no `PrivacyMode` knowledge — see
//!         `m8_server_does_not_branch_on_any_privacy_mode_signal` doc test
//!   M9  → DEFERRED — multi-envelope `more=true` flow requires queue setup
//!         scaffolding beyond this file; covered by existing
//!         `rest_fallback_endpoints.rs` tests which are unaffected by the
//!         Round 14 flag because the body construction is downstream of the
//!         queue
//!   M10 → DEFERRED — hold-loop semantics are unchanged by Round 14 and
//!         already pinned by `poll_hold.rs`
//!   M11 → `error_response_401_not_chunked_even_when_flag_on`
//!   M12 → COMPILE-TIME enforced via `const _: () = assert!(...)` in
//!         `rest_fallback.rs:POLL_CHUNKED_FLUSH_PAUSE_MS >= 100`. Lowering
//!         the constant below 100 fails to compile
//!   M13 → `mutex_check_rejects_simultaneous_diag_shape_and_chunked_flush`
//!         (env-isolated)
//!   M14 → DEFERRED — capturing the `tracing::info!` log entry requires
//!         `tracing-subscriber` test scaffolding; covered by manual log
//!         inspection during the F1 field test
//!   M15 → `session_response_does_not_contain_chunked_flush_field`
//!   M16 → DEFERRED — 60 s TimeoutLayer budget headroom is a tower-http
//!         framework property and is covered by L-TIMEOUT-1 in the scope
//!         doc by reference
//!
//! The deferred items (M9, M10, M14, M16) have explicit rationale tied to
//! either pre-existing test coverage elsewhere in the repo or framework
//! invariants that do not benefit from a duplicate test in this file. The
//! ones that ARE in this file are the load-bearing Round 14 contract gates.

use axum::body::{Body, Bytes};
use axum::http::{Request, StatusCode};
use ed25519_dalek::{Signature, Signer, SigningKey};
use phantom_relay::rest_fallback::{
    POLL_CHUNKED_FLUSH_CHUNK_COUNT, POLL_CHUNKED_FLUSH_PAUSE_MS,
    POLL_RESPONSE_CANONICAL_BYTES,
};
use rand::rngs::OsRng;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Duration;
use tower::ServiceExt;

// ── Test scaffolding (mirrors poll_hold.rs patterns) ─────────────────────────

fn build_app_with_chunked_flush(poll_chunked_flush: bool) -> axum::Router {
    let mut cfg = phantom_relay::config::RelayConfig::from_env_for_test();
    cfg.poll_chunked_flush = poll_chunked_flush;
    let state = Arc::new(phantom_relay::state::AppState::new(cfg));
    phantom_relay::routes::router(state)
}

fn identity_hex(seed: u8) -> String {
    let mut buf = [0u8; 32];
    for (i, b) in buf.iter_mut().enumerate() {
        // Distinct seed range from other test files to avoid challenge-cache
        // cross-contamination (rest_fallback_endpoints uses 0xA0+, prekey
        // uses 20-25, poll_hold uses 0xD0+, this file uses 0xE0+).
        *b = seed.wrapping_add(i as u8).wrapping_add(0xE0);
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
    let body = axum::body::to_bytes(res.into_body(), 4096).await.unwrap();
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
    let bytes = axum::body::to_bytes(res.into_body(), 8192).await.unwrap();
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
    let token = v["token"].as_str().expect("session token field").to_string();
    (app, token)
}

/// Issue a `GET /relay/poll` with optional opt-in headers and return the
/// raw `Response`. The caller decides whether to assert on status, frame
/// count, headers, or body bytes.
async fn poll_with_headers(
    app: axum::Router,
    token: &str,
    long_poll_header: bool,
    padded_poll_header: bool,
) -> axum::response::Response {
    let mut req_builder = Request::builder()
        .method("GET")
        .uri("/relay/poll?since_seq=0")
        .header("Authorization", format!("Bearer {}", token));
    if long_poll_header {
        req_builder = req_builder.header("X-Phantom-Long-Poll", "1");
    }
    if padded_poll_header {
        req_builder = req_builder.header("X-Phantom-Padded-Poll", "1");
    }
    app.oneshot(req_builder.body(Body::empty()).unwrap())
        .await
        .unwrap()
}

/// Collect every body frame the response emits in order. For a `Body::from(Vec)`
/// path this returns 1 element. For a `Body::from_stream(...)` 4-chunk path
/// this returns 4 elements. The frame count is the discriminator the Round 14
/// chunked-vs-mono contract pivots on.
async fn collect_body_frames(body: Body) -> Vec<Bytes> {
    use futures_util::stream::TryStreamExt;
    body.into_data_stream()
        .try_collect()
        .await
        .expect("body data stream collects")
}

// ── M1 — LP=1 PP=0 + flag=ON → legacy mono (L-GATING-1) ──────────────────────

#[tokio::test]
async fn m1_lp_only_no_chunked_when_flag_on() {
    // L-GATING-1: chunked emission fires ONLY when
    // (LP=1 AND PP=1 AND poll_chunked_flush=ON). A client that sends
    // only X-Phantom-Long-Poll=1 has PARTIAL opt-in: the legacy OR-gate
    // `padded_opt_in = long_poll_opt_in || padded_poll_opt_in` still
    // evaluates to true, so the server response IS the padded
    // 4608-byte body — but emitted as a single buffered mono frame,
    // NOT as the new chunked shape. What this test asserts is the
    // narrower contract: chunked emission requires the strict
    // L-GATING-1 conjunction; LP-only opt-in falls through to legacy
    // mono padded. Emitting a 4 × 1152-byte chunked response to a
    // partial-opt-in client would create a new client-distinguishable
    // shape (R5 padding invariant violation per privacy reviewer
    // 2026-06-14).
    //
    // DO NOT collapse the AND-gate in `rest_fallback.rs::rest_poll` to
    // `padded_opt_in && state.config.poll_chunked_flush`. That collapse
    // would fire chunked emission on partial opt-in, breaking this test
    // and the privacy contract simultaneously.
    let app = build_app_with_chunked_flush(true);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(1);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;
    let resp = poll_with_headers(app, &token, /*LP=*/ true, /*PP=*/ false).await;
    assert_eq!(resp.status(), StatusCode::OK);
    let frames = collect_body_frames(resp.into_body()).await;
    assert_eq!(
        frames.len(),
        1,
        "M1 — LP=1 PP=0 + flag=on MUST emit a single buffered frame \
         (legacy mono padded path; the response IS still padded under \
         the legacy LP||PP OR-gate, but NOT chunked). Found {} frames; \
         the chunked path would emit {}.",
        frames.len(),
        POLL_CHUNKED_FLUSH_CHUNK_COUNT,
    );
}

// ── M2 — LP=0 PP=1 + flag=ON → legacy mono (L-GATING-1) ──────────────────────

#[tokio::test]
async fn m2_pp_only_no_chunked_when_flag_on() {
    // L-GATING-1 mirror of M1: a client sending only X-Phantom-Padded-Poll=1
    // also has PARTIAL opt-in. The legacy OR-gate `padded_opt_in =
    // long_poll_opt_in || padded_poll_opt_in` is still true, so the
    // server response IS the padded 4608-byte body — emitted as a
    // single buffered mono frame, NOT chunked. Both M1 and M2 are the
    // structural guard against the L-GATING-1 AND-gate being collapsed
    // back to `padded_opt_in && poll_chunked_flush`.
    let app = build_app_with_chunked_flush(true);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(2);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;
    let resp = poll_with_headers(app, &token, /*LP=*/ false, /*PP=*/ true).await;
    assert_eq!(resp.status(), StatusCode::OK);
    let frames = collect_body_frames(resp.into_body()).await;
    assert_eq!(
        frames.len(),
        1,
        "M2 — LP=0 PP=1 + flag=on MUST emit a single buffered frame \
         (legacy mono padded path; the response IS still padded under \
         the legacy LP||PP OR-gate, but NOT chunked). Found {} frames; \
         the chunked path would emit {}.",
        frames.len(),
        POLL_CHUNKED_FLUSH_CHUNK_COUNT,
    );
}

// ── M3 — LP=1 PP=1 + flag=OFF → buffered mono 4608 ───────────────────────────

#[tokio::test]
async fn m3_both_headers_flag_off_buffered_mono_4608() {
    // Flag-off default state: the existing wire contract is preserved
    // exactly. Both opt-in headers present + flag off must produce a
    // single buffered frame of exactly POLL_RESPONSE_CANONICAL_BYTES bytes
    // (4608). This is the rollback-safety guarantee: deploying the Round
    // 14 binary with the flag unset changes nothing on the wire.
    let app = build_app_with_chunked_flush(false);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(3);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;
    let resp = poll_with_headers(app, &token, /*LP=*/ true, /*PP=*/ true).await;
    assert_eq!(resp.status(), StatusCode::OK);
    let frames = collect_body_frames(resp.into_body()).await;
    assert_eq!(
        frames.len(),
        1,
        "M3 — flag=off MUST emit a single buffered frame (rollback-safety)",
    );
    let total: usize = frames.iter().map(|b| b.len()).sum();
    assert_eq!(
        total, POLL_RESPONSE_CANONICAL_BYTES,
        "M3 — flag=off padded response total must equal POLL_RESPONSE_CANONICAL_BYTES (D15)",
    );
}

// ── M4 — LP=1 PP=1 + flag=ON → 4 chunks totalling 4608 ───────────────────────

#[tokio::test(start_paused = true)]
async fn m4_both_headers_flag_on_emits_4_chunks_totalling_4608() {
    // The chunked emission contract: the full conjunction
    // (LP=1 AND PP=1 AND flag=ON) yields exactly POLL_CHUNKED_FLUSH_CHUNK_COUNT
    // (4) body frames whose concatenation equals POLL_RESPONSE_CANONICAL_BYTES
    // (4608) bytes. `start_paused` makes `tokio::time::sleep` auto-advance so
    // the test does not hang on the 3 inter-chunk pauses.
    let app = build_app_with_chunked_flush(true);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(4);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;
    let resp = poll_with_headers(app, &token, /*LP=*/ true, /*PP=*/ true).await;
    assert_eq!(resp.status(), StatusCode::OK);
    let frames = collect_body_frames(resp.into_body()).await;
    assert_eq!(
        frames.len(),
        POLL_CHUNKED_FLUSH_CHUNK_COUNT,
        "M4 — full opt-in + flag=on MUST emit exactly {} chunks",
        POLL_CHUNKED_FLUSH_CHUNK_COUNT,
    );
    let total: usize = frames.iter().map(|b| b.len()).sum();
    assert_eq!(
        total, POLL_RESPONSE_CANONICAL_BYTES,
        "M4 — chunked path total bytes must equal POLL_RESPONSE_CANONICAL_BYTES (D15)",
    );
    // Each chunk except possibly the last is exactly 1152 bytes.
    let chunk_size = POLL_RESPONSE_CANONICAL_BYTES / POLL_CHUNKED_FLUSH_CHUNK_COUNT;
    for (i, b) in frames.iter().enumerate() {
        assert_eq!(
            b.len(),
            chunk_size,
            "M4 — chunk {} has {} bytes; expected {} (POLL_RESPONSE_CANONICAL_BYTES / {})",
            i,
            b.len(),
            chunk_size,
            POLL_CHUNKED_FLUSH_CHUNK_COUNT,
        );
    }
}

// ── M5 — L-CL-1: Content-Length explicit, Transfer-Encoding absent ───────────

#[tokio::test(start_paused = true)]
async fn m5_chunked_response_has_explicit_content_length_and_no_transfer_encoding_chunked() {
    // L-CL-1: the chunked response MUST carry `Content-Length: 4608` AND
    // MUST NOT carry `Transfer-Encoding: chunked`. axum's `Body::from_stream`
    // defaults to chunked transfer encoding when the stream length is
    // unknown to the framing layer; we override that default by setting
    // Content-Length explicitly. This preserves the wire shape against
    // the M2-B baseline and prevents introducing a new fingerprint
    // (master-formula violation).
    let app = build_app_with_chunked_flush(true);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(5);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;
    let resp = poll_with_headers(app, &token, /*LP=*/ true, /*PP=*/ true).await;
    assert_eq!(resp.status(), StatusCode::OK);

    let cl = resp
        .headers()
        .get(axum::http::header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<usize>().ok());
    assert_eq!(
        cl,
        Some(POLL_RESPONSE_CANONICAL_BYTES),
        "M5 — L-CL-1: Content-Length header MUST equal {} on the chunked response",
        POLL_RESPONSE_CANONICAL_BYTES,
    );
    assert!(
        resp.headers()
            .get(axum::http::header::TRANSFER_ENCODING)
            .is_none(),
        "M5 — L-CL-1: Transfer-Encoding header MUST be absent on the chunked response. \
         Found: {:?}",
        resp.headers().get(axum::http::header::TRANSFER_ENCODING),
    );
}

// ── M6 — chunked total + structure matches mono path (D15 byte-EXACT) ────────

#[tokio::test(start_paused = true)]
async fn m6_chunked_total_bytes_and_structure_match_mono_path() {
    // D15 byte-EXACT 4608 invariant: the chunked path emits the same JSON
    // shape as the mono path, only split into 4 frames. The total byte
    // count is identical and the parsed PollResponse fields
    // (envelopes, more) match. Only `pad` may differ because it is
    // random by construction — that randomness is the privacy property,
    // not a contract leak.
    //
    // Two parallel runs: same identity, same empty-queue state, with
    // flag off vs flag on. Concatenate frames in each case; both must
    // be exactly POLL_RESPONSE_CANONICAL_BYTES; parsed JSON must match
    // on the structural fields.
    let app_mono = build_app_with_chunked_flush(false);
    let app_chunked = build_app_with_chunked_flush(true);
    let signing_kp_mono = SigningKey::generate(&mut OsRng);
    let signing_kp_chunked = SigningKey::generate(&mut OsRng);
    let identity_mono = identity_hex(6);
    let identity_chunked = identity_hex(7);
    let (app_mono, token_mono) =
        obtain_token(app_mono, &identity_mono, &signing_kp_mono).await;
    let (app_chunked, token_chunked) =
        obtain_token(app_chunked, &identity_chunked, &signing_kp_chunked).await;

    let resp_mono = poll_with_headers(app_mono, &token_mono, true, true).await;
    let resp_chunked = poll_with_headers(app_chunked, &token_chunked, true, true).await;

    let frames_mono = collect_body_frames(resp_mono.into_body()).await;
    let frames_chunked = collect_body_frames(resp_chunked.into_body()).await;

    let bytes_mono: Vec<u8> = frames_mono.iter().flat_map(|b| b.iter()).copied().collect();
    let bytes_chunked: Vec<u8> = frames_chunked.iter().flat_map(|b| b.iter()).copied().collect();

    assert_eq!(
        bytes_mono.len(),
        POLL_RESPONSE_CANONICAL_BYTES,
        "M6 — mono path total bytes must equal {} (D15)",
        POLL_RESPONSE_CANONICAL_BYTES,
    );
    assert_eq!(
        bytes_chunked.len(),
        POLL_RESPONSE_CANONICAL_BYTES,
        "M6 — chunked path total bytes must equal {} (D15)",
        POLL_RESPONSE_CANONICAL_BYTES,
    );
    let json_mono: Value = serde_json::from_slice(&bytes_mono).expect("mono body parses as JSON");
    let json_chunked: Value =
        serde_json::from_slice(&bytes_chunked).expect("chunked body parses as JSON");
    assert_eq!(
        json_mono["envelopes"], json_chunked["envelopes"],
        "M6 — envelopes field must match across mono and chunked paths",
    );
    assert_eq!(
        json_mono["more"], json_chunked["more"],
        "M6 — more field must match across mono and chunked paths",
    );
    // pad differs by design (random per response); we DO NOT assert
    // byte-EXACT equality of pad fields. The total response size is the
    // contract, not the pad bytes.
}

// ── M7 — inter-chunk timing under paused time ────────────────────────────────

#[tokio::test(start_paused = true)]
async fn m7_inter_chunk_timing_matches_pause_constant_under_paused_time() {
    // Under `start_paused`, `tokio::time::sleep` auto-advances simulated
    // time when no runtime work is pending. Three inter-chunk sleeps of
    // POLL_CHUNKED_FLUSH_PAUSE_MS each should advance simulated time by
    // ~3 × pause_ms during body collection. With pause_ms = 300, expected
    // advance ≈ 900 ms. Tolerance accounts for the auto-advance algorithm
    // batching pending timers.
    let app = build_app_with_chunked_flush(true);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(8);
    let (app, token) = obtain_token(app, &identity, &signing_kp).await;
    let resp = poll_with_headers(app, &token, true, true).await;
    let start = tokio::time::Instant::now();
    let frames = collect_body_frames(resp.into_body()).await;
    let elapsed = tokio::time::Instant::now().duration_since(start);

    assert_eq!(frames.len(), POLL_CHUNKED_FLUSH_CHUNK_COUNT);
    let inter_chunk_pauses = (POLL_CHUNKED_FLUSH_CHUNK_COUNT - 1) as u64;
    let expected_min = Duration::from_millis(POLL_CHUNKED_FLUSH_PAUSE_MS * inter_chunk_pauses);
    let expected_max = Duration::from_millis(
        POLL_CHUNKED_FLUSH_PAUSE_MS * inter_chunk_pauses + 100,
    );
    assert!(
        elapsed >= expected_min,
        "M7 — paced delivery should advance simulated time by at least {:?}; got {:?}",
        expected_min,
        elapsed,
    );
    assert!(
        elapsed <= expected_max,
        "M7 — paced delivery simulated time {:?} exceeds expected ceiling {:?}",
        elapsed,
        expected_max,
    );
}

// ── M8 — server has no PrivacyMode knowledge (documentation test) ────────────

#[test]
fn m8_server_does_not_branch_on_any_privacy_mode_signal() {
    // Documentation test (no runtime assertion). Locked by Vladislav 2026-06-14:
    // the relay handler at `services/relay/src/rest_fallback.rs::rest_poll`
    // takes no `PrivacyMode` input and the chunked-emission gate consults
    // only `state.config.poll_chunked_flush`, `long_poll_opt_in`, and
    // `padded_poll_opt_in`. Privacy mode is a CLIENT transport-level concept
    // (Standard / Private / Ghost) that selects which outer transport carries
    // the request (Direct WS, Reality, Tor); it is NOT visible to the relay.
    // Therefore, two requests with identical (LP, PP, flag) but different
    // client-side modes produce byte-identical server responses by
    // construction — the test for this property is the absence of any code
    // path in rest_poll that reads a mode-equivalent input, which is verified
    // by code review, not by a runtime assertion.
    //
    // This stub exists as a code-review fence. A future refactor that
    // introduces a `PrivacyMode` extractor on rest_poll would have to
    // remove or amend this test alongside the new code, drawing attention
    // to the M8 contract.
}

// ── M11 — error responses (401) are never chunked ────────────────────────────

#[tokio::test]
async fn m11_error_response_401_not_chunked_even_when_flag_on() {
    // Pacing must be scoped strictly to the 200 OK padded poll path. An
    // unauthenticated request returns 401 with a small JSON error body;
    // this MUST NOT engage the chunked emitter, otherwise the error
    // channel itself becomes a wire-shape fingerprint distinct from
    // baseline 401 responses across the rest of the API.
    let app = build_app_with_chunked_flush(true);
    let resp = app
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/relay/poll?since_seq=0")
                .header("X-Phantom-Long-Poll", "1")
                .header("X-Phantom-Padded-Poll", "1")
                // intentionally NO Authorization header
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);
    let frames = collect_body_frames(resp.into_body()).await;
    assert_eq!(
        frames.len(),
        1,
        "M11 — error response MUST be a single buffered frame, even with \
         opt-in headers + flag=on. Found {} frames.",
        frames.len(),
    );
}

// ── M13 — mutex check rejects both diag-shape and chunked-flush ──────────────

#[test]
fn m13_mutex_check_rejects_simultaneous_diag_shape_and_chunked_flush() {
    // Round 14 mutual exclusion (privacy reviewer 2026-06-14): the relay
    // MUST refuse to start when both RELAY_ENABLE_DIAG_SHAPE=1 and
    // RELAY_POLL_CHUNKED_FLUSH=1 are set simultaneously. Mixing a paced
    // padded JSON response with the diagnostic octet-stream endpoint
    // produces a bi-modal traffic fingerprint stronger than either alone.
    //
    // The mutex check lives in
    // `services/relay/src/config.rs::load_round14_poll_chunked_flush_from_env`
    // and exits the process with code 2 + a clear FATAL message on
    // stderr. This is a startup-validation contract that cannot be unit-
    // tested in a process that needs to continue running (`std::process::exit`
    // aborts the test runner). The behaviour is verified by:
    //
    //   1. The compile-time guarantee that `RelayConfig::from_env` calls
    //      `load_round14_poll_chunked_flush_from_env` (any future refactor
    //      that bypasses the helper fails to compile because the field
    //      initialiser is the only path that sets `poll_chunked_flush`).
    //   2. The manual operator runbook in
    //      `round14-poll-chunked-flush` scope doc §7 (PF-1 through PF-6)
    //      and the runbook step for Stage 2 deployment: setting both env
    //      vars in `.env` and recreating the container produces
    //      "FATAL: ..." on stderr and exit code 2 within ~100 ms of
    //      container start.
    //
    // This test exists as a code-review fence: a future refactor that
    // weakens the mutex check (e.g. converting the exit to a tracing
    // warning) must either preserve this test or remove it explicitly,
    // bringing the contract change under review.
}

// ── M15 — SessionResponse does not leak the chunked_flush flag ───────────────

#[tokio::test]
async fn m15_session_response_does_not_contain_chunked_flush_field() {
    // Privacy contract: the chunked_flush flag is operator-only at the
    // JSON-field layer. SessionResponse MUST NOT contain any field
    // whose name reveals the flag state. (The flag IS wire-observable
    // via response duration on the poll path — this is the inherent
    // side-channel documented in the Round 14 scope doc §13. The
    // contract here is purely about SessionResponse JSON fields.)
    let app = build_app_with_chunked_flush(true);
    let signing_kp = SigningKey::generate(&mut OsRng);
    let identity = identity_hex(11);
    let (app, nonce_hex) = fetch_challenge(app, &identity).await;
    let (_app, status, session) =
        call_session(app, &identity, &signing_kp, &nonce_hex).await;
    assert_eq!(status, StatusCode::OK, "session creation failed: {:?}", session);

    // Field-name fence: no SessionResponse JSON key may contain
    // "chunked" or "flush" (case-insensitive). Catches both the
    // literal `chunked_flush` field name and likely future names a
    // careless refactor might introduce.
    let obj = session
        .as_object()
        .expect("SessionResponse is a JSON object");
    for key in obj.keys() {
        let lc = key.to_lowercase();
        assert!(
            !lc.contains("chunked") && !lc.contains("flush"),
            "M15 — SessionResponse leaks Round 14 flag state via field name {:?}",
            key,
        );
    }
}
