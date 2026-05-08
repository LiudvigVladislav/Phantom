// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.R
import phantom.android.di.AppContainer
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono

/**
 * OnboardingScreen — Design/PHANTOM_FULL_COMPOSE.md §09 (3 steps).
 *
 * Phase 0: TermsScreen — legal gate. Once accepted, never shown again.
 * Phase 1: 3-page intro pager.
 *   Step 1 — Welcome: phantom_logo + "PHANTOM" wordmark + "Private by design."
 *   Step 2 — Identity Key: ED25519 fingerprint card + username input
 *   Step 3 — Privacy Mode: Standard / Private / Ghost (Ghost = PRO locked)
 *
 * All steps share an ambient concentric-circle motif in the background and a
 * 3-segment step indicator at the bottom. Swipe is free in both directions
 * so the user can preview steps before committing to a username.
 */
@Composable
fun OnboardingScreen(
    container: AppContainer,
    onComplete: () -> Unit,
) {
    var tosAccepted by remember { mutableStateOf(false) }

    if (!tosAccepted) {
        TermsScreen(onAccept = { tosAccepted = true })
    } else {
        IntroPager(container = container, onComplete = onComplete)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IntroPager(container: AppContainer, onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // Identity & privacy state lives at the pager level so the user can swipe
    // back to Step 2 without losing their typed username.
    var username by remember { mutableStateOf("") }
    var privacyMode by remember { mutableStateOf(PrivacyMode.Standard) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val ctx = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onComplete() }

    val finalize: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onComplete()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WelcomeStep(onContinue = {
                    scope.launch { pagerState.animateScrollToPage(1) }
                })
                1 -> IdentityKeyStep(
                    username = username,
                    onUsernameChange = { username = it; error = null },
                    onContinue = {
                        scope.launch { pagerState.animateScrollToPage(2) }
                    },
                )
                2 -> PrivacyModeStep(
                    selected = privacyMode,
                    onSelect = { privacyMode = it },
                    isLoading = loading,
                    canEnter = username.length >= 3,
                    error = error,
                    onEnter = {
                        if (!loading) {
                            scope.launch {
                                loading = true
                                runOnboarding(container, username, finalize) {
                                    error = it; loading = false
                                }
                            }
                        }
                    },
                )
            }
        }

        // Step indicator — 3 segments. Active = cyan, inactive = border.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(3) { i ->
                val active = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(if (active) 28.dp else 16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (active) CyanAccent
                            else BorderSubtle,
                        ),
                )
            }
        }
    }
}

// ── Step 1 · Welcome ────────────────────────────────────────────────────────

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.55f))

        // PHANTOM logo — phantom_logo.png used AS-IS, height 60dp, no clip.
        Image(
            painter = painterResource(R.drawable.phantom_logo),
            contentDescription = "PHANTOM",
            modifier = Modifier.height(72.dp),
        )

        Spacer(Modifier.height(20.dp))

        // Wordmark — Geist 32px Medium, tight tracking.
        Text(
            text = "PHANTOM",
            color = TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.32).sp,
        )

        Spacer(Modifier.height(14.dp))

        // Tagline — Inter 15px secondary.
        Text(
            text = "Private communication, built to last.",
            color = TextDim,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        // Body value-prop.
        Text(
            text = "Your identity is created on this device. No phone number required to get started.",
            color = PhantomTokens.Colors.TextTertiary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))

        IntroCta(label = "Get started", onClick = onContinue)

        Spacer(Modifier.height(80.dp))
    }
}

// ── Step 2 · Identity Key ───────────────────────────────────────────────────

@Composable
private fun IdentityKeyStep(
    username: String,
    onUsernameChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val valid = username.length >= 3
    val sampleFingerprint = "A4B2  C8D1  E3F7  2A9B  4C6D  8E1F  3A5B  7C2D"
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))

        Text(
            text = "02 · YOUR IDENTITY KEY",
            color = CyanAccent,
            fontSize = 10.sp,
            fontFamily = PhantomFontMono,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.4.sp,
        )

        Spacer(Modifier.height(18.dp))

        Text(
            text = "You are your key.",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 34.sp,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.6).sp,
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = "Your account is a cryptographic key generated on this device. There is no password to leak.",
            color = TextDim,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        // ED25519 fingerprint card — surfaceDeep bg, cyan-tinted border.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PhantomTokens.Colors.SurfaceDeep)
                .border(
                    1.dp,
                    CyanAccent.copy(alpha = 0.22f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "ED25519 · GENERATED ON DEVICE · NEVER TRANSMITTED",
                color = CyanAccent.copy(alpha = 0.85f),
                fontSize = 9.sp,
                fontFamily = PhantomFontMono,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = sampleFingerprint,
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = PhantomFontMono,
                letterSpacing = 0.65.sp,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Generated on your device. Never stored, never transmitted.",
                color = PhantomTokens.Colors.TextTertiary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(14.dp))
            // Copy fingerprint to clipboard — useful for verifying via another
            // channel. Sample fingerprint is shown as illustration; the real
            // key is generated only when "Enter PHANTOM" is tapped.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        CyanAccent.copy(alpha = 0.30f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable {
                        val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clip.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "PHANTOM identity key",
                                sampleFingerprint.replace("  ", " "),
                            ),
                        )
                        scope.launch { snack.showSnackbar("Key copied to clipboard") }
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    phantom.android.ui.PhIconCopy(color = CyanAccent, size = 12.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "COPY KEY",
                        color = CyanAccent,
                        fontSize = 10.sp,
                        fontFamily = PhantomFontMono,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.6.sp,
                    )
                }
            }
        }
        SnackbarHost(snack)

        Spacer(Modifier.height(24.dp))

        // Username input — single field with @ prefix, mono.
        Text(
            text = "Choose your handle",
            color = TextDim,
            fontSize = 12.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(10.dp))
        val fieldInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2)
                .border(
                    1.dp,
                    if (valid) CyanAccent.copy(alpha = 0.30f) else BorderSubtle,
                    RoundedCornerShape(10.dp),
                )
                .clickable(
                    interactionSource = fieldInteraction,
                    indication = null,
                ) { focusRequester.requestFocus() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "@",
                color = CyanAccent,
                fontSize = 17.sp,
                fontFamily = PhantomFontMono,
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = username,
                onValueChange = {
                    onUsernameChange(it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' })
                },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontFamily = PhantomFontMono,
                ),
                cursorBrush = SolidColor(CyanAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { if (valid) { focusManager.clearFocus(); onContinue() } }),
                decorationBox = { inner ->
                    if (username.isEmpty()) {
                        Text(
                            text = "yourname",
                            color = TextDim.copy(alpha = 0.5f),
                            fontSize = 17.sp,
                            fontFamily = PhantomFontMono,
                        )
                    }
                    inner()
                },
            )
        }

        Spacer(Modifier.height(28.dp))

        IntroCta(
            label = "Continue",
            onClick = { focusManager.clearFocus(); onContinue() },
            enabled = valid,
        )

        Spacer(Modifier.height(80.dp))
    }
}

// ── Step 3 · Privacy Mode ───────────────────────────────────────────────────

private enum class PrivacyMode { Standard, Private, Ghost }

@Composable
private fun PrivacyModeStep(
    selected: PrivacyMode,
    onSelect: (PrivacyMode) -> Unit,
    isLoading: Boolean,
    canEnter: Boolean,
    error: String?,
    onEnter: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))

        Text(
            text = "03 · PRIVACY MODE",
            color = CyanAccent,
            fontSize = 10.sp,
            fontFamily = PhantomFontMono,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.4.sp,
        )

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Choose your privacy mode",
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 32.sp,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.5).sp,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Control how visible you are to others on PHANTOM.",
            color = TextDim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        PrivacyModeCard(
            title = "Standard",
            body = "Visible to contacts, searchable by username.",
            active = selected == PrivacyMode.Standard,
            locked = false,
            onClick = { onSelect(PrivacyMode.Standard) },
        )
        Spacer(Modifier.height(12.dp))
        PrivacyModeCard(
            title = "Private",
            body = "Visible only to confirmed contacts.",
            active = selected == PrivacyMode.Private,
            locked = false,
            onClick = { onSelect(PrivacyMode.Private) },
        )
        Spacer(Modifier.height(12.dp))
        PrivacyModeCard(
            title = "Ghost Mode",
            body = "Invisible to all. Receive-only.",
            active = selected == PrivacyMode.Ghost,
            locked = true,
            onClick = { /* PRO-locked, informative not blocking */ },
        )

        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = error,
                color = Danger,
                fontSize = 12.sp,
                fontFamily = PhantomFontMono,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(28.dp))

        IntroCta(
            label = if (isLoading) "Entering…" else "Enter PHANTOM",
            onClick = onEnter,
            enabled = canEnter && !isLoading,
            showLoader = isLoading,
        )

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun PrivacyModeCard(
    title: String,
    body: String,
    active: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (locked) 0.5f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Surface2 else Surface)
            .border(
                width = 1.dp,
                color = if (active) CyanAccent.copy(alpha = 0.35f) else BorderSubtle,
                shape = RoundedCornerShape(12.dp),
            )
            .then(
                if (active) Modifier.drawWithContent {
                    drawContent()
                    drawRect(
                        color = CyanAccent,
                        size = Size(3.dp.toPx(), size.height),
                    )
                } else Modifier,
            )
            .clickable(enabled = !locked, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (locked) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(CyanAccent.copy(alpha = 0.10f))
                                .border(
                                    1.dp,
                                    CyanAccent.copy(alpha = 0.30f),
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "PRO",
                                color = CyanAccent,
                                fontSize = 9.sp,
                                fontFamily = PhantomFontMono,
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = body,
                    color = PhantomTokens.Colors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            if (active) {
                Spacer(Modifier.width(8.dp))
                // FULL_COMPOSE §09 Step 3: 15×15dp check badge top-right.
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(CyanAccent.copy(alpha = 0.12f))
                        .border(1.dp, CyanAccent.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✓",
                        color = CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ── Ambient motif (concentric circles) ──────────────────────────────────────

@Composable
private fun AmbientMotif() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2.2f
        val maxR = minOf(size.width, size.height) * 0.45f
        val ringColor = TextPrimary
        for (i in 1..5) {
            drawCircle(
                color = ringColor,
                radius = maxR * (i / 5f),
                center = Offset(cx, cy),
                style = Stroke(width = 0.75.dp.toPx()),
                alpha = 0.028f,
            )
        }
    }
}

// ── Shared CTA ──────────────────────────────────────────────────────────────

@Composable
private fun IntroCta(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    showLoader: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyanAccent,
            contentColor = BgDeep,
            disabledContainerColor = CyanAccent.copy(alpha = 0.18f),
            disabledContentColor = TextDim.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        if (showLoader) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = BgDeep,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── Step 0 · Terms of Service (legal gate) ──────────────────────────────────

@Composable
private fun TermsScreen(onAccept: () -> Unit) {
    val scrollState = rememberScrollState()
    val readEnough = scrollState.value > scrollState.maxValue * 0.6f || scrollState.maxValue == 0

    val arrowAlpha by animateFloatAsState(
        targetValue = if (scrollState.value > 40) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "arrowFade",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .windowInsetsPadding(WindowInsets.statusBars)
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

        Text(
            text = "Terms of Use",
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-0.5).sp,
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

            TosSection(
                title = "1. End-to-end encrypted",
                body = "Your messages are encrypted on your device and only the recipient can decrypt them. Our servers cannot read your conversations — and we built it that way on purpose.",
            )
            TosSection(
                title = "2. We cannot give away what we do not have",
                body = "PHANTOM is designed so that we have no access to your messages, contacts, or activity. If anyone — including authorities — asks us for your data, we have nothing to provide.",
            )
            TosSection(
                title = "3. You must be 16 or older",
                body = "We require all users to be at least 16. This is for safety and legal compliance reasons.",
            )
            TosSection(
                title = "4. Your keys, your account",
                body = "Your identity is cryptographic keys stored only on your device. We cannot reset, recover, or transfer them. Lose your device without backup — lose your account.",
            )
            TosSection(
                title = "5. No phone number, no email, no real name",
                body = "Pick a username and you are in. We do not link your account to any other identifier.",
            )
            TosSection(
                title = "6. Use PHANTOM responsibly",
                body = "We do not endorse illegal use of the service. Reports of abuse can be sent to abuse@phntm.pro.",
            )
            TosSection(
                title = "7. PHANTOM is in Alpha",
                body = "Things may break. Bugs happen. Updates may change how the app works.",
            )
            TosSection(
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

        Button(
            onClick = onAccept,
            enabled = readEnough,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanAccent,
                contentColor = BgDeep,
                disabledContainerColor = CyanAccent.copy(alpha = 0.15f),
                disabledContentColor = TextDim.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = "ACCEPT & CONTINUE",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.5.sp,
                fontFamily = PhantomFontMono,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TosSection(title: String, body: String, isLast: Boolean = false) {
    Text(
        text = title,
        color = CyanAccent.copy(alpha = 0.85f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        fontFamily = PhantomFontMono,
    )
    Spacer(Modifier.height(7.dp))
    Text(
        text = body,
        color = TextDim,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    )
    if (!isLast) {
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(
            color = Color.White.copy(alpha = 0.04f),
            thickness = 1.dp,
        )
        Spacer(Modifier.height(14.dp))
    }
}

// ── Business logic ──────────────────────────────────────────────────────────

private suspend fun runOnboarding(
    container: AppContainer,
    username: String,
    onComplete: () -> Unit,
    onError: (String) -> Unit,
) {
    runCatching {
        val (record, identityKeyPair) = container.identityManager.createOrLoad(username)
        val dhKeyPair = phantom.core.crypto.DhKeyPair(
            phantom.core.crypto.DhPublicKey(identityKeyPair.publicKey.bytes),
            phantom.core.crypto.DhPrivateKey(identityKeyPair.privateKey.bytes),
        )
        container.initMessaging(record, dhKeyPair)
        onComplete()
    }.onFailure { onError(it.message ?: "Failed to create identity") }
}
