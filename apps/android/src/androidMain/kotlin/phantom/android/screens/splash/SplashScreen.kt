// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono

@Composable
fun PhantomSplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LogoWithSonar()

            Spacer(Modifier.height(32.dp))

            // Brand wordmark — Geist 17sp medium with -0.01em tracking,
            // matching the design system's PhantomTopBar treatment.
            Text(
                text = "PHANTOM",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.17).sp,
            )

            Spacer(Modifier.height(16.dp))

            // Tagline — display Geist medium with negative tracking.
            // Inherits PhantomFontGeist via LocalTextStyle since no
            // fontFamily is set here.
            Text(
                text = "Your presence,\nknown to no one.",
                color = TextSecondary,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.20).sp,
                textAlign = TextAlign.Center,
            )
        }

        Text(
            text = "Alpha 1",
            color = TextDim.copy(alpha = 0.55f),
            fontSize = 10.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 0.8.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 42.dp),
        )
    }
}

@Composable
private fun LogoWithSonar() {
    val infiniteTransition = rememberInfiniteTransition(label = "splash")

    val logoPulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logoPulse",
    )

    val ring1 by sonarRingProgress(infiniteTransition, offsetFraction = 0f)
    val ring2 by sonarRingProgress(infiniteTransition, offsetFraction = 0.333f)
    val ring3 by sonarRingProgress(infiniteTransition, offsetFraction = 0.666f)

    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            listOf(ring1, ring2, ring3).forEach { progress ->
                val scale = 1f + progress * 1.4f
                val alpha = (1f - progress).coerceIn(0f, 1f) * 0.18f
                drawCircle(
                    color = CyanAccent,
                    radius = (size.minDimension / 2f) * scale,
                    style = Stroke(width = 1.5.dp.toPx()),
                    alpha = alpha,
                )
            }
        }

        Image(
            painter = painterResource(R.drawable.phantom_logo),
            contentDescription = "PHANTOM logo",
            modifier = Modifier
                .size(160.dp)
                .scale(logoPulse)
                .clip(RoundedCornerShape(36.dp)),
        )
    }
}

@Composable
private fun sonarRingProgress(
    transition: InfiniteTransition,
    offsetFraction: Float,
): State<Float> = transition.animateFloat(
    initialValue = offsetFraction,
    targetValue = 1f + offsetFraction,
    animationSpec = infiniteRepeatable(
        animation = tween(3000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    ),
    label = "sonar_$offsetFraction",
)
