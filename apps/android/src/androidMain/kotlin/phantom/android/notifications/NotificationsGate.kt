// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notifications gate — canonical model for whether a message
 * notification may fire.
 *
 * Round-4 REDLINE on Commit 5 §P1-3 pin: the gate lives in the
 * `phantom.android.notifications` package (production publisher's
 * home) rather than under `screens.onboarding.v2` — the runtime
 * publisher (`PhantomNotificationManager.showMessageNotification`)
 * MUST not depend on any UI package.
 *
 * Round-4 §P1-3 also adds `messageChannelEnabled` — the per-
 * channel `phantom_messages` importance signal. On Android 8+, an
 * app can have notifications enabled overall while a specific
 * channel is set to `IMPORTANCE_NONE`, in which case `notify()`
 * fires but Android silently drops the toast. Excluding this from
 * the gate makes the toggle lie: UI shows ON, `notify()` returns
 * success, nothing appears. `messageChannelEnabled` closes that
 * gap.
 *
 * Model:
 *   effectiveNotifications = userOptedIn
 *                          ∧ runtimePermissionGranted
 *                          ∧ osNotificationsEnabled
 *                          ∧ messageChannelEnabled
 *
 * Signals are INDEPENDENT (never redundant):
 *   - `userOptedIn`               — persisted app-level bit;
 *     default `false`. Flipped only by explicit user action on the
 *     onboarding Notifications toggle or a later Settings surface.
 *   - `runtimePermissionGranted`  — `POST_NOTIFICATIONS` grant
 *     state (Android 13+). Below 13, always `true`.
 *   - `osNotificationsEnabled`    — `NotificationManagerCompat
 *     .areNotificationsEnabled()`.
 *   - `messageChannelEnabled`     — `getNotificationChannel(
 *     phantom_messages).importance != IMPORTANCE_NONE` (Android
 *     8+). Below 8, always `true`.
 *
 * The `permissionPermanentlyDenied` field is not part of the
 * effective-state derivation — it flips the tap-action decision
 * from `LaunchRuntimePermission` (would silently succeed with
 * `granted=false`) to `OpenAppNotificationSettings`.
 */
public data class NotificationsGate(
    public val userOptedIn: Boolean,
    public val runtimePermissionGranted: Boolean,
    public val osNotificationsEnabled: Boolean,
    public val messageChannelEnabled: Boolean,
    public val permissionPermanentlyDenied: Boolean = false,
)

/**
 * Runtime notification-publish gate signal. Used by
 * `PhantomNotificationManager.showMessageNotification` to short-
 * circuit before `notify()`.
 */
public fun isEffectiveNotificationsEnabled(gate: NotificationsGate): Boolean =
    gate.userOptedIn &&
        gate.runtimePermissionGranted &&
        gate.osNotificationsEnabled &&
        gate.messageChannelEnabled

/**
 * Legacy 2-state UI enum used by `PermissionsStepV2` — retained so
 * the step's public signature stays the same. Derived from the
 * full gate.
 */
public enum class NotificationsPermissionState {
    Enabled,
    Disabled,
    ;
}

/**
 * Derive the 2-state UI toggle state from the full gate.
 * `Enabled` iff all four gate signals are true.
 */
public fun deriveNotificationsToggleState(
    gate: NotificationsGate,
): NotificationsPermissionState =
    if (isEffectiveNotificationsEnabled(gate)) {
        NotificationsPermissionState.Enabled
    } else {
        NotificationsPermissionState.Disabled
    }

/**
 * Tap-action decision — round-4 REDLINE §P1-1 5-way expansion.
 *
 * Every action has a visible on-device effect: no action is a
 * silent no-op. The five actions correspond directly to the
 * contract from architect's round-4 message:
 *
 *   Effective ON  → OptOutAppLevelOnly       (write opt-in = false)
 *   Effective OFF:
 *     opt-in true + all OS gates ready →
 *       (only reachable transiently after external gate change)   → OptOutAppLevelOnly is wrong;
 *       this state is impossible if all 4 signals true              this action isn't reached here
 *     opt-in true + runtime permission missing + not perm-denied → LaunchRuntimePermission
 *     opt-in true + runtime permission missing + perm-denied     → OpenAppNotificationSettings
 *     opt-in true + os notifications off                         → OpenAppNotificationSettings
 *     opt-in true + channel disabled                             → OpenMessageChannelSettings
 *     opt-in false + everything else ready                       → OptInAppLevelOnly
 *     opt-in false + runtime permission missing + not perm-denied → LaunchRuntimePermission
 *                                                                   (permission grant is the
 *                                                                   prerequisite; opt-in flip
 *                                                                   follows in launcher callback)
 *     opt-in false + runtime permission missing + perm-denied    → OpenAppNotificationSettings
 *     opt-in false + os notifications off                        → OpenAppNotificationSettings
 *     opt-in false + channel disabled                            → OpenMessageChannelSettings
 */
public enum class NotificationsTapAction {
    /** Write persisted opt-in bit = true. Everything else already ready. */
    OptInAppLevelOnly,

    /** Write persisted opt-in bit = false (user turning off from ON). */
    OptOutAppLevelOnly,

    /** Fire `POST_NOTIFICATIONS` runtime launcher (Android 13+). */
    LaunchRuntimePermission,

    /** Open Settings → app notifications page (channel-agnostic). */
    OpenAppNotificationSettings,

    /** Open Settings → phantom_messages channel importance page. */
    OpenMessageChannelSettings,
    ;
}

/**
 * Pure tap-action decision. Takes the current gate + SDK build
 * version + permanent-denial hint; returns exactly one action.
 * Every branch has a visible effect on-device.
 */
public fun decideNotificationsTapAction(
    gate: NotificationsGate,
    sdkInt: Int,
    tiramisuSdkInt: Int = Build.VERSION_CODES.TIRAMISU,
    oreoSdkInt: Int = Build.VERSION_CODES.O,
): NotificationsTapAction {
    // Effective ON → tap always turns it off. Cheapest and safest
    // path — the user's intent is unambiguous.
    if (isEffectiveNotificationsEnabled(gate)) {
        return NotificationsTapAction.OptOutAppLevelOnly
    }
    // Effective OFF: resolve whatever's blocking. Order matches
    // the least-friction chain: runtime perm → OS toggle →
    // channel toggle → app opt-in. Runtime perm is checked first
    // because it's the only signal that opens a dialog (all
    // others open Settings).
    val needsRuntimePermission =
        sdkInt >= tiramisuSdkInt && !gate.runtimePermissionGranted
    if (needsRuntimePermission) {
        return if (gate.permissionPermanentlyDenied) {
            // Android has already shown the system dialog and the
            // user tapped "Don't ask again" (or denied twice on
            // 11+). Firing the launcher now would silently return
            // granted=false without a dialog — the user has no
            // recourse from within the app. Open Settings so they
            // can flip it manually.
            NotificationsTapAction.OpenAppNotificationSettings
        } else {
            NotificationsTapAction.LaunchRuntimePermission
        }
    }
    if (!gate.osNotificationsEnabled) {
        return NotificationsTapAction.OpenAppNotificationSettings
    }
    if (sdkInt >= oreoSdkInt && !gate.messageChannelEnabled) {
        return NotificationsTapAction.OpenMessageChannelSettings
    }
    // All OS gates ready; only the app-level opt-in is missing.
    return NotificationsTapAction.OptInAppLevelOnly
}

// ── SharedPreferences: persisted app-level opt-in + rationale hint ─

/** SharedPreferences filename. */
private const val PHANTOM_PREFS = "phantom_prefs"

/**
 * SharedPreferences key for the persisted app-level opt-in bit.
 * Public so tests can reset it.
 */
public const val NOTIFICATIONS_USER_OPTED_IN_KEY: String =
    "notifications_user_opted_in"

/**
 * SharedPreferences key: has the app ever requested the
 * `POST_NOTIFICATIONS` runtime permission at least once? Used to
 * distinguish "never asked" (first request will show dialog) from
 * "asked and permanently denied" (next request would silently
 * return `granted=false`). Written by the launcher callback when
 * a request completes; read by the coordinator to infer
 * permanent-denial when `shouldShowRequestPermissionRationale`
 * returns false.
 */
public const val NOTIFICATIONS_HAS_EVER_REQUESTED_PERMISSION_KEY: String =
    "notifications_has_ever_requested_permission"

/**
 * SharedPreferences key: has the one-time upgrade migration for
 * `notifications_user_opted_in` been executed? Round-5 REDLINE
 * on Commit 5 §P0 pin — see [migrateNotificationsOptInIfNeeded]
 * KDoc for the migration contract.
 */
public const val NOTIFICATIONS_OPT_IN_MIGRATION_DONE_KEY: String =
    "notifications_opt_in_migration_done"

/**
 * Read the persisted app-level opt-in bit.
 *
 * Round-6 REDLINE on Commit 5 §P1 pin: **fail-closed**. Prior
 * round-5 shape returned `true` when both key and marker were
 * unset, on the theory that this was an existing-install
 * upgrade window. But on Android ≤ 12 (no runtime permission),
 * with a fresh install where the channel is created and OS
 * notifications default on, all four gates would flip to
 * `true` before the migration coroutine had run — a genuine
 * false-positive with real user impact.
 *
 * Round-6 pin:
 *   - If the opt-in key IS set → return its stored boolean.
 *   - Otherwise (key unset, regardless of migration marker) →
 *     return `false`.
 *
 * This is fail-closed: the safe direction is to drop a possible
 * notification during the process-startup window (which is
 * effectively zero because `PhantomApplication.initScope`
 * completes migration BEFORE `ready.complete(Unit)`, and the
 * publisher's `onNewMessageNotification` callback is only wired
 * up as part of `AppContainer` init that gates on `ready`).
 * The unsafe direction — firing a notification the user hasn't
 * opted into — is worse.
 */
public fun readUserOptedInToNotifications(context: Context): Boolean {
    val prefs = context.applicationContext
        .getSharedPreferences(PHANTOM_PREFS, Context.MODE_PRIVATE)
    if (prefs.contains(NOTIFICATIONS_USER_OPTED_IN_KEY)) {
        return prefs.getBoolean(NOTIFICATIONS_USER_OPTED_IN_KEY, false)
    }
    // Fail-closed: no explicit choice yet, no migration yet → OFF.
    return false
}

/**
 * Synchronous fail-loud writer for the persisted opt-in bit.
 *
 * Round-4 REDLINE §P1-2 pin: returns the `commit()` boolean so
 * the caller can distinguish success from failure and either
 * refresh state or surface the error. Callers MUST invoke this
 * on `Dispatchers.IO` (see `writeUserOptedInToNotificationsIo`).
 *
 * @return `true` if the write reached disk, `false` if SharedPrefs
 *   rejected the write. If `false`, the caller MUST NOT treat the
 *   requested value as persisted — read back with
 *   [readUserOptedInToNotifications] before acting on state.
 */
public fun writeUserOptedInToNotificationsBlocking(
    context: Context,
    value: Boolean,
): Boolean =
    context.applicationContext
        .getSharedPreferences(PHANTOM_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(NOTIFICATIONS_USER_OPTED_IN_KEY, value)
        .commit()

/** Read the "we've asked the runtime permission before" bit. */
public fun readHasEverRequestedNotificationPermission(context: Context): Boolean =
    context.applicationContext
        .getSharedPreferences(PHANTOM_PREFS, Context.MODE_PRIVATE)
        .getBoolean(NOTIFICATIONS_HAS_EVER_REQUESTED_PERMISSION_KEY, false)

/**
 * Mark that we've asked the runtime permission at least once.
 * Called from the launcher callback regardless of `granted`
 * result. Best-effort `.apply()` — if this write is lost the
 * only cost is a redundant launcher fire next tap (Android will
 * still show or not-show its dialog per its own state).
 */
public fun markNotificationPermissionRequested(context: Context) {
    context.applicationContext
        .getSharedPreferences(PHANTOM_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(NOTIFICATIONS_HAS_EVER_REQUESTED_PERMISSION_KEY, true)
        .apply()
}

// ── One-time upgrade migration ───────────────────────────────────

/**
 * Outcome of [migrateNotificationsOptInIfNeeded]. Exposed so the
 * caller (or a test) can inspect what path the migration took +
 * whether the `.commit()` reached disk.
 */
public sealed class NotificationsOptInMigrationOutcome {
    /**
     * Migration marker was already `true` — nothing to do this
     * process launch. No writes attempted.
     */
    public object AlreadyRun : NotificationsOptInMigrationOutcome()

    /**
     * The opt-in key was already present (onboarding or a prior
     * migration run had written it). Migration respects the
     * existing value verbatim — never overwrites an explicit user
     * choice — and just sets the marker so subsequent process
     * launches skip the migration probe entirely.
     */
    public data class RespectedExistingValue(val commitOk: Boolean) :
        NotificationsOptInMigrationOutcome()

    /**
     * No opt-in key was present and this install has an existing
     * identity. Migration wrote `userOptedIn = true` + marker
     * atomically so the user's prior "notifications work" state
     * is preserved across the upgrade.
     */
    public data class SetTrueForExistingUser(val commitOk: Boolean) :
        NotificationsOptInMigrationOutcome()

    /**
     * No opt-in key was present and no identity exists. This is
     * a fresh install — migration wrote `userOptedIn = false` +
     * marker atomically so the onboarding-time toggle governs
     * whether notifications ever fire.
     */
    public data class SetFalseForNewInstall(val commitOk: Boolean) :
        NotificationsOptInMigrationOutcome()
}

/**
 * Round-5 REDLINE on Commit 5 §P0 pin — one-time upgrade
 * migration for the `notifications_user_opted_in` SharedPreferences
 * key.
 *
 * Contract:
 *
 *   |  Prior state                            | Migration action
 *   |-----------------------------------------|------------------------------
 *   |  Marker set (migration done)            | No-op ([AlreadyRun])
 *   |  Key set (any value, marker unset)      | Respect key, write marker
 *   |                                         | ([RespectedExistingValue])
 *   |  Key unset, marker unset, identity=YES  | Write key=true + marker
 *   |                                         | ([SetTrueForExistingUser])
 *   |  Key unset, marker unset, identity=NO   | Write key=false + marker
 *   |                                         | ([SetFalseForNewInstall])
 *
 * INVARIANT: the migration NEVER overwrites an explicit `false`
 * that has been persisted (either by the onboarding toggle's
 * `OptOutAppLevelOnly` path or by an earlier run of this
 * function). Any subsequent process launch reads the persisted
 * key directly and returns its value.
 *
 * Must be called ONCE per process on cold start, BEFORE the
 * runtime notification publisher is first invoked. Wired from
 * `PhantomApplication.initScope.launch { ... }` immediately after
 * `AppContainer` init completes and we can query
 * `container.identityRepo.loadIdentity()` for the existing-user
 * signal.
 *
 * Uses `.commit()` (synchronous fail-loud) — the migration is
 * one shot per install lifetime and the correctness invariant
 * is "we know whether it ran". `.apply()` would return before
 * disk sync, and a process kill in the window would replay the
 * migration on next launch (still safe by construction, but
 * `.commit()` avoids the ambiguity).
 */
public fun migrateNotificationsOptInIfNeeded(
    context: Context,
    hasExistingIdentity: Boolean,
): NotificationsOptInMigrationOutcome {
    val prefs = context.applicationContext
        .getSharedPreferences(PHANTOM_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(NOTIFICATIONS_OPT_IN_MIGRATION_DONE_KEY, false)) {
        return NotificationsOptInMigrationOutcome.AlreadyRun
    }
    if (prefs.contains(NOTIFICATIONS_USER_OPTED_IN_KEY)) {
        val ok = prefs.edit()
            .putBoolean(NOTIFICATIONS_OPT_IN_MIGRATION_DONE_KEY, true)
            .commit()
        return NotificationsOptInMigrationOutcome.RespectedExistingValue(ok)
    }
    val defaultForThisInstall = hasExistingIdentity
    val ok = prefs.edit()
        .putBoolean(NOTIFICATIONS_USER_OPTED_IN_KEY, defaultForThisInstall)
        .putBoolean(NOTIFICATIONS_OPT_IN_MIGRATION_DONE_KEY, true)
        .commit()
    return if (defaultForThisInstall) {
        NotificationsOptInMigrationOutcome.SetTrueForExistingUser(ok)
    } else {
        NotificationsOptInMigrationOutcome.SetFalseForNewInstall(ok)
    }
}

// ── Live gate snapshot from the OS ────────────────────────────────

/**
 * Read the full [NotificationsGate] from the OS + persisted prefs.
 * Called at composition-time, on `ON_RESUME`, and from the runtime
 * notification publisher. Pure snapshot — no side effects.
 *
 * @param permanentlyDeniedOverride If non-null, forces the
 *   `permissionPermanentlyDenied` field to this value. Used by
 *   the coordinator to feed in the `shouldShowRequestPermissionRationale`
 *   signal (which requires an `Activity`, not just a `Context`).
 *   The runtime publisher calls without this — the field defaults
 *   to `false` (irrelevant for `notify()` path).
 */
public fun readCurrentNotificationsGate(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
    tiramisuSdkInt: Int = Build.VERSION_CODES.TIRAMISU,
    oreoSdkInt: Int = Build.VERSION_CODES.O,
    messageChannelId: String = PhantomNotificationManager.CHANNEL_ID,
    permanentlyDeniedOverride: Boolean? = null,
): NotificationsGate {
    val runtimePermissionGranted = if (sdkInt >= tiramisuSdkInt) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    val nmc = NotificationManagerCompat.from(context)
    val osNotificationsEnabled = nmc.areNotificationsEnabled()
    val messageChannelEnabled = if (sdkInt >= oreoSdkInt) {
        val ch = nmc.getNotificationChannel(messageChannelId)
        ch != null && ch.importance != NotificationManager.IMPORTANCE_NONE
    } else {
        true
    }
    return NotificationsGate(
        userOptedIn = readUserOptedInToNotifications(context),
        runtimePermissionGranted = runtimePermissionGranted,
        osNotificationsEnabled = osNotificationsEnabled,
        messageChannelEnabled = messageChannelEnabled,
        permissionPermanentlyDenied = permanentlyDeniedOverride ?: false,
    )
}
