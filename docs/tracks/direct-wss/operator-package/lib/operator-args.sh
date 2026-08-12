#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-2 operator-parameterized preflight helper. Parses
# `--operator <LABEL>` + `--expected-operator-numeric <NNNNN>`
# from the caller's positional args and validates them against
# fixed whitelists. Fails closed on missing/unknown/malformed
# inputs so an operator cannot accidentally run a Yota-labelled
# matrix while the phone's default-data SIM is Tele2 (or vice
# versa).
#
# Usage (from preflight.sh):
#   # shellcheck source=operator-args.sh
#   source "$LIB/operator-args.sh"
#   parse_operator_args "$@" || exit 1
#   # Now these globals are set:
#   #   OPERATOR_LABEL          — YOTA | TELE2 (upper case)
#   #   OPERATOR_LABEL_LOWER    — yota | tele2 (for paths / run_ids)
#   #   EXPECTED_OPERATOR_NUMERIC — 5-6 digit string
#
# All *validate_* helpers are pure functions (no side-effects on
# global state) so `tests/test_shell.sh` can drive them directly.

OPERATOR_LABEL_WHITELIST=("YOTA" "TELE2")

# validate_operator_label <label>
#   Exit 0 if <label> is one of the whitelist entries,
#   1 otherwise.
validate_operator_label() {
    local label="$1"
    local w
    for w in "${OPERATOR_LABEL_WHITELIST[@]}"; do
        if [ "$label" = "$w" ]; then
            return 0
        fi
    done
    return 1
}

# validate_operator_numeric <str>
#   Exit 0 if <str> is a 5-6 digit ASCII string (matches the
#   Android SIM MCC+MNC form), 1 otherwise.
validate_operator_numeric() {
    local n="$1"
    case "$n" in
        ''|*[!0-9]*) return 1 ;;
    esac
    local len="${#n}"
    if [ "$len" -lt 5 ] || [ "$len" -gt 6 ]; then
        return 1
    fi
    return 0
}

# lowercase_label <label>  — prints lowercase ASCII form.
lowercase_label() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

# parse_operator_args <argv>
#   Sets OPERATOR_LABEL, OPERATOR_LABEL_LOWER,
#   EXPECTED_OPERATOR_NUMERIC on success; prints error + returns 1
#   on any missing/unknown/malformed input.
parse_operator_args() {
    OPERATOR_LABEL=""
    EXPECTED_OPERATOR_NUMERIC=""
    OPERATOR_LABEL_LOWER=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --operator)
                OPERATOR_LABEL="${2:-}"
                shift 2 || true
                ;;
            --expected-operator-numeric)
                EXPECTED_OPERATOR_NUMERIC="${2:-}"
                shift 2 || true
                ;;
            --)
                shift
                break
                ;;
            *)
                echo "operator-args: unknown flag: $1" >&2
                return 1
                ;;
        esac
    done
    if [ -z "$OPERATOR_LABEL" ]; then
        echo "operator-args: --operator required (whitelist: ${OPERATOR_LABEL_WHITELIST[*]})" >&2
        return 1
    fi
    if ! validate_operator_label "$OPERATOR_LABEL"; then
        echo "operator-args: --operator '$OPERATOR_LABEL' not in whitelist (${OPERATOR_LABEL_WHITELIST[*]})" >&2
        return 1
    fi
    if [ -z "$EXPECTED_OPERATOR_NUMERIC" ]; then
        echo "operator-args: --expected-operator-numeric required (5-6 digit MCC+MNC)" >&2
        return 1
    fi
    if ! validate_operator_numeric "$EXPECTED_OPERATOR_NUMERIC"; then
        echo "operator-args: --expected-operator-numeric '$EXPECTED_OPERATOR_NUMERIC' not a 5-6 digit string" >&2
        return 1
    fi
    OPERATOR_LABEL_LOWER=$(lowercase_label "$OPERATOR_LABEL")
    return 0
}
