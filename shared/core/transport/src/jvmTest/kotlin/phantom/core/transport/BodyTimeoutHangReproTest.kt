// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * DIAGNOSTIC PROBE V4 — Option Alpha per operator direction 2026-07-03.
 *
 * NOT a regression contract test. NOT for merge into master.
 *
 * This is the class-shape verbatim duplicate of
 * `BodyTimeoutContractTest` in `commonTest`, moved to `jvmTest` and
 * un-quarantined for diagnostic purposes only. The original
 * `BodyTimeoutContractTest.kt` in `commonTest` STAYS under class-level
 * `@Ignore` untouched.
 *
 * Delta vs the original (three items only):
 *
 *   1. Source set: `jvmTest` (not `commonTest`) — required so the
 *      JVM-only `DebugProbes` and `Thread.getAllStackTraces()` calls
 *      can compile.
 *   2. Class name: `BodyTimeoutHangReproTest` (not
 *      `BodyTimeoutContractTest`) — avoid class-name collision with the
 *      quarantined original.
 *   3. Each `@Test` method wrapped with `startExternalWatchdog(...)` +
 *      `DebugProbes.install()` + `DebugProbes.uninstall()` for
 *      diagnostic dump on hang. The test method bodies (fixture use,
 *      `runTest` invocation, assertions) are byte-for-byte identical to
 *      the original except for the wrapper.
 *
 * The four failing exception cells + the two success cells all run
 * exactly as they do in the original, using the original
 * `RecordingCursorRepo`, `BodyTimeoutTestTransport`, `buildOrchestrator`
 * helper, `init()` libsodium bootstrap, and `runTest(timeout = 5.minutes)`
 * cap.
 *
 * Expected CI Ubuntu outcomes:
 *
 *   Case A — HANG reproduced on one or more cells. The watchdog daemon
 *   fires every 60 s of wall clock, dumping (a) `DebugProbes.dumpCoroutines`
 *   (Kotlin suspended-coroutine frames), (b) full JVM
 *   `Thread.getAllStackTraces()` to stderr. `runTest(timeout = 5.minutes)`
 *   is a hard real-time cap: even a total wedge exits before the 30-min
 *   CI job timeout, with ≥ 4 dump cycles for post-mortem. Then the
 *   original commonTest cells can be un-quarantined targeting the
 *   observed wedge.
 *
 *   Case B — CI Ubuntu PASSES all cells here in jvmTest. That would
 *   itself be a diagnostic result: the hang would depend on the
 *   `commonTest` source-set compilation / KMP test-runner shape, not on
 *   the cell code. Next step in that case: try un-quarantining the
 *   original file in place (adding the JVM-only diagnostic hook via
 *   an expect/actual seam or a companion runner).
 *
 * TEMPORARY. Delete after root cause is found and the original
 * `BodyTimeoutContractTest` is properly unquarantined.
 */
class BodyTimeoutHangReproTest {

    private val IDENTITY: String = "aa".repeat(32)

    // Round 13 follow-up (verbatim from `BodyTimeoutContractTest`).
    // The previous version of this fence read the KDoc literally
    // ("no libsodium primitive is invoked along this path") and
    // short-circuited `init()` to a no-op. That broke the test
    // because the orchestrator's jitter helper still pulls `Csprng`
    // bytes, and `Csprng` lives behind libsodium.
    private suspend fun init() {
        if (!com.ionspin.kotlin.crypto.LibsodiumInitializer.isInitialized()) {
            com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        }
    }

    // ── Invariant 1 — cursor preserved ──────────────────────────────────────

    @Test
    fun r12_body_timeout_after_headers_preserves_cursor() {
        runReproWithWatchdog(cellName = "r12_body_timeout_after_headers_preserves_cursor") {
            runTest(timeout = 5.minutes) {
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
        }
    }

    // ── Invariant 2 — no ack call ───────────────────────────────────────────

    @Test
    fun r12_body_timeout_after_headers_suppresses_ack() {
        runReproWithWatchdog(cellName = "r12_body_timeout_after_headers_suppresses_ack") {
            runTest(timeout = 5.minutes) {
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
        }
    }

    // ── Invariant 3 — breaker accounting ────────────────────────────────────

    @Test
    fun r12_body_timeout_after_headers_accounts_toward_breaker() {
        runReproWithWatchdog(cellName = "r12_body_timeout_after_headers_accounts_toward_breaker") {
            runTest(timeout = 5.minutes) {
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
                assertEquals(
                    LongPollBreakerState.Closed,
                    orch.peekBreakerStateForTest(),
                    "pre-condition: breaker must start Closed before the first poll attempt",
                )
                orch.start()
                runCurrent()
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
        }
    }

    // ── Invariant 4 — no immediate retry; cooldown progression ──────────────

    @Test
    fun r12_body_timeout_after_headers_does_not_retry_immediately() {
        runReproWithWatchdog(cellName = "r12_body_timeout_after_headers_does_not_retry_immediately") {
            runTest(timeout = 5.minutes) {
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
                advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
                runCurrent()
                val callsAfterFirstFailure = transport.pollCalls.size
                assertTrue(
                    callsAfterFirstFailure >= 1,
                    "expected the first poll attempt to fire and fail; got pollCalls.size=$callsAfterFirstFailure",
                )
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
        }
    }

    // ── Round 12 step 2 — hold_secs structured field on poll_call ──────────

    @Test
    fun r12_poll_call_log_includes_hold_secs_field() {
        runReproWithWatchdog(cellName = "r12_poll_call_log_includes_hold_secs_field") {
            runTest(timeout = 5.minutes) {
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
                assertTrue(
                    pollCallLine.contains("hold_secs=30"),
                    "Round 12 step 2: poll_call log MUST emit the server-advertised `pollHoldSecs` value. " +
                        "Test transport advertised pollHoldSecs=30; got: $pollCallLine",
                )
                orch.stop()
                runCurrent()
            }
        }
    }

    @Test
    fun r12_ws_active_poll_call_log_includes_hold_secs_field() {
        runReproWithWatchdog(cellName = "r12_ws_active_poll_call_log_includes_hold_secs_field") {
            runTest(timeout = 5.minutes) {
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
        }
    }

    // ── diagnostic wrapper (v4-only addition; not present in original) ──────

    private fun runReproWithWatchdog(cellName: String, block: () -> Unit) {
        val done = AtomicBoolean(false)
        val startWallMs = System.currentTimeMillis()
        val watchdog = Thread {
            var iter = 0
            while (!done.get()) {
                try {
                    Thread.sleep(60_000L)
                } catch (t: InterruptedException) {
                    return@Thread
                }
                if (done.get()) return@Thread
                iter += 1
                val elapsedS = (System.currentTimeMillis() - startWallMs) / 1000L
                val err: PrintStream = System.err
                synchronized(err) {
                    err.println()
                    err.println("╔══════════════════════════════════════════════════════════════════════════════")
                    err.println("║ HANG-REPRO WATCHDOG FIRE #$iter  cell=$cellName  wall_clock_elapsed_s=$elapsedS")
                    err.println("╚══════════════════════════════════════════════════════════════════════════════")
                    err.println("─── Kotlin coroutine dump (DebugProbes.dumpCoroutines) ───")
                    try {
                        DebugProbes.dumpCoroutines(err)
                    } catch (t: Throwable) {
                        err.println("  (DebugProbes.dumpCoroutines threw: ${t.javaClass.simpleName}: ${t.message})")
                    }
                    err.println()
                    err.println("─── JVM thread stack traces (Thread.getAllStackTraces) ───")
                    val stacks = Thread.getAllStackTraces()
                    for ((thread, frames) in stacks) {
                        err.println()
                        err.println("Thread \"${thread.name}\" state=${thread.state} daemon=${thread.isDaemon}")
                        for (frame in frames) {
                            err.println("  at $frame")
                        }
                    }
                    err.println("─── end HANG-REPRO watchdog dump #$iter ───")
                    err.println()
                }
            }
        }.apply {
            isDaemon = true
            name = "BodyTimeoutHangReproTest-Watchdog"
        }
        watchdog.start()
        try {
            DebugProbes.install()
            try {
                block()
            } finally {
                DebugProbes.uninstall()
            }
        } finally {
            done.set(true)
            watchdog.interrupt()
        }
    }

    // ── Test infrastructure (verbatim copy of BodyTimeoutContractTest's fixture) ──

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
