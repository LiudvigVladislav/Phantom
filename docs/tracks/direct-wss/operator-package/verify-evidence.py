#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — evidence verifier v4
# (§12 Round-3 audit repair).
#
# Round-3 closes six adversarial false-GREEN paths that survived
# Round-2:
#
#   P0-1  Every non-blocked cell must have exactly 5 sender_enqueue
#         events, each with a non-empty correlation_id; the 5 CIDs
#         are unique per cell AND globally unique in the run; and
#         a matching set of 5 diagnostic_send_dispatched events
#         (sequences exactly {1,2,3,4,5}) on the sender device.
#
#   P0-2  emitter_id MUST match the source-file device label. All
#         transport-decision events are inspected (not next(...)) —
#         a contradictory second decision is a violation. All
#         route-return events are inspected too; opposite-route
#         events are forbidden; at least one successful WSS return
#         (WSS pin) or REST completion (REST pin) is required.
#
#   P0-3  matrix.run_id, preflight.run_id and device-manifest.run_id
#         must all match; matrix.rest_capability must match
#         preflight.rest_capability; skew values duplicated in
#         preflight+manifest must match; all preflight gates
#         (apk_variant=debug, env entries "ok", canary "ok",
#         paired_conversation_count_ok, emitter_ids_set,
#         yota_confirmed, radio_confirmed, rest_capability
#         well-formed) are enforced; manifest carries a valid
#         default-data operator_numeric.
#
#   P1-1  rest_capability=="disabled" ⇒ both canonical REST cells
#         are BLOCKED and carry zero matrix enqueues; conversely
#         rest_capability ∈ {"enabled","unknown"} ⇒ the REST cells
#         are NOT BLOCKED.
#
#   P1-3  Corrupt / wrong-typed JSON files are surfaced as integrity
#         issues — never as a Python exception.
#
# Retained from Round-2 (unchanged):
#   * Global correlation-ID uniqueness inside a run.
#   * Per-sender + per-run + per-cell pin coverage timeline
#     (session_started restored=false invalidates prior pin_active).
#   * BLOCKED cells only permitted on rest cells.
#   * Preflight-boolean value check (must be True, not just present).
#   * 120-s window driven off host clock via --host-now-ms.
#   * Safe integer parsing (never raises ValueError).
#   * Per-device host↔device skew for wall-time alignment.
#   * Canonical 8-triple matrix set enforcement.

from __future__ import annotations
import argparse
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from typing import Optional

WSS_120_S = 120_000

REQUIRED_FILES = [
    "phone.logcat.wss_diag",
    "emulator.logcat.wss_diag",
    "matrix.json",
    "preflight.json",
    "device-manifest.json",
]

FIELD_RE = re.compile(r"(\w+)=(\S+)")

BANNED_TOKEN_SUBSTRINGS = ["text=", "plaintext=", "content=", "auth=", "token=", "sealed=", "hex="]
KEY_LIKE_HEX_RE = re.compile(r"(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")

EXPECTED_CELL_COUNT = 8
EXPECTED_ENVELOPES_PER_CELL = 5
EXPECTED_SEQUENCE_SET = frozenset({1, 2, 3, 4, 5})
ALLOWED_PINS = {"wss", "rest"}
ALLOWED_DIRECTIONS = {"p2e", "e2p"}
ALLOWED_SCENARIOS = {"after-connect", "after-idle", "bg-fg", "control"}
ALLOWED_ROLES = {"sender", "recipient", "matrix"}
ALLOWED_DEVICES = {"phone", "emulator"}
OPERATOR_NUMERIC_RE = re.compile(r"^\d{5,6}$")

CANONICAL_MATRIX_TRIPLES = frozenset({
    ("wss", "p2e", "after-connect"),
    ("wss", "e2p", "after-connect"),
    ("wss", "p2e", "after-idle"),
    ("wss", "e2p", "after-idle"),
    ("wss", "p2e", "bg-fg"),
    ("wss", "e2p", "bg-fg"),
    ("rest", "p2e", "control"),
    ("rest", "e2p", "control"),
})
CANONICAL_REST_CELL_IDS = frozenset({"rest.p2e.control", "rest.e2p.control"})

RECIPIENT_EVENTS = {
    "recipient_deliver_received",
    "recipient_message_persisted",
    "recipient_ack_deliver_sent",
}
SENDER_EVENTS = {
    "sender_enqueue",
    "sender_transport_decision",
    "sender_wss_send_returned",
    "sender_rest_post_completed",
    "sender_relay_ack_received",
    "sender_ack_watchdog_requeued",
}
MATRIX_EVENTS = {
    "diagnostic_session_started",
    "diagnostic_canary",
    "diagnostic_state_cleared",
    "diagnostic_pin_active",
    "diagnostic_send_dispatched",
    "diagnostic_send_rejected_no_paired_conversation",
    "diagnostic_send_rejected_multiple_paired_conversations",
}
CROSS_RUN_TOLERATED_EVENTS = {
    "diagnostic_session_started",
    "diagnostic_canary",
    "diagnostic_state_cleared",
}

REQUIRED_PREFLIGHT_ENV_TOOLS = ("adb", "python3", "bash", "jq")


@dataclass
class WssEvent:
    device: str
    event: str
    role: Optional[str] = None
    correlation_id: Optional[str] = None
    run_id: Optional[str] = None
    cell_id: Optional[str] = None
    emitter_id: Optional[str] = None
    wall_utc_ms: Optional[int] = None
    monotonic_ms: Optional[int] = None
    outer_transport: Optional[str] = None
    inner_route: Optional[str] = None
    dedup_gate: Optional[str] = None
    outcome_flag: Optional[str] = None
    relay_acceptance: Optional[str] = None
    pin: Optional[str] = None
    dispatched: Optional[bool] = None
    restored: Optional[bool] = None
    sequence: Optional[int] = None
    raw: str = ""


@dataclass
class CellReport:
    cell_id: str
    pin: str
    direction: str
    scenario: str
    blocked: bool
    envelopes: int = 0
    outcome: str = ""             # Delivered once | Unresolved | PENDING | BLOCKED
    issues: list[str] = field(default_factory=list)


@dataclass
class VerifyReport:
    run_id: Optional[str]
    integrity_ok: bool
    integrity_issues: list[str]
    cells: list[CellReport]
    product_outcome: str          # GREEN | RED | PENDING


def _safe_int(v: Optional[str]) -> Optional[int]:
    if v is None:
        return None
    try:
        return int(v)
    except ValueError:
        return None


def _load_json(path: str, expected_top_type: type) -> tuple[object, Optional[str]]:
    """Return (parsed_value, error) — error is None on success. Never raises.
    Round-3 P1-3: JSON parse errors and wrong top-level type surface as
    integrity issues, not as tracebacks."""
    if not os.path.exists(path):
        return (expected_top_type() if expected_top_type in (dict, list) else None,
                f"required file missing: {os.path.basename(path)}")
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = f.read()
        if raw.strip() == "":
            return (expected_top_type() if expected_top_type in (dict, list) else None,
                    f"{os.path.basename(path)}: empty file")
        parsed = json.loads(raw)
    except (OSError, json.JSONDecodeError) as e:
        return (expected_top_type() if expected_top_type in (dict, list) else None,
                f"{os.path.basename(path)}: JSON parse error: {e}")
    if not isinstance(parsed, expected_top_type):
        return (expected_top_type() if expected_top_type in (dict, list) else None,
                f"{os.path.basename(path)}: top-level type is {type(parsed).__name__}, expected {expected_top_type.__name__}")
    return (parsed, None)


def parse_events(path: str, device_label: str) -> tuple[list[WssEvent], list[str]]:
    out: list[WssEvent] = []
    parse_errors: list[str] = []
    if not os.path.exists(path):
        return out, parse_errors
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for lineno, line in enumerate(f, 1):
            if "WSS_DIAG" not in line:
                continue
            m = re.search(r"WSS_DIAG(?::| :) (.*)$", line)
            if not m:
                continue
            payload = m.group(1).strip()
            fields = dict(FIELD_RE.findall(payload))

            wall_ms = _safe_int(fields.get("wall_utc_ms"))
            if "wall_utc_ms" in fields and wall_ms is None:
                parse_errors.append(f"{device_label}:{lineno} malformed wall_utc_ms={fields['wall_utc_ms']!r}")
            mono_ms = _safe_int(fields.get("monotonic_ms"))
            if "monotonic_ms" in fields and mono_ms is None:
                parse_errors.append(f"{device_label}:{lineno} malformed monotonic_ms={fields['monotonic_ms']!r}")
            seq = _safe_int(fields.get("sequence"))
            if "sequence" in fields and seq is None:
                parse_errors.append(f"{device_label}:{lineno} malformed sequence={fields['sequence']!r}")

            out.append(WssEvent(
                device=device_label,
                event=fields.get("event", ""),
                role=fields.get("role"),
                correlation_id=fields.get("correlation_id"),
                run_id=fields.get("run_id"),
                cell_id=fields.get("cell_id"),
                emitter_id=fields.get("emitter_id"),
                wall_utc_ms=wall_ms,
                monotonic_ms=mono_ms,
                outer_transport=fields.get("outer_transport"),
                inner_route=fields.get("inner_route"),
                dedup_gate=fields.get("dedup_gate"),
                outcome_flag=fields.get("outcome_flag"),
                relay_acceptance=fields.get("relay_acceptance"),
                pin=fields.get("pin"),
                dispatched=(fields["dispatched"] == "true") if "dispatched" in fields else None,
                restored=(fields["restored"] == "true") if "restored" in fields else None,
                sequence=seq,
                raw=line.rstrip("\n"),
            ))
    return out, parse_errors


def cell_direction_to_emitters(direction: str) -> tuple[str, str]:
    if direction == "p2e":
        return "phone", "emulator"
    if direction == "e2p":
        return "emulator", "phone"
    return "unknown", "unknown"


def _per_sender_pin_coverage(
    cell_pin: str, run_id: str, cell_id: str, sender_device: str,
    sender_emitter: str, envelope_wall: int, all_events: list[WssEvent],
) -> tuple[bool, str]:
    """Round-4 audit P0-3: `session_started` may cover the current
    envelope ONLY when its `restored=true` AND its run/cell/pin/device/
    emitter ALL match the current cell/sender. `restored=None` (missing
    field) or `restored=false` or mismatched metadata invalidates any
    prior pin_active. `restored=false` sessions and mismatched
    `restored=true` sessions both count as coverage resets."""
    per_sender = [
        e for e in all_events
        if e.device == sender_device and e.wall_utc_ms is not None
        and e.wall_utc_ms <= envelope_wall
        and (e.event == "diagnostic_pin_active" or e.event == "diagnostic_session_started")
    ]
    per_sender.sort(key=lambda e: e.wall_utc_ms or 0)
    latest_pin: Optional[str] = None
    latest_meta: str = ""
    for ev in per_sender:
        if ev.event == "diagnostic_session_started":
            # A session event that does NOT positively cover the current
            # cell resets pin coverage. This handles restored=false,
            # restored=None (missing/malformed), and restored=true with
            # a mismatched run/cell/pin/emitter — none of which can
            # certify the current envelope.
            covers = (
                ev.restored is True
                and ev.run_id == run_id
                and ev.cell_id == cell_id
                and ev.pin == cell_pin
                and ev.emitter_id == sender_emitter
            )
            if covers:
                latest_pin = ev.pin
                latest_meta = (f"session_started(restored=true, run={ev.run_id}, "
                                f"cell={ev.cell_id}, pin={ev.pin}, emitter={ev.emitter_id}) "
                                f"at {ev.wall_utc_ms}")
            else:
                latest_pin = "none"
                latest_meta = (f"session_started reset (restored={ev.restored}, "
                                f"run={ev.run_id}, cell={ev.cell_id}, pin={ev.pin}, "
                                f"emitter={ev.emitter_id}) at {ev.wall_utc_ms}")
        elif ev.event == "diagnostic_pin_active":
            if (ev.run_id == run_id and ev.cell_id == cell_id
                    and ev.emitter_id == sender_emitter):
                latest_pin = ev.pin
                latest_meta = f"pin_active(run={ev.run_id}, cell={ev.cell_id}, pin={ev.pin}) at {ev.wall_utc_ms}"
            else:
                latest_pin = "none"
                latest_meta = f"pin_active(other cell/run/emitter={ev.run_id}/{ev.cell_id}/{ev.emitter_id}) at {ev.wall_utc_ms}"
    if latest_pin == cell_pin:
        return (True, latest_meta)
    return (False, f"latest pin before enqueue on {sender_device}: {latest_pin or 'none'} ({latest_meta or 'no events'}) — expected {cell_pin} for run={run_id} cell={cell_id}")


def _validate_preflight(preflight: dict) -> list[str]:
    """Round-3 audit P0-3: every gate the operator's preflight ran
    must be recorded AND set to a passing value. Any missing field
    or wrong value is integrity RED."""
    problems: list[str] = []

    if preflight.get("apk_variant") != "debug":
        problems.append(f"preflight.apk_variant != 'debug': {preflight.get('apk_variant')!r}")

    env = preflight.get("env")
    if not isinstance(env, dict):
        problems.append(f"preflight.env missing or wrong type: {type(env).__name__}")
    else:
        for tool in REQUIRED_PREFLIGHT_ENV_TOOLS:
            if env.get(tool) != "ok":
                problems.append(f"preflight.env.{tool} != 'ok': {env.get(tool)!r}")

    for req_bool in ("yota_confirmed", "emitter_ids_set", "radio_confirmed",
                     "paired_conversation_count_ok"):
        if preflight.get(req_bool) is not True:
            problems.append(f"preflight.{req_bool} is not True: {preflight.get(req_bool)!r}")

    if preflight.get("canary") != "ok":
        problems.append(f"preflight.canary != 'ok': {preflight.get('canary')!r}")

    if preflight.get("rest_capability") not in ("enabled", "disabled", "unknown"):
        problems.append(f"preflight.rest_capability invalid: {preflight.get('rest_capability')!r}")

    if not isinstance(preflight.get("run_id"), str) or not preflight.get("run_id"):
        problems.append("preflight.run_id missing or empty")

    return problems


def _validate_manifest(manifest: dict) -> list[str]:
    problems: list[str] = []
    for req in ("run_id", "host_to_phone_skew_ms", "host_to_emulator_skew_ms",
                "phone_serial", "emulator_serial",
                "dual_sim_report_operator_numeric"):
        if req not in manifest:
            problems.append(f"device-manifest.json missing required key: {req}")
    if not isinstance(manifest.get("run_id"), str) or not manifest.get("run_id"):
        problems.append("device-manifest.run_id missing or empty")
    # Round-4 P1-2: serials MUST be non-empty strings.
    for k in ("phone_serial", "emulator_serial"):
        v = manifest.get(k)
        if v is None:
            continue
        if not isinstance(v, str) or not v:
            problems.append(f"device-manifest.{k} not a non-empty string: {v!r}")
    for k in ("host_to_phone_skew_ms", "host_to_emulator_skew_ms"):
        v = manifest.get(k)
        if v is None:
            continue
        if not isinstance(v, int) or isinstance(v, bool):
            problems.append(f"device-manifest.{k} not an integer: {v!r}")
            continue
        if abs(v) > 30_000:
            problems.append(f"|device-manifest.{k}| > 30 000: {v}")
    op = manifest.get("dual_sim_report_operator_numeric")
    if op is not None:
        if not isinstance(op, str) or not OPERATOR_NUMERIC_RE.match(op):
            problems.append(f"device-manifest.dual_sim_report_operator_numeric not a 5-6 digit string: {op!r}")
    return problems


def _validate_cross_file_run_consistency(
    matrix: dict, preflight: dict, manifest: dict,
) -> list[str]:
    """Round-3 audit P0-3: matrix / preflight / manifest metadata
    that binds a bundle to a single run must all agree."""
    problems: list[str] = []
    m_run = matrix.get("run_id")
    p_run = preflight.get("run_id")
    d_run = manifest.get("run_id")
    if m_run and p_run and m_run != p_run:
        problems.append(f"matrix.run_id={m_run!r} != preflight.run_id={p_run!r}")
    if m_run and d_run and m_run != d_run:
        problems.append(f"matrix.run_id={m_run!r} != device-manifest.run_id={d_run!r}")
    if p_run and d_run and p_run != d_run:
        problems.append(f"preflight.run_id={p_run!r} != device-manifest.run_id={d_run!r}")

    m_cap = matrix.get("rest_capability")
    p_cap = preflight.get("rest_capability")
    if m_cap is not None and p_cap is not None and m_cap != p_cap:
        problems.append(
            f"matrix.rest_capability={m_cap!r} != preflight.rest_capability={p_cap!r}",
        )

    for k in ("host_to_phone_skew_ms", "host_to_emulator_skew_ms"):
        pv = preflight.get(k)
        dv = manifest.get(k)
        if pv is None or dv is None:
            continue
        if pv != dv:
            problems.append(
                f"preflight.{k}={pv} != device-manifest.{k}={dv}",
            )
    return problems


def integrity_check_bundle(
    events: list[WssEvent], out: str, matrix: dict, preflight: dict, manifest: dict,
    file_load_errors: list[str],
) -> list[str]:
    problems: list[str] = list(file_load_errors)

    for name in REQUIRED_FILES:
        p = os.path.join(out, name)
        if not os.path.exists(p):
            if not any(name in msg for msg in problems):
                problems.append(f"required file missing: {name}")
            continue
        if os.path.getsize(p) == 0:
            problems.append(f"required file empty: {name}")

    if not events:
        problems.append("no WSS_DIAG events parsed — bundle is not usable")

    for e in events:
        if e.role is None:
            problems.append(f"event missing role: {e.event} @ {e.device}")
        elif e.role not in ALLOWED_ROLES:
            problems.append(f"event role not in whitelist: {e.role}")
        if e.wall_utc_ms is None:
            problems.append(f"event missing wall_utc_ms: {e.event} @ {e.device}")
        if e.emitter_id is None:
            problems.append(f"event missing emitter_id: {e.event} @ {e.device}")
        elif e.emitter_id not in ALLOWED_DEVICES:
            problems.append(f"emitter_id not phone/emulator: {e.emitter_id} @ {e.device}")
        elif e.device in ALLOWED_DEVICES and e.emitter_id != e.device:
            # Round-3 audit P0-2: emitter_id MUST match source-file device.
            problems.append(
                f"event provenance mismatch: {e.event} on {e.device} log carries emitter_id={e.emitter_id}",
            )

    for e in events:
        if e.event in RECIPIENT_EVENTS and e.role != "recipient":
            problems.append(
                f"recipient event {e.event} has role={e.role} (expected 'recipient') @ {e.device}",
            )
        if e.event in SENDER_EVENTS and e.role != "sender":
            problems.append(
                f"sender event {e.event} has role={e.role} (expected 'sender') @ {e.device}",
            )
        if e.event in MATRIX_EVENTS and e.role != "matrix":
            problems.append(
                f"matrix event {e.event} has role={e.role} (expected 'matrix') @ {e.device}",
            )

    for dev in ALLOWED_DEVICES:
        if not any(e.event == "diagnostic_session_started" and e.device == dev for e in events):
            problems.append(f"no diagnostic_session_started event on {dev}")

    # Round-4 audit P0-3: every diagnostic_session_started must carry a
    # real boolean `restored` (parser reads only "true"/"false"; a
    # missing field yields None) AND a whitelisted pin
    # {none, wss, rest}. Missing / malformed fields are integrity RED —
    # they must NOT be silently ignored by _per_sender_pin_coverage.
    for e in events:
        if e.event != "diagnostic_session_started":
            continue
        if e.restored is None:
            problems.append(
                f"diagnostic_session_started missing/malformed restored field on {e.device} @ wall={e.wall_utc_ms}",
            )
        if e.pin not in ("none", "wss", "rest"):
            problems.append(
                f"diagnostic_session_started pin not in whitelist on {e.device} @ wall={e.wall_utc_ms}: {e.pin!r}",
            )

    for e in events:
        if e.event == "unresolved_120s_marker" or e.outcome_flag == "unresolved_120s_marker":
            problems.append(f"forbidden verifier-only classification emitted by client: {e.raw}")

    run_id_matrix_raw = matrix.get("run_id")
    if not isinstance(run_id_matrix_raw, str) or not run_id_matrix_raw:
        problems.append(f"matrix.json missing or wrong-typed run_id: {run_id_matrix_raw!r}")
        run_id_matrix: Optional[str] = None
    else:
        run_id_matrix = run_id_matrix_raw
    cells_raw = matrix.get("cells")
    if not isinstance(cells_raw, list):
        problems.append(f"matrix.cells missing or wrong type: {type(cells_raw).__name__}")
        cells_raw = []
    if len(cells_raw) != EXPECTED_CELL_COUNT:
        problems.append(f"matrix.json cells count={len(cells_raw)} (expected {EXPECTED_CELL_COUNT})")
    seen_cell_ids: set[str] = set()
    observed_triples: set[tuple[str, str, str]] = set()
    for idx, cell in enumerate(cells_raw):
        if not isinstance(cell, dict):
            problems.append(f"matrix.cells[{idx}] is not an object: {type(cell).__name__}")
            continue
        # Round-4 P1-2: validate every nested field type BEFORE any set
        # membership / set insertion / sorting. All strings must be non-
        # empty and members of their whitelist; `blocked` must be a real
        # bool. Wrong-typed fields never crash the verifier.
        cid = cell.get("cell_id")
        if not isinstance(cid, str) or not cid:
            problems.append(f"matrix.cells[{idx}].cell_id not a non-empty string: {cid!r}")
            continue
        if cid in seen_cell_ids:
            problems.append(f"duplicate cell_id in matrix: {cid}")
        seen_cell_ids.add(cid)
        pin = cell.get("pin")
        direction = cell.get("direction")
        scenario = cell.get("scenario")
        if not isinstance(pin, str) or pin not in ALLOWED_PINS:
            problems.append(f"cell {cid} pin not a whitelisted string: {pin!r}")
        if not isinstance(direction, str) or direction not in ALLOWED_DIRECTIONS:
            problems.append(f"cell {cid} direction not a whitelisted string: {direction!r}")
        if not isinstance(scenario, str) or scenario not in ALLOWED_SCENARIOS:
            problems.append(f"cell {cid} scenario not a whitelisted string: {scenario!r}")
        # Round-4 P0-2: blocked MUST be a real bool. `"false"` is a string,
        # not the boolean False; the prior `bool(...)` at report time
        # coerced any truthy value including non-empty strings and skipped
        # WSS cells silently.
        blocked_val = cell.get("blocked")
        if not isinstance(blocked_val, bool):
            problems.append(f"cell {cid} blocked must be a JSON boolean, got {type(blocked_val).__name__}: {blocked_val!r}")
        if blocked_val is True and pin != "rest":
            problems.append(f"cell {cid} BLOCKED but pin != rest — only REST cells may be BLOCKED")
        if (isinstance(pin, str) and isinstance(direction, str)
                and isinstance(scenario, str)):
            observed_triples.add((pin, direction, scenario))
            if cid != f"{pin}.{direction}.{scenario}":
                problems.append(f"cell {cid} does not match pin.direction.scenario shape ({pin}.{direction}.{scenario})")

    for t in sorted(CANONICAL_MATRIX_TRIPLES - observed_triples):
        problems.append(f"canonical matrix triple missing: {t}")
    for t in sorted(observed_triples - CANONICAL_MATRIX_TRIPLES):
        problems.append(f"non-canonical matrix triple present: {t}")

    problems += _validate_preflight(preflight)
    problems += _validate_manifest(manifest)
    problems += _validate_cross_file_run_consistency(matrix, preflight, manifest)

    # Round-3 audit P1-1: bidirectional REST BLOCKED / rest_capability parity.
    rest_cap = preflight.get("rest_capability")
    rest_cells = [c for c in cells_raw if isinstance(c, dict)
                  and isinstance(c.get("cell_id"), str)
                  and c.get("cell_id") in CANONICAL_REST_CELL_IDS]
    if rest_cap == "disabled":
        for c in rest_cells:
            if not c.get("blocked"):
                problems.append(
                    f"rest_capability=disabled but REST cell {c.get('cell_id')} is not BLOCKED",
                )
        # And zero matrix enqueues into those cells.
        for c in rest_cells:
            cid = c.get("cell_id")
            enq_in_blocked = [
                e for e in events
                if e.event == "sender_enqueue" and e.cell_id == cid
                and e.run_id == run_id_matrix
            ]
            if enq_in_blocked:
                problems.append(
                    f"rest_capability=disabled + BLOCKED cell {cid} has {len(enq_in_blocked)} enqueue(s) — expected zero",
                )
    elif rest_cap in ("enabled", "unknown"):
        for c in rest_cells:
            if c.get("blocked"):
                problems.append(
                    f"rest_capability={rest_cap} but REST cell {c.get('cell_id')} is BLOCKED",
                )

    # Every event's run_id must match matrix.run_id (session/canary/state_cleared exempted).
    if run_id_matrix:
        for e in events:
            if e.event in CROSS_RUN_TOLERATED_EVENTS:
                continue
            if e.run_id is None:
                problems.append(f"event {e.event} missing run_id")
            elif e.run_id != run_id_matrix and e.run_id != "-":
                problems.append(f"event {e.event} has run_id={e.run_id} but matrix.run_id={run_id_matrix}")

    # Round-3 audit P0-1: every sender_enqueue must carry a non-empty
    # correlation_id. A missing CID lets a cell's five envelopes join
    # through a single delivery triplet via `cid=None`.
    enqueue_by_cid: dict[str, list[WssEvent]] = {}
    for e in events:
        if e.event == "sender_enqueue":
            if not e.correlation_id or e.correlation_id == "-":
                problems.append(
                    f"sender_enqueue missing correlation_id on {e.device} @ wall_utc_ms={e.wall_utc_ms} cell_id={e.cell_id}",
                )
                continue
            enqueue_by_cid.setdefault(e.correlation_id, []).append(e)
    for cid, evs in enqueue_by_cid.items():
        if len(evs) > 1:
            problems.append(f"correlation_id used multiple times: {cid} ({len(evs)} enqueues)")

    for name in ("phone.logcat.wss_diag", "emulator.logcat.wss_diag"):
        p = os.path.join(out, name)
        if not os.path.exists(p):
            continue
        with open(p, "r", encoding="utf-8", errors="ignore") as f:
            for i, line in enumerate(f, 1):
                if "WSS_DIAG" not in line:
                    continue
                for tok in BANNED_TOKEN_SUBSTRINGS:
                    if tok in line:
                        problems.append(f"{name}:{i} banned token '{tok}'")
                if KEY_LIKE_HEX_RE.search(line):
                    problems.append(f"{name}:{i} 64-char lowercase hex substring")

    return problems


def classify_envelope(
    envelope_enqueue: WssEvent,
    corr_events: list[WssEvent],
    cell_pin: str,
    cell_id: str, run_id: str,
    sender_device: str, recipient_device: str,
    sender_emitter: str, recipient_emitter: str,
    all_events: list[WssEvent],
    host_now_ms: int,
    sender_wall_to_host_offset_ms: int,
) -> tuple[str, list[str]]:
    issues: list[str] = []

    if envelope_enqueue.run_id != run_id:
        issues.append(f"enqueue run_id={envelope_enqueue.run_id} != {run_id}")
    if envelope_enqueue.cell_id != cell_id:
        issues.append(f"enqueue cell_id={envelope_enqueue.cell_id} != {cell_id}")
    if envelope_enqueue.emitter_id != sender_emitter:
        issues.append(f"enqueue emitter={envelope_enqueue.emitter_id} != {sender_emitter}")
    if envelope_enqueue.device != sender_device:
        issues.append(f"enqueue device={envelope_enqueue.device} != {sender_device}")

    for e in corr_events:
        if e.event in SENDER_EVENTS:
            if e.role != "sender":
                issues.append(f"sender event {e.event} has role={e.role} != 'sender'")
            if e.device != sender_device or e.run_id != run_id or e.cell_id != cell_id:
                issues.append(f"sender event {e.event} owner mismatch (device={e.device} run={e.run_id} cell={e.cell_id})")
            if e.emitter_id != sender_emitter:
                issues.append(f"sender event {e.event} emitter mismatch: {e.emitter_id}")

    for e in corr_events:
        if e.event in RECIPIENT_EVENTS:
            if e.role != "recipient":
                issues.append(f"recipient event {e.event} has role={e.role} != 'recipient'")
            if e.device != recipient_device:
                issues.append(f"recipient event {e.event} device mismatch: {e.device}")
            if e.emitter_id != recipient_emitter:
                issues.append(f"recipient event {e.event} emitter mismatch: {e.emitter_id}")
            if e.run_id != run_id or e.cell_id != cell_id:
                issues.append(f"recipient event {e.event} owner mismatch (run={e.run_id} cell={e.cell_id})")

    (covered, why) = _per_sender_pin_coverage(
        cell_pin, run_id, cell_id, sender_device, sender_emitter,
        envelope_enqueue.wall_utc_ms or 0, all_events,
    )
    if not covered:
        issues.append(f"pin coverage: {why}")

    # Round-3 audit P0-2 + Round-4 audit P1-1: inspect ALL
    # sender_transport_decision events. A contradictory second decision
    # is a violation. Every decision MUST be dispatched=True (not
    # missing, not False), outer=direct, inner=cell pin — the
    # production emitter always sets all three fields.
    decisions = [e for e in corr_events if e.event == "sender_transport_decision"]
    if not decisions:
        issues.append("missing sender_transport_decision")
    else:
        for d in decisions:
            if d.outer_transport != "direct":
                issues.append(f"transport decision outer_transport={d.outer_transport} != direct")
            if d.inner_route != cell_pin:
                issues.append(f"transport decision inner_route={d.inner_route} != cell pin {cell_pin}")
            if d.dispatched is not True:
                issues.append(f"transport decision dispatched must be true, got {d.dispatched!r}")
        outer_values = {d.outer_transport for d in decisions}
        if len(outer_values) > 1:
            issues.append(f"contradictory sender_transport_decision outer_transport values: {sorted(str(x) for x in outer_values)}")
        inner_values = {d.inner_route for d in decisions}
        if len(inner_values) > 1:
            issues.append(f"contradictory sender_transport_decision inner_route values: {sorted(str(x) for x in inner_values)}")

    # Round-3 audit P0-2 + Round-4 audit P1-1: inspect ALL route-return
    # events. Opposite route forbidden. At least one successful
    # matching return required with dispatched=True (WSS) or
    # accepted/duplicate acceptance (REST). Missing `inner_route` is
    # NOT accepted as a successful match — the production emitter
    # always sets it and its absence indicates a schema drift the
    # verifier must not ignore.
    wss_returns = [e for e in corr_events if e.event == "sender_wss_send_returned"]
    rest_returns = [e for e in corr_events if e.event == "sender_rest_post_completed"]
    if cell_pin == "wss":
        if rest_returns:
            issues.append(f"WSS cell has {len(rest_returns)} REST completion event(s) — opposite route")
        for w in wss_returns:
            if w.inner_route != "wss":
                issues.append(f"sender_wss_send_returned inner_route must be wss, got {w.inner_route!r}")
        if not any(w.dispatched is True and w.inner_route == "wss" for w in wss_returns):
            issues.append("missing successful sender_wss_send_returned (dispatched=true, inner_route=wss)")
    elif cell_pin == "rest":
        if wss_returns:
            issues.append(f"REST cell has {len(wss_returns)} WSS return event(s) — opposite route")
        for r in rest_returns:
            if r.inner_route != "rest":
                issues.append(f"sender_rest_post_completed inner_route must be rest, got {r.inner_route!r}")
        if not any(
            r.inner_route == "rest" and r.relay_acceptance in ("accepted", "duplicate")
            for r in rest_returns
        ):
            issues.append("missing successful sender_rest_post_completed (inner_route=rest, relay_acceptance ∈ accepted/duplicate)")

    deliver_fresh = [
        e for e in corr_events
        if e.event == "recipient_deliver_received"
        and e.role == "recipient"
        and e.device == recipient_device
        and e.dedup_gate == "fresh"
    ]
    persist = [
        e for e in corr_events
        if e.event == "recipient_message_persisted"
        and e.role == "recipient"
        and e.device == recipient_device
    ]
    ack = [
        e for e in corr_events
        if e.event == "recipient_ack_deliver_sent"
        and e.role == "recipient"
        and e.device == recipient_device
    ]

    missing: list[str] = []
    if not deliver_fresh:
        missing.append("recipient_deliver_received(fresh)")
    if not persist:
        missing.append("recipient_message_persisted")
    if not ack:
        missing.append("recipient_ack_deliver_sent")
    if len(deliver_fresh) > 1:
        missing.append("2nd recipient_deliver_received(fresh) — dedup violation")

    if issues:
        return ("Unresolved", issues + missing)

    if missing:
        enqueue_wall_device = envelope_enqueue.wall_utc_ms or 0
        enqueue_wall_host = enqueue_wall_device + sender_wall_to_host_offset_ms
        age_ms = host_now_ms - enqueue_wall_host
        if age_ms < WSS_120_S:
            return ("PENDING", missing + [f"age_ms={age_ms} < 120_000 (host_now={host_now_ms}, sender_wall_host={enqueue_wall_host})"])
        return ("Unresolved", missing + [f"age_ms={age_ms}"])

    return ("Delivered once", [])


def _check_cell_dispatched_binding(
    cr: CellReport, enqueues: list[WssEvent], cell_evts: list[WssEvent],
    sender_device: str, sender_emitter: str,
) -> None:
    """Round-3 audit P0-1 (cell-level): exactly 5 diagnostic_send_dispatched
    events on the sender device; each has a non-empty correlation_id
    matching one enqueue's CID one-to-one; sequences are exactly
    {1,2,3,4,5}. Populates cr.issues with problems — the caller sets
    cr.outcome based on this being nonempty."""
    dispatched = [e for e in cell_evts if e.event == "diagnostic_send_dispatched"]

    for d in dispatched:
        if d.device != sender_device:
            cr.issues.append(f"diagnostic_send_dispatched on {d.device}, expected sender {sender_device}")
        if d.emitter_id != sender_emitter:
            cr.issues.append(f"diagnostic_send_dispatched emitter_id={d.emitter_id} != {sender_emitter}")
        if not d.correlation_id or d.correlation_id == "-":
            cr.issues.append(f"diagnostic_send_dispatched missing correlation_id (sequence={d.sequence})")

    dispatch_cids = {e.correlation_id for e in dispatched
                     if e.correlation_id and e.correlation_id != "-"}
    enqueue_cids = {e.correlation_id for e in enqueues
                    if e.correlation_id and e.correlation_id != "-"}
    if dispatch_cids != enqueue_cids:
        missing_here = enqueue_cids - dispatch_cids
        extra_here = dispatch_cids - enqueue_cids
        if missing_here:
            cr.issues.append(
                f"enqueue CIDs without matching diagnostic_send_dispatched: {sorted(list(missing_here))[:5]}",
            )
        if extra_here:
            cr.issues.append(
                f"diagnostic_send_dispatched CIDs without matching enqueue: {sorted(list(extra_here))[:5]}",
            )

    observed_sequences = sorted(
        [e.sequence for e in dispatched if e.sequence is not None]
    )
    if set(observed_sequences) != EXPECTED_SEQUENCE_SET:
        cr.issues.append(
            f"diagnostic_send_dispatched sequence set {observed_sequences} != {sorted(EXPECTED_SEQUENCE_SET)}",
        )

    if len(dispatched) != EXPECTED_ENVELOPES_PER_CELL:
        cr.issues.append(
            f"diagnostic_send_dispatched count={len(dispatched)} != {EXPECTED_ENVELOPES_PER_CELL}",
        )


def _derive_host_now_ms(all_events: list[WssEvent], manifest: dict, override: Optional[int]) -> int:
    if override is not None:
        return override
    return int(time.time_ns() // 1_000_000)


def build_report(out: str, host_now_override_ms: Optional[int] = None) -> VerifyReport:
    phone_events, phone_parse_errors = parse_events(os.path.join(out, "phone.logcat.wss_diag"), "phone")
    emu_events, emu_parse_errors = parse_events(os.path.join(out, "emulator.logcat.wss_diag"), "emulator")
    all_events = phone_events + emu_events

    matrix_path = os.path.join(out, "matrix.json")
    manifest_path = os.path.join(out, "device-manifest.json")
    preflight_path = os.path.join(out, "preflight.json")

    matrix, matrix_err = _load_json(matrix_path, dict)
    manifest, manifest_err = _load_json(manifest_path, dict)
    preflight, preflight_err = _load_json(preflight_path, dict)
    file_load_errors = [e for e in (matrix_err, manifest_err, preflight_err) if e]

    assert isinstance(matrix, dict) and isinstance(manifest, dict) and isinstance(preflight, dict)

    integrity_issues = integrity_check_bundle(
        all_events, out, matrix, preflight, manifest, file_load_errors,
    )
    integrity_issues += phone_parse_errors + emu_parse_errors
    integrity_ok = not integrity_issues

    run_id_matrix = matrix.get("run_id") or ""

    host_to_phone_ms = manifest.get("host_to_phone_skew_ms", 0)
    host_to_emu_ms = manifest.get("host_to_emulator_skew_ms", 0)
    if not isinstance(host_to_phone_ms, int) or isinstance(host_to_phone_ms, bool):
        host_to_phone_ms = 0
    if not isinstance(host_to_emu_ms, int) or isinstance(host_to_emu_ms, bool):
        host_to_emu_ms = 0

    run_scoped_events = [
        e for e in all_events if e.run_id in (run_id_matrix, None, "-")
    ]

    host_now_ms = _derive_host_now_ms(all_events, manifest, host_now_override_ms)

    cells_report: list[CellReport] = []
    aggregate_pending = False
    aggregate_red = False
    cells_iter = matrix.get("cells", [])
    if not isinstance(cells_iter, list):
        cells_iter = []
    for cell in cells_iter:
        if not isinstance(cell, dict):
            continue
        # Round-4 P0-2 / P1-2: only strings/bools are allowed. If any
        # nested field is wrong-typed, integrity_issues already carries
        # the RED marker; we skip the cell here rather than crash on
        # a hash / f-string / classification pass.
        raw_cid = cell.get("cell_id")
        raw_pin = cell.get("pin")
        raw_direction = cell.get("direction")
        raw_scenario = cell.get("scenario")
        raw_blocked = cell.get("blocked")
        if not isinstance(raw_cid, str) or not raw_cid:
            continue
        if not isinstance(raw_pin, str) or raw_pin not in ALLOWED_PINS:
            continue
        if not isinstance(raw_direction, str) or raw_direction not in ALLOWED_DIRECTIONS:
            continue
        if not isinstance(raw_scenario, str) or raw_scenario not in ALLOWED_SCENARIOS:
            continue
        cr = CellReport(
            cell_id=raw_cid,
            pin=raw_pin,
            direction=raw_direction,
            scenario=raw_scenario,
            # P0-2: `is True` — a string "false" (or "true", 0, 1, list…)
            # is neither blocked nor unblocked; it must have already
            # tripped integrity RED above. We treat any non-True value
            # as NOT blocked here, so a stringly-typed "true" cannot
            # skip a WSS cell to a false BLOCKED verdict.
            blocked=(raw_blocked is True),
        )
        if cr.blocked:
            cr.outcome = "BLOCKED"
            cells_report.append(cr)
            continue

        sender_emitter, recipient_emitter = cell_direction_to_emitters(cr.direction)
        sender_device, recipient_device = sender_emitter, recipient_emitter

        cell_evts = [
            e for e in run_scoped_events
            if e.cell_id == cr.cell_id and e.run_id == run_id_matrix
        ]
        enqueues = sorted(
            [e for e in cell_evts if e.event == "sender_enqueue"],
            key=lambda e: e.wall_utc_ms or 0,
        )
        cr.envelopes = len(enqueues)

        if cr.envelopes != EXPECTED_ENVELOPES_PER_CELL:
            cr.issues.append(f"envelope count = {cr.envelopes} (expected {EXPECTED_ENVELOPES_PER_CELL})")
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue

        cids = [e.correlation_id for e in enqueues if e.correlation_id and e.correlation_id != "-"]
        if len(cids) != EXPECTED_ENVELOPES_PER_CELL:
            cr.issues.append(f"only {len(cids)} of {EXPECTED_ENVELOPES_PER_CELL} enqueues carry a correlation_id")
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue
        if len(set(cids)) != len(cids):
            cr.issues.append("duplicate correlation_id within cell")
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue

        # Round-3 audit P0-1: 5 dispatched events matching the enqueues.
        pre_dispatch_issues = list(cr.issues)
        _check_cell_dispatched_binding(cr, enqueues, cell_evts, sender_device, sender_emitter)
        if len(cr.issues) > len(pre_dispatch_issues):
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue

        sender_offset_ms = host_to_phone_ms if sender_device == "phone" else host_to_emu_ms

        env_outcomes: list[str] = []
        for enq in enqueues:
            cid = enq.correlation_id
            corr_evts = [
                e for e in run_scoped_events
                if e.correlation_id == cid
                and e.run_id == run_id_matrix
                and e.cell_id == cr.cell_id
            ]
            (o, mm) = classify_envelope(
                envelope_enqueue=enq,
                corr_events=corr_evts,
                cell_pin=cr.pin,
                cell_id=cr.cell_id,
                run_id=run_id_matrix,
                sender_device=sender_device,
                recipient_device=recipient_device,
                sender_emitter=sender_emitter,
                recipient_emitter=recipient_emitter,
                all_events=all_events,
                host_now_ms=host_now_ms,
                sender_wall_to_host_offset_ms=sender_offset_ms,
            )
            if mm:
                cr.issues.append(f"{cid[:8] if cid else '?'}: {'; '.join(mm)}")
            env_outcomes.append(o)

        if any(o == "Unresolved" for o in env_outcomes):
            cr.outcome = "Unresolved"
            aggregate_red = True
        elif any(o == "PENDING" for o in env_outcomes):
            cr.outcome = "PENDING"
            aggregate_pending = True
        else:
            cr.outcome = "Delivered once"
        cells_report.append(cr)

    if aggregate_red:
        product_outcome = "RED"
    elif aggregate_pending:
        product_outcome = "PENDING"
    else:
        product_outcome = "GREEN"

    return VerifyReport(
        run_id=matrix.get("run_id"),
        integrity_ok=integrity_ok,
        integrity_issues=integrity_issues,
        cells=cells_report,
        product_outcome=product_outcome,
    )


def render_markdown(rep: VerifyReport) -> str:
    lines = ["# Direct WSS Yota-First — verification report v4"]
    lines.append("")
    lines.append(f"run_id: `{rep.run_id}`")
    lines.append("")
    lines.append("## evidence_integrity")
    lines.append(f"- **{'GREEN' if rep.integrity_ok else 'RED'}**")
    for p in rep.integrity_issues:
        lines.append(f"  - {p}")
    lines.append("")
    lines.append("## product_outcome per cell")
    lines.append("")
    lines.append("| cell_id | pin | dir | scenario | envs | outcome | issues |")
    lines.append("|---|---|---|---|---:|---|---|")
    for c in rep.cells:
        issues = "; ".join(c.issues)[:400]
        lines.append(f"| `{c.cell_id}` | {c.pin} | {c.direction} | {c.scenario} | {c.envelopes} | **{c.outcome}** | {issues} |")
    lines.append("")
    lines.append(f"## Aggregate: product_outcome = **{rep.product_outcome}**")
    lines.append("")
    lines.append("Notes: relay ingress / dedup / persistence are NOT verified here")
    lines.append("(client-only first-pass). `recipient_message_persisted` proves")
    lines.append("chat-store write; not pixel-level visibility. `Recovered` is")
    lines.append("NOT distinguished in WSS-1 first pass (§12 P0-7).")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("out", help="evidence directory")
    parser.add_argument("--host-now-ms", type=int, default=None,
                        help="override the verification clock (fixture use only)")
    ns = parser.parse_args()
    out = ns.out
    if not os.path.isdir(out):
        print(f"verify FAILED: not a directory: {out}", file=sys.stderr)
        return 1

    rep = build_report(out, host_now_override_ms=ns.host_now_ms)
    md = render_markdown(rep)
    report_path = os.path.join(out, "verification-report.md")
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(md)

    print(f"evidence_integrity={'GREEN' if rep.integrity_ok else 'RED'}")
    print(f"product_outcome={rep.product_outcome}")
    print(f"report: {report_path}")

    if not rep.integrity_ok:
        return 1
    if rep.product_outcome == "PENDING":
        return 3
    if rep.product_outcome == "RED":
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
