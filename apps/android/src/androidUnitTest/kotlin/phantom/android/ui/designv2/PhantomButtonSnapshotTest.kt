// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseButtonMatrixA
import phantom.android.ui.designv2.showcase.ShowcaseButtonMatrixB

/**
 * PhantomButton matrix goldens — 3 variants × 4 states, SPLIT into two
 * PNGs per round-3 REDLINE P1-3 (2026-08-01).
 *
 * Prior single-golden shape (`ShowcaseButtonMatrix`) produced 12 cells
 * stacked vertically at ~72 dp each — total content ~864 dp. Paparazzi's
 * Pixel 5 device profile viewport is ~891 dp tall, and the PNG encoder
 * caps output at ~1000 px. The last 2 cells (`Ghost / pressed`,
 * `Ghost / disabled`) were silently clipped from the golden;
 * independent pixel-scan confirmed active pixels landed on y=999.
 *
 * Split:
 *   button_matrix_a — Primary + Secondary (8 cells).
 *   button_matrix_b — Ghost                (4 cells).
 *
 * Same pattern the icon-audit test already uses for its 21 → (A + B)
 * split. The pressed / focused cells inside each half use the
 * `ForcedPressedInteractionSource` / `ForcedFocusedInteractionSource`
 * helpers from the debug showcase so state is deterministic for a
 * single Paparazzi render frame.
 */
@org.junit.experimental.categories.Category(phantom.android.testing.PaparazziTestEngine::class)
class PhantomButtonSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun button_matrix_a_primary_secondary() {
        paparazzi.snapshot { ShowcaseButtonMatrixA() }
    }

    @Test
    fun button_matrix_b_ghost() {
        paparazzi.snapshot { ShowcaseButtonMatrixB() }
    }
}
