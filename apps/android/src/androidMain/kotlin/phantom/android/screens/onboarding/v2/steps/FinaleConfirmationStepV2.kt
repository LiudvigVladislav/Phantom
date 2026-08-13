// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.android.ui.designv2.components.PhantomButton

/**
 * FinaleConfirmationStepV2 — post-Permissions confirmation state.
 * Chromeless: no top bar, no dots.
 *
 * Onboarding-stabilization block 2026-08-11 — SIMPLIFIED per
 * architect verdict on the prior dual-key-labels shape ("шоу двух
 * ключей + short IDs + Copy кнопок в основном интерфейсе не нужно").
 * The screen now confirms identity creation without exposing any
 * raw key material or copy affordance. Users who need the
 * cryptographic detail find it later in Profile → Advanced
 * cryptographic details (collapsible).
 *
 * Shape:
 *   - "Identity created" title.
 *   - One-line body copy explaining the identity is on-device.
 *   - Single "Continue" CTA routing to `onComplete()` in the flow.
 *
 * Callers no longer need to pass the raw hexes — the sealed
 * `OnboardingFinalizeStateHolder` still carries them into
 * `IdentityRecord` for downstream consumers (Profile, addressing),
 * but the Finale UI itself does not read them.
 */
@Composable
fun FinaleConfirmationStepV2(
    onContinueClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignV2Tokens.Colors.Background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "Identity created",
            style = TextStyle(
                fontFamily = DesignV2FontDisplay,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                color = DesignV2Tokens.Colors.TextPrimary,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Your identity is created and stored on this device.",
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = DesignV2Tokens.Colors.TextSecondary,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        PhantomButton(
            text = "Continue",
            onClick = onContinueClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
