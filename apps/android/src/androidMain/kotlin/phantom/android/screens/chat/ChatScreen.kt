// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import org.json.JSONException
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import phantom.android.di.AppContainer
import phantom.android.ui.*
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono
import phantom.core.messaging.OutgoingMessage
import phantom.core.messaging.SafetyReportCategory
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus
import phantom.core.storage.ReactionEntry
import phantom.core.transport.TransportState

private const val PROFILE_MSG_PREFIX = "\u200B__PHANTOM_PROFILE__\u200B"
private const val PREFS_NAME = "phantom_prefs"

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

    // Voice recording state
    var isRecording by remember { mutableStateOf(false) }
    var recordingDurationMs by remember { mutableStateOf(0L) }
    var audioFile by remember { mutableStateOf<java.io.File?>(null) }
    var mediaRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }

    // Release recorder on screen dispose
    DisposableEffect(Unit) {
        onDispose {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    // RECORD_AUDIO permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val result = startChatRecording(context)
            audioFile = result.first
            mediaRecorder = result.second
            isRecording = true
        }
    }

    // Recording duration counter
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDurationMs = 0L
            while (isRecording) {
                delay(100)
                recordingDurationMs += 100
            }
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Own profile fields — read once from SharedPreferences
    val ownFirstName = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_first_name", "") ?: ""
    }
    val ownLastName = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_last_name", "") ?: ""
    }
    val ownCity = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_city", "") ?: ""
    }
    val ownCountry = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_country", "") ?: ""
    }

    // Reply / Edit / Forward state
    var replyToMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardText by remember { mutableStateOf<String?>(null) }
    var forwardSenderLabel by remember { mutableStateOf("") }
    var conversations by remember { mutableStateOf<List<phantom.core.storage.ConversationEntity>>(emptyList()) }
    var theirPublicKeyHex by remember { mutableStateOf("") }

    // Typing indicator: hidden in Alpha 2.
    // Re-enabled in Phase 5 alongside sealed-sender extension to control
    // messages — until then `transport.sendTyping(...)` exposes the sender's
    // full pubkey at the relay (audit finding F18). Plumbing is preserved so
    // re-enable is a one-line flip.
    val typingIndicatorEnabled = false
    var isContactTyping by remember { mutableStateOf(false) }
    var typingJob by remember { mutableStateOf<Job?>(null) }

    // Pinned messages banner state — loaded once and refreshed after pin/unpin actions
    var pinnedMessages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }

    // Pinned messages — polled every 2 s instead of one-shot. TYPE_PIN
    // arriving from the peer updates the local DB silently; without polling
    // the banner would not refresh on the receiver's side.
    LaunchedEffect(conversationId) {
        while (true) {
            pinnedMessages = container.messageRepo.getPinnedMessages(conversationId)
            kotlinx.coroutines.delay(2_000)
        }
    }

    LaunchedEffect(Unit) {
        conversations = container.conversationRepo.getActiveConversations()
        theirPublicKeyHex = container.conversationRepo.getConversation(conversationId)?.theirPublicKeyHex ?: ""
    }

    val transportState by container.transport.state.collectAsState()
    val isConnected = transportState is TransportState.Connected

    suspend fun reloadMessages() {
        messages = container.messageRepo.getMessages(conversationId)
        Log.i(
            "PhantomUI",
            "ChatScreen reloadMessages: conv=${conversationId.take(24)}… loaded=${messages.size}",
        )
    }

    LaunchedEffect(conversationId) {
        Log.i(
            "PhantomUI",
            "ChatScreen subscribed to conv=${conversationId.take(24)}… theirUsername=$theirUsername",
        )
        reloadMessages()
        val conv = container.conversationRepo.getConversation(conversationId)
        if (conv != null) {
            container.messagingService?.markConversationRead(conversationId, conv.theirPublicKeyHex)
        }

        // Send profile card once per conversation if own profile has at least one name field
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val profileAlreadySent = prefs.getString("profile_sent_$conversationId", null) == "true"
        val hasProfileData = ownFirstName.isNotEmpty() || ownLastName.isNotEmpty()
        if (!profileAlreadySent && hasProfileData && conv != null) {
            val myGradientIndex = prefs.getInt("gradient_index", 0)
            val json = JSONObject().apply {
                put("fn", ownFirstName)
                put("ln", ownLastName)
                put("city", ownCity)
                put("country", ownCountry)
                put("gradientIndex", myGradientIndex)
            }.toString()
            container.messagingService?.sendMessage(
                OutgoingMessage(
                    id = uuid4().toString(),
                    conversationId = conversationId,
                    recipientPublicKeyHex = conv.theirPublicKeyHex,
                    text = PROFILE_MSG_PREFIX + json,
                )
            )
            prefs.edit().putString("profile_sent_$conversationId", "true").apply()
        }
    }

    DisposableEffect(conversationId) {
        onDispose {
            scope.launch { container.conversationRepo.resetUnread(conversationId) }
        }
    }

    // Collect incoming typing events from the contact in this conversation.
    // The indicator auto-clears after 3 s with no new event (matches standard messenger UX).
    LaunchedEffect(conversationId, theirPublicKeyHex, typingIndicatorEnabled) {
        if (!typingIndicatorEnabled) return@LaunchedEffect
        if (theirPublicKeyHex.isEmpty()) return@LaunchedEffect
        container.transport.typingEvents.collect { senderKey ->
            if (senderKey == theirPublicKeyHex) {
                isContactTyping = true
                delay(3_000)
                isContactTyping = false
            }
        }
    }

    LaunchedEffect(container.messagingService) {
        container.messagingService?.incomingMessages
            ?.catch { e ->
                Log.e("PhantomUI", "incomingMessages flow error in ChatScreen: ${e.message}", e)
            }
            ?.collect { incoming ->
                Log.i(
                    "PhantomUI",
                    "ChatScreen received incoming for conv=${incoming.conversationId.take(24)}… " +
                        "(my conv=${conversationId.take(24)}…, match=${incoming.conversationId == conversationId})",
                )
            if (incoming.conversationId == conversationId) {
                // Check if this is a profile card — handle silently, do not display
                val plaintext = incoming.text
                if (plaintext.startsWith(PROFILE_MSG_PREFIX)) {
                    val jsonStr = plaintext.removePrefix(PROFILE_MSG_PREFIX)
                    try {
                        val obj = JSONObject(jsonStr)
                        val profileJson = JSONObject().apply {
                            put("fn", obj.optString("fn", ""))
                            put("ln", obj.optString("ln", ""))
                            put("city", obj.optString("city", ""))
                            put("country", obj.optString("country", ""))
                        }.toString()
                        val gradientIndex = if (obj.has("gradientIndex")) obj.getInt("gradientIndex") else -1
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString("contact_profile_$conversationId", profileJson)
                            .apply()
                        if (gradientIndex >= 0) {
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putInt("${conversationId}_gradient_index", gradientIndex)
                                .apply()
                        }
                    } catch (_: JSONException) {
                        // Malformed profile payload — ignore silently
                    }
                    // Do not reload or scroll — profile card is invisible
                    return@collect
                }

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
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

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

    // adjustResize in the manifest is not enough once
    // WindowCompat.setDecorFitsSystemWindows(window, false) is active on API < 35
    // (it is, see MainActivity.onCreate). Compose then has to opt in to IME insets
    // explicitly. We use the union of `ime` and `navigationBars` so that:
    //   - IME open  → padding = IME height (which already covers the nav bar visually)
    //   - IME closed → padding = nav bar height (3-button or gesture indicator)
    // `union` takes the per-side maximum, so they never stack — without that, the
    // QA-v9 Tecno-HiOS phone showed the InputBar peeking from underneath the
    // 3-button nav bar with the keyboard closed (Modifier.imePadding() alone
    // does nothing once IME is hidden, so the 48 dp nav bar covered the input).
    Scaffold(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.ime.union(WindowInsets.navigationBars)
        ),
        containerColor = BgDeep,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            ChatTopBar(
                theirUsername = theirUsername,
                isConnected = isConnected,
                isTyping = isContactTyping,
                onBack = onBack,
                onContactProfile = onContactProfile,
                onMoreMenu = { showMenu = true },
                showMenu = showMenu,
                onDismissMenu = { showMenu = false },
                onReport = { showMenu = false; showReportDialog = true },
                onBlock = { showMenu = false; showBlockDialog = true },
            )
        },
        bottomBar = {
            // Inset padding lives on the Scaffold modifier (above) via
            // `WindowInsets.ime.union(WindowInsets.navigationBars)`, so the
            // bottomBar slot is already positioned above both the keyboard and
            // the nav bar by the time we render here. Keep this Column inset-
            // free to avoid double-padding.
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
                            PhIconBack(color = TextDim, size = 18.dp)
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
                            PhIconBack(color = TextDim, size = 18.dp)
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
                    onTextChange = { newText ->
                        inputText = newText
                        if (showEmojiPanel) showEmojiPanel = false
                        // Debounced typing event: cancel any pending send and schedule a new one.
                        // One event per keystroke burst is enough — the relay drops if offline.
                        if (typingIndicatorEnabled &&
                            newText.isNotEmpty() &&
                            theirPublicKeyHex.isNotEmpty()
                        ) {
                            typingJob?.cancel()
                            typingJob = scope.launch {
                                container.transport.sendTyping(theirPublicKeyHex)
                            }
                        }
                    },
                    onEmojiToggle = { showEmojiPanel = !showEmojiPanel },
                    emojiPanelOpen = showEmojiPanel,
                    isEditing = editingMessage != null,
                    isRecording = isRecording,
                    recordingDurationMs = recordingDurationMs,
                    onCancelRecording = {
                        mediaRecorder?.stop()
                        mediaRecorder?.release()
                        mediaRecorder = null
                        isRecording = false
                        audioFile?.delete()
                        audioFile = null
                    },
                    onMicClick = {
                        if (isRecording) {
                            // Stop recording and send audio
                            mediaRecorder?.stop()
                            mediaRecorder?.release()
                            mediaRecorder = null
                            isRecording = false
                            val file = audioFile
                            if (file != null && file.exists()) {
                                scope.launch {
                                    val bytes = file.readBytes()
                                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                    val audioPayload = "[AUDIO:$base64]"
                                    val conversation = container.conversationRepo.getConversation(conversationId)
                                    if (conversation != null) {
                                        container.messagingService?.sendMessage(
                                            OutgoingMessage(
                                                id = com.benasher44.uuid.uuid4().toString(),
                                                conversationId = conversationId,
                                                recipientPublicKeyHex = conversation.theirPublicKeyHex,
                                                text = audioPayload,
                                            )
                                        )
                                        reloadMessages()
                                        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                                    }
                                }
                            }
                            audioFile = null
                        } else {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                val result = startChatRecording(context)
                                audioFile = result.first
                                mediaRecorder = result.second
                                isRecording = true
                            } else {
                                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
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
        // Pre-process: filter out invisible profile cards, then inject date separators
        val chatItems = remember(messages) {
            buildList<ChatItem> {
                var lastDate = ""
                messages
                    .filter { msg -> !(msg.plaintextCache?.startsWith(PROFILE_MSG_PREFIX) ?: false) }
                    .forEach { msg ->
                        val dk = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(java.util.Date(msg.createdAt))
                        if (dk != lastDate) { lastDate = dk; add(ChatItem.DateSep(dk, msg.createdAt)) }
                        add(ChatItem.Msg(msg))
                    }
            }
        }

        // Snapshot of message IDs present at first composition — used to skip animation for
        // messages that were already in the list when the screen opened.
        val initialIds = remember {
            chatItems
                .filterIsInstance<ChatItem.Msg>()
                .map { it.entity.id }
                .toHashSet()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "__e2ee__") { E2EENoteRow(theirUsername = theirUsername) }

            // Pinned message banner — shown when at least one message is pinned
            if (pinnedMessages.isNotEmpty()) {
                item(key = "pinned_banner") {
                    val msg = pinnedMessages.last() // most recently pinned
                    val pinnedIndex = remember(msg.id, chatItems) {
                        chatItems.indexOfFirst { it is ChatItem.Msg && it.entity.id == msg.id }
                    }
                    val selfPubKey = container.identityState.value?.publicKeyHex
                    val pinnerLabel = when (msg.pinnedByPubkey) {
                        null -> "Pinned"
                        selfPubKey -> "Pinned by you"
                        theirPublicKeyHex -> "Pinned by $theirUsername"
                        else -> "Pinned"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface2.copy(alpha = 0.78f))
                            .clickable {
                                if (pinnedIndex >= 0) {
                                    scope.launch { listState.animateScrollToItem(pinnedIndex) }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(modifier = Modifier.size(16.dp)) {
                            drawLine(
                                color = CyanAccent,
                                start = Offset(size.width / 2, 0f),
                                end = Offset(size.width / 2, size.height * 0.6f),
                                strokeWidth = 2.dp.toPx(),
                            )
                            drawCircle(
                                color = CyanAccent,
                                radius = size.width * 0.35f,
                                center = Offset(size.width / 2, size.height * 0.3f),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pinnerLabel,
                                color = CyanAccent,
                                fontSize = 10.sp,
                                fontFamily = PhantomFontMono,
                            )
                            Text(
                                text = msg.plaintextCache?.take(60) ?: "•••",
                                color = TextDim,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        // Quick-unpin (local-only). Tapping the X removes the
                        // banner without notifying the peer; for an unpin that
                        // syncs both sides, use the message's long-press menu.
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    scope.launch {
                                        container.messageRepo.pinMessage(
                                            messageId = msg.id,
                                            pinned = false,
                                            pinnedByPubkey = null,
                                        )
                                        pinnedMessages = container.messageRepo.getPinnedMessages(conversationId)
                                    }
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(12.dp)) {
                                val sw = 1.5.dp.toPx()
                                drawLine(
                                    color = TextDim,
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = sw,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                                drawLine(
                                    color = TextDim,
                                    start = Offset(size.width, 0f),
                                    end = Offset(0f, size.height),
                                    strokeWidth = sw,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                }
            }

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
                        val isNew = msg.id !in initialIds
                        AnimatedVisibility(
                            visible = true,
                            enter = if (isNew) {
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> if (msg.sent) fullWidth else -fullWidth },
                                    animationSpec = tween(durationMillis = 150),
                                ) + fadeIn(animationSpec = tween(durationMillis = 150))
                            } else {
                                EnterTransition.None
                            },
                        ) {
                        MessageBubble(
                            entity = msg,
                            theirUsername = theirUsername,
                            conversationId = conversationId,
                            theirPublicKeyHex = theirPublicKeyHex,
                            container = container,
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
                            onPin = {
                                scope.launch {
                                    container.messagingService?.pinMessageForBoth(
                                        messageId = msg.id,
                                        conversationId = conversationId,
                                        recipientPublicKeyHex = theirPublicKeyHex,
                                        pinned = !msg.pinned,
                                    )
                                    pinnedMessages = container.messageRepo.getPinnedMessages(conversationId)
                                }
                            },
                            onPinLocal = {
                                // Local-only pin: write directly to the message
                                // table without sending TYPE_PIN over the wire,
                                // so the peer never learns this message was
                                // pinned. Useful for personal bookmarks the
                                // user does not want surfaced on the other
                                // side.
                                scope.launch {
                                    val newPinned = !msg.pinned
                                    val selfPubKey = container.identityState.value?.publicKeyHex
                                    container.messageRepo.pinMessage(
                                        messageId = msg.id,
                                        pinned = newPinned,
                                        pinnedByPubkey = if (newPinned) selfPubKey else null,
                                    )
                                    pinnedMessages = container.messageRepo.getPinnedMessages(conversationId)
                                }
                            },
                        )
                        } // end AnimatedVisibility
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

/**
 * PHANTOM_FULL_COMPOSE §05 — Encryption strip is THE WOW MOMENT.
 *
 *   32dp, surfaceDeep bg, borderSubtle bottom hairline.
 *   Lock 10dp + "End-to-end encrypted · ED25519 · @username"
 *   JetBrains Mono 10sp · textTertiary · opacity 0.7
 */
@Composable
private fun E2EENoteRow(theirUsername: String) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(PhantomTokens.Colors.SurfaceDeep),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    val w = this.size.width
                    val h = this.size.height
                    val sw = 1.4.dp.toPx()
                    val color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.7f)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(w * 0.18f, h * 0.45f),
                        size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.45f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                        style = Stroke(sw),
                    )
                    drawArc(
                        color = color,
                        startAngle = 180f, sweepAngle = 180f, useCenter = false,
                        topLeft = Offset(w * 0.30f, h * 0.18f),
                        size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.50f),
                        style = Stroke(sw),
                    )
                }
                Text(
                    text = "End-to-end encrypted · ED25519 · @$theirUsername",
                    color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 0.4.sp,
                )
            }
        }
        HorizontalDivider(color = PhantomTokens.Colors.BorderSubtle, thickness = 1.dp)
    }
}

@Composable
private fun ChatDateSep(millis: Long) {
    // Design Brief v3 §9.5: just centered uppercase mono label, no hairlines.
    val label = when {
        isToday(millis) -> "TODAY"
        isYesterday(millis) -> "YESTERDAY"
        else -> java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US).format(java.util.Date(millis)).uppercase(java.util.Locale.US)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PhantomTokens.Colors.TextTertiary,
            style = PhantomType.overline,
        )
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

private val REACTION_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "👎")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    entity: MessageEntity,
    theirUsername: String,
    conversationId: String,
    theirPublicKeyHex: String,
    container: AppContainer,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForBoth: () -> Unit,
    onCopy: (String) -> Unit,
    onForward: (cleanText: String, senderLabel: String) -> Unit,
    onPin: () -> Unit,
    onPinLocal: () -> Unit,
) {
    val context = LocalContext.current
    val bubbleCoroutineScope = rememberCoroutineScope()
    val isSent = entity.sent
    val rawText = entity.plaintextCache ?: "•••"
    val timeStr = formatMessageTime(entity.createdAt)
    // Single combined long-press panel — emoji row at top, action list
    // already expanded below it. Replaces the previous two-step UX where the
    // user had to tap "•••" inside the emoji popup to open a separate
    // DropdownMenu.
    var showActionPanel by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPinChoice by remember { mutableStateOf(false) }
    // Bubble bounds in window-pixel coordinates — used to position the
    // long-press panel directly next to the tapped message instead of the
    // screen centre.
    var bubbleBoundsPx by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // Load reactions; polls every 2 s so real-time reactions appear without a full DB Flow.
    val reactions by produceState(initialValue = emptyList<ReactionEntry>(), key1 = entity.id) {
        while (true) {
            value = container.reactionRepo.getReactions(entity.id)
            delay(2_000)
        }
    }

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
            // Outer column: bubble + reaction pills stacked vertically
            Column {
            // Bubble shape per PHANTOM_FULL_COMPOSE §05:
            //   incoming  → 12 12 12 2   (tail bottom-left)
            //   outgoing  → 12 12 2 12   (tail bottom-right)
            // OutBubble is FLAT cyan — gradient explicitly LOCKED OFF in the
            // Phase-2 polish pass (see §15 "What was normalized": "OutBubble:
            // gradient removed → flat C.cyan").
            val bubbleShape = RoundedCornerShape(
                topStart = PhantomTokens.Radius.md,
                topEnd = PhantomTokens.Radius.md,
                bottomStart = if (isSent) PhantomTokens.Radius.md else 2.dp,
                bottomEnd = if (isSent) 2.dp else PhantomTokens.Radius.md,
            )
            Column(
                modifier = Modifier
                    .widthIn(min = 80.dp, max = 260.dp)
                    .then(
                        if (isSent) Modifier.background(
                            color = PhantomTokens.Colors.Cyan,
                            shape = bubbleShape,
                        ) else Modifier
                            .background(
                                color = PhantomTokens.Colors.SurfaceElevated,
                                shape = bubbleShape,
                            )
                            .border(
                                width = 1.dp,
                                color = PhantomTokens.Colors.BorderSubtle,
                                shape = bubbleShape,
                            )
                    )
                    .onGloballyPositioned { coords ->
                        bubbleBoundsPx = coords.boundsInWindow()
                    }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showActionPanel = true },
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

                // Audio bubble — detected by [AUDIO:...] prefix in plaintext cache
                val isAudio = rawText.startsWith("[AUDIO:")
                if (isAudio) {
                    val base64Data = rawText.removePrefix("[AUDIO:").removeSuffix("]")
                    AudioBubble(
                        base64Data = base64Data,
                        isSent = isSent,
                        timeStr = timeStr,
                        status = entity.status,
                        context = context,
                    )
                } else {
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
                } // end audio/text branch

                // Link preview — shown for messages that contain a URL
                val urlInMsg = remember(entity.id) { extractUrl(rawText) }
                var linkPreview by remember(entity.id) { mutableStateOf<LinkPreview?>(null) }

                if (urlInMsg != null) {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    LaunchedEffect(entity.id) {
                        linkPreview = fetchLinkPreview(urlInMsg)
                    }
                    val preview = linkPreview
                    if (preview != null) {
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSent) BgDeep.copy(alpha = 0.30f) else Surface.copy(alpha = 0.8f)
                                )
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.06f),
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { runCatching { uriHandler.openUri(urlInMsg) } }
                                .padding(10.dp),
                        ) {
                            Text(
                                text = preview.title,
                                color = if (isSent) Color.White else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (preview.description.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = preview.description,
                                    color = if (isSent) Color.White.copy(alpha = 0.75f) else TextDim,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = preview.url.take(50),
                                color = if (isSent) Color.White.copy(alpha = 0.6f) else CyanAccent.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                fontFamily = PhantomFontMono,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } // end bubble Column

            // Reaction pills — shown below the bubble when reactions exist
            if (reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    reactions.forEach { entry ->
                        Text(
                            text = entry.emoji,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Surface2)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            } // end outer Column (bubble + pills)

            // Combined long-press panel — emoji row at the top, compact
            // action list expanded directly below. Rendered as a Dialog so we
            // get a dimmed scrim and the panel does not eat the whole screen.
            if (showActionPanel) {
                val within24h = (System.currentTimeMillis() - entity.createdAt) < 24 * 60 * 60 * 1000L
                val density = androidx.compose.ui.platform.LocalDensity.current
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
                val screenWidthPx  = with(density) { configuration.screenWidthDp.dp.toPx() }
                val panelMaxWidthPx = with(density) { 280.dp.toPx() }
                val panelEstimatedHeightPx = with(density) { 380.dp.toPx() }
                val gapPx = with(density) { 8.dp.toPx() }
                val sideMarginPx = with(density) { 16.dp.toPx() }
                val topMarginPx = with(density) { 40.dp.toPx() }
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showActionPanel = false },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false,
                        dismissOnClickOutside = true,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.32f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { showActionPanel = false },
                    ) {
                        // Compute panel offset relative to the long-pressed
                        // bubble. Prefer placing the panel above the bubble;
                        // fall back below if there is not enough room above.
                        val bounds = bubbleBoundsPx
                        val placeAbove = bounds != null && bounds.top > panelEstimatedHeightPx + gapPx + topMarginPx
                        val panelXPx = (bounds?.left ?: (screenWidthPx / 2f - panelMaxWidthPx / 2f))
                            .coerceIn(sideMarginPx, screenWidthPx - panelMaxWidthPx - sideMarginPx)
                        val panelYPx = when {
                            bounds == null ->
                                // Fallback to vertical centre when bounds are unknown.
                                (screenHeightPx / 2f - panelEstimatedHeightPx / 2f).coerceAtLeast(topMarginPx)
                            placeAbove ->
                                bounds.top - panelEstimatedHeightPx - gapPx
                            else ->
                                (bounds.bottom + gapPx)
                                    .coerceAtMost(screenHeightPx - panelEstimatedHeightPx - sideMarginPx)
                        }
                        Column(
                            modifier = Modifier
                                .offset {
                                    androidx.compose.ui.unit.IntOffset(
                                        x = panelXPx.toInt(),
                                        y = panelYPx.toInt(),
                                    )
                                }
                                .widthIn(max = 280.dp)
                                .wrapContentHeight()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Surface.copy(alpha = 0.96f))
                                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {}, // swallow taps so panel doesn't dismiss itself
                        ) {
                            // ── Emoji row (top) ──────────────────────────────
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                REACTION_EMOJIS.forEach { emoji ->
                                    Text(
                                        text = emoji,
                                        fontSize = 22.sp,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable {
                                                showActionPanel = false
                                                if (theirPublicKeyHex.isNotEmpty()) {
                                                    bubbleCoroutineScope.launch {
                                                        container.messagingService?.sendReaction(
                                                            messageId = entity.id,
                                                            conversationId = conversationId,
                                                            recipientPublicKeyHex = theirPublicKeyHex,
                                                            emoji = emoji,
                                                        )
                                                    }
                                                }
                                            }
                                            .padding(6.dp),
                                    )
                                }
                            }
                            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                            // ── Action list (bottom, expanded) ───────────────
                            ActionRow(
                                icon = { c -> PhIconReply(color = c, size = 16.dp) },
                                label = "Reply",
                            ) {
                                showActionPanel = false; onReply()
                            }
                            if (isSent && within24h) {
                                ActionRow(
                                    icon = { c -> PhIconEdit(color = c, size = 16.dp) },
                                    label = "Edit",
                                ) {
                                    showActionPanel = false; onEdit()
                                }
                            }
                            ActionRow(
                                icon = { c -> PhIconCopy(color = c, size = 14.dp) },
                                label = "Copy text",
                            ) {
                                showActionPanel = false; onCopy(rawText)
                            }
                            ActionRow(
                                icon = { c -> PhIconPinAction(color = c, size = 16.dp) },
                                label = if (entity.pinned) "Unpin" else "Pin",
                            ) {
                                showActionPanel = false; showPinChoice = true
                            }
                            ActionRow(
                                icon = { c -> PhIconForward(color = c, size = 16.dp) },
                                label = "Forward",
                            ) {
                                showActionPanel = false
                                val senderLabel = if (entity.sent) "You" else theirUsername
                                onForward(text, senderLabel)
                            }
                            ActionRow(
                                icon = { c -> PhIconBookmark(color = c, size = 16.dp) },
                                label = "Save",
                            ) {
                                showActionPanel = false
                                bubbleCoroutineScope.launch {
                                    val savedConvId = "saved_messages_local"
                                    container.messageRepo.insertMessage(
                                        phantom.core.storage.MessageEntity(
                                            id = com.benasher44.uuid.uuid4().toString(),
                                            conversationId = savedConvId,
                                            ciphertext = ByteArray(0),
                                            plaintextCache = "↩ from @${if (entity.sent) "You" else theirUsername}\n$text",
                                            sent = true,
                                            status = phantom.core.storage.MessageStatus.DELIVERED,
                                            createdAt = System.currentTimeMillis(),
                                        )
                                    )
                                }
                            }
                            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                            ActionRow(
                                icon = { c -> PhIconTrash(color = c, size = 15.dp) },
                                label = "Delete",
                                danger = true,
                            ) {
                                showActionPanel = false; showDeleteDialog = true
                            }
                        }
                    }
                }
            }

            // Pin sub-choice dialog — opened from the action panel's "Pin" row.
            if (showPinChoice) {
                AlertDialog(
                    onDismissRequest = { showPinChoice = false },
                    containerColor = Surface,
                    title = {
                        Text(
                            text = if (entity.pinned) "Unpin message" else "Pin message",
                            color = TextPrimary,
                            fontSize = 16.sp,
                        )
                    },
                    text = {
                        Text(
                            text = if (entity.pinned) {
                                "Choose where to unpin."
                            } else {
                                "Pin for everyone shows this message at the top of the chat for both of you. Pin for me keeps it private to your device."
                            },
                            color = TextDim,
                            fontSize = 13.sp,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showPinChoice = false; onPin() }) {
                            Text(
                                text = if (entity.pinned) "Unpin for everyone" else "Pin for everyone",
                                color = CyanAccent,
                                fontSize = 14.sp,
                            )
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { showPinChoice = false; onPinLocal() }) {
                                Text(
                                    text = if (entity.pinned) "Unpin for me" else "Pin for me",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                )
                            }
                            TextButton(onClick = { showPinChoice = false }) {
                                Text("Cancel", color = TextDim, fontSize = 14.sp)
                            }
                        }
                    },
                )
            }
        }
        } // end Row
    } // end swipe Box
}

@Composable
private fun ActionRow(
    icon: @Composable (color: Color) -> Unit,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) Danger else PhantomTokens.Colors.TextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) { icon(tint) }
        Text(
            text = label,
            color = if (danger) Danger else TextPrimary,
            fontSize = 14.sp,
        )
    }
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
            // Single checkmark — dim on cyan bubble
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCheckmark(
                    color = BgDeep.copy(alpha = 0.62f),
                    offsetX = size.width * 0.1f,
                )
            }
        }
        MessageStatus.RELAYED, MessageStatus.DELIVERED -> {
            // Double checkmark — dim or slightly brighter
            val color = if (status == MessageStatus.DELIVERED)
                BgDeep.copy(alpha = 0.75f) else BgDeep.copy(alpha = 0.55f)
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCheckmark(color = color, offsetX = 0f)
                drawCheckmark(color = color, offsetX = size.width * 0.3f)
            }
        }
        MessageStatus.READ -> {
            // Double checkmark — cyan accent (matches design spec)
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCheckmark(color = CyanAccent, offsetX = 0f)
                drawCheckmark(color = CyanAccent, offsetX = size.width * 0.3f)
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

// ── Audio message bubble ──────────────────────────────────────────────────────

@Composable
private fun AudioBubble(
    base64Data: String,
    isSent: Boolean,
    timeStr: String,
    status: MessageStatus,
    context: android.content.Context,
) {
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var durationMs by remember { mutableStateOf(0) }
    // Playback speed cycle: 1× → 1.5× → 2× → 0.5× → 1×. State persists across
    // pause/resume so the user keeps their chosen speed for this bubble.
    val speedSteps = remember { listOf(1.0f, 1.5f, 2.0f, 0.5f) }
    var speedIdx by remember { mutableIntStateOf(0) }
    val currentSpeed = speedSteps[speedIdx]

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun formatDur(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    Row(
        modifier = Modifier.width(220.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Play/pause button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (isSent) Color.White.copy(alpha = 0.2f)
                    else CyanAccent.copy(alpha = 0.15f)
                )
                .clickable {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        if (mediaPlayer == null) {
                            try {
                                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
                                val file = java.io.File(context.cacheDir, "play_${System.currentTimeMillis()}.3gp")
                                file.writeBytes(bytes)
                                val mp = android.media.MediaPlayer().apply {
                                    setDataSource(file.absolutePath)
                                    prepare()
                                    setOnCompletionListener {
                                        isPlaying = false
                                        progress = 0f
                                    }
                                }
                                mediaPlayer = mp
                                durationMs = mp.duration
                                runCatching {
                                    mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed)
                                }
                                mp.start()
                            } catch (_: Exception) {}
                        } else {
                            mediaPlayer?.let { mp ->
                                runCatching {
                                    mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed)
                                }
                                mp.start()
                            }
                        }
                        isPlaying = true
                        scope.launch {
                            while (isPlaying) {
                                val mp = mediaPlayer ?: break
                                progress = if (mp.duration > 0) mp.currentPosition / mp.duration.toFloat() else 0f
                                delay(200)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isPlaying) {
                // Pause icon
                Canvas(modifier = Modifier.size(16.dp)) {
                    val col = if (isSent) BgDeep else CyanAccent
                    val sw = 2.5.dp.toPx()
                    drawLine(col, Offset(size.width * 0.3f, size.height * 0.2f), Offset(size.width * 0.3f, size.height * 0.8f), sw, StrokeCap.Round)
                    drawLine(col, Offset(size.width * 0.7f, size.height * 0.2f), Offset(size.width * 0.7f, size.height * 0.8f), sw, StrokeCap.Round)
                }
            } else {
                // Play triangle
                Canvas(modifier = Modifier.size(16.dp)) {
                    val col = if (isSent) BgDeep else CyanAccent
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.25f, size.height * 0.15f)
                        lineTo(size.width * 0.85f, size.height * 0.5f)
                        lineTo(size.width * 0.25f, size.height * 0.85f)
                        close()
                    }
                    drawPath(path, color = col)
                }
            }
        }

        // Waveform progress + time
        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (isSent) BgDeep else CyanAccent,
                trackColor = if (isSent) BgDeep.copy(alpha = 0.3f) else Surface,
            )
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = formatDur(durationMs),
                        color = if (isSent) BgDeep.copy(alpha = 0.65f) else TextDim,
                        fontSize = 10.sp,
                    )
                    // Playback speed cycle button. Compact pill — tap to bump
                    // through 1× → 1.5× → 2× → 0.5× → 1×. Applied to live
                    // MediaPlayer immediately if one exists; otherwise stored
                    // and applied on next play.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isSent) BgDeep.copy(alpha = 0.2f) else Surface2,
                            )
                            .clickable {
                                speedIdx = (speedIdx + 1) % speedSteps.size
                                mediaPlayer?.let { mp ->
                                    runCatching {
                                        mp.playbackParams = mp.playbackParams.setSpeed(speedSteps[speedIdx])
                                    }
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = formatSpeed(currentSpeed),
                            fontSize = 9.5.sp,
                            color = if (isSent) BgDeep.copy(alpha = 0.7f) else CyanAccent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = timeStr,
                        color = if (isSent) BgDeep.copy(alpha = 0.65f) else TextDim,
                        fontSize = 10.sp,
                    )
                    if (isSent) StatusIcon(status = status)
                }
            }
        }
    }
}

private fun formatSpeed(speed: Float): String = when (speed) {
    1.0f -> "1×"
    2.0f -> "2×"
    0.5f -> "0.5×"
    else -> "${"%.1f".format(speed)}×"
}

// ── Input bar ─────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onEmojiToggle: () -> Unit,
    emojiPanelOpen: Boolean,
    isEditing: Boolean = false,
    isRecording: Boolean = false,
    recordingDurationMs: Long = 0L,
    onMicClick: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onSend: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val recordingSeconds = recordingDurationMs / 1000
    // Phase 2 mockup: composer sits on SurfaceElevated, BorderSubtle 1px top.
    // Slightly more "premium" than the surrounding chat surface, signalling
    // the input zone without a heavy bar.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PhantomTokens.Colors.SurfaceElevated),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PhantomTokens.Colors.BorderSubtle),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Left: cancel (X) during recording, emoji toggle otherwise
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = if (isRecording) onCancelRecording else onEmojiToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (isRecording) {
                    // X — cancel recording
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val sw = 2.dp.toPx()
                        val pad = size.width * 0.18f
                        drawLine(Danger, Offset(pad, pad), Offset(size.width - pad, size.height - pad), sw, StrokeCap.Round)
                        drawLine(Danger, Offset(size.width - pad, pad), Offset(pad, size.height - pad), sw, StrokeCap.Round)
                    }
                } else {
                    PhIconPaperclip(color = TextDim, size = 22.dp)
                }
            }
            if (!isRecording) Spacer(Modifier.width(6.dp))

            // Center: text field or recording indicator
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Surface2)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Canvas(modifier = Modifier.size(10.dp)) {
                            drawCircle(color = Danger)
                        }
                        Text(
                            text = "Recording %d:%02d".format(recordingSeconds / 60, recordingSeconds % 60),
                            color = TextPrimary,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…", color = TextDim, fontSize = 14.sp) },
                    singleLine = false,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = CyanAccent,
                        focusedContainerColor = Surface2,
                        unfocusedContainerColor = Surface2,
                    ),
                    shape = RoundedCornerShape(18.dp),
                )
            }

            Spacer(Modifier.width(8.dp))

            // Right: send button (text/edit) or mic button (empty/recording).
            // Phase 2 mockup: 38dp circular Cyan with restrained glow
            // (0 2px 10px rgba(0,212,255,0.10)) — present but never decorative.
            if ((text.isNotBlank() || isEditing) && !isRecording) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = CircleShape,
                            clip = false,
                            spotColor = PhantomTokens.Colors.Cyan.copy(alpha = 0.18f),
                            ambientColor = PhantomTokens.Colors.Cyan.copy(alpha = 0.08f),
                        )
                        .clip(CircleShape)
                        .background(PhantomTokens.Colors.Cyan)
                        .clickable(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSend()
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isEditing) {
                        Canvas(modifier = Modifier.size(18.dp)) {
                            val sw = 2.dp.toPx()
                            drawLine(BgDeep, Offset(size.width * 0.15f, size.height * 0.5f), Offset(size.width * 0.42f, size.height * 0.76f), sw, StrokeCap.Round)
                            drawLine(BgDeep, Offset(size.width * 0.42f, size.height * 0.76f), Offset(size.width * 0.85f, size.height * 0.24f), sw, StrokeCap.Round)
                        }
                    } else {
                        // Arrow-up icon
                        Canvas(modifier = Modifier.size(20.dp)) {
                            val sw = 2.2.dp.toPx()
                            val cap = StrokeCap.Round
                            val cx = size.width / 2f
                            drawLine(BgDeep, Offset(cx, size.height * 0.82f), Offset(cx, size.height * 0.18f), sw, cap)
                            drawLine(BgDeep, Offset(cx, size.height * 0.18f), Offset(cx - size.width * 0.28f, size.height * 0.46f), sw, cap)
                            drawLine(BgDeep, Offset(cx, size.height * 0.18f), Offset(cx + size.width * 0.28f, size.height * 0.46f), sw, cap)
                        }
                    }
                }
            } else {
                // Mic button — tap to start recording; send arrow when recording
                Box(
                    modifier = Modifier
                        .size(if (isRecording) 44.dp else 36.dp)
                        .then(
                            if (isRecording) Modifier.shadow(
                                elevation = 12.dp,
                                shape = CircleShape,
                                clip = false,
                                spotColor = CyanAccent.copy(alpha = 0.4f),
                                ambientColor = CyanAccent.copy(alpha = 0.15f),
                            ) else Modifier
                        )
                        .clip(CircleShape)
                        .background(if (isRecording) CyanAccent else Color.Transparent)
                        .clickable(onClick = onMicClick),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRecording) {
                        // Send arrow (same as text send)
                        Canvas(modifier = Modifier.size(20.dp)) {
                            val sw = 2.2.dp.toPx()
                            val cap = StrokeCap.Round
                            val cx = size.width / 2f
                            drawLine(BgDeep, Offset(cx, size.height * 0.82f), Offset(cx, size.height * 0.18f), sw, cap)
                            drawLine(BgDeep, Offset(cx, size.height * 0.18f), Offset(cx - size.width * 0.28f, size.height * 0.46f), sw, cap)
                            drawLine(BgDeep, Offset(cx, size.height * 0.18f), Offset(cx + size.width * 0.28f, size.height * 0.46f), sw, cap)
                        }
                    } else {
                        // Mic icon (Canvas)
                        Canvas(modifier = Modifier.size(22.dp)) {
                            val c = TextDim
                            val sw = 1.6.dp.toPx()
                            val st = Stroke(width = sw, cap = StrokeCap.Round)
                            drawRoundRect(
                                color = c, style = st,
                                topLeft = Offset(size.width * 0.33f, size.height * 0.08f),
                                size = Size(size.width * 0.34f, size.height * 0.52f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.17f),
                            )
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(size.width * 0.18f, size.height * 0.48f)
                                cubicTo(size.width * 0.18f, size.height * 0.82f, size.width * 0.82f, size.height * 0.82f, size.width * 0.82f, size.height * 0.48f)
                            }
                            drawPath(path, color = c, style = st)
                            drawLine(c, Offset(size.width * 0.5f, size.height * 0.78f), Offset(size.width * 0.5f, size.height * 0.95f), sw, StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

// ── Recording helper ──────────────────────────────────────────────────────────

private fun startChatRecording(context: android.content.Context): Pair<java.io.File, android.media.MediaRecorder> {
    // Codec selection by API level. Voice notes inline as base64 in the chat
    // payload, so smaller is better. Mono is intentional — at the same bitrate
    // it sounds cleaner than stereo for speech and halves payload size, which
    // matters on slow uplinks (Bug J / ISSUE-001 Tecno radio parking). A
    // stereo voice-notes mode is reserved for the future Plus tier.
    val useOpus = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    val ext = if (useOpus) "ogg" else "m4a"
    val file = java.io.File(context.cacheDir, "audio_${System.currentTimeMillis()}.$ext")
    @Suppress("DEPRECATION")
    val recorder = android.media.MediaRecorder().apply {
        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
        if (useOpus) {
            // OGG + Opus, 48 kHz mono, 48 kbps — comfortably above the
            // transparent-voice threshold (~24-32 kbps). Extra headroom helps
            // on cheap OEM mics (Tecno-class) where the captured signal has
            // more noise that needs more bits to encode without artefacts.
            setOutputFormat(android.media.MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.OPUS)
            setAudioSamplingRate(48_000)
            setAudioChannels(1)
            setAudioEncodingBitRate(48_000)
        } else {
            // Pre-API-29 fallback: AAC in MPEG-4 container. Still vastly better
            // than the legacy AMR_NB this code used before.
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioChannels(1)
            setAudioEncodingBitRate(64_000)
        }
        setOutputFile(file.absolutePath)
        prepare()
        start()
    }
    return file to recorder
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

// ── Link preview ──────────────────────────────────────────────────────────────

private data class LinkPreview(
    val url: String,
    val title: String,
    val description: String,
)

private val URL_REGEX = Regex("""https?://[^\s]+""")

private fun extractUrl(text: String): String? = URL_REGEX.find(text)?.value

private suspend fun fetchLinkPreview(url: String): LinkPreview? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.setRequestProperty("User-Agent", "PHANTOM/1.0")
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) return@runCatching null
        val html = conn.inputStream.bufferedReader().use { r ->
            val sb = StringBuilder()
            val buf = CharArray(1024)
            var read: Int
            var total = 0
            while (r.read(buf).also { read = it } != -1 && total < 32_768) {
                sb.appendRange(buf, 0, read); total += read
            }
            sb.toString()
        }
        val title = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(80)
            ?: return@runCatching null
        val desc = Regex(
            """<meta[^>]+name=.description.[^>]+content=.([^"']{1,200})""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
        LinkPreview(url = url, title = title, description = desc)
    }.getOrNull()
}

private val CircleShape = RoundedCornerShape(50)

// ── Chat top bar ──────────────────────────────────────────────────────────────

@Composable
private fun ChatTopBar(
    theirUsername: String,
    isConnected: Boolean,
    isTyping: Boolean = false,
    onBack: () -> Unit,
    onContactProfile: () -> Unit,
    onMoreMenu: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    // PHANTOM_FULL_COMPOSE §05 layout:
    //   [← back] [Avatar 36dp] [name + @username] [Phone] [Video] [MoreHoriz]
    // 56dp height, BorderSubtle bottom hairline.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PhantomTokens.Colors.Surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                PhIconBack(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onContactProfile),
            ) {
                GradientAvatar(
                    name = theirUsername,
                    size = 36.dp,
                    online = if (isConnected) true else null,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onContactProfile),
            ) {
                Text(
                    text = theirUsername,
                    color = PhantomTokens.Colors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.15).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isTyping) {
                    Text(
                        text = "typing…",
                        color = PhantomTokens.Colors.Cyan.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontFamily = PhantomFontMono,
                    )
                } else {
                    Text(
                        text = "@$theirUsername",
                        color = PhantomTokens.Colors.TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 0.4.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { /* TODO: voice call */ }, modifier = Modifier.size(40.dp)) {
                PhIconPhone(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
            }
            IconButton(onClick = { /* TODO: video call */ }, modifier = Modifier.size(40.dp)) {
                PhIconVideo(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
            }
            Box {
                IconButton(onClick = onMoreMenu, modifier = Modifier.size(40.dp)) {
                    PhIconMoreVert(color = PhantomTokens.Colors.TextSecondary, size = 18.dp)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onDismissMenu,
                    containerColor = Surface2,
                ) {
                    DropdownMenuItem(
                        text = { Text("Report", color = TextPrimary, fontSize = 14.sp) },
                        onClick = onReport,
                    )
                    DropdownMenuItem(
                        text = { Text("Block", color = Danger, fontSize = 14.sp) },
                        onClick = onBlock,
                    )
                }
            }
        }
        HorizontalDivider(color = PhantomTokens.Colors.BorderSubtle, thickness = 1.dp)
    }
}
