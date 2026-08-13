// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * PhantomAvatar — DesignV2 circular avatar.
 *
 * Handoff `design-system-notes.md §Avatars`:
 *   - Sizes 64 / 48 / 40 / 32pt, circular.
 *   - Gradient or flat colour + initials.
 *   - Online indicator: Success (#22C55E) with 2px surface-coloured border.
 *   - Verified: 14pt cyan circle with 7pt lock glyph on SurfaceDeep bg.
 *
 * The avatar itself is not interactive; wrappers that make it clickable are
 * responsible for the 48dp touch target. That said, at size ≥48dp the avatar
 * already meets the target; below that (32dp/40dp) the wrapping row must add
 * padding to compensate.
 *
 * [surfaceBackground] is the background colour of the SURFACE this avatar sits
 * on — used to punch through the online-indicator stroke so it visually cuts
 * into the parent surface. Defaults to SurfaceDeep, which is correct for most
 * screen backgrounds; override for cards.
 */
@Composable
fun PhantomAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    gradient: Pair<Color, Color>? = null,
    fillColor: Color = DesignV2Tokens.Colors.SurfaceElevated,
    online: Boolean = false,
    verified: Boolean = false,
    surfaceBackground: Color = DesignV2Tokens.Colors.SurfaceDeep,
) {
    val fontSize = (size.value * 0.4f).sp
    val indicatorSize = when {
        size <= 32.dp -> 8.dp
        size <= 48.dp -> 10.dp
        else -> 12.dp
    }
    val verifiedSize = 14.dp

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .then(
                    if (gradient != null) {
                        Modifier.background(
                            brush = Brush.linearGradient(listOf(gradient.first, gradient.second)),
                            shape = CircleShape,
                        )
                    } else {
                        Modifier.background(fillColor, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials.take(2).uppercase(),
                color = DesignV2Tokens.Colors.TextPrimary,
                style = TextStyle(
                    fontFamily = DesignV2FontDisplay,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    lineHeight = fontSize,
                ),
            )
        }

        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(indicatorSize + 4.dp)
                    .background(surfaceBackground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(indicatorSize)
                        .background(DesignV2Tokens.Colors.Success, CircleShape),
                )
            }
        }

        if (verified) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(verifiedSize)
                    .background(DesignV2Tokens.Colors.Cyan, CircleShape)
                    .border(1.dp, DesignV2Tokens.Colors.SurfaceDeep, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_dv2_encrypted),
                    contentDescription = null,
                    tint = DesignV2Tokens.Colors.SurfaceDeep,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}