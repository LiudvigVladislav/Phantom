// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use serde::{Deserialize, Serialize};

// `Deserialize` and `Serialize` remain in use by `Envelope`
// itself; the round-1 REDLINE removed only the three legacy
// DTO wrappers built on top of the base type.
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

// PR-2 M6-3 round-1 REDLINE P1-1: the legacy admin-token-guarded
// POST /send / GET /fetch/:recipient / DELETE /ack/:id endpoints
// were removed together with their handler fns in
// `services/relay/src/routes.rs`. The three DTO types
// (`SendRequest` / `FetchResponse` / `AckResponse`) that they
// carried are gone with them; nothing else in the crate uses
// them. The primary transport paths are the WS handler
// (`handle_socket`) and the REST fallback endpoints
// (`/relay/session`, `/relay/send`, `/relay/poll`,
// `/relay/ack-deliver`) which route through the shard-worker
// runtime.
