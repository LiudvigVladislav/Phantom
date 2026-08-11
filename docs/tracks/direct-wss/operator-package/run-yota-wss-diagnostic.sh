#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — main entry point.
#
# Usage:
#   ./run-yota-wss-diagnostic.sh preflight
#   ./run-yota-wss-diagnostic.sh bootstrap --fresh
#   ./run-yota-wss-diagnostic.sh matrix
#   ./run-yota-wss-diagnostic.sh verify

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LIB="$HERE/lib"
mode="${1:-help}"; shift || true

case "$mode" in
  preflight)
    exec "$HERE/preflight.sh"
    ;;
  bootstrap)
    last=$(cat "$HERE/evidence/.last_run" 2>/dev/null || true)
    if [ -z "$last" ] || [ ! -d "$last" ]; then
      echo "no preflight run found — run: $0 preflight first" >&2; exit 2
    fi
    exec "$LIB/bootstrap.sh" "${1:---help}" "$last"
    ;;
  matrix)
    last=$(cat "$HERE/evidence/.last_run" 2>/dev/null || true)
    if [ -z "$last" ] || [ ! -d "$last" ]; then
      echo "no preflight run found — run: $0 preflight first" >&2; exit 2
    fi
    "$LIB/capture-logs.sh" "$last"
    trap 'if [ -f "$last/log-pids" ]; then xargs kill -TERM 2>/dev/null < "$last/log-pids" || true; fi' EXIT
    "$LIB/run-matrix.sh" "$last"
    # Stop log streams.
    if [ -f "$last/log-pids" ]; then
      xargs kill -TERM 2>/dev/null < "$last/log-pids" || true
    fi
    # Run verifier.
    python3 "$HERE/verify-evidence.py" "$last"
    ;;
  verify)
    last="${1:-$(cat "$HERE/evidence/.last_run" 2>/dev/null || true)}"
    if [ -z "$last" ] || [ ! -d "$last" ]; then
      echo "no evidence dir found — pass one explicitly or run preflight" >&2; exit 2
    fi
    python3 "$HERE/verify-evidence.py" "$last"
    ;;
  *)
    cat <<HELP
Direct WSS Yota-First diagnostic — Mac operator entry point.

  preflight   — check env + devices + APK + pins + REST cap + skew
  bootstrap --fresh   — uninstall + reinstall APK on both devices
  matrix      — run 8 × 5 = 40 envelopes + verify
  verify [DIR]   — re-run verifier on an evidence dir

Runs against exactly one physical phone + one emulator. Serials
auto-detected via getprop ro.kernel.qemu.
HELP
    exit 2
    ;;
esac
