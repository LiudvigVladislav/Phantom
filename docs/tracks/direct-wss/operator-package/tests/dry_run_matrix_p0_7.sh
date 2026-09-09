#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 audit ROUND-11 P0-#2 — REAL mock-backed orchestrator dry-run.
#
# Architect ROUND-11 verdict:
#   > The claimed end-to-end dry-run does not run the orchestrator.
#   > dry_run_matrix_p0_7.py generates ready-made evidence through
#   > build_full_profile_evidence, then runs only the comparator.
#   > preflight -> smoke -> full, wss3_live_full, and the mock ADB
#   > production path are not executed.
#
# This script REPLACES the synthetic Python harness. It drives the
# actual runner (`run-carrier-vpn-matrix.sh`) through the four public
# verbs — `preflight → smoke → full → compare` — for all 8 canonical
# profiles under WSS3_DRY_RUN=1 with the mock ADB backend + fake
# scutil/route + hermetic runtime/evidence roots. Any orchestrator
# bug (e.g. audit ROUND-11 P0-#1 `snap` unbound) surfaces immediately
# because the same code path that runs on real devices runs here.
#
# Positive run: 8 profiles → compare rc=0 with 8/8 GREEN/GREEN.
# Negative run: 8 profiles + 1 planted defect → compare rc!=0.
#
# STOP invariants preserved: no ADB against real devices, no APK
# rebuild, no push, no PR. Everything runs off the mock backend.
#
# Usage:
#   bash tests/dry_run_matrix_p0_7.sh                  # positive only
#   bash tests/dry_run_matrix_p0_7.sh --positive       # explicit positive
#   bash tests/dry_run_matrix_p0_7.sh --negative       # negative only
#   bash tests/dry_run_matrix_p0_7.sh --both           # both, exit 0 iff both correct
#   bash tests/dry_run_matrix_p0_7.sh --keep-evidence <dir>
#                                                      # copy evidence tree here on success
#
# Exit code:
#   0 iff every requested run behaved as required
#   non-zero on any deviation

set -eo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_ROOT="$(cd "$HERE/.." && pwd)"

# Audit ROUND-13 P1: the trap must combine mock-state cleanup AND
# the per-run scratch cleanup (previously the second `trap` at line
# ~322 overwrote the first, leaving lib/.mock-adb.* files behind
# after every run). Register a composite trap that runs both.
# Sub-cleanups may reference variables not yet defined — quote-escape
# them so the trap body reads them at trap-fire time, not now.
#
# Audit ROUND-30.18: the mock-state leg is gone because the state is
# gone from lib/. Mutable mock state lives in WSS3_MOCK_STATE_DIR
# inside the per-run scratch (see env_common), so the scratch leg
# below removes it — and if this trap never fires, the abandoned
# scratch still cannot poison another run, because every run derives
# a fresh path. Cleanup is a courtesy, not a precondition.
_SCRATCH_TO_CLEAN=""
_composite_cleanup() {
    if [ -n "$_SCRATCH_TO_CLEAN" ]; then
        # shellcheck disable=SC2086
        rm -rf $_SCRATCH_TO_CLEAN 2>/dev/null || true
    fi
}
trap '_composite_cleanup' EXIT

MODE="positive"
KEEP_EVIDENCE=""
# Audit ROUND-12 P1-#8: default is ALL 8 profiles through the real
# orchestrator — the ROUND-11 default `--profiles 1 + synth 7`
# reduced the guarantee to one profile and was flagged. The
# synthesis fallback is removed; every dry-run must exercise the
# real preflight → smoke → full → compare loop for every profile.
# `--profiles N` remains for developer smoke-checks only (fails the
# audit gate if <8; the runner will decline to write a "PASS"
# statement in that mode).
PROFILES_TO_RUN=8
while [ $# -gt 0 ]; do
    case "$1" in
        --positive) MODE="positive" ;;
        --negative) MODE="negative" ;;
        --both)     MODE="both" ;;
        --profiles) PROFILES_TO_RUN="$2"; shift ;;
        --all-profiles) PROFILES_TO_RUN=8 ;;
        --keep-evidence) KEEP_EVIDENCE="$2"; shift ;;
        -h|--help)
            sed -n '2,32p' "$0"; exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
    shift
done

# The 8 canonical profiles + their preflight args.
# Format: <carrier>|<expected_operator_numeric>|<phone_vpn>|<host_vpn>|<confirm>
PROFILES=(
    "YOTA|25011|off|on|CONFIRM-YOTA-PHONE-OFF-HOST-ON"
    "YOTA|25011|off|off|CONFIRM-YOTA-PHONE-OFF-HOST-OFF"
    "YOTA|25011|on|off|CONFIRM-YOTA-PHONE-ON-HOST-OFF"
    "YOTA|25011|on|on|CONFIRM-YOTA-PHONE-ON-HOST-ON"
    "TELE2|25020|off|on|CONFIRM-TELE2-PHONE-OFF-HOST-ON"
    "TELE2|25020|off|off|CONFIRM-TELE2-PHONE-OFF-HOST-OFF"
    "TELE2|25020|on|off|CONFIRM-TELE2-PHONE-ON-HOST-OFF"
    "TELE2|25020|on|on|CONFIRM-TELE2-PHONE-ON-HOST-ON"
)

# ── mock scutil/route builders ─────────────────────────────────
# The host-vpn helper (lib/wss3-host-vpn.sh) shells out to $SCUTIL
# and $ROUTE. For a dry-run we fabricate scripts that produce the
# expected shape.

_mkscutil() {
    # $1 = out path; $2 = "connected" or "disconnected"
    # host_vpn=off ALSO needs the SecureVPN service to EXIST (per
    # wss3-host-vpn.sh:148 wss3_hostvpn_service_exists check) — just
    # listed as (Disconnected), NOT absent. An absent service returns
    # `host_vpn_unverifiable`, which is a legitimate failure mode
    # but not what we're modelling for the positive dry-run.
    local out="$1" state="$2"
    local list_state="Disconnected"
    [ "$state" = "connected" ] && list_state="Connected"
    cat > "$out" <<EOF
#!/usr/bin/env bash
case "\$1 \$2" in
  "--nc list")
    echo '* ($list_state) 00000000-0000-0000-0000-000000000000 IPSec "SecureVPN" [IPSec]'
    ;;
  "--nc status")
    if [ "\$3" = "SecureVPN" ]; then
      if [ "$state" = "connected" ]; then
        echo "Connected"; echo "IfName: utun3"
      else
        echo "Disconnected"
      fi
    fi
    ;;
  "--nc show")
    if [ "\$3" = "SecureVPN" ] && [ "$state" = "connected" ]; then
      echo "IfName: utun3"
    fi
    ;;
esac
EOF
    chmod +x "$out"
}

# Write a per-profile network_profile.json template that the mock
# adb layer's `network_profile_state_report` handler consumes.
# The mock strips the `egress_fingerprint` key (audit ROUND-10 P0-1);
# the rest is passed through unchanged, so this must match what the
# verifier expects for the phone (wifi off, mobile data on, VPN
# active matching profile, active_data_sim_operator_numeric matching
# carrier).
# Audit ROUND-24 P1/P2: two EXPLICIT templates — phone and emu.
# The former reflects the operator's phone_vpn setting; the latter
# ALWAYS emits `has_transport_vpn=false` per contract §5. See
# architect ROUND-24 architecture note: phone_vpn and host_vpn are
# independent dimensions; host_vpn is proven only by host-side
# scutil/route + host↔emu HMAC parity, NEVER by an Android
# TRANSPORT_VPN observation inside the emulator.
_write_network_profile_template_phone() {
    local out="$1" op="$2" pvpn="$3"
    local vpn_bool="false"; [ "$pvpn" = "on" ] && vpn_bool="true"
    local fx="$PKG_ROOT/fixtures/canonical_network_profile.phone.json"
    if [ ! -f "$fx" ]; then
        echo "canonical phone fixture missing at $fx" >&2
        return 1
    fi
    WSS3_FX="$fx" WSS3_OUT="$out" WSS3_OP="$op" WSS3_VPN="$vpn_bool" \
        python3 - <<'PY'
import json, os
with open(os.environ["WSS3_FX"], encoding="utf-8") as f:
    j = json.load(f)
j["active_data_sim_operator_numeric"] = os.environ["WSS3_OP"]
j["has_transport_vpn"] = (os.environ["WSS3_VPN"] == "true")
with open(os.environ["WSS3_OUT"], "w", encoding="utf-8") as f:
    json.dump(j, f, sort_keys=True, indent=2)
PY
}

_write_network_profile_template_emu() {
    # Emu template: identity keys match the phone template's
    # per-profile op/carrier BUT `has_transport_vpn` is ALWAYS
    # false. The emu-side egress fingerprint is later stamped in
    # by lib/wss3-adb-mock.sh's `network_profile_report` path.
    local out="$1" op="$2"
    local fx="$PKG_ROOT/fixtures/canonical_network_profile.phone.json"
    if [ ! -f "$fx" ]; then
        echo "canonical phone fixture missing at $fx" >&2
        return 1
    fi
    WSS3_FX="$fx" WSS3_OUT="$out" WSS3_OP="$op" \
        python3 - <<'PY'
import json, os
with open(os.environ["WSS3_FX"], encoding="utf-8") as f:
    j = json.load(f)
j["active_data_sim_operator_numeric"] = os.environ["WSS3_OP"]
j["has_transport_vpn"] = False
with open(os.environ["WSS3_OUT"], "w", encoding="utf-8") as f:
    json.dump(j, f, sort_keys=True, indent=2)
PY
}

_mkroute() {
    # $1 = out path; $2 = interface (utun3 for on, en0 for off)
    local out="$1" iface="$2"
    cat > "$out" <<EOF
#!/usr/bin/env bash
if [ "\$1 \$2" = "-n get" ]; then
    echo "   route to: \$3"
    echo "destination: default"
    echo "interface: $iface"
fi
EOF
    chmod +x "$out"
}

# _wss3_p07_verify_profile <evidence-root> <profile-id> <pkg-root>
#   Audit ROUND-30.18: the verifier account of a profile, printed the
#   same way whether the run stopped at the smoke or completed the
#   matrix. Returns non-zero when the profile is not GREEN/GREEN; the
#   caller decides what that means for its own exit code.
_wss3_p07_verify_profile() {
    local _ev="$1" _pid="$2" _pkg="$3" _dir
    _dir=$(ls -d "$_ev/${_pid}-"* 2>/dev/null | tail -1)
    if [ -z "$_dir" ] || [ ! -d "$_dir" ]; then
        echo "  verify FAILED for $_pid: no evidence directory" >&2
        return 2
    fi
    WSS3_VERIFY_DIR="$_dir" WSS3_PKG_ROOT="$_pkg" python3 - <<'PY'
import os, sys
sys.path.insert(0, os.environ.get("WSS3_PKG_ROOT", "."))
import verify_evidence_wss3 as V
rep = V.verify_profile_dir(os.environ["WSS3_VERIFY_DIR"])
print(f"  verify: evidence_integrity="
      f"{'GREEN' if rep.integrity_ok else 'RED'} "
      f"product_outcome={rep.product_outcome} "
      f"cells_ran={rep.cells_ran}/{rep.cells_expected_to_run}")
for issue in rep.integrity_issues[:20]:
    print(f"    - {issue}")
sys.exit(0 if (rep.integrity_ok and rep.product_outcome == "GREEN") else 1)
PY
}

# ── one full run of the matrix (positive control shape) ────────

_run_matrix() {
    local scratch="$1" label="$2"
    local ev="$scratch/evidence"
    local rt="$scratch/.runtime/wss3"
    mkdir -p "$ev" "$rt"
    chmod 0700 "$scratch/.runtime" 2>/dev/null || true

    local scutil_on="$scratch/scutil-on"
    local scutil_off="$scratch/scutil-off"
    local route_on="$scratch/route-on"
    local route_off="$scratch/route-off"
    _mkscutil "$scutil_on"  "connected"
    _mkscutil "$scutil_off" "disconnected"
    _mkroute  "$route_on"   "utun3"
    _mkroute  "$route_off"  "en0"

    # Mock adb spec — `devices` line drives peer-serial resolution
    # inside the send handler (lib/wss3-adb-mock.sh:206); without
    # this, recipient triplet events land on the SAME serial as the
    # sender, so wait_for_cid_completion on the RECIPIENT serial
    # times out and envelopes_recipient_triplet_complete stays 0.
    local mock_spec="$scratch/mock-adb.spec"
    # Audit ROUND-30.18: mutable mock state is scoped to THIS run's
    # scratch and passed explicitly. One spec serves all 8 profiles,
    # so the state dir — not lib/ — is what the inter-profile purge
    # empties.
    local mock_state="$scratch/mock-state"
    cat > "$mock_spec" <<EOF
devices=mock-phone-serial mock-emu-serial
apk_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
paired_count=1
signed_prekey_published=true
EOF

    local per_profile_rc=0 verbs_rc=0
    local ran_count=0
    for entry in "${PROFILES[@]}"; do
        if [ "$ran_count" -ge "$PROFILES_TO_RUN" ]; then break; fi
        ran_count=$((ran_count+1))
        local carrier op pvpn hvpn confirm
        IFS='|' read -r carrier op pvpn hvpn confirm <<< "$entry"
        local pid
        pid="$(echo "$carrier" | tr '[:upper:]' '[:lower:]')-phone-${pvpn}-host-${hvpn}"
        printf '  == %s / phone-vpn=%s / host-vpn=%s ==\n' "$carrier" "$pvpn" "$hvpn"

        # Purge inter-profile mock state — the mock adb layer parks
        # per-serial state files in WSS3_MOCK_STATE_DIR (audit
        # ROUND-30.18; previously lib/.mock-adb.SERIAL.*); stale
        # files from a prior profile cross-contaminate the next
        # preflight/full pair, so the shared dir is emptied here.
        rm -rf "$mock_state" 2>/dev/null || true
        mkdir -p "$mock_state"

        local scutil="$scutil_off"; local route="$route_off"
        if [ "$hvpn" = "on" ]; then scutil="$scutil_on"; route="$route_on"; fi

        # Audit ROUND-24 P1/P2: two EXPLICIT per-serial templates.
        # phone template carries has_transport_vpn = requested
        # phone_vpn; emu template ALWAYS carries has_transport_vpn
        # = false. `lib/wss3-adb-mock.sh` picks by serial via the
        # explicit WSS3_MOCK_PHONE_SERIAL / WSS3_MOCK_EMU_SERIAL
        # mapping and fails closed on unknown serial.
        local npt_phone="$scratch/np-template.$pid.phone.json"
        local npt_emu="$scratch/np-template.$pid.emu.json"
        _write_network_profile_template_phone "$npt_phone" "$op" "$pvpn"
        _write_network_profile_template_emu   "$npt_emu"   "$op"

        local env_common=(
            WSS3_OPERATOR_PACKAGE_ROOT="$scratch"
            WSS3_EVIDENCE_ROOT="$ev"
            WSS3_RUNTIME_ROOT="$rt"
            WSS3_DRY_RUN=1
            WSS3_MANIFEST_WRITER_RC="${WSS3_MANIFEST_WRITER_RC:-}"
            WSS3_MANIFEST_WRITER_FAIL_STAGE="${WSS3_MANIFEST_WRITER_FAIL_STAGE:-}"
            WSS3_RELAY_HOST=relay.phntm.pro
            WSS3_PHONE_SERIAL=mock-phone-serial
            WSS3_EMU_SERIAL=mock-emu-serial
            SCUTIL="$scutil"
            ROUTE="$route"
            WSS3_CONFIRM_INPUT="$confirm"
            # ROUND-24: explicit serial → template mapping (mock
            # fails closed if a broadcast targets a serial that
            # does not match either).
            WSS3_MOCK_PHONE_SERIAL=mock-phone-serial
            WSS3_MOCK_EMU_SERIAL=mock-emu-serial
            WSS3_MOCK_NP_TEMPLATE_PHONE="$npt_phone"
            WSS3_MOCK_NP_TEMPLATE_EMU="$npt_emu"
            # Audit ROUND-30.18: forwarded so a fixture can drive the REAL
            # preflight -> smoke RED -> full refused path. Unset in every
            # ordinary run, so the positive control is unaffected.
            WSS3_MOCK_DELIVER_FAIL_CELL="${WSS3_MOCK_DELIVER_FAIL_CELL:-}"
            WSS3_MOCK_DELIVER_FAIL_ATTEMPTS="${WSS3_MOCK_DELIVER_FAIL_ATTEMPTS:-}"
            WSS3_MOCK_DELIVER_SILENT_CELL="${WSS3_MOCK_DELIVER_SILENT_CELL:-}"
            WSS3_MOCK_DELIVER_OVERFLOW_CELL="${WSS3_MOCK_DELIVER_OVERFLOW_CELL:-}"
            WSS3_ADB_MOCK_SPEC="$mock_spec"
            WSS3_MOCK_STATE_DIR="$mock_state"
            # Egress body override — same on host + emu so the HMAC
            # equality check succeeds (contract §10 step 4). Same value
            # for every profile; the real fetch is bypassed under
            # WSS3_DRY_RUN=1 (lib/wss3-egress-hmac.sh:61-67).
            WSS3_EGRESS_BODY_OVERRIDE="dry-run-body-${pid}"
            WSS3_EGRESS_AF_OVERRIDE=AF_INET
        )

        # preflight
        if ! env "${env_common[@]}" bash "$PKG_ROOT/run-carrier-vpn-matrix.sh" preflight \
                --operator "$carrier" --expected-operator-numeric "$op" \
                --phone-vpn "$pvpn" --host-vpn "$hvpn" \
                --host-vpn-service "SecureVPN"; then
            echo "  preflight FAILED for $pid" >&2
            per_profile_rc=$((per_profile_rc+1)); continue
        fi
        # smoke
        local _smoke_rc=0
        env "${env_common[@]}" bash "$PKG_ROOT/run-carrier-vpn-matrix.sh" smoke \
                --host-vpn-service "SecureVPN" || _smoke_rc=$?
        if [ "$_smoke_rc" -ne 0 ]; then
            echo "  smoke FAILED for $pid" >&2
            per_profile_rc=$((per_profile_rc+1))
            # Audit ROUND-30.18: do NOT skip the verifier here, for the
            # same reason ROUND-29.4 stopped skipping it after a failed
            # `full`. A RED smoke is precisely when the verifier account
            # matters: it turns "smoke FAILED" into "integrity GREEN,
            # product RED, every attempt reported and accounted for". A
            # profile stopped at the smoke has no matrix by design, and
            # judging THAT shape is the point of the smoke-only
            # terminal profile.
            _wss3_p07_verify_profile "$ev" "$pid" "$PKG_ROOT" || true
            continue
        fi
        # full
        local _full_rc=0
        env "${env_common[@]}" bash "$PKG_ROOT/run-carrier-vpn-matrix.sh" full \
                --host-vpn-service "SecureVPN" || _full_rc=$?
        if [ "$_full_rc" -ne 0 ]; then
            echo "  full FAILED for $pid (rc=$_full_rc)" >&2
            per_profile_rc=$((per_profile_rc+1))
            # Audit ROUND-29.4: do NOT skip the verifier here. A failed
            # `full` is precisely when its account matters — it is what
            # turns "full FAILED" into "aborted with pin_unconfirmed,
            # 2/8 cells, and nothing else wrong". Skipping it would
            # repeat the same mistake in miniature: a gate that is not
            # run does not gate.
        fi
        # Audit ROUND-29.3 P0: VERIFY the produced profile.
        #
        # A subset run skipped `compare`, so nothing ever called
        # `verify_profile_dir` on mock-backed evidence. That is how a
        # producer/verifier protocol split over `diagnostic_pin_active`
        # survived several rounds: every healthy cell was RED and no
        # gate looked. Running the verifier per profile — not only in
        # the all-eight compare — is what makes this harness an
        # acceptance check rather than wiring smoke.
        local _prof_dir
        _prof_dir=$(ls -d "$ev/${pid}-"* 2>/dev/null | tail -1)
        if [ -z "$_prof_dir" ] || [ ! -d "$_prof_dir" ]; then
            echo "  verify FAILED for $pid: no evidence directory" >&2
            per_profile_rc=$((per_profile_rc+1)); continue
        fi
        if ! WSS3_VERIFY_DIR="$_prof_dir" WSS3_PKG_ROOT="$PKG_ROOT" python3 - <<'PY'
import os, sys
sys.path.insert(0, os.environ.get("WSS3_PKG_ROOT", "."))
import verify_evidence_wss3 as V
rep = V.verify_profile_dir(os.environ["WSS3_VERIFY_DIR"])
print(f"  verify: evidence_integrity="
      f"{'GREEN' if rep.integrity_ok else 'RED'} "
      f"product_outcome={rep.product_outcome} "
      f"cells_ran={rep.cells_ran}/{rep.cells_expected_to_run}")
for issue in rep.integrity_issues[:20]:
    print(f"    - {issue}")
sys.exit(0 if (rep.integrity_ok and rep.product_outcome == "GREEN") else 1)
PY
        then
            # Only count the verifier's own verdict as a NEW failure
            # when `full` itself reported success — otherwise the same
            # abort would be counted twice.
            if [ "$_full_rc" -eq 0 ]; then
                echo "  verify FAILED for $pid (see issues above)" >&2
                per_profile_rc=$((per_profile_rc+1))
            fi
            continue
        fi
    done
    if [ "$per_profile_rc" -ne 0 ]; then
        echo "  [$label] $per_profile_rc profile(s) failed preflight/smoke/full" >&2
        return 4
    fi
    echo "  [$label] $ran_count profile(s) preflight/smoke/full OK via REAL orchestrator"

    # Audit ROUND-12 P1-#8: no synthesis fallback. If the caller
    # asked for fewer than 8 profiles this is a developer-only
    # smoke-check — do NOT run compare (comparing an incomplete
    # matrix is meaningless) and return a distinct non-zero so
    # callers cannot mis-read `--profiles 1` as a passing audit gate.
    if [ "$ran_count" -lt 8 ]; then
        echo "  [$label] SUBSET RUN (ran_count=$ran_count < 8); skipping compare" >&2
        echo "  [$label] audit gate requires all 8 profiles — treat as non-audit-passing" >&2
        return 2
    fi

    echo "  [$label] running compare against $ev"
    # NB: invoke compare-vpn-matrix.py directly rather than via the
    # runner's `compare` verb, because the runner resolves the .py
    # path via WSS3_OPERATOR_PACKAGE_ROOT which we set to the scratch
    # dir for hermetic runtime/evidence isolation.
    if ! env WSS3_OPERATOR_PACKAGE_ROOT="$PKG_ROOT" \
             WSS3_RUNTIME_ROOT="$rt" \
             WSS3_DRY_RUN=1 \
             python3 "$PKG_ROOT/compare-vpn-matrix.py" --evidence-root "$ev"; then
        return 1
    fi
    return 0
}

# ── the terminal-smoke control (audit ROUND-30.18 P1-1) ────────
#
# The smoke-only terminal profile must be provable from the REAL
# producer, not from a builder that fabricates the shape: preflight
# passes, the smoke goes RED with an accounted failure, `full` REFUSES
# to run on top of it, no matrix artefact is ever written — and the
# verifier judges that profile trustworthy evidence of a product
# failure (integrity GREEN / product RED). Python fixtures build this
# shape; only this control proves a real run produces it.
_run_terminal_smoke_control() {
    local scratch="$1"
    local ev="$scratch/evidence"
    local rt="$scratch/.runtime/wss3"
    mkdir -p "$ev" "$rt"
    chmod 0700 "$scratch/.runtime" 2>/dev/null || true

    local scutil="$scratch/scutil-on" route="$scratch/route-on"
    _mkscutil "$scutil" "connected"
    _mkroute  "$route"  "utun3"
    local npt_phone="$scratch/np-template.term.phone.json"
    local npt_emu="$scratch/np-template.term.emu.json"
    _write_network_profile_template_phone "$npt_phone" 25011 off
    _write_network_profile_template_emu   "$npt_emu"   25011

    local mock_spec="$scratch/mock-adb.spec"
    cat > "$mock_spec" <<EOF
devices=mock-phone-serial mock-emu-serial
apk_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
paired_count=1
signed_prekey_published=true
EOF
    local env_common=(
        WSS3_OPERATOR_PACKAGE_ROOT="$scratch"
        WSS3_EVIDENCE_ROOT="$ev"
        WSS3_RUNTIME_ROOT="$rt"
        WSS3_DRY_RUN=1
        WSS3_MANIFEST_WRITER_RC="${WSS3_MANIFEST_WRITER_RC:-}"
        WSS3_MANIFEST_WRITER_FAIL_STAGE="${WSS3_MANIFEST_WRITER_FAIL_STAGE:-}"
        WSS3_RELAY_HOST=relay.phntm.pro
        WSS3_PHONE_SERIAL=mock-phone-serial
        WSS3_EMU_SERIAL=mock-emu-serial
        SCUTIL="$scutil"
        ROUTE="$route"
        WSS3_CONFIRM_INPUT="CONFIRM-YOTA-PHONE-OFF-HOST-ON"
        WSS3_MOCK_PHONE_SERIAL=mock-phone-serial
        WSS3_MOCK_EMU_SERIAL=mock-emu-serial
        WSS3_MOCK_NP_TEMPLATE_PHONE="$npt_phone"
        WSS3_MOCK_NP_TEMPLATE_EMU="$npt_emu"
        WSS3_ADB_MOCK_SPEC="$mock_spec"
        WSS3_MOCK_STATE_DIR="$scratch/mock-state"
        # THE knob: the P2E recipient reports attempt 1 as failed and
        # never settles, so the smoke fails with a fully accounted
        # attempt history — trustworthy evidence of a product failure.
        WSS3_MOCK_DELIVER_FAIL_CELL="wss.p2e.after-connect"
        WSS3_MOCK_DELIVER_FAIL_ATTEMPTS=1
        WSS3_EGRESS_BODY_OVERRIDE="dry-run-body-terminal-smoke"
        WSS3_EGRESS_AF_OVERRIDE=AF_INET
    )

    # 1. preflight must PASS — the profile arms normally.
    if ! env "${env_common[@]}" bash "$PKG_ROOT/run-carrier-vpn-matrix.sh" preflight \
            --operator YOTA --expected-operator-numeric 25011 \
            --phone-vpn off --host-vpn on \
            --host-vpn-service "SecureVPN"; then
        echo "  [terminal-smoke] preflight FAILED (must pass)" >&2
        return 1
    fi
    # 2. smoke must go RED.
    local _rc=0
    env "${env_common[@]}" bash "$PKG_ROOT/run-carrier-vpn-matrix.sh" smoke \
            --host-vpn-service "SecureVPN" || _rc=$?
    if [ "$_rc" -eq 0 ]; then
        echo "  [terminal-smoke] smoke passed (must go RED)" >&2
        return 1
    fi
    # 3. `full` must REFUSE to run on top of the RED smoke — and the
    #    refusal must come from the smoke gate, not an incidental
    #    earlier failure.
    local _full_out
    _full_out=$(env "${env_common[@]}" bash "$PKG_ROOT/run-carrier-vpn-matrix.sh" full \
            --host-vpn-service "SecureVPN" 2>&1) && {
        echo "  [terminal-smoke] full RAN on a RED smoke (must refuse)" >&2
        return 1
    }
    if ! printf '%s' "$_full_out" | grep -q "full: refused"; then
        echo "  [terminal-smoke] full failed without refusing: $_full_out" >&2
        return 1
    fi
    # 4. no matrix artefact exists — nothing to hide a full run behind.
    local _dir
    _dir=$(ls -d "$ev/yota-phone-off-host-on-"* 2>/dev/null | tail -1)
    if [ -z "$_dir" ]; then
        echo "  [terminal-smoke] no evidence directory produced" >&2
        return 1
    fi
    if [ -e "$_dir/matrix_completion.json" ] || [ -d "$_dir/matrix_cells" ]; then
        echo "  [terminal-smoke] a matrix artefact exists in $_dir" >&2
        return 1
    fi
    # 5. the FULL verifier judges this trustworthy evidence of failure.
    WSS3_VERIFY_DIR="$_dir" WSS3_PKG_ROOT="$PKG_ROOT" python3 - <<'PY'
import os, sys
sys.path.insert(0, os.environ["WSS3_PKG_ROOT"])
import verify_evidence_wss3 as V
rep = V.verify_profile_dir(os.environ["WSS3_VERIFY_DIR"])
print(f"  [terminal-smoke] verify: evidence_integrity="
      f"{'GREEN' if rep.integrity_ok else 'RED'} "
      f"product_outcome={rep.product_outcome}")
for issue in rep.integrity_issues[:10]:
    print(f"    - {issue}")
ok = rep.integrity_ok and rep.product_outcome == "RED"
if not ok:
    print("  [terminal-smoke] want integrity GREEN / product RED", file=sys.stderr)
sys.exit(0 if ok else 1)
PY
}

# ── entry ──────────────────────────────────────────────────────

overall_ok=1
overall_subset=0  # set to 1 when _run_matrix returned 2 (clean but subset)

# The terminal-smoke control runs whenever the positive control runs:
# it is one profile stopped at the smoke, so it is cheap enough for
# every mode that exercises the orchestrator at all.
if [ "$MODE" = "positive" ] || [ "$MODE" = "both" ]; then
    echo "== TERMINAL-SMOKE control: preflight OK, smoke RED, full refused, verifier GREEN/RED =="
    scratch_term="$(mktemp -d -t wss3-p07-term.XXXXXX)"
    _SCRATCH_TO_CLEAN="$_SCRATCH_TO_CLEAN $scratch_term"
    if _run_terminal_smoke_control "$scratch_term"; then
        echo "TERMINAL-SMOKE: PASS"
    else
        echo "TERMINAL-SMOKE: FAIL (see above)" >&2
        overall_ok=0
    fi
    echo ""
fi

if [ "$MODE" = "positive" ] || [ "$MODE" = "both" ]; then
    echo "== POSITIVE dry-run: 8 profiles, expect compare rc=0 =="
    scratch_pos="$(mktemp -d -t wss3-p07-pos.XXXXXX)"
    # Audit ROUND-13 P1: register scratch for cleanup by the composite
    # trap instead of overwriting it (which used to strand mock state).
    # mktemp paths never contain spaces on the platforms we target
    # (Linux/macOS/Git Bash all use /tmp/prefix.XXXXXX), so a simple
    # space-separated accumulator is safe here.
    _SCRATCH_TO_CLEAN="$_SCRATCH_TO_CLEAN $scratch_pos"
    _pos_rc=0
    _run_matrix "$scratch_pos" positive || _pos_rc=$?
    if [ "$_pos_rc" = 0 ]; then
        echo "POSITIVE: PASS (compare rc=0)"
    elif [ "$_pos_rc" = 2 ]; then
        # Audit ROUND-12 P1-#8: subset run — orchestrator wiring
        # exercised cleanly for the requested profile(s) but the
        # full-matrix compare was skipped by design. Distinct from a
        # real orchestrator failure so bundle-validator scripts can
        # accept it as "extract works" without accepting it as an
        # audit-gate pass.
        echo "POSITIVE: SUBSET-OK (rc=2 — orchestrator wiring exercised; compare skipped for subset)"
        overall_subset=1
    else
        echo "POSITIVE: FAIL (see above)" >&2
        overall_ok=0
    fi
    # Audit ROUND-19 P1-1 follow-up: also honour --keep-evidence on
    # SUBSET-OK (rc=2) so developer smoke-checks with `--profiles 1`
    # can preserve the produced evidence for review-pack assembly.
    # A subset run is not an audit-gate pass, but its evidence IS
    # legitimate for shipping as "one mocked profile" per the
    # architect's ROUND-19 cadence directive.
    if [ -n "$KEEP_EVIDENCE" ] && \
       [ "$overall_ok" = 1 ] && [ "$_pos_rc" = 0 ]; then
        mkdir -p "$KEEP_EVIDENCE/positive"
        cp -R "$scratch_pos/evidence" "$KEEP_EVIDENCE/positive/" 2>/dev/null || true
        cp -R "$scratch_pos/.runtime" "$KEEP_EVIDENCE/positive/" 2>/dev/null || true
    elif [ -n "$KEEP_EVIDENCE" ] && [ "$overall_subset" = 1 ]; then
        mkdir -p "$KEEP_EVIDENCE/positive"
        cp -R "$scratch_pos/evidence" "$KEEP_EVIDENCE/positive/" 2>/dev/null || true
    elif [ -n "$KEEP_EVIDENCE" ]; then
        # Audit ROUND-29.3: preserve evidence on FAILURE too. The
        # composite EXIT trap deletes the scratch, so a run that
        # aborted mid-matrix used to leave nothing behind — exactly
        # the case where the evidence matters most, because it is what
        # an abort actually looks like on disk. The caller asked for
        # the evidence; whether the run passed is a separate fact
        # already reported above.
        mkdir -p "$KEEP_EVIDENCE/positive"
        cp -R "$scratch_pos/evidence" "$KEEP_EVIDENCE/positive/" 2>/dev/null || true
        cp -R "$scratch_pos/.runtime" "$KEEP_EVIDENCE/positive/" 2>/dev/null || true
    fi
fi

if [ "$MODE" = "negative" ] || [ "$MODE" = "both" ]; then
    echo ""
    echo "== NEGATIVE dry-run: 8 profiles + 1 planted defect, expect compare rc=1 =="
    # Audit ROUND-30.18: no purge needed between the positive and
    # negative runs — each _run_matrix scopes its mock state to its
    # own scratch (WSS3_MOCK_STATE_DIR), so the negative run cannot
    # see the positive run's state by construction.
    scratch_neg="$(mktemp -d -t wss3-p07-neg.XXXXXX)"
    _SCRATCH_TO_CLEAN="$_SCRATCH_TO_CLEAN $scratch_neg"
    # Run the matrix; if it succeeds, plant a defect and re-verify.
    _neg_rc=0
    _run_matrix "$scratch_neg" negative || _neg_rc=$?
    if [ "$_neg_rc" = 2 ]; then
        # Subset run — planting a defect against a partial matrix
        # would test only the individual comparator gate, not the
        # matrix-completion gate the negative run is supposed to
        # prove. Skip planting; the positive path already reported
        # SUBSET-OK.
        echo "NEGATIVE: SUBSET-OK (rc=2 — planting skipped for subset)"
        overall_subset=1
        rm -rf "$scratch_neg"
    elif [ "$_neg_rc" -ne 0 ]; then
        echo "NEGATIVE precondition failure: matrix didn't build cleanly, cannot plant defect" >&2
        rm -rf "$scratch_neg"
        overall_ok=0
    else
        # Plant defect: corrupt one profile's smoke_verdict + refresh
        # SHA256SUMS so the tampering isn't caught by the hash gate
        # (which would technically also fire — belt-and-braces).
        target_dir=$(ls -d "$scratch_neg/evidence/yota-phone-on-host-on-"* 2>/dev/null | head -1)
        if [ -z "$target_dir" ]; then
            echo "NEGATIVE precondition failure: yota-phone-on-host-on evidence dir not found" >&2
            overall_ok=0
        else
            python3 - "$target_dir/smoke_verdict.json" <<'PY'
import json, sys
p = sys.argv[1]
with open(p, encoding="utf-8") as f: v = json.load(f)
v["integrity"] = "RED"; v["p2e"] = "FAIL"; v["e2p"] = "FAIL"
with open(p, "w", encoding="utf-8") as f: json.dump(v, f, sort_keys=True, indent=2)
PY
            # Recompute the SHA line for smoke_verdict.json inside SHA256SUMS.txt
            # so the tampering itself is not what forces RED — the value
            # check is.
            python3 - "$target_dir" <<'PY'
import os, sys, hashlib
d = sys.argv[1]
sha_path = os.path.join(d, "SHA256SUMS.txt")
target = "smoke_verdict.json"
tp = os.path.join(d, target)
new_hash = hashlib.sha256(open(tp, "rb").read()).hexdigest()
with open(sha_path, encoding="utf-8") as f:
    lines = f.readlines()
out = []
for l in lines:
    parts = l.strip().split(None, 1)
    if len(parts) == 2 and parts[1].lstrip("*").endswith(target):
        out.append(f"{new_hash}  {parts[1]}\n")
    else:
        out.append(l)
with open(sha_path, "w", encoding="utf-8") as f:
    f.writelines(out)
PY
            # Re-run compare only — must exit non-zero.
            # NB: invoke compare-vpn-matrix.py directly for the same
            # WSS3_OPERATOR_PACKAGE_ROOT reason as the positive path.
            rc=0
            env WSS3_OPERATOR_PACKAGE_ROOT="$PKG_ROOT" \
                WSS3_RUNTIME_ROOT="$scratch_neg/.runtime/wss3" \
                WSS3_DRY_RUN=1 \
                python3 "$PKG_ROOT/compare-vpn-matrix.py" --evidence-root "$scratch_neg/evidence" \
                || rc=$?
            if [ "$rc" = 0 ]; then
                echo "NEGATIVE: FAIL (compare rc=0 with a planted defect — fail-open)" >&2
                overall_ok=0
            else
                echo "NEGATIVE: PASS (compare rc=$rc, planted defect surfaced)"
                if [ -n "$KEEP_EVIDENCE" ]; then
                    mkdir -p "$KEEP_EVIDENCE/negative"
                    cp -R "$scratch_neg/evidence" "$KEEP_EVIDENCE/negative/" 2>/dev/null || true
                fi
            fi
        fi
        rm -rf "$scratch_neg"
    fi
fi

echo ""
if [ "$overall_ok" = 1 ] && [ "$overall_subset" = 0 ]; then
    echo "==== dry_run_matrix_p0_7 (real orchestrator, mock-backed): PASS ===="
    exit 0
elif [ "$overall_ok" = 1 ] && [ "$overall_subset" = 1 ]; then
    # Distinct rc so bundle-validator can accept "extract works" without
    # calling it a passing audit gate.
    echo "==== dry_run_matrix_p0_7 (subset run): SUBSET-OK — audit gate needs --profiles 8 ===="
    exit 2
else
    echo "==== dry_run_matrix_p0_7 (real orchestrator, mock-backed): FAIL ===="
    exit 1
fi
