// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.components.PhantomBadge
import phantom.android.ui.designv2.showcase.ShowcaseBadgeMatrix

/**
 * PhantomBadge snapshot goldens.
 *
 * `badge_with_count_3` is the F0 compatibility spike golden — proves the
 * Paparazzi + KMP-androidTarget + AGP 9.1.1 + Kotlin 2.2.10 + Compose
 * Multiplatform 1.7.3 toolchain renders a single-cell composable. It stays
 * for F1b snapshot-root normalisation regression coverage.
 *
 * `badge_matrix` is the F2b matrix golden — covers count = 1 / 3 / 12 /
 * 99 / 250 (99+ overflow) plus dotOnly, so every Badge public surface has
 * a review PNG.
 */
class PhantomBadgeSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
    )

    @Test
    fun badge_with_count_3() {
        paparazzi.snapshot {
            Box(
                modifier = Modifier
                    .background(Color(0xFF08090C))
                    .padding(16.dp),
            ) {
                PhantomBadge(count = 3)
            }
        }
    }

    @Test
    fun badge_matrix() {
        paparazzi.snapshot { ShowcaseBadgeMatrix() }
    }
}