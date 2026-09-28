// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.contact

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.R
import phantom.android.ui.theme.Danger
import phantom.android.ui.theme.Surface
import phantom.android.ui.theme.TextDim
import phantom.android.ui.theme.TextPrimary
import phantom.core.messaging.MessagingService

private enum class DeleteStep { CHOICE, CONFIRM_CONTACT, ERROR }

@Composable
fun LocalConversationDeletionDialog(
    conversationId: String,
    theirUsername: String,
    messagingService: MessagingService?,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var step by remember { mutableStateOf(DeleteStep.CHOICE) }
    var deleting by remember { mutableStateOf(false) }

    fun performDeletion(removeContact: Boolean) {
        if (deleting) return
        deleting = true
        scope.launch {
            try {
                val result = deleteLocalConversationAndNavigate(
                    delete = {
                        if (messagingService == null) Result.failure(IllegalStateException("Messaging unavailable"))
                        else if (removeContact) messagingService.deleteConversationLocally(conversationId)
                        else messagingService.clearConversationHistoryLocally(conversationId)
                    },
                    onDeleted = { outcome ->
                        if (outcome.mediaCleanupPending) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.contact_profile_delete_media_cleanup_pending),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        onDismiss()
                        onDeleted()
                    },
                )
                if (result.isFailure) step = DeleteStep.ERROR
            } finally {
                deleting = false
            }
        }
    }

    when (step) {
        DeleteStep.CHOICE -> AlertDialog(
            onDismissRequest = { if (!deleting) onDismiss() },
            containerColor = Surface,
            title = { Text(stringResource(R.string.contact_profile_delete_conversation_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.contact_profile_delete_explanation), color = TextDim, fontSize = 14.sp) },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { performDeletion(removeContact = false) }, modifier = Modifier.fillMaxWidth(), enabled = !deleting) {
                        Text(stringResource(R.string.contact_profile_delete_history_only), color = Danger)
                    }
                    TextButton(onClick = { step = DeleteStep.CONFIRM_CONTACT }, modifier = Modifier.fillMaxWidth(), enabled = !deleting) {
                        Text(stringResource(R.string.contact_profile_delete_contact_and_history), color = Danger)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !deleting) {
                        Text(stringResource(R.string.contact_profile_cancel), color = TextDim)
                    }
                }
            },
        )
        DeleteStep.CONFIRM_CONTACT -> AlertDialog(
            onDismissRequest = { if (!deleting) onDismiss() },
            containerColor = Surface,
            title = { Text(stringResource(R.string.contact_profile_delete_contact_confirm_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.contact_profile_delete_contact_confirm_explanation, theirUsername),
                    color = TextDim, fontSize = 14.sp,
                )
            },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { performDeletion(removeContact = true) }, modifier = Modifier.fillMaxWidth(), enabled = !deleting) {
                        Text(stringResource(R.string.contact_profile_delete_contact_and_history), color = Danger)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !deleting) {
                        Text(stringResource(R.string.contact_profile_cancel), color = TextDim)
                    }
                }
            },
        )
        DeleteStep.ERROR -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Surface,
            title = { Text(stringResource(R.string.contact_profile_delete_failed_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.contact_profile_delete_failed_explanation), color = TextDim) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.contact_profile_cancel), color = TextPrimary)
                }
            },
        )
    }
}
