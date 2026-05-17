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

    let cfg = config::RelayConfig::from_env();
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
        "phantom-relay starting"
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
