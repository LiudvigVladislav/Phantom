// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

/// A stored envelope — ciphertext only, relay never inspects payload.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Envelope {
    pub id: String,
    /// Recipient's public key hex — used as routing key only.
    pub to: String,
    /// Sender's public key hex — populated only for legacy (non-sealed) messages.
    /// For sealed-sender messages this is empty; the sender identity is hidden
    /// inside `sealed_sender` and is never visible to the relay.
    pub from: String,
    /// Opaque sealed-sender blob (base64). The relay never decrypts or inspects
    /// this field. When present, `from` is always empty.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub sealed_sender: String,
    /// Base64-encoded ciphertext blob.
    pub payload: String,
    /// Unix timestamp (seconds) when this envelope expires.
    pub expires_at: u64,
}

impl Envelope {
    pub fn new(
        id: String,
        to: String,
        from: String,
        sealed_sender: String,
        payload: String,
        ttl_secs: u64,
    ) -> Self {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        Self {
            id,
            to,
            from,
            sealed_sender,
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
    /// Empty string for sealed-sender messages.
    #[serde(default)]
    pub from: String,
    /// Opaque sealed-sender blob (base64). Mutually exclusive with `from`.
    /// The relay stores this verbatim without inspection.
    #[serde(default)]
    pub sealed_sender: String,
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
