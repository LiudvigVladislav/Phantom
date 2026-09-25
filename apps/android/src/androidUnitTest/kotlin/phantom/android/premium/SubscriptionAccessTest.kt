// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.premium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import phantom.core.transport.PrivacyMode
import phantom.core.transport.loadPrivacyModeAtStartup

class SubscriptionAccessTest {
    @Test fun ghost_is_locked_without_a_verified_pro_subscription() {
        assertFalse(SubscriptionAccess.hasVerifiedPro())
        assertFalse(SubscriptionAccess.permits(PrivacyMode.Ghost))
        assertTrue(SubscriptionAccess.permits(PrivacyMode.Standard))
        assertTrue(SubscriptionAccess.permits(PrivacyMode.Private))
    }

    @Test fun previously_saved_ghost_is_not_silently_downgraded() {
        val saved = loadPrivacyModeAtStartup(canonicalRaw = "Ghost", legacyRaw = null)
        assertEquals(PrivacyMode.Ghost, saved.mode)
        assertFalse(SubscriptionAccess.permits(saved.mode))
        assertTrue(SubscriptionAccess.requiresModeChoice(saved.mode, repairRequired = false))
        assertFalse(SubscriptionAccess.requiresModeChoice(saved.mode, repairRequired = true))
        assertFalse(SubscriptionAccess.requiresModeChoice(saved.mode, repairRequired = null))
    }
}
