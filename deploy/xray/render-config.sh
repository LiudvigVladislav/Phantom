#!/usr/bin/env bash
# Render deploy/xray/config.json from config.json.template + .env on this host.
# Idempotent: re-run is safe; existing config.json is overwritten.
#
# Required vars in deploy/xray/.env:
#   XRAY_PRIVATE_KEY   (from `xray x25519` private)
#   XRAY_UUID          (from `xray uuid`)
#   XRAY_SHORT_ID      (from `openssl rand -hex 8`)
#
# Outputs:
#   deploy/xray/config.json — gitignored, mounted into the xray container.
#
# Usage: from the deploy/xray directory:
#   ./render-config.sh
#
# KEY ROTATION PLAYBOOK (when VPS is suspected compromised, or on schedule):
#   1. Generate new keypair on VPS:
#        docker run --rm ghcr.io/xtls/xray-core:latest x25519
#        # → Private key: <new-priv>   Password: <new-pub>
#   2. Generate new UUID + shortId:
#        docker run --rm ghcr.io/xtls/xray-core:latest uuid
#        openssl rand -hex 8
#   3. Update deploy/xray/.env on VPS — replace XRAY_PRIVATE_KEY, XRAY_UUID,
#      XRAY_SHORT_ID. Re-run this script. Restart xray:
#        docker compose -f deploy/docker-compose.yml up -d xray
#   4. Edit shared/core/xray/.../OperatorXrayConfig.kt — replace PUBLIC_KEY,
#      UUID, SHORT_ID with the new public values from step 1+2.
#   5. Build, sign, ship a new APK. Old clients connecting with the old
#      capability stop reaching the relay (REALITY handshake fails). Plan
#      rollout: stage release before pulling old keys from .env, so the
#      window of "old client can't connect" is bounded.

set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
    echo "ERROR: .env not found in $(pwd)" >&2
    echo "Create it with XRAY_PRIVATE_KEY, XRAY_UUID, XRAY_SHORT_ID — see README.md." >&2
    exit 1
fi

# shellcheck source=/dev/null
source .env

for var in XRAY_PRIVATE_KEY XRAY_UUID XRAY_SHORT_ID; do
    if [[ -z "${!var:-}" ]]; then
        echo "ERROR: $var is empty in .env" >&2
        exit 1
    fi
done

# Defensive: if config.json was accidentally created as a DIRECTORY
# (the classic docker bind-mount footgun — happens when `docker compose
# up xray` runs before this script), remove it. Otherwise the mv below
# would interpret the rename as "move tmp INTO config.json/" and fail
# with "Permission denied".
if [[ -d config.json ]]; then
    echo "WARN: config.json is a directory (docker bind-mount artefact). Removing." >&2
    rmdir config.json 2>/dev/null || rm -rf config.json
fi

# sed -i is destructive; render to a temp file then atomically rename so
# a half-written config.json never reaches the running container.
tmp="$(mktemp config.json.XXXXXX)"

sed \
    -e "s|REPLACE_PRIVATE_KEY|${XRAY_PRIVATE_KEY}|g" \
    -e "s|REPLACE_UUID|${XRAY_UUID}|g" \
    -e "s|REPLACE_SHORT_ID|${XRAY_SHORT_ID}|g" \
    config.json.template > "$tmp"

mv "$tmp" config.json
# Permissions 644 (not 600): the ghcr.io/xtls/xray-core image runs the
# daemon as a non-root container user whose UID does not match the host
# `phantom` user. With 600 (owner-only), the container cannot open the
# bind-mounted file → "open /etc/xray/config.json: permission denied"
# in container logs. 644 lets the container UID read it.
#
# Trade-off: 644 is "world-readable" on the HOST. On a single-operator
# VPS where only `phantom` and `root` exist, this is effectively the
# same as 600 (root reads everything regardless). If multi-tenant
# operator access is added later, switch to a docker-side fix:
# `user: <container-uid>` in docker-compose + chown to that UID,
# OR move secrets out of bind-mount into a docker secret / env file.
chmod 644 config.json

echo "Rendered config.json from template."
echo "Verify with: docker run --rm -v \"\$(pwd)/config.json:/etc/xray/config.json:ro\" ghcr.io/xtls/xray-core:latest run -test -c /etc/xray/config.json"
