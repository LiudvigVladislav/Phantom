// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Identity of one WSS session attempt.
 *
 * Stage 2 (2026-09-13). The value is the transport's `wsSessionEpoch`,
 * bumped once per reconnect-loop iteration before the auth handshake, so
 * it is monotonic for the single transport instance a process owns and
 * a failed attempt burns an epoch like a successful one. Everything the
 * REST state machine decides about "is this the live socket" compares
 * these values; nothing compares wall-clock time or collector order.
 */
data class WsSessionId(val sessionEpoch: Long) : Comparable<WsSessionId> {
    override fun compareTo(other: WsSessionId): Int = sessionEpoch.compareTo(other.sessionEpoch)
    override fun toString(): String = "S($sessionEpoch)"
}

/**
 * One signal of the transport's single ordered session stream.
 *
 * Stage 2 B1: [KtorRelayTransport] owns ONE channel of these. Every
 * signal is enqueued by code that holds the session epoch it describes,
 * at the site where the fact becomes true, so the channel linearises the
 * enqueue order. The causal orderings the state machine relies on are
 * stated and tested rather than assumed:
 *
 *  (i)   `Connected(id)` is enqueued before that session's read loop can
 *        enqueue any `Activity(id)` -- the emission site precedes the
 *        loop start;
 *  (ii)  `Invalidated(old)` is enqueued in `forceReconnect` before the
 *        new loop is launched, hence before any `Connected(new)`;
 *  (iii) signals of two sessions may otherwise interleave (a zombie
 *        loop's late `Ended(old)`) and are resolved by freshness in
 *        [RestStateMachine], never by arrival order.
 *
 * [WsSessionLifecycleEvent.Connected] and [WsSessionLifecycleEvent.Ended]
 * are members of this hierarchy so the lifecycle stream that existed
 * before Stage 2 and the activity signals added by it travel through
 * the same channel and cannot be reordered against each other.
 */
sealed interface WsSessionSignal {
    val sessionId: WsSessionId

    /**
     * What a live socket did. An ordinary [Pong] is liveness only. A
     * [CandidateProof] is the correlated reply to the transport's single
     * candidate probe for this exact session epoch.
     */
    enum class ActivityKind { Frame, Ack, Pong, CandidateProof }

    /**
     * The relay pushed something on this session: a `Deliver` or `Ack`
     * text frame ([ActivityKind.Frame] / [ActivityKind.Ack]) or the
     * application-level pong ([ActivityKind.Pong]), or the correlated
     * one-shot candidate-probe reply ([ActivityKind.CandidateProof]).
     */
    data class Activity(
        override val sessionId: WsSessionId,
        val kind: ActivityKind,
    ) : WsSessionSignal

    /**
     * The idle watchdog of this session saw no inbound text frame for
     * [sinceLastInboundMs] (at least `INBOUND_STALL_THRESHOLD_MS`).
     */
    data class Stalled(
        override val sessionId: WsSessionId,
        val sinceLastInboundMs: Long,
    ) : WsSessionSignal

    /**
     * A per-envelope ACK deadline elapsed. [sessionId] is the session
     * that WROTE the frame, captured when the deadline was armed -- a
     * deadline armed on session N fires against N even if N+1 is live
     * by the time it expires.
     */
    data class AckDeadlineExpired(
        override val sessionId: WsSessionId,
        val msgId: String,
        val ageMs: Long,
    ) : WsSessionSignal

    /**
     * The transport abandoned or tore down this session on purpose
     * (`forceReconnect`, a teardown). Enqueued BEFORE the socket is
     * closed or the loop cancelled, so no later `Connected` can be
     * observed ahead of it.
     */
    data class Invalidated(
        override val sessionId: WsSessionId,
        val reason: String,
    ) : WsSessionSignal
}

/**
 * The receive side of the transport's session-signal channel, handed
 * out at most once at a time by [KtorRelayTransport.attachSessionSignalConsumer].
 *
 * The channel is backed by `receiveAsFlow()`, so a second concurrent
 * collector would STEAL elements rather than see a copy. The single
 * owner (the Android `HybridRelayTransport`) therefore holds this
 * subscription, launches exactly one collector on [signals], and on
 * shutdown cancels that collector and calls [detachAndJoin], which
 * waits until no collection of this subscription is active before it
 * frees the slot for a successor. It never cancels anything itself.
 */
class SessionSignalSubscription internal constructor(
    source: Flow<WsSessionSignal>,
    private val release: suspend (SessionSignalSubscription) -> Unit,
) {
    /**
     * Review round 7 (2026-09-13): admission and detachment are ONE
     * critical section.
     *
     * The first shape checked a flag at the start of collection and
     * counted collectors afterwards, so a collector could pass the check
     * and a concurrent `detachAndJoin` could then observe a zero count,
     * free the transport's slot, and let a successor attach while the
     * first collector was still about to read from the same channel --
     * the two would then steal elements from each other, which is
     * precisely what one-consumer ownership exists to prevent.
     */
    private val admission = Mutex()
    private val activeCollections = MutableStateFlow(0)
    private val detachedFlag = MutableStateFlow(false)

    /**
     * Review round 8 (2026-09-13): the ONE completion every caller of
     * [detachAndJoin] waits on.
     *
     * Setting the detached flag and returning was not a join. The second
     * caller saw the flag the first had just set and returned while the
     * first was still waiting for the collector to unwind, so a successor
     * could be told "the old consumer is gone" over a subscription whose
     * collector was still reading the channel. The flag says a decision
     * was taken; this deferred says the work is finished.
     */
    private val detachCompletion = CompletableDeferred<Unit>()

    /** Whether [detachAndJoin] has closed this subscription to new collectors. */
    val isDetached: Boolean get() = detachedFlag.value

    /** Number of collectors currently inside [signals]; diagnostic and test surface. */
    val activeCollectionCount: Int get() = activeCollections.value

    /**
     * The ordered signal stream.
     *
     * Collecting a detached subscription, or collecting one that is
     * already being collected, is a programming error and throws: a
     * channel-backed flow DISTRIBUTES elements between collectors, so a
     * second one does not observe the stream, it steals from the first.
     *
     * Review round 8, second pass (2026-09-13): the admission is
     * PER-COLLECTION, and that is the whole point of the shape below.
     *
     * This was `source.onStart { … }.onCompletion { … }`. `onCompletion`
     * runs when the upstream terminates for ANY reason, including the
     * `onStart` check throwing -- so a refused second collection, which
     * had never incremented the counter, decremented it on its way out.
     * With a genuine collector running, the count went to zero,
     * [detachAndJoin] stopped waiting, and the transport's consumer slot
     * was handed to a successor while the real collector was still
     * reading the channel. That is exactly the one-consumer ownership
     * this class exists to enforce, broken by its own bookkeeping.
     *
     * In a `flow { }` builder the increment happens BEFORE the `try`, so
     * a collection that never got past the checks cannot reach the
     * `finally`. Each collection now decrements if and only if it
     * incremented.
     */
    val signals: Flow<WsSessionSignal> = flow {
        admission.withLock {
            check(!detachedFlag.value) { "session signal subscription already detached" }
            check(activeCollections.value == 0) {
                "session signal subscription is already being collected"
            }
            activeCollections.value = activeCollections.value + 1
        }
        try {
            emitAll(source)
        } finally {
            // NonCancellable on purpose: the owner CANCELS its collector and
            // then joins here, so this runs in an already-cancelled
            // coroutine. Without it the mutex acquire would throw before the
            // count came down and [detachAndJoin] would wait for ever.
            withContext(NonCancellable) {
                admission.withLock { activeCollections.value = activeCollections.value - 1 }
            }
        }
    }

    /**
     * Close this subscription to new collectors, wait until the current
     * one has unwound, then free the transport's consumer slot. The owner
     * cancels its collector FIRST; this call only joins.
     *
     * Idempotent AND a real join: exactly one caller performs the detach,
     * every other caller -- concurrent or later -- waits for that same
     * completion before returning. No caller returns while the slot is
     * still held.
     *
     * A detach that FAILS is terminal for this subscription and the
     * failure is handed to every waiter. That is the fail-closed choice:
     * the transport's slot stays held, so a successor is refused, rather
     * than admitted over a collector nobody confirmed had stopped.
     */
    suspend fun detachAndJoin() {
        val mine = admission.withLock {
            val was = detachedFlag.value
            // Taken BEFORE the wait: from here no new collector is admitted,
            // so the count this call waits for can only fall.
            detachedFlag.value = true
            !was
        }
        if (!mine) {
            // NonCancellable: this caller asked for the slot to be free
            // before it continues, and a cancellation here would hand it
            // back a promise that had not been kept.
            withContext(NonCancellable) { detachCompletion.await() }
            return
        }
        try {
            activeCollections.first { it == 0 }
            release(this)
        } catch (t: Throwable) {
            detachCompletion.completeExceptionally(t)
            throw t
        }
        detachCompletion.complete(Unit)
    }
}
