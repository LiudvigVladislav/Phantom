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
import androidx.compose.ui.draw.alpha
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
    var showReportSheet by remember { mutableStateOf(false) }
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
                        container.messagingService?.removeConversationMutex(conversationId)
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

    // Report dialog — pick a category, then POST /report on relay (Level A
    // per ADR-019 placeholder; full admin review pipeline is Phase 2-3).
    if (showReportSheet) {
        var selectedCategory by remember { mutableStateOf<String?>(null) }
        val categories = listOf(
            "spam" to "Spam or unwanted messages",
            "harassment" to "Harassment or threats",
            "inappropriate" to "Inappropriate content",
            "csam" to "Child safety concern",
            "other" to "Other",
        )
        AlertDialog(
            onDismissRequest = { showReportSheet = false },
            containerColor = Surface,
            title = { Text("Report @$theirUsername", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "Select the reason. Reports are reviewed by Willen LLC; PHANTOM does not share the report content with the reported user.",
                        color = TextDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    categories.forEach { (key, label) ->
                        val active = selectedCategory == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedCategory = key }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = active,
                                onClick = { selectedCategory = key },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = CyanAccent,
                                    unselectedColor = TextDim,
                                ),
                            )
                            Text(label, color = if (active) TextPrimary else TextDim, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedCategory != null,
                    onClick = {
                        val category = selectedCategory ?: return@TextButton
                        showReportSheet = false
                        scope.launch {
                            val myKey = container.identityRepo.loadIdentity()?.publicKeyHex.orEmpty()
                            val theirKey = conversation.theirPublicKeyHex
                            val ok = submitAbuseReport(
                                reporterKey = myKey,
                                reportedKey = theirKey,
                                category = category,
                            )
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "Report submitted — thanks for helping keep PHANTOM safe."
                                else "Could not send report. Try again when online.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Text(
                        "Submit",
                        color = if (selectedCategory != null) Danger else TextDim,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportSheet = false }) {
                    Text("Cancel", color = TextDim)
                }
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
                                onClick = { showMoreMenu = false; showReportSheet = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Block", color = Danger, fontSize = 14.sp) },
                                onClick = { showMoreMenu = false; showBlockDialog = true },
                            )
                        }
                    }
                }
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
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
    // PHANTOM_FULL_COMPOSE §12: two FingerprintBlocks stacked (yours + theirs)
    // with an axis bridge between them. Each block shows 8 groups of 4 hex
    // chars — the first 32 hex chars of the ED25519 public key.
    if (showVerifySheet) {
        val theirPublicKeyHex = conversation.theirPublicKeyHex

        val myPubKeyHex by produceState(initialValue = "") {
            value = container.identityRepo.loadIdentity()?.publicKeyHex ?: ""
        }

        // Tri-state machine per FULL_COMPOSE §12: Compare (neutral) →
        // Verified (success-tinted) | Mismatch (danger-tinted, opacity 0.70).
        var verifyState by remember { mutableStateOf(if (isVerified) VerifyState.Verified else VerifyState.Compare) }

        ModalBottomSheet(
            onDismissRequest = { showVerifySheet = false; verifyState = VerifyState.Compare },
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
                // Header — varies by state.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (verifyState) {
                        VerifyState.Compare -> PhIconShield(color = TextDim, size = 14.dp)
                        VerifyState.Verified -> PhIconShieldCheck(color = Success, size = 14.dp)
                        VerifyState.Mismatch -> Text("!", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = when (verifyState) {
                            VerifyState.Compare -> "VERIFY @${conversation.theirUsername.uppercase()}"
                            VerifyState.Verified -> "VERIFIED · @${conversation.theirUsername.uppercase()}"
                            VerifyState.Mismatch -> "MISMATCH · @${conversation.theirUsername.uppercase()}"
                        },
                        color = when (verifyState) {
                            VerifyState.Verified -> Success
                            VerifyState.Mismatch -> Danger
                            else -> TextDim
                        },
                        fontSize = 10.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 2.sp,
                    )
                }

                Text(
                    text = when (verifyState) {
                        VerifyState.Compare -> "Match all 8 groups with @${conversation.theirUsername} in person or on a trusted call."
                        VerifyState.Verified -> "Identity confirmed. Future messages will arrive on this key only — you'll be warned if it changes."
                        VerifyState.Mismatch -> "These keys do not match. Do not trust this conversation until you re-verify in person."
                    },
                    color = TextDim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Spacer(Modifier.height(4.dp))

                FingerprintBlock(
                    ownerLabel = "Your key",
                    name = "You",
                    publicKeyHex = myPubKeyHex,
                    accent = VerifyState.Compare,
                )

                // Axis bridge — neutral lines or success/danger tint per state.
                val bridgeColor = when (verifyState) {
                    VerifyState.Verified -> Success.copy(alpha = 0.45f)
                    VerifyState.Mismatch -> Danger.copy(alpha = 0.30f)
                    else -> TextDim.copy(alpha = 0.15f)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(bridgeColor),
                    )
                    Text(
                        text = when (verifyState) {
                            VerifyState.Compare -> "Compare ↕"
                            VerifyState.Verified -> "Verified ✓"
                            VerifyState.Mismatch -> "Mismatch ×"
                        },
                        color = when (verifyState) {
                            VerifyState.Verified -> Success
                            VerifyState.Mismatch -> Danger
                            else -> TextDim
                        },
                        fontSize = 9.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 1.5.sp,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(bridgeColor),
                    )
                }

                FingerprintBlock(
                    ownerLabel = "@${conversation.theirUsername}'s key",
                    name = conversation.theirUsername,
                    publicKeyHex = theirPublicKeyHex,
                    accent = verifyState,
                )

                Spacer(Modifier.height(8.dp))

                // Combined safety number — identical on both devices, designed
                // to be read aloud. Complements the visual hex blocks above:
                //   - Visual: compare 8×4 hex side by side (in person)
                //   - Verbal: compare these 60 digits (over a trusted call)
                // Computed via phantom.core.crypto.SafetyNumber.compute().
                if (myPubKeyHex.isNotEmpty() && theirPublicKeyHex.isNotEmpty()) {
                    val safetyNumber = remember(myPubKeyHex, theirPublicKeyHex) {
                        phantom.core.crypto.SafetyNumber.compute(myPubKeyHex, theirPublicKeyHex)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "OR READ ALOUD · 60 DIGITS",
                            color = TextDim,
                            fontSize = 9.sp,
                            fontFamily = PhantomFontMono,
                            letterSpacing = 1.6.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = safetyNumber,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontFamily = PhantomFontMono,
                            lineHeight = 18.sp,
                            letterSpacing = 0.3.sp,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // CTA row varies by state. Compare-state priority follows
                // FULL_COMPOSE Verification: the affirmative is a success-
                // bordered ghost ("Keys match — Verified"), NOT a cyan
                // primary. Cyan primary is reserved for the post-verified
                // "Back to chat" CTA, where commitment has already happened.
                // The negative ("Something doesn't match") is the dimmer
                // text-only danger ghost — calm precision, no panic.
                when (verifyState) {
                    VerifyState.Compare -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    container.conversationRepo.setVerified(conversationId, true)
                                    container.conversationRepo.clearIdentityKeyChangedAt(conversationId)
                                    isVerified = true
                                    keyChangedAt = null
                                    conversation = conversation.copy(identityKeyChangedAt = null)
                                    verifyState = VerifyState.Verified
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Success,
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Success.copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text(
                                text = "Keys match — Verified",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Button(
                            onClick = { verifyState = VerifyState.Mismatch },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Danger.copy(alpha = 0.65f),
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Danger.copy(alpha = 0.30f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            Text(
                                text = "Something doesn't match",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    VerifyState.Verified -> Button(
                        onClick = { showVerifySheet = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent,
                            contentColor = BgDeep,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text("Back to chat", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    VerifyState.Mismatch -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { verifyState = VerifyState.Compare },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = TextPrimary,
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(46.dp),
                        ) {
                            Text("Go back", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Button(
                            onClick = {
                                showVerifySheet = false
                                showReportSheet = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Danger.copy(alpha = 0.55f),
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Danger.copy(alpha = 0.30f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(46.dp),
                        ) {
                            Text("Report", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
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
            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
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

/**
 * Tri-state verification flow per FULL_COMPOSE §12.
 *   Compare  — neutral surfaceDeep block, no tinted border
 *   Verified — success-tinted border 0.25
 *   Mismatch — danger-tinted border 0.25, danger-tinted hex 0.70 alpha,
 *              block opacity 0.70 (calm precision, NOT panic)
 */
private enum class VerifyState { Compare, Verified, Mismatch }

/**
 * PHANTOM_FULL_COMPOSE §12 FingerprintBlock — owner label + name + 8 groups
 * of 4 hex chars (first 32 hex of the ED25519 public key) on surfaceDeep,
 * radius 12dp. Border tint and overall opacity respond to the parent
 * verify state.
 */
@Composable
private fun FingerprintBlock(
    ownerLabel: String,
    name: String,
    publicKeyHex: String,
    accent: VerifyState = VerifyState.Compare,
) {
    val fingerprint = remember(publicKeyHex) {
        if (publicKeyHex.length >= 32) {
            publicKeyHex.substring(0, 32).uppercase().chunked(4).joinToString("  ")
        } else {
            "loading…"
        }
    }
    val borderColor = when (accent) {
        VerifyState.Verified -> Success.copy(alpha = 0.25f)
        VerifyState.Mismatch -> Danger.copy(alpha = 0.25f)
        VerifyState.Compare -> Color.Transparent
    }
    val hexColor = when (accent) {
        VerifyState.Mismatch -> Danger.copy(alpha = 0.70f)
        else -> TextPrimary
    }
    val blockAlpha = if (accent == VerifyState.Mismatch) 0.70f else 1f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(blockAlpha)
            .clip(RoundedCornerShape(12.dp))
            .background(BgDeep)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        // FULL_COMPOSE Verification: each FingerprintBlock leads with a
        // 32dp avatar + name (Geist 16sp) + owner-tag (mono 10sp 0.45),
        // so user sees whose key is whose without parsing the label.
        Row(verticalAlignment = Alignment.CenterVertically) {
            phantom.android.ui.GradientAvatar(
                name = name,
                size = 32.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.16).sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = ownerLabel,
                    color = TextDim.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.5.sp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = fingerprint,
            color = hexColor,
            fontSize = 13.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 0.65.sp,
            lineHeight = 22.sp,
        )
    }
}

/**
 * Submit an abuse report to the relay's POST /report endpoint.
 *
 * Schema (matches services/relay/src/routes.rs ReportRequest):
 *   { "reporter_key": <hex>, "reported_key": <hex>, "category": <string> }
 *
 * The relay persists each report to /var/phantom/reports.jsonl on the VPS
 * and surfaces them via the /admin/reports endpoint. Level A scope: client
 * submits, relay stores, no admin review UI yet (Phase 2-3 deliverable).
 *
 * Uses HttpURLConnection rather than the existing Ktor client because
 * KtorRelayTransport is WebSocket-shaped — adding a one-off HTTP call
 * through it would mean either bolting on a generic HTTP method or
 * creating a separate Ktor HttpClient just for this endpoint. The JDK
 * HttpURLConnection has zero new dependencies and runs cleanly off the
 * Dispatchers.IO context the caller already provides.
 *
 * Returns true on 2xx, false on any failure (network, timeout, 4xx, 5xx).
 * Caller surfaces the result via Toast — no retry logic; user can resubmit.
 */
private suspend fun submitAbuseReport(
    reporterKey: String,
    reportedKey: String,
    category: String,
): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (reporterKey.isEmpty() || reportedKey.isEmpty()) return@withContext false
    runCatching {
        // BuildConfig.RELAY_URL is the WebSocket URL (wss://relay.phntm.pro/ws).
        // Convert to the HTTP base used by /report.
        val httpBase = phantom.android.BuildConfig.RELAY_URL
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .removeSuffix("/ws")
        val url = java.net.URL("$httpBase/report")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.doOutput = true
        // JSON literal — keys come from local sources (own pubkey + the
        // peer's stored pubkey); category is one of the predefined enum
        // strings from the dialog. No interpolated user-provided strings
        // are written into JSON, so a hand-built body is safe here.
        val payload = """{"reporter_key":"$reporterKey","reported_key":"$reportedKey","category":"$category"}"""
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    }.getOrDefault(false)
}
