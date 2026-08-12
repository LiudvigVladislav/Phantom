#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — 8 × 5 = 40 envelopes.
#
# §12 Round-3 audit P1-2 fixes:
#   - refuse to run into an evidence directory that already has
#     a matrix.json (no accidental cross-run contamination)
#   - pin waits match pin + cell + run_id + expected emitter on the
#     expected source log per device (per-device grep, not chained
#     across both logs)
#   - CID lookups read ONLY the sender's log and require run_id,
#     cell_id, sequence, expected emitter, AND wall_utc_ms >=
#     command_start_ms (captured just before the send subcommand fires)
#   - the real helpers live in lib/run-matrix-helpers.sh so
#     tests/test_shell.sh can drive them against synthetic logs

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=portable.sh
source "$HERE/portable.sh"
# shellcheck source=run-matrix-helpers.sh
source "$HERE/run-matrix-helpers.sh"

OUT="${1:?usage: run-matrix.sh <evidence-dir>}"

# Round-3 P1-2: refuse a second run into the same evidence dir.
if ! refuse_matrix_rerun "$OUT"; then
  exit 2
fi

RUN_ID=$(cat "$OUT/run_id")

# §12 Round-6 audit P0-2: guarantee an atomic matrix-completion
# marker is written for EVERY exit path (normal completion,
# fail-fast abort, unexpected error, user Ctrl-C). The verifier
# refuses a bundle without this marker — otherwise a killed
# matrix looks identical to a completed one.
CELLS_RAN=0
ABORT_REASON=""
write_matrix_completion() {
  local now_ms; now_ms=$(now_ms)
  local body
  if [ -n "$ABORT_REASON" ]; then
    body=$(printf '{"run_id":"%s","completed_at_wall_ms":%s,"cells_ran":%s,"abort_reason":"%s"}' \
                  "$RUN_ID" "$now_ms" "$CELLS_RAN" "$ABORT_REASON")
  else
    body=$(printf '{"run_id":"%s","completed_at_wall_ms":%s,"cells_ran":%s}' \
                  "$RUN_ID" "$now_ms" "$CELLS_RAN")
  fi
  # Write atomically via a same-dir tmp + mv (POSIX rename).
  local tmp="$OUT/.matrix_completion.$$"
  printf '%s' "$body" > "$tmp"
  mv "$tmp" "$OUT/matrix_completion.json"
}
trap 'write_matrix_completion' EXIT

phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)

phone_log="$OUT/phone.logcat.wss_diag"
emu_log="$OUT/emulator.logcat.wss_diag"

rest_capability=$(python3 -c "import json; print(json.load(open('$OUT/preflight.json')).get('rest_capability','unknown'))")

# §12 Round-4 audit P1-3: read per-device host-to-sender skew so the
# CID-not-before comparison can be done on a device clock the two
# agree on. Values are the same ones the verifier consumes; they were
# measured N=5 in preflight and are echoed here into the runner.
host_to_phone_skew_ms=$(python3 -c "import json; print(json.load(open('$OUT/preflight.json')).get('host_to_phone_skew_ms',0))")
host_to_emu_skew_ms=$(python3 -c "import json; print(json.load(open('$OUT/preflight.json')).get('host_to_emulator_skew_ms',0))")

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

# per-cell/per-device helper wrappers with a 30-s poll deadline.
wait_pin_active_both() {
  # Round-3 P1-2: check phone log with emitter=phone AND emulator log with
  # emitter=emulator; both must match pin+cell+run_id.
  local pin="$1" cell_id="$2" timeout_s="${3:-30}"
  local start; start=$(now_ms)
  local deadline_ms=$(( start + timeout_s * 1000 ))
  while : ; do
    if wait_for_pin_active_in_log "$pin" "$cell_id" "$RUN_ID" "phone" "$phone_log" \
       && wait_for_pin_active_in_log "$pin" "$cell_id" "$RUN_ID" "emulator" "$emu_log"; then
      return 0
    fi
    if [ "$(now_ms)" -ge "$deadline_ms" ]; then return 1; fi
    sleep 2
  done
}

wait_send_cid_from_sender() {
  # Round-3 P1-2 + Round-4 P1-3: reads ONLY the sender's log; requires
  # run_id + cell_id + sequence + emitter + wall_utc_ms ≥
  # (command_start_ms - host_to_sender_skew_ms). Prints the
  # correlation_id or empty (return 1) on timeout.
  local cell_id="$1" sequence="$2" emitter="$3" sender_log="$4"
  local command_start_ms="$5" skew_ms="$6"
  local start; start=$(now_ms)
  local deadline_ms=$(( start + 15000 ))
  while : ; do
    local cid
    cid=$(find_send_cid_in_log "$cell_id" "$sequence" "$RUN_ID" "$emitter" \
                                 "$command_start_ms" "$sender_log" "$skew_ms" || true)
    if [ -n "$cid" ]; then
      printf '%s' "$cid"
      return 0
    fi
    if [ "$(now_ms)" -ge "$deadline_ms" ]; then return 1; fi
    sleep 1
  done
}

poll_envelope() {
  # Poll until 4 delivery signals for cid, OR 120s from enqueue_wall.
  local cid="$1" enqueue_wall_ms="$2"
  local deadline_ms=$(( enqueue_wall_ms + 120000 ))
  while : ; do
    local now; now=$(now_ms)
    if [ "$now" -ge "$deadline_ms" ]; then return 0; fi
    local fresh persist ack
    fresh=$(count_matches "event=recipient_deliver_received.*correlation_id=$cid.*dedup_gate=fresh" "$phone_log" "$emu_log")
    persist=$(count_matches "event=recipient_message_persisted.*correlation_id=$cid" "$phone_log" "$emu_log")
    ack=$(count_matches "event=recipient_ack_deliver_sent.*correlation_id=$cid" "$phone_log" "$emu_log")
    if [ "$fresh" -ge 1 ] && [ "$persist" -ge 1 ] && [ "$ack" -ge 1 ]; then return 0; fi
    sleep 3
  done
}

# §12 Round-9 audit P1 (revised from Round-7): tri-state gate.
# The Round-7 gate accepted `sender_prekey_deferred` as GREEN — a
# local terminal outcome proves the coordinator handed off, but
# NOT that the production transport route was actually reached.
# For smoke-check purposes we need positive proof of transport
# reach, so the gate is tri-state now:
#
#   rc=0  transport_decision seen        → smoke GREEN, proceed
#   rc=2  local terminal outcome seen    → setup/command failure
#         (prekey_deferred | receiver-side rejected | exception).
#         The evidence is enough to diagnose but the transport
#         route was NOT reached — do NOT run the rest of the
#         matrix.
#   rc=1  none of the above within 15 s  → tooling / instrumentation
#         failure (the send never reached HRT and no local terminal
#         fired either).
#
# Only rc=0 unlocks the RUN-FULL-MATRIX prompt.
gate_first_envelope() {
  local cid="$1"
  local start; start=$(now_ms)
  local deadline_ms=$(( start + 15000 ))
  while : ; do
    local disp attempt dec deferred cmd_terminal
    disp=$(count_matches "event=diagnostic_send_dispatched.*correlation_id=$cid" "$phone_log" "$emu_log")
    attempt=$(count_matches "event=sender_send_attempt_started.*correlation_id=$cid" "$phone_log" "$emu_log")
    dec=$(count_matches "event=sender_transport_decision.*correlation_id=$cid" "$phone_log" "$emu_log")
    deferred=$(count_matches "event=sender_prekey_deferred.*correlation_id=$cid" "$phone_log" "$emu_log")
    cmd_terminal=$(count_matches "event=diagnostic_send_command_completed.*correlation_id=$cid.*result=\\(rejected\\|exception\\)" "$phone_log" "$emu_log")
    if [ "$disp" -ge 1 ] && [ "$attempt" -ge 1 ]; then
      if [ "$dec" -ge 1 ]; then
        # rc=0: transport was actually reached → smoke GREEN.
        return 0
      fi
      local terminal=$(( deferred + cmd_terminal ))
      if [ "$terminal" -ge 1 ]; then
        # rc=2: local terminal outcome proves the coordinator
        # handed off but the transport route was NOT reached —
        # setup/command failure. Enough evidence to diagnose,
        # NOT enough to run the rest of the matrix.
        echo "gate SETUP FAILURE: cid=$cid disp=$disp attempt=$attempt dec=$dec deferred=$deferred cmd_terminal=$cmd_terminal" >&2
        return 2
      fi
    fi
    if [ "$(now_ms)" -ge "$deadline_ms" ]; then
      # rc=1: no dispatched, no attempt_started, no terminal
      # outcome → the send never reached HRT AND no local
      # terminal fired → tooling / instrumentation failure.
      echo "gate TOOLING FAILURE after 15s: cid=$cid disp=$disp attempt=$attempt dec=$dec deferred=$deferred cmd_terminal=$cmd_terminal" >&2
      return 1
    fi
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

  # emitter mapping — the SENDER for this cell's direction:
  case "$dir" in
    p2e) sender_emitter="phone"    ; sender_log="$phone_log" ; sender_skew_ms="$host_to_phone_skew_ms" ;;
    e2p) sender_emitter="emulator" ; sender_log="$emu_log"   ; sender_skew_ms="$host_to_emu_skew_ms" ;;
    *)   sender_emitter="unknown"  ; sender_log="/dev/null"  ; sender_skew_ms=0 ;;
  esac

  # Write pin on BOTH devices; wait for diagnostic_pin_active on both.
  "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin "$pin" --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null
  "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin "$pin" --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null
  if ! wait_pin_active_both "$pin" "$cell_id" 30; then
    echo "cell $cell_id ABORTED: pin_active not observed on both devices within 30s (run_id + emitter matched)" >&2
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
    # Round-3 P1-2: capture command_start_ms BEFORE broadcasting the
    # subcommand. Only diagnostic_send_dispatched events with
    # wall_utc_ms >= command_start_ms may satisfy the CID lookup.
    command_start_ms=$(now_ms)
    enqueue_wall_ms=$command_start_ms
    "$HERE/diag-cmd.sh" send --serial "$sender" --run-id "$RUN_ID" --cell-id "$cell_id" --sequence "$seq" >/dev/null || true
    cid=$(wait_send_cid_from_sender "$cell_id" "$seq" "$sender_emitter" "$sender_log" "$command_start_ms" "$sender_skew_ms" || true)
    if [ -z "$cid" ]; then
      # §12 Round-8 audit P0: missing CID after wait_send_cid_from_sender's
      # 15 s deadline is unambiguous tooling failure. The Round-8
      # coordinator emits diagnostic_send_dispatched BEFORE calling
      # sendMessage, so even a hung WSS/REST call MUST produce the CID
      # within 15 s of the broadcast. If it doesn't, the debug receiver
      # never dispatched the coordinator (or the WssDiag emit itself
      # is broken). Do NOT continue the matrix — every following cell
      # would waste time on the same fault.
      ABORT_REASON="tooling_instrumentation_failure"
      echo "ABORT: no diagnostic_send_dispatched CID appeared within 15s of the send" >&2
      echo "       command for cell=$cell_id seq=$seq (run_id=$RUN_ID, emitter=$sender_emitter," >&2
      echo "       host_not_before_ms=$command_start_ms, host_to_sender_skew_ms=$sender_skew_ms)." >&2
      echo "       Not spending another 120s×N envelopes on a receiver that never dispatched." >&2
      echo "       Verifier will report evidence_integrity=RED and product_outcome=NOT_EVALUABLE." >&2
      "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null 2>&1 || true
      "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null 2>&1 || true
      exit 2
    fi
    # §12 Round-6 audit P0-3 (Round-7 revised, Round-9 tri-state):
    # gate the FIRST envelope of the FIRST cell.
    #   rc=0 → smoke GREEN, poll_envelope this envelope, then
    #          prompt for typed RUN-FULL-MATRIX confirmation.
    #   rc=1 → tooling failure.
    #   rc=2 → setup/command failure (local terminal reached, but
    #          transport route NOT reached).
    if [ "$CELLS_RAN" -eq 0 ] && [ "$seq" -eq 1 ]; then
      set +e
      gate_first_envelope "$cid"
      gate_rc=$?
      set -e
      case "$gate_rc" in
        0)
          echo "gate SMOKE GREEN: transport route reached." >&2
          ;;
        2)
          ABORT_REASON="setup_or_command_failure"
          echo "ABORT: first-envelope gate hit a local terminal outcome" >&2
          echo "       (prekey_deferred | rejected | exception) — the coordinator" >&2
          echo "       handed off but the production transport route was NOT" >&2
          echo "       reached. Do NOT run the rest of the matrix; the evidence" >&2
          echo "       collected so far is enough to diagnose the setup failure." >&2
          "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null 2>&1 || true
          "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null 2>&1 || true
          exit 3
          ;;
        *)
          ABORT_REASON="tooling_instrumentation_failure"
          echo "ABORT: production send-path instrumentation missing on the first" >&2
          echo "       canonical envelope (no transport_decision, no prekey_deferred, no" >&2
          echo "       receiver-side rejected/exception). Not spending another 120s×39 envelopes." >&2
          echo "       Verifier will report evidence_integrity=RED and product_outcome=NOT_EVALUABLE." >&2
          "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null 2>&1 || true
          "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null 2>&1 || true
          exit 2
          ;;
      esac
    fi
    poll_envelope "$cid" "$enqueue_wall_ms"

    # §12 Round-9 audit P1: after the smoke envelope finishes its
    # 120-second poll_envelope window, require typed
    # `RUN-FULL-MATRIX` confirmation before continuing. Without
    # it, clear pins and exit gracefully — the trap still writes
    # matrix_completion.json with an abort_reason so the verifier
    # knows to report NOT_EVALUABLE for the unproduced cells.
    # Skippable in unattended dry-runs via WSS_DIAG_SKIP_MATRIX_CONFIRM=1
    # (test fixtures only — never set in a live Yota pass).
    if [ "$CELLS_RAN" -eq 0 ] && [ "$seq" -eq 1 ]; then
      if [ "${WSS_DIAG_SKIP_MATRIX_CONFIRM:-0}" != "1" ]; then
        printf '\nsmoke envelope complete on the first canonical cell.\n'
        printf '  * evidence for this envelope is now in %s\n' "$OUT"
        printf '  * type RUN-FULL-MATRIX to continue with the remaining 39 envelopes\n'
        printf '  * anything else aborts cleanly (pins cleared, matrix_completion recorded)\n'
        printf 'confirmation> '
        # §12 Round-9 packaging nit: `read -r` returns non-zero on
        # EOF (Ctrl-D, closed stdin). Under `set -e` that would
        # skip the abort-cleanup below and fall through — treat
        # any non-zero read (EOF, error) as an EMPTY confirmation
        # so the equality check below routes it into the abort
        # branch. Result: EOF cannot silently proceed to the full
        # 8×5 matrix.
        typed_confirm=""
        read -r typed_confirm || typed_confirm=""
        if [ "$typed_confirm" != "RUN-FULL-MATRIX" ]; then
          ABORT_REASON="operator_declined_full_matrix"
          echo "ABORT: operator did not type RUN-FULL-MATRIX (or stdin closed) — clearing pins and exiting." >&2
          "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null 2>&1 || true
          "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null 2>&1 || true
          "$HERE/diag-cmd.sh" clear --serial "$phone" >/dev/null 2>&1 || true
          "$HERE/diag-cmd.sh" clear --serial "$emu"   >/dev/null 2>&1 || true
          exit 4
        fi
      fi
    fi
  done

  "$HERE/diag-cmd.sh" pin --serial "$sender"    --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null || true
  "$HERE/diag-cmd.sh" pin --serial "$recipient" --pin none --run-id "$RUN_ID" --cell-id "$cell_id" >/dev/null || true
  CELLS_RAN=$(( CELLS_RAN + 1 ))
done

"$HERE/diag-cmd.sh" clear --serial "$phone" >/dev/null || true
"$HERE/diag-cmd.sh" clear --serial "$emu"   >/dev/null || true

echo ""
echo "matrix DONE — evidence in $OUT"
