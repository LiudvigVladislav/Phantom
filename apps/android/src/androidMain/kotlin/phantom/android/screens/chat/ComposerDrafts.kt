// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chat

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import phantom.core.storage.MessageEntity
import phantom.android.locale.hostActivity
import android.content.Context

/** In-memory only: do not put message plaintext into saved-instance-state Bundles. */
internal class ComposerDraft {
    val text = mutableStateOf("")
    val reply = mutableStateOf<MessageEntity?>(null)
    val editing = mutableStateOf<MessageEntity?>(null)
    val editingId = mutableStateOf<String?>(null)

    fun clear() {
        text.value = ""
        reply.value = null
        editing.value = null
        editingId.value = null
    }
}

internal class ComposerDrafts : ViewModel() {
    private var identity: String? = null
    private val drafts = mutableMapOf<String, ComposerDraft>()

    fun get(identityKey: String?, conversationKey: String): ComposerDraft {
        if (identity != identityKey) {
            clear()
            identity = identityKey
        }
        return drafts.getOrPut(conversationKey) { ComposerDraft() }
    }

    fun clear(conversationKey: String? = null) {
        if (conversationKey != null) drafts.remove(conversationKey)?.clear()
        else {
            drafts.values.forEach { it.clear() }
            drafts.clear()
            identity = null
        }
    }

    override fun onCleared() { clear() }
}

internal fun Context.clearComposerDraft(conversationKey: String? = null) {
    val owner = hostActivity() as? ViewModelStoreOwner ?: return
    ViewModelProvider(owner)[ComposerDrafts::class.java].clear(conversationKey)
}

@Composable
internal fun rememberComposerDraft(identityKey: String?, conversationKey: String): ComposerDraft {
    val activity = LocalContext.current.hostActivity()
    val holder = remember(activity) {
        (activity as? ViewModelStoreOwner)?.let { ViewModelProvider(it)[ComposerDrafts::class.java] }
            ?: ComposerDrafts()
    }
    return remember(holder, identityKey, conversationKey) { holder.get(identityKey, conversationKey) }
}
