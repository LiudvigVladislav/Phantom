// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.screens.onboarding.v2.OnboardingFormStateV2
import phantom.android.screens.onboarding.v2.OnboardingStepDotsV2
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.android.ui.designv2.components.PhantomButton
import phantom.core.transport.PrivacyMode

/**
 * PrivacyLevelStepV2 — Step 3 / "Choose your privacy level".
 *
 * Handoff `Onboarding.dc.html` (STEP 3):
 *   - Title 29 sp / lineHeight 34 sp Geist SemiBold.
 *   - 3-segment slider bar (STANDARD / PRIVATE / GHOST); bars 0..i
 *     are cyan-filled, `i+1..2` grey; label under bar goes cyan iff
 *     it is the selected index.
 *   - Description card for the currently-selected tier. Layout:
 *       - IconBox 48 dp (radius 15) with tier icon at 22 dp.
 *       - Name (20 sp SemiBold) + TagPill.
 *       - Description line (13 sp #8b97a8).
 *       - Hairline divider.
 *       - Bullets (each: 16 dp confirm-icon rondel + text 13 sp).
 *       - If tier is locked (Ghost = Pro), an "Unlock with Phantom
 *         Pro" CTA (cyan bg, dark text) below the bullets. Tapping
 *         opens the Pricing sheet.
 *   - Ghost segment tap: opens Pricing sheet (does NOT change the
 *     stored `privacyMode` — the user cannot silently commit to
 *     Ghost from onboarding without a Pro subscription).
 *   - Standard / Private segment tap: writes the chosen mode into
 *     `formState.privacyMode`.
 *   - StepDots at index 2 above the Continue CTA.
 *
 * The `PrivacyMode` enum lives in `phantom.core.transport` and is
 * consumed by the runtime `TransportManager`. Value mapping:
 *   Standard → PrivacyMode.Standard
 *   Private  → PrivacyMode.Private
 *   Ghost    → PrivacyMode.Ghost   (never chosen from onboarding)
 *
 * Accessibility (round-2 REDLINE P2-6 KDoc sync): each segment is
 * a **Role.Tab** with `contentDescription = shortLabel`
 * ("STANDARD"/"PRIVATE"/"GHOST"), a `selected` semantic reflecting
 * current selection, and a `stateDescription` of "Selected" /
 * "Not selected". The `onClickLabel` on `clickable` provides an
 * additional action description ("Select Standard privacy" /
 * "Select Private privacy" / "Ghost Mode requires Phantom Pro").
 * The Unlock CTA is Role.Button labelled "Unlock with Phantom Pro".
 * Decorative dots on the segment bar, tier icons, and bullet
 * rondels are cleared via `clearAndSetSemantics { }` — screen
 * readers surface the segment / CTA labels only.
 */
@Composable
fun PrivacyLevelStepV2(
    formState: OnboardingFormStateV2,
    dotsIndex: Int,
    onFormStateChange: (OnboardingFormStateV2) -> Unit,
    onContinueClick: () -> Unit,
    onGhostLockClick: () -> Unit,
) {
    val selectedIndex = when (formState.privacyMode) {
        PrivacyMode.Standard -> 0
        PrivacyMode.Private -> 1
        PrivacyMode.Ghost -> 2
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp, bottom = 24.dp),
    ) {
        Text(
            text = "Choose your\nprivacy level",
            color = DesignV2Tokens.Colors.TextPrimary,
            style = TextStyle(
                fontFamily = DesignV2FontDisplay,
                fontSize = 29.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                lineHeight = 34.sp,
            ),
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Slide to set how visible you are. Change it any time in Settings.",
            color = DesignV2Tokens.Colors.TextTertiary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 14.5.sp,
                lineHeight = 22.sp,
            ),
        )

        Spacer(Modifier.height(26.dp))

        PrivacySegmentBar(
            selectedIndex = selectedIndex,
            onSegmentClick = { idx ->
                when (idx) {
                    0 -> onFormStateChange(formState.copy(privacyMode = PrivacyMode.Standard))
                    1 -> onFormStateChange(formState.copy(privacyMode = PrivacyMode.Private))
                    2 -> onGhostLockClick()
                }
            },
        )

        Spacer(Modifier.height(24.dp))

        PrivacyTierCard(
            tier = PRIVACY_TIERS[selectedIndex],
            onUnlockClick = onGhostLockClick,
        )

        Spacer(Modifier.weight(1f))

        OnboardingStepDotsV2(dotsIndex = dotsIndex)
        Spacer(Modifier.height(12.dp))

        PhantomButton(
            text = "Continue",
            onClick = onContinueClick,
            enabled = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Segment bar ──────────────────────────────────────────────────────

@Composable
private fun PrivacySegmentBar(
    selectedIndex: Int,
    onSegmentClick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PRIVACY_TIERS.forEachIndexed { idx, tier ->
            PrivacySegment(
                index = idx,
                label = tier.shortLabel,
                a11yLabel = tier.a11yLabel,
                filled = idx <= selectedIndex,
                current = idx == selectedIndex,
                onClick = { onSegmentClick(idx) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PrivacySegment(
    index: Int,
    label: String,
    a11yLabel: String,
    filled: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Round-1 REDLINE on Commit 4 §P2-2: previous shape wiped the
    // segment label's semantics via `clearAndSetSemantics { }` and
    // relied on `clickable`'s `onClickLabel` for the accessible name.
    // `onClickLabel` describes what the click DOES ("Select Standard
    // privacy"), not what the element IS — so screen readers reported
    // an unnamed Button. Fix: use Role.Tab (a segmented control is a
    // tab bar) + `contentDescription` for the visible name +
    // `stateDescription` so TalkBack announces selected state; the
    // label Text merges into the clickable node's a11y tree so the
    // visual "STANDARD" text is still discoverable.
    Column(
        modifier = modifier
            .clickable(
                role = Role.Tab,
                onClickLabel = a11yLabel,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                selected = current
                stateDescription = if (current) "Selected" else "Not selected"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (filled) DesignV2Tokens.Colors.Cyan
                    else DesignV2Tokens.Colors.Border,
                )
                .clearAndSetSemantics { },
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = label,
            color = if (current) DesignV2Tokens.Colors.Cyan
                    else DesignV2Tokens.Colors.TextTertiary,
            style = TextStyle(
                fontFamily = DesignV2FontMono,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 1.14.sp,
            ),
        )
    }
}

// ── Tier description card ────────────────────────────────────────────

internal data class PrivacyTierV2(
    val name: String,
    val shortLabel: String,   // segment-bar label (uppercased)
    val a11yLabel: String,    // click-action label (screen reader)
    val tag: String,
    val description: String,
    val iconRes: Int,
    val locked: Boolean,
    val bullets: List<String>,
)

internal val PRIVACY_TIERS: List<PrivacyTierV2> = listOf(
    PrivacyTierV2(
        name = "Standard",
        shortLabel = "STANDARD",
        a11yLabel = "Select Standard privacy",
        tag = "BALANCED",
        description = "Visible to your contacts. Read receipts and last seen are on.",
        iconRes = R.drawable.ic_dv2_standard,
        locked = false,
        bullets = listOf(
            "Contacts can see when you are online",
            "Read receipts enabled",
            "Discoverable in Nearby",
        ),
    ),
    PrivacyTierV2(
        name = "Private",
        shortLabel = "PRIVATE",
        a11yLabel = "Select Private privacy",
        tag = "RECOMMENDED",
        description = "No read receipts. Last seen hidden from everyone.",
        iconRes = R.drawable.ic_dv2_privacy,
        locked = false,
        bullets = listOf(
            "Last seen hidden from all",
            "Read receipts disabled",
            "Discoverable in Nearby",
        ),
    ),
    PrivacyTierV2(
        // Round-1 REDLINE on Commit 4 §P1-4: copy MUST match the
        // runtime `phantom.core.transport.PrivacyMode.Ghost` contract
        // (Tor onion only, never silently downgrades — connect fails
        // with a user-visible Failed state if Tor is unreachable).
        // The pre-REDLINE copy ("Completely invisible", "No activity
        // signals at all", "Receive-only mode") promised things the
        // runtime does NOT enforce — Ghost does not block outbound
        // messages, does not silence presence beyond what Tor's own
        // path characteristics guarantee, and is not receive-only.
        name = "Ghost Mode",
        shortLabel = "GHOST",
        a11yLabel = "Ghost Mode requires Phantom Pro",
        tag = "PRO",
        description = "All connections route through Tor onion. Never falls back if Tor is blocked.",
        iconRes = R.drawable.ic_dv2_ghost,
        locked = true,
        bullets = listOf(
            "Every message traverses the Tor network",
            "No silent downgrade to direct WSS or REALITY",
            "Connect fails visibly if Tor cannot bootstrap",
        ),
    ),
)
