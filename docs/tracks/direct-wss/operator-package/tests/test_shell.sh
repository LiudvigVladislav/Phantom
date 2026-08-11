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

# ── Round-4 audit P0-1: LF-only bytes in every packaged file ────

# Runs against the checked-out tree. On a Windows clone with
# core.autocrlf=true, the .gitattributes scoped rule for
# operator-package must keep these LF. On the packaged tarball,
# a separate build-time scan runs before sealing (via the handoff
# script), but we ALSO run it here so a broken checkout is caught
# by the shell fixture suite from the operator's Mac.
cr_offenders=0
for f in "$PKG/run-yota-wss-diagnostic.sh" \
         "$PKG/preflight.sh" \
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
    echo "PASS: no CR bytes in any packaged .sh/.py under the operator-package tree"
    pass=$((pass+1))
else
    echo "FAIL: $cr_offenders file(s) carry CR bytes"
    fail=$((fail+1))
fi

echo ""
echo "shell tests: pass=$pass fail=$fail"
if [ "$fail" -gt 0 ]; then exit 1; fi
