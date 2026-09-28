// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import phantom.android.parentScreenOf
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileReturnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun profileReturnsToEachEntryScreen() {
        for (origin in listOf(Screen.Settings, Screen.ChatList, Screen.Calls)) {
            assertEquals(origin, parentScreenOf(Screen.Profile, origin))
        }
        assertEquals(Screen.ChatList, parentScreenOf(Screen.Profile, null))
        assertEquals(Screen.ChatList, parentScreenOf(Screen.Profile, Screen.Profile))
        assertEquals(Screen.Settings, parentScreenOf(Screen.PrivacyModeDetail, Screen.Calls))
    }

    @Test fun originSurvivesRecreationAlongsideProfileRoute() {
        val restoration = StateRestorationTester(compose)
        var open: (() -> Unit)? = null
        restoration.setContent {
            var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen?>(Screen.Settings) }
            var origin by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen?>(Screen.ChatList) }
            open = { origin = screen; screen = Screen.Profile }
            Text("${screen == Screen.Profile}:${parentScreenOf(screen, origin) == Screen.Settings}")
        }
        compose.runOnIdle { open!!() }
        compose.onNodeWithText("true:true").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("true:true").assertExists()
    }
}
