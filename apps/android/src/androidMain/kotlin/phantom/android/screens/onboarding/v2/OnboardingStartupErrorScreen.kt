// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * C6-a round-8 REDLINE §P0 pin — retryable startup error screen.
 *
 * Rendered when `decideStartupRoute` returns
 * [StartupRouteDecision.TransientStartupFailure]. Round-7 shape
 * routed transient operational failures (e.g. one-off
 * initMessaging hiccup) through the SAME repair-required UI
 * used for proven identity corruption + wrote the durable
 * marker, permanently trapping a healthy identity behind the
 * "Identity repair required" screen.
 *
 * Round-8 splits the paths:
 *   - Proven corruption → `OnboardingRepairRequiredScreen`
 *     (unchanged; durable marker + Exit).
 *   - Transient failure → THIS screen (no marker write, no
 *     finalize holder, retry via `onRetry`).
 *
 * Deliberately spartan visual (state-model boundary, not a
 * polished product surface). Downstream commit may harmonise
 * with the app's design system.
 *
 * The screen MUST NOT contain the string "Identity repair
 * required" — that phrase is reserved for the proven-corruption
 * screen. Test
 * `OnboardingV2StartupErrorScreenTest.startup_error_contains_no_repair_text`
 * pins this.
 */
/**
 * @param reason retained purely for diagnostic side-channel
 *   (contentDescription tag, potential logging) — mini-round
 *   §P2 pin: the composable itself renders NO user-facing text
 *   that names the reason. Prior shape rendered "Reason:
 *   identity load failed" etc; architect flagged that as
 *   leaking a technical reason to the user.
 * @param enabled when `false`, the Retry button is disabled.
 *   MainActivity wires this to `!startupInFlight` so a
 *   startup run in flight from a prior Retry cannot be
 *   double-launched by a second tap. Mini-round §P2
 *   behavioural pin.
 * @param onRetry fired on Retry-tap when the button is enabled.
 *   Must be single-flight (guarded by the caller).
 */
@Composable
internal fun OnboardingStartupErrorScreen(
    reason: TransientReason,
    enabled: Boolean = true,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignV2Tokens.Colors.Surface)
            .padding(24.dp)
            .semantics { contentDescription = "OnboardingStartupErrorScreen" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Something went wrong",
                color = DesignV2Tokens.Colors.TextPrimary,
                style = TextStyle(
                    fontFamily = DesignV2FontDisplay,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                // Deliberately vague — the underlying reason is
                // logged with its Throwable stack trace by
                // `applyStartupDecision`; the UI stays on a
                // stable copy that DOES NOT accuse the identity
                // of corruption AND does not surface any
                // internal reason label (mini-round §P2 pin).
                text = "The app could not start. This usually clears up on a retry.",
                color = DesignV2Tokens.Colors.TextSecondary,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontFamily = DesignV2FontBody,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
            )
            Spacer(Modifier.height(24.dp))
            PhantomButton(
                text = "Retry",
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    // The `reason` param is retained on the signature — a
    // downstream commit may surface a hint OR pipe it into a
    // Crashlytics-style report. Left unused inside the render
    // deliberately (mini-round §P2 pin: no user-facing reason
    // text).
    @Suppress("UNUSED_EXPRESSION")
    reason
}
