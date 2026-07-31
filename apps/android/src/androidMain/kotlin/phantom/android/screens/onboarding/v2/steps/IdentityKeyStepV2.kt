// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.runtime.Composable
import phantom.android.screens.onboarding.v2.OnboardingFormStateV2

/**
 * IdentityKeyStepV2 — Step 2 / "Your identity key".
 *
 * Commit 2: PLACEHOLDER body. The real Step 2 (preview key card, username
 * input with `leadingContent`/`trailingContent` + format-only validation
 * per redline §C1..C2) lands in Commit 3.
 *
 * Signature is set today so [phantom.android.screens.onboarding.v2.OnboardingFlowV2]
 * compiles against the target Commit 3 shape without churn at that time:
 *   - Receives `formState` (Commit 3 reads/writes `formState.username`).
 *   - Emits `onFormStateChange` per character typed into the username field
 *     (Commit 3 wires this to the real input's onValueChange).
 *   - Fires `onContinueClick` when the user taps Continue — gated by the
 *     flow-level `canAdvanceFromV2` predicate; Commit 3 makes the button
 *     visually reflect the gate.
 *
 * Body renders [StepPlaceholderV2] so the flow is exercisable end-to-end
 * today for reviewer preview. Continue is disabled (unreachable in the
 * placeholder — the gate would block anyway since `formState.username` is
 * empty, but the disabled visual matches what a reviewer expects to see).
 */
@Suppress("UNUSED_PARAMETER")   // params are Commit-3 wiring; body ignores them in Commit 2
@Composable
fun IdentityKeyStepV2(
    formState: OnboardingFormStateV2,
    dotsIndex: Int,
    onFormStateChange: (OnboardingFormStateV2) -> Unit,
    onContinueClick: () -> Unit,
) {
    StepPlaceholderV2(
        title = "Step 2 · Identity key",
        subtitle = "Placeholder — Commit 3 will render the preview key card + username input.",
        dotsIndex = dotsIndex,
    )
}
