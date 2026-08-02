// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.screens.onboarding.v2.OnboardingFormStateV2
import phantom.android.screens.onboarding.v2.OnboardingStepDotsV2
import phantom.android.screens.onboarding.v2.UsernameValidationV2
import phantom.android.screens.onboarding.v2.validateUsernameV2
import phantom.android.ui.designv2.DesignV2FontBody
import phantom.android.ui.designv2.DesignV2FontDisplay
import phantom.android.ui.designv2.DesignV2FontMono
import phantom.android.ui.designv2.DesignV2Tokens
import phantom.android.ui.designv2.components.PhantomButton
import phantom.android.ui.designv2.components.PhantomInput

/**
 * IdentityKeyStepV2 — Step 2 / "Your identity key" (Commit 3 real body).
 *
 * Per architect REDLINE 2026-08-01 §C1: this step MUST NOT claim a key
 * exists. The identity is created only in `OnboardingFlowV2.finalize()`
 * after Permissions "Done" fires. Here we render:
 *
 *   - A PREVIEW card that clearly signals the key does not exist yet
 *     (header "ED25519 · WILL BE GENERATED" when username empty,
 *     "ED25519 · READY TO CREATE" when username valid). Status dot
 *     stays neutral grey — never cyan-glow or green-glow. Body area
 *     shows a static placeholder pattern (`— — — — · — — — —`),
 *     clearly non-hex. Footer: "Generated on device when you finish
 *     onboarding."
 *   - NO Copy / Save backup / Regenerate buttons (redline §C1: those
 *     would falsify the "key exists" claim).
 *   - NO warning banner ("Lose this key ..." is meaningful only after
 *     the key exists — moved to the finale confirmation state).
 *   - Username input via PhantomInput's `leadingContent` slot (@ prefix)
 *     + `trailingContent` slot (status icon on valid / invalid) — added
 *     by Commit 1. Validation is FORMAT-ONLY per redline §C2: no
 *     availability check.
 *   - Continue CTA gated by `canAdvance` (username format-valid).
 *
 * Username stored as-typed (case-normalised) per redline §C2 — do NOT
 * strip illegal chars via `.filter` (that would make the InvalidChars
 * state unreachable). Case normalisation happens on write via
 * `.lowercase()`; validation runs against the stored string.
 */
@Composable
fun IdentityKeyStepV2(
    formState: OnboardingFormStateV2,
    dotsIndex: Int,
    onFormStateChange: (OnboardingFormStateV2) -> Unit,
    onContinueClick: () -> Unit,
) {
    val validation = validateUsernameV2(formState.username)
    val canAdvance = validation == UsernameValidationV2.Valid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp, bottom = 24.dp),
    ) {
        // Round-1 REDLINE Commit-3 §P2-2 (handoff Onboarding.dc.html
        // line 99): the source design does NOT include an
        // "02 · YOUR IDENTITY KEY" overline on Step 2 — that was a
        // premature Commit-3 addition. Removed. Title uses handoff's
        // 29 sp / lineHeight 34 sp (Geist SemiBold), matching the
        // How-step title metric.
        Text(
            text = "Your identity key",
            color = DesignV2Tokens.Colors.TextPrimary,
            style = TextStyle(
                fontFamily = DesignV2FontDisplay,
                fontSize = 29.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
                lineHeight = 34.sp,
            ),
        )

        Spacer(Modifier.height(8.dp))

        // Redline §C3 wording — no overclaims. Public key IS shared for
        // verification via QR/contact exchange; only the private half
        // stays on device.
        Text(
            text = "Your private key is generated and stored on this device. Your public key can be shared for verification.",
            color = DesignV2Tokens.Colors.TextTertiary,
            style = TextStyle(
                fontFamily = DesignV2FontBody,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
            ),
        )

        Spacer(Modifier.height(20.dp))

        IdentityKeyPreviewCard(usernameValid = canAdvance)

        Spacer(Modifier.height(24.dp))

        UsernameInputSection(
            username = formState.username,
            validation = validation,
            onUsernameChange = { newValue ->
                // Case-normalise only. DO NOT strip characters — redline §C2.
                onFormStateChange(formState.copy(username = newValue.lowercase()))
            },
        )

        Spacer(Modifier.weight(1f))

        OnboardingStepDotsV2(dotsIndex = dotsIndex)
        Spacer(Modifier.height(12.dp))

        PhantomButton(
            text = "Continue",
            onClick = onContinueClick,
            enabled = canAdvance,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun IdentityKeyPreviewCard(usernameValid: Boolean) {
    // Round-1 REDLINE Commit-3 §P2-2 (handoff Onboarding.dc.html): the
    // key card has a distinct HEADER BAND separated from the body by a
    // hairline. Body area sits in its own padding block below.
    val statusText = if (usernameValid) "ED25519 · READY TO CREATE"
                     else "ED25519 · WILL BE GENERATED"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DesignV2Tokens.Colors.SurfaceInset)
            .border(1.dp, DesignV2Tokens.Colors.Border, RoundedCornerShape(18.dp)),
    ) {
        // Header band — status + icon + neutral dot.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dv2_ed25519_key),
                contentDescription = null,
                tint = DesignV2Tokens.Colors.Cyan,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusText,
                color = DesignV2Tokens.Colors.Cyan,
                style = TextStyle(
                    fontFamily = DesignV2FontMono,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                ),
                modifier = Modifier.weight(1f),
            )
            // Neutral grey dot per redline §C1 — nothing exists yet.
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(DesignV2Tokens.Colors.TextQuaternary),
            )
        }
        // Hairline divider between header and body.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DesignV2Tokens.Colors.Border),
        )
        // Body: placeholder pattern + footer.
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = "— — — —  — — — —\n— — — —  — — — —",
                color = DesignV2Tokens.Colors.TextTertiary,
                style = TextStyle(
                    fontFamily = DesignV2FontMono,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 1.6.sp,
                    lineHeight = 24.sp,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Generated on device when you finish onboarding.",
                color = DesignV2Tokens.Colors.TextQuaternary,
                style = TextStyle(
                    fontFamily = DesignV2FontMono,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.4.sp,
                ),
            )
        }
    }
}

@Composable
private fun UsernameInputSection(
    username: String,
    validation: UsernameValidationV2,
    onUsernameChange: (String) -> Unit,
) {
    Text(
        text = "USERNAME",
        color = DesignV2Tokens.Colors.TextQuaternary,
        style = TextStyle(
            fontFamily = DesignV2FontMono,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 2.2.sp,
        ),
    )
    Spacer(Modifier.height(8.dp))

    val helperText: String
    val isError: Boolean
    val statusIconRes: Int?
    val statusIconTint: androidx.compose.ui.graphics.Color?
    when (validation) {
        UsernameValidationV2.Empty -> {
            helperText = "3–20 characters. Letters, numbers, and underscores."
            isError = false
            statusIconRes = null
            statusIconTint = null
        }
        UsernameValidationV2.Short -> {
            helperText = "A little longer — at least 3 characters."
            isError = true
            statusIconRes = R.drawable.ic_dv2_block
            statusIconTint = DesignV2Tokens.Colors.Error
        }
        UsernameValidationV2.InvalidChars -> {
            helperText = "Only letters, numbers, and underscores."
            isError = true
            statusIconRes = R.drawable.ic_dv2_block
            statusIconTint = DesignV2Tokens.Colors.Error
        }
        UsernameValidationV2.Valid -> {
            // Redline §C2: "available" would be a backend claim we can't
            // make. "format is valid" is what we actually check.
            helperText = "Handle format is valid."
            isError = false
            statusIconRes = R.drawable.ic_dv2_confirm
            statusIconTint = DesignV2Tokens.Colors.Success
        }
    }

    PhantomInput(
        value = username,
        onValueChange = onUsernameChange,
        placeholder = "choose a handle",
        helperText = helperText,
        isError = isError,
        useMonoFont = true,
        keyboardType = KeyboardType.Ascii,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            // Round-1 REDLINE Commit-3 §P2-2 (handoff Onboarding.dc.html
            // line 111ish): handoff draws `@` + a 1 dp × 20 dp vertical
            // divider between the prefix and the text field. Both are
            // purely decorative — the field's own semantics (Role.TextField
            // + error semantic) carry all accessibility content, so the
            // `@` and divider are excluded from independent announcement
            // via clearAndSetSemantics.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics { },
            ) {
                Text(
                    text = "@",
                    color = DesignV2Tokens.Colors.TextQuaternary,
                    style = TextStyle(
                        fontFamily = DesignV2FontMono,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                // Hairline divider (1 dp × 20 dp) — matches handoff.
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(DesignV2Tokens.Colors.Border),
                )
                Spacer(Modifier.width(4.dp))
            }
        },
        trailingContent = if (statusIconRes != null && statusIconTint != null) {
            {
                // Round-1 REDLINE Commit-3 §P2-2 accessibility: the
                // trailing status icon is purely decorative (helper text
                // conveys the validation state to screen readers via
                // the field's error semantic). Exclude from independent
                // announcement.
                Icon(
                    painter = painterResource(statusIconRes),
                    contentDescription = null,
                    tint = statusIconTint,
                    modifier = Modifier
                        .size(18.dp)
                        .clearAndSetSemantics { },
                )
            }
        } else null,
    )
}
