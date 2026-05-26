// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.theme.BorderSubtle
import phantom.android.ui.theme.CyanAccent
import phantom.android.ui.theme.PhantomFontMono
import phantom.android.ui.theme.Surface2
import phantom.android.ui.theme.TextPrimary

// Vladislav-locked animation curves (1:1 with the design handoff
// `scroll-bottom-tokens.css` @keyframes definitions).
private val EnterEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val ExitEasing  = CubicBezierEasing(0.55f, 0f, 1f, 1f)

// Enter / Exit / Bump durations — all from the design handoff annotations
// block ("Enter 180ms · Exit 140ms · Badge bump 220ms").
private const val ENTER_DURATION_MS = 180
private const val EXIT_DURATION_MS  = 140
private const val BUMP_DURATION_MS  = 220

// Translation offsets (the small "rise"/"drop" the design adds to the
// fade). 6 dp on enter, 4 dp on exit — both in the "translateY from
// below" direction since the button anchors to the bottom of the chat.
private val ENTER_RISE_DP = 6.dp
private val EXIT_DROP_DP  = 4.dp

/**
 * PR-UI-CHAT-NEW-MSG-CHIP1 — floating scroll-to-bottom button + new-messages
 * badge. **1:1 with the Vladislav-designer handoff bundle** — geometry,
 * colours, typography, and animation curves Vladislav-locked 2026-05-26 in
 * `docs/tracks/chat-new-msg-chip.md`.
 *
 * Visual structure:
 *   ┌─────────────┐    44 × 44 dp circle, surface-elevated @ 92 %, 1 dp
 *   │     ↓       │    border, soft drop shadow, chevron-down 20 dp glyph.
 *   └─────────────┘    Badge anchors top-right at (-5 dp, -5 dp); cyan
 *           ╳5         #00D4FF pill min-width 18 dp, MONO 10 sp tabular,
 *                      surface-deep text, 2 dp chat-surface ring.
 *
 * Caller is expected to place this inside a `Box(Modifier.fillMaxSize())`
 * with `Modifier.align(Alignment.BottomEnd).padding(end = 14.dp,
 * bottom = 70.dp)` — the 70 dp = 56 dp composer + 14 dp design gap.
 */
@Composable
fun ScrollToBottomButton(
    state: ScrollToBottomState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(animationSpec = tween(ENTER_DURATION_MS, easing = EnterEasing)) +
            slideInVertically(
                animationSpec = tween(ENTER_DURATION_MS, easing = EnterEasing),
                initialOffsetY = { full -> (ENTER_RISE_DP.value).toInt() },
            ),
        exit = fadeOut(animationSpec = tween(EXIT_DURATION_MS, easing = ExitEasing)) +
            slideOutVertically(
                animationSpec = tween(EXIT_DURATION_MS, easing = ExitEasing),
                targetOffsetY = { full -> (EXIT_DROP_DP.value).toInt() },
            ),
        modifier = modifier,
    ) {
        ButtonWithBadge(
            count = state.count,
            bumpKey = state.bumpKey,
            onClick = onClick,
        )
    }
}

@Composable
private fun ButtonWithBadge(
    count: Int,
    bumpKey: Int,
    onClick: () -> Unit,
) {
    // Single Animatable that drives the badge bump scale. Triggered by
    // changes to `bumpKey` (the state holder bumps it on every accepted
    // incoming). Half the budget approaches scale 1.12, the other half
    // returns to 1 — visually identical to the CSS 3-keyframe at the
    // same 220 ms total.
    val bumpScale = remember { Animatable(1f) }
    LaunchedEffect(bumpKey) {
        if (bumpKey > 0) {
            bumpScale.snapTo(1f)
            bumpScale.animateTo(
                targetValue = 1.12f,
                animationSpec = tween(BUMP_DURATION_MS / 2, easing = EnterEasing),
            )
            bumpScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(BUMP_DURATION_MS / 2, easing = EnterEasing),
            )
        }
    }

    // Wrapper Box hosts the button + the floating badge. Sized large
    // enough to NOT clip the badge protrusion (badge sits at top:-5,
    // right:-5 relative to the 44 dp button → wrapper is 49 × 49 dp,
    // with the badge tucked into the wrapper's top-right corner).
    Box(modifier = Modifier.size(49.dp)) {
        // The actual 44 × 44 disc, centred within the 49 dp wrapper so
        // the badge can overflow up-and-right.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(44.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                )
                .background(
                    color = Surface2.copy(alpha = 0.92f),
                    shape = CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = BorderSubtle,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll to bottom",
                tint = TextPrimary.copy(alpha = 0.74f), // matches --text-secondary
                modifier = Modifier.size(20.dp),
            )
        }

        if (count > 0) {
            val display = if (count > 99) "99+" else count.toString()
            // Badge floats top-right of the disc. With wrapper = 49 dp and
            // disc = 44 dp aligned BottomStart, the disc occupies cols
            // 0..43 / rows 5..48 of the wrapper. Badge anchors top:0,
            // right:0 of the wrapper to sit at (top:-5, right:-5) of the
            // disc — exactly what the CSS says.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        scaleX = bumpScale.value
                        scaleY = bumpScale.value
                    }
                    .shadow(
                        elevation = 0.dp,
                        shape = RoundedCornerShape(50),
                    )
                    // 2 dp ring of chat-surface around the badge to separate
                    // it from the button. The design uses `box-shadow: 0 0
                    // 0 2px var(--surface)`; the closest Compose primitive
                    // is a transparent border on a slightly larger pill,
                    // but a simpler implementation is to draw a 2-dp
                    // chat-surface border directly (visually equivalent
                    // since the disc colour behind the badge is not
                    // changing).
                    .background(
                        color = phantom.android.ui.theme.Surface,
                        shape = RoundedCornerShape(50),
                    )
                    .padding(2.dp)
                    .background(
                        color = CyanAccent,
                        shape = RoundedCornerShape(50),
                    )
                    .defaultMinSize(minWidth = 18.dp)
                    .height(18.dp)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = display,
                    color = phantom.android.ui.theme.BgDeep,
                    fontFamily = PhantomFontMono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
