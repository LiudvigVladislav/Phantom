// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
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
    // Sonar rings around the logo were too easily read as a radar / network
    // animation, which conflicts with PHANTOM's "no servers, no metadata"
    // narrative on the very first screen the user sees. Replaced with a
    // restrained scale-pulse on the logo itself — same sense of life, none
    // of the architectural lie.
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

    // 2026-07-16: switched from R.drawable.phantom_logo (old ghost-themed
    // rounded app icon) to R.drawable.phantom_splash (new brandmark, high-res
    // transparent PNG showing the full mark). The previous
    // .clip(RoundedCornerShape(36.dp)) was appropriate for the old app-icon-
    // style artwork; the new mark is an unbounded transparent brandmark and
    // should show uncropped, so the clip has been removed.
    Image(
        painter = painterResource(R.drawable.phantom_splash),
        contentDescription = "PHANTOM logo",
        modifier = Modifier
            .size(180.dp)
            .scale(logoPulse),
    )
}
