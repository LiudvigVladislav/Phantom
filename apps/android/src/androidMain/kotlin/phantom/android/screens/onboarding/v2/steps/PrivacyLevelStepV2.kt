// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.runtime.Composable
import phantom.android.screens.onboarding.v2.OnboardingFormStateV2

/**
 * PrivacyLevelStepV2 — Step 3 / "Choose your privacy level".
 *
 * Commit 2: PLACEHOLDER body. The real Step 3 (3-segment slider +
 * description card + Ghost lock CTA that opens the Pricing sheet) lands
 * in Commit 4.
 *
 * `onGhostLockClick` is set today so the flow's toast-emission wiring
 * (Commit 4's Pricing sheet CTA → "Phantom Pro — coming soon.") is
 * signature-compatible now. Placeholder body ignores it.
 */
@Suppress("UNUSED_PARAMETER")   // params are Commit-4 wiring; body ignores them in Commit 2
@Composable
fun PrivacyLevelStepV2(
    formState: OnboardingFormStateV2,
    dotsIndex: Int,
    onFormStateChange: (OnboardingFormStateV2) -> Unit,
    onContinueClick: () -> Unit,
    onGhostLockClick: () -> Unit,
) {
    StepPlaceholderV2(
        title = "Step 3 · Privacy level",
        subtitle = "Placeholder — Commit 4 will render the 3-segment slider + description card.",
        dotsIndex = dotsIndex,
    )
}
