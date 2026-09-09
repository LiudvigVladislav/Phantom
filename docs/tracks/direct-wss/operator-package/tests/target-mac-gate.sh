#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 target-platform gate.
#
# Run this on the TARGET Mac, with the stock interpreter, from the
# operator-package root:
#
#     /bin/bash tests/target-mac-gate.sh 2>&1 | tee target-mac-gate.log
#
# Why this exists (audit ROUND-30.10 closure): the suite carried a `case`
# inside a command substitution, which stock macOS bash 3.2 cannot parse.
# Every reported gate was green because every gate had been run on a
# bash 5 host. A static portability scan is a development check and can
# never settle the question — only the real interpreter on the real
# platform can. A run under any other bash is host-local green, never
# target-platform green, and this script says so in its own verdict.
#
# The gate is RED unless ALL of the following hold:
#   - the interpreter really is /bin/bash on this machine;
#   - the shipped shell file list is DERIVED here, is non-empty, and
#     every derived path exists;
#   - the number of files parsed equals the number derived;
#   - every file passes `bash -n`;
#   - exactly one run of tests/test_shell.sh exits 0.
#
# MANDATORY PRE-RUN STEP on a freshly extracted archive
# ------------------------------------------------------
# macOS puts `com.apple.quarantine` on every file that came out of a
# downloaded archive. Executing a quarantined script fails with
# `/usr/bin/env: bad interpreter: Operation not permitted`, which the
# shell reports as rc=126. Clear it before running this gate:
#
#     xattr -dr com.apple.quarantine <extracted-package-root>
#
# The gate checks for the attribute itself and refuses with a named RED
# rather than letting it surface as an opaque exit code (audit
# ROUND-30.12: a first R30.12 gate run ended one second in at rc=126,
# with no WSS-3 fixture ever reaching a verdict).
#
# It runs no ADB, no device command, no build, and writes nothing into
# the package.

set -u

echo "==== WSS-3 target-platform gate ===="
echo "started_utc: $(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo unknown)"
echo
echo "---- environment ----"
echo "uname -a: $(uname -a)"
if command -v sw_vers >/dev/null 2>&1; then
    echo "sw_vers:  $(sw_vers -productName 2>/dev/null) $(sw_vers -productVersion 2>/dev/null) ($(sw_vers -buildVersion 2>/dev/null))"
fi
echo "interpreter path: ${BASH:-unknown}"
echo "BASH_VERSION:     ${BASH_VERSION:-unknown}"
echo "/bin/bash --version:"
if [ -x /bin/bash ]; then
    /bin/bash --version 2>&1 | sed 's/^/    /'
else
    echo "    /bin/bash is not executable on this host"
fi
echo

# The gate is only meaningful when the running interpreter IS the
# platform's stock bash. Anything else is host-local and is reported as
# such rather than being quietly accepted.
#
# ── Audit ROUND-30.13 ────────────────────────────────────────────
# This used to test one thing: that `$BASH` was `/bin/bash`. `uname`,
# `sw_vers` and `BASH_VERSION` were printed in the environment block and
# never read. On Linux, where `/bin/bash` is also `/bin/bash` and is
# usually 5.x, the arm matched and this script could print
# `TARGET_MAC_GATE=GREEN` — while its own header promises that a run
# under any other bash establishes host-local green only.
#
# The path to an interpreter is not a platform. What this gate exists to
# prove is that the package runs under macOS's stock bash 3.2, so it
# checks the platform and the interpreter's VERSION as well, and each
# failing condition is named rather than folded into one verdict.
gate_target=1
case "${BASH:-}" in
    /bin/bash) ;;
    *)
        gate_target=0
        echo "NOTE: this run is NOT under /bin/bash (${BASH:-unknown})."
        echo "      It can establish host-local green only."
        ;;
esac
gate_uname=$(uname -s 2>/dev/null || echo unknown)
case "$gate_uname" in
    Darwin) ;;
    *)
        gate_target=0
        echo "NOTE: this host is '${gate_uname}', not Darwin."
        echo "      The target platform is macOS; this run is host-local only."
        ;;
esac
case "${BASH_VERSION:-}" in
    3.2.*) ;;
    *)
        gate_target=0
        echo "NOTE: BASH_VERSION is '${BASH_VERSION:-unknown}', not 3.2.x."
        echo "      The portability this gate exists to prove is bash 3.2;"
        echo "      a newer bash cannot establish it."
        ;;
esac
echo "target_platform_check: uname=${gate_uname} bash=${BASH_VERSION:-unknown} interpreter=${BASH:-unknown} -> $([ "$gate_target" = "1" ] && echo TARGET || echo HOST-LOCAL)"
echo

pkg_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$pkg_root" || { echo "TARGET_MAC_GATE=RED — cannot enter the package root"; exit 2; }
echo "package root: $pkg_root"
echo

# ---- derive the file list; never hard-code it --------------------
echo "---- shipped shell files (derived) ----"
files=$(ls -1 ./*.sh lib/*.sh tests/*.sh 2>/dev/null | sed 's|^\./||' | LC_ALL=C sort)
derived=0
while IFS= read -r f; do
    [ -n "$f" ] || continue
    derived=$((derived + 1))
    echo "  $f"
done <<EOF
$files
EOF
echo "derived_count: $derived"
if [ "$derived" -lt 20 ]; then
    echo
    echo "TARGET_MAC_GATE=RED — only $derived shipped shell files were derived; the list is wrong"
    exit 2
fi
echo

# ---- syntax over every derived file ------------------------------
echo "---- bash -n over every derived file ----"
parsed=0
syntax_failures=0
# Read the derived list line by line: a path is one path, whatever it
# contains. The counters have to survive the loop, so the list arrives by
# redirection rather than through a pipe, which would run the body in a
# subshell and throw the counts away.
while IFS= read -r f; do
    [ -n "$f" ] || continue
    if [ ! -f "$f" ]; then
        echo "  MISSING  $f"
        syntax_failures=$((syntax_failures + 1))
        continue
    fi
    out=$(/bin/bash -n "$f" 2>&1)
    rc=$?
    parsed=$((parsed + 1))
    if [ "$rc" = "0" ]; then
        echo "  OK   rc=0  $f"
    else
        echo "  FAIL rc=$rc  $f"
        printf '%s
' "$out" | sed 's/^/      /'
        syntax_failures=$((syntax_failures + 1))
    fi
done <<EOF
$files
EOF
echo "parsed_count: $parsed"
echo "derived_count: $derived"
echo "syntax_failures: $syntax_failures"

if [ "$parsed" != "$derived" ]; then
    echo
    echo "TARGET_MAC_GATE=RED — parsed $parsed of $derived derived files; a file was skipped"
    exit 2
fi
if [ "$syntax_failures" != "0" ]; then
    echo
    echo "TARGET_MAC_GATE=RED — $syntax_failures file(s) failed bash -n on the target interpreter"
    echo "The suite is NOT run: a file that does not parse cannot be tested."
    exit 2
fi
echo

# ---- quarantine pre-check (audit ROUND-30.12) --------------------
# `bash -n` only READS a file; running one needs exec permission, and a
# freshly extracted archive on macOS carries `com.apple.quarantine`,
# which denies it. The failure surfaces as
# `/usr/bin/env: bad interpreter: Operation not permitted` and rc=126 —
# an exit code that says nothing about what to do next. A gate that
# turns a known, fixable environment condition into an opaque number is
# not reporting; it is hiding. So it is named here, before the suite.
echo "---- quarantine pre-check ----"
quarantined=0
if command -v xattr >/dev/null 2>&1; then
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        [ -f "$f" ] || continue
        if xattr -p com.apple.quarantine "$f" >/dev/null 2>&1; then
            echo "  QUARANTINED  $f"
            quarantined=$((quarantined + 1))
        fi
    done <<EOF
$files
EOF
    echo "quarantined_count: $quarantined"
    if [ "$quarantined" != "0" ]; then
        echo
        echo "Clear it, then re-run this gate:"
        echo "    xattr -dr com.apple.quarantine \"$pkg_root\""
        echo
        echo "TARGET_MAC_GATE=RED — $quarantined shipped file(s) carry com.apple.quarantine;"
        echo "the suite would fail with 'bad interpreter: Operation not permitted' (rc=126)"
        exit 2
    fi
else
    echo "quarantined_count: not-applicable (no xattr on this host)"
    echo "NOTE: this host cannot check the attribute. That is expected off macOS,"
    echo "      and it is reported rather than counted as a pass."
fi
echo

# ---- exactly one suite run, under a watchdog ---------------------
# Audit ROUND-30.10 closure round 2: this was a plain foreground run.
# When a fixture waited forever for a signal the platform had swallowed,
# the gate never finished and produced no verdict at all — the one thing
# an authoritative gate may not do. The suite now runs under a bounded
# watchdog and the gate always ends with GREEN or RED.
#
# The window is generous: it is a stall detector, not a performance
# budget. Override it with WSS3_GATE_TIMEOUT_SECONDS when a slow machine
# needs longer.
# The override is validated, not trusted. A non-numeric, zero,
# negative or absurd value would turn a bounded gate back into an
# unbounded wait, which is the failure this watchdog exists to prevent,
# so anything outside a strict positive integer within a hard ceiling
# falls back to the default and says so.
gate_timeout_default=2400
gate_timeout_max=3600
# ── Audit ROUND-30.16: the local-capture controls ──────────────
#
# These six run HERE and nowhere else on purpose. The development host
# is Windows under MSYS, where `chmod` does not take (a fresh
# `mktemp -d` reports 755 and stays 755 after `chmod 700`) and `ln -s`
# degrades to a copy. Those properties are therefore
# HOST-NOT-EXPRESSIBLE there — neither green nor broken, simply
# unprovable. macOS expresses all of them, so this is the only place
# the guarantees can actually be tested.
lc_fail=0
echo "---- local-capture controls (ROUND-30.16) ----"

# 1. mktemp -d gives a unique 0700 directory.
lc_a="$(mktemp -d "${TMPDIR:-/tmp}/wss3gate.XXXXXX")"
lc_b="$(mktemp -d "${TMPDIR:-/tmp}/wss3gate.XXXXXX")"
lc_mode="$(stat -f %Lp "$lc_a" 2>/dev/null || stat -c %a "$lc_a" 2>/dev/null)"
if [ "$lc_a" = "$lc_b" ]; then
    echo "FAIL: mktemp -d reused a directory"; lc_fail=$((lc_fail+1))
else
    echo "PASS: mktemp -d is unique per call"
fi
if [ "$lc_mode" = "700" ]; then
    echo "PASS: mktemp -d directory mode is 0700"
else
    echo "FAIL: mktemp -d directory mode is $lc_mode, want 700"; lc_fail=$((lc_fail+1))
fi

# 2. The capture file is 0600 BEFORE anything is written to it.
(
    umask 077
    : > "$lc_a/probe.PhantomMessaging.log"
)
lc_fmode="$(stat -f %Lp "$lc_a/probe.PhantomMessaging.log" 2>/dev/null \
            || stat -c %a "$lc_a/probe.PhantomMessaging.log" 2>/dev/null)"
if [ "$lc_fmode" = "600" ]; then
    echo "PASS: capture file is 0600 at creation, before any line is written"
else
    echo "FAIL: capture file mode is $lc_fmode, want 600"; lc_fail=$((lc_fail+1))
fi

# 3 + 4. A real symlink from an allowed path into a forbidden tree is
#        refused after `pwd -P`, and a symlinked TMPDIR does not slip
#        past the re-check of the resolved directory.
WSS3_OPERATOR_PACKAGE_ROOT="$pkg_root"
WSS3_EVIDENCE_ROOT="$pkg_root/evidence"
export WSS3_OPERATOR_PACKAGE_ROOT WSS3_EVIDENCE_ROOT
# shellcheck source=../lib/wss3-cleanup.sh
. "$pkg_root/lib/wss3-cleanup.sh" 2>/dev/null || true
if command -v wss3_local_diag_root >/dev/null 2>&1; then
    mkdir -p "$WSS3_EVIDENCE_ROOT" 2>/dev/null || true
    ln -sfn "$WSS3_EVIDENCE_ROOT" "$lc_a/link-into-package"
    if [ -L "$lc_a/link-into-package" ]; then
        if WSS3_LOCAL_DIAG_ROOT="$lc_a/link-into-package" \
           wss3_local_diag_root "$WSS3_EVIDENCE_ROOT/probe" >/dev/null 2>&1; then
            echo "FAIL: a symlink into the package was accepted"; lc_fail=$((lc_fail+1))
        else
            echo "PASS: a real symlink into a forbidden tree is refused after pwd -P"
        fi
        # The same link used as TMPDIR: the base check and the re-check
        # of the resolved mktemp result must both see through it.
        if WSS3_LOCAL_DIAG_ROOT="$lc_a/link-into-package" \
           wss3_local_diag_root "" >/dev/null 2>&1; then
            echo "FAIL: a symlinked TMPDIR bypassed the resolved-path re-check"
            lc_fail=$((lc_fail+1))
        else
            echo "PASS: a symlinked TMPDIR does not bypass the re-check"
        fi
    else
        echo "FAIL: this host cannot create a symlink — the control cannot run"
        lc_fail=$((lc_fail+1))
    fi
    # A directory merely NAMED like a forbidden one must still be fine.
    mkdir -p "$lc_a/harmless-evidence"
    if lc_ok="$(WSS3_LOCAL_DIAG_ROOT="$lc_a/harmless-evidence" \
                wss3_local_diag_root "$WSS3_EVIDENCE_ROOT/probe" 2>/dev/null)"; then
        echo "PASS: containment is canonical, not substring (a dir named 'evidence' is fine)"
        rmdir "$lc_ok" 2>/dev/null || true
    else
        echo "FAIL: a harmless directory named 'evidence' was refused"; lc_fail=$((lc_fail+1))
    fi
else
    echo "FAIL: wss3_local_diag_root is not available to the gate"; lc_fail=$((lc_fail+1))
fi

# 5 + 6. A process that ignores TERM is escalated to KILL, waited for,
#        and leaves no background job behind.
lc_before="$(jobs -p 2>/dev/null | wc -l | tr -d '[:space:]')"
/bin/bash -c 'trap "" TERM; while :; do sleep 0.2; done' &
WSS3_LOCAL_DIAG_PIDS="$!"
WSS3_LOCAL_DIAG_DIR=""
lc_stub=$!
lc_t0=$(date +%s)
_wss3_local_diag_reap
lc_t1=$(date +%s)
if kill -0 "$lc_stub" 2>/dev/null; then
    echo "FAIL: a TERM-ignoring capture survived the reap"; lc_fail=$((lc_fail+1))
else
    echo "PASS: a TERM-ignoring capture is escalated to KILL and waited for ($((lc_t1-lc_t0))s)"
fi
lc_after="$(jobs -p 2>/dev/null | wc -l | tr -d '[:space:]')"
if [ "$lc_after" -le "$lc_before" ]; then
    echo "PASS: no background job survives dispose"
else
    echo "FAIL: $lc_after background job(s) remain after dispose (was $lc_before)"
    lc_fail=$((lc_fail+1))
fi

rm -rf "$lc_a" "$lc_b" 2>/dev/null || true
echo "local_capture_controls: $lc_fail failure(s)"
# Audit ROUND-30.16: a failed control is a DEFECT, not missing evidence.
# Downgrading to HOST-LOCAL here would report a broken guarantee as
# "this was not a target run", which is the more comfortable of the two
# and the false one. `gate_target` describes the PLATFORM and nothing
# else; the verdict below turns a control failure into RED.
echo

gate_timeout="${WSS3_GATE_TIMEOUT_SECONDS:-$gate_timeout_default}"
case "$gate_timeout" in
    ""|*[!0-9]*)
        echo "watchdog: WSS3_GATE_TIMEOUT_SECONDS is not a positive integer — using $gate_timeout_default"
        gate_timeout=$gate_timeout_default
        ;;
esac
if [ "$gate_timeout" -lt 1 ] 2>/dev/null; then
    echo "watchdog: WSS3_GATE_TIMEOUT_SECONDS must be at least 1 — using $gate_timeout_default"
    gate_timeout=$gate_timeout_default
fi
if [ "$gate_timeout" -gt "$gate_timeout_max" ] 2>/dev/null; then
    echo "watchdog: capping WSS3_GATE_TIMEOUT_SECONDS at $gate_timeout_max"
    gate_timeout=$gate_timeout_max
fi
echo "watchdog: ${gate_timeout}s (max ${gate_timeout_max}s)"
# Job control puts the suite in its own process group, so a stall can be
# killed as a group without reaching this script.
set -m
/bin/bash tests/test_shell.sh &
suite_pid=$!
set +m
waited=0
while kill -0 "$suite_pid" 2>/dev/null && [ "$waited" -lt "$gate_timeout" ]; do
    sleep 1
    waited=$((waited + 1))
done
if kill -0 "$suite_pid" 2>/dev/null; then
    echo
    echo "the suite did not finish within ${gate_timeout}s — killing it"
    kill -TERM "-$suite_pid" 2>/dev/null || kill -TERM "$suite_pid" 2>/dev/null || true
    sleep 2
    kill -KILL "-$suite_pid" 2>/dev/null || kill -KILL "$suite_pid" 2>/dev/null || true
    wait "$suite_pid" 2>/dev/null || true
    echo "suite_rc: timeout"
    echo "elapsed_seconds: $waited"
    echo
    echo "---- summary ----"
    echo "derived_count: $derived"
    echo "parsed_count: $parsed"
    echo "syntax_failures: $syntax_failures"
    echo "suite_rc: timeout"
    echo "finished_utc: $(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo unknown)"
    echo "TARGET_MAC_GATE=RED — the shell suite stalled and was killed after ${gate_timeout}s"
    exit 1
fi
wait "$suite_pid" 2>/dev/null
suite_rc=$?
echo "suite_rc: $suite_rc"
if [ "$suite_rc" = "126" ]; then
    # Audit ROUND-30.12: name the class instead of leaving a number.
    echo "note: rc=126 means a file was found but could not be executed. On macOS"
    echo "      that is almost always com.apple.quarantine on a freshly extracted"
    echo "      tree, or a missing execute bit. Clear it with:"
    echo "          xattr -dr com.apple.quarantine \"$pkg_root\""
    echo "      and re-run. The pre-check above reports the attribute directly"
    echo "      when this host can read it."
fi
echo "elapsed_seconds: $waited"
echo

echo "---- summary ----"
echo "derived_count: $derived"
echo "parsed_count: $parsed"
echo "syntax_failures: $syntax_failures"
echo "suite_rc: $suite_rc"
echo "finished_utc: $(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo unknown)"

# ── Audit ROUND-30.16: three terminal branches, in this order ────
#
#   not the target platform            → HOST-LOCAL (no target claim)
#   target platform + any failure      → RED
#   target platform + everything green → GREEN
#
# The platform question is answered FIRST and on its own. Once Darwin
# and /bin/bash 3.2 are confirmed, every subsequent failure is a real
# defect of the target gate and must be reported as RED — never
# re-labelled HOST-LOCAL, which would present a broken guarantee as an
# absence of evidence.
echo "local_capture_controls_failures: $lc_fail"
if [ "$gate_target" != "1" ]; then
    echo "TARGET_MAC_GATE=HOST-LOCAL — not Darwin with /bin/bash 3.2, so this is NOT a target-platform result"
    exit 3
fi
if [ "$suite_rc" != "0" ]; then
    echo "TARGET_MAC_GATE=RED — the shell suite failed on the target interpreter"
    exit 1
fi
if [ "$lc_fail" -ne 0 ]; then
    echo "TARGET_MAC_GATE=RED — $lc_fail local-capture control(s) failed on the target platform"
    exit 1
fi
if [ "$syntax_failures" != "0" ]; then
    echo "TARGET_MAC_GATE=RED — $syntax_failures shipped file(s) failed bash -n"
    exit 1
fi
echo "TARGET_MAC_GATE=GREEN (derived $derived files, all parsed, suite rc=0, local-capture controls 0 failures)"
exit 0
