#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# rotate_seq_mac_key.sh — RC-RELAY-STATE-DIR-REPAIR Ops PR.
#
# Rotates the `RELAY_SEQ_MAC_KEY` env value in `/srv/phantom/.env`
# atomically, using the locked-v4.2.3 §2 INTENT+fsync sidecar flow so
# a crash mid-rotation leaves recoverable state a human can triage
# rather than a torn .env or a relay running with the OLD key while
# the compose declares the NEW.
#
# ── Round-2 architect fixes (P0 #2) ──
#   * The pre-round-2 flow rewrote .env BEFORE stopping the relay,
#     creating a window in which .env said "new key" but the running
#     relay still had the OLD key in its env — any failure inside
#     that window left the two disagreeing without any script action
#     to reconcile. The current flow stops the relay FIRST; .env
#     rewrite only happens after the stop is verified. Consequence:
#     any error before the rewrite leaves .env untouched, and any
#     error after the rewrite is caught by an EXIT trap (round-3.1
#     replaced the round-2 ERR trap because bash does NOT fire ERR
#     on an explicit `exit N`) that guarantees the relay is not
#     running with the stale env. Round-3.2 upgraded the EXIT trap
#     to a true fail-closed path: docker compose stop failure
#     falls back to `docker stop $RELAY_CONTAINER`, then a
#     mandatory `.State.Running` check confirms the container is
#     down or a distinct exit code (5) signals the operator that
#     the inconsistent state could not be resolved.
#   * `--resume-from-crash` was unsafe: it re-read the CURRENT .env
#     key and generated a NEW key, so it "resumed" by starting a
#     second rotation whose fingerprints did not match the first
#     INTENT. Removed. On sidecar collision the script prints the
#     stale INTENT and exits 4 — the operator must inspect, then
#     either roll forward manually or pass `--clear-stale-sidecar`
#     to abandon the record before re-invoking.
#
# ── Locked v4.2.3 §2 flow (round-2 order) ──
#   1. Snapshot the currently-configured key from .env.
#   2. Generate the new key.
#   3. Write INTENT sidecar + fsync (`$SIDECAR_DIR/INTENT.rotate-seq-mac`).
#   4. Stop the relay (bounded wait, strict CLI-error check).
#   5. Atomic .env rewrite: awk-render $ENV.new (0600) + fsync;
#      POSIX rename $ENV.new → $ENV; fsync parent. Owner+group+mode
#      preserved via mandatory `stat -c '%u:%g:%a'` + chown/chmod
#      + post-rename re-stat verify (round-3.2 P1 #3).
#   6. Bring the relay up with `--force-recreate`. Wait for
#      preflight_ok (bounded).
#   7. Attest RELAY_SEQ_MAC_KEY inside the container equals the new
#      value byte-for-byte (fingerprint compare).
#   8. Write DONE sidecar + fsync. Only now does the rotation count.
#
# From step 4 onward, an EXIT trap ensures the relay is left in a
# defined state — either down (mid-rotation, awaiting the operator)
# or up on the new .env — never running with the stale in-container
# env while .env has already been rewritten.
#
# ── Exit codes ──
#   0  PASS (rotation complete + attested + DONE written).
#   2  FAIL: attestation failed after new key applied. Operator must
#      review DONE state manually; relay is DOWN.
#   3  FAIL: relay did not stop or come back within bounded window.
#      Relay state is left as-is (DOWN), INTENT sidecar preserved.
#   4  FAIL: previous rotation left INTENT without DONE. Human review
#      required. Pass `--clear-stale-sidecar` to abandon the record
#      after inspection.
#   5  FAIL: EXIT trap could not confirm relay is stopped after a
#      post-mutation failure. INCONSISTENT STATE — .env may have
#      the new key while a running container still holds the old.
#      Immediate manual intervention required. INTENT preserved.
#   9  Configuration error (guard triggered / precondition missing).

set -Eeuo pipefail
IFS=$'\n\t'

# ── Guards ────────────────────────────────────────────────────────────────

require_bin() {
    if ! command -v "$1" >/dev/null 2>&1; then
        printf 'FATAL: required binary %s not on PATH\n' "$1" >&2
        exit 9
    fi
}
for bin in docker openssl sha256sum awk sed grep sync flock stat chown chmod; do
    require_bin "$bin"
done
if ! docker compose version >/dev/null 2>&1; then
    printf 'FATAL: `docker compose` (v2) required\n' >&2
    exit 9
fi

# ── Configuration ─────────────────────────────────────────────────────────

REPO_DIR="${REPO_DIR:-/srv/phantom}"
ENV_FILE="${ENV_FILE:-$REPO_DIR/.env}"
SIDECAR_DIR="${SIDECAR_DIR:-/var/rotation-tickets/seq-mac}"
COMPOSE_FILE="${COMPOSE_FILE:-$REPO_DIR/deploy/docker-compose.yml}"

# Service vs container name — must NOT be confused (round-2 P0 #1
# in quarantine.md). Service = argument to `docker compose <cmd>`,
# container = argument to `docker inspect / exec / logs`.
RELAY_SERVICE="relay"
RELAY_CONTAINER="phantom-relay"

STOP_DEADLINE="${STOP_DEADLINE:-30}"
UP_DEADLINE="${UP_DEADLINE:-90}"

if [[ ! -d "$REPO_DIR" ]]; then
    printf 'FATAL: REPO_DIR=%s does not exist\n' "$REPO_DIR" >&2
    exit 9
fi
if [[ ! -f "$ENV_FILE" ]]; then
    printf 'FATAL: ENV_FILE=%s does not exist\n' "$ENV_FILE" >&2
    exit 9
fi
if [[ ! -f "$COMPOSE_FILE" ]]; then
    printf 'FATAL: COMPOSE_FILE=%s does not exist\n' "$COMPOSE_FILE" >&2
    exit 9
fi

install -d -m 700 -o "$(id -u)" -g "$(id -g)" "$SIDECAR_DIR"

# Serialise: never run two rotations concurrently.
LOCK_FILE="$SIDECAR_DIR/.rotate.lock"
exec {LOCK_FD}>>"$LOCK_FILE"
if ! flock -n "$LOCK_FD"; then
    printf 'FATAL: another rotation is holding %s\n' "$LOCK_FILE" >&2
    exit 9
fi

# ── Argument parsing ─────────────────────────────────────────────────────
# Round-2 P0 #2: `--resume-from-crash` was unsafe and has been
# removed. `--clear-stale-sidecar` remains as an explicit operator
# action to abandon a mid-flight INTENT after human review.

CLEAR_STALE=0
for arg in "$@"; do
    case "$arg" in
        --clear-stale-sidecar) CLEAR_STALE=1 ;;
        --resume-from-crash)
            printf 'FATAL: --resume-from-crash removed in round-2 (unsafe).\n' >&2
            printf '  Inspect the sidecar INTENT manually, then re-run with\n' >&2
            printf '  --clear-stale-sidecar to abandon and start a fresh rotation.\n' >&2
            exit 9
            ;;
        *)
            printf 'FATAL: unknown arg %s\n' "$arg" >&2
            exit 9
            ;;
    esac
done

# ── Sidecar state ─────────────────────────────────────────────────────────

INTENT_FILE="$SIDECAR_DIR/INTENT.rotate-seq-mac"
DONE_FILE="$SIDECAR_DIR/DONE.rotate-seq-mac"

if [[ -e "$INTENT_FILE" && ! -e "$DONE_FILE" ]]; then
    printf 'FATAL: previous rotation left INTENT without DONE.\n' >&2
    printf '  intent = %s\n' "$INTENT_FILE" >&2
    printf '  ── contents ──\n' >&2
    cat "$INTENT_FILE" >&2
    printf '  ── end contents ──\n' >&2
    if (( CLEAR_STALE == 1 )); then
        mv -- "$INTENT_FILE" "$SIDECAR_DIR/INTENT.rotate-seq-mac.$(date -u +%Y%m%dt%H%M%Sz).abandoned"
        sync
        printf 'Cleared stale sidecar and archived as .abandoned. Re-run to rotate.\n' >&2
        exit 4
    fi
    printf 'Inspect the state manually. Then either finish rotation by hand,\n' >&2
    printf 'or re-run with --clear-stale-sidecar to abandon and rotate afresh.\n' >&2
    exit 4
fi

if [[ -e "$DONE_FILE" ]]; then
    mv -- "$DONE_FILE" "$SIDECAR_DIR/DONE.rotate-seq-mac.$(date -u +%Y%m%dt%H%M%Sz).prev"
    sync
fi

# ── Fingerprint helper ───────────────────────────────────────────────────
# We record ONLY the SHA-256 fingerprint of the key in every audit
# artefact, never the key itself.

fingerprint() {
    printf '%s' "$1" | sha256sum | awk '{print $1}'
}

# ── strict_still_running: fail-loud check ────────────────────────────────
# Prints the container id of `$RELAY_SERVICE` if still running,
# empty otherwise. Aborts (exit 3) on `docker compose ps` CLI
# failure — refuses to mask a CLI error as a successful "stopped".

strict_still_running() {
    local out rc
    out="$(docker compose -f "$COMPOSE_FILE" ps -q --status running "$RELAY_SERVICE" 2>&1)"
    rc=$?
    if (( rc != 0 )); then
        printf 'FATAL: docker compose ps -q --status running %s failed (rc=%d)\n  output: %s\n' \
            "$RELAY_SERVICE" "$rc" "$out" >&2
        exit 3
    fi
    printf '%s' "$out"
}

# ── EXIT trap: guarantee we never leave a stale-env running relay ────────
# Round-3 architect P0 #2: bash does NOT run an `ERR` trap on an
# explicit `exit N` — it fires only on an unchecked command failure
# under `set -e`. The prior `trap on_err ERR` therefore missed six
# hard-exit branches after MUTATED=1 (missing preflight_ok, key
# fingerprint mismatch, empty in-container env, etc.), any of which
# left the relay running with a stale in-container env while .env
# had been rewritten. Replace with an `EXIT` trap that fires on
# ANY exit path, guards on `rc != 0 && MUTATED == 1`, and — this is
# the previously-missing bit — verifies the stop with
# strict_still_running() so a silent stop-failure cannot slip past.
#
# The dangerous window is: .env has been rewritten AND relay is
# currently running with the OLD env. Our step order stops the relay
# BEFORE .env is rewritten, so under normal execution the window
# does not open. The EXIT trap exists so a `compose restart:
# unless-stopped` race, a manual `docker start` in flight, or any
# post-mutation exit path also cannot leave the two disagreeing
# unnoticed.

MUTATED=0
CLEANUP_TRIGGERED=0

on_exit() {
    local rc=$?
    if (( CLEANUP_TRIGGERED == 1 )); then
        exit "$rc"
    fi
    CLEANUP_TRIGGERED=1
    # Successful exit path — trap disarmed in-line at the end of
    # Step 8 for tidiness, but even if it fires with rc=0 there is
    # nothing to clean up.
    if (( rc == 0 )); then
        exit 0
    fi
    printf '\n[EXIT trap] script exiting with rc=%d\n' "$rc" >&2
    if (( MUTATED != 1 )); then
        # Nothing was mutated; the working state is unchanged.
        exit "$rc"
    fi
    printf '[EXIT trap] .env was already rewritten; forcing relay stop\n' >&2

    # Round-3.2 P1 #4: pre-round-3.2 the trap trusted a single
    # `docker compose stop` and, on its non-zero rc, exited
    # WITHOUT checking whether the container was still running —
    # a compose CLI hiccup would happily leave the relay up with
    # the STALE env even though .env now has the NEW key. Fix:
    #   (a) try `docker compose stop`;
    #   (b) on ANY compose failure, fall back to `docker stop`
    #       on the container name — bypasses compose entirely
    #       and speaks directly to the daemon;
    #   (c) drain a bounded wait against strict_still_running();
    #   (d) MANDATORY final `docker inspect .State.Running` check;
    #   (e) if the container cannot be confirmed stopped, exit
    #       with the DISTINCT code 5 — the operator gets an
    #       unambiguous signal that the inconsistent state
    #       requires immediate manual intervention.

    # (a) primary stop path
    if ! docker compose -f "$COMPOSE_FILE" stop "$RELAY_SERVICE" 2>&1; then
        printf '[EXIT trap] docker compose stop returned non-zero — falling back to docker stop\n' >&2
        # (b) direct-daemon fallback
        if ! docker stop "$RELAY_CONTAINER" 2>&1; then
            printf '[EXIT trap] docker stop ALSO returned non-zero\n' >&2
        fi
    fi

    # (c) drain the bounded wait window against strict_still_running.
    local waited=0
    while (( waited < STOP_DEADLINE )); do
        local still
        still="$(strict_still_running 2>/dev/null || true)"
        if [[ -z "$still" ]]; then
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done

    # (d) MANDATORY final Running-state check via docker inspect —
    # authoritative in a way `docker compose ps` is not (compose
    # may fail on state inference; docker inspect on the container
    # name is a direct daemon RPC).
    #
    # Round-3.3 architect P1 #1: pre-round-3.3 the assignment was
    # `running_state="$(docker inspect …)"` on its own line, then
    # `local inspect_rc=$?`. Under `set -e`, a standalone assignment
    # whose command substitution returned non-zero aborts the trap
    # immediately — the `inspect_rc=$?` line was dead code and the
    # `exit 5` branch was unreachable. Run the assignment inside an
    # `if` so `set -e` treats the failure as tested, then read `$?`
    # from the `else` clause.
    local running_state inspect_rc=0
    if running_state="$(docker inspect "$RELAY_CONTAINER" --format '{{.State.Running}}' 2>&1)"; then
        inspect_rc=0
    else
        inspect_rc=$?
    fi
    if (( inspect_rc != 0 )); then
        printf '[EXIT trap] FATAL: docker inspect failed (rc=%d): %s\n' \
            "$inspect_rc" "$running_state" >&2
        printf '[EXIT trap]        Cannot confirm relay state — INCONSISTENT STATE\n' >&2
        printf '[EXIT trap]        INTENT preserved at %s\n' "$INTENT_FILE" >&2
        printf '[EXIT trap]        Manual intervention required\n' >&2
        # (e) distinct fail-closed code
        exit 5
    fi
    if [[ "$running_state" != 'false' ]]; then
        printf '[EXIT trap] FATAL: relay .State.Running = %s (expected false)\n' \
            "$running_state" >&2
        printf '[EXIT trap]        .env has NEW key, container still up with OLD env — INCONSISTENT STATE\n' >&2
        printf '[EXIT trap]        INTENT preserved at %s\n' "$INTENT_FILE" >&2
        printf '[EXIT trap]        Manual intervention required\n' >&2
        # (e) distinct fail-closed code
        exit 5
    fi
    printf '[EXIT trap] relay verified stopped (.State.Running=false); INTENT at %s\n' \
        "$INTENT_FILE" >&2
    exit "$rc"
}
trap on_exit EXIT

# ── Step 1: snapshot the current key from .env ───────────────────────────

OLD_KEY="$(grep -E '^RELAY_SEQ_MAC_KEY=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
if [[ ! "$OLD_KEY" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'FATAL: current RELAY_SEQ_MAC_KEY in %s does not match ^[0-9a-f]{64}$\n' \
        "$ENV_FILE" >&2
    exit 9
fi
OLD_FP="$(fingerprint "$OLD_KEY")"

# ── Step 2: generate the new key ─────────────────────────────────────────

NEW_KEY="$(openssl rand -hex 32)"
if [[ ! "$NEW_KEY" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'FATAL: openssl produced non-hex output\n' >&2
    exit 9
fi
NEW_FP="$(fingerprint "$NEW_KEY")"

if [[ "$OLD_FP" == "$NEW_FP" ]]; then
    printf 'FATAL: openssl generated the SAME key (impossible in practice) — aborting\n' >&2
    exit 9
fi

# ── Step 3: INTENT + fsync ────────────────────────────────────────────────

STAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
umask 077
cat > "$INTENT_FILE" <<EOF
started_at_utc=$STAMP
repo_dir=$REPO_DIR
env_file=$ENV_FILE
compose_file=$COMPOSE_FILE
relay_service=$RELAY_SERVICE
relay_container=$RELAY_CONTAINER
old_key_fingerprint_sha256=$OLD_FP
new_key_fingerprint_sha256=$NEW_FP
EOF
sync

printf 'INTENT written: %s\n' "$INTENT_FILE"

# ── Step 4: stop the relay (bounded, strict) ─────────────────────────────

cd "$REPO_DIR"
docker compose -f "$COMPOSE_FILE" stop "$RELAY_SERVICE"

waited=0
while (( waited < STOP_DEADLINE )); do
    still="$(strict_still_running)"
    if [[ -z "$still" ]]; then break; fi
    sleep 1
    waited=$((waited + 1))
done
still="$(strict_still_running)"
if [[ -n "$still" ]]; then
    printf 'FATAL: relay still running after %ds stop wait — refusing to touch .env\n' \
        "$STOP_DEADLINE" >&2
    exit 3
fi

# ── Step 5: atomic .env rewrite ──────────────────────────────────────────
# Only reached with the relay verified stopped. From this line on,
# MUTATED=1 ensures the EXIT trap re-stops the relay if any later
# step trips (defence-in-depth against a compose `restart:
# unless-stopped` race or a manual `docker start` in flight).

MUTATED=1

TMP_ENV="${ENV_FILE}.new"
# Preserve every other env line verbatim; replace only the seq mac line.
awk -v key='RELAY_SEQ_MAC_KEY' -v val="$NEW_KEY" '
    BEGIN { replaced=0 }
    {
        if (match($0, "^" key "=") == 1) {
            print key "=" val
            replaced=1
        } else {
            print
        }
    }
    END {
        if (replaced == 0) {
            print key "=" val
        }
    }
' "$ENV_FILE" > "$TMP_ENV"

# Preserve mode AND ownership — mandatory, fail-closed at every step.
# Round-3.2 architect P1 #3: pre-round-3.2 the flow was fail-open —
# `stat` absence silently defaulted to 0600, a chown failure was
# ignored whenever the UID matched (even if the GID differed), and
# there was no post-rename metadata re-verify. Fixed:
#   * `stat` is a mandatory precondition (checked in Guards at
#     the top of the script).
#   * Any stat/chown/chmod failure is FATAL (exit 2). No silent
#     fallback to defaults.
#   * After the atomic rename, re-stat the FINAL file and compare
#     `%u:%g:%a` byte-for-byte against the captured snapshot; any
#     drift aborts with exit 2 (which the EXIT trap then converts
#     to a fail-closed relay-stop sequence — MUTATED=1 is set
#     BEFORE the rename below).

if ! command -v stat >/dev/null 2>&1; then
    printf 'FATAL: `stat` not on PATH — cannot preserve .env ownership atomically\n' >&2
    exit 2
fi

if ! _OLD_METADATA="$(stat -c '%u:%g:%a' "$ENV_FILE")"; then
    printf 'FATAL: stat failed on %s — refusing to rotate without metadata snapshot\n' \
        "$ENV_FILE" >&2
    exit 2
fi
OLD_UID="${_OLD_METADATA%%:*}"
_rest="${_OLD_METADATA#*:}"
OLD_GID="${_rest%%:*}"
OLD_MODE="${_rest#*:}"
if [[ -z "$OLD_UID" || -z "$OLD_GID" || -z "$OLD_MODE" ]]; then
    printf 'FATAL: stat returned unparseable metadata: %s\n' "$_OLD_METADATA" >&2
    exit 2
fi

# `chown` needs root when target uid or gid differs from the
# current process. If it fails for any reason, FAIL — never silent.
if ! chown "$OLD_UID:$OLD_GID" "$TMP_ENV"; then
    printf 'FATAL: chown %s:%s %s failed — refusing to leave .env with wrong owner\n' \
        "$OLD_UID" "$OLD_GID" "$TMP_ENV" >&2
    exit 2
fi
if ! chmod "$OLD_MODE" "$TMP_ENV"; then
    printf 'FATAL: chmod %s %s failed — refusing to leave .env with wrong mode\n' \
        "$OLD_MODE" "$TMP_ENV" >&2
    exit 2
fi

sync                                    # fsync-ish for the pre-rename file
mv -- "$TMP_ENV" "$ENV_FILE"            # POSIX atomic rename
sync                                    # parent-dir fsync equivalent

# Sanity re-read.
POST_KEY="$(grep -E '^RELAY_SEQ_MAC_KEY=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
if [[ "$POST_KEY" != "$NEW_KEY" ]]; then
    printf 'FATAL: .env post-write does not contain the new key\n' >&2
    exit 2
fi
POST_FP="$(fingerprint "$POST_KEY")"
if [[ "$POST_FP" != "$NEW_FP" ]]; then
    printf 'FATAL: .env post-write fingerprint mismatch: %s != %s\n' \
        "$POST_FP" "$NEW_FP" >&2
    exit 2
fi

# Round-3.2 P1 #3: post-rename metadata re-verify. `stat` the
# FINAL file (not the temp) and compare byte-for-byte against the
# snapshot captured pre-rewrite. Any drift means the chown/chmod
# did not survive the rename or an external process touched the
# file mid-flight — either way, the operator gets a fail-closed
# signal instead of an escalated-ownership silent success.
if ! _NEW_METADATA="$(stat -c '%u:%g:%a' "$ENV_FILE")"; then
    printf 'FATAL: post-rename stat failed on %s\n' "$ENV_FILE" >&2
    exit 2
fi
if [[ "$_NEW_METADATA" != "$_OLD_METADATA" ]]; then
    printf 'FATAL: .env metadata drift after rename\n' >&2
    printf '  expected (pre-write) : %s\n' "$_OLD_METADATA" >&2
    printf '  actual   (post-rename): %s\n' "$_NEW_METADATA" >&2
    exit 2
fi
printf 'ENV rewritten atomically (new fingerprint: %s...; owner %s preserved)\n' \
    "${NEW_FP:0:16}" "$_OLD_METADATA"

# ── Step 6: recreate + bounded preflight wait ────────────────────────────

docker compose -f "$COMPOSE_FILE" up -d --force-recreate "$RELAY_SERVICE"

waited=0
running='false'
while (( waited < UP_DEADLINE )); do
    running="$(docker inspect "$RELAY_CONTAINER" --format '{{.State.Running}}' 2>/dev/null || echo gone)"
    if [[ "$running" == 'true' ]]; then
        if docker logs --since 5m "$RELAY_CONTAINER" 2>&1 \
                | grep -Fq 'event="state_dir_preflight_ok"'; then
            break
        fi
    fi
    sleep 1
    waited=$((waited + 1))
done
if [[ "$running" != 'true' ]]; then
    printf 'FATAL: relay not Running after %ds\n' "$UP_DEADLINE" >&2
    exit 3
fi
if ! docker logs --since 5m "$RELAY_CONTAINER" 2>&1 \
        | grep -Fq 'event="state_dir_preflight_ok"'; then
    printf 'FATAL: preflight_ok not observed within %ds\n' "$UP_DEADLINE" >&2
    exit 3
fi

# ── Step 7: attest the container actually picked up the new key ──────────

# `docker exec … env` reveals RELAY_SEQ_MAC_KEY. Compare fingerprint,
# not the value — no key material lands in evidence.
IN_CONTAINER_KEY="$(docker exec "$RELAY_CONTAINER" \
    sh -c 'printf %s "${RELAY_SEQ_MAC_KEY:-}"' 2>/dev/null || echo '')"
if [[ ! "$IN_CONTAINER_KEY" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'FATAL: RELAY_SEQ_MAC_KEY inside container does not match ^[0-9a-f]{64}$\n' >&2
    exit 2
fi
IN_CONTAINER_FP="$(fingerprint "$IN_CONTAINER_KEY")"
if [[ "$IN_CONTAINER_FP" != "$NEW_FP" ]]; then
    printf 'FATAL: in-container fingerprint mismatch:\n' >&2
    printf '  container = %s\n' "$IN_CONTAINER_FP" >&2
    printf '  expected  = %s\n' "$NEW_FP" >&2
    exit 2
fi

# ── Step 8: DONE + fsync ──────────────────────────────────────────────────

FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "$DONE_FILE" <<EOF
finished_at_utc=$FINISHED_AT
old_key_fingerprint_sha256=$OLD_FP
new_key_fingerprint_sha256=$NEW_FP
in_container_fingerprint_sha256=$IN_CONTAINER_FP
env_file=$ENV_FILE
service=$RELAY_SERVICE
container=$RELAY_CONTAINER
EOF
sync

# Rotation complete — disarm the EXIT trap so a rc=0 exit stays a
# quiet no-op instead of tripping the cleanup path.
trap - EXIT
CLEANUP_TRIGGERED=1

printf 'OK: RELAY_SEQ_MAC_KEY rotated\n'
printf '  old fp = %s\n' "${OLD_FP:0:16}..."
printf '  new fp = %s\n' "${NEW_FP:0:16}..."
printf '  DONE   = %s\n' "$DONE_FILE"
exit 0
