// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

/**
 * Round 12 step 2 — debug-only per-chunk response-body byte
 * accounting for the REST `/relay/poll` path.
 *
 * Together with the `responseBodyStart` / `responseBodyEnd` events
 * emitted by [HttpPhaseEventListener] (also Round 12 step 2), this
 * interceptor lets a field engineer discriminate the four competing
 * S6 failure-mode hypotheses identified by the council on
 * `d395f682`:
 *
 *   1. Tele2 LTE drops response body bytes mid-transit (byte-budget
 *      cutoff on the response direction).
 *   2. Client `callTimeout` / `readTimeout` too tight for the relay's
 *      hold-then-body shape.
 *   3. Server returns headers immediately but defers body in a way
 *      that exceeds the client timeout.
 *   4. Intermediate TLS / Caddy proxy condition.
 *
 * The interceptor wraps the response body's `Source` in a
 * [LoggingForwardingSource] that emits one
 * `REST_TRACE phase_event op=poll event=body_chunk
 * cumulative_bytes=<n> chunk_bytes=<m> elapsedMs=<t>` log line per
 * Okio `read(...)` call. The cumulative count is the load-bearing
 * value: a probe that times out at `cumulative_bytes=0` rules out
 * hypotheses 2 and 4 in their generic form; a probe that times out
 * at `cumulative_bytes=1024` confirms hypothesis 1 specifically and
 * narrows the byte-budget cutoff to the response direction.
 *
 * Production safety:
 *
 *   * This class is constructed only when the AppContainer wires
 *     `debugBodyLogging = true` into [AndroidNativeOkHttpRestFallbackTransport],
 *     which itself is gated on `BuildConfig.DEBUG` at the
 *     application-module boundary.
 *   * The interceptor's `intercept` method copies the response
 *     metadata verbatim (status, headers, contentLength,
 *     contentType) — only the body source is wrapped. The wrapping
 *     is transparent to the caller; the orchestrator reads the body
 *     via [okhttp3.ResponseBody.string] or similar exactly as
 *     before.
 *   * The cumulative byte count is post-padding — it reflects the
 *     wire-shape bytes, the same value a passive network observer
 *     would see. No pre-padding count, no payload content, no
 *     identity-derived field is ever logged. The byte count alone
 *     is not a content disclosure beyond what the relay already
 *     emits in its own `rest_poll_returned` log line.
 *   * The Round 12 council's security cross-check (BS-2) flagged
 *     `byteCount` timeseries as a potential metadata signal IF
 *     diagnostic logs are shipped to a cloud backend. PHANTOM's
 *     `PhantomHybrid`-tagged debug logs are device-local in release
 *     builds (the `debugBodyLogging` flag pins to `false` in
 *     release), so this surface does not exist in production.
 */
internal class DebugBodyByteLoggingInterceptor(
    private val tag: String = "PhantomHybrid",
    private val keyword: String = "REST_TRACE",
    private val op: String = "poll",
    private val correlationKey: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val originalBody = response.body ?: return response
        val originalSource = originalBody.source()
        val wrappedSource = LoggingForwardingSource(
            delegate = originalSource,
            tag = tag,
            keyword = keyword,
            op = op,
            correlationKey = correlationKey,
        )
        val wrappedBody = wrappedSource.buffer().asResponseBody(
            contentType = originalBody.contentType(),
            contentLength = originalBody.contentLength(),
        )
        return response.newBuilder().body(wrappedBody).build()
    }
}

/**
 * Okio [ForwardingSource] that records every successful `read(...)`
 * call's byte count and the cumulative total received so far. Emits
 * a single `REST_TRACE phase_event` log line per chunk, under the
 * `PhantomHybrid` tag so the new lines sort with the existing
 * REST_TRACE body. A zero-byte read (end of stream) is logged once
 * with `chunk_bytes=0` to mark the EOF event; subsequent zero reads
 * are suppressed to avoid log spam.
 */
private class LoggingForwardingSource(
    delegate: Source,
    private val tag: String,
    private val keyword: String,
    private val op: String,
    private val correlationKey: String,
) : ForwardingSource(delegate) {

    private val startMs: Long = System.currentTimeMillis()
    private var cumulativeBytes: Long = 0L
    private var eofLogged: Boolean = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        val n = super.read(sink, byteCount)
        val elapsedMs = System.currentTimeMillis() - startMs
        when {
            n > 0L -> {
                cumulativeBytes += n
                Log.i(
                    tag,
                    "$keyword phase_event op=$op key=$correlationKey event=body_chunk " +
                        "cumulative_bytes=$cumulativeBytes chunk_bytes=$n elapsedMs=$elapsedMs",
                )
            }
            n == -1L && !eofLogged -> {
                eofLogged = true
                Log.i(
                    tag,
                    "$keyword phase_event op=$op key=$correlationKey event=body_eof " +
                        "cumulative_bytes=$cumulativeBytes elapsedMs=$elapsedMs",
                )
            }
        }
        return n
    }
}
