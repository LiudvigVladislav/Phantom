// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

/**
 * Identity of one tor generation, handed to whatever that generation owns.
 *
 * Compared by reference and never reassigned. An event arriving from a
 * generation's own wrapper carries the token that generation was born
 * with, so a late event cannot be mistaken for the live one by reading a
 * counter at delivery time.
 */
internal class GenerationToken

/** Where a generation is in its life, as the owner sees it. */
internal enum class Phase { Idle, Launching, Running, Stopping, Settled }

/**
 * The live generation, published by [TorLifecycleOwner].
 *
 * This is a notification. It carries no responsibility for whether a
 * reader's view is current: anything that must act on the phase does so
 * inside the owner's monitor, never from a copy of this value.
 */
internal data class GenerationStatus(
    val id: Long,
    val phase: Phase,
    val outcome: StopOutcome?,
    val hostReleased: Boolean,
)

/** What a teardown attempt reports beyond whether it finished. */
internal data class TeardownReport(
    /**
     * The failure of quiescing the network before the stop, if there was
     * one. A stop that then returned normally is still a confirmed stop —
     * this failure is carried, never allowed to erase the confirmation and
     * never dropped.
     */
    val quiesceFailure: Throwable?,
)

/** Whether a generation's host actually let go of its resources. */
internal sealed interface ReleaseResult {
    data object Released : ReleaseResult

    /**
     * The host could not confirm its resources are gone. Nothing is
     * interrupted to force it; the host stays owned by its generation and
     * no successor may be created.
     */
    data class NotReleased(val cause: Throwable?) : ReleaseResult
}

/**
 * The stored result of stopping one tor generation.
 *
 * Every value here is recorded by [TorLifecycleOwner] from what it actually
 * did. None of them is derived from a coroutine finishing, from a job's
 * completion flag, or from the library's own reported state: a job also
 * completes when its work threw, and the library sets its state to stopped
 * on the very error path where it loses track of the daemon.
 */
internal sealed interface StopOutcome {
    val generation: Long

    /**
     * The launch was issued for this generation and the terminate call
     * returned normally. The only positive confirmation.
     *
     * [quiesceFailure] carries a failure to bring the network down before
     * the stop. It does not weaken the confirmation — the process did
     * exit — and it is kept so that failure is not lost.
     */
    data class Confirmed(
        override val generation: Long,
        val quiesceFailure: Throwable? = null,
    ) : StopOutcome

    /**
     * The launch was never issued, so there is provably nothing to stop.
     * Reachable only from that fact — never from a launch that failed.
     */
    data class NothingToStop(override val generation: Long) : StopOutcome

    /**
     * The launch failed and the boundary attested that the library reaped
     * whatever it had spawned. No daemon is left, so a successor is safe.
     */
    data class ResourceReaped(override val generation: Long) : StopOutcome

    /**
     * The stop attempt is still running and the caller's budget elapsed.
     * Not terminal: the same attempt can still settle as [Confirmed] or
     * [Unknown], and asking again returns whatever it settled on.
     */
    data class Pending(override val generation: Long) : StopOutcome

    /**
     * We cannot confirm the daemon is gone. This is the absence of a
     * confirmation, not a proof that a daemon is alive and not a claim
     * that recovery is impossible.
     */
    data class Unknown(override val generation: Long, val cause: Throwable?) : StopOutcome
}

/**
 * Whether this outcome lets a new generation start.
 *
 * Only outcomes that carry positive evidence about the previous daemon do.
 * [StopOutcome.Pending] and [StopOutcome.Unknown] never do, because
 * starting a second daemon while the first may be alive is the failure
 * this whole component exists to prevent.
 *
 * A permitting outcome is necessary and not sufficient: the previous
 * generation's host must also have released its resources.
 */
internal val StopOutcome.permitsNextGeneration: Boolean
    get() = when (this) {
        is StopOutcome.Confirmed,
        is StopOutcome.NothingToStop,
        is StopOutcome.ResourceReaped -> true

        is StopOutcome.Pending,
        is StopOutcome.Unknown -> false
    }

/** Outcome of asking [TorLifecycleOwner] to start a generation. */
internal sealed interface StartResult {
    /** The daemon was launched; configuration completed too. */
    data class Started(val generation: Long) : StartResult

    /**
     * The daemon WAS launched and a later configuration step failed. The
     * stop obligation for this generation stands: something is running.
     */
    data class ConfigurationFailed(
        val generation: Long,
        val cause: Throwable,
    ) : StartResult

    /** The launch itself failed; [resourceReaped] carries the attestation. */
    data class LaunchFailed(
        val generation: Long,
        val cause: Throwable,
        val resourceReaped: Boolean,
    ) : StartResult

    /**
     * A generation is already running and no stop has been registered
     * against it, so this start is a no-op.
     *
     * Decided inside the owner's monitor rather than from a published
     * state, which a teardown may already have invalidated.
     */
    data class AlreadyRunning(val generation: Long) : StartResult

    /** A stop arrived before the launch was issued, so it never was. */
    data class Skipped(val generation: Long) : StartResult

    /**
     * The worker ended without producing a start result — its boundary
     * threw a cancellation, or its scope was gone before the body ran.
     * The generation settles [StopOutcome.Unknown] alongside this, so a
     * caller is never left waiting on a worker that no longer exists.
     */
    data class Abandoned(
        val generation: Long,
        val cause: Throwable?,
    ) : StartResult

    /**
     * Refused because the previous generation has no outcome that permits
     * a successor. [outcome] is null while that generation is unsettled.
     */
    data class RefusedUnsettled(
        val blockingGeneration: Long,
        val outcome: StopOutcome?,
    ) : StartResult

    /**
     * Refused because the previous generation's daemon is gone but its
     * host never confirmed its resources are.
     *
     * This refusal IS the bound on accumulation: at most one unreleased
     * host can exist, because no successor is created while it stands.
     * There is no sweeper and no retry loop.
     */
    data class RefusedHostNotReleased(
        val blockingGeneration: Long,
    ) : StartResult
}

/**
 * Thrown by a host factory that failed part-way AND could not let go of
 * what it had already created.
 *
 * The resources are not abandoned: the factory hands them back through
 * [retained] so the generation that asked for them keeps owning them, and
 * the successor stays refused.
 */
internal class HostResourcesRetainedException(
    val retained: Any?,
    cause: Throwable,
) : RuntimeException("tor host resources were created and could not be released", cause)
