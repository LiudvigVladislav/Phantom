// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * PhantomSegmentedControl — DesignV2 segmented tab strip with
 * an accessibility-aware overflow policy.
 *
 * Handoff `design-system-notes.md §Segmented controls`:
 *   - 32pt visual band (Row mode).
 *   - Selected segment: SurfaceHover bg + Border stroke.
 *   - Unselected segment: transparent.
 *
 * Round-4 REDLINE on Commit 5 §P1-4: at `fontScale = 2.0` inside
 * a 220-dp container, three "Standard / Private / Ghost Mode"
 * labels can't fit horizontally without truncating to `Sta… /
 * Pri… / Gh…`. The names are load-bearing (privacy modes) —
 * hiding them from a large-print user is an accessibility fail.
 * This component now supports two overflow policies:
 *
 *   - [PhantomSegmentedControlOverflow.Truncate] (default) —
 *     fixed Row; individual segments ellipsise their label when
 *     width is short.
 *   - [PhantomSegmentedControlOverflow.AdaptiveStack] — measures
 *     the natural Row width via [SubcomposeLayout]; if the Row
 *     fits within the container's `maxWidth`, renders as Row
 *     exactly like [Truncate]. Otherwise renders as a Column of
 *     full-width stacked segments so every label is fully
 *     visible.
 *
 * Callers that render inside potentially-narrow containers at
 * large font scales pass [AdaptiveStack]. Everyone else keeps
 * the default. Note: production `PrivacyLevelStepV2` uses a
 * separate `PrivacySegmentBar` composable (a bar-of-pills, not
 * a tab strip) — no caller of [PhantomSegmentedControl] currently
 * ships in production onboarding. The stress showcase is the
 * only [AdaptiveStack] consumer today.
 *
 * Round-5 REDLINE on Commit 5 §P1 pin: touch target really is
 * 48 dp per Material a11y guideline — the interactive
 * [RowSegment] `Box` is 48-dp tall and clickable across its full
 * bounds; the visual 32-dp band is a smaller inner `Box`,
 * vertically centred inside it. Prior shape put a 32-dp height
 * constraint on the parent `Row`, which capped children's
 * `defaultMinSize(minHeight = 48.dp)` to 32 dp and made the tap
 * target smaller than advertised.
 * `PhantomSegmentedControlTouchTargetBoundsTest` guards this
 * invariant by asserting every `Role.Tab` node's
 * `boundsInWindow.height >= 48 dp`.
 */
@Composable
fun PhantomSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    overflow: PhantomSegmentedControlOverflow = PhantomSegmentedControlOverflow.Truncate,
) {
    require(options.isNotEmpty()) { "PhantomSegmentedControl needs at least one option" }
    require(selectedIndex in options.indices) {
        "selectedIndex $selectedIndex out of range 0..${options.lastIndex}"
    }
    val outerShape = RoundedCornerShape(10.dp)

    when (overflow) {
        PhantomSegmentedControlOverflow.Truncate -> RowLayout(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            modifier = modifier,
            outerShape = outerShape,
        )
        PhantomSegmentedControlOverflow.AdaptiveStack -> AdaptiveLayout(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            modifier = modifier,
            outerShape = outerShape,
        )
    }
}

/**
 * Overflow behaviour for [PhantomSegmentedControl].
 *
 * @property Truncate Fixed horizontal Row; individual segments
 *   ellipsise their label when width is short. Legacy behaviour
 *   preserved as the default for backward-compat with the
 *   `segmented_matrix` golden.
 * @property AdaptiveStack Falls back to a vertical Column of
 *   full-width segments when a horizontal Row would overflow.
 *   Ensures every label is visible in full at high font scales
 *   or narrow containers. Recommended for any strip inside a
 *   narrow column or on a screen that must remain readable at
 *   `fontScale = 2.0`.
 */
enum class PhantomSegmentedControlOverflow {
    Truncate,
    AdaptiveStack,
}

@Composable
private fun RowLayout(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    outerShape: RoundedCornerShape,
) {
    // Round-6 REDLINE on Commit 5 §P1: pin the interactive Row
    // height at EXACTLY 48 dp (not `defaultMinSize`). Prior
    // shape allowed the Row to fill any surrounding container
    // that hadn't itself constrained height — the
    // `segmented_matrix` golden ended up with a 900-dp-tall Row
    // when the surrounding matrix column gave it unbounded
    // height. `RowSegment`'s `.fillMaxHeight()` inherited the
    // huge height and made every tap-target enormous.
    //
    // With `.height(48.dp)` the interactive band is deterministic
    // and every child bounded to it — bounds test now asserts
    // BOTH a lower AND upper bound (48 dp ± padding).
    // NOTE: no inner padding on the Row — interactive segments
    // must claim the full 48-dp vertical band. The 2-dp gap
    // between the outer chrome and the visual pill is drawn by
    // the nested visual Box inside each RowSegment (its
    // `.padding(vertical = 8.dp)` centres the 32-dp visual band
    // inside its 48-dp interactive parent).
    Row(
        modifier = modifier
            .height(48.dp) // interactive touch band — MUST be exact, not defaultMinSize
            .clip(outerShape)
            .background(DesignV2Tokens.Colors.Surface, outerShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            RowSegment(
                label = label,
                isSelected = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowSegment(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val segmentShape = RoundedCornerShape(8.dp)
    // Round-6 REDLINE on Commit 5 §P1: interactive layer is
    // EXACTLY 48 dp tall (fills the parent Row's exact 48-dp
    // height); visual band is a separate 32-dp Box centred
    // inside it via 8-dp vertical padding on the interactive
    // container. `fillMaxHeight()` here is safe now that the
    // Row is pinned to `.height(48.dp)`; no unbounded-height
    // inheritance.
    //
    // Layer split:
    //   1. Outer Box (interactive): 48-dp exact, owns .clickable
    //      + Role.Tab. Transparent chrome. Its `boundsInWindow`
    //      is what the bounds test measures.
    //   2. Inner Box (visual): 32-dp exact, owns the selected
    //      background + border. Centred vertically by the 8-dp
    //      vertical padding on the interactive container.
    Box(
        modifier = modifier
            .fillMaxHeight() // = 48 dp exact (Row is pinned to 48)
            .defaultMinSize(minWidth = 60.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                role = Role.Tab
                selected = isSelected
            }
            .padding(vertical = 8.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(segmentShape)
                .then(
                    if (isSelected) {
                        Modifier
                            .background(DesignV2Tokens.Colors.SurfaceHover, segmentShape)
                            .border(1.dp, DesignV2Tokens.Colors.Border, segmentShape)
                    } else Modifier.background(Color.Transparent, segmentShape)
                )
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            SegmentLabel(label = label, isSelected = isSelected, singleLine = true)
        }
    }
}

@Composable
private fun ColumnSegment(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val segmentShape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(segmentShape)
            .then(
                if (isSelected) {
                    Modifier
                        .background(DesignV2Tokens.Colors.SurfaceHover, segmentShape)
                        .border(1.dp, DesignV2Tokens.Colors.Border, segmentShape)
                } else Modifier.background(Color.Transparent, segmentShape)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                role = Role.Tab
                selected = isSelected
            }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        SegmentLabel(label = label, isSelected = isSelected, singleLine = false)
    }
}

@Composable
private fun SegmentLabel(
    label: String,
    isSelected: Boolean,
    singleLine: Boolean,
) {
    Text(
        text = label,
        color = if (isSelected) {
            DesignV2Tokens.Colors.TextPrimary
        } else DesignV2Tokens.Colors.TextTertiary,
        style = TextStyle(
            fontFamily = DesignV2FontBody,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            lineHeight = 16.sp,
        ),
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        softWrap = !singleLine,
        overflow = if (singleLine) {
            androidx.compose.ui.text.style.TextOverflow.Ellipsis
        } else {
            androidx.compose.ui.text.style.TextOverflow.Clip
        },
    )
}

/**
 * Round-4 REDLINE §P1-4 pin: measure the natural horizontal
 * strip width first; if it fits the container, lay out as Row
 * (identical byte-for-byte to [RowLayout]). If not, subcompose a
 * vertical Column of full-width segments so every label reads in
 * full — no truncation, no character-by-character shred.
 *
 * `SubcomposeLayout` is the correct primitive here because the
 * decision "does the natural Row fit" requires measuring the Row's
 * intrinsic size against `constraints.maxWidth` at layout time,
 * which no static Composable arrangement can express.
 */
@Composable
private fun AdaptiveLayout(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    outerShape: RoundedCornerShape,
) {
    val outerBg = DesignV2Tokens.Colors.Surface
    val stackedBg = DesignV2Tokens.Colors.Surface
    SubcomposeLayout(modifier = modifier) { constraints ->
        // Subcompose a "probe" of the Row with UNBOUNDED width so
        // we can read its natural minimum width. If that fits the
        // available maxWidth → lay out the real Row. Otherwise
        // subcompose the Column and lay out that.
        val probe = subcompose("probe") {
            // Round-5 REDLINE §P1 pin: probe MUST NOT emit
            // `Role.Tab` semantics — the a11y bounds-test suite
            // enumerates every Tab node in the tree and would
            // double-count if the probe emitted its own tabs.
            // Measure natural width using a plain Row of
            // label-holding Boxes instead of RowSegment.
            Row(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { label ->
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 60.dp)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SegmentLabel(label = label, isSelected = false, singleLine = true)
                    }
                }
            }
        }
        val probePlaceable = probe.first().measure(Constraints())
        val naturalRowWidth = probePlaceable.width
        // Unbounded parent (horizontal scroller, LazyRow): always
        // render Row — the Column fallback only makes sense when a
        // concrete maxWidth is known.
        val fitsAsRow = !constraints.hasBoundedWidth ||
            naturalRowWidth <= constraints.maxWidth
        if (fitsAsRow) {
            val row = subcompose("row") {
                RowLayout(
                    options = options,
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxWidth(),
                    outerShape = outerShape,
                )
            }
            val rowPlaceable = row.first().measure(constraints)
            layout(rowPlaceable.width, rowPlaceable.height) {
                rowPlaceable.placeRelative(0, 0)
            }
        } else {
            val col = subcompose("column") {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(outerShape)
                        .background(stackedBg, outerShape)
                        .padding(4.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
                ) {
                    options.forEachIndexed { index, label ->
                        ColumnSegment(
                            label = label,
                            isSelected = index == selectedIndex,
                            onClick = { onSelect(index) },
                            modifier = Modifier,
                        )
                    }
                }
            }
            val colPlaceable = col.first().measure(constraints)
            layout(colPlaceable.width, colPlaceable.height) {
                colPlaceable.placeRelative(0, 0)
            }
        }
    }
}
