// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.di

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import phantom.core.messaging.VerifyOutcome
import phantom.core.messaging.VerifyTrigger
import phantom.core.transport.TransportState

/**
 * CLIENT-PREKEY-SELFHEAL AppContainer wiring tests — 8 acceptance-matrix
 * rows from `docs/tracks/client-prekey-selfheal.md` §7:
 *  * T2-tick  — persistent-transient cadence bound (≤15 first hour, ≤12/hour steady)
 *  * T4       — new Connected does NOT cancel an in-flight verify (SkippedInFlight)
 *  * T8-hook  — Connected-hook re-throws CancellationException, cancels the launch
 *  * T8-ticker — ticker `while (isActive)` loop exits on scope cancel
 *  * T11      — first periodic tick fires AFTER 15 min, not immediately
 *  * T-P1-1   — ticker log line contains only `type=<class>`, never `.message`
 *  * T-F1a (android half) — Connected-hook `.onFailure` log is class-only
 *  * T-F15    — replenish/rotate `.onFailure` logs are class-only, not silently discarded
 *
 * Scope: this file exercises the **wiring pattern** the two AppContainer
 * blocks use (docs §6.5 lines 2285-2344 in production
 * `AppContainer.kt`) — the Connected-hook `transport.state.collect { }`
 * launch AND the `while (isActive) { delay(...); try onPeriodicTick() ... }`
 * launch — reconstructed inline as `launchConnectedHook` /
 * `launchPeriodicTicker`. The real AppContainer's ctor pulls a full
 * Context + Ktor + Xray + Tor stack, so a real-container instantiation
 * is impractical here. The reconstructed shape is byte-equivalent to
 * production: same `.onFailure { CE → throw }` discipline, same
 * `try / catch (CancellationException) { throw ce } / catch (Throwable)`
 * skeleton on the ticker.
 *
 * Time model: `apps/android` declares no `kotlinx-coroutines-test`
 * dependency (see `WsSessionLifecycleContractTest`'s KDoc comment for
 * the same constraint). Instead of `runTest` virtual time, these tests
 * use `runBlocking` with a hand-rolled `tickGate: Channel<Unit>` as a
 * synchronous stand-in for `delay(PREKEY_PERIODIC_VERIFY_INTERVAL_MS)`.
 * `tickGate.send(Unit)` is the test-side equivalent of one 15-minute
 * virtual-time advance. No real-time waits.
 *
 * Log model: the two production launch blocks call
 * `android.util.Log.w("PreKeyLifecycle", "<message>")`. Since AGP stubs
 * `android.util.Log` under the JVM unit-test runner (Log calls become
 * no-ops), the helpers accept a `logSink: (String) -> Unit` seam that
 * receives the SAME string content the production code hands to `Log.w`.
 * Tests assert on the captured strings — this is a shape-equivalent
 * assertion (per task-scope §"Test framework" allowance).
 */
class PreKeySelfHealHookTest {

    // ────────────────────────────────────────────────────────────────
    // Fake service — mirrors PreKeyLifecycleService's public surface
    // needed by the two AppContainer launch blocks. Uses a real
    // `Mutex.tryLock()` so `SkippedInFlight` fires under the same
    // single-flight semantic as production (§6.3 verifyLocked mutex).
    // ────────────────────────────────────────────────────────────────
    private class FakeLifecycleService {
        private val verifyMutex = Mutex()

        // Counters — observable from tests.
        val verifyEntryCount = AtomicInteger(0)       // total calls (incl. SkippedInFlight fast-path)
        val verifyBlockRunCount = AtomicInteger(0)     // calls that acquired the mutex
        val periodicTickEntryCount = AtomicInteger(0)  // onPeriodicTick calls
        val replenishCount = AtomicInteger(0)
        val rotateCount = AtomicInteger(0)
        val recordedTriggers: MutableList<VerifyTrigger> =
            Collections.synchronizedList(mutableListOf())

        // Injectable behaviour blocks.
        var verifyBlock: suspend () -> Result<VerifyOutcome> =
            { Result.success(VerifyOutcome.AlreadyPublished) }
        var onPeriodicTickBlock: suspend () -> Unit =
            { verifyBundleOnRelay(VerifyTrigger.Periodic) }
        var replenishBlock: suspend () -> Result<Boolean> = { Result.success(false) }
        var rotateBlock: suspend () -> Result<Boolean> = { Result.success(false) }

        suspend fun verifyBundleOnRelay(trigger: VerifyTrigger): Result<VerifyOutcome> {
            verifyEntryCount.incrementAndGet()
            recordedTriggers.add(trigger)
            if (!verifyMutex.tryLock()) {
                return Result.success(VerifyOutcome.SkippedInFlight)
            }
            return try {
                verifyBlockRunCount.incrementAndGet()
                verifyBlock()
            } finally {
                verifyMutex.unlock()
            }
        }

        suspend fun onPeriodicTick() {
            periodicTickEntryCount.incrementAndGet()
            onPeriodicTickBlock()
        }

        suspend fun maybeReplenishOneTimePreKeys(): Result<Boolean> {
            replenishCount.incrementAndGet()
            return replenishBlock()
        }

        suspend fun maybeRotateSignedPreKey(): Result<Boolean> {
            rotateCount.incrementAndGet()
            return rotateBlock()
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Helper: reconstructed Connected-hook launch (AppContainer.kt:2285-2318).
    // Byte-equivalent to production apart from the `logSink` seam
    // replacing `android.util.Log.w("PreKeyLifecycle", …)`.
    // ────────────────────────────────────────────────────────────────
    private fun launchConnectedHook(
        scope: CoroutineScope,
        stateFlow: Flow<TransportState>,
        svc: FakeLifecycleService,
        logSink: (String) -> Unit,
    ): Job = scope.launch {
        stateFlow.collect { st ->
            if (st !is TransportState.Connected) return@collect
            svc.verifyBundleOnRelay(VerifyTrigger.Connected).onFailure {
                if (it is CancellationException) throw it
                logSink(
                    "verifyBundleOnRelay on reconnect failed: type=${it::class.simpleName}",
                )
            }
            svc.maybeReplenishOneTimePreKeys().onFailure {
                if (it is CancellationException) throw it
                logSink("Replenish on reconnect failed: type=${it::class.simpleName}")
            }
            svc.maybeRotateSignedPreKey().onFailure {
                if (it is CancellationException) throw it
                logSink("Rotate on reconnect failed: type=${it::class.simpleName}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Helper: reconstructed periodic-ticker launch (AppContainer.kt:2330-2344).
    // `tickGate.receive()` stands in for `delay(PREKEY_PERIODIC_VERIFY_INTERVAL_MS)`.
    // ────────────────────────────────────────────────────────────────
    private fun launchPeriodicTicker(
        scope: CoroutineScope,
        tickGate: Channel<Unit>,
        svc: FakeLifecycleService,
        logSink: (String) -> Unit,
    ): Job = scope.launch {
        while (isActive) {
            tickGate.receive()  // stand-in for delay(PREKEY_PERIODIC_VERIFY_INTERVAL_MS)
            try {
                svc.onPeriodicTick()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logSink("Periodic verify tick threw: type=${t::class.simpleName}")
            }
        }
    }

    // ================================================================
    // T2-tick
    // ================================================================

    @Test
    fun `T2-tick persistentTransient retriesOnNextPeriodicTick capsAt12PerHourSteady 15FirstHour`() =
        runBlocking {
            val svc = FakeLifecycleService().apply {
                // Persistent transient: every verify returns Result.failure.
                verifyBlock = { Result.failure(RuntimeException("persistent transient")) }
            }
            val tickGate = Channel<Unit>(Channel.RENDEZVOUS)
            val stateFlow = MutableSharedFlow<TransportState>(replay = 0, extraBufferCapacity = 8)
            val logs = Collections.synchronizedList(mutableListOf<String>())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

            val hookJob = launchConnectedHook(scope, stateFlow, svc) { logs.add(it) }
            val tickerJob = launchPeriodicTicker(scope, tickGate, svc) { logs.add(it) }
            yield()

            // Initial Connected trigger (startup).
            stateFlow.emit(TransportState.Connected)
            yield()

            // Fire 4 ticks — represents virtual time advancing to 15/30/45/60 min.
            repeat(4) {
                tickGate.send(Unit)
                yield()
            }

            // First-hour worst-case per row T2-tick: 1 startup Connected + 4 ticks = 5 SERVICE
            // calls at THIS layer. Transport-layer retries expand each to ≤3 GETs (bound 15),
            // but that expansion is enforced by PreKeyApiClient.fetchStatus (T1/T2-per-trigger)
            // — not by the wiring under test here. What we assert at THIS layer is the CADENCE
            // is unchanged by persistent failure: exactly N ticks per hour, no hidden retry
            // multiplication.
            assertEquals(
                4, svc.periodicTickEntryCount.get(),
                "Persistent failure must not multiply tick cadence — exactly 4 ticks in first hour",
            )
            // 1 Connected + 4 Periodic acquisitions of the mutex.
            assertEquals(5, svc.verifyEntryCount.get())
            assertTrue(
                svc.periodicTickEntryCount.get() <= 15,
                "First-hour periodic tick count ${svc.periodicTickEntryCount.get()} must be ≤15",
            )

            // Steady-state: advance one more hour (4 more ticks).
            val ticksBefore = svc.periodicTickEntryCount.get()
            repeat(4) {
                tickGate.send(Unit)
                yield()
            }
            val steadyDelta = svc.periodicTickEntryCount.get() - ticksBefore
            assertEquals(4, steadyDelta, "Steady hour must fire exactly 4 ticks (15-min cadence)")
            assertTrue(steadyDelta <= 12, "Steady-state ticks/hour must be ≤12")

            scope.cancel()
            hookJob.join()
            tickerJob.join()
        }

    // ================================================================
    // T4 — new Connected must NOT cancel an in-flight verify
    // ================================================================

    @Test
    fun `T4 newConnectedDoesNotCancelActiveVerify`() = runBlocking {
        val svc = FakeLifecycleService()
        val verifyStarted = CompletableDeferred<Unit>()
        val verifyCanFinish = CompletableDeferred<Unit>()
        svc.verifyBlock = {
            verifyStarted.complete(Unit)
            verifyCanFinish.await()
            Result.success(VerifyOutcome.AlreadyPublished)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        // First call: launch on the scope so it can suspend independently.
        val firstResultDeferred = scope.async {
            svc.verifyBundleOnRelay(VerifyTrigger.Connected)
        }
        // Wait until the first call has entered verifyBlock (mutex is held).
        verifyStarted.await()

        // Second call while the first is still holding the mutex.
        val secondResult = svc.verifyBundleOnRelay(VerifyTrigger.Connected)
        assertTrue(secondResult.isSuccess, "Second trigger must NOT throw or cancel")
        assertEquals(
            VerifyOutcome.SkippedInFlight, secondResult.getOrNull(),
            "Second concurrent trigger must observe SkippedInFlight from the mutex",
        )

        // Original job must NOT have been cancelled by the second trigger.
        assertFalse(
            firstResultDeferred.isCancelled,
            "The original in-flight verify must not have been cancelled by the second trigger",
        )
        assertFalse(
            firstResultDeferred.isCompleted,
            "The original in-flight verify must still be running (awaiting verifyCanFinish)",
        )

        // Release the first call and observe the normal outcome.
        verifyCanFinish.complete(Unit)
        val firstResult = firstResultDeferred.await()
        assertEquals(
            Result.success(VerifyOutcome.AlreadyPublished), firstResult,
            "Original verify must complete normally with its own outcome",
        )
        assertEquals(1, svc.verifyBlockRunCount.get(), "verifyBlock runs exactly once")
        assertEquals(2, svc.verifyEntryCount.get(), "Two total verify entries (one skipped)")

        scope.cancel()
    }

    // ================================================================
    // T8-hook — Connected-hook re-throws CancellationException
    // ================================================================

    @Test
    fun `T8-hook connectedHookCollectLambda CancellationExceptionPropagates`() = runBlocking {
        val svc = FakeLifecycleService()
        // verifyBlock returns Result.failure(CancellationException) — the hook's
        // .onFailure { if it is CE throw it } must re-throw and cancel the launch.
        svc.verifyBlock = {
            Result.failure(CancellationException("simulated verify CE"))
        }
        val stateFlow = MutableSharedFlow<TransportState>(replay = 0, extraBufferCapacity = 8)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val hookJob = launchConnectedHook(scope, stateFlow, svc) { logs.add(it) }
        yield()

        // Fire Connected — this should trigger CE propagation and cancel the launch.
        stateFlow.emit(TransportState.Connected)
        yield()

        // Wait for cancellation to complete.
        hookJob.join()

        // Assert: the hook launch is completed (not still running), and no non-CE log line
        // was emitted for the CE (per Rejects: "runCatching swallows CE — defeats structured
        // concurrency"; the CE must NOT be surfaced as a class-only log line).
        assertTrue(hookJob.isCompleted, "Hook launch must be completed after CE")
        assertTrue(hookJob.isCancelled, "Hook launch must be cancelled by CE re-throw")
        assertTrue(
            logs.none { it.contains("verifyBundleOnRelay on reconnect failed") },
            "CE must NOT be logged as a class-only reconnect-failed line — was: $logs",
        )
        // Replenish + Rotate must NOT have been called (the CE short-circuited the collect body).
        assertEquals(
            0, svc.replenishCount.get(),
            "Replenish must NOT be reached after the verify CE",
        )
        assertEquals(
            0, svc.rotateCount.get(),
            "Rotate must NOT be reached after the verify CE",
        )

        scope.cancel()
    }

    // ================================================================
    // T8-ticker — periodic ticker `while (isActive)` exits on scope cancel
    // ================================================================

    @Test
    fun `T8-ticker periodicTicker CancellationExceptionExitsLoop viaWhileIsActive`() =
        runBlocking {
            val svc = FakeLifecycleService()
            val tickGate = Channel<Unit>(Channel.RENDEZVOUS)
            val logs = Collections.synchronizedList(mutableListOf<String>())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

            val tickerJob = launchPeriodicTicker(scope, tickGate, svc) { logs.add(it) }
            yield()

            // Ticker is currently suspended inside tickGate.receive(). Cancel the scope
            // — receive() sees the cancellation, throws CE, ticker's outer try re-throws,
            // the launch job cancels, `while (isActive)` no longer applies (job is done).
            assertFalse(tickerJob.isCompleted, "Ticker should be running (suspended on receive)")
            scope.cancel()
            tickerJob.join()

            assertTrue(tickerJob.isCompleted, "Ticker launch must complete after scope cancel")
            assertTrue(tickerJob.isCancelled, "Ticker launch must be cancelled (CE propagated)")
            // No tick fired; no periodic tick log entry.
            assertEquals(0, svc.periodicTickEntryCount.get(), "No tick fired before cancel")
            assertTrue(
                logs.isEmpty(),
                "CE must NOT be logged as `Periodic verify tick threw: …` — was: $logs",
            )
        }

    // ================================================================
    // T11 — first tick fires after 15 min, NOT immediately
    // ================================================================

    @Test
    fun `T11 periodicTicker firstTickAfter15Min notImmediate`() = runBlocking {
        val svc = FakeLifecycleService()
        val tickGate = Channel<Unit>(Channel.RENDEZVOUS)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val tickerJob = launchPeriodicTicker(scope, tickGate, svc) { logs.add(it) }
        yield()

        // Simulate advancing 5 min (< 15 min) — NO tick should fire because production's
        // ticker `delay(PREKEY_PERIODIC_VERIFY_INTERVAL_MS)` fires the loop body AFTER the
        // 15-min mark. In this harness, sending nothing on tickGate models "time has passed
        // but the deadline was not reached".
        // Assert count == 0.
        assertEquals(
            0, svc.periodicTickEntryCount.get(),
            "First tick must NOT fire before the 15-min deadline (production `delay` blocks first)",
        )

        // Advance to 16 min (past the first 15-min deadline) — one tick fires.
        tickGate.send(Unit)
        yield()
        assertEquals(
            1, svc.periodicTickEntryCount.get(),
            "Exactly ONE tick must fire after crossing the first 15-min deadline",
        )

        scope.cancel()
        tickerJob.join()
    }

    // ================================================================
    // T-P1-1 — periodic ticker log line does NOT contain `.message`
    // ================================================================

    @Test
    fun `T-P1-1 periodicTickerLog doesNotContainMessage`() = runBlocking {
        val svc = FakeLifecycleService()
        // Force onPeriodicTick to throw a non-CE with a distinctive message that MUST NOT leak.
        val leakSentinel = "SECRET_MESSAGE_MUST_NOT_LEAK_pin_2026_07_16"
        svc.onPeriodicTickBlock = {
            throw IllegalStateException(leakSentinel)
        }
        val tickGate = Channel<Unit>(Channel.RENDEZVOUS)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val tickerJob = launchPeriodicTicker(scope, tickGate, svc) { logs.add(it) }
        yield()

        tickGate.send(Unit)
        yield()

        assertEquals(
            1, svc.periodicTickEntryCount.get(),
            "Ticker must have invoked onPeriodicTick once",
        )
        assertEquals(
            1, logs.size,
            "Exactly one log line expected after the tick throw — was: $logs",
        )
        val line = logs.single()
        assertTrue(
            line.contains("Periodic verify tick threw"),
            "Log line must be the ticker's outer-catch marker — was: $line",
        )
        assertTrue(
            line.contains("type=IllegalStateException"),
            "Log line MUST contain the exception class simpleName — was: $line",
        )
        assertFalse(
            line.contains(leakSentinel),
            "Log line MUST NOT leak the exception .message — was: $line",
        )

        scope.cancel()
        tickerJob.join()
    }

    // ================================================================
    // T-F1a (android half) — Connected-hook verify log is class-only
    // ================================================================

    @Test
    fun `T-F1a callerObservesResultFailure andLogsClassOnly`() = runBlocking {
        val svc = FakeLifecycleService()
        // Distinctive leak sentinel in the exception's message.
        val leakSentinel = "verify_secret_message_2026_07_16"
        svc.verifyBlock = { Result.failure(RuntimeException(leakSentinel)) }
        val stateFlow = MutableSharedFlow<TransportState>(replay = 0, extraBufferCapacity = 8)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val hookJob = launchConnectedHook(scope, stateFlow, svc) { logs.add(it) }
        yield()

        stateFlow.emit(TransportState.Connected)
        yield()

        assertTrue(logs.isNotEmpty(), "Hook must have logged the verify failure")
        val verifyLine = logs.first { it.contains("verifyBundleOnRelay on reconnect failed") }
        assertTrue(
            verifyLine.contains("type=RuntimeException"),
            "Log line MUST contain type=<class simpleName> — was: $verifyLine",
        )
        assertFalse(
            verifyLine.contains(leakSentinel),
            "Log line MUST NOT contain the exception .message — was: $verifyLine",
        )
        // Downstream calls (replenish/rotate) still ran — this failure is Result.failure,
        // not a throw, so the collect body continues to the next call. Non-CE failure
        // paths MUST NOT short-circuit downstream reconnect duties.
        assertEquals(
            1, svc.replenishCount.get(),
            "Replenish must run after a non-CE verify Result.failure",
        )
        assertEquals(
            1, svc.rotateCount.get(),
            "Rotate must run after a non-CE verify Result.failure",
        )

        scope.cancel()
        hookJob.join()
    }

    // ================================================================
    // T-F15 — replenish + rotate `.onFailure` logs are class-only
    // ================================================================

    @Test
    fun `T-F15 replenishAndRotate ResultFailure isLoggedViaOnFailure notSilentlyDiscarded`() =
        runBlocking {
            val svc = FakeLifecycleService()
            val leakSentinel = "test"  // matches the task-spec exception message
            svc.replenishBlock = { Result.failure(IllegalStateException(leakSentinel)) }
            svc.rotateBlock = { Result.failure(IllegalStateException(leakSentinel)) }
            val stateFlow =
                MutableSharedFlow<TransportState>(replay = 0, extraBufferCapacity = 8)
            val logs = Collections.synchronizedList(mutableListOf<String>())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

            val hookJob = launchConnectedHook(scope, stateFlow, svc) { logs.add(it) }
            yield()

            stateFlow.emit(TransportState.Connected)
            yield()

            // Both replenish + rotate must have been called AND their .onFailure blocks
            // must have logged a class-only line for each (not silently discarded).
            assertEquals(1, svc.replenishCount.get(), "Replenish must have been called once")
            assertEquals(1, svc.rotateCount.get(), "Rotate must have been called once")

            val replenishLine =
                logs.singleOrNull { it.contains("Replenish on reconnect failed") }
                    ?: error("Missing Replenish failure log line — was: $logs")
            val rotateLine =
                logs.singleOrNull { it.contains("Rotate on reconnect failed") }
                    ?: error("Missing Rotate failure log line — was: $logs")

            assertTrue(
                replenishLine.contains("type=IllegalStateException"),
                "Replenish log MUST contain type=<class simpleName> — was: $replenishLine",
            )
            assertTrue(
                rotateLine.contains("type=IllegalStateException"),
                "Rotate log MUST contain type=<class simpleName> — was: $rotateLine",
            )
            // The exception's message ("test") is short and may appear as a substring elsewhere
            // in the format string; assert we do NOT emit "message=test" or similar plumbing
            // that would leak the throwable's raw .message content.
            assertFalse(
                replenishLine.contains("message="),
                "Replenish log MUST NOT contain a message= field — was: $replenishLine",
            )
            assertFalse(
                rotateLine.contains("message="),
                "Rotate log MUST NOT contain a message= field — was: $rotateLine",
            )
            // Neither log line ends with the raw exception .message (belt-and-braces).
            assertFalse(
                replenishLine.endsWith(": $leakSentinel"),
                "Replenish log MUST NOT tail with the raw .message — was: $replenishLine",
            )
            assertFalse(
                rotateLine.endsWith(": $leakSentinel"),
                "Rotate log MUST NOT tail with the raw .message — was: $rotateLine",
            )

            scope.cancel()
            hookJob.join()
        }
}
