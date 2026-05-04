// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

actual fun createHttpClientFactory(): (socksProxyPort: Int?) -> HttpClient = { _ ->
    // JVM target is desktop / tests only — no Tor wiring yet. The port
    // parameter is accepted for source-compat with commonMain but ignored;
    // when desktop integration arrives this will gain a proxy(...) branch.
    HttpClient(OkHttp) {
        install(WebSockets)
    }
}

actual fun createRestHttpClient(): HttpClient = HttpClient(OkHttp)

actual fun forceShutdownActiveEngine() {
    // No-op on JVM placeholder — used only for tests/desktop.
}
