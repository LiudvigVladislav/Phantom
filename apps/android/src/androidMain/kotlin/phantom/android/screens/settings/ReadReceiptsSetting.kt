// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.android.screens.settings

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import phantom.android.R
import phantom.android.privacy.ReadReceiptPreference
import phantom.android.ui.PhIconDoubleCheck
import phantom.android.ui.SettingsRowItem
import phantom.android.ui.SettingsToggleRow
import phantom.android.ui.theme.CyanAccent

@Composable
internal fun ReadReceiptsSetting(
    privacyAllows: Boolean,
    onError: () -> Unit,
    write: suspend (Context, Boolean) -> Boolean = { context, value ->
        withContext(Dispatchers.IO) { ReadReceiptPreference.write(context, value) }
    },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(ReadReceiptPreference.enabled(context)) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val reportError by rememberUpdatedState(onError)
    SettingsRowItem(
        icon = { PhIconDoubleCheck(color = CyanAccent, size = 16.dp) },
        label = stringResource(R.string.settings_read_receipts),
        value = stringResource(if (selected && privacyAllows) R.string.settings_on else R.string.settings_off),
        onClick = { selected = ReadReceiptPreference.enabled(context); show = true },
    )
    if (show) AlertDialog(
        onDismissRequest = { if (!busy) show = false },
        title = { Text(stringResource(R.string.settings_read_receipts)) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(if (privacyAllows) R.string.read_receipts_description else R.string.read_receipts_privacy_restriction))
                SettingsToggleRow(
                    icon = { PhIconDoubleCheck(color = CyanAccent, size = 16.dp) },
                    label = stringResource(R.string.read_receipts_send),
                    checked = selected,
                    onCheckedChange = { value ->
                        if (!busy) {
                            busy = true
                            scope.launch {
                                try {
                                    if (!write(context, value)) reportError()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    reportError()
                                } finally {
                                    selected = ReadReceiptPreference.enabled(context)
                                    busy = false
                                }
                            }
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { show = false }) { Text(stringResource(R.string.settings_ok)) }
        },
    )
}
