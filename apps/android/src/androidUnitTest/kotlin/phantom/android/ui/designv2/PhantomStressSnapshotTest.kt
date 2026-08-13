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
import phantom.android.ui.designv2.showcase.ShowcaseStress

/**
 * Stress golden — narrow width (220 dp) + long strings + fontScale = 2.0.
 *
 * Regressions caught:
 *   - Truncation / wrap regressions when a label exceeds container width.
 *   - Broken layout under accessibility scaling (Compose ignores fontScale
 *     unless propagated via LocalDensity).
 *   - Ring / border misalignment on multi-line Input helper text.
 */
class PhantomStressSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun stress_narrow_long_strings_font_scale_2() {
        paparazzi.snapshot {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = 2.0f,
                ),
            ) {
                ShowcaseStress()
            }
        }
    }
}
