// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.theme.*

/**
 * EmptyState — PHANTOM_FULL_COMPOSE §13.
 *
 * Shared empty-state pattern: ambient concentric-circle motif (5 rings,
 * 0.05 opacity, centered), 48dp icon textTertiary, primary text Geist 20px
 * textSecondary, secondary text Inter 14px textTertiary opacity 0.65, and
 * an optional ghost CTA.
 *
 * Four canonical variants are exposed as helpers below: EmptyChats,
 * EmptyCalls, EmptyContacts, EmptySearch.
 */
@Composable
fun EmptyState(
    icon: @Composable () -> Unit,
    primaryText: String,
    secondaryText: String,
    ctaLabel: String? = null,
    onCta: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PhantomTokens.Colors.SurfaceDeep),
        contentAlignment = Alignment.Center,
    ) {
        AmbientCircleMotif()

        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = primaryText,
                color = PhantomTokens.Colors.TextSecondary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.20).sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = secondaryText,
                color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.65f),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
            if (ctaLabel != null) {
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            PhantomTokens.Colors.Cyan.copy(alpha = 0.30f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable(onClick = onCta)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = ctaLabel,
                        color = PhantomTokens.Colors.Cyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Ambient motif drawing — 5 concentric rings at 0.05 opacity, used as a
 * subtle architectural backdrop for empty states. Restrained, never
 * dominant.
 */
@Composable
private fun AmbientCircleMotif() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = minOf(size.width, size.height) * 0.45f
        for (i in 1..5) {
            drawCircle(
                color = Color.White,
                radius = maxR * (i / 5f),
                center = Offset(cx, cy),
                style = Stroke(width = 0.75.dp.toPx()),
                alpha = 0.05f,
            )
        }
    }
}

@Composable
fun EmptyChats(onAddContact: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        icon = { PhIconMessage(color = PhantomTokens.Colors.TextTertiary, size = 40.dp) },
        primaryText = "No conversations yet",
        secondaryText = "Add a contact to start a private, encrypted conversation.",
        ctaLabel = "Add contact",
        onCta = onAddContact,
        modifier = modifier,
    )
}

@Composable
fun EmptyCalls(modifier: Modifier = Modifier) {
    EmptyState(
        icon = { PhIconPhone(color = PhantomTokens.Colors.TextTertiary, size = 40.dp) },
        primaryText = "No call history",
        secondaryText = "All calls are end-to-end encrypted. No record is stored.",
        modifier = modifier,
    )
}

@Composable
fun EmptyContacts(onFindPeople: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        icon = { PhIconPerson(color = PhantomTokens.Colors.TextTertiary, size = 40.dp) },
        primaryText = "No contacts yet",
        secondaryText = "Search for a username or scan a QR code to connect.",
        ctaLabel = "Find people",
        onCta = onFindPeople,
        modifier = modifier,
    )
}

@Composable
fun EmptySearch(modifier: Modifier = Modifier) {
    EmptyState(
        icon = { PhIconSearch(color = PhantomTokens.Colors.TextTertiary, size = 40.dp) },
        primaryText = "No results",
        secondaryText = "Try a different username or check your spelling.",
        modifier = modifier,
    )
}
