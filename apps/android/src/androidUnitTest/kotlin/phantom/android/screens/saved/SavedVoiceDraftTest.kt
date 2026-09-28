package phantom.android.screens.saved

import java.io.File
import java.util.Base64
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*
import phantom.android.screens.chat.RecordingPanelState
import phantom.core.messaging.VoiceMediaPolicy
import phantom.core.storage.MessageStatus

class SavedVoiceDraftTest {
    @get:Rule val temp = TemporaryFolder()
    private var time = 0L
    private class Recorder : LocalVoiceRecorder {
        var stops = 0
        var releases = 0
        var failStop = false
        override fun stop() { stops++; if (failStop) error("too short") }
        override fun release() { releases++ }
        override fun pause() {}
        override fun resume() {}
        override fun amplitude() = 1000
    }
    private fun draft(file: File, recorder: Recorder) = SavedVoiceDraft({ file to recorder }, { time })

    @Test fun existingAudioRepresentationRoundTripsAndNeverEntersOutbox() {
        val bytes = byteArrayOf(1, 2, 3, 127, -1)
        val body = savedVoiceBody(bytes)
        assertContentEquals(bytes, Base64.getDecoder().decode(body.removePrefix("[AUDIO:").removeSuffix("]")))
        val row = savedMessage("id", body, 42)
        assertEquals(SAVED_CONV_ID, row.conversationId)
        assertEquals(MessageStatus.DELIVERED, row.status)
        assertTrue(row.sent)
        assertTrue(row.ciphertext.isEmpty())
        assertNull(row.expiresAtMs)
        assertFailsWith<IllegalArgumentException> { savedVoiceBody(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { savedVoiceBody(ByteArray(VoiceMediaPolicy.MAX_PLAINTEXT_BYTES + 1)) }
    }
    @Test fun failedSaveKeepsTheSameDraftFileAndIdForRetry() {
        val file = temp.newFile().apply { writeText("audio") }
        val rec = Recorder()
        val draft = draft(file, rec)
        assertTrue(draft.start())
        val id = draft.id
        time = 5000
        assertEquals(file, draft.finish())
        assertTrue(draft.pending)
        assertTrue(file.exists())
        assertFalse(draft.start())
        assertEquals(file, draft.finish())
        assertEquals(id, draft.id)
        assertEquals(1, rec.stops)
        assertEquals(1, rec.releases)
        draft.discard()
        assertFalse(file.exists())
        assertFalse(draft.pending)
        assertEquals(1, rec.stops)
    }
    @Test fun pauseExcludesIdleTimeAndResumeStaysHandsFree() {
        val draft = draft(temp.newFile(), Recorder())
        draft.start()
        time = 1000
        draft.lock()
        draft.pause()
        time = 50000
        draft.tick()
        assertEquals(1000L, draft.durationMs)
        draft.resume()
        assertEquals(RecordingPanelState.Locked, draft.state)
        time = 51000
        draft.tick()
        assertEquals(2000L, draft.durationMs)
        time += VoiceMediaPolicy.MAX_DURATION_MS
        draft.tick()
        assertEquals(VoiceMediaPolicy.MAX_DURATION_MS, draft.durationMs)
        draft.discard()
    }
    @Test fun cancelReleasesOnceAndDeletesOnlyItsOwnTemporaryFile() {
        val file = temp.newFile()
        val other = temp.newFile()
        val rec = Recorder()
        val draft = draft(file, rec)
        draft.start()
        assertFalse(draft.start())
        draft.discard()
        draft.discard()
        assertEquals(1, rec.stops)
        assertEquals(1, rec.releases)
        assertFalse(file.exists())
        assertTrue(other.exists())
        assertNull(draft.state)
    }
    @Test fun stopFailureDoesNotCreateASavableRecordingOrLeakTheRecorder() {
        val file = temp.newFile()
        val rec = Recorder().apply { failStop = true }
        val draft = draft(file, rec)
        draft.start()
        assertFails { draft.finish() }
        assertNull(draft.state)
        assertFalse(draft.pending)
        assertFalse(file.exists())
        assertEquals(1, rec.releases)
    }
    @Test fun startFailureLeavesNoRecordingState() {
        val draft = SavedVoiceDraft({ error("permission denied") }, { time })
        assertFails { draft.start() }
        assertNull(draft.state)
        assertFalse(draft.pending)
        draft.discard()
    }
}
