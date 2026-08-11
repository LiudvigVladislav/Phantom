#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — bootstrap (§12 P0-4 split).
#
# Standalone entry — does NOT require a prior preflight. Detects
# devices, uninstalls the app (no `-k`), verifies the SHA-256 of the
# APK, installs, sets `emitter_id` on each device, prints manual
# onboarding + QR pairing instructions.
#
# Usage:
#   bootstrap.sh --fresh
#   bootstrap.sh --verify

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
MODE="${1:-help}"
APP_ID="${APP_ID:-phantom.android}"
APK="$ROOT/android-debug-diagnostic.apk"
SHA="${APK}.sha256"

case "$MODE" in
  --fresh)
    # §12 P0-4: bootstrap runs standalone — creates its OWN scratch
    # dir; measurement preflight later creates a separate evidence
    # dir. We only need role detection + APK install here.
    SCRATCH="$HERE/../evidence/.bootstrap-$(date -u +%Y%m%dT%H%M%SZ)"
    mkdir -p "$SCRATCH"
    "$HERE/detect-devices.sh" "$SCRATCH"
    phone=$(grep '^PHONE=' "$SCRATCH/roles.env" | cut -d= -f2)
    emu=$(grep '^EMULATOR=' "$SCRATCH/roles.env" | cut -d= -f2)

    printf 'BOOTSTRAP-CONFIRM\n' > "$SCRATCH/confirm-token.txt"
    printf 'This will UNINSTALL %s from BOTH devices (all data lost) then reinstall the diagnostic APK.\n' "$APP_ID"
    printf 'Type BOOTSTRAP-CONFIRM to proceed: '
    read -r typed
    if [ "$typed" != "BOOTSTRAP-CONFIRM" ]; then
      echo "bootstrap --fresh ABORTED" >&2; exit 3
    fi

    "$HERE/install-apk.sh" "$SCRATCH" "$APK"

    # §12 P0-6: set emitter_id on each device via the debug receiver.
    "$HERE/diag-cmd.sh" set_emitter_id --serial "$phone" --emitter-id phone
    "$HERE/diag-cmd.sh" set_emitter_id --serial "$emu"   --emitter-id emulator
    # Read back via health subcommand so operator sees confirmation.
    adb -s "$phone" logcat -c
    adb -s "$emu"   logcat -c
    "$HERE/diag-cmd.sh" health --serial "$phone" >/dev/null
    "$HERE/diag-cmd.sh" health --serial "$emu"   >/dev/null
    sleep 2
    for serial in "$phone" "$emu"; do
      line=$(adb -s "$serial" logcat -d WSS_DIAG_CMD:V "*:S" | grep "health emitter_id=" | tail -1 || true)
      echo "$serial: $line"
    done

    printf '\nAPKs installed and emitter_id set on both devices.\n'
    printf 'Next MANUAL steps (see README-OPERATOR §Bootstrap):\n'
    printf '  a) On BOTH devices, run production onboarding through the UI.\n'
    printf '  b) On BOTH devices, pair via Profile > My Phantom QR >\n'
    printf '     Share my Phantom contact > scan on the other device.\n'
    printf '  c) When BOTH devices have exactly ONE paired conversation\n'
    printf '     with each other, run: run-yota-wss-diagnostic.sh preflight\n'
    ;;

  --verify)
    SCRATCH="$HERE/../evidence/.bootstrap-verify-$(date -u +%Y%m%dT%H%M%SZ)"
    mkdir -p "$SCRATCH"
    "$HERE/detect-devices.sh" "$SCRATCH"
    phone=$(grep '^PHONE=' "$SCRATCH/roles.env" | cut -d= -f2)
    emu=$(grep '^EMULATOR=' "$SCRATCH/roles.env" | cut -d= -f2)
    for serial in "$phone" "$emu"; do
      if ! adb -s "$serial" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
        echo "bootstrap --verify FAILED: $APP_ID not installed on $serial" >&2
        exit 1
      fi
    done
    # Ensure the installed APK matches our checksum. Pulls
    # `base.apk` from either device, shas it locally.
    tmp_apk="$SCRATCH/base-phone.apk"
    adb -s "$phone" shell pm path "$APP_ID" | tr -d '\r' | sed 's|package:||' | head -1 | \
        xargs -I{} adb -s "$phone" pull "{}" "$tmp_apk" >/dev/null
    installed_sha=$(sha256sum "$tmp_apk" | awk '{print $1}')
    expected_sha=$(awk '{print $1}' "$SHA")
    if [ "$installed_sha" != "$expected_sha" ]; then
      echo "bootstrap --verify FAILED: phone installed SHA-256=$installed_sha != expected=$expected_sha" >&2
      exit 1
    fi
    echo "bootstrap --verify OK: APK sha256 matches on phone (spot-check)"
    ;;

  *)
    echo "usage: bootstrap.sh <--fresh|--verify>"
    exit 2
    ;;
esac
