package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    install(WebSockets)
}

actual fun forceCancelAllEngineCalls() {
    // Darwin engine does not expose a dispatcher-level cancel; rely on
    // session.cancel() and Darwin's own close behaviour.
}
