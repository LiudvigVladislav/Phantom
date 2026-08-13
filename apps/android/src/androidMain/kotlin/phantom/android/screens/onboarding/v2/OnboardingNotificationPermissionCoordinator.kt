// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import phantom.android.notifications.NotificationsGate
import phantom.android.notifications.NotificationsPermissionState
import phantom.android.notifications.PhantomNotificationManager
import phantom.android.notifications.deriveNotificationsToggleState
import phantom.android.notifications.markNotificationPermissionRequested
import phantom.android.notifications.readCurrentNotificationsGate
import phantom.android.notifications.readHasEverRequestedNotificationPermission
import phantom.android.notifications.writeUserOptedInToNotificationsBlocking

/**
 * Onboarding-side Compose glue for the notification-permission
 * toggle. Round-4 REDLINE on Commit 5 §P1-3 pin: the pure gate
 * data + logic moved to `phantom.android.notifications` so the
 * runtime publisher no longer depends on this UI package. This
 * file now holds only:
 *   - Composable state holder (lifecycle-aware refresh on
 *     `ON_RESUME`, plus optional `Activity` handle used to feed
 *     the permanent-denial signal into the gate).
 *   - Two Settings intent launchers (app-level and channel-level).
 *   - Suspend fail-loud writer for the persisted opt-in bit —
 *     runs on `Dispatchers.IO` per round-4 §P1-2, returns success
 *     bool so callers can block re-tap and log failure.
 *
 * Legacy top-level `deriveNotificationsToggleState`,
 * `isEffectiveNotificationsEnabled`, `readCurrentNotificationsGate`,
 * `NotificationsGate`, `NotificationsPermissionState`,
 * `NotificationsTapAction`, `decideNotificationsTapAction` still
 * accessible via `phantom.android.notifications.*` (moved-package
 * imports). Old fully-qualified names in this package removed.
 *
 * Legacy `notificationsPermissionStateOf` +
 * `shouldLaunchNotificationPermissionRequest` retained as thin
 * shims for external test call sites (documented @deprecated is
 * skipped intentionally — no external consumers).
 */

// ── Backward-compat re-exports ────────────────────────────────────
// Round-4 REDLINE §P1-3 pin: gate + enum moved to
// `phantom.android.notifications`. Test suites + step composables
// referenced them via the pre-move `phantom.android.screens.onboarding.v2`
// package name; these typealiases keep those references working
// without editing every call site.

public typealias NotificationsPermissionState =
    phantom.android.notifications.NotificationsPermissionState
public typealias NotificationsGate = phantom.android.notifications.NotificationsGate
public typealias NotificationsTapAction = phantom.android.notifications.NotificationsTapAction

// ── Settings intents ──────────────────────────────────────────────

/**
 * Fire the app-notifications Settings intent. Round-2 REDLINE
 * §P1-2 pin preserved: every tap must have a visible on-device
 * effect. Used when the gate says the OS-side notification switch
 * is what's blocking (or when the permission is permanently
 * denied and the launcher would silent-succeed).
 */
public fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

/**
 * Fire the per-channel Settings intent for the `phantom_messages`
 * notification channel. Round-4 REDLINE §P1-3 pin: added because
 * the two-gate now includes `messageChannelEnabled` — when the
 * user has set the channel importance to NONE while leaving app-
 * level notifications on, the app-notification Settings page
 * doesn't focus the channel row. This intent targets the channel
 * page directly on Android 8+.
 *
 * On pre-Oreo this delegates to [openAppNotificationSettings]
 * (channels don't exist).
 */
public fun openMessageChannelSettings(
    context: Context,
    channelId: String = PhantomNotificationManager.CHANNEL_ID,
    sdkInt: Int = android.os.Build.VERSION.SDK_INT,
    oreoSdkInt: Int = android.os.Build.VERSION_CODES.O,
) {
    if (sdkInt < oreoSdkInt) {
        openAppNotificationSettings(context)
        return
    }
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

// ── Persisted opt-in — fail-loud suspend writer ───────────────────

/**
 * Suspend fail-loud writer. Round-4 REDLINE §P1-2 pin: switches
 * the SharedPreferences `.commit()` from the caller's thread to
 * `Dispatchers.IO`, and returns the boolean result so callers can
 * block re-tap and surface failure to the user.
 *
 * `NonCancellable` because a mid-write cancellation would leave
 * the caller uncertain whether the bit reached disk — for a
 * durable-boundary write this is the same pattern used by
 * `OnboardingPrivacyModePersistence` (matches architect's round-
 * 4 M4 review guidance on durable IO discipline).
 *
 * @return `true` if the write reached disk. Callers MUST NOT
 *   treat the value as persisted on `false` — read back via
 *   `readUserOptedInToNotifications` before advancing state.
 */
public suspend fun writeUserOptedInToNotifications(
    context: Context,
    value: Boolean,
): Boolean = withContext(Dispatchers.IO + NonCancellable) {
    writeUserOptedInToNotificationsBlocking(context, value)
}

// ── Composable state holder ───────────────────────────────────────

/**
 * Composable holder. Round-4 REDLINE pin: takes an optional
 * `Activity` so `shouldShowRequestPermissionRationale` can be
 * queried to infer permanent-denial (feeding
 * `permissionPermanentlyDenied` into the gate). If no Activity
 * is available (harness/test), the holder falls back to
 * `permissionPermanentlyDenied = false`.
 */
@Composable
public fun rememberNotificationsPermissionState(): NotificationsPermissionStateHolder {
    val context = LocalContext.current
    val activity = context.findActivityOrNull()
    val lifecycleOwner = LocalLifecycleOwner.current

    val gateState: MutableState<NotificationsGate> = remember {
        mutableStateOf(readGateWithDenialHint(context, activity))
    }
    val holder = remember {
        NotificationsPermissionStateHolder(
            gateState = gateState,
            refreshImpl = { },
        )
    }
    val refresh = remember(context, activity) {
        {
            gateState.value = readGateWithDenialHint(context, activity)
        }
    }
    holder.refreshImpl = refresh

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return holder
}

/**
 * Read the current gate, filling in `permissionPermanentlyDenied`
 * from an `Activity` handle when available. Permanent denial is
 * inferred as: "we've asked before" ∧ permission still not granted
 * ∧ `shouldShowRequestPermissionRationale == false`. This is
 * Android's documented signal that the system will no longer show
 * a dialog for this permission.
 */
private fun readGateWithDenialHint(
    context: Context,
    activity: Activity?,
): NotificationsGate {
    val bareGate = readCurrentNotificationsGate(context)
    if (bareGate.runtimePermissionGranted) return bareGate
    val requestedBefore = readHasEverRequestedNotificationPermission(context)
    if (!requestedBefore) return bareGate
    val rationale = if (activity != null) {
        ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    } else false
    val permanentlyDenied = !rationale
    return bareGate.copy(permissionPermanentlyDenied = permanentlyDenied)
}

/**
 * Walk up the ContextWrapper chain to find an Activity. Returns
 * null when composed inside a non-Activity context (Paparazzi /
 * unit-test harness) — the caller then treats permanent-denial
 * as false.
 */
// C6-a round-4 REDLINE §P1 pin: promoted from `private` to
// `internal` so the repair-screen exit action in
// `OnboardingFlowV2Internal` can reuse the same
// ContextWrapper-chain walker. Prior round-3 shape used
// `(context as? Activity)?.finishAffinity()` which silently
// no-op'd on wrapped contexts.
internal fun Context.findActivityOrNull(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * State holder returned by [rememberNotificationsPermissionState].
 * Exposes the observable [NotificationsGate] plus a derived
 * legacy `state` accessor for existing UI code + a `refresh()`
 * hook for callback-triggered refresh (e.g. from the permission
 * launcher callback).
 */
public class NotificationsPermissionStateHolder internal constructor(
    public val gateState: MutableState<NotificationsGate>,
    internal var refreshImpl: () -> Unit,
) {
    /**
     * Legacy accessor — derived `Enabled` / `Disabled` boolean.
     * Prefer reading `gateState.value` for full context.
     */
    public val state: MutableState<NotificationsPermissionState>
        get() = object : MutableState<NotificationsPermissionState> {
            override var value: NotificationsPermissionState
                get() = deriveNotificationsToggleState(gateState.value)
                set(_) { /* derived, not settable directly */ }
            override fun component1(): NotificationsPermissionState = value
            override fun component2(): (NotificationsPermissionState) -> Unit = { value = it }
        }

    /** Trigger a re-read of the OS + persisted state. */
    public fun refresh() {
        refreshImpl()
    }
}

// ── Legacy shims ──────────────────────────────────────────────────

/**
 * Legacy 2-state derivation from a boolean OS signal. Kept for
 * older callers that don't build a full gate. New code should
 * use [phantom.android.notifications.deriveNotificationsToggleState]
 * with a full gate.
 */
public fun notificationsPermissionStateOf(
    areNotificationsEnabledSystemwide: Boolean,
): NotificationsPermissionState =
    if (areNotificationsEnabledSystemwide) {
        NotificationsPermissionState.Enabled
    } else {
        NotificationsPermissionState.Disabled
    }

/**
 * Legacy launcher-decision helper. New code uses
 * [phantom.android.notifications.decideNotificationsTapAction].
 */
public fun shouldLaunchNotificationPermissionRequest(
    currentState: NotificationsPermissionState,
    sdkInt: Int,
    tiramisuSdkInt: Int,
): Boolean =
    currentState == NotificationsPermissionState.Disabled &&
        sdkInt >= tiramisuSdkInt

/**
 * Best-effort mark that the runtime notification permission has
 * been requested. Called from the launcher callback so future
 * taps can infer permanent-denial via
 * `shouldShowRequestPermissionRationale`.
 */
public fun markNotificationPermissionAsRequested(context: Context) {
    markNotificationPermissionRequested(context)
}
