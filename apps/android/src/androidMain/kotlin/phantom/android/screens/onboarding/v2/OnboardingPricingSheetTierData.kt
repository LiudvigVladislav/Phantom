// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.annotation.StringRes
import phantom.android.R

/**
 * Static tier data for the onboarding pricing bottom sheet.
 *
 * Extracted from `OnboardingPricingSheetV2.kt` in round-3 REDLINE
 * §P2-5 file-split — the sheet composables live in the main file,
 * the tier-card composables in `OnboardingPricingSheetTierCard.kt`,
 * and this file owns the immutable content. Splitting by role
 * keeps each file below the 500-line CLAUDE.md limit AND makes it
 * obvious where to look when handoff content changes vs when the
 * modal frame or a tier-card shape changes.
 *
 * Round-1 REDLINE on Commit 4 §P1-2 pt 4: Business features and
 * CTA labels re-synced with handoff `Onboarding.dc.html`
 * §buildPricing (`tierCard('tier-business',...`). Pre-REDLINE list
 * ("Team workspace with roles", "Shared media vaults",
 * "Audit-friendly retention controls") was invented; corrected to
 * the handoff's actual list below.
 *
 * Round-3 REDLINE on Commit 4 §P1-4: Pro tier callout body
 * rewritten to match the actual `phantom.core.transport.PrivacyMode.Ghost`
 * runtime contract (Tor onion only, no silent downgrade) rather
 * than the pre-round-3 aspirational "completely invisible /
 * receive-only" copy. Additional invisibility features remain
 * called out as a future Pro capability.
 */
internal data class PricingTierV2(
    @StringRes val nameRes: Int,
    @StringRes val priceRes: Int,
    @StringRes val subRes: Int,
    val iconRes: Int,
    @StringRes val ctaRes: Int,
    val recommended: Boolean = false,
    @StringRes val calloutTitleRes: Int? = null,
    @StringRes val calloutBodyRes: Int? = null,
    val featureRes: List<Int>,
)

internal val PRICING_TIERS: List<PricingTierV2> = listOf(
    PricingTierV2(
        nameRes = R.string.pricing_name_plus,
        priceRes = R.string.pricing_price_plus,
        subRes = R.string.pricing_tagline_plus,
        iconRes = R.drawable.ic_dv2_tier_plus,
        ctaRes = R.string.pricing_cta_plus,
        featureRes = listOf(
            R.string.pricing_feature_everything_free,
            R.string.pricing_feature_plus_groups,
            R.string.pricing_feature_plus_disappearing,
            R.string.pricing_feature_custom_relay,
            R.string.pricing_feature_priority_routing,
            R.string.pricing_feature_premium_typeface,
        ),
    ),
    PricingTierV2(
        nameRes = R.string.pricing_name_pro,
        priceRes = R.string.pricing_price_pro,
        subRes = R.string.pricing_tagline_pro,
        iconRes = R.drawable.ic_dv2_tier_pro,
        ctaRes = R.string.pricing_cta_pro,
        recommended = true,
        calloutTitleRes = R.string.pricing_ghost_title,
        calloutBodyRes = R.string.pricing_ghost_body,
        featureRes = listOf(
            R.string.pricing_feature_everything_plus,
            R.string.pricing_feature_mono_typeface,
            R.string.pricing_feature_stealth,
            R.string.pricing_feature_tor_bridge,
            R.string.pricing_feature_self_hosted,
            R.string.pricing_feature_sealed_sender,
        ),
    ),
    PricingTierV2(
        nameRes = R.string.pricing_name_business,
        priceRes = R.string.pricing_price_business,
        subRes = R.string.pricing_tagline_business,
        iconRes = R.drawable.ic_dv2_tier_business,
        ctaRes = R.string.pricing_cta_business,
        featureRes = listOf(
            R.string.pricing_feature_everything_pro,
            R.string.pricing_feature_admin,
            R.string.pricing_feature_sso,
            R.string.pricing_feature_dedicated_relay,
            R.string.pricing_feature_priority_support,
        ),
    ),
)
