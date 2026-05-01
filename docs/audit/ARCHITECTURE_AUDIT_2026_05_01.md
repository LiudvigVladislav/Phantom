# PHANTOM Android — Architecture Audit 2026-05-01

Auditor: PHANTOM Project Architect (Claude Sonnet 4.6)
Scope: reliability, lifecycle, delivery correctness
Status: pre-Kickstarter Alpha-1 build; ADR-010 (evictAll fix) already merged

---

## Plain-Language Summary (for the founder)

Five things most likely to break next, ordered by how often a user will notice:

1. **Voice messages never arrive on the other device.** A voice note is one giant base64
   blob (~300 KB after encoding). The relay enforces `max_payload_bytes` and the relay
   store also has a per-recipient cap of `max_envelopes_per_recipient`. If the voice note
   exceeds either limit it is silently dropped with no error shown to the sender.
   There is no chunking anywhere in the stack.

2. **"Calling…" stays forever on the caller's screen.** When the callee picks up, the
   `call_answer` signal goes through the Double Ratchet E2EE pipeline (because
   `sealedSender` is empty but it IS a real envelope). The relay stores it and live-
   delivers it — but back on the caller side, `handleAnswer` calls
   `peerConnection.setRemoteDescription()` whose success callback runs on a WebRTC
   thread and updates `_activeCall.value`. That update is correct, BUT: if the answer
   arrives while the caller's `peerConnection` is null (race between WebRTC offer
   creation and the answer arriving quickly), the state machine stalls in `CALLING`
   with no recovery path. The callee also has no timeout to un-ring.

3. **First message to a new contact disappears silently.** When the send path calls
   `encryptUnderLock`, it fetches the peer's bundle and calls
   `sessionManager.initiatorBootstrap()` which calls `saveSession()` — persisting ratchet
   state — and THEN the outer function encrypts and inserts the message row. If the
   device loses power between `saveSession` and `insertMessage`, the ratchet state on
   disk is one step ahead of any stored message. On restart the next message will decrypt
   fine, but the original message is gone from local storage and the peer receives it
   without a corresponding row on the sender's side.

4. **Messages stuck in QUEUED after a reconnect.** The `flushPendingOutbox` loop in
   KtorRelayTransport reads the in-memory `pendingOutbox`. If `sendRaw` fails for any
   entry, that entry is logged and discarded — the loop does not re-enqueue it. Messages
   whose DB row is already `QUEUED` (from a previous run or a transport failure) are
   never re-submitted by any background job; only messages in
   `WAITING_FOR_RECIPIENT_BUNDLE` get retried. A message stuck in `QUEUED` across a
   process restart is stranded forever.

5. **SQLCipher passphrase breaks permanently after a biometric-change or factory reset
   of the secure element.** The passphrase is encrypted by a Keystore key created with
   `setUnlockedDeviceRequired(true)`. Enrolling a new fingerprint, removing the lock
   screen, or performing a partial factory reset can invalidate the key without deleting
   the SharedPreferences entry. The next app launch calls `getOrCreatePassphrase`, which
   tries to decrypt the stored bytes, gets a `KeyPermanentlyInvalidatedException`, and
   crashes the database open. There is no recovery path — the user loses all message
   history silently with no error shown.

---

## Findings

---

### F-01 — CRITICAL: flushPendingOutbox silently drops items on sendRaw failure; QUEUED messages are never retried after process restart

**File:** `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt`
Lines 487–522 (`flushPendingOutbox`), lines 390–425 (`send`)

**Failure scenario:** User sends a message while the socket is briefly transitioning.
The envelope enters `pendingOutbox`. On reconnect, `flushPendingOutbox` drains the list
but `sendRaw` returns false for one item (session just died again mid-flush). The item
is logged and the loop continues with the next item — the failed item is gone.
Separately, if the process is killed while an envelope is in the in-memory `pendingOutbox`
(common: Android kills background service), the envelope disappears entirely.
The DB row stays `QUEUED` but nothing in the codebase ever re-submits `QUEUED` rows.
`retryWaitingMessages` only touches `WAITING_FOR_RECIPIENT_BUNDLE`.

**Root cause:** `flushPendingOutbox` does not re-enqueue failed items (line 514–520).
`pendingOutbox` is not persisted. There is no background job that reads `QUEUED` rows
from the DB and calls `transport.send()` again.

**Recommended fix:** On `sendRaw` failure inside `flushPendingOutbox`, push the item
back to the front of `pendingOutbox` and break out of the flush (the session is dead;
next reconnect will retry). Separately, add a startup sweep in `initMessagingFromStorage`
that reads all `QUEUED` messages from the DB and re-submits them via `transport.send()`.

---

### F-02 — CRITICAL: SQLCipher passphrase permanently lost on Keystore key invalidation; no recovery path

**File:** `shared/core/storage/src/androidMain/kotlin/phantom/core/storage/DatabasePassphraseManager.kt`
Lines 33–48 (`getOrCreatePassphrase`), line 65 (`setUnlockedDeviceRequired(true)`)

**Failure scenario:** User enrols a new fingerprint, removes and re-adds a PIN, or
triggers a partial factory reset that wipes the secure element. Android invalidates the
`phantom_db_passphrase_key` Keystore key. On next launch, `getOrCreatePassphrase` calls
`decrypt(storedBytes)`, which throws `KeyPermanentlyInvalidatedException` (or on older
APIs, `javax.crypto.BadPaddingException`). The exception propagates into
`DatabaseDriverFactory.createDriver()`, crashing the database open. All message history
and session state become inaccessible. There is no migration, wipe-and-restart, or
user-visible error message.

**Root cause:** The Keystore key carries `setUnlockedDeviceRequired(true)`, which ties
it to the current biometric enrollment. No error handling exists around `decrypt()` in
`getOrCreatePassphrase` for the case where decryption fails due to key invalidation.

**Recommended fix:** Wrap `decrypt()` in a try/catch that catches
`KeyPermanentlyInvalidatedException` and `BadPaddingException`. On invalidation, generate
a new passphrase and a new Keystore key, delete the old encrypted entry, and show the
user a one-time warning that history was lost due to security key change. This is
unavoidable — the old ciphertext is unrecoverable — but the app must not crash silently.

---

### F-03 — CRITICAL: Caller stuck in CALLING forever — call_answer race when peerConnection is null

**File:** `apps/android/src/androidMain/kotlin/phantom/android/calls/CallManager.kt`
Lines 213–229 (`handleAnswer`)

**Failure scenario:** User reports "caller still shows Calling after callee picks up."
The `call_answer` signal arrives on the caller's side via `handleDeliver` fast-path
(plaintext, `sealedSender.isEmpty()`). `handleAnswer` is called. It reads
`_activeCall.value` (non-null, state = CALLING) and then calls
`peerConnection?.setRemoteDescription(...)`. `peerConnection` can be null if `startCall`
was interrupted between creating the `ActiveCall` state (line 97) and calling
`createPeerConnection` (line 99) — for example, if `createPeerConnection` threw because
`peerConnectionFactory` was null (race with `initialize()` not yet complete). In that
case `setRemoteDescription` is a no-op, the success callback never fires, and
`_activeCall` stays in `CALLING` until the caller manually hangs up.

There is also no ring timeout on the caller side — if the callee never answers (device
offline, dismissed notification), the caller's UI stays in `CALLING` indefinitely.

**Root cause:** No null-guard before `peerConnection?.setRemoteDescription`; no
call-ring timeout coroutine.

**Recommended fix:** In `handleAnswer`, if `peerConnection == null`, log and transition
to `ENDED`. Add a ring-timeout coroutine in `startCall` (e.g. 60 seconds) that calls
`cleanupCall(CallState.ENDED)` if still in `CALLING` state.

---

### F-04 — HIGH: Ratchet state persisted before message row — crash window causes ghost session advance

**File:** `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt`
Lines 143–149, then lines 258–269 (`sendMessage`)
`shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/SessionManager.kt`
Line 171 (`saveSession` inside `initiatorBootstrap`)

**Failure scenario:** Alice sends the first message to Bob. Inside `encryptUnderLock`,
`initiatorBootstrap` is called, which calls `saveSession()` (line 171 of SessionManager),
persisting the ratchet state to the DB. Control returns to `encryptUnderLock`. Then
`sendMessage` calls `messageRepository.insertMessage()`. If the process dies in the
window between these two DB writes, the ratchet state on disk shows "first message sent"
but there is no message row. On restart the next send uses the already-advanced chain
key, producing correct ciphertext from the ratchet's perspective, but Alice's local
history shows no record of the first message ever being attempted.
Separately on Bob's side: he receives the first message and bootstraps his session
successfully. But if the relay redelivers the same envelope (no ack was sent because
Alice's app died), Bob's duplicate guard (`getMessageById`) catches it and skips it —
correct. However, Alice's ratchet is now ahead of the relay's envelope store by one
step. The second message Alice sends decrypts fine on Bob's side.
This is not a decryption failure, but it is a silent message loss from the sender's
conversation history.

**Root cause:** `saveSession` is called inside `initiatorBootstrap` (before the envelope
is inserted). There is no transactional guarantee linking the ratchet state write and the
message row write.

**Recommended fix:** In `encryptUnderLock`, do not persist the bootstrapped state
immediately inside `initiatorBootstrap`. Instead, return the `RatchetState` without
writing it, then write both the message row and the ratchet state in a single SQLDelight
transaction. This requires exposing a transactional path in `SessionManager` and
`MessageRepository`. Medium refactor but eliminates the crash window.

---

### F-05 — HIGH: Voice messages silently dropped at relay payload or store cap

**File:** `services/relay/src/routes.rs` lines 355–364 (store capacity check)
`apps/android/src/androidMain/kotlin/phantom/android/calls/CallManager.kt` — no
audio-message handling; voice goes through DefaultMessagingService as inline text.
`shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt`
lines 198–204 (`sendMessage` — no chunking)

**Failure scenario:** A voice note is base64-inlined into the `MessagePayload.text`
field and sent as a single WireFrame envelope. A 10-second voice note at 16 kHz / 16-bit
mono / Opus (typical mobile codec) is ~20–40 KB compressed, but the codebase stores raw
PCM or AAC and base64-encodes it, making the final payload 3–4x larger.
The relay's `max_payload_bytes` is a configurable value. If the encoded envelope exceeds
it, `RequestBodyLimitLayer` (line 77 of routes.rs) returns 413 BEFORE the send handler
runs. The client gets an HTTP-level error in the WS frame write path, `sendRaw` returns
false (exception caught silently), the item is re-enqueued, and retries indefinitely
without ever succeeding. The user sees the message stuck in a sending state forever.

Even if the payload fits within `max_payload_bytes`, if the recipient's store is at
`max_envelopes_per_recipient`, the envelope is dropped at line 364 with a server-side
warning log and no client-side notification.

**Root cause:** No chunking. No size validation before send. No client-side cap check.
No user-visible error when the relay rejects with 413.

**Recommended fix (structural):** Voice messages must not be inlined in the text field.
Define a maximum inline attachment size (suggested: 64 KB post-padding). Anything larger
must either be chunked across multiple envelopes or moved to an out-of-band upload flow
(Phase 2 scope). For Alpha-1, add a pre-send size check in `sendMessage` that fails fast
with a user-visible error rather than queuing an unsendable envelope.

---

### F-06 — HIGH: sessionMutexes map leaks one Mutex per peer forever

**File:** `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt`
Lines 95–99 (`mutexFor`)

**Failure scenario:** Every conversation the user has ever opened adds one `Mutex` to
`sessionMutexes`. The map is never cleaned. On a device used for months with hundreds of
conversations, this is a trivial memory leak in absolute terms (~40 bytes per entry).
However, the structural concern is that `sessionMutexes` is keyed by `conversationId`
(a deterministic string derived from two public keys), and the map is a
`mutableMapOf<String, Mutex>` held in process memory. If a contact is deleted and their
conversation is wiped from the DB, the orphan mutex remains in the map. This is currently
harmless but will cause confusion if a future path tries to infer "is this conversation
active" from the mutex map.

**Root cause:** `getOrPut` pattern with no eviction. No lifecycle hook removes entries
when a conversation is deleted.

**Recommended fix:** Add a `removeConversationMutex(conversationId: String)` method
called from the conversation-delete path. Since this is a non-urgent memory issue, a
weekly sweep that removes keys absent from the DB is also acceptable.

---

### F-07 — HIGH: Incoming call screen shows pubkey prefix instead of username (confirmed)

**File:** `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt`
Lines 378–379 (`fromUsername = conversationRepo.getConversation(fromPubKeyHex)?.theirUsername ?: fromPubKeyHex.take(8)`)

**Failure scenario:** User reported: incoming call screen shows first 8 hex characters
of the caller's pubkey instead of their username. This is confirmed by the code.
`getConversation(fromPubKeyHex)` uses `fromPubKeyHex` as the conversation `id` lookup,
but the conversation `id` is the deterministic sorted-concat of BOTH keys
(`deriveConversationId` in DMS), not the peer's pubkey alone. The lookup therefore always
returns null for callers, falling back to `fromPubKeyHex.take(8)`.

**Root cause:** AppContainer uses the peer pubkey as the lookup key, but
`SqlDelightConversationRepository.getConversation` queries `WHERE id = ?` and `id` is
the two-pubkey derivation, not the peer pubkey alone.

**Recommended fix:** Change the lookup in `onCallMessage` wiring to
`conversationRepo.getConversation(deriveConversationId(myPubKeyHex, fromPubKeyHex))`.
Since `deriveConversationId` is private to `DefaultMessagingService`, either expose a
utility function in a shared module or add a `getConversationByPeerKey(peerPubHex)` query
to `SqlDelightConversationRepository` that queries `WHERE their_public_key_hex = ?`.

---

### F-08 — HIGH: PreKeyLifecycleService bootstrap window — user can send/receive before prekeys are published

**File:** `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt`
Lines 255–263 (`bootstrapForNewIdentity` launched as fire-and-forget)

**Failure scenario:** On first launch after onboarding, `initMessaging` is called and
`bootstrapForNewIdentity` is launched in `appScope` as a fire-and-forget coroutine.
`messagingService` is set immediately after (line 390), before the bootstrap coroutine
has completed. If the user switches back to a chat and sends a message in the same
instant, `encryptUnderLock` runs while `bootstrapForNewIdentity` is still in-flight,
possibly competing over `signedPreKeyRepository.upsert()`. More concretely, the incoming
side: if Alice sends a message and Bob's device receives it before `bootstrapForNewIdentity`
has published Bob's bundle, Alice's `encryptUnderLock` gets a 404 and puts the message in
`WAITING_FOR_RECIPIENT_BUNDLE`. This is handled correctly. However if the bootstrap is
slow (relay latency, cold start), and a peer has cached Bob's old bundle (from Alpha-1),
the 4-DH handshake will fail because the old bundle no longer has valid local keypairs
(migration wiped them). The error bubbles as a `SessionBootstrapException.SpkNotFound`
and the peer's message is permanently undeliverable — it is logged, not ack'd, so the
relay redelivers it on every reconnect until TTL.

**Root cause:** `bootstrapForNewIdentity` is async and not awaited before
`messagingService` is exposed to callers. No serialization between bootstrap completion
and the first allow-send.

**Recommended fix:** In `initMessaging`, await `bootstrapForNewIdentity()` before setting
`messagingService`. Gate the UI's ability to send a first message behind a
`preKeyLifecycle.bootstrapForNewIdentity()` completion signal (can be a
`MutableStateFlow<Boolean>` on AppContainer).

---

### F-09 — HIGH: disconnect() does not drain in-flight envelopes; in-memory outbox lost on service stop

**File:** `shared/core/transport/src/commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt`
Lines 538–547 (`disconnect`)

**Failure scenario:** User force-swipes the app or Android kills the foreground service
for battery reasons. `onDestroy` calls `disconnect()`. `disconnect()` sets
`disconnectRequested = true`, cancels jobs, cancels the scope, and calls `session.close()`.
Any envelopes in `pendingOutbox` (e.g., message typed during a brief connection hiccup)
are discarded. The DB rows remain `QUEUED` but (per F-01) are never retried on next start.

**Root cause:** `disconnect()` does not drain `pendingOutbox` before closing. There is no
flush-before-disconnect path.

**Recommended fix:** Before cancelling the scope in `disconnect()`, attempt a best-effort
`flushPendingOutbox()` with a short timeout (e.g. 3 seconds). This pairs with the F-01
fix — once QUEUED rows are retried on startup, this becomes less critical.

---

### F-10 — MEDIUM: ICE candidates may arrive before offer is processed and be silently queued against the wrong peerConnection

**File:** `apps/android/src/androidMain/kotlin/phantom/android/calls/CallManager.kt`
Lines 231–241 (`handleIce`)

**Failure scenario:** Because call signals are plaintext and go through the same relay
queue, the relay may deliver `call_ice` frames before `call_offer` if the offer was
queued from a previous session and ICE arrives live. `handleIce` checks
`peerConnection?.remoteDescription != null`; if false, it buffers in `pendingIceCandidates`.
But `pendingIceCandidates` is a plain `mutableListOf` with no size bound and no
expiration. If an ICE frame arrives before `handleOffer` is called (because the offer
is still in the relay store from a prior disconnected attempt), `pendingIceCandidates`
accumulates stale candidates that will be applied to the wrong `PeerConnection` instance
when the next call starts.

**Root cause:** `pendingIceCandidates` is not cleared when no active call exists.
`handleOffer` does not clear the list before setting up the new session.

**Recommended fix:** In `handleOffer` (line 151), clear `pendingIceCandidates` before
setting `_activeCall.value`. Add a `callId` check in `handleIce` so candidates from a
prior call session (different `callId`) are discarded rather than queued.

---

### F-11 — MEDIUM: Relay store is in-memory only; all queued envelopes lost on relay process restart

**File:** `services/relay/src/routes.rs` lines 155–175 (in-memory store flush on connect)
Referenced in `state.rs` (not read, but store is an in-memory HashMap per the Arc<AppState> pattern)

**Failure scenario:** The relay restarts (deploy, OOM, crash). All pending envelopes for
offline recipients are lost. Recipients who were offline at relay restart never receive
those messages. The sender's DB shows `QUEUED` or `SENT` status with no error. No retry
from the sender occurs (F-01).

**Root cause:** The relay's envelope store is an in-memory `HashMap`. There is no
persistence layer (no SQLite, no Redis, no write-ahead log).

**Recommended fix (structural):** This is a known Alpha limitation. For Kickstarter
demo safety: add SQLite persistence to the relay store (one table: envelopes with TTL).
This is the single highest-leverage reliability fix for the relay side before demos.
Flagged as "needs implementation before public Beta" per ADR scope.

---

### F-12 — MEDIUM: KeystoreManager identity key has no setUnlockedDeviceRequired; DatabasePassphraseManager does

**File:** `apps/android/src/androidMain/kotlin/phantom/android/security/KeystoreManager.kt`
Line 38 (TODO comment, no `setUnlockedDeviceRequired`)
`shared/core/storage/src/androidMain/kotlin/phantom/core/storage/DatabasePassphraseManager.kt`
Line 65 (`setUnlockedDeviceRequired(true)`)

**Failure scenario:** The identity private key (DH key) is encrypted with a Keystore
key that does NOT require the device to be unlocked, while the database passphrase key
DOES require it. This creates an asymmetry: after a biometric change, the database
becomes inaccessible (passphrase key invalidated, F-02) but the identity key Keystore
entry survives. On recovery, a new passphrase is generated and a new empty database is
opened — but the identity private key can still be decrypted from the old DB entry that
is now in an inaccessible database. This combination of partial invalidation creates
a confusing state where the identity appears to load (from the old ciphertext, which
was decrypted before the DB open) but there are no messages and no conversations.

**Root cause:** Inconsistent `setUnlockedDeviceRequired` policy between the two
Keystore key provisioners.

**Recommended fix:** Make both Keystore keys consistent. Either both require unlock
(preferred for security) or neither does. If `setUnlockedDeviceRequired(true)` is
applied to the identity key, devices without a lock screen will fail to generate the key
— add a runtime check for `KeyguardManager.isDeviceSecure()` before provisioning.

---

### F-13 — MEDIUM: startReceiving() is not idempotent at the flow-collection level

**File:** `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt`
Lines 313–335 (`startReceiving`)

**Failure scenario:** `startReceiving` guards with `if (receiving) return`, sets
`receiving = true`, then launches `transport.incoming.onEach {...}.launchIn(scope)`.
If `onStartCommand` and an Activity `LaunchedEffect` both call `startReceiving` on
the same `DefaultMessagingService` instance in a race before either has set `receiving`
to true (e.g., two coroutines call the suspend function simultaneously), two collectors
are launched. Every inbound envelope is then processed twice: two ratchet decrypts of
the same ciphertext, where the second decrypt will fail MAC verification and produce an
error log, but not before the first decrypt advances the chain state. The
`activeProcessing` set in `handleDeliver` prevents the duplicate from completing (the
second caller sees the ID already present), but the lock contention is a silent race that
depends on coroutine scheduling order.

**Root cause:** `receiving` flag is read and written without synchronization (it is
`@Volatile` which prevents caching but not TOCTOU in a coroutine context where two
coroutines can both read `false` before either writes `true`).

**Recommended fix:** Gate the receiving startup with a `Mutex` rather than a bare
boolean, or use `compareAndSet` on an `AtomicBoolean`.

---

### F-14 — LOW: generateAndPersistSpk does not persist the SPK; only publishBundle does

**File:** `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/PreKeyLifecycleService.kt`
Lines 184–199 (`generateAndPersistSpk`), lines 224–267 (`publishBundle`)

**Failure scenario (needs verification):** `generateAndPersistSpk` generates the SPK
keypair and constructs the entity but does NOT call `signedPreKeyRepository.upsert()`.
The persist happens inside `publishBundle` (line 231). If the process is killed after
`generateAndPersistSpk` returns but before `publishBundle` calls `upsert`, the SPK
private key is lost. On next launch, `bootstrapForNewIdentity` finds
`signedPreKeyRepository.get() != null` — false (nothing was persisted) — and runs
bootstrap again, generating a different SPK. This double-bootstrap is harmless IF the
relay accepts the new publish. However, if the relay's `max_payload_bytes` is exceeded
or the relay is down, the second bootstrap also fails and the user remains permanently
un-bootstrapped (no bundle published, no first messages possible).

**Root cause:** SPK persist and network publish are bundled in a single function
(`publishBundle`) with no intermediate persistence checkpoint.

**Recommended fix:** Call `signedPreKeyRepository.upsert(entity)` inside
`generateAndPersistSpk` immediately after generating the keypair. This ensures local
state is durable even if network publish fails.

---

### F-15 — LOW: toggleMute logic is inverted

**File:** `apps/android/src/androidMain/kotlin/phantom/android/calls/CallManager.kt`
Line 268

**Failure scenario:** `toggleMute()` reads `localAudioTrack?.enabled()` as `enabled`,
then calls `setEnabled(!enabled)` (correct), but then sets `isMuted = enabled` (wrong).
If the track was enabled (not muted), `enabled = true`, the track is set to disabled
(muted), but `isMuted` is set to `true` — that is accidentally correct. However on the
second call: `enabled = false` (track is now disabled), `setEnabled(true)` (unmutes),
and `isMuted = false` — that is correct too. The logic happens to produce the right
result because `isMuted = enabled` mirrors the pre-toggle state, which equals the
inverse of the desired state. This is a latent bug that will break if the initial state
of `isMuted` is ever set non-null before the first toggle.

**Root cause:** Semantically incorrect assignment. `isMuted` should be `!enabled` (the
new state) not `enabled` (the old state). Currently accidentally correct.

**Recommended fix:** Change line 268 to `_activeCall.value = _activeCall.value?.copy(isMuted = !enabled)`.

---

## Summary Table

| ID   | Severity | Area               | User-visible symptom                                  |
|------|----------|--------------------|-------------------------------------------------------|
| F-01 | Critical | Transport/Storage  | Messages stuck in QUEUED forever after reconnect      |
| F-02 | Critical | Storage/Keystore   | App loses all history after biometric/PIN change      |
| F-03 | Critical | Calls              | Caller stuck on "Calling…" after callee answers       |
| F-04 | High     | Messaging/Crypto   | First message silently lost on crash during send      |
| F-05 | High     | Transport          | Voice messages never deliver (silent relay drop)      |
| F-06 | High     | Messaging          | Memory leak — one Mutex per peer, never freed         |
| F-07 | High     | Calls/UI           | Incoming call shows pubkey instead of username        |
| F-08 | High     | PreKey/Lifecycle   | First messages can fail if bootstrap not yet done     |
| F-09 | High     | Transport          | Outbox lost when service is killed by Android         |
| F-10 | Medium   | Calls              | Stale ICE candidates corrupt next call session        |
| F-11 | Medium   | Relay              | All queued messages lost on relay restart             |
| F-12 | Medium   | Storage/Security   | Inconsistent Keystore policy; partial invalidation    |
| F-13 | Medium   | Messaging          | Rare: two delivery pipelines if race on startReceiving |
| F-14 | Low      | PreKey             | SPK keypair not persisted before network publish      |
| F-15 | Low      | Calls              | toggleMute logic accidentally correct but semantically wrong |
| F-16 | Critical | Messaging/Crypto   | Zombie pre-migration envelope loops forever; MAC always fails |
| F-17 | High     | Messaging/Relay    | Relay redelivers permanently undecryptable envelope every reconnect |

---

*End of original audit. Supplemental section appended below.*

---

## Supplemental: QA Incident 2026-05-01 — Envelope id=0e8780fb-175

Added: 2026-05-01. Source: QA logs from Caddy-fix session (phone → emulator).

### Plain-language summary

A message sent at 09:22 from the phone is being delivered by the relay to the emulator
on every reconnect, all the way to 14:53 and beyond. Every delivery attempt fails with
the same error: "MAC verification error." The emulator cannot decrypt it. The relay never
gets the confirmation it needs to delete the envelope, so it keeps delivering it in an
infinite loop. After 5 hours of testing that envelope was still looping.

There are two separate problems: why the emulator cannot decrypt it (Issue A), and why
the relay keeps trying to deliver it forever (Issue B).

---

### Issue A — Root cause diagnosis: why the emulator cannot decrypt envelope 0e8780fb-175

**Five hypotheses evaluated against the code:**

**Hypothesis 5 — PR C / Alpha-2 migration aftermath (MOST LIKELY, confidence: high)**

PR C wiped every session on both devices and bootstrapped fresh Alpha-2 sessions. The
phone generated envelope 0e8780fb-175 at 09:22:08. The relay stored it. The emulator
received it later — but by then the emulator's ratchet state had been bootstrapped from
a *different* first message (one that carried an `x3dhInit` header). The emulator's
receiving chain is now positioned AFTER the key position that was used to encrypt
0e8780fb-175. The Double Ratchet in this codebase has no skipped-message-key cache
(confirmed: `RatchetState.kt` lines 11-12 explicitly notes this limitation). There is
no way to recover a key from a position the chain has already advanced past.

Evidence: (a) the emulator log shows "Session loaded" — a fresh Alpha-2 session exists;
(b) the envelope was sent at 09:22 but first appeared in the emulator log at 14:53,
meaning 5+ hours passed during which other traffic established the ratchet state;
(c) every retry fails with the exact same error, which rules out a transient state
problem — the key is permanently gone.

**Hypothesis 1 — Double-send with advanced ratchet (SECOND MOST LIKELY, confidence: medium)**

`sendMessage` calls `encryptUnderLock`, which calls `ratchet.encrypt(state, plaintext)`
and then `saveSession(conversationId, newState)`. The chain key advances on every call.
The transport layer's `pendingOutbox` re-tries the already-encrypted wire bytes
(`sendRaw`) — it does NOT call `encryptUnderLock` again. So transport retries are safe.

However: if the Caddy reconnect dropped the connection between `sendRaw` attempts, and
the phone also triggered `retryWaitingMessages()` (which calls `sendMessage()` again with
the same message id), a SECOND encryption would be produced from a further-advanced chain
key. The DB guards against duplicate `insertMessage` by id, but `encryptUnderLock` itself
has no idempotency guard by `messageId` — calling it twice for the same id produces two
different ciphertexts from different chain positions.

The emulator would advance past the first key (if it received any other message in between),
leaving the second ciphertext undecryptable.

This scenario requires the message to have been in `WAITING_FOR_RECIPIENT_BUNDLE` status
to trigger `retryWaitingMessages`. The evidence does not confirm this, so Hypothesis 5
is ranked higher — but both can be simultaneously true.

**Hypothesis 2 — Session bootstrap mismatch (LOW LIKELIHOOD)**

Both sides use `deriveConversationId` = `sorted(myPubKey, theirPubKey).joinToString("_")`.
The SessionManager's `initiatorBootstrap` and `recipientBootstrap` both take
`localIdentityKeyPair` and peer identity as inputs to `X3DHProtocol`. Roles (initiator
vs recipient) are determined by which side calls which method, not by key ordering alone.
The QR scan flow determines who calls `initiatorBootstrap`. This is a structural concern
but the logs confirm a session was loaded and the same key position fails every time,
which is inconsistent with a pure bootstrap mismatch (that would fail on the first
message, not 5 hours later with a chain that has advanced).

**Hypothesis 3 — Ratchet state save race (LOW LIKELIHOOD)**

The per-conversation `Mutex` in `DefaultMessagingService` (lines 94-100) correctly
serialises all `loadSession → encrypt/decrypt → saveSession` sequences. A race is
not structurally possible given the mutex is held for the entire load-decrypt-save
sequence.

**Hypothesis 4 — Storage corruption (LOW LIKELIHOOD)**

`saveSession` calls `ratchetStateRepository.upsertRatchetState()` — a single SQL upsert.
SQLDelight + SQLCipher provide atomic writes at the row level. A torn write would corrupt
the entire row (JSON blob), which would surface as a `SerializationException` on next
load, not a MAC error on decrypt.

---

**Immediate action required for Issue A**

The root cause is structural: there is no skipped-message-key cache. This was a known
Alpha-0 constraint (noted in `RatchetState.kt`). The migration between Alpha-1 and Alpha-2
left zombie envelopes in the relay store that were encrypted under a now-unreachable chain
key position. These envelopes can never be decrypted. They cannot be "fixed" without
re-encryption from the sender.

The fix has two parts:

1. **Relay-side (urgent):** After PR C deployed, the relay should have flushed all
   envelopes older than the migration timestamp. It did not. A one-time relay-side command
   to purge all envelopes with a `created_at` earlier than the Alpha-2 deploy time (approx
   2026-04-28 per commit history) would clear the zombie queue for all affected users.
   This is a relay operations task, not a code change.

2. **Code-side (Phase 2):** Implement a skipped-message-key cache in `RatchetState` (add a
   `skippedMessageKeys: Map<Pair<ByteArrayKey, Int>, ByteArray>` field with a bounded size,
   typically max 1000 entries per the Signal spec). This enables out-of-order delivery and
   eliminates this class of failure for future migrations or reorder events.

---

### Issue B — Why the relay keeps redelivering a permanently failed envelope

**How the loop works:**

`handleDeliver` calls `ratchet.decrypt()`. That throws `IllegalArgumentException` (MAC
error). The exception is caught by the `.onFailure` block at line 785 of
`DefaultMessagingService.kt`. Logging happens. The function returns. `sendDeliveryAck`
is never called. The relay does not know delivery failed. On the next reconnect the relay
re-emits the stored envelope from its in-memory store. `handleDeliver` runs again. Same
result. Infinite loop.

The existing idempotency guard (`getMessageById(deliver.messageId) != null`) only fires
if the message was *successfully* stored. A message that failed decrypt is never stored,
so the guard never trips.

**Why the code deliberately avoids acking on unknown failures (see comments at lines
466-474):** an earlier version silently acked on any parse failure and was found to eat
legitimate user data. The current design is conservative: do not ack unless you know
what you are looking at. This is the right policy in general but it has no carve-out for
the one case where acking is safe: a hard cryptographic failure with no possible recovery.

**Recommended fix for Issue B:**

Add a `dead_letter_envelopes` table to the local database with columns:
`envelope_id TEXT PRIMARY KEY, conversation_id TEXT, failure_reason TEXT, failed_at INTEGER, payload_preview TEXT`.

In `handleDeliver`, add a new catch branch specifically for `IllegalArgumentException`
where `message` contains "MAC verification error". The branch should:
1. Insert a row into `dead_letter_envelopes` with the envelope id and truncated payload
   preview (first 80 bytes of the raw payload, for diagnostics).
2. Call `transport.sendDeliveryAck(deliver.messageId)` to break the redeliver loop.
3. Log at ERROR level: "Permanently undecryptable envelope ack'd to dead-letter:
   id=... Relay will no longer redeliver. Row written to dead_letter_envelopes."

Safety considerations:
- This is explicit data loss. The message is gone. The `dead_letter_envelopes` row is
  the audit trail. A future "Help & Feedback" screen can surface dead-letter entries to
  the user ("1 message could not be decrypted — it may be from before an update").
- Only trigger on MAC failure (which is a hard cryptographic verdict from libsodium, not
  a transient condition). Do not trigger on parse errors (`SerializationException`) or
  network errors — those are potentially recoverable.
- The `dead_letter_envelopes` table must be included in the SQLCipher database (same
  encryption boundary as messages). Do not write dead-letter rows to a plaintext log.
- Do not show a per-message error banner in the chat UI. One aggregate notice in Settings
  is enough.

**Layer affected:** `shared/core/messaging` (feature layer) and
`shared/core/storage` (core layer — new table migration required).

---

### Sprint reprioritisation

The in-flight PR sprints are:
- **Track A (1-5 Reliability sprint):** F-01 (QUEUED retry), F-04 (crash window),
  F-09 (drain on disconnect), F-11 (relay persistence), F-08 (bootstrap gate).
- **Track B (Security sprint):** P1 security blockers from the 2026-04-27 audit.

**F-16 and F-17 (this incident) do not block Track B.** The security findings are
independent of the ratchet-state-position problem.

**F-16 and F-17 partially overlap with Track A:**
- The relay-side zombie flush (Issue A fix 1) should be inserted as an urgent ops task
  before the next QA session. It requires no code change — just a relay admin command.
  Estimated effort: 15 minutes on the relay host.
- The `dead_letter_envelopes` fix (Issue B) is a small, bounded feature: one new SQL
  table migration, one new catch branch in `handleDeliver`, no interface changes. It can
  be added to the Track A sprint as item A-6 without affecting the other items.
- The skipped-message-key cache (Issue A fix 2) is Phase 2 scope. It is not a Kickstarter
  blocker because the zombie-flush ops task clears the immediate symptom and no
  Alpha-2 user should be generating cross-migration envelopes after the relay is flushed.

**Recommended priority order for next session:**
1. Relay ops: flush pre-migration envelopes (no code, 15 min).
2. Track B security sprint (unblocked).
3. Track A item A-6: dead-letter table + ack-on-MAC-failure (small, safe, kills the loop).
4. Track A items A-1 through A-5 in original order.

---

### F-16 — CRITICAL: Zombie pre-migration envelope permanently undecryptable; ratchet has no key cache

**Files:**
`shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/RatchetState.kt` (lines 11-12, no skipped-key cache)
`shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/LibsodiumDoubleRatchet.kt` (decrypt, no skipped-key lookup)

**Failure scenario:** PR C migration wiped all local ratchet sessions. The relay's
in-memory store was not flushed. Envelopes encrypted under the old (Alpha-1) chain key
position remained in the relay store. After migration the receiver bootstrapped a fresh
session from a subsequent first-message exchange. The old envelope was then delivered to
a chain key position that no longer exists locally. MAC verification fails permanently.
The Double Ratchet implementation has no skipped-message-key cache (explicit design note
in `RatchetState.kt`), so there is no recovery path.

**Root cause:** Intentional Alpha-0 simplification (no skipped-key cache) combined with
relay not flushing pre-migration envelopes on deploy.

**Recommended fix:**
- Immediate (ops): flush relay envelope store of all envelopes with `created_at` before
  the Alpha-2 migration deployment date.
- Future (code): implement Signal-spec skipped-message-key cache in `RatchetState` and
  `LibsodiumDoubleRatchet.decrypt`. Bounded to 1000 entries per session. Phase 2 scope.

---

### F-17 — HIGH: Relay redelivers permanently undecryptable envelope every reconnect; no dead-letter path

**Files:**
`shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt`
(handleDeliver, lines 785-795 — onFailure block does not ack)
`shared/core/storage` — no dead_letter_envelopes table exists

**Failure scenario:** Any envelope that causes `ratchet.decrypt()` to throw (MAC error,
corrupt state) is logged but never ack'd. The relay receives no ack-deliver, keeps the
envelope in its store, and redelivers it on every reconnect. The emulator received and
failed to decrypt envelope 0e8780fb-175 at least seven times across a 5-hour window.
Each attempt advances no ratchet state (the exception fires before `saveSession`) but
consumes a network round trip and burns log space.

**Root cause:** The `onFailure` handler in `handleDeliver` has no carve-out for hard
cryptographic failures that are permanently unrecoverable. The conservative no-ack-on-unknown
policy (correct in general) has no exception for MAC failures.

**Recommended fix:** Add `dead_letter_envelopes` table (schema above). In the `onFailure`
block, detect `IllegalArgumentException` with message containing "MAC verification error"
and ack-deliver the envelope after writing to `dead_letter_envelopes`. No code change to
the relay is needed.

---

*Supplemental section added 2026-05-01. No production code was written.*
