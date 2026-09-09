// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R-2 P2. The predecessor/successor handover, on two real threads, with the
 * interleaving deliberately arranged rather than hoped for.
 *
 * The hazard: a generation settles — which permits a successor — while it
 * still owes a release fact. If another thread creates the successor in that
 * window, the release fact recorded afterwards is published as the CURRENT
 * one and writes a stopped tor over a starting one.
 *
 * The rendezvous below opens that window as wide as it can be opened. The
 * `project` callback runs INSIDE the owner's monitor, so while it is running
 * no other thread can be anywhere else in the owner. It waits there until a
 * second thread has committed to entering `start()` and is BLOCKED on that
 * very monitor. Only then does it return, ending the critical section with a
 * successor already queued to take it.
 *
 * What that does and does not establish: it does not prove the absence of a
 * race — two threads and an intrinsic lock offer no fairness guarantee, so
 * which of them takes the monitor next is the JVM's choice. It does place
 * both threads at exactly the point where the defect would occur, on every
 * round, which is a great deal stronger than running the sequence quickly
 * many times and hoping.
 */
class GenerationHandoverInterleavingTest {

    @Test
    fun a_successor_created_at_the_handover_is_not_published_over() {
        repeat(ROUNDS) { round ->
            val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val committed = CountDownLatch(1)
            val published = SafeList<GenerationStatus>()
            var failing = true

            val owner = TorLifecycleOwner(
                handleFactory = {
                    if (failing) throw IllegalStateException("no host") else FakeHandle()
                },
                workers = workers,
                project = { status ->
                    published.add(status)
                    if (status.id == 1L && status.phase == Phase.Settled) {
                        // Inside the monitor. Do not leave until the successor
                        // thread is blocked on it.
                        check(committed.await(10, TimeUnit.SECONDS)) {
                            "the successor thread never committed (round $round)"
                        }
                    }
                },
            )

            try {
                runBlocking {
                    owner.start(listOf("bridge"))

                    failing = false
                    val successor = Thread {
                        committed.countDown()
                        runBlocking { owner.start(listOf("bridge")) }
                    }
                    successor.start()

                    // The stop settles generation 1; `project` parks inside the
                    // monitor until the successor is queued behind it.
                    owner.stop(TorBudget(10_000))
                    successor.join(TimeUnit.SECONDS.toMillis(10))
                    assertTrue(!successor.isAlive, "the successor never finished (round $round)")
                }

                val seen = published.toList()
                val successorAt = seen.indexOfFirst { it.id == 2L }
                assertTrue(successorAt >= 0, "the successor published nothing (round $round)")
                assertTrue(
                    seen.drop(successorAt).none { it.id != 2L },
                    "a superseded generation published over the live one in round " +
                        "$round: ${seen.map { it.id to it.phase }}",
                )
                assertEquals(2L, owner.status.value.id, "round $round")
            } finally {
                committed.countDown()
                workers.cancel()
            }
        }
    }

    private class FakeHandle : TorProcessHandle {
        override suspend fun launch(): LaunchAttestation = LaunchAttestation.Launched
        override suspend fun configureBridges(bridges: List<String>?) = Unit
        override suspend fun enableNetwork(enabled: Boolean) = Unit
        override suspend fun terminate(): TeardownReport = TeardownReport(quiesceFailure = null)
        override suspend fun release(): ReleaseResult = ReleaseResult.Released
    }

    private companion object {
        const val ROUNDS = 50
    }
}
