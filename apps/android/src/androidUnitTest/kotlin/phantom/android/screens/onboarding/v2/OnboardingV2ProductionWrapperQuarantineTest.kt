// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C6-a round-8 REDLINE §P1 pin — REAL production-wrapper tests
 * for [OnboardingScreenV2Host].
 *
 * Round-7 shape drove `PreFlowTermsGate` directly + pinned the
 * wrapper's OR-clause via regex. Architect flagged that as not
 * a full behavioural test.
 *
 * Round-8 extracted [OnboardingScreenV2Host] with a SLOT-based
 * `flowContent` param (no controller / no AppContainer). The
 * production call site
 * (`OnboardingScreenV2(container, onComplete, override)`)
 * passes the real `OnboardingFlowV2` into that slot; tests pass
 * any composable marker. Both call the SAME host, so the
 * Terms-bypass logic under test IS the production path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2ProductionWrapperQuarantineTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun FlowSlotMarker() = Text("FLOW_SLOT_MARKER")

    // ── Quarantine cold-start bypasses Terms ─────────────────────────

    @Test
    fun quarantine_override_bypasses_Terms_renders_flowContent_on_first_frame() {
        var flowSlotRendered = false
        composeTestRule.setContent {
            OnboardingScreenV2Host(
                explicitInitialFinalizeState =
                    OnboardingFinalizeState.MissingKeyRepairRequired,
                flowContent = {
                    flowSlotRendered = true
                    FlowSlotMarker()
                },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("FLOW_SLOT_MARKER").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Terms of Use").assertCountEquals(0)
        assert(flowSlotRendered) {
            "flowContent slot MUST render on first frame under quarantine — the " +
                "wrapper's `tosAccepted = tosAccepted || quarantined` clause bypasses " +
                "PreFlowTermsGate when explicitInitialFinalizeState is " +
                "MissingKeyRepairRequired."
        }
    }

    // ── Fresh install shows Terms first ──────────────────────────────

    @Test
    fun fresh_install_null_override_shows_Terms_first() {
        var flowSlotRendered = false
        composeTestRule.setContent {
            OnboardingScreenV2Host(
                explicitInitialFinalizeState = null,
                flowContent = {
                    flowSlotRendered = true
                    FlowSlotMarker()
                },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Terms of Use").assertIsDisplayed()
        composeTestRule.onNodeWithText("ACCEPT & CONTINUE").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("FLOW_SLOT_MARKER").assertCountEquals(0)
        assert(!flowSlotRendered) {
            "flowContent slot MUST NOT render on first frame when the override is " +
                "null — the wrapper shows Terms first (normal cold-start)."
        }
    }

    // ── Real MainActivity source-contract: Screen.StartupError is a distinct branch

    @Test
    fun source_contract_MainActivity_renders_Screen_StartupError_via_OnboardingStartupErrorScreen() {
        // Round-8 §P0 pin: MainActivity MUST route Screen.StartupError
        // to OnboardingStartupErrorScreen (NOT OnboardingScreenV2).
        // Otherwise the transient failure path would re-enter the
        // wrapper + Terms + finalize holder + repair screen — the
        // exact leak round-8 closes.
        val source = java.io.File(
            "src/androidMain/kotlin/phantom/android/MainActivity.kt",
        ).readText()
        val startupErrorBranch = Regex(
            """is\s+Screen\.StartupError\s*->\s*\{[\s\S]*?OnboardingStartupErrorScreen""",
        )
        assert(startupErrorBranch.containsMatchIn(source)) {
            "MainActivity.kt must render Screen.StartupError via OnboardingStartupErrorScreen. " +
                "Round-8 §P0 pin: transient failure has its OWN screen; NEVER re-enter " +
                "the Terms/quarantine wrapper."
        }
        // Also assert MainActivity's Retry callback is single-flight
        // (guards on `startupInFlight`).
        val retryGuard = Regex(
            """if\s*\(!startupInFlight\)\s*\{\s*startupInFlight\s*=\s*true\s*retryTick\s*\+=\s*1""",
        )
        assert(retryGuard.containsMatchIn(source)) {
            "MainActivity.kt Retry callback MUST guard on !startupInFlight AND set " +
                "startupInFlight = true SYNCHRONOUSLY BEFORE incrementing retryTick. " +
                "Mini-round §P2 pin: without the synchronous flip, a same-frame double " +
                "tap could pass the guard twice before LaunchedEffect flipped the flag."
        }
        // Retry button must be wired with `enabled = !startupInFlight`.
        val enabledWiring = Regex(
            """enabled\s*=\s*!startupInFlight""",
        )
        assert(enabledWiring.containsMatchIn(source)) {
            "MainActivity.kt OnboardingStartupErrorScreen call MUST pass " +
                "`enabled = !startupInFlight`. Mini-round §P2 pin: the button visually " +
                "disables during an in-flight startup run so the user sees the " +
                "single-flight state, not just a silent no-op tap."
        }
    }

    @Test
    fun source_contract_OnboardingScreenV2Host_uses_slot_not_controller_param() {
        // Round-8 §P1 pin: the host is slot-based
        // (flowContent: @Composable () -> Unit), not
        // controller-based. Tests can render the SAME host with
        // any slot content, proving the Terms-bypass logic
        // without a controller / container. If a refactor
        // reintroduces the controller param, this test fails-red.
        val source = java.io.File(
            "src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingScreenV2.kt",
        ).readText()
        val hostSignature = Regex(
            """internal\s+fun\s+OnboardingScreenV2Host\s*\([\s\S]*?flowContent\s*:\s*@Composable\s*\(\s*\)\s*->\s*Unit""",
        )
        assert(hostSignature.containsMatchIn(source)) {
            "OnboardingScreenV2Host MUST take a `flowContent: @Composable () -> Unit` " +
                "slot (round-8 §P1 pin — architect scope-lock). No controller / " +
                "AppContainer param."
        }
    }
}
