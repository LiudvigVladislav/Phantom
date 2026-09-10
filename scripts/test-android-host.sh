#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2
for arg in "$@"; do
    case "$arg" in
        --offline|--info|--max-workers=*) ;;
        *) printf 'Unsupported host-suite option: %s\n' "$arg" >&2; exit 2 ;;
    esac
done

# Separate invocations retain AGP/Paparazzi's original task setup. Neither
# engine shares a process with the other; ordinary tests still share a JVM.
# Always attempt both halves, and never let the second success hide a failure.
status=0
for engine in regular paparazzi; do
    printf '\nAndroid host engine: %s\n' "$engine"
    ./gradlew :apps:android:testDebugUnitTest \
        "-PphantomHostTestEngine=$engine" \
        --rerun-tasks --no-build-cache --no-daemon --max-workers=4 \
        --console=plain --continue "$@" || status=1
done
exit "$status"
