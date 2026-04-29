// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.calls

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val identity by container.identityState.collectAsState()
    val userName = identity?.username ?: ""
    val selfAvatarBitmap by container.selfAvatar.collectAsState()
    val selfAvatarImage = remember(selfAvatarBitmap) { selfAvatarBitmap?.asImageBitmap() }
    var contacts by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }

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
                                scope.launch {
                                    container.callManager?.startCall(conv.theirPublicKeyHex, conv.theirUsername)
                                }
                                onNavigate(Screen.ActiveCall(conv.id, conv.theirUsername))
                            },
                        )
                    }

                    item { SectionHeader(text = "Recent") }
                    item { ComingSoonBanner() }
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
