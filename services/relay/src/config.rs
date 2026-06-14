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

    // ── M2-B diag-shape (Trek 2 Round 12 follow-up) ───────────────────────────

    /// When `true`, the relay exposes `GET /diag-shape/{mode}` for the M2-B
    /// chunked-vs-monolithic carrier-path diagnostic. Default `false`. Set
    /// by env var `RELAY_ENABLE_DIAG_SHAPE=1` exactly; any other value
    /// (including `"true"` or `"yes"`) fails closed. When `false`, the
    /// route is NOT registered — any GET to `/diag-shape/*` returns 404.
    ///
    /// The endpoint streams a fixed 4608-byte body either monolithically
    /// (`mode = mono`) or as four 1152-byte chunks with 100 / 200 / 500
    /// millisecond `tokio::time::sleep` pauses between yields (`mode =
    /// chunked-100|chunked-200|chunked-500`). Total response size is
    /// held constant across modes so the diagnostic isolates the
    /// time-spreading axis.
    ///
    /// Wire-level behaviour (whether the requested pauses survive Caddy
    /// / TLS / the kernel TCP stack onto the wire) is verified by pcap
    /// on the VPS side, NOT inferred from the handler code.
    pub diag_shape_enabled: bool,

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
            // M2-B diag-shape is off in tests by default; tests that exercise
            // the handler construct a config with this flipped to `true`.
            diag_shape_enabled: false,
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
            // M2-B diag-shape: strict `"1"` parse, fails closed on any other
            // value. Mirrors slow_post_diag_enabled gate pattern. When
            // `false`, the `/diag-shape/{mode}` route is NOT registered
            // (returns 404 to any GET) — defence-in-depth, route off → 404.
            diag_shape_enabled: std::env::var("RELAY_ENABLE_DIAG_SHAPE")
                .map(|v| v == "1")
                .unwrap_or(false),
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
            .field("diag_shape_enabled", &self.diag_shape_enabled)
            .field("poll_hold_secs", &self.poll_hold_secs)
            // `seq_mac_key` carries its own `[REDACTED]` Debug impl from
            // `SeqMacRootKey`, but we still elide the wrapping `Arc` here
            // so a stray operator never sees the field in startup logs
            // even if the inner impl ever changed.
            .field("seq_mac_key", &"[REDACTED]")
            .finish()
    }
}
