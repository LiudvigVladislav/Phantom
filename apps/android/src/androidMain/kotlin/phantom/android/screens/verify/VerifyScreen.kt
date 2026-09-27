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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.R
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
 *   Mismatch — danger-tinted fingerprint and a return-to-comparison action.
 *
 * Confirmation requires both displayed keys to remain available and unchanged.
 */
private enum class VerifyState { Compare, Verified, Mismatch }

internal fun verificationKeyIsValid(key: String): Boolean =
    key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

internal fun verificationKeysReady(myKey: String, theirKey: String): Boolean =
    verificationKeyIsValid(myKey) && verificationKeyIsValid(theirKey)

internal fun verificationSnapshotStillCurrent(
    displayedMyKey: String,
    displayedTheirKey: String,
    currentMyKey: String,
    currentTheirKey: String,
): Boolean = verificationKeysReady(displayedMyKey, displayedTheirKey) &&
    displayedMyKey.equals(currentMyKey, ignoreCase = true) &&
    displayedTheirKey.equals(currentTheirKey, ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(
    container: AppContainer,
    conversationId: String,
    theirUsername: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Resolve both keys when the screen mounts; unavailable keys cannot be confirmed.
    var myPubKeyHex by remember(conversationId) { mutableStateOf("") }
    var theirPubKeyHex by remember(conversationId) { mutableStateOf("") }
    var initialVerified by remember(conversationId) { mutableStateOf(false) }
    var keysLoaded by remember(conversationId) { mutableStateOf(false) }
    var confirmInFlight by remember(conversationId) { mutableStateOf(false) }
    val snackbarHostState = remember(conversationId) { SnackbarHostState() }

    LaunchedEffect(conversationId) {
        try {
            myPubKeyHex = container.identityRepo.loadIdentity()?.publicKeyHex.orEmpty()
            val conv = container.conversationRepo.getConversation(conversationId)
            theirPubKeyHex = conv?.theirPublicKeyHex.orEmpty()
            initialVerified = conv?.let { it.isVerified && it.identityKeyChangedAt == null } == true &&
                verificationKeysReady(myPubKeyHex, theirPubKeyHex)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            myPubKeyHex = ""
            theirPubKeyHex = ""
            initialVerified = false
        } finally {
            keysLoaded = true
        }
    }

    val keysReady = verificationKeysReady(myPubKeyHex, theirPubKeyHex)

    var verifyState by remember(conversationId, initialVerified) {
        mutableStateOf(if (initialVerified) VerifyState.Verified else VerifyState.Compare)
    }

    Scaffold(
        containerColor = PhantomTokens.Colors.SurfaceDeep,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        text = stringResource(R.string.verify_title, theirUsername),
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
                        VerifyState.Compare -> stringResource(R.string.verify_compare_status, theirUsername.uppercase(java.util.Locale.ROOT))
                        VerifyState.Verified -> stringResource(R.string.verify_confirmed_status)
                        VerifyState.Mismatch -> stringResource(R.string.verify_mismatch_status)
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
                    VerifyState.Compare -> stringResource(R.string.verify_compare_headline)
                    VerifyState.Verified -> stringResource(R.string.verify_confirmed_headline, theirUsername)
                    VerifyState.Mismatch -> stringResource(R.string.verify_mismatch_headline)
                },
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 30.sp,
                letterSpacing = (-0.5).sp,
            )

            Text(
                text = when (verifyState) {
                    VerifyState.Compare -> stringResource(R.string.verify_compare_instruction, theirUsername)
                    VerifyState.Verified -> stringResource(R.string.verify_confirmed_instruction)
                    VerifyState.Mismatch -> stringResource(R.string.verify_mismatch_instruction)
                },
                color = TextDim,
                fontSize = 14.sp,
                lineHeight = 22.sp,
            )

            Spacer(Modifier.height(4.dp))

            FingerprintBlock(
                ownerLabel = stringResource(R.string.verify_your_key),
                name = stringResource(R.string.verify_you),
                publicKeyHex = myPubKeyHex,
                accent = VerifyState.Compare,
                keysLoaded = keysLoaded,
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
                        VerifyState.Compare -> stringResource(R.string.verify_compare_bridge)
                        VerifyState.Verified -> stringResource(R.string.verify_confirmed_bridge)
                        VerifyState.Mismatch -> stringResource(R.string.verify_mismatch_bridge)
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
                ownerLabel = stringResource(R.string.verify_peers_key, theirUsername),
                name = theirUsername,
                publicKeyHex = theirPubKeyHex,
                accent = verifyState,
                keysLoaded = keysLoaded,
            )

            // Read-aloud safety number — same digits on both devices.
            if (keysReady) {
                val safetyNumber = remember(myPubKeyHex, theirPubKeyHex) {
                    phantom.core.crypto.SafetyNumber.compute(
                        myPubKeyHex.lowercase(java.util.Locale.ROOT),
                        theirPubKeyHex.lowercase(java.util.Locale.ROOT),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.verify_read_aloud),
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
                            if (confirmInFlight || !keysReady) return@Button
                            val displayedMyKey = myPubKeyHex
                            val displayedTheirKey = theirPubKeyHex
                            confirmInFlight = true
                            scope.launch {
                                try {
                                    val currentMyKey = container.identityRepo.loadIdentity()?.publicKeyHex.orEmpty()
                                    val currentTheirKey = container.conversationRepo
                                        .getConversation(conversationId)?.theirPublicKeyHex.orEmpty()
                                    if (!verificationSnapshotStillCurrent(
                                            displayedMyKey, displayedTheirKey, currentMyKey, currentTheirKey,
                                        )) {
                                        myPubKeyHex = currentMyKey
                                        theirPubKeyHex = currentTheirKey
                                        verifyState = VerifyState.Compare
                                        snackbarHostState.showSnackbar(context.getString(R.string.verify_keys_changed))
                                        return@launch
                                    }
                                    container.conversationRepo.setVerified(conversationId, true)
                                    container.conversationRepo.clearIdentityKeyChangedAt(conversationId)
                                    verifyState = VerifyState.Verified
                                } catch (_: Exception) {
                                    snackbarHostState.showSnackbar(context.getString(R.string.verify_save_failed))
                                } finally {
                                    confirmInFlight = false
                                }
                            }
                        },
                        enabled = keysReady && !confirmInFlight,
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
                            stringResource(R.string.verify_keys_match),
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
                            stringResource(R.string.verify_keys_differ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                VerifyState.Verified -> phantom.android.ui.PhantomPrimaryButton(
                    label = stringResource(R.string.verify_back_to_chat),
                    onClick = onBack,
                    shape = RoundedCornerShape(8.dp),
                )

                VerifyState.Mismatch -> {
                    Button(
                        onClick = { verifyState = VerifyState.Compare },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = TextPrimary,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Text(stringResource(R.string.verify_back_to_comparison), fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
    keysLoaded: Boolean,
) {
    val fingerprint = remember(publicKeyHex) {
        if (verificationKeyIsValid(publicKeyHex))
            publicKeyHex.substring(0, 32).uppercase(java.util.Locale.ROOT).chunked(4).joinToString("  ")
        else null
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
            text = fingerprint ?: stringResource(
                if (keysLoaded) R.string.verify_key_unavailable else R.string.verify_loading_key,
            ),
            color = hexColor,
            fontSize = 13.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 0.65.sp,
            lineHeight = 22.sp,
        )
    }
}
