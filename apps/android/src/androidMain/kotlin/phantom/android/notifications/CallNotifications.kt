// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import phantom.android.MainActivity
import phantom.android.R
import phantom.android.calls.ActiveCall
import phantom.android.calls.CallState
import phantom.android.locale.AppLanguageStore

internal object CallNotifications {
    const val CHANNEL_ID = "phantom_calls"
    private const val TAG = "incoming_call"
    private const val ID = 1

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val strings = AppLanguageStore.stringsContext(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            existing.name = strings.getString(R.string.notification_calls_channel)
            existing.description = strings.getString(R.string.notification_calls_description)
            manager.createNotificationChannel(existing)
            return
        }
        // This is a new capability, not a reinterpretation of legacy call_alerts.
        // Android owns explicit opt-in, sound and vibration. Recreating this ID
        // must never overwrite the user's channel preferences.
        val channel = NotificationChannel(CHANNEL_ID, strings.getString(R.string.notification_calls_channel),
            NotificationManager.IMPORTANCE_NONE).apply {
            description = strings.getString(R.string.notification_calls_description)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            enableVibration(true)
            setSound(Settings.System.DEFAULT_RINGTONE_URI, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        }
        manager.createNotificationChannel(channel)
    }

    fun enabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)?.importance ?: NotificationManager.IMPORTANCE_NONE) >
            NotificationManager.IMPORTANCE_NONE
    }

    fun openSettings(context: Context) {
        createChannel(context)
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID))
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(TAG, ID)

    fun show(context: Context) {
        if (!enabled(context)) return
        val strings = AppLanguageStore.stringsContext(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(strings.getString(R.string.notification_incoming_call))
            .setContentText(strings.getString(R.string.notification_call_open))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(60_000)
            .build().apply { flags = flags or Notification.FLAG_INSISTENT }
        try {
            NotificationManagerCompat.from(context).notify(TAG, ID, notification)
        } catch (_: SecurityException) {
            // Permission may be revoked between the gate and notify.
        }
    }

    fun observe(context: Context, scope: CoroutineScope, calls: StateFlow<ActiveCall?>): Job = scope.launch {
        var ringingId: String? = null
        try {
            cancel(context)
            calls.collect { call ->
                val next = call?.takeIf { it.state == CallState.RINGING }?.callId
                if (next != ringingId) {
                    cancel(context)
                    if (next != null) show(context)
                    ringingId = next
                }
            }
        } finally {
            cancel(context)
        }
    }
}
