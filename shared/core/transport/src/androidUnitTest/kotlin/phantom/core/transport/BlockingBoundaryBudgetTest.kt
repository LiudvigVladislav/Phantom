// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The budget in [TorLifecycleOwner.stop] against a teardown that really
 * blocks a thread, rather than one that suspends on virtual time.
 *
 * The whole design rests on bounding the WAIT and not the work: the real
 * `wrapper.stop()` sits in `Process.waitFor()`, and interrupting it is the
 * one path where the library discards its process reference while a daemon
 * may still live. So this runs on a real executor with a real latch, and
 * asserts that an expired budget answers an unconfirmed daemon while the
 * call carries on, and that the same attempt later comes back free — one library call in
 * total, never a second.
 */
class BlockingBoundaryBudgetTest {

    @Test
    fun an_expired_budget_leaves_a_blocked_teardown_running_and_it_confirms_later() {
        val wrapper = FakeTorWrapper()
        val gate = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val workers = CoroutineScope(dispatcher + SupervisorJob())
        try {
            wrapper.stopGate = gate
            val handle = AndroidTorProcessHandle(wrapper, dispatcher)
            val owner = TorLifecycleOwner({ handle }, workers)

            runBlocking {
                assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))

                val expiring = async(Dispatchers.Default) { owner.stop(TorBudget(300)).result }

                // The teardown is genuinely inside the blocking library call
                // before the budget is allowed to matter.
                assertTrue(
                    wrapper.stopEntered.await(10, TimeUnit.SECONDS),
                    "the teardown never reached the blocking library call",
                )
                assertEquals(notConfirmedYet(1L), expiring.await())
                assertEquals(1, wrapper.callCount("stop"))

                gate.countDown()

                // The same attempt, not a new one.
                assertEquals(
                    TorStopResult.Free(1L, quiesceFailure = null),
                    owner.stop(TorBudget(10_000)).result,
                )
                assertEquals(1, wrapper.callCount("stop"))
            }
        } finally {
            gate.countDown()
            workers.cancel()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                "the boundary thread did not finish within its budget"
            }
        }
    }

    @Test
    fun a_worker_that_goes_away_does_not_interrupt_the_blocking_teardown() {
        val wrapper = FakeTorWrapper()
        val gate = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val workers = CoroutineScope(dispatcher + SupervisorJob())
        try {
            wrapper.stopGate = gate
            val handle = AndroidTorProcessHandle(wrapper, dispatcher)
            val owner = TorLifecycleOwner({ handle }, workers)

            runBlocking {
                assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))
                val expiring = async(Dispatchers.Default) { owner.stop(TorBudget(200)).result }
                assertTrue(
                    wrapper.stopEntered.await(10, TimeUnit.SECONDS),
                    "the teardown never reached the blocking library call",
                )
                assertEquals(notConfirmedYet(1L), expiring.await())

                // The worker's scope goes away while the library call is
                // inside its blocking wait. Interrupting the call here is
                // exactly what must not happen: that is the path where the
                // library drops its process reference.
                workers.cancel()
                gate.countDown()

                assertTrue(
                    wrapper.stopReturned.await(10, TimeUnit.SECONDS),
                    "the blocking teardown never left the library call",
                )
                assertFalse(
                    wrapper.observedInterrupt,
                    "the blocking teardown was interrupted",
                )
            }
        } finally {
            gate.countDown()
            workers.cancel()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                "the boundary thread did not finish within its budget"
            }
        }
    }


    @Test
    fun a_parking_that_is_never_released_does_not_become_a_confirmed_stop() {
        val wrapper = FakeTorWrapper()
        val neverReleased = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val workers = CoroutineScope(dispatcher + SupervisorJob())
        try {
            wrapper.stopGate = neverReleased
            // Short on purpose: the guard exists to fail a forgetful fixture
            // quickly, not to make the suite wait out its default budget.
            wrapper.stopGateBudgetMs = 150
            val handle = AndroidTorProcessHandle(wrapper, dispatcher)
            val owner = TorLifecycleOwner({ handle }, workers)

            runBlocking {
                assertEquals(StartResult.Started(1L), owner.start(listOf("bridge")))

                // The guard budget expiring is a fixture fault surfacing as a
                // failed teardown. It must never read as a closed daemon.
                val outcome = assertUnknown(owner.stop(TorBudget(10_000)).result)

                // And it must be Unknown FOR THAT REASON: an Unknown from any
                // other stumble would satisfy the assertion above while
                // saying nothing about the guard.
                val reason = generateSequence(outcome.cause) { it.cause }
                    .mapNotNull { it.message }
                    .firstOrNull { "stop parking was not released" in it }
                assertNotNull(reason, "Unknown did not come from the fixture guard budget")
            }
        } finally {
            neverReleased.countDown()
            workers.cancel()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                "the boundary thread did not finish within its budget"
            }
        }
    }

}
