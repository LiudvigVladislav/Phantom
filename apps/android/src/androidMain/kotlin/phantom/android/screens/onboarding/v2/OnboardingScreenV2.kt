// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import phantom.android.di.AppContainer

/**
 * OnboardingScreenV2 — entry composable for the redesigned onboarding.
 *
 * Signature-compatible with the pre-existing
 * [phantom.android.screens.onboarding.OnboardingScreen] so the atomic
 * entry-point switch in Commit 5 is a one-line change in `MainActivity`:
 *   `Screen.Onboarding -> OnboardingScreen(container, onComplete)`
 *   becomes
 *   `Screen.Onboarding -> OnboardingScreenV2(container, onComplete)`
 *
 * Structure mirrors the pre-existing shape at a high level — Terms gate
 * first, then the multi-step flow. Round-4 REDLINE on Commit 5 §P2-1:
 * `tosAccepted` state is HOISTED into this wrapper so
 * `PreFlowTermsGate` is a stateless renderer. This makes the gate
 * externally-drivable in tests (e.g. `OnboardingScreenV2GateTest`
 * passes an explicit `tosAccepted` value + observes `onAccept`
 * invocation without needing to compose the whole Terms scroll +
 * button-enable flow).
 */
@Composable
fun OnboardingScreenV2(
    container: AppContainer,
    onComplete: () -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Round-10 REDLINE §P1 pin: `rememberSaveable` so a config
    // change (rotation, dark-mode toggle, font-scale change,
    // Activity recreation on low memory) preserves the accepted-
    // Terms flag. Prior plain `remember` reset to `false` on
    // rotation and forced the user to re-read the Terms.
    var tosAccepted by rememberSaveable { mutableStateOf(false) }
    PreFlowTermsGate(
        topInset = topInset,
        tosAccepted = tosAccepted,
        onAcceptTos = { tosAccepted = true },
        flowContent = {
            OnboardingFlowV2(container = container, onComplete = onComplete)
        },
    )
}

/**
 * Terms-of-Service gate — round-4 REDLINE on Commit 5 §P2-1 pin.
 *
 * Stateless. The wrapper owns [tosAccepted]; the gate renders
 * either `TermsScreenV2` (with `onAccept = onAcceptTos`) OR
 * `flowContent`, based on the current value.
 *
 * `OnboardingScreenV2GateTest` uses this signature directly to
 * drive both branches AND the transition without reproducing the
 * if/else in the test harness.
 */
@Composable
fun PreFlowTermsGate(
    topInset: Dp,
    tosAccepted: Boolean,
    onAcceptTos: () -> Unit,
    flowContent: @Composable () -> Unit,
) {
    if (!tosAccepted) {
        TermsScreenV2(onAccept = onAcceptTos, topInset = topInset)
    } else {
        flowContent()
    }
}
