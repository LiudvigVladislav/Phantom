#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — Mac host VPN state probing.
#
# Contract: docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md
# §7 R7 + §4.6 checkpoint table + §8 failure matrix.
#
#   ON case  : `scutil --nc status "<svc>"` == Connected
#              AND `route -n get <relay_host>` interface is OWNED by <svc>.
#   OFF case : `scutil --nc status "<svc>"` == Disconnected
#              AND `route -n get <relay_host>` interface is NOT owned by
#              ANY Connected Network Configuration VPN service (REDLINE-5
#              blocker 2 any-other-VPN reject).
#   Absent   : if <svc> is not in `scutil --nc list`, fail-closed with
#              host_vpn_unverifiable BEFORE arm — never guess.
#
# All helpers are pure enough to be tested against synthetic fixtures.
# The `SCUTIL` and `ROUTE` env vars let tests inject fake commands
# (`SCUTIL=./fake-scutil.sh`).

: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
    # shellcheck source=portable.sh
    source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
    PORTABLE_SH_LOADED=1
fi

: "${SCUTIL:=scutil}"
: "${ROUTE:=route}"
# Audit ROUND-30: Network Extension / TUN attestation needs to read
# interface state. Injectable for fixtures, same discipline as
# SCUTIL/ROUTE.
: "${IFCONFIG:=ifconfig}"

readonly WSS3_HOSTVPN_ERR_UNVERIFIABLE="host_vpn_unverifiable"
readonly WSS3_HOSTVPN_ERR_OTHER_SERVICE="host_vpn_other_service_active"
readonly WSS3_HOSTVPN_ERR_STATE_MISMATCH="host_vpn_state_mismatch"
readonly WSS3_HOSTVPN_ERR_ROUTE_MISMATCH="host_vpn_route_mismatch"

# ── Audit ROUND-30.3 P0-4: ONE tunnel-shape predicate ──────────────
#
# R30.2 carried two independent notions of "tunnel-shaped": the parser
# regex accepted `utun|tun|tap|ppp|ipsec`, while the host-OFF guard's
# `case` accepted only `utun|tun|ipsec`. An active relay/default route
# through `ppp0` (and `tap0` by the same source-level gap) therefore
# passed as `host_vpn=off`. Every shape decision in this file now reads
# this single expression, so the two can never drift apart again.
#
# The predicate is deliberately BROADER than
# `wss3_validate_tunnel_iface` in lib/wss3-runtime-state.sh, which
# governs what an operator may DECLARE and persist (`utun|tun|ipsec`
# only). Broadening recognition tightens OFF without widening ON: the
# ON path can only ever see an interface that survived the narrower
# declaration syntax.
# Audit ROUND-30.7: this is no longer a second definition. The one
# authority owns the pattern; this module reads it, and the declaration
# rule and the host-OFF recognition rule are now provably the same
# predicate rather than two lists that agreed by inspection.
: "${WSS3_RUNTIME_STATE_SH_SELF_LOADED:=0}"
if [ "$WSS3_RUNTIME_STATE_SH_SELF_LOADED" != "1" ]; then
    # shellcheck source=wss3-runtime-state.sh
    source "$(dirname "${BASH_SOURCE[0]}")/wss3-runtime-state.sh"
fi
readonly WSS3_HOSTVPN_TUNNEL_IFACE_RE="$WSS3_RECORD_TUNNEL_RE"

# ── Audit ROUND-30.3 P0-3: scutil command authority ────────────────
#
# `scutil --nc list`, `--nc status` and `--nc show` are three separate
# authorities. R30.2 piped `status`/`show` straight into `awk`, so the
# command's own exit status was discarded and a FAILED invocation was
# indistinguishable from a service that legitimately reports nothing —
# the declared tunnel was then attested with rc=0. Every scutil call
# below goes through this wrapper and every caller keeps the status.
_wss3_hostvpn_scutil_nc() {
    "$SCUTIL" --nc "$@" 2>/dev/null
}

# wss3_hostvpn_list_all_services
#   Prints one service name per line, from `scutil --nc list`.
#   Robust against the various columns scutil emits.
#   Returns 2 — never an empty success — when `--nc list` itself fails.
wss3_hostvpn_list_all_services() {
    # `scutil --nc list` format on macOS 14+:
    #   * (Disconnected) 66DAC79C-... IPSec       "MyServiceName"    [IPSec]
    # We extract everything inside the FIRST double-quoted string of
    # each line — that's the user-facing service name.
    local out rc=0
    out=$(_wss3_hostvpn_scutil_nc list) || rc=$?
    if [ "$rc" != "0" ]; then
        return 2
    fi
    printf '%s\n' "$out" | awk -F'"' 'NF>=2 { print $2 }'
    return 0
}

# wss3_hostvpn_service_exists <service>
#   Exit 0 if the service is listed by scutil --nc list, 1 if the list
#   is readable and does not contain it, 2 if the list command failed.
#   Every caller treats any non-zero as fail-closed UNVERIFIABLE; the
#   distinct code keeps "absent" and "unreadable" separable.
wss3_hostvpn_service_exists() {
    local svc="$1" list rc=0
    list=$(wss3_hostvpn_list_all_services) || rc=$?
    if [ "$rc" != "0" ]; then
        return 2
    fi
    printf '%s\n' "$list" | grep -Fx -- "$svc" >/dev/null
}

# wss3_hostvpn_service_status <service>
#   Prints one of: Connected | Disconnected | Connecting | Disconnecting
#   | Invalid | UnknownService.
#   Returns 2 and prints NOTHING when `--nc list` or `--nc status`
#   failed (audit ROUND-30.3 P0-3). An empty string must never be
#   compared against "Connected"/"Disconnected" as if the command had
#   answered.
wss3_hostvpn_service_status() {
    local svc="$1"
    local exists_rc=0
    wss3_hostvpn_service_exists "$svc" || exists_rc=$?
    if [ "$exists_rc" = "2" ]; then
        return 2
    fi
    if [ "$exists_rc" != "0" ]; then
        printf 'UnknownService'
        return 0
    fi
    # `scutil --nc status "<svc>"` on macOS prints a multi-line block;
    # the first line is `Connected` / `Disconnected` / etc.
    local out rc=0
    out=$(_wss3_hostvpn_scutil_nc status "$svc") || rc=$?
    if [ "$rc" != "0" ]; then
        return 2
    fi
    printf '%s' "$out" | head -1 | awk '{print $1}'
    return 0
}

# wss3_hostvpn_service_interface <service>
#   Prints exactly ONE tunnel interface owned by <service>, or fails
#   closed. Contract:
#     - Combine BOTH `scutil --nc show "<svc>"` (legacy IPSec /
#       IKEv2 form emitting `IfName: utunN`) AND
#       `scutil --nc status "<svc>"` (Network Extension form emitting
#       zero or more `InterfaceName : <iface>` lines, some of which
#       are the underlay `en0`).
#     - Accept BOTH key spellings `IfName` and `InterfaceName`, with
#       arbitrary whitespace around and after the colon.
#     - Normalize whitespace and deduplicate candidates via `sort -u`.
#     - Keep only candidates whose name matches a macOS tunnel form:
#       utunN, tunN, tapN, pppN, ipsecN. Any other candidate (e.g.
#       the underlay `en0`) is filtered out.
#     - Emit exactly one distinct tunnel candidate on stdout and
#       return 0. If zero OR more than one distinct tunnel candidate
#       remain, emit nothing and return 1 — callers MUST treat this
#       as host_vpn_unverifiable and never pick an arbitrary one.
#
#   R25 physical-finding rationale: NetworkExtension VPN services
#   (e.g. "Happ") do not populate `--nc show` and their `--nc status`
#   block includes the underlay before the tunnel. The pre-R25 parser
#   read only `--nc show IfName`, returned empty, and the caller
#   short-circuited on host_vpn_route_mismatch even when the tunnel
#   was in fact up.
wss3_hostvpn_service_interface() {
    local svc="$1"
    local candidates n rc=0
    candidates=$(_wss3_hostvpn_service_iface_candidates "$svc") || rc=$?
    # Audit ROUND-30.3 P0-3: a failed `status`/`show` is not "zero
    # candidates", it is no answer at all. Callers map non-zero here to
    # host_vpn_unverifiable, which is the correct diagnosis for both.
    if [ "$rc" != "0" ]; then
        return 1
    fi
    n=$(printf '%s' "$candidates" | awk 'NF>0 {n++} END {print n+0}')
    if [ "$n" = "1" ]; then
        printf '%s' "$candidates" | awk 'NF>0 {print; exit}'
        return 0
    fi
    return 1
}

# _wss3_hostvpn_service_iface_candidates <service>
#   The single parser behind both `wss3_hostvpn_service_interface` and
#   `wss3_hostvpn_service_interfaces_all`. Prints every distinct
#   tunnel-shaped interface the SELECTED service reports.
#
#   Audit ROUND-30.3 P0-3: `--nc status` and `--nc show` are captured
#   into separate variables with separate exit statuses BEFORE any
#   parsing. If either invocation failed, this returns 2 and the caller
#   must fail closed — the previous `{ scutil; scutil; } | awk … || true`
#   pipeline laundered both failures into a benign empty result.
_wss3_hostvpn_service_iface_candidates() {
    local svc="$1"
    local status_out show_out status_rc=0 show_rc=0
    status_out=$(_wss3_hostvpn_scutil_nc status "$svc") || status_rc=$?
    show_out=$(_wss3_hostvpn_scutil_nc show "$svc") || show_rc=$?
    if [ "$status_rc" != "0" ] || [ "$show_rc" != "0" ]; then
        return 2
    fi
    printf '%s\n%s\n' "$status_out" "$show_out" | awk '
        /^[[:space:]]*IfName[[:space:]]*:/ {
            sub(/^[[:space:]]*IfName[[:space:]]*:[[:space:]]*/, "")
            gsub(/[[:space:]]+$/, "")
            if (length($0) > 0) print $0
        }
        /^[[:space:]]*InterfaceName[[:space:]]*:/ {
            sub(/^[[:space:]]*InterfaceName[[:space:]]*:[[:space:]]*/, "")
            gsub(/[[:space:]]+$/, "")
            if (length($0) > 0) print $0
        }
    ' | sort -u | grep -E "$WSS3_HOSTVPN_TUNNEL_IFACE_RE" || true
    return 0
}

# wss3_hostvpn_route_interface <host>
#   Prints the interface the route to <host> currently exits from.
#   Uses `route -n get <host>` (`interface: en0` line). Empty on
#   error.
wss3_hostvpn_route_interface() {
    local host="$1"
    "$ROUTE" -n get "$host" 2>/dev/null \
        | awk -F': *' '/^[[:space:]]*interface:/ { print $2; exit }'
}

# wss3_hostvpn_all_connected_service_interfaces
#   Prints one tunnel interface per line — the unique tunnel owned by
#   every currently-Connected Network Configuration VPN service. Used
#   by the OFF-case to enumerate the set of interfaces that must NOT
#   own the relay route (REDLINE-5 blocker 2 any-other-VPN reject).
#
#   R25 requirement 9: if ANY Connected service's tunnel cannot be
#   uniquely resolved (zero or >1 candidates after underlay filtering),
#   this function returns non-zero. The OFF-case caller must then
#   fail closed as host_vpn_unverifiable — never silently skip such
#   a service, because that would let a foreign VPN carry the
#   relay traffic while the check reports GREEN.
wss3_hostvpn_all_connected_service_interfaces() {
    local svc st st_rc iface iface_rc list list_rc=0
    # Audit ROUND-30.3 P0-3: an unreadable service list is not an empty
    # service list. Fail closed before the loop.
    list=$(wss3_hostvpn_list_all_services) || list_rc=$?
    if [ "$list_rc" != "0" ]; then
        return 2
    fi
    while IFS= read -r svc; do
        [ -n "$svc" ] || continue
        st_rc=0
        st=$(wss3_hostvpn_service_status "$svc") || st_rc=$?
        if [ "$st_rc" != "0" ]; then
            # The status command failed for SOME service on this host;
            # we cannot enumerate who owns what. Never skip past it.
            return 2
        fi
        if [ "$st" = "Connected" ]; then
            iface_rc=0
            iface=$(wss3_hostvpn_service_interface "$svc") || iface_rc=$?
            if [ "$iface_rc" != "0" ] || [ -z "$iface" ]; then
                return 2
            fi
            printf '%s\n' "$iface"
        fi
    done <<EOF
$list
EOF
    return 0
}

# ── Audit ROUND-30: Network Extension / TUN attestation ───────────
#
# Happ (and any modern VLESS/Xray client) runs as a Network Extension
# packet tunnel. macOS routes default and relay traffic through its
# `utunN`, but the legacy Network Configuration entry still reports
# `Disconnected` — so `wss3_hostvpn_check_on` returned
# `host_vpn_state_mismatch` on a host that was demonstrably tunnelled.
# That is an instrumentation false-negative, not an operator error and
# not a Direct WSS product failure.
#
# The repair adds a SECOND attestation path. It is deliberately NOT
# "if some utun carries the route, call it a VPN" — that would attest
# any tunnel from any application and hollow out §7 R7. It requires an
# operator-DECLARED tunnel interface and then proves that tunnel is the
# one actually carrying traffic.

# wss3_hostvpn_is_tunnel_iface <iface>
#   Exit 0 iff <iface> has the shape of a tunnel device. A physical
#   underlay (en0, en1, bridge0 …) can never be a declared VPN tunnel;
#   accepting one would let an untunnelled host attest as ON.
#
#   Audit ROUND-30.3 P0-4: this reads the single shared predicate
#   `WSS3_HOSTVPN_TUNNEL_IFACE_RE`. The R30.2 `case` recognised only
#   `utun|tun|ipsec`, so an active `ppp0`/`tap0` relay or default route
#   sailed through the host-OFF guard. The regex is also stricter than
#   the old globs — `utun0x` is not an interface name — which can only
#   narrow what attests as ON.
wss3_hostvpn_is_tunnel_iface() {
    local iface="${1:-}"
    [ -n "$iface" ] || return 1
    printf '%s' "$iface" | grep -qE "$WSS3_HOSTVPN_TUNNEL_IFACE_RE"
}

# wss3_hostvpn_iface_is_active <iface>
#   Exit 0 iff the interface exists, is UP+RUNNING and carries an inet
#   address. A `utunN` that is merely present (created and torn down,
#   or held by a dormant profile) must not attest.
wss3_hostvpn_iface_is_active() {
    local iface="$1" out
    [ -n "$iface" ] || return 1
    out=$("$IFCONFIG" "$iface" 2>/dev/null) || return 1
    [ -n "$out" ] || return 1
    printf '%s' "$out" | grep -q "UP" || return 1
    printf '%s' "$out" | grep -q "RUNNING" || return 1
    printf '%s' "$out" | grep -qE '^[[:space:]]*inet[[:space:]]' || return 1
    return 0
}

# wss3_hostvpn_service_interfaces_all <service>
#   Audit ROUND-30.1 P0-3: every tunnel-shaped interface the service
#   reports, without the "exactly one" requirement of
#   `wss3_hostvpn_service_interface`. Used to BIND a declared tunnel
#   to the declared service when scutil gives us anything to bind to.
#   Audit ROUND-30.3 P0-3: returns 2 when the selected service's
#   `status` or `show` command failed, so the caller can tell "reports
#   nothing" (the Happ case, benign) from "did not answer" (fail
#   closed).
wss3_hostvpn_service_interfaces_all() {
    _wss3_hostvpn_service_iface_candidates "$1"
}

# wss3_hostvpn_check_on_tunnel <service> <declared-iface> <relay-host>
#   Fail-closed Network Extension / TUN attestation. Exit 0 ONLY when
#   ALL of the following hold:
#
#     1. <declared-iface> has a tunnel shape (never a physical NIC);
#     2. it is UP + RUNNING with an inet address;
#     3. the RELAY route exits through it;
#     4. the DEFAULT route exits through it — a split tunnel that
#        happens to carry the relay today is not the full-tunnel state
#        the matrix profile declares;
#     5. no OTHER Connected Network Configuration service owns the
#        relay-route interface (same any-other-VPN reject as the OFF
#        case, REDLINE-5 blocker 2).
#
#   The declared interface name is operator input and is NEVER
#   persisted to evidence — §5 denylist bans raw `utunN` strings. It
#   lives in `.runtime` beside `host-vpn-service`.
wss3_hostvpn_check_on_tunnel() {
    local svc="$1" iface="$2" relay="$3"
    # ── Audit ROUND-30.1 P0-3: bind to the service WHERE EVIDENCE EXISTS
    # A live full tunnel is not by itself proof of WHICH application
    # owns it. When the declared service reports any interface at all,
    # the declared tunnel MUST be one of them — that rejects another
    # Network Extension masquerading under this service's name. When
    # the service reports nothing (the Happ case: invisible to
    # `scutil --nc`), no trustworthy binding exists, so this path
    # attests an OPERATOR-DECLARED full tunnel and nothing more. The
    # contract and the operator docs say exactly that; no provider
    # identity is claimed or recorded anywhere.
    local svc_ifaces ifaces_rc=0
    svc_ifaces=$(wss3_hostvpn_service_interfaces_all "$svc") || ifaces_rc=$?
    # Audit ROUND-30.3 P0-3: R30.2 read a FAILED `status`/`show` as the
    # benign "service reports nothing" case and attested the declared
    # tunnel with rc=0. A selected service that cannot be interrogated
    # is unverifiable, full stop.
    if [ "$ifaces_rc" != "0" ]; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    if [ -n "$svc_ifaces" ]; then
        if ! printf '%s
' "$svc_ifaces" | grep -qx -- "$iface"; then
            echo "$WSS3_HOSTVPN_ERR_OTHER_SERVICE" >&2
            return 1
        fi
    fi
    if ! wss3_hostvpn_is_tunnel_iface "$iface"; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    if ! wss3_hostvpn_iface_is_active "$iface"; then
        echo "$WSS3_HOSTVPN_ERR_STATE_MISMATCH" >&2
        return 1
    fi
    local relay_iface default_iface
    relay_iface=$(wss3_hostvpn_route_interface "$relay")
    if [ -z "$relay_iface" ] || [ "$relay_iface" != "$iface" ]; then
        echo "$WSS3_HOSTVPN_ERR_ROUTE_MISMATCH" >&2
        return 1
    fi
    default_iface=$(wss3_hostvpn_route_interface default)
    if [ -z "$default_iface" ] || [ "$default_iface" != "$iface" ]; then
        echo "$WSS3_HOSTVPN_ERR_ROUTE_MISMATCH" >&2
        return 1
    fi
    # A legacy Connected service must not be the thing carrying the
    # relay — otherwise we would attest the declared tunnel while a
    # different VPN owns the traffic.
    local other_ifaces enum_rc=0 other_iface
    other_ifaces=$(wss3_hostvpn_all_connected_service_interfaces) || enum_rc=$?
    if [ "$enum_rc" != "0" ]; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    while IFS= read -r other_iface; do
        [ -n "$other_iface" ] || continue
        # No exclusion for "it is the declared interface too": if a
        # legacy Connected service OWNS this tunnel then the tunnel is
        # that service's, not the declared app's, and attesting it
        # would be the foreign-VPN-carries-the-relay hole. We only
        # reach this path because the legacy check could not attest
        # the declared service, so any Connected owner here is by
        # definition someone else.
        if [ "$other_iface" = "$relay_iface" ]; then
            echo "$WSS3_HOSTVPN_ERR_OTHER_SERVICE" >&2
            return 1
        fi
    done <<EOF
$other_ifaces
EOF
    return 0
}

# wss3_hostvpn_check_on <service> <relay-host> [declared-tunnel-iface]
#   Exit 0 IFF the service reports Connected AND wss3_hostvpn_service_interface
#   returns exactly one distinct tunnel candidate AND the relay route's exit
#   interface equals that tunnel. Any deviation fails closed and prints
#   an error code (WSS3_HOSTVPN_ERR_*) on stderr:
#     - service absent                             → UNVERIFIABLE
#     - status != Connected                        → STATE_MISMATCH
#     - 0 or >1 tunnel candidates (R25 req 7)      → UNVERIFIABLE
#     - relay route iface != tunnel candidate      → ROUTE_MISMATCH
_wss3_hostvpn_check_on_legacy() {
    local svc="$1" relay="$2"
    if ! wss3_hostvpn_service_exists "$svc"; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    local st st_rc=0
    st=$(wss3_hostvpn_service_status "$svc") || st_rc=$?
    # Audit ROUND-30.3 P0-3: a failed `scutil --nc status` produced an
    # empty string that then "failed" the Connected comparison and was
    # reported as a state mismatch — a positive claim about the host
    # drawn from a command that never answered.
    if [ "$st_rc" != "0" ]; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    if [ "$st" != "Connected" ]; then
        echo "$WSS3_HOSTVPN_ERR_STATE_MISMATCH" >&2
        return 1
    fi
    local svc_iface svc_iface_rc=0 route_iface
    svc_iface=$(wss3_hostvpn_service_interface "$svc") || svc_iface_rc=$?
    if [ "$svc_iface_rc" != "0" ] || [ -z "$svc_iface" ]; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    route_iface=$(wss3_hostvpn_route_interface "$relay")
    if [ -z "$route_iface" ] || [ "$svc_iface" != "$route_iface" ]; then
        echo "$WSS3_HOSTVPN_ERR_ROUTE_MISMATCH" >&2
        return 1
    fi
    return 0
}

# Audit ROUND-30: the legacy `scutil --nc status` path stays FIRST and
# unchanged, so every previously-attesting configuration keeps
# attesting by exactly the same evidence. The Network Extension / TUN
# path is a FALLBACK that requires an operator-declared tunnel: with
# no declaration the behaviour is byte-for-byte what it was, including
# the error code. Nothing is widened by default.
wss3_hostvpn_check_on() {
    local svc="$1" relay="$2" declared_iface="${3:-}"
    local legacy_err=""
    if legacy_err=$(_wss3_hostvpn_check_on_legacy "$svc" "$relay" 2>&1 >/dev/null); then
        return 0
    fi
    if [ -n "$declared_iface" ]; then
        # ── Audit ROUND-30.2 P0-2 ──────────────────────────────────
        # The service-existence contract (§3.1 verification 6, "absent
        # = fail-closed BEFORE arm — never guess") is UNCONDITIONAL. A
        # declared tunnel must never rescue a service that is not
        # there: `wss3_hostvpn_service_interfaces_all` returns empty
        # both for "service exists but reports nothing" and for
        # "scutil failed / no such service", and R30.1 read the second
        # as the first. Re-assert existence explicitly before the
        # fallback may run.
        if ! wss3_hostvpn_service_exists "$svc"; then
            echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
            return 1
        fi
        # The tunnel path prints its own fail-closed code.
        wss3_hostvpn_check_on_tunnel "$svc" "$declared_iface" "$relay" || return 1
        return 0
    fi
    printf '%s
' "$legacy_err" >&2
    return 1
}

# wss3_hostvpn_check_off <service> <relay-host>
#   Exit 0 IFF the service reports Disconnected AND the relay route does not
#   exit via a tunnel owned by ANY OTHER Connected VPN service. Any deviation
#   fails closed:
#     - service absent                                        → UNVERIFIABLE
#     - status != Disconnected                                → STATE_MISMATCH
#     - relay route has no interface                          → ROUTE_MISMATCH
#     - another Connected VPN owns the relay-route interface  → OTHER_SERVICE
#     - another Connected VPN's interface is unresolvable
#       (R25 req 9 — must not silently skip)                  → UNVERIFIABLE
wss3_hostvpn_check_off() {
    local svc="$1" relay="$2"
    if ! wss3_hostvpn_service_exists "$svc"; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    local st st_rc=0
    st=$(wss3_hostvpn_service_status "$svc") || st_rc=$?
    # Audit ROUND-30.3 P0-3: same asymmetry as the ON case — an
    # unanswered status command must not be spent as evidence that the
    # service is not Disconnected.
    if [ "$st_rc" != "0" ]; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    if [ "$st" != "Disconnected" ]; then
        echo "$WSS3_HOSTVPN_ERR_STATE_MISMATCH" >&2
        return 1
    fi
    local route_iface
    route_iface=$(wss3_hostvpn_route_interface "$relay")
    if [ -z "$route_iface" ]; then
        echo "$WSS3_HOSTVPN_ERR_ROUTE_MISMATCH" >&2
        return 1
    fi
    local other_ifaces enum_rc=0
    other_ifaces=$(wss3_hostvpn_all_connected_service_interfaces) || enum_rc=$?
    if [ "$enum_rc" != "0" ]; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    local other_iface
    while IFS= read -r other_iface; do
        [ -n "$other_iface" ] || continue
        if [ "$other_iface" = "$route_iface" ]; then
            echo "$WSS3_HOSTVPN_ERR_OTHER_SERVICE" >&2
            return 1
        fi
    done <<EOF
$other_ifaces
EOF
    # ── Audit ROUND-30.2 P0-3: fail-closed on tunnel-shaped routes ──
    # A Network Extension that `scutil --nc` reports as Disconnected is
    # invisible to every check above, so an active Happ-style tunnel
    # passed as `host_vpn=off`. R30.1 rejected it only when `ifconfig`
    # POSITIVELY said the interface was active — an unreadable or
    # inactive interface fell through and could still return GREEN.
    #
    # Precedence is deliberate: the OTHER_SERVICE diagnosis above is
    # more specific and runs first, so the accepted R25 error taxonomy
    # is unchanged. This guard is the catch-all beneath it.
    #
    # A route we cannot resolve at all is not evidence of anything.
    local def_iface
    def_iface=$(wss3_hostvpn_route_interface default)
    if [ -z "$def_iface" ]; then
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    fi
    local probe
    for probe in "$route_iface" "$def_iface"; do
        [ -n "$probe" ] || continue
        wss3_hostvpn_is_tunnel_iface "$probe" || continue
        # Tunnel-shaped and demonstrably up → this host is not VPN-off.
        if wss3_hostvpn_iface_is_active "$probe"; then
            echo "$WSS3_HOSTVPN_ERR_STATE_MISMATCH" >&2
            return 1
        fi
        # Tunnel-shaped but its state cannot be established (ifconfig
        # unreadable, or a route through a device reported down). That
        # is unverifiable, never evidence of VPN-off.
        echo "$WSS3_HOSTVPN_ERR_UNVERIFIABLE" >&2
        return 1
    done
    return 0
}
