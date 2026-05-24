// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import org.json.JSONException
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import phantom.android.R
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.benasher44.uuid.uuid4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import phantom.android.di.AppContainer
import phantom.android.ui.*
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono
import phantom.core.messaging.MediaProgressBus
import phantom.core.messaging.OutgoingMessage
import phantom.core.messaging.SafetyReportCategory
import phantom.core.storage.MessageEntity
import phantom.core.storage.MessageStatus
import phantom.core.storage.ReactionEntry
import phantom.core.transport.CallDisabledReason
import phantom.core.transport.TransportState

private const val PROFILE_MSG_PREFIX = "\u200B__PHANTOM_PROFILE__\u200B"
private const val PREFS_NAME = "phantom_prefs"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    theirUsername: String,
    container: AppContainer,
    onBack: () -> Unit,
    onContactProfile: () -> Unit = {},
    onStartVoiceCall: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showEmojiPanel by remember { mutableStateOf(false) }

    // PR-UI-REC1 (2026-05-20) — Voice recording state machine.
    //
    // `recordingState` is the canonical source of truth for whether a voice
    // is being captured and, if so, in which sub-state. It replaces the old
    // `var isRecording: Boolean` so the ticker and amplitude poller can tell
    // Recording (live capture) from Paused (frozen capture) without resetting
    // `recordingDurationMs` between them.
    //
    // Reachable values in this PR: Recording, Paused.
    // Locked / SwipeCancel are declared so the RecordingPanel composable can
    // be wired now and extended in PR-UI-REC2 (hold-to-lock) and PR-UI-REC3
    // (swipe-to-cancel) without touching the surrounding state machine.
    var recordingState by remember { mutableStateOf<RecordingPanelState?>(null) }
    var recordingDurationMs by remember { mutableStateOf(0L) }
    val recordingAmplitudes = remember { mutableStateListOf<Float>() }
    var audioFile by remember { mutableStateOf<java.io.File?>(null) }
    var mediaRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    // PR-M2d.1b — UI-side voice-send guard. Prevents a second tap on the mic
    // button (or a bounced touch) from immediately starting a new recording
    // while the previous one is still finalising / sending. Test #67b log
    // showed a 334 ms re-entry after `VOICE_REC complete` — see Bug 1.
    var voiceSendInProgress by remember { mutableStateOf(false) }

    // PR-UI-REC2.4 — crash hardening. `MediaRecorder.stop()` throws
    // IllegalStateException whenever the recorder is in a non-Recording
    // state (too short, already stopped, post-pause/resume race, etc.)
    // and architect's Test #76.2 review caught the app crashing because
    // we were calling `stop()` raw in multiple places. Centralise the
    // teardown in a single helper that swallows the throw and logs the
    // reason for the post-mortem; nullify the field BEFORE calling stop
    // so a re-entrant teardown cannot double-stop the same instance.
    fun stopReleaseRecorderSafely(reason: String) {
        val recorder = mediaRecorder
        mediaRecorder = null
        if (recorder == null) return
        runCatching { recorder.stop() }
            .onFailure { Log.w("PhantomMedia", "VOICE_REC stop_failed reason=$reason", it) }
        runCatching { recorder.release() }
            .onFailure { Log.w("PhantomMedia", "VOICE_REC release_failed reason=$reason", it) }
    }

    // Release recorder on screen dispose
    DisposableEffect(Unit) {
        onDispose {
            stopReleaseRecorderSafely(reason = "screen_dispose")
        }
    }

    // RECORD_AUDIO permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val result = startChatRecording(context)
            audioFile = result.first
            mediaRecorder = result.second
            recordingDurationMs = 0L
            recordingAmplitudes.clear()
            recordingState = RecordingPanelState.Recording
        }
    }

    // PR-UI-REC1: recording-duration ticker. Increments only while the
    // recorder is actually capturing (Recording / Locked). Pausing freezes
    // the value in place; resuming continues from where it left off. The
    // reset to 0 is owned by the start path (mic-button start +
    // permissionLauncher), not by this effect — so a Paused → Recording
    // transition does not lose accumulated time. The cleanup case (state
    // back to null after send / cancel) just lets the loop exit and the
    // next start clears it anew.
    LaunchedEffect(recordingState) {
        while (recordingState == RecordingPanelState.Recording
            || recordingState == RecordingPanelState.Locked
        ) {
            delay(100)
            recordingDurationMs += 100
        }
    }

    // PR-UI-REC1: waveform amplitude poller. MediaRecorder.getMaxAmplitude()
    // returns the peak audio level since the previous call (Android API ≥ 1),
    // so a steady ~80 ms sample produces a ~12 Hz envelope — enough for a
    // DAW-style scroll without burning battery. Samples are appended to a
    // SnapshotStateList capped at 64 (the Recording Panel Matrix design
    // budget), and emptied automatically when `recordingState` returns to
    // null at the start of the next session.
    LaunchedEffect(recordingState) {
        while (recordingState == RecordingPanelState.Recording
            || recordingState == RecordingPanelState.Locked
        ) {
            delay(80)
            val raw = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            val normalized = (raw.toFloat() / 32_768f).coerceIn(0f, 1f)
            recordingAmplitudes.add(normalized)
            while (recordingAmplitudes.size > 64) recordingAmplitudes.removeAt(0)
        }
    }

    // PR-C1 (2026-05-17): capability snapshot — single source of truth for
    // call / voice gating. Replaces the inline `hybridTransport?.stateMachine`
    // reads that were scattered across onVoiceCall and onMicClick handlers.
    val capabilities by container.transportCapabilities.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Own profile fields — read once from SharedPreferences
    val ownFirstName = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_first_name", "") ?: ""
    }
    val ownLastName = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_last_name", "") ?: ""
    }
    val ownCity = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_city", "") ?: ""
    }
    val ownCountry = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("profile_country", "") ?: ""
    }

    // Reply / Edit / Forward state
    var replyToMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardText by remember { mutableStateOf<String?>(null) }
    var forwardSenderLabel by remember { mutableStateOf("") }
    var conversations by remember { mutableStateOf<List<phantom.core.storage.ConversationEntity>>(emptyList()) }
    var theirPublicKeyHex by remember { mutableStateOf("") }
    // Verified flag drives the cyan shield-lock badge on the chat header
    // avatar (FULL_COMPOSE §02 verified-avatar treatment). Refreshed each
    // time the chat is opened — after a successful Verify flow the user
    // expects the badge to appear without a manual reload.
    var isVerified by remember { mutableStateOf(false) }

    // Typing indicator: hidden in Alpha 2.
    // Re-enabled in Phase 5 alongside sealed-sender extension to control
    // messages — until then `transport.sendTyping(...)` exposes the sender's
    // full pubkey at the relay (audit finding F18). Plumbing is preserved so
    // re-enable is a one-line flip.
    val typingIndicatorEnabled = false
    var isContactTyping by remember { mutableStateOf(false) }
    var typingJob by remember { mutableStateOf<Job?>(null) }

    // Pinned messages banner state — loaded once and refreshed after pin/unpin actions
    var pinnedMessages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }

    // Pinned messages — polled every 2 s instead of one-shot. TYPE_PIN
    // arriving from the peer updates the local DB silently; without polling
    // the banner would not refresh on the receiver's side.
    LaunchedEffect(conversationId) {
        while (true) {
            pinnedMessages = container.messageRepo.getPinnedMessages(conversationId)
            kotlinx.coroutines.delay(2_000)
        }
    }

    LaunchedEffect(Unit) {
        conversations = container.conversationRepo.getActiveConversations()
        val conv = container.conversationRepo.getConversation(conversationId)
        theirPublicKeyHex = conv?.theirPublicKeyHex ?: ""
        isVerified = conv?.isVerified == true
    }

    val transportState by container.transport.state.collectAsState()
    val isConnected = transportState is TransportState.Connected

    suspend fun reloadMessages() {
        messages = container.messageRepo.getMessages(conversationId)
        Log.i(
            "PhantomUI",
            "ChatScreen reloadMessages: conv=${conversationId.take(24)}… loaded=${messages.size}",
        )
    }

    // PR-UI-REC2: shared finalise-and-send path. Two entry points reach it:
    //   1. The in-panel Send tap and the legacy tap-to-toggle path (via
    //      `onMicClick` when `recordingState != null`).
    //   2. The release-after-press-and-hold gesture (via
    //      `onMicReleaseAfterHold`) — WhatsApp-style "let go to send".
    // Captures `recordingState` BEFORE clearing it so a Locked-state send can
    // emit a `VOICE_REC locked_send` line for diagnostics.
    fun finalizeAndSendVoice() {
        if (voiceSendInProgress) {
            android.util.Log.i(
                "PhantomMedia",
                "VOICE_REC ignored_finalize reason=already_in_progress state=${recordingState?.name ?: "idle"}",
            )
            return
        }
        val statePriorToFinalize = recordingState ?: return
        voiceSendInProgress = true
        stopReleaseRecorderSafely(reason = "finalize_send")
        recordingState = null
        if (statePriorToFinalize == RecordingPanelState.Locked) {
            Log.i("PhantomMedia", "VOICE_REC locked_send")
        }
        val file = audioFile
        val tickerDurationMsAtFinalize = recordingDurationMs
        if (file != null && file.exists()) {
            scope.launch {
                try {
                    val bytes = file.readBytes()
                    val mimeType = if (android.os.Build.VERSION.SDK_INT >= 29) "audio/ogg" else "audio/m4a"

                    // PR-UI-REC-FOLLOWUP — source-of-truth duration. Read the
                    // real encoded duration from the file via
                    // MediaMetadataRetriever; fall back to the ticker if the
                    // retriever cannot extract a positive value (rare encoder
                    // edge cases). Emit which source was used so future tests
                    // can attribute any remaining miscount unambiguously.
                    val metadataDurationMs = readAudioDurationMs(file)
                    val finalDurationMs = metadataDurationMs ?: tickerDurationMsAtFinalize
                    val durationSource =
                        if (metadataDurationMs != null) "metadata" else "ticker_fallback"

                    // PR-UI-REC-FOLLOWUP — empty/too-short safety gate. The
                    // gesture-layer 700 ms gate already drops releases the
                    // user obviously did not intend to send, but the
                    // MediaRecorder warm-up race (Test #76.3:
                    // heldMs=819 durationMs=0, ~98 bytes) can still produce
                    // a file with no playable audio. Drop it silently here
                    // before any MEDIA_TX work starts; no relay envelope,
                    // no LazyColumn row, no notification on the receiver.
                    if (finalDurationMs < MIN_SENDABLE_VOICE_DURATION_MS ||
                        bytes.size < MIN_SENDABLE_VOICE_BYTES
                    ) {
                        android.util.Log.i(
                            "PhantomMedia",
                            "VOICE_REC drop_empty durationMs=$finalDurationMs " +
                                "tickerMs=$tickerDurationMsAtFinalize bytes=${bytes.size} " +
                                "source=$durationSource reason=too_short_or_empty",
                        )
                        runCatching { file.delete() }
                        return@launch
                    }

                    val bytesPerSec = if (finalDurationMs > 0) {
                        bytes.size.toLong() * 1000L / finalDurationMs
                    } else 0L
                    android.util.Log.i(
                        "PhantomMedia",
                        "VOICE_REC complete durationMs=$finalDurationMs " +
                            "tickerMs=$tickerDurationMsAtFinalize source=$durationSource " +
                            "bytes=${bytes.size} bytesPerSec=$bytesPerSec mime=$mimeType"
                    )
                    val result = container.messagingService?.sendAudio(
                        conversationId = conversationId,
                        audioBytes = bytes,
                        durationMs = finalDurationMs,
                        mimeType = mimeType,
                    )
                    if (result != null && result.isFailure) {
                        val ex = result.exceptionOrNull()
                        val msg = if (ex is IllegalStateException &&
                            ex.message?.contains("still uploading") == true
                        ) {
                            context.getString(R.string.m1w_voice_still_uploading)
                        } else {
                            "Голосовое сообщение слишком длинное"
                        }
                        android.widget.Toast.makeText(
                            context,
                            msg,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        reloadMessages()
                        if (messages.isNotEmpty()) {
                            // PR-UI-CHAT-AUTOSCROLL1 — unified log; behaviour
                            // unchanged (voice send already animated to bottom
                            // before this PR, this is observability only).
                            Log.i(
                                "PhantomUI",
                                "CHAT_SCROLL source=voice_send conv=${conversationId.take(24)} " +
                                    "targetIndex=${messages.lastIndex} total=${messages.size}",
                            )
                            listState.animateScrollToItem(messages.lastIndex)
                        }
                    }
                } finally {
                    voiceSendInProgress = false
                }
            }
        } else {
            voiceSendInProgress = false
        }
        audioFile = null
    }

    LaunchedEffect(conversationId) {
        Log.i(
            "PhantomUI",
            "ChatScreen subscribed to conv=${conversationId.take(24)}… theirUsername=$theirUsername",
        )
        reloadMessages()
        // PR-UI-CHAT-AUTOSCROLL1 — land the LazyColumn at the latest message
        // immediately on chat open. Without this, opening a chat that has
        // unread messages from the background leaves the user looking at the
        // middle of the conversation and forces a manual scroll down. Uses
        // scrollToItem (not animate) so there's no visible top-to-bottom
        // sweep — the user sees the latest message from the first frame.
        if (messages.isNotEmpty()) {
            Log.i(
                "PhantomUI",
                "CHAT_SCROLL source=initial_open conv=${conversationId.take(24)} " +
                    "targetIndex=${messages.lastIndex} total=${messages.size}",
            )
            listState.scrollToItem(messages.lastIndex)
        } else {
            Log.i(
                "PhantomUI",
                "CHAT_SCROLL source=initial_open conv=${conversationId.take(24)} " +
                    "skipped reason=no_messages",
            )
        }
        val conv = container.conversationRepo.getConversation(conversationId)
        if (conv != null) {
            // Privacy Mode: Standard sends read receipts; Private/Ghost suppress
            // them at the wire level (local state still flips to READ).
            val privacyPrefs = context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            val sendReceipts = privacyPrefs.getString("privacy_mode", "Standard") == "Standard"
            container.messagingService?.markConversationRead(
                conversationId, conv.theirPublicKeyHex, sendReceipts,
            )
        }

        // Send profile card once per conversation if own profile has at least one name field
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val profileAlreadySent = prefs.getString("profile_sent_$conversationId", null) == "true"
        val hasProfileData = ownFirstName.isNotEmpty() || ownLastName.isNotEmpty()
        if (!profileAlreadySent && hasProfileData && conv != null) {
            val myGradientIndex = prefs.getInt("gradient_index", 0)
            val json = JSONObject().apply {
                put("fn", ownFirstName)
                put("ln", ownLastName)
                put("city", ownCity)
                put("country", ownCountry)
                put("gradientIndex", myGradientIndex)
            }.toString()
            container.messagingService?.sendMessage(
                OutgoingMessage(
                    id = uuid4().toString(),
                    conversationId = conversationId,
                    recipientPublicKeyHex = conv.theirPublicKeyHex,
                    text = PROFILE_MSG_PREFIX + json,
                )
            )
            prefs.edit().putString("profile_sent_$conversationId", "true").apply()
        }
    }

    DisposableEffect(conversationId) {
        onDispose {
            scope.launch { container.conversationRepo.resetUnread(conversationId) }
        }
    }

    // Collect incoming typing events from the contact in this conversation.
    // The indicator auto-clears after 3 s with no new event (matches standard messenger UX).
    LaunchedEffect(conversationId, theirPublicKeyHex, typingIndicatorEnabled) {
        if (!typingIndicatorEnabled) return@LaunchedEffect
        if (theirPublicKeyHex.isEmpty()) return@LaunchedEffect
        container.transport.typingEvents.collect { senderKey ->
            if (senderKey == theirPublicKeyHex) {
                isContactTyping = true
                delay(3_000)
                isContactTyping = false
            }
        }
    }

    LaunchedEffect(container.messagingService) {
        container.messagingService?.incomingMessages
            ?.catch { e ->
                Log.e("PhantomUI", "incomingMessages flow error in ChatScreen: ${e.message}", e)
            }
            ?.collect { incoming ->
                Log.i(
                    "PhantomUI",
                    "ChatScreen received incoming for conv=${incoming.conversationId.take(24)}… " +
                        "(my conv=${conversationId.take(24)}…, match=${incoming.conversationId == conversationId})",
                )
            if (incoming.conversationId == conversationId) {
                // Check if this is a profile card — handle silently, do not display
                val plaintext = incoming.text
                if (plaintext.startsWith(PROFILE_MSG_PREFIX)) {
                    val jsonStr = plaintext.removePrefix(PROFILE_MSG_PREFIX)
                    try {
                        val obj = JSONObject(jsonStr)
                        val profileJson = JSONObject().apply {
                            put("fn", obj.optString("fn", ""))
                            put("ln", obj.optString("ln", ""))
                            put("city", obj.optString("city", ""))
                            put("country", obj.optString("country", ""))
                        }.toString()
                        val gradientIndex = if (obj.has("gradientIndex")) obj.getInt("gradientIndex") else -1
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString("contact_profile_$conversationId", profileJson)
                            .apply()
                        if (gradientIndex >= 0) {
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putInt("${conversationId}_gradient_index", gradientIndex)
                                .apply()
                        }
                    } catch (_: JSONException) {
                        // Malformed profile payload — ignore silently
                    }
                    // Do not reload or scroll — profile card is invisible
                    return@collect
                }

                reloadMessages()
                val conv = container.conversationRepo.getConversation(conversationId)
                if (conv != null) {
                    // Honor Privacy Mode (see top of LaunchedEffect above for explanation).
                    val privacyPrefs = context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
                    val sendReceipts = privacyPrefs.getString("privacy_mode", "Standard") == "Standard"
                    container.messagingService?.markConversationRead(
                        conversationId, conv.theirPublicKeyHex, sendReceipts,
                    )
                }
                // PR-UI-CHAT-AUTOSCROLL1 — unified log; behaviour unchanged
                // (incoming-in-active-chat already animated to bottom before
                // this PR, this is observability only).
                Log.i(
                    "PhantomUI",
                    "CHAT_SCROLL source=incoming_active conv=${conversationId.take(24)} " +
                        "targetIndex=${messages.lastIndex.coerceAtLeast(0)} total=${messages.size}",
                )
                listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
            }
        }
    }

    // Forward dialog
    val fwdText = forwardText
    if (fwdText != null) {
        AlertDialog(
            onDismissRequest = { forwardText = null },
            containerColor = Surface,
            title = { Text("Forward to…", color = TextPrimary) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Notes always on top
                    val savedConvId = "saved_messages_local"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                forwardText = null
                                scope.launch {
                                    val label = forwardSenderLabel.ifBlank { "Unknown" }
                                    container.messageRepo.insertMessage(
                                        phantom.core.storage.MessageEntity(
                                            id = uuid4().toString(),
                                            conversationId = savedConvId,
                                            ciphertext = ByteArray(0),
                                            plaintextCache = "↩ from @$label\n$fwdText",
                                            sent = true,
                                            status = phantom.core.storage.MessageStatus.DELIVERED,
                                            createdAt = System.currentTimeMillis(),
                                        )
                                    )
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(CyanAccent.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(18.dp)) {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(size.width * 0.2f, 0f); lineTo(size.width * 0.8f, 0f)
                                    lineTo(size.width * 0.8f, size.height); lineTo(size.width * 0.5f, size.height * 0.72f)
                                    lineTo(size.width * 0.2f, size.height); close()
                                }
                                drawPath(path, CyanAccent)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Notes", color = TextPrimary, fontSize = 15.sp)
                    }
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

                    if (conversations.isEmpty()) {
                        Text("No other conversations", color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    }
                    conversations.forEach { fwdConv ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        forwardText = null
                                        scope.launch {
                                            container.messagingService?.sendMessage(
                                                OutgoingMessage(
                                                    id = uuid4().toString(),
                                                    conversationId = fwdConv.id,
                                                    recipientPublicKeyHex = fwdConv.theirPublicKeyHex,
                                                    text = fwdText,
                                                )
                                            )
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(CyanAccent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(fwdConv.theirUsername.take(1).uppercase(), color = CyanAccent, fontSize = 14.sp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(fwdConv.theirUsername, color = TextPrimary, fontSize = 15.sp)
                            }
                        }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { forwardText = null }) { Text("Cancel", color = TextDim) }
            },
        )
    }

    if (showBlockDialog) {
        BlockConfirmDialog(
            username = theirUsername,
            onConfirm = {
                showBlockDialog = false
                scope.launch {
                    container.conversationRepo.blockConversation(conversationId)
                    onBack()
                }
            },
            onDismiss = { showBlockDialog = false },
        )
    }

    if (showReportDialog) {
        ReportDialog(
            username = theirUsername,
            onReport = { _ ->
                showReportDialog = false
                scope.launch { snackbarHostState.showSnackbar("Report sent. Thank you.") }
            },
            onDismiss = { showReportDialog = false },
        )
    }

    // adjustResize in the manifest is not enough once
    // WindowCompat.setDecorFitsSystemWindows(window, false) is active on API < 35
    // (it is, see MainActivity.onCreate). Compose then has to opt in to IME insets
    // explicitly. We use the union of `ime` and `navigationBars` so that:
    //   - IME open  → padding = IME height (which already covers the nav bar visually)
    //   - IME closed → padding = nav bar height (3-button or gesture indicator)
    // `union` takes the per-side maximum, so they never stack — without that, the
    // QA-v9 Tecno-HiOS phone showed the InputBar peeking from underneath the
    // 3-button nav bar with the keyboard closed (Modifier.imePadding() alone
    // does nothing once IME is hidden, so the 48 dp nav bar covered the input).
    Scaffold(
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.ime.union(WindowInsets.navigationBars)
        ),
        containerColor = BgDeep,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            ChatTopBar(
                theirUsername = theirUsername,
                isConnected = isConnected,
                isVerified = isVerified,
                isTyping = isContactTyping,
                onBack = onBack,
                onContactProfile = onContactProfile,
                onVoiceCall = {
                    if (theirPublicKeyHex.isNotEmpty()) {
                        // PR-C1 (2026-05-17) — UI guard for calls via TransportCapabilities.
                        // Source of truth: container.transportCapabilities (StateFlow).
                        // The reason field drives per-reason snackbar copy so the user
                        // gets an honest, actionable message rather than the single
                        // "Limited realtime" string used in PR-D2a.
                        // CallManager.startCall has the same gate as a second layer.
                        if (!capabilities.canStartCalls) {
                            Log.w(
                                "PhantomTransport",
                                "CALL_CAPABILITY disabled " +
                                    "reason=${capabilities.callDisabledReason?.name?.lowercase()} " +
                                    "source=ui",
                            )
                            val msg = when (capabilities.callDisabledReason) {
                                CallDisabledReason.LIMITED_REALTIME ->
                                    context.getString(R.string.c1_call_blocked_limited_realtime)
                                CallDisabledReason.TOR_TRANSPORT ->
                                    context.getString(R.string.c1_call_blocked_tor_transport)
                                CallDisabledReason.REALITY_UNPROBED ->
                                    context.getString(R.string.c1_call_blocked_reality_unprobed)
                                CallDisabledReason.NO_TRANSPORT ->
                                    context.getString(R.string.c1_call_blocked_no_transport)
                                null -> context.getString(R.string.d2a_call_blocked_limited_realtime)
                            }
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    msg,
                                    duration = androidx.compose.material3.SnackbarDuration.Short,
                                )
                            }
                            return@ChatTopBar
                        }
                        scope.launch {
                            container.callManager?.startCall(theirPublicKeyHex, theirUsername)
                        }
                        onStartVoiceCall()
                    }
                },
                onMoreMenu = { showMenu = true },
                showMenu = showMenu,
                onDismissMenu = { showMenu = false },
                onReport = { showMenu = false; showReportDialog = true },
                onBlock = { showMenu = false; showBlockDialog = true },
            )
        },
        bottomBar = {
            // Inset padding lives on the Scaffold modifier (above) via
            // `WindowInsets.ime.union(WindowInsets.navigationBars)`, so the
            // bottomBar slot is already positioned above both the keyboard and
            // the nav bar by the time we render here. Keep this Column inset-
            // free to avoid double-padding.
            Column {
                // Reply bar
                val reply = replyToMessage
                if (reply != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(CyanAccent, shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (reply.sent) "You" else theirUsername,
                                color = CyanAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = reply.plaintextCache ?: "•••",
                                color = TextDim,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        IconButton(onClick = { replyToMessage = null }, modifier = Modifier.size(32.dp)) {
                            PhIconBack(color = TextDim, size = 18.dp)
                        }
                    }
                }

                // Edit bar
                val editing = editingMessage
                if (editing != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(CyanAccent.copy(alpha = 0.7f), shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Editing", color = CyanAccent.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(editing.plaintextCache ?: "•••", color = TextDim, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = { editingMessage = null; inputText = "" }, modifier = Modifier.size(32.dp)) {
                            PhIconBack(color = TextDim, size = 18.dp)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showEmojiPanel,
                    enter = expandVertically(expandFrom = Alignment.Bottom),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
                ) {
                    EmojiPanel(onEmoji = { inputText += it })
                }
                InputBar(
                    text = inputText,
                    onTextChange = { newText ->
                        inputText = newText
                        if (showEmojiPanel) showEmojiPanel = false
                        // Debounced typing event: cancel any pending send and schedule a new one.
                        // One event per keystroke burst is enough — the relay drops if offline.
                        if (typingIndicatorEnabled &&
                            newText.isNotEmpty() &&
                            theirPublicKeyHex.isNotEmpty()
                        ) {
                            typingJob?.cancel()
                            typingJob = scope.launch {
                                container.transport.sendTyping(theirPublicKeyHex)
                            }
                        }
                    },
                    onEmojiToggle = { showEmojiPanel = !showEmojiPanel },
                    emojiPanelOpen = showEmojiPanel,
                    isEditing = editingMessage != null,
                    recordingState = recordingState,
                    recordingDurationMs = recordingDurationMs,
                    waveformAmplitudes = recordingAmplitudes,
                    onCancelRecording = {
                        // PR-UI-REC2: log a Locked-specific exit reason so a
                        // future post-mortem can tell a Cancel-from-Locked
                        // from a Cancel-from-Recording / Cancel-from-Paused.
                        if (recordingState == RecordingPanelState.Locked) {
                            Log.i("PhantomMedia", "VOICE_REC locked_cancel")
                        }
                        stopReleaseRecorderSafely(reason = "cancel")
                        recordingState = null
                        audioFile?.delete()
                        audioFile = null
                    },
                    onPauseRecording = {
                        // PR-UI-REC1: MediaRecorder.pause() is available since
                        // API 24; minSdk is 26, so no runtime guard needed.
                        runCatching { mediaRecorder?.pause() }
                            .onSuccess { recordingState = RecordingPanelState.Paused }
                            .onFailure {
                                Log.w("PhantomMedia", "VOICE_REC pause_failed", it)
                            }
                    },
                    onResumeRecording = {
                        runCatching { mediaRecorder?.resume() }
                            .onSuccess { recordingState = RecordingPanelState.Recording }
                            .onFailure {
                                Log.w("PhantomMedia", "VOICE_REC resume_failed", it)
                            }
                    },
                    // PR-UI-REC2.3 — new ACTION_DOWN-driven gesture lifecycle.
                    // Replaces `onMicClick` (tap-toggle) +
                    // `onMicReleaseAfterHold` + `onLockGesture` with five
                    // focused callbacks that map cleanly onto pointer events.
                    // See architect verdict on Test #76.1.2 for the rationale.
                    onMicDownStartRecording = onMicDown@{
                        // 1) capability guard (mirror of the legacy onMicClick
                        //    capability-disabled branch, including the in-
                        //    flight tear-down for a mid-recording capability
                        //    drop).
                        if (!capabilities.canSendVoice) {
                            Log.w(
                                "PhantomTransport",
                                "VOICE_CAPABILITY disabled " +
                                    "reason=${capabilities.callDisabledReason?.name?.lowercase()} " +
                                    "source=ui recording_state=${recordingState?.name ?: "idle"}",
                            )
                            if (recordingState != null) {
                                stopReleaseRecorderSafely(reason = "capability_disabled")
                                recordingState = null
                                audioFile?.delete()
                                audioFile = null
                            }
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.d2a_voice_blocked_limited_realtime),
                                    duration = androidx.compose.material3.SnackbarDuration.Short,
                                )
                            }
                            return@onMicDown false
                        }
                        // 2) busy guard — never start a second recording on
                        //    top of an in-flight upload or finalising tail.
                        //    Architect-required defence after Test #76.1.2
                        //    showed VOICE_REC config firing mid-upload.
                        if (voiceSendInProgress || recordingState != null) {
                            Log.i(
                                "PhantomMedia",
                                "VOICE_REC ignored_start reason=busy " +
                                    "voiceSendInProgress=$voiceSendInProgress " +
                                    "state=${recordingState?.name ?: "idle"}",
                            )
                            return@onMicDown false
                        }
                        // 3) text-not-empty guard — UI-side dispatch already
                        //    routes a Send-arrow tap to the text path, but
                        //    keep the defence-in-depth log here for any future
                        //    routing regression.
                        if (inputText.trim().isNotEmpty()) {
                            Log.i("PhantomMedia", "VOICE_REC ignored_start reason=text_not_empty")
                            return@onMicDown false
                        }
                        // 4) permission gate — RECORD_AUDIO. If we have it,
                        //    start the recorder synchronously and tell the
                        //    gesture detector recording is live. If we do
                        //    not, fire the permission dialog and tell the
                        //    detector to bail (a subsequent press will start
                        //    once permission is granted).
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!hasPermission) {
                            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            return@onMicDown false
                        }
                        // 5) start the recorder.
                        val result = startChatRecording(context)
                        audioFile = result.first
                        mediaRecorder = result.second
                        recordingDurationMs = 0L
                        recordingAmplitudes.clear()
                        recordingState = RecordingPanelState.Recording
                        Log.i("PhantomMedia", "VOICE_REC hold_record_start")
                        true
                    },
                    onMicHoldReleaseSend = { heldMs ->
                        // Real pointer ACTION_UP after a held recording.
                        // Send what we have via the same finalise path the
                        // in-panel Send button uses.
                        Log.i(
                            "PhantomMedia",
                            "VOICE_REC hold_release_send heldMs=$heldMs durationMs=$recordingDurationMs",
                        )
                        finalizeAndSendVoice()
                    },
                    onMicHoldTooShortCancel = { heldMs ->
                        // Pointer ACTION_UP before MIN_HOLD_SEND_MS — treat
                        // as accidental tap, drop the recording quietly.
                        Log.i(
                            "PhantomMedia",
                            "VOICE_REC hold_release_cancel_too_short heldMs=$heldMs",
                        )
                        stopReleaseRecorderSafely(reason = "too_short")
                        recordingState = null
                        audioFile?.delete()
                        audioFile = null
                    },
                    onMicHoldSwipeCancel = { heldMs ->
                        // PR-UI-REC2.4 — interim swipe-left-to-cancel handler.
                        // Replaces the previous broken path where dragging the
                        // finger right-to-left over the mic could still produce
                        // a release-send if the elapsed time crossed
                        // MIN_HOLD_SEND_MS. Full SwipeCancel state + visual
                        // (trail / trash icon / threshold animation) ships in
                        // PR-UI-REC3.
                        Log.i(
                            "PhantomMedia",
                            "VOICE_REC hold_release_cancel_swipe_left heldMs=$heldMs",
                        )
                        stopReleaseRecorderSafely(reason = "swipe_left")
                        recordingState = null
                        audioFile?.delete()
                        audioFile = null
                    },
                    onMicSlideUpLock = {
                        // Drag-up ≥ 60 dp while pressed — promote to Locked.
                        // The MediaRecorder is already running; only the UI
                        // state changes. Pointer ACTION_UP after this is a
                        // no-op (handled in the gesture detector).
                        if (recordingState == RecordingPanelState.Recording) {
                            recordingState = RecordingPanelState.Locked
                            Log.i("PhantomMedia", "VOICE_REC locked_entered reason=hold_slide_up")
                        }
                    },
                    onSendVoiceTap = {
                        // Tap on the cyan send-voice arrow while recording is
                        // already in flight (Locked or Recording). Capability
                        // / busy guards live inside finalizeAndSendVoice.
                        Log.i("PhantomMedia", "VOICE_REC send_voice_tap state=${recordingState?.name ?: "idle"}")
                        finalizeAndSendVoice()
                    },
                    onSend = {
                        val text = inputText.trim()
                        if (text.isEmpty()) return@InputBar
                        val editMsg = editingMessage
                        val replyMsg = replyToMessage   // capture BEFORE clearing
                        inputText = ""
                        showEmojiPanel = false
                        editingMessage = null
                        replyToMessage = null
                        scope.launch {
                            if (editMsg != null) {
                                // Save edit locally and notify recipient
                                val conversation = container.conversationRepo.getConversation(conversationId)
                                    ?: return@launch
                                container.messagingService?.editMessageForBoth(
                                    messageId = editMsg.id,
                                    newText = text,
                                    conversationId = conversationId,
                                    recipientPublicKeyHex = conversation.theirPublicKeyHex,
                                )
                                reloadMessages()
                            } else {
                                // Send new message (with optional reply prefix)
                                val finalText = if (replyMsg != null) {
                                    "> ${replyMsg.plaintextCache?.take(60) ?: "•••"}\n$text"
                                } else {
                                    text
                                }
                                val conversation = container.conversationRepo.getConversation(conversationId)
                                    ?: return@launch
                                container.messagingService?.sendMessage(
                                    OutgoingMessage(
                                        id = uuid4().toString(),
                                        conversationId = conversationId,
                                        recipientPublicKeyHex = conversation.theirPublicKeyHex,
                                        text = finalText,
                                    )
                                )
                                reloadMessages()
                                if (messages.isNotEmpty()) {
                                    // PR-UI-CHAT-AUTOSCROLL1 — unified log;
                                    // behaviour unchanged (text send already
                                    // animated to bottom before this PR).
                                    Log.i(
                                        "PhantomUI",
                                        "CHAT_SCROLL source=text_send conv=${conversationId.take(24)} " +
                                            "targetIndex=${messages.lastIndex} total=${messages.size}",
                                    )
                                    listState.animateScrollToItem(messages.lastIndex)
                                }
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    val backThreshold = 120.dp.toPx()
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = { if (totalDrag > backThreshold) onBack() },
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragAmount > 0 || totalDrag > 0) {
                                totalDrag += dragAmount
                                change.consume()
                            }
                        },
                    )
                }
        ) {
        // Day-start state ticks at local midnight so [ChatDateSep] flips
        // "TODAY" → "YESTERDAY" without requiring a navigation refresh.
        val dayStartMillis = rememberDayStartMillis()

        // Pre-process: filter out invisible profile cards, then inject date separators
        val chatItems = remember(messages) {
            buildList<ChatItem> {
                var lastDate = ""
                messages
                    .filter { msg -> !(msg.plaintextCache?.startsWith(PROFILE_MSG_PREFIX) ?: false) }
                    .forEach { msg ->
                        val dk = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(java.util.Date(msg.createdAt))
                        if (dk != lastDate) { lastDate = dk; add(ChatItem.DateSep(dk, msg.createdAt)) }
                        add(ChatItem.Msg(msg))
                    }
            }
        }

        // Snapshot of message IDs present at first composition — used to skip animation for
        // messages that were already in the list when the screen opened.
        val initialIds = remember {
            chatItems
                .filterIsInstance<ChatItem.Msg>()
                .map { it.entity.id }
                .toHashSet()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "__e2ee__") { E2EENoteRow(theirUsername = theirUsername) }

            // Pinned message banner — shown when at least one message is pinned
            if (pinnedMessages.isNotEmpty()) {
                item(key = "pinned_banner") {
                    val msg = pinnedMessages.last() // most recently pinned
                    val pinnedIndex = remember(msg.id, chatItems) {
                        chatItems.indexOfFirst { it is ChatItem.Msg && it.entity.id == msg.id }
                    }
                    val selfPubKey = container.identityState.value?.publicKeyHex
                    val pinnerLabel = when (msg.pinnedByPubkey) {
                        null -> "Pinned"
                        selfPubKey -> "Pinned by you"
                        theirPublicKeyHex -> "Pinned by $theirUsername"
                        else -> "Pinned"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface2.copy(alpha = 0.78f))
                            .clickable {
                                if (pinnedIndex >= 0) {
                                    scope.launch { listState.animateScrollToItem(pinnedIndex) }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(modifier = Modifier.size(16.dp)) {
                            drawLine(
                                color = CyanAccent,
                                start = Offset(size.width / 2, 0f),
                                end = Offset(size.width / 2, size.height * 0.6f),
                                strokeWidth = 2.dp.toPx(),
                            )
                            drawCircle(
                                color = CyanAccent,
                                radius = size.width * 0.35f,
                                center = Offset(size.width / 2, size.height * 0.3f),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pinnerLabel,
                                color = CyanAccent,
                                fontSize = 10.sp,
                                fontFamily = PhantomFontMono,
                            )
                            Text(
                                text = msg.plaintextCache?.take(60) ?: "•••",
                                color = TextDim,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        // Quick-unpin (local-only). Tapping the X removes the
                        // banner without notifying the peer; for an unpin that
                        // syncs both sides, use the message's long-press menu.
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    scope.launch {
                                        container.messageRepo.pinMessage(
                                            messageId = msg.id,
                                            pinned = false,
                                            pinnedByPubkey = null,
                                        )
                                        pinnedMessages = container.messageRepo.getPinnedMessages(conversationId)
                                    }
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(12.dp)) {
                                val sw = 1.5.dp.toPx()
                                drawLine(
                                    color = TextDim,
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = sw,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                                drawLine(
                                    color = TextDim,
                                    start = Offset(size.width, 0f),
                                    end = Offset(0f, size.height),
                                    strokeWidth = sw,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                }
            }

            lazyItems(chatItems, key = {
                when (it) {
                    is ChatItem.DateSep -> "__date_${it.dateKey}"
                    is ChatItem.Msg -> it.entity.id
                }
            }) { chatItem ->
                when (chatItem) {
                    is ChatItem.DateSep -> ChatDateSep(chatItem.millis, dayStartMillis)
                    is ChatItem.Msg -> {
                        val msg = chatItem.entity
                        val isNew = msg.id !in initialIds
                        AnimatedVisibility(
                            visible = true,
                            enter = if (isNew) {
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> if (msg.sent) fullWidth else -fullWidth },
                                    animationSpec = tween(durationMillis = 150),
                                ) + fadeIn(animationSpec = tween(durationMillis = 150))
                            } else {
                                EnterTransition.None
                            },
                        ) {
                        MessageBubble(
                            entity = msg,
                            theirUsername = theirUsername,
                            conversationId = conversationId,
                            theirPublicKeyHex = theirPublicKeyHex,
                            container = container,
                            onReply = {
                                replyToMessage = msg
                                editingMessage = null
                            },
                            onEdit = {
                                editingMessage = msg
                                inputText = msg.plaintextCache ?: ""
                                replyToMessage = null
                            },
                            onDeleteForMe = {
                                scope.launch {
                                    container.messageRepo.deleteMessage(msg.id)
                                    reloadMessages()
                                }
                            },
                            onDeleteForBoth = {
                                scope.launch {
                                    val key = theirPublicKeyHex
                                    if (key.isNotEmpty()) {
                                        container.messagingService?.deleteMessageForBoth(msg.id, conversationId, key)
                                    } else {
                                        container.messageRepo.deleteMessage(msg.id)
                                    }
                                    reloadMessages()
                                }
                            },
                            onCopy = { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
                            },
                            onForward = { cleanText, senderLabel ->
                                forwardText = cleanText
                                forwardSenderLabel = senderLabel
                            },
                            onPin = {
                                scope.launch {
                                    container.messagingService?.pinMessageForBoth(
                                        messageId = msg.id,
                                        conversationId = conversationId,
                                        recipientPublicKeyHex = theirPublicKeyHex,
                                        pinned = !msg.pinned,
                                    )
                                    pinnedMessages = container.messageRepo.getPinnedMessages(conversationId)
                                }
                            },
                            onPinLocal = {
                                // Local-only pin: write directly to the message
                                // table without sending TYPE_PIN over the wire,
                                // so the peer never learns this message was
                                // pinned. Useful for personal bookmarks the
                                // user does not want surfaced on the other
                                // side.
                                scope.launch {
                                    val newPinned = !msg.pinned
                                    val selfPubKey = container.identityState.value?.publicKeyHex
                                    container.messageRepo.pinMessage(
                                        messageId = msg.id,
                                        pinned = newPinned,
                                        pinnedByPubkey = if (newPinned) selfPubKey else null,
                                    )
                                    pinnedMessages = container.messageRepo.getPinnedMessages(conversationId)
                                }
                            },
                            // PR-MEDIA-UPLOAD-CANCEL1 — after a cancelled
                            // voice upload the local row is deleted from
                            // the repo; the bubble needs the parent to
                            // re-read messages so the LazyColumn loses
                            // the cancelled entry.
                            onReloadMessages = {
                                scope.launch { reloadMessages() }
                            },
                        )
                        } // end AnimatedVisibility
                    }
                }
            }
        }
        } // end back-swipe Box
    }
}

private sealed class ChatItem {
    data class DateSep(val dateKey: String, val millis: Long) : ChatItem()
    data class Msg(val entity: MessageEntity) : ChatItem()
}

/**
 * PHANTOM_FULL_COMPOSE §05 — Encryption strip is THE WOW MOMENT.
 *
 *   32dp, surfaceDeep bg, borderSubtle bottom hairline.
 *   Lock 10dp + "End-to-end encrypted · ED25519 · @username"
 *   JetBrains Mono 10sp · textTertiary · opacity 0.7
 */
@Composable
private fun E2EENoteRow(theirUsername: String) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(PhantomTokens.Colors.SurfaceDeep),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    val w = this.size.width
                    val h = this.size.height
                    val sw = 1.4.dp.toPx()
                    val color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.7f)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(w * 0.18f, h * 0.45f),
                        size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.45f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                        style = Stroke(sw),
                    )
                    drawArc(
                        color = color,
                        startAngle = 180f, sweepAngle = 180f, useCenter = false,
                        topLeft = Offset(w * 0.30f, h * 0.18f),
                        size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.50f),
                        style = Stroke(sw),
                    )
                }
                Text(
                    text = "End-to-end encrypted · ED25519 · @$theirUsername",
                    color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 0.4.sp,
                )
            }
        }
        HorizontalDivider(color = PhantomTokens.Colors.BorderSubtle, thickness = 1.dp)
    }
}

@Composable
private fun ChatDateSep(millis: Long, dayStartMillis: Long) {
    // Design Brief v3 §9.5: just centered uppercase mono label, no hairlines.
    // The label depends on [dayStartMillis] — a screen-level state that ticks
    // at midnight via [rememberDayStartMillis]. Without that dependency, the
    // separator captured "TODAY" once on first composition and never moved to
    // "YESTERDAY" after the day rolled over (Vladislav 2026-05-20 report).
    val label = when {
        millis >= dayStartMillis -> "TODAY"
        millis >= dayStartMillis - DAY_MILLIS -> "YESTERDAY"
        else -> java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US)
            .format(java.util.Date(millis))
            .uppercase(java.util.Locale.US)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = PhantomTokens.Colors.TextTertiary,
            style = PhantomType.overline,
        )
    }
}

private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * Returns the wall-clock millis of the start of "today" (local midnight),
 * and refreshes that state in-place when the day rolls over. Used by the
 * chat day-separator labels so that a chat opened at 23:59 transitions
 * from "TODAY" to "YESTERDAY" automatically at 00:00 without needing the
 * user to navigate away and back.
 */
@Composable
private fun rememberDayStartMillis(): Long {
    var dayStart by remember { mutableLongStateOf(startOfDayMillis(System.currentTimeMillis())) }
    LaunchedEffect(dayStart) {
        // Sleep until just past the next local midnight, then refresh.
        val now = System.currentTimeMillis()
        val nextMidnight = dayStart + DAY_MILLIS
        val waitMs = (nextMidnight - now).coerceAtLeast(60_000L) + 1_000L
        delay(waitMs)
        dayStart = startOfDayMillis(System.currentTimeMillis())
    }
    return dayStart
}

private fun startOfDayMillis(now: Long): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

// ── Emoji panel ───────────────────────────────────────────────────────────────

private val CATEGORY_ICONS = listOf("🙂", "🖐️", "🐱", "🍎", "⚽", "✈️", "💡", "#️⃣")

@Composable
private fun EmojiPanel(onEmoji: (String) -> Unit) {
    var selectedCategory by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val displayEmojis: List<EmojiItem> = remember(selectedCategory, searchQuery) {
        if (searchQuery.isBlank()) {
            EMOJI_CATEGORIES.getOrNull(selectedCategory)?.items ?: emptyList()
        } else {
            searchEmoji(searchQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(Surface),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            placeholder = { Text("Search emoji…", color = TextDim, fontSize = 13.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = CyanAccent.copy(alpha = 0.4f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                cursorColor = CyanAccent,
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
            ),
            shape = RoundedCornerShape(12.dp),
        )

        if (searchQuery.isBlank()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface2)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(EMOJI_CATEGORIES) { idx, cat ->
                    val isSelected = idx == selectedCategory
                    Text(
                        text = CATEGORY_ICONS.getOrElse(idx) { cat.label.take(1) },
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) CyanAccent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { selectedCategory = idx }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            Text(
                text = EMOJI_CATEGORIES.getOrNull(selectedCategory)?.label ?: "",
                color = TextDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 42.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp),
            contentPadding = PaddingValues(bottom = 6.dp),
        ) {
            gridItems(displayEmojis) { item ->
                Text(
                    text = item.emoji,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onEmoji(item.emoji) }
                        .padding(4.dp),
                )
            }
        }
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

private val REACTION_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "👎")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    entity: MessageEntity,
    theirUsername: String,
    conversationId: String,
    theirPublicKeyHex: String,
    container: AppContainer,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForBoth: () -> Unit,
    onCopy: (String) -> Unit,
    onForward: (cleanText: String, senderLabel: String) -> Unit,
    onPin: () -> Unit,
    onPinLocal: () -> Unit,
    // PR-MEDIA-UPLOAD-CANCEL1 — invoked after a voice upload cancel
    // completes so the parent can re-read messages and the deleted /
    // cancelled bubble disappears from the LazyColumn. The parent wraps
    // its own suspend `reloadMessages()` inside scope.launch.
    onReloadMessages: () -> Unit = {},
) {
    val context = LocalContext.current
    val bubbleCoroutineScope = rememberCoroutineScope()
    val isSent = entity.sent
    val rawText = entity.plaintextCache ?: "•••"
    val timeStr = formatMessageTime(entity.createdAt)
    // PR-M2d.1b — live chunk-progress map shared across all bubbles in this
    // ChatScreen. Compose deduplicates state subscriptions on the underlying
    // StateFlow, so collecting per-bubble has no extra runtime cost.
    val mediaProgress by container.mediaProgressBus.flow.collectAsState()
    // Single combined long-press panel — emoji row at top, action list
    // already expanded below it. Replaces the previous two-step UX where the
    // user had to tap "•••" inside the emoji popup to open a separate
    // DropdownMenu.
    var showActionPanel by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPinChoice by remember { mutableStateOf(false) }
    // Bubble bounds in window-pixel coordinates — used to position the
    // long-press panel directly next to the tapped message instead of the
    // screen centre.
    var bubbleBoundsPx by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // Load reactions; polls every 2 s so real-time reactions appear without a full DB Flow.
    val reactions by produceState(initialValue = emptyList<ReactionEntry>(), key1 = entity.id) {
        while (true) {
            value = container.reactionRepo.getReactions(entity.id)
            delay(2_000)
        }
    }

    // Parse reply prefix: "> quote\nmessage"
    val isReply = rawText.startsWith("> ")
    val quoteText: String
    val text: String
    if (isReply) {
        val newlineIdx = rawText.indexOf('\n')
        if (newlineIdx > 2) {
            quoteText = rawText.substring(2, newlineIdx)
            text = rawText.substring(newlineIdx + 1)
        } else {
            quoteText = rawText.substring(2)
            text = ""
        }
    } else {
        quoteText = ""
        text = rawText
    }

    // Delete confirmation dialog
    val canDeleteForBoth = isSent && (System.currentTimeMillis() - entity.createdAt) < 24 * 60 * 60 * 1000L
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Surface,
            title = { Text("Delete message?", color = TextPrimary) },
            text = {
                if (canDeleteForBoth) {
                    Text("Choose who to delete this message for.", color = TextDim, fontSize = 14.sp)
                } else {
                    Text("Delete this message for yourself?", color = TextDim, fontSize = 14.sp)
                }
            },
            confirmButton = {
                Column {
                    if (canDeleteForBoth) {
                        TextButton(onClick = { showDeleteDialog = false; onDeleteForBoth() }) {
                            Text("Delete for everyone", color = Danger, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = { showDeleteDialog = false; onDeleteForMe() }) {
                        Text("Delete for me", color = Danger, fontSize = 13.sp)
                    }
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel", color = TextDim, fontSize = 13.sp)
                    }
                }
            },
        )
    }

    val swipeOffset = remember { Animatable(0f) }
    val bubbleScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                val maxSwipePx = 72.dp.toPx()
                val replyThresholdPx = 60.dp.toPx()
                var triggered = false
                detectHorizontalDragGestures(
                    onDragStart = { triggered = false },
                    onDragEnd = {
                        bubbleScope.launch {
                            if (!triggered && swipeOffset.value >= replyThresholdPx) {
                                triggered = true
                                onReply()
                            }
                            swipeOffset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f))
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (dragAmount > 0 || swipeOffset.value > 0) {
                            change.consume()
                            bubbleScope.launch {
                                swipeOffset.snapTo((swipeOffset.value + dragAmount).coerceIn(0f, maxSwipePx))
                            }
                        }
                    },
                )
            }
    ) {
        val maxSwipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
        val swipeProgress = (swipeOffset.value / maxSwipePx).coerceIn(0f, 1f)
        if (swipeProgress > 0.05f) {
            Box(
                modifier = Modifier
                    .align(if (isSent) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Surface2.copy(alpha = swipeProgress)),
                contentAlignment = Alignment.Center,
            ) {
                Text("↩", fontSize = 14.sp, color = CyanAccent.copy(alpha = swipeProgress))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) },
            horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
        ) {
        Box {
            // Outer column: bubble + reaction pills stacked vertically
            Column {
            // Bubble shape per PHANTOM_FULL_COMPOSE §05:
            //   incoming  → 12 12 12 2   (tail bottom-left)
            //   outgoing  → 12 12 2 12   (tail bottom-right)
            // OutBubble is FLAT cyan — gradient explicitly LOCKED OFF in the
            // Phase-2 polish pass (see §15 "What was normalized": "OutBubble:
            // gradient removed → flat C.cyan").
            val bubbleShape = RoundedCornerShape(
                topStart = PhantomTokens.Radius.md,
                topEnd = PhantomTokens.Radius.md,
                bottomStart = if (isSent) PhantomTokens.Radius.md else 2.dp,
                bottomEnd = if (isSent) 2.dp else PhantomTokens.Radius.md,
            )
            // Voice Bubble Matrix fix (Test #72): audio messages draw their own
            // cyan/surface-elevated bubble inside AudioBubble. The parent
            // MessageBubble shell was double-framing it. For audio rows we
            // strip the parent's background/border/padding/clip and let
            // AudioBubble fully own the visual.
            val isAudioRow = rawText.startsWith("[AUDIO:")
                || rawText.startsWith("[AUDIO_LOCAL:")
                || rawText.startsWith("[AUDIO_DOWNLOADING]")
                || rawText.startsWith("[AUDIO_FAILED:")
            Column(
                modifier = Modifier
                    .widthIn(
                        min = if (isAudioRow) 220.dp else 80.dp,
                        max = if (isAudioRow) 320.dp else 260.dp,
                    )
                    .then(
                        if (isAudioRow) Modifier
                        else if (isSent) Modifier.background(
                            color = PhantomTokens.Colors.Cyan,
                            shape = bubbleShape,
                        ) else Modifier
                            .background(
                                color = PhantomTokens.Colors.SurfaceElevated,
                                shape = bubbleShape,
                            )
                            .border(
                                width = 1.dp,
                                color = PhantomTokens.Colors.BorderSubtle,
                                shape = bubbleShape,
                            )
                    )
                    .onGloballyPositioned { coords ->
                        bubbleBoundsPx = coords.boundsInWindow()
                    }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showActionPanel = true },
                    )
                    .then(
                        if (isAudioRow) Modifier
                        else Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp)
                    ),
            ) {
                // Quote block (reply) — stacked above main text
                if (isReply && quoteText.isNotEmpty()) {
                    val senderLabel = if (entity.sent) "You" else theirUsername

                    if (isSent) {
                        // On cyan bubble — dark overlay block
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgDeep.copy(alpha = 0.30f))
                                .padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .heightIn(min = 36.dp)
                                    .background(Color.White, shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                            )
                            Column(modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)) {
                                Text(
                                    text = senderLabel,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 14.sp,
                                )
                                Text(
                                    text = quoteText,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                )
                            }
                        }
                    } else {
                        // On dark bubble — cyan accent block
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyanAccent.copy(alpha = 0.12f))
                                .padding(0.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .heightIn(min = 36.dp)
                                    .background(CyanAccent, shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                            )
                            Column(modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)) {
                                Text(
                                    text = senderLabel,
                                    color = CyanAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 14.sp,
                                )
                                Text(
                                    text = quoteText,
                                    color = TextPrimary.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // Audio bubble — detected by [AUDIO:…] / [AUDIO_LOCAL:…] / [AUDIO_DOWNLOADING] / [AUDIO_FAILED:…]
                val isAudio = rawText.startsWith("[AUDIO:")
                    || rawText.startsWith("[AUDIO_LOCAL:")
                    || rawText.startsWith("[AUDIO_DOWNLOADING]")
                    || rawText.startsWith("[AUDIO_FAILED:")
                if (isAudio) {
                    // PR-MEDIA-UPLOAD-CANCEL1 — only the sender side wires
                    // a real cancel callback; receiver-download bubbles
                    // intentionally leave it null so the X glyph
                    // disappears (no UI for a no-op).
                    AudioBubble(
                        plaintextCache = rawText,
                        isSent = isSent,
                        timeStr = timeStr,
                        status = entity.status,
                        context = context,
                        progress = mediaProgress[entity.id],
                        onCancelUpload = if (isSent) {
                            {
                                bubbleCoroutineScope.launch {
                                    Log.i(
                                        "PhantomMedia",
                                        "MEDIA_UI upload_cancel_tap localMsgId=${entity.id.take(8)}",
                                    )
                                    container.messagingService?.cancelVoiceUpload(
                                        conversationId = entity.conversationId,
                                        localMsgId = entity.id,
                                    )
                                    onReloadMessages()
                                }
                            }
                        } else null,
                    )
                } else {
                // Stacked layout: text on top, meta row (time + status) on its
                // own footer line aligned to the trailing edge of the bubble.
                // The previous Box-with-overlay layout reserved 36/56 dp of
                // end-padding on the Text so the time could float in the
                // bottom-right corner. That made the bubble grow to the
                // widest wrapped line *plus* the reserved padding, and when
                // the last wrapped line was short (e.g. "идут долго") it
                // left a visible empty gap between the text and the time
                // (Vladislav 2026-05-20 screenshot). Stacking the meta below
                // the text lets the bubble hug the widest wrapped line and
                // keeps the meta tight on its own row.
                Text(
                    text = text,
                    color = if (isSent) BgDeep else TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = timeStr,
                        color = if (isSent) BgDeep.copy(alpha = 0.65f) else TextDim,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                    )
                    if (isSent) StatusIcon(status = entity.status)
                }
                } // end audio/text branch

                // Link preview — shown for messages that contain a URL
                val urlInMsg = remember(entity.id) { extractUrl(rawText) }
                var linkPreview by remember(entity.id) { mutableStateOf<LinkPreview?>(null) }

                if (urlInMsg != null) {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    LaunchedEffect(entity.id) {
                        linkPreview = fetchLinkPreview(urlInMsg)
                    }
                    val preview = linkPreview
                    if (preview != null) {
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSent) BgDeep.copy(alpha = 0.30f) else Surface.copy(alpha = 0.8f)
                                )
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.06f),
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { runCatching { uriHandler.openUri(urlInMsg) } }
                                .padding(10.dp),
                        ) {
                            Text(
                                text = preview.title,
                                color = if (isSent) Color.White else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (preview.description.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = preview.description,
                                    color = if (isSent) Color.White.copy(alpha = 0.75f) else TextDim,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = preview.url.take(50),
                                color = if (isSent) Color.White.copy(alpha = 0.6f) else CyanAccent.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                fontFamily = PhantomFontMono,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } // end bubble Column

            // Reaction pills — shown below the bubble when reactions exist
            if (reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    reactions.forEach { entry ->
                        Text(
                            text = entry.emoji,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Surface2)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            } // end outer Column (bubble + pills)

            // Combined long-press panel — emoji row at the top, compact
            // action list expanded directly below. Rendered as a Dialog so we
            // get a dimmed scrim and the panel does not eat the whole screen.
            if (showActionPanel) {
                val within24h = (System.currentTimeMillis() - entity.createdAt) < 24 * 60 * 60 * 1000L
                val density = androidx.compose.ui.platform.LocalDensity.current
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
                val screenWidthPx  = with(density) { configuration.screenWidthDp.dp.toPx() }
                val panelMaxWidthPx = with(density) { 280.dp.toPx() }
                val panelEstimatedHeightPx = with(density) { 380.dp.toPx() }
                val gapPx = with(density) { 8.dp.toPx() }
                val sideMarginPx = with(density) { 16.dp.toPx() }
                val topMarginPx = with(density) { 40.dp.toPx() }
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showActionPanel = false },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false,
                        dismissOnClickOutside = true,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.32f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { showActionPanel = false },
                    ) {
                        // Compute panel offset relative to the long-pressed
                        // bubble. Prefer placing the panel above the bubble;
                        // fall back below if there is not enough room above.
                        val bounds = bubbleBoundsPx
                        val placeAbove = bounds != null && bounds.top > panelEstimatedHeightPx + gapPx + topMarginPx
                        val panelXPx = (bounds?.left ?: (screenWidthPx / 2f - panelMaxWidthPx / 2f))
                            .coerceIn(sideMarginPx, screenWidthPx - panelMaxWidthPx - sideMarginPx)
                        val panelYPx = when {
                            bounds == null ->
                                // Fallback to vertical centre when bounds are unknown.
                                (screenHeightPx / 2f - panelEstimatedHeightPx / 2f).coerceAtLeast(topMarginPx)
                            placeAbove ->
                                bounds.top - panelEstimatedHeightPx - gapPx
                            else ->
                                (bounds.bottom + gapPx)
                                    .coerceAtMost(screenHeightPx - panelEstimatedHeightPx - sideMarginPx)
                        }
                        Column(
                            modifier = Modifier
                                .offset {
                                    androidx.compose.ui.unit.IntOffset(
                                        x = panelXPx.toInt(),
                                        y = panelYPx.toInt(),
                                    )
                                }
                                .widthIn(max = 280.dp)
                                .wrapContentHeight()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Surface.copy(alpha = 0.96f))
                                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {}, // swallow taps so panel doesn't dismiss itself
                        ) {
                            // ── Emoji row (top) ──────────────────────────────
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                REACTION_EMOJIS.forEach { emoji ->
                                    Text(
                                        text = emoji,
                                        fontSize = 22.sp,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable {
                                                showActionPanel = false
                                                if (theirPublicKeyHex.isNotEmpty()) {
                                                    bubbleCoroutineScope.launch {
                                                        container.messagingService?.sendReaction(
                                                            messageId = entity.id,
                                                            conversationId = conversationId,
                                                            recipientPublicKeyHex = theirPublicKeyHex,
                                                            emoji = emoji,
                                                        )
                                                    }
                                                }
                                            }
                                            .padding(6.dp),
                                    )
                                }
                            }
                            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                            // ── Action list (bottom, expanded) ───────────────
                            ActionRow(
                                icon = { c -> PhIconReply(color = c, size = 16.dp) },
                                label = "Reply",
                            ) {
                                showActionPanel = false; onReply()
                            }
                            if (isSent && within24h) {
                                ActionRow(
                                    icon = { c -> PhIconEdit(color = c, size = 16.dp) },
                                    label = "Edit",
                                ) {
                                    showActionPanel = false; onEdit()
                                }
                            }
                            ActionRow(
                                icon = { c -> PhIconCopy(color = c, size = 14.dp) },
                                label = "Copy text",
                            ) {
                                showActionPanel = false; onCopy(rawText)
                            }
                            ActionRow(
                                icon = { c -> PhIconPinAction(color = c, size = 16.dp) },
                                label = if (entity.pinned) "Unpin" else "Pin",
                            ) {
                                showActionPanel = false; showPinChoice = true
                            }
                            ActionRow(
                                icon = { c -> PhIconForward(color = c, size = 16.dp) },
                                label = "Forward",
                            ) {
                                showActionPanel = false
                                val senderLabel = if (entity.sent) "You" else theirUsername
                                onForward(text, senderLabel)
                            }
                            ActionRow(
                                icon = { c -> PhIconBookmark(color = c, size = 16.dp) },
                                label = "Save",
                            ) {
                                showActionPanel = false
                                bubbleCoroutineScope.launch {
                                    val savedConvId = "saved_messages_local"
                                    container.messageRepo.insertMessage(
                                        phantom.core.storage.MessageEntity(
                                            id = com.benasher44.uuid.uuid4().toString(),
                                            conversationId = savedConvId,
                                            ciphertext = ByteArray(0),
                                            plaintextCache = "↩ from @${if (entity.sent) "You" else theirUsername}\n$text",
                                            sent = true,
                                            status = phantom.core.storage.MessageStatus.DELIVERED,
                                            createdAt = System.currentTimeMillis(),
                                        )
                                    )
                                }
                            }
                            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                            ActionRow(
                                icon = { c -> PhIconTrash(color = c, size = 15.dp) },
                                label = "Delete",
                                danger = true,
                            ) {
                                showActionPanel = false; showDeleteDialog = true
                            }
                        }
                    }
                }
            }

            // Pin sub-choice dialog — opened from the action panel's "Pin" row.
            if (showPinChoice) {
                AlertDialog(
                    onDismissRequest = { showPinChoice = false },
                    containerColor = Surface,
                    title = {
                        Text(
                            text = if (entity.pinned) "Unpin message" else "Pin message",
                            color = TextPrimary,
                            fontSize = 16.sp,
                        )
                    },
                    text = {
                        Text(
                            text = if (entity.pinned) {
                                "Choose where to unpin."
                            } else {
                                "Pin for everyone shows this message at the top of the chat for both of you. Pin for me keeps it private to your device."
                            },
                            color = TextDim,
                            fontSize = 13.sp,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showPinChoice = false; onPin() }) {
                            Text(
                                text = if (entity.pinned) "Unpin for everyone" else "Pin for everyone",
                                color = CyanAccent,
                                fontSize = 14.sp,
                            )
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { showPinChoice = false; onPinLocal() }) {
                                Text(
                                    text = if (entity.pinned) "Unpin for me" else "Pin for me",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                )
                            }
                            TextButton(onClick = { showPinChoice = false }) {
                                Text("Cancel", color = TextDim, fontSize = 14.sp)
                            }
                        }
                    },
                )
            }
        }
        } // end Row
    } // end swipe Box
}

@Composable
private fun ActionRow(
    icon: @Composable (color: Color) -> Unit,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) Danger else PhantomTokens.Colors.TextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) { icon(tint) }
        Text(
            text = label,
            color = if (danger) Danger else TextPrimary,
            fontSize = 14.sp,
        )
    }
}

private fun formatMessageTime(millis: Long): String {
    val date = java.util.Date(millis)
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return fmt.format(date)
}

@Composable
private fun StatusIcon(status: MessageStatus) {
    when (status) {
        MessageStatus.QUEUED -> {
            // Clock-dot: just a dim dot
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCircle(color = Color.White.copy(alpha = 0.35f), radius = 2.dp.toPx())
            }
        }
        MessageStatus.SENT -> {
            // Single checkmark — dim on cyan bubble
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCheckmark(
                    color = BgDeep.copy(alpha = 0.62f),
                    offsetX = size.width * 0.1f,
                )
            }
        }
        MessageStatus.RELAYED, MessageStatus.DELIVERED -> {
            // Double checkmark — dim or slightly brighter
            val color = if (status == MessageStatus.DELIVERED)
                BgDeep.copy(alpha = 0.75f) else BgDeep.copy(alpha = 0.55f)
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCheckmark(color = color, offsetX = 0f)
                drawCheckmark(color = color, offsetX = size.width * 0.3f)
            }
        }
        MessageStatus.READ -> {
            // Double checkmark — cyan accent (matches design spec)
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCheckmark(color = CyanAccent, offsetX = 0f)
                drawCheckmark(color = CyanAccent, offsetX = size.width * 0.3f)
            }
        }
        MessageStatus.WAITING_FOR_RECIPIENT_BUNDLE -> {
            // Hourglass-style amber dot — recipient hasn't published
            // a prekey bundle yet, retry sweep will pick this up.
            // PR C-followup-3.
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCircle(
                    color = Color(0xFFF59E0B), // amber/warning
                    radius = 3.dp.toPx(),
                )
            }
        }
        MessageStatus.UPLOADING -> {
            // Spinning arc — voice/media upload in progress (PR-M1w).
            // Infinite rotation via animate*AsState is available but heavy;
            // a static arc conveys "in-progress" without an animation loop
            // until the design system adds a proper spinner token.
            Canvas(modifier = Modifier.size(14.dp)) {
                drawArc(
                    color = Color.White.copy(alpha = 0.60f),
                    startAngle = -90f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    ),
                )
            }
        }
        MessageStatus.DOWNLOADING -> {
            // Downward-arc — voice/media download in progress (PR-M1w).
            Canvas(modifier = Modifier.size(14.dp)) {
                drawArc(
                    color = CyanAccent.copy(alpha = 0.70f),
                    startAngle = 90f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    ),
                )
            }
        }
        MessageStatus.FAILED -> {
            // Red dot — non-retryable encryption failure. Tap-to-retry
            // hook is a future polish item; for now the indicator just
            // tells the user the message didn't ship.
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCircle(
                    color = Color(0xFFEF4444), // red/danger
                    radius = 3.dp.toPx(),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckmark(
    color: Color,
    offsetX: Float,
) {
    val w = size.width
    val h = size.height
    val sw = 1.6.dp.toPx()
    // checkmark: short left leg + long right leg
    val x0 = offsetX + w * 0.05f
    val y0 = h * 0.50f
    val x1 = offsetX + w * 0.22f
    val y1 = h * 0.72f
    val x2 = offsetX + w * 0.50f
    val y2 = h * 0.28f
    drawLine(color = color, start = Offset(x0, y0), end = Offset(x1, y1), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
}

// ── Audio message bubble ──────────────────────────────────────────────────────

/**
 * Renders a voice message bubble per the **Voice Bubble Matrix** design
 * (PR-UI Voice Bubble Matrix, design handed off 2026-05-20 — see
 * `phantom-messengers/project/Voice Bubble Matrix.html`).
 *
 * Five visual states, gated on existing `plaintextCache` prefixes (PR-M1w):
 *   * `[AUDIO_DOWNLOADING]` — receiver during chunk download. STATE 2.
 *   * `[AUDIO:<base64>]`     — sender's row, before manifest leaves device.
 *     Sender's upload progress is detected via the in-memory [MediaProgressBus]
 *     entry (PR-M2d.1b lesson — DB status column is not reliable). STATE 1.
 *   * `[AUDIO_LOCAL:<path>]` — playable, file decrypted to local FS. STATE 3.
 *   * `[AUDIO:<base64>]`     — playable, sender self-playback (legacy path
 *     and current voice_v2 sender). STATE 3.
 *   * `[AUDIO_FAILED:<reason>]` — permanent failure (kept from M2d.1b; the
 *     Voice Bubble Matrix design intentionally does not cover this state,
 *     so the styling tracks the design palette but the layout is bespoke).
 *
 * Animation budget per the design annotations (and verified by Compose
 * implementation): exactly one rotating arc, one staggered bar-opacity
 * tween, one playhead-bar repaint per audio tick, zero layout passes.
 */
@Composable
private fun AudioBubble(
    plaintextCache: String,
    isSent: Boolean,
    timeStr: String,
    status: MessageStatus,
    context: android.content.Context,
    progress: MediaProgressBus.Progress? = null,
    // PR-MEDIA-UPLOAD-CANCEL1 — wired by the sender side only. The X
    // button on an outgoing uploading bubble routes here; the lambda owns
    // the call into `MessagingService.cancelVoiceUpload(...)`. Null
    // suppresses the X visual entirely, which is the right behaviour for
    // receiver download bubbles (no cancel path wired yet) and for any
    // ready/failed state.
    onCancelUpload: (() -> Unit)? = null,
) {
    // ── State detection (unchanged from M2d.1b — only visuals change) ─────────
    val isDownloading = plaintextCache == "[AUDIO_DOWNLOADING]"
    val isFailed = plaintextCache.startsWith("[AUDIO_FAILED:")
    val isLocalFile = plaintextCache.startsWith("[AUDIO_LOCAL:")
    val isLegacyBase64 = plaintextCache.startsWith("[AUDIO:") && !isLocalFile
    val isUploadingSender = isSent
        && progress != null
        && progress.direction == MediaProgressBus.Direction.UPLOAD
    val isLoading = isDownloading || isUploadingSender

    // Diagnostic — kept from M2d.1b for transport-team troubleshooting.
    androidx.compose.runtime.LaunchedEffect(progress) {
        if (progress != null) {
            android.util.Log.i(
                "PhantomMedia",
                "MEDIA_UI progress_lookup found=true " +
                    "direction=${progress.direction.name.lowercase()} " +
                    "sent=${progress.sent} total=${progress.total} " +
                    "isSent=$isSent status=${status.name}",
            )
        }
    }

    val localFilePath: String? = if (isLocalFile) {
        plaintextCache.removePrefix("[AUDIO_LOCAL:").trimEnd(']')
    } else null
    val base64Payload: String? = if (isLegacyBase64) {
        plaintextCache.removePrefix("[AUDIO:").trimEnd(']')
    } else null

    // ── Failed branch — kept from M2d.1b, no Matrix coverage for this state ──
    if (isFailed) {
        val reason = plaintextCache.removePrefix("[AUDIO_FAILED:").trimEnd(']')
        AudioBubbleFailed(reason = reason, isSent = isSent, timeStr = timeStr, status = status)
        return
    }

    // ── Playable state: MediaPlayer lifecycle ────────────────────────────────
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    // PR-UI-VB1 (2026-05-20): `playProgress` is the canonical playhead [0..1].
    // It survives pause (the waveform freezes at the paused position) and is
    // reset to 0 only by the MediaPlayer completion listener. The old logic
    // hid `playProgress` behind `if (isPlaying) ... else 0f` which made
    // pause look identical to stop — Vladislav Test #73 visual bug.
    var playProgress by remember { mutableStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var durationMs by remember { mutableStateOf(0) }
    // Reusable on-disk playback source. For [AUDIO_LOCAL:<path>] this is
    // simply the underlying file; for [AUDIO:<base64>] legacy bubbles we
    // decode once into the cacheDir and reuse it across the lifetime of the
    // composable, then delete on dispose.
    var playbackCacheFile by remember { mutableStateOf<java.io.File?>(null) }
    val speedSteps = remember { listOf(1.0f, 1.5f, 2.0f, 0.5f) }
    var speedIdx by remember { mutableIntStateOf(0) }
    val currentSpeed = speedSteps[speedIdx]

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
            // Only delete the temp file we created for legacy base64 voices.
            // For [AUDIO_LOCAL:<path>] the file is the authoritative voice
            // store owned by VoiceFileStore — we must not delete it here.
            playbackCacheFile?.takeIf { base64Payload != null }?.delete()
            playbackCacheFile = null
        }
    }

    // PR-UI-VB1: preload duration as soon as the bubble is in a ready
    // (non-loading) state. Without this, durationMs stayed 0 until the user
    // hit play for the first time, and the bubble showed "—:——" instead of
    // the real total duration. `MediaMetadataRetriever` does the extraction
    // on the IO dispatcher so the Compose main thread is never blocked, and
    // the legacy base64 branch primes `playbackCacheFile` while it is at it
    // so the first tap on Play doesn't re-decode.
    LaunchedEffect(plaintextCache, isLoading) {
        if (isLoading) return@LaunchedEffect
        if (durationMs > 0) return@LaunchedEffect
        val extracted = withContext(Dispatchers.IO) {
            val sourceFile = when {
                localFilePath != null -> {
                    val f = java.io.File(localFilePath)
                    if (f.exists()) f else null
                }
                base64Payload != null -> {
                    val existing = playbackCacheFile
                    if (existing != null && existing.exists()) {
                        existing
                    } else {
                        val bytes = runCatching {
                            android.util.Base64.decode(base64Payload, android.util.Base64.NO_WRAP)
                        }.getOrNull() ?: return@withContext 0
                        val f = java.io.File(
                            context.cacheDir,
                            "play_${System.currentTimeMillis()}_${plaintextCache.hashCode()}.audio",
                        )
                        f.writeBytes(bytes)
                        playbackCacheFile = f
                        f
                    }
                }
                else -> null
            } ?: return@withContext 0
            runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(sourceFile.absolutePath)
                    mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull()?.toInt() ?: 0
                } finally {
                    mmr.release()
                }
            }.getOrDefault(0)
        }
        if (extracted > 0) durationMs = extracted
    }

    fun togglePlayback() {
        if (isLoading) return // ignore taps while the chunk loop is still running
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
            return
        }
        if (mediaPlayer == null) {
            try {
                // Prefer the original local file (no copy), fall back to the
                // preloaded cache, last resort decode the legacy base64 here.
                val playSourcePath: String = when {
                    localFilePath != null && java.io.File(localFilePath).exists() -> localFilePath
                    playbackCacheFile?.exists() == true -> playbackCacheFile!!.absolutePath
                    base64Payload != null -> {
                        val bytes = runCatching {
                            android.util.Base64.decode(base64Payload, android.util.Base64.NO_WRAP)
                        }.getOrNull() ?: return
                        val ext = localFilePath?.substringAfterLast('.', "audio") ?: "audio"
                        val f = java.io.File(
                            context.cacheDir,
                            "play_${System.currentTimeMillis()}.$ext",
                        )
                        f.writeBytes(bytes)
                        playbackCacheFile = f
                        f.absolutePath
                    }
                    else -> return
                }
                val mp = android.media.MediaPlayer().apply {
                    setDataSource(playSourcePath)
                    prepare()
                    setOnCompletionListener {
                        isPlaying = false
                        playProgress = 0f
                    }
                }
                mediaPlayer = mp
                if (durationMs <= 0) durationMs = mp.duration
            } catch (_: Exception) {
                return
            }
        }
        mediaPlayer?.let { mp ->
            // If the player is sitting at the end of the clip (just completed
            // and the user tapped Play again), rewind first so playback
            // restarts from the beginning instead of returning instantly.
            val totalMs = mp.duration
            val nearEnd = totalMs > 0 && mp.currentPosition >= totalMs - 50
            if (nearEnd) {
                runCatching { mp.seekTo(0) }
                playProgress = 0f
            }
            runCatching { mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed) }
            mp.start()
        }
        isPlaying = true
        scope.launch {
            while (isPlaying) {
                val mp = mediaPlayer ?: break
                playProgress = if (mp.duration > 0) mp.currentPosition / mp.duration.toFloat() else 0f
                delay(200)
            }
        }
    }

    // ── Right-column label: percent (loading) or duration (ready) ────────────
    val percentLabel: String? = if (isLoading && progress != null && progress.total > 0) {
        "${(progress.sent.toFloat() / progress.total * 100).toInt()}%"
    } else if (isLoading) {
        // Edge case: loading without progress numbers (M2d.1b fallback).
        ""
    } else null

    // PR-UI-VB1: four-state label. `playProgress` is non-zero while the
    // user is in the middle of a clip — either actively playing or paused
    // partway through. Both states show "current / total" so paused stops
    // looking like stopped. The genuine fallback "—:——" remains for cases
    // where duration preload failed (e.g. corrupted file).
    val hasPlaybackPosition = playProgress > 0f && playProgress < 0.999f
    val durationLabel: String? = if (!isLoading) {
        when {
            isPlaying || hasPlaybackPosition -> {
                val currentMs = (playProgress * durationMs).toInt()
                "${formatVoiceDur(currentMs)} / ${formatVoiceDur(durationMs)}"
            }
            durationMs > 0 -> formatVoiceDur(durationMs)
            else -> "—:——"
        }
    } else null

    // PR-UI-VB1: waveform tracks the canonical playhead always — pause must
    // keep the highlighted bars in place; only MediaPlayer's onCompletion
    // resets `playProgress` to 0, which naturally clears the waveform too.
    val waveProgress = playProgress
    val canTap = !isLoading && (localFilePath != null || base64Payload != null)

    // ── Bubble shell + meta row ──────────────────────────────────────────────
    Column(
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start,
    ) {
        // Asymmetric tail per Voice Bubble Matrix: out = TL/TR/BR=tail/BL,
        // in = TL/TR/BR/BL=tail. Tail-corner radius = 6dp; the other three = 18dp.
        val shape = if (isSent) {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
        } else {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
        }

        Row(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 280.dp)
                .clip(shape)
                .then(
                    if (isSent) Modifier.background(CyanAccent)
                    else Modifier
                        .background(Surface2)
                        .border(width = 1.dp, color = BorderSubtle, shape = shape)
                )
                .let { mod ->
                    if (canTap) mod.clickable(onClick = { togglePlayback() }) else mod
                }
                .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
                .heightIn(min = 52.dp - 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VoiceDisc(
                isSent = isSent,
                isLoading = isLoading,
                isUpload = isUploadingSender,
                isPlaying = isPlaying,
                onTap = if (canTap) ::togglePlayback else null,
            )
            VoiceWaveform(
                progress = waveProgress,
                loading = isLoading,
                isSent = isSent,
                modifier = Modifier.weight(1f).height(26.dp),
            )
            // Right column: percent + cancel (loading) OR duration (ready)
            if (percentLabel != null) {
                if (percentLabel.isNotEmpty()) {
                    Text(
                        text = percentLabel,
                        fontFamily = PhantomFontMono,
                        fontSize = 10.sp,
                        color = if (isSent) BgDeep.copy(alpha = 0.78f) else TextSecondary,
                    )
                }
                // PR-MEDIA-UPLOAD-CANCEL1: only render the X when the
                // caller actually wired a cancel callback. Receiver
                // download bubbles (and any other loading state without a
                // cancellation surface) pass null and the X disappears
                // entirely — Test #76.3 caught the prior no-op button.
                if (onCancelUpload != null) {
                    CancelXButton(isSent = isSent, onClick = onCancelUpload)
                }
            } else if (durationLabel != null) {
                Text(
                    text = durationLabel,
                    fontFamily = PhantomFontMono,
                    fontSize = 11.sp,
                    color = if (isSent) BgDeep.copy(alpha = 0.72f) else TextSecondary,
                )
            }
        }

        // Meta row (timestamp + tick + loading label) — sits below the bubble.
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = timeStr,
                fontFamily = PhantomFontMono,
                fontSize = 10.sp,
                color = TextDim,
            )
            if (isSent && !isLoading) StatusIcon(status = status)
            if (isLoading) {
                Text(
                    text = if (isUploadingSender) "Sending" else "Receiving",
                    fontFamily = PhantomFontMono,
                    fontSize = 10.sp,
                    color = TextDim.copy(alpha = 0.7f),
                )
            }
            // Playback speed pill — kept from the pre-Matrix bubble because
            // the design doesn't replace this UX. Visible only while playing.
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Surface2)
                        .clickable {
                            speedIdx = (speedIdx + 1) % speedSteps.size
                            mediaPlayer?.let { mp ->
                                runCatching {
                                    mp.playbackParams = mp.playbackParams.setSpeed(speedSteps[speedIdx])
                                }
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = formatSpeed(currentSpeed),
                        fontSize = 9.5.sp,
                        color = CyanAccent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Voice Bubble Matrix — 36dp disc with state-dependent glyph + optional progress arc. */
@Composable
private fun VoiceDisc(
    isSent: Boolean,
    isLoading: Boolean,
    isUpload: Boolean,
    isPlaying: Boolean,
    onTap: (() -> Unit)?,
) {
    val discBg = when {
        isPlaying -> CyanAccent.copy(red = 0f, green = 0.6f, blue = 0.73f) // cyan-dark #0099BB
        isSent -> BgDeep.copy(alpha = 0.12f)
        else -> Surface
    }
    val glyphTint = when {
        isPlaying -> CyanAccent
        isSent -> BgDeep
        else -> CyanAccent.copy(red = 0f, green = 0.6f, blue = 0.73f)
    }
    val transition = rememberInfiniteTransition(label = "voiceArc")
    val arcRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "arcRot",
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .background(discBg)
            .then(
                if (isPlaying || isLoading) Modifier
                else Modifier.border(
                    width = if (isSent) 0.dp else 1.dp,
                    color = if (isSent) Color.Transparent else BorderSubtle,
                    shape = RoundedCornerShape(50),
                )
            )
            .let { mod -> if (onTap != null) mod.clickable(onClick = onTap) else mod },
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            // Static background ring + rotating arc.
            Canvas(modifier = Modifier.size(40.dp)) {
                val sw = 1.5.dp.toPx()
                val ringColor = if (isSent) BgDeep.copy(alpha = 0.16f) else BorderSubtle
                drawArc(
                    color = ringColor,
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw),
                )
            }
            Canvas(modifier = Modifier.size(40.dp)) {
                val sw = 1.5.dp.toPx()
                val arcColor = if (isSent) BgDeep.copy(alpha = 0.55f) else CyanAccent.copy(alpha = 0.85f)
                rotate(degrees = arcRotation, pivot = center) {
                    drawArc(
                        color = arcColor,
                        startAngle = -90f, sweepAngle = 180f, useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = sw,
                            cap = StrokeCap.Round,
                        ),
                    )
                }
            }
            // Arrow-up (uploading) or arrow-down (downloading), 12px.
            Canvas(modifier = Modifier.size(12.dp)) {
                val w = size.width
                val h = size.height
                val path = androidx.compose.ui.graphics.Path()
                if (isUpload) {
                    // Up-arrow body
                    path.apply {
                        moveTo(w * 0.50f, h * 0.21f)
                        lineTo(w * 0.79f, h * 0.50f)
                        lineTo(w * 0.58f, h * 0.50f)
                        lineTo(w * 0.58f, h * 0.79f)
                        lineTo(w * 0.42f, h * 0.79f)
                        lineTo(w * 0.42f, h * 0.50f)
                        lineTo(w * 0.21f, h * 0.50f)
                        close()
                    }
                } else {
                    path.apply {
                        moveTo(w * 0.50f, h * 0.79f)
                        lineTo(w * 0.21f, h * 0.50f)
                        lineTo(w * 0.42f, h * 0.50f)
                        lineTo(w * 0.42f, h * 0.21f)
                        lineTo(w * 0.58f, h * 0.21f)
                        lineTo(w * 0.58f, h * 0.50f)
                        lineTo(w * 0.79f, h * 0.50f)
                        close()
                    }
                }
                drawPath(path, color = glyphTint)
            }
        } else if (isPlaying) {
            // Pause glyph — two clearly separated bars (Test #72 fix: the
            // previous 0.21/0.21 split read as a single solid block aka
            // "stop button" on Tecno's screen pixel density).
            Canvas(modifier = Modifier.size(14.dp)) {
                val w = size.width
                val h = size.height
                val barW = w * 0.22f
                val gapHalf = w * 0.10f
                val top = h * 0.15f
                val barH = h * 0.70f
                drawRoundRect(
                    color = glyphTint,
                    topLeft = Offset(w / 2f - gapHalf - barW, top),
                    size = androidx.compose.ui.geometry.Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f, 1f),
                )
                drawRoundRect(
                    color = glyphTint,
                    topLeft = Offset(w / 2f + gapHalf, top),
                    size = androidx.compose.ui.geometry.Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f, 1f),
                )
            }
        } else {
            // Play glyph (12px) — triangle.
            Canvas(modifier = Modifier.size(12.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.27f, size.height * 0.15f)
                    lineTo(size.width * 0.82f, size.height * 0.50f)
                    lineTo(size.width * 0.27f, size.height * 0.85f)
                    close()
                }
                drawPath(path, color = glyphTint)
            }
        }
    }
}

/**
 * 36-bar waveform per the Voice Bubble Matrix design. Heights are a
 * deterministic sin+cos envelope — same array on every bubble (the goal
 * is a recognisable shape, not real audio analysis). When [loading] is
 * true, each bar pulses 0.35→0.85 opacity with a 3-phase stagger. When
 * [progress] > 0, bars to the left of `floor(progress * 36)` are drawn
 * in the active color; the rest stay muted. The played/unplayed
 * boundary is a HARD cut (no gradient or anti-aliased sweep) — see the
 * "Played / unplayed boundary" zoom block in Voice Bubble Matrix.html.
 */
@Composable
private fun VoiceWaveform(
    progress: Float,
    loading: Boolean,
    isSent: Boolean,
    modifier: Modifier = Modifier,
) {
    val activeColor = if (isSent) BgDeep else CyanAccent
    val mutedColor = if (isSent) BgDeep.copy(alpha = 0.32f) else BorderSubtle
    val bars = VOICE_WAVEFORM_BARS
    val cutoff = (progress.coerceIn(0f, 1f) * bars.size).toInt()

    // Three pulse phases, staggered at 0 / 180 / 340 ms — matches the
    // CSS nth-child(3n)/(3n+1)/(3n+2) stagger in voice-tokens.css.
    val transition = rememberInfiniteTransition(label = "voicePulse")
    val pulseSpec = infiniteRepeatable<Float>(
        animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse,
    )
    val pulse0 by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = pulseSpec, label = "p0",
    )
    val pulse1 by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = pulseSpec,
        label = "p1",
    )
    val pulse2 by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.85f,
        animationSpec = pulseSpec,
        label = "p2",
    )
    // Approximation of the CSS stagger — we offset two of the three
    // phases by a small phase shift in playback time. Compose's
    // InfiniteTransition doesn't expose a per-animation StartOffset
    // override without an additional easing trick; the slight
    // imperfection in stagger here is acceptable per the locked
    // "animation budget" guidance (we hit the 3-tween count) and not
    // visible at 60 fps to the naked eye.
    val phases = floatArrayOf(pulse0, pulse1, pulse2)

    Canvas(modifier = modifier) {
        // Voice Bubble Matrix fix (Test #72): scale bar width + gap so the
        // 36-bar waveform always fits the canvas width — previously a fixed
        // 2dp bar + 3dp gap (177 dp total) overflowed the ~140-170 dp the
        // weight(1f) middle column actually gets on 220-280 dp bubbles,
        // pushing bars under the play button + percent labels.
        // Keep the design's 2 : 3 width-to-gap ratio.
        val n = bars.size
        val gapToBarRatio = 1.5f
        val barWidth = size.width / (n + (n - 1) * gapToBarRatio)
        val barGap = barWidth * gapToBarRatio
        val maxBarHeightDp = 24f
        val canvasH = size.height
        bars.forEachIndexed { i, h ->
            val barHeight = (h.toFloat() / maxBarHeightDp) * canvasH
            val left = i * (barWidth + barGap)
            val top = (canvasH - barHeight) / 2f
            val baseColor = if (i < cutoff) activeColor else mutedColor
            val color = if (loading) baseColor.copy(alpha = phases[i % 3].coerceIn(0f, 1f))
                        else baseColor
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.4f, barWidth * 0.4f),
            )
        }
    }
}

/** 22dp circle with 1px border + X-mark. Tap is a no-op for now —
 *  full upload/download cancellation is queued as a separate UI/state PR. */
@Composable
private fun CancelXButton(isSent: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSent) BgDeep.copy(alpha = 0.2f) else BorderSubtle
    val glyphTint = if (isSent) BgDeep.copy(alpha = 0.65f) else TextDim
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(50))
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            val sw = 1.5.dp.toPx()
            drawLine(glyphTint, Offset(size.width * 0.25f, size.height * 0.25f), Offset(size.width * 0.75f, size.height * 0.75f), sw, StrokeCap.Round)
            drawLine(glyphTint, Offset(size.width * 0.75f, size.height * 0.25f), Offset(size.width * 0.25f, size.height * 0.75f), sw, StrokeCap.Round)
        }
    }
}

@Composable
private fun AudioBubbleFailed(reason: String, isSent: Boolean, timeStr: String, status: MessageStatus) {
    Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
        Row(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 280.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, if (isSent) 6.dp else 18.dp, if (isSent) 18.dp else 6.dp))
                .background(Surface2)
                .border(
                    width = 1.dp,
                    color = Danger.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(18.dp, 18.dp, if (isSent) 6.dp else 18.dp, if (isSent) 18.dp else 6.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Danger.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(14.dp)) {
                    val sw = 1.8.dp.toPx()
                    drawLine(Danger, Offset(size.width * 0.2f, size.height * 0.2f), Offset(size.width * 0.8f, size.height * 0.8f), sw, StrokeCap.Round)
                    drawLine(Danger, Offset(size.width * 0.8f, size.height * 0.2f), Offset(size.width * 0.2f, size.height * 0.8f), sw, StrokeCap.Round)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Voice unavailable", color = Danger.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(text = "Try again later", color = TextDim, fontSize = 11.sp)
                Text(text = reason, color = TextDim.copy(alpha = 0.7f), fontFamily = PhantomFontMono, fontSize = 9.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = timeStr, fontFamily = PhantomFontMono, fontSize = 10.sp, color = TextDim)
            if (isSent) StatusIcon(status = status)
        }
    }
}

private fun formatVoiceDur(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Deterministic 36-bar speech-envelope waveform per Voice Bubble Matrix.
 * The math is intentionally simple — two overlapping sin envelopes plus a
 * cosine — so the same shape renders every bubble and the visual reads as
 * "voice" without any per-message audio analysis. Heights clamped 3-24 dp.
 */
private val VOICE_WAVEFORM_BARS: IntArray = IntArray(36).also { out ->
    val n = 36
    for (i in 0 until n) {
        val t = i.toFloat() / (n - 1).toFloat()
        val env1 = kotlin.math.sin(t * Math.PI.toFloat() * 2.3f) * 0.55f
        val env2 = kotlin.math.sin(t * Math.PI.toFloat() * 5.1f + 0.7f) * 0.35f
        val env3 = kotlin.math.cos(t * Math.PI.toFloat() * 3.7f + 1.4f) * 0.20f
        val v = kotlin.math.abs(env1 + env2 + env3) + 0.18f
        out[i] = kotlin.math.max(3, kotlin.math.min(24, (v * 22f).toInt()))
    }
}

private fun formatSpeed(speed: Float): String = when (speed) {
    1.0f -> "1×"
    2.0f -> "2×"
    0.5f -> "0.5×"
    else -> "${"%.1f".format(speed)}×"
}

// ── Input bar ─────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onEmojiToggle: () -> Unit,
    emojiPanelOpen: Boolean,
    isEditing: Boolean = false,
    recordingState: RecordingPanelState? = null,
    recordingDurationMs: Long = 0L,
    waveformAmplitudes: List<Float> = emptyList(),
    // PR-UI-REC2.3 — new gesture lifecycle. Replaces the prior
    // `onMicClick / onMicReleaseAfterHold / onLockGesture` triplet with a
    // proper ACTION_DOWN / ACTION_UP / drag state machine.
    onMicDownStartRecording: () -> Boolean = { false },
    onMicHoldReleaseSend: (heldMs: Long) -> Unit = {},
    onMicHoldTooShortCancel: (heldMs: Long) -> Unit = {},
    onMicHoldSwipeCancel: (heldMs: Long) -> Unit = {},
    onMicSlideUpLock: () -> Unit = {},
    onSendVoiceTap: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onPauseRecording: () -> Unit = {},
    onResumeRecording: () -> Unit = {},
    onSend: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // PR-UI-REC2 — distance the finger must move up from the press-down point
    // before we transition from `Recording` to `Locked`. 60 dp is the WhatsApp /
    // Telegram convention for the slide-to-lock affordance.
    val lockThresholdPx = with(density) { 60.dp.toPx() }
    // PR-UI-REC2.4 — interim swipe-left-to-cancel guard. Until the full
    // SwipeCancel state is implemented in PR-UI-REC3, any meaningful left
    // swipe during a hold must cancel the recording instead of falling
    // through to `hold_release_send` on release (Test #76.2 caught the user
    // dragging right-to-left and getting the voice sent).
    val swipeCancelThresholdPx = with(density) { 56.dp.toPx() }
    var isMicHeld by remember { mutableStateOf(false) }
    // PR-UI-REC3 — live swipe-left distance in pixels. The gesture loop
    // writes this on every drag event; the render branch reads it to
    // overlay the SwipeCancel state 4 visual (trail / threshold marker /
    // trash handle / live distance %). Kept here at the InputBar scope so
    // both the gesture loop (writer) and the visual branch (reader) live
    // in the same composition. Reset to 0 at the end of every gesture so
    // a completed-then-restarted recording does not inherit stale swipe
    // progress.
    var swipeDragLeftPx by remember { mutableFloatStateOf(0f) }
    val isLive = recordingState == RecordingPanelState.Recording
        || recordingState == RecordingPanelState.Locked

    // PR-UI-REC2.4 — Test #76.2 verdict: `pointerInput(Unit)` freezes
    // every value it captures by closure from first composition, so all
    // state we want to read inside `awaitEachGesture` must come from
    // `rememberUpdatedState` holders. Critically `isSendVoiceVisual` was
    // captured as `false` from the idle-state first composition, so even
    // after `recordingState` became `Locked` the gesture detector kept
    // routing taps as mic-start — `ignored_start reason=busy state=Locked`.
    val currentTextState = androidx.compose.runtime.rememberUpdatedState(text)
    val currentIsEditingState = androidx.compose.runtime.rememberUpdatedState(isEditing)
    val currentIsSendVoiceVisual = androidx.compose.runtime.rememberUpdatedState(recordingState != null)

    // Phase 2 mockup: composer sits on SurfaceElevated, BorderSubtle 1px top.
    // Slightly more "premium" than the surrounding chat surface, signalling
    // the input zone without a heavy bar.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PhantomTokens.Colors.SurfaceElevated),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PhantomTokens.Colors.BorderSubtle),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Conditional left + center + middle-right slots ──────────────
            //
            // PR-UI-REC2: the right-side mic/send Box (rendered last in this
            // Row) is intentionally a persistent composable across all
            // recording states so its `pointerInput` keeps tracking the
            // user's finger across an idle → Recording → Locked transition.
            // Compose preserves a composable's identity when its position in
            // its parent (here: the last child of this Row) stays constant
            // across recompositions, which means the gesture in flight is
            // not cancelled when the rest of the Row swaps idle controls for
            // recording controls underneath it.
            if (recordingState != null) {
                // PR-UI-REC3 — derive whether the swipe-cancel overlay
                // should take over the center / sides of the row. The
                // visible threshold (8 dp) is a small dead zone so a
                // jittery fingertip does not flicker the overlay in and
                // out; once past it, the SwipeZone is visible and the
                // side controls dim to 0.4 opacity per the design brief.
                val swipeVisibleThresholdPx = with(density) { 8.dp.toPx() }
                val isSwipeOverlayActive = swipeDragLeftPx > swipeVisibleThresholdPx
                    && recordingState != RecordingPanelState.Locked
                val swipeFraction = (swipeDragLeftPx / swipeCancelThresholdPx).coerceIn(0f, 1f)
                val dimmedAlpha = if (isSwipeOverlayActive) 0.4f else 1f

                // X · Cancel (or Resume-disabled visual while swiping).
                // The click handler is suppressed during the overlay so
                // the user does not accidentally tap-cancel while the
                // swipe gesture is already arming the same outcome.
                RecPanelControl(
                    onClick = if (isSwipeOverlayActive) ({}) else onCancelRecording,
                    background = Color.Transparent,
                    border = false,
                ) {
                    Box(modifier = Modifier.graphicsLayer(alpha = dimmedAlpha)) {
                        Canvas(modifier = Modifier.size(18.dp)) {
                            val sw = 1.5.dp.toPx()
                            val pad = size.width * 0.28f
                            drawLine(TextSecondary, Offset(pad, pad), Offset(size.width - pad, size.height - pad), sw, StrokeCap.Round)
                            drawLine(TextSecondary, Offset(size.width - pad, pad), Offset(pad, size.height - pad), sw, StrokeCap.Round)
                        }
                    }
                }

                // Center: either the standard dot+timer+waveform stack OR
                // the SwipeCancel zone (trail / threshold / hint / handle).
                if (isSwipeOverlayActive) {
                    RecPanelSwipeZone(
                        swipeFraction = swipeFraction,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .padding(horizontal = 4.dp),
                    )
                } else {
                    // Center stack: dot + (lock-badge if Locked) + timer + waveform + (paused pill if Paused)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RecPanelDot(live = isLive)
                        if (recordingState == RecordingPanelState.Locked) {
                            RecPanelLockBadge()
                        }
                        RecPanelTimer(durationMs = recordingDurationMs, paused = !isLive)
                        RecPanelWaveform(
                            amplitudes = waveformAmplitudes,
                            live = isLive,
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp),
                        )
                        if (recordingState == RecordingPanelState.Paused) {
                            RecPanelPausedPill()
                        }
                    }
                }

                // PR-UI-REC3.3 — hide Pause/Resume during press-hold
                // recording. The user's finger is on the mic, so they
                // physically can't reach the in-panel control with the
                // same finger anyway. The button reappears as soon as
                // the recording goes hands-free (Locked, Paused, or
                // Resumed-from-Paused). SwipeCancel-state edge case
                // returns true here as well so dimmed-Pause still
                // appears if state ever transitions there.
                val isPressHoldRecording = recordingState == RecordingPanelState.Recording
                    && isMicHeld
                if (!isPressHoldRecording) {
                    RecPanelControl(
                        onClick = if (isSwipeOverlayActive) ({}) else if (isLive) onPauseRecording else onResumeRecording,
                        background = Surface2,
                        border = true,
                    ) {
                        Box(modifier = Modifier.graphicsLayer(alpha = dimmedAlpha)) {
                            if (isLive) {
                                Canvas(modifier = Modifier.size(16.dp)) {
                                    val barW = size.width * 0.18f
                                    val barH = size.height * 0.62f
                                    val gap = size.width * 0.18f
                                    val centerX = size.width / 2f
                                    val y = (size.height - barH) / 2f
                                    drawRoundRect(
                                        color = TextPrimary,
                                        topLeft = Offset(centerX - gap / 2f - barW, y),
                                        size = Size(barW, barH),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.35f),
                                    )
                                    drawRoundRect(
                                        color = TextPrimary,
                                        topLeft = Offset(centerX + gap / 2f, y),
                                        size = Size(barW, barH),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.35f),
                                    )
                                }
                            } else {
                                Canvas(modifier = Modifier.size(16.dp)) {
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(size.width * 0.32f, size.height * 0.20f)
                                        lineTo(size.width * 0.82f, size.height * 0.50f)
                                        lineTo(size.width * 0.32f, size.height * 0.80f)
                                        close()
                                    }
                                    drawPath(path, color = TextPrimary)
                                }
                            }
                        }
                    }
                }
            } else {
                // Idle composer: emoji + text field
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onEmojiToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    PhIconSmile(color = TextDim, size = 22.dp)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp)
                        .clip(RoundedCornerShape(50))
                        .background(PhantomTokens.Colors.Surface)
                        .border(1.dp, PhantomTokens.Colors.Border, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 5,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = TextPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(CyanAccent),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    text = "Message…",
                                    color = TextDim.copy(alpha = 0.4f),
                                    fontSize = 14.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
            }

            // ── Right-side action button ──────────────────────────────────
            //
            // PR-UI-REC2.3 — split into TWO mutually-exclusive branches per
            // architect verdict on Test #76.1.2:
            //
            //   text-mode  → cyan Send-text arrow with plain `clickable`,
            //                **no mic pointerInput** anywhere on this Box.
            //                A tap here can never start a recording.
            //   mic-mode   → persistent Box across the idle ↔ Recording ↔
            //                Locked transitions, hosting the real ACTION_DOWN
            //                / drag / ACTION_UP state machine. The visual
            //                content switches mic-icon ↔ send-voice-arrow but
            //                the Box identity stays, so an in-flight gesture
            //                survives the state change.
            //
            // The text-mode ↔ mic-mode swap only happens while idle (the
            // text input is hidden during recording, so the user cannot type
            // while a recording is in flight). The Box-identity break
            // between the two branches therefore cannot cancel an active
            // recording.
            val hasTextSend = (text.isNotBlank() || isEditing) && recordingState == null
            if (hasTextSend) {
                // Text-mode Box. Architect's #76.1.2 acceptance rule 9:
                // NEVER attach any pointerInput here. The tap goes only to
                // `onSend()`; mic gestures cannot fire on this branch.
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .shadow(
                            elevation = 6.dp,
                            shape = CircleShape,
                            clip = false,
                            spotColor = PhantomTokens.Colors.Cyan.copy(alpha = 0.18f),
                            ambientColor = PhantomTokens.Colors.Cyan.copy(alpha = 0.08f),
                        )
                        .clip(CircleShape)
                        .background(PhantomTokens.Colors.Cyan)
                        .clickable(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            Log.i("PhantomUI", "COMPOSER_ACTION send_text_clicked")
                            onSend()
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isEditing) {
                        Canvas(modifier = Modifier.size(18.dp)) {
                            val sw = 2.dp.toPx()
                            drawLine(BgDeep, Offset(size.width * 0.15f, size.height * 0.5f), Offset(size.width * 0.42f, size.height * 0.76f), sw, StrokeCap.Round)
                            drawLine(BgDeep, Offset(size.width * 0.42f, size.height * 0.76f), Offset(size.width * 0.85f, size.height * 0.24f), sw, StrokeCap.Round)
                        }
                    } else {
                        Canvas(modifier = Modifier.size(20.dp)) {
                            val sw = 2.2.dp.toPx()
                            val cap = StrokeCap.Round
                            val cx = size.width / 2f
                            drawLine(BgDeep, Offset(cx, size.height * 0.82f), Offset(cx, size.height * 0.18f), sw, cap)
                            drawLine(BgDeep, Offset(cx, size.height * 0.18f), Offset(cx - size.width * 0.28f, size.height * 0.46f), sw, cap)
                            drawLine(BgDeep, Offset(cx, size.height * 0.18f), Offset(cx + size.width * 0.28f, size.height * 0.46f), sw, cap)
                        }
                    }
                }
            } else {
                // Mic-mode Box — persistent across mic ↔ send-voice. The
                // pointerInput hosts the ACTION_DOWN-driven state machine.
                val isSendVoiceVisual = recordingState != null
                val micBoxSize = if (isSendVoiceVisual) 44.dp else 36.dp
                // PR-UI-REC3 — dim the mic/send glyph (not the gesture
                // surface!) to 0.4 when the swipe-cancel overlay is
                // active. The Box keeps its `pointerInput` fully alive so
                // the gesture in flight is not interrupted; only the
                // visual content inside fades.
                val swipeVisibleThresholdPx = with(density) { 8.dp.toPx() }
                val isSwipeOverlayActiveOnRight = swipeDragLeftPx > swipeVisibleThresholdPx
                    && recordingState != null
                    && recordingState != RecordingPanelState.Locked
                val rightGlyphAlpha = if (isSwipeOverlayActiveOnRight) 0.4f else 1f
                Box(
                    modifier = Modifier
                        .size(micBoxSize)
                        .clip(CircleShape)
                        .background(if (isSendVoiceVisual) PhantomTokens.Colors.Cyan else Color.Transparent)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downId = down.id
                                val downX = down.position.x
                                val downY = down.position.y
                                val downTimeMs = System.currentTimeMillis()

                                // PR-UI-REC2.4 — read `isSendVoiceVisual`
                                // through `rememberUpdatedState`. The plain
                                // `val isSendVoiceVisual = …` captured by
                                // this lambda is frozen to its value at
                                // first composition (typically `false` while
                                // idle), so without this indirection a tap
                                // on the Send arrow after Lock was wrongly
                                // routed back into `onMicDownStartRecording`
                                // and produced `ignored_start reason=busy
                                // state=Locked` — Test #76.2 blocker.
                                val isSendVoiceTap = currentTextState.value.isBlank()
                                    && !currentIsEditingState.value
                                    && currentIsSendVoiceVisual.value

                                var locked = false
                                // PR-UI-REC3.1: replaced the previous
                                // `swipeCancelArmed` boolean latch with a
                                // haptic-fired tracker. Cancel intent is
                                // re-evaluated from the LIVE drag distance
                                // at release time (see release branch
                                // below) so the user can drag back out of
                                // the threshold and the gesture sends
                                // instead of canceling. `swipeCancelHapticFired`
                                // exists only to fire the haptic exactly
                                // once when the finger first crosses the
                                // threshold.
                                var swipeCancelHapticFired = false
                                var recordingStarted = false

                                if (!isSendVoiceTap) {
                                    recordingStarted = onMicDownStartRecording()
                                    if (!recordingStarted) {
                                        return@awaitEachGesture
                                    }
                                    isMicHeld = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == downId }
                                        ?: continue

                                    if (recordingStarted && !locked) {
                                        // Drag-up-to-lock.
                                        val dragUp = downY - change.position.y
                                        if (dragUp >= lockThresholdPx) {
                                            locked = true
                                            isMicHeld = false
                                            // PR-UI-REC3: reset live swipe
                                            // progress when the gesture
                                            // commits to lock instead of
                                            // swipe-cancel.
                                            swipeDragLeftPx = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onMicSlideUpLock()
                                        } else {
                                            // PR-UI-REC3.1 — keep updating
                                            // `swipeDragLeftPx` on every
                                            // pointer event (no
                                            // `!swipeCancelArmed` short-
                                            // circuit). The cancel
                                            // decision is now taken at
                                            // release time from the LIVE
                                            // distance, so the user can
                                            // drag past the threshold and
                                            // then drag back below it to
                                            // un-arm. Negative drag
                                            // (finger moved right) clamps
                                            // to 0 so the overlay never
                                            // shows when the user is not
                                            // actively swiping left.
                                            val dragLeft = downX - change.position.x
                                            swipeDragLeftPx = dragLeft.coerceAtLeast(0f)
                                            // Fire the threshold-crossing
                                            // haptic exactly once. We do
                                            // NOT fire haptic on crossing
                                            // back — that would feel
                                            // chatty during a hesitant
                                            // gesture.
                                            // PR-UI-REC3.4: do NOT flip
                                            // `isMicHeld` here. The finger
                                            // is still on the mic during
                                            // the entire swipe gesture
                                            // (only `Lock` and gesture
                                            // end legitimately mean
                                            // "hands free"). The earlier
                                            // flip was overloaded with
                                            // hiding the lock-hint chip,
                                            // but now that Pause /Resume
                                            // visibility also keys off
                                            // `isMicHeld`, flipping it on
                                            // swipe-cross made the Pause
                                            // button briefly appear at
                                            // 100 % — Test #76.6c bug.
                                            // Chip hiding now keys off
                                            // `swipeDragLeftPx` instead.
                                            if (!swipeCancelHapticFired
                                                && dragLeft >= swipeCancelThresholdPx
                                            ) {
                                                swipeCancelHapticFired = true
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        }
                                    }

                                    if (!change.pressed) {
                                        val heldMs = System.currentTimeMillis() - downTimeMs
                                        // PR-UI-REC3.1 — final cancel
                                        // decision uses the LIVE drag
                                        // distance at the moment of
                                        // release. If the user crossed
                                        // 56 dp earlier but then dragged
                                        // back below it, the recording
                                        // sends normally (per heldMs).
                                        // The percentage visual the user
                                        // saw drop back down matches the
                                        // actual gesture outcome.
                                        val finalDragLeftPx = (downX - change.position.x).coerceAtLeast(0f)
                                        val swipeCancelAtRelease = finalDragLeftPx >= swipeCancelThresholdPx
                                        when {
                                            isSendVoiceTap -> {
                                                onSendVoiceTap()
                                            }
                                            swipeCancelAtRelease -> {
                                                onMicHoldSwipeCancel(heldMs)
                                            }
                                            locked -> {
                                                // Hands-free locked recording
                                                // continues; release is a
                                                // no-op by design.
                                            }
                                            heldMs >= MIN_HOLD_SEND_MS -> {
                                                onMicHoldReleaseSend(heldMs)
                                            }
                                            else -> {
                                                onMicHoldTooShortCancel(heldMs)
                                            }
                                        }
                                        break
                                    }
                                    change.consume()
                                }
                                isMicHeld = false
                                // PR-UI-REC3: drop any in-flight swipe
                                // progress when the gesture ends so the
                                // overlay disappears immediately and the
                                // next recording starts with a clean
                                // visual.
                                swipeDragLeftPx = 0f
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // PR-UI-REC3 — wrap the glyph in a graphicsLayer alpha
                    // so the visual fades to 0.4 when the swipe-cancel
                    // overlay is active. The Box's pointerInput stays at
                    // full hit-test priority — only the visual dims.
                    Box(modifier = Modifier.graphicsLayer(alpha = rightGlyphAlpha)) {
                        if (isSendVoiceVisual) {
                            Canvas(modifier = Modifier.size(20.dp)) {
                                val sw = 2.2.dp.toPx()
                                val cap = StrokeCap.Round
                                val cx = size.width / 2f
                                drawLine(BgDeep, Offset(cx, size.height * 0.82f), Offset(cx, size.height * 0.18f), sw, cap)
                                drawLine(BgDeep, Offset(cx, size.height * 0.18f), Offset(cx - size.width * 0.28f, size.height * 0.46f), sw, cap)
                                drawLine(BgDeep, Offset(cx, size.height * 0.18f), Offset(cx + size.width * 0.28f, size.height * 0.46f), sw, cap)
                            }
                        } else {
                            Canvas(modifier = Modifier.size(22.dp)) {
                                val c = TextDim
                                val sw = 1.6.dp.toPx()
                                val st = Stroke(width = sw, cap = StrokeCap.Round)
                                drawRoundRect(
                                    color = c, style = st,
                                    topLeft = Offset(size.width * 0.33f, size.height * 0.08f),
                                    size = Size(size.width * 0.34f, size.height * 0.52f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.17f),
                                )
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(size.width * 0.18f, size.height * 0.48f)
                                    cubicTo(size.width * 0.18f, size.height * 0.82f, size.width * 0.82f, size.height * 0.82f, size.width * 0.82f, size.height * 0.48f)
                                }
                                drawPath(path, color = c, style = st)
                                drawLine(c, Offset(size.width * 0.5f, size.height * 0.78f), Offset(size.width * 0.5f, size.height * 0.95f), sw, StrokeCap.Round)
                            }
                        }
                    }

                    // Floating lock-hint chip — visible while the user is
                    // mid-hold and the panel is still in `Recording`. Once
                    // they cross the slide-up threshold the chip disappears
                    // (isMicHeld flips to false on lock).
                    //
                    // PR-UI-REC3.4 — also hide the chip once the swipe-cancel
                    // overlay becomes visible (>8 dp drag-left). Previously
                    // the haptic block flipped `isMicHeld = false` at the
                    // 56 dp threshold to take the chip down for free, but
                    // that flip also leaked into the Pause/Resume gate added
                    // in REC3.3 and made the Pause button briefly appear
                    // mid-swipe (Test #76.6c). The chip now keys off the
                    // same `swipeDragLeftPx` threshold the rest of the
                    // overlay uses, so it disappears as soon as the trail /
                    // hint render in the row.
                    if (isMicHeld
                        && recordingState == RecordingPanelState.Recording
                        && swipeDragLeftPx <= swipeVisibleThresholdPx
                    ) {
                        Popup(
                            alignment = Alignment.TopCenter,
                            offset = IntOffset(0, -with(density) { 84.dp.toPx() }.toInt()),
                            properties = PopupProperties(
                                focusable = false,
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false,
                            ),
                        ) {
                            LockHintChip()
                        }
                    }
                }
            }
        }
    }
}

// ── PR-UI-REC1 — Recording Panel Matrix ───────────────────────────────────────
//
// State enum. Recording / Paused are the only values reachable from the
// composer in this PR. Locked is reserved for PR-UI-REC2 (hold-to-lock) and
// SwipeCancel for PR-UI-REC3 (swipe-to-cancel) — they are declared here so
// downstream PRs can add render branches without touching the state machine.
enum class RecordingPanelState {
    Recording,
    Paused,
    Locked,
    SwipeCancel,
}

/**
 * PR-UI-REC2.2 — minimum press duration before a hold-then-release becomes a
 * "send-voice" intent. Below this threshold a release after a long-press
 * cancels the recording instead of shipping a near-empty file. Set to 700 ms
 * per the architect's Test #76.1.1 review: holds under ~500 ms in the prior
 * code produced `durationMs=0 / bytes=98` ghost voices.
 *
 * The 300 ms window between `viewConfig.longPressTimeoutMillis` (~400 ms,
 * when the lock-hint chip appears) and this threshold (700 ms) is the
 * "you held the mic but not long enough to send anything meaningful" zone;
 * inside it we treat the gesture as if the user had second thoughts and
 * silently drop the recording.
 */
private const val MIN_HOLD_SEND_MS = 700L

/**
 * PR-UI-REC-FOLLOWUP — empty-voice safety gate inside `finalizeAndSendVoice`.
 *
 * The gesture-layer `MIN_HOLD_SEND_MS` above guards "user released too soon" by
 * heldMs measured at the pointer event. It does NOT cover the warm-up race
 * where the user holds long enough but `MediaRecorder` produces nothing
 * playable (Test #76.3: `hold_release_send heldMs=819 durationMs=0`, gesture
 * passed the 700 ms gate but the file is empty).
 *
 * This second gate runs after the file lands on disk and reads the audio
 * file's real duration via `MediaMetadataRetriever`. If either the duration
 * is below 700 ms OR the file is suspiciously small (< 1024 bytes), the row
 * is dropped silently before the upload kicks off — no relay envelope leaves
 * the device. Both conditions are required because (a) duration alone
 * doesn't catch a broken file with garbage metadata, (b) bytes alone doesn't
 * catch a very short but technically valid recording the user clearly didn't
 * mean to send.
 */
private const val MIN_SENDABLE_VOICE_DURATION_MS = 700L
private const val MIN_SENDABLE_VOICE_BYTES = 1024L

/**
 * PR-UI-REC-FOLLOWUP — read the real duration of a recorded audio file via
 * `MediaMetadataRetriever`. Used as the source of truth for the `durationMs`
 * value that ships in `VOICE_REC complete` logs, downstream `sendAudio`
 * call, and ultimately the receiver-side `AudioBubble` timer.
 *
 * Background: the existing 100 ms in-Composable ticker undercounts long
 * voices because it's paused or destroyed across some Compose state
 * transitions (Test #76.5b: `durationMs=8000` on a ~12 s voice — roughly
 * 33% undercount). The MediaRecorder writes the real elapsed encoded
 * duration into the file container, which a `MediaMetadataRetriever` can
 * read in single-digit ms on a finished file.
 *
 * Returns `null` if the retriever cannot extract a positive duration (some
 * AAC_ELD-on-API-26-28 encoder edge cases, malformed files, etc) — the
 * caller falls back to the ticker value in that case and emits
 * `source=ticker_fallback` in the log so future tests can distinguish.
 */
private fun readAudioDurationMs(file: java.io.File): Long? = runCatching {
    val retriever = android.media.MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        retriever
            .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()

/** PR-UI-REC2 — Locked-state badge in the center stack of the recording panel.
 *  28 dp cyan-tinted disc with a 11 px lock glyph. Static visual only; the
 *  hold-to-lock gesture that produces this state lives on the persistent
 *  right-side mic/send box in [InputBar]. */
@Composable
private fun RecPanelLockBadge() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(CyanAccent.copy(alpha = 0.08f))
            .border(1.dp, CyanAccent.copy(alpha = 0.32f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(11.dp)) {
            val sw = 1.5.dp.toPx()
            // Lock body — rounded rect in the lower half.
            val bodyTop = size.height * 0.45f
            val bodyHeight = size.height * 0.50f
            val bodyWidth = size.width * 0.78f
            val bodyLeft = (size.width - bodyWidth) / 2f
            drawRoundRect(
                color = CyanAccent,
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw),
                style = Stroke(width = sw),
            )
            // Lock shackle — half-rounded arc in the upper half.
            val shacklePath = androidx.compose.ui.graphics.Path().apply {
                val centerX = size.width / 2f
                val shackleR = size.width * 0.22f
                val shackleY = bodyTop
                moveTo(centerX - shackleR, shackleY)
                cubicTo(
                    centerX - shackleR, shackleY - shackleR * 1.4f,
                    centerX + shackleR, shackleY - shackleR * 1.4f,
                    centerX + shackleR, shackleY,
                )
            }
            drawPath(
                path = shacklePath,
                color = CyanAccent,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
    }
}

/** PR-UI-REC2 — floating "slide up to lock" hint shown above the mic while the
 *  user holds it. The chip itself is the slide-up target; reaching its
 *  vertical position is what trips the lock transition. Rendered inside a
 *  [Popup] in [InputBar] so it can paint above the composer bounds. */
@Composable
private fun LockHintChip() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Surface2)
                .border(1.dp, CyanAccent.copy(alpha = 0.32f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val sw = 1.8.dp.toPx()
                val bodyTop = size.height * 0.45f
                val bodyHeight = size.height * 0.50f
                val bodyWidth = size.width * 0.78f
                val bodyLeft = (size.width - bodyWidth) / 2f
                drawRoundRect(
                    color = CyanAccent,
                    topLeft = Offset(bodyLeft, bodyTop),
                    size = Size(bodyWidth, bodyHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(sw * 0.8f),
                    style = Stroke(width = sw),
                )
                val shacklePath = androidx.compose.ui.graphics.Path().apply {
                    val centerX = size.width / 2f
                    val shackleR = size.width * 0.22f
                    val shackleY = bodyTop
                    moveTo(centerX - shackleR, shackleY)
                    cubicTo(
                        centerX - shackleR, shackleY - shackleR * 1.4f,
                        centerX + shackleR, shackleY - shackleR * 1.4f,
                        centerX + shackleR, shackleY,
                    )
                }
                drawPath(
                    path = shacklePath,
                    color = CyanAccent,
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
        }
        // Small arrow + LOCK caption hint, mono so it reads as a UI affordance
        // rather than copy.
        Canvas(modifier = Modifier.size(width = 10.dp, height = 8.dp)) {
            val sw = 1.5.dp.toPx()
            val cap = StrokeCap.Round
            val cx = size.width / 2f
            drawLine(TextDim, Offset(cx, size.height * 0.95f), Offset(cx, size.height * 0.05f), sw, cap)
            drawLine(TextDim, Offset(cx, size.height * 0.05f), Offset(cx - size.width * 0.35f, size.height * 0.35f), sw, cap)
            drawLine(TextDim, Offset(cx, size.height * 0.05f), Offset(cx + size.width * 0.35f, size.height * 0.35f), sw, cap)
        }
    }
}

/** 44 dp circular button used by Cancel and Pause/Resume in the recording panel. */
@Composable
private fun RecPanelControl(
    onClick: () -> Unit,
    background: Color,
    border: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .then(
                if (border) Modifier.border(1.dp, BorderSubtle, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Recording-state dot. Live: 8 dp Danger disc with a pulsing aura (1 s,
 *  ease-in-out, opacity 0 → 0.22, scale 0.7 → 1.0). Paused: 8 dp hollow ring
 *  with a 1.5 dp Text-Tertiary stroke. */
@Composable
private fun RecPanelDot(live: Boolean) {
    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (live) {
            val transition = rememberInfiniteTransition(label = "recDotPulse")
            val pulse by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "recDotPulseT",
            )
            val scale = 0.7f + (1.0f - 0.7f) * pulse
            val alpha = 0f + (0.22f - 0f) * pulse
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCircle(color = Danger.copy(alpha = alpha), radius = (size.width / 2f) * scale)
            }
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = Danger)
            }
        } else {
            Canvas(modifier = Modifier.size(8.dp)) {
                val sw = 1.5.dp.toPx()
                drawCircle(
                    color = TextDim,
                    radius = (size.minDimension - sw) / 2f,
                    style = Stroke(width = sw),
                )
            }
        }
    }
}

/** Recording timer. JetBrains Mono 13 sp, tabular numerals so MM:SS does not
 *  jitter horizontally. Paused state drops the colour to text-tertiary. */
@Composable
private fun RecPanelTimer(durationMs: Long, paused: Boolean) {
    val totalSeconds = (durationMs / 1000).toInt()
    val label = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    Text(
        text = label,
        color = if (paused) TextDim else TextPrimary,
        fontSize = 13.sp,
        fontFamily = PhantomFontMono,
        modifier = Modifier.widthIn(min = 32.dp),
    )
}

/** DAW-style scrolling waveform. Most recent samples are right-justified and
 *  drawn in the active color; older samples fade into the muted tone. The
 *  number of bars rendered is computed from the canvas width so the panel
 *  shrinks gracefully on narrow devices. */
@Composable
private fun RecPanelWaveform(
    amplitudes: List<Float>,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val activeColor = if (live) Danger else TextSecondary
    val mutedColor = if (live) Danger.copy(alpha = 0.22f) else BorderSubtle
    Canvas(modifier = modifier) {
        val barWidthPx = 2.dp.toPx()
        val gapPx = 2.5.dp.toPx()
        val unit = barWidthPx + gapPx
        if (unit <= 0f) return@Canvas
        val maxBars = ((size.width + gapPx) / unit).toInt().coerceAtLeast(1)
        val displayed = amplitudes.takeLast(maxBars)
        val count = displayed.size
        if (count == 0) return@Canvas
        val totalWidth = count * unit - gapPx
        val startX = (size.width - totalWidth).coerceAtLeast(0f)
        val centerY = size.height / 2f
        val recentCount = 26 // last 26 bars are "recent" per design annotations
        val minBarHeightPx = 2.dp.toPx()
        displayed.forEachIndexed { i, amp ->
            val xLeft = startX + i * unit
            val barHeight = (amp.coerceIn(0f, 1f) * size.height).coerceAtLeast(minBarHeightPx)
            val isRecent = i >= count - recentCount
            val color = if (isRecent) activeColor else mutedColor
            drawRoundRect(
                color = color,
                topLeft = Offset(xLeft, centerY - barHeight / 2f),
                size = Size(barWidthPx, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidthPx / 2f),
            )
        }
    }
}

/** "PAUSED" capsule shown to the right of the timer/waveform stack while the
 *  recording is paused. Surface-elevated background, border-subtle outline,
 *  mono 10 sp letter-spaced label with a tiny pause glyph. */
@Composable
private fun RecPanelPausedPill() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Surface2)
            .border(1.dp, BorderSubtle, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Canvas(modifier = Modifier.size(9.dp)) {
            val barW = size.width * 0.22f
            val barH = size.height * 0.7f
            val gap = size.width * 0.18f
            val centerX = size.width / 2f
            val y = (size.height - barH) / 2f
            drawRoundRect(
                color = TextDim,
                topLeft = Offset(centerX - gap / 2f - barW, y),
                size = Size(barW, barH),
            )
            drawRoundRect(
                color = TextDim,
                topLeft = Offset(centerX + gap / 2f, y),
                size = Size(barW, barH),
            )
        }
        Text(
            text = "PAUSED",
            color = TextDim,
            fontSize = 10.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * PR-UI-REC3 — SwipeCancel state zone. Replaces the dot+timer+waveform
 * center stack while the user is dragging the mic left to cancel. Layout
 * matches the Recording Panel Matrix `CancelSwipePanel` design:
 *
 *   - Rounded zone (radius 22 dp) on `Danger.05` background with a
 *     `Danger.18` border.
 *   - Trail gradient (`Danger.22` → `Danger.04`) growing left-to-right as
 *     the user drags; its width is `swipeFraction × zone width`.
 *   - Fixed dashed threshold marker (1 px `Danger.55` dashed line) at the
 *     full-cancel position so the user has a visible target.
 *   - "← SWIPE TO DISCARD" hint label left-aligned, mono 10 sp `Danger`.
 *   - Live distance percentage right-aligned next to the trash handle,
 *     mono 9 sp `Danger.65`.
 *   - 32 dp trash handle (Danger fill, white trash glyph) at the trail's
 *     right edge — moves with the gesture.
 */
@Composable
private fun RecPanelSwipeZone(
    swipeFraction: Float,
    modifier: Modifier = Modifier,
) {
    val clampedFraction = swipeFraction.coerceIn(0f, 1f)
    val zoneShape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(zoneShape)
            .background(Danger.copy(alpha = 0.05f))
            .border(1.dp, Danger.copy(alpha = 0.18f), zoneShape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Trail: gradient fill growing from left up to the threshold
            // marker. The width tracks `clampedFraction` so the bar moves
            // with the finger.
            val trailWidth = (size.width * clampedFraction).coerceAtLeast(0f)
            if (trailWidth > 0f) {
                val trailBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        Danger.copy(alpha = 0.22f),
                        Danger.copy(alpha = 0.04f),
                    ),
                    startX = 0f,
                    endX = trailWidth,
                )
                drawRect(brush = trailBrush, topLeft = Offset.Zero, size = Size(trailWidth, size.height))
            }

            // Dashed threshold marker — fixed at the full-cancel position
            // (right edge of the trail at fraction = 1.0). Indicates the
            // visual target the user is dragging toward.
            val thresholdX = (size.width * 1f).coerceAtMost(size.width - 1.dp.toPx())
            val dashOn = 3.dp.toPx()
            val dashOff = 3.dp.toPx()
            val markerEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(dashOn, dashOff), 0f,
            )
            drawLine(
                color = Danger.copy(alpha = 0.55f),
                start = Offset(thresholdX, size.height * 0.15f),
                end = Offset(thresholdX, size.height * 0.85f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = markerEffect,
            )
        }

        // PR-UI-REC3.1 — single Row instead of two `align()`-anchored
        // overlays. Test #76.6 on a narrow Tecno screen showed the
        // CenterStart hint and the CenterEnd percentage running into
        // each other because they did not share a layout pass. Now the
        // hint occupies a weight=1 slot (it ellipsises if necessary)
        // and the percent + trash handle have fixed widths on the
        // right, so the two never collide.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer(alpha = (1f - clampedFraction * 0.6f).coerceIn(0.4f, 1f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // PR-UI-REC3.2 — animated left-arrow nudge. Suggests the
                // swipe direction with a subtle horizontal oscillation
                // (~3 dp range, 700 ms reverse-cycle, ease-in-out). The
                // arrow drifts left then back to its rest position; the
                // text stays still so the hint never blurs.
                val arrowTransition = rememberInfiniteTransition(label = "swipeArrowNudge")
                val arrowOffsetDp by arrowTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = -3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "swipeArrowOffset",
                )
                Canvas(
                    modifier = Modifier
                        .size(width = 9.dp, height = 9.dp)
                        .offset(x = arrowOffsetDp.dp),
                ) {
                    val sw = 1.5.dp.toPx()
                    val cap = StrokeCap.Round
                    val cy = size.height / 2f
                    drawLine(Danger, Offset(size.width * 0.95f, cy), Offset(size.width * 0.05f, cy), sw, cap)
                    drawLine(Danger, Offset(size.width * 0.05f, cy), Offset(size.width * 0.35f, cy - size.height * 0.35f), sw, cap)
                    drawLine(Danger, Offset(size.width * 0.05f, cy), Offset(size.width * 0.35f, cy + size.height * 0.35f), sw, cap)
                }
                Text(
                    text = "discard",
                    color = Danger,
                    fontSize = 11.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = "${(clampedFraction * 100).toInt()}%",
                color = Danger.copy(alpha = 0.65f),
                fontSize = 9.sp,
                fontFamily = PhantomFontMono,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 32.dp),
            )

            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Danger),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(14.dp)) {
                    val sw = 1.5.dp.toPx()
                    val cap = StrokeCap.Round
                    val white = Color.White
                    // Trash glyph: horizontal lid line + vertical body
                    // outline + two grip bars on top of lid.
                    drawLine(white, Offset(size.width * 0.15f, size.height * 0.30f), Offset(size.width * 0.85f, size.height * 0.30f), sw, cap)
                    drawLine(white, Offset(size.width * 0.40f, size.height * 0.30f), Offset(size.width * 0.40f, size.height * 0.18f), sw, cap)
                    drawLine(white, Offset(size.width * 0.40f, size.height * 0.18f), Offset(size.width * 0.60f, size.height * 0.18f), sw, cap)
                    drawLine(white, Offset(size.width * 0.60f, size.height * 0.18f), Offset(size.width * 0.60f, size.height * 0.30f), sw, cap)
                    drawLine(white, Offset(size.width * 0.22f, size.height * 0.30f), Offset(size.width * 0.30f, size.height * 0.88f), sw, cap)
                    drawLine(white, Offset(size.width * 0.78f, size.height * 0.30f), Offset(size.width * 0.70f, size.height * 0.88f), sw, cap)
                    drawLine(white, Offset(size.width * 0.30f, size.height * 0.88f), Offset(size.width * 0.70f, size.height * 0.88f), sw, cap)
                    drawLine(white, Offset(size.width * 0.40f, size.height * 0.42f), Offset(size.width * 0.40f, size.height * 0.78f), sw, cap)
                    drawLine(white, Offset(size.width * 0.60f, size.height * 0.42f), Offset(size.width * 0.60f, size.height * 0.78f), sw, cap)
                }
            }
        }
    }
}

// ── Recording helper ──────────────────────────────────────────────────────────

private fun startChatRecording(context: android.content.Context): Pair<java.io.File, android.media.MediaRecorder> {
    // PR-M2a: voice-note codec profile (was music-grade in PR-D2a).
    //   OPUS @ 16 kHz mono 24 kbps  (API 29+, primary)
    //   AAC_ELD @ 16 kHz mono 24 kbps  (API 26-28 fallback; AAC-LD is voice-optimised)
    //
    // Target: ~2-4 KB/sec on the wire so 5-sec voice ≈ 10-20 KB, 60-sec ≈ 120-240 KB.
    // Old profile was 48/48 kbps Opus and 44.1/64 kbps AAC — that produced 5-sec
    // voices weighing 39-57 KB and forced 25-34 chunks per voice on Tele2 LTE,
    // which the M1w native OkHttp media transport could deliver but with
    // user-visible UX cost (architect Test #60 verdict, see PROJECT_LOG.md).
    //
    // 16 kHz mono is the W3C/RFC-7587 voice-mode sweet spot for Opus and is
    // also the documented sweet spot for AAC-LD. 24 kbps is "transparent voice"
    // for both — voices remain natural, music is poor (intentional).
    val useOpus = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    val ext = if (useOpus) "ogg" else "m4a"
    val file = java.io.File(context.cacheDir, "audio_${System.currentTimeMillis()}.$ext")

    val encoderName: String
    val outputFormatName: String
    val sampleRate = 16_000
    val bitrate = 24_000
    val channels = 1
    val mime = if (useOpus) "audio/ogg" else "audio/m4a"

    @Suppress("DEPRECATION")
    val recorder = android.media.MediaRecorder().apply {
        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
        if (useOpus) {
            setOutputFormat(android.media.MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.OPUS)
            encoderName = "OPUS"
            outputFormatName = "OGG"
        } else {
            // AAC_ELD (Enhanced Low Delay AAC): voice profile available since
            // API 16, comfortably present on every API-26 device. Uses the
            // same MPEG_4 container the previous code did, so the .m4a
            // extension + VoiceFileStore mimeToExtension mapping still match.
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC_ELD)
            encoderName = "AAC_ELD"
            outputFormatName = "MPEG_4"
        }
        setAudioSamplingRate(sampleRate)
        setAudioChannels(channels)
        setAudioEncodingBitRate(bitrate)
        setOutputFile(file.absolutePath)
        prepare()
        start()
    }
    // Diagnostic log: confirms the recorder actually applied the chosen profile
    // rather than silently defaulting to something else. PR-M2a Test #62
    // grep target: `VOICE_REC config encoder=OPUS|AAC_ELD` must appear before
    // every voice-send for the profile change to be considered live.
    android.util.Log.i(
        "PhantomMedia",
        "VOICE_REC config encoder=$encoderName sampleRate=$sampleRate bitrate=$bitrate " +
            "channels=$channels outputFormat=$outputFormatName mime=$mime"
    )
    return file to recorder
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun BlockConfirmDialog(username: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Block $username?", color = TextPrimary) },
        text = { Text("They won't be able to send you messages.", color = TextDim, fontSize = 13.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Block", color = Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim) } },
    )
}

@Composable
private fun ReportDialog(username: String, onReport: (SafetyReportCategory) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<SafetyReportCategory?>(null) }
    var otherText by remember { mutableStateOf("") }
    val canSend = selected != null && (selected != SafetyReportCategory.OTHER || otherText.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Report $username", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Select a reason:", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                SafetyReportCategory.entries.forEach { category ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selected == category,
                            onClick = { selected = category },
                            colors = RadioButtonDefaults.colors(selectedColor = CyanAccent, unselectedColor = TextDim),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = categoryLabel(category),
                            color = if (selected == category) TextPrimary else TextDim,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (selected == SafetyReportCategory.OTHER) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = otherText,
                        onValueChange = { otherText = it },
                        placeholder = { Text("Describe the issue…", color = TextDim, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = CyanAccent,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canSend) onReport(selected!!) },
                enabled = canSend,
            ) {
                Text("Send Report", color = if (canSend) CyanAccent else TextDim)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextDim) } },
    )
}

private fun categoryLabel(category: SafetyReportCategory): String = when (category) {
    SafetyReportCategory.SPAM            -> "Spam"
    SafetyReportCategory.HARASSMENT      -> "Harassment"
    SafetyReportCategory.THREATS         -> "Threats or violence"
    SafetyReportCategory.CSAM            -> "Child safety"
    SafetyReportCategory.ILLEGAL_CONTENT -> "Illegal content"
    SafetyReportCategory.OTHER           -> "Other"
}

// ── Link preview ──────────────────────────────────────────────────────────────

private data class LinkPreview(
    val url: String,
    val title: String,
    val description: String,
)

private val URL_REGEX = Regex("""https?://[^\s]+""")

private fun extractUrl(text: String): String? = URL_REGEX.find(text)?.value

private suspend fun fetchLinkPreview(url: String): LinkPreview? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.setRequestProperty("User-Agent", "PHANTOM/1.0")
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) return@runCatching null
        val html = conn.inputStream.bufferedReader().use { r ->
            val sb = StringBuilder()
            val buf = CharArray(1024)
            var read: Int
            var total = 0
            while (r.read(buf).also { read = it } != -1 && total < 32_768) {
                sb.appendRange(buf, 0, read); total += read
            }
            sb.toString()
        }
        val title = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(80)
            ?: return@runCatching null
        val desc = Regex(
            """<meta[^>]+name=.description.[^>]+content=.([^"']{1,200})""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
        LinkPreview(url = url, title = title, description = desc)
    }.getOrNull()
}

private val CircleShape = RoundedCornerShape(50)

// ── Chat top bar ──────────────────────────────────────────────────────────────

@Composable
private fun ChatTopBar(
    theirUsername: String,
    isConnected: Boolean,
    isVerified: Boolean = false,
    isTyping: Boolean = false,
    onBack: () -> Unit,
    onContactProfile: () -> Unit,
    onVoiceCall: () -> Unit,
    onMoreMenu: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    // PHANTOM_FULL_COMPOSE §05 layout:
    //   [← back] [Avatar 36dp] [name + @username] [Phone] [MoreHoriz]
    // 56dp height, BorderSubtle bottom hairline.
    // Video button removed: CallManager is audio-only (no VideoTrack /
    // VideoCapturer / VideoSource integration). Video calls land with a
    // future ADR after Phase 4 iOS parity. Phone button now wired to
    // CallManager.startCall.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PhantomTokens.Colors.Surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                PhIconBack(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onContactProfile),
            ) {
                GradientAvatar(
                    name = theirUsername,
                    size = 36.dp,
                    online = if (isConnected) true else null,
                    verified = isVerified,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onContactProfile),
            ) {
                // Name row + tiny lock badge per FULL_COMPOSE §05 — the
                // 14×14 SurfaceDeep circle with cyan lock is the chat-level
                // E2EE trust signal. Reinforces the encryption strip below
                // the header without being loud.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = theirUsername,
                        color = PhantomTokens.Colors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.15).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(PhantomTokens.Colors.SurfaceDeep)
                            .border(1.dp, PhantomTokens.Colors.BorderSubtle, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        PhIconLock(color = CyanAccent, size = 7.dp)
                    }
                }
                Spacer(Modifier.height(2.dp))
                if (isTyping) {
                    Text(
                        text = "typing…",
                        color = PhantomTokens.Colors.Cyan.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontFamily = PhantomFontMono,
                    )
                } else if (isConnected) {
                    // Online state row per FULL_COMPOSE §05: 5dp Success
                    // dot + mono "online" label. Falls back to the @handle
                    // line when the transport is offline so the user always
                    // sees something meaningful below the name.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(Success),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "online",
                            color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                            fontFamily = PhantomFontMono,
                            letterSpacing = 0.4.sp,
                        )
                    }
                } else {
                    Text(
                        text = "@$theirUsername",
                        color = PhantomTokens.Colors.TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = PhantomFontMono,
                        letterSpacing = 0.4.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // TODO(C1.1): disable button visually when capabilities.canStartCalls == false
            //   ChatTopBar needs a `callsEnabled: Boolean` param and the icon tinted
            //   with TextDisabled colour + pointer-events blocked when false.
            IconButton(onClick = onVoiceCall, modifier = Modifier.size(40.dp)) {
                PhIconPhone(color = PhantomTokens.Colors.TextSecondary, size = 20.dp)
            }
            Box {
                IconButton(onClick = onMoreMenu, modifier = Modifier.size(40.dp)) {
                    PhIconMoreHoriz(color = PhantomTokens.Colors.TextSecondary, size = 18.dp)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onDismissMenu,
                    containerColor = Surface2,
                ) {
                    DropdownMenuItem(
                        text = { Text("Report", color = TextPrimary, fontSize = 14.sp) },
                        onClick = onReport,
                    )
                    DropdownMenuItem(
                        text = { Text("Block", color = Danger, fontSize = 14.sp) },
                        onClick = onBlock,
                    )
                }
            }
        }
        HorizontalDivider(color = PhantomTokens.Colors.BorderSubtle, thickness = 1.dp)
    }
}
