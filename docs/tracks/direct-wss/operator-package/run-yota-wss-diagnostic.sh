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
    exec "$HERE/preflight.sh"
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
Direct WSS Yota-First diagnostic — Mac operator entry point.

  bootstrap --fresh  standalone: detect devices + uninstall + reinstall APK
                     + set emitter_id + print manual onboarding instructions
  preflight          measurement preflight (after manual onboarding + QR pairing)
  matrix             run 8 × 5 = 40 envelopes + auto-verify
  verify [DIR]       re-run verifier

  Order: bootstrap --fresh -> manual onboarding+pairing -> preflight -> matrix
HELP
    exit 2
    ;;
esac
