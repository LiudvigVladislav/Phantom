// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import phantom.android.notifications.PhantomNotificationManager
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.service.PhantomMessagingService
import phantom.android.screens.splash.PhantomSplashScreen
import phantom.android.navigation.Screen
import phantom.android.qr.QrScanScreen
import phantom.android.calls.ActiveCall
import phantom.android.calls.CallState
import phantom.android.screens.calls.ActiveCallScreen
import phantom.android.screens.calls.CallsScreen
import phantom.android.screens.calls.IncomingCallScreen
import phantom.android.screens.chat.ChatScreen
import phantom.android.screens.chatlist.ChatListScreen
import phantom.android.screens.contact.ContactProfileScreen
import phantom.android.screens.lock.AppLockScreen
import phantom.android.screens.onboarding.OnboardingScreen
import phantom.android.screens.profile.ProfileScreen
import phantom.android.screens.requests.MessageRequestsScreen
import phantom.android.screens.saved.SavedMessagesScreen
import phantom.android.screens.archive.ArchiveScreen
import phantom.android.screens.channel.CreateChannelScreen
import phantom.android.screens.group.CreateGroupScreen
import phantom.android.screens.group.GroupChatScreen
import phantom.android.screens.settings.SettingsScreen
import phantom.android.ui.theme.*

class MainActivity : ComponentActivity() {

    /**
     * Parses a `phantom://invite/{base64url(username:pubkeyHex)}` URI from an incoming Intent.
     * Returns the decoded payload as-is (`"username:pubkeyHex"`) — the same format the QR
     * scanner produces — so it can be fed directly into [scannedQrValue] / [AddContactDialog].
     */
    private fun parseInviteIntent(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "phantom" || uri.host != "invite") return null
        val encoded = uri.lastPathSegment ?: return null
        return try {
            val decoded = String(Base64.decode(encoded, Base64.URL_SAFE), Charsets.UTF_8)
            // Require at least one colon separating username from pubkey
            if (decoded.indexOf(':') < 0) null else decoded
        } catch (e: Exception) {
            Log.w("PHANTOM", "parseInviteIntent: malformed payload — ${e.message}")
            null
        }
    }

    // Mutable state hoisted to Activity level so onNewIntent can update Compose state.
    private val pendingInviteQr = androidx.compose.runtime.mutableStateOf<String?>(null)

    // App Lock — hoisted to Activity so onResume can trigger re-lock after background timeout.
    // Initialised to false; set to true in onCreate when the pref is enabled.
    private val isLockedState = androidx.compose.runtime.mutableStateOf(false)
    private var backgroundedAt: Long = 0

    override fun onPause() {
        super.onPause()
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("app_lock_enabled", false)) {
            val elapsed = System.currentTimeMillis() - backgroundedAt
            if (backgroundedAt > 0 && elapsed > 60_000) {
                isLockedState.value = true
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseInviteIntent(intent)?.let { payload ->
            pendingInviteQr.value = payload
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("PHANTOM_INIT", "MainActivity onCreate")
        // Block screenshots, screen recording, and the recents-thumbnail preview.
        // FLAG_SECURE on the only Activity in the app is sufficient — there are
        // no other Activity classes (one ComponentActivity, all screens are
        // Compose). Windows that don't belong to MainActivity (system dialogs,
        // BiometricPrompt, IME, OS notifications) are governed by the OS, not us.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        // Start the foreground service that owns the WebSocket connection lifetime.
        // The service awaits app.ready internally, so it is safe to launch before init completes.
        startForegroundService(Intent(this, PhantomMessagingService::class.java))
        val app = application as PhantomApplication
        // Read notification extras once — intent is immutable after activity creation.
        val notifConversationId = intent.getStringExtra(PhantomNotificationManager.EXTRA_CONVERSATION_ID)
        val notifSenderName     = intent.getStringExtra(PhantomNotificationManager.EXTRA_THEIR_USERNAME)
        // Parse invite deep link from cold-start intent (warm-start handled by onNewIntent).
        parseInviteIntent(intent)?.let { payload ->
            pendingInviteQr.value = payload
        }
        // Edge-to-edge configuration.
        //
        // API 35+: the system enforces edge-to-edge automatically; calling
        // enableEdgeToEdge() ourselves there corrupts the EGL surface setup
        // (observed: GFXSTREAM / Unknown dataspace 0).
        //
        // API 26–34: we have to opt in. We previously tried just
        // `WindowCompat.setDecorFitsSystemWindows(window, false)` — that drew
        // under the bars but left IME inset reporting unreliable on some OEM
        // builds (Tecno HiOS / Android 12 in QA-v9: `Modifier.imePadding()`
        // resolved to 0 with the keyboard open, so the chat input slid under
        // the keyboard). enableEdgeToEdge() is the AndroidX-managed wrapper
        // that combines decor-fits-system-windows + the OnApplyWindowInsets
        // listener Compose needs for `WindowInsets.ime` and
        // `WindowInsets.navigationBars` to be populated.
        if (Build.VERSION.SDK_INT < 35) {
            enableEdgeToEdge()
        }

        // Initialise lock state from prefs before Compose renders its first frame.
        val prefs = getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("app_lock_enabled", false)) {
            isLockedState.value = true
        }

        setContent {
            PhantomTheme {
                // Observe the activity-level lock flag inside Compose.
                val isLocked by isLockedState

                if (isLocked) {
                    AppLockScreen(onUnlocked = { isLockedState.value = false })
                } else {
                    var container by remember { mutableStateOf<AppContainer?>(null) }
                    var initError by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        Log.d("PHANTOM_INIT", "MainActivity: awaiting ready…")
                        runCatching { app.ready.await() }
                            .onSuccess {
                                Log.d("PHANTOM_INIT", "MainActivity: ready — rendering app")
                                container = app.container
                            }
                            .onFailure { t ->
                                Log.e("PHANTOM_INIT", "MainActivity: init failed: ${t.message}", t)
                                initError = when (t) {
                                    is SecurityException ->
                                        "Encryption keys could not be unlocked.\nTry restarting the app."
                                    is android.database.sqlite.SQLiteException ->
                                        "Database error. Please reinstall the app."
                                    else ->
                                        "Startup failed. Please restart."
                                }
                            }
                    }

                    val c = container
                    when {
                        c != null -> PhantomApp(
                            container              = c,
                            notifConversationId    = notifConversationId,
                            notifSenderName        = notifSenderName,
                            pendingInviteQr        = pendingInviteQr,
                        )

                        initError != null -> Box(
                            modifier = Modifier.fillMaxSize().background(BgDeep).padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Startup error:\n\n$initError",
                                color = Danger,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp,
                            )
                        }

                        else -> PhantomSplashScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun PhantomApp(
    container: AppContainer,
    notifConversationId: String? = null,
    notifSenderName: String? = null,
    pendingInviteQr: androidx.compose.runtime.MutableState<String?> = androidx.compose.runtime.mutableStateOf(null),
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var startScreen by remember { mutableStateOf<Screen?>(null) }

    LaunchedEffect(Unit) {
        val identity = container.identityRepo.loadIdentity()
        startScreen = if (identity == null) Screen.Onboarding else Screen.ChatList
    }

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Onboarding) }
    // scannedQrValue carries both QR-scanner results and decoded invite deep links —
    // both resolve to the same "username:pubkeyHex" format consumed by AddContactDialog.
    var scannedQrValue by remember { mutableStateOf<String?>(null) }

    // Drain any invite deep link that arrived before Compose was ready (cold-start)
    // or while the app was running (onNewIntent forwards to pendingInviteQr).
    LaunchedEffect(pendingInviteQr.value) {
        pendingInviteQr.value?.let { payload ->
            scannedQrValue = payload
            pendingInviteQr.value = null
        }
    }

    LaunchedEffect(startScreen) {
        startScreen?.let { screen ->
            if (screen is Screen.ChatList) {
                runCatching { container.initMessagingFromStorage() }
            }
            // If launched from a notification tap, navigate directly to the relevant chat.
            // Only valid when user already has an identity (ChatList start screen).
            val destination = if (
                screen is Screen.ChatList &&
                notifConversationId != null &&
                notifSenderName != null
            ) {
                Log.d("PHANTOM", "Notification tap → Chat($notifConversationId)")
                Screen.Chat(notifConversationId, notifSenderName)
            } else {
                screen
            }
            currentScreen = destination
        }
    }

    // Transport connect and startReceiving are now owned by PhantomMessagingService (foreground
    // service). Calling them here would create a second competing connection loop. The service
    // is started from MainActivity.onCreate() and runs independently of Activity lifecycle.

    val context = androidx.compose.ui.platform.LocalContext.current
    when (val screen = currentScreen) {
        is Screen.Onboarding -> OnboardingScreen(
            container = container,
            onComplete = {
                // Identity is now persisted. Restart the foreground service so it
                // picks up the new identity, calls startReceiving(), and opens the
                // WebSocket — the earlier onStartCommand bailed out via stopSelf()
                // because no identity existed yet. Without this kick the user has
                // to fully restart the app before messages can flow.
                context.startForegroundService(Intent(context, PhantomMessagingService::class.java))
                currentScreen = Screen.ChatList
            },
        )
        is Screen.ChatList -> ChatListScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onProfile = { currentScreen = Screen.Profile },
            onScanQr = { currentScreen = Screen.QrScan },
            scannedQr = scannedQrValue,
            onScannedQrConsumed = { scannedQrValue = null },
        )
        is Screen.Calls -> CallsScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onProfile = { currentScreen = Screen.Profile },
        )
        is Screen.Settings -> SettingsScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onProfile = { currentScreen = Screen.Profile },
        )
        is Screen.Profile -> ProfileScreen(
            container = container,
            onBack = { currentScreen = Screen.ChatList },
            onLogout = { currentScreen = Screen.Onboarding },
        )
        is Screen.MessageRequests -> MessageRequestsScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onBack = { currentScreen = Screen.ChatList },
        )
        is Screen.QrScan -> QrScanScreen(
            onScanned = { raw ->
                currentScreen = Screen.ChatList
                scannedQrValue = raw
            },
            onBack = { currentScreen = Screen.ChatList },
        )
        is Screen.Chat -> ChatScreen(
            conversationId = screen.conversationId,
            theirUsername = screen.theirUsername,
            container = container,
            onBack = { currentScreen = Screen.ChatList },
            onContactProfile = { currentScreen = Screen.ContactProfile(screen.conversationId, screen.theirUsername) },
        )
        is Screen.ContactProfile -> ContactProfileScreen(
            conversationId = screen.conversationId,
            theirUsername = screen.theirUsername,
            container = container,
            onBack = { currentScreen = Screen.Chat(screen.conversationId, screen.theirUsername) },
            onDeleteConversation = { currentScreen = Screen.ChatList },
        )
        is Screen.SavedMessages -> SavedMessagesScreen(
            container = container,
            onBack = { currentScreen = Screen.ChatList },
        )
        is Screen.Archive -> ArchiveScreen(
            container = container,
            onBack = { currentScreen = Screen.ChatList },
            onNavigateToChat = { chatScreen -> currentScreen = chatScreen },
        )
        is Screen.GroupChat -> GroupChatScreen(
            groupId   = screen.groupId,
            groupName = screen.groupName,
            isChannel = screen.isChannel,
            container = container,
            onBack    = { currentScreen = Screen.ChatList },
        )
        is Screen.CreateGroup -> CreateGroupScreen(
            container = container,
            onCreated = { groupId, groupName -> currentScreen = Screen.GroupChat(groupId, groupName, false) },
            onBack    = { currentScreen = Screen.ChatList },
        )
        is Screen.CreateChannel -> CreateChannelScreen(
            container = container,
            onCreated = { groupId, groupName -> currentScreen = Screen.GroupChat(groupId, groupName, true) },
            onBack    = { currentScreen = Screen.ChatList },
        )
        is Screen.ActiveCall -> {
            val cm = container.callManager
            val noCallFlow = remember { MutableStateFlow<ActiveCall?>(null) }
            val callState by (cm?.activeCall ?: noCallFlow).collectAsState()
            val call = callState
            if (call != null && cm != null) {
                ActiveCallScreen(
                    call = call,
                    onHangup = { scope.launch { cm.hangup() }; currentScreen = Screen.ChatList },
                    onToggleMute = { cm.toggleMute() },
                    onToggleSpeaker = { cm.toggleSpeaker() },
                    onBack = { currentScreen = Screen.ChatList },
                )
            }
        }
        is Screen.IncomingCall -> IncomingCallScreen(
            username = screen.username,
            onAnswer = {
                scope.launch { container.callManager?.answerCall() }
                currentScreen = Screen.ActiveCall(screen.conversationId, screen.username)
            },
            onReject = {
                scope.launch { container.callManager?.rejectCall() }
                currentScreen = Screen.ChatList
            },
        )
    }

    // Global observer — navigate to IncomingCall when a RINGING call arrives from any screen
    LaunchedEffect(container.callManager) {
        container.callManager?.activeCall?.collect { call ->
            if (call != null &&
                call.state == CallState.RINGING &&
                currentScreen !is Screen.IncomingCall
            ) {
                currentScreen = Screen.IncomingCall(call.remotePubKeyHex, call.remoteUsername)
            }
        }
    }
}
