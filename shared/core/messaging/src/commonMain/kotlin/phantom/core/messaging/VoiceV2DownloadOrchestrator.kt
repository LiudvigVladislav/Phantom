// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.hash.Hash
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import phantom.core.crypto.MediaCrypto
import phantom.core.storage.MessageRepository
import phantom.core.storage.MessageStatus
import phantom.core.storage.VoiceV2DownloadRepository
import phantom.core.transport.MediaAuthException
import phantom.core.transport.MediaAuthTokenProvider
import phantom.core.transport.MediaTransportException
import phantom.core.transport.MediaUploadTransport
import phantom.core.transport.NotFoundException

/**
 * Runs a single voice_v2 download task end-to-end (PR-M1w, Commit 4):
 *   1. Load durable task from [VoiceV2DownloadRepository].
 *   2. Download all ciphertext chunks from the relay via [MediaUploadTransport].
 *   3. Reassemble the ciphertext blob.
 *   4. Decrypt via [MediaCrypto.decryptVoice].
 *   5. Verify SHA-256 of plaintext against manifest (Q2: ionspin Hash.sha256).
 *   6. Write audio bytes to persistent local file via [VoiceFileStore].
 *   7. Update [MessageRepository] row to DELIVERED + [AUDIO_LOCAL:<path>].
 *   8. Delete the durable task row (COMPLETE).
 *
 * Token refresh uses CAS semantics via [MediaAuthTokenProvider] (Q3).
 * Network retry: 5 attempts, backoff [1s, 3s, 8s, 20s, 60s].
 *
 * Security: no mediaKey / nonce / sha256 values appear in any log line
 * (hard guardrail §1). mediaId truncated to 8 chars in all logs (§2).
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
class VoiceV2DownloadOrchestrator(
    private val downloadRepo: VoiceV2DownloadRepository,
    private val messageRepo: MessageRepository,
    private val mediaTransport: MediaUploadTransport,
    private val tokenProvider: MediaAuthTokenProvider,
    private val mediaCrypto: MediaCrypto,
    private val fileStore: VoiceFileStore,
    private val log: (String) -> Unit = {},
) {

    /**
     * Download, decrypt, verify, and persist the audio for [mediaId].
     * Safe to call multiple times for the same mediaId (idempotent via find-guard).
     */
    suspend fun runDownloadTask(
        mediaId: String,
        onChunkDownloaded: ((received: Int, total: Int) -> Unit)? = null,
    ) {
        val task = downloadRepo.find(mediaId) ?: return // already deleted (complete) or never inserted
        if (task.status == VoiceV2DownloadRepository.STATUS_COMPLETE) return

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val manifest = runCatching {
            json.decodeFromString<VoiceManifestV2>(task.manifestJson)
        }.getOrElse { ex ->
            log("MEDIA_RX download_failed mediaId=${mediaId.take(8)} reason=manifest_parse_error error=${ex::class.simpleName}")
            markFailed(mediaId, "manifest_parse_error")
            return
        }

        // PR-M2e — dynamic fresh-task window. The sender now sends the
        // manifest after the first 3 chunks (EARLY_MANIFEST_AFTER_CHUNKS),
        // so the receiver MUST treat 404 on still-uploading chunks as
        // `chunk_not_ready_yet`, not `media_chunks_gone`. The window scales
        // with chunkCount so a 200-chunk voice still has slack.
        val freshWindowMs = computeFreshWindowMs(manifest.chunkCount)
        log(
            "MEDIA_RX fresh_window_ms mediaId=${mediaId.take(8)} " +
                "chunkCount=${manifest.chunkCount} windowMs=$freshWindowMs " +
                "createdAtMs=${task.createdAtMs}",
        )

        // Download all chunks sequentially
        val chunks = mutableListOf<ByteArray>()
        for (idx in 0 until manifest.chunkCount) {
            val chunkBytes = downloadOneChunkWithFreshRetry(
                mediaId          = manifest.mediaId,
                idx              = idx,
                taskCreatedAtMs  = task.createdAtMs,
                freshWindowMs    = freshWindowMs,
            ) ?: return // markFailed already invoked inside on terminal/expired
            chunks.add(chunkBytes)
            val received = idx + 1
            log(
                "MEDIA_RX download_progress mediaId=${mediaId.take(8)} " +
                    "received=$received total=${manifest.chunkCount}",
            )
            onChunkDownloaded?.invoke(received, manifest.chunkCount)
        }

        // Reassemble ciphertext blob
        val totalSize = chunks.sumOf { it.size }
        val encryptedBlob = ByteArray(totalSize)
        var offset = 0
        for (c in chunks) { c.copyInto(encryptedBlob, offset); offset += c.size }

        // Decrypt
        val plainAudio = runCatching {
            mediaCrypto.decryptVoice(
                mediaId   = manifest.mediaId,
                mediaKey  = Base64.decode(manifest.mediaKey),
                nonce     = Base64.decode(manifest.nonce),
                ciphertext = encryptedBlob,
            )
        }.getOrElse { ex ->
            log("MEDIA_RX decrypt_failed mediaId=${mediaId.take(8)} error=${ex::class.simpleName}")
            markFailed(mediaId, "decrypt_failed")
            return
        }

        // SHA-256 verify (Q2: ionspin Hash.sha256)
        val expectedSha256 = runCatching { Base64.decode(manifest.sha256) }
            .getOrElse { ex ->
                log("MEDIA_RX sha256_decode_failed mediaId=${mediaId.take(8)} error=${ex::class.simpleName}")
                markFailed(mediaId, "sha256_decode_failed")
                return
            }
        val actualSha256 = Hash.sha256(plainAudio.toUByteArray()).toByteArray()
        if (!actualSha256.contentEquals(expectedSha256)) {
            log("MEDIA_RX sha256_mismatch mediaId=${mediaId.take(8)}")
            markFailed(mediaId, "sha256_mismatch")
            return
        }

        // Write to persistent local file
        val audioPath = fileStore.save(
            mediaId    = manifest.mediaId,
            audioBytes = plainAudio,
            mime       = manifest.mime,
        )

        // Update message row + task row
        messageRepo.updateMessageText(task.mediaId, "[AUDIO_LOCAL:$audioPath]")
        messageRepo.updateStatus(task.mediaId, MessageStatus.DELIVERED)
        downloadRepo.update(mediaId, VoiceV2DownloadRepository.STATUS_COMPLETE, null, Clock.System.now().toEpochMilliseconds())
        downloadRepo.delete(mediaId) // task complete — free space immediately

        log("MEDIA_RX download_complete mediaId=${mediaId.take(8)} plainBytes=${plainAudio.size}")
    }

    // ── internal helpers ──────────────────────────────────────────────────────

    /**
     * PR-M2e — outcome of a single chunk-download attempt. Needed because the
     * old `ByteArray?` return type collapsed 404 (NotFoundException) and
     * terminal auth-exhausted failures into the same `null`. With overlap
     * upload/download a 404 is now an expected transient on the upload tail.
     */
    private sealed interface ChunkOutcome {
        data class Ok(val bytes: ByteArray) : ChunkOutcome
        /** Relay returned 404 — chunk not yet uploaded OR genuinely expired. */
        data object NotReady : ChunkOutcome
        /** Auth refresh exhausted, transport gave up, etc. — not retryable. */
        data object Terminal : ChunkOutcome
    }

    /**
     * PR-M2e — wraps a single chunk download with the fresh-task retry loop.
     * 404 is treated as `chunk_not_ready_yet` and retried with backoff
     * (1s → 2s → 3s cap) while `now - taskCreatedAtMs < freshWindowMs`. Once
     * the window expires, falls through to `markFailed(media_chunks_gone)`.
     * Terminal errors mark failed immediately.
     */
    private suspend fun downloadOneChunkWithFreshRetry(
        mediaId: String,
        idx: Int,
        taskCreatedAtMs: Long,
        freshWindowMs: Long,
    ): ByteArray? {
        var backoffMs = NOT_READY_INITIAL_BACKOFF_MS
        while (true) {
            when (val outcome = downloadChunkOnce(mediaId, idx)) {
                is ChunkOutcome.Ok -> return outcome.bytes
                ChunkOutcome.NotReady -> {
                    val ageMs = Clock.System.now().toEpochMilliseconds() - taskCreatedAtMs
                    if (ageMs >= freshWindowMs) {
                        log(
                            "MEDIA_RX chunk_not_ready_deadline_exceeded " +
                                "mediaId=${mediaId.take(8)} idx=$idx ageMs=$ageMs " +
                                "windowMs=$freshWindowMs",
                        )
                        log("MEDIA_RX download_failed mediaId=${mediaId.take(8)} reason=media_chunks_gone idx=$idx")
                        markFailed(mediaId, "media_chunks_gone")
                        return null
                    }
                    log(
                        "MEDIA_RX chunk_not_ready_yet mediaId=${mediaId.take(8)} " +
                            "idx=$idx retry_in=${backoffMs}ms ageMs=$ageMs windowMs=$freshWindowMs",
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs + 1_000L).coerceAtMost(NOT_READY_MAX_BACKOFF_MS)
                }
                ChunkOutcome.Terminal -> {
                    log(
                        "MEDIA_RX download_failed mediaId=${mediaId.take(8)} " +
                            "reason=transport_terminal idx=$idx",
                    )
                    markFailed(mediaId, "transport_terminal")
                    return null
                }
            }
        }
    }

    private suspend fun downloadChunkOnce(mediaId: String, idx: Int): ChunkOutcome {
        var staleToken: String? = null
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = tokenProvider.acquireToken(reason = "media_download", staleToken = staleToken)
                ?: return ChunkOutcome.Terminal

            val result = downloadWithNetworkRetry(token, mediaId, idx)
            when {
                result.isSuccess -> return ChunkOutcome.Ok(result.getOrThrow().ciphertext)
                result.exceptionOrNull() is NotFoundException -> return ChunkOutcome.NotReady
                result.exceptionOrNull() is MediaAuthException -> {
                    staleToken = token
                    // loop — let CAS provider refresh
                }
                else -> return ChunkOutcome.Terminal
            }
        }
        return ChunkOutcome.Terminal
    }

    /**
     * PR-M2e — dynamic fresh-task window in milliseconds. Larger voices need
     * more grace because the sender's upload tail takes longer.
     * Formula: clamp(chunkCount * PER_CHUNK_MS, [MIN, MAX]).
     *   15 chunks  → 120 s (MIN floor)
     *   107 chunks → 160 s
     *   200 chunks → 300 s (MAX cap)
     */
    private fun computeFreshWindowMs(chunkCount: Int): Long {
        val scaled = chunkCount.toLong() * FRESH_TASK_PER_CHUNK_MS
        return scaled.coerceIn(FRESH_TASK_MIN_WINDOW_MS, FRESH_TASK_MAX_WINDOW_MS)
    }

    private suspend fun downloadWithNetworkRetry(
        token: String,
        mediaId: String,
        idx: Int,
    ): Result<MediaUploadTransport.DownloadResult> {
        var last: Result<MediaUploadTransport.DownloadResult>? = null
        for (attempt in 1..MAX_NETWORK_ATTEMPTS) {
            val r = mediaTransport.downloadChunk(token, mediaId, idx)
            if (r.isSuccess) return r
            if (r.exceptionOrNull() !is MediaTransportException) return r
            last = r
            if (attempt < MAX_NETWORK_ATTEMPTS) delay(NETWORK_RETRY_DELAYS_MS[attempt - 1])
        }
        return last ?: Result.failure(MediaTransportException("download_network_retry_exhausted"))
    }

    private suspend fun markFailed(mediaId: String, reason: String) {
        // Receiver message row PK == mediaId (see DefaultMessagingService.handleVoiceV2Manifest line 2523).
        downloadRepo.update(mediaId, VoiceV2DownloadRepository.STATUS_FAILED, reason, Clock.System.now().toEpochMilliseconds())
        messageRepo.updateStatus(mediaId, MessageStatus.FAILED)
        messageRepo.updateMessageText(mediaId, "[AUDIO_FAILED:$reason]")
    }

    companion object {
        private const val MAX_TOKEN_ATTEMPTS = 3
        private const val MAX_NETWORK_ATTEMPTS = 5
        private val NETWORK_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L, 60_000L)

        // PR-M2e — dynamic fresh-task window. Vladislav 2026-05-19: fixed
        // 60 s + 10 retries × 2 s would false-fail the tail of a 107-chunk
        // voice on Tele2 LTE (~78 s upload). The window scales with chunk
        // count so long voice notes have enough slack while pathological
        // requests (chunkCount = 256 max from manifest) are still capped.
        private const val FRESH_TASK_MIN_WINDOW_MS = 120_000L   // 2 min floor
        private const val FRESH_TASK_PER_CHUNK_MS  = 1_500L     // ≈ measured per-chunk upload
        private const val FRESH_TASK_MAX_WINDOW_MS = 300_000L   // 5 min cap

        // PR-M2e — backoff for `chunk_not_ready_yet`. Climbs 1 s → 2 s → 3 s
        // and stays at the cap until the fresh-task window expires.
        private const val NOT_READY_INITIAL_BACKOFF_MS = 1_000L
        private const val NOT_READY_MAX_BACKOFF_MS     = 3_000L
    }
}
