// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JVM actual of [VoiceFileStore] — used in unit tests / desktop builds (PR-M1w).
 *
 * Writes audio bytes to a system temp directory so tests that exercise the
 * download orchestrator path can verify the file was written without needing
 * an Android runtime.
 */
actual class VoiceFileStore {

    actual suspend fun save(
        mediaId: String,
        audioBytes: ByteArray,
        mime: String,
    ): String = withContext(Dispatchers.IO) {
        val dir = File(System.getProperty("java.io.tmpdir"), "phantom_voice_test")
        dir.mkdirs()
        val file = File(dir, "$mediaId.audio")
        file.writeBytes(audioBytes)
        file.absolutePath
    }
}
