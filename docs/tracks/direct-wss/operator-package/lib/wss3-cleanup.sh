#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — cleanup verb helpers.
#
# Contract: docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md
# §3.6 invariant 8 (REVISED per REDLINE-7 blocker 1).
#
#   - bare `cleanup` (no --all): removes .runtime/wss3/<profile-id>/
#     for every profile whose evidence dir has
#     matrix_completion.json with abort_reason=null (COMPLETE).
#     Aborted profiles are PRESERVED for retry.
#   - `cleanup --all`: additionally removes .runtime/wss3/<profile-id>/
#     for every profile whose evidence dir has matrix_completion.json
#     with abort_reason != null (ABORTED). Preserved: profiles whose
#     evidence dir has NO matrix_completion.json (in-progress).
#   - `cleanup` never touches in-progress profiles.

: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
    # shellcheck source=portable.sh
    source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
    PORTABLE_SH_LOADED=1
fi

: "${WSS3_RUNTIME_STATE_SH_LOADED:=0}"
if [ "$WSS3_RUNTIME_STATE_SH_LOADED" != "1" ]; then
    # shellcheck source=wss3-runtime-state.sh
    source "$(dirname "${BASH_SOURCE[0]}")/wss3-runtime-state.sh"
    WSS3_RUNTIME_STATE_SH_LOADED=1
fi

# wss3_completion_state <evidence-dir>
#   Prints one of: COMPLETE | ABORTED | INPROGRESS | UNKNOWN
#   - COMPLETE:   matrix_completion.json exists and abort_reason is null.
#   - ABORTED:    matrix_completion.json exists and abort_reason is a
#                 non-null enum value.
#   - INPROGRESS: matrix_completion.json is absent.
#   - UNKNOWN:    evidence-dir itself doesn't exist.
wss3_completion_state() {
    local d="$1"
    if [ ! -d "$d" ]; then
        printf 'UNKNOWN'; return 0
    fi
    local mc="$d/matrix_completion.json"
    if [ ! -f "$mc" ]; then
        printf 'INPROGRESS'; return 0
    fi
    # Parse abort_reason via python (portable JSON without jq).
    local st
    st=$(WSS3_MC_TMP="$mc" python3 - <<'PY'
import json, os, sys
mc = os.environ.pop("WSS3_MC_TMP")
try:
    with open(mc, encoding="utf-8") as f:
        obj = json.load(f)
except Exception:
    print("UNKNOWN"); sys.exit(0)
ar = obj.get("abort_reason", None)
print("COMPLETE" if ar in (None, "null") else "ABORTED")
PY
)
    printf '%s' "$st"
}

# wss3_cleanup_run <runtime-root> <evidence-root> <all-mode>
#   <all-mode> — the literal string "all" to remove aborted-retention
#   too; anything else runs the bare policy.
#
#   Iterates .runtime/wss3/<profile-id>/ subdirs. For each, looks up
#   the newest matching evidence dir (prefix match on <profile-id>)
#   and applies the removal policy.
#
# Prints one line per action:
#   removed <profile-id> (COMPLETE)
#   removed <profile-id> (ABORTED, --all)
#   kept    <profile-id> (INPROGRESS)
#   kept    <profile-id> (ABORTED, retry candidate)
#   kept    <profile-id> (UNKNOWN evidence)
wss3_cleanup_run() {
    local runtime_root="$1" evidence_root="$2" all_mode="$3"
    [ -d "$runtime_root" ] || { echo "no runtime state"; return 0; }
    # Audit ROUND-30.10 closure: an unrecognised object in the store
    # stops the walk. Skipping it and reporting success — which is what
    # a blanket "ignore hidden names" rule would have done — lets
    # `cleanup --all` announce a clean store over content it never
    # looked at.
    if ! wss3_assert_store_recognised "$runtime_root"; then
        echo "cleanup: refusing to run over a store with unrecognised content" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    local pid
    while IFS= read -r pid; do
        [ -n "$pid" ] || continue
        local latest_evidence
        latest_evidence=$(wss3_latest_evidence_dir_for_profile "$evidence_root" "$pid")
        local state
        state=$(wss3_completion_state "$latest_evidence")
        case "$state" in
            COMPLETE)
                if wss3_remove_profile_runtime "$runtime_root" "$pid"; then
                    echo "removed $pid (COMPLETE)"
                else
                    echo "kept    $pid (removal refused or failed)"
                fi
                ;;
            ABORTED)
                if [ "$all_mode" = "all" ]; then
                    if wss3_remove_profile_runtime "$runtime_root" "$pid"; then
                        echo "removed $pid (ABORTED, --all)"
                    else
                        echo "kept    $pid (removal refused or failed)"
                    fi
                else
                    echo "kept    $pid (ABORTED, retry candidate)"
                fi
                ;;
            INPROGRESS)
                echo "kept    $pid (INPROGRESS)"
                ;;
            UNKNOWN|*)
                echo "kept    $pid (UNKNOWN evidence)"
                ;;
        esac
    done < <(wss3_list_profile_ids "$runtime_root")
    return 0
}

# wss3_latest_evidence_dir_for_profile <evidence-root> <profile-id>
#   Evidence dir suffix format is `-<UTC>`; the newest dir wins.
#   Prints "" if none matches.
wss3_latest_evidence_dir_for_profile() {
    local root="$1" pid="$2"
    [ -d "$root" ] || return 0
    find "$root" -mindepth 1 -maxdepth 1 -type d -name "${pid}-*" 2>/dev/null \
        | LC_ALL=C sort \
        | tail -1
}


# ── Audit ROUND-30.16: the local WARN capture ─────────────────
#
# `PhantomMessaging:W`, not `:E`. The hold-on-MAC path logs
# `DECRYPT_TRACE ... action=hold` at WARN and then returns WITHOUT
# throwing, so an ERROR threshold would miss precisely the case that
# motivated this round. `:W` is a floor and takes WARN and ERROR both.
#
# The stream carries `e.message` and stack traces — free text this
# project does not put into an evidence archive — so it lives outside
# every tree that can be packaged, checksummed or reviewed, in a
# directory that is private, fresh and never reused.

# _wss3_realpath <path>
#   Canonical absolute path, symlinks resolved. Portable across stock
#   macOS (no `readlink -f`, no `realpath`).
_wss3_realpath() {
    local p="${1:-}"
    [ -n "$p" ] || return 1
    if [ -d "$p" ]; then
        ( cd -P "$p" 2>/dev/null && pwd -P ) || return 1
    else
        local d b
        d="$(dirname "$p")"; b="$(basename "$p")"
        d="$( cd -P "$d" 2>/dev/null && pwd -P )" || return 1
        printf '%s/%s\n' "${d%/}" "$b"
    fi
}

# _wss3_path_contains <ancestor> <candidate>
#   True when CANDIDATE is ANCESTOR or lies beneath it. Both sides are
#   canonicalised first, so this is real containment rather than a
#   substring guess: a directory merely NAMED "evidence" is fine, and a
#   symlink pointing into the package is not.
_wss3_path_contains() {
    local anc cand
    anc="$(_wss3_realpath "${1:-}")" || return 1
    cand="$(_wss3_realpath "${2:-}")" || return 1
    [ "$cand" = "$anc" ] && return 0
    case "$cand/" in
        "$anc"/*) return 0 ;;
    esac
    return 1
}

# wss3_local_diag_root <evidence-dir>
#   Creates a FRESH private directory under a canonicalised TMPDIR and
#   refuses it if it falls inside any shippable tree.
#
#   `mktemp -d` rather than a fixed name: a predictable
#   /tmp/<constant> is a symlink target another user can pre-create and
#   is reused across runs, so one run's free-text log could outlive it
#   or be redirected somewhere world-readable. Each run gets its own
#   0700 directory that never existed before.
#
#   Fail-closed: anything unresolvable, non-absolute or contained
#   returns non-zero and the caller records a diagnostic-capture
#   failure instead of writing anywhere.
wss3_local_diag_root() {
    local ev="${1:-}"
    local base
    base="$(_wss3_realpath "${WSS3_LOCAL_DIAG_ROOT:-${TMPDIR:-/tmp}}")" || return 1
    case "$base" in /*) ;; *) return 1 ;; esac
    [ -d "$base" ] || return 1

    # Refuse the BASE first: creating inside a shippable tree and
    # deleting afterwards would still have written there.
    local guard
    for guard in "$ev" "${WSS3_OPERATOR_PACKAGE_ROOT:-}" "${WSS3_EVIDENCE_ROOT:-}"; do
        [ -n "$guard" ] || continue
        _wss3_path_contains "$guard" "$base" && return 1
    done

    local dir
    dir="$(mktemp -d "$base/phantom-wss3-diag.XXXXXX" 2>/dev/null)" || return 1
    dir="$(_wss3_realpath "$dir")" || return 1
    chmod 700 "$dir" 2>/dev/null || true

    # And re-check the RESOLVED directory: mktemp honours a symlinked
    # TMPDIR, so the canonical result can land somewhere the base check
    # never saw.
    for guard in "$ev" "${WSS3_OPERATOR_PACKAGE_ROOT:-}" "${WSS3_EVIDENCE_ROOT:-}"; do
        [ -n "$guard" ] || continue
        if _wss3_path_contains "$guard" "$dir"; then
            rm -rf "$dir" 2>/dev/null || true
            return 1
        fi
    done
    printf '%s\n' "$dir"
    return 0
}

# wss3_local_diag_start <evidence-dir> <phone-serial> <emulator-serial>
#   Starts the capture in the CURRENT shell — not a subshell — so the
#   pids are this shell's children and can genuinely be `wait`-ed.
WSS3_LOCAL_DIAG_PIDS=""
WSS3_LOCAL_DIAG_DIR=""
wss3_local_diag_start() {
    local ev="${1:-}" phone="${2:-}" emu="${3:-}"
    WSS3_LOCAL_DIAG_PIDS=""
    WSS3_LOCAL_DIAG_DIR=""
    [ -n "$ev" ] || return 0
    local dir
    if ! dir="$(wss3_local_diag_root "$ev")"; then
        echo "diagnostic-capture failure: no private capture directory outside" \
             "the shippable trees (product verdict unaffected)" >&2
        return 0
    fi
    WSS3_LOCAL_DIAG_DIR="$dir"
    local tag
    tag="$(basename "$ev")"

    local old_umask
    old_umask="$(umask)"
    umask 077
    local _pair _role _serial _dest
    for _pair in "phone:$phone" "emulator:$emu"; do
        _role="${_pair%%:*}"
        _serial="${_pair#*:}"
        [ -n "$_serial" ] || continue
        _dest="$dir/${tag}.${_role}.PhantomMessaging.log"
        if ! : > "$_dest" 2>/dev/null; then
            echo "capture-create-failed role=$_role" >> "$dir/CAPTURE-FAILED" 2>/dev/null || true
            continue
        fi
        wss3_adb -s "$_serial" logcat -v threadtime -T 1 \
            PhantomMessaging:W "*:S" >> "$_dest" 2>/dev/null &
        WSS3_LOCAL_DIAG_PIDS="$WSS3_LOCAL_DIAG_PIDS $!"
    done
    umask "$old_umask"
    if [ -f "$dir/CAPTURE-FAILED" ]; then
        echo "diagnostic-capture failure recorded (product verdict unaffected)" >&2
    fi
    return 0
}

# _wss3_local_diag_reap
#   TERM → bounded poll → KILL → mandatory wait. A logcat that ignores
#   TERM must not be able to hang the cleanup path, and an unwaited
#   child must not outlive the smoke.
#
#   Audit ROUND-30.17: before signalling anything, check who is still
#   alive. A capture that ENDED ON ITS OWN before dispose — for any
#   reason and with any status, including a clean 0 — stopped recording
#   at some unknown point, so the window it was supposed to cover is not
#   covered. That is a capture failure and is recorded as one.
#
#   The signals dispose itself sends are NOT a failure: that is the
#   normal end of a capture that did its job. Distinguishing the two is
#   the whole reason liveness is sampled here rather than inferred from
#   `wait` afterwards, where they look identical.
_wss3_local_diag_reap() {
    local _pid _i
    # ── Audit ROUND-30.18: no window between the check and the signal ─
    #
    # R30.18's first attempt sampled liveness for every pid in one loop
    # and signalled in a second, so a capture that exited BETWEEN the two
    # was neither marked nor detectable afterwards: the TERM failure was
    # suppressed and `wait` cannot tell a self-exit from a reap.
    #
    # `kill -TERM` is now both the check and the signal. It succeeds only
    # while the process is alive, so its failure IS the evidence that the
    # capture had already gone - and there is no interval between the two
    # in which the state could change, because they are the same
    # syscall.
    #
    # A process that is alive and ignores TERM is escalated below; one
    # that was already gone is recorded here.
    _wss3_diag_mark_failed() {
        if [ -n "${WSS3_LOCAL_DIAG_DIR:-}" ] && [ -d "${WSS3_LOCAL_DIAG_DIR}" ]; then
            echo "capture-ended-early pid=$1" \
                >> "${WSS3_LOCAL_DIAG_DIR}/CAPTURE-FAILED" 2>/dev/null || true
        fi
    }
    for _pid in ${WSS3_LOCAL_DIAG_PIDS:-}; do
        if ! kill -TERM "$_pid" 2>/dev/null; then
            _wss3_diag_mark_failed "$_pid"
        fi
    done
    for _pid in ${WSS3_LOCAL_DIAG_PIDS:-}; do
        _i=0
        while [ "$_i" -lt 20 ]; do
            kill -0 "$_pid" 2>/dev/null || break
            sleep 0.1
            _i=$((_i + 1))
        done
        if kill -0 "$_pid" 2>/dev/null; then
            kill -KILL "$_pid" 2>/dev/null || true
        fi
        wait "$_pid" 2>/dev/null || true
    done
    WSS3_LOCAL_DIAG_PIDS=""
    return 0
}

# wss3_local_diag_dispose <evidence-dir>
#   Runs from the ONE cleanup path the driver reaches on success,
#   failure, INT and TERM.
#
#   Deletion happens ONLY on an exact GREEN predicate parsed from
#   `smoke_verdict.json`. A missing, truncated, malformed or incomplete
#   verdict keeps the capture — the moment we cannot prove the run was
#   clean is the moment the log is worth most.
wss3_local_diag_dispose() {
    local ev="${1:-}"
    local dir="${WSS3_LOCAL_DIAG_DIR:-}"
    _wss3_local_diag_reap

    [ -n "$dir" ] && [ -d "$dir" ] || return 0
    [ -n "$ev" ] || return 0
    local tag
    tag="$(basename "$ev")"

    if [ -f "$dir/CAPTURE-FAILED" ]; then
        echo "diagnostic-capture failure: PhantomMessaging:W did not start" \
             "(product verdict unaffected)" >&2
    fi

    local green=0
    if [ -f "$ev/smoke_verdict.json" ]; then
        if python3 - "$ev/smoke_verdict.json" <<'PY' >/dev/null 2>&1
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        v = json.load(fh)
except Exception:
    raise SystemExit(1)
if not isinstance(v, dict):
    raise SystemExit(1)
raise SystemExit(0 if (v.get("integrity") == "GREEN"
                       and v.get("p2e") == "OK"
                       and v.get("e2p") == "OK") else 1)
PY
        then
            green=1
        fi
    fi

    # Audit ROUND-30.17: deletion is VERIFIED, not attempted. Suppressing
    # the error and clearing the path afterwards left a sensitive log on
    # disk with nobody holding its location — the one state in which it
    # can be neither used nor cleaned up.
    local kept=0 undeleted=0 f
    for f in "$dir/$tag".*.PhantomMessaging.log; do
        [ -f "$f" ] || continue
        if [ "$green" = "1" ]; then
            rm -f "$f" 2>/dev/null || true
            if [ -e "$f" ]; then
                undeleted=$((undeleted + 1))
            fi
        else
            kept=$((kept + 1))
        fi
    done
    if [ "$green" = "1" ] && [ "$undeleted" -eq 0 ]; then
        rm -f "$dir/CAPTURE-FAILED" 2>/dev/null || true
        rmdir "$dir" 2>/dev/null || true
        if [ -d "$dir" ]; then
            echo "diagnostic-capture cleanup: could not remove $dir" \
                 "(product verdict unaffected)" >&2
        else
            WSS3_LOCAL_DIAG_DIR=""
        fi
    elif [ "$green" = "1" ]; then
        # Deletion failed. Keep the handle: the operator needs the path
        # precisely because the sensitive file is still there.
        echo "diagnostic-capture cleanup: $undeleted capture file(s) could" \
             "not be deleted and remain in: $dir (product verdict" \
             "unaffected)" >&2
    elif [ "$kept" -gt 0 ]; then
        # Path only. Never the contents.
        echo "local diagnostic capture retained (not shipped): $dir" >&2
    fi
    return 0
}
