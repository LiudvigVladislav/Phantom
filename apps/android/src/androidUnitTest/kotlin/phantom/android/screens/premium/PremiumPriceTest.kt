// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.premium

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.screens.onboarding.v2.PRICING_TIERS
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PremiumPriceTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun plus_price_matches_onboarding_and_owner_choice() {
        assertEquals("$4.99", PRICING_TIERS.single { it.name == "Plus" }.price)

        composeTestRule.setContent { PremiumScreen(onBack = {}) }
        composeTestRule.onNodeWithText("PLUS").performClick()
        composeTestRule.onNodeWithText("$4.99").assertExists()
        composeTestRule.onNodeWithText("Planned plans and prices. Subscriptions are not available yet.").assertExists()
        composeTestRule.onAllNodesWithText("Cancel any time. No data sold, ever.").assertCountEquals(0)
    }
}
