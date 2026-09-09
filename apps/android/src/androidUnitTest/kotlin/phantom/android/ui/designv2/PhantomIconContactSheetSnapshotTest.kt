// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseIconContactSheet

/**
 * Icon contact sheet golden — all 21 `ic_dv2_*` vector drawables rendered
 * in a 4-column grid on a Surface background.
 *
 * Purpose: catch regressions in the SVG → Android VectorDrawable conversion
 * pipeline (clipping, blank paths, primitive-to-pathData translation errors,
 * viewport / stroke-width mistakes) with a single review PNG per revision.
 */
@org.junit.experimental.categories.Category(phantom.android.testing.PaparazziTestEngine::class)
class PhantomIconContactSheetSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun icon_contact_sheet() {
        paparazzi.snapshot { ShowcaseIconContactSheet() }
    }
}
