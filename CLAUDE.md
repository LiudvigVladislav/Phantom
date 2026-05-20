# PHANTOM — Agent Operating Guide

## Repo

- **GitHub**: `LiudvigVladislav/Phantom` (default branch `master`).
- **Codeberg mirror**: `VladislavLiudvig/Phantom` (auto-mirrored via `.github/workflows/mirror.yml`).
- **Author identity**: Vladislav (`WladislaWLE`). Solo-author repo — never add `Co-Authored-By: Claude` footers to commits or PRs.

## GitHub operations — you can do them yourself

`gh` CLI is installed and authenticated as `LiudvigVladislav`. Use it directly. Do not dictate copy-paste commands to the user for git/GitHub operations — execute them.

Typical workflows:

```bash
# Push a feature branch
git push -u origin <branch-name>

# Open a PR with title + body
gh pr create --base master --head <branch> --title "<title>" --body "$(cat <<'EOF'
<body markdown>
EOF
)"

# Inspect / merge / comment
gh pr view <PR#>
gh pr checks <PR#>
gh pr merge <PR#> --squash --delete-branch
gh pr comment <PR#> --body "<text>"
gh issue list / gh issue create / gh issue close
```

If `gh` is somehow unavailable, fallback is `git credential fill` (token is in Windows Credential Manager) plus PowerShell `Invoke-RestMethod` to `https://api.github.com/repos/LiudvigVladislav/Phantom/pulls`. Never echo the token into chat output.

## Public-artifact conventions

- **Language**: PR titles, descriptions, commit messages, issues, README, ADRs, public docs — **English only**. Russian only for chat with the user.
- **Voice**: first-person ("I added X", not "Vladislav added X").
- **No grant-application mentions** in public artefacts (README, ADRs, ARCHITECTURE, Threat Model, public PR descriptions). Grant context lives in internal docs only (`docs/PROJECT_LOG.md`, `docs/project/MASTER_TIMELINE_2026.md`).
- **No Claude attribution footers** in commits or PR bodies.

## Where the source of truth lives

- **Master timeline / track status**: `docs/project/MASTER_TIMELINE_2026.md` — single source of truth for all four work tracks (Reliability, Security, Grant Readiness, Alpha 2 Features). Check this first when asked "what's next?" or "where are we?"
- **Session journal**: `docs/PROJECT_LOG.md` — reverse-chronological session entries, decisions, lessons.
- **ADRs**: `docs/adr/` (numbered ADR-001 … ADR-027).
- **Architecture overview**: `ARCHITECTURE.md` + `docs/ARCHITECTURE.md`.
- **Threat model**: `docs/threat-model/Threat_Model_v0.md` (English exec summary at top, Russian body below).
- **Known issues**: `docs/KNOWN_ISSUES.md`.

Update `MASTER_TIMELINE_2026.md` + append to `PROJECT_LOG.md` at the end of any session that merges a PR or makes a non-trivial decision. Both live in the repo — commit them with the work.

## Local + VPS layout

- **APK output**: `apps/android/build/outputs/apk/debug/android-debug.apk` is the single source for in-flight test builds. `Releases/` is for signed release builds only.
- **Relay VPS**: SSH `phantom@relay.phntm.pro`. Repo lives at `~/Phantom` (not `/opt/phantom`).
- **Relay redeploy**: `cd ~/Phantom && git pull origin master && docker compose -f deploy/docker-compose.yml up -d --build relay` — Docker Compose builds the Rust relay inside the container; no local cross-compile needed.

## Working norms

- **Quality over deadlines** (Vladislav 2026-05-14 strategic reset). No grant-deadline pressure. Milestones (Alpha-2, Beta) are not targets in themselves — functional quality is the target. Tag a release only when real-device evidence supports the claim. Never propose accelerating work to hit an artificial date.
- **Honest repo over polished repo.** README, ADRs, Threat Model, KNOWN_ISSUES describe what currently is, not what might be. No aspirational claims in public artefacts. If a feature is incomplete or unstable, KNOWN_ISSUES says so plainly.
- **7-times-measure rule**: before any finalising action (commit/push/merge/"done"), run a 7-point check. The user explicitly requires this.
- **Diagnose before fixing**: when in doubt, ship observability first (a "diag" PR) and let real-device data drive the fix. The PR-G3 → PR-G4 sequence and PR-H1a → PR-H1c sequence are both examples.
- **No backward-compat shims** unless explicitly asked. No defensive validation for impossible cases. Trust framework guarantees; validate only at system boundaries.
- **Don't narrate internal deliberation in user-visible text.** Brief updates only at key moments (found, changed direction, hit a blocker).

## Single-developer discipline rules (locked 2026-05-21)

Governing rules for solo work — applied to every session, every track.

1. **One active track at a time.** Only one feature/fix track in flight at a time. If a new finding surfaces while working on something else, it goes into `PROJECT_LOG.md` as a one-line "found X while doing Y — deferred", not into parallel development.

2. **One PR = one layer.** UI / transport / crypto / notifications / persistence / docs — each PR touches a single layer. Mixed PRs are split into separate ones *before* the code is written.

3. **Mini-lock before code.** Every new track starts with `docs/tracks/<track-name>.md` containing goal / scope / out-of-scope / test acceptance / parking conditions. The mini-lock file is committed *before* the feature branch opens.

4. **Two architectural failures = park.** If a PR breaks for an architectural reason twice in a row, park it and restart from `master` with a redesigned approach. A third attempt on the same architecture is not allowed.

5. **Hand-off note on context switch.** If work is interrupted (new bug surfaces, external event, urgent priority), write a `Last hand-off` note into the current track's mini-lock *before* switching. Without it, context is lost.

6. **Log everything to `PROJECT_LOG.md`.** PR opened, PR merged, fix landed, error lasting > 30 min, decision, parked track, restart — all of it. Format: date, type, track, branch/PR, what happened, what's next.

7. **New findings = log, not develop.** Discoveries during current work are recorded into `PROJECT_LOG.md`, not built. Logging takes 30 seconds; building takes hours.
