// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.theme.*

/**
 * Utility bottom sheets — PHANTOM_FULL_COMPOSE §14.
 *
 * Pattern: ModalBottomSheet on dimmed content. Sheet bg = surfaceElevated,
 * 20dp top corners, no bottom radius. 36×4dp drag handle (cosmetic — the
 * Material3 sheet provides its own).
 *
 * Three variants surfaced here: NotificationPermissionSheet, BackupExportSheet,
 * SearchActiveSheet.
 *
 * Each sheet is a stateless composable taking onDismiss and onPrimary. Wire
 * them up at the call site whenever the matching prompt is needed.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPermissionSheet(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PhantomTokens.Colors.SurfaceElevated,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        SheetBody {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                PhIconBell(color = PhantomTokens.Colors.TextSecondary, size = 32.dp)
            }
            Spacer(Modifier.height(14.dp))
            SheetTitle("Stay in the loop")
            Spacer(Modifier.height(8.dp))
            SheetBodyText(
                "Get notified when contacts message you. Notifications are silent and never include message content.",
            )
            Spacer(Modifier.height(20.dp))
            SheetPrimaryCta(label = "Allow notifications", onClick = onAllow)
            Spacer(Modifier.height(8.dp))
            SheetGhostCta(label = "Not now", onClick = onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupExportSheet(
    fingerprint: String,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PhantomTokens.Colors.SurfaceElevated,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        SheetBody {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                PhIconDownload(color = PhantomTokens.Colors.TextSecondary, size = 30.dp)
            }
            Spacer(Modifier.height(14.dp))
            SheetTitle("Backup your identity key")
            Spacer(Modifier.height(8.dp))
            SheetBodyText(
                "Your ED25519 key is stored only on this device. If you lose access, we cannot recover it. Export an encrypted backup so you can move to a new device.",
            )
            Spacer(Modifier.height(16.dp))
            // Fingerprint card — cyan border, mono fingerprint.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhantomTokens.Colors.SurfaceDeep)
                    .border(
                        1.dp,
                        PhantomTokens.Colors.Cyan.copy(alpha = 0.22f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "ED25519 · YOUR KEY",
                    color = PhantomTokens.Colors.Cyan.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    fontFamily = PhantomFontMono,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = fingerprint.ifEmpty { "A4B2  C8D1  E3F7  2A9B  4C6D  8E1F" },
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 0.55.sp,
                )
            }
            Spacer(Modifier.height(20.dp))
            // Encrypted backup export ships with recovery phrase in Phase 3
            // (Sep 2026) per ADR-012. The sheet describes the future feature
            // honestly; the CTA stays disabled until then.
            SheetPrimaryCta(
                label = "Available Sep 2026",
                onClick = onExport,
                enabled = false,
            )
            Spacer(Modifier.height(8.dp))
            SheetGhostCta(label = "Close", onClick = onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchActiveSheet(
    initialQuery: String = "",
    recentQueries: List<String> = emptyList(),
    onPickRecent: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onSubmit: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PhantomTokens.Colors.SurfaceElevated,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        SheetBody {
            // Search input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhantomTokens.Colors.SurfaceDeep)
                    .border(
                        1.dp,
                        PhantomTokens.Colors.Cyan.copy(alpha = 0.20f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhIconSearch(color = PhantomTokens.Colors.Cyan, size = 16.dp)
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = TextPrimary,
                        fontSize = 15.sp,
                    ),
                    cursorBrush = SolidColor(PhantomTokens.Colors.Cyan),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "Search…",
                                color = TextDim.copy(alpha = 0.6f),
                                fontSize = 15.sp,
                            )
                        }
                        inner()
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            if (recentQueries.isNotEmpty()) {
                Text(
                    text = "RECENT",
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.8.sp,
                )
                Spacer(Modifier.height(8.dp))
                recentQueries.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickRecent(item) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhIconSearch(color = TextDim, size = 14.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = item,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Clear history",
                    color = TextDim.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontFamily = PhantomFontMono,
                    modifier = Modifier.clickable { onClearHistory() },
                )
            } else {
                Text(
                    text = "Type to search contacts, messages, or fingerprints.",
                    color = TextDim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

// ── Shared sheet primitives ─────────────────────────────────────────────────

@Composable
private fun SheetBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 4.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.20).sp,
    )
}

@Composable
private fun SheetBodyText(text: String) {
    Text(
        text = text,
        color = TextDim,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun SheetPrimaryCta(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PhantomTokens.Colors.Cyan,
            contentColor = BgDeep,
            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledContentColor = TextDim,
        ),
        border = if (!enabled)
            androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle) else null,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SheetGhostCta(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = TextDim,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
