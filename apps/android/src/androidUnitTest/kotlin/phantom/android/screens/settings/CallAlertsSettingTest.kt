// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.android.screens.settings

import android.app.Application
import android.app.NotificationManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import phantom.android.notifications.CallNotifications
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CallAlertsSettingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    @Before fun prepare() {
        CallNotifications.createChannel(context)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @Test fun settings_opens_call_channel_and_reflects_os_choice_on_resume() {
        compose.setContent { CallAlertsSetting(onError = { error("could not open settings") }) }
        compose.onNodeWithText("Off").assertExists()
        compose.onNodeWithText("Call Alerts").performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(CallNotifications.CHANNEL_ID, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        manager.getNotificationChannel(CallNotifications.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_HIGH
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("On").assertExists()
    }
}
