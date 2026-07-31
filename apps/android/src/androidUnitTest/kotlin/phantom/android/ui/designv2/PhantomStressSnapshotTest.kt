// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseStressA
import phantom.android.ui.designv2.showcase.ShowcaseStressB

/**
 * Stress goldens — narrow width (220 dp) + long strings + fontScale = 2.0.
 *
 * Split into two halves per round-3 REDLINE P1-3 (2026-08-01): the
 * prior single golden's last row ("Segmented, cramped") landed on
 * y=999 under fontScale=2.0 — content-touched Paparazzi's PNG-height
 * ceiling. Split is on natural row boundaries so each half fits
 * comfortably below the ceiling.
 *
 * Regressions caught:
 *   - Truncation / wrap regressions when a label exceeds container width.
 *   - Broken layout under accessibility scaling (Compose ignores fontScale
 *     unless propagated via LocalDensity).
 *   - Ring / border misalignment on multi-line Input helper text.
 *   - Chip / segmented cramped layout under 2x font scale.
 */
class PhantomStressSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun stress_a_text_rows_font_scale_2() {
        paparazzi.snapshot {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = 2.0f,
                ),
            ) {
                ShowcaseStressA()
            }
        }
    }

    @Test
    fun stress_b_chip_and_segmented_font_scale_2() {
        paparazzi.snapshot {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = 2.0f,
                ),
            ) {
                ShowcaseStressB()
            }
        }
    }
}
