// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.nearby

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.*
import phantom.android.ui.theme.*

/**
 * NearbyScreen — Design Brief v3 §13: signature mesh-discovery screen.
 *
 * Architectural truth: PHANTOM finds peers over BLE / WiFi-Direct, not via
 * any cloud directory. The radar visual makes that physical fact visible —
 * concentric rings sweeping around the user's device, with detected peers
 * laid out in the list below.
 *
 * Stage 0 (this commit): pure visual scaffold. The peer list is a static
 * sample; the rings sweep purely for aesthetic. Real BLE/WiFi-Direct
 * scanning lands in a later transport milestone.
 */
@Composable
fun NearbyScreen(
    container: AppContainer,
    onNavigate: (Screen) -> Unit,
    onProfile: () -> Unit = {},
) {
    Scaffold(
        containerColor = PhantomTokens.Colors.SurfaceDeep,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = PhantomTokens.Spacing.comfortable),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Nearby",
                        color = TextPrimary,
                        style = PhantomType.headline,
                        modifier = Modifier.weight(1f),
                    )
                    // §04 Filter icon — opens future filter sheet (e.g. trusted-only).
                    IconButton(
                        onClick = { /* TODO: filter sheet */ },
                        modifier = Modifier.size(40.dp),
                    ) {
                        PhIconFunnel(color = PhantomTokens.Colors.TextSecondary, size = 18.dp)
                    }
                }
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp),
            ) {
                item { RadarPanel() }

                // BLE / WiFi-Direct discovery isn't wired into the transport
                // layer yet, so we surface an honest empty state instead of
                // mocking peers. Replace with the real peer feed once the
                // mesh transport milestone lands.
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = PhantomTokens.Spacing.comfortable,
                                vertical = PhantomTokens.Spacing.gap,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "LOOKING FOR PEERS",
                            color = PhantomTokens.Colors.TextTertiary,
                            style = PhantomType.overline,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Mesh discovery (BLE / WiFi-Direct) is coming in a future build. Stay close to your contact and we'll find them when ready.",
                            color = PhantomTokens.Colors.TextSecondary,
                            style = PhantomType.caption,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }

            BottomNavPill(
                activeTab = NavTab.NEARBY,
                onTabSelected = { tab ->
                    when (tab) {
                        NavTab.CALLS -> onNavigate(Screen.Calls)
                        NavTab.CHATS -> onNavigate(Screen.ChatList)
                        NavTab.SETTINGS -> onNavigate(Screen.Settings)
                        NavTab.NEARBY -> {}
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun RadarPanel() {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
        ),
        label = "sweep",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(horizontal = PhantomTokens.Spacing.comfortable, vertical = PhantomTokens.Spacing.comfortable)
            .clip(RoundedCornerShape(PhantomTokens.Radius.lg))
            .background(PhantomTokens.Colors.SurfaceElevated)
            .border(
                1.dp,
                PhantomTokens.Colors.BorderSubtle,
                RoundedCornerShape(PhantomTokens.Radius.lg),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val maxR = minOf(cx, cy) - 4f

            // Concentric rings — 3 levels, fading outward.
            for (i in 1..3) {
                val r = maxR * (i / 3f)
                drawCircle(
                    color = PhantomTokens.Colors.Cyan.copy(alpha = 0.18f - (i - 1) * 0.04f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1f),
                )
            }

            // Sweep arm — cyan fading gradient.
            rotate(degrees = sweepAngle, pivot = Offset(cx, cy)) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.Transparent,
                            PhantomTokens.Colors.Cyan.copy(alpha = 0.18f),
                            PhantomTokens.Colors.Cyan.copy(alpha = 0.55f),
                        ),
                        center = Offset(cx, cy),
                    ),
                    radius = maxR,
                    center = Offset(cx, cy),
                )
            }

            // Center dot — the user's own device.
            drawCircle(
                color = PhantomTokens.Colors.Cyan,
                radius = 5f,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = PhantomTokens.Colors.Cyan.copy(alpha = 0.25f),
                radius = 14f,
                center = Offset(cx, cy),
            )
        }

    }
}

