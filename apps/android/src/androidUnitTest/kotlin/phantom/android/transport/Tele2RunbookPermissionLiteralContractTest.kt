// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 8 P2.runbook-drift) —
 * contract test that pins the Tele2 LTE smoke runbook's expected
 * startup log literal against the receiver's actual permission
 * constant.
 *
 * The runbook (`docs/tracks/trek2-stage2b-b-tele2-smoke.md`) shows
 * the expected logcat line emitted at AppContainer init:
 *
 *   `Registered S6BreakerTriggerReceiver for action ... (S6 trigger
 *   flag enabled, permission=<permission-name>)`
 *
 * If the permission constant flips and the runbook does NOT, an
 * operator greping the runbook literal sees "receiver not
 * registered" while the receiver is wired correctly — false
 * negative on the mandatory one-shot smoke verification step.
 *
 * Three pins:
 *
 *   1. The runbook contains `permission=android.permission.DUMP` —
 *      the round-7 wire-up.
 *   2. The runbook does NOT contain any reference to the round-5
 *      custom permission name. (Round 4's hygiene test pinned
 *      this for the receiver + AppContainer source files; this
 *      test extends the pin to the runbook.)
 *   3. The ADB recipe line specifies `--receiver-permission
 *      android.permission.DUMP`.
 */
class Tele2RunbookPermissionLiteralContractTest {

    private val runbook: File by lazy {
        val candidates = listOf(
            File("../docs/tracks/trek2-stage2b-b-tele2-smoke.md"),
            File("../../docs/tracks/trek2-stage2b-b-tele2-smoke.md"),
            File("docs/tracks/trek2-stage2b-b-tele2-smoke.md"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate trek2-stage2b-b-tele2-smoke.md. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    @Test
    fun runbook_expected_startup_log_references_DUMP_permission() {
        val text = runbook.readText(Charsets.UTF_8)
        assertTrue(
            text.contains("permission=android.permission.DUMP"),
            "The Tele2 runbook MUST show `permission=android.permission.DUMP` in the " +
                "expected AppContainer startup log line. An operator greping the literal " +
                "on a debug Tecno needs to see the actual emitted token.",
        )
    }

    @Test
    fun runbook_does_not_contain_round5_custom_permission_name() {
        val text = runbook.readText(Charsets.UTF_8)
        assertTrue(
            !text.contains("phantom.android.dev.permission.TRIGGER_S6"),
            "The Tele2 runbook MUST NOT reference the round-5 custom permission name " +
                "`phantom.android.dev.permission.TRIGGER_S6`. Round 7 switched the " +
                "sender gate to `android.permission.DUMP`; a stale reference here would " +
                "lead the operator to use a `--receiver-permission` flag that the wire-" +
                "up no longer accepts.",
        )
    }

    @Test
    fun runbook_adb_recipe_uses_DUMP_receiver_permission_flag() {
        val text = runbook.readText(Charsets.UTF_8)
        assertTrue(
            text.contains("--receiver-permission android.permission.DUMP"),
            "The Tele2 runbook MUST show the ADB recipe with " +
                "`--receiver-permission android.permission.DUMP`. Any other flag value " +
                "would cause the broadcast to drop at delivery time on the Tecno.",
        )
    }
}
