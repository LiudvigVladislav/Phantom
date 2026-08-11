#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — 8 directed cells × 5 envelopes.
# §12 P0-5: polls per-envelope for the 4 delivery signals up to 120 s
# from sender_enqueue, then moves on.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:?usage: run-matrix.sh <evidence-dir>}"
RUN_ID=$(cat "$OUT/run_id")

phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)

rest_capability=$(python3 -c "import json,sys; print(json.load(open('$OUT/preflight.json')).get('rest_capability','unknown'))")

cells=(
  "wss|p2e|after-connect|$phone|$emu"
  "wss|e2p|after-connect|$emu|$phone"
  "wss|p2e|after-idle|$phone|$emu"
  "wss|e2p|after-idle|$emu|$phone"
  "wss|p2e|bg-fg|$phone|$emu"
  "wss|e2p|bg-fg|$emu|$phone"
  "rest|p2e|control|$phone|$emu"
  "rest|e2p|control|$emu|$phone"
)

# matrix.json — always emit 8 cells with `blocked` per REST capability.
{
  echo "{"
  echo "  \"run_id\": \"$RUN_ID\","
  echo "  \"rest_capability\": \"$rest_capability\","
  echo "  \"preflight\": $(cat "$OUT/preflight.json"),"
  echo "  \"cells\": ["
  first=1
  for row in "${cells[@]}"; do
    IFS='|' read -r pin dir scenario sender recipient <<< "$row"
    cell_id="${pin}.${dir}.${scenario}"
    blocked="false"
    if [ "$pin" = "rest" ] && [ "$rest_capability" = "disabled" ]; then
      blocked="true"
    fi
    [ $first -eq 0 ] && echo ","
    printf '    {"cell_id": "%s", "pin": "%s", "direction": "%s", "scenario": "%s", "blocked": %s}' \
      "$cell_id" "$pin" "$dir" "$scenario" "$blocked"
    first=0
  done
  echo ""
  echo "  ]"
  echo "}"
} > "$OUT/matrix.json"

poll_envelope() {
  # Wait for 4 delivery signals for the given cid, OR 120s from
  # enqueue_wall, whichever first. Returns 0 always.
  local cid="$1" enqueue_wall_ms="$2"
  local deadline_ms=$(( enqueue_wall_ms + 120000 ))
  while : ; do
    local now_ms=$(date +%s%3N)
    if [ "$now_ms" -ge "$deadline_ms" ]; then
      return 0
    fi
    local logfiles=("$OUT/phone.logcat.wss_diag" "$OUT/emulator.logcat.wss_diag")
    local fresh=$(grep -h -c "event=recipient_deliver_received .*correlation_id=$cid .*dedup_gate=fresh" "${logfiles[@]}" || echo 0)
    local persist=$(grep -h -c "event=recipient_message_persisted .*correlation_id=$cid" "${logfiles[@]}" || echo 0)
    local ack=$(grep -h -c "event=recipient_ack_deliver_sent .*correlation_id=$cid" "${logfiles[@]}" || echo 0)
    if [ "$fresh" -ge 1 ] && [ "$persist" -ge 1 ] && [ "$ack" -ge 1 ]; then
      return 0
    fi
    sleep 3
  done
}

for row in "${cells[@]}"; do
  IFS='|' read -r pin dir scenario sender recipient <<< "$row"
  cell_id="${pin}.${dir}.${scenario}"
  echo ""
  echo "=== CELL $cell_id ==="

  if [ "$pin" = "rest" ] && [ "$rest_capability" = "disabled" ]; then
    echo "SKIPPED (BLOCKED: rest_capability=disabled)"
    continue
  fi

  # Write pin on BOTH devices; wait for diagnostic_pin_active on both.
  "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin "$pin" --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null
  "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin "$pin" --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null
  # Wait for BOTH pin_active events to land.
  for i in 1 2 3 4 5 6 7 8 9 10; do
    p_count=$(grep -h -c "event=diagnostic_pin_active .*pin=$pin .*cell_id=$cell_id" "$OUT/phone.logcat.wss_diag" "$OUT/emulator.logcat.wss_diag" 2>/dev/null || echo 0)
    if [ "$p_count" -ge 2 ]; then break; fi
    sleep 2
  done

  case "$scenario" in
    after-connect) : ;;
    after-idle)    sleep 300 ;;
    bg-fg)
      adb -s "$sender" shell input keyevent KEYCODE_HOME
      sleep 5
      adb -s "$sender" shell monkey -p "${APP_ID:-phantom.android}" -c android.intent.category.LAUNCHER 1 >/dev/null
      sleep 3
      ;;
    control)       : ;;
    *)             : ;;
  esac

  for seq in 1 2 3 4 5; do
    enqueue_wall_ms=$(date +%s%3N)
    "$HERE/diag-cmd.sh" send --serial "$sender" --run-id "$RUN_ID" --cell-id "$cell_id" --sequence "$seq" >/dev/null || true
    # Grab the correlation ID from the last "send ok" line by the receiver.
    sleep 2
    cid=$(grep -h "send ok" "$OUT/phone.logcat.wss_diag" "$OUT/emulator.logcat.wss_diag" 2>/dev/null | tail -1 | sed -n 's/.*correlation_id=\([a-f0-9-]*\).*/\1/p')
    # Fallback: if no correlation_id read yet, wait a bit and try again.
    if [ -z "$cid" ]; then
      sleep 3
      cid=$(grep -h "send ok" "$OUT/phone.logcat.wss_diag" "$OUT/emulator.logcat.wss_diag" 2>/dev/null | tail -1 | sed -n 's/.*correlation_id=\([a-f0-9-]*\).*/\1/p')
    fi
    if [ -n "$cid" ]; then
      poll_envelope "$cid" "$enqueue_wall_ms"
    else
      echo "warn: could not read correlation_id for seq=$seq — continuing"
      sleep 5
    fi
  done

  # Clear pin at end of cell to keep guard state clean between cells.
  "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null || true
  "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null || true
done

# End-of-run clear on both devices.
"$HERE/diag-cmd.sh" clear --serial "$phone" >/dev/null || true
"$HERE/diag-cmd.sh" clear --serial "$emu"   >/dev/null || true

echo ""
echo "matrix DONE — evidence in $OUT"
