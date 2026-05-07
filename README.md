# PHANTOM

> **An end-to-end encrypted messenger that works on Russian mobile carriers without a VPN, without Orbot, without any third-party app.**
> Built on the Signal protocol. Production-validated through Russia's TSPU deep-packet-inspection on 2026-05-07.

[![Status: Alpha 2](https://img.shields.io/badge/status-alpha%202-orange)](#status)
[![License: AGPL--3.0--or--later](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)](LICENSE)
[![Platforms](https://img.shields.io/badge/platforms-android-green)](#platforms)
[![Mirror: Codeberg](https://img.shields.io/badge/mirror-Codeberg-teal)](https://codeberg.org/VladislavLiudvig/Phantom)

---

## What is PHANTOM?

PHANTOM is a messenger built for people who cannot trust their network. Journalists working under hostile regimes, activists in countries with internet censorship, dissidents whose communications must not be traceable, and ordinary people who simply believe that private communication is a human right.

It combines two things that have historically been mutually exclusive:

- **The user experience of Telegram** — fast, modern, beautiful
- **The security architecture of Signal** — end-to-end encryption, sealed sender, forward secrecy

PHANTOM is being built as an open-source project with a values-driven mission: to provide infrastructure for free communication that works even when networks try to stop it.

---

## Status

**Current stage:** Alpha 2 (mid-sprint as of 2026-05-08).
**Tagged release:** `v0.1.0-alpha.1` (2026-04-27).
**Latest production milestone:** Stage 5E (Xray VLESS+REALITY censorship resistance) shipped 2026-05-07 and validated end-to-end on a Tecno phone connected through Russia's MTS network — text and voice messages flow through PHANTOM without any VPN, Orbot, or third-party app installed. See [ADR-019](docs/adr/ADR-019-Xray-REALITY-Outer-Transport.md) for the full architectural rationale.

**What works today:**
- End-to-end encrypted one-on-one messaging between Android devices (Double Ratchet, Sealed Sender envelopes)
- **Censorship circumvention via Xray VLESS+REALITY** — masquerades the wire traffic as a TLS handshake to `www.microsoft.com`, bypassing Russia's TSPU 16-kilobyte curtain
- Production relay deployed at `relay.phntm.pro` (Hetzner, EU jurisdiction) with a Tor v3 onion service as fallback
- Voice messages with chunked transport (~55 KB envelopes per chunk, delivered through the Xray tunnel)
- Trust Tier flow (first messages from unknowns land in Message Requests)
- QR code contact exchange
- Store-and-forward delivery (messages queue when recipient is offline)

**Roadmap, by realistic horizon:**

- **Alpha 2 (next release):** Group chats foundation (state machine + Sender Keys crypto already present in shared core, surface work pending), ADR-020 *Adaptive Transport Selection* — runtime probe-and-pick between direct WSS, Xray, Tor (today's choice is a build-time flag), encrypted attachments (photos and files via the encrypted MinIO design in the ADR backlog).
- **Beta:** Voice and video calls (call-signalling already exists in shared core; UI wiring in active development), ADR-021 *Multi-server Xray fan-out* — closes the single-point-of-failure on the current Hetzner endpoint, iOS app (XCFramework via Compose Multiplatform; ADR-022), additional pluggable transports (obfs4, Snowflake) joining today's Tor + Xray pair.
- **Post-Beta research:** Wi-Fi Direct + Bluetooth Mesh nearby modes for offline-first delivery (Briar-class), Kademlia DHT P2P routing as a fallback to the central relay, post-quantum migration of the cryptographic primitives.

See [RELEASE_NOTES.md](RELEASE_NOTES.md) for full Alpha 1 details, [KNOWN_ISSUES.md](KNOWN_ISSUES.md) for current limitations, and [docs/PROJECT_LOG.md](docs/PROJECT_LOG.md) for the running development journal.

---

## Architecture

PHANTOM is a 5-layer system:

```
┌─────────────────────────────────────────────────────┐
│  Application Layer                                  │
│  Chats, contacts, UI, push notifications, settings  │
├─────────────────────────────────────────────────────┤
│  Offline Mesh (planned)                             │
│  Bluetooth, Wi-Fi Direct, mDNS local discovery      │
├─────────────────────────────────────────────────────┤
│  P2P Routing (relay today, DHT in Alpha 2)          │
│  Kademlia DHT, @username discovery, sealed routing  │
├─────────────────────────────────────────────────────┤
│  Pluggable Transport                                │
│  WSS today; planned: 7 censorship-circumvention     │
│  channels (Tor bridges, domain fronting, more)      │
├─────────────────────────────────────────────────────┤
│  Cryptographic Core                                 │
│  libsodium, Double Ratchet, X25519, XChaCha20-Poly  │
└─────────────────────────────────────────────────────┘
```

### Tech stack

- **Kotlin Multiplatform** for shared core (Android, iOS, web from one codebase)
- **Compose Multiplatform** for UI
- **libsodium** for cryptography (via [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium))
- **SQLDelight + SQLCipher** for encrypted local storage
- **Ktor** for WebSocket client
- **Rust + Axum** for relay server
- **Caddy** for TLS termination and reverse proxy
- **Docker Compose** for relay deployment

### Documented decisions

Every architectural choice has a public rationale captured as an Architecture Decision Record. Index: [docs/adr/](docs/adr/) (also exported as a short table at the top of [docs/adr/README.md](docs/adr/README.md)). Highlighted entries:

- [ADR-001 (System Boundaries)](docs/adr/ADR-001-System-Boundaries.md) — what PHANTOM is and is not
- [ADR-006 (Crypto Library Decision)](docs/adr/ADR-006-Crypto-Library-Decision.md) — libsodium-only stack and the AGPL-3.0-or-later licensing rationale
- [ADR-016 (Tor + UnifiedPush hybrid transport)](docs/adr/ADR-016-tor-unified-push-hybrid-transport.md) — the two-channel data-plane / wakeup-plane architecture
- [ADR-019 (Xray VLESS+REALITY)](docs/adr/ADR-019-Xray-REALITY-Outer-Transport.md) — Stage 5E censorship-resistance rationale, threat model, known limitations

---

## Threat model

PHANTOM has a formal threat model documenting adversary capabilities, asset inventory, security goals, and explicit out-of-scope items. Read it at [docs/threat-model/Threat_Model_v0.md](docs/threat-model/Threat_Model_v0.md) — an English executive summary sits at the top of the file, with the formal model body below in Russian. Headline summary:

PHANTOM is designed to protect against:

- **Mass surveillance** by state-level adversaries on the network path
- **Compromised relay servers** — relay learns nothing about message content or sender identity
- **Device seizure** with screen-locked device — local data is encrypted at rest
- **Network-level censorship** (in progress — pluggable transport in Alpha 2)
- **Forward secrecy compromise** — past messages remain secure even if current keys are compromised

PHANTOM is **not** designed to protect against:

- A device with the screen unlocked and the user logged in
- An attacker with root access to the device's memory while the app is running
- Social engineering or coercion
- Endpoint compromise via malware

For the most sensitive use cases, PHANTOM should be combined with operating system hardening (GrapheneOS recommended) and secure operational practices.

---

## Building from source

### Prerequisites

- JDK 21 (Gradle and modules target Java 21 bytecode)
- Android Studio (latest stable)
- Android SDK — `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`
- Rust 1.83+ (for the relay)
- Git

### Clone

```bash
git clone https://github.com/LiudvigVladislav/Phantom.git
cd Phantom
# Alpha 1 lives on master once the release is tagged:
git checkout v0.1.0-alpha.1
```

### Build Android APK

```bash
# Debug build (defaults to production relay)
./gradlew :apps:android:assembleDebug

# Release build (signed)
./gradlew :apps:android:assembleRelease
```

Output APKs:
- Debug: `apps/android/build/outputs/apk/debug/android-debug.apk`
- Release: `apps/android/build/outputs/apk/release/android-release.apk`

### Run tests

```bash
# Crypto integration tests (16 tests) on a connected Android emulator/device.
# The libsodium loader does not run on plain JVM, so these tests live in
# androidInstrumentedTest and require an attached device with `adb devices`.
./gradlew :shared:core:crypto:connectedDebugAndroidTest
```

### Run the relay locally

```bash
cd services/relay
cargo run --release
```

The relay listens on `0.0.0.0:8080` by default.

---

## Deploy your own relay

PHANTOM relays are intentionally simple and designed to be self-hostable. The reference relay is deployed via Docker Compose:

```bash
# On your VPS
git clone https://github.com/LiudvigVladislav/Phantom.git
cd Phantom/deploy
# Edit Caddyfile to set your domain
docker compose up --build -d
```

Configure your Android client to use your relay by setting `RELAY_URL` in the build configuration.

---

## Contributing

PHANTOM is in active early-stage development. Contributions are welcome. Areas where help is most needed:

- **Cryptography review** — anyone with formal crypto background, please review the Double Ratchet integration
- **Censorship circumvention** — pluggable transport implementations
- **iOS development** — Compose Multiplatform iOS target needs polish
- **Translations** — English and Russian today; more languages welcome
- **Threat modeling** — adversarial review and documentation
- **Documentation** — user guides, deployment guides, FAQ

Before contributing code, please open an issue to discuss the change.

---

## Funding & sustainability

The core messenger — including all privacy guarantees, the full Double Ratchet implementation, the relay stack, and the censorship-resistance transport layer — remains free, open-source, and uncrippled for individual users, forever. Long-term sustainability is funded by a separate optional commercial track of value-added services for organisations: managed relay deployments, priority support contracts, encrypted backup-and-recovery convenience features, and specialised integrations. None of these services restrict access to or affect the privacy guarantees of the core open-source software.

**Donations:** see the **Sponsor** button at the top of this repository for direct support channels. Public-interest funding programmes that align with the project's privacy mission are also pursued from time to time; the canonical funding-channel list lives in [`funding.json`](funding.json).

PHANTOM is being developed by a solo founder operating through Willen LLC (Delaware, USA).

---

## License

PHANTOM is licensed under the **GNU Affero General Public License version 3** (AGPL-3.0-or-later). See [`LICENSE`](LICENSE) for the full text and [`NOTICE`](NOTICE) for third-party attributions.

```
Copyright (c) 2026 Willen LLC
Copyright (c) 2026 Vladislav Liudvig
```

**Why AGPL-3.0.** The relay component is server-side software that users interact with over the network. AGPL-3.0 §13 (Remote Network Interaction) ensures any modified version offered as a network service must publish its source — protecting the project's privacy promises against silently-modified hosted forks. See [ADR-006](docs/adr/ADR-006-Crypto-Library-Decision.md) for the full rationale.

A commercial dual-licensing option is available for white-label or B2B deployments that cannot ship under AGPL terms. Contact `legal@phntm.pro`.

---

## Contact

- **Source code:** [GitHub](https://github.com/LiudvigVladislav/Phantom) · [Codeberg mirror](https://codeberg.org/VladislavLiudvig/Phantom) (non-US, AGPL-aligned host)
- **GitHub Issues:** https://github.com/LiudvigVladislav/Phantom/issues
- **Author:** Vladislav Liudvig
- **Company:** Willen LLC (Delaware, USA)

### Email routing

| Purpose | Address |
|---|---|
| Security vulnerabilities (responsible disclosure) | `security@phntm.pro` |
| User support, general questions | `support@phntm.pro` |
| Privacy / GDPR / data-handling questions | `privacy@phntm.pro` |
| Legal correspondence, DMCA, lawful-process requests | `legal@phntm.pro` |
| Abuse reports (RFC 2142 — spam, harassment, illegal content) | `abuse@phntm.pro` |
| Press / media inquiries | `press@phntm.pro` |

See [SECURITY.md](SECURITY.md) for full security-disclosure terms.

---

## Acknowledgments

PHANTOM stands on the shoulders of many open-source projects and research efforts. Special recognition to:

- The **Signal** protocol designers (Trevor Perrin, Moxie Marlinspike) — the Double Ratchet and Sealed Sender constructions PHANTOM builds on
- The **libsodium** project (Frank Denis et al.) — the cryptographic primitives underneath every E2EE operation in the codebase
- The **Tor Project** — PHANTOM embeds an in-process tor daemon (via [kmp-tor](https://github.com/05nelsonm/kmp-tor) packaging Briar's `onionwrapper` fork) for the onion-service data path; Stage 5A-5C of the transport layer would not exist without their work
- The **XTLS / Xray-core** maintainers — Stage 5E's REALITY transport (the censorship-resistance path validated through Russia's TSPU) is built on top of their daemon
- The **Briar** project — the `onionwrapper` Android packaging of Tor that we depend on, plus the broader research on offline mesh delivery that informs our post-Beta direction
- The wider open-source privacy-tech community whose libraries, research, and review effort make work like this possible

---

*"Privacy is necessary for an open society in the electronic age."* — Eric Hughes, A Cypherpunk's Manifesto, 1993
