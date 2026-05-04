// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

actual fun createHttpClientFactory(): (socksProxyPort: Int?) -> HttpClient = { _ ->
    // iOS target is dormant on Windows builds (no native cross-compile).
    // When iOS is wired up, the port parameter will gate a Darwin SOCKS
    // proxy via Network.framework. For now accepted-but-ignored to match
    // the commonMain expect signature.
    HttpClient(Darwin) {
        install(WebSockets)
    }
}

actual fun createRestHttpClient(): HttpClient = HttpClient(Darwin)

actual fun forceShutdownActiveEngine() {
    // No-op on iOS — Darwin engine doesn't have the
    // OkHttp shutdownNow / kernel-recv-blocking problem.
}
