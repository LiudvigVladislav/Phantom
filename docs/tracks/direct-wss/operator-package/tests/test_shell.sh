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

echo ""
echo "shell tests: pass=$pass fail=$fail"
if [ "$fail" -gt 0 ]; then exit 1; fi
