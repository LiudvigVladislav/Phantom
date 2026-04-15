package phantom.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.theme.*
import phantom.core.messaging.OutgoingMessage
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    theirUsername: String,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    suspend fun reloadMessages() {
        messages = container.messageRepo.getMessages(conversationId)
    }

    LaunchedEffect(conversationId) {
        reloadMessages()
        container.conversationRepo.resetUnread(conversationId)
    }

    // Live incoming messages
    LaunchedEffect(container.messagingService) {
        container.messagingService?.incomingMessages?.collect { incoming ->
            if (incoming.conversationId == conversationId) {
                reloadMessages()
                listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
            }
        }
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(theirUsername, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Normal)
                        Text("E2E encrypted · relay", color = Success, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        },
        bottomBar = {
            InputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    val text = inputText.trim()
                    if (text.isEmpty()) return@InputBar
                    inputText = ""
                    scope.launch {
                        val conversation = container.conversationRepo.getConversation(conversationId) ?: return@launch
                        container.messagingService?.sendMessage(
                            OutgoingMessage(
                                id = uuid4().toString(),
                                conversationId = conversationId,
                                recipientPublicKeyHex = conversation.theirPublicKeyHex,
                                text = text,
                            )
                        )
                        reloadMessages()
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.lastIndex)
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(entity = msg)
            }
        }
    }
}

@Composable
private fun MessageBubble(entity: MessageEntity) {
    val isSent = entity.sent
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = if (isSent) CyanAccent else Surface2,
                    shape = RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (isSent) 18.dp else 4.dp,
                        bottomEnd = if (isSent) 4.dp else 18.dp,
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                Text(
                    text = entity.plaintextCache ?: "•••",
                    color = if (isSent) BgDeep else TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = statusLabel(entity.status),
                        color = if (isSent) BgDeep.copy(alpha = 0.6f) else TextDim,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

private fun statusLabel(status: MessageStatus): String = when (status) {
    MessageStatus.QUEUED    -> "⏳"
    MessageStatus.SENT      -> "✓"
    MessageStatus.RELAYED   -> "✓✓"
    MessageStatus.DELIVERED -> "✓✓"
    MessageStatus.READ      -> "✓✓"
}

@Composable
private fun InputBar(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = Surface, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    focusedBorderColor = CyanAccent.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    cursorColor = CyanAccent,
                ),
                shape = RoundedCornerShape(20.dp),
            )

            Spacer(Modifier.width(8.dp))

            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (text.isNotBlank()) CyanAccent else CyanAccent.copy(alpha = 0.2f),
                        shape = CircleShape,
                    ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank()) BgDeep else TextDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private val CircleShape = RoundedCornerShape(50)
