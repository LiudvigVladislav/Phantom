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
APK="$HERE/android-debug-diagnostic.apk"
APK_SHA_SIDECAR="${APK}.sha256"

echo "=== measurement preflight $STAMP ==="

# 1. Env.
for tool in adb python3 bash jq; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "preflight FAILED: missing $tool" >&2; exit 1
    fi
done
# §12 Round-5 audit P1: verifier uses 3.9-only syntax (dict[..], list[..]
# in dataclass annotations) — enforce the version gate here so a stock
# macOS python 3.8 doesn't die in unittest with a SyntaxError.
if ! python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)'; then
    py_ver=$(python3 -c 'import sys; print(".".join(str(x) for x in sys.version_info[:3]))')
    echo "preflight FAILED: python3 >= 3.9 required (found $py_ver)" >&2; exit 1
fi
# sha256_file abstracts sha256sum vs shasum — no direct requirement here.

# 2. Device detection.
"$LIB/detect-devices.sh" "$OUT"
phone=$(grep '^PHONE=' "$OUT/roles.env" | cut -d= -f2)
emu=$(grep '^EMULATOR=' "$OUT/roles.env" | cut -d= -f2)

# 3. APK variant + debug receiver presence + exact SHA-256 binding.
#    §12 Round-5 audit P1: presence + receiver-name check alone
#    could not prove that the installed APK is the SAME diagnostic
#    build that ships in the operator package. A stale prior build
#    or a differently-signed diagnostic APK would silently pass. Now
#    we pull `base.apk` from each device and sha it locally, and
#    fail closed on any mismatch with the bundled sidecar.
if [ ! -f "$APK" ]; then
    echo "preflight FAILED: bundled APK not found at $APK — final operator package must ship the APK" >&2; exit 1
fi
if [ ! -f "$APK_SHA_SIDECAR" ]; then
    echo "preflight FAILED: bundled APK .sha256 sidecar not found at $APK_SHA_SIDECAR" >&2; exit 1
fi
bundled_apk_sha=$(sha256_file "$APK")
sidecar_sha=$(awk '{print $1}' "$APK_SHA_SIDECAR")
if [ -z "$sidecar_sha" ] || [ "$bundled_apk_sha" != "$sidecar_sha" ]; then
    echo "preflight FAILED: bundled APK sha $bundled_apk_sha != sidecar $sidecar_sha" >&2; exit 1
fi
for serial in "$phone" "$emu"; do
    if ! adb -s "$serial" shell pm list packages | tr -d '\r' | grep -q "package:$APP_ID"; then
        echo "preflight FAILED: $APP_ID not installed on $serial — run bootstrap.sh --fresh first" >&2; exit 1
    fi
    # §12 Round-6 audit live-Mac fix: on API 36 `dumpsys package` output
    # no longer includes the receiver's simple class name in a form
    # `grep DiagnosticCommandReceiver` can find; the previous check
    # false-negatived on a valid debug APK. Replace with an ACTIVE,
    # non-mutating health readback: fire `diag-cmd.sh health` and
    # confirm a `WSS_DIAG_CMD health emitter_id=` line appears within
    # 5 seconds. Only the debug variant registers the receiver AND
    # implements the health subcommand — a release APK would silently
    # drop the broadcast.
    adb -s "$serial" logcat -c
    "$HERE/lib/diag-cmd.sh" health --serial "$serial" >/dev/null || {
        echo "preflight FAILED: diag-cmd.sh health broadcast to $serial exited non-zero — receiver not present or DUMP permission missing" >&2; exit 1
    }
    sleep 2
    if ! adb -s "$serial" logcat -d WSS_DIAG_CMD:V "*:S" | grep -q "health emitter_id="; then
        echo "preflight FAILED: no active health readback from $serial after 2s — installed variant is not debug (release APK never registers the DiagnosticCommandReceiver)" >&2
        exit 1
    fi
    # Pull base.apk (there may be multiple split APKs; the base one is
    # the one containing the receiver — pick the first `base.apk`).
    device_base=$(adb -s "$serial" shell pm path "$APP_ID" | tr -d '\r' \
                   | sed 's|package:||' | grep -E '/base\.apk$' | head -1)
    if [ -z "$device_base" ]; then
        # fall back to first path if no split scheme
        device_base=$(adb -s "$serial" shell pm path "$APP_ID" | tr -d '\r' \
                       | sed 's|package:||' | head -1)
    fi
    pulled="$OUT/base-$serial.apk"
    adb -s "$serial" pull "$device_base" "$pulled" >/dev/null 2>&1 || {
        echo "preflight FAILED: could not pull $device_base from $serial" >&2; exit 1
    }
    installed_sha=$(sha256_file "$pulled")
    if [ "$installed_sha" != "$bundled_apk_sha" ]; then
        echo "preflight FAILED: installed APK on $serial sha $installed_sha != bundled $bundled_apk_sha" >&2
        echo "  (stale diagnostic APK on device — re-run bootstrap.sh --fresh)" >&2
        exit 1
    fi
    rm -f "$pulled"
done
echo "APK sha256 binding OK on both devices (sha256=$bundled_apk_sha)"

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

# 4b. §12 Round-7 + Round-8 audit P1-2: signed prekey readiness on
#     relay. The debug receiver's `signed_prekey_readiness` subcommand
#     fires `PreKeyApi.fetchStatus(identity, identity)` — a
#     NON-consuming GET /prekeys/status — and logs
#     `signed_prekey_readiness published=<true|false>
#     signed_prekey_age_days=<n|null> remaining_opks=<n>` to
#     WSS_DIAG_CMD. Preflight refuses to run the matrix if either
#     device's own signed prekey has not yet been published to the
#     relay AND writes per-device booleans into preflight.json so
#     the verifier can enforce the gate at report time (Round-8 audit
#     P1: the Round-7 preflight ran the check but did not persist the
#     result — verifier had no way to prove this gate was ever run).
phone_signed_prekey_ready="false"
emu_signed_prekey_ready="false"
for want in "$phone|phone" "$emu|emulator"; do
    serial="${want%|*}"; role="${want#*|}"
    adb -s "$serial" logcat -c
    "$LIB/diag-cmd.sh" signed_prekey_readiness --serial "$serial" >/dev/null
    sleep 5
    readiness=$(adb -s "$serial" logcat -d WSS_DIAG_CMD:V "*:S" \
                 | grep "signed_prekey_readiness " | tail -1 || true)
    if [ -z "$readiness" ]; then
        echo "preflight FAILED: no signed_prekey_readiness output from $serial within 5s — receiver not present or PreKeyApi not wired" >&2
        exit 1
    fi
    if ! printf '%s' "$readiness" | grep -q "published=true"; then
        echo "preflight FAILED: $serial ($role) reports signed_prekey_readiness NOT published — its own signed prekey is not on the relay yet." >&2
        echo "  do NOT run the matrix — the sender would emit sender_prekey_deferred for every envelope, no transport traffic would happen, and the run would be indistinguishable from a real Yota failure." >&2
        echo "  Wait 30 s and re-run preflight. If it persists, verify the relay is reachable and the app has published its bundle at least once." >&2
        echo "  ($readiness)" >&2
        exit 1
    fi
    if [ "$role" = "phone" ]; then
        phone_signed_prekey_ready="true"
    else
        emu_signed_prekey_ready="true"
    fi
    echo "$serial ($role): $readiness"
done

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

# 10. Clock skews (N=5 samples). §12 Round-2 audit P1: measure
#     host↔phone AND host↔emulator (Mac clock may itself be drifted
#     from both devices; comparing device-to-device alone would
#     hide a shared shift). Positive skew = host clock ahead of
#     device clock. Verifier adds this offset to device wall_utc_ms
#     to get an equivalent host time.
h2p_sum=0; h2e_sum=0; n=5
for _ in 1 2 3 4 5; do
    host_ms=$(now_ms)
    phone_ms=$(adb -s "$phone" shell date +%s000 | tr -d '\r')
    emu_ms=$(adb -s "$emu"   shell date +%s000 | tr -d '\r')
    h2p_sum=$(( h2p_sum + host_ms - phone_ms ))
    h2e_sum=$(( h2e_sum + host_ms - emu_ms ))
done
host_to_phone_ms=$(( h2p_sum / n ))
host_to_emu_ms=$(( h2e_sum / n ))
worst_abs=${host_to_phone_ms#-}
tmp_abs=${host_to_emu_ms#-}
if [ "$tmp_abs" -gt "$worst_abs" ]; then worst_abs="$tmp_abs"; fi
if [ "$worst_abs" -gt 30000 ]; then
    echo "preflight FAILED: |host↔device skew| exceeds 30 000 ms (host_to_phone=$host_to_phone_ms host_to_emu=$host_to_emu_ms)" >&2
    exit 1
elif [ "$worst_abs" -gt 2000 ]; then
    echo "preflight WARN: |host↔device skew| > 2 000 ms (host_to_phone=$host_to_phone_ms host_to_emu=$host_to_emu_ms)"
fi

cat > "$OUT/device-manifest.json" <<EOF
{
  "run_id": "$RUN_ID",
  "phone_serial": "$phone",
  "emulator_serial": "$emu",
  "host_to_phone_skew_ms": $host_to_phone_ms,
  "host_to_emulator_skew_ms": $host_to_emu_ms,
  "dual_sim_report_operator_numeric": "$op_numeric",
  "diagnostic_apk_sha256": "$bundled_apk_sha"
}
EOF

# §12 Round-3 audit P0-3: verifier cross-checks matrix.run_id ==
# preflight.run_id == device-manifest.run_id, matrix.rest_capability ==
# preflight.rest_capability, and skews between the two files.
# §12 Round-5 audit P1: verifier also cross-checks
# preflight.diagnostic_apk_sha256 == device-manifest.diagnostic_apk_sha256.

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
  "host_to_phone_skew_ms": $host_to_phone_ms,
  "host_to_emulator_skew_ms": $host_to_emu_ms,
  "diagnostic_apk_sha256": "$bundled_apk_sha",
  "phone_signed_prekey_ready": $phone_signed_prekey_ready,
  "emulator_signed_prekey_ready": $emu_signed_prekey_ready
}
EOF

echo "preflight OK evidence_dir=$OUT rest_capability=$rest_cap"
echo "$OUT" > "$HERE/evidence/.last_run"
