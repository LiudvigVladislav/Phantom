// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Audit ROUND-30.16 — the wire tokens of `recipient_deliver_failed`.
 *
 * The verifier decides against a closed vocabulary held in
 * `schema_wss3.DELIVER_FAILURE_ENUM` / `DELIVER_STAGE_ENUM`. Those sets
 * and these enums live in different languages and different
 * repositories-within-a-repository, so nothing but a test makes them
 * agree. Without this, renaming a Kotlin enum member would keep
 * compiling, keep emitting, and quietly start producing an event the
 * shipped verifier rejects as a schema violation — the failure would
 * surface on a physical device, days later, as an unexplained RED.
 *
 * The literals below are duplicated ON PURPOSE. They are the wire
 * contract; deriving them from the enum would make the test agree with
 * whatever the enum happens to say.
 */
class DeliverFailedSchemaTest {

    private val captured = mutableListOf<String>()

    @Before
    fun installSink() {
        WssDiag.testSink = { line -> captured += line }
    }

    @After
    fun resetSink() {
        WssDiag.testSink = null
        captured.clear()
    }

    private fun emitFailure(
        failure: WssDiag.DeliverFailure,
        stage: WssDiag.DeliverStage,
    ): String {
        captured.clear()
        WssDiag.emit(
            event = "recipient_deliver_failed",
            role = WssDiag.Role.RECIPIENT,
            correlationId = "cid-1",
            deliverFailure = failure,
            deliverStage = stage,
        )
        assertEquals("exactly one line per emit", 1, captured.size)
        return captured.single()
    }

    // ── the closed vocabularies, member by member ─────────────────

    @Test
    fun deliver_failure_tokens_match_the_verifier_vocabulary() {
        val expected = mapOf(
            WssDiag.DeliverFailure.THREW to "threw",
            WssDiag.DeliverFailure.HELD to "held",
            WssDiag.DeliverFailure.UNKNOWN_PROCESSING_FAILURE to "unknown_processing_failure",
        )
        assertEquals(
            "every DeliverFailure member needs a pinned wire token",
            WssDiag.DeliverFailure.entries.toSet(),
            expected.keys,
        )
        for ((member, token) in expected) {
            val line = emitFailure(member, WssDiag.DeliverStage.RECEIVED)
            assertTrue(
                "expected deliver_failure=$token in: $line",
                line.contains(" deliver_failure=$token"),
            )
        }
    }

    @Test
    fun deliver_stage_tokens_match_the_verifier_vocabulary() {
        val expected = mapOf(
            WssDiag.DeliverStage.RECEIVED to "received",
            WssDiag.DeliverStage.DECRYPTED to "decrypted",
            WssDiag.DeliverStage.PERSISTED to "persisted",
            WssDiag.DeliverStage.LEDGER_MARKED to "ledger_marked",
            WssDiag.DeliverStage.ACK_SENT to "ack_sent",
        )
        assertEquals(
            "every DeliverStage member needs a pinned wire token",
            WssDiag.DeliverStage.entries.toSet(),
            expected.keys,
        )
        for ((member, token) in expected) {
            val line = emitFailure(WssDiag.DeliverFailure.THREW, member)
            assertTrue(
                "expected deliver_stage=$token in: $line",
                line.contains(" deliver_stage=$token"),
            )
        }
    }

    // ── the event carries no free text ────────────────────────────

    @Test
    fun the_event_carries_the_correlation_id_and_the_run_context() {
        val line = emitFailure(WssDiag.DeliverFailure.HELD, WssDiag.DeliverStage.RECEIVED)
        assertTrue(line, line.contains("event=recipient_deliver_failed"))
        assertTrue(line, line.contains("role=recipient"))
        assertTrue(line, line.contains("correlation_id=cid-1"))
        // run/cell/emitter are always present in the schema prefix, so a
        // failure is attributable to the run that produced it.
        assertTrue(line, line.contains("run_id="))
        assertTrue(line, line.contains("cell_id="))
        assertTrue(line, line.contains("emitter_id="))
    }

    @Test
    fun the_emitted_line_has_no_field_beyond_the_closed_schema() {
        val line = emitFailure(
            WssDiag.DeliverFailure.UNKNOWN_PROCESSING_FAILURE,
            WssDiag.DeliverStage.ACK_SENT,
        )
        val keys = Regex("(?:^| )([a-z_]+)=")
            .findAll(line)
            .map { it.groupValues[1] }
            .toSet()
        val allowed = setOf(
            "event", "role", "emitter_id", "run_id", "cell_id",
            "wall_utc_ms", "monotonic_ms", "correlation_id",
            "deliver_failure", "deliver_stage",
        )
        assertEquals(
            "recipient_deliver_failed must carry only closed schema fields; line=$line",
            emptySet<String>(),
            keys - allowed,
        )
    }

    @Test
    fun no_other_event_carries_the_deliver_failure_fields() {
        captured.clear()
        WssDiag.emit(
            event = "recipient_deliver_received",
            role = WssDiag.Role.RECIPIENT,
            correlationId = "cid-2",
            dedupGate = WssDiag.DedupGate.FRESH,
        )
        val line = captured.single()
        assertFalse(line, line.contains("deliver_failure="))
        assertFalse(line, line.contains("deliver_stage="))
    }
}
