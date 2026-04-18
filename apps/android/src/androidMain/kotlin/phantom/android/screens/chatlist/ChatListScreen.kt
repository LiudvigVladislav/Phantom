package phantom.android.screens.chatlist

import androidx.compose.foundation.background
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.theme.*
import phantom.core.storage.ConversationEntity
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

    // When QR scan result arrives — open AddContactDialog pre-filled
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

    Scaffold(
        containerColor = BgDeep,
        topBar = { ChatListTopBar(onProfile = onProfile, onScanQr = onScanQr) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = CyanAccent,
                contentColor = BgDeep,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add contact")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Message Requests banner
            if (requestCount > 0) {
                MessageRequestsBanner(
                    count = requestCount,
                    onClick = { onNavigate(Screen.MessageRequests) },
                )
            }

            // Saved Messages pinned row
            SavedMessagesRow(onClick = { onNavigate(Screen.SavedMessages) })
            HorizontalDivider(color = Color.White.copy(alpha = 0.04f), thickness = 1.dp)

            if (conversations.isEmpty() && requestCount == 0) {
                EmptyState(modifier = Modifier.weight(1f))
            } else if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No active conversations", color = TextDim, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        ConversationRow(
                            entity = conv,
                            onClick = { onNavigate(Screen.Chat(conv.id, conv.theirUsername)) },
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.04f), thickness = 1.dp)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            prefill = prefillContactString,
            onDismiss = { showAddDialog = false; prefillContactString = "" },
            onAdd = { pubKeyHex, displayName ->
                scope.launch {
                    val convId = buildConversationId(container, pubKeyHex)
                    container.conversationRepo.upsertConversation(
                        ConversationEntity(
                            id = convId,
                            theirUsername = displayName,
                            theirPublicKeyHex = pubKeyHex,
                            lastMessagePreview = null,
                            lastMessageAt = null,
                            unreadCount = 0,
                            trustTier = TrustTier.TRUSTED,
                            blocked = false,
                        )
                    )
                    reload()
                    showAddDialog = false
                    prefillContactString = ""
                    onNavigate(Screen.Chat(convId, displayName))
                }
            }
        )
    }
}


@Composable
private fun MessageRequestsBanner(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(CyanAccent.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(CyanAccent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Email,
                contentDescription = null,
                tint = CyanAccent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Message Requests", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = if (count == 1) "1 pending request" else "$count pending requests",
                color = TextDim,
                fontSize = 12.sp,
            )
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
                .clip(CircleShape)
                .background(CyanAccent)
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                color = BgDeep,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.04f), thickness = 1.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListTopBar(onProfile: () -> Unit, onScanQr: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "PHANTOM",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraLight,
                letterSpacing = 5.sp,
            )
        },
        actions = {
            IconButton(onClick = onScanQr) {
                // QR scan icon — 3 finder squares + center dot, drawn with Canvas
                Canvas(modifier = androidx.compose.ui.Modifier.size(24.dp)) {
                    val s = size
                    val c = TextDim
                    val sw = 1.8.dp.toPx()
                    val st = Stroke(width = sw)
                    val cr = CornerRadius(2.4.dp.toPx())
                    val box = s.width * 0.36f
                    val gap = sw / 2f
                    // top-left finder square
                    drawRoundRect(color = c, topLeft = androidx.compose.ui.geometry.Offset(gap, gap), size = androidx.compose.ui.geometry.Size(box, box), cornerRadius = cr, style = st)
                    // top-right finder square
                    drawRoundRect(color = c, topLeft = androidx.compose.ui.geometry.Offset(s.width - box - gap, gap), size = androidx.compose.ui.geometry.Size(box, box), cornerRadius = cr, style = st)
                    // bottom-left finder square
                    drawRoundRect(color = c, topLeft = androidx.compose.ui.geometry.Offset(gap, s.height - box - gap), size = androidx.compose.ui.geometry.Size(box, box), cornerRadius = cr, style = st)
                    // inner fill dots for finder squares
                    val dotR = box * 0.22f
                    drawCircle(color = c, radius = dotR, center = androidx.compose.ui.geometry.Offset(gap + box / 2f, gap + box / 2f))
                    drawCircle(color = c, radius = dotR, center = androidx.compose.ui.geometry.Offset(s.width - box / 2f - gap, gap + box / 2f))
                    drawCircle(color = c, radius = dotR, center = androidx.compose.ui.geometry.Offset(gap + box / 2f, s.height - box / 2f - gap))
                    // bottom-right data dots (simplified)
                    val br = s.width - box - gap
                    val bb = s.height - gap
                    val d = box / 3f
                    drawCircle(color = c, radius = sw * 0.9f, center = androidx.compose.ui.geometry.Offset(br + d, bb - box + d))
                    drawCircle(color = c, radius = sw * 0.9f, center = androidx.compose.ui.geometry.Offset(br + d * 2.4f, bb - box + d * 2.4f))
                    drawCircle(color = c, radius = sw * 0.9f, center = androidx.compose.ui.geometry.Offset(br + d, bb - d))
                }
            }
            IconButton(onClick = onProfile) {
                Icon(Icons.Default.Person, contentDescription = "Profile", tint = TextDim)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
    )
}

@Composable
private fun ConversationRow(entity: ConversationEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(BgDeep)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(CyanAccent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entity.theirUsername.take(1).uppercase(),
                color = CyanAccent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entity.theirUsername,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (entity.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
            )
            entity.lastMessagePreview?.let { preview ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preview,
                    color = TextDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }

        if (entity.unreadCount > 0) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
                    .clip(CircleShape)
                    .background(CyanAccent)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (entity.unreadCount > 99) "99+" else entity.unreadCount.toString(),
                    color = BgDeep,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "No conversations yet", color = TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(text = "Tap + to add a contact", color = TextDim.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun AddContactDialog(prefill: String = "", onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    // Raw paste field — accepts "username:pubkey_hex" or plain "pubkey_hex"
    var pasteValue by remember { mutableStateOf(prefill) }
    var localAlias by remember { mutableStateOf("") }

    // Parse "username:key" format on the fly
    val parsed = remember(pasteValue) { parseContactString(pasteValue.trim()) }
    val resolvedKey = parsed?.second ?: ""
    val resolvedName = parsed?.first ?: ""
    val isValid = resolvedKey.length >= 64

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Add contact", color = TextPrimary) },
        text = {
            Column {
                Text(
                    "Paste their key (from Profile → Share my key)",
                    color = TextDim,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasteValue,
                    onValueChange = { pasteValue = it },
                    placeholder = { Text("username:key or key…", color = TextDim) },
                    singleLine = true,
                    isError = pasteValue.isNotEmpty() && !isValid,
                    supportingText = {
                        when {
                            pasteValue.isEmpty() -> {}
                            isValid && resolvedName.isNotEmpty() ->
                                Text("✓  @$resolvedName", color = Success, fontSize = 11.sp)
                            isValid ->
                                Text("✓  Key recognised", color = Success, fontSize = 11.sp)
                            else ->
                                Text("Key looks too short", color = Danger, fontSize = 11.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = if (isValid) Success else CyanAccent,
                        unfocusedBorderColor = TextDim.copy(alpha = 0.4f),
                        errorBorderColor = Danger,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Text("Local nickname (optional — overrides their name)", color = TextDim, fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = localAlias,
                    onValueChange = { localAlias = it },
                    placeholder = {
                        Text(
                            if (resolvedName.isNotEmpty()) "@$resolvedName" else "Leave blank to use their name",
                            color = TextDim.copy(alpha = 0.5f),
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = TextDim.copy(alpha = 0.4f),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isValid) {
                        val displayName = localAlias.ifBlank { resolvedName.ifBlank { resolvedKey.take(8) } }
                        onAdd(resolvedKey, displayName)
                    }
                },
                enabled = isValid,
            ) {
                Text("Add", color = if (isValid) CyanAccent else TextDim)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDim)
            }
        },
    )
}

/** Parses "username:pubkey_hex" or plain "pubkey_hex". Returns Pair(username, key) or null. */
private fun parseContactString(input: String): Pair<String, String>? {
    if (input.isEmpty()) return null
    val colonIndex = input.indexOf(':')
    return if (colonIndex > 0 && colonIndex < input.length - 1) {
        val name = input.substring(0, colonIndex)
        val key = input.substring(colonIndex + 1)
        Pair(name, key)
    } else {
        Pair("", input)
    }
}

@Composable
private fun SavedMessagesRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(BgDeep)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(CyanAccent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            // Bookmark icon drawn with Canvas
            Canvas(modifier = Modifier.size(22.dp)) {
                val w = size.width
                val h = size.height
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.2f, 0f)
                    lineTo(w * 0.8f, 0f)
                    lineTo(w * 0.8f, h)
                    lineTo(w * 0.5f, h * 0.72f)
                    lineTo(w * 0.2f, h)
                    close()
                }
                drawPath(path = path, color = CyanAccent)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Notes", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Normal)
            Text("Personal notes & saved", color = TextDim, fontSize = 12.sp)
        }
    }
}

private suspend fun buildConversationId(container: AppContainer, theirKey: String): String {
    val myKey = container.identityRepo.loadIdentity()?.publicKeyHex ?: ""
    return listOf(myKey, theirKey).sorted().joinToString("_")
}
