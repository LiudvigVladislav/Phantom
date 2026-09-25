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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.R
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
    val snackbarHostState = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf(container.transportPreferences.privacyMode) }
    var pendingGhost by remember { mutableStateOf(false) }

    // Wait-time disclaimer surfaced on every mode switch. The chain walk
    // can take 30 s for Reality init alone; falling all the way through
    // to Tor on a censored network adds several minutes on top. Without
    // this snackbar the foreground notification ("Connecting via Tor… ·
    // Privacy") is the only feedback the user sees, which was confusing
    // when the chain takes >30 s on Tecno МТС (cross-device test 2026-05-10).
    fun snackForMode(mode: PrivacyMode) {
        val msg = when (mode) {
            PrivacyMode.Standard -> context.getString(R.string.privacy_mode_switch_standard)
            PrivacyMode.Private  -> context.getString(R.string.privacy_mode_switch_private)
            PrivacyMode.Ghost    -> context.getString(R.string.privacy_mode_switch_ghost)
        }
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    if (pendingGhost) {
        AlertDialog(
            onDismissRequest = { pendingGhost = false },
            containerColor = Surface,
            title = { Text(stringResource(R.string.privacy_mode_ghost_confirm_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.privacy_mode_ghost_confirm_body),
                    color = TextDim, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingGhost = false
                    selected = PrivacyMode.Ghost
                    snackForMode(PrivacyMode.Ghost)
                    scope.launch { applyPrivacyModeFromDetail(container, context, PrivacyMode.Ghost) }
                }) { Text(stringResource(R.string.privacy_mode_switch_button), color = CyanAccent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingGhost = false }) {
                    Text(stringResource(R.string.privacy_mode_cancel_button), color = TextDim)
                }
            },
        )
    }

    Scaffold(
        containerColor = BgDeep,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    containerColor = Surface,
                    contentColor = TextPrimary,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(data.visuals.message, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        },
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
                        text = stringResource(R.string.settings_privacy_mode),
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
                text = stringResource(R.string.privacy_mode_description),
                color = TextDim,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(20.dp))

            // Three mode cards — full-width, with title + description.
            ModeCard(
                title = stringResource(R.string.settings_privacy_standard),
                tagline = stringResource(R.string.privacy_mode_standard_tagline),
                description = stringResource(R.string.privacy_mode_standard_description),
                active = selected == PrivacyMode.Standard,
                onClick = {
                    if (selected == PrivacyMode.Standard) return@ModeCard
                    selected = PrivacyMode.Standard
                    snackForMode(PrivacyMode.Standard)
                    scope.launch { applyPrivacyModeFromDetail(container, context, PrivacyMode.Standard) }
                },
            )
            Spacer(Modifier.height(10.dp))
            ModeCard(
                title = stringResource(R.string.settings_privacy_private),
                tagline = stringResource(R.string.privacy_mode_private_tagline),
                description = stringResource(R.string.privacy_mode_private_description),
                active = selected == PrivacyMode.Private,
                onClick = {
                    if (selected == PrivacyMode.Private) return@ModeCard
                    selected = PrivacyMode.Private
                    snackForMode(PrivacyMode.Private)
                    scope.launch { applyPrivacyModeFromDetail(container, context, PrivacyMode.Private) }
                },
            )
            Spacer(Modifier.height(10.dp))
            ModeCard(
                title = stringResource(R.string.settings_privacy_ghost),
                tagline = stringResource(R.string.privacy_mode_ghost_tagline),
                description = stringResource(R.string.privacy_mode_ghost_description),
                active = selected == PrivacyMode.Ghost,
                onClick = {
                    if (selected == PrivacyMode.Ghost) return@ModeCard
                    pendingGhost = true
                },
            )

            Spacer(Modifier.height(24.dp))
            // Footer note explaining the active-transport visibility.
            Text(
                text = stringResource(R.string.privacy_mode_transport_note),
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
                        text = stringResource(R.string.privacy_mode_active),
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
