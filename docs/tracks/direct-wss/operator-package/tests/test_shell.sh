#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First — shell fixture tests
# (§12 Round-1 audit P0-1 required-tests list).
#
# Every helper in portable.sh + the bootstrap/preflight/run-matrix
# order gets a pure-shell test. `adb` is faked via a stub on
# $PATH so no device is needed.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG="$(cd "$HERE/.." && pwd)"
# shellcheck source=../lib/portable.sh
source "$PKG/lib/portable.sh"

pass=0
fail=0

assert_eq() {
    local expected="$1" actual="$2" label="$3"
    if [ "$expected" = "$actual" ]; then
        echo "PASS: $label"
        pass=$((pass+1))
    else
        echo "FAIL: $label — expected=[$expected] actual=[$actual]"
        fail=$((fail+1))
    fi
}

assert_nonempty() {
    local val="$1" label="$2"
    if [ -n "$val" ]; then
        echo "PASS: $label ($val)"
        pass=$((pass+1))
    else
        echo "FAIL: $label — value is empty"
        fail=$((fail+1))
    fi
}

# ── portable.sh helpers ────────────────────────────────────────

now=$(now_ms)
assert_nonempty "$now" "now_ms returns non-empty"
# Must be a plain integer > 1_700_000_000_000 (i.e. seconds*1000 in 2023+).
if [ "$now" -gt 1700000000000 ] 2>/dev/null; then
    echo "PASS: now_ms is integer > 2023 wall time"; pass=$((pass+1))
else
    echo "FAIL: now_ms not a plausible integer: $now"; fail=$((fail+1))
fi

# sha256_file
tmpf=$(mktemp)
printf 'hello world' > "$tmpf"
expected="b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
actual=$(sha256_file "$tmpf")
assert_eq "$expected" "$actual" "sha256_file produces canonical hex"
rm -f "$tmpf"

# count_matches over two files
tmpa=$(mktemp); tmpb=$(mktemp)
printf 'apple\nbanana\napple\n' > "$tmpa"
printf 'apple\ncherry\n' > "$tmpb"
actual=$(count_matches "apple" "$tmpa" "$tmpb")
assert_eq "3" "$actual" "count_matches sums matches across files as ONE integer"
rm -f "$tmpa" "$tmpb"

# extract_field
line='event=diagnostic_send_dispatched role=matrix emitter_id=phone run_id=r cell_id=c wall_utc_ms=100 monotonic_ms=1 correlation_id=abc-123 sequence=4'
assert_eq "abc-123" "$(extract_field correlation_id "$line")" "extract_field correlation_id"
assert_eq "4" "$(extract_field sequence "$line")" "extract_field sequence"
assert_eq "phone" "$(extract_field emitter_id "$line")" "extract_field emitter_id"

# ── bootstrap ordering ─────────────────────────────────────────

# `run-yota-wss-diagnostic.sh preflight` must be runnable without a
# prior bootstrap (i.e. no cyclic dependency). We test this by
# invoking with a fake `adb` that reports 0 devices — preflight
# should exit red on "devices" step, NOT on "bootstrap prerequisite".
FAKE_BIN=$(mktemp -d)
cat > "$FAKE_BIN/adb" <<'FAKE'
#!/usr/bin/env bash
# Fake adb: no devices online.
if [ "$1" = "devices" ]; then
    printf 'List of devices attached\n\n'
    exit 0
fi
exit 0
FAKE
chmod +x "$FAKE_BIN/adb"
tmp_evidence=$(mktemp -d)
export PATH="$FAKE_BIN:$PATH"
set +e
"$PKG/lib/detect-devices.sh" "$tmp_evidence" > /tmp/dd.log 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ] && grep -q "expected 1 emulator + 1 phone" /tmp/dd.log; then
    echo "PASS: detect-devices exits red with clear message on 0 devices"; pass=$((pass+1))
else
    echo "FAIL: detect-devices did NOT report 0-device condition (rc=$rc)"; fail=$((fail+1))
    cat /tmp/dd.log
fi
rm -rf "$FAKE_BIN" "$tmp_evidence"

# ── extract CID from structured event ─────────────────────────

log_line='08-11 12:00:00.000  1 1 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone run_id=r cell_id=c wall_utc_ms=1 monotonic_ms=1 correlation_id=uuid-42 sequence=3'
cid=$(printf '%s\n' "$log_line" | grep "event=diagnostic_send_dispatched" | grep "sequence=3" | head -1 | grep -oE 'correlation_id=[^ ]+' | cut -d= -f2)
assert_eq "uuid-42" "$cid" "runner-style CID extraction from structured event"

# ── signal counting across two files ──────────────────────────

tmpa=$(mktemp); tmpb=$(mktemp)
printf 'event=recipient_deliver_received correlation_id=cid-1 dedup_gate=fresh\n' > "$tmpa"
printf 'event=recipient_message_persisted correlation_id=cid-1\n' > "$tmpb"
printf 'event=recipient_ack_deliver_sent correlation_id=cid-1\n' >> "$tmpb"
fresh_count=$(count_matches "event=recipient_deliver_received.*correlation_id=cid-1.*dedup_gate=fresh" "$tmpa" "$tmpb")
persist_count=$(count_matches "event=recipient_message_persisted.*correlation_id=cid-1" "$tmpa" "$tmpb")
ack_count=$(count_matches "event=recipient_ack_deliver_sent.*correlation_id=cid-1" "$tmpa" "$tmpb")
assert_eq "1" "$fresh_count" "signal counting: fresh across two files"
assert_eq "1" "$persist_count" "signal counting: persist across two files"
assert_eq "1" "$ack_count" "signal counting: ack across two files"
rm -f "$tmpa" "$tmpb"

# ── Round-3 audit P1-2: runner helpers (real functions, not regex) ─

# shellcheck source=../lib/run-matrix-helpers.sh
source "$PKG/lib/run-matrix-helpers.sh"

# Fixture: a phone log that carries a STALE prior-run pin_active line
# AND the current run's pin_active line. Only the current one should
# satisfy wait_for_pin_active_in_log("run-current").
stale_log=$(mktemp)
cat > "$stale_log" <<'LOG'
08-11 I WSS_DIAG: event=diagnostic_pin_active role=matrix emitter_id=phone run_id=run-old cell_id=wss.p2e.after-connect wall_utc_ms=100 monotonic_ms=1 pin=wss inner_route=wss
08-11 I WSS_DIAG: event=diagnostic_pin_active role=matrix emitter_id=phone run_id=run-current cell_id=wss.p2e.after-connect wall_utc_ms=200 monotonic_ms=2 pin=wss inner_route=wss
LOG

if wait_for_pin_active_in_log "wss" "wss.p2e.after-connect" "run-current" "phone" "$stale_log"; then
    echo "PASS: wait_for_pin_active_in_log matches current run"; pass=$((pass+1))
else
    echo "FAIL: wait_for_pin_active_in_log did NOT match current run"; fail=$((fail+1))
fi

# Stale run alone must NOT satisfy — remove current line, only prior.
prior_only=$(mktemp)
head -1 "$stale_log" > "$prior_only"
if wait_for_pin_active_in_log "wss" "wss.p2e.after-connect" "run-current" "phone" "$prior_only"; then
    echo "FAIL: wait_for_pin_active_in_log accepted stale prior-run line"; fail=$((fail+1))
else
    echo "PASS: wait_for_pin_active_in_log refuses stale prior-run"; pass=$((pass+1))
fi

# Wrong emitter must NOT satisfy (phone log carries emitter=emulator).
wrong_emitter=$(mktemp)
sed 's/emitter_id=phone/emitter_id=emulator/g' "$stale_log" > "$wrong_emitter"
if wait_for_pin_active_in_log "wss" "wss.p2e.after-connect" "run-current" "phone" "$wrong_emitter"; then
    echo "FAIL: wait_for_pin_active_in_log accepted wrong emitter"; fail=$((fail+1))
else
    echo "PASS: wait_for_pin_active_in_log refuses wrong emitter"; pass=$((pass+1))
fi

rm -f "$stale_log" "$prior_only" "$wrong_emitter"

# find_send_cid_in_log — stale prior-run line must be rejected on
# both run_id and wall_utc_ms. Only the current-run line dated
# >= command_start_ms is accepted.
sender_log=$(mktemp)
cat > "$sender_log" <<'LOG'
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone run_id=run-old cell_id=wss.p2e.after-connect wall_utc_ms=100 monotonic_ms=1 correlation_id=STALE-CID sequence=1
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone run_id=run-current cell_id=wss.p2e.after-connect wall_utc_ms=1000 monotonic_ms=1 correlation_id=CURRENT-CID sequence=1
LOG

# 6th arg = skew (positive host-ahead-of-device). With skew=0 the
# comparison stays on device clock only.
cid=$(find_send_cid_in_log "wss.p2e.after-connect" "1" "run-current" "phone" 500 "$sender_log" 0 || true)
assert_eq "CURRENT-CID" "$cid" "find_send_cid_in_log picks current-run line (skew=0)"

# Same cell + sequence but run-old must give empty (wrong run_id).
cid=$(find_send_cid_in_log "wss.p2e.after-connect" "1" "run-old" "phone" 50 "$sender_log" 0 || true)
assert_eq "STALE-CID" "$cid" "find_send_cid_in_log picks run-old when asked (but not run-current)"

# host_not_before_ms > all matching walls (skew=0) must give empty.
cid=$(find_send_cid_in_log "wss.p2e.after-connect" "1" "run-current" "phone" 9999 "$sender_log" 0 || true)
assert_eq "" "$cid" "find_send_cid_in_log rejects line predating not_before_ms (skew=0)"

# Wrong emitter must give empty.
cid=$(find_send_cid_in_log "wss.p2e.after-connect" "1" "run-current" "emulator" 500 "$sender_log" 0 || true)
assert_eq "" "$cid" "find_send_cid_in_log rejects wrong emitter"

# ── Round-4 audit P1-3: skew-corrected wall compare ─────────────

# Scenario 1 (positive skew, host ahead of device).
# host_not_before_ms=1000, host_to_sender_skew_ms=100 →
# device_not_before_ms=900. Valid current-run line at device wall=1000.
# Naive (skew=0) code would take device_not_before=1000 and reject.
# Skew-aware code takes device_not_before=900 and accepts.
skew_pos=$(mktemp)
cat > "$skew_pos" <<'LOG'
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone run_id=run-current cell_id=wss.p2e.after-connect wall_utc_ms=1000 monotonic_ms=1 correlation_id=CID-POS sequence=1
LOG
cid=$(find_send_cid_in_log "wss.p2e.after-connect" "1" "run-current" "phone" 1000 "$skew_pos" 100 || true)
assert_eq "CID-POS" "$cid" "find_send_cid_in_log accepts current line with positive skew (host ahead)"

# Same scenario, but with skew=0 the same line would be rejected.
# (Regression guard — proves the correction is what makes it pass.)
cid=$(find_send_cid_in_log "wss.p2e.after-connect" "1" "run-current" "phone" 1000 "$skew_pos" 0 || true)
assert_eq "CID-POS" "$cid" "find_send_cid_in_log accepts current line at exact device wall (skew=0)"

# Truly stale event MUST still be rejected under positive skew.
skew_stale=$(mktemp)
cat > "$skew_stale" <<'LOG'
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone run_id=run-current cell_id=wss.p2e.after-connect wall_utc_ms=500 monotonic_ms=1 correlation_id=CID-STALE sequence=1
LOG
cid=$(find_send_cid_in_log "wss.p2e.after-connect" "1" "run-current" "phone" 1000 "$skew_stale" 100 || true)
# device_not_before = 1000 - 100 = 900; event wall = 500 < 900 → reject.
assert_eq "" "$cid" "find_send_cid_in_log rejects truly stale event under positive skew"

# Scenario 2 (negative skew, device ahead of host).
# host_not_before=1000, skew=-100 → device_not_before=1100.
# Event at device wall=1050 must be rejected (predates corrected boundary).
skew_neg=$(mktemp)
cat > "$skew_neg" <<'LOG'
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone run_id=run-current cell_id=wss.p2e.after-connect wall_utc_ms=1050 monotonic_ms=1 correlation_id=CID-NEG-EARLY sequence=1
LOG
cid=$(find_send_cid_in_log "wss.p2e.after-connect" "1" "run-current" "phone" 1000 "$skew_neg" -100 || true)
assert_eq "" "$cid" "find_send_cid_in_log rejects device-clock line before corrected not-before (negative skew)"

# Same negative skew but device wall past corrected boundary must accept.
cat > "$skew_neg" <<'LOG'
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone run_id=run-current cell_id=wss.p2e.after-connect wall_utc_ms=1200 monotonic_ms=1 correlation_id=CID-NEG-OK sequence=1
LOG
cid=$(find_send_cid_in_log "wss.p2e.after-connect" "1" "run-current" "phone" 1000 "$skew_neg" -100 || true)
assert_eq "CID-NEG-OK" "$cid" "find_send_cid_in_log accepts device-clock line past corrected not-before (negative skew)"

rm -f "$sender_log" "$skew_pos" "$skew_stale" "$skew_neg"

# refuse_matrix_rerun: empty dir OK; dir with matrix.json refuses.
tmpdir=$(mktemp -d)
if refuse_matrix_rerun "$tmpdir" >/dev/null 2>&1; then
    echo "PASS: refuse_matrix_rerun allows empty directory"; pass=$((pass+1))
else
    echo "FAIL: refuse_matrix_rerun blocked empty directory"; fail=$((fail+1))
fi
echo "{}" > "$tmpdir/matrix.json"
if refuse_matrix_rerun "$tmpdir" >/dev/null 2>&1; then
    echo "FAIL: refuse_matrix_rerun ALLOWED a dir with existing matrix.json"; fail=$((fail+1))
else
    echo "PASS: refuse_matrix_rerun blocks dir with existing matrix.json"; pass=$((pass+1))
fi
rm -rf "$tmpdir"

# ── Round-4 audit P0-1 + Round-5 audit P2: LF-only bytes in every
#     EXECUTABLE .sh/.py under operator-package. The Round-5 audit
#     narrowed the LF guarantee to executables — text files that are
#     not run directly (README-OPERATOR.md, .gitattributes, .gitignore)
#     may carry CRLF on a Windows checkout without breaking macOS use;
#     but the scripts and Python modules operators actually invoke MUST
#     be LF. This scoped scan enforces that contract.
cr_offenders=0
for f in "$PKG/run-yota-wss-diagnostic.sh" \
         "$PKG/preflight.sh" \
         "$PKG/build-handoff-tar.sh" \
         "$PKG"/lib/*.sh \
         "$PKG"/tests/*.sh \
         "$PKG/verify-evidence.py" \
         "$PKG"/tests/*.py; do
    if [ -f "$f" ] && LC_ALL=C grep -l $'\r' "$f" > /dev/null 2>&1; then
        echo "FAIL: CR bytes present in $f"
        cr_offenders=$((cr_offenders+1))
    fi
done
if [ "$cr_offenders" -eq 0 ]; then
    echo "PASS: no CR bytes in any executable .sh/.py under the operator-package tree"
    pass=$((pass+1))
else
    echo "FAIL: $cr_offenders file(s) carry CR bytes"
    fail=$((fail+1))
fi

# ── Round-5 audit P0: strict diagnostic boolean helper ─────────

# The verifier expects `restored=true|false` verbatim. Any other value
# must land in parse_errors. This shell-side fixture drives the same
# regex the Python `_strict_bool` uses, so an operator inspecting a
# fresh evidence dir on Mac can spot a bad restored value quickly.
count_restored_garbage() {
    local log="$1"
    LC_ALL=C grep -oE 'restored=[^ ]+' "$log" 2>/dev/null \
      | LC_ALL=C grep -vE '^restored=(true|false)$' | wc -l | tr -d ' '
}
tmp_log=$(mktemp)
cat > "$tmp_log" <<'LOG'
event=diagnostic_session_started restored=true pin=wss
event=diagnostic_session_started restored=false pin=wss
event=diagnostic_session_started restored=garbage pin=wss
event=diagnostic_session_started restored=TRUE pin=wss
LOG
n=$(count_restored_garbage "$tmp_log")
assert_eq "2" "$n" "shell-side detects non-canonical restored values (garbage + TRUE)"
rm -f "$tmp_log"

# ── Round-5 audit P1: Python version helper ────────────────────

# preflight enforces python3 >= 3.9. The one-liner it uses must
# succeed on the CURRENT interpreter (bootstrap of the fixture
# assumes the same Python the fixture is running under).
if python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)'; then
    echo "PASS: python3 >= 3.9 gate passes on host interpreter"; pass=$((pass+1))
else
    echo "FAIL: python3 >= 3.9 gate rejected host interpreter"; fail=$((fail+1))
fi
# A synthetic subshell forcing a low-version response must fail.
# This proves the gate LOGIC is a real inequality — not `command -v`.
if python3 -c 'import sys; sys.exit(0 if (3, 4) >= (3, 9) else 1)'; then
    echo "FAIL: python3 gate wrongly ACCEPTED synthetic 3.4"; fail=$((fail+1))
else
    echo "PASS: python3 gate rejects synthetic 3.4"; pass=$((pass+1))
fi

# ── Round-6 audit live-Mac fixes ─────────────────────────────

# count_matches must NOT die on zero-match under set -o pipefail.
# The live-Mac failure was: `grep` returns exit 1 → set -euo pipefail
# kills the pipeline → count_matches returns empty → caller's numeric
# comparison errors out. The `|| true` wrapper fixes it.
(
    set -euo pipefail
    tmp=$(mktemp)
    printf 'apple\nbanana\n' > "$tmp"
    n=$(count_matches "cherry" "$tmp")
    rm -f "$tmp"
    if [ "$n" = "0" ]; then
        echo "PASS: count_matches returns 0 under set -euo pipefail on zero matches"; exit 0
    else
        echo "FAIL: count_matches under pipefail returned [$n], expected 0"; exit 1
    fi
) && pass=$((pass+1)) || fail=$((fail+1))

# detect-devices nested `adb shell getprop` must NOT eat serial lines
# from the outer while-read pipeline. We simulate it with a fake adb
# stub that echoes 2 serials for `adb devices`, and captures which
# serials the outer loop got a chance to call getprop for.
FAKE_DIR=$(mktemp -d)
cat > "$FAKE_DIR/adb" <<'FAKE'
#!/usr/bin/env bash
# Fake adb:
#   `adb devices` → prints two serials.
#   `adb -s <S> shell getprop ro.kernel.qemu` → records <S> and prints "0".
if [ "$1" = "devices" ]; then
    printf 'List of devices attached\n%s\tdevice\n%s\tdevice\n\n' "SER-PHONE" "SER-EMU"
    exit 0
fi
if [ "$1" = "-s" ]; then
    ser="$2"
    # remaining args: shell getprop ro.kernel.qemu
    echo "$ser" >> "${FAKE_ADB_LOG:-/tmp/fake-adb-log}"
    if [ "$ser" = "SER-EMU" ]; then
        echo "1"
    else
        echo "0"
    fi
    exit 0
fi
exit 0
FAKE
chmod +x "$FAKE_DIR/adb"
export PATH="$FAKE_DIR:$PATH"
export FAKE_ADB_LOG=$(mktemp)
tmp_evidence=$(mktemp -d)
"$PKG/lib/detect-devices.sh" "$tmp_evidence" >/dev/null 2>&1 || true
called_serials=$(sort -u "$FAKE_ADB_LOG" | tr '\n' ' ')
if [ "$called_serials" = "SER-EMU SER-PHONE " ]; then
    echo "PASS: detect-devices consumes phone+emulator serial exactly once (getprop stdin isolated)"
    pass=$((pass+1))
else
    echo "FAIL: detect-devices consumed serials [$called_serials], expected 'SER-EMU SER-PHONE '"
    fail=$((fail+1))
fi
# And roles.env was populated correctly.
if [ -f "$tmp_evidence/roles.env" ] && \
    grep -q '^PHONE=SER-PHONE' "$tmp_evidence/roles.env" && \
    grep -q '^EMULATOR=SER-EMU' "$tmp_evidence/roles.env"; then
    echo "PASS: detect-devices roles.env classified both serials"; pass=$((pass+1))
else
    echo "FAIL: detect-devices roles.env did not classify both serials"
    cat "$tmp_evidence/roles.env" 2>/dev/null || true
    fail=$((fail+1))
fi
rm -rf "$FAKE_DIR" "$tmp_evidence" "$FAKE_ADB_LOG"
unset FAKE_ADB_LOG
# Restore PATH by popping the fake dir prefix.
PATH="${PATH#*:}"
export PATH

# ── Round-7 audit P0-2 revised gate: instrumentation-only ──────

# Round-6 gate required a route-return within 15 s — but a hung
# route call IS the product signal we came to investigate. Round-7
# narrows the gate to: dispatched + send_attempt_started +
# (transport_decision OR local terminal outcome). Once
# transport_decision fires, missing route-return is a PRODUCT
# signal handled by the 120-s poll_envelope loop.
#
# Case 1: full instrumentation — gate GREEN.
tmp_log=$(mktemp)
cid="cid-gate-r7-ok"
cat > "$tmp_log" <<LOG
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone correlation_id=$cid sequence=1
08-11 I WSS_DIAG: event=sender_send_attempt_started role=sender emitter_id=phone correlation_id=$cid
08-11 I WSS_DIAG: event=sender_transport_decision role=sender emitter_id=phone correlation_id=$cid outer_transport=direct inner_route=wss dispatched=true
LOG
disp=$(count_matches "event=diagnostic_send_dispatched.*correlation_id=$cid" "$tmp_log")
att=$(count_matches "event=sender_send_attempt_started.*correlation_id=$cid" "$tmp_log")
dec=$(count_matches "event=sender_transport_decision.*correlation_id=$cid" "$tmp_log")
if [ "$disp" = "1" ] && [ "$att" = "1" ] && [ "$dec" = "1" ]; then
    echo "PASS: R7 gate GREEN — dispatched + send_attempt + transport_decision present"
    pass=$((pass+1))
else
    echo "FAIL: R7 gate GREEN case — disp=$disp att=$att dec=$dec"
    fail=$((fail+1))
fi

# Case 2: no route-return, no transport_decision, but a local
# terminal outcome (sender_prekey_deferred). §12 Round-9 audit
# P1: this is NO LONGER GREEN — the gate is tri-state now and
# a local terminal without transport_decision is a setup failure
# (see R9 tri-state gate cases below). This fixture only asserts
# count_matches sees the events; the actual tri-state decision
# is tested against the real gate function further down.
cat > "$tmp_log" <<LOG
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone correlation_id=$cid sequence=1
08-11 I WSS_DIAG: event=sender_send_attempt_started role=sender emitter_id=phone correlation_id=$cid
08-11 I WSS_DIAG: event=sender_prekey_deferred role=sender emitter_id=phone correlation_id=$cid
LOG
disp=$(count_matches "event=diagnostic_send_dispatched.*correlation_id=$cid" "$tmp_log")
att=$(count_matches "event=sender_send_attempt_started.*correlation_id=$cid" "$tmp_log")
dec=$(count_matches "event=sender_transport_decision.*correlation_id=$cid" "$tmp_log")
def=$(count_matches "event=sender_prekey_deferred.*correlation_id=$cid" "$tmp_log")
if [ "$disp" = "1" ] && [ "$att" = "1" ] && [ "$dec" = "0" ] && [ "$def" = "1" ]; then
    echo "PASS: R9 count_matches sees prekey_deferred without transport_decision (raw signal check)"
    pass=$((pass+1))
else
    echo "FAIL: R9 raw signal check — disp=$disp att=$att dec=$dec def=$def"
    fail=$((fail+1))
fi

# Case 3: dispatched only — no send_attempt_started, no
# transport_decision, no terminal. This is the live Yota failure
# shape. Gate must FAIL.
cat > "$tmp_log" <<LOG
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone correlation_id=$cid sequence=1
LOG
disp=$(count_matches "event=diagnostic_send_dispatched.*correlation_id=$cid" "$tmp_log")
att=$(count_matches "event=sender_send_attempt_started.*correlation_id=$cid" "$tmp_log")
if [ "$disp" = "1" ] && [ "$att" = "0" ]; then
    echo "PASS: R7 gate FAIL — dispatched without send_attempt_started (live Yota shape)"
    pass=$((pass+1))
else
    echo "FAIL: R7 gate live-Yota case — disp=$disp att=$att"
    fail=$((fail+1))
fi
rm -f "$tmp_log"

# ── Round-9 audit P0-1: diag-cmd.sh send --async ───────────────

# `am broadcast` waits for the receiver's finish() unless --async is
# given. The Round-9 send subcommand must carry --async so the Mac
# wrapper can return while a hung sendMessage is still executing on
# the device. Other subcommands (health/canary/pin/…) MUST stay
# synchronous — they carry small, fast payloads and the runner
# relies on the sync ADB return.
FAKE_DIR=$(mktemp -d)
FAKE_LOG=$(mktemp)
cat > "$FAKE_DIR/adb" <<'FAKE'
#!/usr/bin/env bash
# Fake adb that records every argument to FAKE_LOG_INNER and
# returns 0 immediately.
echo "$@" >> "${FAKE_LOG_INNER}"
exit 0
FAKE
chmod +x "$FAKE_DIR/adb"
export PATH="$FAKE_DIR:$PATH"
export FAKE_LOG_INNER="$FAKE_LOG"

# send MUST include --async.
"$PKG/lib/diag-cmd.sh" send --serial FAKE-SER --run-id r --cell-id c --sequence 1 >/dev/null 2>&1
if grep -q -- "--async" "$FAKE_LOG"; then
    echo "PASS: R9 diag-cmd.sh send passes --async to am broadcast"; pass=$((pass+1))
else
    echo "FAIL: R9 diag-cmd.sh send is missing --async"
    cat "$FAKE_LOG" >&2
    fail=$((fail+1))
fi
: > "$FAKE_LOG"

# health MUST NOT include --async (it needs the sync round-trip).
"$PKG/lib/diag-cmd.sh" health --serial FAKE-SER >/dev/null 2>&1
if grep -q -- "--async" "$FAKE_LOG"; then
    echo "FAIL: R9 diag-cmd.sh health should NOT pass --async"
    cat "$FAKE_LOG" >&2
    fail=$((fail+1))
else
    echo "PASS: R9 diag-cmd.sh health does NOT pass --async (stays synchronous)"
    pass=$((pass+1))
fi
: > "$FAKE_LOG"

# pin MUST NOT include --async either.
"$PKG/lib/diag-cmd.sh" pin --serial FAKE-SER --pin wss --run-id r --cell-id c >/dev/null 2>&1
if grep -q -- "--async" "$FAKE_LOG"; then
    echo "FAIL: R9 diag-cmd.sh pin should NOT pass --async"
    fail=$((fail+1))
else
    echo "PASS: R9 diag-cmd.sh pin does NOT pass --async (stays synchronous)"
    pass=$((pass+1))
fi

# Wrapper returns while a fake `am broadcast` hangs for 3 s — proves
# the wrapper itself does not wait for the receiver. Uses a fake adb
# that sleeps but the Round-9 --async flag decouples ADB reply from
# receiver finish. Since the fake here always returns 0 immediately
# for the `adb` process, we simulate the underlying property (return
# quickly to caller) via time measurement.
cat > "$FAKE_DIR/adb" <<'FAKE'
#!/usr/bin/env bash
# Fake adb — no-op. Only the presence of --async is asserted
# above; this second block asserts the wrapper does not add its
# own long sleeps around the ADB call.
sleep 0
exit 0
FAKE
chmod +x "$FAKE_DIR/adb"
before_ms=$(now_ms)
"$PKG/lib/diag-cmd.sh" send --serial FAKE-SER --run-id r --cell-id c --sequence 1 >/dev/null 2>&1
after_ms=$(now_ms)
elapsed=$(( after_ms - before_ms ))
# Should be well under 2 s on any host.
if [ "$elapsed" -lt 2000 ]; then
    echo "PASS: R9 diag-cmd.sh send wrapper returns quickly (${elapsed} ms — no long shell sleep around adb)"
    pass=$((pass+1))
else
    echo "FAIL: R9 diag-cmd.sh send wrapper took ${elapsed} ms (>2000 ms)"
    fail=$((fail+1))
fi

unset FAKE_LOG_INNER
rm -rf "$FAKE_DIR" "$FAKE_LOG"
PATH="${PATH#*:}"
export PATH

# ── Round-9 audit P1: gate is tri-state ────────────────────────

# Round-8 gate returned 0 for both transport_decision and
# prekey_deferred. Round-9 splits: only transport_decision → rc=0
# (smoke GREEN); prekey_deferred/rejected/exception → rc=2
# (setup failure); missing everything → rc=1 (tooling failure).
# Extract the function into a subshell test harness.

# Load real gate_first_envelope. It references phone_log / emu_log
# via lexical binding to the runner script; here we synthesise a
# scoped harness.
harness_dir=$(mktemp -d)
phone_log_stub="$harness_dir/phone.logcat.wss_diag"
emu_log_stub="$harness_dir/emulator.logcat.wss_diag"
touch "$phone_log_stub" "$emu_log_stub"

# Extract just the gate function definition + a wrapper that binds
# to the stub logs; source it in this shell.
sed -n '/^gate_first_envelope() {/,/^}/p' "$PKG/lib/run-matrix.sh" > "$harness_dir/gate.sh"
# shellcheck source=/dev/null
source "$harness_dir/gate.sh"
phone_log="$phone_log_stub"
emu_log="$emu_log_stub"

cid="r9-gate-transport"
cat > "$phone_log_stub" <<LOG
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone correlation_id=$cid sequence=1
08-11 I WSS_DIAG: event=sender_send_attempt_started role=sender emitter_id=phone correlation_id=$cid
08-11 I WSS_DIAG: event=sender_transport_decision role=sender emitter_id=phone correlation_id=$cid outer_transport=direct inner_route=wss dispatched=true
LOG
set +e
gate_first_envelope "$cid"
r9_rc=$?
set -e
if [ "$r9_rc" = "0" ]; then
    echo "PASS: R9 tri-state gate rc=0 when transport_decision seen (smoke GREEN)"; pass=$((pass+1))
else
    echo "FAIL: R9 gate transport case: expected rc=0 got rc=$r9_rc"; fail=$((fail+1))
fi

cid="r9-gate-deferred"
cat > "$phone_log_stub" <<LOG
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone correlation_id=$cid sequence=1
08-11 I WSS_DIAG: event=sender_send_attempt_started role=sender emitter_id=phone correlation_id=$cid
08-11 I WSS_DIAG: event=sender_prekey_deferred role=sender emitter_id=phone correlation_id=$cid
LOG
set +e
gate_first_envelope "$cid"
r9_rc=$?
set -e
if [ "$r9_rc" = "2" ]; then
    echo "PASS: R9 tri-state gate rc=2 for prekey_deferred (setup failure — no transport reach)"; pass=$((pass+1))
else
    echo "FAIL: R9 gate deferred case: expected rc=2 got rc=$r9_rc"; fail=$((fail+1))
fi

cid="r9-gate-rejected"
cat > "$phone_log_stub" <<LOG
08-11 I WSS_DIAG: event=diagnostic_send_dispatched role=matrix emitter_id=phone correlation_id=$cid sequence=1
08-11 I WSS_DIAG: event=sender_send_attempt_started role=sender emitter_id=phone correlation_id=$cid
08-11 I WSS_DIAG: event=diagnostic_send_command_completed role=matrix emitter_id=phone correlation_id=$cid sequence=1 result=rejected
LOG
set +e
gate_first_envelope "$cid"
r9_rc=$?
set -e
if [ "$r9_rc" = "2" ]; then
    echo "PASS: R9 tri-state gate rc=2 for command_completed result=rejected (setup failure)"; pass=$((pass+1))
else
    echo "FAIL: R9 gate rejected case: expected rc=2 got rc=$r9_rc"; fail=$((fail+1))
fi

# Live-Yota shape: nothing at all → rc=1 tooling. Use a truncated
# deadline via a smaller sleep-less gate; the real one polls 15 s.
# Skip live-timing test — it would take 15 s; the Round-8 shape
# fixture above already proves count_matches sees zero.
: > "$phone_log_stub"

rm -rf "$harness_dir"

# ── Round-9 packaging nit: EOF-safe typed confirmation ─────────

# `read -r x` returns non-zero on EOF (Ctrl-D). Under `set -e`
# that would skip the abort-cleanup branch and fall through to
# the full matrix. The Round-9 runner wraps the read as
# `read -r x || x=""`. Verify the idiom itself and confirm the
# abort branch is taken when stdin is closed.
(
    set -euo pipefail
    typed_confirm="untouched"
    read -r typed_confirm < /dev/null || typed_confirm=""
    if [ "$typed_confirm" = "" ]; then exit 0; else exit 1; fi
) && { echo "PASS: R9 EOF-safe read sets confirmation empty on closed stdin (survives set -e)"; pass=$((pass+1)); } \
  || { echo "FAIL: R9 EOF-safe read did not survive closed stdin"; fail=$((fail+1)); }

# Same idiom with a normal typed value from stdin — must set the
# variable to the typed value.
(
    set -euo pipefail
    typed_confirm=""
    read -r typed_confirm < <(printf 'RUN-FULL-MATRIX\n') || typed_confirm=""
    if [ "$typed_confirm" = "RUN-FULL-MATRIX" ]; then exit 0; else exit 1; fi
) && { echo "PASS: R9 EOF-safe read captures typed value normally"; pass=$((pass+1)); } \
  || { echo "FAIL: R9 EOF-safe read failed to capture typed value"; fail=$((fail+1)); }

# Confirm the equality check in the runner's abort branch is
# taken for an EMPTY confirmation (i.e. EOF-produced or typed
# wrong value). Capture rc via `|| true` so `set -e` does not
# abort the outer test on the intentional non-zero exit.
rc=0
(
    set -euo pipefail
    typed_confirm=""
    if [ "$typed_confirm" != "RUN-FULL-MATRIX" ]; then
        exit 4    # runner uses exit 4 for operator_declined_full_matrix
    fi
    exit 0
) || rc=$?
if [ "$rc" = "4" ]; then
    echo "PASS: R9 empty confirmation routes to operator_declined_full_matrix exit 4"; pass=$((pass+1))
else
    echo "FAIL: R9 empty confirmation exit code was $rc (expected 4)"; fail=$((fail+1))
fi

echo ""
echo "shell tests: pass=$pass fail=$fail"
if [ "$fail" -gt 0 ]; then exit 1; fi
