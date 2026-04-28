# PHANTOM

> A privacy-focused, censorship-resistant messenger for restricted internet environments.
> Telegram's UX. Signal's security architecture. Built to be unstoppable.

[![Status: Alpha 1](https://img.shields.io/badge/status-alpha%201-orange)](#status)
[![License: TBD](https://img.shields.io/badge/license-TBD-lightgrey)](#license)
[![Platforms](https://img.shields.io/badge/platforms-android-green)](#platforms)

---

## What is PHANTOM?

PHANTOM is a messenger built for people who cannot trust their network. Journalists working under hostile regimes, activists in countries with internet censorship, dissidents whose communications must not be traceable, and ordinary people who simply believe that private communication is a human right.

It combines two things that have historically been mutually exclusive:

- **The user experience of Telegram** — fast, modern, beautiful
- **The security architecture of Signal** — end-to-end encryption, sealed sender, forward secrecy

PHANTOM is being built as an open-source project with a values-driven mission: to provide infrastructure for free communication that works even when networks try to stop it.

---

## Status

**Current stage:** Alpha 1 (development sprint completed 2026-04-27, tagged `v0.1.0-alpha.1`)

**What works today:**
- End-to-end encrypted one-on-one messaging between Android devices
- Production relay deployed at `relay.phntm.pro` (Hetzner, EU jurisdiction)
- Trust Tier flow (first messages from unknowns land in Message Requests)
- QR code contact exchange
- Store-and-forward delivery (messages queue when recipient is offline)
- Sealed sender envelopes (relay sees only routing metadata)

**What's coming next** (Alpha 2 / Beta):
- iOS app
- Web client (Compose Multiplatform via WASM)
- Nearby mode (offline mesh via Bluetooth + Wi-Fi Direct)
- Kademlia DHT-based P2P routing (reduce dependence on central relay)
- Pluggable censorship-circumvention transports
- Group chats
- Voice and video calls

See [RELEASE_NOTES.md](RELEASE_NOTES.md) for full Alpha 1 details and [KNOWN_ISSUES.md](KNOWN_ISSUES.md) for current limitations.

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

---

## Threat model

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

PHANTOM is being developed by a solo founder (Russia/Delaware-incorporated US company) with the goal of building sustainable infrastructure.

**Active funding pursuits:**
- NLnet Foundation grant application (Privacy & Trust Enhancing Technologies, deadline 2026-06-01)

**Planned monetization** (to fund continued development without compromising free core):
- Premium subscriptions (advanced features, custom themes, larger limits)
- B2B accounts for organizations with compliance requirements
- Bot API platform
- White-label licensing for jurisdictions with specific deployment needs
- Crypto donations

The core messenger will remain free and open-source for individual users — always.

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

- **GitHub Issues:** https://github.com/LiudvigVladislav/Phantom/issues
- **Author:** WladislaW
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

- The **Signal** protocol designers (Trevor Perrin, Moxie Marlinspike)
- The **libsodium** project (Frank Denis et al.)
- The **Tor Project** for foundational work on censorship circumvention
- The **NLnet Foundation** for funding privacy-enhancing technology
- Anyone who has built tools that help people communicate freely

---

*"Privacy is necessary for an open society in the electronic age."* — Eric Hughes, A Cypherpunk's Manifesto, 1993
