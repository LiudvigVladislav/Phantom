// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.calls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import phantom.android.ui.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono

@Composable
fun IncomingCallScreen(
    username: String,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    // Pulsing animation: 1f → 1.15f → 1f, continuous
    val pulseScale = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        pulseScale.animateTo(
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(Modifier.weight(1f))

            // Pulsing avatar with glow ring
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp),
            ) {
                // Outer glow ring — pulsates with the avatar
                Box(
                    modifier = Modifier
                        .size(148.dp)
                        .scale(pulseScale.value)
                        .clip(CircleShape)
                        .background(CyanAccent.copy(alpha = 0.12f)),
                )
                // Mid glow ring
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .scale(pulseScale.value)
                        .clip(CircleShape)
                        .background(CyanAccent.copy(alpha = 0.18f)),
                )
                // Avatar circle with initials
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .scale(pulseScale.value)
                        .clip(CircleShape)
                        .background(Color(0xFF1A2535)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = username.take(1).uppercase(),
                        color = CyanAccent,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = username,
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Incoming call...",
                color = TextDim,
                fontSize = 14.sp,
                fontFamily = PhantomFontMono,
                letterSpacing = 0.6.sp,
            )

            Spacer(Modifier.weight(1f))

            // Action buttons row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp)
                    .padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Reject button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FloatingActionButton(
                        onClick = onReject,
                        containerColor = Danger,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp),
                    ) {
                        PhIconCallEnd(color = Color.White, size = 28.dp)
                    }
                    Text(
                        text = "Decline",
                        color = TextDim,
                        fontSize = 12.sp,
                        fontFamily = PhantomFontMono,
                    )
                }

                // Accept button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FloatingActionButton(
                        onClick = onAnswer,
                        containerColor = Success,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp),
                    ) {
                        PhIconPhoneFill(color = Color.White, size = 28.dp)
                    }
                    Text(
                        text = "Accept",
                        color = TextDim,
                        fontSize = 12.sp,
                        fontFamily = PhantomFontMono,
                    )
                }
            }
        }
    }
}
