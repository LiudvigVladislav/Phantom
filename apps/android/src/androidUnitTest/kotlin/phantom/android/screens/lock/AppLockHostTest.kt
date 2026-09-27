// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.lock

import android.app.Application
import android.app.Activity
import android.app.KeyguardManager
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.os.Build
import androidx.biometric.BiometricManager as JetpackBiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBiometricManager
import phantom.android.MainActivity
import phantom.android.R
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppLockHostTest {
    @Test
    fun productionActivityCanHostTheBiometricPrompt() {
        assertTrue(FragmentActivity::class.java.isAssignableFrom(MainActivity::class.java))
    }

    @Test
    fun wrappedContextFindsTheActualActivity() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        assertSame(activity, findLockHost(ContextWrapper(ContextWrapper(activity))))
    }

    @Test
    fun unsupportedHostNeverUnlocks() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(findLockHost(context))
        var unlocked = false
        var error: Int? = null

        showBiometricPrompt(context, onSuccess = { unlocked = true }, onError = { error = it })

        assertFalse(unlocked)
        assertEquals(R.string.lock_authentication_unavailable, error)
    }

    @Test
    fun unavailableDeviceAuthenticationNeverUnlocks() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val biometricManager = activity.getSystemService(BiometricManager::class.java)
        Shadow.extract<ShadowBiometricManager>(biometricManager).setCanAuthenticate(false)
        var unlocked = false
        var error: Int? = null

        showBiometricPrompt(activity, onSuccess = { unlocked = true }, onError = { error = it })

        assertFalse(unlocked)
        assertEquals(R.string.lock_authentication_unavailable, error)
    }

    @Test
    fun androidNineAndTenDoNotRequestUnsupportedCredentialCombination() {
        val strong = JetpackBiometricManager.Authenticators.BIOMETRIC_STRONG
        val combined = strong or JetpackBiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(combined, appLockAuthenticators(27))
        assertEquals(strong, appLockAuthenticators(28))
        assertEquals(strong, appLockAuthenticators(29))
        assertEquals(combined, appLockAuthenticators(30))
        assertFalse(usesLegacyCredentialPrompt(27))
        assertTrue(usesLegacyCredentialPrompt(28))
        assertTrue(usesLegacyCredentialPrompt(29))
        assertFalse(usesLegacyCredentialPrompt(30))
    }

    @Test
    @Config(sdk = [28, 29])
    fun missingDeviceCredentialCannotStartLegacyUnlock() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(false)

        assertNull(legacyCredentialIntent(context))
    }

    @Test
    @Config(sdk = [28, 29])
    fun enrolledDeviceCredentialProvidesLegacyUnlockIntent() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)

        assertNotNull(legacyCredentialIntent(context))
    }

    @Test
    fun legacyUnlockRequiresSuccessfulSystemResult() {
        assertTrue(credentialResultUnlocks(Activity.RESULT_OK))
        assertFalse(credentialResultUnlocks(Activity.RESULT_CANCELED))
        assertFalse(credentialResultUnlocks(1))
    }

    @Test
    @Config(sdk = [28, 29])
    fun strongOnlyPromptBuildsOnAndroidNineAndTen() {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("PHANTOM")
            .setAllowedAuthenticators(appLockAuthenticators(Build.VERSION.SDK_INT))
            .setNegativeButtonText("Cancel")
            .build()
    }
}
