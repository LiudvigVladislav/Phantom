#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — mock adb backend for WSS3_DRY_RUN.
#
# Contract: docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md
# §10 step 4. Used by lib/wss3-adb.sh when WSS3_DRY_RUN=1.
#
# Reads canned responses from a spec file (WSS3_ADB_MOCK_SPEC env var,
# default = <this-script-dir>/mock-adb.spec). Spec format is line-based
# key=value:
#
#   devices=phone-serial-A phone-serial-B
#   apk_sha256=deadbeef...   # 64-char hex; applied uniformly across serials
#   network_profile_json=<path-to-json-file>   # returned by run-as cat
#   logcat_snapshot=<path-to-preloaded-wss-diag-log>
#   paired_count=1
#   signed_prekey_published=true
#
# The mock covers exactly the ADB calls WSS-3 makes:
#   - `adb devices`
#   - `adb -s X shell pm path phantom.android`
#   - `adb -s X shell sha256sum '<path>'`
#   - `adb -s X shell am broadcast -n .../DiagnosticCommandReceiver --es subcommand X`
#   - `adb -s X shell run-as phantom.android cat /data/data/.../network_profile.json`
#   - `adb -s X shell run-as phantom.android rm '<path>'`
#   - `adb -s X logcat -d -v threadtime WSS_DIAG:I ...`
#   - `adb -s X logcat -c`
#
# Every other adb invocation returns empty + exit 0 (best-effort
# passthrough for future extensions).

set -eu

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Audit ROUND-30.18: mutable mock state lives OUTSIDE the package ──
#
# The mock kept its per-serial pin, run id, cell id and logcat buffers in
# `lib/`, next to the scripts it impersonates. Two things followed, and
# the second one bit:
#
#   * the shipped tree was no longer byte-identical after a suite ran,
#     which is why "a run leaves ADB-mock scratch behind" had been a
#     recorded adjacent finding since ROUND-30.14; and
#   * state SURVIVED a killed run. A run that never reached its cleanup
#     left buffers the NEXT run read as its own: a positive control came
#     back RED carrying another run's `run_id` and a `rest.e2p.control`
#     cell, and it looked exactly like a regression. A hygiene note
#     became a false measurement.
#
# The namespace is derived from the RUN, not from the process. This
# script is executed afresh for every single `adb` call, so a per-process
# `mktemp -d` would give each call its own directory and the pin written
# by one invocation would be invisible to the next - state scoped to a
# process is no state at all here.
#
# `WSS3_ADB_MOCK_SPEC` is what identifies a run: every harness and
# fixture mints one in its own scratch, so the path is stable within a
# run and unique across runs. A killed run's directory is therefore
# never reused, and correctness does not depend on any trap firing:
# cleanup is a courtesy, not a precondition.
#
# `WSS3_MOCK_STATE_DIR` still wins when set, so a caller can pin the
# namespace explicitly.
if [ -z "${WSS3_MOCK_STATE_DIR:-}" ]; then
    _mock_ns_seed="${WSS3_ADB_MOCK_SPEC:-}"
    if [ -z "$_mock_ns_seed" ]; then
        _mock_ns_seed="${PPID:-0}"
    fi
    _mock_ns=$(printf '%s' "$_mock_ns_seed" | cksum | cut -d' ' -f1)
    WSS3_MOCK_STATE_DIR="${TMPDIR:-/tmp}/wss3-mock-state.${_mock_ns}"
fi
_MOCK_STATE_DIR="$WSS3_MOCK_STATE_DIR"
mkdir -p "$_MOCK_STATE_DIR" 2>/dev/null || true
export WSS3_MOCK_STATE_DIR

SPEC="${WSS3_ADB_MOCK_SPEC:-$_SCRIPT_DIR/mock-adb.spec}"

# Track rm invocations for the "run-as rm always" invariant fixture.
: "${WSS3_MOCK_ADB_RM_LOG:=}"

_spec_get() {
    local key="$1"
    if [ -f "$SPEC" ]; then
        awk -F= -v k="$key" '$1==k { sub(/^[^=]*=/, ""); print; exit }' "$SPEC"
    fi
}

# Argv: `devices` OR `-s SER shell CMD` OR `-s SER logcat FLAGS`
if [ "$#" -ge 1 ] && [ "$1" = "devices" ]; then
    # `adb devices` — one line per serial in `device` state.
    devs=$(_spec_get devices)
    if [ -z "$devs" ]; then
        # Default: two synthetic serials for the phone + emu.
        devs="mock-phone-serial mock-emu-serial"
    fi
    echo "List of devices attached"
    for d in $devs; do
        echo -e "${d}\tdevice"
    done
    exit 0
fi

if [ "$#" -lt 4 ] || [ "$1" != "-s" ]; then
    # Unrecognised — no-op.
    exit 0
fi

serial="$2"
shift 2   # consume `-s SER`
subverb="$1"; shift
case "$subverb" in
    shell)
        # Everything after `shell` is the command string.
        cmd="$*"
        case "$cmd" in
            pm\ path\ *)
                pkg=$(printf '%s' "$cmd" | awk '{print $NF}')
                echo "package:/data/app/$pkg-1/base.apk"
                ;;
            sha256sum\ *)
                sh=$(_spec_get apk_sha256)
                [ -n "$sh" ] || sh="000000000000000000000000000000000000000000000000000000000000mock"
                path=$(printf '%s' "$cmd" | sed "s/^sha256sum '\\([^']*\\)'.*/\\1/")
                printf '%s  %s\n' "$sh" "$path"
                ;;
            getprop\ ro.kernel.qemu)
                # Audit ROUND-26 P0: mock classifies by the same
                # serial→role mapping already used for network_profile*
                # templates (WSS3_MOCK_PHONE_SERIAL / WSS3_MOCK_EMU_SERIAL).
                # phone → empty (production phones lack ro.kernel.qemu).
                # emu   → "1". Unknown serial fails closed per R24 P1.
                if [ -n "${WSS3_MOCK_PHONE_SERIAL:-}" ] \
                        && [ "$serial" = "$WSS3_MOCK_PHONE_SERIAL" ]; then
                    :  # emit nothing — phone
                elif [ -n "${WSS3_MOCK_EMU_SERIAL:-}" ] \
                        && [ "$serial" = "$WSS3_MOCK_EMU_SERIAL" ]; then
                    echo "1"
                else
                    echo "mock-adb: getprop ro.kernel.qemu unknown serial '$serial' — fail-closed per R24 P1" >&2
                    exit 4
                fi
                ;;
            true)
                # Liveness ping used by wss3_devices_classify. Always OK.
                : ;;
            am\ broadcast\ *)
                # Extract subcommand from --es subcommand X pattern.
                sub=$(printf '%s' "$cmd" | grep -oE '\-\-es subcommand [^ ]+' | awk '{print $NF}')
                case "$sub" in
                    network_profile_state_report)
                        # Audit ROUND-10 P0-1: state-only variant — no
                        # HTTP, no HMAC, no egress_fingerprint. Materialises
                        # a JSON with the state fields ONLY (drops the
                        # egress_fingerprint key entirely).
                        #
                        # Audit ROUND-24 P1: mock fidelity repair. The
                        # canonical fixture is EXPLICITLY split into
                        # phone + emu templates; the mock selects by
                        # exact serial→role mapping (WSS3_MOCK_PHONE_SERIAL
                        # / WSS3_MOCK_EMU_SERIAL). Unknown serial fails
                        # closed. NO fallback to either template.
                        # phone_vpn and host_vpn are independent
                        # dimensions: emu.has_transport_vpn stays False.
                        _tpl=""
                        if [ -n "${WSS3_MOCK_PHONE_SERIAL:-}" ] \
                                && [ "$serial" = "$WSS3_MOCK_PHONE_SERIAL" ]; then
                            _tpl="${WSS3_MOCK_NP_TEMPLATE_PHONE:-${WSS3_MOCK_NETWORK_PROFILE_JSON_TEMPLATE:-}}"
                        elif [ -n "${WSS3_MOCK_EMU_SERIAL:-}" ] \
                                && [ "$serial" = "$WSS3_MOCK_EMU_SERIAL" ]; then
                            _tpl="${WSS3_MOCK_NP_TEMPLATE_EMU:-}"
                        else
                            echo "mock-adb: network_profile_state_report — serial $serial not in explicit mapping (phone=${WSS3_MOCK_PHONE_SERIAL:-<unset>}, emu=${WSS3_MOCK_EMU_SERIAL:-<unset>}); fail-closed per R24 P1" >&2
                            exit 4
                        fi
                        if [ -n "$_tpl" ] && [ -f "$_tpl" ]; then
                            live_path="$_MOCK_STATE_DIR/.mock-adb.$serial.network_profile.json"
                            python3 - "$_tpl" "$live_path" <<'PY'
import json, sys
tpl, out = sys.argv[1], sys.argv[2]
with open(tpl, encoding='utf-8') as f: j = json.load(f)
j.pop("egress_fingerprint", None)
with open(out, "w", encoding='utf-8') as f:
    json.dump(j, f, sort_keys=True, indent=2)
PY
                        fi
                        echo "Broadcasting: Intent { ... network_profile_state_report }"
                        echo "Broadcast completed: result=0"
                        ;;
                    network_profile_report)
                        # Simulate the receiver flushing the report file.
                        # Capture the checkpoint_key_hex extra so the
                        # subsequent `run-as cat` can hand back a JSON
                        # whose hmac_fp_hex matches the host-side
                        # `wss3_host_egress_fingerprint` for the SAME
                        # body (WSS3_EGRESS_BODY_OVERRIDE) — this is
                        # what makes the synthetic dry-run's host↔emu
                        # equality check succeed. The real reporter
                        # does the equivalent with a real HTTP body.
                        #
                        # Audit ROUND-24 P1: pick template by explicit
                        # serial→role mapping. Full-egress path is only
                        # emitted for the EMU serial (the phone side is
                        # state-only per contract §5). Unknown serial
                        # fails closed.
                        _tpl=""
                        if [ -n "${WSS3_MOCK_EMU_SERIAL:-}" ] \
                                && [ "$serial" = "$WSS3_MOCK_EMU_SERIAL" ]; then
                            _tpl="${WSS3_MOCK_NP_TEMPLATE_EMU:-}"
                        elif [ -n "${WSS3_MOCK_PHONE_SERIAL:-}" ] \
                                && [ "$serial" = "$WSS3_MOCK_PHONE_SERIAL" ]; then
                            # Phone side legitimately receives
                            # `network_profile_report` too but its
                            # egress-suppressed template is used.
                            _tpl="${WSS3_MOCK_NP_TEMPLATE_PHONE:-${WSS3_MOCK_NETWORK_PROFILE_JSON_TEMPLATE:-}}"
                        else
                            echo "mock-adb: network_profile_report — serial $serial not in explicit mapping (phone=${WSS3_MOCK_PHONE_SERIAL:-<unset>}, emu=${WSS3_MOCK_EMU_SERIAL:-<unset>}); fail-closed per R24 P1" >&2
                            exit 4
                        fi
                        ck_hex=$(printf '%s' "$cmd" | grep -oE '\-\-es checkpoint_key_hex [0-9a-f]+' | awk '{print $NF}')
                        if [ -n "${WSS3_EGRESS_BODY_OVERRIDE:-}" ] && [ -n "$ck_hex" ] && [ -n "$_tpl" ] && [ -f "$_tpl" ]; then
                            hmac=$(WSS3_KEY_HEX_TMP="$ck_hex" WSS3_BODY_TMP="$WSS3_EGRESS_BODY_OVERRIDE" python3 - <<'PY'
import os, hmac, hashlib
k = os.environ["WSS3_KEY_HEX_TMP"]; b = os.environ["WSS3_BODY_TMP"].encode("utf-8")
print(hmac.new(bytes.fromhex(k), b, hashlib.sha256).digest()[:16].hex())
PY
)
                            # Materialise the fresh JSON path the next
                            # `run-as cat` will read from. Uses the
                            # template + hmac override.
                            live_path=$(_spec_get network_profile_json_live_out)
                            [ -n "$live_path" ] || live_path="$_MOCK_STATE_DIR/.mock-adb.$serial.network_profile.json"
                            python3 - "$_tpl" "$live_path" "$hmac" <<'PY'
import json, sys
tpl, out, h = sys.argv[1], sys.argv[2], sys.argv[3]
with open(tpl, encoding="utf-8") as f:
    j = json.load(f)
# Audit ROUND-20 P0-1 + ROUND-21 P0-1: mock MUST emit the FULL
# real reporter egress_fingerprint shape AND pinned constants.
# Source pins: DiagnosticNetworkProfileReporter.kt:59
# (EGRESS_TIMEOUT_MS = 5000) and :557-573 (buildJson egress
# block). ROUND-20's `timeout_ms_used=4000` disagreed with the
# production constant and would mask any timeout regression.
egf = j.setdefault("egress_fingerprint", {})
egf["endpoint"] = "api4.ipify.org"
egf["address_family"] = "AF_INET"
egf["hmac_fp_hex"] = h
egf["at_wall_ms"] = j.get("at_wall_ms", 1786817000000)
egf["http_status"] = 200
egf["timeout_ms_used"] = 5000
with open(out, "w", encoding="utf-8") as f:
    json.dump(j, f, sort_keys=True, indent=2)
PY
                            # Point the spec's network_profile_json at
                            # the freshly-rendered path so `cat` below
                            # returns the right hmac.
                            printf 'network_profile_json=%s\n' "$live_path" >> "$SPEC"
                        fi
                        echo "Broadcasting: Intent { ... network_profile_report }"
                        echo "Broadcast completed: result=0"
                        ;;
                    paired_count_report)
                        # Simulate the receiver logging via WSS_DIAG_CMD.
                        # The count is asserted by the caller reading logcat.
                        cnt=$(_spec_get paired_count)
                        [ -n "$cnt" ] || cnt=1
                        # Append to the logcat pre-buffer so the next
                        # snapshot picks it up.
                        buf="$_MOCK_STATE_DIR/.mock-adb.$serial.logcat-buf"
                        echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG_CMD: paired_count_report count=${cnt}" >> "$buf"
                        echo "Broadcast completed: result=0"
                        ;;
                    signed_prekey_readiness)
                        pub=$(_spec_get signed_prekey_published)
                        [ -n "$pub" ] || pub=true
                        buf="$_MOCK_STATE_DIR/.mock-adb.$serial.logcat-buf"
                        echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG_CMD: signed_prekey_readiness published=${pub} signed_prekey_age_days=1 remaining_opks=99" >> "$buf"
                        echo "Broadcast completed: result=0"
                        ;;
                    pin)
                        # Record the pin state on this serial so a
                        # subsequent `send` can emit REST or WSS
                        # route-return events accordingly AND so
                        # recipient events carry this serial's own
                        # cell_id (audit ROUND-29 P0: recipient events
                        # derive cell_id from the recipient's local
                        # pin — the real-device behaviour behind the
                        # old simultaneous-pin false-attribution
                        # defect). Also flush a diagnostic_pin_active
                        # line so verifier sees the pin transition.
                        pin=$(printf '%s' "$cmd" | grep -oE '\-\-es pin [^ ]+' | awk '{print $NF}')
                        run_id=$(printf '%s' "$cmd" | grep -oE '\-\-es run_id [^ ]+' | awk '{print $NF}')
                        cell_id=$(printf '%s' "$cmd" | grep -oE '\-\-es cell_id [^ ]+' | awk '{print $NF}')
                        # ── Audit ROUND-30.11: the mock validates what
                        # the real receiver validates ────────────────
                        # `DiagnosticCommandReceiver.handlePin` returns
                        # early unless run_id AND cell_id are both
                        # present and well formed, whatever the pin
                        # value is — `pin=none` included. The mock used
                        # to ACCEPT a clear with neither, clear its
                        # state and return success, so the runner's
                        # invalid clear passed every simulated run and
                        # was rejected by both real devices. A mock that
                        # is more permissive than the thing it stands in
                        # for does not test the caller, it hides it.
                        #
                        # validRunId : 1..64 of [A-Za-z0-9._-]
                        # validCellId: 1..128 of [A-Za-z0-9._:-]
                        # ALLOWED_PINS: none | wss | rest
                        _p_valid=1
                        case "$pin" in
                            none|wss|rest) ;;
                            *) _p_valid=0 ;;
                        esac
                        case "$run_id" in
                            "") _p_valid=0 ;;
                            *[!A-Za-z0-9._-]*) _p_valid=0 ;;
                        esac
                        case "$cell_id" in
                            "") _p_valid=0 ;;
                            *[!A-Za-z0-9._:-]*) _p_valid=0 ;;
                        esac
                        if [ "${#run_id}" -gt 64 ] || [ "${#cell_id}" -gt 128 ]; then
                            _p_valid=0
                        fi
                        if [ "$_p_valid" = "0" ]; then
                            # The real receiver logs the rejection and
                            # writes nothing: no state change, no
                            # breadcrumb. The broadcast itself still
                            # "completes" from adb's point of view,
                            # which is exactly why the caller has to
                            # confirm the breadcrumb rather than trust
                            # the exit code.
                            echo "mock-adb: rejected: pin subcommand invalid input (pin=$pin runId=$run_id cellId=$cell_id)" >&2
                            echo "Broadcast completed: result=0"
                            exit 0
                        fi
                        # ── Audit ROUND-29.1 P1-3 fixture knobs ──────
                        # R29 suppressed pin output and dispatched
                        # immediately, so a failed pin was invisible.
                        # These knobs make both failure shapes — a
                        # non-zero broadcast and a missing/mismatched
                        # breadcrumb — reproducible under the mock.
                        # `_FAIL_EMITTER` (phone|emulator|both,
                        # default both) selects which side misbehaves
                        # so the bounded `pre_send_pin_emitter` value
                        # is itself under test. Resolved BEFORE any
                        # state is written so a rejected pin leaves no
                        # trace, exactly as on a device.
                        _p_emit=phone; _p_i=0
                        for _d in $(_spec_get devices); do
                            if [ "$_d" = "$serial" ]; then
                                if [ "$_p_i" = "0" ]; then _p_emit=phone; else _p_emit=emulator; fi
                                break
                            fi
                            _p_i=$((_p_i+1))
                        done
                        _p_target="${WSS3_MOCK_PIN_FAIL_EMITTER:-both}"
                        _p_selected=0
                        if [ "$pin" != "none" ]; then
                            if [ "$_p_target" = "both" ] || [ "$_p_target" = "$_p_emit" ]; then
                                _p_selected=1
                            fi
                        fi
                        # Audit ROUND-29.2 P1-2: scope a pin failure to
                        # ONE cell. Without this the full matrix can
                        # never reach its `rest.*.control` cells, so the
                        # `rest` expected-pin path would stay untested.
                        if [ "$_p_selected" = "1" ] && [ -n "${WSS3_MOCK_PIN_FAIL_CELL:-}" ]; then
                            if [ "$cell_id" != "$WSS3_MOCK_PIN_FAIL_CELL" ]; then
                                _p_selected=0
                            fi
                        fi
                        _p_rc="${WSS3_MOCK_PIN_RC:-0}"
                        if [ "$_p_selected" = "1" ] && [ "$_p_rc" != "0" ]; then
                            echo "mock-adb: pin broadcast failed (WSS3_MOCK_PIN_RC=$_p_rc)" >&2
                            exit "$_p_rc"
                        fi
                        # ── Audit ROUND-30.11: a CLEAR that does not
                        # take effect ────────────────────────────────
                        # The knobs above deliberately exclude
                        # `pin=none`, so before this there was NO way
                        # to make a clear fail under the mock — and a
                        # rule about unconfirmed clears that cannot be
                        # made to fail is a rule nobody has watched
                        # fail. These knobs are separate from the pin
                        # ones on purpose: widening `_p_selected` to
                        # cover `none` would silently re-target every
                        # existing pin fixture at the clears too.
                        #
                        #   WSS3_MOCK_CLEAR_FAIL_EMITTER
                        #       phone | emulator | both. UNSET means no
                        #       clear ever fails, so untouched fixtures
                        #       keep their behaviour exactly.
                        #   WSS3_MOCK_CLEAR_FAIL_CELL
                        #       optional DERIVED clear id (`<cell>.clear`)
                        #       scoping the failure to one clear.
                        #
                        # The shape reproduced is the real one: adb
                        # reports the broadcast completed, and the
                        # receiver never acts — no state change and no
                        # breadcrumb. Only the breadcrumb can tell the
                        # caller the difference, which is exactly why
                        # the runner has to confirm it.
                        _c_selected=0
                        if [ "$pin" = "none" ] && [ -n "${WSS3_MOCK_CLEAR_FAIL_EMITTER:-}" ]; then
                            _c_target="$WSS3_MOCK_CLEAR_FAIL_EMITTER"
                            if [ "$_c_target" = "both" ] || [ "$_c_target" = "$_p_emit" ]; then
                                _c_selected=1
                            fi
                            if [ "$_c_selected" = "1" ] && [ -n "${WSS3_MOCK_CLEAR_FAIL_CELL:-}" ]; then
                                if [ "$cell_id" != "$WSS3_MOCK_CLEAR_FAIL_CELL" ]; then
                                    _c_selected=0
                                fi
                            fi
                        fi
                        if [ "$_c_selected" = "1" ]; then
                            echo "mock-adb: clear withheld (emitter=$_p_emit cell_id=$cell_id)" >&2
                            echo "Broadcast completed: result=0"
                            exit 0
                        fi
                        # Audit ROUND-29: `pin --es pin none` clears
                        # the persisted pin AND local cell_id so a
                        # subsequent send on a different cell does
                        # not inherit a stale recipient attribution.
                        if [ "$pin" = "none" ]; then
                            rm -f "$_MOCK_STATE_DIR/.mock-adb.$serial.pin" \
                                  "$_MOCK_STATE_DIR/.mock-adb.$serial.cell_id" \
                                  "$_MOCK_STATE_DIR/.mock-adb.$serial.run_id"
                        else
                            printf '%s\n' "$pin"     > "$_MOCK_STATE_DIR/.mock-adb.$serial.pin"
                            printf '%s\n' "$run_id"  > "$_MOCK_STATE_DIR/.mock-adb.$serial.run_id"
                            printf '%s\n' "$cell_id" > "$_MOCK_STATE_DIR/.mock-adb.$serial.cell_id"
                        fi
                        buf="$_MOCK_STATE_DIR/.mock-adb.$serial.logcat-buf"
                        wall=$(python3 -c 'import time; print(int(time.time()*1000))')
                        # Audit ROUND-18 P0-5: map $serial → canonical
                        # `phone`/`emulator` for emitter_id. The
                        # accepted WSS-2 schema permits only these two
                        # matrix-emitter identities; a raw device
                        # serial (e.g. `mock-phone-serial`) is a
                        # schema violation. First device in the
                        # `devices` spec is `phone`, second is
                        # `emulator`.
                        devs=$(_spec_get devices)
                        emitter_id=phone
                        i=0
                        for d in $devs; do
                            if [ "$d" = "$serial" ]; then
                                if [ "$i" = "0" ]; then emitter_id=phone; else emitter_id=emulator; fi
                                break
                            fi
                            i=$((i+1))
                        done
                        # ── Audit ROUND-30.11 ────────────────────────
                        # R29 suppressed the breadcrumb for `pin=none`
                        # because the runner sent that clear WITHOUT
                        # run_id/cell_id, and an event with empty ids
                        # would have violated the schema. The runner
                        # now sends a valid clear, and the real
                        # receiver emits `diagnostic_pin_active` for
                        # EVERY accepted pin including NONE — that
                        # breadcrumb is the only proof a clear
                        # actually happened. Suppressing it here made
                        # a confirmed clear unobservable under the
                        # mock, so no fixture could have caught the
                        # invalid form. Invalid input is rejected
                        # above; anything that reaches here is valid
                        # and gets its breadcrumb.
                        if true; then
                            # Audit ROUND-29.1 P1-3 fixture knobs:
                            #   _PIN_SILENT       — rc=0 but NO
                            #     breadcrumb (pin silently ignored);
                            #   _PIN_CELL_OVERRIDE — breadcrumb for a
                            #     DIFFERENT cell (mismatched pin).
                            # Both must leave the confirmation gate
                            # fail-closed with zero dispatch.
                            _p_bc_cell="$cell_id"
                            if [ "$_p_selected" = "1" ] && [ -n "${WSS3_MOCK_PIN_CELL_OVERRIDE:-}" ]; then
                                _p_bc_cell="$WSS3_MOCK_PIN_CELL_OVERRIDE"
                            fi
                            if [ "$_p_selected" = "1" ] && [ "${WSS3_MOCK_PIN_SILENT:-0}" = "1" ]; then
                                : # breadcrumb deliberately withheld
                            else
                                # Field ORDER matches the real emitter:
                                # `WssDiag.emit` appends pin AFTER the
                                # timestamps, and `handlePin` passes no
                                # later field, so `pin=` ends the line.
                                # The mock used to print it mid-line,
                                # which is the second reason a
                                # trailing-space match passed here and
                                # failed on a device.
                                echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=diagnostic_pin_active role=matrix emitter_id=$emitter_id run_id=$run_id cell_id=$_p_bc_cell wall_utc_ms=$wall monotonic_ms=$wall pin=$pin" >> "$buf"
                            fi
                        fi
                        echo "Broadcast completed: result=0"
                        ;;
                    send)
                        # Simulate the receiver dispatching the send and
                        # emit a full round-trip: sender-side breadcrumbs
                        # + recipient triplet with exactly one fresh.
                        # The recipient events are appended to the
                        # OTHER serial's buffer (peer emitter).
                        run_id=$(printf '%s' "$cmd" | grep -oE '\-\-es run_id [^ ]+' | awk '{print $NF}')
                        cell_id=$(printf '%s' "$cmd" | grep -oE '\-\-es cell_id [^ ]+' | awk '{print $NF}')
                        seq=$(printf '%s' "$cmd" | grep -oE '\-\-ei sequence [0-9]+' | awk '{print $NF}')
                        pin=$(cat "$_MOCK_STATE_DIR/.mock-adb.$serial.pin" 2>/dev/null || echo wss)
                        # Emitter mapping: phone <-> emulator symmetric.
                        # `emitter_id` derived from the cell_id direction.
                        case "$cell_id" in
                            *.p2e.*) sender_emitter=phone; recipient_emitter=emulator ;;
                            *.e2p.*) sender_emitter=emulator; recipient_emitter=phone ;;
                            *) sender_emitter=phone; recipient_emitter=emulator ;;
                        esac
                        # Find the peer serial by scanning the spec for
                        # 'devices' — the OTHER one is the peer.
                        devs=$(_spec_get devices)
                        peer_serial=""
                        for d in $devs; do
                            [ "$d" = "$serial" ] || peer_serial="$d"
                        done
                        [ -n "$peer_serial" ] || peer_serial="$serial"
                        # CID includes run_id per audit ROUND-9 P0-11 —
                        # cross-run collisions are impossible.
                        cid="cid-${run_id}-${cell_id//./-}-${seq}"
                        # Audit ROUND-29 P0: recipient events derive
                        # cell_id from the RECIPIENT's own local pin
                        # (this is the real-device behaviour behind
                        # the old simultaneous-pin false-attribution
                        # defect). Falls back to the message's
                        # cell_id when the recipient has no live pin.
                        recipient_cell_id=$(cat "$_MOCK_STATE_DIR/.mock-adb.$peer_serial.cell_id" 2>/dev/null || printf '')
                        [ -n "$recipient_cell_id" ] || recipient_cell_id="$cell_id"
                        buf_s="$_MOCK_STATE_DIR/.mock-adb.$serial.logcat-buf"
                        buf_r="$_MOCK_STATE_DIR/.mock-adb.$peer_serial.logcat-buf"
                        wall_base=$(python3 -c 'import time; print(int(time.time()*1000))')
                        inner="wss"; [ "$pin" = "rest" ] && inner="rest"
                        {
                            echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=sender_send_attempt_started role=sender emitter_id=$sender_emitter run_id=$run_id cell_id=$cell_id correlation_id=$cid sequence=$seq wall_utc_ms=$wall_base monotonic_ms=$wall_base"
                            echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=sender_enqueue role=sender emitter_id=$sender_emitter run_id=$run_id cell_id=$cell_id correlation_id=$cid wall_utc_ms=$((wall_base+1)) monotonic_ms=$((wall_base+1))"
                            echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=$sender_emitter run_id=$run_id cell_id=$cell_id correlation_id=$cid sequence=$seq wall_utc_ms=$((wall_base+2)) monotonic_ms=$((wall_base+2))"
                            echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=sender_transport_decision role=sender emitter_id=$sender_emitter run_id=$run_id cell_id=$cell_id correlation_id=$cid dispatched=true outer_transport=direct inner_route=$inner wall_utc_ms=$((wall_base+3)) monotonic_ms=$((wall_base+3))"
                            # Audit ROUND-30.21: mirror the product's
                            # route-dependent terminal shape. Common dispatch
                            # proof is on sender_transport_decision. WSS repeats
                            # dispatched=true and later receives an async ack;
                            # REST carries HTTP acceptance and neither WSS-only
                            # signal.
                            if [ "$pin" = "rest" ]; then
                                echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=sender_rest_post_completed role=sender emitter_id=$sender_emitter run_id=$run_id cell_id=$cell_id correlation_id=$cid outer_transport=direct inner_route=rest relay_acceptance=accepted wall_utc_ms=$((wall_base+4)) monotonic_ms=$((wall_base+4))"
                            else
                                echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=sender_wss_send_returned role=sender emitter_id=$sender_emitter run_id=$run_id cell_id=$cell_id correlation_id=$cid dispatched=true outer_transport=direct inner_route=wss outcome_flag=none wall_utc_ms=$((wall_base+4)) monotonic_ms=$((wall_base+4))"
                                echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=sender_relay_ack_received role=sender emitter_id=$sender_emitter run_id=$run_id cell_id=$cell_id correlation_id=$cid wall_utc_ms=$((wall_base+5)) monotonic_ms=$((wall_base+5))"
                            fi
                        } >> "$buf_s"
                        # ── Audit ROUND-30.18 ─────────────────────────
                        #
                        # The recipient stamps every event with the
                        # ATTEMPT it belongs to, so the mock does too: a
                        # harness that emits a shape the producer cannot
                        # emit tests the verifier against a world that
                        # does not exist.
                        #
                        # `WSS3_MOCK_DELIVER_FAIL_CELL` makes this cell's
                        # delivery FAIL the way the 2026-08-26 run did:
                        # N fresh attempts, each terminated by a
                        # `recipient_deliver_failed` and none settling.
                        # `WSS3_MOCK_DELIVER_FAIL_ATTEMPTS` chooses N
                        # (default 1). It exists so a fixture can drive
                        # the REAL `preflight → smoke RED → full refused`
                        # path instead of building a successful tree and
                        # deleting files from it.
                        # Audit ROUND-30.18: the two other producer shapes a fixture
                        # must be able to demand.
                        #
                        # `WSS3_MOCK_DELIVER_SILENT_CELL` drops the triplet and
                        # explains nothing -- a short chain with no valid account,
                        # which must come back as `integrity=RED`.
                        #
                        # `WSS3_MOCK_DELIVER_OVERFLOW_CELL` emits the truncation
                        # marker, so a fixture can drive the overflow path without a
                        # thousand real redeliveries.
                        if [ -n "${WSS3_MOCK_DELIVER_SILENT_CELL:-}" ] \
                           && [ "${WSS3_MOCK_DELIVER_SILENT_CELL}" = "$recipient_cell_id" ]; then
                            {
                                echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=recipient_deliver_received role=recipient emitter_id=$recipient_emitter run_id=$run_id cell_id=$recipient_cell_id correlation_id=$cid dedup_gate=fresh attempt=1 wall_utc_ms=$((wall_base+6)) monotonic_ms=$((wall_base+6))"
                            } >> "$buf_r"
                        elif [ -n "${WSS3_MOCK_DELIVER_OVERFLOW_CELL:-}" ] \
                           && [ "${WSS3_MOCK_DELIVER_OVERFLOW_CELL}" = "$recipient_cell_id" ]; then
                            {
                                echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=recipient_deliver_received role=recipient emitter_id=$recipient_emitter run_id=$run_id cell_id=$recipient_cell_id correlation_id=$cid dedup_gate=fresh attempt=1 wall_utc_ms=$((wall_base+6)) monotonic_ms=$((wall_base+6))"
                                echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=recipient_deliver_failed role=recipient emitter_id=$recipient_emitter run_id=$run_id cell_id=$recipient_cell_id correlation_id=$cid attempt=1 deliver_failure=held deliver_stage=received wall_utc_ms=$((wall_base+7)) monotonic_ms=$((wall_base+7))"
                                echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=recipient_deliver_attempt_overflow role=recipient emitter_id=$recipient_emitter run_id=$run_id cell_id=$recipient_cell_id correlation_id=$cid wall_utc_ms=$((wall_base+8)) monotonic_ms=$((wall_base+8))"
                            } >> "$buf_r"
                        elif [ -n "${WSS3_MOCK_DELIVER_FAIL_CELL:-}" ] \
                           && [ "${WSS3_MOCK_DELIVER_FAIL_CELL}" = "$recipient_cell_id" ]; then
                            _mock_fail_n="${WSS3_MOCK_DELIVER_FAIL_ATTEMPTS:-1}"
                            {
                                _mock_a=1
                                while [ "$_mock_a" -le "$_mock_fail_n" ]; do
                                    echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=recipient_deliver_received role=recipient emitter_id=$recipient_emitter run_id=$run_id cell_id=$recipient_cell_id correlation_id=$cid dedup_gate=fresh attempt=$_mock_a wall_utc_ms=$((wall_base+6)) monotonic_ms=$((wall_base+6))"
                                    echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=recipient_deliver_failed role=recipient emitter_id=$recipient_emitter run_id=$run_id cell_id=$recipient_cell_id correlation_id=$cid attempt=$_mock_a deliver_failure=held deliver_stage=received wall_utc_ms=$((wall_base+7)) monotonic_ms=$((wall_base+7))"
                                    _mock_a=$((_mock_a + 1))
                                done
                            } >> "$buf_r"
                        else
                        {
                            echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=recipient_deliver_received role=recipient emitter_id=$recipient_emitter run_id=$run_id cell_id=$recipient_cell_id correlation_id=$cid dedup_gate=fresh attempt=1 wall_utc_ms=$((wall_base+6)) monotonic_ms=$((wall_base+6))"
                            echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=recipient_message_persisted role=recipient emitter_id=$recipient_emitter run_id=$run_id cell_id=$recipient_cell_id correlation_id=$cid attempt=1 wall_utc_ms=$((wall_base+7)) monotonic_ms=$((wall_base+7))"
                            echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=recipient_ack_deliver_sent role=recipient emitter_id=$recipient_emitter run_id=$run_id cell_id=$recipient_cell_id correlation_id=$cid attempt=1 wall_utc_ms=$((wall_base+8)) monotonic_ms=$((wall_base+8))"
                        } >> "$buf_r"
                        fi
                        echo "Broadcast completed: result=0"
                        ;;
                    canary)
                        # Audit ROUND-29 P0-5: canary probe seeds a
                        # sender_transport_decision event so the
                        # pre-send health gate can read outer_transport
                        # without waiting for real traffic. Real device
                        # emits sender_transport_decision on its own
                        # canary path. `WSS3_MOCK_CANARY_OUTER` lets
                        # fixtures drive direct / failed / unknown
                        # without touching product code.
                        _canary_ot="${WSS3_MOCK_CANARY_OUTER:-direct}"
                        _canary_buf="$_MOCK_STATE_DIR/.mock-adb.$serial.logcat-buf"
                        _canary_wall=$(python3 -c 'import time; print(int(time.time()*1000))')
                        _canary_devs=$(_spec_get devices)
                        _canary_emit=phone; _canary_i=0
                        for _d in $_canary_devs; do
                            if [ "$_d" = "$serial" ]; then
                                if [ "$_canary_i" = "0" ]; then _canary_emit=phone; else _canary_emit=emulator; fi
                                break
                            fi
                            _canary_i=$((_canary_i+1))
                        done
                        if [ "$_canary_ot" != "unknown" ]; then
                            _canary_cid="canary-${serial}-${_canary_wall}"
                            echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG: event=sender_transport_decision role=sender emitter_id=$_canary_emit run_id=canary cell_id=canary correlation_id=$_canary_cid dispatched=false outer_transport=$_canary_ot inner_route=wss wall_utc_ms=$_canary_wall monotonic_ms=$_canary_wall" >> "$_canary_buf"
                        fi
                        echo "Broadcast completed: result=0"
                        ;;
                    health)
                        # Audit ROUND-29.1 P0: the pre-send gate now
                        # demands a FRESH `health` readback per device
                        # instead of trusting a historical
                        # sender_transport_decision. Mirror the real
                        # producer exactly — DiagnosticCommandReceiver
                        # .handleHealth() logs ONE line under the
                        # WSS_DIAG_CMD tag carrying emitter_id, pin,
                        # run_id, cell_id and outer_transport, reading
                        # live guard state. The mock therefore reports
                        # its OWN persisted pin state, which is what
                        # makes fail-closed pin confirmation testable.
                        #
                        # Fixture knobs (never product code):
                        #   WSS3_MOCK_HEALTH_RC     — non-zero → the
                        #       broadcast itself fails.
                        #   WSS3_MOCK_HEALTH_SILENT — rc=0 but NO line
                        #       is emitted (probe produced nothing).
                        #   WSS3_MOCK_HEALTH_OUTER  — outer_transport
                        #       for both sides; the _PHONE / _EMU
                        #       variants override per side so the
                        #       per-device disagreement case is
                        #       reproducible.
                        _h_rc="${WSS3_MOCK_HEALTH_RC:-0}"
                        if [ "$_h_rc" != "0" ]; then
                            echo "mock-adb: health broadcast failed (WSS3_MOCK_HEALTH_RC=$_h_rc)" >&2
                            exit "$_h_rc"
                        fi
                        _h_emit=phone; _h_i=0
                        for _d in $(_spec_get devices); do
                            if [ "$_d" = "$serial" ]; then
                                if [ "$_h_i" = "0" ]; then _h_emit=phone; else _h_emit=emulator; fi
                                break
                            fi
                            _h_i=$((_h_i+1))
                        done
                        if [ "$_h_emit" = "phone" ]; then
                            _h_ot="${WSS3_MOCK_HEALTH_OUTER_PHONE:-${WSS3_MOCK_HEALTH_OUTER:-direct}}"
                        else
                            _h_ot="${WSS3_MOCK_HEALTH_OUTER_EMU:-${WSS3_MOCK_HEALTH_OUTER:-direct}}"
                        fi
                        if [ "${WSS3_MOCK_HEALTH_SILENT:-0}" != "1" ]; then
                            # Same fallbacks as the real reporter:
                            # Pin.NONE prints `none`; empty runId /
                            # cellId print `-`.
                            _h_pin=$(cat "$_MOCK_STATE_DIR/.mock-adb.$serial.pin" 2>/dev/null || printf 'none')
                            _h_rid=$(cat "$_MOCK_STATE_DIR/.mock-adb.$serial.run_id" 2>/dev/null || printf '%s' '-')
                            _h_cid=$(cat "$_MOCK_STATE_DIR/.mock-adb.$serial.cell_id" 2>/dev/null || printf '%s' '-')
                            [ -n "$_h_pin" ] || _h_pin=none
                            [ -n "$_h_rid" ] || _h_rid='-'
                            [ -n "$_h_cid" ] || _h_cid='-'
                            echo "08-14 12:00:00.000  1234  1234 I WSS_DIAG_CMD: health emitter_id=$_h_emit pin=$_h_pin run_id=$_h_rid cell_id=$_h_cid outer_transport=$_h_ot" \
                                >> "$_MOCK_STATE_DIR/.mock-adb.$serial.logcat-buf"
                        fi
                        echo "Broadcast completed: result=0"
                        ;;
                    dual_sim_report|clear|checkpoint|set_emitter_id)
                        # Accepted WSS-2 + WSS-3 commands — no-op OK.
                        echo "Broadcast completed: result=0"
                        ;;
                    *)
                        # Audit ROUND-9 P0-11: unknown subcommand FAILS.
                        echo "mock-adb: unknown subcommand: $sub" >&2
                        exit 3
                        ;;
                esac
                ;;
            run-as\ phantom.android\ cat\ *)
                # Prefer the per-serial live-out (freshly rendered by
                # the previous `network_profile_report` broadcast on
                # this SAME serial). Falls back to the spec-referenced
                # path for tests that pre-seed a static JSON.
                per_serial="$_MOCK_STATE_DIR/.mock-adb.$serial.network_profile.json"
                if [ -f "$per_serial" ]; then
                    cat "$per_serial"
                else
                    p=$(_spec_get network_profile_json)
                    if [ -n "$p" ] && [ -f "$p" ]; then
                        cat "$p"
                    fi
                fi
                ;;
            run-as\ phantom.android\ rm\ *)
                # Log the rm for the "always-rm" fixture; produce no
                # stdout so the caller doesn't see leaked paths.
                if [ -n "${WSS3_MOCK_ADB_RM_LOG:-}" ]; then
                    printf 'rm %s %s\n' "$serial" "$cmd" >> "$WSS3_MOCK_ADB_RM_LOG"
                fi
                ;;
            *)
                # Unrecognised shell command — no-op.
                ;;
        esac
        ;;
    logcat)
        # Two sub-modes: `-d ...` (dump) or `-c` (clear).
        if [ "$#" -ge 1 ] && [ "$1" = "-c" ]; then
            rm -f "$_MOCK_STATE_DIR/.mock-adb.$serial.logcat-buf" 2>/dev/null || true
            exit 0
        fi
        # Dump mode: emit the pre-baked snapshot file (if configured)
        # PLUS anything accumulated in the per-serial buffer.
        snap=$(_spec_get logcat_snapshot)
        if [ -n "$snap" ] && [ -f "$snap" ]; then
            cat "$snap"
        fi
        buf="$_MOCK_STATE_DIR/.mock-adb.$serial.logcat-buf"
        if [ -f "$buf" ]; then
            cat "$buf"
        fi
        ;;
    *)
        # Unrecognised subverb — no-op.
        ;;
esac
exit 0
