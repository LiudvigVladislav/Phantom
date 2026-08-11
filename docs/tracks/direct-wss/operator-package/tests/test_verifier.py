#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First — verifier fixture tests v4
# (§12 Round-3 audit repair).
#
# Covers, in addition to the Round-1 + Round-2 case list:
#
#   Round-3 P0-1  cid=None shared-join false GREEN → RED
#                  enqueue with empty CID → integrity RED
#                  wrong dispatched sequence set → Unresolved
#                  missing dispatched → Unresolved
#
#   Round-3 P0-2  phone log carrying emitter_id=emulator → integrity RED
#                  contradictory second sender_transport_decision → Unresolved
#                  opposite route completion (REST return under WSS pin) → Unresolved
#                  wss return without dispatched=true → Unresolved
#
#   Round-3 P0-3  matrix.run_id != preflight.run_id → integrity RED
#                  matrix.run_id != device-manifest.run_id → integrity RED
#                  matrix.rest_capability != preflight.rest_capability → integrity RED
#                  preflight vs manifest skew mismatch → integrity RED
#                  preflight missing apk_variant / env tool / canary /
#                  paired_conversation_count_ok → integrity RED
#                  manifest missing / malformed operator_numeric → integrity RED
#
#   Round-3 P1-1  rest_capability=disabled but REST cells NOT BLOCKED → RED
#                  rest_capability=disabled + BLOCKED + carries enqueues → RED
#                  rest_capability=enabled but REST cell BLOCKED → RED
#
#   Round-3 P1-3  corrupted matrix.json → integrity RED, no exception
#                  matrix.json with cells as string → integrity RED
#                  preflight.json corrupted → integrity RED
#                  manifest.json corrupted → integrity RED

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


def default_preflight() -> dict:
    return {
        "run_id": RUN_ID,
        "env": {"adb": "ok", "python3": "ok", "bash": "ok", "jq": "ok"},
        "apk_variant": "debug",
        "canary": "ok",
        "rest_capability": "enabled",
        "yota_confirmed": True,
        "emitter_ids_set": True,
        "radio_confirmed": True,
        "paired_conversation_count_ok": True,
        "host_to_phone_skew_ms": 100,
        "host_to_emulator_skew_ms": 100,
    }


def default_manifest() -> dict:
    return {
        "run_id": RUN_ID,
        "host_to_phone_skew_ms": 100,
        "host_to_emulator_skew_ms": 100,
        "phone_serial": "P",
        "emulator_serial": "E",
        "dual_sim_report_operator_numeric": "25011",
    }


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
    return {
        "run_id": RUN_ID,
        "rest_capability": "enabled",
        "preflight": default_preflight(),
        "cells": cells,
    }


def make_bundle(tmpdir: str,
                phone_lines: list[str] | None = None,
                emulator_lines: list[str] | None = None,
                matrix: dict | None = None,
                preflight: dict | None = None,
                manifest: dict | None = None,
                skip: set[str] | None = None,
                raw_matrix: str | None = None,
                raw_preflight: str | None = None,
                raw_manifest: str | None = None) -> str:
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
            if raw_matrix is not None:
                f.write(raw_matrix)
            else:
                json.dump(matrix if matrix is not None else default_matrix(), f)
    if "preflight.json" not in skip:
        with open(os.path.join(out, "preflight.json"), "w") as f:
            if raw_preflight is not None:
                f.write(raw_preflight)
            else:
                json.dump(preflight if preflight is not None else default_preflight(), f)
    if "device-manifest.json" not in skip:
        with open(os.path.join(out, "device-manifest.json"), "w") as f:
            if raw_manifest is not None:
                f.write(raw_manifest)
            else:
                json.dump(manifest if manifest is not None else default_manifest(), f)
    return out


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


def rest_completed_line(wall: int, mono: int, cell_id: str, emitter: str,
                        correlation_id: str, acceptance: str) -> str:
    return (f"08-11 12:00:00.000  1234  1234 I WSS_DIAG: "
            f"event=sender_rest_post_completed role=sender emitter_id={emitter} "
            f"run_id={RUN_ID} cell_id={cell_id} wall_utc_ms={wall} monotonic_ms={mono} "
            f"correlation_id={correlation_id} inner_route=rest relay_acceptance={acceptance}")


def make_complete_delivery_lines(cell_id: str, direction: str, base_wall: int = 100_000,
                                 pin: str = "wss", cid_prefix: str = "cid") -> tuple[list[str], list[str]]:
    if direction == "p2e":
        sender_dev, recipient_dev = "phone", "emulator"
    else:
        sender_dev, recipient_dev = "emulator", "phone"
    phone: list[str] = []
    emu: list[str] = []
    # boot: emitter_id ALWAYS matches source device.
    phone.append(line("diagnostic_session_started", base_wall - 1000, 1, "matrix", cell_id, "phone",
                       pin=pin, inner=pin, restored=False))
    emu.append(line("diagnostic_session_started", base_wall - 1000, 1, "matrix", cell_id, "emulator",
                     pin=pin, inner=pin, restored=False))
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
        if pin == "wss":
            sender_lines.append(line("sender_wss_send_returned", wall + 20, 12, "sender", cell_id, sender_dev,
                                      correlation_id=cid, inner=pin, dispatched=True))
        else:
            sender_lines.append(rest_completed_line(wall + 20, 12, cell_id, sender_dev, cid, acceptance="accepted"))
        sender_lines.append(line("sender_relay_ack_received", wall + 30, 13, "sender", cell_id, sender_dev,
                                  correlation_id=cid, outcome="sender_relay_ack_delivered"))
        # §12 Round-3 audit P0-1: diagnostic_send_dispatched, matrix role,
        # sender device, matching CID + sequence.
        sender_lines.append(line("diagnostic_send_dispatched", wall + 5, 9, "matrix", cell_id, sender_dev,
                                  correlation_id=cid, sequence=i))
        recipient_lines.append(line("recipient_deliver_received", wall + 40, 20, "recipient", cell_id, recipient_dev,
                                     correlation_id=cid, dedup="fresh"))
        recipient_lines.append(line("recipient_message_persisted", wall + 50, 21, "recipient", cell_id, recipient_dev,
                                     correlation_id=cid))
        recipient_lines.append(line("recipient_ack_deliver_sent", wall + 60, 22, "recipient", cell_id, recipient_dev,
                                     correlation_id=cid))
    return phone, emu


def build_full_matrix_bundle(tmpdir: str, host_now_ms: int | None = None,
                              matrix_override: dict | None = None) -> tuple[str, int]:
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


def _min_boot(dev: str) -> str:
    return line("diagnostic_session_started", 1, 1, "matrix", "-", dev, pin="wss", inner="wss", restored=False)


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
        rep = ve.build_report(out, host_now_override_ms=base + 200_000 + 120_000)
        self.assertTrue(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
        for c in rep.cells:
            self.assertEqual(c.outcome, "Delivered once", msg=f"{c.cell_id}: {c.issues}")
        self.assertEqual(rep.product_outcome, "GREEN")

    # ── Round-1 audit cases (kept) ──────────────────────────────

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
        m = default_matrix()
        phone = [_min_boot("phone")]
        emu = [_min_boot("emulator")]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out)
        for c in rep.cells:
            if not c.blocked:
                self.assertEqual(c.outcome, "Unresolved")
        self.assertEqual(rep.product_outcome, "RED")

    def test_wrong_emitter_device_produces_integrity_RED(self):
        # Under Round-3 P0-2, phone-log lines with emitter_id=emulator
        # are integrity RED (event provenance mismatch).
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        phone = [ln.replace("emitter_id=phone", "emitter_id=emulator") for ln in phone]
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("event provenance mismatch" in p for p in rep.integrity_issues))

    def test_wrong_outer_transport_with_complete_delivery_produces_Unresolved(self):
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        phone = [ln.replace("outer_transport=direct", "outer_transport=reality") for ln in phone]
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

    def test_wrong_inner_route_with_complete_delivery_produces_Unresolved(self):
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        phone = [
            ln.replace("inner_route=wss dispatched", "inner_route=rest dispatched")
            if "sender_transport_decision" in ln else ln
            for ln in phone
        ]
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

    def test_cross_cell_pin_contamination_is_detected(self):
        cell_a = "wss.p2e.after-connect"
        cell_b = "wss.p2e.after-idle"
        base = 200_000
        phone: list[str] = []
        emu: list[str] = []
        phone.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "phone",
                           pin="wss", inner="wss", restored=False))
        emu.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "emulator",
                         pin="wss", inner="wss", restored=False))
        phone.append(line("diagnostic_pin_active", base - 500, 2, "matrix", cell_a, "phone", pin="wss", inner="wss"))

        pA, eA = make_complete_delivery_lines(cell_a, "p2e", base_wall=base, pin="wss")
        pA = [ln for ln in pA if "diagnostic_pin_active" not in ln and "diagnostic_session_started" not in ln]
        eA = [ln for ln in eA if "diagnostic_pin_active" not in ln and "diagnostic_session_started" not in ln]
        phone.extend(pA); emu.extend(eA)

        pB, eB = make_complete_delivery_lines(cell_b, "p2e", base_wall=base + 30_000, pin="wss", cid_prefix="cidB")
        pB = [ln for ln in pB if "diagnostic_pin_active" not in ln and "diagnostic_session_started" not in ln]
        eB = [ln for ln in eB if "diagnostic_pin_active" not in ln and "diagnostic_session_started" not in ln]
        phone.extend(pB); emu.extend(eB)

        m = default_matrix()
        for cell in m["cells"]:
            if cell["cell_id"] in (cell_a, cell_b) or cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 200_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)

        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 500_000)
        cell_b_report = next(c for c in rep.cells if c.cell_id == cell_b)
        self.assertEqual(cell_b_report.outcome, "Unresolved", msg=f"issues: {cell_b_report.issues}")

    def test_restart_with_lost_pin_invalidates_prior_coverage(self):
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone: list[str] = []
        emu: list[str] = []
        phone.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "phone", pin="none", inner="unknown", restored=False))
        emu.append(line("diagnostic_session_started", base - 5000, 1, "matrix", "-", "emulator", pin="none", inner="unknown", restored=False))
        phone.append(line("diagnostic_pin_active", base - 4000, 2, "matrix", cell_id, "phone", pin="wss", inner="wss"))
        phone.append(line("diagnostic_session_started", base - 100, 3, "matrix", "-", "phone", pin="none", inner="unknown", restored=False))
        p, e = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        p = [ln for ln in p if "diagnostic_session_started" not in ln and "diagnostic_pin_active" not in ln]
        e = [ln for ln in e if "diagnostic_session_started" not in ln and "diagnostic_pin_active" not in ln]
        phone.extend(p); emu.extend(e)
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 200_000, pin=cell["pin"])
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
        phone.append(line("diagnostic_session_started", base - 100, 3, "matrix", cell_id, "phone",
                           pin="wss", inner="wss", restored=True))
        p, e = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        p = [ln for ln in p if "diagnostic_session_started" not in ln and "diagnostic_pin_active" not in ln]
        e = [ln for ln in e if "diagnostic_session_started" not in ln and "diagnostic_pin_active" not in ln]
        phone.extend(p); emu.extend(e)
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 200_000, pin=cell["pin"])
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
        phone.append(line("sender_enqueue", base, 10, "sender", cell_a, "phone", correlation_id="cid-dup"))
        emu.append(line("sender_enqueue", base + 1000, 10, "sender", cell_b, "emulator", correlation_id="cid-dup"))
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("correlation_id used multiple times" in p for p in rep.integrity_issues))

    def test_malformed_matrix_wrong_cell_count_fails_integrity(self):
        m = default_matrix()
        m["cells"] = m["cells"][:5]
        out = make_bundle(self.tmp, matrix=m,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("cells count=5" in p for p in rep.integrity_issues))

    def test_false_preflight_bool_fails_integrity(self):
        pf = default_preflight()
        pf["yota_confirmed"] = False
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)

    def test_illegal_BLOCKED_on_wss_cell_fails_integrity(self):
        m = default_matrix()
        m["cells"][0]["blocked"] = True
        out = make_bundle(self.tmp, matrix=m,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("BLOCKED but pin != rest" in p for p in rep.integrity_issues))

    def test_blocked_rest_cells_require_rest_capability_disabled(self):
        m = default_matrix()
        m["cells"][6]["blocked"] = True
        m["cells"][7]["blocked"] = True
        pf = default_preflight()
        pf["rest_capability"] = "enabled"
        out = make_bundle(self.tmp, matrix=m, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
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
            phone.append(line("diagnostic_send_dispatched", wall + 5, 9, "matrix", cell_id, "phone",
                               correlation_id=cid, sequence=i))
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        # 5s past base → PENDING
        rep = ve.build_report(out, host_now_override_ms=base + 5000)
        first_cell = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(first_cell.outcome, "PENDING", msg=f"issues: {first_cell.issues}")
        # 200s past → Unresolved
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

    # ── Round-2 audit cases (kept) ────────────────────────────

    def test_recipient_persisted_with_role_matrix_fails_integrity(self):
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
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
        self.assertFalse(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")

    def test_recipient_events_for_wrong_cell_id_produce_Unresolved(self):
        cell_a = "wss.p2e.after-connect"
        cell_b = "wss.p2e.after-idle"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_a, "p2e", base_wall=base)
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
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
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
        m = default_matrix()
        m["cells"][7] = {
            "cell_id": "wss.p2e.after-connect-dup",
            "pin": "wss", "direction": "p2e", "scenario": "after-connect",
            "blocked": False,
        }
        out = make_bundle(self.tmp, matrix=m,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("canonical matrix triple missing" in p for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    def test_malformed_numeric_field_fails_integrity_no_exception(self):
        cell_id = "wss.p2e.after-connect"
        bad_line = ("08-11 I WSS_DIAG: event=sender_enqueue role=sender "
                     "emitter_id=phone run_id=run-test cell_id=" + cell_id +
                     " wall_utc_ms=1786464700%N monotonic_ms=123 correlation_id=cid-x")
        phone = [_min_boot("phone"), bad_line]
        emu = [_min_boot("emulator")]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("malformed wall_utc_ms" in p for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    def test_manifest_missing_host_skews_fails_integrity(self):
        bad_manifest = {"run_id": RUN_ID, "phone_serial": "P", "emulator_serial": "E",
                         "dual_sim_report_operator_numeric": "25011"}
        out = make_bundle(self.tmp, manifest=bad_manifest,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
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

    # ── Round-3 audit repro cases ─────────────────────────────

    def _shared_join_bundle(self) -> str:
        """Cell A: 5 enqueues with cid=None (shared join to a single
        recipient triplet). Fully clean elsewhere. Should be Unresolved
        or integrity RED (Round-3 P0-1)."""
        cell_a = "wss.p2e.after-connect"
        base = 200_000
        phone: list[str] = [
            line("diagnostic_session_started", base - 1000, 1, "matrix", "-", "phone", pin="wss", inner="wss", restored=False),
            line("diagnostic_pin_active", base - 500, 2, "matrix", cell_a, "phone", pin="wss", inner="wss"),
        ]
        emu: list[str] = [
            line("diagnostic_session_started", base - 1000, 1, "matrix", "-", "emulator", pin="wss", inner="wss", restored=False),
            line("diagnostic_pin_active", base - 500, 2, "matrix", cell_a, "emulator", pin="wss", inner="wss"),
        ]
        # 5 enqueues WITHOUT correlation_id — but one deliver+persist+ack triplet.
        for i in range(5):
            phone.append(line("sender_enqueue", base + i * 100, 10, "sender", cell_a, "phone"))  # cid absent
        # single recipient triplet without CID either
        emu.append(line("recipient_deliver_received", base + 1000, 20, "recipient", cell_a, "emulator", dedup="fresh"))
        emu.append(line("recipient_message_persisted", base + 1010, 21, "recipient", cell_a, "emulator"))
        emu.append(line("recipient_ack_deliver_sent", base + 1020, 22, "recipient", cell_a, "emulator"))
        # Fill remaining cells cleanly.
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 100_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        return make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)

    def test_R3_P0_1_cid_none_shared_join_is_not_GREEN(self):
        out = self._shared_join_bundle()
        rep = ve.build_report(out, host_now_override_ms=1_000_000_000)
        # Integrity RED: enqueues missing correlation_id.
        self.assertFalse(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
        self.assertTrue(
            any("sender_enqueue missing correlation_id" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )
        # Aggregate cell outcome also not GREEN.
        cell_a = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertNotEqual(cell_a.outcome, "Delivered once")
        self.assertEqual(rep.product_outcome, "RED")

    def test_R3_P0_1_missing_dispatched_events_produces_Unresolved(self):
        out, base = build_full_matrix_bundle(self.tmp)
        # Strip all diagnostic_send_dispatched from phone log.
        p_path = os.path.join(out, "phone.logcat.wss_diag")
        with open(p_path, "r") as f:
            data = f.read().splitlines()
        with open(p_path, "w") as f:
            f.write("\n".join(ln for ln in data if "diagnostic_send_dispatched" not in ln) + "\n")
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        cell = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertEqual(cell.outcome, "Unresolved", msg=f"issues: {cell.issues}")
        self.assertEqual(rep.product_outcome, "RED")

    def test_R3_P0_1_wrong_dispatched_sequence_set_produces_Unresolved(self):
        out, base = build_full_matrix_bundle(self.tmp)
        p_path = os.path.join(out, "phone.logcat.wss_diag")
        with open(p_path, "r") as f:
            data = f.read().splitlines()
        # Rewrite all diagnostic_send_dispatched sequences to 1 on target cell.
        target = "wss.p2e.after-connect"
        newdata = []
        for ln in data:
            if "diagnostic_send_dispatched" in ln and f"cell_id={target}" in ln:
                import re
                ln = re.sub(r"sequence=\d+", "sequence=1", ln)
            newdata.append(ln)
        with open(p_path, "w") as f:
            f.write("\n".join(newdata) + "\n")
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        cell = next(c for c in rep.cells if c.cell_id == target)
        self.assertEqual(cell.outcome, "Unresolved", msg=f"issues: {cell.issues}")

    def test_R3_P0_2_contradictory_transport_decision_produces_Unresolved(self):
        target = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(target, "p2e", base_wall=base)
        # Inject a second contradictory decision line per envelope.
        new = []
        for ln in phone:
            new.append(ln)
            if "sender_transport_decision" in ln and f"cell_id={target}" in ln:
                # duplicate decision with outer=reality
                new.append(ln.replace("outer_transport=direct", "outer_transport=reality"))
        phone = new
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        cell = next(c for c in rep.cells if c.cell_id == target)
        self.assertEqual(cell.outcome, "Unresolved", msg=f"issues: {cell.issues}")
        self.assertTrue(any("contradictory" in i for i in cell.issues),
                        msg=f"issues: {cell.issues}")

    def test_R3_P0_2_opposite_route_return_under_WSS_pin_produces_Unresolved(self):
        target = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(target, "p2e", base_wall=base)
        # Replace each WSS return with a REST completion for target cell.
        new = []
        for ln in phone:
            if "sender_wss_send_returned" in ln and f"cell_id={target}" in ln:
                # extract cid
                cid = ln.split("correlation_id=")[1].split(" ")[0]
                new.append(rest_completed_line(base + 20, 12, target, "phone", cid, "accepted"))
            else:
                new.append(ln)
        phone = new
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        cell = next(c for c in rep.cells if c.cell_id == target)
        self.assertEqual(cell.outcome, "Unresolved", msg=f"issues: {cell.issues}")
        self.assertTrue(any("opposite route" in i for i in cell.issues),
                        msg=f"issues: {cell.issues}")

    def test_R3_P0_2_wss_return_without_dispatched_true_is_Unresolved(self):
        target = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(target, "p2e", base_wall=base)
        phone = [
            (ln.replace("dispatched=true", "dispatched=false")
             if "sender_wss_send_returned" in ln and f"cell_id={target}" in ln
             else ln)
            for ln in phone
        ]
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        cell = next(c for c in rep.cells if c.cell_id == target)
        self.assertEqual(cell.outcome, "Unresolved", msg=f"issues: {cell.issues}")

    def test_R3_P0_3_matrix_run_id_mismatch_preflight_fails_integrity(self):
        pf = default_preflight(); pf["run_id"] = "different-run"
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("matrix.run_id" in p and "preflight.run_id" in p
                             for p in rep.integrity_issues))

    def test_R3_P0_3_matrix_run_id_mismatch_manifest_fails_integrity(self):
        mf = default_manifest(); mf["run_id"] = "different-run"
        out = make_bundle(self.tmp, manifest=mf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("matrix.run_id" in p and "device-manifest.run_id" in p
                             for p in rep.integrity_issues))

    def test_R3_P0_3_rest_capability_matrix_vs_preflight_mismatch(self):
        m = default_matrix(); m["rest_capability"] = "disabled"
        pf = default_preflight()  # rest_capability=enabled
        # Blocked-state parity: matrix says disabled ⇒ REST cells must be BLOCKED.
        # Make the REST cells blocked to isolate the capability mismatch.
        m["cells"][6]["blocked"] = True
        m["cells"][7]["blocked"] = True
        out = make_bundle(self.tmp, matrix=m, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("rest_capability" in p and "preflight.rest_capability" in p
                             for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    def test_R3_P0_3_skew_preflight_vs_manifest_mismatch(self):
        mf = default_manifest(); mf["host_to_phone_skew_ms"] = 5000
        pf = default_preflight()  # 100
        out = make_bundle(self.tmp, preflight=pf, manifest=mf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("host_to_phone_skew_ms" in p and "!=" in p
                             for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    def test_R3_P0_3_preflight_missing_apk_variant_fails_integrity(self):
        pf = default_preflight(); pf.pop("apk_variant")
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("preflight.apk_variant" in p for p in rep.integrity_issues))

    def test_R3_P0_3_preflight_missing_env_tool_fails_integrity(self):
        pf = default_preflight(); del pf["env"]["jq"]
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("preflight.env.jq" in p for p in rep.integrity_issues))

    def test_R3_P0_3_preflight_canary_not_ok_fails_integrity(self):
        pf = default_preflight(); pf["canary"] = "fail"
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("preflight.canary" in p for p in rep.integrity_issues))

    def test_R3_P0_3_paired_conversation_count_ok_false_fails_integrity(self):
        pf = default_preflight(); pf["paired_conversation_count_ok"] = False
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("paired_conversation_count_ok" in p for p in rep.integrity_issues))

    def test_R3_P0_3_manifest_missing_operator_numeric_fails_integrity(self):
        mf = default_manifest(); mf.pop("dual_sim_report_operator_numeric")
        out = make_bundle(self.tmp, manifest=mf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("dual_sim_report_operator_numeric" in p for p in rep.integrity_issues))

    def test_R3_P0_3_manifest_malformed_operator_numeric_fails_integrity(self):
        mf = default_manifest(); mf["dual_sim_report_operator_numeric"] = "ABC12"
        out = make_bundle(self.tmp, manifest=mf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("operator_numeric" in p and "5-6 digit" in p
                             for p in rep.integrity_issues))

    def test_R3_P1_1_rest_disabled_but_rest_cells_not_blocked_is_RED(self):
        m = default_matrix()
        m["rest_capability"] = "disabled"
        # do NOT set blocked=True on the two REST cells
        pf = default_preflight(); pf["rest_capability"] = "disabled"
        out = make_bundle(self.tmp, matrix=m, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("REST cell rest.p2e.control is not BLOCKED" in p
                             for p in rep.integrity_issues))

    def test_R3_P1_1_rest_disabled_blocked_but_has_enqueues_is_RED(self):
        m = default_matrix()
        m["rest_capability"] = "disabled"
        m["cells"][6]["blocked"] = True
        m["cells"][7]["blocked"] = True
        pf = default_preflight(); pf["rest_capability"] = "disabled"
        phone = [_min_boot("phone"),
                 line("sender_enqueue", 5000, 10, "sender", "rest.p2e.control", "phone",
                      correlation_id="stray-1")]
        emu = [_min_boot("emulator")]
        out = make_bundle(self.tmp, matrix=m, preflight=pf,
                           phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("BLOCKED cell rest.p2e.control has 1 enqueue" in p
                             for p in rep.integrity_issues))

    def test_R3_P1_1_rest_enabled_but_cell_blocked_is_RED(self):
        m = default_matrix()
        m["rest_capability"] = "enabled"
        m["cells"][6]["blocked"] = True  # illegal under enabled
        pf = default_preflight(); pf["rest_capability"] = "enabled"
        out = make_bundle(self.tmp, matrix=m, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("REST cell rest.p2e.control is BLOCKED" in p
                             for p in rep.integrity_issues))

    def test_R3_P1_3_corrupt_matrix_json_is_integrity_RED_no_exception(self):
        out = make_bundle(self.tmp, raw_matrix="{ this is not JSON",
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        # Must NOT raise:
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("matrix.json" in p and "JSON parse error" in p
                             for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    def test_R3_P1_3_matrix_wrong_top_level_type_is_integrity_RED(self):
        out = make_bundle(self.tmp, raw_matrix='["not","an","object"]',
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("matrix.json" in p and "top-level type" in p
                             for p in rep.integrity_issues))

    def test_R3_P1_3_matrix_cells_wrong_type_is_integrity_RED(self):
        m = default_matrix(); m["cells"] = "not-a-list"
        out = make_bundle(self.tmp, matrix=m,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("matrix.cells" in p and "wrong type" in p
                             for p in rep.integrity_issues))

    def test_R3_P1_3_corrupt_preflight_json_is_integrity_RED(self):
        out = make_bundle(self.tmp, raw_preflight="{ oops",
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("preflight.json" in p and "JSON parse error" in p
                             for p in rep.integrity_issues))

    def test_R3_P1_3_corrupt_manifest_json_is_integrity_RED(self):
        out = make_bundle(self.tmp, raw_manifest="{ oops",
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("device-manifest.json" in p and "JSON parse error" in p
                             for p in rep.integrity_issues))


if __name__ == "__main__":
    unittest.main(verbosity=2)
