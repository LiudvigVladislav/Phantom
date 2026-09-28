// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.android.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import phantom.android.calls.ActiveCall
import phantom.android.calls.CallState
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CallNotificationsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun prepare() {
        shadowOf(manager).setNotificationsEnabled(true)
        manager.cancelAll()
        CallNotifications.createChannel(context)
    }
    @After fun stop() { scope.cancel() }

    private fun enable() {
        // Test fixture models an OS user choice, not an application upgrade.
        manager.getNotificationChannel(CallNotifications.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_HIGH
    }
    private fun call(state: CallState = CallState.RINGING) = ActiveCall("private-id", "secret-key", "private-name", state)

    @Test fun new_channel_requires_explicit_choice_and_does_not_reuse_legacy_preferences() {
        context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("call_alerts", true).commit()
        assertFalse(CallNotifications.enabled(context))
        CallNotifications.show(context)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun incoming_call_uses_separate_private_channel_and_clears_on_answer() {
        enable()
        assertTrue(CallNotifications.enabled(context))
        val calls = MutableStateFlow<ActiveCall?>(null)
        CallNotifications.observe(context, scope, calls)
        calls.value = call()
        val notification = manager.activeNotifications.single().notification
        assertEquals(CallNotifications.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertEquals(60_000L, notification.timeoutAfter)
        assertEquals("Incoming call", notification.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(notification.extras.toString().contains("private-name"))
        assertFalse(notification.extras.toString().contains("secret-key"))
        calls.value = call(CallState.IN_CALL)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun rejection_hangup_and_scope_teardown_cancel_alert() {
        enable()
        val calls = MutableStateFlow<ActiveCall?>(call())
        val job = CallNotifications.observe(context, scope, calls)
        assertEquals(1, manager.activeNotifications.size)
        calls.value = call(CallState.REJECTED)
        assertTrue(manager.activeNotifications.isEmpty())
        calls.value = call()
        calls.value = call(CallState.ENDED)
        assertTrue(manager.activeNotifications.isEmpty())
        calls.value = call()
        job.cancel()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun app_mute_blocks_calls_without_changing_message_opt_in() {
        enable()
        assertFalse(readUserOptedInToNotifications(context))
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(CallNotifications.enabled(context))
        CallNotifications.show(context)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test @Config(sdk = [33]) fun denied_runtime_permission_blocks_calls() {
        enable()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(CallNotifications.enabled(context))
        CallNotifications.show(context)
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(CallNotifications.enabled(context))
    }

    @Test fun channel_refresh_preserves_sound_and_user_importance() {
        enable()
        val before = manager.getNotificationChannel(CallNotifications.CHANNEL_ID)
        CallNotifications.createChannel(context)
        val after = manager.getNotificationChannel(CallNotifications.CHANNEL_ID)
        assertEquals(before.importance, after.importance)
        assertEquals(before.sound, after.sound)
    }

    @Test @Config(qualifiers = "ru") fun russian_channel_and_alert_are_localized() {
        enable()
        CallNotifications.createChannel(context)
        assertEquals("Входящие звонки", manager.getNotificationChannel(CallNotifications.CHANNEL_ID).name.toString())
        CallNotifications.show(context)
        assertEquals("Входящий звонок", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE))
    }
}
