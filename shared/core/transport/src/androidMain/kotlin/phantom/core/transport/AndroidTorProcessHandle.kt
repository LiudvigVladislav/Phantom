// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.briarproject.onionwrapper.TorWrapper
import java.io.IOException

/**
 * The real boundary over Briar's `TorWrapper`, and the only place that knows
 * what that library guarantees.
 *
 * Two things are deliberate and load-bearing.
 *
 * **The classifier covers `wrapper.start()` and nothing else.** The daemon is
 * spawned part-way through the library's own start, and the steps that follow
 * — bridges, network — can fail with the daemon already running. Folding them
 * into one call would report "the start failed" for a generation that owns a
 * live process, which is the difference between a stopped tor and a second one
 * on the same fixed ports. [configureBridges] and [enableNetwork] are separate
 * for that reason and never produce a [LaunchAttestation].
 *
 * **Blocking calls stay blocking.** `wrapper.stop()` waits on
 * `Process.waitFor()`, and interrupting that wait is the one path where the
 * library discards its process reference and marks itself stopped while a
 * daemon may still be alive. So there is no `runInterruptible` here and no
 * timeout that would cancel the call: waiting is bounded by
 * [TorLifecycleOwner], which bounds the WAIT and leaves the attempt running.
 */
internal class AndroidTorProcessHandle(
    private val wrapper: TorWrapper,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Releases the threads this generation's host owns. Injected because
     * the handle owns none itself, and because the release must not run on
     * the very executors whose termination it waits for.
     */
    private val releaseHost: suspend () -> ReleaseResult = { ReleaseResult.Released },
) : TorProcessHandle {

    /**
     * Spawn the daemon and classify the outcome.
     *
     * The mapping is read off `AbstractTorWrapper.start()` in the pinned
     * onionwrapper 0.1.4. Its handler covers `IOException` only, and before
     * rethrowing it runs `Process.destroy()`, `Process.waitFor()` and clears
     * the process field. So an `IOException` arriving here means that cleanup
     * ran to completion: either no process was ever spawned, or it was killed
     * and reaped. That is the whole justification for
     * [LaunchAttestation.FailedResourceReaped], and it must be re-derived if
     * the dependency ever moves off 0.1.4.
     *
     * Anything else — an `InterruptedException` out of that same `waitFor`
     * above all — bypasses the handler, leaving the library's process field
     * set and its state stuck part-way. Nothing can be said about what exists,
     * so it is [LaunchAttestation.FailedResourceUnknown].
     */
    override suspend fun launch(): LaunchAttestation = withContext(io) {
        try {
            wrapper.start()
            LaunchAttestation.Launched
        } catch (cancellation: CancellationException) {
            // Not a library failure and never classified as one.
            throw cancellation
        } catch (reaped: IOException) {
            LaunchAttestation.FailedResourceReaped(reaped)
        } catch (unknown: Throwable) {
            LaunchAttestation.FailedResourceUnknown(unknown)
        }
    }

    /**
     * Apply the bridge lines, or run without bridges.
     *
     * An empty list means the same as none: telling tor to use no bridges at
     * all is the direct-guards path, which fails fast on a censored network
     * and is the honest signal, rather than starting with an empty bridge set.
     *
     * Failures propagate. This runs after a launch that returned, so a failure
     * here leaves a daemon running and must not read as an absent resource.
     */
    override suspend fun configureBridges(bridges: List<String>?) = withContext(io) {
        if (bridges.isNullOrEmpty()) wrapper.disableBridges() else wrapper.enableBridges(bridges)
    }

    /** Bring tor's network up or down. Failures propagate, as above. */
    override suspend fun enableNetwork(enabled: Boolean) = withContext(io) {
        wrapper.enableNetwork(enabled)
    }

    /**
     * Bring the network down, then stop the daemon.
     *
     * Both steps are always attempted: a failure to quiesce the network is
     * kept and reported, never allowed to skip the stop. Both calls are made
     * plainly and left to finish — bounding them here would mean
     * interrupting `Process.waitFor()`, which is exactly the path that loses
     * the process reference; the owner bounds how long anyone waits instead,
     * once, for the whole attempt.
     */
    override suspend fun terminate(): TeardownReport = withContext(io) {
        var quiesceFailure: Throwable? = null
        try {
            wrapper.enableNetwork(false)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            quiesceFailure = failure
        }
        try {
            wrapper.stop()
        } catch (failure: Throwable) {
            // The stop is the fact that decides the outcome, so its failure
            // leaves as itself; the earlier one rides along rather than
            // disappearing.
            quiesceFailure?.let { failure.addSuppressed(it) }
            throw failure
        }
        // The process exited. A failed quiesce does not undo that, so it is
        // carried rather than promoted into a doubt about the daemon.
        TeardownReport(quiesceFailure)
    }

    /**
     * Hand the release on to whoever owns this generation's threads. The
     * handle itself owns none; production passes the host's release, which
     * shuts its executors down without interrupting them.
     */
    override suspend fun release(): ReleaseResult = releaseHost()
}
