// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use crate::auth::{ChallengeStore, SigningKeyBindings};
use crate::config::RelayConfig;
use crate::envelope::Envelope;
use crate::media::MediaStore;
use crate::prekeys::PreKeyStore;
use crate::rest_fallback::{IdempotencyCache, RestEnvelope, RestTokenStore, SeqCounter, SessionChallengeCache};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU8, AtomicU64};
use std::sync::Arc;
use tokio::sync::{mpsc, Notify, RwLock};

/// Trek 2 Stage 1.x Lock-4 — per-recipient hold slot.
///
/// Combines the existing per-recipient `Notify` (waker for the
/// `/relay/poll` long-poll hold) with a per-identity concurrent-hold
/// counter. The counter is incremented when a poll enters the hold
/// path (via `HoldGuard::try_acquire`) and decremented when the guard
/// drops — including the cases where the axum handler future is
/// cancelled or the client closes the TCP connection. The Rust
/// language guarantees `Drop` runs whenever the owning future is
/// actually dropped.
///
/// The map value type was previously `Arc<Notify>`; widening it to
/// `Arc<HoldSlot>` keeps the per-recipient lifetime aligned with the
/// existing `Arc::strong_count == 1` cleanup pattern in `main.rs` —
/// the counter rides the same drop path as the notifier for free, so
/// the counter and the notifier cannot diverge.
pub struct HoldSlot {
    pub notify: Notify,
    pub hold_count: AtomicU8,
}

impl HoldSlot {
    pub fn new() -> Self {
        Self {
            notify: Notify::new(),
            hold_count: AtomicU8::new(0),
        }
    }
}

impl Default for HoldSlot {
    fn default() -> Self {
        Self::new()
    }
}

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

    // ── Trek 2 Stage 1 long-poll (SHORT-CYCLE-LONGPOLL1) ──────────────────────

    /// Per-recipient wake-up channel for `/relay/poll` long-poll hold.
    /// `/relay/send` calls `notify_one()` on the recipient's entry after
    /// `mirror_envelope_to_rest_store` so an in-flight poll waiter wakes
    /// within ~50 ms (coalescing window) and returns the new envelope.
    ///
    /// The `Arc<Notify>` is cloned by both producers (send path) and
    /// consumers (poll path) so background cleanup (in `main.rs` 5-min
    /// task) can safely evict map entries only when `Arc::strong_count == 1`
    /// (no active waiter or send-path reference) — guards the
    /// drop-while-register race per Q6 lock.
    ///
    /// Bounded at `POLL_HOLD_NOTIFIERS_LRU_CAP` entries (see
    /// `rest_fallback.rs`) to defend against map-size amplification —
    /// when full the poll handler degrades to immediate return (no Notify
    /// registration) per Trek 2 mini-lock cross-cutting invariant 7.
    pub notifiers: RwLock<HashMap<String, Arc<HoldSlot>>>,

    /// Per-identity sliding-window rate limiter for `/relay/ack-deliver`.
    /// SEPARATE from `rate_limiter` (which counts `/relay/send` per sender)
    /// because the same identity can be both sender and recipient — sharing
    /// the same map would block ack-deliver after 60 sends in the window,
    /// or block sends after 120 acks. Each map has its own limit
    /// (60 / window for send, 120 / window for ack-deliver) and its own
    /// per-identity counter so the two flows are independent per Q2 lock.
    pub ack_rate_limiter: RwLock<HashMap<String, RateEntry>>,

    // ── Media upload store (PR-M1r) ───────────────────────────────────────────

    /// In-memory store for encrypted media chunks uploaded via
    /// POST /media/upload-chunk. Keyed by opaque `media_id` capability token.
    /// Relay never inspects ciphertext content — only stores and serves blobs.
    pub media_store: MediaStore,

    // ── State-file paths (RC-RELAY-STATE-DIR-REPAIR PR-1a §4.1) ───────────────

    /// Absolute paths (or workspace-relative under `from_env_for_test`) that
    /// each of the three `state.rs`-owned append-log files resolves against.
    /// Computed once at `AppState::new` from `cfg.state_dir`; route handlers
    /// read these to pass into `append_report_to_disk` / `append_block_to_disk`
    /// / `append_push_token_to_disk`.
    ///
    /// `prekeys.jsonl` is NOT surfaced here — it lives inside `PreKeyStore`
    /// so the store owns the full path just like it owns its in-memory map.
    /// See `docs/tracks/rc-relay-state-dir-repair.md` §3.2 / §4.1.
    pub state_paths: StatePaths,
}

/// Joined absolute paths for the three `state.rs`-owned append-log files.
/// Computed from `cfg.state_dir` at `AppState::new`. `prekeys.jsonl` lives
/// on `PreKeyStore` instead of here.
#[derive(Clone, Debug)]
pub struct StatePaths {
    pub reports: PathBuf,
    pub blocklist: PathBuf,
    pub push_tokens: PathBuf,
}

impl StatePaths {
    pub fn from_state_dir(state_dir: &Path) -> Self {
        Self {
            reports: state_dir.join(REPORTS_FILENAME),
            blocklist: state_dir.join(BLOCKLIST_FILENAME),
            push_tokens: state_dir.join(PUSH_TOKENS_FILENAME),
        }
    }
}

impl AppState {
    pub fn new(config: RelayConfig) -> Self {
        // RC-RELAY-STATE-DIR-REPAIR PR-1a §4.1: compute state-file paths
        // from the injected `state_dir` before spinning up sub-stores.
        let state_paths = StatePaths::from_state_dir(&config.state_dir);
        // Load persisted reports from disk
        let persisted = load_reports_from_disk(&state_paths.reports);
        // Load persisted blocklist from disk
        let blocked = load_blocklist_from_disk(&state_paths.blocklist);
        // Load persisted push tokens from disk
        let tokens = load_push_tokens_from_disk(&state_paths.push_tokens);
        // HTTP client for outbound UnifiedPush wake-ups. 5s timeout is
        // generous — ntfy distributor is on the same Docker network in
        // production. On error we log and move on; envelope delivery
        // is unaffected.
        let http = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(5))
            .user_agent("phantom-relay/0.1")
            .build()
            .expect("reqwest::Client::build with default rustls should not fail");
        let prekeys = PreKeyStore::new(&config.state_dir);
        Self {
            config,
            store: RwLock::new(HashMap::new()),
            clients: RwLock::new(HashMap::new()),
            conn_counter: AtomicU64::new(0),
            rate_limiter: RwLock::new(HashMap::new()),
            reports: RwLock::new(persisted),
            blocklist: RwLock::new(blocked),
            prekeys,
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
            // Trek 2 Stage 1 long-poll
            notifiers: RwLock::new(HashMap::new()),
            ack_rate_limiter: RwLock::new(HashMap::new()),
            // Media upload (PR-M1r)
            media_store: MediaStore::new(),
            state_paths,
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

    /// Return the per-recipient `Arc<Notify>` used by the `/relay/poll`
    /// long-poll hold loop, creating a fresh one if the recipient has no
    /// entry yet. Bounded by `cap`: when the map is full and the recipient
    /// has no existing entry, returns `None` so the caller can degrade to
    /// the immediate-return short-poll path (preserves Guardrail A
    /// "delivery never lost" because the message is still in `rest_store`
    /// and will be picked up on the next poll cycle).
    ///
    /// Trek 2 Stage 1 Q6 lock — the same `Arc` is handed to producers
    /// (`/relay/send` -> `notify_one()`) and consumers (poll waiter); the
    /// cleanup task only drops map entries with `Arc::strong_count == 1`
    /// to avoid the race where eviction strands a waiter without a
    /// notifier producers can reach.
    pub async fn notifier_for(
        &self,
        recipient: &str,
        cap: usize,
    ) -> Option<Arc<HoldSlot>> {
        // Read-lock fast path.
        {
            let map = self.notifiers.read().await;
            if let Some(arc) = map.get(recipient) {
                return Some(Arc::clone(arc));
            }
        }
        // Miss → write-lock to insert. Re-check under write lock to handle
        // racing inserts (two concurrent polls for the same identity).
        let mut map = self.notifiers.write().await;
        if let Some(arc) = map.get(recipient) {
            return Some(Arc::clone(arc));
        }
        if map.len() >= cap {
            // Map at capacity. Don't admit a new identity — the poll
            // handler degrades to immediate-return short-poll, which
            // is acceptable per Guardrail A (envelope still in queue
            // for the next poll cycle).
            return None;
        }
        let arc = Arc::new(HoldSlot::new());
        map.insert(recipient.to_string(), Arc::clone(&arc));
        Some(arc)
    }

    /// Best-effort `notify_one()` for a recipient — used by `/relay/send`
    /// to wake any in-flight poll hold. Returns `true` if a notifier
    /// existed for the recipient. If no notifier exists (the recipient
    /// has no in-flight or recent poll), this is a no-op — the envelope
    /// is still in `rest_store` and will be returned on the next poll.
    ///
    /// Performance: read-lock only, no map mutation. The send path is
    /// hot; a write-lock here would contend with every concurrent send.
    pub async fn notify_recipient(&self, recipient: &str) -> bool {
        let map = self.notifiers.read().await;
        if let Some(arc) = map.get(recipient) {
            arc.notify.notify_one();
            true
        } else {
            false
        }
    }
}

/// Basenames of the three `state.rs`-owned append-log files. Full paths
/// are computed at `AppState::new` via `StatePaths::from_state_dir` — see
/// the doc comment on `AppState::state_paths` (RC-RELAY-STATE-DIR-REPAIR
/// PR-1a §4.1). Kept public within the crate so the `state_persistence`
/// integration test can assert file names without duplicating literals.
pub(crate) const REPORTS_FILENAME: &str = "reports.jsonl";
pub(crate) const BLOCKLIST_FILENAME: &str = "blocklist.txt";
pub(crate) const PUSH_TOKENS_FILENAME: &str = "push_tokens.jsonl";

/// On-disk record for a single /push/register call. `identity` is the
/// recipient's hex public key; `topic_url` is the ntfy URL the client
/// will subscribe to via its UnifiedPush distributor.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PushTokenRecord {
    pub identity: String,
    pub topic_url: String,
}

pub fn load_reports_from_disk(path: &Path) -> Vec<AbuseReport> {
    let Ok(content) = std::fs::read_to_string(path) else { return vec![] };
    content.lines()
        .filter_map(|line| serde_json::from_str(line).ok())
        .collect()
}

pub fn load_blocklist_from_disk(path: &Path) -> std::collections::HashSet<String> {
    let Ok(content) = std::fs::read_to_string(path) else {
        return std::collections::HashSet::new()
    };
    content.lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect()
}

pub fn append_report_to_disk(path: &Path, report: &AbuseReport) {
    if let Ok(line) = serde_json::to_string(report) {
        use std::io::Write;
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
            let _ = writeln!(f, "{}", line);
        }
    }
}

pub fn append_block_to_disk(path: &Path, key: &str) {
    use std::io::Write;
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "{}", key);
    }
}

pub fn load_push_tokens_from_disk(path: &Path) -> HashMap<String, String> {
    let Ok(content) = std::fs::read_to_string(path) else {
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

pub fn append_push_token_to_disk(path: &Path, rec: &PushTokenRecord) {
    use std::io::Write;
    if let Ok(line) = serde_json::to_string(rec) {
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
            let _ = writeln!(f, "{}", line);
        }
    }
}
