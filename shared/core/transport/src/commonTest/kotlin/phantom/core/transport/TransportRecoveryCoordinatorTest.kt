// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioural contract of [TransportRecoveryCoordinator].
 *
 * Review round 8 (2026-09-13) is the reason this file exists. The
 * coordinator's rules had been pinned only by source-scanning tripwires,
 * because it lived inside `PhantomMessagingService` and constructing a
 * `Service` in a unit test is not practical. A tripwire can see that a
 * registration is written; it cannot see that the registration is
 * ATOMIC with respect to the work it admits. An external start that
 * registered only when the slot was free, and ran anyway when it was
 * not, therefore passed static review: the unregistered attempt was
 * invisible at clause (5), could win the startup CAS, and could then sit
 * waiting for the device unlock while the registered one finished.
 *
 * Every case here drives the real coordinator with virtual time and an
 * explicit barrier. Nothing is asserted about source text.
 */
class TransportRecoveryCoordinatorTest {

    private class FakeEnvironment : TransportRecoveryCoordinator.Environment {
        var shuttingDown: Boolean = false
        var torBlocks: Boolean = false
        var liveSession: Long? = null
        var restIsUsable: Boolean = false

        override suspend fun isShuttingDown(): Boolean = shuttingDown
        override suspend fun torObligationBlocks(): Boolean = torBlocks
        override suspend fun liveSessionEpoch(): Long? = liveSession
        override suspend fun restUsable(): Boolean = restIsUsable
    }

    /** Everything one case needs, built around one coordinator. */
    private class Rig(
        val scope: CoroutineScope,
        val env: FakeEnvironment,
        val log: MutableList<String>,
        val scheduler: ConnectRetryScheduler,
        val coordinator: TransportRecoveryCoordinator,
        /** Stamps of the attempts that actually RAN, in order. */
        val started: MutableList<Long>,
    ) {
        fun close() = scope.cancel()
    }

    private fun TestScope.rig(
        env: FakeEnvironment = FakeEnvironment(),
        attempt: suspend Rig.(isRetry: Boolean, rewalkReason: String?, stamp: Long) -> Unit,
    ): Rig {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val log = mutableListOf<String>()
        val started = mutableListOf<Long>()
        var generation = 0L
        val ownership = ConnectOwnership(
            nextToken = { ++generation },
            log = { line -> log += line },
        )
        val handoff = HandoffRecovery(
            ownership = ownership,
            scope = scope,
            startOneOrdinaryConnect = { false },
            log = { line -> log += line },
        )
        val scheduler = ConnectRetryScheduler(
            nowMs = { testScheduler.currentTime },
            jitterFactor = { 1.0 },
            log = { line -> log += line },
        )
        lateinit var rig: Rig
        val coordinator = TransportRecoveryCoordinator(
            scope = scope,
            nowMs = { testScheduler.currentTime },
            log = { line -> log += line },
            ownership = ownership,
            handoffRecovery = handoff,
            retryScheduler = scheduler,
            connectGeneration = { generation },
            environment = env,
            runStartAttempt = { isRetry, rewalkReason, stamp ->
                started += stamp
                rig.attempt(isRetry, rewalkReason, stamp)
            },
        )
        rig = Rig(scope, env, log, scheduler, coordinator, started)
        return rig
    }

    private fun MutableList<String>.hasLine(fragment: String): Boolean =
        any { fragment in it }

    private fun MutableList<String>.indexOfLine(fragment: String): Int =
        indexOfFirst { fragment in it }

    // ── Finding 2: the external-start race ───────────────────────────────

    @Test
    fun two_concurrent_external_starts_run_exactly_one_attempt() = runTest {
        val admitted = CompletableDeferred<Unit>()
        val rig = rig { _, _, _ -> admitted.await() }
        try {
            // Both arrive the way `onStartCommand` delivers them:
            // UNDISPATCHED, so the admission runs on the calling thread
            // before the launcher returns. This is the interleaving the
            // production path produces, not an artificial one.
            val first = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rig.coordinator.runExternalStart(isRetryAttempt = false, rewalkReason = null)
            }
            val second = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rig.coordinator.runExternalStart(isRetryAttempt = false, rewalkReason = null)
            }
            runCurrent()

            assertEquals(
                1, rig.started.size,
                "the second external start must not run an attempt of its own: an " +
                    "unregistered attempt is invisible to clause (5), can win the " +
                    "startup CAS, and can then wait for the unlock while the " +
                    "registered one finishes. Started stamps: ${rig.started}",
            )
            assertTrue(
                rig.log.hasLine("external_start_joined"),
                "and it must JOIN the attempt that owns the slot; log=${rig.log}",
            )
            assertTrue(first.isActive, "the first attempt is still inside its start")
            assertTrue(second.isActive, "the second waits for it rather than returning early")

            // The barrier lifts: both callers observe the same one start.
            admitted.complete(Unit)
            runCurrent()
            assertEquals(1, rig.started.size, "still exactly one attempt after both finish")
            assertTrue(first.isCompleted && second.isCompleted)
        } finally {
            rig.close()
        }
    }

    @Test
    fun an_external_start_is_registered_before_its_work_begins() = runTest {
        // The registration must be visible to the coordinator at the
        // moment the attempt body starts, not some dispatch later.
        val seen = mutableListOf<Boolean>()
        val hold = CompletableDeferred<Unit>()
        val rig = rig { _, _, _ ->
            seen += true
            hold.await()
        }
        try {
            rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rig.coordinator.runExternalStart(isRetryAttempt = false, rewalkReason = null)
            }
            // No runCurrent(): UNDISPATCHED means the admission already
            // happened on this thread.
            assertTrue(
                rig.log.hasLine("external_start_registered"),
                "the slot must be taken before onStartCommand returns; log=${rig.log}",
            )
            assertTrue(
                rig.coordinator.startJobPending.value,
                "and presentation must already see a start in flight (L1 state (e))",
            )
            runCurrent()
            assertEquals(listOf(true), seen)
            hold.complete(Unit)
            runCurrent()
        } finally {
            rig.close()
        }
    }

    // ── The in-process granted start (review round 8, second pass) ───────

    @Test
    fun a_granted_in_process_start_is_pending_from_the_moment_it_is_granted() = runTest {
        // The defect this pins. `startGrantedAttemptLocked` publishes the
        // handle BEFORE it calls `start()`, on purpose, so a body that
        // finished first could not report against a job that had not been
        // recorded. A `CoroutineStart.LAZY` job is `New` at that moment --
        // not active, not completed -- and the pending flag was computed
        // from `isActive`, so it stayed false for the whole attempt. A
        // granted retry waiting behind the lock screen was invisible to
        // presentation, and the banner could read `Offline` while recovery
        // was under way.
        //
        // The external-start cases cannot see this: that path registers an
        // already-running job.
        val unlocked = CompletableDeferred<Unit>()
        val claims = CompletableDeferred<Unit>()
        val rig = rig { _, _, stamp ->
            // Still behind the unlock gate: no prerequisites reported yet.
            unlocked.await()
            coordinator.noteStartPrerequisitesReady(stamp)
            claims.await()
            coordinator.noteStartJobClaimedOwnership(stamp)
            env.liveSession = 5L
        }
        try {
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("vacuum") }

            // No scheduler step yet. The grant was made and the job was
            // published under the coordinator's own lock, so presentation
            // must already say a start is in flight.
            assertTrue(
                rig.coordinator.startJobPending.value,
                "a granted attempt is L1 state (e) from the instant it is granted, not " +
                    "from the instant its body happens to be dispatched",
            )

            runCurrent()
            assertEquals(1, rig.started.size, "the grant started exactly one attempt")
            assertTrue(
                rig.coordinator.startJobPending.value,
                "and it stays pending once the body is running",
            )

            // However long the phone stays locked, presentation must keep
            // saying a start is in flight.
            repeat(3) {
                advanceTimeBy(TransportRecoveryCoordinator.START_BUDGET_MS)
                runCurrent()
                assertTrue(
                    rig.coordinator.startJobPending.value,
                    "waiting for the unlock is a live attempt; `Offline` here is the " +
                        "defect this whole stage exists to remove",
                )
            }

            // The device is unlocked: still pending, because the lease has
            // not been taken yet.
            unlocked.complete(Unit)
            runCurrent()
            assertTrue(rig.coordinator.startJobPending.value)

            // The lease is taken: the grant became a walk, and a walk is
            // not a pending start.
            claims.complete(Unit)
            runCurrent()
            assertFalse(
                rig.coordinator.startJobPending.value,
                "a start that claimed the lease is a walk, not a start in flight",
            )
        } finally {
            unlocked.complete(Unit)
            claims.complete(Unit)
            rig.close()
        }
    }

    // ── Waiting for the device unlock ────────────────────────────────────

    @Test
    fun a_start_waiting_for_the_unlock_is_live_and_refuses_a_second_start() = runTest {
        // The attempt never reports its prerequisites: it is suspended in
        // the unlock gate, which is L1 state (e) and NOT a vacuum.
        val unlocked = CompletableDeferred<Unit>()
        val rig = rig { _, _, _ -> unlocked.await() }
        try {
            rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rig.coordinator.runExternalStart(isRetryAttempt = false, rewalkReason = null)
            }
            runCurrent()
            assertEquals(1, rig.started.size)
            assertTrue(
                rig.coordinator.startJobPending.value,
                "a start that has not claimed the lease is pending, so the banner is " +
                    "never Offline while the phone is locked",
            )

            // However long the screen stays locked, and however many
            // triggers arrive, no second attempt may be started and the
            // live one may not be cancelled: its budget has not begun.
            repeat(5) {
                advanceTimeBy(TransportRecoveryCoordinator.START_BUDGET_MS * 3)
                withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("alarm_$it") }
                runCurrent()
            }
            assertEquals(
                1, rig.started.size,
                "the unlock wait is a live attempt; a second start would be refused by " +
                    "the startup CAS and would drop whatever grant it carried",
            )
            assertTrue(
                rig.log.hasLine("start_job_waiting_for_prerequisites"),
                "and the coordinator must say so rather than treating it as a vacuum; " +
                    "log=${rig.log}",
            )

            unlocked.complete(Unit)
            runCurrent()
        } finally {
            rig.close()
        }
    }

    // ── The overdue start: cancel, join, then restore ────────────────────

    @Test
    fun an_overdue_start_is_cancelled_and_joined_before_the_grant_is_restored() = runTest {
        val hang = CompletableDeferred<Unit>()
        val rig = rig { _, _, stamp ->
            coordinator.noteStartPrerequisitesReady(stamp)
            try {
                hang.await()
            } finally {
                log += "TEST attempt_unwound stamp=$stamp"
            }
        }
        try {
            // A vacuum grants and starts an attempt.
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("vacuum") }
            runCurrent()
            assertEquals(1, rig.started.size, "the vacuum must have started an attempt")
            assertTrue(rig.log.hasLine("attempt_started_in_process"))

            // It sits past its budget with its prerequisites long ready.
            advanceTimeBy(TransportRecoveryCoordinator.START_BUDGET_MS + 1_000)
            // The socket came back meanwhile, so the decision that follows
            // the restore stops at clause (6). This keeps the assertions
            // about the cancel and the restore rather than about whatever
            // the coordinator does next.
            rig.env.liveSession = 7L

            // The hard timeout IS the assertion the acceptance asked for:
            // the retired job's completion handler takes the coordinator's
            // own mutex, so a join performed under that mutex deadlocks and
            // this call never returns.
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("alarm") }
            runCurrent()

            val unwound = rig.log.indexOfLine("TEST attempt_unwound")
            val restored = rig.log.indexOfLine("start_job_overdue_restored")
            assertTrue(unwound >= 0, "the overdue attempt must actually be cancelled; log=${rig.log}")
            assertTrue(restored >= 0, "and the restore decision must be reached; log=${rig.log}")
            assertTrue(
                unwound < restored,
                "the join must COMPLETE before anything is restored: restoring over a " +
                    "job that is still unwinding is how a grant lands under a walk that " +
                    "has just started. log=${rig.log}",
            )

            val snapshot = rig.scheduler.snapshot()
            assertNotNull(
                snapshot.dueAtMs,
                "the grant must end up back in the scheduler, not nowhere",
            )
        } finally {
            hang.complete(Unit)
            rig.close()
        }
    }

    @Test
    fun a_cancelled_caller_is_not_swallowed_and_restores_nothing() = runTest {
        val hang = CompletableDeferred<Unit>()
        val finallyGate = CompletableDeferred<Unit>()
        val rig = rig { _, _, stamp ->
            coordinator.noteStartPrerequisitesReady(stamp)
            try {
                hang.await()
            } finally {
                // The attempt refuses to finish unwinding, so the
                // coordinator's `cancelAndJoin` is still waiting when its
                // caller is cancelled.
                withContext(NonCancellable) { finallyGate.await() }
            }
        }
        try {
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("vacuum") }
            runCurrent()
            assertEquals(1, rig.started.size)
            advanceTimeBy(TransportRecoveryCoordinator.START_BUDGET_MS + 1_000)

            var caught: Throwable? = null
            val caller = rig.scope.launch {
                try {
                    rig.coordinator.ensureRecoveryProgress("alarm")
                } catch (t: Throwable) {
                    caught = t
                }
            }
            runCurrent()
            caller.cancel()
            runCurrent()

            assertTrue(
                caught is CancellationException,
                "the caller's cancellation must reach it. Swallowing it here means the " +
                    "coordinator carries on and restores a grant on the strength of a " +
                    "join that never happened. Caught: $caught",
            )
            assertFalse(
                rig.log.hasLine("start_job_overdue_restored=true"),
                "and nothing may be restored on an unconfirmed join; log=${rig.log}",
            )
        } finally {
            finallyGate.complete(Unit)
            hang.complete(Unit)
            rig.close()
        }
    }

    // ── The grant's three possible endings ───────────────────────────────

    @Test
    fun a_restored_due_grant_is_re_entered_by_a_bounded_local_wake_up() = runTest {
        // The attempt ends at once without claiming, so its grant goes
        // back due-now. The coordinator then defers for spacing -- and the
        // point of this case is that the deferral ENDS on its own.
        val rig = rig { _, _, _ -> /* ends immediately, claims nothing */ }
        try {
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("vacuum") }
            runCurrent()

            assertEquals(1, rig.started.size, "one attempt so far")
            assertTrue(
                rig.log.hasLine("grant_restored"),
                "an attempt that never claimed must put its grant back; log=${rig.log}",
            )
            val deferral = rig.log.firstOrNull { "reason=start_spacing" in it }
            assertNotNull(
                deferral,
                "and the immediate re-entry must be deferred, or the pair hot-loops; " +
                    "log=${rig.log}",
            )
            assertTrue(
                "wakeInMs=" in deferral,
                "the deferral must name the wake-up it armed; line=$deferral",
            )

            // No external trigger from here. Only the local timer can make
            // this move, and it must. Bounded on purpose: a permanent
            // vacuum re-arms a wake every budget, which is the intended
            // cadence, so "advance until idle" would never return.
            advanceTimeBy(TransportRecoveryCoordinator.START_BUDGET_MS + 1_000)
            runCurrent()
            assertTrue(
                rig.started.size >= 2,
                "the spacing wake-up must re-enter the coordinator on its own: without " +
                    "it a grant restored due-now waits for the alarm heartbeat, which " +
                    "Doze can defer without bound. Started: ${rig.started}",
            )
            assertTrue(rig.log.hasLine("start_spacing_elapsed"), "log=${rig.log}")
        } finally {
            rig.close()
        }
    }

    @Test
    fun a_grant_that_became_a_walk_is_never_restored() = runTest {
        val rig = rig { _, _, stamp ->
            coordinator.noteStartPrerequisitesReady(stamp)
            // The attempt took the lease: its grant is now a walk.
            coordinator.noteStartJobClaimedOwnership(stamp)
            env.liveSession = 3L
        }
        try {
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("vacuum") }
            runCurrent()

            assertEquals(1, rig.started.size)
            assertTrue(
                rig.log.hasLine("grant_consumed_by_claim"),
                "the claim must be recorded; log=${rig.log}",
            )
            assertFalse(
                rig.log.hasLine("claim_restored"),
                "restoring after a claim puts a second attempt on top of a live walk; " +
                    "log=${rig.log}",
            )
            assertFalse(
                rig.coordinator.startJobPending.value,
                "a start that claimed the lease is no longer pending: it is a walk",
            )
            val snapshot = rig.scheduler.snapshot()
            assertEquals(
                null, snapshot.dueAtMs,
                "and nothing may be left scheduled behind it",
            )
        } finally {
            rig.close()
        }
    }

    // ── The decision's guards ────────────────────────────────────────────

    @Test
    fun a_shutdown_starts_nothing() = runTest {
        val env = FakeEnvironment().apply { shuttingDown = true }
        val rig = rig(env) { _, _, _ -> }
        try {
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("alarm") }
            runCurrent()
            assertEquals(0, rig.started.size)
            assertTrue(rig.log.hasLine("recovery_blocked"), "log=${rig.log}")
            assertTrue(rig.log.hasLine("reason=shutdown"), "log=${rig.log}")
        } finally {
            rig.close()
        }
    }

    @Test
    fun an_unsettled_tor_obligation_starts_nothing() = runTest {
        val env = FakeEnvironment().apply { torBlocks = true }
        val rig = rig(env) { _, _, _ -> }
        try {
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("alarm") }
            runCurrent()
            assertEquals(0, rig.started.size)
            assertTrue(rig.log.hasLine("reason=tor_unsettled"), "log=${rig.log}")
        } finally {
            rig.close()
        }
    }

    @Test
    fun a_live_session_means_the_transport_owns_liveness() = runTest {
        val env = FakeEnvironment().apply { liveSession = 9L }
        val rig = rig(env) { _, _, _ -> }
        try {
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("alarm") }
            runCurrent()
            assertEquals(0, rig.started.size)
            assertTrue(rig.log.hasLine("live_session_9"), "log=${rig.log}")
        } finally {
            rig.close()
        }
    }

    @Test
    fun a_working_rest_fallback_is_not_a_vacuum() = runTest {
        val env = FakeEnvironment().apply { restIsUsable = true }
        val rig = rig(env) { _, _, _ -> }
        try {
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("alarm") }
            runCurrent()
            assertEquals(
                0, rig.started.size,
                "REST is delivering, so nothing is owed: arming here would walk the " +
                    "chain against a path that is already working",
            )
            assertTrue(rig.log.hasLine("reason=rest_usable"), "log=${rig.log}")
        } finally {
            rig.close()
        }
    }

    @Test
    fun a_shutdown_stands_the_local_wake_up_down() = runTest {
        val rig = rig { _, _, _ -> /* ends immediately */ }
        try {
            withTimeout(5_000) { rig.coordinator.ensureRecoveryProgress("vacuum") }
            runCurrent()
            assertTrue(rig.log.hasLine("reason=start_spacing"), "log=${rig.log}")
            val before = rig.started.size

            rig.coordinator.shutdownNow()
            rig.env.shuttingDown = true
            advanceTimeBy(TransportRecoveryCoordinator.START_BUDGET_MS * 4)
            runCurrent()

            assertEquals(
                before, rig.started.size,
                "a cancelled wake-up must not start the service again after onDestroy",
            )
            assertFalse(
                rig.coordinator.startJobPending.value,
                "and presentation must not be left claiming a start is in flight",
            )
        } finally {
            rig.close()
        }
    }
}
