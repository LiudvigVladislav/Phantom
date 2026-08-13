// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * PhantomToggle — DesignV2 on/off switch.
 *
 * Handoff `design-system-notes.md §Toggles`:
 *   - ON : cyan track, SurfaceDeep knob.
 *   - OFF: BorderHover track (#2A2F38), TextTertiary knob.
 *   - Disabled: 38% opacity.
 *
 * Track 44×26 (visual), knob 22×22. 48dp touch target enforced by an outer
 * padding-free wrapper Box sized 48dp — the visual switch sits centred inside.
 *
 * Uses `Modifier.toggleable` so the node carries the full ON/OFF semantics
 * (`ToggleableState`), `Role.Switch`, and click action in one modifier — the
 * combination TalkBack needs to announce "Switch, on" / "Switch, off" and the
 * combination Compose-test `assertIsOn()` / `assertIsOff()` matches on.
 */
@Composable
fun PhantomToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }

    val trackColor by animateColorAsState(
        targetValue = if (checked) DesignV2Tokens.Colors.Cyan else DesignV2Tokens.Colors.BorderHover,
        label = "toggle-track",
    )
    val knobColor by animateColorAsState(
        targetValue = if (checked) DesignV2Tokens.Colors.SurfaceDeep else DesignV2Tokens.Colors.TextTertiary,
        label = "toggle-knob",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        label = "toggle-offset",
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        interactionSource = interaction,
                        indication = null,
                        onValueChange = onCheckedChange,
                    )
                } else Modifier
            )
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .background(trackColor, CircleShape)
                .size(width = 44.dp, height = 26.dp),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = knobOffset, y = 2.dp)
                    .size(22.dp)
                    .background(knobColor, CircleShape),
            )
        }
    }
}