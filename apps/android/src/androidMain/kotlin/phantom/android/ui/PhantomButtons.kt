// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.theme.BgDeep
import phantom.android.ui.theme.CyanAccent
import phantom.android.ui.theme.TextDim

/**
 * Primary cyan CTA — the canonical PHANTOM button for "commit" actions
 * (Get started / Send handshake / Save / Open chat / etc.).
 *
 * Spec from FULL_COMPOSE Components / BUTTONS:
 *   bg          : Cyan #00D4FF
 *   text        : SurfaceDeep (Inter 15sp Medium)
 *   shape       : pill (9999dp) on mobile, 8dp on tablet/web — we ship the
 *                 mobile pill default and let the call site override `shape`.
 *   shadow      : 0 2px 8px rgba(0,212,255,0.09) ambient + 0 1px 4px spot
 *                 — modelled here as Compose `shadow(elevation, shape,
 *                 ambientColor=Cyan@10%, spotColor=Cyan@20%)`.
 *   focus ring  : 2dp Cyan@30% outside the pill when focused/pressed.
 *
 * Disabled = Cyan@18% bg + TextDim text, no shadow, no focus ring.
 */
@Composable
fun PhantomPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    shape: RoundedCornerShape = RoundedCornerShape(9999.dp),
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val showFocusRing = enabled && (pressed || focused)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (enabled) Modifier.shadow(
                    elevation = 8.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = CyanAccent.copy(alpha = 0.10f),
                    spotColor = CyanAccent.copy(alpha = 0.20f),
                ) else Modifier,
            )
            .then(
                if (showFocusRing)
                    Modifier.border(
                        width = 2.dp,
                        color = CyanAccent.copy(alpha = 0.30f),
                        shape = shape,
                    )
                else Modifier,
            )
            .clip(shape)
            .background(if (enabled) CyanAccent else CyanAccent.copy(alpha = 0.18f))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) BgDeep else TextDim.copy(alpha = 0.5f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Ghost / secondary CTA — same anatomy, no fill, BorderSubtle outline,
 * TextPrimary label. Used as the negative/dismiss companion to a primary
 * pill (e.g. "Skip for now" under "Export key").
 */
@Composable
fun PhantomGhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    border: BorderStroke = BorderStroke(1.dp, phantom.android.ui.theme.BorderSubtle),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .border(border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) phantom.android.ui.theme.TextPrimary else TextDim,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
