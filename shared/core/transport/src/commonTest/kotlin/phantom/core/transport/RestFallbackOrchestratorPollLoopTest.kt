// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C3 review-fix round 2) — behavioural tests that
 * drive `orchestrator.start()` and exercise BOTH the legacy `pollLoop`
 * and the `wsActivePollLoop` simultaneously.
 *
 * Companion to [AckInboundAndAdvanceCursorTest], which unit-tests
 * `ackInboundAndAdvanceCursor` in isolation via the internal
 * `primePendingSeqForAckForTest` seam. The tests in THIS file land
 * the broader contract:
 *
 *   * **M12 (real two-loop concurrent)** — both REST poll loops
 *     run simultaneously; a barrier inside the FakeTransport blocks
 *     the first poll until the second loop has also entered poll
 *     (proves concurrent execution by construction — if only one
 *     loop existed, the test would deadlock). Envelopes are
 *     interleaved across both loops; the monotonic cursor mirror
 *     pins the storage-side D5 contract.
 *   * **Cursor read failure backoff (P2 review-fix)** — for each
 *     poll loop, a `getLastSeenSeq` exception MUST cause the loop
 *     to skip the current poll iteration and back off, NOT degrade
 *     to `since_seq=null` and replay the entire retention window
 *     on every subsequent iteration. Tests verify the
 *     `poll_call_skipped reason=cursor_read_fail` log line fires
 *     and `transport.poll(...)` is NOT called for that iteration.
 */
class RestFallbackOrchestratorPollLoopTest {

    private val IDENTITY: String = "aa".repeat(32)

    private val SESSION_RESPONSE_OK: RestFallbackResponse<AuthSessionResponse> =
        RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AuthSessionResponse(
                token = "test-token",
                expiresAt = Long.MAX_VALUE,
                restFallback = true,
                maxSendBodyBytes = 4096,
                pollMaxEnvelopes = 1,
                pollHoldSecs = 30,
                seqMacVerifyKey = "f".repeat(64),
            ),
            rawBody = "{}",
            elapsedMs = 1L,
        )

    private fun pollEnvelope(seq: Long): PollEnvelope = PollEnvelope(
        id = "env-$seq",
        fromHex = "ff".repeat(32),
        payloadBase64 = "",
        sequenceTs = 60_000L,
        seq = seq,
    )

    /**
     * FakeTransport tailored to multi-loop scenarios. Two modes:
     *
     *   * **Default** (no barrier) — returns the next envelope from
     *     `envelopeQueue` on every `poll` call until the queue is
     *     empty (then returns empty `PollResponse`). Used by the
     *     cursor-read-failure tests below.
     *
     *   * **`pollBarrier = true`** — load-bearing concurrency proof
     *     for the M12 cell. The FIRST distinct `poll` call suspends
     *     on `secondPollLanded.await()`; the SECOND completes the
     *     deferred and proceeds. The orchestrator's two REST poll
     *     loops MUST both enter `poll(...)` for the barrier to
     *     resolve — if only one loop spawned, the test's
     *     `withTimeout` on `secondPollLanded.await()` fires and the
     *     test fails by name. Subsequent calls (after the barrier
     *     has fired) proceed without suspending.
     */
    private class MultiLoopFakeTransport(
        val envelopeQueue: ArrayDeque<PollEnvelope> = ArrayDeque(),
    ) : RestFallbackTransport {

        var sessionScript: () -> RestFallbackResponse<AuthSessionResponse> = {
            error("sessionScript not set")
        }
        var ackScript: (envelopeId: String) -> RestFallbackResponse<AckDeliverResponse> = { _ ->
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AckDeliverResponse(ok = 1),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        var pollBarrier: Boolean = false
        val firstPollLanded: CompletableDeferred<Unit> = CompletableDeferred()
        val secondPollLanded: CompletableDeferred<Unit> = CompletableDeferred()

        val pollCountMutex: Mutex = Mutex()
        var pollEnterCount: Int = 0
        val ackCalls: MutableList<String> = mutableListOf()

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> = sessionScript()

        override suspend fun send(
            url: String,
            token: String,
            idempotencyKey: String,
            body: SendRequest,
        ): RestFallbackResponse<SendResponse> = fail("send unexpected in poll-loop tests")

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            val n = pollCountMutex.withLock {
                pollEnterCount += 1
                pollEnterCount
            }
            if (pollBarrier) {
                when (n) {
                    1 -> {
                        firstPollLanded.complete(Unit)
                        // Block until the SECOND distinct poll caller
                        // also enters — proves both loops are active.
                        // Subsequent calls (n > 2) bypass the barrier.
                        secondPollLanded.await()
                    }
                    2 -> {
                        secondPollLanded.complete(Unit)
                    }
                }
            }
            val envelope = pollCountMutex.withLock {
                if (envelopeQueue.isEmpty()) null else envelopeQueue.removeFirst()
            }
            return RestFallbackResponse(
                statusCode = 200,
                bodyParsed = PollResponse(
                    envelopes = listOfNotNull(envelope),
                    more = pollCountMutex.withLock { envelopeQueue.isNotEmpty() },
                ),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> {
            pollCountMutex.withLock { ackCalls += body.id }
            return ackScript(body.id)
        }
    }

    /**
     * Monotonic cursor mirror that enforces the SQLDelight no-op
     * contract: writes with `newSeq <= persisted` are no-ops.
     * Records the full call log on both accepted and rejected
     * attempts so the M12 assertions can pin the property as an
     * end-to-end invariant.
     */
    private class MonotonicCursorRepo(
        initial: Long? = null,
        var readHook: (suspend (identityHex: String) -> Unit)? = null,
    ) : LongPollCursorRepository {
        private val lock = Mutex()
        private var persisted: Long? = initial
        val reads: MutableList<String> = mutableListOf()
        val attempts: MutableList<Long> = mutableListOf()
        val accepted: MutableList<Long> = mutableListOf()
        val persistedSeq: Long? get() = persisted

        override suspend fun getLastSeenSeq(identityHex: String): Long? {
            readHook?.invoke(identityHex)
            return lock.withLock {
                reads += identityHex
                persisted
            }
        }

        override suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long) {
            lock.withLock {
                attempts += seq
                val current = persisted
                if (current == null || seq > current) {
                    persisted = seq
                    accepted += seq
                }
            }
        }
    }

    private fun buildOrchestrator(
        transport: MultiLoopFakeTransport,
        cursor: LongPollCursorRepository?,
        scheduler: TestCoroutineScheduler,
        longPollEnabled: Boolean = true,
        logSink: (String) -> Unit = {},
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
        // C3 review-fix round 2 — StandardTestDispatcher (not
        // Unconfined) sharing the runTest scheduler. Reasoning:
        // Unconfined runs launched bodies eagerly inline, which
        // makes it impossible for the test to explicitly schedule
        // pump steps (`runCurrent()`) between barrier arrivals.
        // Standard enqueues every launch; `runCurrent()` then
        // pumps exactly the tasks ready at the current virtual
        // time, leaving long-lived `delay()` suspensions in place
        // until `advanceTimeBy` wakes them. This is what lets the
        // M12 barrier test pass deterministically without the
        // earlier UnconfinedTestDispatcher deadlock.
        dispatcher = StandardTestDispatcher(scheduler),
    )

    /**
     * Drive the orchestrator's state machine out of `WsActive` so
     * the legacy `pollLoop` spawns alongside the parallel
     * `wsActivePollLoop`. The threshold-based transition takes
     * `ACTIVE_FAIL_THRESHOLD = 2` `WsSessionEnded` events with zero
     * inbound frames + positive pending acks.
     */
    private fun driveStateToRestActive(orch: RestFallbackOrchestrator) {
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L,
                    inboundFrames = 0,
                    pendingAcksAtClose = 1,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive) {
            "expected RestActive after threshold flips; was ${orch.stateMachine.state.value}"
        }
    }

    // ── M12 — real two-loop concurrent test with barrier proof ──────────────

    @Test
    fun m12_two_poll_loops_concurrent_barrier_resolves_envelopes_processed_monotonically() = runTest {
        // Load-bearing test for the scope-doc M12 cell. Drives BOTH
        // the legacy `pollLoop` AND the parallel `wsActivePollLoop`
        // via `orchestrator.start()`. A barrier inside the
        // FakeTransport's `poll(...)` blocks the FIRST poll caller
        // on `secondPollLanded.await()`; the SECOND poll caller
        // completes the deferred and proceeds. If only one loop
        // spawned, `secondPollLanded` is NEVER completed and the
        // test's `withTimeout` on the await fires — concurrent
        // execution of both poll loops is the load-bearing
        // assertion this test makes.
        //
        // Dispatcher discipline: orchestrator uses
        // `StandardTestDispatcher(testScheduler)`. We pump with
        // `runCurrent()` (executes tasks ready at the current
        // virtual time without advancing time) and explicit
        // `advanceTimeBy(...)` only when we WANT to step through
        // poll-loop delays. This avoids the
        // `advanceUntilIdle()`-against-infinite-poll-loops deadlock
        // that an earlier draft tripped.
        //
        // Envelopes seq = [3, 5, 4, 6] are returned across the
        // interleaved poll calls (which loop picks up which is
        // scheduler-dependent). The MonotonicCursorRepo mirrors
        // the SQLDelight no-op-on-lower contract.
        val transport = MultiLoopFakeTransport(
            envelopeQueue = ArrayDeque(listOf(
                pollEnvelope(3L),
                pollEnvelope(5L),
                pollEnvelope(4L),
                pollEnvelope(6L),
            )),
        ).apply {
            sessionScript = { SESSION_RESPONSE_OK }
            pollBarrier = true
        }
        val cursor = MonotonicCursorRepo()
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(
            transport,
            cursor,
            scheduler = testScheduler,
            longPollEnabled = true,
            logSink = { logLines += it },
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        // Flip state so the legacy pollLoop also spawns under start().
        driveStateToRestActive(orch)

        // Register the inbound collector BEFORE start() so emits
        // from both loops are observed.
        val received = mutableListOf<PollEnvelope>()
        val collectorJob = launch {
            orch.inbound.collect { env ->
                received += env
                orch.ackInboundAndAdvanceCursor(env.id)
            }
        }

        orch.start()
        // Pump tasks scheduled at the current virtual time. With
        // StandardTestDispatcher, both poll-loop launches plus
        // their bodies up to the first suspension (the barrier
        // await on the first loop, the barrier complete on the
        // second) execute here.
        runCurrent()

        // Load-bearing concurrency proof: BOTH poll callers
        // arrived at `transport.poll(...)`. We await both
        // deferreds under a virtual-time timeout — if only one
        // poll loop spawned, `secondPollLanded` never completes
        // and the test fails by name.
        withTimeout(5_000L) {
            transport.firstPollLanded.await()
            transport.secondPollLanded.await()
        }

        // Both poll loops processed their first envelopes (one each
        // from the barrier resolution). To pick up the remaining
        // two envelopes, we step virtual time forward over the
        // poll-loop's `delay(intervalMs)` between iterations.
        // Bound the budget so an infinite-loop regression fails
        // noisily.
        var pumpIterations = 0
        while (received.size < 4 && pumpIterations < 50) {
            // POLL_ACTIVE_MS = 1_000L; one second per iteration is
            // ample headroom.
            advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
            runCurrent()
            pumpIterations += 1
        }

        // Tear down explicitly so leaked coroutines surface in this
        // test rather than masking failures of later tests.
        orch.stop()
        collectorJob.cancel()
        runCurrent()

        // Teardown invariants — neither poll loop nor the inbound
        // collector remains active.
        assertTrue(
            !collectorJob.isActive,
            "inbound collector must be torn down by test exit; was active=${collectorJob.isActive}",
        )

        // Functional assertions.
        assertEquals(
            4,
            received.size,
            "all four envelopes must reach the consumer; received ${received.map { it.seq }} " +
                "after $pumpIterations pump iterations",
        )
        assertEquals(setOf(3L, 5L, 4L, 6L), received.map { it.seq }.toSet())
        assertEquals(4, transport.ackCalls.size, "all four envelopes acked")
        assertEquals(setOf("env-3", "env-5", "env-4", "env-6"), transport.ackCalls.toSet())

        // M12 monotonicity: accepted writes strictly increasing,
        // persisted value at the max.
        for (i in 1 until cursor.accepted.size) {
            assertTrue(
                cursor.accepted[i] > cursor.accepted[i - 1],
                "accepted writes must be strictly increasing; saw ${cursor.accepted}",
            )
        }
        assertEquals(6L, cursor.persistedSeq, "final persisted cursor at max")
    }

    // ── P2 review-fix — cursor read failure skips poll + backs off ──────────

    @Test
    fun pollLoop_cursor_read_failure_skips_poll_iteration_then_recovers() = runTest {
        // Inject a single read failure followed by recovery. The
        // legacy pollLoop must:
        //   1. Log `poll_call_skipped reason=cursor_read_fail`.
        //   2. NOT call `transport.poll(...)` for that iteration.
        //   3. Back off and retry the read on the next iteration.
        //   4. On read recovery, resume normal polling.
        val transport = MultiLoopFakeTransport().apply {
            sessionScript = { SESSION_RESPONSE_OK }
            envelopeQueue.add(pollEnvelope(11L))
        }
        var readCallCount = 0
        val cursor = MonotonicCursorRepo().apply {
            readHook = { _ ->
                readCallCount++
                if (readCallCount == 1) {
                    throw RuntimeException("simulated SQLCipher I/O on first read")
                }
            }
        }
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(
            transport,
            cursor,
            scheduler = testScheduler,
            longPollEnabled = false, // legacy pollLoop only
            logSink = { logLines += it },
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)
        driveStateToRestActive(orch)

        val received = mutableListOf<PollEnvelope>()
        val collectorJob = launch {
            orch.inbound.collect { env ->
                received += env
                orch.ackInboundAndAdvanceCursor(env.id)
            }
        }

        orch.start()
        runCurrent()

        // Pump time across the backoff + next iteration. The skip
        // path uses POLL_FAIL_BACKOFF_MS = 5_000 ms; the recovery
        // iteration adds another poll cadence. Generous budget.
        var pumpIterations = 0
        while (received.isEmpty() && pumpIterations < 20) {
            advanceTimeBy(RestFallbackOrchestrator.POLL_FAIL_BACKOFF_MS + 1_000L)
            runCurrent()
            pumpIterations += 1
        }

        orch.stop()
        collectorJob.cancel()
        runCurrent()

        assertTrue(!collectorJob.isActive, "inbound collector must be torn down")
        assertTrue(readCallCount >= 2, "cursor read retried after initial failure")
        // The skipped iteration logged the discriminating reason.
        assertTrue(
            logLines.any { it.contains("poll_call_skipped") && it.contains("cursor_read_fail") },
            "expected `poll_call_skipped reason=cursor_read_fail` log; got:\n" +
                logLines.joinToString("\n"),
        )
        // The recovery iteration actually polled and got the envelope.
        assertEquals(1, received.size, "exactly one envelope received after recovery")
        assertEquals(11L, received.single().seq)
    }

    @Test
    fun wsActivePollLoop_cursor_read_failure_skips_poll_iteration_then_recovers() = runTest {
        // Mirror of the legacy-loop test, on the parallel
        // `wsActivePollLoop`. The same P2 review-fix discipline
        // applies: a single read failure skips that iteration and
        // backs off; recovery resumes normal polling.
        val transport = MultiLoopFakeTransport().apply {
            sessionScript = { SESSION_RESPONSE_OK }
            envelopeQueue.add(pollEnvelope(13L))
        }
        var readCallCount = 0
        val cursor = MonotonicCursorRepo().apply {
            readHook = { _ ->
                readCallCount++
                if (readCallCount == 1) {
                    throw RuntimeException("simulated SQLCipher I/O on first read")
                }
            }
        }
        val logLines = mutableListOf<String>()
        // longPollEnabled = true, no driveStateToRestActive() →
        // only the parallel wsActivePollLoop runs (state stays
        // WsActive so the legacy pollLoop does NOT spawn).
        val orch = buildOrchestrator(
            transport,
            cursor,
            scheduler = testScheduler,
            longPollEnabled = true,
            logSink = { logLines += it },
        )
        val caps = orch.bootstrap()
        check(caps.restFallback)

        val received = mutableListOf<PollEnvelope>()
        val collectorJob = launch {
            orch.inbound.collect { env ->
                received += env
                orch.ackInboundAndAdvanceCursor(env.id)
            }
        }

        orch.start()
        runCurrent()

        var pumpIterations = 0
        while (received.isEmpty() && pumpIterations < 20) {
            advanceTimeBy(RestFallbackOrchestrator.POLL_FAIL_BACKOFF_MS + 1_000L)
            runCurrent()
            pumpIterations += 1
        }

        orch.stop()
        collectorJob.cancel()
        runCurrent()

        assertTrue(!collectorJob.isActive, "inbound collector must be torn down")
        assertTrue(readCallCount >= 2)
        assertTrue(
            logLines.any { it.contains("ws_active_poll_call_skipped") && it.contains("cursor_read_fail") },
            "expected `ws_active_poll_call_skipped reason=cursor_read_fail` log; got:\n" +
                logLines.joinToString("\n"),
        )
        assertEquals(1, received.size)
        assertEquals(13L, received.single().seq)
    }
}
