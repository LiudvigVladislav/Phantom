// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use std::sync::Arc;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

mod config;
mod envelope;
mod error;
mod routes;
mod state;

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| "phantom_relay=info".into()))
        .with(tracing_subscriber::fmt::layer())
        .init();

    let cfg = config::RelayConfig::from_env();
    let app_state = Arc::new(state::AppState::new(cfg.clone()));

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
        auth = cfg.secret_token.is_some(),
        "phantom-relay starting"
    );

    // Background task: purge expired envelopes every 5 minutes.
    // Recipients that never connect would otherwise accumulate stale envelopes
    // indefinitely; this ensures the in-memory store is bounded in practice.
    let cleanup_state = Arc::clone(&app_state);
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(300));
        loop {
            interval.tick().await;
            let mut store = cleanup_state.store.write().await;
            store.retain(|_, queue| {
                queue.retain(|e| !e.is_expired());
                !queue.is_empty()
            });
            tracing::debug!("Cleanup: purged expired envelopes");
        }
    });

    axum::serve(listener, app).await.unwrap();
}
