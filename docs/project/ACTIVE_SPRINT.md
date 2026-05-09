# PHANTOM — Active Sprint

> **What we're working on right now.** Updated after every PR merge and at
> the end of every working session. This is the document a fresh session
> reads first to know "where are we, what's next."
>
> Sister documents:
> [`DECISIONS_LOG.md`](DECISIONS_LOG.md) — append-only product decisions.
> [`TECHNICAL_BACKLOG.md`](TECHNICAL_BACKLOG.md) — prioritised waiting items.
> [`MASTER_TIMELINE_2026.md`](MASTER_TIMELINE_2026.md) — high-level tracks.

**Last updated:** 2026-05-09 (Step 1 in flight)

---

## Sprint name

**Alpha 2 — Release Candidate.** Get every code-side requirement in master,
land the design polish to match the Figma spec, then tag `v0.1.0-alpha.2`.

## Sprint goal

A debug APK installable on any Android (>= API 26) device that:
1. Connects through the adaptive transport stack (direct → REALITY → Tor) per
   the user's Privacy Mode without manual `local.properties` flags.
2. Renders every screen consistent with `Design/PHANTOM_FULL_COMPOSE.md`
   (no placeholder strings, no design-pass TODOs).
3. Passes the 8-scenario cross-device test matrix in
   [`PRIVACY_MODE_BEHAVIOR.md`](../PRIVACY_MODE_BEHAVIOR.md) §"Test matrix"
   without regressions.

After tag → operational task list (keystore backup, release notes), then post-
Alpha-2 work begins: ADR-007 (username directory), ADR-008 (verification
authority), demo video (Vladislav), funding-application paperwork.

---

## Step ordering (sequential unless marked parallel)

| # | Step | Branch | Estimate | Status |
|---|---|---|---|---|
| 0 | Persistence layer | `feat/project-persistence-docs` | 30 min | ✅ merged (#82) |
| 1 | Bug #2 + Bug #3 fix (notification + retry) | `feat/notification-and-reset-fixes` | ~1 h | ✅ merged (#83) |
| 2 | Stage 5G Phase 1 experiment (FlokiNET reuse + obfs4) | `feat/stage-5g-phase-1-bridges` + `feat/obfs4-bridge-line-populated` | 2-3 days | 🟡 server deployed 2026-05-09, bridge line populated; awaiting Test 13.1 cross-device |
| 3 | Settings rewrite per FULL_COMPOSE §06 | `feat/settings-screen-rewrite` | 4-6 h | 🟡 in progress (PR open) |
| 4 | Other-screen UI audit + rewrite (multiple PRs) | per screen | 3-5 days | ⬜ |
| 5 | Operational: keystore backup + tag | n/a | <1 h | ⬜ |
| 6 | Post-Alpha-2: ADR-007/008 drafts + demo + paperwork | per item | parallel | ⬜ |

Steps 1, 2, 3 do **not** block each other beyond shared QA budget — Step 1
is small, Step 3 is single-file isolated, Step 2 is server-side mostly.
Step 4 is the long tail and benefits from Step 3 landing first (reuses the
component conventions).

---

## Step detail

### Step 0 — Persistence layer (this PR)

Branch: `feat/project-persistence-docs`

Three new docs in `docs/project/`:
- `DECISIONS_LOG.md` — D-1 through D-19, append-only.
- `ACTIVE_SPRINT.md` — this file.
- `TECHNICAL_BACKLOG.md` — prioritised list.

Solves the "context lost between sessions" complaint: a fresh session reads
these three files and knows the current state without trawling the chat
transcript.

### Step 1 — Bug #2 + #3 fix

Branch: `feat/notification-and-reset-fixes`

Cross-device QA report 2026-05-09:
- **Bug #2** — switching Standard → Private leaves the foreground notification
  text unchanged because the working transport (REALITY) is the same. Fix:
  include `PrivacyMode` in the notification text (`Online via Reality ·
  Standard` → `Online via Reality · Private`).
- **Bug #3** — after a Ghost-mode chain failure, switching back to Standard
  leaves the notification stuck on `Cannot reach relay (tried 1). Tap to
  retry.` and the tap is a no-op. Fixes:
  - Reset notification text → `DEFAULT_STATUS_TEXT` inside
    `TransportManager.release()`.
  - Either remove the misleading "Tap to retry" or wire a real
    `PendingIntent` that triggers a `setPrivacyMode(current)` reload.
  - Force `transport.startReceiving()` after the fresh connect lands.
  - Add a debug log in the notification updater to make state transitions
    visible during QA.

Deliverable: small PR + cross-device retest on Tecno + emulator. Then
update `KNOWN_ISSUES.md`.

### Step 2 — Stage 5G Phase 1 experiment

Branch: `feat/stage-5g-phase-1-bridges`

Per [D-16](DECISIONS_LOG.md#d-16-stage-5g-phased--reuse-flokinet--obfs4snowflake-experiment--2026-05-09):

- Server-side: deploy obfs4 + Snowflake bridges on the existing FlokiNET VPS
  (config change, leverage existing onionwrapper knowledge — no new VPS).
- Client-side: extend `OperatorBridges.kt` with the new bridge entries.
- Wire through the Privacy Mode Ghost path in `TransportManager`.
- **Test 13.1** on Tecno МТС in Ghost mode.
- Document results in `docs/research/stage-5g-phase-1-2026-05-XX/`.
- Update `DECISIONS_LOG.md` with the outcome (continue Stage 5G full vs
  fall back to Variant C in-app warning).

### Step 3 — Settings rewrite per Figma

Branch: `feat/settings-screen-rewrite`

Source of truth: `Design/PHANTOM_FULL_COMPOSE.md` §06 +
`Design/src/app/components/phase2/SettingsScreen.tsx`.

Structure (top → bottom):
1. **Profile card** (avatar + name + `@username` + tier badge + chevron).
2. **Account** — Profile, Username, Plan (with Upgrade badge per [D-18](DECISIONS_LOG.md#d-18-pro-infrastructure-in-alpha-2--full-ui-no-payment--2026-05-09)).
3. **Privacy & Security** — Encryption Protocol (value `ED25519`), Privacy
   Mode (value `Standard`/`Private`/`Ghost`, chevron → detail screen),
   Read Receipts (toggle), Last Seen (value), Screenshot Protection
   (toggle + Pro badge).
4. **Notifications** — Messages Alerts (toggle), Call Alerts (toggle),
   Sound (chevron).
5. **Appearance** — Theme (Locked badge), Language (chevron).
6. **Advanced** — Storage & Cache (value), Export Data (chevron).
   _(Developer Mode dropped per [D-17](DECISIONS_LOG.md#d-17-developer-mode-toggle-removed-from-settings-ui--2026-05-09).)_
7. **About** — Version, Send Feedback, Privacy Policy.

Privacy Mode pills move out of the inline Settings row into a dedicated
`PrivacyModeDetailScreen` (re-uses the existing pill picker + Ghost confirm).

Step 3 outcome depends on Step 2 result:
- **Phase 1 success** → Ghost is presented as a clean Pro option globally.
- **Phase 1 fail** → Ghost row in the detail screen shows a checkpoint
  warning when the device locale or carrier indicates RU.

### Step 4 — Other-screen UI audit + rewrite

Per Vladislav priority:

| Screen | FULL_COMPOSE § | Priority |
|---|---|---|
| ChatList | §03 | critical (first screen after login) |
| ChatScreen | §05 | critical (where users live) |
| OnboardingScreen | §09 | critical (first impression) |
| ProfileScreen | §07 | important (4-zone spec) |
| AddContact | §10 | important (discovery flow) |
| VerificationScreen | §12 | important (security UX) |
| Premium / Upgrade | §11 | important (Pro UI showcase per D-18) |
| CallsScreen | §08 | Beta scope (calls remain experimental) |
| Empty states | §13 | low (nice-to-have) |
| Notification / Backup / Search | §14 | low |

Each screen → its own PR. Update `ACTIVE_SPRINT.md` after each merge.

### Step 5 — Operational

Vladislav-only (already partly done):
- ✅ Keystore backup to USB + cloud (Vladislav 2026-05-09).
- ⬜ `git tag -a v0.1.0-alpha.2 -m "..."` once Steps 1-4 are merged + QA passes.
- ⬜ Update `README.md` Status badge: `Alpha 2 → Alpha 2 RC` once tagged.
- ⬜ Generate fresh `PROJECT_STATUS_SNAPSHOT_<date>.md` snapshot.

### Step 6 — Post-Alpha-2 (parallel after tag)

- Vladislav: record demo video (5-10 min, Tecno МТС via REALITY without VPN).
- Claude Code: ADR-007 (username directory) draft.
- Claude Code: ADR-008 (verification authority) draft.
- Claude Code: update funding-application body with current state.
- Submission target: ~6 weeks after tag.

---

## Maintenance protocol

After **every PR merge**:
1. If the PR landed a decision → append entry in
   [`DECISIONS_LOG.md`](DECISIONS_LOG.md) (D-N+1).
2. Update the relevant Step row above (status emoji + branch link).
3. If new items surfaced (bugs, follow-ups) → append to
   [`TECHNICAL_BACKLOG.md`](TECHNICAL_BACKLOG.md).

Before milestones (tags, releases, external review):
- Regenerate `PROJECT_STATUS_SNAPSHOT_YYYY_MM_DD.md`.
- Re-read this file; archive completed steps; rewrite Sprint goal if it
  has shifted.

---

## Pointers to live state

- Latest PR queue: <https://github.com/LiudvigVladislav/Phantom/pulls>
- Latest master: `git log --oneline -10`
- ADR index: [`docs/adr/README.md`](../adr/README.md)
- Security finding ledger: [`docs/security/SECURITY_ROADMAP.md`](../security/SECURITY_ROADMAP.md)
