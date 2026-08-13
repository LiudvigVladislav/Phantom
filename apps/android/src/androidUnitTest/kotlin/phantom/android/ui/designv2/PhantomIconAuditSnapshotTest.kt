// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.Dv2Icons
import phantom.android.ui.designv2.showcase.ShowcaseIconAudit

/**
 * Icon side-by-side audit goldens — each of 21 dv2 drawables rendered at 24dp
 * AND 48dp on a Surface background, tinted Cyan.
 *
 * Split into two goldens (A = first half, B = second half) because 21 rows at
 * ~72dp per row overflow Paparazzi's default Pixel 5 viewport (~891dp) AND its
 * ~1000px PNG-height ceiling. An earlier single-golden variant silently
 * clipped the last 9 rows (verified by comparison against the source SVG
 * list). The split ensures every row is fully rendered in one of the two
 * PNGs; ordering matches `Dv2Icons` (alphabetical).
 *
 * Reviewer workflow: open both PNGs side-by-side with
 * `scratchpad/icon-audit-svg-source.html` to A/B-verify each row against
 * the source SVG at 24 px and 48 px.
 */
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
}