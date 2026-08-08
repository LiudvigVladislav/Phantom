// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.content.Context

/**
 * C6-a round-4 REDLINE §P1 pin — DURABLE quarantine marker for
 * the "identity persisted but signing-key material is broken"
 * corner case.
 *
 * ## Why a durable marker
 *
 * Round-3 shape called `Activity.finishAffinity()` from the
 * repair-screen exit action. That closes the current task, but
 * the SharedPreferences-backed identity record remains on disk.
 * The next process launch reaches the startup routing which
 * checks `identityRepo.loadIdentity() != null` — the broken
 * record is present, so the flow bypasses onboarding and lands
 * the user on `ChatList` with a malformed signing key.
 * Downstream runtime paths that rely on the signing key
 * (message signing, discovery advertisements, hybrid-relay
 * verification) fail in surprising ways.
 *
 * Round-4 introduces this marker: it's set at the same moment
 * we detect the malformed-hex state (from the repair-screen
 * exit action, BEFORE any Activity.finishAffinity call), and
 * the startup routing in `MainActivity` reads it BEFORE the
 * identity check. When set, the app is routed back into the
 * onboarding flow with the holder pre-initialised to
 * [OnboardingFinalizeState.MissingKeyRepairRequired] — so the
 * user sees the repair-required screen again on next launch
 * instead of a broken ChatList.
 *
 * The marker is cleared only by:
 *   - Uninstall + reinstall of the app (wipes SharedPreferences).
 *   - A downstream commit's real repair capability
 *     (`IdentityManager.delete()` + fresh identity generation)
 *     which will call [clearRepairRequiredBlocking] after
 *     writing the new identity to disk.
 *
 * The exit-side call MUST use the `Blocking` variant with
 * `.commit()` — best-effort `.apply()` would race with
 * `Activity.finishAffinity()`, and a lost write would leave
 * the app open to bypass onboarding on the next launch.
 *
 * ## SharedPreferences storage
 *
 * Uses the shared `phantom_prefs` file (matches
 * `NotificationsGate` + `TransportPreferences` — all app-wide
 * prefs live under one file so uninstall clears every marker
 * atomically).
 */
public object IdentityRepairMarker {

    private const val PHANTOM_PREFS = "phantom_prefs"
    public const val IDENTITY_REPAIR_REQUIRED_KEY: String = "identity_repair_required"

    /**
     * Read the marker. Called from `MainActivity` startup gate
     * BEFORE the identity check — this is the durable side of
     * the repair-required state.
     */
    public fun isRepairRequired(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PHANTOM_PREFS, Context.MODE_PRIVATE)
            .getBoolean(IDENTITY_REPAIR_REQUIRED_KEY, false)

    /**
     * Write the marker with `.commit()` — synchronous, durable,
     * fail-loud. Called from the repair-screen exit action.
     *
     * @return `true` if the write reached disk. Callers MUST NOT
     *   proceed to `Activity.finishAffinity()` on `false` — a lost
     *   write means the next launch could bypass onboarding.
     *   Surface the error to the user (or leave the flow in
     *   MissingKeyRepairRequired) rather than silently exiting.
     */
    public fun markRepairRequiredBlocking(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PHANTOM_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(IDENTITY_REPAIR_REQUIRED_KEY, true)
            .commit()

    /**
     * Clear the marker with `.commit()`. Not called from C6-a —
     * only a downstream commit's real repair capability
     * (`IdentityManager.delete()` + fresh identity) will invoke
     * this after successfully writing a healthy identity to disk.
     */
    public fun clearRepairRequiredBlocking(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PHANTOM_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(IDENTITY_REPAIR_REQUIRED_KEY, false)
            .commit()
}

// C6-a round-4 REDLINE §P1 pin: `Context.findActivityOrNull()`
// is declared in `OnboardingNotificationPermissionCoordinator.kt`
// (promoted from `private` to `internal` in the same amend) and
// reused by the repair-screen exit action to walk the
// ContextWrapper chain reliably. Prior round-3 shape's
// `(context as? Activity)?.finishAffinity()` silently no-op'd
// when Compose passed a `ContextThemeWrapper` — the exit action
// now uses that helper with a fail-loud Log when no Activity is
// in the chain.
