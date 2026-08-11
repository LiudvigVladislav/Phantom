#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — bootstrap (§9.5 REDLINE-2).
# Uninstall (no -k) → verify absent → install checksum-verified APK.
# Onboarding + QR pairing are MANUAL (real UI, real code paths).

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
MODE="${1:-help}"
OUT="${2:?usage: bootstrap.sh <--fresh|--verify> <evidence-dir>}"
APP_ID="${APP_ID:-phantom.android}"
APK="$ROOT/android-debug-diagnostic.apk"

case "$MODE" in
  --fresh)
    RUN_ID=$(cat "$OUT/run_id" 2>/dev/null || true)
    if [ -z "$RUN_ID" ]; then
      echo "bootstrap --fresh FAILED: run_id missing at $OUT/run_id — run preflight.sh first" >&2
      exit 2
    fi
    echo ""
    echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    echo "!  bootstrap --fresh will:                                    !"
    echo "!    1. UNINSTALL $APP_ID from BOTH devices (all data lost)  !"
    echo "!    2. Reinstall the diagnostic APK on both                  !"
    echo "!  Type the run_id ($RUN_ID) to confirm.                      !"
    echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    printf "> "
    read -r typed
    if [ "$typed" != "$RUN_ID" ]; then
      echo "bootstrap --fresh ABORTED: run_id mismatch" >&2
      exit 3
    fi
    "$HERE/install-apk.sh" "$OUT" "$APK"
    echo ""
    echo "APKs installed on both devices."
    echo "Next manual steps (READ THE OPERATOR README):"
    echo "  a) On BOTH devices, run production onboarding through the UI."
    echo "  b) On BOTH devices, pair via Profile → My Phantom QR →"
    echo "     Share my Phantom contact → scan on the other device."
    echo "  c) When BOTH devices have exactly ONE paired conversation"
    echo "     with each other, run ./run-yota-wss-diagnostic.sh matrix."
    ;;

  --verify)
    phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
    emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)
    for serial in "$phone" "$emu"; do
      if ! adb -s "$serial" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
        echo "bootstrap --verify FAILED: $APP_ID not installed on $serial" >&2
        exit 1
      fi
    done
    echo "bootstrap --verify OK: APK installed on both devices"
    ;;

  *)
    echo "usage: bootstrap.sh <--fresh|--verify> <evidence-dir>"
    exit 2
    ;;
esac
