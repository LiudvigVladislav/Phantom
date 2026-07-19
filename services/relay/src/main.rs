// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use std::sync::Arc;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

// All implementation lives in lib.rs so that integration tests in
// `tests/` and any future tooling can drive the same router this binary
// serves. main.rs is just the runtime entry point.
use phantom_relay::{config, routes, state};

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| "phantom_relay=info".into()))
        .with(tracing_subscriber::fmt::layer())
        .init();

    // PR-0 A-5: from_env() now returns Result<RelayConfig, ConfigError>
    // so a misconfigured RELAY_STATE_DIR (relative path, `..` component,
    // empty string) fails FATAL at boot instead of silently landing
    // state files at an unpredictable location under the read_only
    // rootfs. Exit code 12 reserved for config-time errors — distinct
    // from PR-2's exit 10 (replay quarantine) and 11 (tombstone
    // config error) so an operator can distinguish causes.
    let cfg = match config::RelayConfig::from_env() {
        Ok(cfg) => cfg,
        Err(e) => {
            eprintln!("FATAL: relay boot config invalid: {e}");
            std::process::exit(12);
        }
    };

    // RC-RELAY-STATE-DIR-REPAIR PR-1b — Order B boot preflight:
    //   validate cfg (done above)
    //   → ensure state_dir exists
    //   → open state_dir/.lock and try_lock_exclusive
    //   → preflight sentinel write+fsync+unlink under the held lock
    //   → return the locked File; main binds it to `_state_dir_lock`
    //     so the singleton lock is held for the ENTIRE process lifetime.
    //
    // Contention → std::process::exit(2). Preflight failure → panic-loud.
    // Both are distinct from the config exit=12 above so an operator can
    // distinguish "config invalid" from "another relay is running" from
    // "state_dir not writable".
    let _state_dir_lock = state::state_dir_preflight(&cfg);

    let app_state = Arc::new(state::AppState::new(cfg.clone()));
    // F11 + F26: rebuild the WS-auth signing-key bindings from the
    // disk-replayed prekey store before serving traffic so a relay restart
    // keeps every previously-published identity's binding stable.
    app_state.rebuild_signing_keys_from_prekeys().await;

    let app = routes::router(Arc::clone(&app_state));

    let addr = format!("{}:{}", cfg.host, cfg.port);
    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();

    // PR-H1c (2026-05-13): aggressive TCP keepalive on the listener so each
    // accepted client connection picks up SO_KEEPALIVE + TCP_KEEPIDLE/INTVL/
    // CNT inherited from the listening socket. On Linux these options ARE
    // inherited at accept() time (per socket(7)).
    //
    // Test #35 motivation: server-side conn_id=5 sat in a half-open state
    // for 178 s after the client side noticed the socket was dead and
    // forceReconnect()'d. Default Linux tcp_keepalive_time is 7200 s — far
    // too long for a mobile messenger. With these values the relay surfaces
    // half-open sockets in ~30 s (15 s idle + 3 × 5 s probes), matching
    // the client's PR-H1c proactive-reconnect window. The two sides become
    // symmetric: neither holds a zombie session for minutes.
    //
    // `#[cfg(unix)]` because socket2's `TcpKeepalive::with_retries(...)`
    // (which sets TCP_KEEPCNT) is unavailable on Windows. The relay
    // ships in a Linux container in production (deploy/docker-compose.yml);
    // Windows builds are local dev only and run without the tuned
    // keepalive — the kernel's default applies, which is acceptable for
    // dev where mobile NAT eviction is not in scope.
    #[cfg(unix)]
    {
        use socket2::{SockRef, TcpKeepalive};
        use std::time::Duration;
        let sock_ref = SockRef::from(&listener);
        let keepalive = TcpKeepalive::new()
            .with_time(Duration::from_secs(15))
            .with_interval(Duration::from_secs(5))
            .with_retries(3);
        if let Err(e) = sock_ref.set_tcp_keepalive(&keepalive) {
            tracing::warn!(
                error = %e,
                "TCP keepalive setup failed on listener — accepted sockets will use kernel defaults",
            );
        } else {
            tracing::info!(
                idle_s = 15, interval_s = 5, retries = 3,
                "TCP keepalive configured on listener (PR-H1c)",
            );
        }
    }

    // Startup banner — key operational parameters logged once at boot.
    // Trust boundary: only operational metadata, never secrets.
    tracing::info!(
        host = %cfg.host,
        port = cfg.port,
        max_payload_kb = cfg.max_payload_bytes / 1024,
        ttl_days = cfg.envelope_ttl_secs / 86400,
        rate_limit = cfg.rate_limit_per_window,
        admin_token_set = cfg.secret_token.is_some(),
        ws_auth = "signed-challenge (Ed25519)",
        state_dir = %cfg.state_dir.display(),
        "phantom-relay starting"
    );

    // Arm D heartbeat echo flag state — logged at startup so an operator
    // running the RC-DIRECT-STABILITY1 Arm D field test can confirm at the
    // banner whether the env var was picked up before the first Android
    // connection arrives. When the flag is `false` (production default),
    // this is just a one-line audit trail; when `true`, it is a load-bearing
    // signal that the diagnostic handler is live.
    tracing::info!(
        heartbeat_echo_enabled = cfg.heartbeat_echo_enabled,
        slow_post_diag_enabled = cfg.slow_post_diag_enabled,
        t2_diag_enabled = cfg.t2_diag_enabled,
        poll_chunked_flush = cfg.poll_chunked_flush,
        "relay feature flags"
    );

    // Background task: purge expired envelopes every 5 minutes.
    // Recipients that never connect would otherwise accumulate stale envelopes
    // indefinitely; this ensures the in-memory store is bounded in practice.
    // Also purges previous-SPK records past their retention window so the
    // ADR-009 14-day grace period gets enforced even on a long-running relay.
    let cleanup_state = Arc::clone(&app_state);
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(300));
        loop {
            interval.tick().await;
            {
                let mut store = cleanup_state.store.write().await;
                store.retain(|_, queue| {
                    queue.retain(|e| !e.is_expired());
                    !queue.is_empty()
                });
            }
            let now_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);
            cleanup_state.prekeys.purge_expired_previous_spks(now_ms).await;
            cleanup_state.auth_challenges.purge_expired(now_ms).await;
            // PR-D0r: purge expired REST fallback state.
            cleanup_state.rest_tokens.purge_expired().await;
            cleanup_state.rest_session_cache.purge_expired().await;
            cleanup_state.rest_idempotency.purge_expired().await;
            {
                let mut rest_store = cleanup_state.rest_store.write().await;
                rest_store.retain(|_, queue| {
                    queue.retain(|e| {
                        use std::time::{SystemTime, UNIX_EPOCH};
                        let now = SystemTime::now()
                            .duration_since(UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_secs();
                        now < e.expires_at
                    });
                    !queue.is_empty()
                });
            }
            // Trek 2 Stage 1 Q6 — evict idle `notifiers` entries to bound
            // map size. Drop ONLY entries whose `Arc::strong_count == 1`
            // — that means the only owner is the map itself (no in-flight
            // poll waiter holds a clone, no concurrent `notify_recipient`
            // send call is mid-execution with a clone). This avoids the
            // drop-while-register race where eviction would strand a
            // waiter without a notifier the send path can reach
            // (security-reviewer Finding 3.A).
            //
            // Trade-off in the race window: if cleanup drops the entry
            // just before `/relay/send` does `notify_recipient`, the
            // send simply finds no entry and is a no-op — the envelope
            // is still in `rest_store` for the next poll cycle, so
            // Guardrail A "delivery never lost" holds.
            {
                let mut notifiers = cleanup_state.notifiers.write().await;
                let before = notifiers.len();
                notifiers.retain(|_, arc| std::sync::Arc::strong_count(arc) > 1);
                let dropped = before.saturating_sub(notifiers.len());
                if dropped > 0 {
                    tracing::debug!(
                        event   = "trek2_notifiers_cleanup",
                        dropped = dropped,
                        kept    = notifiers.len(),
                    );
                }
            }
            tracing::debug!(
                "Cleanup: purged expired envelopes + previous SPKs + auth challenges + REST state"
            );
        }
    });

    // Background task: sweep expired media chunks every hour (PR-M1r).
    // TTL is per-media-entry: if the earliest chunk is older than media_ttl_secs,
    // the entire entry is removed. Relay never touches content; only metadata
    // (media_id prefix, chunk count, age) is logged.
    let media_sweep_state = Arc::clone(&app_state);
    tokio::spawn(async move {
        let ttl_secs = media_sweep_state.config.media_ttl_secs;
        let ttl_ms = ttl_secs * 1_000;
        let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(3_600));
        loop {
            interval.tick().await;
            let swept = media_sweep_state.media_store.sweep_expired(ttl_ms).await;
            if swept > 0 {
                tracing::info!(swept = swept, "media sweeper removed expired entries");
            } else {
                tracing::debug!("media sweeper: no expired entries");
            }
        }
    });

    axum::serve(listener, app).await.unwrap();
}
