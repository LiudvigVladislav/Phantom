// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * OkHttp [EventListener] emitting one phase-event log line per
 * connection-lifecycle event, for diagnostic-only instrumentation of the
 * REST fallback transport (`AndroidNativeOkHttpRestFallbackTransport`) and
 * the WS reconnect HTTPS auth path (`KtorRelayTransport` via the
 * `createHttpClientFactory()` OkHttp client).
 *
 * Commit 1 of PR-WS-HEALTH-STATE1, per `docs/tracks/ws-health-state.md`
 * §Implementation plan §Commit 1: NO behaviour change. The listener
 * is a sibling of [ProbeEventListener] used by [KtorTransportProbe];
 * shape mirrored to keep operator pattern-matching consistent across
 * `PROBE_TRACE` and the new phase logs.
 *
 * # Log line format
 *
 * ```
 * <TAG>: <keyword> phase_event op=<op> key=<correlationKey> event=<phase> [more=...] elapsedMs=<n>
 * ```
 *
 * - `tag`     — Android log tag, e.g. `PhantomHybrid` for REST fallback or
 *               `PhantomRelay` for WS reconnect auth.
 * - `keyword` — top-level log keyword for grep filtering, e.g.
 *               `REST_TRACE` for REST fallback (lines up with the existing
 *               `REST_TRACE send_start` / `REST_TRACE poll_call` body) or
 *               `RELAY_TRACE` for WS reconnect (sits alongside the existing
 *               `[gen=N s=M] Auth handshake failed` records that share the
 *               `PhantomRelay` tag).
 * - `op`      — semantic operation. REST fallback passes one of
 *               `session` / `send` / `poll` / `ack`. WS path passes
 *               `ws_auth` for the `/auth/challenge` HTTPS GET and
 *               `ws_upgrade` for the subsequent WebSocket upgrade.
 * - `key`     — correlation token. REST fallback passes the idempotency
 *               key when present, the URL when not. WS path passes the
 *               OkHttp `Call` identity hash so events of the same call
 *               are linkable. Generation/session correlation for the WS
 *               path is done by timestamp against the existing
 *               `[gen=N s=M]` lines in the same log; not injected here
 *               to avoid the cross-module mutable shared-state plumbing
 *               an inline tag would require — see commit message and
 *               class doc for the deferred-improvement note.
 *
 * # Threading
 *
 * OkHttp invokes all [EventListener] callbacks on the same dispatcher
 * thread for a given [Call], so the mutable phase-start timestamps in this
 * listener do not need synchronisation when used per-call.
 *
 * Two creation patterns:
 *
 * - REST fallback: one listener instance per call (`buildClient` runs per
 *   call in `AndroidNativeOkHttpRestFallbackTransport`).
 * - WS path: an `eventListenerFactory` lambda yields a fresh listener per
 *   call (the WS OkHttpClient is shared across the `/auth/challenge` GET
 *   and the WebSocket upgrade, so the factory inspects the URL to pick
 *   the `op` tag).
 */
internal class HttpPhaseEventListener(
    private val tag: String,
    private val keyword: String,
    private val op: String,
    private val correlationKey: String,
) : EventListener() {

    private var callStartMs: Long = 0L
    private var dnsStartMs: Long = 0L
    private var connectStartMs: Long = 0L
    private var secureConnectStartMs: Long = 0L
    private var responseHeadersStartMs: Long = 0L
    private var responseBodyStartMs: Long = 0L

    private fun elapsed(): Long = System.currentTimeMillis() - callStartMs

    private fun log(eventBody: String) =
        Log.i(tag, "$keyword phase_event op=$op key=$correlationKey $eventBody")

    private fun logW(eventBody: String) =
        Log.w(tag, "$keyword phase_event op=$op key=$correlationKey $eventBody")

    override fun callStart(call: Call) {
        callStartMs = System.currentTimeMillis()
        log("event=callStart")
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartMs = System.currentTimeMillis()
        log("event=dnsStart domain=$domainName")
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        val elapsedMs = System.currentTimeMillis() - dnsStartMs
        val addresses = inetAddressList.joinToString(
            separator = ",", prefix = "[", postfix = "]",
        ) { it.hostAddress.orEmpty() }
        log("event=dnsEnd domain=$domainName addresses=$addresses elapsedMs=$elapsedMs")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartMs = System.currentTimeMillis()
        val host = inetSocketAddress.address?.hostAddress ?: inetSocketAddress.hostName
        log("event=connectStart host=$host port=${inetSocketAddress.port}")
    }

    override fun secureConnectStart(call: Call) {
        secureConnectStartMs = System.currentTimeMillis()
        log("event=secureConnectStart")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        val elapsedMs = System.currentTimeMillis() - secureConnectStartMs
        val tlsVersion = handshake?.tlsVersion?.javaName ?: "unknown"
        val cipher = handshake?.cipherSuite?.javaName ?: "unknown"
        log("event=secureConnectEnd handshake=$tlsVersion cipher=$cipher elapsedMs=$elapsedMs")
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        val elapsedMs = System.currentTimeMillis() - connectStartMs
        log("event=connectEnd elapsedMs=$elapsedMs")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        val elapsedMs = System.currentTimeMillis() - connectStartMs
        logW(
            "event=connectFailed exception=${ioe::class.simpleName} " +
                "message=${ioe.message} elapsedMs=$elapsedMs",
        )
    }

    override fun requestHeadersStart(call: Call) {
        log("event=requestHeadersStart")
    }

    override fun responseHeadersStart(call: Call) {
        responseHeadersStartMs = System.currentTimeMillis()
        log("event=responseHeadersStart elapsedMs=${elapsed()}")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val elapsedMs = System.currentTimeMillis() - responseHeadersStartMs
        log("event=responseHeadersEnd status=${response.code} elapsedMs=$elapsedMs")
    }

    // Round 12 step 2 — body-read phase events. The S6 council on
    // d395f682 identified that the gap between `responseHeadersEnd`
    // and `callFailed` (~9.3 s in the captured incident) is where
    // body bytes either arrive, partially arrive, or never arrive,
    // and that the absence of dedicated body-phase events made the
    // four competing failure-mode hypotheses (Tele2 byte-budget,
    // client timeout too tight, server defers body, Caddy/TLS
    // intermediate) undiscriminatable in field evidence.
    //
    // OkHttp's `responseBodyStart` fires when the response body
    // begins to be consumed (the source becomes readable);
    // `responseBodyEnd` fires only on a SUCCESSFUL full read.
    // The combination forms a partial discriminator on its own:
    //
    //   * neither fires + callFailed       — body read never began
    //                                        (server-side hold, TLS
    //                                        stall before body chunk
    //                                        emission, etc.)
    //   * responseBodyStart fires + callFailed
    //                                      — body read began but did
    //                                        not complete (a real
    //                                        per-byte stall mid-read,
    //                                        the byte-budget cutoff
    //                                        class).
    //   * both fire + callEnd              — body read completed
    //                                        within budget.
    //
    // The per-chunk byte accounting that distinguishes "stalled at
    // 0 bytes" from "stalled at N bytes" is delivered by the
    // separate `DebugBodyByteLoggingInterceptor` (debug-only).
    override fun responseBodyStart(call: Call) {
        responseBodyStartMs = System.currentTimeMillis()
        log("event=responseBodyStart elapsedMs=${elapsed()}")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        val elapsedMs = System.currentTimeMillis() - responseBodyStartMs
        log("event=responseBodyEnd byteCount=$byteCount elapsedMs=$elapsedMs")
    }

    override fun callEnd(call: Call) {
        log("event=callEnd totalMs=${elapsed()}")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        logW("event=callFailed exception=${ioe::class.simpleName} totalMs=${elapsed()}")
    }
}
