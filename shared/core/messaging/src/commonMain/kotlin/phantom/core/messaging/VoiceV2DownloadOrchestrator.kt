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

        // Download all chunks sequentially
        val chunks = mutableListOf<ByteArray>()
        for (idx in 0 until manifest.chunkCount) {
            val chunkBytes = downloadChunkWithRefresh(manifest.mediaId, idx)
            if (chunkBytes == null) {
                // Permanent failure: relay lost the chunks (TTL expiry or relay restart)
                log("MEDIA_RX download_failed mediaId=${mediaId.take(8)} reason=media_chunks_gone idx=$idx")
                markFailed(mediaId, "media_chunks_gone")
                return
            }
            chunks.add(chunkBytes)
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

    private suspend fun downloadChunkWithRefresh(mediaId: String, idx: Int): ByteArray? {
        var staleToken: String? = null
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = tokenProvider.acquireToken(reason = "media_download", staleToken = staleToken)
                ?: return null // terminal auth failure

            val result = downloadWithNetworkRetry(token, mediaId, idx)
            when {
                result.isSuccess -> return result.getOrThrow().ciphertext
                result.exceptionOrNull() is NotFoundException -> return null // chunks gone
                result.exceptionOrNull() is MediaAuthException -> {
                    staleToken = token
                    // loop — let CAS provider refresh
                }
                else -> return null // other terminal error
            }
        }
        return null // auth refresh exhausted
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
    }
}
