// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.saved

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import phantom.android.R
import phantom.android.screens.chat.*
import phantom.core.messaging.VoiceMediaPolicy

@Composable
internal fun SavedComposer(
    text: String,
    onTextChange: (String) -> Unit,
    isEditing: Boolean,
    onCancelEdit: () -> Unit,
    onSaveText: suspend (String) -> Unit,
    onSaveVoice: suspend (String, String) -> Unit,
    recording: SavedVoiceDraft? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val voice = recording ?: remember(context) {
        SavedVoiceDraft(startRecorder = {
            val (file, recorder) = startChatRecording(context)
            file to object : LocalVoiceRecorder {
                override fun stop() = recorder.stop()
                override fun release() = recorder.release()
                override fun pause() = recorder.pause()
                override fun resume() = recorder.resume()
                override fun amplitude() = recorder.maxAmplitude
            }
        }, clock = SystemClock::elapsedRealtime)
    }
    var emojiOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val amplitudes = remember { mutableStateListOf<Float>() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        error = if (granted) R.string.saved_mic_ready else R.string.saved_mic_denied
    }
    DisposableEffect(voice, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && voice.state != null) {
                voice.discard()
                error = R.string.saved_recording_interrupted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voice.discard()
        }
    }
    BackHandler(voice.state != null || voice.pending || emojiOpen) {
        if (!busy) { voice.discard(); emojiOpen = false; error = null }
    }

    fun saveVoice() {
        if (busy || (voice.state == null && !voice.pending)) return
        val file = try { voice.finish() } catch (_: Exception) {
            error = R.string.saved_recording_failed
            return
        }
        busy = true
        scope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    require(file.length() in 1..VoiceMediaPolicy.MAX_PLAINTEXT_BYTES.toLong())
                    savedVoiceBody(file.readBytes())
                }
                onSaveVoice(voice.id, body)
                voice.discard()
                error = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = R.string.saved_save_failed }
            finally { busy = false }
        }
    }
    LaunchedEffect(voice.state) {
        while (voice.state != null) {
            delay(100)
            voice.tick()
            amplitudes.add(voice.amplitude())
            while (amplitudes.size > 64) amplitudes.removeAt(0)
            if (voice.durationMs >= VoiceMediaPolicy.MAX_DURATION_MS - 1000L) saveVoice()
        }
    }
    Column {
        error?.let { Text(stringResource(it), modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (voice.pending && !busy) {
            Row {
                TextButton(onClick = ::saveVoice) { Text(stringResource(R.string.saved_retry)) }
                TextButton(onClick = { voice.discard(); error = null }) { Text(stringResource(R.string.saved_discard)) }
            }
        } else if (!busy) {
            if (isEditing) TextButton(onClick = onCancelEdit) { Text(stringResource(R.string.saved_cancel_edit)) }
            if (emojiOpen && voice.state == null) EmojiPanel(onEmoji = { onTextChange(text + it) })
            InputBar(
                text = text,
                onTextChange = onTextChange,
                onEmojiToggle = { emojiOpen = !emojiOpen },
                emojiPanelOpen = emojiOpen,
                isEditing = isEditing,
                actionLabel = stringResource(if (isEditing) R.string.saved_save_changes else R.string.saved_save_note),
                recordingState = voice.state,
                recordingDurationMs = voice.durationMs,
                waveformAmplitudes = amplitudes,
                onMicDownStartRecording = {
                    if (busy || text.isNotBlank() || isEditing) false
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                        false
                    } else try {
                        error = null
                        emojiOpen = false
                        amplitudes.clear()
                        voice.start()
                    } catch (_: Exception) { error = R.string.saved_recording_failed; false }
                },
                onMicHoldReleaseSend = { saveVoice() },
                onMicHoldTooShortCancel = { voice.discard() },
                onMicHoldSwipeCancel = { voice.discard() },
                onMicSlideUpLock = { voice.lock() },
                onSendVoiceTap = ::saveVoice,
                onCancelRecording = { voice.discard() },
                onPauseRecording = { runCatching { voice.pause() }.onFailure { voice.discard(); error = R.string.saved_recording_failed } },
                onResumeRecording = { runCatching { voice.resume() }.onFailure { voice.discard(); error = R.string.saved_recording_failed } },
                onSend = {
                    val captured = text.trim()
                    if (captured.isNotEmpty() && !busy) {
                        busy = true
                        scope.launch {
                            try { onSaveText(captured); error = null; emojiOpen = false }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = R.string.saved_save_failed }
                            finally { busy = false }
                        }
                    }
                },
            )
        }
    }
}
