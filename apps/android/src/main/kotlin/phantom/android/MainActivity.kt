package phantom.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.screens.chat.ChatScreen
import phantom.android.screens.chatlist.ChatListScreen
import phantom.android.screens.onboarding.OnboardingScreen
import phantom.android.ui.theme.PhantomTheme

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as PhantomApplication).container
        enableEdgeToEdge()
        setContent {
            PhantomTheme {
                PhantomApp(container)
            }
        }
    }
}

@Composable
private fun PhantomApp(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var startScreen by remember { mutableStateOf<Screen?>(null) }

    // Determine starting screen based on whether identity exists
    LaunchedEffect(Unit) {
        val identity = container.identityRepo.loadIdentity()
        startScreen = if (identity == null) {
            Screen.Onboarding
        } else {
            Screen.ChatList
        }
    }

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Onboarding) }

    LaunchedEffect(startScreen) {
        startScreen?.let { currentScreen = it }
    }

    // Connect transport when on ChatList or Chat
    LaunchedEffect(currentScreen) {
        if (currentScreen !is Screen.Onboarding) {
            scope.launch {
                runCatching {
                    container.transport.connect(
                        relayUrl = "ws://relay.phantom.net/ws",
                        identityPublicKeyHex = container.identityRepo.loadIdentity()?.publicKeyHex ?: "",
                    )
                    container.messagingService?.startReceiving()
                }
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
        )
        is Screen.Chat -> ChatScreen(
            conversationId = screen.conversationId,
            theirUsername = screen.theirUsername,
            container = container,
            onBack = { currentScreen = Screen.ChatList },
        )
    }
}
