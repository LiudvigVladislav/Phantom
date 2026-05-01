// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

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
    // readTimeout is the OS-level backstop: if the kernel socket is silent
    // for this long, OkHttp throws SocketTimeoutException, webSocket{}
    // block exits, and runReconnectLoop opens a fresh session.
    //
    // Sized at 12 s deliberately, NOT 60 s. Field testing on Tecno Spark Go
    // (HiOS / Android 12) showed that when the OS parks the Wi-Fi radio for
    // battery reasons, dispatcher.cancelAll() does NOT propagate into the
    // native socket reader — it stays blocked on the kernel read until OS
    // readTimeout fires. With readTimeout=60 the user saw 60–80 s gaps
    // between "Pong timeout" and reconnect; in the meantime any messages
    // queued on the dead socket could not be sent and the user had to
    // restart the app. With readTimeout=12 the dead socket recovers in
    // ~12 s, ack timeout (60 s) re-queues unacked envelopes onto the
    // fresh session, and the user sees normal delivery.
    //
    // 12 s is safe because the application-level Ping flows every 10 s
    // and the server immediately responds with a Pong frame, so a healthy
    // socket sees an incoming frame at least every ~10 s. 12 s is the
    // tightest setting that still tolerates one missed ping cycle from
    // brief jitter.
    .readTimeout(12, TimeUnit.SECONDS)
    .build()

actual fun forceCancelAllEngineCalls() {
    runCatching { sharedOkHttpClient.dispatcher.cancelAll() }
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
