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
import kotlinx.coroutines.launch
import phantom.android.ui.*
import phantom.android.ui.theme.*

/**
 * PremiumScreen — PHANTOM_FULL_COMPOSE §11.
 *
 * 3 tiers: Free / Plus / Pro.
 *  - Mobile: pill segmented selector shows one plan card at a time.
 *  - Plan card: tier name + price baseline-aligned (Geist), tagline below,
 *    feature list with neutral check ticks, CTA pill per tier variant.
 *  - Pro card carries a "RECOMMENDED" corner ribbon (top-right, drops down
 *    from the card edge) and a Ghost Mode inset block (surfaceDeep ·
 *    borderSubtle · 10dp radius).
 *
 * D-18 (2026-05-09): all CTAs visually enabled, but tap surfaces a
 * "Coming soon" snackbar — payment integration is deferred to Beta.
 */
@Composable
fun PremiumScreen(
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf(Tier.Pro) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showComingSoon() {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("Coming soon — payment integration lands in Beta")
        }
    }

    Scaffold(
        containerColor = PhantomTokens.Colors.SurfaceDeep,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    containerColor = Surface,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(10.dp),
                ) { Text(data.visuals.message, fontSize = 13.sp) }
            }
        },
        topBar = {
            // FULL_COMPOSE §11 mobile header: 52dp tall, 17sp title, no
            // bottom divider (the Surface bg already separates from the
            // SurfaceDeep page).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        PhIconBack(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
                    }
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "Upgrade",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.17).sp,
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // FULL_COMPOSE §11 mobile intro: a single subtitle paragraph,
            // not the heading + overline + description trio. Keeps the page
            // density restrained and lets the plan card carry the visual
            // weight.
            Text(
                text = "More control. Same PHANTOM.",
                color = TextDim.copy(alpha = 0.85f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            Spacer(Modifier.height(14.dp))

            // Pill segmented selector — Free / Plus / Pro.
            TierSelector(selected = selected, onSelect = { selected = it })

            Spacer(Modifier.height(16.dp))

            when (selected) {
                Tier.Free -> FreePlanCard(onCta = { /* current plan — no-op */ })
                Tier.Plus -> PlusPlanCard(onCta = ::showComingSoon)
                Tier.Pro -> ProPlanCard(onCta = ::showComingSoon)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Cancel any time. No data sold, ever.",
                color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.45f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

private enum class Tier { Free, Plus, Pro }

@Composable
private fun TierSelector(selected: Tier, onSelect: (Tier) -> Unit) {
    // FULL_COMPOSE §11 selector: full pill (radius 9999), 32dp tab height,
    // active tab uses neutral SurfaceHover (NOT cyan — cyan is reserved for
    // active CTAs and trust signals only). Active label is TextPrimary so
    // the selector stays a navigation control, not an accent.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(PhantomTokens.Colors.SurfaceDeep)
            .border(1.dp, BorderSubtle, RoundedCornerShape(50))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Tier.entries.forEach { tier ->
            val active = tier == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) SurfaceHover else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) PhantomTokens.Colors.Border else Color.Transparent,
                        RoundedCornerShape(50),
                    )
                    .clickable { onSelect(tier) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tier.name.uppercase(),
                    color = if (active) TextPrimary else TextDim,
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    letterSpacing = 1.6.sp,
                )
            }
        }
    }
}

// ── Tier cards ──────────────────────────────────────────────────────────────

@Composable
private fun FreePlanCard(onCta: () -> Unit) {
    PlanCardShell(
        recommended = false,
        tierName = "Free",
        price = null,
        cadence = null,
        tagline = "The foundation.",
        features = listOf(
            "1:1 end-to-end encrypted messages",
            "Group chats up to 8",
            "Voice & video calls",
            "Disappearing messages up to 30 days",
            "Phntm.pro relay",
        ),
        ctaLabel = "Current plan",
        ctaVariant = CtaVariant.Ghost,
        ghostInset = false,
        onCta = onCta,
    )
}

@Composable
private fun PlusPlanCard(onCta: () -> Unit) {
    PlanCardShell(
        recommended = false,
        tierName = "Plus",
        price = "$4.99",
        cadence = "/mo",
        tagline = "More control.",
        features = listOf(
            "Everything in Free",
            "Larger groups up to 64 members",
            "Disappearing messages up to 1 year",
            "Custom relay support",
            "Priority message routing",
            "Premium typeface (PP Neue Montreal)",
        ),
        ctaLabel = "Upgrade to Plus",
        ctaVariant = CtaVariant.Secondary,
        ghostInset = false,
        onCta = onCta,
    )
}

@Composable
private fun ProPlanCard(onCta: () -> Unit) {
    PlanCardShell(
        recommended = true,
        tierName = "Pro",
        price = "$9.99",
        cadence = "/mo",
        tagline = "Full control.",
        features = listOf(
            "Everything in Plus",
            "Berkeley Mono for fingerprints & keys",
            "Stealth routing & decoy traffic",
            "Tor-bridge fallback",
            "Self-hosted relay support",
            "Advanced sealed sender",
        ),
        ctaLabel = "Upgrade to Pro",
        ctaVariant = CtaVariant.Primary,
        ghostInset = true,
        onCta = onCta,
    )
}

private enum class CtaVariant { Primary, Secondary, Ghost }

@Composable
private fun PlanCardShell(
    recommended: Boolean,
    tierName: String,
    price: String?,
    cadence: String?,
    tagline: String,
    features: List<String>,
    ctaLabel: String,
    ctaVariant: CtaVariant,
    ghostInset: Boolean,
    onCta: () -> Unit,
) {
    val cardShape = RoundedCornerShape(12.dp)
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(
                    if (recommended) PhantomTokens.Colors.SurfaceElevated else Surface,
                )
                .border(
                    1.dp,
                    if (recommended) PhantomTokens.Colors.Border else BorderSubtle,
                    cardShape,
                )
                // FULL_COMPOSE §11 mobile compact: 16/16 padding so the card
                // feels dense and intentional. Earlier 20/22 felt bloated.
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // Tier name + price baseline-aligned per FULL_COMPOSE §11 mock.
            // The earlier mono "PLUS" overline + huge price stack diverged
            // from the React ProductScreen, where name and price live on
            // the same line.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = tierName,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.18).sp,
                )
                Spacer(Modifier.width(8.dp))
                if (price != null) {
                    Text(
                        text = price,
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.24).sp,
                    )
                    if (cadence != null) {
                        Text(
                            text = cadence,
                            color = TextDim.copy(alpha = 0.65f),
                            fontSize = 11.sp,
                            fontFamily = PhantomFontMono,
                            modifier = Modifier.padding(start = 1.dp, bottom = 4.dp),
                        )
                    }
                } else {
                    Text(
                        text = "Free forever",
                        color = TextDim.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = tagline,
                color = TextDim.copy(alpha = 0.65f),
                fontSize = 12.sp,
            )

            // Pro-only Ghost Mode callout — sits between intro and feature
            // list, before the bullets. The wow moment of the Pro card.
            if (ghostInset) {
                Spacer(Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PhantomTokens.Colors.SurfaceDeep)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "GHOST MODE",
                        color = PhantomTokens.Colors.TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = PhantomFontMono,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.4.sp,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "Become completely invisible on PHANTOM. Receive-only mode disables read receipts, presence, and discovery.",
                        color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            features.forEachIndexed { i, feature ->
                Row(
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Neutral check icon per FULL_COMPOSE §11 React mock —
                    // SurfaceHover circle + BorderSubtle ring + textSecondary
                    // tick. Reserves cyan for active CTAs and trust signals
                    // (e.g. Verified state) — using cyan everywhere drains
                    // the accent's meaning.
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(SurfaceHover)
                            .border(1.dp, BorderSubtle, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "✓",
                            color = PhantomTokens.Colors.TextSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = feature,
                        color = PhantomTokens.Colors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // FULL_COMPOSE §11: per-tier CTA variant.
            //  - Primary (Pro)     → 46dp pill, Cyan bg, surfaceDeep label
            //  - Secondary (Plus)  → 44dp pill, SurfaceElevated bg + Border
            //  - Ghost (Free)      → 40dp pill, transparent bg + BorderSubtle,
            //                        opacity 0.5 — visually disabled but
            //                        still clickable per D-18 (no payment
            //                        gating in Alpha 2).
            when (ctaVariant) {
                CtaVariant.Primary -> Button(
                    onClick = onCta,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        contentColor = BgDeep,
                    ),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(text = ctaLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                CtaVariant.Secondary -> Button(
                    onClick = onCta,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PhantomTokens.Colors.SurfaceElevated,
                        contentColor = TextPrimary,
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, PhantomTokens.Colors.Border,
                    ),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(text = ctaLabel, fontSize = 14.sp)
                }
                CtaVariant.Ghost -> Button(
                    onClick = onCta,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = PhantomTokens.Colors.TextTertiary,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                    shape = RoundedCornerShape(50),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                ) {
                    Text(
                        text = ctaLabel,
                        fontSize = 13.sp,
                        color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // FULL_COMPOSE §11 "RECOMMENDED" corner ribbon — drops down from
        // the top-right edge of the Pro card. Cyan bg + surfaceDeep label,
        // mono 8sp, only the bottom corners are rounded so it reads as
        // attached to the card edge.
        if (recommended) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 20.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 0.dp,
                            topEnd = 0.dp,
                            bottomStart = 6.dp,
                            bottomEnd = 6.dp,
                        ),
                    )
                    .background(CyanAccent)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "RECOMMENDED",
                    color = BgDeep,
                    fontSize = 8.sp,
                    fontFamily = PhantomFontMono,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                )
            }
        }
    }
}
