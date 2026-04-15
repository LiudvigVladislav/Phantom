package phantom.android.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val valid = username.length >= 3

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))

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

        Spacer(Modifier.height(56.dp))

        Text(
            text = "Choose your identity",
            color = TextDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
        )

        Spacer(Modifier.height(24.dp))

        // Username input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = if (valid) CyanAccent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f))
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
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
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
                            runOnboarding(container, username, onComplete) { error = it; loading = false }
                        }
                    }
                }),
                decorationBox = { inner ->
                    if (username.isEmpty()) {
                        Text("username", color = TextDim, fontSize = 20.sp, fontWeight = FontWeight.Light)
                    }
                    inner()
                }
            )
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = error!!, color = Danger, fontSize = 12.sp)
        }

        Spacer(Modifier.weight(1f))

        // CTA button
        Button(
            onClick = {
                if (valid && !loading) {
                    scope.launch {
                        loading = true
                        runOnboarding(container, username, onComplete) { error = it; loading = false }
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

        Spacer(Modifier.height(48.dp))
    }
}

private suspend fun runOnboarding(
    container: AppContainer,
    username: String,
    onComplete: () -> Unit,
    onError: (String) -> Unit,
) {
    runCatching {
        val (record, keyPair) = container.identityManager.createOrLoad(username)
        container.initMessaging(record, phantom.core.crypto.DhKeyPair(
            phantom.core.crypto.DhPublicKey(record.publicKeyHex.hexToByteArray()),
            phantom.core.crypto.DhPrivateKey(ByteArray(32)), // placeholder — secure storage in Beta
        ))
        onComplete()
    }.onFailure { onError(it.message ?: "Failed to create identity") }
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
