// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import phantom.core.transport.RestHealth
import phantom.core.transport.RestMode
import phantom.core.transport.RestModeSnapshot
import phantom.core.transport.RestRecoveryCause
import phantom.core.transport.TransportState

/**
 * Presentation-only UI state for the Android transport banner / status row
 * / notification overlay. Derived from the raw [TransportState] of the
 * underlying WebSocket transport, the [RestModeSnapshot] of
 * [HybridRelayTransport.stateMachine], the orchestrator's [RestHealth],
 * the transport's connected session epoch and the recovery coordinator's
 * activity (Stage 2 B9, 2026-09-13).
 *
 * NOT a substitute for the common-side [TransportState] source of truth.
 * `TransportState` is consumed by transport-internal logic (prekey retry,
 * keepalive guards) and MUST stay on raw WS semantics. This type is read
 * by the three Android UI surfaces:
 * [phantom.android.screens.chatlist.ChatListScreen],
 * [phantom.android.screens.chat.ChatScreen], and
 * [phantom.android.ui.ConnectionBanner].
 */
sealed class ConnectionUiState {
    /** A proven live WS session in `WsActive`, the socket `Connected`, epochs matching. */
    object Online : ConnectionUiState()

    /** REST fallback is delivering envelopes. */
    object LimitedRealtime : ConnectionUiState()

    /** WS probation following a failure, route change, or unknown cause; REST usable or a socket alive. */
    object Recovering : ConnectionUiState()

    /** First WS attempt in flight, nothing proven yet and no usable REST. */
    object Connecting : ConnectionUiState()

    /** No usable REST and the socket is between attempts, or a start is being prepared. */
    object Reconnecting : ConnectionUiState()

    /** No live session, no usable REST, no start in flight: messages queue (L1 holds). */
    object Offline : ConnectionUiState()

    /** No usable REST and the last WS attempt failed with a cause. */
    data class Error(val cause: Throwable) : ConnectionUiState()
}

/**
 * Stage 2 B7d/B9: what the recovery coordinator is doing, for
 * presentation. `StartJobPending` is L1 state (e): a start job is alive
 * and has not claimed ownership yet -- waiting for the container, for the
 * device unlock, or inside `prepareStart`. Never `Offline` in that state
 * (I5).
 */
enum class RecoveryActivity { Idle, StartJobPending }

/**
 * Legacy two-input derivation kept for callers and fixtures that predate
 * Stage 2: no REST health (unusable), no transport epoch, no recovery
 * activity. Equivalent to the full derivation with those defaults.
 */
internal fun deriveConnectionUiState(
    wsState: TransportState,
    restMode: RestMode,
): ConnectionUiState = deriveConnectionUiState(wsState, RestModeSnapshot(restMode))

/**
 * Pure derivation, Stage 2 B9. Internal visibility so unit tests can
 * target it without going through `combine` / `StateFlow` plumbing.
 *
 * Invariants asserted by the fixtures:
 *  - I4: `Online` implies a proven live session, `WsActive`,
 *    `TransportState.Connected` and a matching transport epoch.
 *  - I5: `Offline` implies no live session, REST unusable and no start
 *    job in flight.
 *  - `Error(cause)` only when REST is unusable and the last WS attempt
 *    failed with a cause.
 *
 * `Connecting` is kept for exactly one row the B9 table does not name:
 * the very first WS attempt (`wsState == Connecting`, no session was
 * ever live) with no usable REST. Every other `WsActive` case without a
 * proven, connected, matching session is `Recovering` (REST usable) or
 * `Reconnecting` (REST unusable), never `Online`.
 */
internal fun deriveConnectionUiState(
    wsState: TransportState,
    snapshot: RestModeSnapshot,
    restHealth: RestHealth = RestHealth.UNKNOWN,
    transportSessionEpoch: Long? = null,
    recovery: RecoveryActivity = RecoveryActivity.Idle,
): ConnectionUiState {
    val restUsable = restHealth.usable
    val live = snapshot.liveSessionEpoch
    val silence = snapshot.recoveryCause == RestRecoveryCause.InboundSilence
    val startPending = recovery == RecoveryActivity.StartJobPending
    return when (snapshot.mode) {
        RestMode.WsActive -> {
            val proven = live != null && snapshot.provenSessionEpoch == live
            when {
                proven && wsState == TransportState.Connected && transportSessionEpoch == live ->
                    ConnectionUiState.Online
                startPending -> if (restUsable) ConnectionUiState.Recovering else ConnectionUiState.Reconnecting
                restUsable -> ConnectionUiState.Recovering
                wsState is TransportState.Error -> ConnectionUiState.Error(wsState.cause)
                wsState == TransportState.Connecting && live == null -> ConnectionUiState.Connecting
                else -> ConnectionUiState.Reconnecting
            }
        }
        RestMode.WsCandidate ->
            if (restUsable && silence) ConnectionUiState.LimitedRealtime else ConnectionUiState.Recovering
        RestMode.RestActive -> when {
            // A silent live socket: the transport's watchdogs own it; L1 is not required.
            live != null -> if (restUsable) ConnectionUiState.LimitedRealtime else ConnectionUiState.Recovering
            restUsable -> if (silence) ConnectionUiState.LimitedRealtime else ConnectionUiState.Recovering
            startPending -> ConnectionUiState.Reconnecting
            wsState is TransportState.Error -> ConnectionUiState.Error(wsState.cause)
            // Review round 7: the machine now starts in `RestActive` with no
            // session, because a handshake is no longer taken as proof. A
            // socket that is being established is not "nothing works": the
            // first attempt reads `Connecting`, a later one `Reconnecting`,
            // and `Offline` is left for the state where no attempt is in
            // flight at all.
            wsState == TransportState.Connecting -> ConnectionUiState.Connecting
            wsState == TransportState.Reconnecting -> ConnectionUiState.Reconnecting
            else -> ConnectionUiState.Offline
        }
    }
}
