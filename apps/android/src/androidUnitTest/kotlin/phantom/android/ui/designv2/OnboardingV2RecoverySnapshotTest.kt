// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityRepairRequired
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingTransientStartupError

/**
 * C6-a — recovery-surface goldens for the two chromeless full-
 * screen surfaces added by the sealed-holder work: proven-
 * corruption (`OnboardingRepairRequiredScreen`) and transient
 * startup failure (`OnboardingStartupErrorScreen`).
 *
 * Split into its OWN test class so `recordPaparazziDebug --tests
 * OnboardingV2RecoverySnapshotTest` re-records only these two
 * PNGs — the C6-a mini-round handoff needs a targeted record pass,
 * not a full v2 regeneration.
 *
 * Both goldens use the default Pixel-5 viewport
 * (411 dp × 891 dp, fontScale 1.0) — the canonical device tier
 * for every other OnboardingV2 golden. The narrow-width +
 * fontScale-2.0 reachability check for these screens lives in
 * [phantom.android.screens.onboarding.v2.OnboardingV2ScrollableCtaReachabilityTest]
 * (Robolectric + Compose UI test), not as a Paparazzi golden —
 * consistent with how every other CTA-reachability guarantee in
 * the OnboardingV2 stack is pinned.
 */
class OnboardingV2RecoverySnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun onboarding_v2_identity_repair_required() {
        paparazzi.snapshot { ShowcaseOnboardingIdentityRepairRequired() }
    }

    @Test
    fun onboarding_v2_transient_startup_error() {
        paparazzi.snapshot { ShowcaseOnboardingTransientStartupError() }
    }
}
