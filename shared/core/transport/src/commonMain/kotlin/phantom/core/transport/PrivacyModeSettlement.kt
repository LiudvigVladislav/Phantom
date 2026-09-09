// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Give a shutdown a bounded wait on an obligation it may not abandon.
 *
 * ## Why an outer `withTimeoutOrNull` was not one
 *
 * R-N1.17 P1. `onDestroy` wrapped the settlement in a five-second
 * timeout, and the settlement runs - deliberately - inside
 * `NonCancellable`, as does the whole teardown beneath it. A timeout
 * cannot cancel what will not be cancelled, so the budget bounded
 * nothing: a wedged native `stop()` could block `onDestroy` past its
 * budget and, in principle, indefinitely.
 *
 * Cancelling the teardown is not the answer either. A half-run teardown
 * is the defect this round has been closing all along, and a native stop
 * already in progress cannot be aborted from Kotlin whatever the wrapper
 * says.
 *
 * So the WAIT is bounded rather than the work. The obligation runs in a
 * scope that outlives the service instance; the shutdown waits [budgetMs]
 * for it and then stops waiting. On expiry the obligation stands, it is
 * reported as unfinished so the cleanup is re-armed, and no successor is
 * started - the recovery is stood down. If the in-flight attempt finishes
 * later it reports itself, so the record is discharged by whichever gets
 * there first.
 */
suspend fun settleWithinShutdownBudget(
    /** The claim this shutdown was handed. */
    claim: HandoffRecovery.StandDown.SettlementOwed,
    budgetMs: Long,
    /** A scope that outlives the service instance being destroyed. */
    scope: CoroutineScope,
    settle: suspend (epoch: Long) -> Boolean,
    /** Apply a finished attempt's result, CAS'd on the ATTEMPT's identity. */
    noteOutcome: suspend (claimToken: Long, settled: Boolean) -> Unit,
    /** Stop waiting without releasing the claim the work still holds. */
    noteOverBudget: suspend (claimToken: Long) -> Unit,
    log: ((String) -> Unit)? = null,
): Boolean {
    // ONE worker per obligation. When the claim was already in flight -
    // the timer got there first and is inside a NonCancellable teardown
    // that cancelling the timer does not stop - this waits on THAT
    // attempt. Starting another would produce two teardowns and,
    // once the service resumed, two successors for one epoch.
    val work = claim.inFlight ?: scope.async {
        val ok = runCatching { settle(claim.epoch) }
            .onFailure { log?.invoke("SHUTDOWN settle_threw error=${it::class.simpleName}") }
            .getOrDefault(false)
        // Reported from here too: the shutdown may already have given up
        // waiting, and this attempt is still the one that did the work.
        noteOutcome(claim.claimToken, ok)
        ok
    }
    val settled = withTimeoutOrNull(budgetMs) { work.await() }
    if (settled == null) {
        log?.invoke(
            "SHUTDOWN settlement_over_budget claim=${claim.claimToken} budgetMs=$budgetMs",
        )
        // Giving up WAITING is not giving up the claim: the attempt is
        // still running and still owns it. Releasing it here would let
        // the driver armed below take the same obligation and run a
        // second teardown beside the first.
        noteOverBudget(claim.claimToken)
        return false
    }
    if (!settled) {
        // Nothing to do here, and that is the point.
        //
        // An attempt that finished without settling leaves an obligation
        // still owning the claim fence, and it must have something able
        // to try again. This function used to report that itself - which
        // was wrong twice over: it named the OBLIGATION rather than the
        // attempt, so a slow report could release a claim that had since
        // passed to another worker, and it made the guarantee a duty of
        // every caller who might stop waiting.
        //
        // The guarantee now lives where the claim is released, which is
        // the only place that can see the state it is about.
        log?.invoke("SHUTDOWN settlement_unfinished claim=${claim.claimToken}")
    }
    return settled
}

/** What the inline half of a switch did about the successor. */
sealed interface SwitchConclusion {
    val result: PrivacyModeChangeResult

    /**
     * Nothing from the old policy was left. This transaction completed
     * the switch and owns the single successor start.
     */
    data class Finished(
        override val result: PrivacyModeChangeResult,
        val successorStarted: Boolean,
    ) : SwitchConclusion

    /**
     * Something from the old policy outlived the transaction. The
     * deferred settlement owns the release, the completion AND the
     * successor; this transaction starts nothing.
     */
    data class Deferred(
        override val result: PrivacyModeChangeResult,
        val reason: String,
    ) : SwitchConclusion
}

/**
 * Conclude the INLINE half of a privacy switch, and decide who owes the
 * successor.
 *
 * ## Why this is not three lines in the Android container
 *
 * R-N1.17 P1. The deferred half was made a single epoch-bound obligation
 * and given sole ownership of the successor start - and then the inline
 * path started one unconditionally anyway, so the ownership was nominal.
 * The two successors then raced: the inline one could win the connect
 * lease and reach a permit that was already revoked, and the settlement
 * would start another behind it. `startForegroundService` being
 * idempotent does not help; these are two DIFFERENT successors, not one
 * intent sent twice.
 *
 * Keeping the rule in the container also meant it could only be checked
 * by reading source. This is the other half of [settleDeferredPrivacySwitch],
 * and the pair is what guarantees "exactly one successor per switch" -
 * so the pair has to be exercisable in one fixture.
 *
 * The rule, entire: the transaction finished only if no socket from the
 * old policy is still open AND the walk is confirmed stopped.
 */
suspend fun concludePrivacySwitch(
    epoch: Long,
    coordinator: PrivacyModeCoordinator,
    /** Sockets from the old policy still registered as open. */
    stillOpen: Int,
    /** Whether the displaced chain walk was confirmed stopped. */
    walkQuiesced: Boolean,
    /**
     * Whether the transport subsystems were CONFIRMED stopped.
     *
     * R-N1.17 P1: the deferred half refuses to call a dirty release a
     * settled switch, and the inline half used to log one and carry on to
     * `Applied` plus a successor - the same defect, on the path that runs
     * far more often.
     *
     * The WHOLE teardown, not just the subsystem release. A REST egress
     * revocation that did not join, or a WSS disconnect that timed out,
     * means old-policy I/O may have outlived the switch just as surely as
     * a proxy that would not stop - and a switch that publishes `Applied`
     * over any of them is the silent downgrade this round exists to
     * prevent. A release that was SKIPPED is not a confirmed one either.
     */
    teardownConfirmed: Boolean,
    /** Record the deferred obligation, epoch-bound. */
    armSettlement: suspend (reason: String, epoch: Long) -> Unit,
    /**
     * Let ordinary connect claims through again.
     *
     * Called after the completion and BEFORE the successor is started:
     * the successor has to be able to claim the lease, and nothing else
     * may claim it before the teardown has finished. NOT called when the
     * switch defers - an obligation that still owes a release keeps the
     * lease shut.
     */
    lowerClaimFence: suspend () -> Unit = {},
    /** Start exactly one successor, unless a shutdown is in progress. */
    startSuccessor: suspend () -> HandoffRecovery.SuccessorOutcome,
    /** Record that a start this transaction owed did not happen. */
    armStartRetry: suspend (reason: String, epoch: Long) -> Unit,
    /**
     * Tell the recovery that this epoch started its own successor, so an
     * older start-only debt is void.
     */
    noteSuccessorStarted: suspend (epoch: Long) -> Unit = {},
    log: ((String) -> Unit)? = null,
): SwitchConclusion {
    val finished = stillOpen == 0 && walkQuiesced && teardownConfirmed
    // Armed BEFORE the completion: from here the obligation exists even
    // if everything after it fails.
    if (!finished) {
        val reason = when {
            stillOpen > 0 -> "privacy_switch_sockets_open"
            !walkQuiesced -> "privacy_switch_walk_not_quiesced"
            else -> "privacy_switch_teardown_incomplete"
        }
        armSettlement(reason, epoch)
        val result = coordinator.complete(
            epoch = epoch,
            // A switch whose subsystems are still up has not changed the
            // posture, whatever the socket register says.
            policyChangeComplete = stillOpen == 0 && teardownConfirmed,
            walkQuiesced = walkQuiesced,
        )
        log?.invoke(
            "SWITCH successor_deferred epoch=$epoch stillOpen=$stillOpen " +
                "walkQuiesced=$walkQuiesced teardownConfirmed=$teardownConfirmed",
        )
        // The fence STAYS UP. This switch is Blocked and owes a
        // settlement: something from the old policy is still live, and
        // the release that would stop it has not run. Reopening the lease
        // here let an ordinary service start begin a fresh chain walk in
        // exactly that state, and the settlement would then release the
        // subsystems under it.
        //
        // Fail-closed while an obligation stands is the same trade the
        // handover block already makes - a brief outage is better than
        // two policies live at once - and the settlement's retry is what
        // keeps it brief.
        return SwitchConclusion.Deferred(result, reason)
    }
    val result = coordinator.complete(
        epoch = epoch,
        policyChangeComplete = true,
        walkQuiesced = true,
    )
    // Only now: the release is done, so a successor's claim can no longer
    // be torn down by this transition.
    lowerClaimFence()
    val outcome = startSuccessor()
    when (outcome) {
        HandoffRecovery.SuccessorOutcome.Started ->
            // R-N1.17 P2: a start debt from an OLDER epoch describes a
            // successor that this one has now provided. Left standing,
            // its timer sends a second, unthrottled start.
            noteSuccessorStarted(epoch)
        HandoffRecovery.SuccessorOutcome.Failed -> {
            // A failed start is not a log line. Nothing else would retry
            // it - the caller may already be cancelled, and a clean
            // handover leaves the recovery timer unarmed - so the app
            // would sit with a persisted new mode and no transport.
            log?.invoke("SWITCH successor_start_failed epoch=$epoch")
            armStartRetry("successor_start_failed", epoch)
        }
        HandoffRecovery.SuccessorOutcome.Suppressed ->
            // A stop landed mid-switch. Arming a retry here would be the
            // restart the stop forbids.
            log?.invoke("SWITCH successor_suppressed epoch=$epoch cause=shutdown")
    }
    return SwitchConclusion.Finished(
        result,
        successorStarted = outcome == HandoffRecovery.SuccessorOutcome.Started,
    )
}

/** How far the deferred half of a switch has got. */
enum class SettlementPhase {
    /** Sockets from the previous policy are still open. */
    SocketsOutstanding,

    /** The chain walk that owned the connect lease is not confirmed stopped. */
    WalkOutstanding,

    /** Everything was released, completed and one successor started. */
    Settled,

    /**
     * The teardown could not be confirmed complete. The obligation
     * stands: `Settled` means every phase ran and joined, and a swallowed
     * failure would make that word untrue.
     */
    TeardownIncomplete,

    /** A newer epoch overtook this obligation. It must do nothing. */
    Superseded,

    /** The successor could not be started. The obligation stands. */
    SuccessorFailed,
}

/**
 * One attempt at the whole idempotent teardown.
 *
 * R-N1.17 P1: the deferred half used to retry only the subsystem release.
 * A REST egress revocation that threw during the inline half was
 * correctly refused as a completed switch - and then the next recovery
 * tick released the subsystems, saw a clean permit register and published
 * `Applied`, with the failed revocation never retried and old REST/media
 * leases possibly still live. Every phase is idempotent, so the fix is to
 * run them all again and let a FRESH full result decide.
 */
data class TeardownAttempt(
    /** Whether the displaced chain walk was confirmed stopped. */
    val walkQuiesced: Boolean,
    /**
     * Whether EVERY phase of this attempt ran and joined - revocation,
     * socket disconnect, walk handover and subsystem release.
     */
    val confirmed: Boolean,
)

data class SettlementOutcome(val phase: SettlementPhase) {
    val isSettled: Boolean get() = phase == SettlementPhase.Settled
    /** A superseded obligation is finished with - it just achieved nothing. */
    val isDischarged: Boolean
        get() = phase == SettlementPhase.Settled || phase == SettlementPhase.Superseded
}

/**
 * Finish the deferred half of a privacy switch: release the subsystems,
 * complete the switch, and start exactly ONE successor.
 *
 * ## Why this is a function and not three flags
 *
 * R-N1.17. The obligation used to live in three independent places - a
 * start debt in the recovery, a settlement flag in the recovery, and a
 * boolean in the Android container - and all three described one action:
 * "once every blocker has cleared, start exactly one successor". Three
 * representations of one fact disagree, and they did:
 *
 *  - the container's flag carried no epoch, so a timer armed by switch A
 *    would settle again over switch B, releasing a transport B was using
 *    and starting a second successor;
 *  - a lease recovery started a successor and the settlement started
 *    another in the same tick;
 *  - a successful start did not discharge a start debt recorded earlier,
 *    so a later arm sent yet another.
 *
 * One epoch-bound obligation, one place that discharges it.
 *
 * Every step is checked against [epoch]: an obligation from a superseded
 * switch reports [SettlementPhase.Superseded] and touches nothing.
 */
suspend fun settleDeferredPrivacySwitch(
    epoch: Long,
    coordinator: PrivacyModeCoordinator,
    /**
     * Run the WHOLE teardown again - revocation, socket disconnect, walk
     * handover, subsystem release - and report what this attempt
     * achieved.
     *
     * Not "release the subsystems". Retrying one phase of a transaction
     * and then completing the whole thing is how a failed revocation
     * became an applied switch.
     */
    retryTeardown: suspend () -> TeardownAttempt,
    /**
     * Hold the lease shut for this epoch. Idempotent, and raised on every
     * attempt: the inline half already raised it, but the process may
     * have restarted since.
     */
    raiseClaimFence: suspend () -> Unit = {},
    /**
     * Let ordinary connect claims through again - after the release and
     * the completion, before the successor is started. Called ONLY on a
     * settled or superseded obligation: while the release is still owed,
     * the lease stays shut.
     */
    lowerClaimFence: suspend () -> Unit = {},
    /**
     * Start exactly one successor, UNLESS a shutdown is in progress.
     *
     * One operation, not a question followed by an action. The switch is
     * still finished during a shutdown - released, completed, fence
     * lowered - because the obligation owns the fence and something must
     * lower it; what a stop must not do is schedule its own restart.
     *
     * No default: a caller that forgets to wire this must not silently
     * get fail-open behaviour.
     */
    startSuccessor: suspend () -> HandoffRecovery.SuccessorOutcome,
    log: ((String) -> Unit)? = null,
): SettlementOutcome = withContext(NonCancellable) {
    if (coordinator.currentEpoch() != epoch) {
        log?.invoke("SETTLE superseded epoch=$epoch live=${coordinator.currentEpoch()}")
        // Ownership makes this safe: if a newer transition has raised its
        // own fence, this call is refused and the lease stays shut.
        lowerClaimFence()
        return@withContext SettlementOutcome(SettlementPhase.Superseded)
    }
    raiseClaimFence()
    if (coordinator.hasUnclosed()) {
        return@withContext SettlementOutcome(SettlementPhase.SocketsOutstanding)
    }
    val attempt = try {
        retryTeardown()
    } catch (t: Throwable) {
        // Not WalkOutstanding: an attempt that threw reports nothing
        // about the walk, and naming a phase it did not reach would be a
        // claim the evidence does not support. The obligation stands
        // either way.
        log?.invoke("SETTLE teardown_threw epoch=$epoch error=${t::class.simpleName}")
        return@withContext SettlementOutcome(SettlementPhase.TeardownIncomplete)
    }
    if (!attempt.walkQuiesced) {
        return@withContext SettlementOutcome(SettlementPhase.WalkOutstanding)
    }
    // Re-checked after the teardown: it suspends, and a newer request can
    // land while it runs. Completing then would install a mode the new
    // switch has already moved past.
    if (coordinator.currentEpoch() != epoch) {
        log?.invoke("SETTLE superseded_after_teardown epoch=$epoch")
        lowerClaimFence()
        return@withContext SettlementOutcome(SettlementPhase.Superseded)
    }
    if (!attempt.confirmed) {
        log?.invoke("SETTLE teardown_incomplete epoch=$epoch — obligation stands")
        return@withContext SettlementOutcome(SettlementPhase.TeardownIncomplete)
    }
    val completion = coordinator.complete(
        epoch = epoch,
        policyChangeComplete = true,
        walkQuiesced = true,
    )
    if (completion is PrivacyModeChangeResult.Superseded) {
        lowerClaimFence()
        return@withContext SettlementOutcome(SettlementPhase.Superseded)
    }
    lowerClaimFence()
    when (startSuccessor()) {
        HandoffRecovery.SuccessorOutcome.Started -> Unit
        HandoffRecovery.SuccessorOutcome.Suppressed -> {
            // Everything the obligation owed has been done; the only
            // thing left out is the restart, which is the one thing a
            // stop may not do. Settled, and deliberately quiet.
            log?.invoke("SETTLE settled_without_successor epoch=$epoch cause=shutdown")
            return@withContext SettlementOutcome(SettlementPhase.Settled)
        }
        HandoffRecovery.SuccessorOutcome.Failed -> {
            log?.invoke("SETTLE successor_failed epoch=$epoch")
            return@withContext SettlementOutcome(SettlementPhase.SuccessorFailed)
        }
    }
    log?.invoke("SETTLE settled epoch=$epoch")
    SettlementOutcome(SettlementPhase.Settled)
}
