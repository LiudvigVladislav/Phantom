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
/// Trek 2 Stage 1 mini-lock Q8 keeps this at 1 — raising requires a Double
/// Ratchet skip-key store audit (see `project_trek2_minilock_draft_2026_06_09.md`
/// cross-cutting invariant 7).
const POLL_MAX_ENVELOPES: usize = 1;

// ── Trek 2 Stage 1 long-poll (SHORT-CYCLE-LONGPOLL1) constants ───────────────

/// Canonical body size (bytes) every `PollResponse` is padded to so that
/// empty-poll responses and envelope-bearing poll responses are
/// byte-indistinguishable on the wire (Q4 lock 2026-06-09 after audit:
/// max realistic envelope-bearing body ≈ 4154 bytes; 4608 leaves ≈ 454
/// bytes padding margin and stays comfortably under the Tele2 ~5 KB
/// cellular-middlebox cutoff documented in `project_t2_outcome_2026_06_05.md`).
///
/// Guardrail C — padding can be tuned later but NEVER reduced silently;
/// any change requires tcpdump-based size-distribution proof first.
pub const POLL_RESPONSE_CANONICAL_BYTES: usize = 4_608;

/// Max distinct identities tracked in `AppState.notifiers` before the
/// long-poll path degrades to immediate-return for new identities
/// (defends against map-amplification: a single attacker authenticating
/// as many identities and spamming polls would otherwise blow the map).
/// Parallels `IDEMPOTENCY_LRU_CAP`.
pub const POLL_HOLD_NOTIFIERS_LRU_CAP: usize = 50_000;

/// Coalescing delay (ms) between a `notify_one()` wake and the
/// post-wake queue re-check. Lets a burst of `/relay/send` calls
/// for the same recipient batch into one poll response without
/// waiting the full hold timeout (MTProto-style pattern).
const POLL_HOLD_COALESCE_MS: u64 = 50;

/// Rate-limit window count for `/relay/ack-deliver` per recipient
/// identity. SEPARATE from `RelayConfig.rate_limit_per_window`
/// (which counts `/relay/send` per sender) because the same identity
/// can be both sender and recipient (self-test, system identities) —
/// sharing the bucket would break independence (Q2 lock).
pub const ACK_DELIVER_RATE_LIMIT_PER_WINDOW: u32 = 120;

/// HTTP request header that signals "this client knows about long-poll
/// and has raised its socket read/call timeouts to accommodate up to
/// `poll_hold_secs` of server-side hold time." Without this header,
/// `/relay/poll` ALWAYS returns immediately (short-poll), regardless
/// of `RelayConfig.poll_hold_secs`. With this header set to `"1"`,
/// the server applies the configured hold.
///
/// Why request-level opt-in: existing Android clients on `master` have
/// `CALL_TIMEOUT_MS = READ_TIMEOUT_MS = 10_000`. If the operator sets
/// `RELAY_POLL_HOLD_SECS=20` globally, an old client polling on its
/// short-poll cadence would see its socket timeout (10 s) fire long
/// before the server's hold (20 s) returns, breaking the "old clients
/// keep working unchanged" Stage 1 contract. Stage 2 client work
/// raises the timeout AND sets this header in the same flight, so the
/// upgrade is atomic from the client's perspective.
///
/// Value contract: `"1"` enables hold, anything else (including
/// absence, empty string, `"true"`, `"yes"`, `"0"`) keeps short-poll
/// behaviour. Strict equality so a typo cannot accidentally opt in.
pub const LONG_POLL_OPT_IN_HEADER: &str = "x-phantom-long-poll";

/// Trek 2 Stage 1.x Lock-2 — opt-in header that gates the **padded body
/// shape** independently of the hold path. Separate from
/// [`LONG_POLL_OPT_IN_HEADER`] so a client can request the padded 4608-
/// byte response without also requesting a server-side hold, and vice
/// versa.
///
/// 4-cell wire contract:
/// - LP absent, PP absent → short-poll, legacy small body
/// - LP present, PP absent → hold up to `poll_hold_secs`, padded 4608 bytes (Stage 1 legacy preserved)
/// - LP absent, PP present → short-poll, padded 4608 bytes (Stage 2B-A circuit-breaker fallback)
/// - LP present, PP present → hold up to `poll_hold_secs`, padded 4608 bytes
///
/// Padded gate is `padded_opt_in = long_poll_opt_in || padded_poll_opt_in`,
/// so an LP-only request keeps the original Stage 1 hold+padded
/// behaviour. The decoupling exists so a Stage 2B client whose
/// circuit-breaker drops LP can still send PP and keep the padded
/// posture — closes the guardrail C ("padding never reduced silently")
/// gap during the breaker's transient short-poll period.
///
/// Value contract mirrors [`LONG_POLL_OPT_IN_HEADER`]: strict equality
/// to `"1"`. Server stays stateless — no per-identity persistent flag.
pub const PADDED_POLL_OPT_IN_HEADER: &str = "x-phantom-padded-poll";

/// Trek 2 Stage 1 — quantize a millisecond wall-clock timestamp to the
/// nearest 60-second boundary by flooring (`ts - (ts % 60_000)`).
/// Removes sub-minute precision from the relay-visible `sequence_ts`
/// field per Q5 lock. Applied unconditionally on the server side; the
/// relay does NOT trust the client to pre-quantize. `u64::MAX` is safe
/// because Rust modulo arithmetic on `u64` does not panic.
#[inline]
pub const fn quantize_sequence_ts_to_60s(ts_ms: u64) -> u64 {
    ts_ms - (ts_ms % 60_000)
}

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
    ///
    /// # Trek 2 Stage 1.x Lock-3 invalidation invariant
    ///
    /// Removes the prior token from `by_token` under write lock BEFORE
    /// inserting the new token, then updates `by_identity` to the new
    /// token. Any concurrent `validate()` call during the window
    /// between the two lock acquisitions observes at most a transient
    /// `None` — never a successful validate against the prior token.
    /// The window is benign in consequence: the old token is
    /// structurally unreachable from any path that requires a
    /// `by_token` hit.
    ///
    /// Contract pinned by the
    /// `concurrent_issue_for_same_identity_is_serialised` test in
    /// `tests/token_invalidation.rs`: after 3 concurrent `issue()` calls
    /// for the same identity with distinct challenges, exactly one
    /// returned token validates; the other two return `None`. If that
    /// test ever fails, the implementation strategy moves from
    /// invariant-driven to structural refactor (single critical section
    /// across both maps).
    ///
    /// Single-device-per-identity assumption: `by_identity` maps
    /// `identity → one token`. Multi-device support would require
    /// `identity → Vec<(device_id, token)>` and a `device_id`
    /// parameter on `issue()` so a new-device token only invalidates
    /// that device's prior token, not all devices'. Documented here so
    /// a future multi-device migration is explicit.
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
    /// Trek 2 Stage 1.x `seq_mac` — HMAC-SHA-256 integrity tag computed
    /// at store time over `(identity_hex, seq, envelope_id, sequence_ts)`
    /// using the per-identity verify key derived from `RELAY_SEQ_MAC_KEY`.
    /// 64-char lowercase hex. See `seq_mac::SeqMacVerifyKey::compute_seq_mac`
    /// for the canonical input encoding and the threat-model wording on
    /// `PollEnvelope::seq_mac`.
    ///
    /// `#[serde(default)]` so that any code path that deserializes a
    /// `RestEnvelope` from a pre-Stage-1.x JSON form (e.g. snapshot
    /// fixtures in tests) reads as the empty string rather than failing.
    /// Production stores are in-memory only, so a relay restart begins
    /// with this column populated by every new mirror call.
    #[serde(default)]
    pub seq_mac: String,
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
    // Trek 2 Stage 1 Q5 lock — quantize `sequence_ts` to the nearest
    // 60-second boundary on the ONE shared storage path so both REST
    // `/relay/send` and WS send (which both flow through this helper)
    // produce identical reduced-precision timestamps in the stored
    // RestEnvelope. Server-side, unconditional — the relay does not
    // trust the client to pre-quantize (Q7 of security-reviewer).
    let sequence_ts = quantize_sequence_ts_to_60s(sequence_ts);

    // Trek 2 Stage 1.x Lock-1 — compute the `seq_mac` integrity tag at
    // STORE time over the canonical
    // `(identity_hex, seq, envelope_id, sequence_ts)` tuple and persist
    // it in the `seq_mac` column on `RestEnvelope`. The poll-response
    // path (`drain_eligible`) reads this column verbatim — it does NOT
    // recompute the MAC at response time.
    //
    // Why store-time and not response-time: response-time computation
    // would HMAC over whatever the DB returns, including already-
    // corrupted values; a client receiving such a MAC would verify
    // successfully and consume the corrupted envelope. Store-time
    // computation creates a persistent integrity anchor — any
    // subsequent mutation to `envelope_id`, `seq`, or `sequence_ts`
    // without also recomputing this column produces a mismatch when
    // the client verifies on receive.
    //
    // Per-identity verify key derivation goes through the relay-side
    // root key; the root key never leaves this process. The same
    // derived key is published to the client in
    // `SessionResponse.seq_mac_verify_key` (see `rest_session`) so the
    // client can verify on receive.
    let verify_key = state.config.seq_mac_key.derive_verify_key(to);
    let seq_mac_bytes = verify_key
        .compute_seq_mac(to, seq, envelope_id, sequence_ts)
        .expect(
            "compute_seq_mac returns Err only on envelope_id length \
             overflow, which is rejected upstream at the request boundary \
             in rest_send and in the WS Send handler",
        );
    let seq_mac = crate::seq_mac::seq_mac_to_hex(&seq_mac_bytes);

    let rest_env = RestEnvelope {
        id: envelope_id.to_string(),
        from: String::new(),
        sealed_sender: sealed_sender.to_string(),
        payload: payload.to_string(),
        sequence_ts,
        seq,
        expires_at,
        seq_mac,
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
    /// PR-M2f — server-announced media capabilities. Clients consult this
    /// object instead of guessing endpoint shapes. Extensible: future flags
    /// (e.g. `range_download`, `max_range_bytes`, `recommended_chunk_bytes`)
    /// land here without breaking older clients that ignore unknown fields.
    pub media_capabilities: MediaCapabilities,
    /// Trek 2 Stage 1 — server-side long-poll hold-time in seconds.
    /// `0` = short-poll (existing behaviour, kill switch). `>0` = upgraded
    /// client uses this as `/relay/poll` request timeout target. Field is
    /// **always present** (no `skip_serializing_if`) so old clients that
    /// check for field presence see explicit `0` rather than absence.
    pub poll_hold_secs: u32,
    /// Trek 2 Stage 1.x — per-identity verify key for the `seq_mac`
    /// integrity tag on `PollEnvelope`. 64-char lowercase hex (32-byte
    /// HMAC-SHA-256 output). Derived from the relay-side root key
    /// (which never leaves the relay process) and the bound identity.
    ///
    /// The same derived key is used by the server to compute each
    /// envelope's `seq_mac` at store time AND by the client to verify
    /// on receive. The client can verify MACs for envelopes addressed
    /// to itself, and to itself only — without the root key, the client
    /// cannot derive any other identity's key.
    ///
    /// Field is always present so the wire shape is stable; old
    /// clients lacking awareness of the field simply ignore it (serde
    /// `ignoreUnknownKeys` on the Android JSON codec).
    pub seq_mac_verify_key: String,
}

#[derive(Serialize)]
pub struct MediaCapabilities {
    /// True when the relay serves the binary `/media/v3/{mediaId}/{idx}`
    /// endpoint pair. Clients MUST still tolerate a 404/405 from the v3
    /// path and fall back to legacy `/media/upload-chunk` + `/media/chunk/...`
    /// for safety, because relay redeploys may briefly disagree with the
    /// cached session announcement.
    pub binary_v3: bool,
    /// Hard cap on `POST /media/v3/.../...` (and legacy `/media/upload-chunk`)
    /// body bytes. Echoes `state.config.max_media_upload_body_bytes`. With v3,
    /// raw ciphertext === body, so this is the operative chunk ceiling.
    pub max_upload_body_bytes: usize,
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
    /// Trek 2 Stage 1.x — HMAC-SHA-256 integrity tag over
    /// `(identity_hex, seq, envelope_id, sequence_ts)`, computed at
    /// envelope STORE time (inside `mirror_envelope_to_rest_store`),
    /// persisted as a column on `RestEnvelope`, and read verbatim
    /// here — never recomputed at response time. 64-char lowercase hex.
    ///
    /// Threat-model wording, verbatim from the locked scope doc — do
    /// NOT soften:
    ///
    /// This protects against poll-layer / DB tamper / bugs (envelopes
    /// mishandled by relay code, by-token cache corruption, accidental
    /// seq replay from misuse of the dedup buckets), **not** a fully
    /// malicious relay operator unless signing key is outside the
    /// relay process. Do not pretend otherwise.
    ///
    /// Always present and non-empty on this REST-poll wire shape. The
    /// WS Deliver wire shape (a distinct struct, see `routes.rs`)
    /// does NOT carry a `seq_mac` field — verification semantics apply
    /// only to the batched poll path.
    pub seq_mac: String,
}

#[derive(Serialize)]
pub struct PollResponse {
    pub envelopes: Vec<PollEnvelope>,
    pub more: bool,
    /// Trek 2 Stage 1 — exact-length padding to the canonical body size
    /// (`POLL_RESPONSE_CANONICAL_BYTES`) for opted-in (Stage 2+) clients.
    /// Empty and envelope-bearing responses BOTH pad to identical total
    /// body bytes so a passive observer cannot distinguish "message
    /// arrived" from "no message" by response size on the opt-in tier.
    ///
    /// **Skipped on serialise when empty.** That is intentional — old
    /// clients without the `X-Phantom-Long-Poll: 1` opt-in header get
    /// a legacy small-body response (no `pad` field at all), saving
    /// ~4.5 KB per poll on metered cellular plans (Vladislav PR #297
    /// round-3 review P1: padding old-client responses to 4608 bytes
    /// would have been a silent bandwidth regression on the old client
    /// even after the hold-timeout fix). The full padding guarantee
    /// activates only when the client opts in and signals it accepts
    /// the larger body shape.
    ///
    /// Stage 1 = **two-tier** response shape:
    ///   * No header   → small body, no `pad` field (legacy).
    ///   * Header `=1` → exactly `POLL_RESPONSE_CANONICAL_BYTES` bytes,
    ///                   `pad` carries random base64url-safe filler.
    ///
    /// Security invariant 1 (byte-indistinguishable empty vs envelope)
    /// holds **within** the opt-in tier — old clients were already
    /// distinguishable by body size before Stage 1, so this is not a
    /// new regression; new opt-in clients gain the privacy upgrade.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub pad: String,
}

/// Number of bytes the `,"pad":""` field framing adds to a serialised
/// `PollResponse` when `pad` is non-empty (i.e. opt-in path). Used by
/// `pad_poll_response` to size the random padding string exactly so
/// the total response body hits `POLL_RESPONSE_CANONICAL_BYTES`.
///
/// Breakdown of the 9 framing bytes (`,"pad":""`):
///   `,` `"` `p` `a` `d` `"` `:` `"` `"` = 9 chars
const POLL_RESPONSE_PAD_FIELD_FRAMING: usize = 9;

#[derive(Deserialize)]
pub struct AckDeliverRequest {
    pub id: String,
}

// ── Trek 2 Stage 1 helpers ────────────────────────────────────────────────────

/// Base64url-safe alphabet (`[A-Za-z0-9_-]`, 64 chars) used to fill the
/// `PollResponse.pad` field. Each character is exactly 1 JSON byte (no
/// escaping needed), so a string of length `N` adds exactly `N` body
/// bytes — this is the property that lets us hit the canonical 4608
/// byte target exactly regardless of whether the response carries an
/// envelope or not.
///
/// Why this alphabet vs `hex::encode`: hex always produces an EVEN
/// number of characters (each byte → 2 chars), so it cannot hit odd
/// target lengths. Per Vladislav's Q4 padding correction we need exact
/// byte length; the 1-char-per-byte alphabet below makes that trivial.
const PAD_ALPHABET: &[u8] =
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// Build a random padding string of EXACTLY `len` characters using
/// `PAD_ALPHABET`. Each character drawn uniformly via `rand::thread_rng()`.
/// Entropy at len ≈ 500 chars: 500 × log2(64) = 3000 bits → indistinguishable
/// from base64-encoded ciphertext at this scale (security-reviewer Finding 4.B).
fn random_padding_string(len: usize) -> String {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    (0..len)
        .map(|_| {
            let idx = rng.gen_range(0..PAD_ALPHABET.len());
            PAD_ALPHABET[idx] as char
        })
        .collect()
}

/// Serialize `resp` to JSON bytes padded to EXACTLY
/// `POLL_RESPONSE_CANONICAL_BYTES`. Used on the opt-in
/// (`X-Phantom-Long-Poll: 1`) path only — the no-header legacy path
/// uses plain `serde_json::to_vec` so old clients get the original
/// small-body shape.
///
/// If the response without padding already meets or exceeds the
/// canonical size minus the pad-field framing (impossible for the
/// current `PollEnvelope` shape under `REST_MAX_BODY_BYTES=4096`, but
/// guard anyway), the response is returned unpadded with a
/// `tracing::warn!` emitted — Guardrail C / Q4 explicit non-truncation
/// rule.
///
/// Algorithm (accounting for `#[serde(skip_serializing_if = "String::is_empty")]`
/// on the `pad` field — when empty the field is OMITTED on probe, so
/// the math must add back the framing overhead before computing the
/// pad-string length):
///   1. Serialize `resp` with `pad = ""` → field is skipped → small probe.
///   2. The final padded body adds `,"pad":"<X>"` = 9 framing bytes plus
///      the pad-string length. Solve:
///         `target = probe_len + 9 + pad_len`
///         `pad_len = target - probe_len - 9`
///   3. Generate exactly `pad_len` chars of random `PAD_ALPHABET`.
///   4. Re-serialize and debug-assert exact-byte invariant.
pub fn pad_poll_response(mut resp: PollResponse) -> Vec<u8> {
    resp.pad = String::new();
    let probe = serde_json::to_vec(&resp).expect("PollResponse serialises");
    let target = POLL_RESPONSE_CANONICAL_BYTES;
    // probe + framing(9) + pad_len = target.
    // If even `probe + framing` already meets or exceeds target there
    // is no room for any padding string — emit warn and return the
    // unpadded probe (Guardrail C: never truncate ciphertext).
    if probe.len() + POLL_RESPONSE_PAD_FIELD_FRAMING >= target {
        tracing::warn!(
            event       = "poll_response_exceeds_canonical_size",
            unpadded_len = probe.len(),
            canonical    = target,
            "PollResponse cannot fit canonical padding budget — returning unpadded"
        );
        return probe;
    }
    let pad_len = target - probe.len() - POLL_RESPONSE_PAD_FIELD_FRAMING;
    resp.pad = random_padding_string(pad_len);
    let padded = serde_json::to_vec(&resp).expect("PollResponse serialises");
    debug_assert_eq!(
        padded.len(),
        target,
        "pad_poll_response invariant: padded body must equal canonical size \
         (probe={}, framing={}, pad_len={})",
        probe.len(),
        POLL_RESPONSE_PAD_FIELD_FRAMING,
        pad_len,
    );
    padded
}

/// Trek 2 Stage 1 — long-poll hold loop for `/relay/poll`. Free
/// function (not inlined into the axum handler) so the wait can be
/// unit-tested with `tokio::time::pause` and deterministic stepping.
///
/// Contract:
///   * If `rest_store` for `recipient` has eligible envelopes
///     (`seq > since_seq` and not expired), returns immediately.
///   * Else if `hold_secs == 0`, returns immediately with empty batch
///     (kill-switch fast path — no Notify entry created, no map mutation).
///   * Else looks up (or inserts, bounded by `POLL_HOLD_NOTIFIERS_LRU_CAP`)
///     the recipient's `Arc<Notify>` and races (a) `notify_one()` arrival,
///     (b) `hold_secs`-second timeout. On wake (a) sleeps
///     `POLL_HOLD_COALESCE_MS` so a burst of sends batches, then re-reads
///     the queue. On timeout (b) returns empty.
///   * If the notifier map is at capacity AND the recipient has no entry,
///     degrades to immediate-return short-poll (Guardrail A — envelope
///     still in `rest_store` for the next poll cycle).
///
/// Returns `(envelopes, more)` — same shape the caller previously built
/// inline. The caller is responsible for wrapping these in a
/// `PollResponse` and applying `pad_poll_response`.
pub async fn poll_hold_loop(
    state: &Arc<AppState>,
    recipient: &str,
    since_seq: u64,
    hold_secs: u32,
) -> (Vec<PollEnvelope>, bool) {
    // Phase 1 — initial queue check. If something is already pending,
    // return immediately without registering a waiter.
    if let Some(batch) = drain_eligible(state, recipient, since_seq).await {
        return batch;
    }
    // Phase 2 — kill-switch fast path. Either operator set hold_secs=0
    // globally, or the client did not send the opt-in header.
    if hold_secs == 0 {
        return (Vec::new(), false);
    }
    // Phase 3 — register on the per-recipient Notify and wait.
    let notifier = match state.notifier_for(recipient, POLL_HOLD_NOTIFIERS_LRU_CAP).await {
        Some(n) => n,
        None => {
            // Map at capacity. Degrade to short-poll behaviour for this
            // request; an upgraded client will simply retry sooner.
            return (Vec::new(), false);
        }
    };
    // Phase 3.5 — race-window re-check. Between phase 1's queue read
    // and phase 3's notifier insert, a concurrent `/relay/send` could
    // have:
    //   (a) written the envelope into rest_store, AND
    //   (b) called `notify_recipient` which found no entry in the map
    //       (because we hadn't inserted yet) and silently dropped the
    //       wake.
    // The envelope is now in the queue but no notify will fire to wake
    // us. Without this re-check we'd sit on `notified().await` until
    // `hold_secs` timeout, adding up to 30 s of unnecessary latency to
    // a delivery that should have been sub-50 ms (P2 fix per Vladislav
    // PR #297 review).
    if let Some(batch) = drain_eligible(state, recipient, since_seq).await {
        return batch;
    }
    let timeout = tokio::time::Duration::from_secs(hold_secs as u64);
    let coalesce = tokio::time::Duration::from_millis(POLL_HOLD_COALESCE_MS);
    let wake = tokio::time::timeout(timeout, notifier.notified()).await;
    if wake.is_err() {
        // Timeout. P2 fix per Vladislav PR #297 review — re-check the
        // queue before returning empty. A send that arrived in the few
        // ms between phase 3.5 and timeout MAY have notify_one'd the
        // notifier just as it expired, in which case the message is in
        // rest_store but `notified()` already returned `Err(Elapsed)`.
        // drain_eligible is cheap (one rwlock + one filter); the
        // guaranteed-delivery invariant (Guardrail A) is more
        // important than skipping the check.
        return drain_eligible(state, recipient, since_seq)
            .await
            .unwrap_or((Vec::new(), false));
    }
    // Notify fired. Wait the coalescing window so multiple back-to-back
    // sends batch into one response (POLL_MAX_ENVELOPES=1 still applies,
    // but `more=true` then tells the client to immediately re-poll).
    tokio::time::sleep(coalesce).await;
    // Phase 4 — post-wake queue re-check.
    drain_eligible(state, recipient, since_seq)
        .await
        .unwrap_or((Vec::new(), false))
}

/// Inspect the REST store for `recipient` and return the
/// `(envelopes, more)` batch if there is at least one envelope with
/// `seq > since_seq`. Returns `None` if there is nothing eligible
/// (caller uses this to decide whether to wait on Notify).
///
/// Also purges expired envelopes from the queue as a side-effect — the
/// existing handler did this each call, so the long-poll path preserves
/// the same TTL-enforcement cadence.
async fn drain_eligible(
    state: &Arc<AppState>,
    recipient: &str,
    since_seq: u64,
) -> Option<(Vec<PollEnvelope>, bool)> {
    let mut rest_store = state.rest_store.write().await;
    let queue = rest_store.entry(recipient.to_string()).or_default();
    queue.retain(|e| !e.is_expired());
    let eligible: Vec<&RestEnvelope> = queue.iter().filter(|e| e.seq > since_seq).collect();
    if eligible.is_empty() {
        return None;
    }
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
            // Trek 2 Stage 1.x Lock-1 — read the stored MAC verbatim;
            // store-time computation is the persistent integrity anchor.
            // Recomputing here would silently re-sign over whatever the
            // DB currently returns, collapsing the DB-tamper scope to
            // "tamper between drain and wire" — a far narrower threat
            // than the wording on `PollEnvelope::seq_mac` promises.
            seq_mac: e.seq_mac.clone(),
        })
        .collect();
    Some((batch, more))
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
            // Trek 2 Stage 1.x Lock-1 — derive the per-identity verify
            // key from the relay-side root key and publish its hex form
            // to the client. The same key is used by the server to
            // compute each envelope's `seq_mac` at store time.
            let seq_mac_verify_key = state
                .config
                .seq_mac_key
                .derive_verify_key(&req.identity)
                .to_hex();
            return (
                StatusCode::OK,
                Json(SessionResponse {
                    token,
                    expires_at,
                    rest_fallback: true,
                    max_send_body_bytes: REST_MAX_BODY_BYTES,
                    poll_max_envelopes: POLL_MAX_ENVELOPES,
                    media_capabilities: MediaCapabilities {
                        binary_v3: true,
                        max_upload_body_bytes: state.config.max_media_upload_body_bytes,
                    },
                    poll_hold_secs: state.config.poll_hold_secs,
                    seq_mac_verify_key,
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

    // Trek 2 Stage 1.x Lock-1 — derive + publish the per-identity verify
    // key on the fresh-issuance path too. Mirrors the cache-hit branch
    // above so both code paths return the same wire shape.
    let seq_mac_verify_key = state
        .config
        .seq_mac_key
        .derive_verify_key(&req.identity)
        .to_hex();

    (
        StatusCode::OK,
        Json(SessionResponse {
            token,
            expires_at,
            rest_fallback: true,
            max_send_body_bytes: REST_MAX_BODY_BYTES,
            poll_max_envelopes: POLL_MAX_ENVELOPES,
            media_capabilities: MediaCapabilities {
                binary_v3: true,
                max_upload_body_bytes: state.config.max_media_upload_body_bytes,
            },
            poll_hold_secs: state.config.poll_hold_secs,
            seq_mac_verify_key,
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

    // Trek 2 Stage 1.x Lock-1 — bound the UTF-8 byte length of
    // `envelope_id` to the `u16-BE` length-prefix capacity used in the
    // canonical `seq_mac` input. Production sizes are ~32 bytes; the
    // 65535 ceiling is a defensive cap so an oversized id cannot reach
    // `compute_seq_mac` and force a panic on the store-time MAC path.
    if req.envelope_id.len() > crate::seq_mac::ENVELOPE_ID_MAX_BYTES {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({
                "error": "envelope_id UTF-8 byte length exceeds 65535"
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

    // Trek 2 Stage 1 — wake any in-flight `/relay/poll` long-poll waiter
    // for this recipient. Best-effort: if no waiter exists (offline /
    // short-poll client), this is a no-op and the envelope is picked up
    // on the next poll cycle. Read-lock-only to keep the send path hot.
    let _ = state.notify_recipient(&req.to).await;

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

    // Trek 2 Stage 1 — request-level opt-in for long-poll hold.
    // The server only applies `RelayConfig.poll_hold_secs` when the
    // client sets `X-Phantom-Long-Poll: 1`. Old clients (no header)
    // ALWAYS get short-poll, regardless of server env — preserves the
    // "Stage 1 is zero-client-risk" contract even if the operator
    // flips `RELAY_POLL_HOLD_SECS > 0` globally. Strict equality on
    // the header value so a typo cannot accidentally opt in.
    let long_poll_opt_in = headers
        .get(LONG_POLL_OPT_IN_HEADER)
        .and_then(|v| v.to_str().ok())
        .map(|v| v == "1")
        .unwrap_or(false);
    // Trek 2 Stage 1.x Lock-2 — independent gate for the padded
    // response shape. Same strict `"1"` equality contract as the
    // long-poll header.
    let padded_poll_opt_in = headers
        .get(PADDED_POLL_OPT_IN_HEADER)
        .and_then(|v| v.to_str().ok())
        .map(|v| v == "1")
        .unwrap_or(false);
    // Padded shape activates when EITHER header opts in. LP-only
    // preserves the Stage 1 legacy behaviour (hold + padded). PP-only
    // gives the Stage 2B circuit-breaker fallback its short-poll +
    // padded posture so the on-wire footprint stays canonical while
    // the breaker is open.
    let padded_opt_in = long_poll_opt_in || padded_poll_opt_in;
    let effective_hold_secs = if long_poll_opt_in {
        state.config.poll_hold_secs
    } else {
        0
    };

    // Trek 2 Stage 1 — long-poll hold. `poll_hold_loop` returns
    // immediately if `effective_hold_secs == 0` (kill switch OR client
    // did not opt in) or if the queue is non-empty; otherwise it awaits
    // a per-identity Notify with a ~50 ms coalescing window so a burst
    // of sends batches into one response.
    let (envelopes, more) =
        poll_hold_loop(&state, &recipient_identity, since_seq, effective_hold_secs).await;

    let envelope_id_log = envelopes.first().map(|e| e.id.as_str()).unwrap_or("");
    tracing::info!(
        event              = "rest_poll_returned",
        identity           = %&recipient_identity[..8.min(recipient_identity.len())],
        envelope_id        = %envelope_id_log,
        more               = more,
        hold_secs          = effective_hold_secs,
        long_poll_opt_in   = long_poll_opt_in,
        padded_poll_opt_in = padded_poll_opt_in,
    );

    // Trek 2 Stage 1.x Lock-2 — response shape is gated by
    // `padded_opt_in = long_poll_opt_in || padded_poll_opt_in`, NOT by
    // the long-poll header alone. This decouples the hold path from
    // the padded body shape:
    //   * `padded_opt_in == true`: pad to canonical 4608-byte body so
    //     empty and envelope-bearing responses are byte-
    //     indistinguishable on the wire.
    //   * `padded_opt_in == false`: legacy small-body shape (no `pad`
    //     field, skipped by serde). Old no-header clients keep their
    //     original bandwidth profile and never pay the ~4.5 KB-per-
    //     poll cost.
    let body: Vec<u8> = if padded_opt_in {
        pad_poll_response(PollResponse {
            envelopes,
            more,
            pad: String::new(),
        })
    } else {
        serde_json::to_vec(&PollResponse {
            envelopes,
            more,
            pad: String::new(),
        })
        .expect("PollResponse serialises")
    };
    (
        StatusCode::OK,
        [(axum::http::header::CONTENT_TYPE, "application/json")],
        body,
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

    // Trek 2 Stage 1 Q2 — rate-limit ack-deliver on a SEPARATE map
    // (`state.ack_rate_limiter`) keyed on recipient identity. Must NOT
    // share the `/relay/send` bucket because the same identity can be
    // both sender and recipient (self-test, system identities) — sharing
    // would block ack-deliver after 60 sends or block sends after 120
    // acks. Limit: 120 / 60 s window per
    // `ACK_DELIVER_RATE_LIMIT_PER_WINDOW`. Reuses the existing sliding-
    // window pattern from `rest_send` above for shape consistency.
    let ack_rate_ok = {
        let mut limiter = state.ack_rate_limiter.write().await;
        let entry = limiter
            .entry(recipient_identity.clone())
            .or_insert(crate::state::RateEntry {
                count: 0,
                window_start: std::time::Instant::now(),
            });
        if entry.window_start.elapsed().as_secs() >= state.config.rate_limit_window_secs {
            entry.count = 1;
            entry.window_start = std::time::Instant::now();
            true
        } else if entry.count < ACK_DELIVER_RATE_LIMIT_PER_WINDOW {
            entry.count += 1;
            true
        } else {
            false
        }
    };
    if !ack_rate_ok {
        tracing::warn!(
            event       = "rest_ack_deliver_rate_limited",
            identity    = %&recipient_identity[..8.min(recipient_identity.len())],
            envelope_id = %req.id,
        );
        return (
            StatusCode::TOO_MANY_REQUESTS,
            Json(serde_json::json!({ "error": "rate limit exceeded" })),
        )
            .into_response();
    }

    tracing::info!(
        event       = "rest_ack_deliver_received",
        identity    = %&recipient_identity[..8.min(recipient_identity.len())],
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

// ── Trek 2 Stage 1 unit tests ────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── sequence_ts quantization (Q5) ────────────────────────────────────────

    #[test]
    fn quantize_60s_floors_to_boundary() {
        // Table-driven: (input_ms, expected_quantized_ms)
        let cases = [
            (0u64, 0u64),
            (1, 0),
            (59_999, 0),
            (60_000, 60_000),
            (60_001, 60_000),
            (125_000, 120_000),
            (1_700_000_000_000, 1_700_000_000_000 - (1_700_000_000_000 % 60_000)),
        ];
        for (input, expected) in cases {
            assert_eq!(
                quantize_sequence_ts_to_60s(input),
                expected,
                "input={} expected={}",
                input,
                expected
            );
        }
    }

    #[test]
    fn quantize_60s_does_not_overflow_at_u64_max() {
        // u64::MAX modulo arithmetic must not panic.
        let q = quantize_sequence_ts_to_60s(u64::MAX);
        assert_eq!(q, u64::MAX - (u64::MAX % 60_000));
        assert_eq!(q % 60_000, 0);
    }

    // ── padding helper (Q4) ───────────────────────────────────────────────────

    fn fixture_envelope(payload_bytes: usize) -> PollEnvelope {
        PollEnvelope {
            id: "00000000-0000-0000-0000-000000000000".to_string(), // 36 chars
            from: "a".repeat(64),                                    // X25519 hex
            sealed_sender: "".to_string(),
            payload: "x".repeat(payload_bytes),
            sequence_ts: 1_700_000_000_000,
            seq: 42,
            // Trek 2 Stage 1.x — the padding canonical-size tests use a
            // 64-char placeholder MAC so the wire-size math reflects the
            // Stage 1.x `seq_mac` field shape. Real MACs are byte-exact
            // hex from `SeqMacVerifyKey::compute_seq_mac`; for padding
            // sizing the bytes themselves don't matter, only the length.
            seq_mac: "0".repeat(64),
        }
    }

    #[test]
    fn pad_poll_response_empty_hits_canonical_size() {
        let resp = PollResponse {
            envelopes: Vec::new(),
            more: false,
            pad: String::new(),
        };
        let body = pad_poll_response(resp);
        assert_eq!(
            body.len(),
            POLL_RESPONSE_CANONICAL_BYTES,
            "empty poll body must equal canonical 4608 bytes exactly"
        );
    }

    #[test]
    fn pad_poll_response_small_envelope_hits_canonical_size() {
        let resp = PollResponse {
            envelopes: vec![fixture_envelope(256)],
            more: false,
            pad: String::new(),
        };
        let body = pad_poll_response(resp);
        assert_eq!(body.len(), POLL_RESPONSE_CANONICAL_BYTES);
    }

    #[test]
    fn pad_poll_response_max_realistic_envelope_hits_canonical_size() {
        // ~3500-byte payload approximates the max realistic post-quantize
        // PollEnvelope under REST_MAX_BODY_BYTES=4096 send-side cap.
        let resp = PollResponse {
            envelopes: vec![fixture_envelope(3_500)],
            more: false,
            pad: String::new(),
        };
        let body = pad_poll_response(resp);
        assert_eq!(body.len(), POLL_RESPONSE_CANONICAL_BYTES);
    }

    #[test]
    fn pad_poll_response_empty_and_with_envelope_have_identical_size() {
        // The load-bearing security invariant test (Q4 / Decision 3):
        // empty-poll and message-bearing-poll bodies must be byte-
        // indistinguishable on the wire after JSON framing.
        let empty = pad_poll_response(PollResponse {
            envelopes: Vec::new(),
            more: false,
            pad: String::new(),
        });
        let with_env = pad_poll_response(PollResponse {
            envelopes: vec![fixture_envelope(512)],
            more: false,
            pad: String::new(),
        });
        assert_eq!(empty.len(), with_env.len());
        assert_eq!(empty.len(), POLL_RESPONSE_CANONICAL_BYTES);
    }

    #[test]
    fn pad_poll_response_oversize_returns_unpadded_without_panic() {
        // If the audit-stated max (≈4154 bytes) is ever exceeded, the
        // helper must not panic and must not truncate ciphertext —
        // it returns the unpadded body and emits a warn log.
        let resp = PollResponse {
            envelopes: vec![fixture_envelope(5_000)],
            more: false,
            pad: String::new(),
        };
        let body = pad_poll_response(resp);
        // Body is unpadded so it is allowed to exceed canonical size.
        assert!(body.len() > POLL_RESPONSE_CANONICAL_BYTES);
    }

    #[test]
    fn random_padding_string_hits_exact_length_and_uses_valid_alphabet() {
        for target_len in [0usize, 1, 7, 100, 1_234, 4_500] {
            let s = random_padding_string(target_len);
            assert_eq!(s.len(), target_len, "exact-length contract");
            assert!(
                s.bytes().all(|b| PAD_ALPHABET.contains(&b)),
                "all padding chars must be from PAD_ALPHABET"
            );
        }
    }

    #[test]
    fn random_padding_string_produces_distinct_outputs() {
        // Two calls at the same length should differ almost certainly.
        let a = random_padding_string(1_000);
        let b = random_padding_string(1_000);
        assert_ne!(a, b);
    }

    // ── PollResponse `pad` field serialization ───────────────────────────────

    #[test]
    fn poll_response_pad_field_present_when_populated() {
        // Opt-in path: server populates `pad` with random filler;
        // serialised body MUST carry the field verbatim.
        let resp = PollResponse {
            envelopes: Vec::new(),
            more: false,
            pad: "abc".to_string(),
        };
        let json = serde_json::to_string(&resp).unwrap();
        assert!(json.contains(r#""pad":"abc""#), "json was: {}", json);
    }

    #[test]
    fn poll_response_pad_field_omitted_when_empty() {
        // Legacy / no-header path: server leaves `pad = String::new()`;
        // serde's `skip_serializing_if = "String::is_empty"` MUST drop
        // the field entirely so old Android clients see the original
        // small-body shape they had before Stage 1 (Vladislav PR #297
        // round-3 review P1 — no silent bandwidth regression).
        let resp = PollResponse {
            envelopes: Vec::new(),
            more: false,
            pad: String::new(),
        };
        let json = serde_json::to_string(&resp).unwrap();
        assert!(!json.contains(r#""pad""#), "pad field must be absent on legacy path; json was: {}", json);
    }

    // ── ack-deliver rate-limit constant (Q2) ─────────────────────────────────

    #[test]
    fn ack_deliver_rate_limit_constant_is_120() {
        // Lock from Q2: separate map, fixed 120/min, no config knob in Stage 1.
        assert_eq!(ACK_DELIVER_RATE_LIMIT_PER_WINDOW, 120);
    }

    // ── canonical padding constant (Q4) ──────────────────────────────────────

    #[test]
    fn poll_canonical_size_is_4608() {
        // Lock from Q4 audit: 4608 bytes covers max realistic envelope
        // (~4154 bytes) + ~454 bytes empty-padding margin under Tele2
        // ~5KB cutoff.
        assert_eq!(POLL_RESPONSE_CANONICAL_BYTES, 4_608);
    }

    // ── notifier LRU cap (Q6) ────────────────────────────────────────────────

    #[test]
    fn notifier_cap_is_50k() {
        assert_eq!(POLL_HOLD_NOTIFIERS_LRU_CAP, 50_000);
    }
}
