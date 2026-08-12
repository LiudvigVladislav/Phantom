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


FAKE_APK_SHA256 = "e" * 64


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
        "diagnostic_apk_sha256": FAKE_APK_SHA256,
        # §12 Round-8 audit P1: preflight writes per-device readiness
        # booleans so the verifier can enforce the gate at report time.
        "phone_signed_prekey_ready": True,
        "emulator_signed_prekey_ready": True,
    }


def default_manifest() -> dict:
    return {
        "run_id": RUN_ID,
        "host_to_phone_skew_ms": 100,
        "host_to_emulator_skew_ms": 100,
        "phone_serial": "P",
        "emulator_serial": "E",
        "dual_sim_report_operator_numeric": "25011",
        "diagnostic_apk_sha256": FAKE_APK_SHA256,
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


def default_completion(cells_ran: int = 8, abort_reason: str | None = None) -> dict:
    """§12 Round-6 audit P0-2 default matrix_completion.json helper."""
    d = {"run_id": RUN_ID, "completed_at_wall_ms": 1_786_500_000_000, "cells_ran": cells_ran}
    if abort_reason is not None:
        d["abort_reason"] = abort_reason
    return d


def make_bundle(tmpdir: str,
                phone_lines: list[str] | None = None,
                emulator_lines: list[str] | None = None,
                matrix: dict | None = None,
                preflight: dict | None = None,
                manifest: dict | None = None,
                completion: dict | None = None,
                skip: set[str] | None = None,
                raw_matrix: str | None = None,
                raw_preflight: str | None = None,
                raw_manifest: str | None = None,
                raw_completion: str | None = None) -> str:
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
    if "matrix_completion.json" not in skip:
        with open(os.path.join(out, "matrix_completion.json"), "w") as f:
            if raw_completion is not None:
                f.write(raw_completion)
            else:
                json.dump(completion if completion is not None else default_completion(), f)
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
        # §12 Round-7 audit P1-1: send_attempt_started at entry;
        # sender_enqueue keeps its afterEncrypt semantic; both share
        # the same CID.
        sender_lines.append(line("sender_send_attempt_started", wall - 1, 9, "sender", cell_id, sender_dev, correlation_id=cid))
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
        # §12 Round-6 audit P0-2: incomplete matrix (envs=0 across all
        # non-blocked cells) trips integrity RED, so the product outcome
        # is NOT_EVALUABLE — never an authoritative RED/GREEN signal.
        self.assertFalse(rep.integrity_ok)
        self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

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
            # §12 Round-7 audit P1-1: send_attempt_started at entry.
            phone.append(line("sender_send_attempt_started", wall - 1, 9, "sender", cell_id, "phone", correlation_id=cid))
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
        # §12 Round-6 audit P0-2: integrity RED ⇒ NOT_EVALUABLE.
        self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

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
        # Missing dispatched leaves enqueues intact → integrity stays
        # GREEN and product_outcome = RED (real product signal).
        self.assertTrue(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
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

    # ── Round-4 audit repro cases ─────────────────────────────

    # P0-2 — strict boolean blocked: every non-True value must fail
    # integrity AND must NOT be treated as BLOCKED at report time.
    def _blocked_value_case(self, bad_value) -> "ve.VerifyReport":
        m = default_matrix()
        # Try to sneak a WSS cell out of the run.
        m["cells"][0]["blocked"] = bad_value
        out, base = build_full_matrix_bundle(self.tmp, matrix_override=m)
        return ve.build_report(out, host_now_override_ms=base + 400_000)

    def test_R4_P0_2_blocked_string_false_is_integrity_RED_and_not_BLOCKED(self):
        rep = self._blocked_value_case("false")
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("blocked must be a JSON boolean" in p for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")
        cell = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertNotEqual(cell.outcome, "BLOCKED",
                            msg=f"stringly-typed blocked must not skip WSS cell; outcome={cell.outcome}")

    def test_R4_P0_2_blocked_string_true_is_integrity_RED(self):
        rep = self._blocked_value_case("true")
        self.assertFalse(rep.integrity_ok)
        cell = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertNotEqual(cell.outcome, "BLOCKED")

    def test_R4_P0_2_blocked_zero_is_integrity_RED(self):
        rep = self._blocked_value_case(0)
        self.assertFalse(rep.integrity_ok)

    def test_R4_P0_2_blocked_one_is_integrity_RED(self):
        rep = self._blocked_value_case(1)
        self.assertFalse(rep.integrity_ok)
        cell = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertNotEqual(cell.outcome, "BLOCKED")

    def test_R4_P0_2_blocked_null_is_integrity_RED(self):
        rep = self._blocked_value_case(None)
        self.assertFalse(rep.integrity_ok)

    def test_R4_P0_2_blocked_list_is_integrity_RED_no_exception(self):
        rep = self._blocked_value_case([])
        self.assertFalse(rep.integrity_ok)

    def test_R4_P0_2_blocked_object_is_integrity_RED_no_exception(self):
        rep = self._blocked_value_case({"k": "v"})
        self.assertFalse(rep.integrity_ok)

    # P0-3 — strict diagnostic_session_started.
    def _session_started_control_bundle(self, injected_session_kwargs: dict) -> str:
        """Full-delivery bundle with a mid-run session_started injected
        on the phone (sender) BEFORE the first envelope. Kwargs
        control the injected session's run_id, cell_id, pin, restored,
        etc. Everything else is a clean full-matrix delivery."""
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        # Insert an extra session_started on the phone side after the
        # pin_active but BEFORE the first sender_enqueue.
        injected = line("diagnostic_session_started", base + 400, 5, "matrix",
                         injected_session_kwargs.get("cell_id", cell_id), "phone",
                         pin=injected_session_kwargs.get("pin", "wss"),
                         inner=injected_session_kwargs.get("pin", "wss"),
                         run_id=injected_session_kwargs.get("run_id", RUN_ID),
                         restored=injected_session_kwargs.get("restored"))
        # Splice it in ordering-wise (verifier sorts by wall_utc_ms so
        # position in the file is not strictly needed, but keep it neat).
        phone.append(injected)
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        return make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)

    def test_R4_P0_3_session_started_missing_restored_is_integrity_RED(self):
        out = self._session_started_control_bundle({"restored": None})
        rep = ve.build_report(out, host_now_override_ms=600_000)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("missing/malformed restored" in p for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    def test_R4_P0_3_session_started_restored_true_wrong_run_does_not_cover(self):
        out = self._session_started_control_bundle({"restored": True, "run_id": "stale-run"})
        rep = ve.build_report(out, host_now_override_ms=600_000)
        # Wrong run_id → run_id mismatch flag on the event globally
        # (session_started is exempted, but classify_envelope's
        # coverage check must reject the stale-run restored=true).
        cell = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertEqual(cell.outcome, "Unresolved",
                         msg=f"stale-run restored=true must not cover current cell; issues: {cell.issues}")

    def test_R4_P0_3_session_started_restored_true_wrong_cell_does_not_cover(self):
        out = self._session_started_control_bundle({"restored": True, "cell_id": "wss.p2e.after-idle"})
        rep = ve.build_report(out, host_now_override_ms=600_000)
        cell = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertEqual(cell.outcome, "Unresolved",
                         msg=f"wrong-cell restored=true must not cover current cell; issues: {cell.issues}")

    def test_R4_P0_3_session_started_restored_true_wrong_pin_does_not_cover(self):
        out = self._session_started_control_bundle({"restored": True, "pin": "rest"})
        rep = ve.build_report(out, host_now_override_ms=600_000)
        cell = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertEqual(cell.outcome, "Unresolved",
                         msg=f"wrong-pin restored=true must not cover current cell; issues: {cell.issues}")

    def test_R4_P0_3_session_started_restored_true_matching_covers(self):
        # Positive control: matching restored=true preserves coverage.
        out = self._session_started_control_bundle({"restored": True})
        rep = ve.build_report(out, host_now_override_ms=600_000)
        cell = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertEqual(cell.outcome, "Delivered once",
                         msg=f"matching restored=true must cover current cell; issues: {cell.issues}")

    def test_R4_P0_3_session_started_pin_not_in_whitelist_is_integrity_RED(self):
        # Build a bundle with an injected session whose pin is bogus.
        cell_id = "wss.p2e.after-connect"
        phone = [line("diagnostic_session_started", 1000, 1, "matrix", "-", "phone", pin="bogus", inner="unknown", restored=False)]
        emu = [_min_boot("emulator")]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("pin not in whitelist" in p for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    # P1-1 — required transport evidence fields.
    def test_R4_P1_1_transport_decision_without_dispatched_is_Unresolved(self):
        target = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(target, "p2e", base_wall=base)
        # Strip `dispatched=true` from every transport_decision for target cell.
        import re
        phone = [
            re.sub(r" dispatched=(true|false)", "", ln) if "sender_transport_decision" in ln and f"cell_id={target}" in ln else ln
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
        self.assertTrue(any("dispatched must be true" in i for i in cell.issues),
                        msg=f"issues: {cell.issues}")

    def test_R4_P1_1_wss_return_without_inner_route_is_Unresolved(self):
        target = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(target, "p2e", base_wall=base)
        import re
        phone = [
            re.sub(r" inner_route=wss", "", ln) if "sender_wss_send_returned" in ln and f"cell_id={target}" in ln else ln
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
        self.assertTrue(any("inner_route must be wss" in i for i in cell.issues),
                        msg=f"issues: {cell.issues}")

    def test_R4_P1_1_rest_return_without_inner_route_is_Unresolved(self):
        target = "rest.p2e.control"
        base = 200_000
        phone, emu = make_complete_delivery_lines(target, "p2e", base_wall=base, pin="rest")
        import re
        phone = [
            re.sub(r" inner_route=rest", "", ln) if "sender_rest_post_completed" in ln and f"cell_id={target}" in ln else ln
            for ln in phone
        ]
        m = default_matrix()
        for cell in m["cells"]:
            if cell["cell_id"] == target or cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        cell = next(c for c in rep.cells if c.cell_id == target)
        self.assertEqual(cell.outcome, "Unresolved", msg=f"issues: {cell.issues}")
        self.assertTrue(any("inner_route must be rest" in i for i in cell.issues),
                        msg=f"issues: {cell.issues}")

    # P1-2 — nested type-safety: wrong-typed fields must integrity RED
    # and never raise. Table-driven for cell fields and manifest serials.
    def _nested_type_case(self, cell_field: str, bad_value) -> "ve.VerifyReport":
        m = default_matrix()
        m["cells"][0][cell_field] = bad_value
        out = make_bundle(self.tmp, matrix=m,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        # Must not raise.
        return ve.build_report(out)

    def test_R4_P1_2_cell_pin_as_list_is_integrity_RED_no_exception(self):
        rep = self._nested_type_case("pin", [])
        self.assertFalse(rep.integrity_ok)

    def test_R4_P1_2_cell_id_as_list_is_integrity_RED_no_exception(self):
        rep = self._nested_type_case("cell_id", [])
        self.assertFalse(rep.integrity_ok)

    def test_R4_P1_2_cell_direction_as_object_is_integrity_RED_no_exception(self):
        rep = self._nested_type_case("direction", {"a": 1})
        self.assertFalse(rep.integrity_ok)

    def test_R4_P1_2_cell_scenario_as_int_is_integrity_RED_no_exception(self):
        rep = self._nested_type_case("scenario", 42)
        self.assertFalse(rep.integrity_ok)

    def test_R4_P1_2_manifest_phone_serial_as_list_is_integrity_RED(self):
        mf = default_manifest(); mf["phone_serial"] = []
        out = make_bundle(self.tmp, manifest=mf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("phone_serial not a non-empty string" in p
                             for p in rep.integrity_issues))

    def test_R4_P1_2_manifest_emulator_serial_empty_is_integrity_RED(self):
        mf = default_manifest(); mf["emulator_serial"] = ""
        out = make_bundle(self.tmp, manifest=mf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("emulator_serial not a non-empty string" in p
                             for p in rep.integrity_issues))

    def test_R4_P1_2_matrix_run_id_as_list_is_integrity_RED_no_exception(self):
        m = default_matrix(); m["run_id"] = []
        out = make_bundle(self.tmp, matrix=m,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)

    # ── Round-5 audit repro cases ────────────────────────────

    # P0 — strict diagnostic boolean parser. Present-but-malformed
    # values must be integrity RED, not silently coerced to False.
    def test_R5_P0_restored_garbage_makes_full_bundle_RED(self):
        # Full-delivery baseline is GREEN…
        out, base = build_full_matrix_bundle(self.tmp)
        rep_baseline = ve.build_report(out, host_now_override_ms=base + 200_000 + 120_000)
        self.assertTrue(rep_baseline.integrity_ok, msg=f"baseline issues: {rep_baseline.integrity_issues}")
        self.assertEqual(rep_baseline.product_outcome, "GREEN")

        # …but a single `restored=garbage` on the phone's pre-run
        # session_started must trip integrity RED even though the
        # rest of the bundle is untouched.
        p_path = os.path.join(out, "phone.logcat.wss_diag")
        with open(p_path, "r") as f:
            data = f.read()
        mutated = data.replace(
            "event=diagnostic_session_started role=matrix emitter_id=phone",
            "event=diagnostic_session_started role=matrix emitter_id=phone",
            1,
        )
        # Locate first session_started line on phone and swap the
        # `restored=false` for `restored=garbage`.
        lines = mutated.splitlines()
        for idx, ln in enumerate(lines):
            if "event=diagnostic_session_started" in ln and " restored=false" in ln:
                lines[idx] = ln.replace(" restored=false", " restored=garbage")
                break
        else:
            self.fail("could not find a restored=false line to mutate")
        with open(p_path, "w") as f:
            f.write("\n".join(lines) + "\n")

        rep = ve.build_report(out, host_now_override_ms=base + 200_000 + 120_000)
        self.assertFalse(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
        self.assertTrue(
            any("malformed restored" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_R5_P0_dispatched_garbage_makes_bundle_RED(self):
        out, base = build_full_matrix_bundle(self.tmp)
        p_path = os.path.join(out, "phone.logcat.wss_diag")
        with open(p_path, "r") as f:
            data = f.read()
        # Corrupt the first `dispatched=true` on the phone side.
        lines = data.splitlines()
        for idx, ln in enumerate(lines):
            if "sender_transport_decision" in ln and " dispatched=true" in ln:
                lines[idx] = ln.replace(" dispatched=true", " dispatched=garbage")
                break
        else:
            self.fail("could not find a dispatched=true line to mutate")
        with open(p_path, "w") as f:
            f.write("\n".join(lines) + "\n")

        rep = ve.build_report(out, host_now_override_ms=base + 200_000 + 120_000)
        self.assertFalse(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
        self.assertTrue(
            any("malformed dispatched" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_R5_P0_restored_uppercase_TRUE_is_integrity_RED(self):
        # Non-canonical capitalisation must NOT be accepted — the
        # Kotlin emitter always writes lowercase.
        cell_id = "wss.p2e.after-connect"
        bad = "08-11 I WSS_DIAG: event=diagnostic_session_started role=matrix emitter_id=phone run_id=run-test cell_id=- wall_utc_ms=100 monotonic_ms=1 pin=wss inner_route=wss restored=TRUE"
        phone = [bad]
        emu = [_min_boot("emulator")]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("malformed restored" in p and "TRUE" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    # P1 — verifier cross-checks the diagnostic APK SHA-256 that
    # preflight records into preflight.json + device-manifest.json.
    def test_R5_P1_diagnostic_apk_sha256_missing_from_manifest_is_RED(self):
        mf = default_manifest()
        mf.pop("diagnostic_apk_sha256", None)
        pf = default_preflight()
        pf["diagnostic_apk_sha256"] = "abc123"
        out = make_bundle(self.tmp, manifest=mf, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("diagnostic_apk_sha256" in p for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    def test_R5_P1_diagnostic_apk_sha256_mismatch_between_preflight_and_manifest_is_RED(self):
        mf = default_manifest()
        mf["diagnostic_apk_sha256"] = "a" * 64
        pf = default_preflight()
        pf["diagnostic_apk_sha256"] = "b" * 64
        out = make_bundle(self.tmp, manifest=mf, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("diagnostic_apk_sha256" in p and "!=" in p
                             for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    def test_R5_P1_diagnostic_apk_sha256_malformed_is_RED(self):
        mf = default_manifest(); mf["diagnostic_apk_sha256"] = "not-hex"
        pf = default_preflight(); pf["diagnostic_apk_sha256"] = "not-hex"
        out = make_bundle(self.tmp, manifest=mf, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("diagnostic_apk_sha256" in p and "not a 64-char lowercase hex" in p
                             for p in rep.integrity_issues),
                        msg=f"issues: {rep.integrity_issues}")

    # ── Round-6 audit cases ────────────────────────────────────

    # P0-1 — dispatched without matching sender_enqueue = integrity RED.
    def test_R6_P0_1_dispatched_without_send_attempt_started_is_integrity_RED(self):
        # Mimics the live Yota failure exactly: dispatched CIDs are
        # logged but no matching sender_send_attempt_started anywhere
        # (production send path never reached).
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone: list[str] = [
            line("diagnostic_session_started", base - 1000, 1, "matrix", "-", "phone",
                 pin="wss", inner="wss", restored=False),
            line("diagnostic_pin_active", base - 500, 2, "matrix", cell_id, "phone",
                 pin="wss", inner="wss"),
        ]
        emu: list[str] = [
            line("diagnostic_session_started", base - 1000, 1, "matrix", "-", "emulator",
                 pin="wss", inner="wss", restored=False),
            line("diagnostic_pin_active", base - 500, 2, "matrix", cell_id, "emulator",
                 pin="wss", inner="wss"),
        ]
        for i in range(1, 6):
            phone.append(line("diagnostic_send_dispatched", base + i * 1000, 9,
                               "matrix", cell_id, "phone",
                               correlation_id=f"cid-orphan-{i}", sequence=i))
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("has no matching sender_send_attempt_started" in p
                for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )
        self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

    def test_R6_P0_1_preflight_rest_probe_dispatched_without_attempt_started_is_OK(self):
        # The preflight REST-capability probe intentionally has a
        # dispatched with no matching send_attempt_started — it
        # classifies through sender_rest_post_completed only.
        base = 200_000
        phone, emu = make_complete_delivery_lines("wss.p2e.after-connect", "p2e", base_wall=base)
        # Add a dispatched for preflight.rest_capability with NO enqueue.
        phone.append(line("diagnostic_send_dispatched", base - 10_000, 9, "matrix",
                           "preflight.rest_capability", "phone",
                           correlation_id="cid-preflight-probe", sequence=1))
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        self.assertTrue(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")

    # P0-1 — diagnostic_send_command_completed schema.
    def test_R6_P0_1_command_completed_invalid_result_is_integrity_RED(self):
        cell_id = "wss.p2e.after-connect"
        phone = [
            line("diagnostic_session_started", 1000, 1, "matrix", "-", "phone",
                 pin="wss", inner="wss", restored=False),
            # send_command_completed carries result="bogus" (schema violation)
            "08-11 I WSS_DIAG: event=diagnostic_send_command_completed role=matrix "
            "emitter_id=phone run_id=run-test cell_id=" + cell_id +
            " wall_utc_ms=2000 monotonic_ms=2 correlation_id=cid-x sequence=1 result=bogus",
        ]
        emu = [_min_boot("emulator")]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("invalid result='bogus'" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_R6_P0_1_command_completed_valid_results_accepted(self):
        # §12 Round-7 audit P0-1: accepted renamed to handled.
        # §12 Round-9 audit P2: `deferred` removed from the closed
        # schema — production emits only handled|rejected|exception;
        # `sender_prekey_deferred` is a separate event.
        for good in ("handled", "rejected", "exception"):
            cell_id = "wss.p2e.after-connect"
            phone = [
                line("diagnostic_session_started", 1000, 1, "matrix", "-", "phone",
                     pin="wss", inner="wss", restored=False),
                "08-11 I WSS_DIAG: event=diagnostic_send_command_completed role=matrix "
                "emitter_id=phone run_id=run-test cell_id=" + cell_id +
                f" wall_utc_ms=2000 monotonic_ms=2 correlation_id=cid-x sequence=1 result={good}",
            ]
            emu = [_min_boot("emulator")]
            out = make_bundle(
                os.path.join(self.tmp, f"good-{good}"),
                phone_lines=phone, emulator_lines=emu,
            )
            rep = ve.build_report(out)
            issues = [p for p in rep.integrity_issues if "invalid result" in p]
            self.assertEqual(issues, [], msg=f"unexpected schema issues: {issues}")

    # P0-2 — envs != 5 in non-blocked cell is integrity RED.
    def test_R6_P0_2_incomplete_cell_zero_envelopes_is_integrity_RED(self):
        out, base = build_full_matrix_bundle(self.tmp)
        # Strip ALL events for one non-blocked cell.
        target = "wss.p2e.after-connect"
        p_path = os.path.join(out, "phone.logcat.wss_diag")
        with open(p_path, "r") as f:
            data = f.read().splitlines()
        with open(p_path, "w") as f:
            f.write("\n".join(ln for ln in data if f"cell_id={target}" not in ln) + "\n")
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        self.assertFalse(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
        self.assertTrue(
            any("has 0 sender_send_attempt_started events" in p and target in p
                for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )
        self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

    # P0-2 — matrix_completion.json required + shape.
    def test_R6_P0_2_missing_matrix_completion_is_integrity_RED(self):
        out, base = build_full_matrix_bundle(self.tmp)
        os.remove(os.path.join(out, "matrix_completion.json"))
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        self.assertFalse(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
        self.assertTrue(
            any("matrix_completion.json" in p and "missing" in p
                for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_R6_P0_2_matrix_completion_wrong_run_id_is_RED(self):
        out, base = build_full_matrix_bundle(self.tmp)
        comp = default_completion()
        comp["run_id"] = "other-run"
        with open(os.path.join(out, "matrix_completion.json"), "w") as f:
            json.dump(comp, f)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("matrix_completion.run_id" in p and "!= matrix.run_id" in p
                for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_R6_P0_2_matrix_completion_abort_reason_is_RED(self):
        # Aborted matrix (fail-fast gate) must never be GREEN.
        out, base = build_full_matrix_bundle(self.tmp)
        comp = default_completion(abort_reason="tooling_instrumentation_failure")
        with open(os.path.join(out, "matrix_completion.json"), "w") as f:
            json.dump(comp, f)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("abort_reason='tooling_instrumentation_failure'" in p
                for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )
        self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

    def test_R6_P0_2_matrix_completion_wrong_cells_ran_is_RED(self):
        out, base = build_full_matrix_bundle(self.tmp)
        comp = default_completion(cells_ran=3)
        with open(os.path.join(out, "matrix_completion.json"), "w") as f:
            json.dump(comp, f)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("cells_ran=3 != expected 8" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    # P0-2 — real Yota bundle regression. Minimized copy of the live
    # failure evidence: 16 diagnostic_send_dispatched, 15 pin_active,
    # 8 session_started, ZERO enqueue / decision / recipient events.
    def test_R6_P0_2_real_yota_bundle_regression_is_integrity_RED(self):
        real_run_id = "run-yota-20260812T082016Z"
        m = default_matrix(); m["run_id"] = real_run_id
        pf = default_preflight(); pf["run_id"] = real_run_id
        mf = default_manifest(); mf["run_id"] = real_run_id
        # No matrix_completion means the runner was killed mid-run.
        phone: list[str] = []
        emu: list[str] = []
        # session_started × 8 (both boots)
        for i in range(4):
            phone.append(line("diagnostic_session_started", 1000 + i, 1, "matrix", "-",
                               "phone", pin="wss", inner="wss", restored=False, run_id=real_run_id))
            emu.append(line("diagnostic_session_started", 1000 + i, 1, "matrix", "-",
                             "emulator", pin="wss", inner="wss", restored=False, run_id=real_run_id))
        # pin_active × ~15 (per cell / per device) — abbreviate to first 3 cells
        for cell_id in ("wss.p2e.after-connect", "wss.e2p.after-connect", "wss.p2e.after-idle"):
            phone.append(line("diagnostic_pin_active", 2000, 2, "matrix", cell_id, "phone",
                               pin="wss", inner="wss", run_id=real_run_id))
            emu.append(line("diagnostic_pin_active", 2000, 2, "matrix", cell_id, "emulator",
                             pin="wss", inner="wss", run_id=real_run_id))
        # 15 matrix dispatch + 1 preflight probe dispatch, ZERO enqueue.
        phone.append(line("diagnostic_send_dispatched", 3000, 3, "matrix",
                           "preflight.rest_capability", "phone",
                           correlation_id="cid-probe", sequence=1, run_id=real_run_id))
        cells_dispatched = [
            "wss.p2e.after-connect", "wss.e2p.after-connect", "wss.p2e.after-idle",
        ]
        for cid_idx, cell_id in enumerate(cells_dispatched):
            for seq in range(1, 6):
                dev = "phone" if cell_id.startswith("wss.p2e") else "emulator"
                (phone if dev == "phone" else emu).append(
                    line("diagnostic_send_dispatched", 4000 + cid_idx * 100 + seq, 4,
                         "matrix", cell_id, dev,
                         correlation_id=f"cid-{cell_id}-{seq}",
                         sequence=seq, run_id=real_run_id),
                )
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu,
                           matrix=m, preflight=pf, manifest=mf,
                           skip={"matrix_completion.json"})
        rep = ve.build_report(out)
        # Multiple RED triggers: missing matrix_completion, envs=0 cells,
        # 15 dispatched without matching enqueues.
        self.assertFalse(rep.integrity_ok)
        self.assertEqual(rep.product_outcome, "NOT_EVALUABLE",
                          msg="NOT_EVALUABLE: an aborted, evidence-less run must never surface a product signal")

    # ── Round-7 audit repro cases ────────────────────────────

    # P0-1 — PeerBundleMissingException produces a success-shaped
    # Result. `handled` from command_completed MUST NOT be treated
    # as transport acceptance when `sender_prekey_deferred` is
    # present for the same correlation_id.
    def test_R7_P0_1_prekey_deferred_makes_cell_Unresolved_not_delivered(self):
        # Full-delivery baseline for the target cell, then mutate ONE
        # envelope's evidence: replace its sender_enqueue with a
        # sender_prekey_deferred (encryption bailed on
        # PeerBundleMissingException, catch block wrote the deferred
        # event, sendMessage returned Result.success). Recipient
        # signals remain absent for that envelope. Verifier MUST
        # NOT report the cell as Delivered once.
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        target_cid = f"cid-{cell_id}-1"
        # Strip enqueue + transport_decision + wss_send_returned +
        # recipient events for the target CID; add a
        # sender_prekey_deferred instead.
        strip_pat = (
            "sender_enqueue", "sender_transport_decision",
            "sender_wss_send_returned", "sender_relay_ack_received",
            "recipient_deliver_received", "recipient_message_persisted",
            "recipient_ack_deliver_sent",
        )
        phone = [
            ln for ln in phone
            if not any(p in ln and f"correlation_id={target_cid}" in ln for p in strip_pat)
        ]
        emu = [
            ln for ln in emu
            if not any(p in ln and f"correlation_id={target_cid}" in ln for p in strip_pat)
        ]
        # Insert the deferred event on the sender's log.
        phone.append(line(
            "sender_prekey_deferred", base + 1_500, 15, "sender", cell_id, "phone",
            correlation_id=target_cid,
        ))
        # Fill the remaining cells so integrity holds elsewhere.
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        # Integrity stays GREEN (all 5 send_attempts still present,
        # all schema valid). Product outcome: RED — one envelope in
        # the target cell is Unresolved with `deferred` reason.
        self.assertTrue(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
        cell = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(cell.outcome, "Unresolved",
                          msg=f"prekey-deferred envelope must NOT be Delivered once: {cell.issues}")
        self.assertTrue(
            any("sender_prekey_deferred" in i for i in cell.issues),
            msg=f"cell.issues must mention prekey deferral: {cell.issues}",
        )
        self.assertEqual(rep.product_outcome, "RED")

    def test_R7_P0_1_command_completed_result_handled_alone_is_not_transport_acceptance(self):
        # command_completed.result=handled is closed schema OK on its
        # own. But when paired with sender_prekey_deferred for the
        # same CID it MUST NOT be misread as transport acceptance —
        # the cell is Unresolved via the deferred path.
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        target_cid = f"cid-{cell_id}-1"
        # Add command_completed handled + prekey_deferred both for the
        # same CID (no delivery signals). Strip the normal enqueue
        # + delivery signals for that CID.
        strip_pat = (
            "sender_enqueue", "sender_transport_decision",
            "sender_wss_send_returned", "sender_relay_ack_received",
            "recipient_deliver_received", "recipient_message_persisted",
            "recipient_ack_deliver_sent",
        )
        phone = [ln for ln in phone if not any(p in ln and f"correlation_id={target_cid}" in ln for p in strip_pat)]
        emu = [ln for ln in emu if not any(p in ln and f"correlation_id={target_cid}" in ln for p in strip_pat)]
        phone.append(line(
            "sender_prekey_deferred", base + 1_500, 15, "sender", cell_id, "phone",
            correlation_id=target_cid,
        ))
        phone.append(
            "08-11 I WSS_DIAG: event=diagnostic_send_command_completed role=matrix "
            "emitter_id=phone run_id=run-test cell_id=" + cell_id +
            f" wall_utc_ms={base + 1_600} monotonic_ms=16 "
            f"correlation_id={target_cid} sequence=1 result=handled",
        )
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        cell = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertNotEqual(cell.outcome, "Delivered once",
                             msg=f"handled+deferred must NOT be Delivered once: {cell.issues}")

    # P1-1 — sender_enqueue and sender_send_attempt_started are
    # distinct signals. A cell that fires 5 attempts but only 4
    # enqueues (one deferred) still has envelopes=5 and product
    # outcome = RED (via the deferred envelope's Unresolved
    # classification), NOT integrity RED.
    def test_R7_P1_1_send_attempt_started_and_enqueue_are_distinct_signals(self):
        cell_id = "wss.p2e.after-connect"
        base = 200_000
        phone, emu = make_complete_delivery_lines(cell_id, "p2e", base_wall=base)
        target_cid = f"cid-{cell_id}-1"
        strip_pat = (
            "sender_enqueue", "sender_transport_decision",
            "sender_wss_send_returned", "sender_relay_ack_received",
            "recipient_deliver_received", "recipient_message_persisted",
            "recipient_ack_deliver_sent",
        )
        phone = [ln for ln in phone if not any(p in ln and f"correlation_id={target_cid}" in ln for p in strip_pat)]
        emu = [ln for ln in emu if not any(p in ln and f"correlation_id={target_cid}" in ln for p in strip_pat)]
        phone.append(line(
            "sender_prekey_deferred", base + 1_500, 15, "sender", cell_id, "phone",
            correlation_id=target_cid,
        ))
        m = default_matrix()
        for cell in m["cells"][1:]:
            if cell.get("blocked"): continue
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"],
                                                    base_wall=base + 50_000, pin=cell["pin"])
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        cell = next(c for c in rep.cells if c.cell_id == cell_id)
        # 5 send_attempts fired → envs=5, integrity stays GREEN, but
        # cell is Unresolved because the deferred envelope isn't
        # authoritatively delivered.
        self.assertEqual(cell.envelopes, 5, msg=f"expected 5 attempts, got {cell.envelopes}")
        self.assertTrue(rep.integrity_ok, msg=f"issues: {rep.integrity_issues}")
        self.assertEqual(cell.outcome, "Unresolved")

    # ── Round-8 audit repro cases ────────────────────────────

    # P1 — missing per-device readiness boolean = integrity RED.
    def test_R8_P1_missing_phone_signed_prekey_ready_is_integrity_RED(self):
        pf = default_preflight(); pf.pop("phone_signed_prekey_ready")
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("phone_signed_prekey_ready" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_R8_P1_missing_emulator_signed_prekey_ready_is_integrity_RED(self):
        pf = default_preflight(); pf.pop("emulator_signed_prekey_ready")
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("emulator_signed_prekey_ready" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_R8_P1_false_signed_prekey_ready_is_integrity_RED(self):
        pf = default_preflight(); pf["phone_signed_prekey_ready"] = False
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("phone_signed_prekey_ready is not True" in p for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_R8_P1_stringly_typed_signed_prekey_ready_is_integrity_RED(self):
        # `"true"` (string) MUST be rejected — closed schema is JSON bool.
        pf = default_preflight(); pf["phone_signed_prekey_ready"] = "true"
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("phone_signed_prekey_ready must be a JSON boolean" in p
                for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    def test_R8_P1_int_signed_prekey_ready_is_integrity_RED(self):
        # `1` (int) MUST be rejected — closed schema is JSON bool.
        # Also confirms `type(v) is bool` (not `isinstance(v, int)`).
        pf = default_preflight(); pf["emulator_signed_prekey_ready"] = 1
        out = make_bundle(self.tmp, preflight=pf,
                           phone_lines=[_min_boot("phone")],
                           emulator_lines=[_min_boot("emulator")])
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("emulator_signed_prekey_ready must be a JSON boolean" in p
                for p in rep.integrity_issues),
            msg=f"issues: {rep.integrity_issues}",
        )

    # P0-2 — NOT_EVALUABLE never hides behind RED/GREEN.
    def test_R6_P0_2_NOT_EVALUABLE_replaces_product_outcome_on_integrity_RED(self):
        out, base = build_full_matrix_bundle(self.tmp)
        # Delete matrix_completion to force integrity RED.
        os.remove(os.path.join(out, "matrix_completion.json"))
        rep = ve.build_report(out, host_now_override_ms=base + 400_000)
        self.assertFalse(rep.integrity_ok)
        self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")
        self.assertNotEqual(rep.product_outcome, "GREEN")
        self.assertNotEqual(rep.product_outcome, "RED")


if __name__ == "__main__":
    unittest.main(verbosity=2)
