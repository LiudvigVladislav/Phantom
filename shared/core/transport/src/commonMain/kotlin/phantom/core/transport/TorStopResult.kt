// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * One deadline covering both halves of a teardown: waiting for the daemon
 * to be gone and waiting for its host to let go.
 *
 * It is monotonic and it does not restart per phase — a stop spends part of
 * it and the release wait spends the remainder. Expiry bounds how long a
 * caller waits; it never cancels the work and never disowns anything.
 */
public class TorBudget(totalMs: Long) {
    private val mark = TimeSource.Monotonic.markNow()
    private val totalMs = totalMs.coerceAtLeast(0)

    public fun remainingMs(): Long =
        (totalMs - mark.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)

    public val expired: Boolean get() = remainingMs() == 0L
}

/** Why a teardown has not been confirmed. */
public enum class TorStopReason {
    /**
     * The attempt is still running. It proves the absence of a
     * confirmation, and nothing about whether a daemon is alive.
     */
    NotConfirmedYet,

    /** The attempt ended without being able to say the daemon is gone. */
    Unknown,
}

/**
 * What a caller learns about one teardown.
 *
 * Two of these are intermediate — the attempt is still running — and three
 * are terminal. A host that was never created reaches [Free] by its own
 * path; it is not a separate kind of answer.
 */
public sealed interface TorStopResult {
    public val generation: Long

    /**
     * The daemon is not confirmed gone. Intermediate when the reason is
     * [TorStopReason.NotConfirmedYet], terminal when it is
     * [TorStopReason.Unknown].
     */
    public data class NotConfirmed(
        override val generation: Long,
        public val reason: TorStopReason,
        public val cause: Throwable?,
    ) : TorStopResult

    /**
     * The daemon is gone and its host is still letting go. Intermediate:
     * the same attempt can still reach [Free] or [ReleaseFailed].
     */
    public data class Releasing(override val generation: Long) : TorStopResult

    /**
     * The daemon is gone and its host could not confirm its resources are.
     * Terminal. Both causes are kept: the one that stopped the release, and
     * any failure to quiesce the network before the stop.
     */
    public data class ReleaseFailed(
        override val generation: Long,
        public val releaseFailure: Throwable?,
        public val quiesceFailure: Throwable?,
    ) : TorStopResult

    /**
     * The daemon is gone and nothing is held. Terminal, and the only result
     * that permits a successor — subject to the owner's own check at the
     * moment of starting one.
     */
    public data class Free(
        override val generation: Long,
        public val quiesceFailure: Throwable?,
    ) : TorStopResult
}

/**
 * A fixed word for what this result is, safe to put in a log.
 *
 * The results carry causes, so rendering one whole would put an
 * exception message from the tor library into a log line. Those messages
 * can name paths, ports and bridge configuration. This vocabulary is
 * closed and says only which of the two facts is unfinished: whether the
 * DAEMON is unconfirmed, or whether its HOST has not let go.
 */
public val TorStopResult.label: String
    get() = when (this) {
        is TorStopResult.NotConfirmed -> when (reason) {
            TorStopReason.NotConfirmedYet -> "daemon_not_confirmed_yet"
            TorStopReason.Unknown -> "daemon_unknown"
        }
        is TorStopResult.Releasing -> "host_release_unfinished"
        is TorStopResult.ReleaseFailed -> "host_not_released"
        is TorStopResult.Free -> "free"
    }

/** Whether this result is terminal for its attempt. */
public val TorStopResult.isTerminal: Boolean
    get() = when (this) {
        is TorStopResult.Releasing -> false
        is TorStopResult.NotConfirmed -> reason == TorStopReason.Unknown
        is TorStopResult.ReleaseFailed, is TorStopResult.Free -> true
    }

/** Whether this result says nothing is left of the daemon or its host. */
public val TorStopResult.isFree: Boolean
    get() = this is TorStopResult.Free

/**
 * A handle on one teardown attempt.
 *
 * It is returned for every outcome, not only for an unfinished one, so a
 * caller can always say which attempt it is talking about. Waiting on it
 * again never restarts the work, and the attempt's own completion is not
 * reachable from outside.
 */
public class TorStopAttempt internal constructor(
    public val generation: Long,
    private val observed: Deferred<TorStopObservation>,
    private val snapshot: () -> TorStopObservation,
) {
    /**
     * Wait for this attempt, bounded by [budget].
     *
     * On expiry it reads a consistent snapshot of THIS attempt and returns
     * an intermediate result. It never completes the attempt, never cancels
     * it, and never consults whichever generation happens to be live.
     */
    internal suspend fun await(budget: TorBudget): TorStopResult {
        val settled = withTimeoutOrNull(budget.remainingMs()) { observed.await() }
        return (settled ?: snapshot()).toResult()
    }
}

/** A teardown's handle and what is known about it right now. */
public data class TorStopResponse(
    public val attempt: TorStopAttempt,
    public val result: TorStopResult,
)

/**
 * The answer of a teardown that had nothing to tear down.
 *
 * [TorService] is public and implementable outside this module; the
 * observation a response is built from is not. Without this, a service
 * that never launches a daemon -- a platform stub, a build with tor left
 * out -- could not answer a stop at all. It reports exactly one thing: no
 * daemon and no host, which is the only outcome such a service can honestly
 * claim. Nothing here lets a caller manufacture a confirmation it did not
 * observe.
 */
public fun torStopNothingToStop(generation: Long = 0L): TorStopResponse {
    val observation = TorStopObservation(
        generation = generation,
        stop = StopOutcome.NothingToStop(generation),
        release = ReleaseObservation.NotApplicable,
    )
    return TorStopResponse(
        TorStopAttempt(generation, CompletableDeferred(observation)) { observation },
        observation.toResult(),
    )
}

/**
 * The lifecycle did not settle.
 *
 * Deliberately NOT a `CancellationException`: it is not the caller changing
 * its mind, and inheriting cancellation would let it ride the paths built
 * for a real stop — including the one that arms an ordinary connect retry.
 * It carries the attempt and the structured result so a handler can tell an
 * unreleased host from an unconfirmed daemon.
 */
public class TorLifecycleUnsettled(
    public val attempt: TorStopAttempt,
    public val result: TorStopResult,
) : Exception(
    "tor lifecycle unsettled for generation ${attempt.generation}: ${result.label}",
)

// ── internal observation ─────────────────────────────────────────────────

/** Where the release of a generation's host stands. */
internal sealed interface ReleaseObservation {
    /** The stop is still running, or has not reached the release yet. */
    data object NotStarted : ReleaseObservation

    /** No host was ever created, so there is nothing to release. */
    data object NotApplicable : ReleaseObservation

    data object InProgress : ReleaseObservation
    data object Released : ReleaseObservation
    data class Failed(val cause: Throwable?) : ReleaseObservation
}

/**
 * The two independent facts about one attempt.
 *
 * They are kept apart because either can be true without the other: a
 * confirmed stop says the daemon is gone and says nothing about threads,
 * and a finished release says nothing about the daemon.
 */
internal data class TorStopObservation(
    val generation: Long,
    val stop: StopOutcome?,
    val release: ReleaseObservation,
)

/** Total fold of the two facts onto the public answer. */
internal fun TorStopObservation.toResult(): TorStopResult {
    val outcome = stop
        ?: return TorStopResult.NotConfirmed(generation, TorStopReason.NotConfirmedYet, cause = null)
    if (!outcome.permitsNextGeneration) {
        val cause = (outcome as? StopOutcome.Unknown)?.cause
        return TorStopResult.NotConfirmed(generation, TorStopReason.Unknown, cause)
    }
    val quiesce = (outcome as? StopOutcome.Confirmed)?.quiesceFailure
    return when (val release = release) {
        ReleaseObservation.Released,
        ReleaseObservation.NotApplicable -> TorStopResult.Free(generation, quiesce)

        ReleaseObservation.NotStarted,
        ReleaseObservation.InProgress -> TorStopResult.Releasing(generation)

        is ReleaseObservation.Failed ->
            TorStopResult.ReleaseFailed(generation, release.cause, quiesce)
    }
}
