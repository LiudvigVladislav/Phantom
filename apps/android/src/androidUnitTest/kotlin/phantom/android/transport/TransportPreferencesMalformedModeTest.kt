// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.transport.PrivacyMode
import phantom.core.transport.PrivacyModeRestEgressPolicy
import phantom.core.transport.RestEgressDecision
import phantom.core.transport.TransportPreferencesAndroid
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * N1-F2 R-N1.2 P2 (2026-08-29) — the REAL Android persistence adapter,
 * not a fake provider.
 *
 * A MISSING privacy-mode preference keeps the intentional legacy
 * default (Standard). A preference that is PRESENT but malformed must
 * stay distinguishable and drive the egress policy to
 * `AnonymousRequiredButUnavailable` — never `DirectAllowed`. A
 * corrupted stored value must not silently authorise Direct egress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TransportPreferencesMalformedModeTest {

    private fun prefs(): SharedPreferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("phantom_prefs_n1f2_test", Context.MODE_PRIVATE)

    private fun freshPrefs(): SharedPreferences = prefs().also {
        it.edit().clear().commit()
    }

    private val key = "transport.privacy_mode"

    @Test
    fun missing_preference_keeps_legacy_standard_default() {
        val p = TransportPreferencesAndroid(freshPrefs())
        assertEquals(PrivacyMode.Standard, p.privacyMode)
        assertEquals(
            PrivacyMode.Standard, p.privacyModeForEgress(),
            "a missing key is the intentional legacy default, not a corruption",
        )
        assertEquals(
            RestEgressDecision.DirectAllowed,
            PrivacyModeRestEgressPolicy { p.privacyModeForEgress() }.decide(),
        )
    }

    @Test
    fun present_and_valid_preference_round_trips() {
        for (mode in PrivacyMode.entries) {
            val sp = freshPrefs()
            sp.edit().putString(key, mode.name).commit()
            val p = TransportPreferencesAndroid(sp)
            assertEquals(mode, p.privacyModeForEgress(), "valid value must parse: $mode")
        }
    }

    @Test
    fun present_but_malformed_preference_fails_closed() {
        for (bad in listOf("", "   ", "standard", "GHOST_MODE", "Privat", "\u0000", "42")) {
            val sp = freshPrefs()
            sp.edit().putString(key, bad).commit()
            val p = TransportPreferencesAndroid(sp)
            assertNull(
                p.privacyModeForEgress(),
                "a present-but-malformed value must be distinguishable: ${bad.take(12)}",
            )
            val decision = PrivacyModeRestEgressPolicy { p.privacyModeForEgress() }.decide()
            assertIs<RestEgressDecision.AnonymousRequiredButUnavailable>(
                decision,
                "malformed stored mode must never authorise Direct egress",
            )
        }
    }

    @Test
    fun malformed_preference_still_reads_standard_on_the_ux_getter() {
        // The plain getter keeps its legacy UX behaviour; only the egress
        // read distinguishes. This pins that the two readers differ ON
        // PURPOSE, so a future refactor cannot collapse them silently.
        val sp = freshPrefs()
        sp.edit().putString(key, "not-a-mode").commit()
        val p = TransportPreferencesAndroid(sp)
        assertEquals(PrivacyMode.Standard, p.privacyMode)
        assertNull(p.privacyModeForEgress())
    }
}
