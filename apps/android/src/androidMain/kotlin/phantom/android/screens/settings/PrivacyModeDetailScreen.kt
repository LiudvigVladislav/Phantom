// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.service.PhantomMessagingService
import phantom.android.ui.*
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono
import phantom.core.transport.PrivacyMode

/**
 * ADR-020 Phase 3 + Settings rewrite: dedicated detail screen for the
 * Privacy Mode selector. Reached from Settings → Privacy & Security →
 * Privacy Mode (chevron). Hosts the pill picker (formerly inline in the
 * old Settings) plus the Ghost-mode confirm dialog.
 *
 * Selecting a non-Ghost mode applies it immediately via
 * [`AppContainer.setPrivacyMode`] + a foreground-service restart so the
 * `TransportManager` walks the new strategy chain on the next connect.
 *
 * Ghost-mode selection opens a one-time confirm dialog explaining the
 * no-silent-downgrade trade-off — same wording as the previous inline
 * implementation, just lifted into its own screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyModeDetailScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(container.transportPreferences.privacyMode) }
    var pendingGhost by remember { mutableStateOf(false) }

    if (pendingGhost) {
        AlertDialog(
            onDismissRequest = { pendingGhost = false },
            containerColor = Surface,
            title = { Text("Switch to Ghost mode?", color = TextPrimary) },
            text = {
                Text(
                    "Ghost routes every message through Tor only. If Tor cannot " +
                        "bootstrap on the current network the app will show " +
                        "\"Cannot reach relay\" instead of silently falling back to " +
                        "REALITY or direct WSS — that silent downgrade would defeat " +
                        "the privacy promise you are opting into.",
                    color = TextDim, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingGhost = false
                    selected = PrivacyMode.Ghost
                    scope.launch { applyPrivacyModeFromDetail(container, context, PrivacyMode.Ghost) }
                }) { Text("Switch", color = CyanAccent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingGhost = false }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = PhantomTokens.Spacing.comfortable),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        PhIconBack(color = TextPrimary, size = 22.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Privacy Mode",
                        color = TextPrimary,
                        style = PhantomType.headline,
                    )
                }
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // Header description.
            Text(
                text = "Choose how PHANTOM connects to the relay. Different modes " +
                    "trade latency for unlinkability.",
                color = TextDim,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(20.dp))

            // Three mode cards — full-width, with title + description.
            ModeCard(
                title = "Standard",
                tagline = "direct WSS → REALITY → Tor",
                description = "Lowest latency on clean networks; falls through to " +
                    "REALITY then Tor onion if direct WSS is blocked. Read receipts on. " +
                    "Default for new installations.",
                active = selected == PrivacyMode.Standard,
                onClick = {
                    if (selected == PrivacyMode.Standard) return@ModeCard
                    selected = PrivacyMode.Standard
                    scope.launch { applyPrivacyModeFromDetail(container, context, PrivacyMode.Standard) }
                },
            )
            Spacer(Modifier.height(10.dp))
            ModeCard(
                title = "Private",
                tagline = "REALITY → Tor",
                description = "Skip direct WSS entirely. The relay never sees your " +
                    "source IP. Read receipts suppressed.",
                active = selected == PrivacyMode.Private,
                onClick = {
                    if (selected == PrivacyMode.Private) return@ModeCard
                    selected = PrivacyMode.Private
                    scope.launch { applyPrivacyModeFromDetail(container, context, PrivacyMode.Private) }
                },
            )
            Spacer(Modifier.height(10.dp))
            ModeCard(
                title = "Ghost",
                tagline = "Tor onion only — no fallback",
                description = "Maximum unlinkability. If Tor cannot bootstrap on " +
                    "your network the app will show \"Cannot reach relay\" rather " +
                    "than silently downgrading to a less-private path. Read receipts " +
                    "suppressed.",
                active = selected == PrivacyMode.Ghost,
                onClick = {
                    if (selected == PrivacyMode.Ghost) return@ModeCard
                    pendingGhost = true
                },
            )

            Spacer(Modifier.height(24.dp))
            // Footer note explaining the active-transport visibility.
            Text(
                text = "The active transport is visible in the foreground notification " +
                    "(\"Online via Reality · Standard\"). Mode switches reset the " +
                    "active connection and walk the new chain.",
                color = TextDim.copy(alpha = 0.7f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = PhantomFontMono,
            )
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    tagline: String,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PhantomTokens.Colors.SurfaceElevated)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) CyanAccent else BorderSubtle,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
            if (active) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CyanAccent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "ACTIVE",
                        color = BgDeep,
                        fontSize = 8.sp,
                        fontFamily = PhantomFontMono,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.0.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = tagline,
            color = TextDim,
            fontSize = 11.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 0.4.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            color = PhantomTokens.Colors.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

private suspend fun applyPrivacyModeFromDetail(
    container: AppContainer,
    context: android.content.Context,
    mode: PrivacyMode,
) {
    container.setPrivacyMode(mode)
    val intent = Intent(context.applicationContext, PhantomMessagingService::class.java)
    context.applicationContext.startForegroundService(intent)
}
