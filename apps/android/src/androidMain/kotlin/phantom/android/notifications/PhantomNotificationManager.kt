// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import phantom.android.MainActivity

/**
 * Local-only notification manager for PHANTOM — no Firebase, no FCM.
 *
 * Design decisions:
 * - CHANNEL_ID "phantom_messages" with IMPORTANCE_HIGH for heads-up display.
 * - Preview truncated to 30 chars (privacy — full plaintext never leaves the process).
 * - Notification ID derived from conversationId.hashCode() so per-conversation
 *   updates replace rather than accumulate notifications.
 * - PendingIntent carries conversationId + senderName so MainActivity can navigate
 *   directly to the correct chat without an extra DB lookup.
 *
 * PR-NOTIF-DIAG (2026-05-22): structured `PhantomNotif`-tagged logs added on every
 * step of the show path (entry, api_level, permission_check, channel_check,
 * notify_called, notify_returned, skip reasons) + on channel creation. No
 * behaviour change — observability only. See `docs/tracks/notifications-diag.md`.
 */
object PhantomNotificationManager {

    /**
     * PR-NOTIF-DIAG (2026-05-22) — sole logcat tag for the notification show path.
     * Combine with `PhantomMessaging:V` to see the full chain from
     * DefaultMessagingService's `NOTIF invoke_*` lines through to
     * `NOTIF notify_returned` here.
     */
    private const val LOG_TAG = "PhantomNotif"

    const val CHANNEL_ID = "phantom_messages"
    const val CHANNEL_NAME = "Messages"

    // Intent extra keys — must match what MainActivity reads
    const val EXTRA_CONVERSATION_ID  = "conversationId"
    const val EXTRA_THEIR_USERNAME   = "theirUsername"

    // Quick-reply extras — used by QuickReplyReceiver
    const val KEY_REPLY_TEXT         = "reply_text"
    const val EXTRA_RECIPIENT_PUB_KEY = "their_pub_key"

    /**
     * Creates the notification channel. Safe to call multiple times — Android is idempotent.
     * Must be called before any [showMessageNotification] call (done in Application.onCreate).
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(LOG_TAG, "NOTIF channel_create_skipped reason=pre_o sdk=${Build.VERSION.SDK_INT}")
            return
        }
        Log.i(LOG_TAG, "NOTIF channel_create_attempt sdk=${Build.VERSION.SDK_INT} channelId=$CHANNEL_ID")
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            importance,
        ).apply {
            description     = "Incoming PHANTOM messages"
            enableVibration(true)
            enableLights(true)
            lightColor      = 0xFF00D4FF.toInt() // CyanAccent
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        Log.i(LOG_TAG, "NOTIF channel_created channelId=$CHANNEL_ID importance=$importance")
    }

    /**
     * Shows a heads-up notification for an incoming message.
     *
     * Privacy constraints:
     * - [preview] is truncated to 30 characters — never the full plaintext.
     * - No message content is sent to any remote service.
     * - Logs only `.take(8)` truncations of conversationId / senderPubKeyHex,
     *   length-only of preview, and the Android-side notification id. No
     *   plaintext, no ciphertext, no full hex.
     *
     * On API 33+ silently skips if POST_NOTIFICATIONS permission is not granted
     * (user must grant it via system dialog — we do not re-ask here). PR-NOTIF-DIAG
     * leaves this silent-return behaviour unchanged; only the log is new.
     *
     * @param source PR-NOTIF-DIAG `source` enum value passed through from
     *   `DefaultMessagingService` — one of `text`, `voice_v1_assembled`,
     *   `voice_v1_chunk`, `voice_v2_manifest`. Logged for diagnostic
     *   attribution; not used for any routing decision.
     */
    fun showMessageNotification(
        context: Context,
        source: String,
        conversationId: String,
        senderName: String,
        preview: String,
        recipientPubKey: String,
    ) {
        // PR-NOTIF-DIAG: notification id is conversationId.hashCode() per the
        // existing design. Logged so logcat reveals collision-perezatiranie
        // (if the same conversation produces multiple notifications, Android
        // updates the row rather than stacking — diagnostic, not a bug here).
        val notificationId = conversationId.hashCode()
        val notificationTag: String? = null // existing call uses notify(int, Notification) overload
        Log.i(
            LOG_TAG,
            "NOTIF show_entry source=$source conv=${conversationId.take(8)} " +
                "id=$notificationId senderHash=${recipientPubKey.take(8)} previewLen=${preview.length}",
        )

        Log.i(LOG_TAG, "NOTIF api_level sdk=${Build.VERSION.SDK_INT}")

        // Permission guard — POST_NOTIFICATIONS is runtime on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            Log.i(LOG_TAG, "NOTIF permission_check granted=$granted")
            if (!granted) {
                Log.i(
                    LOG_TAG,
                    "NOTIF skip reason=permission_denied conv=${conversationId.take(8)} id=$notificationId",
                )
                return
            }
        }

        // Channel-level + app-level enable state. Logged so a flaky run can be
        // attributed unambiguously to "user disabled this channel" or "user
        // disabled the whole app's notifications" without guessing. We do
        // NOT bypass the `notify()` call when these are false — Android will
        // drop the notification on its own. Logging the drop is the goal.
        val nmc = NotificationManagerCompat.from(context)
        val appEnabled = nmc.areNotificationsEnabled()
        val channelImportance: Int
        val channelEnabled: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nmc.getNotificationChannel(CHANNEL_ID)
            channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE
            channelEnabled = channel != null && channelImportance != NotificationManager.IMPORTANCE_NONE
        } else {
            // Pre-O has no channels; app-level enable is the only gate.
            channelImportance = -1
            channelEnabled = appEnabled
        }
        Log.i(
            LOG_TAG,
            "NOTIF channel_check app_enabled=$appEnabled channel_enabled=$channelEnabled " +
                "channel_importance=$channelImportance channel_id=$CHANNEL_ID",
        )
        if (!appEnabled) {
            Log.i(
                LOG_TAG,
                "NOTIF skip reason=notifications_disabled conv=${conversationId.take(8)} id=$notificationId",
            )
            // Continue to notify() anyway — observability over bypass; Android will drop.
        } else if (!channelEnabled) {
            Log.i(
                LOG_TAG,
                "NOTIF skip reason=channel_disabled conv=${conversationId.take(8)} id=$notificationId",
            )
            // Continue to notify() anyway.
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_THEIR_USERNAME, senderName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Truncate to 30 chars — privacy: show only enough to identify the sender's message
        val safePreview = if (preview.length > 30) preview.take(30) + "…" else preview

        // Inline reply action — lets users respond without opening the app
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply…")
            .build()

        val replyIntent = Intent(context, QuickReplyReceiver::class.java).apply {
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_RECIPIENT_PUB_KEY, recipientPubKey)
        }
        // FLAG_MUTABLE is required so the system can write the RemoteInput result into the Intent.
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent,
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(safePreview)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .build()

        Log.i(
            LOG_TAG,
            "NOTIF notify_called id=$notificationId tag=$notificationTag conv=${conversationId.take(8)}",
        )
        nmc.notify(notificationId, notification)
        // If notify() throws, this next line never fires and the existing
        // `runCatching` at the AppContainer wire site surfaces the failure via
        // `NOTIF callback_threw`. If notify() returns normally, this line is
        // the strongest evidence Android accepted the call.
        Log.i(
            LOG_TAG,
            "NOTIF notify_returned id=$notificationId tag=$notificationTag conv=${conversationId.take(8)}",
        )
    }
}
