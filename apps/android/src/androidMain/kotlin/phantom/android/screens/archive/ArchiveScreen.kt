// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.archive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.*
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono
import phantom.core.storage.ConversationEntity

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArchiveScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onNavigateToChat: (Screen.Chat) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var archived by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    var showMenuFor by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        archived = runCatching { container.conversationRepo.getArchivedConversations() }.getOrElse { emptyList() }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        // Top bar
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
                        .clip(RoundedCornerShape(18.dp))
                        .background(Surface2)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    PhIconBack(color = TextPrimary, size = 18.dp)
                }
                Text(
                    text = "ARCHIVED",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.size(36.dp))
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        }

        if (archived.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(modifier = Modifier.size(56.dp)) {
                        val sw = 2.dp.toPx()
                        val st = Stroke(sw, cap = StrokeCap.Round)
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.125f, size.height * 0.292f)
                            lineTo(size.width * 0.125f, size.height * 0.875f)
                            lineTo(size.width * 0.875f, size.height * 0.875f)
                            lineTo(size.width * 0.875f, size.height * 0.375f)
                            lineTo(size.width * 0.5f,   size.height * 0.375f)
                            lineTo(size.width * 0.375f, size.height * 0.25f)
                            lineTo(size.width * 0.125f, size.height * 0.25f)
                            close()
                        }
                        drawPath(path, TextDim.copy(alpha = 0.4f), style = st)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("No archived chats", color = TextDim, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Long-press a chat to archive it",
                        color = TextDim.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontFamily = PhantomFontMono,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(archived, key = { it.id }) { conv ->
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        onNavigateToChat(Screen.Chat(conv.id, conv.theirUsername))
                                    },
                                    onLongClick = { showMenuFor = conv.id },
                                )
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            GradientAvatar(name = conv.theirUsername, size = 46.dp)

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = conv.theirUsername,
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val timeStr = conv.lastMessageAt?.let { formatArchiveTime(it) } ?: ""
                                    Text(text = timeStr, color = TextDim, fontSize = 11.sp)
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = conv.lastMessagePreview ?: "",
                                    color = TextDim,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showMenuFor == conv.id,
                            onDismissRequest = { showMenuFor = null },
                            containerColor = Surface2,
                            offset = DpOffset(20.dp, 0.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Unarchive", color = TextPrimary, fontSize = 14.sp) },
                                onClick = {
                                    showMenuFor = null
                                    scope.launch {
                                        container.conversationRepo.unarchiveConversation(conv.id)
                                        reload()
                                    }
                                },
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = Color.White.copy(alpha = 0.04f),
                    )
                }
            }
        }
    }
}

private fun formatArchiveTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val dayMs = 86_400_000L
    return when {
        diff < dayMs     -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(millis))
        diff < 7 * dayMs -> java.text.SimpleDateFormat("EEE", java.util.Locale.US).format(java.util.Date(millis))
        else             -> java.text.SimpleDateFormat("dd MMM", java.util.Locale.US).format(java.util.Date(millis))
    }
}
