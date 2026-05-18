// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

//! Encrypted media upload/download for voice messages (PR-M1r).
//!
//! New endpoints:
//!   POST /media/upload-chunk  — body cap 3072 bytes, bearer-session auth.
//!   GET  /media/chunk/{media_id}/{idx}  — bearer-session auth.
//!
//! The relay sees only opaque ciphertext keyed by `media_id`, a capability
//! token chosen by the client (≤64 chars, opaque to relay).
//!
//! ## Durability — IMPORTANT
//!
//! Media chunks are retained **in-memory only**, up to 7 days while the
//! relay process is alive. Relay restart drops all uploaded media chunks.
//! Persistent media storage (SQLite/Sled/disk) is deferred to M1r.1 / M2.
//!
//! This is acceptable for Alpha because the existing envelope store has
//! the same semantics. Production callers must NOT assume 7-day durable
//! store-and-forward.
//!
//! ## Quotas (per-media)
//!   max_media_chunks = 256
//!   max_media_bytes  = 1 MiB
//!   media_ttl        = 7 days (logical expiry; not durable)
//!
//! Sweeper runs hourly and emits MEDIA_SWEEP per swept media_id.

use std::{
    collections::HashMap,
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use axum::{
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    Json,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::RwLock;

use crate::{rest_fallback::extract_bearer, state::AppState};

// ── Constants ─────────────────────────────────────────────────────────────────

/// Default POST /media/upload-chunk body cap. Used by `config::default()` and
/// as the env-var fallback in `config::from_env()`. The effective runtime cap
/// is `state.config.max_media_upload_body_bytes`, sourced from
/// `RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES` if set, otherwise this default. Both
/// the axum `DefaultBodyLimit` middleware (routes.rs) and the in-handler
/// defence-in-depth check below read from that same config field, so the env
/// var fully governs the cap.
pub(crate) const MAX_MEDIA_UPLOAD_BODY_BYTES: usize = 3_072;

/// Maximum number of chunks per media object.
pub const MAX_MEDIA_CHUNKS: u32 = 256;

/// Maximum cumulative ciphertext bytes per media_id (1 MiB).
pub const MAX_MEDIA_BYTES: u64 = 1_048_576;

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
///  - `chunks.len() <= total <= MAX_MEDIA_CHUNKS`.
///  - `sum(chunks.ciphertext.len()) <= MAX_MEDIA_BYTES`.
pub struct MediaEntry {
    /// The canonical `total` value for this media object, established by the
    /// first chunk uploaded. Every subsequent chunk must present the same value.
    pub total: u32,
    /// idx → chunk.
    pub chunks: HashMap<u32, MediaChunk>,
    /// `created_at_ms` of the earliest chunk — used by the sweeper to age-off
    /// the entire entry when the whole object is older than `media_ttl_ms`.
    pub earliest_created_at_ms: u64,
}

/// In-memory media chunk store.
///
/// Keyed by `media_id` (opaque string). Shared state; operations take a
/// short-lived write lock only for the duration of the mutation.
#[derive(Default)]
pub struct MediaStore {
    inner: RwLock<HashMap<String, MediaEntry>>,
}

impl MediaStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Sweep all `media_id` entries whose `earliest_created_at_ms` is older
    /// than `ttl_ms` milliseconds. Returns the number of entries removed.
    ///
    /// Called from the background sweeper in `main.rs` every hour.
    ///
    /// N1: holds a read lock only during the scan phase, then upgrades to a
    /// write lock solely for the removals. This avoids blocking all upload/
    /// download requests for the duration of the (potentially large) `retain`
    /// iteration. The window between read-unlock and write-lock may let a new
    /// chunk arrive for a key we are about to expire; the entry's
    /// `earliest_created_at_ms` will then be stale but the remove will still
    /// execute. That is intentional: an entry that was old enough to expire
    /// during the scan phase should be removed even if a chunk just arrived —
    /// the client must re-upload after the sweep window closes.
    pub async fn sweep_expired(&self, ttl_ms: u64) -> usize {
        let now_ms = now_ms();

        // Phase 1: read lock — collect (key, chunk_count, age_ms) for expired entries.
        let expired: Vec<(String, usize, u64)> = {
            let inner = self.inner.read().await;
            inner
                .iter()
                .filter_map(|(k, e)| {
                    let age = now_ms.saturating_sub(e.earliest_created_at_ms);
                    if age >= ttl_ms {
                        Some((k.clone(), e.chunks.len(), age))
                    } else {
                        None
                    }
                })
                .collect()
        };

        if expired.is_empty() {
            return 0;
        }

        // Phase 2: write lock — remove the collected keys.
        let mut inner = self.inner.write().await;
        for (key, chunk_count, age_ms) in &expired {
            inner.remove(key);
            // Log first 8 chars of media_id only — never full id (capability token).
            tracing::info!(
                "MEDIA_SWEEP expired media_id={} chunks={} age_ms={}",
                key.chars().take(8).collect::<String>(),
                chunk_count,
                age_ms,
            );
        }
        expired.len()
    }
}

// ── Shared helper ─────────────────────────────────────────────────────────────

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn sha256_bytes(data: &[u8]) -> [u8; 32] {
    let digest = Sha256::digest(data);
    digest.into()
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
///  - `total > 256` → 413 `too_many_chunks`
///  - cumulative ciphertext bytes > 1 MiB → 413 `media_quota_exceeded`
///  - raw body > 3072 bytes → 413 `body_too_large`
pub async fn upload_chunk(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> impl IntoResponse {
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
    // TODO(M1r.1): apply per-identity upload rate limit + global storage cap.
    // Today a single authenticated client can fill the in-memory store with
    // distinct media_ids (each up to 1 MiB), bounded only by session TTL.

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

    // media_id length cap.
    if req.media_id.is_empty() || req.media_id.len() > MAX_MEDIA_ID_LEN {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "media_id must be 1–64 chars" })),
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
    let new_sha256 = sha256_bytes(&ciphertext);
    let created_at_ms = now_ms();
    let media_id_prefix = &req.media_id[..req.media_id.len().min(8)];

    // Lock and apply.
    let mut store = state.media_store.inner.write().await;

    let entry = store.entry(req.media_id.clone()).or_insert_with(|| MediaEntry {
        total: req.total,
        chunks: HashMap::new(),
        earliest_created_at_ms: created_at_ms,
    });

    // B1: enforce total consistency — every chunk for the same media_id must
    // declare the same total. An attacker sending chunk 0 with total=3 then
    // chunk 1 with total=10 would produce ambiguous reassembly on the receiver.
    if entry.total != req.total {
        tracing::info!(
            "MEDIA_RX upload_reject media_id={} reason=total_mismatch req_total={} entry_total={}",
            &req.media_id[..req.media_id.len().min(8)],
            req.total,
            entry.total,
        );
        return (
            StatusCode::CONFLICT,
            Json(serde_json::json!({ "error": "total_mismatch" })),
        )
            .into_response();
    }

    // Check if an existing chunk is present at this idx.
    if let Some(existing) = entry.chunks.get(&req.idx) {
        if existing.ciphertext_sha256 == new_sha256 {
            tracing::info!(
                event     = "MEDIA_RX",
                action    = "upload",
                media_id  = %media_id_prefix,
                idx       = req.idx,
                total     = req.total,
                body_bytes = body_bytes,
                status    = "duplicate",
                "chunk duplicate",
            );
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
    let current_bytes: u64 = entry.chunks.values().map(|c| c.ciphertext.len() as u64).sum();
    let new_total_bytes = current_bytes + ciphertext.len() as u64;
    if new_total_bytes > state.config.max_media_bytes {
        tracing::info!(
            event     = "MEDIA_RX",
            action    = "upload_reject",
            media_id  = %media_id_prefix,
            reason    = "media_quota_exceeded",
            body_bytes = body_bytes,
            "media quota exceeded: current={} + new={} > max={}",
            current_bytes, ciphertext.len(), MAX_MEDIA_BYTES,
        );
        return (
            StatusCode::PAYLOAD_TOO_LARGE,
            Json(serde_json::json!({ "error": "media_quota_exceeded" })),
        )
            .into_response();
    }

    // Store the chunk.
    entry.chunks.insert(
        req.idx,
        MediaChunk {
            idx: req.idx,
            total: req.total,
            ciphertext,
            ciphertext_sha256: new_sha256,
            created_at_ms,
        },
    );

    tracing::info!(
        event     = "MEDIA_RX",
        action    = "upload",
        media_id  = %media_id_prefix,
        idx       = req.idx,
        total     = req.total,
        body_bytes = body_bytes,
        status    = "stored",
        "chunk stored",
    );

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

    let media_id_prefix = &media_id[..media_id.len().min(8)];
    let store = state.media_store.inner.read().await;

    let Some(entry) = store.get(&media_id) else {
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
