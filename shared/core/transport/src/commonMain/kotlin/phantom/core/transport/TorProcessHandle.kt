// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

/**
 * The narrow seam over the embedded-tor library, kept deliberately free of
 * any library type so [TorLifecycleOwner] can be exercised without one.
 *
 * The four operations are separate on purpose. The Android adapter used to
 * run "spawn the daemon, configure bridges, enable the network" as one
 * block, which erased the distinction that decides whether a resource may
 * still exist: `wrapper.start()` returning is the moment the daemon is
 * running, and everything after it can fail with the daemon already up. A
 * single `start(bridges)` cannot report that, so it is not offered here.
 */
internal interface TorProcessHandle {
    /**
     * Spawn the daemon. A [LaunchAttestation.Launched] return is the ONLY
     * evidence the owner accepts that a daemon exists.
     *
     * Implementations classify their own failures rather than throwing,
     * because only the implementation knows what its library guarantees
     * about cleanup. Cancellation is not a library failure and must
     * propagate rather than be classified.
     */
    suspend fun launch(): LaunchAttestation

    /** Apply the bridge lines, or disable bridges when [bridges] is null. */
    suspend fun configureBridges(bridges: List<String>?)

    /** Bring tor's network up or down. */
    suspend fun enableNetwork(enabled: Boolean)

    /**
     * Bring the network down and then stop the daemon.
     *
     * A normal return is evidence the process exited, and is evidence only
     * when the owner's own record says this generation was launched; see
     * [TorLifecycleOwner] for why a normal return proves nothing otherwise.
     *
     * The two steps are reported apart: a failure to quiesce the network
     * comes back in [TeardownReport] rather than as a thrown error, because
     * it must neither be lost nor allowed to turn a process that really did
     * exit into an unconfirmed one. It must also never cause the stop to be
     * skipped.
     */
    suspend fun terminate(): TeardownReport

    /**
     * Let go of everything this generation's host owns, and say whether it
     * actually happened.
     *
     * Waiting is bounded and nothing is interrupted: interrupting is the
     * path where the library loses its process reference. A
     * [ReleaseResult.NotReleased] leaves the host owned by its generation
     * and blocks any successor — that refusal is what bounds accumulation.
     */
    suspend fun release(): ReleaseResult
}

/**
 * What the boundary can attest about the daemon after a launch attempt.
 *
 * The distinction between the two failures is the whole point. For the
 * pinned onionwrapper 0.1.4 the mapping is read off `AbstractTorWrapper
 * .start()`: its handler covers `IOException` only, and before rethrowing
 * it runs `Process.destroy()`, `Process.waitFor()` and clears its process
 * reference. So an `IOException` reaching the caller means that cleanup
 * ran to completion — the daemon was either never spawned or was reaped.
 * Anything else (notably an `InterruptedException` out of that `waitFor`)
 * bypasses the handler, leaving the library's process reference set and
 * its state stuck, and leaves us unable to say what exists.
 *
 * That mapping belongs to the Android implementation of [TorProcessHandle]
 * and must be re-derived if the dependency is ever moved off 0.1.4.
 */
internal sealed interface LaunchAttestation {
    /** The library reported a completed launch: a daemon is running. */
    data object Launched : LaunchAttestation

    /**
     * The launch failed AND the library completed its own cleanup, so no
     * daemon is left behind. This is evidence, not an assumption.
     */
    data class FailedResourceReaped(val cause: Throwable) : LaunchAttestation

    /**
     * The launch failed and nothing can be said about what exists. Not
     * proof that a daemon is alive — proof that we cannot confirm either
     * way, which is why it must never permit a successor.
     */
    data class FailedResourceUnknown(val cause: Throwable) : LaunchAttestation
}
