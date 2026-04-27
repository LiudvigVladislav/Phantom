# PHANTOM — Architecture Overview

**Status:** draft v0.1 (2026-04-24) · **Audience:** grant reviewers, security
auditors, new contributors. For the authoritative decisions see the ADRs
linked throughout this document.

PHANTOM is a privacy-first end-to-end encrypted messenger. The
cryptographic core is [libsignal](https://github.com/signalapp/libsignal)
(AGPL-3.0) and [libsodium](https://libsodium.org) (ISC) — we write no
custom cryptography (Product Doctrine §2.4). The architecture is built
so that no single infrastructure component can read user content or
derive the full communication graph.

This document is a draft. Sections marked **TODO** are intentional
placeholders for depth we plan to add before the Kickstarter launch.

---

## 1. High-level layering

```
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 5 — Application                                              │
│  Android (Compose Multiplatform) · iOS (planned) · Desktop (later)  │
│    Screens, navigation, UX, local push, biometrics                  │
└─────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │  plaintext only here
                                  │
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 4 — Mesh (future)                                            │
│    Bluetooth LE · Wi-Fi Direct · local peer discovery               │
│    For offline / censorship / disaster scenarios. Not in Alpha.     │
└─────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 3 — Routing & Discovery                                      │
│    Username → public key lookup · invite links (phantom:// + https) │
│    DHT (Kademlia) and federated directory are future work.          │
└─────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 2 — Transport                                                │
│    WebSocket relay · pluggable: obfs4, Tor bridge, BLE, Wi-Fi       │
│    All transports see only opaque ciphertext envelopes.             │
│    See ADR-003 (Transport Abstraction) and ADR-004 (Relay Trust).   │
└─────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 1 — Cryptographic Core                                       │
│    X3DH handshake · Double Ratchet · Sealed Sender · Safety Numbers │
│    libsignal + libsodium. See docs/CRYPTO.md.                       │
└─────────────────────────────────────────────────────────────────────┘
```

Only Layer 1 and Layer 5 ever touch plaintext. Layers 2–4 handle
ciphertext only. This follows the plaintext policy in
[ADR-001 §Plaintext policy](adr/ADR-001-System-Boundaries.md).

---

## 2. Module layout

PHANTOM is a Kotlin Multiplatform monorepo. Shared business logic lives
under `shared/core/*` and is consumed by per-platform app shells.

| Module | Responsibility | Reference |
|---|---|---|
| `shared/core/identity`  | Local identity keys, username binding                 | ADR-002 |
| `shared/core/crypto`    | Signal Protocol primitives, memzero, Safety Numbers   | ADR-006, [CRYPTO.md](CRYPTO.md) |
| `shared/core/storage`   | SQLCipher-encrypted local DB (SQLDelight)             | ADR-002 |
| `shared/core/transport` | `Transport` interface, WebSocket relay client         | ADR-003 |
| `shared/core/messaging` | Session manager, envelope pipeline, group logic       | ADR-002 |
| `services/relay`        | Rust/axum store-and-forward relay (no plaintext ever) | ADR-004 |
| `apps/android`          | Compose UI, foreground service, Universal Links       | — |

The app shell (`apps/android`) **never** contains crypto or transport
logic. That split is enforced by module boundaries: the UI module
depends on `shared/core/*` but not on libsodium or axum directly.

Full architectural decisions: [ADR-001](adr/ADR-001-System-Boundaries.md),
[ADR-002](adr/ADR-002-Shared-Core-Layout.md).

---

## 3. Data flow — sending a message

The following trace shows a single 1:1 text message from Alice to Bob.
State names match
[docs/protocols/State_Machines.md](protocols/State_Machines.md) — the
authoritative description of message and session lifecycles.

```
Alice's device                                    Bob's device
──────────────                                    ────────────
1. User types "hi"
2. [Draft]
3. [Encrypted]
   ├─ Get-or-create Ratchet session (X3DH if new)
   ├─ DoubleRatchet.encrypt(messageKey)
   ├─ Zeroize messageKey + ephemeral DH output
   └─ Seal sender identity (Sealed Sender)
4. [Queued]  ──► SQLCipher on disk
5. [Sent]    ──► WebSocket /ws   relay.phntm.pro
                                     │
                                     │  Store-and-forward
                                     │  Sees only: recipient routing
                                     │  token, TTL, opaque ciphertext
                                     ▼
                                  relay queue for Bob
                                     │
                    Bob online ──────┤
                                     │
                                     ▼  WebSocket deliver
                              6. [AcceptedByTransport]
                              7. [Delivered]
                                 ├─ Unseal sender
                                 ├─ DoubleRatchet.decrypt
                                 └─ Zeroize messageKey
                              8. [Displayed]
                                 └─ Insert into SQLCipher, emit UI event
                              9. Read receipt ──► relay ──► Alice
                                                          [Read]
```

Delivery acknowledgements (`RELAYED`, `DELIVERED`, `READ`) are surfaced
in the UI as check marks. The relay never sees plaintext at any point,
and since Alpha-1 the envelope carries a
[Sealed Sender](CRYPTO.md#sealed-sender) token so the relay cannot
learn Alice's public key from routing metadata either.

---

## 4. Threat model summary

The detailed threat model lives at
[docs/threat-model/Threat_Model_v0.md](threat-model/Threat_Model_v0.md).
Headline points:

- **Relay compromise.** A fully compromised relay can drop or delay
  messages and observe recipient-side metadata (sizes, timings). It
  cannot decrypt content. Compromise does not retroactively break past
  sessions (forward secrecy via Double Ratchet).
- **Device compromise.** If Alice's device is compromised, past messages
  on that device are exposed. Post-Compromise Security (PCS) ensures
  that as soon as Alice's device recovers (new DH ratchet step), an
  attacker holding her old keys cannot decrypt future messages.
- **Network adversary.** All transport is TLS 1.3 between client and
  Caddy. WebSocket upgrade is encrypted end-to-end at the transport
  layer and end-to-end at the application layer. A passive observer
  sees only that some device talked to `relay.phntm.pro`.
- **Out of scope.** Physical access with unlocked device. Malicious
  OS/baseband. State-level targeted attacks against a specific user's
  device.

Operator abuse prevention follows
[docs/policy/Abuse_Prevention_Doctrine.md](policy/Abuse_Prevention_Doctrine.md):
private chats are *not* subject to server-side moderation; abuse
surfaces (public channels, discovery) live in a separate layer with
different rules.

---

## 5. Deployment topology (current)

```
Android client ── TLS ──► Caddy 2 ── internal bridge ──► phantom-relay (axum)
                            │
                            └──► static assetlinks.json + landing page
```

- **VPS:** `phantom-relay-01` at Hetzner Helsinki (CPX22, Ubuntu 24.04).
- **DNS:** `relay.phntm.pro` (DNS-only, direct TLS) and `phntm.pro`
  (Cloudflare-proxied, for the landing page and
  `/.well-known/assetlinks.json`).
- **Certificate:** Let's Encrypt, auto-renewed by Caddy.
- **No database on the relay.** Queues are in-memory with a TTL; this
  is deliberate (ADR-004) to minimize what a compromised relay can leak.

Operational runbook:
[deploy/README.md](../deploy/README.md) in the repository root.

---

## 6. Open questions — TODO for later drafts

- Multi-device identity model. Today each install is a distinct
  identity; a proper multi-device model requires linked-device trust.
- Large-group scalability beyond Sender Keys (fan-out cost on the
  relay at thousands of members).
- Tor bridge integration — planned for v1.0, exact packaging open.
- Federated directory vs. single hosted username registry. Alpha-2
  will use a hosted registry; federation comes later.
- Formal verification of the Sealed Sender + padding scheme against
  known traffic-analysis attacks.
