package phantom.android.screens.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.di.AppContainer
import phantom.android.navigation.Screen
import phantom.android.ui.*
import phantom.android.ui.theme.*

private data class CallEntry(
    val name: String,
    val direction: String, // "in" | "out" | "missed"
    val type: String,       // "voice" | "video"
    val time: String,
    val duration: String?,
)

private val sampleCalls = listOf(
    CallEntry("Alex",     "out",    "voice", "17:42", "12:04"),
    CallEntry("Mikhail",  "in",     "video", "15:30", "03:18"),
    CallEntry("Dr.Raven", "missed", "voice", "14:12", null),
    CallEntry("Karel",    "out",    "voice", "Tue",   "00:42"),
    CallEntry("Ghost",    "in",     "video", "Tue",   "08:55"),
    CallEntry("Sasha",    "out",    "voice", "Sun",   "01:07"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    container: AppContainer,
    onNavigate: (Screen) -> Unit,
    onProfile: () -> Unit = {},
) {
    var userName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        userName = container.identityRepo.loadIdentity()?.username ?: ""
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            PhantomTopBar(
                userName = userName,
                onProfile = onProfile,
                onAddContact = { onNavigate(Screen.ChatList) },
                onScanQr = { onNavigate(Screen.QrScan) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Filter chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(label = "All", active = true) }
                    item { FilterChip(label = "Incoming") }
                    item { FilterChip(label = "Outgoing") }
                    item { FilterChip(label = "Missed") }
                }

                // Call list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 180.dp),
                ) {
                    items(sampleCalls) { call ->
                        CallRow(call)
                    }
                }
            }

            // COMING SOON overlay — covers filter chips + list
            ComingSoonOverlay(kicker = "CALLS · α 0.1")

            // Start a Call button — visible through overlay? No — above overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 100.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CyanAccent),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = BgDeep, modifier = Modifier.size(16.dp))
                        Text(
                            text = "START A CALL",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.8.sp,
                            color = BgDeep,
                        )
                    }
                }
            }

            // Bottom nav pill
            BottomNavPill(
                activeTab = NavTab.CALLS,
                onTabSelected = { tab ->
                    when (tab) {
                        NavTab.CHATS -> onNavigate(Screen.ChatList)
                        NavTab.SETTINGS -> onNavigate(Screen.Settings)
                        NavTab.CALLS -> {}
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) CyanAccent else Color.Transparent)
            .border(
                width = if (active) 0.dp else 1.dp,
                color = Color.White.copy(alpha = if (active) 0f else 0.08f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) BgDeep else TextDim,
            letterSpacing = 0.2.sp,
        )
    }
}

@Composable
private fun CallRow(call: CallEntry) {
    val nameColor = if (call.direction == "missed") Danger else TextPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GradientAvatar(name = call.name, size = 44.dp)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.name,
                color = nameColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val arrowColor = when (call.direction) {
                    "in"     -> Success
                    "out"    -> CyanAccent
                    else     -> Danger
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(arrowColor),
                )
                Text(
                    text = when {
                        call.direction == "missed" -> "Missed"
                        call.type == "video" -> "Video · ${call.duration}"
                        else -> call.duration ?: ""
                    },
                    color = TextDim,
                    fontSize = 12.sp,
                )
            }
        }

        Text(text = call.time, color = TextDim, fontSize = 11.sp)

        Icon(
            Icons.Default.Phone,
            contentDescription = "Call back",
            tint = CyanAccent,
            modifier = Modifier.size(18.dp),
        )
    }
}
