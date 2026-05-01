# PHANTOM Security Audit — 2026-05-01

**Scope:** master HEAD + recent QA PRs (APK 4/7/8-11, PR C, PR C-followup-1/2/3)
**Auditor:** PHANTOM security reviewer (automated read of source — no trust granted to prior audit conclusions without fresh code verification)
**Prior audits:** 2026-04-27 (F1–F15), 2026-04-29 re-verification (F16–F18)
**Files read:** DefaultMessagingService.kt, SessionManager.kt, CallManager.kt, LibsodiumX3DH.kt, SealedSender.kt, PreKeyLifecycleService.kt, SenderKey.kt, RatchetState.kt, PreKeys.kt, routes.rs, prekeys.rs, AppContainer.kt, KeystoreManager.kt, PhantomNotificationManager.kt, DisappearingMessageScheduler.kt, AddContactDialog.kt, MessagingLog.android.kt, LocalSignedPreKey.sq

---

## Section 1 — Status of F1–F18

| # | Title | Status | Evidence (file:line) | Notes |
|---|-------|--------|----------------------|-------|
| F1 | Group control messages in plaintext | **Still open** | `DefaultGroupMessagingService.kt:427-438` | `sendControlMessage()` sends raw base64 JSON with no Double Ratchet wrap. Comment says "future ADR item." Relay sees SenderKey chain bytes and full member lists. |
| F2 | SenderKey `signingPrivHex` in SQLite plaintext | **Still open** | `DefaultGroupMessagingService.kt:86-95, 139-143` | `SenderKeyEntity(signingPrivHex = ...)` written to SQLite without Keystore wrap. Unchanged. |
| F3 | SenderKey KDF: bare SHA-256, no HKDF | **Still open** | `SenderKey.kt:34-36` | `Hash.sha256(chainKey + DOMAIN_MSG)` unchanged. No iteration counter in hash input. |
| F4 | Group member-leave does not rotate keys | **Still open** | `DefaultGroupMessagingService.kt:319-322` | Local delete + `senderKeyRepo.deleteForGroup()` only. Leaver retains all chain keys received before departure. |
| F5 | Call signaling without E2EE | **Still open — see F19/F20 for new detail** | `CallManager.kt:335-349` | Confirmed. Plain base64 JSON on wire, no Double Ratchet, no Sealed Sender on outbound. APK 7 explicitly added a fast-path in the receive side to handle this, acknowledging it as security debt. |
| F6 | `setUnlockedDeviceRequired(true)` TODO | **Still open** | `KeystoreManager.kt:38-41` | TODO comment unchanged. |
| F7 | Plaintext hex fallback in `loadIdentity()` | **Still open** | `AppContainer.kt:443-446` | Migration path remains. Severity stays P2; plain-hex guard is conservative. |
| F8 | RatchetState in plaintext SQLite | **Still open** | `SessionManager.kt:315` | `ratchetStateRepository.upsertRatchetState(conversationId, json.encodeToString(state))` — unencrypted JSON blob. No Keystore wrap added. |
| F9 | MessagingLog writes sender pubkey prefix to logcat | **Still open — new instance added, see F21** | `MessagingLog.android.kt:16-20` | All levels route unconditionally to `android.util.Log`. No `BuildConfig.DEBUG` gate. |
| F10 | Relay logs `key=identity[..16]` on connect/disconnect | **Still open** | `routes.rs:138-143, 220-229` | `key = %&identity[..identity.len().min(16)]` on connect AND disconnect events with `ts_ms`. Presence record is created. |
| F11 | Relay shared-secret token, not per-user auth | **Still open** | `routes.rs:94-103` | Constant-time compare via `subtle::ConstantTimeEq` was added (good), but it is still a single shared secret. Any connected client can impersonate any other identity in a "send" frame's `to` field. |
| F12 | X3DH without OPK + no bundle signature verify on receive | **Partially closed — SPK signature verify added; OPK is now implemented; relay-side substitution still possible on retry** | `SessionManager.kt:128-138, 147-153` `PreKeyLifecycleService.kt:79-92` | SPK Ed25519 signature is verified in `initiatorBootstrap()` before any DH operation. OPKs are generated and published. The residual risk is on the retry path — see F23. Downgraded from P1 to P2. |
| F13 | SenderKey signing infrastructure inoperative | **Still open** | `SenderKey.kt:40-54` | `encrypt()` does not sign. `decrypt()` does not verify. Unchanged. |
| F14 | Notification preview leaks plaintext + `EXTRA_RECIPIENT_PUB_KEY` | **Partially mitigated — still open** | `PhantomNotificationManager.kt:104, 112-114` | 30-char truncation confirmed in place. `EXTRA_RECIPIENT_PUB_KEY` with full pubkey hex is still embedded in the `replyPendingIntent` broadcast, readable by any notification listener. |
| F15 | Identity private key reused as ratchet DH key | **Closed** | `LibsodiumX3DH.kt:139, 67-68` `SessionManager.kt:155-169` | F15 invariant check added in `initiatorBootstrap` and `recipientBootstrap`. `sendingRatchet = generateDhKeyPair()` inside both handshake paths. Tests named `initiatorBootstrap_F15_freshEphemeralIsNotIdentity` exist in build artifacts. Verified closed. |
| F16 | `DefaultMessagingService.handleDeliver` trusts `deliver.from` for non-sealed messages; CallManager not using Sealed Sender | **Still open — compounded by APK 7** | `DefaultMessagingService.kt:368` | `deliver.from` fallback for `sealedSender.isEmpty()` branch is still in place. APK 7 fast-path at line 411 confirms call signals arrive sealed=false and `senderPubKeyHex` is set to `deliver.from` (relay-supplied). |
| F17 | AppContainer notification callback swallows `e.message` | **Closed — exception message no longer logged to logcat without context** | `AppContainer.kt:307-313` | Now logs `e::class.simpleName` + `e.message` via `android.util.Log.e`. Previously silent; now logged but key alias details are not specifically exposed here. Downgraded to informational. |
| F18 | Relay typing event exposes full sender pubkey | **Partially mitigated** | `routes.rs:473-478` | The "from" field in the typing JSON frame is set to `from_identity` (the full authenticated WS identity). This is not truncated. F18 remains open as P2. |

---

## Section 2 — New Findings Since 2026-04-29

### TRANSPORT-BLOCKING FLAG

**F19 and F20 below interact with the APK 7 fast-path (`DefaultMessagingService.handleDeliver:411-427`). That fast-path is necessary for calls to work, but it codifies plaintext signaling as a stable receive path. Shipping the ADR-010 transport fixes without also planning the call-encryption sprint means the plaintext call path becomes load-bearing infrastructure that is progressively harder to remove. These findings are not blockers for merging APK 8-11 transport fixes, but they must be addressed before any public demo where an adversary controls network infrastructure.**

---

### F19 — Plaintext call-signaling: exact data exposure

**Severity:** P1

**File:** `apps/android/src/androidMain/kotlin/phantom/android/calls/CallManager.kt:335-349`
`shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt:411-427`

**What leaks:**

1. `TYPE_CALL_OFFER` payload contains the full WebRTC SDP, which includes: DTLS fingerprint, codec list, ICE candidates (LAN IPv4/v6 addresses from all network interfaces, host candidates, server-reflexive candidates showing NAT'd public IP), and the `o=` line origin which on some WebRTC stacks includes a hostname.
2. `TYPE_CALL_ICE` payloads contain individual `IceCandidateJson` objects — each is a full candidate string (`candidate:... typ host/srflx/relay ...`) exposing the same IP topology incrementally.
3. `TYPE_CALL_ANSWER` and `TYPE_CALL_REJECT` / `TYPE_CALL_HANGUP` expose call timing and whether the callee answered.
4. All of these travel in `RelayMessage.Send` with `sealedSender = ""` and `from = myPubKeyHex` (full, untruncated). The relay stores `envelope_from = from_identity` (the authenticated WS connection identity) in the envelope. The relay's `handle_message` log at `routes.rs:264-271` emits `sealed = false` alongside `msg_id` and `size_b` — a relay operator can trivially correlate call events.

**Who can observe it:**

- Relay operator: sees both parties' full pubkeys plus SDP/ICE in plaintext (not sealed). On the current single-relay setup this is the Phantom infra operator.
- A TLS-terminating middlebox (hostile network, corporate proxy, government DPI with a trusted root cert) can see the full WS stream.
- ICE-Lite or TURN routing does not reduce exposure: ICE candidates still appear in the SDP before ICE negotiation, and the relay sees the OFFER before TURN is negotiated.

**Threat:** Relay compromise or hostile network = full call graph (who calls whom, when, from which IP) plus enough SDP context to attempt DTLS-fingerprint-based call interception.

**Fix:** Route all call-signaling types through `encryptUnderLock` + Sealed Sender (the same path as `sendMessage`). The architectural obstacle noted in `CallManager.kt:338` ("sharing session manager with CallManager crosses module boundaries") is real but resolvable: add a `suspend fun sendSignalingMessage(recipientPubKeyHex: String, payload: MessagePayload)` to `MessagingService` that wraps call payloads identically to regular messages. CallManager calls it instead of `transport.send` directly.

**Blocker:** Yes, before public demo / Kickstarter.

---

### F20 — Plaintext call-signaling: Sealed Sender omission (F5 split)

**Severity:** P1

**File:** `CallManager.kt:341-349`

F5 from prior audits named the absence of E2EE but did not distinguish (a) no encryption from (b) no sender hiding. These are now two separate traceable defects because the fix path differs:

- **F5a (no encryption):** SDP content readable in transit. Fix: encrypt under Double Ratchet.
- **F5b (no sender hiding):** `from = myPubKeyHex` in clear on every call signal. The relay knows caller identity for every call type, not just OFFER. Fix: add Sealed Sender after F5a is done.

Both remain open. F5 should be split in the findings tracker as F5a + F5b = F19 + F20 going forward.

**Blocker:** Yes (same sprint as F19).

---

### F21 — 240-byte plaintext preview written to logcat in unparseable-envelope branch

**Severity:** P2

**File:** `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt:462-472`

```kotlin
val previewLen = minOf(ciphertextText.length, 240)
val preview = ciphertextText.substring(0, previewLen)
messagingLog(
    MessagingLogLevel.ERROR,
    "Unparseable wire payload ... preview=<<<$preview>>> ..."
)
```

**What leaks:** `ciphertextText` at this point in the code is the output of `MessagePadding.unpad(rawPayloadBytes)` (line 431), then `.decodeToString()` (line 450). For a malformed envelope this might be undecodable binary; for a call-signaling envelope that bypassed the sealed-sender fast-path at line 411 it would be the raw JSON payload including `{"type":"call_offer","sdp":"..."}` with the full SDP string, up to 240 chars of it.

Additionally: the log line includes `sealed=${deliver.sealedSender.isNotEmpty()}` and `totalBytes=`. If the 240-char preview happens to start with a WireFrame JSON structure that includes `senderSigningPublicKeyHex`, that hex fragment would also be logged.

F9 (prior audit) covers the general logcat leak pattern. F21 is a distinct new source because it is a `MessagingLogLevel.ERROR` call introduced after the 2026-04-29 audit, it fires on a code path triggered by real (call-signaling) envelopes, and it writes up to 240 bytes of payload content — significantly more than the 16-char prefix cap elsewhere.

**Fix:** Remove `preview` from the log line entirely, or replace it with `previewBytes=$previewLen` (the length only). The message and `id` fields already provide enough context for debugging without leaking content. Gate remaining INFO-level `senderPubKeyHex.take(16)` logs behind `BuildConfig.DEBUG` as called for by F9.

**Blocker:** No (P2), but should be in the same sprint as F9.

---

### F22 — SPK and OPK private keys in plaintext SQLite (new attack surface from PR C)

**Severity:** P1

**File:** `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/LocalSignedPreKey.sq:3-13`
`shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/PreKeyLifecycleService.kt:192-199`

The `local_signed_pre_key` table stores `private_key_hex TEXT NOT NULL` and `previous_private_key_hex TEXT` in plaintext SQLite (the same database that already holds ratchet state under F8). `LocalOneTimePreKeyRepository` similarly stores OPK private keys in plaintext (confirmed by analogous schema pattern).

**Why this is P1 and separate from F8:**

- F8 covers RatchetState blobs. F22 covers the X3DH prekey private keys.
- Compromise of the SPK private key allows a passive adversary who recorded past traffic to retroactively complete any X3DH handshake that used that SPK, deriving the session root key and decrypting all messages from that session. This breaks forward secrecy of X3DH.
- The OPK private key has the same property for any handshake that included that OPK.
- PR C introduced this new storage without adding Keystore wrapping. The identity DH private key is Keystore-wrapped via `KeystoreIdentityRepository`. The SPK and OPK private keys are not.

**Fix:** Add a `KeystoreSignedPreKeyRepository` and `KeystoreOneTimePreKeyRepository` wrapper analogous to `KeystoreIdentityRepository`. Encrypt `privateKeyHex` and `previousPrivateKeyHex` with `KeystoreManager.encrypt()` before writing, decrypt on read. Same pattern already established by F6/F7 mitigations.

**Blocker:** Yes, before public demo / Kickstarter.

---

### F23 — WAITING_FOR_RECIPIENT_BUNDLE retry fetches a fresh bundle without re-verifying signing key binding

**Severity:** P2

**File:** `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt:843-872`

`retryWaitingMessages()` calls `sendMessage()` which calls `encryptUnderLock()` which calls `preKeyApi.fetchBundle()` again. On the retry, the fetched bundle is passed to `sessionManager.initiatorBootstrap()`, which does verify the SPK signature. However:

1. The SPK signature is verified against the `signing_pubkey_hex` that is **inside the bundle itself** — the same bundle being evaluated. There is no cached-expected-signing-key that the current retry would compare against.
2. A relay attacker who intercepted the first fetch (which returned 404) could at a subsequent retry serve a forged bundle with a different identity key and a matching signing keypair (self-signed bundle). The signature would verify against the forged `signing_pubkey_hex` because both were chosen by the attacker.

This is the same root issue called out for F12: bundle signature verify is necessary but not sufficient without an out-of-band trust anchor for the signing key. The comment at `DefaultMessagingService.kt:540-543` acknowledges that `senderSigningPublicKeyHex` persistence is deferred ("TODO PR C commit 12"). Until that cache exists, the retry path has no way to detect a substituted bundle.

**Fix (short-term):** Store the `signingPubkeyHex` learned from the first successful bootstrap on the `ConversationEntity`. On retry, if a prior successful session existed (it would not for a WAITING message, by definition), compare the fetched bundle's signing key against the cached one. For the true first-contact retry case, the risk is unavoidable until the Safety Number / key verification UX lands (ADR reference needed).

**Fix (long-term):** Implement the signing-key cache described in the TODO at DMS:540, gate it behind a Key Change Warning screen before the retry proceeds.

**Blocker:** No (P2). Risk exists only for the window between first attempted send and first successful session establishment. The attacker would also need to control the relay.

---

### F24 — AddContactDialog validates key length but not hex format or curve point validity

**Severity:** P2

**File:** `apps/android/src/androidMain/kotlin/phantom/android/screens/chatlist/AddContactDialog.kt:33, 147-154`

```kotlin
val isValid = resolvedKey.length >= 64
```

The only validation is `length >= 64`. A string of 64+ characters that is not valid hex (e.g. contains non-hex characters) will pass the UI gate and be stored as `theirPublicKeyHex` in `ConversationEntity`. Downstream, `hexToByteArray()` in `AppContainer.kt:459-462` will throw an `IllegalArgumentException` when the first non-hex character is encountered during `substring(...).toInt(16)`.

**Consequences:**
1. A crafted non-hex string of length 64 stored via `AddContactDialog` causes `encryptUnderLock()` to throw on the next send attempt, which is caught by `runCatching` and silently returns `Result.failure`. The user sees a QUEUED message that never sends, with no error displayed.
2. No crash or UB on the parsing side — Kotlin's `toInt(16)` throws cleanly. However the exception propagates through DMS's `runCatching` and the error message logged at `MessagingLogLevel.ERROR` will contain a partial dump of the invalid key string (via `e.message`), which is then written to logcat per F9.
3. There is no curve point validation (checking that the decoded bytes are a valid X25519 point). A valid-looking 32-byte string that is not a valid curve point would cause `ScalarMultiplication.scalarMultiplication` inside libsodium to return a low-order point or fail silently depending on the libsodium build. This is a potential denial-of-service: the session establishment attempt fails but the WAITING message is never cleared.

**Fix:** In `parseContactString`, validate that the key portion is strictly hex (`all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }`), has length exactly 64, and attempt a test `ByteArray` decode before accepting it as valid. Do not gate further on curve point validity in the UI layer — let the crypto layer throw and surface it as an `InvalidContactKey` error type.

**Blocker:** No (P2). The key is stored, not executed.

---

### F25 — Disappearing timer enforcement is local-only; peer can receive messages after local expiry without the local device knowing

**Severity:** P2

**File:** `apps/android/src/androidMain/kotlin/phantom/android/service/DisappearingMessageScheduler.kt`
`shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt:656-657`

The timer is wired correctly on the receive side: `expiresAtMs` is set from `getDisappearingTimer(conversationId)` and stored on `MessageEntity`. `DisappearingMessageScheduler` deletes rows when `expiresAtMs` has passed. The update message type `TYPE_DISAPPEARING_TIMER` propagates the timer to the peer.

However:

1. `DisappearingMessageScheduler` is a coroutine loop tied to `appScope`. If the app process is killed before `deleteExpiredMessages()` fires, no deletion occurs until the next launch. On Android, the process can be killed by the OS at any time after the foreground service is removed.
2. `plaintextCache` is stored in the `messages` table alongside `ciphertext`. Both the ciphertext blob and the plaintext cache survive in SQLite until `deleteExpiredMessages()` runs. A forensic SQLite read of the DB file between the message's logical expiry and the process's next launch would expose both.
3. The timer update (`TYPE_DISAPPEARING_TIMER`) is sent E2EE via `encryptUnderLock`, but a peer running old Alpha 1 code that does not implement the DELETE path will simply not delete. There is no enforcement mechanism.

This is a known limitation for Alpha phase. It is classified P2 because it does not break the cryptographic guarantees, only the operational "messages disappear" promise.

**Fix:** Use `WorkManager` with a `setInitialDelay` constraint so deletion fires even after process kill. Use SQLCipher's WAL journal to limit forensic exposure. Document the limitation in the Alpha 2 release notes.

**Blocker:** No (P2). Cosmetic promise, not cryptographic.

---

### F26 — Relay token passed in WebSocket URL query string; visible in server access logs

**Severity:** P2

**File:** `apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt:139`
`services/relay/src/routes.rs:94-103`

`BuildConfig.RELAY_TOKEN` is appended as `?token=<value>` to the WebSocket URL. The relay's `TraceLayer` at `routes.rs:66-76` logs only `method` and `path` (not query string) — this was a deliberate improvement noted in the comment. However:

1. Any reverse proxy in front of the relay (Caddy, nginx) that logs the full URI by default will record `?token=<secret>` in access logs.
2. On Android, `BuildConfig.RELAY_TOKEN` is a compile-time string constant embedded in the APK. It is readable by anyone who decompiles the APK.
3. As noted in F11, this is a shared secret — the same token grants any holder the ability to connect as any identity.

The combination means: leaked token (from APK decompile or proxy log) + F11 single shared-secret model = anyone can subscribe to any identity's message queue.

**Fix (token leak):** Pass the token as an HTTP `Authorization: Bearer <token>` header in the WebSocket upgrade request, not as a URL query parameter. The relay reads `req.headers()` in the upgrade handler. This prevents proxy log leakage.

**Fix (root cause, F11):** Replace the shared token with per-user signed challenge-response auth (signed nonce using the identity private key), as called for by F11.

**Blocker:** No (P2 on its own). Severity of the combination with F11 is P1 collectively.

---

## Section 3 — Pre-Kickstarter Blocker List

The following items must be resolved before any public demo, Kickstarter campaign launch, or App Store submission. Items are listed in rough fix-dependency order.

1. **F15 [CLOSED — VERIFIED]** — Identity key reused as ratchet seed. Fixed in PR C. No action needed.

2. **F22 — SPK and OPK private keys in plaintext SQLite** (P1). PR C added new key material to the DB without Keystore wrapping. Compromise of the SQLite file (local attack, backup extraction, rooted device) retroactively breaks forward secrecy of all sessions established with those keys. Fix: `KeystoreSignedPreKeyRepository` wrapper before Alpha 2 ships.

3. **F19 + F20 — Plaintext call signaling** (P1, combined). Every call reveals both parties' identity keys and LAN/WAN IP topology to the relay operator and any TLS-intercepting network. Fix: route call signals through `DefaultMessagingService.sendMessage` + Sealed Sender. This is the single most visible privacy failure for a first-contact demo scenario.

4. **F8 — RatchetState in plaintext SQLite** (P1). The full Double Ratchet state (root key, chain keys, ratchet private key) is stored as unencrypted JSON. Physical access to the device = decrypt any recorded traffic from any session whose state was on disk. Fix: Keystore-wrap the serialized blob.

5. **F5 / F1 / F16 — Control messages (call signals + group control) without E2EE or Sealed Sender** (P1 collectively). The relay operator sees group membership events, SenderKey chain bytes, call graphs, and caller identities in plain. Fix: run all control message types through `encryptUnderLock`.

6. **F2 + F13 — SenderKey `signingPrivHex` plaintext + signing inoperative** (P1). Group messages cannot be authenticated by recipients. A relay-substituted group message is accepted without error. Fix: either implement HMAC-SHA256 per ciphertext on the group stream or remove the signing field entirely and document the threat. Either way, stop storing `signingPrivHex` in SQLite without wrapping.

7. **F3 — SenderKey KDF: bare SHA-256** (P1). The chain ratchet does not meet standard security margins. Fix: HKDF-SHA256 with the iteration counter bound in.

8. **F4 — Group member leave does not rotate remaining members' keys** (P1). A removed group member retains the SenderKey chain and can decrypt future messages. Fix: re-generate and redistribute SenderKeys to all remaining members on each leave/removal event.

9. **F11 + F26 — Relay shared-secret token** (P1 combined). Single shared secret embeds in APK, leaks from proxy logs, and grants full queue access for any identity. Fix before Kickstarter: per-user signed challenge-response.

10. **F14 — Notification `EXTRA_RECIPIENT_PUB_KEY` in broadcast intent** (P2 → effectively P1 for privacy). Any installed notification listener app reads the recipient's full pubkey hex from the quick-reply PendingIntent. Fix: replace with an opaque internal conversation row ID. Trivial one-line change with high privacy impact.

---

## Section 4 — What Is Not Broken

The following items were specifically verified against current HEAD and found to be correctly implemented.

1. **Identity DH private key is Keystore-wrapped.** `KeystoreIdentityRepository` in `AppContainer.kt:422-457` correctly encrypts the DH private key with AES-256-GCM before storing in SQLite, and decrypts on load. The migration fallback for pre-Keystore hex values is conservative (all-lowercase hex guard). F7 residual risk is understood and acceptable for Alpha phase.

2. **Sealed Sender is applied on all regular 1:1 message types.** `DefaultMessagingService.sendMessage`, `deleteMessageForBoth`, `editMessageForBoth`, `sendDisappearingTimerUpdate`, `sendReaction`, and `pinMessageForBoth` all produce a `sealedSender` field via `SealedSender.seal()`. The relay side (`routes.rs:319-323`) correctly blanks `from` for sealed envelopes and never logs sender identity for sealed messages. The `SealedSender` implementation uses libsodium X25519 + XSalsa20-Poly1305 with a fresh ephemeral keypair per seal.

3. **F15 (identity key / ratchet key separation) is fixed and guarded by assertion.** `LibsodiumX3DH.initiatorHandshake4DHWithEphemeral` (line 139) and `recipientHandshake4DH` generate a fresh ephemeral for the ratchet seed. `SessionManager.initiatorBootstrap` and `recipientBootstrap` both assert `sendingRatchetPublicKey != localIdentityKeyPair.publicKey.bytes` before persisting state. The old `computeSharedSecret(identity_priv, their_identity_pub)` bootstrap path is gone with no fallback.

4. **SPK Ed25519 signature is verified before any session material is derived.** `SessionManager.initiatorBootstrap` calls `SignedPreKeySigner.verify()` and throws `SessionBootstrapException.InvalidSpkSignature` before calling `x3dh.initiatorHandshake4DHWithEphemeral`. The relay also verifies the SPK signature at publish time (`prekeys.rs:235`). Double verification means a relay that stored an unverified SPK (from a bug or future code change) would still be caught on the client side.

5. **Relay correctly implements OPK atomic single-use semantics.** `prekeys.rs:295-317` pops one OPK inside a single write-lock critical section, preventing two concurrent fetchers from receiving the same OPK. The client side (`SessionManager.recipientBootstrap:233-244`) deletes the OPK from the local pool before deriving the secret (atomic consume), preventing a crash-and-retry from reusing a consumed OPK.

---

*End of audit. 2026-05-01.*
