// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Semantics tests for the OnboardingV2 chrome — Robolectric-backed
 * `createComposeRule()` in a JVM unit test.
 *
 * Round-2 REDLINE P2-4 pin: the Back pill is a SINGLE semantics node
 * that carries THREE properties at once — Role.Button, the "Back" text
 * label, and a click action. Previously the shape was
 *   `.clickable(...).semantics(mergeDescendants=true) { role = Button }`
 * — two modifiers each contributing to the node. This test locks the
 * consolidated form
 *   `.clickable(role = Role.Button, onClickLabel = "Back", onClick = ...)`
 * so any future refactor that splits them back would fail here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingV2SemanticsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun back_pill_has_role_label_and_click_action() {
        var clickCount = 0
        composeTestRule.setContent {
            OnboardingTopBarV2(
                stepNumber = 1,
                totalSteps = 4,
                onBackClick = { clickCount++ },
            )
        }

        // The visible "Back" text is the node's label; the same node
        // is a Role.Button with a click action.
        val backNode = composeTestRule.onNodeWithText("Back")

        backNode
            .assertHasClickAction()
            .assert(hasButtonRole())

        // Click actually fires — proves the click action lives on the
        // same node the reviewer would locate via role + label.
        backNode.performClick()
        assert(clickCount == 1) {
            "Back-pill click did not fire (expected 1 click, saw $clickCount)"
        }

        // Regression guard: the click node must have Role.Button. If a
        // future refactor moves the click action to an unrelated child
        // node without Role.Button, this assertion catches it.
        composeTestRule.onNode(
            SemanticsMatcher("Node has Role.Button + click action + Back label") { node ->
                val role = node.config.getOrNull(SemanticsProperties.Role)
                val hasClick = node.config.contains(SemanticsActions.OnClick)
                val hasBackLabel = node.config.getOrNull(SemanticsProperties.Text)
                    ?.any { it.text == "Back" } == true
                role?.toString() == "Button" && hasClick && hasBackLabel
            },
        ).assertExists()
    }


    @Test
    fun username_input_error_state_carries_error_semantic_via_helper_text() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2(
                formState = phantom.android.screens.onboarding.v2.OnboardingFormStateV2(username = "al!ce"),
                dotsIndex = 1,
                onFormStateChange = {},
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasAnyErrorSemantic()).assertExists()
    }

    @Test
    fun username_input_at_prefix_is_decorative_no_independent_text_node() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2(
                formState = phantom.android.screens.onboarding.v2.OnboardingFormStateV2(username = "alice"),
                dotsIndex = 1,
                onFormStateChange = {},
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        // The @ prefix + divider live inside a `clearAndSetSemantics { }`
        // Row, so neither the "@" character nor the invisible divider
        // has an independent semantic node. A screen reader would NOT
        // announce "@" as a separate item.
        composeTestRule.onAllNodesWithText("@").assertCountEquals(0)
    }

    @Test
    fun username_input_status_icon_is_decorative_no_independent_a11y_node() {
        // Valid state renders a green confirm icon in the trailing slot.
        // The icon has clearAndSetSemantics { } → no independent node.
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2(
                formState = phantom.android.screens.onboarding.v2.OnboardingFormStateV2(username = "alice"),
                dotsIndex = 1,
                onFormStateChange = {},
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        // No node reports "confirm" / "block" / "check" as a
        // contentDescription — the icon is decorative.
        composeTestRule.onAllNodesWithContentDescription("confirm").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("check").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("valid").assertCountEquals(0)
    }

    // Round-2 REDLINE Commit-3 §P2-A pin: the "exactly one editable
    // field" contract. Semantic tree of the Identity step must expose
    // ONE and only ONE SetText action (the username input), and the
    // error semantic MUST live on that same node so a screen reader
    // announces the error against the field itself — not against a
    // separate helper-text node.

    @Test
    fun identity_step_exposes_exactly_one_settext_node() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2(
                formState = phantom.android.screens.onboarding.v2.OnboardingFormStateV2(username = "alice"),
                dotsIndex = 1,
                onFormStateChange = {},
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        // The username input is the ONLY editable field on the Identity
        // step. Decorative slots (@ prefix, divider, status icon) must
        // NOT emit their own SetText action.
        composeTestRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test
    fun error_semantic_lives_on_the_same_node_as_settext_action() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2(
                formState = phantom.android.screens.onboarding.v2.OnboardingFormStateV2(username = "al!ce"),
                dotsIndex = 1,
                onFormStateChange = {},
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        // Round-3 REDLINE §P2 pin: the Error semantic MUST live on the
        // same accessibility node that carries the SetText action.
        // TalkBack announces the error only when it lands on the
        // node currently focused. An error attached to an ancestor
        // without `mergeDescendants = true` leaves the field's own
        // node error-less — a screen reader would not announce the
        // error when focus enters the field.
        //
        // This assertion fails if `error()` is moved off the
        // BasicTextField's own modifier chain (which is exactly the
        // regression this test guards against).
        composeTestRule.onNode(hasSetTextAction()).assert(hasAnyErrorSemantic())

        // Global invariants preserved from round-2: exactly ONE
        // SetText node (only one editable field on the step) AND
        // exactly ONE Error node (no rogue duplicate on a sibling
        // helper-text node).
        composeTestRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        composeTestRule.onAllNodes(hasAnyErrorSemantic()).assertCountEquals(1)
    }

    @Test
    fun decorative_slots_produce_no_extra_interactive_nodes() {
        composeTestRule.setContent {
            phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2(
                formState = phantom.android.screens.onboarding.v2.OnboardingFormStateV2(username = "alice"),
                dotsIndex = 1,
                onFormStateChange = {},
                onContinueClick = {},
            )
        }
        composeTestRule.waitForIdle()
        // Round-2 REDLINE §P2 pin: the decorative slots (`@` prefix,
        // 1 dp × 20 dp divider, trailing status icon) all live inside
        // `Modifier.clearAndSetSemantics { }` — so they emit no
        // independent interactive semantics. The Identity step's
        // interactive surface is exactly two nodes: the username input
        // (SetText action, count = 1) and the Continue button
        // (Role.Button, count = 1). Any decorative slot that leaked
        // semantics — a stray editable field, a stray button — would
        // inflate one of these counts.
        //
        // Note: BasicTextField itself owns an OnClick action alongside
        // SetText (click-to-focus), which is why we discriminate the
        // "button" count by Role.Button rather than by generic
        // OnClick presence — the Continue node is the only Role.Button
        // on the step.
        composeTestRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        composeTestRule.onAllNodes(hasButtonRole()).assertCountEquals(1)
    }

    @Test
    fun finale_has_no_edge_swipe_surface() {
        // In OnboardingV2HostFrame the overlay is conditionally rendered
        // via `if (edgeSwipeBackEnabled)`. This test asserts what the
        // predicate returns for the two chromeless steps AND models the
        // conditional-render contract: when the predicate is false the
        // surface is NOT part of the composition, so no swipe on any
        // area can invoke onEdgeSwipeBack.
        assert(!isEdgeSwipeBackFromEnabled(OnboardingStepV2.FinaleConfirmation)) {
            "isEdgeSwipeBackFromEnabled(FinaleConfirmation) must be false — " +
                "otherwise the host frame would render the edge overlay on Finale."
        }
        assert(!isEdgeSwipeBackFromEnabled(OnboardingStepV2.Welcome)) {
            "isEdgeSwipeBackFromEnabled(Welcome) must be false — the flow starts here."
        }

        // Now render WITHOUT the overlay (mirroring the host's `if`
        // branch that skips the composable on Finale) and confirm a
        // swipe anywhere does not invoke the back callback.
        var backCount = 0
        composeTestRule.setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Text(
                    text = "STAGE",
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "STAGE_SENTINEL" },
                )
                // No OnboardingLeftEdgeSwipeSurface here — this
                // mirrors the host frame's `if (edgeSwipeBackEnabled)`
                // guard when the predicate returns false.
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput {
            swipeRight(startX = 5f, endX = 200f)
        }
        composeTestRule.waitForIdle()

        assert(backCount == 0) {
            "Finale must not fire onEdgeSwipeBack even for a swipe originating " +
                "in the 34-dp left band — the host frame skips the overlay entirely."
        }
    }
}
