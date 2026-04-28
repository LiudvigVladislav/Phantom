# PHANTOM Alpha 1 — Known Issues

**Last updated:** 2026-04-27
**Build:** branch `fix/bug-h-libsodium-jni`, latest commit `b2447dde`
**Tested platforms:** Android (Tecno Spark 2022 / Android 12, plus Pixel 8 Pro emulator API 35), Hetzner VPS relay (`relay.phntm.pro`)

---

## Overview

PHANTOM Alpha 1 is a functional privacy-focused E2E messenger with verified end-to-end encryption (Double Ratchet via libsodium), Trust Tier message requests, and store-and-forward delivery via centralized relay over WebSocket Secure (WSS).

This document is intentionally exhaustive. Transparency about limitations is essential for a privacy tool — users deserve to know exactly what works and what doesn't.

## Severity Legend

- **P1 (High):** Causes user-visible problems. Should fix before Beta.
- **P2 (Medium):** Polish issues. Acceptable in Alpha, fix in Beta.
- **P3 (Low):** Cosmetic or edge-case. Track for future iterations.

---

## P1 — High Severity

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

**Long-term fix (Alpha 2).** Push-on-disconnect via Unified Push (FOSS protocol, no Google dependency). PHANTOM intentionally does **not** add Firebase Cloud Messaging as a hard dependency: FCM would put a Google trackable identifier on every install and break the zero-third-party-metadata posture documented in [ADR-001](docs/adr/ADR-001-System-Boundaries.md) and [Threat Model v0](docs/threat-model/Threat_Model_v0.md).

---

### ISSUE-002: Init sequence requires app restart after first registration  ✅ RESOLVED

**Resolved by commit `5caf61eb` (fix(android): WebSocket connect after onboarding).**

After identity creation in onboarding, `MainActivity.PhantomApp` now re-triggers `startForegroundService(...)` from the `OnboardingScreen.onComplete` callback so `PhantomMessagingService.onStartCommand` re-runs with the freshly-created identity available, calls `service.startReceiving()`, and opens the WebSocket without an app restart.

---

### ISSUE-003: "Offline — messages queued" badge shows when no items are queued  ✅ RESOLVED

**Resolved by commit `5caf61eb` (fix(android): suppress false offline badge).**

`ConnectionBanner` now gates the "Offline" line on a `hasEverConnected` flag set the first time `TransportState` transitions to `Connecting` or `Connected`. A real disconnect-after-connect still surfaces the banner; the cold-start "I have never tried" case stays silent.

---

## P2 — Medium Severity

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

## P3 — Low Severity

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

---

## Tracking

This list is maintained as a living document. Issues are tracked in GitHub Issues at:
https://github.com/LiudvigVladislav/Phantom/issues

For NLnet review and Beta planning, this snapshot represents the state at the end of Alpha 1 development sprint (2026-04-25).
