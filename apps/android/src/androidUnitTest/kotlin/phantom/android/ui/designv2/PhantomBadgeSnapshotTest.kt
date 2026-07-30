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

/**
 * F0 compatibility spike — Paparazzi + KMP-androidTarget + AGP 9.1.1 +
 * Kotlin 2.2.10 + Compose Multiplatform 1.7.3.
 *
 * If this test passes and a golden PNG lands under
 * `apps/android/src/androidUnitTest/snapshots/`, the toolchain works and
 * F1/F2 can proceed. If it fails, capture the stack trace and stop —
 * do NOT self-switch to a different snapshot tool (per Vladislav's F0
 * directive).
 *
 * The golden itself only proves the pixel output is stable — visual
 * accuracy against the handoff spec requires a separate Vladislav
 * sign-off.
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
}