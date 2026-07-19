# PHANTOM — Architecture Overview

**Status:** current `master` snapshot (2026-07-19) · **Audience:** external
reviewers, security auditors, and new contributors. Architectural decisions
live in the [ADR catalogue](adr/README.md); the shorter entry point is the
[root architecture overview](../ARCHITECTURE.md).

PHANTOM is an Android-first, end-to-end encrypted messenger built as a Kotlin
Multiplatform client plus a Rust relay. Its protocol layer implements X3DH,
Double Ratchet, Sealed Sender, and media encryption over libsodium primitives.
It does **not** integrate `libsignal-client`, and its cryptographic protocol
implementation has not received an independent third-party audit. See
[ADR-006](adr/ADR-006-Crypto-Library-Decision.md) and the authoritative
[`shared/core/crypto`](../shared/core/crypto/) implementation for the current
code boundary and known limitations.

---

## 1. High-level layering

```
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 5 — Application                                              │
│  Android / Compose UI · shared Kotlin Multiplatform core            │
│  iOS and desktop shells are planned, not current release targets    │
└─────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 4 — Mesh (post-v1 direction)                                 │
│  Bluetooth LE · Wi-Fi Direct · local peer discovery                 │
│  No mesh transport ships in the current application                 │
└─────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 3 — Routing & Discovery                                      │
│  QR codes · invite links · relay-hosted prekey lookup               │
│  Username directory and DHT discovery are future work               │
└─────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 2 — Transport                                                │
│  Direct WSS · Xray VLESS+REALITY · Tor v3 onion (text only)         │
│  REST poll/send fallback when WebSocket delivery degrades           │
└─────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │
┌─────────────────────────────────────────────────────────────────────┐
│  Layer 1 — Cryptographic Core                                       │
│  X3DH · Double Ratchet · Sealed Sender · encrypted media            │
│  PHANTOM protocol code over libsodium primitives                    │
└─────────────────────────────────────────────────────────────────────┘
```

Application code and the cryptographic core are the only layers that handle
message plaintext. Discovery, transport, and relay components handle public
keys, routing identifiers, protocol metadata, and ciphertext. This is a
minimization boundary, not a claim of metadata anonymity: an authenticated
relay session can still reveal timing, volume, network origin (unless hidden
by Tor), and the destination needed for delivery. See
[ADR-001](adr/ADR-001-System-Boundaries.md) and
[ADR-004](adr/ADR-004-Relay-Trust-Model.md).

---

## 2. Module layout

PHANTOM is a Kotlin Multiplatform monorepo with an Android app shell and a
separate Rust service:

| Module | Current responsibility |
|---|---|
| `shared/core/identity` | Local identity keys, signing keys, and identity persistence |
| `shared/core/crypto` | PHANTOM's X3DH, Double Ratchet, Sealed Sender, Sender Keys, Safety Numbers, and media crypto |
| `shared/core/storage` | SQLDelight repositories; SQLCipher-backed storage on Android |
| `shared/core/transport` | Transport interfaces, relay client, selection state, and REST fallback protocol |
| `shared/core/xray` | Embedded libXray lifecycle and VLESS+REALITY configuration |
| `shared/core/messaging` | Session/bootstrap orchestration, envelopes, receipts, groups, and encrypted voice media |
| `shared/core/discovery` | Invite and discovery contracts; decentralized discovery is not yet shipped |
| `shared/core/policy` | Shared product and policy rules |
| `apps/android` | Compose UI, Android services, notifications, network-change handling, and platform wiring |
| `services/relay` | Rust/Axum store-and-forward relay, prekeys, REST fallback, push registration, and encrypted media chunks |

The Android shell wires platform services to the shared modules; cryptographic
protocol code remains in `shared/core/crypto`, while message/session policy is
coordinated by `shared/core/messaging`. Module intent is recorded in
[ADR-002](adr/ADR-002-Shared-Core-Layout.md).

---

## 3. Cryptographic boundary

PHANTOM currently uses libsodium-backed primitives through Kotlin
Multiplatform bindings:

- X25519 for identity, ephemeral, signed-prekey, and ratchet DH operations;
- HKDF-SHA256 for the X3DH 3-DH/4-DH session secret;
- XSalsa20-Poly1305 (`SecretBox`) for ratcheted text-message encryption;
- XChaCha20-Poly1305-IETF for voice/media blobs;
- SHA-256/BLAKE2-family hashing where specified by the protocol code.

PHANTOM implements the protocol composition around those primitives. Audited
primitives do not make the composition audited: the custom X3DH/Double Ratchet
implementation, session lifecycle, storage transactions, envelope framing,
and media pipeline remain project code. The repository therefore makes no
claim of an independent cryptographic audit.

The initial-session path publishes signed and one-time prekeys to the relay,
consumes a bundle, derives an X3DH secret, and initializes a Double Ratchet.
Safety Numbers and QR verification provide the user-visible identity check.
Sealed Sender reduces identity exposure inside the message envelope; it does
not make an authenticated transport session invisible to the relay.

---

## 4. Data flow — 1:1 text

```
Alice's Android client                            Bob's Android client
──────────────────────                            ────────────────────
1. User composes text
2. Load or create session
   ├─ fetch/consume Bob's prekey bundle if needed
   ├─ X3DH 3-DH/4-DH bootstrap
   └─ initialize or advance Double Ratchet
3. Encrypt + authenticate message
4. Wrap protocol envelope; persist local state transactionally
5. Select a path
   ├─ Direct WSS
   ├─ WSS through embedded Xray/REALITY
   ├─ WSS through Tor onion (text-only mode)
   └─ REST send/poll when the WebSocket path is degraded
                         │
                         ▼
                  Rust/Axum relay
                  ├─ authenticates the session
                  ├─ queues opaque ciphertext for Bob
                  ├─ exposes WS and REST delivery paths
                  └─ removes acknowledged or expired envelopes
                         │
                         ▼
                                              6. Deduplicate + decrypt
                                              7. Commit message/session state
                                              8. Acknowledge delivery
                                              9. Render in Compose UI
```

WebSocket and REST are two delivery mechanisms for the same end-to-end
encrypted envelope. Switching between them does not change message crypto.
The REST path supports held polls where available and degrades to immediate
short-poll responses when its guardrails require it.

Receipts are encrypted protocol messages. The relay can delay, drop, replay,
or correlate ciphertext traffic, so the client owns deduplication, session
state, and user-visible delivery state.

---

## 5. Data flow — encrypted voice media

Voice notes use a separate encrypted-media pipeline:

1. The sender records audio locally and encrypts the complete blob with a
   fresh XChaCha20-Poly1305 key and nonce.
2. Ciphertext is split into bounded chunks and uploaded through authenticated
   `/media/*` endpoints.
3. A media manifest containing the decryption material and integrity digest
   travels inside the ratcheted end-to-end encrypted message channel.
4. The recipient downloads ciphertext chunks, reassembles and decrypts them,
   verifies the plaintext digest, and stores the local result.

The relay receives an opaque media identifier, chunk indices, sizes, timing,
and ciphertext. Media chunks are TTL-bounded and held in memory; this is not
durable object storage. On current `master`, the REST fallback and encrypted
media work together on the tested Tele2 LTE path. Tor remains intentionally
text-only.

---

## 6. Transport model

The adaptive transport layer separates the application protocol from the
outer path:

- **Direct WSS** is the lowest-overhead path on ordinary networks.
- **Xray VLESS+REALITY** wraps the relay path for networks where direct WSS is
  disrupted by DPI. This path was production-validated against TSPU on a real
  Russian mobile network; that result is evidence for the tested path and
  date, not a guarantee for every carrier or future blocking regime.
- **Tor v3 onion** is the strongest current network-origin hiding option and
  is deliberately scoped to text-only emergency delivery.
- **REST fallback** uses authenticated send/poll/ack endpoints when a network
  passes HTTPS but silently degrades long-lived WebSocket frames.

Runtime selection follows the user's privacy mode and observed path health;
it can re-walk the chain after a network change. See
[ADR-016](adr/ADR-016-tor-unified-push-hybrid-transport.md),
[ADR-019](adr/ADR-019-Xray-REALITY-Outer-Transport.md), and
[ADR-020](adr/ADR-020-Adaptive-Transport-Selection.md).

---

## 7. Relay and deployment topology

```
Android client
  ├─ Direct WSS / HTTPS ───────────────────────────┐
  ├─ embedded Xray → VLESS+REALITY listener ──────┤
  └─ embedded Tor → v3 onion service (text) ──────┤
                                                   ▼
                                      operator edge / Caddy
                                                   ▼
                                      phantom-relay (Rust/Axum)
```

The relay exposes health, authenticated WebSocket, prekey, REST delivery,
push-registration, and encrypted-media endpoints. Message queues, REST
sessions, idempotency caches, and media chunks are bounded in-memory state.
Prekey bundles and limited operational records (push registrations, abuse
reports, and blocklist state) use append-only files under the configured relay
state directory.

The operator can observe service and network metadata and can deny service.
The design goal is that the operator cannot decrypt correctly formed message
or media ciphertext. Deployment files and the current operational boundary are
documented in [deploy/README.md](../deploy/README.md).

---

## 8. Threat-model summary

- **Compromised relay:** can observe metadata, delay/drop/replay traffic, or
  return incorrect directory/prekey responses. It should not have message or
  media plaintext. Clients authenticate ciphertext, verify identities, track
  sequence state, and deduplicate deliveries.
- **Network observer:** Direct exposes a connection to the relay; REALITY
  changes the observable wire shape; Tor hides the client's network origin
  from the relay. None of these eliminates all timing and volume analysis.
- **Compromised unlocked device:** can read what the user can read. Android
  local storage uses SQLCipher and platform-keystore protection for data at
  rest, but this does not defeat a live root/OS compromise.
- **Cryptographic implementation risk:** the protocol composition is custom,
  Alpha-stage, and not independently audited. Known limitations and open
  security work must be evaluated before high-risk use.

The full scope and exclusions are in
[Threat_Model_v0.md](threat-model/Threat_Model_v0.md) and
[SECURITY.md](../SECURITY.md).

---

## 9. Current boundaries and future work

Shipped on current `master` includes the Android client, custom
libsodium-backed session crypto, Direct/REALITY/Tor/REST transport paths,
prekey bootstrap, encrypted local storage, 1:1 text, and encrypted voice
media. Some UI surfaces represent later roadmap features and should not be
read as proof that every represented workflow is production-ready.

Not current architecture:

- DHT discovery and offline BLE/Wi-Fi mesh (post-v1 directions);
- iOS and desktop release clients;
- full media or realtime calling over Tor;
- hardened multi-device identity and a public username directory;
- an independent third-party cryptographic/security audit.

For the live product state and release pointers, start with the
[README](../README.md), [KNOWN_ISSUES.md](../KNOWN_ISSUES.md), and
[ROADMAP.md](../ROADMAP.md).
