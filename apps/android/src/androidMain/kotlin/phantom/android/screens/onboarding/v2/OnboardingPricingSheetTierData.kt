// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

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
    val name: String,
    val price: String,
    val sub: String,
    val iconRes: Int,
    val cta: String,
    val recommended: Boolean = false,
    val calloutTitle: String? = null,
    val calloutBody: String? = null,
    val features: List<String>,
)

internal val PRICING_TIERS: List<PricingTierV2> = listOf(
    PricingTierV2(
        name = "Plus",
        price = "$3.99",
        sub = "More control.",
        iconRes = R.drawable.ic_dv2_tier_plus,
        cta = "Upgrade to Plus",
        features = listOf(
            "Everything in Free",
            "Larger groups up to 64 members",
            "Disappearing messages up to 1 year",
            "Custom relay support",
            "Priority message routing",
            "Premium typeface (PP Neue Montreal)",
        ),
    ),
    PricingTierV2(
        name = "Pro",
        price = "$9.99",
        sub = "Full control.",
        iconRes = R.drawable.ic_dv2_tier_pro,
        cta = "Upgrade to Pro",
        recommended = true,
        calloutTitle = "GHOST MODE",
        calloutBody = "Route every message through the Tor network with no silent downgrade to WSS or REALITY. Additional invisibility controls arrive in a future Pro release.",
        features = listOf(
            "Everything in Plus",
            "Berkeley Mono for fingerprints & keys",
            "Stealth routing & decoy traffic",
            "Tor-bridge fallback",
            "Self-hosted relay support",
            "Advanced sealed sender",
        ),
    ),
    PricingTierV2(
        name = "Business",
        price = "$19.99",
        sub = "For teams & organizations.",
        iconRes = R.drawable.ic_dv2_tier_business,
        cta = "Upgrade to Business",
        features = listOf(
            "Everything in Pro",
            "Centralized admin console",
            "SSO & audit logs",
            "Dedicated relay cluster",
            "Priority support",
        ),
    ),
)
