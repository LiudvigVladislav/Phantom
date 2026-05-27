// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
        // ~60 s) recovers from. (PR-R0.4a: AlarmManager no longer calls
        // forceReconnect; it pokes connectivity only.)
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
        //
        // PR-RECV-DIAG1 v1.3 (Test #84.3 verdict, 2026-05-27) — A/B disable.
        // Tecno-side WS sessions die every ~31s with
        // `SocketTimeoutException: sent ping but didn't receive pong
        // within 15000ms`. session_summary shows `inbound_frames=0,
        // delivers_received=0, pings_sent=0, pongs_received=0` across
        // every session — so OkHttp's WS-protocol Ping doesn't actually
        // see a Pong back from the relay (or it's being eaten by an
        // intermediate proxy / CDN / OEM Wi-Fi). Disabling the OkHttp
        // ping is a diagnostic: if Tecno WS stays connected for >30s
        // after this change, the killer was the ping/pong cycle itself
        // and the fix needs a different heartbeat strategy. If WS
        // still dies, the issue is below WS-control-frame level (TCP
        // idle / nat / proxy timeout).
        //
        // 0L is OkHttp's documented "no automatic ping" value.
        .pingInterval(0L, TimeUnit.MILLISECONDS)
        // PR-RECV-DIAG1 v1.4 (Test #84.4 verdict 2026-05-27) — A/B
        // disable read timeout. With ping disabled at v1.3, the next
        // killer surfaced: a plain `SocketTimeoutException: timeout`
        // exactly ~61 s after WS connect, session_summary
        // `inbound_frames=0 pings_sent=0`. That's THIS readTimeout
        // firing on an idle WS socket. With pingInterval(0) there's
        // no traffic for OkHttp's read pipeline to see, so the read
        // pump hits the 60s mark and aborts.
        //
        // 0L = "no timeout". For a WS client this is the standard
        // configuration — the connection is long-lived idle and
        // explicit close from either side is the only termination
        // signal we want.
        //
        // Risk note: this OkHttpClient is shared by the Ktor HttpClient
        // factory that also handles REST short-poll fallback (PR-D0r/
        // D1d). REST requests have their own per-request timeout via
        // Ktor's HttpRequestBuilder.timeout, so removing the OkHttp
        // read backstop here does NOT make REST hang forever on a
        // broken connection. Confirmed by inspecting RestPoller in
        // shared/core/transport — every poll attempt sets its own
        // requestTimeoutMillis.
        .readTimeout(0L, TimeUnit.MILLISECONDS)
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

@Suppress("DEPRECATION")
actual fun createPreKeyPublishHttpClient(): HttpClient {
    // Dedicated client for POST /prekeys/publish only.
    //
    // DEPRECATED in PR-R0.1: production DI now calls createPreKeyPublishHttpTransport()
    // instead. This Ktor-based path stalls at 8192 bytes (Test #43, 2026-05-15)
    // due to a Ktor→OkHttp ByteChannel streaming bug. Kept for surface compatibility.
    //
    // Original PR-R0 rationale:
    //   ConnectionPool(0) — no pooling; every publish gets a fresh TCP+TLS
    //   handshake, so a stale pool entry cannot be the failure path.
    //   Connection: close interceptor — tells the server and any proxy to tear
    //   down the TCP connection after the response.
    //   writeTimeout=60s / readTimeout=60s / callTimeout=120s — generous budgets
    //   for the 13.9 KB body upload on a lossy mobile connection.
    //   connectTimeout=15s — fresh-handshake cost on mobile.
    val okHttp = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
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

/**
 * Android production implementation of [PreKeyPublishHttpTransport].
 *
 * PR-R0.1 (2026-05-15): bypasses Ktor entirely for POST /prekeys/publish.
 *
 * WHY FRESH CLIENT PER CALL: the 8192-byte stall (Test #43) is deterministic
 * and appears on both Tele2 LTE and emulator Direct, pointing at the Ktor→OkHttp
 * ByteChannel adapter rather than a network condition. By constructing a brand-new
 * OkHttpClient for every call we eliminate any adapter state carry-over and
 * guarantee the body is written in one shot via okio.Buffer without streaming.
 *
 * The cost (TLS handshake per call) is acceptable: publish runs at most 3 times
 * per onboarding/rotation event, never per message.
 */
private class AndroidNativeOkHttpPreKeyPublishTransport : PreKeyPublishHttpTransport {
    override suspend fun publish(
        url: String,
        bodyBytes: ByteArray,
        contentType: String,
    ): PreKeyPublishHttpResponse = withContext(Dispatchers.IO) {
        // Fresh OkHttpClient per call — no pool state, no adapter state.
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false) // retry logic lives in PreKeyApiClient
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

        val requestBody = bodyBytes.toRequestBody(contentType.toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Connection", "close")
            .post(requestBody)
            .build()

        val startMs = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val elapsedMs = System.currentTimeMillis() - startMs
            PreKeyPublishHttpResponse(
                statusCode = response.code,
                bodyText = body,
                elapsedMs = elapsedMs,
            )
        }
    }
}

actual fun createPreKeyPublishHttpTransport(): PreKeyPublishHttpTransport =
    AndroidNativeOkHttpPreKeyPublishTransport()

/**
 * Android production [RestFallbackTransport] — PR-D1.
 *
 * Returns a fresh [AndroidNativeOkHttpRestFallbackTransport] each call. The
 * orchestrator typically caches one instance per app lifetime; the transport
 * itself is stateless (no shared OkHttpClient — every method allocates its
 * own per the locked native-OkHttp pattern).
 */
actual fun createRestFallbackTransport(): RestFallbackTransport =
    AndroidNativeOkHttpRestFallbackTransport()
