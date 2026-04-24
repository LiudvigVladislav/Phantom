package phantom.android.screens.requests

import androidx.compose.foundation.background
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
import phantom.core.storage.ConversationEntity

@Composable
fun MessageRequestsScreen(
    container: AppContainer,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }

    suspend fun reload() {
        requests = container.conversationRepo.getMessageRequests()
    }

    LaunchedEffect(Unit) { reload() }

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
                    text = "REQUESTS",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.size(36.dp))
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
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
                                container.conversationRepo.acceptRequest(req.id)
                                reload()
                                onNavigate(Screen.Chat(req.id, req.theirUsername))
                            }
                        },
                        onBlock = {
                            scope.launch {
                                container.conversationRepo.blockConversation(req.id)
                                reload()
                            }
                        },
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(name = entity.theirUsername, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entity.theirUsername, color = TextPrimary, fontSize = 15.sp)
                entity.lastMessagePreview?.let { preview ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        preview,
                        color = TextDim,
                        fontSize = 12.sp,
                        maxLines = 2,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Danger.copy(alpha = 0.12f))
                    .clickable(onClick = onBlock),
                contentAlignment = Alignment.Center,
            ) {
                Text("Block", color = Danger, fontSize = 13.sp)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(CyanAccent)
                    .clickable(onClick = onAccept),
                contentAlignment = Alignment.Center,
            ) {
                Text("Accept", color = BgDeep, fontSize = 13.sp)
            }
        }
    }
}
