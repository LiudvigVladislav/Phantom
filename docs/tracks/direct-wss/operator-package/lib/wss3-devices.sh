#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — live device resolver (audit ROUND-26).
#
# Physical R26 finding: run-carrier-vpn-matrix.sh's default of
# `${WSS3_PHONE_SERIAL:-mock-phone-serial}` was shipping mock serials
# into every live preflight/smoke/full invocation, so the first R25
# field preflight failed with:
#     preflight: adb devices missing phone=mock-phone-serial ...
# even though the phone and the AVD were both healthy on `adb devices`.
#
# Every WSS-3 runner phase now shares this resolver, which speaks
# only through the wss3_adb seam (never raw `adb`).
#
# Modes:
#   - Auto-detect (no overrides): enumerate `adb devices` for online
#     serials, classify each via `getprop ro.kernel.qemu`, require
#     EXACTLY one physical phone (qemu != "1") and EXACTLY one
#     emulator (qemu == "1"), return their serials. Never falls back
#     to mock-phone-serial / mock-emu-serial.
#   - Explicit overrides: BOTH `WSS3_PHONE_SERIAL` and `WSS3_EMU_SERIAL`
#     must be set. Both must be online. The phone must classify as a
#     physical device (getprop ro.kernel.qemu != "1"). The emulator
#     must classify as an emulator (ro.kernel.qemu == "1"). Partial
#     or role-swapped overrides fail closed BEFORE any runtime state
#     or evidence is created (this module's contract; the caller
#     enforces the no-side-effects rule by invoking the resolver
#     before mkdir / write of the profile's runtime + evidence dirs).
#
# Public API:
#   wss3_devices_resolve
#     Emits two lines on stdout:
#         phone=<serial>
#         emu=<serial>
#     Exit 0 on success. On any failure: prints a machine-parsable
#     error code (WSS3_DEV_ERR_*) followed by a human-readable
#     diagnostic on stderr and returns a non-zero exit code.
#
#   _wss3_resolve_serials_to <phone-var-name> <emu-var-name>
#     Runner-facing helper: calls the resolver and assigns the two
#     resolved serials into the caller-named variables. Returns
#     non-zero on any resolver failure (caller MUST propagate).

: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
    # shellcheck source=portable.sh
    source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
    PORTABLE_SH_LOADED=1
fi
: "${WSS3_ADB_LOADED:=0}"
if [ "$WSS3_ADB_LOADED" != "1" ]; then
    # shellcheck source=wss3-adb.sh
    source "$(dirname "${BASH_SOURCE[0]}")/wss3-adb.sh"
    WSS3_ADB_LOADED=1
fi

# Error codes. All map onto WSS3_EXIT_PREARM_CLI_FAILURE at the
# runner boundary; specific codes let fixtures assert precise
# causes and let the operator see the exact failure mode without
# reading the whole runner log.
readonly WSS3_DEV_ERR_PARTIAL_OVERRIDE="devices_partial_override"
readonly WSS3_DEV_ERR_OVERRIDE_OFFLINE="devices_override_offline"
readonly WSS3_DEV_ERR_OVERRIDE_ROLE_MISMATCH="devices_override_role_mismatch"
readonly WSS3_DEV_ERR_CLASSIFY_FAIL="devices_classify_failed"
readonly WSS3_DEV_ERR_ZERO_PHONE="devices_zero_phone"
readonly WSS3_DEV_ERR_ZERO_EMU="devices_zero_emu"
readonly WSS3_DEV_ERR_MULTIPLE_PHONE="devices_multiple_phone"
readonly WSS3_DEV_ERR_MULTIPLE_EMU="devices_multiple_emu"
readonly WSS3_DEV_ERR_ENUMERATION_FAILED="devices_enumeration_failed"

# wss3_devices_online
#   Prints one serial per line for every `adb devices` line whose
#   state is exactly `device` (offline / unauthorized / no-permissions
#   / connecting are dropped silently — the resolver still fails
#   closed if the resulting topology is wrong, but a merely-
#   unauthorized device does NOT become a phantom candidate).
#
#   Audit ROUND-27 (R26 REDLINE P0-1): capture stdout AND rc of
#   `wss3_adb devices` separately. Any non-zero rc — even with a
#   valid-looking devices list on stdout — is fail-closed as
#   WSS3_DEV_ERR_ENUMERATION_FAILED at the caller. This function
#   itself just returns non-zero on rc failure; it does NOT rely on
#   caller `set -e` or `pipefail`.
wss3_devices_online() {
    local raw enum_rc=0
    raw=$(wss3_adb devices 2>/dev/null) || enum_rc=$?
    if [ "$enum_rc" != "0" ]; then
        return 1
    fi
    printf '%s\n' "$raw" | awk 'NR>1 && $2=="device" { print $1 }'
    return 0
}

# wss3_devices_classify <serial>
#   Prints "phone" or "emu" on stdout, exit 0. Prints nothing +
#   returns non-zero on classification failure (device vanished
#   mid-flight, adb race, permission-denied shell).
#
#   Audit ROUND-27 (R26 REDLINE P0-2): the exit code of the
#   `wss3_adb -s SER shell getprop ro.kernel.qemu` invocation is
#   authoritative — captured BEFORE any stdout normalisation, and
#   non-zero returns non-zero regardless of what came back on
#   stdout. A preceding `shell true` liveness probe is not a
#   substitute: getprop's own rc is the only signal that a
#   classification decision may safely rest on.
#
#   Successful getprop → empty stdout means "phone" (production
#   phones lack ro.kernel.qemu); stdout "1" means "emu".
wss3_devices_classify() {
    local serial="$1"
    local raw getprop_rc=0
    # Audit ROUND-28 P0: `wss3_devices_resolve` iterates serials via
    # `while read` fed by a heredoc, and this classify call runs
    # inside that loop body. Real macOS `adb -s SER shell CMD` reads
    # from its inherited stdin (it opens an adbd shell session);
    # without the `</dev/null` guard here it drains the next serial
    # line straight out of the loop's heredoc, so after phys-A is
    # classified the loop iterator sees EOF and emu-B disappears —
    # the resolver returns devices_zero_emu on a perfectly healthy
    # 1-phone + 1-emu topology. Pin required: this getprop
    # invocation MUST redirect stdin from /dev/null. Do not remove
    # `</dev/null` on this exact line without also refactoring the
    # resolver's iteration model.
    raw=$(wss3_adb -s "$serial" shell getprop ro.kernel.qemu </dev/null 2>/dev/null) || getprop_rc=$?
    if [ "$getprop_rc" != "0" ]; then
        return 1
    fi
    local first
    first=$(printf '%s' "$raw" | tr -d '\r' | awk 'NR==1 { print; exit }')
    if [ "$first" = "1" ]; then
        printf 'emu\n'
    else
        printf 'phone\n'
    fi
    return 0
}

# Internal — verify one override serial matches the required role.
_wss3_devices_verify_override() {
    local role="$1" serial="$2" online="$3"
    if ! printf '%s\n' "$online" | grep -Fxq -- "$serial"; then
        echo "$WSS3_DEV_ERR_OVERRIDE_OFFLINE" >&2
        echo "resolve: $role override serial '$serial' is not in \`adb devices\` (state=device)" >&2
        return 1
    fi
    local got classify_rc=0
    got=$(wss3_devices_classify "$serial") || classify_rc=$?
    if [ "$classify_rc" != "0" ]; then
        echo "$WSS3_DEV_ERR_CLASSIFY_FAIL" >&2
        echo "resolve: getprop ro.kernel.qemu classification failed for $role='$serial'" >&2
        return 1
    fi
    if [ "$got" != "$role" ]; then
        echo "$WSS3_DEV_ERR_OVERRIDE_ROLE_MISMATCH" >&2
        echo "resolve: $role override serial '$serial' classified as '$got' (expected '$role')" >&2
        return 1
    fi
    return 0
}

wss3_devices_resolve() {
    local phone_ov="${WSS3_PHONE_SERIAL:-}"
    local emu_ov="${WSS3_EMU_SERIAL:-}"

    # Reject partial overrides BEFORE touching adb — nothing about
    # the device topology can fix a caller who supplied only one
    # of the two required serials.
    if [ -n "$phone_ov" ] && [ -z "$emu_ov" ]; then
        echo "$WSS3_DEV_ERR_PARTIAL_OVERRIDE" >&2
        echo "resolve: WSS3_PHONE_SERIAL is set but WSS3_EMU_SERIAL is not — both required for explicit override" >&2
        return 1
    fi
    if [ -z "$phone_ov" ] && [ -n "$emu_ov" ]; then
        echo "$WSS3_DEV_ERR_PARTIAL_OVERRIDE" >&2
        echo "resolve: WSS3_EMU_SERIAL is set but WSS3_PHONE_SERIAL is not — both required for explicit override" >&2
        return 1
    fi

    local online enum_rc=0
    online=$(wss3_devices_online) || enum_rc=$?
    if [ "$enum_rc" != "0" ]; then
        echo "$WSS3_DEV_ERR_ENUMERATION_FAILED" >&2
        echo "resolve: \`adb devices\` enumeration failed (rc=$enum_rc)" >&2
        return 1
    fi

    if [ -n "$phone_ov" ] && [ -n "$emu_ov" ]; then
        # Explicit override path.
        _wss3_devices_verify_override phone "$phone_ov" "$online" || return 1
        _wss3_devices_verify_override emu   "$emu_ov"   "$online" || return 1
        printf 'phone=%s\n' "$phone_ov"
        printf 'emu=%s\n'   "$emu_ov"
        return 0
    fi

    # Auto-detect path: classify every online device, require
    # exactly one phone AND exactly one emu.
    local phones="" emus=""
    local phone_count=0 emu_count=0
    local s got classify_rc
    while IFS= read -r s; do
        [ -n "$s" ] || continue
        classify_rc=0
        got=$(wss3_devices_classify "$s") || classify_rc=$?
        if [ "$classify_rc" != "0" ]; then
            echo "$WSS3_DEV_ERR_CLASSIFY_FAIL" >&2
            echo "resolve: getprop ro.kernel.qemu classification failed for online serial '$s'" >&2
            return 1
        fi
        if [ "$got" = "phone" ]; then
            phones="${phones}${s}
"
            phone_count=$((phone_count+1))
        else
            emus="${emus}${s}
"
            emu_count=$((emu_count+1))
        fi
    done <<EOF
$online
EOF

    if [ "$phone_count" = "0" ]; then
        echo "$WSS3_DEV_ERR_ZERO_PHONE" >&2
        echo "resolve: no online physical phone (\`adb devices\` had $phone_count/1)" >&2
        return 1
    fi
    if [ "$phone_count" -gt 1 ]; then
        echo "$WSS3_DEV_ERR_MULTIPLE_PHONE" >&2
        echo "resolve: expected exactly one physical phone, found $phone_count" >&2
        return 1
    fi
    if [ "$emu_count" = "0" ]; then
        echo "$WSS3_DEV_ERR_ZERO_EMU" >&2
        echo "resolve: no online emulator (\`adb devices\` had $emu_count/1)" >&2
        return 1
    fi
    if [ "$emu_count" -gt 1 ]; then
        echo "$WSS3_DEV_ERR_MULTIPLE_EMU" >&2
        echo "resolve: expected exactly one emulator, found $emu_count" >&2
        return 1
    fi

    local phone_s emu_s
    phone_s=$(printf '%s' "$phones" | awk 'NF>0 {print; exit}')
    emu_s=$(printf '%s' "$emus"     | awk 'NF>0 {print; exit}')
    printf 'phone=%s\n' "$phone_s"
    printf 'emu=%s\n'   "$emu_s"
    return 0
}

# _wss3_resolve_serials_to <phone-var-name> <emu-var-name>
#   Runner-facing helper. Uses `eval` to store the resolved serials
#   into the caller-named variables so the runner's phase code can
#   stay a plain `local x y; _wss3_resolve_serials_to x y || return 1`.
_wss3_resolve_serials_to() {
    local __out __resolve_rc=0
    __out=$(wss3_devices_resolve) || __resolve_rc=$?
    if [ "$__resolve_rc" != "0" ]; then
        return "$__resolve_rc"
    fi
    local __ph __em
    __ph=$(printf '%s\n' "$__out" | awk -F= '$1=="phone" { print $2; exit }')
    __em=$(printf '%s\n' "$__out" | awk -F= '$1=="emu"   { print $2; exit }')
    if [ -z "$__ph" ] || [ -z "$__em" ]; then
        echo "resolve: internal error — resolver stdout malformed: $__out" >&2
        return 1
    fi
    eval "$1"='"$__ph"'
    eval "$2"='"$__em"'
    return 0
}
