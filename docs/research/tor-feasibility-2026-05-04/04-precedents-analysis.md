# 04 — Precedents Analysis: FOSS Messengers Shipping Tor

*Research date: 2026-05-04. Author: PHANTOM research agent.*

## TL;DR

Six projects have meaningfully shipped Tor or onion-routed transport for messaging:
**Briar** (P2P, no relay, delay-tolerant), **Cwtch** (P2P + optional untrusted server),
**Ricochet Refresh** (pure P2P onion-to-onion), **SecureDrop** (hosted onion, large-scale ops),
**Quiet** (Tor + IPFS team chat), and **Session** (Tor-like onion routing on Oxen
Service Nodes — not Tor itself, but the closest "metadata-resistant relay" model).

Common lessons across all six:

1. **Battery and latency are the dominant complaints.** Briar drains battery
   roughly 4× faster than a server-based messenger; users repeatedly file battery
   bugs against Briar and Cwtch.
2. **Pure P2P over Tor produces "both peers must be online" semantics.** Briar,
   Cwtch P2P, and Ricochet all fight this; Cwtch's "untrusted server" mode and
   Quiet's IPFS layer were direct responses.
3. **Onion address = identity creates migration pain.** Once your contacts have
   your `.onion`, you can't move servers without re-adding everyone.
4. **Censorship robustness is not free with Tor.** obfs4 and WebTunnel bridges are
   blocked in Russia; bootstrap requires out-of-band bridge distribution.
5. **The closest precedent for PHANTOM's relay-mediated model is Cwtch's
   "untrusted server" mode and Session's swarm model**, not Briar/Ricochet's
   pure P2P.

PHANTOM should treat Briar as the cautionary tale (battery, P2P semantics that
PHANTOM intentionally rejected) and Cwtch + Session as the architectural
templates (relay/server runs as a Tor onion service; identities are independent
of transport).

---

## 1. Briar

### 1.1 Architecture

Briar is Tor-only when on the internet, with no central relay. Two contacts
each run a v3 onion service inside the app and dial each other's addresses.
The protocol stack — collectively the "Bramble" suite — includes BHP (Handshake),
BQP (QR Code), BRP (Rendezvous), BSP (Synchronisation), and BTP (Transport)
([Briar protocol stack wiki](https://code.briarproject.org/briar/briar/-/wikis/A-Quick-Overview-of-the-Protocol-Stack)).
BTP runs as a binary framed protocol over TCP over Tor, with session keys
rotated for forward secrecy and rekeyed from a master key derived during BHP
([BTP spec v4](https://code.briarproject.org/briar/briar-spec/blob/master/protocols/BTP.md)).

Briar is explicitly delay-tolerant: it accepts that both endpoints may not be
online simultaneously, and it falls back to Bluetooth or shared Wi-Fi when the
internet is down (its mesh story).

### 1.2 Identity

Identity is a long-term key pair plus the onion address derived for that contact.
QR-exchanged contacts ship a Briar link containing the keys, then BHP completes
a key-confirmed shared master key. Notably, Briar does not currently support
transferring a contact onion identifier to another device
([Wikipedia: Briar](https://en.wikipedia.org/wiki/Briar_(software))).
This is a hard binding of identity to onion: change device → lose the onion →
need to re-pair.

### 1.3 Performance reality

Independent reporting (and the project's own bug tracker) put Briar's mobile
battery cost at roughly 4× a server-based messenger
([byteiota analysis](https://byteiota.com/briar-offline-mesh-when-internet-shutdowns-cut-85m-off/)).
The drain is driven by a persistent foreground notification keeping Tor alive,
plus opportunistic Bluetooth scanning. Briar's docs make this explicit and
offer "only on Wi-Fi / only when charging" toggles
([Briar Quick Start](https://briarproject.org/quick-start/)).

Latency is "delay-tolerant by design": "the system doesn't try to be real-time
because it can't be" ([byteiota](https://byteiota.com/briar-offline-mesh-when-internet-shutdowns-cut-85m-off/)).
Onion rendezvous typically takes several seconds end-to-end — workable for
text, painful for the kind of UX expectations PHANTOM has set with active chats
and calls.

### 1.4 Adoption story and learnings

Briar passed two security audits (Cure53, then Radically Open Security Feb-Mar
2024 recommending production use)
([BleepingComputer](https://www.bleepingcomputer.com/news/security/briar-tor-based-messenger-passes-security-audit-enters-beta-stage/)).
It has a small but loyal user base in activist and journalist communities. Its
adoption ceiling is real-time UX: it can't compete with Signal/WhatsApp for
casual users.

In 2023, the Bramble Handshake Protocol (BHP) was found to lack forward
secrecy — CVE-2023-33982. The fix shipped in Briar 1.5.3 in 2023
([Briar 2023 security issues](https://briarproject.org/news/2023-three-security-issues-found-and-fixed/)).
Lesson: a hand-rolled handshake on top of Tor is a non-trivial attack surface.

### 1.5 Mapping to PHANTOM

- Battery model is the inverse of PHANTOM's — PHANTOM uses a relay specifically
  to keep clients lightweight. Briar's "every client is a server" model is what
  we explicitly opted out of.
- Identity model (key + onion bound together, no migration) is **anti-pattern**
  for PHANTOM. We must keep identity independent of `.onion`.
- Bramble BTP framing over a TCP-over-Tor pipe is conceptually identical to
  WSS-over-Tor and confirms the architecture works at scale.

---

## 2. Cwtch

### 2.1 Architecture

Cwtch is a Go-based messenger from Open Privacy Research Society. All
communication runs over Tor v3 onion services
([docs.cwtch.im](https://docs.cwtch.im/)). It supports two modes:

- **P2P 1:1**: two clients dial each other's onion addresses (Ricochet-style).
- **Group chat via untrusted server**: members upload encrypted, sealed messages
  to a Cwtch Server (also an onion service). Servers cannot tell which message
  belongs to which group, who members are, or read content
  ([Cwtch Server docs](https://docs.cwtch.im/security/components/cwtch/server/)).

This is the most relevant architectural precedent for PHANTOM. The Cwtch paper
explicitly frames the design as "Privacy Preserving Infrastructure for
Asynchronous, Decentralized, Multi-Party and Metadata Resistant Applications"
([cwtch.pdf](https://cwtch.im/cwtch.pdf)).

### 2.2 Identity

Cwtch identities are pseudonymous Ed25519 key pairs that any user can mint as
many times as they want. Identities are not bound to the onion address of a
server; the same identity can talk through any server
([Cwtch overview](https://openprivacy.ca/work/cwtch/)).
This is exactly the property PHANTOM needs.

### 2.3 Group chat through Tor

The "untrusted server" pattern works as a tagged-anonymous-broadcast over Tor:

- All group members poll the server's onion.
- Messages are delivered to all subscribers; clients drop messages they cannot
  decrypt.
- The server cannot link a message to a specific group.

Cost: O(n) bandwidth per message, since every member downloads every encrypted
blob. Cwtch accepts that cost in exchange for metadata resistance.

### 2.4 Performance and learnings

Cwtch shares Briar's latency characteristics — Tor onion handshake dominates.
The team's iteration arc is informative: they started P2P-only (Ricochet-like),
hit the "both peers must be online" wall, then introduced untrusted servers as
the asynchronous escape hatch. The whole Cwtch protocol paper exists to
formalize that decision.

### 2.5 Mapping to PHANTOM

- Cwtch's "client identities, server identities, both are onions, bound only at
  rendezvous time" is the right pattern for PHANTOM.
- The relay running as an onion service (with optional plain WSS for fallback)
  cleanly fits PHANTOM's Rust relay.
- Group chat broadcast cost is a warning: when PHANTOM tackles groups, the
  Cwtch broadcast pattern will trade bandwidth for metadata privacy, and that
  trade should be a conscious decision.

---

## 3. SecureDrop

### 3.1 Architecture

SecureDrop is the largest production Tor onion service deployment in journalism.
Each newsroom runs two physical servers — an Application Server (the public
onion) and a Monitor Server — plus an air-gapped Secure Viewing Station for
journalists ([SecureDrop docs](https://docs.securedrop.org/en/stable/what_is_securedrop.html)).
Sources reach the public onion via Tor Browser; journalists reach an
authenticated onion via a Tails workstation.

### 3.2 Lessons for PHANTOM

SecureDrop is not a messenger, but it is the most operationally mature case
study of "hosted Tor service at scale":

- Even with a Tor-only architecture, **timing, file size, and conversation
  flow leak metadata**. The SecureDrop team's docs explicitly warn that "even
  assuming a Tor connection ... metadata such as timing information,
  conversation flows, attachment sizes, or filenames on the server side can
  still leak information"
  ([securedrop.org/news](https://securedrop.org/news/anatomy-of-a-whistleblowing-system/)).
  This bites PHANTOM voice messages directly (size patterns).
- **Operational complexity is high.** SecureDrop deployments require trained
  admins, hardware reproducibility, and multi-machine isolation. PHANTOM cannot
  ask end-users to do any of this; the relay operator must absorb it.
- **Authenticated onion services for trusted parties are valuable.** SecureDrop
  uses v3 client authorization for journalists. PHANTOM could use the same
  primitive to keep the relay onion address private, sharing the auth key only
  with paired devices.

---

## 4. Ricochet Refresh

### 4.1 Architecture

Ricochet Refresh is the spiritual successor to Ricochet (the original died in
2016 due to lack of maintainers). It is pure P2P onion-to-onion: identity =
onion address ([ricochetrefresh.net](https://www.ricochetrefresh.net/)). No
relay, no server, no asynchronous storage. If both peers aren't online at the
same time, the message waits.

### 4.2 Status (2025–2026)

Refresh has revived with a 3.1.0-alpha series in late 2025, focused on an
improved backend that hardens against timing analysis and gives users explicit
control over online visibility
([Tor Forum: Refresh 3.1 alpha](https://forum.torproject.org/t/new-release-ricochet-refresh-3-1-0-alpha-0/20860)).
The rewrite uses Gosling, a library specifically for "private and secure p2p
applications" pitched at State of the Onion 2024
([Tor blog: State of the Onion 2024](https://blog.torproject.org/state-of-the-onion-2024/)).

### 4.3 Why Ricochet died and what that means

Ricochet died for two reasons: (1) maintainer departure, (2) v2 onion services
being deprecated, leaving the v2-only codebase unusable. The fix was a full
rewrite for v3.

For PHANTOM, the lesson is operational: **onion address formats change**.
If we bake the current 56-char v3 base32 format into our identity payload
without a version field, we will eat the same migration cost when v4 lands.

### 4.4 Mapping to PHANTOM

PHANTOM is intentionally not P2P. Ricochet is the "pure form" of what PHANTOM
rejected — but Ricochet's identity = onion model is the **anti-pattern** worth
naming explicitly. PHANTOM keeps the existing Curve25519 identity and treats
the relay's onion as a transport-layer concern, not an identity concern.

---

## 5. Session (one comparison point)

Session is a Signal fork that replaced direct WSS to a server with a 3-hop
onion-routed protocol over the Oxen Service Node network ("onion requests"),
plus a swarm of redundant nodes that store messages until the recipient comes
online ([getsession.org/blog](https://getsession.org/blog/onion-requests-session-new-message-routing-solution)).
Each user's swarm is selected by a deterministic function of their public key.

It is not Tor — but the architectural lessons are the most directly applicable
to PHANTOM:

- **Onion routing for control plane is workable on mobile** at acceptable
  battery cost, because each hop is short-lived and the connection is not held
  open like Briar's.
- **Mailbox-style storage solves "both peers online" without Briar's pain.**
  Session's swarm is conceptually identical to PHANTOM's relay queue, just
  decentralized.
- **Public-key-only identities decouple from transport.** Session identities
  are X25519 keys; the onion routing layer is invisible to identity. This is
  exactly PHANTOM's current design.

The TUM evaluation paper notes Session's metadata protection is meaningfully
stronger than Signal's, at the cost of higher latency
([TUM NET-2020-11-1](https://www.net.in.tum.de/fileadmin/TUM/NET/NET-2020-11-1/NET-2020-11-1_05.pdf)).

---

## 6. Quiet (tryquiet.org)

Quiet is a Slack/Discord-style team chat over Tor + IPFS. Each community has
an owner who is the certificate authority; peers connect to each other's onion
services, share data via IPFS+OrbitDB CRDTs, and authenticate via PKI.js
certificates ([github.com/TryQuiet/quiet](https://github.com/TryQuiet/quiet)).

Relevance to PHANTOM:

- Quiet is the most recent attempt (2022–2026) at productizing Tor messaging,
  and it has chosen a hybrid model: P2P onion connections, but synced state
  via IPFS — basically reinventing a CRDT-based relay layer.
- It validates that **state synchronization over Tor needs structure.** Naive
  pull-from-peer doesn't scale; you need a sync protocol.
- Their UX iteration shows the persistent pain of slow Tor handshakes for
  new-message latency.

---

## 7. Synthesis: Patterns Across All Six

### 7.1 Common architectural patterns

| Pattern | Briar | Cwtch | Ricochet | SecureDrop | Quiet | Session |
|---|---|---|---|---|---|---|
| Identity = onion address | yes | no | yes | n/a | no | no |
| Pure P2P (no relay) | yes | optional | yes | no | partial | no |
| Asynchronous via untrusted server | no | yes | no | yes | yes | yes |
| Onion service for server side | n/a | yes | n/a | yes | n/a | n/a |
| Custom routing (not Tor) | no | no | no | no | no | yes |
| QR-based contact exchange | yes | yes | yes | no | partial | yes |

### 7.2 Five patterns that recur

1. **Decouple identity from transport.** Cwtch, Quiet, and Session do this;
   Briar and Ricochet don't, and pay for it on device migration.
2. **Run the relay as the onion service, not the client.** Briar has every
   client publish an onion → battery cost. SecureDrop, Cwtch (group), and a
   PHANTOM-style relay only publish on the server side → much cheaper.
3. **Plan for onion version migration.** v2 → v3 already happened. Treat the
   onion address as a versioned field.
4. **Tor alone doesn't give metadata privacy.** Timing, size, and pattern
   leakage requires application-layer countermeasures (padding, batching).
5. **Bootstrap is the censorship choke point.** Every project relies on
   out-of-band bridge distribution in Russia/Iran/China. The first connection
   problem is unsolved at the project level.

### 7.3 Closest match to PHANTOM

PHANTOM is **relay-mediated, asynchronous, with persistent X25519 identities
shared via QR**. The two closest precedents are:

- **Cwtch (untrusted server mode)** — same shape: client identities ≠ server
  onion, server is just a metadata-resistant message relay. PHANTOM should
  read the Cwtch protocol paper as a primary architectural reference.
- **Session (swarm + onion requests)** — same shape: identity = X25519 public
  key, transport is onion-routed, mailbox stores messages. PHANTOM doesn't
  need Session's blockchain-backed Sybil resistance because PHANTOM operates
  trusted relays.

Briar and Ricochet are useful as **anti-patterns** to consciously reject, and
SecureDrop is useful as the **operational discipline reference** for running
production onion infrastructure.

---

## 8. Recommendations for PHANTOM

1. **Adopt the Cwtch architecture pattern.** Relay publishes a v3 onion;
   clients dial it. Identity stays X25519 public key. No client-side onions.
2. **Read the Cwtch paper as a primary reference for group chat design** when
   PHANTOM tackles groups (post-Alpha).
3. **Version the onion address in QR payloads** to avoid the v2→v3 migration
   pain Ricochet ate.
4. **Treat battery as a P0 concern.** Don't keep an always-open Tor circuit
   like Briar. Use UnifiedPush for wakeup (see deliverable 07) and bring up
   Tor only when there's traffic.
5. **Plan for application-layer metadata defense** (envelope padding, fixed
   chunk sizes, decoupled timing) — Tor is necessary but not sufficient.
   SecureDrop's warning applies directly.
6. **Do not bind identity to onion.** Never ship a build where changing the
   relay address invalidates user identities.

---

## 9. Open questions

- How does Cwtch handle relay rotation when an operator wants to migrate
  servers? (Worth reading the cwtch.pdf protocol section in detail.)
- Does Session's onion-request approach (custom 3-hop routing on a known
  service-node set) outperform real Tor for our threat model? Probably yes
  on latency, at the cost of building our own anonymity network — out of
  scope for PHANTOM Alpha.
- For Briar: what's the actual measured latency of first-message-after-idle
  under a 4G connection? We need real numbers to set user expectations.

---

## Sources

- [Briar — How it works](https://briarproject.org/how-it-works/)
- [Briar — Quick Start (battery options)](https://briarproject.org/quick-start/)
- [Briar protocol stack wiki](https://code.briarproject.org/briar/briar/-/wikis/A-Quick-Overview-of-the-Protocol-Stack)
- [Bramble Transport Protocol v4](https://code.briarproject.org/briar/briar-spec/blob/master/protocols/BTP.md)
- [Briar 2023 security fixes (CVE-2023-33982)](https://briarproject.org/news/2023-three-security-issues-found-and-fixed/)
- [BleepingComputer — Briar passes security audit](https://www.bleepingcomputer.com/news/security/briar-tor-based-messenger-passes-security-audit-enters-beta-stage/)
- [byteiota — Briar Offline Mesh](https://byteiota.com/briar-offline-mesh-when-internet-shutdowns-cut-85m-off/)
- [Wikipedia — Briar (software)](https://en.wikipedia.org/wiki/Briar_(software))
- [Cwtch — docs](https://docs.cwtch.im/)
- [Cwtch Server architecture](https://docs.cwtch.im/security/components/cwtch/server/)
- [Cwtch protocol paper (PDF)](https://cwtch.im/cwtch.pdf)
- [Open Privacy — Cwtch](https://openprivacy.ca/work/cwtch/)
- [SecureDrop — what is it](https://docs.securedrop.org/en/stable/what_is_securedrop.html)
- [SecureDrop — Anatomy of a whistleblowing system](https://securedrop.org/news/anatomy-of-a-whistleblowing-system/)
- [Ricochet Refresh](https://www.ricochetrefresh.net/)
- [Tor Forum — Ricochet Refresh 3.1.0-alpha.0](https://forum.torproject.org/t/new-release-ricochet-refresh-3-1-0-alpha-0/20860)
- [Tor blog — State of the Onion 2024](https://blog.torproject.org/state-of-the-onion-2024/)
- [Session — Onion Requests](https://getsession.org/blog/onion-requests-session-new-message-routing-solution)
- [Session FAQ](https://getsession.org/faq)
- [TUM evaluation of Session (PDF)](https://www.net.in.tum.de/fileadmin/TUM/NET/NET-2020-11-1/NET-2020-11-1_05.pdf)
- [Quiet — GitHub](https://github.com/TryQuiet/quiet)
- [Quiet — tryquiet.org](https://tryquiet.org/)
