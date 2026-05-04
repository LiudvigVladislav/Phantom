# PHANTOM Execution Plan — 2026-05-01

Living roadmap for the current sprint. Ties together yesterday's strategic plan, today's audit findings (architecture + security), and the QA backlog from manual device testing.

This document is **the authoritative working plan for the reliability-sprint mechanics** (Track A + Track B detail). For the cross-track timeline that ties Reliability + Security + Grant Readiness + Alpha 2 features together, see [`MASTER_TIMELINE_2026.md`](MASTER_TIMELINE_2026.md).

When in doubt, read MASTER_TIMELINE first for the big picture, this file second for Track A/B details.

---

## Where we are right now

- **Codebase status:** Alpha-2 X3DH bootstrap shipped (PR C). Real-device QA closed on Tecno Spark Go (HiOS / Android 12) + twin Pixel 8 Pro emulators.
- **Transport sprint: COMPLETE and merged.** Final commit `12ac26c5` on master (PR #28). 21-commit transport reliability sprint covering ADR-010 (per-reconnect HttpClient + abandon-and-restart), MAC-zombie ack-deliver, AlarmManager wakeup, Tier-1 MulticastLock + ConnectivityManager.
- **Track A PR 2 (calls UX): COMPLETE and merged.** Commit `45338fb8` on master (PR #29). Closed F-03 (handleAnswer null-guard + ring timeout), F-07 (conversation lookup key), F-10 (pendingIceCandidates clear), F-15 (toggleMute naming).
- **Track A PR 2.5 (calls media): COMPLETE and merged.** Commit `c62fbfff` on master (PR #30). Closed mic permission gate, audio mode `MODE_IN_COMMUNICATION`, black screen on remote hangup, speaker toggle responsiveness.
- **Calls = experimental in Alpha.** Asymmetric audio on Tecno HiOS, occasional mid-call crashes on transport reconnect, and state-desync between participants during establishment all remain. Real fix is PR 2.6 (post-Phase-5) — see ISSUE-014 in `KNOWN_ISSUES.md`. Decision rationale below in "Decisions locked".
- **Final transport behaviour on Tecno HiOS:** 30-second connection cycle, ~700 ms recovery, no text-message loss, 1–2 s send latency in worst case. **This is the Alpha baseline** — push-based wakeup deferred to Phase 5 (UnifiedPush). Documented as ISSUE-013 in `KNOWN_ISSUES.md`.
- **Active audits on disk:** [docs/audit/ARCHITECTURE_AUDIT_2026_05_01.md](../audit/ARCHITECTURE_AUDIT_2026_05_01.md), [docs/audit/SECURITY_AUDIT_2026_05_01.md](../audit/SECURITY_AUDIT_2026_05_01.md), [docs/audit/RELAY_AUDIT_2026_05_01.md](../audit/RELAY_AUDIT_2026_05_01.md), [docs/research/TECNO_HIOS_WIFI_PARKING_RESEARCH.md](../research/TECNO_HIOS_WIFI_PARKING_RESEARCH.md). All completed 2026-05-01 / 2026-05-02.
- **Architectural decisions on disk:** ADR-001…ADR-013. Latest: [ADR-010](../adr/ADR-010-transport-reconnect-deadlock.md) → [ADR-011](../adr/ADR-011-alarm-manager-network-wakeup.md) → [ADR-013](../adr/ADR-013-revised-transport-diagnosis-2026-05-02.md).

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

The goal: close the user-visible bugs surfaced during real-device QA on top of the now-stable transport baseline. **Ordering is set by Vladislav's priority — most-visible-broken first.** Approved 2026-05-02. Estimated total: ~15 working days.

PR order: **2 → 3 → 1 → 4 → 5** (calls, voice, data integrity, durability, polish).

### Track B — Security sprint (10 items)

The goal: close all P1 security findings before any public Kickstarter / Beta announcement. F22 is new (regression from PR C — SPK/OPK private keys in plaintext SQLite) so it jumps to the top.

---

## Track A — Reliability sprint

**Execution order (revised 2026-05-02 after calls testing):** PR 2 ✅ → PR 2.5 ✅ → **PR 3 (next)** → PR 1 → PR 4 → PR 5. PR 2.6 (calls audio plumbing) deferred to post-Phase-5.

Reasoning: PRs 2 and 2.5 closed call UX/state bugs; remaining call issues (asymmetric audio, mid-call crash on reconnect) are architectural and need stable transport (Phase 5 / UnifiedPush, Feb 2027). Voice messages (PR 3) serve the same async-voice need with much higher reliability — independent of WebRTC, runs over the regular transport.

### PR 2 — Calls work end-to-end ✅ MERGED (PR #29, commit `45338fb8`)

| Finding | Where | What |
|---------|-------|------|
| F-03 | CallManager.kt | `handleAnswer` null-guards `peerConnection` → ENDED transition. 60-second ring-timeout coroutine added in `startCall`, cancelled on answer/cleanup. |
| F-07 | AppContainer.kt | `onCallMessage` conversation lookup uses sorted-concat key matching `DefaultMessagingService.deriveConversationId`. Username now correct on incoming call. |
| F-10 | CallManager.kt | `pendingIceCandidates.clear()` at the start of both `startCall` and `handleOffer`. ICE callId discriminator deferred. |
| F-15 | CallManager.kt | `toggleMute` rename for clarity. |

### PR 2.5 — Calls media plumbing ✅ MERGED (PR #30, commit `c62fbfff`)

| Bug | Where | Fix |
|-----|-------|-----|
| No mic at runtime | CallsScreen.kt + MainActivity.kt | `RECORD_AUDIO` permission gate before `startCall` (caller) and `answerCall` (callee). Reused ChatScreen pattern. Denied path → Toast on caller side, auto-reject on callee side. |
| Wrong audio routing / silent toggles | CallManager.kt + manifest | `AudioManager.MODE_IN_COMMUNICATION` set before `createPeerConnection` in `startCall`/`answerCall`, restored to `MODE_NORMAL` in `cleanupCall`. `MODIFY_AUDIO_SETTINGS` permission added to manifest. Speaker and Mute toggles now actually take effect. |
| Black screen after remote hangup | MainActivity.kt | `LaunchedEffect(call)` navigates to ChatList when `_activeCall` flips to null. Was failing because the `if (call != null)` guard had no `else`. |

### Track A — known limitations remaining after PR 2.5 (intentionally not fixed in Alpha)

Documented as ISSUE-014 in `KNOWN_ISSUES.md`. Calls are **experimental** in Alpha. Remaining issues:

- Asymmetric audio on Tecno HiOS (caller can hear callee, callee cannot hear caller — receiver side)
- Crash possible mid-call when transport reconnect cycle (ISSUE-013) fires during ICE/DTLS-SRTP setup
- State desync between participants during establishment when reconnect fires between offer and answer

Real fix path (deferred):

- **PR 2.6 (post-Phase-5):** explicit `JavaAudioDeviceModule`, `AudioFocus` request, suppress transport `forceReconnect()` while `_activeCall != null`, default-on speakerphone for testing convenience. Estimated 2–3 days when picked up.
- **Phase 5 (UnifiedPush, ~Feb 2027):** removes the architectural cause (30-s reconnect cycle on aggressive-OEM devices).

---

### PR 3 — Voice messages (NEXT, ~5 days)

| Finding | Where | What |
|---------|-------|------|
| F-05 | DefaultMessagingService.sendMessage + payload schema | Voice notes inline-base64 the entire blob into a single envelope. ~270 KB single payload cannot complete transit before the next 30 s reconnect window on Tecno. Chunk into multiple envelopes; reassemble on receiver. Pre-send size check with user-visible error if over hard cap (~10 MB). |

Acceptance: 30-second voice note delivers reliably between Tecno and emulator on the production relay, including under the 30-second reconnect cadence we accept as Alpha baseline.

---

### PR 1 — Data-integrity edges (THIRD, ~3 days)

Closes the remaining edge cases where messages can silently disappear under crash / mid-flush conditions.

| Finding | Where | What |
|---------|-------|------|
| F-08 | AppContainer.kt:255-263, 390 | `bootstrapForNewIdentity()` is fire-and-forget; UI sees `messagingService` before bootstrap completes. Await it, or gate UI send path on a `bootstrapReady` StateFlow. |
| F-01 | KtorRelayTransport.kt:514-520 | `flushPendingOutbox` silently drops envelopes on `sendRaw` failure. Re-enqueue on failure + sweep DB for `QUEUED` rows on startup. |
| F-09 | KtorRelayTransport.kt:538-547 | `disconnect()` does not flush outbox before cancelling. Add a 3-second best-effort flush. |
| F-04 | DefaultMessagingService.sendMessage + SessionManager.initiatorBootstrap | `saveSession` runs before `insertMessage`. On crash between the two, message disappears from sender's history but peer receives it. Make atomic via single SQLDelight transaction. |

Acceptance: kill the app process mid-send (`adb shell am kill phantom.android`) — on next launch the queued message either sends or is recoverable, never silently lost. Background flush window before service teardown does not drop frames.

Note: 2026-05-02 QA showed "send works first try" most attempts after APK 19 — this PR closes the remaining 1-in-10 edge case.

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

- **Transport branch merged** = stable Alpha baseline. Tecno-class devices: 30 s reconnect cycle, ~700 ms recovery, no message loss. Done.
- **PR 2 merged** = user-visible call breakage closed (no more stuck "Calling…", username shows correctly, no black-screen after end-call).
- **PR 3 merged** = voice messages deliver between any two devices including Tecno.
- **PR 1 merged** = no message ever silently disappears even under process kill mid-send.
- **PR 2 + 3 + 1 merged** = Alpha-3 release-candidate quality for text + voice + calls.
- **PR 4 merged** = durability against Keystore invalidation events (biometric reset, factory-state edge cases).
- **PR 5 merged** = visual polish + Tier-3 onboarding for aggressive-OEM users.
- **Track B items 1–8 closed** = security clearance for Kickstarter announcement. Items 9–17 can land during the campaign window.
- **NLNet application** can submit any time after Track B items 1–4 close (the first four cover the most user-visible privacy claims).

---

## What QA needs to focus on right now

**Transport sprint complete.** Branch `fix/call-serializer-and-stuck-envelope-loop` is open as a PR against `master`; merge after CI green and Vladislav's review.

After merge, QA focus shifts to PR 2 (calls). The Alpha baseline transport behaviour is the new normal — text messages deliver reliably with up to 1–2 s latency in worst case on Tecno; that is acceptable per ISSUE-013.

---

## Transport-sprint outcome (2026-05-02 close-out)

Verified on Tecno Spark Go 2023 / HiOS / Android 12 + twin Pixel 8 Pro emulators across APK 7 → APK 19.

| Behaviour | Tecno HiOS | Pixel emulators |
|---|---|---|
| Stable connection window | ~30 s | 5+ minutes |
| Recovery after Pong timeout | ~700 ms | n/a (does not happen) |
| Text message delivery | Yes, ≤2 s end-to-end | Yes, sub-second |
| QR-add → first send works | Yes (was failing before APK 17) | Yes |
| Duplicate-envelope guard | Working | Working |
| MAC-zombie ack-deliver | Working | Not exercised |
| Voice message delivery | **Fails** — 270 KB single envelope can't fit in 30 s window — fixed by PR 3 | n/a |
| Call UX | Multiple bugs (F-03, F-07, F-10, F-15) — fixed by PR 2 | same UX bugs |

**Conclusion:** transport architecture is final for Alpha. Remaining defects are above the transport layer and addressed by Track A.

---

## Decisions locked in 2026-05-02

1. **Push-based wakeup** — deferred to Phase 5 (UnifiedPush, ~Feb 2027). Documented as ISSUE-013. Tier-1 user-space mitigations attempted and verified ineffective.
2. **Voice-message hard cap** — to be set by PR 3. Default working assumption: 10 MB hard cap with chunking, ~75 KB per chunk. Vladislav can override.
3. **UI polish (radar circles, online indicator, etc.)** — bundled into PR 5, not split into a separate "Alpha-3 visual polish" PR. Use `ui-prototyper` agent inside PR 5 scope.
4. **Track B (security)** — runs in parallel with Track A after PR 2 ships. Items 1–8 are Kickstarter gating; 9–17 can land during the campaign window.
5. **NLNet application** — submittable any time after Track B items 1–4 close.
6. **Calls = experimental in Alpha; PR 2.6 deferred.** After PRs #29 and #30 closed all definitively-fixable call UX bugs above the transport layer, real-device QA on 2026-05-02 surfaced asymmetric audio on Tecno HiOS, mid-call crash risk on reconnect, and state desync between participants. Root cause is architectural (WebRTC voice over a transport that reconnects every ~30 s on aggressive-OEM devices). Each further PR on calls would have diminishing returns relative to PR 3 (voice messages, async, runs over the regular transport, ~90 % of real-world voice use case). Decision: ship Alpha with calls labelled experimental in `KNOWN_ISSUES.md` ISSUE-014, prioritise PR 3 next. PR 2.6 (`JavaAudioDeviceModule`, `AudioFocus`, suppress reconnect during active call, default-on speakerphone) parked until Phase 5 makes the underlying transport stable enough for the work to stick.

---

## Document maintenance

- This file replaces ad-hoc planning chats. Update it whenever an audit finishes or a PR closes.
- The two audit documents (`ARCHITECTURE_AUDIT_2026_05_01.md` + `SECURITY_AUDIT_2026_05_01.md`) are the source of truth for findings; this plan summarises and prioritises them.
- ADR-010 is the source of truth for the transport reconnect fix.
- Memory file `feedback_project_log.md` rule still applies — add a session entry to `docs/PROJECT_LOG.md` when a PR closes.
