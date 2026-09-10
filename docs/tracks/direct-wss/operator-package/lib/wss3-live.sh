#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — live smoke + full flows.
#
# Contract: docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md
# §3.1 (preflight live checks), §3.2 (smoke), §3.3 (full), §4.6
# (per-cell + post-matrix checkpoints).
#
# Every function here exercises the REAL production path — real
# `wss3_adb` calls, real network_profile_report broadcasts, real
# checkpoint HMAC computation, real matrix_completion atomic write.
# The WSS3_DRY_RUN switch redirects `wss3_adb` to the mock backend
# (lib/wss3-adb-mock.sh); the same code paths still execute.

: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
    # shellcheck source=portable.sh
    source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
    PORTABLE_SH_LOADED=1
fi
: "${WSS3_ADB_SH_LOADED:=0}"
if [ "$WSS3_ADB_SH_LOADED" != "1" ]; then
    # shellcheck source=wss3-adb.sh
    source "$(dirname "${BASH_SOURCE[0]}")/wss3-adb.sh"
    WSS3_ADB_SH_LOADED=1
fi
: "${WSS3_EGRESS_HMAC_SH_LOADED:=0}"
if [ "$WSS3_EGRESS_HMAC_SH_LOADED" != "1" ]; then
    # shellcheck source=wss3-egress-hmac.sh
    source "$(dirname "${BASH_SOURCE[0]}")/wss3-egress-hmac.sh"
    WSS3_EGRESS_HMAC_SH_LOADED=1
fi

# ── canonical cell set (must mirror verify_evidence_wss3.canonical_cell_ids)

WSS3_CANONICAL_CELLS=(
    "wss.p2e.after-connect" "wss.e2p.after-connect"
    "wss.p2e.after-idle"    "wss.e2p.after-idle"
    "wss.p2e.bg-fg"         "wss.e2p.bg-fg"
    "rest.p2e.control"      "rest.e2p.control"
)
WSS3_REST_CELLS=("rest.p2e.control" "rest.e2p.control")

wss3_is_rest_cell() {
    local c="$1"
    for r in "${WSS3_REST_CELLS[@]}"; do
        [ "$r" = "$c" ] && return 0
    done
    return 1
}

wss3_cell_direction_emitter() {
    # Prints phone|emulator — the SENDER for this cell.
    case "$1" in
        wss.p2e.*|rest.p2e.*) echo phone ;;
        wss.e2p.*|rest.e2p.*) echo emulator ;;
        *) echo phone ;;
    esac
}

# ── live checkpoint (§4.6)
#
# Runs the drift check. Returns 0 on match, non-zero on drift.
# Prints one of the WSS3_HOSTVPN_ERR_* codes on failure to stderr.
#
# Contract discipline (REDLINE-4 blocker 1): the PHONE reporter is
# invoked WITHOUT a checkpoint_key — the phone does not participate
# in HMAC egress fingerprinting. Only host + emulator do. Any code
# path that broadcasts checkpoint_key_hex to the phone is a contract
# violation.
wss3_live_checkpoint() {
    local phone_serial="$1" emu_serial="$2"
    local expected_operator_numeric="$3" expected_phone_vpn="$4"
    local expected_host_vpn="$5" host_vpn_service="$6" relay_host="$7"
    local checkpoint_key="$8" evidence_dir="$9"
    # Audit ROUND-24 CONTINUATION: `mode` picks between the
    # PREFLIGHT-BASELINE checkpoint (default `baseline`, writes
    # EGRESS_FINGERPRINT.json + emits the emu_json to
    # WSS3_EMIT_EMU_JSON_TO so preflight can build network_profile
    # + PROFILE_STATE from THE SAME snapshot) and the per-cell /
    # post-matrix path (`observe`, does the drift + HMAC parity
    # checks but MUST NOT overwrite baseline files). §5 evidence
    # ownership per architect ROUND-24 CONTINUATION.
    local mode="${WSS3_CHECKPOINT_MODE:-baseline}"

    # 1. Phone reporter — STATE-ONLY per audit ROUND-10 P0-1. The phone
    #    NEVER receives a checkpoint_key, NEVER computes an HMAC, and
    #    NEVER emits an egress_fingerprint object. This helper broadcasts
    #    the `network_profile_state_report` subcommand which the receiver
    #    routes to DiagnosticNetworkProfileReporter.runStateOnly(context).
    local phone_json
    phone_json=$(wss3_adb_invoke_network_profile_state_report "$phone_serial")
    if [ -z "$phone_json" ]; then
        echo "checkpoint: phone reporter returned empty output" >&2
        return 1
    fi
    # Belt-and-braces: if the phone JSON somehow contains an
    # egress_fingerprint (mock scaffolding bug OR contract regression),
    # fail-closed immediately — the audit ROUND-10 P0-1 discipline
    # must NEVER be silently softened.
    if printf '%s' "$phone_json" | grep -q '"egress_fingerprint"'; then
        echo "checkpoint: phone JSON contains egress_fingerprint (contract violation, audit ROUND-10 P0-1)" >&2
        return 1
    fi
    # 2. Extract state fields — strict null fail-closed per audit
    #    ROUND-9 P0-7. wifi_enabled=null / mobile_data_enabled=null
    #    MUST NOT pass; they mean the reporter caught SecurityException
    #    or the state is unknowable, which is exactly the failure mode
    #    we cannot accept.
    local drift
    drift=$(WSS3_PJ="$phone_json" WSS3_EXP_OP="$expected_operator_numeric" \
            WSS3_EXP_PVPN="$expected_phone_vpn" python3 - <<'PY'
import json, os, sys
raw = os.environ["WSS3_PJ"]
exp_op = os.environ["WSS3_EXP_OP"]
exp_pvpn = os.environ["WSS3_EXP_PVPN"]
try:
    j = json.loads(raw)
except Exception as e:
    print(f"phone_reporter_json_malformed:{e}"); sys.exit(0)
op = j.get("active_data_sim_operator_numeric")
if op != exp_op:
    print(f"carrier_attribution_lost:got={op} want={exp_op}"); sys.exit(0)
# Strict null / non-boolean fail-closed (audit P0-7).
w = j.get("wifi_enabled")
if not isinstance(w, bool):
    print(f"wifi_active_or_mobile_data_off:wifi_enabled_not_bool:{type(w).__name__}"); sys.exit(0)
if w is True:
    print("wifi_active_or_mobile_data_off:wifi_enabled_true"); sys.exit(0)
m = j.get("mobile_data_enabled")
if not isinstance(m, bool):
    print(f"wifi_active_or_mobile_data_off:mobile_data_enabled_not_bool:{type(m).__name__}"); sys.exit(0)
if m is False:
    print("wifi_active_or_mobile_data_off:mobile_data_enabled_false"); sys.exit(0)
has_vpn = j.get("has_transport_vpn")
if not isinstance(has_vpn, bool):
    print(f"phone_vpn_state_mismatch:has_transport_vpn_not_bool:{type(has_vpn).__name__}"); sys.exit(0)
want_vpn = (exp_pvpn == "on")
if has_vpn is not want_vpn:
    print(f"phone_vpn_state_mismatch:has_transport_vpn={has_vpn} want={want_vpn}"); sys.exit(0)
# Also require the wifi_read_error field to be absent OR null (a
# SecurityException produces wifi_read_error="SECURITY_EXCEPTION"
# and MUST fail-close per REDLINE-4 blocker 3).
wre = j.get("wifi_read_error")
if wre not in (None, "null"):
    print(f"wifi_read_error_reported:{wre}"); sys.exit(0)
print("OK")
PY
)
    if [ "$drift" != "OK" ]; then
        echo "checkpoint drift: $drift" >&2
        return 1
    fi

    # 3. Host VPN state per --host-vpn expected.
    if [ "$expected_host_vpn" = "on" ]; then
        # Audit ROUND-30: same fallback contract as preflight — legacy
        # scutil first, declared tunnel only if that cannot attest.
        wss3_hostvpn_check_on "$host_vpn_service" "$relay_host"             "${WSS3_HOST_VPN_TUNNEL:-}" || return 1
    else
        wss3_hostvpn_check_off "$host_vpn_service" "$relay_host" || return 1
    fi

    # 4. Host+emu egress HMAC parity. ONLY emu gets the checkpoint_key.
    local emu_json
    emu_json=$(wss3_adb_invoke_network_profile_report "$emu_serial" "$checkpoint_key")
    if [ -z "$emu_json" ]; then
        echo "checkpoint: emu reporter returned empty output" >&2
        return 1
    fi
    local emu_hmac emu_af
    emu_hmac=$(printf '%s' "$emu_json" | python3 -c "import json,sys; j=json.load(sys.stdin); print(j.get('egress_fingerprint',{}).get('hmac_fp_hex') or '')")
    emu_af=$(printf '%s' "$emu_json" | python3 -c "import json,sys; j=json.load(sys.stdin); print(j.get('egress_fingerprint',{}).get('address_family') or '')")
    local host_line host_hmac host_af host_status
    host_line=$(wss3_host_egress_fingerprint "$checkpoint_key")
    host_hmac=$(printf '%s' "$host_line" | cut -d' ' -f1)
    host_af=$(printf '%s' "$host_line" | cut -d' ' -f2)
    host_status=$(printf '%s' "$host_line" | cut -d' ' -f3)
    # Audit ROUND-24 CONTINUATION: compute the snapshot wall-clock
    # ONCE. Rewrite emu_json's `at_wall_ms` + egress `at_wall_ms`
    # to this value AND stamp EGRESS_FINGERPRINT.at_wall_ms with
    # the same value so verifier's cross-file timestamp equality
    # holds by construction (verifier ROUND-22 P0-3 parent/nested
    # equality + ROUND-24C-G baseline temporal identity).
    local snap_wall_ms
    snap_wall_ms=$(now_ms)
    emu_json=$(WSS3_EMU_JSON_IN="$emu_json" WSS3_TS="$snap_wall_ms" \
               python3 - <<'PY'
import json, os
j = json.loads(os.environ["WSS3_EMU_JSON_IN"])
ts = int(os.environ["WSS3_TS"])
j["at_wall_ms"] = ts
egf = j.get("egress_fingerprint")
if isinstance(egf, dict):
    egf["at_wall_ms"] = ts
print(json.dumps(j, sort_keys=True))
PY
)
    # Baseline-only: persist EGRESS_FINGERPRINT.json + emit the
    # (timestamp-stamped) emu_json to WSS3_EMIT_EMU_JSON_TO so
    # preflight can consume the SAME snapshot for network_profile
    # + PROFILE_STATE. Observe mode NEVER writes baseline files.
    # ── Audit ROUND-30.8 P0: baseline writers propagate ────────────
    # R30.7 ran this heredoc with no status capture and no atomic
    # publication. With the writer forced to fail, `preflight` still
    # printed `armed` while EGRESS_FINGERPRINT.json was absent, and only
    # a later verifier noticed. It is now staged, checked and renamed,
    # and any failure returns non-zero to the caller's transaction.
    local _ef_rc=0
    if [ "$mode" = "baseline" ] && [ -n "$evidence_dir" ]; then
        WSS3_HH="$host_hmac" WSS3_EH="$emu_hmac" WSS3_HAF="$host_af" WSS3_EAF="$emu_af" \
            WSS3_TS="$snap_wall_ms" python3 - "$evidence_dir/EGRESS_FINGERPRINT.json.tmp" <<'PY' || _ef_rc=$?
import json, os, sys
hh = os.environ["WSS3_HH"] or None
eh = os.environ["WSS3_EH"] or None
haf = os.environ["WSS3_HAF"] or "UNKNOWN"
eaf = os.environ["WSS3_EAF"] or "UNKNOWN"
ts = int(os.environ["WSS3_TS"])
if hh == "null": hh = None
if eh == "null": eh = None
obj = {
    "host_hmac_fp_hex": hh,
    "emu_hmac_fp_hex": eh,
    "address_family_host": haf,
    "address_family_emu": eaf,
    "equal_host_emu": (hh is not None and eh is not None and hh == eh),
    "at_wall_ms": ts,
}
with open(sys.argv[1], "w", encoding="utf-8") as f:
    json.dump(obj, f, sort_keys=True, indent=2)
PY
        local _ef_tmp="$evidence_dir/EGRESS_FINGERPRINT.json.tmp"
        if [ "$_ef_rc" != "0" ] || [ ! -s "$_ef_tmp" ]; then
            echo "checkpoint: EGRESS_FINGERPRINT.json writer failed (rc=$_ef_rc)" >&2
            rm -f -- "$_ef_tmp" 2>/dev/null || true
            return 1
        fi
        if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$_ef_tmp" 2>/dev/null; then
            echo "checkpoint: EGRESS_FINGERPRINT.json is not valid JSON" >&2
            rm -f -- "$_ef_tmp" 2>/dev/null || true
            return 1
        fi
        if ! mv "$_ef_tmp" "$evidence_dir/EGRESS_FINGERPRINT.json"; then
            echo "checkpoint: could not publish EGRESS_FINGERPRINT.json" >&2
            rm -f -- "$_ef_tmp" 2>/dev/null || true
            return 1
        fi
    fi
    if [ "$mode" = "baseline" ] && [ -n "${WSS3_EMIT_EMU_JSON_TO:-}" ]; then
        # Audit ROUND-30.8 P0: the emulator snapshot is mandatory input
        # for two required evidence files; a failed emit is a failed
        # checkpoint, not an empty section downstream.
        if ! printf '%s' "$emu_json" > "$WSS3_EMIT_EMU_JSON_TO"; then
            echo "checkpoint: could not emit the emulator snapshot" >&2
            return 1
        fi
        if [ ! -s "$WSS3_EMIT_EMU_JSON_TO" ]; then
            echo "checkpoint: emulator snapshot is empty" >&2
            return 1
        fi
    fi
    if [ "$host_af" != "AF_INET" ] || [ "$emu_af" != "AF_INET" ]; then
        echo "checkpoint drift: mixed_address_family host=$host_af emu=$emu_af" >&2
        return 1
    fi
    if [ -z "$host_hmac" ] || [ "$host_hmac" = "null" ] || \
       [ -z "$emu_hmac" ] || [ "$emu_hmac" = "null" ]; then
        echo "checkpoint drift: path_divergence_host_emu (hmac unavailable)" >&2
        return 1
    fi
    if [ "$host_hmac" != "$emu_hmac" ]; then
        echo "checkpoint drift: path_divergence_host_emu (host != emu)" >&2
        return 1
    fi
    return 0
}

# ── live smoke (§3.2 + audit ROUND-10 P0-2)
#
# Real 2-direction verification with REAL-CID join. Sequence per
# direction:
#   1. Fire ONE `send` via diag-cmd (--async).
#   2. Bounded-poll SENDER logcat for the specific
#      `diagnostic_send_dispatched` event matching this profile's
#      run_id + this direction's cell_id + sequence=1 to LEARN the
#      real CID the receiver minted.
#   3. Bounded-poll RECIPIENT logcat for the full triplet on THAT
#      SPECIFIC CID (deliver_received[fresh] + message_persisted +
#      ack_deliver_sent).
#   4. Verify sender-side route: sender_transport_decision on the
#      SENDER emitter with dispatched=true AND
#      outer_transport=direct AND inner_route=wss for the SAME CID;
#      route return sender_wss_send_returned with dispatched=true;
#      sender_relay_ack_received.
# Both directions MUST succeed independently, EACH bound by its
# own real CID (no cross-direction event mixing). Any missing piece
# on either direction → smoke_verdict.integrity=RED, full is
# refused.
wss3_live_smoke() {
    local phone="$1" emu="$2" run_id="$3" ev="$4"
    local cell_p2e="wss.p2e.after-connect"
    local cell_e2p="wss.e2p.after-connect"

    # Clear BOTH logcats before anything else so every health and
    # pin breadcrumb this smoke reads is provably produced by THIS
    # invocation — freshness is the whole point of audit ROUND-29.1.
    wss3_adb_logcat_clear "$phone"
    wss3_adb_logcat_clear "$emu"

    # ── Audit ROUND-30.16: start the local WARN capture HERE ──────
    #
    # Everything else this smoke reads is a `logcat -d` SNAPSHOT taken
    # after the run. That is exactly how the 2026-08-26 attempt lost the
    # reason its delivery failed: by the time anyone looked for it, the
    # ring buffer had evicted the window. A snapshot cannot be widened
    # into evidence that no longer exists.
    #
    # So this one STREAMS, and it starts before the first smoke action
    # — immediately after the clear above, so it cannot miss a line this
    # invocation produces. It is local-only, never shipped, and disposed
    # of by `wss3_local_diag_dispose` on success, failure, INT and TERM.
    WSS3_LOCAL_DIAG_EVIDENCE_DIR="$ev"
    wss3_local_diag_start "$ev" "$phone" "$emu"

    # ── Audit ROUND-29.1 P0: FRESH pre-send health authority.
    # R29 read the most recent historical `sender_transport_decision`
    # and only probed when none existed, so a stale `direct` from an
    # earlier healthy period authorised a send on a phone whose Direct
    # was currently failed (independent reproduction returned RC=0 /
    # GREEN). The gate now invokes the accepted `health` subcommand
    # UNCONDITIONALLY on each device and accepts only a health line
    # produced after that exact invocation. A non-zero broadcast, a
    # timeout, a missing fresh line, `unknown` or any other non-direct
    # value fails closed BEFORE either send. No historical event and
    # no uncorrelated canary can authorise a dispatch.
    # `|| true`: the probe encodes its own failure in field 1
    # (`probe_failed`) and returns 1; under the runner's `set -e` the
    # bare substitution would abort before that value can be recorded.
    local phone_probe emu_probe phone_ot emu_ot
    phone_probe=$(_wss3_health_probe "$phone" || true)
    emu_probe=$(_wss3_health_probe "$emu" || true)
    phone_ot=$(printf '%s' "$phone_probe" | awk '{print $1}')
    emu_ot=$(printf '%s' "$emu_probe" | awk '{print $1}')

    if [ "$phone_ot" = "probe_failed" ] || [ "$emu_ot" = "probe_failed" ]; then
        _wss3_smoke_snapshot_log "$phone" "$emu" "$ev"
        _wss3_smoke_write_verdict "$ev" "$run_id" FAIL FAIL RED null null \
            health_probe_failed "$phone_ot" "$emu_ot" "" ""
        return 1
    fi
    if [ "$phone_ot" != "direct" ] || [ "$emu_ot" != "direct" ]; then
        _wss3_smoke_snapshot_log "$phone" "$emu" "$ev"
        _wss3_smoke_write_verdict "$ev" "$run_id" FAIL FAIL RED null null \
            direct_unavailable "$phone_ot" "$emu_ot" "" ""
        return 1
    fi

    # ── Audit ROUND-29 P0: serialise directions.
    # Each direction pins BOTH devices to the same canonical cell_id
    # so that recipient events (which the mock derives from the
    # recipient's LOCAL pin — mirroring real device behaviour behind
    # the old simultaneous-pin false-attribution defect) attribute
    # to the same cell as the sender's own events. Pins are cleared
    # between directions with `pin --es pin none` on BOTH devices.
    #
    # Audit ROUND-29.1 P1-3: a pin is no longer ASSUMED. Both pin
    # broadcasts must return zero AND each emitter must produce a
    # fresh `diagnostic_pin_active` matching run_id, cell_id, pin and
    # emitter before anything is dispatched. Any failure clears both
    # pins, writes a bounded RED and sends nothing.
    local pin_fail=""
    local clear_fail=""

    # ── Direction 1: P2E — BOTH pinned to cell_p2e; phone sends.
    local cid_p2e waited_p2e=0
    pin_fail=$(_wss3_pin_both_confirmed "$phone" phone "$emu" emulator "$run_id" "$cell_p2e" wss)
    if [ -n "$pin_fail" ]; then
        # Nothing has been dispatched yet at this stage. The run is
        # already failing, so the clear is best-effort: an unreachable
        # device must not replace this verdict with a worse one.
        _wss3_clear_both_pins "$phone" phone "$emu" emulator "$run_id" "$cell_p2e"
        _wss3_smoke_snapshot_log "$phone" "$emu" "$ev"
        _wss3_smoke_write_verdict "$ev" "$run_id" FAIL FAIL RED null null \
            pin_unconfirmed "" "" p2e "$pin_fail"
        return 1
    fi
    wss3_adb_invoke_diag_cmd "$phone" send --es run_id "$run_id" --es cell_id "$cell_p2e" --ei sequence 1 >/dev/null
    cid_p2e=$(_wss3_wait_for_dispatched_cid "$phone" "$run_id" "$cell_p2e" 1 120)
    if [ -n "$cid_p2e" ]; then
        wss3_adb_wait_for_cid_completion "$emu" "$run_id" "$cell_p2e" "$cid_p2e" 120 && waited_p2e=1 || :
    fi
    # ── Audit ROUND-30.11: the inter-direction clear is STRICT ────────
    # A pin left on either device is inherited by the next direction,
    # because recipient events carry the RECIPIENT's own cell_id — the
    # exact false attribution the dual pin was introduced to remove. So
    # E2P does not start until both sides have confirmed `pin=none`.
    #
    # E2P genuinely never dispatches here, so this is a PRE-SEND abort
    # for that direction and it keeps the pre-send shape: P2E is judged
    # from the same evidence the success path uses, E2P is FAIL/null.
    clear_fail=$(_wss3_clear_both_pins_strict \
        "$phone" phone "$emu" emulator "$run_id" "$cell_p2e")
    if [ -n "$clear_fail" ]; then
        _wss3_smoke_snapshot_log "$phone" "$emu" "$ev"
        local p2e_c="FAIL" p2e_cid_c="null"
        if [ -n "$cid_p2e" ] && _wss3_smoke_direction_ok_by_cid \
                "$ev/smoke.log" "$run_id" "$cell_p2e" phone emulator wss "$cid_p2e"; then
            p2e_c="OK"
            p2e_cid_c="$cid_p2e"
        fi
        _wss3_smoke_write_verdict "$ev" "$run_id" "$p2e_c" FAIL RED \
            "$p2e_cid_c" null pin_clear_unconfirmed "" "" e2p "$clear_fail"
        return 1
    fi

    # ── Direction 2: E2P — BOTH pinned to cell_e2p; emulator sends.
    local cid_e2p waited_e2p=0
    pin_fail=$(_wss3_pin_both_confirmed "$phone" phone "$emu" emulator "$run_id" "$cell_e2p" wss)
    if [ -n "$pin_fail" ]; then
        _wss3_clear_both_pins "$phone" phone "$emu" emulator "$run_id" "$cell_e2p"
        _wss3_smoke_snapshot_log "$phone" "$emu" "$ev"
        # ── Audit ROUND-29.2 P1-1 ─────────────────────────────────
        # P2E has ALREADY been sent and observed by this point. R29.1
        # wrote FAIL/null for it here, which contradicted a smoke.log
        # that already carried the P2E dispatch and could hide a
        # successfully sent diagnostic envelope. Judge P2E from the
        # SAME evidence the success path uses — no assumption, no
        # erasure. E2P stays FAIL/null: it never dispatched.
        local p2e_r="FAIL" p2e_cid_r="null"
        if [ -n "$cid_p2e" ] && _wss3_smoke_direction_ok_by_cid \
                "$ev/smoke.log" "$run_id" "$cell_p2e" phone emulator wss "$cid_p2e"; then
            p2e_r="OK"
            p2e_cid_r="$cid_p2e"
        fi
        _wss3_smoke_write_verdict "$ev" "$run_id" "$p2e_r" FAIL RED \
            "$p2e_cid_r" null pin_unconfirmed "" "" e2p "$pin_fail"
        return 1
    fi
    wss3_adb_invoke_diag_cmd "$emu"   send --es run_id "$run_id" --es cell_id "$cell_e2p" --ei sequence 1 >/dev/null
    cid_e2p=$(_wss3_wait_for_dispatched_cid "$emu" "$run_id" "$cell_e2p" 1 120)
    if [ -n "$cid_e2p" ]; then
        wss3_adb_wait_for_cid_completion "$phone" "$run_id" "$cell_e2p" "$cid_e2p" 120 && waited_e2p=1 || :
    fi
    # ── Audit ROUND-30.11: the FINAL clear is strict too ──────────────
    # GREEN may not be written over an unconfirmed clear. Leaving a pin
    # set is what the next run inherits, and "we tried" is not evidence.
    #
    # This failure is NOT a pre-send abort: both directions dispatched
    # and were delivered. Forcing it into `pre_send_reason` would demand
    # `e2p=FAIL` with a null cid and would erase a proven delivery —
    # false evidence of exactly the kind ROUND-29.2 removed. It gets its
    # own post-send fields, and both real CIDs survive.
    local final_clear_fail=""
    final_clear_fail=$(_wss3_clear_both_pins_strict \
        "$phone" phone "$emu" emulator "$run_id" "$cell_e2p")

    # Snapshot both sides into smoke.log for archival + verifier consumption.
    _wss3_smoke_snapshot_log "$phone" "$emu" "$ev"

    # One shared authority returns the whole direction state. Keeping
    # a separate shell success predicate after sharing only the failure
    # predicate left one last drift: a legal retry with two fresh
    # attempts was GREEN to the verifier and FAIL to the producer.
    local _p2e_sound=0 _e2p_sound=0
    local _global_surface_sound=0
    local _p2e_state="INVALID" _e2p_state="INVALID"
    if [ -n "$cid_p2e" ]; then
        _p2e_state=$(_wss3_smoke_direction_status \
            "$ev/smoke.log" "$run_id" "$cell_p2e" \
            phone emulator wss "$cid_p2e") || _p2e_state="INVALID"
    fi
    if [ -n "$cid_e2p" ]; then
        _e2p_state=$(_wss3_smoke_direction_status \
            "$ev/smoke.log" "$run_id" "$cell_e2p" \
            emulator phone wss "$cid_e2p") || _e2p_state="INVALID"
    fi
    if _wss3_smoke_global_surface_ok \
            "$ev/smoke.log" "$run_id" "$cid_p2e" "$cid_e2p"; then
        _global_surface_sound=1
    fi

    local p2e="FAIL" e2p="FAIL" integrity="RED"
    case "$_p2e_state" in
        OK) _p2e_sound=1; p2e="OK" ;;
        ACCOUNTED) _p2e_sound=1 ;;
    esac
    case "$_e2p_state" in
        OK) _e2p_sound=1; e2p="OK" ;;
        ACCOUNTED) _e2p_sound=1 ;;
    esac
    # ── Audit ROUND-30.18: integrity describes the EVIDENCE ────────
    #
    # `p2e`/`e2p` already say whether each direction delivered. This flag
    # says whether what we recorded can be believed, and a failure that
    # says exactly why it failed is believable. Writing RED for any
    # non-OK direction meant an honest failure report reached the
    # verifier wearing the same label as a broken capture, and the
    # verifier's ability to tell them apart went unused.
    if [ "$_p2e_sound" = "1" ] && [ "$_e2p_sound" = "1" ] \
            && [ "$_global_surface_sound" = "1" ]; then integrity="GREEN"; fi

    # ── Audit ROUND-30.11: the clear verdict is applied LAST ─────────
    # Order matters and is deliberate. Both directions are judged
    # FIRST, from their real CIDs and the same evidence the ordinary
    # success path uses, so a proven send and delivery keeps its
    # result and its correlation id. Only then does an unconfirmed
    # final clear force integrity to RED, and it is recorded in its
    # own post-send fields.
    #
    # Judging the clear first, or letting it rewrite the directions,
    # would erase a delivery that actually happened — the smoke would
    # report less than it observed, which is the failure mode this
    # tooling exists to avoid.
    local post_reason="" post_stage="" post_emitter=""
    if [ -n "$final_clear_fail" ]; then
        integrity="RED"
        post_reason="pin_clear_unconfirmed"
        post_stage="final"
        post_emitter="$final_clear_fail"
    fi

    # Send phase ran: no pre-send fields are emitted at all.
    #
    # Audit ROUND-30.20: the verdict writer's status IS the manifest
    # seal's status, and an unsealed profile cannot be reported GREEN
    # - the verifier will refuse evidence with no manifest, so a
    # `smoke: GREEN` over one is a claim the evidence cannot support.
    local _sealed=0
    _wss3_smoke_write_verdict "$ev" "$run_id" "$p2e" "$e2p" "$integrity" \
        "${cid_p2e:-null}" "${cid_e2p:-null}" "" "" "" "" "" \
        "$post_reason" "$post_stage" "$post_emitter" || _sealed=$?
    if [ "$_sealed" != "0" ]; then
        echo "smoke: evidence manifest was not sealed (rc=$_sealed) — refusing to report a verdict over unsealed evidence" >&2
        return 1
    fi
    # Audit ROUND-30.18: the smoke PASSES on delivery, not on the
    # trustworthiness of its own record. Now that `integrity` describes
    # the evidence, returning it here made a smoke whose delivery failed
    # report success and let `full` run - the separation of the two
    # questions has to reach the answer this function gives its caller,
    # or it only moved the conflation somewhere less visible.
    [ "$p2e" = "OK" ] && [ "$e2p" = "OK" ] && [ "$integrity" = "GREEN" ]
}

# _wss3_smoke_snapshot_log <phone> <emu> <ev>
#   Audit ROUND-29.1: every terminal smoke path — including the two
#   fail-closed pre-send aborts — snapshots both logcats into
#   smoke.log. On an abort the file is what proves the negative:
#   the health/pin breadcrumbs are present and there is NO
#   `diagnostic_send_dispatched` on either canonical smoke cell.
#   Writes only inside the evidence dir for THIS attempt; never
#   touches runtime state or retry lineage.
_wss3_smoke_snapshot_log() {
    local phone="$1" emu="$2" ev="$3"
    local tmp_p="$ev/.smoke.phone.log" tmp_e="$ev/.smoke.emu.log"
    wss3_adb_logcat_snapshot "$phone" "$tmp_p"
    wss3_adb_logcat_snapshot "$emu"   "$tmp_e"
    cat "$tmp_p" "$tmp_e" > "$ev/smoke.log"
    rm -f "$tmp_p" "$tmp_e"
}

# ══════════════════════════════════════════════════════════════════
# Audit ROUND-30.11 — clearing a pin is an operation with a result
# ══════════════════════════════════════════════════════════════════
#
# The old clear sent `pin --es pin none` with no `run_id` and no
# `cell_id`. The real receiver validates both before it does anything —
# `handlePin` returns early on `!validRunId(runId) || !validCellId(cellId)`
# — so on real devices the clear was REJECTED every time. It looked like
# it worked because the outer EXIT trap cleared the pins afterwards using
# the valid form, and because the mock accepted the invalid call and
# returned success.
#
# Two forms exist now, and which one a caller uses is a decision about
# evidence, not about tidiness:
#
#   strict       the run may not continue until BOTH devices have
#                confirmed a fresh `pin=none`. Used after every direction
#                and every cell that COMPLETED, including the last one:
#                a stale pin on either device mis-attributes whatever
#                runs next, and a GREEN written over an unconfirmed clear
#                is a claim nobody checked.
#   best-effort  used only where the run is already failing, and in the
#                EXIT trap. There, a clear that cannot be reached must
#                not overwrite the original verdict with a worse one.
#
# The clear carries a DERIVED cell id, `<cell>.clear`, never the parent
# cell id. Reusing the parent would put a second `diagnostic_pin_active`
# into the cell's dual-party set, where exactly one per party is allowed,
# and a healthy run would turn RED. The derived id is not a way to hide
# from the verifier: it is linked to its parent and checked there.
#
# `.` is inside the receiver's CELL_ID_EXTRA, so the derived id passes
# `validCellId` — the same shape the EXIT trap already uses for
# `trap.clear`.

# wss3_pin_clear_cell_id <cell>
#   The one place the derived clear identity is formed.
wss3_pin_clear_cell_id() {
    printf '%s.clear' "$1"
}

# _wss3_pin_clear_one <serial> <emitter> <run_id> <cell> [timeout_s]
#   Issues a VALID clear and confirms the resulting breadcrumb. Returns
#   0 only when a fresh `pin=none` for this emitter, run and derived
#   cell has actually appeared.
_wss3_pin_clear_one() {
    local serial="$1" emitter="$2" run_id="$3" cell="$4"
    local timeout_s="${5:-30}"
    local clear_cell tmp before after deadline
    clear_cell=$(wss3_pin_clear_cell_id "$cell")
    tmp=$(mktemp -t wss3-pinclear.XXXXXX)
    wss3_adb_logcat_snapshot "$serial" "$tmp"
    before=$(_wss3_pin_active_count "$tmp" "$emitter" "$run_id" "$clear_cell" none)
    if ! wss3_adb_invoke_diag_cmd "$serial" pin \
            --es pin none --es run_id "$run_id" --es cell_id "$clear_cell" >/dev/null 2>&1; then
        rm -f "$tmp"
        return 1
    fi
    deadline=$(( $(date +%s) + timeout_s ))
    while :; do
        wss3_adb_logcat_snapshot "$serial" "$tmp"
        after=$(_wss3_pin_active_count "$tmp" "$emitter" "$run_id" "$clear_cell" none)
        if [ "$after" -gt "$before" ]; then
            rm -f "$tmp"
            return 0
        fi
        if [ "${WSS3_DRY_RUN:-}" = "1" ]; then break; fi
        if [ "$(date +%s)" -ge "$deadline" ]; then break; fi
        sleep 1
    done
    rm -f "$tmp"
    return 1
}

# ── Audit ROUND-30.11: serial and emitter travel TOGETHER ────────
#
# The first version of these two helpers took `<phone> <emu>` and
# derived the emitter from the position. `wss3_live_full` calls them
# with `<sender> <recipient>`, which is the same pair for a p2e cell
# and the REVERSED pair for an e2p one — so on every e2p cell the
# clear was sent to one device while its confirmation was awaited from
# the other. It could never confirm, and the run aborted with
# `pin_clear_unconfirmed` on evidence that plainly carried both
# breadcrumbs: the producer contradicting its own capture.
#
# The pair is now explicit at every call site, exactly as
# `_wss3_pin_both_confirmed` already does it. A caller passing
# sender/recipient is then correct BY CONSTRUCTION rather than by
# coincidence of direction, and the positional trap is gone.

# _wss3_clear_both_pins <serial_a> <emitter_a> <serial_b> <emitter_b>
#                       <run_id> <cell>
#   BEST-EFFORT. Only for a path that is already failing, and for the
#   EXIT trap. Always returns 0: a device that cannot be reached must not
#   replace the verdict that is already being written.
_wss3_clear_both_pins() {
    local serial_a="$1" emitter_a="$2" serial_b="$3" emitter_b="$4"
    local run_id="$5" cell="$6"
    _wss3_pin_clear_one "$serial_a" "$emitter_a" "$run_id" "$cell" 5 >/dev/null 2>&1 || true
    _wss3_pin_clear_one "$serial_b" "$emitter_b" "$run_id" "$cell" 5 >/dev/null 2>&1 || true
    return 0
}

# _wss3_clear_both_pins_strict <serial_a> <emitter_a> <serial_b> <emitter_b>
#                              <run_id> <cell>
#   STRICT. Prints the side(s) that did not confirm, and returns
#   non-zero, so the caller stops instead of carrying a stale pin into
#   whatever runs next. What it prints is the EMITTER, which is what
#   the verdict records and the verifier reads.
_wss3_clear_both_pins_strict() {
    local serial_a="$1" emitter_a="$2" serial_b="$3" emitter_b="$4"
    local run_id="$5" cell="$6"
    local a_ok=1 b_ok=1
    _wss3_pin_clear_one "$serial_a" "$emitter_a" "$run_id" "$cell" || a_ok=0
    _wss3_pin_clear_one "$serial_b" "$emitter_b" "$run_id" "$cell" || b_ok=0
    # ── Audit ROUND-30.11: a CLOSED value, never a joined list ───────
    # This is serialised into the verdict and checked against an enum.
    # A free-form scalar like "phone,emulator" would pass through the
    # producer, fail the schema, and — worse — invite readers to parse
    # it, so the two-sided case gets its own canonical name here rather
    # than being reconstructed downstream.
    if [ "$a_ok" = "0" ] && [ "$b_ok" = "0" ]; then
        printf both
        return 1
    fi
    if [ "$a_ok" = "0" ]; then
        printf '%s' "$emitter_a"
        return 1
    fi
    if [ "$b_ok" = "0" ]; then
        printf '%s' "$emitter_b"
        return 1
    fi
    return 0
}


# _wss3_smoke_global_surface_ok <smoke.log> <run_id> <p2e_cid> <e2p_cid>
#   A direction query is deliberately scoped: a later E2P failure cannot
#   erase a P2E delivery already proved. Two scoped queries, however, leave
#   records owned by neither direction unseen. Before the producer writes
#   integrity GREEN it therefore runs the verifier's whole-log authority once
#   over the complete snapshot and both declared CIDs.
_wss3_smoke_global_surface_ok() {
    local log="$1" run_id="$2" p2e_cid="$3" e2p_cid="$4"
    [ -f "$log" ] || return 1
    local pkg
    pkg="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    WSS3_PKG_DIR="$pkg" WSS3_SOUND_LOG="$log" \
    WSS3_SOUND_RUN_ID="$run_id" WSS3_SOUND_P2E_CID="$p2e_cid" \
    WSS3_SOUND_E2P_CID="$e2p_cid" \
        python3 - <<'PY'
import os, sys
sys.path.insert(0, os.environ["WSS3_PKG_DIR"])
import verify_evidence_wss3 as V

_events, issues = V.smoke_global_surface_audit(
    os.environ["WSS3_SOUND_LOG"],
    expected_run_id=os.environ["WSS3_SOUND_RUN_ID"],
    expected_cids=(
        os.environ.get("WSS3_SOUND_P2E_CID") or None,
        os.environ.get("WSS3_SOUND_E2P_CID") or None,
    ),
)
if issues:
    for issue in issues:
        sys.stderr.write("smoke global surface: %s\n" % issue)
    sys.exit(1)
PY
}


# _wss3_smoke_direction_status <smoke.log> <run_id> <cell_id>
#                              <sender> <recipient> <pin> <cid>
#   Prints OK, ACCOUNTED or INVALID from the verifier's authority.
#
#   A direction that did not deliver has failed. Whether the EVIDENCE is
#   sound is a different question, and the producer used to answer both
#   with one flag: any non-OK direction wrote `integrity=RED`, so a smoke
#   that failed and recorded exactly why was indistinguishable from one
#   whose capture was broken. R30.17 separated the two in the verifier
#   and left the producer writing the old conflation, so the verifier
#   never saw the state it had been taught to read.
#
#   Delivery and evidence soundness are the verifier's questions, so the
#   verifier answers both: this
#   calls `verify_evidence_wss3.smoke_direction_soundness`, which runs
#   the same scoped event-surface sweep and the same per-CID direction
#   audit the verifier runs, and reports one three-state answer. The
#   producer separately runs `_wss3_smoke_global_surface_ok` over the whole
#   snapshot before it may write integrity GREEN.
#
#   Audit ROUND-30.20: the two previous attempts at this predicate were
#   both private re-implementations, and both were weaker than the rule
#   they claimed to mirror. R30.18 compared TOTALS, so two failure
#   records bound to the same attempt passed. R30.19 fixed that in awk
#   but aggregated by run+cell+emitter with no `correlation_id` and
#   repeated none of the role, stage or enum checks, so a fresh
#   delivery on one CID and a settlement on another - both numbered
#   `attempt=1` - passed the producer and were refused by the verifier.
#
#   A copy of a rule is a second rule. The old success helper was still
#   a second rule after the accounted predicate became shared: it kept
#   the envelope-level `fresh == 1` check and rejected a legal retry
#   whose second attempt settled. The shared authority now decides both
#   success and accounted failure, bound to the producer's run, cell and
#   dispatched CID.
_wss3_smoke_direction_status() {
    local log="$1" run_id="$2" cell="$3" sender="$4" recipient="$5"
    local pin="$6" cid="$7"
    [ -f "$log" ] || return 1
    local pkg
    pkg="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    WSS3_PKG_DIR="$pkg" WSS3_SOUND_LOG="$log" \
    WSS3_SOUND_SENDER="$sender" WSS3_SOUND_RECIPIENT="$recipient" \
    WSS3_SOUND_PIN="$pin" WSS3_SOUND_RUN_ID="$run_id" \
    WSS3_SOUND_CELL_ID="$cell" WSS3_SOUND_CID="$cid" \
        python3 - <<'PY'
import os, sys
sys.path.insert(0, os.environ["WSS3_PKG_DIR"])
import verify_evidence_wss3 as V

r = V.smoke_direction_soundness(
    os.environ["WSS3_SOUND_LOG"],
    sender=os.environ["WSS3_SOUND_SENDER"],
    recipient=os.environ["WSS3_SOUND_RECIPIENT"],
    pin=os.environ["WSS3_SOUND_PIN"],
    expected_run_id=os.environ["WSS3_SOUND_RUN_ID"],
    expected_cell_id=os.environ["WSS3_SOUND_CELL_ID"],
    expected_cid=os.environ["WSS3_SOUND_CID"] or None,
)
if r["ok"]:
    print("OK")
elif r["accounted"]:
    print("ACCOUNTED")
else:
    print("INVALID")
PY
}

# Compatibility predicate for focused tests and old callers. Runtime
# verdict construction consumes the full three-state answer above.
_wss3_smoke_direction_accounted() {
    local log="$1" run_id="$2" cell="$3" recipient="$4"
    local sender
    case "$recipient" in
        emulator) sender=phone ;;
        phone)    sender=emulator ;;
        *)        return 1 ;;
    esac
    [ "$(_wss3_smoke_direction_status \
        "$log" "$run_id" "$cell" "$sender" "$recipient" wss "")" = \
        "ACCOUNTED" ]
}

# wss3_write_evidence_manifest <evidence-dir> [smoke|full]
#   Audit ROUND-30.19. Writes the per-profile SHA256SUMS.txt over
#   every file currently in the evidence directory (§5 last row; Q7
#   immutability). Called from every point where the profile can STOP:
#   each smoke verdict write (a RED or aborted smoke is a terminal
#   profile and the verifier refuses terminal evidence without a
#   manifest), and the end of `full`, which rewrites it so the matrix
#   artefacts are covered. A stale smoke-era manifest under a full run
#   that died mid-matrix FAILS the hash gate - that is the honest
#   answer, not a gap.
wss3_write_evidence_manifest() {
    local _ev="$1" _stage="${2:-any}"
    # Audit ROUND-30.20: this function's answer is AUTHORITATIVE. It
    # used to end in a `( ... || ... )` whose status nobody read, so a
    # failed seal was invisible and the verb could still announce
    # GREEN over unsealed evidence. A manifest is not a courtesy
    # artefact: the verifier refuses evidence without one, and every
    # later hash check depends on it.
    #
    # `WSS3_MANIFEST_WRITER_RC` is a test seam. It is honoured only
    # when set, forces this exact failure path, and exists because a
    # write failure cannot be provoked reliably on a healthy
    # filesystem - and an untested failure path is not a path.
    if [ -n "${WSS3_MANIFEST_WRITER_RC:-}" ] \
       && [ "${WSS3_MANIFEST_WRITER_RC:-0}" != "0" ] \
       && { [ -z "${WSS3_MANIFEST_WRITER_FAIL_STAGE:-}" ] \
            || [ "${WSS3_MANIFEST_WRITER_FAIL_STAGE}" = "$_stage" ]; }; then
        echo "evidence manifest: forced failure rc=${WSS3_MANIFEST_WRITER_RC} (test seam)" >&2
        return "${WSS3_MANIFEST_WRITER_RC}"
    fi
    # With nothing to hash, `xargs` still runs the hasher once with no
    # arguments; it then hashes STDIN and writes a line for `-` — a
    # manifest describing the empty pipe, reported as a successful
    # seal. `xargs -r` would suppress that but is not portable to the
    # target's BSD xargs, and an evidence directory with no files is
    # not a sealable profile in the first place (audit ROUND-30.20).
    local _n
    _n=$( (cd "$_ev" 2>/dev/null && find . -type f ! -name SHA256SUMS.txt)           | wc -l | tr -d '[:space:]' )
    if [ "${_n:-0}" -eq 0 ]; then
        echo "evidence manifest: nothing to seal in $_ev" >&2
        return 1
    fi
    (
        cd "$_ev" || exit 1
        if command -v sha256sum >/dev/null 2>&1; then
            find . -type f ! -name SHA256SUMS.txt -print0 | \
                LC_ALL=C sort -z | \
                xargs -0 sha256sum > SHA256SUMS.txt
        else
            find . -type f ! -name SHA256SUMS.txt -print0 | \
                LC_ALL=C sort -z | \
                xargs -0 shasum -a 256 > SHA256SUMS.txt
        fi
    ) || { echo "evidence manifest: hashing failed for $_ev" >&2; return 1; }
    # Verified, not assumed: an empty or absent manifest is a failed
    # seal even when every command reported success.
    if [ ! -s "$_ev/SHA256SUMS.txt" ]; then
        echo "evidence manifest: SHA256SUMS.txt missing or empty in $_ev" >&2
        return 1
    fi
    return 0
}

# _wss3_smoke_write_verdict <ev> <run_id> <p2e> <e2p> <integrity>
#                          <p2e_cid|null> <e2p_cid|null>
#                          <pre_send_reason|""> <pre_send_detail|"">
#
#   Writes smoke_verdict.json bound to PROFILE.json's attempt_id +
#   arm_token (audit ROUND-17 P0-3).
#
#   Audit ROUND-29.1 P1-2: R29 emitted `pre_send_reason` plus a
#   free-text `pre_send_detail` ("phone=X emu=Y") that the closed
#   schema had never registered, so `check_registered_surface`
#   reported both as unknown keys and the producer diverged from the
#   schema authority. The detail string is now replaced by per-device
#   enum-bounded fields registered in SMOKE_VERDICT_SCHEMA:
#
#     pre_send_reason       direct_unavailable | health_probe_failed
#                           | pin_unconfirmed
#     pre_send_phone_outer  outer_transport reported by the phone
#     pre_send_emu_outer    outer_transport reported by the emulator
#     pre_send_pin_stage    p2e | e2p
#     pre_send_pin_emitter  phone | emulator
#
#   Each field is emitted ONLY for the reason it belongs to, so a
#   GREEN verdict carries none of them; cross-field consistency is
#   enforced independently in `load_smoke_verdict`.
_wss3_smoke_write_verdict() {
    local ev="$1" run_id="$2" p2e="$3" e2p="$4" integrity="$5"
    local cid_p2e="$6" cid_e2p="$7" pre_reason="$8"
    local pre_phone_outer="${9}" pre_emu_outer="${10}"
    local pre_pin_stage="${11}" pre_pin_emitter="${12}"
    # Audit ROUND-30.11: post-send cleanup fields. Separate from the
    # pre-send set on purpose — a pre-send abort means nothing was
    # dispatched, while these describe a run whose directions both
    # completed and whose CLEANUP did not.
    local post_reason="${13:-}" post_stage="${14:-}" post_emitter="${15:-}"
    WSS3_P2E="$p2e" WSS3_E2P="$e2p" WSS3_INT="$integrity" \
        WSS3_CID_P2E="$cid_p2e" WSS3_CID_E2P="$cid_e2p" \
        WSS3_WALL="$(now_ms)" WSS3_EV="$ev" WSS3_SMOKE_RUN="$run_id" \
        WSS3_PRE_REASON="$pre_reason" \
        WSS3_PRE_PHONE_OUTER="$pre_phone_outer" \
        WSS3_PRE_EMU_OUTER="$pre_emu_outer" \
        WSS3_PRE_PIN_STAGE="$pre_pin_stage" \
        WSS3_PRE_PIN_EMITTER="$pre_pin_emitter" \
        WSS3_POST_REASON="$post_reason" \
        WSS3_POST_STAGE="$post_stage" \
        WSS3_POST_EMITTER="$post_emitter" \
        python3 - "$ev/smoke_verdict.json" <<'PY'
import json, os, sys
ev = os.environ["WSS3_EV"]
try:
    with open(os.path.join(ev, "PROFILE.json"), encoding="utf-8") as f:
        prof = json.load(f)
    attempt_id = prof.get("attempt_id", "")
    arm_token  = prof.get("arm_token", "")
except (OSError, json.JSONDecodeError):
    attempt_id = ""
    arm_token  = ""
obj = {
    "p2e": os.environ["WSS3_P2E"],
    "e2p": os.environ["WSS3_E2P"],
    "integrity": os.environ["WSS3_INT"],
    "at_wall_ms": int(os.environ["WSS3_WALL"]),
    "p2e_cid": None if os.environ["WSS3_CID_P2E"] == "null" else os.environ["WSS3_CID_P2E"],
    "e2p_cid": None if os.environ["WSS3_CID_E2P"] == "null" else os.environ["WSS3_CID_E2P"],
    "attempt_id": attempt_id,
    "arm_token":  arm_token,
    "smoke_run_id": os.environ["WSS3_SMOKE_RUN"],
}
# Audit ROUND-29.1 P1-2: bounded pre-send fields, each emitted only
# for the reason it belongs to. Empty env value means "not applicable
# to this path" and the key is omitted entirely — the closed schema
# declares every one of them OPTIONAL.
_pre_reason = os.environ.get("WSS3_PRE_REASON", "")
if _pre_reason:
    obj["pre_send_reason"] = _pre_reason
    for _key, _env in (
        ("pre_send_phone_outer", "WSS3_PRE_PHONE_OUTER"),
        ("pre_send_emu_outer",   "WSS3_PRE_EMU_OUTER"),
        ("pre_send_pin_stage",   "WSS3_PRE_PIN_STAGE"),
        ("pre_send_pin_emitter", "WSS3_PRE_PIN_EMITTER"),
    ):
        _val = os.environ.get(_env, "")
        if _val:
            obj[_key] = _val
# Audit ROUND-30.11 — post-send cleanup fields.
#
# These describe a run whose directions BOTH completed and whose final
# clear did not confirm. They are a separate set from the pre-send
# fields on purpose: a pre-send abort means nothing was dispatched and
# its rules demand e2p=FAIL with a null cid, which here would erase a
# delivery that is proven in smoke.log. The two sets are mutually
# exclusive by construction — the code paths that write them cannot
# both run — and the verifier enforces that.
_post_reason = os.environ.get("WSS3_POST_REASON", "")
if _post_reason:
    obj["post_send_reason"] = _post_reason
    for _key, _env in (
        ("post_send_clear_stage",   "WSS3_POST_STAGE"),
        ("post_send_clear_emitter", "WSS3_POST_EMITTER"),
    ):
        _val = os.environ.get(_env, "")
        if _val:
            obj[_key] = _val
with open(sys.argv[1], "w", encoding='utf-8') as f:
    json.dump(obj, f, sort_keys=True, indent=2)
PY
    # Audit ROUND-30.19: a written verdict means the profile can stop
    # here, and a stoppable profile owes a manifest. `full` rewrites it
    # later if the matrix runs.
    #
    # Audit ROUND-30.20: this is the LAST command on purpose - its
    # status is this function's status, and every caller is required
    # to read it. R30.19 sealed the manifest and then let the answer
    # fall on the floor.
    wss3_write_evidence_manifest "$ev" smoke
}

# _wss3_parse_health_line <line>
#   Splits one `WSS_DIAG_CMD: health …` line into five positional
#   fields. Field order mirrors DiagnosticCommandReceiver
#   .handleHealth(): emitter_id, pin, run_id, cell_id,
#   outer_transport. Missing pieces degrade to `unknown` / `-`
#   rather than to an empty string, so a caller can never mistake a
#   parse miss for a healthy value.
_wss3_parse_health_line() {
    local line="$1"
    local ot pin rid cid emi
    ot=$(printf '%s' "$line"  | grep -oE 'outer_transport=[A-Za-z_]+'   | tail -1 | cut -d= -f2 || true)
    pin=$(printf '%s' "$line" | grep -oE ' pin=[A-Za-z_]+'              | tail -1 | cut -d= -f2 || true)
    rid=$(printf '%s' "$line" | grep -oE ' run_id=[^ ]+'                | tail -1 | cut -d= -f2 || true)
    cid=$(printf '%s' "$line" | grep -oE ' cell_id=[^ ]+'               | tail -1 | cut -d= -f2 || true)
    emi=$(printf '%s' "$line" | grep -oE 'health emitter_id=[A-Za-z_]+' | tail -1 | cut -d= -f2 || true)
    printf '%s %s %s %s %s\n' "${ot:-unknown}" "${pin:--}" "${rid:--}" "${cid:--}" "${emi:--}"
}

# _wss3_health_probe <serial> [timeout_s]
#   Audit ROUND-29.1 P0: FRESH health authority, replacing the R29
#   passive scan of the most recent `sender_transport_decision`.
#
#   The accepted `health` subcommand is invoked UNCONDITIONALLY, then
#   this bounded-polls for a `health emitter_id=` line that appeared
#   AFTER that exact invocation. Freshness is proven by COUNT, not by
#   "take the latest": a historical line cannot raise the count, so a
#   stale `direct` can never authorise a send. Prints five fields:
#
#       <outer_transport> <pin> <run_id> <cell_id> <emitter_id>
#
#   A non-zero broadcast, a timeout or a missing fresh line prints
#   `probe_failed - - - -` and returns 1 — there is deliberately no
#   device value to misread on that path. Never touches evidence
#   directories or runtime state.
_wss3_health_probe() {
    local serial="$1" timeout_s="${2:-30}"
    local tmp before after line deadline
    tmp=$(mktemp -t wss3-health.XXXXXX)
    wss3_adb_logcat_snapshot "$serial" "$tmp"
    before=$(grep -c 'health emitter_id=' "$tmp" 2>/dev/null || true)
    [ -n "$before" ] || before=0
    if ! wss3_adb_invoke_diag_cmd "$serial" health >/dev/null 2>&1; then
        rm -f "$tmp"
        printf 'probe_failed - - - -\n'
        return 1
    fi
    deadline=$(( $(date +%s) + timeout_s ))
    while :; do
        wss3_adb_logcat_snapshot "$serial" "$tmp"
        after=$(grep -c 'health emitter_id=' "$tmp" 2>/dev/null || true)
        [ -n "$after" ] || after=0
        if [ "$after" -gt "$before" ]; then
            line=$(grep 'health emitter_id=' "$tmp" | tail -1 || true)
            rm -f "$tmp"
            _wss3_parse_health_line "$line"
            return 0
        fi
        # The dry-run mock is synchronous: one miss means never.
        if [ "${WSS3_DRY_RUN:-}" = "1" ]; then break; fi
        if [ "$(date +%s)" -ge "$deadline" ]; then break; fi
        sleep 1
    done
    rm -f "$tmp"
    printf 'probe_failed - - - -\n'
    return 1
}

# _wss3_pin_active_count <file> <emitter> <run_id> <cell> <expect_pin>
#   Counts `diagnostic_pin_active` breadcrumbs in a logcat snapshot
#   that match ALL of emitter, run_id, cell_id and the EXPECTED pin.
#   Trailing spaces in each pattern stop a shorter id prefix-matching
#   a longer one.
#
#   Audit ROUND-29.2 P1-2: `expect_pin` is a parameter, not the
#   hard-coded `wss` of R29.1 — the full matrix also runs `rest`
#   control cells and they need the same proof.
# ══════════════════════════════════════════════════════════════════
# Audit ROUND-30.11 — one authority for matching a log field
# ══════════════════════════════════════════════════════════════════
#
# A diagnostic line is whitespace-separated `key=value` fields. Matching
# one used to be done ad hoc, and both possible mistakes were present at
# once:
#
#   grep -F "pin=$want "     a TRAILING SPACE. The real receiver emits
#                            the pin as the LAST field — `... pin=wss`
#                            with nothing after it — so the genuine
#                            breadcrumb matched nothing and the pre-send
#                            confirmation could never succeed. That is
#                            what stopped the physical smoke before its
#                            first dispatch.
#
#   grep -F "run_id=$want"   NO boundary at all, which matches any field
#                            that merely STARTS with the value:
#                            `run_id=abc1` also matches `run_id=abc12`.
#
# Neither is repaired by adjusting the pattern, because a regex over a
# value would then need every metacharacter escaped — `.` and `-` occur
# in cell ids and uuids — and one missed escape reintroduces the same
# class silently. So the comparison is not a pattern at all: awk splits
# the line on whitespace and each field is compared for EXACT string
# equality. End of line is a boundary because the last field simply ends;
# a prefix cannot match because the strings differ; nothing needs
# escaping because nothing is interpreted.
#
# wss3_log_count_tokens <file> <key=value> [<key=value> ...]
#   Prints the number of lines in <file> on which EVERY given token
#   appears as a complete field. Prints 0 for a missing file. Refuses a
#   token containing a newline, which cannot be a field.
wss3_log_count_tokens() {
    local file="$1"; shift
    local joined="" t
    [ -n "$file" ] || { printf '0\n'; return 1; }
    for t in "$@"; do
        case "$t" in
            *"
"*) printf '0\n'; return 1 ;;
        esac
        joined="$joined$t
"
    done
    [ -n "$joined" ] || { printf '0\n'; return 1; }
    # An unreadable file must still print a NUMBER: awk exits before
    # its END block when it cannot open the input, so the caller would
    # get an empty string — and every caller feeds this into an
    # arithmetic comparison, where an empty operand is a shell error,
    # not a zero.
    [ -f "$file" ] || { printf '0\n'; return 0; }
    WSS3_LOG_TOKENS="$joined" awk '
        BEGIN {
            c = 0
            n = split(ENVIRON["WSS3_LOG_TOKENS"], raw, "\n")
            for (k = 1; k <= n; k++) if (raw[k] != "") { c++; want[c] = raw[k] }
        }
        {
            for (k = 1; k <= c; k++) hit[k] = 0
            for (f = 1; f <= NF; f++)
                for (k = 1; k <= c; k++)
                    if ($f == want[k]) hit[k] = 1
            ok = 1
            for (k = 1; k <= c; k++) if (hit[k] == 0) ok = 0
            if (ok) matched++
        }
        END { print matched + 0 }
    ' "$file" 2>/dev/null
    return 0
}

# wss3_log_has_tokens <file> <key=value> ...
#   Exit 0 when at least one line carries every token.
wss3_log_has_tokens() {
    [ "$(wss3_log_count_tokens "$@")" != "0" ]
}

# wss3_log_first_line_with_tokens <file> <key=value> ...
#   Prints the FIRST line carrying every token, or nothing.
wss3_log_first_line_with_tokens() {
    local file="$1"; shift
    local joined="" t
    [ -n "$file" ] || return 1
    for t in "$@"; do
        case "$t" in
            *"
"*) return 1 ;;
        esac
        joined="$joined$t
"
    done
    WSS3_LOG_TOKENS="$joined" awk '
        BEGIN {
            c = 0
            n = split(ENVIRON["WSS3_LOG_TOKENS"], raw, "\n")
            for (k = 1; k <= n; k++) if (raw[k] != "") { c++; want[c] = raw[k] }
            found = 0
        }
        found == 0 {
            for (k = 1; k <= c; k++) hit[k] = 0
            for (f = 1; f <= NF; f++)
                for (k = 1; k <= c; k++)
                    if ($f == want[k]) hit[k] = 1
            ok = 1
            for (k = 1; k <= c; k++) if (hit[k] == 0) ok = 0
            if (ok) { print; found = 1 }
        }
    ' "$file" 2>/dev/null
    return 0
}

_wss3_pin_active_count() {
    local f="$1" emitter="$2" run_id="$3" cell="$4" expect_pin="$5"
    # Audit ROUND-30.11: every field goes through the one token
    # authority. `pin=` is the LAST field the real receiver emits for
    # this event, so the previous trailing-space match counted a
    # genuine breadcrumb as zero and the pre-send confirmation could
    # never succeed. That is what stopped the physical smoke before
    # its first dispatch.
    wss3_log_count_tokens "$f" \
        "event=diagnostic_pin_active" \
        "emitter_id=$emitter" \
        "run_id=$run_id" \
        "cell_id=$cell" \
        "pin=$expect_pin"
}

# _wss3_pin_one_confirmed <serial> <emitter> <run_id> <cell> <expect_pin> [timeout_s]
#   Audit ROUND-29.1 P1-3: a pin is proven, never assumed. Two gates:
#     1. the pin broadcast itself MUST return zero;
#     2. a FRESH `diagnostic_pin_active` matching run_id, cell_id,
#        the EXPECTED pin and this emitter MUST appear afterwards.
#   Freshness is proven by count, so a breadcrumb left over from an
#   earlier direction or an earlier cell cannot satisfy the gate.
_wss3_pin_one_confirmed() {
    local serial="$1" emitter="$2" run_id="$3" cell="$4" expect_pin="$5"
    local timeout_s="${6:-30}"
    local tmp before after deadline
    tmp=$(mktemp -t wss3-pin.XXXXXX)
    wss3_adb_logcat_snapshot "$serial" "$tmp"
    before=$(_wss3_pin_active_count "$tmp" "$emitter" "$run_id" "$cell" "$expect_pin")
    if ! wss3_adb_invoke_diag_cmd "$serial" pin \
            --es pin "$expect_pin" --es run_id "$run_id" --es cell_id "$cell" >/dev/null 2>&1; then
        rm -f "$tmp"
        return 1
    fi
    deadline=$(( $(date +%s) + timeout_s ))
    while :; do
        wss3_adb_logcat_snapshot "$serial" "$tmp"
        after=$(_wss3_pin_active_count "$tmp" "$emitter" "$run_id" "$cell" "$expect_pin")
        if [ "$after" -gt "$before" ]; then
            rm -f "$tmp"
            return 0
        fi
        if [ "${WSS3_DRY_RUN:-}" = "1" ]; then break; fi
        if [ "$(date +%s)" -ge "$deadline" ]; then break; fi
        sleep 1
    done
    rm -f "$tmp"
    return 1
}

# _wss3_pin_both_confirmed <a_serial> <a_emitter> <b_serial> <b_emitter>
#                          <run_id> <cell> <expect_pin>
#   Pins BOTH devices to the SAME cell with the SAME expected pin and
#   confirms both. Prints the failing emitter name (`phone` or
#   `emulator`) on stdout, or nothing when both are confirmed — the
#   caller uses that string as the bounded emitter value.
_wss3_pin_both_confirmed() {
    local a_ser="$1" a_emit="$2" b_ser="$3" b_emit="$4"
    local run_id="$5" cell="$6" expect_pin="$7"
    if ! _wss3_pin_one_confirmed "$a_ser" "$a_emit" "$run_id" "$cell" "$expect_pin"; then
        printf '%s\n' "$a_emit"
        return 0
    fi
    if ! _wss3_pin_one_confirmed "$b_ser" "$b_emit" "$run_id" "$cell" "$expect_pin"; then
        printf '%s\n' "$b_emit"
        return 0
    fi
    return 0
}

# _wss3_wait_for_dispatched_cid <serial> <run_id> <cell_id> <sequence> <timeout_s>
#   Bounded-polls the SENDER serial's logcat for the specific
#   diagnostic_send_dispatched event matching (run_id, cell_id,
#   sequence). Extracts and prints the correlation_id from that
#   event; empty print + return 1 on timeout.
_wss3_wait_for_dispatched_cid() {
    local serial="$1" run_id="$2" cell="$3" seq="$4" timeout_s="${5:-120}"
    local deadline
    deadline=$(( $(date +%s) + timeout_s ))
    local tmp
    tmp=$(mktemp -t wss3-cid.XXXXXX)
    while [ "$(date +%s)" -lt "$deadline" ]; do
        wss3_adb_logcat_snapshot "$serial" "$tmp"
        local line
        # Audit ROUND-30.11: exact field tokens. Unbounded matching
        # here accepted any field that merely STARTED with the value,
        # so `sequence=1` also matched `sequence=12`.
        line=$(wss3_log_first_line_with_tokens "$tmp" \
            "event=diagnostic_send_dispatched" \
            "run_id=$run_id" "cell_id=$cell" "sequence=$seq")
        if [ -n "$line" ]; then
            local cid
            cid=$(printf '%s\n' "$line" | grep -oE 'correlation_id=[^ ]+' | cut -d= -f2 | head -1)
            rm -f "$tmp"
            printf '%s' "$cid"
            return 0
        fi
        # In dry-run the mock is synchronous — one snapshot is enough.
        if [ "${WSS3_DRY_RUN:-}" = "1" ]; then
            rm -f "$tmp"
            return 1
        fi
        sleep 2
    done
    rm -f "$tmp"
    return 1
}

# _wss3_smoke_direction_ok_by_cid <log> <run_id> <cell> <sender> <recipient> <pin> <cid>
#   Verifies that ALL events (sender chain + recipient triplet) are
#   present on the SAME cid. Also verifies outer_transport=direct
#   and inner_route=<pin> on sender_transport_decision.
_wss3_smoke_direction_ok_by_cid() {
    local log="$1" run_id="$2" cell="$3" sender="$4" recipient="$5" pin="$6" cid="$7"
    [ "$(_wss3_smoke_direction_status \
        "$log" "$run_id" "$cell" "$sender" "$recipient" "$pin" "$cid")" = \
        "OK" ]
}

# _wss3_smoke_direction_ok <log> <run_id> <cell_id> <sender-emitter> <recipient-emitter> <pin>
#   Grep-strict check that ONE direction's full route is present:
#     - sender_transport_decision (dispatched=true, correct outer+inner)
#     - route return (WSS: sender_wss_send_returned; REST:
#       sender_rest_post_completed)
#     - sender_relay_ack_received
#     - recipient triplet with exactly ONE dedup_gate=fresh
#   All lines must carry the matching run_id AND cell_id AND emitter.
_wss3_smoke_direction_ok() {
    local log="$1" run_id="$2" cell="$3" sender="$4" recipient="$5" pin="$6"
    local rr_line
    if [ "$pin" = "rest" ]; then rr_line="sender_rest_post_completed"; else rr_line="sender_wss_send_returned"; fi
    # Multi-conjunction check — every condition must match on ONE
    # line (chained greps against successive grep-fed subsets so
    # linear-regex field-order issues are avoided).
    _match() {
        local evt="$1"; shift
        local line=""
        # Audit ROUND-30.11: exact tokens, no prefix matching.
        wss3_log_has_tokens "$log" "event=$evt" "$@"
    }
    _match sender_transport_decision "run_id=$run_id" "cell_id=$cell" "emitter_id=$sender" "dispatched=true" || return 1
    _match "$rr_line"                 "run_id=$run_id" "cell_id=$cell" "emitter_id=$sender" "dispatched=true" || return 1
    _match sender_relay_ack_received "run_id=$run_id" "cell_id=$cell" "emitter_id=$sender" || return 1
    _match recipient_deliver_received "run_id=$run_id" "cell_id=$cell" "emitter_id=$recipient" "dedup_gate=fresh" || return 1
    _match recipient_message_persisted "run_id=$run_id" "cell_id=$cell" "emitter_id=$recipient" || return 1
    _match recipient_ack_deliver_sent  "run_id=$run_id" "cell_id=$cell" "emitter_id=$recipient" || return 1
    # Exactly one fresh for this run+cell+recipient.
    local fresh
    fresh=$(wss3_log_count_tokens "$log" \
        "event=recipient_deliver_received" "run_id=$run_id" \
        "cell_id=$cell" "emitter_id=$recipient" "dedup_gate=fresh")
    [ "$fresh" = "1" ]
}

# ── live full (§3.3 + audit ROUND-9 P0-1/P0-4/P0-6)
#
# Real 8-cell matrix with per-scenario behaviour:
#   - after-connect: fire immediately
#   - after-idle: 300 s idle wait before firing (0 s in dry-run)
#   - bg-fg: HOME + delay + MainActivity relaunch (0 s in dry-run)
# Pre-cell checkpoint runs BEFORE every cell; post-matrix checkpoint
# runs AFTER the last cell. Each checkpoint result is appended to
# checkpoint_log.json (§4.6 + §5). Per-cell delivered_count is
# computed from OBSERVED CIDs with full recipient triplet — no
# pre-assumed success.
# matrix_completion.json + matrix_verdict.json + per-profile
# SHA256SUMS.txt materialised from real PROFILE.json + observed
# cell_verdict counts (audit P0-4/P0-6).
# _wss3_write_cell_verdict <out> <cell> <blocked> <delivered> <observed>
#                          [post_cell_clear_emitter]
#
#   Audit ROUND-30.11 — the ONE writer of a cell verdict.
#
#   This was an inline heredoc inside `wss3_live_full`, and that body
#   cannot be driven from a focused fixture (the testability note in
#   the shell suite records why). A rule that lives only inside an
#   untestable body is a rule nobody ever watched fail, so the writer
#   is a named function the fixtures call directly. They exercise the
#   same code the run uses; nothing is re-implemented test-side.
#
#   A failed post-cell clear is recorded ALONGSIDE the real counters,
#   never instead of them: those envelopes were dispatched and
#   delivered, and reporting less than was observed is the false
#   evidence this round exists to remove.
#
#   `abort_reason` travels WITH the emitter. `post_cell_clear_emitter`
#   belongs to `pin_clear_unconfirmed` alone, and the verifier rejects
#   it under any other reason — a missing one included — so writing the
#   emitter without its reason would produce evidence the verifier is
#   required to reject.
_wss3_write_cell_verdict() {
    local out="$1" cell="$2" blocked="$3" delivered="$4" observed="$5"
    local clear_fail="${6:-}"
    WSS3_CELL="$cell" WSS3_BLOCKED="$blocked" \
        WSS3_DELIVERED="$delivered" WSS3_OBSERVED="$observed" \
        WSS3_CELL_CLEAR="$clear_fail" \
        python3 - "$out" <<'PY'
import json, os, sys
d = int(os.environ["WSS3_DELIVERED"])
o = int(os.environ["WSS3_OBSERVED"])
obj = {
    "cell_id": os.environ["WSS3_CELL"],
    "blocked": os.environ["WSS3_BLOCKED"] == "true",
    "envelopes_dispatched": o,  # actual diagnostic_send_dispatched observed
    "delivered_count": d,
    "unresolved_count": max(0, o - d),
    "verdict": "Delivered" if (o >= 5 and d >= 5) else "Unresolved",
}
_clear = os.environ.get("WSS3_CELL_CLEAR", "")
if _clear:
    obj["post_cell_clear_emitter"] = _clear
    obj["abort_reason"] = "pin_clear_unconfirmed"
with open(sys.argv[1], "w", encoding="utf-8") as f:
    json.dump(obj, f, sort_keys=True, indent=2)
PY
}

wss3_live_full() {
    local phone="$1" emu="$2" run_id="$3" ev="$4" rest_blocked="$5"
    mkdir -p "$ev/matrix_cells"
    local cells_expected=8
    if [ "$rest_blocked" = "true" ]; then
        cells_expected=6
    fi
    local cells_ran=0
    local abort_reason="null"
    # Audit ROUND-29.3 P1-1: an abort stops RUNNING cells, it does not
    # stop MATERIALISING them. The contract (§4.4) pins the on-disk
    # manifest at eight rows always — "two of them may say
    # blocked=true" — and explicitly expects `cells_ran < 8` alongside
    # a non-null `abort_reason`. R29.2 used `break`, which left three
    # directories and three matrix_verdict rows.
    local matrix_aborted=0

    # Live durations (§4.3). Dry-run uses zero-second stubs — the mock
    # ADB is synchronous.
    local IDLE_SECONDS=300
    local BGFG_SECONDS=15
    if [ "${WSS3_DRY_RUN:-}" = "1" ]; then
        IDLE_SECONDS=0
        BGFG_SECONDS=0
    fi

    local started_wall_ms started_mono_ms
    started_wall_ms=$(now_ms)
    started_mono_ms=$(python3 -c 'import time; print(int(time.monotonic()*1000))')

    # Initialise checkpoint_log.json — appended via atomic rewrite
    # per checkpoint below.
    printf '[]' > "$ev/checkpoint_log.json"

    local exp_op exp_pvpn exp_hvpn host_vpn_service relay pid_only
    exp_op=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['expected_operator_numeric'])" "$ev/PROFILE.json")
    exp_pvpn=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['phone_vpn'])" "$ev/PROFILE.json")
    exp_hvpn=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['host_vpn'])" "$ev/PROFILE.json")
    host_vpn_service="${WSS3_HOST_VPN_SERVICE:-}"
    if [ -z "$host_vpn_service" ]; then
        pid_only=$(basename "$ev" | sed -E 's/-[0-9]{8}T[0-9]{6}Z$//')
        host_vpn_service=$(wss3_read_service "$WSS3_RUNTIME_ROOT" "$pid_only" 2>/dev/null || echo "")
    fi
    relay="${WSS3_RELAY_HOST:-relay.phntm.pro}"

    # Cached preflight anchors for cross-check.
    local expected_apk_sha expected_sub_id
    expected_apk_sha=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['apk_sha256'])" "$ev/PROFILE.json")
    if [ -f "$ev/PROFILE_STATE.json" ]; then
        expected_sub_id=$(python3 -c "import json,sys; j=json.load(open(sys.argv[1])); print(j.get('phone_observed',{}).get('active_data_subscription_id',''))" "$ev/PROFILE_STATE.json" 2>/dev/null || echo "")
    fi

    _wss3_run_checkpoint() {
        local label="$1"
        local ck_hex
        ck_hex=$(wss3_mint_checkpoint_key)
        export WSS3_CHECKPOINT_KEY_HEX="$ck_hex"
        local before_wall after_wall before_mono after_mono result specific_reason=""
        before_wall=$(now_ms)
        before_mono=$(python3 -c 'import time; print(int(time.monotonic()*1000))')

        # Re-check APK SHA on both devices (audit ROUND-10 P0-5).
        # Audit ROUND-11 P0-#6 tail: an EMPTY observed hash is not a
        # pass — it means the device probe returned no data (adb dead,
        # package uninstalled, DUMP-perm denied). Treat empty as drift
        # with a distinct reason so operators can diagnose the class.
        local apk_now_phone apk_now_emu
        apk_now_phone=$(wss3_adb_apk_sha256 "$phone" phantom.android 2>/dev/null || echo "")
        apk_now_emu=$(wss3_adb_apk_sha256 "$emu"   phantom.android 2>/dev/null || echo "")
        local apk_drift=false
        if [ -z "$expected_apk_sha" ]; then
            apk_drift=true; specific_reason="apk_sha_drift"
        elif [ -z "$apk_now_phone" ] || [ -z "$apk_now_emu" ]; then
            apk_drift=true; specific_reason="apk_sha_drift"
        elif [ "$apk_now_phone" != "$expected_apk_sha" ]; then
            apk_drift=true; specific_reason="apk_sha_drift"
        elif [ "$apk_now_emu" != "$expected_apk_sha" ]; then
            apk_drift=true; specific_reason="apk_sha_drift"
        fi

        # Re-check paired-count on both devices.
        local paired_ok=true paired_p paired_e
        if [ "$apk_drift" != "true" ]; then
            wss3_adb_logcat_clear "$phone" >/dev/null 2>&1 || true
            wss3_adb_logcat_clear "$emu"   >/dev/null 2>&1 || true
            wss3_adb_invoke_diag_cmd "$phone" paired_count_report >/dev/null 2>&1 || true
            wss3_adb_invoke_diag_cmd "$emu"   paired_count_report >/dev/null 2>&1 || true
            local pc_tmp1 pc_tmp2
            pc_tmp1=$(mktemp -t wss3-pc-cp-p.XXXXXX); pc_tmp2=$(mktemp -t wss3-pc-cp-e.XXXXXX)
            wss3_adb_logcat_snapshot "$phone" "$pc_tmp1"
            wss3_adb_logcat_snapshot "$emu"   "$pc_tmp2"
            paired_p=$(grep -oE 'paired_count_report count=[0-9]+' "$pc_tmp1" 2>/dev/null | tail -1 | cut -d= -f2)
            paired_e=$(grep -oE 'paired_count_report count=[0-9]+' "$pc_tmp2" 2>/dev/null | tail -1 | cut -d= -f2)
            rm -f "$pc_tmp1" "$pc_tmp2"
            if [ "${paired_p:-0}" != "1" ] || [ "${paired_e:-0}" != "1" ]; then
                paired_ok=false; specific_reason="pair_lost"
            fi
        fi

        # Re-check signed-prekey readiness.
        local sp_ok=true
        if [ "$apk_drift" != "true" ] && [ "$paired_ok" = "true" ]; then
            wss3_adb_logcat_clear "$phone" >/dev/null 2>&1 || true
            wss3_adb_logcat_clear "$emu"   >/dev/null 2>&1 || true
            wss3_adb_invoke_diag_cmd "$phone" signed_prekey_readiness >/dev/null 2>&1 || true
            wss3_adb_invoke_diag_cmd "$emu"   signed_prekey_readiness >/dev/null 2>&1 || true
            local sp_tmp1 sp_tmp2
            sp_tmp1=$(mktemp -t wss3-sp-cp-p.XXXXXX); sp_tmp2=$(mktemp -t wss3-sp-cp-e.XXXXXX)
            wss3_adb_logcat_snapshot "$phone" "$sp_tmp1"
            wss3_adb_logcat_snapshot "$emu"   "$sp_tmp2"
            if ! grep -q "signed_prekey_readiness published=true" "$sp_tmp1" || \
               ! grep -q "signed_prekey_readiness published=true" "$sp_tmp2"; then
                sp_ok=false; specific_reason="signed_prekey_regressed"
            fi
            rm -f "$sp_tmp1" "$sp_tmp2"
        fi

        # Network drift + host+emu HMAC parity via wss3_live_checkpoint.
        # Only run if the earlier structural checks passed.
        local drift_ok=true
        local host_vpn_ok_bool="null"
        if [ "$apk_drift" != "true" ] && [ "$paired_ok" = "true" ] && [ "$sp_ok" = "true" ]; then
            local cp_err
            # Audit ROUND-24 CONTINUATION E: pre-cell + post-matrix
            # checkpoints MUST NOT overwrite baseline files. Run
            # `wss3_live_checkpoint` in observe mode — it still does
            # every drift/HMAC-parity check with a fresh checkpoint
            # key but leaves EGRESS_FINGERPRINT.json /
            # network_profile.json / PROFILE_STATE.json untouched.
            cp_err=$(WSS3_CHECKPOINT_MODE=observe \
                    wss3_live_checkpoint "$phone" "$emu" "$exp_op" "$exp_pvpn" "$exp_hvpn" \
                    "$host_vpn_service" "$relay" "$ck_hex" "$ev" 2>&1)
            if [ $? -ne 0 ]; then
                drift_ok=false
                host_vpn_ok_bool="false"
                # Map wss3_live_checkpoint's error output to a specific
                # abort_reason. Categories match the §4.4 enum.
                if printf '%s' "$cp_err" | grep -q "carrier_attribution_lost"; then
                    specific_reason="carrier_attribution_lost"
                elif printf '%s' "$cp_err" | grep -q "wifi_active_or_mobile_data_off"; then
                    specific_reason="wifi_active_or_mobile_data_off"
                elif printf '%s' "$cp_err" | grep -q "phone_vpn_state_mismatch"; then
                    specific_reason="network_profile_drift"
                elif printf '%s' "$cp_err" | grep -q "path_divergence_host_emu"; then
                    specific_reason="path_divergence_host_emu"
                elif printf '%s' "$cp_err" | grep -q "mixed_address_family"; then
                    specific_reason="mixed_address_family"
                elif printf '%s' "$cp_err" | grep -q "host_vpn_unverifiable"; then
                    specific_reason="host_vpn_unverifiable"
                elif printf '%s' "$cp_err" | grep -q "host_vpn_other_service_active"; then
                    specific_reason="host_vpn_other_service_active"
                else
                    specific_reason="network_profile_drift"
                fi
            else
                host_vpn_ok_bool="true"
            fi
        fi

        # Audit ROUND-11 P0-#5 + P0-#6: capture the phone-observed
        # fields that wss3_live_checkpoint just wrote to network_profile.json
        # so the checkpoint entry itself is auditable end-to-end. In
        # particular, `active_data_subscription_id` — read at line 484
        # before ROUND-11 but never re-checked mid-run — is now
        # explicitly compared against `expected_sub_id`; a mismatch
        # (same operator, swapped SIM) forces carrier_attribution_lost.
        local obs_wifi="null" obs_mobile="null" obs_vpn="null"
        local obs_sub_id="" obs_op_num=""
        if [ -f "$ev/network_profile.json" ]; then
            obs_wifi=$(python3 -c "import json,sys; j=json.load(open(sys.argv[1])); v=j.get('phone',{}).get('wifi_enabled'); print('true' if v is True else 'false' if v is False else 'null')" "$ev/network_profile.json" 2>/dev/null || echo "null")
            obs_mobile=$(python3 -c "import json,sys; j=json.load(open(sys.argv[1])); v=j.get('phone',{}).get('mobile_data_enabled'); print('true' if v is True else 'false' if v is False else 'null')" "$ev/network_profile.json" 2>/dev/null || echo "null")
            # Audit ROUND-12 P0-#2: the Android reporter emits
            # `has_transport_vpn` (see
            # DiagnosticNetworkProfileReporter.kt:537). Reading a
            # non-existent `vpn_active` field returned null on real
            # devices; the ROUND-11 mock template shipped both aliases
            # which masked the divergence. Use the production key only.
            obs_vpn=$(python3 -c "import json,sys; j=json.load(open(sys.argv[1])); v=j.get('phone',{}).get('has_transport_vpn'); print('true' if v is True else 'false' if v is False else 'null')" "$ev/network_profile.json" 2>/dev/null || echo "null")
            obs_sub_id=$(python3 -c "import json,sys; j=json.load(open(sys.argv[1])); print(j.get('phone',{}).get('active_data_subscription_id',''))" "$ev/network_profile.json" 2>/dev/null || echo "")
            obs_op_num=$(python3 -c "import json,sys; j=json.load(open(sys.argv[1])); print(j.get('phone',{}).get('active_data_sim_operator_numeric',''))" "$ev/network_profile.json" 2>/dev/null || echo "")
            if [ "$drift_ok" = "true" ] && [ -n "$expected_sub_id" ] && [ -n "$obs_sub_id" ] && [ "$obs_sub_id" != "$expected_sub_id" ]; then
                drift_ok=false
                specific_reason="carrier_attribution_lost"
            fi
        fi

        after_wall=$(now_ms)
        after_mono=$(python3 -c 'import time; print(int(time.monotonic()*1000))')
        if [ "$apk_drift" = "true" ] || [ "$paired_ok" != "true" ] || [ "$sp_ok" != "true" ] || [ "$drift_ok" != "true" ]; then
            result="drift"
        else
            result="ok"
        fi
        unset WSS3_CHECKPOINT_KEY_HEX

        # Audit ROUND-11 P0-#6: the checkpoint entry must record EVERY
        # observed field the run branches on, so verifiers can catch
        # false-GREEN caused by a checkpoint that "passed" on partial
        # data. Adds: signed-prekey published (both sides), host-VPN
        # ok, phone Wi-Fi/mobile/VPN observed, active SIM sub_id +
        # operator numeric.
        WSS3_EV="$ev" WSS3_LBL="$label" \
            WSS3_BEFORE_W="$before_wall" WSS3_AFTER_W="$after_wall" \
            WSS3_BEFORE_M="$before_mono" WSS3_AFTER_M="$after_mono" \
            WSS3_RESULT="$result" WSS3_REASON="$specific_reason" \
            WSS3_APK_P="$apk_now_phone" WSS3_APK_E="$apk_now_emu" \
            WSS3_PAIRED_P="${paired_p:-0}" WSS3_PAIRED_E="${paired_e:-0}" \
            WSS3_SP_OK="$sp_ok" WSS3_HVPN_OK="$host_vpn_ok_bool" \
            WSS3_OBS_WIFI="$obs_wifi" WSS3_OBS_MOBILE="$obs_mobile" \
            WSS3_OBS_VPN="$obs_vpn" WSS3_OBS_SUB_ID="$obs_sub_id" \
            WSS3_OBS_OP="$obs_op_num" WSS3_EXP_SUB_ID="$expected_sub_id" \
            python3 - "$ev/checkpoint_log.json" <<'PY'
import json, os, sys
path = sys.argv[1]
with open(path, encoding='utf-8') as f:
    arr = json.load(f)
ef = None
efp = os.path.join(os.environ["WSS3_EV"], "EGRESS_FINGERPRINT.json")
if os.path.isfile(efp):
    try:
        with open(efp, encoding='utf-8') as f: ef = json.load(f)
    except Exception:
        ef = None
def _tri(env_key):
    v = os.environ.get(env_key, "null")
    return True if v == "true" else False if v == "false" else None
reason = os.environ.get("WSS3_REASON", "")
entry = {
    "label": os.environ["WSS3_LBL"],
    "started_at_wall_ms": int(os.environ["WSS3_BEFORE_W"]),
    "completed_at_wall_ms": int(os.environ["WSS3_AFTER_W"]),
    "started_at_monotonic_ms": int(os.environ["WSS3_BEFORE_M"]),
    "completed_at_monotonic_ms": int(os.environ["WSS3_AFTER_M"]),
    "result": os.environ["WSS3_RESULT"],
    "drift": (os.environ["WSS3_RESULT"] != "ok"),
    "abort_reason": (reason if reason else None),
    "observed": {
        "apk_sha256_phone": os.environ.get("WSS3_APK_P") or None,
        "apk_sha256_emu":   os.environ.get("WSS3_APK_E") or None,
        "paired_count_phone": int(os.environ.get("WSS3_PAIRED_P", 0)),
        "paired_count_emu":   int(os.environ.get("WSS3_PAIRED_E", 0)),
        "signed_prekey_published_phone": os.environ.get("WSS3_SP_OK") == "true",
        "signed_prekey_published_emu":   os.environ.get("WSS3_SP_OK") == "true",
        "host_vpn_ok": _tri("WSS3_HVPN_OK"),
        "phone_wifi_enabled":         _tri("WSS3_OBS_WIFI"),
        "phone_mobile_data_enabled":  _tri("WSS3_OBS_MOBILE"),
        "phone_has_transport_vpn":    _tri("WSS3_OBS_VPN"),
        "phone_active_data_subscription_id":     os.environ.get("WSS3_OBS_SUB_ID") or None,
        "phone_active_data_sim_operator_numeric": os.environ.get("WSS3_OBS_OP") or None,
        "expected_active_data_subscription_id":   os.environ.get("WSS3_EXP_SUB_ID") or None,
    },
    "host_hmac_fp_hex": (ef or {}).get("host_hmac_fp_hex"),
    "emu_hmac_fp_hex":  (ef or {}).get("emu_hmac_fp_hex"),
    "equal_host_emu":   (ef or {}).get("equal_host_emu"),
    "address_family_host": (ef or {}).get("address_family_host"),
    "address_family_emu":  (ef or {}).get("address_family_emu"),
}
arr.append(entry)
with open(path, "w", encoding='utf-8') as f:
    json.dump(arr, f, sort_keys=True, indent=2)
PY
        # Propagate specific reason to caller via env for the abort_reason
        # write in matrix_completion.
        WSS3_LAST_CHECKPOINT_REASON="$specific_reason"
        export WSS3_LAST_CHECKPOINT_REASON
        [ "$result" = "ok" ]
    }

    for cell in "${WSS3_CANONICAL_CELLS[@]}"; do
        local cdir="$ev/matrix_cells/$cell"
        mkdir -p "$cdir"
        local blocked=false
        local pin=wss
        if wss3_is_rest_cell "$cell"; then
            pin=rest
            if [ "$rest_blocked" = "true" ]; then
                blocked=true
            fi
        fi
        # cell_verdict.json — write baseline; updated after events collected.
        # Audit ROUND-18 P0-1: include envelopes_dispatched=0 in the
        # baseline so a non-blocked cell that never gets updated still
        # matches the verifier's closed schema. Blocked REST cells
        # ALSO need the terminal `verdict=BLOCKED` immediately (skip
        # path never rewrites the file), so write it here.
        if [ "$blocked" = "true" ]; then
            cat > "$cdir/cell_verdict.json" <<EOF
{"cell_id": "$cell", "blocked": true, "envelopes_dispatched": 0, "delivered_count": 0, "unresolved_count": 0, "verdict": "BLOCKED"}
EOF
            continue
        fi
        cat > "$cdir/cell_verdict.json" <<EOF
{"cell_id": "$cell", "blocked": false, "envelopes_dispatched": 0, "delivered_count": 0, "unresolved_count": 0, "verdict": "PENDING"}
EOF
        # Audit ROUND-29.3 P1-1: once the matrix has aborted, every
        # REMAINING cell still gets its directory and a truthful
        # never-attempted row (zero counts, `verdict=PENDING`) so the
        # manifest keeps its eight rows. PENDING is only legitimate
        # under a non-null matrix abort — the verifier enforces that,
        # preserving the ROUND-18 P0-1 rule that a PENDING cell on a
        # normal run is a producer bug.
        if [ "$matrix_aborted" = "1" ]; then
            continue
        fi
        # Pre-cell checkpoint (§4.6). Drift → abort + set specific reason.
        if ! _wss3_run_checkpoint "pre-cell:$cell"; then
            abort_reason="${WSS3_LAST_CHECKPOINT_REASON:-network_profile_drift}"
            [ -z "$abort_reason" ] && abort_reason="network_profile_drift"
            matrix_aborted=1
            continue
        fi
        # Per-scenario behaviour (§4.3).
        local ser
        case "$cell" in
            *.after-idle)
                [ "$IDLE_SECONDS" -gt 0 ] && sleep "$IDLE_SECONDS"
                ;;
            *.bg-fg)
                ser="$phone"
                [[ "$cell" == *.e2p.* ]] && ser="$emu"
                wss3_adb -s "$ser" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
                [ "$BGFG_SECONDS" -gt 0 ] && sleep "$BGFG_SECONDS"
                wss3_adb -s "$ser" shell am start -n phantom.android/.MainActivity >/dev/null 2>&1 || true
                ;;
        esac
        # Determine sender + recipient for this cell.
        local sender_role sender_serial recipient_serial
        sender_role=$(wss3_cell_direction_emitter "$cell")
        if [ "$sender_role" = "phone" ]; then
            sender_serial="$phone"; recipient_serial="$emu"
        else
            sender_serial="$emu"; recipient_serial="$phone"
        fi
        wss3_adb_logcat_clear "$phone"
        wss3_adb_logcat_clear "$emu"
        # Audit ROUND-29 P0: pin BOTH devices to the SAME cell so
        # the mock (whose recipient events now derive cell_id from
        # the RECIPIENT's local pin — mirroring real device
        # behaviour) attributes recipient events to the sender's
        # cell rather than to a stale pin left over from the
        # previous cell iteration. Pins are cleared at end of each
        # iteration below to keep the next cell's attribution clean.
        # ── Audit ROUND-29.2 P1-2 ─────────────────────────────────
        # R29.1 confirmed pins for the smoke but left `full` firing
        # five envelopes per cell behind UNCONFIRMED pin broadcasts —
        # which could recreate the original false-attribution defect
        # inside the real matrix. The same two gates now apply here,
        # generalised over the cell's expected pin (`wss` or `rest`).
        # On failure the cell dispatches ZERO envelopes, records a
        # bounded per-cell abort, and the matrix stops.
        local recipient_role="emulator"
        if [ "$sender_role" = "emulator" ]; then recipient_role="phone"; fi
        local cell_pin_fail
        cell_pin_fail=$(_wss3_pin_both_confirmed \
            "$sender_serial" "$sender_role" \
            "$recipient_serial" "$recipient_role" \
            "$run_id" "$cell" "$pin")
        if [ -n "$cell_pin_fail" ]; then
            _wss3_clear_both_pins \
                "$sender_serial" "$sender_role" \
                "$recipient_serial" "$recipient_role" "$run_id" "$cell"
            wss3_adb_logcat_snapshot "$phone" "$cdir/.phone.log"
            wss3_adb_logcat_snapshot "$emu"   "$cdir/.emu.log"
            cat "$cdir/.phone.log" "$cdir/.emu.log" > "$cdir/capture.log"
            rm -f "$cdir/.phone.log" "$cdir/.emu.log"
            cat > "$cdir/cell_verdict.json" <<EOF
{"abort_reason": "pin_unconfirmed", "blocked": false, "cell_id": "$cell", "delivered_count": 0, "envelopes_dispatched": 0, "unresolved_count": 0, "verdict": "Unresolved"}
EOF
            abort_reason="pin_unconfirmed"
            matrix_aborted=1
            continue
        fi
        # Fire 5 envelopes; send subcommand is --async (audit P0-8).
        # Per audit ROUND-10 P0-3: for EACH sequence we bounded-poll
        # for the SPECIFIC diagnostic_send_dispatched event BEFORE
        # moving on so async races don't drop CIDs on the floor.
        local seq observed_cids=0 delivered=0
        local tmp_cids
        tmp_cids=$(mktemp -t wss3-cids.XXXXXX)
        : > "$tmp_cids"
        for seq in 1 2 3 4 5; do
            wss3_adb_invoke_diag_cmd "$sender_serial" send \
                --es run_id "$run_id" --es cell_id "$cell" --ei sequence "$seq" >/dev/null
            local seq_cid
            seq_cid=$(_wss3_wait_for_dispatched_cid "$sender_serial" "$run_id" "$cell" "$seq" 120)
            if [ -n "$seq_cid" ]; then
                printf '%s\n' "$seq_cid" >> "$tmp_cids"
                observed_cids=$((observed_cids+1))
            fi
        done
        # Per-CID recipient-triplet poll on RECIPIENT serial.
        local cid
        while IFS= read -r cid; do
            [ -z "$cid" ] && continue
            if wss3_adb_wait_for_cid_completion "$recipient_serial" "$run_id" "$cell" "$cid" 120; then
                delivered=$((delivered+1))
            fi
        done < "$tmp_cids"
        rm -f "$tmp_cids"
        # ── Audit ROUND-30.11: the post-cell clear is STRICT ──────
        # A pin left set is inherited by the NEXT cell, because
        # recipient events carry the recipient's own cell_id. R29 made
        # this best-effort and "informational", which meant a failed
        # clear was invisible and the next cell's attribution was
        # silently suspect. It is now a result the run acts on.
        #
        # What must NOT happen is erasing this cell. Its envelopes are
        # already dispatched and delivered, and the counters below are
        # observed facts. The cleanup failure aborts the MATRIX — later
        # cells materialise as PENDING through the existing discipline
        # — while this cell keeps its real numbers and its real verdict.
        local cell_clear_fail=""
        cell_clear_fail=$(_wss3_clear_both_pins_strict \
            "$sender_serial" "$sender_role" \
            "$recipient_serial" "$recipient_role" "$run_id" "$cell")
        if [ -n "$cell_clear_fail" ]; then
            abort_reason="pin_clear_unconfirmed"
            matrix_aborted=1
            WSS3_CELL_CLEAR_FAIL="$cell_clear_fail"
        fi
        # Truthful envelopes_sent (audit P1-8): observed dispatched
        # count, NOT a hard-coded 5. Stored on cell_verdict so
        # matrix_completion aggregates truthfully.

        wss3_adb_logcat_snapshot "$phone" "$cdir/.phone.log"
        wss3_adb_logcat_snapshot "$emu"   "$cdir/.emu.log"
        cat "$cdir/.phone.log" "$cdir/.emu.log" > "$cdir/capture.log"
        # Audit ROUND-11 P0-#1: `$snap` from the pre-ROUND-10 code path was
        # never re-assigned after the CID-join rewrite; under `set -u`
        # (which the orchestrator runs) referencing it crashes on the
        # first cell and prevents matrix_completion.json from being
        # written. Only the two named snapshot files are cleaned up.
        rm -f "$cdir/.phone.log" "$cdir/.emu.log"

        _wss3_write_cell_verdict "$cdir/cell_verdict.json" \
            "$cell" "$blocked" "$delivered" "$observed_cids" \
            "${WSS3_CELL_CLEAR_FAIL:-}"
        cells_ran=$((cells_ran+1))
    done

    # Post-matrix checkpoint (§4.6). Drift → abort_reason set (specific).
    if [ "$abort_reason" = "null" ]; then
        if ! _wss3_run_checkpoint "post-matrix"; then
            abort_reason="${WSS3_LAST_CHECKPOINT_REASON:-network_profile_drift}"
            [ -z "$abort_reason" ] && abort_reason="network_profile_drift"
        fi
    fi

    # Truthful matrix_completion.json (audit P0-4). Pulls profile_id,
    # attempt_id, supersedes, retry_reason from PROFILE.json.
    # envelopes_recipient_triplet_complete = sum of cell_verdict
    # delivered_counts across non-blocked cells. Real monotonic
    # timestamps. Atomic tmp→fsync→rename write.
    local mc="$ev/matrix_completion.json"
    local tmp="${mc}.tmp.$$"
    local completed_wall_ms completed_mono_ms
    completed_wall_ms=$(now_ms)
    completed_mono_ms=$(python3 -c 'import time; print(int(time.monotonic()*1000))')
    WSS3_EV="$ev" WSS3_RAN="$cells_ran" \
        WSS3_EXP="$cells_expected" WSS3_ABORT="$abort_reason" \
        WSS3_SW="$started_wall_ms" WSS3_SM="$started_mono_ms" \
        WSS3_CW="$completed_wall_ms" WSS3_CM="$completed_mono_ms" \
        python3 - "$tmp" <<'PY'
import json, os, sys, glob
ev = os.environ["WSS3_EV"]
with open(os.path.join(ev, "PROFILE.json"), encoding='utf-8') as f:
    prof = json.load(f)
delivered = 0; sent = 0
for cv_path in glob.glob(os.path.join(ev, "matrix_cells", "*", "cell_verdict.json")):
    with open(cv_path, encoding='utf-8') as f:
        cv = json.load(f)
    if cv.get("blocked"):
        continue
    # Audit ROUND-10 P1-8: envelopes_sent = observed diagnostic_send_dispatched
    # count (envelopes_dispatched), not a hard-coded 5.
    sent += int(cv.get("envelopes_dispatched", 0))
    delivered += int(cv.get("delivered_count", 0))
ar = os.environ["WSS3_ABORT"]
if ar == "null":
    ar = None
abort_at = None
if ar is not None:
    abort_at = int(os.environ["WSS3_CW"])
obj = {
    "schema_version": "1",
    "run_id": prof["run_id"],
    "profile_id": (prof["carrier"].lower() + "-phone-" + prof["phone_vpn"] +
                   "-host-" + prof["host_vpn"]),
    "attempt_id": prof["attempt_id"],
    "supersedes_attempt_id": prof.get("supersedes_attempt_id"),
    "retry_reason": prof.get("retry_reason"),
    "cells_declared": 8,
    "cells_expected_to_run": int(os.environ["WSS3_EXP"]),
    "cells_ran": int(os.environ["WSS3_RAN"]),
    "envelopes_sent": sent,
    "envelopes_recipient_triplet_complete": delivered,
    "started_at_wall_ms": int(os.environ["WSS3_SW"]),
    "completed_at_wall_ms": int(os.environ["WSS3_CW"]),
    "started_at_monotonic_ms": int(os.environ["WSS3_SM"]),
    "completed_at_monotonic_ms": int(os.environ["WSS3_CM"]),
    "abort_reason": ar,
    "abort_at_wall_ms": abort_at,
}
with open(sys.argv[1], "w", encoding='utf-8') as f:
    json.dump(obj, f, sort_keys=True, indent=2)
    f.flush()
    os.fsync(f.fileno())
PY
    mv "$tmp" "$mc"

    # matrix_verdict.json roll-up (§5).
    WSS3_EV="$ev" python3 - "$ev/matrix_verdict.json" <<'PY'
import json, os, sys, glob
ev = os.environ["WSS3_EV"]
with open(os.path.join(ev, "matrix_completion.json"), encoding='utf-8') as f:
    mc = json.load(f)
delivered = 0; unresolved = 0
cells = []
for cv_path in sorted(glob.glob(os.path.join(ev, "matrix_cells", "*", "cell_verdict.json"))):
    with open(cv_path, encoding='utf-8') as f:
        cv = json.load(f)
    cells.append({"cell_id": cv["cell_id"], "verdict": cv.get("verdict"), "blocked": cv.get("blocked", False)})
    delivered += int(cv.get("delivered_count", 0))
    unresolved += int(cv.get("unresolved_count", 0))
ar = mc.get("abort_reason")
integrity = "GREEN" if ar is None and mc.get("cells_ran") == mc.get("cells_expected_to_run") else "RED"
if integrity == "GREEN":
    outcome = "GREEN" if unresolved == 0 else "RED"
else:
    outcome = "NOT_EVALUABLE"
with open(sys.argv[1], "w", encoding='utf-8') as f:
    json.dump({
        "evidence_integrity": integrity,
        "product_outcome": outcome,
        "delivered_count": delivered,
        "unresolved_count": unresolved,
        "cells": cells,
    }, f, sort_keys=True, indent=2)
PY

    # per-profile SHA256SUMS.txt (§5 last row; Q7 immutability) -
    # rewritten over the smoke-era manifest so the matrix artefacts
    # are covered (audit ROUND-30.19).
    #
    # Audit ROUND-30.20: a failed rewrite fails the verb. Continuing
    # would leave the matrix artefacts covered by the smoke-era
    # manifest - evidence describing a tree that no longer exists -
    # and report `full: GREEN` over it.
    if ! wss3_write_evidence_manifest "$ev" full; then
        echo "full: evidence manifest rewrite failed — the matrix artefacts are not sealed" >&2
        return 1
    fi

    # Evidence-isolation sweep — audit ROUND-10 P0-6 fail-closed
    # discipline: on ANY denylist hit we quarantine the evidence dir
    # by renaming to `<dir>.QUARANTINED-<utc>` (still on disk for
    # forensic inspection) and return non-zero. The caller (verb
    # dispatcher) surfaces the failure to the operator.
    local denylist_hit=""
    if [ -n "${WSS3_HOST_VPN_SERVICE:-}" ]; then
        if ! wss3_evidence_sweep_raw_value "$ev" "$WSS3_HOST_VPN_SERVICE" >/dev/null 2>&1; then
            denylist_hit="host_vpn_service_value_leak"
        fi
    fi
    if [ -z "$denylist_hit" ]; then
        if ! wss3_evidence_sweep_checkpoint_key_substring "$ev" >/dev/null 2>&1; then
            denylist_hit="checkpoint_key_substring_leak"
        fi
    fi
    # Audit ROUND-18 P0-4: full producer-side privacy validator BEFORE
    # `full` returns success. Shares one denylist source-of-truth with
    # the verifier; catches credential/token aliases, unknown IP
    # literals, IPv6 forms, 64-hex identity shapes, and username /
    # vpn-provider / package-name / iface text patterns. Contract §7
    # rule 9 (producer MUST NOT persist a leaking evidence dir).
    if [ -z "$denylist_hit" ]; then
        if ! wss3_evidence_full_privacy_sweep "$ev" >/dev/null 2>&1; then
            denylist_hit="privacy_validator_hit"
        fi
    fi
    if [ -n "$denylist_hit" ]; then
        local qutc; qutc=$(date -u +%Y%m%dT%H%M%SZ)
        local qpath="${ev}.QUARANTINED-${denylist_hit}-${qutc}"
        mv "$ev" "$qpath" 2>/dev/null || true
        echo "wss3-live-full: denylist hit ($denylist_hit); evidence dir quarantined to $qpath" >&2
        return 5
    fi

    [ "$abort_reason" = "null" ] && [ "$cells_ran" = "$cells_expected" ]
}
