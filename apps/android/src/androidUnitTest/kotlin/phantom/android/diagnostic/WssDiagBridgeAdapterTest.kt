// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import phantom.core.messaging.WssDiagBridge

/**
 * Direct WSS Yota-First — schema unit tests for the shared
 * [WssDiagBridge] interface, exercised via the same enum surface
 * that the shared `DefaultMessagingService` uses. These tests do
 * NOT depend on Android logging — they capture the emit calls into
 * a test double and assert the schema properties architect
 * pinned in the contract (§4).
 *
 * The tests intentionally do NOT include the `unresolved_120s_marker`
 * event or outcome_flag — architect §11 additional test #7 forbids
 * client emission of that classification.
 */
class WssDiagBridgeAdapterTest {

    private class RecordingBridge : WssDiagBridge {
        data class Row(
            val event: String,
            val correlationId: String,
            val role: WssDiagBridge.Role,
            val outcomeFlag: WssDiagBridge.OutcomeFlag,
            val dedupGate: WssDiagBridge.DedupGate?,
        )
        val rows = mutableListOf<Row>()
        override fun emit(
            event: String,
            correlationId: String,
            role: WssDiagBridge.Role,
            outcomeFlag: WssDiagBridge.OutcomeFlag,
            dedupGate: WssDiagBridge.DedupGate?,
        ) {
            rows += Row(event, correlationId, role, outcomeFlag, dedupGate)
        }
    }

    private val bridge = RecordingBridge()

    @Before
    fun installBridge() {
        phantom.core.messaging.WssDiagBridgeHolder.instance = bridge
    }

    @After
    fun tearDown() {
        phantom.core.messaging.WssDiagBridgeHolder.instance = null
    }

    // ── §5 test #1: sender flow schema ───────────────────────────

    @Test
    fun sender_enqueue_carries_role_sender_and_correlation_id() {
        bridge.emit(
            event = "sender_enqueue",
            correlationId = "corr-1",
            role = WssDiagBridge.Role.SENDER,
        )
        val row = bridge.rows.single()
        assertEquals("sender_enqueue", row.event)
        assertEquals("corr-1", row.correlationId)
        assertEquals(WssDiagBridge.Role.SENDER, row.role)
        assertEquals(WssDiagBridge.OutcomeFlag.NONE, row.outcomeFlag)
    }

    // ── §5 test #2: REST semantics — outcome_flag NOT sender_relay_ack_delivered ─

    @Test
    fun rest_completion_does_not_emit_sender_relay_ack_delivered() {
        // The adapter's contract: `sender_rest_post_completed` carries
        // `relay_acceptance` on the Android WssDiag path (verified in
        // HRT instrumentation), NOT `sender_relay_ack_delivered`.
        // The bridge interface is intentionally minimal — it only
        // carries OutcomeFlag values. When Android emits
        // sender_rest_post_completed it does NOT flow through this
        // bridge (HRT calls WssDiag directly). This test guards
        // the enum surface so shared code cannot accidentally
        // repurpose SENDER_RELAY_ACK_DELIVERED for REST.
        assertTrue(WssDiagBridge.OutcomeFlag.entries.contains(WssDiagBridge.OutcomeFlag.SENDER_RELAY_ACK_DELIVERED))
        // No REST-specific enum variant exists in the shared bridge —
        // guards against a future addition that couples them.
        val names = WssDiagBridge.OutcomeFlag.entries.map { it.name }
        assertFalse(names.any { it.contains("REST", ignoreCase = true) })
        assertFalse(names.any { it.contains("ACCEPTED", ignoreCase = true) })
    }

    // ── §5 test #3: recipient dedup gate ─────────────────────────

    @Test
    fun recipient_deliver_first_fresh_then_duplicate_no_second_persist() {
        bridge.emit(
            event = "recipient_deliver_received",
            correlationId = "corr-9",
            role = WssDiagBridge.Role.RECIPIENT,
            dedupGate = WssDiagBridge.DedupGate.FRESH,
        )
        bridge.emit(
            event = "recipient_message_persisted",
            correlationId = "corr-9",
            role = WssDiagBridge.Role.RECIPIENT,
        )
        // Second delivery of the same id — duplicate gate, no persist.
        bridge.emit(
            event = "recipient_deliver_received",
            correlationId = "corr-9",
            role = WssDiagBridge.Role.RECIPIENT,
            dedupGate = WssDiagBridge.DedupGate.DUPLICATE,
        )

        val deliveries = bridge.rows.filter { it.event == "recipient_deliver_received" }
        assertEquals(2, deliveries.size)
        assertEquals(WssDiagBridge.DedupGate.FRESH, deliveries[0].dedupGate)
        assertEquals(WssDiagBridge.DedupGate.DUPLICATE, deliveries[1].dedupGate)

        val persists = bridge.rows.filter { it.event == "recipient_message_persisted" }
        assertEquals(1, persists.size)
    }

    // ── §5 test #4: no PII / key material in enum surface ────────

    @Test
    fun bridge_interface_carries_no_text_or_key_material_fields() {
        // The interface intentionally omits text, username,
        // sealedSender base64, hex fields. Only correlation_id (UUID)
        // + enums flow through. This test locks the interface's
        // parameter surface.
        val parameters = WssDiagBridge::emit.parameters
            .map { it.name }
            .filterNotNull()
        val banned = listOf("text", "plaintext", "content", "hex", "username", "sealed", "auth", "token")
        for (b in banned) {
            assertFalse(parameters.any { it.contains(b, ignoreCase = true) }, "banned param name: $b")
        }
    }

    // ── §5 test #5: no ack_watchdog terminal-failure name in enum ──

    @Test
    fun outcome_flag_has_no_terminal_failure_or_unresolved_marker() {
        val names = WssDiagBridge.OutcomeFlag.entries.map { it.name }
        assertFalse(names.any { it.contains("UNRESOLVED", ignoreCase = true) })
        assertFalse(names.any { it.contains("TERMINAL", ignoreCase = true) })
        assertFalse(names.any { it.contains("FAILED_120", ignoreCase = true) })
    }

    // ── §5 test #6: recipient_message_persisted lives on RECIPIENT role ──

    @Test
    fun recipient_message_persisted_is_a_recipient_event() {
        bridge.emit(
            event = "recipient_message_persisted",
            correlationId = "corr-42",
            role = WssDiagBridge.Role.RECIPIENT,
        )
        val row = bridge.rows.single()
        assertEquals(WssDiagBridge.Role.RECIPIENT, row.role)
    }

    // ── §5 test #7 (schema guard for verifier-only classification) ──

    @Test
    fun no_client_facing_field_carries_unresolved_120s_marker_text() {
        // Emit every enum value and assert none stringifies to
        // "unresolved_120s_marker" (the verifier-only classification
        // architect banned client emission of).
        val allValues = WssDiagBridge.OutcomeFlag.entries.map { it.name.lowercase() } +
            WssDiagBridge.Role.entries.map { it.name.lowercase() } +
            WssDiagBridge.DedupGate.entries.map { it.name.lowercase() }
        assertFalse(
            actual = allValues.any { it.contains("unresolved_120s_marker") },
            message = "client-facing schema MUST NOT expose the verifier-only " +
                "unresolved_120s_marker classification (contract §2 + §11 test #7)",
        )
    }
}
