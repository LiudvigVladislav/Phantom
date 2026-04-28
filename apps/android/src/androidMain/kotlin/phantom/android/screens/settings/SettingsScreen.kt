// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.BuildConfig
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
    val identity by container.identityState.collectAsState()
    val userName = identity?.username ?: ""
    val selfAvatarBitmap by container.selfAvatar.collectAsState()
    val selfAvatarImage = remember(selfAvatarBitmap) { selfAvatarBitmap?.asImageBitmap() }
    var privacyMode by remember { mutableStateOf("Standard") }

    LaunchedEffect(Unit) {
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
                avatarBitmap = selfAvatarImage,
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

                // About — version + categorised contact addresses (per
                // SECURITY.md / Releases/README.md routing table). Each entry
                // launches an ACTION_SENDTO intent so the user picks their own
                // mail client; we never embed an email body or attachment, so
                // these links carry no app data of their own.
                item { SettingsGroupHeader("About") }
                item {
                    SettingsGroupCard {
                        SettingsRowItem(
                            icon = { PhIconGlobe(color = CyanAccent, size = 16.dp) },
                            label = "App version",
                            value = BuildConfig.VERSION_NAME,
                            onClick = { /* no-op: read-only */ },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = { PhIconMessage(color = CyanAccent, size = 16.dp) },
                            label = "Send feedback",
                            value = "support@phntm.pro",
                            onClick = { context.openMailto("support@phntm.pro", subject = "PHANTOM feedback") },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = { PhIconShield(color = CyanAccent, size = 16.dp) },
                            label = "Report abuse",
                            value = "abuse@phntm.pro",
                            onClick = { context.openMailto("abuse@phntm.pro", subject = "PHANTOM abuse report") },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = { PhIconLock(color = CyanAccent, size = 16.dp) },
                            label = "Security disclosure",
                            value = "security@phntm.pro",
                            onClick = { context.openMailto("security@phntm.pro", subject = "PHANTOM security report") },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = { PhIconShieldCheck(color = CyanAccent, size = 16.dp) },
                            label = "Privacy & data",
                            value = "privacy@phntm.pro",
                            onClick = { context.openMailto("privacy@phntm.pro", subject = "PHANTOM privacy request") },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = { PhIconBookmark(color = CyanAccent, size = 16.dp) },
                            label = "Legal",
                            value = "legal@phntm.pro",
                            onClick = { context.openMailto("legal@phntm.pro", subject = "PHANTOM legal inquiry") },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = { PhIconMegaphone(color = CyanAccent, size = 16.dp) },
                            label = "Press",
                            value = "press@phntm.pro",
                            onClick = { context.openMailto("press@phntm.pro", subject = "PHANTOM press inquiry") },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                        SettingsRowItem(
                            icon = { PhIconShare(color = CyanAccent, size = 14.dp) },
                            label = "Source code on GitHub",
                            value = "LiudvigVladislav/Phantom",
                            onClick = { context.openUrl("https://github.com/LiudvigVladislav/Phantom") },
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

/**
 * Launches the user's mail client with a pre-addressed message. Uses
 * `mailto:` via `ACTION_SENDTO` rather than `ACTION_SEND`, so only apps
 * registered as email handlers (not arbitrary share targets) appear in the
 * chooser. The body is left empty on purpose — the relay-routing addresses
 * are categorised enough that the recipient inbox tag tells us what the
 * user is reporting; we never want PHANTOM to leak local context into an
 * outgoing draft.
 */
private fun android.content.Context.openMailto(address: String, subject: String) {
    val uri = Uri.parse("mailto:$address?subject=${Uri.encode(subject)}")
    val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}

private fun android.content.Context.openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}
