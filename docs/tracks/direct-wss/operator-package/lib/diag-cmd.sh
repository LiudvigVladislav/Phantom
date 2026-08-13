#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — thin ADB wrapper around the
# debug-only `DiagnosticCommandReceiver` (§9.3).
#
# §12 P1: `eval` is replaced with a Bash argument array.
#
# Usage:
#   diag-cmd.sh <subcommand> --serial <SER> [--pin wss|rest|none]
#                             [--run-id <ID>] [--cell-id <ID>]
#                             [--sequence <N>] [--emitter-id phone|emulator]

set -euo pipefail
APP_ID="${APP_ID:-phantom.android}"
COMPONENT="$APP_ID/phantom.android.diagnostic.DiagnosticCommandReceiver"

subcommand="${1:?usage: diag-cmd.sh <subcommand> ...}"; shift

serial=""; pin=""; run_id=""; cell_id=""; sequence=""; emitter_id=""
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) serial="$2"; shift 2 ;;
    --pin) pin="$2"; shift 2 ;;
    --run-id) run_id="$2"; shift 2 ;;
    --cell-id) cell_id="$2"; shift 2 ;;
    --sequence) sequence="$2"; shift 2 ;;
    --emitter-id) emitter_id="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

[ -z "$serial" ] && { echo "--serial required" >&2; exit 2; }

args=(-s "$serial" shell am broadcast -n "$COMPONENT" --es subcommand "$subcommand")

case "$subcommand" in
  pin)
    [ -z "$pin" ] || [ -z "$run_id" ] || [ -z "$cell_id" ] && { echo "pin requires --pin, --run-id, --cell-id" >&2; exit 2; }
    args+=(--es pin "$pin" --es run_id "$run_id" --es cell_id "$cell_id")
    ;;
  send)
    # §12 Round-9 audit P0-1: `am broadcast` waits for the receiver
    # to `finish()` unless `--async` is passed. `send` is the ONLY
    # subcommand whose receiver-side work (`sendMessage`) can hang
    # indefinitely under a hung WSS/REST call — without `--async`
    # the Mac-side `wait_send_cid_from_sender` would not even
    # start until the receiver had fully returned. Passing `--async`
    # decouples the AM callback from the ADB reply so `diag-cmd.sh
    # send` returns as soon as the broadcast is delivered; the CID
    # still lands in logcat via the coordinator's `WssDiag.emit`
    # BEFORE `sendMessage` is called. The other subcommands stay
    # synchronous — their receiver-side work is short and their
    # readback is derived from the ADB return, not from a logcat
    # event.
    [ -z "$run_id" ] || [ -z "$cell_id" ] || [ -z "$sequence" ] && { echo "send requires --run-id, --cell-id, --sequence" >&2; exit 2; }
    args+=(--async --es run_id "$run_id" --es cell_id "$cell_id" --ei sequence "$sequence")
    ;;
  set_emitter_id)
    [ -z "$emitter_id" ] && { echo "set_emitter_id requires --emitter-id" >&2; exit 2; }
    args+=(--es emitter_id "$emitter_id")
    ;;
  canary|dual_sim_report|health|clear|checkpoint|paired_count_report|signed_prekey_readiness) : ;;
  *) echo "unknown subcommand: $subcommand" >&2; exit 2 ;;
esac

adb "${args[@]}"
