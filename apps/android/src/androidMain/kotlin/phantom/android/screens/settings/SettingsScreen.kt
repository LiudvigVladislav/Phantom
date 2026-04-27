package phantom.android.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.*
import phantom.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onNavigate: (Screen) -> Unit,
    onProfile: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var userName by remember { mutableStateOf("") }
    var privacyMode by remember { mutableStateOf("Standard") }

    LaunchedEffect(Unit) {
        userName = container.identityRepo.loadIdentity()?.username ?: ""
        val prefs = context.getSharedPreferences("phantom_prefs", android.content.Context.MODE_PRIVATE)
        privacyMode = prefs.getString("privacy_mode", "Standard") ?: "Standard"
    }

    fun showComingSoon() {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("Coming soon — stay tuned for updates")
        }
    }

    Scaffold(
        containerColor = BgDeep,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    containerColor = Surface,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(12.dp),
                    action = {
                        TextButton(onClick = { data.dismiss() }) {
                            Text("OK", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    },
                ) {
                    Text(data.visuals.message, fontSize = 13.sp)
                }
            }
        },
        topBar = {
            PhantomTopBar(
                userName = userName,
                onProfile = onProfile,
                onAddContact = { onNavigate(Screen.ChatList) },
                onScanQr = { onNavigate(Screen.QrScan) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 110.dp),
            ) {
                // Account
                item { SettingsGroupHeader("Account") }
                item {
                    SettingsGroupCard {
                        // Privacy mode — интерактивный
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Privacy Mode", color = TextPrimary, fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                SoonBadge()
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Standard", "Private", "Ghost").forEach { mode ->
                                    val active = privacyMode == mode
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (active) CyanAccent else Color.Transparent)
                                            .border(
                                                1.dp,
                                                if (active) Color.Transparent else Color.White.copy(alpha = 0.12f),
                                                RoundedCornerShape(20.dp),
                                            )
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            mode.uppercase(),
                                            color = if (active) BgDeep else TextDim,
                                            fontSize = 9.sp,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            fontFamily = FontFamily.Monospace,
                                            letterSpacing = 1.8.sp,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = { PhIconDevice(color = CyanAccent, size = 16.dp) },
                            label = "Linked Devices",
                            value = "Add device",
                            onClick = { showComingSoon() },
                        )
                    }
                }

                // Appearance
                item { SettingsGroupHeader("Appearance") }
                item {
                    SettingsGroupCard {
                        SettingsRowItem(
                            icon = { PhIconGlobe(color = CyanAccent, size = 16.dp) },
                            label = "Language",
                            value = "English",
                            onClick = { showComingSoon() },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = { PhIconSun(color = CyanAccent, size = 16.dp) },
                            label = "Theme",
                            value = "Dark",
                            onClick = { showComingSoon() },
                        )
                    }
                }

                // Notifications
                item { SettingsGroupHeader("Notifications") }
                item {
                    SettingsGroupCard {
                        SettingsRowItem(
                            icon = { PhIconBell(color = CyanAccent, size = 16.dp) },
                            label = "Notifications & Sounds",
                            onClick = { showComingSoon() },
                        )
                    }
                }

                // Privacy
                item { SettingsGroupHeader("Privacy") }
                item {
                    val prefs = context.getSharedPreferences(
                        "phantom_prefs",
                        android.content.Context.MODE_PRIVATE,
                    )
                    var appLockEnabled by remember {
                        mutableStateOf(prefs.getBoolean("app_lock_enabled", false))
                    }
                    SettingsGroupCard {
                        SettingsRowItem(
                            icon = { PhIconLock(color = CyanAccent, size = 16.dp) },
                            label = "App Lock",
                            value = if (appLockEnabled) "On" else "Off",
                            onClick = {
                                val newVal = !appLockEnabled
                                appLockEnabled = newVal
                                prefs.edit().putBoolean("app_lock_enabled", newVal).apply()
                            },
                        )
                    }
                }
            }

            BottomNavPill(
                activeTab = NavTab.SETTINGS,
                onTabSelected = { tab ->
                    when (tab) {
                        NavTab.CALLS -> onNavigate(Screen.Calls)
                        NavTab.CHATS -> onNavigate(Screen.ChatList)
                        NavTab.SETTINGS -> {}
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
