package phantom.android.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.GradientAvatar
import phantom.android.ui.theme.*
import phantom.core.messaging.OutgoingMessage
import phantom.core.messaging.SafetyReportCategory
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus
import phantom.core.transport.TransportState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    theirUsername: String,
    container: AppContainer,
    onBack: () -> Unit,
    onContactProfile: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showEmojiPanel by remember { mutableStateOf(false) }

    var showMenu by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Reply / Edit / Forward state
    var replyToMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardText by remember { mutableStateOf<String?>(null) }
    var forwardSenderLabel by remember { mutableStateOf("") }
    var conversations by remember { mutableStateOf<List<phantom.core.storage.ConversationEntity>>(emptyList()) }
    var theirPublicKeyHex by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        conversations = container.conversationRepo.getActiveConversations()
        theirPublicKeyHex = container.conversationRepo.getConversation(conversationId)?.theirPublicKeyHex ?: ""
    }

    val transportState by container.transport.state.collectAsState()
    val isConnected = transportState is TransportState.Connected

    suspend fun reloadMessages() {
        messages = container.messageRepo.getMessages(conversationId)
    }

    LaunchedEffect(conversationId) {
        reloadMessages()
        val conv = container.conversationRepo.getConversation(conversationId)
        if (conv != null) {
            container.messagingService?.markConversationRead(conversationId, conv.theirPublicKeyHex)
        }
    }

    DisposableEffect(conversationId) {
        onDispose {
            scope.launch { container.conversationRepo.resetUnread(conversationId) }
        }
    }

    LaunchedEffect(container.messagingService) {
        container.messagingService?.incomingMessages?.collect { incoming ->
            if (incoming.conversationId == conversationId) {
                reloadMessages()
                val conv = container.conversationRepo.getConversation(conversationId)
                if (conv != null) {
                    container.messagingService?.markConversationRead(conversationId, conv.theirPublicKeyHex)
                }
                listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
            }
        }
    }

    // Forward dialog
    val fwdText = forwardText
    if (fwdText != null) {
        AlertDialog(
            onDismissRequest = { forwardText = null },
            containerColor = Surface,
            title = { Text("Forward to…", color = TextPrimary) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Notes always on top
                    val savedConvId = "saved_messages_local"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                forwardText = null
                                scope.launch {
                                    val label = forwardSenderLabel.ifBlank { "Unknown" }
                                    container.messageRepo.insertMessage(
                                        phantom.core.storage.MessageEntity(
                                            id = uuid4().toString(),
                                            conversationId = savedConvId,
                                            ciphertext = ByteArray(0),
                                            plaintextCache = "↩ from @$label\n$fwdText",
                                            sent = true,
                                            status = phantom.core.storage.MessageStatus.DELIVERED,
                                            createdAt = System.currentTimeMillis(),
                                        )
                                    )
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(CyanAccent.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(18.dp)) {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(size.width * 0.2f, 0f); lineTo(size.width * 0.8f, 0f)
                                    lineTo(size.width * 0.8f, size.height); lineTo(size.width * 0.5f, size.height * 0.72f)
                                    lineTo(size.width * 0.2f, size.height); close()
                                }
                                drawPath(path, CyanAccent)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Notes", color = TextPrimary, fontSize = 15.sp)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                    if (conversations.isEmpty()) {
                        Text("No other conversations", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    }
                    conversations.forEach { fwdConv ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        forwardText = null
                                        scope.launch {
                                            container.messagingService?.sendMessage(
                                                OutgoingMessage(
                                                    id = uuid4().toString(),
                                                    conversationId = fwdConv.id,
                                                    recipientPublicKeyHex = fwdConv.theirPublicKeyHex,
                                                    text = fwdText,
                                                )
                                            )
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(CyanAccent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(fwdConv.theirUsername.take(1).uppercase(), color = CyanAccent, fontSize = 14.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(fwdConv.theirUsername, color = TextPrimary, fontSize = 15.sp)
                            }
                        }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { forwardText = null }) { Text("Cancel", color = TextDim) }
            },
        )
    }

    if (showBlockDialog) {
        BlockConfirmDialog(
            username = theirUsername,
            onConfirm = {
                showBlockDialog = false
                scope.launch {
                    container.conversationRepo.blockConversation(conversationId)
                    onBack()
                }
            },
            onDismiss = { showBlockDialog = false },
        )
    }

    if (showReportDialog) {
        ReportDialog(
            username = theirUsername,
            onReport = { _ ->
                showReportDialog = false
                scope.launch { snackbarHostState.showSnackbar("Report sent. Thank you.") }
            },
            onDismiss = { showReportDialog = false },
        )
    }

    // adjustResize in manifest handles keyboard — no imePadding needed
    Scaffold(
        containerColor = BgDeep,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onContactProfile),
                    ) {
                        GradientAvatar(
                            name = theirUsername,
                            size = 36.dp,
                            online = isConnected,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = theirUsername,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                            )
                            Text(
                                text = if (isConnected) "end-to-end encrypted" else "connecting…",
                                color = if (isConnected) Success.copy(alpha = 0.8f) else CyanAccent.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                                letterSpacing = 0.3.sp,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextDim)
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextDim)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = Surface2,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Report", color = TextPrimary, fontSize = 14.sp) },
                            onClick = { showMenu = false; showReportDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Block", color = Danger, fontSize = 14.sp) },
                            onClick = { showMenu = false; showBlockDialog = true },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        },
        bottomBar = {
            Column {
                // Reply bar
                val reply = replyToMessage
                if (reply != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(CyanAccent, shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (reply.sent) "You" else theirUsername,
                                color = CyanAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = reply.plaintextCache ?: "•••",
                                color = TextDim,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        IconButton(onClick = { replyToMessage = null }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Отмена", tint = TextDim, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Edit bar
                val editing = editingMessage
                if (editing != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(CyanAccent.copy(alpha = 0.7f), shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Editing", color = CyanAccent.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(editing.plaintextCache ?: "•••", color = TextDim, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = { editingMessage = null; inputText = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Отмена", tint = TextDim, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showEmojiPanel,
                    enter = expandVertically(expandFrom = Alignment.Bottom),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
                ) {
                    EmojiPanel(onEmoji = { inputText += it })
                }
                InputBar(
                    text = inputText,
                    onTextChange = {
                        inputText = it
                        if (showEmojiPanel) showEmojiPanel = false
                    },
                    onEmojiToggle = { showEmojiPanel = !showEmojiPanel },
                    emojiPanelOpen = showEmojiPanel,
                    isEditing = editingMessage != null,
                    onSend = {
                        val text = inputText.trim()
                        if (text.isEmpty()) return@InputBar
                        val editMsg = editingMessage
                        val replyMsg = replyToMessage   // capture BEFORE clearing
                        inputText = ""
                        showEmojiPanel = false
                        editingMessage = null
                        replyToMessage = null
                        scope.launch {
                            if (editMsg != null) {
                                // Save edit locally and notify recipient
                                val conversation = container.conversationRepo.getConversation(conversationId)
                                    ?: return@launch
                                container.messagingService?.editMessageForBoth(
                                    messageId = editMsg.id,
                                    newText = text,
                                    conversationId = conversationId,
                                    recipientPublicKeyHex = conversation.theirPublicKeyHex,
                                )
                                reloadMessages()
                            } else {
                                // Send new message (with optional reply prefix)
                                val finalText = if (replyMsg != null) {
                                    "> ${replyMsg.plaintextCache?.take(60) ?: "•••"}\n$text"
                                } else {
                                    text
                                }
                                val conversation = container.conversationRepo.getConversation(conversationId)
                                    ?: return@launch
                                container.messagingService?.sendMessage(
                                    OutgoingMessage(
                                        id = uuid4().toString(),
                                        conversationId = conversationId,
                                        recipientPublicKeyHex = conversation.theirPublicKeyHex,
                                        text = finalText,
                                    )
                                )
                                reloadMessages()
                                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    val backThreshold = 120.dp.toPx()
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = { if (totalDrag > backThreshold) onBack() },
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragAmount > 0 || totalDrag > 0) {
                                totalDrag += dragAmount
                                change.consume()
                            }
                        },
                    )
                }
        ) {
        // Pre-process: inject date separators into the item list
        val chatItems = remember(messages) {
            buildList<ChatItem> {
                var lastDate = ""
                messages.forEach { msg ->
                    val dk = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(msg.createdAt))
                    if (dk != lastDate) { lastDate = dk; add(ChatItem.DateSep(dk, msg.createdAt)) }
                    add(ChatItem.Msg(msg))
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "__e2ee__") { E2EENoteRow() }
            lazyItems(chatItems, key = {
                when (it) {
                    is ChatItem.DateSep -> "__date_${it.dateKey}"
                    is ChatItem.Msg -> it.entity.id
                }
            }) { chatItem ->
                when (chatItem) {
                    is ChatItem.DateSep -> ChatDateSep(chatItem.millis)
                    is ChatItem.Msg -> {
                        val msg = chatItem.entity
                        MessageBubble(
                            entity = msg,
                            theirUsername = theirUsername,
                            onReply = {
                                replyToMessage = msg
                                editingMessage = null
                            },
                            onEdit = {
                                editingMessage = msg
                                inputText = msg.plaintextCache ?: ""
                                replyToMessage = null
                            },
                            onDeleteForMe = {
                                scope.launch {
                                    container.messageRepo.deleteMessage(msg.id)
                                    reloadMessages()
                                }
                            },
                            onDeleteForBoth = {
                                scope.launch {
                                    val key = theirPublicKeyHex
                                    if (key.isNotEmpty()) {
                                        container.messagingService?.deleteMessageForBoth(msg.id, conversationId, key)
                                    } else {
                                        container.messageRepo.deleteMessage(msg.id)
                                    }
                                    reloadMessages()
                                }
                            },
                            onCopy = { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
                            },
                            onForward = { cleanText, senderLabel ->
                                forwardText = cleanText
                                forwardSenderLabel = senderLabel
                            },
                        )
                    }
                }
            }
        }
        } // end back-swipe Box
    }
}

private sealed class ChatItem {
    data class DateSep(val dateKey: String, val millis: Long) : ChatItem()
    data class Msg(val entity: MessageEntity) : ChatItem()
}

@Composable
private fun E2EENoteRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.03f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Canvas(modifier = Modifier.size(10.dp)) {
                val r = size.minDimension / 2f
                drawCircle(color = Success, radius = r, style = Stroke(1.2.dp.toPx()))
                drawCircle(color = Success, radius = r * 0.38f)
            }
            Text(
                "messages are end-to-end encrypted",
                color = TextDim, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun ChatDateSep(millis: Long) {
    val label = when {
        isToday(millis) -> "Today"
        isYesterday(millis) -> "Yesterday"
        else -> java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).format(java.util.Date(millis))
    }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label.uppercase(), color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
    }
}

private fun isToday(millis: Long): Boolean {
    val cal = java.util.Calendar.getInstance()
    val today = java.util.Calendar.getInstance()
    cal.timeInMillis = millis
    return cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun isYesterday(millis: Long): Boolean {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = millis
    val yesterday = java.util.Calendar.getInstance()
    yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1)
    return cal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR)
}

// ── Emoji panel ───────────────────────────────────────────────────────────────

private val CATEGORY_ICONS = listOf("🙂", "🖐️", "🐱", "🍎", "⚽", "✈️", "💡", "#️⃣")

@Composable
private fun EmojiPanel(onEmoji: (String) -> Unit) {
    var selectedCategory by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val displayEmojis: List<EmojiItem> = remember(selectedCategory, searchQuery) {
        if (searchQuery.isBlank()) {
            EMOJI_CATEGORIES.getOrNull(selectedCategory)?.items ?: emptyList()
        } else {
            searchEmoji(searchQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(Surface),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            placeholder = { Text("Search emoji…", color = TextDim, fontSize = 13.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = CyanAccent.copy(alpha = 0.4f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                cursorColor = CyanAccent,
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
            ),
            shape = RoundedCornerShape(12.dp),
        )

        if (searchQuery.isBlank()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface2)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(EMOJI_CATEGORIES) { idx, cat ->
                    val isSelected = idx == selectedCategory
                    Text(
                        text = CATEGORY_ICONS.getOrElse(idx) { cat.label.take(1) },
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) CyanAccent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { selectedCategory = idx }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            Text(
                text = EMOJI_CATEGORIES.getOrNull(selectedCategory)?.label ?: "",
                color = TextDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 42.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp),
            contentPadding = PaddingValues(bottom = 6.dp),
        ) {
            gridItems(displayEmojis) { item ->
                Text(
                    text = item.emoji,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onEmoji(item.emoji) }
                        .padding(4.dp),
                )
            }
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    entity: MessageEntity,
    theirUsername: String,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForBoth: () -> Unit,
    onCopy: (String) -> Unit,
    onForward: (cleanText: String, senderLabel: String) -> Unit,
) {
    val context = LocalContext.current
    val isSent = entity.sent
    val rawText = entity.plaintextCache ?: "•••"
    val timeStr = formatMessageTime(entity.createdAt)
    var showActions by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Parse reply prefix: "> quote\nmessage"
    val isReply = rawText.startsWith("> ")
    val quoteText: String
    val text: String
    if (isReply) {
        val newlineIdx = rawText.indexOf('\n')
        if (newlineIdx > 2) {
            quoteText = rawText.substring(2, newlineIdx)
            text = rawText.substring(newlineIdx + 1)
        } else {
            quoteText = rawText.substring(2)
            text = ""
        }
    } else {
        quoteText = ""
        text = rawText
    }

    // Delete confirmation dialog
    val canDeleteForBoth = isSent && (System.currentTimeMillis() - entity.createdAt) < 24 * 60 * 60 * 1000L
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Surface,
            title = { Text("Delete message?", color = TextPrimary) },
            text = {
                if (canDeleteForBoth) {
                    Text("Choose who to delete this message for.", color = TextDim, fontSize = 14.sp)
                } else {
                    Text("Delete this message for yourself?", color = TextDim, fontSize = 14.sp)
                }
            },
            confirmButton = {
                Column {
                    if (canDeleteForBoth) {
                        TextButton(onClick = { showDeleteDialog = false; onDeleteForBoth() }) {
                            Text("Delete for everyone", color = Danger, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = { showDeleteDialog = false; onDeleteForMe() }) {
                        Text("Delete for me", color = Danger, fontSize = 13.sp)
                    }
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel", color = TextDim, fontSize = 13.sp)
                    }
                }
            },
        )
    }

    val swipeOffset = remember { Animatable(0f) }
    val bubbleScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                val maxSwipePx = 72.dp.toPx()
                val replyThresholdPx = 60.dp.toPx()
                var triggered = false
                detectHorizontalDragGestures(
                    onDragStart = { triggered = false },
                    onDragEnd = {
                        bubbleScope.launch {
                            if (!triggered && swipeOffset.value >= replyThresholdPx) {
                                triggered = true
                                onReply()
                            }
                            swipeOffset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f))
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (dragAmount > 0 || swipeOffset.value > 0) {
                            change.consume()
                            bubbleScope.launch {
                                swipeOffset.snapTo((swipeOffset.value + dragAmount).coerceIn(0f, maxSwipePx))
                            }
                        }
                    },
                )
            }
    ) {
        val maxSwipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
        val swipeProgress = (swipeOffset.value / maxSwipePx).coerceIn(0f, 1f)
        if (swipeProgress > 0.05f) {
            Box(
                modifier = Modifier
                    .align(if (isSent) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Surface2.copy(alpha = swipeProgress)),
                contentAlignment = Alignment.Center,
            ) {
                Text("↩", fontSize = 14.sp, color = CyanAccent.copy(alpha = swipeProgress))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) },
            horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
        ) {
        Box {
            // time "06:26" ≈ 28dp + gap 3dp + status icon ≈ 18dp = ~56dp for sent, ~36dp for received
            val timeReserve = if (isSent) 56.dp else 36.dp
            // Column wraps quote block + text+time — no overlapping content
            Column(
                modifier = Modifier
                    .widthIn(min = 80.dp, max = 260.dp)
                    .background(
                        color = if (isSent) CyanAccent else Surface2,
                        shape = RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (isSent) 16.dp else 4.dp,
                            bottomEnd = if (isSent) 4.dp else 16.dp,
                        )
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showActions = true },
                    )
                    .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp),
            ) {
                // Quote block (reply) — stacked above main text
                if (isReply && quoteText.isNotEmpty()) {
                    val senderLabel = if (entity.sent) "You" else theirUsername

                    if (isSent) {
                        // On cyan bubble — dark overlay block
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgDeep.copy(alpha = 0.30f))
                                .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .heightIn(min = 36.dp)
                                    .background(Color.White, shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                            )
                            Column(modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)) {
                                Text(
                                    text = senderLabel,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 14.sp,
                                )
                                Text(
                                    text = quoteText,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                )
                            }
                        }
                    } else {
                        // On dark bubble — cyan accent block
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyanAccent.copy(alpha = 0.12f))
                                .padding(0.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .heightIn(min = 36.dp)
                                    .background(CyanAccent, shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                            )
                            Column(modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)) {
                                Text(
                                    text = senderLabel,
                                    color = CyanAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 14.sp,
                                )
                                Text(
                                    text = quoteText,
                                    color = TextPrimary.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // Text + time in a Box so time overlays bottom-right corner
                Box {
                    Text(
                        text = text,
                        modifier = Modifier.padding(end = timeReserve),
                        color = if (isSent) BgDeep else TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = timeStr,
                            color = if (isSent) BgDeep.copy(alpha = 0.65f) else TextDim,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                        )
                        if (isSent) StatusIcon(status = entity.status)
                    }
                }
            }

            // Long-press context menu
            val within24h = (System.currentTimeMillis() - entity.createdAt) < 24 * 60 * 60 * 1000L
            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false },
                containerColor = Surface2,
                offset = DpOffset(0.dp, 4.dp),
            ) {
                DropdownMenuItem(
                    leadingIcon = { MenuIcon(0x21A9) }, // ↩
                    text = { Text("Reply", color = TextPrimary, fontSize = 14.sp) },
                    onClick = { showActions = false; onReply() },
                )
                if (isSent && within24h) {
                    DropdownMenuItem(
                        leadingIcon = { MenuIcon(0x270F) }, // ✏
                        text = { Text("Edit", color = TextPrimary, fontSize = 14.sp) },
                        onClick = { showActions = false; onEdit() },
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { MenuIcon(0x1F4CB) }, // 📋
                    text = { Text("Copy text", color = TextPrimary, fontSize = 14.sp) },
                    onClick = { showActions = false; onCopy(rawText) },
                )
                DropdownMenuItem(
                    leadingIcon = { MenuIcon(0x1F4CC) }, // 📌
                    text = { Text("Pin", color = TextPrimary, fontSize = 14.sp) },
                    onClick = {
                        showActions = false
                        android.widget.Toast.makeText(context, "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
                DropdownMenuItem(
                    leadingIcon = { MenuIcon(0x27A1) }, // ➡
                    text = { Text("Forward", color = TextPrimary, fontSize = 14.sp) },
                    onClick = {
                        showActions = false
                        val senderLabel = if (entity.sent) "You" else theirUsername
                        onForward(text, senderLabel)
                    },
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                DropdownMenuItem(
                    leadingIcon = { MenuIcon(0x1F5D1) }, // 🗑
                    text = { Text("Delete", color = Danger, fontSize = 14.sp) },
                    onClick = { showActions = false; showDeleteDialog = true },
                )
            }
        }
        } // end Row
    } // end swipe Box
}

@Composable
private fun MenuIcon(codePoint: Int) {
    Text(
        text = String(Character.toChars(codePoint)),
        fontSize = 16.sp,
        modifier = Modifier.size(20.dp),
    )
}

private fun formatMessageTime(millis: Long): String {
    val date = java.util.Date(millis)
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return fmt.format(date)
}

@Composable
private fun StatusIcon(status: MessageStatus) {
    when (status) {
        MessageStatus.QUEUED -> {
            // Clock-dot: just a dim dot
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCircle(color = Color.White.copy(alpha = 0.35f), radius = 2.dp.toPx())
            }
        }
        MessageStatus.SENT -> {
            // Single checkmark
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCheckmark(
                    color = Color.White.copy(alpha = 0.55f),
                    offsetX = size.width * 0.1f,
                )
            }
        }
        MessageStatus.RELAYED, MessageStatus.DELIVERED -> {
            // Double checkmark, white
            val color = if (status == MessageStatus.DELIVERED)
                Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.7f)
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCheckmark(color = color, offsetX = 0f)
                drawCheckmark(color = color, offsetX = size.width * 0.3f)
            }
        }
        MessageStatus.READ -> {
            // Double checkmark, green
            val green = Color(0xFF4FC97B)
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCheckmark(color = green, offsetX = 0f)
                drawCheckmark(color = green, offsetX = size.width * 0.3f)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckmark(
    color: Color,
    offsetX: Float,
) {
    val w = size.width
    val h = size.height
    val sw = 1.6.dp.toPx()
    // checkmark: short left leg + long right leg
    val x0 = offsetX + w * 0.05f
    val y0 = h * 0.50f
    val x1 = offsetX + w * 0.22f
    val y1 = h * 0.72f
    val x2 = offsetX + w * 0.50f
    val y2 = h * 0.28f
    drawLine(color = color, start = Offset(x0, y0), end = Offset(x1, y1), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
}

// ── Input bar ─────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onEmojiToggle: () -> Unit,
    emojiPanelOpen: Boolean,
    isEditing: Boolean = false,
    onSend: () -> Unit,
) {
    Surface(color = Surface, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Emoji toggle button — monochrome Canvas icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onEmojiToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (emojiPanelOpen) {
                    Canvas(modifier = Modifier.size(22.dp)) {
                        val s = size
                        val c = TextDim
                        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                        drawRoundRect(color = c, style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                        val keyW = s.width / 8f
                        val row1Y = s.height * 0.32f
                        for (i in 1..5) {
                            drawCircle(color = c, radius = 1.4.dp.toPx(), center = Offset(i * keyW + keyW * 0.5f, row1Y))
                        }
                        val row2Y = s.height * 0.56f
                        for (i in 1..4) {
                            drawCircle(color = c, radius = 1.4.dp.toPx(), center = Offset(i * keyW + keyW, row2Y))
                        }
                        val spaceY = s.height * 0.78f
                        drawLine(color = c, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                            start = Offset(s.width * 0.25f, spaceY), end = Offset(s.width * 0.75f, spaceY))
                    }
                } else {
                    Canvas(modifier = Modifier.size(22.dp)) {
                        val r = size.minDimension / 2f
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val c = TextDim
                        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                        drawCircle(color = c, radius = r - 1.dp.toPx(), style = stroke)
                        drawCircle(color = c, radius = 1.6.dp.toPx(), center = Offset(cx - r * 0.3f, cy - r * 0.2f))
                        drawCircle(color = c, radius = 1.6.dp.toPx(), center = Offset(cx + r * 0.3f, cy - r * 0.2f))
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(cx - r * 0.38f, cy + r * 0.12f)
                            cubicTo(cx - r * 0.38f, cy + r * 0.55f, cx + r * 0.38f, cy + r * 0.55f, cx + r * 0.38f, cy + r * 0.12f)
                        }
                        drawPath(path = path, color = c, style = stroke)
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message…", color = TextDim, fontSize = 14.sp) },
                singleLine = false,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = CyanAccent.copy(alpha = 0.4f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                    cursorColor = CyanAccent,
                ),
                shape = RoundedCornerShape(20.dp),
            )

            if (text.isNotBlank() || isEditing) {
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = onSend,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(color = CyanAccent),
                ) {
                    if (isEditing) {
                        Canvas(modifier = Modifier.size(18.dp)) {
                            val sw = 2.dp.toPx()
                            drawLine(BgDeep, Offset(size.width * 0.15f, size.height * 0.5f), Offset(size.width * 0.42f, size.height * 0.76f), sw, StrokeCap.Round)
                            drawLine(BgDeep, Offset(size.width * 0.42f, size.height * 0.76f), Offset(size.width * 0.85f, size.height * 0.24f), sw, StrokeCap.Round)
                        }
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = BgDeep,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun BlockConfirmDialog(username: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Block $username?", color = TextPrimary) },
        text = { Text("They won't be able to send you messages.", color = TextDim, fontSize = 13.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Block", color = Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim) } },
    )
}

@Composable
private fun ReportDialog(username: String, onReport: (SafetyReportCategory) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<SafetyReportCategory?>(null) }
    var otherText by remember { mutableStateOf("") }
    val canSend = selected != null && (selected != SafetyReportCategory.OTHER || otherText.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Report $username", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Select a reason:", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                SafetyReportCategory.entries.forEach { category ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selected == category,
                            onClick = { selected = category },
                            colors = RadioButtonDefaults.colors(selectedColor = CyanAccent, unselectedColor = TextDim),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = categoryLabel(category),
                            color = if (selected == category) TextPrimary else TextDim,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (selected == SafetyReportCategory.OTHER) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = otherText,
                        onValueChange = { otherText = it },
                        placeholder = { Text("Describe the issue…", color = TextDim, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = CyanAccent,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canSend) onReport(selected!!) },
                enabled = canSend,
            ) {
                Text("Send Report", color = if (canSend) CyanAccent else TextDim)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim) } },
    )
}

private fun categoryLabel(category: SafetyReportCategory): String = when (category) {
    SafetyReportCategory.SPAM            -> "Spam"
    SafetyReportCategory.HARASSMENT      -> "Harassment"
    SafetyReportCategory.THREATS         -> "Threats or violence"
    SafetyReportCategory.CSAM            -> "Child safety"
    SafetyReportCategory.ILLEGAL_CONTENT -> "Illegal content"
    SafetyReportCategory.OTHER           -> "Other"
}

private val CircleShape = RoundedCornerShape(50)
