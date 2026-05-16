// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [RestInboundDeduplicator] — covers Vladislav's locked
 * ACK-after-persistence discipline (PR-D1b contract review 2026-05-16):
 *
 *  - First-time envelope id → [RestInboundDeduplicator.Action.Emit]
 *  - Duplicate while still pending DMS persist → [Action.SkipNoAck]
 *    (must NOT call ackInbound; envelope might not yet be saved)
 *  - Duplicate after DMS sendDeliveryAck was called → [Action.ReAck]
 *    (safe to re-ack; previous ack network call must have failed)
 *  - Expired entries fall out of the recent window
 *  - FIFO eviction at capacity
 */
class RestInboundDeduplicatorTest {

    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(deltaMs: Long) { nowMs += deltaMs }
    }

    private fun deduplicator(
        clock: FakeClock = FakeClock(),
        capacity: Int = 4,
        ttlMs: Long = 1_000L,
    ): RestInboundDeduplicator = RestInboundDeduplicator(
        nowMs = { clock.nowMs },
        capacity = capacity,
        ttlMs = ttlMs,
    )

    @Test
    fun first_time_id_emits() = runTest {
        val dedup = deduplicator()
        assertEquals(RestInboundDeduplicator.Action.Emit, dedup.resolve("env-1"))
    }

    @Test
    fun duplicate_while_pending_is_skip_no_ack() = runTest {
        val dedup = deduplicator()
        assertEquals(RestInboundDeduplicator.Action.Emit, dedup.resolve("env-1"))
        // Same id again, no ack between → DMS still processing → MUST NOT ack.
        assertEquals(RestInboundDeduplicator.Action.SkipNoAck, dedup.resolve("env-1"))
    }

    @Test
    fun duplicate_after_ack_is_reack() = runTest {
        val dedup = deduplicator()
        assertEquals(RestInboundDeduplicator.Action.Emit, dedup.resolve("env-1"))
        // DMS calls sendDeliveryAck → caller invokes markAcknowledged.
        dedup.markAcknowledged("env-1")
        // Server re-delivers because previous /relay/ack-deliver failed.
        assertEquals(RestInboundDeduplicator.Action.ReAck, dedup.resolve("env-1"))
    }

    @Test
    fun expired_entry_is_treated_as_fresh() = runTest {
        val clock = FakeClock()
        val dedup = deduplicator(clock = clock, ttlMs = 1_000L)
        assertEquals(RestInboundDeduplicator.Action.Emit, dedup.resolve("env-1"))
        dedup.markAcknowledged("env-1") // out of pendingAck
        clock.advance(1_500L)            // past TTL
        // Even though we previously saw env-1, it's past TTL — treat as new.
        // (Persistent ledger guard is authoritative at this horizon.)
        assertEquals(RestInboundDeduplicator.Action.Emit, dedup.resolve("env-1"))
    }

    @Test
    fun fifo_eviction_at_capacity() = runTest {
        val dedup = deduplicator(capacity = 3)
        dedup.resolve("a")
        dedup.resolve("b")
        dedup.resolve("c")
        dedup.resolve("d") // forces eviction of "a"
        // markAcknowledged so we can test re-emission semantics.
        dedup.markAcknowledged("a")
        // "a" was evicted from recentlyEmitted → reappears as Emit, not ReAck.
        assertEquals(RestInboundDeduplicator.Action.Emit, dedup.resolve("a"))
    }

    @Test
    fun two_distinct_pending_ids_both_skip_separately() = runTest {
        val dedup = deduplicator()
        assertEquals(RestInboundDeduplicator.Action.Emit, dedup.resolve("a"))
        assertEquals(RestInboundDeduplicator.Action.Emit, dedup.resolve("b"))
        // Both pending; each duplicate is SkipNoAck independently.
        assertEquals(RestInboundDeduplicator.Action.SkipNoAck, dedup.resolve("a"))
        assertEquals(RestInboundDeduplicator.Action.SkipNoAck, dedup.resolve("b"))
        // markAcknowledged on "a" only.
        dedup.markAcknowledged("a")
        // Now "a" can be re-acked, but "b" must still SkipNoAck.
        assertEquals(RestInboundDeduplicator.Action.ReAck, dedup.resolve("a"))
        assertEquals(RestInboundDeduplicator.Action.SkipNoAck, dedup.resolve("b"))
    }

    @Test
    fun snapshot_reports_correct_counts() = runTest {
        val dedup = deduplicator()
        dedup.resolve("a"); dedup.resolve("b"); dedup.resolve("c")
        dedup.markAcknowledged("a")
        val snap = dedup.snapshot()
        assertEquals(3, snap.recentSize)
        assertEquals(2, snap.pendingSize) // "a" was removed; b, c still pending
    }
}
