// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseButtonMatrix

/**
 * PhantomButton matrix golden — 3 variants × 4 states.
 *
 * Cells (top-to-bottom in the rendered PNG):
 *   Primary   / normal | focused | pressed | disabled
 *   Secondary / normal | focused | pressed | disabled
 *   Ghost     / normal | focused | pressed | disabled
 *
 * The pressed/focused cells use the `ForcedPressedInteractionSource` /
 * `ForcedFocusedInteractionSource` helpers from the debug showcase so state
 * is deterministic for a single Paparazzi render frame.
 */
class PhantomButtonSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun button_matrix() {
        paparazzi.snapshot { ShowcaseButtonMatrix() }
    }
}
