package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/**
 * JVM actual for createHttpClient.
 *
 * Reuses the OkHttp engine (already on the classpath via ktor-client-okhttp).
 * Intended for JVM unit tests and future desktop targets.
 */
actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets)
}

actual fun forceCancelAllEngineCalls() {
    // No-op on the JVM target — used only for tests/desktop placeholder.
}
