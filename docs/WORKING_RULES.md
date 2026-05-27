# PHANTOM — Working Rules (Single-Developer Discipline)

**Locked:** 2026-05-21
**Scope:** Engineering process. Applies to every track and every PR.

These are the rules that govern how work flows through the repository. They formalise the patterns that already held intuitively across the recent M2 / REC / CANCEL sprints, where parallel exploration and mixed-layer PRs had started to cost more than they saved.

The rules are written for a single-developer baseline. They are also the contract any future contributor inherits.

---

## 1. One active track at a time

Only one feature or fix track is in flight at any moment. When a new finding surfaces while working on something else, it goes into `docs/PROJECT_LOG.md` as a one-line `"found X while doing Y — deferred"` entry. It does **not** start parallel development.

The reason: switching between half-finished tracks costs hours of re-loading context per switch, and finished-but-untested work tends to accumulate. One track at a time lets each one close cleanly before the next opens.

## 2. One PR = one layer

Each PR touches a single layer:

- **UI** (Compose composables, screens, design tokens)
- **Transport** (relay, networking, REST/WS)
- **Crypto** (ratchet, sealed sender, key management)
- **Persistence** (SQLDelight schema, repositories)
- **Notifications** (foreground service, push)
- **Docs** (PROJECT_LOG, ADRs, MASTER_TIMELINE, KNOWN_ISSUES, this file)

Mixed-layer PRs are split into separate ones **before** the code is written. The reviewer then knows exactly what surface a PR is changing, and a regression in one layer cannot mask another.

## 3. Mini-lock before code

Every new track starts with a file at `docs/tracks/<track-name>.md` containing:

- **Goal** — the one-sentence outcome.
- **Scope** — what the track will change.
- **Out of scope** — what it deliberately won't, even if tempting.
- **Test acceptance** — the concrete observation that decides PASS.
- **Parking conditions** — the failure modes that trigger a restart.

The mini-lock is committed to `master` **before** the feature branch opens. It is the contract the work commits to; if reality drifts from it during implementation, the mini-lock gets updated *first*, then the code follows.

## 4. Two architectural failures = park

If a PR breaks for an architectural reason twice in a row — meaning the chosen approach itself is wrong, not just a typo — the track is parked and restarted from `master` with a redesigned approach.

A third attempt on the same architecture is not allowed. Parking is the cheap signal that the chosen abstraction is wrong; pushing through it on the third try almost always burns more time than the redesign.

## 5. Hand-off note on context switch

If work is interrupted (a new bug surfaces, an external event preempts, a more urgent track lands), a `Last hand-off` note is added to the current track's mini-lock **before** switching. The note captures:

- The exact state of the code (branch name, last working commit).
- What was about to be done.
- What was the next decision to make.

Without this note, returning to the parked track later costs the same hours of context-rebuild that the mini-lock was supposed to prevent.

## 6. Log everything to `PROJECT_LOG.md`

Every observable change goes into the session journal:

- PR opened.
- PR merged.
- Fix landed.
- Error that took > 30 minutes to diagnose.
- A decision (especially one that rejects an obvious approach).
- A parked track.
- A restart.

Format: date, type, track, branch/PR, what happened, what's next.

The cost of an entry is 30 seconds. The cost of forgetting *why* a decision was made and re-arguing it three months later is hours.

## 7. New findings = log, not develop

Discoveries that surface during current work go straight into `PROJECT_LOG.md`. They are **not** developed inline.

Logging a finding takes 30 seconds. Building a fix for it takes hours, and worse, drags the current track off-course. The current track closes first; the new finding gets its own mini-lock and its own slot in the track queue.

## 8. Transport regression gate

Any Android transport PR that changes **chain selection, reconnect lifecycle, network-change handling, probes, or WS/REST fallback behaviour** must include a real-device Tele2 LTE smoke test before merge, or explicitly document in the mini-lock why that gate is not applicable.

A Wi-Fi-only PASS is **not sufficient** for these PRs. Wi-Fi is too permissive — Direct WS succeeds, the chain never falls through, and the REST fallback path that exists precisely *for* hostile carriers is never exercised. Tele2 LTE Иркутская is the closest carrier we test against on a regular basis; a Tele2 PASS proves that the fallback machinery actually runs.

This rule is **deliberately narrow**. It does NOT apply to:

- Docs / mini-lock / WORKING_RULES PRs that only describe transport (no behaviour change).
- UI PRs that don't touch the transport surface.
- Crypto PRs whose only transport touch is reading session state.
- Test refactors / fixture cleanups that don't change runtime paths.
- Trivial transport-adjacent fixes (typo in a log line, dead-code removal, renaming a private field).

The author of a transport-touching PR makes the call in the mini-lock: either "Test #N on Tele2 LTE is the acceptance test", or "this PR does not exercise Tele2-relevant paths because <reason>". Reviewers verify that explanation matches the actual diff.

Why this rule exists: PR-RECV-DIAG1 (#234) shipped after Test #86 PASS on Wi-Fi only. On Tele2 LTE the same APK could not deliver messages — a regression that an explicit Tele2 gate would have caught before merge. The fix for that regression is `PR-LTE-NETCHANGE1` (mini-lock at `docs/tracks/lte-netchange.md`).

---

## How these rules interact

Together the eight rules form a single flow:

1. New track → write a mini-lock (rule 3) → feature branch → one-layer PR (rule 2) → merge → log entry (rule 6) → track closes.
2. New finding during the track → log entry only (rule 7), no parallel work (rule 1).
3. Track stuck twice on architecture → park (rule 4) → log the parking reason (rule 6) → restart with new mini-lock (rule 3).
4. Track interrupted → hand-off note in the mini-lock (rule 5) → resume later.
5. PR touches Android transport behaviour → Tele2 LTE smoke test (rule 8) before merge, or documented carve-out in the mini-lock.

The rules are simple. The discipline is in applying them every time, especially under pressure.
