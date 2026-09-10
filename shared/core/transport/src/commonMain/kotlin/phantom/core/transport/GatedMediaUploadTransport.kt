// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * N1-F2 R-N1.2 (2026-08-29) — fail-closed, revocable wrapper around a
 * [MediaUploadTransport].
 *
 * Media upload/download are a Direct REST network boundary that the
 * R-N1.1 authority did not cover. This decorator routes every actual
 * chunk transfer through [RestEgressGate.dispatch] so that in
 * Private/Ghost no media HTTP leaves, and an in-flight upload or
 * download suspended at the network boundary is cancelled/joined when
 * the user leaves Standard.
 *
 * A blocked or revoked transfer surfaces as a [Result.failure] carrying
 * a [MediaTransportException] — the same failure class VoiceV2Sender's
 * retry loop already handles — so durable upload/download retry state
 * is preserved; nothing is silently completed over Direct.
 */
class GatedMediaUploadTransport(
    private val delegate: MediaUploadTransport,
    private val egressGate: RestEgressGate,
) : MediaUploadTransport {

    override suspend fun uploadChunk(
        token: String,
        mediaId: String,
        idx: Int,
        total: Int,
        ciphertext: ByteArray,
    ): Result<MediaUploadTransport.UploadStatus> =
        try {
            egressGate.dispatch("media_upload") {
                delegate.uploadChunk(token, mediaId, idx, total, ciphertext)
            }
        } catch (e: RestEgressBlockedException) {
            Result.failure(
                MediaTransportException(
                    "media upload blocked: decision=${e.decision::class.simpleName}",
                ),
            )
        }

    override suspend fun downloadChunk(
        token: String,
        mediaId: String,
        idx: Int,
    ): Result<MediaUploadTransport.DownloadResult> =
        try {
            egressGate.dispatch("media_download") {
                delegate.downloadChunk(token, mediaId, idx)
            }
        } catch (e: RestEgressBlockedException) {
            Result.failure(
                MediaTransportException(
                    "media download blocked: decision=${e.decision::class.simpleName}",
                ),
            )
        }
}
