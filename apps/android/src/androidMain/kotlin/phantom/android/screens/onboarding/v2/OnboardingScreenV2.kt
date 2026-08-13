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
/**
 * Container-based entry point used by `MainActivity`. Delegates
 * the Terms-bypass-on-quarantine logic to
 * [OnboardingScreenV2Host] and passes the real production flow
 * as `flowContent`.
 *
 * Round-8 §P1 pin — architect's slot-based Host contract: the
 * host doesn't take a controller at all; it takes a
 * `@Composable () -> Unit flowContent` slot. Production passes
 * `OnboardingFlowV2(container, ...)`; tests pass any marker
 * composable. This lets the wrapper's Terms-bypass logic be
 * tested against the SAME real host without pulling in
 * `AppContainer` or a controller stub — the test just observes
 * whether the slot rendered.
 */
@Composable
internal fun OnboardingScreenV2(
    container: AppContainer,
    onComplete: () -> Unit,
    explicitInitialFinalizeState: OnboardingFinalizeState? = null,
) {
    OnboardingScreenV2Host(
        explicitInitialFinalizeState = explicitInitialFinalizeState,
        flowContent = {
            OnboardingFlowV2(
                container = container,
                onComplete = onComplete,
                explicitInitialFinalizeState = explicitInitialFinalizeState,
            )
        },
    )
}

/**
 * Round-8 §P1 pin — production wrapper extracted with a
 * `flowContent` SLOT so `OnboardingV2ProductionWrapperQuarantineTest`
 * can render the SAME composable path a real launch uses
 * (Terms bypass on quarantine + flow otherwise) with a plain
 * marker slot instead of a controller / AppContainer.
 *
 * Owns the Terms `rememberSaveable` state; delegates the render
 * branching to [PreFlowTermsGate] (stateless, unit-tested). The
 * OR-clause `tosAccepted = tosAccepted || quarantined` bypasses
 * Terms when MainActivity forces `MissingKeyRepairRequired` via
 * [explicitInitialFinalizeState]. Round-6 pin retained.
 */
@Composable
internal fun OnboardingScreenV2Host(
    explicitInitialFinalizeState: OnboardingFinalizeState?,
    flowContent: @Composable () -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var tosAccepted by rememberSaveable { mutableStateOf(false) }
    val quarantined = explicitInitialFinalizeState ==
        OnboardingFinalizeState.MissingKeyRepairRequired
    PreFlowTermsGate(
        topInset = topInset,
        tosAccepted = tosAccepted || quarantined,
        onAcceptTos = { tosAccepted = true },
        flowContent = flowContent,
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
