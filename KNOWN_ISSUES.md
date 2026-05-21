# PHANTOM — Known Issues

**Last updated:** 2026-05-21
**Build:** master at `8f4c68c9` — post-Alpha-1, ongoing development. Alpha-1 baseline (2026-04-27, tag `v0.1.0-alpha.1`) is preserved as historical context within this document; the project has since landed the M1w encrypted-media-upload trilogy, the M2 chunk-size sprint (M2a-M2h.1), the D0r/D1 REST fallback transport, the H1/H2 first-message + reconnect reliability sprint, and the REC recording-panel UX trilogy. There is **no fixed release deadline** as of the 2026-05-14 strategic pivot — see [`docs/project/MASTER_TIMELINE_2026.md`](docs/project/MASTER_TIMELINE_2026.md) for the live tracker.
**Tested platforms:** Android (Tecno Spark Go 2023 / Android 12 HiOS — Wi-Fi only since 2026-05-14, no SIM card; Pixel emulators API 35 on Windows dev machine), Tele2 LTE Иркутская (real-device, second SIM phone pending), MTS Wi-Fi (real-device, no SIM cellular path) Hetzner VPS relay (`relay.phntm.pro`).

---

## Overview

PHANTOM is a privacy-focused E2E messenger with verified end-to-end encryption (Double Ratchet via libsodium, Sealed Sender, identity-prekey separation per ADR-009), Trust Tier message requests, multi-transport stack (direct WSS / Reality through Xray / Tor v3 onion as text-only emergency fallback) plus a REST-poll fallback for carrier-middlebox-affected networks, and store-and-forward delivery via a self-hosted relay.

This document is intentionally exhaustive. Transparency about limitations is essential for a privacy tool — users deserve to know exactly what works and what doesn't. New issues are added at the bottom of each severity section; resolved issues stay in place with their resolution annotated so the project's evolution is readable.

## Document structure

This file separates two kinds of items:

1. **Bugs and limitations** — defects against the intended behaviour, scored P1 / P2 / P3. These get fixed.
2. **Architectural choices** — intentional design decisions with documented trade-offs. These do not get "fixed"; they get re-evaluated at planned points (audit, milestone, etc).

## Severity Legend (for bugs)

- **P1 (Critical / High):** Causes user-visible problems or has security relevance. Fix before Beta.
- **P2 (Medium):** Polish issues. Acceptable in Alpha, fix in Beta.
- **P3 (Low):** Cosmetic or edge-case. Track for future iterations.

---

## Critical Security Issues (P1)

### ISSUE-001: WebSocket reconnect cycles on aggressive-OEM Android skins and stateful carrier middleboxes

**Symptom.** On certain OEM Android skins (Tecno HiOS verified) AND on multiple Russian carrier networks (МТС, Tele2 LTE — verified independently of OEM) the foreground-service notification stays visible and the WebSocket connects successfully, but the connection silently dies after some idle period (originally ~60 s on OEM-only diagnoses, later ~30–155 s under the carrier-middlebox half-open TCP pattern). Each cycle:

1. Client logs `SocketTimeoutException`, or — under the half-open pattern — no outbound `Ping` reaches the server at all (server-side `pings_received=0 inbound_frames=0` over the whole session).
2. Reconnect succeeds within ~1 s once the dead socket is detected and torn down.
3. Any envelope the peer sent during the gap is delivered immediately on reconnect (store-and-forward keeps it durable).

**Impact.** A message sent to a recipient mid-reconnect arrives ~1–3 s later than usual. **No messages are lost** (PR-H2b processed-envelope ledger neutralises any relay redelivery — see ISSUE-004). On stable Wi-Fi without a stateful middlebox the connection has been stable across multi-minute QA sessions with the same code path and the same relay endpoint. The issue compounds two effects: (a) the OEM-side power management on Tecno HiOS that parks the Wi-Fi radio between transmissions, and (b) stateful network elements on the Russian carrier path that drop a long-lived idle WebSocket without sending a FIN.

**Root cause.** The 2026-05-04 4-test matrix on the same МТС Wi-Fi (Tecno Spark Go vs Pixel 8 Pro emulator on a stable PC) demonstrated the cycle on **both** devices — so OEM radio parking is one cause, not the only one. The actual primary contributor in production on Russian carriers is a stateful network element along the path (Carrier-Grade NAT, transit border filtering / TSPU, or both) that goes one-way silent without an explicit close. PR-H1b (`0baa4196`) diagnosed this as the half-open TCP black-hole pattern from `session_summary` lines (`pings_received=2-5` server-side vs `pings_sent=11` client-side, `since_last_ping_ms ≈ 153 s` on every dying session).

**Mitigations shipped (current production policy, locked via the H1c/H1e diagnostic sprint).**

The most recent policy is the PR-H1e "Run C" configuration (PR #134, master `bcc501be`, locked 2026-05-14 after a 4-run heartbeat experiment on `diag/h1e-ws-ping-experiments`):

- **App-level RelayMessage Ping/Pong over WS frames disabled** (`APP_LEVEL_PING_ENABLED=false`). Run C empirically doubled WS lifetime by removing this redundant heartbeat layer; tighter cadences (Run B 5 s) made the cycle worse, not better. App-level Ping was an early defensive belt that turned out to be a kill trigger on stateful middleboxes.
- **OkHttp WebSocket `pingInterval(15s)` hard-coded** as the sole client-side liveness check.
- **Dead-socket watchdog** triggers off the last inbound frame timestamp via `RelayTransport.lastInboundFrameElapsedMs` — ANY inbound frame keeps the socket alive, not just Pong (PR-H1c, `e946caba`).
- **AlarmManager proactive reconnect at 45 s stale inbound** survives Doze and OEM AlarmManager throttling as a safety net below the 60 s pong-watchdog floor (ADR-011, now Accepted; PR-H1c).
- **Distinct `TransportState.Reconnecting`** drives a "Reconnecting…" UX badge instead of the chat going dark.
- **Server-side TCP `SO_KEEPALIVE`** (idle 15 s, interval 5 s, retries 3) via `socket2` `#[cfg(unix)]`, so the relay can drop half-open sockets server-side without waiting for the next read attempt.
- Foreground service holds `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` (downgraded to `FULL` by HiOS but kept anyway as defence-in-depth) and a partial `WakeLock` for its full lifetime (`74e6af0a`).
- `forceCancelAllEngineCalls()` so a hung WebSocket aborts in <2 s instead of 60 s (`2ee1d08d`).
- `cancelAndJoin` between reconnect generations so stale ping-timers cannot fire on the new socket (`846d6bed`).

**Test #37 verified** detection time dropped from 155 s → 30–46 s, recovery 5 s → ~1 s, zero message loss across twelve consecutive reconnect cycles per device. **Test #41** (the locked H1e Run C policy on Tecno МТС + emulator-on-dev-Wi-Fi) confirmed zero MAC errors / ledger-dedup misses / FIFO flush issues across 12 reconnects per device.

**Remaining residual on Tele2 LTE: WS Frame.Text silently dropped upstream** — see [ISSUE-018](#issue-018-tele2-lte-ws-frametext-silently-dropped-upstream) for the half of this story that the H1 sprint did not close. The REST fallback transport (PR-D0r / PR-D1 / PR-D1b / PR-D1c / PR-D1c.1 / PR-D1d) was built to address the Tele2-class case where even a healthy underlying TCP can no longer carry application frames.

**Workaround for Tecno HiOS users** (in case the device-side radio parking dominates on a less-affected network):

1. *Settings → Apps → PHANTOM → Battery* → Unrestricted
2. *Settings → Apps → Special access → Battery optimization* → PHANTOM → Don't optimize
3. *Settings → Battery → Battery saver* → off during use
4. If "Power Marathon" / "Smart Power" / "Phone Master" exists in the OEM apps, add PHANTOM to the whitelist

**Long-term direction (no fixed deadline; tracked in `docs/PROJECT_LOG.md → Open follow-ups`).** The push-on-disconnect approach earlier docs pointed at (Unified Push / FCM hybrids) was retired during the 2026-05-14 strategic pivot — both UnifiedPush and FCM are out of scope because the metadata-leak posture documented in [ADR-001](docs/adr/ADR-001-System-Boundaries.md) and [Threat Model v0](docs/threat-model/Threat_Model_v0.md) makes any always-on third-party push channel an architectural non-starter. The current answer is the H1c/H1e mitigations above plus the REST fallback. If a future approach is identified, it will land as its own ADR.

---

### ISSUE-002: Init sequence requires app restart after first registration  ✅ RESOLVED

**Resolved by commit `5caf61eb` (fix(android): WebSocket connect after onboarding).**

After identity creation in onboarding, `MainActivity.PhantomApp` now re-triggers `startForegroundService(...)` from the `OnboardingScreen.onComplete` callback so `PhantomMessagingService.onStartCommand` re-runs with the freshly-created identity available, calls `service.startReceiving()`, and opens the WebSocket without an app restart.

---

### ISSUE-003: "Offline — messages queued" badge shows when no items are queued  ✅ RESOLVED

**Resolved by commit `5caf61eb` (fix(android): suppress false offline badge).**

`ConnectionBanner` now gates the "Offline" line on a `hasEverConnected` flag set the first time `TransportState` transitions to `Connecting` or `Connected`. A real disconnect-after-connect still surfaces the banner; the cold-start "I have never tried" case stays silent.

---

## High Severity (P2)

### ISSUE-004: First envelope after reconnect+flush occasionally fails MAC verification  ✅ RESOLVED

**Resolved by PR-H2b (#129, master `7008cf3e`, 2026-05-13).**

**Original symptom:** When a recipient reconnects and the relay flushes multiple queued envelopes in quick succession, the first envelope sometimes fails decryption with "MAC validation failed". Subsequent envelopes in the same batch decrypt successfully.

**Root cause (confirmed during the H1/H2 diagnostic sprint).** Relay redelivery on a reconnect could re-issue the same envelope after the receiver had already advanced its ratchet chain — the second decrypt attempt would then operate on a chain key that no longer matched. The original "out-of-order delivery + skipped-key window" hypothesis was partly right: it also covered out-of-order MAC misses, but the dominant reproducer in the final round of testing was relay redelivery, not pure out-of-order arrival.

**Fix shipped.** PR-H2b introduces a new `processed_envelopes` SQLDelight table (forward-only `15.sqm` migration, schema v15 → v16) that records every successfully-decrypted envelope id regardless of payload type (text, voice manifest, group control, read receipt). The ledger guard runs **before** `ratchet.decrypt`, so a re-delivered envelope is `Duplicate envelope (already in ledger)` rather than a MAC fail. `INSERT OR IGNORE` makes the guard race-safe under parallel decrypt; the ledger has an 8-day TTL (one day longer than relay store) so it cannot mask a legitimate replay-attack window. Read receipts and other control payloads that never inserted into `messages.id` (and so bypassed the legacy `messages.id` guard) are now also covered. The legacy `messages.id` guard is retained as defence-in-depth.

**Test #34 / #37 verified** the fix end-to-end: 12 messages each direction across 12 reconnect cycles per device on Tecno МТС + emulator, zero MAC errors, no duplicate UI rows.

**Related sprint work:** PR-H2a (`674ce231`) strict FIFO outbox via per-envelope `sequenceTs` + `outboundSendMutex`; PR-H2a.2 (`72e59ce9`) holds the mutex across the entire flush-Send loop so live-send cannot race past a higher `sequenceTs`. The complete trilogy closes the client-side reorder source (H2a/H2a.2) **and** the relay-redelivery class (H2b).

**Still on the queue.** PR-H2c (skipped-message-keys per Signal spec) would close the *third* leg — legitimate future-message reorder from TCP retransmit pauses or Tor circuit shifts. Without H2c, a legitimately reordered frame between sender wire and receiver decrypt would still MAC-fail; the H2b ledger then prevents repeat-failure on redelivery, but the original message is lost. Tracked in `docs/PROJECT_LOG.md → Open follow-ups` at low priority — no production reproducer of legitimate reorder has surfaced since H2b landed.

---

### ISSUE-005: ToS / onboarding screen accepts username but does not visually confirm registration completion

**Symptom:** During registration, after entering username and proceeding, there is no clear "Account created" confirmation. User is dropped onto the main Chats screen which shows the misleading "Offline — messages queued" banner.

**Impact:** Combined with ISSUE-002 and ISSUE-003, the post-registration experience is unclear.

**Planned fix (Alpha 2):**
- Show a brief "Welcome, @username!" confirmation toast/screen
- Display "Connecting..." state with progress indicator
- Transition to home screen only after WebSocket connects

---

### ISSUE-006: No retry feedback when a message fails to send  ⚠️ PARTIALLY ADDRESSED

**Original symptom:** When the WebSocket is disconnected and the user sends a message, the message appears in the chat with no visual indication that it is pending. There is no "Sending..." spinner or pending icon. The message just sits there until reconnect.

**Status (2026-05-21).** Voice notes ARE covered:
- **Upload progress UI shipped by PR-M2d.1b** (master `f804e0d8`): `AudioBubble` renders `Uploading N/M` (sender) and `Downloading N/M` (receiver), keyed off an in-memory `MediaProgressBus` `StateFlow<Map<rowId, Progress>>` so the UI ticks per chunk in real time.
- **Cancellable upload shipped by PR-MEDIA-UPLOAD-CANCEL2.1** (PR #198 part 3, master `b117dcb9`): the X glyph on the uploading bubble now actually stops the upload via `MessagingService.cancelVoiceUpload(conversationId, localMsgId)`, propagates `CancellationException` cleanly through the upload coroutine, tears down the local row, and the bubble disappears from the LazyColumn. End-to-end log chain: `MEDIA_UI upload_cancel_tap` → `MEDIA_TX upload_cancel_requested` → `upload_cancel_dispatched` → `upload_cancelled_by_user manifestSent=false` → `upload_cancel_joined`.
- **Sender row flips UPLOADING → SENT** as soon as the M2e early manifest envelope leaves the device, while the upload tail continues in background and the bubble counter keeps ticking — the user gets the "it's leaving" signal without waiting for the full upload.

**Still open for text messages:**
- The pending icon (clock) / single check / double check pattern in the original symptom has not been implemented yet for text messages. Text bubbles still appear immediately with no per-message status indicator.
- Tracked in `docs/PROJECT_LOG.md → Open follow-ups` under "UI polish — per-message status icons", queued behind the doc-honesty pass and the durationMs / empty-voice follow-ups.

---

### ISSUE-007: Nearby radar UI not yet implemented in app

**Symptom:** The architectural design includes a "Nearby" mode for offline mesh communication via Bluetooth and Wi-Fi Direct, but the UI for discovery and pairing is not yet built into the Android app.

**Impact:** Feature is not available to users in Alpha 1.

**Planned for:** Alpha 2 / Beta. Requires:
- Bluetooth scanner integration
- Wi-Fi Direct discovery
- mDNS local network discovery
- Mesh routing logic
- Discovery and pairing UI

---

## Low Severity (P3)

### ISSUE-008: Debug build URL hardcoded to local development IP (now fixed)

**Symptom (resolved):** Debug APK was hardcoded to `ws://192.168.0.105:8080/ws` (developer's local Wi-Fi IP), causing connection failures with `CLEARTEXT not permitted` on any other machine.

**Status:** Fixed in commit `69e87ad8` — debug build now defaults to `wss://relay.phntm.pro/ws`.

---

### ISSUE-009: No iOS build yet

**Status:** Planned. KMP + Compose Multiplatform stack supports iOS, but the iOS app target has not been built or tested in Alpha 1. Android-first launch.

**Planned for:** Beta release.

---

### ISSUE-010: No web client yet

**Status:** TypeScript web prototype exists in repository but is not wired to the production relay. Web client not part of Alpha 1.

**Planned for:** Beta release. Compose Multiplatform supports web target via WASM.

---

## Architectural Choices

This section documents intentional design decisions with known trade-offs. These are not bugs; they are choices made with explicit trigger conditions for re-evaluation. Users, contributors, and reviewers should know what we chose and why.

### ISSUE-011: Cryptography library status — self-rolled Signal protocol over libsodium

**Status:** Documented architectural choice, hardening in progress.

**Background.** PHANTOM Alpha 2 implements the Signal protocol (X3DH handshake + Double Ratchet) on top of libsodium primitives, rather than using Signal Foundation's `libsignal-client` directly. The original ADR-006 (2026-04-15) accepted libsignal-client; the implementation shipped a libsodium-based equivalent. ADR-006 has been revised (2026-04-29) to reconcile this — see `docs/adr/ADR-006-Crypto-Library-Decision.md`.

**Current limitations** (all tracked for Phase 1 closure or as known P3 items):

| ID  | Limitation                                                                            | Plan                                |
|-----|---------------------------------------------------------------------------------------|-------------------------------------|
| F12 | X3DH handshake exists but is bypassed; SessionManager calls only raw shared-secret    | ✅ Fixed — Phase 1 Week 4 PR C       |
| F15 | Initial ratchet bootstrap reuses identity DH key as ratchet seed                      | ✅ Fixed — Phase 1 Week 4 PR C       |
| F3  | SenderKey KDF uses raw SHA-256 rather than HKDF                                       | Phase 1 Week 5–6                    |
| F13 | SenderKey signing keys generated but never used                                       | Phase 1 Week 4 (ADR-017: remove)    |
| —   | No header encryption in Double Ratchet                                                | P3, post-Beta                       |
| —   | Limited skipped-message-key cache                                                     | P2, Alpha 2 expansion (see ISSUE-004) |
| —   | No one-time prekeys (OPKs)                                                            | ✅ Fixed — Phase 1 Week 4 PRs A/B/C  |

**Why not libsignal-client immediately?** Implementation simplicity (zero JNI bridging, smaller dependency surface, AGPL-3.0 + "external use unsupported" friction). Re-evaluation trigger: post-Phase 6 audit response.

**Audit transparency.** All findings above were identified by internal security review on 2026-04-29 and are tracked publicly in this issue, in `docs/adr/ADR-006-Crypto-Library-Decision.md` (Decision (revised) section), and in `docs/adr/ADR-009-identity-prekey-separation.md` (Phase 1, in draft).

---

### ISSUE-012: Relay PreKeyStore — in-memory + JSONL persistence

**Status:** Documented architectural choice, sufficient for Alpha 2 — Beta 1.

**Background:** PreKey storage on relay uses `RwLock<HashMap>` + JSONL append (consistent with existing patterns for envelopes, reports, blocklist). SQL backend deferred — see [ADR-021](docs/adr/ADR-021-relay-prekey-sql-migration.md) (Reserved — content pending) for the SQL migration slot.

For Alpha 2 single-relay Helsinki deployment: in-memory state with JSONL recovery + automatic client re-publish flow handles all expected operational scenarios (relay restart, deploy update, brief outages). Replay is best-effort and idempotent: on startup the relay reads `prekeys.jsonl` line-by-line; the most recent line per identity wins, which is consistent with the publish endpoint's "replace OPK pool wholesale" semantics. A relay restart that drops the file entirely is recoverable — clients re-publish on next online session via the existing background lifecycle task.

**Trigger conditions for ADR-021 (SQL migration):**
- Single-relay identity count crosses ~10 000 (HashMap + JSONL becomes memory-pressure-relevant on the 4 GB VPS that currently hosts the relay)
- JSONL replay-on-restart becomes user-visible during operator-driven redeploys (current baseline not measured; revisit when the first complaint arrives or the first measured restart crosses ~5 s)
- Multi-relay federation lands on the roadmap (HashMap cannot be shared across relay instances)

**Why this isn't a P3 bug:** the Signal protocol's prekey contract makes the storage replaceable — clients tolerate (and recover from) a server that "forgot" their prekeys by re-publishing. Loss of prekey state degrades to a 3-DH handshake (no OPK round) for at most one window, then fully restores after replenish. This is by design.

---

### ISSUE-013: Stateful NAT/CGN/TSPU silent drops on cellular Russia — multi-transport stack live, original hybrid plan superseded

**Status:** Diagnosis revised 2026-05-04 (ADR-013 was wrong about firmware-radio-parking). The original "Tor + UnifiedPush hybrid" architecture proposed in `feat/tor-unified-push-transport` / ADR-016 / ADR-018 was **superseded by the 2026-05-14 strategic pivot and the 2026-05-15 follow-up**: UnifiedPush was retired (always-on third-party push channel was deemed an unrecoverable metadata leak), and Tor was demoted to a text-only emergency fallback after Test #42 on Tele2 LTE showed Reality probe failures dropping straight through to Tor. The current production stack now has three transports — direct WSS (`Standard`), Reality (`Private`, the load-bearing path on RU mobile per the pivot), and Tor (`Ghost`, text-only) — plus a REST-poll fallback (PR-D0r / PR-D1*) when WS Frame.Text is being dropped by the carrier middlebox. The "silent drops on cellular Russia" symptom no longer has a single fix; it is addressed in layers and tracked via the M1w / M2 / D0r / D1 sprints rather than a single hybrid PR.

**Revised root cause.** The 4-test matrix on 2026-05-04 (Tecno Spark Go + Pixel 8 Pro emulator on the same МТС WiFi, identical network path) demonstrated that the "WebSocket dropped silently every 50-60 s" symptom appears on the **Pixel emulator running on a stable Windows PC** as well — invalidating the Tecno-firmware-radio-parking explanation. Both devices stay perfectly stable behind any VPN. The actual root cause is a **stateful network element along the path between Russian carrier WiFi and Hetinki relay**: Carrier-Grade NAT, transit border filtering (TSPU), or both. VPN tunnels emit their own keepalives at 10-25 s and refresh the NAT entry; bare WSS without TCP keepalive does not.

**Earlier (now-superseded) diagnoses.** Original investigation in ADR-010/ADR-011/ADR-013 attributed the cycle to Tecno HiOS firmware Wi-Fi radio parking. That hypothesis explained the Tecno cycle but cannot explain the matching cycle on a Pixel emulator on a stable PC. The 2026-05-04 4-test matrix (`docs/research/transport-investigation-2026-05-04/`) refuted firmware-radio as root cause. ADR-013 should be read as historical record only.

**Mitigations from earlier diagnosis remain useful as defence in depth.** Foreground service WifiLock, WakeLock, MulticastLock, AlarmManager-driven force-reconnect (ADR-011), and generation-based OkHttp engine disposal (ADR-010 updated 2026-05-01) all stay in place. They reduce reconnect latency from 60 s to 1-3 s once the upstream NAT does drop the connection.

**Layer 2 fix (ADR-014, deployed 2026-05-04):**

`fix/transport-tcp-keepalive` branch enabled `SO_KEEPALIVE` with explicit `TCP_KEEPIDLE=30s / TCP_KEEPINTVL=10s / TCP_KEEPCNT=3` (60-second dead-detection window) on:

- Android client — custom `KeepAliveSocketFactory` wraps the OkHttp `Socket` and applies `setsockopt` immediately after `connect()`.
- Rust relay — `axum::serve::Listener` wrapper applies `socket2::TcpKeepalive` to each accepted stream.
- Caddy and relay containers — namespaced kernel sysctls in `deploy/docker-compose.yml`.

Test 5 (МТС WiFi, both devices): connection lifetime improved 50 s → 120 s, but voice burst delivery still fails because the connection silently goes one-way during the burst. Layer 2 is **partially effective but not sufficient on its own** for cellular Russia. Kept as defence-in-depth on the direct WSS path; Tor mode will be the primary path for users on restrictive networks.

**Current transport stack (post-2026-05-15 pivot):**

- **`Standard` — direct WSS.** Works on every tested network when the carrier path is healthy.
- **`Private` — Reality / VLESS+REALITY through Xray.** The load-bearing transport on Russian mobile carriers (Tele2 / МТС). Auto-skipped when a system VPN is active (see ISSUE-015). When Reality probe fails, the chain falls through to Tor for text.
- **`Ghost` — Tor v3 onion service** via `kmp-tor 2.6.0` (Tor 0.4.9.5, all four Android ABIs incl. ARM32). Demoted to text-only emergency fallback after Test #42 (2026-05-15) — cannot carry WebRTC for calls (TCP-only) and cannot carry voice messages reliably on RU LTE.
- **REST fallback (PR-D0r / PR-D1 series).** When Tele2-class middlebox is silently dropping WS Frame.Text (the "Layer A" diagnosis 2026-05-16, see ISSUE-001 / Tele2 diagnostic notes in `docs/PROJECT_LOG.md`), the orchestrator flips the active transport to REST short-poll with server-side idempotency. PR-M1w then carries voice via the encrypted media-upload path on top of REST.

Calls in restrictive mobile networks remain **unproven** — the Reality+WebRTC combination has not had a production-quality test yet (tracked separately as PR-C2 / PR-C3 in the calls track).

**Implementation references** (multi-PR, ongoing):
- ADR-019 — Xray VLESS+REALITY as outer transport (production-validated Stage 5E, 2026-05-07).
- ADR-018 — Threat Model v0.1 revision (Tor-mode limitations, scope decisions).
- `docs/PROJECT_LOG.md` — Tele2 diagnostic, M1w voice path, M2 trilogy, D0r / D1 REST fallback, D2a voice-on-Limited-realtime gating.

The `feat/tor-unified-push-transport` branch retained as historical research artefact only — its UnifiedPush content is not on the roadmap.

---

### ISSUE-014: Calls — experimental feature, unproven on Russian mobile carriers

**Status (refreshed 2026-05-21 post-pivot).** WebRTC voice calls remain **experimental**. Core text messaging is production-quality across the Standard / Private / REST-fallback stack; voice messages are production-quality on the encrypted media-upload path (M1w → M2). Calls are an entirely separate transport problem — they cannot ride the REST fallback (no realtime UDP through short-poll) and have not been validated on a Russian mobile carrier since the 2026-05-15 strategic pivot demoted Tor to text-only.

**Strategic pivot context (2026-05-14 / 2026-05-15).** The earlier "Tor + UnifiedPush hybrid" answer (ADR-016) was retired and Tor was demoted to a text-only emergency fallback after Test #42 on Tele2 LTE showed Reality probe failures dropping straight through to Tor. Tor cannot carry WebRTC (no UDP through onion, latency too high, bandwidth insufficient). Reality is now the load-bearing mobile transport for both voice notes and the call path — but call-over-Reality has not had a production-quality test yet on RU LTE. The C-track (PR-C1 capabilities / PR-C2 Reality endpoint pool + realistic probe / PR-C3 TURN-over-TLS or Opus-over-Reality) is the queued architectural answer.

**What works (verified 2026-05-02 on Tecno Spark Go ↔ Pixel 8 Pro emulator):**

- Outgoing and incoming call signalling (offer / answer / ICE / reject / hangup)
- Username displayed correctly on incoming call (F-07 fix)
- Sequential calls do not carry stale ICE between sessions (F-10 fix)
- 60-second ring timeout on unanswered outgoing calls (F-03 fix)
- Mic permission requested at call start (caller) and call answer (callee)
- `AudioManager.MODE_IN_COMMUNICATION` set during the call, restored on cleanup
- Mute and Speaker buttons toggle and reflect state in UI
- Black screen after `cleanupCall` no longer occurs — the route navigates back to chat list when the call state goes null (PR #30)

**Known limitations on Tecno HiOS — not fixed in Alpha:**

1. **Asymmetric audio.** Phone caller's mic → emulator callee's speaker works (callee hears caller). Emulator callee's mic → phone caller's speaker is silent (caller does not hear callee), regardless of speakerphone toggle. Likely cause: HiOS-specific audio focus or default `AudioDeviceModule` initialization not coping with this routing. Not investigated to root cause; deferred to PR 2.6 post-Alpha.
2. **Crash possible mid-call.** If the 30-second transport reconnect cycle (ISSUE-013) fires while a WebRTC session is establishing or in progress, the app may crash with a native WebRTC fault and auto-restart via the foreground-service contract. Reproduces ~1 in 5 sustained calls on Tecno; not observed on stock-Android emulator.
3. **State desync between participants during establishment.** When transport reconnect fires between `call_offer` and `call_answer`, one side may show "in call / counting timer" while the other still shows "calling…" until ICE catches up. Self-resolves when the next signalling envelope arrives, typically within 1–3 s.
4. **Speaker / earpiece routing varies.** On the phone, default routing is the earpiece (small speaker near the top, intended for holding to ear). Speaker toggle works but is a separate user action. On emulator there is no earpiece concept; default routes to the host audio device.

**Root cause is architectural, not a localised bug.** WebRTC voice calls expect a stable persistent network session. PHANTOM's transport on aggressive-OEM Android cycles through reconnects every ~30 seconds (ISSUE-013). Each reconnect can disrupt ICE, DTLS-SRTP setup, or the foreground service hosting the WebRTC native code. The original "fix this via push-based wakeup" plan was retired during the 2026-05-14 pivot (no UnifiedPush, no FCM). The current path forward for calls on restrictive networks is the dedicated calls track (PR-C2 Reality endpoint pool + realistic probe, PR-C3 TURN-over-TLS or Opus-over-Reality) — separate work, not bundled with the messaging-transport stack.

**Recommendation for Alpha users:**

- Use **text** and (when PR 3 lands) **voice messages** for important communication. These deliver reliably under the current transport.
- Calls are best-effort — work well between two stock-Android devices on Wi-Fi, less reliable when one side is an aggressive-OEM phone.

**Real fix path:**

- **PR 2.6 (post-pivot, no fixed date):** explicit `JavaAudioDeviceModule`, `AudioFocus` request, suppress transport `forceReconnect()` while a call is active, default-on speakerphone for testing. Estimated 2-3 days when picked up. Originally tagged "deferred to post-Phase-5"; Phase 5 was dissolved during the 2026-05-14 pivot so this is now simply queued behind the current voice-recorder UX track.
- **Calls track (PR-C2 / PR-C3):** Reality endpoint pool with a realistic probe (the current `/health` probe does not catch Tele2's silent WS-drop pattern), then a transport that can carry WebRTC on restrictive networks — candidates include TURN-over-TLS on port 443 or a custom Opus-over-Reality envelope. This is the architectural answer that replaces the retired push-based-wakeup plan.

**Scope decision rationale.** PRs #29 and #30 closed the user-visible call-UX bugs that were definitively fixable above the transport layer. Further iteration would require Tecno-specific WebRTC ADM debugging with diminishing returns. The development sprint priority shifted to PR 3 (voice messages over regular transport) which serves the same async-voice need at much higher reliability and is independent of WebRTC.

---

### ISSUE-015: Reality (VLESS+REALITY) does not work over a system-wide VPN — Reality is skipped automatically when a VPN is active

**Status:** Documented limitation. Mitigated in client by skipping Reality from the transport chain whenever the system reports an active VPN, so the user does not pay a 20 s per-mode connect penalty for a guaranteed-failing probe.

**Symptom.** With any system-wide Android VPN active (tested with one commercial provider on МТС Wi-Fi 2026-05-11), the Reality probe (`GET https://relay.phntm.pro/health` over the local SOCKS listener that Xray exposes) times out at the full 20 s budget with `InterruptedIOException: timeout`. Without the VPN, the same probe succeeds in ~0.6 s on the same network and the same Xray configuration.

**Server-side audit (2026-05-11).** Caddy access logs (`docker logs phantom-caddy --since 12h`) show **zero requests** in the probe window for the test from the device's VPN exit IP. The relay's `phantom-relay` container correspondingly logs no `connect` event for that identity. The packets do not reach the relay's edge.

**Root cause.** Below the application layer; cannot be fixed in PHANTOM. Three plausible mechanisms (only one needs to apply for the symptom to occur):

- The VPN provider's egress applies DPI / classifier rules that drop the REALITY-mirrored TLS handshake (REALITY's TLS fingerprint is designed to look like a legitimate site visit, but heuristics on long-lived single-host TLS streams from a residential IP can still flag it).
- Path-MTU on the VPN tunnel fragments the REALITY ClientHello / ServerHello, breaking the ECH-like state machine that REALITY relies on.
- Hetzner's ingress filtering applies an IDS / abuse-list entry to the VPN provider's exit-IP range and silently drops the SYN before our edge sees it.

We did not narrow it further because the user-visible result is the same in all three cases — the probe never completes — and the fix on our side is identical: do not waste 20 s per connect attempt on a transport that the network upstream has already decided to refuse.

**Mitigation shipped (2026-05-11).** `TransportManager` calls a small Android-side `vpnDetector` (`ConnectivityManager.NetworkCapabilities` checking `NET_CAPABILITY_NOT_VPN` / `TRANSPORT_VPN`). When the detector reports `vpnActive=true`, Reality is filtered out of the walked chain at the start of every `connect()`:

| Privacy mode | Without VPN                  | With VPN active            |
|--------------|------------------------------|----------------------------|
| Standard     | `[Direct, Reality, Tor]`     | `[Direct, Tor]`            |
| Private      | `[Reality, Tor]`             | `[Tor]`                    |
| Ghost        | `[Tor]`                      | `[Tor]` (unchanged)        |

The non-VPN path is unchanged — Reality remains the privacy-preferred default for users who have no VPN running. Logs surface the decision as `vpnActive=true realityFiltered=true` next to the `ordered=...` line, so future test logs make the chain choice obvious without re-reading the source.

**User-visible effect.** With a VPN on:
- `Standard` connects in ~2 s via Direct (unchanged).
- `Private` connects via Tor — bootstrap time depends on bridge availability (~2 min on the audited VPN+МТС Wi-Fi path; without VPN this is much slower on МТС, see ISSUE-013 / Tor bridge work).
- `Ghost` is unchanged.

**Why we do not try Reality as a "last resort" under VPN.** With a 20 s per-attempt budget and a server-side audit showing the packets do not arrive, Reality under VPN is a guaranteed 20 s wasted per fallback walk. Adding it as a tail of the chain would make every fallback to Tor strictly worse without ever rescuing a real user.

---

### ISSUE-016: Tor on RU carrier networks without VPN — RESOLVED via PR-E Briar bridge import (Test #6, 2026-05-11)

**Status: ✅ RESOLVED (single-test confirmation, awaiting production-stability re-verification).** PR-E (2026-05-12) imported Briar's `bridges-s-ru` snowflake set including the Google-AMP-cache fallback fronted on `www.google.com`. Test #6 (2026-05-11 21:25-21:32 МТС Wi-Fi without VPN, Tecno Spark Go) reached `Online via Tor · Ghost` in ~6 minutes on the very first KitchenSink (1/4) attempt. PR-D's bridge rotation order + the Ghost-mode AllFailed copy ("Tor is blocked or slowed by this network. Try Private/Reality or enable a VPN.") remain in place as the fallback if a future TSPU adaptation breaks the AMP-cache route.

**Test #6 outcome timeline (МТС Wi-Fi, no VPN, 2026-05-11):**

| Time | State |
|---|---|
| 21:25:05 | Tor start, KitchenSink (17 bridges in single `enableBridges()`) |
| 21:25:06 | Bootstrap 0 % → 30 % (1 second) |
| 21:25:15 | Bootstrap 50 % |
| 21:30:28 | Bootstrap 51 % (after ~5-min stall while building guard circuits) |
| 21:30:51 | Bootstrap 95 % |
| 21:31:00 | Bootstrap 100 %, `Ready socksPort=39050` |
| 21:31:15 | Probe 200 OK over `relay.phntm.pro/health` through onion |
| 21:31:15 | Notification: `Online via Tor · Ghost` |
| 21:32:00 | WebSocket connected through `zmdrxlrkd7iv...onion:80/ws` |

**Compare to pre-PR-E state.** PR-D + the older snowflake bridges (Netlify-hosted, fronted on `vuejs.org`) timed out at 30 % after 12 minutes on the same network in Test #5 the previous day. The new bridge pool reached 100 % in half that time. The Google-AMP-cache snowflake entries from `bridges-s-ru` are the most likely cause — TSPU cannot block `www.google.com` without breaking the local internet, so the broker-discovery TLS request gets through where the older `vuejs.org`-fronted broker was silently dropped.

**Caveat:** single test on a single device on a single МТС session. Worth a few more МТС sessions across different times of day before claiming production stability. The architecture-side question ("can PHANTOM offer Ghost without VPN to RU users at all?") is answered yes; the operational question ("how often does it work in practice?") needs more data.

**Original problem (kept for context).**

**Symptom.** Selecting Ghost privacy mode on МТС Wi-Fi without a VPN: every one of the four bridge profiles in [`BRIDGE_ROTATION_ORDER`] times out without reaching `Ready`. Test #5 (2026-05-11, captured logcat at 11:22-11:34 МТС Wi-Fi):

| Profile | Reached % | Result |
|---|---|---|
| obfs4 (FlokiNET) | 10% | timeout 180 s — TSPU blocks the bridge handshake |
| webtunnel (FlokiNET + Hetzner) | 10% | timeout 120 s — same wire-pattern blocking |
| snowflake (Tor Project defaults) | 50% | timeout 180 s — broker fronting works, circuit build fails |
| mixed (all five bridges in parallel) | 72% | timeout 240 s — best result; tor selects whichever bridge wins handshake first, but the post-bridge circuit traffic still gets throttled |

`AllFailed (chain exhausted, 4 profiles tried)` after 12 min total walk.

**Root cause.** Upstream censorship layer (TSPU) on Russian carrier networks blocks Tor bridge wire signatures and throttles Tor circuit traffic even when an individual bridge handshake succeeds. The 50%/72% stall on snowflake/mixed is the classic "circuit build" or "loading consensus document" phase — the bridge negotiated a connection but the underlying Tor protocol traffic between guard and middle relays is being filtered. This matches the externally documented behaviour of TSPU against Tor (see e.g. Tor Project's "Russia" bridge guidance, which itself recommends VPN+Tor for users in cellular RU). It is below the application layer and cannot be fixed in PHANTOM code without either deploying additional bridges in non-blocked CIDR ranges or adding alternative pluggable transports.

**What works on МТС without VPN (verified Test #5 + earlier tests):**
- ✅ Standard (Direct WSS) — connects in ~1.3 s.
- ✅ Private (Reality through our Hetzner Xray endpoint) — connects in ~0.6 s on a healthy day.

**What does not work on МТС without VPN:**
- 🔴 Ghost (Tor) — fails in all four bridge profiles. The user-facing Ghost AllFailed notification is now `"Tor is blocked or slowed by this network. Try Private/Reality or enable a VPN."` so the next step is unambiguous.

**What works with a VPN active:**
- ✅ Ghost — Tor bootstraps via mixed/snowflake/webtunnel profile within 2-7 minutes (Tests #2 + Day-of-PR-C visual smoke).

**Mitigation shipped (PR-D, master 2026-05-12):**

1. `BRIDGE_ROTATION_ORDER` reordered with `Mixed` first (600 s budget) — empirically the best-performing profile per Test #5, and it is the entry point that gives tor a chance to select whichever bridge in our pool the network does not block. `SnowflakeOnly` follows with a 360 s budget; the two stable-stuck single-PT profiles get short 90 s budgets so the rotation moves on quickly.
2. Worst-case rotation walk = 19 minutes. On uncensored networks the first profile reaches `Ready` in 1-3 minutes and the rotation ends.
3. Ghost-mode AllFailed copy gives the user a concrete next step (Private/Reality or VPN) instead of a dead-end "Cannot reach relay".

**Why we did not deploy more bridges first.** Adding new bridges (e.g. a snowflake server on a non-blocked CIDR, or obfs4 / webtunnel on a different ASN) is operations work that requires a separate sprint of provisioning, key generation, fingerprint distribution, and APK release coordination. The PR-D mitigation buys us the most-likely-to-work behaviour with code-only changes, while the operations work is tracked as a follow-up post-Alpha-2.

**Cross-reference.** Original transport-investigation work that motivated the Tor track is in [ISSUE-013](#issue-013-stateful-natcgntspu-silent-drops-on-cellular-russia--tor--unifiedpush-hybrid-in-implementation). ISSUE-013 describes the WSS-side TSPU behaviour; ISSUE-016 documents that even the Tor-side fallback we built to get around ISSUE-013 also runs into TSPU at the bridge / circuit layer when used without a VPN on the same network class.

**PR-E (2026-05-12) — bridge data import from Briar onionwrapper.** The single retune above did not move the per-bridge percent stalls; the underlying censorship layer is content-agnostic to which specific bridge IP we ship as long as the bridge's *wire signature* is recognised. PR-E imports the same bridge resource files Briar ships to every Briar user in censorship-active countries (`bridges-s-ru`, `bridges-n-zz`, `bridges-m-zz` from `briar/onionwrapper @ master`, GPL-3 → AGPL-3 compatible) — 4 RU-tuned snowflake entries, 9 non-default obfs4 entries, 1 meek_lite entry. Net pool grows from 5 bridges to ~17, and a new `KitchenSink` profile is placed first in rotation so tor's own path-selection logic gets the entire pool in one `enableBridges()` call (Briar's empirical winning strategy).

**Privacy properties of the Snowflake broker fronting (PR-E).** Two of the four imported `bridges-s-ru` entries route their *broker-discovery* request through Google's AMP cache (`https://cdn.ampproject.org/`) fronted on `www.google.com`. This needs to be honest:

- *What Google sees.* Your IP making TLS connections to a Google CDN endpoint, with `www.google.com` in the SNI field. A frequency / size pattern of these requests can in principle be classified as "this client uses Snowflake-style broker discovery" (this is not PHANTOM-specific — Tor Browser users with Snowflake on RU send the same pattern).
- *What Google does NOT see.* Your PHANTOM identity, your Ed25519 signing key, the relay's onion address (`zmdrxlrkd7iv...`), your contacts, or any message content. Once the broker matches you to a volunteer browser proxy, the actual Tor circuit traffic flows over WebRTC DataChannel directly between your device and that volunteer — Google is not on that path.
- *What TSPU sees.* Only the TLS connection to `www.google.com` — indistinguishable from any of the dozens of legitimate Google services Russian carriers route every minute. Blocking `www.google.com` would have catastrophic consequences for the local internet, which is precisely why this fronting domain is resilient.
- *What we relied on before PR-E.* The previous default Snowflake set fronted on `vuejs.org` via Netlify CDN — Netlify saw exactly the same pattern Google now sees. The privacy property is unchanged in kind, only the CDN identity changes; Google's value here is resilience against censorship, not a new privacy compromise.

The other two `bridges-s-ru` entries front on `cdn.zk.mk, img.icons8.com, cdn.kde.org` via cdn77 — no Google involvement. Tor walks the bridge list internally and uses whichever the network admits first, so on a network that does not block cdn77 the request never reaches the AMP path. The AMP entries are the *resilience fallback*, not the primary path.

This pattern (Snowflake broker fronting via Google AMP cache) is the same one Tor Browser ships by default for RU users in `tor-browser-build` and that Briar ships in `bridges-s-ru`. PHANTOM follows the established censorship-circumvention industry practice rather than inventing its own.

**Trust trade-off documented for the user.** Onboarding / Privacy Policy text should reflect that, when Ghost mode falls back to the Snowflake-via-AMP path, a Google CDN endpoint is on the *broker-discovery* leg of the connection. End-to-end encrypted message content, identity, and contact graph remain protected by the Double Ratchet + Sealed Sender + Tor onion service properties regardless of which bridge profile delivered the circuit. This addition is tracked as a follow-up wording task on the Privacy Policy page; no shipped behaviour change is required.

---

## Bugs that have been fixed in Alpha 1 development

These are documented for transparency about what we resolved during the development sprint:

| ID | Bug | Status | Commit |
|---|---|---|---|
| BUG-A | Trust Tier — incoming first message disappeared from main chat list | Fixed (by design — moved to Message Requests) | earlier |
| BUG-H | libsodium JNI loader crash on some Android devices | Fixed | `dc21a60e`, `b7c277be` |
| BUG-CRYPTO-RACE | Crypto race condition (parallel envelope processing corrupted ratchet state) | Fixed | mutex per conversationId |
| BUG-RELAY-LOST | Sent envelope silently lost when TCP socket was dead | Fixed | `1f35072c` (ACK watchdog + ping) |
| BUG-OUTBOX-ORDER | Two consecutive failed sends reversed outbox order | Fixed | `37e1414e` fix #1 (`addLast`) |
| BUG-CLOSE-HANG | `session.close()` hung 60s on dead TCP, blocking reconnect | Fixed | `37e1414e` fix #2 (`withTimeoutOrNull`) |
| BUG-DOUBLE-DELIVER | Single envelope processed twice in parallel by `handleDeliver` | Fixed | `37e1414e` fix #3 (processingLock) |
| BUG-CONNID-RACE | Server-side cleanup deleted record of new connection on fast reconnect | Fixed | `37e1414e` fix #4 (atomic `conn_id`) |
| BUG-WS-TIMEOUT | Server `TimeoutLayer(30s)` killed WebSocket connections | Fixed | `37e1414e` fix #5 (excluded `/ws` from timeout) |
| BUG-CADDY-TIMEOUT | Caddy `response_header_timeout 30s` killed WebSocket streams | Fixed | `37e1414e` fix #6 (removed) |
| BUG-DEBUG-URL | Debug APK hardcoded to dev's local IP | Fixed | `69e87ad8` |
| BUG-CLOSE-HANG-2 | `session.close()` graceful path waited 60 s on hung sockets | Fixed | `b14b39ff` (`session.cancel()`) → `2ee1d08d` (force-cancel OkHttp dispatcher) |
| BUG-INIT-SEQ | WebSocket did not connect after first onboarding without app restart | Fixed | `5caf61eb` |
| BUG-FALSE-OFFLINE | "Offline" banner showed during initial connect before any attempt | Fixed | `5caf61eb` |
| BUG-PINGJOB-LEAK | Stale `pingJob` from previous generation force-cancelled the new session | Fixed | `846d6bed` (`cancelAndJoin`) |
| BUG-RELAY-NO-PONG | Relay's `socket.split()` deferred WS PONG flushing past idle windows | Fixed | `dbd9393c` (single-task `tokio::select!` with explicit `Message::Pong`) |
| BUG-OEM-RADIO-PARK | Tecno HiOS parked Wi-Fi radio mid-session despite foreground service | Mitigated | `74e6af0a` (WifiLock+WakeLock), see ISSUE-001 |
| F12 | X3DH handshake bypassed — SessionManager called only `computeSharedSecret(identity_priv, identity_pub)` | Fixed in Phase 1 Week 4 | PR A (`8fa020ae`) + PR B (`d53011f5`) + PR C (this branch) |
| F15 | Initial ratchet seeded with identity DH keypair — compromise of identity meant compromise of every session | Fixed in Phase 1 Week 4 | PR A introduced fresh-ephemeral invariant; PR C wires the production path |

---

---

## Relay limitations

### ISSUE-017: Media chunks are in-memory on the relay

**Status (2026-05-21).** Unchanged in kind but the user-visible blast radius is narrower than the Alpha-1 wording suggested.

**What's true today.** The relay stores both text envelopes (`HashMap<identity, Vec<Envelope>>` + JSONL replay) and M1w media chunks (in-memory `MediaStore`) in process memory, with no SQLite/Sled backend. A relay restart between an upload's `manifest_sent` envelope and the recipient's `download_complete` would lose the un-downloaded chunks. The sender has no application-level retry signal for that case.

**Why the impact is operationally smaller than it sounds.**
- Sender-side: the M2e early manifest fires after ~5100 bytes of upload progress, but the sender continues uploading the tail in background and only commits `manifest_sent → SENT` after the manifest envelope acks. If the relay restarts mid-upload, the upload coroutine sees the WS or REST error and the row stays in a retryable state.
- Receiver-side: the dynamic fresh-task window `(chunkCount × 1500 ms).coerceIn(120_000, 300_000)` plus backoff `1s → 2s → 3s` means the receiver patiently retries `404 not_ready_yet` for up to 2–5 min before declaring `media_chunks_gone`. A short relay restart fits inside that window. A long restart (≥ 5 min) loses the media — the sender has no automatic re-send today (queued as **PR-INFRA-MediaRO** + downstream "media-resend on receiver request" follow-up).
- The text-envelope durability story documented under [ISSUE-012](#issue-012-relay-prekeystore--in-memory--jsonl-persistence) is unchanged: text survives via JSONL append-replay; client re-publish flow covers the lossy edges by design.

**Long-term direction.** Persistent media storage on the relay (SQLite, Sled, or filesystem-backed `MediaStore`) is queued but not active; tracked in `docs/PROJECT_LOG.md → Open follow-ups` as part of the broader media-storage track. The current Alpha-2-quality answer is the in-memory store with the receiver's tolerant retry window — sufficient for the daily-usage profile, not sufficient for "phone offline 6 hours, then download".

---

### ISSUE-018: Tele2 LTE — WS Frame.Text silently dropped upstream

**Status:** Known carrier-network limitation. Mitigated by automatic transport degrade to REST short-poll (PR-D0r / PR-D1d).

**Symptom.** On Tele2 LTE Иркутская (verified Test #48 on Tecno `103603734A004351`, 2026-05-16), the WebSocket completes the 101 Upgrade handshake successfully but every subsequent application WS frame from phone to server is silently lost upstream. Server-side `session_summary` lines show `pings_received=0 inbound_frames=0` across ~20 consecutive phone WS sessions, each terminating at the server's ~153 s read timeout. The client side OkHttp `pingInterval(15s)` timeout firing at ~31 s is the **symptom**, not the cause — phone-side ping frames never reach the server in the first place.

**Root cause.** Stateful Tele2 middlebox classifier that admits the WS Upgrade but treats subsequent persistent-WS-frame traffic as suspect and silently drops the body. The H1c/H1e hardening (PR-H1d disable-app-level-ping was considered then killed as wrong direction — `pings_received=0` on the server proves the symptom is not "client sends too fast", so removing client pings just extends zombie-WS lifetime without fixing anything).

**Mitigation shipped.** REST short-poll fallback (PR-D0r relay endpoints + PR-D1/D1b/D1c/D1c.1/D1d client orchestrator). When the orchestrator's per-envelope ACK deadline (PR-D1d, 10 s) fires for a first stuck outbound envelope, the state machine flips `WsActive → RestActive`, the pending envelope migrates to a REST POST with `Idempotency-Key`, and subsequent sends use REST `/relay/send` + `/relay/poll` + `/relay/ack-deliver`. **Test #52 verified** end-to-end: first stuck outbound `397df3c7` armed at `+0.001 s`, expired at `+10.004 s`, migrate completed `send_response status=201 elapsedMs=625`, total user-visible latency ~11 s (was ~40 s on the pre-D1d build).

**REST is not free.** Polling interval + idempotency cache + Tele2 Layer B response-drop (see [ISSUE-019](#issue-019-tele2-lte--post-response-dropped-downstream)) all add latency vs a healthy WS. Voice on REST requires the encrypted media-upload path (M1w + M2 trilogy) because REST `max_send_body=4096 b` is too small for raw voice chunks. Calls cannot run on REST at all (no realtime UDP) — see [ISSUE-014](#issue-014-calls--experimental-feature-unproven-on-russian-mobile-carriers).

**Direct WS is intentionally retained** as a primary transport when the network admits it (Wi-Fi, non-Tele2 cellular, VPN). The REST fallback activates only when the orchestrator's state machine declares the WS realtime as Limited.

---

### ISSUE-019: Tele2 LTE — POST response dropped downstream

**Status:** Known carrier-network limitation. Mitigated by request idempotency + client retry of the same `Idempotency-Key`.

**Symptom.** On Tele2 LTE the same Test #48 verified that a `POST /prekeys/publish` with a 5863-byte body arrives at the server, gets processed (`status=201 duration=2.9ms resp=18b` in Caddy access log), but the 18-byte response is silently dropped downstream and never reaches the phone. The client then hits its 60 s SocketTimeout. Independent of OEM and independent of WS — affects REST POST replies on the same Tele2 path.

**Mitigation shipped (PR-D0r round 1).** Per-identity LRU idempotency cache on the relay (10 000 keys × 24 h TTL, `sha2::Sha256` body hash) keyed by `(identity, Idempotency-Key)`. Every client REST send is retried with the **same** Idempotency-Key on any error path; if the server already processed the request, the retry hits the cache, returns the original response, and the client treats `status=201` as terminal success without double-spending.

**Diagnostic posture.** Layer B is detectable by comparing Caddy access logs (where the response was written) against client logs (where the response was never received). The orchestrator's `migrate_pending_send` / `send_response` / `token_reused` log chain makes the round-trip visible end-to-end in real-device tests.

**Why this isn't a separate failure category from Layer A.** Tele2 Layer A (WS Frame.Text drop, ISSUE-018) and Layer B (POST response drop, ISSUE-019) appear together in the same diagnostic sessions and seem to be the same family of stateful classifier behaviour — but the two layers need to be documented separately because the mitigation surface is different: Layer A drives the WS → REST transport switch, Layer B drives the idempotency contract inside the REST path.

---

### ISSUE-020: Tele2 media-path full-roundtrip ceiling on a single Helsinki relay

**Status:** Architectural limitation of single-relay deployment on Tele2 LTE. Mitigated by chunk-size tuning + M2f binary `/media/v3` endpoint; the long-term fix is route diversity, not chunk size.

**Symptom (PR-M2c.0 cap probe, 2026-05-18 evening).** The PR-M2c.0 probe v2.1 on Tecno Tele2 LTE measured the largest media body that survives a complete POST upload + GET download round trip through `relay.phntm.pro` Helsinki: **stableMaxBodyFullRoundtrip = 2400 bytes** on the v2 JSON+Base64 endpoint, **largestUploadOnly = 6500 bytes** (relay stores the chunk but the 201-response and the subsequent GET body get dropped downstream — same Layer B pattern as [ISSUE-019](#issue-019-tele2-lte--post-response-dropped-downstream)). Above 6500 b the request body itself fails to land.

**Implication for media transport.** The earlier audit recommendation (Section 5 of `docs/design/voice-delivery-audit-2026-05-18.md`) to push chunk size to 5500–6500 b is **not feasible on the current single-relay infrastructure**. The probe's `recommendedRawChunkBytes=1700` was the v2-correct floor — but PR-M2f's binary `/media/v3/{mediaId}/{idx}` endpoint then removed the JSON+Base64 33 % wire-overhead inflation, giving back ~600–700 bytes within the same 2400 b full-roundtrip ceiling. Test #70.2 confirmed `chunk_size_selected = 3500` was stable on v3, and PR-M2f.2 locked production at `TARGET_RAW_CHUNK_BYTES = 3200` per Vladislav's never-ship-max-passed-once policy.

**Long-term direction.** Route diversity, not chunk size: **PR-INFRA-MediaRO** would deploy a second `phantom-relay` at a different VPS/ASN (FlokiNET Romania candidate; an unrelated bridge2 deployment exists as a non-blocking reference but is WebTunnel-only) and surface the second route to clients via a `mediaRelayId` extension to `VoiceManifestV2`. The probe code is preserved on `diag/m2c0-media-route-probe` so the same matrix can be re-run with two `MediaProbeEndpoint` entries when a second route exists. **No active work** until the operations cost (second VPS, federated identity model, per-relay registry decisions) is approved.

**Memory pointer.** `feedback_tele2_media_path_ceiling_2026_05_18.md` for the architectural insight captured in the agent memory.

---

### ISSUE-021: Native OkHttp pattern required for any new Android HTTPS path on RU LTE

**Status:** Architectural pattern, captured to keep future PRs from re-introducing the bug class.

**Background.** Three independent incidents on Russian carrier LTE (PR-R0.1 / PR-R0.3 prekey publish 2026-05-15, PR-M1w-R4 media download 2026-05-18) reproduced the same failure mode: Ktor or pooled-OkHttp HTTPS requests on RU LTE stall for 30+ s between consecutive requests even though the relay logs show the request was served in single-digit milliseconds. The phone simply does not see the response within a reasonable window. The pattern is independent of WS, independent of REST, independent of the M1w media path — it is intrinsic to long-lived OkHttp connections under RU carrier middleboxes.

**Locked architectural rule.** Any new Android HTTPS path that has to work on Russian mobile carriers MUST use **native OkHttp with a fresh client per call**: `HttpClient.Builder` per call, `Connection: close`, `ConnectionPool(0)`, `retryOnConnectionFailure(false)`, 10 s timeouts. Connection-pool reuse is forbidden on RU LTE for application HTTPS. This is encoded in:

- `createPreKeyPublishHttpClient()` in the PreKey API client (PR-R0).
- `AndroidNativeOkHttpMediaUploadTransport` and `AndroidNativeOkHttpMediaDownloadTransport` in the media-upload path (PR-M1w-R4).
- PR-G4 pinned REST OkHttp to `Protocol.HTTP_1_1` only on Android.

**Why this isn't "just fix OkHttp / Ktor".** Both libraries are correct on the standards — the carrier middlebox behaviour is non-standard. Wrapping the lifecycle (one client per call) is cheaper and safer than trying to coax the libraries into matching middlebox quirks.

**Memory pointer.** `project_tele2_media_path_2026_05_18.md` records the third reproducer; `feedback_technical_patterns.md` keeps the rule list.

---

### ISSUE-022: First-message-to-new-contact delay (~10–20 s yellow-dot)

**Status:** Known follow-up. Diagnosed, not yet fixed.

**Symptom.** When user A adds user B as a contact and immediately sends the first text message, the bubble appears with a yellow "Sending…" dot for roughly 10–20 s before flipping to Sent. The yellow-dot symptom is the legacy "first-message reliability" class first hit in Test #28 (2026-05-12).

**Root cause (post-G4).** PR-G4 closed the "yellow dot for two minutes" class entirely by pinning REST OkHttp to HTTP/1.1 only (Test #30 dropped phone bundle fetch from 8009 ms × 4 timeouts to 151 ms × 1 OK). The remaining ~10–20 s delay observable across Tests #67b–#68 traces to a different layer: `PREKEY_TRACE upload_fail SocketTimeoutException elapsedMs=8021` in the bootstrap window for a freshly-added contact — same class as the H1e half-open WS pattern, just on the prekey path. The OPK publish times out the first time and only succeeds on retry.

**Fix queued.** **PR-D1e — first-message bootstrap fast path.** No active work yet. Tracked in `docs/PROJECT_LOG.md → Open follow-ups`. Expected to combine (a) shorter per-attempt timeout with eager retry, (b) reuse the orchestrator's REST transport for prekey publish when WS is in Limited mode, (c) parallel bundle-fetch + send rather than sequential.

**User impact today.** The bubble eventually sends and the conversation works normally from message 2 onward. No data loss, no silent failure — just a 10–20 s delay that an external user reasonably reads as "the app is slow".

---

### ISSUE-023: Receiver-side media cancel is not supported

**Status:** Architectural limitation of the current relay media protocol. Blocks re-enabling the M2e early-manifest overlap.

**Symptom.** The X glyph on the receiver-side downloading voice bubble is intentionally hidden (`AudioBubble.onCancelUpload = null` on the receive path). The receiver has no way to cancel an in-flight download or to tell the relay "stop holding the rest of this media for me". The X glyph on the sender-side uploading bubble works (PR-MEDIA-UPLOAD-CANCEL2.1 — see ISSUE-006), but that only stops the upload coroutine; it cannot retract a manifest already in the receiver's hands.

**Knock-on effect: M2e overlap currently disabled.** PR-M2e's "early manifest after ~5100 bytes" shipped successfully for the happy path. But when the **sender** cancels a voice mid-upload after the early manifest has been issued, the receiver knows about a media the sender then refuses to complete — and would loop on `MEDIA_RX chunk_not_ready_yet … reason=media_chunks_gone` until its fresh-task window expired. PR-MEDIA-UPLOAD-CANCEL2 reverted M2e to a tail-only manifest (`sendAudioV2` passes `onEarlyManifest = null`) until the relay grows a real cancel/chunk-delete protocol. Sequential upload + tail manifest is correct semantically but slower than the M2e overlap baseline on long voices.

**Long-term direction.** Two relay-side endpoints are required:
1. `DELETE /media/v3/{mediaId}` — sender-issued chunk-delete that revokes the manifest's claim. Receiver responds to `404 mediaId_revoked` by deleting the in-progress download row and the local placeholder bubble.
2. `POST /media/v3/{mediaId}/cancel-pull` — receiver-issued cancel that tells the relay "drop the chunks held for this mediaId for me". Without this, a receiver who cancels download today simply stops `GET`-ing; the relay still holds the chunks until the sender's TTL.

Both are queued as **PR-MEDIA-CANCEL-PROTOCOL** with no fixed schedule. Once shipped, M2e overlap re-enables and the receiver bubble's X glyph becomes interactive.

---

## Tracking

This list is maintained as a living document. Issues are tracked in GitHub Issues at:
https://github.com/LiudvigVladislav/Phantom/issues

For external review and Beta planning, this snapshot represents the state of master `8f4c68c9` (2026-05-21). The Alpha-1 baseline snapshot (2026-04-25) is preserved upstream in this file's git history — `git log -p KNOWN_ISSUES.md` will reproduce the original wording for any issue ID.
