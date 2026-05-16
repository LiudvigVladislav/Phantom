// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use crate::auth::{ChallengeStore, SigningKeyBindings};
use crate::config::RelayConfig;
use crate::envelope::Envelope;
use crate::prekeys::PreKeyStore;
use crate::rest_fallback::{IdempotencyCache, RestEnvelope, RestTokenStore, SeqCounter, SessionChallengeCache};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::AtomicU64;
use tokio::sync::{mpsc, RwLock};

/// Per-sender sliding-window rate-limit entry.
#[derive(Debug)]
pub struct RateEntry {
    pub count: u32,
    pub window_start: std::time::Instant,
}

/// Abuse report submitted by a user. No message content is stored.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AbuseReport {
    pub reporter_key: String,
    pub reported_key: String,
    pub category: String,
    pub timestamp_ms: u64,
}

/// In-memory store for Alpha-0.
pub struct AppState {
    pub config: RelayConfig,
    /// recipient_public_key_hex → queue of offline envelopes
    pub store: RwLock<HashMap<String, Vec<Envelope>>>,
    /// identity_hex → (connection_id, sender channel) for live WebSocket clients.
    /// connection_id is a monotonically increasing u64 minted at connect time.
    /// Cleanup only removes the entry if the stored connection_id matches, so a
    /// reconnect that inserts a new entry before the old cleanup runs is safe.
    pub clients: RwLock<HashMap<String, (u64, mpsc::UnboundedSender<String>)>>,
    /// Monotonic counter — each new WebSocket connection gets a unique ID.
    pub conn_counter: AtomicU64,
    /// Rate limiter: message count per sender identity in current window.
    pub rate_limiter: RwLock<HashMap<String, RateEntry>>,
    /// Abuse reports received via /report endpoint (also persisted to reports.jsonl).
    pub reports: RwLock<Vec<AbuseReport>>,
    /// Blocked public key prefixes — relay silently drops their messages.
    pub blocklist: RwLock<std::collections::HashSet<String>>,
    /// X3DH 4-DH prekey bundle storage (ADR-009). Hosts published SPKs +
    /// OPK pools and serves consume-bundle / status queries. Lives outside
    /// `store` because the persistence shape and access patterns differ
    /// (per-identity record, atomic OPK pop, separate jsonl file).
    pub prekeys: PreKeyStore,
    /// UnifiedPush registration tokens (ADR-016). Maps recipient identity
    /// (hex public key) -> ntfy topic URL the client published via
    /// POST /push/register. Set when the client (re-)registers with its
    /// UnifiedPush distributor; consumed when the relay needs to wake the
    /// client because an envelope was queued offline.
    ///
    /// Storage shape mirrors `reports` and `blocklist`:
    ///   in-memory HashMap + JSONL append on every change.
    /// On startup the JSONL is replayed line-by-line; the most recent
    /// line per identity wins (matches the publish endpoint's
    /// "replace topic for this identity" semantics).
    ///
    /// Privacy boundary: the relay stores the topic URL only. It never
    /// stores the per-install secret that authenticates against the
    /// distributor — that lives only in the client. The topic URL is
    /// the equivalent of an FCM token in metadata sensitivity but does
    /// not identify the user to any third party because the distributor
    /// is self-hosted (`ntfy.phntm.pro`).
    pub push_tokens: RwLock<HashMap<String, String>>,
    /// HTTP client used to POST UnifiedPush wake-ups to the ntfy
    /// distributor. Created once at startup with conservative timeouts;
    /// reused for every push. Fire-and-forget at the call site —
    /// envelope delivery never blocks on push completion.
    pub http: reqwest::Client,
    /// Per-identity outstanding signed-challenge nonces. Replaces the
    /// shared-secret `?token=` previously used by the WS upgrade
    /// (closes F11 + F26). Single-shot consume on first WS handshake;
    /// background sweep purges expired entries every minute.
    pub auth_challenges: ChallengeStore,
    /// Identity → Ed25519 signing pubkey binding used by the WS auth
    /// signature verify. Populated by both `publish_prekeys` (long-term
    /// binding) and the WS handshake itself (TOFU first connect).
    pub signing_keys: SigningKeyBindings,

    // ── REST fallback transport (PR-D0r) ──────────────────────────────────────

    /// Opaque bearer tokens issued by POST /auth/session.
    /// In-memory, Alpha-grade. Token TTL: 1 hour.
    pub rest_tokens: RestTokenStore,
    /// Replay-safety cache for /auth/session: same (identity, challenge)
    /// within 5 minutes returns the same token without re-verifying
    /// the signature (the challenge was already consumed one-shot).
    pub rest_session_cache: SessionChallengeCache,
    /// Per-identity LRU idempotency cache for /relay/send.
    /// Bounded at 10K entries per identity; 24-hour TTL per entry.
    pub rest_idempotency: IdempotencyCache,
    /// REST-specific per-recipient envelope queue.
    /// Carries the monotonic `seq` counter that /relay/poll uses for
    /// resume (?since_seq=). Shares envelope IDs with `store` so
    /// /relay/ack-deliver removes from both simultaneously.
    pub rest_store: RwLock<HashMap<String, Vec<RestEnvelope>>>,
    /// Monotonic per-recipient sequence counter for REST poll resume.
    pub rest_seq: SeqCounter,
}

impl AppState {
    pub fn new(config: RelayConfig) -> Self {
        // Load persisted reports from disk
        let persisted = load_reports_from_disk();
        // Load persisted blocklist from disk
        let blocked = load_blocklist_from_disk();
        // Load persisted push tokens from disk
        let tokens = load_push_tokens_from_disk();
        // HTTP client for outbound UnifiedPush wake-ups. 5s timeout is
        // generous — ntfy distributor is on the same Docker network in
        // production. On error we log and move on; envelope delivery
        // is unaffected.
        let http = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(5))
            .user_agent("phantom-relay/0.1")
            .build()
            .expect("reqwest::Client::build with default rustls should not fail");
        Self {
            config,
            store: RwLock::new(HashMap::new()),
            clients: RwLock::new(HashMap::new()),
            conn_counter: AtomicU64::new(0),
            rate_limiter: RwLock::new(HashMap::new()),
            reports: RwLock::new(persisted),
            blocklist: RwLock::new(blocked),
            prekeys: PreKeyStore::new(),
            push_tokens: RwLock::new(tokens),
            http,
            auth_challenges: ChallengeStore::new(),
            signing_keys: SigningKeyBindings::new(),
            // REST fallback (PR-D0r)
            rest_tokens: RestTokenStore::new(),
            rest_session_cache: SessionChallengeCache::new(),
            rest_idempotency: IdempotencyCache::new(),
            rest_store: RwLock::new(HashMap::new()),
            rest_seq: SeqCounter::new(),
        }
    }

    /// Seed the in-memory signing-key bindings from the disk-replayed
    /// `PreKeyStore` so a relay restart keeps every previously-published
    /// identity's signing-key binding stable for WS auth. Call once after
    /// `AppState::new()` and before serving traffic.
    pub async fn rebuild_signing_keys_from_prekeys(&self) {
        let pairs = self.prekeys.iter_identity_signing_pairs().await;
        self.signing_keys.load(pairs).await;
    }
}

const REPORTS_FILE: &str = "reports.jsonl";
const BLOCKLIST_FILE: &str = "blocklist.txt";
const PUSH_TOKENS_FILE: &str = "push_tokens.jsonl";

/// On-disk record for a single /push/register call. `identity` is the
/// recipient's hex public key; `topic_url` is the ntfy URL the client
/// will subscribe to via its UnifiedPush distributor.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PushTokenRecord {
    pub identity: String,
    pub topic_url: String,
}

pub fn load_reports_from_disk() -> Vec<AbuseReport> {
    let Ok(content) = std::fs::read_to_string(REPORTS_FILE) else { return vec![] };
    content.lines()
        .filter_map(|line| serde_json::from_str(line).ok())
        .collect()
}

pub fn load_blocklist_from_disk() -> std::collections::HashSet<String> {
    let Ok(content) = std::fs::read_to_string(BLOCKLIST_FILE) else {
        return std::collections::HashSet::new()
    };
    content.lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect()
}

pub fn append_report_to_disk(report: &AbuseReport) {
    if let Ok(line) = serde_json::to_string(report) {
        use std::io::Write;
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(REPORTS_FILE) {
            let _ = writeln!(f, "{}", line);
        }
    }
}

pub fn append_block_to_disk(key: &str) {
    use std::io::Write;
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(BLOCKLIST_FILE) {
        let _ = writeln!(f, "{}", key);
    }
}

pub fn load_push_tokens_from_disk() -> HashMap<String, String> {
    let Ok(content) = std::fs::read_to_string(PUSH_TOKENS_FILE) else {
        return HashMap::new();
    };
    // Replay JSONL line by line. Last write wins per identity — matches
    // the /push/register semantics (each registration replaces the
    // previous topic for that identity).
    let mut map = HashMap::new();
    for line in content.lines() {
        if let Ok(rec) = serde_json::from_str::<PushTokenRecord>(line) {
            map.insert(rec.identity, rec.topic_url);
        }
    }
    map
}

pub fn append_push_token_to_disk(rec: &PushTokenRecord) {
    use std::io::Write;
    if let Ok(line) = serde_json::to_string(rec) {
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(PUSH_TOKENS_FILE) {
            let _ = writeln!(f, "{}", line);
        }
    }
}
