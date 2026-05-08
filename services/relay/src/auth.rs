// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Per-user signed-challenge WebSocket authentication.
//!
//! Closes findings F11 (shared-secret WS token) and F26 (token leaks via APK
//! and proxy access logs) by replacing the relay-wide shared `?token=` with
//! a per-identity Ed25519 signed challenge bound to a server-issued nonce.
//!
//! Flow:
//!   1. Client → `GET /auth/challenge?identity={X25519_hex}` → relay returns
//!      a fresh 32-byte nonce with TTL.
//!   2. Client signs the nonce with its Ed25519 signing private key.
//!   3. Client opens WS with `?id={X25519}&signing_pubkey={Ed25519}
//!      &challenge={nonce_hex}&signature={sig_hex}`.
//!   4. Relay one-shot consumes the nonce, looks up the registered Ed25519
//!      signing key for this identity (TOFU on first connect — see
//!      [`crate::prekeys::PreKeyStore::get_or_register_signing_pubkey`]),
//!      verifies the signature.
//!
//! Replay protection: each issued nonce is tracked in a `HashMap` keyed by
//! `(identity, nonce_bytes)` and removed on first consume. Background sweep
//! drops expired entries every minute so the map cannot grow unbounded.

use rand::RngCore;
use std::collections::HashMap;
use tokio::sync::RwLock;

/// Per-issued-challenge state. Kept inside [`ChallengeStore`] until consumed
/// or expired.
#[derive(Debug, Clone)]
pub struct ChallengeEntry {
    pub nonce: [u8; 32],
    /// Wall-clock millisecond at which this entry stops being accepted.
    pub expires_at_ms: i64,
}

/// Default time-to-live for an issued challenge. The client must complete
/// the WS handshake within this window or fetch a fresh one. Five minutes
/// covers the worst-case path of "challenge issued → bridge / Tor warmup →
/// WS upgrade".
pub const CHALLENGE_TTL_MS: i64 = 5 * 60 * 1_000;

/// Length of the random nonce in bytes.
pub const NONCE_LEN: usize = 32;

/// In-memory store of outstanding challenges, keyed by `(identity, nonce)`.
///
/// We key on the pair (not just the identity) so the same identity can have
/// multiple outstanding challenges from concurrent reconnect attempts on
/// different transports (e.g. direct + Xray) without one starving the other.
/// Each WS handshake consumes the specific nonce it was given; a stale
/// challenge from a prior reconnect is simply ignored.
#[derive(Default)]
pub struct ChallengeStore {
    inner: RwLock<HashMap<(String, [u8; 32]), ChallengeEntry>>,
}

impl ChallengeStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Issue a fresh challenge for `identity`. Stores the entry under
    /// `(identity, nonce)` for one-shot consume by the WS upgrade.
    pub async fn issue(&self, identity: &str, now_ms: i64) -> ChallengeEntry {
        let mut nonce = [0u8; NONCE_LEN];
        rand::thread_rng().fill_bytes(&mut nonce);
        let entry = ChallengeEntry {
            nonce,
            expires_at_ms: now_ms + CHALLENGE_TTL_MS,
        };
        self.inner
            .write()
            .await
            .insert((identity.to_string(), nonce), entry.clone());
        entry
    }

    /// One-shot consume: removes the `(identity, nonce)` entry on success.
    /// Returns `Ok(())` if the challenge existed and was not expired,
    /// `Err(...)` otherwise. A second consume of the same nonce always
    /// fails — by definition the entry is gone after the first consume.
    pub async fn consume(
        &self,
        identity: &str,
        nonce: &[u8; 32],
        now_ms: i64,
    ) -> Result<(), AuthError> {
        let mut map = self.inner.write().await;
        let key = (identity.to_string(), *nonce);
        let entry = map.remove(&key).ok_or(AuthError::UnknownChallenge)?;
        if now_ms > entry.expires_at_ms {
            return Err(AuthError::ChallengeExpired);
        }
        Ok(())
    }

    /// Drop expired entries. Call from a background sweep so the map cannot
    /// grow unbounded if clients fetch challenges and never connect.
    pub async fn purge_expired(&self, now_ms: i64) {
        self.inner
            .write()
            .await
            .retain(|_, entry| entry.expires_at_ms >= now_ms);
    }

    #[cfg(test)]
    pub async fn len(&self) -> usize {
        self.inner.read().await.len()
    }
}

/// In-memory mapping from X25519 identity pubkey to its bound Ed25519 signing
/// pubkey, used by the WS auth path to verify the client signature.
///
/// Two write paths populate this store:
///
/// * **Publish bundle** — every successful `POST /prekeys/publish` calls
///   [`SigningKeyBindings::bind`], so once a client publishes, the binding is
///   identity-permanent (mismatch on a future bind returns
///   [`AuthError::SigningKeyMismatch`]).
/// * **TOFU first WS connect** — if a client has never published a bundle but
///   wants to come online (so it can later publish + receive), the WS handshake
///   uses [`SigningKeyBindings::get_or_register_tofu`]: an existing binding is
///   returned, otherwise the proposed key is recorded.
///
/// Persistence: not stored to disk in Alpha-2. Restart-time rebuild happens
/// via `PreKeyStore` JSONL replay (`AppState::rebuild_signing_keys_from_prekeys`).
/// Pure TOFU bindings (no publish yet) are lost on restart — the next
/// reconnect by the legitimate holder of the Ed25519 private key re-establishes
/// them.
#[derive(Default)]
pub struct SigningKeyBindings {
    inner: RwLock<HashMap<String, String>>,
}

impl SigningKeyBindings {
    pub fn new() -> Self {
        Self::default()
    }

    /// Bulk-load bindings from a pre-built (identity, signing_pubkey) iterator.
    /// Used at startup to seed the in-memory map from `PreKeyStore` entries.
    pub async fn load(&self, entries: impl IntoIterator<Item = (String, String)>) {
        let mut map = self.inner.write().await;
        for (identity, signing) in entries {
            if !signing.is_empty() {
                map.insert(identity, signing);
            }
        }
    }

    /// TOFU: returns the bound Ed25519 signing pubkey for `identity`. If no
    /// binding exists, atomically records `proposed` as the binding and
    /// returns it. The caller verifies that the returned pubkey matches the
    /// `proposed` one — a mismatch means an existing binding is in force,
    /// and the WS upgrade must be rejected with [`AuthError::SigningKeyMismatch`].
    pub async fn get_or_register_tofu(&self, identity: &str, proposed: &str) -> String {
        let mut map = self.inner.write().await;
        match map.get(identity) {
            Some(existing) => existing.clone(),
            None => {
                map.insert(identity.to_string(), proposed.to_string());
                proposed.to_string()
            }
        }
    }

    /// Bind `signing` to `identity` from the publish path. Returns
    /// [`AuthError::SigningKeyMismatch`] if a different signing key is
    /// already bound. No-op if the same key is already bound.
    pub async fn bind(&self, identity: &str, signing: &str) -> Result<(), AuthError> {
        let mut map = self.inner.write().await;
        match map.get(identity) {
            Some(existing) if existing != signing => Err(AuthError::SigningKeyMismatch),
            _ => {
                map.insert(identity.to_string(), signing.to_string());
                Ok(())
            }
        }
    }

    #[cfg(test)]
    pub async fn get(&self, identity: &str) -> Option<String> {
        self.inner.read().await.get(identity).cloned()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthError {
    /// No outstanding challenge matches the (identity, nonce) the client sent.
    /// Either the client never fetched a challenge, fetched one for a different
    /// identity, or the nonce was already consumed by a previous handshake.
    UnknownChallenge,
    /// The challenge existed but its TTL has elapsed. Client must fetch a
    /// fresh challenge and retry.
    ChallengeExpired,
    /// Ed25519 signature verification failed against the registered signing
    /// pubkey.
    BadSignature,
    /// Client supplied a `signing_pubkey` that does not match the one
    /// previously bound to this identity. Mirrors `PublishError::SigningKeyMismatch`.
    SigningKeyMismatch,
    /// Hex decode / length validation failed on the supplied auth fields.
    BadFormat,
}

impl AuthError {
    /// Stable message returned to clients. Deliberately terse so we do not
    /// give an attacker a way to distinguish "wrong nonce" from "wrong
    /// signature" through the response body — the relay log records the
    /// specific reason.
    pub fn public_message(&self) -> &'static str {
        "unauthorized"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn issue_then_consume_succeeds() {
        let store = ChallengeStore::new();
        let entry = store.issue("a".repeat(64).as_str(), 0).await;
        assert!(store
            .consume(&"a".repeat(64), &entry.nonce, 100)
            .await
            .is_ok());
    }

    #[tokio::test]
    async fn second_consume_fails() {
        let store = ChallengeStore::new();
        let entry = store.issue("a".repeat(64).as_str(), 0).await;
        store.consume(&"a".repeat(64), &entry.nonce, 100).await.unwrap();
        assert_eq!(
            store.consume(&"a".repeat(64), &entry.nonce, 100).await,
            Err(AuthError::UnknownChallenge),
        );
    }

    #[tokio::test]
    async fn expired_challenge_rejected() {
        let store = ChallengeStore::new();
        let entry = store.issue("a".repeat(64).as_str(), 0).await;
        let later = entry.expires_at_ms + 1;
        assert_eq!(
            store.consume(&"a".repeat(64), &entry.nonce, later).await,
            Err(AuthError::ChallengeExpired),
        );
    }

    #[tokio::test]
    async fn purge_expired_drops_old_entries() {
        let store = ChallengeStore::new();
        let _e1 = store.issue("a".repeat(64).as_str(), 0).await;
        let e2 = store.issue("b".repeat(64).as_str(), 1_000_000).await;
        store.purge_expired(CHALLENGE_TTL_MS + 100).await;
        // First entry expired (issued at 0, now > TTL); second still valid.
        assert_eq!(store.len().await, 1);
        assert!(store
            .consume(&"b".repeat(64), &e2.nonce, 1_000_001)
            .await
            .is_ok());
    }

    #[tokio::test]
    async fn different_nonce_for_same_identity_does_not_collide() {
        let store = ChallengeStore::new();
        let e1 = store.issue("a".repeat(64).as_str(), 0).await;
        let e2 = store.issue("a".repeat(64).as_str(), 1).await;
        assert_ne!(e1.nonce, e2.nonce);
        assert!(store.consume(&"a".repeat(64), &e1.nonce, 100).await.is_ok());
        assert!(store.consume(&"a".repeat(64), &e2.nonce, 100).await.is_ok());
    }
}
