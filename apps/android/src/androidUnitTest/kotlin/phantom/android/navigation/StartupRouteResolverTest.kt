// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

import kotlin.test.assertEquals
import org.junit.Test
import phantom.android.screens.onboarding.v2.RepairReason
import phantom.android.screens.onboarding.v2.StartupRouteDecision
import phantom.android.screens.onboarding.v2.TransientReason

/**
 * Final Stabilization Mini-Block 2026-08-11 — resolver contract tests.
 *
 * Pins the seven precedence rules the architect specified. Regression
 * protection: any drift in the mapping (e.g. a healthy `ChatList`
 * decision clobbering a restored `Profile`, or a security-critical
 * decision silently deferring to user navigation) fails at least one
 * test red.
 *
 * The resolver is a pure function — no Robolectric, no Compose, no
 * Android SDK; these are plain JVM tests.
 */
class StartupRouteResolverTest {

    // ── §1 — Healthy ChatList decision + no restored screen ──

    @Test
    fun healthy_startup_with_no_restored_screen_routes_to_ChatList() {
        val resolved = resolveScreenAfterStartup(
            decision = StartupRouteDecision.ChatList,
            currentOrRestoredScreen = null,
        )
        assertEquals(Screen.ChatList, resolved)
    }

    // ── §2 — Healthy ChatList decision + restored Profile ────

    @Test
    fun healthy_startup_preserves_restored_Profile() {
        val resolved = resolveScreenAfterStartup(
            decision = StartupRouteDecision.ChatList,
            currentOrRestoredScreen = Screen.Profile,
        )
        assertEquals(
            expected = Screen.Profile,
            actual = resolved,
            message = "Healthy ChatList decision MUST NOT clobber a restored " +
                "Profile route — this is the load-bearing defect fix.",
        )
    }

    // ── §3 — Healthy ChatList decision + user-selected non-Profile route ──

    @Test
    fun healthy_startup_preserves_user_selected_route_while_startup_in_flight() {
        // The user navigated into a chat before the startup coroutine
        // completed (or resumed a chat via the sealed Screen.Chat data
        // class). Startup finishing healthy MUST NOT reset them.
        val chat = Screen.Chat("conv-42", "alice")
        val resolved = resolveScreenAfterStartup(
            decision = StartupRouteDecision.ChatList,
            currentOrRestoredScreen = chat,
        )
        assertEquals(chat, resolved)
    }

    // ── §4 — Repair quarantine overrides restored route ─────

    @Test
    fun repair_quarantine_overrides_restored_Profile() {
        val resolved = resolveScreenAfterStartup(
            decision = StartupRouteDecision.RepairQuarantine(
                RepairReason.MarkerAlreadySet,
            ),
            currentOrRestoredScreen = Screen.Profile,
        )
        assertEquals(
            expected = Screen.Onboarding,
            actual = resolved,
            message = "Repair quarantine is proven identity corruption — " +
                "MUST force the repair/onboarding route regardless of restored screen.",
        )
    }

    // ── §5 — Transient failure overrides restored route ─────

    @Test
    fun transient_failure_overrides_restored_Profile() {
        val resolved = resolveScreenAfterStartup(
            decision = StartupRouteDecision.TransientStartupFailure(
                reason = TransientReason.LoadIdentityThrew,
            ),
            currentOrRestoredScreen = Screen.Profile,
        )
        assertEquals(
            expected = Screen.StartupError("LoadIdentityThrew"),
            actual = resolved,
            message = "Transient startup failure MUST force the retry surface " +
                "regardless of restored screen.",
        )
    }

    // ── §6 — Fresh onboarding overrides restored route ──────

    @Test
    fun fresh_onboarding_overrides_restored_Profile() {
        val resolved = resolveScreenAfterStartup(
            decision = StartupRouteDecision.FreshOnboarding,
            currentOrRestoredScreen = Screen.Profile,
        )
        assertEquals(
            expected = Screen.Onboarding,
            actual = resolved,
            message = "Fresh install (no identity on disk) MUST force onboarding " +
                "even if a stale restored Profile was in saved state (defence in " +
                "depth — this shape should not occur in practice, but a stale " +
                "saved-state slot MUST NOT bypass onboarding).",
        )
    }

    // ── §7 — Migration overrides restored route ─────────────

    @Test
    fun migration_overrides_restored_Profile() {
        val resolved = resolveScreenAfterStartup(
            decision = StartupRouteDecision.Migration,
            currentOrRestoredScreen = Screen.Profile,
        )
        assertEquals(
            expected = Screen.Migration,
            actual = resolved,
            message = "Migration required (Alpha 1 identity → Alpha 2) MUST " +
                "force the Migration screen regardless of restored route — the " +
                "upgrade blocks all normal navigation until it completes.",
        )
    }

    // ── Bonus — repair quarantine still clobbers even null (matches production) ──

    @Test
    fun repair_quarantine_with_null_restored_screen_routes_to_Onboarding() {
        val resolved = resolveScreenAfterStartup(
            decision = StartupRouteDecision.RepairQuarantine(
                RepairReason.MalformedSigningKeyHex,
            ),
            currentOrRestoredScreen = null,
        )
        assertEquals(Screen.Onboarding, resolved)
    }

    @Test
    fun transient_failure_carries_reason_name_verbatim() {
        // The Screen.StartupError branch reads decision.reason.name so
        // the UI surface can display stable copy without leaking a
        // Throwable message. Pin the mapping so a rename of any
        // TransientReason enum entry surfaces in this test rather than
        // silently changing the on-screen text.
        for (reason in TransientReason.values()) {
            val resolved = resolveScreenAfterStartup(
                decision = StartupRouteDecision.TransientStartupFailure(reason),
                currentOrRestoredScreen = Screen.Profile,
            )
            assertEquals(
                expected = Screen.StartupError(reason.name),
                actual = resolved,
                message = "Transient reason $reason must carry its enum name " +
                    "verbatim into the Screen.StartupError payload.",
            )
        }
    }
}
