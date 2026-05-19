// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.ceil
import kotlin.math.max
import phantom.core.crypto.MediaCrypto
import phantom.core.transport.MediaAuthException
import phantom.core.transport.MediaAuthTokenProvider
import phantom.core.transport.MediaConflictException
import phantom.core.transport.MediaQuotaException
import phantom.core.transport.MediaTransportException
import phantom.core.transport.MediaUploadTransport

/**
 * Encrypts audio, splits it into chunks, and uploads each chunk to the relay's
 * /media/upload-chunk endpoint (PR-M1w).
 *
 * This class is a pure upload helper. It never touches MessageRepository,
 * the Double Ratchet, or the in-progress guard — those remain in
 * [DefaultMessagingService] which owns the full send lifecycle.
 *
 * Token refresh uses CAS semantics via [MediaAuthTokenProvider.acquireToken]:
 * pass the stale token on 401 so concurrent callers from the poll loop
 * don't each force a separate refresh (D1c.1 lesson).
 *
 * Network retry: 5 attempts per chunk, backoff [1 s, 3 s, 8 s, 20 s, 60 s].
 * Auth refresh: max 3 cycles per chunk; on exhaustion returns failure.
 */
@OptIn(ExperimentalEncodingApi::class)
class VoiceV2Sender(
    private val mediaCrypto: MediaCrypto,
    private val mediaTransport: MediaUploadTransport,
    private val tokenProvider: MediaAuthTokenProvider,
    private val log: (String) -> Unit,
    /**
     * PR-M2f.1 (debug probe) — runtime chunk size override.
     *
     * Production-default returns [MediaChunker.TARGET_RAW_CHUNK_BYTES]
     * (3200 after PR-M2f.2; was 1700).
     * Android debug builds wire a SharedPreferences-backed provider so
     * Settings → Diagnostics can select 1700 / 2200 / 2300 / 2400 / 2600
     * across consecutive voice sends without rebuilding the APK. The provider
     * is invoked exactly once per `uploadVoice` call (when the encrypted
     * blob is split) — switching the selector mid-upload is harmless because
     * the new value applies only to the NEXT voice.
     */
    private val chunkSizeProvider: () -> Int = { MediaChunker.TARGET_RAW_CHUNK_BYTES },
) {

    /**
     * Encrypt [audioBytes] then upload all ciphertext chunks.
     *
     * @return [Result.success] carrying the manifest once all chunks are 201/200.
     *         [Result.failure] on terminal error (auth exhausted, quota, conflict,
     *         repeated network failure). Callers mark the local row FAILED on failure.
     */
    suspend fun uploadVoice(
        audioBytes: ByteArray,
        durationMs: Long,
        mime: String,
        onSplit: ((total: Int) -> Unit)? = null,
        onChunkUploaded: ((sent: Int, total: Int) -> Unit)? = null,
        onEarlyManifest: (suspend (manifest: VoiceManifestV2) -> Unit)? = null,
    ): Result<VoiceManifestV2> = runCatching {
        // Step 1: encrypt
        val enc = mediaCrypto.encryptVoice(audioBytes)
        log(
            "MEDIA_TX encrypt_ok mediaId=${enc.mediaId.take(8)} " +
                "ciphertextBytes=${enc.ciphertext.size} plaintextBytes=${audioBytes.size}",
        )

        // Step 2: split into upload-safe chunks
        // PR-M2f.1 — chunk size now comes from a provider lambda so the debug
        // selector in Settings can probe 1700 / 2200 / 2300 / 2400 / 2600 on
        // the same APK. Clamped to a sane range as defence-in-depth against a
        // SharedPreferences read returning a stale or out-of-range value.
        val selectedChunkSize = chunkSizeProvider()
            .coerceIn(MIN_PROBE_CHUNK_BYTES, MAX_PROBE_CHUNK_BYTES)
        log(
            "MEDIA_TX chunk_size_selected mediaId=${enc.mediaId.take(8)} " +
                "bytes=$selectedChunkSize source=provider",
        )
        val chunks = MediaChunker.chunk(enc.ciphertext, chunkSize = selectedChunkSize)
        val total = chunks.size
        log(
            "MEDIA_TX chunk_split mediaId=${enc.mediaId.take(8)} " +
                "chunkCount=$total chunkSizeBytes=$selectedChunkSize " +
                "totalCiphertextBytes=${enc.ciphertext.size}",
        )
        onSplit?.invoke(total)

        // PR-M2e (2026-05-19): build the manifest up front so we can hand it
        // to the early-manifest callback after the first few chunks. The
        // receiver starts downloading while the sender is still uploading
        // the tail; 404s during the dynamic fresh-task window are treated
        // as `chunk_not_ready_yet` and retried, not as `media_chunks_gone`.
        val manifest = VoiceManifestV2(
            type               = VoiceManifestV2.TYPE,
            mediaId            = enc.mediaId,
            mediaKey           = Base64.encode(enc.mediaKey),
            nonce              = Base64.encode(enc.nonce),
            alg                = VoiceManifestV2.ALG,
            durationMs         = durationMs,
            mime               = mime,
            chunkCount         = total,
            encryptedSizeBytes = enc.ciphertext.size.toLong(),
            plainSizeBytes     = audioBytes.size.toLong(),
            sha256             = Base64.encode(enc.plaintextSha256),
        )
        // mediaKey / nonce / sha256 never appear in log lines per hard guardrail.

        // PR-M2f.2 — byte-based early-manifest threshold. The previous
        // fixed `EARLY_MANIFEST_AFTER_CHUNKS = 3` was tuned for 1700-byte
        // chunks (3 × 1700 = 5100 bytes of proof-of-life before the
        // receiver is told to start downloading). Now that production
        // `TARGET_RAW_CHUNK_BYTES = 3200` and the debug selector can push
        // up to 3500, a fixed-3 threshold would delay the receiver to
        // 9600+ bytes — almost double the original budget — and weaken
        // the M2e upload/download overlap. Switching to a byte budget
        // recovers the original M2e timing across all chunk sizes:
        //   1700 → 3 chunks   2400 → 3 chunks
        //   3200 → 2 chunks   3500 → 2 chunks
        val earlyAt = max(
            1,
            ceil(EARLY_MANIFEST_AFTER_BYTES.toDouble() / selectedChunkSize).toInt(),
        ).coerceAtMost(total)
        var earlyManifestSent = false

        // Step 3: upload loop (sequential, one chunk at a time)
        for (idx in 0 until total) {
            uploadChunkWithRefresh(
                mediaId = enc.mediaId,
                idx = idx,
                total = total,
                chunkBytes = chunks[idx],
            )
            // uploadChunkWithRefresh throws on terminal failure.
            val sent = idx + 1
            log(
                "MEDIA_TX upload_progress mediaId=${enc.mediaId.take(8)} " +
                    "sent=$sent total=$total",
            )
            onChunkUploaded?.invoke(sent, total)

            // PR-M2e — fire the early-manifest callback exactly once, after
            // the first K chunks have committed on the relay. Callback may
            // suspend (it goes through the Double Ratchet + transport.send).
            if (!earlyManifestSent && sent >= earlyAt && onEarlyManifest != null) {
                earlyManifestSent = true
                log(
                    "MEDIA_TX early_manifest_sent mediaId=${enc.mediaId.take(8)} " +
                        "afterChunks=$sent total=$total",
                )
                onEarlyManifest.invoke(manifest)
            }
        }
        log("MEDIA_TX upload_complete mediaId=${enc.mediaId.take(8)} chunks=$total")

        manifest
    }

    // ── internal helpers ──────────────────────────────────────────────────────

    /**
     * Upload a single chunk with CAS token-refresh (≤3 cycles) and network
     * backoff retry (≤5 attempts). Throws on terminal failure.
     */
    private suspend fun uploadChunkWithRefresh(
        mediaId: String,
        idx: Int,
        total: Int,
        chunkBytes: ByteArray,
    ) {
        var staleToken: String? = null
        repeat(MAX_TOKEN_ATTEMPTS) { attempt ->
            val token = tokenProvider.acquireToken(reason = "media_upload", staleToken = staleToken)
                ?: throw IllegalStateException(
                    "auth_refresh_exhausted after $MAX_TOKEN_ATTEMPTS cycles " +
                        "mediaId=${mediaId.take(8)} idx=$idx",
                )

            val result = uploadWithNetworkRetry(token, mediaId, idx, total, chunkBytes)

            when {
                result.isSuccess -> {
                    val status = result.getOrThrow()
                    log(
                        "MEDIA_TX chunk_uploaded mediaId=${mediaId.take(8)} " +
                            "idx=$idx/$total status=${status.name.lowercase()}",
                    )
                    return // success
                }
                result.exceptionOrNull() is MediaAuthException -> {
                    staleToken = token // tell provider: this token is dead
                    // loop continues
                }
                result.exceptionOrNull() is MediaConflictException -> {
                    val ex = result.exceptionOrNull() as MediaConflictException
                    log(
                        "MEDIA_TX chunk_conflict_mismatch mediaId=${mediaId.take(8)} " +
                            "idx=$idx reason=${ex.reason.take(60)}",
                    )
                    throw ex // not retryable — caller bug
                }
                result.exceptionOrNull() is MediaQuotaException -> {
                    val ex = result.exceptionOrNull() as MediaQuotaException
                    log(
                        "MEDIA_TX chunk_quota_exceeded mediaId=${mediaId.take(8)} " +
                            "reason=${ex.reason.take(60)}",
                    )
                    throw ex // not retryable
                }
                else -> throw result.exceptionOrNull()
                    ?: IllegalStateException("upload returned empty failure")
            }
        }
        throw MediaAuthException("auth_refresh_exhausted after $MAX_TOKEN_ATTEMPTS cycles mediaId=${mediaId.take(8)} idx=$idx")
    }

    /**
     * Wraps [MediaUploadTransport.uploadChunk] with 5-attempt network backoff.
     * Returns the upstream result unchanged for non-network errors.
     */
    private suspend fun uploadWithNetworkRetry(
        token: String,
        mediaId: String,
        idx: Int,
        total: Int,
        chunkBytes: ByteArray,
    ): Result<MediaUploadTransport.UploadStatus> {
        var lastResult: Result<MediaUploadTransport.UploadStatus>? = null
        for (attempt in 1..MAX_NETWORK_ATTEMPTS) {
            val r = mediaTransport.uploadChunk(token, mediaId, idx, total, chunkBytes)
            if (r.isSuccess) return r
            val ex = r.exceptionOrNull()
            if (ex !is MediaTransportException) return r // auth/quota/conflict — let caller handle
            lastResult = r
            if (attempt < MAX_NETWORK_ATTEMPTS) {
                delay(NETWORK_RETRY_DELAYS_MS[attempt - 1])
            }
        }
        return lastResult ?: Result.failure(MediaTransportException("network_retry_exhausted"))
    }

    companion object {
        private const val MAX_TOKEN_ATTEMPTS = 3
        private const val MAX_NETWORK_ATTEMPTS = 5
        private val NETWORK_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L, 60_000L)

        // PR-M2e — byte budget the sender uploads before sending the
        // manifest envelope (was a fixed chunk count until PR-M2f.2).
        //
        // 5100 bytes preserves the original 3 × 1700 = 5100 budget so the
        // M2e overlap timing is identical at the 1700 baseline and degrades
        // gracefully on larger chunks — relay has proof of life before the
        // receiver is told to start downloading, but we don't accidentally
        // postpone the manifest by ~9.6 KB once chunks grow to 3200.
        //
        // Per chunk size the threshold becomes:
        //   `max(1, ceil(5100.0 / chunkSize)).coerceAtMost(total)`
        //   1700 → 3   2400 → 3   3200 → 2   3500 → 2
        //
        // Vladislav 2026-05-19 locked policy: never K=1; the relay must
        // have a couple of chunks stored before the receiver is told to
        // start downloading so `chunk_not_ready_yet` doesn't fire on the
        // very first attempted GET.
        private const val EARLY_MANIFEST_AFTER_BYTES = 5_100

        // PR-M2f.1 probe — defence-in-depth bounds for the chunk-size
        // provider. The relay's HTTP body cap (default 9000) and the
        // recorded Tele2 full-roundtrip ceiling (~2400 wire bytes on v3)
        // sandwich the realistic search range. A stale/typo'd selector
        // value gets clamped here rather than crashing the upload.
        private const val MIN_PROBE_CHUNK_BYTES = 1024
        private const val MAX_PROBE_CHUNK_BYTES = 8000
    }
}
