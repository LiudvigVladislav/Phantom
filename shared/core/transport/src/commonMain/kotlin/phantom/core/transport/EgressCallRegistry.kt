// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * N1-F2 R-N1.3 / R-N1.4 — the missing half of revocation, made
 * generation-aware.
 *
 * R-N1.2 cancelled coroutine `Job`s and assumed that stopped the
 * network. It did not: every production Android transport blocks in
 * OkHttp `Call.execute()`, which is not cooperative with coroutine
 * cancellation. R-N1.3 added this registry so a revocation could invoke
 * `Call.cancel()` — the only supported way to abort an in-flight OkHttp
 * call. Thread interruption is deliberately not used: OkHttp does not
 * guarantee that a blocking socket read aborts on interrupt.
 *
 * ## R-N1.4: the snapshot was still a race
 *
 * R-N1.3's `cancelAll` took a one-shot snapshot of the live handles and
 * cancelled those. A dispatch that had ALREADY been authorized under the
 * old posture, but had not yet reached its `register` call, was absent
 * from that snapshot — so it registered afterwards and went on to
 * `execute()`. Cancelling its coroutine `Job` did not help, because the
 * blocking call ignores that. Direct I/O therefore continued after the
 * revocation had completed, which is exactly the "No Silent Downgrade"
 * violation the gate exists to prevent.
 *
 * The registry is now generation-aware and linear with the gate's lease:
 *
 *  - [RestEgressGate.dispatch] carries its lease generation into the
 *    coroutine context as an [EgressLeaseContext];
 *  - [cancelAll] records `revokedThrough` and takes its snapshot inside
 *    the SAME lock a [register] must hold;
 *  - a registration whose lease is at or below `revokedThrough` is
 *    REFUSED, and its cancellation handle is invoked immediately.
 *
 * A late registration is therefore either already in the snapshot (and
 * cancelled) or refused before `execute()`. There is no third case.
 *
 * A caller with no [EgressLeaseContext] in scope — a transport used
 * directly, outside any gate, as several tests do — is never refused. It
 * holds no lease, so there is nothing to revoke.
 *
 * Handles carry no request content — only the ability to abort.
 */
fun interface EgressCancellable {
    /**
     * Abort the in-flight native call. Must be safe to invoke from a
     * thread other than the one blocked in the call, safe to invoke
     * before the call starts or after it has finished, and must not
     * throw.
     */
    fun cancelInFlight()
}

/**
 * Carries the gate lease generation of the current dispatch down to the
 * transport that is about to open a native call. Propagates through
 * `withContext(Dispatchers.IO)` like any other context element, so the
 * adapters need no new parameters.
 */
class EgressLeaseContext(
    val generation: Long,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<EgressLeaseContext>
}

class EgressCallRegistry(
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var nextToken: Long = 0L
    private val live = mutableMapOf<Long, EgressCancellable>()

    /**
     * Every lease generation at or below this value has been revoked. A
     * registration carrying such a lease is refused. `-1` means nothing
     * has been revoked yet; gate generations start at 0.
     */
    @Volatile
    private var revokedThrough: Long = -1L

    /** Number of native calls currently registered (diagnostic/test). */
    suspend fun liveCount(): Int = mutex.withLock { live.size }

    /** Highest revoked lease generation (diagnostic/test). */
    val revokedThroughGeneration: Long get() = revokedThrough

    /**
     * Register [cancellable] for the duration of a native call.
     *
     * Returns the de-registration token, or `null` when the caller's
     * lease has already been revoked — in which case [cancellable] has
     * already been invoked and the caller MUST NOT proceed to the
     * network.
     */
    suspend fun register(cancellable: EgressCancellable): Long? {
        val lease = currentCoroutineContext()[EgressLeaseContext]?.generation
        val token = mutex.withLock {
            if (lease != null && lease <= revokedThrough) {
                null
            } else {
                val t = ++nextToken
                live[t] = cancellable
                t
            }
        }
        if (token == null) {
            // Refused: abort the handle anyway, so a transport that
            // ignores the return value still cannot complete a Direct
            // call under a revoked lease.
            try {
                cancellable.cancelInFlight()
            } catch (_: Throwable) {
            }
            log(
                "REST_EGRESS late_registration_refused lease=$lease " +
                    "revokedThrough=$revokedThrough",
            )
        }
        return token
    }

    /**
     * [register], but throws instead of returning `null`, so a caller
     * cannot proceed by ignoring the result. [operation] is a short
     * label used only in diagnostics.
     */
    suspend fun registerOrRefuse(operation: String, cancellable: EgressCancellable): Long =
        register(cancellable) ?: throw RestEgressBlockedException(
            operation,
            RestEgressDecision.AnonymousRequiredButUnavailable,
        )

    suspend fun unregister(token: Long) {
        mutex.withLock { live.remove(token) }
    }

    /**
     * Abort every registered in-flight native call, and refuse any later
     * registration carrying a lease at or below [revokedThroughLease].
     *
     * The bookkeeping and the snapshot happen inside one lock hold, so a
     * concurrent [register] is ordered strictly before this call (and is
     * therefore in the snapshot) or strictly after it (and is therefore
     * refused). Returns how many handles were invoked.
     */
    suspend fun cancelAll(reason: String, revokedThroughLease: Long = Long.MAX_VALUE): Int {
        val snapshot = mutex.withLock {
            if (revokedThroughLease > revokedThrough) {
                revokedThrough = revokedThroughLease
            }
            live.values.toList()
        }
        for (handle in snapshot) {
            try {
                handle.cancelInFlight()
            } catch (_: Throwable) {
                // A cancellation handle must not throw; if one does, the
                // remaining handles still get cancelled.
            }
        }
        if (snapshot.isNotEmpty()) {
            log(
                "REST_EGRESS native_calls_cancelled count=${snapshot.size} reason=$reason " +
                    "revokedThrough=$revokedThrough",
            )
        }
        return snapshot.size
    }
}

/**
 * Register [cancellable] for the duration of [block].
 *
 * Throws [RestEgressBlockedException] if the caller's lease is already
 * revoked, so the block never runs. The de-registration runs under
 * [NonCancellable] so a cancelled coroutine still cleans up its entry —
 * otherwise a cancelled call would leave a dead handle that a later
 * [EgressCallRegistry.cancelAll] would pointlessly invoke, and
 * [EgressCallRegistry.liveCount] could never return to zero.
 */
suspend fun <T> EgressCallRegistry?.withRegisteredCall(
    operation: String,
    cancellable: EgressCancellable,
    block: suspend () -> T,
): T {
    val registry = this ?: return block()
    val token = registry.registerOrRefuse(operation, cancellable)
    try {
        return block()
    } finally {
        withContext(NonCancellable) { registry.unregister(token) }
    }
}
