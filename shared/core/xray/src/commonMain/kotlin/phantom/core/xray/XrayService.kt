// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.xray

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle facade for an embedded Xray-core client (ADR-018 Stage 5E).
 *
 * Why Xray at all: Russian DPI (TSPU) silently throttles direct TLS streams
 * larger than ~16 KB to flagged datacenter IPs (Hetzner, FlokiNET, …) — the
 * "16-kilobyte curtain". Tor's WebTunnel pluggable transport is one such
 * stream, so it freezes at bootstrap ~14 % on RU mobile carriers without VPN.
 * VLESS+REALITY mimics a genuine TLS handshake to a public CDN host (we use
 * `www.microsoft.com`) and so is classified as trusted-CDN traffic the TSPU
 * does not policy.
 *
 * Architecture: Xray runs *in-process* on Android via libXray (Go-mobile
 * AAR) — same lifecycle reasoning as kmp-tor's resource-noexec variant. Tor
 * dies cleanly when our JVM dies, no orphan child process, no second
 * foreground service. The client opens a localhost SOCKS5 listener on
 * [XrayState.Ready.socksPort]; callers route their HTTP/WebSocket traffic
 * through it the same way Stage 2C wires the Tor SOCKS port.
 *
 * Implementation contract:
 *  - [start] is idempotent — no-op when already [XrayState.Ready].
 *  - [stop] is idempotent — no-op when already [XrayState.Off].
 *  - [state] is conflated; consumers receive an immediate replay on collect.
 *  - All methods are safe to call from any coroutine context.
 */
interface XrayService {
    val state: StateFlow<XrayState>

    /**
     * Start the embedded Xray daemon and bind its SOCKS5 listener. Returns
     * after the runtime is configured; [state] then transitions to
     * [XrayState.Ready] (or [XrayState.Failed]) asynchronously.
     */
    suspend fun start()

    /**
     * Stop the embedded Xray daemon and tear down all outbound connections.
     * Returns when the runtime has fully exited; [state] becomes
     * [XrayState.Off].
     */
    suspend fun stop()
}
