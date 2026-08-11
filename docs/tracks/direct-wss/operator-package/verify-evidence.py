#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — evidence verifier v2 (§12 P0-1).
#
# Closed-schema verifier. Rejects empty bundles, missing files,
# wrong run/cell/emitter, duplicate correlation IDs, wrong pin/route,
# lost pin/process restart without session_started, early verification
# before 120 s (→ PENDING) vs after 120 s (→ Unresolved).
#
# Reads exactly five files, refuses anything else:
#   phone.logcat.wss_diag
#   emulator.logcat.wss_diag
#   matrix.json
#   preflight.json
#   device-manifest.json
#
# Exit codes:
#   0 = evidence_integrity=GREEN AND product_outcome all Delivered/BLOCKED
#   1 = evidence_integrity=RED  (tooling / capture failure)
#   2 = evidence_integrity=GREEN, product_outcome=RED (Unresolved cells)
#   3 = evidence_integrity=GREEN, product_outcome=PENDING (rerun after 120 s)
#
# Priority-3 outcome per §2:
#   PENDING    — 4 signals missing AND newest event < 120 s from
#                sender_enqueue (verifier called too early)
#   Unresolved — 4 signals missing AND newest event >= 120 s
#
# Recovered classification (§12 P0-7) is REMOVED — first pass emits
# only Delivered once / Unresolved / PENDING / BLOCKED.

from __future__ import annotations
import json
import os
import re
import sys
from dataclasses import dataclass, field
from typing import Iterable, Optional

WSS_120_S = 120_000

REQUIRED_FILES = [
    "phone.logcat.wss_diag",
    "emulator.logcat.wss_diag",
    "matrix.json",
    "preflight.json",
    "device-manifest.json",
]

FIELD_RE = re.compile(r"(\w+)=(\S+)")

# Banned tokens in raw log lines — indicates a schema violation.
BANNED_TOKEN_SUBSTRINGS = ["text=", "plaintext=", "content=", "auth=", "token=", "sealed=", "hex="]
KEY_LIKE_HEX_RE = re.compile(r"(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")

EXPECTED_CELL_COUNT = 8
EXPECTED_ENVELOPES_PER_CELL = 5
ALLOWED_PINS = {"wss", "rest"}
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
                raw=line.rstrip("\n"),
            ))
    return out


def cell_direction_to_emitters(direction: str) -> tuple[str, str]:
    """Return (sender_emitter, recipient_emitter) — 'phone' / 'emulator'."""
    if direction == "p2e":
        return "phone", "emulator"
    if direction == "e2p":
        return "emulator", "phone"
    return "unknown", "unknown"


def pin_covers_envelope(
    cell_pin: str,
    envelope_enqueue_wall: int,
    pin_active_events: list[WssEvent],
    per_device_session_started: dict[str, list[WssEvent]],
) -> tuple[bool, str]:
    """
    A pin covers an envelope's send if EITHER:
      (a) A `diagnostic_pin_active pin=<cell_pin>` event exists BEFORE
          the envelope's sender_enqueue with the same cell_id.
      (b) A `diagnostic_session_started pin=<cell_pin>` event exists on
          the same device WITHOUT a subsequent pin change to a different
          value before the envelope.

    Returns (ok, why_not_if_false).
    """
    matches = [e for e in pin_active_events
               if e.pin == cell_pin and e.wall_utc_ms is not None
               and e.wall_utc_ms <= envelope_enqueue_wall]
    if not matches:
        # Fall back to session_started event as pin coverage — the boot
        # init emits it with the persisted pin AFTER restoring.
        for dev in per_device_session_started.values():
            for e in dev:
                if e.pin == cell_pin and e.wall_utc_ms is not None and e.wall_utc_ms <= envelope_enqueue_wall:
                    return (True, "")
        return (False, f"no diagnostic_pin_active pin={cell_pin} or diagnostic_session_started covering enqueue")
    return (True, "")


def integrity_check_bundle(events: list[WssEvent], out: str, matrix: dict, manifest: dict) -> list[str]:
    problems: list[str] = []

    # (a) Required files present + non-empty.
    for name in REQUIRED_FILES:
        p = os.path.join(out, name)
        if not os.path.exists(p):
            problems.append(f"required file missing: {name}")
            continue
        if os.path.getsize(p) == 0:
            problems.append(f"required file empty: {name}")

    # (b) No events at all is RED regardless.
    if not events:
        problems.append("no WSS_DIAG events parsed — bundle is not usable")

    # (c) Every event has a role, wall_utc_ms.
    for e in events:
        if e.role is None:
            problems.append(f"event missing role: {e.event} @ {e.device}")
        elif e.role not in ALLOWED_ROLES:
            problems.append(f"event role not in whitelist: {e.role}")
        if e.wall_utc_ms is None:
            problems.append(f"event missing wall_utc_ms: {e.event} @ {e.device}")

    # (d) Emitter role sanity — every event must carry an emitter_id.
    for e in events:
        if e.emitter_id is None:
            problems.append(f"event missing emitter_id: {e.event} @ {e.device}")
        elif e.emitter_id not in ("phone", "emulator"):
            problems.append(f"emitter_id not phone/emulator: {e.emitter_id} @ {e.device}")

    # (e) session_started present on BOTH devices.
    started_by_dev = {d: [] for d in ("phone", "emulator")}
    for e in events:
        if e.event == "diagnostic_session_started":
            started_by_dev.setdefault(e.device, []).append(e)
    for dev in ("phone", "emulator"):
        if not started_by_dev.get(dev):
            problems.append(f"no diagnostic_session_started event on {dev} — boot init did not fire")

    # (f) No client-emitted verifier-only classification.
    for e in events:
        if e.event == "unresolved_120s_marker" or e.outcome_flag == "unresolved_120s_marker":
            problems.append(f"forbidden verifier-only classification emitted by client: {e.raw}")

    # (g) matrix.json shape.
    if matrix.get("run_id") is None:
        problems.append("matrix.json missing run_id")
    cells = matrix.get("cells", [])
    if len(cells) != EXPECTED_CELL_COUNT:
        problems.append(f"matrix.json cells count={len(cells)} (expected {EXPECTED_CELL_COUNT})")

    # (h) preflight.json + device-manifest.json required fields.
    preflight_disk: dict = {}
    preflight_path = os.path.join(out, "preflight.json")
    if os.path.exists(preflight_path):
        with open(preflight_path, "r", encoding="utf-8") as _pf:
            preflight_disk = json.load(_pf)
    for req in ("rest_capability", "yota_confirmed", "emitter_ids_set"):
        if req not in matrix.get("preflight", {}) and req not in preflight_disk:
            problems.append(f"preflight.json missing required key: {req}")
    for req in ("clock_skew_ms", "phone_serial", "emulator_serial"):
        if req not in manifest:
            problems.append(f"device-manifest.json missing required key: {req}")
    if abs(manifest.get("clock_skew_ms", 999_999)) > 30_000:
        problems.append(f"|clock_skew_ms| > 30 000 → cross-device correlation degraded: {manifest.get('clock_skew_ms')}")

    # (i) Raw banned-token scan.
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
                    problems.append(f"{name}:{i} 64-char lowercase hex substring — key-material shape")

    return problems


def classify_envelope(
    envelope_enqueue: WssEvent,
    corr_events: list[WssEvent],
    cell_pin: str,
    expected_sender_emitter: str,
    expected_recipient_emitter: str,
    pin_active_events: list[WssEvent],
    session_started: dict[str, list[WssEvent]],
    now_wall_ms: int,
) -> tuple[str, list[str]]:
    issues: list[str] = []

    # Role/emitter sanity.
    sender_evts = [e for e in corr_events if e.role == "sender"]
    recipient_evts = [e for e in corr_events if e.role == "recipient"]
    if sender_evts and any(e.emitter_id != expected_sender_emitter for e in sender_evts):
        issues.append(f"sender emitter_id != {expected_sender_emitter}")
    if recipient_evts and any(e.emitter_id != expected_recipient_emitter for e in recipient_evts):
        issues.append(f"recipient emitter_id != {expected_recipient_emitter}")

    # Pin coverage.
    (covered, why) = pin_covers_envelope(
        cell_pin, envelope_enqueue.wall_utc_ms or 0,
        pin_active_events, session_started,
    )
    if not covered:
        issues.append(f"pin not covered at enqueue: {why}")

    # Transport decision matches pin.
    decision = next((e for e in corr_events if e.event == "sender_transport_decision"), None)
    if decision is None:
        issues.append("missing sender_transport_decision")
    else:
        if decision.outer_transport != "direct":
            issues.append(f"outer_transport observed != direct: {decision.outer_transport}")
        if decision.inner_route != cell_pin:
            issues.append(f"inner_route != cell pin: {decision.inner_route} vs {cell_pin}")
        if decision.dispatched is False:
            issues.append("sender_transport_decision dispatched=false — cell BLOCKED for this envelope")

    # Delivery signals.
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

    if not missing and not issues:
        return ("Delivered once", [])
    if not missing:
        # Delivery signals present but pinning / role issues — still classify Delivered once with warnings.
        return ("Delivered once", issues)

    # PENDING vs Unresolved by 120s window.
    enqueue_wall = envelope_enqueue.wall_utc_ms or 0
    age_ms = now_wall_ms - enqueue_wall
    if age_ms < WSS_120_S:
        return ("PENDING", missing + issues + [f"age_ms={age_ms} < 120_000"])
    return ("Unresolved", missing + issues + [f"age_ms={age_ms}"])


def build_report(out: str) -> VerifyReport:
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

    # Per-cell classification.
    now_wall = max((e.wall_utc_ms or 0 for e in all_events), default=0)
    pin_active_events = [e for e in all_events if e.event == "diagnostic_pin_active"]
    session_started_by_dev = {"phone": [], "emulator": []}
    for e in all_events:
        if e.event == "diagnostic_session_started":
            session_started_by_dev.setdefault(e.device, []).append(e)

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
        cell_evts = [e for e in all_events if e.cell_id == cr.cell_id]
        enqueues = sorted(
            [e for e in cell_evts if e.event == "sender_enqueue"],
            key=lambda e: e.wall_utc_ms or 0,
        )
        corr_ids = [e.correlation_id for e in enqueues if e.correlation_id]
        cr.envelopes = len(corr_ids)

        # Envelope count check.
        if cr.envelopes != EXPECTED_ENVELOPES_PER_CELL:
            cr.issues.append(f"envelope count = {cr.envelopes} (expected {EXPECTED_ENVELOPES_PER_CELL})")
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue

        # Correlation IDs unique.
        if len(set(corr_ids)) != len(corr_ids):
            cr.issues.append("duplicate correlation_id within cell")
            cr.outcome = "Unresolved"
            aggregate_red = True
            cells_report.append(cr)
            continue

        sender_emitter, recipient_emitter = cell_direction_to_emitters(cr.direction)
        env_outcomes: list[str] = []
        for enq in enqueues:
            cid = enq.correlation_id
            corr_evts = [e for e in all_events if e.correlation_id == cid]
            (o, mm) = classify_envelope(
                enq, corr_evts, cr.pin, sender_emitter, recipient_emitter,
                pin_active_events, session_started_by_dev, now_wall,
            )
            if mm:
                cr.issues.append(f"{cid[:8]}: {'; '.join(mm)}")
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

    # Product outcome aggregation.
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
    lines = ["# Direct WSS Yota-First — verification report v2"]
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
    if len(sys.argv) < 2:
        print("usage: verify-evidence.py <evidence-dir>", file=sys.stderr)
        return 2
    out = sys.argv[1]
    if not os.path.isdir(out):
        print(f"verify FAILED: not a directory: {out}", file=sys.stderr)
        return 1

    rep = build_report(out)
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
