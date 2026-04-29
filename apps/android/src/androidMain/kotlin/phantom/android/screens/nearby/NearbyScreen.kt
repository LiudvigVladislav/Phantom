// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.nearby

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = PhantomTokens.Spacing.comfortable),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column {
                        Text(
                            text = "Nearby",
                            color = TextPrimary,
                            style = PhantomType.headline,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "MESH PEER DISCOVERY",
                            color = PhantomTokens.Colors.TextTertiary,
                            style = PhantomType.overline,
                        )
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

                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "DETECTED  ·  ${SAMPLE_PEERS.size}",
                        color = PhantomTokens.Colors.TextTertiary,
                        style = PhantomType.overline,
                        modifier = Modifier.padding(
                            horizontal = PhantomTokens.Spacing.comfortable,
                            vertical = PhantomTokens.Spacing.tight,
                        ),
                    )
                }

                items(SAMPLE_PEERS, key = { it.fingerprint }) { peer ->
                    NearbyPeerRow(peer = peer, onTap = { /* TODO: handshake */ })
                    HorizontalDivider(
                        color = BorderSubtle,
                        thickness = 1.dp,
                        modifier = Modifier.padding(start = 72.dp),
                    )
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

        // Sample peer pings — tiny dots floating at fixed positions on the
        // radar. Real implementation will project signal strength → radius.
        Canvas(modifier = Modifier.size(220.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = minOf(cx, cy) - 4f
            SAMPLE_PEERS.forEachIndexed { idx, _ ->
                val angle = (idx * 67f) % 360f
                val r = maxR * (0.45f + (idx % 3) * 0.18f)
                val rad = Math.toRadians(angle.toDouble())
                val x = cx + r * Math.cos(rad).toFloat()
                val y = cy + r * Math.sin(rad).toFloat()
                drawCircle(
                    color = PhantomTokens.Colors.Cyan,
                    radius = 3.5f,
                    center = Offset(x, y),
                )
                drawCircle(
                    color = PhantomTokens.Colors.Cyan.copy(alpha = 0.25f),
                    radius = 8f,
                    center = Offset(x, y),
                )
            }
        }
    }
}

private data class NearbyPeer(
    val handle: String,
    val fingerprint: String,
    val transport: String,
    val signalDbm: Int,
    val trusted: Boolean,
)

private val SAMPLE_PEERS = listOf(
    NearbyPeer("@river", "A1F3·E92B", "BLE", -42, trusted = true),
    NearbyPeer("@forge", "5C0B·D7A1", "WiFi", -56, trusted = false),
    NearbyPeer("@aurora", "9AE2·44C8", "BLE", -71, trusted = false),
)

@Composable
private fun NearbyPeerRow(peer: NearbyPeer, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(
                horizontal = PhantomTokens.Spacing.comfortable,
                vertical = PhantomTokens.Spacing.tight,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAvatar(name = peer.handle, size = 40.dp)
        Spacer(Modifier.width(PhantomTokens.Spacing.tight))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = peer.handle,
                    color = TextPrimary,
                    style = PhantomType.title,
                )
                if (peer.trusted) {
                    Spacer(Modifier.width(6.dp))
                    PhIconShieldCheck(color = PhantomTokens.Colors.Cyan, size = 14.dp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${peer.transport} · ${peer.signalDbm} dBm · ${peer.fingerprint}",
                color = PhantomTokens.Colors.TextTertiary,
                style = PhantomType.monoSm,
            )
        }
        // Trust chip — Cyan if already trusted, neutral otherwise.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(PhantomTokens.Radius.pill))
                .background(
                    if (peer.trusted) PhantomTokens.Colors.Cyan.copy(alpha = 0.10f)
                    else PhantomTokens.Colors.SurfaceHover,
                )
                .border(
                    1.dp,
                    if (peer.trusted) PhantomTokens.Colors.Cyan.copy(alpha = 0.45f)
                    else PhantomTokens.Colors.BorderSubtle,
                    RoundedCornerShape(PhantomTokens.Radius.pill),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (peer.trusted) "TRUSTED" else "HANDSHAKE",
                color = if (peer.trusted) PhantomTokens.Colors.Cyan else TextPrimary,
                style = PhantomType.overline,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
