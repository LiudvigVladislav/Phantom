// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.notifications.NotificationsPermissionState
import phantom.android.screens.onboarding.v2.OnboardingFormStateV2
import phantom.android.screens.onboarding.v2.OnboardingStepDotsV2
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.android.ui.designv2.components.PhantomButton

/**
 * PermissionsStepV2 — Step 4 / "Almost there".
 *
 * Round-1 REDLINE on Commit 5 §P1-1 / §P1-2 shape:
 *   - Notifications: real interactive Switch row whose state
 *     mirrors the OS permission (source of truth per
 *     `OnboardingNotificationPermissionCoordinator`). Tapping ON
 *     when currently OFF fires `onRequestNotificationPermission` —
 *     which the flow-level composable wires to the runtime
 *     launcher. Below Android 13 or when already granted, no
 *     launcher fires (the coordinator makes that decision).
 *   - Microphone: STATIC info row per architect §A4. Icon + title
 *     + "Asked when first used." body. No toggle, no `Role.Switch`,
 *     no `ToggleableState`. The OS RECORD_AUDIO dialog appears
 *     at first voice-record / call attempt.
 *   - Nearby discovery: STATIC info row per architect §A4. Icon +
 *     title + "Asked when first used." body. No toggle. The OS
 *     BLUETOOTH_SCAN dialog appears at first mesh-discovery
 *     attempt.
 *
 * Full layout per handoff `Onboarding.dc.html` §"STEP 4":
 *   - Title 29 sp / lineHeight 34 sp Geist SemiBold ("Almost there").
 *   - Subtitle "Everything below is optional and stays on your
 *     device. Skip anything you don't want."
 *   - Three rows, each a full-width tile (18 dp radius, 15 dp
 *     padding, 11 dp gap). Row background / border animate on
 *     state change via `animateColorAsState` with a 160 ms
 *     ease-out — handoff `transition:'border-color .16s ease,
 *     background .16s ease'`. Notifications row also renders a
 *     Toggle track (46 × 27 dp) + knob (21 dp) sliding via
 *     `translateX(19dp)` on 180 ms cubic-bezier easing.
 *   - Bottom disclaimer: "Phantom never uploads contacts,\nlocation,
 *     or usage analytics." (11 sp mono, centered).
 *   - StepDots at index 3, "Done, let's go" CTA.
 *
 * `onDoneClick` is the flow's terminal advance-trigger; it
 * delegates to `OnboardingFlowV2`'s finalize state machine (three
 * phases: savePrivacyMode → createOrLoad → initMessaging). No
 * permission gate on Done — user can proceed with the toggle OFF;
 * features prompt at first use.
 */
@Composable
fun PermissionsStepV2(
    formState: OnboardingFormStateV2,
    dotsIndex: Int,
    onFormStateChange: (OnboardingFormStateV2) -> Unit,
    onDoneClick: () -> Unit,
    onRequestNotificationPermission: () -> Unit = {},
    notificationsState: NotificationsPermissionState = NotificationsPermissionState.Disabled,
) {
    // NB: `formState` + `onFormStateChange` remain in the signature
    // for symmetry with sibling steps (Identity / Privacy) but this
    // step does NOT write to them — Notifications state is derived
    // from the OS via `notificationsState`, Mic/Nearby are info
    // rows.
    @Suppress("UNUSED_PARAMETER") val _formState = formState
    @Suppress("UNUSED_PARAMETER") val _onFormStateChange = onFormStateChange

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                // Round-8 REDLINE §P1: top bar reserved above.
                .padding(top = 4.dp, bottom = 8.dp),
        ) {
            Text(
                text = "Almost there",
                color = DesignV2Tokens.Colors.TextPrimary,
                style = TextStyle(
                    fontFamily = DesignV2FontDisplay,
                    fontSize = 29.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 34.sp,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Everything below is optional and stays on your device. Skip anything you don't want.",
                color = DesignV2Tokens.Colors.TextTertiary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 14.5.sp,
                    lineHeight = 22.sp,
                ),
            )
            Spacer(Modifier.height(22.dp))

            NotificationsPermissionRow(
                state = notificationsState,
                onRequestNotificationPermission = onRequestNotificationPermission,
            )
            Spacer(Modifier.height(11.dp))
            InfoPermissionRow(
                iconRes = R.drawable.ic_dv2_mic_on,
                title = "Microphone",
                body = "Asked when first used — for voice messages and calls.",
            )
            Spacer(Modifier.height(11.dp))
            InfoPermissionRow(
                iconRes = R.drawable.ic_dv2_nearby,
                title = "Nearby discovery",
                body = "Asked when first used — for local-mesh people search.",
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Phantom never uploads contacts,\nlocation, or usage analytics.",
                color = DesignV2Tokens.Colors.TextQuaternary,
                style = TextStyle(
                    fontFamily = DesignV2FontMono,
                    fontSize = 11.sp,
                    lineHeight = 18.7.sp,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Round-9 REDLINE §P1: safe-bottom navigation-bar inset.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp)
                .padding(top = 14.dp, bottom = 34.dp),
        ) {
            OnboardingStepDotsV2(dotsIndex = dotsIndex)
            Spacer(Modifier.height(16.dp))
            PhantomButton(
                text = "Done, let's go",
                onClick = onDoneClick,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Notifications row — interactive Switch ───────────────────────────

@Composable
private fun NotificationsPermissionRow(
    state: NotificationsPermissionState,
    onRequestNotificationPermission: () -> Unit,
) {
    val checked = state == NotificationsPermissionState.Enabled
    // Row bg/border animate on grant-state flip per handoff
    // `transition:'border-color .16s ease, background .16s ease'`.
    // Round-2 REDLINE §P2-2 corrected easing: CSS `ease` is
    // `cubic-bezier(0.25, 0.1, 0.25, 1)`, NOT Compose's default
    // `tween` easing (which is `FastOutSlowInEasing`, a Material
    // curve). Round-1 shape used the default and drifted from
    // handoff's motion feel.
    val rowSpec = tween<Color>(
        durationMillis = 160,
        easing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f),
    )
    val rowBg by animateColorAsState(
        targetValue = if (checked) DesignV2Tokens.Colors.Cyan.copy(alpha = 0.04f)
                      else DesignV2Tokens.Colors.SurfaceInset,
        animationSpec = rowSpec,
        label = "notifRowBg",
    )
    val rowBorder by animateColorAsState(
        targetValue = if (checked) DesignV2Tokens.Colors.Cyan.copy(alpha = 0.22f)
                      else DesignV2Tokens.Colors.Border,
        animationSpec = rowSpec,
        label = "notifRowBorder",
    )
    val iconBg by animateColorAsState(
        targetValue = if (checked) DesignV2Tokens.Colors.Cyan.copy(alpha = 0.12f)
                      else DesignV2Tokens.Colors.Surface,
        animationSpec = rowSpec,
        label = "notifIconBg",
    )
    val iconBorder by animateColorAsState(
        targetValue = if (checked) DesignV2Tokens.Colors.Cyan.copy(alpha = 0.24f)
                      else DesignV2Tokens.Colors.Border,
        animationSpec = rowSpec,
        label = "notifIconBorder",
    )
    val iconTint by animateColorAsState(
        targetValue = if (checked) DesignV2Tokens.Colors.Cyan
                      else DesignV2Tokens.Colors.TextTertiary,
        animationSpec = rowSpec,
        label = "notifIconTint",
    )

    // Round-2 REDLINE on Commit 5 §P1-2 tap contract: tap ALWAYS
    // fires `onRequestNotificationPermission` — the flow-level
    // callback routes to (a) the runtime launcher on Android 13+
    // with Disabled state, or (b) system app-notification
    // Settings for every other case. Both surfaces have real
    // side effects; there is no no-op path.
    //
    // Rationale for the click label split: when Enabled the user
    // needs to know the app can't revoke on-device — Settings is
    // the only path back to Disabled. When Disabled the user
    // needs to know the tap will trigger a permission grant flow
    // (either OS dialog or Settings jump).
    val onClickLabel = if (checked)
        "Notifications on — tap to manage in system settings"
    else "Turn notifications on"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(rowBg)
            .border(1.dp, rowBorder, RoundedCornerShape(18.dp))
            .clickable(
                role = Role.Switch,
                onClickLabel = onClickLabel,
                onClick = onRequestNotificationPermission,
            )
            .semantics {
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        PermissionRowIconBox(
            iconRes = R.drawable.ic_dv2_notify_on,
            iconBg = iconBg,
            iconBorder = iconBorder,
            iconTint = iconTint,
        )
        Column(modifier = Modifier.weight(1f)) {
            // Round-9 REDLINE §P1: allow the title to wrap on
            // char boundaries when the container width forces it
            // (Compose breaks unbreakable Latin words when
            // softWrap = true + maxLines > 1). Full text
            // preserved, user's fontScale respected — no cap.
            Text(
                text = "Notifications",
                color = DesignV2Tokens.Colors.TextPrimary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                ),
                maxLines = 2,
                softWrap = true,
            )
            Spacer(Modifier.height(3.dp))
            // Round-4 REDLINE on Commit 5 §P1-5: prior copy
            // ("Sender and preview stay encrypted on device")
            // was inaccurate — plaintext preview IS handed to
            // Android's Notification Manager, and the lock-screen
            // renderer, so it's neither "encrypted" nor "on-
            // device-only" in the strict sense. Reworded to what
            // is actually true: notifications are generated
            // locally without a push provider round-trip.
            Text(
                text = "Notifications are generated locally on this device — no push provider sees the preview.",
                color = DesignV2Tokens.Colors.TextTertiary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 12.5.sp,
                    lineHeight = 18.75.sp,
                ),
            )
        }
        ToggleTrack(checked = checked)
    }
}

// ── Info rows (Microphone, Nearby) — static per §A4 ──────────────────

@Composable
private fun InfoPermissionRow(
    iconRes: Int,
    title: String,
    body: String,
) {
    // Static styling — no state animation, no Role.Switch, no
    // ToggleableState. Not interactive.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DesignV2Tokens.Colors.SurfaceInset)
            .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(18.dp))
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        PermissionRowIconBox(
            iconRes = iconRes,
            iconBg = DesignV2Tokens.Colors.Surface,
            iconBorder = DesignV2Tokens.Colors.Border,
            iconTint = DesignV2Tokens.Colors.TextTertiary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DesignV2Tokens.Colors.TextPrimary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                ),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = body,
                color = DesignV2Tokens.Colors.TextTertiary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 12.5.sp,
                    lineHeight = 18.75.sp,
                ),
            )
        }
    }
}

@Composable
private fun PermissionRowIconBox(
    iconRes: Int,
    iconBg: Color,
    iconBorder: Color,
    iconTint: Color,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(iconBg)
            .border(1.dp, iconBorder, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .size(20.dp)
                .clearAndSetSemantics { },
        )
    }
}

/**
 * Toggle track (46 × 27 dp) + knob (21 dp). Sliding via
 * `translateX(19dp)` on 180 ms `cubic-bezier(.2,.85,.25,1)`
 * easing per handoff line 154. Purely visual — the row's outer
 * `clickable` handles the state change so the toggle track itself
 * is decorative (`clearAndSetSemantics { }`).
 */
@Composable
private fun ToggleTrack(checked: Boolean) {
    val trackBg by animateColorAsState(
        targetValue = if (checked) DesignV2Tokens.Colors.Cyan
                      else DesignV2Tokens.Colors.Surface,
        animationSpec = tween(180, easing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1.0f)),
        label = "toggleTrackBg",
    )
    val trackBorder by animateColorAsState(
        targetValue = if (checked) DesignV2Tokens.Colors.Cyan
                      else DesignV2Tokens.Colors.Border,
        animationSpec = tween(180, easing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1.0f)),
        label = "toggleTrackBorder",
    )
    val knobBg by animateColorAsState(
        targetValue = if (checked) Color(0xFF04222B)
                      else DesignV2Tokens.Colors.TextTertiary,
        animationSpec = tween(180, easing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1.0f)),
        label = "toggleKnobBg",
    )
    val knobX by animateDpAsState(
        targetValue = if (checked) 19.dp else 0.dp,
        animationSpec = tween(180, easing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1.0f)),
        label = "toggleKnobX",
    )

    Box(
        modifier = Modifier
            .width(46.dp)
            .height(27.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(trackBg)
            .border(1.dp, trackBorder, RoundedCornerShape(999.dp))
            .padding(2.dp)
            .clearAndSetSemantics { },
    ) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .offset(x = knobX, y = 0.dp)
                .clip(CircleShape)
                .background(knobBg),
        )
    }
}
