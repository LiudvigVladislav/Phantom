// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * Round 12 — Decision B contract regression test.
 *
 * Pins the four load-bearing invariants of the REST poll
 * body-timeout-after-headers failure shape per
 * `docs/tracks/trek2-stage2b-b-deployment-gate-and-body-timeout-contract.md`
 * § Decision B.
 *
 * When the REST poll path receives `HTTP 200 OK` response headers from
 * the relay but the response body does NOT complete reading before the
 * configured OkHttp call / read timeout fires, the client MUST:
 *
 *   1. Preserve the cursor. `lastSeenSeq` stays at its current value.
 *      No advance. No regression. No partial-state write.
 *   2. Suppress ack and emit. Do NOT call `ackInboundAndAdvanceCursor`
 *      for any envelope. The poll response is treated as if zero
 *      envelopes were delivered, unless the response body has been
 *      fully read, parsed, authenticated, and accepted by the normal
 *      envelope pipeline.
 *   3. Account toward the breaker. The failure is recorded via
 *      [RestFallbackOrchestrator.recordRestFailureForTest] /
 *      `recordRestFailure` and contributes to the L9
 *      `ConsecutiveRestFailures` counter exactly like a
 *      transport-class poll failure.
 *   4. Retry on the next cooldown cycle. No immediate retry. The
 *      standard breaker progression
 *      (`Closed → Open → HalfOpen → probe`) handles the back-off.
 *
 * Test seam mechanics. The contract specifies the OkHttp call/read
 * timeout firing AFTER `responseHeadersEnd` but BEFORE the body is
 * fully consumed. At the [RestFallbackTransport] interface level
 * (which is upstream of OkHttp), this manifests as `poll(...)`
 * throwing an `IOException`-class exception. The orchestrator's
 * `pollLoop` wraps the call in `runCatching`, observes the failure,
 * and runs the four-invariant path that this test pins. From the
 * orchestrator's point of view there is no observable difference
 * between "OkHttp threw mid-body" and "transport threw any other
 * IOException-class exception" — so a vanilla [RuntimeException] from
 * the transport script faithfully simulates the contract's failure
 * mode without introducing JVM-only `InterruptedIOException` into
 * commonTest.
 */
class BodyTimeoutContractTest {

    private val IDENTITY: String = "aa".repeat(32)

    // Round 13 follow-up — the previous version of this fence read the
    // KDoc literally ("no libsodium primitive is invoked along this path")
    // and short-circuited `init()` to a no-op. That broke the test
    // because the orchestrator's jitter helper still pulls `Csprng`
    // bytes, and `Csprng` lives behind libsodium. Matches the established
    // pattern across ~10 commonTest files in transport+messaging+crypto:
    // libsodium-via-`ionspin/kotlin-cryptography` is the project's
    // convention for KMP test setup. iOS portability of the test
    // initializer is a separate KMP migration concern tracked in
    // `docs/tech_debt.md` Bug H and addressed by a future blanket move,
    // not by this one test.
    private suspend fun init() {
        if (!com.ionspin.kotlin.crypto.LibsodiumInitializer.isInitialized()) {
            com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        }
    }

    // ── Invariant 1 — cursor preserved ──────────────────────────────────────

    @Test
    fun r12_body_timeout_after_headers_preserves_cursor() = runTest(timeout = 5.minutes) {
        init()
        val cursor = RecordingCursorRepo(initialStored = 7L)
        val transport = BodyTimeoutTestTransport(
            pollScript = { _ ->
                throw RuntimeException("simulated body-read timeout (200 headers received, body never completed)")
            },
        )
        val orch = buildOrchestrator(transport, testScheduler, cursor = cursor)
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive)
        orch.start()
        runCurrent()
        // Advance enough virtual time for the loop to attempt multiple
        // polls and have each one fail with the simulated body
        // timeout. Each iteration delays for `intervalMs.coerceAtLeast(
        // POLL_FAIL_BACKOFF_MS)` jittered (~5_000-6_000 ms), so 30
        // seconds is comfortably 3-5 attempts.
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(
            transport.pollCalls.size >= 2,
            "expected at least 2 poll attempts under simulated body timeout; got ${transport.pollCalls.size}",
        )
        assertEquals(
            7L, cursor.stored,
            "Decision B invariant 1: cursor MUST be preserved at its prior value across body-timeout failures. " +
                "Initial stored=7, post-failures stored=${cursor.stored}.",
        )
        assertEquals(
            0, cursor.writes.size,
            "Decision B invariant 1: cursor MUST NOT be written across body-timeout failures. " +
                "Got writes=${cursor.writes}.",
        )
        orch.stop()
        runCurrent()
    }

    // ── Invariant 2 — no ack call ───────────────────────────────────────────

    @Test
    fun r12_body_timeout_after_headers_suppresses_ack() = runTest(timeout = 5.minutes) {
        init()
        val transport = BodyTimeoutTestTransport(
            pollScript = { _ ->
                throw RuntimeException("simulated body-read timeout")
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive)
        orch.start()
        runCurrent()
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(
            transport.pollCalls.size >= 2,
            "expected at least 2 poll attempts; got ${transport.pollCalls.size}",
        )
        assertEquals(
            0, transport.ackCalls.size,
            "Decision B invariant 2: ack MUST NOT be called for any envelope when the poll body times out " +
                "after headers. The poll response is treated as if zero envelopes were delivered. " +
                "Got ackCalls=${transport.ackCalls}.",
        )
        orch.stop()
        runCurrent()
    }

    // ── Invariant 3 — breaker accounting ────────────────────────────────────

    @Test
    fun r12_body_timeout_after_headers_accounts_toward_breaker() = runTest(timeout = 5.minutes) {
        init()
        val transport = BodyTimeoutTestTransport(
            pollScript = { _ ->
                throw RuntimeException("simulated body-read timeout")
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive)
        // Initial state: breaker Closed.
        assertEquals(
            LongPollBreakerState.Closed,
            orch.peekBreakerStateForTest(),
            "pre-condition: breaker must start Closed before the first poll attempt",
        )
        orch.start()
        runCurrent()
        // Run enough virtual time to land at least
        // BREAKER_CONSECUTIVE_FAIL_THRESHOLD (= 5) consecutive
        // failures. Each iteration delays roughly POLL_FAIL_BACKOFF_MS
        // (5_000 ms) jittered, so 60 seconds covers ~10 attempts and
        // leaves slack for jitter ceiling.
        advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(
            transport.pollCalls.size >= RestFallbackOrchestrator.BREAKER_CONSECUTIVE_FAIL_THRESHOLD,
            "expected at least ${RestFallbackOrchestrator.BREAKER_CONSECUTIVE_FAIL_THRESHOLD} poll attempts " +
                "under sustained body timeout; got ${transport.pollCalls.size}",
        )
        val finalState = orch.peekBreakerStateForTest()
        assertTrue(
            finalState is LongPollBreakerState.Open,
            "Decision B invariant 3: after ${RestFallbackOrchestrator.BREAKER_CONSECUTIVE_FAIL_THRESHOLD} " +
                "consecutive body-timeout failures, the breaker MUST transition to Open. " +
                "Got finalState=$finalState.",
        )
        assertEquals(
            BreakerOpenReason.ConsecutiveRestFailures,
            (finalState as LongPollBreakerState.Open).reason,
            "Decision B invariant 3: body-timeout failures count exactly as transport-class poll failures; " +
                "the Open reason MUST be ConsecutiveRestFailures (not Status410Storm or another class).",
        )
        orch.stop()
        runCurrent()
    }

    // ── Invariant 4 — no immediate retry; cooldown progression ──────────────

    @Test
    fun r12_body_timeout_after_headers_does_not_retry_immediately() = runTest(timeout = 5.minutes) {
        init()
        val transport = BodyTimeoutTestTransport(
            pollScript = { _ ->
                throw RuntimeException("simulated body-read timeout")
            },
        )
        val orch = buildOrchestrator(transport, testScheduler)
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive)
        orch.start()
        runCurrent()
        // Advance just past the active poll cadence so the first poll
        // fires and fails.
        advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
        runCurrent()
        val callsAfterFirstFailure = transport.pollCalls.size
        assertTrue(
            callsAfterFirstFailure >= 1,
            "expected the first poll attempt to fire and fail; got pollCalls.size=$callsAfterFirstFailure",
        )
        // Now advance by 1 second. This is WELL UNDER
        // POLL_FAIL_BACKOFF_MS (5_000 ms). Decision B invariant 4
        // requires the loop NOT to retry immediately — the next poll
        // must wait for the cooldown.
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(
            callsAfterFirstFailure,
            transport.pollCalls.size,
            "Decision B invariant 4: after a body-timeout failure, the orchestrator MUST NOT retry within " +
                "1 second. The cooldown is at least POLL_FAIL_BACKOFF_MS (5_000 ms). " +
                "Got pollCalls.size=${transport.pollCalls.size} after a 1-second advance, " +
                "expected $callsAfterFirstFailure.",
        )
        // Advance well past the jittered backoff ceiling
        // (POLL_FAIL_BACKOFF_MS * 1.2 = 6_000 ms) plus slack. The next
        // poll attempt must land in this window.
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(
            transport.pollCalls.size > callsAfterFirstFailure,
            "Decision B invariant 4: after the cooldown elapses, the orchestrator MUST resume polling. " +
                "Got pollCalls.size=${transport.pollCalls.size}, expected > $callsAfterFirstFailure " +
                "after a 10-second advance past the failure.",
        )
        orch.stop()
        runCurrent()
    }

    // ── Round 12 step 2 — hold_secs structured field on poll_call ──────────

    @Test
    fun r12_poll_call_log_includes_hold_secs_field() = runTest(timeout = 5.minutes) {
        // Per Round 12 step 2 instrumentation: the legacy `pollLoop`
        // poll_call log line carries the server-advertised
        // `pollHoldSecs` as a structured field. The S6 council on
        // d395f682 identified that the absence of this single field
        // caused a 30-minute field run to be ambiguous on the
        // kill-switch precondition. The test pins the field's
        // presence so a future refactor that strips it surfaces
        // here, not after a wasted re-test.
        init()
        val logLines = mutableListOf<String>()
        val transport = BodyTimeoutTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(
            transport = transport,
            scheduler = testScheduler,
            logSink = { logLines += it },
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive)
        orch.start()
        runCurrent()
        advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
        runCurrent()
        val pollCallLine = logLines.firstOrNull { it.contains("REST_TRACE poll_call ") }
        assertTrue(
            pollCallLine != null,
            "expected at least one `REST_TRACE poll_call ` line during a single poll iteration; " +
                "got logLines.size=${logLines.size}",
        )
        assertTrue(
            pollCallLine!!.contains("hold_secs="),
            "Round 12 step 2: poll_call log line MUST carry the structured field `hold_secs=<n>`. " +
                "Got: $pollCallLine",
        )
        // The test transport session response advertised `pollHoldSecs = 30`
        // — assert the value flowed through the capabilities snapshot to
        // the log line. A drift here proves the field is decoupled from
        // the actual server-advertised value, which would defeat the
        // purpose of the field (operator could not tell from logs what
        // the server said).
        assertTrue(
            pollCallLine.contains("hold_secs=30"),
            "Round 12 step 2: poll_call log MUST emit the server-advertised `pollHoldSecs` value. " +
                "Test transport advertised pollHoldSecs=30; got: $pollCallLine",
        )
        orch.stop()
        runCurrent()
    }

    @Test
    fun r12_ws_active_poll_call_log_includes_hold_secs_field() = runTest(timeout = 5.minutes) {
        // Mirror of the legacy-loop test for the parallel
        // `wsActivePollLoop` poll_call site. Both poll origins MUST
        // carry the same `hold_secs` field so a single grep covers
        // both. Without this pin, a future refactor that adds the
        // field to one site and not the other would create an
        // observability gap.
        init()
        val logLines = mutableListOf<String>()
        val transport = BodyTimeoutTestTransport(
            pollScript = { _ ->
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            },
        )
        val orch = buildOrchestrator(
            transport = transport,
            scheduler = testScheduler,
            logSink = { logLines += it },
            longPollEnabled = true,
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        orch.start()
        runCurrent()
        advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
        runCurrent()
        val wsPollCallLine = logLines.firstOrNull { it.contains("REST_TRACE ws_active_poll_call ") }
        assertTrue(
            wsPollCallLine != null,
            "expected at least one `REST_TRACE ws_active_poll_call ` line during a single iteration; " +
                "got logLines.size=${logLines.size}",
        )
        assertTrue(
            wsPollCallLine!!.contains("hold_secs="),
            "Round 12 step 2: ws_active_poll_call log line MUST carry the structured field " +
                "`hold_secs=<n>`. Got: $wsPollCallLine",
        )
        assertTrue(
            wsPollCallLine.contains("hold_secs=30"),
            "Round 12 step 2: ws_active_poll_call log MUST emit the server-advertised value. " +
                "Test transport advertised pollHoldSecs=30; got: $wsPollCallLine",
        )
        orch.stop()
        runCurrent()
    }

    // ── Test infrastructure (inline, single-file scope) ─────────────────────

    /**
     * Minimal scriptable transport for the body-timeout contract
     * tests. Distinct from the breaker-test sibling so the contract
     * test stays self-contained and the body-timeout failure shape is
     * the only thing the script can express.
     */
    private class BodyTimeoutTestTransport(
        var pollScript: suspend (callIndex: Int) -> RestFallbackResponse<PollResponse>,
    ) : RestFallbackTransport {
        val pollCalls: MutableList<Long?> = mutableListOf()
        val authCalls: MutableList<Unit> = mutableListOf()
        val ackCalls: MutableList<String> = mutableListOf()

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> {
            authCalls += Unit
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "tok-${authCalls.size}",
                    expiresAt = Long.MAX_VALUE,
                    restFallback = true,
                    maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1,
                    pollHoldSecs = 30,
                    seqMacVerifyKey = "",
                ),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        override suspend fun send(
            url: String, token: String, idempotencyKey: String, body: SendRequest,
        ): RestFallbackResponse<SendResponse> =
            fail("send not used in body-timeout contract tests")

        override suspend fun poll(
            url: String, token: String, sinceSeq: Long?,
            longPollOptIn: Boolean, readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            pollCalls += sinceSeq
            return pollScript(pollCalls.size - 1)
        }

        override suspend fun ackDeliver(
            url: String, token: String, body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> {
            ackCalls += body.id
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AckDeliverResponse(ok = 1),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }
    }

    /**
     * Cursor repo that records every upsert and exposes the current
     * stored value. Used by the cursor-preservation invariant test.
     */
    private class RecordingCursorRepo(
        initialStored: Long? = null,
    ) : LongPollCursorRepository {
        val writes: MutableList<Pair<Long, Long>> = mutableListOf()
        var stored: Long? = initialStored

        override suspend fun getLastSeenSeq(identityHex: String): Long? = stored

        override suspend fun upsertLastSeenSeq(
            identityHex: String,
            seq: Long,
            nowMs: Long,
        ): CursorUpsertOutcome {
            val previous = stored
            if (previous != null && previous >= seq) {
                return CursorUpsertOutcome.NoChange(previous)
            }
            writes += seq to nowMs
            stored = maxOf(previous ?: Long.MIN_VALUE, seq)
            return CursorUpsertOutcome.Advanced(seq)
        }
    }

    private fun buildOrchestrator(
        transport: BodyTimeoutTestTransport,
        scheduler: TestCoroutineScheduler,
        cursor: LongPollCursorRepository = RecordingCursorRepo(),
        logSink: (String) -> Unit = {},
        longPollEnabled: Boolean = false,
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        baseUrl = "https://relay.test",
        identityHex = IDENTITY,
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = transport,
        now = { 0L },
        log = logSink,
        longPollEnabled = longPollEnabled,
        cursorRepository = cursor,
        dispatcher = StandardTestDispatcher(scheduler),
    )
}
