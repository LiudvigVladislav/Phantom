// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.settings

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import phantom.android.notifications.PhantomNotificationManager
import phantom.android.notifications.isEffectiveNotificationsEnabled
import phantom.android.notifications.readCurrentNotificationsGate
import phantom.android.notifications.readUserOptedInToNotifications
import phantom.android.notifications.writeUserOptedInToNotificationsBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class MessageAlertsSettingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun prepare() {
        context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        PhantomNotificationManager.createChannel(context)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.cancelAll()
    }

    private fun click() = compose.onNodeWithText("Message Alerts").performClick()
    private fun toggle() = compose.onNode(isToggleable())
    private fun awaitToggle(value: ToggleableState) {
        compose.waitUntil(5_000) {
            toggle().fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == value
        }
    }
    private fun publish() = PhantomNotificationManager.showMessageNotification(
        context, "text", "notification-test", "Sender", "Message", "test-key",
    )

    @Test fun a_legacy_enabled_switch_does_not_claim_publisher_opt_in() {
        context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("message_alerts", true).commit()
        compose.setContent { MessageAlertsSetting(onError = { error("unexpected failure") }) }
        toggle().assertIsOff()
        assertFalse(readUserOptedInToNotifications(context))
        publish()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun explicit_opt_in_enables_the_real_publisher_and_opt_out_disables_it() {
        compose.setContent { MessageAlertsSetting(onError = { error("unexpected failure") }) }
        click()
        compose.waitUntil(5_000) { readUserOptedInToNotifications(context) }
        awaitToggle(ToggleableState.On)
        toggle().assertIsOn()
        publish()
        assertEquals(1, manager.activeNotifications.size)
        click()
        compose.waitUntil(5_000) { !readUserOptedInToNotifications(context) }
        awaitToggle(ToggleableState.Off)
        toggle().assertIsOff()
        manager.cancelAll()
        publish()
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE).contains("message_alerts"))
    }

    @Test fun a_failed_write_reports_failure_without_claiming_enabled() {
        var errors = 0
        compose.setContent { MessageAlertsSetting(onError = { errors++ }, writeOptIn = { _, _ -> false }) }
        click()
        compose.waitUntil(5_000) { errors == 1 }
        toggle().assertIsOff()
        assertFalse(isEffectiveNotificationsEnabled(readCurrentNotificationsGate(context)))
    }

    @Test fun another_tap_during_a_write_does_not_issue_another_write() {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        compose.setContent {
            MessageAlertsSetting(onError = {}, writeOptIn = { _, _ ->
                calls++
                release.await()
                false
            })
        }
        try {
            click()
            compose.waitUntil(5_000) { calls == 1 }
            click()
            compose.waitForIdle()
            assertEquals(1, calls)
        } finally {
            release.complete(Unit)
            compose.waitForIdle()
        }
    }

    @Test fun a_blocked_channel_opens_channel_settings_without_opting_in() {
        val channel = manager.getNotificationChannel(PhantomNotificationManager.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        compose.setContent { MessageAlertsSetting(onError = { error("unexpected failure") }) }
        toggle().assertIsOff()
        click()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(PhantomNotificationManager.CHANNEL_ID, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertFalse(readUserOptedInToNotifications(context))
    }

    @Test fun an_os_block_opens_app_settings_and_does_not_change_the_opt_in() {
        writeUserOptedInToNotificationsBlocking(context, true)
        shadowOf(manager).setNotificationsEnabled(false)
        compose.setContent { MessageAlertsSetting(onError = { error("unexpected failure") }) }
        toggle().assertIsOff()
        click()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(context).nextStartedActivity.action)
        assertTrue(readUserOptedInToNotifications(context))
    }

    @Test fun returning_from_android_settings_refreshes_the_effective_toggle() {
        writeUserOptedInToNotificationsBlocking(context, true)
        shadowOf(manager).setNotificationsEnabled(false)
        compose.setContent { MessageAlertsSetting(onError = { error("unexpected failure") }) }
        toggle().assertIsOff()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        shadowOf(manager).setNotificationsEnabled(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        toggle().assertIsOn()
    }

    private class PermissionRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var request: Int? = null
        var permission: Any? = null
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            request = requestCode
            permission = input
        }
    }

    @Test @Config(sdk = [35])
    fun denying_the_runtime_dialog_does_not_opt_in() {
        val registry = PermissionRegistry()
        shadowOf(context.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                MessageAlertsSetting(onError = { error("unexpected failure") })
            }
        }
        click()
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, registry.permission)
        compose.runOnIdle { registry.dispatchResult(requireNotNull(registry.request), false) }
        toggle().assertIsOff()
        assertFalse(readUserOptedInToNotifications(context))
    }

    @Test @Config(sdk = [35])
    fun granting_the_requested_permission_persists_opt_in_and_enables_the_gate() {
        val registry = PermissionRegistry()
        shadowOf(context.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                MessageAlertsSetting(onError = { error("unexpected failure") })
            }
        }
        click()
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, registry.permission)
        compose.runOnIdle {
            shadowOf(context.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            registry.dispatchResult(requireNotNull(registry.request), true)
        }
        compose.waitUntil(5_000) { readUserOptedInToNotifications(context) }
        awaitToggle(ToggleableState.On)
        toggle().assertIsOn()
        assertTrue(isEffectiveNotificationsEnabled(readCurrentNotificationsGate(context)))
    }
}
