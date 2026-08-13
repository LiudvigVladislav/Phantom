// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import kotlin.math.max

/**
 * Pure-function drag geometry for the pricing sheet panel.
 *
 * Extracted from `OnboardingPricingSheetPanel` in round-5 REDLINE
 * §P1: the rebound path in `onDragEnd` / `onDragCancel` was reading
 * `startFrom = dragOffsetPx` from a stale pointer-input closure
 * capture (the classic captured-parameter bug that already bit us
 * in round-3 §P1-1 for the accumulation path). The fix was to
 * recompute the visual offset from the gesture-local `rawDragY`
 * on the release side too — but doing it inline duplicated the
 * damping / upward-cap arithmetic in three places and made the
 * drag callback hard to read. Extracting the mapping + the
 * dismiss decision here lets:
 *
 *   - the panel's drag callback consume both via one-line calls,
 *   - the release path start its rebound animation from the SAME
 *     computed visual offset the last on-drag frame painted, and
 *   - both are covered by a pure JVM unit test suite
 *     (`OnboardingPricingSheetDragGeometryTest`) with no Robolectric
 *     dependency — the tests run in milliseconds and pin the
 *     damping constants + dismiss threshold behaviour exactly.
 *
 * Constants live at the call site (`OnboardingPricingSheetPanel`)
 * so this file stays a pure input→output mapping with no policy
 * beyond the two-branch damping formula.
 */

/**
 * Map the raw gesture-local drag distance to the visual offset
 * the panel actually renders at.
 *
 * Positive [rawDragY] (downward drag) tracks the finger 1:1.
 *
 * Negative [rawDragY] (upward drag) is dampened at 35 % — matches
 * the handoff `_sheetDrag` idiom `raw >= 0 ? raw : Math.max(-60,
 * raw * 0.35)` — and clamped to no lower than [upwardResistanceCapPx]
 * (typically the pixel-equivalent of -60 dp) so the elastic pull
 * doesn't launch the panel off the top.
 */
internal fun pricingSheetVisualOffsetPx(
    rawDragY: Float,
    upwardResistanceCapPx: Float,
): Float =
    if (rawDragY >= 0f) {
        rawDragY
    } else {
        max(upwardResistanceCapPx, rawDragY * 0.35f)
    }

/**
 * Rebound animation spec used by the panel drag path when releasing
 * below the dismiss threshold or cancelling a gesture.
 *
 * Round-6 REDLINE §P1-2: matches handoff `_sheetDrag` line 323
 * (`transition: 'transform .26s cubic-bezier(.2,.85,.25,1)'`)
 * exactly — 260 ms tween with the cubic-Bezier easing curve
 * `(0.2, 0.85, 0.25, 1.0)`. Pre-round-6 used
 * `Animatable.animateTo(0f)` without a spec which fell back to
 * Compose's default `spring()` — different duration, different
 * feel.
 */
internal val ReboundAnimationSpec: TweenSpec<Float> = tween(
    durationMillis = 260,
    easing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1.0f),
)

/**
 * Should a release at accumulated [rawDragY] trigger dismiss?
 *
 * Dismisses iff the RAW downward path exceeds [dismissThresholdPx]
 * — matches the handoff `_sheetDrag` threshold check
 * (`ev.clientY - startY > 90`). Negative or below-threshold
 * releases return false → caller runs the rebound animation.
 */
internal fun shouldDismissOnRelease(
    rawDragY: Float,
    dismissThresholdPx: Float,
): Boolean = rawDragY > dismissThresholdPx
