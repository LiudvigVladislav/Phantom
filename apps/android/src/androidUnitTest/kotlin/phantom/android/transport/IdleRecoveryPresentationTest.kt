package phantom.android.transport

import kotlinx.coroutines.test.runTest
import phantom.core.transport.RestHealth
import phantom.core.transport.RestHealthReason
import phantom.core.transport.RestMode
import phantom.core.transport.RestModeSnapshot
import phantom.core.transport.RestRecoveryCause
import phantom.core.transport.RestStateMachine
import phantom.core.transport.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Input-driven checks: these simulate transport signals, not a network
 * failure, and read the banner the user would see.
 *
 * Stage 2 (2026-09-13) keeps the question this file has always asked —
 * "does a quiet chat look like a failure?" — and adds the one the 19:07
 * phone answered wrongly: "does a dead socket with a working fallback
 * look like nothing works?". Every case here pairs a machine state with
 * the REST health of the moment, because after Stage 2 the banner is a
 * function of both.
 */
class IdleRecoveryPresentationTest {

    private val restWorking = RestHealth(
        usable = true,
        lastPollOkAtMs = 1_000L,
        networkGeneration = 0L,
        reason = RestHealthReason.PollOk,
    )
    private val restDown = RestHealth(
        usable = false,
        lastPollOkAtMs = null,
        networkGeneration = 0L,
        reason = RestHealthReason.Expired,
    )

    private fun present(
        sm: RestStateMachine,
        ws: TransportState = TransportState.Connected,
        health: RestHealth = restWorking,
        epoch: Long? = sm.liveSessionEpoch,
    ) = deriveConnectionUiState(
        wsState = ws,
        snapshot = sm.snapshot.value,
        restHealth = health,
        transportSessionEpoch = epoch,
    )

    private suspend fun RestStateMachine.connect(epoch: Long) =
        onEvent(RestStateMachine.Event.WsSessionConnected(sessionEpoch = epoch))

    private suspend fun RestStateMachine.stall(epoch: Long) =
        onEvent(RestStateMachine.Event.InboundIdleTimeout(sessionEpoch = epoch, sinceLastInboundMs = 60_000))

    private suspend fun RestStateMachine.frame(epoch: Long) =
        onEvent(RestStateMachine.Event.WsFrameTextReceived(sessionEpoch = epoch))

    private suspend fun RestStateMachine.tick(epoch: Long) =
        onEvent(RestStateMachine.Event.WsAliveTickElapsed(sessionEpoch = epoch))

    private suspend fun RestStateMachine.ackTimeout(epoch: Long, id: String) =
        onEvent(RestStateMachine.Event.ActiveOutboundAckTimeout(sessionEpoch = epoch, msgId = id, ageMs = 10_000))

    @Test
    fun a_quiet_chat_keeps_fallback_and_does_not_claim_failure_or_full_recovery() = runTest {
        var now = 0L
        val sm = RestStateMachine(now = { now })
        sm.connect(1)
        sm.stall(1)
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(ConnectionUiState.LimitedRealtime, present(sm))
        sm.frame(1)
        assertEquals(RestMode.WsCandidate, sm.current)
        assertEquals(ConnectionUiState.LimitedRealtime, present(sm))
        now = 59_999
        sm.tick(1)
        assertEquals(RestMode.WsCandidate, sm.current)
        now = 60_000
        sm.tick(1)
        assertEquals(RestMode.WsActive, sm.current)
        assertEquals(ConnectionUiState.Online, present(sm))
    }

    @Test
    fun a_missing_inbound_channel_does_not_recover_just_because_time_passes() = runTest {
        var now = 0L
        val sm = RestStateMachine(now = { now })
        sm.connect(1)
        sm.stall(1)
        now = 600_000
        sm.tick(1)
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(ConnectionUiState.LimitedRealtime, present(sm))
    }

    @Test
    fun an_ack_timeout_still_requires_recovery_after_an_inbound_frame() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.connect(1)
        sm.ackTimeout(1, "test-envelope")
        assertEquals(RestMode.RestActive, sm.current)
        sm.frame(1)
        assertEquals(RestMode.WsCandidate, sm.current)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun an_ack_timeout_during_fallback_overrides_silence_without_a_mode_change() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.connect(1)
        sm.stall(1)
        assertEquals(RestRecoveryCause.InboundSilence, sm.snapshot.value.recoveryCause)
        sm.ackTimeout(1, "late-ack")
        assertEquals(RestMode.RestActive, sm.snapshot.value.mode)
        assertEquals(RestRecoveryCause.FailureOrUnknown, sm.snapshot.value.recoveryCause)
        sm.frame(1)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun an_ack_timeout_during_probation_updates_the_same_mode_snapshot() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.connect(1)
        sm.stall(1)
        sm.frame(1)
        assertEquals(ConnectionUiState.LimitedRealtime, present(sm))
        sm.ackTimeout(1, "late-ack")
        assertEquals(RestMode.RestActive, sm.snapshot.value.mode)
        assertEquals(RestRecoveryCause.FailureOrUnknown, sm.snapshot.value.recoveryCause)
        assertEquals(
            ConnectionUiState.LimitedRealtime, present(sm),
            "the socket is still alive and REST is delivering: B9 shows the fallback, " +
                "and the transport's own watchdogs own the silent socket",
        )
        assertEquals(
            ConnectionUiState.Recovering,
            present(sm, health = restDown),
            "with REST unusable the same state is a recovery, never Offline: the session lives",
        )
    }

    @Test
    fun a_closed_session_cannot_be_presented_as_only_silence() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.connect(1)
        sm.stall(1)
        sm.frame(1)
        sm.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 80_000, inboundFrames = 1, pendingAcksAtClose = 0, sessionEpoch = 1,
            ),
        )
        assertEquals(RestMode.RestActive, sm.current)
        sm.connect(2)
        assertEquals(RestMode.WsCandidate, sm.current)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun a_new_session_during_fallback_does_not_inherit_the_old_silence_reason() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.connect(1)
        sm.stall(1)
        assertEquals(RestRecoveryCause.InboundSilence, sm.snapshot.value.recoveryCause)
        sm.connect(2)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun a_route_change_requires_recovery_even_if_it_happens_during_probation() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.connect(1)
        sm.stall(1)
        sm.frame(1)
        assertEquals(RestMode.WsCandidate, sm.current)
        sm.onEvent(
            RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = false, networkGeneration = 2),
        )
        assertEquals(RestMode.RestActive, sm.current, "a route change invalidates the proof")
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun a_candidate_without_a_working_fallback_is_recovering_whatever_the_socket_says() = runTest {
        // B9 reads the candidate by its CAUSE and by REST health, not by the
        // raw socket state: a `Connected` socket in probation has proved
        // nothing yet, and a `Disconnected` one with REST delivering is not
        // a failure the user needs to see.
        val sm = RestStateMachine(now = { 0L })
        sm.connect(1)
        sm.stall(1)
        sm.frame(1)
        for (ws in listOf(
            TransportState.Connecting, TransportState.Reconnecting,
            TransportState.Disconnected, TransportState.Error(IllegalStateException("test")),
        )) {
            assertEquals(
                ConnectionUiState.Recovering, present(sm, ws, health = restDown),
                "wsState=$ws",
            )
            assertEquals(
                ConnectionUiState.LimitedRealtime, present(sm, ws, health = restWorking),
                "wsState=$ws",
            )
        }
    }

    @Test
    fun an_unknown_candidate_never_gets_the_silence_exception() {
        assertEquals(
            ConnectionUiState.Recovering,
            deriveConnectionUiState(
                wsState = TransportState.Connected,
                snapshot = RestModeSnapshot(RestMode.WsCandidate),
                restHealth = restWorking,
            ),
        )
    }

    @Test
    fun prior_failure_is_not_erased_by_a_later_idle_timeout() = runTest {
        val sm = RestStateMachine(now = { 0L })
        sm.connect(1)
        sm.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 80_000, inboundFrames = 1, pendingAcksAtClose = 0, sessionEpoch = 1,
            ),
        )
        assertEquals(RestMode.RestActive, sm.current)
        sm.connect(2)
        sm.stall(2)
        sm.frame(2)
        assertEquals(ConnectionUiState.Recovering, present(sm))
    }

    @Test
    fun a_dead_socket_with_a_working_fallback_is_never_offline() = runTest {
        // The 19:07 sequence, read as the user would read it.
        val sm = RestStateMachine(now = { 0L })
        sm.connect(1)
        sm.onEvent(
            RestStateMachine.Event.WsSessionEnded(
                durationMs = 31_000, inboundFrames = 0, pendingAcksAtClose = 1, sessionEpoch = 1,
            ),
        )
        sm.onEvent(
            RestStateMachine.Event.NetworkChanged(clearsMode2Sticky = true, networkGeneration = 2),
        )
        assertEquals(RestMode.RestActive, sm.current)
        assertEquals(
            ConnectionUiState.Recovering,
            present(sm, ws = TransportState.Disconnected, health = restWorking, epoch = null),
            "REST is answering: the user must not be told messages are only queued",
        )
        assertEquals(
            ConnectionUiState.Offline,
            present(sm, ws = TransportState.Disconnected, health = restDown, epoch = null),
            "when REST is unusable too, `Offline` is the honest banner",
        )
    }
}
