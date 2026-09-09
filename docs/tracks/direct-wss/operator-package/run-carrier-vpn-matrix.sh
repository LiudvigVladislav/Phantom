#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — top-level operator CLI.
#
# Verbs: preflight | smoke | full | resume | compare | cleanup [--all]
#
# Contract: docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md
# (Round-7 GREEN sheet, ARCHITECT ROUND-8 APPROVED). Every subcommand
# below implements the corresponding §3.x contract clause verbatim.
#
# Non-goals honoured:
#   - No modification to production onboarding/UI code.
#   - No modification to accepted WSS-2 lib/*.sh helpers beyond
#     additive parametrisation (§1).
#   - No `adb` invocation at all until architect + operator green-light
#     past the synthetic Mac dry-run (§1 last bullet). This script
#     supports a `WSS3_DRY_RUN=1` env var — when set, every helper
#     that would touch a real device is short-circuited into an
#     evidence-only path. The public verbs still exit with the same
#     codes so `tests/test_shell.sh` can drive the full state machine.
#
# The bulk of the logic lives in `lib/wss3-*.sh` so the shell fixtures
# under `tests/test_shell.sh` (§9 fixtures 46-52) call the REAL helpers
# — not private test copies.

set -euo pipefail

WSS3_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$WSS3_SCRIPT_DIR/lib"
# Honour an operator-supplied WSS3_OPERATOR_PACKAGE_ROOT (used by
# shell fixtures + synthetic Mac dry-run) so evidence/ and .runtime/
# can be redirected to a scratch dir without touching the real
# operator-package tree. Default: this script's directory.
WSS3_OPERATOR_PACKAGE_ROOT="${WSS3_OPERATOR_PACKAGE_ROOT:-$WSS3_SCRIPT_DIR}"
WSS3_ROOT="$WSS3_OPERATOR_PACKAGE_ROOT"
export WSS3_OPERATOR_PACKAGE_ROOT

# shellcheck source=lib/portable.sh
source "$LIB/portable.sh"
PORTABLE_SH_LOADED=1
# shellcheck source=lib/wss3-args.sh
source "$LIB/wss3-args.sh"
# shellcheck source=lib/wss3-runtime-state.sh
source "$LIB/wss3-runtime-state.sh"
WSS3_RUNTIME_STATE_SH_LOADED=1
# shellcheck source=lib/wss3-host-vpn.sh
source "$LIB/wss3-host-vpn.sh"
# shellcheck source=lib/wss3-egress-hmac.sh
source "$LIB/wss3-egress-hmac.sh"
# shellcheck source=lib/wss3-evidence.sh
source "$LIB/wss3-evidence.sh"
# shellcheck source=lib/wss3-cleanup.sh
source "$LIB/wss3-cleanup.sh"
# shellcheck source=lib/wss3-adb.sh
source "$LIB/wss3-adb.sh"
WSS3_ADB_SH_LOADED=1
WSS3_ADB_LOADED=1
# shellcheck source=lib/wss3-devices.sh
source "$LIB/wss3-devices.sh"
WSS3_DEVICES_SH_LOADED=1
# shellcheck source=lib/wss3-live.sh
source "$LIB/wss3-live.sh"

# ── EXIT/INT/TERM trap (§7 R16 + audit ROUND-8 P0-1)
#
# The trap fires pin=NONE on every device we know about, wipes any
# in-memory checkpoint_key hex, and (importantly) does NOT remove
# runtime state — retention across non-COMPLETE terminal states is
# the REDLINE-7 blocker 1 fix. Runtime removal happens only via a
# successful `compare` or explicit `cleanup [--all]` (§3.6 invariant
# 8).
WSS3_TRAP_PHONE_SERIAL=""
WSS3_TRAP_EMU_SERIAL=""
WSS3_TRAP_RUN_ID=""
# ── Audit ROUND-30.10: EXIT, INT and TERM are DIFFERENT events ────
#
# R30.9 served all three from one handler whose rollback was gated on
# `$? != 0`. A signal arriving between commands leaves `$?` at 0, so the
# transaction was not rolled back and the handler returned 0 — the
# process carried on after TERM. A signal is now its own event: it
# ALWAYS requests the rollback, never consults the previous status, and
# terminates with the conventional 128+signal code. The cleanup body
# runs exactly once however many paths reach it.
WSS3_CLEANUP_DONE=0

_wss3_cleanup_once() {
    [ "${WSS3_CLEANUP_DONE:-0}" = "0" ] || return 0
    WSS3_CLEANUP_DONE=1
    local force_rollback="${1:-0}"
    local rc="${2:-0}"
    if [ "$force_rollback" = "1" ] || { [ "$rc" -ne 0 ] && wss3_txn_active; }; then
        if wss3_txn_active; then
            wss3_txn_rollback >/dev/null 2>&1 || true
        fi
    fi
    # Audit ROUND-30.10 closure: an interrupted REMOVAL is finished
    # here too. Past its rename there is no transaction left to be
    # active — the lock moved with the tree — so this cannot be left
    # to the rollback above, and an unfinished tombstone or a claimed
    # name would otherwise outlive the run. The signal exit code is
    # unaffected: the handler exits 130 or 143 whatever happens here.
    wss3_removal_finish_tombstone >/dev/null 2>&1 || true
    wss3_removal_release_claim >/dev/null 2>&1 || true
    _wss3_resume_rollback_attempt "${WSS3_TXN_PID:-}" >/dev/null 2>&1 || true
    _wss3_txn_sweep_tmp 2>/dev/null || true
    _wss3_clear_device_pins
    # Audit ROUND-30.16: reap and dispose of the local WARN capture on
    # every terminal path — success, failure, INT and TERM all arrive
    # here exactly once. Never changes `$rc`.
    # Audit ROUND-30.17: stderr is where the disposer reports a capture
    # failure and the path of a retained sensitive log, so discarding it
    # here silently defeated both. The helper emits fixed, privacy-safe
    # sentences and never the log's contents, so passing them through is
    # safe; only the exit status is ignored, because a diagnostic must
    # not change a product verdict.
    wss3_local_diag_dispose "${WSS3_LOCAL_DIAG_EVIDENCE_DIR:-}" || true
    unset WSS3_CHECKPOINT_KEY_HEX WSS3_KEY_HEX_TMP 2>/dev/null || true
    return 0
}

_wss3_trap_signal() {
    local signame="$1" code="$2"
    # No `$?` anywhere on this path: a signal is a rollback request in
    # its own right.
    _wss3_cleanup_once 1 "$code"
    echo "aborted on $signame" >&2
    exit "$code"
}
trap '_wss3_trap_signal INT 130' INT
trap '_wss3_trap_signal TERM 143' TERM

_wss3_clear_device_pins() {
    if [ -n "${WSS3_TRAP_PHONE_SERIAL:-}" ] && [ -n "${WSS3_TRAP_RUN_ID:-}" ]; then
        wss3_adb_invoke_diag_cmd "$WSS3_TRAP_PHONE_SERIAL" pin \
            --es pin none --es run_id "$WSS3_TRAP_RUN_ID" --es cell_id "trap.clear" >/dev/null 2>&1 || true
    fi
    if [ -n "${WSS3_TRAP_EMU_SERIAL:-}" ] && [ -n "${WSS3_TRAP_RUN_ID:-}" ]; then
        wss3_adb_invoke_diag_cmd "$WSS3_TRAP_EMU_SERIAL" pin \
            --es pin none --es run_id "$WSS3_TRAP_RUN_ID" --es cell_id "trap.clear" >/dev/null 2>&1 || true
    fi
    return 0
}

_wss3_trap_cleanup() {
    local rc=$?
    # ── Audit ROUND-30.7: INT and TERM unwind the transaction ──────
    # A signal arriving between the first mutation and the commit used
    # to leave whatever had been written where it was: an armed profile
    # with no attempt, or a reserved attempt with no arm. The unwind
    # removes only what the in-flight transaction created; once a verb
    # commits it clears these fields, so a signal AFTER the commit
    # cannot touch the arm. Runtime retention across terminal states
    # (§3.6 invariant 7) is unaffected — that is about COMMITTED state.
    # Audit ROUND-30.10: EXIT is the ordinary end of the process. A
    # non-zero exit still unwinds an open transaction; a zero exit after
    # a committed verb has nothing to unwind, because the commit cleared
    # it. Signals do NOT arrive here — they have their own handlers,
    # which never consult `$?`.
    _wss3_cleanup_once 0 "$rc"
    # Audit ROUND-12 P0-#1: guarantee non-zero fatal exit propagates
    # to the caller across all Bash versions. On stock macOS Bash 3.2
    # a plain `return $rc` from an EXIT trap could be clobbered by
    # any intervening command (including the trap's own no-op returns)
    # that reset $?. Explicit `exit $rc` when non-zero commits the
    # exit code before the shell re-collapses.
    if [ "$rc" -ne 0 ]; then
        exit "$rc"
    fi
    return 0
}
trap _wss3_trap_cleanup EXIT

WSS3_EVIDENCE_ROOT="$WSS3_OPERATOR_PACKAGE_ROOT/evidence"
WSS3_RUNTIME_ROOT="$WSS3_OPERATOR_PACKAGE_ROOT/.runtime/wss3"

# Audit ROUND-30.4 P0: the in-flight re-arm transaction. `WSS3_REARM_EV_DIR`
# is the EXACT attempt directory this command reserved — it is what the
# successor verification inspects and what a rollback removes, so no
# caller has to re-derive it from a directory listing.
WSS3_REARM_EV_DIR=""
WSS3_REARM_TUNNEL_CREATED=0
# Audit ROUND-30.7: the same transaction discipline now covers PREFLIGHT
# as well as resume. `preflight` used to arm `.runtime` before the two
# operator confirmations and before `PROFILE.json`, with no rollback at
# all: an operator answering "no" left a fully armed profile with no
# attempt behind it. These fields record what the in-flight transaction
# created so ONE unwind can remove exactly that and nothing else.
WSS3_TXN_PID=""
WSS3_TXN_SVC_CREATED=0
WSS3_TXN_ARM_CREATED=0
# Audit ROUND-30.8 P0: temporary files are COMPOSED into the one trap
# instead of replacing it. R30.7 installed
# `trap "rm -f '<tmp>'" EXIT INT TERM` mid-preflight, which silently
# replaced both the transaction unwind and the pin cleanup for the rest
# of the run.
WSS3_TXN_TMPFILES=""
# ══════════════════════════════════════════════════════════════════
# Audit ROUND-30.9 — the OUTER transaction journal
# ══════════════════════════════════════════════════════════════════
#
# R30.8 restored a record only inside `wss3_write_arm` and discarded the
# snapshot as soon as the pair published, so a failure later in the
# preflight — the baseline checkpoint — had nothing to restore from and
# left the profile carrying the failed run's arm.
#
# The journal is taken ONCE, before the first mutation, over every
# runtime record the transaction may touch, and it is held until the
# baseline commits. Every failure path — writer, verification, EXIT, INT
# and TERM — unwinds through it.
#
# What the journal holds is raw record bytes, so it lives in a 0700
# directory that the shared trap sweeps: a rollback must not leave
# snapshots of a service name lying in the temp directory (§5).
# Audit ROUND-30.10: the journal state itself lives in
# lib/wss3-runtime-state.sh, the single authority. R30.9 kept a second
# copy of it here and `wss3_write_arm` kept a third, private one; there
# is exactly one now.

# _wss3_journal_begin <root> <profile-id>
#   Refuses a non-canonical pre-state, then captures every record.
#   Nothing has been mutated when this fails.
# Audit ROUND-30.10: these are thin wrappers over the ONE journal
# authority in lib/wss3-runtime-state.sh. R30.9 had a second journal
# implementation here and a third private snapshot inside
# `wss3_write_arm`; there is now exactly one, and the signal handlers,
# the preflight and the standalone library callers all drive it.
_wss3_journal_begin() {
    wss3_txn_begin "$1" "$2"
}
_wss3_journal_created() {
    wss3_txn_created "$1"
}
_wss3_journal_discard() {
    wss3_txn_discard
}
_wss3_journal_rollback() {
    wss3_txn_rollback
}

_wss3_txn_track_tmp() {
    WSS3_TXN_TMPFILES="$WSS3_TXN_TMPFILES
$1"
}
_wss3_txn_sweep_tmp() {
    local f
    printf '%s\n' "$WSS3_TXN_TMPFILES" | while IFS= read -r f; do
        [ -n "$f" ] || continue
        rm -f -- "$f" 2>/dev/null || true
    done
    WSS3_TXN_TMPFILES=""
}

# The mandatory baseline evidence set. `preflight` may not commit its
# transaction until every one of these exists, is non-empty and parses.
WSS3_REQUIRED_BASELINE_EVIDENCE="PROFILE.json EGRESS_FINGERPRINT.json PROFILE_STATE.json network_profile.json signed_prekey_readiness.json"
WSS3_INHERITED_BASELINE_EVIDENCE="EGRESS_FINGERPRINT.json PROFILE_STATE.json network_profile.json signed_prekey_readiness.json"

# ─── usage banner ───

wss3_usage() {
    cat <<EOF
usage: $(basename "$0") <verb> [flags]

verbs:
  preflight  --operator YOTA|TELE2 --expected-operator-numeric NNNNN
             --phone-vpn off|on --host-vpn off|on
             --host-vpn-service "<macOS Network Configuration service>"
             [--host-vpn-tunnel <utunN>]  # declare a Network Extension /
                                          # TUN when scutil reports it
                                          # Disconnected (audit ROUND-30)
  smoke      [--host-vpn-service "<name>"] [--host-vpn-tunnel <utunN>]
  full       [--host-vpn-service "<name>"] [--host-vpn-tunnel <utunN>]
  resume     [--retry --profile-id <id> --reason <enum>]
             [--profile-id <id>]          # bare mode: resume only this
                                          # profile (audit ROUND-30.3)
             [--host-vpn-tunnel <utunN>]  # migrate a pre-ROUND-30 arm
                                          # (audit ROUND-30.2); REQUIRES
                                          # --profile-id (ROUND-30.3)
  compare
  cleanup    [--all]

Runtime state store lives at:
  $WSS3_RUNTIME_ROOT
Evidence lives at:
  $WSS3_EVIDENCE_ROOT

See docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md for
the full contract. Nothing touches real devices without operator
green-light past the synthetic Mac dry-run.
EOF
}

# ─── verb: preflight ───

wss3_verb_preflight() {
    wss3_parse_args "$@" || return 1
    if [ -z "${WSS3_PHONE_VPN}" ] || [ -z "${WSS3_HOST_VPN}" ]; then
        echo "preflight: --phone-vpn and --host-vpn required" >&2
        return 1
    fi

    # Refuse re-preflight into a completed profile per §3.1 R10 — the
    # only way to re-run COMPLETE is `resume --retry`.
    local latest
    latest=$(wss3_latest_evidence_dir_for_profile "$WSS3_EVIDENCE_ROOT" "$WSS3_PROFILE_ID")
    if [ -n "$latest" ] && [ -f "$latest/matrix_completion.json" ]; then
        echo "preflight: profile-already-complete at $latest — use \`resume --retry --reason ...\`" >&2
        return 3
    fi

    # Refuse stale runtime state: if a runtime dir exists for this
    # profile but there's no matching evidence dir on disk (or the
    # evidence dir's arm_token disagrees), fail-closed
    # runtime_state_stale per §3.6 invariant 5.
    if [ -d "$WSS3_RUNTIME_ROOT/$WSS3_PROFILE_ID" ]; then
        if [ -z "$latest" ]; then
            echo "$WSS3_ERR_STALE_RUNTIME" >&2
            echo "preflight: runtime state for $WSS3_PROFILE_ID has no matching evidence dir — run \`cleanup\` first" >&2
            return "$WSS3_EXIT_PREARM_CLI_FAILURE"
        fi
    fi

    # Mint a fresh arm_token + record confirmation prompt for the
    # operator; typed confirmation is enforced live in production but
    # bypassed under WSS3_DRY_RUN=1 (no stdin in test harness).
    local arm_token
    arm_token=$(wss3_mint_arm_token)
    if ! wss3_validate_arm_token "$arm_token"; then
        echo "preflight: minted arm_token is not a valid UUIDv4" >&2
        return 1
    fi

    # Audit ROUND-26 P0: resolve devices BEFORE typed confirmation.
    # Invalid topology (no phone, no emu, multiple, unauthorized,
    # role-mismatched override, ...) MUST NOT ask the operator to
    # type the arm string first. `wss3_devices_resolve` speaks only
    # through the wss3_adb seam and never falls back to mock serials
    # in production.
    local phone_serial="" emu_serial=""
    if ! _wss3_resolve_serials_to phone_serial emu_serial; then
        echo "preflight: device topology check failed pre-arm" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    WSS3_TRAP_PHONE_SERIAL="$phone_serial"
    WSS3_TRAP_EMU_SERIAL="$emu_serial"

    # Confirmation. Operator types the deterministic string;
    # test / dry-run mode reads it from WSS3_CONFIRM_INPUT env var.
    local typed
    if [ -n "${WSS3_CONFIRM_INPUT:-}" ]; then
        typed="$WSS3_CONFIRM_INPUT"
    elif [ -n "${WSS3_DRY_RUN:-}" ]; then
        typed="$WSS3_CONFIRM_STRING"
    else
        echo "preflight: type EXACTLY '$WSS3_CONFIRM_STRING' to arm this profile:"
        # shellcheck disable=SC2162
        read -r typed
    fi
    if [ "$typed" != "$WSS3_CONFIRM_STRING" ]; then
        echo "preflight: confirmation string mismatch (expected '$WSS3_CONFIRM_STRING')" >&2
        return 1
    fi

    # Host-VPN state check per §7 R7.
    local relay="${WSS3_RELAY_HOST:-relay.phntm.pro}"
    case "$WSS3_HOST_VPN" in
        on)
            # Audit ROUND-30: the optional declared Network Extension /
            # TUN interface is a FALLBACK only — `check_on` tries the
            # legacy scutil path first and consults this exclusively
            # when that path cannot attest.
            if ! wss3_hostvpn_check_on "$WSS3_HOST_VPN_SERVICE" "$relay" \
                    "${WSS3_HOST_VPN_TUNNEL:-}"; then
                echo "preflight: host_vpn=on failed pre-arm check" >&2
                return "$WSS3_EXIT_PREARM_CLI_FAILURE"
            fi
            ;;
        off)
            if ! wss3_hostvpn_check_off "$WSS3_HOST_VPN_SERVICE" "$relay"; then
                echo "preflight: host_vpn=off failed pre-arm check (may be another Connected VPN)" >&2
                return "$WSS3_EXIT_PREARM_CLI_FAILURE"
            fi
            ;;
    esac

    # Device topology + trap serials were resolved at pre-arm
    # (audit ROUND-26 P0); the resolver already verified both are
    # in `adb devices` state=device and role-correct via
    # `getprop ro.kernel.qemu`.

    # APK SHA binding — must match between phone + emu.
    local apk_sha_phone apk_sha_emu
    apk_sha_phone=$(wss3_adb_apk_sha256 "$phone_serial" phantom.android)
    apk_sha_emu=$(wss3_adb_apk_sha256 "$emu_serial" phantom.android)
    if [ -z "$apk_sha_phone" ] || [ "$apk_sha_phone" != "$apk_sha_emu" ]; then
        echo "preflight: APK SHA mismatch phone=$apk_sha_phone emu=$apk_sha_emu" >&2
        return 1
    fi
    # Override the default WSS3_APK_SHA256 with the resolved live value
    # so the PROFILE.json record is truthful.
    WSS3_APK_SHA256="$apk_sha_phone"

    # Paired-conversation count (§7 R2).
    wss3_adb_logcat_clear "$phone_serial"
    wss3_adb_logcat_clear "$emu_serial"
    wss3_adb_invoke_diag_cmd "$phone_serial" paired_count_report >/dev/null 2>&1 || true
    wss3_adb_invoke_diag_cmd "$emu_serial"   paired_count_report >/dev/null 2>&1 || true
    [ "${WSS3_DRY_RUN:-}" = "1" ] || sleep 3
    local pc_tmp_p pc_tmp_e
    pc_tmp_p=$(mktemp -t wss3-pc-p.XXXXXX)
    pc_tmp_e=$(mktemp -t wss3-pc-e.XXXXXX)
    wss3_adb_logcat_snapshot "$phone_serial" "$pc_tmp_p"
    wss3_adb_logcat_snapshot "$emu_serial"   "$pc_tmp_e"
    for lf in "$pc_tmp_p" "$pc_tmp_e"; do
        local count
        count=$(grep -oE 'paired_count_report count=[0-9]+' "$lf" 2>/dev/null | tail -1 | cut -d= -f2)
        if [ -z "$count" ] || [ "$count" -ne 1 ]; then
            rm -f "$pc_tmp_p" "$pc_tmp_e"
            echo "preflight: paired conversation count on $(basename "$lf") not 1 (got '$count')" >&2
            return 1
        fi
    done
    rm -f "$pc_tmp_p" "$pc_tmp_e"

    # Signed-prekey readiness with bounded 12x5s polling in production.
    # In dry-run the mock responds immediately, so ONE attempt suffices.
    local sp_ok=0 sp_attempts=12 sp_step=5
    if [ "${WSS3_DRY_RUN:-}" = "1" ]; then
        sp_attempts=1
        sp_step=0
    fi
    local _sp
    for ((_sp=0; _sp<sp_attempts; _sp++)); do
        wss3_adb_logcat_clear "$phone_serial"
        wss3_adb_logcat_clear "$emu_serial"
        wss3_adb_invoke_diag_cmd "$phone_serial" signed_prekey_readiness >/dev/null 2>&1 || true
        wss3_adb_invoke_diag_cmd "$emu_serial"   signed_prekey_readiness >/dev/null 2>&1 || true
        [ "$sp_step" -gt 0 ] && sleep "$sp_step"
        local sp_p sp_e
        sp_p=$(mktemp -t wss3-sp-p.XXXXXX)
        sp_e=$(mktemp -t wss3-sp-e.XXXXXX)
        wss3_adb_logcat_snapshot "$phone_serial" "$sp_p"
        wss3_adb_logcat_snapshot "$emu_serial"   "$sp_e"
        if grep -q "signed_prekey_readiness published=true" "$sp_p" && \
           grep -q "signed_prekey_readiness published=true" "$sp_e"; then
            rm -f "$sp_p" "$sp_e"
            sp_ok=1
            break
        fi
        rm -f "$sp_p" "$sp_e"
    done
    if [ "$sp_ok" != "1" ]; then
        echo "preflight: signed_prekey_readiness never reached published=true within 60s" >&2
        return 1
    fi

    # Set emitter_id on each device so their WSS_DIAG lines carry the
    # correct provenance.
    wss3_adb_invoke_diag_cmd "$phone_serial" set_emitter_id --es emitter_id phone   >/dev/null 2>&1 || true
    wss3_adb_invoke_diag_cmd "$emu_serial"   set_emitter_id --es emitter_id emulator >/dev/null 2>&1 || true

    # Audit ROUND-24 CONTINUATION: preflight snapshot ownership.
    # The phone STATE-ONLY report is one call (no HMAC). The emu
    # egress-capable report is called EXACTLY ONCE inside
    # `wss3_live_checkpoint` (below, mode=baseline) with ck_hex —
    # the same key used to write EGRESS_FINGERPRINT.json. That call
    # emits the emu_json to a temp path (WSS3_EMIT_EMU_JSON_TO)
    # so this preflight reads back the SAME snapshot for
    # network_profile.json + PROFILE_STATE.json. No second emu
    # egress fetch. No ck_pre.
    local phone_np
    phone_np=$(wss3_adb_invoke_network_profile_state_report "$phone_serial")

    # Snapshot signed_prekey_readiness for BOTH devices.
    local sp_tmp_p sp_tmp_e sp_p sp_e
    sp_tmp_p=$(mktemp -t wss3-sp-final-p.XXXXXX)
    sp_tmp_e=$(mktemp -t wss3-sp-final-e.XXXXXX)
    wss3_adb_logcat_clear "$phone_serial"; wss3_adb_logcat_clear "$emu_serial"
    wss3_adb_invoke_diag_cmd "$phone_serial" signed_prekey_readiness >/dev/null 2>&1 || true
    wss3_adb_invoke_diag_cmd "$emu_serial"   signed_prekey_readiness >/dev/null 2>&1 || true
    wss3_adb_logcat_snapshot "$phone_serial" "$sp_tmp_p"
    wss3_adb_logcat_snapshot "$emu_serial"   "$sp_tmp_e"
    sp_p=$(grep -oE "signed_prekey_readiness.*" "$sp_tmp_p" | tail -1)
    sp_e=$(grep -oE "signed_prekey_readiness.*" "$sp_tmp_e" | tail -1)
    rm -f "$sp_tmp_p" "$sp_tmp_e"

    # Full checkpoint (host+emu HMAC parity + phone reporter).
    local ck_hex
    ck_hex=$(wss3_mint_checkpoint_key)
    export WSS3_CHECKPOINT_KEY_HEX="$ck_hex"
    # NOTE: evidence_dir passed as the freshly-created ev_dir below —
    # we'll invoke the checkpoint AFTER ev_dir is created + PROFILE.json
    # written, so it can materialise EGRESS_FINGERPRINT.json in the
    # proper location.

    # ══ Audit ROUND-30.7: preflight is one transaction ═════════════
    #
    # R30.6 armed `.runtime` HERE, then asked the operator to confirm,
    # then wrote `PROFILE.json` — with no rollback anywhere. An operator
    # answering "no", or an unwritable evidence root, or a SIGINT, left
    # a fully armed profile with no attempt behind it, and the next
    # `preflight` refused it as stale runtime.
    #
    # The order is now: every validation and every confirmation first,
    # then reserve, then stage, then publish, then verify, then commit.
    # Any failure after the first mutation goes through the single
    # unwind, including the EXIT/INT/TERM trap.

    # ── all operator confirmations, BEFORE the first mutation ──────
    local auto_switch other_sim
    if [ -n "${WSS3_MANUAL_AUTO_DATA_SWITCHING_OFF:-}" ]; then
        auto_switch="$WSS3_MANUAL_AUTO_DATA_SWITCHING_OFF"
    elif [ "${WSS3_DRY_RUN:-}" = "1" ]; then
        auto_switch="true"
    else
        echo "preflight: type 'yes' to confirm auto data switching is OFF on the phone:"
        # shellcheck disable=SC2162
        read -r _ans
        auto_switch=$([ "$_ans" = "yes" ] && echo true || echo false)
    fi
    if [ "$auto_switch" != "true" ]; then
        echo "preflight: auto_data_switching_off must be 'true' (operator declined)" >&2
        return 1
    fi
    if [ -n "${WSS3_MANUAL_OTHER_SIM_DATA_DISABLED:-}" ]; then
        other_sim="$WSS3_MANUAL_OTHER_SIM_DATA_DISABLED"
    elif [ "${WSS3_DRY_RUN:-}" = "1" ]; then
        other_sim="true"
    else
        echo "preflight: type 'yes' to confirm mobile data is DISABLED on every OTHER SIM slot:"
        # shellcheck disable=SC2162
        read -r _ans
        other_sim=$([ "$_ans" = "yes" ] && echo true || echo false)
    fi
    if [ "$other_sim" != "true" ]; then
        echo "preflight: other_sim_data_disabled must be 'true' (operator declined)" >&2
        return 1
    fi

    # ── Audit ROUND-30.9: open the outer journal ───────────────────
    # Existence alone was not enough: R30.8 could not put an overwritten
    # record back. The journal captures type, exact bytes and mode for
    # every runtime record and holds them until the baseline commits. A
    # non-canonical pre-state is refused here, before anything changes.
    WSS3_TXN_PID="$WSS3_PROFILE_ID"
    WSS3_TXN_SVC_CREATED=0
    WSS3_TXN_ARM_CREATED=0
    WSS3_REARM_TUNNEL_CREATED=0
    WSS3_REARM_EV_DIR=""
    local _journal_rc=0
    _wss3_journal_begin "$WSS3_RUNTIME_ROOT" "$WSS3_PROFILE_ID" || _journal_rc=$?
    if [ "$_journal_rc" != "0" ]; then
        return "$_journal_rc"
    fi
    local _rt_dir="$WSS3_RUNTIME_ROOT/$WSS3_PROFILE_ID"
    local _svc_before=0 _arm_before=0 _tun_before=0
    if wss3_path_present "$_rt_dir/host-vpn-service"; then _svc_before=1; fi
    if wss3_path_present "$_rt_dir/arm-token"; then _arm_before=1; fi
    if wss3_path_present "$_rt_dir/host-vpn-tunnel"; then _tun_before=1; fi

    # ── reserve the attempt atomically (no `mkdir -p`, strictly newer) ──
    local ev_dir reserve_rc=0
    ev_dir=$(_wss3_reserve_attempt_dir "$WSS3_PROFILE_ID") || reserve_rc=$?
    if [ "$reserve_rc" != "0" ] || [ -z "$ev_dir" ]; then
        echo "preflight: could not reserve an attempt directory" >&2
        return "${reserve_rc:-1}"
    fi
    WSS3_REARM_EV_DIR="$ev_dir"
    # Audit ROUND-30.9: the attempt directory is an object this
    # invocation created, so the journal owns its removal too.
    # Audit ROUND-30.10 rework: it lives under the EVIDENCE root, outside
    # the runtime store, so the transaction is told that container is in
    # scope before it is registered — ownership is declared, never
    # inferred — and a registration that is refused fails the preflight
    # instead of leaving an object nothing will unwind.
    wss3_txn_scope_add "$WSS3_EVIDENCE_ROOT"
    if ! _wss3_journal_created "$ev_dir"; then
        echo "preflight: could not take ownership of the reserved attempt directory" >&2
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    fi
    local utc
    utc=$(basename "$ev_dir"); utc="${utc##*-}"

    # ── stage the runtime records ──────────────────────────────────
    if ! wss3_write_tunnel "$WSS3_RUNTIME_ROOT" "$WSS3_PROFILE_ID" "${WSS3_HOST_VPN_TUNNEL:-}"; then
        if [ "$_tun_before" = "0" ] && wss3_path_present "$_rt_dir/host-vpn-tunnel"; then
            WSS3_REARM_TUNNEL_CREATED=1
        fi
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    fi
    if [ "$_tun_before" = "0" ] && wss3_path_present "$_rt_dir/host-vpn-tunnel"; then
        WSS3_REARM_TUNNEL_CREATED=1
    fi
    local _arm_rc=0
    wss3_write_arm "$WSS3_RUNTIME_ROOT" "$WSS3_PROFILE_ID" "$WSS3_HOST_VPN_SERVICE" "$arm_token" || _arm_rc=$?
    if [ "$_svc_before" = "0" ] && wss3_path_present "$_rt_dir/host-vpn-service"; then
        WSS3_TXN_SVC_CREATED=1
    fi
    if [ "$_arm_before" = "0" ] && wss3_path_present "$_rt_dir/arm-token"; then
        WSS3_TXN_ARM_CREATED=1
    fi
    if [ "$_arm_rc" != "0" ]; then
        echo "preflight: runtime state write failed" >&2
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    fi

    local now_ms
    now_ms=$(now_ms)
    local mono_ms
    mono_ms=$(python3 -c 'import time; print(int(time.monotonic()*1000))')

    # apk_sha256 comes from an operator-supplied env var so the
    # test harness / synthetic dry-run can inject a canonical value.
    local apk_sha="${WSS3_APK_SHA256:-000000000000000000000000000000000000000000000000000000000000dead}"
    local run_id="${WSS3_RUN_ID:-wss3-$utc-$(printf '%s' "$arm_token" | cut -c1-8)}"
    local attempt_id
    attempt_id=$(wss3_mint_arm_token)  # UUIDv4 for attempt too

    local supers="${WSS3_SUPERSEDES_ATTEMPT_ID:-null}"
    local retry_reason_raw="${WSS3_RETRY_REASON:-null}"
    # Manual confirmations (§7 R6 (d)/(e)) were collected ABOVE, before
    # the first mutation, per the ROUND-30.7 transaction discipline.

    # Audit ROUND-30.9 P1: PROFILE.json goes through the SAME atomic
    # publisher as every other mandatory evidence file. It was the one
    # written straight to its final path.
    local _pf_write_rc=0
    python3 - "$ev_dir/PROFILE.json.tmp" <<PY || _pf_write_rc=$?
import json, sys
path = sys.argv[1]
obj = {
  "carrier": "$WSS3_OPERATOR",
  "phone_vpn": "$WSS3_PHONE_VPN",
  "host_vpn": "$WSS3_HOST_VPN",
  "expected_operator_numeric": "$WSS3_EXPECTED_OPERATOR_NUMERIC",
  "apk_sha256": "$apk_sha",
  "run_id": "$run_id",
  "attempt_id": "$attempt_id",
  "supersedes_attempt_id": (None if "$supers" == "null" else "$supers"),
  "retry_reason": (None if "$retry_reason_raw" == "null" else "$retry_reason_raw"),
  "arm_token": "$arm_token",
  "arm_wall_ms": $now_ms,
  "arm_monotonic_ms": $mono_ms,
  "confirm_string": "$WSS3_CONFIRM_STRING",
  "operator_manual_confirmations": {
    "auto_data_switching_off": ("$auto_switch" == "true"),
    "other_sim_data_disabled": ("$other_sim" == "true"),
  },
}
with open(path, "w", encoding="utf-8") as f:
    json.dump(obj, f, sort_keys=True, indent=2)
PY
    # Audit ROUND-30.7: publish-then-verify. An unwritten PROFILE.json
    # is a failed preflight, and the whole transaction unwinds — the
    # reserved attempt directory and the runtime records this command
    # created.
    _wss3_publish_evidence "$ev_dir" PROFILE.json "$_pf_write_rc" || {
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    }
    # The arm is bound: the runtime token must be exactly the one this
    # attempt recorded, read back through the byte-exact reader.
    if ! wss3_verify_arm_matches_profile "$WSS3_RUNTIME_ROOT" "$WSS3_PROFILE_ID" "$arm_token"; then
        echo "preflight: armed runtime does not match the attempt just written" >&2
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    # ── Audit ROUND-30.8 P0: the transaction stays OPEN ────────────
    # R30.7 committed here, before the baseline checkpoint and every
    # mandatory evidence file it produces. A late failure therefore kept
    # an armed profile, an attempt directory and a PROFILE.json with no
    # evidence behind them. The commit now happens after the required
    # set has been written AND verified, at the end of this function.

    # Audit ROUND-24 CONTINUATION: baseline checkpoint owns the
    # ONE snapshot from which EGRESS_FINGERPRINT.json,
    # network_profile.json and PROFILE_STATE.json all derive.
    # The emu_json is emitted to a temp path with the snapshot's
    # wall-clock stamped in so cross-file at_wall_ms matches by
    # construction. The temp is cleaned unconditionally
    # (success / failure / EXIT / INT / TERM).
    local emu_np_tmp
    emu_np_tmp=$(mktemp -t wss3-baseline-emu.XXXXXX)
    # Audit ROUND-30.8 P0: registered with the ONE trap, not a
    # replacement for it.
    _wss3_txn_track_tmp "$emu_np_tmp"
    if ! WSS3_CHECKPOINT_MODE=baseline WSS3_EMIT_EMU_JSON_TO="$emu_np_tmp" \
            wss3_live_checkpoint "$phone_serial" "$emu_serial" \
                "$WSS3_EXPECTED_OPERATOR_NUMERIC" "$WSS3_PHONE_VPN" \
                "$WSS3_HOST_VPN" "$WSS3_HOST_VPN_SERVICE" \
                "$relay" "$ck_hex" "$ev_dir"; then
        echo "preflight: live checkpoint failed" >&2
        rm -f "$emu_np_tmp" 2>/dev/null || true
        unset WSS3_CHECKPOINT_KEY_HEX
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    fi
    # Audit ROUND-30.8 P0: the emulator snapshot is mandatory input for
    # two of the required evidence files. An empty emit is a failed
    # baseline, not an empty section.
    local emu_np=""
    if [ ! -s "$emu_np_tmp" ]; then
        echo "preflight: baseline emulator snapshot was not emitted" >&2
        rm -f "$emu_np_tmp" 2>/dev/null || true
        unset WSS3_CHECKPOINT_KEY_HEX
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    fi
    emu_np=$(cat "$emu_np_tmp")
    rm -f "$emu_np_tmp" 2>/dev/null || true
    unset WSS3_CHECKPOINT_KEY_HEX

    # PROFILE_STATE.json (§5) — observed device state at preflight
    # matching PROFILE.json. Captures the reporter JSON we already have.
    local _ps_rc=0
    WSS3_EV="$ev_dir" WSS3_PJ="$phone_np" WSS3_EJ="$emu_np" \
        python3 - "$ev_dir/PROFILE_STATE.json.tmp" <<'PY' || _ps_rc=$?
import json, os, sys
def _tolerant_load(raw):
    try: return json.loads(raw)
    except Exception: return {"raw": raw[:2048]}
pj = _tolerant_load(os.environ.get("WSS3_PJ", ""))
ej = _tolerant_load(os.environ.get("WSS3_EJ", ""))
obj = {
    "phone_observed": {
        "active_data_sim_operator_numeric": pj.get("active_data_sim_operator_numeric"),
        "active_data_subscription_id": pj.get("active_data_subscription_id"),
        "wifi_enabled": pj.get("wifi_enabled"),
        "mobile_data_enabled": pj.get("mobile_data_enabled"),
        "has_transport_vpn": pj.get("has_transport_vpn"),
        "wifi_read_error": pj.get("wifi_read_error"),
    },
    "emu_observed": {
        "active_network_kind": ej.get("active_network_kind"),
        "has_transport_vpn": ej.get("has_transport_vpn"),
    },
}
with open(sys.argv[1], "w", encoding='utf-8') as f:
    json.dump(obj, f, sort_keys=True, indent=2)
PY
    _wss3_publish_evidence "$ev_dir" PROFILE_STATE.json "$_ps_rc" || {
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    }

    # network_profile.json (§5) — full sanitised reporter output per
    # device. Ships phone + emu sections; egress_fingerprint fields
    # preserved (HMAC hex only per the reporter's own denylist).
    local _np_rc=0
    WSS3_PJ="$phone_np" WSS3_EJ="$emu_np" \
        python3 - "$ev_dir/network_profile.json.tmp" <<'PY' || _np_rc=$?
import json, os, sys
def _tolerant_load(raw):
    try: return json.loads(raw)
    except Exception: return {"raw": raw[:2048]}
obj = {
    "phone": _tolerant_load(os.environ.get("WSS3_PJ", "")),
    "emu":   _tolerant_load(os.environ.get("WSS3_EJ", "")),
}
with open(sys.argv[1], "w", encoding='utf-8') as f:
    json.dump(obj, f, sort_keys=True, indent=2)
PY
    _wss3_publish_evidence "$ev_dir" network_profile.json "$_np_rc" || {
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    }

    # signed_prekey_readiness.json (§5) — both devices' output.
    local _sp_rc=0
    cat > "$ev_dir/signed_prekey_readiness.json.tmp" <<EOF || _sp_rc=$?
{
  "phone_line": $(printf '%s' "${sp_p:-}" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))"),
  "emu_line":   $(printf '%s' "${sp_e:-}" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
}
EOF
    _wss3_publish_evidence "$ev_dir" signed_prekey_readiness.json "$_sp_rc" || {
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    }

    # ── Audit ROUND-30.8 P0: verify the REQUIRED SET, then commit ───
    if ! _wss3_verify_required_evidence "$ev_dir"; then
        echo "preflight: mandatory baseline evidence is incomplete" >&2
        _wss3_resume_rollback_attempt "$WSS3_PROFILE_ID"
        return 1
    fi
    # Committed. Only now may nothing unwind the arm, and only now is
    # the journal — which holds raw record bytes — discarded.
    # Audit ROUND-30.10 rework: the commit goes through the state machine
    # (VERIFIED -> COMMITTED -> IDLE) and its cleanup is checked. A
    # journal that survives the commit still holds the service value in
    # the clear, and a lock that survives it blocks the next run, so
    # neither may be reported as done when it is not.
    wss3_txn_verified
    WSS3_TXN_PID=""
    WSS3_TXN_SVC_CREATED=0
    WSS3_TXN_ARM_CREATED=0
    WSS3_REARM_EV_DIR=""
    WSS3_REARM_TUNNEL_CREATED=0
    if ! wss3_txn_commit; then
        echo "preflight: the arm is on disk but its transaction could not be closed cleanly" >&2
        return 1
    fi

    echo "preflight: armed $WSS3_PROFILE_ID at $ev_dir"
    return 0
}

# _wss3_publish_evidence <evidence-dir> <name> <writer-rc>
#   Audit ROUND-30.8 P0. Every mandatory evidence file is written to
#   `<name>.tmp`, checked, and only then renamed into place. R30.7 wrote
#   these straight to their final names and never looked at the writer's
#   exit status, so a failed write left the file absent or half-written
#   while preflight still printed `armed`.
_wss3_publish_evidence() {
    local ev_dir="$1" name="$2" writer_rc="$3"
    local tmp="$ev_dir/$name.tmp" target="$ev_dir/$name"
    if [ "$writer_rc" != "0" ]; then
        echo "preflight: writer for $name failed (rc=$writer_rc)" >&2
        rm -f -- "$tmp" 2>/dev/null || true
        return 1
    fi
    if [ ! -s "$tmp" ]; then
        echo "preflight: $name was not written" >&2
        rm -f -- "$tmp" 2>/dev/null || true
        return 1
    fi
    if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$tmp" 2>/dev/null; then
        echo "preflight: $name is not valid JSON" >&2
        rm -f -- "$tmp" 2>/dev/null || true
        return 1
    fi
    mv "$tmp" "$target" || {
        echo "preflight: could not publish $name" >&2
        rm -f -- "$tmp" 2>/dev/null || true
        return 1
    }
    [ -s "$target" ] || {
        echo "preflight: $name vanished after publication" >&2
        return 1
    }
    return 0
}

# _wss3_verify_required_evidence <evidence-dir>
#   The commit precondition: every mandatory baseline file exists, is
#   non-empty and parses, and no `.tmp` staging file is left behind.
_wss3_verify_required_evidence() {
    local ev_dir="$1" name rc=0
    for name in $WSS3_REQUIRED_BASELINE_EVIDENCE; do
        if [ ! -s "$ev_dir/$name" ]; then
            echo "preflight: required evidence missing or empty: $name" >&2
            rc=1
            continue
        fi
        if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$ev_dir/$name" 2>/dev/null; then
            echo "preflight: required evidence does not parse: $name" >&2
            rc=1
        fi
    done
    local stray
    stray=$(find "$ev_dir" -maxdepth 1 -name '*.tmp' -print 2>/dev/null | head -1)
    if [ -n "$stray" ]; then
        echo "preflight: staging file left behind: $(basename "$stray")" >&2
        rc=1
    fi
    return "$rc"
}

# _wss3_copy_verified_successor_baseline <predecessor-dir> <successor-dir>
#
# A resumed attempt retains the arm and run established by preflight, so its
# baseline is the predecessor's immutable, already observed baseline.  The old
# resume path copied only PROFILE.json fields and left the successor without
# the four files that every common audit requires.  Copying happens only after
# every source byte is matched to the predecessor's manifest.  Publication is
# atomic per file and the surrounding resume transaction removes the entire
# successor if any step fails.
_wss3_copy_verified_successor_baseline() {
    local src="$1" dst="$2"
    WSS3_BASELINE_SOURCE="$src" WSS3_BASELINE_DEST="$dst" \
    WSS3_BASELINE_NAMES="$WSS3_INHERITED_BASELINE_EVIDENCE" \
        python3 - <<'PY'
import hashlib
import json
import os
import re
import stat
import sys

src = os.environ["WSS3_BASELINE_SOURCE"]
dst = os.environ["WSS3_BASELINE_DEST"]
names = os.environ["WSS3_BASELINE_NAMES"].split()
manifest_path = os.path.join(src, "SHA256SUMS.txt")

def regular_file(path):
    try:
        return stat.S_ISREG(os.lstat(path).st_mode)
    except OSError:
        return False

if not regular_file(manifest_path):
    sys.stderr.write("resume: predecessor SHA256SUMS.txt is missing or not a regular file\n")
    raise SystemExit(1)

manifest = {}
with open(manifest_path, encoding="ascii", errors="strict") as fh:
    for line_no, raw in enumerate(fh, 1):
        line = raw.rstrip("\n")
        match = re.fullmatch(r"([0-9a-f]{64})  \./(.+)", line)
        if not match:
            sys.stderr.write(
                f"resume: malformed predecessor manifest line {line_no}\n")
            raise SystemExit(1)
        digest, name = match.groups()
        parts = name.split("/")
        if (name.startswith("/") or "\\" in name
                or any(part in ("", ".", "..") for part in parts)):
            sys.stderr.write(
                f"resume: unsafe predecessor manifest path at line {line_no}\n")
            raise SystemExit(1)
        if name in manifest:
            sys.stderr.write(f"resume: duplicate predecessor manifest entry: {name}\n")
            raise SystemExit(1)
        manifest[name] = digest

for name in names:
    source = os.path.join(src, name)
    target = os.path.join(dst, name)
    temp = target + ".tmp"
    if name not in manifest or not regular_file(source) or os.path.getsize(source) == 0:
        sys.stderr.write(f"resume: predecessor baseline is incomplete: {name}\n")
        raise SystemExit(1)
    with open(source, "rb") as fh:
        data = fh.read()
    if hashlib.sha256(data).hexdigest() != manifest[name]:
        sys.stderr.write(f"resume: predecessor baseline hash mismatch: {name}\n")
        raise SystemExit(1)
    try:
        parsed = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        sys.stderr.write(f"resume: predecessor baseline is not valid UTF-8 JSON: {name}\n")
        raise SystemExit(1)
    if not isinstance(parsed, dict):
        sys.stderr.write(f"resume: predecessor baseline is not a JSON object: {name}\n")
        raise SystemExit(1)
    if os.path.lexists(target) or os.path.lexists(temp):
        sys.stderr.write(f"resume: successor baseline target already exists: {name}\n")
        raise SystemExit(1)
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
    except BaseException:
        try:
            os.unlink(temp)
        except OSError:
            pass
        raise
    os.replace(temp, target)
PY
}

# ─── verb: smoke ───

wss3_verb_smoke() {
    local cli_service=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --host-vpn-service) cli_service="$2"; shift 2 ;;
            --host-vpn-tunnel) WSS3_HOST_VPN_TUNNEL="$2"; shift 2 ;;
            *) echo "smoke: unknown flag: $1" >&2; return 1 ;;
        esac
    done
    _wss3_arm_verify "$cli_service" || return $?
    # Resolve evidence dir + run_id from PROFILE.json.
    local ev
    ev=$(wss3_latest_evidence_dir_for_profile "$WSS3_EVIDENCE_ROOT" "$WSS3_PROFILE_ID_RESOLVED")
    local run_id
    run_id=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['run_id'])" "$ev/PROFILE.json")
    # Audit ROUND-26 P0: shared resolver for phone + emu — no
    # ${WSS3_PHONE_SERIAL:-mock-phone-serial} fallback in production.
    local phone_serial="" emu_serial=""
    if ! _wss3_resolve_serials_to phone_serial emu_serial; then
        echo "smoke: device topology check failed" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    WSS3_TRAP_PHONE_SERIAL="$phone_serial"
    WSS3_TRAP_EMU_SERIAL="$emu_serial"
    WSS3_TRAP_RUN_ID="$run_id"
    if wss3_live_smoke "$phone_serial" "$emu_serial" "$run_id" "$ev"; then
        echo "smoke: GREEN for $WSS3_PROFILE_ID_RESOLVED"
        return 0
    fi
    echo "smoke: RED for $WSS3_PROFILE_ID_RESOLVED" >&2
    return 1
}

# ─── verb: full ───

wss3_verb_full() {
    local cli_service=""
    local rest_blocked="${WSS3_REST_BLOCKED:-false}"
    while [ $# -gt 0 ]; do
        case "$1" in
            --host-vpn-service) cli_service="$2"; shift 2 ;;
            --host-vpn-tunnel) WSS3_HOST_VPN_TUNNEL="$2"; shift 2 ;;
            --rest-blocked) rest_blocked=true; shift ;;
            *) echo "full: unknown flag: $1" >&2; return 1 ;;
        esac
    done
    _wss3_arm_verify "$cli_service" || return $?
    local ev
    ev=$(wss3_latest_evidence_dir_for_profile "$WSS3_EVIDENCE_ROOT" "$WSS3_PROFILE_ID_RESOLVED")
    # Audit ROUND-9 P0-3: `full` MUST NOT run without a GREEN smoke.
    # A missing smoke_verdict.json OR non-GREEN integrity blocks it —
    # and since ROUND-30.18 split integrity (is the evidence sound?)
    # from outcome (did the directions deliver?), a FAIL direction
    # blocks it too: contract §3.2, "Smoke RED → STOP for this
    # profile; `full` refuses to run."
    local sv="$ev/smoke_verdict.json"
    if [ ! -f "$sv" ]; then
        echo "full: refused — no smoke_verdict.json in $ev. Run \`smoke\` first." >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    # Audit ROUND-19 P0-1: pre-full smoke gate MUST actually parse
    # `smoke.log` and rebuild the p2e + e2p CID chains, not just
    # check that the file exists and that JSON metadata is present.
    # ROUND-18's version accepted an empty `smoke.log` as long as
    # `smoke_verdict.json` was well-formed and bound to
    # PROFILE.json — architect proved a zero-byte log unlocked
    # `wss3_live_full`. Fix: shell out to the verifier module's
    # `load_smoke_verdict`, which validates JSON schema, binds
    # attempt/arm/run to PROFILE.json, AND independently rebuilds
    # both smoke CID chains from `smoke.log`. Any inconsistency
    # (empty log, missing chain event, unknown-cell smoke event,
    # foreign attempt/arm/run) is a pre-arm CLI failure.
    local pkg_dir; pkg_dir="$(cd "$(dirname "$0")" && pwd)"
    local sv_check_rc
    WSS3_PKG_DIR="$pkg_dir" python3 - "$ev" >/dev/null <<'PY' && sv_check_rc=0 || sv_check_rc=$?
import json, os, sys
sys.path.insert(0, os.environ["WSS3_PKG_DIR"])
import verify_evidence_wss3 as V

ev = sys.argv[1]
sv_path = os.path.join(ev, "smoke_verdict.json")
pf_path = os.path.join(ev, "PROFILE.json")

def _die(reason):
    sys.stderr.write("full: refused — %s\n" % reason)
    sys.exit(2)

if not os.path.isfile(pf_path):
    _die("PROFILE.json missing at %s" % pf_path)
try:
    with open(pf_path, encoding="utf-8") as f:
        pf = json.load(f)
except Exception as exc:
    _die("PROFILE.json unreadable: %r" % exc)
if not isinstance(pf, dict):
    _die("PROFILE.json must be a JSON object")
if not os.path.isfile(sv_path):
    _die("smoke_verdict.json missing at %s (run `smoke` first)" % sv_path)

# Single shared smoke validator: closed-schema JSON checks + attempt/
# arm/run binding to PROFILE.json + INDEPENDENT rebuild of both CID
# chains from `smoke.log`. Any issue → refuse `full`.
sv, issues = V.load_smoke_verdict(
    ev, profile_run_id=pf.get("run_id"), profile=pf,
)
if issues:
    for i in issues:
        sys.stderr.write("full: refused — smoke gate: %s\n" % i)
    sys.exit(2)
# Belt-and-braces: reject explicitly non-GREEN even if load returned
# no other issues (paranoid duplicate — the validator already
# reports this as an issue).
if not isinstance(sv, dict) or sv.get("integrity") != "GREEN":
    _die("smoke_verdict.integrity=%r (want GREEN)" % (sv or {}).get("integrity"))
# Audit ROUND-30.18: `integrity` now describes the EVIDENCE, not the
# outcome — a truthful RED smoke carries integrity=GREEN with a FAIL
# direction. Before that split, integrity!=GREEN covered both and this
# gate refused a failed smoke implicitly; now the product outcome must
# be refused by name, or a well-accounted RED smoke would unlock the
# matrix the smoke exists to gate.
if sv.get("p2e") != "OK" or sv.get("e2p") != "OK":
    _die("smoke declares a failed direction (p2e=%r e2p=%r) — a RED "
         "smoke stops the profile; the matrix does not run on top of it"
         % (sv.get("p2e"), sv.get("e2p")))
sys.exit(0)
PY
    if [ "$sv_check_rc" != "0" ]; then
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    local run_id
    run_id=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['run_id'])" "$ev/PROFILE.json")
    # Audit ROUND-26 P0: shared resolver for phone + emu — no
    # ${WSS3_PHONE_SERIAL:-mock-phone-serial} fallback in production.
    local phone_serial="" emu_serial=""
    if ! _wss3_resolve_serials_to phone_serial emu_serial; then
        echo "full: device topology check failed" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    WSS3_TRAP_PHONE_SERIAL="$phone_serial"
    WSS3_TRAP_EMU_SERIAL="$emu_serial"
    WSS3_TRAP_RUN_ID="$run_id"
    if wss3_live_full "$phone_serial" "$emu_serial" "$run_id" "$ev" "$rest_blocked"; then
        echo "full: GREEN for $WSS3_PROFILE_ID_RESOLVED"
        return 0
    fi
    echo "full: RED for $WSS3_PROFILE_ID_RESOLVED" >&2
    return 1
}

# ─── verb: resume ───

wss3_verb_resume() {
    local retry_mode="" profile_id="" reason=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --retry) retry_mode=1; shift ;;
            --profile-id) profile_id="$2"; shift 2 ;;
            --reason) reason="$2"; shift 2 ;;
            # Audit ROUND-30.2 P0-1: the FIELD RED attempt was armed
            # before ROUND-30 and has no `host-vpn-tunnel`. Bare
            # `resume` is the only authorised way back into it, so the
            # migration declaration has to be accepted HERE or the
            # historical lineage simply cannot be continued with a
            # Network Extension VPN.
            --host-vpn-tunnel) WSS3_HOST_VPN_TUNNEL="$2"; shift 2 ;;
            *) echo "resume: unknown flag: $1" >&2; return 1 ;;
        esac
    done
    if [ -n "$retry_mode" ]; then
        if [ -z "$profile_id" ] || [ -z "$reason" ]; then
            echo "resume --retry requires --profile-id + --reason" >&2
            return 1
        fi
        case "$reason" in
            vpn_provider_change|sim_reseat|sdk_environment_recovery|mid_run_operator_interrupt|network_transient_recovery) : ;;
            resume_incomplete)
                echo "resume: --reason resume_incomplete is reserved for the bare-resume path (§3.4)" >&2
                return 1 ;;
            *) echo "resume: --reason '$reason' not in whitelist" >&2; return 1 ;;
        esac
        _wss3_resume_retry "$profile_id" "$reason" || return $?
        return 0
    fi
    # Bare resume: iterate incomplete profiles and re-arm each with
    # supersedes_attempt_id + retry_reason=resume_incomplete
    # (REDLINE-7 blocker 1). The actual re-execution is scoped to the
    # helper below; the full ADB-driven cell replay lands with the
    # smoke/full ADB extension in a later batch.
    #
    # Audit ROUND-30.3 P0-2: `--profile-id` is no longer parsed and
    # ignored here. It narrows the bare path to exactly one profile,
    # and it is REQUIRED whenever a tunnel migration is declared.
    _wss3_resume_bare "$profile_id" || return $?
    return 0
}

# ─── verb: compare ───

wss3_verb_compare() {
    # Extracts a per-profile roll-up from every valid matrix_completion.json
    # under evidence/. The full 8-row comparison lives in
    # compare-vpn-matrix.py (shipped since Impl 5/6). Audit ROUND-11
    # P0-#2: use `-f` (file exists) not `-x` (executable) — on Windows
    # Git Bash, chmod +x may not stick, and we invoke via python3
    # anyway so the exec bit is irrelevant.
    if [ -f "$WSS3_ROOT/compare-vpn-matrix.py" ]; then
        python3 "$WSS3_ROOT/compare-vpn-matrix.py" --evidence-root "$WSS3_EVIDENCE_ROOT" || return $?
        return 0
    fi
    echo "compare: compare-vpn-matrix.py not found at $WSS3_ROOT/" >&2
    return 1
}

# ─── verb: cleanup ───

wss3_verb_cleanup() {
    local all_mode=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --all) all_mode="all"; shift ;;
            *) echo "cleanup: unknown flag: $1" >&2; return 1 ;;
        esac
    done
    wss3_cleanup_run "$WSS3_RUNTIME_ROOT" "$WSS3_EVIDENCE_ROOT" "$all_mode"
    return 0
}

# ─── internal helpers ───

# _wss3_arm_verify <cli-service-or-empty>
#   Resolves the profile-id from the most recent evidence-dir
#   (single-profile mode) OR looks it up by env WSS3_PROFILE_ID_HINT.
#   Then validates .runtime/wss3/<id>/{host-vpn-service, arm-token}
#   AGAINST both the CLI-passed service AND PROFILE.json.arm_token.
#
#   Sets:
#     WSS3_PROFILE_ID_RESOLVED
_wss3_arm_verify() {
    local cli_service="$1"
    local pid="${WSS3_PROFILE_ID_HINT:-}"
    if [ -z "$pid" ]; then
        # Pick the newest evidence dir that has no matrix_completion.
        pid=$(find "$WSS3_EVIDENCE_ROOT" -mindepth 1 -maxdepth 1 -type d 2>/dev/null \
            | LC_ALL=C sort | while read -r d; do
                if [ ! -f "$d/matrix_completion.json" ]; then
                    basename "$d" | sed -E 's/-[0-9]{8}T[0-9]{6}Z$//'
                fi
              done | tail -1)
        if [ -z "$pid" ]; then
            echo "arm-verify: no in-progress profile found under $WSS3_EVIDENCE_ROOT" >&2
            return "$WSS3_EXIT_PREARM_CLI_FAILURE"
        fi
    fi
    WSS3_PROFILE_ID_RESOLVED="$pid"

    wss3_verify_service_matches_cli "$WSS3_RUNTIME_ROOT" "$pid" "$cli_service" || return $?

    # ── Audit ROUND-30.1 P0-1: consume the persisted tunnel ────────
    # R30 wrote `host-vpn-tunnel` at preflight but nothing ever read
    # it, so a separate `smoke`/`full` process lost the attested
    # interface and the operator had to retype a denylisted string on
    # every command. Load it here — the one place every post-arm verb
    # already validates the arm — and bind it to this profile.
    local stored_tunnel="" tunnel_rc=0
    stored_tunnel=$(wss3_read_tunnel "$WSS3_RUNTIME_ROOT" "$pid") || tunnel_rc=$?
    if [ "$tunnel_rc" != "0" ]; then
        return "$tunnel_rc"
    fi
    local cli_tunnel="${WSS3_HOST_VPN_TUNNEL:-}"
    if [ -n "$cli_tunnel" ]; then
        if [ -z "$stored_tunnel" ]; then
            echo "arm-verify: --host-vpn-tunnel supplied but this profile was armed without one" >&2
            return "$WSS3_EXIT_PREARM_CLI_FAILURE"
        fi
        if [ "$cli_tunnel" != "$stored_tunnel" ]; then
            # Exact equality, same discipline as the service name.
            echo "arm-verify: --host-vpn-tunnel disagrees with the armed declaration" >&2
            return "$WSS3_EXIT_PREARM_CLI_FAILURE"
        fi
    fi
    WSS3_HOST_VPN_TUNNEL="$stored_tunnel"
    export WSS3_HOST_VPN_TUNNEL

    # Cross-check runtime arm_token vs PROFILE.json.arm_token.
    local latest
    latest=$(wss3_latest_evidence_dir_for_profile "$WSS3_EVIDENCE_ROOT" "$pid")
    if [ -z "$latest" ] || [ ! -f "$latest/PROFILE.json" ]; then
        echo "$WSS3_ERR_STALE_RUNTIME" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    local profile_token
    profile_token=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['arm_token'])" "$latest/PROFILE.json")
    wss3_verify_arm_matches_profile "$WSS3_RUNTIME_ROOT" "$pid" "$profile_token" || return $?
    return 0
}

_wss3_resume_bare() {
    # For each in-progress profile: re-arm with retry_reason=resume_incomplete
    # + supersedes_attempt_id linked to the previous attempt.
    local only_pid="${1:-}"
    local declared="${WSS3_HOST_VPN_TUNNEL:-}"

    # ── Audit ROUND-30.3 P0-2: migration is single-profile ──────────
    # R30.2 looped over EVERY incomplete profile and applied one global
    # `--host-vpn-tunnel` declaration to each, so a single operator flag
    # could mutate several runtime arms and leave partial state behind
    # when a later profile failed. A declaration now names exactly one
    # profile, and that profile is validated before the first write.
    if [ -n "$declared" ] && [ -z "$only_pid" ]; then
        echo "resume: --host-vpn-tunnel requires exactly one explicit --profile-id" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi

    local pid armed=0 matched=0
    # Audit ROUND-30.10 closure: refuse a store holding anything this
    # tooling did not create, rather than walking past it.
    if ! wss3_assert_store_recognised "$WSS3_RUNTIME_ROOT"; then
        echo "resume: refusing to run over a store with unrecognised content" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    while IFS= read -r pid; do
        [ -n "$pid" ] || continue
        if [ -n "$only_pid" ] && [ "$pid" != "$only_pid" ]; then
            continue
        fi
        matched=$((matched + 1))
        local latest
        latest=$(wss3_latest_evidence_dir_for_profile "$WSS3_EVIDENCE_ROOT" "$pid")
        if [ -z "$latest" ]; then
            # An explicitly named target that has no evidence at all is
            # an operator error, not a profile to skip silently.
            if [ -n "$only_pid" ]; then
                echo "resume: no evidence dir for profile-id $pid" >&2
                return "$WSS3_EXIT_PREARM_CLI_FAILURE"
            fi
            continue
        fi
        if [ -f "$latest/matrix_completion.json" ]; then
            # already complete/aborted — needs --retry, not bare resume
            if [ -n "$only_pid" ]; then
                echo "resume: $pid is already complete — use \`resume --retry --reason ...\`" >&2
                return 3
            fi
            continue
        fi
        # ── Audit ROUND-30.3 P0-2: prove the target BEFORE any write ──
        _wss3_resume_precheck "$pid" "$latest" || return $?
        # Read previous attempt_id from PROFILE.json.
        local prev_att prev_rc=0
        prev_att=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['attempt_id'])" "$latest/PROFILE.json") || prev_rc=$?
        if [ "$prev_rc" != "0" ] || [ -z "$prev_att" ]; then
            echo "$WSS3_ERR_STALE_RUNTIME" >&2
            echo "resume: $pid has an unreadable attempt_id at $latest" >&2
            return "$WSS3_EXIT_PREARM_CLI_FAILURE"
        fi
        local rearm_rc=0
        WSS3_SUPERSEDES_ATTEMPT_ID="$prev_att" WSS3_RETRY_REASON="resume_incomplete" \
            _wss3_reprearm_from_profile "$latest" || rearm_rc=$?
        # ── Audit ROUND-30.3 P0-1: propagate EVERY unexpected non-zero ─
        # R30.2 forwarded only the exact pre-arm code 4 and `continue`d
        # past everything else, so a failed atomic tunnel write (rc=1)
        # created no successor, preserved the old evidence and still
        # returned RESUME_RC=0 — fail-open on the command that carries
        # the FIELD RED lineage. Ineligibility is decided ABOVE this
        # call; anything the re-arm itself reports is a failure.
        if [ "$rearm_rc" != "0" ]; then
            echo "resume: re-arm failed for $pid (rc=$rearm_rc)" >&2
            return "$rearm_rc"
        fi
        # ── Audit ROUND-30.3 P0-1: no rc=0 without a proven successor ──
        # Audit ROUND-30.4 P0: verify the EXACT directory the re-arm
        # created, and roll the whole transaction back when it fails.
        # R30.3 re-derived the successor with `find | sort | tail -1` and
        # returned here without any rollback, so a failed verification
        # left the migrated tunnel and a half-built attempt on disk.
        local verify_rc=0
        _wss3_resume_verify_successor "$pid" "$WSS3_REARM_EV_DIR" "$latest" "$prev_att" || verify_rc=$?
        if [ "$verify_rc" != "0" ]; then
            _wss3_resume_rollback_attempt "$pid"
            return "$verify_rc"
        fi
        # ── the commit point for `resume` ─────────────────────────
        # Audit ROUND-30.10 rework: everything mandatory has now
        # passed — the migration, the successor attempt and its
        # lineage. Only here does the transaction commit, and its
        # cleanup is verified: a journal that outlives the commit
        # still holds record bytes, and a lock that outlives it
        # blocks the next run.
        wss3_txn_verified
        WSS3_REARM_EV_DIR=""
        WSS3_REARM_TUNNEL_CREATED=0
        if ! wss3_txn_commit; then
            echo "resume: $pid is re-armed but its transaction could not be closed cleanly" >&2
            return 1
        fi
        armed=$((armed + 1))
    done < <(wss3_list_profile_ids "$WSS3_RUNTIME_ROOT")

    if [ -n "$only_pid" ] && [ "$matched" -eq 0 ]; then
        echo "resume: no runtime arm for profile-id $only_pid" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if [ "$armed" -eq 0 ]; then
        # §7 R17: bare resume with nothing incomplete to continue is
        # refused without prompting. Reporting success here would be a
        # success claim with no successor attempt behind it.
        echo "resume: no incomplete profile to resume" >&2
        return 3
    fi
    return 0
}

# _wss3_resume_precheck <profile-id> <latest-evidence-dir>
#   Audit ROUND-30.3 P0-2. Runs BEFORE the first byte is written by the
#   re-arm: the profile must exist in `.runtime` at the contracted
#   modes, its latest attempt must be incomplete (checked by the
#   caller), and the runtime arm MUST be bound to that attempt. A
#   migration that writes first and validates later can leave a tunnel
#   declaration attached to an arm it was never entitled to touch.
_wss3_resume_precheck() {
    local pid="$1" latest="$2"
    if [ ! -f "$latest/PROFILE.json" ]; then
        echo "$WSS3_ERR_STALE_RUNTIME" >&2
        echo "resume: $pid has no PROFILE.json at $latest" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    # Service + arm-token present, 0600 files inside a 0700 tree.
    wss3_read_service "$WSS3_RUNTIME_ROOT" "$pid" >/dev/null || return $?
    # The runtime arm must belong to THIS attempt (§3.6 invariant 6).
    local profile_token token_rc=0
    profile_token=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['arm_token'])" "$latest/PROFILE.json") || token_rc=$?
    if [ "$token_rc" != "0" ] || [ -z "$profile_token" ]; then
        echo "$WSS3_ERR_STALE_RUNTIME" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    wss3_verify_arm_matches_profile "$WSS3_RUNTIME_ROOT" "$pid" "$profile_token" || return $?
    # ── Audit ROUND-30.5 P0: validate the OPTIONAL tunnel path here ──
    # An existing declaration must be a plain, well-formed 0600 file
    # before anything is reserved or migrated. A directory or symlink at
    # that path used to read as "nothing declared", so `resume` reserved
    # a successor and reported success while no declaration was ever
    # written. Absence still passes silently — the tunnel is optional.
    wss3_read_tunnel "$WSS3_RUNTIME_ROOT" "$pid" >/dev/null || return $?
    # The runtime directory name must be the profile the evidence
    # describes; otherwise the migration would write into a different
    # arm than the one being re-armed.
    local derived derived_rc=0
    derived=$(_wss3_profile_id_from_profile_json "$latest/PROFILE.json") || derived_rc=$?
    if [ "$derived_rc" != "0" ] || [ "$derived" != "$pid" ]; then
        echo "$WSS3_ERR_STALE_RUNTIME" >&2
        echo "resume: runtime arm $pid disagrees with the profile recorded at $latest" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    return 0
}

# _wss3_profile_id_from_profile_json <PROFILE.json>
#   The profile-id the recorded carrier/phone/host flags imply.
_wss3_profile_id_from_profile_json() {
    local pj="$1" pdata carrier phone host operator_lower
    pdata=$(python3 -c "
import json, sys
o = json.load(open(sys.argv[1]))
print(o['carrier'], o['phone_vpn'], o['host_vpn'])
" "$pj") || return 1
    carrier=$(printf '%s' "$pdata" | awk '{print $1}')
    phone=$(printf '%s' "$pdata" | awk '{print $2}')
    host=$(printf '%s' "$pdata" | awk '{print $3}')
    [ -n "$carrier" ] && [ -n "$phone" ] && [ -n "$host" ] || return 1
    operator_lower=$(printf '%s' "$carrier" | tr '[:upper:]' '[:lower:]')
    wss3_profile_id_from_flags "$operator_lower" "$phone" "$host"
}

# _wss3_resume_verify_successor <profile-id> <created-dir> <prev-dir> <prev-attempt-id>
#   Audit ROUND-30.3 P0-1. A re-arm that returns 0 must have produced a
#   NEW attempt directory whose PROFILE.json actually carries the
#   lineage: `supersedes_attempt_id` = the superseded attempt,
#   `retry_reason` = resume_incomplete, a fresh `attempt_id`, and the
#   RETAINED runtime `arm_token` (§3.4 arm_token semantics on retry).
#   Anything less and `resume` must not report success.
#
#   Audit ROUND-30.4 P0: the directory to verify is the EXACT path the
#   re-arm created, handed over in `$WSS3_REARM_EV_DIR`. R30.3 asked
#   `wss3_latest_evidence_dir_for_profile` instead, which is
#   `find | sort | tail -1`: a future-dated attempt directory outranks
#   every real successor forever, so the check inspected the wrong
#   attempt and failed a re-arm that had in fact succeeded.
_wss3_resume_verify_successor() {
    # Audit ROUND-30.10 rework: the expected retry_reason is a
    # PARAMETER. It was hard-coded to bare resume's value, so applying
    # this verification to `resume --retry` — which has its own
    # operator-supplied reason — rejected a successor that was
    # perfectly well formed. The default keeps every existing caller
    # behaving exactly as before.
    local pid="$1" newest="$2" prev_dir="$3" prev_att="$4"
    local want_reason="${5:-resume_incomplete}"
    if [ -z "$newest" ] || [ "$newest" = "$prev_dir" ] || [ ! -f "$newest/PROFILE.json" ]; then
        echo "resume: no successor attempt was created for $pid" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    local runtime_token
    runtime_token=$(wss3_read_arm_token "$WSS3_RUNTIME_ROOT" "$pid") || return $?
    local check_rc=0
    python3 - "$newest/PROFILE.json" "$prev_att" "$runtime_token" "$want_reason" <<'PY' || check_rc=$?
import json, sys
o = json.load(open(sys.argv[1]))
prev, token, want_reason = sys.argv[2], sys.argv[3], sys.argv[4]
problems = []
if o.get("supersedes_attempt_id") != prev:
    problems.append("supersedes_attempt_id")
if o.get("retry_reason") != want_reason:
    problems.append("retry_reason")
if o.get("arm_token") != token:
    problems.append("arm_token")
if not o.get("attempt_id") or o.get("attempt_id") == prev:
    problems.append("attempt_id")
if problems:
    sys.stderr.write("successor lineage broken: " + ",".join(problems) + "\n")
    sys.exit(1)
PY
    if [ "$check_rc" != "0" ]; then
        echo "resume: successor attempt for $pid failed lineage verification" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! _wss3_verify_required_evidence "$newest"; then
        echo "resume: successor attempt for $pid does not own a complete baseline" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    return 0
}

_wss3_resume_retry() {
    local pid="$1" reason="$2"
    # Find the latest COMPLETED / ABORTED evidence dir for this
    # profile (i.e. one that has matrix_completion.json). That is the
    # attempt we're superseding. If the most recent evidence dir is
    # in-progress, use it too — bare `resume` covers the incomplete
    # case, but `resume --retry` also links to it (any prior attempt
    # is a valid parent).
    local latest
    latest=$(wss3_latest_evidence_dir_for_profile "$WSS3_EVIDENCE_ROOT" "$pid")
    if [ -z "$latest" ]; then
        echo "resume --retry: no evidence dir for profile-id $pid" >&2
        return 1
    fi
    local prev_att
    prev_att=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['attempt_id'])" "$latest/PROFILE.json")
    # Compute a UTC newer than the existing latest so re-armed dir
    # sorts strictly after; falls back to now if the disk clock is
    # ahead.
    # ── Audit ROUND-30.10 rework: `--retry` commits too ────────────
    # `_wss3_reprearm_from_profile` deliberately leaves its transaction
    # OPEN so the caller can verify the successor before anything is
    # committed. Bare `resume` does that; this path did not, so the
    # transaction stayed open, the EXIT trap treated it as an
    # unfinished run and rolled back the attempt that had just
    # succeeded. Commit responsibility is explicit at BOTH callers, and
    # `--retry` now gets the successor verification bare resume has
    # always had.
    local retry_rc=0
    WSS3_SUPERSEDES_ATTEMPT_ID="$prev_att" WSS3_RETRY_REASON="$reason" \
        _wss3_reprearm_from_profile "$latest" || retry_rc=$?
    if [ "$retry_rc" != "0" ]; then
        _wss3_resume_rollback_attempt "$pid"
        return "$retry_rc"
    fi
    local retry_verify_rc=0
    _wss3_resume_verify_successor "$pid" "$WSS3_REARM_EV_DIR" "$latest" "$prev_att" \
        "$reason" || retry_verify_rc=$?
    if [ "$retry_verify_rc" != "0" ]; then
        _wss3_resume_rollback_attempt "$pid"
        return "$retry_verify_rc"
    fi
    wss3_txn_verified
    WSS3_REARM_EV_DIR=""
    WSS3_REARM_TUNNEL_CREATED=0
    if ! wss3_txn_commit; then
        echo "resume --retry: $pid is re-armed but its transaction could not be closed cleanly" >&2
        return 1
    fi
    return 0
}

# _wss3_migrate_tunnel_for <profile-id> <host_vpn>
#   Audit ROUND-30.2 P0-1. Rules, all fail-closed:
#     - a declaration is only meaningful for a host_vpn=on profile;
#       declaring one for an OFF profile is an operator error, not a
#       silent no-op;
#     - if a tunnel is already persisted it must match exactly, the
#       same discipline `_wss3_arm_verify` applies to the service;
#     - with no declaration the profile keeps whatever it had, so
#       pre-R30 arms stay untouched until the operator migrates them.
_wss3_migrate_tunnel_for() {
    local pid="$1" host_vpn="$2"
    local declared="${WSS3_HOST_VPN_TUNNEL:-}"
    [ -n "$declared" ] || return 0
    if [ "$host_vpn" != "on" ]; then
        echo "resume: --host-vpn-tunnel is only meaningful for a host_vpn=on profile ($pid is '$host_vpn')" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    local existing="" rc=0
    existing=$(wss3_read_tunnel "$WSS3_RUNTIME_ROOT" "$pid") || rc=$?
    if [ "$rc" != "0" ]; then
        return "$rc"
    fi
    if [ -n "$existing" ]; then
        if [ "$existing" != "$declared" ]; then
            echo "resume: --host-vpn-tunnel disagrees with the tunnel already armed for $pid" >&2
            return "$WSS3_EXIT_PREARM_CLI_FAILURE"
        fi
        # ── Audit ROUND-30.5 P0: an agreeing declaration is accepted
        # READ-ONLY. Rewriting it would replace a file this command does
        # not own with an identical one, which both muddies ownership
        # for the rollback and puts a perfectly good arm through a
        # needless write.
        echo "resume: host-vpn-tunnel for $pid already matches the declaration — left untouched"
        return 0
    fi
    wss3_write_tunnel "$WSS3_RUNTIME_ROOT" "$pid" "$declared" || return 1
    echo "resume: migrated host-vpn-tunnel for $pid"
    return 0
}

_wss3_reprearm_from_profile() {
    # Re-arms a profile by reading its existing PROFILE.json to recover
    # the arm_token (per REDLINE-7 blocker 1 — new attempt copies the
    # retained arm_token; does NOT mint a new one). Creates a fresh
    # UTC-suffixed evidence dir.
    local src_dir="$1"
    local src_profile="$src_dir/PROFILE.json"
    # Audit ROUND-30.4 P0: start every re-arm with an empty transaction,
    # so a rollback can never remove a directory an earlier call owned.
    WSS3_REARM_EV_DIR=""
    WSS3_REARM_TUNNEL_CREATED=0
    [ -f "$src_profile" ] || { echo "re-arm: $src_profile missing" >&2; return 1; }
    local pdata carrier phone host num arm_token apk_sha run_id operator_lower
    pdata=$(python3 -c "
import json, sys
o = json.load(open(sys.argv[1]))
print(o['carrier'], o['phone_vpn'], o['host_vpn'],
      o['expected_operator_numeric'], o['arm_token'],
      o['apk_sha256'], o['run_id'])
" "$src_profile")
    carrier=$(printf '%s' "$pdata" | awk '{print $1}')
    phone=$(printf '%s' "$pdata" | awk '{print $2}')
    host=$(printf '%s' "$pdata" | awk '{print $3}')
    num=$(printf '%s' "$pdata" | awk '{print $4}')
    arm_token=$(printf '%s' "$pdata" | awk '{print $5}')
    apk_sha=$(printf '%s' "$pdata" | awk '{print $6}')
    run_id=$(printf '%s' "$pdata" | awk '{print $7}')
    operator_lower=$(printf '%s' "$carrier" | tr '[:upper:]' '[:lower:]')
    local pid
    pid=$(wss3_profile_id_from_flags "$operator_lower" "$phone" "$host")
    # ── Audit ROUND-30.10 rework: `resume` IS a transaction ─────────
    # It reserved an attempt directory and migrated a tunnel declaration
    # with no lock and no journal of its own, relying on the writers to
    # take one — and the tunnel writer took none either. Two `resume`
    # runs for one profile could therefore interleave, and a failure
    # part-way through had no captured pre-state to restore. The lock and
    # the journal are taken here, before the first read, and every exit
    # below goes through commit or rollback.
    local _rr_rc=0
    _wss3_journal_begin "$WSS3_RUNTIME_ROOT" "$pid" || return $?
    wss3_txn_scope_add "$WSS3_EVIDENCE_ROOT"
    # ── Audit ROUND-30.10 closure: re-read the parent UNDER the lock ──
    # The parent attempt and its `attempt_id` were chosen before the lock
    # existed, and nothing re-checked them once it was held. Two resume
    # runs could therefore queue on the lock, both having read the SAME
    # latest attempt, and each go on to create a successor of it — one
    # parent with two children, which the lineage is not allowed to have
    # and which the verifier can only report after the fact. The parent
    # must still be the latest attempt at the moment this transaction
    # owns the profile, or this run is superseded and stops without
    # mutating anything.
    local _now_latest
    _now_latest=$(wss3_latest_evidence_dir_for_profile "$WSS3_EVIDENCE_ROOT" "$pid")
    if [ "$_now_latest" != "$src_dir" ]; then
        echo "$WSS3_ERR_STALE_RUNTIME" >&2
        echo "resume: $pid gained a newer attempt while this run waited for the lock — refusing before any change" >&2
        _wss3_resume_rollback_attempt "$pid"
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    # Re-arm .runtime with the same service + arm_token.
    local stored_service
    stored_service=$(wss3_read_service "$WSS3_RUNTIME_ROOT" "$pid") || {
        _rr_rc=$?
        _wss3_resume_rollback_attempt "$pid"
        return "$_rr_rc"
    }
    # ── Audit ROUND-30.2 P0-1: typed, arm-bound tunnel migration ────
    # Persist a tunnel declaration supplied to `resume` for THIS
    # profile only. Old evidence is never touched — the migration
    # writes solely into `.runtime` at 0600, and `wss3_write_tunnel`
    # enforces exact interface syntax.
    #
    # Audit ROUND-30.3 P0-2: remember whether a declaration existed
    # before this call, so a failure AFTER the migration can put the
    # runtime back the way it was instead of leaving a tunnel bound to
    # an attempt that was never created.
    #
    # Audit ROUND-30.5 P0: "was it there BEFORE?" is answered with
    # `wss3_path_present`, not `[ -f ]`. A directory or dangling symlink
    # at that path is something this command did not create and must
    # never delete, and `[ -f ]` would have called both of them absent.
    # (Explicit `if` — a bare `[ … ] && x=1` AND-list returns non-zero
    # when the file is absent and would trip this script's `set -e`.)
    local tunnel_path="$WSS3_RUNTIME_ROOT/$pid/host-vpn-tunnel"
    local tunnel_existed=0
    if wss3_path_present "$tunnel_path"; then
        tunnel_existed=1
    fi

    # ── Audit ROUND-30.4 P0: reserve the attempt BEFORE any change ──
    # R30.3 migrated the tunnel first and only then derived a directory
    # name from a one-second UTC stamp, creating it with `mkdir -p`. A
    # same-second collision silently reused the PREVIOUS attempt
    # directory and overwrote its `PROFILE.json` — §3.4's "old evidence
    # is never modified" broken, and the superseded attempt left
    # pointing at itself. A future-dated attempt directory poisoned the
    # ordering permanently.
    #
    # The reservation now runs first and is atomic: the stamp must sort
    # strictly after every existing attempt for this profile, and the
    # directory is created with a bare `mkdir`, which fails if the path
    # exists. Nothing has been mutated yet when it refuses.
    local ev_dir reserve_rc=0
    ev_dir=$(_wss3_reserve_attempt_dir "$pid") || reserve_rc=$?
    if [ "$reserve_rc" != "0" ] || [ -z "$ev_dir" ]; then
        _wss3_resume_rollback_attempt "$pid"
        return "${reserve_rc:-1}"
    fi
    # From here on the command owns `$ev_dir`; every failure path below
    # unwinds it together with a tunnel this command created. Ownership
    # is registered with the journal, which is what removes it.
    WSS3_REARM_EV_DIR="$ev_dir"
    WSS3_REARM_TUNNEL_CREATED=0
    if ! _wss3_journal_created "$ev_dir"; then
        echo "resume: could not take ownership of the reserved attempt directory" >&2
        _wss3_resume_rollback_attempt "$pid"
        return 1
    fi

    local migrate_rc=0
    _wss3_migrate_tunnel_for "$pid" "$host" || migrate_rc=$?
    # ── Audit ROUND-30.5 P0: ownership comes from OBSERVED state ────
    # R30.4 set this flag only after the migration RETURNED SUCCESS, so
    # a writer that failed AFTER publishing the file — `mv` done, final
    # `chmod` refused — rolled back with the flag still 0 and left the
    # declaration it had just created on disk. What decides ownership is
    # the filesystem before and after the call, never the call's exit
    # code: absent before and present after means this transaction
    # created it, whatever the writer reported.
    if [ "$tunnel_existed" = "0" ] && wss3_path_present "$tunnel_path"; then
        WSS3_REARM_TUNNEL_CREATED=1
    fi
    if [ "$migrate_rc" != "0" ]; then
        _wss3_resume_rollback_attempt "$pid"
        return "$migrate_rc"
    fi
    # (Service and arm_token already present; touch them via a fresh
    # write to renew mtimes only if the caller wanted; skip here.)

    local now_ms mono_ms
    now_ms=$(now_ms)
    mono_ms=$(python3 -c 'import time; print(int(time.monotonic()*1000))')
    local attempt_id
    attempt_id=$(wss3_mint_arm_token)
    local supers="${WSS3_SUPERSEDES_ATTEMPT_ID:-null}"
    local retry_reason="${WSS3_RETRY_REASON:-null}"
    local confirm
    confirm=$(wss3_confirmation_string "$carrier" "$phone" "$host")

    local write_rc=0
    python3 - "$ev_dir/PROFILE.json" <<PY || write_rc=$?
import json, sys
path = sys.argv[1]
obj = {
  "carrier": "$carrier",
  "phone_vpn": "$phone",
  "host_vpn": "$host",
  "expected_operator_numeric": "$num",
  "apk_sha256": "$apk_sha",
  "run_id": "$run_id",
  "attempt_id": "$attempt_id",
  "supersedes_attempt_id": (None if "$supers" == "null" else "$supers"),
  "retry_reason": (None if "$retry_reason" == "null" else "$retry_reason"),
  "arm_token": "$arm_token",
  "arm_wall_ms": $now_ms,
  "arm_monotonic_ms": $mono_ms,
  "confirm_string": "$confirm",
  "operator_manual_confirmations": {
    "auto_data_switching_off": True,
    "other_sim_data_disabled": True,
  },
}
with open(path, "w", encoding="utf-8") as f:
    json.dump(obj, f, sort_keys=True, indent=2)
PY
    # Audit ROUND-30.3 P0-1/P0-2: an unwritten successor PROFILE.json is
    # a failed re-arm, not a quiet one. Leave neither a half-built
    # attempt directory nor a tunnel this attempt never earned.
    if [ "$write_rc" != "0" ] || [ ! -f "$ev_dir/PROFILE.json" ]; then
        echo "resume: could not write $ev_dir/PROFILE.json" >&2
        _wss3_resume_rollback_attempt "$pid"
        return 1
    fi
    if ! _wss3_copy_verified_successor_baseline "$src_dir" "$ev_dir"; then
        echo "resume: could not inherit the verified preflight baseline" >&2
        _wss3_resume_rollback_attempt "$pid"
        return 1
    fi
    if ! _wss3_verify_required_evidence "$ev_dir"; then
        echo "resume: inherited baseline failed successor verification" >&2
        _wss3_resume_rollback_attempt "$pid"
        return 1
    fi
    # Audit ROUND-30.10 rework: the transaction stays OPEN here. The
    # caller still has to prove the successor attempt exists and
    # carries the right lineage, and that verification belongs to
    # this transaction — committing at this point would move the
    # commit BEFORE a mandatory check and leave the caller's
    # rollback with nothing to unwind, which is exactly the defect
    # ROUND-30.3 and ROUND-30.4 closed for this same command.
    # The caller commits.
    echo "resume: re-armed $pid at $ev_dir (retry_reason=$retry_reason, supersedes=$supers)"
    return 0
}

# _wss3_str_gt <a> <b>
#   Exit 0 iff <a> sorts strictly after <b> under LC_ALL=C. Used instead
#   of bash's locale-sensitive `[[ > ]]` so the attempt ordering is the
#   same byte ordering the evidence directory listing uses.
_wss3_str_gt() {
    [ "$1" != "$2" ] || return 1
    [ "$(printf '%s\n%s\n' "$1" "$2" | LC_ALL=C sort | tail -1)" = "$1" ]
}

# _wss3_max_attempt_basename <profile-id>
#   The highest-sorting existing attempt directory name for this
#   profile, or nothing when the profile has no attempt yet.
_wss3_max_attempt_basename() {
    local pid="$1"
    [ -d "$WSS3_EVIDENCE_ROOT" ] || return 0
    find "$WSS3_EVIDENCE_ROOT" -mindepth 1 -maxdepth 1 -type d -name "${pid}-*" 2>/dev/null \
        | while read -r d; do basename "$d"; done \
        | LC_ALL=C sort | tail -1
}

# How many one-second stamps a re-arm may try before giving up. A
# same-second collision is resolved by waiting for the clock, never by
# reusing or overwriting the colliding directory.
readonly WSS3_ATTEMPT_STAMP_TRIES=3

# _wss3_reserve_attempt_dir <profile-id>
#   Audit ROUND-30.4 P0. Prints the newly CREATED attempt directory and
#   returns 0, or prints nothing and returns non-zero having changed
#   nothing. Three rules, all fail-closed:
#
#     1. the stamp must sort strictly after every existing attempt for
#        this profile;
#     2. a stamp that collides with the newest existing attempt (the
#        same-second case) is retried a BOUNDED number of times while
#        the clock advances, then refused;
#     3. an existing attempt that sorts AFTER a stamp taken now is a
#        future-dated directory: waiting cannot fix it, so it is refused
#        immediately — before the tunnel migration, before any write.
#
#   The directory is created with a bare `mkdir`. `mkdir -p` accepted an
#   existing path, which is exactly how R30.3 overwrote a previous
#   attempt's `PROFILE.json`.
_wss3_reserve_attempt_dir() {
    local pid="$1"
    local max cand utc tries=0
    max=$(_wss3_max_attempt_basename "$pid")
    while [ "$tries" -lt "$WSS3_ATTEMPT_STAMP_TRIES" ]; do
        tries=$((tries + 1))
        utc=$(date -u +%Y%m%dT%H%M%SZ)
        cand="${pid}-${utc}"
        if [ -z "$max" ] || _wss3_str_gt "$cand" "$max"; then
            mkdir -p "$WSS3_EVIDENCE_ROOT" || return 1
            if ! mkdir "$WSS3_EVIDENCE_ROOT/$cand" 2>/dev/null; then
                echo "resume: attempt directory $cand already exists — refusing to reuse it" >&2
                return "$WSS3_EXIT_PREARM_CLI_FAILURE"
            fi
            printf '%s' "$WSS3_EVIDENCE_ROOT/$cand"
            return 0
        fi
        if [ "$cand" != "$max" ]; then
            echo "$WSS3_ERR_STALE_RUNTIME" >&2
            echo "resume: $pid has a future-dated attempt ($max) — refusing before any change" >&2
            return "$WSS3_EXIT_PREARM_CLI_FAILURE"
        fi
        sleep 1
    done
    echo "resume: no strictly newer attempt timestamp for $pid after $WSS3_ATTEMPT_STAMP_TRIES tries" >&2
    return "$WSS3_EXIT_PREARM_CLI_FAILURE"
}

# _wss3_resume_rollback_attempt <profile-id>
#   Audit ROUND-30.4 P0 transaction unwind. Removes ONLY what this
#   command created: the attempt directory it reserved with a bare
#   `mkdir` (so it can never be a pre-existing one) and the
#   `host-vpn-tunnel` declaration it wrote. A declaration that was
#   already armed belongs to the earlier attempt and is never touched,
#   and no evidence outside the reserved directory is read or written.
#   Audit ROUND-30.7: this is now THE unwind for both transactions. It
#   also removes a `host-vpn-service`/`arm-token` pair this command
#   created, so a preflight that fails after arming — a declined
#   confirmation, an unwritable PROFILE.json, a SIGINT — leaves the
#   store exactly as it found it.
#   Audit ROUND-30.9: when an outer journal is open it owns the unwind —
#   it is the only thing that holds the original bytes and modes. The
#   existence-only path below remains for the resume transaction, which
#   never overwrites a record.
_wss3_resume_rollback_attempt() {
    local pid="${1:-${WSS3_TXN_PID:-}}" rc=0
    if wss3_txn_active; then
        _wss3_journal_rollback || rc=1
        WSS3_REARM_EV_DIR=""
        WSS3_REARM_TUNNEL_CREATED=0
        WSS3_TXN_PID=""
        WSS3_TXN_SVC_CREATED=0
        WSS3_TXN_ARM_CREATED=0
        return "$rc"
    fi
    [ -n "$pid" ] || return 0
    if [ -n "${WSS3_REARM_EV_DIR:-}" ] && [ -d "$WSS3_REARM_EV_DIR" ]; then
        rm -rf -- "$WSS3_REARM_EV_DIR" 2>/dev/null || true
        if [ -e "$WSS3_REARM_EV_DIR" ]; then
            echo "resume: WARNING — could not roll back the attempt at $WSS3_REARM_EV_DIR" >&2
            rc=1
        fi
    fi
    if [ "${WSS3_REARM_TUNNEL_CREATED:-0}" = "1" ]; then
        local f="$WSS3_RUNTIME_ROOT/$pid/host-vpn-tunnel"
        # ── Audit ROUND-30.6 P1: `wss3_path_present`, before AND after ──
        # R30.5 gated removal on `[ -e "$f" ]`, which is FALSE for a
        # dangling symlink — precisely the case `wss3_path_present` was
        # introduced to catch. Ownership detection used the new helper
        # while the removal still used the old test, so a late failure
        # that left a dangling symlink rolled the attempt back and kept
        # the link this command had created.
        if wss3_path_present "$f"; then
            rm -f -- "$f" 2>/dev/null || true
            if wss3_path_present "$f"; then
                echo "resume: WARNING — could not roll back the migrated tunnel for $pid" >&2
                rc=1
            fi
        fi
    fi
    # Audit ROUND-30.7: the arm pair this transaction created.
    local sf="$WSS3_RUNTIME_ROOT/$pid/host-vpn-service"
    local af="$WSS3_RUNTIME_ROOT/$pid/arm-token"
    if [ "${WSS3_TXN_SVC_CREATED:-0}" = "1" ] && wss3_path_present "$sf"; then
        rm -f -- "$sf" 2>/dev/null || true
        if wss3_path_present "$sf"; then
            echo "rollback: WARNING — could not remove the service record for $pid" >&2
            rc=1
        fi
    fi
    if [ "${WSS3_TXN_ARM_CREATED:-0}" = "1" ] && wss3_path_present "$af"; then
        rm -f -- "$af" 2>/dev/null || true
        if wss3_path_present "$af"; then
            echo "rollback: WARNING — could not remove the arm-token record for $pid" >&2
            rc=1
        fi
    fi
    WSS3_REARM_EV_DIR=""
    WSS3_REARM_TUNNEL_CREATED=0
    WSS3_TXN_PID=""
    WSS3_TXN_SVC_CREATED=0
    WSS3_TXN_ARM_CREATED=0
    return "$rc"
}

# ─── main dispatch ───

if [ $# -lt 1 ]; then
    wss3_usage
    exit 1
fi

verb="$1"; shift
# Explicit exit-code propagation — `case` does propagate the last
# command's exit code, but the EXIT trap plus set -e interact in
# ways that have masked failures in the past. Explicit `exit $?`
# per branch removes the ambiguity.
_rc=0
case "$verb" in
    preflight) wss3_verb_preflight "$@" || _rc=$? ;;
    smoke)     wss3_verb_smoke "$@"     || _rc=$? ;;
    full)      wss3_verb_full "$@"      || _rc=$? ;;
    resume)    wss3_verb_resume "$@"    || _rc=$? ;;
    compare)   wss3_verb_compare "$@"   || _rc=$? ;;
    cleanup)   wss3_verb_cleanup "$@"   || _rc=$? ;;
    -h|--help|help) wss3_usage ;;
    *) echo "unknown verb: $verb" >&2; wss3_usage >&2; _rc=1 ;;
esac
exit $_rc
