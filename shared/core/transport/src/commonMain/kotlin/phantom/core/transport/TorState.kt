// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

/**
 * Observable lifecycle state of an embedded [TorService] (ADR-016 Stage 2).
 *
 * Transition graph:
 *
 *     Off ── start() ──▶ Bootstrapping(0) ─… progress …─▶ Bootstrapping(99)
 *                                                              │
 *                                                              ▼
 *                                                            Ready
 *      ▲                                                       │
 *      │                                                  stop()
 *      └──────────────────── Off ◀──────────────────────────────┘
 *
 * [Failed] is reachable from any state when the underlying tor daemon
 * reports an unrecoverable error (network unavailable, port collision,
 * directory authority unreachable in censored environments without bridges).
 * Privacy-Mode UI in Stage 4 surfaces this to the user with the option to
 * retry or fall back to direct WSS.
 */
sealed class TorState {
    /** Tor is not running. Default state before [TorService.start] and after a clean [TorService.stop]. */
    data object Off : TorState()

    /**
     * Tor daemon is launching and the directory authority handshake is in
     * progress. [percent] mirrors the `Bootstrapped %` line from tor's own
     * notice log (0..99). 100 % collapses into [Ready].
     */
    data class Bootstrapping(val percent: Int) : TorState()

    /** Tor is bootstrapped and SOCKS-ready; circuits will build on demand. */
    data object Ready : TorState()

    /**
     * Tor failed to start or lost its bootstrap. [message] is a single-line
     * human-readable summary (tor's own `WARN`/`ERROR` text); detailed
     * diagnostics go to RelayLog at the implementation site.
     */
    data class Failed(val message: String) : TorState()
}
