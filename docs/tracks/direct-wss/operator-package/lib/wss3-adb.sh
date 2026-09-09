#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — ADB abstraction seam.
#
# Contract: docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md
# §10 step 4 (synthetic Mac dry-run) + audit ROUND-8 P0-1 remediation.
#
# Every ADB call in the WSS-3 orchestrator goes through `wss3_adb`.
# Under production it dispatches to the real `adb` (or an env-var
# override for CI-supplied SDK path). Under WSS3_DRY_RUN=1 it invokes
# a scripted mock (`${WSS3_ADB_MOCK:-lib/wss3-adb-mock.sh}`) that
# reads canned responses from `${WSS3_ADB_MOCK_SPEC}` — a plain-text
# file the shell fixtures + synthetic dry-run set up per-scenario.
#
# The DRY_RUN dispatch runs the SAME production code paths in
# smoke/full/preflight; only the network endpoint (adb) is replaced.
# That satisfies the audit ROUND-8 requirement "SAME code paths under
# WSS3_DRY_RUN=1".
#
# Absolutely no dependency on physical devices from this module.
# Public function is `wss3_adb <argv...>` — same argv shape as `adb`.

: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
    # shellcheck source=portable.sh
    source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
    PORTABLE_SH_LOADED=1
fi

# wss3_adb <argv...> — the single ADB entry point for the orchestrator.
# Dispatches to `${WSS3_ADB_CMD}` if set, else `adb`. Under WSS3_DRY_RUN
# the default `${WSS3_ADB_CMD}` becomes the mock script.
wss3_adb() {
    local cmd="${WSS3_ADB_CMD:-}"
    if [ -z "$cmd" ]; then
        if [ "${WSS3_DRY_RUN:-}" = "1" ]; then
            cmd="$(dirname "${BASH_SOURCE[0]}")/wss3-adb-mock.sh"
        else
            cmd="adb"
        fi
    fi
    "$cmd" "$@"
}

# wss3_adb_devices_visible <phone-serial> <emu-serial>
#   Exit 0 if `adb devices` output contains both serials in the
#   `device` state (not `unauthorized` / `offline`). Returns 1 else.
wss3_adb_devices_visible() {
    local phone="$1" emu="$2"
    local out
    out=$(wss3_adb devices 2>/dev/null)
    printf '%s\n' "$out" | grep -qE "^${phone}[[:space:]]+device([[:space:]]|\$)" \
        && printf '%s\n' "$out" | grep -qE "^${emu}[[:space:]]+device([[:space:]]|\$)"
}

# wss3_adb_apk_sha256 <serial> <package>
#   Prints the SHA-256 of the installed APK for <package>. Empty on
#   error. Uses `pm path <pkg>` to get the path, then `sha256sum` on
#   the device (Android 8+ has toybox sha256sum).
wss3_adb_apk_sha256() {
    local serial="$1" pkg="$2"
    local pmout
    pmout=$(wss3_adb -s "$serial" shell pm path "$pkg" 2>/dev/null | tr -d '\r')
    local apk_path
    apk_path=$(printf '%s\n' "$pmout" | head -1 | sed 's|^package:||')
    [ -n "$apk_path" ] || return 1
    local sh
    sh=$(wss3_adb -s "$serial" shell "sha256sum '$apk_path' 2>/dev/null" | tr -d '\r' | awk '{print $1}')
    printf '%s' "$sh"
}

# wss3_adb_invoke_diag_cmd <serial> <subcommand> [extra --es args...]
#   Thin wrapper around the accepted WSS-2 `lib/diag-cmd.sh` helper.
#   Forwarded via wss3_adb so the mock intercepts it in dry-run.
#   Per audit ROUND-9 P0-8: `send` subcommand ALWAYS uses `--async`
#   so the ADB reply doesn't wait for a hung sendMessage — matches
#   the accepted WSS-2 `lib/diag-cmd.sh` `--async` policy verbatim.
#   Every other subcommand stays synchronous (short receiver work).
wss3_adb_invoke_diag_cmd() {
    local serial="$1" sub="$2"; shift 2
    local component="phantom.android/phantom.android.diagnostic.DiagnosticCommandReceiver"
    # Audit ROUND-12 P0-#1: on stock macOS Bash 3.2 with `set -u`
    # active (see run-carrier-vpn-matrix.sh:28 `set -euo pipefail`),
    # expanding `"${arr[@]}"` on an EMPTY array is an unbound-variable
    # error, which took down every non-`send` subcommand invocation.
    # The failure was silently masked by the runner's EXIT trap. The
    # portable expansion `"${arr[@]+"${arr[@]}"}"` is a no-op when the
    # array is empty and expands each element otherwise; it works on
    # Bash 3.2 through 5.x.
    local am_flags=()
    if [ "$sub" = "send" ]; then
        am_flags+=(--async)
    fi
    local args=(-s "$serial" shell am broadcast "${am_flags[@]+"${am_flags[@]}"}" -n "$component" --es subcommand "$sub" "$@")
    wss3_adb "${args[@]}" 2>&1
}

# wss3_adb_wait_for_cid_completion <serial> <run-id> <cell-id> <cid> <timeout-s>
#   Bounded poll for the recipient triplet on <cid>. Streams
#   `adb logcat -d` in a loop (bounded 120s max) until either:
#     - all three recipient events (recipient_deliver_received,
#       recipient_message_persisted, recipient_ack_deliver_sent)
#       are seen for this cid on this serial;
#     - the timeout expires (return 1, caller marks Unresolved).
#   Under WSS3_DRY_RUN=1 the mock adb emits events synchronously
#   before `am broadcast` returns, so the first snapshot finds
#   everything and the loop terminates in one iteration.
wss3_adb_wait_for_cid_completion() {
    local serial="$1" run_id="$2" cell_id="$3" cid="$4" timeout_s="${5:-120}"
    local deadline
    deadline=$(( $(date +%s) + timeout_s ))
    local tmp
    tmp=$(mktemp -t wss3-wait.XXXXXX)
    while [ "$(date +%s)" -lt "$deadline" ]; do
        wss3_adb_logcat_snapshot "$serial" "$tmp"
        if grep -q "event=recipient_deliver_received.*correlation_id=$cid" "$tmp" && \
           grep -q "event=recipient_message_persisted.*correlation_id=$cid" "$tmp" && \
           grep -q "event=recipient_ack_deliver_sent.*correlation_id=$cid" "$tmp"; then
            rm -f "$tmp"
            return 0
        fi
        # Dry-run mock is synchronous; a single miss means it will
        # never arrive — don't spin.
        if [ "${WSS3_DRY_RUN:-}" = "1" ]; then
            rm -f "$tmp"
            return 1
        fi
        sleep 2
    done
    rm -f "$tmp"
    return 1
}

# wss3_adb_invoke_network_profile_report <serial> <checkpoint-key-hex>
#   Broadcasts network_profile_report (EGRESS-CAPABLE variant — HMAC
#   computed). Read + delete flow same as before.
#   PER CONTRACT §4.5 + AUDIT ROUND-10 P0-1: this variant is used ONLY
#   on the emulator serial. The phone MUST use
#   wss3_adb_invoke_network_profile_state_report (below) instead.
wss3_adb_invoke_network_profile_report() {
    local serial="$1" key="$2"
    wss3_adb_invoke_diag_cmd "$serial" network_profile_report --es checkpoint_key_hex "$key" >/dev/null
    [ "${WSS3_DRY_RUN:-}" = "1" ] || sleep 1
    local out
    out=$(wss3_adb -s "$serial" shell "run-as phantom.android cat /data/data/phantom.android/files/wss3/network_profile.json" 2>/dev/null | tr -d '\r')
    wss3_adb_invoke_run_as_rm "$serial" "/data/data/phantom.android/files/wss3/network_profile.json"
    printf '%s' "$out"
}

# wss3_adb_invoke_network_profile_state_report <serial>
#   Broadcasts network_profile_state_report — state-only variant per
#   audit ROUND-10 P0-1. NO checkpoint_key_hex extra. The emitted JSON
#   carries NO egress_fingerprint object. Used EXCLUSIVELY on the
#   phone serial: the phone never participates in HMAC-egress
#   fingerprinting (contract REDLINE-4 blocker 1).
wss3_adb_invoke_network_profile_state_report() {
    local serial="$1"
    wss3_adb_invoke_diag_cmd "$serial" network_profile_state_report >/dev/null
    [ "${WSS3_DRY_RUN:-}" = "1" ] || sleep 1
    local out
    out=$(wss3_adb -s "$serial" shell "run-as phantom.android cat /data/data/phantom.android/files/wss3/network_profile.json" 2>/dev/null | tr -d '\r')
    wss3_adb_invoke_run_as_rm "$serial" "/data/data/phantom.android/files/wss3/network_profile.json"
    printf '%s' "$out"
}

# wss3_adb_invoke_run_as_rm <serial> <path>
#   Public helper for the "run-as rm always" invariant (§4.5). ALWAYS
#   attempts to remove the reporter file — on success, failure, or
#   trap. Return code is best-effort informational; caller must NOT
#   fail-close on this.
wss3_adb_invoke_run_as_rm() {
    local serial="$1" path="$2"
    wss3_adb -s "$serial" shell "run-as phantom.android rm '$path' 2>/dev/null || true" >/dev/null 2>&1 || true
}

# wss3_adb_logcat_snapshot <serial> <output-file>
#   Captures a snapshot of the WSS_DIAG lines currently in logcat.
#   Uses `-d` (dump-and-exit) so this is bounded, not a stream.
wss3_adb_logcat_snapshot() {
    local serial="$1" out="$2"
    wss3_adb -s "$serial" logcat -d -v threadtime WSS_DIAG:I WSS_DIAG_CMD:I '*:S' 2>/dev/null > "$out"
}

# wss3_adb_logcat_clear <serial>
#   Clears logcat so the next snapshot has only fresh events.
wss3_adb_logcat_clear() {
    local serial="$1"
    wss3_adb -s "$serial" logcat -c 2>/dev/null || true
}
