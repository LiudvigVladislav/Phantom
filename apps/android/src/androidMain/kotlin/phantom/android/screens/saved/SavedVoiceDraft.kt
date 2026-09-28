// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.saved

import androidx.compose.runtime.*
import com.benasher44.uuid.uuid4
import java.io.File
import java.util.Base64
import phantom.android.screens.chat.RecordingPanelState
import phantom.core.messaging.VoiceMediaPolicy
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus

internal const val SAVED_CONV_ID = "saved_messages_local"

internal fun savedMessage(id: String, text: String, createdAt: Long) = MessageEntity(
    id = id, conversationId = SAVED_CONV_ID, ciphertext = ByteArray(0),
    plaintextCache = text, sent = true, status = MessageStatus.DELIVERED, createdAt = createdAt,
)

internal fun savedVoiceBody(bytes: ByteArray): String {
    require(bytes.isNotEmpty() && bytes.size <= VoiceMediaPolicy.MAX_PLAINTEXT_BYTES)
    return "[AUDIO:${Base64.getEncoder().encodeToString(bytes)}]"
}

internal interface LocalVoiceRecorder {
    fun stop()
    fun release()
    fun pause()
    fun resume()
    fun amplitude(): Int
}

/** Ephemeral draft only; the existing message row becomes authoritative after save. */
internal class SavedVoiceDraft(
    private val startRecorder: () -> Pair<File, LocalVoiceRecorder>,
    private val clock: () -> Long,
) {
    var state by mutableStateOf<RecordingPanelState?>(null)
        private set
    var pending by mutableStateOf(false)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var id = ""
        private set
    private var file: File? = null
    private var recorder: LocalVoiceRecorder? = null
    private var accumulated = 0L
    private var activeSince = 0L

    fun start(): Boolean {
        if (state != null || pending) return false
        val (newFile, newRecorder) = startRecorder()
        file = newFile
        recorder = newRecorder
        id = uuid4().toString()
        accumulated = 0L
        durationMs = 0L
        activeSince = clock()
        state = RecordingPanelState.Recording
        return true
    }

    fun tick() {
        durationMs = (accumulated + if (state == RecordingPanelState.Recording || state == RecordingPanelState.Locked)
            (clock() - activeSince).coerceAtLeast(0) else 0L).coerceAtMost(VoiceMediaPolicy.MAX_DURATION_MS)
    }
    fun amplitude(): Float = runCatching { (recorder?.amplitude() ?: 0) / 32768f }.getOrDefault(0f).coerceIn(0f, 1f)
    fun lock() { if (state == RecordingPanelState.Recording) state = RecordingPanelState.Locked }
    fun pause() {
        if (state != RecordingPanelState.Recording && state != RecordingPanelState.Locked) return
        recorder?.pause()
        tick()
        accumulated = durationMs
        state = RecordingPanelState.Paused
    }
    fun resume() {
        if (state != RecordingPanelState.Paused) return
        recorder?.resume()
        activeSince = clock()
        state = RecordingPanelState.Locked
    }

    fun finish(): File {
        if (pending) return checkNotNull(file)
        check(state != null)
        tick()
        val active = checkNotNull(recorder)
        recorder = null
        try {
            active.stop()
            pending = true
        } catch (error: Exception) {
            file?.delete()
            file = null
            throw error
        } finally {
            runCatching { active.release() }
            state = null
        }
        return checkNotNull(file)
    }

    fun discard() {
        val active = recorder
        recorder = null
        runCatching { active?.stop() }
        runCatching { active?.release() }
        file?.delete()
        file = null
        pending = false
        state = null
    }
}
