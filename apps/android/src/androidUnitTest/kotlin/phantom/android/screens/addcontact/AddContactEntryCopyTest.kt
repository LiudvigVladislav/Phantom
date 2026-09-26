// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.addcontact

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import phantom.android.R
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AddContactEntryCopyTest {
    @Test
    fun reachableEntryDoesNotPromiseAReleaseDateOrQrHandshake() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("Username search is not available", resources.getString(R.string.add_contact_screen_search_unavailable))
        assertEquals("Scan a contact's public key", resources.getString(R.string.add_contact_screen_scan_qr_hint))
        assertFalse(resources.getString(R.string.add_contact_screen_search_alternative).contains("2026"))
        assertFalse(resources.getString(R.string.add_contact_screen_scan_qr_hint).contains("handshake", ignoreCase = true))
    }

    @Test
    fun ownKeyPreviewUsesTheProvidedKeyFragment() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("key: ABCDEF12…", resources.getString(R.string.add_contact_screen_key_preview, "ABCDEF12"))
    }
}
