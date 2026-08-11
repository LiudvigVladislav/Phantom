#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — runner helpers, extracted so
# tests/test_shell.sh can drive the REAL functions against synthetic
# logs (§12 Round-3 audit P1-2).
#
# All helpers are pure: they read only from paths + arguments, write
# nothing to disk, and must never call `adb`. That keeps the tests
# reproducible on a Mac without a device attached.

# require portable.sh loaded first
: "${PORTABLE_SH_LOADED:=0}"
if [ "$PORTABLE_SH_LOADED" != "1" ]; then
  # shellcheck source=portable.sh
  source "$(dirname "${BASH_SOURCE[0]}")/portable.sh"
  PORTABLE_SH_LOADED=1
fi

# wait_for_pin_active_in_log
#   Arguments: <pin> <cell_id> <run_id> <expected_emitter> <log_path>
#   Prints    : nothing (return status only)
#   Returns   : 0 if a matching diagnostic_pin_active line is present
#                in <log_path>; 1 otherwise.
#   Match     : event=diagnostic_pin_active AND run_id=<run_id> AND
#                cell_id=<cell_id> AND pin=<pin> AND emitter_id=<expected_emitter>.
#
# Round-3 P1-2: match ALL four fields (was: pin+cell only). A stale
# prior-run pin_active line will not satisfy this even if pin+cell
# repeat, because run_id changes every run.
wait_for_pin_active_in_log() {
  local pin="$1" cell_id="$2" run_id="$3" emitter="$4" log="$5"
  [ -f "$log" ] || return 1
  grep -h "event=diagnostic_pin_active" "$log" 2>/dev/null \
    | grep " run_id=$run_id " \
    | grep " cell_id=$cell_id " \
    | grep " pin=$pin" \
    | grep -q " emitter_id=$emitter "
}

# find_send_cid_in_log
#   Arguments: <cell_id> <sequence> <run_id> <expected_emitter>
#              <not_before_host_ms> <sender_log> <host_to_sender_skew_ms>
#   Prints    : the correlation_id of the newest matching line, or empty
#   Returns   : 0 if a matching line exists; 1 otherwise
#   Match     : event=diagnostic_send_dispatched AND run_id=<run_id> AND
#                cell_id=<cell_id> AND sequence=<sequence> AND
#                emitter_id=<expected_emitter> AND
#                wall_utc_ms >= (not_before_host_ms - host_to_sender_skew_ms)
#                — the last term converts the host-clock not-before into
#                a device-clock not-before so a device up to
#                host_to_sender_skew_ms behind the Mac still qualifies.
#
# Round-3 P1-2: reject stale prior-run lines by requiring run_id AND
# a not-before timestamp.
# Round-4 P1-3: the not-before comparison is now cross-clock aware —
# the caller passes command_start_ms captured on the Mac plus the
# preflight-measured host_to_sender_skew_ms, and the helper compares
# on a device clock the two agree on. Without this, a positive skew
# (host clock ahead of device) at the contract's 30-second ceiling
# would reject every genuine current-run CID.
find_send_cid_in_log() {
  local cell_id="$1" sequence="$2" run_id="$3" emitter="$4"
  local not_before_host_ms="$5" sender_log="$6"
  local host_to_sender_skew_ms="${7:-0}"
  [ -f "$sender_log" ] || return 1
  # Cross-clock: convert the host not-before into a device not-before.
  # host_to_sender_skew_ms = host_ms - device_ms at preflight time.
  # device_now ≈ host_now - host_to_sender_skew_ms.
  local not_before_device_ms=$(( not_before_host_ms - host_to_sender_skew_ms ))
  # Pick the newest matching line (tail -1); check its wall_utc_ms;
  # fail if it predates the device-clock not-before.
  local line
  line=$(grep -h "event=diagnostic_send_dispatched" "$sender_log" 2>/dev/null \
         | grep " run_id=$run_id " \
         | grep " cell_id=$cell_id " \
         | grep " sequence=$sequence" \
         | grep " emitter_id=$emitter " \
         | tail -1)
  [ -n "$line" ] || return 1
  local wall
  wall=$(printf '%s\n' "$line" | grep -oE 'wall_utc_ms=[0-9]+' | tail -1 | cut -d= -f2)
  if [ -z "$wall" ]; then
    return 1
  fi
  if [ "$wall" -lt "$not_before_device_ms" ]; then
    return 1
  fi
  # Extract correlation_id.
  printf '%s\n' "$line" | grep -oE 'correlation_id=[^ ]+' | tail -1 | cut -d= -f2-
}

# refuse_matrix_rerun
#   Arguments: <evidence_dir>
#   Returns   : 0 if the directory is safe to run into; 1 if a prior
#                matrix.json exists (in which case a message is written
#                to stderr).
#
# Round-3 P1-2: prevents accidental re-runs into a populated evidence
# directory, which would mix events from two runs into one bundle.
refuse_matrix_rerun() {
  local out="$1"
  if [ -f "$out/matrix.json" ]; then
    echo "refuse: matrix.json already exists at $out/matrix.json — " \
         "each matrix run needs a fresh evidence directory (rerun preflight)" >&2
    return 1
  fi
  return 0
}
