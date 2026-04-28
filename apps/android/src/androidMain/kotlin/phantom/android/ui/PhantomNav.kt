// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.theme.*

enum class NavTab { CALLS, CHATS, SETTINGS }

@Composable
fun PhantomTopBar(
    userName: String = "",
    onProfile: () -> Unit = {},
    onAddContact: () -> Unit = {},
    onScanQr: () -> Unit = {},
    avatarBitmap: ImageBitmap? = null,
) {
    var showAvatarMenu by remember { mutableStateOf(false) }
    var showComposeMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val gradientBrush = remember(userName) {
        val prefs = context.getSharedPreferences("phantom_prefs", android.content.Context.MODE_PRIVATE)
        val idx = prefs.getInt("profile_gradient_index", -1)
        if (idx >= 0) {
            val presets = listOf(
                Pair(androidx.compose.ui.graphics.Color(0xFF00D4FF), androidx.compose.ui.graphics.Color(0xFF0055CC)),
                Pair(androidx.compose.ui.graphics.Color(0xFF8B5CF6), androidx.compose.ui.graphics.Color(0xFFEC4899)),
                Pair(androidx.compose.ui.graphics.Color(0xFF2FBF71), androidx.compose.ui.graphics.Color(0xFF0099AA)),
                Pair(androidx.compose.ui.graphics.Color(0xFFF97316), androidx.compose.ui.graphics.Color(0xFFE85D75)),
                Pair(androidx.compose.ui.graphics.Color(0xFFF59E0B), androidx.compose.ui.graphics.Color(0xFFD97706)),
                Pair(androidx.compose.ui.graphics.Color(0xFF3B82F6), androidx.compose.ui.graphics.Color(0xFF1D4ED8)),
                Pair(androidx.compose.ui.graphics.Color(0xFFFB7185), androidx.compose.ui.graphics.Color(0xFFF43F5E)),
                Pair(androidx.compose.ui.graphics.Color(0xFF10B981), androidx.compose.ui.graphics.Color(0xFF065F46)),
            )
            presets.getOrNull(idx)?.let { (a, b) -> Brush.linearGradient(listOf(a, b)) }
        } else null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar + dropdown
            Box {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { showAvatarMenu = !showAvatarMenu },
                ) {
                    GradientAvatar(
                        name = userName.ifEmpty { "?" },
                        size = 36.dp,
                        brushOverride = gradientBrush,
                        imageBitmap = avatarBitmap,
                    )
                }
                DropdownMenu(
                    expanded = showAvatarMenu,
                    onDismissRequest = { showAvatarMenu = false },
                ) {
                    DropdownMenuItem(
                        leadingIcon = { PhIconFunnel(color = TextDim, size = 15.dp) },
                        text = { Text("Filter unread", fontSize = 14.sp) },
                        onClick = { showAvatarMenu = false },
                    )
                    DropdownMenuItem(
                        leadingIcon = { PhIconCheck3(color = TextDim, size = 15.dp) },
                        text = { Text("Select chats", fontSize = 14.sp) },
                        onClick = { showAvatarMenu = false },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        leadingIcon = { PhIconPerson(color = CyanAccent, size = 15.dp) },
                        text = {
                            Text(
                                "Profile",
                                color = CyanAccent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        },
                        onClick = { showAvatarMenu = false; onProfile() },
                    )
                }
            }

            // PHANTOM wordmark centered
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "PHANTOM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 5.sp,
                    color = TextPrimary,
                )
            }

            // Compose button + dropdown
            Box {
                IconButton(onClick = { showComposeMenu = !showComposeMenu }) {
                    PhIconPencilCompose(color = CyanAccent, size = 22.dp)
                }
                DropdownMenu(
                    expanded = showComposeMenu,
                    onDismissRequest = { showComposeMenu = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Create group", fontSize = 14.sp)
                                SoonBadge()
                            }
                        },
                        onClick = { showComposeMenu = false },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Create channel", fontSize = 14.sp)
                                SoonBadge()
                            }
                        },
                        onClick = { showComposeMenu = false },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Scan QR code", fontSize = 14.sp) },
                        onClick = { showComposeMenu = false; onScanQr() },
                    )
                    DropdownMenuItem(
                        text = { Text("Add by key", fontSize = 14.sp) },
                        onClick = { showComposeMenu = false; onAddContact() },
                    )
                }
            }
        }

        // Hairline divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.05f)),
        )
    }
}

@Composable
fun SoonBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(CyanAccent.copy(alpha = 0.06f))
            .border(1.dp, CyanAccent.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = "SOON",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            letterSpacing = 1.5.sp,
            color = CyanAccent,
        )
    }
}

@Composable
fun BottomNavPill(
    activeTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(bottom = 28.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF0F1318).copy(alpha = 0.94f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavPillItem(
                icon = { color -> PhIconPhone(color = color, size = 18.dp) },
                label = "Calls",
                active = activeTab == NavTab.CALLS,
                onClick = { onTabSelected(NavTab.CALLS) },
            )
            NavPillItem(
                icon = { color -> PhIconMessage(color = color, size = 18.dp) },
                label = "Chats",
                active = activeTab == NavTab.CHATS,
                onClick = { onTabSelected(NavTab.CHATS) },
            )
            NavPillItem(
                icon = { color -> PhIconGear(color = color, size = 18.dp) },
                label = "Settings",
                active = activeTab == NavTab.SETTINGS,
                onClick = { onTabSelected(NavTab.SETTINGS) },
            )
        }
    }
}

@Composable
private fun NavPillItem(
    icon: @Composable (color: Color) -> Unit,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val color = if (active) CyanAccent else TextDim
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) CyanAccent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        icon(color)
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            letterSpacing = 0.2.sp,
        )
    }
}

@Composable
fun ComingSoonOverlay(kicker: String = "COMING SOON") {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070809).copy(alpha = 0.90f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = kicker,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 3.6.sp,
                color = TextDim,
            )
            Text(
                text = "COMING SOON",
                fontFamily = FontFamily.Monospace,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp,
                color = CyanAccent,
            )
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, CyanAccent, Color.Transparent),
                        ),
                    ),
            )
            Text(
                text = "Secure peer-to-peer calls are in development.\nNo servers, no metadata, end-to-end keys only.",
                fontSize = 13.sp,
                color = TextDim,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

// ── Shared settings composables ─────────────────────────────────

@Composable
fun SettingsGroupHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 2.8.sp,
        color = TextDim,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 8.dp),
    )
}

@Composable
fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(14.dp)),
    ) {
        content()
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
fun SettingsRowItem(
    icon: @Composable () -> Unit,
    label: String,
    value: String? = null,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.5.sp,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(text = value, color = TextDim, fontSize = 13.sp)
        }
        PhIconChevron(color = TextDim, size = 14.dp)
    }
}

@Composable
fun PrivacySettingsRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            PhIconShield(color = CyanAccent, size = 16.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Privacy & Security",
                color = TextPrimary,
                fontSize = 14.5.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ModePill(label = "Standard", active = false)
                ModePill(label = "Private", active = true)
                ModePill(label = "Ghost", active = false)
            }
        }
        PhIconChevron(color = TextDim, size = 14.dp)
    }
}

@Composable
fun ModePill(label: String, active: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) CyanAccent else Color.Transparent)
            .then(
                if (!active) Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
                else Modifier,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            color = if (active) BgDeep else TextDim,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
