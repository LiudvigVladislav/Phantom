#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — narrow capture (§9.2, P0-4).
# Only WSS_DIAG events; no broad tags.
#
# §12 Round-1 audit P0-1: the earlier `logcat -c` before starting
# capture wiped the boot-init diagnostic_session_started event —
# forcing the verifier to always report integrity RED. Fix: do
# NOT clear the buffer. Instead, capture from a wall-clock
# timestamp AND rely on the operator preflight to call
# `diag-cmd.sh checkpoint` AFTER capture starts so an in-stream
# session_started is guaranteed even if the boot event predates
# capture.

set -euo pipefail
OUT="${1:?usage: capture-logs.sh <evidence-dir>}"

phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)

# `logcat -T 1` = capture from the last line in the buffer forward
# (roughly "everything from now on"). We do NOT clear the buffer.
adb -s "$phone" logcat -v threadtime -T 1 \
    WSS_DIAG:V "*:S" \
    > "$OUT/phone.logcat.wss_diag" 2>/dev/null &
echo $! >> "$OUT/log-pids"
adb -s "$emu" logcat -v threadtime -T 1 \
    WSS_DIAG:V "*:S" \
    > "$OUT/emulator.logcat.wss_diag" 2>/dev/null &
echo $! >> "$OUT/log-pids"

echo "capture-logs streaming; pids: $(tr '\n' ' ' < "$OUT/log-pids")"
