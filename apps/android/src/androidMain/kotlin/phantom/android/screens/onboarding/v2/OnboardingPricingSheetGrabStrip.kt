// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Grab strip subcomposable extracted from
 * `OnboardingPricingSheetPanel.kt` in round-6 REDLINE §P2 to keep
 * the panel file under 500 lines. Owns the drag path + rebound
 * animation Job lifecycle; the panel-level `Panel` composable
 * threads shared state (`dragOffsetPx`, `updateDragOffsetPx`,
 * `reboundAnimatable`, `reboundScope`) into this file's public
 * `GrabStrip` composable.
 *
 * See `OnboardingPricingSheetDragGeometry.kt` for the pure-function
 * mapping + animation-spec constants and their unit tests.
 */


@Composable
internal fun GrabStrip(
    onDismiss: () -> Unit,
    dragOffsetPx: Float = 0f,
    updateDragOffsetPx: (Float) -> Unit = {},
    dismissThresholdPx: Float = 0f,
    upwardResistanceCapPx: Float = 0f,
    reboundAnimatable: Animatable<Float, *>? = null,
    reboundScope: kotlinx.coroutines.CoroutineScope? = null,
) {
    // Grab strip acts as BOTH a tap-to-dismiss surface (Role.Button
    // + "Close pricing" label) AND a drag surface. A straight tap
    // fires the clickable; a vertical drag fires the pointer-input
    // path below.
    //
    // Round-3 REDLINE on Commit 4 §P1-2: drag callbacks now write
    // to a SYNCHRONOUS `Float` state (parent's
    // `updateDragOffsetPx`) — no per-delta coroutine launches, no
    // race between accumulated writes and `onDragEnd`'s read of
    // the current value. The rebound-to-zero animation on release
    // IS launched from `reboundScope` — but that's a single, guarded
    // launch that reads the final synchronous value at the moment
    // of release.
    //
    // Round-3 REDLINE on Commit 4 §P1-3 handoff feature 3: upward
    // drag now has resistance per handoff `_sheetDrag` line 320
    // (`panel.style.transform='translateY('+(raw>=0?raw:Math.max(-60,raw*.35))+'px)'`).
    // Above zero the panel tracks the finger; below zero the offset
    // is `max(-60, raw * 0.35)` — dampened at 35 % and capped at
    // -60 px so the user gets a small elastic pull without the
    // panel launching off the top.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Close pricing",
                onClick = onDismiss,
            )
            .then(
                if (reboundAnimatable != null && reboundScope != null) {
                    // Round-4 REDLINE on Commit 4 §P1-1: drag is now
                    // tracked in a GESTURE-LOCAL `rawDragY` reset in
                    // `onDragStart`. The pre-round-4 shape captured
                    // the parent's `dragOffsetPx` value in the
                    // pointerInput closure at composition time
                    // (initialised to 0f) — because `dragOffsetPx`
                    // wasn't a pointerInput key, the closure never
                    // saw parameter updates. Every delta computed
                    // `raw = 0f + dy`, so the threshold was almost
                    // impossible to hit and `onDragEnd`'s
                    // `dragOffsetPx` read was also stale.
                    //
                    // The fix mirrors the handoff `_sheetDrag` exactly:
                    // `rawDragY` is the RAW cumulative path from
                    // gesture start (equivalent to `ev.clientY -
                    // startY` in the handoff), and the damping /
                    // upward cap applies to `rawDragY` on the way
                    // out, not to the running visual offset. Dismiss
                    // decision on release reads `rawDragY` — the
                    // authoritative accumulated distance the finger
                    // has travelled.
                    Modifier.pointerInput(dismissThresholdPx, upwardResistanceCapPx) {
                        var rawDragY = 0f
                        // Round-6 REDLINE on Commit 4 §P1-1: store the
                        // rebound Job so a new gesture can cancel a
                        // still-running rebound before starting to
                        // write its own offsets. Handoff `_sheetDrag`
                        // does this via `panel.style.transition =
                        // 'none'` on pointerdown — same intent,
                        // Kotlin idiom: `reboundJob?.cancel()`.
                        // Without this, a fast second touch during
                        // the tail of a rebound animation would have
                        // BOTH the animation coroutine AND the drag
                        // callback writing `updateDragOffsetPx()`
                        // concurrently — visible as a stutter or
                        // panel snap.
                        var reboundJob: kotlinx.coroutines.Job? = null
                        detectVerticalDragGestures(
                            onDragStart = {
                                reboundJob?.cancel()
                                reboundJob = null
                                rawDragY = 0f
                            },
                            onVerticalDrag = { _, dy ->
                                rawDragY += dy
                                updateDragOffsetPx(
                                    pricingSheetVisualOffsetPx(rawDragY, upwardResistanceCapPx),
                                )
                            },
                            onDragEnd = {
                                if (shouldDismissOnRelease(rawDragY, dismissThresholdPx)) {
                                    onDismiss()
                                } else {
                                    val startFrom = pricingSheetVisualOffsetPx(
                                        rawDragY, upwardResistanceCapPx,
                                    )
                                    rawDragY = 0f
                                    reboundJob = reboundScope.launch {
                                        reboundAnimatable.snapTo(startFrom)
                                        // Round-6 REDLINE §P1-2:
                                        // handoff `_sheetDrag` line
                                        // 323 spec is
                                        //   `transition: 'transform
                                        //   .26s cubic-bezier(.2,.85,.25,1)'`
                                        // Compose equivalent: 260 ms
                                        // tween with matching cubic-
                                        // Bezier easing (pre-round-6
                                        // used the default `spring`
                                        // which had different feel
                                        // and duration).
                                        reboundAnimatable.animateTo(
                                            targetValue = 0f,
                                            animationSpec = ReboundAnimationSpec,
                                        ) {
                                            updateDragOffsetPx(value)
                                        }
                                    }
                                }
                            },
                            onDragCancel = {
                                val startFrom = pricingSheetVisualOffsetPx(
                                    rawDragY, upwardResistanceCapPx,
                                )
                                rawDragY = 0f
                                reboundJob = reboundScope.launch {
                                    reboundAnimatable.snapTo(startFrom)
                                    reboundAnimatable.animateTo(
                                        targetValue = 0f,
                                        animationSpec = ReboundAnimationSpec,
                                    ) {
                                        updateDragOffsetPx(value)
                                    }
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF2A2F38))
                .clearAndSetSemantics { },
        )
    }
}
