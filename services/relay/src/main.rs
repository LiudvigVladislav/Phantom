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
            tracing::debug!(
                "Cleanup: purged expired envelopes + previous SPKs + auth challenges"
            );
        }
    });

    axum::serve(listener, app).await.unwrap();
}
