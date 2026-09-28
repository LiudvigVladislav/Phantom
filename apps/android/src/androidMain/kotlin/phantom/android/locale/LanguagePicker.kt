// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.locale

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import phantom.android.R
import phantom.android.notifications.PhantomNotificationManager
import phantom.android.ui.theme.*

internal fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.hostActivity() else null
    else -> null
}

@Composable
internal fun languageLabel(language: AppLanguage): String {
    val effective = if (language == AppLanguage.SYSTEM)
        AppLanguageStore.effectiveLanguage(LocalContext.current) else language
    return stringResource(if (effective == AppLanguage.RUSSIAN)
        R.string.language_russian else R.string.settings_english)
}

@Composable
internal fun LanguagePicker(onDismiss: () -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(AppLanguageStore.effectiveLanguage(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        title = { Text(stringResource(R.string.settings_language), Modifier.fillMaxWidth()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                listOf(AppLanguage.ENGLISH, AppLanguage.RUSSIAN).forEach { language ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(choice == language, role = Role.RadioButton) { choice = language },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(choice == language, onClick = null)
                        Text(languageLabel(language), Modifier.weight(1f).padding(start = 12.dp))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.language_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                if (choice == AppLanguageStore.selected(context)) {
                    onDismiss()
                } else if (runCatching { AppLanguageStore.set(context, choice) }.getOrDefault(false)) {
                    PhantomNotificationManager.createChannel(context)
                    onDismiss()
                    if (Build.VERSION.SDK_INT < 33) context.hostActivity()?.recreate()
                } else onError()
            }) {
                Text(stringResource(R.string.language_apply), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        },
    )
}
