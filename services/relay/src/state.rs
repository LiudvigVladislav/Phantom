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

    // ── Audit-tier persistence counters (RC-RELAY-STATE-DIR-REPAIR PR-1b §4.2) ──
    //
    // Bump on every `append_*_to_disk` outcome from the audit tier. The
    // paired `_success` counter increments ONLY after `writeln! → sync_data
    // → parent-dir fsync` all report Ok, so the ratio `_failed / (_failed +
    // _success)` is a real disk-error rate — not the pre-1b `disk_writes`
    // shape, which counted attempts. Handler HTTP semantics are unchanged
    // by the audit tier: a failure logs+counts and the caller still
    // returns 2xx (§4.2). Correctness-tier prekey counters live on
    // `PreKeyStore`; see `prekeys.rs`.
    pub reports_persist_failed: AtomicU64,
    pub reports_persist_success: AtomicU64,
    pub blocklist_persist_failed: AtomicU64,
    pub blocklist_persist_success: AtomicU64,
    pub push_tokens_persist_failed: AtomicU64,
    pub push_tokens_persist_success: AtomicU64,
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
            // RC-RELAY-STATE-DIR-REPAIR PR-1b §4.2 audit-tier counters.
            reports_persist_failed: AtomicU64::new(0),
            reports_persist_success: AtomicU64::new(0),
            blocklist_persist_failed: AtomicU64::new(0),
            blocklist_persist_success: AtomicU64::new(0),
            push_tokens_persist_failed: AtomicU64::new(0),
            push_tokens_persist_success: AtomicU64::new(0),
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

/// Sanitised shape for audit-tier persist failures. Emitted alongside the
/// structured `tracing::error!` so the log stream and the counter agree on
/// what "kind" of failure was observed without ever leaking a path or a
/// raw OS message. Same taxonomy as the correctness-tier
/// `PreKeyPersistFailure::shape()` in `prekeys.rs` — kept identical
/// deliberately so operator dashboards can compare across tiers.
fn audit_persist_shape(err: &std::io::Error) -> &'static str {
    use std::io::ErrorKind;
    match err.kind() {
        ErrorKind::PermissionDenied => "permission",
        ErrorKind::NotFound => "not_found",
        // ENOSPC on Unix / ERROR_HANDLE_DISK_FULL on Windows.
        // `ErrorKind::StorageFull` is nightly-only; match on `raw_os_error`
        // instead so this stays stable on stable Rust.
        _ => match err.raw_os_error() {
            Some(28) => "storage_full",
            _ => "io",
        },
    }
}

/// Audit-tier append with fail-loud semantics (RC-RELAY-STATE-DIR-REPAIR
/// PR-1b §4.2 audit tier). Writes `line` + '\n' to `path`, then `sync_data`
/// on the file, then `sync_all` on the state_dir (parent-dir fsync — makes
/// the directory entry itself durable, load-bearing for restart-safety on
/// crash). On any Err at any step: increment `fail_counter`, emit a
/// structured `WARN` with sanitised `shape`, return without bumping
/// `success_counter`. Handler HTTP semantics preserved by the caller;
/// audit-tier failures do NOT surface as 5xx per §4.2.
///
/// `success_counter` is incremented ONLY after ALL three durability steps
/// (write + `sync_data` + parent-dir fsync) complete, matching the mini-
/// lock rule that "counter increment only after required syncs".
fn append_audit_line(
    path: &Path,
    line: &str,
    kind: &'static str,
    success_counter: &AtomicU64,
    fail_counter: &AtomicU64,
) {
    use std::io::Write;
    use std::sync::atomic::Ordering;

    let mut file = match std::fs::OpenOptions::new().create(true).append(true).open(path) {
        Ok(f) => f,
        Err(e) => {
            fail_counter.fetch_add(1, Ordering::Relaxed);
            tracing::error!(
                event = "audit_persist_failed",
                tier = kind,
                stage = "open",
                shape = audit_persist_shape(&e),
                "state-dir audit-tier persist failed"
            );
            return;
        }
    };
    if let Err(e) = writeln!(file, "{}", line) {
        fail_counter.fetch_add(1, Ordering::Relaxed);
        tracing::error!(
            event = "audit_persist_failed",
            tier = kind,
            stage = "write",
            shape = audit_persist_shape(&e),
            "state-dir audit-tier persist failed"
        );
        return;
    }
    if let Err(e) = file.sync_data() {
        fail_counter.fetch_add(1, Ordering::Relaxed);
        tracing::error!(
            event = "audit_persist_failed",
            tier = kind,
            stage = "sync_data",
            shape = audit_persist_shape(&e),
            "state-dir audit-tier persist failed"
        );
        return;
    }
    // Parent-directory fsync so the newly-appended file's directory
    // entry is durable across a crash. On Windows this is a no-op —
    // directory-fd fsync semantics differ, so we skip and rely on the
    // fact that append writes to an existing file don't touch the
    // directory entry (the durability need is Unix-specific).
    #[cfg(unix)]
    if let Some(parent) = path.parent() {
        match std::fs::File::open(parent) {
            Ok(dir) => {
                if let Err(e) = dir.sync_all() {
                    fail_counter.fetch_add(1, Ordering::Relaxed);
                    tracing::error!(
                        event = "audit_persist_failed",
                        tier = kind,
                        stage = "sync_parent_dir",
                        shape = audit_persist_shape(&e),
                        "state-dir audit-tier persist failed"
                    );
                    return;
                }
            }
            Err(e) => {
                fail_counter.fetch_add(1, Ordering::Relaxed);
                tracing::error!(
                    event = "audit_persist_failed",
                    tier = kind,
                    stage = "open_parent_dir",
                    shape = audit_persist_shape(&e),
                    "state-dir audit-tier persist failed"
                );
                return;
            }
        }
    }
    success_counter.fetch_add(1, Ordering::Relaxed);
}

pub fn append_report_to_disk(
    path: &Path,
    report: &AbuseReport,
    success_counter: &AtomicU64,
    fail_counter: &AtomicU64,
) {
    let line = match serde_json::to_string(report) {
        Ok(l) => l,
        Err(_) => {
            // Serialisation failure is a code bug, not a disk error —
            // count it and move on. Handler still returns 2xx.
            fail_counter.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            tracing::error!(
                event = "audit_persist_failed",
                tier = "reports",
                stage = "serialize",
                shape = "io",
                "audit-tier serialization failed"
            );
            return;
        }
    };
    append_audit_line(path, &line, "reports", success_counter, fail_counter);
}

pub fn append_block_to_disk(
    path: &Path,
    key: &str,
    success_counter: &AtomicU64,
    fail_counter: &AtomicU64,
) {
    append_audit_line(path, key, "blocklist", success_counter, fail_counter);
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

pub fn append_push_token_to_disk(
    path: &Path,
    rec: &PushTokenRecord,
    success_counter: &AtomicU64,
    fail_counter: &AtomicU64,
) {
    let line = match serde_json::to_string(rec) {
        Ok(l) => l,
        Err(_) => {
            fail_counter.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            tracing::error!(
                event = "audit_persist_failed",
                tier = "push_tokens",
                stage = "serialize",
                shape = "io",
                "audit-tier serialization failed"
            );
            return;
        }
    };
    append_audit_line(path, &line, "push_tokens", success_counter, fail_counter);
}

// ── Boot preflight + singleton state-dir lock (RC-RELAY-STATE-DIR-REPAIR PR-1b §6.2) ──
//
// Order B (locked by architect + operator sign-off, mini-lock 2026-07-19):
//
//   validate cfg          — done in main() before calling this fn
//   ↓
//   ensure state_dir exists
//   ↓
//   open state_dir/.lock
//   ↓
//   fs2::try_lock_exclusive  → exit 2 on contention (no sentinel write,
//                              no state mutation — second instance never
//                              races the first over the state files)
//   ↓
//   preflight sentinel write + sync_data + parent-dir fsync + unlink
//                              under the held lock — panic-loud on any Err
//   ↓
//   return the locked File; main() binds it to `_state_dir_lock` so the
//                              lock is held for the ENTIRE process lifetime
//
// Distinct exit codes surface distinct failure modes to the operator:
//   12 → config invalid (RelayConfig::from_env())
//   2  → another relay is already holding state_dir/.lock
//   101 → panic (preflight sentinel could not be written / fsynced /
//          unlinked; state_dir is not writable or its parent isn't fsyncable)

/// Perform the Order B boot-preflight sequence and return the locked
/// `state_dir/.lock` file. Caller MUST bind the return value in `main`
/// so the lock stays alive for the process's whole lifetime. Any
/// unrecoverable step calls `std::process::exit` OR panics — this
/// function does not return an error.
pub fn state_dir_preflight(cfg: &RelayConfig) -> std::fs::File {
    use fs2::FileExt;

    let state_dir = &cfg.state_dir;

    // Step 1 — ensure state_dir exists. Panic-loud on failure; if we
    // cannot even create the directory, there is no safe way to serve.
    if let Err(e) = std::fs::create_dir_all(state_dir) {
        panic!(
            "FATAL: preflight: cannot create state_dir {}: {}",
            state_dir.display(),
            e
        );
    }

    // Step 2 — open state_dir/.lock. `create(true)` so a fresh volume
    // seeds the lock file. `write(true)` (no truncate) so we never wipe
    // the contents (currently empty, but reserved for a future BOOT
    // marker without breaking backwards-compat).
    let lock_path = state_dir.join(".lock");
    let lock_file = match std::fs::OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .truncate(false)
        .open(&lock_path)
    {
        Ok(f) => f,
        Err(e) => panic!(
            "FATAL: preflight: cannot open state_dir/.lock {}: {}",
            lock_path.display(),
            e
        ),
    };

    // Step 3 — try_lock_exclusive. ONLY `ErrorKind::WouldBlock` is
    // "another process holds the lock" (fs2 maps EAGAIN/EWOULDBLOCK on
    // Unix and `ERROR_LOCK_VIOLATION` on Windows to `WouldBlock`). Every
    // OTHER io::Error (EIO on a bad device, EOPNOTSUPP on a filesystem
    // that doesn't support advisory locks such as some NFS mounts,
    // permission changes mid-boot) means the LOCK OPERATION itself
    // failed — treating those as "contention" would mislead the
    // operator into thinking another relay is running when the real
    // failure is a boot-preflight defect. Round-1 architect P1.
    match lock_file.try_lock_exclusive() {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
            eprintln!(
                "FATAL: another relay instance holds {} — refusing to start",
                lock_path.display()
            );
            std::process::exit(2);
        }
        Err(e) => panic!(
            "FATAL: preflight: try_lock_exclusive on {} failed: {} \
             (this is NOT lock contention — likely EIO or an unsupported \
             filesystem)",
            lock_path.display(),
            e
        ),
    }

    // Step 4 — preflight sentinel: prove the state_dir accepts a
    // write+fsync+unlink cycle right now, under the held lock. This
    // catches EROFS / EACCES / ENOSPC before AppState::new starts
    // returning empty maps on silent-EROFS. Any error → panic-loud.
    let sentinel_path = state_dir.join(".preflight-sentinel");
    {
        let mut sentinel = match std::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&sentinel_path)
        {
            Ok(f) => f,
            Err(e) => panic!(
                "FATAL: preflight: cannot create sentinel {}: {}",
                sentinel_path.display(),
                e
            ),
        };
        use std::io::Write;
        if let Err(e) = writeln!(sentinel, "preflight") {
            panic!(
                "FATAL: preflight: cannot write sentinel {}: {}",
                sentinel_path.display(),
                e
            );
        }
        if let Err(e) = sentinel.sync_data() {
            panic!(
                "FATAL: preflight: cannot fsync sentinel {}: {}",
                sentinel_path.display(),
                e
            );
        }
    } // sentinel `File` closes here so remove_file can succeed on Windows

    // Parent-directory fsync so the sentinel's directory entry is
    // durable; matches the audit-tier writer's durability contract.
    // Unix-only (Windows doesn't expose fsync on directories the same
    // way; on Linux this is load-bearing for the "state_dir has ever
    // received a durable write" preflight guarantee).
    #[cfg(unix)]
    {
        match std::fs::File::open(state_dir) {
            Ok(dir) => {
                if let Err(e) = dir.sync_all() {
                    panic!(
                        "FATAL: preflight: cannot fsync state_dir {}: {}",
                        state_dir.display(),
                        e
                    );
                }
            }
            Err(e) => panic!(
                "FATAL: preflight: cannot open state_dir for fsync {}: {}",
                state_dir.display(),
                e
            ),
        }
    }

    if let Err(e) = std::fs::remove_file(&sentinel_path) {
        panic!(
            "FATAL: preflight: cannot unlink sentinel {}: {}",
            sentinel_path.display(),
            e
        );
    }

    tracing::info!(
        event = "state_dir_preflight_ok",
        state_dir = %state_dir.display(),
        "state_dir preflight OK (writable + fsyncable + lock held)"
    );

    lock_file
}
