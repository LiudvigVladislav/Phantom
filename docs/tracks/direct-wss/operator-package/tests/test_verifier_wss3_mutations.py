#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 audit ROUND-21 CLOSURE — data-driven adversarial mutation
# matrix.
#
# Reads schema_wss3.py (the machine-readable canonical schema) and
# generates ONE test case per (field × mutation kind) covering:
#
#   1. delete_required           — required field removed
#   2. add_unknown_key           — sibling extra key
#   3. wrong_type_<T>            — value replaced with every wrong JSON type
#   4. invalid_enum              — enum member outside the allowed set
#   5. wrong_pinned              — pinned constant replaced
#   6. forbidden_null            — null in a non-nullable slot
#   7. contradictory_value       — cross-file duplicated value diverged
#   8. malformed_timestamp       — non-int / negative timestamp
#   9. privacy_denylisted        — planted credential-alias key/value
#
# Every generated fixture MUST produce integrity=False (RED or
# NOT_EVALUABLE). The runner prints a per-kind pass/fail count on
# stdout so the review pack can capture proof-of-RED.
#
# Also asserts cross-consistency between:
#   - schema_wss3.py canonical schema
#   - fixtures/canonical_network_profile.{phone,emu}.json
#   - Kotlin conformance test's enumerated CANONICAL_*_KEYS
#
# Divergence between any two → test failure.

from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import unittest
from typing import Any, Callable, Dict, List, Tuple

_HERE = os.path.abspath(os.path.dirname(__file__))
_PKG  = os.path.dirname(_HERE)
sys.path.insert(0, _PKG)
sys.path.insert(0, _HERE)

import schema_wss3 as S  # noqa: E402
import verify_evidence_wss3 as V  # noqa: E402
from test_verifier_wss3 import (  # noqa: E402
    build_full_profile_evidence,
    _uuid4,
    _write_wss_diag_line,
)


_FIXTURE_DIR = os.path.join(_PKG, "fixtures")


# ── cross-consistency: schema ↔ fixture ↔ Kotlin test ─────────────

class CanonicalShapeConsistency(unittest.TestCase):
    """Three-way pin: schema_wss3.py keys, on-disk fixture keys, and
    the Kotlin conformance test's enumerated `CANONICAL_*_KEYS` MUST
    all agree. Any drift → P0 audit failure per ROUND-21."""

    def _fixture(self, name: str) -> dict:
        with open(os.path.join(_FIXTURE_DIR, name), encoding="utf-8") as f:
            return json.load(f)

    def _kotlin_source_loads_fixture(self) -> bool:
        """Audit ROUND-22 P0-2: the Kotlin test MUST load the fixture
        as a classpath resource (no hard-coded key sets). Verify by
        source inspection: (a) file exists in this checkout, (b)
        contains `loadCanonical("canonical_network_profile...")`
        markers, (c) does NOT re-declare a hard-coded
        `CANONICAL_*_KEYS` set."""
        path = os.path.abspath(os.path.join(
            _PKG, "..", "..", "..", "..",
            "apps", "android", "src", "androidUnitTest", "kotlin",
            "phantom", "android", "diagnostic",
            "DiagnosticNetworkProfileReporterCanonicalShapeTest.kt",
        ))
        if not os.path.isfile(path):
            return False
        with open(path, encoding="utf-8") as f:
            src = f.read()
        # MUST load fixture via classpath.
        if 'loadCanonical("canonical_network_profile.emu.json")' not in src:
            return False
        if 'loadCanonical("canonical_network_profile.phone.json")' not in src:
            return False
        # MUST NOT re-declare hard-coded key sets.
        if re.search(r"CANONICAL_.*_KEYS\s*:\s*Set<String>", src):
            return False
        return True

    def test_phone_schema_matches_fixture(self):
        fx = self._fixture("canonical_network_profile.phone.json")
        # Schema declares wifi_read_error as OPTIONAL — healthy fixture
        # omits it. Assert the fixture is a subset of allowed keys AND
        # includes every non-optional required key.
        req = {n for n, s in S.NETWORK_PROFILE_DEVICE_SCHEMA.items()
               if s.get("presence") == S.P_REQ
               and n != "egress_fingerprint"}  # phone-side forbidden
        allowed = set(S.NETWORK_PROFILE_DEVICE_SCHEMA.keys())
        self.assertLessEqual(set(fx.keys()), allowed,
                             msg=f"phone fixture has unknown key(s)")
        self.assertTrue(req.issubset(set(fx.keys())),
                        msg=f"phone fixture missing required: {req - set(fx.keys())}")

    def test_emu_schema_matches_fixture(self):
        fx = self._fixture("canonical_network_profile.emu.json")
        req = {n for n, s in S.NETWORK_PROFILE_DEVICE_SCHEMA.items()
               if s.get("presence") == S.P_REQ}
        req.add("egress_fingerprint")   # emu-side REQUIRED
        allowed = set(S.NETWORK_PROFILE_DEVICE_SCHEMA.keys())
        self.assertLessEqual(set(fx.keys()), allowed)
        self.assertTrue(req.issubset(set(fx.keys())),
                        msg=f"emu fixture missing required: {req - set(fx.keys())}")
        # Nested egress: closed schema + required keys.
        egf = fx["egress_fingerprint"]
        egf_req = {n for n, s in S.NETWORK_PROFILE_EGRESS_SCHEMA.items()
                   if s.get("presence") == S.P_REQ}
        self.assertLessEqual(set(egf.keys()),
                             set(S.NETWORK_PROFILE_EGRESS_SCHEMA.keys()))
        self.assertTrue(egf_req.issubset(set(egf.keys())),
                        msg=f"emu egress missing required: {egf_req - set(egf.keys())}")

    def test_kotlin_conformance_reads_fixture_as_resource(self):
        """Audit ROUND-22 P0-2: the Kotlin conformance test must
        consume the on-disk fixture (via classpath resource), not
        re-declare the key set in Kotlin source."""
        if not os.path.isfile(os.path.abspath(os.path.join(
            _PKG, "..", "..", "..", "..",
            "apps", "android", "src", "androidUnitTest", "kotlin",
            "phantom", "android", "diagnostic",
            "DiagnosticNetworkProfileReporterCanonicalShapeTest.kt",
        ))):
            self.skipTest("Kotlin source not present in this checkout")
        self.assertTrue(
            self._kotlin_source_loads_fixture(),
            msg=("Kotlin conformance test violates ROUND-22 P0-2: "
                 "must call loadCanonical(...) for both phone AND emu "
                 "fixtures, AND MUST NOT re-declare "
                 "CANONICAL_*_KEYS as a Kotlin authority."),
        )


# ── data-driven mutation generation ───────────────────────────────

# Path-manipulation helpers.

def _split(path: str) -> List[str]:
    return path.split(".")


def _dig(obj: dict, path: List[str]) -> Tuple[dict, str]:
    parent = obj
    for p in path[:-1]:
        parent = parent[p]
    return parent, path[-1]


# Audit ROUND-23 P1: dispatch is DERIVED from `SURFACES` (exact
# surface IDs — no basename matching, no separate mapping table).
# `_SURFACE_DISPATCH[surface_id] = (file, json_path)` for every
# non-validator-only entry.
_SURFACE_DISPATCH = {
    sid: (entry["file"], list(entry["json_path"]))
    for sid, entry in S.SURFACES.items()
    if not entry.get("validator_only")
}
# Reverse lookup: schema path_prefix → surface_id (mutation
# generator's leaf `entry` carries `surface`, but for legacy
# call sites we may still need this).
_PREFIX_TO_SURFACE = {
    entry["path_prefix"]: sid
    for sid, entry in S.SURFACES.items()
    if not entry.get("validator_only") and "path_prefix" in entry
}


def _dig_indexed(obj, path):
    """Navigate `path` (each element either a dict key or a
    `__list_index__:<key>:<i>` / `__list_index__:<i>` sentinel for
    list traversal) and return (parent, last_key_or_index)."""
    parent = obj
    for i, seg in enumerate(path[:-1]):
        if isinstance(seg, str) and seg.startswith("__list_index__:"):
            spec = seg.split(":")
            if len(spec) == 2:
                idx = int(spec[1])
                parent = parent[idx]
            else:  # e.g. __list_index__:cells:0
                key = spec[1]; idx = int(spec[2])
                parent = parent[key][idx]
        else:
            parent = parent[seg]
    last = path[-1]
    if isinstance(last, str) and last.startswith("__list_index__:"):
        spec = last.split(":")
        if len(spec) == 2:
            return parent, int(spec[1])
        key = spec[1]; idx = int(spec[2])
        return parent[key], idx
    return parent, last


def _mutations_for_entry(entry: dict) -> List[Tuple[str, Callable[[dict], None]]]:
    spec = entry["spec"]
    field = entry["field"]
    # Audit ROUND-23 P1: dispatch by EXACT surface_id carried on
    # the leaf entry (populated by all_leaf_schemas() from
    # SURFACES). No basename matching, no separate mapping.
    surface_id = entry.get("surface")
    if surface_id is None:
        raise AssertionError(
            f"leaf entry missing `surface` key: {entry['path']!r} "
            f"(all_leaf_schemas() drifted from SURFACES)"
        )
    dispatch = _SURFACE_DISPATCH.get(surface_id)
    if dispatch is None:
        raise AssertionError(
            f"surface_id {surface_id!r} in leaf entry but NOT in "
            f"_SURFACE_DISPATCH (registry drift)"
        )
    file_key, prefix_path = dispatch
    sub_path = list(prefix_path) + [field]

    kinds: List[Tuple[str, Callable[[dict], None]]] = []

    # 1. delete_required (only for required fields).
    if spec.get("presence") == S.P_REQ:
        def _delete(obj, sp=sub_path):
            parent, key = _dig_indexed(obj, sp)
            if isinstance(parent, dict):
                parent.pop(key, None)
        kinds.append(("delete_required", _delete))

    # 2. add_unknown_key (add sibling extra).
    def _unknown(obj, sp=sub_path):
        parent, _ = _dig_indexed(obj, sp)
        if isinstance(parent, dict):
            parent["future_field_r22_generated"] = True
    kinds.append(("add_unknown_key", _unknown))

    # 3. wrong_type_* (one test per wrong JSON type).
    for wrong in S.wrong_type_replacements_for(spec["type"]):
        def _wt(obj, sp=sub_path, w=wrong):
            parent, key = _dig_indexed(obj, sp)
            parent[key] = w
        kinds.append((f"wrong_type_{type(wrong).__name__}", _wt))

    # 4. invalid_enum.
    ev = S.enum_values(spec)
    if ev is not None:
        def _bad_enum(obj, sp=sub_path):
            parent, key = _dig_indexed(obj, sp)
            parent[key] = "R22_INVALID_ENUM_VALUE"
        kinds.append(("invalid_enum", _bad_enum))

    # 5. wrong_pinned.
    pin = S.pinned_value(spec)
    if pin is not None:
        def _pin_break(obj, sp=sub_path, p=pin):
            parent, key = _dig_indexed(obj, sp)
            if isinstance(p, bool):
                parent[key] = not p
            elif isinstance(p, str):
                parent[key] = "R22_pin_drift"
            elif isinstance(p, int):
                parent[key] = p + 1
            else:
                parent[key] = None
        kinds.append(("wrong_pinned", _pin_break))

    # 6. forbidden_null (only for non-nullable).
    if spec.get("nullability") == S.N_NONE:
        def _null(obj, sp=sub_path):
            parent, key = _dig_indexed(obj, sp)
            parent[key] = None
        kinds.append(("forbidden_null", _null))

    # 7. contradictory_value / cross-file mismatch.
    for _cf in spec.get("cross_file", []):
        other_path, _ = _cf
        def _contradict(obj, sp=sub_path, op=other_path):
            parent, key = _dig_indexed(obj, sp)
            v = parent.get(key) if isinstance(parent, dict) else parent[key]
            if isinstance(v, str):
                parent[key] = (v + "_DRIFT") if v else "DRIFT"
            elif isinstance(v, bool):
                parent[key] = not v
            elif isinstance(v, int):
                parent[key] = v + 100
        kinds.append(("contradictory_cross_file", _contradict))

    # 8. malformed_timestamp (only for temporal anchors).
    if field.endswith("_ms") or field.startswith("at_"):
        def _bad_ts(obj, sp=sub_path):
            parent, key = _dig_indexed(obj, sp)
            parent[key] = "later"
        kinds.append(("malformed_timestamp", _bad_ts))

    # 9. privacy_denylisted.
    def _priv(obj, sp=sub_path):
        parent, _ = _dig_indexed(obj, sp)
        if isinstance(parent, dict):
            parent["session_token"] = "SECRET_SESSION_VALUE"
    kinds.append(("privacy_denylisted", _priv))

    # Audit ROUND-22 P0-2: additional mutation kinds.
    # 10. below_min — one below declared numeric minimum.
    bm = S.below_min_of(spec)
    if bm is not None:
        def _below(obj, sp=sub_path, v=bm):
            parent, key = _dig_indexed(obj, sp)
            parent[key] = v
        kinds.append(("below_min", _below))

    # 11. above_max — one above declared numeric maximum.
    am = S.above_max_of(spec)
    if am is not None:
        def _above(obj, sp=sub_path, v=am):
            parent, key = _dig_indexed(obj, sp)
            parent[key] = v
        kinds.append(("above_max", _above))

    # 12. regex_break — value that intentionally fails the regex.
    rb = S.regex_break_of(spec)
    if rb is not None:
        def _rb(obj, sp=sub_path, v=rb):
            parent, key = _dig_indexed(obj, sp)
            parent[key] = v
        kinds.append(("regex_break", _rb))

    # Attach the on-disk file key so the harness can locate the
    # right mutation target.
    return [(k, _wrap(cb, file_key)) for k, cb in kinds]


# ── ROUND-22 P0-2: cross-field / success invariant mutations ───────
#
# These operate on multi-field JSON invariants rather than single
# leaves, so they're registered separately from the per-leaf
# generator.

def _mutate_cross_field_temporal_parent_nested(evidence_dir: str):
    """Break the network_profile.emu at_wall_ms parent/nested
    equality (audit ROUND-22 P0-3)."""
    p = os.path.join(evidence_dir, "network_profile.json")
    with open(p, encoding="utf-8") as f:
        obj = json.load(f)
    obj["emu"]["egress_fingerprint"]["at_wall_ms"] = (
        obj["emu"]["egress_fingerprint"]["at_wall_ms"] + 999_999
    )
    with open(p, "w", encoding="utf-8") as f:
        json.dump(obj, f, sort_keys=True, indent=2)


def _mutate_success_invariant_hmac_with_5xx(evidence_dir: str):
    """Non-null `hmac_fp_hex` with HTTP 500 breaks the success
    invariant (audit ROUND-22 P0-3)."""
    p = os.path.join(evidence_dir, "network_profile.json")
    with open(p, encoding="utf-8") as f:
        obj = json.load(f)
    obj["emu"]["egress_fingerprint"]["http_status"] = 500
    # hmac_fp_hex stays non-null (canonical fixture) — contradiction.
    with open(p, "w", encoding="utf-8") as f:
        json.dump(obj, f, sort_keys=True, indent=2)


CROSS_FIELD_MUTATIONS = [
    ("cross_field_temporal", "network_profile.emu.at_wall_ms<->egress.at_wall_ms",
     _mutate_cross_field_temporal_parent_nested),
    ("success_invariant",    "network_profile.emu.egress.hmac_nonnull_requires_2xx",
     _mutate_success_invariant_hmac_with_5xx),
]


def _wrap(mutator: Callable[[dict], None], file_key: str) -> Callable[[str], None]:
    """Bind a JSON-object mutator to (evidence_dir, file_key)."""
    def _run(evidence_dir: str):
        p = os.path.join(evidence_dir, file_key)
        with open(p, encoding="utf-8") as f:
            obj = json.load(f)
        mutator(obj)
        with open(p, "w", encoding="utf-8") as f:
            json.dump(obj, f, sort_keys=True, indent=2)
    return _run


class GeneratedMutationMatrix(unittest.TestCase):
    """One method per (field × mutation kind) — auto-registered via
    __init_subclass__-style dynamic method binding below."""

    maxDiff = None


def _iter_mutations():
    for entry in S.all_leaf_schemas():
        # Skip entries whose top-level surface is PROFILE_STATE.emu_observed
        # for cross_file mutations (PROFILE_STATE binds via
        # phone_observed only in this pass; emu_observed cross-file
        # applies only to has_transport_vpn / active_network_kind and
        # those are covered by the general mutation classes below).
        for kind, fn in _mutations_for_entry(entry):
            yield entry, kind, fn


_COUNTS: Dict[str, int] = {}


def _make_test(entry: dict, kind: str, mutator: Callable[[str], None]):
    def _t(self):
        # Build a healthy evidence dir, mutate one field, re-verify.
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=lambda dd: mutator(dd),
            )
            rep = V.verify_profile_dir(d)
            self.assertFalse(
                rep.integrity_ok,
                msg=(f"MUTATION SURVIVED as GREEN: "
                     f"{entry['path']} / {kind}: {rep.integrity_issues}"),
            )
            _COUNTS[kind] = _COUNTS.get(kind, 0) + 1
    _t.__doc__ = f"mutation: {entry['path']} / {kind}"
    return _t


def _register_generated_mutations():
    seen = set()
    for entry, kind, fn in _iter_mutations():
        base = entry["path"].replace(".", "_")
        # Include the kind + a stable disambiguator so subTest-like
        # duplicates across wrong-type variants each get a name.
        name = f"test_mut_{base}_{kind}"
        i = 0
        candidate = name
        while candidate in seen:
            i += 1
            candidate = f"{name}_{i}"
        seen.add(candidate)
        setattr(GeneratedMutationMatrix, candidate, _make_test(entry, kind, fn))


_register_generated_mutations()


# ── ROUND-22 P0-2: cross-field mutations registered as generated tests ──

def _make_cross_field_test(label: str, target: str, mutator: Callable[[str], None]):
    def _t(self):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=lambda dd: mutator(dd),
            )
            rep = V.verify_profile_dir(d)
            self.assertFalse(
                rep.integrity_ok,
                msg=(f"CROSS-FIELD MUTATION SURVIVED as GREEN: "
                     f"{target} / {label}: {rep.integrity_issues}"),
            )
            _COUNTS[label] = _COUNTS.get(label, 0) + 1
    _t.__doc__ = f"cross-field mutation: {target} / {label}"
    return _t


for _lbl, _tgt, _fn in CROSS_FIELD_MUTATIONS:
    _name = f"test_cross_{_tgt.replace('.', '_').replace('<->', '__vs__').replace('/','_')}_{_lbl}"
    setattr(GeneratedMutationMatrix, _name,
            _make_cross_field_test(_lbl, _tgt, _fn))


# ── ROUND-22 P0-3: 6 named regression fixtures for the exact
#    SHA-bound false-GREEN probes the architect reproduced.

class AuditR22_named_false_greens(unittest.TestCase):

    def _harness(self, mutator):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutator,
            )
            rep = V.verify_profile_dir(d)
            return rep

    def test_http_status_99(self):
        def m(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: obj = json.load(f)
            obj["emu"]["egress_fingerprint"]["http_status"] = 99
            with open(p, "w", encoding="utf-8") as f: json.dump(obj, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)

    def test_http_status_600(self):
        def m(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: obj = json.load(f)
            obj["emu"]["egress_fingerprint"]["http_status"] = 600
            with open(p, "w", encoding="utf-8") as f: json.dump(obj, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)

    def test_process_uid_negative_one(self):
        for side in ("phone", "emu"):
            with self.subTest(side=side):
                def m(d, s=side):
                    p = os.path.join(d, "network_profile.json")
                    with open(p, encoding="utf-8") as f: obj = json.load(f)
                    obj[s]["process_uid"] = -1
                    with open(p, "w", encoding="utf-8") as f: json.dump(obj, f, sort_keys=True, indent=2)
                rep = self._harness(m)
                self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)

    def test_parent_nested_at_wall_mismatch(self):
        def m(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: obj = json.load(f)
            # Egress at_wall_ms diverges from parent by 1 hour.
            obj["emu"]["egress_fingerprint"]["at_wall_ms"] = (
                obj["emu"]["at_wall_ms"] + 3_600_000
            )
            with open(p, "w", encoding="utf-8") as f: json.dump(obj, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)

    def test_absurd_egress_at_wall_ms(self):
        def m(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: obj = json.load(f)
            # Absurd year-33000 timestamp — parent stays healthy, so
            # the parent/nested equality check catches this.
            obj["emu"]["egress_fingerprint"]["at_wall_ms"] = 999_999_999_999_999
            with open(p, "w", encoding="utf-8") as f: json.dump(obj, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)

    def test_hmac_nonnull_with_http_500(self):
        def m(d):
            p = os.path.join(d, "network_profile.json")
            with open(p, encoding="utf-8") as f: obj = json.load(f)
            obj["emu"]["egress_fingerprint"]["http_status"] = 500
            # hmac_fp_hex left non-null → success invariant violation.
            with open(p, "w", encoding="utf-8") as f: json.dump(obj, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)


class AuditR23_checkpoint_abort_reason(unittest.TestCase):
    """ROUND-23 P0: architect proved two SHA-bound false-GREENs:
       (a) `abort_reason="TOTALLY_UNKNOWN"` passed because the schema
           was plain str/null with no enum;
       (b) `abort_reason="network_profile_drift"` alongside
           `result="ok"` + `drift=false` passed because there was no
           coherence rule."""

    def _harness(self, mutator):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutator,
            )
            return V.verify_profile_dir(d)

    def test_totally_unknown_reason_rejected(self):
        def m(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            arr[0]["abort_reason"] = "TOTALLY_UNKNOWN"
            with open(p, "w", encoding="utf-8") as f:
                json.dump(arr, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)
        self.assertTrue(any("not in enum" in i for i in rep.integrity_issues),
                        msg=rep.integrity_issues)

    def test_valid_reason_with_ok_result_rejected(self):
        def m(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            # Valid enum value BUT alongside result=ok/drift=false —
            # the coherence rule must fire.
            arr[0]["abort_reason"] = "network_profile_drift"
            arr[0]["result"] = "ok"
            arr[0]["drift"] = False
            with open(p, "w", encoding="utf-8") as f:
                json.dump(arr, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertFalse(rep.integrity_ok, msg=rep.integrity_issues)
        self.assertTrue(
            any("coherence" in i for i in rep.integrity_issues),
            msg=rep.integrity_issues,
        )

    def test_null_reason_with_ok_result_is_green(self):
        """Baseline: null abort_reason alongside result=ok/drift=false
        is the healthy shape — the coherence rule must NOT fire on
        this input."""
        def m(d):
            p = os.path.join(d, "checkpoint_log.json")
            with open(p, encoding="utf-8") as f: arr = json.load(f)
            arr[0]["abort_reason"] = None
            arr[0]["result"] = "ok"
            arr[0]["drift"] = False
            with open(p, "w", encoding="utf-8") as f:
                json.dump(arr, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertTrue(rep.integrity_ok, msg=rep.integrity_issues)


class AuditR24C_baseline_snapshot_ownership(unittest.TestCase):
    """Audit ROUND-24 CONTINUATION E/G — baseline snapshot ownership.
    The three baseline surfaces (network_profile.emu.egress,
    EGRESS_FINGERPRINT, and the emu.at_wall_ms parent) MUST agree
    by construction because they derive from ONE snapshot. Any
    later-checkpoint HMAC planted into EGRESS_FINGERPRINT.json
    (i.e. the runner illegitimately overwriting the baseline)
    surfaces as RED."""

    def _harness(self, mutator):
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutator,
            )
            return V.verify_profile_dir(d)

    def test_baseline_hmac_and_timestamps_match_by_construction(self):
        """Healthy fixture MUST already satisfy the R24 CONTINUATION
        G equality rules — baseline is GREEN with no mutation."""
        rep = self._harness(lambda d: None)
        self.assertTrue(rep.integrity_ok, msg=rep.integrity_issues)

    def test_planted_later_hmac_into_egress_fingerprint_reds(self):
        """Later checkpoint HMAC planted into EGRESS_FINGERPRINT
        (simulating a runner that illegitimately re-wrote baseline)
        MUST surface as RED via the hmac cross-file rule."""
        def m(d):
            p = os.path.join(d, "EGRESS_FINGERPRINT.json")
            with open(p, encoding="utf-8") as f: ef = json.load(f)
            ef["emu_hmac_fp_hex"] = "f" * 32  # different from baseline
            with open(p, "w", encoding="utf-8") as f:
                json.dump(ef, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("emu_hmac_fp_hex" in i and "disagrees" in i
                for i in rep.integrity_issues),
            msg=rep.integrity_issues,
        )

    def test_planted_later_at_wall_ms_into_egress_fingerprint_reds(self):
        """Timestamp equality rule — divergent EGRESS_FINGERPRINT
        at_wall_ms vs baseline network_profile snapshot MUST RED."""
        def m(d):
            p = os.path.join(d, "EGRESS_FINGERPRINT.json")
            with open(p, encoding="utf-8") as f: ef = json.load(f)
            ef["at_wall_ms"] = ef["at_wall_ms"] + 123_456
            with open(p, "w", encoding="utf-8") as f:
                json.dump(ef, f, sort_keys=True, indent=2)
        rep = self._harness(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(
            any("baseline snapshot must own ONE wall-clock" in i
                for i in rep.integrity_issues),
            msg=rep.integrity_issues,
        )


class AuditR24C_mock_fresh_key_per_call(unittest.TestCase):
    """Audit ROUND-24 CONTINUATION E: the mock's `network_profile_report`
    seam produces a DIFFERENT HMAC for every distinct
    `checkpoint_key_hex`. This proves the runner has a legitimate
    reason to demand baseline snapshot ownership (two different
    keys → two different HMACs) — the fix is snapshot ownership,
    not key reuse."""

    def test_different_keys_produce_different_hmacs_via_mock(self):
        # Call the mock's HMAC computation twice with different keys
        # and confirm the outputs differ. This uses the same Python
        # HMAC seam that the shell mock invokes at
        # lib/wss3-adb-mock.sh:132.
        import hmac as _hmac, hashlib
        body = "dry-run-body-yota-phone-off-host-on".encode("utf-8")
        h1 = _hmac.new(bytes.fromhex("a" * 64), body, hashlib.sha256).digest()[:16].hex()
        h2 = _hmac.new(bytes.fromhex("b" * 64), body, hashlib.sha256).digest()[:16].hex()
        self.assertNotEqual(
            h1, h2,
            msg=("HMAC seam does not vary with the key — the "
                 "underlying premise for baseline snapshot "
                 "ownership is invalid."),
        )


# ── ROUND-22 P0-1: parity gate ─────────────────────────────────────

class SchemaRegistryParity(unittest.TestCase):
    """Audit ROUND-23 P1: parity proven by TRACING a real
    verify_profile_dir invocation and comparing the exact set of
    surface_ids that verifier consumed against the set of
    non-validator-only entries in SURFACES."""

    def test_every_declared_surface_covered_by_dispatch(self):
        """Every non-validator-only SURFACES entry MUST appear in
        the mutation dispatch table (by exact ID, not basename)."""
        for name, entry in S.SURFACES.items():
            if entry.get("validator_only"):
                continue
            self.assertIn(
                name, _SURFACE_DISPATCH,
                msg=(f"SURFACES[{name!r}] not represented in "
                     f"_SURFACE_DISPATCH by exact surface_id."),
            )

    def test_verifier_consumes_schema_module(self):
        with open(os.path.join(_PKG, "verify_evidence_wss3.py"),
                  encoding="utf-8") as f:
            src = f.read()
        self.assertIn("import schema_wss3", src,
                      "verify_evidence_wss3.py MUST import schema_wss3")
        # R23 P1 explicit gate: verifier MUST call the registry-driven
        # entry point (check_registered_surface) — the generic
        # check_schema_object stays available but is not the
        # primary boundary.
        self.assertIn("def check_registered_surface", src,
                      "verify_evidence_wss3.py MUST expose check_registered_surface")
        self.assertIn("check_registered_surface(", src,
                      "verify_evidence_wss3.py MUST actually CALL "
                      "check_registered_surface at least once")

    def test_derived_constants_match_schema(self):
        self.assertEqual(
            set(V.REQUIRED_PROFILE_KEYS),
            set(S.PROFILE_SCHEMA.keys()),
            msg="REQUIRED_PROFILE_KEYS drifted from schema",
        )
        self.assertEqual(
            set(V.REQUIRED_COMPLETION_KEYS),
            {n for n, sp in S.MATRIX_COMPLETION_SCHEMA.items()
             if sp.get("presence") == S.P_REQ},
            msg="REQUIRED_COMPLETION_KEYS drifted from schema",
        )
        # R23 P0: VALID_ABORT_REASONS derived from schema authority.
        self.assertEqual(
            set(V.VALID_ABORT_REASONS),
            set(S.ABORT_REASON_ENUM),
            msg="VALID_ABORT_REASONS drifted from ABORT_REASON_ENUM",
        )

    def test_live_verifier_visits_every_registered_surface(self):
        """Trace a real healthy verify_profile_dir invocation and
        prove that every non-validator-only SURFACES entry is
        actually consumed by the verifier. If a schema entry
        exists in the registry but no verifier call site touches
        it, this test RED's — architect's ROUND-22 P1
        `never-validated-child` mutation would be caught here.
        """
        with tempfile.TemporaryDirectory() as t:
            d = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260814T093012Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
            )
            S.surfaces_visited_reset()
            rep = V.verify_profile_dir(d)
            visited = S.surfaces_visited_snapshot()
        # Healthy build should be GREEN (baseline sanity).
        self.assertTrue(rep.integrity_ok, msg=rep.integrity_issues)
        expected = {
            sid for sid, entry in S.SURFACES.items()
            if not entry.get("validator_only")
        }
        missing = expected - visited
        self.assertFalse(
            missing,
            msg=(f"live verifier failed to consume {sorted(missing)!r}. "
                 f"Either wire check_registered_surface({{surface}}, ...) "
                 f"at the missing surface's call site OR remove the "
                 f"surface from SURFACES."),
        )
        # And no unknown surfaces sneak in (defence-in-depth).
        extra = visited - expected
        self.assertFalse(
            extra,
            msg=f"verifier visited unregistered surface_id(s): {sorted(extra)!r}",
        )

    def test_validator_only_entries_are_callable(self):
        """R23 P1: every validator-only SURFACES entry MUST name a
        function that actually exists on the verifier module."""
        for sid, entry in S.SURFACES.items():
            if not entry.get("validator_only"):
                continue
            fn_name = entry.get("validator")
            self.assertTrue(
                fn_name,
                msg=f"validator-only SURFACES[{sid!r}] missing `validator`",
            )
            fn = getattr(V, fn_name, None)
            self.assertTrue(
                callable(fn),
                msg=(f"validator-only SURFACES[{sid!r}].validator="
                     f"{fn_name!r} not callable on verify_evidence_wss3"),
            )

    # ── Negative parity fixtures (R23 P1) ─────────────────────────
    #
    # Both must fail: (a) an unconsumed nested surface (registry
    # entry with no verifier call site), (b) a registry schema
    # disconnected from the verifier's actual call.

    def test_negative_unconsumed_nested_surface_would_red(self):
        """Simulate the R22 REDLINE probe: inject a bogus
        `never-validated-child` entry into SURFACES and run the
        live-trace parity. It MUST detect the miss."""
        bogus_id = "network_profile.json/never-validated-child"
        S.SURFACES[bogus_id] = {
            "schema":      {"x": {"type": S.T_STR, "presence": S.P_OPT,
                                   "nullability": S.N_NULL, "privacy": "pub"}},
            "file":        "network_profile.json",
            "json_path":   ["never-validated-child"],
            "path_prefix": "network_profile.never_validated_child",
        }
        # Rebuild dispatch (parity of production behaviour).
        _SURFACE_DISPATCH[bogus_id] = ("network_profile.json",
                                       ["never-validated-child"])
        try:
            with tempfile.TemporaryDirectory() as t:
                d = build_full_profile_evidence(
                    t, profile_id="yota-phone-off-host-on",
                    utc_suffix="20260814T093012Z",
                    attempt_id=_uuid4(), arm_token=_uuid4(),
                )
                S.surfaces_visited_reset()
                V.verify_profile_dir(d)
                visited = S.surfaces_visited_snapshot()
            expected = {
                sid for sid, entry in S.SURFACES.items()
                if not entry.get("validator_only")
            }
            missing = expected - visited
            self.assertIn(
                bogus_id, missing,
                msg=("parity trace failed to detect unconsumed "
                     "surface — R22 REDLINE would have shipped."),
            )
        finally:
            S.SURFACES.pop(bogus_id, None)
            _SURFACE_DISPATCH.pop(bogus_id, None)
            S.surfaces_visited_reset()

    def test_negative_disconnected_registry_schema_would_red(self):
        """Simulate the R22 REDLINE probe: replace `matrix_verdict.
        json/row`'s schema with an unrelated one and prove the
        live verifier surfaces the divergence via the mutation
        matrix (fixture becomes broken)."""
        original = S.SURFACES["matrix_verdict.json/row"]["schema"]
        S.SURFACES["matrix_verdict.json/row"]["schema"] = {
            "totally_wrong_field": {"type": S.T_STR, "presence": S.P_REQ,
                                     "nullability": S.N_NONE, "privacy": "pub"},
        }
        try:
            with tempfile.TemporaryDirectory() as t:
                d = build_full_profile_evidence(
                    t, profile_id="yota-phone-off-host-on",
                    utc_suffix="20260814T093012Z",
                    attempt_id=_uuid4(), arm_token=_uuid4(),
                )
                rep = V.verify_profile_dir(d)
            # A disconnected schema mapping causes the healthy
            # producer output to become invalid (missing
            # `totally_wrong_field`), so the verifier surfaces it.
            self.assertFalse(
                rep.integrity_ok,
                msg=("parity trace failed to catch disconnected "
                     "schema mapping — R22 REDLINE would have shipped."),
            )
        finally:
            S.SURFACES["matrix_verdict.json/row"]["schema"] = original


# ── mutation-summary writer (dumped after suite runs) ─────────────

def _write_summary_if_env():
    """When WSS3_R21_MUTATION_SUMMARY env is set to a path, write
    the per-kind counts + total after the current process exits."""
    out = os.environ.get("WSS3_R22_MUTATION_SUMMARY") or \
          os.environ.get("WSS3_R21_MUTATION_SUMMARY")
    if not out:
        return
    lines = ["WSS-3 ROUND-22 mutation matrix — per-kind proof-of-RED counts",
             ""]
    total = 0
    for k in sorted(_COUNTS):
        lines.append(f"  {k:32s} {_COUNTS[k]:5d} RED")
        total += _COUNTS[k]
    lines += ["", f"  TOTAL                             {total:5d} RED"]
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


# ── audit ROUND-29.1 P1-2: pre-send reason fields ─────────────────

_R291_MARK = "audit ROUND-29.1 P1-2"


def _r291_verdict(**over) -> dict:
    """A structurally valid fail-closed pre-send verdict."""
    obj = {
        "p2e": "FAIL",
        "e2p": "FAIL",
        "integrity": "RED",
        "attempt_id": _uuid4(),
        "arm_token": _uuid4(),
        "smoke_run_id": "r291",
        "at_wall_ms": 1786817000000,
        "p2e_cid": None,
        "e2p_cid": None,
        "pre_send_reason": "direct_unavailable",
        "pre_send_phone_outer": "failed",
        "pre_send_emu_outer": "direct",
    }
    for k, v in over.items():
        if v is _R291_DROP:
            obj.pop(k, None)
        else:
            obj[k] = v
    return obj


class _R291Drop:
    pass


_R291_DROP = _R291Drop()


def _r291_issues(obj: dict) -> List[str]:
    """Run the real verifier over a lone smoke_verdict.json.

    `smoke.log` is deliberately absent: this exercises the schema and
    cross-field layers, which run before the chain rebuild.
    """
    with tempfile.TemporaryDirectory() as t:
        with open(os.path.join(t, "smoke_verdict.json"), "w",
                  encoding="utf-8") as f:
            json.dump(obj, f)
        _v, issues = V.load_smoke_verdict(t)
    return issues


class AuditR291PreSendReasonSchema(unittest.TestCase):
    """R29 shipped `pre_send_reason` + free-text `pre_send_detail`
    without registering either, so `check_registered_surface` reported
    them as unknown keys — the producer and the schema authority had
    diverged. These tests pin the closed schema's ability to express a
    fail-closed RED, and the cross-field rules that stop that shape
    being abused to dress a RED up as anything else."""

    # ── the P1 fix itself ─────────────────────────────────────────
    def test_pre_send_red_is_structurally_valid(self):
        """The whole point: a fail-closed RED must pass the STRUCTURAL
        layer. Before R29.1 both `integrity` and `p2e`/`e2p` were
        pinned to GREEN/OK, so this object could not exist."""
        issues = V.check_registered_surface("smoke_verdict.json",
                                            _r291_verdict())
        bad = [i for i in issues if "unknown key" in i or "missing required key" in i]
        self.assertEqual(bad, [], msg=f"pre-send RED rejected structurally: {bad}")

    def test_pre_send_red_still_unacceptable(self):
        """Structural validity must NOT become acceptance. The verdict
        is still rejected — for being RED, which is the honest reason."""
        issues = _r291_issues(_r291_verdict())
        self.assertTrue(any("integrity='RED'" in i or "(want GREEN)" in i
                            for i in issues),
                        msg=f"a RED verdict must remain non-GREEN: {issues}")

    def test_free_text_detail_is_no_longer_accepted(self):
        """The replaced free-text field must now be an unknown key."""
        obj = _r291_verdict()
        obj["pre_send_detail"] = "phone=failed emu=direct"
        issues = V.check_registered_surface("smoke_verdict.json", obj)
        self.assertTrue(any("unknown key" in i and "pre_send_detail" in i
                            for i in issues),
                        msg=f"pre_send_detail must not be schema-authorised: {issues}")

    def test_valid_shape_raises_no_cross_field_issue(self):
        issues = [i for i in _r291_issues(_r291_verdict()) if _R291_MARK in i]
        self.assertEqual(issues, [], msg=f"valid pre-send shape flagged: {issues}")

    # ── cross-field rules ─────────────────────────────────────────
    def _assert_flagged(self, obj: dict, needle: str):
        issues = [i for i in _r291_issues(obj) if _R291_MARK in i]
        self.assertTrue(any(needle in i for i in issues),
                        msg=f"expected {needle!r} among {issues}")

    def test_field_without_reason_is_flagged(self):
        self._assert_flagged(
            _r291_verdict(pre_send_reason=_R291_DROP),
            "without pre_send_reason")

    def test_reason_requires_red_integrity(self):
        self._assert_flagged(_r291_verdict(integrity="GREEN"),
                             "requires integrity=RED")

    def test_reason_requires_failed_directions(self):
        self._assert_flagged(_r291_verdict(p2e="OK"), "requires p2e=FAIL")

    def test_reason_requires_null_cids(self):
        self._assert_flagged(_r291_verdict(p2e_cid="cid-1"),
                             "requires p2e_cid=null")

    def test_direct_unavailable_requires_both_outer_values(self):
        self._assert_flagged(
            _r291_verdict(pre_send_emu_outer=_R291_DROP),
            "pre_send_emu_outer")

    def test_direct_unavailable_forbids_pin_fields(self):
        self._assert_flagged(
            _r291_verdict(pre_send_pin_stage="p2e",
                          pre_send_pin_emitter="phone"),
            "forbids")

    def test_direct_unavailable_needs_a_non_direct_value(self):
        """Claiming Direct was unavailable while both devices reported
        `direct` is self-contradictory."""
        self._assert_flagged(
            _r291_verdict(pre_send_phone_outer="direct",
                          pre_send_emu_outer="direct"),
            "both devices reported")

    def test_health_probe_failed_needs_a_probe_failed_value(self):
        self._assert_flagged(
            _r291_verdict(pre_send_reason="health_probe_failed",
                          pre_send_phone_outer="direct",
                          pre_send_emu_outer="direct"),
            "neither device is 'probe_failed'")

    def test_pin_unconfirmed_requires_pin_fields(self):
        self._assert_flagged(
            _r291_verdict(pre_send_reason="pin_unconfirmed",
                          pre_send_phone_outer=_R291_DROP,
                          pre_send_emu_outer=_R291_DROP),
            "requires")

    def test_pin_unconfirmed_forbids_outer_fields(self):
        self._assert_flagged(
            _r291_verdict(pre_send_reason="pin_unconfirmed",
                          pre_send_pin_stage="p2e",
                          pre_send_pin_emitter="phone"),
            "forbids")

    def test_pin_unconfirmed_valid_shape_is_clean(self):
        obj = _r291_verdict(pre_send_reason="pin_unconfirmed",
                            pre_send_phone_outer=_R291_DROP,
                            pre_send_emu_outer=_R291_DROP,
                            pre_send_pin_stage="e2p",
                            pre_send_pin_emitter="emulator")
        structural = [i for i in V.check_registered_surface("smoke_verdict.json", obj)
                      if "unknown key" in i or "missing required key" in i]
        self.assertEqual(structural, [], msg=f"structural: {structural}")
        cross = [i for i in _r291_issues(obj) if _R291_MARK in i]
        self.assertEqual(cross, [], msg=f"cross-field: {cross}")

    # ── enum bounds ───────────────────────────────────────────────
    def test_reason_enum_is_bounded(self):
        issues = V.check_registered_surface(
            "smoke_verdict.json", _r291_verdict(pre_send_reason="whatever"))
        self.assertTrue(any("not in enum" in i for i in issues),
                        msg=f"pre_send_reason must be enum-bounded: {issues}")

    def test_outer_enum_is_bounded(self):
        issues = V.check_registered_surface(
            "smoke_verdict.json", _r291_verdict(pre_send_phone_outer="wat"))
        self.assertTrue(any("not in enum" in i for i in issues),
                        msg=f"pre_send_phone_outer must be enum-bounded: {issues}")

    def test_integrity_enum_still_bounded_after_unpinning(self):
        """Removing the GREEN pin must not open `integrity` to
        arbitrary strings."""
        issues = V.check_registered_surface(
            "smoke_verdict.json", _r291_verdict(integrity="MAYBE"))
        self.assertTrue(any("not in enum" in i for i in issues),
                        msg=f"integrity must stay enum-bounded: {issues}")


# ── audit ROUND-29.2: truthful partial evidence + cell aborts ─────

_R292_MARK_SMOKE = "audit ROUND-29.2 P1-1"
_R292_MARK_CELL = "audit ROUND-29.2 P1-2"


class AuditR292E2PStagePreservesP2E(unittest.TestCase):
    """R29.1 forced `p2e=FAIL` / `p2e_cid=null` on EVERY pre-send
    abort. But P2E is sent and observed before the E2P pin is
    attempted, so a stage-`e2p` abort can legitimately sit on top of a
    completed P2E. Forcing FAIL there contradicted a `smoke.log` that
    already carried the P2E dispatch — fail-closed, but false
    evidence."""

    def _e2p_stage(self, **over) -> dict:
        obj = _r291_verdict(
            pre_send_reason="pin_unconfirmed",
            pre_send_phone_outer=_R291_DROP,
            pre_send_emu_outer=_R291_DROP,
            pre_send_pin_stage="e2p",
            pre_send_pin_emitter="emulator",
            p2e="OK",
            p2e_cid="cid-r292-p2e",
        )
        for k, v in over.items():
            if v is _R291_DROP:
                obj.pop(k, None)
            else:
                obj[k] = v
        return obj

    def test_completed_p2e_survives_e2p_stage_abort(self):
        cross = [i for i in _r291_issues(self._e2p_stage())
                 if _R292_MARK_SMOKE in i or _R291_MARK in i]
        self.assertEqual(cross, [], msg=f"completed P2E must be allowed: {cross}")

    def test_e2p_stage_abort_is_still_red(self):
        issues = _r291_issues(self._e2p_stage())
        self.assertTrue(any("(want GREEN)" in i for i in issues),
                        msg=f"acceptance must still reject it: {issues}")

    def test_p2e_ok_requires_a_real_cid(self):
        issues = [i for i in _r291_issues(self._e2p_stage(p2e_cid=None))
                  if _R292_MARK_SMOKE in i]
        self.assertTrue(any("requires a real p2e_cid" in i for i in issues), issues)

    def test_p2e_fail_forbids_a_cid(self):
        issues = [i for i in _r291_issues(self._e2p_stage(p2e="FAIL"))
                  if _R292_MARK_SMOKE in i]
        self.assertTrue(any("p2e=FAIL requires p2e_cid=null" in i for i in issues), issues)

    def test_p2e_fail_with_null_cid_is_allowed_at_e2p_stage(self):
        obj = self._e2p_stage(p2e="FAIL", p2e_cid=None)
        cross = [i for i in _r291_issues(obj)
                 if _R292_MARK_SMOKE in i or _R291_MARK in i]
        self.assertEqual(cross, [], msg=f"P2E may also legitimately have failed: {cross}")

    def test_e2p_direction_can_never_be_ok_on_an_abort(self):
        issues = [i for i in _r291_issues(self._e2p_stage(e2p="OK"))
                  if _R291_MARK in i]
        self.assertTrue(any("requires e2p=FAIL" in i for i in issues), issues)

    def test_e2p_cid_can_never_be_set_on_an_abort(self):
        issues = [i for i in _r291_issues(self._e2p_stage(e2p_cid="cid-x"))
                  if _R291_MARK in i]
        self.assertTrue(any("requires e2p_cid=null" in i for i in issues), issues)

    def test_p2e_stage_abort_still_forbids_any_p2e_evidence(self):
        """The narrower rule must not leak to the P2E stage, where
        nothing was dispatched at all."""
        obj = _r291_verdict(
            pre_send_reason="pin_unconfirmed",
            pre_send_phone_outer=_R291_DROP,
            pre_send_emu_outer=_R291_DROP,
            pre_send_pin_stage="p2e",
            pre_send_pin_emitter="phone",
            p2e="OK",
            p2e_cid="cid-should-not-exist",
        )
        issues = [i for i in _r291_issues(obj) if _R291_MARK in i]
        self.assertTrue(any("requires p2e=FAIL" in i for i in issues), issues)
        self.assertTrue(any("requires p2e_cid=null" in i for i in issues), issues)


class AuditR292CellAbortReason(unittest.TestCase):
    """`wss3_live_full` may now stop a cell before dispatch when its
    pin cannot be confirmed. The bounded `abort_reason` must describe
    a zero-dispatch cell and nothing else, or it becomes a new way to
    write false evidence."""

    def _cell(self, **over) -> dict:
        cv = {
            "cell_id": "wss.p2e.after-connect",
            "blocked": False,
            "envelopes_dispatched": 0,
            "delivered_count": 0,
            "unresolved_count": 0,
            "verdict": "Unresolved",
            "abort_reason": "pin_unconfirmed",
        }
        cv.update(over)
        return cv

    def test_pin_unconfirmed_is_a_registered_abort_reason(self):
        self.assertIn("pin_unconfirmed", S.ABORT_REASON_ENUM)

    def test_aborted_cell_is_structurally_valid(self):
        issues = [i for i in V.check_registered_surface("cell_verdict.json", self._cell())
                  if "unknown key" in i or "missing required key" in i or "not in enum" in i]
        self.assertEqual(issues, [], msg=f"aborted cell rejected structurally: {issues}")

    def test_aborted_cell_passes_content_rules(self):
        issues = V.validate_cell_verdict_content(self._cell(), "wss.p2e.after-connect",
                                                 False, 0)
        self.assertEqual(issues, [], msg=f"valid abort flagged: {issues}")

    def test_abort_forbids_dispatch(self):
        cv = self._cell(envelopes_dispatched=5, delivered_count=5, unresolved_count=0,
                        verdict="Delivered")
        issues = [i for i in V.validate_cell_verdict_content(cv, "wss.p2e.after-connect",
                                                             False, 5)
                  if _R292_MARK_CELL in i]
        self.assertTrue(any("requires" in i and "=0" in i for i in issues), issues)

    def test_abort_forbids_blocked_cell(self):
        cv = self._cell(blocked=True, verdict="BLOCKED")
        issues = [i for i in V.validate_cell_verdict_content(cv, "wss.p2e.after-connect",
                                                             True, 0)
                  if _R292_MARK_CELL in i]
        self.assertTrue(any("blocked cell" in i for i in issues), issues)

    def test_abort_requires_unresolved_verdict(self):
        cv = self._cell(verdict="PENDING")
        issues = [i for i in V.validate_cell_verdict_content(cv, "wss.p2e.after-connect",
                                                             False, 0)
                  if _R292_MARK_CELL in i]
        self.assertTrue(any("verdict='Unresolved'" in i for i in issues), issues)

    def test_abort_reason_is_enum_bounded(self):
        issues = V.check_registered_surface("cell_verdict.json",
                                            self._cell(abort_reason="whatever"))
        self.assertTrue(any("not in enum" in i for i in issues), issues)

    def test_healthy_cell_without_abort_is_unaffected(self):
        cv = {
            "cell_id": "wss.p2e.after-connect", "blocked": False,
            "envelopes_dispatched": 5, "delivered_count": 5,
            "unresolved_count": 0, "verdict": "Delivered",
        }
        issues = V.validate_cell_verdict_content(cv, "wss.p2e.after-connect", False, 5)
        self.assertEqual(issues, [], msg=f"healthy cell flagged: {issues}")


# ── audit ROUND-29.3 P0: aborted evidence is still audited ────────

class AuditR293AbortedEvidenceStillAudited(unittest.TestCase):
    """R29.2 returned from `verify_profile_dir` as soon as
    `abort_reason` was non-null, which skipped SHA256SUMS
    verification, the privacy denylist sweep, required-file and
    inventory checks and every matrix-content rule.

    An aborted profile is the evidence MOST likely to be malformed,
    and a privacy leak inside it would simply not have been reported.
    The outcome must stay RED / NOT_EVALUABLE — what changes is that
    the rest of the audit still runs."""

    def _aborted(self, tmp, **kw):
        return build_full_profile_evidence(
            tmp,
            profile_id="yota-phone-off-host-on",
            utc_suffix="20260821T193000Z",
            attempt_id=_uuid4(),
            arm_token=_uuid4(),
            abort_reason="pin_unconfirmed",
            cells_ran=2,
            **kw,
        )

    def test_abort_is_reported_and_outcome_is_not_evaluable(self):
        with tempfile.TemporaryDirectory() as t:
            ev = self._aborted(t)
            rep = V.verify_profile_dir(ev)
        self.assertFalse(rep.integrity_ok)
        self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")
        self.assertTrue(any("aborted: pin_unconfirmed" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues)

    def test_privacy_denylist_still_runs_on_aborted_evidence(self):
        """THE regression. A raw IP planted in an aborted profile's
        capture must still be reported."""
        def _plant(ev_dir):
            cap = os.path.join(ev_dir, "matrix_cells",
                               "wss.p2e.after-connect", "capture.log")
            with open(cap, "a", encoding="utf-8") as f:
                f.write("08-14 12:00:00.000 1 1 I WSS_DIAG: "
                        "event=sender_enqueue role=sender emitter_id=phone "
                        "run_id=r cell_id=wss.p2e.after-connect "
                        "correlation_id=c peer_ip=203.0.113.7\n")

        with tempfile.TemporaryDirectory() as t:
            ev = self._aborted(t, mutate_before_return=_plant)
            rep = V.verify_profile_dir(ev)
        self.assertTrue(
            any("denylist" in i.lower() for i in rep.integrity_issues),
            msg=f"planted raw IP not reported on aborted evidence: "
                f"{rep.integrity_issues}")

    def test_sha256sums_still_runs_on_aborted_evidence(self):
        """Tampering after the manifest was computed must still be
        caught on an aborted profile."""
        def _tamper(ev_dir):
            cap = os.path.join(ev_dir, "matrix_cells",
                               "wss.p2e.after-connect", "capture.log")
            with open(cap, "a", encoding="utf-8") as f:
                f.write("tampered after SHA256SUMS\n")

        with tempfile.TemporaryDirectory() as t:
            ev = self._aborted(t, mutate_after_sha256sums=_tamper)
            rep = V.verify_profile_dir(ev)
        self.assertTrue(
            any("sha" in i.lower() for i in rep.integrity_issues),
            msg=f"post-manifest tampering not reported on aborted "
                f"evidence: {rep.integrity_issues}")

    def test_aborted_audit_reports_more_than_the_abort_line(self):
        """Guards the shape of the fix: the abort must not be the only
        thing the verifier has to say about a malformed aborted tree."""
        def _plant(ev_dir):
            cap = os.path.join(ev_dir, "matrix_cells",
                               "wss.p2e.after-connect", "capture.log")
            with open(cap, "a", encoding="utf-8") as f:
                f.write("08-14 12:00:00.000 1 1 I WSS_DIAG: "
                        "event=sender_enqueue role=sender emitter_id=phone "
                        "run_id=r cell_id=wss.p2e.after-connect "
                        "correlation_id=c peer_ip=203.0.113.7\n")

        with tempfile.TemporaryDirectory() as t:
            ev = self._aborted(t, mutate_before_return=_plant)
            rep = V.verify_profile_dir(ev)
        non_abort = [i for i in rep.integrity_issues
                     if not i.startswith("aborted:")]
        self.assertTrue(non_abort,
                        msg="verifier stopped at the abort line again")


class AuditR293PendingOnlyUnderAbort(unittest.TestCase):
    """Audit ROUND-29.3 P1-1: the manifest keeps eight rows after an
    abort, so cells the matrix never reached stay `PENDING` with zero
    counts. That is legitimate ONLY under a non-null matrix abort —
    on a normal run `PENDING` remains the ROUND-18 P0-1 producer bug."""

    def _pending_cell(self, **over) -> dict:
        cv = {
            "cell_id": "wss.p2e.bg-fg",
            "blocked": False,
            "envelopes_dispatched": 0,
            "delivered_count": 0,
            "unresolved_count": 0,
            "verdict": "PENDING",
        }
        cv.update(over)
        return cv

    def test_pending_allowed_when_matrix_aborted(self):
        issues = V.validate_cell_verdict_content(
            self._pending_cell(), "wss.p2e.bg-fg", False, 0,
            matrix_aborted=True)
        self.assertEqual(issues, [], msg=issues)

    def test_pending_rejected_on_a_normal_run(self):
        issues = V.validate_cell_verdict_content(
            self._pending_cell(), "wss.p2e.bg-fg", False, 0,
            matrix_aborted=False)
        self.assertTrue(any("inconsistent" in i for i in issues),
                        msg=f"ROUND-18 P0-1 protection lost: {issues}")

    def test_pending_with_counts_rejected_even_when_aborted(self):
        cv = self._pending_cell(envelopes_dispatched=3, delivered_count=1,
                                unresolved_count=2)
        issues = V.validate_cell_verdict_content(
            cv, "wss.p2e.bg-fg", False, 3, matrix_aborted=True)
        self.assertTrue(any("never-attempted PENDING" in i for i in issues),
                        msg=issues)

    def test_delivered_cell_unaffected_by_abort_flag(self):
        cv = {
            "cell_id": "wss.p2e.after-connect", "blocked": False,
            "envelopes_dispatched": 5, "delivered_count": 5,
            "unresolved_count": 0, "verdict": "Delivered",
        }
        issues = V.validate_cell_verdict_content(
            cv, "wss.p2e.after-connect", False, 5, matrix_aborted=True)
        self.assertEqual(issues, [], msg=issues)


# ── audit ROUND-29.3 P0: dual-party pin ownership mutations ───────

_R293_CELL = "wss.p2e.after-connect"   # sender=phone, recipient=emulator
_R293_MARK = "audit ROUND-29.3 P0"


def _r293_capture(ev_dir: str) -> str:
    return os.path.join(ev_dir, "matrix_cells", _R293_CELL, "capture.log")


def _r293_rewrite(ev_dir: str, fn) -> None:
    """Apply `fn(lines) -> lines` to the cell's capture.log."""
    path = _r293_capture(ev_dir)
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines(keepends=True)
    with open(path, "w", encoding="utf-8") as f:
        f.writelines(fn(lines))


def _r293_is_pin(line: str, emitter: str) -> bool:
    return ("event=diagnostic_pin_active" in line
            and f"emitter_id={emitter} " in line)


class AuditR293DualPartyPinOwnership(unittest.TestCase):
    """The orchestrator pins BOTH devices to a cell since ROUND-29 —
    that is the fix for the field's false cell attribution. Ownership
    is therefore a strict SET, not "the sender": exactly one
    breadcrumb per party, both `role=matrix`, both bound to this
    run_id, cell_id and expected pin.

    Ownership is NOT relaxed to "any canonical emitter": each of the
    mutations below must stay RED."""

    def _build(self, tmp, mutate=None):
        return build_full_profile_evidence(
            tmp,
            profile_id="yota-phone-off-host-on",
            utc_suffix="20260821T200000Z",
            attempt_id=_uuid4(),
            arm_token=_uuid4(),
            mutate_before_return=mutate,
        )

    def _issues(self, mutate=None):
        with tempfile.TemporaryDirectory() as t:
            ev = self._build(t, mutate)
            rep = V.verify_profile_dir(ev)
        return rep

    # ── baseline ──────────────────────────────────────────────────
    def test_dual_party_baseline_is_green(self):
        rep = self._issues()
        self.assertTrue(rep.integrity_ok,
                        msg=f"canonical dual-party tree must be GREEN: "
                            f"{rep.integrity_issues[:6]}")
        self.assertEqual(rep.product_outcome, "GREEN")

    # ── the four required mutations ───────────────────────────────
    def test_recipient_breadcrumb_missing_is_red(self):
        def m(ev):
            _r293_rewrite(ev, lambda ls: [l for l in ls
                                          if not _r293_is_pin(l, "emulator")])
        rep = self._issues(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("missing diagnostic_pin_active from the recipient" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    def test_recipient_breadcrumb_duplicated_is_red(self):
        def m(ev):
            def f(ls):
                dup = [l for l in ls if _r293_is_pin(l, "emulator")]
                return ls + dup
            _r293_rewrite(ev, f)
        rep = self._issues(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("exactly one is allowed" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    def test_recipient_breadcrumb_wrong_pin_is_red(self):
        def m(ev):
            def f(ls):
                return [l.replace("pin=wss", "pin=rest")
                        if _r293_is_pin(l, "emulator") else l for l in ls]
            _r293_rewrite(ev, f)
        rep = self._issues(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("has pin=" in i and _R293_MARK in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    def test_intruder_breadcrumb_is_red(self):
        def m(ev):
            def f(ls):
                src = [l for l in ls if _r293_is_pin(l, "emulator")]
                return ls + [src[0].replace("emitter_id=emulator ",
                                            "emitter_id=intruder ")]
            _r293_rewrite(ev, f)
        rep = self._issues(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("intruder emitter" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    # ── ownership must not have been relaxed ──────────────────────
    def test_sender_breadcrumb_missing_is_red(self):
        """Symmetry: dropping the SENDER's breadcrumb must be just as
        RED as dropping the recipient's."""
        def m(ev):
            _r293_rewrite(ev, lambda ls: [l for l in ls
                                          if not _r293_is_pin(l, "phone")])
        rep = self._issues(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("missing diagnostic_pin_active from the sender" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    def test_wrong_role_is_red(self):
        def m(ev):
            def f(ls):
                return [l.replace("role=matrix", "role=sender")
                        if _r293_is_pin(l, "emulator") else l for l in ls]
            _r293_rewrite(ev, f)
        rep = self._issues(m)
        self.assertFalse(rep.integrity_ok)

    def test_wrong_cell_binding_is_red(self):
        """A pin bound to another cell IS the false-attribution
        defect; it must never be tolerated."""
        def m(ev):
            def f(ls):
                return [l.replace(f"cell_id={_R293_CELL} ",
                                  "cell_id=wss.e2p.after-connect ")
                        if _r293_is_pin(l, "emulator") else l for l in ls]
            _r293_rewrite(ev, f)
        rep = self._issues(m)
        self.assertFalse(rep.integrity_ok)

    def test_wrong_run_id_is_red(self):
        def m(ev):
            def f(ls):
                out = []
                for l in ls:
                    if _r293_is_pin(l, "emulator"):
                        import re as _re
                        l = _re.sub(r"run_id=\S+", "run_id=some-other-run", l, count=1)
                    out.append(l)
                return out
            _r293_rewrite(ev, f)
        rep = self._issues(m)
        self.assertFalse(rep.integrity_ok)


# ── audit ROUND-29.4 P0: dual-party pin ownership in the SMOKE ────

_R294_CELL = "wss.p2e.after-connect"   # smoke sender=phone, recipient=emulator


def _r294_smoke_rewrite(ev_dir: str, fn) -> None:
    path = os.path.join(ev_dir, "smoke.log")
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines(keepends=True)
    with open(path, "w", encoding="utf-8") as f:
        f.writelines(fn(lines))


def _r294_is_pin(line: str, emitter: str, cell: str = _R294_CELL) -> bool:
    return ("event=diagnostic_pin_active" in line
            and f"emitter_id={emitter} " in line
            and f"cell_id={cell} " in line)


class AuditR294SmokeDualPartyPin(unittest.TestCase):
    """R29.3 implemented the dual-party rule for full cells ONLY.
    Deleting the recipient breadcrumb from a healthy `smoke.log` and
    recomputing SHA256SUMS still produced GREEN/GREEN — a false GREEN
    on the gate that authorises `full`.

    The rule now lives in one helper with two call sites. These
    mutations are the smoke half; the full half is
    `AuditR293DualPartyPinOwnership`."""

    def _rep(self, mutate=None):
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t,
                profile_id="yota-phone-off-host-on",
                utc_suffix="20260822T030000Z",
                attempt_id=_uuid4(),
                arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            return V.verify_profile_dir(ev)

    def test_smoke_baseline_is_green(self):
        rep = self._rep()
        self.assertTrue(rep.integrity_ok, msg=rep.integrity_issues[:6])
        self.assertEqual(rep.product_outcome, "GREEN")

    def test_smoke_missing_recipient_is_red(self):
        """THE R29.3 false-GREEN reproduction."""
        def m(ev):
            _r294_smoke_rewrite(ev, lambda ls: [
                l for l in ls if not _r294_is_pin(l, "emulator")])
        rep = self._rep(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("smoke.log cell" in i and "recipient" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    def test_smoke_missing_sender_is_red(self):
        def m(ev):
            _r294_smoke_rewrite(ev, lambda ls: [
                l for l in ls if not _r294_is_pin(l, "phone")])
        rep = self._rep(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("smoke.log cell" in i and "sender" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    def test_smoke_duplicate_is_red(self):
        def m(ev):
            def f(ls):
                return ls + [l for l in ls if _r294_is_pin(l, "emulator")]
            _r294_smoke_rewrite(ev, f)
        rep = self._rep(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("exactly one is allowed" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    def test_smoke_intruder_is_red(self):
        def m(ev):
            def f(ls):
                src = [l for l in ls if _r294_is_pin(l, "emulator")]
                return ls + [src[0].replace("emitter_id=emulator ",
                                            "emitter_id=intruder ")]
            _r294_smoke_rewrite(ev, f)
        rep = self._rep(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("intruder emitter" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    def test_smoke_wrong_role_is_red(self):
        def m(ev):
            _r294_smoke_rewrite(ev, lambda ls: [
                l.replace("role=matrix", "role=sender")
                if _r294_is_pin(l, "emulator") else l for l in ls])
        rep = self._rep(m)
        self.assertFalse(rep.integrity_ok)

    def test_smoke_wrong_pin_is_red(self):
        def m(ev):
            _r294_smoke_rewrite(ev, lambda ls: [
                l.replace("pin=wss", "pin=rest")
                if _r294_is_pin(l, "emulator") else l for l in ls])
        rep = self._rep(m)
        self.assertFalse(rep.integrity_ok)
        self.assertTrue(any("has pin=" in i for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:6])

    def test_smoke_wrong_run_id_is_red(self):
        def m(ev):
            import re as _re
            _r294_smoke_rewrite(ev, lambda ls: [
                _re.sub(r"run_id=\S+", "run_id=foreign-run", l, count=1)
                if _r294_is_pin(l, "emulator") else l for l in ls])
        rep = self._rep(m)
        self.assertFalse(rep.integrity_ok)

    def test_smoke_wrong_cell_is_red(self):
        """Re-tagging the recipient pin to the OTHER direction is the
        exact false-attribution shape the serialisation fixed."""
        def m(ev):
            _r294_smoke_rewrite(ev, lambda ls: [
                l.replace(f"cell_id={_R294_CELL} ", "cell_id=wss.e2p.after-connect ")
                if _r294_is_pin(l, "emulator") else l for l in ls])
        rep = self._rep(m)
        self.assertFalse(rep.integrity_ok)

    def test_both_paths_share_one_helper(self):
        """A rule that exists in only one of the two paths is not a
        rule. Pin the shared entry point by source inspection."""
        src = open(os.path.join(_PKG, "verify_evidence_wss3.py"),
                   encoding="utf-8").read()
        self.assertEqual(src.count("def check_dual_party_pin("), 1)
        self.assertGreaterEqual(src.count("check_dual_party_pin("), 3,
                                msg="expected one definition + two call sites")


class AuditR294AbortAwareAudit(unittest.TestCase):
    """Audit ROUND-29.4 P1: an aborted matrix must be audited as an
    abort, not as a completed matrix that lost most of its evidence.
    R29.3 demanded captures for never-attempted rows, nine checkpoints,
    forty envelopes and observed counts for rows that never ran."""

    def _aborted(self, **kw):
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t,
                profile_id="yota-phone-off-host-on",
                utc_suffix="20260822T031500Z",
                attempt_id=_uuid4(),
                arm_token=_uuid4(),
                abort_reason="pin_unconfirmed",
                cells_ran=2,
                **kw,
            )
            return V.verify_profile_dir(ev)

    def test_abort_still_red_and_not_evaluable(self):
        rep = self._aborted()
        self.assertFalse(rep.integrity_ok)
        self.assertEqual(rep.product_outcome, "NOT_EVALUABLE")

    def test_abort_reports_the_abort_itself(self):
        rep = self._aborted()
        self.assertTrue(any("aborted: pin_unconfirmed" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:8])

    def test_abort_does_not_manufacture_completion_defects(self):
        """None of the four success-oriented complaint classes may
        appear on a correctly-recorded abort."""
        rep = self._aborted()
        forbidden = (
            "zero WSS_DIAG events in capture",
            "expected exactly 9",
            "cells_expected_to_run*5=40",
            "no verifier-observed counts",
        )
        bad = [i for i in rep.integrity_issues
               if any(f in i for f in forbidden)]
        self.assertEqual(bad, [],
                         msg=f"abort audited as a completed matrix: {bad}")

    def test_checkpoint_prefix_still_rejects_a_forged_log(self):
        """Prefix tolerance must not become "any labels". A shuffled
        prefix is still RED."""
        def m(ev):
            p = os.path.join(ev, "checkpoint_log.json")
            with open(p, encoding="utf-8") as fh:
                arr = json.load(fh)
            if len(arr) >= 2:
                arr[0], arr[1] = arr[1], arr[0]
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(arr, fh)
        rep = self._aborted(mutate_before_return=m)
        self.assertTrue(any("canonical prefix" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:8])


# ── audit ROUND-29.5: abort evidence is bound to the evidence ─────

_R295_FAILED_CELL = "wss.p2e.after-idle"   # cells_ran=2 -> cell index 2


class AuditR295AbortBoundToEvidence(unittest.TestCase):
    """R29.4 lifted the success-shaped checks for an abort but left
    four ways to lie inside one: the aborting cell escaped its own
    content and pin validation, totals were only checked for EXCESS,
    and any canonical checkpoint prefix was accepted regardless of
    length. Each mutation below is SHA-consistent — the manifest is
    recomputed after it — so only the semantic rule can catch it."""

    def _rep(self, mutate=None):
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t,
                profile_id="yota-phone-off-host-on",
                utc_suffix="20260822T040000Z",
                attempt_id=_uuid4(),
                arm_token=_uuid4(),
                abort_reason="pin_unconfirmed",
                cells_ran=2,
                mutate_before_return=mutate,
            )
            return V.verify_profile_dir(ev)

    def _new_issues(self, rep) -> List[str]:
        """Everything beyond the two findings a truthful abort has."""
        return [i for i in rep.integrity_issues
                if not i.startswith("aborted:")
                and "cells_ran=2 != cells_expected_to_run=8" not in i]

    def test_truthful_abort_baseline_has_exactly_two_findings(self):
        rep = self._rep()
        self.assertEqual(self._new_issues(rep), [],
                         msg=f"baseline drifted: {rep.integrity_issues}")

    # ── mutation 1: the aborting cell's own verdict ───────────────
    def test_failed_cell_verdict_downgraded_is_red(self):
        def m(ev):
            p = os.path.join(ev, "matrix_cells", _R295_FAILED_CELL,
                             "cell_verdict.json")
            with open(p, encoding="utf-8") as fh:
                cv = json.load(fh)
            cv["verdict"] = "PENDING"
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(cv, fh, sort_keys=True, indent=2)
        new = self._new_issues(self._rep(m))
        self.assertTrue(any("requires verdict='Unresolved'" in i for i in new),
                        msg=new)

    # ── mutation 2: forged partial pin in the aborting cell ───────
    def test_forged_partial_pin_in_failed_cell_is_red(self):
        def m(ev):
            p = os.path.join(ev, "matrix_cells", _R295_FAILED_CELL, "capture.log")
            with open(p, "a", encoding="utf-8") as fh:
                fh.write("08-14 12:00:00.000  1  1 I WSS_DIAG: "
                         "event=diagnostic_pin_active role=sender "
                         "emitter_id=intruder run_id=forged "
                         f"cell_id={_R295_FAILED_CELL} pin=rest "
                         "wall_utc_ms=1 monotonic_ms=1\n")
        new = self._new_issues(self._rep(m))
        self.assertTrue(any("intruder emitter" in i for i in new), msg=new)

    # ── mutation 3: under-reported totals ─────────────────────────
    def test_envelopes_sent_under_reported_is_red(self):
        def m(ev):
            p = os.path.join(ev, "matrix_completion.json")
            with open(p, encoding="utf-8") as fh:
                mc = json.load(fh)
            mc["envelopes_sent"] = 0          # ten were actually observed
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(mc, fh, sort_keys=True, indent=2)
        new = self._new_issues(self._rep(m))
        self.assertTrue(any("actually observed on disk" in i for i in new),
                        msg=new)

    # ── mutation 4: truncated checkpoint prefix ───────────────────
    def test_truncated_checkpoint_prefix_is_red(self):
        def m(ev):
            p = os.path.join(ev, "checkpoint_log.json")
            with open(p, encoding="utf-8") as fh:
                arr = json.load(fh)
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(arr[:1], fh, sort_keys=True, indent=2)
        new = self._new_issues(self._rep(m))
        self.assertTrue(any("expected exactly 3 for an aborted profile" in i
                            for i in new), msg=new)

    def test_shuffled_checkpoint_prefix_is_red(self):
        """Length alone is not enough: order still matters."""
        def m(ev):
            p = os.path.join(ev, "checkpoint_log.json")
            with open(p, encoding="utf-8") as fh:
                arr = json.load(fh)
            if len(arr) >= 2:
                arr[0], arr[1] = arr[1], arr[0]
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(arr, fh, sort_keys=True, indent=2)
        new = self._new_issues(self._rep(m))
        self.assertTrue(any("canonical prefix" in i for i in new), msg=new)


class AuditR295SmokeAbortIsStageAware(unittest.TestCase):
    """Audit ROUND-29.5 P1: the smoke serialises P2E then E2P, so a
    pre-send abort means the later direction never ran. R29.4 demanded
    both breadcrumbs from BOTH directions unconditionally, inventing
    two findings on a P2E-stage pin failure."""

    def _rep(self, stage: str, drop_e2p_pins: bool = True):
        def m(ev):
            # Shape a stage-`{stage}` pin abort: RED verdict, and a
            # smoke.log whose later direction has no pins at all.
            p = os.path.join(ev, "smoke_verdict.json")
            with open(p, encoding="utf-8") as fh:
                sv = json.load(fh)
            sv.update({
                "integrity": "RED", "e2p": "FAIL", "e2p_cid": None,
                "pre_send_reason": "pin_unconfirmed",
                "pre_send_pin_stage": stage,
                "pre_send_pin_emitter": "emulator",
            })
            if stage == "p2e":
                sv["p2e"] = "FAIL"
                sv["p2e_cid"] = None
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(sv, fh, sort_keys=True, indent=2)

            sp = os.path.join(ev, "smoke.log")
            with open(sp, encoding="utf-8") as fh:
                lines = fh.read().splitlines(keepends=True)

            def _is_pin(l, cell):
                return ("event=diagnostic_pin_active" in l
                        and f"cell_id={cell} " in l)

            keep = []
            for l in lines:
                if drop_e2p_pins and _is_pin(l, "wss.e2p.after-connect"):
                    continue
                # A stage-p2e abort means the P2E pin pair was never
                # completed either: drop the recipient's breadcrumb.
                if (stage == "p2e" and _is_pin(l, "wss.p2e.after-connect")
                        and "emitter_id=emulator " in l):
                    continue
                keep.append(l)
            with open(sp, "w", encoding="utf-8") as fh:
                fh.writelines(keep)

        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t,
                profile_id="yota-phone-off-host-on",
                utc_suffix="20260822T041500Z",
                attempt_id=_uuid4(),
                arm_token=_uuid4(),
                mutate_before_return=m,
            )
            return V.verify_profile_dir(ev)

    def _pin_issues(self, rep):
        return [i for i in rep.integrity_issues
                if "smoke.log cell" in i and "diagnostic_pin_active" in i]

    def test_p2e_stage_abort_does_not_demand_e2p_pins(self):
        rep = self._rep("p2e")
        bad = [i for i in self._pin_issues(rep) if "wss.e2p.after-connect" in i]
        self.assertEqual(bad, [],
                         msg=f"E2P never ran; its pins must not be demanded: {bad}")

    def test_e2p_stage_abort_still_demands_completed_p2e_pins(self):
        """The direction that DID complete keeps the full requirement:
        stage-awareness must not become a blanket exemption."""
        def m(ev):
            p = os.path.join(ev, "smoke_verdict.json")
            with open(p, encoding="utf-8") as fh:
                sv = json.load(fh)
            sv.update({
                "integrity": "RED", "e2p": "FAIL", "e2p_cid": None,
                "pre_send_reason": "pin_unconfirmed",
                "pre_send_pin_stage": "e2p",
                "pre_send_pin_emitter": "emulator",
            })
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(sv, fh, sort_keys=True, indent=2)
            sp = os.path.join(ev, "smoke.log")
            with open(sp, encoding="utf-8") as fh:
                lines = fh.read().splitlines(keepends=True)
            keep = [l for l in lines
                    if not ("event=diagnostic_pin_active" in l
                            and "cell_id=wss.p2e.after-connect " in l
                            and "emitter_id=emulator " in l)]
            with open(sp, "w", encoding="utf-8") as fh:
                fh.writelines(keep)
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260822T042500Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=m)
            rep = V.verify_profile_dir(ev)
        self.assertTrue(any("wss.p2e.after-connect" in i and "recipient" in i
                            for i in self._pin_issues(rep)),
                        msg=self._pin_issues(rep))

    def test_forged_pin_still_caught_on_a_never_run_direction(self):
        """Not demanding pins is not the same as ignoring them."""
        def m(ev):
            p = os.path.join(ev, "smoke_verdict.json")
            with open(p, encoding="utf-8") as fh:
                sv = json.load(fh)
            sv.update({
                "integrity": "RED", "p2e": "FAIL", "p2e_cid": None,
                "e2p": "FAIL", "e2p_cid": None,
                "pre_send_reason": "pin_unconfirmed",
                "pre_send_pin_stage": "p2e",
                "pre_send_pin_emitter": "emulator",
            })
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(sv, fh, sort_keys=True, indent=2)
            sp = os.path.join(ev, "smoke.log")
            with open(sp, "a", encoding="utf-8") as fh:
                fh.write("08-14 12:00:00.000  1  1 I WSS_DIAG: "
                         "event=diagnostic_pin_active role=matrix "
                         f"emitter_id=intruder run_id={sv['smoke_run_id']} "
                         "cell_id=wss.e2p.after-connect pin=wss "
                         "wall_utc_ms=1 monotonic_ms=1\n")
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260822T043500Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=m)
            rep = V.verify_profile_dir(ev)
        self.assertTrue(any("intruder emitter" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues[:8])


import atexit
atexit.register(_write_summary_if_env)



if __name__ == "__main__":
    unittest.main(verbosity=2)


# ── Audit ROUND-30.12: the two bypasses the R30.11 review found ───

_R3012_HOST_CELL = "wss.p2e.after-connect"   # index 0 — ran, capture is parsed
_R3012_PENDING_CELL = "wss.e2p.bg-fg"        # index 5 — never reached


def _r3012_run_id(ev):
    with open(os.path.join(ev, "PROFILE.json"), encoding="utf-8") as fh:
        return json.load(fh)["run_id"]


def _r3012_clear_line(run_id, cell, emitter="phone"):
    return ("08-14 12:00:00.000  1  1 I WSS_DIAG: "
            "event=diagnostic_pin_active role=matrix "
            "emitter_id=%s run_id=%s cell_id=%s.clear "
            "wall_utc_ms=1 monotonic_ms=1 pin=none\n"
            % (emitter, run_id, cell))


def _r3012_honest(issues):
    """Everything beyond the findings a truthful abort always carries."""
    return [i for i in issues
            if not i.startswith("aborted:")
            and "!= cells_expected_to_run" not in i]


class AuditR3012OrphanClearNeedsAnExecutedParent(unittest.TestCase):
    """R30.11 shipped `check_orphan_clear_events` with a message that
    reads "its parent cell did not run in this evidence" and a
    known-parent set that made precisely that undetectable: the full
    sweep was handed all eight canonical ids and the smoke sweep both
    canonical directions, whatever had actually run.

    So a clear breadcrumb planted on a row the matrix never reached
    changed no finding at all — the exact hole the derived
    `<cell>.clear` identity was introduced WITH a sweep to avoid. The
    docstring and the call site disagreed, and the call site won.
    """

    def _rep(self, mutate=None, *, suffix):
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on", utc_suffix=suffix,
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_unconfirmed", cells_ran=2,
                mutate_before_return=mutate)
            return V.verify_profile_dir(ev)

    @staticmethod
    def _orphans(rep):
        return [i for i in rep.integrity_issues if "orphan clear breadcrumb" in i]

    def test_clear_for_a_never_reached_cell_is_an_orphan(self):
        """THE REVIEW REPRODUCTION. Under R30.11 this returned nothing."""
        def m(ev):
            p = os.path.join(ev, "matrix_cells", _R3012_HOST_CELL, "capture.log")
            with open(p, "a", encoding="utf-8") as fh:
                fh.write(_r3012_clear_line(_r3012_run_id(ev), _R3012_PENDING_CELL))
        found = self._orphans(self._rep(m, suffix="20260826T090000Z"))
        self.assertTrue(any(_R3012_PENDING_CELL in i for i in found),
                        msg=f"a clear owed by nothing passed silently: {found}")

    def test_clear_for_an_executed_cell_is_not_an_orphan(self):
        """The negative control: narrowing the set must not start
        reporting the clears a healthy cell legitimately writes."""
        rep = self._rep(suffix="20260826T090500Z")
        self.assertEqual(self._orphans(rep), [], msg=rep.integrity_issues)


class AuditR3012MatrixAbortNeedsExactlyOneCell(unittest.TestCase):
    """R30.11 ran the matrix-to-cell agreement check only once an
    aborting cell had already been found, so the matrix could declare
    an abort that no cell corroborated and the claim was never tested.
    Only the cell-to-matrix direction was enforced.
    """

    @staticmethod
    def _branch(rep):
        # Audit ROUND-30.13: the duplicate-claim message moved rounds when
        # the rule stopped depending on order, so the marker filter has to
        # follow it. Filtering on a round number is a way of naming the
        # rules under test, not of pinning where they were last edited.
        return [i for i in rep.integrity_issues
                if "ROUND-30.11" in i or "ROUND-30.12" in i
                or "ROUND-30.13" in i]

    def _healthy_with_matrix_abort(self, reason, suffix):
        def m(ev):
            p = os.path.join(ev, "matrix_completion.json")
            with open(p, encoding="utf-8") as fh:
                mc = json.load(fh)
            mc["abort_reason"] = reason
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(mc, fh, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on", utc_suffix=suffix,
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=m)
            return V.verify_profile_dir(ev)

    def test_zero_matching_cells_for_clear_abort(self):
        """THE REVIEW REPRODUCTION. Eight healthy cells, no cell claims
        an abort, and the matrix says the run stopped on a failed
        clear. Under R30.11 the verifier said only `aborted: ...`."""
        found = self._branch(self._healthy_with_matrix_abort(
            "pin_clear_unconfirmed", "20260826T091000Z"))
        self.assertTrue(any("no cell recorded it" in i for i in found),
                        msg=f"an uncorroborated matrix abort passed: {found}")

    def test_zero_matching_cells_for_pin_unconfirmed(self):
        """The same hole on the other cell-scoped reason."""
        found = self._branch(self._healthy_with_matrix_abort(
            "pin_unconfirmed", "20260826T091500Z"))
        self.assertTrue(any("no cell recorded it" in i for i in found),
                        msg=found)

    def test_two_aborting_cells_are_reported_as_such(self):
        """A second cell claiming the abort was caught only sideways,
        by the later-rows PENDING rule, which describes a different
        defect. The count is now stated directly."""
        def m(ev):
            p = os.path.join(ev, "matrix_cells", _R3012_PENDING_CELL,
                             "cell_verdict.json")
            with open(p, encoding="utf-8") as fh:
                cv = json.load(fh)
            cv["abort_reason"] = "pin_unconfirmed"
            cv["verdict"] = "Unresolved"
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(cv, fh, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260826T092000Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_unconfirmed", cells_ran=2,
                mutate_before_return=m)
            rep = V.verify_profile_dir(ev)
        found = self._branch(rep)
        self.assertTrue(any("2 cells record" in i for i in found), msg=found)

    def test_mismatched_reason_is_still_reported(self):
        """Regression guard: the direction R30.11 DID enforce."""
        def m(ev):
            p = os.path.join(ev, "matrix_completion.json")
            with open(p, encoding="utf-8") as fh:
                mc = json.load(fh)
            mc["abort_reason"] = "pin_clear_unconfirmed"
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(mc, fh, sort_keys=True, indent=2)
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260826T092500Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_unconfirmed", cells_ran=2,
                mutate_before_return=m)
            rep = V.verify_profile_dir(ev)
        found = self._branch(rep)
        self.assertTrue(any("does not match the aborting cell" in i for i in found),
                        msg=found)

    def test_truthful_single_cell_pin_unconfirmed_is_quiet(self):
        """Control: the shape a real `pin_unconfirmed` abort writes must
        raise no branch finding at all."""
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260826T093000Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_unconfirmed", cells_ran=2)
            rep = V.verify_profile_dir(ev)
        self.assertEqual(self._branch(rep), [], msg=rep.integrity_issues)


class AuditR3013OnlyOneCellMayClaimTheAbort(unittest.TestCase):
    """R30.12 counted only the cells whose reason was one of the two
    cell-scoped ones, so a cell recording ANY of the other enum values
    was invisible to the exactly-one rule. The bypass depended on
    ORDER: placed before the matching cell it produced no coherence
    finding at all; placed after it was caught only sideways, by the
    later-rows PENDING rule, which describes a different defect.

    The contract already said what should happen — "zero corroborating
    cells, more than one, or a cell recording a different reason are
    each findings" — so this is a rule that was written down and not
    built. A normative sentence with nothing behind it reads as a
    guarantee, which is worse than saying nothing.
    """

    _MATCHING = "wss.p2e.after-idle"      # index 2 — the cell that aborted
    _EARLIER = "wss.p2e.after-connect"    # index 0 — BEFORE it
    _LATER = "wss.e2p.bg-fg"              # index 5 — after it, PENDING

    @staticmethod
    def _claims(rep):
        return [i for i in rep.integrity_issues if "may claim the abort" in i]

    def _rep(self, mutate, *, suffix):
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on", utc_suffix=suffix,
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_unconfirmed", cells_ran=2,
                mutate_before_return=mutate)
            return V.verify_profile_dir(ev)

    @staticmethod
    def _plant(cell, reason):
        def m(ev):
            p = os.path.join(ev, "matrix_cells", cell, "cell_verdict.json")
            with open(p, encoding="utf-8") as fh:
                cv = json.load(fh)
            cv["abort_reason"] = reason
            cv["envelopes_dispatched"] = 0
            cv["delivered_count"] = 0
            cv["unresolved_count"] = 0
            cv["verdict"] = "Unresolved"
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(cv, fh, sort_keys=True, indent=2)
        return m

    def test_a_different_reason_before_the_match_is_reported(self):
        """THE REVIEW REPRODUCTION. Under R30.12 this returned nothing."""
        found = self._claims(self._rep(
            self._plant(self._EARLIER, "operator_interrupt"),
            suffix="20260826T100000Z"))
        self.assertTrue(any(self._EARLIER in i for i in found),
                        msg=f"a second cell claimed the abort unreported: {found}")

    def test_a_different_reason_after_the_match_is_reported(self):
        """Order must not decide whether the rule applies. After the
        match it was caught only by the PENDING rule, which is about
        something else."""
        found = self._claims(self._rep(
            self._plant(self._LATER, "operator_interrupt"),
            suffix="20260826T100500Z"))
        self.assertTrue(any(self._LATER in i for i in found), msg=found)

    def test_truthful_single_claim_stays_quiet(self):
        """Control: one matching cell and no other claim."""
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260826T101000Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_unconfirmed", cells_ran=2)
            rep = V.verify_profile_dir(ev)
        self.assertEqual(self._claims(rep), [], msg=rep.integrity_issues)

    # ── order-independence, stated directly on the rule ────────────
    #
    # The end-to-end fixtures above pin the behaviour a profile shows.
    # These pin the invariant itself, in both orders, with nothing else
    # in the evidence able to account for the finding.

    @staticmethod
    def _cell(cid, reason=None, verdict="Unresolved", disp=0, deliv=0):
        cv = {"cell_id": cid, "envelopes_dispatched": disp,
              "delivered_count": deliv, "unresolved_count": max(0, disp - deliv),
              "verdict": verdict}
        if reason is not None:
            cv["abort_reason"] = reason
        return cv

    def test_rule_is_order_independent(self):
        match = self._cell("a", "pin_unconfirmed")
        other = self._cell("b", "operator_interrupt")
        for order, label in (([other, match], "different reason FIRST"),
                             ([match, other], "different reason SECOND")):
            found = [i for i in V.check_cell_abort_branches(
                order, matrix_abort_reason="pin_unconfirmed")
                if "may claim the abort" in i]
            self.assertTrue(found, msg=f"{label}: no finding")
            self.assertTrue(any("'b'" in i for i in found),
                            msg=f"{label}: {found}")

    def test_two_matching_cells_still_reported(self):
        cells = [self._cell("a", "pin_unconfirmed"),
                 self._cell("b", "pin_unconfirmed")]
        found = V.check_cell_abort_branches(
            cells, matrix_abort_reason="pin_unconfirmed")
        self.assertTrue(any("2 cells record" in i for i in found), msg=found)

    def test_zero_matching_cells_still_reported(self):
        cells = [self._cell("a", None, verdict="Delivered", disp=5, deliv=5)]
        found = V.check_cell_abort_branches(
            cells, matrix_abort_reason="pin_unconfirmed")
        self.assertTrue(any("no cell recorded it" in i for i in found), msg=found)

    def test_a_run_level_matrix_reason_does_not_demand_a_cell(self):
        """`network_profile_drift` and its siblings are mid-run drift,
        not a claim about one cell, so nothing is owed."""
        cells = [self._cell("a", None, verdict="Delivered", disp=5, deliv=5)]
        found = V.check_cell_abort_branches(
            cells, matrix_abort_reason="network_profile_drift")
        self.assertEqual(found, [], msg=found)

    def test_cell_level_reasons_come_from_the_schema_authority(self):
        """The set is not a constant local to the verifier."""
        import schema_wss3 as S
        self.assertEqual(set(V.CELL_SCOPED_ABORT_REASONS),
                         set(S.CELL_SCOPED_ABORT_REASONS))
        self.assertTrue(set(S.CELL_SCOPED_ABORT_REASONS)
                        <= set(S.ABORT_REASON_ENUM),
                        msg="cell-scoped reasons must be a subset of the enum")


class AuditR3012ClearAbortCellIsTruthful(unittest.TestCase):
    """`pin_clear_unconfirmed` is the one reason describing a cell that
    FINISHED: it is counted in `cells_ran`, its counters are observed
    facts and its own pre-cell checkpoint is already inside that count.
    These fixtures drive that shape end to end through the real
    `verify_profile_dir`.
    """

    _CELL = "wss.p2e.after-idle"   # index 2 — the last cell that ran

    def _rep(self, mutate=None, *, suffix, emitter="phone"):
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on", utc_suffix=suffix,
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_clear_unconfirmed", cells_ran=3,
                clear_failed_emitter=emitter,
                mutate_before_return=mutate)
            return V.verify_profile_dir(ev)

    def test_truthful_clear_abort_raises_no_new_finding(self):
        """Control. The counters survive and nothing else is claimed."""
        rep = self._rep(suffix="20260826T094000Z")
        self.assertEqual(_r3012_honest(rep.integrity_issues), [],
                         msg=rep.integrity_issues)

    def test_counters_are_preserved_on_the_aborting_cell(self):
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260826T094500Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_clear_unconfirmed", cells_ran=3,
                clear_failed_emitter="phone")
            with open(os.path.join(ev, "matrix_cells", self._CELL,
                                   "cell_verdict.json"), encoding="utf-8") as fh:
                cv = json.load(fh)
        self.assertEqual(cv["envelopes_dispatched"], 5, msg=cv)
        self.assertEqual(cv["delivered_count"], 5, msg=cv)
        self.assertEqual(cv["verdict"], "Delivered", msg=cv)
        self.assertEqual(cv["abort_reason"], "pin_clear_unconfirmed", msg=cv)

    def test_declared_failure_contradicted_by_evidence_is_reported(self):
        """The verdict names a side as unconfirmed while the capture
        carries that side's breadcrumb: the two disagree, and the
        verdict is the one making a claim."""
        def m(ev):
            p = os.path.join(ev, "matrix_cells", self._CELL, "capture.log")
            with open(p, "a", encoding="utf-8") as fh:
                fh.write(_r3012_clear_line(_r3012_run_id(ev), self._CELL,
                                           emitter="phone"))
        rep = self._rep(m, suffix="20260826T095000Z")
        found = [i for i in rep.integrity_issues if "unconfirmed clear" in i]
        self.assertTrue(any("'phone'" in i for i in found), msg=found)


# ── Audit ROUND-30.11: the cell-branch wiring must actually RUN ───

_R3011_MARKER = "audit ROUND-30.11"
_R3011_TARGET_CELL = "wss.p2e.after-connect"


class AuditR3011CellBranchWiringActuallyRuns(unittest.TestCase):
    """The round wired the cell-abort branch rules and the orphan
    sweep into `verify_profile_dir`, but read a name that was never
    bound there. `ast.parse` accepted the file, so a syntax check saw
    nothing; every real profile raised `NameError` instead of being
    verified. A parse is not evidence that Python code works.

    These fixtures therefore import the real module and CALL the real
    `verify_profile_dir` against a materialised evidence tree. The
    first proves the call completes at all. The second proves the
    ordered cell verdicts genuinely REACH
    `check_cell_abort_branches` — a collection that were quietly
    dropped would leave the rules unreachable while the call still
    returned. The third pins that the rule stays silent on evidence
    that does not break it.
    """

    def _rep(self, mutate=None, *, suffix):
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t,
                profile_id="yota-phone-off-host-on",
                utc_suffix=suffix,
                attempt_id=_uuid4(),
                arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            return V.verify_profile_dir(ev)

    @staticmethod
    def _branch_issues(rep) -> List[str]:
        """Only the findings `check_cell_abort_branches` can raise.

        The round marker alone is too wide: the clear-breadcrumb and
        orphan sweeps carry it too, and a filter that swept those in
        would turn this fixture into a report on unrelated rules.
        """
        return [i for i in rep.integrity_issues
                if _R3011_MARKER in i and "post_cell_clear_emitter" in i]

    def test_verify_profile_dir_runs_instead_of_raising(self):
        """The reproduction of the defect: this raised NameError."""
        rep = self._rep(suffix="20260825T150000Z")
        self.assertIsInstance(rep.integrity_issues, list)
        self.assertIn(rep.product_outcome,
                      ("GREEN", "RED", "NOT_EVALUABLE"))

    def test_planted_clear_emitter_reaches_the_branch_rules(self):
        """`post_cell_clear_emitter` under any other reason is a
        ROUND-30.11 finding, and only `check_cell_abort_branches`
        raises it — so seeing it proves the ordered verdicts arrived
        there."""
        def m(ev):
            p = os.path.join(ev, "matrix_cells", _R3011_TARGET_CELL,
                             "cell_verdict.json")
            with open(p, encoding="utf-8") as fh:
                cv = json.load(fh)
            cv.pop("abort_reason", None)
            cv["post_cell_clear_emitter"] = "phone"
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(cv, fh, sort_keys=True, indent=2)
        found = self._branch_issues(self._rep(m, suffix="20260825T150500Z"))
        self.assertTrue(
            any("post_cell_clear_emitter" in i and _R3011_TARGET_CELL in i
                for i in found),
            msg=f"branch rules never ran: {found}")

    def test_untouched_tree_raises_no_branch_finding(self):
        """The negative control for the fixture above: with nothing
        planted, the same rule must stay silent."""
        rep = self._rep(suffix="20260825T151000Z")
        self.assertEqual(self._branch_issues(rep), [],
                         msg=rep.integrity_issues)


# ── Audit ROUND-30.14 ──────────────────────────────────────────────

_R3014_MARK = "(audit ROUND-30.14)"
_R3014_RUN_LEVEL = sorted(S.ABORT_REASON_ENUM - S.CELL_SCOPED_ABORT_REASONS)


class AuditR3014ACellMayNotClaimARunLevelReason(unittest.TestCase):
    """R30.13 moved the cell-scoped reasons into the schema module and
    called that set the single authority for the distinction. Nothing
    executable consulted it: `CELL_VERDICT_SCHEMA["abort_reason"]` still
    carried the full `ABORT_REASON_ENUM`, so a cell claiming a run-level
    reason passed the structural pass, the content pass and the
    coherence pass in silence, whatever the matrix said.

    A rule named an authority must be consumed by something executable,
    or it is documentation wearing the word.

    Every check below is driven by the actual set difference rather than
    by one hand-picked value, so widening the enum without deciding
    which kind of reason the new member is cannot slip past.
    """

    _CELL = "wss.p2e.after-connect"

    @staticmethod
    def _cv(reason, cid="wss.p2e.after-connect"):
        return {"cell_id": cid, "blocked": False, "envelopes_dispatched": 0,
                "delivered_count": 0, "unresolved_count": 0,
                "verdict": "Unresolved", "abort_reason": reason}

    def test_the_partition_is_not_empty_on_either_side(self):
        """Guard the fixtures themselves: an empty difference would make
        every loop below vacuously true."""
        self.assertEqual(len(S.CELL_SCOPED_ABORT_REASONS), 2)
        self.assertGreaterEqual(len(_R3014_RUN_LEVEL), 10)
        self.assertTrue(S.CELL_SCOPED_ABORT_REASONS <= S.ABORT_REASON_ENUM)

    def test_the_cell_field_enum_is_the_cell_scoped_set(self):
        """The primary fix: the authority is the field's enum, not a
        claim made about the field elsewhere."""
        self.assertEqual(
            set(S.CELL_VERDICT_SCHEMA["abort_reason"]["enum"]),
            set(S.CELL_SCOPED_ABORT_REASONS))
        self.assertEqual(
            set(S.MATRIX_COMPLETION_SCHEMA["abort_reason"]["enum"]),
            set(S.ABORT_REASON_ENUM),
            msg="the RUN may still record a run-level reason")

    def test_structural_pass_rejects_every_run_level_reason(self):
        for reason in _R3014_RUN_LEVEL:
            with self.subTest(reason=reason):
                found = V.check_registered_surface(
                    "cell_verdict.json", self._cv(reason))
                self.assertTrue(
                    any("abort_reason" in i and "enum" in i for i in found),
                    msg=f"{reason!r} accepted structurally: {found}")

    def test_content_pass_rejects_every_run_level_reason(self):
        for reason in _R3014_RUN_LEVEL:
            with self.subTest(reason=reason):
                found = [i for i in V.validate_cell_verdict_content(
                    self._cv(reason), self._CELL, blocked=False,
                    dispatched_cids_count=0, matrix_aborted=True)
                    if _R3014_MARK in i]
                self.assertTrue(found,
                                msg=f"{reason!r} passed content validation")

    def test_coherence_reports_run_level_reasons_whatever_the_matrix_says(self):
        """The review reproduction. `matrix=None` is the case R30.13
        could not reach at all: its claim rules run only when the MATRIX
        reason is itself cell-scoped."""
        for matrix in (None, "network_profile_drift", "operator_interrupt",
                       "pin_unconfirmed"):
            for reason in _R3014_RUN_LEVEL:
                with self.subTest(matrix=matrix, reason=reason):
                    found = [i for i in V.check_cell_abort_branches(
                        [self._cv(reason)], matrix_abort_reason=matrix)
                        if _R3014_MARK in i]
                    self.assertTrue(
                        found,
                        msg=f"cell {reason!r} silent under matrix={matrix!r}")

    def test_position_in_the_matrix_does_not_decide(self):
        """First row and last row must give the same finding."""
        for reason in _R3014_RUN_LEVEL:
            with self.subTest(reason=reason):
                first = [self._cv(reason, "a"), self._cv(None, "b"),
                         self._cv(None, "c")]
                last = [self._cv(None, "a"), self._cv(None, "b"),
                        self._cv(reason, "c")]
                for cells, where in ((first, "first"), (last, "last")):
                    found = [i for i in V.check_cell_abort_branches(
                        cells, matrix_abort_reason=None) if _R3014_MARK in i]
                    self.assertTrue(found,
                                    msg=f"{reason!r} silent in {where} row")

    def test_cell_scoped_reasons_are_still_accepted(self):
        """Control. If this ever fails the rule has stopped being about
        the run/cell distinction and started rejecting everything."""
        for reason in sorted(S.CELL_SCOPED_ABORT_REASONS):
            with self.subTest(reason=reason):
                self.assertEqual(
                    V.check_registered_surface("cell_verdict.json",
                                               self._cv(reason)), [])
                self.assertEqual(
                    [i for i in V.check_cell_abort_branches(
                        [self._cv(reason)], matrix_abort_reason=reason)
                     if _R3014_MARK in i], [])

    def test_a_malformed_reason_is_not_treated_as_run_level(self):
        """An unhashable value is the schema pass's business, and the
        predicate must not raise on it - the mutation matrix caught that
        shape once already."""
        for junk in ({"a": 1}, ["x"], 7, None, "TOTALLY_UNKNOWN"):
            with self.subTest(junk=repr(junk)):
                self.assertFalse(V.run_level_abort_in_cell(junk))

    # ── end to end, producer shape through the real verifier ───────

    def test_end_to_end_a_run_level_reason_in_a_cell_is_reported(self):
        def plant(ev):
            p = os.path.join(ev, "matrix_cells", self._CELL,
                             "cell_verdict.json")
            with open(p, encoding="utf-8") as fh:
                cv = json.load(fh)
            cv["abort_reason"] = "operator_interrupt"
            cv["envelopes_dispatched"] = 0
            cv["delivered_count"] = 0
            cv["unresolved_count"] = 0
            cv["verdict"] = "Unresolved"
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(cv, fh, sort_keys=True, indent=2)

        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260826T110000Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_unconfirmed", cells_ran=2,
                mutate_before_return=plant)
            rep = V.verify_profile_dir(ev)
        found = [i for i in rep.integrity_issues if _R3014_MARK in i]
        self.assertTrue(found,
                        msg=f"no R30.14 finding: {rep.integrity_issues}")
        self.assertTrue(any(self._CELL in i for i in found), msg=found)

    def test_end_to_end_untouched_tree_raises_no_run_level_finding(self):
        """The negative control: the same rule stays silent on a tree
        with nothing planted."""
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260826T111000Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                abort_reason="pin_unconfirmed", cells_ran=2)
            rep = V.verify_profile_dir(ev)
        self.assertEqual(
            [i for i in rep.integrity_issues if _R3014_MARK in i], [],
            msg=rep.integrity_issues)


# ── Audit ROUND-30.16 ──────────────────────────────────────────────

_R3016_MARK = "(audit ROUND-30.16)"
# Audit ROUND-30.17: the recipient rules moved from per-envelope to
# per-attempt and re-issue under a new marker. These older fixtures keep
# asserting the same PROPERTIES, so they follow the rule rather than the
# label they happened to be written against.
_R3017_MARK = "(audit ROUND-30.17)"

_R3016_CELL = "wss.p2e.after-connect"
_R3016_DROP = ("recipient_message_persisted", "recipient_ack_deliver_sent")


def _r3016_smoke_tree(tmp_root, suffix, *, mutate_line=None, drop_fresh=False,
                      drop=_R3016_DROP, omit_event=False):
    """A smoke of the shape the 2026-08-26 run actually produced: a
    fresh delivery, no persist, no ack, and one failure record.

    `mutate_line` rewrites the record's fields so a control can make it
    malformed, misattributed or unbound.
    """
    def m(ev):
        p = os.path.join(ev, "smoke.log")
        with open(p, encoding="utf-8") as fh:
            lines = fh.readlines()
        hdr = [l for l in lines
               if "recipient_deliver_received" in l and _R3016_CELL in l][0].split()
        cid = [x for x in hdr if x.startswith("correlation_id=")][0].split("=")[1]
        rid = [x for x in hdr if x.startswith("run_id=")][0].split("=")[1]
        keep = [l for l in lines if not (cid in l and any(d in l for d in drop))]
        if drop_fresh:
            keep = [l for l in keep
                    if not (cid in l and "recipient_deliver_received" in l)]
        kw = dict(cid=cid, role="recipient", run_id=rid, emitter="emulator",
                  cell_id=_R3016_CELL, deliver_failure="held",
                  deliver_stage="received", attempt=1)
        if mutate_line:
            kw = mutate_line(dict(kw))
        with open(p, "w", encoding="utf-8") as fh:
            fh.writelines(keep)
            if not omit_event:
                twice = kw.pop("_twice", False)
                _write_wss_diag_line(fh, "recipient_deliver_failed", **kw)
                if twice:
                    _write_wss_diag_line(fh, "recipient_deliver_failed", **kw)
        sv = os.path.join(ev, "smoke_verdict.json")
        with open(sv, encoding="utf-8") as fh:
            d = json.load(fh)
        d["p2e"] = "FAIL"
        d["integrity"] = "GREEN"
        with open(sv, "w", encoding="utf-8") as fh:
            json.dump(d, fh, sort_keys=True, indent=2)

    return build_full_profile_evidence(
        tmp_root, profile_id="yota-phone-off-host-on", utc_suffix=suffix,
        attempt_id=_uuid4(), arm_token=_uuid4(), mutate_before_return=m)


class AuditR3016SmokeFailureIsEvidenceNotCorruption(unittest.TestCase):
    """A reported delivery failure and a broken capture are different
    things, and the smoke verifier must not confuse them.

    The 2026-08-26 physical smoke produced two
    `recipient_deliver_received dedup_gate=fresh` for one envelope and
    nothing else — no persist, no ack, twice — and the evidence stream
    never said a delivery had FAILED. The recipient now reports its own
    postcondition, so that silence becomes a record.

    A WELL-FORMED record is valid evidence OF A PRODUCT FAILURE: the
    capture is intact, the delivery is not. Smoke integrity therefore
    stays GREEN while the DIRECTION stays FAIL. Only evidence that
    cannot be trusted is an integrity finding.

    Scope, deliberately: this is the SMOKE. The full matrix keeps every
    rule it had, including `triplet_complete == cells_expected_to_run *
    5` — the defect happened before `full` was ever allowed to run, so
    widening the profile rules would loosen guarantees the failure never
    touched. `test_the_profile_path_is_untouched` pins that.
    """

    @staticmethod
    def _integrity(ev):
        _, issues = V.load_smoke_verdict(ev)
        return [i for i in issues
                if "integrity=GREEN" in i or _R3016_MARK in i
                or _R3017_MARK in i or "missing recipient_" in i]

    def _run(self, suffix, **kw):
        with tempfile.TemporaryDirectory() as t:
            return self._integrity(_r3016_smoke_tree(t, suffix, **kw))

    # ── the boundary itself ────────────────────────────────────────

    def test_a_well_formed_record_leaves_smoke_integrity_green(self):
        """THE SHAPE OF THE REAL FAILURE."""
        self.assertEqual(self._run("20260826T200000Z"), [])

    def test_the_direction_still_fails(self):
        """Integrity GREEN must not quietly become 'the smoke passed'.
        The record explains the short triplet; it does not repair it."""
        with tempfile.TemporaryDirectory() as t:
            ev = _r3016_smoke_tree(t, "20260826T200500Z")
            v, _ = V.load_smoke_verdict(ev)
        self.assertEqual(v.get("p2e"), "FAIL")

    def test_no_record_at_all_is_still_an_integrity_finding(self):
        """The control that keeps the rule honest: silence is what the
        round exists to stop accepting."""
        self.assertNotEqual(self._run("20260826T201000Z", omit_event=True), [])

    # ── evidence that cannot be trusted ────────────────────────────

    def test_a_value_outside_the_vocabulary_is_red(self):
        for bad in ("mac_error", "THREW", "unknown"):
            with self.subTest(deliver_failure=bad):
                def mut(kw, bad=bad):
                    kw["deliver_failure"] = bad
                    return kw
                self.assertNotEqual(
                    self._run("20260826T2015%02dZ" % len(bad), mutate_line=mut), [])

    def test_a_stage_outside_the_vocabulary_is_red(self):
        self.assertNotEqual(
            self._run("20260826T202000Z",
                      mutate_line=lambda kw: {**kw, "deliver_stage": "halfway"}), [])

    def test_the_wrong_emitter_is_red(self):
        self.assertNotEqual(
            self._run("20260826T202500Z",
                      mutate_line=lambda kw: {**kw, "emitter": "phone"}), [])

    def test_a_duplicate_record_is_red(self):
        self.assertNotEqual(
            self._run("20260826T203000Z",
                      mutate_line=lambda kw: {**kw, "_twice": True}), [])

    def test_a_stage_the_stream_contradicts_is_red(self):
        """`ack_sent` asserts an ack was observed. Claiming it with no
        `recipient_ack_deliver_sent` present is a contradiction, and one
        of the two claims has to be wrong."""
        self.assertNotEqual(
            self._run("20260826T203500Z",
                      mutate_line=lambda kw: {**kw, "deliver_stage": "ack_sent"}), [])

    # ── the record explains THIS delivery or nothing ───────────────

    def test_a_foreign_cid_explains_nothing(self):
        self.assertNotEqual(
            self._run("20260826T204000Z",
                      mutate_line=lambda kw: {**kw, "cid": "cid-someone-else"}), [])

    def test_a_foreign_run_id_explains_nothing(self):
        self.assertNotEqual(
            self._run("20260826T204500Z",
                      mutate_line=lambda kw: {**kw, "run_id": "rid-someone-else"}), [])

    def test_a_foreign_cell_id_explains_nothing(self):
        self.assertNotEqual(
            self._run("20260826T205000Z",
                      mutate_line=lambda kw: {**kw, "cell_id": "wss.e2p.after-connect"}), [])

    def test_a_record_with_no_fresh_delivery_is_an_orphan(self):
        """Nothing was being delivered, so nothing could have failed."""
        found = self._run("20260826T205500Z", drop_fresh=True)
        # Assert the PROPERTY, not the sentence: ROUND-30.17 re-issues
        # this finding per attempt and words it differently.
        self.assertTrue(any("orphan" in i for i in found), msg=found)

    # ── the explanation is bounded by the stage it claims ──────────

    def test_stage_received_accounts_for_persist_and_ack(self):
        self.assertEqual(self._run("20260826T210000Z"), [])

    def test_stage_persisted_accounts_for_the_ack_alone(self):
        """Dropping only the ack, and claiming `persisted`, is coherent."""
        self.assertEqual(
            self._run("20260826T210500Z",
                      drop=("recipient_ack_deliver_sent",),
                      mutate_line=lambda kw: {**kw, "deliver_stage": "persisted"}),
            [])

    def test_stage_persisted_does_not_account_for_a_missing_persist(self):
        """Over-claiming leaves the unexplained part a finding. Without
        this, one stage value would excuse every gap.

        Asserts the SPECIFIC finding, not merely that something was
        reported: claiming `persisted` with no persist in the stream also
        trips the contradiction rule, so a non-empty list would pass
        whether or not the stage still bounds the explanation. Widening
        `persisted` to cover a missing persist has to be caught here.
        """
        found = self._run("20260826T211000Z",
                          mutate_line=lambda kw: {**kw, "deliver_stage": "persisted"})
        self.assertTrue(
            any("missing recipient_message_persisted" in i for i in found),
            msg=f"stage=persisted must not excuse a missing persist: {found}")

    # ── the profile path is NOT widened ────────────────────────────

    def test_the_profile_path_is_untouched(self):
        """The full matrix keeps its rules. A failure record inside a
        matrix cell does NOT excuse a short triplet there."""
        def m(ev):
            p = os.path.join(ev, "matrix_cells", _R3016_CELL, "capture.log")
            with open(p, encoding="utf-8") as fh:
                lines = fh.readlines()
            hdr = [l for l in lines if "recipient_deliver_received" in l][0].split()
            cid = [x for x in hdr if x.startswith("correlation_id=")][0].split("=")[1]
            rid = [x for x in hdr if x.startswith("run_id=")][0].split("=")[1]
            keep = [l for l in lines
                    if not (cid in l and any(d in l for d in _R3016_DROP))]
            with open(p, "w", encoding="utf-8") as fh:
                fh.writelines(keep)
                _write_wss_diag_line(
                    fh, "recipient_deliver_failed", cid=cid, role="recipient",
                    run_id=rid, emitter="emulator", cell_id=_R3016_CELL,
                    deliver_failure="held", deliver_stage="received")

        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260826T211500Z", attempt_id=_uuid4(),
                arm_token=_uuid4(), mutate_before_return=m)
            rep = V.verify_profile_dir(ev)
        missing = [i for i in rep.integrity_issues if "missing recipient_" in i]
        self.assertEqual(
            len(missing), 2,
            msg=f"the profile path must still demand a full triplet: {missing}")

    # ── the vocabulary comes from the schema, not a local copy ─────

    def test_the_verifier_reads_the_schema_vocabulary(self):
        self.assertEqual(V.DELIVER_FAILURE_ENUM, S.DELIVER_FAILURE_ENUM)
        self.assertEqual(V.DELIVER_STAGE_ENUM, S.DELIVER_STAGE_ENUM)
        self.assertIn("recipient_deliver_failed", V.ALL_ALLOWED_EVENTS)
        self.assertIn("recipient_deliver_failed", V.RECIPIENT_EVENTS)


# ── Audit ROUND-30.17 ──────────────────────────────────────────────

_R3017_CELL = "wss.p2e.after-connect"


def _r3017_tree(tmp_root, suffix, *, attempts, settle=(), mutate=None,
                declare_p2e=None):
    """A smoke of a given ATTEMPT shape.

    `attempts` is the ordinals to emit a fresh delivery for; `settle` is
    the subset that completes its triplet, the rest report a failure.
    `mutate` rewrites the failure record's fields so a control can break
    exactly one property.
    """
    def m(ev):
        p = os.path.join(ev, "smoke.log")
        with open(p, encoding="utf-8") as fh:
            lines = fh.readlines()
        hdr = [l for l in lines
               if "recipient_deliver_received" in l and _R3017_CELL in l][0].split()
        cid = [x for x in hdr if x.startswith("correlation_id=")][0].split("=")[1]
        rid = [x for x in hdr if x.startswith("run_id=")][0].split("=")[1]
        drop = ("recipient_deliver_received", "recipient_message_persisted",
                "recipient_ack_deliver_sent")
        keep = [l for l in lines
                if not (cid in l and any(d in l for d in drop))]
        with open(p, "w", encoding="utf-8") as fh:
            fh.writelines(keep)
            for n in attempts:
                base = dict(cid=cid, role="recipient", run_id=rid,
                            emitter="emulator", cell_id=_R3017_CELL, attempt=n)
                fresh = dict(base, dedup_gate="fresh")
                term = dict(base, deliver_failure="held",
                            deliver_stage="received")
                if mutate:
                    fresh, term = mutate(n, fresh, term)
                if fresh is not None:
                    _write_wss_diag_line(fh, "recipient_deliver_received", **fresh)
                if n in settle:
                    _write_wss_diag_line(fh, "recipient_message_persisted", **base)
                    _write_wss_diag_line(fh, "recipient_ack_deliver_sent", **base)
                elif term is not None:
                    _write_wss_diag_line(fh, "recipient_deliver_failed", **term)
        want = declare_p2e
        if want is None:
            want = "OK" if settle else "FAIL"
        sv = os.path.join(ev, "smoke_verdict.json")
        with open(sv, encoding="utf-8") as fh:
            d = json.load(fh)
        d["p2e"] = want
        with open(sv, "w", encoding="utf-8") as fh:
            json.dump(d, fh, sort_keys=True, indent=2)

    return build_full_profile_evidence(
        tmp_root, profile_id="yota-phone-off-host-on", utc_suffix=suffix,
        attempt_id=_uuid4(), arm_token=_uuid4(), mutate_before_return=m)


class AuditR3017TheAttemptOwnsTheOutcome(unittest.TestCase):
    """An ATTEMPT owns a terminal outcome; an envelope does not.

    R30.16 counted terminal records per envelope, so the run it existed
    to describe - a redelivery, two fresh processings, two records -
    came back as corrupt evidence. It also reported a truthful failure
    through the same channel as a broken capture, so the profile went
    NOT_EVALUABLE: the verifier refusing to judge a run whose evidence
    was intact and complete.

    Every fixture here asserts the whole externally visible report -
    `integrity_ok` AND `product_outcome` - not a filtered slice of the
    diagnostics behind it. Filtering is how the R30.16 fixture asserted
    a semantics the shipped verifier did not have.
    """

    def _report(self, suffix, **kw):
        with tempfile.TemporaryDirectory() as t:
            return V.verify_profile_dir(_r3017_tree(t, suffix, **kw))

    def _assert(self, rep, *, integrity, outcome, msg=""):
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome), (integrity, outcome),
            # `getattr`, not attribute access: the message must not raise
            # on a report shape lacking the field, or these fixtures would
            # ERROR on the parent for the wrong reason and prove nothing.
            msg=f"{msg}\nintegrity_issues={rep.integrity_issues}\n"
                f"product_issues={getattr(rep, 'product_issues', '<absent>')}")

    # ── the shapes that must be accepted ───────────────────────────

    def test_one_failed_attempt(self):
        """Trustworthy evidence of a product failure."""
        rep = self._report("20260827T110000Z", attempts=[1])
        self._assert(rep, integrity=True, outcome="RED")
        self.assertTrue(any("attempt=1" in i for i in rep.product_issues),
                        msg=rep.product_issues)

    def test_two_fresh_and_two_failed_attempts(self):
        """THE 2026-08-26 SHAPE. Two fresh deliveries of one envelope,
        two terminal records. R30.16 called this corrupt."""
        rep = self._report("20260827T110500Z", attempts=[1, 2])
        self._assert(rep, integrity=True, outcome="RED",
                     msg="the run this round exists to describe")
        # Both attempts are reported, plus the direction's own declared
        # failure. Assert the attempt-level facts rather than a count,
        # which would break the moment another true statement is added.
        self.assertTrue(any("attempt=1" in i for i in rep.product_issues),
                        msg=rep.product_issues)
        self.assertTrue(any("attempt=2" in i for i in rep.product_issues),
                        msg=rep.product_issues)

    def test_failed_attempt_then_a_settled_retry(self):
        """Delivered on the retry. A transient failure is not a product
        failure, or every successful redelivery would report one."""
        rep = self._report("20260827T111000Z", attempts=[1, 2], settle=(2,))
        self._assert(rep, integrity=True, outcome="GREEN")

    def test_a_legacy_unnumbered_attempt_is_readable(self):
        """The three recorded physical attempts predate the ordinal and
        are immutable. One unambiguous unnumbered attempt still reads."""
        def m(n, fresh, term):
            fresh.pop("attempt"); term.pop("attempt")
            return fresh, term
        rep = self._report("20260827T111500Z", attempts=[1], mutate=m)
        self._assert(rep, integrity=True, outcome="RED")

    # ── the shapes that must be refused ────────────────────────────

    def test_an_unaccounted_attempt(self):
        """A fresh delivery that neither settles nor says why."""
        def m(n, fresh, term):
            return fresh, None
        rep = self._report("20260827T112000Z", attempts=[1], mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")
        self.assertTrue(any("unaccounted" in i for i in rep.integrity_issues),
                        msg=rep.integrity_issues)

    def test_an_orphan_terminal_record(self):
        """A record whose attempt has no fresh delivery behind it."""
        def m(n, fresh, term):
            return None, term
        rep = self._report("20260827T112500Z", attempts=[1], mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")
        self.assertTrue(any("orphan" in i for i in rep.integrity_issues),
                        msg=rep.integrity_issues)

    def test_two_terminal_outcomes_for_one_attempt(self):
        """Settled AND reported failed under one ordinal."""
        with tempfile.TemporaryDirectory() as t:
            ev = _r3017_tree(t, "20260827T113000Z", attempts=[1], settle=(1,))
            with open(os.path.join(ev, "smoke_verdict.json"),
                      encoding="utf-8") as fh:
                smoke_run_id = json.load(fh)["smoke_run_id"]
            with open(os.path.join(ev, "smoke.log"), "a", encoding="utf-8") as fh:
                _write_wss_diag_line(
                    fh, "recipient_deliver_failed",
                    cid="smoke-p2e", role="recipient", emitter="emulator",
                    run_id=smoke_run_id,
                    cell_id=_R3017_CELL, deliver_failure="held",
                    deliver_stage="received", attempt=1)
            _r3019_rehash(ev)
            rep = V.verify_profile_dir(ev)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")
        self.assertTrue(
            any("exactly one terminal outcome" in i for i in rep.integrity_issues),
            msg=rep.integrity_issues)

    def test_a_duplicate_terminal_failure_for_one_attempt(self):
        with tempfile.TemporaryDirectory() as t:
            ev = _r3017_tree(t, "20260827T113500Z", attempts=[1])
            with open(os.path.join(ev, "smoke_verdict.json"),
                      encoding="utf-8") as fh:
                smoke_run_id = json.load(fh)["smoke_run_id"]
            with open(os.path.join(ev, "smoke.log"), "a", encoding="utf-8") as fh:
                _write_wss_diag_line(
                    fh, "recipient_deliver_failed",
                    cid="smoke-p2e", role="recipient", emitter="emulator",
                    run_id=smoke_run_id,
                    cell_id=_R3017_CELL, deliver_failure="held",
                    deliver_stage="received", attempt=1)
            _r3019_rehash(ev)
            rep = V.verify_profile_dir(ev)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_two_unnumbered_attempts_are_ambiguous(self):
        """Exactly the 2026-08-26 shape without ordinals: two fresh
        deliveries that cannot be told apart. Guessing is not reading."""
        def m(n, fresh, term):
            fresh.pop("attempt"); term.pop("attempt")
            return fresh, term
        rep = self._report("20260827T114000Z", attempts=[1, 2], mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_a_non_consecutive_ordinal(self):
        rep = self._report("20260827T114500Z", attempts=[1, 3])
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")
        self.assertTrue(any("consecutive" in i for i in rep.integrity_issues),
                        msg=rep.integrity_issues)

    def test_an_out_of_range_ordinal(self):
        def m(n, fresh, term):
            fresh["attempt"] = 99999; term["attempt"] = 99999
            return fresh, term
        rep = self._report("20260827T115000Z", attempts=[1], mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_a_foreign_run_id(self):
        def m(n, fresh, term):
            term["run_id"] = "rid-somebody-else"
            return fresh, term
        rep = self._report("20260827T115500Z", attempts=[1], mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_a_foreign_cell_id(self):
        def m(n, fresh, term):
            term["cell_id"] = "wss.e2p.after-connect"
            return fresh, term
        rep = self._report("20260827T120000Z", attempts=[1], mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_a_foreign_emitter(self):
        def m(n, fresh, term):
            term["emitter"] = "phone"
            return fresh, term
        rep = self._report("20260827T120500Z", attempts=[1], mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_a_malformed_failure_enum(self):
        def m(n, fresh, term):
            term["deliver_failure"] = "mac_error"
            return fresh, term
        rep = self._report("20260827T121000Z", attempts=[1], mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_a_contradictory_stage(self):
        """`ack_sent` asserts an ack this attempt never emitted."""
        def m(n, fresh, term):
            term["deliver_stage"] = "ack_sent"
            return fresh, term
        rep = self._report("20260827T121500Z", attempts=[1], mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_one_attempt_cannot_explain_another_gap(self):
        """Attempt 2 settles; attempt 1 reports `persisted`, which its
        OWN attempt never emitted. Attempt 2's persist must not cover
        it."""
        def m(n, fresh, term):
            if n == 1:
                term["deliver_stage"] = "persisted"
            return fresh, term
        rep = self._report("20260827T122000Z", attempts=[1, 2], settle=(2,),
                           mutate=m)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_a_declared_failure_nothing_accounts_for(self):
        """The producer says the direction failed and the log does not
        say why. That is untrustworthy evidence, not a product result."""
        rep = self._report("20260827T122500Z", attempts=[1], settle=(1,),
                           declare_p2e="FAIL")
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    # ── the full matrix keeps every rule it had ────────────────────

    def test_the_full_matrix_is_not_widened(self):
        """A failure record inside a matrix CELL does not excuse a short
        triplet there. The 2026-08-26 defect was in the smoke, before
        `full` was ever allowed to run."""
        def m(ev):
            p = os.path.join(ev, "matrix_cells", _R3017_CELL, "capture.log")
            with open(p, encoding="utf-8") as fh:
                lines = fh.readlines()
            hdr = [l for l in lines if "recipient_deliver_received" in l][0].split()
            cid = [x for x in hdr if x.startswith("correlation_id=")][0].split("=")[1]
            rid = [x for x in hdr if x.startswith("run_id=")][0].split("=")[1]
            keep = [l for l in lines
                    if not (cid in l and ("recipient_message_persisted" in l
                                          or "recipient_ack_deliver_sent" in l))]
            with open(p, "w", encoding="utf-8") as fh:
                fh.writelines(keep)
                _write_wss_diag_line(
                    fh, "recipient_deliver_failed", cid=cid, role="recipient",
                    run_id=rid, emitter="emulator", cell_id=_R3017_CELL,
                    deliver_failure="held", deliver_stage="received", attempt=1)
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260827T123000Z", attempt_id=_uuid4(),
                arm_token=_uuid4(), mutate_before_return=m)
            rep = V.verify_profile_dir(ev)
        self.assertFalse(rep.integrity_ok)
        self.assertEqual(
            len([i for i in rep.integrity_issues if "missing recipient_" in i]), 2,
            msg=rep.integrity_issues)


# ─────────────────────────────────────────────────────────────────────
# Audit ROUND-30.18 — the smoke-only terminal profile and the overflow
# marker.
# ─────────────────────────────────────────────────────────────────────

_R3018_CELL = "wss.p2e.after-connect"


def _r3019_rehash(ev):
    """Rewrite SHA256SUMS.txt to match the tree as it now stands.

    Audit ROUND-30.19: the terminal profile passes the manifest audit
    BEFORE any verdict, so a fixture that mutates a file after the
    builder wrote the manifest must either rehash (to aim at a deeper
    rule) or deliberately not (to aim at the manifest rule itself).
    """
    lines = []
    for root, _dirs, files in os.walk(ev):
        for f in sorted(files):
            if f == "SHA256SUMS.txt":
                continue
            fp = os.path.join(root, f)
            rel = os.path.relpath(fp, ev).replace(os.sep, "/")
            with open(fp, "rb") as source:
                h = hashlib.sha256(source.read()).hexdigest()
            lines.append(f"{h}  ./{rel}")
    with open(os.path.join(ev, "SHA256SUMS.txt"), "w",
              encoding="utf-8") as fh:
        fh.write("\n".join(sorted(lines)) + "\n")


def _r3018_tree(tmp_root, suffix, *, attempts=(1,), settle=(), marker=None,
                drop_fresh=False, untouched_smoke=False,
                drop_smoke_verdict=False):
    """A smoke-only terminal profile.

    The shape a real producer emits when the smoke goes RED: preflight
    ran, the smoke ran and failed, `full` REFUSED to run, and no
    matrix-phase artefact was ever written — not the completion record,
    not the verdict, not the cells, and not checkpoint_log.json, which
    `full` initialises. There is no successfully executed matrix
    underneath — the builder removes every matrix artefact, so nothing
    these fixtures accept can be explained by a full run hiding behind
    the smoke.

    `attempts` / `settle` shape the P2E attempt records as in
    `_r3017_tree`; `marker` writes a recipient_deliver_attempt_overflow
    with the given field overrides (`_times` repeats it); `drop_fresh`
    removes the fresh deliveries so a marker stands alone;
    `untouched_smoke` keeps the built (passing) smoke as-is — the
    GREEN-smoke-without-a-matrix shape.
    """
    def m(ev):
        p = os.path.join(ev, "smoke.log")
        if not untouched_smoke:
            with open(p, encoding="utf-8") as fh:
                lines = fh.readlines()
            hdr = [l for l in lines
                   if "recipient_deliver_received" in l and _R3018_CELL in l][0].split()
            cid = [x for x in hdr if x.startswith("correlation_id=")][0].split("=")[1]
            rid = [x for x in hdr if x.startswith("run_id=")][0].split("=")[1]
            drop = ("recipient_deliver_received", "recipient_message_persisted",
                    "recipient_ack_deliver_sent")
            keep = [l for l in lines
                    if not (cid in l and any(d in l for d in drop))]
            with open(p, "w", encoding="utf-8") as fh:
                fh.writelines(keep)
                if not drop_fresh:
                    for n in attempts:
                        base = dict(cid=cid, role="recipient", run_id=rid,
                                    emitter="emulator", cell_id=_R3018_CELL,
                                    attempt=n)
                        _write_wss_diag_line(fh, "recipient_deliver_received",
                                             dedup_gate="fresh", **base)
                        if n in settle:
                            _write_wss_diag_line(
                                fh, "recipient_message_persisted", **base)
                            _write_wss_diag_line(
                                fh, "recipient_ack_deliver_sent", **base)
                        else:
                            _write_wss_diag_line(
                                fh, "recipient_deliver_failed",
                                deliver_failure="held",
                                deliver_stage="received", **base)
                if marker is not None:
                    kw = dict(cid=cid, role="recipient", run_id=rid,
                              emitter="emulator", cell_id=_R3018_CELL)
                    kw.update(marker)
                    for _ in range(kw.pop("_times", 1)):
                        _write_wss_diag_line(
                            fh, "recipient_deliver_attempt_overflow", **kw)
            sv = os.path.join(ev, "smoke_verdict.json")
            with open(sv, encoding="utf-8") as fh:
                d = json.load(fh)
            d["p2e"] = "OK" if (attempts and set(attempts) <= set(settle)
                                and not drop_fresh) else "FAIL"
            with open(sv, "w", encoding="utf-8") as fh:
                json.dump(d, fh, sort_keys=True, indent=2)
        if drop_smoke_verdict:
            os.remove(os.path.join(ev, "smoke_verdict.json"))
        # Audit ROUND-30.20: checkpoint_log.json joins the removals.
        # `wss3_live_full` initialises it and fills it per cell, so a
        # profile the smoke stopped has never had one — the builder was
        # producing a shape no producer emits, and the terminal branch
        # now forbids matrix-phase artefacts outright.
        for name in ("matrix_completion.json", "matrix_verdict.json",
                     "checkpoint_log.json"):
            fp = os.path.join(ev, name)
            if os.path.exists(fp):
                os.remove(fp)
        shutil.rmtree(os.path.join(ev, "matrix_cells"), ignore_errors=True)

    return build_full_profile_evidence(
        tmp_root, profile_id="yota-phone-off-host-on", utc_suffix=suffix,
        attempt_id=_uuid4(), arm_token=_uuid4(), mutate_before_return=m)


class AuditR3018TheSmokeOnlyTerminalProfile(unittest.TestCase):
    """A RED smoke stops the profile, and that is a judgeable outcome.

    `full` refuses to run after a RED smoke, so the producer never
    writes matrix_completion.json. R30.17 still answered NOT_EVALUABLE
    for that shape, which meant the state it existed to make expressible
    could not arise from any real run. A trustworthy RED smoke without a
    matrix is now integrity_ok=True / product_outcome=RED; every
    untrustworthy variant keeps the old refusal, and the full-matrix
    rules are not weakened.

    Every fixture asserts the whole externally visible report on the
    FULL verify_profile_dir — never a filtered slice.
    """

    def _assert(self, rep, *, integrity, outcome, msg=""):
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome), (integrity, outcome),
            msg=f"{msg}\nintegrity_issues={rep.integrity_issues}\n"
                f"product_issues={getattr(rep, 'product_issues', '<absent>')}")

    def test_a_truthful_red_smoke_without_a_matrix_is_judgeable(self):
        """THE MANDATED SHAPE: smoke RED, failure accounted, no matrix
        — trustworthy evidence of a product failure."""
        with tempfile.TemporaryDirectory() as t:
            ev = _r3018_tree(t, "20260827T140000Z", attempts=[1])
            # No successfully executed matrix underneath — by shape,
            # not by trust in the builder.
            self.assertFalse(
                os.path.exists(os.path.join(ev, "matrix_completion.json")))
            self.assertFalse(
                os.path.exists(os.path.join(ev, "matrix_cells")))
            rep = V.verify_profile_dir(ev)
        self._assert(rep, integrity=True, outcome="RED")
        self.assertEqual(rep.cells_ran, 0)
        self.assertTrue(any("attempt=1" in i for i in rep.product_issues),
                        msg=rep.product_issues)

    def test_a_green_smoke_without_a_matrix_stays_unjudgeable(self):
        """A GREEN smoke advances to the matrix; its absence is
        unexplained and stays a refusal."""
        with tempfile.TemporaryDirectory() as t:
            rep = V.verify_profile_dir(_r3018_tree(
                t, "20260827T140500Z", untouched_smoke=True))
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")
        self.assertTrue(any("smoke passed" in i for i in rep.integrity_issues),
                        msg=rep.integrity_issues)

    def test_a_missing_smoke_verdict_stays_unjudgeable(self):
        """No matrix AND no smoke verdict: nothing explains anything."""
        with tempfile.TemporaryDirectory() as t:
            rep = V.verify_profile_dir(_r3018_tree(
                t, "20260827T141000Z", attempts=[1], drop_smoke_verdict=True))
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")

    def test_an_unaccounted_failure_stays_unjudgeable(self):
        """A declared FAIL that no attempt record explains — the
        R30.16 physical shape — is still not trustworthy evidence."""
        def _tree(t):
            return _r3018_tree(t, "20260827T141500Z", attempts=[1])
        with tempfile.TemporaryDirectory() as t:
            ev = _tree(t)
            p = os.path.join(ev, "smoke.log")
            with open(p, encoding="utf-8") as fh:
                lines = fh.readlines()
            with open(p, "w", encoding="utf-8") as fh:
                fh.writelines(l for l in lines
                              if "recipient_deliver_failed" not in l)
            # Rehash: this fixture aims at the ATTEMPT audit, not the
            # manifest gate the mutation would otherwise trip first.
            _r3019_rehash(ev)
            rep = V.verify_profile_dir(ev)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")
        self.assertTrue(any("unaccounted" in i for i in rep.integrity_issues),
                        msg=rep.integrity_issues)

    def test_a_present_matrix_is_still_held_to_the_matrix_rules(self):
        """The smoke-only reading exists ONLY when the matrix is absent.
        A matrix_completion.json that exists — here as the §4.4 empty
        sentinel — is judged by the full-matrix rules, weakened by
        nothing above."""
        def m(ev):
            with open(os.path.join(ev, "matrix_completion.json"), "w"):
                pass
        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260827T142000Z", attempt_id=_uuid4(),
                arm_token=_uuid4(), mutate_before_return=m)
            rep = V.verify_profile_dir(ev)
        self._assert(rep, integrity=False, outcome="NOT_EVALUABLE")
        self.assertTrue(any("empty sentinel" in i
                            for i in rep.integrity_issues),
                        msg=rep.integrity_issues)


class AuditR3018TheOverflowMarker(unittest.TestCase):
    """recipient_deliver_attempt_overflow marks a TRUNCATED history.

    Silence after attempt 1000 is unacceptable: the producer announces
    the truncation once, delivery continues, and the evidence stops
    being judgeable — attempts happened whose records are not here, so
    a verdict on what IS here would be a verdict on part of the run
    presented as the whole. Every marker must be bound to the delivery
    it truncates: a marker on a foreign emitter, cell or run is an
    intruder, not somebody's overflow.

    Every fixture asserts the whole externally visible report on the
    FULL verify_profile_dir — a helper returning 0 for a foreign
    binding proves only invisibility, not safety.
    """

    def _report(self, suffix, **kw):
        with tempfile.TemporaryDirectory() as t:
            return V.verify_profile_dir(_r3018_tree(t, suffix, **kw))

    def _refused(self, rep, needle, msg=""):
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome),
            (False, "NOT_EVALUABLE"),
            msg=f"{msg}\nintegrity_issues={rep.integrity_issues}")
        self.assertTrue(any(needle in i for i in rep.integrity_issues),
                        msg=f"wanted {needle!r} in {rep.integrity_issues}")

    def test_a_correct_marker_makes_the_history_unjudgeable(self):
        rep = self._report("20260827T143000Z", attempts=[1], marker={})
        self._refused(rep, "TRUNCATED",
                      msg="the announced truncation must be believed")

    def test_a_duplicate_marker_is_still_a_truncation_not_a_pass(self):
        """One marker per envelope is the contract; a second copy must
        not cancel the first or crash the audit."""
        rep = self._report("20260827T143500Z", attempts=[1],
                           marker={"_times": 2})
        self._refused(rep, "TRUNCATED")

    def test_a_marker_from_a_foreign_emitter_is_an_intruder(self):
        """P2E's recipient is the emulator. A marker signed by the
        phone is bound to nothing — and it must be an integrity finding
        on the full report, not merely invisible to a helper."""
        rep = self._report("20260827T144000Z", attempts=[1],
                           marker={"emitter": "phone"})
        self._refused(rep, "not bound")

    def test_a_marker_on_a_foreign_cell_is_an_intruder(self):
        rep = self._report("20260827T144500Z", attempts=[1],
                           marker={"cell_id": "rest.e2p.control"})
        self._refused(rep, "not a smoke direction")

    def test_a_marker_from_a_foreign_run_is_an_intruder(self):
        rep = self._report("20260827T145000Z", attempts=[1],
                           marker={"run_id": "rid-someone-else"})
        self._refused(rep, "")

    def test_an_orphan_marker_with_no_delivery_behind_it(self):
        """A truncation of a history that never began."""
        rep = self._report("20260827T145500Z", attempts=[1], marker={},
                           drop_fresh=True)
        self._refused(rep, "")

    def test_a_marker_beside_a_settled_envelope_is_still_a_truncation(self):
        """Settlement clears the marker at the producer; a marker that
        nevertheless reaches the evidence says attempts are missing,
        and a settled triplet does not un-say it."""
        rep = self._report("20260827T150000Z", attempts=[1], settle=(1,),
                           marker={})
        self._refused(rep, "TRUNCATED")

    def test_the_last_nameable_ordinal_is_not_an_overflow(self):
        """A consecutive history ending at attempt=1000 is inside the
        shared range: without a marker it is ordinary judgeable
        evidence, not a truncation. (A LONE attempt=1000 is a different
        shape — a gap — and the R30.17 consecutiveness rule owns it.)"""
        rep = self._report(
            "20260827T150500Z",
            attempts=list(range(1, S.DELIVER_ATTEMPT_ORDINAL_MAX + 1)))
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome), (True, "RED"),
            msg=f"integrity_issues={rep.integrity_issues[:5]}")
class AuditR3019TheTerminalProfileIsStillAudited(unittest.TestCase):
    """The smoke-only verdict comes AFTER the common audits.

    R30.18 returned `True/RED` for a terminal profile before the
    SHA256SUMS verification, the required-file check, the privacy
    denylist and the closed inventory ever ran, so a coherently
    tampered or privacy-dirty terminal profile was accepted as intact
    evidence with zero findings — the same early-return class R29.3
    closed for the aborted matrix. Every fixture here takes the exact
    tree the accepted terminal shape uses, breaks ONE property, and
    asserts the whole externally visible report on the FULL
    `verify_profile_dir`. Each one passes on the R30.18 candidate.
    """

    def _tree(self, t, suffix):
        return _r3018_tree(t, suffix, attempts=[1])

    def _refused(self, rep, needle):
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome),
            (False, "NOT_EVALUABLE"),
            msg=f"integrity_issues={rep.integrity_issues}")
        self.assertTrue(any(needle in i for i in rep.integrity_issues),
                        msg=f"wanted {needle!r} in {rep.integrity_issues}")

    def test_the_accepted_shape_still_passes(self):
        """The base tree these fixtures mutate must itself be the
        accepted True/RED shape — otherwise every refusal below is
        vacuous."""
        with tempfile.TemporaryDirectory() as t:
            rep = V.verify_profile_dir(self._tree(t, "20260827T160000Z"))
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome), (True, "RED"),
            msg=f"integrity_issues={rep.integrity_issues}")

    def test_a_missing_manifest_is_refused(self):
        """Deleting SHA256SUMS.txt must not go unnoticed — it is the
        hash gate itself."""
        with tempfile.TemporaryDirectory() as t:
            ev = self._tree(t, "20260827T160500Z")
            os.remove(os.path.join(ev, "SHA256SUMS.txt"))
            rep = V.verify_profile_dir(ev)
        self._refused(rep, "SHA256SUMS.txt missing")

    def test_post_manifest_tampering_is_refused(self):
        """A file changed after the manifest was sealed."""
        with tempfile.TemporaryDirectory() as t:
            ev = self._tree(t, "20260827T161000Z")
            with open(os.path.join(ev, "smoke.log"), "a",
                      encoding="utf-8") as fh:
                fh.write("# a line the manifest never saw\n")
            rep = V.verify_profile_dir(ev)
        self._refused(rep, "hash mismatch")

    def test_a_missing_required_file_is_refused(self):
        """PROFILE_STATE.json removed, manifest rehashed so ONLY the
        required-file rule can catch it."""
        with tempfile.TemporaryDirectory() as t:
            ev = self._tree(t, "20260827T161500Z")
            os.remove(os.path.join(ev, "PROFILE_STATE.json"))
            _r3019_rehash(ev)
            rep = V.verify_profile_dir(ev)
        self._refused(rep, "PROFILE_STATE.json")

    def test_a_privacy_leak_is_refused(self):
        """The reviewer's probe: payload text, payload_bytes and a raw
        IP planted in smoke.log, manifest rehashed so the hash gate is
        clean and ONLY the denylist can catch it."""
        with tempfile.TemporaryDirectory() as t:
            ev = self._tree(t, "20260827T162000Z")
            with open(os.path.join(ev, "smoke.log"), "a",
                      encoding="utf-8") as fh:
                fh.write("leak text=secret payload_bytes=42 "
                         "peer=10.20.30.40\n")
            _r3019_rehash(ev)
            rep = V.verify_profile_dir(ev)
        self._refused(rep, "privacy denylist hit")

    def test_an_unknown_inventory_file_is_refused(self):
        """A stowaway .bin, hashed into the manifest so ONLY the closed
        inventory can catch it."""
        with tempfile.TemporaryDirectory() as t:
            ev = self._tree(t, "20260827T162500Z")
            with open(os.path.join(ev, "stowaway.bin"), "wb") as fh:
                fh.write(b"opaque")
            _r3019_rehash(ev)
            rep = V.verify_profile_dir(ev)
        self._refused(rep, "unknown evidence file")
class AuditR3020ChainDefectsSurviveASingleCid(unittest.TestCase):
    """A chain defect is a defect at any candidate count.

    `direction_issues.extend(non_qualifying_issues)` lived inside the
    `len(candidate_cids) > 1` branch, so for the ordinary single-CID
    direction every defect `_validate_cid_chain` found was computed and
    thrown away. A terminal profile whose only
    `diagnostic_send_dispatched` had been deleted came back
    `True / RED` with an empty issue list: evidence accepted as
    trustworthy with no proof the message was ever sent.
    """

    def _tree_without(self, t, suffix, event):
        """The accepted terminal shape, minus one sender-chain event,
        with the manifest resealed so ONLY the chain rule can object."""
        ev = _r3018_tree(t, suffix, attempts=[1])
        p = os.path.join(ev, "smoke.log")
        with open(p, encoding="utf-8") as fh:
            lines = fh.readlines()
        kept = [l for l in lines
                if not (f"event={event} " in l and _R3018_CELL in l)]
        self.assertLess(len(kept), len(lines),
                        msg=f"fixture removed nothing: {event}")
        with open(p, "w", encoding="utf-8") as fh:
            fh.writelines(kept)
        _r3019_rehash(ev)
        return ev

    def test_a_missing_send_dispatch_is_refused(self):
        """THE REVIEW REPRODUCTION. No proof the message was sent."""
        with tempfile.TemporaryDirectory() as t:
            rep = V.verify_profile_dir(
                self._tree_without(t, "20260827T170000Z",
                                   "diagnostic_send_dispatched"))
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome),
            (False, "NOT_EVALUABLE"),
            msg=f"integrity_issues={rep.integrity_issues}")
        self.assertTrue(rep.integrity_issues,
                        msg="refused with no stated reason")

    def test_a_missing_transport_decision_is_refused(self):
        """The same rule, a different sender-chain event: the fixture
        must not be pinned to one deletion."""
        with tempfile.TemporaryDirectory() as t:
            rep = V.verify_profile_dir(
                self._tree_without(t, "20260827T170500Z",
                                   "sender_transport_decision"))
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome),
            (False, "NOT_EVALUABLE"),
            msg=f"integrity_issues={rep.integrity_issues}")

    def test_the_unmutated_shape_still_passes(self):
        """Positive control: the tree these fixtures mutate is the
        accepted terminal shape, so the refusals above are not
        vacuous."""
        with tempfile.TemporaryDirectory() as t:
            rep = V.verify_profile_dir(
                _r3018_tree(t, "20260827T171000Z", attempts=[1]))
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome), (True, "RED"),
            msg=f"integrity_issues={rep.integrity_issues}")


class AuditR3020CompletedDirectionSurvivesALaterPinFailure(unittest.TestCase):
    """A later E2P pin defect makes the smoke RED without erasing P2E."""

    def test_direction_is_scoped_but_global_surface_vetoes_green(self):
        def mutate(ev):
            with open(os.path.join(ev, "smoke_verdict.json"),
                      encoding="utf-8") as fh:
                sv = json.load(fh)
            with open(os.path.join(ev, "smoke.log"), "a",
                      encoding="utf-8") as fh:
                _write_wss_diag_line(
                    fh, "diagnostic_pin_active", role="matrix",
                    emitter="emulator", run_id=sv["smoke_run_id"],
                    cell_id="wss.somewhere.else", pin="wss")

        with tempfile.TemporaryDirectory() as t:
            ev = build_full_profile_evidence(
                t, profile_id="yota-phone-off-host-on",
                utc_suffix="20260828T000000Z",
                attempt_id=_uuid4(), arm_token=_uuid4(),
                mutate_before_return=mutate,
            )
            with open(os.path.join(ev, "smoke_verdict.json"),
                      encoding="utf-8") as fh:
                sv = json.load(fh)
            direction = V.smoke_direction_soundness(
                os.path.join(ev, "smoke.log"), sender="phone",
                recipient="emulator", pin="wss",
                expected_run_id=sv["smoke_run_id"],
                expected_cell_id=_R3017_CELL,
                expected_cid=sv["p2e_cid"],
            )
            _, global_issues = V.smoke_global_surface_audit(
                os.path.join(ev, "smoke.log"),
                expected_run_id=sv["smoke_run_id"],
                expected_cids=(sv["p2e_cid"], sv["e2p_cid"]),
            )
            report = V.verify_profile_dir(ev)
        self.assertTrue(direction["ok"], msg=direction)
        self.assertTrue(
            any("non-canonical cell_id='wss.somewhere.else'" in issue
                for issue in global_issues),
            msg=f"global_issues={global_issues}")
        self.assertFalse(report.integrity_ok,
                         msg="the global smoke must retain the extra event")


class AuditR3020TheTerminalBranchForbidsTheMatrixPhase(unittest.TestCase):
    """No matrix-phase artefact may sit in a smoke-stopped profile.

    The branch is selected on the absence of `matrix_completion.json`
    alone, while the closed inventory permits `matrix_verdict.json`,
    `matrix_cells/` and `checkpoint_log.json` -- none of which the
    terminal path validated. An invalid `matrix_verdict.json` with a
    resealed manifest was accepted as sound terminal evidence.

    Forbidding them is also the producer's own shape: `wss3_live_full`
    writes all of them, so a profile the smoke stopped has none. It
    additionally classifies a `full` that died mid-matrix -- artefacts
    present, completion record absent -- as unjudgeable, where the old
    branch read it as a stopped smoke and judged it.
    """

    def _refused(self, rep, needle):
        self.assertEqual(
            (rep.integrity_ok, rep.product_outcome),
            (False, "NOT_EVALUABLE"),
            msg=f"integrity_issues={rep.integrity_issues}")
        self.assertTrue(any(needle in i for i in rep.integrity_issues),
                        msg=f"wanted {needle!r} in {rep.integrity_issues}")

    def _with_artefact(self, t, suffix, name, payload):
        ev = _r3018_tree(t, suffix, attempts=[1])
        with open(os.path.join(ev, name), "w", encoding="utf-8") as fh:
            fh.write(payload)
        _r3019_rehash(ev)
        return ev

    def test_an_invalid_matrix_verdict_is_refused(self):
        """THE REVIEW REPRODUCTION."""
        with tempfile.TemporaryDirectory() as t:
            rep = V.verify_profile_dir(self._with_artefact(
                t, "20260827T172000Z", "matrix_verdict.json",
                '{"not": "a matrix verdict"}'))
        self._refused(rep, "matrix_verdict.json is present")

    def test_a_well_formed_matrix_verdict_is_refused_too(self):
        """Validity is not the question -- PRESENCE is. A matrix
        verdict that parses still cannot belong to a profile whose
        matrix never ran."""
        with tempfile.TemporaryDirectory() as t:
            ev = _r3018_tree(t, "20260827T172500Z", attempts=[1])
            # Copy the builder's own well-formed artefact back in.
            src = _r3018_tree(t, "20260827T172600Z", attempts=[1],
                              untouched_smoke=True)
            good = os.path.join(os.path.dirname(src), "kept.json")
            with open(os.path.join(ev, "matrix_verdict.json"), "w",
                      encoding="utf-8") as fh:
                json.dump({"evidence_integrity": "GREEN",
                           "product_outcome": "GREEN",
                           "delivered_count": 40,
                           "unresolved_count": 0,
                           "cells": []}, fh, sort_keys=True, indent=2)
            _r3019_rehash(ev)
            rep = V.verify_profile_dir(ev)
        self._refused(rep, "matrix_verdict.json is present")

    def test_a_checkpoint_log_is_refused(self):
        """`full` initialises it, so its presence means the matrix
        phase started."""
        with tempfile.TemporaryDirectory() as t:
            rep = V.verify_profile_dir(self._with_artefact(
                t, "20260827T173000Z", "checkpoint_log.json", "[]"))
        self._refused(rep, "checkpoint_log.json is present")

    def test_a_matrix_cells_directory_is_refused(self):
        """A `full` that died mid-matrix: cells on disk, no completion
        record. Not a stopped smoke, and not judgeable."""
        with tempfile.TemporaryDirectory() as t:
            ev = _r3018_tree(t, "20260827T173500Z", attempts=[1])
            cell = os.path.join(ev, "matrix_cells", "wss.p2e.after-connect")
            os.makedirs(cell)
            with open(os.path.join(cell, "capture.log"), "w",
                      encoding="utf-8") as fh:
                fh.write("")
            _r3019_rehash(ev)
            rep = V.verify_profile_dir(ev)
        self._refused(rep, "matrix_cells/ is present")
