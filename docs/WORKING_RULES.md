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

## 9. No merge without verification

A PR is not mergeable until **at least one** of the following has happened:

1. **External architect (or designated reviewer) has approved** the PR explicitly, after reading the diff.
2. **Every concrete code-state claim in the PR description, mini-lock, or commit message has been verified against the source** by `grep` / `git log -p` / file read. "X exists at line N", "Y is wired", "Z is enabled", "the current log already shows W" — each such claim earns its own verification line in the PR description (file:line + grep output snippet, or "verified against `master <sha>` by reading `<file>`").

The second path exists so a docs-only / process-only PR doesn't have to wait for human review hours. The first path is required as soon as the PR touches behaviour. Both paths can apply to the same PR; that is the safest case.

This rule is also **deliberately narrow**. It does NOT apply to:

- Trivial typo / comment / formatting PRs whose diff contains no factual claim about other code.
- Auto-generated changelog / version-bump PRs whose only "claim" is "the version is now N+1".
- Reverts of a previously verified PR (the previous verification carries through).

Why this rule exists: today (2026-05-28) the LTE-NETCHANGE1 prep docs PR (#238) was merged after Wi-Fi-only verification by the author, before the external transport architect's review arrived. The architect found three P2 issues — one factual error ("the current log shows `ordered=[...]` but not `vpnActive/realityFiltered`" — actually those fields *are* already in the log at `TransportManager.kt:84`), one architectural ambiguity (`HybridRelayTransport` proposed as `notifyNetworkChanged` owner despite having zero references to `TransportManager` / `TransportPreferences`), and one stale queue (`PROJECT_LOG.md` upper "Consolidated queue (2026-05-23)" still listed already-shipped tracks at the top). Each of those would have been caught by a 30-second `grep` against the source, but the PR shipped before the check happened. The follow-up docs fix PR is the cost of that omission. This rule formalises the verification step so the same omission cannot repeat.

The discipline: before clicking "merge", paste the verification evidence into the PR description (architect approval ID, or file:line + grep output for each concrete claim). The cost is 30 seconds per claim; the cost of an unverified merge is hours of follow-up.

---

## How these rules interact

Together the nine rules form a single flow:

1. New track → write a mini-lock (rule 3) → feature branch → one-layer PR (rule 2) → merge → log entry (rule 6) → track closes.
2. New finding during the track → log entry only (rule 7), no parallel work (rule 1).
3. Track stuck twice on architecture → park (rule 4) → log the parking reason (rule 6) → restart with new mini-lock (rule 3).
4. Track interrupted → hand-off note in the mini-lock (rule 5) → resume later.
5. PR touches Android transport behaviour → Tele2 LTE smoke test (rule 8) before merge, or documented carve-out in the mini-lock.
6. Before clicking merge → either architect approval is on the PR, or every concrete code-state claim has been grep-verified and the evidence is in the PR description (rule 9).

The rules are simple. The discipline is in applying them every time, especially under pressure.
