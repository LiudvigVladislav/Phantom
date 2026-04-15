use crate::config::RelayConfig;
use crate::envelope::Envelope;
use std::collections::HashMap;
use tokio::sync::RwLock;

/// In-memory store for Alpha-0.
/// Post-Alpha-0: replace with Redis or a persistent store.
pub struct AppState {
    pub config: RelayConfig,
    /// recipient_public_key_hex → queue of envelopes
    pub store: RwLock<HashMap<String, Vec<Envelope>>>,
}

impl AppState {
    pub fn new(config: RelayConfig) -> Self {
        Self {
            config,
            store: RwLock::new(HashMap::new()),
        }
    }

    /// Purge expired envelopes from all queues.
    pub async fn purge_expired(&self) {
        let mut store = self.store.write().await;
        for queue in store.values_mut() {
            queue.retain(|e| !e.is_expired());
        }
        store.retain(|_, queue| !queue.is_empty());
    }
}
