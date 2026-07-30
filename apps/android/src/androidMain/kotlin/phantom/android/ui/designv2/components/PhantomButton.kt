// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
 *   - Primary  : cyan fill, OnCyan text.
 *                focus  = 2px cyan-30% ring.
 *                active = CyanDeepActive.
 *                disabled = CyanDeepDisabled + muted text.
 *   - Secondary: SurfaceElevated bg, Border 1px, TextPrimary.
 *                focus  = cyan border + 8% cyan ring.
 *                active = SurfaceHover fill.  (handoff-implied press feedback.)
 *   - Ghost    : transparent, TextTertiary.
 *                focus/hover = Border stroke reveal.
 *                active      = SurfaceHover fill.
 *
 * State priority for visuals: **disabled > pressed > focused > normal.**
 * pressed always overrides focused when both are true (touch input has both).
 *
 * [interactionSource] is exposed as a parameter so tests (Paparazzi matrix
 * goldens in F2b) can pre-emit `PressInteraction.Press` / `FocusInteraction.Focus`
 * to render the pressed / focused variants deterministically.
 *
 * Sizing: default pill height ≥48dp — visual chip padding shrinks it inside
 * a 48dp touch box, not by shrinking the box itself.
 */
enum class PhantomButtonVariant { Primary, Secondary, Ghost }

private enum class ButtonVisualState { Normal, Focused, Pressed, Disabled }

@Composable
fun PhantomButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PhantomButtonVariant = PhantomButtonVariant.Primary,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val state = when {
        !enabled -> ButtonVisualState.Disabled
        pressed  -> ButtonVisualState.Pressed
        focused  -> ButtonVisualState.Focused
        else     -> ButtonVisualState.Normal
    }

    val visuals = buttonVisualsFor(variant, state)

    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .background(visuals.background, CircleShape)
            .then(
                if (visuals.border != null) {
                    Modifier.border(1.dp, visuals.border, CircleShape)
                } else Modifier
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
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
            color = visuals.foreground,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
            ),
        )
    }
}

private data class ButtonVisuals(
    val background: Color,
    val foreground: Color,
    val border: Color?,
)

private fun buttonVisualsFor(
    variant: PhantomButtonVariant,
    state: ButtonVisualState,
): ButtonVisuals {
    val c = DesignV2Tokens.Colors
    return when (variant) {
        PhantomButtonVariant.Primary -> when (state) {
            ButtonVisualState.Disabled -> ButtonVisuals(c.CyanDeepDisabled, c.TextTertiary, null)
            ButtonVisualState.Pressed  -> ButtonVisuals(c.CyanDeepActive,   c.OnCyan,        null)
            ButtonVisualState.Focused  -> ButtonVisuals(c.Cyan,             c.OnCyan,        c.Cyan)
            ButtonVisualState.Normal   -> ButtonVisuals(c.Cyan,             c.OnCyan,        null)
        }
        PhantomButtonVariant.Secondary -> when (state) {
            ButtonVisualState.Disabled -> ButtonVisuals(c.SurfaceElevated, c.TextTertiary, c.Border)
            ButtonVisualState.Pressed  -> ButtonVisuals(c.SurfaceHover,    c.TextPrimary,  c.Border)
            ButtonVisualState.Focused  -> ButtonVisuals(c.SurfaceElevated, c.TextPrimary,  c.Cyan)
            ButtonVisualState.Normal   -> ButtonVisuals(c.SurfaceElevated, c.TextPrimary,  c.Border)
        }
        PhantomButtonVariant.Ghost -> when (state) {
            ButtonVisualState.Disabled -> ButtonVisuals(Color.Transparent, c.TextQuaternary, null)
            ButtonVisualState.Pressed  -> ButtonVisuals(c.SurfaceHover,    c.TextTertiary,   null)
            ButtonVisualState.Focused  -> ButtonVisuals(Color.Transparent, c.TextTertiary,   c.Border)
            ButtonVisualState.Normal   -> ButtonVisuals(Color.Transparent, c.TextTertiary,   null)
        }
    }
}