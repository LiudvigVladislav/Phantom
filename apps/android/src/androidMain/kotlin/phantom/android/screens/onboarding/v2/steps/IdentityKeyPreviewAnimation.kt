// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.withContext

/**
 * C6-b — Step 2 key-preview card animation controller.
 *
 * Contract sheet:
 * `docs/tracks/android-onboarding/c6-b-key-preview-animation.md`.
 *
 * Canonical design source
 * (`design_handoff_phantom_messenger/Onboarding.dc.html` L24, L104-L129,
 * L247-L268, L362-L391) drives ONLY the pure-motion values (timings,
 * easings, animated properties). All product-logic decisions come
 * from the architect's C6-b contract, NOT from canonical.
 *
 * This file houses the animation-driving composable, phase enum, and
 * timing constants. Pure rendering + deterministic decorative-glyph
 * generators live in
 * [IdentityKeyPreviewCardFrame] / `IdentityKeyPreviewCardFrame.kt`
 * (split in Round-1 mini-round 2026-08-10, P2-1).
 *
 * NO crypto surface reachable from here.
 */

/**
 * Pure state model — the three animation phases.
 *
 * Kept as `enum` (not `sealed`) — no per-instance state needed;
 * `Animatable<Float>` carries progress separately.
 */
internal enum class KeyPreviewAnimationPhase {
    Idle,
    Running,
    Terminal,
}

/**
 * Total shuffle duration — 32 glyphs × 45 ms per tick.
 * Source: `Onboarding.dc.html` L254 + L258.
 */
internal const val KEY_PREVIEW_SHUFFLE_TOTAL_MILLIS: Int = 1440

/**
 * `phFadeUp` mount animation — source `Onboarding.dc.html` L24 +
 * L376. Card fades in with a subtle upward slide as it mounts.
 * Delay is expressed via the tween's `delayMillis` (not a plain
 * `kotlinx.coroutines.delay`) so it respects `MotionDurationScale`
 * — under scale=0 the whole animation (delay + duration) collapses
 * to 0 ms (mini-round P1 fix).
 */
internal const val KEY_PREVIEW_MOUNT_DELAY_MILLIS: Int = 160
internal const val KEY_PREVIEW_MOUNT_DURATION_MILLIS: Int = 500
private val PhFadeUpEasing: CubicBezierEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

// ── Public composable ──────────────────────────────────────────────

/**
 * Animated key-preview card. Renders three phases:
 *   - Idle: neutral card, dash placeholder, "WILL BE GENERATED".
 *   - Running: shuffle in flight, cyan-fading border, decorative
 *     bullet-family glyphs (`·`, `•`, `○`, `.`) settling.
 *   - Terminal: cyan border, white masked-bullet text, green dot,
 *     "READY TO CREATE".
 *
 * State restoration (contract §2.3): if a Terminal render (OR a
 * mid-flight render that had started shuffling) preceded process
 * death, the composable jumps DIRECTLY to Terminal state on restore
 * — NO re-shuffle. This is preserved via a `rememberSaveable`
 * "shuffle-started" flag; the `Animatable` initialises at 1f when
 * that flag is true. Cancellation (`usernameValid: true → false`)
 * resets the flag AND snaps progress to 0, so a subsequent
 * `false → true` flip within the same composition lifecycle DOES
 * trigger a fresh shuffle.
 *
 * Under `LocalInspectionMode = true` (Paparazzi Showcase wrap /
 * IDE preview / any static render context that opts in), the
 * composable skips both the mount fade and the shuffle animation,
 * rendering the phase implied by `usernameValid`. Paparazzi
 * 2.0.0-alpha05 does NOT automatically set `LocalInspectionMode`;
 * the Identity showcase entries wrap their content in
 * `CompositionLocalProvider(LocalInspectionMode provides true)`
 * explicitly (P1-4 fix from Round-1).
 *
 * Reduced motion (contract §2.4): both the mount and the shuffle
 * animations are `Animatable.animateTo(tween(...))` calls, whose
 * durations (including tween `delayMillis`) collapse to 0 ms under
 * `MotionDurationScale.scaleFactor = 0f`. Production callers pass
 * `testMotionDurationScale = null` and inherit the ambient scale.
 *
 * @param testMotionDurationScale optional `MotionDurationScale`
 *   injected into BOTH the mount and shuffle animations'
 *   coroutine contexts. Tests pass an explicit zero-scale to
 *   exercise the reduced-motion path — mount + shuffle collapse
 *   on first dispatch → card renders visibly Terminal on the
 *   first frame (contract §2.4; mini-round P1 fix). Applies to
 *   mount too now: prior shape used a plain
 *   `kotlinx.coroutines.delay(160)` which does NOT respect
 *   `MotionDurationScale` and left the card at alpha=0 for the
 *   first 160 ms under scale=0.
 * @param testMountProgressSink optional per-composition sink that
 *   receives the current `mountAnimatable.value` on every
 *   recomposition. Used ONLY by
 *   `IdentityKeyPreviewAnimatedCardTest.reduced_motion_…` to
 *   deterministically assert that mount progress has reached 1f
 *   on the very first frame under `MotionDurationScale(0f)` —
 *   a check that pixel-level capture cannot cleanly express
 *   under Robolectric (vector-drawable NPE on `View.draw` +
 *   `captureToImage` idle-wait timeout under a frozen clock).
 *   Production callers pass `null`.
 */
@Composable
internal fun IdentityKeyPreviewAnimatedCard(
    usernameValid: Boolean,
    testMotionDurationScale: MotionDurationScale? = null,
    testMountProgressSink: ((Float) -> Unit)? = null,
) {
    val inspection = LocalInspectionMode.current

    if (inspection) {
        // Static render context (Paparazzi Showcase wrap /
        // IDE preview): skip Animatable entirely, render the
        // terminal-vs-Idle steady state at full mount opacity so
        // goldens stay deterministic.
        val phase = if (usernameValid) KeyPreviewAnimationPhase.Terminal
                    else KeyPreviewAnimationPhase.Idle
        val progress = if (usernameValid) 1f else 0f
        IdentityKeyPreviewCardFrame(
            progress = progress,
            phase = phase,
            mountProgress = 1f,
        )
        return
    }

    // Live render — subscribe to the Animatable-driven progress.

    // Preserves the "shuffle-started-in-current-validity-session"
    // flag across process death so recreation jumps to Terminal
    // instead of re-shuffling (contract §2.3, Round-1 P1-1 fix).
    var shuffleStartedInCurrentValidity by rememberSaveable {
        mutableStateOf(false)
    }
    val progressAnimatable = remember {
        // Recreation with shuffle previously started (mid-flight
        // OR terminal): jump straight to 1f (contract §2.3 —
        // "no partial re-shuffle").
        Animatable(if (shuffleStartedInCurrentValidity) 1f else 0f)
    }

    LaunchedEffect(usernameValid) {
        if (usernameValid) {
            if (progressAnimatable.value < 1f) {
                shuffleStartedInCurrentValidity = true
                val shuffleBlock: suspend () -> Unit = {
                    progressAnimatable.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = KEY_PREVIEW_SHUFFLE_TOTAL_MILLIS,
                            easing = LinearEasing,
                        ),
                    )
                }
                if (testMotionDurationScale != null) {
                    withContext(testMotionDurationScale) { shuffleBlock() }
                } else {
                    shuffleBlock()
                }
            }
            // If already at 1f (Terminal survived recreation OR
            // a prior valid period), do nothing — contract §2.3.
        } else {
            // Username invalid: cancel any in-flight shuffle and
            // snap progress back to Idle. Also reset the saved
            // "started" flag so a subsequent valid flip triggers
            // a fresh shuffle (contract §2.3 in-composition rule).
            shuffleStartedInCurrentValidity = false
            progressAnimatable.snapTo(0f)
        }
    }

    // Mount fade — canonical `phFadeUp` (500 ms + 160 ms delay,
    // cubic-bezier(.22, 1, .36, 1)). Source L376 + L24. Runs once
    // per composition-instance mount (skipped in inspection mode).
    //
    // Mini-round P1 fix: delay expressed via `tween(delayMillis)`,
    // NOT a plain `kotlinx.coroutines.delay(160)`. And the animate
    // block runs under the same optional `MotionDurationScale` as
    // the shuffle — so under scale=0 the WHOLE mount (delay +
    // duration) collapses to 0 ms and the card is visibly present
    // on the first frame.
    val mountAnimatable = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val mountBlock: suspend () -> Unit = {
            mountAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = KEY_PREVIEW_MOUNT_DURATION_MILLIS,
                    delayMillis = KEY_PREVIEW_MOUNT_DELAY_MILLIS,
                    easing = PhFadeUpEasing,
                ),
            )
        }
        if (testMotionDurationScale != null) {
            withContext(testMotionDurationScale) { mountBlock() }
        } else {
            mountBlock()
        }
    }

    val progress = progressAnimatable.value
    val phase: KeyPreviewAnimationPhase = when {
        !usernameValid -> KeyPreviewAnimationPhase.Idle
        progress >= 1f -> KeyPreviewAnimationPhase.Terminal
        else           -> KeyPreviewAnimationPhase.Running
    }

    val mountValue = mountAnimatable.value
    if (testMountProgressSink != null) {
        SideEffect { testMountProgressSink.invoke(mountValue) }
    }

    IdentityKeyPreviewCardFrame(
        progress = progress,
        phase = phase,
        mountProgress = mountValue,
    )
}

// ── Pure phase reducer ─────────────────────────────────────────────

/**
 * Pure reducer used by [KeyPreviewAnimationPhaseTest] to pin the
 * state transition contract without a Compose runtime. The Compose
 * composable arrives at the same transitions via [Animatable] +
 * `LaunchedEffect` — the reducer is a test seam, not the production
 * driver.
 */
@VisibleForTesting
internal fun nextKeyPreviewPhase(
    current: KeyPreviewAnimationPhase,
    usernameValid: Boolean,
    progressReachedOne: Boolean,
): KeyPreviewAnimationPhase = when {
    !usernameValid -> KeyPreviewAnimationPhase.Idle
    current == KeyPreviewAnimationPhase.Terminal -> KeyPreviewAnimationPhase.Terminal
    progressReachedOne -> KeyPreviewAnimationPhase.Terminal
    else -> KeyPreviewAnimationPhase.Running
}
