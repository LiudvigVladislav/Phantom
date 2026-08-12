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
  # §12 Round-6 audit live-Mac fix: `adb ... shell` inherits stdin
  # from the surrounding `while read -r serial` loop. On the live
  # Mac run the nested `adb shell getprop` swallowed the second
  # serial line from the pipeline, and only the first device got
  # classified. `< /dev/null` detaches adb's stdin so the outer
  # while-read continues to see every serial.
  is_emu=$(adb -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null < /dev/null | tr -d '\r\n')
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
