// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import phantom.core.transport.MediaAuthTokenProvider
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * PR-M2c.0 diagnostic probe (round 2 after Test #66.1) — measures the largest
 * media-chunk POST body that the configured media endpoints can reliably
 * accept on the current carrier.
 *
 * Test #66.1 found Tele2 Layer B (response-body drop) is the actual ceiling,
 * not the request-body cap. Relay stored 5500/6500-byte POSTs but the 18-byte
 * 201 response never reached the phone within 15s. The original probe coded
 * this as `outcome=stored` AND `error=InterruptedIOException:timeout` on the
 * same line, which is technically correct but architecturally misleading.
 *
 * This round splits outcomes into distinct, non-overlapping enums:
 *
 * UPLOAD:
 *   stored                    — 201, response body fully read
 *   duplicate                 — 200, response body fully read
 *   stored_response_dropped   — 201, body read timed out (relay-side success
 *                               + Tele2 Layer B drop; same idempotent retry
 *                               story as /relay/send PR-D0r)
 *   relay_too_large           — 413
 *   auth_fail / bad_request   — 401 / 400
 *   request_timeout           — status=none, exception before status capture
 *
 * DOWNLOAD:
 *   ok                — 200, body read, sha256 match
 *   sha_mismatch      — 200, body read, sha256 differs
 *   body_read_failed  — 200, body throw (Tele2 Layer B again on GET)
 *   not_found         — 404
 *   request_timeout   — status=none
 *   skipped           — upload didn't reach relay
 *
 * The new control size 2400 matches the current production chunk wire size.
 * If 2400 shows sha_mismatch the probe's decode logic is wrong; if 2400 is
 * `ok` then any larger-size failure is genuinely a Tele2 ceiling.
 *
 * Security: token is never logged. mediaId is logged as `.take(8)` only.
 * Expected/actual sha256 are logged as hex prefix (.take(16)) to help match
 * client and relay logs without exposing full ciphertext fingerprint.
 */
@OptIn(ExperimentalEncodingApi::class)
class Tele2CapProbe(
    private val tokenProvider: MediaAuthTokenProvider,
    private val relayBaseUrl: String,
    private val log: (String) -> Unit,
) {

    data class MediaProbeEndpoint(
        val id: String,
        val baseUrl: String,
        val tokenProvider: MediaAuthTokenProvider,
    )

    /** (size in bytes, number of attempts) — small for control, larger for confidence. */
    data class SizePlan(val bodySize: Int, val attempts: Int)

    enum class UploadOutcome {
        STORED, DUPLICATE, STORED_RESPONSE_DROPPED,
        RELAY_TOO_LARGE, AUTH_FAIL, BAD_REQUEST,
        REQUEST_TIMEOUT, OTHER_HTTP, FAIL_EXCEPTION,
    }

    enum class DownloadOutcome {
        OK, SHA_MISMATCH, BODY_READ_FAILED,
        NOT_FOUND, AUTH_FAIL, OTHER_HTTP,
        REQUEST_TIMEOUT, SKIPPED,
    }

    data class AttemptResult(
        val endpoint: String,
        val bodySize: Int,
        val attempt: Int,
        val mediaIdShort: String,
        val uploadStatus: Int?,
        val uploadElapsedMs: Long,
        val uploadOutcome: UploadOutcome,
        val uploadError: String?,
        val downloadStatus: Int?,
        val downloadElapsedMs: Long,
        val downloadOutcome: DownloadOutcome,
        val downloadError: String?,
        val downloadBytes: Int?,
        val expectedShaHex: String?,
        val actualShaHex: String?,
    ) {
        /** A chunk that the relay accepted, even if the response was lost en route. */
        val relayStoredOk: Boolean get() = uploadOutcome == UploadOutcome.STORED ||
            uploadOutcome == UploadOutcome.DUPLICATE ||
            uploadOutcome == UploadOutcome.STORED_RESPONSE_DROPPED
        /** End-to-end success including download verification. */
        val fullRoundtripOk: Boolean get() = relayStoredOk && downloadOutcome == DownloadOutcome.OK
    }

    private val endpoints: List<MediaProbeEndpoint> = listOf(
        MediaProbeEndpoint(
            id = "helsinki",
            baseUrl = relayBaseUrl,
            tokenProvider = tokenProvider,
        ),
        // Floki Romania: bridge2.phntm.pro is WebTunnel-only, not a media relay
        // (2026-05-18 — see deploy/bridge2/DEPLOY.md). When a second media
        // relay deploys, append a MediaProbeEndpoint(id="romania", …) here.
    )

    private val plan: List<SizePlan> = listOf(
        SizePlan(bodySize = 2_400, attempts = 3),   // control — current production
        SizePlan(bodySize = 5_500, attempts = 5),
        SizePlan(bodySize = 6_500, attempts = 5),
        SizePlan(bodySize = 7_168, attempts = 3),   // Tele2 ceiling probe — short
    )

    /**
     * Runs the full probe sweep across all configured endpoints.
     *
     * @param onProgress called with a human-readable status string for the UI.
     */
    suspend fun run(onProgress: suspend (String) -> Unit): List<AttemptResult> =
        withContext(Dispatchers.IO) {
            log(
                "M2C0_PROBE start endpoints=${endpoints.size} " +
                    "plan=${plan.joinToString(",") { "${it.bodySize}x${it.attempts}" }}"
            )
            onProgress("Running media probe…")

            val results = mutableListOf<AttemptResult>()

            for (endpoint in endpoints) {
                val token = endpoint.tokenProvider.acquireToken(
                    reason = "m2c0_probe",
                    staleToken = null,
                )
                if (token == null) {
                    log("M2C0_PROBE endpoint_skipped endpoint=${endpoint.id} reason=no_token")
                    onProgress("Endpoint ${endpoint.id}: no auth token — skipped")
                    continue
                }

                for (sp in plan) {
                    for (attempt in 1..sp.attempts) {
                        val r = runOneAttempt(endpoint, token, sp.bodySize, attempt)
                        results += r
                        logAttempt(r)
                    }
                    summarizePerSize(endpoint.id, sp.bodySize, results)
                }
            }

            emitFinalVerdict(endpoints.map { it.id }, plan, results)
            onProgress("Probe complete. Send logs.")
            results
        }

    // ── Per attempt ────────────────────────────────────────────────────────────

    private suspend fun runOneAttempt(
        endpoint: MediaProbeEndpoint,
        token: String,
        targetBodySize: Int,
        attempt: Int,
    ): AttemptResult {
        val mediaIdBytes = ByteArray(32).also { Random.Default.nextBytes(it) }
        val mediaId = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(mediaIdBytes)
        val mediaIdShort = mediaId.take(8)

        // ciphertext sized so HTTP body is ~targetBodySize.
        val fixedOverhead = 51 + mediaId.length + 1 + 1
        val b64Aligned = ((targetBodySize - fixedOverhead) / 4) * 4
        val rawBytes = (b64Aligned / 4) * 3
        val ciphertext = ByteArray(rawBytes).also { Random.Default.nextBytes(it) }
        val ciphertextB64 = Base64.encode(ciphertext)
        val body =
            "{\"media_id\":\"$mediaId\",\"idx\":0,\"total\":1,\"ciphertext_b64\":\"$ciphertextB64\"}"
        val expectedSha = sha256(ciphertext)
        val expectedShaHex = expectedSha.toHexShort()

        log(
            "M2C0_PROBE upload_start endpoint=${endpoint.id} size=$targetBodySize " +
                "attempt=$attempt mediaId=$mediaIdShort"
        )

        val uploadClient = freshClient()
        val uploadReq = Request.Builder()
            .url("${endpoint.baseUrl}/media/upload-chunk")
            .header("Authorization", "Bearer $token")
            .header("Connection", "close")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // ── Upload ─────────────────────────────────────────────────────────────
        val uploadStart = System.currentTimeMillis()
        var uploadStatus: Int? = null
        var uploadError: String? = null
        var bodyReadFailed = false
        try {
            uploadClient.newCall(uploadReq).execute().use { response: Response ->
                uploadStatus = response.code
                // Try to drain body; on Tele2 Layer B this is where we time out
                // EVEN AFTER status code arrived. We track this distinction.
                try {
                    response.body?.string()
                } catch (bodyExc: Throwable) {
                    bodyReadFailed = true
                    uploadError = "${bodyExc::class.simpleName}:${bodyExc.message?.take(80) ?: ""}"
                }
            }
        } catch (t: Throwable) {
            uploadError = "${t::class.simpleName}:${t.message?.take(80) ?: ""}"
        }
        val uploadElapsed = System.currentTimeMillis() - uploadStart

        val uploadOutcome = classifyUpload(uploadStatus, uploadError, bodyReadFailed)

        // ── Download ───────────────────────────────────────────────────────────
        if (!isRelayStored(uploadOutcome)) {
            return AttemptResult(
                endpoint = endpoint.id,
                bodySize = body.length,
                attempt = attempt,
                mediaIdShort = mediaIdShort,
                uploadStatus = uploadStatus,
                uploadElapsedMs = uploadElapsed,
                uploadOutcome = uploadOutcome,
                uploadError = uploadError,
                downloadStatus = null,
                downloadElapsedMs = 0L,
                downloadOutcome = DownloadOutcome.SKIPPED,
                downloadError = null,
                downloadBytes = null,
                expectedShaHex = expectedShaHex,
                actualShaHex = null,
            )
        }

        log(
            "M2C0_PROBE download_start endpoint=${endpoint.id} size=$targetBodySize " +
                "attempt=$attempt mediaId=$mediaIdShort"
        )

        val downloadClient = freshClient()
        val downloadReq = Request.Builder()
            .url("${endpoint.baseUrl}/media/chunk/$mediaId/0")
            .header("Authorization", "Bearer $token")
            .header("Connection", "close")
            .header("Cache-Control", "no-store")
            .get()
            .build()

        val downloadStart = System.currentTimeMillis()
        var downloadStatus: Int? = null
        var downloadError: String? = null
        var downloadBytes: Int? = null
        var actualSha: ByteArray? = null
        var dlBodyReadFailed = false
        try {
            downloadClient.newCall(downloadReq).execute().use { response: Response ->
                downloadStatus = response.code
                if (response.code == 200) {
                    try {
                        val raw = response.body?.string() ?: ""
                        if (raw.isNotEmpty()) {
                            val b64 = extractCiphertextB64(raw)
                            if (b64 != null) {
                                val downloaded = runCatching { Base64.decode(b64) }.getOrNull()
                                if (downloaded != null) {
                                    downloadBytes = downloaded.size
                                    actualSha = sha256(downloaded)
                                }
                            }
                        }
                    } catch (bodyExc: Throwable) {
                        dlBodyReadFailed = true
                        downloadError = "${bodyExc::class.simpleName}:${bodyExc.message?.take(80) ?: ""}"
                    }
                } else {
                    // drain body so the socket can close
                    runCatching { response.body?.string() }
                }
            }
        } catch (t: Throwable) {
            downloadError = "${t::class.simpleName}:${t.message?.take(80) ?: ""}"
        }
        val downloadElapsed = System.currentTimeMillis() - downloadStart

        val downloadOutcome = classifyDownload(
            status = downloadStatus,
            bodyReadFailed = dlBodyReadFailed,
            haveBytes = downloadBytes != null,
            shaMatch = actualSha?.contentEquals(expectedSha),
            error = downloadError,
        )

        return AttemptResult(
            endpoint = endpoint.id,
            bodySize = body.length,
            attempt = attempt,
            mediaIdShort = mediaIdShort,
            uploadStatus = uploadStatus,
            uploadElapsedMs = uploadElapsed,
            uploadOutcome = uploadOutcome,
            uploadError = uploadError,
            downloadStatus = downloadStatus,
            downloadElapsedMs = downloadElapsed,
            downloadOutcome = downloadOutcome,
            downloadError = downloadError,
            downloadBytes = downloadBytes,
            expectedShaHex = expectedShaHex,
            actualShaHex = actualSha?.toHexShort(),
        )
    }

    // ── Outcome classification ────────────────────────────────────────────────

    private fun classifyUpload(status: Int?, error: String?, bodyReadFailed: Boolean): UploadOutcome =
        when {
            status == 201 && !bodyReadFailed -> UploadOutcome.STORED
            status == 200 && !bodyReadFailed -> UploadOutcome.DUPLICATE
            status == 201 && bodyReadFailed -> UploadOutcome.STORED_RESPONSE_DROPPED
            status == 413 -> UploadOutcome.RELAY_TOO_LARGE
            status == 401 -> UploadOutcome.AUTH_FAIL
            status == 400 -> UploadOutcome.BAD_REQUEST
            status != null -> UploadOutcome.OTHER_HTTP
            error?.contains("Timeout", ignoreCase = true) == true ||
                error?.contains("Interrupted", ignoreCase = true) == true ->
                UploadOutcome.REQUEST_TIMEOUT
            error != null -> UploadOutcome.FAIL_EXCEPTION
            else -> UploadOutcome.OTHER_HTTP
        }

    private fun classifyDownload(
        status: Int?,
        bodyReadFailed: Boolean,
        haveBytes: Boolean,
        shaMatch: Boolean?,
        error: String?,
    ): DownloadOutcome = when {
        status == 200 && shaMatch == true -> DownloadOutcome.OK
        status == 200 && shaMatch == false -> DownloadOutcome.SHA_MISMATCH
        status == 200 && bodyReadFailed -> DownloadOutcome.BODY_READ_FAILED
        status == 200 && !haveBytes -> DownloadOutcome.BODY_READ_FAILED  // could not parse
        status == 404 -> DownloadOutcome.NOT_FOUND
        status == 401 -> DownloadOutcome.AUTH_FAIL
        status != null -> DownloadOutcome.OTHER_HTTP
        error?.contains("Timeout", ignoreCase = true) == true ||
            error?.contains("Interrupted", ignoreCase = true) == true ->
            DownloadOutcome.REQUEST_TIMEOUT
        else -> DownloadOutcome.REQUEST_TIMEOUT
    }

    private fun isRelayStored(u: UploadOutcome): Boolean = when (u) {
        UploadOutcome.STORED, UploadOutcome.DUPLICATE, UploadOutcome.STORED_RESPONSE_DROPPED -> true
        else -> false
    }

    // ── Logging ────────────────────────────────────────────────────────────────

    private fun logAttempt(r: AttemptResult) {
        log(
            "M2C0_PROBE upload_result endpoint=${r.endpoint} size=${r.bodySize} " +
                "attempt=${r.attempt} mediaId=${r.mediaIdShort} " +
                "status=${r.uploadStatus ?: "none"} outcome=${r.uploadOutcome.name.lowercase()} " +
                "elapsedMs=${r.uploadElapsedMs}" +
                (r.uploadError?.let { " error=${it.take(60)}" } ?: "")
        )
        if (r.downloadOutcome != DownloadOutcome.SKIPPED) {
            log(
                "M2C0_PROBE download_result endpoint=${r.endpoint} size=${r.bodySize} " +
                    "attempt=${r.attempt} mediaId=${r.mediaIdShort} " +
                    "status=${r.downloadStatus ?: "none"} outcome=${r.downloadOutcome.name.lowercase()} " +
                    "elapsedMs=${r.downloadElapsedMs} " +
                    "bytes=${r.downloadBytes ?: "?"} " +
                    "expectedSha=${r.expectedShaHex ?: "?"} actualSha=${r.actualShaHex ?: "?"}" +
                    (r.downloadError?.let { " error=${it.take(60)}" } ?: "")
            )
        }
    }

    private fun summarizePerSize(
        endpointId: String,
        size: Int,
        all: List<AttemptResult>,
    ) {
        val perSize = all.filter { it.endpoint == endpointId && it.bodySize in (size - 8)..(size + 8) }
        if (perSize.isEmpty()) return
        val n = perSize.size
        val relayStored = perSize.count { it.relayStoredOk }
        val fullOk = perSize.count { it.fullRoundtripOk }
        val responseDropped = perSize.count { it.uploadOutcome == UploadOutcome.STORED_RESPONSE_DROPPED }
        val downloadBodyFailed = perSize.count { it.downloadOutcome == DownloadOutcome.BODY_READ_FAILED }
        val uploadAvg = perSize.map { it.uploadElapsedMs }.averageOrZero()
        val downloadAvg = perSize.filter { it.relayStoredOk }.map { it.downloadElapsedMs }.averageOrZero()
        val uploadP95 = perSize.map { it.uploadElapsedMs }.p95OrZero()

        val verdict = when {
            fullOk == n -> "stable_full"
            relayStored == n && responseDropped == n -> "stored_but_resp_dropped"
            relayStored >= n - 1 -> "borderline"
            else -> "unstable"
        }
        log(
            "M2C0_SUMMARY endpoint=$endpointId size=$size " +
                "relayStored=$relayStored/$n fullRoundtrip=$fullOk/$n " +
                "respDropped=$responseDropped dlBodyFail=$downloadBodyFailed " +
                "uploadAvgMs=$uploadAvg downloadAvgMs=$downloadAvg uploadP95Ms=$uploadP95 " +
                "verdict=$verdict"
        )
    }

    private fun emitFinalVerdict(
        endpointIds: List<String>,
        plan: List<SizePlan>,
        all: List<AttemptResult>,
    ) {
        // For M2c production decision we MUST require both upload AND download
        // to succeed. Upload-only safety (relay accepted + stored) is a
        // necessary but insufficient signal — if the receiver cannot download
        // a chunk of size N over the same hostile network, shipping N as the
        // production chunk size strands every received voice in
        // "Downloading…" forever (the actual Test #62-65 failure mode).
        //
        // largestStoredSafe is therefore reported separately as a diagnostic
        // (interesting to know which sizes the relay stored even when response
        // body dropped on the wire — informs the sender's idempotent-retry
        // design), but the recommendedRawChunkBytes is sourced from
        // largestFullRoundtrip, not largestStoredSafe.
        data class EndpointBest(
            val id: String,
            val largestStoredSafe: Int?,        // upload-only (diagnostic)
            val largestFullRoundtrip: Int?,     // upload + download (production decision)
        )

        val bests = endpointIds.map { eid ->
            val largestStored = plan.map { it.bodySize }.sortedDescending().firstOrNull { sz ->
                val attempts = all.filter { it.endpoint == eid && it.bodySize in (sz - 8)..(sz + 8) }
                attempts.isNotEmpty() && attempts.all { it.relayStoredOk }
            }
            val largestFull = plan.map { it.bodySize }.sortedDescending().firstOrNull { sz ->
                val attempts = all.filter { it.endpoint == eid && it.bodySize in (sz - 8)..(sz + 8) }
                attempts.isNotEmpty() && attempts.all { it.fullRoundtripOk }
            }
            EndpointBest(eid, largestStored, largestFull)
        }

        val winner = bests.maxByOrNull { it.largestFullRoundtrip ?: 0 }
        if (winner == null || winner.largestFullRoundtrip == null) {
            val largestUploadOnlyAcrossAll = bests.maxOfOrNull { it.largestStoredSafe ?: 0 } ?: 0
            val anomaly = if (largestUploadOnlyAcrossAll > 0) {
                "uploads alone reached $largestUploadOnlyAcrossAll but downloads failed — likely Tele2 Layer B on GET body or probe decode bug; check 2400 control row"
            } else {
                "no endpoint completed full upload+download or even upload for any size"
            }
            log(
                "M2C0_FINAL bestEndpoint=none stableMaxBodyFullRoundtrip=0 " +
                    "largestUploadOnly=$largestUploadOnlyAcrossAll " +
                    "recommendedRawChunkBytes=1700 " +
                    "reason=\"$anomaly. M2c production stays at 1700.\""
            )
            return
        }

        val full = winner.largestFullRoundtrip!!
        val storedOnly = winner.largestStoredSafe ?: -1
        val recommendedRaw = when {
            full >= 7_168 -> 5_000
            full >= 6_500 -> 4_500
            full >= 5_500 -> 3_800
            else -> 1_700
        }
        val diagnostic = if (storedOnly > full) {
            " Upload-only ceiling is HIGHER ($storedOnly) — sender could push larger chunks " +
                "if response-drop is tolerated via idempotent retry, but receiver download " +
                "is the binding constraint, so M2c picks based on full roundtrip."
        } else ""
        log(
            "M2C0_FINAL bestEndpoint=${winner.id} stableMaxBodyFullRoundtrip=$full " +
                "largestUploadOnly=$storedOnly " +
                "recommendedRawChunkBytes=$recommendedRaw " +
                "reason=\"M2c production decision uses fullRoundtripOk (upload AND download " +
                "5/5 per size).$diagnostic\""
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun freshClient(): OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    private fun ByteArray.toHexShort(): String =
        joinToString("") { "%02x".format(it) }.take(16)

    private fun extractCiphertextB64(json: String): String? {
        val key = "\"ciphertext_b64\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val valueStart = start + key.length
        val end = json.indexOf('"', valueStart)
        if (end < 0) return null
        return json.substring(valueStart, end)
    }

    private fun List<Long>.averageOrZero(): Int =
        if (isEmpty()) 0 else (sum() / size).toInt()

    private fun List<Long>.p95OrZero(): Int {
        if (isEmpty()) return 0
        val sorted = sorted()
        val idx = ((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx].toInt()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
