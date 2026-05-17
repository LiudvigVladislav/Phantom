// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Platform-independent contract for the relay's media chunk endpoints
 * (PR-M1r). Used by the voice send path (PR-M1w) to push and pull
 * encrypted voice note chunks.
 *
 * Each chunk is an AEAD ciphertext (XChaCha20-Poly1305-IETF) produced
 * by MediaCrypto. The transport layer is cipher-agnostic — it carries
 * opaque bytes and maps relay HTTP status codes to typed results.
 *
 * Token lifecycle: callers hold a bearer token obtained via the existing
 * auth-session flow. On [MediaAuthException] callers refresh the token
 * and retry. This transport does NOT own token caching or refresh.
 *
 * Retry semantics: upload is idempotent — re-uploading the same chunk
 * (same mediaId + idx + ciphertext) returns [UploadStatus.DUPLICATE].
 * Callers MAY retry on network errors (5xx / IOException). A 409 response
 * is NOT retryable without intervention (content mismatch or total mismatch).
 */
interface MediaUploadTransport {

    /**
     * Uploads one ciphertext chunk to `POST /media/upload-chunk`.
     *
     * The ciphertext MUST have been pre-size-checked: callers verify
     * that `base64EnvelopeSize(ciphertext) <= CLIENT_MAX_CHUNK_BODY_BYTES`
     * before calling this function. The implementation performs the same
     * check and throws [IllegalArgumentException] if it fails.
     *
     * @param token      Bearer session token. Passed in `Authorization: Bearer <token>`.
     *                   Callers refresh externally on [MediaAuthException] and retry
     *                   the same call with the new token (mirrors RestFallbackOrchestrator).
     * @param mediaId    Unique media identifier (base64url, 32 random bytes).
     * @param idx        0-based chunk index.
     * @param total      Total chunk count for this media item.
     * @param ciphertext Raw ciphertext bytes (AEAD output including tag).
     * @return [Result.success] with [UploadStatus.STORED] (first upload) or
     *   [UploadStatus.DUPLICATE] (idempotent re-upload).
     * @return [Result.failure] with [MediaConflictException], [MediaQuotaException],
     *   [MediaAuthException], or [IllegalArgumentException] on error responses.
     */
    suspend fun uploadChunk(
        token: String,
        mediaId: String,
        idx: Int,
        total: Int,
        ciphertext: ByteArray,
    ): Result<UploadStatus>

    /**
     * Downloads one ciphertext chunk from `GET /media/chunk/{mediaId}/{idx}`.
     *
     * @param token      Bearer session token. See [uploadChunk] for refresh semantics.
     * @return [Result.success] with [DownloadResult] containing the chunk
     *   ciphertext and the relay's declared total chunk count.
     * @return [Result.failure] with [NotFoundException], [MediaAuthException],
     *   or [MediaTransportException] on error.
     */
    suspend fun downloadChunk(
        token: String,
        mediaId: String,
        idx: Int,
    ): Result<DownloadResult>

    /** Outcome of a successful [uploadChunk] call. */
    enum class UploadStatus {
        /** The relay stored the chunk for the first time (HTTP 201). */
        STORED,
        /** The relay already has an identical chunk (HTTP 200 idempotent re-upload). */
        DUPLICATE,
    }

    /** Payload returned by a successful [downloadChunk] call. */
    data class DownloadResult(
        /** Raw ciphertext bytes as stored by the relay. */
        val ciphertext: ByteArray,
        /** Total chunk count for this media item, as declared by the relay. */
        val total: Int,
    )
}

// ── Domain exceptions ──────────────────────────────────────────────────────────

/** Relay returned 409: ciphertext or total mismatch on an idempotent re-upload. */
class MediaConflictException(val reason: String) : Exception("Media chunk conflict: $reason")

/** Relay returned 413: body too large, too many chunks, or media quota exceeded. */
class MediaQuotaException(val reason: String) : Exception("Media quota: $reason")

/** Relay returned 401: the bearer token is missing or expired. */
object MediaAuthException : Exception("Media auth token invalid or expired")

/** Relay returned 404: the requested chunk does not exist. */
object NotFoundException : Exception("Media chunk not found")

/** Network or unexpected relay error that is safe to retry with backoff. */
class MediaTransportException(message: String) : Exception(message)
