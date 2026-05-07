# PHANTOM Alpha 1 — Release Notes

**Release date:** 2026-04-27 (development sprint completion)
**Branch:** `fix/bug-h-libsodium-jni` (will be merged into `master` and tagged `v0.1.0-alpha.1`)
**Latest commit:** `b2447dde`
**Platforms:** Android (debug + release builds)

---

## What is PHANTOM?

PHANTOM is a privacy-focused, censorship-resistant messenger that combines Telegram's user experience with Signal's security architecture. Built specifically for users in heavily restricted internet environments.

**Core philosophy:**
- End-to-end encryption by default — every message, no exceptions
- Sealed sender — relay sees only routing metadata, never sender identity
- Censorship-resistant transport (planned: pluggable circumvention channels)
- No phone number required (planned: optional, locally hashed)
- Decentralized routing via DHT (planned for Alpha 2)
- Offline mesh mode via Bluetooth + Wi-Fi Direct (planned for Alpha 2)

---

## Alpha 1 — What's Included

### Cryptography

- **End-to-end encryption** using libsodium (`secretbox` for symmetric encryption, X25519 for key exchange)
- **Double Ratchet** implementation for forward secrecy and post-compromise security
- **Sealed sender** envelopes — relay cannot identify message senders, only recipients
- **Per-conversation session keys** stored encrypted in Android Keystore
- **SHA-256 + PSI hashing** for future contact discovery (architecture ready, not user-facing in Alpha 1)

### Transport

- **WebSocket Secure (WSS)** transport over TLS 1.3
- **Production relay** deployed at `wss://relay.phntm.pro/ws` (Hetzner Helsinki, EU jurisdiction)
- **Caddy** reverse proxy with automatic HTTPS via Let's Encrypt
- **Rust relay server** with WebSocket handling, store-and-forward queue, and metadata-only logging
- **OkHttp protocol-level ping** every 8s for dead-connection detection
- **Client-side ACK watchdog** with 15s timeout and automatic requeue
- **Outbox flush on reconnect** preserves message order (`addLast` semantics)

### Storage

- **SQLDelight** with **SQLCipher** for encrypted local database
- **Database schema v8** with archived/saved fields per conversation/message
- **Migration framework** for schema upgrades
- **Identity persistence** in Android Keystore (private keys never leave hardware-backed storage where available)

### Application

- **Trust Tier flow** — first message from unknown contact lands in Message Requests; user accepts to upgrade to Trusted conversation (Signal-style)
- **QR code contact sharing** for two-way key exchange
- **Username system** (`@vl1`, `@wl2` style local handles)
- **Onboarding** with Terms of Service acceptance and identity creation
- **Chat list** with active conversations and Message Requests separation
- **One-on-one messaging** verified working with E2E encryption end-to-end

### Architecture

- **Kotlin Multiplatform** — single codebase targeting Android, iOS (planned), and web (planned)
- **Compose Multiplatform** for UI
- **5-layer system architecture:**
  1. Cryptographic core (Signal Protocol + Double Ratchet)
  2. Pluggable transport (currently WSS; censorship channels planned)
  3. P2P routing (currently relay-mediated; Kademlia DHT planned)
  4. Offline mesh (Bluetooth + Wi-Fi Direct planned)
  5. Application layer (UI, chat logic, contact management)
- **Modular design** — crypto, transport, storage, messaging, and UI are separate KMP modules

### Infrastructure

- **Production VPS** on Hetzner (Helsinki, EU)
- **Docker Compose** deployment with `phantom-relay` (Rust) and `phantom-caddy` containers
- **CI-ready** repository structure on GitHub
- **Network security config** enforcing TLS for all production traffic, cleartext only on `localhost` for dev

---

## Test Results — 2026-04-27 Final QA

Tested between physical Android phone (`Tecno Spark 2022`, Android 12 / HiOS, `vl1`) and Android emulator (`Pixel 8 Pro`, API 35, `vl2`) — both on the same home Wi-Fi router, both connecting to production relay at `wss://relay.phntm.pro/ws`.

### What worked

- ✅ Identity creation and registration on both devices
- ✅ WebSocket connection to production relay over TLS
- ✅ QR code contact addition (one-way and two-way)
- ✅ First message from `vl1` → `vl2` delivered as Message Request (Trust Tier flow working)
- ✅ Message accepted from Requests → conversation upgraded to Trusted
- ✅ Reply from `vl2` → `vl1` delivered to active chat (Trusted tier)
- ✅ Subsequent messages delivered both directions
- ✅ Decryption: zero MAC errors across the entire QA campaign
- ✅ Store-and-forward: messages sent during reconnect queued and delivered after reconnect succeeded
- ✅ No double-processing of envelopes (parallel `handleDeliver` race fixed)
- ✅ Dedup guard: duplicate envelopes correctly ack'd and skipped
- ✅ Stock-Android (Pixel 8 Pro emulator on Wi-Fi) WebSocket stable across multi-minute QA sessions with the fixed relay code path — no spurious reconnects, no message loss
- ✅ Init sequence: identity created → WebSocket connects without app restart (commit `5caf61eb`)
- ✅ "Offline" banner suppressed during cold-start before any connect attempt (commit `5caf61eb`)
- ✅ All 16 crypto integration tests green on emulator: `./gradlew :shared:core:crypto:connectedDebugAndroidTest` (BUG-H formally closed)

### Known issues encountered (see KNOWN_ISSUES.md)

- ISSUE-001 — Tecno HiOS parks Wi-Fi radio between transmissions, causing a deterministic ~60 s reconnect cycle on that device specifically. Mitigated to 1–3 s message-delivery latency via WakeLock + WifiLock + faster reconnect path; messages still durable via store-and-forward. Long-term fix is Unified Push (Alpha 2). Stock Android is unaffected.

---

## Commits in this release

Final commits on `fix/bug-h-libsodium-jni` branch (most recent first):

| Commit | Description |
|---|---|
| `b2447dde` | revert: remove server-driven heartbeat — was the wrong abstraction |
| `74e6af0a` | fix(android): hold Wi-Fi + partial wake locks for the foreground service |
| `452a0b5e` | fix(transport): tighten OkHttp pingInterval and reconnect base delay for cellular |
| `dbd9393c` | fix(relay): respond to WebSocket PING immediately, fixes 60 s reconnect cycle |
| `846d6bed` | fix(transport): cancelAndJoin previous generation before reconnect |
| `2ee1d08d` | fix(transport): force-cancel OkHttp dispatcher to bypass 60 s graceful close |
| `5caf61eb` | fix(android): WebSocket connect after onboarding + suppress false offline badge |
| `b14b39ff` | fix(transport): replace `session.close()` with `session.cancel()` on pong/ack timeout |
| `69e87ad8` | fix(android): debug build defaults to production relay URL |
| `37e1414e` | fix(transport): 6 bugs from 2026-04-25 QA — reconnect stability + ordering + double-processing |
| `1f35072c` | chore(dev): add gradlew compile permissions |
| `b7c277be` | feat(storage): migration 8 — archived on conversation, saved on message |
| `dc21a60e` | feat(android): network security config — block cleartext, allow localhost |

---

## Build instructions

### Prerequisites
- Android Studio with Android SDK
- JDK 21 (matches the README — the toolchain compiles to Java 21 bytecode)
- Android device or emulator (API 26+)

### Build debug APK
```bash
./gradlew :apps:android:assembleDebug
```
Output: `apps/android/build/outputs/apk/debug/android-debug.apk`

### Build release APK
```bash
./gradlew :apps:android:assembleRelease
```
Output: `apps/android/build/outputs/apk/release/android-release.apk`

### Install on device
```bash
adb install -r apps/android/build/outputs/apk/debug/android-debug.apk
```

---

## Relay deployment

Relay runs on Hetzner VPS via Docker Compose:

```bash
ssh phantom@relay.phntm.pro
cd ~/Phantom
git pull --rebase
cd deploy
docker compose up --build relay -d
docker compose restart caddy
```

Production endpoint: `wss://relay.phntm.pro/ws`

---

## What's next — Alpha 2 priorities

In rough order of importance:

1. Push-on-disconnect via Unified Push (FOSS, no Google dependency) — fixes ISSUE-001 properly for Tecno-class OEM skins
2. Per-message status indicators in the chat UI (Sending / Sent / Delivered / Read)
3. Implement Nearby UI (Bluetooth + Wi-Fi Direct discovery)
4. Begin Kademlia DHT integration for P2P routing
5. iOS target build and testing
6. Add at least one censorship-circumvention transport (WebSocket over CDN or similar)

(Items previously listed for Alpha 2 that have shipped in Alpha 1: init-sequence auto-connect, banner cold-start suppression, the entire 60 s reconnect cycle on stock Android, and the green crypto integration test run.)

---

## License

**AGPL-3.0-or-later** for the entire repository — see [`LICENSE`](LICENSE) for the full text and [`NOTICE`](NOTICE) for third-party attributions. AGPL-3.0 §13 ensures the relay's privacy promises remain enforceable against silently-modified hosted forks. An optional commercial dual-licensing arrangement is available for white-label or B2B deployments that cannot ship under AGPL terms; contact `legal@phntm.pro`.

---

## Contact

- GitHub: https://github.com/LiudvigVladislav/Phantom
- Author: WladislaW (Willen LLC, Delaware)
- Project status: Active development, Alpha stage
