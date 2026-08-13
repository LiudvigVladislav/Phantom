// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Direct WSS Yota-First — §11 additional focused tests for the
 * debug-only [DiagnosticCommandReceiver].
 *
 * These are pure JVM tests targeting the whitelist/enum surface of
 * the receiver's companion object — they do NOT instantiate the
 * receiver itself, do NOT touch Android system services, and do
 * NOT talk to `MessagingService`. That keeps them fast and
 * independent of the cumulative Robolectric flake (see
 * `project_android_test_infra_appnotidleexception_cumulative_2026_08_11`).
 */
class DiagnosticCommandReceiverContractTest {

    // ── §11 lock 4 test #1: unknown extras / subcommands rejected ──

    @Test
    fun allowed_subcommands_enum_is_strict() {
        val expected = setOf(
            "pin", "send", "canary", "set_emitter_id",
            "dual_sim_report", "health", "clear",
            "checkpoint", "paired_count_report",
            "signed_prekey_readiness",
        )
        assertEquals(expected, DiagnosticCommandReceiver.ALLOWED_SUBCOMMANDS)
    }

    @Test
    fun allowed_pins_enum_is_strict() {
        val expected = setOf("none", "wss", "rest")
        assertEquals(expected, DiagnosticCommandReceiver.ALLOWED_PINS)
    }

    @Test
    fun pin_subcommand_whitelist_rejects_send_specific_extras() {
        val pinAllowed = DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND["pin"] ?: emptySet()
        // Must accept exactly the pin-flow extras.
        assertEquals(setOf("pin", "run_id", "cell_id"), pinAllowed)
        // Must NOT accept text, contact_alias, or arbitrary payload.
        for (banned in listOf("text", "plaintext", "content", "contact_alias", "recipient", "public_key")) {
            assertFalse(pinAllowed.contains(banned), "pin whitelist leaked banned key: $banned")
        }
    }

    @Test
    fun send_subcommand_whitelist_rejects_arbitrary_text_and_contact() {
        val sendAllowed = DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND["send"] ?: emptySet()
        // §11 lock 3: contact_alias is NOT in the whitelist.
        // §11 lock 4 test #2: text must not be accepted.
        assertEquals(setOf("run_id", "cell_id", "sequence"), sendAllowed)
        for (banned in listOf("text", "plaintext", "content", "contact_alias", "recipient", "public_key", "hex")) {
            assertFalse(sendAllowed.contains(banned), "send whitelist leaked banned key: $banned")
        }
    }

    @Test
    fun canary_subcommand_accepts_no_extras() {
        assertTrue(DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND["canary"]!!.isEmpty())
    }

    @Test
    fun set_emitter_id_only_accepts_emitter_id_key() {
        val allowed = DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND["set_emitter_id"] ?: emptySet()
        assertEquals(setOf("emitter_id"), allowed)
    }

    @Test
    fun health_dual_sim_and_clear_accept_no_extras() {
        assertTrue(DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND["health"]!!.isEmpty())
        assertTrue(DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND["dual_sim_report"]!!.isEmpty())
        assertTrue(DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND["clear"]!!.isEmpty())
    }

    // §12 P0-1 — schema stays closed after the repair block; the
    // rest_capability_probe subcommand from the previous round is
    // gone (its evidence comes from a controlled fail-closed matrix
    // envelope during preflight, per §9.6 Method B).
    @Test
    fun rest_capability_probe_subcommand_is_no_longer_defined() {
        assertFalse("rest_capability_probe" in DiagnosticCommandReceiver.ALLOWED_SUBCOMMANDS)
        assertFalse("rest_capability_probe" in DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND)
    }

    // §12 Round-2 audit P0-1: receiver's dispatch is gated on the
    // extras-whitelist map lookup — a subcommand missing from the
    // map is silently rejected. Every ALLOWED_SUBCOMMAND MUST have
    // a corresponding entry (potentially empty) in
    // ALLOWED_EXTRAS_BY_SUBCOMMAND. A regression that adds a new
    // subcommand and forgets the map entry fails-red here.
    @Test
    fun every_allowed_subcommand_has_extras_whitelist_entry() {
        val missing = DiagnosticCommandReceiver.ALLOWED_SUBCOMMANDS.filter { sub ->
            sub !in DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND
        }
        assertTrue(
            missing.isEmpty(),
            "subcommands without ALLOWED_EXTRAS_BY_SUBCOMMAND entry (receiver would silently reject them): $missing",
        )
    }

    @Test
    fun no_stale_ALLOWED_EXTRAS_entries_for_removed_subcommands() {
        val stale = DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND.keys.filter { key ->
            key !in DiagnosticCommandReceiver.ALLOWED_SUBCOMMANDS
        }
        assertTrue(
            stale.isEmpty(),
            "ALLOWED_EXTRAS_BY_SUBCOMMAND contains entries for subcommands not in the enum: $stale",
        )
    }
}
