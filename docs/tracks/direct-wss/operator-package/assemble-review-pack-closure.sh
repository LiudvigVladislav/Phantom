#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 audit ROUND-19 P1-1 — review-pack post-copy closure gate.
#
# Architect ROUND-19 verdict:
#   > Review-pack root contains an unlisted `.DS_Store`.
#   > files not listed: .DS_Store, SHA256SUMS.txt
#   > The operator tarball is clean, so this needs only review-pack
#   > sanitation and a post-copy closure gate.
#
# This script:
#   1. Removes OS/cache junk from the review-pack directory
#      (.DS_Store, __MACOSX, .pyc, __pycache__, .runtime/).
#   2. Regenerates SHA256SUMS.txt with every regular file EXCEPT
#      the manifest itself.
#   3. Verifies exact closure: every regular file (other than
#      SHA256SUMS.txt) must appear in the manifest AND every
#      manifest entry must resolve to a file whose SHA matches.
#   4. Exits non-zero on any discrepancy so no pack that fails
#      exact closure can be shipped.
#
# Usage:
#   ./assemble-review-pack-closure.sh <pack-root>
#
# STOP invariants preserved: no ADB, no APK build, no push, no PR.

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "usage: $0 <pack-root>" >&2
    exit 2
fi
PACK="$1"
if [ ! -d "$PACK" ]; then
    echo "$0: not a directory: $PACK" >&2
    exit 2
fi

# 1. Strip OS/cache junk. `.runtime/` is a producer artefact that
#    MUST never ship in a review pack (contract §5).
find "$PACK" \
    \( -name '.DS_Store' -o -name '__MACOSX' \
       -o -name '__pycache__' -o -name '*.pyc' \
       -o -name '._*' -o -name 'Thumbs.db' \) \
    -exec rm -rf {} + 2>/dev/null || true
# Prune any nested `.runtime/` directory copied from producer scratch.
find "$PACK" -type d -name '.runtime' -exec rm -rf {} + 2>/dev/null || true

# 2. Regenerate SHA256SUMS.txt excluding the manifest itself.
rm -f "$PACK/SHA256SUMS.txt"
(
    cd "$PACK"
    find . -type f ! -path './SHA256SUMS.txt' | LC_ALL=C sort \
        | while IFS= read -r f; do sha256sum "$f"; done \
        > SHA256SUMS.txt
)

# 3. Exact closure check.
tree_count=$(find "$PACK" -type f ! -path "$PACK/SHA256SUMS.txt" | wc -l | tr -d ' ')
manifest_count=$(wc -l < "$PACK/SHA256SUMS.txt" | tr -d ' ')
if [ "$tree_count" != "$manifest_count" ]; then
    echo "$0: closure mismatch — tree files=$tree_count manifest lines=$manifest_count" >&2
    diff <(cd "$PACK" && find . -type f ! -path './SHA256SUMS.txt' | LC_ALL=C sort) \
         <(awk '{sub(/^[0-9a-f]+ [ *]/,""); print}' "$PACK/SHA256SUMS.txt" | LC_ALL=C sort) \
         | head -20 >&2 || true
    exit 3
fi

# 4. Verify every hash matches.
(cd "$PACK" && sha256sum -c SHA256SUMS.txt >/tmp/pack-closure-verify.log 2>&1) \
    || { echo "$0: sha256sum -c failed:" >&2; tail -20 /tmp/pack-closure-verify.log >&2; exit 4; }

# 5. Belt-and-braces: reject any stray junk names that would embarrass
#    the handoff even if they somehow slipped past step 1.
banned=$(find "$PACK" \( -name '.DS_Store' -o -name '__MACOSX' \
                          -o -name '*.pyc' -o -name 'Thumbs.db' \
                          -o -name '._*' \) -print 2>/dev/null || true)
if [ -n "$banned" ]; then
    echo "$0: banned junk still present:" >&2
    printf '%s\n' "$banned" >&2
    exit 5
fi

echo "assemble-review-pack-closure: OK — $tree_count/$manifest_count files, exact closure verified"

# Audit ROUND-20 P1-1: pack the closure-clean directory into ONE
# outer `.tar.gz` next to the directory, plus a `.sha256` sidecar.
# This freezes the exact-closure state at handoff time — Finder /
# cloud-sync recreating `.DS_Store` in the loose directory after
# assembly cannot invalidate the delivered archive.
parent=$(cd "$(dirname "$PACK")" && pwd)
base=$(basename "$PACK")
outer="$parent/${base}.tar.gz"
outer_sha="${outer}.sha256"
rm -f "$outer" "$outer_sha"
(
    cd "$parent"
    # NB: `--sort=name` keeps the archive byte-deterministic across
    # runs (excluding mtimes); flags portable to BSD tar via GNU
    # extensions only when available. Fall back to plain -czf if
    # `--sort` isn't supported.
    if tar --sort=name -czf "$outer" "$base" 2>/dev/null; then :
    else tar -czf "$outer" "$base"; fi
)
if [ ! -s "$outer" ]; then
    echo "$0: outer tarball assembly failed at $outer" >&2
    exit 6
fi
(cd "$parent" && sha256sum "${base}.tar.gz" > "${base}.tar.gz.sha256")
outer_bytes=$(wc -c < "$outer" | tr -d ' ')
echo "assemble-review-pack-closure: outer archive $outer (${outer_bytes} bytes)"
echo "assemble-review-pack-closure: outer sha $(cat "$outer_sha")"
