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

# sed -i is destructive; render to a temp file then atomically rename so
# a half-written config.json never reaches the running container.
tmp="$(mktemp config.json.XXXXXX)"

sed \
    -e "s|REPLACE_PRIVATE_KEY|${XRAY_PRIVATE_KEY}|g" \
    -e "s|REPLACE_UUID|${XRAY_UUID}|g" \
    -e "s|REPLACE_SHORT_ID|${XRAY_SHORT_ID}|g" \
    config.json.template > "$tmp"

mv "$tmp" config.json
chmod 600 config.json

echo "Rendered config.json from template."
echo "Verify with: docker run --rm -v \"\$(pwd)/config.json:/etc/xray/config.json:ro\" ghcr.io/xtls/xray-core test -config /etc/xray/config.json"
