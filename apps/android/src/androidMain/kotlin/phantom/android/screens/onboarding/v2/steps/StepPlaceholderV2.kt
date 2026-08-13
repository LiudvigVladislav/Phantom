// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.screens.onboarding.v2.OnboardingStepDotsV2
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens

/**
 * StepPlaceholderV2 — shared placeholder body for step files whose real
 * implementation lands in a later commit (Identity / Privacy / Permissions
 * / FinaleConfirmation in Commit 2). Not exported outside the `steps`
 * sub-package; each placeholder step file wraps this composable with its
 * commit-specific title/subtitle so a reviewer previewing today sees "this
 * is Step N, real body coming in Commit M" instead of a blank screen.
 *
 * The placeholder does NOT render a Continue button — advancing past a
 * placeholder step would falsify the flow's exercise. It DOES render
 * step dots at the bottom (per round-1 REDLINE P1-3: dots live inside
 * step body, not as a frame-level overlay) at the caller-provided
 * position when [dotsIndex] >= 0; the reviewer sees the correct step
 * indicator even on a not-yet-built body.
 */
@Composable
internal fun StepPlaceholderV2(
    title: String,
    subtitle: String,
    dotsIndex: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "IN PROGRESS",
                color = DesignV2Tokens.Colors.TextQuaternary,
                style = TextStyle(
                    fontFamily = DesignV2FontMono,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 2.4.sp,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                color = DesignV2Tokens.Colors.TextPrimary,
                style = TextStyle(
                    fontFamily = DesignV2FontDisplay,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = subtitle,
                color = DesignV2Tokens.Colors.TextTertiary,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
        // Step dots at the bottom of the placeholder — matches real
        // step-body layout (dots above CTA) so the flow's indicator
        // sits in the same spatial slot regardless of whether the body
        // is a stub or real content. Hidden when dotsIndex < 0.
        OnboardingStepDotsV2(dotsIndex = dotsIndex)
    }
}
