# 06 — Security Analysis: Tor for PHANTOM

*Research date: 2026-05-04. Author: PHANTOM research agent.*

## TL;DR

Tor gives PHANTOM three concrete properties it does not currently have:
hides the user's IP from the relay, hides the relay's IP from network
observers (when it runs as an onion service), and resists naive ISP-level
blocking. It does **not** give PHANTOM resistance to a global passive
adversary, endpoint compromise, traffic-pattern fingerprinting, or
deanonymization through guard discovery without **vanguards**.

Specific findings for PHANTOM:

- **Onion + identity + relay address fits in a QR code** at QR version 8–10
  with high error correction — readable for in-person scan.
- **Identity should NOT bind to onion address.** The onion is a transport
  attribute. PHANTOM's existing X25519 identity must remain the canonical
  identifier; onion is a per-relay metadata field.
- **WSS over Tor is the correct architecture**, not Tor over WSS. Tunnel TCP
  to the relay's `.onion`, run WSS *inside* the Tor circuit. This matches
  Briar/Cwtch and lets us reuse existing relay code.
- **Single relay onion** with optional **per-conversation client-auth keys**
  is the right tradeoff for v1. Per-user onions don't buy enough at our scale
  to justify the complexity.
- **Vanguards-Lite (Proposal 333) is our baseline.** Built into stable
  C-tor since 0.4.7.1-alpha and **on by default** for every onion service
  — zero deployment work, zero performance cost. Defends against guard
  discovery at the L2 layer, the path Tor Project upstreamed instead of
  the original full Vanguards (Proposal 292) addon. See no-go check
  `no-go-checks/02-vanguards-deployability.md` for why full Prop 292 is
  not deployable today (Python addon `mikeperry-tor/vanguards`
  unmaintained since 2021, removed from Debian Trixie). Track Arti
  migration as the trigger for evaluating full Vanguards properties
  again — Arti landed Vanguards support in 1.2.2, August 2024.
- **Application-layer countermeasures defend against SUMo, not Vanguards.**
  Padding, fixed-size envelopes, batched delivery are mandatory because
  the SUMo flow-correlation attack (NDSS 2024) operates at a layer Vanguards
  cannot reach. Vanguards reduces *guard-discovery* probability — making
  obtaining a vantage point harder — but does not break correlation once
  vantage exists. Real SUMo defences are circuit-padding machines (already
  shipped in Tor as `circpadding-builtin`) plus short-session usage and
  in-app application-layer padding.
  ([NDSS 2024 paper](https://www.ndss-symposium.org/wp-content/uploads/2024-337-paper.pdf)).

PHANTOM's existing crypto stack (Curve25519 X25519 + Double Ratchet over
libsodium) is independent of transport and "just works" over Tor with no
changes. There are subtle timing issues to watch (replay window during long
reconnects), but no breaking compatibility problems.

---

## 1. Tor threat model for messengers

### 1.1 What Tor protects against

When the client connects to a v3 onion service, Tor gives a 6-relay circuit
(3 chosen by client, 3 chosen by service, meeting at a rendezvous point)
([Tor Project: How onion services work](https://community.torproject.org/onion-services/overview/)).
The protections:

- **The relay (server) does not see the user's IP.** It sees a Tor exit (or
  rather, the rendezvous-point side circuit), not the client.
- **Network observers (ISPs, telecom DPI) do not see the relay's IP** if the
  relay is reached via its onion service. They see traffic to a Tor guard.
- **Even Tor relays in the circuit cannot see both ends.** Each relay knows
  only its predecessor and successor.
- **DNS does not leak** — onion lookup goes through the Tor directory system,
  not the local resolver.
- **Endpoint authentication is built-in.** The onion address is the ed25519
  public key, so reaching `xyz.onion` cryptographically authenticates the
  relay — no CA, no MITM via certificate.

### 1.2 What Tor does NOT protect against

- **Endpoint compromise.** If the client device is compromised (malware,
  forensic seizure), Tor is irrelevant. PHANTOM's at-rest crypto must hold
  on its own.
- **Global passive adversary.** A nation-state with visibility into both the
  client's guard and the relay's guard can perform flow correlation.
- **Traffic correlation attacks via timing/volume.** The 2024 NDSS paper
  "Flow Correlation Attacks on Tor Onion Service Sessions with Sliding
  Subset Sum (SUMo)" demonstrated practical deanonymization with multiple
  colluding ISPs ([NDSS 2024](https://www.ndss-symposium.org/wp-content/uploads/2024-337-paper.pdf)).
- **Hidden service guard discovery → deanonymization.** An adversary who
  can probe the onion service over time can identify its guards and then
  target attacks at those specific guards. The Tor team historically
  called this "the most serious threat that v3 onion services currently
  face" ([Vanguards Add-On announcement, 2018](https://blog.torproject.org/announcing-vanguards-add-onion-services/)).
  Modern stable Tor (>= 0.4.7.1-alpha) ships **Vanguards-Lite (Proposal
  333) on by default** for every onion service, which materially reduces
  this attack surface at the L2 layer. Full Vanguards (Prop 292) is no
  longer recommended deployment because the Python addon is unmaintained
  (last real commit 2021); see no-go check
  `no-go-checks/02-vanguards-deployability.md`.
- **Bridge enumeration.** Russian DPI is actively enumerating obfs4 and
  WebTunnel bridges ([Tor blog: 2025 censorship recap](https://blog.torproject.org/staying-ahead-of-censors-2025/)).
  This is a bootstrap problem, not an in-circuit problem.
- **Application-level metadata.** SecureDrop's docs warn: "even assuming a
  Tor connection ... metadata such as timing information, conversation flows,
  attachment sizes, or filenames on the server side can still leak"
  ([securedrop.org](https://securedrop.org/news/anatomy-of-a-whistleblowing-system/)).

### 1.3 Specific known attacks 2020–2026

| Attack | Year | What it breaks | Mitigation |
|---|---|---|---|
| Guard discovery (general) | ongoing | onion service deanon | Vanguards-Lite (Proposal 333, built-in to stable Tor by default) |
| SUMo flow correlation | 2024 | onion session deanon by colluding ISPs | application-level padding/timing defenses |
| obfs4 enumeration in Russia | 2022–ongoing | bootstrap blocked | rotate bridges, WebTunnel, Snowflake |
| WebTunnel enumeration in Russia | mid-2025 | bootstrap on commodity ASNs | bridges on lesser-known ASNs, Telegram distributor |
| Tor v2 deprecation | 2021 | all v2 addresses dead | already migrated, watch for v3 → v4 future |

References: [PETS 2024 Onion Services in the Wild](https://petsymposium.org/popets/2024/popets-2024-0117.pdf),
[NDSS 2024 SUMo](https://www.ndss-symposium.org/wp-content/uploads/2024-337-paper.pdf),
[Vanguards Whonix entry](https://www.whonix.org/wiki/Vanguards),
[Arti vanguards announcement](https://blog.torproject.org/announcing-vanguards-for-arti/).

---

## 2. PHANTOM-specific threat surface

### 2.1 Onion address distribution and QR codes

A v3 onion address is `base32(PUBKEY || CHECKSUM || VERSION) + ".onion"` =
**56 base32 chars + ".onion"** = **62 characters total**
([Tor spec: encoding onion addresses](https://spec.torproject.org/rend-spec/encoding-onion-addresses.html)).

PHANTOM's QR code currently encodes:
- Curve25519 X25519 identity public key: 32 bytes (44 base64 chars or 64 hex)
- Username: variable, ~32 chars
- Relay WSS URL: ~32 chars
- (proposed) Relay v3 onion address: 62 chars
- Optional schema version, signature, verification SAS seed

Total payload before encoding: ~250 bytes. With base64 + URI structure: ~340
chars. This fits in a **QR version 10 with error correction level Q** (or
version 12 with H), giving a 57×57 to 65×65 module grid.

**Verdict:** fits. Readability under in-person printed-paper conditions has
been validated by Briar, Cwtch, and Session, all of which encode similar or
larger payloads at similar QR densities. PHANTOM is not pushing the limit.

### 2.2 Migration path for existing users without onion address

The deployed Alpha-1 QR payload doesn't carry an onion address. Migration
options:

1. **Backward-compatible payload.** New QR payloads include an optional
   onion field. Old clients ignore unknown fields. New clients with old
   contacts simply don't have the onion → fall back to direct WSS for those
   conversations.
2. **Side-channel onion delivery.** Once paired, the relay can deliver its
   own onion address to clients in the next envelope. After that exchange,
   clients use Tor for all future traffic with that contact.
3. **Onion address comes from the relay, not the contact.** Since PHANTOM
   uses a relay, the onion is a relay-level attribute, not a per-contact
   one. Clients learn it once when they connect to the relay (or via app
   config), and use it for *all* contacts on that relay.

Recommendation: **option 3 is canonical**. The onion lives in the client's
relay configuration (or the relay advertises it on first WSS connect). QR
codes still carry it for *out-of-band relay discovery* — i.e., if a contact
is on a different relay, the QR tells the scanning client which onion to
dial. But for same-relay contacts (the common case), no per-contact onion
is needed.

### 2.3 Identity binding — should `.onion` be part of identity?

**No.** Three reasons:

1. **Relay migration must work.** If an operator has to move
   `relay.phntm.pro` to new hardware (which has new keys, since the onion
   private key probably never leaves the old box), users' identities must
   travel with them.
2. **Multi-relay future.** PHANTOM's roadmap includes federated relays
   (multi-instance). Identities have to be portable across relays.
3. **Ricochet Refresh proves the cost.** Ricochet bound identity to onion
   address; the v2 → v3 migration was a forced re-onboarding. Don't repeat
   that ([Ricochet/Wikipedia](https://en.wikipedia.org/wiki/Ricochet_(software))).

Concrete rule: **identity = X25519 public key, hashed for fingerprint
display. Onion address is metadata. Changing onion does NOT change identity.**

This is the same rule Cwtch and Session apply.

### 2.4 X3DH / Double Ratchet compatibility

The transport is independent of the crypto. X3DH prekey exchange and Double
Ratchet message encryption are TCP-bytestream-agnostic; whether the bytes
travel over WSS-direct or WSS-over-Tor is invisible to the protocol.

Subtle issues to watch:

- **Replay window during reconnect.** Tor circuit failures and rebuilds can
  cause an envelope to land twice (once via the dying circuit, once via the
  retry). The relay's idempotency on envelope IDs should already handle
  this — confirm.
- **Prekey publish timing.** First-launch publishes a batch of one-time
  prekeys. Over Tor this is slow; the user shouldn't be blocked from sending
  their first message waiting for prekey publish to finish. Make it
  background work.
- **Forward secrecy on long Tor reconnects.** If a Tor circuit dies and
  rebuilds 10 minutes later, the Double Ratchet's chain has not advanced;
  no problem there. But out-of-order delivery from buffered envelopes during
  reconnect must be handled — the existing skipped-message-key cache should
  cover this.

No blocking issues.

### 2.5 Tor over WSS vs WSS over Tor

Two confusingly-similar names, very different architectures:

**Tor over WSS** (rare): the client opens WSS to a public endpoint, and inside
that WSS pipe runs the Tor protocol. Used by some pluggable transports
(WebTunnel is essentially this). Purpose: hide the fact that you're using
Tor from observers who only see HTTPS.

**WSS over Tor** (what we want): the client opens a Tor circuit to the
relay's `.onion`, and inside that circuit speaks WSS to the relay. Purpose:
hide the relay's IP, hide the user's IP, hide the connection from the local
network.

For PHANTOM's data plane: **WSS over Tor.** The client uses Arti's SOCKS5
or an embedded Tor channel API to dial `<relay-onion>:443`, then runs the
existing WSS protocol over that pipe. Server-side: the relay binds an onion
service that forwards to the same WSS port that already exists, so the relay
code does not need to know it's being reached over Tor.

WebTunnel (Tor over WSS) is used **only at the bridge step**, not for
PHANTOM application traffic.

### 2.6 Per-conversation onion vs single relay onion

| Property | Single relay onion | Per-user onion | Per-conversation onion |
|---|---|---|---|
| Operational complexity | low | high | very high |
| Relay IP hidden | yes | yes | yes |
| User IP hidden from relay | yes | yes | yes |
| Relay can correlate "user A talks to user B" | yes (sees both) | yes (sees both connect) | yes (still sees both) |
| Compromise of one onion key reveals | the relay | one user | one conversation |
| Briar | n/a | yes | n/a |
| Cwtch P2P | n/a | yes | n/a |
| Cwtch Server | yes | n/a | n/a |
| Session | n/a (swarm) | n/a | n/a |
| SecureDrop | yes (+ client auth) | n/a | n/a |

Per-user onions don't actually buy much for PHANTOM's threat model: the
relay already sees *which envelope went to which X25519 fingerprint*, so the
onion identity isn't load-bearing in the "who talks to whom" sense — the
ratchet keys are. Per-user onions would add ops complexity and battery cost
(every client running a hidden service) for marginal gain.

**Recommendation: single relay onion**, with **client authorization** as a
hardening layer for the trusted-device channel. Clients receive the auth
key during pairing; the onion is unreachable to anyone without it. This
matches SecureDrop's journalist-facing onion model.

---

## 3. Vanguards and the relay

PHANTOM uses **Vanguards-Lite (Proposal 333)** as its baseline guard-
discovery defence. It is built into stable C-tor since 0.4.7.1-alpha and
**enabled by default** on every onion service — zero deployment work,
zero performance cost. The PHANTOM relay's onion service inherits this
defence the moment we run a recent Tor.

The full Vanguards system (Proposal 292) — L2/L3 layered guard rotation,
bandguards monitoring, rendezvous-point rotation — was historically the
strongest recommendation but is **not deployable as a 2026 dependency**.
The Python addon `mikeperry-tor/vanguards` has not had a real code
change since July 2021, has zero releases, has been removed from Debian
Trixie, and Tor Project has marked Proposal 292 itself as superseded by
Lite as the in-tree path. See `no-go-checks/02-vanguards-deployability.md`
for the audit trail.

Arti has integrated vanguard support since 1.2.2 (August 2024)
([Arti vanguards announcement](https://blog.torproject.org/announcing-vanguards-for-arti/)).
**Track Arti maturity as the trigger for re-evaluating full Vanguards
properties** — when Arti reaches HS-hosting parity with C-tor and its
mobile binary size is acceptable, migration brings full Vanguards
back as a deployable option.

For the SUMo flow-correlation attack specifically (NDSS 2024), Vanguards
is **not** the right defence — Vanguards reduces guard-discovery
probability, while SUMo operates at a layer Vanguards cannot reach
(end-to-end traffic correlation given AS-level vantage). The actual
SUMo defences are circuit-padding machines (already shipped in Tor as
`circpadding-builtin`), short sessions, and the application-layer
padding described in section 4 below.

---

## 4. Application-layer countermeasures

Tor doesn't pad. PHANTOM should pad.

### 4.1 What leaks through Tor

- **Envelope size.** A 30-second voice message vs a one-line text vs a typing
  notification all have characteristic sizes after compression and AEAD
  framing. An observer of the relay's onion guards can fingerprint the
  message type from sizes alone.
- **Envelope timing.** Real-time chat creates burst patterns. A user who sends
  three messages in 30 seconds, pauses 10 minutes, sends one, etc., creates
  a recognizable signature.
- **Connection lifecycle.** "User opened app" → "user closed app" pattern
  visible to whoever sees the Tor entry guard.

### 4.2 What we should do

- **Fixed-size envelope chunks.** PHANTOM already chunks voice into 8 KB
  pieces (per the recent fix/voice-chunk commit). Extend to all envelope
  types: pad short messages to 1 KB, voice to 8 KB chunks, files to fixed
  multiples. (This is exactly what Cwtch's protocol paper recommends.)
- **Cover traffic / dummy packets** during idle: optional, expensive.
  Probably out of scope for v1.
- **Decoupled send timing.** Where UX permits, jitter envelope sends by
  ±2 seconds. Probably noticeable for voice, not for text.

These are nice-to-haves layered on top of Tor, not replacements. Without
Tor they buy little; with Tor they meaningfully raise the SUMo bar.

---

## 5. Recommendations for PHANTOM

1. **Architecture: WSS over Tor.** Client tunnels TCP to relay onion via
   Arti, then speaks existing WSS protocol. Relay code unchanged on the wire.
2. **Identity stays X25519. Onion is a relay attribute.** No identity binding
   to `.onion`.
3. **Single relay onion + client authorization** for the trusted-device
   channel. Auth key shipped in pairing payload.
4. **Vanguards-Lite is on by default in stable Tor** — no extra config
   work. Track Arti maturity as the trigger for evaluating full
   Vanguards (Prop 292) properties later. Full Prop 292 deployment via
   the Python addon is **not** recommended (unmaintained since 2021).
5. **Pad envelopes to fixed sizes** (1 KB / 8 KB / 64 KB tiers). PHANTOM
   already does this for voice; extend.
6. **Document explicitly** in user-facing privacy copy what Tor protects and
   what it does not. Don't oversell.
7. **Plan for v3 → v4 onion migration.** Treat the onion address as a
   versioned, optional field in QR payloads.

---

## 6. Open questions

- What is Arti's mobile vanguards support specifically? The
  announcement covers desktop; mobile config story unclear and
  needs verification before Alpha-2 ship.
- Should the relay rotate its onion address periodically (e.g., quarterly)
  for forward secrecy of the onion key itself? If yes, we need a
  client-side mechanism to learn the new onion before the old one dies.
- How do we measure SUMo-class flow correlation risk in practice? Probably
  out of scope for our team — accept the academic mitigations and move on.
- Should client authorization be required from day one, or shipped as
  hardening in v2? Required-from-day-one is more secure but breaks
  "scan QR and chat" flow; v2 hardening is more pragmatic.

---

## Sources

- [Tor Project — How do Onion Services work?](https://community.torproject.org/onion-services/overview/)
- [Tor Specs — Encoding onion addresses](https://spec.torproject.org/rend-spec/encoding-onion-addresses.html)
- [Tor Specs — Rendezvous protocol](https://spec.torproject.org/rend-spec/rendezvous-protocol.html)
- [Wikipedia — .onion](https://en.wikipedia.org/wiki/.onion)
- [Tor Project — Client Authorization for v3 onion services](https://community.torproject.org/onion-services/advanced/client-auth/)
- [Tor blog — Announcing the Vanguards Add-On for Onion Services](https://blog.torproject.org/announcing-vanguards-add-onion-services/)
- [Tor blog — Announcing Vanguards Support in Arti](https://blog.torproject.org/announcing-vanguards-for-arti/)
- [Whonix — Vanguards](https://www.whonix.org/wiki/Vanguards)
- [NDSS 2024 — Flow Correlation Attacks on Tor Onion Service Sessions with Sliding Subset Sum (SUMo) (PDF)](https://www.ndss-symposium.org/wp-content/uploads/2024-337-paper.pdf)
- [PETS 2024 — Onion Services in the Wild: A Study of Deanonymization Attacks (PDF)](https://petsymposium.org/popets/2024/popets-2024-0117.pdf)
- [Tor blog — Staying ahead of censors in 2025](https://blog.torproject.org/staying-ahead-of-censors-2025/)
- [Tor blog — Tor in Russia: a call for WebTunnel bridges](https://blog.torproject.org/call-for-webtunnel-bridges/)
- [SecureDrop — Anatomy of a whistleblowing system](https://securedrop.org/news/anatomy-of-a-whistleblowing-system/)
- [Cwtch protocol paper (PDF)](https://cwtch.im/cwtch.pdf)
- [Tor blog — Arti 1.8.0 release](https://blog.torproject.org/arti_1_8_0_released/)
- [Wikipedia — Ricochet (software)](https://en.wikipedia.org/wiki/Ricochet_(software))
