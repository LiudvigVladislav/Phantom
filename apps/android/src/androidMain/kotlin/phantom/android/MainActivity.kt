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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import phantom.android.screens.migration.MigrationScreen
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
            // User-configurable threshold — see Settings → App Lock → Auto-lock.
            // 0L means "lock immediately whenever the app is backgrounded";
            // any positive value is a grace window in milliseconds.
            val timeoutMs = prefs.getLong("app_lock_timeout_ms", 60_000L)
            val elapsed = System.currentTimeMillis() - backgroundedAt
            if (backgroundedAt > 0 && (timeoutMs == 0L || elapsed > timeoutMs)) {
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
        startScreen = when {
            identity == null -> Screen.Onboarding
            // PR C-followup-2: Alpha 1 → Alpha 2 migration trigger.
            // Records that predate the PR C commit-6 schema migration
            // have null signingPublicKeyHex; needsMigration() returns
            // true and the user lands on MigrationScreen instead of
            // ChatList. After they tap Continue the screen invokes
            // [onMigrationComplete] which advances to ChatList.
            // We must initMessaging FIRST so container.migrationManager
            // is set; without it the field is null and we'd fall
            // through to ChatList, where the broken-Alpha-1 send path
            // would throw on every outgoing message.
            else -> {
                runCatching { container.initMessagingFromStorage() }
                val mgr = container.migrationManager
                if (mgr != null && mgr.needsMigration()) Screen.Migration
                else Screen.ChatList
            }
        }
    }

    // currentScreen starts as null so the first frame after readiness
    // shows the splash, NOT a brief flash of Onboarding (the prior
    // default that surfaced as "ToS shown on every restart" in 2026-04-30
    // testing — bug F). The when-block below treats null → splash.
    var currentScreen by remember { mutableStateOf<Screen?>(null) }
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
            // initMessagingFromStorage was called inside the prior
            // LaunchedEffect for the Migration / ChatList branches;
            // calling it again here would be redundant. Onboarding
            // path doesn't initialise messaging until onComplete fires.
            if (screen is Screen.ChatList && container.migrationManager == null) {
                // Defence-in-depth: cover any cold-path where the
                // outer LaunchedEffect bailed before initMessaging.
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

    // Holds the ActiveCall screen to navigate to once mic permission is granted on answer.
    var pendingAnswerScreen by remember { mutableStateOf<Screen.ActiveCall?>(null) }

    val answerMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val dest = pendingAnswerScreen ?: return@rememberLauncherForActivityResult
        pendingAnswerScreen = null
        if (granted) {
            scope.launch { container.callManager?.answerCall() }
            currentScreen = dest
        } else {
            // Permission denied — cannot proceed; treat as reject so the call clears.
            scope.launch { container.callManager?.rejectCall() }
            currentScreen = Screen.ChatList
        }
    }

    when (val screen = currentScreen) {
        null -> {
            // Initial frame before LaunchedEffect resolves the start
            // screen. Show splash, NOT Onboarding — the prior default
            // briefly flashed the welcome screen on every relaunch
            // even when identity already existed (bug F, 2026-04-30).
            PhantomSplashScreen()
        }
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
        is Screen.Migration -> {
            // PR C-followup-2: Alpha 1 → Alpha 2 migration UI. The
            // manager was wired by initMessagingFromStorage in the
            // launch LaunchedEffect, so it's non-null here. On a
            // success the runMigration call has already updated the
            // local state; we kick the foreground service so the new
            // bundle gets published over a fresh WS connection
            // (background ticker would otherwise wait 24h) and route
            // the user onto ChatList.
            val mgr = container.migrationManager
            if (mgr != null) {
                MigrationScreen(
                    migrationManager = mgr,
                    onMigrationComplete = {
                        // Trigger initial bundle publish via lifecycle
                        // service immediately — without it the user
                        // can't receive first messages until the 24-h
                        // ticker fires.
                        scope.launch {
                            runCatching {
                                container.preKeyLifecycle?.bootstrapForNewIdentity()
                            }
                        }
                        context.startForegroundService(
                            Intent(context, PhantomMessagingService::class.java),
                        )
                        currentScreen = Screen.ChatList
                    },
                    onQuit = {
                        // Activity finish() drops the user back to launcher.
                        // The next launch will see needsMigration() == true
                        // again — there is no "skip migration" path.
                        (context as? android.app.Activity)?.finishAndRemoveTask()
                    },
                )
            } else {
                // Edge case: container.migrationManager is null because
                // initMessaging never ran (e.g. some race). Fall back
                // to ChatList; the user will hit a hard send error
                // until they restart the app.
                Log.w(
                    "PHANTOM_MIGRATION",
                    "Screen.Migration with null migrationManager; falling back to ChatList",
                )
                currentScreen = Screen.ChatList
            }
        }
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
        is Screen.Nearby -> phantom.android.screens.nearby.NearbyScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onProfile = { currentScreen = Screen.Profile },
        )
        is Screen.Premium -> phantom.android.screens.premium.PremiumScreen(
            onBack = { currentScreen = Screen.Settings },
        )
        is Screen.AddContact -> phantom.android.screens.addcontact.AddContactScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onBack = { currentScreen = Screen.ChatList },
        )
        is Screen.Settings -> SettingsScreen(
            container = container,
            onNavigate = { currentScreen = it },
            onProfile = { currentScreen = Screen.Profile },
        )
        is Screen.PrivacyModeDetail -> phantom.android.screens.settings.PrivacyModeDetailScreen(
            container = container,
            onBack = { currentScreen = Screen.Settings },
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
            onStartVoiceCall = {
                currentScreen = Screen.ActiveCall(screen.conversationId, screen.theirUsername)
            },
        )
        is Screen.ContactProfile -> ContactProfileScreen(
            conversationId = screen.conversationId,
            theirUsername = screen.theirUsername,
            container = container,
            onBack = { currentScreen = Screen.Chat(screen.conversationId, screen.theirUsername) },
            onDeleteConversation = { currentScreen = Screen.ChatList },
            onVerify = {
                currentScreen = Screen.Verify(screen.conversationId, screen.theirUsername)
            },
        )
        is Screen.Verify -> phantom.android.screens.verify.VerifyScreen(
            container = container,
            conversationId = screen.conversationId,
            theirUsername = screen.theirUsername,
            onBack = {
                currentScreen = Screen.ContactProfile(screen.conversationId, screen.theirUsername)
            },
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
            // After hangup / remote-hangup return to the chat we came from, NOT
            // back to ChatList. The user expects context continuity: end the
            // call, see the conversation thread.
            val backToChat = Screen.Chat(screen.conversationId, screen.username)
            LaunchedEffect(call) {
                if (call == null) {
                    currentScreen = backToChat
                }
            }
            if (call != null && cm != null) {
                ActiveCallScreen(
                    call = call,
                    onHangup = { scope.launch { cm.hangup() }; currentScreen = backToChat },
                    onToggleMute = { cm.toggleMute() },
                    onToggleSpeaker = { cm.toggleSpeaker() },
                    onBack = { currentScreen = backToChat },
                )
            }
        }
        is Screen.IncomingCall -> IncomingCallScreen(
            username = screen.username,
            onAnswer = {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    scope.launch { container.callManager?.answerCall() }
                    currentScreen = Screen.ActiveCall(screen.conversationId, screen.username)
                } else {
                    pendingAnswerScreen = Screen.ActiveCall(screen.conversationId, screen.username)
                    answerMicLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
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
