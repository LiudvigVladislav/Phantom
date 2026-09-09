#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — segregated runtime state store.
#
# Contract: docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md §3.6.
#
# `--host-vpn-service` cannot survive `PROFILE.json`/evidence — the raw
# service string leaks VPN provider identity (denylist §5). But
# `preflight`, `smoke`, `full`, `resume` are separate shell invocations
# and each needs the same anchor to run its host-VPN checkpoint. This
# module owns the segregated store at
# `docs/tracks/direct-wss/operator-package/.runtime/wss3/<profile-id>/`,
# strictly outside `evidence/` and outside the handoff tarball, with
# `chmod 0600` on files and `0700` on directories, atomic writes, and
# fail-closed reads. See §3.6 invariants 1-9 for the full contract.
#
# All functions here are pure enough that
# `tests/test_shell.sh` fixtures 46-49-50-52 (§9) can drive them
# against synthetic directories WITHOUT touching real ADB or a real
# Mac. Every function that would mutate the filesystem takes the
# root directory (`--runtime-root`) so tests can inject a `mktemp -d`
# scratch dir.

# Audit ROUND-30.6: self-guard. This module is now pulled in by
# wss3-args.sh as well as directly by the orchestrator and the test
# suite, so it can be sourced twice in one shell — and its `readonly`
# constants would abort the second pass. Return early instead.
if [ "${WSS3_RUNTIME_STATE_SH_SELF_LOADED:-0}" = "1" ]; then
    return 0
fi
WSS3_RUNTIME_STATE_SH_SELF_LOADED=1

# require portable.sh loaded first
: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
    # shellcheck source=portable.sh
    source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
    PORTABLE_SH_LOADED=1
fi

# Default location. Overridable in tests via the first arg of every
# public function that touches disk.
wss3_runtime_default_root() {
    local pkg_root="${WSS3_OPERATOR_PACKAGE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
    printf '%s/.runtime/wss3' "$pkg_root"
}

# ─── canonical error codes (per §3.6 invariant 6 error classification) ───

readonly WSS3_ERR_SERVICE_MISSING="host_vpn_service_missing"
readonly WSS3_ERR_STALE_ARM="host_vpn_service_stale_arm"
readonly WSS3_ERR_MISMATCH="host_vpn_service_mismatch"
readonly WSS3_ERR_MODE_VIOLATION="runtime_state_mode_violation"
readonly WSS3_ERR_STALE_RUNTIME="runtime_state_stale"
# Audit ROUND-30.5 P0: a file whose BYTES are not a single-value record.
readonly WSS3_ERR_RECORD_CORRUPT="runtime_state_record_corrupt"
# ── Audit ROUND-30.7: the taxonomy stops conflating distinct causes ──
# A missing arm-token used to be reported as a missing SERVICE; a
# directory or symlink at a record path used to be reported as a MODE
# violation; and a value that was a clean record but wrong for its kind
# shared a code with both. Each now has its own code. See §3.6 of the
# contract for the canonical table these constants mirror.
readonly WSS3_ERR_ARM_TOKEN_MISSING="runtime_state_arm_token_missing"
readonly WSS3_ERR_TYPE_VIOLATION="runtime_state_record_type_violation"
readonly WSS3_ERR_RECORD_SYNTAX="runtime_state_record_syntax"
# Audit ROUND-30.10: terminal transaction conditions.
readonly WSS3_ERR_TXN_LOCKED="runtime_state_transaction_locked"
readonly WSS3_ERR_ROLLBACK_FAILED="runtime_state_rollback_failed"
# Audit ROUND-30.10 rework: a transaction is bound to ONE root and ONE
# profile. A writer reached while a transaction for a DIFFERENT profile
# is open, or a second `begin` over an open transaction, is a caller
# defect that would silently mutate state no journal covers.
readonly WSS3_ERR_TXN_MISMATCH="runtime_state_transaction_mismatch"
# A commit whose journal or lock could not be verifiably released. The
# write itself landed; the cleanup did not, and a journal holding record
# bytes must never be reported as gone while it is still on disk.
readonly WSS3_ERR_TXN_CLEANUP="runtime_state_transaction_cleanup_failed"

# Every code above is a pre-arm CLI failure with exit code 4. The set is
# closed and mirrors the canonical table in contract §3.6; the count is
# deliberately not restated here, because a stale "five errors" line
# outlived two rounds of additions.
readonly WSS3_EXIT_PREARM_CLI_FAILURE=4

# ── Audit ROUND-30.6: byte-exact single-value records ──────────────
#
# All three files this module owns — `host-vpn-tunnel`, `arm-token` and
# `host-vpn-service` — are ONE value and nothing else. R30.5 validated
# them with `printf '%s' "$v" | grep -qE '^…$'`, and `grep` matches PER
# LINE, so `garbage\nutun4` validated on the strength of its second
# line. `$(cat "$f")` then hid the rest: command substitution strips all
# trailing newlines, so a file holding `utun4\n` read back as a clean
# `utun4` and the writer's "byte-exact" postcondition compared equal.
#
# The class is closed at the byte level. Every decision is taken on the
# file's raw bytes rendered as hex — which represents NUL, CR and LF
# faithfully, unlike any shell variable — and `cat` is used only after
# the bytes are proven free of framing, at which point command
# substitution cannot lose anything.

# ── Audit ROUND-30.7: PRIVACY. A runtime value never reaches output ──
#
# `host-vpn-service` is a VPN service name and `host-vpn-tunnel` is an
# interface the operator declared; §5 bans both from evidence, and a
# diagnostic is no different. R30.6 printed the offending value inline
# and, for the tunnel postcondition, its exact hex — a fully reversible
# disclosure. Failure paths may now emit ONLY a stable error code, the
# record kind, the file path (which names a profile, never a value) and
# this marker. Hashes and fingerprints are equally forbidden: a service
# name or an interface name is low-entropy enough to be recovered from
# one by search.
readonly WSS3_RECORD_REDACTED="[redacted]"

# wss3_record_redacted — the only thing a diagnostic may say about a value.
wss3_record_redacted() { printf '%s' "$WSS3_RECORD_REDACTED"; }

# wss3_hex_of_file <path> — the file's exact bytes as lowercase hex.
#   `-v` keeps `od` from collapsing repeats into `*`.
#
#   INTERNAL USE ONLY. The hex of a record is the record; it must never
#   be printed, logged or packaged. Callers use it to DECIDE, never to
#   REPORT.
wss3_hex_of_file() {
    od -An -v -tx1 < "$1" | tr -d ' \n'
}

# wss3_hex_of_value <value> — the value's exact bytes as lowercase hex.
wss3_hex_of_value() {
    printf '%s' "${1:-}" | od -An -v -tx1 | tr -d ' \n'
}

# wss3_record_bytes_ok <hex>
#   Exit 0 iff <hex> is a non-empty single-value record: at least one
#   byte, and no NUL, CR, LF, other C0 control byte or DEL anywhere —
#   including at the end, so a trailing newline is corruption, not
#   cosmetic.
#
#   Bytes 0x80-0xff are deliberately ALLOWED. `host-vpn-service` holds a
#   macOS service name the operator typed; spaces and non-ASCII
#   characters are ordinary there, and narrowing that would break real
#   names for no security gain. Only unrepresentable framing is refused.
#   No provider name is matched, tested for or special-cased anywhere.
#
#   The hex is split into byte pairs BEFORE matching: a regex over the
#   concatenated string would find "0a" straddling two adjacent bytes
#   (e.g. `70 61` reads as "7061", whose middle two characters are "06")
#   and reject a perfectly good value.
wss3_record_bytes_ok() {
    local hex="${1:-}" b
    [ -n "$hex" ] || return 1
    case "$hex" in *[!0-9a-f]*) return 1 ;; esac
    [ $(( ${#hex} % 2 )) -eq 0 ] || return 1
    for b in $(printf '%s' "$hex" | sed 's/../& /g'); do
        case "$b" in
            0?|1?|7f) return 1 ;;
        esac
    done
    return 0
}

# wss3_value_is_record <value>
#   Exit 0 iff <value> would be a well-formed single-value record. This
#   is the guard every syntax validator applies FIRST, which is what
#   makes their `^…$` patterns whole-value: once the value is known to
#   contain no LF or CR, it is exactly one line and `grep` cannot be
#   satisfied by some other line.
wss3_value_is_record() {
    wss3_record_bytes_ok "$(wss3_hex_of_value "${1:-}")"
}

# wss3_read_record <path>
#   Prints the file's exact content and returns 0 only when those bytes
#   are a well-formed single-value record; emits WSS3_ERR_RECORD_CORRUPT
#   and returns 4 otherwise.
#   Audit ROUND-30.7: takes the record KIND, so the one authority
#   decides, and says NOTHING about the bytes it rejected. R30.6 printed
#   the exact hex here, which for `host-vpn-service` is a reversible
#   disclosure of the VPN service name.
wss3_read_record() {
    local kind="$1" f="$2" hex value
    hex=$(wss3_hex_of_file "$f") || {
        echo "$WSS3_ERR_RECORD_CORRUPT" >&2
        echo "wss3_read_record: $kind record at '$f' is unreadable ($WSS3_RECORD_REDACTED)" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    }
    if ! wss3_record_check_bytes "$hex"; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_read_record: $kind record at '$f' rejected ($WSS3_RECORD_REDACTED)" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    # Proven newline-free above, so this cannot silently drop anything.
    value=$(cat "$f")
    if ! wss3_record_check_syntax "$kind" "$value"; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_read_record: $kind record at '$f' rejected ($WSS3_RECORD_REDACTED)" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    printf '%s' "$value"
    return 0
}

# wss3_file_bytes_equal_value <path> <value>
#   Exit 0 iff the file's bytes are EXACTLY the value's bytes. This is
#   the writer postcondition; `[ "$(cat "$f")" = "$v" ]` cannot serve as
#   one because it silently equates `utun4` with `utun4\n`.
wss3_file_bytes_equal_value() {
    [ "$(wss3_hex_of_file "$1")" = "$(wss3_hex_of_value "$2")" ]
}

# ══════════════════════════════════════════════════════════════════
# Audit ROUND-30.7 — ONE EXECUTABLE AUTHORITY for runtime records
# ══════════════════════════════════════════════════════════════════
#
# Every rule about what a runtime record may contain lives here, as
# code, once. CLI parsers, readers, writers, comparisons and the
# preflight/resume transactions all call into it. Six rounds of this
# audit were spent on defects that existed only because a second,
# slightly different copy of a rule lived somewhere else — a `case`
# glob that forgot `tap`/`ppp`, an args-layer regex that drifted from
# the runtime one, an ASCII filter that contradicted the documented
# policy. There are no local validators left.
#
# Canonical value policy, provider-neutral throughout — no rule here
# names, matches or special-cases any VPN client:
#
#   service    non-empty valid UTF-8; spaces and non-ASCII allowed;
#              no leading "-" (it would be read as a scutil flag)
#   arm-token  exact lowercase UUIDv4
#   tunnel     utunN | tunN | tapN | pppN | ipsecN
#   ALL        bounded length; no NUL, CR, LF, other C0 control or DEL;
#              malformed UTF-8 and oversized values fail closed
readonly WSS3_RECORD_MAX_BYTES=255
readonly WSS3_RECORD_TUNNEL_RE='^(utun|tun|tap|ppp|ipsec)[0-9]+$'
readonly WSS3_RECORD_ARM_TOKEN_RE='^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'

# wss3_record_filename <kind> — the on-disk name for a record kind.
wss3_record_filename() {
    case "${1:-}" in
        service)   printf 'host-vpn-service' ;;
        arm-token) printf 'arm-token' ;;
        tunnel)    printf 'host-vpn-tunnel' ;;
        *)         return 1 ;;
    esac
}

# wss3_record_utf8_ok_hex <hex>
#   Exit 0 iff the bytes decode as UTF-8. The hex travels through argv,
#   never through an interpolated program text.
wss3_record_utf8_ok_hex() {
    python3 -c '
import binascii, sys
try:
    binascii.unhexlify(sys.argv[1]).decode("utf-8")
except Exception:
    sys.exit(1)
' "${1:-}" 2>/dev/null
}

# wss3_record_check_bytes <hex>
#   The BYTE half of the authority, kind-independent: non-empty,
#   bounded, no NUL/CR/LF/C0/DEL, valid UTF-8. Emits nothing; sets
#   WSS3_RECORD_ERR on failure.
wss3_record_check_bytes() {
    local hex="${1:-}"
    WSS3_RECORD_ERR=""
    if ! wss3_record_bytes_ok "$hex"; then
        WSS3_RECORD_ERR="$WSS3_ERR_RECORD_CORRUPT"
        return 1
    fi
    if [ $(( ${#hex} / 2 )) -gt "$WSS3_RECORD_MAX_BYTES" ]; then
        WSS3_RECORD_ERR="$WSS3_ERR_RECORD_CORRUPT"
        return 1
    fi
    if ! wss3_record_utf8_ok_hex "$hex"; then
        WSS3_RECORD_ERR="$WSS3_ERR_RECORD_CORRUPT"
        return 1
    fi
    return 0
}

# wss3_record_check_syntax <kind> <value>
#   The KIND half of the authority. The caller must already have proved
#   the bytes, which is what lets these patterns apply to the whole
#   value.
#
#   The value is never round-tripped through another process to get
#   here. R30.7 first decoded it back out of Python's stdout, whose
#   encoding follows the console code page — on a cp1251 console every
#   non-ASCII service name died with a UnicodeEncodeError and was
#   reported as corrupt. That is the same platform-dependence this
#   round is closing, reintroduced one layer down. The value stays in
#   the shell; only the byte checks look at hex.
wss3_record_check_syntax() {
    local kind="${1:-}" value="${2:-}"
    WSS3_RECORD_ERR=""
    case "$kind" in
        service)
            case "$value" in
                -*) WSS3_RECORD_ERR="$WSS3_ERR_RECORD_SYNTAX"; return 1 ;;
            esac
            ;;
        arm-token)
            printf '%s' "$value" | grep -qE "$WSS3_RECORD_ARM_TOKEN_RE" || {
                WSS3_RECORD_ERR="$WSS3_ERR_RECORD_SYNTAX"; return 1
            }
            ;;
        tunnel)
            printf '%s' "$value" | grep -qE "$WSS3_RECORD_TUNNEL_RE" || {
                WSS3_RECORD_ERR="$WSS3_ERR_RECORD_SYNTAX"; return 1
            }
            ;;
        *)
            WSS3_RECORD_ERR="$WSS3_ERR_RECORD_SYNTAX"
            return 1
            ;;
    esac
    return 0
}

# wss3_record_check_value <kind> <value>
#   THE decision procedure for a value in hand — a CLI argument, a
#   freshly minted token, a declaration about to be written.
wss3_record_check_value() {
    local kind="${1:-}" value="${2:-}"
    wss3_record_check_bytes "$(wss3_hex_of_value "$value")" || return 1
    wss3_record_check_syntax "$kind" "$value" || return 1
    return 0
}

# ─── mint / validate arm_token ───

# wss3_mint_arm_token: prints a fresh lowercase-hex UUIDv4.
# Uses /dev/urandom because macOS BSD doesn't ship a portable uuidgen
# that emits v4 without external tools.
wss3_mint_arm_token() {
    python3 -c '
import os
b = bytearray(os.urandom(16))
b[6] = (b[6] & 0x0F) | 0x40  # version 4
b[8] = (b[8] & 0x3F) | 0x80  # variant 1
print("{:08x}-{:04x}-{:04x}-{:04x}-{:012x}".format(
    int.from_bytes(b[0:4], "big"),
    int.from_bytes(b[4:6], "big"),
    int.from_bytes(b[6:8], "big"),
    int.from_bytes(b[8:10], "big"),
    int.from_bytes(b[10:16], "big"),
))
'
}

# wss3_validate_arm_token <token>
#   Exit 0 if <token> is a lowercase UUIDv4 (version-4 + variant-1
#   bits enforced). 1 otherwise.
#   Audit ROUND-30.6: the record guard runs FIRST, so the pattern below
#   is matched against the WHOLE value. Without it `grep` was satisfied
#   by any single conforming line, and `garbage\n<valid-uuid>` validated
#   — an `arm_token` is what binds a retry to its lineage, so a value
#   that merely CONTAINS a token is not one.
#   Audit ROUND-30.7: a thin call into the one authority.
wss3_validate_arm_token() {
    wss3_record_check_value arm-token "${1:-}"
}

# wss3_validate_service_value <value>
#   The service rule, from the same authority the readers and writers
#   use. `lib/wss3-args.sh` calls this instead of its own filter: that
#   filter used `grep -P`, which BSD grep does not support, so the CLI
#   silently accepted on macOS exactly what it rejected under GNU.
wss3_validate_service_value() {
    wss3_record_check_value service "${1:-}"
}

# ─── directory + file mode enforcement ───

# wss3_ensure_profile_dir <root> <profile-id>
#   Creates `<root>/<profile-id>/` at mode 0700 (and the parent
#   `<root>/` at 0700 if it doesn't exist yet). Idempotent.
#
#   Audit ROUND-30.8 P0: containment FIRST. R30.7 ran `mkdir -p` and
#   `chmod 0700` here and validated the path afterwards, so a symlinked
#   profile directory was chmod'ed through before the rejection — the
#   rejection changed a foreign directory's mode. Nothing below touches
#   the filesystem until every component has been checked, and the
#   directories are created one level at a time with a bare `mkdir` so
#   `mkdir -p` cannot silently traverse something new.
wss3_ensure_profile_dir() {
    local root="$1" pid="$2"
    if [ -z "$root" ] || [ -z "$pid" ]; then
        echo "wss3_ensure_profile_dir: root + profile-id required" >&2
        return 2
    fi
    if ! wss3_assert_store_path "$root" "$pid"; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_ensure_profile_dir: path is not contained" >&2
        return 2
    fi
    local anchor
    anchor=$(wss3_runtime_anchor "$root")
    # `<anchor>/.runtime` then `<anchor>/.runtime/wss3`, one level at a
    # time, each re-checked for a symlink that appeared meanwhile.
    local mid
    mid=$(dirname "$root")
    # Audit ROUND-30.10 closure: creating the container IS a mutation of
    # the store, and it was the one mutation nothing owned. A `begin` that
    # failed after establishing the directories, and a rollback of a
    # first-ever profile, both left an empty runtime profile behind — which
    # the next preflight then refused as stale runtime state. Every level
    # this call creates is recorded, deepest last, so the transaction can
    # take it back.
    local d
    for d in "$mid" "$root" "$root/$pid"; do
        if [ -L "$d" ]; then
            echo "$WSS3_ERR_TYPE_VIOLATION" >&2
            return 2
        fi
        if [ ! -d "$d" ]; then
            mkdir "$d" || return 1
            wss3_txn_container_created "$d"
        fi
        if [ -L "$d" ] || [ ! -d "$d" ]; then
            echo "$WSS3_ERR_TYPE_VIOLATION" >&2
            return 2
        fi
    done
    chmod 0700 "$root" || return 1
    chmod 0700 "$root/$pid" || return 1
    return 0
}

# wss3_check_mode <path> <expected-octal>
#   Exit 0 if <path>'s effective mode equals <expected-octal>. 1 else.
#   Portable across BSD stat (macOS) and GNU stat (Linux).
#
#   Windows/MSYS caveat: NTFS ACLs approximate POSIX mode to 644/755
#   regardless of chmod calls. On Windows we soften the check to
#   "no world/group write" — the security-critical part is that
#   arm-token and host-vpn-service files are NOT world-writable, which
#   NTFS honours via the base ACL. The strict 0600/0700 enforcement
#   lives on the operator's macOS host where the checkpoint runs live.
#   Skipping is toggled by WSS3_SKIP_STRICT_MODE_CHECK=1 (set
#   automatically under MSYS/Cygwin).
wss3_is_msys_like() {
    case "${OSTYPE:-}" in msys*|cygwin*|win32*) return 0 ;; esac
    case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) return 0 ;; esac
    return 1
}
# ── Audit ROUND-30.5 P0: file TYPE is part of the contract ─────────
#
# Every file this module owns is a plain 0600 regular file. R30.4
# checked only the mode, and `[ -f ]` alone answers "no" for a directory
# and "yes" for a symlink to a regular file — so a directory at a
# declaration path read as "nothing declared", and `mv` then moved the
# atomic-write temp file INSIDE it while `chmod` altered the directory
# itself and the writer returned 0. Type is now checked wherever
# presence is.
#
# wss3_is_plain_file <path>
#   Exit 0 only for an existing REGULAR file that is not a symlink.
#   Explicit `if`s, not `&&` lists, so a false test cannot trip a
#   caller's `set -e`.
wss3_is_plain_file() {
    local p="${1:-}"
    if [ -z "$p" ]; then
        return 1
    fi
    if [ -L "$p" ]; then
        return 1
    fi
    if [ -f "$p" ]; then
        return 0
    fi
    return 1
}

# ── Audit ROUND-30.7: FILESYSTEM CONTAINMENT ───────────────────────
#
# The store is a fixed shape: a real 0700 root directory, real 0700
# per-profile directories, and plain 0600 regular files inside them.
# Anything else at any of those paths — a symlink (dangling or not), a
# directory where a record belongs, a FIFO, a device, a socket — is
# corrupted runtime state that this tooling refuses to read, write,
# overwrite or delete. Foreign objects are left exactly as found: the
# only thing that ever gets removed is something this command created.

# ── Audit ROUND-30.8 P0: containment is LEXICAL, and it runs FIRST ──
#
# R30.7 checked containment by resolving the root and the profile
# directory and comparing them. A symlink ABOVE the root resolves
# consistently on both sides of that comparison, so it passed and a
# record was written outside the store with rc=0. And the check ran
# AFTER `wss3_ensure_profile_dir`, which had already `mkdir`ed and
# `chmod`ed through the very path under suspicion.
#
# The fix walks the path COMPONENT BY COMPONENT and refuses a symlink at
# any of them, before the first `mkdir`, `chmod`, write, rename or
# removal.
#
# The walk starts at an anchor rather than at `/` on purpose. Above the
# store the operator owns the layout, and legitimate symlinks live
# there — `/tmp` is one on macOS. Anchoring at the store's grandparent
# covers exactly the components this tooling creates and traverses
# (`.runtime`, `wss3`, `<profile-id>`, the record) plus the one
# component that the R30.7 reproduction subverted.

# wss3_runtime_anchor <root> — the trusted directory the walk starts at.
wss3_runtime_anchor() {
    local root="${1:-}"
    printf '%s' "$(dirname "$(dirname "$root")")"
}

# wss3_assert_path_components <anchor> <path>
#   Exit 0 iff no component of <path> below <anchor> is a symlink.
#   Components that do not exist yet are fine — they cannot redirect
#   anything — but one that exists and is a symlink is refused. Sets
#   WSS3_RECORD_ERR.
wss3_assert_path_components() {
    local anchor="${1:-}" path="${2:-}" rel cur comp
    WSS3_RECORD_ERR=""
    if [ -z "$anchor" ] || [ -z "$path" ]; then
        WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
        return 1
    fi
    case "$path" in
        "$anchor"/*) rel="${path#"$anchor"/}" ;;
        "$anchor")   return 0 ;;
        *)
            # The path is not under the anchor at all: refuse rather
            # than walk something we cannot reason about.
            WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
            return 1 ;;
    esac
    cur="$anchor"
    while [ -n "$rel" ]; do
        comp="${rel%%/*}"
        case "$rel" in
            */*) rel="${rel#*/}" ;;
            *)   rel="" ;;
        esac
        [ -n "$comp" ] || continue
        case "$comp" in
            ..|.)
                WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
                return 1 ;;
        esac
        cur="$cur/$comp"
        if [ -L "$cur" ]; then
            WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
            return 1
        fi
    done
    return 0
}

# wss3_assert_store_path <root> [profile-id] [kind]
#   The one guard every mutating and reading entry point calls BEFORE it
#   touches the filesystem. Validates the anchor, then every component
#   down to the record.
wss3_assert_store_path() {
    local root="${1:-}" pid="${2:-}" kind="${3:-}" anchor name path
    WSS3_RECORD_ERR=""
    anchor=$(wss3_runtime_anchor "$root")
    if [ -L "$anchor" ] || [ ! -d "$anchor" ]; then
        WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
        return 1
    fi
    path="$root"
    if [ -n "$pid" ]; then
        path="$path/$pid"
        if [ -n "$kind" ]; then
            name=$(wss3_record_filename "$kind") || {
                WSS3_RECORD_ERR="$WSS3_ERR_RECORD_SYNTAX"
                return 1
            }
            path="$path/$name"
        fi
    fi
    wss3_assert_path_components "$anchor" "$path" || return 1
    return 0
}

# wss3_assert_container_dir <path>
#   Exit 0 iff <path> is a real directory, not a symlink to one. Sets
#   WSS3_RECORD_ERR on failure.
wss3_assert_container_dir() {
    local d="${1:-}"
    WSS3_RECORD_ERR=""
    if [ -L "$d" ]; then
        WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
        return 1
    fi
    if [ ! -d "$d" ]; then
        WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
        return 1
    fi
    return 0
}

# wss3_assert_contained_record_path <root> <profile-id> <kind>
#   Exit 0 iff the record path for <kind> sits inside <root> through
#   real directories only, with no symlink at ANY component, and the
#   resolved parent is still the profile directory under <root>. A
#   symlinked component would let a write land outside the store while
#   every per-file check still passed. Sets WSS3_RECORD_ERR on failure.
wss3_assert_contained_record_path() {
    local root="${1:-}" pid="${2:-}" kind="${3:-}"
    local name pdir real_root real_pdir
    WSS3_RECORD_ERR=""
    name=$(wss3_record_filename "$kind") || {
        WSS3_RECORD_ERR="$WSS3_ERR_RECORD_SYNTAX"
        return 1
    }
    pdir="$root/$pid"
    # Audit ROUND-30.8 P0: the lexical walk runs first — the resolved
    # comparison below cannot see a symlink ABOVE the root, because both
    # sides resolve through it identically.
    wss3_assert_store_path "$root" "$pid" "$kind" || return 1
    wss3_assert_container_dir "$root" || return 1
    wss3_assert_container_dir "$pdir" || return 1
    # `cd` follows symlinks, so comparing the resolved parent against the
    # resolved root catches a component that points elsewhere even when
    # each individual `-L` test passed.
    real_root=$(cd "$root" 2>/dev/null && pwd -P) || {
        WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
        return 1
    }
    real_pdir=$(cd "$pdir" 2>/dev/null && pwd -P) || {
        WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
        return 1
    }
    case "$real_pdir" in
        "$real_root"/*) : ;;
        *)
            WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
            return 1 ;;
    esac
    # The record itself, when present, must be a plain regular file.
    if wss3_path_present "$pdir/$name" && ! wss3_is_plain_file "$pdir/$name"; then
        WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
        return 1
    fi
    return 0
}

# wss3_path_present <path>
#   Exit 0 if anything at all occupies <path> — including a dangling
#   symlink, which `[ -e ]` alone reports as absent.
wss3_path_present() {
    local p="${1:-}"
    if [ -z "$p" ]; then
        return 1
    fi
    if [ -e "$p" ]; then
        return 0
    fi
    if [ -L "$p" ]; then
        return 0
    fi
    return 1
}

wss3_check_mode() {
    local path="$1" want="$2"
    [ -e "$path" ] || return 1
    if [ "${WSS3_SKIP_STRICT_MODE_CHECK:-}" = "1" ] || wss3_is_msys_like; then
        # Portability fallback: assert only that the file/dir exists
        # and is owner-readable. Strict mode enforcement runs on Mac.
        [ -r "$path" ] && return 0 || return 1
    fi
    local mode
    if stat -f "%OLp" "$path" >/dev/null 2>&1; then
        mode=$(stat -f "%OLp" "$path")
    else
        mode=$(stat -c "%a" "$path")
    fi
    mode=$((10#$mode))
    want=$((10#$want))
    [ "$mode" -eq "$want" ]
}

# ─── atomic writes (tmp → fsync → rename) ───

# wss3_atomic_write_0600 <target> <content>
#   Writes <content> to <target> via a same-directory `.tmp.N` +
#   `mv` sequence. Sets 0600 on the tmp before rename so the final
#   file never briefly exists at another mode.
wss3_atomic_write_0600() {
    local target="$1" content="$2"
    local dir tmp
    # ── Audit ROUND-30.5 P0: never `mv <file> <directory>` ─────────
    # `mv tmp target` publishes the value when <target> is a regular
    # file, but MOVES THE TEMP FILE INSIDE when <target> is a directory,
    # and the following `chmod` then changes that directory's mode. The
    # call reported success while nothing had been published. A target
    # that exists must be a plain regular file before we touch it.
    if wss3_path_present "$target"; then
        if ! wss3_is_plain_file "$target"; then
            echo "wss3_atomic_write_0600: '$target' is not a regular file" >&2
            return 1
        fi
    fi
    dir=$(dirname "$target")
    tmp=$(mktemp "$dir/.tmp.XXXXXX") || return 1
    chmod 0600 "$tmp" || { rm -f "$tmp"; return 1; }
    printf '%s' "$content" > "$tmp" || { rm -f "$tmp"; return 1; }
    # Best-effort fsync via python (portable across BSD/GNU) — a
    # broken toolchain that lacks python3 falls back to `sync`.
    if command -v python3 >/dev/null 2>&1; then
        # Audit ROUND-30.7: the path goes through argv. Interpolating it
        # into the program text made every quote and backslash in a
        # scratch path a parse hazard, and a crafted path a code-
        # execution one.
        python3 -c '
import os, sys
f = open(sys.argv[1], "rb")
os.fsync(f.fileno())
f.close()
' "$tmp" 2>/dev/null || sync
    else
        sync
    fi
    mv "$tmp" "$target" || { rm -f "$tmp"; return 1; }
    chmod 0600 "$target" || return 1
    return 0
}

# ─── public CRUD ───

# wss3_write_arm <root> <profile-id> <service-value> <arm-token>
#   Writes both files atomically. Both must be non-empty; arm_token
#   validated for UUIDv4 shape.
#   Audit ROUND-30.7: both records go through the one authority, both
#   paths are containment-checked before any write, and the PAIR is
#   transactional — a service without its matching arm-token is not a
#   half-armed profile, it is a failed arm, and it is unwound. No
#   diagnostic names a value.
wss3_write_arm() {
    local root="$1" pid="$2" service="$3" token="$4"
    local svc_f arm_f svc_before=0 arm_before=0
    # Audit ROUND-30.10: a profile held in ROLLBACK_FAILED is refused by
    # EVERY later entry point, not only by the next transaction begin.
    # Recovery data is not a state anything may read past or write over.
    wss3_txn_assert_not_blocked "$root" "$pid" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    # Audit ROUND-30.10 rework: an open transaction for a DIFFERENT
    # profile does not license this write and cannot undo it.
    wss3_txn_assert_no_foreign "$root" "$pid" wss3_write_arm \
        || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    # Audit ROUND-30.10 closure: a bare `.lock` — no BLOCKED marker,
    # just another run holding the profile — refuses this writer too,
    # and it is stated HERE rather than being left to the `mkdir` in
    # the lock acquisition further down. The only exception is the
    # transaction that provably owns that exact lock.
    if ! wss3_txn_owns "$root" "$pid"; then
        wss3_txn_assert_no_live_lock "$root" "$pid" \
            || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_record_check_value service "$service"; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_write_arm: service value rejected ($WSS3_RECORD_REDACTED)" >&2
        return 2
    fi
    if ! wss3_record_check_value arm-token "$token"; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_write_arm: arm-token rejected ($WSS3_RECORD_REDACTED)" >&2
        return 2
    fi
    wss3_ensure_profile_dir "$root" "$pid" || return 1
    if ! wss3_assert_contained_record_path "$root" "$pid" service; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_write_arm: service record path is not contained" >&2
        return 2
    fi
    if ! wss3_assert_contained_record_path "$root" "$pid" arm-token; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_write_arm: arm-token record path is not contained" >&2
        return 2
    fi
    # Audit ROUND-30.9 P0: a non-canonical existing record is refused
    # before the first byte is written. R30.8 checked type and
    # containment but not mode, so a tampered 0644 service file was
    # silently replaced and its mode "fixed" in passing — fail-open on
    # exactly the state an operator most needs told about.
    if ! wss3_assert_canonical_record "$root" "$pid" service; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_write_arm: existing service record is not canonical" >&2
        return 2
    fi
    if ! wss3_assert_canonical_record "$root" "$pid" arm-token; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_write_arm: existing arm-token record is not canonical" >&2
        return 2
    fi
    svc_f="$root/$pid/host-vpn-service"
    arm_f="$root/$pid/arm-token"
    if wss3_path_present "$svc_f"; then svc_before=1; fi
    if wss3_path_present "$arm_f"; then arm_before=1; fi
    # ── Audit ROUND-30.10: ONE journal owns every snapshot ─────────
    # R30.8 captured the pre-state here in a private `mktemp -d`, which
    # nothing else knew about: it was 755 with 644 bytes inside holding
    # a service name, and a signal between the two publications stranded
    # it in /tmp. When an outer transaction is open it already holds a
    # canonical snapshot of both records, so this function takes none.
    # Called standalone, it opens a real transaction rather than a
    # private directory, so there is no untracked snapshot anywhere.
    #
    # Audit ROUND-30.10 rework: a standalone call opens a transaction
    # ALWAYS, not only when a record already exists. R30.10 skipped the
    # transaction for a first-ever arm, which is exactly the case that
    # takes no lock — so two first-arms for one profile could interleave,
    # and the claimed per-profile exclusivity did not cover the very
    # first write. "Nothing to restore" is not "nothing to serialise".
    local snap_dir="" owned_txn=0
    if wss3_txn_owns "$root" "$pid"; then
        snap_dir="$WSS3_TXN_JOURNAL"
    else
        wss3_txn_begin "$root" "$pid" || return 1
        owned_txn=1
        snap_dir="$WSS3_TXN_JOURNAL"
    fi
    # ── the pair is one transaction ────────────────────────────────
    # Audit ROUND-30.10: when a transaction is open the unwind IS the
    # one verified rollback — the same path a signal takes — and its
    # failure propagates. A partial restoration is never success.
    if [ "$svc_before" = "0" ] && ! wss3_txn_created "$svc_f"; then
        _wss3_arm_unwind "$svc_f" "$svc_before" "$arm_f" "$arm_before" "$snap_dir" || return 1
        return 1
    fi
    if [ "$arm_before" = "0" ] && ! wss3_txn_created "$arm_f"; then
        _wss3_arm_unwind "$svc_f" "$svc_before" "$arm_f" "$arm_before" "$snap_dir" || return 1
        return 1
    fi
    wss3_txn_mutating
    if ! wss3_atomic_write_0600 "$svc_f" "$service"; then
        _wss3_arm_unwind "$svc_f" "$svc_before" "$arm_f" "$arm_before" "$snap_dir" || return 1
        return 1
    fi
    if ! wss3_atomic_write_0600 "$arm_f" "$token"; then
        _wss3_arm_unwind "$svc_f" "$svc_before" "$arm_f" "$arm_before" "$snap_dir" || return 1
        return 1
    fi
    # Byte-exact postconditions for both records, plus type and mode.
    if ! wss3_is_plain_file "$svc_f" || ! wss3_is_plain_file "$arm_f" \
       || ! wss3_check_mode "$svc_f" 600 || ! wss3_check_mode "$arm_f" 600 \
       || ! wss3_file_bytes_equal_value "$svc_f" "$service" \
       || ! wss3_file_bytes_equal_value "$arm_f" "$token"; then
        echo "wss3_write_arm: post-write verification failed ($WSS3_RECORD_REDACTED)" >&2
        _wss3_arm_unwind "$svc_f" "$svc_before" "$arm_f" "$arm_before" "$snap_dir" || return 1
        return 1
    fi
    # A transaction this call opened is its own to close. Cleanup that
    # cannot be verified is a failure, not a formality: a surviving
    # journal holds the service value in the clear.
    #
    # A writer running INSIDE someone else's transaction verifies its own
    # records and nothing more — declaring the whole transaction VERIFIED
    # from here would speak for mutations that have not happened yet.
    # The owner declares it.
    if [ "$owned_txn" = "1" ]; then
        wss3_txn_verified
        wss3_txn_commit || return 1
    fi
    return 0
}

# _wss3_arm_unwind <svc> <svc-before> <arm> <arm-before> <snap-dir>
#   Audit ROUND-30.10: routes to the one verified transaction rollback
#   whenever a transaction is open, and only falls back to the
#   existence-only unwind for a caller that has none — which, with
#   `wss3_write_arm` opening its own, means a first-ever arm where there
#   is nothing to restore.
_wss3_arm_unwind() {
    local svc_f="$1" svc_before="$2" arm_f="$3" arm_before="$4" snap_dir="$5"
    if wss3_txn_active; then
        wss3_txn_rollback || return 1
        return 0
    fi
    _wss3_unwind_arm_pair "$svc_f" "$svc_before" "$arm_f" "$arm_before" ""
}

# ══════════════════════════════════════════════════════════════════
# Audit ROUND-30.9 — canonical pre-state and the outer journal
# ══════════════════════════════════════════════════════════════════
#
# R30.8 restored a record only inside `wss3_write_arm`, and threw the
# snapshot away the moment the pair published — so a failure later in
# the preflight (the baseline checkpoint) had nothing to restore from
# and left the profile holding the failed run's arm. And a pre-state
# that was NOT canonical — a 0644 service file, a directory, a symlink —
# was quietly overwritten or deleted rather than refused.
#
# Two rules follow, and everything below implements them:
#
#   1. A record that already exists is either CANONICAL — contained,
#      regular, 0600, well-formed for its kind — or the operation fails
#      closed before touching anything. This tooling never repairs and
#      never deletes an object it does not recognise as its own.
#   2. What is canonical is snapshotted — existence, type, bytes, mode —
#      before the first mutation, and the snapshot outlives every
#      individual writer. It is discarded only when the whole
#      transaction commits.

# ══════════════════════════════════════════════════════════════════
# Audit ROUND-30.10 — the transaction protocol
# ══════════════════════════════════════════════════════════════════
#
# One state machine governs every runtime mutation:
#
#   IDLE -> LOCKED -> JOURNALED -> MUTATING -> VERIFIED -> COMMITTED
#                          \           |          /
#                           `---> ROLLING_BACK <-'
#                                   |        \
#                                 IDLE        ROLLBACK_FAILED
#
# LOCKED           an exclusive per-profile lock is held
# JOURNALED        canonical pre-state captured for every record
# MUTATING         at least one write has been published
# VERIFIED         postconditions checked, nothing committed yet
# COMMITTED        journal discarded, lock released
# ROLLING_BACK     restoring; every step verified
# ROLLBACK_FAILED  a restore postcondition failed. TERMINAL. The
#                  recovery journal is preserved INSIDE the protected
#                  store at 0700/0600, the profile is blocked for later
#                  verbs, and only redacted diagnostics are emitted. The
#                  one copy of the recovery data is never discarded.
#
# There is exactly ONE journal authority, here. R30.9 left
# `wss3_write_arm` making its own untracked `mktemp -d`, which a signal
# between the two publications stranded in /tmp holding plaintext.

WSS3_TXN_STATE="IDLE"
WSS3_TXN_JOURNAL=""
WSS3_TXN_ROOT=""
WSS3_TXN_PROFILE=""
WSS3_TXN_CREATED=""
# Containers this transaction may own objects in. Seeded with the
# profile directory; a caller that reserves an attempt directory under
# the evidence root declares it explicitly.
WSS3_TXN_SCOPE=""
# Container directories this transaction created, deepest last. They are
# taken back with `rmdir`, never `rm -rf`: an empty container this run
# made is removable, a container anything else has since put something
# into is not.
WSS3_TXN_CONTAINERS=""
WSS3_TXN_LOCK=""
# Set when a failed rollback could not preserve its recovery journal and
# the profile lock is deliberately left in place as the fail-closed
# marker. Nothing releases it afterwards; an operator resolves it.
WSS3_TXN_LOCK_RETAINED=0
# Where and how the last failed rollback was blocked. These are the only
# evidence a later `begin` in this process may use to argue that the
# damaged profile is protected on disk; without them it may not argue at
# all, and the process stays terminal.
WSS3_TXN_FAILED_ROOT=""
WSS3_TXN_FAILED_PROFILE=""
WSS3_TXN_FAILED_BLOCK=""
WSS3_TXN_FAILED_LOCK=""
# The one prefix a tombstone is ever named with. It begins with a dot so
# `wss3_list_profile_ids` cannot mistake it for a profile, and it is a
# constant so the enumeration and the removal can never disagree about
# what a tombstone looks like.
WSS3_TOMBSTONE_PREFIX=".removing-"
# Set between the rename that takes a profile out of the way and the
# verified deletion of what was renamed. While it is set there is no
# canonical profile to restore into, so the shared cleanup finishes or
# preserves the tombstone instead of attempting a record rollback.
WSS3_REMOVAL_TOMB=""
# The tombstone NAME a removal has claimed but not yet used. The window
# between claiming it and the rename is short but real, and a signal in
# it must give the name back rather than leave it blocking every later
# removal of that profile.
WSS3_REMOVAL_CLAIM=""
WSS3_TXN_KIND_LIST="service arm-token tunnel"

# wss3_txn_active — exit 0 while a transaction owns the store.
#   Audit ROUND-30.10 closure: a transaction becomes active when its LOCK
#   is taken, not when its journal appears. The lock is acquired first and
#   the journal a few statements later, and testing only the journal left
#   that window outside the protocol: a signal arriving in it found no
#   active transaction, so the handler rolled nothing back and the lock
#   was left behind with no process owning it.
wss3_txn_active() { [ -n "${WSS3_TXN_JOURNAL:-}" ] || [ -n "${WSS3_TXN_LOCK:-}" ]; }

# wss3_txn_journalled — exit 0 once pre-state capture has completed.
#   Rollback needs this distinction: before it is true there is nothing to
#   restore, only a lock and any container this transaction created.
wss3_txn_journalled() { [ -n "${WSS3_TXN_JOURNAL:-}" ]; }

# ── the transition table ───────────────────────────────────────────
#   Audit ROUND-30.10 closure: R30.10 ASSIGNED every state but permitted
#   every order, so an untouched, unverified transaction committed
#   straight out of JOURNALED. A state machine that cannot refuse a
#   transition is a set of labels. Each verb below asks this table first
#   and fails closed on an unlisted move.
#
#   IDLE       -> LOCKED
#   LOCKED     -> JOURNALED | ROLLING_BACK
#   JOURNALED  -> MUTATING | VERIFIED | ROLLING_BACK
#   MUTATING   -> VERIFIED | ROLLING_BACK
#   VERIFIED   -> MUTATING | COMMITTED | ROLLING_BACK
#   COMMITTED  -> IDLE
#   ROLLING_BACK -> IDLE | ROLLBACK_FAILED
#   ROLLBACK_FAILED -> IDLE
#
#   ROLLBACK_FAILED is terminal for the TRANSACTION and for the
#   PROFILE, and the profile's terminality is the one that has to
#   outlive the process: it lives on disk, in `.recovery/BLOCKED` or
#   the retained lock, where no in-process reset can reach it. The
#   single move out of it exists because the state variable describes
#   the CURRENT transaction, and a process that has released a failed
#   one must still be able to serve a DIFFERENT profile. Without it a
#   long-lived process — the test suite is one — could never open a
#   transaction again after any rollback failure anywhere, which is
#   not fail-closed, just broken. The affected profile stays refused
#   by `wss3_txn_assert_not_blocked` at every entry point.
#
#   JOURNALED -> VERIFIED is deliberate: a transaction that legitimately
#   mutates nothing still has to be verified before it may commit. What
#   is refused is committing WITHOUT that verification.
wss3_txn_can_transition() {
    case "$1:$2" in
        IDLE:LOCKED) return 0 ;;
        LOCKED:JOURNALED|LOCKED:ROLLING_BACK|LOCKED:IDLE) return 0 ;;
        JOURNALED:MUTATING|JOURNALED:VERIFIED) return 0 ;;
        JOURNALED:ROLLING_BACK|JOURNALED:IDLE) return 0 ;;
        MUTATING:MUTATING|MUTATING:VERIFIED|MUTATING:ROLLING_BACK) return 0 ;;
        VERIFIED:MUTATING|VERIFIED:COMMITTED|VERIFIED:ROLLING_BACK) return 0 ;;
        COMMITTED:IDLE) return 0 ;;
        ROLLING_BACK:IDLE|ROLLING_BACK:ROLLBACK_FAILED) return 0 ;;
        ROLLBACK_FAILED:IDLE) return 0 ;;
    esac
    return 1
}

# wss3_txn_enter <state> — perform a checked transition.
wss3_txn_enter() {
    local want="$1"
    if ! wss3_txn_can_transition "$WSS3_TXN_STATE" "$want"; then
        echo "$WSS3_ERR_TXN_MISMATCH" >&2
        echo "transaction: refusing the move $WSS3_TXN_STATE -> $want" >&2
        return 1
    fi
    WSS3_TXN_STATE="$want"
    return 0
}

# wss3_txn_owns <root> <profile-id>
#   Audit ROUND-30.10 rework: "a transaction is open" and "a transaction
#   covering THIS profile is open" are different questions, and only the
#   second one licenses a mutation. R30.10 asked the first, so a writer
#   aimed at profile B, called while a transaction for profile A was
#   open, wrote B under A's journal: B's pre-state was never captured, A's
#   rollback did not restore B, and the write escaped the protocol
#   entirely. The transaction is bound to one root and one profile.
wss3_txn_owns() {
    wss3_txn_active || return 1
    [ "${WSS3_TXN_ROOT:-}" = "$1" ] || return 1
    [ "${WSS3_TXN_PROFILE:-}" = "$2" ] || return 1
    return 0
}

# wss3_txn_assert_no_foreign <root> <profile-id> <caller>
#   Fail closed when a transaction is open for something else. There is
#   no safe interpretation of that call: it is neither this transaction's
#   work nor work the journal can undo.
wss3_txn_assert_no_foreign() {
    if wss3_txn_active && ! wss3_txn_owns "$1" "$2"; then
        echo "$WSS3_ERR_TXN_MISMATCH" >&2
        echo "$3: a transaction for another profile is open — refusing" >&2
        return 1
    fi
    return 0
}

# wss3_txn_blocked_marker <root> <profile-id>
wss3_txn_blocked_marker() { printf '%s/%s/.recovery/BLOCKED' "$1" "$2"; }

# wss3_txn_lock_marker <root> <profile-id>
#   The fallback fail-closed marker, written inside the lock directory
#   the transaction already holds. Reaching it needs no new container, so
#   it survives the case where creating `.recovery` is what failed.
wss3_txn_lock_marker() { printf '%s/%s/.lock/BLOCKED' "$1" "$2"; }

# wss3_txn_assert_not_blocked <root> <profile-id>
#   A profile left in ROLLBACK_FAILED is refused by every later verb
#   until an operator resolves it by hand.
#   Audit ROUND-30.10 rework: EITHER marker blocks. When preservation
#   into `.recovery` is the step that failed, the recovery data is still
#   in the journal and the profile is still unsafe — checking only the
#   `.recovery` marker made exactly that case fail OPEN.
wss3_txn_assert_not_blocked() {
    local m l
    m=$(wss3_txn_blocked_marker "$1" "$2")
    l=$(wss3_txn_lock_marker "$1" "$2")
    if wss3_path_present "$m" || wss3_path_present "$l"; then
        echo "$WSS3_ERR_ROLLBACK_FAILED" >&2
        echo "runtime state for this profile is held for recovery — resolve it by hand before running this verb again" >&2
        return 1
    fi
    return 0
}

# wss3_txn_assert_no_live_lock <root> <profile-id>
#   Audit ROUND-30.10 closure: a live lock stopped WRITERS and nothing
#   else, so a reader could take a value out of a profile that another
#   process was in the middle of changing — including the window between
#   the lock being taken and the journal existing, where a signal leaves
#   the store mid-transaction. A reader now refuses a profile somebody
#   else holds. The transaction that OWNS the lock reads normally, which
#   is what `resume` does between its begin and its first write.
#
#   A lock left behind by a killed run therefore refuses reads too. That
#   is the intended direction: it is resolved by removing the `.lock`
#   directory by hand once no run is active, which the README documents.
wss3_txn_assert_no_live_lock() {
    local lock="$1/$2/.lock"
    [ -d "$lock" ] || return 0
    [ "${WSS3_TXN_LOCK:-}" = "$lock" ] && return 0
    echo "$WSS3_ERR_TXN_LOCKED" >&2
    echo "another transaction holds this profile — refusing to read state it may be changing" >&2
    return 1
}

# wss3_txn_lock_acquire <root> <profile-id>
#   Audit ROUND-30.10: an exclusive per-profile lock, taken BEFORE the
#   journal is captured. `mkdir` is atomic on every filesystem this runs
#   on, so a second preflight or resume for the same profile fails
#   closed without mutating anything. Different profiles are unaffected:
#   the lock lives inside the profile directory.
wss3_txn_lock_acquire() {
    local root="$1" pid="$2" lock="$1/$2/.lock"
    if ! mkdir "$lock" 2>/dev/null; then
        echo "$WSS3_ERR_TXN_LOCKED" >&2
        echo "another transaction is already running for this profile" >&2
        return 1
    fi
    chmod 0700 "$lock" 2>/dev/null || true
    WSS3_TXN_LOCK="$lock"
    return 0
}

# wss3_txn_lock_release
#   Audit ROUND-30.10 rework: releasing the lock is a postcondition, not
#   an attempt. R30.10 returned 0 unconditionally, so a lock that
#   survived was reported as dropped and the next run met a "locked"
#   profile with nothing running. A surviving lock is fail-closed and
#   must be said out loud.
wss3_txn_lock_release() {
    local lock="${WSS3_TXN_LOCK:-}"
    [ -n "$lock" ] || return 0
    if [ ! -d "$lock" ]; then
        WSS3_TXN_LOCK=""
        return 0
    fi
    rmdir "$lock" 2>/dev/null || rm -rf -- "$lock" 2>/dev/null
    if [ -e "$lock" ]; then
        echo "$WSS3_ERR_TXN_CLEANUP" >&2
        echo "transaction: the profile lock at $lock could not be released" >&2
        WSS3_TXN_LOCK=""
        return 1
    fi
    WSS3_TXN_LOCK=""
    return 0
}

# _wss3_txn_failure_is_contained
#   Exit 0 only when the profile the last rollback failed on is provably
#   blocked ON DISK, by one of exactly two means:
#
#     recovery — `.recovery/BLOCKED` exists AND the preserved copy still
#                verifies against its manifest, closure and bytes. A
#                marker with missing or altered data behind it is not
#                protection.
#     lock     — the retained lock is still there AND its path is the
#                canonical `<root>/<profile>/.lock` this very transaction
#                created. A lock somewhere else, or one this process did
#                not take, proves nothing about this profile.
#
#   Anything else — no record of the failure, an unknown block kind, a
#   vanished marker — is not proof, and the process stays terminal.
_wss3_txn_failure_is_contained() {
    local root="${WSS3_TXN_FAILED_ROOT:-}" pid="${WSS3_TXN_FAILED_PROFILE:-}"
    [ -n "$root" ] && [ -n "$pid" ] || return 1
    case "${WSS3_TXN_FAILED_BLOCK:-}" in
        recovery)
            local recovery="$root/$pid/.recovery"
            [ -f "$recovery/BLOCKED" ] || return 1
            _wss3_recovery_manifest_verify "$recovery" || return 1
            return 0
            ;;
        lock)
            local canonical="$root/$pid/.lock"
            [ "${WSS3_TXN_FAILED_LOCK:-}" = "$canonical" ] || return 1
            [ -d "$canonical" ] || return 1
            return 0
            ;;
    esac
    return 1
}

# wss3_txn_begin <root> <profile-id>
#   IDLE -> LOCKED -> JOURNALED. Refuses a blocked profile, takes the
#   lock, then captures canonical pre-state for every record. Nothing is
#   mutated on any failure path here beyond the lock, which is released.
wss3_txn_begin() {
    local root="$1" pid="$2" kind
    # Audit ROUND-30.10 rework: a second `begin` over an open transaction
    # overwrote the globals and stranded the first journal AND its lock —
    # the first transaction's pre-state became unreachable and its
    # profile stayed locked forever. There is no nesting in this
    # protocol: one transaction at a time, per process.
    if wss3_txn_active; then
        echo "$WSS3_ERR_TXN_MISMATCH" >&2
        echo "transaction: one is already open — refusing to nest" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    # A previous transaction in this process may have ended in the
    # terminal ROLLBACK_FAILED state. This process may go on to serve a
    # DIFFERENT profile — but only after PROVING that the damaged one is
    # protected on disk, because a process that forgot about an
    # unprotected damaged profile is worse than one that stops.
    if [ "$WSS3_TXN_STATE" = "ROLLBACK_FAILED" ]; then
        if ! _wss3_txn_failure_is_contained; then
            echo "$WSS3_ERR_ROLLBACK_FAILED" >&2
            echo "transaction: the previous rollback failure is not provably contained — refusing to start anything" >&2
            return "$WSS3_EXIT_PREARM_CLI_FAILURE"
        fi
        wss3_txn_enter IDLE || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    wss3_txn_assert_not_blocked "$root" "$pid" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    WSS3_TXN_CONTAINERS=""
    # The lock lives INSIDE the store, so the store has to exist before
    # it can be taken. A first-ever profile has no directory yet, and
    # taking the lock first made `mkdir` fail and refused every fresh
    # preflight — the containment-checked helper establishes the
    # container first, which mutates no record.
    if ! wss3_ensure_profile_dir "$root" "$pid"; then
        _wss3_txn_remove_containers
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_txn_lock_acquire "$root" "$pid"; then
        _wss3_txn_remove_containers
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    wss3_txn_enter LOCKED || {
        wss3_txn_lock_release
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    }
    # Audit ROUND-30.10: where journals live is configurable, defaulting
    # to the system temp directory. That is not a test hook — it lets an
    # operator keep recovery data on the same volume as the store — and
    # it is what makes "no untracked snapshot exists" a checkable
    # statement about a known directory rather than a glob over all of
    # /tmp, which is what made the R30.9 fixtures interfere with a
    # concurrent suite run.
    WSS3_TXN_JOURNAL=$(mktemp -d "${WSS3_TXN_JOURNAL_BASE:-${TMPDIR:-/tmp}}/wss3-txn.XXXXXX") || {
        wss3_txn_lock_release
        wss3_txn_enter IDLE
        echo "transaction: could not open a journal" >&2
        return 1
    }
    if ! chmod 0700 "$WSS3_TXN_JOURNAL"; then
        rm -rf -- "$WSS3_TXN_JOURNAL"
        WSS3_TXN_JOURNAL=""
        wss3_txn_lock_release
        wss3_txn_enter IDLE
        echo "$WSS3_ERR_MODE_VIOLATION" >&2
        return 1
    fi
    WSS3_TXN_ROOT="$root"
    WSS3_TXN_PROFILE="$pid"
    WSS3_TXN_CREATED=""
    WSS3_TXN_SCOPE=""
    wss3_txn_scope_add "$root/$pid"
    for kind in $WSS3_TXN_KIND_LIST; do
        if ! wss3_journal_capture "$WSS3_TXN_JOURNAL" "$root" "$pid" "$kind"; then
            echo "$WSS3_RECORD_ERR" >&2
            echo "transaction: existing $kind record is not canonical — refusing before any change" >&2
            wss3_txn_discard
            return "$WSS3_EXIT_PREARM_CLI_FAILURE"
        fi
    done
    wss3_txn_enter JOURNALED || {
        wss3_txn_discard
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    }
    return 0
}

# wss3_txn_mutating — JOURNALED -> MUTATING. Called by every writer
#   before its first byte lands, whether it creates a record or
#   overwrites one. Audit ROUND-30.10 rework: MUTATING used to be set
#   only from `wss3_txn_created`, so overwriting an EXISTING record — the
#   most common mutation there is — left the machine reading JOURNALED
#   while the store was already changed.
#   VERIFIED is included deliberately: a mutation that happens after a
#   verification invalidates it and puts the machine back into MUTATING.
#   Only COMMITTED and ROLLBACK_FAILED, which are terminal, are immune.
wss3_txn_mutating() {
    wss3_txn_active || return 0
    [ "$WSS3_TXN_STATE" = "MUTATING" ] && return 0
    wss3_txn_enter MUTATING || return 1
    return 0
}

# wss3_txn_verified — MUTATING -> VERIFIED. Postconditions passed;
#   nothing has been committed yet, and the journal is still held.
wss3_txn_verified() {
    wss3_txn_active || return 0
    [ "$WSS3_TXN_STATE" = "VERIFIED" ] && return 0
    wss3_txn_enter VERIFIED || return 1
    return 0
}

# wss3_txn_container_created <directory>
#   Records a container directory this transaction created. Kept separate
#   from the record/object list because the removal verb differs: `rmdir`
#   only succeeds on an EMPTY directory, so taking back a container can
#   never destroy anything that arrived after it was made.
wss3_txn_container_created() {
    case "$1" in
        *"
"*) return 1 ;;
    esac
    WSS3_TXN_CONTAINERS="$WSS3_TXN_CONTAINERS
$1"
    return 0
}

# _wss3_txn_remove_containers — take back created containers, deepest
#   first. A container that is not empty is left alone and is not an
#   error: something else owns what is inside it.
_wss3_txn_remove_containers() {
    local d rev=""
    while IFS= read -r d; do
        [ -n "$d" ] || continue
        rev="$d
$rev"
    done <<EOF
$WSS3_TXN_CONTAINERS
EOF
    while IFS= read -r d; do
        [ -n "$d" ] || continue
        [ -d "$d" ] || continue
        rmdir "$d" 2>/dev/null || true
    done <<EOF
$rev
EOF
    WSS3_TXN_CONTAINERS=""
    return 0
}

# wss3_txn_scope_add <directory>
#   Declares a container this transaction may own objects in, beyond its
#   own profile directory. The preflight and `resume` both reserve an
#   attempt directory under the EVIDENCE root, which is legitimately
#   outside the runtime store — so the ownership scope is declared rather
#   than assumed, and anything outside every declared container is
#   refused instead of being removed.
wss3_txn_scope_add() {
    [ -n "${1:-}" ] || return 1
    WSS3_TXN_SCOPE="$WSS3_TXN_SCOPE
${1%/}"
    return 0
}

# _wss3_txn_in_scope <path> — exit 0 iff <path> sits inside a declared
#   container of the open transaction.
_wss3_txn_in_scope() {
    local path="$1" c
    while IFS= read -r c; do
        [ -n "$c" ] || continue
        _wss3_path_within "$c" "$path" && return 0
    done <<EOF
$WSS3_TXN_SCOPE
EOF
    return 1
}

# wss3_txn_created <path> — an object this transaction created.
#   Audit ROUND-30.10 rework: the ownership list is newline-separated and
#   read back line by line, so a path containing SPACES — every path
#   under this project's own store — survives it. A path holding a
#   newline cannot be represented here at all, so it is refused at
#   registration rather than silently truncated into two half-paths at
#   rollback time. Scope is checked HERE, before the object is mutated,
#   so an out-of-scope registration fails the operation instead of
#   surfacing as an unremovable object during recovery.
wss3_txn_created() {
    wss3_txn_active || return 0
    case "$1" in
        *"
"*)
            echo "$WSS3_ERR_TXN_MISMATCH" >&2
            echo "transaction: refusing to track an object whose path contains a newline" >&2
            return 1
            ;;
    esac
    if ! _wss3_txn_in_scope "$1"; then
        echo "$WSS3_ERR_TXN_MISMATCH" >&2
        echo "transaction: refusing to track an object outside this transaction's scope" >&2
        return 1
    fi
    WSS3_TXN_CREATED="$WSS3_TXN_CREATED
$1"
    wss3_txn_mutating
    return 0
}

# wss3_txn_discard — release the journal and the lock, and PROVE both are
#   gone. Audit ROUND-30.10 rework: this used `rm -rf … || true`, ignored
#   the lock-release result and returned 0 regardless, so a commit could
#   report success while a 0700 journal full of record bytes and a live
#   profile lock both survived. Cleanup is part of the transaction.
wss3_txn_discard() {
    local rc=0 journal="${WSS3_TXN_JOURNAL:-}"
    if [ -n "$journal" ] && [ -d "$journal" ]; then
        rm -rf -- "$journal" 2>/dev/null
        if [ -e "$journal" ]; then
            echo "$WSS3_ERR_TXN_CLEANUP" >&2
            echo "transaction: the journal at $journal could not be removed — it still holds record bytes" >&2
            rc=1
        fi
    fi
    WSS3_TXN_JOURNAL=""
    WSS3_TXN_ROOT=""
    WSS3_TXN_PROFILE=""
    WSS3_TXN_CREATED=""
    WSS3_TXN_SCOPE=""
    WSS3_TXN_CONTAINERS=""
    wss3_txn_lock_release || rc=1
    # Audit ROUND-30.10 closure: cleanup itself is unconditional — a
    # journal left on disk would be worse than a protocol complaint — but
    # abandoning a transaction that has already MUTATED the store, without
    # rolling it back, is not a clean exit and is reported as such.
    if ! wss3_txn_enter IDLE; then
        # Audit ROUND-30.10 closure: cleanup itself is unconditional,
        # but this is the ONE place that forces the state, so it is
        # fenced. It may never be the way out of ROLLBACK_FAILED:
        # leaving that state is licensed by proof of on-disk
        # containment in `wss3_txn_begin` and by nothing else.
        if [ "$WSS3_TXN_STATE" = "ROLLBACK_FAILED" ]; then
            echo "transaction: refusing to clear the terminal state" >&2
            return 1
        fi
        echo "transaction: discarded from $WSS3_TXN_STATE without a rollback" >&2
        WSS3_TXN_STATE="IDLE"
        rc=1
    fi
    return "$rc"
}

# wss3_txn_commit — VERIFIED -> COMMITTED -> IDLE. The only success exit
#   from a transaction. Audit ROUND-30.10 rework: R30.10 named COMMITTED
#   in its diagram and never assigned it; every success path went
#   straight to `discard`. The state is now reached on the way out, and a
#   cleanup failure is reported rather than absorbed.
wss3_txn_commit() {
    wss3_txn_active || return 0
    # Audit ROUND-30.10 closure: COMMITTED is reachable only from
    # VERIFIED. R30.10 assigned the state unconditionally, so a
    # transaction that had neither mutated nor been verified committed
    # straight out of JOURNALED and the machine never refused anything.
    if ! wss3_txn_enter COMMITTED; then
        echo "transaction: refusing to commit without a verification" >&2
        return 1
    fi
    wss3_txn_discard || return 1
    return 0
}

# _wss3_path_within <container> <path>
#   Lexical containment for an ownership entry: <path> must be <container>
#   itself or sit strictly beneath it, with no `..` component anywhere.
#   Both sides come from this process, so the comparison is lexical by
#   design — it is a guard against a corrupted list, not a resolver.
_wss3_path_within() {
    local container="${1%/}" path="$2"
    [ -n "$container" ] || return 1
    case "$path" in
        *"/../"*|*"/..") return 1 ;;
        "../"*|"..") return 1 ;;
    esac
    [ "$path" = "$container" ] && return 0
    case "$path" in
        "$container"/?*) return 0 ;;
    esac
    return 1
}

# _wss3_recovery_copy_verified <journal> <recovery>
#   Audit ROUND-30.10 rework: proves the recovery copy is a COMPLETE,
#   byte-identical replica before the original journal is destroyed.
#   R30.10 checked only that a BLOCKED marker had appeared, so a `cp`
#   that reported success while copying nothing still satisfied it — and
#   the one copy of the recovery data was then deleted. Every regular
#   file in the journal must exist in the copy with the same bytes, and
#   the copy must carry no fewer files than the journal.
_wss3_recovery_copy_verified() {
    local journal="$1" recovery="$2" rel src dst n_src=0 n_dst=0
    [ -d "$recovery" ] || return 1
    while IFS= read -r src; do
        [ -n "$src" ] || continue
        n_src=$((n_src + 1))
        rel=${src#"$journal"/}
        dst="$recovery/$rel"
        [ -f "$dst" ] || return 1
        [ "$(wss3_hex_of_file "$src")" = "$(wss3_hex_of_file "$dst")" ] || return 1
    done <<EOF
$(find "$journal" -type f 2>/dev/null)
EOF
    n_dst=$(find "$recovery" -type f 2>/dev/null | wc -l | tr -d '[:space:]')
    [ "${n_dst:-0}" -ge "$n_src" ] || return 1
    return 0
}

# The two names the manifest describes but never lists, so it can never
# reference itself and the marker can never masquerade as recovery data.
WSS3_RECOVERY_MANIFEST_NAME="MANIFEST"
WSS3_RECOVERY_MARKER_NAME="BLOCKED"

# _wss3_recovery_is_reserved <recovery-dir> <path>
#   Exit 0 for the two reserved names at the top level of the copy.
_wss3_recovery_is_reserved() {
    case "$2" in
        "$1/$WSS3_RECOVERY_MANIFEST_NAME"|"$1/$WSS3_RECOVERY_MARKER_NAME") return 0 ;;
    esac
    return 1
}

# _wss3_recovery_name_is_representable <relative-name>
#   Audit ROUND-30.10 closure: the manifest is one line per file, so a
#   name containing a newline cannot be written down unambiguously — and
#   an inventory that walks `find` output line by line would silently
#   read such a name as two entries, which is the same word-splitting
#   class that broke the rollback ownership list. Rather than invent an
#   escaping scheme for a name this tooling never creates, any name that
#   cannot be represented exactly is REFUSED, and the refusal names no
#   value: the operator gets the error code and a redacted marker, per
#   the privacy rule that governs every other diagnostic here.
_wss3_recovery_name_is_representable() {
    case "$1" in
        "") return 1 ;;
        *[[:cntrl:]]*) return 1 ;;
    esac
    return 0
}

# _wss3_recovery_manifest_write <recovery-dir>
#   Records, inside the protected store, exactly what the verified
#   recovery copy contains: one line per preserved file and its content
#   digest. This never reaches stdout, stderr, evidence or any packaged
#   artifact — it lives at 0600 in the same 0700 directory as the bytes
#   it describes, so it discloses nothing that is not already there, and
#   the privacy rule keeps applying to everything that IS printed.
#
#   The inventory is NUL-delimited, so no name is ever split. A copy that
#   holds anything other than plain regular files, or a name that cannot
#   be written down exactly, is refused outright: the journal is flat and
#   its names are this module's own, so anything else means something
#   other than this transaction produced it.
_wss3_recovery_manifest_write() {
    local recovery="$1" e rel manifest="$1/$WSS3_RECOVERY_MANIFEST_NAME"
    : > "$manifest" || return 1
    chmod 0600 "$manifest" || return 1
    while IFS= read -r -d '' e; do
        [ -n "$e" ] || continue
        _wss3_recovery_is_reserved "$recovery" "$e" && continue
        # A symlink is not a regular file, and `-L` is asked FIRST so a
        # link pointing at a regular file cannot answer for it.
        if [ -L "$e" ] || [ ! -f "$e" ]; then
            echo "$WSS3_ERR_TYPE_VIOLATION" >&2
            echo "recovery inventory: a preserved object is not a regular file ($WSS3_RECORD_REDACTED)" >&2
            return 1
        fi
        rel=${e#"$recovery"/}
        if ! _wss3_recovery_name_is_representable "$rel"; then
            echo "$WSS3_ERR_TYPE_VIOLATION" >&2
            echo "recovery inventory: a preserved name cannot be recorded exactly ($WSS3_RECORD_REDACTED)" >&2
            return 1
        fi
        printf '%s %s\n' "$rel" "$(wss3_hex_of_file "$e")" >> "$manifest" || return 1
    # A here-doc cannot carry this: command substitution strips NUL
    # bytes, which is the one byte the inventory depends on. The walk
    # is fed straight in instead, and the loop still runs in this shell
    # so its counters survive.
    done < <(find "$recovery" -mindepth 1 -print0 2>/dev/null)
    [ -s "$manifest" ] || return 1
    return 0
}

# _wss3_recovery_manifest_verify <recovery-dir>
#   Re-proves that the preserved copy is EXACTLY what was preserved:
#   every listed file present with the same bytes, and no object present
#   that the manifest does not list — not an added file, not an added
#   directory, not a symlink, and not a name that cannot be represented.
#   Audit ROUND-30.10 closure: a `.recovery/BLOCKED` marker counts as
#   proof that a damaged profile is protected only after this passes.
#   Counting regular files line by line would have let a planted
#   directory, a symlink standing in for a record, or a name holding a
#   newline sit inside the copy unnoticed.
_wss3_recovery_manifest_verify() {
    local recovery="$1" manifest="$1/$WSS3_RECOVERY_MANIFEST_NAME"
    local rel want_hex f n=0 present=0 e
    [ -f "$manifest" ] || return 1
    [ -f "$recovery/$WSS3_RECOVERY_MARKER_NAME" ] || return 1
    while IFS=' ' read -r rel want_hex; do
        [ -n "$rel" ] || continue
        _wss3_recovery_name_is_representable "$rel" || return 1
        f="$recovery/$rel"
        [ -L "$f" ] && return 1
        [ -f "$f" ] || return 1
        # The hex of the exact bytes settles length and content together.
        [ "$(wss3_hex_of_file "$f")" = "$want_hex" ] || return 1
        n=$((n + 1))
    done < "$manifest"
    [ "$n" -gt 0 ] || return 1
    # Closure the other way: EVERY object in the copy, of any type and
    # whatever its name contains, is either one of the two reserved names
    # or one the manifest listed. The walk is NUL-delimited so a name can
    # never arrive as two entries.
    while IFS= read -r -d '' e; do
        [ -n "$e" ] || continue
        _wss3_recovery_is_reserved "$recovery" "$e" && continue
        [ -L "$e" ] && return 1
        [ -f "$e" ] || return 1
        rel=${e#"$recovery"/}
        _wss3_recovery_name_is_representable "$rel" || return 1
        present=$((present + 1))
    done < <(find "$recovery" -mindepth 1 -print0 2>/dev/null)
    [ "$present" = "$n" ] || return 1
    return 0
}

# wss3_txn_rollback
#   MUTATING -> ROLLING_BACK -> IDLE, or -> ROLLBACK_FAILED.
#   Every restore and every removal is verified. If ANY postcondition
#   fails the journal is NOT discarded: it is moved into the protected
#   store, the profile is blocked, and the function returns non-zero.
#   Success is never reported for a partial restoration.
wss3_txn_rollback() {
    local kind path failed=0 recovery
    # ── Audit ROUND-30.10 closure: past the removal commit boundary
    # a record rollback is meaningless ─────────────────────────────
    # Between the rename that takes a profile out of the way and the
    # verified deletion of what was renamed, the canonical profile
    # does not exist. A signal arriving in that window used to reach
    # here and try to restore records INTO a path that is gone —
    # recreating the very directory the removal had just emptied.
    # What is owed in that window is the tombstone: finish it, or
    # keep it where an operator can find it.
    if [ -n "${WSS3_REMOVAL_TOMB:-}" ]; then
        wss3_removal_finish_tombstone
        return $?
    fi
    wss3_txn_active || return 0
    wss3_txn_enter ROLLING_BACK || {
        echo "rollback: refusing an impossible move from $WSS3_TXN_STATE" >&2
        return 1
    }
    for kind in $WSS3_TXN_KIND_LIST; do
        if ! wss3_journal_restore "$WSS3_TXN_JOURNAL" "$WSS3_TXN_ROOT" "$WSS3_TXN_PROFILE" "$kind"; then
            echo "rollback: could not restore the $kind record ($WSS3_RECORD_REDACTED)" >&2
            failed=1
        fi
    done
    # Objects this transaction created. A removal that does not verify
    # is a failed rollback, not a warning.
    #
    # Audit ROUND-30.10 rework: this loop was
    # `for path in $(printf '%s\n' "$WSS3_TXN_CREATED")`, which word-splits
    # on IFS. Every path in this project's own store contains a space
    # ("VL Stories Studio"), so the created object was never removed —
    # rollback reported success with the object still there — and the
    # leading fragment was passed to `rm -rf` on its own, which for
    # `<...>/a b/...` means deleting an unrelated `<...>/a`. A verified
    # rollback cannot be built on word splitting.
    #
    # Two rules now hold. The list is read line by line with IFS cleared,
    # so a path is one path. And nothing is removed until it is proven to
    # sit inside THIS transaction's profile directory — the same
    # containment discipline every other mutation goes through, applied
    # to the one operation that can destroy a tree.
    while IFS= read -r path; do
        [ -n "$path" ] || continue
        if ! _wss3_txn_in_scope "$path"; then
            echo "rollback: refusing to remove an object outside this transaction's scope" >&2
            failed=1
            continue
        fi
        if wss3_path_present "$path"; then
            rm -rf -- "$path" 2>/dev/null
            if wss3_path_present "$path"; then
                echo "rollback: could not remove an object this run created" >&2
                failed=1
            fi
        fi
    done <<EOF
$WSS3_TXN_CREATED
EOF
    if [ "$failed" != "0" ]; then
        # ROLLBACK_FAILED. Preserve the ONLY copy of the recovery data
        # inside the protected store, and block the profile.
        # Audit ROUND-30.10: preservation is MANDATORY, so every step is
        # checked. The journal is removed from its old location ONLY
        # after the copy is verified to exist — losing the one copy of
        # the recovery data while reporting a blocked profile would be
        # the worst outcome of all. If preservation fails, the journal
        # stays exactly where it is and the operator is told where.
        recovery="$WSS3_TXN_ROOT/$WSS3_TXN_PROFILE/.recovery"
        local preserved=0
        if mkdir -p "$recovery" && chmod 0700 "$recovery"; then
            # Audit ROUND-30.10 rework: the copy is verified for CLOSURE
            # and byte parity before anything is deleted. Marker presence
            # is not evidence that the data came with it.
            if cp -R "$WSS3_TXN_JOURNAL"/. "$recovery"/ 2>/dev/null \
               && _wss3_recovery_copy_verified "$WSS3_TXN_JOURNAL" "$recovery" \
               && find "$recovery" -type f -exec chmod 0600 {} + \
               && _wss3_recovery_manifest_write "$recovery" \
               && : > "$recovery/BLOCKED" \
               && chmod 0600 "$recovery/BLOCKED" \
               && [ -f "$recovery/BLOCKED" ]; then
                preserved=1
            fi
        fi
        if [ "$preserved" = "1" ]; then
            rm -rf -- "$WSS3_TXN_JOURNAL" 2>/dev/null
            if [ -e "$WSS3_TXN_JOURNAL" ]; then
                echo "rollback: the superseded journal at $WSS3_TXN_JOURNAL could not be removed" >&2
            fi
            WSS3_TXN_LOCK_RETAINED=0
            WSS3_TXN_FAILED_BLOCK="recovery"
            # The recovery copy is verified and `.recovery/BLOCKED`
            # is in place, so the profile is already fail-closed and
            # releasing the lock is cleanup, not recovery. The
            # release prints its own diagnostic and this function
            # returns ROLLBACK_FAILED either way; `|| true` only
            # stops a caller running under `set -e` from aborting
            # the remaining bookkeeping.
            wss3_txn_lock_release || true
        else
            # ── the fail-closed path ───────────────────────────────────
            # Audit ROUND-30.10 rework: R30.10 released the lock and
            # cleared every global here, leaving no PERSISTENT evidence
            # that the profile was unsafe: the `.recovery/BLOCKED` marker
            # is exactly what could not be created, so the next process
            # saw an ordinary profile and was free to mutate it on top of
            # an unrestored state.
            #
            # Two things now make the refusal survive this process. The
            # lock the transaction already holds is RETAINED — dropping
            # it needs no filesystem success, so keeping it cannot fail —
            # and a marker is written inside that existing directory,
            # which needs no new container. Either one blocks the next
            # verb; the lock does so even if nothing can be written.
            echo "rollback: could not preserve the recovery journal in the store" >&2
            echo "rollback: the recovery data remains at $WSS3_TXN_JOURNAL — do not delete it" >&2
            if [ -n "${WSS3_TXN_LOCK:-}" ] && [ -d "$WSS3_TXN_LOCK" ]; then
                # Creating the marker IS checked; tightening its mode
                # is defence in depth. What refuses the next verb is
                # the marker existing, and the retained lock refuses
                # it even if neither can be written.
                if : > "$WSS3_TXN_LOCK/BLOCKED" 2>/dev/null; then
                    chmod 0600 "$WSS3_TXN_LOCK/BLOCKED" 2>/dev/null || true
                fi
            fi
            WSS3_TXN_LOCK_RETAINED=1
            WSS3_TXN_FAILED_BLOCK="lock"
            WSS3_TXN_FAILED_LOCK="$WSS3_TXN_LOCK"
            WSS3_TXN_LOCK=""
        fi
        echo "$WSS3_ERR_ROLLBACK_FAILED" >&2
        WSS3_TXN_FAILED_ROOT="$WSS3_TXN_ROOT"
        WSS3_TXN_FAILED_PROFILE="$WSS3_TXN_PROFILE"
        WSS3_TXN_JOURNAL=""
        WSS3_TXN_ROOT=""
        WSS3_TXN_PROFILE=""
        WSS3_TXN_CREATED=""
        WSS3_TXN_SCOPE=""
        wss3_txn_enter ROLLBACK_FAILED || WSS3_TXN_STATE="ROLLBACK_FAILED"
        return 1
    fi
    # A rollback whose own cleanup could not be verified is not a clean
    # rollback: the journal still holds record bytes, or the profile is
    # still locked. Report it.
    local _containers="$WSS3_TXN_CONTAINERS" _drc=0
    wss3_txn_discard || _drc=1
    # Containers this transaction created come LAST — after the lock is
    # released, because the lock is itself a directory inside the profile
    # container and `rmdir` refuses a container that still holds it. The
    # records inside have already been restored or removed by now, so an
    # empty container is this run's to take back and a non-empty one
    # belongs to whatever put something in it.
    WSS3_TXN_CONTAINERS="$_containers"
    _wss3_txn_remove_containers
    [ "$_drc" = "0" ] || return 1
    return 0
}

# wss3_assert_canonical_record <root> <profile-id> <kind>
#   Exit 0 if the record is absent, or present and canonical. Sets
#   WSS3_RECORD_ERR otherwise. Says nothing about the value.
wss3_assert_canonical_record() {
    local root="$1" pid="$2" kind="$3" name f
    WSS3_RECORD_ERR=""
    name=$(wss3_record_filename "$kind") || {
        WSS3_RECORD_ERR="$WSS3_ERR_RECORD_SYNTAX"
        return 1
    }
    wss3_assert_store_path "$root" "$pid" "$kind" || return 1
    f="$root/$pid/$name"
    wss3_path_present "$f" || return 0
    if ! wss3_is_plain_file "$f"; then
        WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
        return 1
    fi
    if ! wss3_check_mode "$f" 600; then
        WSS3_RECORD_ERR="$WSS3_ERR_MODE_VIOLATION"
        return 1
    fi
    local hex
    hex=$(wss3_hex_of_file "$f") || {
        WSS3_RECORD_ERR="$WSS3_ERR_RECORD_CORRUPT"
        return 1
    }
    wss3_record_check_bytes "$hex" || return 1
    wss3_record_check_syntax "$kind" "$(cat "$f")" || return 1
    return 0
}

# wss3_journal_capture <journal-dir> <root> <profile-id> <kind>
#   Refuses a non-canonical pre-state, then records existence, bytes and
#   mode under `<journal-dir>/<kind>`.
wss3_journal_capture() {
    local jdir="$1" root="$2" pid="$3" kind="$4" name
    wss3_assert_canonical_record "$root" "$pid" "$kind" || return 1
    name=$(wss3_record_filename "$kind") || return 1
    _wss3_snapshot_record "$root/$pid/$name" "$jdir/$kind"
}

# wss3_journal_restore <journal-dir> <root> <profile-id> <kind>
#   Puts a captured record back exactly — bytes and mode — or removes it
#   again if it was absent when captured.
wss3_journal_restore() {
    local jdir="$1" root="$2" pid="$3" kind="$4" name
    name=$(wss3_record_filename "$kind") || return 1
    [ -f "$jdir/$kind.state" ] || return 0
    _wss3_restore_record "$root/$pid/$name" "$jdir/$kind"
}

# wss3_mode_of <path> — the path's octal mode, or empty.
wss3_mode_of() {
    if stat -f "%OLp" "$1" >/dev/null 2>&1; then
        stat -f "%OLp" "$1" 2>/dev/null
    else
        stat -c "%a" "$1" 2>/dev/null
    fi
}

# wss3_assert_snapshot_dir <dir>
#   Audit ROUND-30.10: raw record bytes may only be written into a
#   directory that is ours and is 0700. R30.9's local `mktemp -d` was
#   755 with 644 bytes inside, holding a service name in plaintext.
wss3_assert_snapshot_dir() {
    local d="${1:-}" m
    WSS3_RECORD_ERR=""
    if [ -z "$d" ] || [ -L "$d" ] || [ ! -d "$d" ]; then
        WSS3_RECORD_ERR="$WSS3_ERR_TYPE_VIOLATION"
        return 1
    fi
    if wss3_is_msys_like || [ "${WSS3_SKIP_STRICT_MODE_CHECK:-}" = "1" ]; then
        return 0
    fi
    m=$(wss3_mode_of "$d")
    [ "$m" = "700" ] || { WSS3_RECORD_ERR="$WSS3_ERR_MODE_VIOLATION"; return 1; }
    return 0
}

# _wss3_snapshot_record <path> <snapshot-prefix>
#   Copies the record's exact bytes to <prefix>.bytes and its mode to
#   <prefix>.mode. A path that does not exist snapshots as "absent",
#   which the restore honours by removing whatever is there.
#
#   Audit ROUND-30.10: the containing directory must be 0700 and the
#   bytes file is created at 0600 BEFORE any content reaches it, then
#   verified. Every step reports failure; none is best-effort.
_wss3_snapshot_record() {
    local f="$1" pfx="$2" dir
    dir=$(dirname "$pfx")
    wss3_assert_snapshot_dir "$dir" || return 1
    if ! wss3_path_present "$f"; then
        printf 'absent' > "$pfx.state" || return 1
        return 0
    fi
    wss3_is_plain_file "$f" || return 1
    printf 'present' > "$pfx.state" || return 1
    : > "$pfx.bytes" || return 1
    chmod 0600 "$pfx.bytes" || return 1
    cat "$f" > "$pfx.bytes" || return 1
    wss3_file_bytes_equal_value "$pfx.bytes" "$(cat "$f")" || return 1
    wss3_mode_of "$f" > "$pfx.mode" || return 1
    [ -s "$pfx.mode" ] || printf '600' > "$pfx.mode" || return 1
    return 0
}

# _wss3_restore_record <path> <snapshot-prefix>
#   Audit ROUND-30.10: AUTHORITATIVE. It validates the target's type
#   before mutating, performs each step with its status checked, and
#   verifies the postcondition — exact bytes, exact mode, correct type,
#   or verified absence — before returning 0. R30.9 swallowed the `rm`
#   and `chmod` failures with `|| true`, checked nothing afterwards and
#   always returned success, so a rollback could report a restoration
#   that had not happened.
_wss3_restore_record() {
    local f="$1" pfx="$2" state mode want
    [ -f "$pfx.state" ] || return 0
    state=$(cat "$pfx.state") || return 1
    if [ "$state" = "absent" ]; then
        if wss3_path_present "$f"; then
            # Only ever remove a plain file we could have created. A
            # foreign object of another type is left alone and reported.
            wss3_is_plain_file "$f" || return 1
            rm -f -- "$f" || return 1
        fi
        # Postcondition: verified absence.
        wss3_path_present "$f" && return 1
        return 0
    fi
    if wss3_path_present "$f" && ! wss3_is_plain_file "$f"; then
        return 1
    fi
    cp "$pfx.bytes" "$f" || return 1
    mode=$(cat "$pfx.mode" 2>/dev/null)
    [ -n "$mode" ] || mode=600
    chmod "0$mode" "$f" || return 1
    # Postcondition: type, bytes and mode all as captured.
    wss3_is_plain_file "$f" || return 1
    want=$(wss3_hex_of_file "$pfx.bytes") || return 1
    [ "$(wss3_hex_of_file "$f")" = "$want" ] || return 1
    if ! wss3_is_msys_like && [ "${WSS3_SKIP_STRICT_MODE_CHECK:-}" != "1" ]; then
        [ "$(wss3_mode_of "$f")" = "$mode" ] || return 1
    fi
    return 0
}

# _wss3_unwind_arm_pair <svc-path> <svc-existed> <arm-path> <arm-existed> [snapshot-dir]
#   Audit ROUND-30.8 P0: restores the EXACT pre-state rather than only
#   deleting what was newly created. A record this call created is
#   removed; a record it overwrote is put back with its original bytes
#   and mode. Either way the profile can never be left holding halves of
#   two different arms.
_wss3_unwind_arm_pair() {
    local svc_f="$1" svc_before="$2" arm_f="$3" arm_before="$4" snap_dir="${5:-}"
    if [ -n "$snap_dir" ] && [ -d "$snap_dir" ]; then
        _wss3_restore_record "$svc_f" "$snap_dir/service" \
            || echo "wss3_write_arm: WARNING — could not restore the previous service record" >&2
        _wss3_restore_record "$arm_f" "$snap_dir/arm" \
            || echo "wss3_write_arm: WARNING — could not restore the previous arm-token record" >&2
        rm -rf -- "$snap_dir" 2>/dev/null || true
        return 0
    fi
    if [ "$svc_before" = "0" ] && wss3_path_present "$svc_f"; then
        rm -f -- "$svc_f" 2>/dev/null || true
    fi
    if [ "$arm_before" = "0" ] && wss3_path_present "$arm_f"; then
        rm -f -- "$arm_f" 2>/dev/null || true
    fi
    return 0
}

# wss3_write_tunnel <root> <profile-id> <iface>
#   Audit ROUND-30: persist the OPTIONAL declared Network Extension /
#   TUN interface beside `host-vpn-service`, under the same 0600
#   discipline and for the same reason: the §5 denylist bans raw
#   `utunN` strings from evidence, so the operator types it once at
#   preflight and `smoke`/`full` read it back from `.runtime`.
#   A separate writer keeps `wss3_write_arm`'s accepted signature and
#   its arm/service invariants untouched.
#   Audit ROUND-30.10 rework: the tunnel writer took no lock and opened
#   no transaction of its own, so `resume`'s migration and a concurrent
#   preflight could touch one profile at the same time — the declared
#   per-profile exclusivity did not cover this writer at all. It now
#   enters the same protocol as every other mutation: refuse a foreign
#   transaction, join the owning one, or open and close its own.
wss3_write_tunnel() {
    local root="$1" pid="$2" iface="$3" rc=0 owned_txn=0
    wss3_txn_assert_not_blocked "$root" "$pid" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    wss3_txn_assert_no_foreign "$root" "$pid" wss3_write_tunnel \
        || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    # Audit ROUND-30.10 closure: a bare `.lock` — no BLOCKED marker,
    # just another run holding the profile — refuses this writer too,
    # and it is stated HERE rather than being left to the `mkdir` in
    # the lock acquisition further down. The only exception is the
    # transaction that provably owns that exact lock.
    if ! wss3_txn_owns "$root" "$pid"; then
        wss3_txn_assert_no_live_lock "$root" "$pid" \
            || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    wss3_ensure_profile_dir "$root" "$pid" || return 1
    if ! wss3_txn_owns "$root" "$pid"; then
        wss3_txn_begin "$root" "$pid" || return 1
        owned_txn=1
    fi
    _wss3_write_tunnel_body "$root" "$pid" "$iface" || rc=$?
    if [ "$owned_txn" = "1" ]; then
        if [ "$rc" = "0" ]; then
            wss3_txn_verified
            wss3_txn_commit || rc=1
        else
            # The body's own exit code is what the caller acts on — a
            # rejected declaration is rc=2 whatever happens next. A
            # rollback that then fails announces itself, marks the
            # profile terminal and blocks later verbs, so it is never
            # silent even though it does not overwrite rc here.
            wss3_txn_rollback || true
        fi
    fi
    return "$rc"
}

_wss3_write_tunnel_body() {
    local root="$1" pid="$2" iface="$3"
    if [ -z "$iface" ]; then
        # Audit ROUND-30.1 P0-4 / ROUND-30.2 P0-4: an empty
        # declaration must REMOVE any stale file, and must REPORT
        # failure if it cannot. `rm -f ... || true` returned success
        # while an immutable file survived, silently carrying a
        # previous declaration into a later attempt.
        #
        # Audit ROUND-30.9 P0: the REMOVAL goes through the same
        # authority as a write. R30.8 called `rm -f` here before any
        # containment or type check ran — the checks below execute only
        # for a non-empty value — so an empty declaration deleted a
        # foreign symlink sitting at the record path. Deleting is a
        # mutation like any other: it happens only to a canonical record
        # this tooling recognises as its own.
        if ! wss3_assert_canonical_record "$root" "$pid" tunnel; then
            echo "$WSS3_RECORD_ERR" >&2
            echo "wss3_write_tunnel: refusing to remove a non-canonical object" >&2
            return 2
        fi
        local f="$root/$pid/host-vpn-tunnel"
        if wss3_path_present "$f"; then
            wss3_txn_mutating
            rm -f "$f" 2>/dev/null || true
            if wss3_path_present "$f"; then
                echo "wss3_write_tunnel: stale host-vpn-tunnel could not be removed" >&2
                return 1
            fi
        fi
        return 0
    fi
    if ! wss3_record_check_value tunnel "$iface"; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_write_tunnel: declaration rejected ($WSS3_RECORD_REDACTED)" >&2
        return 2
    fi
    local f="$root/$pid/host-vpn-tunnel"
    # ── Audit ROUND-30.5 P0: reject a non-regular target BEFORE the
    # atomic write is even attempted. `wss3_atomic_write_0600` refuses
    # it too; this is the diagnosis at the layer that knows what the
    # path means — a directory, symlink or FIFO here is corrupted
    # runtime state, not a declaration to overwrite.
    #
    # Audit ROUND-30.7: containment covers the whole path, not just the
    # leaf — a symlinked root or profile directory would land the write
    # outside the store with every per-file check still satisfied.
    if ! wss3_assert_contained_record_path "$root" "$pid" tunnel; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_write_tunnel: record path is not contained" >&2
        return 2
    fi
    # Audit ROUND-30.9 P0: a non-canonical existing record is refused,
    # never repaired. R30.8 replaced a 0644 file and fixed its mode on
    # the way past.
    if ! wss3_assert_canonical_record "$root" "$pid" tunnel; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_write_tunnel: existing record is not canonical" >&2
        return 2
    fi
    # An overwrite is a mutation exactly like a creation; the state
    # machine hears about it before the bytes land, not afterwards.
    if ! wss3_path_present "$f"; then
        wss3_txn_created "$f" || return 1
    fi
    wss3_txn_mutating
    wss3_atomic_write_0600 "$f" "$iface" || return 1
    # ── Audit ROUND-30.5 P0: postcondition. `rc=0` is a claim that the
    # declaration IS on disk, so prove it: a plain regular file, mode
    # 0600, holding exactly the declared interface. Anything else is a
    # failed write no matter what the primitives returned.
    if ! wss3_is_plain_file "$f"; then
        echo "$WSS3_ERR_TYPE_VIOLATION" >&2
        echo "wss3_write_tunnel: '$f' is not a regular file after the write" >&2
        return 1
    fi
    if ! wss3_check_mode "$f" 600; then
        echo "$WSS3_ERR_MODE_VIOLATION" >&2
        return 1
    fi
    # Audit ROUND-30.6: RAW BYTES, not `$(cat …)`. Command substitution
    # strips trailing newlines, so the old comparison accepted a file
    # holding `utun4\n` as byte-identical to `utun4`.
    #
    # Audit ROUND-30.7: and the mismatch is reported WITHOUT the bytes.
    # Printing both hex strings here disclosed the declared interface in
    # full, which §5 bans from any output for the same reason it bans it
    # from evidence.
    if ! wss3_file_bytes_equal_value "$f" "$iface"; then
        echo "wss3_write_tunnel: post-write byte comparison failed ($WSS3_RECORD_REDACTED)" >&2
        return 1
    fi
    return 0
}

# wss3_validate_tunnel_iface <iface>
#   Audit ROUND-30.1 P0-4: exact syntax. utunN | tunN | ipsecN only.
#
#   Audit ROUND-30.6: THE authority for tunnel-declaration syntax. The
#   record guard runs first so the pattern applies to the whole value —
#   `garbage\nutun4`, `utun4\ngarbage` and `utun4\n` are all rejected.
#   `lib/wss3-args.sh` calls this function rather than carrying its own
#   copy of the pattern, and fixture `wss3-r306-one-tunnel-syntax-authority`
#   pins that no second copy reappears.
#   Audit ROUND-30.7: a thin call into the one authority, and the
#   predicate is now the single provider-neutral interface family
#   `utunN|tunN|tapN|pppN|ipsecN`. The declaration rule and the
#   host-OFF recognition rule were two different lists until this
#   round; `lib/wss3-host-vpn.sh` reads the same constant.
wss3_validate_tunnel_iface() {
    wss3_record_check_value tunnel "${1:-}"
}

# wss3_read_tunnel <root> <profile-id>
#   Prints the stored tunnel interface, or nothing when none was
#   declared. Absence is NOT an error: the tunnel path is a fallback,
#   and a profile attested by the legacy scutil path has no tunnel
#   file at all. A present-but-wrong-mode file IS an error.
#
#   Audit ROUND-30.5 P0: ABSENT and CORRUPTED are different answers.
#   The old `[ -f "$f" ] || return 0` collapsed them: a directory, a
#   FIFO, a dangling symlink — anything not a regular file — reported
#   "nothing declared" and the caller carried on. Only a path with
#   nothing at it is absence; anything else present is corrupted
#   runtime state and fails closed at exit 4.
#   Audit ROUND-30.7: containment first, then the one authority — which
#   applies the tunnel syntax rule itself, so there is no second check
#   here to drift. A non-regular path is a TYPE violation, not a mode
#   one; the two were conflated.
wss3_read_tunnel() {
    local root="$1" pid="$2"
    wss3_txn_assert_not_blocked "$root" "$pid" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    wss3_txn_assert_no_live_lock "$root" "$pid" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    local f="$root/$pid/host-vpn-tunnel"
    if ! wss3_path_present "$f"; then
        return 0
    fi
    if ! wss3_assert_contained_record_path "$root" "$pid" tunnel; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_read_tunnel: record path is not contained" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_check_mode "$f" 600; then
        echo "$WSS3_ERR_MODE_VIOLATION" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    wss3_read_record tunnel "$f" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    return 0
}

# wss3_read_service <root> <profile-id>
#   Prints the stored service on stdout on success; emits an error
#   code (one of WSS3_ERR_*) on stderr and returns
#   WSS3_EXIT_PREARM_CLI_FAILURE (4) on any failure.
#
#   Audit ROUND-30.6: the service is a byte-exact single-value record
#   too. It stays provider-neutral — spaces and non-ASCII characters are
#   ordinary in a macOS service name and remain legal; only framing
#   bytes (NUL, CR, LF, other controls) are corruption. No provider name
#   is matched or special-cased.
wss3_read_service() {
    local root="$1" pid="$2"
    wss3_txn_assert_not_blocked "$root" "$pid" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    wss3_txn_assert_no_live_lock "$root" "$pid" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    local f="$root/$pid/host-vpn-service"
    if ! wss3_path_present "$f"; then
        echo "$WSS3_ERR_SERVICE_MISSING" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_assert_contained_record_path "$root" "$pid" service; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_read_service: record path is not contained" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_check_mode "$f" 600; then
        echo "$WSS3_ERR_MODE_VIOLATION" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_check_mode "$root/$pid" 700; then
        echo "$WSS3_ERR_MODE_VIOLATION" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_check_mode "$root" 700; then
        echo "$WSS3_ERR_MODE_VIOLATION" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    wss3_read_record service "$f" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    return 0
}

# wss3_read_arm_token <root> <profile-id>
#   Same failure semantics as wss3_read_service, with its OWN missing
#   code.
#
#   Audit ROUND-30.7: an absent arm-token used to be reported as
#   `host_vpn_service_missing`, which sends the operator to look at a
#   file that is sitting right there. It is now
#   `runtime_state_arm_token_missing`. A malformed token is a record
#   failure from the one authority, not a "stale arm": staleness means
#   a VALID token that belongs to another attempt.
wss3_read_arm_token() {
    local root="$1" pid="$2"
    wss3_txn_assert_not_blocked "$root" "$pid" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    wss3_txn_assert_no_live_lock "$root" "$pid" || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    local f="$root/$pid/arm-token"
    if ! wss3_path_present "$f"; then
        echo "$WSS3_ERR_ARM_TOKEN_MISSING" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_assert_contained_record_path "$root" "$pid" arm-token; then
        echo "$WSS3_RECORD_ERR" >&2
        echo "wss3_read_arm_token: record path is not contained" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_check_mode "$f" 600; then
        echo "$WSS3_ERR_MODE_VIOLATION" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    # Audit ROUND-30.6: byte-exact read. A token file carrying a
    # trailing newline used to read back as a clean UUID.
    local t rec_rc=0
    t=$(wss3_read_record arm-token "$f") || rec_rc=$?
    if [ "$rec_rc" != "0" ]; then
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    if ! wss3_validate_arm_token "$t"; then
        echo "$WSS3_ERR_STALE_ARM" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    printf '%s' "$t"
    return 0
}

# wss3_verify_arm_matches_profile <root> <profile-id> <profile-json-arm-token>
#   Exit 0 if `.runtime/wss3/<profile-id>/arm-token` matches the given
#   PROFILE.json.arm_token. Returns WSS3_EXIT_PREARM_CLI_FAILURE with
#   WSS3_ERR_STALE_ARM on mismatch.
wss3_verify_arm_matches_profile() {
    local root="$1" pid="$2" profile_token="$3"
    local runtime_token
    runtime_token=$(wss3_read_arm_token "$root" "$pid") || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    if [ "$runtime_token" != "$profile_token" ]; then
        echo "$WSS3_ERR_STALE_ARM" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    return 0
}

# wss3_verify_service_matches_cli <root> <profile-id> <cli-service-or-empty>
#   Exit 0 if the CLI value is empty (implicit pickup) OR matches the
#   stored value. Returns 4 with WSS3_ERR_MISMATCH otherwise.
wss3_verify_service_matches_cli() {
    local root="$1" pid="$2" cli_service="$3"
    if [ -z "$cli_service" ]; then
        return 0
    fi
    local stored
    stored=$(wss3_read_service "$root" "$pid") || return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    if [ "$stored" != "$cli_service" ]; then
        echo "$WSS3_ERR_MISMATCH" >&2
        return "$WSS3_EXIT_PREARM_CLI_FAILURE"
    fi
    return 0
}

# wss3_remove_profile_runtime <root> <profile-id>
#   Fail-safe removal — no error if the directory doesn't exist.
# wss3_removal_release_claim
#   Gives back a tombstone NAME that was claimed but never used, which is
#   the window between claiming it and the rename. `rmdir` only succeeds
#   on an empty directory, so a claim that somehow holds something is
#   left alone and reported.
wss3_removal_release_claim() {
    local claim="${WSS3_REMOVAL_CLAIM:-}"
    [ -n "$claim" ] || return 0
    WSS3_REMOVAL_CLAIM=""
    [ -d "$claim" ] || return 0
    if ! rmdir "$claim" 2>/dev/null; then
        echo "$WSS3_ERR_TXN_CLEANUP" >&2
        echo "removal: the claimed name at $claim could not be released" >&2
        return 1
    fi
    return 0
}

# _wss3_store_entry_kind <name>
#   Classifies one top-level entry of the runtime store by an EXACT
#   grammar. Audit ROUND-30.10 closure: the first version of the
#   enumeration skipped every name beginning with a dot, which meant any
#   hidden object at all — a half-copied directory, something an operator
#   put there, a tombstone from a build that named them differently —
#   silently disappeared from `resume` and from `cleanup`, and
#   `cleanup --all` could then report success over content it had never
#   looked at. Only what this tooling itself creates is recognised.
#
#     profile   an ordinary profile id: no leading dot, no slash
#     tombstone `.removing-<profile-id>.<digits>`
#     claim     `.removing-<profile-id>.<digits>.claim`
#     unknown   anything else — fail closed
_wss3_store_entry_kind() {
    local name="$1" rest
    case "$name" in
        ""|.|..) printf 'unknown'; return 0 ;;
    esac
    case "$name" in
        "$WSS3_TOMBSTONE_PREFIX"*)
            rest=${name#"$WSS3_TOMBSTONE_PREFIX"}
            case "$rest" in
                *.claim)
                    rest=${rest%.claim}
                    case "${rest##*.}" in
                        ''|*[!0-9]*) printf 'unknown'; return 0 ;;
                    esac
                    case "${rest%.*}" in
                        ''|.*|*/*) printf 'unknown'; return 0 ;;
                    esac
                    printf 'claim'
                    return 0
                    ;;
                *)
                    case "${rest##*.}" in
                        ''|*[!0-9]*) printf 'unknown'; return 0 ;;
                    esac
                    case "${rest%.*}" in
                        ''|.*|*/*) printf 'unknown'; return 0 ;;
                    esac
                    printf 'tombstone'
                    return 0
                    ;;
            esac
            ;;
        .*) printf 'unknown'; return 0 ;;
    esac
    printf 'profile'
    return 0
}

# wss3_assert_store_recognised <root>
#   Exit 0 only when every top-level entry of the store is something this
#   tooling created. A verb that walks the store calls this FIRST, so an
#   unrecognised object stops it instead of being skipped: `cleanup --all`
#   reporting success while an unhandled directory sits in the store is
#   exactly the fail-open this closes. The diagnostic names the entry —
#   it is a directory name this tooling did not create, not a record
#   value — but never its contents.
wss3_assert_store_recognised() {
    local root="$1" d name kind bad=0
    [ -d "$root" ] || return 0
    while IFS= read -r d; do
        [ -n "$d" ] || continue
        name=$(basename "$d")
        kind=$(_wss3_store_entry_kind "$name")
        if [ "$kind" = "unknown" ]; then
            echo "$WSS3_ERR_TYPE_VIOLATION" >&2
            echo "runtime store: '$name' is not a profile or a tombstone this tooling created" >&2
            bad=$((bad + 1))
        fi
    done <<EOF
$(find "$root" -mindepth 1 -maxdepth 1 -print 2>/dev/null)
EOF
    [ "$bad" = "0" ] || return 1
    return 0
}

# wss3_removal_finish_tombstone
#   Completes a removal that was interrupted after its rename. There is
#   no canonical profile to restore, so the only question is whether the
#   tombstone can be deleted. If it cannot, it is retained at 0700 with a
#   marker and the failure is reported — the data is never abandoned in
#   a readable state and never reported as gone.
wss3_removal_finish_tombstone() {
    local tomb="${WSS3_REMOVAL_TOMB:-}"
    [ -n "$tomb" ] || return 0
    rm -rf -- "$tomb" 2>/dev/null
    if [ -e "$tomb" ]; then
        chmod 0700 "$tomb" 2>/dev/null || true
        if : > "$tomb/$WSS3_RECOVERY_MARKER_NAME" 2>/dev/null; then
            chmod 0600 "$tomb/$WSS3_RECOVERY_MARKER_NAME" 2>/dev/null || true
        fi
        echo "$WSS3_ERR_TXN_CLEANUP" >&2
        echo "removal: the removed profile is retained at $tomb — remove it by hand" >&2
        WSS3_REMOVAL_TOMB=""
        return 1
    fi
    WSS3_REMOVAL_TOMB=""
    return 0
}

wss3_remove_profile_runtime() {
    local root="$1" pid="$2" tomb claim
    [ -d "$root/$pid" ] || return 0
    # ── Audit ROUND-30.10 closure: removal is a mutation ───────────────
    # `cleanup` reached this with a bare `rm -rf` and no protocol at all.
    # It therefore destroyed a profile left in the terminal
    # ROLLBACK_FAILED state — the BLOCKED marker AND the only copy of the
    # recovery data with it — and it could delete a profile another run
    # was in the middle of changing. Everything else in this module
    # refuses a blocked profile; the verb whose whole job is deletion
    # must refuse it hardest.
    if ! wss3_txn_assert_not_blocked "$root" "$pid"; then
        echo "wss3_remove_profile_runtime: refusing to delete a profile held for recovery" >&2
        return 1
    fi
    if ! wss3_txn_assert_no_live_lock "$root" "$pid"; then
        echo "wss3_remove_profile_runtime: refusing to delete a profile another transaction holds" >&2
        return 1
    fi
    if ! wss3_txn_lock_acquire "$root" "$pid"; then
        echo "wss3_remove_profile_runtime: refusing to delete a profile another transaction holds" >&2
        return 1
    fi
    # ── the lock must outlive what it protects ─────────────────────────
    # Audit ROUND-30.10 closure: the lock lives INSIDE the profile
    # directory, so deleting that directory destroys the lock partway
    # through its own deletion. From that instant the profile path is
    # unguarded while `rm -rf` is still walking it, and another run is
    # free to create the profile again underneath — into a tree being
    # deleted.
    #
    # A single atomic rename closes that window. After it the profile
    # path is simply absent: a concurrent run that creates it again gets
    # a genuinely new profile, which is correct and harmless, while
    # everything this call is deleting — the lock included — has already
    # moved out of the way under a name nothing else looks up.
    tomb="$root/$WSS3_TOMBSTONE_PREFIX$pid.$$"
    claim="$tomb.claim"
    # The NAME is claimed atomically before anything moves. `[ -e ]`
    # alone would leave a window in which the name appears between the
    # test and the rename — and `mv src dir` moves src INSIDE dir when
    # the destination exists, which would bury a live profile inside
    # another run's tombstone instead of failing.
    if ! mkdir "$claim" 2>/dev/null; then
        wss3_txn_lock_release
        echo "wss3_remove_profile_runtime: a removal for this profile is already in flight" >&2
        return 1
    fi
    WSS3_REMOVAL_CLAIM="$claim"
    if [ -e "$tomb" ]; then
        wss3_removal_release_claim
        wss3_txn_lock_release
        echo "wss3_remove_profile_runtime: a stale removal for this profile is in the way" >&2
        return 1
    fi
    if ! mv "$root/$pid" "$tomb" 2>/dev/null; then
        wss3_removal_release_claim
        wss3_txn_lock_release
        echo "wss3_remove_profile_runtime: could not take the profile out of the way" >&2
        return 1
    fi
    # ── past the commit boundary ───────────────────────────────────────
    # The canonical profile no longer exists, so there is nothing left to
    # restore INTO and record-level rollback is meaningless from here on.
    # A signal arriving now must finish or preserve the tombstone, not
    # try to put records back at a path that is gone; `WSS3_REMOVAL_TOMB`
    # is what tells the shared cleanup which of the two it is looking at.
    WSS3_REMOVAL_TOMB="$tomb"
    # The lock moved with the tree, so this process no longer holds a
    # path anything can look up; the global is cleared by hand rather
    # than through `wss3_txn_lock_release`, which would go looking for a
    # directory that is deliberately no longer there.
    WSS3_TXN_LOCK=""
    wss3_removal_release_claim
    # One finisher serves both this path and the signal path, so an
    # interrupted removal and a completed one cannot disagree about
    # what happens to the tombstone.
    wss3_removal_finish_tombstone || return 1
    return 0
}

# wss3_remove_all_runtime was REMOVED in audit ROUND-30.10 closure.
#
# It deleted the whole runtime root with a single `rm -rf` and had no
# caller anywhere in the package — which is precisely why nothing had
# ever noticed. Making it safe needed a root-wide lock that every
# transaction and every reader would have to honour: a new exclusion
# concept, introduced late, to protect a code path nobody used. Even
# two-phase it kept a TOCTOU window, because a profile could be
# claimed between the phase that inspected it and the phase that
# deleted it.
#
# `cleanup` removes profiles ONE at a time through
# `wss3_remove_profile_runtime`, which holds that profile's lock and
# refuses a blocked or held one. `cleanup --all` differs only in which
# profiles it offers to that same authority. There is no root-wide
# destructive primitive to get wrong.

# wss3_list_profile_ids <root>
#   Prints one profile-id per line, or nothing if the root has no
#   subdirs. Sorted so callers get deterministic order.
wss3_list_profile_ids() {
    local root="$1" d name
    [ -d "$root" ] || return 0
    # Audit ROUND-30.10 closure: an entry is emitted only when the
    # grammar recognises it as a PROFILE. Tombstones and the names a
    # removal has claimed are this module's own bookkeeping and are not
    # profiles; anything else is not silently dropped here either —
    # `wss3_assert_store_recognised` is what refuses it, and every verb
    # that walks the store asks that first.
    find "$root" -mindepth 1 -maxdepth 1 -type d -print 2>/dev/null \
        | while IFS= read -r d; do
              name=$(basename "$d")
              [ "$(_wss3_store_entry_kind "$name")" = "profile" ] || continue
              printf "%s\n" "$name"
          done \
        | LC_ALL=C sort
}
