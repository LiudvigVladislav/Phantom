// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.theme.BgDeep
import phantom.android.ui.theme.CyanAccent
import phantom.android.ui.theme.PhantomFontMono
import phantom.android.ui.theme.Surface
import phantom.android.ui.theme.TextDim
import phantom.android.ui.theme.TextPrimary

/**
 * TermsScreenV2 — pre-flow legal gate. PRESERVED verbatim from the
 * pre-existing [phantom.android.screens.onboarding.OnboardingScreen]'s
 * private `TermsScreen` composable (2026-06-XX, F0 baseline).
 *
 * Verbatim carry-over is intentional per architect ask A1 (2026-08-01):
 * Terms is not part of the redesign — it's a pre-flow legal gate whose
 * visual and copy must not drift between the old and new flows during the
 * co-existence period (Commits 2..4). Both flows render the same Terms
 * until the atomic entry-point switch in Commit 5, at which point the old
 * private TermsScreen becomes dead code (removal is a follow-up, not this
 * PR's scope).
 *
 * References the pre-existing PhantomTokens/PhantomTheme aliases
 * (BgDeep, CyanAccent, TextPrimary, TextDim, Surface, PhantomFontMono),
 * NOT DesignV2Tokens — this is the "preserved" gate, not a redesigned
 * surface. The DesignV2 palette lock (2026-07-31) explicitly permits
 * PhantomTokens usage in un-migrated screens.
 */
/**
 * TermsScreenV2 caller entry: [topInset] is passed by both runtime
 * ([OnboardingScreenV2]) and showcase.
 *   - Runtime computes it via
 *     `WindowInsets.statusBars.asPaddingValues().calculateTopPadding()`.
 *   - Showcase passes a hard-coded 24.dp so the Paparazzi golden
 *     matches the on-device layout (Paparazzi returns 0 for
 *     `WindowInsets.statusBars`).
 *
 * Round-3 REDLINE P2-4 fix: the previous shape hard-coded
 * `.windowInsetsPadding(WindowInsets.statusBars)` which reads zero
 * insets in Paparazzi — the golden diverged from device by the
 * status-bar height.
 */
@Composable
fun TermsScreenV2(
    onAccept: () -> Unit,
    topInset: Dp = 0.dp,
) {
    val scrollState = rememberScrollState()
    // P2-6 REDLINE: the "scroll to read all terms" hint promises the user
    // reads the entire list before the CTA enables; the pre-existing
    // 60 % threshold falsified that promise. `readEnough` now requires
    // scrollState.value to reach maxValue (the very bottom). The
    // `scrollState.maxValue == 0` branch stays so a viewport tall enough
    // to show the whole card without scrolling (rare on target devices,
    // possible on tablets) still enables Accept.
    val readEnough = scrollState.value >= scrollState.maxValue || scrollState.maxValue == 0

    val arrowAlpha by animateFloatAsState(
        targetValue = if (scrollState.value > 40) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "arrowFade",
    )

    // Round-9 REDLINE §P1: `windowInsetsPadding(navigationBars)` on
    // the outer Column keeps the ACCEPT & CONTINUE button above
    // the system navigation bar on Android 15 edge-to-edge.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .padding(top = topInset)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        Text(
            text = "PHANTOM",
            color = CyanAccent.copy(alpha = 0.55f),
            fontSize = 10.sp,
            letterSpacing = 5.sp,
            fontFamily = PhantomFontMono,
            fontWeight = FontWeight.Normal,
        )

        Spacer(Modifier.height(28.dp))

        // letterSpacing = 0.sp per round-2 REDLINE P2-5: the UI contract
        // (2026-08-01) prohibits negative tracking on any DesignV2 text.
        // Preserved-verbatim Terms carried a legacy -0.5 sp value from
        // the pre-DesignV2 baseline; zeroing it here brings the pre-flow
        // gate in line with the contract without any visual regression
        // meaningful at 26 sp Light.
        Text(
            text = "Terms of Use",
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 0.sp,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Please read before continuing",
            color = TextDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
        )

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(16.dp),
                )
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Text(
                text = "Welcome to PHANTOM",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "A quick read before you start:",
                color = TextDim,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))

            TosSectionV2(
                title = "1. End-to-end encrypted",
                body = "Your messages are encrypted on your device and only the recipient can decrypt them. Our servers cannot read your conversations — and we built it that way on purpose.",
            )
            TosSectionV2(
                title = "2. We cannot give away what we do not have",
                body = "PHANTOM is designed so that we have no access to your messages, contacts, or activity. If anyone — including authorities — asks us for your data, we have nothing to provide.",
            )
            TosSectionV2(
                title = "3. You must be 16 or older",
                body = "We require all users to be at least 16. This is for safety and legal compliance reasons.",
            )
            TosSectionV2(
                title = "4. Your keys, your account",
                body = "Your identity is cryptographic keys stored only on your device. We cannot reset, recover, or transfer them. Lose your device without backup — lose your account.",
            )
            TosSectionV2(
                title = "5. No phone number, no email, no real name",
                body = "Pick a username and you are in. We do not link your account to any other identifier.",
            )
            TosSectionV2(
                title = "6. Use PHANTOM responsibly",
                body = "We do not endorse illegal use of the service. Reports of abuse can be sent to abuse@phntm.pro.",
            )
            TosSectionV2(
                title = "7. PHANTOM is in Alpha",
                body = "Things may break. Bugs happen. Updates may change how the app works.",
            )
            TosSectionV2(
                title = "8. We collect the bare minimum",
                body = "Only your public key and the IP you connect from — needed to route messages. None of it is logged or stored long-term. See the full Privacy Policy below for details.",
                isLast = true,
            )
            Spacer(Modifier.height(20.dp))

            val linkContext = LocalContext.current
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Read full Terms",
                    color = CyanAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = PhantomFontMono,
                    modifier = Modifier.clickable {
                        linkContext.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://phntm.pro/terms"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
                Text(
                    text = "Privacy Policy",
                    color = CyanAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = PhantomFontMono,
                    modifier = Modifier.clickable {
                        linkContext.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://phntm.pro/privacy"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "By tapping \"Accept & Continue\" you confirm that you are at least 16 years old and agree to these terms and the Privacy Policy.",
                color = TextDim.copy(alpha = 0.6f),
                fontSize = 11.sp,
                lineHeight = 17.sp,
                fontFamily = PhantomFontMono,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (!readEnough) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(arrowAlpha),
            ) {
                Text(
                    text = "scroll to read all terms",
                    color = TextDim.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontFamily = PhantomFontMono,
                )
                Spacer(Modifier.height(6.dp))
                Canvas(modifier = Modifier.size(16.dp, 8.dp)) {
                    val w = size.width
                    val h = size.height
                    val stroke = Stroke(
                        width = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(w / 2f, h)
                        lineTo(w, 0f)
                    }
                    drawPath(
                        path = path,
                        color = TextDim.copy(alpha = 0.4f),
                        style = stroke,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // Round-7 REDLINE on Commit 5 §P1 pin: the button's fixed
        // 54-dp height + wrap-friendly label caused
        // `ACCEPT & CONTINUE` to clip to `ACCEPT &` at fontScale
        // 2.0 on narrow phones. `defaultMinSize(minHeight = 54.dp)`
        // lets the button grow when the label is taller (so the
        // second-line text remains readable when it actually
        // wraps), and `singleLine = true` on the Text prevents
        // mid-word breaks — the letter-spaced monospaced label is
        // designed to render one line only.
        Button(
            onClick = onAccept,
            enabled = readEnough,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanAccent,
                contentColor = BgDeep,
                disabledContainerColor = CyanAccent.copy(alpha = 0.15f),
                disabledContentColor = TextDim.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            // Round-9 REDLINE §P1: allow the button label to wrap
            // to 2 lines at extreme fontScale rather than capping
            // the user's requested scale. `ACCEPT & CONTINUE`
            // breaks naturally between the two words. Button's
            // `defaultMinSize(minHeight = 54.dp)` (round-7 pin)
            // grows the button vertically to fit the wrapped
            // label — full text preserved, no ellipsis, user's
            // fontScale respected.
            Text(
                text = "ACCEPT & CONTINUE",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.5.sp,
                fontFamily = PhantomFontMono,
                maxLines = 2,
                softWrap = true,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TosSectionV2(title: String, body: String, isLast: Boolean = false) {
    Column {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            color = TextDim,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        if (!isLast) Spacer(Modifier.height(14.dp))
    }
}
