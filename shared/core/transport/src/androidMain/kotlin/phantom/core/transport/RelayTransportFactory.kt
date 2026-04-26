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
    // OkHttp WebSocket protocol-level ping. If a pong does not arrive within
    // pingInterval, OkHttp forcefully cancels the call (calls webSocket.cancel()
    // internally, NOT graceful close), which closes the socket immediately.
    // This is the dead-network detection path; ~15 s is fast enough for users
    // and large enough to avoid spurious cancels on a slow but alive cellular
    // link.
    .pingInterval(15, TimeUnit.SECONDS)
    // Backstop: if OkHttp's reader thread blocks on a silent socket for
    // longer than this, throw SocketTimeoutException. With pingInterval at
    // 15 s the socket should never go fully silent for 40 s while alive.
    .readTimeout(40, TimeUnit.SECONDS)
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
