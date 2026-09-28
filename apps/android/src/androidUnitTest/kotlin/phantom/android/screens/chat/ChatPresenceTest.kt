// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.android.screens.chat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ChatPresenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(typing: Boolean) = compose.setContent {
        ChatTopBar(theirUsername = "test-peer", isTyping = typing, onBack = {},
            onContactProfile = {}, onVoiceCall = {}, onMoreMenu = {}, showMenu = false,
            onDismissMenu = {}, onReport = {}, onBlock = {}, onDisappearingTimer = {})
    }

    @Test fun header_shows_handle_not_unverified_online_presence() {
        show(false)
        compose.onNodeWithText("@test-peer").assertExists()
        compose.onNodeWithText("online", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test fun authenticated_typing_signal_is_not_replaced_by_a_handle() {
        show(true)
        compose.onNodeWithText("@test-peer").assertDoesNotExist()
        compose.onNodeWithText("typing", substring = true, ignoreCase = true).assertExists()
    }
}
