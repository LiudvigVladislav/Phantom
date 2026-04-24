package phantom.android.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer
import phantom.android.ui.theme.*

@Composable
fun OnboardingScreen(
    container: AppContainer,
    onComplete: () -> Unit,
) {
    var tosAccepted by remember { mutableStateOf(false) }

    if (!tosAccepted) {
        TermsScreen(onAccept = { tosAccepted = true })
    } else {
        IdentityScreen(container = container, onComplete = onComplete)
    }
}

// ── Step 1: Terms of Service ──────────────────────────────────────────────────

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

        // Wordmark
        Text(
            text = "PHANTOM",
            color = CyanAccent.copy(alpha = 0.55f),
            fontSize = 10.sp,
            letterSpacing = 5.sp,
            fontFamily = FontFamily.Monospace,
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

        // Scrollable ToS card
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
            TosSection(
                title = "1. Acceptable Use",
                body = "You may use PHANTOM only for lawful purposes. You must not use the service to transmit, store, or distribute content that is illegal under applicable law, including but not limited to: child sexual abuse material (CSAM), content that facilitates violence or terrorism, fraud, harassment, or spam.",
            )
            TosSection(
                title = "2. No Illegal Content",
                body = "Transmission of material that violates local, national, or international law is strictly prohibited. This includes but is not limited to: sale of controlled substances, distribution of malware, identity theft, and financial fraud. Violations will be reported to appropriate law enforcement authorities.",
            )
            TosSection(
                title = "3. Privacy & Encryption",
                body = "PHANTOM uses end-to-end encryption. The service operator cannot read your messages. However, connection metadata (timestamps, pseudonymous key identifiers, message sizes) may be logged by the relay service for security and legal compliance purposes. This data may be disclosed in response to valid legal process.",
            )
            TosSection(
                title = "4. Report & Abuse",
                body = "Use the in-app report function to flag abuse. Reports are forwarded to the relay operator and may result in blocking of the reported identity. We cooperate with law enforcement on valid requests.",
            )
            TosSection(
                title = "5. Liability",
                body = "PHANTOM is provided as-is. The operator is not responsible for content transmitted by users. You are solely responsible for your use of the service and any consequences thereof.",
            )
            TosSection(
                title = "6. Changes",
                body = "These terms may be updated. Continued use of the service after changes constitutes acceptance of the revised terms.",
                isLast = true,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "By tapping \"Accept & Continue\" you confirm that you are at least 16 years old and agree to these terms.",
                color = TextDim.copy(alpha = 0.6f),
                fontSize = 11.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Scroll hint with drawn arrow
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
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(6.dp))
                // Drawn chevron-down arrow — no material icon
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
                fontFamily = FontFamily.Monospace,
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
        fontFamily = FontFamily.Monospace,
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

// ── Step 2: Identity creation ─────────────────────────────────────────────────

@Composable
private fun IdentityScreen(container: AppContainer, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        runCatching { focusRequester.requestFocus() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onComplete() }

    val proceed: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onComplete()
        }
    }

    val valid = username.length >= 3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(52.dp))

        // Wordmark
        Text(
            text = "PHANTOM",
            color = CyanAccent.copy(alpha = 0.55f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 5.sp,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(40.dp))

        // Hero tagline
        Text(
            text = "Your presence,\nknown to no one.",
            color = TextPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 42.sp,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.8).sp,
        )

        Spacer(Modifier.height(28.dp))

        // Trust badge pills
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.wrapContentWidth(),
        ) {
            FeaturePill("E2E ENCRYPTED")
            FeaturePill("NO PHONE")
            FeaturePill("ZERO METADATA")
        }

        Spacer(Modifier.height(52.dp))

        Text(
            text = "Choose your identity",
            color = TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.5.sp,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(14.dp))

        // Username input field — Surface2 box with CyanAccent left border
        val fieldInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                .background(Surface2)
                .border(
                    width = 1.dp,
                    color = if (valid) CyanAccent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp),
                )
                // 2dp CyanAccent left border via inner padding trick with a drawn line
                .drawLeftBorder(color = CyanAccent.copy(alpha = if (valid) 0.9f else 0.35f), width = 2.dp)
                // Entire pill area forwards taps to the text field — avoids the problem
                // where the tight BasicTextField hit box misses taps on the "@" prefix
                // or on the padding zone around it.
                .clickable(
                    interactionSource = fieldInteraction,
                    indication = null,
                ) { focusRequester.requestFocus() }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "@",
                color = CyanAccent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = username,
                onValueChange = {
                    username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }
                    error = null
                },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(CyanAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (valid && !loading) {
                        scope.launch {
                            loading = true
                            runOnboarding(container, username, proceed) { error = it; loading = false }
                        }
                    }
                }),
                decorationBox = { inner ->
                    if (username.isEmpty()) {
                        Text(
                            text = "username",
                            color = TextDim.copy(alpha = 0.5f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Light,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    inner()
                },
            )
        }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = error!!,
                color = Danger,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                if (valid && !loading) {
                    scope.launch {
                        loading = true
                        runOnboarding(container, username, proceed) { error = it; loading = false }
                    }
                }
            },
            enabled = valid && !loading,
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
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = BgDeep,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = "BEGIN",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Reusable sub-components ───────────────────────────────────────────────────

@Composable
private fun FeaturePill(label: String) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = CyanAccent.copy(alpha = 0.25f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = CyanAccent.copy(alpha = 0.55f),
            fontSize = 8.sp,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Draws a solid left border stripe directly on the Modifier draw layer,
 * avoiding the need for a nested Box or extra composable.
 */
private fun Modifier.drawLeftBorder(color: Color, width: Dp): Modifier =
    this.drawWithContent {
        drawContent()
        drawRect(
            color = color,
            size = Size(width.toPx(), size.height),
        )
    }

// ── Business logic (unchanged) ────────────────────────────────────────────────

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
