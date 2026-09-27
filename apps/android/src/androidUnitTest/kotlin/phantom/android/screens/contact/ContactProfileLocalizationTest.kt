// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.contact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactProfileLocalizationTest {
    private val key = "a1b2".repeat(16)

    @Test
    fun absentOrMalformedKeyHasNoFingerprintToShowOrCopy() {
        assertNull(contactKeyPreview(""))
        assertNull(contactKeyPreview("a1b2"))
        assertNull(contactKeyPreview("z".repeat(64)))
        assertEquals("a1b2  a1b2  a1b2  a1b2  a1b2  a1b2  a1b2  a1b2  …", contactKeyPreview(key))
    }

    @Test
    fun verifiedLabelRequiresARealUnchangedKey() {
        assertFalse(contactKeyIsVerified("", true, null))
        assertFalse(contactKeyIsVerified("z".repeat(64), true, null))
        assertFalse(contactKeyIsVerified(key, false, null))
        assertFalse(contactKeyIsVerified(key, true, 1L))
        assertTrue(contactKeyIsVerified(key, true, null))
    }
}
