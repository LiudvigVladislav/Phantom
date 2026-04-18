package phantom.android.screens.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import phantom.android.navigation.Screen
import phantom.android.ui.theme.*
import phantom.core.storage.ConversationEntity

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Message Requests",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Normal,
                        )
                        Text(
                            "From people not in your contacts",
                            color = TextDim,
                            fontSize = 11.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextDim,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        }
    ) { padding ->
        if (requests.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No pending requests", color = TextDim, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
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
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(CyanAccent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entity.theirUsername.take(1).uppercase(),
                    color = CyanAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
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
            OutlinedButton(
                onClick = onBlock,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                border = androidx.compose.foundation.BorderStroke(1.dp, Danger.copy(alpha = 0.4f)),
            ) {
                Text("Block", fontSize = 13.sp)
            }
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanAccent,
                    contentColor = BgDeep,
                ),
            ) {
                Text("Accept", fontSize = 13.sp)
            }
        }
    }
}
