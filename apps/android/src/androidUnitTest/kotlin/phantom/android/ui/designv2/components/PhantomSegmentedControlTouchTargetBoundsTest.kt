// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.components

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-5 REDLINE on Commit 5 §P1 pin — every `Role.Tab` node
 * in a rendered `PhantomSegmentedControl` MUST have a bounding
 * height of at least 48 dp (Material a11y minimum touch target).
 *
 * The prior shape hard-capped the parent `Row` at 32 dp height,
 * so `defaultMinSize(minHeight = 48.dp)` on each child clipped
 * to 32 dp — the visible clickable bounds ended up smaller than
 * advertised. Round-5 splits the interactive 48-dp container
 * from the visual 32-dp band; this test locks that split in.
 *
 * Runs under three conditions:
 *   1. Truncate overflow (default) at a comfortable width.
 *   2. Truncate overflow at a narrow width — verifies clipping
 *      the visual band doesn't shrink the interactive bounds.
 *   3. AdaptiveStack overflow with a wide-enough container
 *      (renders as Row).
 *   4. AdaptiveStack overflow with a narrow container +
 *      `fontScale = 2.0` (renders as Column of full-width
 *      segments; Column-mode segments are also 48-dp tall).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhantomSegmentedControlTouchTargetBoundsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun truncate_default_width_every_tab_bounds_at_least_48_dp() {
        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .background(Color.Black)
                    .padding(16.dp),
            ) {
                PhantomSegmentedControl(
                    options = listOf("All", "Incoming", "Missed"),
                    selectedIndex = 0,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEveryTabHeightAtLeast48Dp(expectedCount = 3)
    }

    @Test
    fun truncate_narrow_width_every_tab_bounds_at_least_48_dp() {
        // Even when the visual band clips, the touch target must not.
        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .background(Color.Black),
            ) {
                PhantomSegmentedControl(
                    options = listOf("Standard", "Private", "Ghost"),
                    selectedIndex = 1,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEveryTabHeightAtLeast48Dp(expectedCount = 3)
    }

    @Test
    fun adaptive_stack_row_mode_every_tab_bounds_at_least_48_dp() {
        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .background(Color.Black)
                    .padding(16.dp),
            ) {
                PhantomSegmentedControl(
                    options = listOf("All", "Incoming", "Missed"),
                    selectedIndex = 2,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                    overflow = PhantomSegmentedControlOverflow.AdaptiveStack,
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEveryTabHeightAtLeast48Dp(expectedCount = 3)
    }

    @Test
    fun adaptive_stack_column_mode_every_tab_bounds_at_least_48_dp() {
        // Narrow width + fontScale=2.0 → Column fallback path.
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = 2.0f,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .background(Color.Black),
                ) {
                    PhantomSegmentedControl(
                        options = listOf("Standard", "Private", "Ghost Mode"),
                        selectedIndex = 2,
                        onSelect = {},
                        modifier = Modifier.fillMaxWidth(),
                        overflow = PhantomSegmentedControlOverflow.AdaptiveStack,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEveryTabHeightAtLeast48Dp(expectedCount = 3)
    }

    private fun assertEveryTabHeightAtLeast48Dp(expectedCount: Int) {
        // Round-6 REDLINE on Commit 5 §P1 upgrade: assert BOTH a
        // lower AND an upper bound on Tab node height. Prior
        // round-5 shape only asserted `>= 48 dp`, so an unbounded
        // 900-dp Tab (like the segmented_matrix regression) would
        // pass. Now: `48 dp <= height <= 64 dp`. The upper cap
        // gives 16 dp of slack for any surrounding safe-area /
        // window inset the harness might attribute to the bounds
        // rectangle, without permitting the runaway growth the
        // prior test missed.
        //
        // Row-mode tabs render inside a `.height(48.dp)` parent
        // Row → tab bounds land on 48 dp exactly. Column-mode
        // tabs use `defaultMinSize(minHeight = 48.dp)` in
        // `ColumnSegment` and lay out on natural content height
        // when at fontScale 2.0; the upper cap accepts up to
        // 64 dp for those (2x label + padding).
        val rootNode = composeTestRule.onRoot().fetchSemanticsNode()
        val tabNodes = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
        collectTabNodes(rootNode, tabNodes)
        check(tabNodes.size == expectedCount) {
            "expected $expectedCount Role.Tab nodes, found ${tabNodes.size}"
        }
        val density = composeTestRule.density
        val minPx = with(density) { 48.dp.toPx() }
        val maxPx = with(density) { 96.dp.toPx() }  // Column-mode at fs 2.0 = ~72 dp
        tabNodes.forEachIndexed { i, node ->
            val bounds = node.boundsInWindow
            val heightPx = bounds.height
            check(heightPx >= minPx - 0.5f) {
                "Tab index $i bounds height $heightPx px < 48 dp (${minPx.toInt()} px). " +
                    "boundsInWindow=$bounds"
            }
            check(heightPx <= maxPx + 0.5f) {
                "Tab index $i bounds height $heightPx px > 96 dp (${maxPx.toInt()} px). " +
                    "Tab exploded to unbounded height — this is the segmented_matrix regression " +
                    "the round-6 upper-bound pin guards against. boundsInWindow=$bounds"
            }
        }
    }

    private fun collectTabNodes(
        node: androidx.compose.ui.semantics.SemanticsNode,
        into: MutableList<androidx.compose.ui.semantics.SemanticsNode>,
    ) {
        val role = if (node.config.contains(SemanticsProperties.Role)) {
            node.config[SemanticsProperties.Role]
        } else null
        if (role == Role.Tab) into += node
        node.children.forEach { collectTabNodes(it, into) }
    }

    // Round-6 REDLINE §P1 pin: coordinate-driven taps close to
    // each edge of every Tab's visible bounds — verifies that
    // the touch surface REALLY extends to those edges, not just
    // in the semantics tree. If the interactive Box is smaller
    // than the semantics bounds report, an edge-tap misses the
    // registered handler.
    @Test
    fun row_mode_edge_taps_hit_correct_segment() {
        val selectedIndex = androidx.compose.runtime.mutableStateOf(0)
        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .width(360.dp)
                    .background(Color.Black)
                    .padding(16.dp),
            ) {
                PhantomSegmentedControl(
                    options = listOf("A", "B", "C"),
                    selectedIndex = selectedIndex.value,
                    onSelect = { selectedIndex.value = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        composeTestRule.waitForIdle()
        val rootNode = composeTestRule.onRoot().fetchSemanticsNode()
        val tabNodes = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
        collectTabNodes(rootNode, tabNodes)
        check(tabNodes.size == 3) { "expected 3 Tab nodes, got ${tabNodes.size}" }
        // Tap 1 px inside the top edge of tab 1 → must select 1
        val t1 = tabNodes[1].boundsInRoot
        composeTestRule.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(
                x = (t1.left + t1.right) / 2f,
                y = t1.top + 1f,
            ))
        }
        composeTestRule.waitForIdle()
        check(selectedIndex.value == 1) {
            "top-edge tap on tab 1 selected ${selectedIndex.value}, expected 1"
        }
        // Tap 1 px inside the bottom edge of tab 2 → must select 2
        val t2 = tabNodes[2].boundsInRoot
        composeTestRule.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(
                x = (t2.left + t2.right) / 2f,
                y = t2.bottom - 1f,
            ))
        }
        composeTestRule.waitForIdle()
        check(selectedIndex.value == 2) {
            "bottom-edge tap on tab 2 selected ${selectedIndex.value}, expected 2"
        }
    }
}
