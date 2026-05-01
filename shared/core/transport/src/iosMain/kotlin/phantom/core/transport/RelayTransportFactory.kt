// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

actual fun createHttpClientFactory(): () -> HttpClient = {
    HttpClient(Darwin) {
        install(WebSockets)
    }
}

actual fun createRestHttpClient(): HttpClient = HttpClient(Darwin)

actual fun forceShutdownActiveEngine() {
    // No-op on iOS — Darwin engine doesn't have the
    // OkHttp shutdownNow / kernel-recv-blocking problem.
}
