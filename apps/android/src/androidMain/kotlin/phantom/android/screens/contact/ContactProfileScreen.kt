// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.contact

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.*
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono

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
    val context = LocalContext.current

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
    var showMoreMenu by remember { mutableStateOf(false) }
    var keyCopied by remember { mutableStateOf(false) }
    var showVerifySheet by remember { mutableStateOf(false) }
    var isVerified by remember { mutableStateOf(false) }
    var keyChangedAt by remember { mutableStateOf<Long?>(null) }
    var showTimerSheet by remember { mutableStateOf(false) }
    var disappearingTimer by remember { mutableStateOf(0L) }

    val timerOptions = listOf(
        0L to "Off",
        30L to "30 seconds",
        300L to "5 minutes",
        3600L to "1 hour",
        86400L to "1 day",
        604800L to "1 week",
    )

    fun timerLabel(secs: Long): String =
        timerOptions.firstOrNull { it.first == secs }?.second ?: "Off"

    LaunchedEffect(conversationId) {
        val conv = container.conversationRepo.getConversation(conversationId)
        if (conv != null) {
            conversation = conv
            notesText = conv.notes ?: ""
            savedNotesText = conv.notes ?: ""
            isVerified = conv.isVerified
            disappearingTimer = conv.disappearingTimerSecs
            keyChangedAt = conv.identityKeyChangedAt
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Surface,
            title = { Text("Delete conversation?", color = TextPrimary) },
            text = {
                Text(
                    "This removes all messages locally. Contact won't be notified.",
                    color = TextDim, fontSize = 14.sp,
                )
            },
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
            title = { Text("Block @$theirUsername?", color = TextPrimary) },
            text = {
                Text(
                    "You will no longer receive messages from them.",
                    color = TextDim, fontSize = 14.sp,
                )
            },
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

    // ── Screen ───────────────────────────────────────────────────────────────

    Scaffold(
        containerColor = BgDeep,
        contentWindowInsets = WindowInsets(0),
        topBar = {
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
                    // Back — flat icon (mockup spec, no Surface2 chip).
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        PhIconBack(color = TextPrimary, size = 20.dp)
                    }

                    // Title — overline mono 11sp 0.08em tracked.
                    Text(
                        text = "CONTACT",
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = TextDim,
                        fontSize = 11.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 0.88.sp,
                        fontWeight = FontWeight.Normal,
                    )

                    // More menu — flat icon, matching back button.
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            PhIconMoreVert(color = TextPrimary, size = 20.dp)
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            containerColor = Surface2,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Report", color = TextPrimary, fontSize = 14.sp) },
                                onClick = { showMoreMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Block", color = Danger, fontSize = 14.sp) },
                                onClick = { showMoreMenu = false; showBlockDialog = true },
                            )
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Key change warning banner ──────────────────────────────────────
            if (conversation.identityKeyChangedAt != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Danger.copy(alpha = 0.12f))
                        .clickable { showVerifySheet = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PhIconShield(color = Danger, size = 18.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Safety number changed",
                            color = Danger,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "This contact may have reinstalled. Tap to re-verify.",
                            color = Danger.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            // ── Hero card ─────────────────────────────────────────────────────
            ContactCard(topPad = 24.dp, bottomPad = 20.dp) {
                Box(contentAlignment = Alignment.Center) {
                    GradientAvatar(name = theirUsername, size = 96.dp)
                    // Online dot
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-4).dp, y = (-4).dp)
                            .size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Surface)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Success),
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "@$theirUsername",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Last seen recently",
                    color = TextDim,
                    fontSize = 11.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 0.4.sp,
                )

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Message button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CyanAccent)
                            .clickable(onClick = onMessage),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(size.width * 0.1f, size.height * 0.2f)
                                    lineTo(size.width * 0.9f, size.height * 0.2f)
                                    lineTo(size.width * 0.9f, size.height * 0.72f)
                                    lineTo(size.width * 0.6f, size.height * 0.72f)
                                    lineTo(size.width * 0.35f, size.height * 0.95f)
                                    lineTo(size.width * 0.35f, size.height * 0.72f)
                                    lineTo(size.width * 0.1f, size.height * 0.72f)
                                    close()
                                }
                                drawPath(path, BgDeep, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                            }
                            Text("Message", color = BgDeep, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Block / Unblock button
                    if (conversation.blocked) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Success.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    scope.launch {
                                        container.conversationRepo.unblockConversation(conversationId)
                                        conversation = conversation.copy(blocked = false)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PhIconCheck(color = Success, size = 16.dp)
                                Text("Unblock", color = Success, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Danger, RoundedCornerShape(12.dp))
                                .clickable { showBlockDialog = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Canvas(modifier = Modifier.size(16.dp)) {
                                    val r = size.minDimension / 2f - 1.dp.toPx()
                                    val sw = 1.6.dp.toPx()
                                    drawCircle(Danger, radius = r, style = Stroke(sw))
                                    drawLine(Danger, androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.2f), androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.8f), sw, StrokeCap.Round)
                                }
                                Text("Block", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // ── Public key card ───────────────────────────────────────────────
            ContactCard {
                CContactSectionHeader(label = "Public Key", icon = {
                    Canvas(Modifier.size(12.dp)) {
                        val r = size.minDimension / 2f - 0.5.dp.toPx()
                        drawCircle(Success, radius = r * 0.55f)
                        drawCircle(Success, radius = r, style = Stroke(1.dp.toPx()))
                    }
                })

                // Key preview box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "ed25519",
                                color = CyanAccent,
                                fontSize = 9.sp,
                                fontFamily = PhantomFontMono,
                                letterSpacing = 2.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "verified · ${java.text.SimpleDateFormat("dd MMM", java.util.Locale.US).format(java.util.Date())}",
                                color = TextDim,
                                fontSize = 9.sp,
                                fontFamily = PhantomFontMono,
                                letterSpacing = 1.6.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        val keyPreview = if (conversation.theirPublicKeyHex.isNotEmpty()) {
                            conversation.theirPublicKeyHex.chunked(4).take(8).joinToString("  ") + "  …"
                        } else {
                            "9C3F  4A2B  81E7  D05C  2F1A  B6E4  77D9  …"
                        }
                        Text(
                            text = keyPreview,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = PhantomFontMono,
                            letterSpacing = 0.6.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }

                // Copy key row
                CKeyRow(
                    icon = {
                        Canvas(Modifier.size(14.dp)) {
                            val sw = 1.4.dp.toPx()
                            val st = Stroke(sw, cap = StrokeCap.Round)
                            drawRoundRect(CyanAccent, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.28f, 0f), size = androidx.compose.ui.geometry.Size(size.width * 0.72f, size.height * 0.72f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = st)
                            drawRoundRect(CyanAccent, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.28f), size = androidx.compose.ui.geometry.Size(size.width * 0.72f, size.height * 0.72f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = st)
                        }
                    },
                    label = "Copy key",
                    value = "32-byte fingerprint",
                    right = {
                        Text(
                            text = if (keyCopied) "Copied" else "Copy",
                            color = if (keyCopied) Success else CyanAccent,
                            fontSize = 10.sp,
                            fontFamily = PhantomFontMono,
                            letterSpacing = 1.8.sp,
                        )
                    },
                    onClick = {
                        val key = conversation.theirPublicKeyHex.ifEmpty { "ed25519:placeholder" }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("key", key))
                        keyCopied = true
                        scope.launch { delay(2000); keyCopied = false }
                    },
                )

                // Verify row
                CKeyRow(
                    icon = {
                        if (isVerified) {
                            PhIconShieldCheck(color = Success, size = 16.dp)
                        } else {
                            Canvas(Modifier.size(16.dp)) {
                                val sw = 1.4.dp.toPx()
                                val st = Stroke(sw, cap = StrokeCap.Round)
                                val r = size.minDimension / 2f - 1.dp.toPx()
                                drawCircle(CyanAccent, radius = r, style = st)
                                drawLine(CyanAccent, androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.35f), androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.62f), sw, StrokeCap.Round)
                                drawCircle(CyanAccent, radius = 1.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.72f))
                            }
                        }
                    },
                    label = "Safety number",
                    value = if (isVerified) "Verified" else "Tap to verify",
                    right = {
                        Canvas(Modifier.size(14.dp)) {
                            val sw = 1.4.dp.toPx()
                            val tint = if (isVerified) Success else TextDim
                            drawLine(tint, androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.2f), androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.5f), sw, StrokeCap.Round)
                            drawLine(tint, androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.5f), androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.8f), sw, StrokeCap.Round)
                        }
                    },
                    onClick = { showVerifySheet = true },
                )
            }

            // ── Notes card ────────────────────────────────────────────────────
            ContactCard {
                CContactSectionHeader(label = "Notes", icon = {
                    Canvas(Modifier.size(12.dp)) {
                        val sw = 1.3.dp.toPx()
                        val st = Stroke(sw, cap = StrokeCap.Round)
                        drawLine(TextDim, androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.3f), androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.3f), sw, StrokeCap.Round)
                        drawLine(TextDim, androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.55f), androidx.compose.ui.geometry.Offset(size.width * 0.65f, size.height * 0.55f), sw, StrokeCap.Round)
                        drawLine(TextDim, androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.8f), androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.8f), sw, StrokeCap.Round)
                    }
                })

                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 76.dp),
                    placeholder = { Text("Add private notes about this contact…", color = TextDim, fontSize = 14.sp) },
                    minLines = 3,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = CyanAccent,
                        focusedContainerColor = Surface2,
                        unfocusedContainerColor = Surface2,
                    ),
                    shape = RoundedCornerShape(10.dp),
                )

                if (notesText != savedNotesText) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CyanAccent)
                            .clickable {
                                val text = notesText
                                scope.launch {
                                    container.conversationRepo.updateNotes(conversationId, text.ifBlank { null })
                                    savedNotesText = text
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Save note", color = BgDeep, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Canvas(Modifier.size(10.dp)) {
                        drawCircle(Success, radius = size.minDimension / 2f - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                        drawCircle(Success, radius = size.minDimension * 0.2f)
                    }
                    Text(
                        "Encrypted · stored locally only",
                        color = TextDim,
                        fontSize = 9.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 1.8.sp,
                    )
                }
            }

            // ── Settings card ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column {
                    CKeyRow(
                        icon = {
                            Canvas(Modifier.size(16.dp)) {
                                val sw = 1.4.dp.toPx(); val st = Stroke(sw, cap = StrokeCap.Round)
                                drawOval(CyanAccent, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.25f, size.height * 0.1f), size = androidx.compose.ui.geometry.Size(size.width * 0.5f, size.height * 0.5f), style = st)
                                drawLine(CyanAccent, androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.6f), androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.9f), sw, StrokeCap.Round)
                            }
                        },
                        label = "Disappearing messages",
                        value = timerLabel(disappearingTimer),
                        right = { ChevronIcon() },
                        onClick = { showTimerSheet = true },
                    )
                    CKeyRow(
                        icon = {
                            Canvas(Modifier.size(16.dp)) {
                                val sw = 1.4.dp.toPx(); val st = Stroke(sw, cap = StrokeCap.Round)
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(size.width * 0.5f, size.height * 0.08f)
                                    cubicTo(size.width * 0.2f, size.height * 0.08f, size.width * 0.08f, size.height * 0.35f, size.width * 0.08f, size.height * 0.58f)
                                    cubicTo(size.width * 0.08f, size.height * 0.75f, size.width * 0.15f, size.height * 0.82f, size.width * 0.5f, size.height * 0.85f)
                                    cubicTo(size.width * 0.85f, size.height * 0.82f, size.width * 0.92f, size.height * 0.75f, size.width * 0.92f, size.height * 0.58f)
                                    cubicTo(size.width * 0.92f, size.height * 0.35f, size.width * 0.8f, size.height * 0.08f, size.width * 0.5f, size.height * 0.08f)
                                    close()
                                }
                                drawPath(path, CyanAccent, style = st)
                                drawLine(CyanAccent, androidx.compose.ui.geometry.Offset(size.width * 0.35f, size.height * 0.88f), androidx.compose.ui.geometry.Offset(size.width * 0.65f, size.height * 0.88f), sw, StrokeCap.Round)
                                drawLine(CyanAccent, androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.88f), androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.98f), sw, StrokeCap.Round)
                            }
                        },
                        label = "Notifications",
                        value = "Default · with preview",
                        right = { ChevronIcon() },
                    )
                    CKeyRow(
                        icon = {
                            Canvas(Modifier.size(16.dp)) {
                                val sw = 1.4.dp.toPx(); val st = Stroke(sw, cap = StrokeCap.Round)
                                drawLine(CyanAccent, androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.5f), androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.5f), sw, StrokeCap.Round)
                                drawLine(CyanAccent, androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.15f), androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.85f), sw, StrokeCap.Round)
                                drawCircle(CyanAccent, radius = size.minDimension / 2f - 0.5.dp.toPx(), style = st)
                            }
                        },
                        label = "Media auto-download",
                        value = "Wi-Fi only",
                        right = { ChevronIcon() },
                        showTopDivider = false,
                    )
                }
            }

            // ── Danger zone ───────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 20.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Danger, RoundedCornerShape(12.dp))
                        .clickable { showDeleteDialog = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Canvas(Modifier.size(15.dp)) {
                            val sw = 1.5.dp.toPx(); val st = Stroke(sw, cap = StrokeCap.Round)
                            drawLine(Danger, androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.28f), androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.28f), sw, StrokeCap.Round)
                            drawRoundRect(Danger, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.28f), size = androidx.compose.ui.geometry.Size(size.width * 0.4f, size.height * 0.65f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = st)
                            drawLine(Danger, androidx.compose.ui.geometry.Offset(size.width * 0.38f, size.height * 0.12f), androidx.compose.ui.geometry.Offset(size.width * 0.62f, size.height * 0.12f), sw, StrokeCap.Round)
                        }
                        Text("Delete conversation", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "This removes all messages locally. Contact won't be notified.",
                    color = TextDim,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    // ── Safety-number verification sheet ─────────────────────────────────────
    if (showVerifySheet) {
        val theirPublicKeyHex = conversation.theirPublicKeyHex

        // Load own public key once when the sheet opens.
        val myPubKeyHex by produceState(initialValue = "") {
            value = container.identityRepo.loadIdentity()?.publicKeyHex ?: ""
        }

        val fingerprint = remember(myPubKeyHex, theirPublicKeyHex) {
            if (myPubKeyHex.isNotEmpty() && theirPublicKeyHex.isNotEmpty()) {
                phantom.core.crypto.SafetyNumber.compute(myPubKeyHex, theirPublicKeyHex)
            } else {
                "loading…"
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showVerifySheet = false },
            containerColor = Surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "SAFETY NUMBER",
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 2.sp,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface2, RoundedCornerShape(12.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = fingerprint,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 3.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                Text(
                    text = "Compare this number with your contact in person or via another channel.",
                    color = TextDim,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Button(
                    onClick = {
                        scope.launch {
                            container.conversationRepo.setVerified(conversationId, true)
                            container.conversationRepo.clearIdentityKeyChangedAt(conversationId)
                            isVerified = true
                            keyChangedAt = null
                            conversation = conversation.copy(identityKeyChangedAt = null)
                            showVerifySheet = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Success),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (isVerified) "Verified" else "Mark as Verified",
                        color = Color.Black,
                    )
                }
            }
        }
    }

    // ── Disappearing-messages timer sheet ────────────────────────────────────
    if (showTimerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTimerSheet = false },
            containerColor = Surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = "DISAPPEARING MESSAGES",
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                timerOptions.forEach { (secs, label) ->
                    val selected = disappearingTimer == secs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    container.conversationRepo.setDisappearingTimer(conversationId, secs)
                                    val recipientKey = conversation.theirPublicKeyHex
                                    if (recipientKey.isNotEmpty()) {
                                        container.messagingService?.sendDisappearingTimerUpdate(
                                            timerSecs = secs,
                                            conversationId = conversationId,
                                            recipientPublicKeyHex = recipientKey,
                                        )
                                    }
                                    disappearingTimer = secs
                                    showTimerSheet = false
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            color = if (selected) CyanAccent else TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            PhIconCheck(color = CyanAccent, size = 18.dp)
                        }
                    }
                }
            }
        }
    }
}

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
private fun ContactCard(
    topPad: androidx.compose.ui.unit.Dp = 16.dp,
    bottomPad: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp)
            .padding(top = topPad, bottom = bottomPad),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun CContactSectionHeader(label: String, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon()
        Text(
            text = label.uppercase(),
            color = TextDim,
            fontSize = 10.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 2.4.sp,
        )
    }
}

@Composable
private fun CKeyRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String = "",
    right: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showTopDivider: Boolean = true,
) {
    Column {
        if (showTopDivider) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface2),
                contentAlignment = Alignment.Center,
            ) { icon() }

            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = TextPrimary, fontSize = 14.sp, letterSpacing = (-0.1).sp)
                if (value.isNotEmpty()) {
                    Text(
                        text = value,
                        color = TextDim,
                        fontSize = 11.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 0.2.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            right?.invoke()
        }
    }
}

@Composable
private fun ChevronIcon() {
    Canvas(Modifier.size(14.dp)) {
        val sw = 1.4.dp.toPx()
        drawLine(TextDim, androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.2f), androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.5f), sw, StrokeCap.Round)
        drawLine(TextDim, androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.5f), androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.8f), sw, StrokeCap.Round)
    }
}
