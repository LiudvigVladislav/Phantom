package phantom.android.screens.settings

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
import androidx.compose.ui.unit.dp
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
    var userName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        userName = container.identityRepo.loadIdentity()?.username ?: ""
    }

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
                        PrivacySettingsRow()
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = Icons.Default.Phone,
                            label = "Linked Devices",
                            value = "Add device",
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
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = Icons.Default.Star,
                            label = "Theme",
                            value = "Dark",
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
