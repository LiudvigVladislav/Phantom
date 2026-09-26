// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.premium

import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.R
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
 * Plans are previews until payment and entitlement verification exist.
 */
@Composable
fun PremiumScreen(
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf(Tier.Pro) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailableMessage = stringResource(R.string.pricing_unavailable)
    val backLabel = stringResource(R.string.premium_back)

    fun showComingSoon() {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(unavailableMessage)
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
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp).semantics { contentDescription = backLabel },
                    ) {
                        PhIconBack(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
                    }
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = stringResource(R.string.premium_title),
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
                text = stringResource(R.string.premium_preview),
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
                text = stringResource(R.string.premium_footer),
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

private enum class Tier(@StringRes val labelRes: Int) {
    Free(R.string.pricing_name_free),
    Plus(R.string.pricing_name_plus),
    Pro(R.string.pricing_name_pro),
}

@Composable
private fun TierSelector(selected: Tier, onSelect: (Tier) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
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
                    text = stringResource(tier.labelRes).uppercase(locale),
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
        tierName = stringResource(R.string.pricing_name_free),
        price = null,
        cadence = null,
        tagline = stringResource(R.string.pricing_tagline_free),
        features = listOf(
            stringResource(R.string.pricing_feature_private_messages),
            stringResource(R.string.pricing_feature_free_groups),
            stringResource(R.string.pricing_feature_calls),
            stringResource(R.string.pricing_feature_free_disappearing),
            stringResource(R.string.pricing_feature_default_relay),
        ),
        ctaLabel = stringResource(R.string.pricing_cta_current),
        ctaVariant = CtaVariant.Ghost,
        ghostInset = false,
        onCta = onCta,
    )
}

@Composable
private fun PlusPlanCard(onCta: () -> Unit) {
    PlanCardShell(
        recommended = false,
        tierName = stringResource(R.string.pricing_name_plus),
        price = stringResource(R.string.pricing_price_plus),
        cadence = stringResource(R.string.pricing_monthly),
        tagline = stringResource(R.string.pricing_tagline_plus),
        features = listOf(
            stringResource(R.string.pricing_feature_everything_free),
            stringResource(R.string.pricing_feature_plus_groups),
            stringResource(R.string.pricing_feature_plus_disappearing),
            stringResource(R.string.pricing_feature_custom_relay),
            stringResource(R.string.pricing_feature_priority_routing),
            stringResource(R.string.pricing_feature_premium_typeface),
        ),
        ctaLabel = stringResource(R.string.pricing_cta_plus),
        ctaVariant = CtaVariant.Secondary,
        ghostInset = false,
        onCta = onCta,
    )
}

@Composable
private fun ProPlanCard(onCta: () -> Unit) {
    PlanCardShell(
        recommended = true,
        tierName = stringResource(R.string.pricing_name_pro),
        price = stringResource(R.string.pricing_price_pro),
        cadence = stringResource(R.string.pricing_monthly),
        tagline = stringResource(R.string.pricing_tagline_pro),
        features = listOf(
            stringResource(R.string.pricing_feature_everything_plus),
            stringResource(R.string.pricing_feature_mono_typeface),
            stringResource(R.string.pricing_feature_stealth),
            stringResource(R.string.pricing_feature_tor_bridge),
            stringResource(R.string.pricing_feature_self_hosted),
            stringResource(R.string.pricing_feature_sealed_sender),
        ),
        ctaLabel = stringResource(R.string.pricing_cta_pro),
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
                        text = stringResource(R.string.premium_free_price),
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
                        text = stringResource(R.string.pricing_ghost_title),
                        color = PhantomTokens.Colors.TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = PhantomFontMono,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.4.sp,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.pricing_ghost_body),
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
                            modifier = Modifier.clearAndSetSemantics { },
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
                    text = stringResource(R.string.pricing_recommended),
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
