// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import phantom.android.notifications.PhantomNotificationManager

/** Activity-owned warm-intent request, retained while the app lock hides its content. */
internal class PendingNotificationChat {
    var target: Screen.Chat? by mutableStateOf(null)
        private set

    fun accept(intent: Intent) {
        val id = intent.getStringExtra(PhantomNotificationManager.EXTRA_CONVERSATION_ID)
        val sender = intent.getStringExtra(PhantomNotificationManager.EXTRA_THEIR_USERNAME)
        if (!id.isNullOrBlank() && !sender.isNullOrBlank()) target = Screen.Chat(id, sender)
    }

    fun consume(expected: Screen.Chat) {
        if (target == expected) target = null
    }

    fun save(outState: Bundle) {
        outState.remove(STATE_KEY)
        target?.let { chat ->
            outState.putBundle(STATE_KEY, Bundle().apply {
                putString(PhantomNotificationManager.EXTRA_CONVERSATION_ID, chat.conversationId)
                putString(PhantomNotificationManager.EXTRA_THEIR_USERNAME, chat.theirUsername)
            })
        }
    }

    fun restore(savedState: Bundle?) {
        target = null
        savedState?.getBundle(STATE_KEY)?.let { accept(Intent().putExtras(it)) }
    }

    private companion object {
        const val STATE_KEY = "phantom.pending_notification_chat"
    }
}

/** Called only inside unlocked app content; startup and restricted routes still take precedence. */
@Composable
internal fun ApplyPendingNotificationChat(
    pending: PendingNotificationChat,
    startupReady: Boolean,
    currentScreen: Screen?,
    navigate: (Screen.Chat) -> Unit,
) {
    val request = pending.target
    LaunchedEffect(request, startupReady, currentScreen) {
        if (request != null && startupReady && currentScreen != null &&
            currentScreen !is Screen.Onboarding && currentScreen !is Screen.Migration &&
            currentScreen !is Screen.StartupError && currentScreen !is Screen.IncomingCall &&
            currentScreen !is Screen.ActiveCall
        ) {
            navigate(request)
            pending.consume(request)
        }
    }
}
