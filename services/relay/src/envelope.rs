use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

/// A stored envelope — ciphertext only, relay never inspects payload.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Envelope {
    pub id: String,
    /// Recipient's public key hex — used as routing key only.
    pub to: String,
    /// Sender's public key hex — included for recipient's session lookup.
    pub from: String,
    /// Base64-encoded ciphertext blob.
    pub payload: String,
    /// Unix timestamp (seconds) when this envelope expires.
    pub expires_at: u64,
}

impl Envelope {
    pub fn new(id: String, to: String, from: String, payload: String, ttl_secs: u64) -> Self {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        Self {
            id,
            to,
            from,
            payload,
            expires_at: now + ttl_secs,
        }
    }

    pub fn is_expired(&self) -> bool {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        now >= self.expires_at
    }
}

/// Request body for POST /send
#[derive(Debug, Deserialize)]
pub struct SendRequest {
    pub id: String,
    pub to: String,
    pub from: String,
    pub payload: String,
}

/// Response body for GET /fetch/:recipient
#[derive(Debug, Serialize)]
pub struct FetchResponse {
    pub envelopes: Vec<Envelope>,
}

/// Response body for DELETE /ack/:id
#[derive(Debug, Serialize)]
pub struct AckResponse {
    pub acknowledged: String,
}
