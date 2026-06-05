// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * RC-DIRECT-STABILITY1 §10 T2 — slow-POST byte-threshold diagnostic arm.
 *
 * Design locked in [docs/tracks/rc-direct-stability1.md] §10 T2 mini-lock.
 * This class is **structurally different** from the WebSocket arms
 * ([RcDirectArmB], [RcDirectArmC], [RcDirectArmD], [RcDirectArmA2]) and the
 * Caddy-bypass arm ([RcDirectArmA]). T2 is a **one-shot** diagnostic: one
 * HTTP POST to the relay's `/diag/slow-post` endpoint that streams 40 960
 * bytes (8 chunks × 5120 bytes) over ~70-80 seconds (10 s delay between
 * chunks, `sink.flush()` after each chunk). After the POST completes (or
 * aborts), the class logs the outcome and the job terminates — no
 * reconnect loop, no per-session epoch, no heartbeat.
 *
 * **Goal — discriminator for hypothesis 5 in the Arm D outcome open set
 * (extended to 5 after the Independent Audit input 2026-06-05).** Does
 * the carrier path "freeze" the TCP connection at a cumulative-bytes
 * threshold (`net4people/bbs Issue #490` 14-32 KB on RU mobile operators
 * including Tele2)? The diagnostic answers in three outcomes:
 *
 *   - **Relay receives 14-32 KB and aborts** → byte-threshold hypothesis
 *     strongly confirmed on this path. Architectural implication: even
 *     long SSE responses die at the threshold; Matrix-style 25-sec
 *     long-poll becomes the mandatory primary realtime pattern; inside
 *     Reality must also use short-cycle (mux / XHTTP).
 *   - **Relay receives > 32 KB but < 40 KB and aborts** → same class
 *     of hypothesis (cumulative-bytes freeze) with a different threshold
 *     value.
 *   - **Relay receives all 40 960 bytes and responds 200 OK** →
 *     byte-threshold hypothesis refuted on HTTP POST through Caddy.
 *     Architectural implication: kill is in hypotheses 1/2/3/4 (OkHttp
 *     egress, Caddy WS framing, carrier stateful inspection, or
 *     interaction); Arm G (WS-over-Reality) is the primary next test.
 *
 * **Primary discriminator = relay `total_received`, NOT Android
 * `total_sent`.** Per Vladislav 2026-06-06 hard gate 2: `write()`
 * accepting bytes only proves OkHttp queue-accept; it does NOT prove
 * physical egress from the device radio. The verdict counter is the
 * server-side log entry from `services/relay/src/routes.rs:slow_post_diag`,
 * which logs `event=slow_post_chunk_received total_bytes=N` per accepted
 * chunk and `event=slow_post_aborted total_bytes=N reason=...` if the
 * stream ends mid-body.
 *
 * **SEPARATE OkHttp profile from WebSocket arms.** Per Vladislav
 * 2026-06-06 hard gate 1: the WS arms' `callTimeout(10s)` would kill
 * this slow POST in 10 seconds and produce garbage data. T2's client
 * uses `connectTimeout=5s`, `writeTimeout=30s`, `readTimeout=60s`,
 * `callTimeout=180s`. Each chunk is written through a `RequestBody`
 * that explicitly calls `sink.flush()` after each chunk emit so the
 * chunk physically egresses (or is queued for egress) before the
 * `delay(10_000)` between chunks fires.
 *
 * **Sequential, not parallel.** Per Inv-ParallelArmIsolation, this arm
 * runs only when [BuildConfig.DEBUG_T2_SLOW_POST_URL] is non-empty AND
 * [BuildConfig.DEBUG] is true. The wire-up site
 * (`PhantomMessagingService.onStartCommand`) short-circuits the
 * production Hybrid Ktor `transport.connect(...)` path in that case,
 * so production and diagnostic traffic never collide on the relay.
 *
 * **One-shot — no reconnect.** Unlike Arms A/A.2/B/C/D, this class
 * does NOT loop; it executes exactly one POST and the [runJob]
 * completes when the POST finishes (success or failure). The foreground
 * Service stays alive (it's the diagnostic host) but the T2 arm itself
 * is done after one run. Re-running requires killing the app and
 * starting it again with the BuildConfig flag still set.
 *
 * **No production behaviour change.** Wire-up sites (AppContainer +
 * PhantomMessagingService) gate every reference behind
 * `BuildConfig.DEBUG && BuildConfig.DEBUG_T2_SLOW_POST_URL.isNotEmpty()`.
 * Release builds (`!BuildConfig.DEBUG`) ignore the flag entirely; the
 * release BuildConfig block pins `DEBUG_T2_SLOW_POST_URL` to `""` as
 * defence-in-depth. The class is `internal` to the diagnostic package
 * so production code cannot accidentally reference it.
 */
internal class T2SlowPostDiag(
    private val endpointUrl: String,
    private val scope: CoroutineScope,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        // Per Vladislav 2026-06-06 hard gate 1: SEPARATE timeouts from the
        // WebSocket arms. The slow POST runs ~70-80 s; callTimeout(10s)
        // from the WS arms' builder would kill it long before the byte
        // threshold could trigger and skew the verdict.
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    @Volatile private var runJob: Job? = null

    fun start() {
        if (runJob?.isActive == true) {
            Log.w(TAG, "T2_SLOW_POST_start_ignored already_running")
            return
        }
        runJob = scope.launch {
            Log.i(
                TAG,
                "T2_SLOW_POST_armed " +
                    "endpoint_url=$endpointUrl " +
                    "total_bytes=$TOTAL_BYTES " +
                    "chunk_bytes=$CHUNK_BYTES " +
                    "chunk_count=$CHUNK_COUNT " +
                    "delay_ms_between_chunks=$DELAY_MS_BETWEEN_CHUNKS " +
                    "connect_timeout_ms=5000 " +
                    "write_timeout_ms=30000 " +
                    "read_timeout_ms=60000 " +
                    "call_timeout_ms=180000 " +
                    "protocols=HTTP_1_1",
            )
            runOneShot()
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        Log.i(TAG, "T2_SLOW_POST_stopped")
    }

    private suspend fun runOneShot() {
        val startedMs = System.currentTimeMillis()
        // RequestBody that streams CHUNK_COUNT chunks of CHUNK_BYTES,
        // flushing after each chunk and sleeping DELAY_MS_BETWEEN_CHUNKS
        // between chunks. The flush is what makes this a real streaming
        // chunked POST — without it OkHttp may buffer the entire body
        // before sending the first byte, which would defeat the byte-
        // threshold experiment.
        val body = SlowPostRequestBody(startedMs)
        val request = Request.Builder()
            .url(endpointUrl)
            .header(HEADER_NAME, HEADER_VALUE)
            .post(body)
            .build()

        try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { resp ->
                    val responseBody = resp.body?.string().orEmpty()
                    val elapsedMs = System.currentTimeMillis() - startedMs
                    if (resp.isSuccessful) {
                        Log.i(
                            TAG,
                            "T2_SLOW_POST_completed " +
                                "response_status=${resp.code} " +
                                "total_sent=${body.totalSent} " +
                                "elapsed_ms=$elapsedMs " +
                                "response_body=${responseBody.take(200)}",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "T2_SLOW_POST_non_2xx " +
                                "response_status=${resp.code} " +
                                "total_sent=${body.totalSent} " +
                                "elapsed_ms=$elapsedMs " +
                                "response_body=${responseBody.take(200)}",
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val elapsedMs = System.currentTimeMillis() - startedMs
            Log.w(
                TAG,
                "T2_SLOW_POST_failed " +
                    "t=${t::class.simpleName} " +
                    "msg=${t.message?.take(200)} " +
                    "total_sent=${body.totalSent} " +
                    "elapsed_ms=$elapsedMs",
            )
        }
    }

    /**
     * Streams [CHUNK_COUNT] chunks of [CHUNK_BYTES] zero-bytes through
     * the OkHttp `BufferedSink`, calling `flush()` after each chunk and
     * `delay()` between chunks.
     *
     * `totalSent` is updated synchronously per chunk write so the
     * outer `runOneShot` can log the secondary client-side counter at
     * the moment of failure. This counter is **secondary** — the
     * verdict counter is the relay-side `total_received` log entry
     * (Vladislav 2026-06-06 hard gate 2).
     */
    private inner class SlowPostRequestBody(
        private val startedMs: Long,
    ) : RequestBody() {
        @Volatile var totalSent: Long = 0L

        override fun contentType() = CONTENT_TYPE
        override fun contentLength(): Long = TOTAL_BYTES.toLong()

        override fun writeTo(sink: BufferedSink) {
            val chunk = ByteArray(CHUNK_BYTES)
            for (i in 0 until CHUNK_COUNT) {
                sink.write(chunk)
                sink.flush()
                totalSent += CHUNK_BYTES.toLong()
                Log.i(
                    TAG,
                    "T2_SLOW_POST_chunk_sent " +
                        "seq=${i + 1} " +
                        "chunk_bytes=$CHUNK_BYTES " +
                        "total_sent=$totalSent " +
                        "elapsed_ms=${System.currentTimeMillis() - startedMs}",
                )
                if (i + 1 < CHUNK_COUNT) {
                    // The diagnostic depends on the inter-chunk gap. Use
                    // Thread.sleep here because `writeTo` is called on
                    // OkHttp's I/O thread (not a coroutine context).
                    // Cooperative cancellation by the outer call is
                    // handled via `Call.cancel()` from `stop()` — OkHttp
                    // will throw an IOException on the next write/flush.
                    Thread.sleep(DELAY_MS_BETWEEN_CHUNKS)
                }
            }
        }
    }

    companion object {
        private const val TAG = "T2_SLOW_POST"

        /** Total body length — locked at 40 960 = 8 × 5120 bytes. */
        const val TOTAL_BYTES: Int = 40_960

        /** Per-chunk byte count — locked at 5120 bytes. */
        const val CHUNK_BYTES: Int = 5_120

        /** Chunk count — locked at 8 (5120 × 8 = 40 960). */
        const val CHUNK_COUNT: Int = 8

        /**
         * Delay between chunk writes — locked at 10 000 ms (10 s). Total
         * run time = 8 chunks × 10 s between = ~70-80 s including the
         * inter-chunk gap (the last chunk has no trailing delay).
         */
        const val DELAY_MS_BETWEEN_CHUNKS: Long = 10_000L

        /** Required request header (anti-stray-POST guard, Vladislav gate 4). */
        const val HEADER_NAME: String = "X-Phantom-Diag"
        const val HEADER_VALUE: String = "slow-post-v1"

        private val CONTENT_TYPE = "application/octet-stream".toMediaType()
    }
}
