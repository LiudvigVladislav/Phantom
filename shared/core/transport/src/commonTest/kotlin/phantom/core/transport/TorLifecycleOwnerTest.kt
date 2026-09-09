// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Behavioural tests for [TorLifecycleOwner], the component itself — not a
 * copy of its logic. Only the library boundary is substituted.
 *
 * Every fixture that parks the worker does so on a gate created before its
 * `try`, installed inside it, and released in the matching `finally`. The
 * `try` opens before the gate is installed, so a failure while arranging
 * the fixture releases the gate just as readily as a failure in the
 * assertions.
 */
/**
 * Wait for a generation to settle with a budget of its own.
 *
 * Without this the tests that pin "a worker that ends leaves an outcome"
 * would hang until runTest's global watchdog, which reports a timeout for
 * the whole class rather than naming the property that broke.
 */
private suspend fun <T> settlingWithin(
    what: String,
    budgetMs: Long = 5_000,
    block: suspend () -> T,
): T = withTimeoutOrNull(budgetMs) { block() }
    ?: fail("$what did not settle within $budgetMs ms - the generation was left with no outcome")

class TorLifecycleOwnerTest {

    // ── success and succession ────────────────────────────────────────────

    @Test
    fun a_confirmed_stop_permits_the_next_generation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)

        assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))
        assertEquals(TorStopResult.Free(1L, quiesceFailure = null), owner.stop(TorBudget(1_000)).result)
        assertEquals(StartResult.Started(2L), owner.start(bridges = null))

        assertEquals(1, handle.steps.count { it == "terminate" })
    }

    @Test
    fun an_unsettled_generation_refuses_a_successor() = runTest {
        val handle = FakeTorProcessHandle()
        val launchGate = CompletableDeferred<Unit>()
        try {
            handle.launchGate = launchGate
            val owner = TorLifecycleOwner({ handle }, backgroundScope)
            val starting = backgroundScope.async { owner.start(listOf("bridge")) }
            runCurrent()

            // Still launching: not settled, and not finished starting
            // either, so it is neither a successor nor "already running".
            val refusal = owner.start(listOf("other"))

            assertEquals(StartResult.RefusedUnsettled(1L, outcome = null), refusal)
            assertEquals(1, handle.steps.count { it == "launch" })

            launchGate.complete(Unit)
            runCurrent()
            starting.await()
        } finally {
            launchGate.complete(Unit)
        }
    }

    // ── stop during start ─────────────────────────────────────────────────

    @Test
    fun a_stop_requested_during_a_launch_is_honoured_after_it() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        val launchGate = CompletableDeferred<Unit>()
        try {
            handle.launchGate = launchGate
            val starting = backgroundScope.async { owner.start(listOf("bridge")) }
            runCurrent()

            // The worker is parked inside launch(): the budget expires and
            // the obligation survives it.
            assertEquals(notConfirmedYet(1L), owner.stop(TorBudget(5)).result)
            assertEquals(0, handle.steps.count { it == "terminate" })

            launchGate.complete(Unit)
            runCurrent()

            assertEquals(StartResult.Started(1L), starting.await())
            // The other side of the decision: the launch was claimed
            // before the stop registered, so it was issued and the stop
            // is honoured after it rather than preventing it.
            assertEquals(1, handle.steps.count { it == "launch" })
            assertEquals(TorStopResult.Free(1L, quiesceFailure = null), owner.stop(TorBudget(1_000)).result)
            assertEquals(1, handle.steps.count { it == "terminate" })
        } finally {
            launchGate.complete(Unit)
        }
    }

    @Test
    fun a_stop_requested_during_a_failing_launch_keeps_the_obligation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        val launchGate = CompletableDeferred<Unit>()
        try {
            handle.launchGate = launchGate
            handle.launchResult =
                LaunchAttestation.FailedResourceUnknown(IllegalStateException("interrupted"))
            val starting = backgroundScope.async { owner.start(listOf("bridge")) }
            runCurrent()

            assertEquals(notConfirmedYet(1L), owner.stop(TorBudget(5)).result)

            launchGate.complete(Unit)
            runCurrent()

            assertIs<StartResult.LaunchFailed>(starting.await())
            val outcome = owner.stop(TorBudget(1_000)).result
            assertUnknown(outcome)
            assertEquals(1L, outcome.generation)
            assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))
        } finally {
            launchGate.complete(Unit)
        }
    }

    // ── configuration failures after a launch that succeeded ──────────────

    @Test
    fun a_bridge_configuration_failure_after_launch_keeps_the_obligation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        handle.configureFailure = IllegalStateException("bridges refused")

        val started = owner.start(listOf("bridge"))

        assertIs<StartResult.ConfigurationFailed>(started)
        assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))
        assertEquals(TorStopResult.Free(1L, quiesceFailure = null), owner.stop(TorBudget(1_000)).result)
        assertEquals(1, handle.steps.count { it == "terminate" })

        handle.configureFailure = null
        assertEquals(StartResult.Started(2L), owner.start(bridges = null))
    }

    @Test
    fun an_enable_network_failure_after_launch_keeps_the_obligation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        handle.enableNetworkFailure = IllegalStateException("network refused")

        assertIs<StartResult.ConfigurationFailed>(owner.start(listOf("bridge")))
        assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))
        assertEquals(TorStopResult.Free(1L, quiesceFailure = null), owner.stop(TorBudget(1_000)).result)
        assertEquals(1, handle.steps.count { it == "terminate" })
    }

    // ── pending, and its late success ─────────────────────────────────────

    @Test
    fun a_pending_stop_that_completes_later_becomes_confirmed() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        val terminateGate = CompletableDeferred<Unit>()
        try {
            handle.terminateGate = terminateGate
            owner.start(listOf("bridge"))

            assertEquals(notConfirmedYet(1L), owner.stop(TorBudget(5)).result)
            assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))

            terminateGate.complete(Unit)
            runCurrent()

            assertEquals(TorStopResult.Free(1L, quiesceFailure = null), owner.stop(TorBudget(1_000)).result)
            assertEquals(StartResult.Started(2L), owner.start(bridges = null))
        } finally {
            terminateGate.complete(Unit)
        }
    }

    // ── unknown ───────────────────────────────────────────────────────────

    @Test
    fun a_throwing_terminate_is_unknown_and_forbids_the_next_generation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        handle.terminateFailure = IllegalStateException("stop refused")
        owner.start(listOf("bridge"))

        val outcome = owner.stop(TorBudget(1_000)).result

        assertUnknown(outcome)
        val refusal = owner.start(bridges = null)
        assertIs<StartResult.RefusedUnsettled>(refusal)
        assertIs<StopOutcome.Unknown>(refusal.outcome)
    }

    @Test
    fun an_indeterminate_launch_is_unknown_not_nothing_to_stop() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        handle.launchResult =
            LaunchAttestation.FailedResourceUnknown(IllegalStateException("interrupted"))

        assertIs<StartResult.LaunchFailed>(owner.start(listOf("bridge")))

        val outcome = owner.stop(TorBudget(1_000)).result
        assertUnknown(outcome)
        assertFalse(outcome.isFree)
        assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))
    }

    @Test
    fun a_reaped_launch_failure_permits_the_next_generation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        handle.launchResult =
            LaunchAttestation.FailedResourceReaped(IllegalStateException("io"))

        val started = owner.start(listOf("bridge"))
        assertIs<StartResult.LaunchFailed>(started)
        // The public result below says only that nothing is left; that the
        // library ATTESTED it reaped the daemon is pinned here.
        assertTrue(started.resourceReaped)

        assertEquals(TorStopResult.Free(1L, quiesceFailure = null), owner.stop(TorBudget(1_000)).result)
        handle.launchResult = LaunchAttestation.Launched
        assertEquals(StartResult.Started(2L), owner.start(bridges = null))
    }

    // ── one attempt, and a caller that leaves ─────────────────────────────

    @Test
    fun repeated_stops_share_one_attempt() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        val terminateGate = CompletableDeferred<Unit>()
        try {
            handle.terminateGate = terminateGate
            owner.start(listOf("bridge"))

            val first = backgroundScope.async { owner.stop(TorBudget(60_000)).result }
            val second = backgroundScope.async { owner.stop(TorBudget(60_000)).result }
            runCurrent()
            terminateGate.complete(Unit)
            runCurrent()

            assertEquals(TorStopResult.Free(1L, quiesceFailure = null), first.await())
            assertEquals(TorStopResult.Free(1L, quiesceFailure = null), second.await())
            assertEquals(1, handle.steps.count { it == "terminate" })
        } finally {
            terminateGate.complete(Unit)
        }
    }

    @Test
    fun cancelling_the_caller_does_not_lose_the_obligation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        val terminateGate = CompletableDeferred<Unit>()
        try {
            handle.terminateGate = terminateGate
            owner.start(listOf("bridge"))

            val waiter = backgroundScope.async { owner.stop(TorBudget(60_000)).result }
            runCurrent()
            waiter.cancel()
            runCurrent()

            terminateGate.complete(Unit)
            runCurrent()

            assertEquals(TorStopResult.Free(1L, quiesceFailure = null), owner.stop(TorBudget(1_000)).result)
            assertEquals(1, handle.steps.count { it == "terminate" })
        } finally {
            terminateGate.complete(Unit)
        }
    }

    // ── a boundary that throws cancellation, and a worker that is gone ────

    @Test
    fun cancellation_from_configure_bridges_still_settles_the_generation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        handle.configureFailure = CancellationException("boundary interrupted")

        assertIs<StartResult.Abandoned>(
            settlingWithin("start after a cancelled configureBridges") { owner.start(listOf("bridge")) },
        )

        val outcome = owner.stop(TorBudget(1_000)).result
        assertUnknown(outcome)
        assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))
    }

    @Test
    fun cancellation_from_enable_network_still_settles_the_generation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        handle.enableNetworkFailure = CancellationException("boundary interrupted")

        assertIs<StartResult.Abandoned>(
            settlingWithin("start after a cancelled enableNetwork") { owner.start(listOf("bridge")) },
        )

        assertUnknown(owner.stop(TorBudget(1_000)).result)
        assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))
    }

    @Test
    fun cancellation_from_terminate_is_not_a_confirmation() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        owner.start(listOf("bridge"))
        handle.terminateFailure = CancellationException("teardown interrupted")

        val outcome = owner.stop(TorBudget(1_000)).result

        assertUnknown(outcome)
        assertEquals(1L, outcome.generation)
        assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))
    }

    @Test
    fun a_generation_whose_scope_is_already_cancelled_still_settles() = runTest {
        val handle = FakeTorProcessHandle()
        // On the test scheduler, so the settlement is virtual time rather
        // than a real thread the budget below cannot see.
        val dead = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        dead.cancel()
        val owner = TorLifecycleOwner({ handle }, dead)

        // The worker body never runs at all, so nothing inside it could
        // have recorded an outcome.
        assertIs<StartResult.Abandoned>(
            settlingWithin("start on an already cancelled scope") { owner.start(listOf("bridge")) },
        )

        assertUnknown(owner.stop(TorBudget(1_000)).result)
        assertTrue(handle.steps.none { it == "launch" })
        assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))
    }

    @Test
    fun a_worker_cancelled_before_its_body_runs_still_settles() = runTest {
        val handle = FakeTorProcessHandle()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val owner = TorLifecycleOwner({ handle }, scope)

        val starting = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            owner.start(listOf("bridge"))
        }
        // The worker job exists and is queued; cancelling now means its
        // body is never entered.
        scope.cancel()
        runCurrent()

        assertIs<StartResult.Abandoned>(
            settlingWithin("start whose worker was cancelled before its body ran") { starting.await() },
        )
        assertUnknown(owner.stop(TorBudget(1_000)).result)
        assertTrue(handle.steps.none { it == "launch" })
    }

    // ── a registered stop, before its signal exists ───────────────────────

    @Test
    fun a_registered_stop_is_honoured_before_its_signal_is_delivered() = runTest {
        val handle = FakeTorProcessHandle()
        val seam = CompletableDeferred<Unit>()
        try {
            // The stop parks between registering itself and delivering its
            // signal, so the worker reaches the launch decision while the
            // registration exists and the signal does not.
            val owner = TorLifecycleOwner(
                { handle },
                backgroundScope,
                afterStopRegistered = { seam.await() },
            )
            val starting = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                owner.start(listOf("bridge"))
            }
            val stopping = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                owner.stop(TorBudget(60_000)).result
            }
            runCurrent()

            assertEquals(StartResult.Skipped(1L), starting.await())
            assertTrue(handle.steps.none { it == "launch" })

            seam.complete(Unit)
            runCurrent()

            assertEquals(TorStopResult.Free(1L, quiesceFailure = null), stopping.await())
            assertTrue(handle.steps.none { it == "terminate" })
        } finally {
            seam.complete(Unit)
        }
    }

    @Test
    fun a_stop_registered_after_the_launch_is_claimed_runs_one_teardown() = runTest {
        val handle = FakeTorProcessHandle()
        val seam = CompletableDeferred<Unit>()
        try {
            val owner = TorLifecycleOwner(
                { handle },
                backgroundScope,
                afterStopRegistered = { seam.await() },
            )
            // The reverse order: the launch is claimed and issued first, so
            // the stop cannot prevent it and is honoured after it instead.
            assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))
            assertEquals(1, handle.steps.count { it == "launch" })

            val stopping = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                owner.stop(TorBudget(60_000)).result
            }
            runCurrent()
            seam.complete(Unit)
            runCurrent()

            assertEquals(TorStopResult.Free(1L, quiesceFailure = null), stopping.await())
            assertEquals(1, handle.steps.count { it == "terminate" })
        } finally {
            seam.complete(Unit)
        }
    }

    @Test
    fun a_caller_cancelled_inside_the_seam_still_delivers_the_signal() = runTest {
        val handle = FakeTorProcessHandle()
        val seam = CompletableDeferred<Unit>()
        try {
            val owner = TorLifecycleOwner(
                { handle },
                backgroundScope,
                afterStopRegistered = { seam.await() },
            )
            assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))

            val stopping = backgroundScope.async { owner.stop(TorBudget(60_000)).result }
            runCurrent()
            assertEquals(0, handle.steps.count { it == "terminate" })

            stopping.cancel()
            runCurrent()

            // No second stop() and no nudge: a registered stop owes its
            // signal, so the worker gets it from the cancelled caller's way
            // out. Asking again here would perform the teardown itself and
            // hide exactly the defect this pins.
            assertEquals(1, handle.steps.count { it == "terminate" })
            assertFailsWith<CancellationException> { stopping.await() }
        } finally {
            seam.complete(Unit)
        }
    }

    @Test
    fun a_seam_that_throws_still_delivers_the_signal() = runTest {
        val handle = FakeTorProcessHandle()
        val refusal = IllegalStateException("seam refused")
        val owner = TorLifecycleOwner(
            { handle },
            backgroundScope,
            afterStopRegistered = { throw refusal },
        )
        assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))

        val thrown = assertFailsWith<IllegalStateException> { owner.stop(TorBudget(60_000)).result }
        assertSame(refusal, thrown)

        runCurrent()
        assertEquals(1, handle.steps.count { it == "terminate" })
    }

    // ── the phase never walks backwards ───────────────────────────────────

    @Test
    fun a_stop_registered_during_the_launch_keeps_the_phase_from_returning_to_running() = runTest {
        val handle = FakeTorProcessHandle()
        val launchGate = CompletableDeferred<Unit>()
        val configureGate = CompletableDeferred<Unit>()
        try {
            handle.launchGate = launchGate
            handle.configureGate = configureGate
            val owner = TorLifecycleOwner({ handle }, backgroundScope)
            val starting = backgroundScope.async { owner.start(listOf("bridge")) }
            runCurrent()

            // Registered while the launch is still in flight: this closes
            // the window, and the launch returning must not reopen it.
            assertEquals(notConfirmedYet(1L), owner.stop(TorBudget(5)).result)

            launchGate.complete(Unit)
            runCurrent()

            // Observed here on purpose: the worker is parked in
            // configuration, which is exactly the moment a phase that walked
            // backwards would still be showing Running.
            var seen: Phase? = null
            owner.onGeneration(1L) { seen = it }
            assertEquals(Phase.Stopping, seen, "the phase went back after the launch returned")

            configureGate.complete(Unit)
            runCurrent()
            starting.await()
        } finally {
            launchGate.complete(Unit)
            configureGate.complete(Unit)
        }
    }

    @Test
    fun a_start_during_a_parked_teardown_is_not_already_running() = runTest {
        val handle = FakeTorProcessHandle()
        val terminateGate = CompletableDeferred<Unit>()
        try {
            handle.terminateGate = terminateGate
            val owner = TorLifecycleOwner({ handle }, backgroundScope)
            owner.start(listOf("bridge"))
            assertEquals(notConfirmedYet(1L), owner.stop(TorBudget(5)).result)

            assertIs<StartResult.RefusedUnsettled>(owner.start(bridges = null))

            terminateGate.complete(Unit)
            runCurrent()
        } finally {
            terminateGate.complete(Unit)
        }
    }

    @Test
    fun a_second_start_of_a_running_generation_is_already_running() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)

        assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))
        assertEquals(StartResult.AlreadyRunning(1L), owner.start(listOf("bridge")))

        assertEquals(1, handle.steps.count { it == "launch" })
    }

    @Test
    fun a_generation_whose_worker_was_cancelled_is_not_already_running() = runTest {
        val handle = FakeTorProcessHandle()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val owner = TorLifecycleOwner({ handle }, scope)
        assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))

        // The worker goes away while it waits for a stop that never comes.
        scope.cancel()
        runCurrent()

        // No stop() in between: the generation settled Unknown on its own,
        // so "it was configured once" must not answer for "it is running".
        val refusal = owner.start(bridges = null)

        assertIs<StartResult.RefusedUnsettled>(refusal)
        assertIs<StopOutcome.Unknown>(refusal.outcome)
        assertEquals(1, handle.steps.count { it == "launch" })
    }

    @Test
    fun a_gate_addressed_by_a_closed_generation_does_nothing() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        owner.start(listOf("bridge"))
        owner.stop(TorBudget(1_000)).result
        owner.start(bridges = null)

        var stale = false
        var live = false
        owner.onGeneration(1L) { stale = true }
        owner.onGeneration(2L) { live = true }

        assertFalse(stale, "a result from a closed generation reached the live one")
        assertTrue(live, "the live generation's own result was refused")
    }

    // ── nothing to stop ───────────────────────────────────────────────────

    @Test
    fun stopping_before_any_generation_is_nothing_to_stop() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)

        assertEquals(TorStopResult.Free(0L, quiesceFailure = null), owner.stop(TorBudget(1_000)).result)
        assertTrue(handle.steps.none { it == "launch" })
        assertTrue(handle.steps.none { it == "terminate" })
    }

    @Test
    fun a_stop_arriving_before_the_launch_is_issued_prevents_it() = runTest {
        val handle = FakeTorProcessHandle()
        val owner = TorLifecycleOwner({ handle }, backgroundScope)

        // UNDISPATCHED runs start() up to its first suspension, so the
        // generation exists and its worker is queued but has not run.
        val starting = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            owner.start(listOf("bridge"))
        }
        val stopping = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            owner.stop(TorBudget(60_000)).result
        }
        runCurrent()

        assertEquals(StartResult.Skipped(1L), starting.await())
        assertEquals(TorStopResult.Free(1L, quiesceFailure = null), stopping.await())
        assertTrue(handle.steps.none { it == "launch" })
        assertEquals(StartResult.Started(2L), owner.start(bridges = null))
    }
}

/**
 * The library boundary, and nothing else, replaced. Gates park the worker
 * exactly where a blocking library call would.
 */
/**
 * R-1 P1. A host factory that failed part-way and could NOT free what it
 * had already made hands those resources back. There is no handle to
 * release and there is very much something held.
 *
 * Reading that as "no host was ever built" reports the teardown as Free,
 * and a manager acting on Free would call the release clean and let a
 * privacy switch complete -- while this very owner still refuses the
 * successor. Two components would be answering the same question
 * differently, which is the whole class of defect this owner exists to
 * remove.
 */
class RetainedHostReleaseTest {

    @Test
    fun a_retained_host_is_a_failed_release_and_never_a_free_one() = runTest {
        val retention = IllegalStateException("executor would not shut down")
        val owner = TorLifecycleOwner(
            { throw HostResourcesRetainedException(retained = Any(), cause = retention) },
            backgroundScope,
        )

        val started = owner.start(listOf("bridge"))
        assertIs<StartResult.LaunchFailed>(started)

        val result = owner.stop(TorBudget(10_000)).result
        val failed = assertIs<TorStopResult.ReleaseFailed>(
            result,
            "retained resources were reported as $result",
        )
        assertFalse(result.isFree)
        assertTrue(
            carries(failed.releaseFailure, retention),
            "the retention reason was lost: ${failed.releaseFailure}",
        )

        // And the two answers agree: what the result says is not free, the
        // owner also refuses to start a successor over.
        assertIs<StartResult.RefusedHostNotReleased>(owner.start(listOf("bridge")))
    }

    /**
     * The positive control on the same seam: a factory that failed and left
     * NOTHING behind is the other case, and it must still read as free.
     * Without this, refusing every failed factory would pass the test above.
     */
    @Test
    fun a_factory_that_left_nothing_behind_is_still_free() = runTest {
        val owner = TorLifecycleOwner(
            { throw IllegalStateException("failed before creating anything") },
            backgroundScope,
        )

        assertIs<StartResult.LaunchFailed>(owner.start(listOf("bridge")))

        assertEquals(
            TorStopResult.Free(1L, quiesceFailure = null),
            owner.stop(TorBudget(10_000)).result,
        )
    }
}

/**
 * R-1 P1. A predecessor must have finished speaking before a successor is
 * allowed to exist.
 *
 * The hazard is a generation that settles -- which permits a successor --
 * while it still owes a release fact, because the fact it records later
 * would be published as the CURRENT one and write a stopped tor over a
 * starting one. The owner closes that by recording both facts in a single
 * critical section whenever the second needs no work, so there is no
 * moment at which the first is visible without the second.
 *
 * That is what is checked here, because it is what makes the interleaving
 * impossible rather than merely unlikely: at the instant a successor is
 * permitted, the predecessor is already terminal, and the status the owner
 * publishes from then on is the successor's.
 */
class GenerationHandoverPublicationTest {

    @Test
    fun a_successor_is_permitted_only_after_its_predecessor_has_finished() = runTest {
        val published = SafeList<GenerationStatus>()
        var failing = true
        val owner = TorLifecycleOwner(
            handleFactory = {
                if (failing) throw IllegalStateException("no host this time")
                FakeTorProcessHandle()
            },
            workers = backgroundScope,
            project = { published.add(it) },
        )

        assertIs<StartResult.LaunchFailed>(owner.start(listOf("bridge")))
        val first = owner.stop(TorBudget(10_000)).result

        // Terminal, not `Releasing`: nothing about generation 1 is still
        // outstanding at the moment generation 2 becomes possible.
        assertTrue(first.isTerminal, "generation 1 still owed something: $first")
        assertTrue(first.isFree)

        failing = false
        assertEquals(StartResult.Started(2L), owner.start(listOf("bridge")))

        // Everything the owner has published since belongs to generation 2.
        val afterHandover = published.toList().dropWhile { it.id != 2L }
        assertTrue(afterHandover.isNotEmpty(), "the successor published nothing")
        assertTrue(
            afterHandover.none { it.id != 2L },
            "a superseded generation published over the live one: " +
                "${published.toList().map { it.id to it.phase }}",
        )
        assertEquals(2L, owner.status.value.id)
    }

    /**
     * R-1. Both halves of a teardown can fail at once, and neither may be
     * dropped in favour of the other: a network that would not come down
     * before the stop and a host that would not let go afterwards are
     * independent facts about the same attempt.
     */
    @Test
    fun a_quiesce_failure_and_a_release_failure_are_both_kept() = runTest {
        val quiesce = IllegalStateException("network would not come down")
        val release = IllegalStateException("executor would not terminate")
        val handle = FakeTorProcessHandle().apply {
            quiesceFailure = quiesce
            releaseResult = ReleaseResult.NotReleased(release)
        }
        val owner = TorLifecycleOwner({ handle }, backgroundScope)
        assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))

        val failed = assertIs<TorStopResult.ReleaseFailed>(
            owner.stop(TorBudget(10_000)).result,
        )

        assertTrue(carries(failed.releaseFailure, release), "release cause lost")
        assertTrue(carries(failed.quiesceFailure, quiesce), "quiesce cause lost")
    }
}

private class FakeTorProcessHandle : TorProcessHandle {
    val steps = SafeList<String>()

    var launchGate: CompletableDeferred<Unit>? = null
    var launchResult: LaunchAttestation = LaunchAttestation.Launched
    var configureGate: CompletableDeferred<Unit>? = null
    var configureFailure: Throwable? = null
    var enableNetworkFailure: Throwable? = null
    var terminateGate: CompletableDeferred<Unit>? = null
    var terminateFailure: Throwable? = null
    var quiesceFailure: Throwable? = null
    var releaseResult: ReleaseResult = ReleaseResult.Released

    override suspend fun launch(): LaunchAttestation {
        steps.add("launch")
        launchGate?.await()
        return launchResult
    }

    override suspend fun configureBridges(bridges: List<String>?) {
        steps.add("configureBridges")
        configureGate?.await()
        configureFailure?.let { throw it }
    }

    override suspend fun enableNetwork(enabled: Boolean) {
        steps.add("enableNetwork:$enabled")
        enableNetworkFailure?.let { throw it }
    }

    override suspend fun terminate(): TeardownReport {
        steps.add("terminate")
        terminateGate?.await()
        terminateFailure?.let { throw it }
        return TeardownReport(quiesceFailure = quiesceFailure)
    }

    override suspend fun release(): ReleaseResult {
        steps.add("release")
        return releaseResult
    }
}

/** Whether [expected] survived anywhere in the cause chain of [thrown]. */
private fun carries(thrown: Throwable?, expected: Throwable): Boolean =
    generateSequence(thrown) { it.cause }.any {
        it === expected || (it::class == expected::class && it.message == expected.message)
    }
