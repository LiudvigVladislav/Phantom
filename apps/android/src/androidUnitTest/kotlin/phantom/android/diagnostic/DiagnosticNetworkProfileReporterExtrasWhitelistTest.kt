// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * WSS-3 §9 focused fixture 74 —
 * `DiagnosticNetworkProfileReporterExtrasWhitelistTest`.
 *
 * Pins the extras whitelist for the `network_profile_report`
 * subcommand — the receiver accepts EXACTLY `subcommand` +
 * `checkpoint_key_hex`. Any other extra (`report_target`, `output`,
 * `path`, etc.) is rejected fail-closed by the existing
 * receiver-wide `ALLOWED_EXTRAS_BY_SUBCOMMAND` map (Round-2 blocker 5).
 *
 * This is a source-set-level contract test — it inspects the constants
 * declared on [DiagnosticCommandReceiver.Companion] instead of driving
 * a broadcast, because the receiver's rejection path returns silently
 * from `onReceive` and does not itself surface a testable signal.
 * The wired dispatch is exercised end-to-end by
 * [DiagnosticNetworkProfileReporterFixedOutputPathTest] (fixture 73)
 * and the shell fixtures under §9.
 */
class DiagnosticNetworkProfileReporterExtrasWhitelistTest {

    @Test
    fun subcommand_registered_in_ALLOWED_SUBCOMMANDS() {
        assertTrue(
            "network_profile_report" in DiagnosticCommandReceiver.ALLOWED_SUBCOMMANDS,
            "SUB_NETWORK_PROFILE_REPORT missing from ALLOWED_SUBCOMMANDS",
        )
    }

    @Test
    fun allowed_extras_are_exactly_checkpoint_key_hex() {
        val allowed = DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND["network_profile_report"]
        assertTrue(allowed != null, "no whitelist declared for network_profile_report")
        assertEquals(
            setOf("checkpoint_key_hex"),
            allowed,
            "network_profile_report must accept exactly {checkpoint_key_hex} beyond `subcommand` " +
                "(Round-2 blocker 5 — no report_target / output / path / any operator-controlled extra)",
        )
    }

    @Test
    fun no_operator_supplied_path_key_is_declared_anywhere() {
        // Cross-check that no forbidden extra name accidentally survives
        // in any subcommand's whitelist under the receiver — a canary
        // against a future contributor loosening a different subcommand
        // and inadvertently accepting a caller-controlled path.
        val forbidden = setOf("report_target", "output", "path", "file", "target", "output_path")
        for ((sub, keys) in DiagnosticCommandReceiver.ALLOWED_EXTRAS_BY_SUBCOMMAND) {
            val bad = keys.intersect(forbidden)
            assertTrue(
                bad.isEmpty(),
                "subcommand `$sub` declares forbidden operator-controlled extras: $bad",
            )
        }
    }
}
