// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseToggleMatrix

/**
 * PhantomToggle matrix golden — 4 cells: on / off / disabled-on / disabled-off.
 */
@org.junit.experimental.categories.Category(phantom.android.testing.PaparazziTestEngine::class)
class PhantomToggleSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun toggle_matrix() {
        paparazzi.snapshot { ShowcaseToggleMatrix() }
    }
}
