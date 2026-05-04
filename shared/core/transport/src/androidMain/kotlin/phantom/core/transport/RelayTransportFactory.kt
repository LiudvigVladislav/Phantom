// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol

// ADR-010 (Updated 2026-05-01): WebSocket clients are NO LONGER singletons.
// Each reconnect generation in KtorRelayTransport allocates its own
// HttpClient via this factory and disposes it in the finally block.
//
// We additionally track the most recently created OkHttpClient in a volatile
// field so forceShutdownActiveEngine() (called from the pong watchdog) can
// reach it and call dispatcher.executorService.shutdownNow() — which sends
// InterruptedException to the WebSocket reader thread, the only user-space
// mechanism that unblocks a kernel recv() on a parked-Wi-Fi-radio socket.
// Ktor's HttpClient.close() does shutdown() (graceful) not shutdownNow(),
// which is why APK 13 still hung 4 minutes on Tecno HiOS.
@Volatile private var activeOkHttp: OkHttpClient? = null

actual fun createHttpClientFactory(): () -> HttpClient = {
    val okHttp = OkHttpClient.Builder()
        // OkHttp WebSocket transport-level ping is DISABLED. App-level
        // RelayMessage.Ping/Pong loop handles liveness — see KtorRelayTransport.startPing.
        .pingInterval(0, TimeUnit.SECONDS)
        // readTimeout is the OS-level backstop only. After ADR-010
        // "Updated 2026-05-01" the primary teardown path on pong/ack
        // timeout is `generationClient.close()` which destroys the
        // OkHttp engine entirely, releasing the active WS socket. 60 s
        // is generous because it never has to fire in normal recovery.
        .readTimeout(60, TimeUnit.SECONDS)
        // Force HTTP/1.1 only for the WebSocket upgrade. With HTTP/2 one
        // TCP connection multiplexes many streams and the pool entry is
        // a long-lived h2 connection — connection lifecycle reasoning
        // gets harder. HTTP/1.1 keeps "one TCP per WS" so close() acts
        // intuitively. See ADR-010 "Updated 2026-05-01".
        .protocols(listOf(Protocol.HTTP_1_1))
        // ADR-014 (2026-05-04): TCP keepalive defends NAT/CGN idle
        // timeout on cellular. SO_KEEPALIVE + TCP_KEEPIDLE=30 + INTVL=10
        // + CNT=3 → dead-detection in 60 s, with NAT-entry refresh every
        // 10 s once the connection has been idle for 30 s. Matches the
        // observed VPN-keepalive cadence (OpenVPN 10 s, WireGuard 25 s)
        // that empirically restores delivery on Russian carrier WiFi.
        // KeepAliveSocketFactory.kt sets the timing via setsockopt as
        // soon as Socket.connect() returns.
        .socketFactory(KeepAliveSocketFactory())
        .build()

    // Record so forceShutdownActiveEngine() can interrupt this engine's
    // pool threads on pong timeout. Last-write-wins is fine: we only
    // ever have one live WebSocket generation at a time.
    activeOkHttp = okHttp

    HttpClient(OkHttp) {
        engine {
            preconfigured = okHttp
        }
        install(WebSockets) {
            // Disabled — app-level Ping/Pong handles liveness in KtorRelayTransport.
            pingIntervalMillis = 0L
        }
    }
}

actual fun forceShutdownActiveEngine() {
    val okHttp = activeOkHttp ?: return
    runCatching {
        // shutdownNow returns the list of tasks that were awaiting execution;
        // we don't care about them — we want the side effect of interrupting
        // every running task, including the parked WebSocket reader.
        val pending = okHttp.dispatcher.executorService.shutdownNow()
        val poolBefore = okHttp.connectionPool.connectionCount()
        okHttp.connectionPool.evictAll()
        val poolAfter = okHttp.connectionPool.connectionCount()
        android.util.Log.w(
            "PhantomRelay",
            "forceShutdownActiveEngine: dispatcher.shutdownNow returned ${pending.size} pending; " +
                "connectionPool $poolBefore → $poolAfter",
        )
    }.onFailure {
        android.util.Log.e(
            "PhantomRelay",
            "forceShutdownActiveEngine FAILED: ${it::class.simpleName}: ${it.message}",
            it,
        )
    }
    activeOkHttp = null
}

actual fun createRestHttpClient(): HttpClient {
    // REST traffic (PreKey publish/fetch, future /me endpoints).
    // Long-lived: connection pooling and TLS session reuse across short
    // HTTP calls is desirable. Does NOT install the WebSockets plugin —
    // unused by the REST path.
    val okHttp = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
        .build()

    return HttpClient(OkHttp) {
        engine {
            preconfigured = okHttp
        }
    }
}
