package phantom.android.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    // Holds the pending onComplete call until the permission dialog resolves.
    // On Android < 13 this lambda is invoked immediately without showing a dialog.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — proceed either way; notifications are non-blocking */ ->
        onComplete()
    }

    // Wraps onComplete so that on Android 13+ we first ask for POST_NOTIFICATIONS.
    // The request is shown at most once by the OS; subsequent calls are silently skipped.
    val proceed: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onComplete()
        }
    }

    val valid = username.length >= 3

    // No statusBarsPadding/navigationBarsPadding — without edge-to-edge the system
    // already places content below the status bar. adjustResize handles IME.
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
