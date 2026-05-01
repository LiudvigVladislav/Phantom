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
// HttpClient via this factory and disposes it in the finally block. This
// is the only way to force-close an active WebSocket on Tecno HiOS where
// dispatcher.cancelAll() is a no-op (Call removed from dispatcher post-101)
// and connectionPool.evictAll() is a partial-no-op (only evicts idle
// connections, the active WS connection survives).

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
        .build()

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
