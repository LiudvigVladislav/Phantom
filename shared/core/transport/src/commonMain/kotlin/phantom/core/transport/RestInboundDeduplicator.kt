// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Two-state dedup tracker for REST-poll-sourced envelopes — PR-D1b.
 *
 * The orchestrator's `/relay/poll` loop has no memory of what it previously
 * emitted: if a recipient client's `/relay/ack-deliver` call fails, the
 * relay keeps the envelope and the next poll returns the same id. Without
 * a client-side gate, that duplicate emission would reach
 * [phantom.core.messaging.DefaultMessagingService] (DMS) and attempt to
 * decrypt and persist the same envelope a second time — guaranteed MAC
 * failure on the now-advanced ratchet.
 *
 * This tracker distinguishes three cases for an incoming envelope id:
 *
 *  - [Action.Emit] — first time we've seen this id within the TTL window;
 *                    emit it downstream and mark it as `pendingAck`. The
 *                    caller (`HybridRelayTransport`) emits a `Deliver` into
 *                    the merged inbound flow and waits for DMS to call
 *                    `sendDeliveryAck` after persist.
 *  - [Action.SkipNoAck] — we recently emitted this id AND it is still
 *                    marked `pendingAck` (i.e. DMS has not yet finished
 *                    decrypt + save). Do NOT emit again, but critically
 *                    also do NOT ack: acking now would tell the relay the
 *                    envelope is durably stored when it might not be. The
 *                    server will re-deliver on the next poll if needed.
 *  - [Action.ReAck] — we recently emitted this id AND it is no longer
 *                    `pendingAck` (i.e. DMS already called sendDeliveryAck,
 *                    which means it persisted the envelope). The previous
 *                    ack call must have failed at the orchestrator's
 *                    network layer, otherwise the server would have
 *                    removed the envelope from its queue. Safe to re-ack.
 *
 * The pending-vs-completed distinction is the ACK-after-persistence
 * invariant Vladislav locked in the 2026-05-16 contract review. Without
 * it the dedup path can ack envelopes that DMS is still in the middle of
 * processing, breaking the at-least-once delivery guarantee.
 *
 * Capacity: FIFO eviction beyond [capacity] entries to bound memory.
 * TTL: entries fall out of the recent window after [ttlMs] — by then
 * [phantom.core.storage.ProcessedEnvelopeRepository] is authoritative.
 *
 * Threading: all public methods suspend and serialise through an internal
 * [Mutex]. Safe to call concurrently from any number of collectors.
 */
class RestInboundDeduplicator(
    private val nowMs: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    /** Outcome the caller acts on. See class kdoc. */
    enum class Action { Emit, SkipNoAck, ReAck }

    private val lock = Mutex()

    /** envelope_id → first-seen timestamp, FIFO-evicted at [capacity]. */
    private val recentlyEmitted = LinkedHashMap<String, Long>()

    /** envelope_id that has been emitted but DMS has not yet acked. */
    private val pendingAck = mutableSetOf<String>()

    /**
     * Resolve what to do with an incoming REST poll envelope id.
     *
     * **Precedence rule (locked in 2026-05-16 follow-up review):**
     * `pendingAck` is checked FIRST, before `recentlyEmitted`. A pending id
     * that has been evicted from `recentlyEmitted` (by TTL or capacity)
     * still must NOT re-emit — DMS is in the middle of decrypt + persist
     * and a second emission would attempt the same MAC verification on the
     * now-advanced ratchet, guaranteed to fail. Only after
     * [markAcknowledged] flips the id out of `pendingAck` can a subsequent
     * duplicate be ack'd or treated as fresh.
     *
     * Because `pendingAck` follows DMS's lifecycle (not the recent window),
     * an id may stay pending longer than [ttlMs]; that is intentional. The
     * persistent [phantom.core.storage.ProcessedEnvelopeRepository] ledger
     * guard catches the cross-horizon case at the caller level.
     */
    suspend fun resolve(envelopeId: String): Action = lock.withLock {
        val now = nowMs()
        // Drop entries past TTL so the recent window stays tight.
        evictExpired(now)
        val recentTs = recentlyEmitted[envelopeId]
        val isRecent = recentTs != null && (now - recentTs) < ttlMs
        val isPending = envelopeId in pendingAck

        when {
            // PRIORITY: pending overrides recent-window state. A pending id
            // that aged out of recentlyEmitted is STILL not safe to re-emit.
            isPending -> Action.SkipNoAck
            isRecent -> Action.ReAck
            else -> {
                // First time (or far enough past TTL to count as fresh).
                recentlyEmitted[envelopeId] = now
                pendingAck.add(envelopeId)
                while (recentlyEmitted.size > capacity) {
                    val oldest = recentlyEmitted.keys.iterator().next()
                    recentlyEmitted.remove(oldest)
                    // Note: pendingAck is intentionally NOT pruned here;
                    // it follows DMS's sendDeliveryAck lifecycle, not
                    // capacity-eviction. An id that pendingAck'd long
                    // ago can still be cleaned via [markAcknowledged].
                }
                Action.Emit
            }
        }
    }

    /**
     * Called by the caller's `sendDeliveryAck(messageId)` path after DMS
     * has finished persisting and is requesting the REST `/relay/ack-deliver`
     * round-trip. Removes the id from `pendingAck` so a subsequent
     * duplicate poll-emission is treated as [Action.ReAck] (safe to ack)
     * rather than [Action.SkipNoAck] (still processing).
     */
    suspend fun markAcknowledged(envelopeId: String) {
        lock.withLock {
            pendingAck.remove(envelopeId)
        }
    }

    /**
     * Returns true iff [envelopeId] was emitted via REST and is still
     * awaiting DMS's `sendDeliveryAck` (i.e. in [pendingAck]). Used by
     * the wrapper's ACK-routing path to decide whether an outbound delivery
     * ack should go via REST or via the WS transport.
     */
    suspend fun isPending(envelopeId: String): Boolean = lock.withLock {
        envelopeId in pendingAck
    }

    /**
     * Diagnostic counters. Public for testing / debug. Lock-free read
     * snapshot — values may be off-by-one mid-mutation, which is fine
     * for telemetry but not for correctness decisions.
     */
    fun snapshot(): Snapshot = Snapshot(
        recentSize = recentlyEmitted.size,
        pendingSize = pendingAck.size,
    )

    data class Snapshot(val recentSize: Int, val pendingSize: Int)

    private fun evictExpired(now: Long) {
        // recentlyEmitted is insertion-ordered (LinkedHashMap); the
        // expired ones cluster at the head, so we can short-circuit
        // once a non-expired entry is found.
        val iter = recentlyEmitted.entries.iterator()
        while (iter.hasNext()) {
            val (_, ts) = iter.next()
            if ((now - ts) < ttlMs) break
            iter.remove()
        }
    }

    companion object {
        /**
         * Maximum number of recently-emitted ids retained for dedup.
         * 256 covers a long worst-case poll burst (~64 b per entry =
         * ~16 KB, negligible) without unbounded growth.
         */
        const val DEFAULT_CAPACITY: Int = 256

        /**
         * After this many ms a recent id falls out of the window. The
         * persistent [phantom.core.storage.ProcessedEnvelopeRepository]
         * ledger guard takes over at that point; the in-memory cache is
         * only the fast path for the race window between emit and
         * DMS's `markProcessed`.
         */
        const val DEFAULT_TTL_MS: Long = 5 * 60_000L
    }
}
