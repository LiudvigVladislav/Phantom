// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Audit ROUND-30.16 — the delivery postcondition.
 *
 * The 2026-08-26 physical smoke produced two
 * `recipient_deliver_received dedup_gate=fresh` for one envelope and
 * nothing else: no persist, no ack, twice. The shipped evidence stream
 * never said a delivery had FAILED, so the run recorded a symptom and
 * lost the event.
 *
 * The reason it could not be recorded from inside the handler is that
 * `handleDeliver` leaves without settling from dozens of places, and one
 * of them — the hold-on-MAC branch — returns `null` rather than throwing.
 * An `onFailure`-only diagnostic would have missed exactly the case that
 * motivated it. So the observation lives at the outer boundary, and the
 * decision it makes is this pure function.
 *
 * Every combination below is asserted directly rather than being
 * reachable only through a live delivery, because a decision table that
 * is only exercised end-to-end is a decision table with untested rows.
 */
class DeliveryOutcomeObserverTest {

    private fun classify(
        decrypted: Boolean = false,
        persisted: Boolean = false,
        ledgerMarked: Boolean? = false,
        ackSent: Boolean = false,
        threw: Boolean = false,
        held: Boolean? = false,
    ) = classifyDeliveryOutcome(decrypted, persisted, ledgerMarked, ackSent, threw, held)

    // ── scenario 6: the settled path reports nothing ──────────────

    @Test
    fun a_fully_successful_delivery_reports_no_failure() {
        assertNull(
            classify(decrypted = true, persisted = true, ledgerMarked = true, ackSent = true),
            "a delivery that persisted, marked the ledger and acked has settled",
        )
    }

    // ── scenario 1: silent return null with a held row ────────────

    @Test
    fun a_silent_return_with_a_held_row_is_reported_as_held() {
        val (failure, stage) = classify(held = true)!!
        assertEquals(WssDiagBridge.DeliverFailure.HELD, failure)
        assertEquals(WssDiagBridge.DeliverStage.RECEIVED, stage)
    }

    // ── scenario 2: an exception escaped ──────────────────────────

    @Test
    fun a_thrown_exception_is_reported_as_threw() {
        val (failure, stage) = classify(threw = true)!!
        assertEquals(WssDiagBridge.DeliverFailure.THREW, failure)
        assertEquals(WssDiagBridge.DeliverStage.RECEIVED, stage)
    }

    @Test
    fun threw_outranks_held_because_the_exception_is_the_stronger_fact() {
        val (failure, _) = classify(threw = true, held = true)!!
        assertEquals(WssDiagBridge.DeliverFailure.THREW, failure)
    }

    // ── scenarios 3, 4, 5: where the path stopped ─────────────────

    @Test
    fun a_persist_failure_stops_at_decrypted() {
        val (failure, stage) = classify(decrypted = true, threw = true)!!
        assertEquals(WssDiagBridge.DeliverFailure.THREW, failure)
        assertEquals(WssDiagBridge.DeliverStage.DECRYPTED, stage)
    }

    @Test
    fun a_ledger_mark_failure_stops_at_persisted() {
        val (failure, stage) = classify(decrypted = true, persisted = true, threw = true)!!
        assertEquals(WssDiagBridge.DeliverFailure.THREW, failure)
        assertEquals(WssDiagBridge.DeliverStage.PERSISTED, stage)
    }

    @Test
    fun an_ack_failure_stops_at_ledger_marked() {
        val (failure, stage) = classify(
            decrypted = true, persisted = true, ledgerMarked = true, threw = true,
        )!!
        assertEquals(WssDiagBridge.DeliverFailure.THREW, failure)
        assertEquals(WssDiagBridge.DeliverStage.LEDGER_MARKED, stage)
    }

    // ── scenario 7: unexplained, fail-closed ──────────────────────

    @Test
    fun an_unexplained_silent_return_is_fail_closed() {
        val (failure, stage) = classify()!!
        assertEquals(WssDiagBridge.DeliverFailure.UNKNOWN_PROCESSING_FAILURE, failure)
        assertEquals(WssDiagBridge.DeliverStage.RECEIVED, stage)
    }

    @Test
    fun an_unqueryable_hold_store_is_fail_closed_not_assumed_benign() {
        // `held = null` means the store could not answer, NOT that the
        // envelope was unheld. Reporting UNKNOWN_PROCESSING_FAILURE here
        // is the whole point of the tri-state.
        val (failure, _) = classify(held = null)!!
        assertEquals(WssDiagBridge.DeliverFailure.UNKNOWN_PROCESSING_FAILURE, failure)
    }

    @Test
    fun an_unqueryable_ledger_never_counts_as_settled() {
        // persisted + acked but the ledger cannot confirm: this is NOT a
        // settled delivery. Treating `null` as `true` would let a broken
        // ledger silence the diagnostic.
        val v = classify(decrypted = true, persisted = true, ledgerMarked = null, ackSent = true)
        assertEquals(WssDiagBridge.DeliverFailure.UNKNOWN_PROCESSING_FAILURE, v!!.first)
        assertEquals(WssDiagBridge.DeliverStage.ACK_SENT, v.second)
    }

    @Test
    fun a_missing_ack_alone_is_enough_to_report_a_failure() {
        val v = classify(decrypted = true, persisted = true, ledgerMarked = true, ackSent = false)
        assertEquals(WssDiagBridge.DeliverStage.LEDGER_MARKED, v!!.second)
    }

    @Test
    fun a_missing_persist_alone_is_enough_to_report_a_failure() {
        val v = classify(decrypted = true, persisted = false, ledgerMarked = true, ackSent = true)
        assertEquals(WssDiagBridge.DeliverStage.ACK_SENT, v!!.second)
    }
}
