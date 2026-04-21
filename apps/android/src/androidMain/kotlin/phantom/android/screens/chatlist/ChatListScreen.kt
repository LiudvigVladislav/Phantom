package phantom.android.screens.chatlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.*
import phantom.android.ui.theme.*
import phantom.core.storage.ConversationEntity

@OptIn(ExperimentalMaterial3Api::class)
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
    var searchQuery by remember { mutableStateOf("") }
    var conversations by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    val filtered = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter { conv ->
            conv.theirUsername.contains(searchQuery, ignoreCase = true) ||
            (conv.lastMessagePreview ?: "").contains(searchQuery, ignoreCase = true)
        }
    }
    var requestCount by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var prefillContactString by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        userName = container.identityRepo.loadIdentity()?.username ?: ""
    }

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
        topBar = {
            PhantomTopBar(
                userName = userName,
                onProfile = onProfile,
                onAddContact = { showAddDialog = true },
                onScanQr = onScanQr,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp),
            ) {
                // ── Search pill ──────────────────────────────
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 12.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface)
                            .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = TextPrimary,
                                fontSize = 13.sp,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(CyanAccent),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search messages, contacts",
                                        color = TextDim,
                                        fontSize = 13.sp,
                                    )
                                }
                                inner()
                            },
                        )
                    }
                }

                // ── PINNED section ───────────────────────────
                item { SectionLabel(text = "Pinned", showPin = true) }

                item(key = "__notes__") {
                    NotesRow(onClick = { onNavigate(Screen.SavedMessages) })
                }

                item(key = "__archive__") {
                    ArchiveRow(onClick = { onNavigate(Screen.Archive) })
                }

                // ── Message requests banner ──────────────────
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
                                Canvas(modifier = Modifier.size(18.dp)) { drawQrFinderDots(CyanAccent) }
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

                // ── CHATS section ────────────────────────────
                item { SectionLabel(text = "Chats") }

                items(filtered, key = { it.id }) { conv ->
                    ChatRow(
                        conv = conv,
                        onClick = { onNavigate(Screen.Chat(conv.id, conv.theirUsername)) },
                    )
                }

                if (filtered.isEmpty() && searchQuery.isNotBlank()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No results for \"$searchQuery\"",
                                color = TextDim,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                if (conversations.isEmpty() && requestCount == 0 && searchQuery.isBlank()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No conversations yet", color = TextDim, fontSize = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Tap the compose button to add a contact",
                                    color = TextDim.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }

            // ── Floating bottom nav ──────────────────────────
            BottomNavPill(
                activeTab = NavTab.CHATS,
                onTabSelected = { tab ->
                    when (tab) {
                        NavTab.CALLS -> onNavigate(Screen.Calls)
                        NavTab.SETTINGS -> onNavigate(Screen.Settings)
                        NavTab.CHATS -> {}
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
private fun SectionLabel(text: String, showPin: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showPin) {
            Canvas(modifier = Modifier.size(10.dp)) {
                val w = size.width; val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.67f, 0f)
                    lineTo(w, h * 0.42f)
                    lineTo(w * 0.60f, h * 0.42f)
                    lineTo(w * 0.55f, h)
                    lineTo(w * 0.45f, h)
                    lineTo(w * 0.40f, h * 0.42f)
                    lineTo(0f, h * 0.42f)
                    close()
                }
                drawPath(path, color = TextDim)
            }
        }
        Text(
            text = text.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 2.8.sp,
            color = TextDim,
        )
    }
}

@Composable
private fun NotesRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(CyanAccent.copy(alpha = 0.10f))
                .border(1.dp, CyanAccent.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Bookmark icon
            Canvas(modifier = Modifier.size(22.dp)) {
                val w = size.width; val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.25f, 0f)
                    lineTo(w * 0.75f, 0f)
                    lineTo(w * 0.75f, h)
                    lineTo(w * 0.5f, h * 0.72f)
                    lineTo(w * 0.25f, h)
                    close()
                }
                drawPath(path, color = CyanAccent)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Notes", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Canvas(modifier = Modifier.size(9.dp)) {
                    val path = Path().apply {
                        moveTo(size.width * 0.67f, 0f)
                        lineTo(size.width, size.height * 0.42f)
                        lineTo(size.width * 0.60f, size.height * 0.42f)
                        lineTo(size.width * 0.55f, size.height)
                        lineTo(size.width * 0.45f, size.height)
                        lineTo(size.width * 0.40f, size.height * 0.42f)
                        lineTo(0f, size.height * 0.42f)
                        close()
                    }
                    drawPath(path, color = CyanAccent)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text("Personal notes & saved", color = TextDim, fontSize = 13.sp)
        }
        Text("Wed", color = TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun ArchiveRow(onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Surface2)
                .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Folder icon (Canvas)
            Canvas(modifier = Modifier.size(20.dp)) {
                val w = size.width; val h = size.height
                val stroke = Stroke(width = 1.6.dp.toPx())
                val path = Path().apply {
                    moveTo(w * 0.125f, h * 0.292f)
                    lineTo(w * 0.125f, h * 0.875f)
                    lineTo(w * 0.875f, h * 0.875f)
                    lineTo(w * 0.875f, h * 0.375f)
                    lineTo(w * 0.5f, h * 0.375f)
                    lineTo(w * 0.375f, h * 0.25f)
                    lineTo(w * 0.125f, h * 0.25f)
                    close()
                }
                drawPath(path, color = TextDim, style = stroke)
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = "Archive",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = TextDim,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ChatRow(conv: ConversationEntity, onClick: () -> Unit) {
    val isUnread = conv.unreadCount > 0
    val context = LocalContext.current

    // Read contact real name from persisted profile card
    val contactDisplayName = remember(conv.id) {
        val raw = context.getSharedPreferences("phantom_prefs", android.content.Context.MODE_PRIVATE)
            .getString("contact_profile_${conv.id}", null)
        if (raw != null) {
            try {
                val obj = JSONObject(raw)
                val fn = obj.optString("fn", "")
                val ln = obj.optString("ln", "")
                when {
                    fn.isNotEmpty() && ln.isNotEmpty() -> "$fn $ln"
                    fn.isNotEmpty() -> fn
                    ln.isNotEmpty() -> ln
                    else -> null
                }
            } catch (_: JSONException) {
                null
            }
        } else {
            null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientAvatar(name = conv.theirUsername, size = 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conv.theirUsername,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
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
                    fontWeight = if (isUnread) FontWeight.Medium else FontWeight.Normal,
                )
            }
            // Real name subtitle — shown only when profile card has been received
            if (contactDisplayName != null) {
                Text(
                    text = contactDisplayName,
                    color = TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conv.lastMessagePreview ?: "",
                    color = TextDim,
                    fontSize = 13.sp,
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQrFinderDots(color: Color) {
    val s = size
    val sw = 1.8.dp.toPx()
    val st = Stroke(width = sw)
    val cr = androidx.compose.ui.geometry.CornerRadius(2.4.dp.toPx())
    val box = s.width * 0.36f
    val gap = sw / 2f
    drawRoundRect(color = color, topLeft = Offset(gap, gap), size = Size(box, box), cornerRadius = cr, style = st)
    drawRoundRect(color = color, topLeft = Offset(s.width - box - gap, gap), size = Size(box, box), cornerRadius = cr, style = st)
    drawRoundRect(color = color, topLeft = Offset(gap, s.height - box - gap), size = Size(box, box), cornerRadius = cr, style = st)
    val dotR = box * 0.22f
    drawCircle(color = color, radius = dotR, center = Offset(gap + box / 2f, gap + box / 2f))
    drawCircle(color = color, radius = dotR, center = Offset(s.width - box / 2f - gap, gap + box / 2f))
    drawCircle(color = color, radius = dotR, center = Offset(gap + box / 2f, s.height - box / 2f - gap))
}

private fun formatChatTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val dayMs = 86_400_000L
    return when {
        diff < dayMs -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(millis))
        diff < 7 * dayMs -> java.text.SimpleDateFormat("EEE", java.util.Locale.US).format(java.util.Date(millis))
        else -> java.text.SimpleDateFormat("dd MMM", java.util.Locale.US).format(java.util.Date(millis))
    }
}
