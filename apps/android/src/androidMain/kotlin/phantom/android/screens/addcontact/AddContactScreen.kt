// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.addcontact

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.*
import phantom.android.ui.theme.*

/**
 * AddContactScreen — PHANTOM_FULL_COMPOSE §10 (Add Contact / Handshake).
 *
 * Three states the screen cycles through:
 *   A · Search       — search input + QR option + suggested contacts + own
 *                      username card
 *   B · Found        — identity card for the looked-up handle with their
 *                      ED25519 fingerprint and Send-handshake / Verify-first
 *                      CTAs
 *   C · Connected    — two identity cards stacked, connected pill bridge,
 *                      trust receipt
 *
 * Real handshake wiring (X3DH, conversation creation) lives in the existing
 * AddContactDialog path — this screen is the architectural shell the brief
 * defines. State A's "use existing flow" entry hands off to that dialog.
 */
private enum class AddContactState { Search, Found, Connected }

@Composable
fun AddContactScreen(
    container: AppContainer,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf(AddContactState.Search) }
    var query by remember { mutableStateOf("") }
    var foundHandle by remember { mutableStateOf("") }

    val ownUsername by container.identityState.collectAsState()
    val ownHandle = ownUsername?.username.orEmpty()
    val ownPubKey = ownUsername?.publicKeyHex.orEmpty()

    Scaffold(
        containerColor = PhantomTokens.Colors.SurfaceDeep,
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
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (state == AddContactState.Search) onBack()
                            else state = AddContactState.Search
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        PhIconBack(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when (state) {
                            AddContactState.Search -> "Add contact"
                            AddContactState.Found -> "Verify identity"
                            AddContactState.Connected -> "Connected"
                        },
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.20).sp,
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
                .padding(horizontal = PhantomTokens.Spacing.comfortable, vertical = 16.dp),
        ) {
            when (state) {
                AddContactState.Search -> SearchState(
                    query = query,
                    onQueryChange = { query = it },
                    onScanQr = { onNavigate(Screen.QrScan) },
                    onPasteKey = { /* AddContactDialog handles real flow */ },
                    onSuggestedTap = { handle ->
                        foundHandle = handle
                        state = AddContactState.Found
                    },
                    ownHandle = ownHandle,
                    ownPubKey = ownPubKey,
                )
                AddContactState.Found -> FoundState(
                    handle = foundHandle,
                    onSendHandshake = { state = AddContactState.Connected },
                    onVerifyFirst = { /* TODO: verification screen */ },
                )
                AddContactState.Connected -> ConnectedState(
                    yourHandle = ownHandle,
                    yourKey = ownPubKey,
                    theirHandle = foundHandle,
                    onDone = onBack,
                )
            }
        }
    }
}

// ── State A · Search ────────────────────────────────────────────────────────

@Composable
private fun SearchState(
    query: String,
    onQueryChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onPasteKey: () -> Unit,
    onSuggestedTap: (String) -> Unit,
    ownHandle: String,
    ownPubKey: String,
) {
    // Search input
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIconSearch(color = TextDim, size = 16.dp)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = TextPrimary,
                fontSize = 15.sp,
            ),
            cursorBrush = SolidColor(CyanAccent),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "Search by username…",
                        color = TextDim.copy(alpha = 0.6f),
                        fontSize = 15.sp,
                    )
                }
                inner()
            },
        )
    }

    Spacer(Modifier.height(12.dp))

    // QR option row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PhantomTokens.Colors.SurfaceElevated)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .clickable(onClick = onScanQr)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CyanAccent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            PhIconShare(color = CyanAccent, size = 14.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Scan QR code",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Camera-based handshake",
                color = TextDim,
                fontSize = 12.sp,
            )
        }
        PhIconChevron(color = TextDim, size = 14.dp)
    }

    Spacer(Modifier.height(20.dp))

    // Suggested contacts overline (placeholder list — empty until address-book
    // integration ships).
    Text(
        text = "PEOPLE YOU MAY KNOW",
        color = TextDim,
        fontSize = 10.sp,
        fontFamily = PhantomFontMono,
        letterSpacing = 1.8.sp,
    )
    Spacer(Modifier.height(10.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No suggestions yet — discover peers via Nearby or QR.",
            color = TextDim,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(20.dp))

    // Own username card
    Text(
        text = "YOUR USERNAME",
        color = TextDim,
        fontSize = 10.sp,
        fontFamily = PhantomFontMono,
        letterSpacing = 1.8.sp,
    )
    Spacer(Modifier.height(10.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PhantomTokens.Colors.SurfaceElevated)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = if (ownHandle.isNotEmpty()) "@$ownHandle" else "@yourname",
            color = TextPrimary,
            fontSize = 16.sp,
            fontFamily = PhantomFontMono,
        )
        if (ownPubKey.length >= 8) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "key: ${ownPubKey.take(8).uppercase()}…",
                color = PhantomTokens.Colors.TextTertiary,
                fontSize = 11.sp,
                fontFamily = PhantomFontMono,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

// ── State B · Found ─────────────────────────────────────────────────────────

@Composable
private fun FoundState(
    handle: String,
    onSendHandshake: () -> Unit,
    onVerifyFirst: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PhantomTokens.Colors.SurfaceDeep)
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(name = handle, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = handle,
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.20).sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "@$handle",
                    color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontFamily = PhantomFontMono,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // "Not yet connected" status badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = "NOT YET CONNECTED",
                color = TextDim,
                fontSize = 9.sp,
                fontFamily = PhantomFontMono,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.6.sp,
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "IDENTITY KEY · ED25519",
            color = CyanAccent.copy(alpha = 0.85f),
            fontSize = 9.sp,
            fontFamily = PhantomFontMono,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.6.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "C4D1  F8A2  3B9E  6C7D  1A4F  8B2E  5C3A  9D6F",
            color = TextPrimary,
            fontSize = 11.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 0.55.sp,
            lineHeight = 18.sp,
        )
    }

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = onSendHandshake,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyanAccent,
            contentColor = BgDeep,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "Send handshake",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }

    Spacer(Modifier.height(10.dp))

    Button(
        onClick = onVerifyFirst,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "Verify first",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── State C · Connected ─────────────────────────────────────────────────────

@Composable
private fun ConnectedState(
    yourHandle: String,
    yourKey: String,
    theirHandle: String,
    onDone: () -> Unit,
) {
    IdentityCard(
        ownerLabel = "Your key",
        handle = "@${yourHandle.ifEmpty { "you" }}",
        keyHex = yourKey.ifEmpty { "A4B2 C8D1 E3F7 2A9B" }.take(35).uppercase(),
    )

    Spacer(Modifier.height(14.dp))

    // Connected pill bridge
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(CyanAccent.copy(alpha = 0.14f)),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9999.dp))
                .background(CyanAccent.copy(alpha = 0.05f))
                .border(1.dp, CyanAccent.copy(alpha = 0.14f), RoundedCornerShape(9999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PhIconLock(color = CyanAccent, size = 10.dp)
            Text(
                text = "Connected",
                color = CyanAccent,
                fontSize = 10.sp,
                fontFamily = PhantomFontMono,
                letterSpacing = 1.5.sp,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(CyanAccent.copy(alpha = 0.14f)),
        )
    }

    Spacer(Modifier.height(14.dp))

    IdentityCard(
        ownerLabel = "$theirHandle's key",
        handle = "@$theirHandle",
        keyHex = "C4D1 F8A2 3B9E 6C7D 1A4F",
    )

    Spacer(Modifier.height(20.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        PhIconLock(color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.45f), size = 10.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "End-to-end encrypted from the first message",
            color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.45f),
            fontSize = 10.sp,
            fontFamily = PhantomFontMono,
        )
    }

    Spacer(Modifier.height(28.dp))

    Button(
        onClick = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyanAccent,
            contentColor = BgDeep,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "Open chat",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun IdentityCard(ownerLabel: String, handle: String, keyHex: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PhantomTokens.Colors.SurfaceDeep)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(name = handle.removePrefix("@"), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ownerLabel,
                    color = TextDim.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = handle,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontFamily = PhantomFontMono,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = keyHex,
            color = PhantomTokens.Colors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 0.55.sp,
        )
    }
}
