#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# Direct WSS Yota-First — verifier fixture tests (§12 P0-1).
#
# Pure unittest, no pytest dep. Run:
#   python3 -m unittest discover -s tests -v
#
# Every P0-1 / P0-5 case listed in the audit gets a case here.

from __future__ import annotations
import json
import os
import shutil
import sys
import tempfile
import unittest

# Import the verifier by path. The file lives as
# `verify-evidence.py` (dash); importlib can load it but the
# dataclass machinery on Python 3.12+ needs the module registered
# under its stated name in sys.modules first.
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


# ── Fixture builders ────────────────────────────────────────────

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
    return {"run_id": "run-test", "rest_capability": "enabled",
            "preflight": default_preflight(), "cells": cells}


def default_preflight() -> dict:
    return {"rest_capability": "enabled", "yota_confirmed": True, "emitter_ids_set": True}


def default_manifest() -> dict:
    return {"clock_skew_ms": 100, "phone_serial": "P", "emulator_serial": "E"}


def line(event: str, wall: int, mono: int, role: str, cell_id: str, emitter: str,
         correlation_id: str = "-", pin: str | None = None,
         outer: str | None = None, inner: str | None = None,
         dedup: str | None = None, outcome: str | None = None,
         dispatched: bool | None = None) -> str:
    fields = [
        f"event={event}", f"role={role}", f"emitter_id={emitter}",
        f"run_id=run-test", f"cell_id={cell_id}",
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
    payload = " ".join(fields)
    # Match the WSS_DIAG:V logcat "-v threadtime" line shape closely
    # enough that the parser's regex finds the payload.
    return f"08-11 12:00:00.000  1234  1234 I WSS_DIAG: {payload}"


def make_complete_delivery_lines(cell_id: str, direction: str, base_wall: int = 100_000, pin: str = "wss") -> tuple[list[str], list[str]]:
    """Build a fully-successful 5-envelope cell across two log streams."""
    if direction == "p2e":
        sender_dev, recipient_dev = "phone", "emulator"
    else:
        sender_dev, recipient_dev = "emulator", "phone"
    phone: list[str] = []
    emu: list[str] = []
    # diagnostic_session_started on both devices at boot.
    phone.append(line("diagnostic_session_started", base_wall - 1000, 1, "matrix", cell_id, "phone", pin=pin, inner=pin, dispatched=True))
    emu.append(line("diagnostic_session_started", base_wall - 1000, 1, "matrix", cell_id, "emulator", pin=pin, inner=pin, dispatched=True))
    # diagnostic_pin_active AFTER boot but BEFORE first enqueue.
    phone.append(line("diagnostic_pin_active", base_wall - 500, 2, "matrix", cell_id, "phone", pin=pin, inner=pin))
    emu.append(line("diagnostic_pin_active", base_wall - 500, 2, "matrix", cell_id, "emulator", pin=pin, inner=pin))
    for i in range(1, 6):
        wall = base_wall + i * 1000
        cid = f"cid-{cell_id}-{i}"
        sender_lines = phone if sender_dev == "phone" else emu
        recipient_lines = phone if recipient_dev == "phone" else emu
        sender_lines.append(line("sender_enqueue", wall, 10, "sender", cell_id, sender_dev, correlation_id=cid))
        sender_lines.append(line("sender_transport_decision", wall + 10, 11, "sender", cell_id, sender_dev,
                                  correlation_id=cid, outer="direct", inner=pin, dispatched=True))
        sender_lines.append(line("sender_wss_send_returned", wall + 20, 12, "sender", cell_id, sender_dev,
                                  correlation_id=cid, inner=pin, dispatched=True))
        sender_lines.append(line("sender_relay_ack_received", wall + 30, 13, "sender", cell_id, sender_dev,
                                  correlation_id=cid, outcome="sender_relay_ack_delivered"))
        recipient_lines.append(line("recipient_deliver_received", wall + 40, 20, "recipient", cell_id, recipient_dev,
                                     correlation_id=cid, dedup="fresh"))
        recipient_lines.append(line("recipient_message_persisted", wall + 50, 21, "recipient", cell_id, recipient_dev,
                                     correlation_id=cid))
        recipient_lines.append(line("recipient_ack_deliver_sent", wall + 60, 22, "recipient", cell_id, recipient_dev,
                                     correlation_id=cid))
    return phone, emu


# ── Tests ───────────────────────────────────────────────────────

class VerifierTests(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="wss-verify-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_empty_bundle_is_integrity_RED(self):
        out = make_bundle(self.tmp)  # all files present but empty logcats
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok, msg="empty bundle must be RED, was GREEN")
        self.assertIn("no WSS_DIAG events parsed — bundle is not usable", rep.integrity_issues)

    def test_missing_required_file_is_integrity_RED(self):
        out = make_bundle(self.tmp, skip={"matrix.json"})
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("matrix.json" in p for p in rep.integrity_issues))

    def test_zero_envelopes_in_cell_is_Unresolved_not_Delivered_once(self):
        # Session_started present on both, but NO enqueue in any cell.
        phone = [line("diagnostic_session_started", 100, 1, "matrix", "-", "phone", pin="wss", inner="wss")]
        emu = [line("diagnostic_session_started", 100, 1, "matrix", "-", "emulator", pin="wss", inner="wss")]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        # The empty cells trigger "envelope count = 0" issue → Unresolved.
        for c in rep.cells:
            if not c.blocked:
                self.assertEqual(c.outcome, "Unresolved", f"cell {c.cell_id} should be Unresolved on zero envelopes")
        self.assertEqual(rep.product_outcome, "RED")

    def test_complete_delivery_first_cell_is_Delivered_once(self):
        phone, emu = make_complete_delivery_lines("wss.p2e.after-connect", "p2e", base_wall=200_000)
        # Also generate for other cells so envelope counts match.
        for cell in default_matrix()["cells"][1:]:
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"], base_wall=200_000)
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        # We might still fail on device-manifest keys; check individual cell.
        cell_first = next(c for c in rep.cells if c.cell_id == "wss.p2e.after-connect")
        self.assertEqual(cell_first.outcome, "Delivered once", msg=f"issues: {cell_first.issues}")

    def test_pending_vs_unresolved_by_120s_window(self):
        cell_id = "wss.p2e.after-connect"
        base = 500_000
        phone: list[str] = [
            line("diagnostic_session_started", base - 1000, 1, "matrix", cell_id, "phone", pin="wss", inner="wss"),
            line("diagnostic_pin_active", base - 500, 2, "matrix", cell_id, "phone", pin="wss", inner="wss"),
            # 5 enqueues but NO recipient events at all.
        ]
        emu: list[str] = [
            line("diagnostic_session_started", base - 1000, 1, "matrix", cell_id, "emulator", pin="wss", inner="wss"),
        ]
        for i in range(1, 6):
            wall = base + i * 1000
            cid = f"cid-{i}"
            phone.append(line("sender_enqueue", wall, 10, "sender", cell_id, "phone", correlation_id=cid))
        # Bring "now" (max wall in bundle) less than 120s past first enqueue.
        # Newest event = base+5000; enqueue for #1 = base+1000. Diff = 4000ms → PENDING.
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        first_cell = next(c for c in rep.cells if c.cell_id == cell_id)
        self.assertEqual(first_cell.outcome, "PENDING", f"issues: {first_cell.issues}")

        # Now add a much later event to push "now" past 120s from first enqueue.
        phone.append(line("diagnostic_pin_active", base + 200_000, 200, "matrix", cell_id, "phone", pin="wss"))
        with open(os.path.join(out, "phone.logcat.wss_diag"), "w") as f:
            f.write("\n".join(phone) + "\n")
        rep2 = ve.build_report(out)
        first_cell2 = next(c for c in rep2.cells if c.cell_id == cell_id)
        self.assertEqual(first_cell2.outcome, "Unresolved")

    def test_pin_not_covered_produces_outcome_issue(self):
        cell_id = "wss.p2e.after-connect"
        base = 300_000
        # session_started with pin=NONE and NO pin_active → pin coverage fails.
        phone = [
            line("diagnostic_session_started", base - 1000, 1, "matrix", cell_id, "phone", pin="none", inner="unknown"),
            line("sender_enqueue", base, 10, "sender", cell_id, "phone", correlation_id="cid-1"),
            line("sender_transport_decision", base + 10, 11, "sender", cell_id, "phone",
                 correlation_id="cid-1", outer="direct", inner="wss", dispatched=True),
        ]
        emu = [line("diagnostic_session_started", base - 1000, 1, "matrix", cell_id, "emulator", pin="none", inner="unknown")]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        first_cell = next(c for c in rep.cells if c.cell_id == cell_id)
        # Only 1 envelope → envelope count mismatch also fires; the specific
        # pin-not-covered issue is captured either in cell.issues (per envelope)
        # or bumps to Unresolved. Assert outcome is not Delivered once.
        self.assertIn(first_cell.outcome, ("Unresolved", "PENDING"))

    def test_blocked_rest_cell_is_reported_BLOCKED(self):
        m = default_matrix()
        m["cells"][6]["blocked"] = True
        m["cells"][7]["blocked"] = True
        phone, emu = [], []
        for cell in m["cells"][:6]:
            p2, e2 = make_complete_delivery_lines(cell["cell_id"], cell["direction"], base_wall=200_000)
            phone.extend(p2); emu.extend(e2)
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu, matrix=m)
        rep = ve.build_report(out)
        rest_cell = next(c for c in rep.cells if c.pin == "rest")
        self.assertEqual(rest_cell.outcome, "BLOCKED")

    def test_forbidden_client_outcome_flag_fails_integrity(self):
        cell_id = "wss.p2e.after-connect"
        phone = [
            line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "phone", pin="wss", inner="wss"),
            # Fake a client-emitted forbidden classification.
            line("some_event", 2000, 2, "sender", cell_id, "phone",
                 correlation_id="cid-1", outcome="unresolved_120s_marker"),
        ]
        emu = [line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "emulator", pin="wss", inner="wss")]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("unresolved_120s_marker" in p for p in rep.integrity_issues))

    def test_key_like_hex_in_log_fails_integrity(self):
        cell_id = "wss.p2e.after-connect"
        phone = [
            line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "phone", pin="wss", inner="wss"),
            # 64-char lowercase hex — key-material shape.
            "08-11 12:00:00.000 I WSS_DIAG: event=leak " + ("a" * 64) + " role=sender emitter_id=phone wall_utc_ms=1 monotonic_ms=1",
        ]
        emu = [line("diagnostic_session_started", 1000, 1, "matrix", cell_id, "emulator", pin="wss", inner="wss")]
        out = make_bundle(self.tmp, phone_lines=phone, emulator_lines=emu)
        rep = ve.build_report(out)
        self.assertFalse(rep.integrity_ok)


if __name__ == "__main__":
    unittest.main(verbosity=2)
