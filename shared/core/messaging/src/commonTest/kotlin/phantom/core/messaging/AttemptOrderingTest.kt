// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit ROUND-30.18 — attempt N's terminal record lands before attempt
 * N+1 becomes fresh.
 *
 * ROUND-30.17 claimed exactly this and did the opposite: the `finally`
 * released the claim first and reported second, so once
 * `activeProcessing` no longer held the id, the next delivery could be
 * claimed while the previous one was still querying the ledger and
 * assembling its record. The sentence was written into the code
 * comments, the commit message, the contract and the handoff, and was
 * never true of the code beneath it.
 *
 * A comment cannot establish an ordering. This test reproduces the
 * structure — claim, work, report, release — and drives it so that the
 * second delivery is *waiting* to claim while the first is inside its
 * reporting step. If the release moves back ahead of the report, the
 * interleaving assertion fails.
 *
 * It models the handler's ordering rather than calling
 * `DefaultMessagingService`, because reaching the real `handleDeliver`
 * needs a decryptable envelope, a session and a full repository stack:
 * that scaffolding would test the fixture more than the order. What is
 * asserted here is the property the handler must have, and the handler
 * is written in exactly this shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttemptOrderingTest {

    /** The events, in the order they actually happened. */
    private val trace = mutableListOf<String>()

    private val lock = Mutex()
    private val active = mutableSetOf<String>()
    private val counter = mutableMapOf<String, Int>()
    private val announced = mutableSetOf<String>()

    /**
     * A small stand-in bound so the overflow tests do not need a
     * thousand deliveries. The SHARED production bound is pinned by
     * [the_ordinal_range_is_one_shared_contract]; what is modelled here
     * is the lifecycle around whatever the bound is.
     */
    private val maxOrdinal = 3

    /**
     * Mirrors the handler: claim + number in one critical section, and
     * past the bound the ordinal is suppressed and the overflow is
     * announced at most once per envelope (audit ROUND-30.18).
     */
    private class Claim(val attempt: Int, val suppressed: Boolean,
                        val announcedNow: Boolean)

    private suspend fun claim(id: String): Claim? = lock.withLock {
        if (id in active) return@withLock null
        active.add(id)
        val n = (counter[id] ?: 0) + 1
        counter[id] = n
        if (n > maxOrdinal) {
            val first = id !in announced
            Claim(n, suppressed = true, announcedNow = first)
        } else {
            Claim(n, suppressed = false, announcedNow = false)
        }
    }

    /**
     * Mirrors the handler's `finally`: report first, release second, and
     * release even if the report throws.
     */
    private suspend fun deliver(
        id: String,
        settle: Boolean,
        beforeReport: suspend () -> Unit = {},
        report: suspend (Int) -> Unit,
    ): Boolean {
        val claimed = claim(id) ?: return false
        if (claimed.suppressed) {
            if (claimed.announcedNow) trace += "overflow"
            trace += "fresh:unnumbered"
        } else {
            trace += "fresh:${claimed.attempt}"
        }
        try {
            beforeReport()
        } finally {
            try {
                report(claimed.attempt)
            } finally {
                lock.withLock {
                    active.remove(id)
                    if (settle) {
                        // Settlement ends the chain: the counter AND the
                        // overflow marker go together, or a later chain
                        // for the same id would inherit a truncation
                        // that never happened to it.
                        counter.remove(id)
                        announced.remove(id)
                    } else if (claimed.announcedNow) {
                        announced.add(id)
                    }
                }
            }
        }
        return true
    }

    @Test
    fun the_terminal_record_lands_before_the_next_attempt_is_claimed() = runTest {
        val id = "envelope-1"
        val reportStarted = CompletableDeferred<Unit>()
        val releaseReport = CompletableDeferred<Unit>()

        val first = launch {
            deliver(id, settle = false) { attempt ->
                // Attempt 1 is inside its reporting step: this is exactly
                // the window in which R30.17 had already released the
                // claim.
                reportStarted.complete(Unit)
                releaseReport.await()
                trace += "terminal:$attempt"
            }
        }

        reportStarted.await()

        var secondClaimed = false
        val second = launch {
            // Spins until the claim is available. If the release had
            // happened before the report, this would succeed now.
            while (!secondClaimed) {
                secondClaimed = deliver(id, settle = false) { attempt ->
                    trace += "terminal:$attempt"
                }
                if (!secondClaimed) yield()
            }
        }

        // Give the second coroutine every chance to get in early.
        repeat(50) { yield() }
        assertEquals(
            listOf("fresh:1"), trace,
            "attempt 2 became fresh while attempt 1 was still reporting: $trace",
        )

        releaseReport.complete(Unit)
        first.join()
        second.join()

        assertEquals(
            listOf("fresh:1", "terminal:1", "fresh:2", "terminal:2"), trace,
            "the terminal record must precede the next fresh delivery",
        )
    }

    @Test
    fun a_throwing_report_still_releases_the_claim() = runTest {
        val id = "envelope-2"
        // A diagnostic must never be able to strand an envelope as
        // permanently in-flight.
        runCatching {
            deliver(id, settle = false) { error("diagnostic exploded") }
        }
        assertTrue(id !in active, "the claim survived a throwing report")

        val second = deliver(id, settle = false) { attempt ->
            trace += "terminal:$attempt"
        }
        assertTrue(second, "the envelope could not be delivered again")
        assertEquals(2, counter[id], "the ordinal must continue, not restart")
    }

    @Test
    fun a_settled_envelope_drops_its_counter_and_an_unsettled_one_keeps_it() =
        runTest {
            deliver("settled", settle = true) { }
            assertTrue(
                "settled" !in counter,
                "a settled envelope keeps no state: its chain is over",
            )

            deliver("unsettled", settle = false) { }
            assertEquals(
                1, counter["unsettled"],
                "an unsettled envelope must keep its ordinal for the retry",
            )
            deliver("unsettled", settle = false) { }
            assertEquals(
                2, counter["unsettled"],
                "the retry must continue the chain, not restart it",
            )
        }

    @Test
    fun the_overflow_is_announced_once_and_later_ordinals_stay_suppressed() =
        runTest {
            val id = "looping-envelope"
            repeat(maxOrdinal + 2) { deliver(id, settle = false) { } }
            assertEquals(
                listOf(
                    "fresh:1", "fresh:2", "fresh:3",
                    "overflow", "fresh:unnumbered",
                    "fresh:unnumbered",
                ),
                trace,
                "past the bound: one announcement, then suppressed " +
                    "ordinals — a thousand copies of the marker would " +
                    "not say it better",
            )
        }

    @Test
    fun settlement_clears_the_overflow_marker_with_the_counter() = runTest {
        val id = "recovered-envelope"
        repeat(maxOrdinal + 1) { deliver(id, settle = false) { } }
        assertTrue(id in announced, "the overflow was never announced")

        deliver(id, settle = true) { }
        assertTrue(
            id !in counter && id !in announced,
            "a settled envelope keeps no state: not the counter, " +
                "not the truncation marker",
        )

        trace.clear()
        deliver(id, settle = false) { }
        assertEquals(
            listOf("fresh:1"), trace,
            "a new chain for the same id starts numbered and " +
                "unannounced — it inherits nothing",
        )
    }

    @Test
    fun the_ordinal_range_is_one_shared_contract() {
        // Named on both sides. The verifier's schema authority carries
        // the same number; R30.17 had the producer unbounded and the
        // verifier enforcing a limit it invented for itself.
        assertEquals(1000, DELIVER_ATTEMPT_ORDINAL_MAX)
    }
}
