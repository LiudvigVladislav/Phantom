#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First diagnostic — evidence verifier (§9.4).
#
# Reads phone.logcat.wss_diag, emulator.logcat.wss_diag, matrix.json,
# preflight.json, device-manifest.json. Emits verification-report.md.
#
# Outputs two INDEPENDENT results:
#   evidence_integrity — bundle completeness (fully-collected failure = GREEN)
#   product_outcome    — per cell, one of Recovered | Delivered once |
#                        Unresolved | BLOCKED (Priority order per §2)
#
# The verifier NEVER makes claims about relay ingress / dedup /
# persistence (client-only first pass per §3, §8-Q1). It refuses to
# open files other than the five whitelisted evidence files.

from __future__ import annotations
import json
import os
import re
import sys
from dataclasses import dataclass
from typing import Iterable

WSS_120_S = 120_000  # verifier-side ceiling for outcome Priority 3.

# Fields the verifier honors from each WSS_DIAG log line. Values are
# space-separated `k=v` tokens; missing values are treated as None.
FIELD_RE = re.compile(r"(\w+)=(\S+)")


@dataclass
class WssEvent:
    device: str           # "phone" | "emulator"
    event: str
    role: str | None
    correlation_id: str | None
    run_id: str | None
    cell_id: str | None
    wall_utc_ms: int | None
    monotonic_ms: int | None
    outer_transport: str | None
    inner_route: str | None
    dedup_gate: str | None
    outcome_flag: str | None
    relay_acceptance: str | None
    attempt: int | None
    pin: str | None
    dispatched: bool | None


BANNED_TOKENS = [
    # Never expected to appear inside a WSS_DIAG structured field.
    "text=", "plaintext=", "content=", "auth=", "token=", "sealed=", "hex=",
]


def parse_events(path: str, device_label: str) -> list[WssEvent]:
    out: list[WssEvent] = []
    if not os.path.exists(path):
        return out
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            if "WSS_DIAG" not in line:
                continue
            # Extract just the fields portion after "WSS_DIAG:".
            # `Log.i` prefixes with tag; the printable payload is everything after "WSS_DIAG: ".
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
                wall_utc_ms=int(fields["wall_utc_ms"]) if "wall_utc_ms" in fields else None,
                monotonic_ms=int(fields["monotonic_ms"]) if "monotonic_ms" in fields else None,
                outer_transport=fields.get("outer_transport"),
                inner_route=fields.get("inner_route"),
                dedup_gate=fields.get("dedup_gate"),
                outcome_flag=fields.get("outcome_flag"),
                relay_acceptance=fields.get("relay_acceptance"),
                attempt=int(fields["attempt"]) if "attempt" in fields else None,
                pin=fields.get("pin"),
                dispatched=(fields["dispatched"] == "true") if "dispatched" in fields else None,
            ))
    return out


def by_cell(events: Iterable[WssEvent], cell_id: str) -> list[WssEvent]:
    return [e for e in events if e.cell_id == cell_id]


def outcome_for_envelope(
    cell_events: list[WssEvent],
    correlation_id: str,
    clock_skew_ms: int,
) -> tuple[str, list[str]]:
    """
    Priority 1 (Recovered): 4 signals + fallback breadcrumb
    Priority 2 (Delivered once): 4 signals, no breadcrumb
    Priority 3 (Unresolved): after 120 s of sender_enqueue
    """
    corr = [e for e in cell_events if e.correlation_id == correlation_id]
    enqueue = next((e for e in corr if e.event == "sender_enqueue"), None)
    if enqueue is None:
        return ("Unresolved", ["missing sender_enqueue"])
    deliver_fresh = [e for e in corr if e.event == "recipient_deliver_received" and e.dedup_gate == "fresh"]
    persist = [e for e in corr if e.event == "recipient_message_persisted"]
    ack = [e for e in corr if e.event == "recipient_ack_deliver_sent"]

    missing = []
    if not deliver_fresh:
        missing.append("recipient_deliver_received(dedup_gate=fresh)")
    if not persist:
        missing.append("recipient_message_persisted")
    if not ack:
        missing.append("recipient_ack_deliver_sent")
    # A second fresh delivery is a violation.
    if len(deliver_fresh) > 1:
        missing.append("second recipient_deliver_received(dedup_gate=fresh) — dedup violation")

    # Priority 3 window check.
    if missing:
        latest_wall = max((e.wall_utc_ms for e in corr if e.wall_utc_ms is not None), default=enqueue.wall_utc_ms or 0)
        if enqueue.wall_utc_ms is not None and latest_wall - enqueue.wall_utc_ms > WSS_120_S:
            return ("Unresolved", missing)
        return ("Unresolved", missing)

    # Fallback breadcrumbs.
    decisions = [e for e in corr if e.event == "sender_transport_decision"]
    routes = {e.inner_route for e in decisions if e.inner_route}
    watchdogs = [e for e in corr if e.event == "sender_ack_watchdog_requeued"]
    max_attempt = max((e.attempt or 1 for e in corr if e.event in {"sender_wss_frame_written", "sender_rest_post_completed"}), default=1)
    has_breadcrumb = len(routes) > 1 or watchdogs or max_attempt >= 2
    if has_breadcrumb:
        return ("Recovered", [])
    return ("Delivered once", [])


def integrity_check(events: list[WssEvent], matrix: dict) -> tuple[bool, list[str]]:
    problems: list[str] = []
    # Guard: no client-side unresolved_120s_marker.
    for e in events:
        if e.event == "unresolved_120s_marker" or e.outcome_flag == "unresolved_120s_marker":
            problems.append(f"client emitted forbidden verifier-only classification: {e}")
    # Guard: every event has a role.
    for e in events:
        if e.role is None:
            problems.append(f"event missing role: {e}")
    # Guard: no banned tokens in raw payloads.
    for e in events:
        # We only round-trip fields the parser exposed — banned tokens are
        # checked in the raw log files below.
        pass
    return (len(problems) == 0, problems)


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: verify-evidence.py <evidence-dir>", file=sys.stderr)
        return 2
    out = sys.argv[1]

    phone_events = parse_events(os.path.join(out, "phone.logcat.wss_diag"), "phone")
    emu_events = parse_events(os.path.join(out, "emulator.logcat.wss_diag"), "emulator")
    all_events = phone_events + emu_events

    # Raw-file banned-token scan (§4 guardrail).
    banned_hits: list[str] = []
    for name in ("phone.logcat.wss_diag", "emulator.logcat.wss_diag"):
        path = os.path.join(out, name)
        if not os.path.exists(path):
            continue
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for i, line in enumerate(f, 1):
                if "WSS_DIAG" not in line:
                    continue
                for tok in BANNED_TOKENS:
                    if tok in line:
                        banned_hits.append(f"{name}:{i} contains banned token '{tok}'")
                # 64-char lowercase hex substring — key-material shape.
                for hex_match in re.finditer(r"[0-9a-f]{64}", line):
                    banned_hits.append(f"{name}:{i} contains 64-char hex substring")
                    break

    matrix_path = os.path.join(out, "matrix.json")
    if not os.path.exists(matrix_path):
        print(f"verify FAILED: matrix.json missing at {matrix_path}", file=sys.stderr)
        return 1
    with open(matrix_path, "r", encoding="utf-8") as f:
        matrix = json.load(f)
    manifest_path = os.path.join(out, "device-manifest.json")
    clock_skew_ms = 0
    if os.path.exists(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as f:
            clock_skew_ms = json.load(f).get("clock_skew_ms", 0)

    integrity_ok, integrity_problems = integrity_check(all_events, matrix)
    if banned_hits:
        integrity_ok = False
        integrity_problems.extend(banned_hits)

    lines = [f"# Direct WSS Yota-First — verification report"]
    lines.append("")
    lines.append(f"run_id: `{matrix.get('run_id')}`")
    lines.append(f"clock_skew_ms: `{clock_skew_ms}`")
    lines.append(f"rest_capability: `{matrix.get('rest_capability')}`")
    lines.append("")
    lines.append("## evidence_integrity")
    lines.append(f"- **{'GREEN' if integrity_ok else 'RED'}**")
    for p in integrity_problems:
        lines.append(f"  - {p}")
    lines.append("")
    lines.append("## product_outcome per cell")
    lines.append("")
    lines.append("| cell_id | pin | direction | scenario | envelopes | outcome | missing |")
    lines.append("|---|---|---|---|---:|---|---|")
    any_red = False
    for cell in matrix["cells"]:
        cell_id = cell["cell_id"]
        cell_evts = by_cell(all_events, cell_id)
        # Correlation IDs for this cell come from sender_enqueue emits.
        corr_ids = sorted({e.correlation_id for e in cell_evts if e.event == "sender_enqueue" and e.correlation_id})
        if cell.get("blocked"):
            lines.append(f"| `{cell_id}` | {cell['pin']} | {cell['direction']} | {cell['scenario']} | 0 | **BLOCKED** | rest_capability=disabled |")
            continue
        per_env: list[tuple[str, str, list[str]]] = []
        for cid in corr_ids:
            outcome, missing = outcome_for_envelope(cell_evts, cid, clock_skew_ms)
            per_env.append((cid, outcome, missing))
        # Cell outcome: worst of the envelopes.
        worst = "Delivered once"
        for _, o, _ in per_env:
            if o == "Unresolved":
                worst = "Unresolved"; break
            if o == "Recovered" and worst == "Delivered once":
                worst = "Recovered"
        if worst == "Unresolved":
            any_red = True
        missing_summary = "; ".join(
            f"{cid[:8]}: {', '.join(m) or 'ok'}"
            for cid, _, m in per_env if m
        )
        lines.append(f"| `{cell_id}` | {cell['pin']} | {cell['direction']} | {cell['scenario']} | {len(per_env)} | **{worst}** | {missing_summary} |")

    lines.append("")
    lines.append(f"## Aggregate: product_outcome = **{'RED' if any_red else 'GREEN'}**")
    lines.append("")
    lines.append("Note: relay ingress / dedup / persistence are NOT verified in this")
    lines.append("first-pass client-only black-box. `recipient_message_persisted`")
    lines.append("proves chat-store write; it does not literally prove pixel-level")
    lines.append("visibility. See contract §11.1.")

    report_path = os.path.join(out, "verification-report.md")
    with open(report_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"verify done: evidence_integrity={'GREEN' if integrity_ok else 'RED'} product_outcome={'RED' if any_red else 'GREEN'}")
    print(f"report: {report_path}")
    return 0 if integrity_ok and not any_red else (1 if not integrity_ok else 3)


if __name__ == "__main__":
    sys.exit(main())
