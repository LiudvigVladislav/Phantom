<p align="center"><img src="site/static/logo-mark.png" width="220"></p>

# PHANTOM

End-to-end encrypted Android messaging with censorship-resistant transports,
production-validated against carrier-grade DPI (TSPU) on real Russian mobile networks.

[![Status: Alpha 2](https://img.shields.io/badge/status-alpha%202-orange)](#status)
[![Release: v0.1.0-alpha.2](https://img.shields.io/badge/release-v0.1.0--alpha.2-orange)](https://github.com/LiudvigVladislav/Phantom/releases/tag/v0.1.0-alpha.2)
[![License: AGPL-3.0-or-later](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/platform-Android-3ddc84)](#building-from-source)
[![Site: phntm.pro](https://img.shields.io/badge/site-phntm.pro-665cff)](https://phntm.pro)
[![Mirror: Codeberg](https://img.shields.io/badge/mirror-Codeberg-2185d0)](https://codeberg.org/VladislavLiudvig/Phantom)

## What is PHANTOM?

PHANTOM is an open-source messenger for people who cannot assume their network is
neutral or trustworthy: journalists, activists, dissidents, and anyone who believes private communication is a human right.

The project combines a familiar modern messenger experience with explicit
security and censorship-resistance boundaries:

- end-to-end encrypted 1:1 messaging and encrypted voice notes;
- multiple network paths selected at runtime, without requiring a separate VPN or third-party circumvention app;
- an untrusted store-and-forward relay that handles ciphertext and necessary routing metadata, not message plaintext;
- public architecture decisions, threat modeling, known issues, and development history.

PHANTOM implements X3DH, Double Ratchet, and Sealed Sender-inspired protocol layers
over libsodium primitives. This is custom protocol code, not `libsignal-client`,
and it has not received an independent third-party cryptographic audit.

## Status

**Current stage:** Alpha 2, active development on `master`.

**Latest tagged pre-release:** [`v0.1.0-alpha.2`](https://github.com/LiudvigVladislav/Phantom/releases/tag/v0.1.0-alpha.2).
The tag is a historical snapshot from 2026-04-30; development on `master` has moved
substantially beyond it. Both Alpha releases remain on the [Releases page](https://github.com/LiudvigVladislav/Phantom/releases).

**Production relay:** [`relay.phntm.pro`](https://relay.phntm.pro), a Rust/Axum
service that stores and forwards encrypted envelopes. It can still observe timing,
size, authenticated sessions, and delivery destinations.

### Working on current `master`

- **End-to-end encrypted 1:1 text** with prekey bootstrap, X3DH, Double Ratchet,
  Sealed Sender envelopes, Safety Numbers, and QR contact exchange.
- **Adaptive transport selection** across Direct WSS, embedded Xray VLESS+REALITY,
  and Tor v3 onion. Tor is deliberately text-only.
- **REST send/poll fallback** when a carrier passes HTTPS but silently drops or stalls long-lived WebSocket traffic.
- **Encrypted voice notes:** XChaCha20-Poly1305 encryption, bounded chunk upload,
  a ratcheted media manifest, and receiver reassembly. The media + REST path has
  been exercised on Tele2 LTE.
- **Per-user signed-challenge authentication**, encrypted Android storage
  (SQLDelight + SQLCipher), store-and-forward delivery, disappearing messages,
  message edit/delete, and conversation mute/pin.

### Honest Alpha boundaries

- The custom cryptographic protocol composition is not independently audited.
- Groups and calls have code and UI surfaces, but are not yet production-ready release features.
- Tor is an emergency text path, not a media or realtime-call transport.
- Field validation proves specific devices, carriers, routes, and dates—not every carrier or future DPI policy.

### Roadmap by horizon

- **Next:** Direct/REST stability hardening across carrier changes, faster first
  contact bootstrap, stable groups, and encrypted photo/file attachments using
  the existing media pipeline.
- **Beta:** harden 1:1 voice/video calls over Direct/REALITY, add a desktop
  client, expand pluggable transports, and design linked-device identity.
- **v1.0:** iOS client, public channels, a rate-limited username directory,
  supported self-hosted relay packaging, and an independent security audit.
- **Post-v1 research:** BLE/Wi-Fi Direct mesh, Kademlia DHT routing,
  federation experiments, and post-quantum migration.

See [ROADMAP.md](ROADMAP.md), [KNOWN_ISSUES.md](KNOWN_ISSUES.md), and the
[development journal](docs/PROJECT_LOG.md) for the detailed state.

## Architecture

```
┌────────────────────────────────────────────────────────────┐
│ Application                                                │
│ Android Compose UI · shared Kotlin Multiplatform core      │
├────────────────────────────────────────────────────────────┤
│ Offline mesh — post-v1 research                            │
│ Bluetooth LE · Wi-Fi Direct · local discovery              │
├────────────────────────────────────────────────────────────┤
│ Routing & discovery                                        │
│ QR/invite exchange · relay prekeys · DHT post-v1           │
├────────────────────────────────────────────────────────────┤
│ Shipped transports                                         │
│ Direct WSS · VLESS+REALITY · Tor text · REST fallback      │
├────────────────────────────────────────────────────────────┤
│ Cryptographic core                                         │
│ Custom X3DH/Double Ratchet over libsodium · media AEAD      │
└────────────────────────────────────────────────────────────┘
```

### Tech stack

- Kotlin Multiplatform shared core and Compose UI (Android-first)
- libsodium-backed X25519, XSalsa20-Poly1305, XChaCha20-Poly1305, and hashing
- SQLDelight + SQLCipher for encrypted Android storage
- Ktor/OkHttp clients with Direct, Xray/REALITY, Tor, and REST paths
- Rust + Axum relay, Caddy edge, and Docker Compose deployment

Start with [ARCHITECTURE.md](ARCHITECTURE.md) for the short overview and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the module and data-flow
walkthrough. Architectural decisions are indexed in
[docs/adr/README.md](docs/adr/README.md); especially relevant are
[ADR-006](docs/adr/ADR-006-Crypto-Library-Decision.md),
[ADR-016](docs/adr/ADR-016-tor-unified-push-hybrid-transport.md),
[ADR-019](docs/adr/ADR-019-Xray-REALITY-Outer-Transport.md), and
[ADR-020](docs/adr/ADR-020-Adaptive-Transport-Selection.md).

## Threat model

PHANTOM is designed around an untrusted network and an untrusted relay. The
formal model is in
[docs/threat-model/Threat_Model_v0.md](docs/threat-model/Threat_Model_v0.md).

The current design aims to protect against:

- passive network inspection of message content;
- a compromised relay reading correctly encrypted messages or media;
- retrospective decryption of earlier traffic after later ratchet-state
  compromise;
- endpoint blocking and DPI through production-validated pluggable transport
  paths;
- offline extraction from a locked Android device through encrypted local
  storage and platform-keystore protection.

It does not claim to protect against:

- an unlocked or malware/root-compromised endpoint;
- coercion, social engineering, or a malicious conversation partner;
- a global observer correlating timing and volume at multiple network points;
- every future censorship technique or every carrier configuration;
- undiscovered flaws in the unaudited custom protocol implementation.

Direct transport reveals a connection to the relay. REALITY changes the
observable wire shape but is not an anonymity system. Tor provides the strongest
current network-origin hiding and is restricted to text. Review
[SECURITY.md](SECURITY.md) and [KNOWN_ISSUES.md](KNOWN_ISSUES.md) before relying
on Alpha software for a high-risk use case.

## Building from source

### Prerequisites

- JDK 21
- Android Studio Narwhal (2026.1) or later and Android SDK 35
- Rust 1.83+ for the relay
- Git

### Clone and select a baseline

```bash
git clone https://github.com/LiudvigVladislav/Phantom.git
cd Phantom

# Stay on master for the current development state, or use the release snapshot:
git checkout v0.1.0-alpha.2
```

### Build and test

```bash
# Android debug APK
./gradlew :apps:android:assembleDebug

# Crypto instrumented tests (requires an attached emulator/device)
./gradlew :shared:core:crypto:connectedDebugAndroidTest

# Relay tests
cargo test --manifest-path services/relay/Cargo.toml
```

Run the relay locally with:

```bash
cargo run --release --manifest-path services/relay/Cargo.toml
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full development and validation
workflow.

## Deploy your own relay

The repository contains the operator deployment used for PHANTOM's relay,
including Caddy, Axum, Tor, Xray/REALITY, and supporting services. It is an
Alpha-stage reference deployment, not yet the supported v1 self-hosting kit.

Start with [deploy/README.md](deploy/README.md). Before exposing a relay, replace
operator-specific domains and credentials, review firewall and persistent-state
requirements, and build a client configured for your endpoint.

## Contributing

Contributions and adversarial review are welcome, especially in cryptography,
transport field testing, Android reliability, threat modeling, accessibility,
and documentation.

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. For a
large feature or trust-boundary change, open an issue first so the design and
required ADR can be agreed before implementation.

## Funding

PHANTOM is maintained by Willen LLC and accepts support through:

- [Liberapay](https://liberapay.com/Phantom-messenger)
- [Buy Me a Coffee](https://www.buymeacoffee.com/phantompro)
- BTC, XMR, and ETH addresses published in [`funding.json`](funding.json)

The machine-readable funding file also documents current budgets and project
funding goals. Repository Sponsor links are configured in
[`.github/FUNDING.yml`](.github/FUNDING.yml).

## License

PHANTOM is licensed under the
**GNU Affero General Public License v3.0 or later** (`AGPL-3.0-or-later`). See
[LICENSE](LICENSE) and [NOTICE](NOTICE).

AGPL network-source requirements help keep modified hosted relays auditable.
A commercial dual-license is available for white-label or B2B deployments that
cannot use the AGPL; contact `legal@phntm.pro`.

## Contact

- Website: [phntm.pro](https://phntm.pro)
- Source: [GitHub](https://github.com/LiudvigVladislav/Phantom) ·
  [Codeberg mirror](https://codeberg.org/VladislavLiudvig/Phantom)
- Bugs and feature requests: [GitHub Issues](https://github.com/LiudvigVladislav/Phantom/issues)
- Security disclosures: `security@phntm.pro` — see [SECURITY.md](SECURITY.md)
- General contact: `hello@phntm.pro`
- Legal / licensing: `legal@phntm.pro`

## Acknowledgments

PHANTOM builds on the work of the Signal protocol designers, libsodium, the Tor
Project, XTLS/Xray-core, Briar and its Android Tor packaging, Kotlin
Multiplatform, Rust, Axum, Caddy, and the wider open-source privacy-tech
community. See [NOTICE](NOTICE) for third-party attributions.

*“Privacy is necessary for an open society in the electronic age.”*
— Eric Hughes, *A Cypherpunk's Manifesto* (1993)
