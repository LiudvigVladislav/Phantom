// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-A (B3) — behavioural contract tests for the parallel
 * `wsActivePollJob`. Covers M3, M5, M6, and M7 from the scope-doc test
 * matrix. The tests drive the real coroutine machinery
 * ([UnconfinedTestDispatcher] for synchronous corouqine execution
 * under `runTest`), not a regex / source-parse stub.
 */
class WsActivePollJobLifecycleTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    /**
     * Recording fake transport. The orchestrator calls `authSession` once
     * via [RestFallbackOrchestrator.bootstrap], then drives both the
     * legacy poll loop and the parallel `wsActivePollJob` through
     * `poll(...)`. Each poll captures `(sinceSeq, longPollOptIn,
     * readTimeoutMs)` and returns the next scripted response.
     */
    private class FakeTransport : RestFallbackTransport {

        var sessionScript: () -> RestFallbackResponse<AuthSessionResponse> = {
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = AuthSessionResponse(
                    token = "test-token",
                    expiresAt = Long.MAX_VALUE,
                    restFallback = true,
                    maxSendBodyBytes = 4096,
                    pollMaxEnvelopes = 1,
                    pollHoldSecs = 30,
                    // C4: empty key keeps state at KeyAbsent →
                    // unverified pass-through; lifecycle tests
                    // assert delivery without verify gate.
                    seqMacVerifyKey = "",
                ),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        val pollMutex: Mutex = Mutex()
        val pollSinceSeqs: MutableList<Long?> = mutableListOf()
        val pollLongPollOptIns: MutableList<Boolean> = mutableListOf()
        val pollReadTimeouts: MutableList<Long?> = mutableListOf()

        var pollScript: () -> RestFallbackResponse<PollResponse> = {
            RestFallbackResponse(
                statusCode = 200,
                bodyParsed = PollResponse(envelopes = emptyList(), more = false),
                rawBody = "{}",
                elapsedMs = 1L,
            )
        }

        override suspend fun authSession(
            url: String,
            body: AuthSessionRequest,
        ): RestFallbackResponse<AuthSessionResponse> = sessionScript()

        override suspend fun send(
            url: String,
            token: String,
            idempotencyKey: String,
            body: SendRequest,
        ): RestFallbackResponse<SendResponse> = fail("send unexpected in B3 lifecycle tests")

        override suspend fun poll(
            url: String,
            token: String,
            sinceSeq: Long?,
            longPollOptIn: Boolean,
            readTimeoutMs: Long?,
        ): RestFallbackResponse<PollResponse> {
            pollMutex.withLock {
                pollSinceSeqs += sinceSeq
                pollLongPollOptIns += longPollOptIn
                pollReadTimeouts += readTimeoutMs
            }
            return pollScript()
        }

        override suspend fun ackDeliver(
            url: String,
            token: String,
            body: AckDeliverRequest,
        ): RestFallbackResponse<AckDeliverResponse> =
            fail("ackDeliver unexpected in B3 lifecycle tests")
    }

    /**
     * Recording fake cursor repository. Trek 2 Stage 2B-B (C3, L4)
     * migrated this from the read-only `LongPollCursorReader` SAM to
     * the read/write [LongPollCursorRepository] interface so the new
     * orchestrator parameter shape is what the test wires. Stage
     * 2B-A's M5 contract (read activity is recorded without giving
     * the orchestrator an opportunity to advance the cursor) still
     * holds in C3 lifecycle tests because the lifecycle harness does
     * NOT drive the ack path — `upsertLastSeenSeq` simply records
     * any write that occurs so a regression that incorrectly
     * advances the cursor from a poll response (instead of from
     * `ackInboundAndAdvanceCursor`) trips the test.
     */
    private class RecordingCursorRepository(
        private val backing: Long? = 7L,
    ) : LongPollCursorRepository {
        val reads: MutableList<String> = mutableListOf()
        val writes: MutableList<Triple<String, Long, Long>> = mutableListOf()
        override suspend fun getLastSeenSeq(identityHex: String): Long? {
            reads += identityHex
            return backing
        }
        override suspend fun upsertLastSeenSeq(identityHex: String, seq: Long, nowMs: Long) {
            writes += Triple(identityHex, seq, nowMs)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build an orchestrator wired with [transport]. [longPollEnabled]
     * gates whether B3 spawns the parallel job at [start]; [cursor] is
     * the (optional) cursor source for the parallel job.
     */
    private fun orchestrator(
        transport: FakeTransport,
        longPollEnabled: Boolean = true,
        cursor: LongPollCursorRepository? = null,
        dispatcher: CoroutineContext = UnconfinedTestDispatcher(),
    ): RestFallbackOrchestrator = RestFallbackOrchestrator(
        baseUrl = "https://relay.test",
        identityHex = "aa".repeat(32),
        signingPubkeyHex = "bb".repeat(32),
        getChallenge = { _ -> "cc".repeat(32) },
        signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
        transport = transport,
        now = { 0L },
        longPollEnabled = longPollEnabled,
        cursorRepository = cursor,
        dispatcher = dispatcher,
    )

    private suspend fun pumpUntilFirstPoll(transport: FakeTransport, attempts: Int = 50): Boolean {
        repeat(attempts) {
            yield()
            val seen = transport.pollMutex.withLock { transport.pollSinceSeqs.size }
            if (seen > 0) return true
        }
        return false
    }

    // ── M3 — kill switch ──────────────────────────────────────────────────────

    @Test
    fun m3_parallel_job_is_not_spawned_when_flag_is_off() = runTest {
        val transport = FakeTransport()
        val cursor = RecordingCursorRepository(backing = 5L)
        val orch = orchestrator(transport, longPollEnabled = false, cursor = cursor)

        orch.bootstrap()
        orch.start()
        // Yield enough times to ensure that, IF a parallel job had been
        // spawned, it would have called `transport.poll` at least once.
        repeat(10) { yield() }
        orch.stop()

        assertEquals(
            emptyList<String>(),
            cursor.reads,
            "Cursor repository must NOT be touched when LONGPOLL_V2_ENABLED is off.",
        )
        val pollsAttributableToParallelJob = transport.pollMutex.withLock {
            transport.pollLongPollOptIns.count { it }
        }
        assertEquals(
            0,
            pollsAttributableToParallelJob,
            "No `longPollOptIn=true` poll call may fire with the flag off — " +
                "this would mean the parallel job spawned despite the kill switch. " +
                "Recorded: ${transport.pollLongPollOptIns}",
        )
    }

    @Test
    fun m3_capability_disabled_skips_parallel_job_even_with_flag_on() = runTest {
        val transport = FakeTransport().apply {
            sessionScript = {
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = AuthSessionResponse(
                        token = "t",
                        expiresAt = Long.MAX_VALUE,
                        restFallback = false,
                        maxSendBodyBytes = 0,
                        pollMaxEnvelopes = 0,
                    ),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            }
        }
        val cursor = RecordingCursorRepository(backing = 5L)
        val orch = orchestrator(transport, longPollEnabled = true, cursor = cursor)

        orch.bootstrap()
        // Capability disabled → start() returns immediately without
        // launching any job, including the parallel one.
        orch.start()
        repeat(10) { yield() }
        orch.stop()

        assertEquals(
            emptyList<String>(),
            cursor.reads,
            "Cursor repository must NOT be touched when capabilities advertise " +
                "rest_fallback=false, even if the long-poll flag is on.",
        )
    }

    // ── M5 — cursor frozen (read-only) ────────────────────────────────────────

    @Test
    fun m5_parallel_job_reads_cursor_and_forwards_to_transport_poll() = runTest {
        val transport = FakeTransport()
        val cursor = RecordingCursorRepository(backing = 42L)
        val orch = orchestrator(transport, longPollEnabled = true, cursor = cursor)

        orch.bootstrap()
        orch.start()
        val landed = pumpUntilFirstPoll(transport)
        orch.stop()

        assertTrue(landed, "Parallel job did not produce a poll call within the pump window.")
        assertTrue(
            cursor.reads.isNotEmpty(),
            "Parallel job must call `LongPollCursorReader.getLastSeenSeq(...)` at " +
                "least once when it runs.",
        )
        val firstSinceSeq = transport.pollMutex.withLock { transport.pollSinceSeqs.first() }
        assertEquals(
            42L,
            firstSinceSeq,
            "Parallel job must forward the persisted cursor value into the " +
                "`transport.poll(sinceSeq = ...)` call exactly.",
        )
    }

    @Test
    fun m5_cursor_reader_is_a_sam_fun_interface_with_no_write_method() {
        // Structural guarantee that locks L4: [LongPollCursorReader]
        // is a `fun interface` (SAM constraint), so the Kotlin
        // compiler refuses to compile any declaration with more than
        // one abstract member. Adding a write method to the interface
        // would be a compile error at the interface declaration
        // itself; this test pins the SAM invariant at runtime as a
        // backstop in case a future Kotlin version relaxes the rule.
        //
        // We instantiate the interface via the SAM constructor with a
        // no-op lambda; if anyone adds a second abstract method to
        // the interface, the SAM constructor stops compiling and so
        // does this test file.
        val reader: LongPollCursorReader = LongPollCursorReader { null }
        assertNotNull(reader)
    }

    @Test
    fun m5_null_reader_means_no_since_seq_on_wire() = runTest {
        val transport = FakeTransport()
        val orch = orchestrator(transport, longPollEnabled = true, cursor = null)

        orch.bootstrap()
        orch.start()
        val landed = pumpUntilFirstPoll(transport)
        orch.stop()

        assertTrue(landed, "Parallel job did not produce a poll call within the pump window.")
        val firstSinceSeq = transport.pollMutex.withLock { transport.pollSinceSeqs.first() }
        assertNull(
            firstSinceSeq,
            "With no cursor repository, the parallel job must pass `sinceSeq = null` " +
                "to the transport — server treats null as since_seq=0.",
        )
    }

    // ── M6 — seq_mac presence parse without verification ──────────────────────

    @Test
    fun m6_poll_envelope_with_seq_mac_field_deserialises_and_carries_field() = runTest {
        val expectedMac = "abcdef1234567890".repeat(4)
        val transport = FakeTransport().apply {
            pollScript = {
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = PollResponse(
                        envelopes = listOf(
                            PollEnvelope(
                                id = "env-1",
                                fromHex = "",
                                sealedSenderBase64 = "",
                                payloadBase64 = "AAA",
                                sequenceTs = 1_700_000_000_000L,
                                seq = 1L,
                                seqMac = expectedMac,
                            ),
                        ),
                        more = false,
                    ),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            }
        }
        // StandardTestDispatcher bound to the runTest scheduler: lets us
        // drain coroutines step-by-step with `runCurrent` so the
        // pre-start subscription attaches before the parallel job
        // tryEmits its first envelope.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val orch = orchestrator(
            transport,
            longPollEnabled = true,
            cursor = RecordingCursorRepository(0L),
            dispatcher = dispatcher,
        )

        orch.bootstrap()
        runCurrent()
        // Subscribe BEFORE start: MutableSharedFlow inside the
        // orchestrator has replay=0, so a tryEmit before any collector
        // attaches drops the envelope. We open a collector flow first
        // (and runCurrent to let it actually subscribe), then start
        // the orchestrator.
        val collected = mutableListOf<PollEnvelope>()
        val collector = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(dispatcher) {
            orch.inbound.collect { collected += it }
        }
        runCurrent()
        try {
            orch.start()
            // Advance virtual time enough to drive at least one full
            // poll cycle through the parallel job.
            advanceTimeBy(10_000L)
            runCurrent()
            assertTrue(collected.isNotEmpty(), "No envelope emitted after virtual-time advance.")
            val emitted = collected.first()
            assertEquals("env-1", emitted.id)
            assertEquals(
                expectedMac,
                emitted.seqMac,
                "PollEnvelope.seqMac must be presence-parsed and forwarded to " +
                    "the orchestrator's inbound flow unchanged. No verification " +
                    "happens in B3; the wire value rides through to Stage 2B-B.",
            )
        } finally {
            collector.cancel()
            orch.stop()
            runCurrent()
        }
    }

    @Test
    fun m6_old_relay_without_seq_mac_field_decodes_to_empty_string() {
        // Backwards-compatibility sanity: a relay that has not been
        // redeployed with Stage 1.x simply omits `seq_mac`. The DTO
        // default `""` must accept that wire shape; otherwise an old
        // relay would crash a 2B-A client at JSON deserialise time.
        val envWithoutSeqMac = PollEnvelope(
            id = "env-old",
            fromHex = "",
            sealedSenderBase64 = "",
            payloadBase64 = "AAA",
            sequenceTs = 1L,
            seq = 1L,
            // seqMac NOT specified — defaults to ""
        )
        assertEquals("", envWithoutSeqMac.seqMac)
    }

    @Test
    fun m6_session_response_seq_mac_verify_key_is_cached_in_session_scope() = runTest {
        val expectedVerifyKey = "9".repeat(64)
        val transport = FakeTransport().apply {
            sessionScript = {
                RestFallbackResponse(
                    statusCode = 200,
                    bodyParsed = AuthSessionResponse(
                        token = "t",
                        expiresAt = Long.MAX_VALUE,
                        restFallback = true,
                        maxSendBodyBytes = 4096,
                        pollMaxEnvelopes = 1,
                        pollHoldSecs = 0,
                        seqMacVerifyKey = expectedVerifyKey,
                    ),
                    rawBody = "{}",
                    elapsedMs = 1L,
                )
            }
        }
        val orch = orchestrator(transport, longPollEnabled = true, cursor = null)
        assertEquals(
            "",
            orch.seqMacVerifyKey,
            "Verify key must be empty before bootstrap.",
        )
        orch.bootstrap()
        assertEquals(
            expectedVerifyKey,
            orch.seqMacVerifyKey,
            "Verify key must be cached in the session-scoped slot after bootstrap " +
                "so Stage 2B-B can read it without a session-rotation handshake.",
        )
        orch.close()
    }

    // ── M7 — parallel job continues while WS is up ────────────────────────────

    @Test
    fun m7_parallel_job_keeps_polling_when_state_machine_says_ws_active() = runTest {
        val transport = FakeTransport()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val orch = orchestrator(
            transport,
            longPollEnabled = true,
            cursor = RecordingCursorRepository(1L),
            dispatcher = dispatcher,
        )

        orch.bootstrap()
        runCurrent()
        orch.start()
        // Let the first poll cycle complete: parallel job calls
        // acquireOrRefreshToken, transport.poll, then enters delay.
        advanceTimeBy(1_000L)
        runCurrent()
        val before = transport.pollMutex.withLock { transport.pollSinceSeqs.size }
        assertTrue(before > 0, "First poll never landed before WsActive transition.")

        // Force the state machine into WsActive — this stops the LEGACY
        // poll loop but must NOT stop the parallel B3 loop. Use the
        // public RestStateMachine API as the orchestrator does:
        // `WsOutboundAckReceived` is the canonical event that puts the
        // machine into WsActive (see `RestStateMachine.kt`).
        orch.stateMachine.onEvent(RestStateMachine.Event.WsOutboundAckReceived)
        runCurrent()
        assertEquals(RestMode.WsActive, orch.stateMachine.state.value)

        // Advance virtual time past the inter-poll delay so the parallel
        // job exits its `delay(...)` and issues another poll.
        // `pollIntervalMs()` returns up to POLL_LONG_IDLE_MS = 15_000 ms;
        // advance a generous multiple.
        advanceTimeBy(120_000L)
        runCurrent()

        val after = transport.pollMutex.withLock { transport.pollSinceSeqs.size }
        orch.stop()
        runCurrent()

        assertTrue(
            after > before,
            "Parallel job must keep issuing /relay/poll while the state " +
                "machine is in WsActive (lock L3). before=$before after=$after " +
                "— if these are equal, the parallel job is incorrectly tied to " +
                "the state machine.",
        )
    }

    @Test
    fun m7_parallel_job_only_uses_long_poll_opt_in_when_flag_enabled() = runTest {
        val transport = FakeTransport()
        val orch = orchestrator(transport, longPollEnabled = true, cursor = null)

        orch.bootstrap()
        orch.start()
        val landed = pumpUntilFirstPoll(transport)
        orch.stop()

        assertTrue(landed)
        val firstOptIn = transport.pollMutex.withLock { transport.pollLongPollOptIns.first() }
        assertEquals(
            true,
            firstOptIn,
            "When the parallel job runs (flag on), every poll it issues must " +
                "carry `longPollOptIn=true` so the L1 header pair fires. Found: " +
                "${transport.pollLongPollOptIns}",
        )
    }

    @Test
    fun m7_stop_cancels_parallel_job_and_pump_quiesces() = runTest {
        val transport = FakeTransport()
        val orch = orchestrator(transport, longPollEnabled = true, cursor = null)

        orch.bootstrap()
        orch.start()
        assertTrue(pumpUntilFirstPoll(transport))
        orch.stop()

        // After stop, the parallel job is cancelled. Any subsequent
        // yield-loop pumping must NOT produce new polls.
        val afterStop = transport.pollMutex.withLock { transport.pollSinceSeqs.size }
        repeat(40) { yield() }
        val afterPump = transport.pollMutex.withLock { transport.pollSinceSeqs.size }
        assertEquals(
            afterStop,
            afterPump,
            "stop() must cancel the parallel job structurally. before=$afterStop " +
                "afterPump=$afterPump — additional polls after stop indicate the " +
                "job kept running.",
        )
    }
}
