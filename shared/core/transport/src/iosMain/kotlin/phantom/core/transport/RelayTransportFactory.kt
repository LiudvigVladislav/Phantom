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

// iOS stub — Darwin engine does not have the OkHttp connection-pool half-close
// problem. When iOS is wired up (Beta), Darwin-specific tuning can be added here.
// PR-R0. DEPRECATED in PR-R0.1: production DI uses createPreKeyPublishHttpTransport().
@Suppress("DEPRECATION")
actual fun createPreKeyPublishHttpClient(): HttpClient = HttpClient(Darwin)

// iOS stub — PR-R0.1. The 8192-byte Ktor body streaming bug is Android-only
// (OkHttp engine adapter). iOS is not a production path yet; throw to surface
// any accidental invocation at development time.
actual fun createPreKeyPublishHttpTransport(): PreKeyPublishHttpTransport =
    throw NotImplementedError(
        "Native publish transport is not implemented for iOS. " +
            "iOS production wiring is planned for Beta.",
    )

actual fun forceShutdownActiveEngine() {
    // No-op on iOS — Darwin engine doesn't have the
    // OkHttp shutdownNow / kernel-recv-blocking problem.
}

// iOS stub — PR-D1. REST fallback is currently Android-only production
// path; iOS wiring is planned for Beta (PR-D3 parity track).
//
// Trek 2 Stage 2A (A4): `socksProxyPort` parameter accepted to match the
// commonMain `expect` signature, but ignored — the stub throws regardless.
// When iOS production wiring lands in Beta, the port will gate a Darwin
// SOCKS proxy the same way the Android actual gates an OkHttp proxy().
actual fun createRestFallbackTransport(
    @Suppress("UNUSED_PARAMETER") socksProxyPort: Int?,
    @Suppress("UNUSED_PARAMETER") debugBodyLogging: Boolean,
    @Suppress("UNUSED_PARAMETER") pollSkipLpAndPpProvider: () -> Boolean,
    @Suppress("UNUSED_PARAMETER") httpPhaseLogging: Boolean,
): RestFallbackTransport =
    throw NotImplementedError(
        "Native REST fallback transport is not implemented for iOS. " +
            "iOS production wiring is planned for Beta.",
    )
