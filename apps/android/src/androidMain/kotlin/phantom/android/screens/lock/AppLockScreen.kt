// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import phantom.android.ui.theme.*

@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var errorMsg by remember { mutableStateOf("") }

    // Trigger biometric prompt automatically when the screen first appears.
    LaunchedEffect(Unit) {
        showBiometricPrompt(context, onSuccess = onUnlocked, onError = { errorMsg = it })
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // PHANTOM wordmark
            Text(
                text = "PHANTOM",
                color = CyanAccent,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 8.sp,
            )

            // Canvas-drawn padlock icon
            Canvas(modifier = Modifier.size(64.dp)) {
                val w = size.width
                val h = size.height
                val strokeWidth = w * 0.07f

                // Shackle — semicircular arc forming the top of the padlock
                drawArc(
                    color = CyanAccent,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.25f, h * 0.05f),
                    size = Size(w * 0.5f, h * 0.45f),
                    style = Stroke(width = strokeWidth),
                )

                // Body — rounded rectangle forming the lock body
                drawRoundRect(
                    color = CyanAccent.copy(alpha = 0.85f),
                    topLeft = Offset(w * 0.15f, h * 0.45f),
                    size = Size(w * 0.7f, h * 0.46f),
                    cornerRadius = CornerRadius(w * 0.1f),
                )

                // Keyhole circle
                drawCircle(
                    color = BgDeep,
                    radius = w * 0.08f,
                    center = Offset(w * 0.5f, h * 0.655f),
                )
            }

            if (errorMsg.isNotEmpty()) {
                Text(
                    text = errorMsg,
                    color = Danger,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Manual unlock button — shown so the user can retry if the auto-prompt was dismissed
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface2)
                    .border(1.dp, CyanAccent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .clickable {
                        showBiometricPrompt(
                            context,
                            onSuccess = onUnlocked,
                            onError = { errorMsg = it },
                        )
                    }
                    .padding(horizontal = 32.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "UNLOCK",
                    color = CyanAccent,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}

private fun showBiometricPrompt(
    context: Context,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val activity = context as? FragmentActivity ?: run {
        // Not a FragmentActivity — skip lock gracefully
        onSuccess()
        return
    }

    val biometricManager = BiometricManager.from(context)
    val canAuth = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    )
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        // No biometric or PIN is enrolled — unlock directly so the app is not stuck
        onSuccess()
        return
    }

    val executor = ContextCompat.getMainExecutor(context)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (
                errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
            ) {
                onError("Tap UNLOCK to try again")
            } else {
                onError(errString.toString())
            }
        }

        override fun onAuthenticationFailed() {
            onError("Authentication failed — try again")
        }
    }

    val prompt = BiometricPrompt(activity, executor, callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("PHANTOM")
        .setSubtitle("Verify your identity")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        .build()
    prompt.authenticate(info)
}
