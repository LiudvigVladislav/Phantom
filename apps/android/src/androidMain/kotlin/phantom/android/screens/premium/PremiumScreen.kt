// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.premium

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.*
import phantom.android.ui.theme.*

/**
 * PremiumScreen — Design Brief v3 §14: PHANTOM PRO upgrade.
 *
 * Tone: never marketing-loud. PRO unlocks operator-grade features (custom
 * relays, Berkeley Mono, advanced sealed-sender variants) for users whose
 * threat model demands them. Free tier is fully functional E2E messaging —
 * PRO is for journalists, dissidents, security teams.
 */
@Composable
fun PremiumScreen(
    onBack: () -> Unit,
) {
    val scroll = rememberScrollState()

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
                        .height(64.dp)
                        .padding(horizontal = PhantomTokens.Spacing.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        PhIconBack(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "PHANTOM PRO",
                        color = TextPrimary,
                        style = PhantomType.headline,
                    )
                }
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = PhantomTokens.Spacing.comfortable)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(PhantomTokens.Spacing.gap))

            // Hero glyph — concentric vault rings with cyan core.
            Canvas(modifier = Modifier.size(96.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxR = size.width / 2f - 4f
                for (i in 1..3) {
                    drawCircle(
                        color = PhantomTokens.Colors.Cyan.copy(alpha = 0.20f - (i - 1) * 0.05f),
                        radius = maxR * (i / 3f),
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.2f),
                    )
                }
                drawCircle(
                    color = PhantomTokens.Colors.Cyan,
                    radius = maxR * 0.18f,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = PhantomTokens.Colors.Cyan.copy(alpha = 0.2f),
                    radius = maxR * 0.36f,
                    center = Offset(cx, cy),
                )
            }

            Spacer(Modifier.height(PhantomTokens.Spacing.gap))

            Text(
                text = "Operator-grade privacy",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 32.sp,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "PHANTOM is free and fully encrypted. PRO unlocks the controls a journalist, dissident, or security team needs.",
                color = TextDim,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(PhantomTokens.Spacing.gap))

            PremiumFeatureCard(
                kicker = "RELAY CONTROL",
                title = "Bring your own relay",
                body = "Route through a self-hosted onion or LAN relay. Default to phntm.pro only when you choose to.",
            )
            Spacer(Modifier.height(12.dp))
            PremiumFeatureCard(
                kicker = "BERKELEY MONO",
                title = "Premium typeface",
                body = "Tabular figures, ligatures, and a cut tuned for cryptographic readouts. Used in fingerprints, key prefixes, hashes.",
            )
            Spacer(Modifier.height(12.dp))
            PremiumFeatureCard(
                kicker = "STEALTH ROUTING",
                title = "Advanced sealed sender",
                body = "Per-conversation tag rotation, decoy traffic shaping, and Tor-bridge fallback when relays are unreachable.",
            )
            Spacer(Modifier.height(12.dp))
            PremiumFeatureCard(
                kicker = "EXTENDED LIFETIME",
                title = "Disappearing windows up to 1 year",
                body = "Free is capped at 30 days. PRO lets you set retention from one minute to one year per conversation.",
            )

            Spacer(Modifier.height(PhantomTokens.Spacing.sectionGap))

            Button(
                onClick = { /* TODO: launch billing flow */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(9999.dp),
                        clip = false,
                        spotColor = CyanAccent.copy(alpha = 0.30f),
                        ambientColor = CyanAccent.copy(alpha = 0.10f),
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanAccent,
                    contentColor = BgDeep,
                ),
                shape = RoundedCornerShape(9999.dp),
            ) {
                Text(
                    text = "Upgrade — $4.99 / month",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(PhantomTokens.Spacing.tight))

            Text(
                text = "Cancel any time. PRO never weakens encryption — Free is fully E2E.",
                color = PhantomTokens.Colors.TextTertiary,
                style = PhantomType.caption,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PremiumFeatureCard(kicker: String, title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomTokens.Radius.md))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        PhantomTokens.Colors.SurfaceElevated,
                        PhantomTokens.Colors.SurfaceElevated.copy(alpha = 0.85f),
                    ),
                ),
            )
            .border(
                1.dp,
                PhantomTokens.Colors.Cyan.copy(alpha = 0.10f),
                RoundedCornerShape(PhantomTokens.Radius.md),
            )
            .padding(horizontal = PhantomTokens.Spacing.comfortable, vertical = PhantomTokens.Spacing.tight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(PhantomTokens.Colors.Cyan),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = kicker,
                color = PhantomTokens.Colors.Cyan,
                style = PhantomType.overline,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = TextPrimary,
            style = PhantomType.title,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            color = PhantomTokens.Colors.TextSecondary,
            style = PhantomType.body,
        )
    }
}
