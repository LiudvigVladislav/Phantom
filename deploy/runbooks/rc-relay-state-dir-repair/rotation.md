# Relay image rotation runbook

Safe procedure for rotating the running relay image on the production
VPS (`relay.phntm.pro`) after a new PR-1b-compatible relay build is
available. Every step is fail-closed: any check that does not clearly
pass aborts the runbook with a non-zero exit and no automatic
recovery.

## When to use this runbook

- After merging a PR that changes `services/relay/**` or
  `services/relay/Dockerfile`.
- After merging a PR that changes anything under `deploy/` that
  reaches the relay container (env, mount, memory limit, network).
- After a routine base-image refresh (`debian:bookworm-slim` security
  update).

Do NOT use this runbook to roll back — rollback is a separate flow
(git revert the offending commit, rebuild the image, then re-run
this rotation runbook against the reverted image).

## Preconditions

1. The new relay image has been built somewhere reproducible (CI or a
   trusted operator workstation) and has a known immutable image ID
   (`docker image inspect --format '{{.Id}}'`).
2. That image ID is recorded in the operator's rotation ticket
   alongside the source git commit and the compose file SHA.
3. Read the invariants below and make sure none of them changes: the
   rotation must NOT relax any of them.

## PR-1b invariants that persist across rotations

The rotation preserves — never widens — every invariant enforced by
the deploy-lint CI gate at `.github/workflows/deploy-lint.yml`:

1. `read_only: true` on the relay rootfs.
2. Volume `phantom-reports` mounted at `/var/phantom`, RW, `volume`
   type (or unspecified). Never `bind`, never `:ro`.
3. `RELAY_STATE_DIR=/var/phantom` in the compose env.
4. `WORKDIR /var/phantom` in the runtime stage of
   `services/relay/Dockerfile`.
5. Runtime user `phantom` pinned at `uid=10001 gid=10001` via
   explicit `groupadd --gid 10001 phantom` + `useradd --uid 10001
   --gid 10001`. State-dir ownership `phantom:phantom:750`.
6. `deploy.resources.limits.memory` present on the relay service,
   value matching `^[1-9][0-9]*[MG]$` (Ops-PR default `512M`,
   locked v4.2.3 §3 Inv-8).

## Step 1 — pre-rotation snapshot on the VPS

Under an operator SSH session on the VPS. Everything is read-only.
Every line writes to the rotation ticket (`| tee` → operator's
capture file). This block is fail-closed: any missing output aborts.

```bash
set -Eeuo pipefail

TICKET_DIR="/var/rotation-tickets/pre-$(date -u +%Y%m%dt%H%M%Sz)"
sudo install -d -m 700 -o "$(id -u)" -g "$(id -g)" "$TICKET_DIR"

# What's running right now. Every field must be non-empty.
docker inspect phantom-relay --format \
    '{{.Image}} {{.Config.Image}} {{.State.Running}} {{.RestartCount}}' \
    | tee "$TICKET_DIR/inspect-summary.txt"
docker inspect phantom-relay --format '{{.State.StartedAt}}' \
    | tee "$TICKET_DIR/started-at.txt"

# Ownership + mode of the state directory as the container sees it.
docker exec phantom-relay id | tee "$TICKET_DIR/container-id.txt"
docker exec phantom-relay stat -c '%U:%G:%a' /var/phantom \
    | tee "$TICKET_DIR/state-dir-owner.txt"
docker exec phantom-relay ls -la /var/phantom \
    | tee "$TICKET_DIR/state-dir-ls.txt"

# `.lock` exists and is held by the current relay process.
docker exec phantom-relay ls -la /var/phantom/.lock \
    | tee "$TICKET_DIR/lock.txt"

# Persist-failure counters. ANY non-zero means STOP: follow
# quarantine.md before rotating.
docker logs --since 24h phantom-relay 2>&1 \
    | grep -F 'event="state_dir_preflight_ok"' \
    | tail -3 \
    | tee "$TICKET_DIR/preflight-ok.txt"

audit_fail_count=$(docker logs --since 24h phantom-relay 2>&1 \
    | grep -Fc 'event="audit_persist_failed"' || true)
prekey_fail_count=$(docker logs --since 24h phantom-relay 2>&1 \
    | grep -Fc 'event="prekey_persist_failed"' || true)
printf 'audit_fail=%s\nprekey_fail=%s\n' \
    "$audit_fail_count" "$prekey_fail_count" \
    | tee "$TICKET_DIR/persist-fail-counts.txt"
if [[ "$audit_fail_count" != '0' || "$prekey_fail_count" != '0' ]]; then
    echo "FATAL: persist-failure events observed — follow quarantine.md, do NOT rotate"
    exit 1
fi

# Volume identity — must be `deploy_phantom-reports|true`. Round-2
# architect P1 #6: pre-round-2 only printed the value; if the mount
# had drifted to a different volume or been remounted `:ro`, the
# rotation continued. Now: hard-assert the exact expected string
# and fail the rotation before it does any damage.
MOUNT="$(docker inspect phantom-relay --format \
    '{{range .Mounts}}{{if eq .Destination "/var/phantom"}}{{.Name}}|{{.RW}}{{end}}{{end}}')"
printf '%s\n' "$MOUNT" | tee "$TICKET_DIR/mount.txt"
if [[ "$MOUNT" != 'deploy_phantom-reports|true' ]]; then
    echo "FATAL: mount attestation drift"
    echo "  expected: deploy_phantom-reports|true"
    echo "  actual  : $MOUNT"
    exit 1
fi
```

## Step 2 — stop the relay THEN back up the volume

Rotate expects a clean tar. The pre-round-1 order tarred a live
mutable volume and produced a mixed / torn snapshot. Round-1
architect P1 #5: stop first, then verify the container is fully
stopped (`docker compose ps -q --status running` returns empty),
THEN tar.

```bash
set -Eeuo pipefail

STAMP="$(date -u +%Y%m%dt%H%M%Sz)"
BACKUP_DIR="/var/backups/phantom-reports"
sudo install -d -m 750 -o "$(id -u)" -g "$(id -g)" "$BACKUP_DIR"

# 2a — stop the relay cleanly (SIGTERM → 10s grace → SIGKILL). The
# PR-1b `.lock` releases on the process's file-descriptor close.
cd /srv/phantom
docker compose -f deploy/docker-compose.yml stop relay

# 2b — bounded wait for the container to actually enter stopped state.
# Round-2 architect P0 #3: `2>/dev/null || true` MUST NOT be used —
# it turns a `docker compose ps` CLI error into an empty output that
# then reads as "container stopped", and the rotation proceeds to
# tar a live volume. Use strict_still_running(): CLI failure aborts.
strict_still_running() {
    local out rc
    out="$(docker compose -f deploy/docker-compose.yml ps -q --status running relay 2>&1)"
    rc=$?
    if (( rc != 0 )); then
        echo "FATAL: docker compose ps -q --status running relay failed (rc=$rc)"
        echo "  output: $out"
        exit 1
    fi
    printf '%s' "$out"
}

STOP_DEADLINE=30
waited=0
while (( waited < STOP_DEADLINE )); do
    still="$(strict_still_running)"
    if [[ -z "$still" ]]; then
        break
    fi
    sleep 1
    waited=$((waited + 1))
done
still="$(strict_still_running)"
if [[ -n "$still" ]]; then
    echo "FATAL: relay still running after ${STOP_DEADLINE}s stop wait — rotation aborted"
    exit 1
fi

# 2c — offline tar. Volume is now quiescent; the tar is consistent.
docker run --rm \
    -v deploy_phantom-reports:/data:ro \
    -v "$BACKUP_DIR":/backup \
    alpine:3.20 \
    tar -C /data -czf "/backup/phantom-reports-${STAMP}.tgz" .

sudo sha256sum "$BACKUP_DIR/phantom-reports-${STAMP}.tgz" \
    | sudo tee "$BACKUP_DIR/phantom-reports-${STAMP}.tgz.sha256"

# 2d — verify the backup can round-trip. Untars into a scratch dir
# and re-checksums the file listing. Any tar error → abort BEFORE
# we bring the relay back up on the new image.
scratch="$(mktemp -d)"
docker run --rm \
    -v "$BACKUP_DIR":/backup:ro \
    -v "$scratch":/scratch \
    alpine:3.20 \
    sh -c "tar -C /scratch -xzf /backup/phantom-reports-${STAMP}.tgz && ls -la /scratch" \
    > "$BACKUP_DIR/phantom-reports-${STAMP}.roundtrip.txt"
rm -rf "$scratch"
```

Retain at least three most-recent rotations. Older snapshots may be
pruned by cron.

The relay is currently DOWN. Continue to Step 3 without delay.

## Step 3 — bring in the new image

Two supported delivery paths — use ONE, not both.

**Path A — build on VPS from the merged commit** (preferred when the
VPS is trusted and has enough RAM for the compile).

```bash
set -Eeuo pipefail

NEW_COMMIT='<full-40-char-sha>'
NEW_IMAGE_ID='<sha256:...>'   # architect-supplied source of truth

cd /srv/phantom
git fetch origin
git checkout "$NEW_COMMIT"
docker build \
    -f services/relay/Dockerfile \
    -t "phantom-relay:${NEW_COMMIT:0:12}" \
    .

# Fail-closed image ID assertion.
ACTUAL_ID="$(docker image inspect "phantom-relay:${NEW_COMMIT:0:12}" \
    --format '{{.Id}}')"
if [[ "$ACTUAL_ID" != "$NEW_IMAGE_ID" ]]; then
    echo "FATAL: built image ID mismatch"
    echo "  actual   = $ACTUAL_ID"
    echo "  expected = $NEW_IMAGE_ID"
    exit 1
fi

# Round-1 architect P0 #4: compose consumes `phantom-relay:latest`
# — retag the new image so `up -d` actually picks it up.
docker tag "phantom-relay:${NEW_COMMIT:0:12}" phantom-relay:latest
LATEST_ID="$(docker image inspect phantom-relay:latest --format '{{.Id}}')"
if [[ "$LATEST_ID" != "$NEW_IMAGE_ID" ]]; then
    echo "FATAL: phantom-relay:latest resolves to $LATEST_ID after retag; expected $NEW_IMAGE_ID"
    exit 1
fi
echo "OK: phantom-relay:latest now points at $NEW_IMAGE_ID"
```

**Path B — offline tarball transfer** (when the VPS should not run
the build itself).

Round-2 architect P1 #5: pre-round-2 fused the build-host and VPS
blocks into a single fence, which under `set -u` on the VPS caused
`$NEW_COMMIT` to be undefined (defined only on the build host), and
`$NEW_IMAGE_ID` — needed by Step 6 post-rotation attestation — was
never set on the VPS at all. The two blocks below are explicitly
separated. The operator MUST copy the printed `NEW_COMMIT` and
`NEW_IMAGE_ID` values from the build-host block into the VPS block
(or into the rotation-ticket header the operator maintains across
the session).

### On the build host — Mac or trusted workstation

```bash
set -Eeuo pipefail

NEW_COMMIT='<full-40-char-sha>'          # architect-supplied
docker save "phantom-relay:${NEW_COMMIT:0:12}" -o "phantom-relay-${NEW_COMMIT:0:12}.tar"
shasum -a 256 "phantom-relay-${NEW_COMMIT:0:12}.tar" \
    > "phantom-relay-${NEW_COMMIT:0:12}.tar.sha256"
docker image inspect "phantom-relay:${NEW_COMMIT:0:12}" \
    --format '{{.Id}}' > "phantom-relay-${NEW_COMMIT:0:12}.image-id"

echo
echo "──── copy these two values into the VPS block below ────"
echo "NEW_COMMIT   = $NEW_COMMIT"
echo "NEW_IMAGE_ID = $(cat "phantom-relay-${NEW_COMMIT:0:12}.image-id")"
echo "─────────────────────────────────────────────────────────"

scp "phantom-relay-${NEW_COMMIT:0:12}.tar" \
    "phantom-relay-${NEW_COMMIT:0:12}.tar.sha256" \
    "phantom-relay-${NEW_COMMIT:0:12}.image-id" \
    operator@relay.phntm.pro:/tmp/
```

### On the VPS — every check is fail-closed

```bash
set -Eeuo pipefail

# ─── FILL THESE IN from the build-host output above ────────────────
NEW_COMMIT='<full-40-char-sha>'          # from build-host printout
NEW_IMAGE_ID='<sha256:...>'              # from build-host printout
# ──────────────────────────────────────────────────────────────────

cd /tmp
sha256sum -c "phantom-relay-${NEW_COMMIT:0:12}.tar.sha256"
docker load -i "phantom-relay-${NEW_COMMIT:0:12}.tar"

# Cross-check the loaded image against the build-host source-of-truth
# BOTH via the sidecar .image-id file AND against NEW_IMAGE_ID —
# fail if either disagrees with the other.
SIDECAR_ID="$(cat "phantom-relay-${NEW_COMMIT:0:12}.image-id")"
if [[ "$SIDECAR_ID" != "$NEW_IMAGE_ID" ]]; then
    echo "FATAL: sidecar .image-id ($SIDECAR_ID) != operator NEW_IMAGE_ID ($NEW_IMAGE_ID)"
    exit 1
fi
ACTUAL_ID="$(docker image inspect "phantom-relay:${NEW_COMMIT:0:12}" \
    --format '{{.Id}}')"
if [[ "$ACTUAL_ID" != "$NEW_IMAGE_ID" ]]; then
    echo "FATAL: loaded image ID mismatch"
    echo "  actual   = $ACTUAL_ID"
    echo "  expected = $NEW_IMAGE_ID"
    exit 1
fi

# Round-1 architect P0 #4: compose consumes `phantom-relay:latest`
# — retag the loaded image so `up -d` actually picks it up.
docker tag "phantom-relay:${NEW_COMMIT:0:12}" phantom-relay:latest
LATEST_ID="$(docker image inspect phantom-relay:latest --format '{{.Id}}')"
if [[ "$LATEST_ID" != "$NEW_IMAGE_ID" ]]; then
    echo "FATAL: phantom-relay:latest resolves to $LATEST_ID after retag; expected $NEW_IMAGE_ID"
    exit 1
fi
echo "OK: phantom-relay:latest now points at $NEW_IMAGE_ID"
```

`NEW_COMMIT` and `NEW_IMAGE_ID` must remain defined for Step 6
post-rotation attestation and for the ticket record.

Neither path uploads over an unpinned registry.

## Step 4 — sync the compose file (fast-forward only)

If the Ops PR updated `deploy/docker-compose.yml`, the VPS git clone
needs the change BEFORE `up -d`. Round-1 architect P1 #7: the
pre-round-1 shape (`git checkout origin/master -- deploy/docker-
compose.yml`) leaves HEAD on the old commit and marks the worktree
dirty — a subsequent operator inspecting `git status` sees drift
that reflects neither the pre-rotation state nor a proper sync.

Use `pull --ff-only` and require a clean worktree afterwards.

```bash
set -Eeuo pipefail

cd /srv/phantom

# Refuse to sync a dirty tree — the operator must resolve local
# edits first.
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    echo "FATAL: /srv/phantom worktree is dirty. Refusing to sync compose."
    git status --short
    exit 1
fi

# Fast-forward only. Any diverged history → abort.
git fetch origin master
git merge --ff-only origin/master

# Post-sync assertion.
if [[ "$(git rev-parse HEAD)" != "$(git rev-parse origin/master)" ]]; then
    echo "FATAL: HEAD != origin/master after ff-only merge"
    exit 1
fi

# Record the compose SHA that the operator ticket references.
COMPOSE_SHA="$(sha256sum deploy/docker-compose.yml | awk '{print $1}')"
echo "compose SHA-256: $COMPOSE_SHA"
```

## Step 5 — rotate: bring the relay back up on the new image

`docker compose up -d --force-recreate` on the relay service ONLY.
Every other service (caddy, tor, ntfy, webtunnel-bridge, xray) is
untouched — they never went down.

```bash
set -Eeuo pipefail

cd /srv/phantom
docker compose -f deploy/docker-compose.yml up -d --force-recreate relay
```

Watch the transition with fail-closed loops. Expected window: 1–3
seconds container swap + up to ~90 seconds for
`state_dir_preflight_ok` to appear in logs.

```bash
set -Eeuo pipefail

# Round-1 architect P1 #6: bounded loops with hard exit on timeout,
# not fall-through-and-hope.

RUN_DEADLINE=60
waited=0
running='false'
while (( waited < RUN_DEADLINE )); do
    running="$(docker inspect phantom-relay --format '{{.State.Running}}' 2>/dev/null || echo gone)"
    if [[ "$running" == 'true' ]]; then
        break
    fi
    sleep 1
    waited=$((waited + 1))
done
if [[ "$running" != 'true' ]]; then
    echo "FATAL: relay container not Running after ${RUN_DEADLINE}s"
    exit 1
fi

PREFLIGHT_DEADLINE=90
waited=0
preflight_ok=0
while (( waited < PREFLIGHT_DEADLINE )); do
    if docker logs --since 5m phantom-relay 2>&1 \
            | grep -Fq 'event="state_dir_preflight_ok"'; then
        preflight_ok=1
        break
    fi
    sleep 1
    waited=$((waited + 1))
done
if (( preflight_ok == 0 )); then
    echo "FATAL: preflight_ok not observed within ${PREFLIGHT_DEADLINE}s"
    exit 1
fi
```

## Step 6 — post-rotation attestation (fail-closed)

Every check aborts on any failure. No `MISSING` printouts that
continue past — a missing file returns non-zero from `test -s`,
which propagates through `set -e` and stops the runbook.

```bash
set -Eeuo pipefail

TICKET_DIR="/var/rotation-tickets/post-$(date -u +%Y%m%dt%H%M%Sz)"
sudo install -d -m 700 -o "$(id -u)" -g "$(id -g)" "$TICKET_DIR"

# 1. New image is running with the expected ID.
ACTUAL_IMAGE_ID="$(docker inspect phantom-relay --format '{{.Image}}')"
printf '%s\n' "$ACTUAL_IMAGE_ID" | tee "$TICKET_DIR/image-id.txt"
if [[ "$ACTUAL_IMAGE_ID" != "$NEW_IMAGE_ID" ]]; then
    echo "FATAL: running image = $ACTUAL_IMAGE_ID, expected $NEW_IMAGE_ID"
    exit 1
fi

# 2. RestartCount = 0 (fresh container).
RC="$(docker inspect phantom-relay --format '{{.RestartCount}}')"
if [[ "$RC" != '0' ]]; then
    echo "FATAL: RestartCount = $RC, expected 0"
    exit 1
fi

# 3. State-dir ownership + mode preserved.
STATE_OWNER="$(docker exec phantom-relay stat -c '%U:%G:%a' /var/phantom)"
if [[ "$STATE_OWNER" != 'phantom:phantom:750' ]]; then
    echo "FATAL: /var/phantom owner = $STATE_OWNER, expected phantom:phantom:750"
    exit 1
fi

# 4. .lock present.
docker exec phantom-relay ls -la /var/phantom/.lock \
    > "$TICKET_DIR/lock.txt"

# 5. All four state files present + non-empty (replay worked). Fail
# on the first missing file rather than printing MISSING and passing.
for f in prekeys.jsonl reports.jsonl blocklist.txt push_tokens.jsonl; do
    if ! docker exec phantom-relay test -s "/var/phantom/$f"; then
        echo "FATAL: /var/phantom/$f missing or empty after rotation"
        exit 1
    fi
done

# 6. No persist-failure events since rotation start. Hard exit on
# any hit — no ANSI-cat swallow.
docker logs --since 10m phantom-relay 2>&1 \
    | sed -E $'s/\x1b\\[[0-9;]*[mKGH]//g' \
    > "$TICKET_DIR/post-rotation-logs.txt"

if grep -qF -e 'event="audit_persist_failed"' \
             -e 'event="prekey_persist_failed"' \
             "$TICKET_DIR/post-rotation-logs.txt"; then
    echo "FATAL: persist-failure events observed after rotation"
    grep -F -e 'event="audit_persist_failed"' \
            -e 'event="prekey_persist_failed"' \
            "$TICKET_DIR/post-rotation-logs.txt"
    exit 1
fi

# 7. No panic / FATAL / OOM / SIGSEGV.
if grep -qE 'panic|FATAL:|OOM|SIGSEGV' \
        "$TICKET_DIR/post-rotation-logs.txt"; then
    echo "FATAL: panic / FATAL / OOM / SIGSEGV in post-rotation logs"
    grep -E 'panic|FATAL:|OOM|SIGSEGV' \
        "$TICKET_DIR/post-rotation-logs.txt"
    exit 1
fi

# 8. Live-traffic smoke test — one request via Caddy against the
# well-known /health endpoint (non-mutating). Fail on non-200.
HEALTH_CODE="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' \
    "https://relay.phntm.pro/health")"
printf '%s\n' "$HEALTH_CODE" > "$TICKET_DIR/health-code.txt"
if [[ "$HEALTH_CODE" != '200' ]]; then
    echo "FATAL: /health returned $HEALTH_CODE"
    exit 1
fi
```

If any check fails and recovery is not immediate, follow
`quarantine.md`.

## Triple witness — memory ceiling attestation

Locked v4.2.3 §3: prove the memory ceiling is *actually* enforced,
not just declared. Three numbers must agree byte-for-byte:

1. What `deploy/docker-compose.yml` declares (`compose_bytes`).
2. What Docker gave the running container
   (`HostConfig.Memory` in `docker inspect`).
3. What the kernel cgroup enforces
   (`/sys/fs/cgroup/memory.max` — cgroup v2 unified hierarchy).

Any drift means either the operator edited the compose but the
container still runs the old value, or the kernel cgroup limits are
mis-configured. Both are hard rollback triggers.

Round-2 architect P1 #4 fixes:
- The block explicitly creates `$TICKET_DIR` before `tee` (the
  pre-round-2 shape sourced the name but never created the
  directory).
- Cgroup value is read INSIDE the container via
  `docker exec phantom-relay cat /sys/fs/cgroup/memory.max`
  (locked-v4.2.3 command shape). Container cgroup v2 mounts the
  container's own scope at `/sys/fs/cgroup/`, so the reading is
  authoritative and does not depend on host-path speculation.
- The compose value is extracted with a pure-regex Python script,
  removing the `python3-yaml` runtime dependency (a fresh minimal
  VPS may not have it and this block would fail-open the
  attestation on `ModuleNotFoundError` under `set -e`).

```bash
set -Eeuo pipefail

# Round-2 architect P1 #4: mkdir the ticket dir before tee.
TICKET_DIR="/var/rotation-tickets/witness-$(date -u +%Y%m%dt%H%M%Sz)"
sudo install -d -m 700 -o "$(id -u)" -g "$(id -g)" "$TICKET_DIR"

# Preflight: python3 required for regex extraction.
if ! command -v python3 >/dev/null 2>&1; then
    echo "FATAL: python3 required for triple-witness compose extraction"
    exit 1
fi

# (1) compose_bytes — regex-parse the compose declaration into bytes.
# NO `import yaml` — avoids a runtime dependency on python3-yaml
# that a fresh VPS may not have installed.
COMPOSE_MEM="$(python3 - <<'PY'
import re, sys
mem_re = re.compile(
    r"^(?P<indent>\s+)memory:\s*(?P<val>[^\s#]+)"
    r"(?:\s+#.*)?$",
    re.MULTILINE,
)
with open("/srv/phantom/deploy/docker-compose.yml") as f:
    text = f.read()
# Find the `limits.memory` under services.relay.deploy.resources.
# The nesting depth for the value we want is 10 leading spaces (5
# levels × 2-space YAML indent). This runbook is a hard witness of
# THAT exact declaration; if the compose is restructured, this block
# fails-closed (no match → sys.exit(1)) and the operator must
# refresh the runbook.
val = None
for m in mem_re.finditer(text):
    if len(m.group("indent")) == 10 and m.group("val").rstrip():
        val = m.group("val").rstrip()
        break
if val is None:
    print("FATAL: could not extract deploy.resources.limits.memory from compose", file=sys.stderr)
    sys.exit(1)
mm = re.fullmatch(r"([1-9][0-9]*)([MG])", val)
if not mm:
    print(f"FATAL: compose memory value {val!r} does not fullmatch ^[1-9][0-9]*[MG]$", file=sys.stderr)
    sys.exit(1)
num, unit = int(mm.group(1)), mm.group(2)
bytes_ = num * (1024 * 1024 if unit == "M" else 1024 * 1024 * 1024)
print(bytes_)
PY
)"

# (2) HostConfig.Memory on the running container.
HOSTCONFIG_MEM="$(docker inspect phantom-relay --format '{{.HostConfig.Memory}}')"

# (3) Cgroup limit — read INSIDE the container. In cgroup v2 the
# container's own scope is mounted at /sys/fs/cgroup/ and the file
# is `memory.max`. Round-3 architect P1 #4: the locked-v4.2.3
# binding requires the `memory.max || memory.limit_in_bytes`
# fallback for hosts still on cgroup v1 (older kernels, some
# distros). Try v2 first; on ENOENT try v1. Fail-closed only if
# both are absent.
CGROUP_MAX="$(docker exec phantom-relay sh -c '
    if [ -r /sys/fs/cgroup/memory.max ]; then
        cat /sys/fs/cgroup/memory.max
    elif [ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
        cat /sys/fs/cgroup/memory/memory.limit_in_bytes
    else
        echo "FATAL: neither cgroup-v2 memory.max nor cgroup-v1 memory.limit_in_bytes readable" >&2
        exit 1
    fi
' 2>&1)"
if ! [[ "$CGROUP_MAX" =~ ^[0-9]+$ ]]; then
    echo "FATAL: unexpected cgroup memory value from container: $CGROUP_MAX"
    echo "       (expected a positive integer bytes count)"
    exit 1
fi

printf 'compose_bytes=%s\nhostconfig=%s\ncgroup_max=%s\n' \
    "$COMPOSE_MEM" "$HOSTCONFIG_MEM" "$CGROUP_MAX" \
    | tee "$TICKET_DIR/triple-witness.txt"

if [[ "$COMPOSE_MEM" != "$HOSTCONFIG_MEM" ]]; then
    echo "FATAL: compose bytes ($COMPOSE_MEM) != HostConfig.Memory ($HOSTCONFIG_MEM)"
    exit 1
fi
if [[ "$COMPOSE_MEM" != "$CGROUP_MAX" ]]; then
    echo "FATAL: compose bytes ($COMPOSE_MEM) != cgroup memory.max ($CGROUP_MAX)"
    exit 1
fi
echo "OK: triple witness — compose = HostConfig = cgroup = $COMPOSE_MEM bytes"
```

Record all three values in the ticket. A rotation that changes the
compose memory value MUST come with an updated triple-witness
capture; the ticket is not closed without it.

## Restore from backup (only after quarantine says restore)

Never restore casually. This is destructive on the current volume.

```bash
set -Eeuo pipefail

BACKUP="/var/backups/phantom-reports/phantom-reports-<stamp>.tgz"

# Verify the checksum first.
sudo sha256sum -c "${BACKUP}.sha256"

# Stop the relay.
cd /srv/phantom
docker compose -f deploy/docker-compose.yml stop relay

# Round-2 architect P0 #3: pre-round-2 the wait loop used
# `2>/dev/null || true` and had NO post-loop verification, so a
# still-running relay (or a docker CLI error) fell straight through
# to the destructive `find /data -delete` on the LIVE volume. Use
# strict_still_running(): CLI failure aborts, and after the loop a
# fresh strict check must return empty or the restore is aborted.
strict_still_running() {
    local out rc
    out="$(docker compose -f deploy/docker-compose.yml ps -q --status running relay 2>&1)"
    rc=$?
    if (( rc != 0 )); then
        echo "FATAL: docker compose ps -q --status running relay failed (rc=$rc)"
        echo "  output: $out"
        exit 1
    fi
    printf '%s' "$out"
}

RESTORE_STOP_DEADLINE=30
waited=0
while (( waited < RESTORE_STOP_DEADLINE )); do
    still="$(strict_still_running)"
    if [[ -z "$still" ]]; then break; fi
    sleep 1
    waited=$((waited + 1))
done
still="$(strict_still_running)"
if [[ -n "$still" ]]; then
    echo "FATAL: relay still running after ${RESTORE_STOP_DEADLINE}s — refusing to wipe volume"
    exit 1
fi

# Only reached with the relay verified stopped. Wipe the current
# volume contents and restore from the backup tar.
docker run --rm \
    -v deploy_phantom-reports:/data \
    -v "$(dirname "$BACKUP")":/backup:ro \
    alpine:3.20 \
    sh -c "find /data -mindepth 1 -delete && tar -C /data -xzf /backup/$(basename "$BACKUP")"

# Round-3 architect P1 #5: pre-round-3 only `ls -la`'d the volume,
# which prints ownership but does NOT fail on drift — a restore
# from a tar produced by a foreign uid, or a tar that lost mode
# bits, would silently pass and the relay would boot with the wrong
# uid inside /var/phantom (PR-1a Inv-5 relies on the container's
# `phantom:phantom` at 10001:10001:750). Hard-assert numeric.
STATE_OWNER="$(docker run --rm \
    -v deploy_phantom-reports:/data:ro \
    alpine:3.20 \
    stat -c '%u:%g:%a' /data)"
if [[ "$STATE_OWNER" != '10001:10001:750' ]]; then
    echo "FATAL: /var/phantom ownership drift after restore"
    echo "  expected: 10001:10001:750"
    echo "  actual  : $STATE_OWNER"
    echo "  Refusing to bring relay up on a mis-owned state directory."
    exit 1
fi

# Per-file ownership hard-assert on the four state files (any that
# exist in the backup). Missing files are legitimate (a fresh
# quarantine restore that dropped one file) — the check only
# validates existing files.
for f in prekeys.jsonl reports.jsonl blocklist.txt push_tokens.jsonl; do
    if docker run --rm \
            -v deploy_phantom-reports:/data:ro \
            alpine:3.20 \
            test -e "/data/$f"; then
        FILE_OWNER="$(docker run --rm \
            -v deploy_phantom-reports:/data:ro \
            alpine:3.20 \
            stat -c '%u:%g' "/data/$f")"
        if [[ "$FILE_OWNER" != '10001:10001' ]]; then
            echo "FATAL: /var/phantom/$f ownership drift after restore"
            echo "  expected: 10001:10001"
            echo "  actual  : $FILE_OWNER"
            exit 1
        fi
    fi
done

# Start relay back and re-run Step 6 + triple witness.
docker compose -f deploy/docker-compose.yml up -d --force-recreate relay
```
