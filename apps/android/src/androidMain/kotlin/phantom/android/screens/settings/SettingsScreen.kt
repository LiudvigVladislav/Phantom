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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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

/**
 * Settings screen — rewritten 2026-05-09 to match
 * `Design/PHANTOM_FULL_COMPOSE.md` §06 +
 * `Design/src/app/components/phase2/SettingsScreen.tsx`.
 *
 * Structure (top → bottom):
 *   1. Profile card (avatar + name + tier badge + chevron → ProfileScreen)
 *   2. Account            — Profile, Username, Plan
 *   3. Privacy & Security — Encryption Protocol, Privacy Mode, Read Receipts,
 *                           Last Seen, Screenshot Protection
 *   4. Notifications      — Message Alerts, Call Alerts, Sound
 *   5. Appearance         — Theme (Locked), Language
 *   6. Advanced           — Storage & Cache, Export Data
 *   7. About              — Version, Send Feedback, Privacy Policy
 *
 * Per [`DECISIONS_LOG`](../../docs/project/DECISIONS_LOG.md):
 *   - D-17: Developer Mode toggle removed (dead pref).
 *   - D-18: Pro infrastructure ships full UI in Alpha 2 (badges visible),
 *           payment integration deferred to Beta. Pro-gated controls
 *           remain functionally OPEN for testers.
 *   - ADR-020 Phase 3: Privacy Mode is a row with chevron → opens
 *     [`PrivacyModeDetailScreen`] (the pill picker + Ghost confirm).
 */
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

    // Toggle state — backed by `phantom_prefs` so the value survives the
    // Settings round-trip (re-entry sees the last toggle position).
    val prefs = remember {
        context.getSharedPreferences("phantom_prefs", android.content.Context.MODE_PRIVATE)
    }
    var readReceipts by remember { mutableStateOf(prefs.getBoolean("read_receipts", true)) }
    var screenshotProtection by remember { mutableStateOf(prefs.getBoolean("screenshot_protection", false)) }
    var messageAlerts by remember { mutableStateOf(prefs.getBoolean("message_alerts", true)) }
    var callAlerts by remember { mutableStateOf(prefs.getBoolean("call_alerts", true)) }

    // Privacy Mode value displayed in the row (no inline picker — that's a
    // separate detail screen now per ADR-020 Phase 3 spec).
    val privacyModeLabel = remember(identity) {
        container.transportPreferences.privacyMode.name
    }

    // Storage & Cache — sum of cacheDir + databases dir, recomputed on entry.
    var cacheSize by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cacheSize = formatByteSize(computeCacheSizeBytes(context))
        }
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
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    containerColor = Surface,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(12.dp),
                    action = {
                        TextButton(onClick = { data.dismiss() }) {
                            Text("OK", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    },
                ) { Text(data.visuals.message, fontSize = 13.sp) }
            }
        },
        topBar = {
            // Settings header per Design Brief v3 §11.4 — Geist Medium 20pt,
            // 64pt header height, single divider underneath.
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Bottom contentPadding accounts for the floating BottomNavPill
            // (pill is 64dp tall + 16dp from screen edge + a little breathing
            // room) so the last "About" row stays scroll-reachable above it.
            contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp),
        ) {
            // ── 1. Profile card ──────────────────────────────────────────
            item {
                ProfileCard(
                    name = userName,
                    handle = if (userName.isNotEmpty()) "@$userName" else "",
                    avatar = selfAvatarImage,
                    initials = userName.take(2).uppercase(),
                    tierBadge = "FREE", // D-18: Pro infrastructure UI visible, no payment yet
                    onClick = onProfile,
                )
            }

            // ── 2. Account ───────────────────────────────────────────────
            item { SettingsGroupHeader("Account") }
            item {
                SettingsGroupCard {
                    SettingsRowItem(
                        icon = { PhIconPerson(color = CyanAccent, size = 16.dp) },
                        label = "Profile",
                        value = userName,
                        onClick = onProfile,
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsRowItem(
                        icon = { PhIconKey(color = CyanAccent, size = 16.dp) },
                        label = "Username",
                        value = if (userName.isNotEmpty()) "@$userName" else "",
                        onClick = { showComingSoon() },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsRowItemWithBadge(
                        icon = { PhIconCreditCard(color = CyanAccent, size = 16.dp) },
                        label = "Plan",
                        badge = { UpgradeBadge() },
                        value = "Free",
                        onClick = { onNavigate(Screen.Premium) },
                    )
                }
            }

            // ── 3. Privacy & Security ────────────────────────────────────
            item { SettingsGroupHeader("Privacy & Security") }
            item {
                SettingsGroupCard {
                    SettingsRowItem(
                        icon = { PhIconShield(color = CyanAccent, size = 16.dp) },
                        label = "Encryption Protocol",
                        value = "ED25519",
                        onClick = { showComingSoon() },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsRowItem(
                        icon = { PhIconEyeOff(color = CyanAccent, size = 16.dp) },
                        label = "Privacy Mode",
                        value = privacyModeLabel,
                        onClick = { onNavigate(Screen.PrivacyModeDetail) },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsToggleRow(
                        icon = { PhIconDoubleCheck(color = CyanAccent, size = 16.dp) },
                        label = "Read Receipts",
                        checked = readReceipts,
                        onCheckedChange = {
                            readReceipts = it
                            prefs.edit().putBoolean("read_receipts", it).apply()
                        },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsRowItem(
                        icon = { PhIconClock(color = CyanAccent, size = 16.dp) },
                        label = "Last Seen",
                        value = "Contacts only",
                        onClick = { showComingSoon() },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsToggleRow(
                        icon = { PhIconCamera(color = CyanAccent, size = 16.dp) },
                        label = "Screenshot Protection",
                        checked = screenshotProtection,
                        onCheckedChange = {
                            screenshotProtection = it
                            prefs.edit().putBoolean("screenshot_protection", it).apply()
                        },
                        proBadge = true, // D-18: visible badge, control still functional
                    )
                }
            }

            // ── 4. Notifications ─────────────────────────────────────────
            item { SettingsGroupHeader("Notifications") }
            item {
                SettingsGroupCard {
                    SettingsToggleRow(
                        icon = { PhIconBell(color = CyanAccent, size = 16.dp) },
                        label = "Message Alerts",
                        checked = messageAlerts,
                        onCheckedChange = {
                            messageAlerts = it
                            prefs.edit().putBoolean("message_alerts", it).apply()
                        },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsToggleRow(
                        icon = { PhIconPhone(color = CyanAccent, size = 16.dp) },
                        label = "Call Alerts",
                        checked = callAlerts,
                        onCheckedChange = {
                            callAlerts = it
                            prefs.edit().putBoolean("call_alerts", it).apply()
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

            // ── 5. Appearance ────────────────────────────────────────────
            item { SettingsGroupHeader("Appearance") }
            item {
                SettingsGroupCard {
                    SettingsRowItemWithBadge(
                        icon = { PhIconSun(color = CyanAccent, size = 16.dp) },
                        label = "Theme",
                        badge = { LockedBadge() },
                        value = "Dark",
                        onClick = { showComingSoon() },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsRowItem(
                        icon = { PhIconGlobe(color = CyanAccent, size = 16.dp) },
                        label = "Language",
                        value = "English",
                        onClick = { showComingSoon() },
                    )
                }
            }

            // ── 6. Advanced ──────────────────────────────────────────────
            // D-17: Developer Mode toggle removed.
            item { SettingsGroupHeader("Advanced") }
            item {
                SettingsGroupCard {
                    SettingsRowItem(
                        icon = { PhIconDatabase(color = CyanAccent, size = 16.dp) },
                        label = "Storage & Cache",
                        value = cacheSize ?: "…",
                        onClick = { showComingSoon() },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsRowItem(
                        icon = { PhIconDownload(color = CyanAccent, size = 16.dp) },
                        label = "Export Data",
                        onClick = { showComingSoon() },
                    )
                }
            }

            // ── 7. About ─────────────────────────────────────────────────
            item { SettingsGroupHeader("About") }
            item {
                SettingsGroupCard {
                    SettingsRowItem(
                        icon = { PhIconInfo(color = CyanAccent, size = 16.dp) },
                        label = "Version",
                        value = BuildConfig.VERSION_NAME,
                        onClick = { /* read-only */ },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsRowItem(
                        // Speech-bubble glyph per Vladislav's design ref —
                        // PhIconMessageCircle was rebuilt on Canvas so it
                        // renders cleanly at 16dp instead of as overlapping
                        // rings (the old Lucide-arc path did).
                        icon = { PhIconMessageCircle(color = CyanAccent, size = 16.dp) },
                        label = "Send Feedback",
                        onClick = {
                            context.openMailto("support@phntm.pro", subject = "PHANTOM feedback")
                        },
                    )
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    SettingsRowItem(
                        icon = { PhIconFileText(color = CyanAccent, size = 16.dp) },
                        label = "Privacy Policy",
                        onClick = {
                            context.openUrl("https://phntm.pro/privacy")
                        },
                    )
                }
            }

            // PR-M2c.0 — diagnostics row. Removed once the probe data lands the
            // production M2c chunk-size change. Visible in all builds because
            // this is a one-shot field-test triggered by a single tap.
            item {
                Spacer(Modifier.height(20.dp))
                SettingsGroupHeader("Diagnostics")
                Spacer(Modifier.height(10.dp))
                var probeStatus by remember { mutableStateOf<String?>(null) }
                var probeRunning by remember { mutableStateOf(false) }
                SettingsGroupCard {
                    SettingsRowItem(
                        icon = { PhIconInfo(color = CyanAccent, size = 16.dp) },
                        label = if (probeRunning) "Running media probe…" else "Run media route probe",
                        value = probeStatus,
                        onClick = run@ {
                            if (probeRunning) return@run
                            val tokenProvider = container.mediaAuthTokenProvider
                            val relayBase = container.relayHttpBaseForProbe
                            if (tokenProvider == null || relayBase == null) {
                                probeStatus = "Hybrid transport not ready — open a chat first"
                                return@run
                            }
                            probeRunning = true
                            probeStatus = "Running media probe…"
                            scope.launch {
                                runCatching {
                                    phantom.android.diagnostics.Tele2CapProbe(
                                        tokenProvider = tokenProvider,
                                        relayBaseUrl = relayBase,
                                        log = { msg ->
                                            android.util.Log.i("PhantomMedia", msg)
                                        },
                                    ).run { status -> probeStatus = status }
                                }.onFailure { ex ->
                                    android.util.Log.w(
                                        "PhantomMedia",
                                        "M2C0_PROBE crashed: ${ex::class.simpleName}: ${ex.message}",
                                        ex,
                                    )
                                    probeStatus = "Crashed: ${ex::class.simpleName}"
                                }
                                probeRunning = false
                            }
                        },
                    )
                }
            }

            // Footer — quiet build identifier per FULL_COMPOSE
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "PHANTOM · ${BuildConfig.VERSION_NAME}",
                    color = TextDim.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 8.dp)
                        .wrapContentWidth(Alignment.CenterHorizontally),
                )
            }
        }
            // Floating bottom-nav pill — Settings is a top-level destination
            // alongside Chats / Calls / Nearby, so the pill stays visible.
            // Earlier the pill was missing here, leaving Settings the only
            // top-level tab without app-wide navigation. Bug surfaced
            // during 2026-05-09 visual QA.
            BottomNavPill(
                activeTab = NavTab.SETTINGS,
                onTabSelected = { tab ->
                    when (tab) {
                        NavTab.CHATS    -> onNavigate(Screen.ChatList)
                        NavTab.CALLS    -> onNavigate(Screen.Calls)
                        NavTab.NEARBY   -> onNavigate(Screen.Nearby)
                        NavTab.SETTINGS -> {}
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

// ── Self-profile gradient helper ──────────────────────────────────────────────

/**
 * Re-derives the self-profile gradient brush every recomposition so the
 * Settings ProfileCard reflects gradient picks made in ProfileScreen
 * without needing an explicit refresh signal. Returns null when the user
 * has not picked a preset (the avatar then derives a brush from the
 * username, which is the GradientAvatar default behaviour).
 */
@Composable
private fun rememberSelfProfileGradient(): Brush? {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("phantom_prefs", android.content.Context.MODE_PRIVATE)
    }
    val idx = prefs.getInt("profile_gradient_index", -1)
    if (idx < 0) return null
    val presets = listOf(
        Color(0xFF00D4FF) to Color(0xFF0055CC),
        Color(0xFF8B5CF6) to Color(0xFFEC4899),
        Color(0xFF2FBF71) to Color(0xFF0099AA),
        Color(0xFFF97316) to Color(0xFFE85D75),
        Color(0xFFF59E0B) to Color(0xFFD97706),
        Color(0xFF3B82F6) to Color(0xFF1D4ED8),
        Color(0xFFFB7185) to Color(0xFFF43F5E),
        Color(0xFF10B981) to Color(0xFF065F46),
    )
    return presets.getOrNull(idx)?.let { (a, b) -> Brush.linearGradient(listOf(a, b)) }
}

// ── Profile card ──────────────────────────────────────────────────────────────

/**
 * Top-of-Settings profile card per FULL_COMPOSE §06. Surface elevated, 12dp
 * radius, 16dp inner padding, 14dp gap between avatar and text. Right-aligned
 * chevron signals tap-through to the profile screen.
 */
@Composable
private fun ProfileCard(
    name: String,
    handle: String,
    avatar: androidx.compose.ui.graphics.ImageBitmap?,
    @Suppress("UNUSED_PARAMETER") initials: String,
    tierBadge: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PhantomTokens.Colors.SurfaceElevated)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Avatar 52dp via GradientAvatar so the user's chosen profile
        // gradient (or photo) from ProfileScreen is reflected here too.
        // The earlier hand-rolled Box with hardcoded indigo bg ignored the
        // preset picker and the avatar felt out-of-sync after a gradient
        // change in ProfileScreen.
        GradientAvatar(
            name = name.ifEmpty { handle.removePrefix("@").ifEmpty { "?" } },
            size = 52.dp,
            brushOverride = rememberSelfProfileGradient(),
            imageBitmap = avatar,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name.ifEmpty { "—" },
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.16).sp,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Surface)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = tierBadge,
                        color = TextDim,
                        fontSize = 9.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 0.7.sp,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = handle,
                color = TextDim.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontFamily = PhantomFontMono,
            )
        }
        PhIconChevron(color = TextDim.copy(alpha = 0.55f), size = 14.dp)
    }
    Spacer(Modifier.height(8.dp))
}

// ── Settings row variants with badges ─────────────────────────────────────────

/**
 * Settings row with a trailing badge between label and value/chevron. Used
 * for rows that need a Pro / Locked / Upgrade indicator (see [UpgradeBadge],
 * [LockedBadge]). Otherwise identical anatomy to [SettingsRowItem].
 */
@Composable
private fun SettingsRowItemWithBadge(
    icon: @Composable () -> Unit,
    label: String,
    badge: @Composable () -> Unit,
    value: String? = null,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        badge()
        if (value != null) {
            Text(
                text = value,
                color = PhantomTokens.Colors.TextSecondary,
                fontSize = 13.sp,
            )
        }
        PhIconChevron(color = TextDim, size = 14.dp)
    }
}

@Composable
private fun UpgradeBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(CyanAccent)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "UPGRADE",
            color = BgDeep,
            fontSize = 8.sp,
            fontFamily = PhantomFontMono,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.0.sp,
        )
    }
}

@Composable
private fun LockedBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Surface)
            .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "LOCKED",
            color = TextDim,
            fontSize = 8.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 1.0.sp,
        )
    }
}

// ── Helpers (preserved from previous Settings implementation) ─────────────────

/**
 * Open a `mailto:` link with subject pre-filled. ACTION_SENDTO ensures only
 * apps registered as email handlers (not arbitrary share targets) appear in
 * the chooser. Body is intentionally empty — we never want PHANTOM to leak
 * local context into an outgoing draft.
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

/**
 * Sum cacheDir + database files recursively. Used to render the "Storage &
 * Cache" row trailing value in the Advanced section.
 */
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
