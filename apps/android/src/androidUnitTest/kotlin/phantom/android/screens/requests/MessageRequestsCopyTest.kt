// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.requests

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import phantom.android.R
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MessageRequestsCopyTest {
    @Test
    fun block_confirmation_keeps_the_peer_name_verbatim() {
        val peerName = "alice%20_test"

        assertEquals(
            "Block @alice%20_test?",
            RuntimeEnvironment.getApplication().getString(
                R.string.requests_block_confirm_title,
                peerName,
            ),
        )
    }
}
