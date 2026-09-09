#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 Carrier x VPN Matrix — evidence-isolation invariant sweeps.
#
# Contract: §3.6 "Evidence isolation invariant" + §5 denylist +
# REDLINE-6 blocker 3 (fixed-string search, defence-in-depth tar
# absence, no-raw-in-report discipline).

: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
    # shellcheck source=portable.sh
    source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
    PORTABLE_SH_LOADED=1
fi

# wss3_evidence_sweep_raw_value <evidence-root> <raw-service-value>
#   Fixed-string search for the raw --host-vpn-service value across
#   every file under evidence-root. Prints:
#       ok
#   on success (zero matches). On any hit, prints file paths of hits
#   to stdout (one per line) AND returns non-zero. The raw value is
#   NEVER echoed to stdout / stderr by this function — only the
#   redacted marker `<RAW_SERVICE_VALUE_REDACTED>` (REDLINE-6 blocker 3
#   no-raw-in-report discipline).
#
# Uses `grep -rF -- "$val"` to disable regex interpretation and to
# survive service names starting with `-`.
wss3_evidence_sweep_raw_value() {
    local root="$1" val="$2"
    if [ -z "$val" ]; then
        echo "wss3-evidence: raw service value required" >&2
        return 2
    fi
    if [ ! -d "$root" ]; then
        # No evidence tree at all is trivially OK.
        echo "ok"
        return 0
    fi
    local hits
    # NB: process substitution instead of pipe so the caller sees the
    # per-file list; `-l` prints paths only (never the raw match).
    hits=$(grep -rlF -- "$val" "$root" 2>/dev/null || true)
    if [ -n "$hits" ]; then
        printf '%s\n' "$hits"
        echo "wss3-evidence: raw <RAW_SERVICE_VALUE_REDACTED> found in the paths above" >&2
        return 1
    fi
    echo "ok"
    return 0
}

# wss3_evidence_sweep_checkpoint_key_substring <evidence-root>
#   Sweep for any occurrence of `checkpoint_key`, `checkpoint_key_hex`,
#   `checkpoint-key`, `checkpointKey`, `checkpointKeyHex` in evidence
#   files. Returns hits list on stdout + non-zero exit on failure.
wss3_evidence_sweep_checkpoint_key_substring() {
    local root="$1"
    if [ ! -d "$root" ]; then
        echo "ok"; return 0
    fi
    local hits
    hits=$(grep -rlE 'checkpoint_key|checkpoint-key|checkpointKey' "$root" 2>/dev/null || true)
    if [ -n "$hits" ]; then
        printf '%s\n' "$hits"
        return 1
    fi
    echo "ok"; return 0
}

# wss3_evidence_full_privacy_sweep <evidence-root>
#   Audit ROUND-18 P0-4: full producer-side privacy validator. Shells
#   out to the verifier module's `check_privacy_denylist` +
#   `_scan_denylist_strings` so producer and verifier share ONE
#   denylist source of truth (no drift between "what we wrote" and
#   "what we later reject"). Returns non-zero on any hit; prints
#   the hit list (paths + reasons) to stdout. Rejects credential
#   aliases, raw IP literals, IPv6 forms, 64-hex identity shapes,
#   session-token/username/vpn-provider text patterns, etc. before
#   any evidence dir is declared "full success".
wss3_evidence_full_privacy_sweep() {
    local root="$1"
    if [ ! -d "$root" ]; then
        echo "ok"; return 0
    fi
    local pkg_dir; pkg_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    local rc
    WSS3_PRIVACY_ROOT="$root" WSS3_PKG_DIR="$pkg_dir" \
        python3 - >&2 <<'PY' && rc=0 || rc=$?
import os, sys
sys.path.insert(0, os.environ["WSS3_PKG_DIR"])
import verify_evidence_wss3 as V
root = os.environ["WSS3_PRIVACY_ROOT"]
hits = V.check_privacy_denylist(root)
for h in hits:
    print("wss3-evidence-privacy: " + h, file=sys.stderr)
sys.exit(0 if not hits else 1)
PY
    if [ "$rc" != "0" ]; then
        return 1
    fi
    echo "ok"; return 0
}

# wss3_evidence_sweep_tar_body_raw_value <tarball-path> <raw-value>
#   Same fixed-string sweep over the extracted contents of a tar.gz.
#   Discipline: raw value never echoed.
wss3_evidence_sweep_tar_body_raw_value() {
    local tar="$1" val="$2"
    [ -f "$tar" ] || { echo "wss3-evidence: tar not found: $tar" >&2; return 2; }
    [ -n "$val" ] || { echo "wss3-evidence: raw value required" >&2; return 2; }
    # Extract entire body to stdout (`-O`) and fixed-string grep.
    if tar -xzf "$tar" -O 2>/dev/null | grep -qF -- "$val"; then
        echo "wss3-evidence: raw <RAW_SERVICE_VALUE_REDACTED> found inside tar body $tar" >&2
        return 1
    fi
    echo "ok"; return 0
}

# wss3_evidence_tar_runtime_absent <tarball-path>
#   Defence-in-depth: `tar -tzf | grep '(^|/)\.runtime/'` MUST be
#   empty. The .runtime/ subtree must never be packaged into a
#   handoff tarball.
wss3_evidence_tar_runtime_absent() {
    local tar="$1"
    [ -f "$tar" ] || { echo "wss3-evidence: tar not found: $tar" >&2; return 2; }
    local hits
    hits=$(tar -tzf "$tar" 2>/dev/null | grep -E '(^|/)\.runtime/' || true)
    if [ -n "$hits" ]; then
        printf '%s\n' "$hits"
        echo "wss3-evidence: .runtime/ entries present in tar — MUST be absent (REDLINE-5 blocker 1)" >&2
        return 1
    fi
    echo "ok"; return 0
}
