// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.net.InetSocketAddress
import java.net.Proxy
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

actual fun createHttpClientFactory(): (socksProxyPort: Int?) -> HttpClient = { socksProxyPort ->
    val builder = OkHttpClient.Builder()
        // PR-H1c (2026-05-13) / PR-H1e (2026-05-14): WebSocket-protocol
        // Ping ENABLED at 15 s. Since PR-H1e the app-level RelayMessage.Ping
        // loop is suppressed (RelayTransportConfig.APP_LEVEL_PING_ENABLED
        // = false), so this is now the sole heartbeat sourced by the
        // client. OkHttp closes the WebSocket with SocketTimeoutException
        // if no Pong arrives within `pingInterval` ms — that path is what
        // the in-process dead-socket watchdog (DEAD_SOCKET_TIMEOUT_MS,
        // ~60 s) and the AlarmManager keepalive (ALARM_STALE_RECONNECT_MS,
        // 45 s) recover from.
        //
        // 15 s is deliberate: PR-H1e Run B tested 5 s and saw WS lifetime
        // *halve* (Phone 21.8 s vs 46.5 s baseline) — the tighter pong
        // window and the cadence itself appeared to provoke teardown.
        // 15 s is the best-known stable value; see Run C in the
        // APP_LEVEL_PING_ENABLED comment for the comparison data.
        //
        // Kept separate from Ktor's `pingIntervalMillis` (still 0) so we
        // do not double-up Pong handling — only OkHttp engine emits Ping.
        // The relay's Message::Ping arm in routes.rs (PR-F2-relay) already
        // handles WS-protocol Ping correctly: pong on the same socket.
        .pingInterval(15_000L, TimeUnit.MILLISECONDS)
        // readTimeout is the OS-level backstop only. After ADR-010
        // "Updated 2026-05-01" the primary teardown path on pong/ack
        // timeout is `generationClient.close()` which destroys the
        // OkHttp engine entirely, releasing the active WS socket. 60 s
        // is generous because it never has to fire in normal recovery.
        .readTimeout(60, TimeUnit.SECONDS)
        // connectTimeout is per-path. Direct WSS keeps the OkHttp default
        // (10 s) — relay.phntm.pro resolves and connects in <500 ms on a
        // healthy network, longer means real outage. SOCKS-proxied paths
        // (Reality / Tor) get 90 s because the auth/challenge GET is the
        // first traffic over a freshly-established Reality tunnel or a
        // brand-new Tor circuit; on Tor the HS-descriptor fetch +
        // rendezvous can comfortably eat 30–60 s. The 10 s default was
        // firing as ConnectTimeoutException on /auth/challenge under Tor
        // (cross-device test 2026-05-10), making the WS attempt cycle
        // for ~3 minutes before succeeding even though the underlying
        // transport had reached Connected.
        .connectTimeout(if (socksProxyPort != null) 90 else 10, TimeUnit.SECONDS)
        // Force HTTP/1.1 only for the WebSocket upgrade. With HTTP/2 one
        // TCP connection multiplexes many streams and the pool entry is
        // a long-lived h2 connection — connection lifecycle reasoning
        // gets harder. HTTP/1.1 keeps "one TCP per WS" so close() acts
        // intuitively. See ADR-010 "Updated 2026-05-01".
        .protocols(listOf(Protocol.HTTP_1_1))

    // ADR-016 Stage 2C: route this generation's TCP connection through
    // the embedded tor's SOCKS5 listener. tor binds an ephemeral port
    // discovered via TorService.state; we get it here as `socksProxyPort`.
    // Null = direct, no proxy applied. We use Proxy.Type.SOCKS (which is
    // SOCKS4 in older specs but SOCKS5 with a v5 server such as tor — the
    // negotiation chooses the actual version) bound to the loopback
    // interface so we never leak the proxy address off-device.
    if (socksProxyPort != null) {
        builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksProxyPort)))
    }

    val okHttp = builder.build()

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
    // REST traffic (PreKey publish/fetch, /auth/challenge, future /me endpoints).
    // Long-lived: connection pooling and TLS session reuse across short
    // HTTP calls is desirable. Does NOT install the WebSockets plugin —
    // unused by the REST path.
    //
    // PR-G4 (2026-05-13): force HTTP/1.1 only. Test29 caught OkHttp's HTTP/2
    // implementation getting stuck on REST requests in two distinct ways:
    //
    //   1. POST /prekeys/publish: client opened the H2 stream and sent
    //      headers (Content-Length: 13903) but never delivered the body.
    //      Caddy waited 30 s for body bytes (`bytes_read: 0`) and returned
    //      408 Request Timeout. Client surfaced this as
    //      `SocketException: Connection reset` from `Http2Reader.nextFrame`.
    //   2. GET /prekeys/bundle/{peer}: client never sent the request at
    //      all — the 4 failed phone fetches in Test29 are absent from the
    //      Caddy access log entirely. They timed out client-side after 8 s
    //      with `CancellationException` from
    //      `attachToUserJob$cleanupHandler`. Most likely a stale H2
    //      connection in the OkHttp pool that the server (or NAT) had
    //      half-closed; OkHttp kept routing new requests onto it instead
    //      of opening a fresh one.
    //
    // The WebSocket path is unaffected because WS upgrade requires HTTP/1.1
    // anyway (see WS factory above). The relay (Caddy) speaks both H1.1 and
    // H2 happily, and the access log shows H1.1 REST clients completing in
    // <2 ms — there is no throughput cost here at our request volume.
    //
    // If/when we revisit H2 for REST, the fix is upstream in OkHttp / Ktor's
    // okhttp engine. For now we trade multiplexing we do not need for
    // reliability we do.
    val okHttp = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    return HttpClient(OkHttp) {
        engine {
            preconfigured = okHttp
        }
    }
}

actual fun createPreKeyPublishHttpClient(): HttpClient {
    // Dedicated client for POST /prekeys/publish only.
    //
    // WHY: Tele2 LTE Иркутская 2026-05-14 Test #42 showed POST /prekeys/publish
    // consistently reaching Caddy with bytes_read=0, status=408, duration=30s.
    // Client-side: SocketTimeoutException from Http1ExchangeCodec.readResponseHeaders
    // after 30 s. The 13.9 KB request body never reached the wire despite
    // createRestHttpClient() already being pinned to HTTP/1.1 (PR-G4).
    //
    // Root cause: OkHttp connection pool reuses a TCP connection that was
    // silently half-closed by a carrier-side NAT or transparent proxy between
    // requests. The server receives headers but no body bytes and times out.
    // This is the same class of bug PR-G4 fixed for H2, now manifesting in
    // HTTP/1.1 pool reuse under mobile carrier conditions.
    //
    // Fix:
    //   ConnectionPool(0) — no pooling; every publish gets a fresh TCP+TLS
    //   handshake, so a stale pool entry cannot be the failure path.
    //
    //   Connection: close interceptor — tells the server and any proxy to tear
    //   down the TCP connection after the response. Belt-and-suspenders: the
    //   server cannot keep-alive back into a broken state.
    //
    //   writeTimeout=60s — the 13.9 KB body upload needs a fair per-byte budget
    //   on a lossy mobile connection (30s default was co-expiring with the server
    //   timeout, leaving no margin). readTimeout=60s for symmetry.
    //
    //   callTimeout=120s — outer ceiling prevents an indefinitely-stalled
    //   coroutine if both writeTimeout and readTimeout are somehow bypassed.
    //
    //   connectTimeout=15s — generous for the fresh-handshake cost on mobile;
    //   tight enough to not hold a coroutine too long if the relay is down.
    //
    // PR-R0, 2026-05-15.
    val okHttp = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Connection", "close")
                    .build(),
            )
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    return HttpClient(OkHttp) {
        engine {
            preconfigured = okHttp
        }
    }
}
