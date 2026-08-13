// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseInputMatrix

/**
 * PhantomInput matrix golden — 5 rows:
 *   Default (empty) | Focused (has value) | Error | Disabled | Mono (identity key).
 *
 * The Focused row displays with a value present; the actual focus-ring
 * visual is best inspected in the ButtonMatrix (which uses the forced
 * interaction source). Input does not currently expose its interactionSource,
 * so a forced-focus visual would require a wrapper — left for a future
 * component-API extension if the Input focus visual regresses.
 */
class PhantomInputSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun input_matrix() {
        paparazzi.snapshot { ShowcaseInputMatrix() }
    }
}
