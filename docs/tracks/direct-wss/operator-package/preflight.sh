#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — §9.6 mandatory preflight.
# Env + devices + APK variant + pin store round-trip + canary
# + dual-SIM default-data check + REST capability + clock skew.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LIB="$HERE/lib"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$HERE/evidence/yota-wss-$STAMP"
mkdir -p "$OUT"
echo "run-yota-$STAMP" > "$OUT/run_id"
APP_ID="${APP_ID:-phantom.android}"

echo "=== preflight $STAMP ==="

# 1. Environment.
for tool in adb python3 bash jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "preflight FAILED: missing $tool" >&2; exit 1
  fi
done

# 2. Device auto-detect (writes roles.env).
"$LIB/detect-devices.sh" "$OUT"
phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)

# 3. APK installed on both + variant is debug.
if ! adb -s "$phone" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
  echo "preflight FAILED: $APP_ID not installed on phone — run bootstrap.sh --fresh first" >&2; exit 1
fi
if ! adb -s "$emu" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
  echo "preflight FAILED: $APP_ID not installed on emulator — run bootstrap.sh --fresh first" >&2; exit 1
fi
# Presence of the diagnostic receiver in `dumpsys package` proves the
# debug variant is installed (release APK does not declare it).
for serial in "$phone" "$emu"; do
  if ! adb -s "$serial" shell dumpsys package "$APP_ID" | tr -d '\r' | grep -q "DiagnosticCommandReceiver"; then
    echo "preflight FAILED: DiagnosticCommandReceiver missing on $serial — installed variant is not debug" >&2; exit 1
  fi
done

# 4. Pin store round-trip (write NONE → confirm via `diagnostic_pin_active` in logcat).
for serial in "$phone" "$emu"; do
  adb -s "$serial" logcat -c
  "$LIB/diag-cmd.sh" pin --serial "$serial" --pin none --run-id "preflight-$STAMP" --cell-id "preflight.pin_roundtrip" >/dev/null
  sleep 3
  if ! adb -s "$serial" logcat -d WSS_DIAG:V "*:S" | grep -q "diagnostic_pin_active"; then
    echo "preflight FAILED: no diagnostic_pin_active event on $serial after pin write" >&2; exit 1
  fi
done

# 5. Canary — proves WSS_DIAG tag emits without any envelope enqueue.
for serial in "$phone" "$emu"; do
  adb -s "$serial" logcat -c
  "$LIB/diag-cmd.sh" canary --serial "$serial" >/dev/null
  sleep 2
  if ! adb -s "$serial" logcat -d WSS_DIAG:V "*:S" | grep -q "diagnostic_canary"; then
    echo "preflight FAILED: canary emit not observed on $serial" >&2; exit 1
  fi
done

# 6. Dual-SIM default-data-operator check.
adb -s "$phone" logcat -c
"$LIB/diag-cmd.sh" dual_sim_report --serial "$phone" >/dev/null
sleep 2
dual_sim_line=$(adb -s "$phone" logcat -d WSS_DIAG:V "*:S" | grep "dual_sim_report" | tail -1 || true)
echo "phone default-data: $dual_sim_line"

# 7. REST capability — Method B controlled fail-closed probe (§9.6):
#    write REST pin; send one canary envelope via the debug receiver;
#    read `sender_rest_post_completed`. If relay_acceptance=
#    disabled_by_capability, REST cells are BLOCKED. Any other value
#    → REST capability is enabled (accepted / duplicate / failed).
#    We record "unknown" if no event lands within the timeout.
rest_cap="unknown"
"$LIB/diag-cmd.sh" pin --serial "$phone" --pin rest --run-id "preflight-$STAMP" --cell-id "preflight.rest_capability" >/dev/null
sleep 3
adb -s "$phone" logcat -c
"$LIB/diag-cmd.sh" send --serial "$phone" --run-id "preflight-$STAMP" --cell-id "preflight.rest_capability" --sequence 1 >/dev/null || true
sleep 20
rest_line=$(adb -s "$phone" logcat -d WSS_DIAG:V "*:S" | grep "sender_rest_post_completed" | tail -1 || true)
if echo "$rest_line" | grep -q "relay_acceptance=disabled_by_capability"; then
  rest_cap="disabled"
elif echo "$rest_line" | grep -qE "relay_acceptance=(accepted|duplicate)"; then
  rest_cap="enabled"
fi
# Reset pin to NONE.
"$LIB/diag-cmd.sh" pin --serial "$phone" --pin none --run-id "preflight-$STAMP" --cell-id "preflight.reset" >/dev/null

# 8. Clock skew (N=5 samples).
skew_sum=0; n=5
for i in 1 2 3 4 5; do
  phone_ms=$(adb -s "$phone" shell date +%s%3N | tr -d '\r')
  emu_ms=$(adb -s "$emu"   shell date +%s%3N | tr -d '\r')
  skew_sum=$(( skew_sum + phone_ms - emu_ms ))
done
skew_ms=$(( skew_sum / n ))
if [ ${skew_ms#-} -gt 30000 ]; then
  echo "preflight FAILED: |clock skew| $skew_ms ms exceeds 30 000 ms cap" >&2; exit 1
elif [ ${skew_ms#-} -gt 2000 ]; then
  echo "preflight WARN: |clock skew| $skew_ms ms exceeds 2 000 ms — cross-device timing precision reduced"
fi

# Write manifests.
{
  echo "{"
  echo "  \"run_id\": \"run-yota-$STAMP\","
  echo "  \"phone_serial\": \"$phone\","
  echo "  \"emulator_serial\": \"$emu\","
  echo "  \"clock_skew_ms\": $skew_ms,"
  echo "  \"dual_sim_report\": \"$(echo "$dual_sim_line" | sed 's/"/\\"/g')\""
  echo "}"
} > "$OUT/device-manifest.json"

{
  echo "{"
  echo "  \"run_id\": \"run-yota-$STAMP\","
  echo "  \"env\": {\"adb\": \"ok\", \"python3\": \"ok\", \"bash\": \"ok\", \"jq\": \"ok\"},"
  echo "  \"apk_variant\": \"debug\","
  echo "  \"pin_roundtrip\": \"ok\","
  echo "  \"canary\": \"ok\","
  echo "  \"rest_capability\": \"$rest_cap\","
  echo "  \"clock_skew_ms\": $skew_ms"
  echo "}"
} > "$OUT/preflight.json"

echo "preflight OK evidence_dir=$OUT rest_capability=$rest_cap"
echo "$OUT" > "$HERE/evidence/.last_run"
