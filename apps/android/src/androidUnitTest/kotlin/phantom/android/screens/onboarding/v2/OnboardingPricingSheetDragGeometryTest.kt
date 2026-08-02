// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM unit tests for the pricing sheet drag geometry helpers.
 *
 * These pin the exact numeric behaviour of the damping formula +
 * dismiss threshold check without needing Robolectric, Paparazzi,
 * or a Compose test rule — so the round-5 REDLINE §P1 fix (rebound
 * animation start point read via the same mapping the drag path
 * uses) can be validated in milliseconds and any future change to
 * the constants will trip these before shipping.
 */
class OnboardingPricingSheetDragGeometryTest {

    // Pixel values matching the panel's Pixel-5 density:
    //   90 dp × 2.75 (Pixel 5 xxhdpi) ≈ 247.5 px
    //   -60 dp × 2.75 ≈ -165 px
    // Test uses cleaner constants to make expected values obvious.
    private val dismissThresholdPx = 100f
    private val upwardResistanceCapPx = -60f

    // ── pricingSheetVisualOffsetPx ────────────────────────────────

    @Test
    fun offset_at_zero_raw_is_zero() {
        assertEquals(
            0f,
            pricingSheetVisualOffsetPx(rawDragY = 0f, upwardResistanceCapPx),
        )
    }

    @Test
    fun offset_tracks_downward_drag_one_to_one() {
        assertEquals(1f,   pricingSheetVisualOffsetPx(1f,   upwardResistanceCapPx))
        assertEquals(50f,  pricingSheetVisualOffsetPx(50f,  upwardResistanceCapPx))
        assertEquals(150f, pricingSheetVisualOffsetPx(150f, upwardResistanceCapPx))
        assertEquals(999f, pricingSheetVisualOffsetPx(999f, upwardResistanceCapPx))
    }

    @Test
    fun offset_applies_35_percent_damping_to_upward_drag() {
        // Just above the cap: -100 * 0.35 = -35, which is > -60, so
        // dampened value applies (not the cap).
        assertEquals(-35f, pricingSheetVisualOffsetPx(-100f, upwardResistanceCapPx))
        assertEquals(-14f, pricingSheetVisualOffsetPx(-40f,  upwardResistanceCapPx))
        assertEquals(-3.5f, pricingSheetVisualOffsetPx(-10f, upwardResistanceCapPx))
    }

    @Test
    fun offset_clamps_to_upward_cap_when_raw_would_exceed_it() {
        // -200 * 0.35 = -70 which is beyond -60 cap → cap wins.
        assertEquals(-60f, pricingSheetVisualOffsetPx(-200f, upwardResistanceCapPx))
        assertEquals(-60f, pricingSheetVisualOffsetPx(-500f, upwardResistanceCapPx))
        assertEquals(-60f, pricingSheetVisualOffsetPx(-9999f, upwardResistanceCapPx))
    }

    @Test
    fun offset_cap_boundary_is_inclusive_of_the_cap_value() {
        // -60/0.35 ≈ -171.43 — at exactly this raw, dampened equals cap.
        val boundary = upwardResistanceCapPx / 0.35f
        assertEquals(-60f, pricingSheetVisualOffsetPx(boundary, upwardResistanceCapPx))
    }

    @Test
    fun offset_survives_different_cap_values() {
        // Formula is `max(cap, raw * 0.35)` — deeper caps allow more
        // upward pull before clamping.
        assertEquals(-30f, pricingSheetVisualOffsetPx(-100f, upwardResistanceCapPx = -30f))
        assertEquals(-35f, pricingSheetVisualOffsetPx(-100f, upwardResistanceCapPx = -120f))
    }

    // ── shouldDismissOnRelease ────────────────────────────────────

    @Test
    fun dismiss_false_below_threshold() {
        assertFalse(shouldDismissOnRelease(rawDragY = 0f,   dismissThresholdPx))
        assertFalse(shouldDismissOnRelease(rawDragY = 50f,  dismissThresholdPx))
        assertFalse(shouldDismissOnRelease(rawDragY = 99f,  dismissThresholdPx))
        assertFalse(shouldDismissOnRelease(rawDragY = 100f, dismissThresholdPx))
    }

    @Test
    fun dismiss_true_strictly_above_threshold() {
        assertTrue(shouldDismissOnRelease(rawDragY = 100.01f, dismissThresholdPx))
        assertTrue(shouldDismissOnRelease(rawDragY = 150f,    dismissThresholdPx))
        assertTrue(shouldDismissOnRelease(rawDragY = 500f,    dismissThresholdPx))
    }

    @Test
    fun dismiss_false_on_negative_raw_regardless_of_magnitude() {
        // Upward drag never dismisses — dismiss threshold only
        // applies to downward path.
        assertFalse(shouldDismissOnRelease(rawDragY = -50f,   dismissThresholdPx))
        assertFalse(shouldDismissOnRelease(rawDragY = -1000f, dismissThresholdPx))
    }

    // ── Integration: mapping + dismiss decision consistency ──────

    @Test
    fun rebound_start_matches_the_last_painted_frame() {
        // The whole point of extracting these helpers: on release,
        // the rebound animation MUST start from the same visual
        // offset the last on-drag frame painted. That value is
        // `pricingSheetVisualOffsetPx(rawDragY, cap)`.
        val rawAtRelease = -80f
        val expectedVisualAtRelease = pricingSheetVisualOffsetPx(
            rawAtRelease, upwardResistanceCapPx,
        )
        // -80 * 0.35 = -28 — dampened, not capped.
        assertEquals(-28f, expectedVisualAtRelease)

        val downwardBelowThreshold = 40f
        assertEquals(
            40f,
            pricingSheetVisualOffsetPx(downwardBelowThreshold, upwardResistanceCapPx),
        )
        assertFalse(shouldDismissOnRelease(downwardBelowThreshold, dismissThresholdPx))
        // Rebound animation from y=40 → 0.

        val downwardAboveThreshold = 200f
        assertTrue(shouldDismissOnRelease(downwardAboveThreshold, dismissThresholdPx))
        // No rebound — sheet dismisses from current visual y=200.
    }
}
