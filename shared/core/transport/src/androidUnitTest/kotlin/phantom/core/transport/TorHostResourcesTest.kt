// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The host's resource paths against real executors.
 *
 * [TorServiceAndroidTest] substitutes the host wholesale, so nothing there
 * exercises an actual shutdown, an actual failure to terminate, or the
 * cleanup after a part-way construction. These do.
 *
 * Every fixture releases its latch in a `finally` that opens before the
 * latch is handed to a thread, and every executor is left terminated —
 * including when an assertion fails.
 */
class TorHostResourcesTest {

    @Test
    fun idle_executors_are_released() {
        val executors = listOf(single(), single())
        try {
            val result = runBlocking {
                TorHostResources.release(executors, budgetMs = 5_000, dispatcher = Dispatchers.IO)
            }

            assertEquals(ReleaseResult.Released, result)
            assertTrue(executors.all { it.isTerminated })
        } finally {
            executors.forEach { it.shutdownNow() }
        }
    }

    @Test
    fun an_executor_that_will_not_finish_is_reported_and_not_interrupted() {
        val executor = single()
        val hold = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            executor.execute {
                entered.countDown()
                try {
                    hold.await(30, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted.set(true)
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the task never started")

            val result = runBlocking {
                TorHostResources.release(listOf(executor), budgetMs = 200, dispatcher = Dispatchers.IO)
            }

            assertIs<ReleaseResult.NotReleased>(result)
            // Reported, never forced: forcing means interrupting, and that
            // is the path this whole design refuses to take.
            assertTrue(!interrupted.get(), "the host thread was interrupted")
            assertTrue(!executor.isTerminated)
        } finally {
            hold.countDown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                "the fixture's executor did not finish"
            }
        }
    }

    @Test
    fun a_part_way_construction_that_can_clean_up_rethrows_its_own_cause() {
        val executors = listOf(single(), single())
        try {
            val cause = IllegalStateException("no supported ABI")

            // Returns normally, so the caller rethrows `cause` as itself.
            TorHostResources.releaseOrRetain(executors, budgetMs = 5_000, cause = cause)

            assertTrue(executors.all { it.isTerminated })
        } finally {
            executors.forEach { it.shutdownNow() }
        }
    }

    @Test
    fun a_part_way_construction_that_cannot_clean_up_hands_the_resources_back() {
        val executor = single()
        val hold = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            executor.execute {
                entered.countDown()
                hold.await(30, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the task never started")
            val cause = IllegalStateException("wrapper construction failed")

            val retained = assertFailsWith<HostResourcesRetainedException> {
                TorHostResources.releaseOrRetain(listOf(executor), budgetMs = 200, cause = cause)
            }

            // The resources themselves come back, not just a flag.
            @Suppress("UNCHECKED_CAST")
            val handedBack = retained.retained as List<ExecutorService>
            assertEquals(listOf(executor), handedBack)
            assertEquals(cause, retained.cause)
        } finally {
            hold.countDown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                "the fixture's executor did not finish"
            }
        }
    }

    private fun single(): ExecutorService = Executors.newSingleThreadExecutor()
}
