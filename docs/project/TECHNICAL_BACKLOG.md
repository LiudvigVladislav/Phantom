# PHANTOM — Technical Backlog

> **Prioritised list of waiting items.** Anything that is not in
> [`ACTIVE_SPRINT.md`](ACTIVE_SPRINT.md) but should not be forgotten lives
> here. Re-prioritise after every PR merge if needed.
>
> Sister documents:
> [`DECISIONS_LOG.md`](DECISIONS_LOG.md) — append-only product decisions.
> [`ACTIVE_SPRINT.md`](ACTIVE_SPRINT.md) — current sprint state.
> [`MASTER_TIMELINE_2026.md`](MASTER_TIMELINE_2026.md) — high-level tracks.

**Last updated:** 2026-05-09

---

## P0 — Active sprint

These items are scheduled in [`ACTIVE_SPRINT.md`](ACTIVE_SPRINT.md). Listed
here for one-page completeness only.

| # | Item | Branch | Source |
|---|---|---|---|
| 1 | Bug #2 + #3 fix (notification + retry) | `feat/notification-and-reset-fixes` | QA 2026-05-09 |
| 2 | Stage 5G Phase 1 experiment | `feat/stage-5g-phase-1-bridges` | [D-16](DECISIONS_LOG.md#d-16) |
| 3 | Settings rewrite per FULL_COMPOSE §06 | `feat/settings-screen-rewrite` | Design audit 2026-05-09 |
| 4 | Other-screen UI rewrites (multiple PRs) | per screen | Design audit 2026-05-09 |
| 5 | Operational tag `v0.1.0-alpha.2` | n/a | Sprint goal |

---

## P1 — Post-Alpha-2 launch sequence

To be picked up in the days after tag.

| Item | Owner | Notes |
|---|---|---|
| ADR-007 — Username directory (relay-side namespace + rate-limited lookup) | Claude Code (draft) | Spec exists in `ARCHITECTURAL_DECISIONS_TODO.md`; needs full ADR |
| ADR-008 — Verification authority model (Willen LLC central vs distributed) | Claude Code (draft) | Trust anchor for username binding; Beta-scope decision |
| Demo video (5-10 min, Tecno МТС via REALITY without VPN, EN+RU captions) | Vladislav | Council recommended Day 3; not blocking ADRs |
| External funding application body update | Vladislav with Claude Code support | Current state worth re-pitching after Alpha 2 tag |
| Public write-up Stage 5E (HN + Хабр) | Vladislav | Optional momentum signal |

---

## P2 — Beta-scope code work

Larger features that wait for the Alpha-2 release window to close.

### Crypto / messaging

| Item | Notes |
|---|---|
| F6, F7, F9, F10, F12 retry, F14, F18, F23, F25 (Track B P2 batch) | Logging tightening, edge cases, validation hardening; cleanup pass during Beta polish |
| Multi-device support (linked-device trust, no shared key) | 3-4 weeks focused; needs design ADR |
| Plaintext-only-in-RAM for Ghost mode | Major architectural refactor; spec exists in design docs, no impl |

### Calls

| Item | Notes |
|---|---|
| **PR 2.6 — Calls audio plumbing** | `JavaAudioDeviceModule` + AudioFocus + suppress reconnect during call. Surfaces as one-way audio in 2026-05-09 cross-device QA. ~1 week. Bundles with the calls-leave-experimental milestone. |
| Calls UI polish per FULL_COMPOSE §08 | Active call screen, history, missed-call danger color |

### Transport

| Item | Notes |
|---|---|
| ADR-015 — Pluggable transports beyond REALITY + Tor (obfs4, Snowflake, fronting) | Driven by Stage 5G Phase 1 outcome (D-16) |
| ADR-020 / Phase-3-follow-on — Privacy Mode mid-conversation network change polish | Reconnecting state visible in UI when chain re-walks during a network handover |
| ADR-021 — Multi-server REALITY fan-out | Reduce single-Hetzner-IP correlation risk; mentioned in ADR-019 Phase 2 |
| ADR-022 — iOS XCFramework export of shared core | Stage gate for iOS port; mentioned in ADR-019 Phase 2 |

### Features

| Item | Notes |
|---|---|
| Attachments (encrypted MinIO) | ADR-013 draft exists; ~2 weeks server + client + per-attachment key wrap |
| Stable groups | F1 + F3 + F4 already closed; needs UX polish + scale test |
| Public channels (read-only broadcast with per-channel sender keys) | New primitive, ADR needed |
| Account migration via seed phrase | ADR-012 draft exists |
| Pinned issue "Roadmap to v1.0" on GitHub | 30 min cleanup |

### iOS

| Item | Notes |
|---|---|
| ADR-014 — iOS architecture (KMP shared core + SwiftUI shell) | 3-4 months solo work end-to-end |
| iOS port itself | Blocked on FUTO Microgrants application outcome ($2K Mac mini) |

### Operational

| Item | Notes |
|---|---|
| External monitoring / uptime check on `relay.phntm.pro/health` | Pingdom / Healthchecks.io / similar; currently zero alerting |
| Off-host JSONL backup for relay state (`prekeys.jsonl` etc) | Hetzner disk failure today wipes published bundles + reports |
| Firebase Console SHA-1 fingerprint allowlist | Defence-in-depth; rotation already done in PR #47 |

---

## P3 — Nice-to-have / parking lot

Lower priority items kept in the log so we do not lose them.

| Item | Notes |
|---|---|
| `.github/ISSUE_TEMPLATE/` + PR templates | Half-day; not on critical path |
| Full English translation of Threat Model + Doctrine (beyond exec summary) | After grant cycles when external review needs it |
| Pinned "Roadmap to v1.0" GitHub issue | Copy of `ROADMAP.md` with checkboxes |
| Hardened multi-device identity diagram in `ARCHITECTURE.md` | Once D-1 backed multi-device lands |
| Diagnostics suite (the Developer-Mode toggle's would-have-been job per [D-17](DECISIONS_LOG.md#d-17)) | Verbose logging, debug overlays, "send debug bundle" — separate sprint when needed |
| ADR-020 Phase 4 — runtime adaptive transport selection refinements | If real users surface chain-walk pathologies after Alpha 2 |

---

## Maintenance protocol

After every PR merge:
- Move completed items out of P0 into the relevant section of
  [`MASTER_TIMELINE_2026.md`](MASTER_TIMELINE_2026.md).
- Promote items between P0 / P1 / P2 / P3 if priority changed.
- Append new items surfaced in QA / review here at the right tier.

If a P3 item ages > 6 months untouched, either promote it or delete it
with a one-line justification in
[`DECISIONS_LOG.md`](DECISIONS_LOG.md).
