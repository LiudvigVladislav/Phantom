// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chatlist

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.theme.*
import phantom.core.storage.ConversationEntity
import phantom.core.storage.TrustTier

@Composable
fun AddContactDialog(
    prefill: String = "",
    container: AppContainer,
    onDismiss: () -> Unit,
    onAdded: (convId: String, username: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pasteValue by remember { mutableStateOf(prefill) }
    var localAlias by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val parsed = remember(pasteValue) { parseContactString(pasteValue.trim()) }
    val resolvedKey = parsed?.second ?: ""
    val resolvedName = parsed?.first ?: ""
    val isValid = resolvedKey.length >= 64

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PhantomTokens.Colors.SurfaceElevated,
        title = {
            Text(
                "Add contact",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                letterSpacing = (-0.17).sp,
            )
        },
        text = {
            Column {
                Text(
                    "Paste their key (from Profile → Share my key)",
                    color = TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasteValue,
                    onValueChange = { pasteValue = it },
                    placeholder = { Text("username:key or key…", color = TextDim) },
                    singleLine = true,
                    isError = pasteValue.isNotEmpty() && !isValid,
                    supportingText = {
                        when {
                            pasteValue.isEmpty() -> {}
                            isValid && resolvedName.isNotEmpty() ->
                                Text("✓  @$resolvedName", color = Success, fontSize = 11.sp)
                            isValid ->
                                Text("✓  Key recognised", color = Success, fontSize = 11.sp)
                            else ->
                                Text("Key looks too short", color = Danger, fontSize = 11.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = if (isValid) Success else CyanAccent,
                        unfocusedBorderColor = TextDim.copy(alpha = 0.4f),
                        errorBorderColor = Danger,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Local nickname (optional)",
                    color = TextDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = localAlias,
                    onValueChange = { localAlias = it },
                    placeholder = {
                        Text(
                            if (resolvedName.isNotEmpty()) "@$resolvedName" else "Leave blank to use their name",
                            color = TextDim.copy(alpha = 0.5f),
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = TextDim.copy(alpha = 0.4f),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!isValid || loading) return@TextButton
                    loading = true
                    scope.launch {
                        val displayName = localAlias.ifBlank { resolvedName.ifBlank { resolvedKey.take(8) } }
                        val convId = buildConversationId(container, resolvedKey)
                        container.conversationRepo.upsertConversation(
                            ConversationEntity(
                                id = convId,
                                theirUsername = displayName,
                                theirPublicKeyHex = resolvedKey,
                                lastMessagePreview = null,
                                lastMessageAt = null,
                                unreadCount = 0,
                                trustTier = TrustTier.TRUSTED,
                                blocked = false,
                            )
                        )
                        onAdded(convId, displayName)
                    }
                },
                enabled = isValid && !loading,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CyanAccent)
                } else {
                    Text("Add", color = if (isValid) CyanAccent else TextDim)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDim)
            }
        },
    )
}

private fun parseContactString(input: String): Pair<String, String>? {
    if (input.isEmpty()) return null
    val colonIndex = input.indexOf(':')
    return if (colonIndex > 0 && colonIndex < input.length - 1) {
        Pair(input.substring(0, colonIndex), input.substring(colonIndex + 1))
    } else {
        Pair("", input)
    }
}

private suspend fun buildConversationId(container: AppContainer, theirKey: String): String {
    val myKey = container.identityRepo.loadIdentity()?.publicKeyHex ?: ""
    return listOf(myKey, theirKey).sorted().joinToString("_")
}
