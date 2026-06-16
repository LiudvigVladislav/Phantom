// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use std::sync::Arc;

use crate::seq_mac::SeqMacRootKey;

/// Relay configuration loaded from environment variables.
#[derive(Clone)]
pub struct RelayConfig {
    pub host: String,
    pub port: u16,
    /// Maximum envelope payload size in bytes (default 64 KB).
    pub max_payload_bytes: usize,
    /// How long an envelope is kept before being purged (seconds).
    pub envelope_ttl_secs: u64,
    /// Maximum envelopes stored per recipient public key.
    pub max_envelopes_per_recipient: usize,
    /// Optional shared secret for Alpha-0 access control.
    /// When set, every WebSocket connection must supply a matching `token` query
    /// parameter.  When absent the relay operates in open dev mode.
    /// Relay stores this only to compare — it never logs or forwards the value.
    pub secret_token: Option<String>,
    /// Max messages a single sender identity may send per rate-limit window.
    pub rate_limit_per_window: u32,
    /// Duration of the rate-limit sliding window in seconds.
    pub rate_limit_window_secs: u64,
    /// Base URL of the self-hosted ntfy distributor used for UnifiedPush wakeup
    /// (ADR-016). When set, the relay POSTs a one-byte payload to
    /// `<ntfy_url>/<topic>` after queueing an envelope for an offline recipient,
    /// where `<topic>` is the per-install token published via /push/register.
    /// When absent the relay skips push delivery entirely — no crash, no error.
    /// Set via environment variable: RELAY_NTFY_URL=https://ntfy.phntm.pro
    /// (or for the in-cluster Docker Compose case: http://ntfy:80).
    /// The relay never embeds Google FCM, Apple APNs, or any third-party
    /// push provider per ADR-001 / ADR-016 metadata-privacy posture.
    pub ntfy_url: Option<String>,

    // ── Media upload (PR-M1r) ─────────────────────────────────────────────────

    /// Hard cap on POST /media/upload-chunk body in bytes. Client targets ≤2600;
    /// relay enforces 3072. Different from `/relay/send`'s 4096 cap.
    pub max_media_upload_body_bytes: usize,
    /// Maximum number of chunks permitted per media object.
    pub max_media_chunks: u32,
    /// Maximum cumulative ciphertext bytes stored per `media_id` (1 MiB).
    pub max_media_bytes: u64,
    /// How long media chunks are retained before the sweeper removes them
    /// (seconds). Default 7 days.
    pub media_ttl_secs: u64,

    // ── Arm D heartbeat echo (PR-RC-DIRECT-STABILITY1) ────────────────────────

    /// When `true`, the relay echoes inbound WS Text frames whose payload
    /// starts with `phantom:diagnostic:heartbeat-echo:v1:` back to the
    /// sender as a Text frame. Default `false`. Set by env var
    /// `RELAY_ENABLE_HEARTBEAT_ECHO=1` exactly; any other value (including
    /// `"true"` or `"yes"`) fails closed.
    ///
    /// Locked design in `docs/tracks/rc-direct-stability1.md` §4 Arm D.
    /// The handler is a diagnostic primitive for the Arm D field test —
    /// it answers whether application data frames survive the conditions
    /// that kill WS control-plane Ping/Pong on the target carrier.
    pub heartbeat_echo_enabled: bool,

    // ── T2 slow-POST diagnostic (RC-DIRECT-STABILITY1 §10) ────────────────────

    /// When `true`, the relay exposes `POST /diag/slow-post` for the T2
    /// byte-threshold diagnostic. Default `false`. Set by env var
    /// `RELAY_ENABLE_SLOW_POST_DIAG=1` exactly; any other value (including
    /// `"true"` or `"yes"`) fails closed. When `false`, the route returns
    /// 404 — the handler is not registered (defence-in-depth per Vladislav
    /// 2026-06-06 design lock).
    ///
    /// The endpoint accepts a chunked POST body up to 64 KB, logs each
    /// received chunk progressively (`event=slow_post_chunk_received`
    /// with byte-counter), and either returns `200 OK` on body complete
    /// with `{"total_received": N, "duration_ms": T}` or logs
    /// `event=slow_post_aborted` if the connection drops mid-body.
    ///
    /// The diagnostic answers whether the carrier path "freezes" the
    /// TCP connection at a cumulative-bytes threshold (`net4people/bbs`
    /// Issue #490 hypothesis: 14-32 KB on RU mobile operators). Relay's
    /// `total_received` at abort point IS the primary discriminator
    /// (per Vladislav 2026-06-06 hard gate 2 — Android `total_sent` is
    /// secondary because `write()` only proves OkHttp queue accept, not
    /// physical radio egress).
    ///
    /// Locked design in `docs/tracks/rc-direct-stability1.md` §10 T2.
    pub slow_post_diag_enabled: bool,

    // ── T2 carrier-ceiling instrumentation (2026-06-16 reconnaissance Option A) ──

    /// When `true`, the relay enables the T2 carrier-ceiling slow-POST
    /// instrumentation suite:
    ///   - A tower body-wrapper middleware on `/prekeys/publish` emits
    ///     `event=t2_diag_publish_chunk` per HTTP/1.1 chunk (or HTTP/2 DATA
    ///     frame) consumed from the request body. Terminal events:
    ///     `t2_diag_publish_body_complete` on normal end-of-stream and
    ///     `t2_diag_publish_body_error` on read error.
    ///   - An outer "timeout-trigger" middleware on `/prekeys/publish`
    ///     detects the 30 s axum `TimeoutLayer` 408 response and emits
    ///     `event=t2_diag_publish_timeout` carrying the last observed
    ///     `cumulative_bytes` correlated by `request_id`.
    ///   - The probe endpoint `POST /diag-upstream-shape` is mounted
    ///     (analogous to `/diag/slow-post`, but designed for controlled
    ///     paced-chunk upstream POST measurement and instrumented with the
    ///     same per-chunk log shape).
    ///
    /// Default `false`. Set by env var `RELAY_T2_DIAG=1` exactly; any
    /// other value (including `"true"` / `"yes"` / unset) fails closed.
    /// Mirrors the strict-parse pattern of `slow_post_diag_enabled` and
    /// `poll_chunked_flush`.
    ///
    /// MUTUAL EXCLUSION (M13-style): `RELAY_T2_DIAG=1` and
    /// `RELAY_ENABLE_SLOW_POST_DIAG=1` are mutually exclusive in
    /// production — the relay refuses to start with `std::process::exit(2)`
    /// when both are set. Rationale: both diagnostics co-instrument the
    /// request-body stream and emit overlapping per-chunk log lines on
    /// adjacent endpoints; running them simultaneously would let a
    /// misdirected POST to `/diag/slow-post` skew the `t2_diag_publish_*`
    /// counters on a concurrent `/prekeys/publish` and vice-versa. The
    /// `poll_chunked_flush` mutex against `RELAY_ENABLE_DIAG_SHAPE`
    /// covers a different concern (response-side octet-stream conflict);
    /// this mutex covers request-side body-counter conflict.
    ///
    /// Locked design in `C:\temp\t2-reconnaissance-2026-06-16\synthesis\
    /// option-a-scope-lock.md` Item 5.
    pub t2_diag_enabled: bool,

    // ── Round 14 paced padded poll (Trek 2 Round 14) ──────────────────────────

    /// When `true`, the relay emits the padded `/relay/poll` response body
    /// as 4 chunks of 1152 bytes with 300 ms `tokio::time::sleep` pauses
    /// between yields, instead of buffering the full 4608-byte response
    /// and emitting it in one go. Total body shape is preserved
    /// byte-EXACT 4608 (D15 invariant); only wire-level emission timing
    /// changes. Default `false`. Set by env var
    /// `RELAY_POLL_CHUNKED_FLUSH=1` exactly; any other value (including
    /// `"true"` / `"yes"` / unset) fails closed.
    ///
    /// L-GATING-1 — chunked emission fires if and only if ALL THREE
    /// conditions hold at request time:
    ///   1. `X-Phantom-Long-Poll: 1` header present
    ///   2. `X-Phantom-Padded-Poll: 1` header present
    ///   3. `poll_chunked_flush == true` (this flag)
    ///
    /// IMPORTANT: this is STRICTLY narrower than the existing
    /// `padded_opt_in = long_poll_opt_in || padded_poll_opt_in` OR-gate
    /// at `rest_fallback.rs`. The legacy OR-gate stays as-is to preserve
    /// the Stage 1 / 2A / 2B-A wire contract. The new chunked path
    /// requires the strict AND because emitting a 4608-byte chunked
    /// body to a client that opted into only ONE of LP/PP would create
    /// a new client-distinguishable shape (R5 padding invariant
    /// violation per privacy reviewer 2026-06-14). The two gates serve
    /// different contracts and MUST NOT be collapsed by a future
    /// "simplification".
    ///
    /// MUTUAL EXCLUSION: `RELAY_POLL_CHUNKED_FLUSH=1` and
    /// `RELAY_ENABLE_DIAG_SHAPE=1` (the diagnostic octet-stream endpoint
    /// flag, if introduced) are mutually exclusive in production — the
    /// relay refuses to start with `std::process::exit(2)` when both are
    /// set simultaneously. Mixing a paced padded JSON response with the
    /// diagnostic octet-stream endpoint produces a bi-modal traffic
    /// fingerprint stronger than either alone. The mutex check happens
    /// in `RelayConfig::from_env` at startup.
    ///
    /// Locked design in `round14-poll-chunked-flush` scope doc
    /// 2026-06-14 (filed under M2-B field measurement working notes).
    pub poll_chunked_flush: bool,

    // ── Trek 2 Stage 1 long-poll (SHORT-CYCLE-LONGPOLL1) ──────────────────────

    /// Server-side hold-time (seconds) for `/relay/poll`. When > 0, an empty
    /// poll request blocks on a per-identity `tokio::sync::Notify` until
    /// either an envelope arrives (with ~50 ms coalescing) or this many
    /// seconds elapse. When 0, the endpoint returns immediately as today —
    /// this is the production kill switch.
    ///
    /// Announced to clients via `SessionResponse.poll_hold_secs`. Old
    /// clients ignore the field and keep polling on their own cadence;
    /// upgraded clients use it as the request timeout target.
    ///
    /// Default: 0 (short-poll). Override via env var `RELAY_POLL_HOLD_SECS`.
    /// Recommended Stage 2+ values per Trek 2 mini-lock: 10–25 s active /
    /// idle / long-idle on Standard + Private; 30 s on Ghost.
    pub poll_hold_secs: u32,

    // ── Trek 2 Stage 1.x sequence-MAC ─────────────────────────────────────────

    /// Relay-side root key for the Stage 1.x `seq_mac` integrity tag.
    ///
    /// Sourced from the `RELAY_SEQ_MAC_KEY` environment variable (64-char
    /// hex = 32 bytes). The relay process FAILS TO START if the variable
    /// is absent or malformed at production startup — see
    /// `RelayConfig::from_env`. `from_env_for_test` uses an all-zero
    /// fixture key so integration tests are deterministic.
    ///
    /// The root key NEVER leaves the relay process. The `Debug` impl on
    /// `SeqMacRootKey` redacts the bytes as `[REDACTED]`, and the
    /// `Zeroizing<[u8; 32]>` inner type wipes the bytes on drop. The
    /// client only ever sees a per-identity verify key derived via
    /// `SeqMacRootKey::derive_verify_key`.
    ///
    /// Wrapped in `Arc` so cloning `RelayConfig` does not copy the key
    /// bytes — the `Clone` derive on `RelayConfig` becomes refcount work
    /// rather than a memcpy of secret material.
    pub seq_mac_key: Arc<SeqMacRootKey>,
}

impl RelayConfig {
    /// Deterministic config for integration tests. Mirrors `from_env`
    /// but ignores the surrounding shell environment so tests don't pick
    /// up stray `RELAY_*` vars set by a developer's terminal session.
    pub fn from_env_for_test() -> Self {
        Self {
            host: "127.0.0.1".into(),
            port: 0,
            max_payload_bytes: 65_536,
            envelope_ttl_secs: 7 * 24 * 3600,
            max_envelopes_per_recipient: 500,
            secret_token: None,
            ntfy_url: None,
            rate_limit_per_window: 60,
            rate_limit_window_secs: 60,
            max_media_upload_body_bytes: crate::media::MAX_MEDIA_UPLOAD_BODY_BYTES,
            max_media_chunks: crate::media::MAX_MEDIA_CHUNKS,
            max_media_bytes: crate::media::MAX_MEDIA_BYTES,
            media_ttl_secs: 7 * 24 * 3600,
            // Arm D heartbeat echo is off in tests by default; tests that
            // exercise the echo handler construct a config with this flipped
            // to `true` rather than relying on the surrounding env.
            heartbeat_echo_enabled: false,
            // T2 slow-POST diagnostic is off in tests by default; tests that
            // exercise the handler construct a config with this flipped to
            // `true` rather than relying on the surrounding env.
            slow_post_diag_enabled: false,
            // T2 carrier-ceiling instrumentation off in tests by default;
            // tests that exercise the body-counter middleware or the
            // `/diag-upstream-shape` handler construct a config with this
            // flipped to `true`.
            t2_diag_enabled: false,
            // Round 14 paced padded poll is off in tests by default; tests that
            // exercise the chunked path construct a config with this set to
            // `true`.
            poll_chunked_flush: false,
            // Trek 2 Stage 1: long-poll hold disabled in tests by default;
            // tests that exercise the hold loop construct a config with
            // this set to a non-zero value (typically 1 s, with
            // `tokio::time::pause()` to make the wait deterministic).
            poll_hold_secs: 0,
            // Trek 2 Stage 1.x: deterministic test-fixture root key.
            // Tests that need byte-pinned MAC vectors construct a
            // `SeqMacRootKey::from_bytes(...)` directly with a known
            // value; this default of `[0u8; 32]` is the universal
            // "no real secret" placeholder.
            seq_mac_key: Arc::new(SeqMacRootKey::from_bytes([0u8; 32])),
        }
    }

    pub fn from_env() -> Self {
        Self {
            host: std::env::var("RELAY_HOST").unwrap_or_else(|_| "0.0.0.0".into()),
            port: std::env::var("RELAY_PORT")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(8080),
            max_payload_bytes: std::env::var("RELAY_MAX_PAYLOAD_BYTES")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(65_536),
            envelope_ttl_secs: std::env::var("RELAY_ENVELOPE_TTL_SECS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(7 * 24 * 3600), // 7 days
            max_envelopes_per_recipient: std::env::var("RELAY_MAX_ENVELOPES_PER_RECIPIENT")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(500),
            secret_token: std::env::var("RELAY_SECRET_TOKEN").ok(),
            ntfy_url: std::env::var("RELAY_NTFY_URL").ok(),
            rate_limit_per_window: std::env::var("RELAY_RATE_LIMIT_PER_WINDOW")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(60),
            rate_limit_window_secs: std::env::var("RELAY_RATE_LIMIT_WINDOW_SECS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(60),
            max_media_upload_body_bytes: std::env::var("RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(crate::media::MAX_MEDIA_UPLOAD_BODY_BYTES),
            max_media_chunks: std::env::var("RELAY_MAX_MEDIA_CHUNKS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(crate::media::MAX_MEDIA_CHUNKS),
            max_media_bytes: std::env::var("RELAY_MAX_MEDIA_BYTES")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(crate::media::MAX_MEDIA_BYTES),
            media_ttl_secs: std::env::var("RELAY_MEDIA_TTL_SECS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(7 * 24 * 3600), // 7 days
            // Arm D heartbeat echo: strict `"1"` parse. Any other value
            // (including `"true"`, `"yes"`, `"0"`, empty string, unset) fails
            // closed. A typo like `=true` therefore silently keeps the
            // diagnostic disabled — explicit activation is required.
            heartbeat_echo_enabled: std::env::var("RELAY_ENABLE_HEARTBEAT_ECHO")
                .map(|v| v == "1")
                .unwrap_or(false),
            // T2 slow-POST diagnostic: strict `"1"` parse, fails closed on
            // any other value. Mirrors heartbeat_echo_enabled gate pattern.
            // When `false`, the `/diag/slow-post` route is NOT registered
            // (returns 404 to any POST) — defence-in-depth per Vladislav
            // 2026-06-06 hard gate B (route off → 404, not live-405).
            slow_post_diag_enabled: std::env::var("RELAY_ENABLE_SLOW_POST_DIAG")
                .map(|v| v == "1")
                .unwrap_or(false),
            // T2 carrier-ceiling instrumentation: strict `"1"` parse, fails
            // closed on any other value. Mirrors slow_post_diag gate
            // pattern. Also enforces M13-style mutex against
            // `RELAY_ENABLE_SLOW_POST_DIAG` at startup (see
            // `load_t2_diag_from_env`).
            t2_diag_enabled: load_t2_diag_from_env(),
            // Round 14 paced padded poll: strict `"1"` parse, fails closed
            // on any other value. Mirrors slow_post_diag gate pattern.
            // Also enforces M13 mutex against `RELAY_ENABLE_DIAG_SHAPE` at
            // startup (see `load_round14_poll_chunked_flush_from_env`).
            poll_chunked_flush: load_round14_poll_chunked_flush_from_env(),
            // Trek 2 Stage 1: long-poll hold-time. Default 0 = short-poll
            // (existing behaviour). Operator opts-in by setting
            // `RELAY_POLL_HOLD_SECS=20` (or similar) in `.env` and restarting
            // the container; setting it back to 0 is the kill switch.
            //
            // Trek 2 Stage 1.x Lock-4 config-parse-time clamp — any value
            // above `MAX_POLL_HOLD_SECS_CAP` (480 s) is silently capped here
            // so the value announced to clients via `SessionResponse.poll_hold_secs`
            // is always within the ceiling. Pairs with the runtime per-hold
            // clamp inside `poll_hold_loop`; both layers are required so a
            // future code path that bypasses this clamp still cannot
            // exceed the ceiling.
            poll_hold_secs: std::env::var("RELAY_POLL_HOLD_SECS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(0u32)
                .min(crate::rest_fallback::MAX_POLL_HOLD_SECS_CAP),
            // Trek 2 Stage 1.x: root key for the `seq_mac` integrity tag.
            // This is the ONLY required env var without a fallback default
            // — the relay refuses to start if it is absent or malformed.
            // Rationale: silently running with no MAC key would ship a
            // relay that violates its own security contract; a startup
            // crash is the honest failure mode.
            //
            // Generate with `openssl rand -hex 32` and provision in the
            // VPS `.env` BEFORE redeploying the Stage 1.x relay image.
            seq_mac_key: Arc::new(load_seq_mac_root_key_from_env()),
        }
    }
}

/// Round 14 — parse `RELAY_POLL_CHUNKED_FLUSH` env var with strict
/// `"1"` semantics AND enforce mutual exclusion (M13) against the
/// `RELAY_ENABLE_DIAG_SHAPE` diagnostic flag.
///
/// Strict `"1"` parse: any value other than the literal string `"1"`
/// (including `"true"` / `"yes"` / empty / unset) evaluates to `false`.
/// Mirrors `RELAY_ENABLE_SLOW_POST_DIAG` and `RELAY_ENABLE_HEARTBEAT_ECHO`
/// patterns established at lines 217–231 of this file.
///
/// Mutual exclusion: if BOTH `RELAY_POLL_CHUNKED_FLUSH=1` and
/// `RELAY_ENABLE_DIAG_SHAPE=1` are set in env at startup, the relay
/// exits with `std::process::exit(2)` and a clear FATAL message.
/// Rationale: mixing a paced padded JSON response (Round 14) with the
/// diagnostic octet-stream endpoint produces a bi-modal traffic
/// fingerprint stronger than either alone (privacy reviewer 2026-06-14
/// Layer 2 finding). Operators MUST set only one of the two flags.
///
/// This implements M13 from the Round 14 scope doc test list as a
/// startup-validation contract.
/// T2 instrumentation (2026-06-16 Option A scope-lock Item 5) — parse
/// `RELAY_T2_DIAG` env var with strict `"1"` semantics AND enforce
/// mutual exclusion (M13-style) against `RELAY_ENABLE_SLOW_POST_DIAG`.
///
/// Strict `"1"` parse: any value other than the literal string `"1"`
/// (including `"true"` / `"yes"` / empty / unset) evaluates to `false`.
/// Mirrors `RELAY_ENABLE_SLOW_POST_DIAG`, `RELAY_ENABLE_HEARTBEAT_ECHO`,
/// and `RELAY_POLL_CHUNKED_FLUSH` patterns.
///
/// Mutual exclusion: if BOTH `RELAY_T2_DIAG=1` and
/// `RELAY_ENABLE_SLOW_POST_DIAG=1` are set in env at startup, the relay
/// exits with `std::process::exit(2)` and a clear FATAL message.
/// Rationale: both diagnostics co-instrument request-body streams and
/// emit overlapping per-chunk log lines on adjacent endpoints; a
/// misdirected POST to `/diag/slow-post` would skew the
/// `t2_diag_publish_*` counters on a concurrent `/prekeys/publish` and
/// vice-versa. This is a different concern from the
/// `RELAY_POLL_CHUNKED_FLUSH` ↔ `RELAY_ENABLE_DIAG_SHAPE` mutex (which
/// covers response-side octet-stream conflict).
///
/// This implements the M13-analogue startup-validation contract for the
/// T2 instrumentation suite (scope-lock Item 5 binding).
fn load_t2_diag_from_env() -> bool {
    let t2_diag = std::env::var("RELAY_T2_DIAG")
        .map(|v| v == "1")
        .unwrap_or(false);
    let slow_post_diag = std::env::var("RELAY_ENABLE_SLOW_POST_DIAG")
        .map(|v| v == "1")
        .unwrap_or(false);
    if t2_diag && slow_post_diag {
        eprintln!(
            "FATAL: RELAY_T2_DIAG=1 and RELAY_ENABLE_SLOW_POST_DIAG=1 \
             are mutually exclusive in production. Both diagnostics \
             co-instrument the request-body stream with overlapping \
             per-chunk log shapes on adjacent endpoints; running them \
             simultaneously would let a misdirected POST skew either \
             counter. Pick one for this run."
        );
        std::process::exit(2);
    }
    t2_diag
}

fn load_round14_poll_chunked_flush_from_env() -> bool {
    let chunked_flush = std::env::var("RELAY_POLL_CHUNKED_FLUSH")
        .map(|v| v == "1")
        .unwrap_or(false);
    let diag_shape = std::env::var("RELAY_ENABLE_DIAG_SHAPE")
        .map(|v| v == "1")
        .unwrap_or(false);
    if chunked_flush && diag_shape {
        eprintln!(
            "FATAL: RELAY_POLL_CHUNKED_FLUSH=1 and RELAY_ENABLE_DIAG_SHAPE=1 \
             are mutually exclusive in production. Mixing a paced padded \
             JSON poll response with the diagnostic octet-stream endpoint \
             produces a bi-modal traffic fingerprint stronger than either \
             alone. Unset one of these env vars and restart. \
             (Round 14 M13 startup-validation contract.)"
        );
        std::process::exit(2);
    }
    chunked_flush
}

/// Parse and validate the `RELAY_SEQ_MAC_KEY` env var, or panic with a
/// clear startup error.
fn load_seq_mac_root_key_from_env() -> SeqMacRootKey {
    let raw = std::env::var("RELAY_SEQ_MAC_KEY").unwrap_or_else(|_| {
        eprintln!(
            "FATAL: RELAY_SEQ_MAC_KEY is required (64 lowercase hex chars). \
             Generate one with: openssl rand -hex 32"
        );
        std::process::exit(2);
    });
    SeqMacRootKey::from_hex(&raw).unwrap_or_else(|err| {
        eprintln!("FATAL: {err}");
        std::process::exit(2);
    })
}

impl std::fmt::Debug for RelayConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RelayConfig")
            .field("host", &self.host)
            .field("port", &self.port)
            .field("max_payload_bytes", &self.max_payload_bytes)
            .field("envelope_ttl_secs", &self.envelope_ttl_secs)
            .field("max_envelopes_per_recipient", &self.max_envelopes_per_recipient)
            .field("secret_token", &self.secret_token.as_ref().map(|_| "[REDACTED]"))
            .field("rate_limit_per_window", &self.rate_limit_per_window)
            .field("rate_limit_window_secs", &self.rate_limit_window_secs)
            .field("ntfy_url", &self.ntfy_url)
            .field("max_media_upload_body_bytes", &self.max_media_upload_body_bytes)
            .field("max_media_chunks", &self.max_media_chunks)
            .field("max_media_bytes", &self.max_media_bytes)
            .field("media_ttl_secs", &self.media_ttl_secs)
            .field("heartbeat_echo_enabled", &self.heartbeat_echo_enabled)
            .field("slow_post_diag_enabled", &self.slow_post_diag_enabled)
            .field("t2_diag_enabled", &self.t2_diag_enabled)
            .field("poll_chunked_flush", &self.poll_chunked_flush)
            .field("poll_hold_secs", &self.poll_hold_secs)
            // `seq_mac_key` carries its own `[REDACTED]` Debug impl from
            // `SeqMacRootKey`, but we still elide the wrapping `Arc` here
            // so a stray operator never sees the field in startup logs
            // even if the inner impl ever changed.
            .field("seq_mac_key", &"[REDACTED]")
            .finish()
    }
}
