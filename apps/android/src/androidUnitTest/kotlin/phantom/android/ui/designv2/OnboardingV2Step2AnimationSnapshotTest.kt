// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyPreviewFrame00
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyPreviewFrame100
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyPreviewFrame50

/**
 * C6-b — three canonical animation frames of the key-preview card.
 * Contract sheet
 * `docs/tracks/android-onboarding/c6-b-key-preview-animation.md`
 * §6.14-6.16.
 *
 * Split into its own class so `recordPaparazziDebug --tests
 * OnboardingV2Step2AnimationSnapshotTest` re-records only these
 * three PNGs — targeted record pass matches C6-a's recovery-
 * snapshot pattern.
 *
 * Both the deterministic target hex sequence AND the per-tick
 * shuffle glyph table are seeded from a fixed 64-bit constant
 * (`KEY_PREVIEW_DETERMINISTIC_SEED`), so all three frames are
 * byte-stable across machines.
 *
 * Pixel-5 viewport (411 dp × 891 dp, fontScale 1.0) — matches
 * every other OnboardingV2 golden.
 */
class OnboardingV2Step2AnimationSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun onboarding_v2_step2_key_preview_frame_00pct() {
        paparazzi.snapshot { ShowcaseOnboardingIdentityKeyPreviewFrame00() }
    }

    @Test
    fun onboarding_v2_step2_key_preview_frame_50pct() {
        paparazzi.snapshot { ShowcaseOnboardingIdentityKeyPreviewFrame50() }
    }

    @Test
    fun onboarding_v2_step2_key_preview_frame_100pct() {
        paparazzi.snapshot { ShowcaseOnboardingIdentityKeyPreviewFrame100() }
    }
}
