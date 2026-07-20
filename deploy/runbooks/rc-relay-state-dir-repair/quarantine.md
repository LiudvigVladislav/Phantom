# Relay quarantine runbook

Safe procedure for the two operations paired under quarantine when a
PR-1b `event="prekey_persist_failed"` / `event="audit_persist_failed"`
/ `event="state_load_torn_lines"` / `FATAL: preflight:` / `panic` /
`OOM` / `SIGSEGV` / unexpected restart is observed on the production
VPS:

1. **Atomic file-level quarantine** — move the specific damaged state
   file (e.g. `prekeys.jsonl`) out of the relay's read-path into an
   in-volume `.quarantine/` sidecar so a subsequent boot survives.
2. **Incident triage bundle** — capture container inspect, logs,
   events, volume snapshot for forensics.

Both run inside a single locked-v4.2.3 §2 INTENT+fsync sidecar. The
atomic rename of the damaged file is the load-bearing step; the
triage capture is diagnostic.

## Service vs container name (do not confuse)

`deploy/docker-compose.yml` declares the relay under:

- **Service name**: `relay` — the argument accepted by
  `docker compose <cmd>`.
- **Container name**: `phantom-relay` — the argument accepted by
  `docker inspect`, `docker exec`, `docker logs`, `docker events`.

Round-2 architect P0 #1: pre-round-2 the runbook passed
`$RELAY_CONTAINER = "phantom-relay"` to `docker compose stop`, which
`compose v2` refuses (only service names are valid). The variables
below are named to make the mistake impossible.

## When to use this runbook

Any of the following, observed on the VPS:

- `event="prekey_persist_failed"` in relay logs at all.
- `event="audit_persist_failed"` in relay logs, repeated.
- `event="state_load_torn_lines" skipped=N` with N > 0 in a fresh
  boot cycle.
- `FATAL: preflight:` in relay logs — preflight refused to boot.
- `panic` at container start, container then keeps failing.
- `OOM`, `out of memory`, or `SIGSEGV` in the relay's stderr.
- `RestartCount` on the relay container has grown since the last
  known-good snapshot AND the operator did not run
  `docker compose up -d --force-recreate`.
- Alerting shows sustained HTTP 5xx from the relay through Caddy.

## What quarantine does NOT do

- Does NOT delete `/var/phantom` volume contents.
- Does NOT `docker system prune`.
- Does NOT modify `deploy/docker-compose.yml`.
- Does NOT rotate the image.
- Does NOT auto-restart the relay. Restart is a distinct operator
  decision under §7 recovery paths after human triage.

## Preflight guards

```bash
set -Eeuo pipefail
IFS=$'\n\t'

for bin in docker sha256sum sync flock date install mktemp; do
    command -v "$bin" >/dev/null 2>&1 || { echo "FATAL: $bin missing"; exit 9; }
done
docker compose version >/dev/null 2>&1 \
    || { echo "FATAL: docker compose v2 required"; exit 9; }

REPO_DIR="${REPO_DIR:-/srv/phantom}"
COMPOSE_FILE="${COMPOSE_FILE:-$REPO_DIR/deploy/docker-compose.yml}"

# Round-2 architect P0 #1: service vs container name are distinct.
RELAY_SERVICE="relay"                       # for `docker compose <cmd>`
RELAY_CONTAINER="phantom-relay"             # for `docker inspect / exec / logs`
RELAY_VOLUME="deploy_phantom-reports"       # phantom-reports named volume

# The damaged file inside /var/phantom that the operator has
# identified from log evidence. If not set, this runbook only runs
# the triage bundle (no file-level quarantine).
#
# Round-3 architect P0 #1: the runbook now supports the two
# quarantine modes locked-v4.2.3 §2 defines, and dispatches on the
# path shape:
#
#   * STATE mode  — one of the four PR-1b state files:
#         prekeys.jsonl | reports.jsonl | blocklist.txt | push_tokens.jsonl
#         → destination /var/phantom/.quarantine/<file>.<stamp>
#         (side procedure kept from round-2)
#   * QUEUE mode  — a queue-envelope path shape
#         `queue/**` (any depth, any basename)
#         → destination /var/phantom/queue-quarantine/manual-<stamp>/<basename>
#         (the mandatory locked-v4.2.3 contract; the four-state
#         side procedure does NOT satisfy it)
#
# All other values refused. The mode is auto-selected below.
QUARANTINE_FILE="${QUARANTINE_FILE:-}"
QUARANTINE_MODE=""

# Round-3.2 architect P0 #1: pre-round-3.2 the queue-mode guard
# only refused `../` and leading `/`. A payload like
# `queue/x'; echo pwn; #'` slipped past because it contained no
# `..` or leading `/`, and the value was then interpolated by bash
# INTO an `sh -c "…'${QF}'…"` — the embedded single quote closed
# the surrounding quoting and injected shell commands that ran in
# the helper container with RW access to the phantom-reports
# volume. Two defences now:
#
#   1. **Strict lexical whitelist.** Every path component must
#      match `^[A-Za-z0-9._-]+$`. That excludes single/double
#      quotes, `;`, `$`, backticks, backslash, newlines, spaces,
#      `..`, `%`, `!`. If a legitimate future queue-envelope
#      basename ever needs a character outside this set the
#      runbook (this file) must be updated deliberately.
#   2. **Never interpolate into `sh -c`.** The Step 4 code passes
#      `QF`, target rel-path, and audit-log path via `env -e`
#      into helper containers whose `sh -c` body is single-quoted
#      and reads only `"$QF"`-style variables. Shell interpolation
#      of the operator-supplied value is thus impossible.
#
# The whitelist alone is sufficient at the input boundary; the
# `env -e` pattern is defence-in-depth against a future edit that
# might loosen the whitelist without re-auditing every `sh -c`.

_qshape_ok() {
    # Return 0 iff $1 is a path made of ONLY components matching
    # ^[A-Za-z0-9._-]+$, no `..` component, no empty component
    # (which catches `//`), and no trailing `/` (empty basename).
    local p="$1" comp rest
    [[ -n "$p" ]] || return 1
    [[ "$p" != */ ]] || return 1
    while [[ -n "$p" ]]; do
        comp="${p%%/*}"
        if [[ "$p" == *"/"* ]]; then
            rest="${p#*/}"
        else
            rest=""
        fi
        [[ -n "$comp" ]] || return 1
        [[ "$comp" != ".." ]] || return 1
        [[ "$comp" =~ ^[A-Za-z0-9._-]+$ ]] || return 1
        p="$rest"
    done
    return 0
}

case "$QUARANTINE_FILE" in
    '')
        QUARANTINE_MODE=""
        ;;
    prekeys.jsonl|reports.jsonl|blocklist.txt|push_tokens.jsonl)
        QUARANTINE_MODE="state"
        ;;
    queue/*)
        # Absolute path fast-reject (belt and braces on top of the
        # component-level check that would also refuse it).
        case "$QUARANTINE_FILE" in
            /*)
                echo "FATAL: QUARANTINE_FILE must be relative"
                exit 9
                ;;
        esac
        if ! _qshape_ok "$QUARANTINE_FILE"; then
            echo "FATAL: QUARANTINE_FILE contains a disallowed character or shape."
            echo "       Allowed per component: [A-Za-z0-9._-]+"
            echo "       Refused: empty basename, '..', quotes, ';', '\$', backtick,"
            echo "                backslash, newline, whitespace, '%', '!'."
            exit 9
        fi
        QUARANTINE_MODE="queue"
        ;;
    *)
        echo "FATAL: QUARANTINE_FILE must be empty, one of"
        echo "       prekeys.jsonl | reports.jsonl | blocklist.txt | push_tokens.jsonl,"
        echo "       or a queue-envelope path shape queue/<...>"
        exit 9
        ;;
esac

STAMP="$(date -u +%Y%m%dt%H%M%Sz)"
QROOT="/var/quarantine"
QDIR="$QROOT/phantom-relay-$STAMP"
QDIR_STAGED="$QROOT/.staged.phantom-relay-$STAMP"

install -d -m 700 -o "$(id -u)" -g "$(id -g)" "$QROOT"

# Serialise: never run two quarantines concurrently.
LOCK_FILE="$QROOT/.quarantine.lock"
exec {LOCK_FD}>>"$LOCK_FILE"
if ! flock -n "$LOCK_FD"; then
    echo "FATAL: another quarantine holds $LOCK_FILE"
    exit 9
fi
```

## Step 1 — INTENT sidecar (v4.2.3 §2 step 1)

Create the staged directory + INTENT marker BEFORE touching the
running relay. If a crash happens after this point the operator
sees an INTENT-without-DONE and knows a quarantine was in-flight.

```bash
install -d -m 700 -o "$(id -u)" -g "$(id -g)" "$QDIR_STAGED"

cat > "$QDIR_STAGED/INTENT.quarantine" <<EOF
started_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
staged_dir=$QDIR_STAGED
final_dir=$QDIR
relay_service=$RELAY_SERVICE
relay_container=$RELAY_CONTAINER
relay_volume=$RELAY_VOLUME
compose_file=$COMPOSE_FILE
quarantine_file=${QUARANTINE_FILE:-<none>}
operator_uid=$(id -u)
operator_reason=${QUARANTINE_REASON:-<not provided>}
EOF
sync
```

`QUARANTINE_REASON` is a short operator-supplied string
(e.g. `prekey_persist_failed observed 2026-07-20T12:00Z`). If unset
it lands as `<not provided>` and the operator MUST fill it in the
ticket.

## Step 2 — pre-stop triage snapshot (best-effort, inside sidecar)

Diagnostic commands run against the still-running relay. Any single
command may fail (the container may already be down); we tolerate
that per-line and record the exit codes.

```bash
run_or_record() {
    local out="$1" ; shift
    local rc=0
    "$@" > "$QDIR_STAGED/$out" 2>&1 || rc=$?
    printf '%s\n' "$out exit=$rc" >> "$QDIR_STAGED/triage-rc.txt"
}

run_or_record inspect.json          docker inspect "$RELAY_CONTAINER"
run_or_record ps.txt                docker exec "$RELAY_CONTAINER" ps -eo pid,ppid,user,rss,vsz,cmd
run_or_record state-dir.txt         docker exec "$RELAY_CONTAINER" ls -la /var/phantom
run_or_record state-file-stats.txt  docker exec "$RELAY_CONTAINER" sh -c \
    'for f in prekeys.jsonl reports.jsonl blocklist.txt push_tokens.jsonl .lock; do
         if [ -e "/var/phantom/$f" ]; then
             stat -c "%n size=%s mode=%a owner=%U:%G mtime=%Y" "/var/phantom/$f"
         else
             echo "MISSING $f"
         fi
     done'
run_or_record logs.txt              docker logs --since 24h "$RELAY_CONTAINER"

# Anomaly extraction runs against our captured logs.txt; grep exit=1
# (no match) is expected on healthy state and does not fail-closed.
grep -F -e 'panic' \
        -e 'FATAL:' \
        -e 'permission denied' \
        -e 'event="audit_persist_failed"' \
        -e 'event="prekey_persist_failed"' \
        -e 'event="state_load_torn_lines"' \
        -e 'OOM' \
        -e 'out of memory' \
        -e 'SIGSEGV' \
        "$QDIR_STAGED/logs.txt" \
        > "$QDIR_STAGED/anomalies.txt" || true

# Docker daemon-level container events.
docker events --since 24h --until 0s \
    --filter container="$RELAY_CONTAINER" \
    > "$QDIR_STAGED/docker-events.txt" 2>&1 &
EVENTS_PID=$!
sleep 1
kill "$EVENTS_PID" 2>/dev/null || true
```

## Step 3 — stop the relay AND enforce a strict running-guard

Round-1 architect P0 #2 + Round-2 architect P0 #1/P0 #3: after
`docker compose stop`, the running relay MUST block further progress
AND `docker compose ps` failure MUST NOT be masked as "stopped".
Pre-round-2 used `2>/dev/null || true`, which swallowed CLI errors
as an empty output and silently satisfied the check. Now: bounded
loop against `docker compose ps -q --status running`, then a final
strict check whose failure is fatal (no `|| true`, no `2>/dev/null`
mask).

```bash
cd "$REPO_DIR"
docker compose -f "$COMPOSE_FILE" stop "$RELAY_SERVICE" \
    2>&1 | tee -a "$QDIR_STAGED/stop.txt"

# strict_still_running: prints the container id of `relay` if it is
# still running, empty otherwise. Aborts on `docker compose ps`
# failure — refusing to mask a CLI error as a successful "stopped".
strict_still_running() {
    local out rc
    out="$(docker compose -f "$COMPOSE_FILE" ps -q --status running "$RELAY_SERVICE" 2>&1)"
    rc=$?
    if (( rc != 0 )); then
        echo "FATAL: docker compose ps -q --status running $RELAY_SERVICE failed (rc=$rc)" >&2
        echo "  output: $out" >&2
        exit 3
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

# Final strict check — MUST NOT rely on the loop-exit condition.
still="$(strict_still_running)"
if [[ -n "$still" ]]; then
    echo "FATAL: relay still running after ${STOP_DEADLINE}s — quarantine aborted mid-flow"
    echo "  INTENT preserved: $QDIR_STAGED/INTENT.quarantine"
    exit 3
fi

# Record post-stop state on the (now stopped) container.
docker inspect "$RELAY_CONTAINER" \
    --format 'Running={{.State.Running}} ExitCode={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}}' \
    | tee "$QDIR_STAGED/post-stop-state.txt"
```

`OOMKilled=true` in the post-stop state means the kernel already
killed the container (very likely the `deploy.resources.limits.
memory` ceiling fired) — that is diagnostic info for the ticket.

## Step 4 — file-level quarantine (v4.2.3 §2 steps 2–4, on the damaged file)

Round-2 architect P0 #1: the sidecar rename in `/var/quarantine/`
does NOT satisfy the quarantine contract. The contract is: the
damaged file itself must be moved atomically out of the relay's
read-path via `INTENT → fsync → rename → parent fsync → DONE`. The
sidecar in `/var/quarantine/` is the AUDIT record of that operation;
the load-bearing rename is on the phantom-reports volume.

The rename happens on the SAME filesystem as the source file so it
is atomic. Destination depends on the mode selected in Preflight:

| Mode  | Destination                                                       |
|-------|-------------------------------------------------------------------|
| state | `/var/phantom/.quarantine/<file>.<stamp>`                         |
| queue | `/var/phantom/queue-quarantine/manual-<stamp>/<basename>`         |

Same volume in both cases, so the relay's read-path (which reads
exactly `/var/phantom/{prekeys,reports,blocklist,push_tokens}` and
scans `queue/` but not `queue-quarantine/`) is undisturbed.

Round-3.2 architect fixes:

- **P0 #2 canonical audit log.** Locked v4.2.3 requires a single
  in-volume audit log at `/var/phantom/quarantine-audit.log` so
  the tool/operator reading the canonical journal sees INTENT +
  DONE from BOTH state-mode and queue-mode. Pre-round-3.2 wrote
  to a per-mode path — canonical readers missed queue events.
  Both modes now append to the same canonical log; the mode
  field inside each block distinguishes them.

- **P0 #1 no shell interpolation.** `QF`, `Q_TARGET_REL`, and
  `Q_AUDIT_LOG` are handed to helper containers via `env -e`
  bindings; the container's `sh -c` body is single-quoted (no
  host-shell expansion) and reads only `"$QF"`-style vars. Even
  if the strict lexical whitelist in Preflight guards were
  relaxed later, the helper `sh -c` cannot be reached by shell
  metacharacters in the input.

- Round-3 P2 #6: `date -u +%Y-%m-%dT%H:%M:%SZ` — not `%%Y…`.

Only runs when the operator set `QUARANTINE_FILE`. Without it,
skip straight to Step 5 (volume snapshot only).

```bash
if [[ -n "$QUARANTINE_FILE" ]]; then
    QF="$QUARANTINE_FILE"

    case "$QUARANTINE_MODE" in
        state)
            Q_TARGET_REL=".quarantine/${QF}.${STAMP}"
            Q_TARGET_DIR=".quarantine"
            ;;
        queue)
            Q_BASENAME="${QF##*/}"
            Q_TARGET_REL="queue-quarantine/manual-${STAMP}/${Q_BASENAME}"
            Q_TARGET_DIR="queue-quarantine/manual-${STAMP}"
            ;;
        *)
            echo "FATAL: unreachable — QUARANTINE_MODE not resolved"
            exit 9
            ;;
    esac

    # Round-3.2 P0 #2: single canonical in-volume audit log for
    # both modes, per locked v4.2.3.
    Q_AUDIT_LOG_ABS="/data/quarantine-audit.log"

    # ─ INTENT (append to canonical audit log — never overwrite) ─
    # env -e binds the operator-supplied values into the container's
    # env; the sh -c body is single-quoted and only expands "$QF"-
    # style vars — bash of the host performs NO interpolation into
    # the sh -c body. This closes the round-3.1 shell-injection
    # window (round-3.2 P0 #1).
    docker run --rm \
        -e "QF=${QF}" \
        -e "Q_TARGET_REL=${Q_TARGET_REL}" \
        -e "Q_TARGET_DIR=${Q_TARGET_DIR}" \
        -e "Q_AUDIT_LOG_ABS=${Q_AUDIT_LOG_ABS}" \
        -e "STAMP=${STAMP}" \
        -e "QMODE=${QUARANTINE_MODE}" \
        -e "SIDECAR=${QDIR_STAGED}" \
        -v "$RELAY_VOLUME:/data" \
        alpine:3.20 \
        sh -c '
            set -eu
            mkdir -p "/data/$Q_TARGET_DIR"
            chmod 700 "/data/$Q_TARGET_DIR"
            {
                printf "\n--- INTENT ---\n"
                printf "stamp=%s\n" "$STAMP"
                printf "mode=%s\n" "$QMODE"
                printf "file=/var/phantom/%s\n" "$QF"
                printf "staged_target=/var/phantom/%s\n" "$Q_TARGET_REL"
                printf "sidecar=%s\n" "$SIDECAR"
                printf "at_utc=%s\n" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            } >> "$Q_AUDIT_LOG_ABS"
            sync
        ' 2>&1 | tee -a "$QDIR_STAGED/file-quarantine.log"

    # ─ Verify source exists BEFORE renaming (fail-closed) ─
    if ! docker run --rm \
            -e "QF=${QF}" \
            -v "$RELAY_VOLUME:/data:ro" \
            alpine:3.20 \
            sh -c 'test -f "/data/$QF"'; then
        echo "FATAL: /var/phantom/${QF} does not exist — nothing to quarantine"
        exit 3
    fi

    # ─ Snapshot the source file's sha256 for the audit record ─
    # Round-3.3 architect P1 #2: pre-round-3.3 shape was
    # `sha256sum … | awk '{print $1}'`. Without `pipefail`, a
    # sha256sum failure (missing file, permission denied) lets awk
    # exit 0 with empty output; SRC_SHA becomes "" and later
    # TGT_SHA == SRC_SHA reads as PASS on two empty strings. Fix:
    # sequence `sha256sum && printf` so any sha256sum failure
    # bubbles a non-zero exit out of the container, which under
    # `set -e` on the host aborts the rotation. And validate the
    # captured value on the host against ^[0-9a-f]{64}$.
    SRC_SHA="$(docker run --rm \
        -e "QF=${QF}" \
        -v "$RELAY_VOLUME:/data:ro" \
        alpine:3.20 \
        sh -c 'sum="$(sha256sum "/data/$QF")" && printf %s "${sum%% *}"')"
    if [[ ! "$SRC_SHA" =~ ^[0-9a-f]{64}$ ]]; then
        echo "FATAL: source sha256 did not match ^[0-9a-f]{64}$ (got: ${SRC_SHA@Q})"
        exit 3
    fi
    printf 'source_sha256=%s\n' "$SRC_SHA" \
        > "$QDIR_STAGED/file-quarantine.source.sha256"

    # ─ Atomic rename inside the volume ─
    docker run --rm \
        -e "QF=${QF}" \
        -e "Q_TARGET_REL=${Q_TARGET_REL}" \
        -v "$RELAY_VOLUME:/data" \
        alpine:3.20 \
        sh -c '
            set -eu
            mv -- "/data/$QF" "/data/$Q_TARGET_REL"
            sync
        ' 2>&1 | tee -a "$QDIR_STAGED/file-quarantine.log"

    # ─ Post-rename fail-closed verify ─
    #   1. source must be gone
    #   2. target must exist
    #   3. target sha256 must match the source snapshot
    if docker run --rm \
            -e "QF=${QF}" \
            -v "$RELAY_VOLUME:/data:ro" \
            alpine:3.20 \
            sh -c 'test -e "/data/$QF"'; then
        echo "FATAL: /var/phantom/${QF} still exists after rename — quarantine broken"
        exit 3
    fi
    # Same fail-closed shape as SRC_SHA above (round-3.3 P1 #2).
    TGT_SHA="$(docker run --rm \
        -e "Q_TARGET_REL=${Q_TARGET_REL}" \
        -v "$RELAY_VOLUME:/data:ro" \
        alpine:3.20 \
        sh -c 'sum="$(sha256sum "/data/$Q_TARGET_REL")" && printf %s "${sum%% *}"')"
    if [[ ! "$TGT_SHA" =~ ^[0-9a-f]{64}$ ]]; then
        echo "FATAL: target sha256 did not match ^[0-9a-f]{64}$ (got: ${TGT_SHA@Q})"
        exit 3
    fi
    if [[ "$TGT_SHA" != "$SRC_SHA" ]]; then
        echo "FATAL: quarantined-file sha256 drift"
        echo "  source snapshot = $SRC_SHA"
        echo "  target on disk  = $TGT_SHA"
        exit 3
    fi

    # ─ DONE (append to the SAME canonical audit log) ─
    docker run --rm \
        -e "QF=${QF}" \
        -e "Q_TARGET_REL=${Q_TARGET_REL}" \
        -e "Q_AUDIT_LOG_ABS=${Q_AUDIT_LOG_ABS}" \
        -e "STAMP=${STAMP}" \
        -e "QMODE=${QUARANTINE_MODE}" \
        -e "TGT_SHA=${TGT_SHA}" \
        -v "$RELAY_VOLUME:/data" \
        alpine:3.20 \
        sh -c '
            set -eu
            {
                printf "\n--- DONE ---\n"
                printf "stamp=%s\n" "$STAMP"
                printf "mode=%s\n" "$QMODE"
                printf "file=/var/phantom/%s\n" "$QF"
                printf "moved_to=/var/phantom/%s\n" "$Q_TARGET_REL"
                printf "sha256=%s\n" "$TGT_SHA"
                printf "finished_at_utc=%s\n" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
            } >> "$Q_AUDIT_LOG_ABS"
            sync
        ' 2>&1 | tee -a "$QDIR_STAGED/file-quarantine.log"

    # Round-3.2 P2 #5: audit_log in the summary must reflect the
    # canonical path (in-volume prefix `/var/phantom/…`), not the
    # helper-container view (`/data/…`), and NOT an obsolete
    # `.quarantine/…` string from an earlier iteration.
    printf 'mode=%s\nfile_quarantined=/var/phantom/%s\nquarantine_target=/var/phantom/%s\nsource_sha256=%s\ntarget_sha256=%s\naudit_log=/var/phantom/%s\n' \
        "$QUARANTINE_MODE" "$QF" "$Q_TARGET_REL" "$SRC_SHA" "$TGT_SHA" \
        "${Q_AUDIT_LOG_ABS#/data/}" \
        > "$QDIR_STAGED/file-quarantine.summary.txt"
fi
```

## Step 5 — offline volume snapshot (inside sidecar)

Container is guaranteed stopped by Step 3. The volume is now
quiescent. The tar captures BOTH the still-present state files AND
the newly-added `.quarantine/` subdirectory from Step 4.

```bash
docker run --rm \
    -v "$RELAY_VOLUME:/data:ro" \
    -v "$QDIR_STAGED":/backup \
    alpine:3.20 \
    sh -c 'tar -C /data -czf /backup/volume-snapshot.tgz . && ls -la /backup/' \
    2>&1 | tee -a "$QDIR_STAGED/volume-snapshot.log"

sha256sum "$QDIR_STAGED/volume-snapshot.tgz" \
    > "$QDIR_STAGED/volume-snapshot.tgz.sha256"

# Verify the tar round-trips.
scratch="$(mktemp -d)"
docker run --rm \
    -v "$QDIR_STAGED":/backup:ro \
    -v "$scratch":/scratch \
    alpine:3.20 \
    sh -c 'tar -C /scratch -xzf /backup/volume-snapshot.tgz && ls -la /scratch' \
    > "$QDIR_STAGED/volume-snapshot.roundtrip.txt"
rm -rf "$scratch"
```

If the tar or the round-trip fails, the volume is likely damaged.
Record the error, do NOT delete the sidecar, exit non-zero, escalate.

## Step 6 — v4.2.3 §2 step 3 rename + parent fsync (sidecar commit)

Now the staged sidecar holds every piece of evidence: INTENT +
triage + file-quarantine audit + volume snapshot. Atomically rename
it to its final name; that rename is the "commit" of the sidecar.

```bash
# Manifest before commit.
( cd "$QDIR_STAGED" && find . -type f -print0 | xargs -0 sha256sum ) \
    > "$QDIR_STAGED/pre-commit-sha256sums.txt"

sync
mv -- "$QDIR_STAGED" "$QDIR"       # POSIX atomic rename
sync                               # parent dir metadata to disk
```

## Step 7 — DONE marker + fsync (v4.2.3 §2 step 4, sidecar)

Only after the atomic rename succeeds do we mark the quarantine
DONE. The DONE marker is what tells a follow-up process (or a human
scanning `/var/quarantine`) that this sidecar completed cleanly.

```bash
cat > "$QDIR/DONE.quarantine" <<EOF
finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
final_dir=$QDIR
service=$RELAY_SERVICE
container=$RELAY_CONTAINER
volume=$RELAY_VOLUME
quarantine_file=${QUARANTINE_FILE:-<none>}
volume_snapshot_sha256=$(awk '{print $1}' "$QDIR/volume-snapshot.tgz.sha256")
EOF
sync

# Final artefact manifest (including DONE).
( cd "$QDIR" && find . -type f ! -name 'sha256sums.txt' -print0 \
    | xargs -0 sha256sum ) > "$QDIR/sha256sums.txt"

echo
echo "─────────────────────────────────────────────────────────────"
echo "  Quarantine snapshot: $QDIR"
if [[ -n "$QUARANTINE_FILE" ]]; then
    # Round-3.3 architect P2 #3: pre-round-3.3 the second line
    # hard-coded `.quarantine/...` even for queue-mode. Print the
    # actual $Q_TARGET_REL that Step 4 resolved.
    echo "  Mode:               $QUARANTINE_MODE"
    echo "  File quarantined:   /var/phantom/$QUARANTINE_FILE"
    echo "                      → /var/phantom/$Q_TARGET_REL"
fi
echo "  Relay is DOWN. Do NOT restart until incident is triaged."
echo "  Hand off the ticket + this directory to the human reviewer."
echo "─────────────────────────────────────────────────────────────"
```

## Step 8 — recovery paths (human decision)

Only one path per incident. Do not mix. Every path ends with the
attestation sequence from `rotation.md` §6 + the triple witness.

### Path A — clean restart with same image (transient issue)

Use when the incident evidence points to a transient cause outside
the relay (disk pressure resolved, kernel OOM ceiling now
appropriate, upstream Docker Desktop / kernel restart). Volume
contents are intact except for the file(s) moved to
`/var/phantom/.quarantine/`. On the next boot the relay will find
no `prekeys.jsonl` (if it was quarantined) and re-generate it
empty — legitimate for the append-log shape.

```bash
cd /srv/phantom
docker compose -f deploy/docker-compose.yml up -d --force-recreate relay
# Then run rotation.md §6 (attestation) + §"Triple witness".
```

### Path B — rollback to previous image (regression in a rotation)

Use when the incident followed a recent `rotation.md` step and the
persist-failure or FATAL was NOT observed on the previous image.

```bash
docker image ls phantom-relay --format '{{.Tag}} {{.ID}} {{.CreatedAt}}'
docker tag phantom-relay:<previous-short-sha> phantom-relay:latest
cd /srv/phantom
docker compose -f deploy/docker-compose.yml up -d --force-recreate relay
# Then run rotation.md §6 + §"Triple witness".
```

Also open a follow-up ticket: the merged commit that produced the
bad image must be reverted or forward-fixed before the next
rotation.

### Path C — restore from a prior volume backup (state corruption)

Use only when the incident evidence indicates ON-DISK state
corruption beyond a single file. Destructive on the current volume
— the volume snapshot from Step 5 preserves the compromised state
for later analysis.

Follow `rotation.md` §"Restore from backup". After restore, run
`rotation.md` §6 + §"Triple witness".

### Path D — quarantine holds; PR-1c-adjacent incident

Use when the incident looks like an application-level defect this
runbook cannot recover (e.g. a poison envelope pattern — future
PR-1c scope). Leave the relay down. Escalate. Do NOT touch the
volume.

## Step 9 — clear quarantine

Only after Path A / B / C attestation is clean AND the incident
ticket is closed. Retain snapshots for at least 30 days for
post-mortem review; prune with a cron that respects the retention
window (not part of this runbook).

`/var/phantom/.quarantine/*.<stamp>` entries can be moved out of
band by the operator (e.g. to long-term cold storage) — do NOT
`rm` them while the incident is open.

## Reference: what triggered which check

| Signal | QUARANTINE_FILE | Path |
|---|---|---|
| `event="prekey_persist_failed"` | `prekeys.jsonl` | A / C |
| `event="audit_persist_failed"` (repeat) | `reports.jsonl` | A |
| `FATAL: preflight:` in fresh boot | (none — image issue) | A / B |
| `panic` at start | (none — image issue) | B |
| `OOM` / `OOMKilled=true` | (none — capacity issue) | A (adjust memory limit) or B |
| `SIGSEGV` | (none — image issue) | B |
| `RestartCount` growing unexpectedly | (depends on log evidence) | A or B |
| `state_load_torn_lines skipped=N` | the file that torn-line applies to | A (single event) or C (repeated) |

## Sidecar structure (what the reviewer sees at `$QDIR`)

```
/var/quarantine/phantom-relay-<utc>/
├── INTENT.quarantine                — from Step 1, timestamp + reason
├── DONE.quarantine                  — from Step 7, only if clean flow
├── inspect.json                     — Step 2
├── ps.txt                           — Step 2
├── state-dir.txt                    — Step 2
├── state-file-stats.txt             — Step 2
├── logs.txt                         — Step 2 (full 24h)
├── anomalies.txt                    — Step 2 (filtered)
├── docker-events.txt                — Step 2
├── triage-rc.txt                    — Step 2 per-line exit codes
├── stop.txt                         — Step 3
├── post-stop-state.txt              — Step 3
├── file-quarantine.log              — Step 4 (only if QUARANTINE_FILE set)
├── file-quarantine.source.sha256    — Step 4
├── file-quarantine.summary.txt      — Step 4
├── volume-snapshot.tgz              — Step 5 (offline, quiescent, includes .quarantine/)
├── volume-snapshot.tgz.sha256       — Step 5
├── volume-snapshot.roundtrip.txt    — Step 5 verify
├── volume-snapshot.log              — Step 5
├── pre-commit-sha256sums.txt        — Step 6
└── sha256sums.txt                   — Step 7 (final, includes DONE)
```

And, inside `/var/phantom/` on the volume itself:

```
/var/phantom/
├── prekeys.jsonl                          (unchanged, unless STATE-quarantined)
├── reports.jsonl                          (unchanged, unless STATE-quarantined)
├── blocklist.txt                          (unchanged, unless STATE-quarantined)
├── push_tokens.jsonl                      (unchanged, unless STATE-quarantined)
├── queue/                                 (unchanged, unless QUEUE-quarantined)
├── .lock                                  (released; relay is stopped)
├── quarantine-audit.log                   (canonical append-only journal —
│                                           INTENT + DONE per rename, both modes,
│                                           locked v4.2.3)
├── .quarantine/                           (only if state-mode ran here at least once)
│   └── <file>.<utc>                       (the atomically-renamed damaged state file(s))
└── queue-quarantine/                      (only if queue-mode ran here at least once)
    └── manual-<utc>/                      (one subdir per queue-mode quarantine)
        └── <basename>                     (the atomically-renamed damaged envelope)
```

The canonical journal `/var/phantom/quarantine-audit.log` is the
single durable record of every rename this runbook has performed
(both modes). Read it from an operator/tooling context via
`docker exec phantom-relay cat /var/phantom/quarantine-audit.log`
(when relay is up) or via a helper container mounting
`deploy_phantom-reports:/data:ro` and `cat /data/quarantine-audit.log`
(when relay is down for quarantine).

Missing DONE.quarantine (the SIDECAR-level marker at `$QDIR`) =
quarantine crashed mid-flow. Do NOT trust the sidecar contents as
complete evidence; the canonical `quarantine-audit.log` inside the
volume is the durable truth of what actually got renamed. Treat
the missing sidecar-DONE as a hint and re-run the quarantine (a
fresh $STAMP produces a fresh sidecar and a fresh audit-log block
paired to a fresh in-volume `manual-<stamp>/` subdirectory).
