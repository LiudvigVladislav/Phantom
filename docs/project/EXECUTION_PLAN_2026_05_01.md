# PHANTOM Execution Plan — 2026-05-01

Living roadmap for the current sprint. Ties together yesterday's strategic plan, today's audit findings (architecture + security), and the QA backlog from manual device testing.

This document is **the authoritative working plan**. When in doubt, read this first.

---

## Where we are right now

- **Codebase status:** Alpha-2 X3DH bootstrap shipped (PR C). Real-device QA in progress on Tecno Spark Go (HiOS / Android 12), Galaxy A05 (Android 14), Pixel 8 Pro emulator.
- **Latest APK:** APK 11, commit `fc5fff5d` on branch `fix/call-serializer-and-stuck-envelope-loop`. Ships ADR-010 transport fix (`connectionPool.evictAll()` instead of `dispatcher.cancelAll()` on pong timeout).
- **Pending merge:** the `fix/call-serializer-and-stuck-envelope-loop` branch holds APK 7-11 changes (call signaling fast-path, ADR-010). Should merge after the user verifies APK 11 reconnect behaviour on Tecno.
- **Active audits:** [docs/audit/ARCHITECTURE_AUDIT_2026_05_01.md](../audit/ARCHITECTURE_AUDIT_2026_05_01.md), [docs/audit/SECURITY_AUDIT_2026_05_01.md](../audit/SECURITY_AUDIT_2026_05_01.md). Both completed 2026-05-01.
- **Architectural decisions on disk:** ADR-001…ADR-010. Latest: [ADR-010](../adr/ADR-010-transport-reconnect-deadlock.md) — transport reconnect deadlock root cause + fix.

---

## Strategic context (carried over from prior sessions)

From the official PHANTOM roadmap v2.0 (PHANTOM_ROADMAP_2026.md, NLNET_APPLICATION_DRAFT_V2.md):

- **Mission:** Secure. Decentralised. Uncensorable. Production-quality messenger for iOS and Android with circumvention and offline modes. Not a niche tool — UX as smooth as Telegram, security as strong as Signal, resilience as good as Briar.
- **Stack (frozen):** KMP + Compose Multiplatform, libsignal-client + libsodium, SQLDelight + SQLCipher, Ktor, Rust relay/bootstrap/discovery, Go obfuscation bridge, Kademlia DHT for P2P.
- **Stage:** between Stage 0 (preparation) and Stage 1 (crypto). Alpha-1 was feature-complete, Alpha-2 added X3DH 4-DH bootstrap. **Real-device QA is now exposing the next wave of correctness work** — that is what this plan addresses.
- **Time horizon:** ~10–12 months to MVP, 18–24 to full ecosystem. Kickstarter campaign target after security blockers close.

---

## Two parallel tracks

We have two independent fix sprints. They do not conflict — different files, different concerns, different reviewers.

### Track A — Reliability sprint (5 PRs)

The goal: make the founder's manual-test sequence (add by QR → write → send → receive → call → voice) work reliably without app restarts. Ordered by user-visible impact.

### Track B — Security sprint (10 items)

The goal: close all P1 security findings before any public Kickstarter / Beta announcement. F22 is new (regression from PR C — SPK/OPK private keys in plaintext SQLite) so it jumps to the top.

---

## Track A — Reliability sprint

### PR 1 — "Message after QR-add does not send until restart"

Closes the founder's #1 manual-test pain.

| Finding | Where | What |
|---------|-------|------|
| F-08 | AppContainer.kt:255-263, 390 | `bootstrapForNewIdentity()` is fire-and-forget; UI sees `messagingService` before bootstrap completes. Await it, or gate UI send path on a `bootstrapReady` StateFlow. |
| F-01 | KtorRelayTransport.kt:514-520 | `flushPendingOutbox` silently drops envelopes on `sendRaw` failure. Re-enqueue on failure + sweep DB for `QUEUED` rows on startup. |
| F-09 | KtorRelayTransport.kt:538-547 | `disconnect()` does not flush outbox before cancelling. Add a 3-second best-effort flush. |
| F-04 | DefaultMessagingService.sendMessage + SessionManager.initiatorBootstrap | `saveSession` runs before `insertMessage`. On crash between the two, message disappears from sender's history but peer receives it. Make atomic via single SQLDelight transaction. |

Acceptance: in a fresh-install QA pass, "Add by QR → type → send" delivers the message without an app restart, even if the recipient's WS is briefly down at send time.

Estimated scope: ~6-8 files, ~200 LOC plus tests.

---

### PR 2 — Calls work end-to-end

| Finding | Where | What |
|---------|-------|------|
| F-03 | CallManager.kt:213-229 | `handleAnswer` no-ops silently if `peerConnection == null`. Add null-guard → ENDED transition. Add 60-second ring-timeout coroutine in `startCall`. |
| F-07 | AppContainer.kt:378 | `onCallMessage` looks up conversation by `fromPubKeyHex` but the table key is the sorted-concat of both pubkeys. Lookup always returns null → username falls back to first 8 hex chars. Fix the lookup. |
| F-10 | CallManager.kt:71 | `pendingIceCandidates` never cleared between calls. Clear in `handleOffer`; add a `callId` discriminator on ICE frames. |
| F-15 | CallManager.kt:268 | `toggleMute` sets `isMuted = enabled` (semantically inverted, works by accident). Cosmetic, fix while we're in the file. |

Acceptance: caller sees "Connected" when callee answers, sees correct username on incoming call, sequential calls don't carry stale ICE.

---

### PR 3 — Voice messages

| Finding | Where | What |
|---------|-------|------|
| F-05 | DefaultMessagingService.sendMessage + payload schema | Voice notes inline-base64 the entire blob into a single envelope. If size > relay's `max_payload_bytes` it 413s, retries forever. Chunk into multiple envelopes; reassemble on receiver. Pre-send size check with user-visible error if over hard cap. |

Acceptance: 30-second voice note delivers reliably between phone and emulator on the production relay.

---

### PR 4 — Storage durability

| Finding | Where | What |
|---------|-------|------|
| F-02 | DatabasePassphraseManager.kt:33-48 | Catch `KeyPermanentlyInvalidatedException` + `BadPaddingException`. On invalidation, generate a new key + passphrase and warn the user once. Currently a biometric re-enrol kills the entire local DB silently. |
| F-12 | KeystoreManager.kt:38 | Identity key has `setUnlockedDeviceRequired` TODO; DB passphrase key has it set. Make policy consistent. |
| F-06 | DefaultMessagingService.kt:95-99 | `sessionMutexes` map grows forever. Add `removeConversationMutex(id)` from the conversation-delete path. |
| F-13 | DefaultMessagingService.startReceiving | TOCTOU on `@Volatile var receiving`. Replace with `AtomicBoolean.compareAndSet`. |

Acceptance: factory-reset-style biometric change does not silently destroy the user's chat history.

---

### PR 5 — UX cleanup + small fixes

| Finding | Where | What |
|---------|-------|------|
| F-14 | PreKeyLifecycleService | Persist SPK entity inside `generateAndPersistSpk` immediately after generation, before the `publishBundle` network call. |
| F-21 | DefaultMessagingService.kt:462-472 | The 240-byte payload preview I added in APK 6 logs raw SDP / plaintext to logcat at ERROR. Redact or truncate aggressively. |
| F-24 | AddContactDialog.kt:33 | Validate hex pubkey format (length + character set + curve point check). Currently any 64+ char string passes the UI gate and the failure surfaces only on next send. |
| Memory: double-`connect()` race | PhantomMessagingService | `@Volatile var connectStarted` guard so a second `onStartCommand` while the first is still establishing doesn't open a second socket. |
| QA bug: onboarding keyboard | OnboardingScreen | Auto-collapse keyboard after username submit. |
| QA bug: radar circles on welcome | OnboardingScreen | Remove per design ref [memory: design_reference_screens.md]. |

---

## Track B — Security sprint (Kickstarter blockers)

Pre-launch security work, ordered by severity. New F19–F26 from today's audit are listed alongside the 8 still-open items from F1–F18.

| # | Finding | Severity | Why it blocks Kickstarter |
|---|---------|----------|---|
| 1 | **F22** SPK/OPK private keys plaintext in SQLite (regression from PR C) | P1 | Physical device access ⇒ retroactive forward-secrecy break |
| 2 | **F19+F20** Call signalling has no E2EE and no Sealed Sender | P1 | Relay sees full call graph + IP topology + identity keys in cleartext |
| 3 | **F8** RatchetState plaintext SQLite | P1 | DB access ⇒ decrypt entire active epoch |
| 4 | **F2 + F13** SenderKey signingPrivHex plaintext **and** signing infrastructure inoperative | P1 | Group authenticity defence is dead code |
| 5 | **F1** Group control messages outside Double Ratchet | P1 | Relay can read sender-key chain keys → decrypt all group messages |
| 6 | **F3** SenderKey KDF uses bare SHA-256 (no HKDF) | P1 | Non-standard, fails any external review |
| 7 | **F4** Member-leave does not rotate remaining members' keys | P1 | Departed member retains decryption capability |
| 8 | **F11 + F26** Shared relay token, embedded in APK + leaks via proxy access logs | P1 | Token leak ⇒ subscribe to any identity's queue |
| 9 | **F14** `EXTRA_RECIPIENT_PUB_KEY` in notification PendingIntent | P2 | Any app with `BIND_NOTIFICATION_LISTENER_SERVICE` reads pubkeys |
| 10 | **F6** Identity key missing `setUnlockedDeviceRequired(true)` | P2 | Identity DH usable while device locked |
| 11 | **F7** Plaintext-hex fallback in `loadIdentity()` | P2 | Migration path; remove once confirmed unused |
| 12 | **F9 + F21** Logcat leaks (sender pubkey + my new SDP preview) | P2 | Logcat readable by adb / OEM apps in some builds |
| 13 | **F10** Relay presence logs include identity prefix | P2 | Operator-visible online/offline timestamps per known prefix |
| 14 | **F12** retry path X3DH bundle substitution risk | P2 | `WAITING_FOR_RECIPIENT_BUNDLE` retries refetch the bundle without cached signing-key binding |
| 15 | **F18** Typing event sender pubkey full-length | P2 | Side-channel correlation when Sealed Sender is everywhere else |
| 16 | **F23** retry queue substitution (subset of F12) | P2 | Track separately because cache fix is a clean isolated change |
| 17 | **F25** Disappearing-timer enforcement gaps | P2 | Coroutine-only deletion; killed process retains plaintext |

### Already closed (verified) ✅

- **F15** identity-as-ratchet-key — fixed by fresh ephemeral DH in `LibsodiumX3DH`, defended by assertions
- **F17** notification callback exception swallow — now logs class + message
- **F12 partial** — SPK Ed25519 sig verify added (client + relay); OPK implemented with atomic single-use semantics

### Verified clean ✅

- Identity DH private key Keystore-wrapped (AES-256-GCM)
- Sealed Sender on every regular 1:1 message type
- F15 mitigation has assertions in both `initiatorBootstrap` and `recipientBootstrap`
- SPK Ed25519 signature verified before any DH derivation
- OPK single-use atomic on relay (write-lock pop) + client (delete-before-use)

---

## What "done" looks like

- **PR 1 merged** = founder's manual test sequence works without restart. This is the user-facing fix.
- **PR 1–5 merged** = Alpha-3 candidate. Internal dogfooding can begin.
- **Track B items 1–8 closed** = security clearance for Kickstarter announcement. Items 9–17 can land during the campaign window.
- **NLNet application** can submit any time after Track B items 1–4 close (the first four cover the most user-visible privacy claims).

---

## What QA needs to focus on right now

The current APK on test is **APK 11** (md5 `88c525b3c0515ba3c1562e7e0a423da7`, commit `fc5fff5d`). It contains only the ADR-010 transport fix; no PR 1–5 work has landed yet.

Verification matrix for this APK is in the next section of this doc — see [Manual test checklist for APK 11](#manual-test-checklist-for-apk-11) below.

---

## Manual test checklist for APK 11

APK 11 fixes ONE specific thing: the WebSocket reconnect deadlock after Pong timeout on Tecno HiOS Wi-Fi parking. Everything else from prior reports is **expected to still misbehave** until PR 1+ ships.

### What MUST work after APK 11 (test focus)

1. **Reconnect speed after Pong timeout.** On Tecno, leave the app idle on the chat list for ~90 seconds with screen off, then unlock. Watch logcat for the sequence:

   ```
   Pong timeout (...) — forcing reconnect
   WebSocket connect FAILED ... SocketException: Socket closed   ← within ~1 sec of Pong timeout
   Retry attempt #1 in 1000ms
   Attempting WebSocket connect (attempt=1)
   WebSocket connected successfully                                ← within ~2 sec total
   Re-queueing N unacknowledged envelope(s) from previous session
   Flushing N queued item(s) after reconnect
   ```

   **Pass criterion:** total elapsed time from "Pong timeout" to "WebSocket connected" is **under 3 seconds**.
   **Fail criterion:** the gap is still 25+ seconds. That means `evictAll()` did not unblock the reader and we need to revisit ADR-010.

2. **No reconnect storm.** After the recovery, the connection should stay up for at least 60 seconds without another `WebSocket connect FAILED`. APK 9's bug was a ~12-second reconnect cycle; APK 11 should not show that.

3. **Queued message flush.** Send a message during the parked-radio window. After reconnect, the message should land on the peer **without an app restart**.

### What is EXPECTED to still misbehave (don't file as new bugs)

These are tracked above as PR 1–5 / Track B items. Don't re-report — they are known and prioritised.

- ❌ "Add by QR → write → send" still hangs first message until restart (PR 1 / F-08)
- ❌ Voice messages don't deliver (PR 3 / F-05)
- ❌ Caller stuck on "Calling…" after callee picks up (PR 2 / F-03)
- ❌ Username shows 8 hex chars instead of `@nickname` on incoming call (PR 2 / F-07)
- ❌ Onboarding keyboard doesn't collapse (PR 5 / QA backlog)
- ❌ Radar circles on welcome screen (PR 5 / QA backlog)
- ❌ Online indicator misalignment (UI design pass, separate PR)

### Logs to capture

For each test session, capture both phones with the standard command:

```powershell
adb -s <device-id> logcat PhantomRelay:V PhantomMessaging:V PhantomMessagingService:V PhantomUI:V PHANTOM_INIT:V AndroidRuntime:E *:S | Tee-Object -FilePath "C:\temp\<scenario>-<device>.log"
```

Send the log file paths after each scenario.

### Bonus: optional sanity checks (architect-suggested)

- `curl -v --http1.1 wss://relay.phntm.pro/ws` — confirms relay uses HTTP/1.1 for the upgrade (the ADR-010 fix relies on this).
- `adb shell dumpsys wifi | grep -i phantom` — confirms `WIFI_MODE_FULL_HIGH_PERF` is granted on HiOS.

These don't block the test but are useful data points for the next debug pass.

---

## Open questions for the founder

1. **PR ordering.** Confirmed: PR 1 → PR 2 → PR 3 → PR 4 → PR 5? Or surface a different order if any of these block product/marketing work?
2. **Kickstarter timing vs Track B.** Track B items 1–8 are the gating set. If Kickstarter has a fixed launch date, we sequence Track B accordingly; otherwise we land it after Alpha-3.
3. **Voice-message size hard cap.** PR 3 needs a number. Suggest 10 MB as the hard cap with chunking under that, but founder has the call.
4. **Design pass timing.** Onboarding keyboard, radar circles, online indicator alignment, call-controls layout — all UI work that should run in parallel via `ui-prototyper`. Do we want that in PR 5, or as a separate "Alpha-3 visual polish" PR after reliability lands?

---

## Document maintenance

- This file replaces ad-hoc planning chats. Update it whenever an audit finishes or a PR closes.
- The two audit documents (`ARCHITECTURE_AUDIT_2026_05_01.md` + `SECURITY_AUDIT_2026_05_01.md`) are the source of truth for findings; this plan summarises and prioritises them.
- ADR-010 is the source of truth for the transport reconnect fix.
- Memory file `feedback_project_log.md` rule still applies — add a session entry to `docs/PROJECT_LOG.md` when a PR closes.
