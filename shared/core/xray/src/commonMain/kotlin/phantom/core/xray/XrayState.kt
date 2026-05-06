// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.xray

/**
 * Observable lifecycle state of an [XrayService] (ADR-018 Stage 5E).
 *
 * Transition graph:
 *
 *     Off ── start() ──▶ Starting ──▶ Ready(socksPort)
 *      ▲                    │              │
 *      │                    ▼              ▼
 *      └──────────────── Failed ◀── stop() / runtime error
 *
 * Unlike [phantom.core.transport.TorState], Xray has no multi-percent
 * bootstrap phase: REALITY's TLS handshake either succeeds on the first
 * round-trip (state goes Starting → Ready in well under a second on a usable
 * link) or it fails outright. There is no progress to surface.
 */
sealed class XrayState {
    /** Xray is not running. Default state before [XrayService.start] and after a clean [XrayService.stop]. */
    data object Off : XrayState()

    /** [XrayService.start] has been invoked; the runtime is initialising. */
    data object Starting : XrayState()

    /**
     * Xray's SOCKS5 inbound is bound and the VLESS+REALITY outbound is
     * configured. Stage 5E.B.4 consumers pass [socksPort] to the Ktor OkHttp
     * engine as `Proxy.SOCKS` so application traffic exits through Xray.
     */
    data class Ready(val socksPort: Int) : XrayState()

    /**
     * Xray failed to start or the runtime aborted. [message] is a single-line
     * human-readable summary; full diagnostics go to the platform log
     * (Logcat tag = `PhantomXray`).
     */
    data class Failed(val message: String) : XrayState()
}
