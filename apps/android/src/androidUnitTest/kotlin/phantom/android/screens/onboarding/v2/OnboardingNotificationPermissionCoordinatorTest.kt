// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import org.junit.Test
import phantom.android.notifications.NotificationsGate
import phantom.android.notifications.NotificationsPermissionState
import phantom.android.notifications.NotificationsTapAction
import phantom.android.notifications.decideNotificationsTapAction
import phantom.android.notifications.deriveNotificationsToggleState
import phantom.android.notifications.isEffectiveNotificationsEnabled
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-4 REDLINE on Commit 5 pure-JVM action-matrix pin for the
 * `NotificationsGate` + tap-action decision:
 *
 *   effectiveNotifications = userOptedIn
 *                          ∧ runtimePermissionGranted
 *                          ∧ osNotificationsEnabled
 *                          ∧ messageChannelEnabled
 *
 * Tests exhaustively cover the 5 tap-actions across the relevant
 * gate + SDK combinations, plus the permanent-denial fallback
 * that closes the round-4 §P1-2 launcher silent-tap gap.
 */
class OnboardingNotificationPermissionCoordinatorTest {

    private val tiramisu = 33
    private val oreo = 26
    private val jellyBean = 21

    private fun gate(
        optedIn: Boolean = false,
        permission: Boolean = false,
        osEnabled: Boolean = false,
        channelEnabled: Boolean = false,
        permanentlyDenied: Boolean = false,
    ) = NotificationsGate(
        userOptedIn = optedIn,
        runtimePermissionGranted = permission,
        osNotificationsEnabled = osEnabled,
        messageChannelEnabled = channelEnabled,
        permissionPermanentlyDenied = permanentlyDenied,
    )

    // ── isEffectiveNotificationsEnabled ───────────────────────────────

    @Test
    fun effective_enabled_iff_all_four_signals_true() {
        assertTrue(isEffectiveNotificationsEnabled(
            gate(optedIn = true, permission = true, osEnabled = true, channelEnabled = true),
        ))
        // Any signal false → Disabled.
        assertFalse(isEffectiveNotificationsEnabled(
            gate(optedIn = false, permission = true, osEnabled = true, channelEnabled = true),
        ))
        assertFalse(isEffectiveNotificationsEnabled(
            gate(optedIn = true, permission = false, osEnabled = true, channelEnabled = true),
        ))
        assertFalse(isEffectiveNotificationsEnabled(
            gate(optedIn = true, permission = true, osEnabled = false, channelEnabled = true),
        ))
        assertFalse(isEffectiveNotificationsEnabled(
            gate(optedIn = true, permission = true, osEnabled = true, channelEnabled = false),
        ))
    }

    @Test
    fun derive_toggle_state_mirrors_effective_boolean() {
        // 16 combinations — the derived enum must match the boolean.
        for (optIn in listOf(false, true)) {
            for (perm in listOf(false, true)) {
                for (os in listOf(false, true)) {
                    for (channel in listOf(false, true)) {
                        val g = gate(optIn, perm, os, channel)
                        val expected = if (optIn && perm && os && channel)
                            NotificationsPermissionState.Enabled
                        else
                            NotificationsPermissionState.Disabled
                        assertEquals(
                            expected,
                            deriveNotificationsToggleState(g),
                            "gate=$g",
                        )
                    }
                }
            }
        }
    }

    // ── decideNotificationsTapAction — 5-action matrix ────────────────

    @Test
    fun effective_ON_tap_writes_opt_out() {
        // Round-4 §P1-1 pin: the tap contract must let the user
        // turn notifications OFF from inside the app. Prior
        // shape (round-3) always opened Settings when opt-in was
        // true, forcing the user out of the app to disable.
        val g = gate(optedIn = true, permission = true, osEnabled = true, channelEnabled = true)
        assertEquals(
            NotificationsTapAction.OptOutAppLevelOnly,
            decideNotificationsTapAction(g, tiramisu, tiramisu, oreo),
        )
    }

    @Test
    fun not_opted_in_but_all_os_gates_ready_tap_flips_opt_in() {
        // Round-4 §P1-1 pin: prior shape (round-3) fired the
        // launcher unconditionally on this state, silently
        // succeeding without changing anything visible.
        val g = gate(optedIn = false, permission = true, osEnabled = true, channelEnabled = true)
        assertEquals(
            NotificationsTapAction.OptInAppLevelOnly,
            decideNotificationsTapAction(g, tiramisu, tiramisu, oreo),
        )
    }

    @Test
    fun permission_missing_and_never_asked_launches_runtime() {
        // Both opt-in cases: launcher is the least-friction
        // path when the runtime dialog is still available.
        for (optIn in listOf(false, true)) {
            for (os in listOf(false, true)) {
                for (channel in listOf(false, true)) {
                    val g = gate(
                        optedIn = optIn,
                        permission = false,
                        osEnabled = os,
                        channelEnabled = channel,
                        permanentlyDenied = false,
                    )
                    assertEquals(
                        NotificationsTapAction.LaunchRuntimePermission,
                        decideNotificationsTapAction(g, tiramisu, tiramisu, oreo),
                        "not-permanent-denial + missing perm → launcher (gate=$g)",
                    )
                }
            }
        }
    }

    @Test
    fun permission_missing_and_permanently_denied_opens_settings() {
        // Round-4 §P1-2 core pin: the round-3 shape would fire
        // the launcher, which Android silently short-circuits
        // with granted=false — the user has no in-app path
        // forward. Correct action is to open Settings.
        for (optIn in listOf(false, true)) {
            for (os in listOf(false, true)) {
                for (channel in listOf(false, true)) {
                    val g = gate(
                        optedIn = optIn,
                        permission = false,
                        osEnabled = os,
                        channelEnabled = channel,
                        permanentlyDenied = true,
                    )
                    assertEquals(
                        NotificationsTapAction.OpenAppNotificationSettings,
                        decideNotificationsTapAction(g, tiramisu, tiramisu, oreo),
                        "permanent-denial + missing perm → Settings (gate=$g)",
                    )
                }
            }
        }
    }

    @Test
    fun permission_granted_but_os_disabled_opens_app_settings() {
        // Round-3 §P1-2 pin preserved. Applies to both opt-in
        // cases: OS-level toggle is the block, Settings is the
        // remedy.
        for (optIn in listOf(false, true)) {
            for (channel in listOf(false, true)) {
                val g = gate(
                    optedIn = optIn,
                    permission = true,
                    osEnabled = false,
                    channelEnabled = channel,
                )
                assertEquals(
                    NotificationsTapAction.OpenAppNotificationSettings,
                    decideNotificationsTapAction(g, tiramisu, tiramisu, oreo),
                    "OS toggle off → app settings (gate=$g)",
                )
            }
        }
    }

    @Test
    fun os_enabled_but_channel_blocked_opens_channel_settings() {
        // Round-4 §P1-3 pin: the message channel importance
        // gate. If the user set `phantom_messages` importance
        // to NONE while leaving app-level notifications on,
        // `notify()` returns success but Android drops the
        // toast — the UI would lie unless the gate reads this
        // signal AND the tap opens the channel settings page.
        for (optIn in listOf(false, true)) {
            val g = gate(
                optedIn = optIn,
                permission = true,
                osEnabled = true,
                channelEnabled = false,
            )
            assertEquals(
                NotificationsTapAction.OpenMessageChannelSettings,
                decideNotificationsTapAction(g, tiramisu, tiramisu, oreo),
                "channel blocked → channel settings (gate=$g)",
            )
        }
    }

    // ── SDK boundary at Tiramisu ──────────────────────────────────────

    @Test
    fun tiramisu_boundary_inclusive_for_runtime_permission_launch() {
        val g = gate(optedIn = true, permission = false, osEnabled = true, channelEnabled = true)
        // Exactly SDK 33 → launcher.
        assertEquals(
            NotificationsTapAction.LaunchRuntimePermission,
            decideNotificationsTapAction(g, tiramisu, tiramisu, oreo),
        )
        // Below 33: gate's `permission` should be `true` in
        // real reads (readCurrentNotificationsGate returns true
        // pre-Tiramisu), but if a caller passes false anyway,
        // we take the OS-toggle-off branch (permission-missing
        // check only fires above Tiramisu).
        val gBelow = gate(optedIn = true, permission = true, osEnabled = true, channelEnabled = true)
        assertEquals(
            NotificationsTapAction.OptOutAppLevelOnly,
            decideNotificationsTapAction(gBelow, tiramisu - 1, tiramisu, oreo),
        )
    }

    @Test
    fun oreo_boundary_channel_gate_ignored_pre_oreo() {
        // Below Android 8, no channels exist. `readCurrentNotificationsGate`
        // returns `messageChannelEnabled = true` in that case,
        // so callers won't see the OpenMessageChannelSettings
        // action pre-Oreo. This test verifies the decision
        // function's behaviour when passed the (unreal-but-
        // possible in tests) combination of `channelEnabled =
        // false` at SDK < Oreo.
        val g = gate(
            optedIn = true, permission = true, osEnabled = true, channelEnabled = false,
        )
        // Pre-Oreo: channel gate skipped → falls through to
        // OptOutAppLevelOnly? Actually no — the effective
        // check reads channelEnabled=false so effective=false;
        // then the OS toggle check passes; then the channel
        // check is guarded by `sdkInt >= oreoSdkInt` so it
        // doesn't fire; and we fall to OptInAppLevelOnly.
        assertEquals(
            NotificationsTapAction.OptInAppLevelOnly,
            decideNotificationsTapAction(g, jellyBean, tiramisu, oreo),
        )
    }

    // ── Legacy helpers still work (backward-compat callers) ───────────

    @Test
    fun legacy_notificationsPermissionStateOf_maps_boolean_to_enum() {
        assertEquals(
            NotificationsPermissionState.Enabled,
            notificationsPermissionStateOf(areNotificationsEnabledSystemwide = true),
        )
        assertEquals(
            NotificationsPermissionState.Disabled,
            notificationsPermissionStateOf(areNotificationsEnabledSystemwide = false),
        )
    }

    @Test
    fun legacy_shouldLaunchNotificationPermissionRequest_still_correct() {
        assertTrue(
            shouldLaunchNotificationPermissionRequest(
                NotificationsPermissionState.Disabled, tiramisu, tiramisu,
            ),
        )
        assertFalse(
            shouldLaunchNotificationPermissionRequest(
                NotificationsPermissionState.Disabled, tiramisu - 1, tiramisu,
            ),
        )
        assertFalse(
            shouldLaunchNotificationPermissionRequest(
                NotificationsPermissionState.Enabled, tiramisu, tiramisu,
            ),
        )
    }
}
