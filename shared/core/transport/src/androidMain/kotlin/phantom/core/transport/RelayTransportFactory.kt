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
    //
    // 8 s is a deliberate compromise:
    //   - long enough that we burn ~7 % power vs always-on idle radio,
    //   - short enough to keep cellular carrier NAT mappings alive (typical
    //     mobile NAT idle timeout is 30 s; some carriers go as low as 20 s
    //     even on TCP). QA-v6 showed the phone losing its connection every
    //     60–90 s with pingInterval=15s — frequent enough to NOT be a
    //     server-side issue (the emulator on Wi-Fi was rock-stable in the
    //     same run) but slow enough that an aggressive carrier NAT killed
    //     the path between two of our pings.
    .pingInterval(8, TimeUnit.SECONDS)
    // Backstop: if OkHttp's reader thread blocks on a silent socket for
    // longer than this, throw SocketTimeoutException. With pingInterval at
    // 8 s the socket should never go fully silent for 25 s while alive.
    .readTimeout(25, TimeUnit.SECONDS)
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
