// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.calls

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import phantom.android.R
import phantom.android.calls.CallState
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallScreenCopyTest {
    @Test
    fun everyVisibleCallStateHasAResource() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(callStatusResource(CallState.IDLE))
        assertEquals("CALLING…", context.getString(callStatusResource(CallState.CALLING)!!))
        assertEquals("INCOMING CALL", context.getString(callStatusResource(CallState.RINGING)!!))
        assertEquals("Connected", context.getString(callStatusResource(CallState.IN_CALL)!!))
        assertEquals("CALL ENDED", context.getString(callStatusResource(CallState.ENDED)!!))
        assertEquals("CALL DECLINED", context.getString(callStatusResource(CallState.REJECTED)!!))
    }

    @Test
    fun callActionsHaveAccessibleNames() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            R.string.call_accept,
            R.string.call_decline,
            R.string.call_back_to_chat,
            R.string.call_mute_microphone,
            R.string.call_unmute_microphone,
            R.string.call_end,
            R.string.call_speaker_on,
            R.string.call_speaker_off,
        ).forEach { resource -> assertTrue(context.getString(resource).isNotBlank()) }
    }
}
