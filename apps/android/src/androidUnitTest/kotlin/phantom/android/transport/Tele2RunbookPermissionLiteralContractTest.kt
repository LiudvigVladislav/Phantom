// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 9 P2.runbook) — contract
 * test that pins the Tele2 LTE smoke runbook against the activity-
 * based trigger surface and forbids any leftover receiver-era
 * recipe.
 *
 * Pins:
 *
 *   1. The runbook ADB recipe uses `am start` (activity launch),
 *      NOT `am broadcast` (receiver dispatch).
 *   2. The runbook does NOT reference the receiver-era permission
 *      tokens (`android.permission.DUMP`,
 *      `phantom.android.dev.permission.TRIGGER_S6`, or the
 *      `--receiver-permission` flag).
 *   3. The runbook names the activity FQCN
 *      `phantom.android.dev.S6BreakerTriggerActivity` so the
 *      operator can copy-paste without a class-name lookup.
 *
 * Filename retained from round 8 for git history continuity; the
 * class name changed to reflect the round-9 surface.
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
    fun runbook_recipe_uses_am_start_for_activity_launch() {
        val text = runbook.readText(Charsets.UTF_8)
        assertTrue(
            text.contains("am start"),
            "The Tele2 runbook MUST show an `am start` recipe for the S6 controllable " +
                "trigger. Round 9 switched the entry point from BroadcastReceiver to a no-" +
                "display Activity launched via `am start`.",
        )
        assertTrue(
            text.contains("phantom.android.dev.S6BreakerTriggerActivity") ||
                text.contains("phantom.android/.dev.S6BreakerTriggerActivity"),
            "The Tele2 runbook MUST name the activity " +
                "`phantom.android.dev.S6BreakerTriggerActivity` (FQCN or short form) " +
                "so the operator can copy-paste the recipe without a class-name lookup.",
        )
    }

    @Test
    fun runbook_no_longer_contains_am_broadcast_recipe_for_S6() {
        val text = runbook.readText(Charsets.UTF_8)
        // Forbid the broadcast recipe specifically — the runbook
        // legitimately references `breaker_open` and other broadcast
        // metaphors, but the COMMAND form (`am broadcast ... -a
        // phantom.android.dev.S6_BREAKER_TRIGGER`) is the round-3/7
        // shape and must not appear.
        val broadcastRecipeRegex = Regex(
            """am\s+broadcast\b[^\n]*phantom\.android\.dev\.S6_BREAKER_TRIGGER""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            !broadcastRecipeRegex.containsMatchIn(text),
            "The Tele2 runbook MUST NOT contain an `am broadcast ... -a " +
                "phantom.android.dev.S6_BREAKER_TRIGGER` recipe. Round 9 dropped the " +
                "BroadcastReceiver entry point; the only remaining recipe is `am start`.",
        )
    }

    @Test
    fun runbook_does_not_contain_round5_or_round7_permission_tokens() {
        val text = runbook.readText(Charsets.UTF_8)
        val staleTokens = listOf(
            "phantom.android.dev.permission.TRIGGER_S6",
            "--receiver-permission",
            "android.permission.DUMP",
        )
        for (token in staleTokens) {
            assertTrue(
                !text.contains(token),
                "The Tele2 runbook MUST NOT reference `$token` — receiver-era artefact. " +
                    "Round 9 dropped the sender-permission scheme entirely.",
            )
        }
    }
}
