// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import phantom.android.R

@Composable
internal fun StorageDialog(cache: PlaybackCache, onDismiss: () -> Unit, onSizeChanged: (Long?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        size = withContext(Dispatchers.IO) { cache.sizeBytes() }
        onSizeChanged(size)
    }
    LaunchedEffect(cache) {
        try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            result = context.getString(R.string.settings_cache_error)
            onSizeChanged(null)
        } finally {
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.settings_local_storage)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_cache_size,
                    size?.let { Formatter.formatShortFileSize(context, it) } ?: "…"))
                Text(stringResource(R.string.settings_cache_description))
                result?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (size ?: 0L) > 0L,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val cleared = withContext(Dispatchers.IO) { cache.clear() }
                            refresh()
                            result = if (cleared.failedFiles == 0) {
                                context.getString(R.string.settings_cache_cleared,
                                    Formatter.formatShortFileSize(context, cleared.deletedBytes))
                            } else context.getString(R.string.settings_cache_partial)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            result = context.getString(R.string.settings_cache_error)
                            size = null
                            onSizeChanged(null)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(if (busy) R.string.settings_cache_working else R.string.settings_clear_cache)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.settings_cache_close))
            }
        },
    )
}
