// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import phantom.android.ui.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChannelScreen(
    container: AppContainer,
    onCreated: (groupId: String, groupName: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var channelName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    val canCreate = channelName.isNotBlank() && !isCreating

    Scaffold(
        containerColor = BgDeep,
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
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Surface2)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        PhIconBack(color = TextPrimary, size = 18.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "New Channel",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = {
                        if (!canCreate) return@Button
                        isCreating = true
                        scope.launch {
                            val groupId = container.groupMessagingService?.createGroup(
                                name = channelName.trim(),
                                members = emptyList(),
                                isChannel = true,
                            ) ?: return@launch
                            onCreated(groupId, channelName.trim())
                        }
                    },
                    enabled = canCreate,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanAccent,
                        disabledContainerColor = Surface2,
                        contentColor = BgDeep,
                        disabledContentColor = TextDim,
                    ),
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = BgDeep,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = "Create Channel",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(32.dp))

            // Channel icon preview
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Surface2)
                        .border(1.dp, CyanAccent.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (channelName.isNotBlank()) {
                        Text(
                            text = channelName.trim().take(2).uppercase(),
                            color = CyanAccent,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        // Broadcast icon
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(36.dp)) {
                            val c = TextDim
                            val sw = 2.dp.toPx()
                            val st = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            drawCircle(color = c, radius = size.width * 0.12f, center = androidx.compose.ui.geometry.Offset(cx, cy))
                            for (r in listOf(0.32f, 0.46f)) {
                                drawArc(
                                    color = c,
                                    startAngle = 225f,
                                    sweepAngle = 90f,
                                    useCenter = false,
                                    topLeft = androidx.compose.ui.geometry.Offset(cx - size.width * r, cy - size.height * r),
                                    size = androidx.compose.ui.geometry.Size(size.width * 2 * r, size.height * 2 * r),
                                    style = st,
                                )
                                drawArc(
                                    color = c,
                                    startAngle = 45f,
                                    sweepAngle = 90f,
                                    useCenter = false,
                                    topLeft = androidx.compose.ui.geometry.Offset(cx - size.width * r, cy - size.height * r),
                                    size = androidx.compose.ui.geometry.Size(size.width * 2 * r, size.height * 2 * r),
                                    style = st,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // Description text
            Text(
                text = "CHANNEL NAME",
                color = TextDim,
                fontSize = 10.sp,
                fontFamily = PhantomFontMono,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                BasicTextField(
                    value = channelName,
                    onValueChange = { if (it.length <= 64) channelName = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 15.sp,
                    ),
                    cursorBrush = SolidColor(CyanAccent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (channelName.isEmpty()) {
                            Text("Enter channel name…", color = TextDim, fontSize = 15.sp)
                        }
                        inner()
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            // Info card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CyanAccent.copy(alpha = 0.05f))
                    .border(1.dp, CyanAccent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "How channels work",
                        color = CyanAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Channels broadcast messages to subscribers. Only admins can post. Subscribers can read and react.",
                        color = TextDim,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}
