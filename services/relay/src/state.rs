use crate::config::RelayConfig;
use crate::envelope::Envelope;
use std::collections::HashMap;
use tokio::sync::{mpsc, RwLock};

/// Per-sender sliding-window rate-limit entry.
/// The relay tracks only a counter and a window start timestamp —
/// no message content is inspected or stored here.
#[derive(Debug)]
pub struct RateEntry {
    pub count: u32,
    pub window_start: std::time::Instant,
}

/// In-memory store for Alpha-0.
/// Post-Alpha-0: replace with Redis or a persistent store.
pub struct AppState {
    pub config: RelayConfig,
    /// recipient_public_key_hex → queue of offline envelopes
    pub store: RwLock<HashMap<String, Vec<Envelope>>>,
    /// identity_hex → sender channel for live WebSocket clients
    pub clients: RwLock<HashMap<String, mpsc::UnboundedSender<String>>>,
    /// Rate limiter: message count per sender identity in current window.
    /// Only the sender's public-key hex and a counter are kept — no payload.
    pub rate_limiter: RwLock<HashMap<String, RateEntry>>,
}

impl AppState {
    pub fn new(config: RelayConfig) -> Self {
        Self {
            config,
            store: RwLock::new(HashMap::new()),
            clients: RwLock::new(HashMap::new()),
            rate_limiter: RwLock::new(HashMap::new()),
        }
    }
}
