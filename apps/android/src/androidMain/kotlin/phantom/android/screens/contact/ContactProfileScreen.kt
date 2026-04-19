package phantom.android.screens.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(
    conversationId: String,
    theirUsername: String,
    container: AppContainer,
    onBack: () -> Unit,
    onDeleteConversation: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var conversation by remember { mutableStateOf(
        phantom.core.storage.ConversationEntity(
            id = conversationId,
            theirUsername = theirUsername,
            theirPublicKeyHex = "",
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
        )
    ) }
    var notesText by remember { mutableStateOf("") }
    // savedNotesText tracks what's actually in DB — button enabled only when text differs
    var savedNotesText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }

    val notesSaved = notesText == savedNotesText

    LaunchedEffect(conversationId) {
        val conv = container.conversationRepo.getConversation(conversationId)
        if (conv != null) {
            conversation = conv
            notesText = conv.notes ?: ""
            savedNotesText = conv.notes ?: ""
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Surface,
            title = { Text("Delete chat", color = TextPrimary) },
            text = { Text("Delete conversation with @$theirUsername? This cannot be undone.", color = TextDim, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        container.conversationRepo.deleteConversation(conversationId)
                        onDeleteConversation()
                    }
                }) { Text("Delete", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = TextDim) }
            },
        )
    }

    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            containerColor = Surface,
            title = { Text("Block contact", color = TextPrimary) },
            text = { Text("Block @$theirUsername? You will no longer receive messages from them.", color = TextDim, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showBlockDialog = false
                    scope.launch {
                        container.conversationRepo.blockConversation(conversationId)
                        onBack()
                    }
                }) { Text("Block", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) { Text("Cancel", color = TextDim) }
            },
        )
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            TopAppBar(
                title = { Text("Profile", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Normal) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Avatar + name block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(CyanAccent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = theirUsername.take(1).uppercase(),
                            color = CyanAccent,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(text = "@$theirUsername", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(text = "end-to-end encrypted", color = Success.copy(alpha = 0.8f), fontSize = 12.sp, letterSpacing = 0.3.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Public key
            if (conversation.theirPublicKeyHex.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("Public key", color = TextDim, fontSize = 11.sp, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = conversation.theirPublicKeyHex,
                        color = TextPrimary.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(1.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Notes about this contact
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text("Note about contact", color = TextDim, fontSize = 11.sp, letterSpacing = 0.5.sp)
                Text("Only visible to you — not synced", color = TextDim.copy(alpha = 0.5f), fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Add a note…", color = TextDim.copy(alpha = 0.5f), fontSize = 14.sp) },
                    minLines = 3,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent.copy(alpha = 0.5f),
                        unfocusedBorderColor = TextDim.copy(alpha = 0.2f),
                        cursorColor = CyanAccent,
                        focusedContainerColor = Surface2,
                        unfocusedContainerColor = Surface2,
                    ),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (notesSaved && savedNotesText.isNotEmpty()) {
                        Text("Saved", color = Success, fontSize = 12.sp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Button(
                        onClick = {
                            val textToSave = notesText
                            scope.launch {
                                container.conversationRepo.updateNotes(conversationId, textToSave.ifBlank { null })
                                savedNotesText = textToSave
                            }
                        },
                        enabled = !notesSaved,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent,
                            contentColor = BgDeep,
                            disabledContainerColor = TextDim.copy(alpha = 0.15f),
                            disabledContentColor = TextDim,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Save", fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Danger zone
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
            ) {
                if (conversation.blocked) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                container.conversationRepo.unblockConversation(conversationId)
                                conversation = conversation.copy(blocked = false)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    ) {
                        Text("✓", fontSize = 16.sp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Unblock @$theirUsername", color = Success, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                    }
                } else {
                    TextButton(
                        onClick = { showBlockDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    ) {
                        Text("🚫", fontSize = 16.sp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Block @$theirUsername", color = Danger, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                    }
                }
                HorizontalDivider(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.04f), thickness = 1.dp)
                TextButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                ) {
                    Text("🗑", fontSize = 16.sp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Delete chat", color = Danger, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
