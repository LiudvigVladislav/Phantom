# PHANTOM Architecture (overview)

This document is the 400-word entry point. For depth, follow the
links — every claim here is backed by a specific Architecture
Decision Record (ADR) or threat-model section.

## High-level layers

PHANTOM is a three-layer system:

- **Client** — Kotlin Multiplatform shared core (`shared/core/*`)
  with an Android-first UI shell (`apps/android/`). The shared core
  hosts identity, crypto, storage, transport, messaging, and the
  censorship-resistance modules; iOS reuses the same core through
  an XCFramework once the post-Alpha-2 iOS milestone lands.
- **Relay** — a stateless Rust + Axum store-and-forward service
  (`services/relay/`) that holds ciphertext envelopes only. It
  speaks WebSocket over Caddy-terminated TLS, enforces per-identity
  capabilities, and never sees plaintext message content. Trust
  posture is captured in [ADR-004](docs/adr/ADR-004-Relay-Trust-Model.md).
- **Discovery** — out-of-band today (QR codes + invite links). A
  username directory is in scope for Beta; see
  [ADR-005](docs/adr/ADR-005-Discovery-Scope-for-MVP.md).

## Why Kotlin Multiplatform

One source of truth for crypto, storage, and transport on Android
and iOS. The same `RelayTransport` interface, the same Double
Ratchet, the same SQLDelight schema. Module layout in
[ADR-002](docs/adr/ADR-002-Shared-Core-Layout.md).

## Why a custom Double Ratchet (not libsignal-client)

libsignal-client is GPL-3.0 — the relay's AGPL-3.0 §13 reach would
have to negotiate around GPL incompatibility on shared types.
We build the ratchet on **libsodium** primitives instead — ISC-licensed,
audited, and aggregation-clean against AGPL. Rationale and
implementation notes in
[ADR-006](docs/adr/ADR-006-Crypto-Library-Decision.md).

## Why a custom Rust relay (not Matrix or XMPP)

Matrix and XMPP carry per-event metadata that the threat model
cannot accept (room membership, presence, server federation).
Our relay is a deliberately small surface: queue ciphertext for
N seconds, deliver on next connect, drop. Trust posture in
[ADR-004](docs/adr/ADR-004-Relay-Trust-Model.md).

## Censorship resistance

Two outer transports plug into the same `RelayTransport` interface:

- **Tor v3 onion + WebTunnel bridges** ([ADR-016](docs/adr/ADR-016-tor-unified-push-hybrid-transport.md))
- **Xray VLESS+REALITY**, masquerading as a TLS handshake to
  `www.microsoft.com` to bypass Russia's TSPU 16-KB curtain
  ([ADR-019](docs/adr/ADR-019-Xray-REALITY-Outer-Transport.md))

Stage 5E (Xray) was production-validated on Tecno + MTS without
VPN on 2026-05-07.

## Future architectural directions

- **Mesh** — Briar-style Bluetooth + Wi-Fi-Direct local transport
  for offline-first delivery
- **Federation** — Matrix bridge for cross-protocol contact import
- **Post-quantum migration** — replace X25519 with hybrid X25519
  + Kyber-768 once libsodium ships post-quantum primitives in a
  stable release

## See also

- [docs/adr/](docs/adr/) — the full ADR catalogue with index
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — deeper module-by-module
  walkthrough (this root document is the summary, that one is the depth)
- [docs/threat-model/Threat_Model_v0.md](docs/threat-model/Threat_Model_v0.md) — what we defend against and what we explicitly do not
- [docs/PROJECT_LOG.md](docs/PROJECT_LOG.md) — running journal of
  decisions and session notes
