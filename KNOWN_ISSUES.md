# PHANTOM Alpha 1 — Known Issues

**Last updated:** 2026-05-02
**Build:** master at `c62fbfff` — PR #28 (transport reliability) + PR #29 (calls UX, F-03/07/10/15) + PR #30 (calls media: mic permission, audio mode, black-screen) all merged.
**Tested platforms:** Android (Tecno Spark Go 2023 / Android 12 HiOS, plus Pixel 8 Pro emulators API 35), Hetzner VPS relay (`relay.phntm.pro`)

---

## Overview

PHANTOM Alpha 1 is a functional privacy-focused E2E messenger with verified end-to-end encryption (Double Ratchet via libsodium), Trust Tier message requests, and store-and-forward delivery via centralized relay over WebSocket Secure (WSS).

This document is intentionally exhaustive. Transparency about limitations is essential for a privacy tool — users deserve to know exactly what works and what doesn't.

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

### ISSUE-001: WebSocket reconnects every ~60 s on aggressive-OEM Android skins

**Symptom.** On certain OEM Android skins (Tecno HiOS verified, Xiaomi MIUI / Huawei EMUI strongly suspected) the foreground-service notification is visible and the WebSocket connects successfully, but the connection drops on a deterministic ~60-second cycle. Each cycle:

1. Client logs `SocketTimeoutException: sent ping but didn't receive pong within 8000ms (after N successful ping/pongs)`
2. Reconnect succeeds within ~1.5 s
3. Any envelope the peer sent during the gap is delivered immediately on reconnect (store-and-forward keeps it durable)

**Impact.** A message sent to a recipient mid-reconnect arrives ~1–3 s later than usual. **No messages are lost.** On stock Android (Pixel 8 Pro emulator, etc.) on Wi-Fi the connection has been stable across multi-minute QA sessions with the same code path and the same relay endpoint. The issue is specifically the OEM skin's wireless power management overriding the foreground service's keepalive intent.

**Root cause.** OEM-side power management parks the Wi-Fi radio between transmissions to save battery, even with a foreground notification active and even when the device is plugged in. Small WebSocket frames get deferred into the next radio wake window; over enough deferrals the peer's PONG response misses the client's ping-timeout deadline and OkHttp forces a fresh connection. (Initial QA pointed at carrier NAT timeout; the WiFi-only retest with the phone and emulator on the same router proved the cause is on the device, not the network path.)

**Mitigations shipped in Alpha 1.**

- Foreground service holds `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` and a partial `WakeLock` for its full lifetime ([apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt](apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt)) — commit `74e6af0a`
- OkHttp transport-level ping is **disabled** (`pingInterval(0)`); application-level `RelayMessage.Ping/Pong` over WS frames is the sole liveness check (`PING_INTERVAL_MS = 10 s`, `PONG_TIMEOUT_MS = 60 s`). The previous OkHttp `pingInterval = 8 s` was killing every large envelope mid-upload because OkHttp's pingInterval is also its pong-timeout — a slow uplink could not return a pong before the next ping fired
- `forceCancelAllEngineCalls()` so a hung WebSocket aborts in <2 s instead of 60 s — commit `2ee1d08d`
- `cancelAndJoin` between reconnect generations so stale ping-timers cannot fire on the new socket — commit `846d6bed`
- `RECONNECT_BASE_DELAY_MS = 1 s` to halve user-visible reconnect latency — commit `452a0b5e`
- `ACK_TIMEOUT_MS = 60 s` so a slow uplink saturated by a large payload does not get torn down before the relay can ack
- Relay `RELAY_MAX_PAYLOAD_BYTES = 1 048 576` (1 MiB) so voice notes (~67 KB per 5 s of base64-encoded 3GP audio) and other inlined media fit
- Relay-side store-and-forward retains envelopes until the recipient sends `ack-deliver`

These bring the affected configuration from "messages stuck for 60 s" down to "1–3 s extra latency under network pressure" for **text** messages.

**Voice messages on Tecno-class OEMs: NOT delivered without VPN (known limitation).** End-to-end log analysis on 2026-04-28 confirmed an asymmetric outbound failure mode: the phone receives inbound envelopes from the relay (incoming text arrives) but its outbound channel to the relay goes silent within ~30–70 s of each reconnect — application-level Pings stop reaching the server even though no upload is in progress, so neither voice envelopes nor the periodic Ping frames reach the relay. Same emulator-to-emulator path delivers a 75 KB voice envelope in ~1 s; the failure is unambiguously local to the Tecno radio. **A VPN client running on the same phone restores voice delivery** because the VPN tunnel keeps a continuous keepalive that prevents the OEM radio from parking — but requiring users to run a VPN is not an acceptable product answer. Workarounds in the user-settings list below sometimes help, but cannot be guaranteed.

**Workaround for Tecno HiOS users.**

1. *Settings → Apps → PHANTOM → Battery* → Unrestricted
2. *Settings → Apps → Special access → Battery optimization* → PHANTOM → Don't optimize
3. *Settings → Battery → Battery saver* → off during use
4. If "Power Marathon" / "Smart Power" / "Phone Master" exists in the OEM apps, add PHANTOM to the whitelist

**Long-term fix.** The push-on-disconnect approach that earlier docs pointed at (Unified Push / FCM hybrids) was retired during the 2026-05-14 strategic pivot — both UnifiedPush and FCM are out of scope, because the metadata-leak posture documented in [ADR-001](docs/adr/ADR-001-System-Boundaries.md) and [Threat Model v0](docs/threat-model/Threat_Model_v0.md) makes any always-on third-party push channel an architectural non-starter. The current answer for this issue is the mitigations listed above (WifiLock, WakeLock, app-level ping/pong, `forceCancelAllEngineCalls`, relay store-and-forward) plus the Reality / REST fallback transports added later — they reduce the user-visible impact to a 1-3 s reconnect latency on affected OEMs. There is no separate scheduled push-channel work; if a future approach is identified, it will land as its own ADR.

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

### ISSUE-004: First envelope after reconnect+flush occasionally fails MAC verification

**Symptom:** When a recipient reconnects and the relay flushes multiple queued envelopes in quick succession, the first envelope sometimes fails decryption with "MAC validation failed". Subsequent envelopes in the same batch decrypt successfully.

**Observed in:** Earlier test sessions before fix `37e1414e` deployed. Not observed in final 2026-04-25 evening test session, but window for skipped message keys may still be tight.

**Root cause:** Out-of-order message delivery edge case in the Double Ratchet implementation. The skipped message keys window may be too small for high-burst recovery scenarios.

**Mitigation in Alpha 1:** Sender retry logic exists; users can resend the failed message and it succeeds.

**Planned fix (Alpha 2):**
- Increase `MAX_SKIP` constant in Double Ratchet implementation (currently default)
- Add monitoring metric for MAC verification failures
- Consider automatic resend on MAC failure (with deduplication on receiver)

---

### ISSUE-005: ToS / onboarding screen accepts username but does not visually confirm registration completion

**Symptom:** During registration, after entering username and proceeding, there is no clear "Account created" confirmation. User is dropped onto the main Chats screen which shows the misleading "Offline — messages queued" banner.

**Impact:** Combined with ISSUE-002 and ISSUE-003, the post-registration experience is unclear.

**Planned fix (Alpha 2):**
- Show a brief "Welcome, @username!" confirmation toast/screen
- Display "Connecting..." state with progress indicator
- Transition to home screen only after WebSocket connects

---

### ISSUE-006: No retry feedback when a message fails to send

**Symptom:** When the WebSocket is disconnected and the user sends a message, the message appears in the chat with no visual indication that it is pending. There is no "Sending..." spinner or pending icon. The message just sits there until reconnect.

**Impact:** User uncertainty about whether the message was actually sent.

**Planned fix (Alpha 2):**
- Pending icon (clock) on messages that are queued in outbox
- Single check (✓) when relay acks (`status=delivered`)
- Double check (✓✓) when recipient acks (`ack-deliver`)

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

### ISSUE-014: Calls — experimental feature in Alpha

**Status:** WebRTC voice calls implemented and partially functional. Marked **experimental** in Alpha. Core text messaging is the primary supported feature; voice calls are best-effort and known-unstable on aggressive-OEM Android skins.

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

### ISSUE-017: M1r media chunks are in-memory only

- **M1r media chunks are in-memory only.** Uploaded voice media may be
  lost on relay restart before the receiver downloads them. Persistent
  media storage is deferred to a follow-up PR (M1r.1 / M2).

**Impact.** If the relay process restarts while a voice message's chunks have been uploaded but not yet downloaded by the recipient, those chunks are lost. The sender has no way to detect this without re-sending. This affects voice messages only; text envelopes have the same semantics (also in-memory), which is documented in ISSUE-012.

**Planned fix.** M1r.1 will introduce SQLite or Sled-backed persistent chunk storage on the relay.

---

## Tracking

This list is maintained as a living document. Issues are tracked in GitHub Issues at:
https://github.com/LiudvigVladislav/Phantom/issues

For external review and Beta planning, this snapshot represents the state at the end of Alpha 1 development sprint (2026-04-25).
