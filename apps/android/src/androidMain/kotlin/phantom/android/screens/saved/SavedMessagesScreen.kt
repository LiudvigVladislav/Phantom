// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.saved

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import phantom.android.ui.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono
import phantom.core.messaging.OutgoingMessage
import phantom.core.storage.ConversationEntity
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus
import phantom.core.storage.TrustTier

private const val SAVED_CONV_ID = "saved_messages_local"
private const val SAVED_USERNAME = "Notes"

@Composable
fun SavedMessagesScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var forwardNoteText by remember { mutableStateOf<String?>(null) }
    var conversations by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    val listState = rememberLazyListState()

    suspend fun reload() {
        messages = container.messageRepo.getMessages(SAVED_CONV_ID)
    }

    LaunchedEffect(Unit) {
        conversations = container.conversationRepo.getActiveConversations()
        val existing = container.conversationRepo.getConversation(SAVED_CONV_ID)
        if (existing == null) {
            container.conversationRepo.upsertConversation(
                ConversationEntity(
                    id = SAVED_CONV_ID,
                    theirUsername = SAVED_USERNAME,
                    theirPublicKeyHex = "",
                    lastMessagePreview = null,
                    lastMessageAt = null,
                    unreadCount = 0,
                    trustTier = TrustTier.TRUSTED,
                    blocked = false,
                )
            )
        }
        reload()
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    // Forward from Notes dialog
    val fwdNote = forwardNoteText
    if (fwdNote != null) {
        AlertDialog(
            onDismissRequest = { forwardNoteText = null },
            containerColor = Surface,
            title = { Text("Forward to…", color = TextPrimary) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (conversations.isEmpty()) {
                        Text("No conversations yet", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    }
                    conversations.forEach { conv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    forwardNoteText = null
                                    scope.launch {
                                        container.messagingService?.sendMessage(
                                            OutgoingMessage(
                                                id = uuid4().toString(),
                                                conversationId = conv.id,
                                                recipientPublicKeyHex = conv.theirPublicKeyHex,
                                                text = fwdNote,
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
                                Text(conv.theirUsername.take(1).uppercase(), color = CyanAccent, fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(conv.theirUsername, color = TextPrimary, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { forwardNoteText = null }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    // Notes screen has no Scaffold — it's a plain Column. We need explicit
    // inset handling so the input bar stays above both the keyboard and the
    // 3-button system nav bar on devices like Tecno HiOS.
    // `WindowInsets.ime.union(WindowInsets.navigationBars)` returns the
    // per-side maximum, so IME-open and IME-closed both resolve correctly
    // without double-padding.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars)
            ),
    ) {
        // Custom top bar — matches ArchiveScreen style
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
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    PhIconBack(color = TextPrimary, size = 20.dp)
                }
                Text(
                    text = "SAVED MESSAGES",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = TextDim,
                    fontSize = 11.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 0.88.sp,
                )
                Spacer(Modifier.size(32.dp))
            }
            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
        }

        // Bottom bar
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                // Canvas-drawn empty state — no emoji
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(modifier = Modifier.size(48.dp)) {
                            val sw = 2.dp.toPx()
                            val stroke = Stroke(
                                width = sw,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            )
                            val iconColor = TextDim.copy(alpha = 0.4f)
                            val w = size.width
                            val h = size.height

                            // Bookmark / pin shape:
                            // Outer rectangle body with a V-notch cut from the bottom
                            val path = Path().apply {
                                // top-left corner
                                moveTo(w * 0.18f, 0f)
                                // top-right corner
                                lineTo(w * 0.82f, 0f)
                                // right side down to bottom-right
                                lineTo(w * 0.82f, h * 0.86f)
                                // V-notch bottom-right to tip
                                lineTo(w * 0.50f, h * 0.64f)
                                // V-notch tip to bottom-left
                                lineTo(w * 0.18f, h * 0.86f)
                                // left side back up
                                close()
                            }
                            drawPath(path, iconColor, style = stroke)

                            // Horizontal rule inside bookmark (like a page line)
                            drawLine(
                                color = iconColor,
                                start = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.30f),
                                end = androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.30f),
                                strokeWidth = sw,
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = iconColor,
                                start = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.44f),
                                end = androidx.compose.ui.geometry.Offset(w * 0.60f, h * 0.44f),
                                strokeWidth = sw,
                                cap = StrokeCap.Round,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Your personal notes", color = TextDim, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Save texts, links, ideas",
                            color = TextDim.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        SavedMessageBubble(
                            entity = msg,
                            onDelete = {
                                scope.launch {
                                    container.messageRepo.deleteMessage(msg.id)
                                    reload()
                                }
                            },
                            onCopy = { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("note", text))
                            },
                            onEdit = { text ->
                                editingMessageId = msg.id
                                inputText = text
                            },
                            onForward = { text ->
                                forwardNoteText = text
                            },
                            onPin = {
                                android.widget.Toast.makeText(context, "Pin — coming soon", android.widget.Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }

        // Input bar
        Surface(color = Surface, tonalElevation = 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (editingMessageId != null) "Edit note…" else "New note…",
                            color = TextDim, fontSize = 14.sp,
                        )
                    },
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
                Spacer(Modifier.width(8.dp))
                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = {
                            val text = inputText.trim()
                            if (text.isEmpty()) return@IconButton
                            val editId = editingMessageId
                            inputText = ""
                            editingMessageId = null
                            scope.launch {
                                if (editId != null) {
                                    container.messageRepo.updateMessageText(editId, text)
                                } else {
                                    val now = System.currentTimeMillis()
                                    container.messageRepo.insertMessage(
                                        MessageEntity(
                                            id = uuid4().toString(),
                                            conversationId = SAVED_CONV_ID,
                                            ciphertext = ByteArray(0),
                                            plaintextCache = text,
                                            sent = true,
                                            status = MessageStatus.DELIVERED,
                                            createdAt = now,
                                        )
                                    )
                                    container.conversationRepo.upsertConversation(
                                        ConversationEntity(
                                            id = SAVED_CONV_ID,
                                            theirUsername = SAVED_USERNAME,
                                            theirPublicKeyHex = "",
                                            lastMessagePreview = text.take(60),
                                            lastMessageAt = now,
                                            unreadCount = 0,
                                            trustTier = TrustTier.TRUSTED,
                                            blocked = false,
                                        )
                                    )
                                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                                }
                                reload()
                            }
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(color = CyanAccent),
                    ) {
                        PhIconArrowUp(color = BgDeep, size = 17.dp)
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedMessageBubble(
    entity: MessageEntity,
    onDelete: () -> Unit,
    onCopy: (String) -> Unit,
    onEdit: (String) -> Unit,
    onForward: (String) -> Unit,
    onPin: () -> Unit,
) {
    val rawText = entity.plaintextCache ?: ""
    val timeStr = run {
        val date = java.util.Date(entity.createdAt)
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(date)
    }
    var showActions by remember { mutableStateOf(false) }
    val within24h = (System.currentTimeMillis() - entity.createdAt) < 24 * 60 * 60 * 1000L

    // Parse "↩ from @username\nbody"
    val isForwarded = rawText.startsWith("↩ from ")
    val forwardHeader: String
    val bodyText: String
    if (isForwarded) {
        val nl = rawText.indexOf('\n')
        forwardHeader = if (nl > 0) rawText.substring(0, nl) else rawText
        bodyText = if (nl in 1 until rawText.length) rawText.substring(nl + 1) else ""
    } else {
        forwardHeader = ""
        bodyText = rawText
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .widthIn(min = 80.dp, max = 280.dp)
                    .background(
                        color = Surface2,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
                    )
                    .combinedClickable(onClick = {}, onLongClick = { showActions = true })
                    .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp),
            ) {
                if (isForwarded) {
                    Text(
                        text = forwardHeader,
                        color = CyanAccent.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 14.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                }
                Box {
                    Text(
                        text = bodyText,
                        modifier = Modifier.padding(end = 40.dp),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    Text(
                        text = timeStr,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        color = TextDim,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                    )
                }
            }

            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false },
                containerColor = Surface2,
                offset = DpOffset(0.dp, 4.dp),
            ) {
                DropdownMenuItem(
                    leadingIcon = {
                        phantom.android.ui.PhIconForward(
                            color = phantom.android.ui.theme.PhantomTokens.Colors.TextSecondary,
                            size = 16.dp,
                        )
                    },
                    text = { Text("Forward", color = TextPrimary, fontSize = 14.sp) },
                    onClick = { showActions = false; onForward(bodyText) },
                )
                DropdownMenuItem(
                    leadingIcon = {
                        phantom.android.ui.PhIconPinAction(
                            color = phantom.android.ui.theme.PhantomTokens.Colors.TextSecondary,
                            size = 16.dp,
                        )
                    },
                    text = { Text("Pin", color = TextPrimary, fontSize = 14.sp) },
                    onClick = { showActions = false; onPin() },
                )
                DropdownMenuItem(
                    leadingIcon = {
                        phantom.android.ui.PhIconCopy(
                            color = phantom.android.ui.theme.PhantomTokens.Colors.TextSecondary,
                            size = 14.dp,
                        )
                    },
                    text = { Text("Copy", color = TextPrimary, fontSize = 14.sp) },
                    onClick = { showActions = false; onCopy(bodyText) },
                )
                if (within24h) {
                    DropdownMenuItem(
                        leadingIcon = {
                            phantom.android.ui.PhIconEdit(
                                color = phantom.android.ui.theme.PhantomTokens.Colors.TextSecondary,
                                size = 16.dp,
                            )
                        },
                        text = { Text("Edit", color = TextPrimary, fontSize = 14.sp) },
                        onClick = { showActions = false; onEdit(bodyText) },
                    )
                }
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                DropdownMenuItem(
                    leadingIcon = {
                        phantom.android.ui.PhIconTrash(
                            color = Danger,
                            size = 15.dp,
                        )
                    },
                    text = { Text("Delete", color = Danger, fontSize = 14.sp) },
                    onClick = { showActions = false; onDelete() },
                )
            }
        }
    }
}
