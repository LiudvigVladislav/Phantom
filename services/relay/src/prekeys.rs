// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! X3DH prekey bundle storage and verification.
//!
//! Implements the relay-side half of ADR-009 4-DH:
//!  - publishers (clients) upload a [SignedPreKeyPublicBundle] plus a batch of
//!    [OneTimePreKeyPublicBundle]s, signed by their identity Ed25519 key
//!  - consumers (other clients starting a new session) fetch a [PreKeyBundle]
//!    that contains the current SPK and ATOMICALLY consumes one OPK from the
//!    pool (or returns an empty OPK slot for the 3-DH fallback path)
//!
//! ## Storage shape (Alpha-2 in-memory, ADR-018 will graduate to SQL)
//!
//! Two `HashMap`s under `RwLock`, mirroring the existing relay state pattern
//! (`AppState::store`, `AppState::reports`). Persistence to disk is optional
//! and append-only via JSONL — same approach as `reports.jsonl` and
//! `blocklist.txt`. A relay restart loses the state cleanly: clients
//! re-publish on next online session. This is deliberate Alpha-2 scope:
//! upgrading to a proper transactional store is its own ADR.
//!
//! ## Security boundaries
//!
//! 1. The relay verifies the Ed25519 signature on every [SignedPreKeyPublicBundle]
//!    before accepting a publish. A bundle whose signature does not validate
//!    against the claimed `identity_pubkey` is rejected with 400.
//! 2. The OPK pool is consumed atomically inside a single write-lock critical
//!    section; two concurrent fetches cannot both receive the same OPK.
//! 3. The relay never sees private keys. It never derives session secrets.
//!    It only stores ciphertext-equivalent (public-key) material.
//! 4. SPK rotation keeps ONE generation back for the 14-day retention window
//!    so handshakes initiated against a just-rotated SPK can still complete.
//!    This is the same retention strategy as Signal's libsignal SPK lifetime.
//!
//! ## What is intentionally NOT here
//!
//! - SQL/sqlite. The existing relay is in-memory; bringing in sqlx is a
//!   workspace-wide architectural change that warrants its own ADR. We keep
//!   the storage shape consistent with `state.rs`.
//! - Bundle signature verification at fetch time. Verification happens at
//!   publish time once; verified bundles are stored verbatim. Re-verifying
//!   on every fetch would burn CPU on every handshake.
//! - Replay protection on `DELETE` requests via timestamp window. The DELETE
//!   path requires a fresh signature each call, which is sufficient: an
//!   attacker who has the identity Ed25519 secret already owns the account.

use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Write;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::sync::RwLock;

// ── Constants ───────────────────────────────────────────────────────────────

/// Wire format identifier prefixed onto the SPK signature payload.
/// Mirrors `phantom.core.crypto.SignedPreKeySigner.DOMAIN_LABEL` on the client.
/// Domain separation: a signature produced for any other phantom step
/// (e.g. the future `phantom-opk-delete-v1` payload) cannot be replayed here.
const SPK_DOMAIN_LABEL: &str = "phantom-spk-v1";

/// Wire format identifier for DELETE-OPK request signatures.
/// The client signs `OPK_DELETE_DOMAIN_LABEL || identity_pubkey || keyId || timestamp_ms`
/// (timestamp is 8-byte big-endian milliseconds) so a stolen-but-stale
/// signature cannot replay a deletion against a rotated keyId.
const OPK_DELETE_DOMAIN_LABEL: &str = "phantom-opk-delete-v1";

/// Maximum tolerated clock skew for DELETE-OPK signatures. 5 minutes —
/// generous enough for client/server clock drift, tight enough that a
/// signature lifted off the wire becomes useless quickly.
const DELETE_TIMESTAMP_TOLERANCE_MS: i64 = 5 * 60 * 1_000;

/// SPK retention window after rotation. A handshake initiated against an
/// SPK that the publisher just rotated still completes if it lands within
/// this window. 14 days mirrors libsignal's default.
pub const SPK_PREVIOUS_RETENTION_DAYS: u64 = 14;

/// Hard cap on OPKs a single publish call may upload. Prevents a malicious
/// or buggy client from filling the relay's memory with unused OPKs.
const MAX_OPKS_PER_PUBLISH: usize = 100;

/// Per-identity pool size cap. After consume + replenish across multiple
/// publishes a single identity must not be able to grow its pool past this.
const MAX_OPKS_PER_IDENTITY: usize = 200;

/// JSONL file the relay appends every publish/rotate/consume event to.
/// Rebuilt on startup (best-effort) by replaying the file. Same pattern as
/// `reports.jsonl` in `state.rs`.
const PREKEYS_FILE: &str = "prekeys.jsonl";

// ── Wire types ──────────────────────────────────────────────────────────────

/// Public half of a SignedPreKey, signed by the identity Ed25519 key.
///
/// Mirrors `phantom.core.crypto.SignedPreKeyPublicBundle` on the client.
/// `signature` is over `SPK_DOMAIN_LABEL || public_key || created_at_ms`
/// where `created_at_ms` is encoded as 8 bytes big-endian.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SignedPreKeyPublicBundle {
    pub key_id: i64,
    /// 32-byte Curve25519 public key, hex-encoded on the wire for human
    /// readability and consistency with the rest of the relay's identity
    /// addressing (also hex on `/send`, `/fetch/:recipient`, etc).
    pub public_key_hex: String,
    pub created_at_ms: i64,
    /// 64-byte detached Ed25519 signature, hex-encoded.
    pub signature_hex: String,
}

/// Public half of a OneTimePreKey. The relay assigns `key_id` server-side
/// (16 random bytes) so two clients can independently submit OPKs without
/// colliding on numeric IDs.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OneTimePreKeyPublicBundle {
    /// 16-byte random ID, hex-encoded (32 hex chars).
    pub key_id_hex: String,
    /// 32-byte Curve25519 public key, hex-encoded.
    pub public_key_hex: String,
}

/// What `GET /prekeys/bundle/:identity` returns. The OPK slot is `None`
/// when the identity's OPK pool is empty — clients then fall back to 3-DH
/// (still under the new HKDF salt, see ADR-009).
///
/// `signing_pubkey_hex` (Ed25519) is what the fetcher uses to verify the
/// SPK signature client-side. `identity_pubkey_hex` (X25519) is the
/// routing identity used by the rest of the relay surface (matches the
/// identity in QR codes and `/send`/`/fetch` endpoints). The two keypairs
/// are stored independently per ADR-009 — the relay enforces only the
/// 1:1 binding (one signing pubkey per X25519 identity).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreKeyBundle {
    pub identity_pubkey_hex: String,
    pub signing_pubkey_hex: String,
    pub signed_pre_key: SignedPreKeyPublicBundle,
    pub one_time_pre_key: Option<OneTimePreKeyPublicBundle>,
}

/// What `GET /prekeys/status/:identity` returns. Used by the client to
/// decide when to replenish the OPK pool (typical threshold: <20 remaining).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreKeyStatus {
    pub remaining_opks: usize,
    /// Wall-clock age of the current SPK. Negative or null if no SPK has
    /// been published yet.
    pub signed_prekey_age_days: Option<i64>,
}

// ── Stored representation ───────────────────────────────────────────────────

/// In-memory record of an identity's published prekey state. Mirrors what a
/// SQL row pair (signed_prekeys + one_time_prekeys) would hold in ADR-018.
///
/// `signing_pubkey_hex` is the Ed25519 verifying key that signed the SPK.
/// Stored alongside the X25519 `identity_pubkey_hex` per ADR-009's
/// two-keypair IdentityRecord model. `signing_pubkey_hex` defaults to
/// empty when an Alpha-1 client publishes without it (rejected in the
/// publish path; persisted-then-replayed records that predate this
/// field on disk default to empty and force a re-publish on next
/// publish call).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoredPreKeyState {
    pub identity_pubkey_hex: String,
    #[serde(default)]
    pub signing_pubkey_hex: String,
    pub current_spk: SignedPreKeyPublicBundle,
    /// Set when an SPK has just been rotated; cleaned up after
    /// `SPK_PREVIOUS_RETENTION_DAYS`. The relay does not currently serve
    /// `previous_spk` from `/prekeys/bundle` (always returns `current`),
    /// but PR C will add the explicit "I started against this older SPK"
    /// path so the recipient can pick the right keypair.
    pub previous_spk: Option<SignedPreKeyPublicBundle>,
    pub previous_retired_at_ms: Option<i64>,
    pub one_time_prekeys: Vec<OneTimePreKeyPublicBundle>,
}

// ── Storage ─────────────────────────────────────────────────────────────────

/// Keyed by hex(identity_pubkey).
pub struct PreKeyStore {
    inner: RwLock<HashMap<String, StoredPreKeyState>>,
    /// Sliding-window publish-rate counters. Distinct from the relay's
    /// per-message rate limiter so a chatty user can still publish prekeys.
    rate: RwLock<HashMap<String, RateBucket>>,
    /// Monotonic disk write counter for testing the persistence path.
    pub disk_writes: AtomicU64,
}

#[derive(Debug, Clone)]
pub struct RateBucket {
    pub count: u32,
    pub window_start: std::time::Instant,
}

impl PreKeyStore {
    pub fn new() -> Self {
        Self {
            inner: RwLock::new(load_from_disk()),
            rate: RwLock::new(HashMap::new()),
            disk_writes: AtomicU64::new(0),
        }
    }

    /// Test-only constructor that skips disk replay. Real production paths
    /// always go through [`new`] which seeds from `prekeys.jsonl`.
    #[cfg(test)]
    pub fn empty() -> Self {
        Self {
            inner: RwLock::new(HashMap::new()),
            rate: RwLock::new(HashMap::new()),
            disk_writes: AtomicU64::new(0),
        }
    }

    /// Atomic publish: validates the SPK signature, rotates the previous
    /// SPK if one existed, replaces the OPK pool with the freshly-uploaded
    /// batch (deduplicating by key_id_hex). Returns the count of OPKs
    /// actually stored (after dedup + cap).
    ///
    /// If `existing_state` already holds an SPK with the same `key_id` as
    /// the incoming SPK, the rotation is a no-op (publish is idempotent
    /// for retries) — this prevents a flaky client from rotating itself
    /// out of its own previous-SPK retention window.
    pub async fn publish(
        &self,
        identity_pubkey_hex: &str,
        signing_pubkey_hex: &str,
        spk: SignedPreKeyPublicBundle,
        opks: Vec<OneTimePreKeyPublicBundle>,
        now_ms: i64,
    ) -> Result<usize, PublishError> {
        validate_identity_hex(identity_pubkey_hex).map_err(PublishError::BadIdentity)?;
        validate_signing_hex(signing_pubkey_hex).map_err(PublishError::BadSigningKey)?;
        verify_spk_signature(signing_pubkey_hex, &spk).map_err(PublishError::BadSignature)?;
        if opks.len() > MAX_OPKS_PER_PUBLISH {
            return Err(PublishError::TooManyOpks(opks.len()));
        }
        for opk in &opks {
            validate_opk_shape(opk).map_err(PublishError::BadOpk)?;
        }

        let mut map = self.inner.write().await;
        // Bound the X25519 identity to a single Ed25519 signing key. Once a
        // signing key is registered, only its rotation is allowed via the
        // explicit "rotate signing key" path (TBD post-Alpha 2). Rejecting
        // mismatched signing keys protects against an attacker who steals
        // the X25519 identity but lacks the matching Ed25519 secret.
        if let Some(prev) = map.get(identity_pubkey_hex) {
            if !prev.signing_pubkey_hex.is_empty()
                && prev.signing_pubkey_hex != signing_pubkey_hex
            {
                return Err(PublishError::SigningKeyMismatch);
            }
        }
        let entry = map.get(identity_pubkey_hex).cloned();
        let stored = match entry {
            Some(mut prev) => {
                // Idempotent retry guard: same key_id → no rotation.
                let rotating = prev.current_spk.key_id != spk.key_id;
                if rotating {
                    prev.previous_spk = Some(prev.current_spk.clone());
                    prev.previous_retired_at_ms = Some(now_ms);
                }
                prev.signing_pubkey_hex = signing_pubkey_hex.to_string();
                prev.current_spk = spk;
                // OPK pool is REPLACED on each publish, not merged: the
                // client owns its OPK lifecycle and a publish is the
                // canonical "here is my current pool" statement.
                prev.one_time_prekeys = dedup_and_cap_opks(opks);
                prev
            }
            None => StoredPreKeyState {
                identity_pubkey_hex: identity_pubkey_hex.to_string(),
                signing_pubkey_hex: signing_pubkey_hex.to_string(),
                current_spk: spk,
                previous_spk: None,
                previous_retired_at_ms: None,
                one_time_prekeys: dedup_and_cap_opks(opks),
            },
        };
        let count = stored.one_time_prekeys.len();
        map.insert(identity_pubkey_hex.to_string(), stored.clone());
        drop(map);
        // Persist outside the lock so the disk write doesn't block readers.
        append_to_disk(&stored);
        self.disk_writes.fetch_add(1, Ordering::Relaxed);
        Ok(count)
    }

    /// Atomic bundle fetch: pops one OPK from the pool (if present) and
    /// returns it together with the current SPK. The pop happens inside
    /// the same write-lock critical section so two concurrent fetches
    /// cannot both receive the same OPK — that's the whole point of OPKs.
    pub async fn consume_bundle(
        &self,
        identity_pubkey_hex: &str,
    ) -> Option<PreKeyBundle> {
        let mut map = self.inner.write().await;
        let entry = map.get_mut(identity_pubkey_hex)?;
        let one_time = entry.one_time_prekeys.pop();
        let bundle = PreKeyBundle {
            identity_pubkey_hex: entry.identity_pubkey_hex.clone(),
            signing_pubkey_hex: entry.signing_pubkey_hex.clone(),
            signed_pre_key: entry.current_spk.clone(),
            one_time_pre_key: one_time.clone(),
        };
        let snapshot = entry.clone();
        drop(map);
        // Persist the post-consume state so a relay restart doesn't
        // hand out the same OPK twice.
        if one_time.is_some() {
            append_to_disk(&snapshot);
            self.disk_writes.fetch_add(1, Ordering::Relaxed);
        }
        Some(bundle)
    }

    pub async fn status(&self, identity_pubkey_hex: &str, now_ms: i64) -> PreKeyStatus {
        let map = self.inner.read().await;
        match map.get(identity_pubkey_hex) {
            Some(s) => PreKeyStatus {
                remaining_opks: s.one_time_prekeys.len(),
                signed_prekey_age_days: Some(
                    ((now_ms - s.current_spk.created_at_ms).max(0)) / (24 * 3600 * 1000),
                ),
            },
            None => PreKeyStatus { remaining_opks: 0, signed_prekey_age_days: None },
        }
    }

    /// Signed OPK deletion. Used rarely — clients normally let the consume
    /// path drain the pool naturally. The signature payload is
    /// `OPK_DELETE_DOMAIN_LABEL || identity_pubkey || keyId || timestamp_ms`
    /// where `timestamp_ms` is 8-byte big-endian. `now_ms` is checked to be
    /// within `DELETE_TIMESTAMP_TOLERANCE_MS` of the signed timestamp to
    /// blunt off-the-wire replay.
    pub async fn delete_opk(
        &self,
        identity_pubkey_hex: &str,
        key_id_hex: &str,
        signed_timestamp_ms: i64,
        signature_hex: &str,
        now_ms: i64,
    ) -> Result<(), DeleteError> {
        validate_identity_hex(identity_pubkey_hex).map_err(DeleteError::BadIdentity)?;
        if (now_ms - signed_timestamp_ms).abs() > DELETE_TIMESTAMP_TOLERANCE_MS {
            return Err(DeleteError::TimestampOutOfWindow);
        }

        // Look up the registered Ed25519 signing key for this X25519
        // identity FIRST — DELETE signatures are verified against the
        // signing key, not against the X25519 identity key (the latter
        // doesn't even support Ed25519 signatures). The bound 1:1 mapping
        // was established at publish time.
        let signing_pubkey_hex = {
            let map = self.inner.read().await;
            match map.get(identity_pubkey_hex) {
                Some(s) if !s.signing_pubkey_hex.is_empty() => s.signing_pubkey_hex.clone(),
                _ => return Err(DeleteError::NotFound),
            }
        };

        verify_opk_delete_signature(
            &signing_pubkey_hex,
            identity_pubkey_hex,
            key_id_hex,
            signed_timestamp_ms,
            signature_hex,
        )
        .map_err(DeleteError::BadSignature)?;

        let mut map = self.inner.write().await;
        let entry = map.get_mut(identity_pubkey_hex).ok_or(DeleteError::NotFound)?;
        let before = entry.one_time_prekeys.len();
        entry.one_time_prekeys.retain(|o| o.key_id_hex != key_id_hex);
        if entry.one_time_prekeys.len() == before {
            return Err(DeleteError::NotFound);
        }
        let snapshot = entry.clone();
        drop(map);
        append_to_disk(&snapshot);
        self.disk_writes.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }

    /// Retire SPKs whose `previous_retired_at_ms` is older than the
    /// retention window. Called from the cleanup background task.
    pub async fn purge_expired_previous_spks(&self, now_ms: i64) {
        let cutoff = now_ms
            - (SPK_PREVIOUS_RETENTION_DAYS as i64) * 24 * 3600 * 1000;
        let mut map = self.inner.write().await;
        for entry in map.values_mut() {
            if matches!(entry.previous_retired_at_ms, Some(t) if t < cutoff) {
                entry.previous_spk = None;
                entry.previous_retired_at_ms = None;
            }
        }
    }

    /// Rate-limit decision for a publish-style endpoint. Sliding window
    /// distinct from the WS message limiter so prekey rotation does not
    /// share quota with regular sends. Returns true if the call may proceed.
    pub async fn allow_call(
        &self,
        bucket_key: &str,
        max_calls: u32,
        window_secs: u64,
    ) -> bool {
        let mut rates = self.rate.write().await;
        let entry = rates.entry(bucket_key.to_string()).or_insert(RateBucket {
            count: 0,
            window_start: std::time::Instant::now(),
        });
        if entry.window_start.elapsed().as_secs() >= window_secs {
            entry.count = 1;
            entry.window_start = std::time::Instant::now();
            true
        } else if entry.count < max_calls {
            entry.count += 1;
            true
        } else {
            false
        }
    }
}

// ── Errors ──────────────────────────────────────────────────────────────────

#[derive(Debug)]
pub enum PublishError {
    BadIdentity(&'static str),
    BadSigningKey(&'static str),
    BadSignature(&'static str),
    BadOpk(&'static str),
    TooManyOpks(usize),
    /// A bundle was published claiming the same X25519 identity as a
    /// prior publish but with a DIFFERENT Ed25519 signing key. The
    /// 1:1 X25519↔Ed25519 binding per identity is enforced server-side.
    SigningKeyMismatch,
}

#[derive(Debug)]
pub enum DeleteError {
    BadIdentity(&'static str),
    BadSignature(&'static str),
    TimestampOutOfWindow,
    NotFound,
}

// ── Validation + signature verification ─────────────────────────────────────

/// 32 bytes hex-encoded → 64 hex chars. Reject anything else fast so the
/// parsing/verify path can assume a known-good shape.
fn validate_identity_hex(s: &str) -> Result<(), &'static str> {
    if s.len() != 64 {
        return Err("identity_pubkey_hex must be 64 hex chars");
    }
    if !s.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("identity_pubkey_hex contains non-hex characters");
    }
    Ok(())
}

/// Same shape check as identity but with distinct error messages so a
/// malformed bundle is debuggable on the client side.
fn validate_signing_hex(s: &str) -> Result<(), &'static str> {
    if s.len() != 64 {
        return Err("signing_pubkey_hex must be 64 hex chars");
    }
    if !s.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("signing_pubkey_hex contains non-hex characters");
    }
    Ok(())
}

fn validate_opk_shape(opk: &OneTimePreKeyPublicBundle) -> Result<(), &'static str> {
    if opk.key_id_hex.len() != 32 || !opk.key_id_hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("opk key_id_hex must be 32 hex chars (16 bytes)");
    }
    if opk.public_key_hex.len() != 64
        || !opk.public_key_hex.chars().all(|c| c.is_ascii_hexdigit())
    {
        return Err("opk public_key_hex must be 64 hex chars (32 bytes)");
    }
    Ok(())
}

/// Recompute the canonical signing payload and verify the Ed25519 signature
/// against the publisher's Ed25519 signing public key. The verifier
/// reconstructs the payload itself — never trust a wire-supplied "message"
/// buffer.
///
/// `signing_pubkey_hex` is the Ed25519 verifying key (NOT the X25519
/// `identity_pubkey_hex`). Per ADR-009 the two keypairs are stored
/// independently on `IdentityRecord` and shipped together in
/// `PublishRequest`.
fn verify_spk_signature(
    signing_pubkey_hex: &str,
    spk: &SignedPreKeyPublicBundle,
) -> Result<(), &'static str> {
    let signing_bytes =
        hex::decode(signing_pubkey_hex).map_err(|_| "signing_pubkey_hex hex-decode failed")?;
    let signing_arr: [u8; 32] = signing_bytes
        .as_slice()
        .try_into()
        .map_err(|_| "signing_pubkey_hex must decode to 32 bytes")?;
    let verifying_key =
        VerifyingKey::from_bytes(&signing_arr).map_err(|_| "signing_pubkey_hex not a valid Ed25519 pubkey")?;

    let spk_pub_bytes =
        hex::decode(&spk.public_key_hex).map_err(|_| "spk public_key_hex hex-decode failed")?;
    if spk_pub_bytes.len() != 32 {
        return Err("spk public_key must decode to 32 bytes");
    }
    let sig_bytes =
        hex::decode(&spk.signature_hex).map_err(|_| "spk signature_hex hex-decode failed")?;
    if sig_bytes.len() != 64 {
        return Err("spk signature must decode to 64 bytes");
    }

    let mut payload = Vec::with_capacity(SPK_DOMAIN_LABEL.len() + 32 + 8);
    payload.extend_from_slice(SPK_DOMAIN_LABEL.as_bytes());
    payload.extend_from_slice(&spk_pub_bytes);
    payload.extend_from_slice(&spk.created_at_ms.to_be_bytes());

    let sig_arr: [u8; 64] = sig_bytes
        .as_slice()
        .try_into()
        .map_err(|_| "signature length")?;
    let signature = Signature::from_bytes(&sig_arr);
    verifying_key
        .verify(&payload, &signature)
        .map_err(|_| "Ed25519 signature verify failed")
}

/// Verify a DELETE-OPK request signature.
///
/// The Ed25519 verifying key (`signing_pubkey_hex`) is the one registered
/// at publish time for this X25519 identity. Payload is bound to BOTH
/// the X25519 identity (so a stolen signature can't be replayed against
/// a different identity that happens to share an Ed25519 signing key)
/// AND the Ed25519 signing key (defence in depth — distinct from the
/// X25519 routing identity). Specifically:
///
///   payload = OPK_DELETE_DOMAIN_LABEL
///          || identity_pubkey (X25519, 32 bytes raw)
///          || signing_pubkey  (Ed25519, 32 bytes raw)
///          || keyId (16 bytes raw)
///          || timestamp_ms (8 bytes big-endian)
fn verify_opk_delete_signature(
    signing_pubkey_hex: &str,
    identity_pubkey_hex: &str,
    key_id_hex: &str,
    signed_timestamp_ms: i64,
    signature_hex: &str,
) -> Result<(), &'static str> {
    let signing_bytes =
        hex::decode(signing_pubkey_hex).map_err(|_| "signing_pubkey hex-decode")?;
    let signing_arr: [u8; 32] = signing_bytes
        .as_slice()
        .try_into()
        .map_err(|_| "signing_pubkey must be 32 bytes")?;
    let verifying_key = VerifyingKey::from_bytes(&signing_arr)
        .map_err(|_| "signing_pubkey not Ed25519 pubkey")?;
    let identity_bytes =
        hex::decode(identity_pubkey_hex).map_err(|_| "identity hex-decode")?;
    let identity_arr: [u8; 32] = identity_bytes
        .as_slice()
        .try_into()
        .map_err(|_| "identity must be 32 bytes")?;
    let sig_bytes = hex::decode(signature_hex).map_err(|_| "signature hex-decode")?;
    if sig_bytes.len() != 64 {
        return Err("signature must be 64 bytes");
    }
    let key_id_bytes = hex::decode(key_id_hex).map_err(|_| "keyId hex-decode")?;
    if key_id_bytes.len() != 16 {
        return Err("keyId must be 16 bytes");
    }
    let mut payload = Vec::with_capacity(
        OPK_DELETE_DOMAIN_LABEL.len() + 32 + 32 + 16 + 8,
    );
    payload.extend_from_slice(OPK_DELETE_DOMAIN_LABEL.as_bytes());
    payload.extend_from_slice(&identity_arr);
    payload.extend_from_slice(&signing_arr);
    payload.extend_from_slice(&key_id_bytes);
    payload.extend_from_slice(&signed_timestamp_ms.to_be_bytes());

    let sig_arr: [u8; 64] = sig_bytes
        .as_slice()
        .try_into()
        .map_err(|_| "signature length")?;
    let signature = Signature::from_bytes(&sig_arr);
    verifying_key
        .verify(&payload, &signature)
        .map_err(|_| "Ed25519 signature verify failed")
}

// ── Helpers ─────────────────────────────────────────────────────────────────

fn dedup_and_cap_opks(
    opks: Vec<OneTimePreKeyPublicBundle>,
) -> Vec<OneTimePreKeyPublicBundle> {
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    let mut out = Vec::with_capacity(opks.len().min(MAX_OPKS_PER_IDENTITY));
    for o in opks {
        if out.len() >= MAX_OPKS_PER_IDENTITY {
            break;
        }
        if seen.insert(o.key_id_hex.clone()) {
            out.push(o);
        }
    }
    out
}

/// Server-side helper used by `POST /prekeys/publish` to mint stable 16-byte
/// IDs when the client uploads OPKs without ID assignment. Currently the
/// wire format requires the client to provide `key_id_hex`, but this is
/// kept as a utility for future server-mint paths.
#[allow(dead_code)]
pub fn random_opk_id_hex() -> String {
    let mut buf = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut buf);
    hex::encode(buf)
}

// ── Persistence ─────────────────────────────────────────────────────────────

/// Best-effort load of the prekey store from disk. Each line is a JSON
/// snapshot of one identity's [`StoredPreKeyState`]; the most recent line
/// for a given identity wins (replay-style). If the file is missing or
/// corrupt the relay starts empty — same forgiving behavior as
/// `state::load_reports_from_disk`.
fn load_from_disk() -> HashMap<String, StoredPreKeyState> {
    let Ok(content) = std::fs::read_to_string(PREKEYS_FILE) else {
        return HashMap::new();
    };
    let mut map: HashMap<String, StoredPreKeyState> = HashMap::new();
    for line in content.lines() {
        if let Ok(state) = serde_json::from_str::<StoredPreKeyState>(line) {
            map.insert(state.identity_pubkey_hex.clone(), state);
        }
    }
    map
}

fn append_to_disk(state: &StoredPreKeyState) {
    if let Ok(line) = serde_json::to_string(state) {
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(PREKEYS_FILE)
        {
            let _ = writeln!(f, "{}", line);
        }
    }
}

// ── Tests ───────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use rand::rngs::OsRng;

    fn build_signed_spk(
        identity_signing: &SigningKey,
        key_id: i64,
        spk_pub: [u8; 32],
        created_at_ms: i64,
    ) -> SignedPreKeyPublicBundle {
        let mut payload = Vec::new();
        payload.extend_from_slice(SPK_DOMAIN_LABEL.as_bytes());
        payload.extend_from_slice(&spk_pub);
        payload.extend_from_slice(&created_at_ms.to_be_bytes());
        let sig: Signature = identity_signing.sign(&payload);
        SignedPreKeyPublicBundle {
            key_id,
            public_key_hex: hex::encode(spk_pub),
            created_at_ms,
            signature_hex: hex::encode(sig.to_bytes()),
        }
    }

    fn make_opk(seed: u8) -> OneTimePreKeyPublicBundle {
        let mut id = [0u8; 16];
        id[0] = seed;
        let mut pk = [0u8; 32];
        pk[0] = seed;
        OneTimePreKeyPublicBundle {
            key_id_hex: hex::encode(id),
            public_key_hex: hex::encode(pk),
        }
    }

    /// Per ADR-009 the X25519 routing identity is independent from the
    /// Ed25519 signing identity. The relay only checks shape on
    /// `identity_pubkey_hex` (must be 32 bytes hex) so we can use any
    /// well-formed value in tests — what matters is that
    /// `signing_pubkey_hex` matches the keypair that signed the SPK.
    fn synthetic_identity_hex(seed: u8) -> String {
        let mut buf = [0u8; 32];
        for (i, b) in buf.iter_mut().enumerate() {
            *b = seed.wrapping_add(i as u8);
        }
        hex::encode(buf)
    }

    #[tokio::test]
    async fn publish_and_consume_round_trip() {
        let signing_kp = SigningKey::generate(&mut OsRng);
        let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let identity_hex = synthetic_identity_hex(1);

        let spk = build_signed_spk(&signing_kp, 1, [42u8; 32], 1_700_000_000_000);
        let opks = vec![make_opk(1), make_opk(2), make_opk(3)];

        let store = PreKeyStore::empty();
        let count = store
            .publish(
                &identity_hex,
                &signing_hex,
                spk.clone(),
                opks.clone(),
                1_700_000_000_000,
            )
            .await
            .expect("publish should succeed");
        assert_eq!(count, 3);

        let bundle = store
            .consume_bundle(&identity_hex)
            .await
            .expect("bundle should exist");
        assert_eq!(bundle.identity_pubkey_hex, identity_hex);
        assert_eq!(bundle.signing_pubkey_hex, signing_hex);
        assert_eq!(bundle.signed_pre_key.key_id, spk.key_id);
        assert_eq!(bundle.signed_pre_key.public_key_hex, spk.public_key_hex);
        assert!(bundle.one_time_pre_key.is_some());

        let status = store.status(&identity_hex, 1_700_000_000_000).await;
        assert_eq!(status.remaining_opks, 2); // one consumed
    }

    #[tokio::test]
    async fn opk_consume_is_atomic_under_concurrent_fetches() {
        let signing_kp = SigningKey::generate(&mut OsRng);
        let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let identity_hex = synthetic_identity_hex(2);
        let spk = build_signed_spk(&signing_kp, 1, [1u8; 32], 0);
        // Single OPK in the pool — exactly one of the two concurrent fetches
        // must receive it; the other must see one_time_pre_key = None.
        let opks = vec![make_opk(7)];

        let store = std::sync::Arc::new(PreKeyStore::empty());
        store
            .publish(&identity_hex, &signing_hex, spk, opks, 0)
            .await
            .unwrap();

        let s1 = std::sync::Arc::clone(&store);
        let s2 = std::sync::Arc::clone(&store);
        let id1 = identity_hex.clone();
        let id2 = identity_hex.clone();

        let (b1, b2) = tokio::join!(
            tokio::spawn(async move { s1.consume_bundle(&id1).await.unwrap() }),
            tokio::spawn(async move { s2.consume_bundle(&id2).await.unwrap() }),
        );
        let b1 = b1.unwrap();
        let b2 = b2.unwrap();
        let got_opk_1 = b1.one_time_pre_key.is_some();
        let got_opk_2 = b2.one_time_pre_key.is_some();
        assert!(
            got_opk_1 ^ got_opk_2,
            "Exactly one fetcher must receive the single OPK (XOR check)"
        );

        let status = store.status(&identity_hex, 0).await;
        assert_eq!(status.remaining_opks, 0);
    }

    #[tokio::test]
    async fn publishing_new_spk_rotates_previous() {
        let signing_kp = SigningKey::generate(&mut OsRng);
        let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let identity_hex = synthetic_identity_hex(3);

        let spk_a = build_signed_spk(&signing_kp, 1, [10u8; 32], 1_000);
        let spk_b = build_signed_spk(&signing_kp, 2, [11u8; 32], 2_000);

        let store = PreKeyStore::empty();
        store
            .publish(&identity_hex, &signing_hex, spk_a.clone(), vec![], 1_000)
            .await
            .unwrap();
        store
            .publish(&identity_hex, &signing_hex, spk_b.clone(), vec![], 2_000)
            .await
            .unwrap();

        let map = store.inner.read().await;
        let s = map.get(&identity_hex).unwrap();
        assert_eq!(s.current_spk.key_id, 2);
        let prev = s.previous_spk.as_ref().expect("previous SPK retained");
        assert_eq!(prev.key_id, 1);
        assert_eq!(s.previous_retired_at_ms, Some(2_000));
    }

    #[tokio::test]
    async fn previous_spk_is_purged_after_retention_window() {
        let signing_kp = SigningKey::generate(&mut OsRng);
        let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let identity_hex = synthetic_identity_hex(4);

        let spk_a = build_signed_spk(&signing_kp, 1, [10u8; 32], 1_000);
        let spk_b = build_signed_spk(&signing_kp, 2, [11u8; 32], 2_000);
        let store = PreKeyStore::empty();
        store
            .publish(&identity_hex, &signing_hex, spk_a, vec![], 1_000)
            .await
            .unwrap();
        store
            .publish(&identity_hex, &signing_hex, spk_b, vec![], 2_000)
            .await
            .unwrap();

        // Advance "now" past the retention window.
        let one_day_ms: i64 = 24 * 3600 * 1000;
        let after_retention = 2_000 + (SPK_PREVIOUS_RETENTION_DAYS as i64 + 1) * one_day_ms;
        store.purge_expired_previous_spks(after_retention).await;

        let map = store.inner.read().await;
        let s = map.get(&identity_hex).unwrap();
        assert!(s.previous_spk.is_none());
        assert!(s.previous_retired_at_ms.is_none());
    }

    #[tokio::test]
    async fn invalid_signature_rejected() {
        let signing_kp = SigningKey::generate(&mut OsRng);
        let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let identity_hex = synthetic_identity_hex(5);

        // Sign a payload, then flip a bit in the signature: must reject.
        let mut spk = build_signed_spk(&signing_kp, 1, [42u8; 32], 1_000);
        let mut sig_bytes = hex::decode(&spk.signature_hex).unwrap();
        sig_bytes[0] ^= 0x01;
        spk.signature_hex = hex::encode(sig_bytes);

        let store = PreKeyStore::empty();
        let r = store
            .publish(&identity_hex, &signing_hex, spk, vec![], 1_000)
            .await;
        match r {
            Err(PublishError::BadSignature(_)) => {}
            other => panic!("expected BadSignature, got {:?}", other),
        }
    }

    #[tokio::test]
    async fn signature_under_wrong_signing_key_rejected() {
        // Honest publisher's signing key, attacker's signing key.
        let owner_kp = SigningKey::generate(&mut OsRng);
        let owner_signing_hex = hex::encode(owner_kp.verifying_key().to_bytes());
        let attacker_kp = SigningKey::generate(&mut OsRng);
        let identity_hex = synthetic_identity_hex(6);

        // Bundle SIGNED by attacker but PUBLISHED claiming owner's signing
        // key. The signature was produced for one Ed25519 key but verified
        // against another — must fail.
        let spk = build_signed_spk(&attacker_kp, 1, [42u8; 32], 1_000);
        let store = PreKeyStore::empty();
        let r = store
            .publish(&identity_hex, &owner_signing_hex, spk, vec![], 1_000)
            .await;
        match r {
            Err(PublishError::BadSignature(_)) => {}
            other => panic!("expected BadSignature, got {:?}", other),
        }
    }

    #[tokio::test]
    async fn signing_key_rotation_rejected_for_existing_identity() {
        // First publish binds an Ed25519 signing key to an X25519 identity.
        // A second publish for the same X25519 identity but a DIFFERENT
        // signing key must be rejected — the 1:1 binding is server-enforced
        // until an explicit rotation path lands (post-Alpha 2).
        let kp_a = SigningKey::generate(&mut OsRng);
        let kp_b = SigningKey::generate(&mut OsRng);
        let signing_a = hex::encode(kp_a.verifying_key().to_bytes());
        let signing_b = hex::encode(kp_b.verifying_key().to_bytes());
        let identity_hex = synthetic_identity_hex(7);

        let store = PreKeyStore::empty();
        let spk_a = build_signed_spk(&kp_a, 1, [1u8; 32], 100);
        store
            .publish(&identity_hex, &signing_a, spk_a, vec![], 100)
            .await
            .expect("first publish should succeed");

        let spk_b = build_signed_spk(&kp_b, 2, [2u8; 32], 200);
        let r = store
            .publish(&identity_hex, &signing_b, spk_b, vec![], 200)
            .await;
        match r {
            Err(PublishError::SigningKeyMismatch) => {}
            other => panic!("expected SigningKeyMismatch, got {:?}", other),
        }
    }

    #[tokio::test]
    async fn empty_opk_pool_returns_only_spk() {
        let signing_kp = SigningKey::generate(&mut OsRng);
        let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let identity_hex = synthetic_identity_hex(8);
        let spk = build_signed_spk(&signing_kp, 1, [1u8; 32], 0);

        let store = PreKeyStore::empty();
        store
            .publish(&identity_hex, &signing_hex, spk.clone(), vec![], 0)
            .await
            .unwrap();

        let bundle = store.consume_bundle(&identity_hex).await.unwrap();
        assert!(bundle.one_time_pre_key.is_none());
        assert_eq!(bundle.signed_pre_key.key_id, spk.key_id);
    }

    #[tokio::test]
    async fn rate_limit_enforced() {
        let store = PreKeyStore::empty();
        // 3 calls per 60-second window. First three pass, fourth is denied.
        let key = "rate-test-bucket";
        assert!(store.allow_call(key, 3, 60).await);
        assert!(store.allow_call(key, 3, 60).await);
        assert!(store.allow_call(key, 3, 60).await);
        assert!(!store.allow_call(key, 3, 60).await);
    }

    /// Build the canonical DELETE-OPK signing payload for a test. Mirrors
    /// `verify_opk_delete_signature`'s reconstruction order.
    fn build_delete_opk_signature(
        signing_kp: &SigningKey,
        identity_hex: &str,
        signing_hex: &str,
        key_id_hex: &str,
        signed_ts_ms: i64,
    ) -> String {
        let identity_bytes = hex::decode(identity_hex).unwrap();
        let signing_bytes = hex::decode(signing_hex).unwrap();
        let key_id_bytes = hex::decode(key_id_hex).unwrap();
        let mut payload = Vec::new();
        payload.extend_from_slice(OPK_DELETE_DOMAIN_LABEL.as_bytes());
        payload.extend_from_slice(&identity_bytes);
        payload.extend_from_slice(&signing_bytes);
        payload.extend_from_slice(&key_id_bytes);
        payload.extend_from_slice(&signed_ts_ms.to_be_bytes());
        let sig: Signature = signing_kp.sign(&payload);
        hex::encode(sig.to_bytes())
    }

    #[tokio::test]
    async fn delete_opk_with_valid_signature_removes_from_pool() {
        let signing_kp = SigningKey::generate(&mut OsRng);
        let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let identity_hex = synthetic_identity_hex(9);
        let spk = build_signed_spk(&signing_kp, 1, [9u8; 32], 0);
        let target = make_opk(42);
        let other = make_opk(7);

        let store = PreKeyStore::empty();
        store
            .publish(
                &identity_hex,
                &signing_hex,
                spk,
                vec![target.clone(), other.clone()],
                0,
            )
            .await
            .unwrap();

        let now_ms: i64 = 1_700_000_000_000;
        let sig_hex = build_delete_opk_signature(
            &signing_kp,
            &identity_hex,
            &signing_hex,
            &target.key_id_hex,
            now_ms,
        );

        store
            .delete_opk(
                &identity_hex,
                &target.key_id_hex,
                now_ms,
                &sig_hex,
                now_ms,
            )
            .await
            .expect("delete with valid sig should succeed");

        let status = store.status(&identity_hex, now_ms).await;
        assert_eq!(status.remaining_opks, 1, "only the targeted OPK should be removed");
    }

    #[tokio::test]
    async fn delete_opk_with_stale_timestamp_rejected() {
        let signing_kp = SigningKey::generate(&mut OsRng);
        let signing_hex = hex::encode(signing_kp.verifying_key().to_bytes());
        let identity_hex = synthetic_identity_hex(10);
        let spk = build_signed_spk(&signing_kp, 1, [9u8; 32], 0);
        let target = make_opk(42);
        let store = PreKeyStore::empty();
        store
            .publish(
                &identity_hex,
                &signing_hex,
                spk,
                vec![target.clone()],
                0,
            )
            .await
            .unwrap();

        // Sign with a timestamp 1 hour in the past — must be outside the
        // 5-minute tolerance window.
        let signed_ts: i64 = 1_700_000_000_000;
        let now_ms: i64 = signed_ts + 60 * 60 * 1000;
        let sig_hex = build_delete_opk_signature(
            &signing_kp,
            &identity_hex,
            &signing_hex,
            &target.key_id_hex,
            signed_ts,
        );

        let r = store
            .delete_opk(
                &identity_hex,
                &target.key_id_hex,
                signed_ts,
                &sig_hex,
                now_ms,
            )
            .await;
        match r {
            Err(DeleteError::TimestampOutOfWindow) => {}
            other => panic!("expected TimestampOutOfWindow, got {:?}", other),
        }
    }
}
