// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.runtime.Composable
import phantom.android.screens.onboarding.v2.OnboardingFormStateV2

/**
 * FinaleConfirmationStepV2 — post-Permissions confirmation state.
 * Chromeless: no top bar, no dots (state = FinaleConfirmation).
 *
 * Commit 2: PLACEHOLDER body. The real finale (real Ed25519 fingerprint
 * from `formState.signingPublicKeyHex`, hex-settle animation, Copy button
 * writes the full 64-char hex, fingerprint chip labelled `fingerprint · short form`
 * per redline §C1) lands in Commit 3.
 *
 * `onContinueClick` fires when the user acknowledges — this is the flow's
 * terminal exit, wired at the caller side to `onComplete()` (which
 * MainActivity uses to transition to ChatList).
 */
@Suppress("UNUSED_PARAMETER")   // params are Commit-3 wiring; body ignores them in Commit 2
@Composable
fun FinaleConfirmationStepV2(
    formState: OnboardingFormStateV2,
    onContinueClick: () -> Unit,
) {
    StepPlaceholderV2(
        title = "Finale · Identity created",
        subtitle = "Placeholder — Commit 3 will render the real Ed25519 fingerprint + Copy + fingerprint chip.",
        dotsIndex = -1,  // Finale is chromeless — no dots per OnboardingStepV2 enum
    )
}
