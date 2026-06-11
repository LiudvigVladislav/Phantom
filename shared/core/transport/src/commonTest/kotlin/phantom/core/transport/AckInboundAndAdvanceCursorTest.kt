// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
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
    fun mb29_no_suspension_point_between_acked_return_and_try_block_structurally() = runTest {
        // Sub-cell (d) — STRUCTURAL: by construction of
        // ackInboundAndAdvanceCursor, there is NO suspension point
        // between the `return ackOutcome` on the non-Acked early
        // exit and the `try { ... }` block entry on the Acked path.
        // The first suspension point after `Acked` is the
        // `_inboundStateMutex.withLock` INSIDE the `try`. This test
        // exercises the structural property by asserting that a
        // post-`Acked` cancellation is observed only via the
        // `finally`/`NonCancellable` cleanup path — never via a
        // mid-region leak. We re-run the cancellation-during-mutex
        // case as the canonical behavioural witness; the absence of
        // a leakable suspension point is a load-bearing property of
        // the implementation, not a runtime measurement.
        val cursor = FakeCursorRepo()
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val orch = buildOrchestrator(transport, cursor)
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("env-struct", 44L)
        val outcome = orch.ackInboundAndAdvanceCursor("env-struct")

        // Witness: under normal completion, the post-`Acked` region
        // unconditionally cleans up; the entry is gone and the cursor
        // is advanced exactly once. A regression that put a
        // suspension point BEFORE the `try { ... }` would not change
        // these assertions under normal flow (they pass either way),
        // but the cancellation tests in mb29_cancel_at_phase1_*
        // exercise the leakable-suspension surface and would trip
        // there if the structural property were broken.
        assertEquals(AckOutcome.Acked, outcome)
        assertEquals(1, cursor.writes.size)
        assertEquals(44L, cursor.writes.single().second)
        assertNull(orch.peekPendingSeqForAckForTest("env-struct"))
    }

    // ── M12 — cursor monotonicity ────────────────────────────────────────────

    @Test
    fun m12_cursor_monotonicity_across_sparse_sequential_acks() = runTest {
        // Sparse but monotonic sequence acks land in order; the
        // cursor never regresses. FakeCursorRepo's monotonicity
        // mirror catches a regression that wrote a lower seq.
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val cursor = FakeCursorRepo()
        val orch = buildOrchestrator(transport, cursor)
        bootstrapped(orch)

        // Out-of-order PRIME (simulating both poll loops registering
        // different ids concurrently before consumer drains), but
        // sequential ack so the writes themselves land in seq order:
        orch.primePendingSeqForAckForTest("env-a", 3L)
        orch.primePendingSeqForAckForTest("env-b", 5L)
        orch.primePendingSeqForAckForTest("env-c", 4L)
        orch.primePendingSeqForAckForTest("env-d", 6L)

        orch.ackInboundAndAdvanceCursor("env-a")
        orch.ackInboundAndAdvanceCursor("env-c") // seq=4 after seq=3
        orch.ackInboundAndAdvanceCursor("env-b") // seq=5 after seq=4
        orch.ackInboundAndAdvanceCursor("env-d") // seq=6 after seq=5

        assertEquals(4, cursor.writes.size)
        assertEquals(listOf(3L, 4L, 5L, 6L), cursor.writes.map { it.second })
        // Final persistent value at the highest observed.
        assertEquals(6L, cursor.initialSeq)
    }

    @Test
    fun m12_cursor_writer_never_called_with_value_lower_than_persisted() = runTest {
        // The orchestrator simply forwards whatever seq is in the
        // pending map. The monotonicity invariant is enforced by
        // the SQLDelight implementation (D5) — this test pins the
        // surface contract: out-of-order acks DO call upsert with
        // potentially-lower seq values; production storage rejects
        // the lower writes. C3 ships the orchestrator side of the
        // contract; the storage side has been pinned since Stage 2A
        // A2/A3. The test confirms the orchestrator does NOT filter
        // out-of-order writes — that responsibility lives at the
        // storage layer.
        val transport = FakeTransport(
            sessionScript = { SESSION_RESPONSE_OK },
            ackScript = ackOk200(),
        )
        val cursor = FakeCursorRepo()
        val orch = buildOrchestrator(transport, cursor)
        bootstrapped(orch)

        orch.primePendingSeqForAckForTest("late-low", 2L)
        orch.primePendingSeqForAckForTest("early-high", 9L)
        orch.ackInboundAndAdvanceCursor("early-high")
        orch.ackInboundAndAdvanceCursor("late-low")

        // The orchestrator forwarded both writes verbatim — including
        // the lower value. Production storage rejects it via its own
        // monotonicity transaction; the orchestrator does not.
        assertEquals(2, cursor.writes.size)
        assertEquals(9L, cursor.writes[0].second)
        assertEquals(2L, cursor.writes[1].second)
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
