package phantom.android.screens.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var userName by remember { mutableStateOf("") }
    var privacyMode by remember { mutableStateOf("Standard") }

    LaunchedEffect(Unit) {
        userName = container.identityRepo.loadIdentity()?.username ?: ""
        val prefs = context.getSharedPreferences("phantom_prefs", android.content.Context.MODE_PRIVATE)
        privacyMode = prefs.getString("privacy_mode", "Standard") ?: "Standard"
    }

    fun showComingSoon() = Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()

    Scaffold(
        containerColor = BgDeep,
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
                                            .background(if (active) CyanAccent.copy(alpha = 0.5f) else Color.Transparent)
                                            .border(1.dp, if (active) CyanAccent.copy(alpha = 0.5f) else TextDim.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            mode,
                                            color = if (active) BgDeep.copy(alpha = 0.7f) else TextDim.copy(alpha = 0.4f),
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = Icons.Default.Phone,
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
                            icon = Icons.Default.Search,
                            label = "Language",
                            value = "English",
                            onClick = { showComingSoon() },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = Icons.Default.Star,
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
                            icon = Icons.Default.Notifications,
                            label = "Notifications & Sounds",
                            onClick = { showComingSoon() },
                        )
                    }
                }

                // Privacy
                item { SettingsGroupHeader("Privacy") }
                item {
                    SettingsGroupCard {
                        SettingsRowItem(
                            icon = Icons.Default.Lock,
                            label = "Confidentiality settings",
                            onClick = { showComingSoon() },
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
