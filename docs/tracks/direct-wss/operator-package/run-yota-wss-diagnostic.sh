#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — main entry point.
#
# §12 P0-4 split: bootstrap and preflight are INDEPENDENT commands.
#
# Usage:
#   ./run-yota-wss-diagnostic.sh bootstrap --fresh    # uninstall+install (standalone)
#   ./run-yota-wss-diagnostic.sh preflight            # measurement preflight (after onboarding)
#   ./run-yota-wss-diagnostic.sh matrix               # run 8x5=40 envelopes (needs preflight)
#   ./run-yota-wss-diagnostic.sh verify [DIR]         # rerun verifier on an evidence dir

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LIB="$HERE/lib"
mode="${1:-help}"; shift || true

case "$mode" in
  bootstrap)
    exec "$LIB/bootstrap.sh" "${1:---help}"
    ;;
  preflight)
    # §12 WSS-2: pass operator flags through unchanged. Preflight
    # itself validates and fails closed on missing/unknown/malformed
    # inputs (see lib/operator-args.sh).
    exec "$HERE/preflight.sh" "$@"
    ;;
  matrix)
    last=$(cat "$HERE/evidence/.last_run" 2>/dev/null || true)
    if [ -z "$last" ] || [ ! -d "$last" ]; then
      echo "no preflight run found — run: $0 preflight first" >&2; exit 2
    fi
    trap 'if [ -f "$last/log-pids" ]; then xargs kill -TERM 2>/dev/null < "$last/log-pids" || true; fi' EXIT
    "$LIB/run-matrix.sh" "$last"
    # Stop log streams (may already be closed).
    if [ -f "$last/log-pids" ]; then
      xargs kill -TERM 2>/dev/null < "$last/log-pids" || true
    fi
    python3 "$HERE/verify-evidence.py" "$last"
    ;;
  verify)
    last="${1:-$(cat "$HERE/evidence/.last_run" 2>/dev/null || true)}"
    if [ -z "$last" ] || [ ! -d "$last" ]; then
      echo "no evidence dir found — pass one explicitly" >&2; exit 2
    fi
    python3 "$HERE/verify-evidence.py" "$last"
    ;;
  *)
    cat <<HELP
Direct WSS diagnostic — Mac operator entry point.

  bootstrap --fresh
      standalone: detect devices + uninstall + reinstall APK + set
      emitter_id + print manual onboarding instructions

  preflight --operator <YOTA|TELE2> --expected-operator-numeric <NNNNN>
      measurement preflight (after manual onboarding + QR pairing).
      Fails closed if the observed default-data operator_numeric on
      the phone does not equal <NNNNN>, or the operator does not
      type <LABEL> at the confirmation prompt. Evidence dir + run_id
      are labelled by <LABEL> so a Yota run and a Tele2 run land in
      distinct directories.

  matrix
      run 8 × 5 = 40 envelopes + smoke gate + typed RUN-FULL-MATRIX
      confirmation + auto-verify

  verify [DIR]
      re-run verifier

Order:  bootstrap --fresh
     -> manual onboarding+pairing
     -> preflight --operator YOTA  --expected-operator-numeric 25011
     -> matrix
     -> (switch phone default-data SIM to Tele2, no re-bootstrap)
     -> preflight --operator TELE2 --expected-operator-numeric <NNNNN>
     -> matrix
HELP
    exit 2
    ;;
esac
