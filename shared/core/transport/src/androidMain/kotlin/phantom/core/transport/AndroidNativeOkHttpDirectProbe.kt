// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Native OkHttp probe for [TransportKind.Direct] clearnet reachability.
 *
 * Replaces the Ktor-engine path for Direct+null-socks probes. The Ktor engine
 * uses a shared connection pool and OkHttp dispatcher that can retain a stale
 * TCP connection from a previous session, causing the probe to return ok=false
 * on LTE even when the relay is reachable (Test #47, Tele2 LTE, 2026-05-15:
 * probe_returned kind=Direct ok=false elapsedMs=4092 while SSH curl confirmed
 * HTTP/2 200 in 48 ms with no auth).
 *
 * This probe creates a fresh OkHttpClient per invocation with:
 * - HTTP/1.1 pinned (no connection coalescing or ALPN negotiation ambiguity)
 * - ConnectionPool(0, 1, ms) — zero idle connections, nothing shared across calls
 * - retryOnConnectionFailure=false — we do our own controlled retry loop
 * - Connection: close header — no keep-alive, ensures body is drained immediately
 * - callTimeout=10s per attempt, MAX_ATTEMPTS=2 with 400ms backoff
 *
 * Reality and Tor probes remain on the Ktor path via [KtorTransportProbe].
 *
 * PR-R0.3, ADR-020.
 */
internal class AndroidNativeOkHttpDirectProbe(
    private val url: String,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    suspend fun run(): Boolean = withContext(Dispatchers.IO) {
        var attempt = 1
        while (true) {
            val startMs = System.currentTimeMillis()
            Log.i(TAG, "PROBE_TRACE direct.okhttp_start attempt=$attempt url=$url")

            val client = buildClient()
            val request = Request.Builder()
                .url(url)
                .header("Connection", "close")
                .get()
                .build()

            val result = runCatching {
                client.newCall(request).execute().use { response ->
                    val elapsedMs = System.currentTimeMillis() - startMs
                    val ok = response.code in 200..299
                    // Log response BEFORE the use{} block closes — per Vladislav 2026-05-16
                    Log.i(
                        TAG,
                        "PROBE_TRACE direct.okhttp_response status=${response.code} " +
                            "elapsedMs=$elapsedMs attempt=$attempt",
                    )
                    ok
                    // response.body is auto-closed by use{} — guardrail #3 satisfied
                }
            }

            val elapsedMs = System.currentTimeMillis() - startMs

            if (result.isSuccess) {
                val ok = result.getOrThrow()
                if (!ok) {
                    Log.i(TAG, "PROBE_TRACE direct.result ok=false totalMs=$elapsedMs attempts=$attempt")
                    return@withContext false
                }
                Log.i(TAG, "PROBE_TRACE direct.result ok=true totalMs=$elapsedMs attempts=$attempt")
                return@withContext true
            }

            val ex = result.exceptionOrNull()!!
            Log.w(
                TAG,
                "PROBE_TRACE direct.okhttp_fail errorType=${ex::class.simpleName} " +
                    "message=${ex.message?.take(120)} elapsedMs=$elapsedMs attempt=$attempt",
            )

            if (attempt >= MAX_ATTEMPTS) {
                Log.i(TAG, "PROBE_TRACE direct.result ok=false totalMs=$elapsedMs attempts=$attempt")
                return@withContext false
            }

            Log.i(TAG, "PROBE_TRACE direct.retry_scheduled delayMs=$retryDelayMs attempt=$attempt")
            delay(retryDelayMs)
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .retryOnConnectionFailure(false)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        // PR-LTE-NETCHANGE1 (2026-05-28): attach the same phase-by-phase
        // event listener that the Ktor-based Reality/Tor probes already
        // use. Without it, a Direct probe failure on Tele2 LTE shows up
        // as a single `direct.okhttp_fail errorType=...` line with no
        // signal about WHICH phase (DNS / TCP / TLS / request / response)
        // actually died. Test #88 Scenario D in particular needs phase
        // attribution to explain a 5.8-minute fallback to Tor.
        //
        // The listener emits under tag `TransportProbe` (see ProbeEvent
        // Listener at KtorTransportProbe.kt:170-268); the existing
        // PROBE_TRACE direct.* lines from this class remain under tag
        // `TransportManager`. Both tags belong in any transport-diagnostic
        // logcat capture per `feedback_logcat_tag_coverage_2026_05_27.md`.
        .eventListener(ProbeEventListener(TransportKind.Direct))
        .build()

    companion object {
        private const val TAG = "TransportManager"
        const val CALL_TIMEOUT_MS = 10_000L
        const val RETRY_DELAY_MS = 400L
        const val MAX_ATTEMPTS = 2
    }
}
