// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.R
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * Panel + panel-owned subcomposables for the onboarding pricing
 * sheet — extracted from `OnboardingPricingSheetV2.kt` in round-3
 * REDLINE §P2-5 file-split so each file stays under the 500-line
 * CLAUDE.md limit.
 *
 * File organisation:
 *   - [Panel]           — bottom-anchored container + 82 %
 *                          max-height cap + top-shadow + drag-down
 *                          state + touch-absorb pointer input.
 *   - [GrabStrip]       — 38 × 4 dp handle + drag/tap surface for
 *                          dismiss (round-3 §P1-2 sync float state;
 *                          §P1-3 upward-drag resistance).
 *   - [HeaderRow]       — `PHANTOM PREMIUM` label + close-X button.
 *   - [CloseXButton]    — 30 dp circular X.
 *   - [TierList]        — scrollable stack of `PricingTierCard`s
 *                          (round-3 §P1-3 `Int.MAX_VALUE` initial
 *                          scroll for the showcase golden).
 *   - [Footer]          — "Cancel any time" bottom text.
 */

@Composable
internal fun Panel(
    onDismiss: () -> Unit,
    onCtaSelected: (String) -> Unit,
    scrollToBottomForShowcase: Boolean = false,
) {
    // BoxWithConstraints so the panel can cap its own height at
    // 82 % of the parent — matches the handoff `_sheetBox` shape.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxPanelHeight = maxHeight * 0.82f
        val density = LocalDensity.current
        val dismissThresholdPx = with(density) { 90.dp.toPx() }
        val upwardResistanceCapPx = with(density) { (-60).dp.toPx() }

        // Round-3 REDLINE on Commit 4 §P1-2: drag offset is now a
        // SYNCHRONOUS `Float` state (was an `Animatable` whose
        // `snapTo` had to be launched per-delta from a coroutine
        // scope — that path had a race between the fire-and-forget
        // `launch { snapTo(...) }` calls and the `onDragEnd` read of
        // `dragOffsetY.value`; a fast swipe could finish before the
        // last `snapTo` landed, dropping accumulated drag).
        // Synchronous `mutableFloatStateOf` updates are applied on
        // the pointer-event thread synchronously — no per-delta
        // coroutine hop — so `onDragEnd` sees the true accumulated
        // offset. Rebound-to-zero is the ONE animated segment, and
        // that IS launched from a coroutine (with `Animatable`) —
        // its scope is only entered on release, after the drag has
        // fully accumulated into the synchronous state.
        var dragOffsetY by remember { mutableFloatStateOf(0f) }
        // Separate Animatable for the rebound animation — driven
        // only from `onDragEnd` / `onDragCancel` (release paths),
        // its `snapshotFlow`-linked value copies back into
        // `dragOffsetY` while animating so the panel's `.offset`
        // observes the animated value.
        val reboundAnimatable = remember { Animatable(0f) }
        val reboundScope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxPanelHeight)
                .offset { IntOffset(x = 0, y = dragOffsetY.toInt()) }
                // Round-3 REDLINE on Commit 4 §P1-3 handoff feature 1:
                // panel top-shadow per handoff line 337 (`boxShadow:
                // '0 -24px 60px rgba(0,0,0,.55)'`). Compose
                // `Modifier.shadow(elevation)` renders a shadow around
                // the shape — because the panel is bottom-anchored,
                // only the top edge is visible above the panel, which
                // matches the handoff's directional "-24 px top"
                // spec.
                //
                // Round-4 REDLINE on Commit 4 §P1-4 disclaimer: the
                // `shadow(12.dp)` value is a Compose-side APPROXIMATION
                // of the handoff's CSS `0 -24px 60px rgba(0,0,0,.55)`.
                // Compose's elevation-based Material shadow uses the
                // system's ambient + spot light model and cannot
                // reproduce the exact CSS box-shadow shape (offset
                // vector + blur radius + colour). 12 dp elevation
                // gives a visually similar top-edge heaviness at
                // Pixel-5 density but is not a pixel-exact match.
                // A true CSS-shape shadow would require a custom
                // `drawWithContent` pass painting a linear-gradient
                // strip above the panel top edge — deferred until an
                // explicit follow-up if the visual approximation is
                // insufficient.
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    ambientColor = Color(0xFF000000),
                    spotColor = Color(0xFF000000),
                )
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Color(0xFF0D1015))
                .border(
                    width = 1.dp,
                    color = Color(0xFF232A35),
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                )
                // Round-4 REDLINE on Commit 4 §P1-3 (corrected): panel
                // MUST absorb any tap not routed to a child interactive
                // element (backdrop dismiss must NOT fire from a tap
                // on the panel body).
                //
                // Round-3 shape observed on `PointerEventPass.Initial`
                // WITHOUT consuming — that was wrong. Not consuming
                // means the pointer event continues to propagate on
                // Main + Final to sibling pointerInputs at lower Z
                // (the backdrop's clickable), so tapping panel body
                // fired backdrop dismiss. The round-3 CTA test also
                // failed because coordinate hit-testing on CTA passed
                // through to the backdrop's clickable via the same
                // path.
                //
                // Correct shape: consume on `PointerEventPass.Main`.
                // The Main pass propagates DESCENDANT → ANCESTOR, so
                // child clickables (CTA buttons, close-X, grab-strip)
                // get the event FIRST and can consume it via their
                // own `detectTapGestures`. When our panel-level Main
                // handler runs, either (a) a child already consumed,
                // in which case our `event.changes.forEach { it.consume() }`
                // is a no-op safety, or (b) no child handled the tap
                // and we consume — preventing propagation to the
                // sibling backdrop's clickable at lower Z.
                //
                // The panel Box itself has NO OnClick semantic action
                // (asserted by
                // `pricing_sheet_panel_body_exposes_no_interactive_semantics_of_its_own`)
                // — pointerInput does not add one, unlike `.clickable`.
                .pointerInput(Unit) {
                    // `detectTapGestures { }` with a no-op callback is
                    // the idiomatic Compose absorber: it installs a
                    // gesture detector that awaits `awaitFirstDown` on
                    // Main, consumes the down change, and never fires
                    // the noop OnTap — this reliably absorbs single-
                    // finger taps that would otherwise propagate to
                    // the sibling backdrop's clickable. Adds NO
                    // semantic OnClick action (asserted by
                    // `pricing_sheet_panel_body_exposes_no_interactive_semantics_of_its_own`)
                    // because pointerInput does not merge semantics
                    // the way `.clickable` does. The round-3 raw
                    // `awaitPointerEvent(Initial)` shape didn't
                    // consume, so sibling backdrop still received
                    // the down; the round-4 Main-pass consumption
                    // didn't help under Robolectric coordinate
                    // injection because our `event.changes.forEach {
                    // it.consume() }` ran after the injection loop
                    // had already dispatched the event to backdrop's
                    // detectTapGestures. detectTapGestures at THIS
                    // level integrates with Compose's gesture-
                    // arbitration properly.
                    detectTapGestures { /* noop — modal panel absorber */ }
                }
                // Round-9 REDLINE §P1: safe-bottom navigation-bar
                // inset so the sheet's Footer + CTAs don't sit
                // under the 3-button / gesture nav on Android 15
                // edge-to-edge.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            GrabStrip(
                onDismiss = onDismiss,
                dragOffsetPx = dragOffsetY,
                updateDragOffsetPx = { dragOffsetY = it },
                dismissThresholdPx = dismissThresholdPx,
                upwardResistanceCapPx = upwardResistanceCapPx,
                reboundAnimatable = reboundAnimatable,
                reboundScope = reboundScope,
            )
            HeaderRow(onDismiss = onDismiss)
            Spacer(Modifier.height(14.dp))
            TierList(
                onCtaSelected = onCtaSelected,
                scrollToBottomForShowcase = scrollToBottomForShowcase,
            )
            Footer()
        }
    }
}

@Composable
private fun HeaderRow(onDismiss: () -> Unit) {
    // "PHANTOM PREMIUM" monospace label on the left, circular close-X
    // on the right (per handoff — replaces the pre-REDLINE
    // "UPGRADE PHANTOM" wording).
    // Round-7 REDLINE on Commit 5 §P1 pin: title constrained to
    // single line + ellipsis so at narrow width × fontScale 2.0
    // it can't wrap into the close button's space. Prior shape
    // let the letter-spaced title wrap and the button visually
    // collided with the second line.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Round-9 REDLINE §P1: allow wrap on 2 lines. The two-
        // word "PHANTOM PREMIUM" breaks naturally between words.
        // User's fontScale respected — no cap.
        Text(
            text = "PHANTOM PREMIUM",
            color = DesignV2Tokens.Colors.TextQuaternary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 11.sp,
                letterSpacing = 3.08.sp,   // ~0.28 em at 11 sp
                fontWeight = FontWeight.Normal,
            ),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            softWrap = true,
        )
        CloseXButton(onClick = onDismiss)
    }
}

@Composable
private fun CloseXButton(onClick: () -> Unit) {
    // 30 × 30 circle with an X path; per handoff. Rendered via the
    // existing `ic_dv2_close.xml` vector-drawable at 14 dp.
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0x0DFFFFFF)) // rgba(255,255,255,.05)
            .clickable(
                role = Role.Button,
                onClickLabel = "Close pricing",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_dv2_close),
            contentDescription = null,
            tint = Color(0xFFA4ACBA),
            modifier = Modifier
                .size(14.dp)
                .clearAndSetSemantics { },
        )
    }
}

@Composable
private fun ColumnScope.TierList(
    onCtaSelected: (String) -> Unit,
    scrollToBottomForShowcase: Boolean = false,
) {
    // `.weight(fill = false)` inside the enclosing Panel Column so
    // the tier list takes only the space it needs (up to the panel
    // cap) and the Footer sticks below it. `weight` here is
    // `ColumnScope.weight` — the reason this composable is a
    // ColumnScope extension.
    //
    // Round-2 REDLINE on Commit 4 §P1-3 (round-2 fix, take 2):
    // showcase-only "scroll to bottom" for the Paparazzi golden.
    // The round-1 shape called `scrollTo(scrollState.maxValue)`
    // inside `LaunchedEffect(Unit)`, which ran BEFORE layout
    // measured content — `maxValue` was still 0, so the scroll
    // was a no-op and the golden still showed the top. The
    // round-2 fix used `snapshotFlow { maxValue }.first { it > 0 }`
    // to wait for layout — but Paparazzi's one-frame render does
    // not advance its coroutine dispatcher between composition and
    // capture, so the LaunchedEffect body suspended at `.first { … }`
    // and never resumed before the snapshot fired.
    //
    // Take 2: initialise the ScrollState with `Int.MAX_VALUE` when
    // scroll-to-bottom is requested. `ScrollState.scrollTo` clamps
    // to `[0, maxValue]` on first layout, so a huge initial value
    // resolves to `maxValue` synchronously as soon as the content
    // is measured — no coroutine dispatch needed. This is the
    // Paparazzi-safe idiom.
    val scrollState = rememberScrollState(
        initial = if (scrollToBottomForShowcase) Int.MAX_VALUE else 0,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(scrollState),
    ) {
        PRICING_TIERS.forEach { tier ->
            PricingTierCard(
                tier = tier,
                onCtaClick = { onCtaSelected(tier.cta) },
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun Footer() {
    // "Cancel any time. No data sold, ever." per handoff — small
    // centered text under the tier stack.
    Text(
        text = "Cancel any time. No data sold, ever.",
        color = DesignV2Tokens.Colors.TextQuaternary,
        style = TextStyle(
            fontFamily = DesignV2FontBody,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
