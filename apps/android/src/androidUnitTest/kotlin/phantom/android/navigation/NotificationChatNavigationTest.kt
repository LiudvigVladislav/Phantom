// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import phantom.android.MainActivity
import phantom.android.notifications.PhantomNotificationManager
import phantom.android.notifications.writeUserOptedInToNotificationsBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class NotificationChatNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun intent(id: String = "chat-a", name: String = "Alice") = Intent()
        .putExtra(PhantomNotificationManager.EXTRA_CONVERSATION_ID, id)
        .putExtra(PhantomNotificationManager.EXTRA_THEIR_USERNAME, name)

    @Test fun the_real_warm_intent_callback_accepts_the_publishers_chat_target() {
        val context = compose.activity
        PhantomNotificationManager.createChannel(context)
        writeUserOptedInToNotificationsBlocking(context, true)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.cancelAll()
        PhantomNotificationManager.showMessageNotification(context, "text", "chat-a", "Alice", "Text", "key")
        val tap = shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
        // Exercise the actual callback, not Application/SQLCipher startup or the full app UI.
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.newIntent(tap)
        val pending = ReflectionHelpers.getField<PendingNotificationChat>(controller.get(), "pendingNotificationChat")
        assertEquals(Screen.Chat("chat-a", "Alice"), pending.target)
    }

    @Test fun a_warm_tap_opens_its_chat_once_and_does_not_hijack_later_navigation() {
        val pending = PendingNotificationChat()
        var screen: Screen? by mutableStateOf(Screen.Settings)
        var navigations = 0
        compose.setContent {
            ApplyPendingNotificationChat(pending, true, screen) { screen = it; navigations++ }
        }
        compose.runOnIdle { pending.accept(intent()) }
        compose.waitForIdle()
        assertEquals(Screen.Chat("chat-a", "Alice"), screen)
        assertNull(pending.target)
        assertEquals(1, navigations)
        compose.runOnIdle { screen = Screen.ChatList }
        compose.waitForIdle()
        assertEquals(Screen.ChatList, screen)
        assertEquals(1, navigations)
    }

    @Test fun startup_must_finish_before_a_restored_normal_screen_can_receive_the_tap() {
        val pending = PendingNotificationChat().apply { accept(intent()) }
        var ready by mutableStateOf(false)
        var screen: Screen? by mutableStateOf(Screen.Profile)
        compose.setContent { ApplyPendingNotificationChat(pending, ready, screen) { screen = it } }
        compose.waitForIdle()
        assertEquals(Screen.Profile, screen)
        assertEquals(Screen.Chat("chat-a", "Alice"), pending.target)
        compose.runOnIdle { ready = true }
        compose.waitForIdle()
        assertEquals(Screen.Chat("chat-a", "Alice"), screen)
        assertNull(pending.target)
    }

    @Test fun restricted_routes_keep_the_request_until_the_normal_app_is_available() {
        val pending = PendingNotificationChat().apply { accept(intent()) }
        var screen: Screen? by mutableStateOf(null)
        compose.setContent { ApplyPendingNotificationChat(pending, true, screen) { screen = it } }
        for (restricted in listOf(null, Screen.Onboarding, Screen.Migration,
            Screen.StartupError("test"), Screen.IncomingCall("other", "Other"), Screen.ActiveCall("other", "Other"))) {
            compose.runOnIdle { screen = restricted }
            compose.waitForIdle()
            assertEquals(restricted, screen)
            assertEquals(Screen.Chat("chat-a", "Alice"), pending.target)
        }
        compose.runOnIdle { screen = Screen.ChatList }
        compose.waitForIdle()
        assertEquals(Screen.Chat("chat-a", "Alice"), screen)
    }

    @Test fun a_tap_waiting_behind_app_lock_is_not_lost_when_content_is_absent() {
        val pending = PendingNotificationChat()
        var unlocked by mutableStateOf(false)
        var screen: Screen? by mutableStateOf(Screen.ChatList)
        compose.setContent {
            if (unlocked) ApplyPendingNotificationChat(pending, true, screen) { screen = it }
        }
        compose.runOnIdle { pending.accept(intent()) }
        compose.waitForIdle()
        assertEquals(Screen.ChatList, screen)
        compose.runOnIdle { unlocked = true }
        compose.waitForIdle()
        assertEquals(Screen.Chat("chat-a", "Alice"), screen)
        assertNull(pending.target)
    }

    @Test fun a_waiting_request_survives_saved_state_but_a_consumed_one_does_not_replay() {
        val first = PendingNotificationChat().apply { accept(intent()) }
        val state = Bundle().also(first::save)
        val recreated = PendingNotificationChat().apply { restore(state) }
        assertEquals(first.target, recreated.target)
        recreated.consume(requireNotNull(recreated.target))
        recreated.save(state)
        val next = PendingNotificationChat().apply { restore(state) }
        assertNull(next.target)
    }

    @Test fun latest_tap_wins_and_old_completion_cannot_consume_it() {
        val pending = PendingNotificationChat().apply { accept(intent()) }
        val old = requireNotNull(pending.target)
        pending.accept(intent("chat-b", "Bob"))
        pending.consume(old)
        assertEquals(Screen.Chat("chat-b", "Bob"), pending.target)
    }

    @Test fun a_later_tap_for_the_same_chat_can_be_consumed_again() {
        val pending = PendingNotificationChat().apply { accept(intent()) }
        val target = requireNotNull(pending.target)
        pending.consume(target)
        pending.accept(intent())
        assertEquals(target, pending.target)
    }

    @Test fun unrelated_or_incomplete_intents_do_not_erase_a_pending_chat() {
        val pending = PendingNotificationChat().apply { accept(intent()) }
        for (invalid in listOf(Intent(), intent("", "Alice"), intent("chat-b", ""),
            Intent().putExtra(PhantomNotificationManager.EXTRA_CONVERSATION_ID, "chat-b"))) {
            pending.accept(invalid)
            assertEquals(Screen.Chat("chat-a", "Alice"), pending.target)
        }
    }
}
