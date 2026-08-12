# RC-RELAY-STATE-DIR-REPAIR — operator runbooks

Operator-facing runbooks and frozen local-witness artefacts for the
`RC-RELAY-STATE-DIR-REPAIR` track. This directory ships with the Ops
PR that follows PR-1a (state_dir plumbing, PR #376) and PR-1b
(fail-loud persistence, PR #389).

## Contents

| File | What it is |
|---|---|
| `README.md` | This index. |
| `rotation.md` | Safe procedure to rotate the running relay image on the VPS after a new PR-1b-compatible build is available. |
| `quarantine.md` | Procedure to safely stop the relay when a persist-failure event or FATAL is observed, without corrupting state or hiding evidence. |
| `witness-runbook.md` | Repo-shipped Mac-local witness runbook v3.4.4 as executed on 2026-07-19. Reproduces the 2-hour integration witness under Docker Desktop. Provenance of the executed run — archive name and SHA — is in §12. |
| `witness-verification-2026-07-20.md` | Independent verification snapshot of the executed run. |
| `run-pr1b-mac-local.sh` | Wrapper the operator invokes to launch the witness. |
| `pr1b-mac-local-witness.sh` | Launcher that boots the isolated stack, drives the 8 probes + wall-clock floor + planned recreate, produces the evidence bundle. |
| `pr1b-mac-local-compose.yml` | Isolated compose fixture (`platform: linux/amd64`, pinned relay image digest, no host ports, unique per-run container name / network / volume). |
| `pr1b-fresh-volume-gate.sh` | Fresh-volume gate (step 1 of the local witness). |
| `pr1b-readonly-fixture.sh` | Read-only fixture (step 2 of the local witness). |
| `witness-client/` | Small Rust HTTP client the launcher invokes for the four persistence handlers. |

## Sequence position

Per `docs/tracks/rc-relay-state-dir-repair.md` §6, updated
2026-07-19 after "no staging VPS" ruling:

1. ✅ PR-1a merged (PR #376, `7b673288`).
2. ✅ PR-1b merged (PR #389, `27a06c8a`).
3. ✅ **Mac-local proportional witness PASS** (executed 2026-07-19,
   independently verified 2026-07-20 — see
   `witness-verification-2026-07-20.md`; archive name + SHA in
   `witness-runbook.md` §12; archive itself preserved out of band,
   not committed to the repo).
4. → **Ops PR** (this PR): compose memory ceiling, deploy-lint Inv-8, rotation and quarantine runbooks, frozen witness runbook + verification snapshot + rotation script. **You are here.**
5. → PR-2 implementation + CI (RC-RELAY-QUEUE-DURABILITY, separate track, gated on this PR merging).
6. → staging benchmark + `RELAY_QUEUE_RAM_BUDGET_BYTES` fix.
7. → PR-2 merge.

## What this PR authorises

- Compose file change: `deploy.resources.limits.memory: 512M` on the
  relay service (see `deploy/docker-compose.yml`) + matching **Inv-8**
  (typed string + regex `^[1-9][0-9]*[MG]$`) in
  `.github/workflows/deploy-lint.yml`.
- Rotation runbook (`rotation.md`) + rotation script
  (`rotate_seq_mac_key.sh`, INTENT+fsync sidecar per locked v4.2.3 §2).
- Quarantine runbook (`quarantine.md`) — INTENT+fsync atomic
  quarantine sidecar + post-stop running-guard.
- Frozen witness runbook + independent verification snapshot +
  frozen executable artefacts (fresh-volume gate, read-only
  fixture, main launcher, wrapper, compose fixture,
  `witness-client/` Rust helper).

## What this PR does NOT authorise

- Deploying the updated compose file to production. That is a separate
  operator action, gated on a follow-up deploy greenlight.
- Deploying the merged PR-1b image (`27a06c8a`) to production. Same.
- Starting or merging PR-2.
- Any release-flag change; `LONGPOLL_V2_ENABLED` + the three RC
  quiescence flags stay locked.
- Android field smoke — separate phone-required task, follow-up
  greenlight required.

## Change summary (for reviewers)

```
deploy/docker-compose.yml         + deploy.resources.limits.memory 512M
                                  + matching reservations.memory 96M
.github/workflows/deploy-lint.yml + Inv-8 (relay memory ceiling, typed string + regex)
deploy/runbooks/rc-relay-state-dir-repair/README.md              (new — this file)
deploy/runbooks/rc-relay-state-dir-repair/rotation.md            (new)
deploy/runbooks/rc-relay-state-dir-repair/rotate_seq_mac_key.sh  (new — INTENT+fsync sidecar)
deploy/runbooks/rc-relay-state-dir-repair/quarantine.md          (new)
deploy/runbooks/rc-relay-state-dir-repair/witness-runbook.md     (new — v3.4.4)
deploy/runbooks/rc-relay-state-dir-repair/witness-verification-2026-07-20.md (new)
deploy/runbooks/rc-relay-state-dir-repair/pr1b-*.sh              (new — frozen)
deploy/runbooks/rc-relay-state-dir-repair/pr1b-mac-local-compose.yml (new — frozen)
deploy/runbooks/rc-relay-state-dir-repair/run-pr1b-mac-local.sh  (new — frozen wrapper)
deploy/runbooks/rc-relay-state-dir-repair/witness-client/        (new — frozen Rust helper)
```

No production code changed. No release-flag changed. No new dependency
added to the runtime image.
