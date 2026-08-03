// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import phantom.android.R
import phantom.android.ui.theme.BgDeep

/**
 * PhantomSplashScreen — round-9 REDLINE §P0 pin.
 *
 * Prior shape rendered a full-screen legacy splash: old blue
 * `R.drawable.phantom_logo` + "PHANTOM" wordmark + "Your presence,
 * known to no one." tagline + "Alpha 1" version pill. That
 * followed the system Splash Screen (which also used the legacy
 * launcher icon), producing a jarring double-splash and shipping
 * an outdated brand identity end-to-end.
 *
 * Round-9 shape: minimal bridge between the Android SplashScreen
 * API (which now renders the new adaptive icon) and the first
 * Compose content. Just the new brand mark on the dark canvas —
 * no tagline, no version pill, no crown/text. Users see one
 * consistent splash asset the whole way. Version + tagline live
 * in Settings / About.
 *
 * `Dp` unit sourced via `androidx.compose.ui.unit.dp`.
 */
@Composable
fun PhantomSplashScreen() {
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
        contentAlignment = Alignment.Center,
    ) {
        // Uses R.drawable.phantom_splash (round-9 §P0 asset from
        // icon-refresh integration). Unbounded transparent
        // brandmark — no clip, no rounded corners; the source PNG
        // has the intended border treatment baked in.
        Image(
            painter = painterResource(R.drawable.phantom_splash),
            contentDescription = "PHANTOM logo",
            modifier = Modifier
                .size(180.dp)
                .scale(logoPulse),
        )
    }
}
