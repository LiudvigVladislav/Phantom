#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — 8 directed cells × 5 envelopes.
# Drives §9.3 matrix. Sends via debug-only DiagnosticCommandReceiver;
# no arbitrary text; no operator-supplied peer.
#
# The runner writes the pin BEFORE each cell (`diag-cmd.sh pin`),
# waits >= 5 s for a `diagnostic_pin_active` event on both devices,
# then fires 5 sends. Between cells:
#   - "after-connect"  : no idle
#   - "after-idle"     : sleep 300 (natural idle; NO airplane mode)
#   - "bg-fg"          : home-key + foreground restore (NO force-stop)

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:?usage: run-matrix.sh <evidence-dir>}"
RUN_ID=$(cat "$OUT/run_id" 2>/dev/null || echo "run-$(date -u +%Y%m%dT%H%M%SZ)")
echo "$RUN_ID" > "$OUT/run_id"

phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)

rest_capability=$(python3 -c "import json,sys; print(json.load(open('$OUT/preflight.json')).get('rest_capability','unknown'))" 2>/dev/null || echo unknown)

# Matrix definition. Each row: PIN|DIRECTION|SCENARIO|SENDER_SERIAL|RECIPIENT_SERIAL
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

# Emit matrix.json.
{
  echo "{"
  echo "  \"run_id\": \"$RUN_ID\","
  echo "  \"rest_capability\": \"$rest_capability\","
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

for row in "${cells[@]}"; do
  IFS='|' read -r pin dir scenario sender recipient <<< "$row"
  cell_id="${pin}.${dir}.${scenario}"
  echo ""
  echo "=== CELL $cell_id ==="

  # Skip BLOCKED REST cells; matrix.json already flags them.
  if [ "$pin" = "rest" ] && [ "$rest_capability" = "disabled" ]; then
    echo "SKIPPED (BLOCKED: rest_capability=disabled)"
    continue
  fi

  # Write pin on BOTH devices; wait 5 s for confirmation.
  "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin "$pin" --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null
  "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin "$pin" --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null
  sleep 5

  # Scenario preparation.
  case "$scenario" in
    after-connect) : ;;
    after-idle)    sleep 300 ;;
    bg-fg)
      # Home key on sender; then foreground restore. No force-stop.
      adb -s "$sender" shell input keyevent KEYCODE_HOME
      sleep 5
      adb -s "$sender" shell monkey -p "${APP_ID:-phantom.android}" -c android.intent.category.LAUNCHER 1 >/dev/null
      sleep 3
      ;;
    control)       : ;;
    *)             : ;;
  esac

  # Fire 5 envelopes from sender.
  for seq in 1 2 3 4 5; do
    "$HERE/diag-cmd.sh" send --serial "$sender" --run-id "$RUN_ID" --cell-id "$cell_id" --sequence "$seq" >/dev/null || true
    # 120 s ceiling per envelope is verifier-side; here we just space
    # sends by 2 s so the ack round-trip has room.
    sleep 2
  done

  # Give recipient path 20 s to persist + emit ack-deliver on all 5.
  sleep 20
done

echo ""
echo "matrix DONE — evidence in $OUT"
