package phantom.android.transport

import kotlinx.coroutines.test.runTest
import phantom.core.transport.RestMode
import phantom.core.transport.RestModeSnapshot
import phantom.core.transport.RestRecoveryCause
import phantom.core.transport.RestStateMachine
import phantom.core.transport.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Input-driven checks: these simulate transport events, not a network failure. */
class IdleRecoveryPresentationTest {
    private fun present(sm: RestStateMachine, ws: TransportState = TransportState.Connected) =
        deriveConnectionUiState(ws, sm.snapshot.value)

    @Test
    fun a_quiet_chat_keeps_fallback_and_does_not_claim_failure_or_full_recovery() = runTest {
        var now = 0L
        val sm = RestStateMachine(now = { now })
        sm.onEvent(RestStateMachine.Event.InboundIdleTimeout(60_000))
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(ConnectionUiState.LimitedRealtime, present(sm))
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)
        assertEquals(ConnectionUiState.LimitedRealtime, present(sm))
        now = 59_999
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsCandidate, sm.current)
        now = 60_000
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(ConnectionUiState.Online, present(sm))
    }

    @Test
    fun a_missing_inbound_channel_does_not_recover_just_because_time_passes() = runTest {
        var now = 0L
        val sm = RestStateMachine(now = { now })
        sm.onEvent(RestStateMachine.Event.InboundIdleTimeout(60_000))
        now = 600_000
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(ConnectionUiState.LimitedRealtime, present(sm))
    }

    @Test
    fun an_ack_timeout_still_requires_recovery_after_an_inbound_frame() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("test-envelope", 10_000))
        assertEquals(RestMode.RestActive, sm.current)
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    private suspend fun quietCandidate(): RestStateMachine = RestStateMachine(now = { 0L }).also {
        it.onEvent(RestStateMachine.Event.InboundIdleTimeout(60_000))
        it.onEvent(RestStateMachine.Event.WsFrameTextReceived)
    }

    @Test
    fun an_ack_timeout_during_fallback_overrides_silence_without_a_mode_change() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.onEvent(RestStateMachine.Event.InboundIdleTimeout(60_000))
        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("late-ack", 10_000))
        assertEquals(RestMode.RestActive, sm.snapshot.value.mode)
        assertEquals(RestRecoveryCause.FailureOrUnknown, sm.snapshot.value.recoveryCause)
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun an_ack_timeout_during_probation_updates_the_same_mode_snapshot() = runTest {
        val sm = quietCandidate()
        assertEquals(ConnectionUiState.LimitedRealtime, present(sm))
        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("late-ack", 10_000))
        assertEquals(RestMode.WsCandidate, sm.snapshot.value.mode)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun a_closed_session_cannot_be_presented_as_only_silence() = runTest {
        val sm = quietCandidate()
        sm.onEvent(RestStateMachine.Event.WsSessionEnded(80_000, 1, 0, sessionEpoch = 1))
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.WsCandidate, sm.current)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun a_new_session_during_fallback_does_not_inherit_the_old_silence_reason() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.onEvent(RestStateMachine.Event.InboundIdleTimeout(60_000))
        sm.onEvent(RestStateMachine.Event.WsSessionConnected(2))
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun a_route_change_requires_recovery_even_if_it_happens_during_probation() = runTest {
        val sm = quietCandidate()
        sm.onEvent(RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = false))
        assertEquals(RestMode.WsCandidate, sm.current)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun raw_ws_failure_is_not_hidden_by_the_silence_reason() = runTest {
        val sm = quietCandidate()
        for (ws in listOf(TransportState.Connecting, TransportState.Reconnecting,
            TransportState.Disconnected, TransportState.Error(IllegalStateException("test")))) {
            assertEquals(ConnectionUiState.Recovering, present(sm, ws))
        }
    }

    @Test
    fun an_unknown_candidate_never_gets_the_silence_exception() {
        assertEquals(ConnectionUiState.Recovering, deriveConnectionUiState(
            TransportState.Connected, RestModeSnapshot(RestMode.WsCandidate)))
    }

    @Test
    fun prior_failure_is_not_erased_by_a_later_idle_timeout() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.onEvent(RestStateMachine.Event.WsSessionEnded(80_000, 1, 0, sessionEpoch = 1))
        assertEquals(RestMode.WsActive, sm.current)
        sm.onEvent(RestStateMachine.Event.InboundIdleTimeout(60_000))
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun successful_probation_allows_a_later_quiet_period_to_be_only_silence() = runTest {
        var now = 0L
        val sm = RestStateMachine(now = { now })
        sm.onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout("test", 10_000))
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        now = 60_000
        sm.onEvent(RestStateMachine.Event.WsAliveTickElapsed)
        sm.onEvent(RestStateMachine.Event.InboundIdleTimeout(60_000))
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(ConnectionUiState.LimitedRealtime, present(sm))
        assertEquals(RestMode.WsCandidate, sm.current)
    }

    @Test
    fun a_sticky_failure_keeps_its_existing_recovery_gate() = runTest {
        val sm = RestStateMachine(now = { 0L }, mode2FastPathEnabled = true, mode2StickyEnabled = true)
        sm.onEvent(RestStateMachine.Event.WsSessionEnded(
            31_000, 0, 0, okhttpPingTimeoutDetected = true, sessionEpoch = 1))
        sm.onEvent(RestStateMachine.Event.InboundIdleTimeout(60_000))
        sm.onEvent(RestStateMachine.Event.WsFrameTextReceived)
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(RestRecoveryCause.FailureOrUnknown, sm.snapshot.value.recoveryCause)
    }
}
