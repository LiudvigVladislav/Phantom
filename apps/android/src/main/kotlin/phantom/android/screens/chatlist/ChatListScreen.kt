package phantom.android.screens.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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

@Composable
fun ChatListScreen(
    container: AppContainer,
    onNavigate: (Screen) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        conversations = container.conversationRepo.getAllConversations()
    }

    // Incoming message updates
    LaunchedEffect(container.messagingService) {
        container.messagingService?.incomingMessages?.collect {
            conversations = container.conversationRepo.getAllConversations()
        }
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = { ChatListTopBar(onAddClick = { showAddDialog = true }) },
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
        if (conversations.isEmpty()) {
            EmptyState(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
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

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { pubKeyHex ->
                scope.launch {
                    val convId = buildConversationId(container, pubKeyHex)
                    container.conversationRepo.upsertConversation(
                        ConversationEntity(
                            id = convId,
                            theirUsername = pubKeyHex.take(8),
                            theirPublicKeyHex = pubKeyHex,
                            lastMessagePreview = null,
                            lastMessageAt = null,
                            unreadCount = 0,
                        )
                    )
                    conversations = container.conversationRepo.getAllConversations()
                    showAddDialog = false
                    onNavigate(Screen.Chat(convId, pubKeyHex.take(8)))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListTopBar(onAddClick: () -> Unit) {
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
        // Avatar circle
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
            if (entity.lastMessagePreview != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entity.lastMessagePreview,
                    color = TextDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }

        if (entity.unreadCount > 0) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(CyanAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (entity.unreadCount > 99) "99+" else entity.unreadCount.toString(),
                    color = BgDeep,
                    fontSize = 9.sp,
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
private fun AddContactDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var pubKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Add contact", color = TextPrimary) },
        text = {
            Column {
                Text("Paste their public key", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pubKey,
                    onValueChange = { pubKey = it.trim() },
                    placeholder = { Text("Public key hex…", color = TextDim) },
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
                onClick = { if (pubKey.isNotEmpty()) onAdd(pubKey) },
                enabled = pubKey.isNotEmpty(),
            ) {
                Text("Add", color = CyanAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDim)
            }
        },
    )
}

private suspend fun buildConversationId(container: AppContainer, theirKey: String): String {
    val myKey = container.identityRepo.loadIdentity()?.publicKeyHex ?: ""
    return listOf(myKey, theirKey).sorted().joinToString("_")
}
