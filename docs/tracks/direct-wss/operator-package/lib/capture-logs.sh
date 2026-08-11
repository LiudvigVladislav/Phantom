#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — narrow capture (§9.2, §11 P0-4).
# Only WSS_DIAG events; no broad PhantomHybrid/PhantomRelay tags.

set -euo pipefail
OUT="${1:?usage: capture-logs.sh <evidence-dir>}"

phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)

START_ISO=$(date -u +"%Y-%m-%dT%H:%M:%S.000")
adb -s "$phone" logcat -c
adb -s "$emu"   logcat -c

adb -s "$phone" logcat -v threadtime -T "$START_ISO" \
    WSS_DIAG:V *:S \
    > "$OUT/phone.logcat.wss_diag" 2>/dev/null &
echo $! >> "$OUT/log-pids"
adb -s "$emu" logcat -v threadtime -T "$START_ISO" \
    WSS_DIAG:V *:S \
    > "$OUT/emulator.logcat.wss_diag" 2>/dev/null &
echo $! >> "$OUT/log-pids"

echo "capture-logs streaming; pids: $(cat "$OUT/log-pids" | tr '\n' ' ')"
