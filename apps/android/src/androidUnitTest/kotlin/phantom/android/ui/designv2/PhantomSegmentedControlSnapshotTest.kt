// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseSegmentedMatrix

/**
 * PhantomSegmentedControl matrix golden — three examples:
 *   2 segments, index=0 (List/Grid)
 *   3 segments, index=1 (All/Incoming/Missed)
 *   3 segments, index=2 (Standard/Private/Ghost)
 */
class PhantomSegmentedControlSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun segmented_matrix() {
        paparazzi.snapshot { ShowcaseSegmentedMatrix() }
    }
}
