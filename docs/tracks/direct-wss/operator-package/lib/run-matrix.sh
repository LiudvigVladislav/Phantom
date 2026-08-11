#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — 8 × 5 = 40 envelopes.
#
# §12 Round-1 audit P0-1 fixes:
#   - use `now_ms` from portable.sh (BSD-date-safe)
#   - poll CID via structured WSS_DIAG `diagnostic_send_dispatched`
#     event (the send outcome moved from WSS_DIAG_CMD to WSS_DIAG
#     via `event=diagnostic_send_dispatched correlation_id=... sequence=...`)
#   - `count_matches` folds two-file grep -c into ONE integer
#   - pin_active wait matches key/value regardless of field order
#     and ABORTS the cell if both devices don't ack within timeout

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=portable.sh
source "$HERE/portable.sh"

OUT="${1:?usage: run-matrix.sh <evidence-dir>}"
RUN_ID=$(cat "$OUT/run_id")

phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)

rest_capability=$(python3 -c "import json; print(json.load(open('$OUT/preflight.json')).get('rest_capability','unknown'))")

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

# matrix.json — 8 cells, blocked flag for REST when capability disabled.
{
  echo "{"
  echo "  \"run_id\": \"$RUN_ID\","
  echo "  \"rest_capability\": \"$rest_capability\","
  echo "  \"preflight\": $(cat "$OUT/preflight.json"),"
  echo "  \"cells\": ["
  first=1
  for row in "${cells[@]}"; do
    IFS='|' read -r pin dir scenario _sender _recipient <<< "$row"
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

wait_for_pin_active() {
    # Waits for `diagnostic_pin_active pin=<pin>` on BOTH devices for
    # (run,cell). Returns 0 on both observed within timeout, 1 else.
    local pin="$1" cell_id="$2" timeout_s="${3:-30}"
    local start; start=$(now_ms)
    local deadline_ms=$(( start + timeout_s * 1000 ))
    while : ; do
        # Field-order-agnostic: two greps chained, both must hit.
        local phone_hit; phone_hit=$(count_matches "diagnostic_pin_active" "$OUT/phone.logcat.wss_diag")
        local phone_ok=0
        if grep -h "event=diagnostic_pin_active" "$OUT/phone.logcat.wss_diag" 2>/dev/null | \
            grep "pin=$pin" | grep -q "cell_id=$cell_id"; then phone_ok=1; fi
        local emu_ok=0
        if grep -h "event=diagnostic_pin_active" "$OUT/emulator.logcat.wss_diag" 2>/dev/null | \
            grep "pin=$pin" | grep -q "cell_id=$cell_id"; then emu_ok=1; fi
        if [ "$phone_ok" = "1" ] && [ "$emu_ok" = "1" ]; then return 0; fi
        if [ "$(now_ms)" -ge "$deadline_ms" ]; then return 1; fi
        sleep 2
    done
    # phone_hit unused but keeps the shellcheck happy about the earlier ref.
    : "$phone_hit"
}

poll_envelope() {
    # Poll until 4 delivery signals for cid, OR 120s from enqueue_wall.
    local cid="$1" enqueue_wall_ms="$2"
    local deadline_ms=$(( enqueue_wall_ms + 120000 ))
    while : ; do
        local now; now=$(now_ms)
        if [ "$now" -ge "$deadline_ms" ]; then return 0; fi
        local logfiles=("$OUT/phone.logcat.wss_diag" "$OUT/emulator.logcat.wss_diag")
        local fresh persist ack
        fresh=$(count_matches "event=recipient_deliver_received.*correlation_id=$cid.*dedup_gate=fresh" "${logfiles[@]}")
        persist=$(count_matches "event=recipient_message_persisted.*correlation_id=$cid" "${logfiles[@]}")
        ack=$(count_matches "event=recipient_ack_deliver_sent.*correlation_id=$cid" "${logfiles[@]}")
        if [ "$fresh" -ge 1 ] && [ "$persist" -ge 1 ] && [ "$ack" -ge 1 ]; then return 0; fi
        sleep 3
    done
}

wait_for_send_cid() {
    # After a send subcommand fires, poll for the structured
    # `diagnostic_send_dispatched sequence=<seq>` event and print
    # its correlation_id. Times out at 15s.
    local sequence="$1"
    local start; start=$(now_ms)
    local deadline_ms=$(( start + 15000 ))
    while : ; do
        local line; line=$(grep -h "event=diagnostic_send_dispatched" \
            "$OUT/phone.logcat.wss_diag" "$OUT/emulator.logcat.wss_diag" 2>/dev/null \
            | grep "sequence=$sequence" | tail -1)
        if [ -n "$line" ]; then
            extract_field correlation_id "$line"
            return 0
        fi
        if [ "$(now_ms)" -ge "$deadline_ms" ]; then return 1; fi
        sleep 1
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
    if ! wait_for_pin_active "$pin" "$cell_id" 30; then
        echo "cell $cell_id ABORTED: pin_active not observed on both devices within 30s" >&2
        continue
    fi

    case "$scenario" in
        after-connect) : ;;
        after-idle)    sleep 300 ;;
        bg-fg)
            adb -s "$sender" shell input keyevent KEYCODE_HOME
            sleep 5
            adb -s "$sender" shell monkey -p "${APP_ID:-phantom.android}" \
                -c android.intent.category.LAUNCHER 1 >/dev/null
            sleep 3
            ;;
        control) : ;;
        *) : ;;
    esac

    for seq in 1 2 3 4 5; do
        enqueue_wall_ms=$(now_ms)
        "$HERE/diag-cmd.sh" send --serial "$sender" --run-id "$RUN_ID" --cell-id "$cell_id" --sequence "$seq" >/dev/null || true
        cid=$(wait_for_send_cid "$seq" || true)
        if [ -n "$cid" ]; then
            poll_envelope "$cid" "$enqueue_wall_ms"
        else
            echo "warn: could not read correlation_id for seq=$seq" >&2
            sleep 5
        fi
    done

    "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null || true
    "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null || true
done

"$HERE/diag-cmd.sh" clear --serial "$phone" >/dev/null || true
"$HERE/diag-cmd.sh" clear --serial "$emu"   >/dev/null || true

echo ""
echo "matrix DONE — evidence in $OUT"
