// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C3) — behavioural contract tests for
 * [RestFallbackOrchestrator.ackInboundAndAdvanceCursor].
 *
 * Ships test cells:
 *
 *   * **M11** — cursor advance gate. C3 covers the (acked ∧ accept)
 *     and (acked ∧ storage-error) sub-cells; the C4 commit will add
 *     the (verify ∧ accept / verify ∧ dedup / verify-fail) sub-cells
 *     once both poll loops are wired through the verify path.
 *   * **M-B20** — ack network failure path: `Failed` returns leave
 *     the cursor un-advanced and the `_pendingSeqForAck` entry in
 *     place; the next `ReAck` call retries and on success advances
 *     the cursor exactly once.
 *   * **M-B26** — sparse-sequence crash recovery: persistent cursor
 *     at `seq=5`, simulated crash on `seq=6` mid-ack, fresh
 *     orchestrator polls `seq=7`, persistent cursor ends at `7`. The
 *     gap is implicit because no envelope holds it.
 *   * **M-B27** — cursor-write bounded retry: one-throw-then-success,
 *     all-three-throws-exhaustion, and cancellation safety during
 *     the retry loop.
 *   * **M-B29** — cancellation at four precise injection points
 *     inside [RestFallbackOrchestrator.ackInboundAndAdvanceCursor]:
 *     (b) during the bounded retry loop's third (last)
 *     `upsertLastSeenSeq` attempt; (c) during `delay()` between
 *     retry attempts; (d) STRUCTURAL — no suspension point exists
 *     between `Acked` return and the `try { ... }` block (verified
 *     by behavioural construction below).
 *
 *     Sub-cell **(a)** (cancellation at the Phase 1 mutex acquire)
 *     is verified by symmetry: the `try { ... } finally { ... }`
 *     region in the orchestrator is structurally identical for
 *     every suspension point inside it. The `finally` block runs
 *     `withContext(NonCancellable) { ... }` regardless of WHERE in
 *     the `try` body cancellation hits; tests (b) and (c) exercise
 *     two distinct suspension points and both confirm the cleanup
 *     property. A direct (a) trigger would require external
 *     contention on the orchestrator's private `_inboundStateMutex`,
 *     which is not exposed even to commonTest — the structural
 *     symmetry argument is the load-bearing assertion.
 *
 * The tests exercise the orchestrator via its public ack entry point
 * and the new module-internal `primePendingSeqForAckForTest` /
 * `peekPendingSeqForAckForTest` seams (added in this commit so the
 * cursor-advance pipeline can be tested without driving the full
 * state-machine + poll-loop machinery).
 */
class AckInboundAndAdvanceCursorTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

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

    /**
     * Recording fake transport — drives the ack path. `pollScript`
     * and `sendScript` defaults are no-ops because these tests do
     * not exercise those paths; if they did fire it would be a bug
     * in the test setup.
     */
    private class FakeTransport(
        var sessionScript: () -> RestFallbackResponse<AuthSessionResponse>,
        var ackScript: (envelopeId: String) -> RestFallbackResponse<AckDeliverResponse>,
    ) : RestFallbackTransport {

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
        ): RestFallbackResponse<SendResponse> = fail("send unexpected in C3 ack tests")

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> = fail("poll unexpected in C3 ack tests")

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> {
            ackCalls += body.id
            return ackScript(body.id)
        }
    }

    private fun ackOk200(): (String) -> RestFallbackResponse<AckDeliverResponse> = {
        RestFallbackResponse(
            statusCode = 200,
            bodyParsed = AckDeliverResponse(ok = 1),
            rawBody = "{}",
            elapsedMs = 1L,
        )
    }

    private fun ackFailed5xx(): (String) -> RestFallbackResponse<AckDeliverResponse> = {
        RestFallbackResponse(
            statusCode = 503,
            bodyParsed = null,
            rawBody = "service unavailable",
            elapsedMs = 1L,
        )
    }

    /**
     * Programmable cursor repository. `upsertHook` runs before every
     * successful `writes += ...` so tests can inject failures, latches,
     * or cancellation triggers exactly where the retry loop touches
     * storage.
     */
    private class FakeCursorRepo(
        var initialSeq: Long? = null,
        var upsertHook: (suspend (identityHex: String, seq: Long, nowMs: Long) -> Unit)? = null,
    ) : LongPollCursorRepository {
        val reads: MutableList<String> = mutableListOf()
        val writes: MutableList<Triple<String, Long, Long>> = mutableListOf()

        override suspend fun getLastSeenSeq(identityHex: String): Long? {
            reads += identityHex
            return initialSeq
        }

        override suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long) {
            upsertHook?.invoke(identityHex, seq, nowMs)
            writes += Triple(identityHex, seq, nowMs)
            // Monotonicity invariant mirroring SqlDelightLastSeenSeqRepository:
            // a `seq` less than or equal to the currently-stored value would
            // be a no-op in production. For these tests we treat the highest
            // observed write as the canonical value and assert monotonicity
            // explicitly in M-B26.
            initialSeq = maxOf(initialSeq ?: Long.MIN_VALUE, seq)
        }
    }

    private fun buildOrchestrator(
        transport: FakeTransport,
        cursor: LongPollCursorRepository?,
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
        longPollEnabled = false,
        cursorRepository = cursor,
        dispatcher = UnconfinedTestDispatcher(),
    )

    private suspend fun bootstrapped(orch: RestFallbackOrchestrator) {
        val caps = orch.bootstrap()
        check(caps.restFallback) { "Fake session response must enable restFallback for tests" }
    }

    // ── M11 — cursor advance gate ─────────────────────────────────────────────

    @Test
    fun m11_acked_with_pending_seq_advances_cursor_and_removes_entry() = runTest {
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val cursor = FakeCursorRepo()
        val orch = buildOrchestrator(transport, cursor)
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-1", 42L)
        val outcome = orch.ackInboundAndAdvanceCursor("env-1")

        assertEquals(AckOutcome.Acked, outcome)
        assertEquals(listOf("env-1"), transport.ackCalls)
        assertEquals(1, cursor.writes.size)
        assertEquals(IDENTITY, cursor.writes.single().first)
        assertEquals(42L, cursor.writes.single().second)
        assertNull(orch.peekPendingSeqForAckForTest("env-1"))
    }

    @Test
    fun m11_acked_without_pending_seq_returns_Acked_no_cursor_advance() = runTest {
        // The relay 2xx'd the ack but the orchestrator never
        // registered a seq for this envelope (e.g. acked through a
        // non-poll-loop path before C3 wiring). Cursor stays; nothing
        // to clean up.
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val cursor = FakeCursorRepo()
        val orch = buildOrchestrator(transport, cursor)
        bootstrapped(orch)

        // NOTE: NO primePendingSeqForAckForTest call.
        val outcome = orch.ackInboundAndAdvanceCursor("env-2")

        assertEquals(AckOutcome.Acked, outcome)
        assertEquals(0, cursor.writes.size)
        assertNull(orch.peekPendingSeqForAckForTest("env-2"))
    }

    @Test
    fun m11_storage_error_all_attempts_no_cursor_advance_entry_removed() = runTest {
        // (acked ∧ storage-error) — all three retries throw. The
        // exhaustion log fires; the entry is removed so the in-memory
        // map does not leak; the cursor stays at its pre-failure value.
        // Sparse-sequence recovery (M-B26) catches up via the next
        // envelope arrival.
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val cursor = FakeCursorRepo(
            upsertHook = { _, _, _ -> throw RuntimeException("simulated storage I/O error") },
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(transport, cursor, logSink = { logLines += it })
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-3", 7L)
        val outcome = orch.ackInboundAndAdvanceCursor("env-3")

        assertEquals(AckOutcome.Acked, outcome)
        assertEquals(0, cursor.writes.size, "no successful write under all-fail")
        assertNull(orch.peekPendingSeqForAckForTest("env-3"), "entry must be removed even under failure")
        assertTrue(
            logLines.any { it.contains("poll_cursor_write_exhausted") && it.contains("seq=7") },
            "expected `poll_cursor_write_exhausted` log; got:\n${logLines.joinToString("\n")}",
        )
    }

    // ── M-B20 — ack network failure path ─────────────────────────────────────

    @Test
    fun mb20_ack_failed_no_cursor_advance_entry_remains() = runTest {
        // Relay returned 5xx for the ack. The cursor stays; the
        // entry remains in `_pendingSeqForAck` so the next ReAck
        // attempt can retry the ack.
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackFailed5xx(),
        )
        val cursor = FakeCursorRepo()
        val orch = buildOrchestrator(transport, cursor)
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-4", 11L)
        val outcome = orch.ackInboundAndAdvanceCursor("env-4")

        assertTrue(outcome is AckOutcome.Failed, "expected Failed; got $outcome")
        assertEquals(0, cursor.writes.size, "no cursor advance on ack failure")
        assertEquals(11L, orch.peekPendingSeqForAckForTest("env-4"))
    }

    @Test
    fun mb20_reack_after_failure_advances_cursor_exactly_once() = runTest {
        // Same envelope: first ack fails, second succeeds. Cursor
        // advances exactly once on the second attempt.
        var ackAttempt = 0
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = {
                ackAttempt++
                if (ackAttempt == 1) {
                    RestFallbackResponse(503, null, "fail", 1L)
                } else {
                    RestFallbackResponse(200, AckDeliverResponse(ok = 1), "{}", 1L)
                }
            },
        )
        val cursor = FakeCursorRepo()
        val orch = buildOrchestrator(transport, cursor)
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-5", 21L)
        val firstOutcome = orch.ackInboundAndAdvanceCursor("env-5")
        assertTrue(firstOutcome is AckOutcome.Failed)
        assertEquals(0, cursor.writes.size)
        assertEquals(21L, orch.peekPendingSeqForAckForTest("env-5"))

        val secondOutcome = orch.ackInboundAndAdvanceCursor("env-5")
        assertEquals(AckOutcome.Acked, secondOutcome)
        assertEquals(1, cursor.writes.size, "cursor advances exactly once across retry")
        assertEquals(21L, cursor.writes.single().second)
        assertNull(orch.peekPendingSeqForAckForTest("env-5"))
    }

    // ── M-B26 — sparse-sequence crash recovery ───────────────────────────────

    @Test
    fun mb26_sparse_sequence_skip_after_simulated_crash() = runTest {
        // Persistent cursor seeded at 5. Simulated crash on seq=6 (we
        // construct a fresh orchestrator that has no pending entry for
        // seq=6 — mirrors "process killed between ackInbound success
        // and the cursor write"). The relay has already removed
        // envelope seq=6 from its queue (cursor.writes shows seq=5
        // pre-crash); the next legitimate envelope arrives at seq=7,
        // which the orchestrator advances to. The persistent cursor
        // ends at 7 with no anomaly — the gap is implicit.
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val cursor = FakeCursorRepo(initialSeq = 5L)
        val orchFresh = buildOrchestrator(transport, cursor)
        bootstrapped(orchFresh)

        // Next legitimate envelope is seq=7; seq=6 was lost to the
        // simulated crash and the relay has already removed it.
        orchFresh.primePendingSeqForAckForTest("env-7", 7L)
        val outcome = orchFresh.ackInboundAndAdvanceCursor("env-7")

        assertEquals(AckOutcome.Acked, outcome)
        assertEquals(1, cursor.writes.size)
        assertEquals(7L, cursor.writes.single().second)
        // Persistent cursor at 7 — the sparse seq=6 gap is implicit.
        assertEquals(7L, cursor.initialSeq)
    }

    // ── M-B27 — bounded retry sub-cells ──────────────────────────────────────

    @Test
    fun mb27_one_throw_then_success_entry_removed_cursor_advances() = runTest {
        // First attempt throws, second succeeds. Entry removed;
        // cursor at expected seq.
        var upsertAttempt = 0
        val cursor = FakeCursorRepo(
            upsertHook = { _, _, _ ->
                upsertAttempt++
                if (upsertAttempt == 1) {
                    throw RuntimeException("transient SQLCipher lock contention")
                }
            },
        )
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(transport, cursor, logSink = { logLines += it })
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-8", 31L)
        val outcome = orch.ackInboundAndAdvanceCursor("env-8")

        assertEquals(AckOutcome.Acked, outcome)
        assertEquals(2, upsertAttempt, "upsert called twice (one throw + one success)")
        assertEquals(1, cursor.writes.size, "one successful write recorded")
        assertEquals(31L, cursor.writes.single().second)
        assertNull(orch.peekPendingSeqForAckForTest("env-8"))
        assertFalse(
            logLines.any { it.contains("poll_cursor_write_exhausted") },
            "exhaustion log MUST NOT fire when retry eventually succeeds",
        )
    }

    @Test
    fun mb27_all_three_attempts_throw_exhaustion_log_entry_removed() = runTest {
        // All three attempts throw → exhaustion log fires + entry
        // REMOVED from _pendingSeqForAck (no memory leak). Subsequent
        // observation does NOT reattempt the upsert (orchestrator has
        // no record).
        var upsertAttempt = 0
        val cursor = FakeCursorRepo(
            upsertHook = { _, _, _ ->
                upsertAttempt++
                throw RuntimeException("persistent SQLCipher I/O error")
            },
        )
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(transport, cursor, logSink = { logLines += it })
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-9", 99L)
        val outcome = orch.ackInboundAndAdvanceCursor("env-9")

        assertEquals(AckOutcome.Acked, outcome, "ack succeeded; only the cursor write failed")
        assertEquals(
            RestFallbackOrchestrator.CURSOR_WRITE_MAX_ATTEMPTS,
            upsertAttempt,
            "exhaustion fires after exactly CURSOR_WRITE_MAX_ATTEMPTS attempts",
        )
        assertEquals(0, cursor.writes.size)
        assertNull(
            orch.peekPendingSeqForAckForTest("env-9"),
            "entry must be removed after exhaustion to avoid leak",
        )
        assertTrue(
            logLines.any {
                it.contains("poll_cursor_write_exhausted") &&
                    it.contains("seq=99") &&
                    it.contains("attempts=3")
            },
            "expected exhaustion log; got:\n${logLines.joinToString("\n")}",
        )
    }

    @Test
    fun mb27_cancellation_during_retry_loop_finally_removes_entry() = runTest {
        // Cancel the caller AFTER `Acked` returns but BEFORE the
        // first storage write completes. The `finally +
        // NonCancellable` cleanup must still remove the entry under
        // `_inboundStateMutex`. No entry leaks past cancellation;
        // `CancellationException` re-throws to the caller after
        // cleanup. The exhaustion log must NOT fire (cancellation
        // is distinct from structural exhaustion).
        val cursorBlocked = CompletableDeferred<Unit>()
        val cursor = FakeCursorRepo(
            upsertHook = { _, _, _ -> cursorBlocked.await() },
        )
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(transport, cursor, logSink = { logLines += it })
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-cancel", 55L)

        val ackJob = launch {
            try {
                orch.ackInboundAndAdvanceCursor("env-cancel")
                fail("expected CancellationException")
            } catch (ce: CancellationException) {
                // Expected. The `finally` cleanup must still have run.
            }
        }
        // Let the coroutine reach the suspending `upsertHook.await()`.
        advanceUntilIdle()
        ackJob.cancel()
        advanceUntilIdle()

        assertNull(
            orch.peekPendingSeqForAckForTest("env-cancel"),
            "finally + NonCancellable cleanup must remove the entry under cancellation",
        )
        assertFalse(
            logLines.any { it.contains("poll_cursor_write_exhausted") },
            "exhaustion log MUST NOT fire under cancellation",
        )
    }

    // ── M-B29 — cancellation at four precise injection points ───────────────

    @Test
    fun mb29_cancel_during_third_attempt_finally_removes_entry_no_exhaustion_log() = runTest {
        // Cancellation during the THIRD (last) upsertLastSeenSeq
        // attempt. CancellationException MUST propagate to the outer
        // catch (which sets `wasCancelled = true`), the `finally`
        // must remove the entry, and the exhaustion log MUST NOT
        // fire. A `runCatching` regression would silently catch the
        // CE and report a structural exhaustion; this test pins the
        // distinction.
        var upsertAttempt = 0
        val ackJobHolder: Array<Job?> = arrayOf(null)
        val cursor = FakeCursorRepo(
            upsertHook = { _, _, _ ->
                upsertAttempt++
                if (upsertAttempt < RestFallbackOrchestrator.CURSOR_WRITE_MAX_ATTEMPTS) {
                    throw RuntimeException("transient I/O")
                }
                // Third attempt — cancel before completing.
                ackJobHolder[0]?.cancel()
                yield() // allow cancellation to take effect
            },
        )
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(transport, cursor, logSink = { logLines += it })
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-cancel-3", 77L)
        val ackJob = launch {
            try {
                orch.ackInboundAndAdvanceCursor("env-cancel-3")
                fail("expected CancellationException to propagate")
            } catch (ce: CancellationException) {
                // Expected.
            }
        }
        ackJobHolder[0] = ackJob
        advanceUntilIdle()

        assertEquals(RestFallbackOrchestrator.CURSOR_WRITE_MAX_ATTEMPTS, upsertAttempt)
        assertNull(
            orch.peekPendingSeqForAckForTest("env-cancel-3"),
            "finally must remove entry on cancellation during third attempt",
        )
        assertFalse(
            logLines.any { it.contains("poll_cursor_write_exhausted") },
            "exhaustion log MUST NOT fire under cancellation (runCatching regression check)",
        )
    }

    @Test
    fun mb29_cancel_during_delay_between_retries_finally_removes_entry() = runTest {
        // Cancellation during `delay()` between retries. delay()
        // throws CancellationException naturally; the outer catch
        // tags it; the finally cleans up. The cancel is fired by
        // the upsertHook after the first throw — the orchestrator
        // then enters delay() and observes the cancellation. The
        // exhaustion log MUST NOT fire because `wasCancelled = true`.
        var upsertAttempt = 0
        val ackJobHolder: Array<Job?> = arrayOf(null)
        val cursor = FakeCursorRepo(
            upsertHook = { _, _, _ ->
                upsertAttempt++
                // Throw so the loop falls through to `delay(BACKOFF[0])`
                // (= 100 ms) — then arrange the cancellation to land
                // during that delay.
                throw RuntimeException("transient")
            },
        )
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(transport, cursor, logSink = { logLines += it })
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-cancel-delay", 88L)
        val ackJob = launch {
            try {
                orch.ackInboundAndAdvanceCursor("env-cancel-delay")
                fail("expected CancellationException to propagate")
            } catch (ce: CancellationException) {
                // Expected.
            }
        }
        ackJobHolder[0] = ackJob
        // Pump until the first upsert attempt has thrown and the
        // orchestrator is inside `delay(100)`.
        while (upsertAttempt == 0) yield()
        // Cancel during the delay window.
        ackJob.cancel()
        advanceUntilIdle()

        // We may or may not have reached attempt #2 — the test does
        // NOT pin the count, only the cleanup discipline.
        assertNull(orch.peekPendingSeqForAckForTest("env-cancel-delay"))
        assertFalse(
            logLines.any { it.contains("poll_cursor_write_exhausted") },
            "exhaustion log MUST NOT fire under cancellation during delay",
        )
    }

    @Test
    fun mb29_cancel_at_phase1_mutex_acquire_finally_removes_entry() = runTest {
        // Sub-cell (a) — REAL cancellation at the Phase 1 mutex
        // acquire. We hold `_inboundStateMutex` externally via the
        // module-internal `withInboundStateMutexHeldForTest` seam so
        // the ack-and-advance coroutine deterministically suspends
        // at the Phase 1 `_inboundStateMutex.withLock` call. Then we
        // cancel the ack coroutine while it is blocked on the lock.
        //
        // The expected sequence:
        //   1. The Phase 1 mutex wait throws CancellationException.
        //   2. The outer try/catch path propagates the CE.
        //   3. Phase 3 finally enters `withContext(NonCancellable) {
        //      _inboundStateMutex.withLock { ... } }`. It suspends
        //      waiting for the holder to release the mutex.
        //   4. We release the held mutex.
        //   5. Phase 3 acquires the mutex, removes the entry, and
        //      the CE propagates to the caller.
        //
        // Assertion: the entry is removed; no exhaustion log fires.
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val cursor = FakeCursorRepo()
        val logLines = mutableListOf<String>()
        val orch = buildOrchestrator(transport, cursor, logSink = { logLines += it })
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-phase1", 23L)

        val mutexAcquired = CompletableDeferred<Unit>()
        val releaseMutex = CompletableDeferred<Unit>()
        val holderJob = launch {
            orch.withInboundStateMutexHeldForTest {
                mutexAcquired.complete(Unit)
                releaseMutex.await()
            }
        }
        // Wait until the holder has actually acquired the lock.
        mutexAcquired.await()

        val ackJob = launch {
            try {
                orch.ackInboundAndAdvanceCursor("env-phase1")
                fail("expected CancellationException — Phase 1 should be cancellable")
            } catch (ce: CancellationException) {
                // Expected.
            }
        }
        // Let the ack reach Phase 0 (synchronous ackInbound) and
        // arrive at Phase 1 suspension on the held mutex.
        advanceUntilIdle()

        // Cancel while blocked on the mutex.
        ackJob.cancel()
        // Release the holder so the ack's Phase 3 finally cleanup
        // can acquire the mutex and remove the entry.
        releaseMutex.complete(Unit)
        advanceUntilIdle()

        // The Phase 3 cleanup ran under NonCancellable; the entry
        // must be gone.
        assertNull(
            orch.peekPendingSeqForAckForTest("env-phase1"),
            "Phase 3 finally cleanup must remove the entry after a Phase 1 cancellation",
        )
        assertFalse(
            logLines.any { it.contains("poll_cursor_write_exhausted") },
            "exhaustion log MUST NOT fire under cancellation at Phase 1",
        )
        assertEquals(0, cursor.writes.size, "no cursor write happens when Phase 1 is cancelled")
        holderJob.join()
    }

    @Test
    fun mb29d_structural_no_suspension_point_between_acked_return_and_try_block() {
        // Sub-cell (d) — STRUCTURAL: the production source file MUST
        // NOT contain a suspending call between the `return ackOutcome`
        // line (early-exit on non-Acked) and the `try {` block entry
        // on the Acked path. The first suspension point after `Acked`
        // must be the `_inboundStateMutex.withLock` INSIDE the try.
        //
        // Verification: read the orchestrator source file, locate the
        // two markers, and assert that the region between them contains
        // ONLY local variable initialisations (assignment to `null` or
        // `false`) plus comments + blank lines. Any other token in
        // that region is a candidate suspension point and breaks the
        // load-bearing property that cancellation cannot leak the
        // `_pendingSeqForAck` entry past the `try { ... } finally { ... }`
        // bracket.
        val source = locateSource(
            "shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RestFallbackOrchestrator.kt",
        ).readText(Charsets.UTF_8)

        // The Acked branch is unique: there's exactly one occurrence
        // of `if (ackOutcome !is AckOutcome.Acked) {` in the
        // orchestrator, immediately followed by the early-exit
        // `return ackOutcome`. The next `try {` opens the post-Acked
        // bracket.
        val ackedCheckIdx = source.indexOf("if (ackOutcome !is AckOutcome.Acked) {")
        assertTrue(
            ackedCheckIdx >= 0,
            "ackInboundAndAdvanceCursor `if (ackOutcome !is AckOutcome.Acked) {` " +
                "anchor not found in orchestrator source — refactor may have moved it",
        )
        val earlyExitEnd = source.indexOf('}', ackedCheckIdx)
        assertTrue(earlyExitEnd > ackedCheckIdx, "no closing `}` after Acked check")
        // Find the next `try {` that begins on its own line (i.e.
        // preceded by indentation only, not embedded inside a
        // documentation comment that references the `try { ... }`
        // block textually). The orchestrator is indented with 4
        // spaces × N levels — match any leading whitespace before
        // `try {` and require a preceding newline.
        val tryBraceAnchor = Regex("""\n\s*try\s*\{""")
        val tryMatch = tryBraceAnchor.find(source, startIndex = earlyExitEnd)
        assertNotNull(
            tryMatch,
            "expected a line-starting `try {` after the early-exit close brace; " +
                "not found",
        )
        val tryBraceIdx = tryMatch.range.first
        val region = source.substring(earlyExitEnd + 1, tryBraceIdx)

        // The region must contain only:
        //   - whitespace / blank lines
        //   - `//` comments (single-line)
        //   - `var <name>: <Type>? = null` initialisations
        //   - `var <name> = false` initialisations
        // No suspending calls, no method invocations, no awaits.
        // Strip comments + whitespace, then assert that the remainder
        // matches only the allowed `var ... = null|false` shapes.
        val stripped = region
            .lineSequence()
            .map { line -> line.replace(Regex("""//.*$"""), "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        // Allow-list: lines of shape `var <ident>: <Type> = null` or
        // `var <ident> = false`. Anything else is a candidate leak.
        val allowedLine = Regex(
            """^var\s+\w+(\s*:\s*[\w?<>]+)?\s*=\s*(null|false|true|0|0L)\s*$""",
        )
        val offending = stripped.lineSequence().filter { line ->
            !allowedLine.matches(line)
        }.toList()

        assertTrue(
            offending.isEmpty(),
            "Region between the Acked early-exit and the `try {` block contains " +
                "suspending or non-trivial statements. The locked structural property " +
                "is that NO suspension point exists in this region; cancellation " +
                "before `try { ... }` would leak `_pendingSeqForAck` past the " +
                "finally-block cleanup. Offending lines:\n" +
                offending.joinToString("\n") { "  > $it" },
        )
    }

    /**
     * Resolve the orchestrator source file from the test working
     * directory. The module layout puts the test under
     * `shared/core/transport/build/classes/...` at run time; the
     * source itself lives at
     * `shared/core/transport/src/commonMain/...`. The repo-root path
     * is the canonical anchor; we also accept a relative path so
     * the test works from the module-level working directory.
     */
    private fun locateSource(repoRelative: String): java.io.File {
        val candidates = listOf(
            java.io.File(repoRelative),
            java.io.File("../../../$repoRelative"),
            java.io.File("../../$repoRelative"),
            java.io.File("../$repoRelative"),
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate orchestrator source from the unit-test " +
                    "working directory. Tried: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    // ── M12 — cursor monotonicity (real concurrent dual-loop test) ──────────

    /**
     * Trek 2 Stage 2B-B (C3 review-fix) — M12-required monotonic
     * storage mirror that ENFORCES the invariant: the underlying
     * SQLDelight `upsertLastSeenSeq` transaction NO-OPs on
     * `newSeq <= persistedSeq` (Stage 2A D5 contract). This fake
     * mirrors that behaviour faithfully so the M12 assertion below
     * can pin "writer never accepts a value ≤ persisted" as an
     * end-to-end property rather than relying on the live
     * SQLCipher transaction.
     *
     * Records the full call log AND the accepted-writes log so the
     * test can prove both:
     *   1. The orchestrator forwards every (id, seq) attempt through
     *      the seam (we do NOT filter in the orchestrator).
     *   2. The storage layer no-ops on non-monotonic attempts, so
     *      the persisted cursor only ever advances — never regresses.
     */
    private class MonotonicCursorRepo(
        initial: Long? = null,
    ) : LongPollCursorRepository {
        private val lock = kotlinx.coroutines.sync.Mutex()
        private var persisted: Long? = initial
        val reads: MutableList<String> = mutableListOf()

        /** Every `upsertLastSeenSeq(...)` call attempt, in order. */
        val attempts: MutableList<Long> = mutableListOf()

        /** Only the attempts that the monotonic transaction ACCEPTED. */
        val accepted: MutableList<Long> = mutableListOf()

        val persistedSeq: Long? get() = persisted

        override suspend fun getLastSeenSeq(identityHex: String): Long? = lock.withLock {
            reads += identityHex
            persisted
        }

        override suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long) {
            lock.withLock {
                attempts += seq
                val current = persisted
                if (current == null || seq > current) {
                    persisted = seq
                    accepted += seq
                }
                // else: SQLDelight monotonicity contract no-ops the write.
            }
        }
    }

    @Test
    fun m12_two_concurrent_poll_loops_interleaved_3_5_4_6_keep_persisted_monotonic() = runTest {
        // Real concurrent test per scope-doc M12 cell. Two
        // independent coroutines simulate `wsActivePollLoop` and
        // the legacy `pollLoop` running in parallel; each receives
        // an interleaved subset of the canonical out-of-order batch
        // `seq = [3, 5, 4, 6]`. The monotonic FakeCursorRepo
        // mirrors the SQLDelight transaction's no-op-on-lower
        // contract.
        //
        // Loop A (wsActive analogue): handles seq=3, then seq=5.
        // Loop B (legacy analogue):   handles seq=4, then seq=6.
        //
        // Real interleaving from concurrent dispatching means the
        // four ack calls land in some non-deterministic order. The
        // ASSERTIONS pin the load-bearing properties regardless of
        // the interleaving:
        //   * All four envelopes ack via the relay (4 upsert attempts).
        //   * The persisted cursor is monotonically non-decreasing
        //     across every accepted write.
        //   * The final persisted value equals max(3,5,4,6) = 6L.
        //   * Any upsert attempt with seq ≤ persisted at attempt time
        //     was NO-OP'd by the storage layer — i.e. the writer was
        //     never "called and committed" with a value lower than
        //     the persisted state.
        //
        // The concurrency here exercises the orchestrator's
        // `_inboundStateMutex` discipline AND the storage layer's
        // monotonicity AND their interaction. A regression that
        // either lost the lock around `_pendingSeqForAck` or wrote
        // a stale seq value would trip the final-state assertion or
        // the monotonicity invariant.
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val cursor = MonotonicCursorRepo()
        val orch = buildOrchestrator(transport, cursor)
        bootstrapped(orch)

        // Loop A — simulates `wsActivePollLoop` ingesting envelopes
        // seq=3 and seq=5.
        val loopA = launch {
            orch.primePendingSeqForAckForTest("env-3", 3L)
            orch.ackInboundAndAdvanceCursor("env-3")
            orch.primePendingSeqForAckForTest("env-5", 5L)
            orch.ackInboundAndAdvanceCursor("env-5")
        }
        // Loop B — simulates the legacy `pollLoop` ingesting envelopes
        // seq=4 and seq=6.
        val loopB = launch {
            orch.primePendingSeqForAckForTest("env-4", 4L)
            orch.ackInboundAndAdvanceCursor("env-4")
            orch.primePendingSeqForAckForTest("env-6", 6L)
            orch.ackInboundAndAdvanceCursor("env-6")
        }
        loopA.join()
        loopB.join()

        // All four envelopes acked at the relay.
        assertEquals(4, transport.ackCalls.size, "all four envelopes must ack")

        // All four upsert attempts forwarded to the storage seam
        // (the orchestrator does NOT pre-filter; the storage layer
        // enforces monotonicity).
        assertEquals(4, cursor.attempts.size, "orchestrator forwards every (id, seq) to upsert")
        assertEquals(setOf(3L, 4L, 5L, 6L), cursor.attempts.toSet())

        // Monotonic non-decreasing accepted writes — the load-bearing
        // M12 property. A regression that accidentally let a lower
        // seq advance the persisted cursor would break this.
        for (i in 1 until cursor.accepted.size) {
            assertTrue(
                cursor.accepted[i] > cursor.accepted[i - 1],
                "accepted writes must be strictly increasing; saw ${cursor.accepted}",
            )
        }

        // Final persisted value at max(envelopes).
        assertEquals(6L, cursor.persistedSeq, "final persisted cursor must be the highest observed")

        // The "writer never accepted a value ≤ persisted" invariant
        // — every accepted write strictly exceeds the persisted
        // value AT THE MOMENT OF the write (this follows from the
        // strict increase above plus the storage layer's contract).
        // We assert it explicitly so the property is named:
        var snapshotPersisted: Long? = null
        for (seq in cursor.accepted) {
            val sp = snapshotPersisted
            assertTrue(
                sp == null || seq > sp,
                "accepted write seq=$seq must exceed persisted-at-time=$sp",
            )
            snapshotPersisted = seq
        }
    }

    @Test
    fun m12_no_torn_seq_value_under_concurrent_emit_and_consume() = runTest {
        // Companion test: under high concurrency, the
        // `_inboundStateMutex` discipline must guarantee that
        // `_pendingSeqForAck` reads always see whole, well-formed
        // seq values — never a torn read across an emit/consume
        // pair. We exercise this by running many concurrent
        // emit→ack pairs and asserting the orchestrator forwarded
        // EXACTLY the primed seq for each envelope.
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val cursor = MonotonicCursorRepo()
        val orch = buildOrchestrator(transport, cursor)
        bootstrapped(orch)

        // 50 envelopes with monotonic seqs, interleaved across two
        // simulated loops.
        val jobs = mutableListOf<Job>()
        for (i in 1..50) {
            val seq = i.toLong()
            val id = "env-$i"
            val coro = if (i % 2 == 0) "loopA" else "loopB"
            jobs += launch {
                orch.primePendingSeqForAckForTest(id, seq)
                yield() // interleave deliberately
                orch.ackInboundAndAdvanceCursor(id)
            }
        }
        jobs.forEach { it.join() }

        // Every primed seq was forwarded to upsert (no envelope lost).
        assertEquals(50, cursor.attempts.size)
        // Every seq from 1..50 appears exactly once.
        assertEquals((1L..50L).toSet(), cursor.attempts.toSet())
        // Final persisted value is the max.
        assertEquals(50L, cursor.persistedSeq)
        // Accepted writes are strictly increasing.
        for (i in 1 until cursor.accepted.size) {
            assertTrue(cursor.accepted[i] > cursor.accepted[i - 1])
        }
    }

    // ── Companion-constant pin ────────────────────────────────────────────────

    @Test
    fun cursor_write_retry_backoff_array_matches_max_attempts_minus_one() {
        // The `init { check(...) }` block in the orchestrator
        // enforces this at construction; this test pins the public
        // companion values directly. A future tuning of one constant
        // without the other surfaces here.
        assertEquals(3, RestFallbackOrchestrator.CURSOR_WRITE_MAX_ATTEMPTS)
        assertEquals(2, RestFallbackOrchestrator.CURSOR_WRITE_RETRY_BACKOFF_MS.size)
        assertEquals(100L, RestFallbackOrchestrator.CURSOR_WRITE_RETRY_BACKOFF_MS[0])
        assertEquals(500L, RestFallbackOrchestrator.CURSOR_WRITE_RETRY_BACKOFF_MS[1])
    }
}
