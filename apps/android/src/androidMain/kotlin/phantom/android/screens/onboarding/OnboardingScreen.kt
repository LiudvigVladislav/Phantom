package phantom.android.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(52.dp))

        Text(
            text = "PHANTOM",
            color = TextDim,
            fontSize = 11.sp,
            letterSpacing = 4.sp,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Terms of Use",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Please read before continuing",
            color = TextDim,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(20.dp))

        // Scrollable ToS card
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .verticalScroll(scrollState)
                .padding(20.dp),
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
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "By tapping \"Accept & Continue\" you confirm that you are at least 16 years old and agree to these terms.",
                color = TextDim.copy(alpha = 0.7f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Spacer(Modifier.height(16.dp))

        if (!readEnough) {
            Text(
                text = "Scroll to read all terms",
                color = TextDim.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = onAccept,
            enabled = readEnough,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanAccent,
                contentColor = BgDeep,
                disabledContainerColor = CyanAccent.copy(alpha = 0.2f),
                disabledContentColor = TextDim,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = "ACCEPT & CONTINUE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TosSection(title: String, body: String) {
    Text(
        text = title,
        color = CyanAccent.copy(alpha = 0.9f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = body,
        color = TextDim,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(18.dp))
}

// ── Step 2: Identity creation ─────────────────────────────────────────────────

@Composable
private fun IdentityScreen(container: AppContainer, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(60.dp))

        Text(
            text = "PHANTOM",
            color = TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 4.sp,
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Your presence,\nknown to no one.",
            color = TextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 38.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(48.dp))

        Text(
            text = "Choose your identity",
            color = TextDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
        )

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (valid) CyanAccent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "@", color = CyanAccent, fontSize = 20.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = username,
                onValueChange = {
                    username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }
                    error = null
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light,
                ),
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
                        Text("username", color = TextDim, fontSize = 20.sp, fontWeight = FontWeight.Light)
                    }
                    inner()
                },
            )
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = error!!, color = Danger, fontSize = 12.sp)
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
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanAccent,
                contentColor = BgDeep,
                disabledContainerColor = CyanAccent.copy(alpha = 0.2f),
                disabledContentColor = TextDim,
            ),
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = BgDeep, strokeWidth = 2.dp)
            } else {
                Text(text = "BEGIN", fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

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
