// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import phantom.android.R

internal val DISAPPEARING_TIMER_OPTIONS = listOf(0L, 30L, 300L, 3600L, 86400L, 604800L)

/** A successful submission is not a peer acknowledgement. Local policy is stored first. */
internal suspend fun applyDisappearingTimer(
    seconds: Long,
    setLocal: suspend (Long) -> Unit,
    send: suspend () -> Result<Unit>,
): Boolean {
    require(seconds in DISAPPEARING_TIMER_OPTIONS)
    setLocal(seconds)
    return try { send().getOrThrow(); true }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { false }
}

@Composable
internal fun DisappearingTimerDialog(
    load: suspend () -> Long,
    apply: suspend (Long) -> Boolean,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val labels = listOf(R.string.contact_profile_timer_off, R.string.contact_profile_timer_30_seconds,
        R.string.contact_profile_timer_5_minutes, R.string.contact_profile_timer_1_hour,
        R.string.contact_profile_timer_1_day, R.string.contact_profile_timer_1_week)
    LaunchedEffect(Unit) {
        try { selected = load() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = R.string.chat_timer_error }
        finally { busy = false }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.contact_profile_disappearing_messages)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                DISAPPEARING_TIMER_OPTIONS.forEachIndexed { index, seconds ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = selected == seconds,
                            enabled = !busy && selected != null,
                            role = Role.RadioButton,
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val submitted = apply(seconds)
                                        selected = seconds
                                        if (submitted) onDismiss() else error = R.string.chat_timer_local_only
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { error = R.string.chat_timer_error }
                                    finally { busy = false }
                                }
                            },
                        ).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected == seconds, onClick = null, enabled = !busy && selected != null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(labels[index]))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}
