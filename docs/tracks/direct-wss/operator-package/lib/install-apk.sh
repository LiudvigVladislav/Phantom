#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — checksum-verified install
# (§9.5). §12 Round-1 audit P1: use portable sha256_file so this
# works on both macOS and Linux without depending on GNU coreutils.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=portable.sh
source "$HERE/portable.sh"

OUT="${1:?usage: install-apk.sh <evidence-dir>}"
APK="${2:?usage: install-apk.sh <evidence-dir> <apk-path>}"
SHA="${APK}.sha256"
APP_ID="${APP_ID:-phantom.android}"

if [ ! -f "$APK" ] || [ ! -f "$SHA" ]; then
    echo "install-apk FAILED: APK or sha256 missing at $APK" >&2; exit 1
fi
computed=$(sha256_file "$APK")
expected=$(awk '{print $1}' "$SHA")
if [ "$computed" != "$expected" ]; then
    echo "install-apk FAILED: SHA-256 mismatch (expected=$expected got=$computed)" >&2; exit 1
fi

phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)
for serial in "$phone" "$emu"; do
    adb -s "$serial" uninstall "$APP_ID" 2>/dev/null || true
    if adb -s "$serial" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
        echo "install-apk FAILED: $APP_ID still present after uninstall on $serial" >&2; exit 1
    fi
    adb -s "$serial" install -r "$APK" >/dev/null
    if ! adb -s "$serial" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
        echo "install-apk FAILED: $APP_ID missing after install on $serial" >&2; exit 1
    fi
done
echo "install-apk OK sha256=$expected"
