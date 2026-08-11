#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Build the operator-package handoff tar.gz DIRECTLY from git-index
# blobs (`git archive`), NOT from a working-tree checkout. This
# bypasses core.autocrlf entirely so no Windows checkout can inject
# CRLF into a Mac-bound tarball (§12 Round-4 audit P0-1).
#
# Usage:
#   ./build-handoff-tar.sh <output-tar-gz-path> [<git-ref>]
#
#   <git-ref> defaults to HEAD.
#
# After building, the script:
#   * scans every packaged .sh / .py for stray CR bytes and fails
#     the build if any are found;
#   * extracts the tar to a temp dir and re-runs the shell fixture
#     script against it to prove the extracted copy is runnable.
#
# The tarball also includes the updated contract sheet
# (direct-wss-yota-contract.md) as a sibling of operator-package/.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(git -C "$HERE" rev-parse --show-toplevel)"
OUT_PATH="${1:?usage: build-handoff-tar.sh <output-tar-gz-path> [<git-ref>]}"
REF="${2:-HEAD}"

echo "building handoff tar from $REF at $OUT_PATH"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Extract operator-package tree from git-index (LF-preserving).
# --prefix injects a top-level directory so tar members read
# `operator-package/<path>`.
git -C "$REPO_ROOT" archive --format=tar --prefix=operator-package/ \
    "$REF" -- docs/tracks/direct-wss/operator-package \
  | tar -x -C "$TMP" --strip-components=4 --show-transformed-names \
        --transform='s|^operator-package/docs/tracks/direct-wss/operator-package|operator-package|' \
  || true

# The transform approach can be fragile; the sturdier path is to
# extract to a scratch tree and then move. Redo cleanly:
rm -rf "$TMP"
TMP="$(mktemp -d)"
git -C "$REPO_ROOT" archive --format=tar "$REF" -- docs/tracks/direct-wss/operator-package \
  | (cd "$TMP" && tar -xf -)
# Now $TMP/docs/tracks/direct-wss/operator-package is the tree we want.
STAGE="$(mktemp -d)"
mkdir -p "$STAGE/operator-package"
cp -a "$TMP/docs/tracks/direct-wss/operator-package/." "$STAGE/operator-package/"

# Also pull the updated contract sheet from git-index at the same ref.
git -C "$REPO_ROOT" show "$REF:docs/tracks/direct-wss/direct-wss-yota-contract.md" \
  > "$STAGE/direct-wss-yota-contract.md"

# CR-byte scan (fail-closed).
cr_offenders=0
while IFS= read -r -d '' f; do
    if LC_ALL=C grep -l $'\r' "$f" > /dev/null 2>&1; then
        echo "FAIL: CR bytes present in $f (git-index blob should be LF)" >&2
        cr_offenders=$((cr_offenders+1))
    fi
done < <(find "$STAGE" \( -name "*.sh" -o -name "*.py" \) -type f -print0)
if [ "$cr_offenders" -ne 0 ]; then
    echo "handoff tar build FAILED: $cr_offenders file(s) with CR bytes" >&2
    exit 1
fi

# Junk-file scan (defence in depth).
junk=$(find "$STAGE" \( -name "*.apk" -o -name ".DS_Store" -o -name "__pycache__" -o -name "*.pyc" \) -print)
if [ -n "$junk" ]; then
    echo "handoff tar build FAILED: junk files present" >&2
    echo "$junk" >&2
    exit 1
fi

# Build the tar. --owner/--group=0 keep the archive reproducible.
# --force-local avoids Windows tar parsing "C:" as a hostname.
mkdir -p "$(dirname "$OUT_PATH")"
(cd "$STAGE" && tar --force-local --owner=0 --group=0 -czf "$OUT_PATH" \
    operator-package direct-wss-yota-contract.md)

# Dry-extract validation — extract the tar we just built and run
# the shell fixtures against that extracted copy. This proves the
# tar is runnable on any POSIX system (including macOS).
DRY="$(mktemp -d)"
(cd "$DRY" && tar --force-local -xzf "$OUT_PATH")
# Verify modes.
bad_modes=$(find "$DRY" -type f \( -name "*.sh" -o -name "*.py" \) \
            ! -perm -u+x -print)
if [ -n "$bad_modes" ]; then
    echo "handoff tar build FAILED: non-executable modes after extract" >&2
    echo "$bad_modes" >&2
    exit 1
fi
# Verify shell fixtures pass from the extracted copy.
(cd "$DRY/operator-package" && bash tests/test_shell.sh) >/dev/null 2>&1 \
  || { echo "handoff tar build FAILED: shell fixtures failed from extracted tar" >&2; exit 1; }
(cd "$DRY/operator-package" && python3 -m unittest tests.test_verifier) >/dev/null 2>&1 \
  || { echo "handoff tar build FAILED: python fixtures failed from extracted tar" >&2; exit 1; }
rm -rf "$DRY"

echo "handoff tar OK: $OUT_PATH"
