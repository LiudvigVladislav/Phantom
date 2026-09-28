// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.android.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import phantom.android.R
import phantom.android.notifications.CallNotifications
import phantom.android.notifications.markNotificationPermissionRequested
import phantom.android.notifications.readHasEverRequestedNotificationPermission
import phantom.android.screens.onboarding.v2.openAppNotificationSettings
import phantom.android.ui.PhIconPhone
import phantom.android.ui.SettingsRowItem
import phantom.android.ui.theme.CyanAccent

@Composable
internal fun CallAlertsSetting(onError: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(CallNotifications.enabled(context)) }
    val reportError by rememberUpdatedState(onError)
    fun open() {
        try { CallNotifications.openSettings(context) } catch (_: Exception) { reportError() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        markNotificationPermissionRequested(context)
        if (granted) open()
        enabled = CallNotifications.enabled(context)
    }
    DisposableEffect(context, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = CallNotifications.enabled(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    SettingsRowItem(
        icon = { PhIconPhone(color = CyanAccent, size = 16.dp) },
        label = stringResource(R.string.settings_call_alerts),
        value = stringResource(if (enabled) R.string.settings_on else R.string.settings_off),
        onClick = {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                try {
                    if (readHasEverRequestedNotificationPermission(context)) openAppNotificationSettings(context)
                    else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Exception) { reportError() }
            } else open()
        },
    )
}
