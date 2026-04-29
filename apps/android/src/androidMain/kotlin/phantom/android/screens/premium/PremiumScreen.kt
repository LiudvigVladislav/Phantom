// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.*
import phantom.android.ui.theme.*

/**
 * PremiumScreen — PHANTOM_FULL_COMPOSE §11.
 *
 * 3 tiers: Free / Plus / Pro.
 *  - Mobile: segmented tab selector shows one plan card at a time.
 *  - Plan card: tier name (Geist 20px), price (Geist 32px /mo), feature list
 *    with cyan check ticks, CTA pill 48dp.
 *  - Pro card carries an inline "Recommended" mono kicker and a Ghost Mode
 *    inset block (surfaceDeep · borderSubtle · radius 10dp).
 */
@Composable
fun PremiumScreen(
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf(Tier.Pro) }

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
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        PhIconBack(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Upgrade",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.20).sp,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Section overline
            Text(
                text = "PHANTOM PRO",
                color = CyanAccent,
                fontSize = 10.sp,
                fontFamily = PhantomFontMono,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.4.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Choose your plan",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 30.sp,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "PHANTOM is free and fully encrypted. Plus and Pro unlock advanced controls.",
                color = TextDim,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // Mobile segmented selector — Free / Plus / Pro.
            TierSelector(selected = selected, onSelect = { selected = it })

            Spacer(Modifier.height(20.dp))

            when (selected) {
                Tier.Free -> FreePlanCard()
                Tier.Plus -> PlusPlanCard()
                Tier.Pro -> ProPlanCard()
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Cancel any time. Subscription never weakens encryption — Free is fully E2E.",
                color = PhantomTokens.Colors.TextTertiary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

private enum class Tier { Free, Plus, Pro }

@Composable
private fun TierSelector(selected: Tier, onSelect: (Tier) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tier.entries.forEach { tier ->
            val active = tier == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) Surface2 else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) CyanAccent.copy(alpha = 0.30f) else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(tier) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tier.name.uppercase(),
                    color = if (active) CyanAccent else TextDim,
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    letterSpacing = 1.8.sp,
                )
            }
        }
    }
}

// ── Tier cards ──────────────────────────────────────────────────────────────

@Composable
private fun FreePlanCard() {
    PlanCardShell(
        recommended = false,
        kicker = "FREE",
        price = "$0",
        cadence = "/mo",
        features = listOf(
            "1:1 end-to-end encrypted messages",
            "Group chats up to 8",
            "Voice & video calls",
            "Disappearing messages up to 30 days",
            "Phntm.pro relay",
        ),
        ctaLabel = "Current plan",
        ctaEnabled = false,
        ghostInset = false,
    )
}

@Composable
private fun PlusPlanCard() {
    PlanCardShell(
        recommended = false,
        kicker = "PLUS",
        price = "$4.99",
        cadence = "/mo",
        features = listOf(
            "Everything in Free",
            "Larger groups up to 64 members",
            "Disappearing messages up to 1 year",
            "Custom relay support",
            "Priority message routing",
            "Premium typeface (PP Neue Montreal)",
        ),
        ctaLabel = "Upgrade to Plus",
        ctaEnabled = true,
        ghostInset = false,
    )
}

@Composable
private fun ProPlanCard() {
    PlanCardShell(
        recommended = true,
        kicker = "PRO",
        price = "$9.99",
        cadence = "/mo",
        features = listOf(
            "Everything in Plus",
            "Berkeley Mono for fingerprints & keys",
            "Stealth routing & decoy traffic",
            "Tor-bridge fallback",
            "Self-hosted relay support",
            "Advanced sealed sender",
        ),
        ctaLabel = "Upgrade to Pro",
        ctaEnabled = true,
        ghostInset = true,
    )
}

@Composable
private fun PlanCardShell(
    recommended: Boolean,
    kicker: String,
    price: String,
    cadence: String,
    features: List<String>,
    ctaLabel: String,
    ctaEnabled: Boolean,
    ghostInset: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PhantomTokens.Colors.SurfaceElevated)
            .border(
                1.dp,
                if (recommended) CyanAccent.copy(alpha = 0.35f) else BorderSubtle,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        if (recommended) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(CyanAccent.copy(alpha = 0.08f))
                    .border(
                        1.dp,
                        CyanAccent.copy(alpha = 0.30f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "RECOMMENDED",
                    color = CyanAccent,
                    fontSize = 9.sp,
                    fontFamily = PhantomFontMono,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = kicker,
            color = TextDim,
            fontSize = 9.sp,
            fontFamily = PhantomFontMono,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.8.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = price,
                color = TextPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.32).sp,
            )
            Text(
                text = cadence,
                color = TextDim,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))

        features.forEach { feature ->
            Row(
                modifier = Modifier.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(CyanAccent.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✓",
                        color = CyanAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = feature,
                    color = PhantomTokens.Colors.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }

        if (ghostInset) {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhantomTokens.Colors.SurfaceDeep)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhIconEye(color = PhantomTokens.Colors.TextTertiary, size = 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Ghost Mode",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Become completely invisible on PHANTOM. Receive-only mode disables read receipts, presence, and discovery.",
                    color = PhantomTokens.Colors.TextTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { /* TODO: launch billing flow */ },
            enabled = ctaEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ctaEnabled) CyanAccent else Color.Transparent,
                contentColor = if (ctaEnabled) BgDeep else TextDim,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = TextDim,
            ),
            border = if (!ctaEnabled) androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle) else null,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = ctaLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
