package phantom.android

import android.os.Bundle
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
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.screens.splash.PhantomSplashScreen
import phantom.android.navigation.Screen
import phantom.android.qr.QrScanScreen
import phantom.android.screens.calls.CallsScreen
import phantom.android.screens.chat.ChatScreen
import phantom.android.screens.chatlist.ChatListScreen
import phantom.android.screens.contact.ContactProfileScreen
import phantom.android.screens.onboarding.OnboardingScreen
import phantom.android.screens.profile.ProfileScreen
import phantom.android.screens.requests.MessageRequestsScreen
import phantom.android.screens.saved.SavedMessagesScreen
import phantom.android.screens.settings.SettingsScreen
import phantom.android.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("PHANTOM_INIT", "MainActivity onCreate")
        val app = application as PhantomApplication
        // Read notification extras once — intent is immutable after activity creation.
        val notifConversationId = intent.getStringExtra(PhantomNotificationManager.EXTRA_CONVERSATION_ID)
        val notifSenderName     = intent.getStringExtra(PhantomNotificationManager.EXTRA_THEIR_USERNAME)
        // enableEdgeToEdge() conflicts with API 35+ system-enforced edge-to-edge.
        // On API 35+, the system handles it automatically; calling it again
        // corrupts the EGL surface setup (GFXSTREAM / Unknown dataspace 0).
        setContent {
            PhantomTheme {
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

@Composable
private fun PhantomApp(
    container: AppContainer,
    notifConversationId: String? = null,
    notifSenderName: String? = null,
) {
    val scope = rememberCoroutineScope()
    var startScreen by remember { mutableStateOf<Screen?>(null) }

    LaunchedEffect(Unit) {
        val identity = container.identityRepo.loadIdentity()
        startScreen = if (identity == null) Screen.Onboarding else Screen.ChatList
    }

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Onboarding) }
    var scannedQrValue by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(container.messagingService) {
        val service = container.messagingService ?: return@LaunchedEffect
        val pubKey = container.identityRepo.loadIdentity()?.publicKeyHex ?: return@LaunchedEffect
        Log.d("PHANTOM", "Starting transport, pubKey=${pubKey.take(12)}")

        runCatching { service.startReceiving() }
            .onSuccess { Log.d("PHANTOM", "startReceiving OK") }
            .onFailure { e -> Log.e("PHANTOM", "startReceiving failed: ${e.message}") }

        scope.launch {
            runCatching {
                container.transport.connect(
                    relayUrl = phantom.android.BuildConfig.RELAY_URL,
                    identityPublicKeyHex = pubKey,
                    token = phantom.android.BuildConfig.RELAY_TOKEN,
                )
            }.onFailure { e ->
                Log.e("PHANTOM", "Transport connect failed: ${e.message}", e)
            }
        }
    }

    when (val screen = currentScreen) {
        is Screen.Onboarding -> OnboardingScreen(
            container = container,
            onComplete = { currentScreen = Screen.ChatList },
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
    }
}
