package phantom.android.transport

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.crypto.Csprng
import phantom.core.transport.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The single session-signal consumer, driven end to end through the real
 * [HybridRelayTransport] and a real [KtorRelayTransport].
 *
 * Stage 2 (2026-09-13) replaced three untagged collectors with one
 * consumer of one ordered channel, so this fixture injects signals at the
 * transport's own enqueue points and asserts what the state machine and
 * the presentation make of them. What it pins:
 *
 *  - a stall of the live session degrades to REST and keeps the session;
 *  - an inbound frame of that same session re-enters candidate, and the
 *    REST poll loop is NOT stopped by the probation;
 *  - an ACK deadline of the same session replaces silence-only evidence
 *    with a failure, which the banner shows as `LimitedRealtime` while
 *    REST is delivering and as `Recovering` once it is not;
 *  - a signal naming a session that is not live changes nothing.
 *
 * Review round 7 (2026-09-13): every wait carries its OWN budget. One
 * `withTimeout` around the whole body reported "the fixture hung" and
 * nothing else, which is how a detach that never returned was first read
 * as a flaky integration test. Each wait now names what it was waiting
 * for, so the next failure points at a step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IdleRecoveryCollectorTest {

    /**
     * Budget for ONE wait, in the virtual time of [runTest]. Deliberately
     * the same 5 s the whole body used to share: the point of the change
     * is to NAME the wait that expires, not to tighten what the fixture
     * tolerates.
     */
    private val stepBudgetMs = 5_000L

    /**
     * Run [block] under its own budget and fail with [what] if it expires.
     * [withTimeoutOrNull] rather than `withTimeout` so the message is the
     * step description instead of a bare `TimeoutCancellationException`.
     */
    private suspend fun <T : Any> step(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(stepBudgetMs) { block() }
            ?: fail("timed out after $stepBudgetMs ms (virtual) waiting for: $what")

    /**
     * Deterministic stand-in for the production [Csprng].
     *
     * The orchestrator draws its backoff jitter from a `Csprng`, and the
     * default is libsodium-backed. This fixture runs on a plain JVM unit
     * test where the native library is never initialised, so the first
     * draw threw `UninitializedPropertyAccessException` inside a poll
     * loop -- swallowed there, invisible in the report, and a source of
     * noise in every diagnosis of this case. Returning the MIDDLE bucket
     * of any bound maps to a jitter factor of exactly 1.0, so every
     * backoff is its nominal value and the virtual clock is exact.
     */
    private class FixedJitterCsprng : Csprng {
        override fun bytes(byteCount: Int): ByteArray =
            error("collector fixture -- bytes() is not on the jitter path")
        override fun hex(byteCount: Int): String =
            error("collector fixture -- hex() is not on the jitter path")
        override fun uniformLong(boundExclusive: Long): Long = boundExclusive / 2
    }

    @Test
    fun the_single_consumer_degrades_recovers_and_ignores_stale_signals() = runTest {
        val job = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(job + dispatcher)
        var ws: KtorRelayTransport? = null
        var orchestrator: RestFallbackOrchestrator? = null
        val polling = CompletableDeferred<Unit>()
        var pollCancelled = false
        try {
            val rest = object : RestFallbackTransport {
                override suspend fun authSession(url: String, body: AuthSessionRequest) = RestFallbackResponse(
                    statusCode = 200, bodyParsed = AuthSessionResponse(
                        token = "idle-test", expiresAt = Long.MAX_VALUE, restFallback = true,
                        maxSendBodyBytes = 4096, pollMaxEnvelopes = 1,
                        mediaCapabilities = MediaCapabilities(), pollHoldSecs = 0, seqMacVerifyKey = "",
                    ), rawBody = "{}", elapsedMs = 1L,
                )
                override suspend fun send(url: String, token: String, idempotencyKey: String,
                    body: SendRequest): RestFallbackResponse<SendResponse> = error("no sends expected")
                override suspend fun ackDeliver(url: String, token: String,
                    body: AckDeliverRequest): RestFallbackResponse<AckDeliverResponse> = error("no ACK expected")
                override suspend fun poll(url: String, token: String, sinceSeq: Long?,
                    longPollOptIn: Boolean, readTimeoutMs: Long?): RestFallbackResponse<PollResponse> {
                    polling.complete(Unit)
                    try { awaitCancellation() } finally { pollCancelled = true }
                }
            }
            val transport = KtorRelayTransport(httpClientFactory = { error("no real network") })
            ws = transport
            // The per-envelope ACK deadline is a 10 s timer. On the
            // transport's own scope that is 10 s of WALL CLOCK, which this
            // fixture can neither reach nor afford to wait for -- it is why
            // the deadline step used to expire against its virtual budget.
            // Pointing the existing production override at the test scope
            // leaves the timer and its duration exactly as production has
            // them, and puts the clock under the test's control.
            transport.installAckDeadlineScopeForIntegrationTest(scope)
            val orch = RestFallbackOrchestrator(
                baseUrl = "https://relay.test", identityHex = "aa".repeat(32),
                signingPubkeyHex = "bb".repeat(32), getChallenge = { "cc".repeat(32) },
                signChallenge = { ByteArray(64) }, transport = rest,
                now = { testScheduler.currentTime }, dispatcher = dispatcher,
                // Idle presentation, not egress policy: allow-all keeps this
                // fixture measuring what it measured before the integration.
                egressGate = RestEgressGate(RestEgressPolicy { RestEgressDecision.DirectAllowed }),
                // Deterministic jitter, and no native library on the path:
                // see [FixedJitterCsprng].
                csprng = FixedJitterCsprng(),
            )
            orchestrator = orch
            val hybrid = HybridRelayTransport(transport, orch, null, scope)
            step("the Hybrid to attach its consumer and bootstrap REST") {
                hybrid.bootstrapAndStart()
            }

            // A live session first: Stage 2 decides by identity, and a
            // signal about a session the machine never saw connect is
            // stale by construction. A handshake makes the session LIVE;
            // it does not make it proven (review round 7).
            transport.simulateSessionConnected(sessionEpoch = 1L)
            step("the consumer to route Connected(1) into the machine") {
                hybrid.stateMachine.snapshot.first { it.liveSessionEpoch == 1L }
            }
            assertEquals(
                RestMode.WsCandidate, hybrid.stateMachine.current,
                "a connect is a candidate: nothing has arrived on the socket yet",
            )

            // A stall of the live session drops the mode and KEEPS the
            // session: a silent socket is not a gone socket.
            transport.simulateInboundStall(sessionEpoch = 1L, sinceLastInboundMs = 60_000)
            step("the live session's stall to degrade the mode") {
                hybrid.stateMachine.snapshot.first { it.mode == RestMode.RestActive }
            }
            step("the REST fallback poll to start") { polling.await() }
            assertEquals(1L, hybrid.stateMachine.liveSessionEpoch)

            // A frame of the SAME session re-enters candidate, and the
            // fallback poll keeps running through the probation.
            transport.simulateInboundDeliver(
                RelayMessage.Deliver(payload = "test-frame", messageId = "test-envelope"),
                sessionEpoch = 1L,
            )
            val candidate = step("an inbound frame to re-enter candidate") {
                hybrid.stateMachine.snapshot.first { it.mode == RestMode.WsCandidate }
            }
            assertEquals(
                ConnectionUiState.LimitedRealtime,
                deriveConnectionUiState(
                    wsState = TransportState.Connected,
                    snapshot = candidate,
                    restHealth = RestHealth(
                        usable = true,
                        lastPollOkAtMs = 0L,
                        networkGeneration = 0L,
                        reason = RestHealthReason.PollOk,
                    ),
                ),
            )
            assertFalse(pollCancelled, "probation must not stop the fallback poll")

            // A signal naming a session that is not live changes nothing.
            transport.simulateInboundStall(sessionEpoch = 999L, sinceLastInboundMs = 60_000)
            assertEquals(RestMode.WsCandidate, hybrid.stateMachine.current)

            // The ACK deadline of the live session replaces
            // silence-only evidence with a failure.
            val entry = transport.newAckPendingForIntegrationTest("late-ack")
            transport.armAckDeadline(entry, sessionEpoch = 1L)
            // Fire the real timer by moving the virtual clock past its real
            // duration. The per-wait budget below stays 5 s virtual on
            // purpose: raising it to cover a 10 s timer would hide exactly
            // the kind of stall these budgets exist to expose.
            advanceTimeBy(RelayTransportConfig.ACK_DEADLINE_MS + 1)
            runCurrent()
            val failed = step("the ACK deadline to replace silence with a failure") {
                hybrid.stateMachine.snapshot.first {
                    it.recoveryCause == RestRecoveryCause.FailureOrUnknown && it.mode == RestMode.RestActive
                }
            }
            assertEquals(
                1L, failed.liveSessionEpoch,
                "an ACK deadline is not a close: the session is degraded, not gone, and " +
                    "which B9 row applies below depends on exactly that",
            )
            // B9, the `RestActive` arm: a KEPT live session with REST
            // delivering is `LimitedRealtime`. The failure is real and is
            // carried in `recoveryCause`, but the banner's job here is to
            // tell the user their messages are still moving -- the
            // transport's own watchdogs own the failed socket. The same
            // contract is pinned from the other direction by
            // `IdleRecoveryPresentationTest.an_ack_timeout_during_probation_updates_the_same_mode_snapshot`.
            //
            // This case asserted `Recovering` until review round 8 pass 4.
            // That expectation predates the `live != null` row and was never
            // reachable once the row existed.
            assertEquals(
                ConnectionUiState.LimitedRealtime,
                deriveConnectionUiState(
                    wsState = TransportState.Connected,
                    snapshot = failed,
                    restHealth = RestHealth(
                        usable = true,
                        lastPollOkAtMs = 0L,
                        networkGeneration = 0L,
                        reason = RestHealthReason.PollOk,
                    ),
                ),
                "with REST delivering, a degraded live session is the fallback banner",
            )
            // The discriminating half: take the fallback away and the SAME
            // snapshot is a recovery. Without this the case would no longer
            // distinguish the failure from an ordinary working state.
            assertEquals(
                ConnectionUiState.Recovering,
                deriveConnectionUiState(
                    wsState = TransportState.Connected,
                    snapshot = failed,
                    restHealth = RestHealth(
                        usable = false,
                        lastPollOkAtMs = null,
                        networkGeneration = 0L,
                        reason = RestHealthReason.Expired,
                    ),
                ),
                "with REST unusable the same state is a recovery, and never Offline: " +
                    "the session lives",
            )
            assertFalse(pollCancelled, "failure must keep fallback running")

            // The Hybrid owns its subscription and releases it. This is the
            // wait that hung before the detach was made atomic, and it is
            // the one that must name itself when it hangs again.
            step("closeAndJoin to cancel the consumer and release the subscription") {
                hybrid.closeAndJoin()
            }
            assertTrue(hybrid.isClosed)
            assertFalse(
                transport.hasSessionSignalConsumer(),
                "closeAndJoin frees the slot for a successor",
            )
        } finally {
            // The fake poll suspends cooperatively; closing joins its finally.
            try { orchestrator?.close() } finally {
                try { job.cancelAndJoin() } finally {
                    ws?.let { assertTrue(it.closeForIntegrationTest()) }
                }
            }
        }
        assertTrue(pollCancelled)
    }
}
