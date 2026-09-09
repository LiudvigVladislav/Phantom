// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The two resource paths a generation's host can take, kept apart from the
 * Android objects around them so both can be exercised with real executors.
 *
 * Neither ever interrupts. Interrupting a tor teardown is the path where
 * the library discards its process reference while a daemon may still be
 * alive, so a pool that will not finish is reported rather than forced.
 */
internal object TorHostResources {

    /**
     * Shut [executors] down and say whether they actually terminated.
     *
     * Runs on [dispatcher], which must never be one of [executors]: waiting
     * for a pool from inside that pool waits on itself. The budget covers
     * all of them together rather than restarting per pool.
     */
    suspend fun release(
        executors: List<ExecutorService>,
        budgetMs: Long,
        dispatcher: CoroutineDispatcher,
    ): ReleaseResult = withContext(dispatcher) {
        val stuck = shutdownAndAwait(executors, budgetMs)
        if (stuck.isEmpty()) {
            ReleaseResult.Released
        } else {
            ReleaseResult.NotReleased(
                IllegalStateException("${stuck.size} tor host executor(s) did not terminate"),
            )
        }
    }

    /**
     * Clean up after a host that failed part-way through construction.
     *
     * Returns normally when nothing is left behind, so [cause] can be
     * rethrown as itself. When some pool will not finish, it throws
     * [HostResourcesRetainedException] carrying those pools, so the
     * generation that asked for them keeps owning them and no successor is
     * created — the resources are handed over, not merely flagged.
     */
    fun releaseOrRetain(
        executors: List<ExecutorService>,
        budgetMs: Long,
        cause: Throwable,
    ) {
        val stuck = shutdownAndAwait(executors, budgetMs)
        if (stuck.isNotEmpty()) {
            throw HostResourcesRetainedException(retained = stuck, cause = cause)
        }
    }

    private fun shutdownAndAwait(
        executors: List<ExecutorService>,
        budgetMs: Long,
    ): List<ExecutorService> {
        executors.forEach { runCatching { it.shutdown() } }
        val deadline = System.nanoTime() + budgetMs * NANOS_PER_MILLI
        return executors.filterNot { executor ->
            val left = deadline - System.nanoTime()
            left > 0 && runCatching {
                executor.awaitTermination(left, TimeUnit.NANOSECONDS)
            }.getOrDefault(false)
        }
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
