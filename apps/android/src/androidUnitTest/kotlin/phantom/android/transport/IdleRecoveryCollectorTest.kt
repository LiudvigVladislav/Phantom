package phantom.android.transport

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.transport.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IdleRecoveryCollectorTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> events(ws: KtorRelayTransport, name: String): MutableSharedFlow<T> =
        KtorRelayTransport::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(ws) as MutableSharedFlow<T>
        }

    @Test
    fun collectors_keep_polling_and_do_not_drop_a_late_ack_timeout() = runTest {
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
            val orch = RestFallbackOrchestrator(
                baseUrl = "https://relay.test", identityHex = "aa".repeat(32),
                signingPubkeyHex = "bb".repeat(32), getChallenge = { "cc".repeat(32) },
                signChallenge = { ByteArray(64) }, transport = rest,
                now = { testScheduler.currentTime }, dispatcher = dispatcher,
                // Idle presentation, not egress policy: allow-all keeps this
                // fixture measuring what it measured before the integration.
                egressGate = phantom.core.transport.RestEgressGate(
                    phantom.core.transport.RestEgressPolicy {
                        phantom.core.transport.RestEgressDecision.DirectAllowed
                    },
                ),
            )
            orchestrator = orch
            val hybrid = HybridRelayTransport(transport, orch, null, scope)
            withTimeout(5_000) {
                hybrid.bootstrapAndStart()
                val idle = events<InboundStalledEvent>(transport, "_inboundStalled")
                val inbound = events<RelayMessage.Deliver>(transport, "_incoming")
                val deadlines = events<OutboundAckDeadlineExpiredEvent>(transport, "_outboundAckDeadlineExpired")
                idle.subscriptionCount.first { it > 0 }
                inbound.subscriptionCount.first { it > 0 }
                deadlines.subscriptionCount.first { it > 0 }
                idle.emit(InboundStalledEvent(60_000))
                polling.await()
                assertEquals(RestMode.RestActive, hybrid.stateMachine.current)
                inbound.emit(RelayMessage.Deliver(payload = "test-frame", messageId = "test-envelope"))
                val candidate = hybrid.stateMachine.snapshot.first { it.mode == RestMode.WsCandidate }
                assertEquals(ConnectionUiState.LimitedRealtime,
                    deriveConnectionUiState(TransportState.Connected, candidate))
                assertFalse(pollCancelled, "probation must not stop the fallback poll")
                deadlines.emit(OutboundAckDeadlineExpiredEvent("late-ack", 10_000))
                val failed = hybrid.stateMachine.snapshot.first {
                    it.recoveryCause == RestRecoveryCause.FailureOrUnknown
                }
                assertEquals(RestMode.WsCandidate, failed.mode)
                assertEquals(ConnectionUiState.Recovering,
                    deriveConnectionUiState(TransportState.Connected, failed))
                assertFalse(pollCancelled, "failure must keep fallback running")
            }
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
