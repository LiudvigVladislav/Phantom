#!/usr/bin/env bash
# Operator wrapper for the approved PR-1b Mac-local witness.

set -Eeuo pipefail
IFS=$'\n\t'

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
STAMP="$(date -u +%Y%m%dt%H%M%Sz)"
SECRET_ROOT="$HOME/.pr1b-witness"
IDENTITY_FILE="$SECRET_ROOT/mac-local-identity.hex"

mkdir -p "$SECRET_ROOT"
chmod 700 "$SECRET_ROOT"

if [[ ! -s "$IDENTITY_FILE" ]]; then
    ( umask 077 && openssl rand -hex 32 > "$IDENTITY_FILE" )
fi

IDENTITY="$(tr -d '\r\n' < "$IDENTITY_FILE")"
if [[ ! "$IDENTITY" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'FATAL: %s must contain exactly 64 lowercase hex characters\n' \
        "$IDENTITY_FILE" >&2
    exit 9
fi

preflight_image() {
    local ref="$1" expected_id="$2" actual_id arch inspect_error

    inspect_error="$(mktemp -t pr1b-image-inspect.XXXXXX)"
    if actual_id="$(docker image inspect "$ref" --format '{{.Id}}' 2>"$inspect_error")"; then
        :
    else
        printf 'FATAL: docker could not inspect %s\n' "$ref" >&2
        sed 's/^/  docker: /' "$inspect_error" >&2
        rm -f "$inspect_error"
        exit 9
    fi
    rm -f "$inspect_error"

    if [[ "$actual_id" != "$expected_id" ]]; then
        printf 'FATAL: image ID mismatch for %s\n  actual:   %s\n  expected: %s\n' \
            "$ref" "$actual_id" "$expected_id" >&2
        exit 9
    fi

    arch="$(docker image inspect "$ref" --format '{{.Architecture}}')"
    if [[ "$arch" != 'amd64' ]]; then
        printf 'FATAL: image architecture mismatch for %s: %s (expected amd64)\n' \
            "$ref" "$arch" >&2
        exit 9
    fi

    printf 'preflight_image=%s id=%s arch=%s\n' "$ref" "$actual_id" "$arch"
}

if [[ "$(docker context show)" != 'desktop-linux' ]]; then
    printf 'FATAL: Docker context must be desktop-linux\n' >&2
    exit 9
fi

preflight_image \
    'phantom-relay:pr1b-27a06c8a' \
    'sha256:a07e53098650b6cac3585da3751d72c159b7ddce4d2b6a874078d1f6526198ce'
preflight_image \
    'pr1b-witness-client:local' \
    'sha256:3d08d6ea00763de0c6936e01b8ffe6c8c89b52e2cfdbbb6e574d22a5fb39d4be'

export COMPOSE_PROJECT_NAME="pr1b-mac-$STAMP"
export COMPOSE_FILE="$BASE/pr1b-mac-local-compose.yml"
export COMPOSE_PROJECT_DIR="$BASE"
export WITNESS_IDENTITY_HEX="$IDENTITY"
export WITNESS_IMAGE="pr1b-witness-client:local"
export WITNESS_KEY_PATH="$SECRET_ROOT/pr1b.key"
export FIXTURE_SECRETS_DIR="$SECRET_ROOT/fixture-secrets"
export EVIDENCE_DIR="$HOME/Downloads/pr1b-mac-local-evidence-$STAMP"
printf '%s\n' "$EVIDENCE_DIR" > "$BASE/LAST-EVIDENCE.txt"

printf 'project=%s\n' "$COMPOSE_PROJECT_NAME"
printf 'evidence=%s\n' "$EVIDENCE_DIR"
printf 'identity_file=%s (length=%d)\n' "$IDENTITY_FILE" "${#IDENTITY}"
printf 'Starting approved two-hour Mac-local witness. Keep power connected, Docker Desktop running, and the lid open.\n'

if bash "$BASE/pr1b-mac-local-witness.sh"; then
    rc=0
else
    rc=$?
fi

printf 'witness_exit=%d\n' "$rc"
printf 'evidence=%s\n' "$EVIDENCE_DIR"
if [[ -f "$EVIDENCE_DIR/verdict.txt" ]]; then
    printf '%s\n' '=== verdict ==='
    cat "$EVIDENCE_DIR/verdict.txt"
else
    printf 'WARNING: verdict.txt is missing\n' >&2
fi

exit "$rc"
