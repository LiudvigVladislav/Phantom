# PR-M1w Design — Encrypted Media Upload Wire-Up for 1:1 Voice Messages

**Date:** 2026-05-17  
**Author:** Architect pass (pre-implementation)  
**Target branch:** `feat/pr-m1w-voice-upload`  
**Prerequisite PRs (verified merged into `master` HEAD `9c502b42`):**
- PR #168 M1r — relay `POST /media/upload-chunk` + `GET /media/chunk/{mediaId}/{idx}` live on `relay.phntm.pro`, smoke-tested (60 KB body → 413 confirmed via curl).
- PR #169 M1k — `MediaUploadTransport`, `MediaCrypto`, `VoiceManifestV2`, `MediaChunker` merged. `MediaCryptoTest` 9/9 green on instrumented Android emulator (real libsodium native binding, Pixel_8_Pro AVD).
- PR #170 C1 — `TransportCapabilities` + `TransportCapabilitiesResolver` source file at `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/TransportCapabilities.kt`. Currently `canSendVoice = false` for `RestActive` / `WsCandidate` — Section 8 flip is the final commit on this branch.

> **Note on architect run state.** The original architect pass observed a stale local
> master at `33974355` (pre-merge) and emitted 4 "contradictions" in the now-removed
> trailing section claiming the prerequisite PRs were absent. After `git fetch + reset
> --hard origin/master`, all four prerequisite files (`services/relay/src/media.rs`,
> `MediaUploadTransport.kt`, `MediaCrypto.kt`, `VoiceManifestV2.kt`,
> `MediaChunker.kt`, `TransportCapabilities.kt`) are present at `origin/master` HEAD
> `9c502b42`. The kmp-builder agent in the implementation session must `git fetch` and
> work from `origin/master` tip — do not trust any stale local state. Q7 in Section 11
> is consequently obsolete (`TransportCapabilitiesResolver` IS a source file, see
> `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/TransportCapabilities.kt`
> lines 110–159). The other 6 open questions (Q1–Q6) remain valid and need Vladislav's
> apply before code lands.

---

## 1. Summary

PR-M1w replaces the current multi-envelope chunked voice path (`audio_chunk` ratchet
envelopes per slice via `/relay/send`) with a two-phase encrypted-media protocol:
the sender encrypts audio with XChaCha20-Poly1305, uploads raw ciphertext chunks
directly to the relay's dedicated `/media/upload-chunk` endpoint (no ratchet overhead
per chunk), then sends exactly one `voice_v2` manifest through the existing ratchet
envelope pipeline. The receiver downloads and reassembles chunks via
`/media/chunk/{mediaId}/{idx}`, decrypts the blob, and presents a single playable
voice message. This is architecturally cleaner than the D2b path because the relay
stores opaque ciphertext blobs (no envelope overhead per chunk, no ratchet chain
advancement per chunk), the payload per HTTP call is within body caps without tuning
chunk size, and the receiver's state machine is a clean download task rather than an
accumulate-N-envelopes pattern. The `canSendVoice = true` flip for Limited realtime
(`RestActive` / `WsCandidate`) is the final commit on this branch, gated on Test #56
real-device evidence.

---

## 2. Affected Files

### commonMain Kotlin

| File | Action | Purpose |
|---|---|---|
| `shared/core/messaging/src/commonMain/.../MessagePayload.kt` | **Edit** | Add `TYPE_VOICE_V2 = "voice_v2"` constant |
| `shared/core/messaging/src/commonMain/.../DefaultMessagingService.kt` | **Edit** | Replace `sendAudio` upload body; add `voice_v2` receive handler; add in-progress guard |
| `shared/core/storage/src/commonMain/.../MessageRepository.kt` | **Edit** | Add `UPLOADING`, `DOWNLOADING` variants to `MessageStatus` enum |
| `shared/core/storage/src/commonMain/.../VoiceV2DownloadRepository.kt` | **New** | Interface for durable download task table |
| `shared/core/storage/src/commonMain/.../SqlDelightVoiceV2DownloadRepository.kt` | **New** | SQLDelight-backed impl |

### SQLDelight

| File | Action | Purpose |
|---|---|---|
| `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/VoiceV2Download.sq` | **New** | Queries for `voice_v2_downloads` table |
| `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/17.sqm` | **New** | Schema migration — adds `voice_v2_downloads` table |

### androidMain

| File | Action | Purpose |
|---|---|---|
| `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt` | **Edit** | Construct `KtorMediaUploadTransport`; inject into `DefaultMessagingService`; wire token provider + refresher lambdas |
| `apps/android/src/androidMain/kotlin/phantom/android/screens/chat/ChatScreen.kt` | **Edit** | Map `UPLOADING`/`DOWNLOADING` status to spinner indicator in `AudioBubble`; add "still uploading" snackbar copy |

### iosMain

No UI changes. `MessagePayload.kt` and `MessageRepository.kt` are `commonMain`; they
compile on iOS. Adding `TYPE_VOICE_V2` and two new `MessageStatus` variants is
additive and requires no iosMain source changes, but the iOS target **will recompile**
the affected KMP modules on the next iOS build. The iOS app does not yet render voice
messages (Alpha-2 scope per strategic decisions 2026-05-14), so the new enum variants
and payload type are inert there.

### Tests

| File | Action | Purpose |
|---|---|---|
| `shared/core/messaging/src/commonTest/.../VoiceV2SendReceiveTest.kt` | **New** | Manifest round-trip; upload loop state machine; failure paths |
| `shared/core/messaging/src/commonTest/.../VoiceV2DownloadOrchestratorTest.kt` | **New** | Download task state machine; 404 / sha256 mismatch / process death recovery |
| `shared/core/storage/src/commonTest/.../VoiceV2DownloadRepositoryContractTest.kt` | **New** | Contract test for new repo interface (mirrors `VoiceChunkRepositoryContractTest`) |
| `shared/core/transport/src/commonTest/.../KtorMediaUploadTransportTest.kt` | **New** | uploadChunk / downloadChunk 201/200/401/409/413/404 response handling |
| `shared/core/transport/src/commonTest/.../TransportCapabilitiesResolverTest.kt` | **Edit** | Update `canSendVoice` assertions for `RestActive`/`WsCandidate` after capability flip |
| `apps/android/src/.../androidUnitTest/.../VoiceV2SenderRetryTest.kt` | **New** | Sender token-refresh logic with mocked transport |
| `apps/android/src/.../androidUnitTest/.../VoiceV2ReceiverHandlerTest.kt` | **New** | Receiver handler for `voice_v2` type |

---

## 3. Send Path — Detailed Contract

### Entry point

`DefaultMessagingService.sendAudio(conversationId, audioBytes, durationMs, mimeType)`

### Step-by-step

**Step 0 — Pre-checks**

```
if (audioBytes.size > MAX_AUDIO_BYTES) → Result.failure(IllegalArgumentException)
if (!canSendVoice())                   → Result.failure + log VOICE_TX blocked_limited_realtime
```

**Step 1 — In-progress guard**

```kotlin
// New field on DMS:
private val voiceSendInProgress = mutableSetOf<String>() // guarded by sessionMutexesLock
```

Under `mutexFor(conversationId)`:
```
if (voiceSendInProgress.contains(conversationId))
  → return Result.failure(IllegalStateException("A voice message is still uploading. Please wait."))
voiceSendInProgress.add(conversationId)
```
Remove from set in `finally` block of the upload section.

**Step 2 — Local row insert (QUEUED)**

```kotlin
val localMsgId = uuid4().toString()
messageRepository.insertMessage(
    MessageEntity(
        id          = localMsgId,
        conversationId,
        ciphertext  = ByteArray(0),
        plaintextCache = "[AUDIO_LOCAL:$localAudioFilePath]",  // see Section 11, Q1
        sent        = true,
        status      = MessageStatus.QUEUED,
        createdAt   = insertedAtMs,
        expiresAtMs = outgoingExpiresAtMs,
    )
)
```

`[AUDIO_LOCAL:<path>]` stores the path to the temp audio file on disk that the
sender holds until send completes. On send success this prefix stays as-is for the
sender's own playback (the file is not deleted). `AudioBubble` detects `[AUDIO_LOCAL:`
and reads the file directly rather than decoding Base64. If the local file is absent
(device reboot, explicit clear), the bubble shows "Голосовое сообщение" text fallback.
See Section 11, Q1 for the open question on whether to keep Base64 here instead.

**Step 3 — Encrypt**

```kotlin
val encResult = MediaCrypto.encryptVoice(audioBytes)
// encResult: EncryptResult(mediaId, mediaKey, nonce, ciphertext, plaintextSha256)
```

Log:
```
MEDIA_TX encrypt_ok mediaId=<take(8)> ciphertextBytes=N plaintextBytes=M
```
`mediaKey`, `nonce`, `ciphertext` never appear in any log line.

**Step 4 — Chunk**

```kotlin
val chunks: List<ByteArray> = MediaChunker.chunk(encResult.ciphertext)
// chunkSize = MediaChunker.TARGET_RAW_CHUNK_BYTES (1700)
val total = chunks.size
```

Log:
```
MEDIA_TX chunk_split mediaId=<take(8)> chunkCount=N totalCiphertextBytes=M
```

**Step 5 — Status → UPLOADING**

```kotlin
messageRepository.updateStatus(localMsgId, MessageStatus.UPLOADING)
```

**Step 6 — Upload loop**

```kotlin
for (idx in 0 until total) {
    uploadChunkWithRefresh(
        localMsgId, mediaId = encResult.mediaId, idx, total,
        chunkBytes = chunks[idx]
    )
    // Throws on terminal failure; caught by outer runCatching.
}
```

`uploadChunkWithRefresh` internal contract (mirrors `RestFallbackOrchestrator.sendEnvelope`):

```
maxRefreshCycles = 3
for cycle in 1..maxRefreshCycles:
    token = tokenProvider()          // reads current session token, no network call
    outcome = mediaUploadTransport.uploadChunk(token, mediaId, idx, total, chunkBytes)
    when outcome:
        201 (stored) or 200 (duplicate) →
            log "MEDIA_TX chunk_uploaded mediaId=<take8> idx=N/total status=stored|duplicate"
            return success
        MediaAuthException (401) →
            if cycle < maxRefreshCycles:
                newToken = refreshToken()
                // newToken replaces cache; next cycle calls tokenProvider() again
                continue
            else:
                throw MediaAuthException("auth_refresh_exhausted after $maxRefreshCycles cycles")
        MediaConflictException(reason="ciphertext_mismatch") →
            throw immediately — bug, not retryable
            log "MEDIA_TX chunk_conflict_mismatch mediaId=<take8> idx=N"
        MediaConflictException(reason="total_mismatch") →
            throw immediately — bug, not retryable
        MediaQuotaException →
            throw immediately — relay quota, not retryable
            log "MEDIA_TX chunk_quota_exceeded mediaId=<take8> reason=<take60>"
        413 / NotFoundException →
            throw immediately
        MediaTransportException (network/timeout) →
            bounded retry with backoff (5 attempts, delays [1s,3s,8s,20s,60s])
            after exhaustion: throw
```

Terminal failure in any chunk:
```kotlin
messageRepository.updateStatus(localMsgId, MessageStatus.FAILED)
voiceSendInProgress.remove(conversationId) // in finally
return // manifest NOT sent
```

Log on loop completion:
```
MEDIA_TX upload_complete mediaId=<take8> chunks=N
```

**Step 7 — Build and send manifest**

All chunks successfully stored. Build manifest:
```kotlin
val manifest = VoiceManifestV2(
    type          = "voice_v2",
    mediaId       = encResult.mediaId,
    mediaKey      = Base64.encode(encResult.mediaKey),        // b64
    nonce         = Base64.encode(encResult.nonce),           // b64, 24 bytes
    alg           = "xchacha20poly1305-v1",
    durationMs    = durationMs,
    mime          = mimeType,
    chunkCount    = total,
    encryptedSizeBytes = encResult.ciphertext.size,
    plainSizeBytes     = audioBytes.size,
    sha256        = Base64.encode(encResult.plaintextSha256), // b64, 32 bytes
)
val payloadBytes = json.encodeToString(manifest).encodeToByteArray()
```

Encrypt manifest through ratchet pipeline (same as `sendMessage`):
```kotlin
val encrypted = encryptUnderLock(conversationId, recipientPublicKeyHex, payloadBytes)
val ciphertext = json.encodeToString(encrypted).encodeToByteArray()
val paddedCiphertext = MessagePadding.pad(ciphertext)
val envelopeId = uuid4().toString()
val sent = transport.send(RelayMessage.Send(
    to            = recipientPublicKeyHex,
    from          = "",
    sealedSender  = Base64.encode(SealedSender.seal(identity.publicKeyHex, hexToBytes(recipientPublicKeyHex))),
    payload       = paddedCiphertext.encodeBase64(),
    messageId     = envelopeId,
))
```

Log:
```
MEDIA_TX manifest_sent mediaId=<take8> envelopeId=<take8> chunkCount=N
```

`mediaKey`, `nonce`, `sha256` never in logs.

If `transport.send` returns `false`:
- The manifest envelope is already in the outbox durable store (D2b.1 pipeline; see
  `HybridRelayTransport` / REST fallback `sendEnvelope` with Idempotency-Key). The
  orchestrator will retry delivery automatically. Do NOT mark `FAILED`.
- Leave row at `UPLOADING` (chunks are already on relay); the row transitions to `SENT`
  when the transport-level ACK arrives via the `acks` flow (same path as text messages).

**Step 8 — Status → SENT on transport ACK**

No change to the existing `acks.onEach` handler in `startReceiving`. The `localMsgId`
row will receive `RELAYED` or `DELIVERED` via the same flow already wired for all outbound messages.

**Step 9 — Cleanup**

```kotlin
conversationRepository.upsertConversation(conv.copy(
    lastMessagePreview = "Voice message",
    lastMessageAt      = Clock.System.now().toEpochMilliseconds(),
))
voiceSendInProgress.remove(conversationId)
```

**Full log spec — send path**

```
MEDIA_TX encrypt_ok mediaId=<8> ciphertextBytes=N plaintextBytes=M
MEDIA_TX chunk_split mediaId=<8> chunkCount=N totalCiphertextBytes=M
MEDIA_TX chunk_uploaded mediaId=<8> idx=N/total status=stored|duplicate
MEDIA_TX chunk_conflict_mismatch mediaId=<8> idx=N      [ERROR — should never happen for fresh mediaId]
MEDIA_TX chunk_quota_exceeded mediaId=<8> reason=<60>   [WARN — terminal]
MEDIA_TX upload_complete mediaId=<8> chunks=N
MEDIA_TX manifest_sent mediaId=<8> envelopeId=<8> chunkCount=N
MEDIA_TX send_failed_no_manifest mediaId=<8> reason=<60> [WARN — terminal failure before manifest]
```

---

## 4. Receive Path — Detailed Contract

### Handler dispatch

In `DefaultMessagingService.handleDeliver` / payload dispatcher (around line 1273 in
current master), after the existing guard for `GROUP_TYPES` and `TYPE_AUDIO_CHUNK`:

```kotlin
if (payload.type == MessagePayload.TYPE_VOICE_V2) {
    handleVoiceV2Manifest(deliver, payload, senderPubKeyHex, nowMs = nowMs)
    return@runCatching
}
```

### `handleVoiceV2Manifest` — step-by-step

**Step 1 — Parse and validate manifest**

```kotlin
val manifest = runCatching {
    json.decodeFromString<VoiceManifestV2>(plainBytes.decodeToString())
}.getOrElse { ex ->
    log "MEDIA_RX manifest_parse_fail envelopeId=<8> error=<60>"
    transport.sendDeliveryAck(deliver.messageId)
    return
}
```

Validation:
```
mediaId.isNotBlank()                    || → FAILED, ack, return
Base64.decode(manifest.mediaKey).size == 32 || → FAILED, ack, return
Base64.decode(manifest.nonce).size == 24   || → FAILED, ack, return
Base64.decode(manifest.sha256).size == 32  || → FAILED, ack, return
manifest.chunkCount in 1..256              || → FAILED, ack, return
manifest.encryptedSizeBytes > 0            || → FAILED, ack, return
manifest.alg == "xchacha20poly1305-v1"    || → FAILED (unknown alg), ack, return
```

Validation failure log:
```
MEDIA_RX manifest_invalid envelopeId=<8> reason=<field_name> mediaId=<take8>
```

`mediaKey`, `nonce`, `sha256` values never logged.

**Step 2 — Idempotency check**

```kotlin
val existing = voiceV2DownloadRepository.find(manifest.mediaId)
if (existing != null) {
    log "MEDIA_RX manifest_duplicate mediaId=<take8> status=${existing.status}"
    transport.sendDeliveryAck(deliver.messageId)
    return
}
```

**Step 3 — Insert local message row (DOWNLOADING)**

```kotlin
val localMsgId = manifest.mediaId   // stable primary key — same as assembleAndDispatch1to1Voice uses voiceId
val timerSecs = conversationRepository.getDisappearingTimer(conversationId)
val expiresAtMs = if (timerSecs > 0L) nowMs + timerSecs * 1_000L else null
messageRepository.insertMessage(
    MessageEntity(
        id             = localMsgId,
        conversationId = conversationId,
        ciphertext     = ByteArray(0),
        plaintextCache = "[AUDIO_DOWNLOADING]",  // sentinel; AudioBubble shows spinner
        sent           = false,
        status         = MessageStatus.DOWNLOADING,
        createdAt      = nowMs,
        expiresAtMs    = expiresAtMs,
    )
)
```

**Step 4 — Insert download task (durable, MUST precede ACK)**

```kotlin
voiceV2DownloadRepository.insert(
    VoiceV2DownloadTask(
        mediaId        = manifest.mediaId,
        conversationId = conversationId,
        senderPubKeyHex = senderPubKeyHex,
        manifestJson   = json.encodeToString(manifest),
        status         = VoiceV2DownloadStatus.PENDING,
        chunkCount     = manifest.chunkCount,
        lastAttemptAtMs = 0L,
        failureReason  = null,
        createdAtMs    = nowMs,
    )
)
```

This write MUST commit before `sendDeliveryAck`. If `insert` throws, do NOT ack;
let the relay redeliver. Pattern mirrors D2b.1 `insertChunk → ack` ordering.

**Step 5 — ACK manifest envelope**

```kotlin
transport.sendDeliveryAck(deliver.messageId)
log "MEDIA_RX manifest_acked_and_queued mediaId=<take8> chunks=${manifest.chunkCount}"
```

**Step 6 — Emit placeholder to UI**

```kotlin
_incomingMessages.emit(
    IncomingMessage(
        id                  = localMsgId,
        conversationId      = conversationId,
        senderPublicKeyHex  = senderPubKeyHex,
        text                = "[AUDIO_DOWNLOADING]",
        receivedAt          = nowMs,
    )
)
conversationRepository.upsertConversation(...) // bump unread, lastMessagePreview = "Voice message"
onNewMessageNotification?.invoke(...)
```

**Step 7 — Trigger download worker**

```kotlin
scope.launch { runDownloadTask(manifest.mediaId) }
```

### `runDownloadTask(mediaId)` — download orchestrator

```kotlin
private suspend fun runDownloadTask(mediaId: String) {
    val task = voiceV2DownloadRepository.find(mediaId) ?: return
    val manifest = json.decodeFromString<VoiceManifestV2>(task.manifestJson)
    val chunks = mutableListOf<ByteArray>()

    for (idx in 0 until manifest.chunkCount) {
        val chunkBytes = downloadChunkWithRefresh(manifest.mediaId, idx)
            ?: run {
                // Permanent failure: relay lost the chunks (TTL expiry, restart)
                voiceV2DownloadRepository.update(mediaId, VoiceV2DownloadStatus.FAILED, "media_chunks_gone")
                messageRepository.updateStatus(task.localMsgId, MessageStatus.FAILED)
                messageRepository.updateMessageText(task.localMsgId, "[AUDIO_FAILED:media_chunks_gone]")
                log "MEDIA_RX download_failed mediaId=<take8> reason=media_chunks_gone idx=$idx"
                return
            }
        chunks.add(chunkBytes)
    }

    // Reassemble
    val encryptedBlob = ByteArray(chunks.sumOf { it.size })
    var offset = 0
    for (c in chunks) { c.copyInto(encryptedBlob, offset); offset += c.size }

    // Decrypt + verify
    val plainAudio = runCatching {
        MediaCrypto.decryptVoice(
            mediaId  = manifest.mediaId,
            mediaKey = Base64.decode(manifest.mediaKey),
            nonce    = Base64.decode(manifest.nonce),
            ciphertext = encryptedBlob,
        )
    }.getOrElse { ex ->
        voiceV2DownloadRepository.update(mediaId, VoiceV2DownloadStatus.FAILED, "decrypt_failed")
        messageRepository.updateStatus(task.localMsgId, MessageStatus.FAILED)
        messageRepository.updateMessageText(task.localMsgId, "[AUDIO_FAILED:decrypt_failed]")
        log "MEDIA_RX decrypt_failed mediaId=<take8> error=${ex::class.simpleName}"
        return
    }

    // sha256 verify (plaintext)
    val expectedSha256 = Base64.decode(manifest.sha256)
    val actualSha256   = sha256(plainAudio)   // KMP-safe; use okio or kotlinx-crypto
    if (!actualSha256.contentEquals(expectedSha256)) {
        voiceV2DownloadRepository.update(mediaId, VoiceV2DownloadStatus.FAILED, "sha256_mismatch")
        messageRepository.updateStatus(task.localMsgId, MessageStatus.FAILED)
        messageRepository.updateMessageText(task.localMsgId, "[AUDIO_FAILED:sha256_mismatch]")
        log "MEDIA_RX sha256_mismatch mediaId=<take8>"
        return
    }

    // Persist audio bytes to local file
    val audioPath = saveAudioToLocalFile(plainAudio, manifest.mediaId, manifest.mime)
    messageRepository.updateMessageText(task.localMsgId, "[AUDIO_LOCAL:$audioPath]")
    messageRepository.updateStatus(task.localMsgId, MessageStatus.DELIVERED)
    voiceV2DownloadRepository.update(mediaId, VoiceV2DownloadStatus.COMPLETE, null)
    voiceV2DownloadRepository.delete(mediaId) // task complete, free space

    log "MEDIA_RX download_complete mediaId=<take8> plainBytes=${plainAudio.size}"
    // No second _incomingMessages.emit — UI reacts to MessageStatus transition
}
```

`downloadChunkWithRefresh(mediaId, idx)` — same token-refresh pattern as upload side:
```
maxRefreshCycles = 3
for cycle in 1..3:
    token = tokenProvider()
    result = mediaUploadTransport.downloadChunk(token, mediaId, idx)
    when result:
        ByteArray → return bytes
        MediaAuthException → refresh + retry
        NotFoundException  → return null (chunks gone — permanent)
        MediaTransportException → bounded retry with backoff (5 attempts)
after all retries: return null
```

### Recovery on process death

In `startReceiving`, before the live subscription (mirrors D2b.1 finalizer):

```kotlin
voiceV2DownloadRepository?.let { repo ->
    val pending = repo.findPending()
    if (pending.isNotEmpty()) {
        log "MEDIA_RX finalizer_start taskCount=${pending.size}"
        pending.forEach { task ->
            log "MEDIA_RX finalizer_resume mediaId=${task.mediaId.take(8)}"
            scope.launch { runDownloadTask(task.mediaId) }
        }
    }
}
```

**Full log spec — receive path**

```
MEDIA_RX manifest_parse_fail envelopeId=<8> error=<60>
MEDIA_RX manifest_invalid envelopeId=<8> reason=<field> mediaId=<8>
MEDIA_RX manifest_duplicate mediaId=<8> status=<status>
MEDIA_RX manifest_acked_and_queued mediaId=<8> chunks=N
MEDIA_RX download_failed mediaId=<8> reason=media_chunks_gone|decrypt_failed|sha256_mismatch idx=N
MEDIA_RX decrypt_failed mediaId=<8> error=<class>
MEDIA_RX sha256_mismatch mediaId=<8>
MEDIA_RX download_complete mediaId=<8> plainBytes=N
MEDIA_RX finalizer_start taskCount=N
MEDIA_RX finalizer_resume mediaId=<8>
```

`mediaKey`, `nonce`, `sha256` values never appear in any receive-path log line.

---

## 5. New Types / Migrations / DI

### MessagePayload.TYPE_VOICE_V2

In `MessagePayload.kt` companion:
```kotlin
const val TYPE_VOICE_V2 = "voice_v2"
```

### MessageStatus new variants

Two new variants inserted between `QUEUED` and `SENT` in `MessageRepository.kt`:
```kotlin
enum class MessageStatus {
    QUEUED,
    UPLOADING,       // new — sender: chunks in progress
    DOWNLOADING,     // new — receiver: chunks being fetched
    WAITING_FOR_RECIPIENT_BUNDLE,
    SENT,
    RELAYED,
    DELIVERED,
    READ,
    FAILED,
}
```

The `message` table stores status as TEXT. SQLDelight will resolve the new variants
to the string `"uploading"` / `"downloading"` automatically. No migration needed for
the `message` table itself — new rows use new variants, existing rows are unaffected.

### SQLDelight migration 17.sqm

**File:** `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/17.sqm`

```sql
-- 17.sqm — voice_v2_downloads task table for durable media download state (PR-M1w).
--
-- Tracks the state of each pending voice_v2 manifest download so a process
-- death between manifest receipt and final audio assembly is recoverable on
-- the next startReceiving call. Once COMPLETE, the row is deleted immediately.
-- A FAILED row is retained so the UI can surface the failure reason.

CREATE TABLE voice_v2_downloads (
    media_id          TEXT    NOT NULL PRIMARY KEY,
    conversation_id   TEXT    NOT NULL,
    sender_pubkey_hex TEXT    NOT NULL,
    manifest_json     TEXT    NOT NULL,
    status            TEXT    NOT NULL DEFAULT 'pending',   -- 'pending' | 'complete' | 'failed'
    chunk_count       INTEGER NOT NULL,
    last_attempt_at_ms INTEGER NOT NULL DEFAULT 0,
    failure_reason    TEXT,
    created_at_ms     INTEGER NOT NULL
);
```

### VoiceV2DownloadRepository interface

**File:** `shared/core/storage/src/commonMain/kotlin/phantom/core/storage/VoiceV2DownloadRepository.kt`

```kotlin
interface VoiceV2DownloadRepository {
    data class Task(
        val mediaId: String,
        val conversationId: String,
        val senderPubKeyHex: String,
        val manifestJson: String,
        val status: String,
        val chunkCount: Int,
        val lastAttemptAtMs: Long,
        val failureReason: String?,
        val createdAtMs: Long,
    )

    suspend fun insert(task: Task)
    suspend fun find(mediaId: String): Task?
    suspend fun findPending(): List<Task>
    suspend fun update(mediaId: String, status: String, failureReason: String?)
    suspend fun delete(mediaId: String)
    suspend fun deleteAll()
}
```

Expose status as `String` rather than a typed enum to avoid a shared-module dependency
on the new `VoiceV2DownloadStatus` enum. Callers use the companion constants
`VoiceV2DownloadRepository.STATUS_PENDING`, `STATUS_COMPLETE`, `STATUS_FAILED`.

### VoiceV2Download.sq

```sql
insert:
INSERT OR IGNORE INTO voice_v2_downloads(media_id, conversation_id, sender_pubkey_hex, manifest_json, status, chunk_count, last_attempt_at_ms, failure_reason, created_at_ms)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);

find:
SELECT * FROM voice_v2_downloads WHERE media_id = ?;

findPending:
SELECT * FROM voice_v2_downloads WHERE status = 'pending' ORDER BY created_at_ms ASC;

update:
UPDATE voice_v2_downloads SET status = ?, failure_reason = ?, last_attempt_at_ms = ? WHERE media_id = ?;

delete:
DELETE FROM voice_v2_downloads WHERE media_id = ?;

deleteAll:
DELETE FROM voice_v2_downloads;
```

### AppContainer.kt DI changes

```kotlin
// After voiceChunkRepo declaration (~line 127):
val voiceV2DownloadRepo = SqlDelightVoiceV2DownloadRepository(dbHolder.database)
```

Inside `initMessaging`, after `restOrchestrator` construction:

```kotlin
val mediaHttpClient = createRestHttpClient()  // reuse or share with restHttpClient
val mediaUploadTransport = KtorMediaUploadTransport(
    baseUrl    = relayHttpBase,
    httpClient = mediaHttpClient,
)
```

`DefaultMessagingService` constructor call gains two new parameters:

```kotlin
val service = DefaultMessagingService(
    // existing params...
    voiceChunkRepository    = voiceChunkRepo,
    voiceV2DownloadRepository = voiceV2DownloadRepo,   // NEW
    mediaUploadTransport    = mediaUploadTransport,     // NEW
    // Token provider + refresher — lambda closures over restOrchestrator:
    mediaTokenProvider      = { restOrchestrator.acquireOrRefreshToken(reason = "media") },    // NEW
    mediaTokenRefresher     = { restOrchestrator.acquireOrRefreshToken(reason = "media_401", forceRefresh = true) }, // NEW
)
```

Note: `acquireOrRefreshToken` is `internal` in `RestFallbackOrchestrator`. For
the M1w injection to compile, either:
1. Move to `shared/core/transport` module's public surface (rename to `internal` within the module, `public` cross-module), OR
2. Add a `getToken(): String?` / `refreshToken(): String?` thin facade on the orchestrator.

**Recommendation:** add `suspend fun currentToken(): String?` and
`suspend fun refreshToken(): String?` as public methods on `RestFallbackOrchestrator`
that delegate to `acquireOrRefreshToken`. This avoids leaking the internal CAS logic.

---

## 6. UI Surface

### ChatScreen — AudioBubble

`AudioBubble` currently accepts `base64Data: String` and `status: MessageStatus`.
The new paths write `[AUDIO_LOCAL:<path>]` or `[AUDIO_DOWNLOADING]` or
`[AUDIO_FAILED:<reason>]` into `plaintextCache`.

The bubble dispatch in `ChatScreen.kt` (around line 1486) must handle:

| `plaintextCache` prefix | Render |
|---|---|
| `[AUDIO:]` | existing Base64 path — unchanged (legacy `audio_chunk` path) |
| `[AUDIO_LOCAL:<path>]` | read file from disk, render waveform as today |
| `[AUDIO_DOWNLOADING]` | spinner + "Downloading…" label, no play button |
| `[AUDIO_FAILED:<reason>]` | error icon + "Failed" label |

Status icons in `StatusIcon` composable:
- `UPLOADING` → circular spinner (reuse existing `CircularProgressIndicator` or a simple animated icon)
- `DOWNLOADING` → downward-arrow spinner
- `FAILED` → red exclamation mark (existing path)

### In-progress Snackbar

On second voice tap while upload is in progress (caught by in-progress guard in DMS),
`sendAudio` returns `Result.failure(IllegalStateException("A voice message is still uploading. Please wait."))`.
`ChatScreen` already checks `result.isFailure` (line 703) and shows a Toast. Change
the Toast to a Snackbar matching the D2a pattern, with locked copy:

```
"A voice message is still uploading. Please wait."
```

Add to `strings.xml`:
```xml
<string name="m1w_voice_still_uploading">A voice message is still uploading. Please wait.</string>
```

### Retry UI

Round 1: no tap-to-retry. A `FAILED` voice row shows the error state. User must
delete the row manually or wait for the relay TTL. Round 2 (not in M1w scope) can
add retry via long-press.

---

## 7. Token-Refresh Integration

### Token source

`KtorMediaUploadTransport` receives two lambdas at construction:
- `tokenProvider: suspend () -> String?` — returns the current bearer token (no
  network call if cache is valid).
- `tokenRefresher: suspend () -> String?` — forces a token refresh (calls
  `POST /auth/session`).

Both lambdas delegate to `RestFallbackOrchestrator` via the thin facade methods
described in Section 5. The orchestrator's `tokenMutex` serialises concurrent
callers from the upload loop and the existing poll loop — no additional locking
needed in `KtorMediaUploadTransport` itself.

### Refresh trigger

On `MediaAuthException` (relay returned 401 on `uploadChunk` or `downloadChunk`):
1. Call `tokenRefresher()`.
2. Retry the same chunk call with the new token.
3. Max 3 refresh-retry cycles per chunk operation (independent of
   `SEND_MAX_ATTEMPTS` in `RestFallbackOrchestrator` — those govern envelope send,
   these govern chunk upload/download).
4. If after 3 cycles still 401: surface as `FAILED` with reason `auth_refresh_exhausted`.

### Network-error retries

`MediaTransportException` (timeout, socket reset, DNS failure) uses its own bounded
retry — 5 attempts, backoff `[1s, 3s, 8s, 20s, 60s]` (mirrors `SEND_RETRY_DELAYS_MS`).
This is separate from the 401 refresh cycle.

---

## 8. Capability Flip (Last Step)

After Test #56 passes (Tecno + Tele2 LTE + emulator receiver, end-to-end voice_v2):

In `AppContainer.kt` `canSendVoice` lambda:
```kotlin
canSendVoice = {
    // M1w: voice_v2 is now safe on Limited realtime (REST/WsCandidate)
    // because media chunks go directly to /media/upload-chunk (not through
    // the ratchet envelope pipeline) and the manifest is a single small
    // envelope well within the REST body cap.
    true   // ← flip: was "mode == WsActive || mode == null"
},
```

In `ChatScreen.kt` `onMicClick` guard:
```kotlin
// Remove the mode check entirely; canSendVoice is now always true.
// The DMS send-layer guard is sufficient.
```

In `TransportCapabilitiesResolver` (PR #170 C1 source file, wherever it lives):
- Set `canSendVoice = true` for `RestActive` and `WsCandidate` states.

Update `TransportCapabilitiesResolverTest`:
- Test names and assertions for `RestActive_canSendVoice` and `WsCandidate_canSendVoice`
  change from `assertFalse` to `assertTrue`.

Commit message: `feat(media): M1w — flip canSendVoice=true for Limited realtime after Test #56 pass`

---

## 9. Test Plan

### commonTest

**`VoiceV2SendReceiveTest`**
- Manifest round-trip: encode `VoiceManifestV2` → JSON → decode, assert all fields preserved.
- Manifest parse failure: malformed JSON → ack-and-skip (no crash, no row insert).
- Manifest validation: mediaKey 31 bytes (wrong length) → `manifest_invalid` log, ack, return.
- Upload happy path: 3 chunks, all 201 → manifest sent, row `SENT`.
- Upload 409 ciphertext_mismatch on chunk 2 → row `FAILED`, manifest not sent, chunk 0/1 wasted (no rollback on relay side — accepted).
- Upload 401 then 201 (1 refresh cycle) → succeeds.
- Upload 401 × 3 → `auth_refresh_exhausted`, row `FAILED`.
- In-progress guard: second `sendAudio` while first is in progress → immediate `Result.failure`, first continues.

**`VoiceV2DownloadOrchestratorTest`**
- Happy path: 3 chunks, decrypt + sha256 verify → row `DELIVERED`, task deleted.
- Chunk 2 returns 404 → row `FAILED`, reason `media_chunks_gone`.
- Decrypt throws → row `FAILED`, reason `decrypt_failed`.
- sha256 mismatch → row `FAILED`, reason `sha256_mismatch`.
- Process death recovery: task in `PENDING` state on fresh `startReceiving` → download resumes.
- Duplicate manifest (already in `voice_v2_downloads`) → ack immediately, no second row.

**`VoiceV2DownloadRepositoryContractTest`**
- `insert` + `find` round-trip.
- `findPending` returns only `status='pending'` rows.
- `update` to `failed` + reason.
- `delete` removes row.
- `insert` idempotency (second insert of same `media_id` is silent no-op per `INSERT OR IGNORE`).

**`KtorMediaUploadTransportTest`**
- `uploadChunk` 201 → `MediaUploadResult.Stored`.
- `uploadChunk` 200 → `MediaUploadResult.Duplicate`.
- `uploadChunk` 401 → throws `MediaAuthException`.
- `uploadChunk` 409 body `ciphertext_mismatch` → throws `MediaConflictException(reason="ciphertext_mismatch")`.
- `uploadChunk` 413 → throws `MediaQuotaException`.
- `downloadChunk` 200 → returns `ByteArray`.
- `downloadChunk` 404 → throws `NotFoundException`.
- `downloadChunk` 401 → throws `MediaAuthException`.
- Client precheck: `ciphertext.size > CLIENT_MAX_CHUNK_BODY_BYTES (2600)` → throws `MediaQuotaException` before HTTP call.

### androidUnitTest

**`VoiceV2SenderRetryTest`**
- 401 on chunk 1, refresh, 201 on retry → upload continues.
- 401 × 3 on chunk 1 → `FAILED`.
- Network exception × 5 on chunk 2 → `FAILED`, chunk 0/1 wasted.

**`VoiceV2ReceiverHandlerTest`**
- `voice_v2` payload dispatched to `handleVoiceV2Manifest` (not to legacy `audio_chunk` path).
- Verify ACK is sent only after `voiceV2DownloadRepository.insert` returns.

### androidInstrumentedTest

- End-to-end on emulator pair (both processes, mock relay or staging relay at
  `relay.phntm.pro`): record 2 s voice → send → verify receiver gets `DELIVERED`
  status and audio is playable within 20 s.

### Real-device test (Test #56)

- Device: Tecno Spark (Wi-Fi or Tele2 LTE once second phone is available).
- Emulator as the other peer.
- Record 3 s voice in Tele2 LTE `RestActive` mode.
- Verify: sender row transitions `QUEUED → UPLOADING → SENT/DELIVERED`.
- Verify: receiver row transitions `DOWNLOADING → DELIVERED`.
- Check logcat for `MEDIA_TX upload_complete` + `MEDIA_RX download_complete`.
- Verify no `mediaKey` / `nonce` / `sha256` values appear in logcat output.
- Pass threshold: end-to-end completion within 20 s on Tele2 LTE.

---

## 10. Out of Scope

- Group voice. Group path continues using `audio_chunk` envelopes via `DefaultGroupMessagingService`.
- Photo / file upload.
- Parallel chunk upload (round 2 optimization).
- Progress bar percentage in UI (spinner only in round 1).
- `DELETE /media/chunk` endpoint (relay TTL handles cleanup).
- Persistent relay storage (relay is in-memory with TTL; M1r does not change this).
- Calls / realtime transport.
- iOS UI changes.
- Tap-to-retry on failed voice row.
- Media quota usage display.

---

## 11. Decisions Locked by Vladislav (2026-05-18)

The original Q1–Q7 below are resolved per Vladislav's apply. **Builder MUST follow these decisions, NOT the architect's original options.** Refinements in bold are non-negotiable contracts.

### Q1 — `plaintextCache` strategy → **split: sender Base64, receiver disk file**

- **Sender own row:** `plaintextCache = "[AUDIO:<base64>]"`. Self-contained — survives reboot/cache clear. SQLite bloat acceptable for round 1. Mirrors current D2b path for sender side.
- **Receiver row:** `plaintextCache = "[AUDIO_LOCAL:<path>]"` where path = `context.filesDir/voice/<messageId-or-mediaId>.ogg`. Persistent (not `cacheDir`). M1 is the media layer — receiver SHOULD use disk files, not blobs in SQLite.
- AudioBubble already detects `[AUDIO:` prefix; add `[AUDIO_LOCAL:` branch reading file from disk. If file missing → show "Голосовое сообщение" text fallback.

### Q2 — SHA-256 → **`ionspin.kotlin.crypto.hash.Hash.sha256`**

Already used by `MediaCryptoTest`. No new dependencies. Receiver verify:
```kotlin
import com.ionspin.kotlin.crypto.hash.Hash
val actualSha = Hash.sha256(decryptedAudio.toUByteArray()).toByteArray()
if (!actualSha.contentEquals(expectedShaFromManifest)) → MEDIA_RX sha256_mismatch + FAILED
```

### Q3 — Token refresh → **`MediaAuthTokenProvider` facade with CAS semantics, NOT naive current/refresh split**

This is the most risk-sensitive locked contract. Naive `currentToken()` + `refreshToken()` causes pinball/race conditions (D1c.1 lesson). The locked interface:

```kotlin
// shared/core/transport/src/commonMain/.../MediaAuthTokenProvider.kt (new file)
interface MediaAuthTokenProvider {
    /**
     * Get a valid bearer session token.
     *
     * @param reason debug string ("media_upload" / "media_download") for logging.
     * @param staleToken if non-null, caller is reporting that THIS token was just
     *   rejected with 401. Provider must return a strictly different token
     *   (refresh if needed). If null, caller wants whatever cached token is fresh
     *   (cheap path — no refresh unless cache is empty/expired).
     * @return new token, or null on terminal auth failure.
     */
    suspend fun acquireToken(reason: String, staleToken: String? = null): String?
}
```

**Provider semantics (CAS):**
- If `staleToken == null` and cached token is fresh → return cached.
- If `staleToken != null` and cached token already differs from `staleToken` → another concurrent caller already refreshed; return cached (CAS-path).
- If `staleToken != null` and cached token equals `staleToken` → call `/auth/session` to refresh; store; return new.
- If `staleToken == null` and cache empty/expired → refresh.
- Bounded refresh attempts per logical call (≤3); on exhaustion return `null`.

**Caller loop (in `VoiceV2Sender`):**
```kotlin
var staleToken: String? = null
repeat(MAX_TOKEN_ATTEMPTS = 3) { attempt ->
    val token = tokenProvider.acquireToken(reason = "media_upload", staleToken = staleToken)
        ?: return Result.failure(IllegalStateException("auth_refresh_exhausted"))

    val result = mediaTransport.uploadChunk(token, mediaId, idx, total, chunk)
    when {
        result.isSuccess -> return result
        result.exceptionOrNull() is MediaAuthException -> {
            staleToken = token  // tells provider next call: that token is dead
            return@repeat       // retry
        }
        else -> return result   // other error — propagate
    }
}
return Result.failure(MediaAuthException)
```

**Implementation adapter (kept thin):**
```kotlin
// in shared/core/transport/src/commonMain/.../RestMediaAuthTokenProvider.kt (new)
class RestMediaAuthTokenProvider(
    private val orchestrator: RestFallbackOrchestrator,
) : MediaAuthTokenProvider {
    override suspend fun acquireToken(reason: String, staleToken: String?): String? =
        orchestrator.acquireOrRefreshMediaToken(reason, staleToken)
}
```

**`RestFallbackOrchestrator` change:** add ONE public method `suspend fun acquireOrRefreshMediaToken(reason: String, staleToken: String?): String?` that implements the CAS semantics above. The internal `acquireOrRefreshToken` stays `internal` — the new method is the public facade and delegates to it with the CAS check around the cached token.

**Critical: auth-refresh logic stays OUT of `KtorMediaUploadTransport`.** The transport accepts a token verbatim and never calls back into a provider. Loop logic lives in `VoiceV2Sender`. Same applies to download path (`VoiceV2DownloadOrchestrator`).

### Q4 — `VoiceV2DownloadRepository` → **nullable for tests, production MUST wire it; ACK only after durable save**

- DMS constructor: `voiceV2DownloadRepository: VoiceV2DownloadRepository? = null` (mirrors `voiceChunkRepository`).
- Production `AppContainer` MUST wire a non-null instance — this is a hard requirement, not optional. Add an `init { }` assertion in DMS that logs `WARN MEDIA_RX no_download_repo running_in_degraded_mode` if null at construction, so a misconfigured production build is loud in logs.
- **ACK discipline (NON-NEGOTIABLE):**
  - If `voiceV2DownloadRepository != null` → durable `INSERT OR IGNORE` into `voice_v2_downloads` → SQL commit → THEN ACK manifest envelope. Mirror D2b.1 `chunk_saved → ack_deliver` pattern.
  - If `voiceV2DownloadRepository == null` (test/degraded) → **DO NOT ACK** the manifest. Return handler failure (`result=waiting` or similar). The relay will retry redeliver next poll. **Better to retry forever than silently drop a voice.** A real production deployment will never hit this path; in tests, the test fixture explicitly wires a repo or asserts the no-ACK path.

### Q5 — DMS surface → **`VoiceV2Sender` composite**

Locked interface:

```kotlin
// shared/core/messaging/src/commonMain/.../VoiceV2Sender.kt (new)
class VoiceV2Sender(
    private val mediaCrypto: MediaCrypto,
    private val mediaTransport: MediaUploadTransport,
    private val tokenProvider: MediaAuthTokenProvider,
    private val log: (String) -> Unit,
) {
    /**
     * Encrypt audio + upload all chunks. Caller (DMS) is responsible for
     * sending the returned VoiceManifestV2 through the existing ratchet
     * envelope pipeline. This class never touches `MessageRepository` or
     * the ratchet — it's a pure upload helper.
     *
     * @return Result.success(manifest) once all chunks are 201/200.
     *         Result.failure on terminal upload error (auth exhausted,
     *         413 quota, 409 conflict, repeated network failure).
     */
    suspend fun uploadVoice(
        audioBytes: ByteArray,
        durationMs: Long,
        mime: String,
    ): Result<VoiceManifestV2>
}
```

DMS calls `voiceV2Sender.uploadVoice(...)`. On success, DMS wraps the manifest in `MessagePayload(type=TYPE_VOICE_V2, ...)`, encrypts via Double Ratchet, and sends via existing `transport.send()`. On failure DMS marks local row `FAILED` with reason from the thrown exception.

**`VoiceV2Sender` does NOT:** touch SQL, send manifest envelope, manage in-progress guard, or update message status. Those stay in DMS where they already are.

### Q6 — Receiver audio storage → **`context.filesDir/voice/<mediaId>.ogg` (persistent)**

NOT `cacheDir` (Android may evict). Filename uses `mediaId` (capability token already random + unique). Decrypted audio bytes written via Okio sink. `plaintextCache = "[AUDIO_LOCAL:<filesDir>/voice/<mediaId>.ogg]"` so `AudioBubble` can read directly. Cleanup (delete on message delete / TTL) is a separate follow-up — out of scope for M1w round 1.

### Q7 — `TransportCapabilitiesResolver` location → ✅ RESOLVED

The resolver is at `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/TransportCapabilities.kt` lines 110–159. Section 8 flip targets this file directly.

---

## 11-OBSOLETE. Original Open Questions (kept for review trail)

**Q1 — `plaintextCache` strategy for the sender's own voice row.**  
Design uses `[AUDIO_LOCAL:<filepath>]` so the sender's bubble plays from disk without
allocating a Base64 string for potentially large audio. Trade-off: if the temp file is
deleted (device reboot, cache clear), the sender sees a "failed" bubble for their own
sent voice. Alternative: keep `[AUDIO:<base64>]` as today — wastes memory but is
fully self-contained. Decision needed before implementation.

**Q2 — SHA-256 implementation on commonMain.**  
`MediaCrypto.encryptVoice` promises to return `plaintextSha256: ByteArray` (per the
M1k brief). The receive path needs to independently compute SHA-256 on the decrypted
plaintext to verify integrity. The current codebase has `ionspin`-based AEAD but no
visible SHA-256 utility in commonMain. Confirm whether `ionspin.kotlin.crypto.hash`
is already a dependency, or if `okio`'s `HashingSink` (Android + JVM only) or a
KMP-compatible digest must be added.

**Q3 — `acquireOrRefreshToken` visibility.**  
`RestFallbackOrchestrator.acquireOrRefreshToken` is `internal`. M1w needs to call
token acquire/refresh from `KtorMediaUploadTransport`. Adding two public thin-facade
methods (`currentToken()`, `refreshToken()`) to the orchestrator is the cleanest path.
Confirm this is acceptable, or if the lambdas should be constructed inline in
`AppContainer.kt` using the orchestrator's existing `bootstrap()` + `sendEnvelope()`
hooks.

**Q4 — `voiceV2DownloadRepository` nullable default.**  
Following the pattern of `voiceChunkRepository: VoiceChunkRepository? = null` in DMS
constructor, should `voiceV2DownloadRepository` also default to null for test
call-site compatibility? If null, the `voice_v2` handler falls back to acking the
manifest and emitting an empty row with `FAILED` status (or silently drops). Confirm
the fallback behaviour, or require non-null.

**Q5 — `mediaUploadTransport` on DMS constructor.**  
Adding `MediaUploadTransport` directly to `DefaultMessagingService` increases the
constructor's parameter count to ~14. Alternative: create a `VoiceV2SendHelper`
object that holds `mediaUploadTransport` + `voiceV2DownloadRepository` and is passed
as one parameter. Architectural preference?

**Q6 — Local audio file storage location.**  
For `[AUDIO_LOCAL:<path>]`, the sender writes audio to... where? Options:
`context.cacheDir/voice/<uuid>.ogg` (auto-cleared by Android on low storage) or
`context.filesDir/voice/<uuid>.ogg` (persistent, requires explicit deletion). The
receiver writes decrypted audio to the same location. Decision needed.

**Q7 — `TransportCapabilitiesResolver` source location.** ✅ RESOLVED.
The resolver IS a source file at `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/TransportCapabilities.kt` lines 110–159 (verified at `origin/master` HEAD `9c502b42`). The Section 8 capability flip targets this file directly. The architect's note was based on a stale local working tree.

---

## Contradictions Section — REMOVED

The original architect pass emitted 4 contradiction claims based on a stale local
master view at `33974355`. Subsequent `git fetch + reset --hard origin/master`
confirmed that all prerequisite files (`media.rs`, `MediaUploadTransport.kt`,
`MediaCrypto.kt`, `VoiceManifestV2.kt`, `MediaChunker.kt`, `TransportCapabilities.kt`
including `TransportCapabilitiesResolver`) are present at `origin/master` HEAD
`9c502b42`. The kmp-builder agent must work from `origin/master` tip after `git fetch`
— no contradictions remain. See note at the top of this document.
