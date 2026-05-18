// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import com.ionspin.kotlin.crypto.hash.Hash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    suspend fun runDownloadTask(mediaId: String) {
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

        // PR-M2b.1 — parallel download with typed failure outcomes + sequential
        // fallback. Test #64 (Tele2 35-sec voice, mediaId=CluGa7GK on the relay):
        // parallelism=2 activated and accelerated the first ~20 chunks (relay
        // log proved pairs idx=1+0, idx=2+3, ..., idx=20+19 served), then idx=21
        // and idx=23 caught InterruptedIOException at ~10 s each. The previous
        // M2b implementation conflated NotFoundException, network timeout, and
        // auth-refresh-exhausted into a single `null` return, all of which the
        // caller then mapped to `media_chunks_gone`. Result: a transient TCP
        // hiccup on a single chunk surfaced as "Voice unavailable" in the UI
        // even though the relay had all 116 chunks stored (VPS log evidence).
        //
        // The fix has three parts:
        //   1. `downloadChunkOnce` returns a typed `ChunkDownloadResult`
        //      so callers distinguish `Missing` (real 404) from `TransientFailure`
        //      (timeout / network / auth exhaustion).
        //   2. The orchestrator runs the parallel phase as best-effort: any
        //      chunk that returns TransientFailure leaves its slot null and we
        //      continue. Only Missing (404) is fatal at this stage.
        //   3. After the parallel phase, a sequential phase sweeps the still-null
        //      slots one-at-a-time. Sequential = the proven-stable pre-M2b path.
        //      If sequential also returns TransientFailure, the task is LEFT
        //      PENDING — chunks may still arrive on the next attempt — and the
        //      startReceiving finalizer at next app start will retry the same
        //      task via `findPending()`. The user keeps seeing [AUDIO_DOWNLOADING]
        //      which is honest; they do NOT see a permanent "Voice unavailable"
        //      false positive.
        //
        // CancellationException is rethrown unchanged before any decision,
        // because a normal scope cancel must not corrupt the task state.
        val chunks = arrayOfNulls<ByteArray>(manifest.chunkCount)
        log(
            "MEDIA_RX download_parallel_start mediaId=${mediaId.take(8)} " +
                "chunks=${manifest.chunkCount} parallelism=$DOWNLOAD_PARALLELISM"
        )
        val semaphore = Semaphore(permits = DOWNLOAD_PARALLELISM)

        val parallelOutcome = runCatching {
            coroutineScope {
                (0 until manifest.chunkCount).map { idx ->
                    async {
                        semaphore.withPermit {
                            when (val r = downloadChunkOnce(manifest.mediaId, idx)) {
                                is ChunkDownloadResult.Success -> {
                                    chunks[idx] = r.bytes
                                    log(
                                        "MEDIA_RX parallel_chunk_ok mediaId=${mediaId.take(8)} " +
                                            "idx=$idx"
                                    )
                                }
                                is ChunkDownloadResult.Missing -> {
                                    // Real 404 — chunks gone — fail fast and cancel siblings.
                                    throw MediaChunkGoneException(idx)
                                }
                                is ChunkDownloadResult.TransientFailure -> {
                                    // Leave slot null; sequential phase will retry.
                                    log(
                                        "MEDIA_RX parallel_transient_failed mediaId=${mediaId.take(8)} " +
                                            "idx=$idx reason=${r.reason}"
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        parallelOutcome.exceptionOrNull()?.let { ex ->
            if (ex is CancellationException) throw ex
            if (ex is MediaChunkGoneException) {
                log(
                    "MEDIA_RX download_failed mediaId=${mediaId.take(8)} " +
                        "reason=media_chunks_gone idx=${ex.idx}"
                )
                markFailed(mediaId, "media_chunks_gone")
                return
            }
            // Any unexpected non-cancellation throwable falls through to
            // sequential fallback rather than markFailed — the chunks may
            // still be there and a single-stream retry is the safer move.
            log(
                "MEDIA_RX parallel_unexpected mediaId=${mediaId.take(8)} " +
                    "error=${ex::class.simpleName}"
            )
        }

        // Phase 2: sequential sweep of still-null slots.
        val missingIdx = (0 until manifest.chunkCount).filter { chunks[it] == null }
        if (missingIdx.isNotEmpty()) {
            log(
                "MEDIA_RX download_fallback_sequential_start mediaId=${mediaId.take(8)} " +
                    "remaining=${missingIdx.size}"
            )
            for (idx in missingIdx) {
                when (val r = downloadChunkOnce(manifest.mediaId, idx)) {
                    is ChunkDownloadResult.Success -> {
                        chunks[idx] = r.bytes
                        log(
                            "MEDIA_RX sequential_chunk_ok mediaId=${mediaId.take(8)} " +
                                "idx=$idx"
                        )
                    }
                    is ChunkDownloadResult.Missing -> {
                        log(
                            "MEDIA_RX download_failed mediaId=${mediaId.take(8)} " +
                                "reason=media_chunks_gone idx=$idx phase=sequential"
                        )
                        markFailed(mediaId, "media_chunks_gone")
                        return
                    }
                    is ChunkDownloadResult.TransientFailure -> {
                        // Sequential phase also failed. Do NOT markFailed.
                        // Task stays PENDING; startReceiving finalizer will
                        // resume on the next app start. Record the attempt
                        // timestamp so the finalizer can rate-limit if needed.
                        log(
                            "MEDIA_RX download_transient_failed mediaId=${mediaId.take(8)} " +
                                "idx=$idx reason=${r.reason} phase=sequential — task stays PENDING"
                        )
                        downloadRepo.update(
                            mediaId,
                            VoiceV2DownloadRepository.STATUS_PENDING,
                            "transient: ${r.reason}",
                            Clock.System.now().toEpochMilliseconds(),
                        )
                        return
                    }
                }
            }
        }
        log(
            "MEDIA_RX download_parallel_complete mediaId=${mediaId.take(8)} " +
                "chunks=${manifest.chunkCount}"
        )

        // Reassemble ciphertext blob in strict idx order (0..N-1).
        val totalSize = chunks.sumOf { it!!.size }
        val encryptedBlob = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until manifest.chunkCount) {
            val c = chunks[i]!!
            c.copyInto(encryptedBlob, offset)
            offset += c.size
        }

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
     * Download one chunk with full token-refresh + network-retry handling.
     *
     * The returned [ChunkDownloadResult] distinguishes:
     *   - [ChunkDownloadResult.Success] — bytes in hand.
     *   - [ChunkDownloadResult.Missing] — the relay confirmed 404 NotFound.
     *     Only this outcome justifies a permanent `media_chunks_gone` failure.
     *   - [ChunkDownloadResult.TransientFailure] — auth refresh exhausted, network
     *     timeout, or any other non-404 exception path. The caller should treat
     *     this as "try again later" (sequential fallback or next startReceiving
     *     pass) rather than a terminal failure.
     *
     * Prior to PR-M2b.1 this method returned `ByteArray?` with `null` meaning
     * everything-except-success, which caused Test #64 to misreport
     * `InterruptedIOException` on idx=21/23 as `media_chunks_gone` even though
     * the VPS relay log confirmed every chunk was stored. The typed result is
     * the contract fix that prevents that false positive.
     */
    private suspend fun downloadChunkOnce(mediaId: String, idx: Int): ChunkDownloadResult {
        var staleToken: String? = null
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = tokenProvider.acquireToken(reason = "media_download", staleToken = staleToken)
                ?: return ChunkDownloadResult.TransientFailure("auth_token_null")

            val result = downloadWithNetworkRetry(token, mediaId, idx)
            when {
                result.isSuccess -> {
                    return ChunkDownloadResult.Success(result.getOrThrow().ciphertext)
                }
                result.exceptionOrNull() is NotFoundException -> {
                    // Real 404 — relay does not have this chunk.
                    return ChunkDownloadResult.Missing
                }
                result.exceptionOrNull() is MediaAuthException -> {
                    staleToken = token
                    // loop — let CAS provider refresh
                }
                result.exceptionOrNull() is MediaTransportException -> {
                    val msg = result.exceptionOrNull()?.message?.take(80) ?: "transport_error"
                    return ChunkDownloadResult.TransientFailure(msg)
                }
                else -> {
                    val msg = result.exceptionOrNull()?.let {
                        "${it::class.simpleName}:${it.message?.take(60)}"
                    } ?: "unknown_error"
                    return ChunkDownloadResult.TransientFailure(msg)
                }
            }
        }
        return ChunkDownloadResult.TransientFailure("auth_refresh_exhausted")
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
        // PR-M2b — receiver-side concurrent chunk downloads. 2 is the safe
        // starting point for Tele2 LTE middleboxes. Fallback to 1 is a
        // single-line constant change if Test #64 surfaces throttling.
        private const val DOWNLOAD_PARALLELISM = 2
    }
}

/**
 * Typed outcome of a single chunk download attempt (PR-M2b.1).
 *
 * The contract: every caller must pattern-match all three variants. Replacing
 * `ByteArray?` with this sealed hierarchy is the fix for Test #64's false
 * `media_chunks_gone` reports — the previous nullable return collapsed
 * auth-refresh-exhausted, network timeout, and real 404 into a single signal.
 */
private sealed class ChunkDownloadResult {
    /** Relay returned the chunk ciphertext. */
    class Success(val bytes: ByteArray) : ChunkDownloadResult()

    /**
     * Relay confirmed the chunk does not exist (HTTP 404 / NotFoundException).
     * Only this outcome justifies a permanent `markFailed("media_chunks_gone")`.
     */
    object Missing : ChunkDownloadResult()

    /**
     * Auth refresh exhausted, network/transport error, or other non-404 path.
     * Caller should retry sequentially or leave the task PENDING for the next
     * startReceiving sweep — never markFailed on this outcome.
     */
    class TransientFailure(val reason: String) : ChunkDownloadResult()
}

/**
 * Thrown by the parallel phase when one worker hits [ChunkDownloadResult.Missing]
 * (real 404). Triggers structured-concurrency cancellation of sibling workers
 * so the caller can react with a single `markFailed("media_chunks_gone")` call.
 * Transient failures do NOT throw — they leave the chunks[idx] slot null for
 * the sequential fallback phase to retry.
 */
private class MediaChunkGoneException(val idx: Int) : Exception("chunk $idx gone")
