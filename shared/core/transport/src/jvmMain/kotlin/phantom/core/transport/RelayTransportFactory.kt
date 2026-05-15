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

// JVM stub — desktop / test target. No OkHttp connection-pool tuning needed
// here; the Android actual carries the production behaviour. PR-R0.
// DEPRECATED in PR-R0.1: production DI uses createPreKeyPublishHttpTransport().
@Suppress("DEPRECATION")
actual fun createPreKeyPublishHttpClient(): HttpClient = HttpClient(OkHttp)

// JVM stub — PR-R0.1. The 8192-byte Ktor body streaming bug is Android-only.
// Tests inject a FakePreKeyPublishHttpTransport directly into PreKeyApiClient
// rather than calling this factory, so this function need not produce a working
// implementation. Throwing makes accidental invocation visible at test time.
actual fun createPreKeyPublishHttpTransport(): PreKeyPublishHttpTransport =
    throw NotImplementedError(
        "Native publish transport factory is Android-only. " +
            "Inject a FakePreKeyPublishHttpTransport in tests.",
    )

actual fun forceShutdownActiveEngine() {
    // No-op on JVM placeholder — used only for tests/desktop.
}
