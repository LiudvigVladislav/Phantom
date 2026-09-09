// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import phantom.android.ui.designv2.showcase.ShowcaseProfileQrKeyCardAdvancedExpanded

/**
 * Final Stabilization Mini-Block 2026-08-11 §P2 — one Profile
 * golden that captures the new architect-exact "Advanced
 * cryptographic details" explainer copy, plus the two labelled
 * `Public key ·` rows and their `Copy public key` buttons. Visual
 * gate for the copy change so a reviewer can pin the surface at a
 * glance.
 *
 * Single default-viewport golden (Pixel 5, fontScale 1.0) — the
 * architect scope-lock caps this block at one Profile golden.
 */
@org.junit.experimental.categories.Category(phantom.android.testing.PaparazziTestEngine::class)
class ProfileQrKeyCardSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun profile_qr_key_card_advanced_expanded() {
        paparazzi.snapshot { ShowcaseProfileQrKeyCardAdvancedExpanded() }
    }
}
