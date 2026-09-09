#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Portable macOS-safe handoff-tar builder.
#
# §12 Round-5 audit P1: the earlier builder used GNU-only tar flags
# (--force-local, --show-transformed-names) and refused every APK
# outright, so it could neither run on stock macOS `tar` nor produce
# the FINAL self-contained operator package (which must ship exactly
# one debug APK + its `.sha256`).
#
# This rewrite has two modes:
#
#   --review              Package the operator scripts ONLY
#                         (no APK). Same intent as the prior
#                         Round-3/4 handoff. Use during audit rounds.
#
#   --final --apk <PATH>  Package the operator scripts PLUS exactly
#                         one debug APK at <PATH> and its <PATH>.sha256
#                         sidecar. Use after LOGICAL GREEN, right
#                         before the Mac dry-run + Yota-pass.
#
# Content comes from git-index blobs (`git archive`), NOT from the
# working tree. This bypasses core.autocrlf entirely so a Windows
# checkout cannot inject CRLF into a Mac-bound tarball.
#
# Uses only POSIX / BSD-compatible tar flags. Avoids `--force-local`
# by staging + writing at a colon-free /tmp location, then `mv`ing
# to the destination.
#
# Every produced tar goes through:
#   * CR-byte scan across every packaged .sh / .py
#   * cache/OS/junk scan (rejects .DS_Store, __pycache__, *.pyc,
#     and — in review mode only — every .apk)
#   * dry-extract validation: the produced archive is extracted to
#     a scratch dir and its shell + python fixture suites re-run
#     from that extracted copy
#
# Usage:
#   ./build-handoff-tar.sh --review <out.tar.gz> [--ref <git-ref>]
#   ./build-handoff-tar.sh --final --apk <apk-path> <out.tar.gz>
#                          [--ref <git-ref>]

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"

MODE=""
OUT_PATH=""
APK_PATH=""
REF="HEAD"

usage() {
    cat >&2 <<USAGE
usage:
  $0 --review <out.tar.gz> [--ref <git-ref>]
  $0 --final --apk <apk-path> <out.tar.gz> [--ref <git-ref>]
USAGE
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --review) MODE="review"; shift ;;
        --final)  MODE="final"; shift ;;
        --apk)    APK_PATH="$2"; shift 2 ;;
        --ref)    REF="$2"; shift 2 ;;
        -h|--help) usage ;;
        *)
            if [ -z "$OUT_PATH" ]; then
                OUT_PATH="$1"; shift
            else
                echo "unexpected arg: $1" >&2; usage
            fi
            ;;
    esac
done

[ -n "$MODE" ]     || { echo "missing --review|--final" >&2; usage; }
[ -n "$OUT_PATH" ] || { echo "missing <out.tar.gz>" >&2; usage; }
if [ "$MODE" = "final" ]; then
    [ -n "$APK_PATH" ]     || { echo "--final requires --apk <path>" >&2; usage; }
    [ -f "$APK_PATH" ]     || { echo "APK not found: $APK_PATH" >&2; exit 1; }
    [ -f "$APK_PATH.sha256" ] || { echo "APK sha256 sidecar not found: $APK_PATH.sha256" >&2; exit 1; }
fi

echo "mode=$MODE out=$OUT_PATH ref=$REF"

# 1. Portable-tmp workspace at a colon-free path so BSD tar's -f
#    parser doesn't treat the drive prefix as a remote host.
TMP="$(mktemp -d)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$TMP" "$STAGE"' EXIT

# 2. Extract operator-package tree from git-index (blobs are LF).
#
# Audit ROUND-30.4 P1: `git archive` HONOURS `core.autocrlf`. With
# `core.autocrlf=true` — the default on a Windows clone — every packaged
# text file that is not pinned by an `eol=lf` attribute came out CRLF,
# so `.md`, `.json`, `.gitattributes` and `.gitignore` shipped with line
# endings the index blobs do not have. The `.sh`/`.py` files were LF
# only because this package's `.gitattributes` pins them. Disabling both
# conversion knobs for this one command makes the archive byte-identical
# to the index blobs for EVERY file, on any host. Step 6a then proves it
# rather than trusting it.
ARCHIVE_PREFIX="docs/tracks/direct-wss/operator-package"
git -C "$REPO_ROOT" -c core.autocrlf=false -c core.eol=lf \
    archive --format=tar "$REF" -- "$ARCHIVE_PREFIX" \
  | (cd "$TMP" && tar -xf -)

# 3. Reshape into <STAGE>/operator-package/... plus the contract sheet.
mkdir -p "$STAGE/operator-package"
cp -R "$TMP/docs/tracks/direct-wss/operator-package/." "$STAGE/operator-package/"
# Audit ROUND-30.4 P1: `git show <rev>:<path>` applies the same
# working-tree conversion as a checkout. `git cat-file blob` writes the
# blob byte-for-byte, which is what "assembled from index blobs" has to
# mean. Both contract copies below go through it.
wss3_cat_index_blob() {
    local path="$1" out="$2" sha
    sha=$(git -C "$REPO_ROOT" rev-parse "$REF:$path") || return 1
    git -C "$REPO_ROOT" cat-file blob "$sha" > "$out"
}
wss3_cat_index_blob "docs/tracks/direct-wss/direct-wss-yota-contract.md" \
    "$STAGE/direct-wss-yota-contract.md"
# Audit ROUND-10 P1-9: the WSS-3 fixture suite greps
# `wss-3-carrier-vpn-matrix-contract.md` from an OPERATOR-PACKAGE-
# local path (per `wss3_contract_path` in tests/test_shell_wss3.sh
# line 121-138). Without this, ~11 contract-invariant fixtures fail
# on a fresh extraction. Ship it alongside the tests dir so
# `$WSS3_TEST_ROOT/wss-3-carrier-vpn-matrix-contract.md` resolves.
wss3_cat_index_blob "docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md" \
    "$STAGE/operator-package/wss-3-carrier-vpn-matrix-contract.md"

# 4. Final-mode: stage EXACTLY the supplied APK + sha256 pair.
if [ "$MODE" = "final" ]; then
    # Verify the sidecar matches locally computed hash.
    supplied_hash=$(awk '{print $1}' < "$APK_PATH.sha256")
    if [ -z "$supplied_hash" ]; then
        echo "empty .sha256 file: $APK_PATH.sha256" >&2; exit 1
    fi
    computed_hash=$(bash "$STAGE/operator-package/lib/portable.sh" \
                    && sha256sum "$APK_PATH" 2>/dev/null \
                    || shasum -a 256 "$APK_PATH")
    # Re-derive via the operator-package portable helper for parity
    # with the runner's own hashing.
    # shellcheck source=lib/portable.sh
    source "$STAGE/operator-package/lib/portable.sh"
    computed_hash=$(sha256_file "$APK_PATH")
    if [ "$supplied_hash" != "$computed_hash" ]; then
        echo "APK sha mismatch: supplied=$supplied_hash computed=$computed_hash" >&2
        exit 1
    fi
    cp "$APK_PATH"        "$STAGE/operator-package/android-debug-diagnostic.apk"
    cp "$APK_PATH.sha256" "$STAGE/operator-package/android-debug-diagnostic.apk.sha256"
    echo "final: staged APK sha256=$supplied_hash"
fi

# 5. Junk-file scan (defence in depth).
#
# Audit ROUND-30.16: the local `PhantomMessaging:W` capture carries
# exception text and stack traces and must never be packaged. Keeping it
# out by writing it elsewhere is a convention; this makes it a gate. A
# planted raw capture file, or the directory that holds them, fails the
# build instead of riding along unnoticed.
JUNK_PATTERNS=(-name ".DS_Store" -o -name "__pycache__" -o -name "*.pyc"
               -o -name "*.PhantomMessaging.log" -o -name ".local-diag"
               -o -name "phantom-wss3-local-diag" -o -name "CAPTURE-FAILED")
if [ "$MODE" = "review" ]; then
    JUNK_PATTERNS+=(-o -name "*.apk" -o -name "*.apk.sha256")
fi
junk=$(find "$STAGE" \( "${JUNK_PATTERNS[@]}" \) -print)
if [ -n "$junk" ]; then
    echo "build FAILED: unwanted files in stage:" >&2
    echo "$junk" >&2
    exit 1
fi

# 6. CR-byte scan across every packaged .sh / .py (executables
#    are the strict LF guarantee; textual files are scanned in
#    review too as a sanity net).
cr_offenders=0
while IFS= read -r -d '' f; do
    if LC_ALL=C grep -l $'\r' "$f" > /dev/null 2>&1; then
        echo "build FAILED: CR bytes in $f (git-index blob should be LF)" >&2
        cr_offenders=$((cr_offenders+1))
    fi
done < <(find "$STAGE" \( -name "*.sh" -o -name "*.py" \) -type f -print0)
if [ "$cr_offenders" -ne 0 ]; then
    exit 1
fi

# 6a. INDEX-BLOB PARITY GATE (audit ROUND-30.4 P1).
#
# The scan above covers two extensions; this covers every tracked file
# the package ships. For each path tracked at <REF> under the archived
# prefix, the staged bytes must hash identically to the index blob. Any
# difference — a CRLF conversion, a truncation, a stray edit — fails the
# build. Untracked additions (the two contract copies, and in final mode
# the APK pair) are listed explicitly and checked separately, so nothing
# is silently exempt.
parity_checked=0
parity_bad=0
while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    staged="$STAGE/operator-package/${rel#$ARCHIVE_PREFIX/}"
    if [ ! -f "$staged" ]; then
        echo "build FAILED: tracked file missing from stage: $rel" >&2
        parity_bad=$((parity_bad+1))
        continue
    fi
    blob_sha=$(git -C "$REPO_ROOT" rev-parse "$REF:$rel")
    want=$(git -C "$REPO_ROOT" cat-file blob "$blob_sha" | sha256sum | awk '{print $1}')
    got=$(sha256sum < "$staged" | awk '{print $1}')
    if [ "$want" != "$got" ]; then
        echo "build FAILED: not byte-identical to the index blob: $rel" >&2
        echo "  index=$want staged=$got" >&2
        parity_bad=$((parity_bad+1))
    fi
    parity_checked=$((parity_checked+1))
done < <(git -C "$REPO_ROOT" ls-tree -r --name-only "$REF" -- "$ARCHIVE_PREFIX")

# The two contract copies are staged from `cat-file blob` above; assert
# the same parity for them so the whole shipped tree is covered.
for pair in \
    "docs/tracks/direct-wss/direct-wss-yota-contract.md|$STAGE/direct-wss-yota-contract.md" \
    "docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md|$STAGE/operator-package/wss-3-carrier-vpn-matrix-contract.md"; do
    rel="${pair%%|*}"; staged="${pair#*|}"
    blob_sha=$(git -C "$REPO_ROOT" rev-parse "$REF:$rel")
    want=$(git -C "$REPO_ROOT" cat-file blob "$blob_sha" | sha256sum | awk '{print $1}')
    got=$(sha256sum < "$staged" | awk '{print $1}')
    if [ "$want" != "$got" ]; then
        echo "build FAILED: not byte-identical to the index blob: $rel" >&2
        parity_bad=$((parity_bad+1))
    fi
    parity_checked=$((parity_checked+1))
done

# Nothing in the stage may sit outside that accounted set. In review
# mode every staged file is parity-checked; final mode adds exactly the
# APK and its sidecar, which are hash-verified separately below.
unchecked_allowance=0
if [ "$MODE" = "final" ]; then
    unchecked_allowance=2
fi
staged_total=$(find "$STAGE" -type f | wc -l | tr -d '[:space:]')
if [ "$staged_total" -ne "$((parity_checked + unchecked_allowance))" ]; then
    echo "build FAILED: stage holds $staged_total files; parity covered $parity_checked with $unchecked_allowance allowed unchecked" >&2
    parity_bad=$((parity_bad+1))
fi
if [ "$parity_bad" -ne 0 ]; then
    exit 1
fi
echo "index-blob parity: OK — $parity_checked packaged files byte-identical to <$REF>"

# 7. Write the tar at a colon-free tmp path (POSIX -f is safe there),
#    then `mv` to the requested destination. Only POSIX/BSD-compatible
#    flags are used: -c, -z, -f, -x, -t.
TAR_TMP="$TMP/handoff.tar.gz"
(cd "$STAGE" && tar -czf "$TAR_TMP" .)
mkdir -p "$(dirname "$OUT_PATH")"
mv "$TAR_TMP" "$OUT_PATH"

# 8. Dry-extract validation. Copy the sealed archive to a colon-free
#    /tmp path first so BSD tar's -f parser cannot mistake a Windows
#    drive-letter destination for a remote host, then extract from
#    that copy. On macOS this is a no-op; on Git Bash for Windows
#    this is what lets the extract step run.
DRY="$(mktemp -d)"
DRY_TAR="$DRY/handoff.tar.gz"
cp "$OUT_PATH" "$DRY_TAR"
(cd "$DRY" && tar -xzf handoff.tar.gz)
rm -f "$DRY_TAR"
bad_modes=$(find "$DRY" -type f \( -name "*.sh" -o -name "*.py" \) \
            ! -perm -u+x -print)
if [ -n "$bad_modes" ]; then
    echo "build FAILED: non-executable modes after extract" >&2
    echo "$bad_modes" >&2
    rm -rf "$DRY"; exit 1
fi
if ! (cd "$DRY/operator-package" && bash tests/test_shell.sh) >/dev/null 2>&1; then
    echo "build FAILED: shell fixtures failed from extracted tar" >&2
    rm -rf "$DRY"; exit 1
fi
if ! (cd "$DRY/operator-package" && python3 -m unittest tests.test_verifier) \
        >/dev/null 2>&1; then
    echo "build FAILED: python fixtures (test_verifier) failed from extracted tar" >&2
    rm -rf "$DRY"; exit 1
fi
# Audit ROUND-10 P1-9: the WSS-3 python suite must also gate the
# bundle so a self-contained tar cannot ship with a broken wss3
# verifier. Same command shape as test_verifier above.
if ! (cd "$DRY/operator-package" && python3 -m unittest tests.test_verifier_wss3) \
        >/dev/null 2>&1; then
    echo "build FAILED: python fixtures (test_verifier_wss3) failed from extracted tar" >&2
    rm -rf "$DRY"; exit 1
fi
# Audit ROUND-12 P1-#8 — the harness default is all 8 profiles
# (~50 min on Windows Git Bash). For bundle-validator use we run
# `--profiles 1` (proves the wiring extracts cleanly and can invoke
# the real orchestrator end-to-end for at least one profile); the
# audit-gate all-8-profile evidence lives in the separately-produced
# review-pack `dry-run-real-all-eight/` tree, generated by hand-
# running `bash tests/dry_run_matrix_p0_7.sh --both`. `--profiles 1`
# now exits with rc=2 (subset-run acknowledgement per audit ROUND-12
# P1-#8); the validator treats rc=2 as OK for the extract check.
_dry_rc=0
(cd "$DRY/operator-package" && bash tests/dry_run_matrix_p0_7.sh --both --profiles 1) \
    >/dev/null 2>&1 || _dry_rc=$?
if [ "$_dry_rc" -ne 0 ] && [ "$_dry_rc" -ne 2 ]; then
    echo "build FAILED: real-orchestrator extract-gate failed with rc=$_dry_rc" >&2
    rm -rf "$DRY"; exit 1
fi
# In final mode: assert the APK is present at extract time AND its
# sha256 sidecar re-verifies.
if [ "$MODE" = "final" ]; then
    apk="$DRY/operator-package/android-debug-diagnostic.apk"
    apk_sha="$apk.sha256"
    [ -f "$apk" ] || { echo "final: extracted APK missing" >&2; rm -rf "$DRY"; exit 1; }
    [ -f "$apk_sha" ] || { echo "final: extracted APK sha256 missing" >&2; rm -rf "$DRY"; exit 1; }
    # shellcheck source=lib/portable.sh
    source "$DRY/operator-package/lib/portable.sh"
    expected=$(awk '{print $1}' < "$apk_sha")
    computed=$(sha256_file "$apk")
    if [ "$expected" != "$computed" ]; then
        echo "final: extracted APK sha mismatch expected=$expected computed=$computed" >&2
        rm -rf "$DRY"; exit 1
    fi
fi
rm -rf "$DRY"

# ── Audit ROUND-30.10: a machine-checkable FINAL verdict ───────────
#
# Every gate above exits non-zero on failure, so reaching this line
# means all of them passed. What was missing is a line an assembler can
# test for. R30.9's pack shipped a builder log whose last line was
# `build FAILED: shell fixtures failed from extracted tar` while the
# report claimed green, because the reporter read the log's FIRST
# interesting line and never its verdict. Anything packaging this
# builder's output must require the line below and refuse to ship
# otherwise.
echo "gate-verdict: ALL GATES GREEN (parity, junk, CR, dry-extract shell, python, orchestrator)"
echo "OK: $OUT_PATH"
