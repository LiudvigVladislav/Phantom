// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-4 REDLINE on Commit 5 §P2-1 pin — the tests now drive
 * the REAL `PreFlowTermsGate` composable with hoisted
 * `tosAccepted` state. Previously the post-accept transition
 * test reproduced the wrapper's if/else in-test, meaning a
 * regression to the gate's own branch structure could pass
 * unnoticed. Now the test calls `PreFlowTermsGate(topInset,
 * tosAccepted, onAcceptTos, flowContent)` directly and
 * observes real branch flips.
 *
 * The gate is stateless post-round-4 (state hoisted into
 * `OnboardingScreenV2`), so the test owns the state var and
 * flips it via `onAcceptTos`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingScreenV2GateTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun gate_shows_terms_first_before_flow_content() {
        var flowContentRendered = false
        composeTestRule.setContent {
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            PreFlowTermsGate(
                topInset = topInset,
                tosAccepted = false,
                onAcceptTos = {},
                flowContent = {
                    flowContentRendered = true
                    Text(text = "STUB_FLOW_CONTENT")
                },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Terms of Use").assertExists()
        composeTestRule.onNodeWithText("ACCEPT & CONTINUE").assertExists()
        composeTestRule.onAllNodesWithText("STUB_FLOW_CONTENT").assertCountEquals(0)
        assert(!flowContentRendered) {
            "flowContent lambda MUST NOT have executed while tosAccepted=false"
        }
    }

    @Test
    fun main_activity_screen_onboarding_branch_invokes_wrapper_not_flow_directly() {
        // Source-contract test — reads `MainActivity.kt` as text
        // and asserts the `Screen.Onboarding` arm invokes
        // `OnboardingScreenV2` (the Terms-gated wrapper), NOT
        // `OnboardingFlowV2` directly.
        //
        // Grep-based; fragile in principle but honest about its
        // scope. Any refactor that legitimately renames or
        // reroutes the entry point updates BOTH the source and
        // this test.
        val mainActivitySource = java.io.File(
            "src/androidMain/kotlin/phantom/android/MainActivity.kt",
        ).readText()
        val onboardingArmPattern = Regex(
            """is\s+Screen\.Onboarding\s*->\s*(?:phantom\.android\.screens\.onboarding\.v2\.)?(\w+)\s*\(""",
        )
        val match = onboardingArmPattern.find(mainActivitySource)
        assert(match != null) {
            "MainActivity.kt has no `is Screen.Onboarding -> …(` arm — the source-contract " +
                "test needs updating along with the entry-point refactor"
        }
        val invokedComposable = match!!.groupValues[1]
        assertEquals(
            "OnboardingScreenV2",
            invokedComposable,
            "MainActivity Screen.Onboarding arm invokes `$invokedComposable(` — MUST " +
                "invoke `OnboardingScreenV2(container, onComplete)` (the wrapper that " +
                "gates Terms), NOT `OnboardingFlowV2` directly. Terms bypass is a P0.",
        )
    }

    @Test
    fun gate_transitions_to_flow_when_accept_callback_fires() {
        // Round-4 §P2-1 pin: drives the REAL `PreFlowTermsGate`
        // by observing its `onAcceptTos` callback and flipping
        // the hoisted state var when the callback fires. This
        // exercises the gate's branch transition itself — a
        // regression to the gate's if/else branching would fail
        // here.
        //
        // Capture the callback the gate handed the harness by
        // exposing it to the test scope. This is exactly what
        // `TermsScreenV2.onAccept` does in production —
        // TermsScreenV2 stores the callback and fires it on the
        // (scroll-gated) button tap; here the test fires it
        // directly.
        var flowRenderCount = 0
        val holder = androidx.compose.runtime.mutableStateOf(false)
        var capturedOnAccept: (() -> Unit)? = null
        composeTestRule.setContent {
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            capturedOnAccept = { holder.value = true }
            PreFlowTermsGate(
                topInset = topInset,
                tosAccepted = holder.value,
                onAcceptTos = { capturedOnAccept?.invoke() },
                flowContent = {
                    flowRenderCount++
                    Text(text = "STUB_FLOW_CONTENT")
                },
            )
        }
        composeTestRule.waitForIdle()
        assert(flowRenderCount == 0) {
            "flowContent should not render before accept — got $flowRenderCount executions"
        }
        composeTestRule.onNodeWithText("ACCEPT & CONTINUE").assertExists()
        // Fire the accept callback the same way TermsScreenV2 does.
        composeTestRule.runOnIdle {
            capturedOnAccept?.invoke()
        }
        composeTestRule.waitForIdle()
        assert(flowRenderCount >= 1) {
            "flowContent MUST render after accept fires — got $flowRenderCount"
        }
        composeTestRule.onNodeWithText("STUB_FLOW_CONTENT").assertExists()
        composeTestRule.onAllNodesWithText("ACCEPT & CONTINUE").assertCountEquals(0)
    }

    @Test
    fun gate_renders_flow_when_state_starts_accepted() {
        // Complements the transition test: verifies the ACCEPTED
        // branch renders `flowContent` cleanly and does NOT show
        // Terms UI (no double-render).
        var flowRenderCount = 0
        composeTestRule.setContent {
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            PreFlowTermsGate(
                topInset = topInset,
                tosAccepted = true,
                onAcceptTos = {},
                flowContent = {
                    flowRenderCount++
                    Text(text = "STUB_FLOW_CONTENT")
                },
            )
        }
        composeTestRule.waitForIdle()
        assert(flowRenderCount >= 1) {
            "flowContent MUST render when tosAccepted=true"
        }
        composeTestRule.onNodeWithText("STUB_FLOW_CONTENT").assertExists()
        composeTestRule.onAllNodesWithText("Terms of Use").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("ACCEPT & CONTINUE").assertCountEquals(0)
    }
}
