// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono
import phantom.core.storage.ConversationEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    container: AppContainer,
    onCreated: (groupId: String, groupName: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isCreating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        contacts = container.conversationRepo.getActiveConversations()
    }

    val canCreate = groupName.isNotBlank() && selectedIds.isNotEmpty() && !isCreating

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
                        text = "New Group",
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
                            val members = contacts
                                .filter { it.id in selectedIds }
                                .map { it.theirPublicKeyHex to it.theirUsername }
                            val groupId = container.groupMessagingService?.createGroup(groupName.trim(), members)
                                ?: return@launch
                            onCreated(groupId, groupName.trim())
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
                            text = "Create Group",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // Group name field
            item {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Group Name")
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface)
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    BasicTextField(
                        value = groupName,
                        onValueChange = { if (it.length <= 64) groupName = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 15.sp,
                        ),
                        cursorBrush = SolidColor(CyanAccent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (groupName.isEmpty()) {
                                Text("Enter group name…", color = TextDim, fontSize = 15.sp)
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            // Members section header
            item {
                SectionLabel("Add Members (${selectedIds.size} selected)")
                Spacer(Modifier.height(4.dp))
            }

            // Contact list
            if (contacts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No contacts yet.\nAdd contacts from the chat list first.",
                            color = TextDim,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 20.sp,
                        )
                    }
                }
            } else {
                items(contacts, key = { it.id }) { contact ->
                    val isSelected = contact.id in selectedIds
                    ContactSelectRow(
                        contact = contact,
                        isSelected = isSelected,
                        onClick = {
                            selectedIds = if (isSelected) {
                                selectedIds - contact.id
                            } else {
                                selectedIds + contact.id
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactSelectRow(
    contact: ConversationEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() },
            colors = CheckboxDefaults.colors(
                checkedColor = CyanAccent,
                uncheckedColor = TextDim,
                checkmarkColor = BgDeep,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.theirUsername,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = contact.theirPublicKeyHex.take(8) + "…",
                color = TextDim,
                fontSize = 11.sp,
                fontFamily = PhantomFontMono,
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(CyanAccent),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = Color.White.copy(alpha = 0.04f),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextDim,
        fontSize = 10.sp,
        fontFamily = PhantomFontMono,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}
