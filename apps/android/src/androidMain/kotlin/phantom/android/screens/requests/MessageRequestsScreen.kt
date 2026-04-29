// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.*
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono
import phantom.core.storage.ConversationEntity

@Composable
fun MessageRequestsScreen(
    container: AppContainer,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    // Block requires confirmation — single-tap on Block button without
    // a dialog was leading to accidental permanent blocks during QA.
    // Pattern matches ContactProfileScreen's existing Block confirmation.
    var blockTarget by remember { mutableStateOf<ConversationEntity?>(null) }

    suspend fun reload() {
        requests = container.conversationRepo.getMessageRequests()
    }

    LaunchedEffect(Unit) { reload() }

    // Block confirmation dialog — opens when user taps Block on a request.
    blockTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { blockTarget = null },
            containerColor = Surface,
            title = { Text("Block @${target.theirUsername}?", color = TextPrimary) },
            text = {
                Text(
                    "They will no longer be able to message you. " +
                        "You can unblock them later from the contact profile.",
                    color = TextDim,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toBlock = target
                    blockTarget = null
                    scope.launch {
                        container.conversationRepo.blockConversation(toBlock.id)
                        reload()
                    }
                }) { Text("Block", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { blockTarget = null }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        // Custom top bar — matches ArchiveScreen style
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
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    PhIconBack(color = TextPrimary, size = 20.dp)
                }
                Text(
                    text = "REQUESTS",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = TextDim,
                    fontSize = 11.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 0.88.sp,
                )
                Spacer(Modifier.size(32.dp))
            }
            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
        }

        if (requests.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("No pending requests", color = TextDim, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(requests, key = { it.id }) { req ->
                    RequestCard(
                        entity = req,
                        onAccept = {
                            scope.launch {
                                // SQL: UPDATE conversation SET trust_tier = 'TRUSTED' WHERE id = ?
                                // After this, getActiveConversations() includes this row;
                                // getMessageRequests() excludes it. ChatList badge updates
                                // on next fresh recomposition (when user navigates back).
                                container.conversationRepo.acceptRequest(req.id)
                                reload()
                                onNavigate(Screen.Chat(req.id, req.theirUsername))
                            }
                        },
                        onBlock = { blockTarget = req },
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestCard(
    entity: ConversationEntity,
    onAccept: () -> Unit,
    onBlock: () -> Unit,
) {
    // Card surface — SurfaceElevated with BorderSubtle outline, radius 12dp.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PhantomTokens.Colors.SurfaceElevated)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(name = entity.theirUsername, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.theirUsername,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
                entity.lastMessagePreview?.let { preview ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        preview,
                        color = TextDim,
                        fontSize = 13.sp,
                        maxLines = 2,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Action row — pill buttons. Block: ghost danger. Accept: filled cyan.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(9999.dp))
                    .background(Color.Transparent)
                    .border(1.dp, Danger.copy(alpha = 0.4f), RoundedCornerShape(9999.dp))
                    .clickable(onClick = onBlock),
                contentAlignment = Alignment.Center,
            ) {
                Text("Block", color = Danger, fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(9999.dp))
                    .background(CyanAccent)
                    .clickable(onClick = onAccept),
                contentAlignment = Alignment.Center,
            ) {
                Text("Accept", color = BgDeep, fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            }
        }
    }
}
