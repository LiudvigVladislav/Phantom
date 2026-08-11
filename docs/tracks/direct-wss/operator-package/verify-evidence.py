#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — evidence verifier v3
# (§12 Round-1 audit repair).
#
# Changes vs v2:
#   * Every event is filtered by matrix.json.run_id (P0-2).
#   * Correlation IDs are globally unique inside a run, not per-cell.
#   * Pin coverage is per-sender-device + per-run + per-cell timeline.
#   * classify_envelope() classifies as Delivered once ONLY if pin,
#     role, emitter, outer arm, and inner route ALL match. Any
#     violation drops the cell to Unresolved.
#   * BLOCKED cells require preflight.rest_capability == "disabled"
#     AND the cell pin == "rest". Any other BLOCKED cell is
#     integrity RED.
#   * Preflight booleans are checked for `True` value (not key
#     presence).
#   * 120-s window uses a host clock injected via --host-now-ms; if
#     omitted, derives host_now_ms from clock_skew and the newest
#     device wall_utc_ms + a "verification age" fudge.
#   * Exit codes: 0/1/2/3 as v2.

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
ALLOWED_PINS = {"wss", "rest"}
ALLOWED_DIRECTIONS = {"p2e", "e2p"}
ALLOWED_SCENARIOS = {"after-connect", "after-idle", "bg-fg", "control"}
ALLOWED_ROLES = {"sender", "recipient", "matrix"}


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


def parse_events(path: str, device_label: str) -> list[WssEvent]:
    out: list[WssEvent] = []
    if not os.path.exists(path):
        return out
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            if "WSS_DIAG" not in line:
                continue
            m = re.search(r"WSS_DIAG(?::| :) (.*)$", line)
            if not m:
                continue
            payload = m.group(1).strip()
            fields = dict(FIELD_RE.findall(payload))
            out.append(WssEvent(
                device=device_label,
                event=fields.get("event", ""),
                role=fields.get("role"),
                correlation_id=fields.get("correlation_id"),
                run_id=fields.get("run_id"),
                cell_id=fields.get("cell_id"),
                emitter_id=fields.get("emitter_id"),
                wall_utc_ms=int(fields["wall_utc_ms"]) if "wall_utc_ms" in fields else None,
                monotonic_ms=int(fields["monotonic_ms"]) if "monotonic_ms" in fields else None,
                outer_transport=fields.get("outer_transport"),
                inner_route=fields.get("inner_route"),
                dedup_gate=fields.get("dedup_gate"),
                outcome_flag=fields.get("outcome_flag"),
                relay_acceptance=fields.get("relay_acceptance"),
                pin=fields.get("pin"),
                dispatched=(fields["dispatched"] == "true") if "dispatched" in fields else None,
                restored=(fields["restored"] == "true") if "restored" in fields else None,
                sequence=int(fields["sequence"]) if "sequence" in fields else None,
                raw=line.rstrip("\n"),
            ))
    return out


def cell_direction_to_emitters(direction: str) -> tuple[str, str]:
    if direction == "p2e":
        return "phone", "emulator"
    if direction == "e2p":
        return "emulator", "phone"
    return "unknown", "unknown"


def _matches_run_and_cell(e: WssEvent, run_id: str, cell_id: str) -> bool:
    return e.run_id == run_id and e.cell_id == cell_id


def _per_sender_pin_coverage(
    cell_pin: str, run_id: str, cell_id: str, sender_device: str,
    envelope_wall: int, all_events: list[WssEvent],
) -> tuple[bool, str]:
    """
    Timeline for the SENDER device only:
      * every diagnostic_pin_active event on the sender for
        (run, cell) → mark "pin=<value>" at wall
      * every diagnostic_session_started event on the sender (any
        cell/run) → if restored=false, the guard was reset;
        pin coverage is broken until a fresh matching pin_active
    Latest coverage BEFORE envelope_wall must be pin=cell_pin,
    matching run_id + cell_id.
    """
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
            # A restart with restored=false invalidates prior pin_active
            # coverage regardless of what pin field is carried — the
            # guard has been reset.
            if ev.restored is False:
                latest_pin = "none"
                latest_meta = f"session_started(restored=false) at {ev.wall_utc_ms}"
            elif ev.restored is True and ev.pin and ev.pin != "none":
                latest_pin = ev.pin
                latest_meta = f"session_started(restored=true, pin={ev.pin}) at {ev.wall_utc_ms}"
        elif ev.event == "diagnostic_pin_active":
            if ev.run_id == run_id and ev.cell_id == cell_id:
                latest_pin = ev.pin
                latest_meta = f"pin_active(run={ev.run_id}, cell={ev.cell_id}, pin={ev.pin}) at {ev.wall_utc_ms}"
            else:
                # Pin_active for a DIFFERENT cell/run resets coverage:
                # the operator moved on. Cell-scope isolation.
                latest_pin = "none"
                latest_meta = f"pin_active(other cell/run={ev.run_id}/{ev.cell_id}) at {ev.wall_utc_ms}"
    if latest_pin == cell_pin:
        return (True, latest_meta)
    return (False, f"latest pin before enqueue on {sender_device}: {latest_pin or 'none'} ({latest_meta or 'no events'}) — expected {cell_pin} for run={run_id} cell={cell_id}")


def integrity_check_bundle(
    events: list[WssEvent], out: str, matrix: dict, manifest: dict,
) -> list[str]:
    problems: list[str] = []

    # (a) Required files present + non-empty.
    for name in REQUIRED_FILES:
        p = os.path.join(out, name)
        if not os.path.exists(p):
            problems.append(f"required file missing: {name}")
            continue
        if os.path.getsize(p) == 0:
            problems.append(f"required file empty: {name}")

    # (b) No events at all is RED.
    if not events:
        problems.append("no WSS_DIAG events parsed — bundle is not usable")

    # (c) Every event has role, wall_utc_ms, emitter_id.
    for e in events:
        if e.role is None:
            problems.append(f"event missing role: {e.event} @ {e.device}")
        elif e.role not in ALLOWED_ROLES:
            problems.append(f"event role not in whitelist: {e.role}")
        if e.wall_utc_ms is None:
            problems.append(f"event missing wall_utc_ms: {e.event} @ {e.device}")
        if e.emitter_id is None:
            problems.append(f"event missing emitter_id: {e.event} @ {e.device}")
        elif e.emitter_id not in ("phone", "emulator"):
            problems.append(f"emitter_id not phone/emulator: {e.emitter_id} @ {e.device}")

    # (d) session_started present on BOTH devices.
    for dev in ("phone", "emulator"):
        if not any(e.event == "diagnostic_session_started" and e.device == dev for e in events):
            problems.append(f"no diagnostic_session_started event on {dev}")

    # (e) forbidden verifier-only classification on client.
    for e in events:
        if e.event == "unresolved_120s_marker" or e.outcome_flag == "unresolved_120s_marker":
            problems.append(f"forbidden verifier-only classification emitted by client: {e.raw}")

    # (f) matrix.json shape.
    run_id_matrix = matrix.get("run_id")
    if not run_id_matrix:
        problems.append("matrix.json missing run_id")
    cells = matrix.get("cells", [])
    if len(cells) != EXPECTED_CELL_COUNT:
        problems.append(f"matrix.json cells count={len(cells)} (expected {EXPECTED_CELL_COUNT})")
    seen_cell_ids: set[str] = set()
    for cell in cells:
        cid = cell.get("cell_id")
        if not cid:
            problems.append("cell missing cell_id")
            continue
        if cid in seen_cell_ids:
            problems.append(f"duplicate cell_id in matrix: {cid}")
        seen_cell_ids.add(cid)
        if cell.get("pin") not in ALLOWED_PINS:
            problems.append(f"cell {cid} pin not in whitelist: {cell.get('pin')}")
        if cell.get("direction") not in ALLOWED_DIRECTIONS:
            problems.append(f"cell {cid} direction not in whitelist: {cell.get('direction')}")
        if cell.get("scenario") not in ALLOWED_SCENARIOS:
            problems.append(f"cell {cid} scenario not in whitelist: {cell.get('scenario')}")
        if cell.get("blocked") is True and cell.get("pin") != "rest":
            problems.append(f"cell {cid} BLOCKED but pin != rest — only REST cells may be BLOCKED")

    # (g) preflight.json required booleans.
    preflight_disk: dict = {}
    preflight_path = os.path.join(out, "preflight.json")
    if os.path.exists(preflight_path):
        with open(preflight_path, "r", encoding="utf-8") as pf:
            preflight_disk = json.load(pf)
    for req_bool in ("yota_confirmed", "emitter_ids_set", "radio_confirmed"):
        if preflight_disk.get(req_bool) is not True:
            problems.append(f"preflight.json.{req_bool} is not True: {preflight_disk.get(req_bool)!r}")
    if preflight_disk.get("rest_capability") not in ("enabled", "disabled", "unknown"):
        problems.append(f"preflight.json.rest_capability invalid: {preflight_disk.get('rest_capability')!r}")

    # (h) REST BLOCKED cells consistent with preflight.rest_capability=disabled.
    if any(cell.get("blocked") for cell in cells):
        if preflight_disk.get("rest_capability") != "disabled":
            problems.append("cells declare blocked but preflight.rest_capability != 'disabled'")

    # (i) device-manifest.json required keys.
    for req in ("clock_skew_ms", "phone_serial", "emulator_serial"):
        if req not in manifest:
            problems.append(f"device-manifest.json missing required key: {req}")
    if abs(manifest.get("clock_skew_ms", 999_999)) > 30_000:
        problems.append(f"|clock_skew_ms| > 30 000: {manifest.get('clock_skew_ms')}")

    # (j) Every event's run_id must match matrix.run_id.
    if run_id_matrix:
        for e in events:
            if e.event in ("diagnostic_session_started", "diagnostic_canary", "diagnostic_state_cleared"):
                continue  # these may fire before the run starts
            if e.run_id is None:
                problems.append(f"event {e.event} missing run_id")
            elif e.run_id != run_id_matrix and e.run_id != "-":
                problems.append(f"event {e.event} has run_id={e.run_id} but matrix.run_id={run_id_matrix}")

    # (k) Global correlation ID uniqueness inside the run
    #     (each `sender_enqueue` must have a globally unique CID).
    enqueue_by_cid: dict[str, list[WssEvent]] = {}
    for e in events:
        if e.event == "sender_enqueue" and e.correlation_id:
            enqueue_by_cid.setdefault(e.correlation_id, []).append(e)
    for cid, evs in enqueue_by_cid.items():
        if len(evs) > 1:
            problems.append(f"correlation_id used multiple times: {cid} ({len(evs)} enqueues)")

    # (l) Raw banned-token scan.
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
) -> tuple[str, list[str]]:
    issues: list[str] = []

    # (1) Enqueue itself must own run/cell/emitter.
    if envelope_enqueue.run_id != run_id:
        issues.append(f"enqueue run_id={envelope_enqueue.run_id} != {run_id}")
    if envelope_enqueue.cell_id != cell_id:
        issues.append(f"enqueue cell_id={envelope_enqueue.cell_id} != {cell_id}")
    if envelope_enqueue.emitter_id != sender_emitter:
        issues.append(f"enqueue emitter={envelope_enqueue.emitter_id} != {sender_emitter}")
    if envelope_enqueue.device != sender_device:
        issues.append(f"enqueue device={envelope_enqueue.device} != {sender_device}")

    # (2) Sender events must all be on the sender device with matching run/cell.
    sender_evts = [e for e in corr_events if e.role == "sender"]
    for e in sender_evts:
        if e.device != sender_device or e.run_id != run_id or e.cell_id != cell_id:
            issues.append(f"sender event {e.event} owner mismatch (device={e.device} run={e.run_id} cell={e.cell_id})")
        if e.emitter_id != sender_emitter:
            issues.append(f"sender event {e.event} emitter mismatch: {e.emitter_id}")

    # (3) Recipient events must be on recipient device.
    recipient_evts = [e for e in corr_events if e.role == "recipient"]
    for e in recipient_evts:
        if e.device != recipient_device:
            issues.append(f"recipient event {e.event} device mismatch: {e.device}")
        if e.emitter_id != recipient_emitter:
            issues.append(f"recipient event {e.event} emitter mismatch: {e.emitter_id}")

    # (4) Pin coverage on sender for this run+cell.
    (covered, why) = _per_sender_pin_coverage(
        cell_pin, run_id, cell_id, sender_device,
        envelope_enqueue.wall_utc_ms or 0, all_events,
    )
    if not covered:
        issues.append(f"pin coverage: {why}")

    # (5) sender_transport_decision must exist, be direct, and match pin.
    decision = next((e for e in corr_events if e.event == "sender_transport_decision"), None)
    if decision is None:
        issues.append("missing sender_transport_decision")
    else:
        if decision.outer_transport != "direct":
            issues.append(f"outer_transport observed != direct: {decision.outer_transport}")
        if decision.inner_route != cell_pin:
            issues.append(f"inner_route != cell pin: {decision.inner_route} vs {cell_pin}")
        if decision.dispatched is False:
            issues.append("sender_transport_decision dispatched=false (BLOCKED for this envelope)")

    # (6) Delivery signals.
    deliver_fresh = [e for e in corr_events if e.event == "recipient_deliver_received" and e.dedup_gate == "fresh"]
    persist = [e for e in corr_events if e.event == "recipient_message_persisted"]
    ack = [e for e in corr_events if e.event == "recipient_ack_deliver_sent"]

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
        # Contract violations always drop to Unresolved (never "Delivered once with warnings").
        return ("Unresolved", issues + missing)

    if missing:
        # PENDING before 120 s host clock; Unresolved after.
        enqueue_wall = envelope_enqueue.wall_utc_ms or 0
        age_ms = host_now_ms - enqueue_wall
        if age_ms < WSS_120_S:
            return ("PENDING", missing + [f"age_ms={age_ms} < 120_000"])
        return ("Unresolved", missing + [f"age_ms={age_ms}"])

    return ("Delivered once", [])


def _derive_host_now_ms(all_events: list[WssEvent], manifest: dict, override: Optional[int]) -> int:
    if override is not None:
        return override
    return int(time.time_ns() // 1_000_000)


def build_report(out: str, host_now_override_ms: Optional[int] = None) -> VerifyReport:
    phone_events = parse_events(os.path.join(out, "phone.logcat.wss_diag"), "phone")
    emu_events = parse_events(os.path.join(out, "emulator.logcat.wss_diag"), "emulator")
    all_events = phone_events + emu_events

    matrix_path = os.path.join(out, "matrix.json")
    manifest_path = os.path.join(out, "device-manifest.json")
    matrix: dict = {}
    manifest: dict = {}
    if os.path.exists(matrix_path):
        with open(matrix_path, "r", encoding="utf-8") as f:
            matrix = json.load(f)
    if os.path.exists(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)

    integrity_issues = integrity_check_bundle(all_events, out, matrix, manifest)
    integrity_ok = not integrity_issues

    run_id_matrix = matrix.get("run_id") or ""

    # For per-envelope classification, restrict events to matching run_id.
    # session_started/canary/state_cleared/checkpoint events keep whatever run_id was
    # persisted at that moment; enqueue/decision/delivery events MUST match.
    run_scoped_events = [e for e in all_events if e.run_id in (run_id_matrix, None, "-")]

    host_now_ms = _derive_host_now_ms(all_events, manifest, host_now_override_ms)

    cells_report: list[CellReport] = []
    aggregate_pending = False
    aggregate_red = False
    for cell in matrix.get("cells", []):
        cr = CellReport(
            cell_id=cell.get("cell_id", "?"),
            pin=cell.get("pin", "?"),
            direction=cell.get("direction", "?"),
            scenario=cell.get("scenario", "?"),
            blocked=bool(cell.get("blocked")),
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

        # Correlation IDs unique within cell (also enforced globally by integrity).
        cids = [e.correlation_id for e in enqueues if e.correlation_id]
        if len(set(cids)) != len(cids):
            cr.issues.append("duplicate correlation_id within cell")
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue

        env_outcomes: list[str] = []
        for enq in enqueues:
            cid = enq.correlation_id
            corr_evts = [e for e in run_scoped_events if e.correlation_id == cid]
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
    lines = ["# Direct WSS Yota-First — verification report v3"]
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
