// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingHowV2
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingTermsV2
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingWelcomeV2

/**
 * OnboardingV2 snapshot goldens for Commit 2 — the two real steps
 * (Welcome + How) + the preserved Terms gate.
 *
 * Coverage scope this commit: 1 golden per real screen at the default
 * Pixel-5 viewport (411 dp × 891 dp, fontScale 1.0). The narrow-width
 * + fontScale 2.0 matrix goldens for the full 5-step flow arrive in
 * Commit 5 alongside the semantics test bundle (per architect delivery
 * shape §5 — "Permissions + finalization + Paparazzi/semantics").
 *
 * These goldens act as an incremental review gate: each commit that
 * lands a real step also lands its baseline PNG so the reviewer can
 * visually verify the frozen visual before the next commit builds on
 * top of it. Placeholder-body steps (Identity / Privacy / Permissions
 * / FinaleConfirmation) don't get goldens this commit — they'd add
 * churn for a body that's replaced in Commits 3-5.
 */
class OnboardingV2SnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun onboarding_v2_terms() {
        paparazzi.snapshot { ShowcaseOnboardingTermsV2() }
    }

    @Test
    fun onboarding_v2_welcome() {
        paparazzi.snapshot { ShowcaseOnboardingWelcomeV2() }
    }

    @Test
    fun onboarding_v2_how() {
        paparazzi.snapshot { ShowcaseOnboardingHowV2() }
    }
}
