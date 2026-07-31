// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseIconAudit

/**
 * Icon side-by-side audit golden — each of 21 dv2 drawables rendered at 24dp
 * AND 48dp on a Surface background, tinted Cyan.
 *
 * Purpose: catch SVG → Android VectorDrawable conversion regressions that
 * only visibly manifest at one scale. Written after the F2b-1 review caught
 * `nearby` and `reaction` bugs at 24dp — the 48dp column would have flagged
 * both immediately (sub-unit stroke widths and mis-scaled arc radii amplify
 * visibly at 2× size).
 */
class PhantomIconAuditSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun icon_audit_24_and_48dp() {
        paparazzi.snapshot { ShowcaseIconAudit() }
    }
}