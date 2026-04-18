package phantom.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
 */
object PhantomNotificationManager {

    const val CHANNEL_ID = "phantom_messages"
    const val CHANNEL_NAME = "Messages"

    // Intent extra keys — must match what MainActivity reads
    const val EXTRA_CONVERSATION_ID = "conversationId"
    const val EXTRA_THEIR_USERNAME   = "theirUsername"

    /**
     * Creates the notification channel. Safe to call multiple times — Android is idempotent.
     * Must be called before any [showMessageNotification] call (done in Application.onCreate).
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description     = "Incoming PHANTOM messages"
            enableVibration(true)
            enableLights(true)
            lightColor      = 0xFF00D4FF.toInt() // CyanAccent
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    /**
     * Shows a heads-up notification for an incoming message.
     *
     * Privacy constraints:
     * - [preview] is truncated to 30 characters — never the full plaintext.
     * - No message content is sent to any remote service.
     *
     * On API 33+ silently skips if POST_NOTIFICATIONS permission is not granted
     * (user must grant it via system dialog — we do not re-ask here).
     */
    fun showMessageNotification(
        context: Context,
        conversationId: String,
        senderName: String,
        preview: String,
    ) {
        // Permission guard — POST_NOTIFICATIONS is runtime on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_THEIR_USERNAME, senderName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Truncate to 30 chars — privacy: show only enough to identify the sender's message
        val safePreview = if (preview.length > 30) preview.take(30) + "…" else preview

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(safePreview)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context)
            .notify(conversationId.hashCode(), notification)
    }
}
