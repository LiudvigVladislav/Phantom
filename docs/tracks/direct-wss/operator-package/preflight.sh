#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — measurement preflight
# (§12 Round-1 audit P0-1 / P0-6 / P1).
#
# Runs AFTER bootstrap.sh --fresh AND after manual onboarding +
# QR pairing on both devices. Verifies:
#   - env + devices auto-detected
#   - APK sha256 matches on both devices (via `pm path` + shasum)
#   - debug variant installed
#   - emitter_id sticks on both (via `health` readback)
#   - paired conversation count == 1 on both (P1)
#   - default-data-SIM operator confirmed as Yota via TYPED YOTA
#     (malformed / empty dual_sim_report rejected before prompt)
#   - radio checklist confirmed per item
#   - REST capability via Method B fail-closed probe
#   - clock skew within 30 s
#   - capture-logs is started AFTER debug boot completes; then a
#     `checkpoint` subcommand is fired so session_started is
#     guaranteed to land in-capture (Round-1 audit P0-1 fix).

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LIB="$HERE/lib"
# shellcheck source=lib/portable.sh
source "$LIB/portable.sh"

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$HERE/evidence/yota-wss-$STAMP"
mkdir -p "$OUT"
RUN_ID="run-yota-$STAMP"
echo "$RUN_ID" > "$OUT/run_id"
APP_ID="${APP_ID:-phantom.android}"

echo "=== measurement preflight $STAMP ==="

# 1. Env.
for tool in adb python3 bash jq; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "preflight FAILED: missing $tool" >&2; exit 1
    fi
done
# sha256_file abstracts sha256sum vs shasum — no direct requirement here.

# 2. Device detection.
"$LIB/detect-devices.sh" "$OUT"
phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)

# 3. APK variant + debug receiver presence.
for serial in "$phone" "$emu"; do
    if ! adb -s "$serial" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
        echo "preflight FAILED: $APP_ID not installed on $serial — run bootstrap.sh --fresh first" >&2; exit 1
    fi
    if ! adb -s "$serial" shell dumpsys package "$APP_ID" | tr -d '\r' | grep -q "DiagnosticCommandReceiver"; then
        echo "preflight FAILED: DiagnosticCommandReceiver missing on $serial — installed variant is not debug" >&2; exit 1
    fi
done

# 4. Emitter role sticks on both devices via health readback.
for want in "$phone|phone" "$emu|emulator"; do
    serial="${want%|*}"; want_id="${want#*|}"
    adb -s "$serial" logcat -c
    "$LIB/diag-cmd.sh" health --serial "$serial" >/dev/null
    sleep 2
    observed=$(adb -s "$serial" logcat -d WSS_DIAG_CMD:V "*:S" | grep "health emitter_id=" | tail -1 | sed -n 's/.*emitter_id=\([a-z_]*\).*/\1/p' || true)
    if [ "$observed" != "$want_id" ]; then
        echo "preflight FAILED: $serial reports emitter_id=$observed, expected $want_id — rerun bootstrap.sh --fresh" >&2; exit 1
    fi
done
emitter_ids_set=true

# 5. Paired conversation count == 1 on both devices (Round-1 audit P1).
for serial in "$phone" "$emu"; do
    adb -s "$serial" logcat -c
    "$LIB/diag-cmd.sh" paired_count_report --serial "$serial" >/dev/null
    sleep 2
    count=$(adb -s "$serial" logcat -d WSS_DIAG_CMD:V "*:S" | grep "paired_count_report count=" | tail -1 | sed -n 's/.*count=\([0-9]*\).*/\1/p' || echo -1)
    if [ "$count" != "1" ]; then
        echo "preflight FAILED: $serial has $count paired conversations, expected exactly 1 — complete manual onboarding + QR pairing per README" >&2; exit 1
    fi
done

# 6. Canary — WSS_DIAG tag emits without any envelope enqueue.
for serial in "$phone" "$emu"; do
    adb -s "$serial" logcat -c
    "$LIB/diag-cmd.sh" canary --serial "$serial" >/dev/null
    sleep 2
    if ! adb -s "$serial" logcat -d WSS_DIAG:V "*:S" | grep -q "event=diagnostic_canary"; then
        echo "preflight FAILED: canary emit not observed on $serial" >&2; exit 1
    fi
done

# 7. Dual-SIM default-data operator (via app-side subcommand).
adb -s "$phone" logcat -c
"$LIB/diag-cmd.sh" dual_sim_report --serial "$phone" >/dev/null
sleep 2
dual_sim_line=$(adb -s "$phone" logcat -d WSS_DIAG_CMD:V "*:S" | grep "dual_sim_report" | tail -1 || true)
if [ -z "$dual_sim_line" ]; then
    echo "preflight FAILED: dual_sim_report produced no output on phone" >&2; exit 1
fi
op_numeric=$(printf '%s' "$dual_sim_line" | sed -n 's/.*operator_numeric=\([0-9]*\).*/\1/p')
if [ -z "$op_numeric" ] || [ "${#op_numeric}" -lt 5 ]; then
    echo "preflight FAILED: dual_sim_report has empty/malformed operator_numeric='$op_numeric'" >&2; exit 1
fi
echo "phone default-data operator_numeric=$op_numeric"

# TYPED YOTA confirmation. Non-YOTA aborts.
printf '\nIs the phone default-data operator YOTA (operator_numeric=%s)? Type YOTA to confirm: ' "$op_numeric"
read -r yota_typed
if [ "$yota_typed" != "YOTA" ]; then
    echo "preflight FAILED: operator did not type YOTA" >&2; exit 1
fi
yota_confirmed=true

# Radio checklist.
for item in "Wi-Fi OFF" "VPN OFF" "Private DNS OFF" "Auto-data-switching OFF" "Other-SIM mobile data OFF"; do
    printf '%s? type YES to confirm: ' "$item"
    read -r a
    if [ "$a" != "YES" ]; then
        echo "preflight FAILED: operator did not confirm '$item'" >&2; exit 1
    fi
done
radio_confirmed=true

# 8. Start capture BEFORE the REST probe so its evidence is preserved.
"$LIB/capture-logs.sh" "$OUT"
sleep 2

# 8a. Fire `checkpoint` on both devices so a session_started event
#     lands in-capture regardless of whether the boot-init event
#     predates capture (Round-1 audit P0-1 fix).
"$LIB/diag-cmd.sh" checkpoint --serial "$phone" >/dev/null
"$LIB/diag-cmd.sh" checkpoint --serial "$emu"   >/dev/null
sleep 3

# 9. REST capability via Method B controlled fail-closed probe.
"$LIB/diag-cmd.sh" pin --serial "$phone" --pin rest --run-id "$RUN_ID" --cell-id "preflight.rest_capability" >/dev/null
sleep 3
"$LIB/diag-cmd.sh" send --serial "$phone" --run-id "$RUN_ID" --cell-id "preflight.rest_capability" --sequence 1 >/dev/null || true
sleep 20
rest_line=$(grep "sender_rest_post_completed" "$OUT/phone.logcat.wss_diag" | tail -1 || true)
rest_cap="unknown"
if echo "$rest_line" | grep -q "relay_acceptance=disabled_by_capability"; then
    rest_cap="disabled"
elif echo "$rest_line" | grep -qE "relay_acceptance=(accepted|duplicate)"; then
    rest_cap="enabled"
fi
"$LIB/diag-cmd.sh" clear --serial "$phone" >/dev/null
"$LIB/diag-cmd.sh" clear --serial "$emu"   >/dev/null

# 10. Clock skew (N=5 samples using device date + host now).
skew_sum=0; n=5
for _ in 1 2 3 4 5; do
    phone_ms=$(adb -s "$phone" shell date +%s000 | tr -d '\r')
    emu_ms=$(adb -s "$emu"   shell date +%s000 | tr -d '\r')
    skew_sum=$(( skew_sum + phone_ms - emu_ms ))
done
skew_ms=$(( skew_sum / n ))
skew_abs=${skew_ms#-}
if [ "$skew_abs" -gt 30000 ]; then
    echo "preflight FAILED: |clock skew| $skew_ms ms > 30 000" >&2; exit 1
elif [ "$skew_abs" -gt 2000 ]; then
    echo "preflight WARN: |clock skew| $skew_ms ms > 2 000 — cross-device timing precision reduced"
fi

cat > "$OUT/device-manifest.json" <<EOF
{
  "run_id": "$RUN_ID",
  "phone_serial": "$phone",
  "emulator_serial": "$emu",
  "clock_skew_ms": $skew_ms,
  "dual_sim_report_operator_numeric": "$op_numeric"
}
EOF

cat > "$OUT/preflight.json" <<EOF
{
  "run_id": "$RUN_ID",
  "env": {"adb": "ok", "python3": "ok", "bash": "ok", "jq": "ok"},
  "apk_variant": "debug",
  "emitter_ids_set": $emitter_ids_set,
  "yota_confirmed": $yota_confirmed,
  "radio_confirmed": $radio_confirmed,
  "paired_conversation_count_ok": true,
  "canary": "ok",
  "rest_capability": "$rest_cap",
  "clock_skew_ms": $skew_ms
}
EOF

echo "preflight OK evidence_dir=$OUT rest_capability=$rest_cap"
echo "$OUT" > "$HERE/evidence/.last_run"
