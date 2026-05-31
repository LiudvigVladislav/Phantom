// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import phantom.core.transport.RestMode
import phantom.core.transport.TransportState

/**
 * Presentation-only UI state for the Android transport banner / status row
 * / notification overlay. Derived from the raw [TransportState] of the
 * underlying WebSocket transport plus the [RestMode] of
 * [HybridRelayTransport.stateMachine].
 *
 * NOT a substitute for the common-side [TransportState] source of truth.
 * `TransportState` is consumed by transport-internal logic (prekey retry,
 * keepalive guards, alarm-driven reconnect cues) and MUST stay on raw
 * WS semantics. This type is read by the three Android UI surfaces:
 * [phantom.android.screens.chatlist.ChatListScreen],
 * [phantom.android.screens.chat.ChatScreen], and
 * [phantom.android.ui.ConnectionBanner].
 *
 * PR-WS-HEALTH-STATE1 Commit 3.1 (2026-05-30). Design note locked in
 * `docs/tracks/ws-health-state.md` § Commit 3.1 design note. The
 * derivation table prioritises [RestMode] over raw [TransportState] so
 * that a transient `Connected` WS during `RestActive` / `WsCandidate`
 * still presents as `LimitedRealtime` / `Recovering` — matching the
 * existing notification shade overlay precedence at
 * `PhantomMessagingService.kt:246+`.
 */
sealed class ConnectionUiState {
    /** RestMode.WsActive + WS Connected — fully healthy, no fallback in flight. */
    object Online : ConnectionUiState()

    /** RestMode.RestActive — REST fallback is delivering envelopes. */
    object LimitedRealtime : ConnectionUiState()

    /** RestMode.WsCandidate — WS came back, REST polling continues until promotion. */
    object Recovering : ConnectionUiState()

    /** RestMode.WsActive + WS Connecting — cold-start / first connect. */
    object Connecting : ConnectionUiState()

    /** RestMode.WsActive + WS Reconnecting — in-process recovery. */
    object Reconnecting : ConnectionUiState()

    /** RestMode.WsActive + WS Disconnected — no transport. */
    object Offline : ConnectionUiState()

    /** RestMode.WsActive + WS Error — terminal failure without fallback. */
    data class Error(val cause: Throwable) : ConnectionUiState()
}

/**
 * Pure derivation function. Internal visibility so unit tests can target
 * it without going through `combine` / `StateFlow` plumbing.
 *
 * Pattern-match order matters in Kotlin `when` (first match wins). This
 * table is intentionally written with `restMode` as the outer dispatch
 * so that `RestActive` / `WsCandidate` ALWAYS supersede raw `wsState`,
 * even when raw WS is `Connected`. Per `RestStateMachine.kt:34-:45`,
 * `WsCandidate` keeps REST polling continuing until either 60 s of WS
 * uptime OR an outbound ACK round-trip lands — so raw WS `Connected`
 * during `WsCandidate` does NOT mean "fully online", and UI must reflect
 * the `Recovering` state in that window.
 *
 * Gate 7 of the Commit 3.1 acceptance gates: this function MUST be
 * unit-tested exhaustively over the 7 priority rows. Architect-flagged
 * ambiguous cases to verify explicitly:
 *   - (Connected, RestActive)    -> LimitedRealtime
 *   - (Connected, WsCandidate)   -> Recovering
 *   - (Reconnecting, RestActive) -> LimitedRealtime
 *   - (Error(t), RestActive)     -> LimitedRealtime
 */
internal fun deriveConnectionUiState(
    wsState: TransportState,
    restMode: RestMode,
): ConnectionUiState = when (restMode) {
    RestMode.RestActive  -> ConnectionUiState.LimitedRealtime   // priority 1
    RestMode.WsCandidate -> ConnectionUiState.Recovering        // priority 2
    RestMode.WsActive    -> when (wsState) {                    // priority 3+
        TransportState.Connected    -> ConnectionUiState.Online
        TransportState.Connecting   -> ConnectionUiState.Connecting
        TransportState.Reconnecting -> ConnectionUiState.Reconnecting
        TransportState.Disconnected -> ConnectionUiState.Offline
        is TransportState.Error     -> ConnectionUiState.Error(wsState.cause)
    }
}
