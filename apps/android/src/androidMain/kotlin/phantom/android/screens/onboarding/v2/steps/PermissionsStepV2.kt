// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.runtime.Composable
import phantom.android.screens.onboarding.v2.OnboardingFormStateV2

/**
 * PermissionsStepV2 — Step 4 / "Almost there".
 *
 * Commit 2: PLACEHOLDER body. The real Step 4 (Notifications real toggle
 * triggering the existing POST_NOTIFICATIONS launcher when switched ON;
 * Microphone + Nearby informational rows "Asked when first used." per
 * redline §A4) lands in Commit 5.
 *
 * `onDoneClick` is the flow's terminal advance-trigger. In Commit 2 the
 * flow simply advances `currentStep` to FinaleConfirmation (no crypto
 * runs — createOrLoad is bound to `onDoneClick` in Commit 5 via a
 * finalize() function inside OnboardingFlowV2).
 */
@Suppress("UNUSED_PARAMETER")   // params are Commit-5 wiring; body ignores them in Commit 2
@Composable
fun PermissionsStepV2(
    formState: OnboardingFormStateV2,
    dotsIndex: Int,
    onFormStateChange: (OnboardingFormStateV2) -> Unit,
    onDoneClick: () -> Unit,
) {
    StepPlaceholderV2(
        title = "Step 4 · Permissions",
        subtitle = "Placeholder — Commit 5 will render Notifications toggle + informational Microphone/Nearby rows.",
        dotsIndex = dotsIndex,
    )
}
