// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.transport.AckDeliverRequest
import phantom.core.transport.AckDeliverResponse
import phantom.core.transport.AuthSessionRequest
import phantom.core.transport.AuthSessionResponse
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.PollResponse
import phantom.core.transport.RelayMessage
import phantom.core.transport.RestEgressDecision
import phantom.core.transport.RestEgressGate
import phantom.core.transport.RestEgressPolicy
import phantom.core.transport.RestFallbackOrchestrator
import phantom.core.transport.RestFallbackResponse
import phantom.core.transport.RestFallbackTransport
import phantom.core.transport.RestMode
import phantom.core.transport.SendRequest
import phantom.core.transport.SendResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 2 B1 (2026-09-13): the Hybrid OWNS the transport's session-signal
 * subscription and every job it launches, and releases all of them.
 *
 * Before Stage 2 its collectors were launched on the injected scope with
 * no handles and the class had no close at all, so a Hybrid abandoned
 * after a failed initialisation kept consuming the transport's flows for
 * the life of the process — and a second Hybrid built by the retry
 * quietly attached a second set of collectors to the same transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HybridSessionSignalOwnershipTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun closeScopes() {
        scopes.forEach { runCatching { it.cancel() } }
        scopes.clear()
    }

    private class SilentRest : RestFallbackTransport {
        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> = RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AuthSessionResponse(
                token = "t", expiresAt = Long.MAX_VALUE, restFallback = true,
                maxSendBodyBytes = 4096, pollMaxEnvelopes = 1, pollHoldSecs = 0,
                seqMacVerifyKey = "",
            ),
            rawBody = "{}", elapsedMs = 1L,
        )

        override suspend fun send(
            url: String, token: String, idempotencyKey: String, body: SendRequest,
        ): RestFallbackResponse<SendResponse> = error("no sends in this fixture")

        override suspend fun ackDeliver(
            url: String, token: String, body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> = error("no acks in this fixture")

        override suspend fun poll(
            url: String, token: String, sinceSeq: Long?, longPollOptIn: Boolean, readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            // Quiet, cancellable: the loops keep running until the
            // orchestrator is closed.
            delay(60_000)
            return RestFallbackResponse(
                statusCode = 200, bodyParsed = PollResponse(), rawBody = "{}", elapsedMs = 1L,
            )
        }
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }

    private fun newOrchestrator(): RestFallbackOrchestrator =
        RestFallbackOrchestrator(
            egressGate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed }),
            baseUrl = "https://relay.test",
            identityHex = "aa".repeat(32),
            signingPubkeyHex = "bb".repeat(32),
            getChallenge = { "cc".repeat(32) },
            signChallenge = { ByteArray(64) },
            transport = SilentRest(),
        )

    @Test
    fun one_hybrid_owns_the_subscription_and_a_second_is_refused() = runBlocking {
        val ws = KtorRelayTransport(httpClientFactory = { error("no network") })
        val orchestratorA = newOrchestrator()
        val hybridA = HybridRelayTransport(ws, orchestratorA, null, newScope())
        hybridA.startWsPassthroughCollectors()
        assertTrue(ws.hasSessionSignalConsumer())

        val orchestratorB = newOrchestrator()
        val hybridB = HybridRelayTransport(ws, orchestratorB, null, newScope())
        assertFailsWith<IllegalStateException> {
            // A second Hybrid against the same transport is a defect, not a
            // fan-out: a channel-backed flow distributes elements between
            // collectors, so the second would STEAL signals from the first.
            hybridB.startWsPassthroughCollectors()
        }

        hybridA.closeAndJoin()
        assertTrue(hybridA.isClosed)
        assertFalse(ws.hasSessionSignalConsumer(), "the slot is free after closeAndJoin")

        // Only now may a successor attach — which is what the init retry does.
        hybridB.startWsPassthroughCollectors()
        assertTrue(ws.hasSessionSignalConsumer())
        hybridB.closeAndJoin()
        orchestratorA.close()
        orchestratorB.close()
    }

    @Test
    fun close_and_join_stops_the_consumer_so_later_signals_change_nothing() = runBlocking {
        val ws = KtorRelayTransport(httpClientFactory = { error("no network") })
        val orchestrator = newOrchestrator()
        val hybrid = HybridRelayTransport(ws, orchestrator, null, newScope())
        hybrid.bootstrapAndStart()

        ws.simulateSessionConnected(sessionEpoch = 1L)
        val live = withTimeoutOrNull(5_000) {
            while (hybrid.stateMachine.liveSessionEpoch != 1L) delay(5)
            true
        }
        assertEquals(true, live, "the consumer must have seen the Connected")

        hybrid.closeAndJoin()
        val modeAtClose = hybrid.stateMachine.current
        val liveAtClose = hybrid.stateMachine.liveSessionEpoch

        // Nothing is consuming now: the signal is buffered on the channel
        // and the machine is untouched.
        ws.simulateSessionEnded(sessionEpoch = 1L)
        delay(100)
        assertEquals(modeAtClose, hybrid.stateMachine.current)
        assertEquals(liveAtClose, hybrid.stateMachine.liveSessionEpoch)
        orchestrator.close()
    }

    @Test
    fun close_and_join_releases_the_orchestrator_event_router() = runBlocking {
        val ws = KtorRelayTransport(httpClientFactory = { error("no network") })
        val orchestrator = newOrchestrator()
        val hybrid = HybridRelayTransport(ws, orchestrator, null, newScope())
        // The Hybrid installs itself as the router so the orchestrator's own
        // producers take the same lock as every other producer (B4).
        assertTrue(orchestrator.eventRouter != null, "the router must be installed at construction")
        hybrid.closeAndJoin()
        assertTrue(
            orchestrator.eventRouter == null,
            "an abandoned Hybrid must not keep routing another instance's events",
        )
        orchestrator.close()
    }

    @Test
    fun close_and_join_is_idempotent_and_refuses_a_later_start() = runBlocking {
        val ws = KtorRelayTransport(httpClientFactory = { error("no network") })
        val orchestrator = newOrchestrator()
        val hybrid = HybridRelayTransport(ws, orchestrator, null, newScope())
        hybrid.startWsPassthroughCollectors()
        hybrid.closeAndJoin()
        hybrid.closeAndJoin()
        assertFailsWith<IllegalStateException> { hybrid.startWsPassthroughCollectors() }
        orchestrator.close()
    }

    @Test
    fun a_session_signal_reaches_the_machine_with_its_identity() = runBlocking {
        val ws = KtorRelayTransport(httpClientFactory = { error("no network") })
        val orchestrator = newOrchestrator()
        val hybrid = HybridRelayTransport(ws, orchestrator, null, newScope())
        hybrid.bootstrapAndStart()

        ws.simulateSessionConnected(sessionEpoch = 4L)
        ws.simulateInboundDeliver(
            RelayMessage.Deliver(payload = "p", messageId = "m1"),
            sessionEpoch = 4L,
        )
        // A stall of a session that is NOT live must change nothing: before
        // Stage 2 this signal carried no epoch and would have degraded the
        // live session.
        ws.simulateInboundStall(sessionEpoch = 99L, sinceLastInboundMs = 61_000)
        delay(200)
        // Review round 7 (2026-09-13): a handshake plus one frame is a
        // CANDIDATE. Reaching `WsActive` from here needs the commit dwell
        // confirmed by an alive tick of the same session, which is the
        // orchestrator's wall-clock loop and does not belong in an
        // ownership fixture — the proof ladder is pinned in
        // `RestStateMachineTest`. What this cell pins is that the signal
        // arrived with its identity and that a foreign one did not.
        assertEquals(RestMode.WsCandidate, hybrid.stateMachine.current)
        assertEquals(4L, hybrid.stateMachine.liveSessionEpoch)
        assertEquals(
            4L, hybrid.stateMachine.snapshot.value.candidateEpoch,
            "the frame of session 4 is recorded against session 4",
        )

        ws.simulateInboundStall(sessionEpoch = 4L, sinceLastInboundMs = 61_000)
        val degraded = withTimeoutOrNull(5_000) {
            while (hybrid.stateMachine.current != RestMode.RestActive) delay(5)
            true
        }
        assertEquals(true, degraded, "the live session's stall must degrade")
        assertEquals(4L, hybrid.stateMachine.liveSessionEpoch, "a silent socket is not a gone socket")

        hybrid.closeAndJoin()
        orchestrator.close()
    }

    @Test
    fun rest_activation_replays_a_socket_that_connected_before_bootstrap() = runBlocking {
        val ws = KtorRelayTransport(httpClientFactory = { error("no network") })
        val orchestrator = newOrchestrator()
        val hybrid = HybridRelayTransport(ws, orchestrator, null, newScope())

        hybrid.startWsPassthroughCollectors()
        ws.simulateSessionConnected(sessionEpoch = 7L, ownerGeneration = 3L)

        // The consumer is alive, but REST has not been activated yet, so the
        // original event cannot mutate the dormant state machine.
        delay(100)
        assertEquals(null, hybrid.stateMachine.liveSessionEpoch)

        hybrid.bootstrapAndStart()
        val replayed = withTimeoutOrNull(5_000) {
            while (hybrid.stateMachine.liveSessionEpoch != 7L) delay(5)
            true
        }
        assertEquals(true, replayed, "activation must adopt the already-live socket")
        assertEquals(RestMode.WsCandidate, hybrid.stateMachine.current)
        assertEquals(7L, hybrid.stateMachine.snapshot.value.candidateEpoch)

        hybrid.closeAndJoin()
        orchestrator.close()
    }
}
