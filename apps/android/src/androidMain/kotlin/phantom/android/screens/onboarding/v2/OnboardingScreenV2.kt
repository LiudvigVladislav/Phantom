// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * first, then the multi-step flow — but everything downstream is a fresh
 * additive V2 tree living in this `.v2` sub-package. The old file is left
 * intact; both flows co-exist through Commits 2..4 and only Commit 5 flips
 * MainActivity's `Screen.Onboarding` branch to this entry.
 *
 * See `OnboardingStateV2.kt` §KDoc for the 6-step enum + form-state shape
 * and `OnboardingFlowV2.kt` §KDoc for the navigation contract.
 */
@Composable
fun OnboardingScreenV2(
    container: AppContainer,
    onComplete: () -> Unit,
) {
    var tosAccepted by remember { mutableStateOf(false) }

    // Round-3 REDLINE P2-4: TermsScreenV2 receives the system status-bar
    // inset explicitly (previously it applied `windowInsetsPadding`
    // itself, which returned 0 in Paparazzi and made the golden diverge
    // from device). The showcase passes 24.dp for the same reason.
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    if (!tosAccepted) {
        TermsScreenV2(onAccept = { tosAccepted = true }, topInset = topInset)
    } else {
        OnboardingFlowV2(container = container, onComplete = onComplete)
    }
}
