// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import phantom.android.ui.theme.PhantomFontMono

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
    var showBackupSheet by remember { mutableStateOf(false) }

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
            // Design Brief v3 §11.4: Settings has its own minimal header — no
            // PHANTOM wordmark, just "Settings" set in Geist Medium 20pt.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = PhantomTokens.Spacing.comfortable),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "Settings",
                        color = TextPrimary,
                        style = PhantomType.headline,
                    )
                }
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
            }
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
                // Profile card — PHANTOM_FULL_COMPOSE §06: avatar 56dp +
                // display name Geist 17px + @handle mono 12px tertiary +
                // inline FREE/PLUS/PRO tier badge. Tap → full Profile.
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = PhantomTokens.Spacing.comfortable,
                                end = PhantomTokens.Spacing.comfortable,
                                top = PhantomTokens.Spacing.tight,
                                bottom = PhantomTokens.Spacing.baseUnit,
                            )
                            .clip(RoundedCornerShape(PhantomTokens.Radius.md))
                            .background(PhantomTokens.Colors.SurfaceElevated)
                            .border(
                                1.dp,
                                BorderSubtle,
                                RoundedCornerShape(PhantomTokens.Radius.md),
                            )
                            .clickable(onClick = onProfile)
                            .padding(
                                horizontal = PhantomTokens.Spacing.comfortable,
                                vertical = PhantomTokens.Spacing.tight,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GradientAvatar(
                            name = userName.ifEmpty { "?" },
                            size = 56.dp,
                            imageBitmap = selfAvatarImage,
                        )
                        Spacer(Modifier.width(PhantomTokens.Spacing.comfortable))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (userName.isNotEmpty()) userName else "Loading…",
                                    color = TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = (-0.17).sp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .border(
                                            1.dp,
                                            Color.White.copy(alpha = 0.08f),
                                            RoundedCornerShape(4.dp),
                                        )
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "FREE",
                                        color = TextDim,
                                        fontSize = 8.sp,
                                        fontFamily = PhantomFontMono,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 1.4.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (userName.isNotEmpty()) "@$userName" else "—",
                                color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                fontFamily = PhantomFontMono,
                                letterSpacing = 0.4.sp,
                            )
                        }
                        PhIconChevron(color = TextDim, size = 14.dp)
                    }
                }

                // PHANTOM PRO upsell — restrained card with cyan accent.
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = PhantomTokens.Spacing.comfortable,
                                vertical = PhantomTokens.Spacing.baseUnit,
                            )
                            .clip(RoundedCornerShape(PhantomTokens.Radius.md))
                            .background(PhantomTokens.Colors.SurfaceElevated)
                            .border(
                                1.dp,
                                PhantomTokens.Colors.Cyan.copy(alpha = 0.20f),
                                RoundedCornerShape(PhantomTokens.Radius.md),
                            )
                            .clickable { onNavigate(Screen.Premium) }
                            .padding(
                                horizontal = PhantomTokens.Spacing.comfortable,
                                vertical = PhantomTokens.Spacing.tight,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PhantomTokens.Colors.Cyan.copy(alpha = 0.10f))
                                .border(
                                    1.dp,
                                    PhantomTokens.Colors.Cyan.copy(alpha = 0.30f),
                                    RoundedCornerShape(10.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            PhIconShieldCheck(color = PhantomTokens.Colors.Cyan, size = 18.dp)
                        }
                        Spacer(Modifier.width(PhantomTokens.Spacing.tight))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PHANTOM PRO",
                                color = PhantomTokens.Colors.Cyan,
                                style = PhantomType.overline,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Operator-grade privacy",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        PhIconChevron(color = TextDim, size = 14.dp)
                    }
                }

                // Account
                item { SettingsGroupHeader("Account") }
                item {
                    SettingsGroupCard {
                        // Privacy mode — three pills (Standard / Private / Ghost) that
                        // change real client behavior. Persisted in SharedPreferences
                        // under key "privacy_mode"; consulted by ChatScreen before
                        // calling markConversationRead so Private/Ghost suppress
                        // outgoing read receipts at the wire level.
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("Privacy Mode", color = TextPrimary, fontSize = 14.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = when (privacyMode) {
                                    "Private" -> "Private — read receipts not sent. Local read state still updates."
                                    "Ghost" -> "Ghost — read receipts not sent. (Future Phase 5: notification preview hidden, sealed sender extended.)"
                                    else -> "Standard — full delivery + read receipts visible to your contacts."
                                },
                                color = TextDim,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
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
                                            .clickable {
                                                privacyMode = mode
                                                val prefs = context.getSharedPreferences(
                                                    "phantom_prefs",
                                                    android.content.Context.MODE_PRIVATE,
                                                )
                                                prefs.edit().putString("privacy_mode", mode).apply()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            mode.uppercase(),
                                            color = if (active) BgDeep else TextDim,
                                            fontSize = 9.sp,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            fontFamily = PhantomFontMono,
                                            letterSpacing = 1.8.sp,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
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
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        // Theme row with inline LOCKED badge (per FULL_COMPOSE
                        // Settings Mobile 2). Dark is the only theme PHANTOM
                        // ships — light mode would weaken the architectural
                        // tone the design language is built on, so it stays
                        // locked rather than "coming soon".
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                PhIconSun(color = CyanAccent, size = 16.dp)
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Theme",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .border(
                                            1.dp,
                                            Color.White.copy(alpha = 0.08f),
                                            RoundedCornerShape(4.dp),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "LOCKED",
                                        color = TextDim,
                                        fontSize = 8.sp,
                                        fontFamily = PhantomFontMono,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 1.4.sp,
                                    )
                                }
                            }
                            Text(
                                text = "Dark",
                                color = TextDim,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                // Notifications — FULL_COMPOSE Settings Mobile 2.
                item { SettingsGroupHeader("Notifications") }
                item {
                    val prefs = context.getSharedPreferences(
                        "phantom_prefs",
                        android.content.Context.MODE_PRIVATE,
                    )
                    var messageAlerts by remember {
                        mutableStateOf(prefs.getBoolean("notif_message_alerts", true))
                    }
                    var callAlerts by remember {
                        mutableStateOf(prefs.getBoolean("notif_call_alerts", true))
                    }
                    SettingsGroupCard {
                        SettingsToggleRow(
                            icon = { PhIconBell(color = CyanAccent, size = 16.dp) },
                            label = "Message Alerts",
                            checked = messageAlerts,
                            onCheckedChange = {
                                messageAlerts = it
                                prefs.edit().putBoolean("notif_message_alerts", it).apply()
                            },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsToggleRow(
                            icon = { PhIconPhone(color = CyanAccent, size = 16.dp) },
                            label = "Call Alerts",
                            checked = callAlerts,
                            onCheckedChange = {
                                callAlerts = it
                                prefs.edit().putBoolean("notif_call_alerts", it).apply()
                            },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconVolume(color = CyanAccent, size = 16.dp) },
                            label = "Sound",
                            value = "Default",
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
                    var lockTimeoutMs by remember {
                        mutableStateOf(prefs.getLong("app_lock_timeout_ms", 60_000L))
                    }
                    var showTimeoutDialog by remember { mutableStateOf(false) }
                    // FULL_COMPOSE Settings Mobile 1 toggles. Defaults match the
                    // canonical reference: receipts on, last-seen on, screenshot
                    // protection off (Pro tier — gate on subscription later).
                    var readReceiptsEnabled by remember {
                        mutableStateOf(prefs.getBoolean("read_receipts", true))
                    }
                    var lastSeenEnabled by remember {
                        mutableStateOf(prefs.getBoolean("last_seen", true))
                    }
                    var screenshotProtectionEnabled by remember {
                        mutableStateOf(prefs.getBoolean("screenshot_protection", false))
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
                        if (appLockEnabled) {
                            SettingsRowItem(
                                icon = { PhIconTimer(color = CyanAccent, size = 16.dp) },
                                label = "Auto-lock",
                                value = lockTimeoutLabel(lockTimeoutMs),
                                onClick = { showTimeoutDialog = true },
                            )
                        }
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsToggleRow(
                            icon = { PhIconCheck3(color = CyanAccent, size = 16.dp) },
                            label = "Read Receipts",
                            checked = readReceiptsEnabled,
                            onCheckedChange = {
                                readReceiptsEnabled = it
                                prefs.edit().putBoolean("read_receipts", it).apply()
                            },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsToggleRow(
                            icon = { PhIconEye(color = CyanAccent, size = 16.dp) },
                            label = "Last Seen",
                            checked = lastSeenEnabled,
                            onCheckedChange = {
                                lastSeenEnabled = it
                                prefs.edit().putBoolean("last_seen", it).apply()
                            },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsToggleRow(
                            icon = { PhIconShield(color = CyanAccent, size = 16.dp) },
                            label = "Screenshot Protection",
                            proBadge = true,
                            checked = screenshotProtectionEnabled,
                            onCheckedChange = {
                                screenshotProtectionEnabled = it
                                prefs.edit().putBoolean("screenshot_protection", it).apply()
                            },
                        )
                    }

                    if (showTimeoutDialog) {
                        AppLockTimeoutDialog(
                            current = lockTimeoutMs,
                            onPick = { ms ->
                                lockTimeoutMs = ms
                                prefs.edit().putLong("app_lock_timeout_ms", ms).apply()
                                showTimeoutDialog = false
                            },
                            onDismiss = { showTimeoutDialog = false },
                        )
                    }
                }

                // Advanced — Data export + Clear cache (FULL_COMPOSE §06).
                // "Export data" opens the BackupExport bottom-sheet (§14).
                // Real export wiring lands with the BackupExport milestone;
                // the sheet exists now so the IA matches the canonical doc.
                item { SettingsGroupHeader("Advanced") }
                item {
                    val prefs = context.getSharedPreferences(
                        "phantom_prefs",
                        android.content.Context.MODE_PRIVATE,
                    )
                    var developerMode by remember {
                        mutableStateOf(prefs.getBoolean("developer_mode", false))
                    }
                    // Cache size — sum of cacheDir + databases dir. Computed
                    // once on composition; refreshes when "Clear cache" runs.
                    var cacheSize by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val total = computeCacheSizeBytes(context)
                            cacheSize = formatByteSize(total)
                        }
                    }

                    SettingsGroupCard {
                        SettingsToggleRow(
                            icon = { PhIconShieldCheck(color = CyanAccent, size = 16.dp) },
                            label = "Developer Mode",
                            checked = developerMode,
                            onCheckedChange = {
                                developerMode = it
                                prefs.edit().putBoolean("developer_mode", it).apply()
                            },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconBookmark(color = CyanAccent, size = 16.dp) },
                            label = "Storage & Cache",
                            value = cacheSize ?: "…",
                            onClick = { showComingSoon() },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconDownload(color = CyanAccent, size = 16.dp) },
                            label = "Export data",
                            value = "Encrypted",
                            onClick = { showBackupSheet = true },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconTrash(color = CyanAccent, size = 16.dp) },
                            label = "Clear cache",
                            value = null,
                            onClick = {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        context.cacheDir.deleteRecursively()
                                        context.cacheDir.mkdirs()
                                    }
                                    val total = computeCacheSizeBytes(context)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        cacheSize = formatByteSize(total)
                                        snackbarHostState.showSnackbar("Cache cleared")
                                    }
                                }
                            },
                        )
                    }
                }

                // Danger zone — destructive actions, danger-tinted, isolated
                // from the rest of the list.
                item { SettingsGroupHeader("Danger zone") }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PhantomTokens.Colors.SurfaceElevated)
                            .border(
                                1.dp,
                                Danger.copy(alpha = 0.30f),
                                RoundedCornerShape(12.dp),
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(Screen.Profile) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                PhIconTrash(color = Danger, size = 16.dp)
                            }
                            Text(
                                text = "Delete account",
                                color = Danger,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f),
                            )
                            PhIconChevron(color = Danger.copy(alpha = 0.6f), size = 14.dp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
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
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconMessage(color = CyanAccent, size = 16.dp) },
                            label = "Send feedback",
                            value = "support@phntm.pro",
                            onClick = { context.openMailto("support@phntm.pro", subject = "PHANTOM feedback") },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconShield(color = CyanAccent, size = 16.dp) },
                            label = "Report abuse",
                            value = "abuse@phntm.pro",
                            onClick = { context.openMailto("abuse@phntm.pro", subject = "PHANTOM abuse report") },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconLock(color = CyanAccent, size = 16.dp) },
                            label = "Security disclosure",
                            value = "security@phntm.pro",
                            onClick = { context.openMailto("security@phntm.pro", subject = "PHANTOM security report") },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconShieldCheck(color = CyanAccent, size = 16.dp) },
                            label = "Privacy & data",
                            value = "privacy@phntm.pro",
                            onClick = { context.openMailto("privacy@phntm.pro", subject = "PHANTOM privacy request") },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconBookmark(color = CyanAccent, size = 16.dp) },
                            label = "Legal",
                            value = "legal@phntm.pro",
                            onClick = { context.openMailto("legal@phntm.pro", subject = "PHANTOM legal inquiry") },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconMegaphone(color = CyanAccent, size = 16.dp) },
                            label = "Press",
                            value = "press@phntm.pro",
                            onClick = { context.openMailto("press@phntm.pro", subject = "PHANTOM press inquiry") },
                        )
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                        SettingsRowItem(
                            icon = { PhIconShare(color = CyanAccent, size = 14.dp) },
                            label = "Source code on GitHub",
                            value = "LiudvigVladislav/Phantom",
                            onClick = { context.openUrl("https://github.com/LiudvigVladislav/Phantom") },
                        )
                    }
                }

                // Mono footer below About — FULL_COMPOSE Settings Mobile 3.
                // Quiet attribution, sits on the SurfaceDeep tail of the list.
                item {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = "PHANTOM · v${BuildConfig.VERSION_NAME}",
                        color = TextDim.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 1.6.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            BottomNavPill(
                activeTab = NavTab.SETTINGS,
                onTabSelected = { tab ->
                    when (tab) {
                        NavTab.CALLS -> onNavigate(Screen.Calls)
                        NavTab.CHATS -> onNavigate(Screen.ChatList)
                        NavTab.NEARBY -> onNavigate(Screen.Nearby)
                        NavTab.SETTINGS -> {}
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showBackupSheet) {
        val identity by container.identityState.collectAsState()
        val pubKey = identity?.publicKeyHex.orEmpty()
        val fingerprint = remember(pubKey) {
            if (pubKey.length >= 32) pubKey.substring(0, 32).uppercase().chunked(4).joinToString("  ")
            else ""
        }
        BackupExportSheet(
            fingerprint = fingerprint,
            onExport = {
                showBackupSheet = false
                showComingSoon()
            },
            onDismiss = { showBackupSheet = false },
        )
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

// ── App Lock timeout ────────────────────────────────────────────────────────

private val LOCK_TIMEOUT_OPTIONS: List<Pair<Long, String>> = listOf(
    0L            to "Immediately",
    60_000L       to "After 1 minute",
    5 * 60_000L   to "After 5 minutes",
    60 * 60_000L  to "After 1 hour",
)

// Sum cacheDir + database files recursively. Used to render the
// "Storage & Cache" row trailing value in the Advanced section, and is
// recomputed after "Clear cache" runs so the user sees the result.
private fun computeCacheSizeBytes(context: android.content.Context): Long {
    fun walk(file: java.io.File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { walk(it) } ?: 0L
    }
    val cache = walk(context.cacheDir)
    val db = walk(context.getDatabasePath("placeholder").parentFile ?: java.io.File(""))
    return cache + db
}

private fun formatByteSize(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    if (bytes < 1_024 * 1_024) return "${bytes / 1_024} KB"
    return "%.1f MB".format(bytes / 1_048_576.0)
}

private fun lockTimeoutLabel(ms: Long): String =
    LOCK_TIMEOUT_OPTIONS.firstOrNull { it.first == ms }?.second
        ?: "After ${ms / 60_000} minutes"

@Composable
private fun AppLockTimeoutDialog(
    current: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Auto-lock", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        text = {
            Column {
                LOCK_TIMEOUT_OPTIONS.forEach { (ms, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(ms) }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Selected indicator: cyan dot for the active option.
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (ms == current) CyanAccent else TextDim.copy(alpha = 0.25f)),
                        )
                        Text(
                            text = label,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = CyanAccent, fontSize = 14.sp)
            }
        },
    )
}
