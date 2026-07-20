#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# PR-1b RC-RELAY-STATE-DIR-REPAIR — Mac-local integration witness.
# v3.4.4 approved — supersedes the pr1b-staging-witness.sh 24h VPS path
# for the immediate execution: we do NOT have a separate staging VPS,
# and running any test project alongside the production relay on
# relay.phntm.pro is out of scope. This launcher runs the entire
# witness locally on Docker Desktop under `caffeinate`.
#
# ── What this is ──
# Proportional local integration witness. 8 probes (7 intervals of
# 900s between them) plus a wall-clock floor of 7200s plus one final
# probe — approximately 2 hours end-to-end. Exercises PR-1b code paths
# that a purely functional test would miss under continuous drive:
# preflight, `.lock`, persist-first + rollback, audit-tier failure
# counters, planned recreate + post-recreate replay. Torn-line loader
# handling is NOT injected here — the witness only detects a torn-
# line event as an anomaly if the relay itself emits one under normal
# load. Uses the merged 27a06c8a image, `linux/amd64` under Docker
# Desktop emulation on Apple Silicon.
#
# ── Accepted residual ──
# Docker Desktop's Linux VM + amd64 emulation exercise the PR-1b
# behaviour but do NOT cover native VPS performance/topology
# characteristics (page cache tuning, disk queue depth, real network
# latency). This gate CANNOT stand in for a native VPS soak; it's a
# proportional integration test we can actually run right now given
# there is no staging VPS.
#
# ── What this NEVER does ──
# - Never touches production VPS (`relay.phntm.pro`) — no SSH, no
#   SCP, no docker context switch, no host reachability probe.
# - Never runs against a Docker context other than `desktop-linux`
#   with a local unix socket endpoint.
# - Never runs on any OS other than Darwin.
# - Never publishes host ports.
# - Never touches production compose project (`deploy`) or production
#   volume (`deploy_phantom-reports`) or production container
#   (`phantom-relay`) — multi-layer guards refuse to start if any
#   overlap is detected.
# - Never prunes global Docker resources on cleanup.
#
# ── Exit codes ──
#   0  PASS.
#   2  FAIL at T0 gate.
#   3  FAIL during observation (see observation-anomalies.txt).
#   4  FAIL at post-recreate re-check.
#   5  FAIL: evidence finalisation could not compute sha256sums.txt.
#   6  FAIL: `caffeinate` died mid-run OR sleep gap detected.
#   9  Configuration error (guard triggered / precondition missing).

set -Eeuo pipefail
IFS=$'\n\t'

# ── §1: platform + Docker-context guards (round-3.4 architect P0) ────────────
#
# Refuse to run outside Docker Desktop's local unix socket. The whole
# rationale of the Mac-local path is "no VPS". A leaked TCP/SSH context
# would silently reach production.

require_bin() {
    if ! command -v "$1" >/dev/null 2>&1; then
        printf 'FATAL: required binary %s not on PATH\n' "$1" >&2
        exit 9
    fi
}
require_env() {
    if [[ -z "${!1:-}" ]]; then
        printf 'FATAL: required env var %s is not set\n' "$1" >&2
        exit 9
    fi
}

for bin in docker git sed awk grep find xargs jq perl caffeinate uname openssl; do
    require_bin "$bin"
done
if ! docker compose version >/dev/null 2>&1; then
    printf 'FATAL: `docker compose` (v2) required\n' >&2
    exit 9
fi

OS_KIND="$(uname -s)"
if [[ "$OS_KIND" != 'Darwin' ]]; then
    printf 'FATAL: this witness is Mac-only (uname -s = %s, expected Darwin)\n' "$OS_KIND" >&2
    exit 9
fi

DOCKER_CTX="$(docker context show 2>/dev/null)"
if [[ "$DOCKER_CTX" != 'desktop-linux' ]]; then
    printf 'FATAL: docker context = %s, expected `desktop-linux`\n' "$DOCKER_CTX" >&2
    printf 'The Mac-local witness refuses to run outside Docker Desktop.\n' >&2
    printf 'Run `docker context use desktop-linux` and retry.\n' >&2
    exit 9
fi

DOCKER_HOST_URI="$(docker context inspect "$DOCKER_CTX" --format '{{.Endpoints.docker.Host}}' 2>/dev/null || echo '')"
case "$DOCKER_HOST_URI" in
    unix://*) : ;;
    *)
        printf 'FATAL: docker endpoint = %s, expected a unix:// socket.\n' "$DOCKER_HOST_URI" >&2
        printf 'SSH/TCP contexts are refused — this witness is strictly local.\n' >&2
        exit 9
        ;;
esac

# ── Portable helpers ─────────────────────────────────────────────────────────

_stat_mode()      { stat -f %A "$1"; }
_stat_owner_uid() { stat -f %u "$1"; }
_canonical() {
    if readlink -f / >/dev/null 2>&1; then
        readlink -f "$1"
    else
        perl -MCwd=abs_path -le 'print abs_path($ARGV[0])' "$1"
    fi
}
if command -v sha256sum >/dev/null 2>&1; then
    SHA256_CMD=(sha256sum -b)
elif command -v shasum >/dev/null 2>&1; then
    SHA256_CMD=(shasum -a 256 -b)
else
    printf 'FATAL: no sha256 utility on PATH\n' >&2
    exit 9
fi

# ── §2: mandatory env + hard floors (round-3.2 P0 #1 pattern) ────────────────

require_env COMPOSE_PROJECT_NAME
require_env COMPOSE_FILE
require_env COMPOSE_PROJECT_DIR
require_env WITNESS_IDENTITY_HEX
require_env WITNESS_IMAGE
require_env WITNESS_KEY_PATH
require_env FIXTURE_SECRETS_DIR

# Expected image digest for the merged PR-1b relay image. Hardcoded
# from the architect-supplied source-of-truth so an operator override
# cannot smuggle a different image in.
readonly EXPECTED_RELAY_IMAGE_ID='sha256:a07e53098650b6cac3585da3751d72c159b7ddce4d2b6a874078d1f6526198ce'
readonly EXPECTED_MASTER_SHA='27a06c8aa06d15f2a152268e00c606a5cefff828'
readonly RELAY_IMAGE_REF='phantom-relay:pr1b-27a06c8a'
readonly RELAY_INTERNAL_PORT='18080'
# Round-3.4.1 architect P0 #2: witness-client image ID is pinned too;
# arch-only match let any amd64 image under the tag become the test
# oracle. Digest is architect-supplied source-of-truth.
readonly EXPECTED_WITNESS_IMAGE_ID='sha256:3d08d6ea00763de0c6936e01b8ffe6c8c89b52e2cfdbbb6e574d22a5fb39d4be'
# Round-3.4.1 architect P1 #3: compose fixture is content-addressed by
# SHA-256 so an unnoticed edit to the YAML cannot silently change the
# test topology. Value = SHA-256 of `pr1b-mac-local-compose.yml` as
# checked into this scratch tree (v3.4 draft).
readonly EXPECTED_COMPOSE_SHA='4566f40b33bdbe7e17f0dcaef93abaeef74bfc99ba28f1b84bb3d9a414273f20'
# Round-3.4.1 architect P1 #5: sleep-gap tolerance is fixed too — an
# operator override would give a lax gate.
readonly SLEEP_GAP_TOLERANCE_SECS=30

# Round-3.2 P0 #1 pattern: fixed 900s probe interval, hard floors on
# iteration + wall-clock. Operator can raise floors, never lower.
if [[ -n "${PROBE_INTERVAL_SECS:-}" && "${PROBE_INTERVAL_SECS}" != '900' ]]; then
    printf 'FATAL: PROBE_INTERVAL_SECS cannot be overridden (fixed at 900s for 2h Mac-local witness)\n' >&2
    exit 9
fi
readonly PROBE_INTERVAL_SECS=900

: "${TOTAL_ITERATIONS:=8}"
: "${OBSERVATION_MIN_SECS:=7200}"
: "${RECREATE_DEADLINE_SECS:=90}"
if ! [[ "$TOTAL_ITERATIONS" =~ ^[0-9]+$ ]] || (( TOTAL_ITERATIONS < 8 )); then
    printf 'FATAL: TOTAL_ITERATIONS must be >= 8, got %s\n' "$TOTAL_ITERATIONS" >&2
    exit 9
fi
if ! [[ "$OBSERVATION_MIN_SECS" =~ ^[0-9]+$ ]] || (( OBSERVATION_MIN_SECS < 7200 )); then
    printf 'FATAL: OBSERVATION_MIN_SECS must be >= 7200, got %s\n' "$OBSERVATION_MIN_SECS" >&2
    exit 9
fi

# Round-3.2 P0 #3 pattern: strict compose-project regex.
if [[ ! "$COMPOSE_PROJECT_NAME" =~ ^pr1b-mac-[0-9]{8}t[0-9]{6}z$ ]]; then
    printf 'FATAL: COMPOSE_PROJECT_NAME=%s does not match required pattern pr1b-mac-<utc-lowercase>\n' \
        "$COMPOSE_PROJECT_NAME" >&2
    printf 'Suggested: pr1b-mac-$(date -u +%%Y%%m%%dt%%H%%M%%Sz)\n' >&2
    exit 9
fi

# Round-3.4 P0: refuse ANY name overlap with production.
CANDIDATE_VOLUME="${COMPOSE_PROJECT_NAME}_phantom-reports"
if [[ "$CANDIDATE_VOLUME" == 'deploy_phantom-reports' ]]; then
    printf 'FATAL: candidate volume collides with production. Refusing.\n' >&2
    exit 9
fi
if docker volume ls --format '{{.Name}}' | grep -Fxq -- 'deploy_phantom-reports'; then
    printf 'FATAL: production volume `deploy_phantom-reports` is present on this Docker daemon.\n' >&2
    printf 'The Mac-local witness refuses to run on a daemon that also carries the production volume.\n' >&2
    exit 9
fi
if docker ps -a --format '{{.Names}}' | grep -Fxq -- 'phantom-relay'; then
    printf 'FATAL: production container name `phantom-relay` is present on this daemon.\n' >&2
    exit 9
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
    printf 'FATAL: COMPOSE_FILE=%s does not exist\n' "$COMPOSE_FILE" >&2
    exit 9
fi
if [[ ! -d "$COMPOSE_PROJECT_DIR" ]]; then
    printf 'FATAL: COMPOSE_PROJECT_DIR=%s does not exist\n' "$COMPOSE_PROJECT_DIR" >&2
    exit 9
fi

# Round-3.4.1 architect P1 #3: compose file content-addressed by SHA-256.
ACTUAL_COMPOSE_SHA="$("${SHA256_CMD[@]}" "$COMPOSE_FILE" | awk '{print $1}')"
if [[ "$ACTUAL_COMPOSE_SHA" != "$EXPECTED_COMPOSE_SHA" ]]; then
    printf 'FATAL: COMPOSE_FILE SHA-256 mismatch\n' >&2
    printf '  file     = %s\n' "$COMPOSE_FILE" >&2
    printf '  actual   = %s\n' "$ACTUAL_COMPOSE_SHA" >&2
    printf '  expected = %s\n' "$EXPECTED_COMPOSE_SHA" >&2
    exit 9
fi

if [[ ! "$WITNESS_IDENTITY_HEX" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'FATAL: WITNESS_IDENTITY_HEX must match ^[0-9a-f]{64}$\n' >&2
    exit 9
fi

# ── §3: witness key + secrets directory hardening (round-3.4.1 P1 #4) ────────
#
# Both `WITNESS_KEY_PATH` and `FIXTURE_SECRETS_DIR` receive a full
# hardening pass BEFORE any file operation:
#   * absolute path required
#   * `_canonical` resolves it — refuse if it lands on `/`, `$HOME`,
#     `/tmp`, `/var/tmp`, or `/private/tmp` (macOS canonical form)
#   * the directory itself must NOT be a symlink; must be a directory
#     when it exists; its owner UID must equal current uid
#   * only THEN we chmod 700; never on an unowned or symlinked path.

_reject_wide_dir() {
    local canonical="$1" label="$2"
    case "$canonical" in
        /|"$HOME"|/tmp|/var/tmp|/private/tmp)
            printf 'FATAL: %s canonical=%s is too wide (top-level or shared dir)\n' \
                "$label" "$canonical" >&2
            exit 9
            ;;
    esac
}

_harden_dir() {
    local path="$1" label="$2"
    case "$path" in
        /*) : ;;
        *)  printf 'FATAL: %s must be absolute, got: %s\n' "$label" "$path" >&2; exit 9 ;;
    esac
    if [[ -L "$path" ]]; then
        printf 'FATAL: %s is a symlink; refusing\n' "$label" >&2
        exit 9
    fi
    if [[ -e "$path" && ! -d "$path" ]]; then
        printf 'FATAL: %s exists but is not a directory\n' "$label" >&2
        exit 9
    fi
    if [[ ! -e "$path" ]]; then
        # Fresh dir: create with restrictive perms in one syscall so
        # there is no race window under 0755.
        mkdir -m 700 -p "$path"
    else
        local owner
        owner="$(_stat_owner_uid "$path")"
        if [[ "$owner" != "$(id -u)" ]]; then
            printf 'FATAL: %s owner uid=%s != current uid=%s\n' \
                "$label" "$owner" "$(id -u)" >&2
            exit 9
        fi
    fi
    # Canonical path check for "too wide" targets AFTER we know the
    # dir exists.
    local canonical
    canonical="$(_canonical "$path")"
    _reject_wide_dir "$canonical" "$label"
    chmod 700 "$path"
}

_harden_secret_file() {
    local path="$1" label="$2"
    if [[ -L "$path" ]]; then
        printf 'FATAL: %s is a symlink; refusing\n' "$label" >&2
        exit 9
    fi
    if [[ ! -e "$path" ]]; then return 0; fi
    if [[ ! -f "$path" ]]; then
        printf 'FATAL: %s exists but is not a regular file\n' "$label" >&2
        exit 9
    fi
    local owner mode
    owner="$(_stat_owner_uid "$path")"
    if [[ "$owner" != "$(id -u)" ]]; then
        printf 'FATAL: %s owner uid=%s != current uid=%s\n' \
            "$label" "$owner" "$(id -u)" >&2
        exit 9
    fi
    mode="$(_stat_mode "$path")"
    if [[ "$mode" != "600" && "$mode" != "0600" ]]; then
        chmod 600 "$path"
    fi
}

# Witness key parent-dir hardening.
case "$WITNESS_KEY_PATH" in
    /*) : ;;
    *) printf 'FATAL: WITNESS_KEY_PATH must be absolute, got: %s\n' "$WITNESS_KEY_PATH" >&2; exit 9 ;;
esac
if [[ -L "$WITNESS_KEY_PATH" ]]; then
    printf 'FATAL: WITNESS_KEY_PATH is a symlink; refusing\n' >&2
    exit 9
fi
WITNESS_KEY_DIR="$(dirname "$WITNESS_KEY_PATH")"
_harden_dir "$WITNESS_KEY_DIR" 'WITNESS_KEY_DIR'

# ── §4: fixture secrets DIR harden (round-3.4.2 P0: reject wide paths). ──────
#
# Round-3.4.2 architect P0 #1: only HARDEN the directory (mkdir 0700 +
# ownership + wide-path reject) here. Files are NOT generated yet —
# containment vs $EVIDENCE cannot be verified until $EVIDENCE has been
# created and canonicalised, and generating secrets before that check
# would land them inside evidence if the operator mistakenly set
# FIXTURE_SECRETS_DIR=$EVIDENCE/secrets.

_harden_dir "$FIXTURE_SECRETS_DIR" 'FIXTURE_SECRETS_DIR'

SEQ_MAC_KEY_FILE="$FIXTURE_SECRETS_DIR/seq-mac.hex"
ADMIN_TOKEN_FILE="$FIXTURE_SECRETS_DIR/admin-token.txt"

# Pre-generation regular-file / owner / mode check on any files that
# happen to already exist. Content is validated below AFTER
# containment.
_harden_secret_file "$SEQ_MAC_KEY_FILE" 'FIXTURE_SECRETS_DIR/seq-mac.hex'
_harden_secret_file "$ADMIN_TOKEN_FILE" 'FIXTURE_SECRETS_DIR/admin-token.txt'

# ── §5: evidence + logging + containment check (round-3.4.2 P0 #1) ───────────

STAMP="$(date -u +%Y%m%dt%H%M%Sz)"
EVIDENCE="${EVIDENCE_DIR:-./evidence/pr1b-mac-$STAMP}"
mkdir -p "$EVIDENCE"

CANONICAL_KEY_DIR="$(_canonical "$WITNESS_KEY_DIR")"
CANONICAL_SECRETS_DIR="$(_canonical "$FIXTURE_SECRETS_DIR")"
CANONICAL_EVIDENCE="$(_canonical "$EVIDENCE")"
CANONICAL_KEY="$CANONICAL_KEY_DIR/$(basename "$WITNESS_KEY_PATH")"

# Refuse EITHER the key OR the fixture secrets dir resolving inside
# evidence BEFORE any secret file is created. Round-3.4.2 P0 #1: the
# prior order (§4 generated files, §5 checked containment) would leak
# secrets into an evidence bundle if the operator pointed
# FIXTURE_SECRETS_DIR at a path inside $EVIDENCE.
case "$CANONICAL_KEY" in
    "$CANONICAL_EVIDENCE"/*|"$CANONICAL_EVIDENCE")
        printf 'FATAL: WITNESS_KEY_PATH resolves inside EVIDENCE. Refusing.\n' >&2
        exit 9
        ;;
esac
case "$CANONICAL_SECRETS_DIR" in
    "$CANONICAL_EVIDENCE"/*|"$CANONICAL_EVIDENCE")
        printf 'FATAL: FIXTURE_SECRETS_DIR resolves inside EVIDENCE. Refusing.\n' >&2
        exit 9
        ;;
esac

# Existing WITNESS_KEY_PATH file check (regular file + owner uid + 0600).
if [[ -e "$WITNESS_KEY_PATH" ]]; then
    if [[ ! -f "$WITNESS_KEY_PATH" ]]; then
        printf 'FATAL: WITNESS_KEY_PATH exists but is not a regular file\n' >&2
        exit 9
    fi
    key_owner_uid="$(_stat_owner_uid "$WITNESS_KEY_PATH")"
    if [[ "$key_owner_uid" != "$(id -u)" ]]; then
        printf 'FATAL: WITNESS_KEY_PATH owner uid=%s != current uid=%s\n' \
            "$key_owner_uid" "$(id -u)" >&2
        exit 9
    fi
    key_mode="$(_stat_mode "$WITNESS_KEY_PATH")"
    if [[ "$key_mode" != "600" && "$key_mode" != "0600" ]]; then
        chmod 600 "$WITNESS_KEY_PATH"
    fi
fi

LOG_FILE="$EVIDENCE/witness.log"
: > "$LOG_FILE"
VERDICT_FILE="$EVIDENCE/verdict.txt"
echo 'IN_PROGRESS' > "$VERDICT_FILE"

log() {
    local msg="$*"
    printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$msg" >>"$LOG_FILE"
    printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$msg" >&2
}

ansi_strip() { sed -E $'s/\x1b\\[[0-9;]*[mKGH]//g'; }

# ── §5b: fixture secret files GENERATION (post-containment) ─────────────────
#
# Now that we know FIXTURE_SECRETS_DIR is (a) properly hardened and
# (b) resolves OUTSIDE $EVIDENCE, we can safely generate the secret
# files if absent, re-harden them, and read them into env.

if [[ ! -e "$SEQ_MAC_KEY_FILE" ]]; then
    # `umask 077` ensures the freshly-created file starts at 0600.
    ( umask 077 && openssl rand -hex 32 > "$SEQ_MAC_KEY_FILE" )
fi
if [[ ! -e "$ADMIN_TOKEN_FILE" ]]; then
    ( umask 077 && openssl rand -hex 32 > "$ADMIN_TOKEN_FILE" )
fi
_harden_secret_file "$SEQ_MAC_KEY_FILE" 'FIXTURE_SECRETS_DIR/seq-mac.hex'
_harden_secret_file "$ADMIN_TOKEN_FILE" 'FIXTURE_SECRETS_DIR/admin-token.txt'

RELAY_SEQ_MAC_KEY="$(cat "$SEQ_MAC_KEY_FILE")"
RELAY_ADMIN_TOKEN="$(cat "$ADMIN_TOKEN_FILE")"
if [[ ! "$RELAY_SEQ_MAC_KEY" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'FATAL: seq-mac.hex content does not match ^[0-9a-f]{64}$\n' >&2
    exit 9
fi
if [[ ! "$RELAY_ADMIN_TOKEN" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'FATAL: admin-token.txt content does not match ^[0-9a-f]{64}$\n' >&2
    exit 9
fi
export RELAY_SEQ_MAC_KEY RELAY_ADMIN_TOKEN

# ── §6: cleanup trap FIRST, caffeinate second (round-3.4.1 P1 #6) ────────────
#
# Trap install BEFORE spawning caffeinate guarantees that any exit
# from this script — including from a config error inside §7-§9 — kills
# caffeinate. Round-3.4 draft installed trap AFTER caffeinate, so an
# early exit between the two would leak the background process.

CAFFEINATE_PID=''
CHECKSUM_STATUS=0

finalise_evidence_and_exit() {
    local rc=$?
    log "cleanup (rc=$rc): tearing down isolated stack"

    if [[ -n "$CAFFEINATE_PID" ]] && kill -0 "$CAFFEINATE_PID" 2>/dev/null; then
        kill "$CAFFEINATE_PID" 2>/dev/null || true
    fi
    printf '%s\n' "$(date -u +%s)" > "$EVIDENCE/caffeinate.end"

    # Down the exact project only. Never `docker system prune` etc.
    docker compose --project-directory "$COMPOSE_PROJECT_DIR" \
                   -f "$COMPOSE_FILE" \
                   -p "$COMPOSE_PROJECT_NAME" \
                   down -v --remove-orphans >>"$LOG_FILE" 2>&1 || true

    # Assert no residuals with our project's names. Any leftover is a
    # cleanup failure (upgraded rc if the run had otherwise succeeded).
    local leftover=""
    if docker ps -a --format '{{.Names}}' \
            | grep -Fq -- "${PR1B_MAC_CONTAINER_NAME:-__unset__}" 2>/dev/null; then
        leftover+="container:${PR1B_MAC_CONTAINER_NAME} "
    fi
    if docker volume ls --format '{{.Name}}' \
            | grep -Fxq -- "${COMPOSE_PROJECT_NAME}_phantom-reports"; then
        leftover+="volume:${COMPOSE_PROJECT_NAME}_phantom-reports "
    fi
    if docker network ls --format '{{.Name}}' \
            | grep -Fxq -- "pr1b-mac-net-${PR1B_MAC_NET_SUFFIX:-__unset__}"; then
        leftover+="network:pr1b-mac-net-${PR1B_MAC_NET_SUFFIX} "
    fi
    if [[ -n "$leftover" ]]; then
        printf 'cleanup residue: %s\n' "$leftover" > "$EVIDENCE/cleanup-residue.txt"
        if (( rc == 0 )); then rc=7; fi
    fi

    log "cleanup: computing sha256sums.txt as final artefact (fail-closed)"
    if ! ( cd "$EVIDENCE" && \
           find . -type f ! -name 'sha256sums.txt' -print0 \
               | xargs -0 "${SHA256_CMD[@]}" > sha256sums.txt ); then
        CHECKSUM_STATUS=1
    fi

    if (( rc != 0 )); then
        exit "$rc"
    elif (( CHECKSUM_STATUS != 0 )); then
        printf 'FATAL: sha256sums.txt generation failed\n' >&2
        exit 5
    fi
}
trap finalise_evidence_and_exit EXIT
trap 'log "SIGINT/SIGTERM received"; exit 130' INT TERM

# Caffeinate: with trap already installed, ANY future exit from this
# script kills the background process. Round-3.4 P0 §4: `-dims`
# disables display / idle / mouse / system sleep for the caffeinate
# process's whole lifetime, which we bind to the script's own lifetime
# via the trap. PID + start recorded in evidence.
CAFFEINATE_START="$(date -u +%s)"
printf '%s\n' "$CAFFEINATE_START" > "$EVIDENCE/caffeinate.start"
caffeinate -dims &
CAFFEINATE_PID=$!
printf '%s\n' "$CAFFEINATE_PID" > "$EVIDENCE/caffeinate.pid"
log "caffeinate holding Mac awake (pid=$CAFFEINATE_PID)"

# ── §7: Host-ports attestation helper (round-3.4.1 P1 #3) ────────────────────
#
# `docker port` on a container returns "0" bytes when nothing is
# published to the host. We assert an empty return after every `up`
# AND after every `--force-recreate`. Compose file SHA-256 was already
# verified above; this defends against a container that somehow ended
# up with host publishing anyway (e.g. `docker run -p` outside compose,
# or a compose merge that inserted `ports:`).

assert_no_host_ports() {
    local cid="$1" phase="$2"
    local out rc stderr_file
    stderr_file="$EVIDENCE/host-ports-${phase}-stderr.txt"
    # Keep the command in an `if` condition so `set -e` cannot terminate
    # the script before we persist the explicit failure evidence.
    if out="$(docker port "$cid" 2>"$stderr_file")"; then
        rc=0
    else
        rc=$?
        log "FATAL: ${phase} \`docker port ${cid}\` failed with exit=${rc}"
        printf 'docker port exit=%d (fail-open guard)\n' "$rc" \
            > "$EVIDENCE/host-ports-${phase}.txt"
        echo "FAIL: ${phase} docker port command failed" > "$VERDICT_FILE"
        exit 2
    fi
    if [[ ! -s "$stderr_file" ]]; then
        rm -f "$stderr_file"
    fi
    if [[ -n "$out" ]]; then
        printf '%s\n' "$out" > "$EVIDENCE/host-ports-${phase}.txt"
        log "FATAL: ${phase} host ports published: $out"
        echo "FAIL: ${phase} host ports published" > "$VERDICT_FILE"
        exit 2
    fi
    printf '(none)\n' > "$EVIDENCE/host-ports-${phase}.txt"
}

# ── §8: relay image attestation (BEFORE `compose up`) ────────────────────────

log "attesting relay image $RELAY_IMAGE_REF"

if ! docker image inspect "$RELAY_IMAGE_REF" >/dev/null 2>&1; then
    log "FATAL: relay image $RELAY_IMAGE_REF not present locally"
    log "Build it from $EXPECTED_MASTER_SHA:"
    log "  git checkout $EXPECTED_MASTER_SHA"
    log "  cd services/relay && docker buildx build --platform linux/amd64 -t $RELAY_IMAGE_REF --load ."
    echo "FAIL: relay image not present" > "$VERDICT_FILE"
    exit 9
fi

ACTUAL_RELAY_IMAGE_ID="$(docker image inspect "$RELAY_IMAGE_REF" --format '{{.Id}}')"
printf '%s\n' "$ACTUAL_RELAY_IMAGE_ID" > "$EVIDENCE/relay-image-id.txt"
if [[ "$ACTUAL_RELAY_IMAGE_ID" != "$EXPECTED_RELAY_IMAGE_ID" ]]; then
    log "FATAL: relay image ID mismatch"
    log "  actual   = $ACTUAL_RELAY_IMAGE_ID"
    log "  expected = $EXPECTED_RELAY_IMAGE_ID"
    log "  commit   = $EXPECTED_MASTER_SHA"
    echo "FAIL: relay image ID does not match architect-supplied digest" > "$VERDICT_FILE"
    exit 2
fi
log "  relay image digest OK ($ACTUAL_RELAY_IMAGE_ID)"

RELAY_IMAGE_ARCH="$(docker image inspect "$RELAY_IMAGE_REF" --format '{{.Architecture}}')"
printf '%s\n' "$RELAY_IMAGE_ARCH" > "$EVIDENCE/relay-image-arch.txt"
if [[ "$RELAY_IMAGE_ARCH" != 'amd64' ]]; then
    log "FATAL: relay image arch = $RELAY_IMAGE_ARCH, expected amd64 (compose pins linux/amd64)"
    echo "FAIL: relay image arch mismatch" > "$VERDICT_FILE"
    exit 2
fi

# ── §9: witness image attestation ────────────────────────────────────────────

if ! docker image inspect "$WITNESS_IMAGE" >/dev/null 2>&1; then
    log "FATAL: witness image $WITNESS_IMAGE not present locally"
    exit 9
fi
WITNESS_IMAGE_ID="$(docker image inspect "$WITNESS_IMAGE" --format '{{.Id}}')"
printf '%s\n' "$WITNESS_IMAGE_ID" > "$EVIDENCE/witness-image-id.txt"

# Round-3.4.1 architect P0 #2: fail-closed compare against the
# architect-supplied EXPECTED_WITNESS_IMAGE_ID before any `docker run`.
# Pre-3.4.1 shape only checked arch, so any arbitrary amd64 image
# tagged as $WITNESS_IMAGE would silently become the test oracle.
if [[ "$WITNESS_IMAGE_ID" != "$EXPECTED_WITNESS_IMAGE_ID" ]]; then
    log "FATAL: witness image ID mismatch"
    log "  actual   = $WITNESS_IMAGE_ID"
    log "  expected = $EXPECTED_WITNESS_IMAGE_ID"
    echo "FAIL: witness image ID does not match architect-supplied digest" > "$VERDICT_FILE"
    exit 2
fi

WITNESS_IMAGE_ARCH="$(docker image inspect "$WITNESS_IMAGE" --format '{{.Architecture}}')"
if [[ "$WITNESS_IMAGE_ARCH" != "$RELAY_IMAGE_ARCH" ]]; then
    log "FATAL: witness arch $WITNESS_IMAGE_ARCH != relay arch $RELAY_IMAGE_ARCH"
    exit 2
fi

# ── §10: bring isolated stack up ─────────────────────────────────────────────

export PR1B_MAC_CONTAINER_NAME="pr1b-mac-relay-${STAMP}"
export PR1B_MAC_NET_SUFFIX="${STAMP}"

log "bringing up isolated stack: project=$COMPOSE_PROJECT_NAME container=$PR1B_MAC_CONTAINER_NAME"

docker compose --project-directory "$COMPOSE_PROJECT_DIR" \
               -f "$COMPOSE_FILE" \
               -p "$COMPOSE_PROJECT_NAME" \
               up -d relay >>"$LOG_FILE" 2>&1

UP_DEADLINE_SECS=30
waited=0
while (( waited < UP_DEADLINE_SECS )); do
    if docker inspect "$PR1B_MAC_CONTAINER_NAME" \
            --format '{{.State.Running}}' 2>/dev/null | grep -Fxq 'true'; then
        docker logs "$PR1B_MAC_CONTAINER_NAME" 2>&1 | ansi_strip > "$EVIDENCE/relay-boot.txt"
        if grep -Fq 'event="state_dir_preflight_ok"' "$EVIDENCE/relay-boot.txt"; then
            break
        fi
    fi
    sleep 1
    waited=$((waited + 1))
done
if ! grep -Fq 'event="state_dir_preflight_ok"' "$EVIDENCE/relay-boot.txt" 2>/dev/null; then
    log "FATAL: preflight_ok not observed within ${UP_DEADLINE_SECS}s"
    echo "FAIL: preflight_ok not observed" > "$VERDICT_FILE"
    exit 2
fi
if ! grep -Fq 'admin_token_set=true' "$EVIDENCE/relay-boot.txt"; then
    log "FATAL: relay booted without RELAY_SECRET_TOKEN (admin_token_set=true missing)"
    echo "FAIL: fixture admin token was not configured" > "$VERDICT_FILE"
    exit 2
fi

RELAY_CONTAINER="$PR1B_MAC_CONTAINER_NAME"
assert_no_host_ports "$RELAY_CONTAINER" 't0'

# ── §11: full attestation (labels + mount + running image + arch) ────────────

log "attesting labels + mount + running image"

POST_UP_IMAGE_ID="$(docker inspect "$RELAY_CONTAINER" --format '{{.Image}}')"
if [[ "$POST_UP_IMAGE_ID" != "$EXPECTED_RELAY_IMAGE_ID" ]]; then
    log "FATAL: running container image $POST_UP_IMAGE_ID != expected $EXPECTED_RELAY_IMAGE_ID"
    echo "FAIL: post-up image drift" > "$VERDICT_FILE"
    exit 2
fi

PROJECT_LABEL="$(docker inspect "$RELAY_CONTAINER" --format '{{index .Config.Labels "com.docker.compose.project"}}')"
SERVICE_LABEL="$(docker inspect "$RELAY_CONTAINER" --format '{{index .Config.Labels "com.docker.compose.service"}}')"
printf 'project=%s\nservice=%s\n' "$PROJECT_LABEL" "$SERVICE_LABEL" \
    > "$EVIDENCE/container-labels.txt"
if [[ "$PROJECT_LABEL" != "$COMPOSE_PROJECT_NAME" || "$SERVICE_LABEL" != 'relay' ]]; then
    log "FATAL: label mismatch project=$PROJECT_LABEL service=$SERVICE_LABEL"
    echo "FAIL: label mismatch" > "$VERDICT_FILE"
    exit 2
fi

MOUNT_ROW="$(docker inspect "$RELAY_CONTAINER" \
    --format '{{range .Mounts}}{{if eq .Destination "/var/phantom"}}{{.Name}}|{{.RW}}{{end}}{{end}}')"
printf '%s\n' "$MOUNT_ROW" > "$EVIDENCE/mount-attestation.txt"
EXPECTED_MOUNT="${COMPOSE_PROJECT_NAME}_phantom-reports"
if [[ "${MOUNT_ROW%%|*}" != "$EXPECTED_MOUNT" || "${MOUNT_ROW#*|}" != 'true' ]]; then
    log "FATAL: mount mismatch $MOUNT_ROW"
    echo "FAIL: mount source mismatch" > "$VERDICT_FILE"
    exit 2
fi
if [[ "${MOUNT_ROW%%|*}" == 'deploy_phantom-reports' ]]; then
    log "FATAL: mount source is production volume"
    exit 2
fi

# uid/gid + owner.
IDENT="$(docker exec "$RELAY_CONTAINER" id)"
printf '%s\n' "$IDENT" > "$EVIDENCE/container-id.txt"
if ! grep -q 'uid=10001(phantom)' <<<"$IDENT" \
    || ! grep -q 'gid=10001(phantom)' <<<"$IDENT"; then
    log "FATAL: container uid/gid mismatch: $IDENT"
    exit 2
fi
STATE_OWNER="$(docker exec "$RELAY_CONTAINER" stat -c '%U:%G:%a' /var/phantom)"
if [[ "$STATE_OWNER" != 'phantom:phantom:750' ]]; then
    log "FATAL: /var/phantom owner=$STATE_OWNER"
    exit 2
fi

# Baseline CID + restart-count for the run.
BASELINE_CID_HEX="$(docker inspect "$RELAY_CONTAINER" --format '{{.Id}}')"
BASELINE_RESTART_COUNT="$(docker inspect "$RELAY_CONTAINER" --format '{{.RestartCount}}')"
printf '%s\n' "$BASELINE_CID_HEX" > "$EVIDENCE/baseline-container-id.txt"
printf '%s\n' "$BASELINE_RESTART_COUNT" > "$EVIDENCE/baseline-restart-count.txt"

# .lock present.
if ! docker exec "$RELAY_CONTAINER" ls -la /var/phantom/.lock \
        > "$EVIDENCE/lock-file.txt" 2>&1; then
    log "FATAL: /var/phantom/.lock missing"
    exit 2
fi

# ── §12: witness-client + pubkey ─────────────────────────────────────────────

WITNESS_KEY_INSIDE="/witness-key/$(basename "$WITNESS_KEY_PATH")"

witness_client() {
    local out_file="$1"; shift
    docker run --rm \
        --network "container:${RELAY_CONTAINER}" \
        -v "${WITNESS_KEY_DIR}:/witness-key:rw" \
        --user "$(id -u):$(id -g)" \
        "$WITNESS_IMAGE_ID" \
        --key-file "$WITNESS_KEY_INSIDE" \
        "$@" \
        > "$out_file" 2>&1
}

if ! witness_client "$EVIDENCE/witness-pubkey.jsonl" pubkey-hex; then
    log "FATAL: witness-client pubkey-hex failed"
    exit 2
fi

# Re-verify the key file (round-3.2 P2 #5a).
if [[ -e "$WITNESS_KEY_PATH" ]]; then
    key_owner_uid_post="$(_stat_owner_uid "$WITNESS_KEY_PATH")"
    if [[ "$key_owner_uid_post" != "$(id -u)" ]]; then
        log "FATAL: post-pubkey key owner mismatch"
        exit 2
    fi
    key_mode_post="$(_stat_mode "$WITNESS_KEY_PATH")"
    if [[ "$key_mode_post" != "600" && "$key_mode_post" != "0600" ]]; then
        chmod 600 "$WITNESS_KEY_PATH"
    fi
fi

# ── §13: portable anomaly grep + growth-check probe ──────────────────────────

grep_anomalies() {
    local file="$1"
    if grep -qF -e 'panic' -e 'FATAL:' -e 'permission denied' \
                -e 'state.persist.fail' \
                -e 'event="audit_persist_failed"' \
                -e 'event="prekey_persist_failed"' \
                -e 'event="state_load_torn_lines"' \
                -e 'SIGSEGV' -e 'out of memory' -e 'OOM' \
                "$file" 2>/dev/null; then
        return 0
    fi
    if grep -qwF -e 'ERROR' "$file" 2>/dev/null; then return 0; fi
    if grep -qE 'HTTP 5[0-9][0-9]' "$file" 2>/dev/null; then return 0; fi
    return 1
}

STATE_FILES=(prekeys.jsonl reports.jsonl blocklist.txt push_tokens.jsonl)

snapshot_state_sizes() {
    local f
    for f in "${STATE_FILES[@]}"; do
        local sz
        sz="$(docker exec "$RELAY_CONTAINER" \
            sh -c "test -e /var/phantom/$f && stat -c %s /var/phantom/$f || echo 0")"
        printf '%s %s\n' "$f" "$sz"
    done
}

drive_all_four_with_growth_check() {
    local phase="$1"
    local seed="$2"
    local out_dir="$3"
    mkdir -p "$out_dir"
    local before_file="$out_dir/sizes-before.txt"
    local after_file="$out_dir/sizes-after.txt"

    snapshot_state_sizes > "$before_file"

    witness_client "$out_dir/prekeys-publish.jsonl" \
        publish-prekeys \
        --base "http://127.0.0.1:${RELAY_INTERNAL_PORT}" \
        --identity-hex "$WITNESS_IDENTITY_HEX" \
        --seed "$seed" || return 1

    witness_client "$out_dir/report.jsonl" \
        report \
        --base "http://127.0.0.1:${RELAY_INTERNAL_PORT}" \
        --reporter-hex "aa$(printf '%062x' "$seed")" \
        --reported-hex "bb$(printf '%062x' "$seed")" || return 1

    witness_client "$out_dir/admin-block.jsonl" \
        admin-block \
        --base "http://127.0.0.1:${RELAY_INTERNAL_PORT}" \
        --admin-token "$RELAY_ADMIN_TOKEN" \
        --key-hex "cc$(printf '%062x' "$seed")" || return 1

    witness_client "$out_dir/push-register.jsonl" \
        push-register \
        --base "http://127.0.0.1:${RELAY_INTERNAL_PORT}" \
        --identity-hex "ee$(printf '%062x' "$seed")" \
        --topic-url "https://ntfy.phntm.pro/pr1b-mac/${phase}/${seed}" || return 1

    snapshot_state_sizes > "$after_file"

    local f before after
    for f in "${STATE_FILES[@]}"; do
        before="$(awk -v k="$f" '$1==k{print $2; exit}' "$before_file")"
        after="$(awk -v k="$f" '$1==k{print $2; exit}' "$after_file")"
        if [[ -z "$before" || -z "$after" ]]; then
            log "FATAL: $phase size snapshot missing $f"
            return 1
        fi
        if (( after <= before )); then
            log "FATAL: $phase $f did not grow (before=$before after=$after)"
            return 1
        fi
    done

    local slice_window=$(( PROBE_INTERVAL_SECS + 60 ))
    docker logs --since "${slice_window}s" "$RELAY_CONTAINER" 2>&1 \
        | ansi_strip > "$out_dir/logs-after.txt"
    if grep_anomalies "$out_dir/logs-after.txt"; then
        log "FATAL: $phase log anomaly"
        return 1
    fi
    return 0
}

# ── §14: T0 gate ─────────────────────────────────────────────────────────────

log "T0 gate: drive four handlers with growth check"
T0_OUT="$EVIDENCE/t0"
if ! drive_all_four_with_growth_check 't0' 1 "$T0_OUT"; then
    echo "FAIL: T0 gate failed" > "$VERDICT_FILE"
    exit 2
fi
SPILL="$(docker exec "$RELAY_CONTAINER" ls / 2>&1 | grep -Ei '\.jsonl|blocklist' || true)"
if [[ -n "$SPILL" ]]; then
    log "FATAL: rootfs spill: $SPILL"
    exit 2
fi
log "T0 gate PASS"

# ── §15: 2-hour observation ──────────────────────────────────────────────────

OBSERVATION_STARTED_AT="$(date -u +%s)"
OBSERVATION_STARTED_RFC3339="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '%s\n' "$OBSERVATION_STARTED_AT" > "$EVIDENCE/observation-started-at.txt"
printf '%s\n' "$OBSERVATION_STARTED_RFC3339" > "$EVIDENCE/observation-started-at-rfc3339.txt"

ANOMALY_FILE="$EVIDENCE/observation-anomalies.txt"
# Round-3.4.1 architect P1 #5: `LAST_PROBE_AT` is the timestamp AFTER
# the previous probe completed, not before. Pre-3.4.1 shape stamped it
# before the probe body ran, so the next iteration's gap check was
# `900 + duration(previous probe)` — a probe that took 30+ seconds
# (very possible under amd64 emulation on Apple Silicon) would flip a
# false FAIL on the very next iteration.
LAST_PROBE_AT="$OBSERVATION_STARTED_AT"

log "starting 2h observation: $TOTAL_ITERATIONS × ${PROBE_INTERVAL_SECS}s + floor ${OBSERVATION_MIN_SECS}s"

# Round-3.4 P0 §4: caffeinate liveness + sleep-gap detection.
assert_caffeinate_alive() {
    local iter="$1"
    if ! kill -0 "$CAFFEINATE_PID" 2>/dev/null; then
        log "FATAL: caffeinate (pid=$CAFFEINATE_PID) died before ${iter}"
        printf '%s caffeinate_died\n' "$iter" >> "$ANOMALY_FILE"
        return 1
    fi
    return 0
}

assert_sleep_gap_under() {
    local label="$1" gap="$2" limit="$3"
    if (( gap > limit )); then
        log "FATAL: sleep-gap ${label}: gap=${gap}s expected<=${limit}s"
        printf '%s sleep_gap actual=%d expected_max=%d\n' \
            "$label" "$gap" "$limit" >> "$ANOMALY_FILE"
        return 1
    fi
    return 0
}

probe_iteration() {
    local iter="$1"
    local seed="$((100 + iter))"
    local out_dir="$EVIDENCE/probes/$iter"

    # Pre-probe: caffeinate must be alive AND wall-clock gap since
    # previous probe completion must be within `PROBE_INTERVAL_SECS +
    # tolerance`. The gap tracks the SLEEP between probes because
    # `LAST_PROBE_AT` is updated at probe END below.
    if ! assert_caffeinate_alive "iter=$iter"; then return 1; fi
    local now_before
    now_before="$(date -u +%s)"
    local gap=$(( now_before - LAST_PROBE_AT ))
    local expected=$(( PROBE_INTERVAL_SECS + SLEEP_GAP_TOLERANCE_SECS ))
    if ! assert_sleep_gap_under "iter=$iter" "$gap" "$expected"; then
        return 1
    fi

    log "  probe $iter/$TOTAL_ITERATIONS (seed=$seed)"

    if ! drive_all_four_with_growth_check "probe-$iter" "$seed" "$out_dir" \
            >/dev/null 2>&1; then
        printf 'iter=%d probe FAIL\n' "$iter" >> "$ANOMALY_FILE"
        return 1
    fi

    local current_cid current_rc
    current_cid="$(docker inspect "$RELAY_CONTAINER" --format '{{.Id}}')"
    if [[ "$current_cid" != "$BASELINE_CID_HEX" ]]; then
        printf 'iter=%d container_id_drift baseline=%s current=%s\n' \
            "$iter" "${BASELINE_CID_HEX:0:12}" "${current_cid:0:12}" >> "$ANOMALY_FILE"
        return 1
    fi
    current_rc="$(docker inspect "$RELAY_CONTAINER" --format '{{.RestartCount}}')"
    if [[ "$current_rc" != "$BASELINE_RESTART_COUNT" ]]; then
        printf 'iter=%d unexpected_restart baseline=%s current=%s\n' \
            "$iter" "$BASELINE_RESTART_COUNT" "$current_rc" >> "$ANOMALY_FILE"
        return 1
    fi

    # Round-3.4.1 P1 #5: stamp AFTER the probe body completes.
    LAST_PROBE_AT="$(date -u +%s)"
    return 0
}

for iter in $(seq 1 "$TOTAL_ITERATIONS"); do
    if ! probe_iteration "$iter"; then
        log "OBSERVATION FAIL at iteration $iter"
        echo "FAIL: observation anomaly at iter $iter" > "$VERDICT_FILE"
        exit 3
    fi
    if (( iter < TOTAL_ITERATIONS )); then
        sleep "$PROBE_INTERVAL_SECS"
    fi
done

# Wall-clock floor.
NOW="$(date -u +%s)"
ELAPSED="$((NOW - OBSERVATION_STARTED_AT))"
if (( ELAPSED < OBSERVATION_MIN_SECS )); then
    REMAINING="$((OBSERVATION_MIN_SECS - ELAPSED))"
    log "wall-clock floor: sleeping additional ${REMAINING}s"
    # Round-3.4.1 architect P1 #5: bound the wall-clock sleep too — if
    # the actual elapsed exceeds REMAINING + tolerance, the Mac slept
    # despite caffeinate. Detected BEFORE any final-probe mutation.
    BEFORE_WALLCLOCK_SLEEP="$(date -u +%s)"
    sleep "$REMAINING"
    AFTER_WALLCLOCK_SLEEP="$(date -u +%s)"
    actual_wallclock_gap=$(( AFTER_WALLCLOCK_SLEEP - BEFORE_WALLCLOCK_SLEEP ))
    if ! assert_sleep_gap_under 'wall-clock-sleep' "$actual_wallclock_gap" \
            "$((REMAINING + SLEEP_GAP_TOLERANCE_SECS))"; then
        echo "FAIL: wall-clock sleep exceeded tolerance (Mac slept)" > "$VERDICT_FILE"
        exit 6
    fi
fi

# Round-3.4.1 architect P1 #5: caffeinate + sleep-gap check BEFORE the
# final probe mutates disk/RAM, not after. If Mac slept during the
# wall-clock sleep and cocktail-hid the fact, we refuse to run the
# probe rather than legitimise a compromised observation window.
if ! assert_caffeinate_alive 'pre-final'; then
    echo "FAIL: caffeinate died before final probe" > "$VERDICT_FILE"
    exit 6
fi

# Final active probe.
log "final active probe"
if ! drive_all_four_with_growth_check 'final' 999998 "$EVIDENCE/final-probe"; then
    echo "FAIL: final probe failed" > "$VERDICT_FILE"
    exit 3
fi
LAST_PROBE_AT="$(date -u +%s)"
if ! assert_caffeinate_alive 'post-final'; then
    echo "FAIL: caffeinate died during final probe" > "$VERDICT_FILE"
    exit 6
fi

# Round-3.4.2 architect P1 #3: pin container ID + RestartCount at the
# TAIL of the observation window (post-final-probe, pre-scan). The
# per-iteration pin only checks each probe; a silent replace or crash
# during the wall-clock sleep OR the final probe body itself would
# otherwise slip through until the recreate step. Both values recorded
# in evidence for the reviewer.
FINAL_CID_HEX="$(docker inspect "$RELAY_CONTAINER" --format '{{.Id}}')"
FINAL_RESTART_COUNT="$(docker inspect "$RELAY_CONTAINER" --format '{{.RestartCount}}')"
printf '%s\n' "$FINAL_CID_HEX" > "$EVIDENCE/final-container-id.txt"
printf '%s\n' "$FINAL_RESTART_COUNT" > "$EVIDENCE/final-restart-count.txt"
if [[ "$FINAL_CID_HEX" != "$BASELINE_CID_HEX" ]]; then
    log "FATAL: post-final container ID drift baseline=${BASELINE_CID_HEX:0:12} final=${FINAL_CID_HEX:0:12}"
    echo "FAIL: post-final container ID drift (silent replace)" > "$VERDICT_FILE"
    exit 3
fi
if [[ "$FINAL_RESTART_COUNT" != "$BASELINE_RESTART_COUNT" ]]; then
    log "FATAL: post-final RestartCount drift baseline=$BASELINE_RESTART_COUNT final=$FINAL_RESTART_COUNT"
    echo "FAIL: post-final RestartCount drift" > "$VERDICT_FILE"
    exit 3
fi

# Full-window scan.
log "full-window log scan"
docker logs --since "$OBSERVATION_STARTED_RFC3339" "$RELAY_CONTAINER" 2>&1 \
    | ansi_strip > "$EVIDENCE/relay-logs-full-observation-window.txt"
if grep_anomalies "$EVIDENCE/relay-logs-full-observation-window.txt"; then
    log "FATAL: anomaly in full observation window"
    echo "FAIL: anomaly in full observation window" > "$VERDICT_FILE"
    exit 3
fi
printf '%s\n' "$(date -u +%s)" > "$EVIDENCE/observation-ended-at.txt"

# ── §16: planned force-recreate + post-recreate re-attest ────────────────────

PRE_RECREATE_CID_HEX="$BASELINE_CID_HEX"

log "planned force-recreate"
docker compose --project-directory "$COMPOSE_PROJECT_DIR" \
               -f "$COMPOSE_FILE" \
               -p "$COMPOSE_PROJECT_NAME" \
               up -d --force-recreate relay >>"$LOG_FILE" 2>&1

waited=0
recreate_ok=0
while (( waited < RECREATE_DEADLINE_SECS )); do
    is_running="$(docker inspect "$RELAY_CONTAINER" \
        --format '{{.State.Running}}' 2>/dev/null || echo 'gone')"
    if [[ "$is_running" == 'true' ]]; then
        docker logs --since 5m "$RELAY_CONTAINER" 2>&1 \
            | ansi_strip > "$EVIDENCE/relay-logs-after-recreate.txt"
        if grep -Fq 'event="state_dir_preflight_ok"' \
                "$EVIDENCE/relay-logs-after-recreate.txt"; then
            recreate_ok=1
            break
        fi
    fi
    sleep 2
    waited=$((waited + 2))
done
if (( recreate_ok == 0 )); then
    log "FATAL: post-recreate preflight_ok not observed"
    echo "FAIL: post-recreate liveness" > "$VERDICT_FILE"
    exit 4
fi
if ! grep -Fq 'admin_token_set=true' "$EVIDENCE/relay-logs-after-recreate.txt"; then
    log "FATAL: post-recreate relay booted without RELAY_SECRET_TOKEN"
    echo "FAIL: post-recreate fixture admin token missing" > "$VERDICT_FILE"
    exit 4
fi

# Full re-attestation chain.
NEW_IMAGE_ID="$(docker inspect "$RELAY_CONTAINER" --format '{{.Image}}')"
if [[ "$NEW_IMAGE_ID" != "$EXPECTED_RELAY_IMAGE_ID" ]]; then
    log "FATAL: post-recreate image drift $NEW_IMAGE_ID"
    exit 4
fi
NEW_PROJECT="$(docker inspect "$RELAY_CONTAINER" --format '{{index .Config.Labels "com.docker.compose.project"}}')"
NEW_SERVICE="$(docker inspect "$RELAY_CONTAINER" --format '{{index .Config.Labels "com.docker.compose.service"}}')"
if [[ "$NEW_PROJECT" != "$COMPOSE_PROJECT_NAME" || "$NEW_SERVICE" != 'relay' ]]; then
    log "FATAL: post-recreate label drift"
    exit 4
fi
NEW_IDENT="$(docker exec "$RELAY_CONTAINER" id)"
if ! grep -q 'uid=10001(phantom)' <<<"$NEW_IDENT" \
    || ! grep -q 'gid=10001(phantom)' <<<"$NEW_IDENT"; then
    log "FATAL: post-recreate identity drift $NEW_IDENT"
    exit 4
fi
NEW_STATE_OWNER="$(docker exec "$RELAY_CONTAINER" stat -c '%U:%G:%a' /var/phantom)"
if [[ "$NEW_STATE_OWNER" != 'phantom:phantom:750' ]]; then
    log "FATAL: post-recreate owner=$NEW_STATE_OWNER"
    exit 4
fi
NEW_MOUNT_ROW="$(docker inspect "$RELAY_CONTAINER" \
    --format '{{range .Mounts}}{{if eq .Destination "/var/phantom"}}{{.Name}}|{{.RW}}{{end}}{{end}}')"
if [[ "${NEW_MOUNT_ROW%%|*}" != "$EXPECTED_MOUNT" || "${NEW_MOUNT_ROW#*|}" != 'true' ]]; then
    log "FATAL: post-recreate mount drift $NEW_MOUNT_ROW"
    exit 4
fi
if ! docker exec "$RELAY_CONTAINER" ls -la /var/phantom/.lock \
        > "$EVIDENCE/lock-file-post-recreate.txt" 2>&1; then
    log "FATAL: post-recreate .lock missing"
    exit 4
fi

BASELINE_CID_HEX="$(docker inspect "$RELAY_CONTAINER" --format '{{.Id}}')"
if [[ "$BASELINE_CID_HEX" == "$PRE_RECREATE_CID_HEX" ]]; then
    log "FATAL: post-recreate CID unchanged (force-recreate had no effect)"
    exit 4
fi
printf '%s\n' "$BASELINE_CID_HEX" > "$EVIDENCE/baseline-container-id-post-recreate.txt"

# Round-3.4.1 architect P0 #1 (post-recreate half): a freshly-recreated
# container starts with RestartCount=0. Any non-zero value would mean
# the recreated container has already crashed and been restarted by
# something outside our control.
POST_RECREATE_RC="$(docker inspect "$RELAY_CONTAINER" --format '{{.RestartCount}}')"
if [[ "$POST_RECREATE_RC" != '0' ]]; then
    log "FATAL: post-recreate RestartCount=$POST_RECREATE_RC, expected 0"
    echo "FAIL: post-recreate RestartCount=$POST_RECREATE_RC" > "$VERDICT_FILE"
    exit 4
fi
BASELINE_RESTART_COUNT="$POST_RECREATE_RC"

# Round-3.4.1 architect P1 #3: re-attest no host ports after recreate.
assert_no_host_ports "$RELAY_CONTAINER" 'post-recreate'

for f in "${STATE_FILES[@]}"; do
    if ! docker exec "$RELAY_CONTAINER" test -s "/var/phantom/$f"; then
        log "FATAL: post-recreate /var/phantom/$f empty — replay broken"
        exit 4
    fi
done

if ! drive_all_four_with_growth_check 'post-recreate' 999 "$EVIDENCE/post-recreate"; then
    echo "FAIL: post-recreate handler drive failed" > "$VERDICT_FILE"
    exit 4
fi

echo 'PASS: T0 gate + 2h observation + wall-clock floor + planned recreate + post-recreate re-attest all clean' \
    > "$VERDICT_FILE"
log "Mac-local witness PASS"
exit 0
