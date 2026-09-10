#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — CLI argument parsing.
#
# Extends the accepted WSS-2 operator-args discipline (--operator,
# --expected-operator-numeric) with the three WSS-3 dimensions:
#   --phone-vpn      off | on
#   --host-vpn       off | on
#   --host-vpn-service "<macOS Network Configuration service>"
#     (mandatory for every profile regardless of --host-vpn value —
#      per REDLINE-5 blocker 2)
#
# All *validate_* helpers are pure so tests can drive them directly.
# See docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md §3.1.

: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
    # shellcheck source=portable.sh
    source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
    PORTABLE_SH_LOADED=1
fi

# Audit ROUND-30.6: the CLI tunnel guard calls the single runtime-state
# authority instead of keeping its own copy of the pattern, so this
# module needs that library loaded. Same conditional-source shape as
# wss3-cleanup.sh; wss3-runtime-state.sh self-guards against being
# sourced twice.
: "${WSS3_RUNTIME_STATE_SH_LOADED:=0}"
if [ "$WSS3_RUNTIME_STATE_SH_LOADED" != "1" ]; then
    # shellcheck source=wss3-runtime-state.sh
    source "$(dirname "${BASH_SOURCE[0]}")/wss3-runtime-state.sh"
    WSS3_RUNTIME_STATE_SH_LOADED=1
fi

# validate_phone_vpn <value> — off | on. Case-sensitive.
wss3_validate_phone_vpn() {
    case "${1:-}" in off|on) return 0 ;; *) return 1 ;; esac
}
wss3_validate_host_vpn() {
    case "${1:-}" in off|on) return 0 ;; *) return 1 ;; esac
}

# validate_host_vpn_service <value>
#   Non-empty; no whitespace; ASCII-printable only. Guards against
#   accidentally passing a shell-metachar string into scutil.
#
#   Audit ROUND-30.7: delegates to the one runtime-record authority.
#   The old body had two defects at once. It used `grep -P`, which BSD
#   grep does not support, so on macOS the match silently failed and
#   EVERY value passed — including framing bytes — while GNU/MSYS
#   rejected the same input: the CLI behaved differently on the two
#   platforms this tooling runs on. And its `[^\x20-\x7E]` filter
#   contradicted the documented policy by rejecting the non-ASCII
#   characters an ordinary macOS service name may contain. The
#   authority applies one platform-independent rule: valid UTF-8,
#   bounded, no framing bytes, no leading `-`.
wss3_validate_host_vpn_service() {
    wss3_validate_service_value "${1:-}"
}

# wss3_profile_id_from_flags <carrier-lower> <phone-vpn> <host-vpn>
#   Prints the canonical profile-id string used everywhere (evidence
#   dir prefix, .runtime/wss3/<profile-id>/, matrix rows).
wss3_profile_id_from_flags() {
    local carrier="$1" phone="$2" host="$3"
    printf '%s-phone-%s-host-%s' "$carrier" "$phone" "$host"
}

# wss3_confirmation_string <CARRIER> <phone> <host>
#   Prints the deterministic operator-typed confirmation string per
#   §7 R11.
wss3_confirmation_string() {
    local carrier_upper="$1" phone="$2" host="$3"
    printf 'CONFIRM-%s-PHONE-%s-HOST-%s' \
        "$carrier_upper" \
        "$(printf '%s' "$phone" | tr '[:lower:]' '[:upper:]')" \
        "$(printf '%s' "$host" | tr '[:lower:]' '[:upper:]')"
}

# wss3_expected_operator_numeric_for <CARRIER>
#   Prints the canonical carrier-numeric pin per §2.3. Returns 1 with
#   an error on unknown carrier.
wss3_expected_operator_numeric_for() {
    local c="${1:-}"
    case "$c" in
        YOTA)  printf '25011' ;;
        TELE2) printf '25020' ;;
        *) echo "wss3-args: unknown carrier '$c' (whitelist: YOTA TELE2)" >&2; return 1 ;;
    esac
}

# wss3_parse_args <argv>
#   Sets globals:
#     WSS3_OPERATOR                   YOTA | TELE2
#     WSS3_OPERATOR_LOWER             yota | tele2
#     WSS3_EXPECTED_OPERATOR_NUMERIC  digits
#     WSS3_PHONE_VPN                  off | on
#     WSS3_HOST_VPN                   off | on
#     WSS3_HOST_VPN_SERVICE           string (raw operator input)
#     WSS3_PROFILE_ID                 <carrier-lower>-phone-<off|on>-host-<off|on>
#     WSS3_CONFIRM_STRING             CONFIRM-<CARRIER>-PHONE-<...>-HOST-<...>
#
#   Returns 0 on success, 1 on any missing/malformed/unknown input.
wss3_parse_args() {
    WSS3_OPERATOR=""; WSS3_OPERATOR_LOWER=""; WSS3_EXPECTED_OPERATOR_NUMERIC=""
    WSS3_PHONE_VPN=""; WSS3_HOST_VPN=""; WSS3_HOST_VPN_SERVICE=""
    WSS3_HOST_VPN_TUNNEL=""
    WSS3_PROFILE_ID=""; WSS3_CONFIRM_STRING=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --operator)
                [ $# -lt 2 ] && { echo "wss3-args: --operator requires a value" >&2; return 1; }
                WSS3_OPERATOR="$2"; shift 2 ;;
            --expected-operator-numeric)
                [ $# -lt 2 ] && { echo "wss3-args: --expected-operator-numeric requires a value" >&2; return 1; }
                WSS3_EXPECTED_OPERATOR_NUMERIC="$2"; shift 2 ;;
            --phone-vpn)
                [ $# -lt 2 ] && { echo "wss3-args: --phone-vpn requires off|on" >&2; return 1; }
                WSS3_PHONE_VPN="$2"; shift 2 ;;
            --host-vpn)
                [ $# -lt 2 ] && { echo "wss3-args: --host-vpn requires off|on" >&2; return 1; }
                WSS3_HOST_VPN="$2"; shift 2 ;;
            --host-vpn-service)
                [ $# -lt 2 ] && { echo "wss3-args: --host-vpn-service requires a value" >&2; return 1; }
                WSS3_HOST_VPN_SERVICE="$2"; shift 2 ;;
            --host-vpn-tunnel)
                # Audit ROUND-30: OPTIONAL declared Network Extension /
                # TUN interface. Only consulted when the legacy
                # `scutil --nc status` path cannot attest. Never
                # persisted to evidence — §5 denylist bans raw utunN.
                [ $# -lt 2 ] && { echo "wss3-args: --host-vpn-tunnel requires a value" >&2; return 1; }
                WSS3_HOST_VPN_TUNNEL="$2"; shift 2 ;;
            --) shift; break ;;
            *) echo "wss3-args: unknown flag: $1" >&2; return 1 ;;
        esac
    done
    [ -n "$WSS3_OPERATOR" ] || { echo "wss3-args: --operator required (YOTA | TELE2)" >&2; return 1; }
    case "$WSS3_OPERATOR" in
        YOTA|TELE2) : ;;
        *) echo "wss3-args: --operator '$WSS3_OPERATOR' not in whitelist (YOTA TELE2)" >&2; return 1 ;;
    esac
    [ -n "$WSS3_EXPECTED_OPERATOR_NUMERIC" ] || { echo "wss3-args: --expected-operator-numeric required" >&2; return 1; }
    printf '%s' "$WSS3_EXPECTED_OPERATOR_NUMERIC" | grep -qE '^[0-9]{5,6}$' \
        || { echo "wss3-args: --expected-operator-numeric must be 5-6 digits" >&2; return 1; }
    local canonical
    canonical=$(wss3_expected_operator_numeric_for "$WSS3_OPERATOR") || return 1
    if [ "$WSS3_EXPECTED_OPERATOR_NUMERIC" != "$canonical" ]; then
        echo "wss3-args: --expected-operator-numeric $WSS3_EXPECTED_OPERATOR_NUMERIC disagrees with " \
             "canonical $canonical for $WSS3_OPERATOR" >&2
        return 1
    fi
    wss3_validate_phone_vpn "$WSS3_PHONE_VPN" \
        || { echo "wss3-args: --phone-vpn '$WSS3_PHONE_VPN' invalid (off|on)" >&2; return 1; }
    wss3_validate_host_vpn "$WSS3_HOST_VPN" \
        || { echo "wss3-args: --host-vpn '$WSS3_HOST_VPN' invalid (off|on)" >&2; return 1; }
    wss3_validate_host_vpn_service "$WSS3_HOST_VPN_SERVICE" \
        || { echo "wss3-args: --host-vpn-service rejected (must be non-empty valid UTF-8, no control characters, no leading '-')" >&2; return 1; }
    # Audit ROUND-30: when declared, the tunnel must have a tunnel shape
    # up front. Rejecting `en0` at parse time means an operator cannot
    # accidentally declare the physical underlay as their VPN and have
    # an untunnelled host attest as ON.
    #
    # Audit ROUND-30.6: this used to carry its OWN copy of the pattern,
    # which is how a second, drifting definition of "tunnel shape" came
    # to exist. There is now one authority —
    # `wss3_validate_tunnel_iface` in wss3-runtime-state.sh — and the
    # CLI guard calls it, so a whole-value check here and there can
    # never disagree again.
    if [ -n "$WSS3_HOST_VPN_TUNNEL" ]; then
        wss3_validate_tunnel_iface "$WSS3_HOST_VPN_TUNNEL" \
            || { echo "wss3-args: --host-vpn-tunnel '$WSS3_HOST_VPN_TUNNEL' is not a tunnel interface (utunN|tunN|ipsecN)" >&2; return 1; }
    fi

    WSS3_OPERATOR_LOWER=$(printf '%s' "$WSS3_OPERATOR" | tr '[:upper:]' '[:lower:]')
    WSS3_PROFILE_ID=$(wss3_profile_id_from_flags "$WSS3_OPERATOR_LOWER" "$WSS3_PHONE_VPN" "$WSS3_HOST_VPN")
    WSS3_CONFIRM_STRING=$(wss3_confirmation_string "$WSS3_OPERATOR" "$WSS3_PHONE_VPN" "$WSS3_HOST_VPN")
    return 0
}
