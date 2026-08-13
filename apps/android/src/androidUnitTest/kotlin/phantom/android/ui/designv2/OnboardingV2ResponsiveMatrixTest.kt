// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui.designv2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingFinaleConfirmation
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingHowV2
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyEmpty
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyInvalid
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyShort
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingIdentityKeyValid
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPermissionsNotifDisabled
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPermissionsNotifEnabled
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPricingSheet
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPricingSheetScrolledBottom
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPrivacyGhostLocked
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPrivacyPrivate
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingPrivacyStandard
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingTermsV2
import phantom.android.ui.designv2.showcase.ShowcaseOnboardingWelcomeV2

/**
 * Round-6 REDLINE on Commit 5 — responsive matrix, corrected
 * coverage. Prior round-5 KDoc claimed "every user-visible state"
 * but shipped only 7 of the ~14 user-facing states. Round-6
 * expands the state list to cover error cases (identity
 * validation failures), the two extra privacy tiers (Private,
 * Ghost-locked), notifications disabled path, pricing sheet
 * scrolled to the bottom, and the How step.
 *
 * Round-7 REDLINE on Commit 5 §P2 pin: identity_key_* variants
 * now render via `ShowcaseIdentityKeyStateAtInput` (see
 * companion object) with an initial ScrollState clamped to
 * bottom — so at fontScale 2.0 the input + helper are actually
 * in view and the state-specific helper text becomes a distinct
 * golden. At fontScale 1.0 the ScrollState clamps to 0 (content
 * fits) so those cells match the top-of-scroll appearance.
 *
 * States NOT covered (and why they're excluded from THIS suite):
 *
 *   - Terms scrolled-to-bottom + Accept-enabled state — deferred
 *     to a per-state semantics test (`OnboardingV2SemanticsTest`)
 *     since Terms is already covered by the singleton
 *     `onboarding_v2_terms` golden across configs.
 *   - Ghost-selected-and-unlocked — Ghost is Pro-locked in this
 *     product tier; the "Ghost UNLOCKED" state is a future flag,
 *     not reachable via the onboarding surface, so no showcase.
 *
 * Viewport × fontScale matrix (unchanged from round-5):
 *
 *   PIXEL_5 (411 dp)   ×   NARROW_320DP (320 dp)
 *   fontScale 1.0      ×   fontScale 2.0
 *
 * Custom `NARROW_320DP` config = `PIXEL_5.copy(screenWidth = 960,
 * screenHeight = 2340)` — 320 dp at xxhdpi (density 3.0).
 *
 * Total: 4 configs × 15 states = 60 goldens.
 */
@RunWith(Parameterized::class)
class OnboardingV2ResponsiveMatrixTest(
    private val deviceConfig: DeviceConfig,
    private val fontScale: Float,
    @Suppress("unused")
    private val label: String,
) {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = deviceConfig)

    @Test fun terms() = snap { ShowcaseOnboardingTermsV2() }

    @Test fun welcome() = snap { ShowcaseOnboardingWelcomeV2() }

    @Test fun how() = snap { ShowcaseOnboardingHowV2() }

    // Round-7 REDLINE §P2 pin: identity_key_* goldens must show
    // their named state. Prior round-6 goldens were byte-identical
    // across `Empty`, `Short`, `Invalid` at fs 2.0 because the
    // input + helper were below the initial viewport — the visible
    // area was just the header/preview card. Inline these into
    // this test with an explicit pre-scrolled ScrollState so the
    // input lands in view; the state-specific helper text is now
    // captured in the golden.

    @Test fun identity_key_empty() = snap {
        ShowcaseIdentityKeyStateAtInput(username = "")
    }
    @Test fun identity_key_short() = snap {
        ShowcaseIdentityKeyStateAtInput(username = "ab")
    }
    @Test fun identity_key_invalid() = snap {
        ShowcaseIdentityKeyStateAtInput(username = "al!ce")
    }
    @Test fun identity_key_valid() = snap {
        ShowcaseIdentityKeyStateAtInput(username = "alice")
    }

    @Test fun privacy_standard() = snap { ShowcaseOnboardingPrivacyStandard() }

    @Test fun privacy_private() = snap { ShowcaseOnboardingPrivacyPrivate() }

    @Test fun privacy_ghost_locked() = snap { ShowcaseOnboardingPrivacyGhostLocked() }

    @Test fun permissions_notif_enabled() = snap { ShowcaseOnboardingPermissionsNotifEnabled() }

    @Test fun permissions_notif_disabled() = snap { ShowcaseOnboardingPermissionsNotifDisabled() }

    @Test fun pricing_sheet() = snap { ShowcaseOnboardingPricingSheet() }

    @Test fun pricing_sheet_scrolled_bottom() = snap { ShowcaseOnboardingPricingSheetScrolledBottom() }

    @Test fun finale_confirmation() = snap { ShowcaseOnboardingFinaleConfirmation() }

    private fun snap(content: @Composable () -> Unit) {
        paparazzi.snapshot {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = fontScale,
                ),
            ) {
                content()
            }
        }
    }

    companion object {
        @Composable
        internal fun ShowcaseIdentityKeyStateAtInput(username: String) {
            // Wrap IdentityKeyStepV2 with a ScrollState pre-scrolled
            // to the bottom (Int.MAX_VALUE clamps on layout to
            // maxValue). At fs 1.0 wide viewport content already
            // fits so scroll clamps to 0; the golden matches the
            // top-of-scroll state. At fs 2.0 narrow viewport the
            // scroll clamps to maxValue and the input + helper
            // text land in the visible area — the state (Empty /
            // Short / Invalid / Valid) is finally rendered as a
            // distinct golden.
            val scrollState = androidx.compose.foundation.rememberScrollState(
                initial = Int.MAX_VALUE,
            )
            phantom.android.screens.onboarding.v2.OnboardingV2HostFrame(
                currentStep = phantom.android.screens.onboarding.v2.OnboardingStepV2.Identity,
                topInset = 24.dp,
                onBackClick = {},
                edgeSwipeBackEnabled = true,
                onEdgeSwipeBack = {},
                toastMessage = null,
                onToastDismiss = {},
            ) {
                phantom.android.screens.onboarding.v2.steps.IdentityKeyStepV2(
                    formState = phantom.android.screens.onboarding.v2.OnboardingFormStateV2(username = username),
                    dotsIndex = 1,
                    onFormStateChange = {},
                    onContinueClick = {},
                    scrollState = scrollState,
                )
            }
        }

        private val NARROW_320DP: DeviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 960,
            screenHeight = 2340,
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{2}")
        fun configs(): Iterable<Array<Any>> = listOf(
            arrayOf(DeviceConfig.PIXEL_5, 1.0f, "pixel5_fs1"),
            arrayOf(DeviceConfig.PIXEL_5, 2.0f, "pixel5_fs2"),
            arrayOf(NARROW_320DP,          1.0f, "narrow320_fs1"),
            arrayOf(NARROW_320DP,          2.0f, "narrow320_fs2"),
        )
    }
}
