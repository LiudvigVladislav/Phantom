# PHANTOM Architecture Decision Records

This directory holds the project's Architecture Decision Records
(ADRs). Each ADR captures one architectural choice with the
context that produced it, the decision itself, the alternatives
that were considered and rejected, and the consequences. ADRs
are immutable once accepted — when a decision is reversed or
extended, a new ADR is added that explicitly supersedes or
extends the older one.

## Why we keep ADRs

Three reasons:

- **Onboarding speed.** A new contributor (or a returning
  developer after a long break) opens this directory and gets the
  load-bearing decisions of the project in one read instead of
  spelunking through git history.
- **External review readiness.** Auditors, security researchers,
  and contributors evaluating the project expect an architectural
  paper trail. Stage 5E's censorship-resistance posture rests on
  ADR-019; the licensing posture rests on ADR-006; the privacy
  posture rests on ADR-001 and ADR-016.
- **Anti-relitigation.** The Decision log in
  [`../PROJECT_LOG.md`](../PROJECT_LOG.md) records *rejected*
  options too — if we ever circle back on "why don't we use FCM
  for push?", the answer is one click away.

## Accepted ADRs (in numerical order)

| #   | Title                                                                                                                | Concern                                                                                                                                       | Status   |
| --- | -------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| 001 | [System Boundaries](ADR-001-System-Boundaries.md)                                                                    | What PHANTOM is and is not — the foundational scope statement that every other ADR refers back to                                             | Accepted |
| 002 | [Shared Core Layout](ADR-002-Shared-Core-Layout.md)                                                                  | Module structure of `:shared:core:*` (identity, crypto, storage, transport, messaging, discovery, policy)                                     | Accepted |
| 003 | [Transport Abstraction](ADR-003-Transport-Abstraction.md)                                                            | The `RelayTransport` interface that lets ADR-016's hybrid (Tor + direct WSS) and ADR-019's Xray REALITY plug in without changing call sites   | Accepted |
| 004 | [Relay Trust Model](ADR-004-Relay-Trust-Model.md)                                                                    | What the relay is allowed to know, and what it must never know — load-bearing for the privacy story                                           | Accepted |
| 005 | [Discovery Scope for MVP](ADR-005-Discovery-Scope-for-MVP.md)                                                        | Which discovery mechanisms are in scope for Alpha (QR + invite link); DHT and username directory deferred                                     | Accepted |
| 006 | [Crypto Library Decision](ADR-006-Crypto-Library-Decision.md)                                                        | libsodium-only crypto; AGPL-3.0-or-later licensing rationale (relay §13 angle); why no libsignal-client                                       | Accepted |
| 009 | [Identity / Prekey Separation](ADR-009-identity-prekey-separation.md)                                                | F13/F14/F15 fix — fresh ephemeral DH for each ratchet, identity key only for signing                                                          | Accepted |
| 010 | [Transport Reconnect Deadlock](ADR-010-transport-reconnect-deadlock.md)                                              | The `forceShutdownActiveEngine` + `cancelAndJoin` discipline that lets `KtorRelayTransport` recover from middlebox-killed connections cleanly | Superseded by PR-H1c + PR-H1e (alternative implementation) |
| 011 | [AlarmManager Network Wakeup](ADR-011-alarm-manager-network-wakeup.md)                                               | Periodic wake-ups that hold the WebSocket alive across aggressive-OEM Doze + radio-park behaviour                                             | Accepted |
| 013 | [Revised Transport Diagnosis 2026-05-02](ADR-013-revised-transport-diagnosis-2026-05-02.md)                          | Root-cause analysis for the MTS WiFi 50–120 s drop pattern that pushed the project toward the ADR-016 hybrid                                  | Superseded by PR-H1c + PR-H1e (alternative implementation) |
| 016 | [Tor + UnifiedPush Hybrid Transport](ADR-016-tor-unified-push-hybrid-transport.md)                                   | Two-channel architecture: UnifiedPush wakeup + Tor data plane; three Privacy Modes (Auto/Always-Tor/Never)                                    | Accepted |
| 017 | [SenderKey Signing Removal](ADR-017-senderkey-signing-removal.md)                                                    | Removal of dead SenderKey signing path that bypassed Double Ratchet; closed F2 + F13 by deleting the unused code rather than fixing it       | Accepted |
| 018 | [Threat Model v0.1 Revision](ADR-018-threat-model-revision-v0_1.md)                                                  | Revised threat model after the 2026-05-04 4-test matrix disproved the Tecno-firmware diagnosis and the Tor + UnifiedPush hybrid was adopted; renumbered from ADR-017 (collision) on 2026-05-14 | Accepted |
| 019 | [Xray VLESS+REALITY as outer transport](ADR-019-Xray-REALITY-Outer-Transport.md)                                     | Stage 5E — TSPU 16-KB curtain bypass; Path-A vs Path-B; libXray gomobile in-process; capability-style UUID; MPL-2.0 + AGPL aggregation        | Accepted |
| 021 | [Relay PreKey storage SQL migration](ADR-021-relay-prekey-sql-migration.md)                                          | Placeholder slot reserving the SQL-migration decision. Trigger conditions in body + `KNOWN_ISSUES.md` § ISSUE-012; current JSONL backend is the conscious choice until one fires | Reserved — content pending |
| 023 | [Local prekey private-key wrap via Android Keystore](ADR-023-Local-Prekey-Keystore-Wrap.md)                          | F22 — wrap SPK + OPK private bytes with Android Keystore AES-256-GCM master key before SQLite write; defence in depth above SQLCipher          | Proposed |

## Pending ADR drafts

The following decisions are sketched in
[`../project/ARCHITECTURAL_DECISIONS_TODO.md`](../project/ARCHITECTURAL_DECISIONS_TODO.md)
but have not yet been promoted to numbered ADRs in this directory.
The TODO file's numbering is *aspirational* and overlaps with several
already-accepted ADRs above; future scheduling pulls each draft into
its own `ADR-NNN-*.md` file with a renumbered title and a `Status: Accepted`
header.

| Working title                                | Why it matters                                                                                                            | Target window                                                              |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Username uniqueness via relay namespace      | Closes ADR-005's "username directory deferred" gap once the operator-controlled username layer is in scope                | Alpha 2 → Beta                                                             |
| Verification authority (Willen LLC central)  | Defines who can issue the org-verified badge; needed before any "verified by PHANTOM" UI                                  | Beta                                                                       |
| Group encryption hardening                   | Closes the security findings around group control messages — wrap them inside the Double Ratchet, HKDF KDF, leave-rotation | Beta-tier                                                                  |
| Premium feature gating architecture          | Records how the optional commercial track plugs in without touching the open-source core                                  | Beta                                                                       |
| Account migration via seed phrase            | The user-side recovery story; today's identity loss is irrecoverable                                                      | Beta                                                                       |
| Attachment server architecture               | Encrypted MinIO-backed attachment store (photos, files); architecturally separate from relay                              | Alpha 2 → Beta                                                             |
| iOS architecture (SwiftUI + KMP shared)      | Tracked in ADR-019 as ADR-022; the iOS port itself is the next major platform deliverable after Alpha 2                   | Beta-tier                                                                  |
| Pluggable transports (obfs4, Snowflake, fronting) | Once ADR-019's Xray REALITY ages, additional outer transports will need a structured slot                              | Beta                                                                       |
| Adaptive Transport Selection                 | Runtime probe-and-pick across direct WSS, Xray, Tor; replaces today's build-time `USE_TOR` / `USE_XRAY` flag mutex        | ADR-020, Beta-tier                                                         |
| Multi-server Xray fan-out                    | Closes the single-Hetzner-VPS point-of-failure on Stage 5E                                                                | ADR-021, Beta-tier                                                         |

## Conventions

- **Filename:** `ADR-NNN-kebab-case-title.md` where `NNN` is a
  zero-padded sequence number. Numbers are never reused after an
  ADR is published, even if the ADR is later superseded.
- **Status header** appears in line 3 of every ADR (`Status: ...`).
  The accepted set is `proposed`, `accepted`, `superseded by ADR-XXX`,
  `deprecated`. Once an ADR ships, its status only ever moves
  forward.
- **Cross-references** use relative links so the directory works
  off-line (cloned repo, offline review session) — never absolute
  GitHub URLs.

## See also

- [`../PROJECT_LOG.md`](../PROJECT_LOG.md) — running development
  journal and the **Decision log** for *rejected* options
- [`../project/ARCHITECTURAL_DECISIONS_TODO.md`](../project/ARCHITECTURAL_DECISIONS_TODO.md) — drafts queue
- [`../project/MASTER_TIMELINE_2026.md`](../project/MASTER_TIMELINE_2026.md) — what shipped when, what's next
- [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) — 400-word architectural overview at repo root
