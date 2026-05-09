// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.GradientAvatar
import phantom.android.ui.PhIconBack
import phantom.android.ui.PhIconShield
import phantom.android.ui.PhIconShieldCheck
import phantom.android.ui.PhIconAlertCircle
import phantom.android.ui.theme.*

/**
 * VerifyScreen — full-screen identity verification (FULL_COMPOSE Verification).
 *
 * Three states (the same machine that used to live in ContactProfile's
 * verify bottom-sheet, now hosted on its own route):
 *
 *   Compare  — neutral, two FingerprintBlocks stacked with a "Compare ↕"
 *              bridge. CTAs: success-bordered "Keys match — Verified" +
 *              dim danger ghost "Something doesn't match".
 *   Verified — success-tinted block borders, "Verified ✓" bridge, a
 *              single cyan primary "Back to chat" CTA.
 *   Mismatch — danger-tinted block (border 0.25, hex 0.70 alpha, block
 *              opacity 0.70). Bridge "Mismatch ×" danger. CTAs:
 *              "Go back" ghost + "Report" danger ghost.
 *
 * The trust write-through is identical to the previous sheet: on a
 * Compare→Verified transition we mark the conversation verified and
 * clear the identity-key-changed timestamp.
 */
private enum class VerifyState { Compare, Verified, Mismatch }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(
    container: AppContainer,
    conversationId: String,
    theirUsername: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Resolve own + their pubkey hex once when the screen mounts. Both
    // FingerprintBlocks render "loading…" until ready.
    var myPubKeyHex by remember { mutableStateOf("") }
    var theirPubKeyHex by remember { mutableStateOf("") }
    var initialVerified by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        myPubKeyHex = container.identityRepo.loadIdentity()?.publicKeyHex.orEmpty()
        val conv = container.conversationRepo.getConversation(conversationId)
        theirPubKeyHex = conv?.theirPublicKeyHex.orEmpty()
        initialVerified = conv?.isVerified == true
    }

    var verifyState by remember(initialVerified) {
        mutableStateOf(if (initialVerified) VerifyState.Verified else VerifyState.Compare)
    }

    Scaffold(
        containerColor = PhantomTokens.Colors.SurfaceDeep,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        PhIconBack(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Verify @$theirUsername",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.20).sp,
                    )
                }
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header status — overline mono 10sp, colour and icon vary by state.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (verifyState) {
                    VerifyState.Compare -> PhIconShield(color = TextDim, size = 14.dp)
                    VerifyState.Verified -> PhIconShieldCheck(color = Success, size = 14.dp)
                    VerifyState.Mismatch -> PhIconAlertCircle(color = Danger, size = 14.dp)
                }
                Text(
                    text = when (verifyState) {
                        VerifyState.Compare -> "VERIFY @${theirUsername.uppercase()}"
                        VerifyState.Verified -> "IDENTITY CONFIRMED"
                        VerifyState.Mismatch -> "KEYS DO NOT MATCH"
                    },
                    color = when (verifyState) {
                        VerifyState.Verified -> Success
                        VerifyState.Mismatch -> Danger
                        else -> TextDim
                    },
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 2.sp,
                )
            }

            // Display headline — Geist 24sp Light, varies by state.
            Text(
                text = when (verifyState) {
                    VerifyState.Compare -> "Compare these keys side by side."
                    VerifyState.Verified -> "$theirUsername is who they say they are."
                    VerifyState.Mismatch -> "Something doesn't match."
                },
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 30.sp,
                letterSpacing = (-0.5).sp,
            )

            Text(
                text = when (verifyState) {
                    VerifyState.Compare -> "Read all 8 groups out loud, or compare visually in person, with @$theirUsername."
                    VerifyState.Verified -> "Future messages will only arrive on this key. We'll warn you if it changes."
                    VerifyState.Mismatch -> "Do not trust this conversation until you re-verify in person or on a trusted call."
                },
                color = TextDim,
                fontSize = 14.sp,
                lineHeight = 22.sp,
            )

            Spacer(Modifier.height(4.dp))

            FingerprintBlock(
                ownerLabel = "Your key",
                name = "You",
                publicKeyHex = myPubKeyHex,
                accent = VerifyState.Compare,
            )

            // Axis bridge — neutral / success / danger tint per state.
            val bridgeColor = when (verifyState) {
                VerifyState.Verified -> Success.copy(alpha = 0.45f)
                VerifyState.Mismatch -> Danger.copy(alpha = 0.30f)
                else -> TextDim.copy(alpha = 0.15f)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(bridgeColor),
                )
                Text(
                    text = when (verifyState) {
                        VerifyState.Compare -> "Compare ↕"
                        VerifyState.Verified -> "Verified ✓"
                        VerifyState.Mismatch -> "Mismatch ×"
                    },
                    color = when (verifyState) {
                        VerifyState.Verified -> Success
                        VerifyState.Mismatch -> Danger
                        else -> TextDim
                    },
                    fontSize = 9.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.5.sp,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(bridgeColor),
                )
            }

            FingerprintBlock(
                ownerLabel = "@$theirUsername's key",
                name = theirUsername,
                publicKeyHex = theirPubKeyHex,
                accent = verifyState,
            )

            // Read-aloud safety number — same digits on both devices.
            if (myPubKeyHex.isNotEmpty() && theirPubKeyHex.isNotEmpty()) {
                val safetyNumber = remember(myPubKeyHex, theirPubKeyHex) {
                    phantom.core.crypto.SafetyNumber.compute(myPubKeyHex, theirPubKeyHex)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "OR READ ALOUD · 60 DIGITS",
                        color = TextDim,
                        fontSize = 9.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 1.6.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = safetyNumber,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = PhantomFontMono,
                        lineHeight = 18.sp,
                        letterSpacing = 0.3.sp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // CTAs vary by state, full-width.
            when (verifyState) {
                VerifyState.Compare -> Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                container.conversationRepo.setVerified(conversationId, true)
                                container.conversationRepo.clearIdentityKeyChangedAt(conversationId)
                                verifyState = VerifyState.Verified
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Success,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, Success.copy(alpha = 0.55f),
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(
                            "Keys match — Verified",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Button(
                        onClick = { verifyState = VerifyState.Mismatch },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Danger.copy(alpha = 0.65f),
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, Danger.copy(alpha = 0.30f),
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Text(
                            "Something doesn't match",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                VerifyState.Verified -> phantom.android.ui.PhantomPrimaryButton(
                    label = "Back to chat",
                    onClick = onBack,
                    shape = RoundedCornerShape(8.dp),
                )

                VerifyState.Mismatch -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { verifyState = VerifyState.Compare },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = TextPrimary,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Text("Go back", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Button(
                        onClick = { /* TODO: report endpoint */ onBack() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Danger.copy(alpha = 0.55f),
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, Danger.copy(alpha = 0.30f),
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Text("Report", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FingerprintBlock(
    ownerLabel: String,
    name: String,
    publicKeyHex: String,
    accent: VerifyState,
) {
    val fingerprint = remember(publicKeyHex) {
        if (publicKeyHex.length >= 32) {
            publicKeyHex.substring(0, 32).uppercase().chunked(4).joinToString("  ")
        } else {
            "loading…"
        }
    }
    val borderColor = when (accent) {
        VerifyState.Verified -> Success.copy(alpha = 0.25f)
        VerifyState.Mismatch -> Danger.copy(alpha = 0.25f)
        VerifyState.Compare -> Color.Transparent
    }
    val hexColor = when (accent) {
        VerifyState.Mismatch -> Danger.copy(alpha = 0.70f)
        else -> TextPrimary
    }
    // FULL_COMPOSE §12: only the fingerprint hex carries the danger tint
    // on mismatch — the surrounding card (avatar, name, label) stays at
    // full opacity so it remains readable. Earlier `.alpha(0.70f)` on the
    // whole Column dimmed everything and felt overcautious.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgDeep)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(name = name, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.16).sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = ownerLabel,
                    color = TextDim.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 1.5.sp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = fingerprint,
            color = hexColor,
            fontSize = 13.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 0.65.sp,
            lineHeight = 22.sp,
        )
    }
}
