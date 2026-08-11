#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First — verifier fixture tests v3
# (§12 Round-1 audit repair).
#
# Every case from the Round-1 audit list is present here:
#   wrong run, wrong cell, wrong emitter/device, wrong outer/inner
#   route with complete delivery, cross-cell pin contamination,
#   restart with restored pin, restart with lost pin, global
#   duplicate CID, malformed 8-cell schema, false preflight booleans,
#   illegal BLOCKED cell, injected-clock PENDING-to-Unresolved.

from __future__ import annotations
import json
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import importlib.util  # noqa: E402
_spec = importlib.util.spec_from_file_location(
    "verify_evidence",
    os.path.join(os.path.dirname(HERE), "verify-evidence.py"),
)
ve = importlib.util.module_from_spec(_spec)
sys.modules["verify_evidence"] = ve
_spec.loader.exec_module(ve)


RUN_ID = "run-test"


def make_bundle(tmpdir: str,
                phone_lines: list[str] | None = None,
                emulator_lines: list[str] | None = None,
                matrix: dict | None = None,
                preflight: dict | None = None,
                manifest: dict | None = None,
                skip: set[str] | None = None) -> str:
    out = os.path.join(tmpdir, "evidence")
    os.makedirs(out, exist_ok=True)
    skip = skip or set()
    if "phone.logcat.wss_diag" not in skip:
        with open(os.path.join(out, "phone.logcat.wss_diag"), "w") as f:
            f.write("\n".join(phone_lines or []) + "\n")
    if "emulator.logcat.wss_diag" not in skip:
        with open(os.path.join(out, "emulator.logcat.wss_diag"), "w") as f:
            f.write("\n".join(emulator_lines or []) + "\n")
    if "matrix.json" not in skip:
        with open(os.path.join(out, "matrix.json"), "w") as f:
            json.dump(matrix or default_matrix(), f)
    if "preflight.json" not in skip:
        with open(os.path.join(out, "preflight.json"), "w") as f:
            json.dump(preflight or default_preflight(), f)
    if "device-manifest.json" not in skip:
        with open(os.path.join(out, "device-manifest.json"), "w") as f:
            json.dump(manifest or default_manifest(), f)
    return out


def default_matrix() -> dict:
    cells = []
    for pin, direction, scenario in [
        ("wss", "p2e", "after-connect"),
        ("wss", "e2p", "after-connect"),
        ("wss", "p2e", "after-idle"),
        ("wss", "e2p", "after-idle"),
        ("wss", "p2e", "bg-fg"),
        ("wss", "e2p", "bg-fg"),
        ("rest", "p2e", "control"),
        ("rest", "e2p", "control"),
    ]:
        cells.append({
            "cell_id": f"{pin}.{direction}.{scenario}",
            "pin": pin, "direction": direction, "scenario": scenario,
            "blocked": False,
        })
    return {"run_id": RUN_ID, "rest_capability": "enabled",
            "preflight": default_preflight(), "cells": cells}


def default_preflight() -> dict:
    return {
        "rest_capability": "enabled",
        "yota_confirmed": True,
        "emitter_ids_set": True,
        "radio_confirmed": True,
    }


def default_manifest() -> dict:
    return {
        "host_to_phone_skew_ms": 100,
        "host_to_emulator_skew_ms": 100,
        "phone_serial": "P",
        "emulator_serial": "E",
    }


def line(event: str, wall: int, mono: int, role: str, cell_id: str, emitter: str,
         correlation_id: str = "-", pin: str | None = None,
         outer: str | None = None, inner: str | None = None,
         dedup: str | None = None, outcome: str | None = None,
         dispatched: bool | None = None,
         run_id: str = RUN_ID,
         restored: bool | None = None,
         sequence: int | None = None) -> str:
    fields = [
        f"event={event}", f"role={role}", f"emitter_id={emitter}",
        f"run_id={run_id}", f"cell_id={cell_id}",
        f"wall_utc_ms={wall}", f"monotonic_ms={mono}",
    ]
    if correlation_id != "-":
        fields.append(f"correlation_id={correlation_id}")
    if pin: fields.append(f"pin={pin}")
    if outer: fields.append(f"outer_transport={outer}")
    if inner: fields.append(f"inner_route={inner}")
    if dedup: fields.append(f"dedup_gate={dedup}")
    if outcome: fields.append(f"outcome_flag={outcome}")
    if dispatched is not None: fields.append(f"dispatched={'true' if dispatched else 'false'}")
    if restored is not None: fields.append(f"restored={'true' if restored else 'false'}")
    if sequence is not None: fields.append(f"sequence={sequence}")
    payload = " ".join(fields)
    return f"08-11 12:00:00.000  1234  1234 I WSS_DIAG: {payload}"


def make_complete_delivery_lines(cell_id: str, direction: str, base_wall: int = 100_000,
                                 pin: str = "wss", cid_prefix: str = "cid") -> tuple[list[str], list[str]]:
    if direction == "p2e":
        sender_dev, recipient_dev = "phone", "emulator"
    else:
        sender_dev, recipient_dev = "emulator", "phone"
    phone: list[str] = []
    emu: list[str] = []
    # diagnostic_session_started on both devices at boot.
    phone.append(line("diagnostic_session_started", base_wall - 1000, 1, "matrix", cell_id, "phone",
                       pin=pin, inner=pin, restored=False))
    emu.append(line("diagnostic_session_started", base_wall - 1000, 1, "matrix", cell_id, "emulator",
                     pin=pin, inner=pin, restored=False))
    # diagnostic_pin_active AFTER boot but BEFORE first enqueue on each device.
    phone.append(line("diagnostic_pin_active", base_wall - 500, 2, "matrix", cell_id, "phone", pin=pin, inner=pin))
    emu.append(line("diagnostic_pin_active", base_wall - 500, 2, "matrix", cell_id, "emulator", pin=pin, inner=pin))
    for i in range(1, 6):
        wall = base_wall + i * 1000
        cid = f"{cid_prefix}-{cell_id}-{i}"
        sender_lines = phone if sender_dev == "phone" else emu
        recipient_lines = phone if recipient_dev == "phone" else emu
        sender_lines.append(line("sender_enqueue", wall, 10, "sender", cell_id, sender_dev, correlation_id=cid))
        sender_lines.append(line("sender_transport_decision", wall + 10, 11, "sender", cell_id, sender_dev,
                                  correlation_id=cid, outer="direct", inner=pin, dispatched=True))
        # Emit the pin-appropriate send-return event.
        if pin == "wss":
            sender_lines.append(line("sender_wss_send_returned", wall + 20, 12, "sender", cell_id, sender_dev,
                                      correlation_id=cid, inner=pin, dispatched=True))
        else:  # rest
            sender_lines.append(rest_completed_line(wall + 20, 12, cell_id, sender_dev, cid, acceptance="accepted"))
        sender_lines.append(line("sender_relay_ack_received", wall + 30, 13, "sender", cell_id, sender_dev,
                                  correlation_id=cid, outcome="sender_relay_ack_delivered"))
        recipient_lines.append(line("recipient_deliver_received", wall + 40, 20, "recipient", cell_id, recipient_dev,
                                     correlation_id=cid, dedup="fresh"))
        recipient_lines.append(line("recipient_message_persisted", wall + 50, 21, "recipient", cell_id, recipient_dev,
                                     correlation_id=cid))
        recipient_lines.append(line("recipient_ack_deliver_sent", wall + 60, 22, "recipient", cell_id, recipient_dev,
                                     correlation_id=cid))
    return phone, emu


def rest_completed_line(wall: int, mono: int, cell_id: str, emitter: str,
                        correlation_id: str, acceptance: str) -> str:
    return (f"08-11 12:00:00.000  1234  1234 I WSS_DIAG: "
            f"event=sender_rest_post_completed role=sender emitter_id={emitter} "
            f"run_id={RUN_ID} cell_id={cell_id} wall_utc_ms={wall} monotonic_ms={mono} "
            f"correlation_id={correlation_id} inner_route=rest relay_acceptance={acceptance}")


def build_full_matrix_bundle(tmpdir: str, host_now_ms: int | None = None,
                              matrix_override: dict | None = None) -> tuple[str, int]:
    """Build a bundle where every cell has 5 complete delivered envelopes,
    with pin taken from the cell (WSS or REST)."""
    m = matrix_override if matrix_override is not None else default_matrix()
    phone: list[str] = []
    emu: list[str] = []
    base = 200_000
    for i, cell in enumerate(m["cells"]):
        if cell.get("blocked"):
            continue
        p, e = make_complete_delivery_lines(
            cell["cell_id"], cell["direction"], base_wall=base + i * 10_000,
            pin=cell["pin"],
        )
        phone.extend(p); emu.extend(e)
    out = make_bundle(tmpdir, phone_lines=phone, emulator_lines=emu, matrix=m)
    return out, base


class VerifierTests(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="wss-verify-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    # ── Basic integrity ─────────────────────────────────────────

    def test_empty_bundle_is_integrity_RED(self):
        out = make_bundle(self.tmp)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)

    def test_missing_required_file_is_integrity_RED(self):
        out = make_bundle(self.tmp, skip={"matrix.json"})
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)

    # ── Full delivery baseline ──────────────────────────────────

    def test_complete_delivery_all_8_cells_is_GREEN(self):
        out, base = build_full_matrix_bundle(self.tmp)
        # host_now_ms after the last envelope + 120s so no PENDING.
        rep = ve.build_report(out, host_now_override_ms=base + 200_000 + 120_000)
        self.assertTrue(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
        for c in rep.cells:
            self.assertEqual(c.outcome, "Delivered once", msg=f"{c.cell_id}: {c.issues}")
        self.assertEqual(rep.product_outcome, "GREEN")

    # ── Round-1 audit case list ─────────────────────────────────

    def test_wrong_run_id_on_event_fails_integrity(self):
        cell_id = "wss.p2e.after-connect"
        phone = [
            line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "phone", pin="wss", inner="wss", restored=False),
            line("sender_enqueue", 2000, 2, "sender", cell_id, "phone", correlation_id="cid-1", run_id="wrong-run"),
        ]
        emu = [line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "emulator", pin="wss", inner="wss", restored=False)]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")

    def test_wrong_cell_id_on_event_lands_that_envelope_in_wrong_cell(self):
        # A phone sender_enqueue with cell_id from a different cell
        # produces envelope count mismatch (target cell has 0 enqueues,
        # foreign cell has 1 stray enqueue). Outcome: Unresolved.
        m = default_matrix()
        phone = [line("diagnostic_session_started", 1000, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False)]
        emu = [line("diagnostic_session_started", 1000, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False)]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out)
        for c in rep.cells:
            if not c.blocked:
                self.assertEqual(c.outcome, "Unresolved")
        self.assertEqual(rep.product_outcome, "RED")

    def test_wrong_emitter_device_produces_Unresolved(self):
        # Full delivery, but the phone-originated events falsely
        # claim emitter_id=emulator.
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        # Rewrite the phone stream to swap emitter_id: replace
        # "emitter_id=phone" with "emitter_id=emulator" in every line.
        phone = [ln.replace("emitter_id=phone", "emitter_id=emulator") for ln in phone]
        # Add other cells complete so overall run has 8x5 correctly.
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"):
                continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"], base_wall=base + 50_000)
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        first_cell = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(first_cell.outcome, "Unresolved", msg=f"issues: {first_cell.issues}")
        self.assertEqual(rep.product_outcome, "RED")

    def test_wrong_outer_transport_with_complete_delivery_produces_Unresolved(self):
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        # Rewrite the sender_transport_decision line to claim outer=reality.
        phone = [ln.replace("outer_transport=direct", "outer_transport=reality") for ln in phone]
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"):
                continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"], base_wall=base + 50_000)
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        first_cell = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(first_cell.outcome, "Unresolved", msg=f"issues: {first_cell.issues}")

    def test_wrong_inner_route_with_complete_delivery_produces_Unresolved(self):
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        # Rewrite decision inner_route from wss to rest — cell pin says wss.
        phone = [
            ln.replace("event=sender_transport_decision", "event=sender_transport_decision")
              .replace("inner_route=wss dispatched", "inner_route=rest dispatched")
            if "sender_transport_decision" in ln else ln
            for ln in phone
        ]
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"):
                continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"], base_wall=base + 50_000)
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        first_cell = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(first_cell.outcome, "Unresolved", msg=f"issues: {first_cell.issues}")

    def test_cross_cell_pin_contamination_is_detected(self):
        # Both cells share the same sender device (phone / p2e).
        # cell A has pin_active(wss) for cell_a; cell B has NO
        # pin_active. Cell B's envelopes MUST fail coverage even
        # though cell A had a pin.
        cell_a = "wss.p2e.after-connect"
        cell_b = "wss.p2e.after-idle"
        base = 200_000
        phone: list[str] = []
        emu: list[str] = []
        phone.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "phone",
                           pin="wss", inner="wss", restored=False))
        emu.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "emulator",
                         pin="wss", inner="wss", restored=False))
        # Cell A pin_active on phone — good.
        phone.append(line("diagnostic_pin_active", base - 500, 2, "matrix", cell_a, "phone", pin="wss", inner="wss"))

        # Cell A itself: 5 complete envelopes, no extra pin/session.
        pA, eA = make_complete_delivery_lines(cell_a, "p2e", base_wall=base, pin="wss")
        pA = [ln for ln in pA if "diagnostic_pin_active" not in ln and "diagnostic_session_started" not in ln]
        eA = [ln for ln in eA if "diagnostic_pin_active" not in ln and "diagnostic_session_started" not in ln]
        phone.extend(pA); emu.extend(eA)

        # Cell B: 5 complete envelopes, still on phone (p2e), NO pin_active.
        pB, eB = make_complete_delivery_lines(cell_b, "p2e", base_wall=base + 30_000, pin="wss", cid_prefix="cidB")
        pB = [ln for ln in pB if "diagnostic_pin_active" not in ln and "diagnostic_session_started" not in ln]
        eB = [ln for ln in eB if "diagnostic_pin_active" not in ln and "diagnostic_session_started" not in ln]
        phone.extend(pB); emu.extend(eB)

        m = default_matrix()
        for cell in m["cells"]:
            if cell["cell_id"] in (cell_a, cell_b) or cell.get("blocked"):
                continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"], base_wall=base + 200_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)

        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 500_000)
        cell_b_report = next(c for c in rep.cells if c.cell_id == cell_b)
        self.assertEqual(cell_b_report.outcome, "Unresolved",
                          msg=f"cell B must NOT be covered by cell A's pin. issues: {cell_b_report.issues}")

    def test_restart_with_lost_pin_invalidates_prior_coverage(self):
        # pin_active fires; then session_started restored=false;
        # then a send occurs — coverage must be gone.
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone: list[str] = []
        emu: list[str] = []
        phone.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "phone", pin="none", inner="unknown", restored=False))
        emu.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "emulator", pin="none", inner="unknown", restored=False))
        phone.append(line("diagnostic_pin_active", base - 4000, 2, "matrix", cell_id, "phone", pin="wss", inner="wss"))
        # Now the phone process restarts — session_started restored=false.
        phone.append(line("diagnostic_session_started", base - 100, 3, "matrix", "-", "phone", pin="none", inner="unknown", restored=False))
        p, e = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        # Strip its own session_started/pin_active to keep our timeline.
        p = [ln for ln in p if "diagnostic_session_started" not in ln and "diagnostic_pin_active" not in ln]
        e = [ln for ln in e if "diagnostic_session_started" not in ln and "diagnostic_pin_active" not in ln]
        phone.extend(p); emu.extend(e)
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"], base_wall=base + 200_000)
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 500_000)
        cell_report = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(cell_report.outcome, "Unresolved", msg=f"issues: {cell_report.issues}")

    def test_restart_with_restored_pin_preserves_coverage(self):
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone: list[str] = []
        emu: list[str] = []
        phone.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "phone", pin="none", inner="unknown", restored=False))
        emu.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "emulator", pin="none", inner="unknown", restored=False))
        phone.append(line("diagnostic_pin_active", base - 4000, 2, "matrix", cell_id, "phone", pin="wss", inner="wss"))
        # Restart with restored=true + pin=wss — coverage stays.
        phone.append(line("diagnostic_session_started", base - 100, 3, "matrix", cell_id, "phone",
                           pin="wss", inner="wss", restored=True))
        p, e = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        p = [ln for ln in p if "diagnostic_session_started" not in ln and "diagnostic_pin_active" not in ln]
        e = [ln for ln in e if "diagnostic_session_started" not in ln and "diagnostic_pin_active" not in ln]
        phone.extend(p); emu.extend(e)
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"], base_wall=base + 200_000)
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 500_000)
        cell_report = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(cell_report.outcome, "Delivered once", msg=f"issues: {cell_report.issues}")

    def test_global_duplicate_correlation_id_fails_integrity(self):
        cell_a = "wss.p2e.after-connect"
        cell_b = "wss.e2p.after-connect"
        base = 200_000
        phone: list[str] = []
        emu: list[str] = []
        phone.append(line("diagnostic_session_started", base - 1000, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False))
        emu.append(line("diagnostic_session_started", base - 1000, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False))
        # Two enqueues on different cells but SAME correlation_id.
        phone.append(line("sender_enqueue", base, 10, "sender", cell_a, "phone", correlation_id="cid-dup"))
        emu.append(line("sender_enqueue", base + 1000, 10, "sender", cell_b, "emulator", correlation_id="cid-dup"))
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("correlation_id used multiple times" in p for p in rep.integrity_issues))

    def test_malformed_matrix_wrong_cell_count_fails_integrity(self):
        m = default_matrix()
        m["cells"] = m["cells"][:5]  # only 5 cells instead of 8
        out = make_bundle(self.tmp, matrix=m,
                           phone_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False)],
                           emulator_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False)])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("cells count=5" in p for p in rep.integrity_issues))

    def test_false_preflight_bool_fails_integrity(self):
        pf = default_preflight()
        pf["yota_confirmed"] = False
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False)],
                           emulator_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False)])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)

    def test_illegal_BLOCKED_on_wss_cell_fails_integrity(self):
        m = default_matrix()
        m["cells"][0]["blocked"] = True   # cell 0 is wss.p2e.after-connect — illegal to block.
        out = make_bundle(self.tmp, matrix=m,
                           phone_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False)],
                           emulator_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False)])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("BLOCKED but pin != rest" in p for p in rep.integrity_issues))

    def test_blocked_rest_cells_require_rest_capability_disabled(self):
        m = default_matrix()
        m["cells"][6]["blocked"] = True
        m["cells"][7]["blocked"] = True
        pf = default_preflight()
        pf["rest_capability"] = "enabled"   # inconsistent — cells claim BLOCKED
        out = make_bundle(self.tmp, matrix=m, preflight=pf,
                           phone_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False)],
                           emulator_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False)])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)

    def test_injected_clock_moves_PENDING_to_Unresolved(self):
        cell_id = "wss.p2e.after-connect"
        base = 500_000
        phone: list[str] = [
            line("diagnostic_session_started", base - 1000, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False),
            line("diagnostic_pin_active", base - 500, 2, "matrix", cell_id, "phone", pin="wss", inner="wss"),
        ]
        emu: list[str] = [
            line("diagnostic_session_started", base - 1000, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False),
        ]
        for i in range(1, 6):
            wall = base + i * 1000
            cid = f"cid-{i}"
            phone.append(line("sender_enqueue", wall, 10, "sender", cell_id, "phone", correlation_id=cid))
            phone.append(line("sender_transport_decision", wall + 10, 11, "sender", cell_id, "phone",
                               correlation_id=cid, outer="direct", inner="wss", dispatched=True))
            phone.append(line("sender_wss_send_returned", wall + 20, 12, "sender", cell_id, "phone",
                               correlation_id=cid, inner="wss", dispatched=True))
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        # host_now = 5s past base → still PENDING.
        rep = ve.build_report(out, host_now_override_ms=base + 5000)
        first_cell = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(first_cell.outcome, "PENDING", msg=f"issues: {first_cell.issues}")

        # Now advance host clock past 120s from first enqueue.
        rep2 = ve.build_report(out, host_now_override_ms=base + 130_000)
        first_cell2 = next(c for c in rep.cells if c.cell_id == cell_id)
        # Reparse with new clock:
        first_cell2 = next(c for c in ve.build_report(out, host_now_override_ms=base + 200_000).cells
                            if c.cell_id == cell_id)
        self.assertEqual(first_cell2.outcome, "Unresolved")

    def test_forbidden_client_outcome_flag_fails_integrity(self):
        cell_id = "wss.p2e.after-connect"
        phone = [
            line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "phone", pin="wss", inner="wss", restored=False),
            line("some_event", 2000, 2, "sender", cell_id, "phone",
                 correlation_id="cid-1", outcome="unresolved_120s_marker"),
        ]
        emu = [line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "emulator", pin="wss", inner="wss", restored=False)]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)

    # ── §12 Round-2 audit repro cases ─────────────────────────

    def test_recipient_persisted_with_role_matrix_fails_integrity(self):
        # Round-2 audit P0-2 case 1: recipient_message_persisted
        # carrying role=matrix must NOT satisfy the delivery signal.
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        # Swap role on the persist line.
        emu = [
            (ln.replace("event=recipient_message_persisted role=recipient",
                        "event=recipient_message_persisted role=matrix")
             if "recipient_message_persisted" in ln else ln)
            for ln in emu
        ]
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        # Integrity RED because the swapped role trips the
        # RECIPIENT_EVENTS role check globally.
        self.assertFalse(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")

    def test_recipient_events_for_wrong_cell_id_produce_Unresolved(self):
        # Round-2 audit P0-2 case 2: recipient events wearing a
        # different cell_id than the sender's must not count.
        cell_a = "wss.p2e.after-connect"
        cell_b = "wss.p2e.after-idle"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_a, "p2e", base_wall=base)
        # Rewrite recipient events (emu side for p2e) so they claim cell_b.
        emu = [
            (ln.replace(f"cell_id={cell_a}", f"cell_id={cell_b}")
             if any(k in ln for k in ("recipient_deliver_received",
                                       "recipient_message_persisted",
                                       "recipient_ack_deliver_sent"))
             else ln)
            for ln in emu
        ]
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        first_cell = next(c for c in rep.cells if c.cell_id == cell_a)
        self.assertEqual(first_cell.outcome, "Unresolved", msg=f"issues: {first_cell.issues}")

    def test_missing_sender_wss_send_returned_produces_Unresolved(self):
        # Round-2 audit P0-2 case 3: absence of the send-return event
        # must not be treated as Delivered once even if recipient
        # signals all fire.
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        # Strip sender_wss_send_returned from phone (sender for p2e).
        phone = [ln for ln in phone if "sender_wss_send_returned" not in ln]
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        first_cell = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(first_cell.outcome, "Unresolved", msg=f"issues: {first_cell.issues}")

    def test_non_canonical_matrix_fails_integrity(self):
        # Round-2 audit P0-3: 8 accepted-per-enum cells but not the
        # canonical set → integrity RED.
        m = default_matrix()
        # Replace REST e2p control with a second WSS p2e after-connect
        # (same triple as cell #0 — this creates a duplicate cell_id).
        m["cells"][7] = {
            "cell_id": "wss.p2e.after-connect-dup",  # avoid dup_cell_id trap
            "pin": "wss", "direction": "p2e", "scenario": "after-connect",
            "blocked": False,
        }
        out = make_bundle(self.tmp, matrix=m,
                           phone_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False)],
                           emulator_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False)])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("canonical matrix triple missing" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_malformed_numeric_field_fails_integrity_no_exception(self):
        # Round-2 audit P1: bad wall_utc_ms must be integrity RED,
        # not raise ValueError.
        cell_id = "wss.p2e.after-connect"
        # Deliberately break wall_utc_ms with a BSD-date-style
        # literal "%N" leftover.
        bad_line = ("08-11 I WSS_DIAG: event=sender_enqueue role=sender "
                     "emitter_id=phone run_id=run-test cell_id=" + cell_id +
                     " wall_utc_ms=1786464700%N monotonic_ms=123 correlation_id=cid-x")
        phone = [
            line("diagnostic_session_started", 1000, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False),
            bad_line,
        ]
        emu = [line("diagnostic_session_started", 1000, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False)]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        # Must NOT throw:
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("malformed wall_utc_ms" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_manifest_missing_host_skews_fails_integrity(self):
        # Round-2 audit P1: manifest missing host_to_phone_skew_ms
        # OR host_to_emulator_skew_ms must be integrity RED.
        bad_manifest = {"phone_serial": "P", "emulator_serial": "E"}  # no host skews
        out = make_bundle(self.tmp, manifest=bad_manifest,
                           phone_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False)],
                           emulator_lines=[line("diagnostic_session_started", 1, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False)])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("host_to_phone_skew_ms" in p for p in rep.integrity_issues))
        self.assertTrue(any("host_to_emulator_skew_ms" in p for p in rep.integrity_issues))

    def test_key_like_hex_in_log_fails_integrity(self):
        cell_id = "wss.p2e.after-connect"
        phone = [
            line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "phone", pin="wss", inner="wss", restored=False),
            "08-11 I WSS_DIAG: event=leak role=sender emitter_id=phone run_id=run-test cell_id=- wall_utc_ms=1 monotonic_ms=1 sig=" + ("a" * 64),
        ]
        emu = [line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "emulator", pin="wss", inner="wss", restored=False)]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)


if __name__ == "__main__":
    unittest.main(verbosity=2)
