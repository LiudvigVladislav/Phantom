// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
 * PhantomFilterChip — DesignV2 filter chip (standalone toggleable pill).
 *
 * Handoff `design-system-notes.md §Filter chips`:
 *   - Selected  : cyan fill + dark text (OnCyan).
 *   - Unselected: transparent + Border stroke, TextTertiary label.
 *
 * Unlike the primary Button, the chip's visual pill can be as small as ~28dp
 * tall while the touch target stays at 48dp — enforced by a min-height wrapper.
 */
@Composable
fun PhantomFilterChip(
    text: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }

    val bg: Color = if (selected) DesignV2Tokens.Colors.Cyan else Color.Transparent
    val fg: Color = if (selected) DesignV2Tokens.Colors.OnCyan else DesignV2Tokens.Colors.TextTertiary
    val strokeColor: Color? = if (selected) null else DesignV2Tokens.Colors.Border

    val currentSelected = selected

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .background(bg, CircleShape)
            .then(if (strokeColor != null) Modifier.border(1.dp, strokeColor, CircleShape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onSelectedChange(!currentSelected) }
            .semantics {
                role = Role.Tab
                this.selected = currentSelected
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = fg,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                lineHeight = 16.sp,
            ),
        )
    }
}