// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.calls

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import phantom.android.calls.ActiveCall
import phantom.android.calls.CallState
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.*
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono
import phantom.core.storage.ConversationEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    container: AppContainer,
    onNavigate: (Screen) -> Unit,
    onProfile: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val identity by container.identityState.collectAsState()
    val userName = identity?.username ?: ""
    val selfAvatarBitmap by container.selfAvatar.collectAsState()
    val selfAvatarImage = remember(selfAvatarBitmap) { selfAvatarBitmap?.asImageBitmap() }
    var contacts by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }

    // Holds the conversation waiting for mic permission so the grant callback can start the call.
    var pendingCallConv by remember { mutableStateOf<ConversationEntity?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val conv = pendingCallConv ?: return@rememberLauncherForActivityResult
        pendingCallConv = null
        if (granted) {
            scope.launch { container.callManager?.startCall(conv.theirPublicKeyHex, conv.theirUsername) }
            onNavigate(Screen.ActiveCall(conv.id, conv.theirUsername))
        } else {
            Toast.makeText(context, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        contacts = runCatching { container.conversationRepo.getActiveConversations() }.getOrElse { emptyList() }
    }

    // Monitor incoming calls — navigate to IncomingCallScreen when RINGING
    val cm = container.callManager
    val activeCall by (cm?.activeCall ?: MutableStateFlow<ActiveCall?>(null)).collectAsState()
    LaunchedEffect(activeCall) {
        val call = activeCall
        if (call != null && call.state == CallState.RINGING) {
            onNavigate(Screen.IncomingCall(call.remotePubKeyHex, call.remoteUsername))
        }
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            PhantomTopBar(
                userName = userName,
                title = "Calls",
                onProfile = onProfile,
                onAddContact = { onNavigate(Screen.ChatList) },
                onScanQr = { onNavigate(Screen.QrScan) },
                avatarBitmap = selfAvatarImage,
                avatarMenuContent = { close ->
                    DropdownMenuItem(
                        leadingIcon = { PhIconPhone(color = TextDim, size = 15.dp) },
                        text = { Text("Missed only", fontSize = 14.sp) },
                        onClick = { close() },
                    )
                    DropdownMenuItem(
                        leadingIcon = { PhIconCheck3(color = TextDim, size = 15.dp) },
                        text = { Text("Select calls", fontSize = 14.sp) },
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
                trailing = {
                    // Calls trailing → "new call" pencil that jumps to chat
                    // list so user picks a contact, no SOON-gated dropdown.
                    IconButton(onClick = { onNavigate(Screen.ChatList) }) {
                        PhIconPhone(color = CyanAccent, size = 22.dp)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (contacts.isEmpty()) {
                // No contacts → show the canonical EmptyCalls state behind the
                // bottom-nav pill. Keeps the Calls tab feeling architectural
                // even before any call history exists.
                EmptyCalls()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 180.dp),
                ) {
                    item {
                        SectionHeader(text = "Contacts")
                    }
                    items(contacts, key = { it.id }) { conv ->
                        ContactCallRow(
                            conv = conv,
                            onCall = {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    scope.launch {
                                        container.callManager?.startCall(conv.theirPublicKeyHex, conv.theirUsername)
                                    }
                                    onNavigate(Screen.ActiveCall(conv.id, conv.theirUsername))
                                } else {
                                    pendingCallConv = conv
                                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        )
                    }

                    item { SectionHeader(text = "Recent") }
                    item {
                        // No call_log table yet — render the canonical
                        // CallHistoryRow only when entries exist. For now,
                        // the section reads honestly that history starts
                        // when the user makes their first call.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No calls yet · all calls are end-to-end encrypted",
                                color = TextDim,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = PhantomFontMono,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }

            // Bottom nav pill
            BottomNavPill(
                activeTab = NavTab.CALLS,
                onTabSelected = { tab ->
                    when (tab) {
                        NavTab.CHATS    -> onNavigate(Screen.ChatList)
                        NavTab.NEARBY   -> onNavigate(Screen.Nearby)
                        NavTab.SETTINGS -> onNavigate(Screen.Settings)
                        NavTab.CALLS    -> {}
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ComingSoonBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .border(1.dp, CyanAccent.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhIconPhone(color = CyanAccent.copy(alpha = 0.5f), size = 36.dp)
            Text(
                text = "Call history — coming soon",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Stay tuned for updates",
                color = TextDim,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 6.dp),
        fontFamily = PhantomFontMono,
        fontSize = 10.sp,
        letterSpacing = 2.8.sp,
        color = TextDim,
    )
}

// ── Call history (FULL_COMPOSE Calls/History) ───────────────────────────────
// Direction-typed history entry + canonical row composable. No data source
// yet — call_log table lands with the recents milestone — so these are
// dormant. Defined now so the wire-up is a single LazyColumn item insertion
// when the storage layer ships.

enum class CallDirection { INCOMING, OUTGOING, MISSED }

data class CallHistoryEntry(
    val id: String,
    val theirUsername: String,
    val direction: CallDirection,
    val occurredAt: Long,
    val durationSeconds: Long,
)

@Suppress("unused")
@Composable
private fun CallDateGroupHeader(label: String) {
    // Mono 10sp tertiary 1.6sp tracking, uppercase. Matches FULL_COMPOSE
    // Calls/History grouping.
    Text(
        text = label.uppercase(),
        color = TextDim,
        fontSize = 10.sp,
        fontFamily = PhantomFontMono,
        letterSpacing = 1.6.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Suppress("unused")
@Composable
private fun CallHistoryRow(entry: CallHistoryEntry, onCallback: () -> Unit) {
    val nameColor = if (entry.direction == CallDirection.MISSED) Danger else TextPrimary
    val directionLabel = when (entry.direction) {
        CallDirection.INCOMING -> "Incoming"
        CallDirection.OUTGOING -> "Outgoing"
        CallDirection.MISSED -> "Missed"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAvatar(name = entry.theirUsername, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.theirUsername,
                color = nameColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (entry.direction) {
                    CallDirection.INCOMING -> PhIconArrowIn(color = Success, size = 12.dp)
                    CallDirection.OUTGOING -> PhIconArrowOut(color = TextDim, size = 12.dp)
                    CallDirection.MISSED -> PhIconArrowIn(color = Danger, size = 12.dp)
                }
                Text(
                    text = directionLabel,
                    color = TextDim,
                    fontSize = 13.sp,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = formatCallDate(entry.occurredAt),
                color = TextDim,
                fontSize = 11.sp,
                fontFamily = PhantomFontMono,
            )
            if (entry.durationSeconds > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatCallDuration(entry.durationSeconds),
                    color = TextDim.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = onCallback, modifier = Modifier.size(36.dp)) {
            PhIconPhone(color = CyanAccent, size = 18.dp)
        }
    }
}

private fun formatCallDate(millis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val now = java.util.Calendar.getInstance()
    val sameDay = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
    } else {
        java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}

private fun formatCallDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun ContactCallRow(conv: ConversationEntity, onCall: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GradientAvatar(name = conv.theirUsername, size = 44.dp)

        Text(
            text = conv.theirUsername,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(CyanAccent.copy(alpha = 0.12f))
                .border(1.dp, CyanAccent.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                .clickable(onClick = onCall)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhIconPhone(color = CyanAccent, size = 14.dp)
                Text(
                    text = "Call",
                    color = CyanAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
