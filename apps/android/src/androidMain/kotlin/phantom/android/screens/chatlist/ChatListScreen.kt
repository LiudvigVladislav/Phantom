package phantom.android.screens.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.DeliveryIcon
import phantom.android.ui.DeliveryStatus
import phantom.android.ui.GradientAvatar
import phantom.android.ui.theme.*
import phantom.core.storage.ConversationEntity
import phantom.core.storage.MessageStatus
import phantom.core.storage.TrustTier

@Composable
fun ChatListScreen(
    container: AppContainer,
    onNavigate: (Screen) -> Unit,
    onProfile: () -> Unit = {},
    onScanQr: () -> Unit = {},
    scannedQr: String? = null,
    onScannedQrConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    var requestCount by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var prefillContactString by remember { mutableStateOf("") }

    LaunchedEffect(scannedQr) {
        if (scannedQr != null) {
            prefillContactString = scannedQr
            showAddDialog = true
            onScannedQrConsumed()
        }
    }

    suspend fun reload() {
        conversations = container.conversationRepo.getActiveConversations()
        requestCount = container.conversationRepo.getMessageRequests().size
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(container.messagingService) {
        container.messagingService?.incomingMessages?.collect { reload() }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ───────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PHANTOM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 6.sp,
                    color = TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onScanQr) {
                    Icon(Icons.Default.Email, contentDescription = "Scan QR", tint = TextDim, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onProfile) {
                    Icon(Icons.Default.Person, contentDescription = "Profile", tint = TextDim, modifier = Modifier.size(22.dp))
                }
            }

            // ── Search pill ───────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                    .clickable { /* search — TODO */ }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Search messages, contacts",
                    color = TextDim,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Default,
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // ── Notes (saved messages) ─────────────────────
                item(key = "__notes__") {
                    NotesRow(onClick = { onNavigate(Screen.SavedMessages) })
                }

                // ── Message requests banner ────────────────────
                if (requestCount > 0) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CyanAccent.copy(alpha = 0.07f))
                                .border(1.dp, CyanAccent.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                                .clickable { onNavigate(Screen.MessageRequests) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(CyanAccent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Email, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Message requests", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("$requestCount new from unverified peers", color = TextDim, fontSize = 12.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(CyanAccent)
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = if (requestCount > 99) "99+" else requestCount.toString(),
                                    color = BgDeep,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // ── Date separator ─────────────────────────────
                if (conversations.isNotEmpty()) {
                    item { DateSeparator("Chats") }
                }

                items(conversations, key = { it.id }) { conv ->
                    ChatRow(
                        conv = conv,
                        onClick = { onNavigate(Screen.Chat(conv.id, conv.theirUsername)) },
                    )
                }

                if (conversations.isEmpty() && requestCount == 0) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No conversations yet", color = TextDim, fontSize = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Tap + to add a contact by key or QR code",
                                    color = TextDim.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── FAB ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(CyanAccent)
                .clickable { showAddDialog = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add contact", tint = BgDeep, modifier = Modifier.size(26.dp))
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            prefill = prefillContactString,
            container = container,
            onDismiss = { showAddDialog = false; prefillContactString = "" },
            onAdded = { convId: String, username: String ->
                showAddDialog = false
                prefillContactString = ""
                scope.launch { reload() }
                onNavigate(Screen.Chat(convId, username))
            },
        )
    }
}

@Composable
private fun NotesRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(CyanAccent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(22.dp)) {
                val w = size.width; val h = size.height
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.2f, 0f); lineTo(w * 0.8f, 0f)
                    lineTo(w * 0.8f, h); lineTo(w * 0.5f, h * 0.72f)
                    lineTo(w * 0.2f, h); close()
                }
                drawPath(path = path, color = CyanAccent)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Notes", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Normal)
            Text("Personal notes & saved", color = TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DateSeparator(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.04f))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 2.5.sp,
            color = TextDim,
        )
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.04f))
    }
}

@Composable
private fun ChatRow(conv: ConversationEntity, onClick: () -> Unit) {
    val isUnread = conv.unreadCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAvatar(
            name = conv.theirUsername,
            size = 46.dp,
            online = null,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conv.theirUsername,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = if (isUnread) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                val timeStr = conv.lastMessageAt?.let { formatChatTime(it) } ?: ""
                Text(
                    text = timeStr,
                    color = if (isUnread) CyanAccent else TextDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Default,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conv.lastMessagePreview ?: "",
                    color = TextDim,
                    fontSize = 13.sp,
                    fontWeight = if (isUnread) FontWeight.Normal else FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isUnread) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(CyanAccent)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (conv.unreadCount > 99) "99+" else conv.unreadCount.toString(),
                            color = BgDeep,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private fun formatChatTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val dayMs = 86_400_000L
    return when {
        diff < dayMs -> {
            val date = java.util.Date(millis)
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(date)
        }
        diff < 7 * dayMs -> {
            val date = java.util.Date(millis)
            java.text.SimpleDateFormat("EEE", java.util.Locale.US).format(date)
        }
        else -> {
            val date = java.util.Date(millis)
            java.text.SimpleDateFormat("dd MMM", java.util.Locale.US).format(date)
        }
    }
}
