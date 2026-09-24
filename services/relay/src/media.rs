// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Encrypted media upload/download for voice messages (PR-M1r).
//!
//! New endpoints:
//!   POST /media/upload-chunk  — bounded body, bearer-session auth.
//!   GET  /media/chunk/{media_id}/{idx}  — bearer-session auth.
//!
//! The relay sees only opaque ciphertext keyed by `media_id`, a capability
//! token chosen by the client (≤64 chars, opaque to relay).
//!
//! ## Durability
//!
//! Chunks are persisted beneath `<state_dir>/media-v1` before an upload is
//! acknowledged. Each file contains only opaque ciphertext plus bounded routing
//! metadata, is written through the relay's same-directory atomic-write helper,
//! and is replayed when a fresh AppState starts after a process/container restart.
//!
//! ## Quotas (defaults; all are runtime-configurable)
//!   max_media_chunks = 1024
//!   max_media_bytes  = 4 MiB
//!   max_media_store_bytes = 256 MiB
//!   media_ttl        = 7 days
//!
//! Sweeper runs hourly and emits MEDIA_SWEEP per swept media_id.

use std::{
    collections::HashMap,
    fs,
    io,
    path::{Path as FsPath, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::{SystemTime, UNIX_EPOCH},
};

use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode},
    response::IntoResponse,
    Json,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::RwLock;

use crate::{
    atomic_write::{create_dir_all_durable, fsync_dir, write_atomic},
    rest_fallback::extract_bearer,
    state::AppState,
};

// ── Constants ─────────────────────────────────────────────────────────────────

/// Default POST /media/upload-chunk body cap. Used by `config::default()` and
/// as the env-var fallback in `config::from_env()`. The effective runtime cap
/// is `state.config.max_media_upload_body_bytes`, sourced from
/// `RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES` if set, otherwise this default. Both
/// the axum `DefaultBodyLimit` middleware (routes.rs) and the in-handler
/// defence-in-depth check below read from that same config field, so the env
/// var fully governs the cap.
pub(crate) const MAX_MEDIA_UPLOAD_BODY_BYTES: usize = 9_000;

/// Maximum number of chunks per media object.
pub const MAX_MEDIA_CHUNKS: u32 = 1_024;

/// Maximum cumulative ciphertext bytes per media_id (4 MiB).
pub const MAX_MEDIA_BYTES: u64 = 4 * 1_048_576;

/// Global on-disk media budget. Per-object bounds and TTL still apply. This is
/// intentionally half of the production container's 512 MiB memory ceiling:
/// the store is indexed in RAM, and the relay still needs headroom for queued
/// envelopes, connections, crypto, and the runtime itself.
pub const MAX_MEDIA_STORE_BYTES: u64 = 256 * 1_048_576;

/// Maximum length of a `media_id` string (chars).
pub const MAX_MEDIA_ID_LEN: usize = 64;

// ── Storage types ─────────────────────────────────────────────────────────────

/// One chunk row stored per `(media_id, idx)`.
#[derive(Clone)]
pub struct MediaChunk {
    /// Plaintext index declared by the uploader.
    pub idx: u32,
    /// Total chunk count for this media object (declared by uploader on first
    /// upload; every subsequent chunk for the same `media_id` must agree).
    pub total: u32,
    /// Raw ciphertext bytes (opaque to relay).
    pub ciphertext: Vec<u8>,
    /// SHA-256 of `ciphertext` — used for idempotency collision detection.
    pub ciphertext_sha256: [u8; 32],
    /// Wall-clock milliseconds when this chunk was first stored.
    pub created_at_ms: u64,
}

/// Per-`media_id` collection.
///
/// Invariants maintained by the handlers:
///  - All chunks share the same `total`.
///  - `chunks.len() <= total <= configured max_media_chunks`.
///  - `sum(chunks.ciphertext.len()) <= configured max_media_bytes`.
pub struct MediaEntry {
    /// The canonical `total` value for this media object, established by the
    /// first chunk uploaded. Every subsequent chunk must present the same value.
    pub total: u32,
    /// idx → chunk.
    pub chunks: HashMap<u32, MediaChunk>,
    /// `created_at_ms` of the latest committed chunk. TTL is measured from
    /// last activity so an upload that is still completing cannot be swept.
    pub last_activity_at_ms: u64,
}

const MEDIA_DIR_NAME: &str = "media-v1";
const MEDIA_RECORD_MAGIC: &[u8; 8] = b"PHMED001";
const MEDIA_RECORD_HEADER_BYTES: usize = 8 + 32 + 4 + 4 + 8 + 4 + 32;
// Charge at least one ordinary filesystem allocation unit per chunk. Counting
// only logical bytes lets an authenticated client turn a 256 MiB budget into
// millions of tiny files and high-cardinality RAM index entries.
const MEDIA_RECORD_BUDGET_FLOOR_BYTES: u64 = 4_096;

fn persisted_record_bytes(chunk: &MediaChunk) -> u64 {
    ((MEDIA_RECORD_HEADER_BYTES + chunk.ciphertext.len()) as u64)
        .max(MEDIA_RECORD_BUDGET_FLOOR_BYTES)
}

/// Restart-durable media store. The in-memory index is keyed by SHA-256(media_id)
/// and rebuilt from atomic ciphertext records at startup. The capability token
/// itself is never written to disk.
pub struct MediaStore {
    inner: RwLock<HashMap<String, MediaEntry>>,
    root: PathBuf,
    max_store_bytes: u64,
    total_bytes: AtomicU64,
}

impl MediaStore {
    pub fn new(
        state_dir: &FsPath,
        ttl_ms: u64,
        max_chunks: u32,
        max_media_bytes: u64,
        max_store_bytes: u64,
    ) -> io::Result<Self> {
        let root = state_dir.join(MEDIA_DIR_NAME);
        create_dir_all_durable(&root)?;
        let (inner, total_bytes) = load_media_records(
            &root,
            ttl_ms,
            max_chunks,
            max_media_bytes,
            max_store_bytes,
        )?;
        Ok(Self {
            inner: RwLock::new(inner),
            root,
            max_store_bytes,
            total_bytes: AtomicU64::new(total_bytes),
        })
    }

    fn storage_key(media_id: &str) -> String {
        sha256_hex(&sha256_bytes(media_id.as_bytes()))
    }

    fn has_global_capacity(&self, additional_bytes: u64) -> bool {
        self.total_bytes
            .load(Ordering::Acquire)
            .checked_add(additional_bytes)
            .is_some_and(|next| next <= self.max_store_bytes)
    }

    fn persist_chunk(&self, media_id: &str, chunk: &MediaChunk) -> io::Result<()> {
        let key_digest = sha256_bytes(media_id.as_bytes());
        let key = sha256_hex(&key_digest);
        let record = encode_media_record(key_digest, chunk);
        let idx = chunk.idx;
        let dir = self.root.join(key);
        create_dir_all_durable(&dir)?;
        write_atomic(&dir.join(format!("{idx:08}.chunk")), &record)
    }

    fn account_insert(&self, bytes: u64) {
        self.total_bytes.fetch_add(bytes, Ordering::AcqRel);
    }

    /// Sweep all `media_id` entries whose last committed chunk is older than
    /// `ttl_ms` milliseconds. Returns the number of entries removed.
    ///
    /// Called from the background sweeper in `main.rs` every hour.
    ///
    /// The read phase only identifies candidates. Under the same write lock
    /// used by upload, each candidate is re-checked and its directory is
    /// atomically renamed to an expiry quarantine before RAM publication is
    /// removed. Therefore an acknowledged concurrent upload cannot land in a
    /// directory that the sweeper deletes after releasing the lock.
    pub async fn sweep_expired(&self, ttl_ms: u64) -> usize {
        let now_ms = now_ms();

        // Phase 1: identify candidates without holding the exclusive lock.
        let candidates: Vec<String> = {
            let inner = self.inner.read().await;
            inner
                .iter()
                .filter_map(|(k, e)| {
                    let age = now_ms.saturating_sub(e.last_activity_at_ms);
                    if age >= ttl_ms {
                        Some(k.clone())
                    } else {
                        None
                    }
                })
                .collect()
        };

        if candidates.is_empty() {
            return 0;
        }

        // Phase 2: re-check and quarantine on disk while uploads are excluded.
        let mut quarantined = Vec::new();
        let mut removed_bytes = 0_u64;
        let mut inner = self.inner.write().await;
        for key in candidates {
            let Some(entry) = inner.get(&key) else {
                continue;
            };
            let age_ms = now_ms.saturating_sub(entry.last_activity_at_ms);
            if age_ms < ttl_ms {
                continue;
            }

            let root = self.root.clone();
            let key_for_move = key.clone();
            let quarantine = self.root.join(format!(".expired-{key}-{now_ms}"));
            let quarantine_for_move = quarantine.clone();
            let moved = tokio::task::spawn_blocking(move || {
                let source = root.join(&key_for_move);
                fs::rename(&source, &quarantine_for_move)?;
                fsync_dir(&root)
            })
            .await
            .map_err(|e| io::Error::other(format!("media sweep task failed: {e}")))
            .and_then(|result| result);
            if let Err(err) = moved {
                tracing::error!(media_key = %key.chars().take(8).collect::<String>(), error = %err, "MEDIA_SWEEP quarantine_failed");
                continue;
            }

            let entry = inner.remove(&key).expect("entry re-checked under write lock");
            let chunk_count = entry.chunks.len();
            let bytes = entry
                .chunks
                .values()
                .map(persisted_record_bytes)
                .sum::<u64>();
            removed_bytes += bytes;
            quarantined.push((key, quarantine, chunk_count, age_ms));
        }
        drop(inner);
        self.total_bytes.fetch_sub(removed_bytes, Ordering::AcqRel);

        for (key, quarantine, chunk_count, age_ms) in &quarantined {
            if let Err(err) = fs::remove_dir_all(quarantine) {
                if err.kind() != io::ErrorKind::NotFound {
                    tracing::error!(media_key = %key.chars().take(8).collect::<String>(), error = %err, "MEDIA_SWEEP disk_remove_failed");
                }
            } else if let Err(err) = fsync_dir(&self.root) {
                tracing::error!(error = %err, "MEDIA_SWEEP root_fsync_failed");
            }
            tracing::info!(
                "MEDIA_SWEEP expired media_key={} chunks={} age_ms={}",
                key.chars().take(8).collect::<String>(),
                chunk_count,
                age_ms,
            );
        }
        quarantined.len()
    }
}

fn encode_media_record(key_digest: [u8; 32], chunk: &MediaChunk) -> Vec<u8> {
    let mut out = Vec::with_capacity(MEDIA_RECORD_HEADER_BYTES + chunk.ciphertext.len());
    out.extend_from_slice(MEDIA_RECORD_MAGIC);
    out.extend_from_slice(&key_digest);
    out.extend_from_slice(&chunk.total.to_be_bytes());
    out.extend_from_slice(&chunk.idx.to_be_bytes());
    out.extend_from_slice(&chunk.created_at_ms.to_be_bytes());
    out.extend_from_slice(&(chunk.ciphertext.len() as u32).to_be_bytes());
    out.extend_from_slice(&chunk.ciphertext_sha256);
    out.extend_from_slice(&chunk.ciphertext);
    out
}

fn decode_media_record(bytes: &[u8]) -> io::Result<([u8; 32], MediaChunk)> {
    if bytes.len() < MEDIA_RECORD_HEADER_BYTES || &bytes[..8] != MEDIA_RECORD_MAGIC {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid media record header"));
    }
    let key_digest: [u8; 32] = bytes[8..40].try_into().unwrap();
    let total = u32::from_be_bytes(bytes[40..44].try_into().unwrap());
    let idx = u32::from_be_bytes(bytes[44..48].try_into().unwrap());
    let created_at_ms = u64::from_be_bytes(bytes[48..56].try_into().unwrap());
    let len = u32::from_be_bytes(bytes[56..60].try_into().unwrap()) as usize;
    let expected_sha: [u8; 32] = bytes[60..92].try_into().unwrap();
    if bytes.len() != MEDIA_RECORD_HEADER_BYTES + len {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "media record length mismatch"));
    }
    let ciphertext = bytes[MEDIA_RECORD_HEADER_BYTES..].to_vec();
    let actual_sha = sha256_bytes(&ciphertext);
    if actual_sha != expected_sha {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "media record sha256 mismatch"));
    }
    Ok((key_digest, MediaChunk {
        idx,
        total,
        ciphertext,
        ciphertext_sha256: actual_sha,
        created_at_ms,
    }))
}

fn load_media_records(
    root: &FsPath,
    ttl_ms: u64,
    max_chunks: u32,
    max_media_bytes: u64,
    max_store_bytes: u64,
) -> io::Result<(HashMap<String, MediaEntry>, u64)> {
    let mut dirs = fs::read_dir(root)?.collect::<Result<Vec<_>, _>>()?;
    dirs.sort_by_key(|entry| entry.file_name());
    let now = now_ms();
    let mut entries = HashMap::new();
    let mut total_store_bytes = 0_u64;

    for dir_entry in dirs {
        let key = dir_entry.file_name().to_string_lossy().to_string();
        let dir = dir_entry.path();
        if key.starts_with(".expired-") {
            let _ = fs::remove_dir_all(&dir);
            continue;
        }
        if !dir_entry.file_type()?.is_dir()
            || key.len() != 64
            || !key.bytes().all(|b| b.is_ascii_hexdigit())
        {
            continue;
        }

        let mut files = fs::read_dir(&dir)?.collect::<Result<Vec<_>, _>>()?;
        files.sort_by_key(|entry| entry.file_name());
        let mut candidate: Option<MediaEntry> = None;
        let mut invalid = false;

        for file_entry in files {
            let path = file_entry.path();
            let name = file_entry.file_name().to_string_lossy().to_string();
            if name.starts_with(".staging-") {
                let _ = fs::remove_file(&path);
                continue;
            }
            if !file_entry.file_type()?.is_file() || path.extension().and_then(|s| s.to_str()) != Some("chunk") {
                continue;
            }
            let parsed = fs::read(&path).and_then(|bytes| decode_media_record(&bytes));
            let (key_digest, chunk) = match parsed {
                Ok(value) => value,
                Err(err) => {
                    tracing::warn!(path = %path.display(), error = %err, "MEDIA_BOOT corrupt_record_removed");
                    let _ = fs::remove_file(&path);
                    continue;
                }
            };
            if sha256_hex(&key_digest) != key
                || name != format!("{:08}.chunk", chunk.idx)
                || chunk.total == 0
                || chunk.total > max_chunks
                || chunk.idx >= chunk.total
            {
                invalid = true;
                break;
            }
            let entry = candidate.get_or_insert_with(|| MediaEntry {
                total: chunk.total,
                chunks: HashMap::new(),
                last_activity_at_ms: chunk.created_at_ms,
            });
            if entry.total != chunk.total || entry.chunks.insert(chunk.idx, chunk.clone()).is_some() {
                invalid = true;
                break;
            }
            entry.last_activity_at_ms = entry.last_activity_at_ms.max(chunk.created_at_ms);
        }

        let Some(entry) = candidate else {
            let _ = fs::remove_dir_all(&dir);
            continue;
        };
        let ciphertext_bytes = entry
            .chunks
            .values()
            .map(|chunk| chunk.ciphertext.len() as u64)
            .sum::<u64>();
        let persisted_bytes = entry
            .chunks
            .values()
            .map(persisted_record_bytes)
            .sum::<u64>();
        let expired = now.saturating_sub(entry.last_activity_at_ms) >= ttl_ms;
        if invalid
            || entry.chunks.len() > entry.total as usize
            || ciphertext_bytes > max_media_bytes
            || total_store_bytes.saturating_add(persisted_bytes) > max_store_bytes
            || expired
        {
            tracing::warn!(
                media_key = %key.chars().take(8).collect::<String>(),
                invalid,
                expired,
                ciphertext_bytes,
                persisted_bytes,
                "MEDIA_BOOT entry_removed"
            );
            let _ = fs::remove_dir_all(&dir);
            continue;
        }
        total_store_bytes += persisted_bytes;
        entries.insert(key, entry);
    }
    fsync_dir(root)?;
    tracing::info!(entries = entries.len(), bytes = total_store_bytes, "MEDIA_BOOT replay_complete");
    Ok((entries, total_store_bytes))
}

// ── Shared helper ─────────────────────────────────────────────────────────────

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// PR-M2f — lowercase hex encoding for the v3 ETag header.
fn sha256_hex(digest: &[u8; 32]) -> String {
    let mut out = String::with_capacity(64);
    for b in digest {
        out.push_str(&format!("{:02x}", b));
    }
    out
}

fn sha256_bytes(data: &[u8]) -> [u8; 32] {
    let digest = Sha256::digest(data);
    digest.into()
}

fn is_valid_media_id(media_id: &str) -> bool {
    !media_id.is_empty()
        && media_id.len() <= MAX_MEDIA_ID_LEN
        && media_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

/// Extract and validate the bearer session token from headers.
/// Returns `None` if absent or expired, in which case the caller should
/// respond 401.
async fn validate_bearer(headers: &HeaderMap, state: &AppState) -> Option<String> {
    let token = extract_bearer(headers)?;
    state.rest_tokens.validate(token).await
}

// ── Request / response types ──────────────────────────────────────────────────

#[derive(Deserialize)]
pub struct UploadChunkRequest {
    pub media_id: String,
    pub idx: u32,
    pub total: u32,
    /// Base64-encoded encrypted chunk bytes.
    pub ciphertext_b64: String,
    // Note: `idempotency_key` was removed from the struct. If clients send
    // it, serde silently ignores it (no `deny_unknown_fields` on this type).
    // Keeping the field server-side was a latent log-leak surface because the
    // key embeds the full mediaId; natural key (media_id, idx) is sufficient.
}

#[derive(Serialize)]
struct UploadChunkResponse {
    status: &'static str,
    idx: u32,
}

// ── POST /media/upload-chunk ──────────────────────────────────────────────────

/// POST /media/upload-chunk
///
/// Idempotency: `(media_id, idx)` is the natural key. On retry with the same
/// ciphertext bytes (compared by sha256) → 200 `duplicate`. On retry with
/// different ciphertext → 409 `ciphertext_mismatch` (the stored chunk is NOT
/// overwritten).
///
/// Quotas enforced per `media_id`:
///  - `total > configured max_media_chunks` → 413 `too_many_chunks`
///  - cumulative ciphertext bytes > configured max_media_bytes → 413
///  - global durable bytes > configured max_media_store_bytes → 507
///  - raw body > configured max_media_upload_body_bytes → 413
///
/// True if the client sent `Prefer: return=minimal` (RFC 7240 §4.2).
///
/// PR-M2d.1: when present, successful upload responses are `204 No Content`
/// with all metadata moved to response headers. This removes the response
/// body from the upload roundtrip entirely — which is the binding cost on
/// Tele2 LTE where Layer B reliably drops response bodies > ~2400 bytes
/// (verified by Test #66.2). For backward compatibility, requests without
/// the header keep getting the legacy `201/200 + JSON` body.
fn wants_minimal(headers: &HeaderMap) -> bool {
    headers
        .get_all("prefer")
        .iter()
        .any(|v| {
            v.to_str()
                .map(|s| s.split(',').any(|t| t.trim().eq_ignore_ascii_case("return=minimal")))
                .unwrap_or(false)
        })
}

/// Build a 204 No Content response carrying chunk-status metadata in headers.
///
/// Headers:
///   X-Chunk-Stored: 1                — chunk is now stored on the relay
///   X-Chunk-Duplicate: 0|1           — 1 if this was a duplicate sha256 hit
///   X-Chunk-Idx: <idx>               — chunk index that was stored
///   Cache-Control: no-store, no-transform
fn minimal_upload_response(idx: u32, duplicate: bool) -> axum::response::Response {
    let mut resp = StatusCode::NO_CONTENT.into_response();
    let h = resp.headers_mut();
    h.insert("X-Chunk-Stored", HeaderValue::from_static("1"));
    h.insert(
        "X-Chunk-Duplicate",
        if duplicate { HeaderValue::from_static("1") } else { HeaderValue::from_static("0") },
    );
    // Safe to unwrap: u32 decimal is always valid ASCII.
    h.insert("X-Chunk-Idx", HeaderValue::from_str(&idx.to_string()).unwrap());
    h.insert(
        "Cache-Control",
        HeaderValue::from_static("no-store, no-transform"),
    );
    resp
}

pub async fn upload_chunk(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> impl IntoResponse {
    // PR-M2d.1: clients opt into 204 + headers via `Prefer: return=minimal`.
    // Captured up front because it affects both the duplicate and the
    // stored success paths below; failure paths always return JSON regardless.
    let minimal = wants_minimal(&headers);

    // 413 body cap first — before any other processing.
    let body_bytes = body.len();
    if body_bytes > state.config.max_media_upload_body_bytes {
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(serde_json::json!({ "error": "body_too_large" })),
        )
            .into_response();
    }

    // Auth.
    let _identity = match validate_bearer(&headers, &state).await {
        Some(id) => id,
        None => return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": "Authorization: Bearer <token> required" })),
        )
            .into_response(),
    };
    // Per-object and global storage quotas are enforced below. Media upload
    // does not use `/relay/send`'s per-identity request limiter: a long voice
    // legitimately consists of hundreds of small requests. The persistent
    // global byte budget is the hard resource bound until a media-specific
    // byte-rate policy is measured and introduced.

    // Parse body.
    let req: UploadChunkRequest = match serde_json::from_slice(&body) {
        Ok(r) => r,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "malformed JSON body" })),
            )
                .into_response()
        }
    };

    // MediaCrypto generates unpadded base64url. Keeping the relay boundary to
    // that ASCII alphabet also makes prefix logging and URL routing total.
    if !is_valid_media_id(&req.media_id) {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "media_id must be 1-64 base64url characters" })),
        )
            .into_response();
    }

    // total > 0 and within quota.
    if req.total == 0 || req.total > state.config.max_media_chunks {
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(serde_json::json!({ "error": "too_many_chunks" })),
        )
            .into_response();
    }

    // idx must be within declared total.
    if req.idx >= req.total {
        let prefix = &req.media_id[..req.media_id.len().min(8)];
        tracing::info!(
            event     = "MEDIA_RX",
            action    = "upload_reject",
            media_id  = %prefix,
            reason    = "idx_oor",
            body_bytes = body_bytes,
            "idx {} >= total {}", req.idx, req.total,
        );
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "idx must be less than total" })),
        )
            .into_response();
    }

    // Decode ciphertext.
    let ciphertext: Vec<u8> = match base64_decode(&req.ciphertext_b64) {
        Ok(b) => b,
        Err(_) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": "ciphertext_b64 is not valid base64" })),
            )
                .into_response()
        }
    };
    if ciphertext.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "ciphertext must not be empty" })),
        )
            .into_response();
    }
    let new_sha256 = sha256_bytes(&ciphertext);
    let created_at_ms = now_ms();
    let media_id_prefix = &req.media_id[..req.media_id.len().min(8)];

    // Serialize validation, durable commit, and index publication so two
    // concurrent uploads cannot both pass the global quota or race the same
    // natural key.
    let mut store = state.media_store.inner.write().await;
    let storage_key = MediaStore::storage_key(&req.media_id);

    // B1: enforce total consistency — every chunk for the same media_id must
    // declare the same total. An attacker sending chunk 0 with total=3 then
    // chunk 1 with total=10 would produce ambiguous reassembly on the receiver.
    if store.get(&storage_key).is_some_and(|entry| entry.total != req.total) {
        let entry_total = store.get(&storage_key).map(|entry| entry.total).unwrap_or(0);
        tracing::info!(
            "MEDIA_RX upload_reject media_id={} reason=total_mismatch req_total={} entry_total={}",
            &req.media_id[..req.media_id.len().min(8)],
            req.total,
            entry_total,
        );
        return (
            StatusCode::CONFLICT,
            Json(serde_json::json!({ "error": "total_mismatch" })),
        )
            .into_response();
    }

    // Check if an existing chunk is present at this idx.
    if let Some(existing) = store.get(&storage_key).and_then(|entry| entry.chunks.get(&req.idx)) {
        if existing.ciphertext_sha256 == new_sha256 {
            tracing::info!(
                event     = "MEDIA_RX",
                action    = "upload",
                media_id  = %media_id_prefix,
                idx       = req.idx,
                total     = req.total,
                body_bytes = body_bytes,
                status    = "duplicate",
                minimal   = minimal,
                "chunk duplicate",
            );
            if minimal {
                return minimal_upload_response(req.idx, true);
            }
            return (
                StatusCode::OK,
                Json(UploadChunkResponse { status: "duplicate", idx: req.idx }),
            )
                .into_response();
        } else {
            tracing::info!(
                event     = "MEDIA_RX",
                action    = "upload",
                media_id  = %media_id_prefix,
                idx       = req.idx,
                total     = req.total,
                body_bytes = body_bytes,
                status    = "conflict",
                "ciphertext mismatch for same (media_id, idx)",
            );
            return (
                StatusCode::CONFLICT,
                Json(serde_json::json!({ "error": "ciphertext_mismatch" })),
            )
                .into_response();
        }
    }

    // Quota: cumulative ciphertext bytes.
    let current_bytes: u64 = store
        .get(&storage_key)
        .map(|entry| entry.chunks.values().map(|c| c.ciphertext.len() as u64).sum())
        .unwrap_or(0);
    let new_total_bytes = current_bytes + ciphertext.len() as u64;
    if new_total_bytes > state.config.max_media_bytes {
        tracing::info!(
            event     = "MEDIA_RX",
            action    = "upload_reject",
            media_id  = %media_id_prefix,
            reason    = "media_quota_exceeded",
            body_bytes = body_bytes,
            "media quota exceeded: current={} + new={} > max={}",
            current_bytes, ciphertext.len(), state.config.max_media_bytes,
        );
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(serde_json::json!({ "error": "media_quota_exceeded" })),
        )
            .into_response();
    }

    let persisted_bytes = ((MEDIA_RECORD_HEADER_BYTES + ciphertext.len()) as u64)
        .max(MEDIA_RECORD_BUDGET_FLOOR_BYTES);
    if !state.media_store.has_global_capacity(persisted_bytes) {
        tracing::warn!(
            event = "MEDIA_RX",
            action = "upload_reject",
            media_id = %media_id_prefix,
            reason = "media_store_full",
            body_bytes = body_bytes,
            "global durable media quota exceeded",
        );
        return (
            StatusCode::INSUFFICIENT_STORAGE,
            Json(serde_json::json!({ "error": "media_store_full" })),
        )
            .into_response();
    }

    let chunk = MediaChunk {
        idx: req.idx,
        total: req.total,
        ciphertext,
        ciphertext_sha256: new_sha256,
        created_at_ms,
    };
    // This synchronous, bounded write deliberately has no cancellation point
    // while the store write lock is held. A detached blocking task could finish
    // after request cancellation and overwrite a later acknowledged retry.
    if let Err(err) = state.media_store.persist_chunk(&req.media_id, &chunk) {
        tracing::error!(
            event = "MEDIA_RX",
            action = "upload_reject",
            media_id = %media_id_prefix,
            reason = "media_persist_failed",
            error = %err,
            "durable chunk commit failed",
        );
        return (
            StatusCode::INSUFFICIENT_STORAGE,
            Json(serde_json::json!({ "error": "media_persist_failed" })),
        )
            .into_response();
    }

    let entry = store.entry(storage_key).or_insert_with(|| MediaEntry {
        total: req.total,
        chunks: HashMap::new(),
        last_activity_at_ms: created_at_ms,
    });
    entry.last_activity_at_ms = entry.last_activity_at_ms.max(created_at_ms);
    entry.chunks.insert(req.idx, chunk);
    state.media_store.account_insert(persisted_bytes);

    tracing::info!(
        event     = "MEDIA_RX",
        action    = "upload",
        media_id  = %media_id_prefix,
        idx       = req.idx,
        total     = req.total,
        body_bytes = body_bytes,
        status    = "stored",
        minimal   = minimal,
        "chunk stored",
    );

    if minimal {
        return minimal_upload_response(req.idx, false);
    }
    (
        StatusCode::CREATED,
        Json(UploadChunkResponse { status: "stored", idx: req.idx }),
    )
        .into_response()
}

// ── GET /media/chunk/{media_id}/{idx} ─────────────────────────────────────────

/// GET /media/chunk/{media_id}/{idx}
///
/// Returns the stored chunk verbatim. `total` is included in the response so
/// the client can verify its expected chunk count without a separate metadata
/// call.
///
/// Status codes:
///   200 — chunk found
///   401 — missing/expired bearer token
///   404 — (media_id, idx) not found
pub async fn download_chunk(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path((media_id, idx)): Path<(String, u32)>,
) -> impl IntoResponse {
    // Auth.
    if validate_bearer(&headers, &state).await.is_none() {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": "Authorization: Bearer <token> required" })),
        )
            .into_response();
    }

    if !is_valid_media_id(&media_id) {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "media_id must be 1-64 base64url characters" })),
        )
            .into_response();
    }

    let media_id_prefix = &media_id[..media_id.len().min(8)];
    let store = state.media_store.inner.read().await;
    let storage_key = MediaStore::storage_key(&media_id);

    let Some(entry) = store.get(&storage_key) else {
        tracing::info!(
            event    = "MEDIA_TX",
            action   = "download_miss",
            media_id = %media_id_prefix,
            idx      = idx,
            "chunk not found",
        );
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "not_found" })),
        )
            .into_response();
    };

    let Some(chunk) = entry.chunks.get(&idx) else {
        tracing::info!(
            event    = "MEDIA_TX",
            action   = "download_miss",
            media_id = %media_id_prefix,
            idx      = idx,
            "chunk not found",
        );
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "not_found" })),
        )
            .into_response();
    };

    let ciphertext_b64 = base64_encode(&chunk.ciphertext);
    let total = chunk.total;
    let bytes_out = chunk.ciphertext.len();

    tracing::info!(
        event    = "MEDIA_TX",
        action   = "download",
        media_id = %media_id_prefix,
        idx      = idx,
        total    = total,
        bytes    = bytes_out,
        "chunk served",
    );

    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ciphertext_b64": ciphertext_b64,
            "total": total,
        })),
    )
        .into_response()
}

// ── PR-M2f — Binary v3 endpoints (additive; v2 above stays as fallback) ──────
//
// Path:   /media/v3/{media_id}/{idx}?total=N
// Method: POST  — Content-Type: application/octet-stream, body = raw ciphertext
// Method: GET   — returns application/octet-stream, body = raw ciphertext
//
// Removes the ~33% JSON+Base64 inflation from the wire. With the same storage
// backend and the same idempotency contract as v2 (natural key (media_id, idx),
// dedup by sha256(ciphertext)). Clients learn this endpoint exists via the
// `media_capabilities.binary_v3=true` field in POST /auth/session.

#[derive(Deserialize)]
pub struct UploadV3Query {
    pub total: u32,
}

/// POST /media/v3/{media_id}/{idx}?total=N
///
/// Body = raw ciphertext bytes. Always responds 204 No Content + headers on
/// success (clients do not need to set `Prefer: return=minimal` — that header
/// only existed to keep v2 backward-compatible). Response headers mirror v2's
/// minimal path plus an `ETag` carrying the ciphertext sha256.
pub async fn upload_chunk_v3(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path((media_id, idx)): Path<(String, u32)>,
    Query(q): Query<UploadV3Query>,
    body: Bytes,
) -> impl IntoResponse {
    let total = q.total;

    // 413 body cap first.
    let body_bytes = body.len();
    if body_bytes > state.config.max_media_upload_body_bytes {
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(serde_json::json!({ "error": "body_too_large" })),
        )
            .into_response();
    }

    // Auth.
    if validate_bearer(&headers, &state).await.is_none() {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": "Authorization: Bearer <token> required" })),
        )
            .into_response();
    }

    if !is_valid_media_id(&media_id) {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "media_id must be 1-64 base64url characters" })),
        )
            .into_response();
    }

    if total == 0 || total > state.config.max_media_chunks {
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(serde_json::json!({ "error": "too_many_chunks" })),
        )
            .into_response();
    }

    if idx >= total {
        let prefix = &media_id[..media_id.len().min(8)];
        tracing::info!(
            event     = "MEDIA_V3",
            action    = "upload_reject",
            media_id  = %prefix,
            reason    = "idx_oor",
            body_bytes = body_bytes,
            "idx {} >= total {}", idx, total,
        );
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "idx must be less than total" })),
        )
            .into_response();
    }

    let ciphertext: Vec<u8> = body.to_vec();
    if ciphertext.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "ciphertext must not be empty" })),
        )
            .into_response();
    }
    let new_sha256 = sha256_bytes(&ciphertext);
    let created_at_ms = now_ms();
    let media_id_prefix = &media_id[..media_id.len().min(8)];

    let mut store = state.media_store.inner.write().await;
    let storage_key = MediaStore::storage_key(&media_id);

    // Total consistency.
    if store.get(&storage_key).is_some_and(|entry| entry.total != total) {
        let entry_total = store.get(&storage_key).map(|entry| entry.total).unwrap_or(0);
        tracing::info!(
            "MEDIA_V3 upload_reject media_id={} reason=total_mismatch req_total={} entry_total={}",
            media_id_prefix,
            total,
            entry_total,
        );
        return (
            StatusCode::CONFLICT,
            Json(serde_json::json!({ "error": "total_mismatch" })),
        )
            .into_response();
    }

    // Idempotency: existing chunk at idx?
    if let Some(existing) = store.get(&storage_key).and_then(|entry| entry.chunks.get(&idx)) {
        if existing.ciphertext_sha256 == new_sha256 {
            tracing::info!(
                event     = "MEDIA_V3",
                action    = "upload",
                media_id  = %media_id_prefix,
                idx       = idx,
                total     = total,
                body_bytes = body_bytes,
                status    = "duplicate",
                "chunk duplicate",
            );
            return minimal_upload_response_v3(idx, true, &sha256_hex(&new_sha256));
        } else {
            tracing::info!(
                event     = "MEDIA_V3",
                action    = "upload",
                media_id  = %media_id_prefix,
                idx       = idx,
                total     = total,
                body_bytes = body_bytes,
                status    = "conflict",
                "ciphertext mismatch for same (media_id, idx)",
            );
            return (
                StatusCode::CONFLICT,
                Json(serde_json::json!({ "error": "ciphertext_mismatch" })),
            )
                .into_response();
        }
    }

    // Quota.
    let current_bytes: u64 = store
        .get(&storage_key)
        .map(|entry| entry.chunks.values().map(|c| c.ciphertext.len() as u64).sum())
        .unwrap_or(0);
    let new_total_bytes = current_bytes + ciphertext.len() as u64;
    if new_total_bytes > state.config.max_media_bytes {
        tracing::info!(
            event     = "MEDIA_V3",
            action    = "upload_reject",
            media_id  = %media_id_prefix,
            reason    = "media_quota_exceeded",
            body_bytes = body_bytes,
            "media quota exceeded: current={} + new={} > max={}",
            current_bytes, ciphertext.len(), state.config.max_media_bytes,
        );
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(serde_json::json!({ "error": "media_quota_exceeded" })),
        )
            .into_response();
    }

    let persisted_bytes = ((MEDIA_RECORD_HEADER_BYTES + ciphertext.len()) as u64)
        .max(MEDIA_RECORD_BUDGET_FLOOR_BYTES);
    if !state.media_store.has_global_capacity(persisted_bytes) {
        tracing::warn!(
            event = "MEDIA_V3",
            action = "upload_reject",
            media_id = %media_id_prefix,
            reason = "media_store_full",
            body_bytes = body_bytes,
            "global durable media quota exceeded",
        );
        return (
            StatusCode::INSUFFICIENT_STORAGE,
            Json(serde_json::json!({ "error": "media_store_full" })),
        )
            .into_response();
    }

    let chunk = MediaChunk {
        idx,
        total,
        ciphertext,
        ciphertext_sha256: new_sha256,
        created_at_ms,
    };
    // Keep durable commit and RAM publication in one cancellation-free critical
    // section; see the equivalent v2 path above.
    if let Err(err) = state.media_store.persist_chunk(&media_id, &chunk) {
        tracing::error!(
            event = "MEDIA_V3",
            action = "upload_reject",
            media_id = %media_id_prefix,
            reason = "media_persist_failed",
            error = %err,
            "durable chunk commit failed",
        );
        return (
            StatusCode::INSUFFICIENT_STORAGE,
            Json(serde_json::json!({ "error": "media_persist_failed" })),
        )
            .into_response();
    }

    let entry = store.entry(storage_key).or_insert_with(|| MediaEntry {
        total,
        chunks: HashMap::new(),
        last_activity_at_ms: created_at_ms,
    });
    entry.last_activity_at_ms = entry.last_activity_at_ms.max(created_at_ms);
    entry.chunks.insert(idx, chunk);
    state.media_store.account_insert(persisted_bytes);

    tracing::info!(
        event     = "MEDIA_V3",
        action    = "upload",
        media_id  = %media_id_prefix,
        idx       = idx,
        total     = total,
        body_bytes = body_bytes,
        status    = "stored",
        "chunk stored",
    );

    minimal_upload_response_v3(idx, false, &sha256_hex(&new_sha256))
}

/// 204 No Content response for v3 successful upload. Mirrors v2's
/// `minimal_upload_response` plus an `ETag` carrying the ciphertext sha256
/// (hex-encoded, double-quoted per RFC 7232).
fn minimal_upload_response_v3(idx: u32, duplicate: bool, sha256_hex: &str) -> axum::response::Response {
    let mut resp = StatusCode::NO_CONTENT.into_response();
    let h = resp.headers_mut();
    h.insert("X-Chunk-Stored", HeaderValue::from_static("1"));
    h.insert(
        "X-Chunk-Duplicate",
        if duplicate { HeaderValue::from_static("1") } else { HeaderValue::from_static("0") },
    );
    h.insert("X-Chunk-Idx", HeaderValue::from_str(&idx.to_string()).unwrap());
    if let Ok(v) = HeaderValue::from_str(&format!("\"{}\"", sha256_hex)) {
        h.insert("ETag", v);
    }
    h.insert(
        "Cache-Control",
        HeaderValue::from_static("no-store, no-transform"),
    );
    resp
}

/// GET /media/v3/{media_id}/{idx}
///
/// Returns the stored chunk as `application/octet-stream`. ETag carries the
/// ciphertext sha256 so the client can sanity-check transport integrity
/// before crypto verification.
pub async fn download_chunk_v3(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path((media_id, idx)): Path<(String, u32)>,
) -> impl IntoResponse {
    if validate_bearer(&headers, &state).await.is_none() {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({ "error": "Authorization: Bearer <token> required" })),
        )
            .into_response();
    }

    if !is_valid_media_id(&media_id) {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "media_id must be 1-64 base64url characters" })),
        )
            .into_response();
    }

    let media_id_prefix = &media_id[..media_id.len().min(8)];
    let store = state.media_store.inner.read().await;
    let storage_key = MediaStore::storage_key(&media_id);

    let Some(entry) = store.get(&storage_key) else {
        tracing::info!(
            event    = "MEDIA_V3",
            action   = "download_miss",
            media_id = %media_id_prefix,
            idx      = idx,
            "chunk not found",
        );
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "not_found" })),
        )
            .into_response();
    };

    let Some(chunk) = entry.chunks.get(&idx) else {
        tracing::info!(
            event    = "MEDIA_V3",
            action   = "download_miss",
            media_id = %media_id_prefix,
            idx      = idx,
            "chunk not found",
        );
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "not_found" })),
        )
            .into_response();
    };

    let bytes_out = chunk.ciphertext.len();
    let body: Vec<u8> = chunk.ciphertext.clone();
    let etag = format!("\"{}\"", sha256_hex(&chunk.ciphertext_sha256));
    let total = chunk.total;

    tracing::info!(
        event    = "MEDIA_V3",
        action   = "download",
        media_id = %media_id_prefix,
        idx      = idx,
        total    = total,
        bytes    = bytes_out,
        "chunk served",
    );

    let mut resp = (StatusCode::OK, body).into_response();
    let h = resp.headers_mut();
    h.insert(
        axum::http::header::CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    if let Ok(v) = HeaderValue::from_str(&etag) {
        h.insert(axum::http::header::ETAG, v);
    }
    if let Ok(v) = HeaderValue::from_str(&total.to_string()) {
        h.insert("X-Chunk-Total", v);
    }
    h.insert(
        axum::http::header::CACHE_CONTROL,
        HeaderValue::from_static("no-store, no-transform"),
    );
    resp
}

// ── Base64 helpers (no external dep beyond what sha2 transitively brings) ─────
//
// The relay already uses hex; base64 is new but only standard-alphabet
// (RFC 4648 §4) decoding is required here. We add a minimal wrapper that
// delegates to the `base64` crate if present, or panics clearly if not.
// The workspace Cargo.toml already pulls in `base64` transitively through
// reqwest; we reference it via the crate name directly.

fn base64_decode(s: &str) -> Result<Vec<u8>, ()> {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD
        .decode(s)
        .map_err(|_| ())
}

fn base64_encode(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

#[cfg(test)]
mod durability_tests {
    use super::*;

    #[test]
    fn default_store_budget_leaves_half_the_container_limit_for_runtime_state() {
        assert_eq!(MAX_MEDIA_STORE_BYTES, 256 * 1_048_576);
        assert!(MAX_MEDIA_STORE_BYTES <= (512 * 1_048_576) / 2);
    }

    fn chunk(idx: u32, total: u32, created_at_ms: u64, value: u8) -> MediaChunk {
        let ciphertext = vec![value; 32];
        MediaChunk {
            idx,
            total,
            ciphertext_sha256: sha256_bytes(&ciphertext),
            ciphertext,
            created_at_ms,
        }
    }

    #[tokio::test]
    async fn recent_chunk_refreshes_ttl_and_prevents_whole_object_sweep() {
        let dir = tempfile::tempdir().unwrap();
        let store = MediaStore::new(dir.path(), 60_000, 8, 1_024, 4_096).unwrap();
        let media_id = "ttl-race";
        let key = MediaStore::storage_key(media_id);
        let old = chunk(0, 2, now_ms().saturating_sub(120_000), 1);
        let recent = chunk(1, 2, now_ms(), 2);
        let persisted_bytes = persisted_record_bytes(&old) + persisted_record_bytes(&recent);
        store.persist_chunk(media_id, &old).unwrap();
        store.persist_chunk(media_id, &recent).unwrap();
        store.inner.write().await.insert(
            key.clone(),
            MediaEntry {
                total: 2,
                chunks: HashMap::from([(0, old), (1, recent.clone())]),
                last_activity_at_ms: recent.created_at_ms,
            },
        );
        store.account_insert(persisted_bytes);

        assert_eq!(store.sweep_expired(60_000).await, 0);
        assert!(store.inner.read().await.contains_key(&key));
        assert!(store.root.join(key).join("00000001.chunk").is_file());
    }

    #[tokio::test]
    async fn expired_entry_is_quarantined_before_ram_publication_is_removed() {
        let dir = tempfile::tempdir().unwrap();
        let store = MediaStore::new(dir.path(), 60_000, 8, 1_024, 4_096).unwrap();
        let media_id = "expired";
        let key = MediaStore::storage_key(media_id);
        let old = chunk(0, 1, now_ms().saturating_sub(120_000), 3);
        store.persist_chunk(media_id, &old).unwrap();
        store.inner.write().await.insert(
            key.clone(),
            MediaEntry {
                total: 1,
                chunks: HashMap::from([(0, old.clone())]),
                last_activity_at_ms: old.created_at_ms,
            },
        );
        store.account_insert(persisted_record_bytes(&old));

        assert_eq!(store.sweep_expired(60_000).await, 1);
        assert!(!store.inner.read().await.contains_key(&key));
        assert!(!store.root.join(key).exists());
        assert_eq!(store.total_bytes.load(Ordering::Acquire), 0);
    }

    #[test]
    fn startup_removes_a_crash_left_expiry_quarantine() {
        let dir = tempfile::tempdir().unwrap();
        let media_root = dir.path().join(MEDIA_DIR_NAME);
        create_dir_all_durable(&media_root).unwrap();
        let quarantine = media_root.join(".expired-deadbeef-1");
        create_dir_all_durable(&quarantine).unwrap();
        fs::write(quarantine.join("orphan"), b"ciphertext").unwrap();

        MediaStore::new(dir.path(), 60_000, 8, 1_024, 4_096).unwrap();

        assert!(!quarantine.exists());
    }

    #[tokio::test]
    async fn startup_per_object_limit_counts_ciphertext_not_record_headers() {
        let dir = tempfile::tempdir().unwrap();
        let media_id = "exact-object-limit";
        let chunk = chunk(0, 1, now_ms(), 7);
        assert_eq!(chunk.ciphertext.len(), 32);
        let first = MediaStore::new(dir.path(), 60_000, 8, 32, 4_096).unwrap();
        first.persist_chunk(media_id, &chunk).unwrap();
        drop(first);

        let replayed = MediaStore::new(dir.path(), 60_000, 8, 32, 4_096).unwrap();

        let key = MediaStore::storage_key(media_id);
        assert!(replayed.inner.read().await.contains_key(&key));
        assert_eq!(
            replayed.total_bytes.load(Ordering::Acquire),
            persisted_record_bytes(&chunk),
        );
    }
}
