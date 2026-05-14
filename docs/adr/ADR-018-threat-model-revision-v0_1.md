# ADR-018: Threat Model v0.1 revision (Tor + UnifiedPush)

Status: accepted (2026-05-04, feat/tor-unified-push-transport)
Layer: docs/threat-model
Companion to: ADR-016 (Tor + UnifiedPush hybrid architecture)
Supersedes (in part): Threat Model v0 sections on transport metadata,
"VPN required for cellular Russia" caveat, ADR-013 firmware-radio-
parking diagnosis
Related: ADR-001 (System Boundaries — extended)

> **Renumbered from ADR-017 on 2026-05-14** due to a number collision
> with `ADR-017-senderkey-signing-removal.md`. The SenderKey ADR is the
> load-bearing ADR-017; this document is its companion-by-coincidence and
> moves to ADR-018. No content changed.
>
> **Looking for a different "ADR-018"?** During 2026 Q1 the number
> ADR-018 was reserved (in scattered planning docs and a few code
> comments) for several aspirational ADRs that were never written under
> that number. If a reference led you here looking for one of the
> following topics, see the actual document instead:
>
> - **Per-User Signed Challenge Auth** — see [ADR-027](ADR-027-Per-User-Signed-Challenge-Auth.md).
> - **Briar Tor stack rationale / kmp-tor pluggable-transport gap** — see [ADR-016](ADR-016-tor-unified-push-hybrid-transport.md) §"Why kmp-tor 2.6.0" plus `docs/research/tor-feasibility-2026-05-04/no-go-checks/01-kmp-tor-arm32.md`.
> - **Tor stage progression / Stage 5E REALITY** — see [ADR-019](ADR-019-Xray-REALITY-Outer-Transport.md).
> - **Onionwrapper migration / why we run our own bridge** — see [ADR-016](ADR-016-tor-unified-push-hybrid-transport.md) + `docs/operations/TOR_STACK_MAINTENANCE.md`.
> - **Relay PreKey storage SQL migration** — see [ADR-021](ADR-021-relay-prekey-sql-migration.md) (Reserved slot, content pending).
>
> The dangling code-level and config-level references that pointed
> here were cleaned up across PRs #139 and #140 (2026-05-14); a few
> historical refs are deliberately left intact in append-only artefacts
> (`docs/PROJECT_LOG.md` session journal, `docs/project/PROJECT_STATUS
> _SNAPSHOT_2026_05_09.md`) per the immutable-journal protocol.

## Context

Threat Model v0 (2026 Q1) was written before:

1. The 4-test matrix on 2026-05-04 disproved the Tecno-firmware-radio-
   parking diagnosis (ADR-013 was wrong — symptom appears on stable PC
   emulator on the same network, not just Tecno).
2. ADR-014 (TCP keepalive) attempted Layer 2 fix and only half-worked.
3. ADR-016 introduced the Tor + UnifiedPush hybrid architecture as the
   real fix.

Several explicit promises in v0 are now either inaccurate, oversold, or
under-specified relative to the new transport:

- "VPN required for voice messages on cellular Russia" — was honest
  about the limitation but framed it as a workaround, not a permanent
  architectural property.
- "No third-party metadata anywhere in the system" — true today, but
  the introduction of UnifiedPush distributor adds a new metadata
  surface that needs explicit honesty even though it is self-hosted.
- "Vanguards (Proposal 292) deployed on relay" — not deployable in
  2026; only Vanguards-Lite (Prop 333) is, and it is built into stable
  Tor by default, not a manual config knob.
- Application-layer padding requirement was implicit in the chunking
  story; now needs to be a first-class threat-model statement because
  SUMo (NDSS 2024) made it concretely necessary.
- Global passive adversary correlation was acknowledged as out-of-scope
  but the boundary needs sharper drawing.

## Decision

Issue **Threat Model v0.1** with the following changes. v0 stays in the
repository for audit traceability; v0.1 supersedes for current product
behaviour and Privacy Policy text.

### 1. Adversary classes — refined

| Class | v0 status | v0.1 status |
|---|---|---|
| Network observer (carrier ISP, transit) | in scope | in scope; further mitigated by Tor onion in Tor modes |
| Malicious relay operator | in scope (relay sees ciphertext only) | unchanged |
| Identity-key compromise (single device) | in scope | unchanged |
| Aggressive-OEM Android fleet attacker | in scope | unchanged |
| Carrier silent-drop / CGN / TSPU (Russia) | not explicit in v0 | **in scope, mitigated by Tor + bundled bridges** |
| **UnifiedPush distributor** | new | **in scope, self-hosted; sees push timing only** |
| Compromised endpoint | out of scope | unchanged |
| Global passive adversary (correlation) | out of scope | **explicit — Tor mode does not protect against this** |
| State-level forensic recovery on device | out of scope | unchanged |

### 2. Transport metadata — explicit per channel

Each transport channel has different metadata visibility. v0.1 documents
each explicitly so users (and reviewers) can map their threat model to
the right channel.

#### Direct WSS (Auto mode primary, Never mode only)

- Network observer sees: client IP ↔ relay IP, TLS SNI
  (`relay.phntm.pro`), TLS handshake timing, encrypted-payload size
  pattern.
- Relay sees: client IP, identity public key (per connect query
  parameter), envelope sizes, delivery timing.
- TLS protects: payload contents (then sealed-sender or E2E inside).

#### Tor onion (Always-Tor mode, Auto fallback)

- Network observer sees: client connecting to a Tor guard. Cannot
  distinguish PHANTOM traffic from any other Tor user's traffic at the
  guard.
- Relay sees: incoming connection from a rendezvous-point-side Tor
  circuit (no client IP), client-authorization key (per-conversation,
  derived from pairing), envelope sizes (mitigated by application-layer
  padding), delivery timing.
- Tor circuit padding (built-in, `circpadding-builtin`) and
  application-layer envelope padding mitigate size-based fingerprinting.

#### UnifiedPush wakeup (always active, in all three modes)

- Distributor (`ntfy.phntm.pro`) sees: opaque per-install token,
  push timestamp, push payload size (always 1 byte by design).
- Distributor does NOT see: client identity, peer identity, message
  content, conversation graph.
- Network observer between client and distributor sees: TLS
  connection to `ntfy.phntm.pro`, encrypted ntfy heartbeat / push
  delivery traffic. Same metadata class as direct WSS to relay.

### 3. New explicit honesty statements (Privacy Policy + KNOWN_ISSUES)

Lines that v0.1 commits PHANTOM to publishing in user-facing copy:

> "Tor mode does not protect against an adversary who can observe both
> the network entry and the relay's network simultaneously (a global
> passive adversary). This is a fundamental property of low-latency
> anonymity systems, not specific to PHANTOM."

> "PHANTOM uses self-hosted UnifiedPush for push wakeup so the app is
> not always-on. The push distributor sees an opaque identifier and
> the timing of push deliveries — never your identity, never your
> peer's identity, never message content. The distributor runs on
> the same infrastructure as the relay; no third party is involved."

> "Voice and video calls require a direct UDP connection (WebRTC).
> Tor only carries TCP. In Always-Tor mode, calls are disabled. In
> Auto mode, calls use the direct connection path and are subject to
> the same network restrictions as today's app — meaning calls may
> not work on certain restrictive networks without a VPN. Future
> work will explore alternative transport for calls."

### 4. Vanguards correction

v0 stated "Vanguards (Proposal 292) is the strongest available defence
for the relay, mandatory for production." v0.1 corrects:

- Full Vanguards (Prop 292) requires the `mikeperry-tor/vanguards`
  Python addon. Last real code change July 2021. Removed from Debian
  Trixie. Not deployable as a 2026 dependency.
- Vanguards-Lite (Prop 333) is built into stable C-tor since
  0.4.7.1-alpha and on by default for every onion service. PHANTOM's
  relay onion inherits this defence with zero configuration.
- SUMo flow-correlation defence is **not** Vanguards (which addresses
  guard discovery, a different problem). Real SUMo defences are
  circuit-padding machines (already shipped in Tor) plus application-
  layer padding (now mandatory in PHANTOM, see section 5).

### 5. Application-layer padding becomes mandatory

v0 had implicit padding for voice (8 KB chunks per ADR-014 implementation).
v0.1 makes padding explicit and applies to all envelope types:

| Envelope type | Pre-pad size | Post-pad size |
|---|---|---|
| Text message | varies, ~500-2000 bytes | padded to 1 KB |
| Voice chunk | 8 KB raw | already 8 KB ciphertext bundle |
| Audio chunk | varies | padded to 8 KB |
| File reference | varies | padded to 1 KB |
| ACK / signalling | < 200 bytes | padded to 1 KB |
| Call signalling (offer / answer / ICE) | varies | padded to 1 KB |

Padding scheme: `MessagePadding` (existing, ISO 7816-4, 256-byte
block, see `shared/core/crypto/.../MessagePadding.kt`). Extend
target sizes to the table above. Reviewer can audit padding by
inspecting the encrypted envelope size on the wire — should always
be one of {1024, 8192, 65536, ...} bytes regardless of plaintext.

This raises the bar for size-fingerprinting and SUMo-class attacks
without adding round-trip latency.

### 6. CGN / TSPU explicit acknowledgement

v0 attributed cellular Russia issues to Tecno HiOS firmware (per
ADR-013). v0.1 corrects:

- Root cause is stateful network element along the path between
  Russian carrier client and Hetinki relay (CGN, TSPU, transit
  firewall). VPN tunnels emit their own keepalives that refresh
  this stateful entry; bare WSS does not. Affects every client on
  every device on Russian carrier networks, not just Tecno.
- ADR-014 (TCP keepalive) provides defense-in-depth on the direct
  WSS path. Helps but not enough on its own.
- ADR-016 (Tor onion) eliminates the cross-border path issue entirely
  by routing through Tor circuits that the stateful element does not
  fingerprint as PHANTOM-specific.
- This is documented in user-facing Privacy Policy as: "PHANTOM works
  on restrictive networks (including Russian carriers without VPN)
  via a Tor-based fallback. The fallback is opt-in by default but the
  app will switch to it automatically if the direct connection fails
  repeatedly."

### 7. Threat Model v0.1 file location

`docs/threat-model/Threat_Model_v0_1.md` (English exec summary at
top, full Russian threat-model body following — same structure as v0).

Prepend a "Changes from v0" subsection summarising the seven points
above. Keep a Russian translation of the English exec summary so RU
readers see the changes immediately.

## Out-of-scope for v0.1 (deferred to v0.2)

- Formal cryptographic protocol audit response (separate workstream)
- iOS-specific threat model additions (deferred to iOS-port milestone)
- Multi-relay federation threat model (deferred to Beta+)
- Discovery layer threat model (separate ADR planned)

## References

- ADR-016 (Tor + UnifiedPush hybrid architecture)
- ADR-001 (System Boundaries)
- `docs/research/tor-feasibility-2026-05-04/06-security-analysis.md`
  (amended 2026-05-04 per Vanguards no-go check)
- `no-go-checks/02-vanguards-deployability.md`
- `no-go-checks/03-ntfy-audit.md`
- NDSS 2024 — Flow Correlation Attacks on Tor Onion Service Sessions
  with Sliding Subset Sum (SUMo)
- Tor Project — Vanguards-Lite (Proposal 333) status in stable C-tor
  releases
