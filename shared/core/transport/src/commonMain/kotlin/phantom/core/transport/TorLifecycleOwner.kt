// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single owner of the embedded-tor lifecycle: one generation at a time, one
 * host per generation, one worker per generation, one stored outcome per
 * stop — and one place where the phase changes and state is published.
 *
 * **The stop result is recorded, never inferred.** A coroutine finishing
 * says nothing about whether the daemon stopped, and neither does the
 * library's own state: on the error path inside its stop it marks itself
 * stopped and drops its process reference before rethrowing.
 *
 * **The worker is not a child of the caller.** It runs in [workers], so a
 * caller that gives up, times out or is cancelled cannot abandon a stop
 * half-way. The budget in [stop] bounds the WAIT; the attempt keeps running
 * and stays the generation's registered obligation.
 *
 * **Every way out of a worker leaves an observable outcome**, carried by a
 * completion handler on the worker's job, which fires however the worker
 * ends and also when its body never ran at all.
 *
 * **A launch that failed is not the same as a launch that never happened.**
 * Only a launch this owner never issued yields
 * [StopOutcome.NothingToStop].
 *
 * **This class is the authority for phase and for publication.** Callers do
 * not keep their own copy of the phase to check later: the phase changes
 * and the projection both happen inside [monitor], so a proposal made from
 * a library callback reads the phase that is true at that instant rather
 * than one an asynchronous observer of [status] has not caught up with.
 * [status] is a notification; it carries no responsibility for currency.
 *
 * Nothing that waits, logs, calls the library or runs arbitrary work may
 * happen under [monitor]; the sections here are a field write and a short
 * projection.
 */
internal class TorLifecycleOwner(
    /**
     * Builds the host for one generation. A fresh host per generation is
     * what makes a late event attributable: it is delivered to that host's
     * own observer, which was born holding that generation's token.
     */
    private val handleFactory: (GenerationToken) -> TorProcessHandle,
    private val workers: CoroutineScope,
    /**
     * Projection of the live generation onto whatever state a caller
     * publishes. Invoked inside [monitor] on every transition, so it must
     * be short: a mapping and a field write, nothing else.
     */
    private val project: (GenerationStatus) -> Unit = {},
    /**
     * Test seam, a no-op in production. Invoked inside [stop] once the stop
     * is registered and the monitor released, but before the signal is
     * delivered — never while the monitor is held.
     */
    private val afterStopRegistered: suspend () -> Unit = {},
) {

    private val monitor = Any()
    private var generationCounter = 0L
    private var live: Generation? = null

    private val _status = MutableStateFlow(
        GenerationStatus(NO_GENERATION, Phase.Idle, outcome = null, hostReleased = true),
    )

    /** Notification only; see the class kdoc. */
    val status: StateFlow<GenerationStatus> = _status.asStateFlow()

    /**
     * Start a generation, refusing while the previous one has no outcome
     * that permits a successor, or has one whose host never let go.
     */
    suspend fun start(bridges: List<String>?): StartResult {
        val generation = synchronized(monitor) {
            val current = live
            if (current != null) {
                // "Already running" is decided here, in the same section
                // that owns the phase, so it cannot be read off a state a
                // caller published earlier and a teardown has since
                // invalidated. Both halves are needed and neither alone
                // is enough: a generation whose configuration failed is
                // running a daemon the app never configured, and one that
                // has since settled — a worker cancelled while it waited
                // for a stop, say — merely USED to be configured. Only a
                // generation that is running right now, under this owner,
                // makes a second start a no-op. Registering a stop already
                // moves the phase off Running, so that check covers it.
                if (current.phase == Phase.Running && current.configured) {
                    return StartResult.AlreadyRunning(current.id)
                }
                val outcome = current.outcome
                if (outcome == null || !outcome.permitsNextGeneration) {
                    return StartResult.RefusedUnsettled(current.id, outcome)
                }
                if (!current.hostReleased) {
                    return StartResult.RefusedHostNotReleased(current.id)
                }
            }
            Generation(++generationCounter).also {
                live = it
                publishLocked(it, Phase.Launching)
            }
        }
        val worker = workers.launch { run(generation, bridges) }
        // Fires however the worker ends, including the case where its body
        // never ran because [workers] was already cancelled: such a job is
        // created already complete. An `isActive` check before launching
        // would narrow that race, not close it.
        worker.invokeOnCompletion { cause -> abandon(generation, cause) }
        return generation.started.await()
    }

    /**
     * Register the stop obligation for the live generation and wait up to
     * [budgetMs] for its stored outcome.
     *
     * Registration happens before the budget starts, so an expiring budget
     * cannot drop the obligation, and the phase moves to
     * [Phase.Stopping] in the same breath so no later event can publish a
     * running state. Repeated calls neither start a second attempt nor
     * queue anything.
     */
    suspend fun stop(budget: TorBudget): TorStopResponse {
        val generation = synchronized(monitor) {
            val current = live ?: return nothingEverStarted()
            current.stopRegistered = true
            if (current.phase != Phase.Settled) publishLocked(current, Phase.Stopping)
            current
        }
        // Once the stop is registered the signal is owed, so it is delivered
        // on every way out of the seam. `complete` does not suspend, so it
        // runs even on a cancelled coroutine; the cancellation or the error
        // still propagates from here.
        try {
            afterStopRegistered()
        } finally {
            generation.stopRequested.complete(Unit)
        }
        val attempt = attemptFor(generation)
        return TorStopResponse(attempt, attempt.await(budget))
    }

    /**
     * Keep waiting on the attempt a caller already holds. Never a new
     * teardown, and never a look at whichever generation is live now.
     */
    suspend fun awaitRelease(attempt: TorStopAttempt, budget: TorBudget): TorStopResult =
        attempt.await(budget)

    private fun attemptFor(generation: Generation) = TorStopAttempt(
        generation = generation.id,
        observed = generation.observed,
        snapshot = { synchronized(monitor) { generation.observationLocked() } },
    )

    /** Nothing was ever started, so nothing is stopping and nothing is held. */
    private fun nothingEverStarted(): TorStopResponse {
        val observation = TorStopObservation(
            generation = NO_GENERATION,
            stop = StopOutcome.NothingToStop(NO_GENERATION),
            release = ReleaseObservation.NotApplicable,
        )
        return TorStopResponse(
            TorStopAttempt(NO_GENERATION, CompletableDeferred(observation)) { observation },
            observation.toResult(),
        )
    }

    /**
     * Run [action] against the authoritative phase, but only if [token] is
     * still the live generation's own.
     *
     * This is the single gate for anything a generation's host wants to
     * publish. The identity check and [action] happen in the same section
     * that performs phase transitions, so an event cannot pass a phase that
     * has already moved on. [action] must be short — a decision and a field
     * write — and must not wait, log or call the library.
     */
    fun onLiveGeneration(token: GenerationToken, action: (Phase) -> Unit) {
        synchronized(monitor) {
            val current = live ?: return
            if (current.token !== token) return
            action(current.phase)
        }
    }

    /**
     * Like [onLiveGeneration] but addressed by the generation a result came
     * from, so a caller that waited can publish about its OWN generation
     * and about no other. Ids are unique and never reused.
     */
    fun onGeneration(id: Long, action: (Phase) -> Unit) {
        synchronized(monitor) {
            val current = live ?: return
            if (current.id != id) return
            action(current.phase)
        }
    }

    private suspend fun run(generation: Generation, bridges: List<String>?) {
        if (!claimLaunch(generation)) {
            generation.started.complete(StartResult.Skipped(generation.id))
            // No host was ever built, so there is nothing to release.
            settleAndRelease(generation, StopOutcome.NothingToStop(generation.id))
            return
        }

        val handle = try {
            handleFactory(generation.token)
        } catch (retained: HostResourcesRetainedException) {
            // The factory failed part-way and could not free what it made.
            // No launch was issued, so nothing is running; the resources it
            // handed back stay owned here and refuse a successor.
            adoptHost(
                generation,
                handle = null,
                retained = retained.retained,
                retainedCause = retained,
            )
            generation.started.complete(
                StartResult.LaunchFailed(generation.id, retained, resourceReaped = true),
            )
            settleAndRelease(generation, StopOutcome.NothingToStop(generation.id))
            return
        } catch (failure: Throwable) {
            // The factory's contract is that it leaves nothing behind on
            // this path, so the generation is settled with its host free.
            generation.started.complete(
                StartResult.LaunchFailed(generation.id, failure, resourceReaped = true),
            )
            settleAndRelease(generation, StopOutcome.NothingToStop(generation.id))
            if (failure is CancellationException) throw failure
            return
        }
        adoptHost(generation, handle, retained = null)

        val attestation = try {
            handle.launch()
        } catch (failure: Throwable) {
            // Includes a boundary that throws CancellationException. Either
            // way the daemon's existence is unknown. Recording it here
            // rather than leaving it to the completion handler keeps the
            // cause, which that handler cannot see.
            settle(generation, StopOutcome.Unknown(generation.id, failure))
            generation.started.complete(
                StartResult.LaunchFailed(generation.id, failure, resourceReaped = false),
            )
            if (failure is CancellationException) throw failure
            return
        }

        when (attestation) {
            is LaunchAttestation.FailedResourceReaped -> {
                generation.started.complete(
                    StartResult.LaunchFailed(generation.id, attestation.cause, resourceReaped = true),
                )
                settleAndRelease(generation, StopOutcome.ResourceReaped(generation.id))
            }

            is LaunchAttestation.FailedResourceUnknown -> {
                generation.started.complete(
                    StartResult.LaunchFailed(generation.id, attestation.cause, resourceReaped = false),
                )
                // Issued once because the library's stop also releases a
                // wake lock. Its result is NOT evidence: after a launch
                // failure the library refuses a real teardown, so the
                // outcome stays Unknown either way.
                runTerminateIgnoringResult(handle)
                settle(generation, StopOutcome.Unknown(generation.id, attestation.cause))
            }

            LaunchAttestation.Launched -> {
                transition(generation, Phase.Running)
                val configured = configure(generation, handle, bridges)
                if (configured is StartResult.Started) markConfigured(generation)
                generation.started.complete(configured)
                // The daemon exists. Whether configuration succeeded or not,
                // this generation owes a stop and the successor waits.
                generation.stopRequested.await()
                transition(generation, Phase.Stopping)
                settleAndRelease(generation, terminate(generation, handle))
            }
        }
    }

    /**
     * The single point at which it is decided whether this generation's
     * launch is issued at all: exactly one of "the stop won and no launch
     * ever happens" or "the launch is claimed and the stop is honoured
     * after it" is true afterwards.
     */
    private fun markConfigured(generation: Generation) = synchronized(monitor) {
        generation.configured = true
    }

    private fun claimLaunch(generation: Generation): Boolean = synchronized(monitor) {
        if (generation.stopRegistered) {
            false
        } else {
            generation.launchClaimed = true
            true
        }
    }

    private suspend fun configure(
        generation: Generation,
        handle: TorProcessHandle,
        bridges: List<String>?,
    ): StartResult =
        try {
            handle.configureBridges(bridges)
            handle.enableNetwork(true)
            StartResult.Started(generation.id)
        } catch (cancellation: CancellationException) {
            // The daemon is up, so the stop obligation still stands; the
            // worker's completion handler records the outcome once this
            // cancellation has carried the worker out.
            throw cancellation
        } catch (failure: Throwable) {
            StartResult.ConfigurationFailed(generation.id, failure)
        }

    private suspend fun terminate(generation: Generation, handle: TorProcessHandle): StopOutcome =
        try {
            val report = handle.terminate()
            // A failure to quiesce the network does not undo a process that
            // exited; it rides along so it is neither lost nor promoted.
            StopOutcome.Confirmed(generation.id, report.quiesceFailure)
        } catch (failure: Throwable) {
            val outcome = StopOutcome.Unknown(generation.id, failure)
            if (failure is CancellationException) {
                settle(generation, outcome)
                throw failure
            }
            outcome
        }

    private suspend fun runTerminateIgnoringResult(handle: TorProcessHandle) {
        try {
            handle.terminate()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Deliberately discarded: this call cannot change the outcome.
        }
    }

    /**
     * Settle this generation and record what became of its host.
     *
     * Whenever the second fact needs no work, BOTH are recorded in one
     * critical section. Splitting them opens a window in which the stop
     * already permits a successor while this generation still owes a
     * release fact, and a successor created inside that window would be
     * publishing beside a predecessor that has not finished speaking.
     */
    private suspend fun settleAndRelease(generation: Generation, outcome: StopOutcome) {
        val releasing = synchronized(monitor) {
            settleLocked(generation, outcome)
            // Read back what was actually recorded: an earlier settle wins,
            // and this decision must follow the recorded outcome, not the
            // one this call happened to bring.
            val settledOutcome = generation.outcome
            val immediate = when {
                settledOutcome == null || !settledOutcome.permitsNextGeneration -> null

                // A factory that failed part-way and could not free what it
                // had made hands the resources BACK. There is no handle to
                // release and there is very much something held, so this is
                // a failed release carrying its reason -- never the
                // "nothing was ever built" answer, which would report Free
                // for a generation whose successor this owner still refuses.
                generation.retainedHost != null ->
                    ReleaseObservation.Failed(generation.retainedCause)

                generation.handle == null -> ReleaseObservation.NotApplicable
                else -> ReleaseObservation.InProgress
            }
            if (immediate != null) noteReleaseLocked(generation, immediate)
            immediate == ReleaseObservation.InProgress
        }
        generation.settled.complete(outcome)
        if (releasing) releaseHost(generation)
    }

    /** Records the release fact and closes the attempt once both are final. */
    private fun noteRelease(generation: Generation, observation: ReleaseObservation) {
        synchronized(monitor) { noteReleaseLocked(generation, observation) }
    }

    /** Must be called with [monitor] held. */
    private fun noteReleaseLocked(generation: Generation, observation: ReleaseObservation) {
        generation.release = observation
        if (observation == ReleaseObservation.Released) generation.hostReleased = true
        publishLocked(generation, generation.phase)
        generation.closeObservationLocked()
    }

    /**
     * Let the generation's host go, off the monitor and off the host's own
     * threads. Only the result of this decides whether a successor is
     * allowed; nothing is interrupted to force it.
     */
    private suspend fun releaseHost(generation: Generation) {
        val handle = generation.handle ?: return
        val result = try {
            handle.release()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            ReleaseResult.NotReleased(failure)
        }
        noteRelease(
            generation,
            when (result) {
                ReleaseResult.Released -> ReleaseObservation.Released
                is ReleaseResult.NotReleased -> ReleaseObservation.Failed(result.cause)
            },
        )
    }

    /**
     * Take ownership of what the factory produced. Until [releaseHost]
     * proves otherwise this generation holds it, and a successor is
     * refused — that refusal is the only thing bounding accumulation, so
     * it is recorded here rather than derived from a later transition.
     */
    private fun adoptHost(
        generation: Generation,
        handle: TorProcessHandle?,
        retained: Any?,
        retainedCause: Throwable? = null,
    ) {
        synchronized(monitor) {
            generation.handle = handle
            generation.retainedHost = retained
            generation.retainedCause = retainedCause
            generation.hostBuilt = true
            generation.hostReleased = false
            publishLocked(generation, generation.phase)
        }
    }

    /** Records [outcome] unless this generation already settled. */
    private fun settle(generation: Generation, outcome: StopOutcome) {
        synchronized(monitor) { settleLocked(generation, outcome) }
        generation.settled.complete(outcome)
    }

    /** Must be called with [monitor] held. */
    private fun settleLocked(generation: Generation, outcome: StopOutcome) {
        if (generation.settled.isCompleted) return
        publishLocked(generation, Phase.Settled, outcome)
        generation.closeObservationLocked()
    }

    /**
     * Backstop for a worker that ended without settling. Both completions
     * are no-ops once the worker has produced its own answer.
     */
    private fun abandon(generation: Generation, cause: Throwable?) {
        // Two different facts, and a worker ending proves only one of them.
        // If the daemon was never confirmed gone this is Unknown, never a
        // release failure -- that would claim the daemon IS gone. If it WAS
        // confirmed and the release had not finished, the release is what
        // failed, because its executor is no longer there to finish it.
        val confirmed = synchronized(monitor) {
            generation.outcome?.permitsNextGeneration == true
        }
        if (confirmed) {
            val unfinished = synchronized(monitor) {
                generation.release == ReleaseObservation.NotStarted ||
                    generation.release == ReleaseObservation.InProgress
            }
            if (unfinished) noteRelease(generation, ReleaseObservation.Failed(cause))
        } else {
            settle(generation, StopOutcome.Unknown(generation.id, cause))
        }
        generation.started.complete(StartResult.Abandoned(generation.id, cause))
    }

    /**
     * Move the phase forward, never back.
     *
     * A stop registered while the launch was still in flight has already
     * moved this generation to [Phase.Stopping]; announcing "Running"
     * afterwards would reopen the window that stop just closed and let an
     * event publish a running state for a generation that is being torn
     * down. Phases only ever advance.
     */
    private fun transition(generation: Generation, phase: Phase) {
        synchronized(monitor) {
            if (phase.ordinal <= generation.phase.ordinal) return
            publishLocked(generation, phase)
        }
    }

    /**
     * Advance a generation's own record, and publish it only while it is
     * still the one this owner speaks for.
     *
     * The record ALWAYS advances: the observation this generation owes has
     * to be closed however late the answer arrives, and a caller waiting on
     * that attempt is owed it whatever else has happened since.
     *
     * Publishing is a different thing. A release finishing for a generation
     * a successor has already replaced would otherwise write that
     * predecessor's Settled over the live one and project a stopped tor
     * onto a daemon that is starting. The last writer would win, and it is
     * the wrong writer.
     *
     * Must be called with [monitor] held.
     */
    private fun publishLocked(
        generation: Generation,
        phase: Phase,
        outcome: StopOutcome? = generation.outcome,
    ) {
        generation.phase = phase
        generation.outcome = outcome
        if (live !== generation) return
        val status = GenerationStatus(generation.id, phase, outcome, generation.hostReleased)
        _status.value = status
        project(status)
    }

    private class Generation(val id: Long) {
        val token = GenerationToken()
        val stopRequested = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<StopOutcome>()
        val started = CompletableDeferred<StartResult>()

        /** Completed only with a FINAL observation of this attempt. */
        val observed = CompletableDeferred<TorStopObservation>()

        var release: ReleaseObservation = ReleaseObservation.NotStarted

        /** Must be called with the owner's monitor held. */
        fun observationLocked() = TorStopObservation(id, outcome, release)

        /** Must be called with the owner's monitor held. */
        fun closeObservationLocked() {
            val observation = observationLocked()
            val stop = observation.stop ?: return
            val terminal = !stop.permitsNextGeneration ||
                observation.release is ReleaseObservation.Failed ||
                observation.release == ReleaseObservation.Released ||
                observation.release == ReleaseObservation.NotApplicable
            if (terminal) observed.complete(observation)
        }

        /** All of these are read and written only under the owner's monitor. */
        var phase: Phase = Phase.Launching
        var outcome: StopOutcome? = null
        var stopRegistered = false
        var launchClaimed = false
        var configured = false
        var hostBuilt = false
        var hostReleased = true
        var retainedHost: Any? = null

        /** Why [retainedHost] could not be let go. Kept so the release
         * fact this generation reports carries its reason. */
        var retainedCause: Throwable? = null

        /** Written by the worker before any transition that reads it. */
        var handle: TorProcessHandle? = null
    }

    private companion object {
        /** Reported when no generation has ever been created. */
        const val NO_GENERATION = 0L
    }
}
