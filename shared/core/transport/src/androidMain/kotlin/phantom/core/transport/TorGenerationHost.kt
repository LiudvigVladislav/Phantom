// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import android.app.Application
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManagerFactory
import org.briarproject.onionwrapper.AndroidTorWrapper
import org.briarproject.onionwrapper.TorWrapper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Everything one tor generation owns: its wrapper, the two executors that
 * wrapper delivers on, and the token that makes its events attributable.
 *
 * A host is never shared between generations. That is what makes a late
 * event answerable: the library dispatches to `this.observer` at execution
 * time, so replacing an observer on a shared wrapper would hand a queued
 * event from the old generation to the new one. A generation's events can
 * only ever reach the observer its own host was built with, and that
 * observer holds [token] from birth.
 */
internal class TorGenerationHost private constructor(
    override val token: GenerationToken,
    override val wrapper: TorWrapper,
    private val executors: List<ExecutorService>,
    private val releaseBudgetMs: Long,
    private val releaseDispatcher: CoroutineDispatcher,
) : TorHost {

    /**
     * Shut the generation's threads down and say whether they actually
     * went.
     *
     * Runs on [releaseDispatcher], which is never one of [executors]:
     * waiting for a pool to terminate from inside that pool would wait on
     * itself. `shutdownNow` is not used — interrupting is the path where
     * the library drops its process reference — so a pool that will not
     * finish is reported, not forced.
     */
    override suspend fun release(): ReleaseResult =
        TorHostResources.release(executors, releaseBudgetMs, releaseDispatcher)

    companion object {
        private const val DEFAULT_RELEASE_BUDGET_MS = 10_000L

        /**
         * Build a host, or leave nothing behind.
         *
         * If construction fails after the executors exist, they are shut
         * down here with the same bounded, non-interrupting discipline. Only
         * if that also fails does this throw
         * [HostResourcesRetainedException], handing the executors back so
         * the generation that asked for them keeps owning them and no
         * successor is created.
         */
        fun create(
            token: GenerationToken,
            application: Application,
            config: TorServiceConfig,
            socksPort: Int,
            controlPort: Int,
            observer: TorWrapper.Observer,
            releaseBudgetMs: Long = DEFAULT_RELEASE_BUDGET_MS,
            releaseDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): TorGenerationHost {
            val ioExecutor = Executors.newSingleThreadExecutor()
            val eventExecutor = Executors.newSingleThreadExecutor()
            val executors = listOf(ioExecutor, eventExecutor)
            try {
                val torDirectory = File(config.dataDirectoryPath).apply { mkdirs() }
                val wakeLockManager = AndroidWakeLockManagerFactory
                    .createAndroidWakeLockManager(application)
                val architecture = Build.SUPPORTED_ABIS.firstOrNull()
                    ?: error("No supported ABI on this device — cannot run the tor wrapper")
                val wrapper = AndroidTorWrapper(
                    application,
                    wakeLockManager,
                    ioExecutor,
                    eventExecutor,
                    architecture,
                    torDirectory,
                    socksPort,
                    controlPort,
                ).apply { setObserver(observer) }
                return TorGenerationHost(
                    token, wrapper, executors, releaseBudgetMs, releaseDispatcher,
                )
            } catch (failure: Throwable) {
                // Leaves nothing behind, or hands back what it could not
                // free so this generation keeps owning it.
                TorHostResources.releaseOrRetain(executors, releaseBudgetMs, failure)
                throw failure
            }
        }
    }
}
