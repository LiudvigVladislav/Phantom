// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

sealed class TransportState {
    object Disconnected : TransportState()
    object Connecting : TransportState()
    object Connected : TransportState()

    /**
     * PR-H1c (2026-05-13): emitted between forceReconnect() and the next
     * successful WebSocket connect. Distinct from [Disconnected] so the UI
     * can show a soft "Reconnecting…" indicator instead of going dark
     * (which on Test #34 was reported as "лагает" UX).
     *
     * The transport is still self-healing during this state — outbound
     * sends queue into pendingOutbox and flush after the new session lands;
     * messages are not lost. The state purely communicates "we noticed the
     * socket was stale, working on it" to upper layers.
     */
    object Reconnecting : TransportState()

    data class Error(val cause: Throwable) : TransportState()
}
