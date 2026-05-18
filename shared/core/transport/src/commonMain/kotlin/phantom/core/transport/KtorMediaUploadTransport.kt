// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ── Relay endpoint paths ───────────────────────────────────────────────────────

/** Path constants for the relay's media chunk REST surface (PR-M1r). */
object MediaRelayEndpoints {
    const val UPLOAD_CHUNK = "/media/upload-chunk"
    const val DOWNLOAD_CHUNK_PREFIX = "/media/chunk"
}

// ── Wire models ────────────────────────────────────────────────────────────────

@Serializable
private data class UploadChunkRequest(
    @SerialName("media_id") val mediaId: String,
    @SerialName("idx") val idx: Int,
    @SerialName("total") val total: Int,
    /** Standard base64 (not URL-safe, no line breaks) — relay accepts standard alphabet. */
    @SerialName("ciphertext_b64") val ciphertextB64: String,
)

@Serializable
private data class DownloadChunkResponse(
    @SerialName("ciphertext_b64") val ciphertextB64: String,
    @SerialName("total") val total: Int,
)

// ── Implementation ─────────────────────────────────────────────────────────────

/**
 * Ktor-based implementation of [MediaUploadTransport].
 *
 * Mirrors the style of [PreKeyApiClient]: the [HttpClient] is injected
 * (constructed by AppContainer with the appropriate engine + OkHttp config).
 * The bearer token is injected at call time — callers (PR-M1w orchestrator)
 * own token caching and refresh, exactly as [RestFallbackOrchestrator] does
 * for REST fallback session tokens.
 *
 * Client-side size guard:
 *   Before making any HTTP call, [uploadChunk] computes the approximate
 *   JSON body size:
 *     base64Size = ceil(ciphertext.size * 4 / 3)   (standard base64)
 *     envelope ≈ JSON wrapper (~120 bytes) + base64Size
 *   If the result exceeds [CLIENT_MAX_CHUNK_BODY_BYTES] (2600 bytes) the
 *   function throws [IllegalArgumentException] without making an HTTP call.
 *   The relay's hard cap is 3072 bytes; the 472-byte headroom is intentional.
 *
 * No retry logic lives here — callers own retry with backoff (same pattern
 * as [RestFallbackOrchestrator] for send/poll).
 */
@OptIn(ExperimentalEncodingApi::class)
class KtorMediaUploadTransport(
    private val httpClient: HttpClient,
    /** Base URL of the relay, e.g. "https://relay.phntm.pro". No trailing slash. */
    private val relayBaseUrl: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : MediaUploadTransport {

    override suspend fun uploadChunk(
        token: String,
        mediaId: String,
        idx: Int,
        total: Int,
        ciphertext: ByteArray,
    ): Result<MediaUploadTransport.UploadStatus> {
        // Client-side body-size guard. Prevents a relay-side 413 and tells the
        // chunker that TARGET_RAW_CHUNK_BYTES needs to be lowered if it fires.
        val b64Size = base64EncodedSize(ciphertext.size)
        // JSON wrapper: {"media_id":"<43chars>","idx":NN,"total":NN,"ciphertext_b64":"<b64>"}
        // Fixed overhead conservatively estimated at 120 bytes.
        val estimatedBodySize = JSON_WRAPPER_OVERHEAD + b64Size
        if (estimatedBodySize > CLIENT_MAX_CHUNK_BODY_BYTES) {
            return Result.failure(
                IllegalArgumentException(
                    "ciphertext_too_large_for_chunk: estimated body $estimatedBodySize > " +
                        "CLIENT_MAX_CHUNK_BODY_BYTES $CLIENT_MAX_CHUNK_BODY_BYTES"
                )
            )
        }

        val ciphertextB64 = Base64.encode(ciphertext)
        val requestBody = json.encodeToString(
            UploadChunkRequest.serializer(),
            UploadChunkRequest(
                mediaId = mediaId,
                idx = idx,
                total = total,
                ciphertextB64 = ciphertextB64,
            ),
        )

        return runCatching {
            val response = httpClient.post("$relayBaseUrl${MediaRelayEndpoints.UPLOAD_CHUNK}") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val rawBody = response.bodyAsText()
            mapUploadResponse(response.status, rawBody)
        }.getOrElse { e ->
            // Wrap network/IO exceptions (SocketTimeoutException, IOException,
            // ConnectException, etc.) as MediaTransportException so VoiceV2Sender's
            // network-retry loop (5 attempts, backoff 1s/3s/8s/20s/60s) fires.
            // Before this fix, a single Tele2 LTE upload timeout killed the entire
            // voice upload because VoiceV2Sender treated non-MediaTransportException
            // as terminal (Test #57 second blocker).
            Result.failure(
                MediaTransportException(
                    "upload-chunk network ${e::class.simpleName}: ${e.message?.take(120) ?: ""}"
                )
            )
        }
    }

    override suspend fun downloadChunk(
        token: String,
        mediaId: String,
        idx: Int,
    ): Result<MediaUploadTransport.DownloadResult> {
        return runCatching {
            val url = "$relayBaseUrl${MediaRelayEndpoints.DOWNLOAD_CHUNK_PREFIX}/$mediaId/$idx"
            val response = httpClient.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            val rawBody = response.bodyAsText()
            mapDownloadResponse(response.status, rawBody)
        }.getOrElse { e ->
            // Same wrap rationale as uploadChunk — see comment there.
            Result.failure(
                MediaTransportException(
                    "download-chunk network ${e::class.simpleName}: ${e.message?.take(120) ?: ""}"
                )
            )
        }
    }

    // ── response mappers ───────────────────────────────────────────────────────

    private fun mapUploadResponse(
        status: HttpStatusCode,
        rawBody: String,
    ): Result<MediaUploadTransport.UploadStatus> = when (status) {
        HttpStatusCode.Created -> Result.success(MediaUploadTransport.UploadStatus.STORED)
        HttpStatusCode.OK -> Result.success(MediaUploadTransport.UploadStatus.DUPLICATE)
        HttpStatusCode.Conflict -> {
            val reason = extractErrorField(rawBody)
            Result.failure(MediaConflictException(reason))
        }
        HttpStatusCode.PayloadTooLarge -> {
            val reason = extractErrorField(rawBody)
            Result.failure(MediaQuotaException(reason))
        }
        HttpStatusCode.BadRequest -> {
            Result.failure(IllegalArgumentException("upload-chunk 400: $rawBody"))
        }
        HttpStatusCode.Unauthorized -> {
            Result.failure(MediaAuthException("media_auth_401"))
        }
        else -> {
            Result.failure(MediaTransportException("upload-chunk unexpected status=${status.value}: $rawBody"))
        }
    }

    private fun mapDownloadResponse(
        status: HttpStatusCode,
        rawBody: String,
    ): Result<MediaUploadTransport.DownloadResult> = when (status) {
        HttpStatusCode.OK -> {
            val parsed = json.decodeFromString(DownloadChunkResponse.serializer(), rawBody)
            val ciphertext = Base64.decode(parsed.ciphertextB64)
            Result.success(
                MediaUploadTransport.DownloadResult(
                    ciphertext = ciphertext,
                    total = parsed.total,
                )
            )
        }
        HttpStatusCode.NotFound -> Result.failure(NotFoundException)
        HttpStatusCode.Unauthorized -> Result.failure(MediaAuthException("media_auth_401"))
        else -> Result.failure(MediaTransportException("download-chunk unexpected status=${status.value}: $rawBody"))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Parses the "error" field from a relay JSON error body, falls back to raw. */
    private fun extractErrorField(rawBody: String): String {
        return try {
            val obj = json.decodeFromString(JsonObject.serializer(), rawBody)
            obj["error"]?.jsonPrimitive?.content ?: rawBody
        } catch (_: Exception) {
            rawBody
        }
    }

    companion object {
        /**
         * Client-side body size cap before making an HTTP upload call.
         * The relay's hard cap is 3072 bytes. We use 2600 to leave 472 bytes
         * of headroom for JSON wrapper variance and relay-side framing overhead.
         */
        const val CLIENT_MAX_CHUNK_BODY_BYTES = 2600

        /**
         * Conservative estimate of the JSON envelope overhead around the
         * base64-encoded ciphertext. Accounts for field names, colons, commas,
         * braces, and a 43-char base64url mediaId.
         */
        private const val JSON_WRAPPER_OVERHEAD = 120
    }
}

// ── Shared utility ─────────────────────────────────────────────────────────────

/** Computes the standard base64 encoded length for [rawBytes] input bytes. */
internal fun base64EncodedSize(rawBytes: Int): Int = ((rawBytes + 2) / 3) * 4
