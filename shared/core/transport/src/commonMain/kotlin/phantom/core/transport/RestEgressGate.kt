// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile

/**
 * N1-F2 — the revocable dispatch boundary for Direct REST egress.
 *
 * [RestEgressPolicy] answers "is Direct egress allowed RIGHT NOW".
 * This gate adds the second half of the owner-approved "No Silent
 * Downgrade" invariant: a runtime departure from Standard must not
 * only refuse NEW work — it must revoke work that is ALREADY holding a
 * Direct authorization, including suspended long polls, in-flight
 * authentication, pre-key and media transfers.
 *
 * ## R-N1.3: what changed and why
 *
 * Two defects in the R-N1.2 shape were confirmed by review.
 *
 * 1. **The authorization was not linearizable.** The policy was read,
 *    and only afterwards was the generation captured. A revocation that
 *    completed in that window was invisible: the dispatch captured the
 *    already-incremented generation, the equality re-check passed, and
 *    the block ran under a stale `DirectAllowed`. The decision and the
 *    lease are now taken together under [registryLock] — the same lock
 *    [revokeAndJoin] takes to bump the generation — so a dispatch is
 *    either fully authorized before a revocation or refused after it.
 *    There is no interleaving in between.
 *
 * 2. **Cancelling a `Job` did not stop the network.** Production
 *    transports block in OkHttp `Call.execute()`, which is not
 *    cooperative with coroutine cancellation. [EgressCallRegistry] now
 *    carries real per-call cancellation handles, and revocation aborts
 *    the native calls BEFORE it cancels and joins the coroutines.
 *
 * ## Lease semantics
 *
 * Every actual network dispatch runs inside [dispatch]:
 *
 *  1. under [registryLock], the policy is evaluated and — only if it
 *     permits Direct — the lease is registered under the current
 *     generation. Refusal throws [RestEgressBlockedException] before
 *     the block runs;
 *  2. the block runs inside a child [Job], so [revokeAndJoin] can
 *     cancel a SUSPENDED dispatch (a parked long poll, a stalled
 *     upload);
 *  3. after the block returns, the generation is re-checked — a result
 *     produced under a stale lease is discarded by throwing
 *     [CancellationException]: it must not COMPLETE either.
 *
 * ## Revocation
 *
 * [revokeAndJoin] bumps the generation, aborts every registered native
 * call, cancels every registered dispatch, and JOINS them, so the
 * caller (privacy-mode switch) returns only after no Direct REST/media
 * work remains active. The join is BOUNDED: a transport that ignores
 * its cancellation handle must not hang the privacy-mode switch
 * forever. On timeout the generation is already bumped and the native
 * calls already aborted, so any late result is discarded — the timeout
 * degrades liveness, not the fail-closed property. It is reported in
 * the return value and the log, never swallowed.
 *
 * [invalidate] is the non-joining half, exposed so a completion racing
 * a revocation is deterministically testable.
 *
 * Diagnostics carry only operation labels, decision names, generation
 * numbers and counts — never identity, tokens, envelope ids or
 * content.
 */
class RestEgressGate(
    private val policy: RestEgressPolicy,
    private val callRegistry: EgressCallRegistry? = null,
    private val log: (String) -> Unit = {},
) : RestEgressPolicy {

    @Volatile
    private var generation: Long = 0L

    private val registryLock = Mutex()
    private val active = mutableSetOf<Job>()

    /**
     * R-N1.3 test seam. Invoked inside [dispatch] AFTER the linearized
     * authorization and BEFORE the block runs. Production leaves it
     * empty; the race fixture uses it to park a dispatch at exactly the
     * point a revocation must not be able to slip past. It cannot widen
     * the authorization window: by the time it runs, the lease is
     * already registered, so a concurrent [revokeAndJoin] sees and
     * cancels it.
     */
    @Volatile
    internal var afterAuthorizationHook: (suspend () -> Unit)? = null

    /**
     * Test seam: runs after [dispatch]'s block has produced a result but
     * BEFORE the atomic acceptance step. It exists so a fixture can queue
     * a complete `revokeAndJoin` into exactly the window R-N1.4 left
     * open. Never set in production.
     */
    internal var beforeCompletionAcceptanceHook: (suspend () -> Unit)? = null

    /**
     * Test seam: runs INSIDE the acceptance lock hold, after the block
     * produced a result and before the generation check and
     * deregistration. A fixture parks here and asserts that a concurrent
     * [revokeAndJoin] makes no progress, which is exactly what the
     * R-N1.4 shape did not guarantee. Never set in production.
     */
    internal var duringAcceptanceHook: (suspend () -> Unit)? = null

    /** Live policy read with the gate's own fail-closed guard. */
    override fun decide(): RestEgressDecision = try {
        policy.decide()
    } catch (_: Throwable) {
        RestEgressDecision.AnonymousRequiredButUnavailable
    }

    val currentGeneration: Long get() = generation

    /** Number of registered in-flight dispatches (diagnostic/test). */
    suspend fun activeDispatchCount(): Int = registryLock.withLock { active.size }

    /**
     * Bump the generation WITHOUT cancelling in-flight work. Any
     * dispatch that completes afterwards discards its result. Prefer
     * [revokeAndJoin] in production; this half exists so the
     * stale-lease completion path is deterministically testable and so
     * revocation can be staged.
     */
    suspend fun invalidate(reason: String) {
        registryLock.withLock { generation += 1 }
        log("REST_EGRESS invalidated generation=$generation reason=$reason")
    }

    /** Outcome of a [revokeAndJoin], so the caller can react to a stall. */
    data class RevocationResult(
        val generation: Long,
        val nativeCallsCancelled: Int,
        val dispatchesCancelled: Int,
        /**
         * `true` when every cancelled dispatch finished within the join
         * budget. `false` means at least one transport did not honour
         * its cancellation handle in time: the posture is still
         * fail-closed and late results are still discarded, but Direct
         * I/O may briefly outlive the switch.
         */
        val joinedCleanly: Boolean,
    )

    /**
     * Synchronously invalidate the current generation, abort every
     * registered native call, cancel every registered in-flight Direct
     * dispatch, and JOIN them within [joinTimeoutMs].
     *
     * Never swallows [CancellationException] of the CALLER: if the
     * caller's own coroutine is cancelled while joining, that
     * cancellation propagates. Only the bounded join's own timeout is
     * absorbed, and it is reported in [RevocationResult.joinedCleanly].
     */
    suspend fun revokeAndJoin(
        reason: String,
        joinTimeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
    ): RevocationResult {
        val toCancel: List<Job>
        val gen: Long
        registryLock.withLock {
            generation += 1
            gen = generation
            toCancel = active.toList()
        }

        // Abort the real network first. Cancelling only the coroutine
        // would leave the blocking socket call running AND make the
        // join below wait for it.
        // R-N1.4: revoking "through gen - 1" both aborts the calls that
        // are already registered AND refuses any dispatch that was
        // authorized under an older lease but has not reached its
        // register() yet. R-N1.3 cancelled only a snapshot, so such a
        // late registration slipped through and went on to execute().
        val nativeCancelled = callRegistry?.cancelAll(
            reason = reason,
            revokedThroughLease = gen - 1,
        ) ?: 0

        for (job in toCancel) {
            job.cancel(CancellationException("rest egress revoked: $reason"))
        }

        var joinedCleanly = true
        if (toCancel.isNotEmpty()) {
            try {
                withTimeout(joinTimeoutMs) {
                    for (job in toCancel) {
                        job.join()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Bounded on purpose: a transport that ignores its
                // cancellation handle must not hang the privacy-mode
                // switch. The generation is already bumped and the
                // native calls already aborted, so nothing that
                // finishes late can be observed by its caller.
                joinedCleanly = false
                log(
                    "REST_EGRESS revoke_join_timeout generation=$gen reason=$reason " +
                        "pending=${toCancel.count { it.isActive }} budgetMs=$joinTimeoutMs",
                )
            }
        }

        log(
            "REST_EGRESS revoked generation=$gen reason=$reason " +
                "cancelled=${toCancel.size} nativeCancelled=$nativeCancelled " +
                "joinedCleanly=$joinedCleanly",
        )
        return RevocationResult(
            generation = gen,
            nativeCallsCancelled = nativeCancelled,
            dispatchesCancelled = toCancel.size,
            joinedCleanly = joinedCleanly,
        )
    }

    /**
     * Run one actual network dispatch under a revocable lease. See the
     * class kdoc for the semantics. [operation] is a short label used
     * only in diagnostics and the blocked-exception message.
     */
    suspend fun <T> dispatch(operation: String, block: suspend () -> T): T {
        val dispatchJob = Job(parent = currentCoroutineContext()[Job])

        // ── Linearized authorization ──────────────────────────────────
        // The policy read and the lease registration happen together
        // under the SAME lock revokeAndJoin takes. A revocation is
        // therefore ordered strictly before or strictly after this
        // block; it can no longer land between the decision and the
        // lease. R-N1.2 read the generation after deciding, which made
        // the re-check vacuous for exactly the interleaving that
        // mattered.
        val leaseGeneration = registryLock.withLock {
            val decision = decide()
            if (decision !is RestEgressDecision.DirectAllowed) {
                dispatchJob.cancel()
                log(
                    "REST_EGRESS blocked operation=$operation " +
                        "decision=${decision::class.simpleName}",
                )
                throw RestEgressBlockedException(operation, decision)
            }
            active += dispatchJob
            generation
        }

        var accepted = false
        try {
            afterAuthorizationHook?.invoke()
            // The lease generation travels with the coroutine so the
            // transport that opens the native call can be refused if its
            // lease has been revoked in the meantime.
            val result = withContext(dispatchJob + EgressLeaseContext(leaseGeneration)) {
                block()
            }
            beforeCompletionAcceptanceHook?.invoke()
            // R-N1.5: accepting the result and leaving the registry are
            // ONE atomic step under the same lock revokeAndJoin takes to
            // bump the generation and snapshot `active`.
            //
            // R-N1.4 read the generation here WITHOUT the lock and only
            // removed the job in the `finally` below. A revocation could
            // land in between: the check passed, revokeAndJoin then found
            // the job still registered, cancelled and joined it (the
            // block was already done, so the join returned at once) and
            // reported a clean switch — while this frame went on to
            // return the stale result to its caller.
            //
            // With one lock hold there are only two orders. This frame
            // wins: the generation still matches, the result is accepted,
            // and the job is deregistered so a later revocation neither
            // sees nor waits for it. The revocation wins: the job was in
            // its snapshot and was cancelled, the generation no longer
            // matches, and the result is discarded.
            accepted = registryLock.withLock {
                // The seam sits INSIDE the lock on purpose. A fixture can
                // hold the acceptance here and prove that a concurrent
                // revokeAndJoin CANNOT complete meanwhile — which is the
                // linearization property itself, and the only thing that
                // distinguishes this shape from R-N1.4's unlocked check
                // plus separate deregistration.
                duringAcceptanceHook?.invoke()
                val stillAuthorized = leaseGeneration == generation
                active -= dispatchJob
                stillAuthorized
            }
            if (!accepted) {
                throw CancellationException(
                    "rest egress revoked mid-dispatch: operation=$operation",
                )
            }
            return result
        } finally {
            // Only the failure paths still need to deregister; an
            // accepted completion already did it inside the lock above.
            if (!accepted) {
                registryLock.withLock { active -= dispatchJob }
            }
            dispatchJob.complete()
        }
    }

    companion object {
        /**
         * Join budget for [revokeAndJoin]. Sized above a healthy
         * abort-on-cancel (milliseconds) and far below the transports'
         * own call timeouts, so a cooperative transport always joins
         * cleanly and an uncooperative one is detected rather than
         * waited on.
         */
        const val DEFAULT_JOIN_TIMEOUT_MS: Long = 2_000L
    }
}

/**
 * Thrown by [RestEgressGate.dispatch] when the policy refuses Direct
 * egress. Callers treat it like any transport failure: durable state
 * (QUEUED messages, WAITING pre-key placeholders, upload retry state)
 * is preserved by the existing failure handling; nothing is discarded.
 * The message carries the operation label and decision name only.
 */
class RestEgressBlockedException(
    val operation: String,
    val decision: RestEgressDecision,
) : Exception(
    "rest egress blocked: operation=$operation decision=${decision::class.simpleName}",
)
