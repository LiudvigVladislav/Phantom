// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import phantom.android.ui.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.theme.*
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: String,
    groupName: String,
    isChannel: Boolean,
    container: AppContainer,
    onBack: () -> Unit,
    onAddMember: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showMenu by remember { mutableStateOf(false) }
    var memberCount by remember { mutableStateOf(0) }
    var myRole by remember { mutableStateOf("member") }

    // Voice recording state
    var isRecording by remember { mutableStateOf(false) }
    var recordingDurationMs by remember { mutableStateOf(0L) }
    var audioFile by remember { mutableStateOf<java.io.File?>(null) }
    var mediaRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }

    // Release recorder if screen is disposed while recording
    DisposableEffect(Unit) {
        onDispose {
            runCatching { mediaRecorder?.stop() }
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    // RECORD_AUDIO permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val result = startGroupRecording(context)
            if (result != null) {
                audioFile = result.first
                mediaRecorder = result.second
                isRecording = true
            }
        }
    }

    suspend fun reloadMessages() {
        messages = container.messageRepo.getMessages(groupId)
    }

    LaunchedEffect(groupId) {
        reloadMessages()
        memberCount = container.groupRepo.getMemberCount(groupId).toInt()
        myRole = container.groupRepo.getGroup(groupId)?.myRole ?: "member"
    }

    // Poll groupMessageFlow for new messages matching this groupId
    LaunchedEffect(container.groupMessagingService) {
        container.groupMessagingService?.groupMessageFlow?.collect { incomingGroupId ->
            if (incomingGroupId == groupId) {
                reloadMessages()
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }
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

    val canWrite = !(isChannel && myRole != "admin")

    // Same window-inset pattern as 1:1 ChatScreen — `union(ime, navigationBars)`
    // so the GroupInputBar sits above the keyboard when it is open and above
    // the system nav bar when it is closed, without double-padding when both
    // insets are reported simultaneously. Without this the input clipped the
    // same way the 1:1 chat did on Tecno HiOS.
    Scaffold(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.ime.union(WindowInsets.navigationBars)
        ),
        containerColor = BgDeep,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GroupTopBar(
                groupName = groupName,
                memberCount = memberCount,
                isChannel = isChannel,
                onBack = onBack,
                showMenu = showMenu,
                onMoreMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onAddMember = { showMenu = false; onAddMember() },
                onLeaveGroup = {
                    showMenu = false
                    scope.launch {
                        container.groupMessagingService?.leaveGroup(groupId)
                        onBack()
                    }
                },
            )
        },
        bottomBar = {
            if (canWrite) {
                GroupInputBar(
                    text = inputText,
                    onTextChange = { inputText = it },
                    isRecording = isRecording,
                    recordingDurationMs = recordingDurationMs,
                    onSend = {
                        val text = inputText.trim()
                        if (text.isEmpty()) return@GroupInputBar
                        inputText = ""
                        scope.launch {
                            container.groupMessagingService?.sendGroupMessage(groupId, text)
                            reloadMessages()
                            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                        }
                    },
                    onMicClick = {
                        if (isRecording) {
                            // Stop recording and send
                            runCatching { mediaRecorder?.stop() }
                            mediaRecorder?.release()
                            mediaRecorder = null
                            isRecording = false
                            val file = audioFile
                            if (file != null && file.exists()) {
                                scope.launch {
                                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
                                    val base64 = android.util.Base64.encodeToString(
                                        bytes, android.util.Base64.NO_WRAP
                                    )
                                    container.groupMessagingService?.sendGroupAudio(
                                        groupId, base64, recordingDurationMs
                                    )
                                    reloadMessages()
                                }
                            }
                            audioFile = null
                        } else {
                            // Start recording — request permission first
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                val result = startGroupRecording(context)
                                if (result != null) {
                                    audioFile = result.first
                                    mediaRecorder = result.second
                                    isRecording = true
                                }
                            } else {
                                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                )
            } else {
                // Read-only channel banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface)
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Read-only channel",
                        color = TextDim,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                    )
                }
            }
        },
    ) { padding ->
        val chatItems = remember(messages) {
            buildList<GroupChatItem> {
                var lastDate = ""
                messages.forEach { msg ->
                    val dk = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date(msg.createdAt))
                    if (dk != lastDate) { lastDate = dk; add(GroupChatItem.DateSep(dk, msg.createdAt)) }
                    add(GroupChatItem.Msg(msg))
                }
            }
        }
        val initialIds = remember {
            chatItems.filterIsInstance<GroupChatItem.Msg>().map { it.entity.id }.toHashSet()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "__e2ee__") { GroupE2EENoteRow(isChannel) }

            items(chatItems, key = {
                when (it) {
                    is GroupChatItem.DateSep -> "__date_${it.dateKey}"
                    is GroupChatItem.Msg -> it.entity.id
                }
            }) { item ->
                when (item) {
                    is GroupChatItem.DateSep -> GroupDateSep(item.millis)
                    is GroupChatItem.Msg -> {
                        val msg = item.entity
                        val isNew = msg.id !in initialIds
                        AnimatedVisibility(
                            visible = true,
                            enter = if (isNew) {
                                slideInHorizontally(
                                    initialOffsetX = { w -> if (msg.sent) w else -w },
                                    animationSpec = tween(150),
                                ) + fadeIn(tween(150))
                            } else {
                                androidx.compose.animation.EnterTransition.None
                            },
                        ) {
                            GroupMessageBubble(entity = msg, context = context)
                        }
                    }
                }
            }
        }
    }
}

// ── Helper: start recording ────────────────────────────────────────────────────

private fun startGroupRecording(context: android.content.Context): Pair<java.io.File, android.media.MediaRecorder>? = runCatching {
    // See startChatRecording() for the codec-selection rationale (Opus on
    // API 29+, AAC fallback on API 26-28, mono 32 kbps Opus / 64 kbps AAC).
    val useOpus = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    val ext = if (useOpus) "ogg" else "m4a"
    val file = java.io.File(context.cacheDir, "audio_${System.currentTimeMillis()}.$ext")
    @Suppress("DEPRECATION")
    val recorder = android.media.MediaRecorder().apply {
        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
        if (useOpus) {
            setOutputFormat(android.media.MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.OPUS)
            setAudioSamplingRate(48_000)
            setAudioChannels(1)
            setAudioEncodingBitRate(48_000)
        } else {
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
    file to recorder
}.getOrNull()

// ── Sealed list items ──────────────────────────────────────────────────────────

private sealed class GroupChatItem {
    data class DateSep(val dateKey: String, val millis: Long) : GroupChatItem()
    data class Msg(val entity: MessageEntity) : GroupChatItem()
}

// ── E2EE note ─────────────────────────────────────────────────────────────────

@Composable
private fun GroupE2EENoteRow(isChannel: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = androidx.compose.ui.Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.03f))
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
                text = if (isChannel) "channel — messages broadcast by admins" else "group · end-to-end encrypted",
                color = TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

// ── Date separator ─────────────────────────────────────────────────────────────

@Composable
private fun GroupDateSep(millis: Long) {
    val label = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
        .format(java.util.Date(millis))
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = TextDim,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
        )
    }
}

// ── Message bubble ─────────────────────────────────────────────────────────────

@Composable
private fun GroupMessageBubble(entity: MessageEntity, context: android.content.Context) {
    val isSent = entity.sent
    val rawText = entity.plaintextCache ?: "•••"
    val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(entity.createdAt))

    val isAudio = rawText.startsWith("[AUDIO:")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
            if (!isSent) {
                Text(
                    text = "Member",
                    color = CyanAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }

            val bubbleShape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isSent) 16.dp else 4.dp,
                bottomEnd = if (isSent) 4.dp else 16.dp,
            )

            Box(
                modifier = Modifier
                    .widthIn(min = 80.dp, max = 260.dp)
                    .background(
                        color = if (isSent) CyanAccent else Surface2,
                        shape = bubbleShape,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (isAudio) {
                    val base64Data = rawText.removePrefix("[AUDIO:").removeSuffix("]")
                    GroupAudioBubble(
                        base64Data = base64Data,
                        isSent = isSent,
                        context = context,
                        timeStr = timeStr,
                    )
                } else {
                    Column {
                        Text(
                            text = rawText,
                            color = if (isSent) BgDeep else TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                        Text(
                            text = timeStr,
                            color = if (isSent) BgDeep.copy(alpha = 0.65f) else TextDim,
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Audio bubble ──────────────────────────────────────────────────────────────

@Composable
private fun GroupAudioBubble(
    base64Data: String,
    isSent: Boolean,
    context: android.content.Context,
    timeStr: String,
) {
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var durationSec by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun formatDuration(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    Row(
        modifier = Modifier.width(200.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = {
                if (isPlaying) {
                    mediaPlayer?.pause()
                    isPlaying = false
                } else {
                    if (mediaPlayer == null) {
                        val player = playGroupAudio(context, base64Data) {
                            isPlaying = false
                            progress = 0f
                        } ?: return@IconButton
                        mediaPlayer = player
                        durationSec = player.duration
                    } else {
                        mediaPlayer?.start()
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
            modifier = Modifier.size(32.dp),
        ) {
            val iconColor = if (isSent) BgDeep else CyanAccent
            if (isPlaying) PhIconPause(color = iconColor, size = 20.dp)
            else PhIconPlay(color = iconColor, size = 20.dp)
        }

        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (isSent) BgDeep else CyanAccent,
                trackColor = if (isSent) BgDeep.copy(alpha = 0.3f) else Surface,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (durationSec > 0) formatDuration(durationSec) else "0:00",
                    color = if (isSent) BgDeep.copy(alpha = 0.65f) else TextDim,
                    fontSize = 10.sp,
                )
                Text(
                    text = timeStr,
                    color = if (isSent) BgDeep.copy(alpha = 0.65f) else TextDim,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private fun playGroupAudio(
    context: android.content.Context,
    base64Data: String,
    onComplete: () -> Unit,
): android.media.MediaPlayer? = runCatching {
    val bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
    val file = java.io.File(context.cacheDir, "play_${System.currentTimeMillis()}.3gp")
    file.writeBytes(bytes)
    android.media.MediaPlayer().apply {
        setDataSource(file.absolutePath)
        prepare()
        setOnCompletionListener { onComplete() }
        start()
    }
}.getOrNull()

// ── Top bar ────────────────────────────────────────────────────────────────────

@Composable
private fun GroupTopBar(
    groupName: String,
    memberCount: Int,
    isChannel: Boolean,
    onBack: () -> Unit,
    showMenu: Boolean,
    onMoreMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onAddMember: () -> Unit,
    onLeaveGroup: () -> Unit,
) {
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
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Surface2)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                PhIconBack(color = TextPrimary, size = 18.dp)
            }

            Spacer(Modifier.width(10.dp))

            // Group avatar — initials in circle
            GroupInitialsAvatar(name = groupName, size = 36.dp)

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = groupName,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isChannel) "Channel" else "$memberCount members",
                    color = TextDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Box {
                IconButton(onClick = onMoreMenu, modifier = Modifier.size(32.dp)) {
                    PhIconMoreVert(color = TextDim, size = 18.dp)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onDismissMenu,
                    containerColor = Surface2,
                ) {
                    if (!isChannel) {
                        DropdownMenuItem(
                            text = { Text("Add Member", color = TextPrimary, fontSize = 14.sp) },
                            onClick = onAddMember,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Leave ${if (isChannel) "Channel" else "Group"}", color = Danger, fontSize = 14.sp) },
                        onClick = onLeaveGroup,
                    )
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
    }
}

// ── Initials avatar ────────────────────────────────────────────────────────────

@Composable
fun GroupInitialsAvatar(name: String, size: androidx.compose.ui.unit.Dp) {
    val initials = name.trim().split(" ").take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("").take(2).ifEmpty { "#" }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Surface2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = CyanAccent,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Input bar ─────────────────────────────────────────────────────────────────

@Composable
private fun GroupInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isRecording: Boolean,
    recordingDurationMs: Long,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
) {
    val recordingSeconds = recordingDurationMs / 1000
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.05f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isRecording) {
                // Recording indicator
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

            if (text.isNotBlank()) {
                // Send button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(CyanAccent)
                        .clickable(onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val sw = 2.2.dp.toPx()
                        val cap = StrokeCap.Round
                        val cx = size.width / 2f
                        drawLine(BgDeep, androidx.compose.ui.geometry.Offset(cx, size.height * 0.82f), androidx.compose.ui.geometry.Offset(cx, size.height * 0.18f), sw, cap)
                        drawLine(BgDeep, androidx.compose.ui.geometry.Offset(cx, size.height * 0.18f), androidx.compose.ui.geometry.Offset(cx - size.width * 0.28f, size.height * 0.46f), sw, cap)
                        drawLine(BgDeep, androidx.compose.ui.geometry.Offset(cx, size.height * 0.18f), androidx.compose.ui.geometry.Offset(cx + size.width * 0.28f, size.height * 0.46f), sw, cap)
                    }
                }
            } else {
                // Mic button — tap to record / tap again to stop+send
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) Danger else Surface2)
                        .clickable(onClick = onMicClick),
                    contentAlignment = Alignment.Center,
                ) {
                    PhIconMic(color = if (isRecording) Color.White else TextDim, size = 20.dp)
                }
            }
        }
    }
}
