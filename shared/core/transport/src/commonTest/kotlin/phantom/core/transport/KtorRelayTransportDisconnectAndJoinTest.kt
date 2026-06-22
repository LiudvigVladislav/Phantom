// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RC-RECONNECT-QUIESCENCE1 (2026-06-21) — unit tests for the
 * `disconnectAndJoin(timeoutMs)` lifecycle API on [KtorRelayTransport].
 *
 * Scope-lock R3 mandates:
 *   1. Acquires `connectionLifecycleMutex` (no parallel `connect()` race).
 *   2. Captures local `val job = reconnectJob` BEFORE nulling the field.
 *   3. Runs the existing teardown (close session, cancel pingJob etc.).
 *   4. Cancels the reconnect-loop coroutine, awaits join with bounded timeout.
 *   5. Returns `true` on clean join, `false` on timeout.
 *   6. ONLY AFTER join (or timeout) does `reconnectJob = null` so a parallel
 *      `connect()` cannot observe a null ref and launch a fresh loop during
 *      teardown.
 *
 * The tests use synthetic [Job] instances installed via
 * [KtorRelayTransport.seedReconnectJobForTest] so the contract is exercised
 * end-to-end at the public API surface without spinning up a real Ktor
 * `webSocket{}` body. `runBlocking` is used over `runTest` to keep the
 * cancellation + join semantics on real time — virtual-time interactions
 * between `withTimeoutOrNull(join)` and cancelled `delay(...)` in nested
 * coroutines are known to be fragile.
 */
class KtorRelayTransportDisconnectAndJoinTest {

    /**
     * Test lifecycle requirement (2026-06-22): every transport
     * created in a test gets registered here and drained by
     * [closeAllTransports] in [@AfterTest][AfterTest]. Without this,
     * the Gradle test JVM accumulates SupervisorJob roots across tests
     * (cleanupScope + per-generation scope) and eventually hangs the
     * worker on the full jvmTest sweep.
     */
    private val livingTransports = mutableListOf<KtorRelayTransport>()

    private fun newTransport(): KtorRelayTransport = KtorRelayTransport(
        httpClientFactory = {
            error("test must not invoke httpClientFactory — pure in-memory exercise")
        },
    ).also { livingTransports.add(it) }

    @AfterTest
    fun closeAllTransports() = runBlocking {
        // RC-RECONNECT-QUIESCENCE1 commit 2e fix-round-4 P2 (2026-06-23).
        // `closeForTest(): Boolean` returns `false` when the inner
        // `cleanupInflight` drain timed out — a stuck cleanup scope.
        // Discard via `runCatching { it.closeForTest() }` silently leaked
        // zombie cleanup-scope workers into subsequent test runs and
        // intermittently hung Gradle sweeps. The teardown now classifies
        // three failure modes per transport and fails the test by name.
        val failures = mutableListOf<String>()
        livingTransports.forEachIndexed { idx, t ->
            val outcome = runCatching {
                withTimeoutOrNull(6_000L) {
                    t.closeForTest(awaitInflightTimeoutMs = 5_000L)
                }
            }
            when {
                outcome.isFailure ->
                    failures.add(
                        "transport[$idx] closeForTest threw " +
                            outcome.exceptionOrNull(),
                    )
                outcome.getOrNull() == null ->
                    failures.add(
                        "transport[$idx] closeForTest did not return within " +
                            "6 s outer timeout; cleanupInflight=" +
                            "${t.cleanupInflightForTest()}",
                    )
                outcome.getOrNull() == false ->
                    failures.add(
                        "transport[$idx] closeForTest reported stuck " +
                            "cleanupInflight after the 5 s drain window; " +
                            "cleanupInflight=${t.cleanupInflightForTest()}",
                    )
            }
        }
        livingTransports.clear()
        if (failures.isNotEmpty()) {
            error(
                "@AfterTest teardown failed (${failures.size} failure(s)):\n" +
                    failures.joinToString("\n") { "  - $it" },
            )
        }
    }

    @Test
    fun disconnectAndJoin_returns_true_immediately_when_no_reconnect_job() = runBlocking {
        val transport = newTransport()
        val result = transport.disconnectAndJoin(timeoutMs = 1_000)
        assertTrue(result, "no reconnect job ⇒ disconnectAndJoin must succeed immediately")
    }

    @Test
    fun disconnectAndJoin_joins_a_cancelled_job_and_clears_the_field() = runBlocking {
        // R3 discipline: cancel + join + null AFTER join. Use a plain Job()
        // sentinel — it has no body to dispatch, so cancel + join is purely
        // a state-machine flip that completes synchronously.
        val transport = newTransport()
        val job = Job()
        transport.seedReconnectJobForTest(job)

        val result = transport.disconnectAndJoin(timeoutMs = 5_000)

        assertTrue(result, "cancellable sentinel job must join cleanly within the timeout")
        assertTrue(job.isCancelled, "job must be cancelled")
        assertTrue(job.isCompleted, "job must be fully completed")
        assertNull(
            transport.reconnectJobForTest(),
            "reconnectJob ref must be null AFTER the join (R3 discipline — not before)",
        )
    }

    @Test
    fun disconnectAndJoin_returns_false_when_reconnect_job_does_not_complete_in_time() = runBlocking {
        // A Job whose completion is held off externally. The test seeds it
        // as the reconnect-loop, then calls disconnectAndJoin with a very
        // small timeout. The timeoutMs bound must trip and disconnectAndJoin
        // must return false.
        val transport = newTransport()
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)

        val result = transport.disconnectAndJoin(timeoutMs = 100)

        assertFalse(
            result,
            "join timeout ⇒ disconnectAndJoin must return false so the coordinator can revoke the route change",
        )

        // Cleanup: complete the holding job so the test framework reports no leaks.
        holdingJob.releaseForTeardown()
    }

    @Test
    fun disconnectAndJoin_preserves_reconnectJob_ref_on_timeout_for_re_await() = runBlocking {
        // Locked lifecycle contract: on timeout the underlying job is still
        // draining; the reconnectJob reference MUST be preserved so:
        //   - a subsequent disconnectAndJoin() re-awaits the SAME job;
        //   - a subsequent connect() observes the still-alive job and refuses
        //     to launch a parallel loop.
        // Nulling the ref on timeout (the v1 implementation did this) was the
        // regression the review flagged.
        val transport = newTransport()
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)

        val result = transport.disconnectAndJoin(timeoutMs = 100)

        assertFalse(result, "timeout case still returns false")
        assertTrue(
            transport.reconnectJobForTest() === holdingJob.job,
            "reconnectJob ref MUST be preserved (identity-equal) on timeout so a subsequent " +
                "disconnectAndJoin() re-awaits the SAME job and connect() refuses to launch a parallel loop",
        )

        holdingJob.releaseForTeardown()
    }

    @Test
    fun disconnectAndJoin_cancels_reconnect_job_before_bounded_teardown() = runBlocking {
        // Locked lifecycle contract: `job.cancel()` MUST be issued IMMEDIATELY
        // after `disconnectRequested = true`, BEFORE the bounded teardown body
        // (flush + close session + close client + join). If session.close() or
        // client.close() hangs and the outer withTimeoutOrNull fires (or the
        // caller is cancelled), the reconnect loop must STILL have received
        // the cancellation signal. The previous shape put job.cancel() after
        // session/client close, which left an orphan loop running against the
        // already-closed transport on the timeout path.
        val transport = newTransport()
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)

        val result = transport.disconnectAndJoin(timeoutMs = 100)

        assertFalse(result, "timeout returns false")
        assertTrue(
            holdingJob.job.isCancelled,
            "reconnect job MUST be cancelled BEFORE the bounded teardown returns, even on timeout",
        )
        assertTrue(
            transport.reconnectJobForTest() === holdingJob.job,
            "ref preserved on timeout for re-await",
        )

        holdingJob.releaseForTeardown()
    }

    @Test
    fun disconnectAndJoin_releases_lifecycle_mutex_so_a_second_call_can_proceed() = runBlocking {
        // Sequential calls must both succeed. If connectionLifecycleMutex
        // were held across the bounded wait without release, the second
        // call would deadlock.
        val transport = newTransport()
        val first = transport.disconnectAndJoin(timeoutMs = 1_000)
        val second = transport.disconnectAndJoin(timeoutMs = 1_000)
        assertTrue(first)
        assertTrue(second)
    }

    @Test
    fun disconnectAndJoin_publishes_Disconnected_immediately_at_teardown_start() = runBlocking {
        // Review amendment P1 #2: external state must flip to Disconnected
        // the moment we commit to teardown — BEFORE the strict-bound body
        // (which contains the join wait that may time out). Previously the
        // _state assignment ran after session/client close so a hung close
        // path could leave the transport on Connected/Connecting visible to
        // external observers (isConnected, REST orchestrator, UI banner).
        // We pre-seed the state to Connected, then drive a timeout via the
        // StubbornNeverCompletingJob; the state MUST be Disconnected on
        // return even though the bounded body timed out.
        val transport = newTransport()
        transport.setStateConnectedForTest()
        assertTrue(transport.isConnected(), "precondition: transport reports Connected")
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)

        val result = transport.disconnectAndJoin(timeoutMs = 100)

        assertFalse(result, "timeout path returns false")
        assertFalse(
            transport.isConnected(),
            "transport state MUST be Disconnected immediately on teardown start — " +
                "even when the bounded body times out before close paths could complete",
        )

        holdingJob.releaseForTeardown()
    }

    @Test
    fun forceReconnect_is_no_op_during_disconnectRequested_drain() = runBlocking {
        // Review amendment P1 #1: forceReconnect MUST NOT overwrite the
        // preserved reconnectJob reference when the transport is mid-
        // teardown. After a disconnectAndJoin timeout: disconnectRequested
        // is true, reconnectJob still references the cancelling-but-not-
        // completed old job. forceReconnect under these conditions used
        // to cancel the old job and replace it with a fresh one — losing
        // the reference and the ability to re-await the same job.
        val transport = newTransport()
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)

        val timeoutResult = transport.disconnectAndJoin(timeoutMs = 100)
        assertFalse(timeoutResult, "precondition: timeout drives state into post-timeout drain")
        val refAfterTimeout = transport.reconnectJobForTest()
        assertNotNull(
            refAfterTimeout,
            "precondition: reconnectJob preserved on timeout",
        )

        // Now call forceReconnect. It MUST be a no-op — same job reference,
        // no fresh loop launched.
        transport.forceReconnect()

        val refAfterForceReconnect = transport.reconnectJobForTest()
        assertTrue(
            refAfterForceReconnect === refAfterTimeout,
            "forceReconnect MUST NOT overwrite the preserved reconnectJob reference while " +
                "disconnectRequested=true; old ref=$refAfterTimeout, new ref=$refAfterForceReconnect",
        )

        holdingJob.releaseForTeardown()
    }

    @Test
    fun connect_ignores_new_params_when_reconnect_loop_is_already_alive() = runBlocking {
        // Review amendments P1 #3 + P2 #4 + P2 (test barrier) regression
        // anchor: the connect() parameter writes MUST happen strictly inside
        // connectionLifecycleMutex AND only on the fresh-launch branch. When
        // a reconnect loop is already alive (!isCompleted), connect() must
        // reuse the existing job's join semantics WITHOUT touching the field
        // config.
        //
        // The barrier is `connectReuseBranchSignalForTest` — production
        // connect() completes the deferred at the exact point it takes the
        // reuse branch under the mutex. Replaces the prior `delay(100)`
        // shape which could not prove connect() had actually reached the
        // decision point.
        val transport = newTransport()
        val firstUrl = "wss://first.example/ws"
        val firstIdentity = "1111111111111111111111111111111111111111111111111111111111111111"
        val firstSigning = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        transport.setConnectParamsForTest(
            relayUrl = firstUrl,
            identityHex = firstIdentity,
            signingPubKeyHex = firstSigning,
            socksProxyPort = null,
        )
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)

        // Arm the test barrier BEFORE invoking connect().
        val reuseBranchTaken = CompletableDeferred<Unit>()
        transport.connectReuseBranchSignalForTest = reuseBranchTaken

        val secondUrl = "wss://second.example/ws"
        val secondIdentity = "2222222222222222222222222222222222222222222222222222222222222222"
        val secondSigning = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val connectJob = launch {
            try {
                transport.connect(
                    relayUrl = secondUrl,
                    identityPublicKeyHex = secondIdentity,
                    signingPublicKeyHex = secondSigning,
                    signChallenge = { _ -> null },
                    socksProxyPort = 1080,
                )
            } catch (_: CancellationException) {
                // expected — alive job never completes, we cancel below.
            }
        }
        // Deterministic barrier: wait for connect() to ACTUALLY reach the
        // reuse branch decision point under the mutex.
        reuseBranchTaken.await()

        assertEquals(
            firstUrl, transport.relayUrlForTest(),
            "relayUrl MUST NOT be overwritten while an alive reconnect loop holds the slot",
        )
        assertEquals(
            firstIdentity, transport.identityHexForTest(),
            "identityHex MUST NOT be overwritten",
        )
        assertEquals(
            firstSigning, transport.signingPubKeyHexForTest(),
            "signingPubKeyHex MUST NOT be overwritten",
        )

        connectJob.cancel()
        connectJob.join()
        holdingJob.releaseForTeardown()
    }

    @Test
    fun sendRaw_returns_false_when_session_is_null() = runBlocking {
        // Review amendment P1 (2026-06-21, third round): sendRaw used to
        // return `true` when `session == null` because `session?.send(...)`
        // is a null-safe no-op. That made every call after a teardown
        // session-null a silent false success. The fix is the null-check
        // at the top of sendRaw with a WARN log + `false` return. This
        // test pins that contract.
        val transport = newTransport()
        // Fresh transport — session is null by construction.
        val ackResult = transport.sendRawForTest(RelayMessage.AckDelivery(messageId = "ack-id-1"))
        assertFalse(
            ackResult,
            "sendRaw on a null session MUST return false (AckDelivery branch) — no silent false success",
        )
        val sendResult = transport.sendRawForTest(
            RelayMessage.Send(
                to = "deadbeef",
                sealedSender = "",
                payload = "payload",
                messageId = "send-id-1",
            )
        )
        assertFalse(
            sendResult,
            "sendRaw on a null session MUST return false (Send branch) — no silent false success",
        )
    }

    @Test
    fun disconnect_invokes_flushPendingOutbox_before_teardown() = runBlocking {
        // Review amendment P1 (2026-06-21, third round): legacy disconnect()
        // semantics — best-effort flush BEFORE teardown. Logout / shutdown
        // / transport-replacement callers have no follow-up
        // `WsActive → RestActive` transition, so the D1c REST migration
        // will NOT pick up the pending stores. Flush gives them one last
        // chance to drain through an alive session.
        val transport = newTransport()
        transport.flushPendingOutboxCallsForTest = 0
        transport.disconnect()
        assertTrue(
            transport.flushPendingOutboxCallsForTest >= 1,
            "disconnect() MUST invoke flushPendingOutbox (best-effort drain through alive session) — " +
                "actual=${transport.flushPendingOutboxCallsForTest}",
        )
    }

    @Test
    fun disconnectAndJoin_does_not_invoke_flushPendingOutbox() = runBlocking {
        // Review amendment P1 (2026-06-21, third round): rewalk /
        // quiescence-entry semantics — preserve pending stores for
        // PR-D1c REST migration. flushPendingOutbox MUST NOT run.
        val transport = newTransport()
        transport.flushPendingOutboxCallsForTest = 0
        val result = transport.disconnectAndJoin(timeoutMs = 1_000)
        assertTrue(result, "no live job + no live session ⇒ clean teardown returns true")
        assertEquals(
            0, transport.flushPendingOutboxCallsForTest,
            "disconnectAndJoin MUST NOT invoke flushPendingOutbox — pending stores remain for D1c migration. " +
                "Actual count=${transport.flushPendingOutboxCallsForTest}",
        )
    }

    @Test
    fun disconnectAndJoin_propagates_caller_cancellation_without_swallowing() = runBlocking {
        // Locked structured-concurrency contract — if the calling coroutine
        // is cancelled while disconnectAndJoin is mid-flight (specifically
        // while suspended inside the bounded `job.join()`),
        // CancellationException MUST propagate up. The state machine contract
        // relies on this so a parallel teardown cannot be ambiguous.
        val transport = newTransport()
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)

        var observedCe: CancellationException? = null
        val caller = launch {
            try {
                transport.disconnectAndJoin(timeoutMs = 60_000)
            } catch (ce: CancellationException) {
                observedCe = ce
                throw ce
            }
        }
        // Give the caller time to enter disconnectAndJoin and reach the
        // bounded `withTimeoutOrNull { ... join() }` body.
        delay(50)
        // Cancel from outside.
        caller.cancel(CancellationException("test cancel"))
        caller.join()

        assertNotNull(
            observedCe,
            "CancellationException MUST surface inside the disconnectAndJoin caller, not be swallowed",
        )

        holdingJob.releaseForTeardown()
    }

    // ── Fourth-round P1 / P2 anchors ──────────────────────────────────────

    @Test
    fun disconnect_flushes_BEFORE_canceling_reconnect_job() = runBlocking {
        // P1 fourth-round fix: when `flushBeforeClose = true` (legacy
        // disconnect path), the bounded flush MUST run BEFORE
        // `job.cancel()`. The earlier shape cancelled first, then flushed —
        // the reconnect-loop body could enter its finally block and close
        // the session concurrently with the flush, defeating logout's
        // best-effort-drain contract.
        //
        // We seed a real Job() sentinel so cancellation is observable; the
        // hook `jobIsCancelledAtFlushTimeForTest` captures
        // `reconnectJob?.isCancelled` at the moment the flush policy
        // decision runs. Correct order ⇒ observed value is `false`.
        val transport = newTransport()
        val job = Job()
        transport.seedReconnectJobForTest(job)
        transport.jobIsCancelledAtFlushTimeForTest = null

        transport.disconnect()

        assertEquals(
            false, transport.jobIsCancelledAtFlushTimeForTest,
            "flush must run BEFORE job.cancel() — observed reconnectJob.isCancelled at flush time " +
                "must be false (was ${transport.jobIsCancelledAtFlushTimeForTest})",
        )
        assertTrue(job.isCompleted, "post-disconnect: job is cancelled + joined")
    }

    @Test
    fun cleanup_body_runs_on_cleanupScope_independently_of_caller_cancellation() = runBlocking {
        // P1 fourth-round fix anchor: maybeLaunchCleanup dispatches its
        // action on `cleanupScope` (Dispatchers.IO + SupervisorJob),
        // which is independent of any caller coroutine. Once the
        // `cleanupScope.launch` call returns, the body is guaranteed to
        // run regardless of what happens to the launching coroutine.
        //
        // This test pins that property. The complementary concern —
        // that the SLOT-RESERVATION suspension point inside
        // `maybeLaunchCleanup` (`cleanupCounterMutex.withLock`) is
        // wrapped in `withContext(NonCancellable)` inside
        // `teardownAndJoin`, so caller cancellation cannot drop the
        // launch BEFORE it is invoked — is asserted via direct code
        // inspection at the `teardownAndJoin` call site, since it
        // requires seeding live Ktor session/client refs which the
        // commonTest harness cannot manufacture.
        val transport = newTransport()
        val ran = CompletableDeferred<Unit>()
        val caller = launch {
            transport.launchCleanupForTest("cleanup-after-caller-CE") {
                ran.complete(Unit)
            }
            // Caller throws AFTER the launch is dispatched. The launched
            // body has already been enqueued on cleanupScope — caller
            // cancellation cannot reach it.
            throw CancellationException("caller cancelled post-launch")
        }
        ran.await()
        assertTrue(caller.isCancelled, "caller was cancelled")
        caller.join()
    }

    @Test
    fun cleanup_cap_refuses_9th_then_accepts_after_one_slot_released() = runBlocking {
        // P2 fourth-round fix: prove the cleanup-cap is real.
        //  - first 8 launches accepted (counter saturates to cap);
        //  - 9th launch refused while cap is saturated;
        //  - completing one cleanup releases its slot;
        //  - subsequent launch accepted again.
        val transport = newTransport()
        // Use 8 long-held cleanups to saturate the cap.
        val holds = Array(8) { CompletableDeferred<Unit>() }
        val started = Array(8) { CompletableDeferred<Unit>() }
        repeat(8) { i ->
            transport.launchCleanupForTest("hung-$i") {
                started[i].complete(Unit)
                holds[i].await()
            }
        }
        // Wait until all 8 have actually entered the cleanup body.
        started.forEach { it.await() }
        assertEquals(
            8, transport.cleanupInflightForTest(),
            "after 8 launches the inflight counter must be at the cap",
        )

        // 9th attempt — must be refused (no body runs).
        val refused = CompletableDeferred<Unit>()
        transport.launchCleanupForTest("over-cap") {
            refused.complete(Unit)
        }
        // The refused launch returns immediately without dispatch. Give the
        // scheduler a moment; the deferred must STILL be uncompleted.
        delay(50)
        assertFalse(
            refused.isCompleted,
            "9th cleanup MUST be refused when cap is saturated — body must not run",
        )
        assertEquals(
            8, transport.cleanupInflightForTest(),
            "refused launch must NOT increment the inflight counter",
        )

        // Release one slot.
        holds[0].complete(Unit)
        // Wait for the counter to drop to 7.
        var settled = 0
        while (transport.cleanupInflightForTest() > 7 && settled < 200) {
            delay(5); settled++
        }
        assertEquals(
            7, transport.cleanupInflightForTest(),
            "completing one cleanup must release its slot",
        )

        // Next launch — accepted.
        val accepted = CompletableDeferred<Unit>()
        transport.launchCleanupForTest("after-release") {
            accepted.complete(Unit)
        }
        accepted.await()
        // Counter-state post-completion is racy (the cleanup body has
        // finished but the slot-release in the cleanup's `finally`
        // happens on cleanupScope and may not have settled yet). The
        // earlier phases pinned cap + refusal + release deterministically.

        // Release all remaining holds so the AfterTest teardown can
        // drain the cleanupInflight counter to zero deterministically.
        holds.forEach { if (!it.isCompleted) it.complete(Unit) }
    }

    // ── Fifth-round P1 anchors: critical teardown is atomic under caller CE ──

    @Test
    fun deterministic_flushEntered_flushRelease_cancellation_anchor() = runBlocking {
        // Closing-test-package anchor (2026-06-22).
        // Pins the contract that caller cancellation arriving WHILE
        // the legacy `disconnect()` is blocked inside its `flush`
        // window still:
        //   - captures the CE into `pendingCe`;
        //   - runs the full critical NonCancellable teardown
        //     (reconnect-job cancel + ping/ACK/scope cancel);
        //   - rethrows CE to the caller after the critical region.
        // Uses `flushEnteredForTest` / `flushReleaseForTest` deterministic
        // seams: the flush body completes `flushEntered` as soon as it
        // enters its `withTimeoutOrNull(3 s)` block and then `await`s
        // `flushRelease` so the test can land its `cancel(...)` while
        // the flush is genuinely in flight (rather than relying on
        // the prior heuristic that cancellation would happen to land
        // somewhere inside the body).
        val transport = newTransport()
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)
        val pingSentinel = Job()
        val ackSentinel = Job()
        val genScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        transport.seedPingJobForTest(pingSentinel)
        transport.seedAckWatchdogJobForTest(ackSentinel)
        transport.seedGenerationScopeForTest(genScope)

        val flushEntered = CompletableDeferred<Unit>()
        val flushRelease = CompletableDeferred<Unit>()
        transport.flushEnteredForTest = flushEntered
        transport.flushReleaseForTest = flushRelease

        var observedCe: CancellationException? = null
        val caller = launch {
            try {
                transport.disconnect()
            } catch (ce: CancellationException) {
                observedCe = ce
                throw ce
            }
        }
        // Wait for the flush body to actually enter.
        flushEntered.await()
        // Now genuinely cancel the caller — the flush is in flight.
        caller.cancel(CancellationException("test cancel mid-flush"))
        // Release the flush. The block observes parent cancellation
        // and propagates CE out of `withTimeoutOrNull`, which the
        // outer try/catch captures into `pendingCe`. Then the critical
        // teardown runs (NonCancellable) and the pendingCe is rethrown.
        flushRelease.complete(Unit)
        caller.join()

        // All four sentinels MUST be cancelled by the critical region,
        // regardless of the mid-flush cancellation.
        assertTrue(holdingJob.job.isCancelled, "reconnect job cancelled in critical region")
        assertTrue(pingSentinel.isCancelled, "pingJob cancelled in critical region")
        assertTrue(ackSentinel.isCancelled, "ackWatchdogJob cancelled in critical region")
        assertFalse(genScope.isActive, "per-generation scope cancelled in critical region")
        // CE propagated to the caller (not swallowed).
        assertNotNull(observedCe, "CancellationException MUST be rethrown to caller after critical region")

        holdingJob.releaseForTeardown()
    }

    @Test
    fun legacy_disconnect_caller_cancellation_pre_join_still_cancels_reconnect_AND_watchdogs() = runBlocking {
        // Fifth-round P1 fix: the critical teardown transaction (set flags,
        // publish Disconnected, optional flush, cancel reconnect job,
        // detach refs, queue cleanups, cancel ping/ACK/scope) MUST run to
        // completion even if the caller is cancelled mid-flight on the
        // legacy [disconnect] path.
        //
        // Honest scope: this test does NOT exercise the cancellation-
        // during-flush path specifically. Because no live WS session is
        // seeded, `flushPendingOutbox` returns instantly and the
        // `delay(80)` lands on the bounded `job.join()`, not inside the
        // flush. The test still pins the load-bearing invariant — the
        // critical `NonCancellable` block runs to completion BEFORE the
        // cancellable join, so all four lifecycle sentinels are
        // cancelled regardless of where the caller CE arrives. The
        // deterministic flush-during-cancellation anchor (with a
        // `flushEntered`/`flushRelease` test seam) is deferred to the
        // gate-integration commit alongside the full Test 20.
        //
        // Test setup: seed the holding reconnect job + a per-generation
        // scope + sentinel pingJob + sentinel ackWatchdogJob. Start
        // `disconnect()` in a launch; cancel the caller. After the launch
        // settles, assert ALL FOUR sentinels are cancelled.
        val transport = newTransport()
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)

        val pingSentinel = Job()
        val ackSentinel = Job()
        val genScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        transport.seedPingJobForTest(pingSentinel)
        transport.seedAckWatchdogJobForTest(ackSentinel)
        transport.seedGenerationScopeForTest(genScope)

        val caller = launch {
            try {
                transport.disconnect()
            } catch (_: CancellationException) {
                // Expected — disconnect() discards the join result but
                // the inner teardownAndJoin throws CE captured during
                // flush. Swallowing here lets us inspect post-state.
            }
        }
        // Give the launch time to enter teardownAndJoin and reach the
        // bounded flush (3 s) — that's the cancellable window.
        delay(80)
        caller.cancel(CancellationException("cancelled mid-flush"))
        caller.join()

        // CRITICAL: all four lifecycle sentinels must be cancelled even
        // though the caller was cancelled mid-flush.
        assertTrue(
            holdingJob.job.isCancelled,
            "reconnect job MUST be cancelled even when caller CE arrived during flush",
        )
        assertTrue(
            pingSentinel.isCancelled,
            "pingJob MUST be cancelled inside critical NonCancellable region",
        )
        assertTrue(
            ackSentinel.isCancelled,
            "ackWatchdogJob MUST be cancelled inside critical NonCancellable region",
        )
        assertFalse(
            genScope.isActive,
            "per-generation scope MUST be cancelled inside critical NonCancellable region " +
                "(it is a separate SupervisorJob — cancelling reconnect job does NOT propagate)",
        )

        holdingJob.releaseForTeardown()
    }

    @Test
    fun disconnectAndJoin_still_cancels_watchdogs_when_caller_is_cancelled_pre_join() = runBlocking {
        // Fifth-round P1 fix: even in the disconnectAndJoin path (no
        // flush), if the caller's CE arrives before the bounded join is
        // reached, the critical NonCancellable region must STILL cancel
        // ping/ACK/scope before propagating. The earlier shape had a
        // window where caller CE accumulated during the cleanup
        // reservation NonCancellable would fire on the next suspension
        // (the strict-bound `withTimeoutOrNull`) and skip the watchdog
        // cancels.
        val transport = newTransport()
        val holdingJob = StubbornNeverCompletingJob()
        holdingJob.awaitEntered()
        transport.seedReconnectJobForTest(holdingJob.job)

        val pingSentinel = Job()
        val ackSentinel = Job()
        val genScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        transport.seedPingJobForTest(pingSentinel)
        transport.seedAckWatchdogJobForTest(ackSentinel)
        transport.seedGenerationScopeForTest(genScope)

        val caller = launch {
            try {
                transport.disconnectAndJoin(timeoutMs = 60_000)
            } catch (_: CancellationException) {
                // Expected: caller CE propagates through the bounded join.
            }
        }
        delay(80)
        caller.cancel(CancellationException("cancelled pre-join"))
        caller.join()

        assertTrue(
            holdingJob.job.isCancelled,
            "reconnect job MUST be cancelled",
        )
        assertTrue(
            pingSentinel.isCancelled,
            "pingJob MUST be cancelled inside critical NonCancellable region",
        )
        assertTrue(
            ackSentinel.isCancelled,
            "ackWatchdogJob MUST be cancelled inside critical NonCancellable region",
        )
        assertFalse(
            genScope.isActive,
            "per-generation scope MUST be cancelled inside critical NonCancellable region",
        )

        holdingJob.releaseForTeardown()
    }
}

/**
 * A real launched [Job] whose body runs under [NonCancellable] and suspends
 * on a [CompletableDeferred]. Cancellation transitions it to Cancelling
 * (isCancelled=true, isActive=false) but `isCompleted` STAYS FALSE until
 * [releaseForTeardown] is called — matching production behaviour of a
 * `runReconnectLoop` coroutine that is mid-`finally`-block when cancelled
 * and cannot exit promptly.
 *
 * Required for the `disconnectAndJoin_preserves_reconnectJob_ref_on_timeout`
 * test: a bare `Job()` transitions directly to Completed on cancel because
 * it has no body, which would let the production code legitimately null
 * `reconnectJob` since the underlying coroutine is "done". This fixture
 * keeps the job in the Cancelling-but-not-Completed state so the production
 * code's `if (job.isCompleted) reconnectJob = null` correctly leaves the
 * reference intact.
 */
private class StubbornNeverCompletingJob {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val release = CompletableDeferred<Unit>()
    private val started = CompletableDeferred<Unit>()
    val job: Job = scope.launch {
        withContext(NonCancellable) {
            // Review amendment P2: signal `started` INSIDE the NonCancellable
            // block. Tests must await this signal before invoking the API
            // under test — otherwise a race where the scheduler hasn't yet
            // dispatched this body before cancel() arrives would let the job
            // complete as Cancelled (no body ran), defeating the
            // never-completing simulation.
            started.complete(Unit)
            release.await()
        }
    }

    /** Suspend until the body has entered the NonCancellable block. */
    suspend fun awaitEntered() {
        started.await()
    }

    suspend fun releaseForTeardown() {
        release.complete(Unit)
        runCatching { job.cancelAndJoin() }
        // Test lifecycle requirement (2026-06-22): also cancel the
        // fixture's own scope so it does not leak into the Gradle test
        // JVM across tests.
        runCatching { scope.cancel() }
    }
}
