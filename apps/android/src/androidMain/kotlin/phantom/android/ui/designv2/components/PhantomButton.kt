// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * PhantomButton — DesignV2 button.
 *
 * Handoff `design-system-notes.md §Buttons` (all pill, radius = full):
 *   - Primary  : cyan fill, OnCyan text, focus = 2px cyan-30% ring,
 *                active = CyanDeepActive, disabled = CyanDeepDisabled + muted text.
 *   - Secondary: SurfaceElevated bg, Border 1px, TextPrimary; focus = cyan
 *                border + 8% cyan ring.
 *   - Ghost    : transparent, TextTertiary; hover reveals border stroke.
 *
 * Sizing: default pill height 48dp (≥ material's 48dp min touch target).
 * Even Ghost variant meets the touch target — the visual chip is smaller
 * than the tappable area only via internal padding, not via a shrunken box.
 */
enum class PhantomButtonVariant { Primary, Secondary, Ghost }

@Composable
fun PhantomButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PhantomButtonVariant = PhantomButtonVariant.Primary,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val bg: Color
    val fg: Color
    val borderColor: Color?
    val borderWidth = 1.dp

    when (variant) {
        PhantomButtonVariant.Primary -> {
            bg = if (!enabled) DesignV2Tokens.Colors.CyanDeepDisabled else DesignV2Tokens.Colors.Cyan
            fg = if (!enabled) DesignV2Tokens.Colors.TextTertiary else DesignV2Tokens.Colors.OnCyan
            borderColor = if (focused) DesignV2Tokens.Colors.Cyan else null
        }

        PhantomButtonVariant.Secondary -> {
            bg = DesignV2Tokens.Colors.SurfaceElevated
            fg = if (!enabled) DesignV2Tokens.Colors.TextTertiary else DesignV2Tokens.Colors.TextPrimary
            borderColor = if (focused) DesignV2Tokens.Colors.Cyan else DesignV2Tokens.Colors.Border
        }

        PhantomButtonVariant.Ghost -> {
            bg = Color.Transparent
            fg = if (!enabled) DesignV2Tokens.Colors.TextQuaternary else DesignV2Tokens.Colors.TextTertiary
            borderColor = if (focused) DesignV2Tokens.Colors.Border else null
        }
    }

    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .background(bg, CircleShape)
            .then(if (borderColor != null) Modifier.border(borderWidth, borderColor, CircleShape) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button }
            .padding(PaddingValues(horizontal = 20.dp, vertical = 12.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = fg,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
            ),
        )
    }
}