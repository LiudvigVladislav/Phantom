package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets) {
        // Protocol-level WebSocket ping (control frame, not our app-level
        // RelayMessage.Ping JSON). Ktor + OkHttp handle this below the
        // application layer, so a dropped TCP path surfaces as a closed
        // session even if the application never gets a chance to write
        // its own ping. Kept slightly faster than the application heartbeat
        // (RelayTransportConfig.PING_INTERVAL_MS = 10 s) so the protocol
        // ping is the first signal of a broken pipe.
        pingIntervalMillis = 8_000L
    }
}
