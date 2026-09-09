#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
"""WSS-3 Carrier x VPN Matrix — Python verifier focused fixtures 54-72.

Contract: docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md §9.

Run via:  python3 -m unittest tests.test_verifier_wss3
       or python3 -m unittest discover -s tests -p 'test_verifier*.py'
       (runs both WSS-2 and WSS-3 suites in one go per README).

Fixtures drive the REAL verify_evidence_wss3.py module against scratch
evidence trees built via the shared build_full_profile_evidence
helper below.
"""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import unittest
import uuid

_HERE = os.path.dirname(os.path.abspath(__file__))
_PKG = os.path.dirname(_HERE)
sys.path.insert(0, _PKG)

import verify_evidence_wss3 as ve3  # noqa: E402


def _uuid4() -> str:
    return str(uuid.uuid4())


CANONICAL_PROFILE_IDS = [
    "yota-phone-off-host-on", "yota-phone-off-host-off",
    "yota-phone-on-host-off", "yota-phone-on-host-on",
    "tele2-phone-off-host-on", "tele2-phone-off-host-off",
    "tele2-phone-on-host-off", "tele2-phone-on-host-on",
]

CANONICAL_CELL_IDS = ve3.canonical_cell_ids()
NON_REST_CELL_IDS = [c for c in CANONICAL_CELL_IDS if c not in ve3.CANONICAL_REST_CELL_IDS]
REST_CELL_IDS = list(ve3.CANONICAL_REST_CELL_IDS)


# ── canonical evidence-tree builder used by every fixture

def make_profile_json(*, profile_id, attempt_id, arm_token,
                     supersedes=None, retry_reason=None,
                     carrier="YOTA", phone="off", host="on",
                     expected_operator_numeric="25011") -> dict:
    return {
        "carrier": carrier,
        "phone_vpn": phone,
        "host_vpn": host,
        "expected_operator_numeric": expected_operator_numeric,
        "apk_sha256": "deadbeef" * 8,
        "run_id": "rid-" + attempt_id[:8],
        "attempt_id": attempt_id,
        "supersedes_attempt_id": supersedes,
        "retry_reason": retry_reason,
        "arm_token": arm_token,
        "arm_wall_ms": 1786700464000,
        "arm_monotonic_ms": 12345,
        "confirm_string": f"CONFIRM-{carrier}-PHONE-{phone.upper()}-HOST-{host.upper()}",
        "operator_manual_confirmations": {
            "auto_data_switching_off": True,
            "other_sim_data_disabled": True,
        },
    }


def make_completion_json(*, cells_ran=8, cells_expected_to_run=8,
                        abort_reason=None,
                        run_id="rid", profile_id="yota-phone-off-host-on",
                        attempt_id=None,
                        supersedes_attempt_id=None,
                        retry_reason=None) -> dict:
    if attempt_id is None:
        attempt_id = _uuid4()
    return {
        "schema_version": "1",
        "cells_declared": 8,
        "cells_expected_to_run": cells_expected_to_run,
        "cells_ran": cells_ran,
        "abort_reason": abort_reason,
        "envelopes_sent": cells_ran * 5,
        "envelopes_recipient_triplet_complete": cells_ran * 5,
        "run_id": run_id,
        "profile_id": profile_id,
        "attempt_id": attempt_id,
        "supersedes_attempt_id": supersedes_attempt_id,
        "retry_reason": retry_reason,
        "started_at_wall_ms": 1786700464000,
        "completed_at_wall_ms": 1786700564000,
        "started_at_monotonic_ms": 1,
        "completed_at_monotonic_ms": 2,
        "abort_at_wall_ms": None,
    }


def _write_wss_diag_line(fh, event, cid=None, role=None, emitter="phone",
                        run_id="rid", cell_id=None, dispatched=None,
                        dedup_gate=None, outer=None, inner=None, pin=None,
                        outcome_flag=None, result=None, restored=None,
                        sequence=None, relay_acceptance=None, wall_ms=1000000,
                        deliver_failure=None, deliver_stage=None, attempt=None):
    """Write a real threadtime-format WSS_DIAG line: `WSS_DIAG: event=X ...`."""
    parts = [f"event={event}"]
    if run_id: parts.append(f"run_id={run_id}")
    if cid: parts.append(f"correlation_id={cid}")
    if role: parts.append(f"role={role}")
    if emitter: parts.append(f"emitter_id={emitter}")
    if cell_id: parts.append(f"cell_id={cell_id}")
    if dispatched is not None:
        parts.append(f"dispatched={'true' if dispatched else 'false'}")
    # Audit ROUND-30.17 — which processing attempt this event belongs to.
    if attempt is not None: parts.append(f"attempt={attempt}")
    # Audit ROUND-30.16 — only on `recipient_deliver_failed`.
    if deliver_failure is not None: parts.append(f"deliver_failure={deliver_failure}")
    if deliver_stage is not None: parts.append(f"deliver_stage={deliver_stage}")
    if restored is not None:
        parts.append(f"restored={'true' if restored else 'false'}")
    if dedup_gate: parts.append(f"dedup_gate={dedup_gate}")
    if outer: parts.append(f"outer_transport={outer}")
    if inner: parts.append(f"inner_route={inner}")
    if pin: parts.append(f"pin={pin}")
    if outcome_flag: parts.append(f"outcome_flag={outcome_flag}")
    if result: parts.append(f"result={result}")
    if sequence is not None: parts.append(f"sequence={sequence}")
    if relay_acceptance: parts.append(f"relay_acceptance={relay_acceptance}")
    parts.append(f"wall_utc_ms={wall_ms}")
    parts.append(f"monotonic_ms={wall_ms}")
    fh.write(
        f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: {' '.join(parts)}\n"
    )


def _emit_valid_cid_full_route_with_run(fh, cell_id, cid, emitter, run_id, wall_ms, sequence=None):
    """Same as emit_valid_cid_full_route but with a specific run_id
    (audit P0-5 strict bindings). Audit ROUND-13 P0-1 adds `sequence`
    on sender_send_attempt_started AND diagnostic_send_dispatched to
    match the real orchestrator (mock-adb send handler:220-222).
    """
    is_rest = cell_id.startswith("rest.")
    _write_wss_diag_line(fh, "sender_send_attempt_started", cid=cid, role="sender",
                        emitter=emitter, cell_id=cell_id, run_id=run_id,
                        sequence=sequence, wall_ms=wall_ms)
    _write_wss_diag_line(fh, "sender_enqueue", cid=cid, role="sender",
                        emitter=emitter, cell_id=cell_id, run_id=run_id, wall_ms=wall_ms+1)
    _write_wss_diag_line(fh, "diagnostic_send_dispatched", cid=cid, role="matrix",
                        emitter=emitter, cell_id=cell_id, run_id=run_id,
                        sequence=sequence, wall_ms=wall_ms+2)
    _write_wss_diag_line(fh, "sender_transport_decision", cid=cid, role="sender",
                        emitter=emitter, cell_id=cell_id, run_id=run_id, dispatched=True,
                        outer="direct", inner="rest" if is_rest else "wss", wall_ms=wall_ms+3)
    # Audit ROUND-30.21: match the product's route-dependent shape.
    # Common dispatch proof lives on sender_transport_decision. A WSS
    # return repeats dispatched=true and later receives an async relay
    # ack; REST completes with HTTP relay_acceptance and neither field.
    if is_rest:
        _write_wss_diag_line(fh, "sender_rest_post_completed", cid=cid, role="sender",
                            emitter=emitter, cell_id=cell_id, run_id=run_id,
                            inner="rest", relay_acceptance="accepted",
                            wall_ms=wall_ms+4)
    else:
        _write_wss_diag_line(fh, "sender_wss_send_returned", cid=cid, role="sender",
                            emitter=emitter, cell_id=cell_id, run_id=run_id, dispatched=True,
                            inner="wss", wall_ms=wall_ms+4)
        _write_wss_diag_line(fh, "sender_relay_ack_received", cid=cid, role="sender",
                            emitter=emitter, cell_id=cell_id, run_id=run_id, wall_ms=wall_ms+5)
    other = "emulator" if emitter == "phone" else "phone"
    _write_wss_diag_line(fh, "recipient_deliver_received", cid=cid, role="recipient",
                        emitter=other, cell_id=cell_id, run_id=run_id, dedup_gate="fresh",
                        attempt=1, wall_ms=wall_ms+6)
    _write_wss_diag_line(fh, "recipient_message_persisted", cid=cid, role="recipient",
                        emitter=other, cell_id=cell_id, run_id=run_id, attempt=1, wall_ms=wall_ms+7)
    _write_wss_diag_line(fh, "recipient_ack_deliver_sent", cid=cid, role="recipient",
                        emitter=other, cell_id=cell_id, run_id=run_id, attempt=1, wall_ms=wall_ms+8)


def emit_valid_cid_full_route(fh, cell_id, cid, emitter, wall_ms, run_id="rid"):
    """One full-coverage CID with the physical route-dependent return:
    WSS carries return-dispatched + relay ack; REST carries HTTP
    acceptance and neither WSS-only signal. Audit ROUND-13 P0-1: smoke's single CID per
    direction always has sequence=1 (only one send per smoke pass).
    Audit ROUND-17 P0-3: `run_id` parameter added so smoke helper
    can pass the smoke_run_id from the verdict JSON."""
    is_rest = cell_id.startswith("rest.")
    _write_wss_diag_line(fh, "sender_send_attempt_started", cid=cid, role="sender",
                        emitter=emitter, run_id=run_id, cell_id=cell_id, sequence=1, wall_ms=wall_ms)
    _write_wss_diag_line(fh, "sender_enqueue", cid=cid, role="sender",
                        emitter=emitter, run_id=run_id, cell_id=cell_id, wall_ms=wall_ms+1)
    _write_wss_diag_line(fh, "diagnostic_send_dispatched", cid=cid, role="matrix",
                        emitter=emitter, run_id=run_id, cell_id=cell_id, sequence=1, wall_ms=wall_ms+2)
    _write_wss_diag_line(fh, "sender_transport_decision", cid=cid, role="sender",
                        emitter=emitter, run_id=run_id, cell_id=cell_id, dispatched=True,
                        outer="direct", inner="rest" if is_rest else "wss",
                        wall_ms=wall_ms+3)
    if is_rest:
        _write_wss_diag_line(fh, "sender_rest_post_completed", cid=cid, role="sender",
                            emitter=emitter, run_id=run_id, cell_id=cell_id,
                            inner="rest", relay_acceptance="accepted",
                            wall_ms=wall_ms+4)
    else:
        _write_wss_diag_line(fh, "sender_wss_send_returned", cid=cid, role="sender",
                            emitter=emitter, run_id=run_id, cell_id=cell_id, dispatched=True,
                            inner="wss", wall_ms=wall_ms+4)
        _write_wss_diag_line(fh, "sender_relay_ack_received", cid=cid, role="sender",
                            emitter=emitter, run_id=run_id, cell_id=cell_id, wall_ms=wall_ms+5)
    # Recipient triplet — exactly ONE fresh, and no duplicate.
    other = "emulator" if emitter == "phone" else "phone"
    _write_wss_diag_line(fh, "recipient_deliver_received", cid=cid, role="recipient",
                        emitter=other, run_id=run_id, cell_id=cell_id, dedup_gate="fresh",
                        attempt=1, wall_ms=wall_ms+6)
    _write_wss_diag_line(fh, "recipient_message_persisted", cid=cid, role="recipient",
                        emitter=other, run_id=run_id, cell_id=cell_id, attempt=1, wall_ms=wall_ms+7)
    _write_wss_diag_line(fh, "recipient_ack_deliver_sent", cid=cid, role="recipient",
                        emitter=other, run_id=run_id, cell_id=cell_id, attempt=1, wall_ms=wall_ms+8)


def build_full_profile_evidence(tmp_root, *, profile_id, utc_suffix,
                                attempt_id, arm_token,
                                supersedes=None, retry_reason=None,
                                cells_expected_to_run=8,
                                cells_ran=None,
                                abort_reason=None,
                                clear_failed_emitter=None,
                                mutate_before_return=None,
                                mutate_after_sha256sums=None):
    """Materialise a full-shape evidence directory: PROFILE.json,
    matrix_completion.json, smoke_verdict.json, matrix_cells/<cell>/*
    with per-cell WSS_DIAG capture that satisfies the hardened
    verifier (5 CIDs per non-blocked cell + full route coverage per
    CID)."""
    if cells_ran is None:
        cells_ran = cells_expected_to_run
    parts = profile_id.split("-")
    carrier = parts[0].upper()
    phone = parts[2]
    host = parts[4]
    dirname = f"{profile_id}-{utc_suffix}"
    d = os.path.join(tmp_root, "evidence", dirname)
    os.makedirs(d, exist_ok=True)
    # ── Audit ROUND-29.5 ───────────────────────────────────────────
    # A synthetic "abort" used to be a COMPLETE tree with abort
    # metadata bolted on: nine checkpoints, eight fully-covered cells
    # and forty envelopes beside `cells_ran=2`. That is not what the
    # producer writes, so the exact abort rules had no truthful
    # baseline to be measured against. Shape it the way a real abort
    # looks: cells_ran fully-covered cells, then the cell it stopped
    # on, then never-reached rows.
    _aborted_shape = abort_reason is not None and cells_ran < cells_expected_to_run
    _executed_cells = set(CANONICAL_CELL_IDS[:cells_ran]) if _aborted_shape else None
    _failed_cell_id = (CANONICAL_CELL_IDS[cells_ran]
                       if _aborted_shape and cells_ran < len(CANONICAL_CELL_IDS)
                       else None)
    # ── Audit ROUND-30.12 ──────────────────────────────────────────
    # `pin_clear_unconfirmed` aborts on a cell that FINISHED. Its
    # envelopes were dispatched and delivered, it is counted in
    # `cells_ran`, its own pre-cell checkpoint is inside that count and
    # no later cell ever started — the opposite shape to every other
    # abort reason, where the cell it stopped on never ran.
    #
    # Without this the control fixture could only be built by bolting
    # abort metadata onto a shape the producer never writes, which is
    # exactly the fiction ROUND-29.5 removed. The failing side's clear
    # breadcrumb is withheld below, so the evidence and the verdict
    # agree the way a real run's would.
    _clear_abort_shape = (_aborted_shape
                          and abort_reason == "pin_clear_unconfirmed"
                          and clear_failed_emitter is not None)
    _clear_abort_cell_id = None
    if _clear_abort_shape:
        _failed_cell_id = None
        _clear_abort_cell_id = CANONICAL_CELL_IDS[cells_ran - 1]
    # PROFILE.json
    pj = make_profile_json(
        profile_id=profile_id, attempt_id=attempt_id, arm_token=arm_token,
        supersedes=supersedes, retry_reason=retry_reason,
        carrier=carrier, phone=phone, host=host,
        expected_operator_numeric=("25011" if carrier == "YOTA" else "25020"),
    )
    with open(os.path.join(d, "PROFILE.json"), "w", encoding="utf-8") as f:
        json.dump(pj, f, sort_keys=True, indent=2)
    # matrix_completion.json — cross-referenced against PROFILE.json
    # per audit ROUND-9 P0-5.
    mc = make_completion_json(
        cells_ran=cells_ran,
        cells_expected_to_run=cells_expected_to_run,
        abort_reason=abort_reason,
        run_id=pj["run_id"],
        profile_id=profile_id,
        attempt_id=pj["attempt_id"],
        supersedes_attempt_id=pj.get("supersedes_attempt_id"),
        retry_reason=pj.get("retry_reason"),
    )
    with open(os.path.join(d, "matrix_completion.json"), "w", encoding="utf-8") as f:
        json.dump(mc, f, sort_keys=True, indent=2)
    # smoke_verdict.json (audit ROUND-10 P0-4: VALUES matter;
    # ROUND-17 P0-3: attempt/arm-token/smoke_run_id bindings).
    _smoke_run = "smoke-" + pj["attempt_id"]
    sv = {"p2e": "OK", "e2p": "OK", "integrity": "GREEN", "at_wall_ms": 1786700464000,
          "p2e_cid": "smoke-p2e", "e2p_cid": "smoke-e2p",
          "attempt_id": pj["attempt_id"],
          "arm_token": pj["arm_token"],
          "smoke_run_id": _smoke_run}
    with open(os.path.join(d, "smoke_verdict.json"), "w", encoding="utf-8") as f:
        json.dump(sv, f, sort_keys=True, indent=2)
    # PROFILE_STATE.json (audit ROUND-10 P0-4 evidence schema).
    profile_state = {
        "phone_observed": {
            "active_data_sim_operator_numeric": pj["expected_operator_numeric"],
            "active_data_subscription_id": 1,
            "wifi_enabled": False,
            "mobile_data_enabled": True,
            "has_transport_vpn": (phone == "on"),
            "wifi_read_error": None,
        },
        "emu_observed": {
            "active_network_kind": "CELLULAR",
            "has_transport_vpn": False,
        },
    }
    with open(os.path.join(d, "PROFILE_STATE.json"), "w", encoding="utf-8") as f:
        json.dump(profile_state, f, sort_keys=True, indent=2)
    # network_profile.json (audit ROUND-10 P0-1: NO egress_fingerprint on phone).
    # Audit ROUND-20 P0-1: the helper writes the FULL real reporter
    # schema so cross-boundary tests actually exercise every required
    # field. Source pin: DiagnosticNetworkProfileReporter.kt:524-582.
    # Audit ROUND-21 CLOSURE: helper derives from ONE canonical
    # producer fixture consumed by all three sinks. See
    # docs/tracks/direct-wss/operator-package/fixtures/README.md.
    _FIXTURE_DIR = os.path.join(_PKG, "fixtures")
    def _mk_device_report(*, with_egress: bool, has_vpn: bool):
        name = "canonical_network_profile.emu.json" if with_egress \
            else "canonical_network_profile.phone.json"
        with open(os.path.join(_FIXTURE_DIR, name), encoding="utf-8") as f:
            obj = json.load(f)
        # Per-profile overrides (the fixture pins the default
        # operator; PROFILE.json may declare a different carrier).
        obj["active_data_sim_operator_numeric"] = pj["expected_operator_numeric"]
        obj["has_transport_vpn"] = bool(has_vpn)
        return obj

    network_profile = {
        "phone": _mk_device_report(with_egress=False, has_vpn=(phone == "on")),
        "emu":   _mk_device_report(with_egress=True,  has_vpn=False),
    }
    with open(os.path.join(d, "network_profile.json"), "w", encoding="utf-8") as f:
        json.dump(network_profile, f, sort_keys=True, indent=2)
    # EGRESS_FINGERPRINT.json (audit ROUND-10 P0-4).
    # Audit ROUND-21 P0-3: emu_hmac_fp_hex MUST equal
    # network_profile.emu.egress_fingerprint.hmac_fp_hex (canonical
    # fixture uses "a" * 32).
    _CANON_HMAC = "a" * 32
    # Audit ROUND-24 CONTINUATION G: EGRESS_FINGERPRINT.at_wall_ms
    # MUST equal network_profile.emu.egress_fingerprint.at_wall_ms
    # AND network_profile.emu.at_wall_ms (baseline snapshot owns
    # ONE wall-clock). The canonical fixture pins that value.
    _CANON_BASELINE_TS = 1786817000000
    egress_fp = {
        "host_hmac_fp_hex": _CANON_HMAC,
        "emu_hmac_fp_hex":  _CANON_HMAC,
        "equal_host_emu": True,
        "address_family_host": "AF_INET",
        "address_family_emu": "AF_INET",
        "at_wall_ms": _CANON_BASELINE_TS,
    }
    with open(os.path.join(d, "EGRESS_FINGERPRINT.json"), "w", encoding="utf-8") as f:
        json.dump(egress_fp, f, sort_keys=True, indent=2)
    # signed_prekey_readiness.json (both devices published=true).
    spr = {
        "phone_line": "signed_prekey_readiness published=true signed_prekey_age_days=1 remaining_opks=99",
        "emu_line":   "signed_prekey_readiness published=true signed_prekey_age_days=1 remaining_opks=99",
    }
    with open(os.path.join(d, "signed_prekey_readiness.json"), "w", encoding="utf-8") as f:
        json.dump(spr, f, sort_keys=True, indent=2)
    # checkpoint_log.json — pre-cell x 8 (or 6) + post-matrix, all ok.
    # Audit ROUND-11 P0-#6: `observed` sub-object must carry the full
    # envelope of fields the orchestrator branches on so verifiers can
    # detect a checkpoint that "passed" on partial data. Every field
    # non-null.
    ck_log = []
    # Audit ROUND-12 P0-#4: labels MUST be the canonical
    # `pre-cell:<canonical-cell-id>` sequence + `post-matrix`, matching
    # what the orchestrator writes (lib/wss3-live.sh:715, 809). The
    # prior placeholder `pre-cell:cell-<i>` masked the label sequence
    # check.
    if cells_expected_to_run == 8:
        _cells_in_order = CANONICAL_CELL_IDS
    elif cells_expected_to_run == 6:
        _cells_in_order = [c for c in CANONICAL_CELL_IDS if c not in ve3.CANONICAL_REST_CELL_IDS]
    else:
        _cells_in_order = list(CANONICAL_CELL_IDS)[:cells_expected_to_run]
    for i in range(cells_expected_to_run + 1):
        ck_log.append({
            "label": (f"pre-cell:{_cells_in_order[i]}" if i < cells_expected_to_run else "post-matrix"),
            "started_at_wall_ms": 1786700464000 + i * 1000,
            "completed_at_wall_ms": 1786700464100 + i * 1000,
            "started_at_monotonic_ms": i * 1000,
            "completed_at_monotonic_ms": i * 1000 + 100,
            "result": "ok",
            "drift": False,
            "abort_reason": None,
            "observed": {
                "apk_sha256_phone": pj["apk_sha256"],
                "apk_sha256_emu":   pj["apk_sha256"],
                "paired_count_phone": 1,
                "paired_count_emu":   1,
                "signed_prekey_published_phone": True,
                "signed_prekey_published_emu":   True,
                "host_vpn_ok": True,
                "phone_wifi_enabled": False,
                "phone_mobile_data_enabled": True,
                "phone_has_transport_vpn": (phone == "on"),
                "phone_active_data_subscription_id": "1",
                "phone_active_data_sim_operator_numeric": pj["expected_operator_numeric"],
                "expected_active_data_subscription_id": "1",
            },
            "host_hmac_fp_hex": _CANON_HMAC,
            "emu_hmac_fp_hex":  _CANON_HMAC,
            "equal_host_emu": True,
            "address_family_host": "AF_INET",
            "address_family_emu":  "AF_INET",
        })
    if _clear_abort_shape:
        # Audit ROUND-30.12: the aborting cell RAN, so its pre-cell
        # checkpoint is already one of the `cells_ran` entries and no
        # further cell logged one. No post-matrix entry either.
        ck_log = ck_log[:cells_ran]
    elif _aborted_shape:
        # cells_ran pre-cell checkpoints + the one for the cell it
        # stopped on; no post-matrix entry.
        ck_log = ck_log[:cells_ran + 1]
    with open(os.path.join(d, "checkpoint_log.json"), "w", encoding="utf-8") as f:
        json.dump(ck_log, f, sort_keys=True, indent=2)
    # matrix_verdict.json (roll-up). Audit ROUND-17 P0-1: cells list
    # MUST contain the full canonical 8-cell set with matching
    # verdicts/blocked; sum counts MUST match per-cell verdicts.
    mv_cells = []
    for _cid in CANONICAL_CELL_IDS:
        _is_rest = _cid in ve3.CANONICAL_REST_CELL_IDS
        _blocked = _is_rest and cells_expected_to_run == 6
        if _blocked:
            _row_verdict = "BLOCKED"
        elif not _aborted_shape:
            _row_verdict = "Delivered"
        elif _cid in _executed_cells:
            _row_verdict = "Delivered"
        elif _cid == _failed_cell_id:
            _row_verdict = "Unresolved"
        else:
            _row_verdict = "PENDING"
        mv_cells.append({
            "cell_id": _cid,
            "verdict": _row_verdict,
            "blocked": _blocked,
        })
    mv = {
        "evidence_integrity": "RED" if _aborted_shape else "GREEN",
        "product_outcome": "NOT_EVALUABLE" if _aborted_shape else "GREEN",
        "delivered_count": (cells_ran if _aborted_shape else cells_expected_to_run) * 5,
        "unresolved_count": 0,
        "cells": mv_cells,
    }
    with open(os.path.join(d, "matrix_verdict.json"), "w", encoding="utf-8") as f:
        json.dump(mv, f, sort_keys=True, indent=2)
    # matrix_cells/<cell>/capture.log
    cells_root = os.path.join(d, "matrix_cells")
    os.makedirs(cells_root, exist_ok=True)
    session_run_id = pj["run_id"]
    _ = session_run_id  # ensure ref if only used below
    for cell_id in CANONICAL_CELL_IDS:
        cell_dir = os.path.join(cells_root, cell_id)
        os.makedirs(cell_dir, exist_ok=True)
        is_rest = cell_id in ve3.CANONICAL_REST_CELL_IDS
        blocked = is_rest and cells_expected_to_run == 6
        # Audit ROUND-17 P0-1: cell_verdict includes envelopes_dispatched
        # to match the orchestrator writer (lib/wss3-live.sh:793-802).
        cell_verdict = {
            "cell_id": cell_id,
            "blocked": blocked,
            "envelopes_dispatched": 0 if blocked else 5,
            "delivered_count": 0 if blocked else 5,
            "unresolved_count": 0,
            "verdict": "BLOCKED" if blocked else "Delivered",
        }
        # ── Audit ROUND-29.5: truthful abort shape ─────────────────
        # The cell the matrix stopped on carries its own bounded
        # abort_reason with zero counts and an empty capture (the pin
        # failed before anything was emitted). Cells the matrix never
        # reached keep the untouched PENDING baseline and have NO
        # capture file at all — exactly what the orchestrator leaves
        # on disk.
        _never_reached = False
        if _aborted_shape and not blocked and cell_id not in _executed_cells:
            cell_verdict["envelopes_dispatched"] = 0
            cell_verdict["delivered_count"] = 0
            cell_verdict["unresolved_count"] = 0
            if cell_id == _failed_cell_id:
                cell_verdict["verdict"] = "Unresolved"
                cell_verdict["abort_reason"] = abort_reason
            else:
                cell_verdict["verdict"] = "PENDING"
                _never_reached = True
        # Audit ROUND-30.12: the cleanup failure is recorded ALONGSIDE
        # the real counters, never instead of them — the same rule the
        # producer's own writer follows.
        if _clear_abort_shape and cell_id == _clear_abort_cell_id:
            cell_verdict["abort_reason"] = "pin_clear_unconfirmed"
            cell_verdict["post_cell_clear_emitter"] = clear_failed_emitter
        with open(os.path.join(cell_dir, "cell_verdict.json"), "w", encoding="utf-8") as f:
            json.dump(cell_verdict, f, sort_keys=True, indent=2)
        if _aborted_shape and not blocked and cell_id not in _executed_cells:
            if not _never_reached:
                # Failed cell: empty capture, like the real producer.
                open(os.path.join(cell_dir, "capture.log"), "w",
                     encoding="utf-8").close()
            continue
        if blocked:
            # Blocked cell: write session-started only (or leave dir with
            # empty capture; blocked check requires no dispatched CIDs).
            continue
        emitter = "phone" if ".p2e." in cell_id else "emulator"
        with open(os.path.join(cell_dir, "capture.log"), "w", encoding="utf-8") as f:
            _write_wss_diag_line(f, "diagnostic_session_started", role="matrix",
                                emitter=emitter, run_id=session_run_id,
                                pin="rest" if is_rest else "wss",
                                restored=False, wall_ms=1_000_000)
            # Audit ROUND-29.3 P0: the orchestrator pins BOTH devices
            # to the cell (that is the fix for the field's false cell
            # attribution — recipient events carry the RECIPIENT's own
            # local pin). The canonical synthetic tree must therefore
            # carry BOTH breadcrumbs, exactly one per party, or it no
            # longer describes what a healthy producer writes.
            _recipient = "emulator" if emitter == "phone" else "phone"
            _write_wss_diag_line(f, "diagnostic_pin_active", role="matrix",
                                emitter=emitter, cell_id=cell_id,
                                pin="rest" if is_rest else "wss",
                                run_id=session_run_id, wall_ms=1_000_100)
            _write_wss_diag_line(f, "diagnostic_pin_active", role="matrix",
                                emitter=_recipient, cell_id=cell_id,
                                pin="rest" if is_rest else "wss",
                                run_id=session_run_id, wall_ms=1_000_150)
            for i in range(5):
                cid = f"cid-{session_run_id}-{cell_id.replace('.', '-')}-{i}"
                # Audit ROUND-13 P0-1: sequences MUST cover {1..5}
                # exactly, so pass `sequence=i+1`.
                _emit_valid_cid_full_route_with_run(f, cell_id, cid, emitter,
                                                   session_run_id,
                                                   wall_ms=1_100_000 + i * 100,
                                                   sequence=i + 1)
            # Audit ROUND-30.11: a cell that RAN clears both pins after
            # its last envelope, and the clear is confirmed by a
            # `pin=none` breadcrumb on the DERIVED `<cell>.clear` id.
            # The canonical synthetic tree has to show what a healthy
            # producer now writes, or the clear rule would have no
            # truthful baseline and every fixture below would be
            # measuring the builder instead of the verifier.
            _withheld = set()
            if _clear_abort_shape and cell_id == _clear_abort_cell_id:
                # Audit ROUND-30.12: the side the verdict NAMES did not
                # confirm, so its breadcrumb is absent from the capture.
                # A tree where the named side cleared anyway is the
                # contradiction fixture, not the control.
                if clear_failed_emitter == "both":
                    _withheld = {emitter, _recipient}
                else:
                    _withheld = {clear_failed_emitter}
            for _off, _em in ((0, emitter), (50, _recipient)):
                if _em in _withheld:
                    continue
                _write_wss_diag_line(f, "diagnostic_pin_active", role="matrix",
                                     emitter=_em, cell_id=f"{cell_id}.clear",
                                     pin="none", run_id=session_run_id,
                                     wall_ms=1_200_000 + _off)
    # smoke.log — full smoke sequence too (p2e + e2p). Audit
    # ROUND-17 P0-3: pass smoke_run_id matching smoke_verdict.json.
    with open(os.path.join(d, "smoke.log"), "w", encoding="utf-8") as f:
        # Audit ROUND-29.4 P0: the smoke serialises directions and pins
        # BOTH devices to the direction's cell, so each smoke cell
        # carries exactly two `diagnostic_pin_active` breadcrumbs —
        # one per party. The canonical tree must show what the real
        # producer writes, otherwise the dual-party rule has no
        # healthy baseline to be measured against.
        for _cell, _sender, _recipient, _base in (
            ("wss.p2e.after-connect", "phone", "emulator", 899_000),
            ("wss.e2p.after-connect", "emulator", "phone", 899_500),
        ):
            for _off, _em in ((0, _sender), (50, _recipient)):
                _write_wss_diag_line(f, "diagnostic_pin_active", role="matrix",
                                     emitter=_em, cell_id=_cell, pin="wss",
                                     run_id=_smoke_run, wall_ms=_base + _off)
        emit_valid_cid_full_route(f, "wss.p2e.after-connect", "smoke-p2e", "phone",
                                  wall_ms=900_000, run_id=_smoke_run)
        emit_valid_cid_full_route(f, "wss.e2p.after-connect", "smoke-e2p", "emulator",
                                  wall_ms=900_500, run_id=_smoke_run)
        # Audit ROUND-30.11: each direction that completed clears both
        # pins afterwards — between the directions, and once more after
        # the last one. Both clears are confirmed breadcrumbs on the
        # derived id, which is what the producer now emits and what the
        # verifier requires of a completed direction.
        for _cell, _sender, _recipient, _base in (
            ("wss.p2e.after-connect", "phone", "emulator", 999_000),
            ("wss.e2p.after-connect", "emulator", "phone", 999_500),
        ):
            for _off, _em in ((0, _sender), (50, _recipient)):
                _write_wss_diag_line(f, "diagnostic_pin_active", role="matrix",
                                     emitter=_em, cell_id=f"{_cell}.clear",
                                     pin="none", run_id=_smoke_run,
                                     wall_ms=_base + _off)
    if mutate_before_return is not None:
        mutate_before_return(d)
    # SHA256SUMS.txt written LAST (after any mutation) so it reflects
    # the actual final tree. Excludes itself.
    import hashlib
    lines = []
    for root, _dirs, files in os.walk(d):
        for f in sorted(files):
            if f == "SHA256SUMS.txt":
                continue
            fp = os.path.join(root, f)
            rel = os.path.relpath(fp, d).replace(os.sep, "/")
            h = hashlib.sha256()
            with open(fp, "rb") as g:
                for chunk in iter(lambda: g.read(65536), b""):
                    h.update(chunk)
            lines.append(f"{h.hexdigest()}  {rel}")
    with open(os.path.join(d, "SHA256SUMS.txt"), "w", encoding="utf-8") as f:
        for l in sorted(lines):
            f.write(l + "\n")
    if mutate_after_sha256sums is not None:
        mutate_after_sha256sums(d)
    return d


# ── fixtures 54-72

class Fixture54_directory_naming(unittest.TestCase):
    def test_canonical_dir_matches(self):
        for pid in CANONICAL_PROFILE_IDS:
            self.assertTrue(ve3.EVIDENCE_DIR_RE.match(f"{pid}-20260814T093012Z"))

    def test_missing_utc_suffix_rejected(self):
        self.assertIsNone(ve3.EVIDENCE_DIR_RE.match("yota-phone-off-host-on"))

    def test_bogus_carrier_rejected(self):
        self.assertIsNone(ve3.EVIDENCE_DIR_RE.match("beeline-phone-off-host-on-20260814T093012Z"))

    def test_verify_profile_dir_rejects_non_canonical_dir_name(self):
        with tempfile.TemporaryDirectory() as t:
            d = os.path.join(t, "not-a-valid-profile-dir")
            os.makedirs(d)
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")


class Fixture55_profile_json_schema(unittest.TestCase):
    def test_valid_profile_passes(self):
        p = make_profile_json(profile_id="yota-phone-off-host-on",
                              attempt_id=_uuid4(), arm_token=_uuid4())
        ok, issues = ve3.validate_profile_json(p)
        self.assertTrue(ok, msg=issues)

    def test_bare_uuid_rejected_wrong_version(self):
        p = make_profile_json(profile_id="yota-phone-off-host-on",
                              attempt_id=_uuid4(),
                              arm_token="00000000-0000-1000-8000-000000000000")
        ok, issues = ve3.validate_profile_json(p)
        self.assertFalse(ok)
        self.assertTrue(any("arm_token" in i for i in issues))

    def test_missing_operator_manual_confirmations_rejected(self):
        p = make_profile_json(profile_id="yota-phone-off-host-on",
                              attempt_id=_uuid4(), arm_token=_uuid4())
        del p["operator_manual_confirmations"]
        ok, issues = ve3.validate_profile_json(p)
        self.assertFalse(ok)

    def test_operator_manual_confirmations_wrong_shape_rejected(self):
        p = make_profile_json(profile_id="yota-phone-off-host-on",
                              attempt_id=_uuid4(), arm_token=_uuid4())
        p["operator_manual_confirmations"] = {
            "auto_data_switching_off": True,
            "other_sim_data_disabled": "yes",
        }
        ok, issues = ve3.validate_profile_json(p)
        self.assertFalse(ok)


class Fixture56_completion_schema(unittest.TestCase):
    def test_valid_completion_passes(self):
        ok, issues = ve3.validate_completion_json(make_completion_json())
        self.assertTrue(ok, msg=issues)

    def test_string_cells_ran_rejected(self):
        mc = make_completion_json(); mc["cells_ran"] = "eight"
        self.assertFalse(ve3.validate_completion_json(mc)[0])

    def test_declared_not_eight_rejected(self):
        mc = make_completion_json(); mc["cells_declared"] = 6
        self.assertFalse(ve3.validate_completion_json(mc)[0])

    def test_expected_to_run_out_of_range_rejected(self):
        mc = make_completion_json(); mc["cells_expected_to_run"] = 5
        self.assertFalse(ve3.validate_completion_json(mc)[0])

    def test_unknown_abort_reason_rejected(self):
        mc = make_completion_json(abort_reason="who_knows")
        self.assertFalse(ve3.validate_completion_json(mc)[0])


class Fixture57_egress_fp_field_shape(unittest.TestCase):
    def test_denylisted_field_names_rejected(self):
        fp = {"endpoint": "api4.ipify.org", "address_family": "AF_INET",
              "hmac_fp_hex": "a" * 32, "at_wall_ms": 1, "http_status": 200,
              "timeout_ms_used": 5000, "ip_address": "203.0.113.1"}
        ok, issues = ve3.egress_fingerprint_field_shape(fp)
        self.assertFalse(ok)
        self.assertTrue(any("ip_address" in i for i in issues))

    def test_valid_shape_passes(self):
        fp = {"endpoint": "api4.ipify.org", "address_family": "AF_INET",
              "hmac_fp_hex": "a" * 32, "at_wall_ms": 1, "http_status": 200,
              "timeout_ms_used": 5000}
        ok, issues = ve3.egress_fingerprint_field_shape(fp)
        self.assertTrue(ok, msg=issues)


class Fixture58_completion_required(unittest.TestCase):
    def test_empty_file_is_not_evaluable(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                            utc_suffix="20260814T093012Z",
                                            attempt_id=_uuid4(), arm_token=_uuid4())
            open(os.path.join(d, "matrix_completion.json"), "w").close()
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

    def test_missing_file_is_not_evaluable(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                            utc_suffix="20260814T093012Z",
                                            attempt_id=_uuid4(), arm_token=_uuid4())
            os.remove(os.path.join(d, "matrix_completion.json"))
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")


class Fixture59_no_partial_green(unittest.TestCase):
    def test_missing_recipient_triplet_forces_product_red(self):
        def mutate(d):
            # Delete recipient_deliver_received for ONE CID (the first
            # dispatched-CID) in one cell. CID format under the new
            # audit-P0-5-strict helper is
            # `cid-<run_id>-<cell-dashed>-<i>` so we match by suffix.
            cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cell, encoding="utf-8") as f:
                lines = f.readlines()
            # Find the run_id + build the specific CID for i=0.
            first_cid = None
            for l in lines:
                if "event=diagnostic_send_dispatched" in l:
                    import re
                    m = re.search(r"correlation_id=(\S+)", l)
                    if m:
                        first_cid = m.group(1); break
            filtered = [l for l in lines if not (
                "event=recipient_deliver_received" in l and
                first_cid is not None and
                f"correlation_id={first_cid}" in l
            )]
            with open(cell, "w", encoding="utf-8") as f:
                f.writelines(filtered)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            # Audit ROUND-13 P0-4 contract §7 R18: integrity failures
            # yield NOT_EVALUABLE (not RED).
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")


class Fixture60_forbidden_emitter(unittest.TestCase):
    def _inject(self, d, evt):
        cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
        with open(cell, "a", encoding="utf-8") as f:
            _write_wss_diag_line(f, evt, cid="LEAK", role="sender",
                                 emitter="phone", cell_id="wss.p2e.after-connect",
                                 wall_ms=9_000_000)

    def test_legacy_emitter_forces_integrity_red(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=lambda dd: self._inject(dd, "sender_wss_frame_written"),
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)

    def test_unresolved_120s_marker_forces_integrity_red(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=lambda dd: self._inject(dd, "unresolved_120s_marker"),
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class Fixture61_missing_recipient_triplet(unittest.TestCase):
    def test_missing_triplet_promoted_to_issues(self):
        def mutate(d):
            cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cell, encoding="utf-8") as f:
                lines = f.readlines()
            filtered = [l for l in lines if "recipient_" not in l]
            with open(cell, "w", encoding="utf-8") as f:
                f.writelines(filtered)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            # Audit ROUND-13 P0-4 contract §7 R18: integrity failures
            # yield NOT_EVALUABLE.
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")
            self.assertTrue(any("recipient_" in i for i in rep.integrity_issues))


class Fixture62_compare_row_shape(unittest.TestCase):
    def test_empty_evidence_produces_8_not_evaluable_rows(self):
        with tempfile.TemporaryDirectory() as t:
            os.makedirs(os.path.join(t, "evidence"), exist_ok=True)
            rep = ve3.verify_evidence_root(os.path.join(t, "evidence"))
            self.assertEqual(len(rep.rows), 8)
            for i, pid in enumerate(CANONICAL_PROFILE_IDS):
                self.assertEqual(rep.rows[i].profile_id, pid)
                self.assertEqual(rep.rows[i].product_outcome, "NOT_EVALUABLE")

    def test_row_shape_has_integrity_and_outcome_columns(self):
        with tempfile.TemporaryDirectory() as t:
            os.makedirs(os.path.join(t, "evidence"), exist_ok=True)
            rep = ve3.verify_evidence_root(os.path.join(t, "evidence"))
            for row in rep.rows:
                self.assertTrue(hasattr(row, "integrity_ok"))
                self.assertTrue(hasattr(row, "product_outcome"))

    def test_retry_lineage_footer_false_when_no_retries(self):
        with tempfile.TemporaryDirectory() as t:
            os.makedirs(os.path.join(t, "evidence"), exist_ok=True)
            rep = ve3.verify_evidence_root(os.path.join(t, "evidence"))
            self.assertFalse(rep.retry_lineage_present)


class Fixture63_rest_blocked_not_partial(unittest.TestCase):
    def test_cells_ran_six_equals_expected_six_is_green(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                cells_expected_to_run=6, cells_ran=6,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertTrue(rep.integrity_ok, msg=rep.integrity_issues)
            self.assertEqual(rep.product_outcome, "GREEN")

    def test_cells_ran_less_than_expected_is_not_evaluable(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                cells_expected_to_run=6, cells_ran=5,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class Fixture64_dedup_gate_tolerance(unittest.TestCase):
    def _mk_evt(self, cid, gate):
        class E: pass
        e = E()
        e.event = "recipient_deliver_received"
        e.correlation_id = cid
        e.dedup_gate = gate
        return e

    def test_one_fresh_plus_trailing_duplicate_and_reack_is_ok(self):
        evs = [self._mk_evt("A", "fresh"),
               self._mk_evt("A", "duplicate"),
               self._mk_evt("A", "reack")]
        self.assertEqual(ve3.check_dedup_gate_tolerance(evs), [])

    def test_two_fresh_on_same_cid_is_rejected(self):
        evs = [self._mk_evt("A", "fresh"), self._mk_evt("A", "fresh")]
        self.assertEqual(len(ve3.check_dedup_gate_tolerance(evs)), 1)


class Fixture65_frozensets_imported_from_verify_evidence(unittest.TestCase):
    def test_frozensets_equal_to_accepted_module(self):
        spec = importlib.util.spec_from_file_location(
            "verify_evidence_check",
            os.path.join(_PKG, "verify-evidence.py"),
        )
        mod = importlib.util.module_from_spec(spec)
        sys.modules["verify_evidence_check"] = mod
        spec.loader.exec_module(mod)
        self.assertEqual(set(ve3.RECIPIENT_EVENTS), set(mod.RECIPIENT_EVENTS))
        self.assertEqual(set(ve3.SENDER_EVENTS), set(mod.SENDER_EVENTS))
        self.assertEqual(set(ve3.MATRIX_EVENTS), set(mod.MATRIX_EVENTS))
        self.assertEqual(set(ve3.CROSS_RUN_TOLERATED_EVENTS),
                         set(mod.CROSS_RUN_TOLERATED_EVENTS))

    def test_frozensets_not_locally_hardcoded(self):
        src = open(os.path.join(_PKG, "verify_evidence_wss3.py")).read()
        self.assertIn("RECIPIENT_EVENTS = VE.RECIPIENT_EVENTS", src)
        self.assertIn("SENDER_EVENTS = VE.SENDER_EVENTS", src)
        self.assertIn("MATRIX_EVENTS = VE.MATRIX_EVENTS", src)

    def test_parser_reused_from_accepted_module(self):
        # Audit P0-2: WSS-3 verifier must REUSE VE.parse_events, not
        # re-implement.
        self.assertIs(ve3.parse_events, ve3.VE.parse_events)


class Fixture66_lineage_cycle(unittest.TestCase):
    def test_cycle_two_attempt(self):
        with tempfile.TemporaryDirectory() as t:
            aid_a = _uuid4(); aid_b = _uuid4(); arm = _uuid4()
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260101T000000Z",
                                        attempt_id=aid_a, arm_token=arm,
                                        supersedes=aid_b, retry_reason="vpn_provider_change")
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260102T000000Z",
                                        attempt_id=aid_b, arm_token=arm,
                                        supersedes=aid_a, retry_reason="vpn_provider_change")
            rep = ve3.verify_evidence_root(os.path.join(t, "evidence"))
            row = next(r for r in rep.rows if r.profile_id == "yota-phone-off-host-on")
            self.assertEqual(row.product_outcome, "NOT_EVALUABLE")
            self.assertTrue(any("cycle" in i for i in row.integrity_issues))


class Fixture67_lineage_fork(unittest.TestCase):
    def test_fork_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            root = _uuid4(); a2 = _uuid4(); a3 = _uuid4(); arm = _uuid4()
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260101T000000Z",
                                        attempt_id=root, arm_token=arm)
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260102T000000Z",
                                        attempt_id=a2, arm_token=arm,
                                        supersedes=root, retry_reason="vpn_provider_change")
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260103T000000Z",
                                        attempt_id=a3, arm_token=arm,
                                        supersedes=root, retry_reason="vpn_provider_change")
            rep = ve3.verify_evidence_root(os.path.join(t, "evidence"))
            row = next(r for r in rep.rows if r.profile_id == "yota-phone-off-host-on")
            self.assertEqual(row.product_outcome, "NOT_EVALUABLE")
            self.assertTrue(any("fork" in i for i in row.integrity_issues))


class Fixture68_cross_profile_lineage(unittest.TestCase):
    def test_supersedes_pointing_to_foreign_attempt_is_dangling(self):
        with tempfile.TemporaryDirectory() as t:
            foreign = _uuid4(); aid = _uuid4(); arm = _uuid4(); root = _uuid4()
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-off",
                                        utc_suffix="20260101T000000Z",
                                        attempt_id=foreign, arm_token=_uuid4())
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260101T000000Z",
                                        attempt_id=root, arm_token=arm)
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260102T000000Z",
                                        attempt_id=aid, arm_token=arm,
                                        supersedes=foreign,
                                        retry_reason="vpn_provider_change")
            rep = ve3.verify_evidence_root(os.path.join(t, "evidence"))
            row = next(r for r in rep.rows if r.profile_id == "yota-phone-off-host-on")
            self.assertEqual(row.product_outcome, "NOT_EVALUABLE")
            self.assertTrue(any("dangling" in i or "cross" in i for i in row.integrity_issues))


class Fixture69_dangling_parent(unittest.TestCase):
    def test_dangling_parent_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            arm = _uuid4()
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260101T000000Z",
                                        attempt_id=_uuid4(), arm_token=arm)
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260102T000000Z",
                                        attempt_id=_uuid4(), arm_token=arm,
                                        supersedes=_uuid4(),
                                        retry_reason="vpn_provider_change")
            rep = ve3.verify_evidence_root(os.path.join(t, "evidence"))
            row = next(r for r in rep.rows if r.profile_id == "yota-phone-off-host-on")
            self.assertEqual(row.product_outcome, "NOT_EVALUABLE")
            self.assertTrue(any("dangling" in i for i in row.integrity_issues))


class Fixture70_unpaired_supersedes_retry_reason(unittest.TestCase):
    def test_supersedes_without_reason_rejected(self):
        p = make_profile_json(profile_id="yota-phone-off-host-on",
                              attempt_id=_uuid4(), arm_token=_uuid4(),
                              supersedes=_uuid4(), retry_reason=None)
        ok, issues = ve3.validate_profile_json(p)
        self.assertFalse(ok)
        self.assertTrue(any("paired" in i for i in issues))

    def test_reason_without_supersedes_rejected(self):
        p = make_profile_json(profile_id="yota-phone-off-host-on",
                              attempt_id=_uuid4(), arm_token=_uuid4(),
                              supersedes=None, retry_reason="vpn_provider_change")
        ok, issues = ve3.validate_profile_json(p)
        self.assertFalse(ok)
        self.assertTrue(any("paired" in i for i in issues))


class Fixture71_explicit_resume_incomplete_rejected(unittest.TestCase):
    def test_reserved_reason_rejected_at_cli(self):
        ok, err = ve3.validate_explicit_retry_reason("resume_incomplete")
        self.assertFalse(ok); self.assertIn("bare-resume", err)

    def test_explicit_reasons_accepted(self):
        for r in ve3.EXPLICIT_RETRY_REASONS:
            ok, err = ve3.validate_explicit_retry_reason(r)
            self.assertTrue(ok, msg=f"{r!r}: {err}")


class Fixture72_arm_token_chain_integrity(unittest.TestCase):
    def test_arm_token_disagreement_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            arm1 = _uuid4(); arm2 = _uuid4()  # DIFFERENT
            root = _uuid4(); child = _uuid4()
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260101T000000Z",
                                        attempt_id=root, arm_token=arm1)
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260102T000000Z",
                                        attempt_id=child, arm_token=arm2,
                                        supersedes=root, retry_reason="vpn_provider_change")
            rep = ve3.verify_evidence_root(os.path.join(t, "evidence"))
            row = next(r for r in rep.rows if r.profile_id == "yota-phone-off-host-on")
            self.assertEqual(row.product_outcome, "NOT_EVALUABLE")
            self.assertTrue(any("arm_token disagreement" in i for i in row.integrity_issues))

    def test_arm_token_agreement_passes_lineage(self):
        with tempfile.TemporaryDirectory() as t:
            arm = _uuid4()  # SAME
            root = _uuid4(); child = _uuid4()
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260101T000000Z",
                                        attempt_id=root, arm_token=arm)
            build_full_profile_evidence(t, profile_id="yota-phone-off-host-on",
                                        utc_suffix="20260102T000000Z",
                                        attempt_id=child, arm_token=arm,
                                        supersedes=root, retry_reason="vpn_provider_change")
            rep = ve3.verify_evidence_root(os.path.join(t, "evidence"))
            row = next(r for r in rep.rows if r.profile_id == "yota-phone-off-host-on")
            self.assertFalse(any("arm_token disagreement" in i for i in row.integrity_issues))


# ── audit ROUND-8 P0-2 regression fixtures (never GREEN traps) ──

class AuditP0_2_zero_events_never_green(unittest.TestCase):
    """Audit P0-2 regression: profile with valid PROFILE + completion
    but zero WSS_DIAG events in every cell capture — must NOT be GREEN."""

    def test_zero_events_forces_not_evaluable(self):
        def wipe(d):
            for root, _dirs, files in os.walk(d):
                for f in files:
                    if f.endswith(".log"):
                        open(os.path.join(root, f), "w").close()
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=wipe,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")
            self.assertNotEqual(rep.product_outcome, "GREEN")


class AuditP0_2_unknown_event_forces_red(unittest.TestCase):
    def test_unknown_event_name_forces_red(self):
        def inject(d):
            cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cell, "a", encoding="utf-8") as f:
                _write_wss_diag_line(f, "some_new_event_never_specced", cid="X",
                                     role="sender", emitter="phone",
                                     cell_id="wss.p2e.after-connect", wall_ms=9_999_999)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=inject,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditP0_2_duplicate_only_delivery_never_green(unittest.TestCase):
    def test_zero_fresh_only_duplicate_forces_red(self):
        def swap(d):
            cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cell, encoding="utf-8") as f:
                content = f.read()
            content = content.replace("dedup_gate=fresh", "dedup_gate=duplicate")
            with open(cell, "w", encoding="utf-8") as f:
                f.write(content)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=swap,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditP0_2_missing_cell_directory_never_green(unittest.TestCase):
    def test_missing_cell_dir_forces_red(self):
        def drop(d):
            import shutil
            shutil.rmtree(os.path.join(d, "matrix_cells", "wss.p2e.after-idle"))
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=drop,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditP0_2_missing_cids_never_green(unittest.TestCase):
    def test_only_three_cids_per_cell_forces_red(self):
        def truncate_cids(d):
            cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            # Rewrite cell with only 3 CIDs.
            emitter = "phone"
            with open(cell, "w", encoding="utf-8") as f:
                _write_wss_diag_line(f, "diagnostic_session_started", role="matrix",
                                     emitter=emitter, pin="wss", restored=False,
                                     wall_ms=1_000_000)
                for i in range(3):
                    cid = f"cid-wss-p2e-after-connect-{i}"
                    emit_valid_cid_full_route(f, "wss.p2e.after-connect", cid,
                                              emitter, wall_ms=1_100_000 + i * 100)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=truncate_cids,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditP0_2_missing_recipient_ack_never_green(unittest.TestCase):
    def test_missing_recipient_ack_deliver_sent_is_red(self):
        def drop_ack(d):
            cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cell, encoding="utf-8") as f:
                lines = f.readlines()
            filtered = [l for l in lines if "recipient_ack_deliver_sent" not in l]
            with open(cell, "w", encoding="utf-8") as f:
                f.writelines(filtered)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=drop_ack,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditP0_2_full_green_happy_path(unittest.TestCase):
    """Positive control — full valid tree passes GREEN."""

    def test_full_valid_tree_is_green(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
            )
            rep = ve3.verify_profile_dir(d)
            self.assertTrue(rep.integrity_ok, msg=rep.integrity_issues)
            self.assertEqual(rep.product_outcome, "GREEN")


# ── audit ROUND-10 regression fixtures ────────────────────────

class AuditR10_smoke_red_forces_not_green(unittest.TestCase):
    """Audit ROUND-10 P0-4 explicit — smoke_verdict.integrity=RED
    must never yield a GREEN outcome. Previously the verifier only
    checked KEY presence."""

    def test_smoke_verdict_integrity_red(self):
        def mutate(d):
            with open(os.path.join(d, "smoke_verdict.json"), "w") as f:
                json.dump({"p2e": "FAIL", "e2p": "FAIL", "integrity": "RED",
                           "at_wall_ms": 1786700464000}, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertNotEqual(rep.product_outcome, "GREEN")


class AuditR10_missing_evidence_forces_red(unittest.TestCase):
    """Audit ROUND-10 P0-4 explicit — any missing §5 file (deleted
    after build) blocks GREEN."""

    def _test_missing(self, name):
        def mutate(d):
            os.remove(os.path.join(d, name))
        with tempfile.TemporaryDirectory() as t:
            # SHA256SUMS.txt is generated inside build_full_profile_evidence
            # AFTER mutate_before_return runs; deleting/tampering with it
            # requires the post-hash hook.
            kw = {"mutate_after_sha256sums": mutate} if name == "SHA256SUMS.txt" \
                 else {"mutate_before_return": mutate}
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                **kw,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok, msg=f"missing {name} was silently accepted")
            self.assertNotEqual(rep.product_outcome, "GREEN")

    def test_missing_PROFILE_STATE(self):        self._test_missing("PROFILE_STATE.json")
    def test_missing_network_profile(self):      self._test_missing("network_profile.json")
    def test_missing_EGRESS_FINGERPRINT(self):   self._test_missing("EGRESS_FINGERPRINT.json")
    def test_missing_signed_prekey(self):        self._test_missing("signed_prekey_readiness.json")
    def test_missing_checkpoint_log(self):       self._test_missing("checkpoint_log.json")
    def test_missing_matrix_verdict(self):       self._test_missing("matrix_verdict.json")
    def test_missing_SHA256SUMS(self):           self._test_missing("SHA256SUMS.txt")


class AuditR10_phone_hmac_forbidden_forces_red(unittest.TestCase):
    """Audit ROUND-10 P0-1 explicit — EGRESS_FINGERPRINT.json with
    any phone_hmac_fp_hex field OR network_profile.json.phone with an
    egress_fingerprint MUST force integrity RED."""

    def test_egress_fp_phone_hmac_field_forces_red(self):
        def mutate(d):
            with open(os.path.join(d, "EGRESS_FINGERPRINT.json"), encoding="utf-8") as f:
                ef = json.load(f)
            ef["phone_hmac_fp_hex"] = "c" * 32  # banned
            with open(os.path.join(d, "EGRESS_FINGERPRINT.json"), "w", encoding="utf-8") as f:
                json.dump(ef, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("phone_hmac_fp_hex" in i for i in rep.integrity_issues))

    def test_network_profile_phone_egress_forces_red(self):
        def mutate(d):
            with open(os.path.join(d, "network_profile.json"), encoding="utf-8") as f:
                npj = json.load(f)
            npj["phone"]["egress_fingerprint"] = {"hmac_fp_hex": "d" * 32}
            with open(os.path.join(d, "network_profile.json"), "w", encoding="utf-8") as f:
                json.dump(npj, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR10_sha256sums_hash_mismatch_forces_red(unittest.TestCase):
    """Audit ROUND-10 P0-4 explicit — SHA256SUMS.txt tampered so a
    hash no longer matches must force integrity RED."""

    def test_sha256sums_tampered(self):
        def mutate(d):
            p = os.path.join(d, "SHA256SUMS.txt")
            with open(p, encoding="utf-8") as f:
                lines = f.readlines()
            # Swap first char of first line's hash → mismatch.
            if lines:
                first = lines[0]
                # first line: "<hash>  <path>"
                new_hash = ("0" if first[0] != "0" else "1") + first[1:]
                lines[0] = new_hash
            with open(p, "w", encoding="utf-8") as f:
                f.writelines(lines)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("hash mismatch" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR10_checkpoint_log_missing_observed_forces_red(unittest.TestCase):
    """Audit ROUND-10 P0-5 — checkpoint_log.json entry missing the
    `observed` object forces integrity RED."""

    def test_missing_observed_forces_red(self):
        def mutate(d):
            with open(os.path.join(d, "checkpoint_log.json"), encoding="utf-8") as f:
                arr = json.load(f)
            for e in arr:
                e.pop("observed", None)
            with open(os.path.join(d, "checkpoint_log.json"), "w", encoding="utf-8") as f:
                json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR10_profile_state_operator_mismatch_forces_red(unittest.TestCase):
    """Audit ROUND-10 P0-4 PROFILE_STATE cross-check — observed
    operator numeric must equal PROFILE.expected_operator_numeric."""

    def test_operator_mismatch_forces_red(self):
        def mutate(d):
            with open(os.path.join(d, "PROFILE_STATE.json"), encoding="utf-8") as f:
                ps = json.load(f)
            ps["phone_observed"]["active_data_sim_operator_numeric"] = "25099"  # not 25011
            with open(os.path.join(d, "PROFILE_STATE.json"), "w", encoding="utf-8") as f:
                json.dump(ps, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


# ── audit ROUND-11 regression fixtures ────────────────────────
#
# Each pins ONE of the P0/P1 findings from architect ROUND-11 verdict.

class AuditR11_verifier_fallback_removed(unittest.TestCase):
    """Audit ROUND-11 P0-#3: stripping every diagnostic_send_dispatched
    line from a cell capture MUST force RED. The prior fallback on
    sender_send_attempt_started allowed a fully-stripped matrix to
    remain GREEN. Architect proved this by deleting 40 dispatched
    lines and re-computing SHA."""

    def test_strip_all_dispatched_from_one_cell_forces_red(self):
        def mutate(d):
            # Remove diagnostic_send_dispatched lines from every
            # cell capture — proves the fallback path no longer promotes.
            import re
            for root, _dirs, files in os.walk(os.path.join(d, "matrix_cells")):
                for f in files:
                    if not f.endswith(".log"):
                        continue
                    p = os.path.join(root, f)
                    with open(p, encoding="utf-8") as fh:
                        s = fh.read()
                    s2 = re.sub(r".*event=diagnostic_send_dispatched.*\n", "", s)
                    with open(p, "w", encoding="utf-8") as fh:
                        fh.write(s2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertNotEqual(rep.product_outcome, "GREEN")


class AuditR11_smoke_log_rebuild(unittest.TestCase):
    """Audit ROUND-11 P0-#4: smoke.log MUST be re-parsed to rebuild
    both CID chains; a JSON verdict alone is not sufficient."""

    def test_smoke_log_replaced_with_garbage_forces_red(self):
        def mutate(d):
            with open(os.path.join(d, "smoke.log"), "w", encoding="utf-8") as f:
                f.write("not a WSS_DIAG smoke chain\n")
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            # Must reference the smoke.log-rebuild issues so the
            # fix path is unambiguous.
            self.assertTrue(any("smoke.log" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)

    def test_smoke_log_missing_p2e_chain_forces_red(self):
        def mutate(d):
            # Strip every phone→emu recipient triplet event from
            # smoke.log — the p2e chain becomes structurally
            # incomplete even though the JSON claims p2e=OK.
            import re
            p = os.path.join(d, "smoke.log")
            with open(p, encoding="utf-8") as f:
                lines = f.readlines()
            keep = []
            for l in lines:
                if "correlation_id=smoke-p2e" in l and (
                    "event=recipient_" in l
                ):
                    continue
                keep.append(l)
            with open(p, "w", encoding="utf-8") as f:
                f.writelines(keep)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR11_checkpoint_canonical_count(unittest.TestCase):
    """Audit ROUND-11 P0-#6: a profile with fewer than N+1 checkpoints
    (where N=cells_expected_to_run) MUST be RED. Prior version accepted
    even 1 checkpoint for an 8-cell run."""

    def test_only_one_checkpoint_when_nine_expected_forces_red(self):
        def mutate(d):
            with open(os.path.join(d, "checkpoint_log.json"), encoding="utf-8") as f:
                arr = json.load(f)
            arr = arr[:1]  # keep only 1 entry
            with open(os.path.join(d, "checkpoint_log.json"), "w", encoding="utf-8") as f:
                json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("expected exactly 9" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR11_checkpoint_observed_non_null(unittest.TestCase):
    """Audit ROUND-11 P0-#6: observed sub-object fields set but null
    (i.e. checkpoint executed but observed no data) MUST be RED."""

    def test_null_observed_signed_prekey_forces_red(self):
        def mutate(d):
            with open(os.path.join(d, "checkpoint_log.json"), encoding="utf-8") as f:
                arr = json.load(f)
            arr[0]["observed"]["signed_prekey_published_phone"] = None
            with open(os.path.join(d, "checkpoint_log.json"), "w", encoding="utf-8") as f:
                json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR11_completion_envelopes_truthful(unittest.TestCase):
    """Audit ROUND-11 P1-#7: envelopes_sent=0 or
    envelopes_recipient_triplet_complete=0 in a non-aborted run
    MUST be RED."""

    def test_envelopes_sent_zero_forces_red(self):
        def mutate(d):
            with open(os.path.join(d, "matrix_completion.json"), encoding="utf-8") as f:
                mc = json.load(f)
            mc["envelopes_sent"] = 0
            with open(os.path.join(d, "matrix_completion.json"), "w", encoding="utf-8") as f:
                json.dump(mc, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR11_sha_covers_smoke_log(unittest.TestCase):
    """Audit ROUND-11 P1-#7: SHA256SUMS.txt without a line for
    smoke.log MUST be RED — a truncated manifest silently exempts
    key evidence."""

    def test_sha_manifest_missing_smoke_log_line_forces_red(self):
        def mutate(d):
            p = os.path.join(d, "SHA256SUMS.txt")
            with open(p, encoding="utf-8") as f:
                lines = f.readlines()
            lines = [l for l in lines if not l.rstrip().endswith("smoke.log")]
            with open(p, "w", encoding="utf-8") as f:
                f.writelines(lines)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("smoke.log" in i and "no line" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR11_parse_errors_propagate(unittest.TestCase):
    """Audit ROUND-11 P1-#7: malformed wall_utc_ms (or any other
    strict-parse error) in a cell capture MUST NOT be silently
    dropped — the profile becomes RED."""

    def test_malformed_wall_utc_ms_in_cell_forces_red(self):
        def mutate(d):
            # Corrupt one wall_utc_ms in the first non-blocked cell.
            cells_root = os.path.join(d, "matrix_cells")
            for cid in sorted(os.listdir(cells_root)):
                cap = os.path.join(cells_root, cid, "capture.log")
                if not os.path.isfile(cap):
                    continue
                with open(cap, encoding="utf-8") as f:
                    s = f.read()
                s = s.replace("wall_utc_ms=1100000", "wall_utc_ms=not-an-int", 1)
                with open(cap, "w", encoding="utf-8") as f:
                    f.write(s)
                break
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("wall_utc_ms" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


# ── audit ROUND-12 adversarial regression fixtures ────────────
#
# Each pins one of the false-GREEN paths the architect exploited.

class AuditR12_smoke_all_dedup_duplicate_forces_red(unittest.TestCase):
    """Audit ROUND-12 P0-#3: smoke verifier used to accept a chain
    where every recipient_deliver_received carried
    dedup_gate=duplicate (0 fresh). Fix: exactly-one-fresh check
    now runs on smoke.log too."""

    def test_all_fresh_flipped_to_duplicate(self):
        def mutate(d):
            p = os.path.join(d, "smoke.log")
            with open(p, encoding="utf-8") as f: s = f.read()
            s = s.replace("dedup_gate=fresh", "dedup_gate=duplicate")
            with open(p, "w", encoding="utf-8") as f: f.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR12_smoke_dispatched_false_forces_red(unittest.TestCase):
    """Audit ROUND-12 P0-#3: smoke used to accept
    sender_transport_decision with dispatched=false as long as the
    event NAME was present."""

    def test_dispatched_flipped_to_false(self):
        def mutate(d):
            p = os.path.join(d, "smoke.log")
            with open(p, encoding="utf-8") as f: s = f.read()
            s = s.replace("dispatched=true", "dispatched=false")
            with open(p, "w", encoding="utf-8") as f: f.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR12_smoke_cid_mismatch_forces_red(unittest.TestCase):
    """Audit ROUND-12 P0-#3: JSON verdict claims one CID but the
    strict smoke.log rebuild finds the chain under a different CID
    → schema violation."""

    def test_json_cid_diverges_from_log_cid(self):
        def mutate(d):
            with open(os.path.join(d, "smoke_verdict.json"), encoding="utf-8") as f:
                v = json.load(f)
            v["p2e_cid"] = "wrong-cid-that-does-not-exist"
            with open(os.path.join(d, "smoke_verdict.json"), "w", encoding="utf-8") as f:
                json.dump(v, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR12_cell_events_missing_cell_id_forces_red(unittest.TestCase):
    """Audit ROUND-12 P0-#5: cell verifier used to tolerate events
    without cell_id via `in (None, cell_id)`. Strip cell_id from cell
    events → RED."""

    def test_strip_cell_id_from_cell_events(self):
        def mutate(d):
            import re
            # Strip cell_id=... (with any trailing whitespace or EOL)
            # from EVERY cell capture — the audit exploit is that
            # missing cell_id previously fell into the `in (None, cell_id)`
            # tolerance clause and inflated coverage.
            for root, _dirs, files in os.walk(os.path.join(d, "matrix_cells")):
                for f in files:
                    if not f.endswith(".log"):
                        continue
                    p = os.path.join(root, f)
                    with open(p, encoding="utf-8") as fh:
                        s = fh.read()
                    s = re.sub(r"\bcell_id=\S+\s?", "", s)
                    with open(p, "w", encoding="utf-8") as fh:
                        fh.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR12_cell_dispatched_false_forces_red(unittest.TestCase):
    """Audit ROUND-12 P0-#5: `sender_transport_decision` with
    dispatched=false but correct emitter+route used to pass via
    separate `any(...)` walks. Coherent single-event check now
    enforces all three on the same event."""

    def test_transport_decision_dispatched_false(self):
        def mutate(d):
            import re
            cells_root = os.path.join(d, "matrix_cells")
            first_cell = sorted(os.listdir(cells_root))[0]
            cap = os.path.join(cells_root, first_cell, "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            # Only in sender_transport_decision events for one CID.
            s = re.sub(
                r"(event=sender_transport_decision[^\n]*)dispatched=true",
                r"\1dispatched=false",
                s, count=1,
            )
            with open(cap, "w", encoding="utf-8") as f: f.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR12_checkpoint_repeating_labels_forces_red(unittest.TestCase):
    """Audit ROUND-12 P0-#4: labels used to be free-form and could
    repeat. Canonical label sequence check now rejects duplicates."""

    def test_all_labels_identical(self):
        def mutate(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            for e in arr:
                e["label"] = "pre-cell:wss.p2e.after-connect"
            with open(p, "w", encoding="utf-8") as f: json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR12_checkpoint_wrong_operator_forces_red(unittest.TestCase):
    """Audit ROUND-12 P0-#4: checkpoint accepted mismatched observed
    operator numeric — non-null was sufficient. Values must match
    PROFILE."""

    def test_operator_numeric_mismatch(self):
        def mutate(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            arr[0]["observed"]["phone_active_data_sim_operator_numeric"] = "99999"
            with open(p, "w", encoding="utf-8") as f: json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR12_checkpoint_wifi_on_forces_red(unittest.TestCase):
    """Audit ROUND-12 P0-#4: Wi-Fi ON, mobile-data OFF, prekey=false
    used to pass structurally as long as fields were non-null. Now
    the values themselves are checked."""

    def test_wifi_on(self):
        def mutate(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            arr[0]["observed"]["phone_wifi_enabled"] = True
            with open(p, "w", encoding="utf-8") as f: json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR12_sha_missing_cell_capture_forces_red(unittest.TestCase):
    """Audit ROUND-12 P1-#6: SHA manifest without a line for a
    matrix_cells capture.log used to pass — the MIN_HASHED whitelist
    only covered top-level files."""

    def test_strip_one_cell_capture_line(self):
        def mutate(d):
            p = os.path.join(d, "SHA256SUMS.txt")
            with open(p, encoding="utf-8") as f: lines = f.readlines()
            # Drop the first matrix_cells capture.log line.
            out = []
            dropped = False
            for l in lines:
                if not dropped and "matrix_cells/" in l and l.rstrip().endswith("capture.log"):
                    dropped = True
                    continue
                out.append(l)
            with open(p, "w", encoding="utf-8") as f: f.writelines(out)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("matrix_cells" in i and "no line" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


# ── audit ROUND-13 adversarial regression fixtures ────────────

class AuditR13_outer_transport_not_direct_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-1: `outer_transport=reality` (or any
    value other than `direct`) MUST force integrity RED. Was GREEN
    before because outer_transport wasn't checked."""
    def test_outer_reality(self):
        def mutate(d):
            for root, _dirs, files in os.walk(os.path.join(d, "matrix_cells")):
                for f in files:
                    if not f.endswith(".log"): continue
                    p = os.path.join(root, f)
                    with open(p, encoding="utf-8") as fh: s = fh.read()
                    s = s.replace("outer_transport=direct", "outer_transport=reality")
                    with open(p, "w", encoding="utf-8") as fh: fh.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_all_sequence_1_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-1: sequences MUST cover {1..5} exactly.
    All-`sequence=1` was GREEN before."""
    def test_all_sequences_collapsed(self):
        def mutate(d):
            import re
            for root, _dirs, files in os.walk(os.path.join(d, "matrix_cells")):
                for f in files:
                    if not f.endswith(".log"): continue
                    p = os.path.join(root, f)
                    with open(p, encoding="utf-8") as fh: s = fh.read()
                    s = re.sub(r"sequence=[0-9]+", "sequence=1", s)
                    with open(p, "w", encoding="utf-8") as fh: fh.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_wrong_role_on_dispatched_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-1: `diagnostic_send_dispatched` with
    wrong role/emitter was GREEN. Role MUST be `matrix`."""
    def test_dispatched_role_sender_instead_of_matrix(self):
        def mutate(d):
            import re
            for root, _dirs, files in os.walk(os.path.join(d, "matrix_cells")):
                for f in files:
                    if not f.endswith(".log"): continue
                    p = os.path.join(root, f)
                    with open(p, encoding="utf-8") as fh: s = fh.read()
                    # Field order in log format: event=X run_id=Y
                    # correlation_id=Z role=W ... — regex-flip role on
                    # diagnostic_send_dispatched lines only.
                    s = re.sub(
                        r"(event=diagnostic_send_dispatched[^\n]*?)role=matrix",
                        r"\1role=sender",
                        s,
                    )
                    with open(p, "w", encoding="utf-8") as fh: fh.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_sender_events_role_matrix_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-1: sender events with role=matrix were
    GREEN. role MUST be `sender` for sender-side events."""
    def test_transport_decision_role_matrix(self):
        def mutate(d):
            import re
            for root, _dirs, files in os.walk(os.path.join(d, "matrix_cells")):
                for f in files:
                    if not f.endswith(".log"): continue
                    p = os.path.join(root, f)
                    with open(p, encoding="utf-8") as fh: s = fh.read()
                    s = re.sub(
                        r"(event=sender_transport_decision[^\n]*?)role=sender",
                        r"\1role=matrix",
                        s,
                    )
                    with open(p, "w", encoding="utf-8") as fh: fh.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_contradictory_transport_decision_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-1: a second contradictory
    sender_transport_decision (different outer/inner/dispatched) was
    GREEN because the coherent-single-event check accepted the first
    coherent one and ignored the contradictory second."""
    def test_second_contradictory_decision(self):
        def mutate(d):
            import re
            # Duplicate the first sender_transport_decision line with
            # dispatched=false + outer_transport=reality — same CID.
            cells_root = os.path.join(d, "matrix_cells")
            for cid_dir in sorted(os.listdir(cells_root)):
                cap = os.path.join(cells_root, cid_dir, "capture.log")
                if not os.path.isfile(cap): continue
                with open(cap, encoding="utf-8") as fh: s = fh.read()
                m = re.search(
                    r"^(.*event=sender_transport_decision[^\n]*)$",
                    s, re.MULTILINE,
                )
                if not m: continue
                line = m.group(1)
                bad = (line.replace("dispatched=true", "dispatched=false")
                            .replace("outer_transport=direct", "outer_transport=reality"))
                with open(cap, "a", encoding="utf-8") as fh:
                    fh.write("\n" + bad + "\n")
                break  # one contradiction is enough
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_opposite_route_return_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-1: REST-return inside a WSS cell was
    GREEN because verifier only sought the expected name and ignored
    the presence of the opposite name."""
    def test_rest_return_in_wss_cell(self):
        def mutate(d):
            # In the first WSS cell, append a rest-return line under
            # the SAME CID (and same run_id) so it survives the
            # run_id/cell_id filter and reaches the opposite-route
            # check.
            import re
            with open(os.path.join(d, "PROFILE.json"), encoding="utf-8") as f:
                run_id = json.load(f)["run_id"]
            cells_root = os.path.join(d, "matrix_cells")
            wss_cell = "wss.p2e.after-connect"
            cap = os.path.join(cells_root, wss_cell, "capture.log")
            with open(cap, encoding="utf-8") as fh: s = fh.read()
            m = re.search(r"correlation_id=(\S+)", s)
            if not m: return
            cid = m.group(1)
            with open(cap, "a", encoding="utf-8") as fh:
                fh.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=sender_rest_post_completed run_id={run_id} "
                    f"correlation_id={cid} role=sender emitter_id=phone "
                    f"cell_id={wss_cell} dispatched=true "
                    f"wall_utc_ms=1234567 monotonic_ms=1234567\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_same_cid_two_cells_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-1: same CID in two cells was GREEN. CIDs
    MUST be globally unique."""
    def test_shared_cid_across_cells(self):
        def mutate(d):
            # Copy one CID's diagnostic_send_dispatched line from
            # wss.p2e.after-connect to wss.e2p.after-idle so the
            # same CID appears in two cells' dispatch events.
            import re
            cells_root = os.path.join(d, "matrix_cells")
            src = os.path.join(cells_root, "wss.p2e.after-connect", "capture.log")
            dst = os.path.join(cells_root, "wss.e2p.after-idle", "capture.log")
            with open(src, encoding="utf-8") as f: s = f.read()
            m = re.search(r"^.*event=diagnostic_send_dispatched[^\n]*\n", s, re.MULTILINE)
            if not m: return
            leaked = m.group(0).replace("cell_id=wss.p2e.after-connect",
                                        "cell_id=wss.e2p.after-idle")
            with open(dst, "a", encoding="utf-8") as f:
                f.write(leaked)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("globally unique" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR13_empty_apk_sha_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-2: empty PROFILE.apk_sha256 was GREEN."""
    def test_empty_apk_sha(self):
        def mutate(d):
            p = os.path.join(d, "PROFILE.json")
            with open(p, encoding="utf-8") as f: pj = json.load(f)
            pj["apk_sha256"] = ""
            with open(p, "w", encoding="utf-8") as f: json.dump(pj, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_manual_confirmations_false_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-2: both manual confirmations = false +
    wrong confirm_string was GREEN."""
    def test_both_manual_confirmations_false(self):
        def mutate(d):
            p = os.path.join(d, "PROFILE.json")
            with open(p, encoding="utf-8") as f: pj = json.load(f)
            pj["operator_manual_confirmations"]["auto_data_switching_off"] = False
            pj["operator_manual_confirmations"]["other_sim_data_disabled"] = False
            pj["confirm_string"] = "CONFIRM-WRONG"
            with open(p, "w", encoding="utf-8") as f: json.dump(pj, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_paired_count_type_wrong_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-2: paired_count=true (bool instead of
    int) and subscription_id as list were GREEN."""
    def test_paired_count_bool_and_sub_id_list(self):
        def mutate(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            arr[0]["observed"]["paired_count_phone"] = True
            arr[0]["observed"]["phone_active_data_subscription_id"] = ["1", "2"]
            with open(p, "w", encoding="utf-8") as f: json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_checkpoint_bad_hmac_af_inet6_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-2: equal_host_emu=false, HMAC null/bad,
    AF_INET6 were GREEN at checkpoint-entry level."""
    def test_bad_hmac_and_af_inet6(self):
        def mutate(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            arr[0]["host_hmac_fp_hex"] = None
            arr[0]["emu_hmac_fp_hex"] = "not-hex"
            arr[0]["equal_host_emu"] = False
            arr[0]["address_family_host"] = "AF_INET6"
            with open(p, "w", encoding="utf-8") as f: json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_denylist_smoke_marker_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-3: `unresolved_120s_marker` in smoke.log
    was GREEN because denylist only ran on matrix cells."""
    def test_marker_in_smoke_log(self):
        def mutate(d):
            sl = os.path.join(d, "smoke.log")
            with open(sl, "a", encoding="utf-8") as f:
                f.write("08-14 12:00:00.000  1234  1234 I WSS_DIAG: unresolved_120s_marker at some point\n")
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_denylist_text_payload_in_capture_forces_red(unittest.TestCase):
    """Architect ROUND-13 P0-3: raw `text=SECRET_PAYLOAD` in a matrix
    capture was GREEN because no text-level scan existed."""
    def test_text_field_in_matrix_capture(self):
        def mutate(d):
            cells_root = os.path.join(d, "matrix_cells")
            for cid_dir in sorted(os.listdir(cells_root)):
                cap = os.path.join(cells_root, cid_dir, "capture.log")
                if not os.path.isfile(cap): continue
                with open(cap, "a", encoding="utf-8") as f:
                    f.write("08-14 12:00:00.000 I WSS_DIAG: event=noise text=SECRET_PAYLOAD wall_utc_ms=1\n")
                break
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR13_integrity_red_yields_not_evaluable(unittest.TestCase):
    """Architect ROUND-13 P0-4 + contract §7 R18: integrity failure
    MUST classify as NOT_EVALUABLE, not RED."""
    def test_integrity_failure_forces_not_evaluable(self):
        def mutate(d):
            # Break integrity via smoke.log denylist hit (proven above)
            sl = os.path.join(d, "smoke.log")
            with open(sl, "a", encoding="utf-8") as f:
                f.write("08-14 12:00:00.000 I WSS_DIAG: unresolved_120s_marker\n")
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")


class AuditR13_sha_absolute_path_forces_red(unittest.TestCase):
    """Architect ROUND-13 P1: SHA line with absolute path or `..`
    was accepted (hashing /etc/hosts stayed GREEN)."""
    def test_absolute_path_line(self):
        def mutate(d):
            p = os.path.join(d, "SHA256SUMS.txt")
            with open(p, "a", encoding="utf-8") as f:
                f.write("00000000000000000000000000000000000000000000000000000000cafebabe  /etc/hosts\n")
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("absolute path" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)

    def test_dotdot_traversal_line(self):
        def mutate(d):
            p = os.path.join(d, "SHA256SUMS.txt")
            with open(p, "a", encoding="utf-8") as f:
                f.write("00000000000000000000000000000000000000000000000000000000cafebabe  ../PROFILE.json\n")
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


# ── audit ROUND-14 adversarial regression fixtures ────────────

class AuditR14_route_return_missing_inner_route(unittest.TestCase):
    """Architect ROUND-14 P0-1: stripping `inner_route=wss` from every
    sender_wss_send_returned in a WSS cell was GREEN. Now must be
    RED because route-return REQUIRES inner_route present-and-matching."""
    def test_strip_inner_route_from_wss_returns(self):
        def mutate(d):
            import re
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            s = re.sub(
                r"(event=sender_wss_send_returned[^\n]*?)inner_route=wss ?",
                r"\1",
                s,
            )
            with open(cap, "w", encoding="utf-8") as f: f.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")


class AuditR14_rest_return_relay_acceptance_rejected(unittest.TestCase):
    """Architect ROUND-14 P0-1: replacing every REST-return
    `relay_acceptance=accepted` with `rejected` was GREEN. Now must be
    RED because REST-return requires relay_acceptance in
    {accepted, duplicate}."""
    def test_flip_relay_acceptance_to_rejected(self):
        def mutate(d):
            cap = os.path.join(d, "matrix_cells", "rest.p2e.control", "capture.log")
            if not os.path.isfile(cap): return
            with open(cap, encoding="utf-8") as f: s = f.read()
            s = s.replace("relay_acceptance=accepted", "relay_acceptance=rejected")
            with open(cap, "w", encoding="utf-8") as f: f.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR14_appended_wrong_role_recipient(unittest.TestCase):
    """Architect ROUND-14 P0-2: an APPENDED
    `recipient_message_persisted` with `role=sender` (while the valid
    one exists) was GREEN — existential check let the malformed extra
    hide. Now must be RED because every event under a CID has its
    role validated per event-class."""
    def test_append_recipient_event_with_role_sender(self):
        def mutate(d):
            import re, json as _j
            with open(os.path.join(d, "PROFILE.json"), encoding="utf-8") as f:
                run_id = _j.load(f)["run_id"]
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            m = re.search(r"correlation_id=(\S+)", s)
            if not m: return
            cid = m.group(1)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=recipient_message_persisted run_id={run_id} "
                    f"correlation_id={cid} role=sender emitter_id=emulator "
                    f"cell_id=wss.p2e.after-connect wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR14_appended_wrong_runid_relayack(unittest.TestCase):
    """Architect ROUND-14 P0-2: an appended same-CID
    `sender_relay_ack_received` with `run_id=run-stale-injected` was
    GREEN — the filter dropped it silently. Now must be RED because
    wrong run_id is flagged as an integrity issue."""
    def test_append_relay_ack_with_stale_run_id(self):
        def mutate(d):
            import re
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            m = re.search(r"correlation_id=(\S+)", s)
            if not m: return
            cid = m.group(1)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=sender_relay_ack_received run_id=run-stale-injected "
                    f"correlation_id={cid} role=sender emitter_id=phone "
                    f"cell_id=wss.p2e.after-connect wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR14_denylist_hit_in_json(unittest.TestCase):
    """Architect ROUND-14 P0-3: `text=SECRET_PAYLOAD` injected into
    matrix_verdict.json was GREEN because the denylist scan skipped
    .json files. Now must be RED because the scan covers JSON too."""
    def test_secret_payload_in_matrix_verdict_json(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            mv["audit_injected_secret"] = "text=SECRET_PAYLOAD"
            with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("matrix_verdict.json" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR14_smoke_second_fresh_cid(unittest.TestCase):
    """Architect ROUND-14 P0-4: duplicating the entire p2e chain
    under a second CID (with its own dedup_gate=fresh) was GREEN
    because _direction_ok returned after first valid CID. Now must
    be RED because exactly one qualifying CID + one fresh delivery
    per direction is enforced."""
    def test_second_full_p2e_chain_with_fresh(self):
        def mutate(d):
            # Duplicate every smoke-p2e line under a fresh CID.
            sl = os.path.join(d, "smoke.log")
            with open(sl, encoding="utf-8") as f: s = f.read()
            dup = s.replace("correlation_id=smoke-p2e", "correlation_id=smoke-p2e-DUP")
            # Keep only the p2e lines from the dup (drop e2p noise).
            dup_lines = [l for l in dup.splitlines(keepends=True)
                         if "correlation_id=smoke-p2e-DUP" in l]
            with open(sl, "a", encoding="utf-8") as f:
                f.writelines(dup_lines)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR14_abort_reason_list_no_crash(unittest.TestCase):
    """Architect ROUND-14 P1-5: `abort_reason=[]` used to crash the
    verifier with `TypeError: unhashable type: 'list'`. Must now
    yield NOT_EVALUABLE, no crash."""
    def test_abort_reason_list(self):
        def mutate(d):
            p = os.path.join(d, "matrix_completion.json")
            with open(p, encoding="utf-8") as f: mc = json.load(f)
            mc["abort_reason"] = []
            with open(p, "w", encoding="utf-8") as f: json.dump(mc, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            # The critical assertion: this must not raise.
            try:
                rep = ve3.verify_profile_dir(d)
            except Exception as e:
                self.fail(f"verifier crashed on abort_reason=[]: {type(e).__name__}: {e}")
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")


class AuditR14_profile_json_is_list_no_crash(unittest.TestCase):
    """Architect ROUND-14 P1-5 generalized: PROFILE.json that is a
    JSON list (not object) must not blow up on `.keys()` — must
    classify as NOT_EVALUABLE."""
    def test_profile_json_top_level_list(self):
        def mutate(d):
            p = os.path.join(d, "PROFILE.json")
            with open(p, "w", encoding="utf-8") as f:
                json.dump(["totally", "wrong", "type"], f)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            try:
                rep = ve3.verify_profile_dir(d)
            except Exception as e:
                self.fail(f"verifier crashed on list-typed PROFILE.json: {type(e).__name__}: {e}")
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")


# ── audit ROUND-15 adversarial regression fixtures ────────────

def _profile_run_id(d):
    with open(os.path.join(d, "PROFILE.json"), encoding="utf-8") as f:
        return json.load(f)["run_id"]


class AuditR15_watchdog_wrong_role(unittest.TestCase):
    """Architect ROUND-15 P0-1: appended
    `sender_ack_watchdog_requeued role=matrix emitter_id=phone`
    under an existing CID was GREEN because the local sender-set
    reduced from the imported SENDER_EVENTS omitted this event."""
    def test_watchdog_requeued_wrong_role(self):
        def mutate(d):
            import re
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            m = re.search(r"correlation_id=(\S+)", s)
            if not m: return
            cid = m.group(1)
            run_id = _profile_run_id(d)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=sender_ack_watchdog_requeued run_id={run_id} "
                    f"correlation_id={cid} role=matrix emitter_id=phone "
                    f"cell_id=wss.p2e.after-connect wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR15_command_completed_wrong_role(unittest.TestCase):
    """Architect ROUND-15 P0-1: appended
    `diagnostic_send_command_completed role=sender result=handled`
    under an existing CID was GREEN because the local matrix-set
    omitted this event."""
    def test_command_completed_role_sender(self):
        def mutate(d):
            import re
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            m = re.search(r"correlation_id=(\S+)", s)
            if not m: return
            cid = m.group(1)
            run_id = _profile_run_id(d)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=diagnostic_send_command_completed run_id={run_id} "
                    f"correlation_id={cid} role=sender emitter_id=phone "
                    f"cell_id=wss.p2e.after-connect result=handled "
                    f"wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR15_orphan_cid(unittest.TestCase):
    """Architect ROUND-15 P0-1: appended a correctly-named
    `sender_relay_ack_received` under a NEW CID with no matching
    `diagnostic_send_dispatched` was GREEN. Every CID that carries
    any event MUST have a dispatched anchor."""
    def test_orphan_cid_without_dispatched(self):
        def mutate(d):
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            run_id = _profile_run_id(d)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=sender_relay_ack_received run_id={run_id} "
                    f"correlation_id=cid-orphan-injected role=sender "
                    f"emitter_id=phone cell_id=wss.p2e.after-connect "
                    f"wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("orphan CID" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR15_smoke_extra_bare_dispatched(unittest.TestCase):
    """Architect ROUND-15 P0-2: appended second bare
    `diagnostic_send_dispatched` under a new CID (no route/delivery
    continuation) was GREEN because _direction_ok counted only fully-
    qualifying chains and discarded non-qualifying candidates."""
    def test_extra_bare_dispatched_new_cid(self):
        def mutate(d):
            with open(os.path.join(d, "smoke_verdict.json"),
                      encoding="utf-8") as f:
                smoke_run_id = json.load(f)["smoke_run_id"]
            sl = os.path.join(d, "smoke.log")
            with open(sl, "a", encoding="utf-8") as f:
                f.write(
                    "08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=diagnostic_send_dispatched run_id={smoke_run_id} "
                    "correlation_id=smoke-p2e-EXTRA role=matrix "
                    "emitter_id=phone cell_id=wss.p2e.after-connect "
                    "sequence=1 wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR15_json_unicode_escape_bypass(unittest.TestCase):
    """Architect ROUND-15 P0-3: `text=SECRET_PAYLOAD` written to a
    JSON with the `x` in `text` Unicode-escaped (`\\u0074`) bypassed
    the raw-line scan but decodes to `text=SECRET_PAYLOAD` after
    JSON parsing."""
    def test_unicode_escape_in_matrix_verdict(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            # Write JSON that will parse to the string `text=SECRET_PAYLOAD`
            # via Unicode-escaped `t`.
            with open(p, "w", encoding="utf-8") as f:
                # json.dump would emit literal chars; write raw JSON
                # with the escape sequence intact.
                mv["audit_injected_secret"] = "__PLACEHOLDER__"
                s = json.dumps(mv, sort_keys=True, indent=2)
                s = s.replace(
                    '"__PLACEHOLDER__"',
                    '"\\u0074\\u0065\\u0078\\u0074=SECRET_PAYLOAD"',
                )
                f.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("decoded string" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR15_unknown_extension_bin_bypass(unittest.TestCase):
    """Architect ROUND-15 P0-3: an added `audit-extra.bin` with
    `text=SECRET_PAYLOAD` was GREEN because the scan filtered by
    extension. Closed evidence-file inventory refuses unknown files
    outright."""
    def test_extra_bin_at_top_level(self):
        def mutate(d):
            p = os.path.join(d, "audit-extra.bin")
            with open(p, "w", encoding="utf-8") as f:
                f.write("text=SECRET_PAYLOAD\n")
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("unknown evidence file" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR15_egress_top_level_list_no_crash(unittest.TestCase):
    """Architect ROUND-15 P1-1: `EGRESS_FINGERPRINT.json=[]` crashed
    the verifier with AttributeError. Must now yield NOT_EVALUABLE."""
    def test_egress_json_top_level_list(self):
        def mutate(d):
            with open(os.path.join(d, "EGRESS_FINGERPRINT.json"), "w", encoding="utf-8") as f:
                json.dump([], f)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            try:
                rep = ve3.verify_profile_dir(d)
            except Exception as e:
                self.fail(f"verifier crashed on EGRESS_FINGERPRINT.json=[]: {type(e).__name__}: {e}")
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")


class AuditR15_other_json_top_level_list_no_crash(unittest.TestCase):
    """Audit ROUND-15 P1-1 generalized: every load-bearing JSON
    file must survive a top-level list without crashing."""
    def test_network_profile_top_level_list(self):
        def mutate(d):
            with open(os.path.join(d, "network_profile.json"), "w", encoding="utf-8") as f:
                json.dump([], f)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            try:
                rep = ve3.verify_profile_dir(d)
            except Exception as e:
                self.fail(f"verifier crashed on network_profile.json=[]: {type(e).__name__}: {e}")
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

    def test_profile_state_top_level_list(self):
        def mutate(d):
            with open(os.path.join(d, "PROFILE_STATE.json"), "w", encoding="utf-8") as f:
                json.dump([], f)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            try:
                rep = ve3.verify_profile_dir(d)
            except Exception as e:
                self.fail(f"verifier crashed on PROFILE_STATE.json=[]: {type(e).__name__}: {e}")
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

    def test_smoke_verdict_top_level_list(self):
        def mutate(d):
            with open(os.path.join(d, "smoke_verdict.json"), "w", encoding="utf-8") as f:
                json.dump([], f)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            try:
                rep = ve3.verify_profile_dir(d)
            except Exception as e:
                self.fail(f"verifier crashed on smoke_verdict.json=[]: {type(e).__name__}: {e}")
            self.assertFalse(rep.integrity_ok)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

    def test_checkpoint_observed_top_level_list(self):
        def mutate(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            arr[0]["observed"] = []
            with open(p, "w", encoding="utf-8") as f: json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            try:
                rep = ve3.verify_profile_dir(d)
            except Exception as e:
                self.fail(f"verifier crashed on checkpoint observed=[]: {type(e).__name__}: {e}")
            self.assertFalse(rep.integrity_ok)


# ── audit ROUND-16 adversarial regression fixtures ────────────

class AuditR16_prekey_deferred_on_delivered_cid(unittest.TestCase):
    """Architect ROUND-16 P0-1: appended `sender_prekey_deferred` under
    an existing Delivered CID was GREEN. Incompatible states."""
    def test_prekey_deferred_on_delivered(self):
        def mutate(d):
            import re
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            m = re.search(r"correlation_id=(\S+)", s)
            if not m: return
            cid = m.group(1)
            run_id = _profile_run_id(d)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=sender_prekey_deferred run_id={run_id} "
                    f"correlation_id={cid} role=sender emitter_id=phone "
                    f"cell_id=wss.p2e.after-connect wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR16_command_completed_invalid_result(unittest.TestCase):
    """Architect ROUND-16 P0-1: command_completed.result=bogus was
    GREEN because result was never enum-checked."""
    def test_bogus_result(self):
        def mutate(d):
            import re
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            m = re.search(r"correlation_id=(\S+)", s)
            if not m: return
            cid = m.group(1)
            run_id = _profile_run_id(d)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=diagnostic_send_command_completed run_id={run_id} "
                    f"correlation_id={cid} role=matrix emitter_id=phone "
                    f"cell_id=wss.p2e.after-connect result=bogus "
                    f"wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR16_missing_attempt_started(unittest.TestCase):
    """Architect ROUND-16 P0-1: dropping `sender_send_attempt_started`
    while keeping `sender_enqueue` was GREEN because the code accepted
    either. attempt_started is the production entrypoint per §3.3."""
    def test_strip_attempt_started_keep_enqueue(self):
        def mutate(d):
            import re
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            s = re.sub(r".*event=sender_send_attempt_started.*\n", "", s)
            with open(cap, "w", encoding="utf-8") as f: f.write(s)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR16_smoke_unknown_event_no_cid(unittest.TestCase):
    """Architect ROUND-16 P0-2: unknown event without CID in smoke.log
    was GREEN because the smoke path only inspected CID-bearing
    events."""
    def test_unknown_event_added(self):
        def mutate(d):
            with open(os.path.join(d, "smoke_verdict.json"),
                      encoding="utf-8") as f:
                smoke_run_id = json.load(f)["smoke_run_id"]
            sl = os.path.join(d, "smoke.log")
            with open(sl, "a", encoding="utf-8") as f:
                f.write(
                    "08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    "event=some_unknown_smoke_event role=matrix "
                    f"run_id={smoke_run_id} "
                    "emitter_id=phone wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR16_smoke_wrong_role_no_cid(unittest.TestCase):
    """Architect ROUND-16 P0-2: accepted event with wrong role and no
    CID stayed GREEN in smoke."""
    def test_recipient_event_role_sender_no_cid(self):
        def mutate(d):
            with open(os.path.join(d, "smoke_verdict.json"),
                      encoding="utf-8") as f:
                smoke_run_id = json.load(f)["smoke_run_id"]
            sl = os.path.join(d, "smoke.log")
            with open(sl, "a", encoding="utf-8") as f:
                f.write(
                    "08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    "event=recipient_message_persisted role=sender "
                    f"run_id={smoke_run_id} "
                    "emitter_id=phone cell_id=wss.p2e.after-connect "
                    "wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR16_smoke_extra_cid_unknown_cell(unittest.TestCase):
    """Architect ROUND-16 P0-2: added CID under non-canonical
    cell_id in smoke.log stayed GREEN."""
    def test_extra_cid_non_canonical_cell(self):
        def mutate(d):
            with open(os.path.join(d, "smoke_verdict.json"),
                      encoding="utf-8") as f:
                smoke_run_id = json.load(f)["smoke_run_id"]
            sl = os.path.join(d, "smoke.log")
            with open(sl, "a", encoding="utf-8") as f:
                f.write(
                    "08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=diagnostic_send_dispatched run_id={smoke_run_id} "
                    "correlation_id=extra-cid role=matrix emitter_id=phone "
                    "cell_id=wss.p2e.bg-fg sequence=1 "
                    "wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR16_cell_verdict_top_level_list(unittest.TestCase):
    """Architect ROUND-16 P0-3: `cell_verdict.json=[]` was GREEN
    because the loader returned None silently. Now malformed cell
    verdicts force RED."""
    def test_cell_verdict_list(self):
        def mutate(d):
            cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "cell_verdict.json")
            with open(cell, "w", encoding="utf-8") as f:
                json.dump([], f)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR16_cell_verdict_malformed_json(unittest.TestCase):
    """Architect ROUND-16 P0-3: malformed cell_verdict.json stayed
    GREEN."""
    def test_cell_verdict_broken(self):
        def mutate(d):
            cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "cell_verdict.json")
            with open(cell, "w", encoding="utf-8") as f:
                f.write("{not a valid json at all")
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR16_malformed_retry_attempt_forces_not_evaluable(unittest.TestCase):
    """Architect ROUND-16 P0-3: adding a second, malformed retry
    attempt directory hid it from lineage. All 8 rows stayed GREEN
    with attempts=1."""
    def test_second_attempt_dir_malformed(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
            )
            # Create a SECOND attempt directory for the same profile
            # id with malformed PROFILE.json.
            parent = os.path.dirname(d)
            malformed_dir = os.path.join(
                parent, "yota-phone-off-host-on-20260814T093113Z"
            )
            os.makedirs(malformed_dir)
            with open(os.path.join(malformed_dir, "PROFILE.json"), "w", encoding="utf-8") as f:
                f.write("{ garbage ")
            report = ve3.verify_evidence_root(parent)
            # The profile row for yota-phone-off-host-on MUST force
            # NOT_EVALUABLE because a malformed attempt exists.
            row = next(r for r in report.rows if r.profile_id == "yota-phone-off-host-on")
            self.assertFalse(row.integrity_ok, msg=row.integrity_issues)
            self.assertEqual(row.product_outcome, "NOT_EVALUABLE")


class AuditR16_json_text_field_payload(unittest.TestCase):
    """Architect ROUND-16 P0-4: `"text":"SECRET_PAYLOAD"` (JSON key
    literally named `text`) bypassed the `\\btext=` regex because
    the key is decoded as `text` without an equals sign."""
    def test_json_text_key_in_matrix_verdict(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            mv["text"] = "SECRET_PAYLOAD"
            with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("banned JSON key" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR16_json_raw_ip(unittest.TestCase):
    """Architect ROUND-16 P0-4: raw IP in JSON was GREEN — denylist
    lacked IP regex."""
    def test_ipv4_in_matrix_verdict(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            mv["relay_ip"] = "203.0.113.42"
            with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR16_non_canonical_cell_dir(unittest.TestCase):
    """Architect ROUND-16 P0-4: `matrix_cells/<non-canonical>/capture.log`
    was GREEN because inventory only checked file name not cell name."""
    def test_extra_non_canonical_cell_directory(self):
        def mutate(d):
            extra = os.path.join(d, "matrix_cells", "wss.p2e.nonexistent")
            os.makedirs(extra)
            with open(os.path.join(extra, "capture.log"), "w", encoding="utf-8") as f:
                f.write("dummy\n")
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("unknown cell directory" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


# ── audit ROUND-17 adversarial regression fixtures ────────────

class AuditR17_cell_verdict_empty_object(unittest.TestCase):
    """Architect ROUND-17 P0-1: empty {} in cell_verdict.json stayed
    GREEN because the loader only proved the JSON was a dict."""
    def test_cell_verdict_empty_object(self):
        def mutate(d):
            cell = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "cell_verdict.json")
            with open(cell, "w", encoding="utf-8") as f: json.dump({}, f)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_matrix_verdict_contradicts(unittest.TestCase):
    """Architect ROUND-17 P0-1: matrix_verdict claiming integrity=RED,
    delivered=0, unresolved=40, cells=[] was GREEN because the
    verifier never loaded its content."""
    def test_matrix_verdict_contradicts_verifier(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, "w", encoding="utf-8") as f:
                json.dump({
                    "evidence_integrity": "RED",
                    "product_outcome": "NOT_EVALUABLE",
                    "delivered_count": 0,
                    "unresolved_count": 40,
                    "cells": [],
                }, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_denylist_session_token(unittest.TestCase):
    """Architect ROUND-17 P0-2: session_token in JSON stayed GREEN."""
    def test_session_token_in_matrix_verdict(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            mv["session_token"] = "abc.def.ghi"
            with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_denylist_vpn_provider(unittest.TestCase):
    """Architect ROUND-17 P0-2: vpn_provider in JSON stayed GREEN."""
    def test_vpn_provider_in_matrix_verdict(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            mv["vpn_provider"] = "ExampleVPN Inc"
            with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_denylist_username_and_identity_key(unittest.TestCase):
    """Architect ROUND-17 P0-2: username + 64-char identity key
    in JSON stayed GREEN."""
    def test_username_and_identity_key(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            mv["username"] = "alice"
            mv["identity_key"] = "d" * 64
            with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_denylist_ipv6_compressed(unittest.TestCase):
    """Architect ROUND-17 P0-2: IPv6 `::1`, `::` and `2001:db8::`
    stayed GREEN before broader IPv6 patterns."""
    def _plant(self, d, addr):
        p = os.path.join(d, "matrix_verdict.json")
        with open(p, encoding="utf-8") as f: mv = json.load(f)
        mv["notes"] = f"reached {addr} via test route"
        with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)

    def test_ipv6_loopback(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=lambda dd: self._plant(dd, "::1"),
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)

    def test_ipv6_trailing_compressed(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=lambda dd: self._plant(dd, "2001:db8::"),
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_smoke_foreign_run(unittest.TestCase):
    """Historical smoke records are ignored unless they collide with
    an identity claimed by the current smoke verdict."""
    def test_foreign_run_event(self):
        def mutate(d):
            with open(os.path.join(d, "smoke_verdict.json"),
                      encoding="utf-8") as f:
                current_cid = json.load(f)["p2e_cid"]
            sl = os.path.join(d, "smoke.log")
            with open(sl, "a", encoding="utf-8") as f:
                f.write(
                    "08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    "event=diagnostic_send_dispatched run_id=foreign-run-999 "
                    f"correlation_id={current_cid} role=matrix emitter_id=phone "
                    "cell_id=wss.p2e.after-connect sequence=2 "
                    "wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)

    def test_unrelated_historical_event_is_not_current_evidence(self):
        def mutate(d):
            sl = os.path.join(d, "smoke.log")
            with open(sl, "a", encoding="utf-8") as f:
                f.write(
                    "08-14 11:59:59.000  1234  1234 I WSS_DIAG: "
                    "event=diagnostic_send_dispatched run_id=historical-run "
                    "correlation_id=historical-cid role=matrix emitter_id=phone "
                    "cell_id=wss.p2e.after-connect sequence=1 "
                    "wall_utc_ms=9998 monotonic_ms=9998\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093013Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertTrue(rep.integrity_ok, msg=rep.integrity_issues)


class AuditR17_smoke_cross_profile_copy(unittest.TestCase):
    """Architect ROUND-17 P0-3: replacing both smoke files with those
    from another profile stayed GREEN because smoke_verdict had no
    attempt/arm binding."""
    def test_smoke_attempt_id_from_different_profile(self):
        def mutate(d):
            p = os.path.join(d, "smoke_verdict.json")
            with open(p, encoding="utf-8") as f: sv = json.load(f)
            sv["attempt_id"] = _uuid4()  # different attempt
            with open(p, "w", encoding="utf-8") as f: json.dump(sv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_smoke_missing_cell_id(unittest.TestCase):
    """Architect ROUND-17 P0-3: smoke event with missing cell_id
    stayed GREEN because canonical-cell check tolerated None."""
    def test_smoke_event_no_cell_id(self):
        def mutate(d):
            sl = os.path.join(d, "smoke.log")
            with open(sl, encoding="utf-8") as f: s = f.read()
            # Get smoke_run_id so appended event has the right run
            with open(os.path.join(d, "smoke_verdict.json"), encoding="utf-8") as f:
                srid = json.load(f)["smoke_run_id"]
            with open(sl, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=diagnostic_send_dispatched run_id={srid} "
                    f"correlation_id=cid-no-cell role=matrix emitter_id=phone "
                    f"sequence=1 wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_duplicate_dispatched(unittest.TestCase):
    """Architect ROUND-17 P0-4: appending a second dispatched event
    under an existing CID stayed GREEN because the dict de-duped."""
    def test_second_dispatched_same_cid(self):
        def mutate(d):
            import re
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            m = re.search(r"correlation_id=(\S+)", s)
            if not m: return
            cid = m.group(1)
            run_id = _profile_run_id(d)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=diagnostic_send_dispatched run_id={run_id} "
                    f"correlation_id={cid} role=matrix emitter_id=phone "
                    f"cell_id=wss.p2e.after-connect sequence=1 "
                    f"wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_duplicate_attempt_started(unittest.TestCase):
    """Architect ROUND-17 P0-4: duplicate sender_send_attempt_started
    under existing CID stayed GREEN."""
    def test_second_attempt_started_same_cid(self):
        def mutate(d):
            import re
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, encoding="utf-8") as f: s = f.read()
            m = re.search(r"correlation_id=(\S+)", s)
            if not m: return
            cid = m.group(1)
            run_id = _profile_run_id(d)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=sender_send_attempt_started run_id={run_id} "
                    f"correlation_id={cid} role=sender emitter_id=phone "
                    f"cell_id=wss.p2e.after-connect sequence=1 "
                    f"wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_no_cid_lifecycle_event(unittest.TestCase):
    """Architect ROUND-17 P0-4: sender_relay_ack_received with
    correct run/cell/role/emitter but NO correlation_id stayed GREEN."""
    def test_lifecycle_event_without_cid(self):
        def mutate(d):
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            run_id = _profile_run_id(d)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=sender_relay_ack_received run_id={run_id} "
                    f"role=sender emitter_id=phone "
                    f"cell_id=wss.p2e.after-connect "
                    f"wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR17_empty_non_canonical_cell_dir(unittest.TestCase):
    """Architect ROUND-17 P1-1: empty matrix_cells/<non-canonical>/
    directory stayed GREEN because inventory only walked files."""
    def test_empty_non_canonical_dir(self):
        def mutate(d):
            extra = os.path.join(d, "matrix_cells", "wss.p2e.not-canonical")
            os.makedirs(extra)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("unknown cell directory" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


# ── ROUND-18 adversarial regressions (one per architect-proven mutation)

class AuditR18_blocked_cell_verdict_pending(unittest.TestCase):
    """Architect ROUND-18 P0-1: a blocked cell with verdict=PENDING
    (six-cell REST-blocked producer path) stayed GREEN because the
    verifier accepted both BLOCKED and PENDING."""
    def test_blocked_cell_pending_verdict(self):
        def mutate(d):
            # Flip the REST cells from BLOCKED → PENDING (the
            # producer-path bug fixed in ROUND-18 P0-1).
            for cell_id in ("rest.p2e.control", "rest.e2p.control"):
                cell = os.path.join(d, "matrix_cells", cell_id, "cell_verdict.json")
                with open(cell, encoding="utf-8") as f: cv = json.load(f)
                cv["verdict"] = "PENDING"
                with open(cell, "w", encoding="utf-8") as f: json.dump(cv, f, sort_keys=True, indent=2)
            # matrix_verdict rows must also carry PENDING (else the
            # per-row equality check fires first and masks the
            # blocked-verdict violation).
            mv_path = os.path.join(d, "matrix_verdict.json")
            with open(mv_path, encoding="utf-8") as f: mv = json.load(f)
            for row in mv["cells"]:
                if row["cell_id"] in ("rest.p2e.control", "rest.e2p.control"):
                    row["verdict"] = "PENDING"
            with open(mv_path, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                cells_expected_to_run=6,
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("blocked cell verdict must be BLOCKED" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR18_matrix_row_flipped(unittest.TestCase):
    """Architect ROUND-18 P0-3: rewrote every matrix_verdict row to
    verdict=Unresolved / blocked=true (after regenerating checksums),
    verifier still returned GREEN because it never per-row cross-checked."""
    def test_all_rows_flipped(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            mv["product_outcome"] = "RED"
            for row in mv["cells"]:
                row["verdict"] = "Unresolved"
                row["blocked"] = True
            with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR18_matrix_verdict_unknown_key_access_token(unittest.TestCase):
    """Architect ROUND-18 P0-4: `access_token=SECRET_SESSION_VALUE`
    added to matrix_verdict.json survived both schema and privacy."""
    def test_access_token_in_matrix_verdict(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            mv["access_token"] = "SECRET_SESSION_VALUE"
            with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR18_denylist_camelCase_aliases(unittest.TestCase):
    """Architect ROUND-18 P0-4: camelCase credential aliases
    (accessToken / apiKey / clientSecret) evaded the exact-name
    _BANNED_JSON_KEYS check."""
    def test_camelcase_aliases(self):
        for alias in ("accessToken", "apiKey", "clientSecret", "sessionId"):
            with self.subTest(alias=alias):
                def mutate(d, alias=alias):
                    p = os.path.join(d, "matrix_verdict.json")
                    with open(p, encoding="utf-8") as f: mv = json.load(f)
                    mv[alias] = "OPAQUE_VALUE"
                    with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
                with tempfile.TemporaryDirectory() as t:
                    d = build_full_profile_evidence(
                        t, profile_id="yota-phone-off-host-on",
                        utc_suffix="20260814T093012Z",
                        attempt_id=_uuid4(), arm_token=_uuid4(),
                        mutate_before_return=mutate,
                    )
                    rep = ve3.verify_profile_dir(d)
                    self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)


class AuditR18_no_cid_pin_active_intruder(unittest.TestCase):
    """Architect ROUND-18 P0-5: `diagnostic_pin_active role=sender
    emitter_id=intruder` with no CID stayed GREEN because per-CID
    validation never ran for it."""
    def test_no_cid_intruder_pin_active(self):
        def mutate(d):
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            run_id = _profile_run_id(d)
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    f"08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    f"event=diagnostic_pin_active role=sender emitter_id=intruder "
                    f"run_id={run_id} cell_id=wss.p2e.after-connect pin=wss "
                    f"wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR18_top_level_unknown_dir(unittest.TestCase):
    """Architect ROUND-18 P1-1: `audit-empty/nested-empty/` under
    top level stayed GREEN because ROUND-17 walk was matrix_cells-only."""
    def test_unknown_top_level_dir(self):
        def mutate(d):
            os.makedirs(os.path.join(d, "audit-empty", "nested-empty"))
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("unknown top-level directory" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR18_empty_subdir_inside_canonical_cell(unittest.TestCase):
    """Architect ROUND-18 P1-1: an empty subdir inside a canonical
    cell (matrix_cells/wss.p2e.after-connect/nested/) stayed GREEN."""
    def test_empty_subdir_inside_cell(self):
        def mutate(d):
            os.makedirs(os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "nested"))
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("unexpected subdirectory inside cell" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR18_symlinked_file(unittest.TestCase):
    """Architect ROUND-18 P1-1: symlinked FILES anywhere in the tree
    were not rejected (only symlinked dirs at matrix_cells/ were)."""
    def test_symlinked_file_rejected(self):
        def mutate(d):
            target = os.path.join(d, "PROFILE.json")
            link = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "extra-link.json")
            try:
                os.symlink(target, link)
            except (OSError, NotImplementedError):
                # Windows without symlink privilege — degrade to
                # writing a plain unknown file that also trips
                # inventory. The symlink path is exercised on POSIX.
                with open(link, "w", encoding="utf-8") as f: f.write("{}")
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_after_sha256sums=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR18_matrix_verdict_duplicate_row(unittest.TestCase):
    """Architect ROUND-18 P0-3: duplicate row in matrix_verdict.cells
    must RED (missing-canonical AND duplicate-row detections both fire)."""
    def test_duplicate_row(self):
        def mutate(d):
            p = os.path.join(d, "matrix_verdict.json")
            with open(p, encoding="utf-8") as f: mv = json.load(f)
            # Duplicate the first row exactly, drop the last one.
            mv["cells"].append(dict(mv["cells"][0]))
            mv["cells"].pop(1)
            with open(p, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR18_matrix_verdict_product_outcome_wrong(unittest.TestCase):
    """Architect ROUND-18 P0-3: `product_outcome` must be recomputed;
    a claim of GREEN when the counts imply RED is a direct violation."""
    def test_outcome_disagrees_with_counts(self):
        def mutate(d):
            # Force unresolved on ONE cell but keep product_outcome=GREEN.
            cv_path = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "cell_verdict.json")
            with open(cv_path, encoding="utf-8") as f: cv = json.load(f)
            cv["delivered_count"] = 3  # 3 of 5 delivered
            cv["unresolved_count"] = 2
            cv["verdict"] = "Unresolved"
            with open(cv_path, "w", encoding="utf-8") as f: json.dump(cv, f, sort_keys=True, indent=2)
            # matrix_verdict still claims GREEN but sum(unresolved)=2>0
            mv_path = os.path.join(d, "matrix_verdict.json")
            with open(mv_path, encoding="utf-8") as f: mv = json.load(f)
            mv["product_outcome"] = "GREEN"
            mv["delivered_count"] = 38
            mv["unresolved_count"] = 2
            for row in mv["cells"]:
                if row["cell_id"] == "wss.p2e.after-connect":
                    row["verdict"] = "Unresolved"
            with open(mv_path, "w", encoding="utf-8") as f: json.dump(mv, f, sort_keys=True, indent=2)
            # matrix_completion.envelopes_recipient_triplet_complete
            # must also be recomputed to stay consistent with the
            # per-cell delivered sum (else that check fires first
            # and masks the product_outcome mismatch).
            mc_path = os.path.join(d, "matrix_completion.json")
            with open(mc_path, encoding="utf-8") as f: mc = json.load(f)
            mc["envelopes_recipient_triplet_complete"] = 38
            with open(mc_path, "w", encoding="utf-8") as f: json.dump(mc, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("product_outcome" in i and "recomputed" in i
                                for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


# ── ROUND-19 adversarial regressions (one per architect-proven mutation)

class AuditR19_cross_run_session_intruder(unittest.TestCase):
    """Architect ROUND-19 P0-3: `diagnostic_session_started role=sender
    emitter_id=intruder` appended after SHA regen stayed GREEN because
    CROSS_RUN_TOLERATED events skipped ownership checks entirely."""
    def test_cross_run_intruder(self):
        def mutate(d):
            cap = os.path.join(d, "matrix_cells", "wss.p2e.after-connect", "capture.log")
            with open(cap, "a", encoding="utf-8") as f:
                f.write(
                    "08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    "event=diagnostic_session_started role=sender emitter_id=intruder "
                    "run_id=foreign-run cell_id=foreign-cell restored=false "
                    "wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("cross-run event" in i and "intruder" in repr(i)
                                for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR19_smoke_cross_run_intruder(unittest.TestCase):
    """Architect ROUND-19 P0-3: same intruder pattern in smoke.log."""
    def test_smoke_cross_run_intruder(self):
        def mutate(d):
            sl = os.path.join(d, "smoke.log")
            with open(sl, "a", encoding="utf-8") as f:
                f.write(
                    "08-14 12:00:00.000  1234  1234 I WSS_DIAG: "
                    "event=diagnostic_session_started role=sender emitter_id=intruder "
                    "run_id=foreign-run cell_id=foreign-cell restored=false "
                    "wall_utc_ms=9999 monotonic_ms=9999\n"
                )
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("smoke.log: cross-run event" in i
                                for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR19_profile_state_unknown_key(unittest.TestCase):
    """Architect ROUND-19 P0-2: unknown top-level key in PROFILE_STATE.json."""
    def test_unknown_top_key(self):
        def mutate(d):
            p = os.path.join(d, "PROFILE_STATE.json")
            with open(p, encoding="utf-8") as f: ps = json.load(f)
            ps["future_field"] = True
            with open(p, "w", encoding="utf-8") as f: json.dump(ps, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR19_profile_state_phone_pii(unittest.TestCase):
    """Architect ROUND-19 P0-2: nested `subscriber_display_name` under
    PROFILE_STATE.phone_observed survived — closed schema now rejects."""
    def test_phone_observed_pii(self):
        def mutate(d):
            p = os.path.join(d, "PROFILE_STATE.json")
            with open(p, encoding="utf-8") as f: ps = json.load(f)
            ps["phone_observed"]["subscriber_display_name"] = "Alice"
            with open(p, "w", encoding="utf-8") as f: json.dump(ps, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR19_network_profile_phone_pii(unittest.TestCase):
    """Architect ROUND-19 P0-2: nested PII under network_profile.phone."""
    def test_network_profile_phone_pii(self):
        def mutate(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: np = json.load(f)
            np["phone"]["subscriber_display_name"] = "Alice"
            with open(p, "w", encoding="utf-8") as f: json.dump(np, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR19_egress_fingerprint_unknown_key(unittest.TestCase):
    """Architect ROUND-19 P0-2: unknown key in EGRESS_FINGERPRINT.json."""
    def test_unknown_key(self):
        def mutate(d):
            p = os.path.join(d, "EGRESS_FINGERPRINT.json")
            with open(p, encoding="utf-8") as f: ef = json.load(f)
            ef["future_field"] = True
            with open(p, "w", encoding="utf-8") as f: json.dump(ef, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR19_signed_prekey_unknown_key(unittest.TestCase):
    """Architect ROUND-19 P0-2: unknown key in signed_prekey_readiness.json."""
    def test_unknown_key(self):
        def mutate(d):
            p = os.path.join(d, "signed_prekey_readiness.json")
            with open(p, encoding="utf-8") as f: spr = json.load(f)
            spr["future_field"] = True
            with open(p, "w", encoding="utf-8") as f: json.dump(spr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR19_checkpoint_log_entry_unknown_key(unittest.TestCase):
    """Architect ROUND-19 P0-2: unknown key in a checkpoint_log[*] entry."""
    def test_entry_unknown_key(self):
        def mutate(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            arr[0]["future_field"] = True
            with open(p, "w", encoding="utf-8") as f: json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR19_checkpoint_log_observed_unknown_key(unittest.TestCase):
    """Architect ROUND-19 P0-2: unknown key inside checkpoint_log[*].observed."""
    def test_observed_unknown_key(self):
        def mutate(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            arr[0]["observed"]["subscriber_display_name"] = "Alice"
            with open(p, "w", encoding="utf-8") as f: json.dump(arr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR19_smoke_log_empty(unittest.TestCase):
    """Architect ROUND-19 P0-1: empty smoke.log with valid PROFILE.json
    + smoke_verdict.json used to unlock `full`. The pre-full gate now
    calls load_smoke_verdict which rebuilds both CID chains from
    smoke.log — an empty log has zero WSS_DIAG events → RED."""
    def test_empty_smoke_log(self):
        def mutate(d):
            open(os.path.join(d, "smoke.log"), "w").close()
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("smoke.log contains zero WSS_DIAG events" in i
                                or "smoke.log missing" in i
                                for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


# ── ROUND-20 adversarial regressions (real-reporter + deletion)

class AuditR20_real_reporter_full_shape(unittest.TestCase):
    """Architect ROUND-20 P0-1: real Kotlin reporter emits six extra
    fields the ROUND-19 verifier rejected. Cross-boundary test:
    feed the FULL producer-shaped report through the verifier and
    require GREEN. build_full_profile_evidence now writes this
    shape by default (helper aligned with the reporter)."""
    def test_full_shape_is_green(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
            )
            with open(os.path.join(d, "network_profile.json"), encoding="utf-8") as f:
                np = json.load(f)
            for k in ("schema_version", "at_wall_ms", "at_monotonic_ms",
                      "active_network_present", "process_uid",
                      "app_debuggable"):
                self.assertIn(k, np["phone"],
                              msg=f"helper must emit {k!r} on phone side (R20 P0-1)")
                self.assertIn(k, np["emu"],
                              msg=f"helper must emit {k!r} on emu side (R20 P0-1)")
            for k in ("endpoint", "at_wall_ms", "timeout_ms_used"):
                self.assertIn(k, np["emu"]["egress_fingerprint"],
                              msg=f"helper egress must emit {k!r} (R20 P0-1)")
            rep = ve3.verify_profile_dir(d)
            self.assertTrue(rep.integrity_ok, msg=rep.integrity_issues)


class AuditR20_delete_profile_state_emu_observed(unittest.TestCase):
    """Architect ROUND-20 P0-2: deleting PROFILE_STATE.emu_observed
    stayed GREEN because closed schema didn't require it."""
    def test_delete_emu_observed(self):
        def mutate(d):
            p = os.path.join(d, "PROFILE_STATE.json")
            with open(p, encoding="utf-8") as f: ps = json.load(f)
            del ps["emu_observed"]
            with open(p, "w", encoding="utf-8") as f: json.dump(ps, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("missing required key" in i and "emu_observed" in i
                                for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR20_delete_phone_observed_field(unittest.TestCase):
    """Architect ROUND-20 P0-2: deleting nested required fields
    (wifi_enabled, mobile_data_enabled, active_data_subscription_id)."""
    def test_delete_nested_fields(self):
        for key in ("wifi_enabled", "mobile_data_enabled",
                    "active_data_subscription_id"):
            with self.subTest(key=key):
                def mutate(d, key=key):
                    p = os.path.join(d, "PROFILE_STATE.json")
                    with open(p, encoding="utf-8") as f: ps = json.load(f)
                    del ps["phone_observed"][key]
                    with open(p, "w", encoding="utf-8") as f: json.dump(ps, f, sort_keys=True, indent=2)
                with tempfile.TemporaryDirectory() as t:
                    d = build_full_profile_evidence(
                        t, profile_id="yota-phone-off-host-on",
                        utc_suffix="20260814T093012Z",
                        attempt_id=_uuid4(), arm_token=_uuid4(),
                        mutate_before_return=mutate,
                    )
                    rep = ve3.verify_profile_dir(d)
                    self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)


class AuditR20_delete_network_profile_wifi_enabled(unittest.TestCase):
    """Architect ROUND-20 P0-2: deleting network_profile.phone.wifi_enabled."""
    def test_delete_phone_wifi(self):
        def mutate(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: np = json.load(f)
            del np["phone"]["wifi_enabled"]
            with open(p, "w", encoding="utf-8") as f: json.dump(np, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            # R23 P1: verifier now emits surface_id
            # (`network_profile.json/phone`) rather than the dotted
            # display path. Accept either.
            self.assertTrue(any(
                ("network_profile.phone missing required key" in i
                 or "network_profile.json/phone missing required key" in i)
                and "wifi_enabled" in i
                for i in rep.integrity_issues),
                msg=rep.integrity_issues)


class AuditR20_delete_network_profile_emu_egress(unittest.TestCase):
    """Architect ROUND-20 P0-2: deleting network_profile.emu.egress_fingerprint."""
    def test_delete_emu_egress(self):
        def mutate(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: np = json.load(f)
            del np["emu"]["egress_fingerprint"]
            with open(p, "w", encoding="utf-8") as f: json.dump(np, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR20_delete_egress_fingerprint_at_wall(unittest.TestCase):
    """Architect ROUND-20 P0-2: deleting EGRESS_FINGERPRINT.at_wall_ms."""
    def test_delete_at_wall(self):
        def mutate(d):
            p = os.path.join(d, "EGRESS_FINGERPRINT.json")
            with open(p, encoding="utf-8") as f: ef = json.load(f)
            del ef["at_wall_ms"]
            with open(p, "w", encoding="utf-8") as f: json.dump(ef, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("EGRESS_FINGERPRINT.json missing required key" in i
                                for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR20_delete_signed_prekey_line(unittest.TestCase):
    """Architect ROUND-20 P0-2: deleting signed_prekey_readiness.emu_line."""
    def test_delete_emu_line(self):
        def mutate(d):
            p = os.path.join(d, "signed_prekey_readiness.json")
            with open(p, encoding="utf-8") as f: spr = json.load(f)
            del spr["emu_line"]
            with open(p, "w", encoding="utf-8") as f: json.dump(spr, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR20_delete_checkpoint_top_field(unittest.TestCase):
    """Architect ROUND-20 P0-2: deleting checkpoint entry required field."""
    def test_delete_entry_field(self):
        for key in ("address_family_host", "equal_host_emu"):
            with self.subTest(key=key):
                def mutate(d, key=key):
                    p = os.path.join(d, "checkpoint_log.json")
                    with open(p, encoding="utf-8") as f: arr = json.load(f)
                    if key in arr[0]:
                        del arr[0][key]
                    with open(p, "w", encoding="utf-8") as f: json.dump(arr, f, sort_keys=True, indent=2)
                with tempfile.TemporaryDirectory() as t:
                    d = build_full_profile_evidence(
                        t, profile_id="yota-phone-off-host-on",
                        utc_suffix="20260814T093012Z",
                        attempt_id=_uuid4(), arm_token=_uuid4(),
                        mutate_before_return=mutate,
                    )
                    rep = ve3.verify_profile_dir(d)
                    self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)


class AuditR20_network_profile_process_uid_accepted(unittest.TestCase):
    """Architect ROUND-20 P0-1: `process_uid` is a legitimate real-
    reporter field. Under ROUND-19 it was flagged as unknown; now
    the verifier accepts it (and REQUIRES it)."""
    def test_process_uid_required(self):
        # Deletion of process_uid must RED.
        def mutate(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: np = json.load(f)
            del np["phone"]["process_uid"]
            with open(p, "w", encoding="utf-8") as f: json.dump(np, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)
            self.assertTrue(any("process_uid" in i for i in rep.integrity_issues),
                            msg=rep.integrity_issues)


class AuditR20_egress_fingerprint_missing_endpoint(unittest.TestCase):
    """Architect ROUND-20 P0-2: deleting egress_fingerprint.endpoint."""
    def test_delete_endpoint(self):
        def mutate(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: np = json.load(f)
            del np["emu"]["egress_fingerprint"]["endpoint"]
            with open(p, "w", encoding="utf-8") as f: json.dump(np, f, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok)


class AuditR321_route_dependent_sender_completion(unittest.TestCase):
    """R30.21: WSS and REST share dispatch proof but not terminal proof.

    Every assertion goes through the full profile verifier unless the test
    explicitly compares the accepted WSS-2 per-envelope authority.
    """

    _PROFILE_ID = "yota-phone-off-host-on"
    _SUFFIX = "20260828T112042Z"
    _REST_CELL = "rest.p2e.control"
    _WSS_CELL = "wss.p2e.after-connect"

    @staticmethod
    def _rewrite_first_event(d, cell_id, event, transform):
        path = os.path.join(d, "matrix_cells", cell_id, "capture.log")
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
        changed = False
        out = []
        for line in lines:
            if not changed and f"event={event} " in line:
                replacement = transform(line)
                if replacement is not None:
                    out.append(replacement)
                changed = True
            else:
                out.append(line)
        if not changed:
            raise AssertionError(f"fixture event {event!r} not found in {cell_id}")
        with open(path, "w", encoding="utf-8") as f:
            f.writelines(out)

    def _build(self, tmp_root, mutate=None):
        return build_full_profile_evidence(
            tmp_root,
            profile_id=self._PROFILE_ID,
            utc_suffix=self._SUFFIX,
            attempt_id=_uuid4(),
            arm_token=_uuid4(),
            mutate_before_return=mutate,
        )

    def _assert_targeted_red(self, mutate, needle):
        with tempfile.TemporaryDirectory() as t:
            d = self._build(t, mutate)
            rep = ve3.verify_profile_dir(d)
            self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)
            self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")
            self.assertTrue(
                any(needle in issue for issue in rep.integrity_issues),
                msg=rep.integrity_issues,
            )

    def test_physical_eight_cell_shape_is_green(self):
        with tempfile.TemporaryDirectory() as t:
            d = self._build(t)
            rep = ve3.verify_profile_dir(d)
            self.assertTrue(rep.integrity_ok, msg=rep.integrity_issues)
            self.assertEqual(rep.product_outcome, "GREEN")
            rest_capture = os.path.join(
                d, "matrix_cells", self._REST_CELL, "capture.log"
            )
            with open(rest_capture, encoding="utf-8") as f:
                rest_lines = f.readlines()
            completions = [
                line for line in rest_lines
                if "event=sender_rest_post_completed " in line
            ]
            self.assertEqual(len(completions), 5)
            self.assertTrue(all("dispatched=" not in line for line in completions))
            self.assertFalse(any(
                "event=sender_relay_ack_received " in line for line in rest_lines
            ))

    def test_rest_rejected_acceptance_is_red(self):
        def mutate(d):
            self._rewrite_first_event(
                d, self._REST_CELL, "sender_rest_post_completed",
                lambda line: line.replace(
                    "relay_acceptance=accepted", "relay_acceptance=rejected", 1
                ),
            )
        self._assert_targeted_red(mutate, "no single sender_rest_post_completed")

    def test_missing_rest_completion_is_red(self):
        def mutate(d):
            self._rewrite_first_event(
                d, self._REST_CELL, "sender_rest_post_completed", lambda _line: None
            )
        self._assert_targeted_red(mutate, "missing route return sender_rest_post_completed")

    def test_rest_transport_decision_dispatched_false_is_red(self):
        def mutate(d):
            self._rewrite_first_event(
                d, self._REST_CELL, "sender_transport_decision",
                lambda line: line.replace("dispatched=true", "dispatched=false", 1),
            )
        self._assert_targeted_red(mutate, "no single sender_transport_decision")

    def test_missing_wss_sender_ack_is_red(self):
        def mutate(d):
            self._rewrite_first_event(
                d, self._WSS_CELL, "sender_relay_ack_received", lambda _line: None
            )
        self._assert_targeted_red(mutate, "missing sender_relay_ack_received")

    def test_wss_return_dispatched_false_is_red(self):
        def mutate(d):
            self._rewrite_first_event(
                d, self._WSS_CELL, "sender_wss_send_returned",
                lambda line: line.replace("dispatched=true", "dispatched=false", 1),
            )
        self._assert_targeted_red(mutate, "no single sender_wss_send_returned")

    def test_broken_rest_recipient_triplet_is_red(self):
        def mutate(d):
            self._rewrite_first_event(
                d, self._REST_CELL, "recipient_message_persisted", lambda _line: None
            )
        self._assert_targeted_red(mutate, "missing recipient_message_persisted")

    def test_rest_completion_with_dispatched_field_is_red(self):
        def mutate(d):
            self._rewrite_first_event(
                d, self._REST_CELL, "sender_rest_post_completed",
                lambda line: line.replace(
                    "inner_route=rest", "dispatched=true inner_route=rest", 1
                ),
            )
        self._assert_targeted_red(mutate, "no single sender_rest_post_completed")

    def test_rest_sender_ack_is_red(self):
        def mutate(d):
            path = os.path.join(
                d, "matrix_cells", self._REST_CELL, "capture.log"
            )
            with open(path, encoding="utf-8") as f:
                text = f.read()
            import re
            cid = re.search(r"correlation_id=(\S+)", text).group(1)
            run_id = _profile_run_id(d)
            with open(path, "a", encoding="utf-8") as f:
                _write_wss_diag_line(
                    f, "sender_relay_ack_received", cid=cid, role="sender",
                    emitter="phone", run_id=run_id, cell_id=self._REST_CELL,
                    wall_ms=1_300_000,
                )
        self._assert_targeted_red(mutate, "must be absent from a REST chain")

    def test_physical_rest_shape_matches_accepted_wss2(self):
        v2 = ve3.VE
        run_id = "run-r321-parity"
        cell_id = self._REST_CELL
        cid = "cid-r321-parity"

        def event(device, name, wall, **kwargs):
            return v2.WssEvent(
                device=device, event=name, run_id=run_id, cell_id=cell_id,
                correlation_id=kwargs.pop("correlation_id", cid),
                wall_utc_ms=wall, monotonic_ms=wall, **kwargs,
            )

        pin = event(
            "phone", "diagnostic_pin_active", 1, correlation_id=None,
            role="matrix", emitter_id="phone", pin="rest",
        )
        enqueue = event(
            "phone", "sender_enqueue", 10,
            role="sender", emitter_id="phone",
        )
        chain = [
            event(
                "phone", "sender_send_attempt_started", 8,
                role="sender", emitter_id="phone", sequence=1,
            ),
            event(
                "phone", "diagnostic_send_dispatched", 9,
                role="matrix", emitter_id="phone", sequence=1,
            ),
            enqueue,
            event(
                "phone", "sender_transport_decision", 11,
                role="sender", emitter_id="phone", dispatched=True,
                outer_transport="direct", inner_route="rest",
            ),
            event(
                "phone", "sender_rest_post_completed", 12,
                role="sender", emitter_id="phone", inner_route="rest",
                relay_acceptance="accepted",
            ),
            event(
                "emulator", "recipient_deliver_received", 13,
                role="recipient", emitter_id="emulator", dedup_gate="fresh",
            ),
            event(
                "emulator", "recipient_message_persisted", 14,
                role="recipient", emitter_id="emulator",
            ),
            event(
                "emulator", "recipient_ack_deliver_sent", 15,
                role="recipient", emitter_id="emulator",
            ),
        ]
        v2_verdict, v2_issues = v2.classify_envelope(
            enqueue, chain, "rest", cell_id, run_id,
            "phone", "emulator", "phone", "emulator", [pin] + chain,
            20, 0,
        )
        v3_issues = ve3._validate_cid_chain(
            cid, chain, cell_id=cell_id,
            expected_sender_emitter="phone",
            expected_recipient_emitter="emulator",
            expected_inner_route="rest", profile_run_id=run_id,
        )
        self.assertEqual(v2_verdict, "Delivered once", msg=v2_issues)
        self.assertEqual(v2_issues, [])
        self.assertEqual(v3_issues, [])

    def test_adb_mock_rest_branch_has_the_physical_shape(self):
        mock_path = os.path.join(_PKG, "lib", "wss3-adb-mock.sh")
        with open(mock_path, encoding="utf-8") as f:
            source = f.read()
        import re
        branch = re.search(
            r'if \[ "\$pin" = "rest" \]; then(?P<rest>.*?)'
            r'\n\s*else(?P<wss>.*?)\n\s*fi',
            source,
            re.DOTALL,
        )
        self.assertIsNotNone(branch)
        self.assertIn("event=sender_rest_post_completed", branch.group("rest"))
        self.assertNotIn("dispatched=true", branch.group("rest"))
        self.assertNotIn("event=sender_relay_ack_received", branch.group("rest"))
        self.assertIn("event=sender_wss_send_returned", branch.group("wss"))
        self.assertIn("dispatched=true", branch.group("wss"))
        self.assertIn("event=sender_relay_ack_received", branch.group("wss"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
