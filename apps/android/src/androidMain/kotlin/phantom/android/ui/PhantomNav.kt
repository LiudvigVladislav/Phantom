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
import phantom.android.ui.theme.PhantomFontMono

enum class NavTab { CALLS, CHATS, NEARBY, SETTINGS }

@Composable
fun PhantomTopBar(
    userName: String = "",
    title: String = "Messages",
    /** When true the [title] is rendered as a centered wordmark — used by
     *  the home (chat list) screen so the PHANTOM brand mark sits
     *  symmetrically between the avatar and the compose action, per
     *  FULL_COMPOSE §03 mobile mock. Other top-level tabs keep
     *  left-aligned literal titles ("Calls" / "Settings"). */
    centerTitle: Boolean = false,
    onProfile: () -> Unit = {},
    onAddContact: () -> Unit = {},
    onScanQr: () -> Unit = {},
    avatarBitmap: ImageBitmap? = null,
    avatarMenuContent: @Composable (close: () -> Unit) -> Unit = { close ->
        // Default: chat-list flavour menu.
        DropdownMenuItem(
            leadingIcon = { PhIconFunnel(color = TextDim, size = 15.dp) },
            text = { Text("Filter unread", fontSize = 14.sp) },
            onClick = { close() },
        )
        DropdownMenuItem(
            leadingIcon = { PhIconCheck3(color = TextDim, size = 15.dp) },
            text = { Text("Select chats", fontSize = 14.sp) },
            onClick = { close() },
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
            onClick = { close(); onProfile() },
        )
    },
    trailing: @Composable () -> Unit = {
        // Default: compose pencil with dropdown for new chat / group / QR.
        var showComposeMenu by remember { mutableStateOf(false) }
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
    },
) {
    var showAvatarMenu by remember { mutableStateOf(false) }
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Avatar + dropdown
                Box {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { showAvatarMenu = !showAvatarMenu },
                    ) {
                        // Phase 2 mockup: 32dp avatar in the chats header.
                        GradientAvatar(
                            name = userName.ifEmpty { "?" },
                            size = 32.dp,
                            brushOverride = gradientBrush,
                            imageBitmap = avatarBitmap,
                        )
                    }
                    DropdownMenu(
                        expanded = showAvatarMenu,
                        onDismissRequest = { showAvatarMenu = false },
                    ) {
                        avatarMenuContent { showAvatarMenu = false }
                    }
                }

                // Left-aligned title path (Calls / Settings / Nearby).
                // The chat list opts into [centerTitle] = true and uses the
                // overlay below instead so the wordmark stays geometrically
                // centered regardless of avatar / trailing widths.
                if (!centerTitle) {
                    Box(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            text = title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.20).sp,
                            color = TextPrimary,
                        )
                    }
                } else {
                    // When centred, the avatar still occupies the leading
                    // edge and trailing() the trailing edge — the wordmark
                    // overlay below sits between them, geometrically.
                    Spacer(modifier = Modifier.weight(1f))
                }

                trailing()
            }

            // Centred wordmark overlay — sits in the middle of the row
            // independent of the avatar / trailing widths.
            if (centerTitle) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.10).sp,
                    color = TextPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // Hairline divider — BorderSubtle from design system.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BorderSubtle),
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
            fontFamily = PhantomFontMono,
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
    // 64dp pill, 16dp side / bottom inset, 20dp radius, Surface bg with
    // BorderSubtle outline. A vertical fade (Transparent → BgDeep) sits
    // behind the pill so list content scrolling underneath softly dissolves
    // into the navigation area instead of cutting against an opaque edge.
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            phantom.android.ui.theme.BgDeep.copy(alpha = 0.85f),
                            phantom.android.ui.theme.BgDeep,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(phantom.android.ui.theme.BgDeep)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(phantom.android.ui.theme.Surface)
                    .border(1.dp, phantom.android.ui.theme.BorderSubtle, RoundedCornerShape(18.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
            NavPillItem(
                icon = { color -> PhIconPhone(color = color, size = 22.dp) },
                label = "Calls",
                active = activeTab == NavTab.CALLS,
                onClick = { onTabSelected(NavTab.CALLS) },
            )
            NavPillItem(
                icon = { color -> PhIconMessage(color = color, size = 22.dp) },
                label = "Chats",
                active = activeTab == NavTab.CHATS,
                onClick = { onTabSelected(NavTab.CHATS) },
            )
            NavPillItem(
                icon = { color -> PhIconRadar(color = color, size = 22.dp) },
                label = "Nearby",
                active = activeTab == NavTab.NEARBY,
                onClick = { onTabSelected(NavTab.NEARBY) },
            )
            NavPillItem(
                icon = { color -> PhIconGear(color = color, size = 22.dp) },
                label = "Settings",
                active = activeTab == NavTab.SETTINGS,
                onClick = { onTabSelected(NavTab.SETTINGS) },
            )
            }
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
    // Clip the tap target to the same RoundedCornerShape as the surrounding
    // pill so the ripple stays inside a rounded rectangle instead of a
    // square halo around the icon.
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon(color)
        // All four tabs always show their label so the user can read the
        // navigation at a glance — active tab gets primary text + cyan icon,
        // inactive stays in tertiary tone.
        Text(
            text = label,
            color = if (active) TextPrimary else TextDim,
            fontSize = 10.sp,
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
                fontFamily = PhantomFontMono,
                fontSize = 10.sp,
                letterSpacing = 3.6.sp,
                color = TextDim,
            )
            Text(
                text = "COMING SOON",
                fontFamily = PhantomFontMono,
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
    // Phase 2 mockup: section overlines are mono 11sp, 0.08em tracked,
    // tertiary tone — quieter than the body but architectural in feel.
    Text(
        text = title.uppercase(),
        fontFamily = PhantomFontMono,
        fontSize = 11.sp,
        letterSpacing = 0.88.sp,  // 0.08em × 11sp
        color = TextDim,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    // Card surface: SurfaceElevated with BorderSubtle outline, radius 12dp.
    // Replaces the inline rgba(white, 4%) border and 14dp radius.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(phantom.android.ui.theme.PhantomTokens.Colors.SurfaceElevated)
            .border(
                1.dp,
                phantom.android.ui.theme.BorderSubtle,
                RoundedCornerShape(12.dp),
            ),
    ) {
        content()
    }
    Spacer(Modifier.height(12.dp))
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Icon container — flat (no Surface2 chip), tertiary glyph.
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            // Design Brief v3 §11.5: trailing meta in Inter 13pt secondary —
            // calmer than mono, reads as detail rather than tech readout.
            Text(
                text = value,
                color = phantom.android.ui.theme.PhantomTokens.Colors.TextSecondary,
                fontSize = 13.sp,
            )
        }
        PhIconChevron(color = TextDim, size = 14.dp)
    }
}

/**
 * Toggle row for Settings — same anatomy as [SettingsRowItem] but trailing
 * is a Material3 Switch instead of value text + chevron. Carries an
 * optional `proBadge` flag that renders the FULL_COMPOSE PRO chip inline
 * with the label (Pro-only privacy controls like Screenshot Protection).
 */
@Composable
fun SettingsToggleRow(
    icon: @Composable () -> Unit,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    proBadge: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 15.sp,
            )
            if (proBadge) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CyanAccent.copy(alpha = 0.10f))
                        .border(1.dp, CyanAccent.copy(alpha = 0.30f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "PRO",
                        color = CyanAccent,
                        fontSize = 8.sp,
                        fontFamily = PhantomFontMono,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.4.sp,
                    )
                }
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = phantom.android.ui.theme.BgDeep,
                checkedTrackColor = CyanAccent,
                checkedBorderColor = CyanAccent,
                uncheckedThumbColor = TextDim,
                uncheckedTrackColor = phantom.android.ui.theme.PhantomTokens.Colors.SurfaceHover,
                uncheckedBorderColor = phantom.android.ui.theme.BorderSubtle,
            ),
        )
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
            fontFamily = PhantomFontMono,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            color = if (active) BgDeep else TextDim,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
