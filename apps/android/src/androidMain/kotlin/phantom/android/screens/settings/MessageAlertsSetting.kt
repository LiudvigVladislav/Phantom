// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import phantom.android.notifications.NotificationsTapAction
import phantom.android.notifications.decideNotificationsTapAction
import phantom.android.notifications.isEffectiveNotificationsEnabled
import phantom.android.notifications.markNotificationPermissionRequested
import phantom.android.screens.onboarding.v2.openAppNotificationSettings
import phantom.android.screens.onboarding.v2.openMessageChannelSettings
import phantom.android.screens.onboarding.v2.rememberNotificationsPermissionState
import phantom.android.screens.onboarding.v2.writeUserOptedInToNotifications
import phantom.android.ui.PhIconBell
import phantom.android.ui.SettingsToggleRow
import phantom.android.ui.theme.CyanAccent

/** Uses the publisher's gate, not the obsolete message_alerts preference. */
@Composable
internal fun MessageAlertsSetting(
    onError: () -> Unit,
    writeOptIn: suspend (Context, Boolean) -> Boolean = ::writeUserOptedInToNotifications,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = rememberNotificationsPermissionState()
    val reportError by rememberUpdatedState(onError)
    var busy by remember { mutableStateOf(false) }

    fun persist(value: Boolean) {
        busy = true
        scope.launch {
            try {
                if (!writeOptIn(context, value)) reportError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportError()
            } finally {
                holder.refresh()
                busy = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        markNotificationPermissionRequested(context)
        if (granted) {
            persist(true)
        } else {
            holder.refresh()
            busy = false
        }
    }

    SettingsToggleRow(
        icon = { PhIconBell(color = CyanAccent, size = 16.dp) },
        label = "Message Alerts",
        checked = isEffectiveNotificationsEnabled(holder.gateState.value),
        onCheckedChange = {
            if (!busy) {
                holder.refresh()
                try {
                    when (decideNotificationsTapAction(holder.gateState.value, Build.VERSION.SDK_INT)) {
                        NotificationsTapAction.OptInAppLevelOnly -> persist(true)
                        NotificationsTapAction.OptOutAppLevelOnly -> persist(false)
                        NotificationsTapAction.LaunchRuntimePermission -> {
                            busy = true
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        NotificationsTapAction.OpenAppNotificationSettings -> openAppNotificationSettings(context)
                        NotificationsTapAction.OpenMessageChannelSettings -> openMessageChannelSettings(context)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    busy = false
                    holder.refresh()
                    reportError()
                }
            }
        },
    )
}
