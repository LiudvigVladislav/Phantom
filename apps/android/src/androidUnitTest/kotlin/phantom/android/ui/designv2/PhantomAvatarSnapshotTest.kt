// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseAvatarMatrix

/**
 * PhantomAvatar matrix golden — 4 sizes (32/40/48/64dp) × 3 badge combos
 * (plain, online, online+verified) + a flat-fill row.
 */
@org.junit.experimental.categories.Category(phantom.android.testing.PaparazziTestEngine::class)
class PhantomAvatarSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun avatar_matrix() {
        paparazzi.snapshot { ShowcaseAvatarMatrix() }
    }
}
