// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.android.ui.designv2.components.PhantomButton

/**
 * WelcomeStepV2 — Step 0 / prelude. Chromeless: no top bar, no dots.
 *
 * Sourced from handoff `Onboarding.dc.html` "Step 0 · Welcome":
 *   - Logo inside a 132 dp radial-glow ring with `phBreathe 4.5s`
 *     ease-in-out infinite scale/opacity pulse.
 *   - "PHANTOM" wordmark, Geist SemiBold 31 sp, letter-spacing .14 em.
 *   - Tagline "Private communication, built to last." (Inter 15 sp).
 *   - 52 dp × 1 dp divider.
 *   - CORRECTED footer copy (redline §C3):
 *     "Your identity is created on this device. / No phone number required."
 *     — removed overclaims ("No servers. No trace.") from round-1 memo.
 *   - Primary CTA "Get started" (PhantomButton).
 *   - Secondary CTA "I already have an account" — HIDDEN this PR per
 *     architect ask A2 (Restore deferred to a later track). No disabled
 *     stub: a visible-but-disabled CTA still implies the feature exists
 *     and will confuse users when Restore never enables.
 */
@Composable
fun WelcomeStepV2(onContinueClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "welcome-breathe")
    val breathScale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4500, easing = { it * it * (3f - 2f * it) }),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe-scale",
    )
    val breathAlpha by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4500, easing = { it * it * (3f - 2f * it) }),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe-alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.55f))

        // Logo inside radial-glow ring, phBreathe pulse.
        //
        // Uses `R.drawable.dv2_logo_phantom` — the authoritative DesignV2
        // brand-mark PNG imported from the handoff at
        //   design_handoff_phantom_messenger/assets/logo-phantom.png
        // (1254×1254 RGBA, SHA-256 199051555efc6f7b42040be6ec599c178d688c50
        //  6227f306d870603b362be8b9, verified byte-identical on import).
        // NOT `R.drawable.phantom_logo` — that is the legacy Alpha-1
        // launcher-adjacent asset used by the old
        // `phantom.android.screens.onboarding.OnboardingScreen`; V2 must not
        // share it (redline 2026-08-01).
        //
        // Placed in `drawable-nodpi` so AGP does not scale-bucket a 1254 px
        // brand asset — the composable does the physical scaling to 126 dp
        // via Modifier.size(). ContentScale.Fit preserves the alpha channel
        // and the square aspect ratio; the source has generous transparent
        // padding around the ghost mark so Fit under a square 126 dp box
        // yields the intended visual (no need for Crop or Inside).
        Box(
            modifier = Modifier
                .size(132.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            DesignV2Tokens.Colors.Cyan.copy(alpha = 0.28f * breathAlpha),
                            DesignV2Tokens.Colors.Cyan.copy(alpha = 0.0f),
                        ),
                    ),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Decorative (P2-5 REDLINE): the visible "PHANTOM" wordmark
            // below announces the brand — a separate "PHANTOM logo"
            // contentDescription here would duplicate the announcement
            // for screen readers.
            Image(
                painter = painterResource(R.drawable.dv2_logo_phantom),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(126.dp)
                    .scale(breathScale),
            )
        }

        Spacer(Modifier.height(28.dp))

        // Wordmark — Geist SemiBold 31 sp, .14 em letter-spacing ≈ 4.34 sp at 31.
        Text(
            text = "PHANTOM",
            color = DesignV2Tokens.Colors.TextPrimary,
            style = TextStyle(
                fontFamily = DesignV2FontDisplay,
                fontSize = 31.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.3.sp,
            ),
        )

        Spacer(Modifier.height(14.dp))

        // Tagline — Inter 15 sp, TextSecondary.
        Text(
            text = "Private communication, built to last.",
            color = DesignV2Tokens.Colors.TextSecondary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )

        Spacer(Modifier.height(20.dp))

        // 52 dp × 1 dp divider.
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(1.dp)
                .background(DesignV2Tokens.Colors.Border),
        )

        Spacer(Modifier.height(20.dp))

        // Corrected footer copy per redline §C3.
        Text(
            text = "Your identity is created on this device.\nNo phone number required.",
            color = DesignV2Tokens.Colors.TextQuaternary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.24.sp,
                textAlign = TextAlign.Center,
            ),
        )

        Spacer(Modifier.weight(1f))

        PhantomButton(
            text = "Get started",
            onClick = onContinueClick,
            modifier = Modifier.fillMaxWidth(),
        )

        // Secondary "I already have an account" — INTENTIONALLY OMITTED.
        // Per architect ask A2 (2026-08-01): Restore is deferred to a later
        // track; the CTA returns when Restore lands.

        Spacer(Modifier.height(56.dp))
    }
}
