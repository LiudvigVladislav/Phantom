// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The long-lived WSS session, as something that can be started under a
 * permit and stopped with confirmation.
 *
 * ## Why this is a component
 *
 * R-N1.17 P1. An earlier revision started the connect loop with a bare
 * `scope.launch` in the service and passed an empty `openSocket` to the
 * permit. Five things were wrong with it, and none was visible to any
 * test:
 *
 *  1. the Job was a SIBLING of the chain walk, not its child - so an
 *     ownership handover could cancel and successfully join the walk
 *     while the WSS Job carried on;
 *  2. teardown ran disconnect, then join, then cancel - the cancel last,
 *     after the thing it was meant to interrupt had already been waited
 *     for;
 *  3. nothing joined again after that cancel, so "stopped" was never
 *     confirmed;
 *  4. the outer per-permit budget was SHORTER than the inner teardown
 *     budget, so the outer one always fired first and the cancel line
 *     was unreachable;
 *  5. the permit was released in a cancellable `finally`, while the
 *     sibling Job could still be running.
 *
 * And the window that made it dangerous rather than merely untidy:
 * `launch` returns before the body runs, so the permit considered the
 * socket open while nothing had started. A switch landing there called
 * `disconnect()` on a transport that had not connected yet, and the Job
 * then opened a WSS under a policy that had already been revoked.
 *
 * Holding the lifecycle here means the ordering is one object's job and
 * a fixture can drive it.
 */
class TransportSession(
    /**
     * MUST be a scope whose Job is the owner of the chain walk, so the
     * session is a CHILD of it. A sibling outlives a handover that
     * believed it had stopped everything.
     */
    private val ownerScope: CoroutineScope,
    /** The connect loop. Does not return until the transport disconnects. */
    private val connectLoop: suspend () -> Unit,
    /** Close the transport. Called AFTER the loop has been cancelled. */
    private val closeTransport: suspend () -> Unit,
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS,
    private val log: ((String) -> Unit)? = null,
) {
    private var job: Job? = null

    /**
     * Start the loop. Called from inside the permit's critical section,
     * so it must not suspend for long - it only launches.
     *
     * Started LAZY and then started explicitly: a cancellation arriving
     * between the launch and the first resumption cancels it before it
     * can open a socket under a policy that has since been revoked.
     *
     * Measured honestly: `start()` starts the Job immediately after
     * assigning it, so laziness buys only that ordering - the field is
     * written before the body can run. Every mutation of this into an
     * eager launch is passed by the whole suite, including one written
     * on an immediate dispatcher specifically to catch it. It is kept
     * because the ordering is free and the window is real if `start()`
     * ever grows work between the two lines; it is NOT claimed to be
     * covered.
     */
    fun start() {
        val created = ownerScope.launch(start = CoroutineStart.LAZY) { connectLoop() }
        job = created
        created.start()
        log?.invoke("SESSION started")
    }

    /**
     * Cancel the loop, close the transport, and confirm the loop is
     * finished.
     *
     * Returns false when the loop could not be confirmed stopped inside
     * [stopTimeoutMs] - and the caller must then treat whatever it was
     * doing as unsuccessful, exactly as a failed permit revocation does.
     *
     * The order is cancel, then close, then join. Closing first would
     * mean waiting on a loop nobody had told to stop.
     */
    suspend fun stop(reason: String): Boolean {
        val running = job ?: return true
        running.cancel(CancellationException(reason))
        var closed = false
        // ONE budget over the close AND the join. The budget used to
        // cover only the join, so a wedged close was bounded by nothing
        // but the caller's own timeout - the session's advertised budget
        // did not apply to the step most likely to hang.
        val finished = withTimeoutOrNull(stopTimeoutMs) {
            closed = runCatching { closeTransport() }
                .onFailure {
                    log?.invoke(
                        "SESSION close_failed reason=$reason error=${it::class.simpleName}",
                    )
                }
                .isSuccess
            // Joined even when the close failed: the loop was cancelled
            // and its state is worth knowing either way.
            running.join()
            true
        } != null
        // Success requires BOTH. Swallowing the close failure and
        // reporting the join alone let a permit count as closed while
        // the socket teardown may never have happened.
        val stopped = closed && finished
        log?.invoke(
            "SESSION stop reason=$reason closed=$closed joined=$finished confirmed=$stopped",
        )
        return stopped
    }

    /** Wait for the loop to end on its own. Outside any permit lock. */
    suspend fun awaitCompletion() {
        job?.join()
    }

    /**
     * Run [block] once the session has finished, non-cancellably.
     *
     * The permit release goes here: releasing it in an ordinary
     * `finally` runs while the coroutine is being cancelled, so it can be
     * skipped at a suspension point - leaving a permit registered
     * against a socket that has closed.
     */
    suspend fun afterCompletion(block: suspend () -> Unit) {
        withContext(NonCancellable) {
            // JOIN first, inside NonCancellable.
            //
            // `awaitCompletion()` throws immediately once the owner is
            // cancelled, so a caller that wrapped it in `runCatching`
            // reached here while the child was still finishing - and then
            // released the permit and the connect lease out from under a
            // live session. Being a child does not help: the owner clears
            // ownership by hand, and it is still alive when it does.
            job?.join()
            block()
        }
    }

    companion object {
        /**
         * How long a stop waits for the loop to finish.
         *
         * MUST stay below [PrivacyModeCoordinator.PERMIT_ATTEMPT_TIMEOUT_MS].
         * When it was larger the outer per-permit budget always fired
         * first, the inner wait never completed, and the code after it
         * was unreachable - a bound that bounded nothing.
         */
        const val DEFAULT_STOP_TIMEOUT_MS: Long = 750L
    }
}

/** Thrown by a revocation whose session could not be confirmed stopped. */
class SessionNotStoppedException(reason: String) : Exception(
    "the WSS session could not be confirmed stopped within its budget ($reason)",
)
