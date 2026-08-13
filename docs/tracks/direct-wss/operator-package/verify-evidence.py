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
    # §12 Round-6 audit P0-2: atomic matrix-completion marker written
    # by run-matrix.sh at end-of-run (whether it completed the full
    # 8×5 matrix or was aborted by the fail-fast gate). Its absence
    # is integrity RED — a bundle without this marker cannot prove
    # the matrix reached a defined stopping point rather than being
    # killed mid-cell by an operator or by a process crash.
    "matrix_completion.json",
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
SHA256_HEX_RE = re.compile(r"^[0-9a-f]{64}$")
# §12 WSS-2: operator-parameterization label whitelist. Additions
# require both an operator-numeric mapping and a new
# `--operator <LABEL>` accepted by `lib/operator-args.sh`.
OPERATOR_LABEL_WHITELIST = frozenset({"YOTA", "TELE2"})

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
    "sender_send_attempt_started",
    "sender_enqueue",
    "sender_transport_decision",
    "sender_wss_send_returned",
    "sender_rest_post_completed",
    "sender_prekey_deferred",
    "sender_relay_ack_received",
    "sender_ack_watchdog_requeued",
}
MATRIX_EVENTS = {
    "diagnostic_session_started",
    "diagnostic_canary",
    "diagnostic_state_cleared",
    "diagnostic_pin_active",
    "diagnostic_send_dispatched",
    "diagnostic_send_command_completed",
    "diagnostic_send_rejected_no_paired_conversation",
    "diagnostic_send_rejected_multiple_paired_conversations",
}
# §12 Round-9 audit P2: production emits only handled | rejected |
# exception; deferred is already represented by a separate event
# (`sender_prekey_deferred`). Reserving `deferred` in this closed
# schema without a corresponding emit site would extend the schema
# beyond code that produces it.
ALLOWED_COMMAND_RESULTS = frozenset({"handled", "rejected", "exception"})
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
    # §12 Round-6 audit P0-1: diagnostic_send_command_completed.result
    # ∈ {accepted, rejected, exception}. Any other value is a schema
    # violation surfaced by the parser as an integrity issue.
    result: Optional[str] = None
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
    product_outcome: str          # GREEN | RED | PENDING | NOT_EVALUABLE
    # §12 WSS-2: operator label carried into the rendered report
    # title. None on legacy Yota-only bundles that predate the
    # operator-parameterization field.
    operator_label: Optional[str] = None


def _safe_int(v: Optional[str]) -> Optional[int]:
    if v is None:
        return None
    try:
        return int(v)
    except ValueError:
        return None


# §12 Round-5 audit P0: strict diagnostic boolean parser. Only the
# literal lower-case strings "true" and "false" are accepted for
# `restored` and `dispatched`. Any other present value (garbage,
# capitalisation, numeric, empty) is a schema violation and returns
# None; the caller must surface it as an integrity issue. The prior
# `fields["x"] == "true"` idiom silently converted any non-"true"
# present value to Python False and let restored=garbage certify a
# whole bundle as GREEN.
def _strict_bool(v: Optional[str]) -> Optional[bool]:
    if v == "true":
        return True
    if v == "false":
        return False
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

            # §12 Round-5 audit P0: strict-only booleans. Present-
            # but-malformed values are a schema violation, NOT a
            # silent False.
            dispatched_val: Optional[bool] = None
            if "dispatched" in fields:
                dispatched_val = _strict_bool(fields["dispatched"])
                if dispatched_val is None:
                    parse_errors.append(
                        f"{device_label}:{lineno} malformed dispatched={fields['dispatched']!r} (only 'true'/'false' allowed)",
                    )
            restored_val: Optional[bool] = None
            if "restored" in fields:
                restored_val = _strict_bool(fields["restored"])
                if restored_val is None:
                    parse_errors.append(
                        f"{device_label}:{lineno} malformed restored={fields['restored']!r} (only 'true'/'false' allowed)",
                    )

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
                dispatched=dispatched_val,
                restored=restored_val,
                sequence=seq,
                result=fields.get("result"),
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


WSS2_COMPANION_FIELDS = (
    "operator_label",
    "expected_operator_numeric",
)


def _validate_operator_gate(preflight: dict, matrix: dict) -> list[str]:
    """§12 WSS-2 (Round-1 audit P1-2): legacy and WSS-2 schemas are
    STRICTLY DISJOINT. A bundle is confirmed under EXACTLY ONE of:

    - **Legacy Round-9 shape** — `yota_confirmed=True` AND every
      WSS-2 companion field (`operator_confirmed`, `operator_label`,
      `expected_operator_numeric`) ABSENT. The archived Yota
      baseline (`run-yota-20260812T171418Z`) matches this shape.

    - **WSS-2 shape** — `operator_confirmed=True` AND
      `operator_label` in whitelist AND `expected_operator_numeric`
      valid AND `matrix.run_id` starts with `run-<label lower>-`
      AND `yota_confirmed == (label == YOTA)`.

    Any WSS-2 companion field present without
    `operator_confirmed=True` → integrity RED — a real
    misconfiguration (someone injected TELE2 metadata into a Yota
    bundle) reproducibly false-GREENed under the pre-Round-1
    gate."""
    problems: list[str] = []

    yota = preflight.get("yota_confirmed")
    opc = preflight.get("operator_confirmed")

    # Type gates — accept only real JSON booleans or absent.
    for key, v in (("yota_confirmed", yota), ("operator_confirmed", opc)):
        if v is not None and not isinstance(v, bool):
            problems.append(
                f"preflight.{key} must be a JSON boolean or absent, got {type(v).__name__}: {v!r}",
            )

    yota_ok = isinstance(yota, bool) and yota is True
    opc_ok = isinstance(opc, bool) and opc is True

    companions_present = [
        f for f in WSS2_COMPANION_FIELDS if f in preflight
    ]

    # Fail-open guard: any WSS-2 companion field present without
    # operator_confirmed=True → RED. This is the exact fixture the
    # Round-1 audit reproduced: yota_confirmed=true +
    # operator_confirmed=false + operator_label=TELE2.
    if companions_present and not opc_ok:
        problems.append(
            f"preflight has WSS-2 companion fields {companions_present} but operator_confirmed is not True "
            "— legacy Round-9 shape (yota_confirmed alone) must not carry WSS-2 fields",
        )

    if not yota_ok and not opc_ok:
        problems.append(
            "preflight has no operator confirmation: neither yota_confirmed nor operator_confirmed is True",
        )
        return problems

    if opc_ok:
        # WSS-2 shape: full companion validation.
        label = preflight.get("operator_label")
        if not isinstance(label, str) or label not in OPERATOR_LABEL_WHITELIST:
            problems.append(
                f"preflight.operator_label not a whitelisted string ({sorted(OPERATOR_LABEL_WHITELIST)}): {label!r}",
            )
        expected = preflight.get("expected_operator_numeric")
        if not isinstance(expected, str) or not OPERATOR_NUMERIC_RE.match(expected):
            problems.append(
                f"preflight.expected_operator_numeric not a 5-6 digit string: {expected!r}",
            )
        # yota_confirmed is a strict function of operator_label.
        if isinstance(label, str) and label == "YOTA":
            if yota is not True:
                problems.append(
                    "preflight.operator_label=YOTA requires yota_confirmed=True (legacy wire-compat)",
                )
        elif isinstance(label, str) and label in OPERATOR_LABEL_WHITELIST:
            if yota is True:
                problems.append(
                    f"preflight.operator_label={label!r} but yota_confirmed=True — labels contradict",
                )
        # run_id prefix binds the matrix to the confirmed carrier.
        run_id = matrix.get("run_id")
        if isinstance(label, str) and isinstance(run_id, str):
            expected_prefix = f"run-{label.lower()}-"
            if not run_id.startswith(expected_prefix):
                problems.append(
                    f"matrix.run_id={run_id!r} does not start with expected prefix {expected_prefix!r} for operator_label={label!r}",
                )
    else:
        # Legacy Round-9 shape: `operator_confirmed` must be absent
        # (present-but-False is also fine — it just means the
        # preflight didn't run the WSS-2 flow — but the companion
        # fields, if present, would already have tripped the fail-
        # open guard above).
        if "operator_confirmed" in preflight and opc is not None and opc is not False:
            problems.append(
                f"legacy Round-9 shape may not carry operator_confirmed={opc!r} — either drop the field or set operator_confirmed=True and add the WSS-2 companion fields",
            )

    return problems


def _validate_preflight(preflight: dict, matrix: dict) -> list[str]:
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

    # §12 Round-8 audit P1: the signed-prekey readiness gate must be
    # PROVEN by the evidence, not just asserted at preflight time. If
    # the boolean is missing or is any non-True value (`false`,
    # `"true"`, `1`, `None`, list …) the verifier rejects the bundle.
    # `type(v) is bool` + `v is True` — same strict semantics as the
    # matrix `blocked` field (§12.4 P0-2).
    #
    # §12 WSS-2 operator parameterization: the operator-confirmation
    # gate now accepts EITHER the legacy `yota_confirmed` field
    # (still True on YOTA-labelled runs; the archived Yota bundle
    # from `run-yota-20260812T171418Z` also carries only this
    # field) OR the new `operator_confirmed` field. The two are
    # cross-verified in `_validate_operator_gate` below and must
    # not contradict when both are present.
    for req_bool in ("emitter_ids_set", "radio_confirmed",
                     "paired_conversation_count_ok",
                     "phone_signed_prekey_ready",
                     "emulator_signed_prekey_ready"):
        v = preflight.get(req_bool)
        if not isinstance(v, bool):
            problems.append(
                f"preflight.{req_bool} must be a JSON boolean, got {type(v).__name__}: {v!r}",
            )
        elif v is not True:
            problems.append(f"preflight.{req_bool} is not True: {v!r}")
    problems += _validate_operator_gate(preflight, matrix)

    if preflight.get("canary") != "ok":
        problems.append(f"preflight.canary != 'ok': {preflight.get('canary')!r}")

    if preflight.get("rest_capability") not in ("enabled", "disabled", "unknown"):
        problems.append(f"preflight.rest_capability invalid: {preflight.get('rest_capability')!r}")

    if not isinstance(preflight.get("run_id"), str) or not preflight.get("run_id"):
        problems.append("preflight.run_id missing or empty")

    # §12 Round-5 audit P1: bundled diagnostic APK SHA-256 must be
    # recorded here after preflight verified it against BOTH the
    # local packaged APK AND the installed base.apk on both devices.
    apk_hash = preflight.get("diagnostic_apk_sha256")
    if apk_hash is None:
        problems.append("preflight.diagnostic_apk_sha256 missing (preflight must verify + record)")
    elif not isinstance(apk_hash, str) or not SHA256_HEX_RE.match(apk_hash):
        problems.append(
            f"preflight.diagnostic_apk_sha256 not a 64-char lowercase hex string: {apk_hash!r}",
        )

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
    # §12 Round-5 audit P1: the manifest also carries the diagnostic
    # APK SHA-256 (the same value written to preflight.json). Both
    # must be present, both well-formed, and their cross-check is
    # performed in `_validate_cross_file_run_consistency`.
    apk_hash = manifest.get("diagnostic_apk_sha256")
    if apk_hash is None:
        problems.append("device-manifest.diagnostic_apk_sha256 missing (preflight must verify + record on both devices)")
    elif not isinstance(apk_hash, str) or not SHA256_HEX_RE.match(apk_hash):
        problems.append(
            f"device-manifest.diagnostic_apk_sha256 not a 64-char lowercase hex string: {apk_hash!r}",
        )
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
    # §12 Round-5 audit P1: cross-check diagnostic APK SHA between
    # preflight.json and device-manifest.json. Preflight writes the
    # same hash to both (recorded after verifying it matched the
    # local APK AND the installed base.apk on both devices).
    pa = preflight.get("diagnostic_apk_sha256")
    da = manifest.get("diagnostic_apk_sha256")
    if isinstance(pa, str) and isinstance(da, str) and pa != da:
        problems.append(
            f"preflight.diagnostic_apk_sha256={pa!r} != device-manifest.diagnostic_apk_sha256={da!r}",
        )

    # §12 WSS-2 operator parameterization: cross-file consistency
    # for the new operator fields — the same operator_label +
    # expected_operator_numeric MUST appear in both files, and
    # the observed `dual_sim_report_operator_numeric` in the
    # manifest MUST equal the expected value. This is what
    # prevents an operator from re-using a Yota manifest under a
    # Tele2 preflight prompt (or vice versa).
    p_label = preflight.get("operator_label")
    d_label = manifest.get("operator_label")
    if p_label is not None or d_label is not None:
        if p_label != d_label:
            problems.append(
                f"preflight.operator_label={p_label!r} != device-manifest.operator_label={d_label!r}",
            )
    p_exp = preflight.get("expected_operator_numeric")
    d_exp = manifest.get("expected_operator_numeric")
    if p_exp is not None or d_exp is not None:
        if p_exp != d_exp:
            problems.append(
                f"preflight.expected_operator_numeric={p_exp!r} != device-manifest.expected_operator_numeric={d_exp!r}",
            )
    observed = manifest.get("dual_sim_report_operator_numeric")
    if isinstance(p_exp, str) and isinstance(observed, str):
        if OPERATOR_NUMERIC_RE.match(p_exp) and OPERATOR_NUMERIC_RE.match(observed):
            if p_exp != observed:
                problems.append(
                    f"preflight.expected_operator_numeric={p_exp!r} != device-manifest.dual_sim_report_operator_numeric={observed!r}",
                )
    return problems


def integrity_check_bundle(
    events: list[WssEvent], out: str, matrix: dict, preflight: dict, manifest: dict,
    completion: dict, file_load_errors: list[str],
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

    problems += _validate_preflight(preflight, matrix)
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

    # §12 Round-6 audit P0-2: matrix_completion.json must exist,
    # carry a matching run_id, and its cells_ran count must equal
    # the number of non-blocked cells in matrix.json OR carry an
    # abort_reason. Aborted runs are always integrity RED — they
    # produced an incomplete matrix and the runner said so.
    if not isinstance(completion.get("run_id"), str) or not completion.get("run_id"):
        problems.append("matrix_completion.json missing or wrong-typed run_id")
    elif run_id_matrix and completion.get("run_id") != run_id_matrix:
        problems.append(
            f"matrix_completion.run_id={completion.get('run_id')!r} != matrix.run_id={run_id_matrix!r}",
        )
    non_blocked = [c for c in cells_raw if isinstance(c, dict) and c.get("blocked") is not True]
    expected_cells_ran = len(non_blocked)
    cells_ran = completion.get("cells_ran")
    abort_reason = completion.get("abort_reason")
    if abort_reason is not None:
        if not isinstance(abort_reason, str) or not abort_reason:
            problems.append(f"matrix_completion.abort_reason not a non-empty string: {abort_reason!r}")
        else:
            problems.append(
                f"matrix_completion reports abort_reason={abort_reason!r} — matrix did not run to completion",
            )
    else:
        if not isinstance(cells_ran, int) or isinstance(cells_ran, bool):
            problems.append(f"matrix_completion.cells_ran not an integer: {cells_ran!r}")
        elif cells_ran != expected_cells_ran:
            problems.append(
                f"matrix_completion.cells_ran={cells_ran} != expected {expected_cells_ran} (non-blocked cells)",
            )

    # §12 Round-7 audit P0-1 (revised from Round-6): every
    # `diagnostic_send_dispatched` correlation_id must have a matching
    # `sender_send_attempt_started` on the SAME sender device. That
    # event is the entry-point signal from `DefaultMessagingService`
    # (fires before encryption). `sender_enqueue` is NOT the right
    # binding target — it fires only inside `afterEncrypt` and is
    # legitimately absent when a `PeerBundleMissingException` is
    # caught (a `sender_prekey_deferred` event then fires instead).
    # Round-5 lived Yota run had 15 dispatched events and ZERO
    # enqueues — under the Round-7 rule both attempt_started and
    # enqueue would be absent, and the missing attempt_started is
    # what proves the production send path was not reached.
    dispatched_by_cid: dict[str, WssEvent] = {}
    for e in events:
        if e.event == "diagnostic_send_dispatched" and e.correlation_id:
            dispatched_by_cid[e.correlation_id] = e
    attempt_started_cids_set: set[str] = {
        e.correlation_id for e in events
        if e.event == "sender_send_attempt_started" and e.correlation_id
    }
    for cid, disp in dispatched_by_cid.items():
        if cid not in attempt_started_cids_set:
            # Exclude the preflight REST-capability probe cell — its
            # dispatched intentionally has no attempt_started matching
            # (the probe classifies via sender_rest_post_completed only).
            if disp.cell_id == "preflight.rest_capability":
                continue
            problems.append(
                f"diagnostic_send_dispatched cid={cid[:8]}… (cell={disp.cell_id}) "
                f"has no matching sender_send_attempt_started — production send path never reached",
            )

    # §12 Round-6 audit P0-1: diagnostic_send_command_completed.result
    # must be one of {accepted, rejected, exception}. Presence proves
    # the receiver observed a definitive outcome from the coordinator.
    for e in events:
        if e.event == "diagnostic_send_command_completed":
            if e.result not in ALLOWED_COMMAND_RESULTS:
                problems.append(
                    f"diagnostic_send_command_completed has invalid result={e.result!r} "
                    f"(allowed: {sorted(ALLOWED_COMMAND_RESULTS)})",
                )

    # Round-3 audit P0-1 (Round-7 revision): apply the missing-CID +
    # global-uniqueness rules to `sender_send_attempt_started` —
    # that event fires once per send attempt (including deferred
    # ones), while `sender_enqueue` only fires on successful
    # encryption, so keying uniqueness on enqueue would miss
    # deferred-attempt collisions. Enqueue keeps its own
    # missing-CID + duplicate check as a defence in depth (the two
    # events share the same CID by design).
    attempt_by_cid: dict[str, list[WssEvent]] = {}
    for e in events:
        if e.event == "sender_send_attempt_started":
            if not e.correlation_id or e.correlation_id == "-":
                problems.append(
                    f"sender_send_attempt_started missing correlation_id on {e.device} @ wall_utc_ms={e.wall_utc_ms} cell_id={e.cell_id}",
                )
                continue
            attempt_by_cid.setdefault(e.correlation_id, []).append(e)
    for cid, evs in attempt_by_cid.items():
        if len(evs) > 1:
            problems.append(f"correlation_id used multiple times: {cid} ({len(evs)} send attempts)")

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

    # §12 Round-7 audit P0-1: presence of `sender_prekey_deferred`
    # for this envelope's correlation_id means the send never reached
    # transport (fresh-pair prekey publish latency, catch block wrote
    # a WAITING placeholder, sendMessage returned success-shaped
    # Result). The envelope cannot be authoritatively delivered even
    # if every other signal is present. Reporter downgrades it to
    # Unresolved with a `deferred` reason so the reader knows the
    # cause was tooling / prekey timing, not transport failure.
    deferred_for_cid = [
        e for e in corr_events if e.event == "sender_prekey_deferred"
    ]
    if deferred_for_cid:
        issues.append(
            "sender_prekey_deferred: envelope did not reach transport "
            "(peer prekey missing at send time)",
        )

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
    cr: CellReport, send_attempts: list[WssEvent], cell_evts: list[WssEvent],
    sender_device: str, sender_emitter: str,
) -> None:
    """Round-3 audit P0-1 + Round-7 revision (cell-level): exactly 5
    diagnostic_send_dispatched events on the sender device; each has
    a non-empty correlation_id matching one send_attempt_started's
    CID one-to-one; sequences are exactly {1,2,3,4,5}. Populates
    cr.issues with problems — the caller sets cr.outcome based on
    this being nonempty.

    Round-7 note: the binding key is now `sender_send_attempt_started`
    (the send-entry signal), not `sender_enqueue` — under a
    PeerBundleMissingException, enqueue is legitimately absent while
    attempt_started is present and every attempt still has a
    dispatched pair."""
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
    attempt_cids = {e.correlation_id for e in send_attempts
                     if e.correlation_id and e.correlation_id != "-"}
    if dispatch_cids != attempt_cids:
        missing_here = attempt_cids - dispatch_cids
        extra_here = dispatch_cids - attempt_cids
        if missing_here:
            cr.issues.append(
                f"send_attempt_started CIDs without matching diagnostic_send_dispatched: {sorted(list(missing_here))[:5]}",
            )
        if extra_here:
            cr.issues.append(
                f"diagnostic_send_dispatched CIDs without matching send_attempt_started: {sorted(list(extra_here))[:5]}",
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
    completion_path = os.path.join(out, "matrix_completion.json")

    matrix, matrix_err = _load_json(matrix_path, dict)
    manifest, manifest_err = _load_json(manifest_path, dict)
    preflight, preflight_err = _load_json(preflight_path, dict)
    completion, completion_err = _load_json(completion_path, dict)
    file_load_errors = [e for e in (matrix_err, manifest_err, preflight_err, completion_err) if e]

    assert isinstance(matrix, dict) and isinstance(manifest, dict) and isinstance(preflight, dict)
    assert isinstance(completion, dict)

    integrity_issues = integrity_check_bundle(
        all_events, out, matrix, preflight, manifest, completion, file_load_errors,
    )
    integrity_issues += phone_parse_errors + emu_parse_errors
    # §12 Round-6 audit P0-2: `integrity_ok` is finalized AFTER the
    # per-cell loop so late-added issues (e.g. `cell X has 0
    # sender_enqueue events`) are counted before product_outcome is
    # decided.

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
        # §12 Round-7 audit P1-1: cell-completion is measured by
        # `sender_send_attempt_started` count — the send-entry signal
        # from DefaultMessagingService. `sender_enqueue` is the real
        # queue/persistence boundary and stays a separate, weaker
        # signal (a deferred attempt has send_attempt_started but no
        # enqueue).
        send_attempts = sorted(
            [e for e in cell_evts if e.event == "sender_send_attempt_started"],
            key=lambda e: e.wall_utc_ms or 0,
        )
        enqueues = sorted(
            [e for e in cell_evts if e.event == "sender_enqueue"],
            key=lambda e: e.wall_utc_ms or 0,
        )
        # Envelopes count (reported in the cell report) reflects
        # attempts observed by the diagnostic — that is the measure
        # the operator can act on. Enqueues remain a distinct product
        # signal available per envelope below.
        cr.envelopes = len(send_attempts)

        if cr.envelopes != EXPECTED_ENVELOPES_PER_CELL:
            # §12 Round-6 audit P0-2 (Round-7 revised): an incomplete
            # cell (fewer than 5 send attempts in a non-blocked cell)
            # is an INTEGRITY violation. Under Round-7 the metric is
            # send_attempts, not enqueues.
            cr.issues.append(f"envelope count = {cr.envelopes} (expected {EXPECTED_ENVELOPES_PER_CELL})")
            cr.outcome = "Unresolved"
            integrity_issues.append(
                f"cell {cr.cell_id} has {cr.envelopes} sender_send_attempt_started events "
                f"(expected {EXPECTED_ENVELOPES_PER_CELL}) — incomplete matrix",
            )
            aggregate_red = True
            cells_report.append(cr)
            continue

        # §12 Round-7 audit P1-1: the 5-count metric is send_attempts,
        # not enqueues (enqueue can legitimately be < 5 under deferred
        # attempts). Enqueue CIDs still get their own uniqueness check
        # as defence in depth, but a low enqueue count is a product
        # signal, not an integrity violation.
        attempt_cids = [
            e.correlation_id for e in send_attempts
            if e.correlation_id and e.correlation_id != "-"
        ]
        if len(attempt_cids) != EXPECTED_ENVELOPES_PER_CELL:
            cr.issues.append(
                f"only {len(attempt_cids)} of {EXPECTED_ENVELOPES_PER_CELL} sender_send_attempt_started carry a correlation_id",
            )
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue
        if len(set(attempt_cids)) != len(attempt_cids):
            cr.issues.append("duplicate correlation_id across sender_send_attempt_started within cell")
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue
        # Enqueue uniqueness within cell (weaker check — only the
        # enqueues that fired need unique CIDs).
        enqueue_cids = [
            e.correlation_id for e in enqueues
            if e.correlation_id and e.correlation_id != "-"
        ]
        if len(set(enqueue_cids)) != len(enqueue_cids):
            cr.issues.append("duplicate correlation_id across sender_enqueue within cell")
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue
        cids = attempt_cids  # used by the loop below

        # Round-3 audit P0-1 (Round-7 rev): 5 dispatched events
        # matching the 5 send_attempts (not enqueues — see helper).
        pre_dispatch_issues = list(cr.issues)
        _check_cell_dispatched_binding(cr, send_attempts, cell_evts, sender_device, sender_emitter)
        if len(cr.issues) > len(pre_dispatch_issues):
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue

        sender_offset_ms = host_to_phone_ms if sender_device == "phone" else host_to_emu_ms

        env_outcomes: list[str] = []
        # §12 Round-7 audit P1-1: iterate over send_attempts (the
        # per-envelope entry signal), not enqueues (which may be
        # legitimately absent for deferred envelopes).
        for enq in send_attempts:
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

    integrity_ok = not integrity_issues
    if not integrity_ok:
        # §12 Round-6 audit P0-2: a bundle that fails integrity CANNOT
        # produce an authoritative product verdict — the operator
        # (and any downstream reader) MUST NOT read a `RED` or
        # `GREEN` product outcome from an incomplete run. The
        # sentinel `NOT_EVALUABLE` marks it explicitly and forbids
        # confusion with a real product signal.
        product_outcome = "NOT_EVALUABLE"
    elif aggregate_red:
        product_outcome = "RED"
    elif aggregate_pending:
        product_outcome = "PENDING"
    else:
        product_outcome = "GREEN"

    op_label_raw = preflight.get("operator_label")
    operator_label = op_label_raw if isinstance(op_label_raw, str) else None
    return VerifyReport(
        run_id=matrix.get("run_id"),
        integrity_ok=integrity_ok,
        integrity_issues=integrity_issues,
        cells=cells_report,
        product_outcome=product_outcome,
        operator_label=operator_label,
    )


def render_markdown(rep: VerifyReport) -> str:
    # §12 WSS-2 (Round-1 audit P1-2): the operator label MAY be
    # used in the title ONLY when the bundle actually verified
    # under the WSS-2 schema (integrity GREEN + operator_label
    # populated). Any integrity failure or a legacy Round-9
    # bundle falls back to "Yota-First" — otherwise an injected
    # `operator_label=TELE2` on a Yota-shaped bundle would render
    # a misleading "Direct WSS TELE2" title next to an integrity
    # RED report.
    if rep.integrity_ok and rep.operator_label:
        op_label = rep.operator_label
    else:
        op_label = "Yota-First"
    lines = [f"# Direct WSS {op_label} — verification report v4"]
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
    if rep.product_outcome == "NOT_EVALUABLE":
        lines.append("")
        lines.append("**`NOT_EVALUABLE` means integrity failed. The product cells above are NOT authoritative** —")
        lines.append("they are shown for diagnosis only. Do NOT interpret them as pass/fail signals.")
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

    # Exit codes:
    #   0 = integrity GREEN + product Delivered once on every non-blocked cell
    #   1 = integrity RED (product_outcome is NOT_EVALUABLE — do not read it)
    #   2 = integrity GREEN + product RED
    #   3 = integrity GREEN + product PENDING (rerun after 120 s)
    if not rep.integrity_ok:
        return 1
    if rep.product_outcome == "PENDING":
        return 3
    if rep.product_outcome == "RED":
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
