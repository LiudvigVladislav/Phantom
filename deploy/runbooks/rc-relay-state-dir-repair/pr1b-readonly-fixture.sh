#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# PR-1b mini-lock §8 — simulated-failure (read-only volume) fixture.
# Round-3 revision after architect verdict on round-2 draft.
#
# See ./README.md §6 for full rationale.
#
# EXIT CODES
#   0  PASS — relay under `:ro` exited non-zero within window AND
#      stderr contains 'FATAL: preflight:'.
#   2  FAIL — see verdict.txt.
#   5  Evidence finalisation failed (sha256sums.txt not written).
#   9  Configuration error.

set -Eeuo pipefail
IFS=$'\n\t'

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

# Portable sha256 utility (round-3).
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

# Round-3 architect P0: lowercase project name (Docker requirement).
if [[ ! "$COMPOSE_PROJECT_NAME" =~ ^pr1b-readonly-[0-9]{8}t[0-9]{6}z$ ]]; then
    printf 'FATAL: COMPOSE_PROJECT_NAME=%s does not match required pattern pr1b-readonly-<utc-lowercase>\n' \
        "$COMPOSE_PROJECT_NAME" >&2
    printf 'Suggested: pr1b-readonly-$(date -u +%%Y%%m%%dt%%H%%M%%Sz)\n' >&2
    exit 9
fi

CANDIDATE_VOL="${COMPOSE_PROJECT_NAME}_phantom-reports"
if [[ "$CANDIDATE_VOL" == 'deploy_phantom-reports' ]]; then
    printf 'FATAL: candidate volume name collides with production. Refusing.\n' >&2
    exit 9
fi
if docker volume ls --format '{{.Name}}' | grep -Fxq -- "$CANDIDATE_VOL"; then
    printf 'FATAL: candidate volume %s already exists — refusing\n' "$CANDIDATE_VOL" >&2
    exit 9
fi

if [[ "${CI:-}" != 'true' && "${LOCAL_DEV_ACK:-}" != '1' ]]; then
    printf 'FATAL: this fixture is CI/local-only. Set CI=true or LOCAL_DEV_ACK=1.\n' >&2
    exit 9
fi

# ── Round-3 architect P1: attest image BEFORE ANY docker compose up. ─────────

log() { printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$*" >&2; }

if ! docker image inspect "$RELAY_IMAGE" >/dev/null 2>&1; then
    log "FATAL: image $RELAY_IMAGE not present locally"
    exit 9
fi

PRE_IMAGE_ID="$(docker image inspect "$RELAY_IMAGE" --format '{{.Id}}')"
PRE_IMAGE_REV="$(docker image inspect "$RELAY_IMAGE" \
    --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' 2>/dev/null || echo '')"

attest_image_ok=0
if [[ -n "$PRE_IMAGE_REV" && "$PRE_IMAGE_REV" == "$EXPECTED_MASTER_SHA" ]]; then
    log "pre-up image revision label matches EXPECTED_MASTER_SHA"
    attest_image_ok=1
elif [[ -n "${EXPECTED_IMAGE_DIGEST:-}" && "$PRE_IMAGE_ID" == "$EXPECTED_IMAGE_DIGEST" ]]; then
    log "pre-up image digest matches EXPECTED_IMAGE_DIGEST"
    attest_image_ok=1
fi
if (( attest_image_ok == 0 )); then
    log "FATAL: cannot attest image origin BEFORE any bring-up"
    log "  RELAY_IMAGE     = $RELAY_IMAGE"
    log "  pre_image_id    = $PRE_IMAGE_ID"
    log "  pre_image_rev   = $PRE_IMAGE_REV"
    log "  expected_sha    = $EXPECTED_MASTER_SHA"
    log "  expected_digest = ${EXPECTED_IMAGE_DIGEST:-<unset>}"
    exit 2
fi

# ── Evidence + logging ────────────────────────────────────────────────────────

STAMP="$(date -u +%Y%m%dt%H%M%Sz)"
EVIDENCE="${EVIDENCE_DIR:-./evidence/pr1b-readonly-$STAMP}"
mkdir -p "$EVIDENCE"

LOG_FILE="$EVIDENCE/fixture.log"
: > "$LOG_FILE"
VERDICT_FILE="$EVIDENCE/verdict.txt"
echo 'IN_PROGRESS' > "$VERDICT_FILE"
printf '%s\n%s\n' "$PRE_IMAGE_ID" "$PRE_IMAGE_REV" > "$EVIDENCE/pre-up-image-attestation.txt"

log() {
    local msg="$*"
    printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$msg" >>"$LOG_FILE"
    printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$msg" >&2
}

ansi_strip() { sed -E $'s/\x1b\\[[0-9;]*[mKGH]//g'; }

TMPDIR_HOST="$(mktemp -d -t pr1b-readonly.XXXXXX)"
COMPOSE_FILE="$TMPDIR_HOST/compose.yml"

CHECKSUM_STATUS=0

finalise_evidence_and_exit() {
    local rc=$?
    log "cleanup (rc=$rc): tearing down"
    docker compose --project-directory "$TMPDIR_HOST" \
                   -f "$COMPOSE_FILE" \
                   -p "$COMPOSE_PROJECT_NAME" \
                   down -v --remove-orphans >>"$LOG_FILE" 2>&1 || true
    rm -rf "$TMPDIR_HOST"
    log "cleanup: computing sha256sums.txt as final artefact (fail-closed)"
    if ! ( cd "$EVIDENCE" && \
           find . -type f ! -name 'sha256sums.txt' -print0 \
               | xargs -0 "${SHA256_CMD[@]}" > sha256sums.txt ); then
        CHECKSUM_STATUS=1
    fi
    if (( rc != 0 )); then
        exit "$rc"
    elif (( CHECKSUM_STATUS != 0 )); then
        printf 'FATAL: sha256sums.txt generation failed — evidence bundle is not finalised\n' >&2
        exit 5
    fi
}
trap finalise_evidence_and_exit EXIT
trap 'log "SIGINT/SIGTERM received"; exit 130' INT TERM

# ── Post-up attestation helpers ────────────────────────────────────────────
#
# Round-3.1 architect P1 #4: pre-round-3.1 shape only compared the image
# ID. This left `FATAL: preflight:` potentially caused by ANY mount, not
# specifically the `:ro` one we designed the fixture around. Now: image
# ID + Compose labels + mount source + mount RW flag, per phase.

assert_running_image_matches_preattested() {
    local cid="$1"
    local phase="$2"
    local post_image_id
    post_image_id="$(docker inspect "$cid" --format '{{.Image}}')"
    printf '%s\n' "$post_image_id" > "$EVIDENCE/post-up-image-id-${phase}.txt"
    if [[ "$post_image_id" != "$PRE_IMAGE_ID" ]]; then
        log "FATAL: ${phase} running container image $post_image_id != pre-attested $PRE_IMAGE_ID"
        echo "FAIL: ${phase} image drift" > "$VERDICT_FILE"
        exit 2
    fi
}

assert_labels_and_mount() {
    local cid="$1"
    local phase="$2"
    local expected_rw="$3"

    local project service mount_row mount_name mount_rw
    project="$(docker inspect "$cid" --format '{{index .Config.Labels "com.docker.compose.project"}}')"
    service="$(docker inspect "$cid" --format '{{index .Config.Labels "com.docker.compose.service"}}')"
    mount_row="$(docker inspect "$cid" \
        --format '{{range .Mounts}}{{if eq .Destination "/var/phantom"}}{{.Name}}|{{.RW}}{{end}}{{end}}')"
    printf 'project=%s\nservice=%s\nmount=%s\n' \
        "$project" "$service" "$mount_row" \
        > "$EVIDENCE/attestation-${phase}.txt"

    if [[ "$project" != "$COMPOSE_PROJECT_NAME" ]]; then
        log "FATAL: ${phase} project label '$project' != COMPOSE_PROJECT_NAME '$COMPOSE_PROJECT_NAME'"
        echo "FAIL: ${phase} project label mismatch" > "$VERDICT_FILE"
        exit 2
    fi
    if [[ "$service" != 'relay' ]]; then
        log "FATAL: ${phase} service label '$service' != 'relay'"
        echo "FAIL: ${phase} service label mismatch" > "$VERDICT_FILE"
        exit 2
    fi
    if [[ -z "$mount_row" ]]; then
        log "FATAL: ${phase} /var/phantom mount not found"
        echo "FAIL: ${phase} /var/phantom mount missing" > "$VERDICT_FILE"
        exit 2
    fi
    mount_name="${mount_row%%|*}"
    mount_rw="${mount_row#*|}"
    if [[ "$mount_name" != "$CANDIDATE_VOL" ]]; then
        log "FATAL: ${phase} /var/phantom source '$mount_name' != expected '$CANDIDATE_VOL'"
        echo "FAIL: ${phase} mount source mismatch" > "$VERDICT_FILE"
        exit 2
    fi
    if [[ "$mount_rw" != "$expected_rw" ]]; then
        log "FATAL: ${phase} /var/phantom RW=$mount_rw, expected $expected_rw"
        echo "FAIL: ${phase} RW expected $expected_rw, got $mount_rw" > "$VERDICT_FILE"
        exit 2
    fi
    log "  ${phase} attestation: project=$project service=$service mount=$mount_name RW=$mount_rw"
}

# ── Phase 1: seed-writable (bounded until preflight_ok) ─────────────────────

log "phase 1: writable seed"

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

docker compose --project-directory "$TMPDIR_HOST" \
               -f "$COMPOSE_FILE" \
               -p "$COMPOSE_PROJECT_NAME" \
               up -d relay >>"$LOG_FILE" 2>&1

SEED_DEADLINE_SECS="${SEED_DEADLINE_SECS:-15}"
SEED_CID="$(docker compose --project-directory "$TMPDIR_HOST" \
                          -f "$COMPOSE_FILE" \
                          -p "$COMPOSE_PROJECT_NAME" \
                          ps -a -q relay)"
if [[ -z "$SEED_CID" ]]; then
    log "FATAL: seed phase: cannot find container id"
    echo "FAIL: seed phase no container" > "$VERDICT_FILE"
    exit 2
fi
assert_running_image_matches_preattested "$SEED_CID" 'seed'
assert_labels_and_mount "$SEED_CID" 'seed' 'true'

waited=0
seed_ok=0
while (( waited < SEED_DEADLINE_SECS )); do
    if docker logs "$SEED_CID" 2>&1 | ansi_strip \
            | grep -Fq 'event="state_dir_preflight_ok"'; then
        seed_ok=1
        break
    fi
    sleep 1
    waited=$((waited + 1))
done
if (( seed_ok == 0 )); then
    log "FATAL: seed phase: preflight_ok not observed within ${SEED_DEADLINE_SECS}s"
    docker logs "$SEED_CID" 2>&1 | ansi_strip > "$EVIDENCE/seed-boot-logs.txt"
    echo "FAIL: seed phase did not confirm preflight_ok" > "$VERDICT_FILE"
    exit 2
fi
log "phase 1: preflight_ok observed after ${waited}s — bringing seed stack down"

docker compose --project-directory "$TMPDIR_HOST" \
               -f "$COMPOSE_FILE" \
               -p "$COMPOSE_PROJECT_NAME" \
               stop relay >>"$LOG_FILE" 2>&1
docker compose --project-directory "$TMPDIR_HOST" \
               -f "$COMPOSE_FILE" \
               -p "$COMPOSE_PROJECT_NAME" \
               rm -f relay >>"$LOG_FILE" 2>&1

# ── Phase 2: `:ro` mount, assert FATAL + non-zero exit ─────────────────────

log "phase 2: re-mount :ro; expect FATAL preflight and non-zero exit"

cat > "$COMPOSE_FILE" <<EOF
services:
  relay:
    image: ${RELAY_IMAGE}
    read_only: true
    tmpfs:
      - /tmp:size=16m
    volumes:
      - type: volume
        source: phantom-reports
        target: /var/phantom
        read_only: true
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

docker compose --project-directory "$TMPDIR_HOST" \
               -f "$COMPOSE_FILE" \
               -p "$COMPOSE_PROJECT_NAME" \
               up -d relay >>"$LOG_FILE" 2>&1 || true

DEADLINE_SECS="${READONLY_DEADLINE_SECS:-5}"

sleep 1
CID="$(docker compose --project-directory "$TMPDIR_HOST" \
                     -f "$COMPOSE_FILE" \
                     -p "$COMPOSE_PROJECT_NAME" \
                     ps -a -q relay)"
if [[ -z "$CID" ]]; then
    log "FATAL: readonly phase: cannot find container id even with ps -a"
    echo "FAIL: readonly phase no container" > "$VERDICT_FILE"
    exit 2
fi
log ":ro-phase container id: $CID"
assert_running_image_matches_preattested "$CID" 'readonly'
assert_labels_and_mount "$CID" 'readonly' 'false'

log "waiting up to ${DEADLINE_SECS}s for :ro container to exit"

waited=0
running=1
while (( waited < DEADLINE_SECS )); do
    is_running="$(docker inspect "$CID" --format '{{.State.Running}}' 2>/dev/null || echo 'gone')"
    if [[ "$is_running" != 'true' ]]; then
        running=0
        break
    fi
    sleep 1
    waited=$((waited + 1))
done

if (( running == 1 )); then
    log "FATAL: :ro container still running after ${DEADLINE_SECS}s"
    echo "FAIL: :ro container did not exit within ${DEADLINE_SECS}s (preflight regressed?)" > "$VERDICT_FILE"
    exit 2
fi

EXIT_CODE_RAW="$(docker inspect "$CID" --format '{{.State.ExitCode}}' 2>/dev/null || echo '')"
printf '%s\n' "$EXIT_CODE_RAW" > "$EVIDENCE/exit-code.txt"
if [[ ! "$EXIT_CODE_RAW" =~ ^-?[0-9]+$ ]]; then
    log "FATAL: :ro container ExitCode is not a number: '$EXIT_CODE_RAW'"
    echo "FAIL: non-numeric ExitCode from docker inspect: $EXIT_CODE_RAW" > "$VERDICT_FILE"
    exit 2
fi
if (( EXIT_CODE_RAW <= 0 )); then
    log "FATAL: :ro container exited with code $EXIT_CODE_RAW (must be strictly > 0)"
    echo "FAIL: :ro container ExitCode=$EXIT_CODE_RAW, expected > 0" > "$VERDICT_FILE"
    exit 2
fi
log ":ro container exit code = $EXIT_CODE_RAW"

docker logs "$CID" 2>&1 | ansi_strip > "$EVIDENCE/readonly-boot-logs.txt"
if ! grep -Fq 'FATAL: preflight:' "$EVIDENCE/readonly-boot-logs.txt"; then
    log "FATAL: expected 'FATAL: preflight:' not found in :ro-phase logs"
    echo "FAIL: no 'FATAL: preflight:' in :ro-phase logs" > "$VERDICT_FILE"
    exit 2
fi

echo "PASS: :ro-mount → exit=$EXIT_CODE_RAW within ${DEADLINE_SECS}s + 'FATAL: preflight:' present" \
    > "$VERDICT_FILE"
log "readonly-fixture gate PASS"
exit 0
