#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — portable helpers.
#
# §12 Round-1 audit P0-1/P1: macOS BSD `date` and GNU `sha256sum`
# both differ from Linux; centralise the portability workarounds.

# now_ms: current wall clock in milliseconds since epoch (integer).
# Uses Python because BSD `date` on macOS returns literal `%N`.
now_ms() {
    python3 -c 'import time; print(time.time_ns() // 1_000_000)'
}

# sha256_file <path>: prints just the 64-char hex digest of <path>.
sha256_file() {
    local f="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$f" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$f" | awk '{print $1}'
    else
        echo "no SHA-256 tool available (need sha256sum or shasum)" >&2
        return 1
    fi
}

# count_matches <pattern> <file...>: prints ONE integer counting
# total matching lines across all files. Works around GNU `grep -c`'s
# per-file-plus-newline output shape.
count_matches() {
    local pattern="$1"; shift
    if [ $# -eq 0 ]; then echo 0; return; fi
    grep -h -c "$pattern" "$@" 2>/dev/null | awk '{s+=$1} END{print s+0}'
}

# extract_field <field-name> <line>: prints the value of a
# `key=value` field from a WSS_DIAG line, or empty.
extract_field() {
    local key="$1" line="$2"
    printf '%s\n' "$line" | grep -oE "$key=[^ ]+" | tail -1 | cut -d= -f2-
}
