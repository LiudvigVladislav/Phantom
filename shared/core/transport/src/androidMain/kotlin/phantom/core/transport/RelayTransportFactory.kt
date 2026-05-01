// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol

// Singleton OkHttpClient — exposed so KtorRelayTransport can call
// dispatcher.cancelAll() to forcibly tear down a hung WebSocket on
// application-level pong timeout. Without that escape hatch, Ktor's
// OkHttpWebsocketSession.outgoing actor calls websocket.close() in its
// finally block on cancellation, which initiates a graceful WS close and
// blocks for OkHttp's hardcoded CANCEL_AFTER_CLOSE_MILLIS = 60_000L while
// it waits for the server's matching CLOSE frame.
private val sharedOkHttpClient: OkHttpClient = OkHttpClient.Builder()
    // OkHttp WebSocket transport-level ping is DISABLED.
    //
    // Why: pingInterval is BOTH the ping cadence AND the pong-timeout in one
    // knob — OkHttp force-cancels the socket if no pong returns within
    // pingInterval. On slow cellular, sending a large envelope (e.g. an 83 KB
    // base64-inlined voice note) takes 5–10 s; the transport ping fires
    // mid-upload but the pong cannot come back because the upload is hogging
    // the single TCP send buffer in front of it. After pingInterval ms OkHttp
    // declares "no pong" and forcibly tears the connection down. Reconnect
    // requeues the envelope and the cycle repeats — voice never delivers.
    //
    // We replace this with the application-level RelayMessage.Ping/Pong loop
    // in KtorRelayTransport (PING_INTERVAL_MS=10 s, PONG_TIMEOUT_MS=25 s).
    // App-level Ping flows over the same WS as data so NAT-keepalive is
    // preserved (10 s < typical 20-30 s mobile-NAT idle timeout). Liveness
    // detection still triggers forceCancelAllEngineCalls() on pong timeout —
    // see KtorRelayTransport.startPing().
    .pingInterval(0, TimeUnit.SECONDS)
    // readTimeout is the OS-level backstop only. After ADR-010 the
    // primary recovery path on pong timeout is connectionPool.evictAll()
    // (see forceCancelAllEngineCalls below) which closes the underlying
    // TCP socket and unblocks the kernel recv() within milliseconds.
    // readTimeout is the last-resort fallback for the unlikely case
    // where evictAll() does not propagate; 60 s is generous enough that
    // it does NOT chop healthy connections during Wi-Fi-to-cellular
    // handovers or large in-flight envelopes.
    .readTimeout(60, TimeUnit.SECONDS)
    // Force HTTP/1.1 only for the WebSocket upgrade.
    //
    // Why: ADR-010 (2026-05-01) fix relied on connectionPool.evictAll()
    // closing the TCP socket and unblocking the kernel recv(). That
    // assumes one TCP connection per WebSocket — which is true for
    // HTTP/1.1. With HTTP/2 (RFC 8441 WS-over-H2) one TCP connection
    // multiplexes many streams, the pool entry is a long-lived h2
    // connection, and evictAll() does not have the same effect on the
    // already-upgraded WS stream. Real-device QA on Tecno HiOS
    // (2026-05-01) confirmed the original ADR-010 fix did not help —
    // analysis traced this to the relay (`relay.phntm.pro`) advertising
    // h2 + h3 via ALPN; OkHttp's default protocol list is `[h2,
    // http/1.1]` so we were upgrading via h2.
    //
    // Locking ALPN to HTTP/1.1 here keeps the pool semantics clean and
    // makes the evictAll() reset path actually work. Cost: marginal —
    // we open one TCP per WS session, but we have one persistent WS per
    // app anyway.
    .protocols(listOf(Protocol.HTTP_1_1))
    .build()

actual fun forceCancelAllEngineCalls() {
    // ADR-010: dispatcher.cancelAll() is a no-op once a WebSocket has
    // been upgraded — OkHttp removes the Call from its dispatcher right
    // after the 101 handshake, so the running-calls list is empty and
    // there is nothing to cancel. The actual reader thread is parked
    // in a kernel recv() on the underlying TCP socket and only unblocks
    // when that socket's file descriptor is closed.
    //
    // connectionPool.evictAll() walks the pool and closes every
    // connection's socket directly, which causes recv() to return
    // immediately, the reader to exit, the Ktor incoming channel to
    // close, the suspended webSocket{} block to return, and
    // runReconnectLoop to enter its next iteration within milliseconds.
    //
    // The pool object itself is not destroyed — the next webSocket()
    // call allocates a fresh connection into it normally. This makes
    // the singleton sharedOkHttpClient safe across reconnect cycles.
    //
    // Observability: log connection counts before/after so QA logs make
    // it obvious whether evictAll() actually closed anything. After APK
    // 11 we still saw multi-minute hangs — the count log will tell us
    // whether the hang is upstream (we never called evictAll) or
    // downstream (we called it but the OS socket did not unblock).
    runCatching {
        val before = sharedOkHttpClient.connectionPool.connectionCount()
        val idleBefore = sharedOkHttpClient.connectionPool.idleConnectionCount()
        sharedOkHttpClient.connectionPool.evictAll()
        val after = sharedOkHttpClient.connectionPool.connectionCount()
        android.util.Log.w(
            "PhantomRelay",
            "forceCancelAllEngineCalls: pool connections before=$before " +
                "(idle=$idleBefore) → after=$after — evictAll() invoked"
        )
    }.onFailure {
        android.util.Log.e(
            "PhantomRelay",
            "forceCancelAllEngineCalls FAILED: ${it::class.simpleName}: ${it.message}",
            it,
        )
    }
}

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        preconfigured = sharedOkHttpClient
    }
    install(WebSockets) {
        // Disabled — OkHttp's pingInterval handles WS protocol-level pings now.
        // Ktor's pinger uses session.close() (graceful) on pong timeout, which
        // re-introduces the 60-second OkHttp close-wait. OkHttp's mechanism
        // forcibly cancels instead.
        pingIntervalMillis = 0L
    }
}
