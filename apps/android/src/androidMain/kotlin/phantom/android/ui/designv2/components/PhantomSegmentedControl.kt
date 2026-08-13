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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * PhantomSegmentedControl — DesignV2 segmented tab strip.
 *
 * Handoff `design-system-notes.md §Segmented controls`:
 *   - 32pt visual height.
 *   - Selected segment: SurfaceHover bg + Border stroke.
 *   - Unselected segment: transparent.
 *
 * Accessibility: each segment carries `Role.Tab` + a `selected` semantic so
 * TalkBack announces "selected"/"not selected". The whole strip is 32dp tall
 * visually, but each individual segment gets a minimum 48dp touch height
 * via an inner defaultMinSize on the clickable Box — visual centering keeps
 * the 32dp band intact.
 */
@Composable
fun PhantomSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(options.isNotEmpty()) { "PhantomSegmentedControl needs at least one option" }
    require(selectedIndex in options.indices) {
        "selectedIndex $selectedIndex out of range 0..${options.lastIndex}"
    }
    val outerShape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .height(32.dp)
            .clip(outerShape)
            .background(DesignV2Tokens.Colors.Surface, outerShape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val interaction = remember(index) { MutableInteractionSource() }
            val segmentShape = RoundedCornerShape(8.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(segmentShape)
                    .then(
                        if (isSelected) {
                            Modifier
                                .background(DesignV2Tokens.Colors.SurfaceHover, segmentShape)
                                .border(1.dp, DesignV2Tokens.Colors.Border, segmentShape)
                        } else Modifier.background(Color.Transparent, segmentShape)
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { onSelect(index) }
                    .semantics {
                        role = Role.Tab
                        selected = isSelected
                    },
                contentAlignment = Alignment.Center,
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
                )
            }
        }
    }
}