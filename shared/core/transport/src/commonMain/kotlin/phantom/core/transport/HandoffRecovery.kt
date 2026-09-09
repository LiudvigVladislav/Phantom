// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * R-N1.16 P1-3 - recovery from a fail-closed connect lease.
 *
 * When [ConnectOwnership.handOver] cannot confirm the displaced walk
 * stopped, the lease is kept and every claim is refused. That is the
 * right trade - two competing transports are worse than a brief outage -
 * but it is only defensible while something can lift it.
 *
 * ## Why the obvious signal does not reach it
 *
 * The alarm heartbeat looks like the natural trigger and is not one:
 *
 *  - the service's nudge branch returns before the claim path;
 *  - the rewalk that caused the handover has already invalidated the
 *    retry slot, so asking the scheduler first yields nothing;
 *  - after `TransportManager.release()` the manager sits in
 *    `ManagerState.Idle`, and the wakeup receiver only nudges on
 *    `AllFailed`.
 *
 * So on a stable network no signal arrives at all, and an app that went
 * fail-closed would have stayed dark permanently rather than briefly.
 * This class is the mechanism that does reach it.
 *
 * ## Why it is a class and not two coroutines in the service
 *
 * Review R-N1.16 asked for behaviour, not statement order: a source
 * tripwire showing `recoverIfBlocked` above `retryScheduler.claim` does
 * not prove that a blocked lease with an empty retry slot actually
 * recovers and starts exactly one connect. Holding this logic outside
 * the Android `Service` is what makes that chain testable.
 */
class HandoffRecovery(
    private val ownership: ConnectOwnership,
    private val scope: CoroutineScope,
    /**
     * Start ONE ordinary connect. Production sends the same intent a
     * retry attempt uses, so privacy mode is re-read, the lease is
     * claimed atomically like any other start and the backoff ladder is
     * untouched. Recovery must not become a second way into the chain
     * walk with its own rules.
     */
    /**
     * Start ONE ordinary connect. Returns whether the start actually
     * succeeded - a start that threw is not a start, and the obligation
     * to retry it must survive.
     */
    private val startOneOrdinaryConnect: (reason: String) -> Boolean,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val log: ((String) -> Unit)? = null,
    /**
     * Sweep anything else left over from a superseded policy, and report
     * whether work remains. Returns false when there is nothing left.
     *
     * R-N1.16 P1: sockets whose teardown failed during a privacy switch
     * need a driver that does not depend on the user doing something or
     * on a network nudge arriving. On a stable network neither happens,
     * and the socket would stay open for ever while new connections were
     * correctly refused - blocked and dark, which is not the trade
     * anyone agreed to.
     *
     * The timer here already has a single-live-instance guarantee, an
     * identity-safe replacement and cancellation at shutdown, so it
     * drives this too rather than a second timer being introduced beside
     * it with the same three properties to get right again.
     */
    private val sweepPending: (suspend () -> Boolean)? = null,
    /**
     * Finish a switch whose deferred half is still owed: release the
     * subsystems, complete it, and start one successor. Returns whether
     * it settled.
     */
    private val finishDeferredSwitch: (suspend (epoch: Long) -> Boolean)? = null,
    /**
     * The policy epoch in force, for checking a debt against the world it
     * was recorded in. Absent means no check - never a silent pass into
     * "the debt is stale", which would drop obligations wholesale.
     */
    private val currentPolicyEpoch: (suspend () -> Long?)? = null,
) {
    /** What happened when a successor was owed. */
    enum class SuccessorOutcome {
        /** One successor was started. */
        Started,

        /** A shutdown is in progress, so nothing was started, by design. */
        Suppressed,

        /** A start was attempted and the platform refused it. */
        Failed,
    }

    /** What a shutdown found outstanding. */
    sealed interface StandDown {
        /** Nothing was owed. The timer is stopped and that is the end of it. */
        data object Clear : StandDown

        /**
         * A settlement is owed, and it owns the claim fence.
         *
         * The shutdown discharges it as part of its OWN teardown -
         * serialised with the shutdown's release rather than racing it -
         * and reports back through [noteShutdownSettlement], quoting
         * [token] so only this claim's result is applied.
         *
         * R-N1.17 P1: this used to carry the epoch alone, and the
         * shutdown then started a worker on it unconditionally. When the
         * timer had ALREADY claimed the record and was inside its
         * teardown, that produced two workers on one obligation:
         * cancelling the timer does not stop a `NonCancellable`
         * settlement, and the transition mutex serialises the two rather
         * than collapsing them. Once the service resumed, each could
         * complete the same epoch and start its own successor.
         */
        data class SettlementOwed(
            /** Identity of the ATTEMPT this shutdown is bound to. */
            val claimToken: Long,
            val epoch: Long,
            val reason: String,
            /**
             * Non-null when a worker already owns this claim: WAIT on it
             * rather than starting a second.
             */
            val inFlight: Deferred<Boolean>?,
        ) : StandDown
    }

    /**
     * Successors are suppressed: a shutdown is in progress, or was never
     * followed by a service instance coming back up.
     *
     * R-N1.17 P1. Keeping a settlement obligation alive across
     * `onDestroy` fixed an orphaned fence and broke a contract that was
     * already written down: an explicit stop must not schedule its own
     * restart. The obligation still has to be discharged - it owns the
     * fence - but discharging it and STARTING something are two different
     * things, and only the second is forbidden here.
     */
    private var shuttingDown: Boolean = false

    /** What an incoming signal did. */
    sealed interface Signal {
        /** The lease was not blocked; the caller proceeds as normal. */
        data object NotBlocked : Signal

        /** The lease was blocked, is now free, and one connect started. */
        data class Recovered(val previousOwner: Long) : Signal

        /** Still cannot confirm the walk stopped. Nothing was started. */
        data object StillBlocked : Signal
    }

    private val lock = Mutex()

    /** The one live recovery timer, or null. */
    private var timer: Job? = null

    /**
     * A successor connect that still has to happen, with the reason.
     *
     * R-N1.17 P1: an ordinary `arm()` recovers a BLOCKED lease. When the
     * lease is free and the thing that failed was the START, that timer
     * asked `recoverIfBlocked`, got null, reported NotBlocked and exited
     * without ever starting anything. A failed start is its own work and
     * needs its own record.
     */
    /**
     * The ONE outstanding obligation, or null.
     *
     * R-N1.17: this replaced three independent flags - a start debt, a
     * settlement flag here, and a boolean in the Android container - all
     * describing the same action: once every blocker clears, start
     * exactly one successor. Three representations of one fact disagreed,
     * and they did: a stale container flag settled again over a newer
     * switch, a lease recovery and a settlement each started a successor
     * in the same tick, and a successful start left an older debt
     * standing.
     */
    private var owed: Owed? = null
    private var owedToken = 0L

    /**
     * Identity of an ATTEMPT, as distinct from identity of the obligation.
     *
     * R-N1.17 P1: `token` names the obligation and survives a claim being
     * released and taken again, so it could not tell two attempts on the
     * same record apart. A slow attempt's report therefore passed the
     * token check and released the claim belonging to the attempt that
     * had taken over - letting a third teardown start beside a second one
     * still running inside `NonCancellable`.
     */
    private var claimSeq = 0L

    private data class Owed(
        val token: Long,
        val epoch: Long,
        val reason: String,
        /** Whether the full deferred half is owed, or only a start. */
        val needsSettlement: Boolean,
        /**
         * A worker has claimed this record and is acting on it.
         *
         * The record stays VISIBLE while in flight - the same lesson as
         * the permit register during a revoke. Hiding it would let a
         * lease recovery conclude that no settlement was owed and start a
         * bare successor beside the one the settlement is about to.
         */
        val inFlight: Boolean = false,
        /**
         * Identity of the attempt currently holding this record, or 0
         * when nobody holds it. Minted fresh by every [claimLocked].
         */
        val claimToken: Long = 0L,
        /**
         * Completed by whoever resolves this claim, with the result.
         *
         * R-N1.17 P1: a claim used to be observable only by its owner, so
         * anything else that wanted the obligation finished had no choice
         * but to start a SECOND worker on it. Cancelling the first is not
         * an option - it runs inside `NonCancellable` precisely so a
         * half-run teardown cannot happen - so the only safe way to want
         * the same work twice is to wait on the attempt that is already
         * running.
         */
        val completion: CompletableDeferred<Boolean>? = null,
    )

    /**
     * Take ownership of the outstanding obligation, if it is free.
     *
     * R-N1.17 P1: reading the record under the lock, releasing it, and
     * then acting is check-then-act - the exact shape this round exists
     * to remove, one layer up from the ownership defect. A worker that
     * acts on a record it has not claimed can have its result applied to
     * a DIFFERENT obligation that arrived in the gap.
     */
    private fun claimLocked(): Owed? {
        val current = owed ?: return null
        if (current.inFlight) return null
        claimSeq += 1
        return current.copy(
            inFlight = true,
            claimToken = claimSeq,
            completion = CompletableDeferred(),
        ).also { owed = it }
    }

    /**
     * Guarantee the standing invariant: an obligation that nobody is
     * working on has something that will try it again.
     *
     * R-N1.17 P1. This used to be every caller's job, and the caller that
     * mattered could not do it - the timer's loop re-arms nothing,
     * because `standDown` had already cancelled it, so a teardown that
     * finished unsettled left the record present, the claim fence raised
     * and no driver at all.
     *
     * Putting it where the claim is RELEASED covers every route into that
     * state, and needs no caller to remember. A live timer is already the
     * driver, so this does not disturb one - re-arming from inside the
     * timer's own loop would cancel that loop mid-flight.
     */
    private fun ensureDriverLocked(reason: String) {
        if (owed == null) return
        if (timer?.isActive == true) return
        log?.invoke("RECOVERY driver_ensured reason=$reason")
        armLocked(reason)
    }

    /**
     * Apply a worker's result - but only to the record it claimed.
     *
     * Compare-and-set on the token. Without it a slow start from a
     * superseded switch could clear a newer debt on success, or overwrite
     * a newer SETTLEMENT with `needsSettlement = false` on failure -
     * silently downgrading a full deferred half to a bare start.
     */
    private fun resolveLocked(claim: Owed, done: Boolean) {
        // Always signalled, even when the CAS fails: anyone waiting on
        // this attempt must learn it is over, or they wait for ever.
        claim.completion?.complete(done)
        val live = owed
        // CAS on the ATTEMPT, not on the obligation. The record can be
        // released and claimed again without its token changing, and a
        // slow attempt reporting then would release someone else's claim.
        if (live?.claimToken != claim.claimToken) {
            log?.invoke(
                "RECOVERY resolve_ignored claim=${claim.claimToken} " +
                    "live=${live?.claimToken} done=$done",
            )
            return
        }
        if (done) {
            owed = null
            return
        }
        owed = live.copy(inFlight = false, claimToken = 0L, completion = null)
        ensureDriverLocked("obligation_still_owed")
    }

    /**
     * The EXTERNAL entry point - a start, a retry attempt, the alarm
     * heartbeat's nudge.
     *
     * Safe to call unconditionally: when nothing is blocked it does
     * nothing and reports [Signal.NotBlocked].
     *
     * Kept separate from the timer's own [attempt] on purpose. The two
     * are independent routes to the same recovery and must be able to
     * fail independently: on a stable network no nudge ever arrives and
     * only the timer runs, while a nudge that does arrive must work even
     * if the timer was never armed. A single shared entry point would
     * make them indistinguishable to a mutation, and review R-N1.16
     * asked for exactly that distinction.
     */
    suspend fun onSignal(source: String): Signal = attempt(source)

    /**
     * Record whether a start actually happened.
     *
     * R-N1.17 P1: the lease can be recovered and the platform can still
     * refuse to start the service. Reporting Recovered and walking away
     * left the app with a free lease and no transport - so a failure here
     * becomes an owed start, which the timer retries.
     */
    /**
     * Start a successor unless a settlement is going to.
     *
     * R-N1.17 P1: when a late socket close and a confirmed walk landed in
     * the same tick, the lease recovery started one successor and the
     * settlement started another - and the second could hand over the
     * first. The settlement owns the single start whenever it is owed.
     */
    private suspend fun startUnlessSettlementOwnsIt(reason: String) {
        lock.withLock {
            if (shuttingDown) {
                log?.invoke("RECOVERY start_suppressed reason=$reason cause=shutdown")
                return@withLock
            }
            if (owed?.needsSettlement == true) {
                log?.invoke("RECOVERY start_deferred_to_settlement reason=$reason")
                return@withLock
            }
            // The decision and the act are ONE critical section.
            //
            // `startOneOrdinaryConnect` sends an intent and does not
            // suspend, so holding the record lock across it costs nothing
            // and closes the window in which a settlement armed between
            // the read and the start would get a bare successor started
            // beside the one it is about to start itself.
            recordStartOutcomeLocked(reason, startOneOrdinaryConnect(reason))
        }
    }

    private fun recordStartOutcomeLocked(reason: String, started: Boolean) {
        if (started) {
            // A successful start discharges any START debt. Leaving it
            // standing meant a later arm sent another successor. A
            // settlement debt is NOT discharged: it still owes a release
            // and a completion.
            owed?.let { if (!it.needsSettlement) owed = null }
            return
        }
        log?.invoke("RECOVERY start_failed reason=$reason — owed")
        owedToken += 1
        // R-N1.17 P1: epoch 0 - "no epoch" - and NOT the last epoch some
        // arm happened to record.
        //
        // This is an ordinary connect that failed to start. It is not tied
        // to a privacy switch and never was. Stamping it with a leftover
        // `currentEpoch` made it look like a debt from a switch that had
        // already been superseded, and the timer then discarded it as
        // stale without ever retrying: debt from epoch 1, a later switch
        // to epoch 2 succeeds, `noteSuccessorStarted(2)` clears the old
        // record but leaves the field at 1, and the NEXT failed start
        // inherits 1 and is deleted unretried.
        owed = Owed(owedToken, epoch = 0L, reason = reason, needsSettlement = false)
        // And it needs a driver. Reached from `onSignal` - a nudge, an
        // alarm heartbeat - there is no timer running, so recording the
        // debt without arming one left it owed to nobody. Same invariant
        // as every other place a record is left standing: if nothing is
        // working on it, something must be able to try again.
        ensureDriverLocked("ordinary_start_failed")
    }

    /**
     * Whether a start-only debt belongs to a policy epoch that is gone.
     *
     * Epoch 0 means "no epoch was supplied" - an ordinary retry unrelated
     * to a privacy switch - and is never stale.
     *
     * R-N1.17 P2: the provider returns null for "I cannot tell you", and
     * that must NOT read as stale. Production could not answer precisely
     * when the application container was not up - which is when a start
     * had most likely just failed - so an unknown epoch would have
     * discharged the very debt that existed to fix it. Unknown means the
     * obligation stands.
     */
    private suspend fun isStale(work: Owed): Boolean {
        if (work.needsSettlement || work.epoch == 0L) return false
        val live = currentPolicyEpoch?.invoke() ?: return false
        return live != work.epoch
    }

    private suspend fun attempt(source: String): Signal {
        val outcome = ownership.recoverIfBlocked(source) ?: return Signal.NotBlocked
        return when (outcome) {
            // R-N1.17 P1: NO cancel() here. One timer carries three
            // jobs - a blocked lease, unclosed sockets, and an owed start
            // - and recovering the lease used to stand the whole thing
            // down, leaving the other two with no driver. The loop ends
            // itself when nothing is left.
            is Handover.Quiesced -> {
                log?.invoke("RECOVERY recovered source=$source previousOwner=${outcome.previousOwner}")
                startUnlessSettlementOwnsIt(source)
                Signal.Recovered(outcome.previousOwner)
            }
            Handover.NothingToStop -> {
                log?.invoke("RECOVERY recovered source=$source previousOwner=none")
                startUnlessSettlementOwnsIt(source)
                Signal.Recovered(-1L)
            }
            else -> {
                log?.invoke("RECOVERY still_blocked source=$source outcome=$outcome")
                Signal.StillBlocked
            }
        }
    }

    /**
     * Hand the lease over, and arm recovery if that could not be
     * confirmed. Returns true only on confirmed quiescence.
     *
     * R-N1.16 P1: this exists because arming was unreachable from the
     * path that actually times out. The rewalk coordinator abandons the
     * rewalk on a failed handover - no release, no service restart - and
     * the only `arm()` call site sat inside the service branch that a
     * restart intent triggers. So the one situation that needs recovery
     * was the one situation that could not reach it, and on a stable
     * network the lease would have stayed blocked for good.
     *
     * Pairing the two here means every caller of the handover gets the
     * arming for free, in the path that runs.
     */
    suspend fun handOverOrArm(reason: String): Boolean =
        when (val outcome = ownership.handOver(reason)) {
            is Handover.Quiesced, Handover.NothingToStop -> {
                // R-N1.17 P1: do NOT cancel the timer here. A recovery
                // armed a moment earlier for unclosed SOCKETS would be
                // destroyed by a handover that only settled the LEASE -
                // two different jobs, one timer. The loop already stands
                // itself down when neither has work left.
                true
            }
            else -> {
                log?.invoke("RECOVERY arming_after_failed_handover reason=$reason outcome=$outcome")
                arm(reason)
                false
            }
        }

    /**
     * Arm - or re-arm - the recovery timer. Exactly one is ever live.
     *
     * Does NOT join the timer it replaces. Joining here would deadlock:
     * the outgoing timer takes this same lock on its way out.
     */
    /**
     * Arm the timer for a successor connect that failed to start.
     *
     * Distinct from [arm]: nothing is blocked, so there is nothing to
     * recover - there is simply a connect that has to happen and did not.
     */
    suspend fun armStartRetry(reason: String, epoch: Long = 0L) {
        // Record AND timer under one lock. See [armLocked].
        lock.withLock {
            owedToken += 1
            owed = Owed(owedToken, epoch, reason, needsSettlement = false)
            armLocked(reason)
        }
    }

    /**
     * Arm the deferred half of a switch whose sockets did not close.
     *
     * When they finally do, [finishDeferredSwitch] runs the rest of the
     * transaction: releasing the subsystems, completing the switch and
     * starting exactly one successor. Without it a late close left the
     * app with the sockets shut, the lease free, and nothing running.
     */
    suspend fun armDeferredSettlement(reason: String, epoch: Long) {
        // Record AND timer under one lock. See [armLocked].
        lock.withLock {
            owedToken += 1
            owed = Owed(owedToken, epoch, reason, needsSettlement = true)
            armLocked(reason)
        }
    }

    /**
     * A switch started its own successor, so an older start-only debt
     * describes work that has now been done.
     *
     * R-N1.17 P2: `armStartRetry` recorded an epoch and nothing ever
     * compared it. Start A is refused and leaves a debt; switch B then
     * starts the service successfully; A's timer still fires an extra
     * retry, outside the ordinary backoff ladder. A successful start is
     * the discharge, and it has to be reported for the debt to know.
     *
     * A SETTLEMENT debt is untouched: it still owes a release and a
     * completion that this successor did not perform. An in-flight
     * record is cleared too - the worker's CAS then finds no record of
     * its own token and applies nothing, which is the intended outcome.
     */
    suspend fun noteSuccessorStarted(epoch: Long) {
        lock.withLock {
            val debt = owed ?: return@withLock
            if (debt.needsSettlement) return@withLock
            if (debt.epoch > epoch) return@withLock
            log?.invoke(
                "RECOVERY start_debt_discharged debtEpoch=${debt.epoch} startedEpoch=$epoch",
            )
            owed = null
        }
    }

    suspend fun arm(reason: String) = lock.withLock { armLocked(reason) }

    /**
     * Install the one live timer. The caller must hold [lock].
     *
     * R-N1.17 P1: arming used to be two separate lock acquisitions -
     * write the record, release, then take the lock again to install the
     * timer. `cancel()` could land in that gap, clear the record and stop
     * the timer, and the arm would then install a fresh timer AFTER the
     * shutdown - one that still sweeps, still recovers the lease, and can
     * start the service again.
     *
     * A lifecycle generation is the other way to close this, and it is
     * the wrong one here: this component is a companion-object singleton
     * that deliberately outlives any one service instance, so a flag set
     * by `onDestroy` would refuse to arm for a legitimately recreated
     * service. Making the pair atomic needs no such flag.
     */
    private fun armLocked(reason: String) {
        run {
            timer?.cancel()
            timer = scope.launch {
                val me = currentCoroutineContext()[Job]
                try {
                    while (isActive) {
                        delay(intervalMs)
                        // Both jobs mean "something from a superseded
                        // policy is still live", so the loop runs while
                        // EITHER has work left.
                        val stillSweeping = sweepPending?.invoke() ?: false
                        val leaseSignal = attempt("recovery_timer_$reason")
                        // Every blocker gone: the lease is not blocked and
                        // no socket from a superseded policy is left.
                        val clear = leaseSignal != Signal.StillBlocked && !stillSweeping
                        // A blocker is still up: keep the timer alive and
                        // do not touch the obligation.
                        if (!clear) continue
                        // One obligation, whatever it is - a full deferred
                        // settlement or just a start that failed - and it
                        // is CLAIMED before any work is done on it.
                        val work = lock.withLock { claimLocked() }
                        if (work == null) {
                            // Either nothing is owed, or another worker
                            // already owns it. Only the first means this
                            // timer has finished its job.
                            if (lock.withLock { owed == null }) return@launch
                            continue
                        }
                        var done = false
                        try {
                            done = if (work.needsSettlement) {
                                log?.invoke(
                                    "RECOVERY settling reason=${work.reason} epoch=${work.epoch}",
                                )
                                // Absent wiring means NOT settled:
                                // deleting one line must not drop the
                                // deferred half silently.
                                finishDeferredSwitch?.invoke(work.epoch) ?: false
                            } else if (isStale(work)) {
                                // R-N1.17 P2: a debt belongs to the epoch
                                // that created it. Once the policy has
                                // moved on, the successor it describes is
                                // not the one anyone wants, and firing it
                                // sends an extra start outside the
                                // backoff ladder.
                                log?.invoke(
                                    "RECOVERY start_debt_stale reason=${work.reason} " +
                                        "epoch=${work.epoch}",
                                )
                                true
                            } else {
                                log?.invoke("RECOVERY retrying_start reason=${work.reason}")
                                startOneOrdinaryConnect(work.reason)
                            }
                        } finally {
                            // The claim is ALWAYS released, and the
                            // release is non-cancellable.
                            //
                            // A settlement re-arms recovery through
                            // `handOverOrArm`, and arming cancels the
                            // timer it replaces - which here is this very
                            // coroutine. A cancellable resolve would then
                            // never run, leaving the record marked
                            // in-flight for ever: the replacement timer
                            // would refuse to claim it and spin, and the
                            // deferred half would never be finished by
                            // anyone. Only the claim's own token may
                            // change the record.
                            withContext(NonCancellable) {
                                lock.withLock { resolveLocked(work, done) }
                            }
                        }
                        if (done) return@launch
                    }
                } finally {
                    // Clear the slot ONLY if it still points at this job.
                    //
                    // R-N1.16: a re-arm cancels the previous timer, and
                    // that timer's teardown runs afterwards. An
                    // unconditional `timer = null` there would wipe the
                    // reference to the timer that replaced it - the same
                    // check-then-act shape as the ownership defect this
                    // whole round is about, one layer up.
                    withContext(NonCancellable) {
                        lock.withLock { if (timer === me) timer = null }
                    }
                }
            }
            log?.invoke("RECOVERY armed reason=$reason intervalMs=$intervalMs")
        }
    }

    /**
     * Stand the recovery down for a shutdown, and report what is still
     * owed.
     *
     * ALWAYS stops the timer, and from here no successor is started until
     * [resume]. That is the shutdown contract, written down long before
     * this round: an explicit stop must not schedule its own restart, and
     * recovery starts the service unconditionally rather than through the
     * scheduler.
     *
     * A SETTLEMENT obligation is not discarded, because it owns the claim
     * fence and the fence lives in a process-wide [ConnectOwnership] that
     * outlives the service instance - dropping it left every later claim
     * refused for good. But keeping its TIMER alive was the wrong way to
     * preserve it: that timer would then run the whole teardown beside
     * the shutdown's own, lower the fence and start a successor, so an
     * explicit stop restarted the service and two teardowns raced.
     *
     * So the obligation is handed to the shutdown instead. The caller
     * discharges it inside its own teardown - one worker, serialised -
     * and reports back through [noteShutdownSettlement].
     */
    suspend fun standDown(reason: String): StandDown = lock.withLock {
        shuttingDown = true
        if (timer != null) {
            log?.invoke("RECOVERY stood_down reason=$reason")
        }
        timer?.cancel()
        timer = null
        val debt = owed
        if (debt == null || !debt.needsSettlement) {
            // A start-only debt holds nothing open. A stop is precisely
            // the moment it stops being wanted.
            owed = null
            return@withLock StandDown.Clear
        }
        if (debt.inFlight) {
            // Someone is already on it. The shutdown waits for THAT
            // attempt; a second worker on one obligation is exactly what
            // the claim protocol exists to prevent.
            log?.invoke(
                "RECOVERY stand_down_settlement_in_flight reason=$reason " +
                    "token=${debt.token} epoch=${debt.epoch}",
            )
            return@withLock StandDown.SettlementOwed(
                debt.claimToken, debt.epoch, debt.reason, debt.completion,
            )
        }
        val claimed = claimLocked() ?: return@withLock StandDown.Clear
        log?.invoke(
            "RECOVERY stand_down_settlement_claimed reason=$reason " +
                "token=${claimed.token} epoch=${claimed.epoch}",
        )
        StandDown.SettlementOwed(claimed.claimToken, claimed.epoch, claimed.reason, null)
    }

    /**
     * Report what the shutdown's own attempt at the obligation achieved.
     *
     * Settled: the record goes. Not settled: it stands, and it gets a
     * driver back - the claim fence is still up and something has to be
     * able to lower it, or the lease is shut for good. That driver starts
     * no successor while [shuttingDown] holds, so this is a recovery
     * path, not a restart.
     */
    suspend fun noteShutdownSettlement(claimToken: Long, settled: Boolean) {
        lock.withLock {
            val debt = owed
            // The same CAS the timer's resolve uses, and on the same
            // thing: the ATTEMPT. Checking the obligation's token let a
            // slow attempt release the claim of the one that replaced it.
            if (debt?.claimToken != claimToken) {
                log?.invoke(
                    "RECOVERY shutdown_settlement_ignored claim=$claimToken " +
                        "live=${debt?.claimToken}",
                )
                return@withLock
            }
            debt.completion?.complete(settled)
            if (settled) {
                log?.invoke("RECOVERY shutdown_settled claim=$claimToken epoch=${debt.epoch}")
                owed = null
                return@withLock
            }
            log?.invoke(
                "RECOVERY shutdown_settlement_failed claim=$claimToken " +
                    "epoch=${debt.epoch} — the obligation stands",
            )
            owed = debt.copy(inFlight = false, claimToken = 0L, completion = null)
            ensureDriverLocked("shutdown_settlement_owed")
        }
    }

    /**
     * The shutdown stopped WAITING; the work itself is still running and
     * still owns the claim.
     *
     * Arms a driver so the claim fence can still come down, and does NOT
     * release the claim: releasing it would let the freshly armed timer
     * take the same obligation and run a second teardown beside the one
     * still in flight. The running attempt resolves the record when it
     * finishes, and the timer then finds nothing owed and stands itself
     * down.
     */
    suspend fun noteShutdownOverBudget(claimToken: Long) {
        lock.withLock {
            val debt = owed
            if (debt?.claimToken != claimToken) {
                log?.invoke(
                    "RECOVERY shutdown_over_budget_ignored claim=$claimToken " +
                        "live=${debt?.claimToken}",
                )
                return@withLock
            }
            // The attempt is still running and still owns the claim, so
            // [ensureDriverLocked] would decline: it only acts on a record
            // nobody holds. This one arms deliberately, because that
            // attempt may never return at all.
            log?.invoke(
                "RECOVERY shutdown_over_budget claim=$claimToken epoch=${debt.epoch} " +
                    "— the attempt keeps the claim; arming a driver only",
            )
            armLocked("shutdown_over_budget")
        }
    }

    /**
     * A service instance is up again, so successors are wanted once more.
     *
     * Re-arms the timer when an obligation is still outstanding: the
     * shutdown either could not discharge it or handed it forward.
     */
    suspend fun resume(reason: String) {
        lock.withLock {
            if (!shuttingDown && timer != null) return@withLock
            shuttingDown = false
            val debt = owed
            if (debt != null) {
                log?.invoke("RECOVERY resumed reason=$reason owed=${debt.reason}")
                armLocked("resumed_$reason")
            } else {
                log?.invoke("RECOVERY resumed reason=$reason owed=none")
            }
        }
    }

    /**
     * Whether successors are currently suppressed. For assertions and
     * logging only.
     *
     * NOT for deciding whether to start one: see
     * [startSuccessorUnlessShutDown]. Asking this and then acting on the
     * answer is the check-then-act shape this whole round exists to
     * remove - a `standDown` landing in the gap leaves an explicit stop
     * restarting the service, which is exactly the contract being
     * protected.
     */
    suspend fun isShutDown(): Boolean = lock.withLock { shuttingDown }

    /**
     * Start one successor unless a shutdown is in progress - as ONE
     * critical section.
     *
     * [start] is deliberately not a suspending function: production sends
     * an intent, which does not suspend, so holding the record lock
     * across it costs nothing and closes the window. A suspending start
     * could not be made atomic this way and would be the wrong shape
     * here.
     */
    suspend fun startSuccessorUnlessShutDown(start: () -> Boolean): SuccessorOutcome =
        lock.withLock {
            if (shuttingDown) {
                log?.invoke("RECOVERY successor_suppressed cause=shutdown")
                return@withLock SuccessorOutcome.Suppressed
            }
            if (start()) SuccessorOutcome.Started else SuccessorOutcome.Failed
        }

    /** Whether a recovery timer is currently live. For assertions. */
    suspend fun isArmed(): Boolean = lock.withLock { timer?.isActive == true }

    /** How many timers this instance has ever created. For assertions. */
    suspend fun liveTimer(): Job? = lock.withLock { timer }

    companion object {
        /**
         * How often a fail-closed lease retries its handover.
         *
         * Short, because the app has no transport at all while the block
         * stands, and each attempt is only a cancel-and-join against a
         * walk that is usually already finished. It does not back off:
         * the cost of checking is trivial next to staying dark.
         */
        const val DEFAULT_INTERVAL_MS: Long = 15_000L
    }
}
