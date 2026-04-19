package phantom.android.screens.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import phantom.android.di.AppContainer
import phantom.android.qr.QrCodeImage
import phantom.android.ui.GradientAvatar
import phantom.android.ui.theme.*
import phantom.core.identity.IdentityRecord
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onLogout: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var identity by remember { mutableStateOf<IdentityRecord?>(null) }
    var copied by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var blockedContacts by remember { mutableStateOf<List<phantom.core.storage.ConversationEntity>>(emptyList()) }
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val avatarFile = remember { File(context.filesDir, "profile_avatar.jpg") }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                avatarFile.outputStream().use { output -> input.copyTo(output) }
            }
            val bmp = BitmapFactory.decodeFile(avatarFile.absolutePath)
            withContext(Dispatchers.Main) {
                avatarBitmap = bmp?.asImageBitmap()
            }
        }
    }

    LaunchedEffect(Unit) {
        identity = container.identityRepo.loadIdentity()
        blockedContacts = container.conversationRepo.getBlockedConversations()
        if (avatarFile.exists()) {
            withContext(Dispatchers.IO) {
                val bmp = BitmapFactory.decodeFile(avatarFile.absolutePath)
                withContext(Dispatchers.Main) { avatarBitmap = bmp?.asImageBitmap() }
            }
        }
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "PROFILE",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 4.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))

            // Avatar + username hero (tap to change photo)
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .clickable { photoPicker.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (avatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBitmap!!,
                        contentDescription = "Profile photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                } else {
                    GradientAvatar(
                        name = identity?.username ?: "?",
                        size = 96.dp,
                        online = null,
                        ring = true,
                    )
                }
                // Camera overlay hint
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(CyanAccent)
                        .border(2.dp, BgDeep, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(14.dp)) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        // camera body
                        drawRoundRect(
                            color = BgDeep,
                            size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.72f),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, cy * 0.4f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        )
                        // lens
                        drawCircle(color = BgDeep, radius = size.width * 0.22f, center = androidx.compose.ui.geometry.Offset(cx, cy + cy * 0.15f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        // viewfinder bump
                        drawRoundRect(
                            color = BgDeep,
                            size = androidx.compose.ui.geometry.Size(size.width * 0.3f, cy * 0.35f),
                            topLeft = androidx.compose.ui.geometry.Offset(cx - size.width * 0.15f, cy * 0.1f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = identity?.username?.let { "@$it" } ?: "Loading…",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Your identity",
                color = TextDim,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(28.dp))

            // QR card
            identity?.let { id ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(Surface, RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "INVITE QR",
                        color = TextDim,
                        fontSize = 10.sp,
                        letterSpacing = 2.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(contentAlignment = Alignment.Center) {
                        QrCodeImage(content = "${id.username}:${id.publicKeyHex}", size = 200.dp)
                        // Cyan center dot (brand mark)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(BgDeep)
                                .border(2.dp, CyanAccent.copy(alpha = 0.6f), CircleShape),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Let your contact scan this to add you",
                        color = TextDim,
                        fontSize = 12.sp,
                    )
                }

                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Identity key card
            identity?.let { id ->
                val identityString = "${id.username}:${id.publicKeyHex}"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(Surface, RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .padding(20.dp),
                ) {
                    Text(
                        text = "IDENTITY KEY",
                        color = TextDim,
                        fontSize = 10.sp,
                        letterSpacing = 2.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                    )

                    Spacer(Modifier.height(12.dp))

                    // Key preview with ed25519: prefix
                    Row {
                        Text(
                            text = "ed25519:",
                            color = CyanAccent.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = id.publicKeyHex.take(12) + "…" + id.publicKeyHex.takeLast(6),
                            color = TextPrimary.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Share button (full width, cyan)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyanAccent)
                            .clickable {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, identityString)
                                    putExtra(Intent.EXTRA_SUBJECT, "PHANTOM identity key")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share via…"))
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Share my key",
                            color = BgDeep,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Copy button (outline)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, if (copied) Success.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .clickable {
                                copyToClipboard(context, identityString)
                                copied = true
                                scope.launch { kotlinx.coroutines.delay(2000); copied = false }
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (copied) "✓  Copied to clipboard" else "Copy key",
                            color = if (copied) Success else TextDim,
                            fontSize = 14.sp,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    Spacer(Modifier.height(12.dp))

                    // Full key expandable
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        text = if (expanded) "Hide full key ↑" else "Show full key ↓",
                        color = TextDim,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = id.publicKeyHex.chunked(8).joinToString(" "),
                            color = TextDim,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Start,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Info card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(Surface, RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(16.dp),
                ) {
                    InfoRow(label = "Algorithm", value = "Ed25519 / X25519")
                    Spacer(Modifier.height(10.dp))
                    InfoRow(label = "Key ID", value = id.publicKeyHex.take(12) + "…")
                    Spacer(Modifier.height(10.dp))
                    InfoRow(label = "Created", value = formatTimestamp(id.createdAt))
                }

            }

            Spacer(Modifier.height(8.dp))

            // Settings list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Surface, RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
            ) {
                ProfileSettingsRow(label = "Username", value = identity?.username?.let { "@$it" } ?: "")
                HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = Color.White.copy(alpha = 0.04f))
                ProfileSettingsRow(label = "Safety numbers", value = "")
                HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = Color.White.copy(alpha = 0.04f))
                ProfileSettingsRow(label = "Linked devices", value = "1 device")
                HorizontalDivider(modifier = Modifier.padding(start = 20.dp), color = Color.White.copy(alpha = 0.04f))
                ProfileSettingsRow(label = "Privacy", value = "")
            }

            Spacer(Modifier.height(24.dp))

            // Blocked contacts
            if (blockedContacts.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface)
                ) {
                    Text(
                        text = "BLOCKED CONTACTS",
                        color = TextDim,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    HorizontalDivider(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f))
                    blockedContacts.forEach { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Surface2),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = contact.theirUsername.take(1).uppercase(),
                                    color = TextDim,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "@${contact.theirUsername}",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        container.conversationRepo.unblockConversation(contact.id)
                                        blockedContacts = container.conversationRepo.getBlockedConversations()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text("Unblock", color = CyanAccent, fontSize = 13.sp)
                            }
                        }
                        if (contact != blockedContacts.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 64.dp),
                                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.04f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Sign out button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Danger.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { showLogoutDialog = true }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Sign Out",
                    color = Danger,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    if (showLogoutDialog) {
        var confirmText by remember { mutableStateOf("") }
        val username = identity?.username ?: ""
        val confirmed = confirmText.trim().lowercase() == username.lowercase()

        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = Surface,
            title = {
                Text("Destroy identity?", color = Danger, fontWeight = FontWeight.Medium)
            },
            text = {
                Column {
                    Text(
                        "⚠  This is not a logout.",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your encryption keys will be permanently deleted from this device. " +
                        "There is no recovery — no phone number, no seed phrase, no backup.",
                        color = TextDim,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Your contacts will no longer be able to reach you at this identity.",
                        color = TextDim,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Type your username to confirm:",
                        color = TextDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        placeholder = { Text("@$username", color = TextDim.copy(alpha = 0.5f)) },
                        singleLine = true,
                        isError = confirmText.isNotEmpty() && !confirmed,
                        supportingText = {
                            if (confirmText.isNotEmpty() && !confirmed) {
                                Text("Username doesn't match", color = Danger, fontSize = 11.sp)
                            } else if (confirmed) {
                                Text("✓  Confirmed", color = Success, fontSize = 11.sp)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = when {
                                confirmed -> Success
                                confirmText.isNotEmpty() -> Danger
                                else -> TextDim.copy(alpha = 0.4f)
                            },
                            unfocusedBorderColor = when {
                                confirmed -> Success.copy(alpha = 0.6f)
                                confirmText.isNotEmpty() -> Danger.copy(alpha = 0.6f)
                                else -> TextDim.copy(alpha = 0.3f)
                            },
                            errorBorderColor = Danger,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (confirmed) {
                            scope.launch {
                                container.identityRepo.deleteIdentity()
                                onLogout()
                            }
                        }
                    },
                    enabled = confirmed,
                ) {
                    Text(
                        "Destroy",
                        color = if (confirmed) Danger else TextDim,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    confirmText = ""
                }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextDim, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ProfileSettingsRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Phantom Public Key", text))
}

private fun formatTimestamp(millis: Long): String {
    val date = java.util.Date(millis)
    val fmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
    return fmt.format(date)
}
