// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceFileStoreLocalDeletionTest {
    @Test
    fun only_the_matching_managed_voice_file_can_be_deleted() = runTest {
        val store = VoiceFileStore()
        val suffix = System.nanoTime().toString()
        val targetId = "target-$suffix"
        val otherId = "other-$suffix"
        val target = store.save(targetId, byteArrayOf(1), "audio/ogg")
        val other = store.save(otherId, byteArrayOf(2), "audio/ogg")
        try {
            assertFalse(store.deleteStored(targetId, other))
            assertTrue(File(other).exists())
            assertFalse(store.deleteStored("../$targetId", target))
            assertTrue(File(target).exists())
            assertTrue(store.deleteStored(targetId, target))
            assertFalse(File(target).exists())
        } finally {
            store.deleteStored(targetId, target)
            store.deleteStored(otherId, other)
        }
    }
}
