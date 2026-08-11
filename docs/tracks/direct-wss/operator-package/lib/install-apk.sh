#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — checksum-verified install.
# §9.5 protocol: uninstall (no -k) → verify absent → SHA-256 verify
# APK → install → verify installed. Executed by bootstrap.sh --fresh.

set -euo pipefail
OUT="${1:?usage: install-apk.sh <evidence-dir>}"
APK="${2:?usage: install-apk.sh <evidence-dir> <apk-path>}"
SHA="${APK}.sha256"
APP_ID="${APP_ID:-phantom.android}"

if [ ! -f "$APK" ] || [ ! -f "$SHA" ]; then
  echo "install-apk FAILED: APK or sha256 missing at $APK" >&2; exit 1
fi
# macOS `shasum` vs Linux `sha256sum` — support both.
if command -v shasum >/dev/null 2>&1; then
  computed=$(shasum -a 256 "$APK" | awk '{print $1}')
else
  computed=$(sha256sum "$APK" | awk '{print $1}')
fi
expected=$(awk '{print $1}' "$SHA")
if [ "$computed" != "$expected" ]; then
  echo "install-apk FAILED: SHA-256 mismatch (expected=$expected got=$computed)" >&2; exit 1
fi

phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)
for serial in "$phone" "$emu"; do
  # Best-effort uninstall; ignore "not installed" errors.
  adb -s "$serial" uninstall "$APP_ID" 2>/dev/null || true
  # Verify absent.
  if adb -s "$serial" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
    echo "install-apk FAILED: $APP_ID still present after uninstall on $serial" >&2; exit 1
  fi
  # Install.
  adb -s "$serial" install -r "$APK" >/dev/null
  # Verify installed.
  if ! adb -s "$serial" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
    echo "install-apk FAILED: $APP_ID missing after install on $serial" >&2; exit 1
  fi
done
echo "install-apk OK sha256=$expected"
