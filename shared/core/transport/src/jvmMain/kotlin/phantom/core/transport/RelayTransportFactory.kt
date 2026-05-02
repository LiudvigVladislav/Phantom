// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

actual fun createHttpClientFactory(): () -> HttpClient = {
    HttpClient(OkHttp) {
        install(WebSockets)
    }
}

actual fun createRestHttpClient(): HttpClient = HttpClient(OkHttp)

actual fun forceShutdownActiveEngine() {
    // No-op on JVM placeholder — used only for tests/desktop.
}
