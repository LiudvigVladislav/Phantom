package phantom.android.notifications

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM service — wakes the device when a message arrives while the WebSocket is offline.
 *
 * Design decisions:
 * - FCM carries ONLY a silent data push (no notification payload).
 *   All notification content is assembled locally from the message pulled via WebSocket,
 *   so FCM never sees plaintext and the relay never forwards unencrypted previews.
 * - onNewToken stores the token in SharedPreferences only.
 *   Relay registration (/register-fcm) is a TODO for a future vertical slice once
 *   the relay authentication layer is finalised.
 * - onMessageReceived shows a local "tap to open" notification immediately and
 *   relies on the Foreground Service / WebSocket drain to deliver the actual content.
 *
 * Activation checklist:
 *  1. Place google-services.json in apps/android/
 *  2. Uncomment id("com.google.gms.google-services") in apps/android/build.gradle.kts
 *  3. Uncomment the root google-services line in the root build.gradle.kts
 */
class PhantomFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called when FCM issues a new registration token (first install or token refresh).
     *
     * The token is persisted locally under key "fcm_token".
     * TODO: POST token to relay /register-fcm endpoint so the relay can address
     *       push notifications to this specific device installation.
     *       This requires the authenticated relay session — wire in AppContainer
     *       after initMessaging() completes.
     */
    override fun onNewToken(token: String) {
        applicationContext
            .getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }

    /**
     * Called when a data push arrives while the app is in foreground or background
     * (but NOT killed — that is handled by the system notification tray).
     *
     * Expected data keys sent by the relay (all optional for forward-compatibility):
     *   "conversationId" — opaque ID used to route the tap into the correct chat
     *   "senderName"     — display name for the heads-up title (may be empty)
     *   "preview"        — short teaser text; relay must never include plaintext here
     *
     * Privacy: the relay sends only a wake signal. If conversationId / senderName
     * are absent we fall back to generic copy so the relay can omit them entirely.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val convId   = message.data["conversationId"] ?: ""
        val sender   = message.data["senderName"]     ?: "PHANTOM"
        val preview  = message.data["preview"]        ?: "New message"

        scope.launch {
            runCatching {
                PhantomNotificationManager.showMessageNotification(
                    context         = applicationContext,
                    conversationId  = convId,
                    senderName      = sender,
                    preview         = preview,
                    recipientPubKey = "",   // not known at push time; full data comes via WebSocket
                )
            }
            // The Foreground Service's WebSocket will drain any queued envelopes
            // automatically once the device is awake — no explicit trigger needed here.
        }
    }
}
