package phantom.android.screens.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.GradientAvatar
import phantom.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(
    conversationId: String,
    theirUsername: String,
    container: AppContainer,
    onBack: () -> Unit,
    onMessage: () -> Unit = onBack,
    onDeleteConversation: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var conversation by remember {
        mutableStateOf(
            phantom.core.storage.ConversationEntity(
                id = conversationId,
                theirUsername = theirUsername,
                theirPublicKeyHex = "",
                lastMessagePreview = null,
                lastMessageAt = null,
                unreadCount = 0,
            )
        )
    }
    var notesText by remember { mutableStateOf("") }
    var savedNotesText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var keyCopied by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
                title = {},
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
            // ── Hero section ─────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(top = 32.dp, bottom = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GradientAvatar(
                        name = theirUsername,
                        size = 96.dp,
                        online = null,
                        ring = false,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "@$theirUsername",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Success.copy(alpha = 0.8f),
                            modifier = Modifier.size(11.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "end-to-end encrypted",
                            color = Success.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            letterSpacing = 0.3.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(1.dp))

            // ── Action buttons ────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Message button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyanAccent)
                        .clickable { onMessage() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Email, contentDescription = null, tint = BgDeep, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Message", color = BgDeep, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                // Block/Unblock button
                if (conversation.blocked) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Success.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    container.conversationRepo.unblockConversation(conversationId)
                                    conversation = conversation.copy(blocked = false)
                                }
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Success, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Unblock", color = Success, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Danger.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .clickable { showBlockDialog = true }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Block", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Public key card ───────────────────────────────────
            if (conversation.theirPublicKeyHex.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Text(
                        "IDENTITY KEY",
                        color = TextDim,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ed25519:",
                            color = CyanAccent.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = conversation.theirPublicKeyHex.take(16) + "…",
                            color = TextPrimary.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (keyCopied) Success.copy(alpha = 0.08f) else Surface2)
                            .border(
                                1.dp,
                                if (keyCopied) Success.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(6.dp),
                            )
                            .clickable {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("key", conversation.theirPublicKeyHex))
                                keyCopied = true
                                scope.launch { kotlinx.coroutines.delay(2000); keyCopied = false }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = if (keyCopied) "✓  Copied" else "Copy full key",
                            color = if (keyCopied) Success else TextDim,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Settings rows ─────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
            ) {
                ContactSettingsRow(
                    icon = Icons.Default.Lock,
                    label = "Disappearing messages",
                    value = "Off",
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = Color.White.copy(alpha = 0.04f))
                ContactSettingsRow(
                    icon = Icons.Default.Notifications,
                    label = "Notifications",
                    value = "On",
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = Color.White.copy(alpha = 0.04f))
                ContactSettingsRow(
                    icon = Icons.Default.Check,
                    label = "Verify safety number",
                    value = "",
                    onClick = {},
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Notes ─────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text("NOTE", color = TextDim, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
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
                        unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
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

            // ── Danger zone ───────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
            ) {
                TextButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                ) {
                    Text("Delete chat", color = Danger, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ContactSettingsRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = TextDim, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(text = value, color = TextDim, fontSize = 13.sp)
        }
    }
}
