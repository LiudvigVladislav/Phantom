// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.Dv2Icons
import phantom.android.ui.designv2.showcase.Dv2OnboardingIcons
import phantom.android.ui.designv2.showcase.Dv2PricingIcons
import phantom.android.ui.designv2.showcase.ShowcaseIconAudit

/**
 * Icon side-by-side audit goldens — every dv2 drawable rendered at 24dp AND
 * 48dp on a Surface background, tinted Cyan.
 *
 * Split into three goldens because Paparazzi's default Pixel 5 viewport is
 * ~891dp tall and the PNG encoder tops out around 1000px in height. An
 * earlier single-golden variant of the F2b batch silently clipped the last
 * 9 rows (verified against the source SVG list). The splits below keep every
 * row fully rendered:
 *
 *   - `icon_audit_a_first_half`   : first 11 of the 21 F2b drawables
 *     (`Dv2Icons` alphabetical).
 *   - `icon_audit_b_second_half`  : remaining 10 F2b drawables.
 *   - `icon_audit_onboarding`     : the 10 net-new Onboarding drawables from
 *     Commit 1 of the Onboarding track (`Dv2OnboardingIcons` alphabetical).
 *     10 rows fit comfortably in one golden — under the ~1000px ceiling by a
 *     wide margin. Per architect lock (2026-08-01 Commit 1 clarifications
 *     §4): "не собирать одним листом на 31 иконку … в Commit 1 проверять
 *     только 10 новых onboarding-иконок в 24/48 dp".
 *
 * Reviewer workflow: open the three PNGs side-by-side with
 * `scratchpad/icon-audit-svg-source.html` to A/B-verify each row against
 * the source SVG at 24 px and 48 px.
 */
@org.junit.experimental.categories.Category(phantom.android.testing.PaparazziTestEngine::class)
class PhantomIconAuditSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun icon_audit_a_first_half() {
        val half = (Dv2Icons.size + 1) / 2
        paparazzi.snapshot { ShowcaseIconAudit(Dv2Icons.subList(0, half)) }
    }

    @Test
    fun icon_audit_b_second_half() {
        val half = (Dv2Icons.size + 1) / 2
        paparazzi.snapshot { ShowcaseIconAudit(Dv2Icons.subList(half, Dv2Icons.size)) }
    }

    @Test
    fun icon_audit_onboarding() {
        paparazzi.snapshot { ShowcaseIconAudit(Dv2OnboardingIcons) }
    }

    @Test
    fun icon_audit_pricing() {
        // Round-1 REDLINE on Commit 4 §P2-1: the 3 new tier icons
        // (`tier_plus`, `tier_pro`, `tier_business`) get their own
        // 24 / 48 dp audit golden per the Commit-1 pattern
        // ("проверять только новые иконки в 24/48 dp"). 3 rows fit
        // trivially in one Paparazzi golden.
        paparazzi.snapshot { ShowcaseIconAudit(Dv2PricingIcons) }
    }
}