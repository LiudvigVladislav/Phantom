// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use std::sync::Arc;
use std::time::Duration;
use socket2::{SockRef, TcpKeepalive};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

// All implementation lives in lib.rs so that integration tests in
// `tests/` and any future tooling can drive the same router this binary
// serves. main.rs is just the runtime entry point.
use phantom_relay::{config, routes, state};

// ADR-014 (Transport TCP Keepalive Strategy, 2026-05-04):
// Defends the relay <-> Caddy <-> client TCP path against intermediate
// stateful NAT/firewall idle timeouts. Cellular carriers (notably MTS,
// Megafon, Beeline in Russia) deploy CGN with idle timeouts in the
// 50-90 s range. Without TCP-layer keepalive, an idle WSS connection is
// dropped by the NAT and any subsequent packet receives RST.
//
// 30/10/3 timing: probe after 30 s idle, 10 s between probes, 3 probes
// before declaring dead. Total dead-detection window 60 s, aligned with
// the client side (RelayTransportFactory.androidMain).
//
// We apply this per-accepted-connection. SO_KEEPALIVE is enabled by
// default on tokio TcpStream in many environments but the *timing*
// inherits kernel sysctls — we override explicitly so behaviour is
// independent of the host's net.ipv4.tcp_keepalive_* values.
const KEEPALIVE_IDLE_SECS: u64 = 30;
const KEEPALIVE_INTVL_SECS: u64 = 10;
const KEEPALIVE_PROBES: u32 = 3;

fn apply_keepalive(stream: &tokio::net::TcpStream) {
    let sock = SockRef::from(stream);

    // socket2 0.5 exposes `with_retries` only on Unix-family targets.
    // Windows-host development (cargo check on the dev workstation) would
    // fail to compile if we called it unconditionally. Production runs on
    // Linux inside the Docker container, where this branch is taken and
    // we get the full 30/10/3 timing. On Windows-host dev builds we still
    // get idle+interval; the probe count then comes from the kernel
    // tcp_keepalive_probes sysctl, which docker-compose pins to 3 in the
    // relay container's network namespace anyway.
    #[cfg(any(
        target_os = "linux",
        target_os = "android",
        target_os = "freebsd",
        target_os = "macos",
    ))]
    let ka = TcpKeepalive::new()
        .with_time(Duration::from_secs(KEEPALIVE_IDLE_SECS))
        .with_interval(Duration::from_secs(KEEPALIVE_INTVL_SECS))
        .with_retries(KEEPALIVE_PROBES);

    #[cfg(not(any(
        target_os = "linux",
        target_os = "android",
        target_os = "freebsd",
        target_os = "macos",
    )))]
    let ka = TcpKeepalive::new()
        .with_time(Duration::from_secs(KEEPALIVE_IDLE_SECS))
        .with_interval(Duration::from_secs(KEEPALIVE_INTVL_SECS));

    let _probe_default_used = KEEPALIVE_PROBES; // silence unused on non-Unix dev hosts

    if let Err(e) = sock.set_tcp_keepalive(&ka) {
        tracing::warn!(error = %e, "set_tcp_keepalive failed; connection will use kernel defaults");
    }
}

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
            tracing::debug!("Cleanup: purged expired envelopes + previous SPKs");
        }
    });

    axum::serve(KeepAliveListener(listener), app).await.unwrap();
}

// Wrapper around tokio::net::TcpListener that applies SO_KEEPALIVE +
// TCP_KEEPIDLE / TCP_KEEPINTVL / TCP_KEEPCNT to every accepted stream
// before handing it to axum/hyper. See ADR-014.
struct KeepAliveListener(tokio::net::TcpListener);

impl axum::serve::Listener for KeepAliveListener {
    type Io = tokio::net::TcpStream;
    type Addr = std::net::SocketAddr;

    async fn accept(&mut self) -> (Self::Io, Self::Addr) {
        // Mirrors axum's own TcpListener impl: loop with brief backoff on
        // transient accept errors. The single non-transient error is a
        // closed listener fd, which cannot recover; we exit the binary in
        // that case via expect() below.
        loop {
            match self.0.accept().await {
                Ok((stream, addr)) => {
                    apply_keepalive(&stream);
                    return (stream, addr);
                }
                Err(e) => {
                    tracing::warn!(error = %e, "TcpListener.accept transient error; backing off 50ms");
                    tokio::time::sleep(Duration::from_millis(50)).await;
                }
            }
        }
    }

    fn local_addr(&self) -> std::io::Result<Self::Addr> {
        self.0.local_addr()
    }
}
