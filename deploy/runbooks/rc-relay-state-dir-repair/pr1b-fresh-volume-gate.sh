#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# PR-1b mini-lock §8 — fresh-volume gate. Round-3 revision after
# architect verdict on round-2 draft.
#
# See ./README.md §5 for full rationale and preconditions.
#
# EXIT CODES
#   0  PASS
#   2  FAIL
#   9  Configuration error (guard triggered / precondition missing).

set -Eeuo pipefail
IFS=$'\n\t'

# ── Guards + preflight ────────────────────────────────────────────────────────

require_env() {
    local name="$1"
    if [[ -z "${!name:-}" ]]; then
        printf 'FATAL: required env var %s is not set\n' "$name" >&2
        exit 9
    fi
}
require_bin() {
    local bin="$1"
    if ! command -v "$bin" >/dev/null 2>&1; then
        printf 'FATAL: required binary %s not on PATH\n' "$bin" >&2
        exit 9
    fi
}
for bin in docker sed awk grep mktemp find xargs; do
    require_bin "$bin"
done
if ! docker compose version >/dev/null 2>&1; then
    printf 'FATAL: `docker compose` (v2) required\n' >&2
    exit 9
fi

# Round-3 architect: `sha256sum` is GNU-only. On macOS it comes with
# coreutils or shows up as `/sbin/sha256sum`; BSD ships `shasum -a 256`.
# Detect once and re-use throughout.
if command -v sha256sum >/dev/null 2>&1; then
    SHA256_CMD=(sha256sum -b)
elif command -v shasum >/dev/null 2>&1; then
    SHA256_CMD=(shasum -a 256 -b)
else
    printf 'FATAL: neither `sha256sum` nor `shasum -a 256` available on PATH\n' >&2
    exit 9
fi

require_env COMPOSE_PROJECT_NAME
require_env RELAY_IMAGE
require_env EXPECTED_MASTER_SHA

# Round-3 architect P0: Compose project names must be lowercase
# `[a-z0-9][a-z0-9_-]*` per Docker docs. Round-2 pattern with uppercase
# `T`/`Z` was silently downcased by compose and did not match on
# lookup. New pattern:
if [[ ! "$COMPOSE_PROJECT_NAME" =~ ^pr1b-witness-[0-9]{8}t[0-9]{6}z$ ]]; then
    printf 'FATAL: COMPOSE_PROJECT_NAME=%s does not match required pattern pr1b-witness-<utc-lowercase>\n' \
        "$COMPOSE_PROJECT_NAME" >&2
    printf 'Docker Compose downcases project names — the stamp MUST use lowercase `t` and `z`.\n' >&2
    printf 'Suggested: pr1b-witness-$(date -u +%%Y%%m%%dt%%H%%M%%Sz)\n' >&2
    exit 9
fi

CANDIDATE_VOL="${COMPOSE_PROJECT_NAME}_phantom-reports"
if docker volume ls --format '{{.Name}}' | grep -Fxq -- "$CANDIDATE_VOL"; then
    printf 'FATAL: volume %s already exists — refusing to overwrite\n' "$CANDIDATE_VOL" >&2
    exit 9
fi
if [[ "$CANDIDATE_VOL" == 'deploy_phantom-reports' ]]; then
    printf 'FATAL: candidate volume name collides with production. Refusing.\n' >&2
    exit 9
fi

# ── Round-3 architect P1: attest image BEFORE `docker compose up`. ────────────
#
# The pre-round-3 shape brought the stack up first, then compared the
# running container's image. If the wrong image happened to be pulled
# in as part of `up`, it had already been booted — the attestation
# came after the compromise. Order corrected: verify the image tag /
# digest against `EXPECTED_MASTER_SHA` (via label) OR
# `EXPECTED_IMAGE_DIGEST` (via digest) BEFORE we spawn anything.

log() { printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }

if ! docker image inspect "$RELAY_IMAGE" >/dev/null 2>&1; then
    log "FATAL: image $RELAY_IMAGE is not present locally — pull or build it first"
    exit 9
fi

PRE_IMAGE_ID="$(docker image inspect "$RELAY_IMAGE" --format '{{.Id}}')"
PRE_IMAGE_REV="$(docker image inspect "$RELAY_IMAGE" \
    --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' 2>/dev/null || echo '')"

attest_image_ok=0
if [[ -n "$PRE_IMAGE_REV" && "$PRE_IMAGE_REV" == "$EXPECTED_MASTER_SHA" ]]; then
    log "pre-up image revision label matches EXPECTED_MASTER_SHA (${EXPECTED_MASTER_SHA:0:12})"
    attest_image_ok=1
elif [[ -n "${EXPECTED_IMAGE_DIGEST:-}" && "$PRE_IMAGE_ID" == "$EXPECTED_IMAGE_DIGEST" ]]; then
    log "pre-up image digest matches EXPECTED_IMAGE_DIGEST"
    attest_image_ok=1
fi
if (( attest_image_ok == 0 )); then
    log "FATAL: cannot attest image origin BEFORE bringing stack up"
    log "  RELAY_IMAGE       = $RELAY_IMAGE"
    log "  pre_image_id      = $PRE_IMAGE_ID"
    log "  pre_image_rev     = $PRE_IMAGE_REV"
    log "  expected_sha      = $EXPECTED_MASTER_SHA"
    log "  expected_digest   = ${EXPECTED_IMAGE_DIGEST:-<unset>}"
    exit 2
fi

# ── Evidence + logging ────────────────────────────────────────────────────────

STAMP="$(date -u +%Y%m%dt%H%M%Sz)"
EVIDENCE="${EVIDENCE_DIR:-./evidence/pr1b-fresh-volume-$STAMP}"
mkdir -p "$EVIDENCE"

LOG_FILE="$EVIDENCE/gate.log"
: > "$LOG_FILE"
VERDICT_FILE="$EVIDENCE/verdict.txt"
echo 'IN_PROGRESS' > "$VERDICT_FILE"

log() {
    local msg="$*"
    printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$msg" >>"$LOG_FILE"
    printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$msg" >&2
}

# Round-3 architect: portable ANSI strip.
ansi_strip() { sed -E $'s/\x1b\\[[0-9;]*[mKGH]//g'; }

TMPDIR_HOST="$(mktemp -d -t pr1b-fresh-volume.XXXXXX)"
COMPOSE_FILE="$TMPDIR_HOST/compose.yml"

# ── Round-3 architect P1: sha256sums.txt fail-closed ────────────────────────
#
# Pre-round-3 shape appended `|| true` on the `sha256sum` invocation,
# so a checksum-generation failure silently produced an empty
# `sha256sums.txt` and the overall exit code stayed 0. Now: on ANY
# failure of the checksum step, we bump the exit code to non-zero
# BEFORE trap exit propagates.

CHECKSUM_STATUS=0

finalise_evidence_and_exit() {
    local rc=$?
    log "cleanup (rc=$rc): tearing down isolated stack"
    docker compose --project-directory "$TMPDIR_HOST" \
                   -f "$COMPOSE_FILE" \
                   -p "$COMPOSE_PROJECT_NAME" \
                   down -v --remove-orphans \
        >>"$LOG_FILE" 2>&1 || true
    rm -rf "$TMPDIR_HOST"
    log "cleanup: computing sha256sums.txt as final artefact (fail-closed)"
    # NOTHING is logged after this point so the checksum stays authoritative.
    if ! ( cd "$EVIDENCE" && \
           find . -type f ! -name 'sha256sums.txt' -print0 \
               | xargs -0 "${SHA256_CMD[@]}" > sha256sums.txt ); then
        CHECKSUM_STATUS=1
    fi
    # If either the underlying script rc OR the checksum step failed,
    # exit non-zero. Propagate original rc when non-zero; otherwise use
    # checksum status.
    if (( rc != 0 )); then
        exit "$rc"
    elif (( CHECKSUM_STATUS != 0 )); then
        printf 'FATAL: sha256sums.txt generation failed — evidence bundle is not finalised\n' >&2
        exit 5
    fi
}
trap finalise_evidence_and_exit EXIT
trap 'log "SIGINT/SIGTERM received"; exit 130' INT TERM

# ── Compose override: minimal isolated stack ──────────────────────────────────

cat > "$COMPOSE_FILE" <<EOF
services:
  relay:
    image: ${RELAY_IMAGE}
    read_only: true
    tmpfs:
      - /tmp:size=16m
    volumes:
      - phantom-reports:/var/phantom
    environment:
      RELAY_STATE_DIR: /var/phantom
      RELAY_HOST: 127.0.0.1
      RELAY_PORT: '18080'
      RELAY_SEQ_MAC_KEY: '0000000000000000000000000000000000000000000000000000000000000000'
      RUST_LOG: 'phantom_relay=info'
      NO_COLOR: '1'
    restart: 'no'

volumes:
  phantom-reports: {}
EOF

log "compose override written to $COMPOSE_FILE"
printf '%s\n%s\n' "$PRE_IMAGE_ID" "$PRE_IMAGE_REV" > "$EVIDENCE/pre-up-image-attestation.txt"

# ── Bring up + re-attest running container ────────────────────────────────────

log "docker compose up -d (fresh stack)"
docker compose --project-directory "$TMPDIR_HOST" \
               -f "$COMPOSE_FILE" \
               -p "$COMPOSE_PROJECT_NAME" \
               up -d relay >>"$LOG_FILE" 2>&1

# Round-3.1 architect P1 #5: bounded wait for Running=true +
# state_dir_preflight_ok. Pre-round-3.1 `sleep 5` was a false-FAIL trap
# on a cold Docker Desktop.
FRESH_UP_DEADLINE_SECS="${FRESH_UP_DEADLINE_SECS:-30}"
waited=0
CID=""
while (( waited < FRESH_UP_DEADLINE_SECS )); do
    CID="$(docker compose --project-directory "$TMPDIR_HOST" \
                         -f "$COMPOSE_FILE" \
                         -p "$COMPOSE_PROJECT_NAME" \
                         ps -a -q relay 2>/dev/null || true)"
    if [[ -n "$CID" ]]; then
        running="$(docker inspect "$CID" --format '{{.State.Running}}' 2>/dev/null || echo 'gone')"
        if [[ "$running" == 'true' ]]; then
            docker logs "$CID" 2>&1 | ansi_strip > "$EVIDENCE/fresh-boot-logs.txt"
            if grep -Fq 'event="state_dir_preflight_ok"' "$EVIDENCE/fresh-boot-logs.txt"; then
                break
            fi
        fi
    fi
    sleep 1
    waited=$((waited + 1))
done
if [[ -z "$CID" ]]; then
    log "FATAL: cannot find relay container id within ${FRESH_UP_DEADLINE_SECS}s"
    echo "FAIL: no container id" > "$VERDICT_FILE"
    exit 2
fi
if ! grep -Fq 'event="state_dir_preflight_ok"' "$EVIDENCE/fresh-boot-logs.txt"; then
    log "FATAL: preflight_ok not observed within ${FRESH_UP_DEADLINE_SECS}s"
    echo "FAIL: preflight_ok not observed" > "$VERDICT_FILE"
    exit 2
fi
log "relay container id: $CID"

# Round-3 architect P1: assert the running container is actually
# running the SAME image object we pre-attested. Guards against a race
# where a competing `pull` inserted a different image tagged as
# `$RELAY_IMAGE` between our pre-attest and `compose up`.
POST_IMAGE_ID="$(docker inspect "$CID" --format '{{.Image}}')"
printf '%s\n' "$POST_IMAGE_ID" > "$EVIDENCE/post-up-image-id.txt"
if [[ "$POST_IMAGE_ID" != "$PRE_IMAGE_ID" ]]; then
    log "FATAL: running container image $POST_IMAGE_ID != pre-attested $PRE_IMAGE_ID"
    echo "FAIL: image drifted between pre-attest and up" > "$VERDICT_FILE"
    exit 2
fi

# ── Container-label + mount attestation ──────────────────────────────────────

PROJECT_LABEL="$(docker inspect "$CID" --format '{{index .Config.Labels "com.docker.compose.project"}}')"
SERVICE_LABEL="$(docker inspect "$CID" --format '{{index .Config.Labels "com.docker.compose.service"}}')"
printf 'project=%s\nservice=%s\n' "$PROJECT_LABEL" "$SERVICE_LABEL" \
    > "$EVIDENCE/container-labels.txt"

if [[ "$PROJECT_LABEL" != "$COMPOSE_PROJECT_NAME" ]]; then
    log "FATAL: container project label '$PROJECT_LABEL' != COMPOSE_PROJECT_NAME '$COMPOSE_PROJECT_NAME'"
    echo "FAIL: container project label mismatch" > "$VERDICT_FILE"
    exit 2
fi
if [[ "$SERVICE_LABEL" != 'relay' ]]; then
    log "FATAL: container service label '$SERVICE_LABEL' != 'relay'"
    echo "FAIL: container service label mismatch" > "$VERDICT_FILE"
    exit 2
fi

MOUNT_ROW="$(docker inspect "$CID" \
    --format '{{range .Mounts}}{{if eq .Destination "/var/phantom"}}{{.Name}}|{{.RW}}{{end}}{{end}}')"
printf '%s\n' "$MOUNT_ROW" > "$EVIDENCE/mount-attestation.txt"
if [[ -z "$MOUNT_ROW" ]]; then
    log "FATAL: /var/phantom mount not found"
    echo "FAIL: /var/phantom mount missing" > "$VERDICT_FILE"
    exit 2
fi
MOUNT_NAME="${MOUNT_ROW%%|*}"
MOUNT_RW="${MOUNT_ROW#*|}"
if [[ "$MOUNT_NAME" != "$CANDIDATE_VOL" ]]; then
    log "FATAL: /var/phantom mount source '$MOUNT_NAME' != expected '$CANDIDATE_VOL'"
    echo "FAIL: mount source mismatch" > "$VERDICT_FILE"
    exit 2
fi
if [[ "$MOUNT_NAME" == 'deploy_phantom-reports' ]]; then
    log "FATAL: /var/phantom mount source is the PRODUCTION volume"
    echo "FAIL: refusing to run against production volume" > "$VERDICT_FILE"
    exit 2
fi
if [[ "$MOUNT_RW" != 'true' ]]; then
    log "FATAL: /var/phantom mount is not RW (got '$MOUNT_RW')"
    echo "FAIL: /var/phantom mount RW=$MOUNT_RW" > "$VERDICT_FILE"
    exit 2
fi

# ── Assert seed-on-first-mount contract ──────────────────────────────────────

# Round-3.1 architect P1 #5: assert container identity via `docker exec id`
# too, not just directory owner.
IDENT="$(docker exec "$CID" id)"
printf '%s\n' "$IDENT" > "$EVIDENCE/container-id.txt"
if ! grep -q 'uid=10001(phantom)' <<<"$IDENT" \
    || ! grep -q 'gid=10001(phantom)' <<<"$IDENT"; then
    log "FATAL: fresh container identity drift: $IDENT"
    echo "FAIL: fresh container id: $IDENT" > "$VERDICT_FILE"
    exit 2
fi

STATE_OWNER="$(docker exec "$CID" stat -c '%U:%G:%a' /var/phantom)"
printf '%s\n' "$STATE_OWNER" > "$EVIDENCE/state-dir-owner.txt"
if [[ "$STATE_OWNER" != 'phantom:phantom:750' ]]; then
    log "FATAL: fresh-mount ownership mismatch: got '$STATE_OWNER'"
    echo "FAIL: fresh-mount /var/phantom=$STATE_OWNER, expected phantom:phantom:750" > "$VERDICT_FILE"
    exit 2
fi

# `fresh-boot-logs.txt` was captured inside the bounded-up loop above.

if ! docker exec "$CID" ls -la /var/phantom/.lock > "$EVIDENCE/fresh-lock.txt" 2>&1; then
    log "FATAL: fresh /var/phantom/.lock missing"
    echo "FAIL: fresh /var/phantom/.lock missing" > "$VERDICT_FILE"
    exit 2
fi

echo 'PASS: fresh volume seeded phantom:phantom:750 + preflight_ok + .lock present + attestation clean' \
    > "$VERDICT_FILE"
log "fresh-volume gate PASS"
exit 0
