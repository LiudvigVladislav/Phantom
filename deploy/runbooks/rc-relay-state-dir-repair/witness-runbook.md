# PR-1b RC-RELAY-STATE-DIR-REPAIR — Mac-local witness runbook (v3.4.4, as executed)

**Type:** operator runbook, frozen at the executed version.
**Status:** executed 2026-07-19 (Mac Docker Desktop); PASS verified
2026-07-20 (independent verification snapshot in
`witness-verification-2026-07-20.md`).
**Target commit:** `27a06c8aa06d15f2a152268e00c606a5cefff828` (PR-1b
merge, PR #389).
**Executed image digest:**
`sha256:a07e53098650b6cac3585da3751d72c159b7ddce4d2b6a874078d1f6526198ce`
(pinned in `pr1b-mac-local-compose.yml`).

This is the repo-shipped version. The forensic archive of the
actual execution (evidence bundle + operator wrapper + tool
versions) is preserved outside this repo; see §12.

## Design origin

The runbook went through a multi-round staged review (v3 → v3.1 →
v3.2 → v3.3 → v3.4 → v3.4.1 → v3.4.2 → v3.4.3 → v3.4.4). Each
version is documented in the "What changed in vN" sections below;
those sections are kept verbatim as pre-execution changelog so a
future reader can reconstruct why each guard exists. The version
that actually ran on 2026-07-19 is v3.4.4.

## v3.4 — reclassification from 24h staging-VPS to 2h Mac-local

**Context (locked upstream):** there is no isolated staging VPS.
The only VPS is production `relay.phntm.pro`, which must not be
touched for this witness. A 24-hour soak on the single operator
Mac was ruled disproportionate. The 24-hour staging-VPS
integration witness was therefore replaced by a **2-hour Mac-local
integration witness under Docker Desktop**.

**Artefacts introduced by v3.4 (all shipped in this directory):**

- `pr1b-mac-local-compose.yml` — isolated compose fixture. Pinned
  image digest, `platform: linux/amd64`, `read_only`, tmpfs
  `/tmp`, named volume, unique per-run stable `container_name`,
  network `pr1b-mac-net-<utc>`. No host ports.
- `pr1b-mac-local-witness.sh` — launcher. Guards: `uname -s ==
  Darwin`, `docker context == desktop-linux`, endpoint `unix://`,
  project name regex `^pr1b-mac-[0-9]{8}t[0-9]{6}z$`, absence of
  the production volume / container / project. `caffeinate -dims`
  holds the Mac awake for the whole window; caffeinate PID goes
  into evidence; mid-run death of caffeinate → FAIL. Sleep-gap
  detection between probes (`SLEEP_GAP_TOLERANCE_SECS=30`).
- `run-pr1b-mac-local.sh` — operator wrapper. Generates the stable
  fixture identity, sets the approved env vars, records the path
  of the most recent evidence bundle in `LAST-EVIDENCE.txt`.

**Locked timing parameters:**

- `PROBE_INTERVAL_SECS` hard-coded 900 (operator override refused).
- `TOTAL_ITERATIONS >= 8` (floor).
- `OBSERVATION_MIN_SECS >= 7200` (floor, 2h).
- All other behavioural gates from v3.3 preserved: 4 handlers with
  strict growth, full-window scan, container ID + RestartCount
  pin, planned force-recreate + full post-recreate re-attest.

**Accepted residual (explicit in the design lock):** Docker
Desktop's Linux VM plus amd64 emulation exercise the PR-1b
behaviour but do NOT provide native VPS performance/topology
coverage (page cache, disk queue depth, network latency). This
gate cannot stand in for a native VPS soak; it is a proportional
integration test that can actually be run today given the "no
staging VPS" constraint.

**Baseline context:** the PR-1a 72h soak (2026-07-17) ran on
production with the state_dir plumbing but did NOT cover the PR-1b
code (preflight, `.lock`, fail-loud persistence, rollback). That
soak reduces baseline risk (state_dir mechanism proven) but does
not replace integration coverage of PR-1b behaviour.

## v3.4.1 → v3.4.4 deltas

- **v3.4.1:** narrow review adjustments to the launcher guards.
- **v3.4.2:** frozen-vs-current section split. Sections describing
  the staging-VPS path (§3, §4.2, §7) were marked `FROZEN v3.3
  REFERENCE — DO NOT EXECUTE IN v3.4` so a reader cannot mistake
  the retained historical text for the live procedure.
- **v3.4.3:** `assert_no_host_ports()` now runs `docker port`
  inside an `if`-condition. This preserves fail-closed behaviour
  under `set -e` and ensures a Docker CLI error lands in
  verdict/evidence instead of a bare exit with `IN_PROGRESS`. The
  operator wrapper `run-pr1b-mac-local.sh` was added at the same
  time to make identity + env setup reproducible.
- **v3.4.4:** macOS BSD command corrections. Host-side file
  commands no longer use GNU-only `chmod ... --` form. The
  launcher and wrapper use BSD-compatible forms of `chmod`,
  `mkdir`, `stat`, `cat`, `rm`, `dirname`, `basename`, `readlink`.

## Mandatory env vars for `pr1b-mac-local-witness.sh`

| Var | Meaning |
|---|---|
| `COMPOSE_PROJECT_NAME` | Regex `^pr1b-mac-[0-9]{8}t[0-9]{6}z$`. |
| `COMPOSE_FILE` | Absolute path to `pr1b-mac-local-compose.yml`. |
| `COMPOSE_PROJECT_DIR` | Absolute path to the directory containing the compose file. |
| `WITNESS_IDENTITY_HEX` | 64 lowercase hex, stable identity across reruns (generated once via `openssl rand -hex 32` into a file OUTSIDE evidence). |
| `WITNESS_IMAGE` | Tag `pr1b-witness-client:local` (built from the frozen `witness-client/`). |
| `WITNESS_KEY_PATH` | Absolute path to the Ed25519 secret, OUTSIDE evidence, mode 0600. |
| `FIXTURE_SECRETS_DIR` | Absolute path to a fixture-only secrets directory (`RELAY_SEQ_MAC_KEY` and operator-side `RELAY_ADMIN_TOKEN` — passed to the relay as `RELAY_SECRET_TOKEN`). Mode 0700 dir + 0600 files, generated once, reused, OUTSIDE evidence. |

The operator wrapper populates all of these from a fixture layout
under `$HOME/.pr1b-witness/` (path chosen by the operator; the
runbook does not hard-code a user's home path).

## Execution order (v3.4)

1. **Build the witness image** (Mac, single time):
   `cd witness-client && docker buildx build --platform linux/amd64 -t pr1b-witness-client:local --load .`
2. **Build the relay image** (Mac, single time):
   `git checkout 27a06c8a && cd services/relay && docker buildx build --platform linux/amd64 -t phantom-relay:pr1b-27a06c8a --load .`.
   `docker image inspect phantom-relay:pr1b-27a06c8a --format '{{.Id}}'`
   must equal
   `sha256:a07e53098650b6cac3585da3751d72c159b7ddce4d2b6a874078d1f6526198ce`.
3. **Fresh-volume gate + read-only fixture:** run
   `pr1b-fresh-volume-gate.sh` then `pr1b-readonly-fixture.sh`
   (both must PASS before proceeding).
4. **Mac-local 2h witness:** invoke `run-pr1b-mac-local.sh`. The
   wrapper prepares env and calls the main launcher. Keep the Mac
   plugged in, do not close the lid, do not reboot. `caffeinate`
   protects against sleep; sleep-gap detection inside the script
   is belt-and-braces.
5. PASS → evidence bundle → this Ops PR.

**No VPS / SSH / SCP at any step.**

## What changed in v3.3 (vs v3.2)

- **P0 runtime blocker:** removed the redundant assignment
  `PROBE_INTERVAL_SECS="${PROBE_INTERVAL_SECS:-1800}"` after the
  `readonly` declaration in the guard block. Under `set -e` bash
  aborted immediately after the T0 gate PASS. A comment at the
  original site explains the removal.
- **P1 Mac↔VPS image ID automated verify:** a third artefact
  `pr1b-witness-client.image-id` is delivered alongside the
  tarball; on the VPS `sha256sum -c` plus a fail-closed
  `[ "$ACTUAL_ID" = "$EXPECTED_ID" ]` check. Removed the
  eyes-only manual comparison. (Superseded in v3.4 by the "no
  staging VPS" rule; historical.)
- **P2:** removed the paragraph proposing a future private
  registry. It incorrectly suggested reusing
  `EXPECTED_IMAGE_DIGEST` (that env var refers to the relay
  image, not the witness). Registry-based delivery, if ever
  needed, will come as a separate Ops-PR-scoped runbook.
- **P2 nit:** dropped the claim that `docker save` produces a
  deterministic tar. The Docker documentation does not guarantee
  it and the claim was unnecessary (the SHA of the specific
  tarball is authoritative on its own).

## What changed in v3.2 (vs v3.1)

**P0:**
- `PROBE_INTERVAL_SECS` hard-coded to 1800 s. The operator can no
  longer pass `PROBE_INTERVAL_SECS=1` to fake a PASS in a minute.
  `TOTAL_ITERATIONS` and `OBSERVATION_MIN_SECS` remain
  overridable ONLY upward.
- **Full-window log scan** before recreate:
  `docker logs --since $OBSERVATION_STARTED_RFC3339` covers the
  entire 24-hour window; `grep_anomalies` runs across it.
  Per-probe slices are now `PROBE_INTERVAL_SECS + 60`s wide (not
  2 min) — covering the whole gap between probes.
- **Regex guard for the main witness project name:**
  `^pr1b-staging-[0-9]{8}t[0-9]{6}z$`. The pre-3.2 blacklist of
  five names was bypassed by any other production project name.

**P1:**
- **Image delivery Mac → VPS** — the sole allowed path documented
  (`docker save → sha256 → scp → verify → docker load`) in §4.
  (Removed in v3.4 execution: no staging VPS.)

**P2:**
- Key re-verify after `pubkey-hex` created a file: regular file +
  owner UID + 0600 (tighten if it was weaker).
- `observation-ended-at.txt` is written AFTER the final probe +
  full-window scan pass, not before.

## What changed in v3.1 (vs v3)

**P0:**
- **Env-floor guard:** the script refuses to run when
  `TOTAL_ITERATIONS < 48`, `OBSERVATION_MIN_SECS < 86400`,
  `PROBE_INTERVAL_SECS <= 0`. Previously these could be dialled to
  `1` for a "formal" PASS. (Floors relaxed in v3.4 to match the
  2h Mac-local design.)
- **Final active probe before recreate:** after the wall-clock
  sleep the script now calls `drive_all_four_with_growth_check` +
  container ID/RC/health pin + fresh anomaly scan. The passive
  last 30 minutes were closed.
- **Key hardening:** `WITNESS_KEY_PATH` is now strictly checked —
  absolute, NOT a symlink, canonical resolution must be OUTSIDE
  `$EVIDENCE`, regular file, owner=UID, mode 0600, dir mode 0700
  always (not just on first creation).

**P1:**
- **Read-only fixture:** `assert_labels_and_mount` is called in
  both phases (seed `RW=true` + read-only `RW=false`). Compose
  labels + mount source + RW status now prove the FATAL was
  caused by the `:ro` volume, not another mount.
- **Fresh gate:** the `sleep 5` was replaced by a bounded loop up
  to `Running=true` + `state_dir_preflight_ok`
  (`FRESH_UP_DEADLINE_SECS=30`). Added `docker exec id` for
  UID/GID pin.
- **Boot log window:** `docker logs --since` is now anchored to
  the container's `.State.StartedAt`, not to a 24-hour window.
  The boot banner is found even if the staging stack was brought
  up more than 24h earlier.
- **Witness image ID pin:** T0 records `WITNESS_IMAGE_ID`; all
  `docker run` calls go via that immutable ID. Architecture is
  also attested (`WITNESS_IMAGE_ARCH == RELAY_IMAGE_ARCH`) —
  protection against Apple Silicon → amd64 mismatch.
- **Post-recreate CID change assert:** `NEW_CID !=
  PRE_RECREATE_CID_HEX` — a recreate that did NOT change the ID
  is a no-op FAIL.

## What changed in v3 (vs v2)

**P0 fixes:**
- Compose project names lowercase:
  `pr1b-witness-<utc-lowercase>` /
  `pr1b-readonly-<utc-lowercase>`. Stamp
  `date -u +%Y%m%dt%H%M%Sz`.
- The private Ed25519 key lives OUTSIDE evidence:
  `$WITNESS_KEY_PATH`. Evidence contains only the public key +
  fingerprint.
- Wall-clock 24h floor: `OBSERVATION_STARTED_AT` +
  `OBSERVATION_MIN_SECS=86400`. After 48 iterations wait the
  remainder of the 24h window before recreate. (Superseded by
  v3.4 2h floor.)
- Growth-check for all four files each iteration + fresh log
  slice AFTER the handler drive. Audit handlers return 2xx even
  on disk failure — `test -s` of the old copy no longer produces
  a false positive.

**P1 fixes:**
- Rust 1.88 in the Dockerfile.
- Fresh + read-only attest the image BEFORE `docker compose up`;
  post-up they cross-check `container.Image` against the
  pre-attested Image ID.
- Container ID pin via baseline hex plus a check in every probe
  iteration.
- Post-recreate re-attest of the whole chain: image
  revision/digest, labels project+service, mount, uid/gid,
  `.lock`.
- Anomaly scan: added `OOM`, `out of memory`, `SIGSEGV`.
- `sha256sum` fail-closed: exit 5 if the checksum step did not
  run.
- Portable sha256 utility (`sha256sum` or `shasum -a 256`).
- `AdminBlock` uses reqwest `.query([("token", …)])` — not raw
  interpolation.
- `WITNESS_IDENTITY_HEX` validated regex `^[0-9a-f]{64}$`.

---

## §1 Artefacts and execution order (v3.4)

Order: Mac-local, no VPS. Files `pr1b-staging-witness.sh` +
`deploy/staging-*` are no longer used (frozen v3.3 reference,
retained for history).

| Step | Script | Where | Duration | What it covers |
|---|---|---|---|---|
| 1 | `pr1b-fresh-volume-gate.sh` | Mac Docker Desktop | ~30 s | fresh-mount ownership + preflight_ok + full attestation |
| 2 | `pr1b-readonly-fixture.sh` | Mac Docker Desktop | ~30 s | `:ro` → `FATAL: preflight:` + exit > 0 + full attestation |
| 3 | `pr1b-mac-local-witness.sh` + `pr1b-mac-local-compose.yml` | Mac Docker Desktop | ~2 h (wall-clock floor 7200 s) | T0 gate + 8 probes × 900 s interval + wall-clock floor + final probe + full-window scan + planned recreate + full post-recreate re-attest |

Steps 1 and 2 PASSed 2026-07-19. Step 3 executed 2026-07-19,
PASSed, independently verified 2026-07-20.

**Frozen v3.3 reference (not used in v3.4 execution):**
`pr1b-staging-witness.sh`. Retained in scratch in case an
isolated staging VPS appears later — the 24-hour path would then
return as a separate decision. There is no such VPS today; the
file is kept only for history (see §12).

---

## §2 Boundaries (what this witness does NOT do)

- Does not run on production. No script can touch
  `deploy_phantom-reports` — layered guards (lowercase project
  name regex, mount source attest, image pre-up attest, container
  labels, container ID pin).
- Does not open the Ops PR — that is a separate step (this
  runbook is a member of the Ops PR body).
- Does not touch PR-1c poison-envelope or PR-2 queue durability.
- Does not use the Android client. The HTTP client is invoked
  inside the Docker network namespace of the relay container
  (direct container-loopback).
- Does not change any release flag.

---

## §3 — FROZEN v3.3 REFERENCE — DO NOT EXECUTE IN v3.4

> §3 describes the staging-VPS execution path that no longer
> exists in v3.4 (no staging VPS). Retained only as historical
> context in case a real staging VPS appears later and the 24h
> path re-opens. **Do NOT follow this section for the current
> Mac-local execution — see §1, §10 for v3.4 flow.**

## §3 Image requirements before staging execution (frozen)

Image built from the repo at `27a06c8a` (or including it as top
commit).

**Required option A — OCI revision label:**
Ops PR adds to `services/relay/Dockerfile`:
```dockerfile
ARG VCS_REF
LABEL org.opencontainers.image.revision=${VCS_REF}
```
and passes `--build-arg VCS_REF=$(git rev-parse HEAD)` in the
build workflow.

**Fallback option B — explicit digest:**
If the label is not yet added: the operator records
`docker inspect <image> --format '{{.Id}}'` at deploy time and
passes it via `$EXPECTED_IMAGE_DIGEST`.

All three scripts accept EITHER label match OR explicit digest.
Neither set or neither matched → exit ≠ 0 with NO
`docker compose up` (attest runs first).

---

## §4 Rust helper (`witness-client/`) — build and delivery

### 4.1 Local build

```bash
cd witness-client
docker build --no-cache -t pr1b-witness-client:local .
```

**On Apple Silicon Mac targeting amd64** — mandatory buildx with
explicit platform:

```bash
docker buildx build --platform linux/amd64 -t pr1b-witness-client:local --load .
```

Otherwise the image is arm64 and on amd64 the `docker run` will
return `exec format error`. The main witness at T0 checks the
architecture match and fails with an explicit message.

Runtime: `gcr.io/distroless/cc-debian12:nonroot`. No shell, no
utilities inside — only the binary.

MSRV: rust 1.88 (Dockerfile pin). `Cargo.lock` contains `rand
0.10`, `syn 3` — modern toolchain required.

### 4.2 — FROZEN v3.3 REFERENCE — DO NOT EXECUTE IN v3.4

> §4.2 describes Mac → VPS delivery, which is out of scope in
> v3.4 (no staging VPS). Delivery artefacts (`.tar` + `.sha256` +
> `.image-id`) may exist locally on a Mac from an earlier build
> attempt but were NOT transferred anywhere. Retained here for
> reference only. **Do NOT execute in v3.4.**

### 4.2 Delivery Mac → staging VPS (frozen, sole allowed path)

`buildx --load` places the image only in the LOCAL Docker daemon
(on the Mac). If a staging VPS ever existed the main witness
would run there; the image would be imported via
`docker save`/`docker load` with a fail-closed check of the
tarball SHA-256 AND the immutable image ID.

**On Mac (after successful `buildx --load`):**
```bash
# 1. Immutable image ID — source of truth for VPS-side verify.
docker image inspect pr1b-witness-client:local --format '{{.Id}}' \
    > pr1b-witness-client.image-id

# 2. Export tarball.
docker save pr1b-witness-client:local -o pr1b-witness-client.tar

# 3. SHA-256 of the tarball.
shasum -a 256 pr1b-witness-client.tar > pr1b-witness-client.tar.sha256
```

**Transfer (Mac → VPS, any safe channel) — three files:**
```bash
scp pr1b-witness-client.tar \
    pr1b-witness-client.tar.sha256 \
    pr1b-witness-client.image-id \
    operator@staging-vps:/tmp/
```

**On VPS (before first main witness invocation) — fail-closed:**
```bash
cd /tmp
set -euo pipefail

sha256sum -c pr1b-witness-client.tar.sha256
docker load -i pr1b-witness-client.tar

EXPECTED_ID="$(cat pr1b-witness-client.image-id)"
ACTUAL_ID="$(docker image inspect pr1b-witness-client:local --format '{{.Id}}')"
if [ "$ACTUAL_ID" != "$EXPECTED_ID" ]; then
    echo "FATAL: witness image ID mismatch"
    echo "  expected (from Mac): $EXPECTED_ID"
    echo "  actual   (on VPS)  : $ACTUAL_ID"
    exit 1
fi
echo "OK: witness image ID matches Mac source-of-truth"
```

**Then:** the operator sets
`WITNESS_IMAGE=pr1b-witness-client:local` in the main-witness
env. The script resolves the tag → immutable ID and pins it for
the whole run (round-3.1 P1 #7).

Subcommands (implemented in both frozen and v3.4 witness client):

| Subcommand | What it does |
|---|---|
| `pubkey-hex` | Prints the derived `signing_pubkey_hex` and `signing_pubkey_fingerprint_sha256`. |
| `publish-prekeys` | Signed SPK + N OPKs via `POST /prekeys/publish`. |
| `report` | `POST /report`. |
| `admin-block` | `POST /admin/block` with `?token=…` via reqwest `.query(...)`. |
| `push-register` | `POST /push/register`. |

`--key-file` is a path INSIDE the container (`/witness-key/pr1b.key`
when mounted). The file is created with mode 0600 on first
invocation and reused on later ones. It does NOT live in evidence.

---

## §5 Fresh-volume gate

**Env vars:**

- `COMPOSE_PROJECT_NAME` — regex `^pr1b-witness-[0-9]{8}t[0-9]{6}z$` (lowercase).
- `RELAY_IMAGE`.
- `EXPECTED_MASTER_SHA=27a06c8aa06d15f2a152268e00c606a5cefff828`.
- `EXPECTED_IMAGE_DIGEST` (optional).
- `EVIDENCE_DIR` (optional).

**Order:**
1. Image pre-attest (label or digest) WITHOUT `up`.
2. Inline compose stack → `up -d`.
3. Post-up image ID cross-check (no drift from pre-attested).
4. Container labels + mount source + uid/gid + preflight_ok + `.lock`.
5. Cleanup: `down -v` of the isolated project.
6. `sha256sums.txt` fail-closed.

---

## §6 Read-only fixture

**Env vars:**

- `COMPOSE_PROJECT_NAME` — regex `^pr1b-readonly-[0-9]{8}t[0-9]{6}z$`.
- `RELAY_IMAGE`, `EXPECTED_MASTER_SHA`.
- `CI=true` OR `LOCAL_DEV_ACK=1`.

**Order:**
1. Image pre-attest WITHOUT `up`.
2. Seed phase: normal volume → `up -d`, wait for
   `state_dir_preflight_ok` in a bounded loop
   (`SEED_DEADLINE_SECS=15`).
3. Post-up image ID cross-check (seed).
4. Stop + rm seed.
5. Read-only phase: `:ro` volume → `up -d`.
6. `ps -a -q` (fast-exit visible), numeric ExitCode
   `^-?[0-9]+$` strictly `> 0`.
7. Grep `FATAL: preflight:` in ANSI-stripped log.
8. `sha256sums.txt` fail-closed.

The merged code does NOT emit a `state_dir_preflight_failed`
event. On preflight failure the relay writes
`FATAL: preflight: cannot ...` via `eprintln!` from the panic
message. We grep for exactly that string via `grep -F` (fixed).

---

## §7 — FROZEN v3.3 REFERENCE — DO NOT EXECUTE IN v3.4

> §7 describes the 24h staging-VPS witness. In v3.4 that
> execution is REPLACED by the 2h Mac-local witness
> (`pr1b-mac-local-witness.sh` + `pr1b-mac-local-compose.yml`).
> The v3.4 env vars, hardening, wall-clock floor and timing
> differ. **Do NOT follow this section for v3.4 execution — the
> current operator flow is at §1 and §10, and the mandatory env
> vars for the Mac-local launcher are documented in the header
> block "Mandatory env vars for `pr1b-mac-local-witness.sh`"
> earlier in this file.** §7 stays for historical context if a
> real staging VPS appears later.

## §7 Main staging witness (frozen v3.3 reference)

**Env vars (all mandatory):**

| Var | Meaning |
|---|---|
| `COMPOSE_PROJECT_NAME` | Regex `^pr1b-staging-[0-9]{8}t[0-9]{6}z$` (round-3.2 P0 #3). |
| `COMPOSE_FILE` | Absolute path to the project's compose file. |
| `COMPOSE_PROJECT_DIR` | Absolute path to the directory from which compose resolves paths. |
| `RELAY_CONTAINER` | Name of the running container. |
| `REPO_DIR` | Local repo checkout for git HEAD pin. |
| `EXPECTED_MASTER_SHA` | `27a06c8aa06d15f2a152268e00c606a5cefff828`. |
| `RELAY_INTERNAL_PORT` | Relay listener port inside the container. |
| `RELAY_ADMIN_TOKEN` | Admin token from relay config. |
| `WITNESS_IDENTITY_HEX` | 32-byte X25519 identity, regex `^[0-9a-f]{64}$`. |
| `WITNESS_IMAGE` | `pr1b-witness-client:local`. |
| `WITNESS_KEY_PATH` | Absolute path to the persistent Ed25519 secret. Do not default it — the operator sets it explicitly under `$HOME`. |

Optional overrides:
- `EXPECTED_IMAGE_DIGEST` (fallback if the OCI label is not set).
- `EVIDENCE_DIR`.
- `TOTAL_ITERATIONS` (default 48, upward only).
- `OBSERVATION_MIN_SECS` (default 86400, upward only).
- `RECREATE_DEADLINE_SECS` (default 90).

**`PROBE_INTERVAL_SECS` hard-coded to 1800 s** — any override
refused (round-3.2 P0 #1). The operator cannot compress the
24-hour window.

**Key storage (round-3.1 enforced):**
- `$WITNESS_KEY_PATH` — absolute, NOT a symlink, canonical
  resolution MUST be OUTSIDE `$EVIDENCE`. Script refuses to start
  on any violation.
- Existing file: must be a regular file, owner UID = current UID,
  mode 0600 (script tightens if it was weaker).
- Directory: 0700 — set unconditionally on every run (not only on
  first creation).
- Evidence bundle contains ONLY public info: `signing_pubkey_hex`
  + `signing_pubkey_fingerprint_sha256`.
- On a re-run the same file is reused → same
  `signing_pubkey_hex` → no TOFU `SigningKeyMismatch` on the
  relay.
- Key rotation is explicit operator action: `rm
  $WITNESS_KEY_PATH` → the next run generates a new one.

**Choice of `WITNESS_IDENTITY_HEX`:**
- Do NOT use constants like `aa…aa` long term. After the first
  successful publication `SigningKeyBindings` on the relay TOFU-
  registers the (identity, signing_pubkey) pair. A subsequent
  run with a rotated `$WITNESS_KEY_PATH` under the same identity
  yields 409 `SigningKeyMismatch`.
- Recommendation: the operator generates a unique staging
  identity once (`openssl rand -hex 32 >
  $HOME/.pr1b-witness/staging-identity.hex`) and reuses it
  together with the key.

**Execution:**

1. **T0 gate** — steps 1-10:
   - git HEAD pin;
   - image attest (label or digest);
   - container labels + mount source + RW + uid/gid + `/var/phantom` owner + `state_dir_preflight_ok` + `.lock`;
   - **baseline container ID hex** pinned;
   - `drive_all_four_with_growth_check`: snapshot sizes BEFORE → drive 4 handlers → snapshot AFTER → strict growth check per file → FRESH log slice AFTER → `grep_anomalies`;
   - rootfs clean.
2. **24h observation** — 48 iterations × 30 min at
   `PROBE_INTERVAL_SECS`:
   - each iteration a fresh-seed `drive_all_four_with_growth_check`;
   - container ID pin re-checked every iteration (defence against silent replace);
   - RestartCount pin.
3. **Wall-clock floor** — if less than `OBSERVATION_MIN_SECS`
   has passed since the last iteration, wait the remainder.
4. **T+24h planned recreate** —
   `docker compose up -d --force-recreate relay` with `-f` +
   `--project-directory`. Bounded liveness loop until
   `Running=true` + `Health` (`healthy` or `<none>`) +
   `state_dir_preflight_ok` in a fresh log.
5. **Post-recreate re-attest** of the whole chain: image
   revision/digest, labels project+service, uid/gid, mount
   source, owner, `.lock`. Baseline container ID updated (recreate
   is the sole expected ID change).
6. **Post-recreate `drive_all_four_with_growth_check`** with a
   unique seed, strict growth + fresh log slice.

Rationale for the 24h floor: the earlier 72-hour soak (PR-1a
2026-07-17) closed state_dir plumbing without PR-1b code. For
new PR-1b behaviour (preflight, `.lock`, fail-loud, rollback) 24
hours of active observation was judged sufficient. Wall-clock
enforcement — because 48 × 1800 s = 23h30m, not 24h.

---

## §8 FAIL triggers (summary)

| Trigger | Action |
|---|---|
| Guard (project name, mount source, image pre-attest, container labels) | exit 9 or 2 |
| HTTP != 2xx on any of the 4 handlers | FAIL |
| A file on the volume did not grow after a handler drive | FAIL |
| `event="audit_persist_failed"` / `"prekey_persist_failed"` / `"state_load_torn_lines"` | FAIL |
| `panic` / `FATAL:` / `OOM` / `out of memory` / `SIGSEGV` / `permission denied` / `state.persist.fail` in the log | FAIL |
| Word `ERROR` (POSIX `-w`) in the log | FAIL |
| `HTTP 5[0-9][0-9]` in the log | FAIL |
| Unexpected `RestartCount++` | FAIL |
| Container ID drift (silent replace) | FAIL |
| Post-recreate: preflight_ok not observed within deadline, OR mount drift, OR image drift, OR label drift, OR identity drift, OR a replay file went missing | FAIL |
| `sha256sum` / `shasum -a 256` did not run | exit 5 |

---

## §9 Evidence bundle

Each script writes to its own `$EVIDENCE`. The final action is
generation of `sha256sums.txt` **fail-closed**. No log lines are
written after the SHA.

- The Ed25519 key does NOT go into evidence — only
  `signing_pubkey_hex` + `signing_pubkey_fingerprint_sha256`.
- Everything else (HTTP responses, logs, mount attestation,
  image digest, labels) is in evidence.

After all three scripts PASS, the bundle is a ZIP of the three
evidence directories with the per-directory `sha256sums.txt`
cross-checked. The archive is attached to the Ops PR body (see
§12 for the archive name and SHA of the executed run).

---

## §10 Draft → execution flow (v3.4)

1. **Round-3.4.1 draft** (README + scripts + compose fixture) →
   narrow architect review.
2. Architect diff → edits, no execution.
3. Architect PASS on v3.4.1 → Mac execution (Docker Desktop):
   - **build witness image**:
     `cd witness-client && docker buildx build --platform linux/amd64 -t pr1b-witness-client:local --load .`.
   - **build relay image locally from the merged commit**:
     ```
     git -C <phantom-repo> checkout 27a06c8a
     cd services/relay
     docker buildx build --platform linux/amd64 -t phantom-relay:pr1b-27a06c8a --load .
     ```
     `docker image inspect --format '{{.Id}}'` MUST equal
     `sha256:a07e53098650b6cac3585da3751d72c159b7ddce4d2b6a874078d1f6526198ce`.
     If not, do not proceed.
   - `pr1b-fresh-volume-gate.sh` PASS.
   - `pr1b-readonly-fixture.sh` PASS.
4. Then Mac-local 2h witness under caffeinate: invoke
   `run-pr1b-mac-local.sh`. Keep the Mac plugged in, do not close
   the lid, do not reboot. Caffeinate plus in-script sleep-gap
   detection is belt-and-braces.
5. PASS → evidence bundle → Ops PR.

---

## §11 Host dependencies (Mac)

On the Mac before execution:
- Docker Desktop or OrbStack running:
  `docker compose version` succeeds.
- `cargo`, `rustc` on `PATH` — for `docker build --no-cache` inside
  the builder stage (the toolchain is downloaded from the image,
  but `cargo check` on the host is a useful preflight).
- `git`, `sed`, `awk`, `grep`, `find`, `xargs`, `jq` — POSIX
  standard.
- `sha256sum` (coreutils) OR `shasum -a 256` (BSD default) — the
  script picks whichever is available.

**ADB is NOT required for PR-1b** — the witness is server-side,
all requests go through Docker. ADB is only needed later for a
separate Android field smoke (verifying the new icon + chat
functionality) under a separate greenlight.

---

## §12 Provenance of the executed run

The evidence bundle of the 2026-07-19 execution is preserved as
an archive OUTSIDE this repo:

- Archive name: `pr1b-local-witness-PASS-20260719.zip`
- Archive SHA-256: `b92cbccc2e...` (full digest recorded in the
  Ops PR body).
- Contents: fresh-volume gate evidence + read-only fixture
  evidence + 2h Mac-local witness evidence + `sha256sums.txt`
  per directory.
- Independent verification: `witness-verification-2026-07-20.md`
  (this directory).

The archive is not committed to the repo; the runbook + verified
snapshot are the load-bearing artefacts in this Ops PR. If a
future auditor needs the raw bundle it can be re-attached out of
band; the SHA above is source of truth.

---

## §13 Sanity checks on draft

- `bash -n` on all three scripts → clean.
- `cargo check --release` in `witness-client/` → clean.
- `docker build --no-cache -t pr1b-witness-client:local witness-client/`
  before first invocation — the real toolchain match is proven
  by this build.
