# PHANTOM — Decisions Log

> **Append-only log of product + architectural decisions.** One entry per
> decision. Newest at the top. Each entry: short title, date, what was
> decided, why, and a reference (ADR / PR / commit) where applicable.
>
> **Update protocol:** after every PR merge that involved a decision (not just
> a fix), append an entry. Existing entries are immutable — if a decision is
> reversed, write a new entry citing the old one.
>
> Sister documents:
> [`ACTIVE_SPRINT.md`](ACTIVE_SPRINT.md) — what we're working on right now.
> [`TECHNICAL_BACKLOG.md`](TECHNICAL_BACKLOG.md) — prioritised items waiting.
> [`MASTER_TIMELINE_2026.md`](MASTER_TIMELINE_2026.md) — track-by-track status.

---

## D-20: Stage 5G Phase 1 succeeded — proceed to full implementation — 2026-05-09

**Decided:** Test 13.1 on Tecno МТС (RU, no VPN) reached
`Online via Tor · Ghost` via the obfs4 bridge on FlokiNET. The hypothesis
from D-16 is confirmed: obfs4's uniform-random wire signature bypasses the
TSPU 16-KB curtain that catches WebTunnel TLS handshakes.

Per the decision gate criteria in
[`docs/research/stage-5g-phase-1-2026-05-09/README.md`](../research/stage-5g-phase-1-2026-05-09/README.md):
**continue Stage 5G full implementation** — Variant C (RU carrier
checkpoint warning) is no longer needed.

**Next steps:**
- Stage 5G Phase 2 — multi-run reliability characterisation (Tests 2-5),
  EU emulator control runs, round-trip text + voice via Tor circuit.
- Stage 5G Phase 3 — multi-bridge fan-out (additional obfs4 bridges on
  geographically distinct hosts) + obfs4-only mode for the Ghost path so
  the failing WebTunnel entry is dropped from the chain. Drafted as
  ADR-015.

**Caveats from the run captured in the research doc:**
- First Tor cold start through the bridge took ~5 min (acceptable for a
  privacy-paranoid Ghost-mode user; Stage 5G Phase 2 will measure warm
  reconnect time).
- `ChatList` header showed `Connecting…` for ~5 min after the foreground
  notification already read `Online via Tor · Ghost` — UI consistency
  follow-up tracked in `TECHNICAL_BACKLOG.md` (notification updater reads
  `TransportManager.state`, ChatList header reads `transport.state`; the
  Tor-circuit WS upgrade is the slow step that drives the second flag).
- Switching back to Standard with `lastWorkingTransport=Tor` hint
  retained from Ghost causes the chain walk to try Tor first in Standard
  too — counter-intuitive on mode switch. Fix queued as a follow-up
  (clear hint inside `setPrivacyMode`).

**Reference:** Vladislav cross-device test 2026-05-09. See
[`docs/research/stage-5g-phase-1-2026-05-09/README.md`](../research/stage-5g-phase-1-2026-05-09/README.md)
for the captured run data.

---

## D-19: FlokiNET server reuse, not new VPS for Stage 5G — 2026-05-09

**Decided:** Test 13 problem was the TSPU 16-KB curtain itself, not the
FlokiNET server. The existing FlokiNET VPS is functional; Stage 5G Phase 1
will deploy obfs4 + Snowflake bridges onto it via config change (no new
hosting cost, no new operational surface).

**Why:** A new server would add €5-15/month and onboarding overhead for an
experiment whose outcome we do not yet know. Reusing reduces commitment
and lets us decision-gate on Phase 1 results without sunk-cost pressure.

**Reference:** Vladislav approval 2026-05-09. See D-16.

---

## D-18: Pro infrastructure in Alpha 2 = full UI, no payment — 2026-05-09

**Decided:** All Pro UI elements per Figma spec ship in Alpha 2 (badges,
upgrade screens, locked indicators). Pro features remain functionally OPEN
for testers (no gating, no entitlement check). "Upgrade" CTAs lead to a
"Coming soon" placeholder. Payment integration is deferred to the Beta
sprint.

**Why:**
- Tester clarity — they see what the Pro tier will look like end-to-end.
- Design system is locked end-to-end before Beta payment work.
- Payment integration is its own multi-week stack (Stripe / app-store
  billing / receipt verification) that should not block Alpha 2.
- No risk of accidentally charging someone who installs Alpha 2.

**Reference:** Vladislav approval 2026-05-09. Implementation in Step 3 of
the [`ACTIVE_SPRINT.md`](ACTIVE_SPRINT.md) Settings rewrite.

---

## D-17: Developer Mode toggle removed from Settings UI — 2026-05-09

**Decided:** The Developer Mode row in Advanced section is dropped from the
Kotlin SettingsScreen (it shipped via design-pass-5 PR #79 from the Figma
spec but no production code reads the resulting `developer_mode` boolean —
dead toggle).

**Why:** Shipping a toggle that does nothing trains users to ignore Settings
UI. The diagnostic flows the toggle was meant to gate (verbose logging,
debug overlays, "send debug bundle") will be revisited in a separate
diagnostics sprint. Until then no UI surface for the concept.

**Reference:** Vladislav report 2026-05-09 (Bug #4). Removed in Settings
rewrite (Step 3, [`ACTIVE_SPRINT.md`](ACTIVE_SPRINT.md)).

---

## D-16: Stage 5G phased — reuse FlokiNET + obfs4/Snowflake experiment — 2026-05-09

**Decided:** Variant D (Phase 1 quick experiment, then decision gate):
- **Phase 1 (2-3 days):** Add obfs4 + Snowflake bridges to existing FlokiNET
  server via config change. Wire through `OperatorBridges.kt` extension.
  Test 13.1 on Tecno МТС in Ghost mode.
- **Decision gate after Phase 1:**
  - Tor bootstraps successfully → continue Stage 5G full implementation
    (ADR-015 territory, Beta scope).
  - Tor still stalls → fall back to **Variant C**: Ghost mode shows a
    checkpoint warning in RU carriers ("Tor may not work — proceed?")
    with no silent downgrade preserved.

**Why:** Selling Ghost as a Pro feature in RU where Tor does not work would
mislead paying users. Three rejected alternatives:
- **A — locale-segmented SKU:** infrastructure cost (separate store
  listings), product confusion, Russian users buying via VPN to a non-RU
  store break it anyway.
- **B — Ghost reframe (REALITY + per-message rotation):** violates the no-
  silent-downgrade invariant that makes Ghost meaningful at all.
- **C standalone:** acceptable but defeatist if obfs4/Snowflake actually
  work on RU MTS — we should test before downgrading the promise.

Phase 1 is the cheap experiment that tells us which of D-or-C is honest.

**Reference:** Vladislav approval 2026-05-09 (Bug #2.5 from cross-device
QA report). Step 2 of [`ACTIVE_SPRINT.md`](ACTIVE_SPRINT.md). Stage 5G
ADR-015 will be drafted post-experiment.

---

## D-15: Per-user Ed25519 signed-challenge WS auth (hard cut-over) — 2026-05-08

Replace shared `?token=` with per-user signed challenge bound to existing
Ed25519 signing keypair. Hard cut-over (no migration window) because Alpha 2
not publicly released. TOFU first connect, 1:1 binding thereafter (mirrors
`publish_prekeys` invariant). RELAY_TOKEN BuildConfig field removed.
Reference: [ADR-027](../adr/ADR-027-Per-User-Signed-Challenge-Auth.md). PR #72.
Production-validated 2026-05-09.

---

## D-14: Group control messages and broadcasts via DR + Sealed Sender — 2026-05-08

DGS depends on `MessagingService` for outgoing transport; all group-related
envelopes (invite/SKD/add/leave + per-recipient ciphertext broadcasts + group
audio chunks) route through `sendGroupControlMessage` → `sendSealedPayload`.
Reuses existing pipeline; no new crypto.
Reference: [ADR-026](../adr/ADR-026-Group-Control-Messages-E2EE.md). PR #70.

---

## D-13: SenderKey KDF replaced with HKDF-SHA256 + iteration-bound salt — 2026-05-08

Bare `SHA256(chainKey || tag)` → RFC 5869 HKDF-SHA256 (single Expand block
L=32) with iteration counter bound into salt and `_v2` info-string suffixes.
New shared `phantom.core.crypto.Hkdf` utility used by both X3DH and SenderKey.
Wire-format-breaking for groups; acceptable since groups not in production.
Reference: PR #69.

---

## D-12: Track B "cut to F22 only" plan abandoned — 2026-05-08

Strategy session 2026-05-07 recommended cutting Track B to a single item (F22)
and shipping the rest as honest SECURITY_ROADMAP. Two days of focused work
closed all 8 items. SECURITY_ROADMAP.md updated to reflect reality.
Plan-vs-reality outcome: original ambition was achievable; the risk-discount
was too steep.

---

## D-11: Stage 5E Xray REALITY chosen over Tor bridge fan-out for RU — 2026-05-07

After Hetzner WebTunnel bridges (5C) and FlokiNET non-Hetzner bridges (5D)
both stalled at TSPU 16-KB curtain on RU MTS, Stage 5E (Xray VLESS+REALITY
embedded via libXray) became the production path. Mutually exclusive with
Tor at compile time. Hard-coded server endpoint `65.108.154.152:8443` SNI
`www.microsoft.com` UUID `09c6fd0e-…`.
Reference: [ADR-019](../adr/ADR-019-Xray-REALITY-Outer-Transport.md).

---

## D-10: Hetzner is the wrong host for Tor bridges (TSPU 16-KB curtain) — 2026-05-06

Diagnosed our 14% bootstrap stall on RU MTS as the externally-known
"16 KB curtain" attack: TSPU silently freezes any TLS flow whose first
~16 KB carries a Tor consensus document, when the source IP is in the
Hetzner CIDR. Tor Project explicitly warns "do not host bridges with
Hetzner" for RU users. Outcome: pivot to Stage 5E (Xray) and Stage 5D
(FlokiNET non-Hetzner bridges). FlokiNET later also stalled, suggesting
the curtain is behavioural not ASN-based.

---

## D-9: Call signalling routed through DR + Sealed Sender — 2026-05-08

WebRTC SDP/ICE/answer/hangup envelopes now use the same `sendSealedPayload`
path as read receipts. CallManager loses RelayTransport reference, gets
MessagingService instead. Plaintext fast-path in `handleDeliver` removed.
Reference: [ADR-025](../adr/ADR-025-Call-Signaling-E2EE.md).

---

## D-8: Ratchet state Keystore-wrapped on disk — 2026-05-08

`RatchetState` BLOB persisted to SQLite is now AES-256-GCM wrapped via
Android Keystore (alias family `phantom_ratchet_wrap_v1`). Same primitive
as ADR-023 (prekey wrap); same lazy-migration pattern.
Reference: [ADR-024](../adr/ADR-024-Ratchet-State-Keystore-Wrap.md).

---

## D-7: SPK + OPK private bytes Keystore-wrapped — 2026-05-08

Same wrap primitive as identity key; alias `phantom_prekey_wrap_v1`. Lazy
migration: rows rewrite themselves on next `maybeReplenishOneTimePreKeys` /
`maybeRotateSignedPreKey`.
Reference: [ADR-023](../adr/ADR-023-Local-Prekey-Keystore-Wrap.md).

---

## D-6: Repo cleanup — `google-services.json` removal + Firebase rotation — 2026-05-08

Live Firebase API key was committed in `apps/android/google-services.json`.
Removed via `git rm --cached`, added to `.gitignore`, key rotated in Firebase
Console. **No git history rewrite** — Firebase keys are publishable by design,
and force-pushing the public repo's history was assessed as more risk than
value. PR #46 (cleanup) + #47 (rotation log).

---

## D-5: Codeberg mirror enabled — 2026-05-08

Mirror at `https://codeberg.org/VladislavLiudvig/Phantom` via GitHub Action
workflow `.github/workflows/mirror.yml`. Reduces single-host (GitHub) takedown
risk and signals openness to the broader FOSS infrastructure ecosystem.

---

## D-4: Hard cut-over over migration windows — repeated decision — 2026-05-08

Three protocol-breaking changes shipped without migration windows: F2+F13
(SenderKey signing removal, migration `14.sqm`), F3 (HKDF KDF, `_v2` info
strings), F1+F11+F26 (group + auth wire format). Justified by "Alpha 2 not
released publicly, no production users." This shortcut is no longer available
once Alpha 2 is tagged.

---

## D-3: Tor + UnifiedPush hybrid (`onionwrapper` rejected, `kmp-tor` chosen) — earlier sprint

For embedded Tor on Android, evaluated `onionwrapper` (community-maintained)
vs. `kmp-tor` (KMP-native binding to upstream Tor). Chose `kmp-tor` for
Kotlin Multiplatform alignment + iOS expansion path. Push uses self-hosted
ntfy (no Google FCM dependency to preserve zero-third-party-metadata posture).
Reference: [ADR-016](../adr/ADR-016-tor-unified-push-hybrid-transport.md).

---

## D-2: Custom Double Ratchet over libsignal-client — 2026-04 (foundational)

`libsignal-client` is GPL-3.0; the relay's AGPL-3.0 §13 reach has compatibility
friction with GPL on shared types. Self-roll Double Ratchet on libsodium
(ISC-licensed, audited, aggregation-clean against AGPL).
Reference: [ADR-006](../adr/ADR-006-Crypto-Library-Decision.md).

---

## D-1: Identity / signed prekey / one-time prekey separation — 2026-04 (foundational)

X25519 identity is the routing pubkey; Ed25519 signing keypair is independent
(SignedPreKey signature, now also WS auth challenge signature). Prevents
F13/F14/F15 class of "single-key compromise = total impersonation" attacks.
Reference: [ADR-009](../adr/ADR-009-identity-prekey-separation.md). The same
Ed25519 keypair F11+F26 leverages for WS auth.
