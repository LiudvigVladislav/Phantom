// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.premium

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.R
import phantom.android.screens.onboarding.v2.PRICING_TIERS
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PremiumPriceTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun ghost_explanation_matches_the_onboarding_contract() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pro = PRICING_TIERS.single { it.nameRes == R.string.pricing_name_pro }
        composeTestRule.setContent { PremiumScreen(onBack = {}) }
        composeTestRule.onNodeWithText(context.getString(pro.calloutBodyRes!!))
            .assertExists()
        composeTestRule.onAllNodesWithText(
            "Become completely invisible on PHANTOM. Receive-only mode disables read receipts, presence, and discovery.",
        ).assertCountEquals(0)
    }

    @Test
    fun back_control_has_an_accessible_action() {
        var returned = false
        composeTestRule.setContent { PremiumScreen(onBack = { returned = true }) }
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(returned)
    }

    @Test
    fun plus_price_matches_onboarding_and_owner_choice() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val plus = PRICING_TIERS.single { it.nameRes == R.string.pricing_name_plus }
        assertEquals("$4.99", context.getString(plus.priceRes))

        composeTestRule.setContent { PremiumScreen(onBack = {}) }
        composeTestRule.onNodeWithText("PLUS").performClick()
        composeTestRule.onNodeWithText("$4.99").assertExists()
        composeTestRule.onNodeWithText("Planned plans and prices. Subscriptions are not available yet.").assertExists()
        composeTestRule.onAllNodesWithText("Cancel any time. No data sold, ever.").assertCountEquals(0)
    }

    @Test
    fun paid_plan_buttons_only_show_the_unavailable_notice() {
        var returned = false
        composeTestRule.setContent { PremiumScreen(onBack = { returned = true }) }
        for (tier in listOf("Plus", "Pro")) {
            composeTestRule.onNodeWithText(tier.uppercase()).performScrollTo().performClick()
            composeTestRule.onNodeWithText("$tier coming soon").performScrollTo().performClick()
            composeTestRule.onNodeWithText("Subscriptions are not available yet").assertExists()
            assertFalse(returned)
        }
    }

    @Test
    fun free_tab_keeps_its_own_content_after_selecting_a_paid_preview() {
        composeTestRule.setContent { PremiumScreen(onBack = {}) }
        composeTestRule.onNodeWithText("PLUS").performClick()
        composeTestRule.onNodeWithText("FREE").performClick()
        composeTestRule.onNodeWithText("Current plan").assertExists()
        composeTestRule.onNodeWithText("The foundation.").assertExists()
        composeTestRule.onAllNodesWithText("Plus coming soon").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("GHOST MODE").assertCountEquals(0)
    }
}
