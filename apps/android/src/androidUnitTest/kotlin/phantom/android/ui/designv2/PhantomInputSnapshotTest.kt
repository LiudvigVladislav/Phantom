// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseInputMatrix
import phantom.android.ui.designv2.showcase.ShowcaseInputSlotsMatrix

/**
 * PhantomInput golden coverage.
 *
 * `input_matrix` — the pre-existing 5-row matrix (default / focused /
 * error / disabled / mono). Locks the original single-column layout
 * and error-state chrome.
 *
 * `input_slots_matrix` — the 4-row slots matrix added in Commit 1 of the
 * Onboarding track. Covers the new `leadingContent` + `trailingContent`
 * generic slots:
 *   - Leading only (`@` prefix)
 *   - Trailing only (success status icon)
 *   - Both slots (Onboarding's username field shape)
 *   - Error state with custom trailingContent (verifies trailing slot
 *     suppresses the built-in `(!)` alert icon — the caller owns the
 *     trailing pixel)
 *
 * The Focused row in the original matrix displays with a value present; the
 * actual focus-ring visual is best inspected in the ButtonMatrix (which uses
 * the forced interaction source). Input does not currently expose its
 * interactionSource, so a forced-focus visual would require a wrapper —
 * left for a future component-API extension if the Input focus visual
 * regresses.
 */
class PhantomInputSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun input_matrix() {
        paparazzi.snapshot { ShowcaseInputMatrix() }
    }

    @Test
    fun input_slots_matrix() {
        paparazzi.snapshot { ShowcaseInputSlotsMatrix() }
    }
}
