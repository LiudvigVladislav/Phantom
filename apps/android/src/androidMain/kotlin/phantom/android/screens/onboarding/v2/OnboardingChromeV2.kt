// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * OnboardingV2 chrome — shared frame elements around the step content.
 *
 * Four composables merged into one file per architect ask A1 (2026-08-01):
 * "объединить мелкие chrome-компоненты в OnboardingChrome.kt". Each is small
 * and thematically related (rendering the frame around the step body); a
 * one-file-per-composable split would push the module past the 10-14 file
 * target without adding readability.
 *
 * Contents:
 *   - [OnboardingCipherBackground] — drifting hex columns behind everything.
 *   - [OnboardingTopBarV2]         — Back pill + "STEP N OF 4" label.
 *   - [OnboardingStepDotsV2]       — 4 dots at the bottom (elongated selected).
 *   - [OnboardingToastV2]          — 2-second auto-dismiss overlay.
 *
 * All values sourced from handoff `Onboarding.dc.html` inline styles + the
 * REDLINE amendments (2026-08-01 §§C1..C4).
 */

// ── 2. Top bar — Back pill + STEP N OF 4 label ──────────────────────────────

/**
 * Top bar: 40 dp tall row. Back pill on the left (Modifier.clickable → back
 * navigation), "STEP N OF 4" label on the right in JBMono. Both hidden on
 * Welcome (per handoff `Onboarding.dc.html` ambient scene) — the caller
 * decides not to render this composable when the step is Welcome; this
 * composable itself is unconditional.
 */
@Composable
fun OnboardingTopBarV2(
    stepNumber: Int,
    totalSteps: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Back pill (round-2 REDLINE P2-4 a11y). Semantic contract:
        //   - Modifier.clickable carries `role = Role.Button` +
        //     `onClickLabel = "Back"` directly — the single node created
        //     by Compose for the clickable target owns the role, the
        //     announced label, AND the click action. A separate
        //     Modifier.semantics(mergeDescendants=true) { role = Button }
        //     is unnecessary here and would produce two nodes advertising
        //     the same button; the clickable-native form is the
        //     idiomatic one-node shape a screen reader expects.
        //   - The chevron Icon is decorative (contentDescription=null).
        //   - The visible "Back" Text is redundant with the a11y label
        //     but stays for sighted users; text-merge inside the
        //     clickable-owned semantics tree collapses everything into
        //     the same node.
        // Pinned by `OnboardingV2SemanticsTest.back_pill_has_role_label_and_click_action`.
        Row(
            modifier = Modifier
                // Round-3 REDLINE P2-5: pill shape (fully rounded)
                // per handoff CSS `border-radius: 999px` on a 36 px
                // pill. `RoundedCornerShape(percent = 50)` produces a
                // 50%-of-shorter-side corner radius, i.e. a full pill
                // for any aspect-ratio ≥ 1:1.
                .clip(RoundedCornerShape(percent = 50))
                .background(DesignV2Tokens.Colors.Surface)
                .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(percent = 50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Back",
                    onClick = onBackClick,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_dv2_back),
                contentDescription = null,
                tint = DesignV2Tokens.Colors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Back",
                color = DesignV2Tokens.Colors.TextSecondary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Normal,
                ),
            )
        }

        Text(
            text = "STEP $stepNumber OF $totalSteps",
            color = DesignV2Tokens.Colors.TextQuaternary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 2.4.sp,   // handoff .22em ≈ 2.4sp at 10.5 sp
            ),
        )
    }
}

// ── 3. Step dots — 4 dots, elongated selected ───────────────────────────────

/**
 * Bottom step indicator: 4 dots, current one elongated 24 dp cyan, past dots
 * cyan-dark #2A6F80, future dots grey #2A2F38. `dotsIndex` is 0..3;
 * `dotsIndex=-1` renders nothing (used for Welcome + FinaleConfirmation).
 *
 * Dots are NON-INTERACTIVE — no clickable, no Role.Button. Screen readers
 * skip them; navigation is controlled solely by Back pill + Continue CTA
 * per architect lock (2026-08-01 §C4).
 */
@Composable
fun OnboardingStepDotsV2(
    dotsIndex: Int,
    totalDots: Int = 4,
    modifier: Modifier = Modifier,
) {
    if (dotsIndex < 0) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .wrapContentSize(Alignment.Center),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(totalDots) { i ->
            val isCurrent = i == dotsIndex
            val isPast    = i <  dotsIndex
            val color = when {
                isCurrent -> DesignV2Tokens.Colors.Cyan
                isPast    -> Color(0xFF2A6F80)              // cyan-dark for completed
                else      -> Color(0xFF2A2F38)              // grey for future
            }
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (isCurrent) 24.dp else 6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

// ── 4. Toast — 2-second auto-dismiss overlay ────────────────────────────────

/**
 * Toast overlay — surface pill with border, positioned 110 dp from the
 * bottom. Auto-dismiss after 2 seconds. Caller passes a nullable `message`;
 * `null` → no toast rendered. Every change to a non-null message resets the
 * dismiss timer (keyed on the message string).
 *
 * Used by Pricing sheet (Commit 4) and finale confirmation Copy (Commit 3).
 * Placed in Chrome so both the flow-level and step-level composables can
 * emit toasts through the same visual.
 */
@Composable
fun OnboardingToastV2(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(2000L)
        onDismiss()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 110.dp)
            .wrapContentSize(Alignment.BottomCenter),
    ) {
        Text(
            text = message,
            color = DesignV2Tokens.Colors.TextPrimary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF014181E))
                .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
