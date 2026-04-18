package phantom.android.screens.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.qr.QrCodeImage
import phantom.android.ui.theme.*
import phantom.core.identity.IdentityRecord

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

    LaunchedEffect(Unit) {
        identity = container.identityRepo.loadIdentity()
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
            Spacer(Modifier.height(40.dp))

            // Avatar
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(CyanAccent.copy(alpha = 0.15f))
                    .border(1.dp, CyanAccent.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = identity?.username?.take(1)?.uppercase() ?: "?",
                    color = CyanAccent,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Username
            Text(
                text = identity?.username?.let { "@$it" } ?: "Loading…",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Your identity",
                color = TextDim,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(32.dp))

            // QR Code
            identity?.let { id ->
                Text(
                    text = "INVITE QR",
                    color = TextDim,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(12.dp))
                QrCodeImage(content = "${id.username}:${id.publicKeyHex}", size = 200.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Let your contact scan this",
                    color = TextDim,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(32.dp))

            // Identity key card
            identity?.let { id ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(Surface, RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .padding(16.dp),
                ) {
                    Text(
                        text = "IDENTITY KEY",
                        color = TextDim,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium,
                    )

                    Spacer(Modifier.height(12.dp))

                    // Short key display
                    val shortKey = buildString {
                        append(id.publicKeyHex.take(16))
                        append(" ···· ")
                        append(id.publicKeyHex.takeLast(8))
                    }
                    Text(
                        text = shortKey,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Light,
                    )

                    Spacer(Modifier.height(12.dp))

                    // Copy button — copies "username:pubkey_hex" so recipients see your name
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (copied) Success.copy(alpha = 0.1f) else CyanAccent.copy(alpha = 0.08f))
                            .border(
                                1.dp,
                                if (copied) Success.copy(alpha = 0.4f) else CyanAccent.copy(alpha = 0.25f),
                                RoundedCornerShape(4.dp),
                            )
                            .clickable {
                                val identityString = "${id.username}:${id.publicKeyHex}"
                                copyToClipboard(context, identityString)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, identityString)
                                    putExtra(Intent.EXTRA_SUBJECT, "PHANTOM identity key")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share via…"))
                                copied = true
                                scope.launch {
                                    kotlinx.coroutines.delay(2000)
                                    copied = false
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (copied) "✓  Shared!" else "Share identity",
                            color = if (copied) Success else CyanAccent,
                            fontSize = 12.sp,
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

                Spacer(Modifier.height(24.dp))

                // Security note
                Text(
                    text = "Share your key with contacts so they can send you encrypted messages.",
                    color = TextDim.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                    lineHeight = 18.sp,
                )
            }

            Spacer(Modifier.height(32.dp))

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

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Phantom Public Key", text))
}

private fun formatTimestamp(millis: Long): String {
    val date = java.util.Date(millis)
    val fmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
    return fmt.format(date)
}
