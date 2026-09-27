// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.premium

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.R
import phantom.android.screens.onboarding.v2.PRICING_TIERS
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class PricingResourceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun catalog_keeps_the_approved_planned_prices_and_order() {
        assertEquals(listOf("Plus", "Pro", "Business"), PRICING_TIERS.map { context.getString(it.nameRes) })
        assertEquals(listOf("$4.99", "$9.99", "$19.99"), PRICING_TIERS.map { context.getString(it.priceRes) })
        assertEquals(listOf(false, true, false), PRICING_TIERS.map { it.recommended })
        assertEquals(listOf(6, 6, 5), PRICING_TIERS.map { it.featureRes.size })
    }

    @Test
    fun every_catalog_label_resolves_and_preserves_ampersands() {
        for (tier in PRICING_TIERS) {
            val labels = listOf(tier.nameRes, tier.priceRes, tier.subRes, tier.ctaRes) +
                listOfNotNull(tier.calloutTitleRes, tier.calloutBodyRes) + tier.featureRes
            labels.forEach { assertTrue(context.getString(it).isNotBlank()) }
        }
        assertEquals("For teams & organizations.", context.getString(R.string.pricing_tagline_business))
        assertEquals("SSO & audit logs", context.getString(R.string.pricing_feature_sso))
        assertEquals("Berkeley Mono for fingerprints & keys", context.getString(R.string.pricing_feature_mono_typeface))
    }

    @Test
    fun ghost_uses_the_shared_routing_description_not_a_receive_only_promise() {
        val pro = PRICING_TIERS.single { it.nameRes == R.string.pricing_name_pro }
        assertEquals(R.string.pricing_ghost_body, pro.calloutBodyRes)
        assertEquals(
            "Route every message through the Tor network with no silent downgrade to WSS or REALITY. " +
                "Additional invisibility controls arrive in a future Pro release.",
            context.getString(R.string.pricing_ghost_body),
        )
        assertEquals("Subscriptions are not available yet", context.getString(R.string.pricing_unavailable))
    }
}
