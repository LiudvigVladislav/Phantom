// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.verify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifyScreenLocalizationTest {
    private val myKey = "a1b2".repeat(16)
    private val theirKey = "c3d4".repeat(16)

    @Test
    fun confirmationRequiresTwoCompleteHexKeys() {
        assertFalse(verificationKeysReady("", theirKey))
        assertFalse(verificationKeysReady(myKey, ""))
        assertFalse(verificationKeysReady("a1b2", theirKey))
        assertFalse(verificationKeysReady("z".repeat(64), theirKey))
        assertTrue(verificationKeysReady(myKey, theirKey))
    }

    @Test
    fun confirmationRequiresTheDisplayedSnapshotToRemainCurrent() {
        assertTrue(verificationSnapshotStillCurrent(myKey, theirKey, myKey, theirKey))
        assertTrue(verificationSnapshotStillCurrent(myKey.uppercase(), theirKey, myKey, theirKey))
        assertFalse(verificationSnapshotStillCurrent(myKey, theirKey, "e5f6".repeat(16), theirKey))
        assertFalse(verificationSnapshotStillCurrent(myKey, theirKey, myKey, "e5f6".repeat(16)))
        assertFalse(verificationSnapshotStillCurrent(myKey, theirKey, myKey, ""))
        assertFalse(verificationSnapshotStillCurrent("", theirKey, myKey, theirKey))
    }
}
