// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseFilterChipMatrix

/**
 * PhantomFilterChip matrix golden — unselected / selected + a typical row.
 */
class PhantomFilterChipSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun filter_chip_matrix() {
        paparazzi.snapshot { ShowcaseFilterChipMatrix() }
    }
}
