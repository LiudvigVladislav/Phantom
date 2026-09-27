// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import phantom.android.R
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono

@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var errorRes by remember { mutableIntStateOf(0) }
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (credentialResultUnlocks(result.resultCode)) onUnlocked()
        else errorRes = R.string.lock_try_again
    }

    val requestUnlock = {
        errorRes = 0
        if (usesLegacyCredentialPrompt(Build.VERSION.SDK_INT)) {
            val intent = legacyCredentialIntent(context)
            if (intent != null) credentialLauncher.launch(intent)
            else errorRes = R.string.lock_authentication_unavailable
        } else {
            showBiometricPrompt(context, onSuccess = onUnlocked, onError = { errorRes = it })
        }
    }

    // Trigger the supported system authentication prompt when the screen first appears.
    LaunchedEffect(Unit) {
        requestUnlock()
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
            // PHANTOM wordmark — Geist 24sp medium, negative tracking.
            // Matches the brand-mark treatment used everywhere else.
            Text(
                text = "PHANTOM",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.24).sp,
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

            if (errorRes != 0) {
                Text(
                    text = stringResource(errorRes),
                    color = Danger,
                    fontSize = 12.sp,
                    fontFamily = PhantomFontMono,
                )
            }

            // Manual unlock — primary cyan pill matching the design's
            // primary CTA pattern (radius 9999, 44dp, restrained glow).
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9999.dp))
                    .background(CyanAccent)
                    .clickable {
                        requestUnlock()
                    }
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lock_unlock),
                    color = BgDeep,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

internal fun findLockHost(context: Context): FragmentActivity? {
    var current: Context? = context
    while (current != null) {
        if (current is FragmentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

internal fun appLockAuthenticators(apiLevel: Int): Int =
    if (apiLevel in 28..29) BiometricManager.Authenticators.BIOMETRIC_STRONG
    else BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

internal fun usesLegacyCredentialPrompt(apiLevel: Int): Boolean = apiLevel in 28..29

internal fun credentialResultUnlocks(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK

@Suppress("DEPRECATION")
internal fun legacyCredentialIntent(context: Context): Intent? {
    val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return null
    if (!keyguard.isDeviceSecure) return null
    return keyguard.createConfirmDeviceCredentialIntent(
        context.getString(R.string.lock_unlock),
        context.getString(R.string.lock_verify_identity),
    )
}

internal fun showBiometricPrompt(
    context: Context,
    onSuccess: () -> Unit,
    onError: (Int) -> Unit,
) {
    val activity = findLockHost(context) ?: run {
        onError(R.string.lock_authentication_unavailable)
        return
    }

    val biometricManager = BiometricManager.from(context)
    val strongOnly = Build.VERSION.SDK_INT in 28..29
    val authenticators = appLockAuthenticators(Build.VERSION.SDK_INT)
    val canAuth = biometricManager.canAuthenticate(authenticators)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        onError(
            if (strongOnly) R.string.lock_strong_biometric_required
            else R.string.lock_authentication_unavailable,
        )
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
                onError(R.string.lock_try_again)
            } else {
                onError(R.string.lock_authentication_failed)
            }
        }

        override fun onAuthenticationFailed() {
            onError(R.string.lock_authentication_failed)
        }
    }

    val prompt = BiometricPrompt(activity, executor, callback)
    val infoBuilder = BiometricPrompt.PromptInfo.Builder()
        .setTitle("PHANTOM")
        .setSubtitle(context.getString(R.string.lock_verify_identity))
        .setAllowedAuthenticators(authenticators)
    if (strongOnly) infoBuilder.setNegativeButtonText(context.getString(R.string.lock_cancel))
    val info = infoBuilder.build()
    prompt.authenticate(info)
}
