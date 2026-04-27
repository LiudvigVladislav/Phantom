// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import phantom.android.PhantomApplication
import phantom.core.messaging.OutgoingMessage

/**
 * Handles the "Reply" inline action from a heads-up message notification.
 *
 * Design decisions:
 * - Uses goAsync() so the coroutine can outlive onReceive() while still satisfying the
 *   10-second BroadcastReceiver window enforced by Android.
 * - A new SupervisorJob+IO scope is created per receive rather than reusing a shared scope:
 *   BroadcastReceivers have no lifecycle we can cancel against, and each reply is independent.
 * - On success the notification is cancelled rather than updated with a "Sent" state because
 *   the chat screen already reflects the sent message when the user taps through.
 * - recipientPubKey is carried in the PendingIntent extras (not looked up from DB) to avoid
 *   an extra DB round-trip inside the broadcast window.
 */
class QuickReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Extract the typed text from the RemoteInput bundle — null means the system
        // delivered this intent without a reply payload; drop silently.
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PhantomNotificationManager.KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()
            ?: return

        if (replyText.isEmpty()) return

        val conversationId = intent.getStringExtra(PhantomNotificationManager.EXTRA_CONVERSATION_ID)
            ?: return
        val recipientPubKey = intent.getStringExtra(PhantomNotificationManager.EXTRA_RECIPIENT_PUB_KEY)
            ?: return

        val app = context.applicationContext as PhantomApplication
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Block until libsodium + AppContainer have finished initialising.
                app.ready.await()

                val messagingService = app.container.messagingService
                    ?: return@launch  // No identity yet — cannot send; drop the reply.

                messagingService.sendMessage(
                    OutgoingMessage(
                        id = uuid4().toString(),
                        conversationId = conversationId,
                        recipientPublicKeyHex = recipientPubKey,
                        text = replyText,
                    )
                )

                // Dismiss the notification — the chat screen will show the sent message
                // when the user opens it.
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .cancel(conversationId.hashCode())
            } finally {
                // Must always be called regardless of success or failure to release the
                // BroadcastReceiver process-priority boost.
                pendingResult.finish()
            }
        }
    }
}
