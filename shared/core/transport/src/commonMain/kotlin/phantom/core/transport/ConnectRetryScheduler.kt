// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * N1-F3 - the retry policy for a chain walk that ended in
 * [ManagerState.AllFailed].
 *
 * ## The defect this exists for
 *
 * [TransportManager.connect] walks the chain once. When every kind fails
 * it sets [ManagerState.AllFailed] and throws; it has no timer, no loop
 * and no way back. Recovery therefore has to come from a caller calling
 * `connect()` again, and until this class existed nobody did:
 *
 *  - the service caught the exception, logged it, released its CAS and
 *    returned;
 *  - the 30 s alarm heartbeat saw `AllFailed` and skipped, on the stated
 *    grounds that "the foreground service owns the retry cadence" - a
 *    cadence the service did not have;
 *  - the network observer did recover, but only on an actual network
 *    TRANSITION. `classify()` returns "not meaningful" when presence,
 *    VPN, transport class and validated are all unchanged.
 *
 * So a chain that failed while the network stayed up - a relay outage, a
 * DPI block, a slow bridge - left the app dark until something restarted
 * the service. That is the physically observed `direct_unavailable`.
 *
 * ## Why the delay is measured from the END of an attempt
 *
 * Not from its start, and NOT by picking a backoff "longer than a chain
 * walk". A walk has no proven upper bound. The nominal per-kind budgets
 * alone reach about 1435 s for Standard, 1410 s for Private and 1350 s
 * for Ghost. Tor prepare walks [TransportManager.BRIDGE_ROTATION_ORDER]
 * - 600 + 420 + 180 + 60 = 1260 s - before its 90 s probe even begins,
 * and `tor.start(profile)` sits OUTSIDE the `withTimeout` that bounds
 * the state wait, so the real ceiling is not established by the source
 * at all.
 *
 * An earlier version of this comment said 720 s. That figure came from
 * a stale KDoc rather than from the executable rotation table; derive
 * these numbers from BRIDGE_ROTATION_ORDER, never from prose.
 *
 * Overlap is therefore prevented structurally, by single-flight and
 * generation, never by hoping the timer is slower than the walk.
 *
 * ## What this class is not
 *
 * It is policy and bookkeeping only: it decides WHEN a retry becomes due
 * and who is allowed to claim it. It never calls `connect()`, never
 * touches a transport, and holds no Android type. The caller owns the
 * attempt.
 *
 * It also says nothing about whether messages flow. A successful outer
 * connect resets the streak here; the WSS session can still be wedged.
 * That is F-7 and is deliberately out of scope - see [onOuterConnectSucceeded].
 */
class ConnectRetryScheduler(
    private val nowMs: () -> Long,
    /**
     * Multiplier applied to the base delay, expected in [0.8, 1.2] for
     * the +/-20 % jitter the contract asks for. Injectable so tests are
     * deterministic; production passes a random draw.
     */
    private val jitterFactor: () -> Double = { 1.0 },
    private val log: ((String) -> Unit)? = null,
) {
    private val mutex = Mutex()

    private var streak: Int = 0
    private var pendingDueAtMs: Long? = null
    private var pendingGeneration: Long = -1L

    /**
     * Bumped by every invalidation and every success. A timer carrying an
     * older epoch cannot claim: that is what stops a stale wakeup from
     * reviving a connection after a rewalk, a mode change, or a shutdown.
     */
    private var epoch: Long = 0L

    /** What [armAfterFailure] granted, so the caller can wait and claim. */
    data class Arm(val delayMs: Long, val epoch: Long, val streak: Int)

    /**
     * Identity of one granted claim: the epoch it was granted under and
     * the connect generation that armed it. Both must still hold for a
     * restore to be accepted.
     */
    data class ClaimToken(val epoch: Long, val generation: Long)

    /** Outcome of an attempt to claim the pending retry. */
    sealed class Claim {
        /**
         * Claimed. The caller - and only this caller - must now attempt.
         *
         * [token] identifies THIS grant. It is the only thing
         * [restoreAfterFailedStart] will accept, so a restore that
         * arrives after an invalidation or after a newer arm cannot put
         * the slot back. Without it, `claim -> invalidate -> late
         * restore` resurrected a retry that had been deliberately
         * cancelled - including one cancelled by service shutdown.
         */
        data class Granted(val token: ClaimToken) : Claim()

        /** Nothing is scheduled. */
        object NotPending : Claim()

        /** Scheduled, but not yet due. [remainingMs] is how much is left. */
        data class NotDue(val remainingMs: Long) : Claim()

        /** The claimant's epoch is older than the current one. */
        data class Stale(val claimEpoch: Long, val currentEpoch: Long) : Claim()

        /**
         * The retry was armed by a connect generation that has since been
         * superseded. Epoch alone does not catch this: a stale generation
         * can arm AFTER the current one, so its epoch is the newest even
         * though its claim is the one that must not run.
         */
        data class StaleGeneration(
            val armedBy: Long,
            val currentGeneration: Long,
        ) : Claim()
    }

    data class Snapshot(
        val streak: Int,
        val dueAtMs: Long?,
        val epoch: Long,
        val generation: Long,
    )

    /**
     * Record that a chain walk finished in `AllFailed` and schedule the
     * next attempt, measured from now - which the caller invokes AFTER
     * the attempt returned, so the delay never overlaps the walk.
     *
     * Re-arming while a retry is already pending replaces it and bumps
     * the epoch, so the superseded timer cannot claim.
     */
    suspend fun armAfterFailure(generation: Long, currentGeneration: Long = generation): Arm? =
        mutex.withLock {
        // A generation that has already been superseded must not schedule
        // anything. It lost ownership the moment a newer connect claimed
        // a token; letting it arm would put a timer on top of the live
        // generation's work and, worse, one whose epoch is NEWER than the
        // live one's -- so epoch checking alone would let it through.
        if (generation != currentGeneration) {
            log?.invoke(
                "RETRY_TRACE arm_refused reason=stale_generation " +
                    "gen=$generation current=$currentGeneration",
            )
            return@withLock null
        }
        streak += 1
        val base = baseDelayMsForStreak(streak)
        val delay = applyJitter(base)
        epoch += 1
        pendingDueAtMs = nowMs() + delay
        pendingGeneration = generation
        log?.invoke(
            "RETRY_TRACE armed streak=$streak baseMs=$base delayMs=$delay " +
                "epoch=$epoch gen=$generation",
        )
        Arm(delayMs = delay, epoch = epoch, streak = streak)
    }

    /**
     * Try to take ownership of the pending retry.
     *
     * This is the single-flight point. Every source of a retry signal -
     * the armed timer, an alarm nudge, a duplicate nudge - goes through
     * it, and at most one of them is granted, because a granted claim
     * clears the pending slot.
     *
     * Pass the epoch from [Arm] when claiming as the armed timer. Pass
     * null when claiming as an external nudge that has no epoch of its
     * own; the nudge then claims whatever is currently pending, if due.
     */
    suspend fun claim(
        claimEpoch: Long?,
        source: String,
        currentGeneration: Long? = null,
    ): Claim = mutex.withLock {
        // Generation is authoritative. A pending retry armed by a
        // generation that is no longer current belongs to work that has
        // been superseded, and must not start a chain walk.
        if (currentGeneration != null &&
            pendingDueAtMs != null &&
            pendingGeneration != currentGeneration
        ) {
            log?.invoke(
                "RETRY_TRACE claim_rejected source=$source reason=stale_generation " +
                    "armedBy=$pendingGeneration current=$currentGeneration",
            )
            return@withLock Claim.StaleGeneration(pendingGeneration, currentGeneration)
        }
        // Staleness is decided FIRST, before whether anything is pending.
        //
        // Both answers refuse the claim, so the product is safe either
        // way, but they are not equally informative: a timer left over
        // from a superseded generation should be told it is stale even
        // when the slot happens to be empty, because that is the
        // condition the contract cares about - a stale timer must never
        // start a connect after a rewalk, a mode change or a shutdown.
        // Reporting `NotPending` there would hide the reason in the logs
        // and make the stale-timer fixtures pass for the wrong reason.
        if (claimEpoch != null && claimEpoch != epoch) {
            log?.invoke(
                "RETRY_TRACE claim_rejected source=$source reason=stale " +
                    "claimEpoch=$claimEpoch currentEpoch=$epoch",
            )
            return@withLock Claim.Stale(claimEpoch, epoch)
        }
        val due = pendingDueAtMs
        if (due == null) {
            log?.invoke("RETRY_TRACE claim_rejected source=$source reason=not_pending")
            return@withLock Claim.NotPending
        }
        val now = nowMs()
        if (now < due) {
            val remaining = due - now
            log?.invoke(
                "RETRY_TRACE claim_rejected source=$source reason=not_due remainingMs=$remaining",
            )
            return@withLock Claim.NotDue(remaining)
        }
        pendingDueAtMs = null
        log?.invoke("RETRY_TRACE claim_granted source=$source epoch=$epoch streak=$streak")
        Claim.Granted(ClaimToken(epoch = epoch, generation = pendingGeneration))
    }

    /**
     * Drop any pending retry and invalidate outstanding timers.
     *
     * Called when something else has taken over recovery - a network
     * rewalk, a privacy-mode change - or when the service is stopping.
     * The streak is deliberately KEPT: an invalidation is not evidence
     * that the network got better, and resetting the streak here would
     * let an unlucky sequence of rewalks hold the backoff at 30 s
     * indefinitely. Only a real success resets it.
     */
    suspend fun invalidate(reason: String) = mutex.withLock {
        val had = pendingDueAtMs != null
        pendingDueAtMs = null
        epoch += 1
        log?.invoke(
            "RETRY_TRACE invalidated reason=$reason hadPending=$had newEpoch=$epoch streak=$streak",
        )
    }

    /**
     * A chain walk reached [ManagerState.Connected]. Reset the backoff.
     *
     * This is about the OUTER transport only. It does not assert that
     * the WSS session is healthy or that messages are flowing; a wedged
     * session after a successful outer connect is F-7, a separate open
     * finding. Naming it `onOuterConnectSucceeded` rather than
     * `onConnected` keeps that boundary visible at every call site.
     */
    suspend fun onOuterConnectSucceeded() = mutex.withLock {
        val hadStreak = streak
        streak = 0
        pendingDueAtMs = null
        epoch += 1
        log?.invoke(
            "RETRY_TRACE reset_on_success previousStreak=$hadStreak newEpoch=$epoch",
        )
    }

    /**
     * Put back a claim that was granted but could not be acted on -- a
     * service start that threw, for instance.
     *
     * Without this the retry is simply lost: [claim] clears the pending
     * slot, so a failed start would leave nothing scheduled and every
     * later nudge would find `NotPending`. That is the original latch,
     * reintroduced through the back door.
     *
     * The slot is restored as immediately due, not re-laddered: the
     * backoff already elapsed once, and the failure was ours, not the
     * network's.
     */
    suspend fun restoreAfterFailedStart(token: ClaimToken, reason: String): Boolean =
        mutex.withLock {
        // The token is what makes this safe. An untyped "put it back if
        // the slot is empty" restore resurrects a retry that was
        // deliberately cancelled: claim -> invalidate -> late restore
        // would reinstate a pending attempt after a rewalk took over, or
        // after the service was destroyed.
        if (token.epoch != epoch) {
            log?.invoke(
                "RETRY_TRACE restore_refused reason=$reason cause=stale_epoch " +
                    "tokenEpoch=${token.epoch} currentEpoch=$epoch",
            )
            return@withLock false
        }
        if (token.generation != pendingGeneration) {
            log?.invoke(
                "RETRY_TRACE restore_refused reason=$reason cause=stale_generation " +
                    "tokenGen=${token.generation} armedBy=$pendingGeneration",
            )
            return@withLock false
        }
        if (pendingDueAtMs != null) {
            log?.invoke(
                "RETRY_TRACE restore_refused reason=$reason cause=already_scheduled",
            )
            return@withLock false
        }
        pendingDueAtMs = nowMs()
        log?.invoke(
            "RETRY_TRACE claim_restored reason=$reason epoch=$epoch streak=$streak",
        )
        true
    }

    suspend fun snapshot(): Snapshot = mutex.withLock {
        Snapshot(
            streak = streak,
            dueAtMs = pendingDueAtMs,
            epoch = epoch,
            generation = pendingGeneration,
        )
    }

    private fun applyJitter(baseMs: Long): Long {
        val factor = jitterFactor()
        val jittered = (baseMs.toDouble() * factor).toLong()
        // A delay of zero would defeat the purpose of the backoff, so the
        // floor is one second even if a caller injects a degenerate
        // factor. The cap keeps a hostile factor from parking recovery
        // beyond the intended ceiling.
        return jittered.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS * 2)
    }

    companion object {
        /**
         * 30 s, 2 min, 5 min, then 15 min for every subsequent failure.
         *
         * The first step is short because the common case is a transient
         * relay or carrier hiccup that clears in seconds. The ceiling is
         * 15 min because past that the user notices before the app does,
         * and a network change or a foreground bring-back will trigger
         * recovery sooner anyway.
         */
        val BACKOFF_LADDER_MS: List<Long> = listOf(
            30_000L,
            120_000L,
            300_000L,
            900_000L,
        )

        const val MIN_DELAY_MS: Long = 1_000L
        val MAX_DELAY_MS: Long = BACKOFF_LADDER_MS.last()

        /** Jitter band: +/-20 %, as the accepted contract specifies. */
        const val JITTER_MIN_FACTOR: Double = 0.8
        const val JITTER_MAX_FACTOR: Double = 1.2

        /**
         * Streak 1 takes the first rung, streak 2 the second, and so on;
         * everything past the ladder stays on its last rung.
         */
        fun baseDelayMsForStreak(streak: Int): Long {
            if (streak <= 0) return BACKOFF_LADDER_MS.first()
            val index = (streak - 1).coerceAtMost(BACKOFF_LADDER_MS.lastIndex)
            return BACKOFF_LADDER_MS[index]
        }
    }
}
