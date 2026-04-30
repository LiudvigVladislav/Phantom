// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

use crate::config::RelayConfig;
use crate::envelope::Envelope;
use crate::prekeys::PreKeyStore;
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
}

impl AppState {
    pub fn new(config: RelayConfig) -> Self {
        // Load persisted reports from disk
        let persisted = load_reports_from_disk();
        // Load persisted blocklist from disk
        let blocked = load_blocklist_from_disk();
        Self {
            config,
            store: RwLock::new(HashMap::new()),
            clients: RwLock::new(HashMap::new()),
            conn_counter: AtomicU64::new(0),
            rate_limiter: RwLock::new(HashMap::new()),
            reports: RwLock::new(persisted),
            blocklist: RwLock::new(blocked),
            prekeys: PreKeyStore::new(),
        }
    }
}

const REPORTS_FILE: &str = "reports.jsonl";
const BLOCKLIST_FILE: &str = "blocklist.txt";

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
