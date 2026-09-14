// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stage 2 B7b: the single decision point for transport recovery.
 *
 * Everything that used to decide for itself -- the alarm receiver reading
 * `ManagerState`, the retry timer claiming and sending an Intent, the
 * rewalk coordinator abandoning silently, an `onStartCommand` running
 * unregistered -- is now a TRIGGER that calls [ensureRecoveryProgress].
 * This is the only place that sees the shutdown flag, the privacy fence,
 * the Tor obligation, the connect lease, the handoff recovery, the start
 * job, the live WSS session and REST health together, and therefore the
 * only place that can tell "recovery is already under way" from "nothing
 * is happening".
 *
 * **Why this is a class and not service methods.** Review round 8
 * (2026-09-13) found an external-start race that had passed static review
 * precisely because the coordinator's contract was pinned only by
 * source-scanning tripwires: constructing `PhantomMessagingService` in a
 * unit test is not practical, so nothing could exercise two concurrent
 * starts, the unlock wait, the overdue cancel/join, the grant restore or
 * the spacing wake-up. Everything the coordinator needs from the Android
 * side is injected here as a function or an [Environment] probe, so the
 * whole contract is reachable from `commonTest` with virtual time and
 * barriers. `TransportRecoveryCoordinatorTest` is that fixture.
 *
 * Thread-safety: every decision runs under [mutex]. The one operation
 * that must NOT run under it is `cancelAndJoin` of a start job, because
 * that job's completion handler takes the same mutex; the two-phase shape
 * in [ensureRecoveryProgress] is what keeps them apart.
 */
class TransportRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
    private val ownership: ConnectOwnership,
    private val handoffRecovery: HandoffRecovery,
    private val retryScheduler: ConnectRetryScheduler,
    private val connectGeneration: () -> Long,
    private val environment: Environment,
    /**
     * Runs one start attempt. The Android side supplies the real one,
     * which admits through the startup CAS, waits for the container and
     * the device unlock, reads identity and walks the chain.
     *
     * [grantStamp] identifies the coordinator's attempt so the attempt can
     * report its prerequisites and its ownership claim back against the
     * right stamp. It is never null: an attempt that is not registered
     * does not run at all (review round 8, finding 2).
     */
    private val runStartAttempt: suspend (
        isRetryAttempt: Boolean,
        rewalkReason: String?,
        grantStamp: Long,
    ) -> Unit,
    private val startBudgetMs: Long = START_BUDGET_MS,
) {

    /**
     * The facts the coordinator cannot observe for itself because they
     * live behind the Android container. Each is read under [mutex]
     * during a decision, so an implementation must not take a lock that
     * the coordinator's own callers already hold.
     */
    interface Environment {
        /** The service is being destroyed; nothing may be started. */
        suspend fun isShuttingDown(): Boolean

        /**
         * B7c: a walk left a Tor generation unsettled and nobody has
         * confirmed the host let go. Fail-closed when it cannot be
         * checked.
         */
        suspend fun torObligationBlocks(): Boolean

        /** The live WSS session epoch, if the transport has one. */
        suspend fun liveSessionEpoch(): Long?

        /** Whether REST is currently a usable delivery path (B8). */
        suspend fun restUsable(): Boolean
    }

    /** The grant a live start job is acting on, with the attempt that owns it. */
    private data class InFlightGrant(
        val token: ConnectRetryScheduler.ClaimToken,
        val stamp: Long,
    )

    /** What the decision phase concluded. Only one shape needs work off the lock. */
    internal sealed interface RecoveryStep {
        /** Nothing to do: recovery is blocked, already active, or not owed. */
        data object None : RecoveryStep

        /**
         * A start job outlived [startBudgetMs] after its prerequisites
         * were ready. It is detached under the lock; the cancel, the join
         * and only then the restore happen off it.
         */
        data class CancelStaleStart(
            val job: Job,
            val token: ConnectRetryScheduler.ClaimToken?,
            val stamp: Long,
        ) : RecoveryStep
    }

    /** How an external start was admitted. */
    private sealed interface ExternalAdmission {
        /** This attempt is the single start job; [stamp] identifies it. */
        data class Registered(val stamp: Long) : ExternalAdmission

        /** Another attempt owns the slot; wait for its result and start nothing. */
        data class Joins(val existing: Job) : ExternalAdmission
    }

    private val mutex = Mutex()

    private var inFlightGrant: InFlightGrant? = null

    /**
     * The one start job this coordinator may have (S1).
     *
     * `@Volatile` because [shutdownNow] reads it from Android's main
     * thread, which cannot suspend to take [mutex]. Every other access is
     * under the mutex.
     */
    @Volatile
    private var startJob: Job? = null

    /** Identifies the current start attempt; a superseded attempt reports nothing. */
    private var startAttemptStamp: Long = 0L

    /** Whether the live start job has claimed the connect lease. */
    private var startJobClaimedOwnership: Boolean = false

    /**
     * When the live start job's prerequisites became ready (container up,
     * device unlocked). Null while it is still waiting -- L1 state (e),
     * `WaitingForUnlock`, which is NOT a vacuum and has no budget.
     */
    private var startPrerequisitesReadyAtMs: Long? = null

    /**
     * When this coordinator last launched a start attempt. Two attempts
     * inside one reaction budget cannot both be useful: the second would
     * be refused by the startup CAS while the first is still admitting,
     * and a grant restored due-now would otherwise be re-granted by the
     * very next trigger -- a hot loop of starts that never begin. Spacing
     * them does not weaken L1: the restored slot is pending and due, which
     * is clause (c) of the liveness condition.
     */
    private var lastStartAttemptAtMs: Long? = null

    /**
     * The local timer that re-enters the coordinator when the spacing
     * interval elapses. Without it a grant restored due-now would wait for
     * the alarm heartbeat, whose delivery Doze and OEM battery managers
     * can defer without bound -- the coordinator would have decided
     * "later" and then had no way to reach that later.
     */
    @Volatile
    private var spacingWakeJob: Job? = null

    private val _startJobPending = MutableStateFlow(false)

    /**
     * B9: a start job that is alive and has not claimed the lease is L1
     * state (e). Presentation shows `Reconnecting` / `Recovering` there,
     * never `Offline` (I5).
     */
    val startJobPending: StateFlow<Boolean> = _startJobPending.asStateFlow()

    // ── Triggers ─────────────────────────────────────────────────────────

    /**
     * The single entry point. Safe to call from anywhere, as often as
     * anything likes.
     */
    suspend fun ensureRecoveryProgress(trigger: String) {
        when (val step = mutex.withLock { decideRecoveryLocked(trigger) }) {
            RecoveryStep.None -> return
            is RecoveryStep.CancelStaleStart -> {
                // OFF the lock, deliberately. The job's completion handler
                // takes [mutex]; joining it while holding the mutex would
                // deadlock the coordinator against the very job it is
                // trying to retire. Two-phase: detached under the lock
                // above, cancelled and joined here, restored under the lock
                // again below -- and only if it is still ours.
                log("RETRY_TRACE start_job_overdue trigger=$trigger cancelling_and_joining=1")
                // The caller's own cancellation is NOT swallowed. `join`
                // throws only when THIS coroutine is cancelled, and in that
                // case the join is not confirmed, so nothing may be
                // restored on the strength of it. The grant is not lost:
                // the attempt's own completion handler still holds the same
                // stamp and restores it there.
                step.job.cancelAndJoin()
                val restored = mutex.withLock {
                    val token = step.token
                    val owner = ownership.currentOwner()
                    when {
                        token == null -> false
                        step.stamp != startAttemptStamp -> false // superseded meanwhile
                        startJob != null -> false // a newer attempt owns the slot
                        startJobClaimedOwnership -> false // it claimed after all
                        owner != null -> false // someone is walking; the grant is not owed back yet
                        else -> {
                            inFlightGrant = null
                            publishStartJobPendingLocked()
                            retryScheduler.restoreAfterFailedStart(token, "start_job_overdue")
                        }
                    }
                }
                log("RETRY_TRACE start_job_overdue_restored=$restored")
                ensureRecoveryProgress("after_overdue_start")
            }
        }
    }

    /**
     * An externally triggered start (`onStartCommand`).
     *
     * Review round 8, finding 2. This used to register when the slot was
     * free and run ANYWAY when it was not, which left an attempt the
     * coordinator could not see: clause (5) reported no start job, the
     * unregistered attempt could win the startup CAS, and the registered
     * one could then finish while the invisible one sat waiting for the
     * device unlock. The admission is now total -- an attempt either
     * becomes the single start job before it does anything, or it does
     * nothing and waits for the attempt that did.
     *
     * The caller must launch this with [CoroutineStart.UNDISPATCHED] so
     * the registration happens synchronously, before the launching thread
     * returns; otherwise there is a window in which the platform has asked
     * for a start and the coordinator cannot see one.
     */
    suspend fun runExternalStart(isRetryAttempt: Boolean, rewalkReason: String?) {
        val me = currentCoroutineContext()[Job]
        val admission = mutex.withLock {
            val existing = startJob
            if (existing != null && existing !== me && existing.isActive) {
                ExternalAdmission.Joins(existing)
            } else {
                val next = ++startAttemptStamp
                startJob = me
                inFlightGrant = null
                startJobClaimedOwnership = false
                startPrerequisitesReadyAtMs = null
                lastStartAttemptAtMs = nowMs()
                publishStartJobPendingLocked()
                log("RETRY_TRACE external_start_registered stamp=$next")
                ExternalAdmission.Registered(next)
            }
        }
        when (admission) {
            is ExternalAdmission.Joins -> {
                // Not a drop and not a second walk: this trigger waits for
                // the attempt that owns the slot, so its caller observes a
                // start that really happened.
                log("RETRY_TRACE external_start_joined reason=start_job_already_live")
                admission.existing.join()
            }
            is ExternalAdmission.Registered -> {
                try {
                    runStartAttempt(isRetryAttempt, rewalkReason, admission.stamp)
                } finally {
                    withContext(NonCancellable) { onStartJobCompleted(admission.stamp) }
                }
            }
        }
    }

    /** The start job's prerequisites are ready: the budget runs from here. */
    suspend fun noteStartPrerequisitesReady(grantStamp: Long?) {
        if (grantStamp == null) return
        mutex.withLock {
            if (grantStamp != startAttemptStamp) return@withLock
            startPrerequisitesReadyAtMs = nowMs()
            log("RETRY_TRACE start_prerequisites_ready stamp=$grantStamp")
        }
    }

    /** The start job claimed the lease: its grant became a walk and cannot be restored. */
    suspend fun noteStartJobClaimedOwnership(grantStamp: Long?) {
        if (grantStamp == null) return
        mutex.withLock {
            if (grantStamp != startAttemptStamp) return@withLock
            startJobClaimedOwnership = true
            inFlightGrant = null
            publishStartJobPendingLocked()
            log("RETRY_TRACE grant_consumed_by_claim stamp=$grantStamp")
        }
    }

    /**
     * Shutdown from a NON-suspending context: Android's `onDestroy`.
     *
     * It deliberately does not take [mutex]. `onDestroy` runs on the main
     * thread and must not block, and a shutdown that waited behind a
     * decision in flight would do exactly that. It only CANCELS, which is
     * safe to do concurrently; nothing new can start behind it because
     * [Environment.isShuttingDown] refuses every decision at clause (1).
     */
    fun shutdownNow() {
        spacingWakeJob?.cancel()
        spacingWakeJob = null
        startJob?.cancel()
        _startJobPending.value = false
    }

    // ── The decision ─────────────────────────────────────────────────────

    /** Caller holds [mutex]. Returns the work that must happen off it. */
    private suspend fun decideRecoveryLocked(trigger: String): RecoveryStep {
        // (1) + (2)
        val blocked = blockedReasonLocked()
        if (blocked != null) {
            log("RETRY_TRACE recovery_blocked trigger=$trigger reason=$blocked")
            return RecoveryStep.None
        }
        // (3) someone owns the walk, or a handover is deciding its fate.
        val owner = ownership.currentOwner()
        if (owner != null || ownership.blockedReason() != null) {
            logActive(trigger, "owner_or_handover", owner)
            return RecoveryStep.None
        }
        // (4) the handoff recovery is driving.
        if (handoffRecovery.isArmed()) {
            logActive(trigger, "handoff_recovery_armed", null)
            return RecoveryStep.None
        }
        // (5) a start job is alive. Waiting for the device unlock is a
        // LIVE attempt, not a stalled one: the start suspends in the
        // unlock gate for as long as the phone stays locked, and a second
        // attempt would be refused by the startup CAS and would drop
        // whatever grant it carried.
        val job = startJob
        if (job != null && job.isActive) {
            val readyAt = startPrerequisitesReadyAtMs
            if (readyAt == null) {
                logActive(trigger, "start_job_waiting_for_prerequisites", null)
                return RecoveryStep.None
            }
            val elapsed = nowMs() - readyAt
            if (elapsed <= startBudgetMs) {
                logActive(trigger, "start_job_within_budget_${elapsed}ms", null)
                return RecoveryStep.None
            }
            // Overdue. Detach the JOB here so clause (5) stops reporting
            // it as live, and carry the stamp forward UNCHANGED: the
            // attempt still owns its grant, and if it claims the lease
            // while we are joining, [noteStartJobClaimedOwnership] must
            // still match and record it. Bumping the stamp here was the
            // defect -- it made that late claim invisible and let the
            // grant be restored under a walk that had just started.
            startJob = null
            startPrerequisitesReadyAtMs = null
            publishStartJobPendingLocked()
            return RecoveryStep.CancelStaleStart(job, inFlightGrant?.token, startAttemptStamp)
        }
        // (6) the transport layer owns liveness while a session is live.
        val liveSession = environment.liveSessionEpoch()
        if (liveSession != null) {
            logActive(trigger, "live_session_$liveSession", null)
            return RecoveryStep.None
        }
        // Spacing, before anything is claimed: claiming and then refusing
        // to start would consume a grant for nothing.
        val lastAttempt = lastStartAttemptAtMs
        if (lastAttempt != null) {
            val sinceMs = nowMs() - lastAttempt
            if (sinceMs < startBudgetMs) {
                val remainingMs = startBudgetMs - sinceMs
                scheduleSpacingWakeLocked(remainingMs)
                log(
                    "RETRY_TRACE recovery_deferred trigger=$trigger reason=start_spacing " +
                        "sinceLastMs=$sinceMs wakeInMs=$remainingMs",
                )
                return RecoveryStep.None
            }
        }
        val generation = connectGeneration()
        // (7) a retry is scheduled: claim it if it is due.
        when (val claim = retryScheduler.claim(null, trigger, generation)) {
            is ConnectRetryScheduler.Claim.Granted -> {
                startGrantedAttemptLocked(claim.token, trigger)
                return RecoveryStep.None
            }
            is ConnectRetryScheduler.Claim.NotDue -> {
                log(
                    "RETRY_TRACE recovery_active trigger=$trigger reason=retry_pending " +
                        "remainingMs=${claim.remainingMs}",
                )
                return RecoveryStep.None
            }
            else -> Unit // NotPending / Stale / StaleGeneration: nothing is scheduled
        }
        // (8) a true vacuum: no owner, no handover, no start job, no live
        // session, no usable REST and nothing scheduled. Before Stage 2
        // this was the end of the story -- `claim(null, ...)` answered
        // `NotPending` and every trigger went home. Arm due-now and claim
        // in the same call.
        if (environment.restUsable()) {
            log("RETRY_TRACE recovery_deferred trigger=$trigger reason=rest_usable")
            return RecoveryStep.None
        }
        val arm = retryScheduler.armImmediate(generation, generation, reason = "vacuum_$trigger")
        if (arm == null) {
            log("RETRY_TRACE recovery_arm_refused trigger=$trigger")
            return RecoveryStep.None
        }
        when (val claim = retryScheduler.claim(arm.epoch, "vacuum_$trigger", generation)) {
            is ConnectRetryScheduler.Claim.Granted -> startGrantedAttemptLocked(claim.token, trigger)
            else -> log("RETRY_TRACE vacuum_claim_yielded trigger=$trigger outcome=$claim")
        }
        return RecoveryStep.None
    }

    /**
     * Stage 2 B7a: may the transport recover right now?
     *
     * Deliberately NOT a function of [RestEgressGate]. That gate answers
     * "may a DIRECT REST request leave", and in Private and Ghost it
     * refuses -- while the chain those modes walk is Reality or Tor, which
     * is exactly the recovery they need. A rule keyed on it would forbid
     * the recovery of the two most exposed modes. The REST refusal reaches
     * the vacuum predicate only as unusable REST health (B8), like any
     * other unusable REST.
     */
    private suspend fun blockedReasonLocked(): String? {
        if (environment.isShuttingDown()) return "shutdown"
        if (ownership.isFenced()) return "privacy_transition"
        if (environment.torObligationBlocks()) return "tor_unsettled"
        return null
    }

    /**
     * Caller holds [mutex]. Arm one local wake-up for the end of the
     * spacing interval. At most one is live; it is not a retry and it
     * decides nothing -- it only re-enters the coordinator, which decides
     * again with everything it can see by then.
     */
    private fun scheduleSpacingWakeLocked(delayMs: Long) {
        if (spacingWakeJob?.isActive == true) return
        spacingWakeJob = scope.launch {
            delay(delayMs)
            ensureRecoveryProgress("start_spacing_elapsed")
        }
    }

    /**
     * Caller holds [mutex]. Starts the granted attempt IN THIS PROCESS,
     * through the same path an external start uses, and keeps the handle.
     * Launching does not suspend, so the "at most one start job"
     * invariant is established atomically with the grant it carries.
     */
    private fun startGrantedAttemptLocked(
        token: ConnectRetryScheduler.ClaimToken,
        trigger: String,
    ) {
        val stamp = ++startAttemptStamp
        lastStartAttemptAtMs = nowMs()
        inFlightGrant = InFlightGrant(token, stamp)
        startJobClaimedOwnership = false
        startPrerequisitesReadyAtMs = null
        // LAZY so the handle is published BEFORE the body can run: a body
        // that finished first would report a completion for a stamp whose
        // job had not been recorded yet.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runStartAttempt(true, null, stamp)
            } finally {
                withContext(NonCancellable) { onStartJobCompleted(stamp) }
            }
        }
        startJob = job
        publishStartJobPendingLocked()
        log(
            "RETRY_TRACE attempt_started_in_process trigger=$trigger stamp=$stamp " +
                "epoch=${token.epoch} gen=${token.generation}",
        )
        job.start()
    }

    /**
     * B7d restore rule: a grant is put back only after its start job has
     * ENDED (this runs from its completion handler) and only when it never
     * claimed the lease. A grant therefore ends in an owned walk, in a
     * restored slot, or in a live start job -- never in nothing.
     */
    private suspend fun onStartJobCompleted(stamp: Long) {
        val token = mutex.withLock {
            if (stamp != startAttemptStamp) return@withLock null // superseded
            startJob = null
            startPrerequisitesReadyAtMs = null
            val grant = inFlightGrant
            inFlightGrant = null
            publishStartJobPendingLocked()
            if (startJobClaimedOwnership) null else grant?.token
        }
        if (token != null) {
            val restored = retryScheduler.restoreAfterFailedStart(token, "start_job_ended_unclaimed")
            log("RETRY_TRACE grant_restored stamp=$stamp restored=$restored")
        }
        ensureRecoveryProgress("start_job_completed")
    }

    /**
     * Caller holds [mutex].
     *
     * Review round 8, second pass (2026-09-13): this asked `job.isActive`,
     * and a `CoroutineStart.LAZY` job that has been published but not yet
     * started is `New` -- not active, not completed. [startGrantedAttemptLocked]
     * publishes the handle before it calls `start()`, on purpose, so the
     * flow was computed in exactly that state and stayed `false` for the
     * whole attempt. A granted retry that then waited behind the lock
     * screen was invisible to presentation, and the banner could read
     * `Offline` while recovery was under way -- the defect this work
     * exists to remove, reintroduced one layer up.
     *
     * The question is not "is it running" but "is it still owed": a start
     * job that has not COMPLETED and has not taken the lease is L1 state
     * (e). `isCompleted` answers that for `New` and `Active` alike.
     */
    private fun publishStartJobPendingLocked() {
        val job = startJob
        _startJobPending.value = job != null && !job.isCompleted && !startJobClaimedOwnership
    }

    private fun logActive(trigger: String, reason: String, owner: Long?) {
        log(
            "RETRY_TRACE recovery_active trigger=$trigger reason=$reason" +
                (owner?.let { " owner=$it" } ?: ""),
        )
    }

    companion object {
        /**
         * Product decision D-020(4): the reaction budget, counted from the
         * moment a start's prerequisites are ready -- the container is up
         * and the device is unlocked -- and NOT from the moment it was
         * launched. A start waiting behind a locked screen is a live
         * attempt, not a stalled one.
         */
        const val START_BUDGET_MS: Long = 10_000L
    }
}
