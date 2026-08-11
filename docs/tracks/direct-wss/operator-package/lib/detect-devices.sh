#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — device auto-detection.
# §9.1 contract: no hard-coded ADB serials. Requires exactly one
# emulator + one physical device online; classifies via `getprop
# ro.kernel.qemu`. Writes `EMULATOR=<serial>` and `PHONE=<serial>`
# entries into $OUT/roles.env.

set -euo pipefail
OUT="${1:?usage: detect-devices.sh <evidence-dir>}"
mkdir -p "$OUT"
: > "$OUT/roles.env"

adb devices | awk 'NR>1 && $2 == "device" { print $1 }' | while read -r serial; do
  [ -z "$serial" ] && continue
  is_emu=$(adb -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r\n')
  if [ "$is_emu" = "1" ]; then
    echo "EMULATOR=$serial" >> "$OUT/roles.env"
  else
    echo "PHONE=$serial" >> "$OUT/roles.env"
  fi
done

emu_count=$(grep -c '^EMULATOR=' "$OUT/roles.env" || true)
phone_count=$(grep -c '^PHONE=' "$OUT/roles.env" || true)
if [ "$emu_count" != "1" ] || [ "$phone_count" != "1" ]; then
  echo "detect-devices FAILED: expected 1 emulator + 1 phone, got emu=$emu_count phone=$phone_count" >&2
  cat "$OUT/roles.env" >&2
  exit 1
fi
echo "detect-devices OK: $(cat "$OUT/roles.env" | tr '\n' ' ')"
