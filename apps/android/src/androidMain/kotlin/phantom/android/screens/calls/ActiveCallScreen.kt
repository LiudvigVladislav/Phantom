// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import phantom.android.ui.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import phantom.android.calls.ActiveCall
import phantom.android.calls.CallState
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono

private fun formatCallDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

@Composable
fun ActiveCallScreen(
    call: ActiveCall,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onBack: () -> Unit,
) {
    // Timer: produce elapsed seconds, updating every second
    val elapsedSeconds by produceState(initialValue = 0L, key1 = call.startedAt, key2 = call.state) {
        if (call.state == CallState.IN_CALL && call.startedAt != null) {
            while (true) {
                val elapsed = (System.currentTimeMillis() - call.startedAt) / 1000L
                value = elapsed.coerceAtLeast(0L)
                delay(1_000)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // Back arrow — top left
        Box(
            modifier = Modifier
                .padding(start = 16.dp, top = 12.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(Surface2)
                .clickable(onClick = onBack)
                .align(Alignment.TopStart),
            contentAlignment = Alignment.Center,
        ) {
            PhIconBack(color = TextPrimary, size = 18.dp)
        }

        // Main content column — centered
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.7f))

            // PHANTOM_FULL_COMPOSE §08 State B — name Geist 28px, "Connected"
            // success-green status pill, timer JetBrains Mono 56px (the
            // typographic centerpiece), avatar 80dp.
            Text(
                text = call.remoteUsername,
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.28).sp,
            )

            Spacer(Modifier.height(10.dp))

            when (call.state) {
                CallState.IN_CALL -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Success),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Connected",
                            color = Success,
                            fontSize = 11.sp,
                            fontFamily = PhantomFontMono,
                            letterSpacing = 1.6.sp,
                        )
                    }
                }
                CallState.CALLING -> Text(
                    text = "CALLING…",
                    color = TextDim,
                    fontSize = 11.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.6.sp,
                )
                CallState.RINGING -> Text(
                    text = "CONNECTING…",
                    color = TextDim,
                    fontSize = 11.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.6.sp,
                )
                CallState.ENDED -> Text(
                    text = "CALL ENDED",
                    color = TextDim,
                    fontSize = 11.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.6.sp,
                )
                CallState.REJECTED -> Text(
                    text = "CALL DECLINED",
                    color = Danger,
                    fontSize = 11.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.6.sp,
                )
                else -> {}
            }

            Spacer(Modifier.height(28.dp))

            // The timer — Mono 56px, the typographic centerpiece per the
            // canonical doc. Renders only during an active call; other
            // states show a small placeholder so the column doesn't jump.
            if (call.state == CallState.IN_CALL) {
                Text(
                    text = formatCallDuration(elapsedSeconds),
                    color = TextPrimary,
                    fontSize = 56.sp,
                    fontFamily = PhantomFontMono,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-0.5).sp,
                )
            } else {
                Spacer(Modifier.height(56.dp))
            }

            Spacer(Modifier.height(36.dp))

            // Avatar 80dp centered.
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A2535)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = call.remoteUsername.take(1).uppercase(),
                    color = CyanAccent,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.weight(1f))

            // Mute + Speaker toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Mute button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(if (call.isMuted) CyanAccent else Surface2)
                            .clickable(onClick = onToggleMute),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (call.isMuted) PhIconMicOff(color = BgDeep, size = 24.dp)
                        else PhIconMic(color = TextPrimary, size = 24.dp)
                    }
                    Text(
                        text = if (call.isMuted) "Muted" else "Mute",
                        color = if (call.isMuted) CyanAccent else TextDim,
                        fontSize = 11.sp,
                        fontFamily = PhantomFontMono,
                    )
                }

                // Speaker button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(if (call.isSpeakerOn) CyanAccent else Surface2)
                            .clickable(onClick = onToggleSpeaker),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (call.isSpeakerOn) PhIconVolume(color = BgDeep, size = 24.dp)
                        else PhIconVolumeOff(color = TextPrimary, size = 24.dp)
                    }
                    Text(
                        text = if (call.isSpeakerOn) "Speaker" else "Speaker",
                        color = if (call.isSpeakerOn) CyanAccent else TextDim,
                        fontSize = 11.sp,
                        fontFamily = PhantomFontMono,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // End call button — large, red, centered
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FloatingActionButton(
                    onClick = onHangup,
                    containerColor = Danger,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp),
                ) {
                    PhIconCallEnd(color = Color.White, size = 30.dp)
                }
                Text(
                    text = "End Call",
                    color = Danger.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontFamily = PhantomFontMono,
                )
            }

            Spacer(Modifier.height(56.dp))
        }
    }
}
