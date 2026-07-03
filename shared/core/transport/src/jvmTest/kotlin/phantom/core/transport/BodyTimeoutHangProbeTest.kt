// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * DIAGNOSTIC PROBE — NOT a regression contract test, NOT for merge into master.
 *
 * Ubuntu-CI reproduction attempt for the BodyTimeoutContractTest hang.
 * Ships on a temporary diagnostic branch only; deleted after the root
 * cause is found.
 *
 * The class-level `@Ignore` on BodyTimeoutContractTest exists because
 * two cells (`does_not_retry_immediately`, `suppresses_ack`) hang the CI
 * Ubuntu runner for the full 30-minute job timeout while the same cells
 * pass on local Windows in ~30 s. Local Windows Explore-3 probe
 * (BodyTimeoutHangProbeTest, PASSED 24 s) shows Windows scheduling does
 * not reproduce the race even under 10× virtual-time amplification, so
 * dynamic evidence requires observing the hang on an Ubuntu runner.
 *
 * Shape:
 *   - Stripped-down variant of BodyTimeoutContractTest's failing cells.
 *   - Real-time watchdog thread (daemon) fires every 60 s wall clock;
 *     on each fire dumps to stderr:
 *       (1) Kotlin coroutine snapshot via `DebugProbes.dumpCoroutines()`
 *       (2) all JVM thread stack traces via `Thread.getAllStackTraces()`.
 *   - `withTimeout(30.seconds)` around `orch.stop()` promotes a silent
 *     hang into a `TimeoutCancellationException` with a stack trace on
 *     the test scope's dispatcher (virtual-time bounded).
 *   - `runTest(timeout = 8.minutes)` — real-time upper bound so that
 *     even a total wedge exits before the 30 min CI job timeout and
 *     the watchdog gets ≥ 7 dump cycles to work with.
 *
 * PASS shape on Windows / macOS: probe completes in < 10 s wall clock.
 * FAIL shape expected on Ubuntu CI (if the hypothesis holds): watchdog
 * fires at 60 s / 120 s / … with dumps to stderr, then either
 * `withTimeout` throws inside `stop()` or `runTest`'s real-time timeout
 * fires.
 *
 * TEMPORARY DIAGNOSTIC. Delete this file before B1-closure follow-up
 * PR gets merged.
 */
class BodyTimeoutHangProbeTest {

    private val IDENTITY: String = "aa".repeat(32)

    private suspend fun ensureLibsodium() {
        if (!com.ionspin.kotlin.crypto.LibsodiumInitializer.isInitialized()) {
            com.ionspin.kotlin.crypto.LibsodiumInitializer.initialize()
        }
    }

    // ── v1 cells — already CI-green (baseline; kept as sanity that watchdog fires as expected) ──

    @Test
    fun probe_v1_single_stop_returns_after_repeated_body_timeout() = runTest(timeout = 8.minutes) {
        ensureLibsodium()
        DebugProbes.install()
        try {
            withWatchdog(cellName = "probe_v1_single_stop_returns_after_repeated_body_timeout") {
                driveOneCycle(label = "cycle-1", advanceTotalMs = 300_000L)
            }
        } finally {
            DebugProbes.uninstall()
        }
    }

    @Test
    fun probe_v1_two_back_to_back_cycles_both_stop_cleanly() = runTest(timeout = 8.minutes) {
        ensureLibsodium()
        DebugProbes.install()
        try {
            withWatchdog(cellName = "probe_v1_two_back_to_back_cycles_both_stop_cleanly") {
                driveOneCycle(label = "cycle-A", advanceTotalMs = 60_000L)
                driveOneCycle(label = "cycle-B", advanceTotalMs = 60_000L)
            }
        } finally {
            DebugProbes.uninstall()
        }
    }

    // ── v2 cells — mirror BodyTimeoutContractTest failing-cell shapes verbatim ──
    //
    // v2 differences from v1:
    //   * watchdog + DebugProbes wrap the WHOLE @Test method (outside `runTest`)
    //     so runTest's implicit-cleanup + advanceUntilIdle at cell exit gets
    //     covered too
    //   * NO `withTimeout(30.seconds)` around `orch.stop()` — original tests
    //     don't have that either; a silent hang propagates to runTest's 8-min
    //     real-time cap
    //   * time-advance patterns match original cells verbatim (POLL_ACTIVE_MS =
    //     2_000L per const val; advance sizes 30_000L / 60_000L / three-step
    //     POLL_ACTIVE_MS+100 → 1_000L → 10_000L)

    @Test
    fun probe_v2_preserves_cursor_shape() {
        runV2WithWatchdog(cellName = "probe_v2_preserves_cursor_shape") {
            driveCellShape(label = "preserves_cursor") { advanceTimeBy(30_000L) }
        }
    }

    @Test
    fun probe_v2_suppresses_ack_shape() {
        runV2WithWatchdog(cellName = "probe_v2_suppresses_ack_shape") {
            driveCellShape(label = "suppresses_ack") { advanceTimeBy(30_000L) }
        }
    }

    @Test
    fun probe_v2_accounts_toward_breaker_shape() {
        runV2WithWatchdog(cellName = "probe_v2_accounts_toward_breaker_shape") {
            driveCellShape(label = "accounts_toward_breaker") { advanceTimeBy(60_000L) }
        }
    }

    @Test
    fun probe_v2_does_not_retry_immediately_shape() {
        runV2WithWatchdog(cellName = "probe_v2_does_not_retry_immediately_shape") {
            driveCellShape(label = "does_not_retry_immediately") {
                advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
                runCurrent()
                advanceTimeBy(1_000L)
                runCurrent()
                advanceTimeBy(10_000L)
            }
        }
    }

    // ── v3 cells — same shapes but WITHOUT DebugProbes, as a control for observer effect ──

    @Test
    fun probe_v3_no_debug_probes_does_not_retry_immediately_shape() {
        val watchdog = startExternalWatchdog(cellName = "probe_v3_no_debug_probes_does_not_retry_immediately_shape")
        try {
            runTest(timeout = 8.minutes) {
                ensureLibsodium()
                driveCellShape(label = "v3_does_not_retry_immediately") {
                    advanceTimeBy(RestFallbackOrchestrator.POLL_ACTIVE_MS + 100L)
                    runCurrent()
                    advanceTimeBy(1_000L)
                    runCurrent()
                    advanceTimeBy(10_000L)
                }
            }
        } finally {
            watchdog.done.set(true)
            watchdog.thread.interrupt()
        }
    }

    private fun runV2WithWatchdog(cellName: String, block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) {
        val watchdog = startExternalWatchdog(cellName = cellName)
        try {
            DebugProbes.install()
            try {
                runTest(timeout = 8.minutes) {
                    ensureLibsodium()
                    block()
                }
            } finally {
                DebugProbes.uninstall()
            }
        } finally {
            watchdog.done.set(true)
            watchdog.thread.interrupt()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.driveCellShape(
        label: String,
        advanceScript: suspend kotlinx.coroutines.test.TestScope.() -> Unit,
    ) {
        val transport = HangProbeTransport(
            pollScript = { _ ->
                throw RuntimeException("$label: simulated body-read timeout")
            },
        )
        val orch = RestFallbackOrchestrator(
            baseUrl = "https://relay.test",
            identityHex = IDENTITY,
            signingPubkeyHex = "bb".repeat(32),
            getChallenge = { _ -> "cc".repeat(32) },
            signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
            transport = transport,
            now = { 0L },
            log = { },
            longPollEnabled = false,
            cursorRepository = HangProbeCursor(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val caps = orch.bootstrap()
        check(caps.restFallback) { "$label: bootstrap did not report restFallback capability" }
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive) {
            "$label: expected RestActive after fail-threshold submits, got ${orch.stateMachine.state.value}"
        }
        orch.start()
        runCurrent()
        advanceScript()
        runCurrent()
        // NO `withTimeout(30.seconds)` here — v2 lets stop() hang naturally so runTest's 8-min cap + watchdog capture the wedge shape.
        orch.stop()
        runCurrent()
    }

    private data class WatchdogHandle(
        val thread: Thread,
        val done: AtomicBoolean,
    )

    private fun startExternalWatchdog(cellName: String): WatchdogHandle {
        val done = AtomicBoolean(false)
        val startWallMs = System.currentTimeMillis()
        val thread = Thread {
            var iter = 0
            while (!done.get()) {
                try {
                    Thread.sleep(60_000L)
                } catch (t: InterruptedException) {
                    return@Thread
                }
                if (done.get()) return@Thread
                iter += 1
                dumpAllThreadStates(iter, cellName, startWallMs, includeCoroutineDebugProbes = true)
            }
        }.apply {
            isDaemon = true
            name = "BodyTimeoutHangProbeTest-Watchdog-Outer"
        }
        thread.start()
        return WatchdogHandle(thread, done)
    }

    private fun dumpAllThreadStates(
        iter: Int,
        cellName: String,
        startWallMs: Long,
        includeCoroutineDebugProbes: Boolean,
    ) {
        val elapsedS = (System.currentTimeMillis() - startWallMs) / 1000L
        val err: PrintStream = System.err
        synchronized(err) {
            err.println()
            err.println("╔══════════════════════════════════════════════════════════════════════════════")
            err.println("║ WATCHDOG FIRE #$iter  cell=$cellName  wall_clock_elapsed_s=$elapsedS")
            err.println("╚══════════════════════════════════════════════════════════════════════════════")
            if (includeCoroutineDebugProbes) {
                err.println("─── Kotlin coroutine dump (DebugProbes.dumpCoroutines) ───")
                try {
                    DebugProbes.dumpCoroutines(err)
                } catch (t: Throwable) {
                    err.println("  (DebugProbes.dumpCoroutines threw: ${t.javaClass.simpleName}: ${t.message})")
                }
                err.println()
            } else {
                err.println("─── (DebugProbes disabled for this cell — observer-effect control) ───")
            }
            err.println("─── JVM thread stack traces (Thread.getAllStackTraces) ───")
            val stacks = Thread.getAllStackTraces()
            for ((thread, frames) in stacks) {
                err.println()
                err.println("Thread \"${thread.name}\" state=${thread.state} daemon=${thread.isDaemon}")
                for (frame in frames) {
                    err.println("  at $frame")
                }
            }
            err.println("─── end watchdog dump #$iter ───")
            err.println()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.driveOneCycle(
        label: String,
        advanceTotalMs: Long,
    ) {
        val transport = HangProbeTransport(
            pollScript = { _ ->
                throw RuntimeException("$label: simulated body-read timeout")
            },
        )
        val orch = RestFallbackOrchestrator(
            baseUrl = "https://relay.test",
            identityHex = IDENTITY,
            signingPubkeyHex = "bb".repeat(32),
            getChallenge = { _ -> "cc".repeat(32) },
            signChallenge = { _ -> ByteArray(64) { 0xDD.toByte() } },
            transport = transport,
            now = { 0L },
            log = { },
            longPollEnabled = false,
            cursorRepository = HangProbeCursor(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val caps = orch.bootstrap()
        check(caps.restFallback) { "$label: bootstrap did not report restFallback capability" }
        repeat(RestStateMachine.ACTIVE_FAIL_THRESHOLD) {
            orch.submitEvent(
                RestStateMachine.Event.WsSessionEnded(
                    durationMs = 1000L, inboundFrames = 0, pendingAcksAtClose = 1,
                    sessionEpoch = 0L,
                ),
            )
        }
        check(orch.stateMachine.state.value == RestMode.RestActive) {
            "$label: expected RestActive after fail-threshold submits, got ${orch.stateMachine.state.value}"
        }
        orch.start()
        runCurrent()
        advanceTimeBy(advanceTotalMs)
        runCurrent()
        assertTrue(
            transport.pollCalls.size >= 2,
            "$label: expected at least 2 poll attempts under sustained body-timeout; got ${transport.pollCalls.size}",
        )
        // Bounded stop: if stop() deadlocks (any cause), this throws
        // TimeoutCancellationException instead of hanging silently.
        withTimeout(30.seconds) {
            orch.stop()
        }
        runCurrent()
    }

    /**
     * Real-time (wall-clock) watchdog daemon thread. Every 60 s while
     * the block is running, dumps Kotlin coroutine state (via
     * `DebugProbes.dumpCoroutines`) and full JVM thread stacks to
     * stderr. On block completion, cancels the daemon quietly.
     */
    private inline fun withWatchdog(cellName: String, block: () -> Unit) {
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
                    err.println("║ WATCHDOG FIRE #$iter  cell=$cellName  wall_clock_elapsed_s=$elapsedS")
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
                    err.println("─── end watchdog dump #$iter ───")
                    err.println()
                }
            }
        }.apply {
            isDaemon = true
            name = "BodyTimeoutHangProbeTest-Watchdog"
        }
        watchdog.start()
        try {
            block()
        } finally {
            done.set(true)
            watchdog.interrupt()
        }
    }

    private class HangProbeTransport(
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
            fail("send not used in hang-probe")

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

    private class HangProbeCursor : LongPollCursorRepository {
        var stored: Long? = null

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
            stored = maxOf(previous ?: Long.MIN_VALUE, seq)
            return CursorUpsertOutcome.Advanced(seq)
        }
    }
}
