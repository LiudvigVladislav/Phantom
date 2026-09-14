// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import phantom.core.transport.RestHealth
import phantom.core.transport.RestHealthReason
import phantom.core.transport.RestMode
import phantom.core.transport.RestModeSnapshot
import phantom.core.transport.RestRecoveryCause
import phantom.core.transport.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [deriveConnectionUiState] under the Stage 2 contract
 * (B9, 2026-09-13). Pure function, JVM source set, no Android runtime.
 *
 * The banner is what the user reads, and before Stage 2 it could be
 * wrong in both directions at once. `WsActive` was reachable by a
 * wall-clock timer with no socket, and the mapping then showed `Offline`
 * for `WsActive + Disconnected` while REST polls were returning 200 and
 * carrying the user's messages — the 19:08 phone. And `RestActive`
 * always read `LimitedRealtime`, including when REST was not usable
 * either, so "nothing works" was indistinguishable from "the fallback is
 * delivering".
 *
 * The invariants these cases pin:
 *  - **I4**: `Online` requires a proven live session, `WsActive`, the
 *    socket `Connected`, and a transport epoch equal to that session.
 *  - **I5**: `Offline` requires no live session, unusable REST and no
 *    start job in flight.
 *
 * Review round 7 (2026-09-13) moved the machine's INITIAL mode from
 * `WsActive` to `RestActive`, because a handshake is no longer read as
 * proof. That moved the cold start from the `WsActive` arm of this
 * function to the `RestActive` one, and the cold-start cases below pin
 * that it did not turn "the app is connecting" into "the app is
 * offline" on the way.
 */
class ConnectionUiStateTest {

    private val usable = RestHealth(
        usable = true,
        lastPollOkAtMs = 1_000L,
        networkGeneration = 1L,
        reason = RestHealthReason.PollOk,
    )
    private val unusable = RestHealth(
        usable = false,
        lastPollOkAtMs = 1_000L,
        networkGeneration = 1L,
        reason = RestHealthReason.Expired,
    )
    private val blocked = RestHealth(
        usable = false,
        lastPollOkAtMs = null,
        networkGeneration = 1L,
        reason = RestHealthReason.EgressBlocked,
    )

    private fun proven(epoch: Long = 5L) = RestModeSnapshot(
        mode = RestMode.WsActive,
        recoveryCause = RestRecoveryCause.FailureOrUnknown,
        liveSessionEpoch = epoch,
        candidateEpoch = null,
        provenSessionEpoch = epoch,
    )

    private fun candidate(epoch: Long = 5L, cause: RestRecoveryCause) = RestModeSnapshot(
        mode = RestMode.WsCandidate,
        recoveryCause = cause,
        liveSessionEpoch = epoch,
        candidateEpoch = epoch,
        provenSessionEpoch = null,
    )

    private fun restActive(
        live: Long? = null,
        cause: RestRecoveryCause = RestRecoveryCause.FailureOrUnknown,
    ) = RestModeSnapshot(
        mode = RestMode.RestActive,
        recoveryCause = cause,
        liveSessionEpoch = live,
        candidateEpoch = null,
        provenSessionEpoch = null,
    )

    // ── Online (I4) ──────────────────────────────────────────────────────────

    @Test
    fun online_requires_proof_mode_connected_state_and_a_matching_epoch() {
        assertEquals(
            ConnectionUiState.Online,
            deriveConnectionUiState(
                wsState = TransportState.Connected,
                snapshot = proven(5),
                restHealth = unusable,
                transportSessionEpoch = 5L,
            ),
        )
    }

    @Test
    fun ws_active_without_a_connected_socket_is_never_online() {
        // The 19:08 case: the mode said WsActive, the socket was down, and
        // the banner said `Offline` while REST was delivering.
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(
                wsState = TransportState.Disconnected,
                snapshot = proven(5),
                restHealth = usable,
                transportSessionEpoch = null,
            ),
        )
        assertEquals(
            ConnectionUiState.Reconnecting,
            deriveConnectionUiState(
                wsState = TransportState.Disconnected,
                snapshot = proven(5),
                restHealth = unusable,
                transportSessionEpoch = null,
            ),
        )
    }

    @Test
    fun a_transport_epoch_that_differs_is_never_online() {
        val shown = deriveConnectionUiState(
            wsState = TransportState.Connected,
            snapshot = proven(5),
            restHealth = unusable,
            transportSessionEpoch = 6L,
        )
        assertEquals(
            ConnectionUiState.Reconnecting, shown,
            "the socket that is connected is not the session the proof belongs to",
        )
    }

    @Test
    fun ws_active_without_a_proven_session_is_never_online() {
        val unproven = RestModeSnapshot(
            mode = RestMode.WsActive,
            liveSessionEpoch = 5L,
            provenSessionEpoch = null,
        )
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(
                wsState = TransportState.Connected,
                snapshot = unproven,
                restHealth = usable,
                transportSessionEpoch = 5L,
            ),
        )
    }

    // ── Candidate ────────────────────────────────────────────────────────────

    @Test
    fun candidate_with_usable_rest_after_silence_is_limited_realtime() {
        assertEquals(
            ConnectionUiState.LimitedRealtime,
            deriveConnectionUiState(
                wsState = TransportState.Connected,
                snapshot = candidate(cause = RestRecoveryCause.InboundSilence),
                restHealth = usable,
                transportSessionEpoch = 5L,
            ),
        )
    }

    @Test
    fun candidate_after_a_failure_is_recovering_whatever_the_socket_says() {
        for (ws in listOf(
            TransportState.Connected,
            TransportState.Connecting,
            TransportState.Reconnecting,
            TransportState.Disconnected,
            TransportState.Error(IllegalStateException("boom")),
        )) {
            assertEquals(
                ConnectionUiState.Recovering,
                deriveConnectionUiState(
                    wsState = ws,
                    snapshot = candidate(cause = RestRecoveryCause.FailureOrUnknown),
                    restHealth = usable,
                    transportSessionEpoch = 5L,
                ),
                "wsState=$ws",
            )
        }
    }

    @Test
    fun candidate_without_usable_rest_is_recovering_even_after_silence() {
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(
                wsState = TransportState.Connected,
                snapshot = candidate(cause = RestRecoveryCause.InboundSilence),
                restHealth = unusable,
                transportSessionEpoch = 5L,
            ),
        )
    }

    // ── RestActive ───────────────────────────────────────────────────────────

    @Test
    fun rest_active_with_usable_rest_after_silence_is_limited_realtime() {
        assertEquals(
            ConnectionUiState.LimitedRealtime,
            deriveConnectionUiState(
                wsState = TransportState.Disconnected,
                snapshot = restActive(cause = RestRecoveryCause.InboundSilence),
                restHealth = usable,
            ),
        )
    }

    @Test
    fun rest_active_with_usable_rest_after_a_failure_is_recovering() {
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(
                wsState = TransportState.Disconnected,
                snapshot = restActive(cause = RestRecoveryCause.FailureOrUnknown),
                restHealth = usable,
            ),
        )
    }

    @Test
    fun a_silent_live_socket_with_unusable_rest_is_recovering_not_offline() {
        val shown = deriveConnectionUiState(
            wsState = TransportState.Connected,
            snapshot = restActive(live = 5L),
            restHealth = unusable,
            transportSessionEpoch = 5L,
        )
        assertEquals(
            ConnectionUiState.Recovering, shown,
            "the socket is alive and the transport's watchdogs own it; L1 is not required",
        )
    }

    @Test
    fun a_silent_live_socket_with_usable_rest_is_limited_realtime() {
        assertEquals(
            ConnectionUiState.LimitedRealtime,
            deriveConnectionUiState(
                wsState = TransportState.Connected,
                snapshot = restActive(live = 5L),
                restHealth = usable,
                transportSessionEpoch = 5L,
            ),
        )
    }

    @Test
    fun offline_requires_no_session_no_usable_rest_and_no_start_job() {
        assertEquals(
            ConnectionUiState.Offline,
            deriveConnectionUiState(
                wsState = TransportState.Disconnected,
                snapshot = restActive(),
                restHealth = unusable,
                recovery = RecoveryActivity.Idle,
            ),
        )
    }

    @Test
    fun a_start_job_in_flight_is_never_offline() {
        // L1 state (e): the attempt is alive — waiting for the container
        // or for the device to be unlocked — so the user is not told the
        // app has given up.
        assertEquals(
            ConnectionUiState.Reconnecting,
            deriveConnectionUiState(
                wsState = TransportState.Disconnected,
                snapshot = restActive(),
                restHealth = unusable,
                recovery = RecoveryActivity.StartJobPending,
            ),
        )
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(
                wsState = TransportState.Disconnected,
                snapshot = restActive(),
                restHealth = usable,
                recovery = RecoveryActivity.StartJobPending,
            ),
        )
    }

    @Test
    fun a_start_job_in_flight_is_never_offline_in_ws_active_either() {
        assertEquals(
            ConnectionUiState.Reconnecting,
            deriveConnectionUiState(
                wsState = TransportState.Disconnected,
                snapshot = RestModeSnapshot(mode = RestMode.WsActive),
                restHealth = unusable,
                recovery = RecoveryActivity.StartJobPending,
            ),
        )
    }

    @Test
    fun private_mode_egress_block_is_unusable_rest_like_any_other() {
        // B7a: a refused DIRECT REST is not a refused recovery. It reaches
        // presentation as unusable REST, nothing more.
        assertEquals(
            ConnectionUiState.Offline,
            deriveConnectionUiState(
                wsState = TransportState.Disconnected,
                snapshot = restActive(),
                restHealth = blocked,
            ),
        )
    }

    // ── Error and Connecting ─────────────────────────────────────────────────

    @Test
    fun an_error_is_shown_only_when_rest_is_unusable() {
        val cause = IllegalStateException("ws error")
        val shown = deriveConnectionUiState(
            wsState = TransportState.Error(cause),
            snapshot = RestModeSnapshot(mode = RestMode.WsActive),
            restHealth = unusable,
        )
        assertIs<ConnectionUiState.Error>(shown)
        assertEquals(cause, shown.cause)
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(
                wsState = TransportState.Error(cause),
                snapshot = RestModeSnapshot(mode = RestMode.WsActive),
                restHealth = usable,
            ),
            "with a working fallback the user does not need the WSS error text",
        )
    }

    @Test
    fun the_first_attempt_before_any_session_is_connecting() {
        assertEquals(
            ConnectionUiState.Connecting,
            deriveConnectionUiState(
                wsState = TransportState.Connecting,
                snapshot = RestModeSnapshot(mode = RestMode.WsActive),
                restHealth = unusable,
            ),
        )
    }

    @Test
    fun a_reconnect_after_a_session_existed_is_not_presented_as_a_first_connect() {
        assertEquals(
            ConnectionUiState.Reconnecting,
            deriveConnectionUiState(
                wsState = TransportState.Connecting,
                snapshot = RestModeSnapshot(mode = RestMode.WsActive, liveSessionEpoch = 5L),
                restHealth = unusable,
            ),
        )
    }

    // ── Cold start (review round 7: the machine begins in RestActive) ────────

    @Test
    fun a_cold_start_attempting_its_first_socket_is_connecting_not_offline() {
        assertEquals(
            ConnectionUiState.Connecting,
            deriveConnectionUiState(
                wsState = TransportState.Connecting,
                snapshot = restActive(),
                restHealth = unusable,
            ),
            "the app has just been opened and is dialling: `RestActive` with no session " +
                "is the machine's starting point now, and it must not read as `Offline`",
        )
    }

    @Test
    fun a_cold_start_between_attempts_is_reconnecting_not_offline() {
        assertEquals(
            ConnectionUiState.Reconnecting,
            deriveConnectionUiState(
                wsState = TransportState.Reconnecting,
                snapshot = restActive(),
                restHealth = unusable,
            ),
            "an attempt is in flight; `Offline` is for the state where none is",
        )
    }

    @Test
    fun a_cold_start_whose_attempt_failed_still_shows_the_error() {
        val cause = IllegalStateException("handshake refused")
        val shown = deriveConnectionUiState(
            wsState = TransportState.Error(cause),
            snapshot = restActive(),
            restHealth = unusable,
        )
        assertIs<ConnectionUiState.Error>(shown)
        assertEquals(
            cause, shown.cause,
            "the error row is ahead of the two attempt-in-flight rows: a named failure " +
                "with no fallback is more useful to the user than `Reconnecting`",
        )
    }

    // ── Invariants over the whole input space ────────────────────────────────

    @Test
    fun i4_and_i5_hold_over_every_combination_of_inputs() {
        val wsStates = listOf(
            TransportState.Connected,
            TransportState.Connecting,
            TransportState.Reconnecting,
            TransportState.Disconnected,
            TransportState.Error(IllegalStateException("x")),
        )
        val snapshots = listOf(
            RestModeSnapshot(RestMode.WsActive),
            proven(5),
            RestModeSnapshot(mode = RestMode.WsActive, liveSessionEpoch = 5L),
            candidate(cause = RestRecoveryCause.InboundSilence),
            candidate(cause = RestRecoveryCause.FailureOrUnknown),
            restActive(),
            restActive(live = 5L),
            restActive(cause = RestRecoveryCause.InboundSilence),
        )
        val healths = listOf(usable, unusable, blocked)
        val epochs = listOf<Long?>(null, 5L, 6L)
        val recoveries = RecoveryActivity.entries
        for (ws in wsStates) for (snap in snapshots) for (h in healths) for (e in epochs) for (r in recoveries) {
            val shown = deriveConnectionUiState(ws, snap, h, e, r)
            if (shown == ConnectionUiState.Online) {
                assertTrue(
                    snap.mode == RestMode.WsActive &&
                        snap.provenSessionEpoch != null &&
                        snap.provenSessionEpoch == snap.liveSessionEpoch &&
                        ws == TransportState.Connected &&
                        e == snap.liveSessionEpoch,
                    "I4 broken for ws=$ws snap=$snap health=$h epoch=$e recovery=$r",
                )
            }
            if (shown == ConnectionUiState.Offline) {
                assertTrue(
                    snap.liveSessionEpoch == null && !h.usable && r == RecoveryActivity.Idle,
                    "I5 broken for ws=$ws snap=$snap health=$h epoch=$e recovery=$r",
                )
            }
        }
    }

    // ── The legacy two-input overload still answers ──────────────────────────

    @Test
    fun the_legacy_overload_assumes_no_rest_health_and_no_epoch() {
        assertEquals(
            ConnectionUiState.Offline,
            deriveConnectionUiState(TransportState.Disconnected, RestMode.RestActive),
        )
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(TransportState.Connected, RestMode.WsCandidate),
        )
    }
}
