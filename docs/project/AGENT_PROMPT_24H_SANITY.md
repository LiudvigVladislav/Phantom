# 24-hour sanity sweep — agent prompt

**When to run:** ~24 hours after merging any non-trivial Phase 1 PR (PR A,
B, C, D, E, etc). Catches problems that only surface after CI accumulates
runs, the Codeberg mirror has had time to fall out of sync, or fresh
clones reveal missing files.

**How to run:** Vladislav invokes `/schedule` with the prompt below pasted
verbatim. The agent runs once, reports back, then exits. No follow-up
unless an alert is raised.

**Output contract:**
- If everything is clean: a single-line confirmation like
  `✅ Sanity sweep clean — master <SHA>, all CI green, mirror in sync.`
  Do not list checks that passed; the absence of an alert is the signal.
- If anything is wrong: an `⚠️ ALERT` block with the exact failing check,
  the offending command output, and a suggested next action. One alert
  block per failed check.

---

## Prompt

```
You are running a 24-hour sanity sweep on the PHANTOM repository. Your
job is to surface anomalies, not to fix them. Be brief.

Repo: https://github.com/LiudvigVladislav/Phantom (working tree at
d:\VL Stories Studio\Phantom).

Run these checks IN ORDER. After each check, decide silently whether it
passed. After the last check, output ONE of:

  ✅ Sanity sweep clean — master <40-char-SHA>, <N> open PRs, mirror
     in sync as of <ISO-8601 timestamp>.

  ⚠️ ALERT: <one-line summary>
     Check: <which check failed>
     Detail: <relevant output, ≤10 lines>
     Suggested action: <one sentence>

Do not produce any other output. Do not narrate the checks themselves.

── Checks ──

1. CI status on master
   - Fetch the most recent workflow runs on master via
     `gh run list --branch master --limit 10` (or curl
     api.github.com/repos/LiudvigVladislav/Phantom/actions/runs?branch=master).
   - Pass: every run in the last 10 has conclusion=success or skipped.
   - Fail: any conclusion=failure or cancelled in the last 10 master runs.

2. Codeberg mirror in sync
   - Compare GitHub master HEAD SHA against Codeberg master HEAD SHA via
     curl https://codeberg.org/api/v1/repos/VladislavLiudvig/Phantom/commits?sha=master&limit=1
     (note the case: VladislavLiudvig with capital V and L).
   - Pass: SHAs match.
   - Fail: SHAs differ. Likely cause: mirror workflow failed; check
     .github/workflows/mirror.yml runs.

3. Open PRs blocked by required-status-checks deadlock
   - List open PRs via gh pr list --state open --json
     number,title,statusCheckRollup,mergeStateStatus
   - For each PR with mergeStateStatus=BLOCKED, check whether all required
     check-runs have actually completed (any in "Expected — Waiting"
     state for >2 hours = stuck).
   - Pass: no PRs stuck waiting for check-runs that will never arrive.
   - Fail: any PR with a required check expected for >2 hours. Likely
     cause: paths-filter on workflow vs PR file scope (recurrence of the
     issue we fixed in commit a7259432 for relay.yml).

4. Fresh-clone gradle smoke test
   - In a temp dir: git clone --depth=1 the repo, cd into it, run
     `./gradlew :shared:core:identity:jvmTest :shared:core:crypto:jvmTest
     --rerun-tasks --quiet`.
   - Pass: BUILD SUCCESSFUL.
   - Fail: any compile error or test failure.
   - Skip on Windows runners (gradle wrapper chmod issue — the CI runner
     handles this differently).

5. Fresh-clone cargo smoke test
   - In the same temp dir: `cargo build --workspace --release --quiet`
     in services/.
   - Pass: clean build, zero warnings on phantom-relay.
   - Fail: any compile error or warning on phantom-relay.

6. Recent commits anomaly scan
   - git log --oneline -20 master
   - Pass: every commit has a conventional-commit prefix
     (feat|fix|chore|docs|test|ci|refactor) and a meaningful subject.
   - Fail: any commit with empty subject, "WIP", "tmp", or starts with
     a lowercase non-prefix word. These are tells of an accidental push.

7. Untracked source files in working tree
   - git status --porcelain | grep '^?? ' | grep -E '\.(kt|rs|toml)$'
   - Pass: no source files untracked (PDFs, design assets, .vscode, etc
     are fine).
   - Fail: any .kt/.rs/.toml file that should have been committed.

── End checks ──

If you discover an issue you cannot diagnose in <5 minutes, alert with
"Suggested action: hand to Vladislav for review" rather than spending
time on a deep dive. The point of this sweep is to catch obvious drift,
not to investigate.
```

---

## Why this exists

Three near-misses during Phase 1 Week 1–4 motivated this sweep:

1. **PR A initially pushed empty** (commit on local master, push of feature
   branch picked up nothing, local reset erased the work). Caught
   manually 30 minutes later via the GitHub PR UI showing "branches are
   identical". A 24-hour sweep would have caught this earlier if the
   reviewer had already merged.

2. **Codeberg mirror fell out of sync twice** during Week 2 — once from a
   trailing-newline bug in CODEBERG_REPO_URL, once from a username case
   mismatch. Each time GitHub master moved forward and Codeberg lagged
   silently for hours.

3. **Required-status-checks deadlock on PR A** — Relay CI's `paths`
   filter meant the workflow never fired for non-services PRs, so the
   required check sat in "Expected — Waiting" forever. Fixed in commit
   `a7259432`. The sweep guards against regressions of the same shape
   when future workflows add their own `paths` filters.

The sweep is **read-only**. It never pushes, never modifies branches,
never opens PRs. If the agent surfaces an alert, Vladislav decides what
to do — the agent's job is detection, not action.

---

## Update log

| Date       | Change                                          |
|------------|-------------------------------------------------|
| 2026-04-30 | Initial draft, alongside PR C (Week 4 SessionManager rewrite). |
