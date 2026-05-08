# PHANTOM — Project Status Snapshot (2026-05-09)

> Engineering snapshot for Vladislav. Single source of truth at this moment in
> time. Honest about what works, what does not, and what is parked. Not for
> external publication — internal artifact, mirrors `MASTER_TIMELINE_2026.md`
> at the section level but goes deeper on infra + tests + decisions.
>
> **Master HEAD:** `6e90e3c6` — `feat(auth): F11+F26 — replace shared WS token
> with per-user Ed25519 signed challenge (#72)`. Repository in clean state, no
> active branches, no pending merges.

---

## 1. Architecture overview (current state)

### Deployed infrastructure

| Component | Endpoint | Host | Status | Notes |
|---|---|---|---|---|
| **Relay** | `wss://relay.phntm.pro/ws` | Hetzner Helsinki CPX22 | ✅ live | Caddy → Rust/Axum, ciphertext store-and-forward, JSONL persistence; signed-challenge auth as of 2026-05-09 |
| **Auth challenge endpoint** | `https://relay.phntm.pro/auth/challenge` | same as relay | ✅ live | Per-user Ed25519 nonce, single-shot 5-min TTL (ADR-027) |
| **Prekey endpoints** | `https://relay.phntm.pro/prekeys/*` | same as relay | ✅ live | Publish / fetch-bundle / status / OPK delete (ADR-009) |
| **UnifiedPush distributor** | `https://ntfy.phntm.pro/` | same Docker network as relay | ✅ live | Ntfy self-hosted; relay stores topic-URL per identity, fires wake-up POST on offline-recipient envelope (ADR-016) |
| **Tor onion service** | `zmdrxlrkd7iv7ozvdl5nlhctsxgx6eyuqionp6xzriolymy3m6ioloyd.onion:80/ws` | embedded in same Docker stack (Tor daemon container) | ✅ live | Plain WS over onion; circuit provides confidentiality+integrity (ADR-016 Stage 2B) |
| **Tor WebTunnel bridge** | Hetzner CIDR | same VPS | ⚠️ unusable on RU MTS | Bridge bootstraps but stalls at 14% on Russian MTS due to TSPU 16-KB curtain attack on Hetzner-hosted bridges (`memory/project_tspu_16kb_curtain_2026_05_06.md`) |
| **Xray REALITY** | `65.108.154.152:8443` SNI `www.microsoft.com` | Hetzner same VPS | ✅ live, production-validated | VLESS+REALITY outer wrap, embedded `libXray` SOCKS5 listener on Android (ADR-019); single shared UUID `09c6fd0e-…` (capability-style auth, NOT identity) |
| **Domain DNS** | `phntm.pro`, `relay.phntm.pro`, `ntfy.phntm.pro` | registrar TBD-by-Vladislav | ✅ resolving | Caddy auto-cert via Let's Encrypt; cert renewal automatic |
| **APK signing** | `keystores/phantom-release.keystore` | local on dev machine, SHA-256 fingerprint pinned in `assetlinks.json` | ⚠️ no off-device backup | Operator action: Vladislav copies to USB / cloud whenever convenient |
| **Firebase project** | rotated 2026-05-08 (PR #47) | Google Cloud | ⚠️ key in git history | Rotated key + APK SHA-1 allowlist; Firebase ROT considered "publishable" but cleaned anyway |

### Transport layer stack (client perspective)

```
                 ┌────────────────────────────────────────────┐
   App layer:    │  WebSocket frames carrying sealed-sender   │
                 │  envelopes of Double-Ratchet ciphertext    │
                 └─────────────────────┬──────────────────────┘
                                       │
   Auth:         ┌─────────────────────┴──────────────────────┐
                 │  GET /auth/challenge → nonce               │
                 │  Ed25519.sign(nonce) → signature           │
                 │  WS upgrade with ?id, ?signing_pubkey,     │
                 │  ?challenge, ?signature  (ADR-027)         │
                 └─────────────────────┬──────────────────────┘
                                       │
   Outer wrap:   ┌─────────────────────┼──────────────────────┐
   (BuildConfig)│         ▼            ▼            ▼         │
                │   USE_XRAY=true   USE_TOR=true  default     │
                │   ────────────    ───────────   ────────    │
                │   embedded        embedded      direct      │
                │   libXray         kmp-tor       wss://      │
                │   SOCKS5 :10808   SOCKS5        relay.      │
                │       │           :auto         phntm.pro   │
                │       ▼              │             │        │
                │   REALITY            ▼             │        │
                │   tunnel to       Tor circuit      │        │
                │   65.108.154.152  to .onion        │        │
                │   :8443           :80              │        │
                │       │              │             │        │
                └───────┼──────────────┼─────────────┼────────┘
                        ▼              ▼             ▼
                ┌──────────────────────────────────────────────┐
                │           Hetzner Helsinki VPS               │
                │  Caddy :443 (TLS) ─┐                         │
                │  Xray :8443 ───────┤  →  relay :8080 → /ws   │
                │  Tor onion :80 ────┘                         │
                │                                              │
                │  ntfy distributor (offline-wake POST)        │
                └──────────────────────────────────────────────┘
```

**Mode selection:** mutually exclusive at compile time (`build.gradle.kts`
errors if both `USE_TOR` and `USE_XRAY` set). Runtime mode toggle deferred to
ADR-020 (drafted, not implemented).

**Push wakeup loop:** when relay queues an envelope and recipient is offline,
relay fires `POST` to ntfy topic URL the client previously published via
`/push/register`. Client UnifiedPush distributor wakes the foreground service,
which reconnects WS and drains the queue.

---

## 2. Implemented features (what's done)

### Crypto & messaging

| Feature | Status | Reference |
|---|---|---|
| 1:1 text E2EE (X3DH 4-DH + Double Ratchet) | ✅ | `LibsodiumX3DH.kt`, `LibsodiumDoubleRatchet.kt`, [ADR-006](../adr/ADR-006-Crypto-Library-Decision.md), [ADR-009](../adr/ADR-009-identity-prekey-separation.md) |
| Sealed Sender on every 1:1 envelope | ✅ | `SealedSender.kt`; relay sees only `to=…` |
| Per-conversation send/receive Mutex (atomic ratchet step) | ✅ | `DefaultMessagingService.encryptUnderLock` |
| Ratchet state Keystore-wrapped on disk | ✅ | F8, [ADR-024](../adr/ADR-024-Ratchet-State-Keystore-Wrap.md) |
| SPK + OPK private bytes Keystore-wrapped on disk | ✅ | F22, [ADR-023](../adr/ADR-023-Local-Prekey-Keystore-Wrap.md) |
| Identity X25519 private key Keystore-wrapped | ✅ | `KeystoreManager`, F11 (storage finding, distinct from F11 auth) |
| Read receipts via sealed Double Ratchet | ✅ | C-2, PR #59 |
| Voice messages (8 KB chunked, reassembly with TTL) | ✅ | `MessagePadding.AUDIO_CHUNK_BYTES`; PR #32 |
| Call signalling (SDP/ICE/answer/hangup) via DR + Sealed Sender | ✅ | F19+F20, [ADR-025](../adr/ADR-025-Call-Signaling-E2EE.md) |
| WebRTC audio (DTLS-SRTP) | ⚠️ one-way audio bug | Calls = experimental, see ISSUE-014 + section 5 |
| Group SenderKey ratchet with HKDF-SHA256 | ✅ | F3, `phantom.core.crypto.Hkdf.sha256L32` |
| Group control messages (invite/SKD/add/leave) via DR + Sealed Sender | ✅ | F1, [ADR-026](../adr/ADR-026-Group-Control-Messages-E2EE.md) |
| Member-leave proactive SenderKey rotation | ✅ | F4, PR #71 |
| Per-user signed-challenge WS auth (Ed25519) | ✅ | F11+F26, [ADR-027](../adr/ADR-027-Per-User-Signed-Challenge-Auth.md) |
| Disappearing messages (5 timer presets) | ✅ | inherited from Alpha 1 |
| Edit / delete-for-both via encrypted control messages | ✅ | inherited from Alpha 1 |
| Reactions | ⚠️ broken | Send-path or receive-path defect, see section 5 |

### Reliability & UX (Track A)

| Feature | Status | Reference |
|---|---|---|
| `bootstrapReady` StateFlow | ✅ | F-08, PR #65 |
| Outbox re-enqueue on flush failure | ✅ | F-01, PR #65 |
| `disconnect()` flushes outbox first (3 s bound) | ✅ | F-09, PR #65 |
| `insertMessage` before `saveSession` (atomicity) | ✅ | F-04, PR #65 |
| `KeyPermanentlyInvalidatedException` recovery | ✅ | F-02, PR #67 |
| `KeystoreManager.setUnlockedDeviceRequired` (API 28+) | ✅ | F-12, PR #67 |
| `removeConversationMutex` on conversation delete | ✅ | F-06, PR #67 |
| `startReceiving()` idempotency under Mutex | ✅ | F-13, PR #67 |
| SPK persist before publish | ✅ | F-14, PR #68 |
| Logcat redaction on parse-fail (no plaintext preview) | ✅ | F-21, PR #68 |
| `AddContactDialog` strict hex pubkey validation | ✅ | F-24, PR #68 |
| Double-`onStartCommand` race guarded | ✅ | PR #68 |
| Onboarding keyboard auto-collapse | ✅ | PR #68 |
| Onboarding radar circles removed | ✅ | PR #68 |

### Censorship resistance (Track E / Stage 5)

| Stage | Status | Reference |
|---|---|---|
| 5A: kmp-tor embedded daemon + lifecycle | ✅ | merged earlier |
| 5B: onion address resolution + `RELAY_ONION_URL` config | ✅ | merged earlier |
| 5C: Hetzner WebTunnel bridges | ⚠️ stalled on RU MTS | TSPU 16-KB curtain attack on Hetzner CIDR |
| 5D: FlokiNET non-Hetzner bridges | ⚠️ same stall | curtain is behavioural, not ASN-based |
| 5E.A: Server-side Xray VLESS+REALITY | ✅ | NekoBox-validated; `deploy/xray/` |
| 5E.B.1: GitHub Actions `build-libxray.yml` | ✅ | commits `5cca2976` + `f5e21fb3` |
| 5E.B.2/3: KMP module `:shared:core:xray` + libXray vendoring | ✅ | commit `96fcbf1a` |
| 5E.B.4: `XrayService` wired into `PhantomMessagingService` | ✅ | commit `98245f69` |
| 5E.B.5: Production validation Tecno МТС без VPN | ✅ | Test 14 (2026-05-07): text + voice 5 s through REALITY |
| Strict Xray routing on server (3-rule chain) | ✅ | PR #45 |
| ADR-019 Xray REALITY rationale (371 lines, threat model + limitations) | ✅ | PR #48 |
| Prekey republish-on-reconnect | ✅ | PR #53 |

### Repository / release polish (Track C — for external review)

| Phase | Status | Reference |
|---|---|---|
| 1: `.gitignore` + `google-services.json` removal + Firebase rotation | ✅ | PR #46 + #47 |
| 2: `funding.json` + `.github/FUNDING.yml` | ✅ | PR #49 |
| 3: README polish (License → AGPL-3.0, Status → Alpha 2, Threat Model + ADR + Codeberg links, Mermaid diagram, 5 new sections) | ✅ | PR #49 + #51 + #52 + #55 |
| 4: Threat Model + Doctrine English exec summary; ADR index `docs/adr/README.md`; root `ARCHITECTURE.md` | ✅ | PR #50 + #52 |

---

## 3. In-progress work (active branches)

**None.** Master is the only active line. All Track A and Track B branches
merged. No staged commits in working tree (`git status` clean apart from
untracked PDFs and IDE config).

---

## 4. Pending / not started

### Code work (Beta-tier hardening)

| Item | Severity | Estimate | Notes |
|---|---|---|---|
| **PR 2.6 — Calls audio plumbing** | medium | 2-3 days | `JavaAudioDeviceModule` + AudioFocus + suppress reconnect during call. Surfaces as one-way audio in 2026-05-09 cross-device QA. |
| **Reactions delivery defect** | medium | <1 day diagnosis | No `type=reaction` envelope visible in either device's 2026-05-09 log — send-path or receive-path broken. |
| **Hangup → return to ChatScreen, not ChatList** | low | <1 hr | Navigation regression. ~5 lines in CallScreen `onEnd` callback. |
| **Delete-conversation in ChatList long-press menu** | low | 1 day | New control-message type `TYPE_CONVERSATION_DELETE` analogous to `deleteMessageForBoth`; UI submenu "Delete for me / Delete for both". |
| **Design polish + iconography mismatch with references** | low for code, high for reviewer perception | separate sprint | Run `ui-prototyper` agent against `memory/design_reference_screens.md`; produce diff report; Vladislav prioritises. |
| **P2 batch Track B (F6, F7, F9, F10, F12 retry, F14, F18, F23, F25)** | low | rolled into Beta polish | Logging tightening, edge cases, validation hardening. |

### Architecture decisions not yet drafted (canonical numbering, distinct from `ARCHITECTURAL_DECISIONS_TODO.md` legacy numbering)

| Open question | Why it matters | Status |
|---|---|---|
| Username directory (hosted, rate-limited lookup by `@username`) | Discovery without leaking social graph; Beta scope | Draft as ADR-007 in `ARCHITECTURAL_DECISIONS_TODO.md` |
| Verification authority model (Willen LLC central vs. distributed) | Trust anchor for username binding; Beta scope | Draft as ADR-008 |
| Account migration via seed phrase | Cross-device identity restore | Draft as ADR-012 |
| Attachment server architecture (encrypted MinIO) | Photos / files; Beta scope | Draft as ADR-013 |
| iOS architecture (KMP shared core + SwiftUI shell) | Cross-platform expansion | Draft as ADR-014 |
| Pluggable transports beyond Tor + Xray (obfs4, Snowflake, fronting) | Multi-bridge resilience | Draft as ADR-015 |
| Adaptive transport selection (auto-pick direct / Tor / Xray) | Runtime mode toggle for Privacy Mode UI | Draft as ADR-020 (mentioned in ADR-019 as Phase 2) |
| Multi-server Xray fan-out | Reduce single-Hetzner-IP correlation risk | ADR-021 (mentioned in ADR-019) |
| iOS XCFramework export of shared core | Stage gate for iOS port | ADR-022 (mentioned in ADR-019) |
| Multi-device support (linked-device trust, no shared key) | Beta milestone | unscheduled |
| Plaintext-only-in-RAM for Ghost Privacy Mode | Beta milestone | spec exists in design docs, no impl |

### Operational / non-code (Vladislav-only)

- **Demo video Stage 5E** — not yet recorded.
- **Public write-up** Stage 5E: HN post + Хабр post.
- **Tag `v0.1.0-alpha.2`** — security gate cleared; tagging is one command + push.
- **Off-device keystore backup** (`keystores/phantom-release.keystore`).
- **Firebase Console** SHA-1 fingerprint allowlist (defence-in-depth).

---

## 5. Outstanding bugs / known issues

### From `KNOWN_ISSUES.md` (Alpha-1 baseline; many superseded)

| ID | Status | Notes |
|---|---|---|
| ISSUE-001 | ⚠️ partly mitigated | OEM-radio ~60 s reconnect cycle. WIFI lock + WAKE lock + MulticastLock + AlarmManager wakeup all shipped. Voice messages on Tecno without VPN remain intermittent — **superseded by Stage 5E Xray** which works on Tecno МТС without VPN (Test 14, 2026-05-07). |
| ISSUE-002 | ✅ resolved | commit `5caf61eb` |
| ISSUE-003 | ✅ resolved | commit `5caf61eb` |
| ISSUE-004 | 🟡 not seen recently | MAC validation failure on flush burst. Last observed pre-`37e1414e`; mitigation: increase `MAX_SKIP` if it returns. |
| ISSUE-005 | 🟡 minor UX | Onboarding completion not visually confirmed. Not blocking. |
| ISSUE-006 | 🟡 minor UX | No retry feedback on send failure. Not blocking. |
| ISSUE-007 | ⬜ not implemented | Nearby radar UI (BLE proximity discovery). Beta scope. |
| ISSUE-008 | ✅ resolved | Debug build URL no longer hardcoded. |
| ISSUE-009 | ⬜ not implemented | iOS port. Post-Beta. |
| ISSUE-010 | ⬜ not implemented | Web client. Post-v1.0. |
| ISSUE-011 | architectural choice | Self-rolled Signal protocol over libsodium (vs libsignal-client) — see [ADR-006](../adr/ADR-006-Crypto-Library-Decision.md). |
| ISSUE-012 | architectural choice | Relay PreKeyStore in-memory + JSONL (vs SQL). Graduate at ADR-018 timeframe. |
| ISSUE-013 | ✅ Stage 5E mitigates | TSPU silent drops on RU cellular. Xray REALITY closes this for Russian mobile networks. |
| ISSUE-014 | architectural choice | Calls = experimental in Alpha. PR 2.6 deferred. |

### New (2026-05-09 cross-device QA)

| Bug | Severity | Expected fix path |
|---|---|---|
| Mic one-way audio when phone↔emu calls | medium | Calls audio plumbing PR (above) |
| Hangup returns to ChatList not ChatScreen | low | Trivial nav fix |
| Reactions not delivered to recipient | medium | Diagnose send/receive path |
| No "delete chat" in ChatList long-press menu | low | New control message + UI submenu |
| Design / iconography mismatch with references | low for code, visible | Separate UI sprint |

---

## 6. Test results latest

### 2026-05-09 cross-device F11+F26 QA (Tecno Spark Go + Pixel emulator API 35)

| Subsystem | Direct WSS | Tor onion | Xray REALITY |
|---|---|---|---|
| Signed-challenge auth (`/auth/challenge` HTTP + signed WS upgrade) | n/a tested | n/a tested | ✅ both devices succeed |
| 1:1 text round-trip | n/a tested | n/a tested | ✅ both directions work |
| Voice (`type=audio_chunk`, ~55 KB chunks) | n/a tested | n/a tested | ✅ phone → emu delivered |
| Call signalling (`call_offer`/`call_ice`/`call_answer`/`call_hangup`) | n/a tested | n/a tested | ✅ envelopes round-trip |
| Read receipts (`type=read_receipt`) | n/a tested | n/a tested | ✅ both directions work |
| Sealed Sender on every envelope | n/a tested | n/a tested | ✅ `sealed=true` in every log line |
| WebRTC audio media plane | n/a tested | n/a tested | ⚠️ one-way (mic plumbing) |

**Network configuration tested:** Tecno on RU MTS cellular **without VPN**;
emulator on host Wi-Fi behind home router. Both clients use Xray REALITY
(`USE_XRAY=true`).

### Russia / MTS-specific status matrix

| Path | Status | Last verified |
|---|---|---|
| Direct WSS | ❌ TSPU silent drops on cellular | 2026-04-28 |
| Tor onion via Hetzner WebTunnel bridge | ❌ stalls at 14% bootstrap (TSPU 16-KB curtain) | Test 11, 2026-05-06 |
| Tor onion via FlokiNET RO bridge | ❌ same stall (curtain is behavioural) | Test 13, 2026-05-06 |
| **Xray REALITY direct to Hetzner** | **✅ works without VPN** | Test 14, 2026-05-07 + cross-device 2026-05-09 |

---

## 7. Pre-Alpha-2 release window

**Target window:** late May 2026.

### Code work remaining (none blocking)

Code state is far ahead of original plan: Track A complete, Track B items 1-8
complete, Stage 5E production-validated 2026-05-07 + cross-device 2026-05-09.
Security gate for Alpha-2 has cleared.

### Operational work remaining (Vladislav-only)

| Item | Estimate | Status |
|---|---|---|
| Demo video (5-10 min, Tecno МТС via Xray; EN+RU captions) | 3-4 hrs | not started |
| Public write-up Stage 5E (HN + Хабр) | 5-6 hrs | not started — optional but recommended (organic momentum signal) |

No code blocker between now and the release window.

---

## 8. Roadmap to Beta release

Beta is a separate window, roughly Fall 2026 per `ROADMAP.md`. Key
prerequisites that are not yet started:

### Must-have for Beta

- **External security audit** — outside reviewer pass on the cryptography
  + transport surface. Without an external reviewer, we self-publish the
  current SECURITY_ROADMAP and request community review.
- **Multi-device support** (linked-device trust, no shared key) — design + ADR
  + impl. Estimated 3-4 weeks of focused work.
- **iOS port** — KMP XCFramework export (ADR-022), SwiftUI shell, identity
  migration tooling. Estimated 3-4 months solo.
- **Calls leave experimental** — PR 2.6 audio plumbing + AudioFocus + reconnect
  suppression during calls. Estimated 1 week.
- **Attachments** (encrypted MinIO) — server side + client side + key wrap
  per attachment. Estimated 2 weeks.
- **Stable groups** — F1+F3+F4 already closed; needs UX polish + scale test.
- **Public channels** (read-only broadcast with per-channel sender keys) — new
  primitive, ADR needed.
- **Username directory** — relay-side namespace, rate-limited lookup, ADR-007
  draft exists.

### Nice-to-have for Beta

- **Pluggable transports** beyond Xray + Tor (obfs4, Snowflake, fronting) —
  ADR-015 draft.
- **Privacy Mode framework** Standard / Private / Ghost (specs exist; impl
  is a separate ADR).
- **Plaintext-only-in-RAM** for Ghost mode — major architectural refactor.
- **Desktop client** (Compose Multiplatform JVM target).

---

## 9. Operational state

### Servers

| Host | Role | Cost | Notes |
|---|---|---|---|
| Hetzner Helsinki CPX22 | relay + ntfy + Tor + Xray (single Docker stack) | ~€7/month | only production server; single point of failure |

### Domain & DNS

- `phntm.pro` registered, resolving.
- A records: `relay.phntm.pro` → Hetzner IP; `ntfy.phntm.pro` → same; `phntm.pro`
  apex → static page (or redirect).
- Caddy auto-renews Let's Encrypt certs.
- `assetlinks.json` published at `https://phntm.pro/.well-known/assetlinks.json`
  with APK signing-key SHA-256 (universal-link verification).

### Monitoring / alerting

- **None deployed.** Health endpoint exists at `https://relay.phntm.pro/health`
  but no external uptime checker (Pingdom / Healthchecks.io / etc) wired.
- `docker logs phantom-relay-relay-1 -f` is the only live observability.
- Vladislav notices outages by users complaining or by his own test client
  failing — no proactive alerting.

### Source-control mirroring

- Primary: GitHub `LiudvigVladislav/Phantom`.
- Mirror: Codeberg `VladislavLiudvig/Phantom` (auto-mirrored via GitHub
  Action workflow `mirror.yml`).

### Backups

- **Relay state:** in-memory + JSONL append-only on host disk. JSONL files
  (`prekeys.jsonl`, `reports.jsonl`, `blocklist.txt`, `push_tokens.jsonl`)
  not currently backed up off-host. **Risk:** Hetzner disk failure wipes
  all published prekey bundles + reports. Mitigation: clients re-publish on
  reconnect, so functional impact is bounded; reports + blocklist are lost.
- **APK signing keystore:** lives only on Vladislav's dev machine. **Risk:**
  laptop loss = unable to sign updates for installed users (they must
  uninstall + reinstall). Mitigation pending: copy to USB + cloud.

---

## 10. Recent decisions log (last 15 architectural decisions)

Most recent first.

### D-15: Per-user Ed25519 signed-challenge WS auth (hard cut-over) — 2026-05-08

Replace shared `?token=` with per-user signed challenge bound to existing
Ed25519 signing keypair. Hard cut-over (no migration window) because Alpha 2
not publicly released. TOFU first connect, 1:1 binding thereafter (mirrors
`publish_prekeys` invariant). RELAY_TOKEN BuildConfig field removed.
[ADR-027](../adr/ADR-027-Per-User-Signed-Challenge-Auth.md). Production-validated 2026-05-09.

### D-14: Group control messages and broadcasts via DR + Sealed Sender — 2026-05-08

DGS depends on `MessagingService` for outgoing transport; all group-related
envelopes (invite/SKD/add/leave + per-recipient ciphertext broadcasts + group
audio chunks) route through `sendGroupControlMessage` → `sendSealedPayload`.
Reuses existing pipeline; no new crypto.
[ADR-026](../adr/ADR-026-Group-Control-Messages-E2EE.md).

### D-13: SenderKey KDF replaced with HKDF-SHA256 + iteration-bound salt — 2026-05-08

Bare `SHA256(chainKey || tag)` → RFC 5869 HKDF-SHA256 (single Expand block
L=32) with iteration counter bound into salt and `_v2` info-string suffixes.
New shared `phantom.core.crypto.Hkdf` utility used by both X3DH and SenderKey.
Wire-format-breaking for groups; acceptable since groups not in production.

### D-12: Track B "cut to F22 only" plan abandoned — 2026-05-08

Strategy session 2026-05-07 recommended cutting Track B to a single item (F22)
and shipping the rest as honest SECURITY_ROADMAP. Two days of focused work
closed all 8 items. SECURITY_ROADMAP.md updated to reflect reality. Plan-vs-
reality outcome: original ambition was achievable; the risk-discount was too
steep.

### D-11: Stage 5E Xray REALITY chosen over Tor bridge fan-out for RU — 2026-05-07

After Hetzner WebTunnel bridges (5C) and FlokiNET non-Hetzner bridges (5D)
both stalled at TSPU 16-KB curtain on RU MTS, Stage 5E (Xray VLESS+REALITY
embedded via libXray) became the production path. Mutually exclusive with
Tor at compile time. Hard-coded server endpoint `65.108.154.152:8443` SNI
`www.microsoft.com` UUID `09c6fd0e-…`. [ADR-019](../adr/ADR-019-Xray-REALITY-Outer-Transport.md).

### D-10: Hetzner is the wrong host for Tor bridges (TSPU 16-KB curtain) — 2026-05-06

Diagnosed our 14% bootstrap stall on RU MTS as the externally-known
"16 KB curtain" attack: TSPU silently freezes any TLS flow whose first
~16 KB carries a Tor consensus document, when the source IP is in the
Hetzner CIDR. Tor Project explicitly warns "do not host bridges with
Hetzner" for RU users. Outcome: pivot to Stage 5E (Xray) and Stage 5D
(FlokiNET non-Hetzner bridges). FlokiNET later also stalled, suggesting
the curtain is behavioural not ASN-based.

### D-9: Call signalling routed through DR + Sealed Sender — 2026-05-08

WebRTC SDP/ICE/answer/hangup envelopes now use the same `sendSealedPayload`
path as read receipts. CallManager loses RelayTransport reference, gets
MessagingService instead. Plaintext fast-path in `handleDeliver` removed.
[ADR-025](../adr/ADR-025-Call-Signaling-E2EE.md).

### D-8: Ratchet state Keystore-wrapped on disk — 2026-05-08

`RatchetState` BLOB persisted to SQLite is now AES-256-GCM wrapped via
Android Keystore (alias family `phantom_ratchet_wrap_v1`). Same primitive
as ADR-023 (prekey wrap); same lazy-migration pattern.
[ADR-024](../adr/ADR-024-Ratchet-State-Keystore-Wrap.md).

### D-7: SPK + OPK private bytes Keystore-wrapped — 2026-05-08

Same wrap primitive as identity key; alias `phantom_prekey_wrap_v1`. Lazy
migration: rows rewrite themselves on next `maybeReplenishOneTimePreKeys` /
`maybeRotateSignedPreKey`. [ADR-023](../adr/ADR-023-Local-Prekey-Keystore-Wrap.md).

### D-6: Repo cleanup — `google-services.json` removal + Firebase rotation — 2026-05-08

Live Firebase API key was committed in `apps/android/google-services.json`.
Removed via `git rm --cached`, added to `.gitignore`, key rotated in Firebase
Console. **No git history rewrite** — Firebase keys are publishable by design,
and force-pushing the public repo's history was assessed as more risk than
value. PR #46 (cleanup) + #47 (rotation log).

### D-5: Codeberg mirror enabled — 2026-05-08

Mirror at `https://codeberg.org/VladislavLiudvig/Phantom` via GitHub Action
workflow `.github/workflows/mirror.yml`. Reduces single-host (GitHub) takedown
risk and signals openness to the broader FOSS infrastructure ecosystem.

### D-4: Hard cut-over over migration windows — repeated decision — 2026-05-08

Three protocol-breaking changes shipped without migration windows: F2+F13
(SenderKey signing removal, migration `14.sqm`), F3 (HKDF KDF, `_v2` info
strings), F1+F11+F26 (group + auth wire format). Justified by "Alpha 2 not
released publicly, no production users." This shortcut is no longer available
once Alpha 2 is tagged.

### D-3: Tor + UnifiedPush hybrid (`onionwrapper` rejected, `kmp-tor` chosen) — earlier sprint

For embedded Tor on Android, evaluated `onionwrapper` (community-maintained)
vs. `kmp-tor` (KMP-native binding to upstream Tor). Chose `kmp-tor` for
Kotlin Multiplatform alignment + iOS expansion path. Push uses self-hosted
ntfy (no Google FCM dependency to preserve zero-third-party-metadata posture).
[ADR-016](../adr/ADR-016-tor-unified-push-hybrid-transport.md).

### D-2: Custom Double Ratchet over libsignal-client — 2026-04 (foundational)

`libsignal-client` is GPL-3.0; the relay's AGPL-3.0 §13 reach has compatibility
friction with GPL on shared types. Self-roll Double Ratchet on libsodium
(ISC-licensed, audited, aggregation-clean against AGPL).
[ADR-006](../adr/ADR-006-Crypto-Library-Decision.md).

### D-1: Identity / signed prekey / one-time prekey separation — 2026-04 (foundational)

X25519 identity is the routing pubkey; Ed25519 signing keypair is independent
(SignedPreKey signature, now also WS auth challenge signature). Prevents
F13/F14/F15 class of "single-key compromise = total impersonation" attacks.
[ADR-009](../adr/ADR-009-identity-prekey-separation.md). The same Ed25519
keypair F11+F26 leverages for WS auth.

---

## Document maintenance

This snapshot is point-in-time. Update either:
- when a substantial decision lands (append to section 10);
- when a major component goes from ⚠️ → ✅ or vice versa (update sections 1, 2, 5, 6);
- before a milestone (Alpha 2 tag, Beta release) so the audit trail is fresh.

Read alongside:
- [`MASTER_TIMELINE_2026.md`](MASTER_TIMELINE_2026.md) — track-by-track tabular status.
- [`PROJECT_LOG.md`](../PROJECT_LOG.md) — session-by-session journal.
- [`docs/security/SECURITY_ROADMAP.md`](../security/SECURITY_ROADMAP.md) — public security ledger.
- [`docs/adr/README.md`](../adr/README.md) — ADR index.
- [`KNOWN_ISSUES.md`](../../KNOWN_ISSUES.md) — Alpha-1 baseline issues + status.
