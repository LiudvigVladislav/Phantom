// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.nearby

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NearbyUnavailableTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test fun unavailable_state_does_not_claim_to_scan_or_broadcast() {
        composeTestRule.setContent { NearbyScreen(onNavigate = {}) }
        composeTestRule.onNodeWithText("Local discovery is not available yet").assertExists()
        composeTestRule.onNodeWithText(
            "Bluetooth and Wi-Fi Direct discovery are planned. This device is not scanning or broadcasting.",
        ).assertExists()
        composeTestRule.onAllNodesWithText("Discoverable by others").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("LOOKING FOR PEERS").assertCountEquals(0)
    }
}
