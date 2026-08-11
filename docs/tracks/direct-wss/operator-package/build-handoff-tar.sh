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
git -C "$REPO_ROOT" archive --format=tar "$REF" \
    -- docs/tracks/direct-wss/operator-package \
  | (cd "$TMP" && tar -xf -)

# 3. Reshape into <STAGE>/operator-package/... plus the contract sheet.
mkdir -p "$STAGE/operator-package"
cp -R "$TMP/docs/tracks/direct-wss/operator-package/." "$STAGE/operator-package/"
git -C "$REPO_ROOT" show "$REF:docs/tracks/direct-wss/direct-wss-yota-contract.md" \
    > "$STAGE/direct-wss-yota-contract.md"

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
JUNK_PATTERNS=(-name ".DS_Store" -o -name "__pycache__" -o -name "*.pyc")
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
    echo "build FAILED: python fixtures failed from extracted tar" >&2
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

echo "OK: $OUT_PATH"
