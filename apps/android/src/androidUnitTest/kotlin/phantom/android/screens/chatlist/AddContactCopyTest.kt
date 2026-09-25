// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chatlist

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
class AddContactCopyTest {
    @Test
    fun recognized_name_and_placeholder_keep_the_peer_name_verbatim() {
        val peerName = "alice%20_test"
        val context = RuntimeEnvironment.getApplication()

        assertEquals("✓  @alice%20_test", context.getString(R.string.add_contact_name_recognized, peerName))
        assertEquals("@alice%20_test", context.getString(R.string.add_contact_name_placeholder, peerName))
    }
}
