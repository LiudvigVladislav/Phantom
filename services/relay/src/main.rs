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
    let state = Arc::new(state::AppState::new(cfg.clone()));

    let app = routes::router(state);

    let addr = format!("{}:{}", cfg.host, cfg.port);
    tracing::info!("phantom-relay listening on {}", addr);

    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
