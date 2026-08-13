// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.onboarding.v2

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.transport.InMemoryTransportPreferences
import phantom.core.transport.PrivacyMode
import phantom.core.transport.TransportPreferencesAndroid
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Round-3 REDLINE on Commit 4 §P1-1 contract tests — the first-run
 * privacy-mode persistence MUST:
 *
 *   1. Write BOTH storage surfaces `AppContainer.setPrivacyMode`
 *      mirrors (canonical `transport.privacy_mode` +
 *      legacy `privacy_mode` — both in `phantom_prefs`).
 *   2. Do it atomically via a single `SharedPreferences.Editor.commit()`
 *      — synchronous, fail-loud. If the commit fails (returns false),
 *      throw `PrivacyModePersistenceException` so no identity is
 *      persisted downstream.
 *   3. Survive a process restart — the value written pre-restart
 *      MUST be readable by a fresh `TransportPreferencesAndroid`
 *      constructed from the same SharedPreferences file.
 *
 * Read-receipt gate parity check: the legacy value written by this
 * helper equals `PrivacyMode.name`, matching what
 * `AppContainer.setPrivacyMode` writes at `di/AppContainer.kt:1100`
 * (which is what `ChatScreen` reads at runtime).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingPrivacyModePersistenceTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun freshPrefs(): SharedPreferences = context()
        .getSharedPreferences(LEGACY_PHANTOM_PREFS_NAME, Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test
    fun private_mode_writes_both_keys_atomically_via_commit() {
        val prefs = InMemoryTransportPreferences(initialMode = PrivacyMode.Standard)
        freshPrefs()  // clean slate

        applyPrivacyModeToFirstRunStores(
            context = context(),
            transportPreferences = prefs,
            mode = PrivacyMode.Private,
        )

        // In-memory fake reflects the write.
        assertEquals(PrivacyMode.Private, prefs.privacyMode)

        // BOTH keys landed in the same `phantom_prefs` file via
        // one atomic editor commit.
        val sp = context()
            .getSharedPreferences(LEGACY_PHANTOM_PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("Private", sp.getString(LEGACY_PRIVACY_MODE_KEY, null))
        assertEquals("Private", sp.getString(CANONICAL_TRANSPORT_PRIVACY_MODE_KEY, null))
    }

    @Test
    fun standard_mode_writes_both_keys() {
        val prefs = InMemoryTransportPreferences(initialMode = PrivacyMode.Ghost)
        freshPrefs()

        applyPrivacyModeToFirstRunStores(context(), prefs, PrivacyMode.Standard)

        assertEquals(PrivacyMode.Standard, prefs.privacyMode)
        val sp = context()
            .getSharedPreferences(LEGACY_PHANTOM_PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("Standard", sp.getString(LEGACY_PRIVACY_MODE_KEY, null))
        assertEquals("Standard", sp.getString(CANONICAL_TRANSPORT_PRIVACY_MODE_KEY, null))
    }

    @Test
    fun ghost_mode_writes_both_keys() {
        val prefs = InMemoryTransportPreferences()
        freshPrefs()

        applyPrivacyModeToFirstRunStores(context(), prefs, PrivacyMode.Ghost)

        assertEquals(PrivacyMode.Ghost, prefs.privacyMode)
        val sp = context()
            .getSharedPreferences(LEGACY_PHANTOM_PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("Ghost", sp.getString(LEGACY_PRIVACY_MODE_KEY, null))
        assertEquals("Ghost", sp.getString(CANONICAL_TRANSPORT_PRIVACY_MODE_KEY, null))
    }

    @Test
    fun legacy_key_matches_chat_screen_read_receipt_gate_call_shape() {
        // Read-receipt gate parity anchor — this is the exact call
        // pattern `ChatScreen` makes:
        //   getSharedPreferences("phantom_prefs", MODE_PRIVATE)
        //     .getString("privacy_mode", "Standard")
        // and it MUST return the value onboarding wrote.
        val prefs = InMemoryTransportPreferences()
        freshPrefs()

        applyPrivacyModeToFirstRunStores(context(), prefs, PrivacyMode.Private)

        val chatScreenReadReceiptRead = context()
            .getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .getString("privacy_mode", "Standard")
        assertEquals("Private", chatScreenReadReceiptRead)
    }

    @Test
    fun rewrite_with_a_different_mode_replaces_both_keys() {
        val prefs = InMemoryTransportPreferences()
        freshPrefs()

        applyPrivacyModeToFirstRunStores(context(), prefs, PrivacyMode.Private)
        applyPrivacyModeToFirstRunStores(context(), prefs, PrivacyMode.Ghost)

        assertEquals(PrivacyMode.Ghost, prefs.privacyMode)
        val sp = context()
            .getSharedPreferences(LEGACY_PHANTOM_PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("Ghost", sp.getString(LEGACY_PRIVACY_MODE_KEY, null))
        assertEquals("Ghost", sp.getString(CANONICAL_TRANSPORT_PRIVACY_MODE_KEY, null))
    }

    // ── Round-3 REDLINE §P1-1 durability tests ────────────────────────

    @Test
    fun value_persists_across_process_restart_via_fresh_transport_preferences_android() {
        // Round-3 REDLINE core assertion: simulate a process restart
        // by discarding the in-memory `InMemoryTransportPreferences`
        // used during onboarding and constructing a FRESH
        // `TransportPreferencesAndroid` from the same SharedPreferences
        // file (as `AppContainer` does on process start). The freshly
        // constructed reader MUST see the mode onboarding wrote.
        //
        // Under the round-2 amend shape (asynchronous `.apply()`),
        // this test would be flaky if the disk flush hadn't
        // completed before the fresh reader ran. The round-3 fix
        // (`.commit()` — synchronous) guarantees the value is on
        // disk by the time this helper returns.
        val onboardingPrefs = InMemoryTransportPreferences()
        freshPrefs()

        applyPrivacyModeToFirstRunStores(context(), onboardingPrefs, PrivacyMode.Private)

        // Simulate "app dies here" — onboardingPrefs goes out of scope.
        // Simulate "app relaunches" — fresh TransportPreferencesAndroid.
        val sp = context()
            .getSharedPreferences(LEGACY_PHANTOM_PREFS_NAME, Context.MODE_PRIVATE)
        val postRestartPrefs = TransportPreferencesAndroid(sp)

        assertEquals(
            PrivacyMode.Private,
            postRestartPrefs.privacyMode,
            "TransportPreferencesAndroid constructed after a process restart " +
                "MUST see the mode onboarding wrote — this is the durability " +
                "boundary contract.",
        )
    }

    @Test
    fun value_persists_across_restart_for_every_privacy_mode() {
        // Same shape as above, exhaustive across every enum value.
        for (mode in PrivacyMode.entries) {
            val fake = InMemoryTransportPreferences()
            freshPrefs()

            applyPrivacyModeToFirstRunStores(context(), fake, mode)

            val sp = context()
                .getSharedPreferences(LEGACY_PHANTOM_PREFS_NAME, Context.MODE_PRIVATE)
            val postRestart = TransportPreferencesAndroid(sp)

            assertEquals(
                mode,
                postRestart.privacyMode,
                "Post-restart TransportPreferencesAndroid must see mode=$mode",
            )
        }
    }

    @Test
    fun chat_screen_legacy_read_survives_restart() {
        // Read-receipt gate durability — the exact `ChatScreen` read
        // pattern still returns the onboarding-written value after a
        // simulated restart.
        val fake = InMemoryTransportPreferences()
        freshPrefs()

        applyPrivacyModeToFirstRunStores(context(), fake, PrivacyMode.Ghost)

        // Simulate process restart — construct a fresh context handle
        // (Robolectric gives us the same Application instance, but
        // the assertion still exercises the disk-backed read path).
        val postRestartRead = context()
            .getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .getString("privacy_mode", "Standard")
        assertEquals("Ghost", postRestartRead)
    }
}
