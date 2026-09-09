// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingFinaleConfirmation
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingHowV2
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyEmpty
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyInvalid
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyShort
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyValid
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPermissionsNotifDisabled
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPermissionsNotifEnabled
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPricingSheet
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPricingSheetScrolledBottom
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPrivacyGhostLocked
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPrivacyPrivate
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPrivacyStandard
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
@org.junit.experimental.categories.Category(phantom.android.testing.PaparazziTestEngine::class)
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

    // Commit 3 additions — IdentityKey states + Finale confirmation.

    @Test
    fun onboarding_v2_identity_key_empty() {
        paparazzi.snapshot { ShowcaseOnboardingIdentityKeyEmpty() }
    }

    @Test
    fun onboarding_v2_identity_key_short() {
        paparazzi.snapshot { ShowcaseOnboardingIdentityKeyShort() }
    }

    @Test
    fun onboarding_v2_identity_key_invalid_chars() {
        paparazzi.snapshot { ShowcaseOnboardingIdentityKeyInvalid() }
    }

    @Test
    fun onboarding_v2_identity_key_valid() {
        paparazzi.snapshot { ShowcaseOnboardingIdentityKeyValid() }
    }

    @Test
    fun onboarding_v2_finale_confirmation() {
        paparazzi.snapshot { ShowcaseOnboardingFinaleConfirmation() }
    }

    // Commit 4 additions — Privacy dial states + Pricing sheet.

    @Test
    fun onboarding_v2_privacy_standard() {
        paparazzi.snapshot { ShowcaseOnboardingPrivacyStandard() }
    }

    @Test
    fun onboarding_v2_privacy_private() {
        paparazzi.snapshot { ShowcaseOnboardingPrivacyPrivate() }
    }

    @Test
    fun onboarding_v2_privacy_ghost_locked() {
        paparazzi.snapshot { ShowcaseOnboardingPrivacyGhostLocked() }
    }

    @Test
    fun onboarding_v2_pricing_sheet() {
        paparazzi.snapshot { ShowcaseOnboardingPricingSheet() }
    }

    @Test
    fun onboarding_v2_pricing_sheet_scrolled_bottom() {
        // Round-1 REDLINE on Commit 4 §P2-1: second pricing golden
        // capturing the SCROLLED-BOTTOM state of the sheet — the
        // Business tier + footer live below the 82 %-viewport fold
        // in the top-of-panel golden. This golden pushes the sheet's
        // internal scroll to its bottom via
        // `scrollToBottomForShowcase = true`.
        paparazzi.snapshot { ShowcaseOnboardingPricingSheetScrolledBottom() }
    }

    // Commit 5 · Round-1 REDLINE additions — Permissions step, 2
    // Notifications OS-state variants. Mic + Nearby are static
    // info rows in both goldens (§A4).

    @Test
    fun onboarding_v2_permissions_notif_disabled() {
        paparazzi.snapshot { ShowcaseOnboardingPermissionsNotifDisabled() }
    }

    @Test
    fun onboarding_v2_permissions_notif_enabled() {
        paparazzi.snapshot { ShowcaseOnboardingPermissionsNotifEnabled() }
    }
}
