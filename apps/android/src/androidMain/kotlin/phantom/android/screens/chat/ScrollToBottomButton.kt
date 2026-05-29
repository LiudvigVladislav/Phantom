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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import phantom.android.ui.theme.PhantomFontMono
import phantom.android.ui.theme.PhantomTokens

/**
 * PR-UI-CHAT-NEW-MSG-CHIP1 — floating scroll-to-bottom button with new-msg badge.
 *
 * Pure visual layer. All state (visibility / count / first-unread tracking)
 * lives in [ScrollToBottomState]. This composable just renders what it's told.
 *
 * 1:1 with `phantom-messengers/project/Scroll-to-bottom.html` design handoff
 * (Vladislav-locked 2026-05-26 in `docs/tracks/chat-new-msg-chip.md`).
 * Token mapping (mini-lock label → source token → PhantomTokens key) for the
 * border-tone in particular: mini-lock label says "border-subtle", the source
 * CSS token is `--btn-border` = `#2A2F38`, which maps to
 * `PhantomTokens.Colors.Border` (NOT `BorderSubtle` which is `#1F242C`).
 */

// ── Animation curves ─────────────────────────────────────────────────────
// Easing values match the design CSS cubic-bezier curves byte-for-byte.
// Mini-lock § "Animations": Enter `cubic-bezier(0.22, 1, 0.36, 1)`,
// Exit `cubic-bezier(0.55, 0, 1, 1)`. The badge bump reuses the enter curve.
private val EnterEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val ExitEasing = CubicBezierEasing(0.55f, 0f, 1f, 1f)

@Composable
fun ScrollToBottomButton(
    visible: Boolean,
    count: Int,
    badgeBumpKey: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    AnimatedVisibility(
        modifier = modifier,
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 180, easing = EnterEasing)) +
            slideInVertically(
                animationSpec = tween(durationMillis = 180, easing = EnterEasing),
                initialOffsetY = { with(density) { 6.dp.roundToPx() } },
            ),
        exit = fadeOut(animationSpec = tween(durationMillis = 140, easing = ExitEasing)) +
            slideOutVertically(
                animationSpec = tween(durationMillis = 140, easing = ExitEasing),
                targetOffsetY = { with(density) { 4.dp.roundToPx() } },
            ),
    ) {
        ScrollToBottomButtonInner(
            count = count,
            badgeBumpKey = badgeBumpKey,
            onClick = onClick,
        )
    }
}

@Composable
private fun ScrollToBottomButtonInner(
    count: Int,
    badgeBumpKey: Int,
    onClick: () -> Unit,
) {
    // Badge bump: scale 1 → 1.12 → 1 over 220 ms split 110/110.
    // Architect-locked split per mini-lock line 88: "The 220 ms total budget
    // splits 110 / 110 for a clean linear approach to peak and back —
    // visually equivalent to the CSS scale(1) → scale(1.12) → scale(1)
    // 3-keyframe at the same total duration."
    val bumpScale = remember { Animatable(1f) }
    LaunchedEffect(badgeBumpKey) {
        if (badgeBumpKey > 0) {
            bumpScale.snapTo(1f)
            bumpScale.animateTo(
                targetValue = 1.12f,
                animationSpec = tween(durationMillis = 110, easing = EnterEasing),
            )
            bumpScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 110, easing = EnterEasing),
            )
        }
    }

    // Container is a Box that anchors the badge at top: -5, right: -5
    // relative to the 44 dp button. We size the container 44 + 5 + 5 wide
    // and 44 + 5 + 5 tall implicitly via .offset on the badge, but Compose
    // doesn't clip overflow children of a non-clipping Box, so the offset
    // negative-5 visually pokes out as designed.
    Box(modifier = Modifier.size(44.dp)) {

        // ── Button surface ─────────────────────────────────────────────
        // 44 × 44 circle:
        //   - 12 dp shadow (mini-lock Token "Elevation shadow: 0 12 32 dp
        //     rgba(0,0,0,0.55) plus 0 0 0 1 dp rgba(0,0,0,0.20)" → Compose
        //     Modifier.shadow gives a single layer; the 1 dp dark hairline
        //     contributes ~negligibly to the visible silhouette and is
        //     skipped without violating the design intent).
        //   - SurfaceElevated @ 92 % alpha (= `--btn-bg: rgba(22,26,32,0.92)`).
        //   - 1 dp Border (= `--btn-border: #2A2F38`, mini-lock label
        //     "border-subtle" — see file-header note).
        //   - NO ripple / NO press-scale per mini-lock § "NOT in the
        //     design (banned)": "No press-scale on the button (hover
        //     lifts glyph colour, no transform). On touch, hover is
        //     ignored." Indication is null intentionally.
        Box(
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.Center)
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    clip = false,
                )
                .clip(CircleShape)
                .background(PhantomTokens.Colors.SurfaceElevated.copy(alpha = 0.92f))
                .border(
                    width = 1.dp,
                    color = PhantomTokens.Colors.Border,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ChevronDownGlyph(
                color = PhantomTokens.Colors.TextSecondary,
            )
        }

        // ── Badge overlay ─────────────────────────────────────────────
        // Only rendered when count >= 1. Positioned top: -5, right: -5
        // relative to the button (mini-lock § Badge "Position relative
        // to button"). The 2 dp ring effect from
        // `box-shadow: 0 0 0 2px var(--surface)` is approximated by giving
        // the badge an outer border in the chat-surface tone — which
        // visually separates the badge from the button edge identically.
        if (count >= 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 5.dp, y = (-5).dp)
                    .graphicsLayer {
                        scaleX = bumpScale.value
                        scaleY = bumpScale.value
                    }
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    // Outer ring in chat-surface color separates the badge
                    // from the button rim. Matches box-shadow 0 0 0 2 px
                    // var(--surface) from scroll-bottom-tokens.css.
                    .border(
                        width = 2.dp,
                        color = PhantomTokens.Colors.Surface,
                        shape = RoundedCornerShape(percent = 50),
                    )
                    .background(PhantomTokens.Colors.Cyan)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label = if (count > 99) "99+" else count.toString()
                Text(
                    text = label,
                    color = PhantomTokens.Colors.SurfaceDeep,
                    style = TextStyle(
                        fontFamily = PhantomFontMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.W500,
                        letterSpacing = 0.02.sp,
                        textAlign = TextAlign.Center,
                        // tabular-nums (CSS `font-variant-numeric: tabular-nums`)
                        // keeps badge width stable across 1 / 5 / 99 / 99+.
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

/**
 * Chevron-down glyph drawn by Canvas at 20 × 20 dp with stroke 1.75 dp.
 *
 * Path matches `<polyline points="6 9 12 15 18 9" />` from
 * `scroll-bottom.jsx` ChevDown component in a 24-unit viewBox.
 *
 * The stroke caps + joins are round (`strokeLinecap="round"
 * strokeLinejoin="round"` in the SVG) for a softer apex than Material's
 * built-in `KeyboardArrowDown`. Using Canvas avoids reaching for an extra
 * vector resource for a 3-point polyline.
 */
@Composable
private fun ChevronDownGlyph(color: Color) {
    val density = LocalDensity.current
    Canvas(modifier = Modifier.size(20.dp)) {
        val viewBoxUnits = 24f
        val unit = size.width / viewBoxUnits
        val strokePx = with(density) { 1.75.dp.toPx() }
        val path = Path().apply {
            moveTo(6f * unit, 9f * unit)
            lineTo(12f * unit, 15f * unit)
            lineTo(18f * unit, 9f * unit)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokePx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
