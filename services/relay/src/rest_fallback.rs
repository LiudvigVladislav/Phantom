// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! REST fallback transport endpoints — PR-D0r.
//!
//! Motivation: Test #48 proved that the Tele2 LTE Иркутск middlebox passes
//! the WS Upgrade handshake (101 Switching Protocols) but silently drops all
//! subsequent WS frames (pings_received=0, inbound_frames=0, outbound_frames=0
//! across 20+ phone sessions, each lasting exactly ~153 s = server read
//! timeout). The same middlebox passes HTTP POST bodies but MAY drop the
//! response (Caddy logs: /prekeys/publish body=5863b status=201 duration=2.9ms
//! resp_size=18 — succeeded server-side, but client got SocketTimeoutException
//! after 60 s). Small GET responses (≤ ~100 bytes) pass reliably.
//!
//! These four endpoints give the Android client a pure HTTP/1.1 polling path
//! that avoids the WS frame layer entirely:
//!
//!   POST /auth/session   — bearer token issuance (single-round-trip auth)
//!   POST /relay/send     — one envelope per call, idempotent
//!   GET  /relay/poll     — poll for inbound envelope (returns at most 1)
//!   POST /relay/ack-deliver — remove envelope from server queue
//!
//! Design invariants:
//!   • Relay stores ciphertext only. No message content is inspected.
//!   • Bearer token is opaque to the relay; it maps to an identity internally.
//!   • Idempotency cache prevents double-delivery on retried POSTs.
//!   • Envelopes are retained in the existing per-recipient queue until
//!     the recipient sends /relay/ack-deliver. The WS live-delivery path
//!     is unmodified; REST and WS paths share the same store.
//!   • TTL on retained envelopes follows the existing envelope_ttl_secs config.

use std::{
    collections::HashMap,
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    Json,
};
use lru::LruCache;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;

use crate::{
    envelope::Envelope,
    push::wake_offline_recipient,
    state::{AppState, RateEntry},
};

// ── Constants ─────────────────────────────────────────────────────────────────

/// Bearer token TTL: 1 hour.
const TOKEN_TTL_MS: u64 = 3_600_000;

/// Challenge-cache TTL for replay-safe auth: 5 minutes.
const SESSION_CHALLENGE_TTL: Duration = Duration::from_secs(5 * 60);

/// Per-identity idempotency-key LRU cap: 10 K entries.
const IDEMPOTENCY_LRU_CAP: std::num::NonZeroUsize =
    std::num::NonZeroUsize::new(10_000).unwrap();

/// Idempotency-key TTL: 24 hours.
const IDEMPOTENCY_TTL: Duration = Duration::from_secs(24 * 3_600);

/// Maximum accepted request body for /relay/send.
pub const REST_MAX_BODY_BYTES: usize = 4_096;

/// How many envelopes /relay/poll returns per call (conservative — raise only
/// after empirical evidence that larger batches traverse the middlebox).
const POLL_MAX_ENVELOPES: usize = 1;

// ── Token store ───────────────────────────────────────────────────────────────

/// A single issued bearer token record.
#[derive(Clone)]
struct TokenRecord {
    /// Identity (X25519 hex pubkey) this token is bound to.
    identity: String,
    /// Absolute wall-clock expiry (ms since UNIX epoch).
    expires_at_ms: u64,
}

/// In-memory bearer token store.
/// Keyed by token string for O(1) validation from Authorization header.
/// Also maintains a secondary index identity → token for revocation and
/// session-replay lookups.
#[derive(Default)]
pub struct RestTokenStore {
    by_token: RwLock<HashMap<String, TokenRecord>>,
    /// identity → token (so we can check current token for that identity).
    by_identity: RwLock<HashMap<String, String>>,
}

impl RestTokenStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Issue a fresh 32-byte random bearer token for `identity`.
    /// Replaces any existing token for that identity.
    /// Returns (token_hex, expires_at_ms).
    pub async fn issue(&self, identity: &str) -> (String, u64) {
        let mut raw = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut raw);
        let token = hex::encode(raw);
        let now_ms = now_ms_u64();
        let expires_at_ms = now_ms + TOKEN_TTL_MS;
        let record = TokenRecord {
            identity: identity.to_string(),
            expires_at_ms,
        };
        {
            let mut by_token = self.by_token.write().await;
            // Remove old token if any so the by_token map stays bounded.
            if let Some(old_token) = self.by_identity.read().await.get(identity) {
                by_token.remove(old_token.as_str());
            }
            by_token.insert(token.clone(), record);
        }
        self.by_identity.write().await.insert(identity.to_string(), token.clone());
        (token, expires_at_ms)
    }

    /// Re-use an existing token for `identity` if it is still valid,
    /// extending its expiry by TOKEN_TTL_MS from now.
    /// Returns (token_hex, new_expires_at_ms) if a live token exists.
    pub async fn refresh_if_live(&self, identity: &str) -> Option<(String, u64)> {
        let existing_token = self.by_identity.read().await.get(identity)?.clone();
        let mut by_token = self.by_token.write().await;
        let rec = by_token.get_mut(&existing_token)?;
        let now_ms = now_ms_u64();
        if now_ms >= rec.expires_at_ms {
            return None;
        }
        // Extend lifetime.
        rec.expires_at_ms = now_ms + TOKEN_TTL_MS;
        Some((existing_token, rec.expires_at_ms))
    }

    /// Validate a token from the Authorization header.
    /// Returns the bound identity on success.
    pub async fn validate(&self, token: &str) -> Option<String> {
        let by_token = self.by_token.read().await;
        let rec = by_token.get(token)?;
        let now_ms = now_ms_u64();
        if now_ms >= rec.expires_at_ms {
            return None;
        }
        Some(rec.identity.clone())
    }

    /// Drop all expired tokens. Called from the background sweeper in main.rs.
    pub async fn purge_expired(&self) {
        let now_ms = now_ms_u64();
        let mut by_token = self.by_token.write().await;
        let mut by_identity = self.by_identity.write().await;
        by_token.retain(|_, rec| {
            if now_ms >= rec.expires_at_ms {
                by_identity.remove(&rec.identity);
                false
            } else {
                true
            }
        });
    }
}

// ── Challenge-replay cache for /auth/session retry-safety ────────────────────

/// Entry in the per-session challenge cache.
///
/// PR-D0r review fix (2026-05-16): the original cache was keyed only by
/// `(identity, challenge)` and returned the cached token on hit without
/// re-verifying the signature. That allowed an adversary who observed
/// an in-flight `(identity, challenge)` pair to mint a request with an
/// arbitrary signature and receive the legitimate client's token from
/// the cache. The fix is to bind the cache value to the exact
/// `(signing_pubkey, signature)` that earned the first issuance, and to
/// refuse replays where either differs.
#[derive(Clone)]
struct SessionCacheEntry {
    token: String,
    /// The `signing_pubkey` hex string the cache value was bound to.
    signing_pubkey: String,
    /// The `signature` hex string the cache value was bound to.
    signature: String,
    issued_at: Instant,
}

/// Outcome of a [`SessionChallengeCache::get`] lookup.
///
/// Three distinct cases so the handler can return the correct HTTP status:
///
///   - `Hit(token)`         → replay; safe to return the cached token.
///   - `SignatureMismatch`  → `(identity, challenge)` is in cache but the
///     supplied signature does not match the one that earned the original
///     token. Handler MUST return 401 Unauthorized.
///   - `Miss`               → no cached entry for this tuple; handler
///     proceeds with the normal verify+consume path.
#[derive(Debug, PartialEq, Eq)]
pub enum CacheLookup {
    Hit(String),
    SignatureMismatch,
    Miss,
}

/// Maps (identity, challenge_hex) → cached (token, signing_pubkey, signature).
#[derive(Default)]
pub struct SessionChallengeCache {
    inner: RwLock<HashMap<(String, String), SessionCacheEntry>>,
}

impl SessionChallengeCache {
    pub fn new() -> Self {
        Self::default()
    }

    /// Look up a cached token for `(identity, challenge)` and compare the
    /// supplied `signing_pubkey` + `signature` against the bound value.
    ///
    /// Returns:
    ///   - [`CacheLookup::Hit`] when a live cache entry exists AND both
    ///     `signing_pubkey` and `signature` match the bound originals.
    ///   - [`CacheLookup::SignatureMismatch`] when a live cache entry exists
    ///     but at least one of `signing_pubkey` / `signature` differs.
    ///   - [`CacheLookup::Miss`] when no entry exists or the entry is past
    ///     [`SESSION_CHALLENGE_TTL`].
    pub async fn get(
        &self,
        identity: &str,
        challenge: &str,
        signing_pubkey: &str,
        signature: &str,
    ) -> CacheLookup {
        let map = self.inner.read().await;
        let Some(entry) = map.get(&(identity.to_string(), challenge.to_string())) else {
            return CacheLookup::Miss;
        };
        if entry.issued_at.elapsed() > SESSION_CHALLENGE_TTL {
            return CacheLookup::Miss;
        }
        if entry.signing_pubkey != signing_pubkey || entry.signature != signature {
            return CacheLookup::SignatureMismatch;
        }
        CacheLookup::Hit(entry.token.clone())
    }

    /// Store `(identity, challenge) → (token, signing_pubkey, signature)`.
    /// Overwrites any existing entry.
    ///
    /// `_expires_at_ms` is accepted for API symmetry with the token store
    /// but purge is driven by `issued_at` elapsed time, not a wall-clock
    /// comparison, so the field is not stored.
    pub async fn put(
        &self,
        identity: &str,
        challenge: &str,
        token: &str,
        signing_pubkey: &str,
        signature: &str,
        _expires_at_ms: u64,
    ) {
        let mut map = self.inner.write().await;
        map.insert(
            (identity.to_string(), challenge.to_string()),
            SessionCacheEntry {
                token: token.to_string(),
                signing_pubkey: signing_pubkey.to_string(),
                signature: signature.to_string(),
                issued_at: Instant::now(),
            },
        );
    }

    /// Drop expired entries.
    pub async fn purge_expired(&self) {
        self.inner
            .write()
            .await
            .retain(|_, e| e.issued_at.elapsed() <= SESSION_CHALLENGE_TTL);
    }

}

// ── Idempotency cache for /relay/send ─────────────────────────────────────────

/// Cached response for an idempotency key.
#[derive(Clone)]
pub struct IdempotencyRecord {
    /// SHA-256 hex of the raw request body.
    pub body_hash: String,
    /// HTTP status code to replay.
    pub status: u16,
    /// The JSON body to replay.
    pub response_json: serde_json::Value,
    /// When this record was inserted (for TTL enforcement).
    pub inserted_at: Instant,
}

/// Per-identity LRU idempotency cache.
/// identity → LruCache<idempotency_key, IdempotencyRecord>
#[derive(Default)]
pub struct IdempotencyCache {
    inner: RwLock<HashMap<String, LruCache<String, IdempotencyRecord>>>,
}

impl IdempotencyCache {
    pub fn new() -> Self {
        Self::default()
    }

    /// Look up an existing idempotency record for (identity, key).
    pub async fn get(&self, identity: &str, key: &str) -> Option<IdempotencyRecord> {
        let mut map = self.inner.write().await; // write because LruCache::get updates MRU
        let lru = map.get_mut(identity)?;
        let rec = lru.get(key)?.clone();
        if rec.inserted_at.elapsed() > IDEMPOTENCY_TTL {
            return None;
        }
        Some(rec)
    }

    /// Store a new idempotency record for (identity, key).
    pub async fn put(
        &self,
        identity: &str,
        key: &str,
        body_hash: String,
        status: u16,
        response_json: serde_json::Value,
    ) {
        let mut map = self.inner.write().await;
        let lru = map
            .entry(identity.to_string())
            .or_insert_with(|| LruCache::new(IDEMPOTENCY_LRU_CAP));
        lru.put(
            key.to_string(),
            IdempotencyRecord {
                body_hash,
                status,
                response_json,
                inserted_at: Instant::now(),
            },
        );
    }

    /// Drop expired entries across all identities.
    pub async fn purge_expired(&self) {
        let mut map = self.inner.write().await;
        for lru in map.values_mut() {
            // LruCache has no retain; collect keys to remove.
            let stale: Vec<String> = lru
                .iter()
                .filter(|(_, v)| v.inserted_at.elapsed() > IDEMPOTENCY_TTL)
                .map(|(k, _)| k.clone())
                .collect();
            for k in stale {
                lru.pop(&k);
            }
        }
    }

}


// ── Monotonic sequence counter for /relay/poll ───────────────────────────────

/// Per-recipient monotonic sequence counter.
/// Incremented every time an envelope is accepted via /relay/send.
/// The `seq` value is attached to each envelope at insert time.
#[derive(Default)]
pub struct SeqCounter {
    inner: RwLock<HashMap<String, u64>>,
}

impl SeqCounter {
    pub fn new() -> Self {
        Self::default()
    }

    /// Increment and return the next seq for `recipient`.
    pub async fn next(&self, recipient: &str) -> u64 {
        let mut map = self.inner.write().await;
        let n = map.entry(recipient.to_string()).or_insert(0);
        *n += 1;
        *n
    }
}

// ── REST-specific envelope wrapper (carries seq) ──────────────────────────────

/// An envelope as stored/served over the REST poll path.
/// Wraps the existing `Envelope` and adds the monotonic `seq` counter
/// so clients can resume polling with `?since_seq=<n>`.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RestEnvelope {
    pub id: String,
    /// Sender pubkey hex. Empty for sealed-sender messages.
    pub from: String,
    /// Base64 sealed-sender blob (opaque to relay). Empty for legacy messages.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub sealed_sender: String,
    /// Base64 ciphertext blob.
    pub payload: String,
    /// Wall-clock timestamp when the sender called /relay/send (unix ms).
    pub sequence_ts: u64,
    /// Monotonic per-recipient counter. Used for ?since_seq= resume.
    pub seq: u64,
    /// Unix epoch seconds when this envelope expires.
    pub expires_at: u64,
}

impl RestEnvelope {
    fn is_expired(&self) -> bool {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        now >= self.expires_at
    }
}

// ── Store-mirror helpers (used by BOTH WS and REST send/ack paths) ───────────
//
// PR-D0r review fix (2026-05-16): originally `rest_send` wrote to both
// `state.store` and `state.rest_store`, but `state.store` writes from the
// WS send path did NOT mirror into `rest_store`. That meant a recipient
// on REST polling would silently miss messages sent by a WS sender.
// Similarly, WS ack-deliver removed from `state.store` but not from
// `rest_store`, so an envelope acked over WS would be re-delivered on
// the next REST poll. Both directions are now mirrored via these two
// helpers, called from both `rest_send` / `rest_ack_deliver` and the
// WS `Send` / `ack-deliver` arms in `routes.rs`.

/// Mirror an envelope into the REST poll store so a recipient on REST
/// polling sees envelopes regardless of which sender path (WS or REST)
/// originated them. Assigns the next monotonic `seq` for `to` and
/// returns it so the caller can attach the value to its own tracing logs.
///
/// Caller has already written the envelope into the primary `state.store`
/// (WS store). This function ONLY touches `state.rest_store`.
///
/// Per-recipient capacity is enforced exactly the same way as the inline
/// write in `rest_send` previously did: a dedup retain on `envelope_id`
/// followed by a length check against `config.max_envelopes_per_recipient`.
pub async fn mirror_envelope_to_rest_store(
    state: &AppState,
    to: &str,
    envelope_id: &str,
    sealed_sender: &str,
    payload: &str,
    sequence_ts: u64,
    expires_at: u64,
) -> u64 {
    let seq = state.rest_seq.next(to).await;
    let rest_env = RestEnvelope {
        id: envelope_id.to_string(),
        from: String::new(),
        sealed_sender: sealed_sender.to_string(),
        payload: payload.to_string(),
        sequence_ts,
        seq,
        expires_at,
    };
    let mut rest_store = state.rest_store.write().await;
    let queue = rest_store.entry(to.to_string()).or_default();
    // Dedup by envelope id (idempotency at the mirror layer too).
    queue.retain(|e: &RestEnvelope| !e.is_expired() && e.id != envelope_id);
    if queue.len() < state.config.max_envelopes_per_recipient {
        queue.push(rest_env);
    } else {
        tracing::warn!(
            envelope_id = %envelope_id,
            cap         = state.config.max_envelopes_per_recipient,
            "rest_store at capacity — mirror dropped"
        );
    }
    seq
}

/// Remove an envelope from `state.rest_store` for `recipient` — the
/// counterpart to [`mirror_envelope_to_rest_store`]. Called when an ACK
/// arrives over EITHER transport so the recipient's REST poll does not
/// re-deliver an already-processed envelope. Idempotent (removing a
/// non-existent entry is a no-op).
pub async fn remove_envelope_from_rest_store(
    state: &AppState,
    recipient: &str,
    envelope_id: &str,
) -> bool {
    let mut rest_store = state.rest_store.write().await;
    let Some(queue) = rest_store.get_mut(recipient) else {
        return false;
    };
    let before = queue.len();
    queue.retain(|e| e.id != envelope_id);
    before != queue.len()
}

// ── AppState extension ────────────────────────────────────────────────────────
//
// New fields are added to AppState in state.rs. This module provides the
// store types; state.rs wires them in. See state.rs for the full picture.

// ── Helper: extract bearer token from Authorization header ────────────────────

/// Returns the raw token string from `Authorization: Bearer <token>`.
/// Returns `None` if the header is absent or malformed.
pub fn extract_bearer(headers: &HeaderMap) -> Option<&str> {
    let val = headers.get("authorization")?.to_str().ok()?;
    val.strip_prefix("Bearer ")
}

/// Hash a request body with SHA-256 for idempotency body-match checks.
/// Returns the hex-encoded 32-byte digest.
///
/// PR-D0r review fix (2026-05-16): originally used `DefaultHasher` (SipHash
/// 1-3) which is not collision-resistant. The idempotency boundary decides
/// between 200-replay and 409-conflict — flipping that decision via a
/// crafted hash collision would let a client overwrite a previously
/// stored response under the same Idempotency-Key with a different body
/// and have the server silently treat it as a replay. SHA-256 closes that
/// gap; the cost is one transitive `sha2` dep that ed25519-dalek already
/// pulls in.
fn sha256_hex(data: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(data);
    hex::encode(digest)
}

// ── Request / response types ──────────────────────────────────────────────────

#[derive(Deserialize)]
pub struct SessionRequest {
    pub identity: String,
    pub signing_pubkey: String,
    pub challenge: String,
    pub signature: String,
}

#[derive(Serialize)]
pub struct SessionResponse {
    pub token: String,
    pub expires_at: u64,
    pub rest_fallback: bool,
    pub max_send_body_bytes: usize,
    pub poll_max_envelopes: usize,
}

#[derive(Deserialize)]
pub struct RestSendRequest {
    pub envelope_id: String,
    pub to: String,
    /// Sealed-sender envelope (base64 of `eph_pub || nonce || ct`). Optional
    /// for plain-from sends; required by the recipient to unseal the
    /// sender's identity. Mirrors the WS-side `Send.sealedSender` field —
    /// PR-D0r review fix (2026-05-16): the original `RestSendRequest`
    /// silently dropped this field, breaking sealed-message decrypt
    /// semantics for clients that use the REST fallback path. Defaults to
    /// empty when absent so existing/old clients still parse.
    #[serde(default)]
    pub sealed_sender: String,
    pub payload: String,
    pub sequence_ts: u64,
}

#[derive(Deserialize)]
pub struct PollQuery {
    pub since_seq: Option<u64>,
}

#[derive(Serialize)]
pub struct PollEnvelope {
    pub id: String,
    pub from: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub sealed_sender: String,
    pub payload: String,
    pub sequence_ts: u64,
    pub seq: u64,
}

#[derive(Serialize)]
pub struct PollResponse {
    pub envelopes: Vec<PollEnvelope>,
    pub more: bool,
}

#[derive(Deserialize)]
pub struct AckDeliverRequest {
    pub id: String,
}

// ── Handlers ──────────────────────────────────────────────────────────────────

/// POST /auth/session
///
/// Single-round-trip auth: client supplies (identity, signing_pubkey,
/// challenge, signature) and receives a bearer token.
///
/// Retry-safe: same (identity, challenge, signature) within 5 minutes → same
/// token returned. Different challenge → new token; old token stays valid.
///
/// Status codes:
///   200 — token returned (fresh or replayed from challenge cache)
///   400 — malformed request
///   401 — signature verification failed
///   410 — challenge expired or unknown
pub async fn rest_session(
    State(state): State<Arc<AppState>>,
    Json(req): Json<SessionRequest>,
) -> impl IntoResponse {
    // Basic shape validation.
    if req.identity.len() != 64
        || !req.identity.chars().all(|c| c.is_ascii_hexdigit())
    {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "identity must be 64 hex chars" })),
        )
            .into_response();
    }
    if req.signing_pubkey.len() != 64
        || !req.signing_pubkey.chars().all(|c| c.is_ascii_hexdigit())
    {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "signing_pubkey must be 64 hex chars" })),
        )
            .into_response();
    }
    if req.challenge.len() != 64
        || !req.challenge.chars().all(|c| c.is_ascii_hexdigit())
    {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "challenge must be 64 hex chars (32-byte nonce)" })),
        )
            .into_response();
    }
    if req.signature.len() != 128
        || !req.signature.chars().all(|c| c.is_ascii_hexdigit())
    {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "signature must be 128 hex chars (64-byte Ed25519)" })),
        )
            .into_response();
    }

    // Challenge-replay cache: same (identity, challenge, signing_pubkey,
    // signature) within 5 min → return the same token. The nonce was already
    // one-shot consumed on the first successful call; all subsequent calls
    // with the same full tuple skip the signature-verify + consume path
    // entirely. A replay with the same (identity, challenge) but a different
    // signature is treated as an attempt to mint a token under someone
    // else's challenge and rejected with 401.
    match state
        .rest_session_cache
        .get(&req.identity, &req.challenge, &req.signing_pubkey, &req.signature)
        .await
    {
        CacheLookup::Hit(_cached_token) => {
            // Return the live token if still valid; re-issue if it just
            // expired. The cached_token from the challenge cache is a
            // pointer to the original issuance; the token store is the
            // source of truth for current validity.
            let (token, expires_at) = match state.rest_tokens.refresh_if_live(&req.identity).await {
                Some((t, exp)) => (t, exp),
                None => state.rest_tokens.issue(&req.identity).await,
            };
            tracing::info!(
                event        = "rest_session_replay",
                identity     = %&req.identity[..8],
                token_prefix = %&token[..8],
                reason       = "challenge_cache_hit",
            );
            // Refresh the session-cache entry TTL, re-binding to the same
            // (signing_pubkey, signature) tuple so future replays continue
            // to match.
            state
                .rest_session_cache
                .put(
                    &req.identity,
                    &req.challenge,
                    &token,
                    &req.signing_pubkey,
                    &req.signature,
                    expires_at,
                )
                .await;
            return (
                StatusCode::OK,
                Json(SessionResponse {
                    token,
                    expires_at,
                    rest_fallback: true,
                    max_send_body_bytes: REST_MAX_BODY_BYTES,
                    poll_max_envelopes: POLL_MAX_ENVELOPES,
                }),
            )
                .into_response();
        }
        CacheLookup::SignatureMismatch => {
            tracing::warn!(
                event    = "rest_session_replay_rejected",
                identity = %&req.identity[..8],
                reason   = "signature_mismatch_on_cached_challenge",
            );
            return (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({
                    "error": "(identity, challenge) replay must use the original signature",
                })),
            )
                .into_response();
        }
        CacheLookup::Miss => {
            // Fall through to the normal verify + consume path.
        }
    }

    // Decode and verify Ed25519 signature.
    let nonce_vec = match hex::decode(&req.challenge) {
        Ok(v) => v,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "challenge hex decode failed" })),
            )
                .into_response()
        }
    };
    let nonce: [u8; 32] = match nonce_vec.try_into() {
        Ok(a) => a,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "challenge must be 32 bytes" })),
            )
                .into_response()
        }
    };

    let sig_vec = match hex::decode(&req.signature) {
        Ok(v) => v,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "signature hex decode failed" })),
            )
                .into_response()
        }
    };
    let sig_arr: [u8; 64] = match sig_vec.try_into() {
        Ok(a) => a,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "signature must be 64 bytes" })),
            )
                .into_response()
        }
    };

    let signing_vec = match hex::decode(&req.signing_pubkey) {
        Ok(v) => v,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "signing_pubkey hex decode failed" })),
            )
                .into_response()
        }
    };
    let signing_arr: [u8; 32] = match signing_vec.try_into() {
        Ok(a) => a,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "signing_pubkey must be 32 bytes" })),
            )
                .into_response()
        }
    };

    // One-shot consume the challenge nonce (same mechanism used by WS auth).
    let now_ms = now_ms_i64();
    match state.auth_challenges.consume(&req.identity, &nonce, now_ms).await {
        Ok(()) => {}
        Err(crate::auth::AuthError::UnknownChallenge) => {
            return (
                StatusCode::GONE,
                Json(serde_json::json!({ "error": "challenge unknown or already consumed" })),
            )
                .into_response();
        }
        Err(crate::auth::AuthError::ChallengeExpired) => {
            return (
                StatusCode::GONE,
                Json(serde_json::json!({ "error": "challenge expired" })),
            )
                .into_response();
        }
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "challenge validation error" })),
            )
                .into_response();
        }
    }

    // TOFU signing-key binding (mirrors WS auth path).
    let bound = state
        .signing_keys
        .get_or_register_tofu(&req.identity, &req.signing_pubkey)
        .await;
    if bound != req.signing_pubkey {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": "signing_pubkey mismatch with registered binding" })),
        )
            .into_response();
    }

    // Verify signature.
    use ed25519_dalek::{Signature, Verifier, VerifyingKey};
    let verifying_key = match VerifyingKey::from_bytes(&signing_arr) {
        Ok(k) => k,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "signing_pubkey is not a valid Ed25519 key" })),
            )
                .into_response()
        }
    };
    let signature = Signature::from_bytes(&sig_arr);
    if verifying_key.verify(&nonce, &signature).is_err() {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": "signature verification failed" })),
        )
            .into_response();
    }

    // Issue token.
    let (token, expires_at) = state.rest_tokens.issue(&req.identity).await;

    // Cache (identity, challenge) → (token, signing_pubkey, signature) for
    // retry-safe replay. A subsequent call with the same full tuple replays
    // the same token; a call with a different signature is rejected (see
    // CacheLookup::SignatureMismatch above).
    state
        .rest_session_cache
        .put(
            &req.identity,
            &req.challenge,
            &token,
            &req.signing_pubkey,
            &req.signature,
            expires_at,
        )
        .await;

    tracing::info!(
        event        = "rest_session_issued",
        identity     = %&req.identity[..8],
        token_prefix = %&token[..8],
    );

    (
        StatusCode::OK,
        Json(SessionResponse {
            token,
            expires_at,
            rest_fallback: true,
            max_send_body_bytes: REST_MAX_BODY_BYTES,
            poll_max_envelopes: POLL_MAX_ENVELOPES,
        }),
    )
        .into_response()
}

/// POST /relay/send
///
/// One envelope per call, idempotent via `Idempotency-Key` header.
///
/// Status codes:
///   201 — accepted, first delivery
///   200 — duplicate idempotency key with same body (no-op replay)
///   400 — malformed body or missing Idempotency-Key header
///   401 — missing/expired bearer token
///   403 — `to` field is blocklisted, OR token identity ≠ implied sender
///   409 — idempotency key reused with different body (client bug)
///   413 — body > 4096 bytes
pub async fn rest_send(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> impl IntoResponse {
    // 413 check first — before any other processing.
    if body.len() > REST_MAX_BODY_BYTES {
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(serde_json::json!({ "error": "body exceeds 4096 bytes" })),
        )
            .into_response();
    }

    // Auth.
    let token = match extract_bearer(&headers) {
        Some(t) => t,
        None => {
            return (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({ "error": "Authorization: Bearer <token> required" })),
            )
                .into_response()
        }
    };
    let sender_identity = match state.rest_tokens.validate(token).await {
        Some(id) => id,
        None => {
            return (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({ "error": "token missing, expired, or invalid" })),
            )
                .into_response()
        }
    };

    // Idempotency-Key header (required).
    let idem_key = match headers
        .get("idempotency-key")
        .and_then(|v| v.to_str().ok())
    {
        Some(k) => k.to_string(),
        None => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "Idempotency-Key header required" })),
            )
                .into_response()
        }
    };

    let body_hash = sha256_hex(&body);

    // Idempotency cache lookup.
    if let Some(cached) = state
        .rest_idempotency
        .get(&sender_identity, &idem_key)
        .await
    {
        if cached.body_hash == body_hash {
            tracing::info!(
                event       = "rest_send_dedup",
                envelope_id = %idem_key,
                reason      = "idempotency_cache_hit",
            );
            // Locked spec (2026-05-16): 201 on first delivery, 200 on
            // duplicate replay. The cached status is the original 201;
            // override to 200 here so the client can distinguish a fresh
            // accept from a server-deduped replay.
            return (StatusCode::OK, Json(cached.response_json)).into_response();
        } else {
            tracing::warn!(
                event       = "rest_send_dedup_conflict",
                envelope_id = %idem_key,
                reason      = "body_mismatch",
            );
            return (
                StatusCode::CONFLICT,
                Json(serde_json::json!({
                    "error": "Idempotency-Key reused with different body"
                })),
            )
                .into_response();
        }
    }

    // Parse body.
    let req: RestSendRequest = match serde_json::from_slice(&body) {
        Ok(r) => r,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "malformed JSON body" })),
            )
                .into_response()
        }
    };

    if req.envelope_id.is_empty() || req.to.is_empty() || req.payload.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({
                "error": "envelope_id, to, and payload are required"
            })),
        )
            .into_response();
    }

    // Blocklist check.
    {
        let bl = state.blocklist.read().await;
        if bl.contains(&sender_identity) || bl.contains(&req.to) {
            return (
                StatusCode::FORBIDDEN,
                Json(serde_json::json!({ "error": "blocked" })),
            )
                .into_response();
        }
    }

    // Rate-limit (reuses existing sliding-window limiter).
    let rate_ok = {
        let mut limiter = state.rate_limiter.write().await;
        let entry = limiter
            .entry(sender_identity.clone())
            .or_insert(RateEntry {
                count: 0,
                window_start: std::time::Instant::now(),
            });
        if entry.window_start.elapsed().as_secs() >= state.config.rate_limit_window_secs {
            entry.count = 1;
            entry.window_start = std::time::Instant::now();
            true
        } else if entry.count < state.config.rate_limit_per_window {
            entry.count += 1;
            true
        } else {
            false
        }
    };
    if !rate_ok {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({ "error": "rate limit exceeded" })),
        )
            .into_response();
    }

    // Persist to the shared envelope store (extend existing in-memory store).
    // The `from` field stays empty because the relay never inspects sender
    // identity; the `sealed_sender` blob is what the recipient unseals to
    // recover it. PR-D0r review fix (2026-05-16): `sealed_sender` from the
    // request body is now propagated end-to-end through both stores and the
    // live-delivery JSON so sealed-mode messages decrypt correctly on the
    // recipient.
    let envelope = Envelope::new(
        req.envelope_id.clone(),
        req.to.clone(),
        String::new(),
        req.sealed_sender.clone(),
        req.payload.clone(),
        state.config.envelope_ttl_secs,
    );

    // Mirror into the REST poll store via the shared helper so a recipient
    // on REST polling always sees the same envelope as a WS-reconnect client.
    let seq = mirror_envelope_to_rest_store(
        &state,
        &req.to,
        &req.envelope_id,
        &req.sealed_sender,
        &req.payload,
        req.sequence_ts,
        envelope.expires_at,
    )
    .await;

    // Also persist in the shared WS store so /ws reconnects see the same
    // envelope as a REST poller.
    {
        let mut store = state.store.write().await;
        let queue = store.entry(req.to.clone()).or_default();
        queue.retain(|e| !e.is_expired() && e.id != req.envelope_id);
        if queue.len() < state.config.max_envelopes_per_recipient {
            queue.push(envelope);
        }
    }

    // Live delivery via WS if recipient is currently online. The WS client
    // expects `sealedSender` in the deliver frame for sealed-mode messages.
    let deliver = serde_json::json!({
        "type":         "deliver",
        "from":         "",
        "sealedSender": req.sealed_sender,
        "payload":      req.payload,
        "messageId":    req.envelope_id,
    })
    .to_string();
    let delivered = {
        let clients = state.clients.read().await;
        if let Some((_, recipient_tx)) = clients.get(&req.to) {
            recipient_tx.send(deliver).is_ok()
        } else {
            false
        }
    };

    if !delivered {
        wake_offline_recipient(Arc::clone(&state), req.to.clone());
    }

    tracing::info!(
        event       = "rest_send_accepted",
        envelope_id = %req.envelope_id,
        from        = %&sender_identity[..8.min(sender_identity.len())],
        to          = %&req.to[..8.min(req.to.len())],
        size_b      = body.len(),
        seq         = seq,
    );

    let resp_json = serde_json::json!({ "ok": 1 });

    // Store in idempotency cache (TTL 24h, LRU cap 10K per identity).
    state
        .rest_idempotency
        .put(&sender_identity, &idem_key, body_hash, 201, resp_json.clone())
        .await;

    (StatusCode::CREATED, Json(resp_json)).into_response()
}

/// GET /relay/poll?since_seq=<n>
///
/// Returns at most 1 envelope per call. Does NOT remove the envelope —
/// wait for /relay/ack-deliver. Short-poll: returns immediately with an
/// empty array if nothing is queued.
///
/// `more: true` in the response indicates additional envelopes are waiting.
///
/// Status codes:
///   200 — success (may be empty)
///   401 — missing/expired bearer token
pub async fn rest_poll(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(q): Query<PollQuery>,
) -> impl IntoResponse {
    // Auth.
    let token = match extract_bearer(&headers) {
        Some(t) => t,
        None => {
            return (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({ "error": "Authorization: Bearer <token> required" })),
            )
                .into_response()
        }
    };
    let recipient_identity = match state.rest_tokens.validate(token).await {
        Some(id) => id,
        None => {
            return (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({ "error": "token missing, expired, or invalid" })),
            )
                .into_response()
        }
    };

    let since_seq = q.since_seq.unwrap_or(0);

    // Read from REST-specific store (carries seq counter).
    let (envelopes, more) = {
        let mut rest_store = state.rest_store.write().await;
        let queue = rest_store.entry(recipient_identity.clone()).or_default();
        // Purge expired.
        queue.retain(|e| !e.is_expired());
        // Filter by since_seq.
        let eligible: Vec<&RestEnvelope> =
            queue.iter().filter(|e| e.seq > since_seq).collect();
        let more = eligible.len() > POLL_MAX_ENVELOPES;
        let batch: Vec<PollEnvelope> = eligible
            .into_iter()
            .take(POLL_MAX_ENVELOPES)
            .map(|e| PollEnvelope {
                id: e.id.clone(),
                from: e.from.clone(),
                sealed_sender: e.sealed_sender.clone(),
                payload: e.payload.clone(),
                sequence_ts: e.sequence_ts,
                seq: e.seq,
            })
            .collect();
        (batch, more)
    };

    let envelope_id_log = envelopes.first().map(|e| e.id.as_str()).unwrap_or("");
    tracing::info!(
        event       = "rest_poll_returned",
        identity    = %&recipient_identity[..8.min(recipient_identity.len())],
        envelope_id = %envelope_id_log,
        more        = more,
    );

    (
        StatusCode::OK,
        Json(PollResponse { envelopes, more }),
    )
        .into_response()
}

/// POST /relay/ack-deliver
///
/// Removes the identified envelope from the server retention queue.
/// Idempotent: re-ack of an already-removed envelope returns 200.
///
/// Status codes:
///   200 — removed (or already absent)
///   400 — missing `id` field
///   401 — missing/expired bearer token
pub async fn rest_ack_deliver(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(req): Json<AckDeliverRequest>,
) -> impl IntoResponse {
    // Auth.
    let token = match extract_bearer(&headers) {
        Some(t) => t,
        None => {
            return (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({ "error": "Authorization: Bearer <token> required" })),
            )
                .into_response()
        }
    };
    let recipient_identity = match state.rest_tokens.validate(token).await {
        Some(id) => id,
        None => {
            return (
                StatusCode::UNAUTHORIZED,
                Json(serde_json::json!({ "error": "token missing, expired, or invalid" })),
            )
                .into_response()
        }
    };

    if req.id.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "id is required" })),
        )
            .into_response();
    }

    tracing::info!(
        event       = "rest_ack_deliver_received",
        envelope_id = %req.id,
    );

    // Remove from REST-specific store.
    let removed_rest = {
        let mut rest_store = state.rest_store.write().await;
        if let Some(queue) = rest_store.get_mut(&recipient_identity) {
            let before = queue.len();
            queue.retain(|e| e.id != req.id);
            before != queue.len()
        } else {
            false
        }
    };

    // Also remove from shared WS store (so WS reconnect doesn't redeliver).
    {
        let mut store = state.store.write().await;
        if let Some(queue) = store.get_mut(&recipient_identity) {
            queue.retain(|e| e.id != req.id);
        }
    }

    if removed_rest {
        tracing::info!(
            event       = "rest_ack_deliver_removed",
            envelope_id = %req.id,
        );
    } else {
        tracing::debug!(
            event       = "rest_ack_deliver_already_removed",
            envelope_id = %req.id,
        );
    }

    (StatusCode::OK, Json(serde_json::json!({ "ok": 1 }))).into_response()
}

// ── Time helpers ──────────────────────────────────────────────────────────────

fn now_ms_u64() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn now_ms_i64() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
