// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A running chain walk that ownership can stop.
 *
 * Implemented by the service over the coroutine [kotlinx.coroutines.Job]
 * that is executing `TransportManager.connect()`. It is an interface so
 * that the ownership state machine carries no Android or coroutine
 * machinery of its own and can be driven deterministically by tests.
 */
fun interface ConnectWalkHandle {
    /**
     * Cancel the walk and wait for it to actually finish.
     *
     * Returns true only when the walk is confirmed finished within
     * [timeoutMs]. Returning false must mean "it may still be running",
     * because the whole fail-closed decision below is built on that.
     */
    suspend fun cancelAndJoin(timeoutMs: Long): Boolean
}

/** The outcome of trying to take the slot away from its current owner. */
sealed interface Handover {
    /** Nobody held the slot. It is free. */
    data object NothingToStop : Handover

    /** The previous walk is confirmed finished and the slot is free. */
    data class Quiesced(val previousOwner: Long) : Handover

    /**
     * The previous walk did NOT finish inside the budget.
     *
     * The lease is deliberately NOT released and no successor may
     * claim. See the fail-closed note on [ConnectOwnership].
     */
    data class TimedOut(val previousOwner: Long, val timeoutMs: Long) : Handover

    /** Another handover is already running. The caller must not proceed. */
    data object AlreadyInProgress : Handover
}

/**
 * N1-F3 - exclusive ownership of the chain-walk slot: one token, one
 * running walk, one atomic transition.
 *
 * ## The first race this replaced
 *
 * Ownership used to be two separate pieces of state: an `AtomicBoolean`
 * saying "someone is connecting" and an `AtomicLong` generation token.
 * Claiming took the boolean and then bumped the generation; releasing
 * READ the generation, compared it, and then wrote the boolean:
 *
 *     val current = connectGeneration.get()     // (1)
 *     if (current == myGen) {
 *         connectStarted.set(false)             // (2)
 *     }
 *
 * Between (1) and (2) the slot could change hands, and (2) would then
 * free a slot belonging to someone else. Holding one variable under one
 * lock closed that: a release is a compare-and-set against the same
 * variable the claim wrote.
 *
 * ## The second race, which the first fix did NOT close
 *
 * Review R-N1.16 found that revoking the token is not the same as
 * stopping the work. The old `forceRelease()` wrote `owner = null` and
 * returned; the displaced coroutine was never cancelled and never
 * joined, and nothing rechecked ownership before
 * `TransportManager.connect()`. So:
 *
 *  1. generation A claims and enters the outer chain walk;
 *  2. a network rewalk force-releases the token;
 *  3. generation B claims the now-free slot and enters the walk;
 *  4. A, which was never told to stop, is still inside its own walk.
 *
 * `TransportManager.connect()` has no internal serialization, so A and B
 * then race subsystem start/stop and `_state`. The window is not small:
 * a Tor probe alone budgets 90 s and bridge rotation budgets far more.
 *
 * The earlier claim that a rewalk is safe "because it has quiesced the
 * transport" was too broad, and that is exactly the defect: releasing
 * Tor/Xray/Hybrid state is not the same as quiescing the coroutine that
 * is walking the chain.
 *
 * ## What this does instead
 *
 * Ownership covers the token AND the execution. A walk registers itself
 * at [claim] time - one atomic step, so there is never a moment where a
 * lease is held by a walk the ownership cannot see - and [handOver]
 * cancels it and waits for it to finish before the slot is released to
 * anyone else.
 *
 * ## Fail-closed on a handover timeout
 *
 * If the displaced walk does not finish inside the budget, the lease is
 * NOT released, no successor may claim, and `handoff_timeout` is logged.
 * Availability yields to privacy and transport integrity here: being
 * briefly disconnected is better than running two competing transports.
 *
 * Recovery is deferred rather than forbidden. The state keeps the walk
 * handle, so the next allowed signal calls [handOver] again; if the walk
 * has since finished, the join returns at once and the slot is released
 * normally. Nothing clears the block implicitly.
 *
 * ## Why the join happens outside the lock
 *
 * A cancelled walk runs its own cleanup, and that cleanup calls
 * [release] - which takes this same mutex. Joining while holding the
 * mutex would therefore deadlock against any cleanup that is
 * non-cancellable. So the transition is: mark the slot in-handover under
 * the lock, join outside it, then settle under the lock again. The
 * in-handover mark is what keeps a successor out during the gap, and
 * [release] is deliberately refused while it is set so that the
 * displaced walk cannot free the slot on its way out.
 */
class ConnectOwnership(
    private val nextToken: () -> Long,
    private val handoverTimeoutMs: Long = DEFAULT_HANDOVER_TIMEOUT_MS,
    private val log: ((String) -> Unit)? = null,
    /**
     * Test seam, null in production: invoked INSIDE [release]'s critical
     * section, between reading the owner and clearing it.
     *
     * It exists because the property that matters cannot be observed
     * from outside. A sequential fixture - claim, hand over, claim,
     * stale release - proves the owner is checked, but it passes equally
     * well against an implementation that checks under the lock and then
     * clears outside it. That implementation still has the race: the
     * window simply moves.
     *
     * Parking here lets a test hold the critical section open and assert
     * that a handover and a competing claim make no progress while it is
     * held. `MUT-F3-RELEASE-OUTSIDE-LOCK` moves the clear out of the
     * lock and fails exactly that fixture and no other.
     */
    private val onReleaseCriticalSection: (suspend () -> Unit)? = null,
) {
    private val mutex = Mutex()

    /** The token of the current owner, or null when the slot is free. */
    private var owner: Long? = null

    /** The walk that owner is running, once it has registered itself. */
    private var walk: ConnectWalkHandle? = null

    /** Set while a handover is joining. No claim and no release passes. */
    private var handoverInProgress = false

    /**
     * Set when a handover timed out. No claim passes until a later
     * handover confirms the previous walk has finished.
     */
    private var handoverBlocked: String? = null

    /**
     * Set while a privacy transition is between its handover and its
     * completion. No claim passes.
     *
     * R-N1.17 P1. A successful [handOver] clears `owner` the moment the
     * old walk is confirmed stopped - and the transition then goes on to
     * stop the transport subsystems, which takes time. In that window the
     * lease is FREE. An ordinary `onStartCommand` - a wakeup, a user
     * opening the app, the platform restarting the service - could claim
     * it, begin a fresh chain walk, and have its newly started subsystems
     * torn down by the release belonging to the transition that had
     * already let go of the lease.
     *
     * The transition mutex does not cover this: it serialises transitions
     * against each other, and an ordinary service start is not a
     * transition. The lease has to stay shut by something the claim path
     * itself consults.
     */
    private var claimFence: String? = null

    /**
     * The policy epoch that raised the fence, and the only one that may
     * lower it.
     *
     * R-N1.17 P1: the fence used to be a scoped wrapper that lowered
     * itself on the way out, so a switch that could NOT confirm its
     * release still reopened the lease the moment its inline half
     * returned - fail-open, in the one state that is not safe. The fence
     * has to outlive the inline half and belong to the obligation, which
     * means it belongs to an epoch rather than to a block.
     *
     * Ownership is what makes a late lower harmless: a superseded
     * settlement lowering "its" fence cannot open a lease that a NEWER
     * transition has since shut.
     */
    private var claimFenceOwner: Long? = null

    /**
     * Take the slot AND register the walk that will run under it, in one
     * atomic step. Returns the owning token on success and null when
     * someone else holds it, when a handover is running, or when a
     * previous handover timed out and has not been resolved.
     *
     * The token is minted only on success, so a refused claim does not
     * consume one and cannot disturb an owner's later release.
     *
     * R-N1.16 P1-1: claiming and registering used to be two calls, and
     * the gap between them was a race of exactly the kind this class
     * exists to remove. A handover could capture `walk == null` from a
     * lease that had been claimed but not yet registered, conclude there
     * was nothing to stop, release the slot to a successor - and the
     * first walk would then register itself and keep running, owned by
     * nobody. Passing the handle to [claim] closes that window by
     * construction rather than by another guard.
     */
    suspend fun claim(source: String, walk: ConnectWalkHandle): Long? = mutex.withLock {
        val fence = claimFence
        if (fence != null) {
            log?.invoke(
                "OWNERSHIP claim_refused source=$source reason=transition_in_progress " +
                    "fencedBy=$fence",
            )
            return@withLock null
        }
        val blocked = handoverBlocked
        if (blocked != null) {
            log?.invoke(
                "OWNERSHIP claim_refused source=$source reason=handoff_blocked " +
                    "blockedBy=$blocked heldBy=${owner ?: "none"}",
            )
            return@withLock null
        }
        if (handoverInProgress) {
            log?.invoke("OWNERSHIP claim_refused source=$source reason=handover_in_progress")
            return@withLock null
        }
        val held = owner
        if (held != null) {
            log?.invoke("OWNERSHIP claim_refused source=$source heldBy=$held")
            return@withLock null
        }
        val token = nextToken()
        owner = token
        this.walk = walk
        log?.invoke("OWNERSHIP claimed source=$source token=$token")
        token
    }

    /**
     * Release the slot, but only if [token] still owns it and no
     * handover is deciding its fate.
     *
     * Returns true when this caller was the owner and the slot is now
     * free; false when ownership had already moved on or a handover is
     * in charge, in which case nothing is written. That false is the
     * fix: the old code wrote regardless, on the strength of a
     * comparison made earlier.
     */
    suspend fun release(token: Long, site: String): Boolean = mutex.withLock {
        val held = owner
        if (held != token) {
            log?.invoke(
                "OWNERSHIP release_refused site=$site token=$token heldBy=${held ?: "none"}",
            )
            return@withLock false
        }
        if (handoverInProgress || handoverBlocked != null) {
            // A handover owns this transition. If the displaced walk
            // freed the slot here on its way out, a successor could
            // start before the walk had actually finished - which is
            // the whole defect the handover exists to prevent.
            log?.invoke(
                "OWNERSHIP release_deferred_to_handover site=$site token=$token",
            )
            return@withLock false
        }
        onReleaseCriticalSection?.invoke()
        owner = null
        walk = null
        log?.invoke("OWNERSHIP released site=$site token=$token")
        true
    }

    /**
     * Take the slot away from whoever holds it, stopping their walk
     * first.
     *
     * Callers, as of R-N1.17: a network rewalk, a privacy switch, a
     * deferred settlement retrying its teardown, and the service's own
     * shutdown. The rewalk was the only one when this was written.
     *
     * Whoever calls it, the reason is the same: releasing the transport
     * quiesces the subsystems; this quiesces the coroutine still walking
     * the chain, which is a different thing and is why revoking the token
     * alone was not enough. The privacy switch additionally holds a claim
     * fence across the handover and the release, because a successful
     * handover frees the lease immediately and the release that follows
     * takes real time - see [fenceClaims].
     *
     * On [Handover.TimedOut] the caller must NOT start a successor and
     * must NOT release the lease by another route.
     */
    suspend fun handOver(reason: String): Handover {
        val handle: ConnectWalkHandle?
        val held: Long
        mutex.withLock {
            if (handoverInProgress) {
                log?.invoke("OWNERSHIP handover_refused reason=$reason cause=already_in_progress")
                return Handover.AlreadyInProgress
            }
            val current = owner
            if (current == null) {
                handoverBlocked = null
                walk = null
                log?.invoke("OWNERSHIP handover_noop reason=$reason")
                return Handover.NothingToStop
            }
            held = current
            handle = walk
            handoverInProgress = true
        }

        // Outside the lock on purpose: the walk's own cleanup calls
        // release(), which takes this mutex.
        val stopped = try {
            if (handle == null) {
                // The owner claimed but never attached a walk, so there
                // is no execution to wait for.
                log?.invoke("OWNERSHIP handover_no_walk reason=$reason previousOwner=$held")
                true
            } else {
                handle.cancelAndJoin(handoverTimeoutMs)
            }
        } catch (t: Throwable) {
            // R-N1.16 P2: the join runs outside the lock and can throw -
            // a CancellationException if the caller is cancelled
            // mid-handover, or anything the walk's own teardown raises.
            //
            // Without this, `handoverInProgress` stayed true for ever:
            // every claim refused, `handoverBlocked` never set, so
            // recoverIfBlocked() returned null and NOTHING could resolve
            // it. That is a worse state than fail-closed, because
            // fail-closed is at least recoverable.
            //
            // Settle into the recoverable fail-closed state before the
            // exception leaves. NonCancellable because the most likely
            // throwable IS a cancellation, and this must still run.
            withContext(NonCancellable) {
                mutex.withLock {
                    handoverInProgress = false
                    handoverBlocked = reason
                    log?.invoke(
                        "OWNERSHIP handoff_failed reason=$reason previousOwner=$held " +
                            "error=${t::class.simpleName} lease=kept successor=refused",
                    )
                }
            }
            throw t
        }

        mutex.withLock {
            handoverInProgress = false
            if (!stopped) {
                handoverBlocked = reason
                log?.invoke(
                    "OWNERSHIP handoff_timeout reason=$reason previousOwner=$held " +
                        "timeoutMs=$handoverTimeoutMs lease=kept successor=refused",
                )
                return Handover.TimedOut(held, handoverTimeoutMs)
            }
            owner = null
            walk = null
            handoverBlocked = null
            log?.invoke("OWNERSHIP handed_over reason=$reason previousOwner=$held")
            return Handover.Quiesced(held)
        }
    }

    /**
     * If a previous handover timed out, try it again now.
     *
     * Returns null when nothing was blocked, so a caller can invoke this
     * unconditionally on any allowed signal.
     *
     * R-N1.16 review item 3. Fail-closed is only defensible if it is
     * recoverable: the timeout keeps the walk handle, so once that walk
     * has actually finished the join returns at once and the lease is
     * released normally. Without a caller for this, the only thing that
     * could ever lift the block was another network rewalk - so on a
     * stable network the app would have stayed dark for good, which
     * trades availability away permanently rather than briefly.
     *
     * Nothing lifts the block implicitly. Recovery still requires a
     * confirmed join; this only supplies the occasions to attempt one.
     */
    suspend fun recoverIfBlocked(reason: String): Handover? {
        val blocked = mutex.withLock { handoverBlocked }
        if (blocked == null) return null
        log?.invoke("OWNERSHIP recovery_attempt reason=$reason blockedBy=$blocked")
        return handOver("recovery_$reason")
    }

    /** Current owner token, or null. For assertions and diagnostics. */
    suspend fun currentOwner(): Long? = mutex.withLock { owner }

    /** Whether [token] still owns the slot, without changing anything. */
    suspend fun isOwner(token: Long): Boolean = mutex.withLock { owner == token }

    /**
     * The reason a handover timed out, or null when the slot is usable.
     * For diagnostics and for the tests that pin the fail-closed state.
     */
    suspend fun blockedReason(): String? = mutex.withLock { handoverBlocked }

    /**
     * Hold the lease shut on behalf of [epoch], from before its handover
     * until its release is CONFIRMED done.
     *
     * Raising is idempotent for the same epoch: the inline half raises
     * it, and a deferred settlement raises it again on every attempt,
     * because the process may have restarted in between.
     */
    suspend fun raiseClaimFence(reason: String, epoch: Long) {
        mutex.withLock {
            val held = claimFenceOwner
            if (held != null && held != epoch) {
                log?.invoke(
                    "OWNERSHIP fence_taken_over reason=$reason epoch=$epoch heldBy=$held",
                )
            }
            claimFence = reason
            claimFenceOwner = epoch
            log?.invoke("OWNERSHIP fence_raised reason=$reason epoch=$epoch")
        }
    }

    /**
     * Let claims through again - only for the epoch that raised the
     * fence.
     *
     * Returns whether this call actually opened the lease. A settlement
     * from a superseded epoch calls this on its way out, and the check is
     * what stops it reopening a lease that a NEWER transition has shut
     * while its own subsystems are coming down.
     */
    suspend fun lowerClaimFence(reason: String, epoch: Long): Boolean = mutex.withLock {
        if (claimFence == null) return@withLock false
        val held = claimFenceOwner
        if (held != null && held != epoch) {
            log?.invoke(
                "OWNERSHIP fence_lower_refused reason=$reason epoch=$epoch heldBy=$held",
            )
            return@withLock false
        }
        claimFence = null
        claimFenceOwner = null
        log?.invoke("OWNERSHIP fence_lowered reason=$reason epoch=$epoch")
        true
    }

    /** Whether the lease is currently fenced. For assertions. */
    suspend fun isFenced(): Boolean = mutex.withLock { claimFence != null }

    /** Which epoch holds the fence, if any. For assertions. */
    suspend fun fenceOwner(): Long? = mutex.withLock { claimFenceOwner }

    companion object {
        /**
         * How long a handover waits for the displaced walk to finish.
         *
         * The walk is cancellable at every suspension point, so a
         * healthy one unwinds in milliseconds. This budget only covers
         * subsystem teardown that is deliberately non-cancellable. It is
         * short because it runs before a successor may start, and
         * because exceeding it is not an error path that guesses - it
         * refuses.
         */
        const val DEFAULT_HANDOVER_TIMEOUT_MS: Long = 5_000L
    }
}
