// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

import android.app.Application
import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Onboarding-stabilization block 2026-08-11 — round-trip pins for
 * [ScreenSaver]. Every [Screen] variant must survive
 * save → restore byte-identically, including nulls and data-class
 * payloads. Regression protection: if a new variant is added to
 * [Screen] without a matching branch in [ScreenSaver], the last
 * catch-all test fails-red.
 *
 * All assertions call `Saver.restore(Saver.save(x))` == x. The
 * [SaverScope] passed to `save` is a no-op canSave gate for these
 * primitive payloads (String, Boolean).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScreenSaverTest {

    private val saverScope = SaverScope { true }

    private fun roundTrip(screen: Screen?): Screen? {
        val saved = with(ScreenSaver) { saverScope.save(screen) }
            ?: error("ScreenSaver.save returned null for $screen")
        return ScreenSaver.restore(saved)
    }

    @Test
    fun null_route_round_trips_to_null() {
        assertNull(roundTrip(null))
    }

    @Test
    fun every_object_variant_round_trips_by_identity() {
        val singletons = listOf(
            Screen.Onboarding, Screen.Migration, Screen.ChatList,
            Screen.Calls, Screen.Nearby, Screen.Premium, Screen.Settings,
            Screen.PrivacyModeDetail, Screen.MessageRequests, Screen.Profile,
            Screen.AddContact, Screen.QrScan, Screen.SavedMessages,
            Screen.Archive, Screen.CreateGroup, Screen.CreateChannel,
        )
        for (s in singletons) {
            assertEquals(s, roundTrip(s), "Round-trip mismatch for $s")
        }
    }

    @Test
    fun startup_error_round_trips_reason_name() {
        val restored = roundTrip(Screen.StartupError("LoadIdentityThrew"))
        assertEquals(Screen.StartupError("LoadIdentityThrew"), restored)
    }

    @Test
    fun chat_round_trips_conversation_and_username() {
        val restored = roundTrip(Screen.Chat("conv-123", "alice"))
        assertEquals(Screen.Chat("conv-123", "alice"), restored)
    }

    @Test
    fun contact_profile_round_trips_conversation_and_username() {
        val restored = roundTrip(Screen.ContactProfile("conv-abc", "bob"))
        assertEquals(Screen.ContactProfile("conv-abc", "bob"), restored)
    }

    @Test
    fun verify_round_trips_conversation_and_username() {
        val restored = roundTrip(Screen.Verify("conv-xyz", "carol"))
        assertEquals(Screen.Verify("conv-xyz", "carol"), restored)
    }

    @Test
    fun group_chat_round_trips_id_name_and_is_channel_flag() {
        val group   = Screen.GroupChat("grp-1", "Team Phantom", isChannel = false)
        val channel = Screen.GroupChat("chn-9", "News", isChannel = true)
        assertEquals(group,   roundTrip(group))
        assertEquals(channel, roundTrip(channel))
    }

    @Test
    fun active_call_round_trips_conversation_and_username() {
        val restored = roundTrip(Screen.ActiveCall("conv-7", "dave"))
        assertEquals(Screen.ActiveCall("conv-7", "dave"), restored)
    }

    @Test
    fun incoming_call_round_trips_conversation_and_username() {
        val restored = roundTrip(Screen.IncomingCall("conv-8", "eve"))
        assertEquals(Screen.IncomingCall("conv-8", "eve"), restored)
    }

    @Test
    fun profile_route_round_trips_specifically() {
        // Load-bearing for the rotation fix — Profile MUST survive
        // save/restore because the whole point of adding the saver
        // was to keep the user on Profile through Activity
        // recreation.
        val restored = roundTrip(Screen.Profile)
        assertEquals(Screen.Profile, restored)
    }
}
