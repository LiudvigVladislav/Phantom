#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
"""WSS-3 Carrier x VPN Matrix — verifier extension module.

Contract: docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md §4.x + §7.

Reuse discipline (per REDLINE-2 blocker 2 + REDLINE-6 blocker 1 +
audit ROUND-8 P0-2 fail-open remediation):

  - The four accepted WSS-2 event frozensets (RECIPIENT_EVENTS /
    SENDER_EVENTS / MATRIX_EVENTS / CROSS_RUN_TOLERATED_EVENTS) plus
    the canonical cell-ID sets are IMPORTED from verify-evidence.py.
    Never restated. Fixture 65 pins the import edge.
  - The ACCEPTED WSS-2 log parser [`VE.parse_events`] is reused
    verbatim — it already handles real threadtime logcat
    (`WSS_DIAG(?::| :) event=...`) and the closed-schema strict
    boolean invariants. WSS-3 does NOT re-implement parsing.
  - WSS-3 layers cell-level coverage checks specific to the matrix
    orchestrator's output layout (matrix_cells/<cell-id>/ subdirs +
    smoke.log + smoke_verdict.json).

Fail-closed discipline (audit ROUND-8 P0-2):

  - Zero WSS_DIAG events in any capture file (smoke.log OR any
    matrix_cells/<cell-id>/*.log): the profile is NOT_EVALUABLE. Never
    GREEN.
  - Unknown event names (`event=` value NOT in the union of
    RECIPIENT_EVENTS + SENDER_EVENTS + MATRIX_EVENTS +
    CROSS_RUN_TOLERATED_EVENTS): integrity RED.
  - A non-blocked cell without exactly 5 unique dispatched CIDs OR any
    CID missing its route-return + recipient triplet + exactly one
    `dedup_gate=fresh`: RED.
  - Missing canonical cell subdir: RED.
  - Duplicate-only recipient events (all fresh counts == 0 despite
    dispatched sends): RED.
  - Forbidden client emitters (unresolved_120s_marker /
    sender_wss_frame_written): RED.

Public entry points:

  - verify_profile_dir(evidence_dir) → ProfileReport
  - verify_evidence_root(evidence_root) → MatrixReport (8 rows)
"""

from __future__ import annotations

import importlib.util
import json
import os
import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

# Audit ROUND-22 P0-1: schema_wss3.py is the SINGLE executable
# authority for closed/allowed key sets, required keys, exact
# JSON types, nullability, enums, pinned constants, min/max and
# regex. This module (verify_evidence_wss3.py) MUST import it and
# MUST NOT retain independent hand-written tables that duplicate
# that authority. Specialised semantic checks (cross-file bindings,
# event/CID lifecycle, counts, timestamps, privacy denylist,
# inventory, SHA256SUMS, outcome recomputation) remain additive.
import schema_wss3 as SCHEMA  # noqa: E402


# ── generic schema-driven validator (audit ROUND-22 P0-1) ──────────

def _schema_type_ok(spec: dict, v) -> bool:
    """Return True if `v`'s JSON type matches `spec['type']` (strict).
    bool never satisfies int (Python's bool-is-int is rejected)."""
    t = spec.get("type")
    if t == SCHEMA.T_STR:
        return isinstance(v, str)
    if t == SCHEMA.T_INT:
        return type(v) is int
    if t == SCHEMA.T_BOOL:
        return isinstance(v, bool)
    if t == SCHEMA.T_LIST:
        return isinstance(v, list)
    if t == SCHEMA.T_DICT:
        return isinstance(v, dict)
    return True


def _schema_nullability_ok(spec: dict, v) -> bool:
    if v is not None:
        return True
    nul = spec.get("nullability")
    return nul in (SCHEMA.N_NULL, SCHEMA.N_TRI)


def check_registered_surface(surface_id: str, obj) -> List[str]:
    """Audit ROUND-23 P1: the ONLY structural entry point verifier
    code should use. Reads schema DIRECTLY from
    `schema_wss3.SURFACES[surface_id]` and records the surface as
    consumed so the parity gate can trace which SURFACES entries
    the live verifier actually touches.

    Callers that pass a `surface_id` not in SURFACES fail early
    (surface_id_visit raises), which is a P0 audit failure —
    verifier and registry MUST agree.
    """
    schema = SCHEMA.surface_schema(surface_id)
    if schema is None:
        # Validator-only surface OR bogus surface_id — either way
        # this function is misused; do NOT silently accept.
        raise AssertionError(
            f"check_registered_surface({surface_id!r}) — no schema in "
            f"SURFACES (validator-only surfaces MUST use their "
            f"dedicated validator, not this generic path)."
        )
    SCHEMA.surface_id_visit(surface_id)
    return check_schema_object(surface_id, obj, schema)


def check_schema_object(surface_name: str, obj: dict,
                        schema: Dict[str, dict]) -> List[str]:
    """Apply the machine-readable schema to a JSON object:

    - closed set of allowed keys
    - required keys present
    - exact JSON type (bool != int)
    - nullability
    - enum membership
    - pinned value
    - min / max numeric range
    - regex

    Additive semantic checks (cross-file, temporal correlation, event
    lifecycle, etc.) stay in the specialised functions above; this
    generic pass ONLY enforces structural + primitive-value rules.
    """
    if not isinstance(obj, dict):
        return [f"{surface_name} must be a JSON object, got {type(obj).__name__}"]
    issues: List[str] = []
    allowed = set(schema.keys())
    _extra = sorted(set(obj.keys()) - allowed)
    if _extra:
        issues.append(
            f"{surface_name} has unknown key(s) {_extra!r} "
            f"(closed schema, audit ROUND-22 P0-1)"
        )
    required = {n for n, s in schema.items() if s.get("presence") == SCHEMA.P_REQ}
    _missing = sorted(required - set(obj.keys()))
    if _missing:
        issues.append(
            f"{surface_name} missing required key(s) {_missing!r} "
            f"(audit ROUND-22 P0-1)"
        )
    for name, spec in schema.items():
        if name not in obj:
            continue
        v = obj[name]
        # Nullability first: a nullable field with `None` short-circuits
        # the rest of the checks (a legitimate null carries no value
        # to constrain).
        if v is None:
            if not _schema_nullability_ok(spec, v):
                issues.append(
                    f"{surface_name}.{name}=null — non-nullable "
                    f"(audit ROUND-22 P0-1)"
                )
            continue
        # Type.
        if not _schema_type_ok(spec, v):
            issues.append(
                f"{surface_name}.{name}={v!r} (type={type(v).__name__}) "
                f"— must be {spec.get('type')} (audit ROUND-22 P0-1)"
            )
            continue
        # Enum.
        e = spec.get("enum")
        if e is not None and v not in e:
            issues.append(
                f"{surface_name}.{name}={v!r} not in enum {sorted(e)!r} "
                f"(audit ROUND-22 P0-1)"
            )
        # Pin.
        pin = spec.get("pin")
        if pin is not None and v != pin:
            issues.append(
                f"{surface_name}.{name}={v!r} — must equal pin {pin!r} "
                f"(audit ROUND-22 P0-1)"
            )
        # Min / max (numeric).
        mn = spec.get("min")
        if mn is not None and isinstance(v, int) and not isinstance(v, bool):
            if v < mn:
                issues.append(
                    f"{surface_name}.{name}={v!r} < min={mn!r} "
                    f"(audit ROUND-22 P0-2)"
                )
        mx = spec.get("max")
        if mx is not None and isinstance(v, int) and not isinstance(v, bool):
            if v > mx:
                issues.append(
                    f"{surface_name}.{name}={v!r} > max={mx!r} "
                    f"(audit ROUND-22 P0-2)"
                )
        # Regex.
        rx = spec.get("regex")
        if rx is not None and isinstance(v, str):
            if not re.match(rx, v):
                issues.append(
                    f"{surface_name}.{name}={v!r} — must match regex "
                    f"{rx!r} (audit ROUND-22 P0-1)"
                )
    return issues

# ── import frozensets + parser from accepted verify-evidence.py

_HERE = os.path.dirname(os.path.abspath(__file__))
_VE_PATH = os.path.join(_HERE, "verify-evidence.py")


def _load_verify_evidence():
    import sys as _sys
    if "verify_evidence" in _sys.modules:
        return _sys.modules["verify_evidence"]
    spec = importlib.util.spec_from_file_location("verify_evidence", _VE_PATH)
    module = importlib.util.module_from_spec(spec)
    _sys.modules["verify_evidence"] = module
    spec.loader.exec_module(module)
    return module


VE = _load_verify_evidence()

RECIPIENT_EVENTS = VE.RECIPIENT_EVENTS
SENDER_EVENTS = VE.SENDER_EVENTS
MATRIX_EVENTS = VE.MATRIX_EVENTS
# Audit ROUND-16 P0-1: use the accepted WSS-2 enum for
# diagnostic_send_command_completed.result so any value outside
# {handled, rejected, exception} is a schema violation, not a
# silently-tolerated free-form string.
ALLOWED_COMMAND_RESULTS = VE.ALLOWED_COMMAND_RESULTS
CROSS_RUN_TOLERATED_EVENTS = VE.CROSS_RUN_TOLERATED_EVENTS
CANONICAL_MATRIX_TRIPLES = VE.CANONICAL_MATRIX_TRIPLES
CANONICAL_REST_CELL_IDS = VE.CANONICAL_REST_CELL_IDS
ALLOWED_COMMAND_RESULTS = VE.ALLOWED_COMMAND_RESULTS
# Reuse WSS-2 parser directly — handles real threadtime logcat
# (`WSS_DIAG: event=...` / `WSS_DIAG : event=...`) via the same regex
# WSS-2 uses in production evidence bundles.
parse_events = VE.parse_events

# Union allowlist. Any event whose `event=` value is not here is a
# schema violation → integrity RED.
ALL_ALLOWED_EVENTS = (
    RECIPIENT_EVENTS | SENDER_EVENTS | MATRIX_EVENTS | CROSS_RUN_TOLERATED_EVENTS
)

# Emitters explicitly forbidden — even if some plumbing tries to write
# them, they force RED.
FORBIDDEN_CLIENT_EMITTERS = frozenset({
    "unresolved_120s_marker",
    "sender_wss_frame_written",
})

# retry_reason enum per §3.4.
EXPLICIT_RETRY_REASONS = frozenset({
    "vpn_provider_change",
    "sim_reseat",
    "sdk_environment_recovery",
    "mid_run_operator_interrupt",
    "network_transient_recovery",
})
BARE_RESUME_RETRY_REASON = "resume_incomplete"
ALL_RETRY_REASONS = EXPLICIT_RETRY_REASONS | {BARE_RESUME_RETRY_REASON}

UUIDV4_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
PROFILE_ID_RE = re.compile(
    r"^(?P<carrier>yota|tele2)-phone-(?P<phone>off|on)-host-(?P<host>off|on)$"
)
EVIDENCE_DIR_RE = re.compile(
    r"^(?P<pid>(?:yota|tele2)-phone-(?:off|on)-host-(?:off|on))-(?P<utc>\d{8}T\d{6}Z)$"
)

# Contract-fixed: 5 unique dispatched CIDs per non-blocked cell (§3.3).
CIDS_PER_CELL = 5


# ── data shapes

@dataclass
class ProfileReport:
    profile_id: str
    integrity_ok: bool
    product_outcome: str  # "GREEN" | "RED" | "NOT_EVALUABLE"
    integrity_issues: List[str] = field(default_factory=list)
    # ── Audit ROUND-30.17 ───────────────────────────────────
    # Things the PRODUCT did wrong, kept apart from things the EVIDENCE
    # got wrong. R30.16 had one channel, so a truthfully reported
    # delivery failure was indistinguishable from a corrupt capture and
    # drove the profile to NOT_EVALUABLE - the verifier refused to
    # evaluate a run whose evidence was in fact intact and complete.
    product_issues: List[str] = field(default_factory=list)
    cells_declared: int = 8
    cells_expected_to_run: int = 8
    cells_ran: int = 0
    abort_reason: Optional[str] = None
    attempts_seen: int = 0
    latest_attempt_id: Optional[str] = None
    retry_lineage_present: bool = False
    arm_token: Optional[str] = None


@dataclass
class MatrixReport:
    rows: List[ProfileReport]
    retry_lineage_present: bool = False
    integrity_issues: List[str] = field(default_factory=list)


# ── PROFILE.json schema (audit ROUND-22 P0-1: DERIVED from schema)
# Handwritten literal removed. `schema_wss3.PROFILE_SCHEMA` is the
# single authority; the parity gate in
# tests/test_verifier_wss3_mutations.py asserts consumer code stays
# in sync with the schema entries.
REQUIRED_PROFILE_KEYS = frozenset(SCHEMA.PROFILE_SCHEMA.keys())


_APK_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def _derive_confirm_string(carrier: str, phone_vpn: str, host_vpn: str) -> str:
    """Mirror the shell helper `wss3_confirmation_string` at
    lib/wss3-args.sh:56-63."""
    return f"CONFIRM-{carrier}-PHONE-{str(phone_vpn).upper()}-HOST-{str(host_vpn).upper()}"


def validate_profile_json(profile: dict) -> Tuple[bool, List[str]]:
    # Audit ROUND-14 P1-5: top-level type guard — a JSON list/string
    # would blow up `.keys()` and abort the verifier before the file
    # could be classified as NOT_EVALUABLE.
    if not isinstance(profile, dict):
        return False, [f"PROFILE.json must be a JSON object, got {type(profile).__name__}"]
    # Audit ROUND-22 P0-1: structural pass via schema authority.
    # Closed keys + required + type + enum + pin + regex all come
    # from `schema_wss3.PROFILE_SCHEMA`.
    issues = list(check_registered_surface("PROFILE.json", profile))
    if any("missing required key" in i or "unknown key" in i for i in issues):
        return False, issues
    if not isinstance(profile["arm_token"], str) or not UUIDV4_RE.match(profile["arm_token"]):
        issues.append(f"PROFILE.json.arm_token not a lowercase UUIDv4: {profile['arm_token']!r}")
    omc = profile["operator_manual_confirmations"]
    if not isinstance(omc, dict):
        issues.append(f"PROFILE.json.operator_manual_confirmations must be object, got {type(omc).__name__}")
    else:
        want = {"auto_data_switching_off", "other_sim_data_disabled"}
        got = set(omc.keys())
        if got != want:
            issues.append(f"PROFILE.json.operator_manual_confirmations keys must be exactly {sorted(want)}, got {sorted(got)}")
        else:
            for k, v in omc.items():
                # Audit ROUND-13 P0-2: require value be exactly the
                # boolean `True`. `False` is a contract violation
                # (operator confirmed nothing → profile not armed).
                # Strings "true"/"false" and integers 0/1 fail the
                # isinstance-bool check (Python treats bool as int
                # subclass, so use type(v) is bool for strictness).
                if type(v) is not bool:
                    issues.append(f"PROFILE.json.operator_manual_confirmations.{k} must be bool, got {type(v).__name__}")
                elif v is not True:
                    issues.append(
                        f"PROFILE.json.operator_manual_confirmations.{k}={v!r} — "
                        f"must be True (operator MUST confirm both prerequisites)"
                    )
    if not isinstance(profile["attempt_id"], str) or not UUIDV4_RE.match(profile["attempt_id"]):
        issues.append(f"PROFILE.json.attempt_id not a UUIDv4: {profile['attempt_id']!r}")
    sup = profile.get("supersedes_attempt_id")
    if sup is not None and (not isinstance(sup, str) or not UUIDV4_RE.match(sup)):
        issues.append(f"PROFILE.json.supersedes_attempt_id not null and not a UUIDv4: {sup!r}")
    # Audit ROUND-14 P1-5: type-safe enum membership. `retry_reason=[]`
    # would have thrown `TypeError: unhashable type: 'list'` inside
    # the frozenset `in` check, killing the verifier instead of
    # yielding an integrity issue.
    rr = profile.get("retry_reason")
    if rr is not None:
        if not isinstance(rr, str):
            issues.append(
                f"PROFILE.json.retry_reason must be null or a string in the enum, "
                f"got {type(rr).__name__}: {rr!r}"
            )
        elif rr not in ALL_RETRY_REASONS:
            issues.append(f"PROFILE.json.retry_reason {rr!r} not in enum {sorted(ALL_RETRY_REASONS)}")
    if (sup is None) != (rr is None):
        issues.append("PROFILE.json.supersedes_attempt_id and retry_reason must be paired (both null or both set)")
    if profile["carrier"] not in ("YOTA", "TELE2"):
        issues.append(f"PROFILE.json.carrier not in {{YOTA, TELE2}}: {profile['carrier']!r}")
    if profile["phone_vpn"] not in ("off", "on"):
        issues.append(f"PROFILE.json.phone_vpn not in {{off, on}}: {profile['phone_vpn']!r}")
    if profile["host_vpn"] not in ("off", "on"):
        issues.append(f"PROFILE.json.host_vpn not in {{off, on}}: {profile['host_vpn']!r}")
    # Audit ROUND-13 P0-2: apk_sha256 MUST be 64-char lowercase hex.
    # An empty string previously passed structurally and was compared
    # against observed APK SHAs with `[ -n ]` guards that silently
    # short-circuited on the empty case.
    apk = profile.get("apk_sha256")
    if not isinstance(apk, str) or not _APK_SHA256_RE.match(apk or ""):
        issues.append(f"PROFILE.json.apk_sha256 not a 64-char lowercase hex: {apk!r}")
    # Audit ROUND-13 P0-2: confirm_string MUST match the derived value
    # for (carrier, phone_vpn, host_vpn). Any drift is a schema
    # violation (an operator armed a different profile than the header
    # claims).
    cs = profile.get("confirm_string")
    if isinstance(profile.get("carrier"), str) and \
       isinstance(profile.get("phone_vpn"), str) and \
       isinstance(profile.get("host_vpn"), str):
        want_cs = _derive_confirm_string(profile["carrier"],
                                         profile["phone_vpn"],
                                         profile["host_vpn"])
        if cs != want_cs:
            issues.append(
                f"PROFILE.json.confirm_string={cs!r} does not match "
                f"derived value {want_cs!r}"
            )
    return (len(issues) == 0), issues


# ── matrix_completion.json schema

# Audit ROUND-22 P0-1: DERIVED from schema.
REQUIRED_COMPLETION_KEYS = frozenset(
    n for n, s in SCHEMA.MATRIX_COMPLETION_SCHEMA.items()
    if s.get("presence") == SCHEMA.P_REQ
)

# Audit ROUND-23 P0: DERIVED from schema authority.
# `schema_wss3.ABORT_REASON_ENUM` is the single source of truth for
# checkpoint_log + matrix_completion abort_reason values. The
# handwritten literal was removed.
VALID_ABORT_REASONS = SCHEMA.ABORT_REASON_ENUM


def validate_completion_json(mc: dict) -> Tuple[bool, List[str]]:
    # Audit ROUND-14 P1-5: type-safe. Prior version threw
    # `TypeError: unhashable type: 'list'` when
    # `abort_reason=[]` reached the frozenset `in` check, killing
    # the entire verifier instead of yielding NOT_EVALUABLE. Every
    # membership check now runs behind a strict type guard.
    issues: List[str] = []
    if not isinstance(mc, dict):
        return False, [f"matrix_completion.json must be a JSON object, got {type(mc).__name__}"]
    missing = REQUIRED_COMPLETION_KEYS - set(mc.keys())
    if missing:
        issues.append(f"matrix_completion.json missing required keys: {sorted(missing)}")
        return False, issues
    # Audit ROUND-22 P0-1: DERIVED from schema (full key set).
    _ALLOWED_MC_KEYS = frozenset(SCHEMA.MATRIX_COMPLETION_SCHEMA.keys())
    # Structural pass via schema authority (types, enums, pins,
    # min/max, regex, nullability).
    issues.extend(check_registered_surface("matrix_completion.json", mc))
    _extra_mc = sorted(set(mc.keys()) - _ALLOWED_MC_KEYS)
    if _extra_mc:
        issues.append(
            f"matrix_completion.json has unknown key(s) {_extra_mc!r} "
            f"(closed schema, audit ROUND-18 P0-4)"
        )
        return False, issues
    for k in ("cells_declared", "cells_expected_to_run", "cells_ran"):
        v = mc[k]
        # Reject bool early (bool is a subclass of int in Python — a
        # `True` would pass isinstance(int) and mask a real bug).
        if type(v) is not int:
            issues.append(f"matrix_completion.json.{k} must be int, got {type(v).__name__}: {v!r}")
    if isinstance(mc["cells_declared"], int) and mc["cells_declared"] != 8:
        issues.append(f"matrix_completion.json.cells_declared must be 8, got {mc['cells_declared']}")
    if isinstance(mc["cells_expected_to_run"], int) and mc["cells_expected_to_run"] not in (6, 8):
        issues.append(f"matrix_completion.json.cells_expected_to_run must be 6 or 8, got {mc['cells_expected_to_run']}")
    ar = mc["abort_reason"]
    if ar is None:
        pass  # explicit None is valid
    elif not isinstance(ar, str):
        # Audit ROUND-14 P1-5: `abort_reason=[]` used to blow up
        # the frozenset membership check with TypeError. Now it
        # yields an integrity issue and the row classifies as
        # NOT_EVALUABLE per contract §7 R18.
        issues.append(
            f"matrix_completion.json.abort_reason must be null or a "
            f"string in the enum, got {type(ar).__name__}: {ar!r}"
        )
    elif ar not in VALID_ABORT_REASONS:
        issues.append(f"matrix_completion.json.abort_reason {ar!r} not in enum")
    return (len(issues) == 0), issues


# ── cell subdirectory scanning + coverage (audit P0-2 hardening)

def canonical_cell_ids() -> List[str]:
    """Returns the 8 canonical cell IDs in canonical order."""
    return [
        "wss.p2e.after-connect", "wss.e2p.after-connect",
        "wss.p2e.after-idle",    "wss.e2p.after-idle",
        "wss.p2e.bg-fg",         "wss.e2p.bg-fg",
        "rest.p2e.control",      "rest.e2p.control",
    ]


def load_cell_verdict(cell_dir: str) -> Tuple[Optional[dict], List[str]]:
    """Audit ROUND-16 P0-3: return (obj-or-None, issues) so the
    caller can surface `cell_verdict.json missing` / malformed /
    non-dict as integrity issues. The ROUND-15 signature returned
    None silently, which let a canonical WSS cell survive without a
    valid verdict (architect proved `cell_verdict.json=[]` and
    malformed-JSON both stayed GREEN)."""
    p = os.path.join(cell_dir, "cell_verdict.json")
    if not os.path.isfile(p):
        return None, ["cell_verdict.json missing"]
    try:
        with open(p, encoding="utf-8") as f:
            obj = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        return None, [f"cell_verdict.json malformed: {e}"]
    if not isinstance(obj, dict):
        return None, [
            f"cell_verdict.json must be a JSON object, got {type(obj).__name__}"
        ]
    return obj, []


def parse_events_in_dir(evidence_dir: str, device_label: str = "cell") -> Tuple[List, List[str]]:
    """Parse every WSS_DIAG line under evidence_dir using the accepted
    WSS-2 parser. Returns (events, parse_errors).

    Audit ROUND-11 P1-#7: parse errors from the underlying WSS-2
    parser (malformed `wall_utc_ms`, malformed booleans, etc.) MUST
    NOT be silently dropped — a matrix where every dispatched event
    carried a broken `wall_utc_ms` used to pass GREEN because the
    caller threw the error list away on `_errs`. Callers now receive
    the error list and surface it as an integrity issue.
    """
    out: List = []
    all_errors: List[str] = []
    if not os.path.isdir(evidence_dir):
        return out, all_errors
    for f in sorted(os.listdir(evidence_dir)):
        if not (f.endswith(".log") or f.endswith(".txt") or f.endswith(".jsonl")):
            continue
        events, errs = parse_events(os.path.join(evidence_dir, f), device_label)
        out.extend(events)
        all_errors.extend(errs)
    return out, all_errors


def _validate_cid_chain(
    cid: str,
    cid_events: List,
    *,
    cell_id: str,
    expected_sender_emitter: Optional[str],
    expected_recipient_emitter: Optional[str],
    expected_inner_route: Optional[str],
    profile_run_id: Optional[str],
) -> List[str]:
    """Audit ROUND-12 P0-#3 + P0-#5 + ROUND-13 P0-1 — the single
    per-CID validator, hardened.

    Beyond the ROUND-12 coherent-single-event checks, this now also:
      - requires `outer_transport=direct` on the transport decision
        (any other value — reality, tor, etc. — forces RED);
      - checks event roles: sender events must carry `role=sender`,
        recipient events `role=recipient`, diagnostic_send_dispatched
        `role=matrix`;
      - refuses opposite-route returns (a REST-return event under an
        inner_route=wss cell, or vice versa);
      - refuses CONTRADICTORY duplicate events (two
        sender_transport_decision events for the same CID with
        different outer_transport / inner_route / dispatched values;
        two route-return events with different names).
    """
    issues: List[str] = []
    # Audit ROUND-14 P0-2 hardened per audit ROUND-15 P0-1: per-event
    # validation across the WHOLE cid_events set. Every event MUST
    # carry the correct cell_id, the profile run_id (when known), AND
    # the correct role for its event class.
    #
    # Use the IMPORTED accepted WSS-2 sets (SENDER_EVENTS,
    # RECIPIENT_EVENTS, MATRIX_EVENTS) — architect ROUND-15 P0-1
    # proved that local reduced sets missed
    # `sender_ack_watchdog_requeued`, `sender_prekey_deferred`,
    # `diagnostic_send_command_completed`, and the two rejection
    # events, letting an appended `sender_ack_watchdog_requeued
    # role=matrix` and `diagnostic_send_command_completed role=sender`
    # slip through as GREEN.
    _SENDER_EVENTS = SENDER_EVENTS
    _RECIPIENT_EVENTS = RECIPIENT_EVENTS
    _MATRIX_EVENTS = MATRIX_EVENTS
    for e in cid_events:
        if getattr(e, "cell_id", None) != cell_id:
            issues.append(
                f"{cell_id} cid={cid}: event {e.event!r} has cell_id="
                f"{getattr(e, 'cell_id', None)!r} != want {cell_id!r} "
                "(audit ROUND-12 P0-#5: no cell_id tolerance)"
            )
        if profile_run_id is not None and getattr(e, "run_id", None) != profile_run_id:
            issues.append(
                f"{cell_id} cid={cid}: event {e.event!r} has run_id="
                f"{getattr(e, 'run_id', None)!r} != profile run_id={profile_run_id!r}"
            )
        # Audit ROUND-14 P0-2: role-per-event-class check. Every
        # sender-side event must be role=sender + emitter=sender;
        # every recipient event must be role=recipient + emitter=
        # recipient; diagnostic_send_dispatched must be role=matrix.
        _role = getattr(e, "role", None)
        _emitter = e.emitter_id
        if e.event in _SENDER_EVENTS:
            if _role != "sender":
                issues.append(
                    f"{cell_id} cid={cid}: {e.event!r}.role={_role!r} "
                    f"— must be 'sender'"
                )
            if _emitter != expected_sender_emitter:
                issues.append(
                    f"{cell_id} cid={cid}: {e.event!r}.emitter_id={_emitter!r} "
                    f"— must be sender {expected_sender_emitter!r}"
                )
        elif e.event in _RECIPIENT_EVENTS:
            if _role != "recipient":
                issues.append(
                    f"{cell_id} cid={cid}: {e.event!r}.role={_role!r} "
                    f"— must be 'recipient'"
                )
            if _emitter != expected_recipient_emitter:
                issues.append(
                    f"{cell_id} cid={cid}: {e.event!r}.emitter_id={_emitter!r} "
                    f"— must be recipient {expected_recipient_emitter!r}"
                )
        elif e.event in _MATRIX_EVENTS:
            if _role != "matrix":
                issues.append(
                    f"{cell_id} cid={cid}: {e.event!r}.role={_role!r} "
                    f"— must be 'matrix'"
                )
            if _emitter != expected_sender_emitter:
                issues.append(
                    f"{cell_id} cid={cid}: {e.event!r}.emitter_id={_emitter!r} "
                    f"— must be sender {expected_sender_emitter!r}"
                )

    # Audit ROUND-16 P0-1 + ROUND-17 P0-4: require EXACTLY ONE
    # `sender_send_attempt_started` per CID. Architect proved that
    # duplicated attempt_started stayed GREEN.
    attempt_started = [e for e in cid_events
                       if e.event == "sender_send_attempt_started"
                       and e.emitter_id == expected_sender_emitter
                       and getattr(e, "role", None) == "sender"]
    if len(attempt_started) == 0:
        issues.append(
            f"{cell_id} cid={cid}: no sender_send_attempt_started "
            f"on emitter={expected_sender_emitter!r} with role='sender' "
            f"(audit ROUND-16 P0-1: production entrypoint required)"
        )
    elif len(attempt_started) > 1:
        issues.append(
            f"{cell_id} cid={cid}: {len(attempt_started)} "
            f"sender_send_attempt_started events — must be exactly 1 "
            f"(audit ROUND-17 P0-4)"
        )

    # Audit ROUND-17 P0-4: EXACTLY ONE diagnostic_send_dispatched
    # per CID. The ROUND-16 dictionary de-duplicated on CID key and
    # kept the first sequence, so a duplicate dispatched (with the
    # same sequence) previously stayed GREEN.
    # Audit ROUND-30.20: the rule said EXACTLY ONE and tested only the
    # upper bound, so ZERO passed in silence. Deleting a direction's
    # only `diagnostic_send_dispatched` left a profile that still read
    # as sound evidence — a delivery verdict with no record that
    # anything was ever dispatched. The sentence was right; the
    # predicate implemented half of it.
    dispatched_here = [e for e in cid_events
                       if e.event == "diagnostic_send_dispatched"]
    if len(dispatched_here) == 0:
        issues.append(
            f"{cell_id} cid={cid}: no diagnostic_send_dispatched — "
            f"must be exactly 1; nothing records that this envelope was "
            f"ever dispatched (audit ROUND-30.20)"
        )
    elif len(dispatched_here) > 1:
        issues.append(
            f"{cell_id} cid={cid}: {len(dispatched_here)} "
            f"diagnostic_send_dispatched events — must be exactly 1 "
            f"(audit ROUND-17 P0-4)"
        )

    # Audit ROUND-16 P0-1: `sender_prekey_deferred` for this CID
    # means the envelope never reached transport — it CANNOT
    # coexist with the Delivered chain. Presence forces RED.
    prekey_deferred = [e for e in cid_events if e.event == "sender_prekey_deferred"]
    if prekey_deferred:
        issues.append(
            f"{cell_id} cid={cid}: sender_prekey_deferred present — "
            f"envelope did not reach transport; incompatible with "
            f"Delivered chain (audit ROUND-16 P0-1)"
        )

    # Audit ROUND-16 P0-1: validate every
    # `diagnostic_send_command_completed.result` against the accepted
    # WSS-2 enum (handled|rejected|exception). A bogus result stayed
    # GREEN because the field was never enum-checked.
    for e in cid_events:
        if e.event == "diagnostic_send_command_completed":
            r_val = getattr(e, "result", None)
            if r_val not in ALLOWED_COMMAND_RESULTS:
                issues.append(
                    f"{cell_id} cid={cid}: diagnostic_send_command_completed.result="
                    f"{r_val!r} not in {sorted(ALLOWED_COMMAND_RESULTS)} "
                    f"(audit ROUND-16 P0-1)"
                )
            # A rejected/exception completion is also incompatible
            # with a Delivered chain — the send did not authoritatively
            # deliver, so the recipient triplet (and any WSS relay ack)
            # is semantically forbidden together with this completion.
            if r_val in ("rejected", "exception"):
                issues.append(
                    f"{cell_id} cid={cid}: diagnostic_send_command_completed.result="
                    f"{r_val!r} incompatible with Delivered chain "
                    f"(audit ROUND-16 P0-1)"
                )

    # diagnostic_send_dispatched MUST carry role=matrix per contract
    # §3.3; a wrong-role dispatched event is a schema violation
    # (architect ROUND-13 P0-1 proved role=sender there stayed GREEN).
    disp = [e for e in cid_events if e.event == "diagnostic_send_dispatched"]
    if disp:
        for e in disp:
            r = getattr(e, "role", None)
            if r != "matrix":
                issues.append(
                    f"{cell_id} cid={cid}: diagnostic_send_dispatched.role={r!r} — must be 'matrix'"
                )
            if e.emitter_id != expected_sender_emitter:
                issues.append(
                    f"{cell_id} cid={cid}: diagnostic_send_dispatched.emitter_id="
                    f"{e.emitter_id!r} — must equal sender {expected_sender_emitter!r}"
                )

    # SINGLE coherent sender_transport_decision — dispatched=true AND
    # emitter=sender AND role=sender AND outer_transport=direct AND
    # inner_route=<pin> all on the SAME event. Audit ROUND-13 P0-1:
    # role and outer_transport were not checked before.
    coherent_td = [
        e for e in cid_events
        if e.event == "sender_transport_decision"
        and getattr(e, "dispatched", None) is True
        and e.emitter_id == expected_sender_emitter
        and getattr(e, "role", None) == "sender"
        and getattr(e, "outer_transport", None) == "direct"
        and (expected_inner_route is None or getattr(e, "inner_route", None) == expected_inner_route)
    ]
    if not coherent_td:
        all_td = [e for e in cid_events if e.event == "sender_transport_decision"]
        if not all_td:
            issues.append(f"{cell_id} cid={cid}: missing sender_transport_decision")
        else:
            summary = [
                f"(dispatched={getattr(e,'dispatched',None)}, "
                f"emitter={e.emitter_id}, role={getattr(e,'role',None)}, "
                f"outer_transport={getattr(e,'outer_transport',None)}, "
                f"inner_route={getattr(e,'inner_route',None)})"
                for e in all_td
            ]
            issues.append(
                f"{cell_id} cid={cid}: no single sender_transport_decision with "
                f"dispatched=true + emitter={expected_sender_emitter!r} + "
                f"role='sender' + outer_transport='direct' + "
                f"inner_route={expected_inner_route!r}; observed={summary}"
            )
    # Audit ROUND-13 P0-1: CONTRADICTORY duplicate transport_decision
    # events for the same CID with divergent load-bearing fields
    # (a second event with different outer_transport / inner_route /
    # dispatched) force RED. A well-formed run has exactly one such
    # event per CID; multiple with different values means the sender
    # log is inconsistent.
    all_td = [e for e in cid_events if e.event == "sender_transport_decision"]
    if len(all_td) >= 2:
        # Collect the (outer_transport, inner_route, dispatched) tuple
        # from every occurrence; if the set has >1 unique tuples this
        # is a contradiction.
        signatures = {
            (getattr(e, "outer_transport", None),
             getattr(e, "inner_route", None),
             getattr(e, "dispatched", None))
            for e in all_td
        }
        if len(signatures) > 1:
            issues.append(
                f"{cell_id} cid={cid}: contradictory sender_transport_decision "
                f"events for same CID; observed signatures="
                f"{sorted(signatures, key=str)}"
            )

    # Route-return — SINGLE coherent event with the RIGHT name,
    # emitter=sender, role=sender, and (audit ROUND-14 P0-1)
    # `inner_route=<pin>` PRESENT AND MATCHING (no None tolerance).
    # Audit ROUND-30.21: the terminal proof is route-dependent. WSS
    # returns require `dispatched=true`; REST completions require
    # `relay_acceptance in {accepted, duplicate}` and MUST NOT carry
    # the WSS-only `dispatched` field. Both routes already prove the
    # common dispatch at `sender_transport_decision` above.
    rr_name = ("sender_wss_send_returned" if expected_inner_route == "wss"
               else "sender_rest_post_completed" if expected_inner_route == "rest"
               else None)
    if rr_name is not None:
        coherent_rr = []
        for e in cid_events:
            if e.event != rr_name:
                continue
            rr_dispatched = getattr(e, "dispatched", None)
            if expected_inner_route == "wss" and rr_dispatched is not True:
                continue
            if expected_inner_route == "rest" and rr_dispatched is not None:
                continue
            if e.emitter_id != expected_sender_emitter:
                continue
            if getattr(e, "role", None) != "sender":
                continue
            # Audit ROUND-14 P0-1: inner_route MUST be present and
            # match expected — None is no longer tolerated.
            if getattr(e, "inner_route", None) != expected_inner_route:
                continue
            # REST-return: relay_acceptance MUST be present and in
            # {accepted, duplicate}.
            if expected_inner_route == "rest":
                ra = getattr(e, "relay_acceptance", None)
                if ra not in ("accepted", "duplicate"):
                    continue
            coherent_rr.append(e)
        if not coherent_rr:
            all_rr = [e for e in cid_events if e.event == rr_name]
            if not all_rr:
                issues.append(f"{cell_id} cid={cid}: missing route return {rr_name}")
            else:
                summary = [
                    f"(dispatched={getattr(e,'dispatched',None)}, "
                    f"emitter={e.emitter_id}, role={getattr(e,'role',None)}, "
                    f"inner_route={getattr(e,'inner_route',None)}, "
                    f"relay_acceptance={getattr(e,'relay_acceptance',None)})"
                    for e in all_rr
                ]
                if expected_inner_route == "rest":
                    needs = ("dispatched absent + "
                             "inner_route='rest' + relay_acceptance in "
                             "{'accepted','duplicate'}")
                else:
                    needs = "dispatched=true + inner_route='wss'"
                issues.append(
                    f"{cell_id} cid={cid}: no single {rr_name} with "
                    f"emitter={expected_sender_emitter!r} + role='sender' + "
                    f"{needs}; observed={summary}"
                )
        # Audit ROUND-14 P0-1: also refuse SAME-name duplicates
        # with contradictory signatures (a second wss_send_returned
        # for the same CID with different dispatched / inner_route
        # / relay_acceptance).
        same_name_rr = [e for e in cid_events if e.event == rr_name]
        if len(same_name_rr) >= 2:
            sigs = {
                (getattr(e, "dispatched", None),
                 getattr(e, "inner_route", None),
                 getattr(e, "relay_acceptance", None))
                for e in same_name_rr
            }
            if len(sigs) > 1:
                issues.append(
                    f"{cell_id} cid={cid}: contradictory {rr_name} events "
                    f"for same CID; observed signatures={sorted(sigs, key=str)}"
                )
        # Audit ROUND-13 P0-1: an OPPOSITE-route return event under
        # this CID (REST-return under a WSS cell, or vice versa) is
        # a hard schema violation.
        opposite = ("sender_rest_post_completed" if expected_inner_route == "wss"
                    else "sender_wss_send_returned")
        opp_events = [e for e in cid_events if e.event == opposite]
        if opp_events:
            issues.append(
                f"{cell_id} cid={cid}: opposite route-return {opposite!r} "
                f"present under inner_route={expected_inner_route!r} cell"
            )

    # Audit ROUND-30.21: sender_relay_ack_received is an asynchronous
    # WSS sender<->relay artifact. REST completion is the synchronous
    # HTTP acceptance above, so a REST CID must not contain this WSS-only
    # event.
    ra = [e for e in cid_events if e.event == "sender_relay_ack_received"
          and e.emitter_id == expected_sender_emitter
          and getattr(e, "role", None) == "sender"]
    if expected_inner_route == "wss" and not ra:
        issues.append(
            f"{cell_id} cid={cid}: missing sender_relay_ack_received on "
            f"emitter={expected_sender_emitter!r} with role='sender'"
        )
    elif expected_inner_route == "rest" and ra:
        issues.append(
            f"{cell_id} cid={cid}: sender_relay_ack_received is WSS-only "
            f"and must be absent from a REST chain"
        )

    # recipient triplet — each event must be on RECIPIENT emitter with
    # role=recipient
    for req in ("recipient_deliver_received",
                "recipient_message_persisted",
                "recipient_ack_deliver_sent"):
        rec = [e for e in cid_events if e.event == req
               and e.emitter_id == expected_recipient_emitter
               and getattr(e, "role", None) == "recipient"]
        if not rec:
            issues.append(
                f"{cell_id} cid={cid}: missing {req} on "
                f"emitter={expected_recipient_emitter!r} with role='recipient'"
            )

    # EXACTLY one fresh deliver on recipient emitter with role=recipient
    fresh = sum(
        1 for e in cid_events
        if e.event == "recipient_deliver_received"
        and e.emitter_id == expected_recipient_emitter
        and getattr(e, "role", None) == "recipient"
        and getattr(e, "dedup_gate", None) == "fresh"
    )
    if fresh != 1:
        issues.append(f"{cell_id} cid={cid}: dedup_gate=fresh count = {fresh} (must be exactly 1)")

    # ── Audit ROUND-30.16: a reported failure is EVIDENCE, not a
    #    defect in the evidence ──────────────────────────────────
    #
    # The 2026-08-26 physical smoke produced two fresh deliveries for one
    # envelope and no triplet, and the stream said nothing about a
    # failure at all. The recipient now reports the postcondition, so a
    # failed delivery leaves a record instead of a silence.
    #
    # A well-formed `recipient_deliver_failed` therefore does NOT make
    # `evidence_integrity` RED. The evidence is intact — it is the
    # PRODUCT that failed, and the incomplete triplet above already says
    # so. Integrity is reserved for evidence that cannot be trusted:
    #
    #   * a value outside the closed vocabulary;
    #   * the wrong emitter or role;
    #   * more than one failure record for one envelope;
    #   * a stage that the event stream contradicts;
    #   * a failure claimed over a COMPLETE settled triplet — the two
    #     claims cannot both be true.
    #
    # Conflating the two would have made every honest failure report
    # look like a broken capture, which is the fastest way to teach an
    # operator to distrust the diagnostic.
    _failed = [e for e in cid_events if e.event == "recipient_deliver_failed"]
    if _failed:
        _well_formed = []
        for e in _failed:
            _bad = False
            if (e.emitter_id != expected_recipient_emitter
                    or getattr(e, "role", None) != "recipient"):
                issues.append(
                    f"{cell_id} cid={cid}: recipient_deliver_failed on "
                    f"emitter={e.emitter_id!r} role={getattr(e, 'role', None)!r} "
                    f"— it belongs to the recipient emitter "
                    f"{expected_recipient_emitter!r} (audit ROUND-30.16)"
                )
                _bad = True
            reason = getattr(e, "deliver_failure", None)
            stage = getattr(e, "deliver_stage", None)
            if reason not in DELIVER_FAILURE_ENUM:
                issues.append(
                    f"{cell_id} cid={cid}: recipient_deliver_failed carries "
                    f"deliver_failure={reason!r}, not one of "
                    f"{sorted(DELIVER_FAILURE_ENUM)} (audit ROUND-30.16)"
                )
                _bad = True
            if stage not in DELIVER_STAGE_ENUM:
                issues.append(
                    f"{cell_id} cid={cid}: recipient_deliver_failed carries "
                    f"deliver_stage={stage!r}, not one of "
                    f"{sorted(DELIVER_STAGE_ENUM)} (audit ROUND-30.16)"
                )
                _bad = True
            if not _bad:
                _well_formed.append(e)

        if len(_failed) > 1:
            issues.append(
                f"{cell_id} cid={cid}: {len(_failed)} recipient_deliver_failed "
                f"records for one envelope — a delivery reports its outcome "
                f"exactly once (audit ROUND-30.16)"
            )

        # A stage claims every earlier stage completed. The stream has to
        # agree, or one of the two is lying.
        _seen = {ev.event for ev in cid_events
                 if ev.emitter_id == expected_recipient_emitter
                 and getattr(ev, "role", None) == "recipient"}
        _needs = {
            "persisted": "recipient_message_persisted",
            "ledger_marked": "recipient_message_persisted",
            "ack_sent": "recipient_ack_deliver_sent",
        }
        for e in _well_formed:
            stage = getattr(e, "deliver_stage", None)
            req = _needs.get(stage)
            if req and req not in _seen:
                issues.append(
                    f"{cell_id} cid={cid}: recipient_deliver_failed claims "
                    f"deliver_stage={stage!r} but no {req} was observed — the "
                    f"stage and the event stream contradict each other "
                    f"(audit ROUND-30.16)"
                )

        # A settled triplet and a failure record cannot both be true.
        _settled = {"recipient_deliver_received",
                    "recipient_message_persisted",
                    "recipient_ack_deliver_sent"} <= _seen
        if _settled and _well_formed:
            issues.append(
                f"{cell_id} cid={cid}: recipient_deliver_failed alongside a "
                f"complete settled triplet — the envelope cannot be both "
                f"settled and failed (audit ROUND-30.16)"
            )

    return issues


CLEAR_CELL_SUFFIX = ".clear"


def clear_cell_id(parent_cell: str) -> str:
    """Audit ROUND-30.11 — the ONE derivation of a clear identity.

    The producer forms it in `wss3_pin_clear_cell_id`; this is the same
    rule on the reading side. Keeping it in one function on each side is
    what lets the verifier LINK a clear back to its parent instead of
    seeing an unrelated cell id.
    """
    return f"{parent_cell}{CLEAR_CELL_SUFFIX}"


def clear_parent_cell(cell_id: str):
    """The parent a clear identity belongs to, or None if it is not one."""
    if isinstance(cell_id, str) and cell_id.endswith(CLEAR_CELL_SUFFIX):
        parent = cell_id[: -len(CLEAR_CELL_SUFFIX)]
        return parent or None
    return None


# Audit ROUND-30.12, moved to the schema authority in ROUND-30.13 —
# the abort reasons that name ONE cell. Re-exported under the name this
# module already used so call sites and fixtures keep one spelling.
CELL_SCOPED_ABORT_REASONS: frozenset = SCHEMA.CELL_SCOPED_ABORT_REASONS

# ── Audit ROUND-30.16 ────────────────────────────────────
# The closed vocabulary of `recipient_deliver_failed`. Both come from
# the schema module, so widening either one in the producer without
# widening it here is a schema violation rather than an unnoticed
# vocabulary drift.
DELIVER_FAILURE_ENUM: frozenset = SCHEMA.DELIVER_FAILURE_ENUM
DELIVER_STAGE_ENUM: frozenset = SCHEMA.DELIVER_STAGE_ENUM
DELIVER_ATTEMPT_ORDINAL_MAX: int = SCHEMA.DELIVER_ATTEMPT_ORDINAL_MAX


def _is_cell_scoped_abort(value) -> bool:
    """Membership that survives a malformed value.

    A wrong-typed `abort_reason` (a dict, a list) is reported by the
    schema pass; the branch rules must simply not treat it as a
    cell-scoped reason. Testing membership directly raises TypeError on
    an unhashable value, which would turn a finding into a crash — the
    adversarial mutation matrix caught exactly that.
    """
    return isinstance(value, str) and value in CELL_SCOPED_ABORT_REASONS


def run_level_abort_in_cell(value) -> bool:
    """Audit ROUND-30.14 — a reason a CELL cannot witness.

    `VALID_ABORT_REASONS` (the schema's `ABORT_REASON_ENUM`) mixes two
    kinds of reason. Two of them are
    claims about one cell; the other twelve describe the RUN - a
    checkpoint drift, a lost pair, an operator interrupt - and a cell
    is not the thing that observes them.

    R30.13 moved the cell-scoped set into the schema authority and
    called it the authority for that distinction, but left
    `CELL_VERDICT_SCHEMA["abort_reason"]` accepting the full enum, so
    nothing executable consulted it: a cell claiming `operator_interrupt`
    passed the structural pass, the content pass and the coherence pass
    in silence. The schema enum is the primary fix. This predicate is
    the defence behind it, and it deliberately does NOT ask what the
    matrix aborted with, where the cell sits, or whether the run aborted
    at all - none of those change whether a cell can witness a run-level
    event.
    """
    return (isinstance(value, str)
            and value in VALID_ABORT_REASONS
            and value not in CELL_SCOPED_ABORT_REASONS)


def is_clear_breadcrumb(e, allowed_parents: set) -> bool:
    """Audit ROUND-30.11 — the ONE place a clear event is RECOGNISED.

    The round gave a clear its own derived identity, `<parent>.clear`,
    because reusing the parent id would put a second
    `diagnostic_pin_active` into that cell's dual-party set where
    exactly one per party is allowed. Two gates then reject any
    non-canonical cell id — per cell, and across smoke.log — and they
    rejected the producer's own clears, so a healthy run could not be
    GREEN.

    Recognising the event here is what lets those gates admit it
    WITHOUT being widened. Nothing about the clear stops being
    checked: `check_clear_breadcrumbs` still requires exactly one per
    party, `role=matrix`, `pin=none` and the right run, and
    `check_orphan_clear_events` still reports a clear whose parent did
    not run. An event that merely resembles one — a pin that is not
    `none`, a parent outside this evidence — is NOT recognised and
    stays the non-canonical cell id it was.
    """
    if getattr(e, "event", None) != "diagnostic_pin_active":
        return False
    if getattr(e, "pin", None) != "none":
        return False
    parent = clear_parent_cell(getattr(e, "cell_id", None))
    return parent is not None and parent in allowed_parents


def check_cell_abort_branches(
    cells: List[dict],
    *,
    matrix_abort_reason,
    source: str = "cell",
) -> List[str]:
    """Audit ROUND-30.11 — the two cell-level aborts are DIFFERENT states.

    `pin_unconfirmed` and `pin_clear_unconfirmed` share an enum and
    nothing else, and collapsing them would undo the point of the round:

      pin_unconfirmed        the cell never ran. Nothing was dispatched,
                             the counters are zero and the verdict is
                             `Unresolved`.
      pin_clear_unconfirmed  the cell RAN. Its envelopes were dispatched
                             and delivered, those counters are observed
                             facts, and its verdict is the ordinary one
                             computed from them. Only the CLEANUP failed,
                             which is recorded in `post_cell_clear_emitter`
                             and aborts the matrix.

    Reusing the first one's rules for the second would demand zero
    counters and an Unresolved cell — erasing envelopes the capture
    proves. That is the exact false-evidence shape this audit exists to
    prevent, so the branches are written separately rather than as one
    rule with a widened enum.

    Also enforced here: the matrix-level abort must agree with the cell
    that caused it, everything after the aborting cell is PENDING, and
    `post_cell_clear_emitter` may not appear under any other reason.
    """
    issues: List[str] = []
    _aborting_index = None
    for _i, cv in enumerate(cells):
        cid = cv.get("cell_id")
        reason = cv.get("abort_reason")
        emitter = cv.get("post_cell_clear_emitter")
        disp = cv.get("envelopes_dispatched")
        deliv = cv.get("delivered_count")
        verdict = cv.get("verdict")

        # ── Audit ROUND-30.14 ─────────────────────────
        # Third layer, and the only one that sees the whole matrix. It
        # sits INSIDE the per-cell loop on purpose: the R30.13 claim
        # rules below run only when the MATRIX reason is cell-scoped, so
        # a cell claiming a run-level reason on a run that never aborted
        # - `matrix_abort_reason=None` - reached none of them.
        if run_level_abort_in_cell(reason):
            issues.append(
                f"{source} {cid}: abort_reason={reason!r} is a RUN-level "
                f"reason - it describes the run, not a cell, and no cell "
                f"records it (audit ROUND-30.14)"
            )

        if emitter is not None and reason != "pin_clear_unconfirmed":
            issues.append(
                f"{source} {cid}: post_cell_clear_emitter={emitter!r} with "
                f"abort_reason={reason!r} — that field belongs to "
                f"'pin_clear_unconfirmed' only (audit ROUND-30.11)"
            )

        if reason == "pin_unconfirmed":
            # The cell never ran.
            if disp not in (0, None):
                issues.append(
                    f"{source} {cid}: abort_reason='pin_unconfirmed' requires "
                    f"envelopes_dispatched=0, got {disp!r} — nothing is sent "
                    f"behind an unconfirmed pin (audit ROUND-30.11)"
                )
            if deliv not in (0, None):
                issues.append(
                    f"{source} {cid}: abort_reason='pin_unconfirmed' requires "
                    f"delivered_count=0, got {deliv!r} (audit ROUND-30.11)"
                )
            if verdict != "Unresolved":
                issues.append(
                    f"{source} {cid}: abort_reason='pin_unconfirmed' requires "
                    f"verdict='Unresolved', got {verdict!r} "
                    f"(audit ROUND-30.11)"
                )
            _aborting_index = _i if _aborting_index is None else _aborting_index

        elif reason == "pin_clear_unconfirmed":
            # The cell RAN. Its numbers are facts and stay.
            if emitter is None:
                issues.append(
                    f"{source} {cid}: abort_reason='pin_clear_unconfirmed' "
                    f"requires post_cell_clear_emitter — the failing side must "
                    f"be named (audit ROUND-30.11)"
                )
            if not isinstance(disp, int) or disp <= 0:
                issues.append(
                    f"{source} {cid}: abort_reason='pin_clear_unconfirmed' "
                    f"requires a real envelopes_dispatched, got {disp!r} — the "
                    f"cell completed, only its cleanup failed "
                    f"(audit ROUND-30.11)"
                )
            if verdict == "PENDING":
                issues.append(
                    f"{source} {cid}: abort_reason='pin_clear_unconfirmed' "
                    f"cannot be PENDING — the cell ran (audit ROUND-30.11)"
                )
            # The ordinary result must still follow from the counters.
            if isinstance(disp, int) and isinstance(deliv, int):
                _expected = "Delivered" if (disp >= 5 and deliv >= 5) else "Unresolved"
                if verdict not in (_expected, "BLOCKED"):
                    issues.append(
                        f"{source} {cid}: abort_reason='pin_clear_unconfirmed' "
                        f"verdict={verdict!r} does not follow from "
                        f"dispatched={disp!r}/delivered={deliv!r} (expected "
                        f"{_expected!r}) — a cleanup failure does not change "
                        f"what was delivered (audit ROUND-30.11)"
                    )
            _aborting_index = _i if _aborting_index is None else _aborting_index

    # ── Audit ROUND-30.12: the count of aborting cells is itself a rule
    #
    # R30.11 checked agreement only once an aborting cell had been
    # found, so the matrix could declare a cell-scoped abort that no
    # cell corroborated and the claim was never tested — only the
    # cell-to-matrix direction was enforced. A matrix reason is a claim
    # about the evidence like any other, and an uncorroborated claim is
    # exactly what this verifier exists to refuse.
    #
    # ── Audit ROUND-30.13 ──────────────────────────────────────────
    # R30.12 counted only the cells whose reason was itself one of the
    # two cell-scoped values, so a cell recording any OTHER enum value
    # was invisible to the count — and whether that mattered depended on
    # where it sat. Before the matching cell it produced nothing; after
    # it, the later-rows PENDING rule caught it sideways while
    # describing a different defect. The contract already said "zero
    # corroborating cells, more than one, or a cell recording a
    # different reason are each findings", so the rule existed on paper
    # and not in the code.
    #
    # The invariant is about CLAIMS, not about reasons: when the matrix
    # blames one cell, exactly one cell may claim the abort, and it must
    # claim the same thing. Position does not enter into it.
    _claiming = [(_i, cv) for _i, cv in enumerate(cells)
                 if cv.get("abort_reason") is not None]
    _matching = [(_i, cv) for _i, cv in _claiming
                 if cv.get("abort_reason") == matrix_abort_reason]
    _divergent = [(_i, cv) for _i, cv in _claiming
                  if cv.get("abort_reason") != matrix_abort_reason]
    _aborting = [(_i, cv) for _i, cv in enumerate(cells)
                 if _is_cell_scoped_abort(cv.get("abort_reason"))]
    if _is_cell_scoped_abort(matrix_abort_reason):
        if not _matching:
            issues.append(
                f"{source}: matrix abort_reason={matrix_abort_reason!r} but no "
                f"cell recorded it — a cell-scoped abort must name exactly one "
                f"aborting cell (audit ROUND-30.12)"
            )
        elif len(_matching) > 1:
            _names = ", ".join(repr(cv.get("cell_id")) for _i, cv in _matching)
            issues.append(
                f"{source}: {len(_matching)} cells record "
                f"abort_reason={matrix_abort_reason!r} ({_names}) — exactly one "
                f"cell may claim the abort (audit ROUND-30.13)"
            )
        for _i, cv in _divergent:
            issues.append(
                f"{source} {cv.get('cell_id')!r}: records "
                f"abort_reason={cv.get('abort_reason')!r} while the matrix "
                f"aborted with {matrix_abort_reason!r} — exactly one cell may "
                f"claim the abort and it must record the same reason "
                f"(audit ROUND-30.13)"
            )

    # The matrix-level reason must be the one the aborting cell recorded.
    if _aborting_index is not None:
        _cell_reason = cells[_aborting_index].get("abort_reason")
        if matrix_abort_reason != _cell_reason:
            issues.append(
                f"{source}: matrix abort_reason={matrix_abort_reason!r} does "
                f"not match the aborting cell "
                f"{cells[_aborting_index].get('cell_id')!r} which recorded "
                f"{_cell_reason!r} (audit ROUND-30.11)"
            )
        # Everything after it must be a never-attempted row.
        for cv in cells[_aborting_index + 1:]:
            if cv.get("verdict") != "PENDING":
                issues.append(
                    f"{source} {cv.get('cell_id')!r}: follows the aborting "
                    f"cell but is {cv.get('verdict')!r} — every later cell is "
                    f"PENDING once the matrix aborts (audit ROUND-30.11)"
                )
    return issues


def _clear_failed_emitters(value, sender_emitter: str, recipient_emitter: str) -> set:
    """Audit ROUND-30.11 — the closed value, resolved to emitters.

    `pre_send_pin_emitter` and `post_send_clear_emitter` are members of
    ONE closed set: `phone`, `emulator`, `both`. This maps that value to
    the emitters it names, by comparison against the pair this
    direction actually uses — no splitting, no parsing, nothing that
    would quietly accept a joined string. An unrecognised value yields
    the empty set, so every party is then held to its evidence and the
    schema reports the bad value separately.
    """
    if value == "both":
        return {sender_emitter, recipient_emitter}
    if value in (sender_emitter, recipient_emitter):
        return {value}
    return set()


def check_clear_breadcrumbs(
    events: List,
    *,
    parent_cell: str,
    sender_emitter: str,
    recipient_emitter: str,
    profile_run_id: Optional[str],
    source: str = "cell",
    expected_missing: Optional[set] = None,
) -> List[str]:
    """Audit ROUND-30.11 — a completed direction/cell must PROVE its
    clear, with the same strictness the `pin=wss` set already has.

    The producer clears both devices after every direction and every cell
    that completed, including the last one, and a clear is only confirmed
    when the device emits `diagnostic_pin_active ... pin=none`. That
    breadcrumb reaches `smoke.log` and the full capture, so it is
    evidence like any other: exactly one from the sender, exactly one
    from the recipient, both `role=matrix`, both bound to this run and to
    the DERIVED cell id, both carrying `pin=none`.

    The derived id exists because reusing the parent cell id would put a
    second `diagnostic_pin_active` into that cell's dual-party set, where
    exactly one per party is allowed — a healthy run would turn RED. It
    is emphatically NOT a way to leave the verifier's field of view: it
    is linked back to its parent here, and `check_orphan_clear_events`
    reports any clear whose parent is not a cell that ran.
    """
    issues: List[str] = []
    if not sender_emitter or not recipient_emitter:
        return issues
    cell = clear_cell_id(parent_cell)
    parties = {sender_emitter: "sender", recipient_emitter: "recipient"}
    seen: Dict[str, list] = {sender_emitter: [], recipient_emitter: []}
    for e in events:
        if e.event != "diagnostic_pin_active" or e.cell_id != cell:
            continue
        em = getattr(e, "emitter_id", None)
        if em not in seen:
            issues.append(
                f"{source} {parent_cell}: clear breadcrumb from intruder "
                f"emitter {em!r} on {cell!r} — only {sender_emitter!r} and "
                f"{recipient_emitter!r} clear this cell (audit ROUND-30.11)"
            )
            continue
        seen[em].append(e)
    _expected_missing = expected_missing or set()
    for em, party in parties.items():
        got = seen[em]
        if em in _expected_missing:
            # The verdict NAMES this side as the one that did not
            # confirm, so the absence is the expected evidence. The
            # link is checked in both directions: a side declared
            # failed that nevertheless produced a fresh breadcrumb
            # means the verdict and the log disagree, and the verdict
            # is the one making a claim.
            if len(got) > 0:
                issues.append(
                    f"{source} {parent_cell}: the verdict reports the {party} "
                    f"({em!r}) as an unconfirmed clear, but {cell!r} carries "
                    f"{len(got)} `pin=none` breadcrumb(s) from it "
                    f"(audit ROUND-30.11)"
                )
            continue
        if len(got) == 0:
            # Reported per party, so a run where NEITHER side cleared
            # produces two independent findings rather than one summary
            # that hides half the failure.
            issues.append(
                f"{source} {parent_cell}: missing clear breadcrumb from the "
                f"{party} ({em!r}) — a completed direction/cell must prove "
                f"`pin=none` on {cell!r} (audit ROUND-30.11)"
            )
            continue
        if len(got) > 1:
            issues.append(
                f"{source} {parent_cell}: {len(got)} clear breadcrumbs from "
                f"the {party} ({em!r}) on {cell!r} — exactly one is allowed "
                f"(audit ROUND-30.11)"
            )
        for e in got:
            if getattr(e, "role", None) != "matrix":
                issues.append(
                    f"{source} {parent_cell}: clear breadcrumb from the "
                    f"{party} ({em!r}) has role={getattr(e, 'role', None)!r} "
                    f"— must be 'matrix' (audit ROUND-30.11)"
                )
            _p = getattr(e, "pin", None)
            if _p != "none":
                issues.append(
                    f"{source} {parent_cell}: clear breadcrumb from the "
                    f"{party} ({em!r}) has pin={_p!r} — a clear is "
                    f"`pin=none` (audit ROUND-30.11)"
                )
            _r = getattr(e, "run_id", None)
            if profile_run_id is not None and _r != profile_run_id:
                issues.append(
                    f"{source} {parent_cell}: clear breadcrumb from the "
                    f"{party} ({em!r}) has run_id={_r!r} != {profile_run_id!r} "
                    f"(audit ROUND-30.11)"
                )
    return issues


def check_orphan_clear_events(
    events: List,
    *,
    known_parents: set,
    source: str = "cell",
) -> List[str]:
    """Audit ROUND-30.11 — a clear that belongs to nothing is a finding.

    `check_clear_breadcrumbs` looks for the clears a completed cell OWES.
    On its own that is not enough: a clear carrying some other parent —
    a stale run, a cell that never ran, a typo, another operator's
    identity — simply would not be looked at, and the derived id would
    have become a way to put events into the evidence that nobody
    checks. That is the failure this pairs with: a wrong `<cell>.clear`
    produces BOTH a `missing` finding at the parent that expected it AND
    an orphan finding here, never one silently standing in for the other.
    """
    issues: List[str] = []
    for e in events:
        if e.event != "diagnostic_pin_active":
            continue
        parent = clear_parent_cell(getattr(e, "cell_id", None))
        if parent is None:
            continue
        if parent not in known_parents:
            issues.append(
                f"{source}: orphan clear breadcrumb {e.cell_id!r} from "
                f"{getattr(e, 'emitter_id', None)!r} — its parent cell "
                f"{parent!r} did not run in this evidence "
                f"(audit ROUND-30.11)"
            )
    return issues


def check_dual_party_pin(
    events: List,
    *,
    cell_id: str,
    sender_emitter: str,
    recipient_emitter: str,
    expected_pin: Optional[str],
    profile_run_id: Optional[str],
    source: str = "cell",
    require_both: bool = True,
) -> List[str]:
    """Audit ROUND-29.3 P0 / ROUND-29.4 P0 — the ONE dual-party
    ownership rule for `diagnostic_pin_active`, shared by the full
    matrix and the smoke.

    Since ROUND-29 the orchestrator pins BOTH devices to a cell — that
    is the fix for the field's false attribution, because recipient
    events carry the RECIPIENT's own local `cell_id`. The recipient's
    breadcrumb is therefore REQUIRED evidence, not noise.

    R29.3 implemented this for full cells only, so deleting the
    recipient breadcrumb from a healthy `smoke.log` still passed
    GREEN/GREEN. One helper, two call sites: a rule that exists in
    only one of the two paths is not a rule.

    Ownership is NOT relaxed to "any canonical emitter". The contract
    is a strict SET: exactly one breadcrumb from the sender, exactly
    one from the recipient, both `role=matrix`, both bound to this
    `run_id`, `cell_id` and expected pin. Missing, duplicate,
    intruder, wrong role, wrong pin, wrong run and wrong cell are
    each RED.
    """
    issues: List[str] = []
    if not sender_emitter or not recipient_emitter:
        return issues
    _PIN_EVENT = "diagnostic_pin_active"
    parties = {sender_emitter: "sender", recipient_emitter: "recipient"}
    seen: Dict[str, list] = {sender_emitter: [], recipient_emitter: []}
    for e in events:
        if e.event != _PIN_EVENT or e.cell_id != cell_id:
            continue
        em = getattr(e, "emitter_id", None)
        if em not in seen:
            issues.append(
                f"{source} {cell_id}: {_PIN_EVENT} from intruder emitter "
                f"{em!r} — only {sender_emitter!r} and {recipient_emitter!r} "
                f"may pin this cell (audit ROUND-29.3 P0)"
            )
            continue
        seen[em].append(e)
    for em, party in parties.items():
        got = seen[em]
        if len(got) == 0:
            # Audit ROUND-29.5 P0/P1: PRESENCE is only required when the
            # cell/direction actually got as far as confirming its pins.
            # On a pin-abort at most one side pinned, and a direction the
            # run never reached has no breadcrumbs at all — demanding
            # them there invents defects. Everything that IS present is
            # still validated in full, so a forged partial pin cannot
            # hide behind the abort.
            if require_both:
                issues.append(
                    f"{source} {cell_id}: missing {_PIN_EVENT} from the {party} "
                    f"({em!r}) — both devices are pinned to the cell, so both "
                    f"breadcrumbs are required (audit ROUND-29.3 P0)"
                )
            continue
        if len(got) > 1:
            issues.append(
                f"{source} {cell_id}: {len(got)} {_PIN_EVENT} events from the "
                f"{party} ({em!r}) — exactly one is allowed "
                f"(audit ROUND-29.3 P0)"
            )
        for e in got:
            if getattr(e, "role", None) != "matrix":
                issues.append(
                    f"{source} {cell_id}: {_PIN_EVENT} from the {party} "
                    f"({em!r}) has role={getattr(e, 'role', None)!r} — must be "
                    f"'matrix' (audit ROUND-29.3 P0)"
                )
            if profile_run_id is not None and e.run_id != profile_run_id:
                issues.append(
                    f"{source} {cell_id}: {_PIN_EVENT} from the {party} "
                    f"({em!r}) has run_id={e.run_id!r} (want {profile_run_id!r}) "
                    f"(audit ROUND-29.3 P0)"
                )
            if expected_pin is not None and getattr(e, "pin", None) != expected_pin:
                issues.append(
                    f"{source} {cell_id}: {_PIN_EVENT} from the {party} "
                    f"({em!r}) has pin={getattr(e, 'pin', None)!r} (want "
                    f"{expected_pin!r}) (audit ROUND-29.3 P0)"
                )
    return issues


def check_cell_coverage(
    cell_id: str,
    cell_dir: str,
    blocked: bool,
    *,
    profile_run_id: Optional[str] = None,
    expected_sender_emitter: Optional[str] = None,
    expected_recipient_emitter: Optional[str] = None,
    expected_inner_route: Optional[str] = None,
) -> Tuple[bool, List[str]]:
    """Audit P0-2 + P0-5: per non-blocked cell, require exactly 5
    unique CIDs each with full sender + recipient coverage AND
    strict bindings to run_id, cell_id, expected emitters, and
    expected inner_route.

    Blocked cells (REST cells when relay REST capability is off)
    require ZERO CIDs.

    A CID is fully covered when:
      - sender_send_attempt_started OR sender_enqueue on the SENDER
        emitter
      - sender_transport_decision(dispatched=true, inner_route=<pin>)
        on the SENDER emitter
      - sender_wss_send_returned(dispatched=true) for WSS, or
        sender_rest_post_completed(relay_acceptance=accepted|duplicate,
        no dispatched field) for REST, on the SENDER emitter
      - sender_relay_ack_received on the SENDER emitter for WSS only;
        the event is forbidden for REST
      - recipient_deliver_received(dedup_gate=fresh) on the RECIPIENT
        emitter (exactly one fresh; trailing duplicate/reack tolerated)
      - recipient_message_persisted on the RECIPIENT emitter
      - recipient_ack_deliver_sent on the RECIPIENT emitter
    Every event must carry the profile's run_id AND the cell's
    cell_id (audit P0-5 strict bindings).
    """
    events, parse_errors = parse_events_in_dir(cell_dir, device_label=f"cell:{cell_id}")

    if blocked:
        cids = {e.correlation_id for e in events if e.correlation_id}
        if cids:
            return False, [f"cell {cell_id} is BLOCKED but has {len(cids)} dispatched CID(s): {sorted(cids)}"]
        # Audit ROUND-11 P1-#7: parse errors surface even on blocked cells.
        blocked_issues: List[str] = list(parse_errors)
        # Audit ROUND-19 P0-3: even blocked cells may carry CROSS_RUN
        # session/canary/state_cleared events. Role/emitter ownership
        # MUST be checked; an intruder-crafted line in a REST-blocked
        # cell capture used to survive because blocked cells returned
        # early.
        for e in events:
            if e.event in CROSS_RUN_TOLERATED_EVENTS:
                _r = getattr(e, "role", None)
                _em = getattr(e, "emitter_id", None)
                if _r != "matrix":
                    blocked_issues.append(
                        f"cell {cell_id} (blocked): cross-run event {e.event!r}"
                        f".role={_r!r} — must be 'matrix' (audit ROUND-19 P0-3)"
                    )
                if _em not in ("phone", "emulator"):
                    blocked_issues.append(
                        f"cell {cell_id} (blocked): cross-run event {e.event!r}"
                        f".emitter_id={_em!r} — must be a canonical matrix "
                        f"emitter ({{phone, emulator}}, audit ROUND-19 P0-3)"
                    )
            else:
                # Any non-cross-run event under a blocked cell is a
                # contract violation on its own.
                blocked_issues.append(
                    f"cell {cell_id} (blocked): non-tolerated event "
                    f"{e.event!r} present — blocked cells accept only "
                    f"cross-run session/canary/state_cleared events "
                    f"(audit ROUND-19 P0-3)"
                )
        return (not blocked_issues), blocked_issues

    # Audit ROUND-11 P1-#7: any parse-error is a schema violation —
    # a matrix with malformed wall_utc_ms in every dispatched event
    # cannot be GREEN.
    early_issues: List[str] = list(parse_errors)

    # Audit ROUND-14 P0-2: ownership check must be VALIDATING, not
    # FILTERING. The ROUND-12 filter silently discarded events with
    # wrong run_id / wrong cell_id; architect proved that adding a
    # same-CID `sender_relay_ack_received` with `run_id=run-stale-
    # injected` kept the profile GREEN because the extra was
    # filtered out before role/emitter checks ran.
    # Fix: flag every non-tolerated event under a cell that carries
    # a WRONG run_id OR WRONG cell_id as an integrity issue, and
    # KEEP the event in the per-CID pool so its role/emitter/etc.
    # are also verified (via _validate_cid_chain below).
    for e in events:
        if e.event in CROSS_RUN_TOLERATED_EVENTS:
            continue
        if profile_run_id is not None and e.run_id != profile_run_id:
            early_issues.append(
                f"cell {cell_id}: event {e.event!r} carries wrong run_id="
                f"{e.run_id!r} (want {profile_run_id!r}) — malformed "
                f"extras cannot be discarded (audit ROUND-14 P0-2)"
            )
        if e.cell_id != cell_id and not is_clear_breadcrumb(e, {cell_id}):
            early_issues.append(
                f"cell {cell_id}: event {e.event!r} carries wrong cell_id="
                f"{e.cell_id!r} (want {cell_id!r})"
            )
    # The per-CID chain check (_validate_cid_chain) walks EVERY event
    # in the CID's set below, including the wrong-run/wrong-cell ones,
    # so any role/emitter violation there also surfaces.
    #
    # Audit ROUND-18 P0-5: accepted no-CID matrix events (e.g.
    # `diagnostic_pin_active`) MUST also be role/emitter-validated —
    # they never enter `_validate_cid_chain` because they have no
    # correlation_id, and the outer run/cell guard above did not
    # cover role/emitter. Architect proved a
    # `diagnostic_pin_active role=sender emitter_id=intruder` stayed
    # GREEN. Enforce here: MATRIX_EVENTS require role=matrix +
    # emitter=canonical sender emitter; every no-CID event must
    # belong to a known accepted class or it's already caught by the
    # generic unknown-event allowlist at parse time.
    _MATRIX = MATRIX_EVENTS
    _SENDER = SENDER_EVENTS
    _RECIPIENT = RECIPIENT_EVENTS
    for e in events:
        if e.correlation_id:
            continue  # covered by _validate_cid_chain per CID
        # Audit ROUND-19 P0-3: cross-run tolerance relaxes run_id +
        # cell_id (session/canary/state_cleared may span runs and
        # cells). It MUST NOT relax role or emitter — an intruder
        # can append `diagnostic_session_started role=sender
        # emitter_id=intruder` and it stayed GREEN in ROUND-18.
        # Fall through to the role/emitter checks below so
        # ownership is validated even for cross-run events.
        ev_role = getattr(e, "role", None)
        ev_emitter = getattr(e, "emitter_id", None)
        # Cross-run events (session/canary/state_cleared) may be
        # emitted by either canonical matrix emitter, so accept
        # both {phone, emulator}. Non-cross-run matrix events
        # (diagnostic_pin_active) must match this cell's sender
        # emitter exactly. Audit ROUND-19 P0-3.
        if e.event in _MATRIX:
            if ev_role != "matrix":
                early_issues.append(
                    f"cell {cell_id}: no-CID matrix event {e.event!r}.role="
                    f"{ev_role!r} — must be 'matrix' (audit ROUND-18 P0-5)"
                )
            if e.event in CROSS_RUN_TOLERATED_EVENTS:
                if ev_emitter not in ("phone", "emulator"):
                    early_issues.append(
                        f"cell {cell_id}: cross-run event {e.event!r}.emitter_id="
                        f"{ev_emitter!r} — must be a canonical matrix emitter "
                        f"({{phone, emulator}}, audit ROUND-19 P0-3)"
                    )
            elif e.event == "diagnostic_pin_active":
                # Audit ROUND-29.3 P0: ownership for this ONE event is
                # a dual-party contract, enforced as a set below —
                # not "must be the sender". Every other matrix event
                # keeps the sender-only rule unchanged.
                pass
            elif ev_emitter != expected_sender_emitter:
                early_issues.append(
                    f"cell {cell_id}: no-CID matrix event {e.event!r}.emitter_id="
                    f"{ev_emitter!r} — must be sender {expected_sender_emitter!r} "
                    f"(audit ROUND-18 P0-5)"
                )
        elif e.event in _SENDER:
            # A sender-class event with no CID is a contract violation
            # (already flagged by _CID_BEARING_EVENTS below); we still
            # cross-check role/emitter so both signals surface.
            if ev_role != "sender" or ev_emitter != expected_sender_emitter:
                early_issues.append(
                    f"cell {cell_id}: no-CID sender event {e.event!r} "
                    f"role={ev_role!r} emitter={ev_emitter!r} "
                    f"— must be role=sender emitter={expected_sender_emitter!r} "
                    f"(audit ROUND-18 P0-5)"
                )
        elif e.event in _RECIPIENT:
            if ev_role != "recipient" or ev_emitter != expected_recipient_emitter:
                early_issues.append(
                    f"cell {cell_id}: no-CID recipient event {e.event!r} "
                    f"role={ev_role!r} emitter={ev_emitter!r} "
                    f"— must be role=recipient emitter={expected_recipient_emitter!r} "
                    f"(audit ROUND-18 P0-5)"
                )

    # Audit ROUND-29.4 P0: one shared rule, two call sites. The
    # dual-party contract lives in `check_dual_party_pin` so the smoke
    # loader enforces exactly the same thing (R29.3 implemented it for
    # full cells only, and a smoke.log with the recipient breadcrumb
    # deleted still passed GREEN/GREEN).
    if (not blocked) and expected_sender_emitter and expected_recipient_emitter:
        early_issues.extend(check_dual_party_pin(
            events,
            cell_id=cell_id,
            sender_emitter=expected_sender_emitter,
            recipient_emitter=expected_recipient_emitter,
            expected_pin=expected_inner_route,
            profile_run_id=profile_run_id,
            source="cell",
        ))

    # Audit ROUND-13 P0-1: also capture the sequence attribute per
    # dispatched CID so we can enforce sequence={1..5} exactly across
    # the 5 CIDs — architect proved that all-`sequence=1` stayed
    # GREEN before.
    dispatched_cids: Dict[str, Optional[int]] = {}
    for e in events:
        if e.correlation_id and e.event == "diagnostic_send_dispatched":
            if e.correlation_id not in dispatched_cids:
                dispatched_cids[e.correlation_id] = getattr(e, "sequence", None)

    issues: List[str] = []

    # Audit ROUND-15 P0-1: closed CID universe — every non-tolerated
    # event that carries a correlation_id MUST belong to a CID that
    # ALSO has a matching `diagnostic_send_dispatched`. An orphan CID
    # (e.g. an appended `sender_relay_ack_received` with a new CID
    # and no dispatched event) previously stayed GREEN because
    # check_cell_coverage only iterated dispatched_cids and never
    # noticed the extra correlation_id.
    orphan_cids: set = set()
    for e in events:
        if e.correlation_id and e.correlation_id not in dispatched_cids \
                and e.event not in CROSS_RUN_TOLERATED_EVENTS:
            orphan_cids.add(e.correlation_id)
    for _orph in sorted(orphan_cids):
        issues.append(
            f"cell {cell_id}: orphan CID {_orph!r} carries events but "
            f"no matching diagnostic_send_dispatched — every CID must "
            f"anchor to a dispatched event (audit ROUND-15 P0-1)"
        )

    # Audit ROUND-17 P0-4: accepted CID-bearing lifecycle events with
    # MISSING or EMPTY correlation_id are contract violations.
    # Architect proved a sender_relay_ack_received with correct
    # run/cell/role/emitter but no correlation_id stayed GREEN.
    # Only CROSS_RUN_TOLERATED events (session/canary/state_cleared)
    # legitimately fire without a CID.
    _CID_BEARING_EVENTS = SENDER_EVENTS | RECIPIENT_EVENTS | frozenset({
        "diagnostic_send_dispatched",
        "diagnostic_send_command_completed",
    })
    for e in events:
        if e.event not in _CID_BEARING_EVENTS:
            continue
        if e.event in CROSS_RUN_TOLERATED_EVENTS:
            continue
        if not e.correlation_id:
            issues.append(
                f"cell {cell_id}: accepted CID-bearing event {e.event!r} "
                f"has missing/empty correlation_id (audit ROUND-17 P0-4)"
            )

    if len(dispatched_cids) != CIDS_PER_CELL:
        issues.append(
            f"cell {cell_id}: expected exactly {CIDS_PER_CELL} unique dispatched CIDs, "
            f"got {len(dispatched_cids)}"
        )

    # Audit ROUND-13 P0-1: sequence numbers across the 5 dispatched
    # CIDs MUST cover {1,2,3,4,5} exactly. Duplicates or missing
    # sequences are a contract violation (§3.3 sequence-per-cell).
    seq_values = sorted([s for s in dispatched_cids.values() if s is not None])
    if len(seq_values) != len(dispatched_cids):
        _missing = sum(1 for s in dispatched_cids.values() if s is None)
        issues.append(
            f"cell {cell_id}: {_missing} dispatched CIDs missing "
            f"`sequence=` field"
        )
    elif seq_values and seq_values != list(range(1, CIDS_PER_CELL + 1)):
        issues.append(
            f"cell {cell_id}: dispatched sequences must be exactly "
            f"{list(range(1, CIDS_PER_CELL + 1))}, got {seq_values}"
        )

    # Per-CID coverage delegated to the single shared validator
    # (audit ROUND-12 P0-#3 + P0-#5 + ROUND-13 P0-1).
    for cid in sorted(dispatched_cids):
        cid_events = [e for e in events if e.correlation_id == cid]
        issues.extend(_validate_cid_chain(
            cid, cid_events,
            cell_id=cell_id,
            expected_sender_emitter=expected_sender_emitter,
            expected_recipient_emitter=expected_recipient_emitter,
            expected_inner_route=expected_inner_route,
            profile_run_id=profile_run_id,
        ))

    issues = early_issues + issues
    return (len(issues) == 0), issues


def check_events_against_allowlist(events: List) -> List[str]:
    """Audit P0-2: any event whose name is not in the union allowlist
    forces integrity RED. FORBIDDEN_CLIENT_EMITTERS are enumerated
    separately for a clearer error message."""
    issues = []
    for e in events:
        if e.event in FORBIDDEN_CLIENT_EMITTERS:
            issues.append(f"forbidden client emitter: {e.event} (correlation_id={e.correlation_id})")
        elif e.event not in ALL_ALLOWED_EVENTS:
            issues.append(f"unknown event name: {e.event!r} — not in accepted WSS-2 schema")
    return issues


# ── audit ROUND-13 P0-3: privacy denylist scan over ALL evidence ─
#
# Contract §3.6 forbids raw plaintext / payload / token leaks in ANY
# captured evidence. The verifier previously only ran the event-name
# allowlist on matrix_cells/**; smoke.log was never scanned, and no
# text-level regex scan for banned patterns ran at all. The architect
# proved a `text=SECRET_PAYLOAD` line in a matrix capture AND an
# `unresolved_120s_marker` line in smoke.log both survived as GREEN.
#
# The regex list is conservative: patterns that MUST NEVER appear in
# WSS-3 diagnostic evidence. Adding a marker here forces the profile
# RED for any file whose content matches.

_DENYLIST_TEXT_PATTERNS = [
    # Raw message payload — a `text=<...>` field on ANY WSS_DIAG line
    # is a plaintext leak (the diagnostic pipeline must never log the
    # payload; the correlation_id + cell_id is enough).
    (re.compile(r"\btext="), "raw payload marker `text=` present"),
    # Forbidden client emitter names, in text (not just as parsed
    # events) so any file that names them is flagged even if the parser
    # missed the line.
    (re.compile(r"\bunresolved_120s_marker\b"),
     "forbidden marker `unresolved_120s_marker`"),
    (re.compile(r"\bsender_wss_frame_written\b"),
     "forbidden emitter `sender_wss_frame_written`"),
    # Payload / ciphertext markers — WSS-2 semantics forbid these too.
    (re.compile(r"\bpayload_bytes=(?:[1-9]|[1-9][0-9]+)"),
     "non-zero payload_bytes field (should be redacted)"),
    (re.compile(r"\bciphertext="),
     "raw `ciphertext=` field present"),
    # Auth token markers.
    (re.compile(r"\bauth_token="), "raw `auth_token=` present"),
    (re.compile(r"\bbearer="), "raw `bearer=` present"),
    # Audit ROUND-17 P0-2: session tokens must never appear on disk.
    (re.compile(r"\bsession_token="), "raw `session_token=` present"),
    (re.compile(r"\bcookie="), "raw `cookie=` present"),
    # Audit ROUND-17 P0-2: usernames/identity keys are contract-denied
    # per contract §5 denylist. `username=` markers + long hex
    # identity keys (64 lowercase hex chars = 256-bit) leak.
    (re.compile(r"\busername="), "raw `username=` present"),
    (re.compile(r"\bidentity_key="), "raw `identity_key=` present"),
    # NB: bare 64-hex identity-key shape check lives in the JSON
    # walker (see _scan_denylist_strings) — it needs key-name context
    # to distinguish `apk_sha256` and HMAC-fp values from identity
    # material. Raw-line scan would false-positive too aggressively.
    # Audit ROUND-17 P0-2: VPN provider/interface/package names are
    # contract-denied.
    (re.compile(r"\bvpn_provider="), "raw `vpn_provider=` present"),
    (re.compile(r"\bpackage_name="), "raw `package_name=` present"),
    (re.compile(r"\butun\d+\b"), "raw `utun<N>` interface name present"),
    # Audit ROUND-16 P0-4: raw IP addresses are contract-denied
    # (relay/endpoint IPs must not leak to disk). IPv4 is a straight
    # dotted-quad match; IPv6 covers full 8-group AND every
    # `::`-collapsed form (leading, trailing, embedded, bare `::`).
    # Timestamps like `12:00:00.000` never contain `::`.
    (re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"),
     "raw IPv4 address present"),
    (re.compile(r"\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b"),
     "raw IPv6 (full 8-group) address present"),
    # Audit ROUND-17 P0-2: broader `::` IPv6 coverage. Matches:
    #   ::            bare (loopback shorthand)
    #   ::1           loopback
    #   ::ffff:1.2.3.4  IPv4-mapped
    #   2001:db8::    trailing compression
    #   fe80::a1b2    leading + trailing groups
    # Lookaround `(?<![:\w])` / `(?![:\w])` avoids matching inside
    # longer identifiers or timestamps.
    (re.compile(r"(?<![:\w])(?:[0-9a-fA-F]{1,4}:){1,7}:(?![:\w])"),
     "raw IPv6 (trailing `::`) address present"),
    (re.compile(r"(?<![:\w])::(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*)?(?![:\w])"),
     "raw IPv6 (leading `::` or bare `::`) address present"),
    (re.compile(r"(?<![:\w])(?:[0-9a-fA-F]{1,4}:){1,6}(?::[0-9a-fA-F]{1,4}){1,6}(?![:\w])"),
     "raw IPv6 (embedded `::`) address present"),
]

# Audit ROUND-16 P0-4 hardened per ROUND-17 P0-2: banned JSON KEY
# names. Extended coverage: session/token/credential aliases;
# provider/username/identity fields; contract §5 denylist forms.
_BANNED_JSON_KEYS = frozenset({
    "text", "payload", "ciphertext", "plaintext",
    "auth_token", "bearer", "authorization",
    "session_token", "cookie", "credentials", "password", "secret",
    "username", "user_id", "phone_number", "msisdn", "imsi",
    "identity_key", "identity_key_hex", "public_key", "private_key",
    "vpn_provider", "vpn_provider_name", "package_name", "iface",
})

# Audit ROUND-18 P0-4: semantic classifier for credential/token/secret
# aliases. `.lower()` collapses `SessionToken`, `session_token`,
# `sessionToken`, and `SESSION_TOKEN` all to `sessiontoken` /
# `session_token` / etc. — we can't enumerate every variant, so we
# match on substrings within the lowercased key. False-positive risk
# is contained by `_ALLOWED_KEY_SUBSTRINGS_ALLOWLIST` for words that
# a legitimate schema might contain (e.g. `arm_token` in PROFILE.json
# is a NON-secret UUID that identifies the arm session).
_BANNED_KEY_SUBSTRINGS = (
    "token", "secret", "credential", "passwd", "password",
    "apikey", "api_key", "client_secret",
    "bearer", "cookie", "sessionid",
)
_ALLOWED_KEY_SUBSTRING_KEYS = frozenset({
    "arm_token",       # non-secret UUID identifying arm session
    "smoke_run_id",    # non-secret UUID identifying smoke run
})


# Audit ROUND-17 P0-2: JSON keys whose 64-char-hex VALUE is
# legitimate (APK SHAs, HMAC fingerprints — those are hashes of
# ephemeral data, not identity material). A 64-hex value under
# ANY OTHER key is treated as an identity-key-shaped leak.
_ALLOWED_HEX_KEYS = frozenset({
    "apk_sha256", "apk_sha256_phone", "apk_sha256_emu",
    "host_hmac_fp_hex", "emu_hmac_fp_hex", "hmac_fp_hex",
    "checkpoint_key_hex_sha",  # if we ever hash it for redaction proof
})
_BARE_HEX64_RE = re.compile(r"^[a-fA-F0-9]{64}$")


def _scan_denylist_strings(node, hits: List[str], path: str,
                           parent_key: Optional[str] = None) -> None:
    """Audit ROUND-15 P0-3 hardened per ROUND-16/17 P0-4/2: recursively
    walk a decoded JSON value and match denylist patterns against
    every string key/value. Also refuses any key whose LITERAL NAME
    is in `_BANNED_JSON_KEYS` (architect proved `"text":"..."`
    bypassed the `\\btext=` regex). A 64-hex value under a key NOT
    in `_ALLOWED_HEX_KEYS` is flagged as an identity-key-shaped leak
    (allows apk_sha256 / hmac_fp_hex, denies everything else).
    """
    if isinstance(node, str):
        for pat, reason in _DENYLIST_TEXT_PATTERNS:
            if pat.search(node):
                hits.append(f"privacy denylist hit in {path}: {reason} (in decoded string)")
        # Audit ROUND-17 P0-2: 64-hex identity-key shape.
        if _BARE_HEX64_RE.match(node) and (parent_key is None
                                           or parent_key.lower() not in _ALLOWED_HEX_KEYS):
            hits.append(
                f"privacy denylist hit in {path}: 64-char hex identity-key-shaped "
                f"value under key {parent_key!r} (audit ROUND-17 P0-2)"
            )
    elif isinstance(node, list):
        for i, item in enumerate(node):
            _scan_denylist_strings(item, hits, f"{path}[{i}]", parent_key=parent_key)
    elif isinstance(node, dict):
        for k, v in node.items():
            if isinstance(k, str):
                for pat, reason in _DENYLIST_TEXT_PATTERNS:
                    if pat.search(k):
                        hits.append(f"privacy denylist hit in {path}.<key>{k!r}: {reason}")
                # Audit ROUND-18 P0-4: semantic alias check FIRST.
                # Catches `access_token`, `accessToken`, `apiKey`,
                # `clientSecret`, etc., that `_BANNED_JSON_KEYS`'s
                # exact-name list would miss.
                _klow = k.lower()
                if _klow not in _ALLOWED_KEY_SUBSTRING_KEYS:
                    for _needle in _BANNED_KEY_SUBSTRINGS:
                        if _needle in _klow:
                            hits.append(
                                f"privacy denylist hit in {path}.<key>{k!r}: "
                                f"credential/token alias (matched {_needle!r}, "
                                f"audit ROUND-18 P0-4)"
                            )
                            break
                if k.lower() in _BANNED_JSON_KEYS:
                    hits.append(
                        f"privacy denylist hit in {path}.<key>{k!r}: "
                        f"banned JSON key name (audit ROUND-16 P0-4)"
                    )
            _scan_denylist_strings(v, hits, f"{path}.{k}", parent_key=k if isinstance(k, str) else None)


# Audit ROUND-15 P0-3: closed evidence-file inventory. Any file
# outside this allowlist forces integrity RED — an added
# `audit-extra.bin` (or any other unknown file) with a denylisted
# token used to survive because the scan filtered by extension.
_ALLOWED_TOP_LEVEL_FILES = frozenset({
    "PROFILE.json",
    "PROFILE_STATE.json",
    "network_profile.json",
    "EGRESS_FINGERPRINT.json",
    "signed_prekey_readiness.json",
    "checkpoint_log.json",
    "smoke.log",
    "smoke_verdict.json",
    "matrix_completion.json",
    "matrix_verdict.json",
    "SHA256SUMS.txt",
})
_ALLOWED_CELL_FILES = frozenset({
    "capture.log",
    "cell_verdict.json",
})


def check_evidence_inventory(evidence_dir: str) -> List[str]:
    """Audit ROUND-15 P0-3 → ROUND-18 P1-1: closed evidence-file
    inventory at EVERY depth.

    Allowed topology:
      <evidence>/                        (top level)
        ├─ <files in _ALLOWED_TOP_LEVEL_FILES>
        └─ matrix_cells/                 (the ONLY allowed top-level dir)
             └─ <canonical-cell-id>/     (one of exactly 8 canonical ids)
                  └─ <files in _ALLOWED_CELL_FILES>

    Rejections (audit ROUND-18 P1-1):
      - any file OR directory at top level not in the allowed set
      - any dir under matrix_cells/ not in the canonical 8
      - any file or subdir INSIDE a canonical cell dir that is not
        in _ALLOWED_CELL_FILES (empty nested dir is a hit too)
      - any symlinked file OR directory anywhere in the tree
    """
    issues: List[str] = []
    if not os.path.isdir(evidence_dir):
        return issues
    _canonical_cells = frozenset(canonical_cell_ids())
    _ALLOWED_TOP_LEVEL_DIRS = frozenset({"matrix_cells"})
    for root, dirs, files in os.walk(evidence_dir):
        rel_root = os.path.relpath(root, evidence_dir).replace(os.sep, "/")
        # Audit ROUND-18 P1-1: reject ALL symlinks (files + dirs) at
        # every depth. ROUND-17 only caught symlinked directories
        # under matrix_cells/; symlinked files anywhere in the tree
        # were still accepted.
        for name in list(dirs) + files:
            fp = os.path.join(root, name)
            if os.path.islink(fp):
                issues.append(
                    f"symlink rejected: {rel_root}/{name} "
                    f"(closed inventory, audit ROUND-18 P1-1)"
                )
        if rel_root == ".":
            # Top level: only the allowed dir is permitted.
            for d in dirs:
                if d not in _ALLOWED_TOP_LEVEL_DIRS:
                    issues.append(
                        f"unknown top-level directory: {d!r} "
                        f"(only {sorted(_ALLOWED_TOP_LEVEL_DIRS)} allowed) "
                        f"— closed inventory audit ROUND-18 P1-1"
                    )
            for f in files:
                if f not in _ALLOWED_TOP_LEVEL_FILES:
                    issues.append(
                        f"unknown evidence file at top level: {f!r} "
                        f"— closed inventory audit ROUND-15 P0-3"
                    )
        elif rel_root == "matrix_cells":
            for d in dirs:
                if d not in _canonical_cells:
                    issues.append(
                        f"unknown cell directory: {d!r} "
                        f"(allowed: {sorted(_canonical_cells)}) "
                        f"— closed inventory audit ROUND-17 P1-1"
                    )
            for f in files:
                issues.append(
                    f"unexpected file directly under matrix_cells/: {f!r} "
                    f"— closed inventory audit ROUND-18 P1-1"
                )
        elif rel_root.startswith("matrix_cells/") and rel_root.count("/") == 1:
            # matrix_cells/<canonical>. Must be canonical.
            cell = rel_root.split("/", 1)[1]
            if cell not in _canonical_cells:
                issues.append(
                    f"unknown cell directory: {cell!r} "
                    f"(allowed: {sorted(_canonical_cells)}) "
                    f"— closed inventory audit ROUND-16 P0-4"
                )
            # No further subdirectories are allowed inside a canonical
            # cell (audit ROUND-18 P1-1: empty nested dir survived).
            for d in dirs:
                issues.append(
                    f"unexpected subdirectory inside cell {cell!r}: "
                    f"matrix_cells/{cell}/{d}/ — closed inventory "
                    f"audit ROUND-18 P1-1"
                )
            for f in files:
                if f not in _ALLOWED_CELL_FILES:
                    issues.append(
                        f"unknown evidence file in matrix_cells: "
                        f"matrix_cells/{cell}/{f} — closed inventory "
                        f"audit ROUND-15 P0-3"
                    )
        else:
            # Any depth beyond `matrix_cells/<cell>/` (or any other
            # unknown directory tree) is rejected outright.
            for f in files:
                issues.append(
                    f"evidence file outside declared inventory tree: "
                    f"{rel_root}/{f} — closed inventory audit "
                    f"ROUND-18 P1-1"
                )
            for d in dirs:
                issues.append(
                    f"evidence directory outside declared inventory tree: "
                    f"{rel_root}/{d}/ — closed inventory audit "
                    f"ROUND-18 P1-1"
                )
    return issues


def check_privacy_denylist(evidence_dir: str) -> List[str]:
    """Recursively scan every regular file under evidence_dir for
    the denylist patterns AND decode every JSON file to scan its
    string keys/values.

    Audit ROUND-15 P0-3: architect proved TWO bypasses of the
    ROUND-14 scan:
      1. A Unicode-escaped string in JSON (`\\u0074\\u0065\\u0078\\u0074=...`
         which decodes to `text=...`) did NOT match the raw-line
         regex because the raw bytes on disk are `\\u0074`, not `t`.
      2. An added `audit-extra.bin` containing `text=SECRET_PAYLOAD`
         was skipped because the scan filtered by extension.

    Fix: scan every regular file regardless of extension (the closed
    inventory check_evidence_inventory ALSO refuses unknown files as
    a hard belt-and-braces). For `.json` files additionally decode
    and recursively walk every string value/key so Unicode escapes
    surface after the JSON parser resolves them.
    """
    issues: List[str] = []
    if not os.path.isdir(evidence_dir):
        return issues
    for root, _dirs, files in os.walk(evidence_dir):
        for f in files:
            # SHA256SUMS.txt is scanned by check_sha256sums (path
            # containment); its content is deterministic hashes that
            # by construction cannot contain a denylisted pattern.
            if f == "SHA256SUMS.txt":
                continue
            fp = os.path.join(root, f)
            rel = os.path.relpath(fp, evidence_dir).replace(os.sep, "/")
            # Raw-line scan: covers every readable file regardless of
            # extension. Binary bytes are read with errors="replace"
            # so we never crash; false-positives against binary noise
            # are unlikely for the specific patterns (text=, etc.).
            try:
                with open(fp, encoding="utf-8", errors="replace") as fh:
                    for lineno, line in enumerate(fh, 1):
                        for pat, reason in _DENYLIST_TEXT_PATTERNS:
                            if pat.search(line):
                                issues.append(
                                    f"privacy denylist hit in {rel}:{lineno}: {reason}"
                                )
            except OSError as e:
                issues.append(f"privacy scan failed for {rel}: {e}")
                continue
            # JSON: decode and recursively walk string values/keys so
            # Unicode escapes are resolved before regex match.
            if f.endswith(".json"):
                try:
                    with open(fp, encoding="utf-8") as fh:
                        decoded = json.load(fh)
                except (OSError, json.JSONDecodeError):
                    # Malformed JSON is a separate integrity concern
                    # picked up by the per-file loader; don't double-
                    # report here.
                    continue
                _scan_denylist_strings(decoded, issues, rel)
    return issues


# ── retry lineage DAG discipline

@dataclass
class AttemptRecord:
    attempt_id: str
    profile_id: str
    supersedes_attempt_id: Optional[str]
    retry_reason: Optional[str]
    arm_token: str
    evidence_dir: str
    completion: Optional[dict]


def load_attempts_for_profile(evidence_root: str, profile_id: str) -> Tuple[List[AttemptRecord], List[str]]:
    """Audit ROUND-16 P0-3: return (attempts, issues) so malformed
    attempts surface as integrity problems instead of vanishing from
    duplicate/retry lineage. The ROUND-15 version silently skipped
    attempts whose PROFILE.json was malformed or non-dict — architect
    proved that adding a second malformed attempt directory kept the
    profile GREEN with attempts=1.
    """
    out: List[AttemptRecord] = []
    issues: List[str] = []
    if not os.path.isdir(evidence_root):
        return out, issues
    for name in sorted(os.listdir(evidence_root)):
        m = EVIDENCE_DIR_RE.match(name)
        if not m:
            continue
        if m.group("pid") != profile_id:
            continue
        d = os.path.join(evidence_root, name)
        prof_path = os.path.join(d, "PROFILE.json")
        if not os.path.isfile(prof_path):
            # Audit ROUND-16 P0-3: an attempt directory named
            # canonically but missing PROFILE.json is a schema
            # violation (the directory shouldn't exist).
            issues.append(
                f"attempt {name!r}: PROFILE.json missing "
                f"(malformed attempt directory)"
            )
            continue
        try:
            with open(prof_path, encoding="utf-8") as f:
                p = json.load(f)
        except (OSError, json.JSONDecodeError) as e:
            issues.append(
                f"attempt {name!r}: PROFILE.json malformed: {e} "
                f"(audit ROUND-16 P0-3)"
            )
            continue
        if not isinstance(p, dict):
            issues.append(
                f"attempt {name!r}: PROFILE.json must be a JSON object, "
                f"got {type(p).__name__} (audit ROUND-16 P0-3)"
            )
            continue
        mc = None
        mc_path = os.path.join(d, "matrix_completion.json")
        if os.path.isfile(mc_path):
            try:
                with open(mc_path, encoding="utf-8") as f:
                    mc_loaded = json.load(f)
                if isinstance(mc_loaded, dict):
                    mc = mc_loaded
                else:
                    issues.append(
                        f"attempt {name!r}: matrix_completion.json must be a "
                        f"JSON object, got {type(mc_loaded).__name__}"
                    )
            except (OSError, json.JSONDecodeError) as e:
                issues.append(
                    f"attempt {name!r}: matrix_completion.json malformed: {e}"
                )
        out.append(AttemptRecord(
            attempt_id=p.get("attempt_id", ""),
            profile_id=profile_id,
            supersedes_attempt_id=p.get("supersedes_attempt_id"),
            retry_reason=p.get("retry_reason"),
            arm_token=p.get("arm_token", ""),
            evidence_dir=d,
            completion=mc,
        ))
    return out, issues


def check_retry_lineage(attempts: List[AttemptRecord]) -> Tuple[bool, List[str]]:
    issues = []
    if len(attempts) <= 1:
        return True, issues
    by_id: Dict[str, AttemptRecord] = {}
    for a in attempts:
        if a.attempt_id in by_id:
            issues.append(f"duplicate attempt_id {a.attempt_id!r} across two evidence dirs")
        by_id[a.attempt_id] = a
    roots = [a for a in attempts if a.supersedes_attempt_id is None]
    if len(roots) != 1:
        issues.append(f"latest_wins fallback needed: found {len(roots)} root attempts (want exactly 1)")
    parents = {}
    for a in attempts:
        sup = a.supersedes_attempt_id
        if sup is None:
            continue
        if sup in parents:
            issues.append(f"fork: attempts {parents[sup]!r} and {a.attempt_id!r} both name parent {sup!r}")
        parents[sup] = a.attempt_id
    known = set(by_id.keys())
    for a in attempts:
        sup = a.supersedes_attempt_id
        if sup is not None and sup not in known:
            issues.append(f"dangling parent: attempt {a.attempt_id!r} supersedes {sup!r} which is not present in this profile")
    for a in attempts:
        visited = set()
        cur = a
        while cur is not None and cur.supersedes_attempt_id is not None:
            if cur.attempt_id in visited:
                issues.append(f"cycle detected in retry chain starting at {a.attempt_id!r}")
                break
            visited.add(cur.attempt_id)
            cur = by_id.get(cur.supersedes_attempt_id)
    for a in attempts:
        if (a.supersedes_attempt_id is None) != (a.retry_reason is None):
            issues.append(f"unpaired supersedes/retry_reason on attempt {a.attempt_id!r}")
    for a in attempts:
        if a.supersedes_attempt_id is None:
            continue
        parent = by_id.get(a.supersedes_attempt_id)
        if parent is None:
            continue
        if a.arm_token != parent.arm_token:
            issues.append(
                f"arm_token disagreement in retry chain: attempt {a.attempt_id!r}"
                f" has arm_token={a.arm_token!r} but its parent {parent.attempt_id!r}"
                f" has arm_token={parent.arm_token!r}"
            )
    return (len(issues) == 0), issues


# ── smoke_verdict.json shape + VALUES + independent smoke.log rebuild
#    (audit ROUND-10 P0-4 hardened per audit ROUND-11 P0-#4)

def r3017_audit_attempts(cid, cid_events, *, cell_id, recipient):
    """Audit ROUND-30.17 — an ATTEMPT is the unit that owns an outcome.

    R30.16 counted terminal records per ENVELOPE and so could not
    describe the run it was built for. The 2026-08-26 smoke was
    redelivered: two fresh processings of one envelope, two terminal
    records. Per-envelope counting called the second one corrupt
    evidence, which is the opposite of what it is.

    Every fresh processing of an envelope is an attempt, numbered by the
    recipient inside the same critical section that claims the envelope,
    so attempts cannot share a number or interleave. Each attempt owes
    EXACTLY ONE terminal outcome:

      * a complete settled triplet — persisted AND acked — or
      * one well-formed `recipient_deliver_failed`.

    Neither is an unaccounted attempt; both is a contradiction; a
    terminal record whose attempt has no fresh delivery is an orphan.
    All three are INTEGRITY findings: the evidence cannot be trusted.

    A failed attempt is not. It is a truthful account of a PRODUCT
    failure, so it is returned separately, and a run that failed and
    said so keeps `integrity_ok` while its outcome goes RED.

    Returns `(integrity_issues, product_issues, settled_attempts,
    seen_attempts)`.
    """
    integrity: List[str] = []
    product: List[str] = []
    mine = [e for e in cid_events
            if getattr(e, "cell_id", None) == cell_id
            and getattr(e, "emitter_id", None) == recipient
            and getattr(e, "role", None) == "recipient"]

    # Audit ROUND-30.18: the bound comes from the schema authority, which
    # carries the same number as the producer's
    # `DELIVER_ATTEMPT_ORDINAL_MAX`. R30.17 had the verifier enforcing a
    # limit it had invented while the producer was unbounded, so the two
    # ends of one field disagreed about its domain.
    _ATTEMPT_MAX = DELIVER_ATTEMPT_ORDINAL_MAX

    def _n(e):
        a = getattr(e, "attempt", None)
        if a is None:
            return None
        try:
            v = int(a)
        except (TypeError, ValueError):
            return None
        return v if 1 <= v <= _ATTEMPT_MAX else None

    fresh_all = [e for e in mine
                 if e.event == "recipient_deliver_received"
                 and getattr(e, "dedup_gate", None) == "fresh"]
    failed_all = [e for e in mine if e.event == "recipient_deliver_failed"]
    # Audit ROUND-30.18: settlements are attempt events too.
    #
    # R30.17 built the attempt population from `fresh + failed` only, so a
    # `persisted`/`ack_sent` pair carrying an ordinal that appeared in
    # neither list was visited by no rule at all - and an orphan
    # settlement, the very shape the orphan rule claims to catch, passed
    # with zero findings. The rule was written for failure records and
    # silently did not cover the other terminal outcome.
    settle_all = [e for e in mine
                  if e.event in ("recipient_message_persisted",
                                 "recipient_ack_deliver_sent")]
    all_attempt_events = fresh_all + failed_all + settle_all

    # ── Audit ROUND-30.18: a truncated history is not judgeable ────
    #
    # The recipient emits this once when an envelope's attempts outrun
    # the ordinal range. Attempts past that point really happened - they
    # delivered or failed - and their records are not here. Rendering a
    # verdict on what IS here would be a verdict on part of the run
    # presented as a verdict on the run.
    #
    # So this is an integrity finding: the evidence is incomplete and
    # says so, which is the honest reading. Delivery is unaffected; only
    # the ability to judge it is.
    _overflow = [e for e in mine
                 if e.event == "recipient_deliver_attempt_overflow"]
    for e in _overflow:
        integrity.append(
            f"smoke {cell_id} cid={cid}: the recipient reported more delivery "
            f"attempts than the ordinal range can name, so the attempt "
            f"history here is TRUNCATED and cannot be judged "
            f"(audit ROUND-30.18)"
        )

    # ── legacy evidence ──────────────────────────────────────
    #
    # The producer stamps every attempt now, but the three recorded
    # physical attempts predate this field and are immutable. Refusing
    # to read them would make the round's own motivating evidence
    # unreadable, so an UNAMBIGUOUS legacy shape - exactly one fresh
    # delivery and no numbered attempts anywhere - is read as attempt 1.
    #
    # Ambiguity is not tolerated: two unnumbered fresh deliveries cannot
    # be told apart, and that is precisely the 2026-08-26 shape, so it
    # stays a finding rather than being guessed at.
    _unnumbered = [e for e in all_attempt_events if _n(e) is None]
    _numbered = [e for e in all_attempt_events if _n(e) is not None]
    # Legacy means the field is ABSENT, not that it failed to parse. An
    # event carrying `attempt=99999` or `attempt=oops` is malformed, and
    # letting it fall through to the legacy reading would turn a broken
    # ordinal into a silent "attempt 1" - the fallback excusing exactly
    # the corruption it must expose.
    _absent = all(getattr(e, "attempt", None) is None
                  for e in all_attempt_events)
    _legacy = (_absent and _unnumbered and not _numbered
               and len([e for e in fresh_all if _n(e) is None]) == 1)
    if _unnumbered and not _legacy:
        for e in _unnumbered:
            integrity.append(
                f"smoke {cell_id} cid={cid}: {e.event} carries "
                f"attempt={getattr(e, 'attempt', None)!r} and cannot be tied "
                f"to one delivery — an attempt ordinal must be an integer in "
                f"1..{_ATTEMPT_MAX}, and an absent one is readable only as a "
                f"single unambiguous attempt (audit ROUND-30.17)"
            )

    if _legacy:
        _n_orig = _n
        def _n(e, _o=_n_orig):
            v = _o(e)
            return 1 if v is None else v

    attempts = sorted({_n(e) for e in all_attempt_events} - {None})

    # Attempts are consecutive from 1. A gap means a processing this
    # evidence does not describe: either it was never recorded, or the
    # numbering is not what it claims to be. Either way the sequence
    # cannot be read as a complete account of the deliveries.
    if attempts and attempts != list(range(1, len(attempts) + 1)):
        integrity.append(
            f"smoke {cell_id} cid={cid}: attempt numbers {attempts} are not "
            f"consecutive from 1 — an attempt is missing from the evidence "
            f"or the numbering is not an attempt sequence "
            f"(audit ROUND-30.17)"
        )

    for n in attempts:
        fresh = [e for e in fresh_all if _n(e) == n]
        failed = [e for e in failed_all if _n(e) == n]
        persisted = [e for e in mine
                     if e.event == "recipient_message_persisted" and _n(e) == n]
        acked = [e for e in mine
                 if e.event == "recipient_ack_deliver_sent" and _n(e) == n]

        if not fresh:
            integrity.append(
                f"smoke {cell_id} cid={cid} attempt={n}: a terminal record "
                f"with no fresh recipient_deliver_received for that attempt "
                f"— an orphan (audit ROUND-30.17)"
            )
            continue
        if len(fresh) > 1:
            integrity.append(
                f"smoke {cell_id} cid={cid} attempt={n}: {len(fresh)} fresh "
                f"deliveries share one attempt number — an attempt is one "
                f"processing (audit ROUND-30.17)"
            )

        # Binding: a record explains the delivery it belongs to or
        # nothing. Same run as the fresh event it terminates.
        _rids = {getattr(f, "run_id", None) for f in fresh}
        for e in failed:
            if getattr(e, "run_id", None) not in _rids:
                integrity.append(
                    f"smoke {cell_id} cid={cid} attempt={n}: "
                    f"recipient_deliver_failed carries "
                    f"run_id={getattr(e, 'run_id', None)!r} but the attempt "
                    f"ran under {sorted(str(r) for r in _rids)} "
                    f"(audit ROUND-30.17)"
                )
            if getattr(e, "deliver_failure", None) not in DELIVER_FAILURE_ENUM:
                integrity.append(
                    f"smoke {cell_id} cid={cid} attempt={n}: "
                    f"deliver_failure={getattr(e, 'deliver_failure', None)!r} "
                    f"is not one of {sorted(DELIVER_FAILURE_ENUM)} "
                    f"(audit ROUND-30.17)"
                )
            if getattr(e, "deliver_stage", None) not in DELIVER_STAGE_ENUM:
                integrity.append(
                    f"smoke {cell_id} cid={cid} attempt={n}: "
                    f"deliver_stage={getattr(e, 'deliver_stage', None)!r} "
                    f"is not one of {sorted(DELIVER_STAGE_ENUM)} "
                    f"(audit ROUND-30.17)"
                )

        settled = bool(persisted) and bool(acked)
        n_failed = len(failed)

        if settled and n_failed:
            integrity.append(
                f"smoke {cell_id} cid={cid} attempt={n}: settled AND reported "
                f"failed — an attempt has exactly one terminal outcome "
                f"(audit ROUND-30.17)"
            )
        elif n_failed > 1:
            integrity.append(
                f"smoke {cell_id} cid={cid} attempt={n}: {n_failed} terminal "
                f"failure records for one attempt — an attempt has exactly "
                f"one terminal outcome (audit ROUND-30.17)"
            )
        elif not settled and n_failed == 0:
            _missing = []
            if not persisted:
                _missing.append("recipient_message_persisted")
            if not acked:
                _missing.append("recipient_ack_deliver_sent")
            integrity.append(
                f"smoke {cell_id} cid={cid} attempt={n}: unaccounted — no "
                f"terminal outcome, and no {' or '.join(_missing)}. A fresh "
                f"delivery either settles or says why (audit ROUND-30.17)"
            )
        elif not settled and n_failed == 1:
            # The one shape that is evidence rather than a defect in it.
            e = failed[0]
            stage = getattr(e, "deliver_stage", None)
            _explains = set()
            if stage in ("received", "decrypted"):
                _explains = {"recipient_message_persisted",
                             "recipient_ack_deliver_sent"}
            elif stage in ("persisted", "ledger_marked"):
                _explains = {"recipient_ack_deliver_sent"}
            # A stage claims every earlier one completed, INSIDE this
            # attempt. Over-claiming leaves the gap unexplained.
            if stage in ("persisted", "ledger_marked") and not persisted:
                integrity.append(
                    f"smoke {cell_id} cid={cid} attempt={n}: the record claims "
                    f"deliver_stage={stage!r} but that attempt has no "
                    f"recipient_message_persisted — the stage and the stream "
                    f"contradict each other (audit ROUND-30.17)"
                )
            if stage == "ack_sent" and not acked:
                integrity.append(
                    f"smoke {cell_id} cid={cid} attempt={n}: the record claims "
                    f"deliver_stage='ack_sent' but that attempt has no "
                    f"recipient_ack_deliver_sent (audit ROUND-30.17)"
                )
            for _name in ("recipient_message_persisted",
                          "recipient_ack_deliver_sent"):
                _have = persisted if _name == "recipient_message_persisted" else acked
                if not _have and _name not in _explains:
                    integrity.append(
                        f"smoke {cell_id} cid={cid} attempt={n}: missing "
                        f"{_name}, and the failure record's "
                        f"deliver_stage={stage!r} does not account for it "
                        f"(audit ROUND-30.17)"
                    )
            product.append(
                f"smoke {cell_id} cid={cid} attempt={n}: the recipient "
                f"reported the delivery FAILED — "
                f"deliver_failure={getattr(e, 'deliver_failure', None)!r} "
                f"deliver_stage={stage!r} (audit ROUND-30.17)"
            )

    settled_attempts = [
        n for n in attempts
        if any(e.event == "recipient_message_persisted" and _n(e) == n for e in mine)
        and any(e.event == "recipient_ack_deliver_sent" and _n(e) == n for e in mine)
    ]
    return integrity, product, settled_attempts, attempts


def smoke_role_issue(e) -> Optional[str]:
    """Role-per-event-class, for one event. Audit ROUND-30.20: shared
    by `load_smoke_verdict` and `smoke_direction_soundness` so the
    producer and the verifier cannot answer it differently."""
    _role = getattr(e, "role", None)
    if e.event in SENDER_EVENTS and _role != "sender":
        return (f"smoke.log: {e.event!r}.role={_role!r} — must be 'sender' "
                f"(audit ROUND-16 P0-2)")
    if e.event in RECIPIENT_EVENTS and _role != "recipient":
        return (f"smoke.log: {e.event!r}.role={_role!r} — must be "
                f"'recipient' (audit ROUND-16 P0-2)")
    if e.event in MATRIX_EVENTS and _role not in ("matrix", None):
        # Some matrix events (session_started/canary/state_cleared)
        # don't require a role; only enforce when set to a wrong value.
        return (f"smoke.log: {e.event!r}.role={_role!r} — must be 'matrix' "
                f"(audit ROUND-16 P0-2)")
    return None


_CANONICAL_SMOKE_CELLS = frozenset({
    "wss.p2e.after-connect", "wss.e2p.after-connect",
})


def smoke_current_run_events(events: List,
                             expected_run_id: Optional[str]) -> List:
    """Return the event surface owned by this smoke invocation.

    A logcat snapshot can contain older relay and transport records.  They
    are neither evidence for nor evidence against the current smoke.  The
    explicit cross-run lifecycle events remain visible to the ownership
    checks that intentionally tolerate them.
    """
    if expected_run_id is None:
        return list(events)
    return [
        e for e in events
        if e.event in CROSS_RUN_TOLERATED_EVENTS
        or getattr(e, "run_id", None) == expected_run_id
    ]


def smoke_event_surface_issues(events: List, expected_run_id: Optional[str],
                               expected_cids=None) -> List[str]:
    """Closed current-smoke surface shared by producer and verifier.

    Historical events are ignored unless they reuse a CID claimed by the
    current verdict.  That distinction keeps harmless retained history from
    vetoing a run while still failing closed when a foreign record purports
    to be part of the current delivery chain.
    """
    issues: List[str] = []
    current_cids = {
        cid for cid in (expected_cids or ())
        if isinstance(cid, str) and cid
    }
    scoped_events = smoke_current_run_events(events, expected_run_id)
    for e in events:
        if e.event in CROSS_RUN_TOLERATED_EVENTS:
            continue
        run_id = getattr(e, "run_id", None)
        cid = getattr(e, "correlation_id", None)
        if (expected_run_id is not None and run_id != expected_run_id
                and cid in current_cids):
            issues.append(
                f"smoke.log: event {e.event!r} reuses current "
                f"correlation_id={cid!r} with foreign run_id={run_id!r} "
                f"(want {expected_run_id!r}; audit ROUND-30.20)"
            )
    for issue in check_events_against_allowlist(scoped_events):
        issues.append(f"smoke.log: {issue}")
    for e in scoped_events:
        if e.event in CROSS_RUN_TOLERATED_EVENTS:
            role = getattr(e, "role", None)
            emitter = getattr(e, "emitter_id", None)
            if role != "matrix":
                issues.append(
                    f"smoke.log: cross-run event {e.event!r}.role={role!r} "
                    "— must be 'matrix' (audit ROUND-19 P0-3)"
                )
            if emitter not in ("phone", "emulator"):
                issues.append(
                    f"smoke.log: cross-run event {e.event!r}.emitter_id="
                    f"{emitter!r} — must be a canonical matrix emitter "
                    "({phone, emulator}, audit ROUND-19 P0-3)"
                )
            continue

        cell_id = getattr(e, "cell_id", None)
        if cell_id is None:
            issues.append(
                f"smoke.log: event {e.event!r} has no cell_id "
                "(audit ROUND-17 P0-3)"
            )
        elif (cell_id not in _CANONICAL_SMOKE_CELLS
              and not is_clear_breadcrumb(e, _CANONICAL_SMOKE_CELLS)):
            issues.append(
                f"smoke.log: event {e.event!r} carries non-canonical "
                f"cell_id={cell_id!r} (allowed: "
                f"{sorted(_CANONICAL_SMOKE_CELLS)}) "
                "(audit ROUND-16 P0-2)"
            )

        run_id = getattr(e, "run_id", None)
        if not isinstance(run_id, str) or not run_id:
            issues.append(
                f"smoke.log: event {e.event!r} missing run_id "
                "(audit ROUND-17 P0-3)"
            )
        elif expected_run_id is not None and run_id != expected_run_id:
            issues.append(
                f"smoke.log: event {e.event!r} run_id={run_id!r} != "
                f"smoke_verdict.smoke_run_id={expected_run_id!r} "
                "(foreign smoke run — audit ROUND-17 P0-3)"
            )

        role_issue = smoke_role_issue(e)
        if role_issue:
            issues.append(role_issue)
    return issues


def smoke_global_surface_audit(smoke_log_path: str, *,
                               expected_run_id: Optional[str],
                               expected_cids=None) -> Tuple[List, List[str]]:
    """Parse and audit the complete smoke surface once.

    Direction audits are intentionally scoped so a later direction cannot
    erase an earlier delivery. This audit is intentionally not scoped: a
    producer may write GREEN only when every current-run event belongs to the
    closed smoke surface that the verifier will later consume.
    """
    events, parse_errors = parse_events(smoke_log_path, "smoke")
    issues: List[str] = list(parse_errors)
    issues.extend(smoke_event_surface_issues(
        events, expected_run_id, expected_cids=expected_cids))
    if not events:
        issues.append("smoke.log contains zero WSS_DIAG events "
                      "(JSON verdict cannot be substantiated)")
    return events, issues


def smoke_overflow_ownership_issues(events: List) -> List[str]:
    """Global ownership sweep for attempt-history truncation markers."""
    issues: List[str] = []
    recipient_of = {
        "wss.p2e.after-connect": "emulator",
        "wss.e2p.after-connect": "phone",
    }
    for event in events:
        if event.event != "recipient_deliver_attempt_overflow":
            continue
        cell_id = getattr(event, "cell_id", None)
        expected = recipient_of.get(cell_id)
        if expected is None:
            issues.append(
                f"smoke: recipient_deliver_attempt_overflow on "
                f"cell_id={cell_id!r}, which is not a smoke direction — a "
                "truncation marker must name the delivery it truncates "
                "(audit ROUND-30.18)"
            )
        elif getattr(event, "emitter_id", None) != expected:
            issues.append(
                f"smoke {cell_id}: recipient_deliver_attempt_overflow from "
                f"emitter={getattr(event, 'emitter_id', None)!r}, but that "
                f"direction's recipient is {expected!r} — the marker is not "
                "bound to the delivery it claims to truncate "
                "(audit ROUND-30.18)"
            )
        elif getattr(event, "role", None) != "recipient":
            issues.append(
                f"smoke {cell_id}: recipient_deliver_attempt_overflow with "
                f"role={getattr(event, 'role', None)!r} — a truncation is a "
                "recipient statement (audit ROUND-30.18)"
            )
    return issues


def audit_smoke_direction(by_cid: Dict[str, List], *, sender: str,
                          recipient: str, pin: str):
    """Audit ROUND-30.20: the ONE authority on whether a smoke
    direction is sound, lifted verbatim out of
    `load_smoke_verdict` (it captured nothing but `by_cid`).

    The producer used to answer this question with its own awk
    re-implementation, which aggregated by run+cell+emitter with no
    `correlation_id` and repeated none of the role, stage or enum
    checks: a fresh delivery on one CID and a settlement on another
    both numbered `attempt=1` satisfied the producer while the
    verifier — which audits per CID — refused the same evidence.
    Two predicates that must agree are one predicate.

    Returns `(ok, winner_cid, integrity_issues, product_issues,
    settled_any)`.
    """
    cell_id = f"wss.{'p2e' if sender == 'phone' else 'e2p'}.after-connect"
    # Audit ROUND-15 P0-2: build the CID candidate set from EVERY
    # CID-bearing event under this direction's cell_id — NOT just
    # from CIDs whose chain qualifies. Architect proved that an
    # extra bare `diagnostic_send_dispatched` under a new CID
    # (with no route/delivery chain) previously stayed GREEN.
    # Now that CID counts as a candidate; direction is only OK
    # when there is EXACTLY ONE candidate CID whose chain qualifies.
    candidate_cids: set = set()
    for cid, evs in by_cid.items():
        if any(getattr(e, "cell_id", None) == cell_id for e in evs):
            candidate_cids.add(cid)
    qualifying: List[str] = []
    non_qualifying_issues: List[str] = []
    # ── Audit ROUND-30.16 ─────────────────────────────────
    # A CID the recipient reported as FAILED is explained, not
    # corrupt. It still does NOT qualify — the direction is FAIL,
    # which is the whole point — but its short triplet stops being
    # an integrity finding, because the capture is intact and the
    # PRODUCT is what went wrong.
    #
    # Bounded by the reported stage exactly as the record claims:
    # `received`/`decrypted` account for a missing persist AND ack,
    # `persisted`/`ledger_marked` for the missing ack alone. A stage
    # that over-claims leaves the unexplained part as a finding, and
    # the contradiction rule catches the over-claim itself.
    #
    # This is SMOKE-only. The full matrix keeps every rule it had,
    # including `triplet_complete == cells_expected_to_run * 5`: the
    # 2026-08-26 defect happened in the smoke, before `full` was
    # allowed to run at all.
    # ── Audit ROUND-30.17: the attempt is the unit of outcome ────
    #
    # R30.16 asked "does this envelope have one terminal record?",
    # which the 2026-08-26 run answers with "no, two" - and that run
    # is the reason any of this exists. The question is now asked of
    # each ATTEMPT, and the direction is delivered when at least one
    # attempt settled.
    r3017_integrity: List[str] = []
    r3017_product: List[str] = []
    settled_any = False
    for cid in sorted(candidate_cids):
        _filtered = [e for e in by_cid[cid]
                     if getattr(e, "cell_id", None) == cell_id]
        _int, _prod, _settled, _seen = r3017_audit_attempts(
            cid, _filtered, cell_id=cell_id, recipient=recipient)
        r3017_integrity.extend(_int)
        if _settled:
            settled_any = True
            # Audit ROUND-30.17: an attempt that failed and was then
            # retried into a settled delivery is a RETRY, not a
            # product failure. Promoting it would make every
            # successful redelivery report the product as broken,
            # which is both false and the fastest way to make the
            # signal worthless. The failed attempts stay in the
            # evidence; they just do not decide the outcome.
        else:
            r3017_product.extend(_prod)

    for cid in sorted(candidate_cids):
        evs = by_cid[cid]
        filtered = [e for e in evs if getattr(e, "cell_id", None) == cell_id]
        probs = _validate_cid_chain(
            cid, filtered,
            cell_id=cell_id,
            expected_sender_emitter=sender,
            expected_recipient_emitter=recipient,
            expected_inner_route=pin,
            profile_run_id=None,
        )
        # The attempt audit owns every recipient-side question in the
        # SMOKE now - the triplet and the fresh count both - so the
        # chain validator's versions would double-report, and its
        # "exactly one fresh" rule is simply wrong here: a redelivery
        # is a second attempt, not a second envelope. Everything else
        # it says still stands.
        #
        # The ROUND-30.16 recipient rules go with them: they counted
        # per ENVELOPE, so "settled alongside failed" fires on the
        # ordinary retry that succeeds on its second attempt. The
        # attempt audit re-issues each of them scoped to an attempt,
        # where they are true.
        #
        # `_validate_cid_chain` itself is untouched, so the FULL
        # MATRIX keeps every one of these rules exactly as it was.
        probs = [p for p in probs
                 if "missing recipient_" not in p
                 and "dedup_gate=fresh count" not in p
                 and "(audit ROUND-30.16)" not in p]
        if not probs:
            qualifying.append(cid)
        else:
            non_qualifying_issues.extend(probs)

    direction_issues: List[str] = []
    # Audit ROUND-15 P0-2: extra candidate CIDs beyond one force RED
    # regardless of whether the extras qualify.
    if len(candidate_cids) == 0:
        return False, "", direction_issues, [], False
    if len(candidate_cids) > 1:
        direction_issues.append(
            f"smoke {cell_id}: {len(candidate_cids)} candidate CIDs "
            f"({sorted(candidate_cids)}) — contract requires exactly 1 "
            f"(audit ROUND-15 P0-2)"
        )
    # ── Audit ROUND-30.20: a defect is a defect at any CID count ──
    #
    # These issues were reported ONLY inside the `> 1` branch, so for
    # the ordinary single-CID direction every defect `_validate_cid_chain`
    # found was computed and dropped. Deleting the direction's only
    # `diagnostic_send_dispatched` then gave `integrity_ok=True` with an
    # empty issue list: evidence accepted as trustworthy with no proof
    # the message was ever sent.
    #
    # An ACCOUNTED failure does not land here — the R30.16/30.17 filters
    # above remove the recipient-side triplet complaints, so a direction
    # that failed and said why leaves `probs` empty and qualifies. What
    # remains is a genuine chain defect, and it belongs in the report at
    # one candidate exactly as much as at two.
    direction_issues.extend(non_qualifying_issues)
    direction_issues.extend(r3017_integrity)
    # Delivered when an attempt settled, the chain is otherwise
    # sound, and the evidence about those attempts is trustworthy.
    ok = (len(candidate_cids) == 1
          and len(qualifying) == 1
          and settled_any
          and not r3017_integrity)
    winner = qualifying[0] if qualifying else next(iter(sorted(candidate_cids)))
    return ok, winner, direction_issues, r3017_product, settled_any

def smoke_direction_soundness(smoke_log_path: str, *, sender: str,
                              recipient: str, pin: str = "wss",
                              expected_run_id: Optional[str] = None,
                              expected_cell_id: Optional[str] = None,
                              expected_cid: Optional[str] = None) -> dict:
    """Audit ROUND-30.20: what the PRODUCER asks, answered by the
    verifier's own code.

    `wss3_live.sh` needs to know, before it writes `smoke_verdict.json`,
    whether a direction that did not deliver nevertheless has sound
    evidence — an accounted failure. It used to answer with a private
    awk predicate; it now calls this, which runs the same parser, the
    same role sweep and the same direction audit the verifier runs.

    `accounted` is deliberately conjunctive: no integrity finding of any
    kind, AND at least one product finding. A direction with nothing to
    say has not accounted for anything.
    """
    events, parse_errors = parse_events(smoke_log_path, "smoke")
    integrity: List[str] = list(parse_errors)
    expected_cell = f"wss.{'p2e' if sender == 'phone' else 'e2p'}.after-connect"
    # The producer asks about one direction while the smoke is still in
    # progress. A later direction may already have emitted a failed pin
    # breadcrumb; that makes the whole smoke RED, but it cannot erase a
    # delivery the earlier direction completed. Keep every record owned by
    # this cell, plus any record that reuses the CID this direction claims so
    # a wrong-cell/cross-run splice still fails closed.
    direction_events = [
        e for e in events
        if e.event in CROSS_RUN_TOLERATED_EVENTS
        or getattr(e, "cell_id", None) == expected_cell
        or (expected_cid is not None
            and getattr(e, "correlation_id", None) == expected_cid)
        or is_clear_breadcrumb(e, frozenset({expected_cell}))
    ]
    integrity.extend(smoke_event_surface_issues(
        direction_events, expected_run_id, expected_cids=(expected_cid,)))
    current_events = smoke_current_run_events(
        direction_events, expected_run_id)
    if expected_cell_id is not None and expected_cell_id != expected_cell:
        integrity.append(
            f"smoke direction caller expected cell_id={expected_cell_id!r}, "
            f"but sender={sender!r}/recipient={recipient!r} owns "
            f"{expected_cell!r} (audit ROUND-30.20)"
        )
    by_cid: Dict[str, List] = {}
    for e in current_events:
        if getattr(e, "correlation_id", None):
            by_cid.setdefault(e.correlation_id, []).append(e)
    ok, winner, dir_issues, product, settled = audit_smoke_direction(
        by_cid, sender=sender, recipient=recipient, pin=pin)
    integrity.extend(dir_issues)
    integrity.extend(smoke_overflow_ownership_issues(current_events))
    if expected_cid is not None and winner and winner != expected_cid:
        integrity.append(
            f"smoke {expected_cell}: producer correlation_id={expected_cid!r} "
            f"does not match the audited CID {winner!r} (audit ROUND-30.20)"
        )
    clean = not integrity
    return {
        "ok": bool(ok) and clean,
        "cid": winner or None,
        "settled": bool(settled),
        "integrity": integrity,
        "product": product,
        "accounted": clean and not ok and bool(product),
    }


def load_smoke_verdict(evidence_dir: str,
                       profile_run_id: Optional[str] = None,
                       profile: Optional[dict] = None,
                       *,
                       with_product: bool = False):
    """Audit ROUND-30.17: returns `(v, integrity_issues)` by default and
    `(v, integrity_issues, product_issues)` when asked. The default keeps
    every existing caller reading exactly what it used to read; the
    profile path asks for both so it can tell a failed product from
    failed evidence."""
    """Load + validate smoke_verdict.json AND independently rebuild
    the p2e + e2p CID chains directly from smoke.log.

    Audit ROUND-11 P0-#4: the ROUND-10 version trusted three JSON
    values (`integrity`, `p2e`, `e2p`) without cross-checking them
    against `smoke.log`. The architect replaced `smoke.log` with a
    literal `not a WSS_DIAG smoke chain`, re-computed SHA256SUMS,
    and the verifier returned GREEN. Fix: rebuild BOTH direction
    chains from `smoke.log` using the same discipline as
    `check_cell_coverage`. A JSON value not backed by smoke.log
    events is a schema violation.
    """
    # Audit ROUND-15 P1-1: routes through _load_json_object.
    v, load_issues = _load_json_object(
        os.path.join(evidence_dir, "smoke_verdict.json"),
        "smoke_verdict.json",
    )
    if load_issues:
        return (None, load_issues, []) if with_product else (None, load_issues)
    # Audit ROUND-22 P0-1: structural pass via schema authority.
    issues: List[str] = list(check_registered_surface("smoke_verdict.json", v))
    product_issues: List[str] = []
    _SMOKE_ALLOWED = frozenset(SCHEMA.SMOKE_VERDICT_SCHEMA.keys())
    if any("missing required key" in i or "unknown key" in i for i in issues):
        return (v, issues, product_issues) if with_product else (v, issues)
    # Acceptance authority (UNCHANGED by audit ROUND-29.1): a verdict
    # is acceptable only at OK/OK/GREEN. The schema now *permits* the
    # RED shape structurally so a fail-closed producer can emit it;
    # these three checks are what still reject it downstream.
    # ── Audit ROUND-30.17: acceptance is two questions, not one ───
    #
    # These three checks used to be the whole answer, and everything
    # they reported became an INTEGRITY finding. A smoke that failed and
    # said so honestly was therefore indistinguishable from a corrupt
    # capture, and the profile came back NOT_EVALUABLE - the verifier
    # refusing to evaluate a run whose evidence was intact.
    #
    # `integrity` is still an evidence question and stays HERE. A
    # producer that declares its own evidence RED has told us the
    # evidence cannot be trusted, and that is an integrity finding
    # whatever the rebuild later says. Dropping this check along with
    # the other two would have left a self-declared RED verdict
    # completely unexamined.
    if v.get("integrity") != "GREEN":
        issues.append(f"smoke_verdict.integrity={v.get('integrity')!r} (want GREEN)")
    # `p2e`/`e2p` are PRODUCT questions, so a declared FAIL is recorded
    # as a product outcome and left to the attempt audit to corroborate.
    # A declared FAIL that the log does NOT corroborate is caught there
    # and is an integrity finding, so this is not a way to hide one.
    if v.get("p2e") != "OK":
        product_issues.append(
            f"smoke_verdict.p2e={v.get('p2e')!r} — the phone-to-emulator "
            f"direction did not deliver (audit ROUND-30.17)")
    if v.get("e2p") != "OK":
        product_issues.append(
            f"smoke_verdict.e2p={v.get('e2p')!r} — the emulator-to-phone "
            f"direction did not deliver (audit ROUND-30.17)")

    # ── Audit ROUND-29.1 P1-2: pre-send abort fields ──────────────
    # These are written ONLY when the smoke never fired. Each is
    # enum-bounded by the schema; the rules below pin their mutual
    # consistency so a pre-send RED cannot be dressed up as a partial
    # success, and so a GREEN verdict can never carry them.
    _PRE_OUTER = ("pre_send_phone_outer", "pre_send_emu_outer")
    _PRE_PIN = ("pre_send_pin_stage", "pre_send_pin_emitter")
    _PRE_KEYS = ("pre_send_reason",) + _PRE_OUTER + _PRE_PIN
    _pre_present = {k for k in _PRE_KEYS if k in v}
    _reason = v.get("pre_send_reason")
    if _pre_present and _reason is None:
        issues.append(
            f"smoke_verdict carries pre_send field(s) {sorted(_pre_present)!r} "
            f"without pre_send_reason (audit ROUND-29.1 P1-2)"
        )
    if _reason is not None:
        _stage = v.get("pre_send_pin_stage")
        if v.get("integrity") != "RED":
            issues.append(
                f"smoke_verdict.pre_send_reason={_reason!r} requires "
                f"integrity=RED, got {v.get('integrity')!r} "
                f"(audit ROUND-29.1 P1-2)"
            )
        # The E2P direction never dispatched on ANY pre-send abort:
        # the E2P gate is the last thing evaluated.
        if v.get("e2p") != "FAIL":
            issues.append(
                f"smoke_verdict.pre_send_reason={_reason!r} requires "
                f"e2p=FAIL, got {v.get('e2p')!r} (audit ROUND-29.1 P1-2)"
            )
        if v.get("e2p_cid") is not None:
            issues.append(
                f"smoke_verdict.pre_send_reason={_reason!r} requires "
                f"e2p_cid=null (E2P never dispatched), got {v.get('e2p_cid')!r} "
                f"(audit ROUND-29.1 P1-2)"
            )
        # ── Audit ROUND-29.2 P1-1 ─────────────────────────────────
        # P2E is evaluated BEFORE the E2P pin is attempted, so an
        # abort at stage `e2p` can legitimately sit on top of a
        # COMPLETED P2E direction. R29.1 forced p2e=FAIL/null there,
        # which contradicted a smoke.log that already carried the P2E
        # dispatch — fail-closed, but false evidence. Only a
        # stage-`e2p` abort may carry a real P2E result, and the
        # value must agree with its CID.
        if _stage == "e2p":
            _p2e = v.get("p2e")
            _p2e_cid = v.get("p2e_cid")
            if _p2e == "OK":
                if not isinstance(_p2e_cid, str) or not _p2e_cid:
                    issues.append(
                        "smoke_verdict: p2e=OK at pre_send_pin_stage='e2p' "
                        f"requires a real p2e_cid, got {_p2e_cid!r} "
                        f"(audit ROUND-29.2 P1-1)"
                    )
            elif _p2e == "FAIL":
                if _p2e_cid is not None:
                    issues.append(
                        "smoke_verdict: p2e=FAIL requires p2e_cid=null, got "
                        f"{_p2e_cid!r} (audit ROUND-29.2 P1-1)"
                    )
            else:
                issues.append(
                    f"smoke_verdict.p2e={_p2e!r} invalid at "
                    f"pre_send_pin_stage='e2p' (audit ROUND-29.2 P1-1)"
                )
        else:
            # Every other abort happens before ANY dispatch.
            if v.get("p2e") != "FAIL":
                issues.append(
                    f"smoke_verdict.pre_send_reason={_reason!r} requires "
                    f"p2e=FAIL, got {v.get('p2e')!r} (audit ROUND-29.1 P1-2)"
                )
            if v.get("p2e_cid") is not None:
                issues.append(
                    f"smoke_verdict.pre_send_reason={_reason!r} requires "
                    f"p2e_cid=null (nothing was dispatched), got "
                    f"{v.get('p2e_cid')!r} (audit ROUND-29.1 P1-2)"
                )
        if _reason in ("direct_unavailable", "health_probe_failed"):
            _missing = sorted(set(_PRE_OUTER) - _pre_present)
            if _missing:
                issues.append(
                    f"smoke_verdict.pre_send_reason={_reason!r} requires "
                    f"{_missing!r} (audit ROUND-29.1 P1-2)"
                )
            _extra = sorted(set(_PRE_PIN) & _pre_present)
            if _extra:
                issues.append(
                    f"smoke_verdict.pre_send_reason={_reason!r} forbids "
                    f"{_extra!r} — pin fields belong to pin_unconfirmed only "
                    f"(audit ROUND-29.1 P1-2)"
                )
            _vals = [v.get(k) for k in _PRE_OUTER]
            if _reason == "direct_unavailable" and not _missing:
                if all(_x == "direct" for _x in _vals):
                    issues.append(
                        "smoke_verdict.pre_send_reason='direct_unavailable' "
                        "but both devices reported outer_transport='direct' "
                        "(audit ROUND-29.1 P1-2)"
                    )
            if _reason == "health_probe_failed" and not _missing:
                if "probe_failed" not in _vals:
                    issues.append(
                        "smoke_verdict.pre_send_reason='health_probe_failed' "
                        f"but neither device is 'probe_failed' (got {_vals!r}) "
                        f"(audit ROUND-29.1 P1-2)"
                    )
        elif _reason == "pin_unconfirmed":
            _missing = sorted(set(_PRE_PIN) - _pre_present)
            if _missing:
                issues.append(
                    f"smoke_verdict.pre_send_reason='pin_unconfirmed' requires "
                    f"{_missing!r} (audit ROUND-29.1 P1-2)"
                )
            _extra = sorted(set(_PRE_OUTER) & _pre_present)
            if _extra:
                issues.append(
                    f"smoke_verdict.pre_send_reason='pin_unconfirmed' forbids "
                    f"{_extra!r} — health had already passed at that point "
                    f"(audit ROUND-29.1 P1-2)"
                )
        elif _reason == "pin_clear_unconfirmed":
            # Audit ROUND-30.11 — the clear BETWEEN directions did not
            # confirm, so the later direction never dispatched. That
            # makes it a pre-send abort for E2P, and the pre-send rules
            # above already bound it: integrity=RED, e2p=FAIL, e2p_cid
            # null, and a stage-`e2p` abort may carry a real P2E result.
            #
            # Two things are specific to this reason.
            _missing = sorted(set(_PRE_PIN) - _pre_present)
            if _missing:
                issues.append(
                    f"smoke_verdict.pre_send_reason='pin_clear_unconfirmed' "
                    f"requires {_missing!r} (audit ROUND-30.11)"
                )
            _extra = sorted(set(_PRE_OUTER) & _pre_present)
            if _extra:
                issues.append(
                    f"smoke_verdict.pre_send_reason='pin_clear_unconfirmed' "
                    f"forbids {_extra!r} — health had already passed "
                    f"(audit ROUND-30.11)"
                )
            # The inter-direction clear only exists between P2E and E2P.
            if _stage != "e2p":
                issues.append(
                    f"smoke_verdict.pre_send_reason='pin_clear_unconfirmed' "
                    f"requires pre_send_pin_stage='e2p', got {_stage!r} — the "
                    f"clear that can abort a direction is the one between "
                    f"them (audit ROUND-30.11)"
                )
    # ── Audit ROUND-30.11: `both` belongs to the clear, not to the pin ─
    # The enum gained `both` for the two clears, which are attempted
    # independently. Pin CONFIRMATION short-circuits on the first failing
    # side and can therefore never legitimately report `both`; without
    # this rule, widening the enum would have quietly widened an
    # invariant that has nothing to do with this round.
    if _reason == "pin_unconfirmed" and v.get("pre_send_pin_emitter") == "both":
        issues.append(
            "smoke_verdict.pre_send_pin_emitter='both' is not reachable for "
            "pre_send_reason='pin_unconfirmed' — the pin check stops at the "
            "first failing side (audit ROUND-30.11)"
        )

    # ── Audit ROUND-30.11: post-send cleanup failure ──────────────────
    # A run whose directions BOTH completed and whose final clear did not
    # confirm. This is not a pre-send abort and must not be written as
    # one: those rules demand e2p=FAIL with a null cid, which would erase
    # a delivery that smoke.log proves. So it has its own fields, and the
    # two sets are mutually exclusive.
    _POST_KEYS = ("post_send_reason", "post_send_clear_stage",
                  "post_send_clear_emitter")
    _post_present = {k for k in _POST_KEYS if k in v}
    _post_reason = v.get("post_send_reason")
    if _post_present and _post_reason is None:
        issues.append(
            f"smoke_verdict carries post_send field(s) {sorted(_post_present)!r} "
            f"without post_send_reason (audit ROUND-30.11)"
        )
    if _post_reason is not None:
        if _reason is not None:
            issues.append(
                f"smoke_verdict carries both pre_send_reason={_reason!r} and "
                f"post_send_reason={_post_reason!r} — a run either aborted "
                f"before dispatch or completed and failed to clean up, never "
                f"both (audit ROUND-30.11)"
            )
        _pmissing = sorted(set(_POST_KEYS) - _post_present)
        if _pmissing:
            issues.append(
                f"smoke_verdict.post_send_reason={_post_reason!r} requires "
                f"{_pmissing!r} (audit ROUND-30.11)"
            )
        if v.get("integrity") != "RED":
            issues.append(
                f"smoke_verdict.post_send_reason={_post_reason!r} requires "
                f"integrity=RED, got {v.get('integrity')!r} "
                f"(audit ROUND-30.11)"
            )
        # Both directions ran. Their results are judged from the same
        # evidence a clean run uses and are NOT erased by the cleanup
        # failure — that is the whole point of a separate field.
        for _d, _dcid in (("p2e", "p2e_cid"), ("e2p", "e2p_cid")):
            _dv = v.get(_d)
            _cv = v.get(_dcid)
            if _dv == "OK":
                if not isinstance(_cv, str) or not _cv:
                    issues.append(
                        f"smoke_verdict: {_d}=OK under post_send_reason="
                        f"{_post_reason!r} requires a real {_dcid}, got "
                        f"{_cv!r} (audit ROUND-30.11)"
                    )
            elif _dv == "FAIL":
                if _cv is not None:
                    issues.append(
                        f"smoke_verdict: {_d}=FAIL requires {_dcid}=null, got "
                        f"{_cv!r} (audit ROUND-30.11)"
                    )
            else:
                issues.append(
                    f"smoke_verdict.{_d}={_dv!r} invalid under "
                    f"post_send_reason={_post_reason!r} (audit ROUND-30.11)"
                )
        # A cleanup failure that erased BOTH directions is not a cleanup
        # failure — it is a pre-send abort wearing the wrong field.
        if v.get("p2e") == "FAIL" and v.get("e2p") == "FAIL":
            issues.append(
                f"smoke_verdict.post_send_reason={_post_reason!r} with both "
                f"directions FAIL — nothing was delivered, so this is a "
                f"pre-send abort and must be reported as one "
                f"(audit ROUND-30.11)"
            )
    # Audit ROUND-17 P0-3: attempt/arm-token MUST match the profile.
    if profile is not None:
        if v.get("attempt_id") != profile.get("attempt_id"):
            issues.append(
                f"smoke_verdict.attempt_id={v.get('attempt_id')!r} != "
                f"PROFILE.attempt_id={profile.get('attempt_id')!r} "
                f"(smoke evidence bound to different attempt — audit ROUND-17 P0-3)"
            )
        if v.get("arm_token") != profile.get("arm_token"):
            issues.append(
                f"smoke_verdict.arm_token={v.get('arm_token')!r} != "
                f"PROFILE.arm_token={profile.get('arm_token')!r} "
                f"(smoke evidence bound to different arm — audit ROUND-17 P0-3)"
            )
    # smoke_run_id must be a non-empty string.
    _srid = v.get("smoke_run_id")
    if not isinstance(_srid, str) or not _srid:
        issues.append(
            f"smoke_verdict.smoke_run_id must be a non-empty string, got {_srid!r}"
        )

    # Audit ROUND-11 P0-#4: rebuild chains from smoke.log.
    sl = os.path.join(evidence_dir, "smoke.log")
    if not os.path.isfile(sl):
        issues.append("smoke.log missing (JSON verdict not independently verifiable)")
        return (v, issues, product_issues) if with_product else (v, issues)
    # Audit ROUND-30.20: producer and verifier consume this same GLOBAL
    # event-surface authority before either may report smoke integrity GREEN.
    # Per-direction helpers remain scoped so one direction cannot erase the
    # other, but their two answers are not a substitute for this whole-log
    # audit.
    _smoke_rid = _srid if isinstance(_srid, str) and _srid else None
    _declared_smoke_cids = (v.get("p2e_cid"), v.get("e2p_cid"))
    events, surface_issues = smoke_global_surface_audit(
        sl, expected_run_id=_smoke_rid,
        expected_cids=_declared_smoke_cids)
    issues.extend(surface_issues)
    if not events:
        return (v, issues, product_issues) if with_product else (v, issues)

    _current_smoke_events = smoke_current_run_events(events, _smoke_rid)

    # ── Audit ROUND-29.4 P0: dual-party pin ownership in the SMOKE ─
    # R29.3 enforced this for full cells only. Deleting the
    # recipient/emulator `diagnostic_pin_active` from a healthy
    # smoke.log and recomputing SHA256SUMS still produced GREEN/GREEN
    # — the smoke serialisation pins BOTH devices per direction, so a
    # missing breadcrumb means the pin the whole attribution fix
    # depends on was never proven. Same helper as the full matrix, so
    # the two paths cannot drift.
    #
    # Each direction is checked with ITS OWN sender/recipient pair;
    # the smoke always pins `wss`. Events are bound to the verdict's
    # smoke_run_id, which is what the rest of this loader uses.
    # ── Audit ROUND-29.5 P1: stage-aware ──────────────────────────
    # The smoke serialises P2E then E2P, so a pre-send abort means the
    # later direction never ran. R29.4 demanded both breadcrumbs from
    # BOTH directions unconditionally, which invented two findings on
    # a P2E-stage pin failure by requiring E2P pins that were never
    # attempted. A direction requires its pair only if it actually
    # completed pin confirmation:
    #
    #   no pre_send_reason              → both directions completed
    #   pin_unconfirmed @ stage e2p     → P2E completed, E2P partial
    #   pin_unconfirmed @ stage p2e     → neither completed
    #   direct_unavailable / probe fail → no pin was ever attempted
    #
    # Whatever IS present is validated in full either way, so a forged
    # partial pin cannot hide behind the abort.
    _completed_directions = {"wss.p2e.after-connect", "wss.e2p.after-connect"}
    # Audit ROUND-30.11: a clear that did not confirm BETWEEN the
    # directions aborts E2P exactly as an unconfirmed pin does, so
    # the same stage arithmetic applies. A post-send cleanup failure
    # means BOTH directions completed, so neither is excused.
    if _reason in ("pin_unconfirmed", "pin_clear_unconfirmed"):
        _stage = v.get("pre_send_pin_stage")
        if _stage == "e2p":
            _completed_directions = {"wss.p2e.after-connect"}
        else:
            _completed_directions = set()
    elif _reason is not None:
        _completed_directions = set()
    # Audit ROUND-30.11: a clear whose parent is neither of these two
    # belongs to nothing in this evidence. Reported separately, so a
    # wrong parent produces BOTH a missing finding where it was owed
    # and an orphan finding where it landed.
    # Audit ROUND-30.12: the completed directions, not both canonical
    # ones. `_completed_directions` is already the stage-aware answer
    # eighteen lines above; passing the literal pair instead is what
    # let a clear on a direction that never ran pass unreported.
    issues.extend(check_orphan_clear_events(
        _current_smoke_events,
        known_parents=_completed_directions,
        source="smoke.log",
    ))
    for _sc, _ssender, _srecipient in (
        ("wss.p2e.after-connect", "phone", "emulator"),
        ("wss.e2p.after-connect", "emulator", "phone"),
    ):
        issues.extend(check_dual_party_pin(
            _current_smoke_events,
            cell_id=_sc,
            sender_emitter=_ssender,
            recipient_emitter=_srecipient,
            expected_pin="wss",
            profile_run_id=_smoke_rid,
            source="smoke.log cell",
            require_both=(_sc in _completed_directions),
        ))
        # ── Audit ROUND-30.11: and the CLEAR that direction owes ──
        # Only a direction that actually completed owes clear evidence.
        # One the run never reached has nothing to clear, and demanding
        # a breadcrumb there would invent a defect — the same
        # stage-awareness the pin set already has.
        if _sc in _completed_directions:
            _cmiss = set()
            if _reason == "pin_clear_unconfirmed" and _sc == "wss.p2e.after-connect":
                _cmiss = _clear_failed_emitters(
                    v.get("pre_send_pin_emitter"), _ssender, _srecipient)
            elif _post_reason is not None and _sc == "wss.e2p.after-connect":
                _cmiss = _clear_failed_emitters(
                    v.get("post_send_clear_emitter"), _ssender, _srecipient)
            issues.extend(check_clear_breadcrumbs(
                _current_smoke_events,
                parent_cell=_sc,
                sender_emitter=_ssender,
                recipient_emitter=_srecipient,
                profile_run_id=_smoke_rid,
                source="smoke.log cell",
                expected_missing=_cmiss,
            ))
    # Group by CID; find at least one CID per direction that passes
    # the SINGLE strict per-CID validator shared with cell coverage.
    # Smoke has its own run_id (it fires before the matrix run_id is
    # minted). Audit only that declared smoke run; harmless records left
    # in logcat by earlier runs are history, not evidence for this run.
    by_cid: Dict[str, List] = {}
    for e in _current_smoke_events:
        if e.correlation_id:
            by_cid.setdefault(e.correlation_id, []).append(e)

    # Smoke revalidation requires exactly one qualifying CID per
    # direction. Redelivery is accounted per attempt, so more than one
    # fresh event is valid only when the attempt audit proves consecutive
    # ordinals and one terminal outcome for each.
    #
    # Rebuild each direction as a set: count CIDs whose chain
    # passes _validate_cid_chain with zero issues; count fresh
    # dedup events on the recipient emitter across every CID under
    # this direction's cell_id.
    _explained_directions: set = set()


    p2e_ok, p2e_cid, p2e_extra, p2e_prod, p2e_settled = audit_smoke_direction(
        by_cid, sender="phone", recipient="emulator", pin="wss")
    e2p_ok, e2p_cid, e2p_extra, e2p_prod, e2p_settled = audit_smoke_direction(
        by_cid, sender="emulator", recipient="phone", pin="wss")
    issues.extend(p2e_extra)
    issues.extend(e2p_extra)
    product_issues.extend(p2e_prod)
    product_issues.extend(e2p_prod)

    # A producer claiming OK that the rebuilt log does not confirm is
    # untrustworthy EVIDENCE, so these stay integrity findings. The
    # reverse - a declared FAIL the log corroborates - is a product
    # result and is recorded as one above.
    if v.get("p2e") == "OK" and not p2e_ok:
        issues.append("smoke.log has no CID with a complete strict p2e chain "
                      "(phone→emu) even though smoke_verdict.p2e=OK")
    if v.get("e2p") == "OK" and not e2p_ok:
        issues.append("smoke.log has no CID with a complete strict e2p chain "
                      "(emu→phone) even though smoke_verdict.e2p=OK")
    # A declared FAIL that nothing in the log accounts for is also
    # untrustworthy: the producer says the direction failed and the
    # evidence does not say why.
    if v.get("p2e") != "OK" and not p2e_prod:
        issues.append("smoke_verdict.p2e declares a failure that no attempt "
                      "record accounts for (audit ROUND-30.17)")
    if v.get("e2p") != "OK" and not e2p_prod:
        issues.append("smoke_verdict.e2p declares a failure that no attempt "
                      "record accounts for (audit ROUND-30.17)")
    # Audit ROUND-30.16: a direction the recipient itself reported as
    # failed does not contradict `integrity=GREEN`. The capture is
    # intact and says so; the DIRECTION is FAIL and the smoke product
    # outcome is RED. Only an unexplained missing chain is an integrity
    # problem.
    # Audit ROUND-30.17: a direction is EXPLAINED when every one of its
    # attempts has a terminal outcome and the attempt audit found nothing
    # untrustworthy. Then `integrity=GREEN` is a true statement about the
    # evidence even though the direction did not deliver.
    # ── Audit ROUND-30.18: the truncation marker cannot be parked ───
    #
    # The per-attempt audit only sees events on ITS direction's recipient
    # emitter, so a marker written with the wrong emitter was invisible
    # to it - and the verifier went back to judging a history the marker
    # says is incomplete. Moving one field is not a way to recover a
    # verdict, so every marker in the smoke is swept here and must name a
    # direction that exists and the recipient that owns it.
    #
    # A marker on the wrong emitter, or on no known direction at all, is
    # an intruder: it claims a truncation that belongs to nobody, and a
    # profile carrying one cannot be judged.
    issues.extend(smoke_overflow_ownership_issues(_current_smoke_events))

    _p2e_unexplained = not p2e_ok and not p2e_prod
    _e2p_unexplained = not e2p_ok and not e2p_prod
    if v.get("integrity") == "GREEN" and (_p2e_unexplained or _e2p_unexplained):
        issues.append("smoke_verdict.integrity=GREEN but the strict smoke.log "
                      "rebuild did not confirm both CID chains")
    # Cross-check the CIDs the JSON verdict claims match those the
    # strict rebuild proved. A JSON verdict that names one CID and a
    # log that contains a chain under a different CID is a schema
    # violation (audit ROUND-12 P0-#3: CID must match).
    for jkey, discovered in (("p2e_cid", p2e_cid), ("e2p_cid", e2p_cid)):
        if jkey in v and discovered and v[jkey] != discovered:
            issues.append(
                f"smoke_verdict.{jkey}={v.get(jkey)!r} does not match "
                f"the CID with a valid chain in smoke.log ({discovered!r})"
            )

    return (v, issues, product_issues) if with_product else (v, issues)


# ── Required §5 evidence files audit ROUND-10 P0-4

REQUIRED_EVIDENCE_FILES = [
    "PROFILE.json",
    "PROFILE_STATE.json",
    "network_profile.json",
    "EGRESS_FINGERPRINT.json",
    "signed_prekey_readiness.json",
    "checkpoint_log.json",
    "smoke.log",
    "smoke_verdict.json",
    "matrix_completion.json",
    "matrix_verdict.json",
    "SHA256SUMS.txt",
]

# Audit ROUND-30.19: the terminal-smoke profile (a RED smoke stopped the
# run, so `full` never wrote the matrix artefacts) owes every required
# file EXCEPT those the matrix phase produces. checkpoint_log.json is
# one of them: `wss3_live_full` initialises it and every entry is a
# per-cell (or post-matrix) drift check, so a profile the smoke stopped
# has honestly never had one. Derived from the one canonical list so
# the sets can never drift apart.
_MATRIX_PHASE_FILES = ("matrix_completion.json", "matrix_verdict.json",
                       "checkpoint_log.json")
REQUIRED_EVIDENCE_FILES_TERMINAL = [
    f for f in REQUIRED_EVIDENCE_FILES if f not in _MATRIX_PHASE_FILES
]


def check_required_evidence_files(evidence_dir: str,
                                  terminal: bool = False) -> List[str]:
    """Audit ROUND-10 P0-4: every §5-required file MUST exist. Missing
    any file → integrity RED. Not just PROFILE + matrix_completion any
    more — the network-profile artefacts + checkpoint_log + SHA256SUMS
    are equally load-bearing.

    Audit ROUND-30.19: `terminal=True` is the smoke-stopped profile —
    the matrix-phase files are not owed, everything else still is."""
    issues = []
    required = (REQUIRED_EVIDENCE_FILES_TERMINAL if terminal
                else REQUIRED_EVIDENCE_FILES)
    for name in required:
        p = os.path.join(evidence_dir, name)
        if not os.path.isfile(p):
            issues.append(f"required evidence file missing: {name}")
        elif os.path.getsize(p) == 0:
            issues.append(f"required evidence file empty: {name}")
    return issues


def _load_json_object(path: str, name: str) -> Tuple[Optional[dict], List[str]]:
    """Audit ROUND-15 P1-1: shared safe-loader that returns
    (obj-or-None, issues). Refuses missing / malformed / non-dict
    top-level values. Every load-bearing JSON loader routes through
    this so wrong types NEVER escape as an unhandled exception
    (architect proved `EGRESS_FINGERPRINT.json=[]` crashed the
    verifier with AttributeError).
    """
    if not os.path.isfile(path):
        return None, [f"{name} missing"]
    try:
        with open(path, encoding="utf-8") as f:
            obj = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        return None, [f"{name} malformed: {e}"]
    if not isinstance(obj, dict):
        return None, [
            f"{name} must be a JSON object, got {type(obj).__name__}"
        ]
    return obj, []


def check_egress_fingerprint_evidence(evidence_dir: str) -> List[str]:
    """Audit ROUND-10 P0-1 + P0-4: EGRESS_FINGERPRINT.json must NOT
    carry phone HMAC. equal_host_emu must be True. Both host and emu
    hex must be present (32 hex chars). address_family MUST be AF_INET
    on both. Audit ROUND-15 P1-1: routes through _load_json_object so
    a list top-level (or any non-dict) yields a clean issue instead
    of AttributeError."""
    ef, load_issues = _load_json_object(
        os.path.join(evidence_dir, "EGRESS_FINGERPRINT.json"),
        "EGRESS_FINGERPRINT.json",
    )
    if load_issues:
        return load_issues
    # Audit ROUND-22 P0-1: structural pass via schema authority.
    issues = list(check_registered_surface("EGRESS_FINGERPRINT.json", ef))
    # Semantic invariants:
    #   (a) phone-side HMAC fields banned (contract §5 / R10 P0-1).
    #   (b) equal_host_emu must be True (contract §10).
    for banned in ("phone_hmac_fp_hex", "address_family_phone"):
        if banned in ef:
            issues.append(
                f"EGRESS_FINGERPRINT.json contains banned field {banned!r} "
                f"(phone MUST NOT participate in HMAC)"
            )
    if ef.get("equal_host_emu") is not True:
        issues.append(
            f"EGRESS_FINGERPRINT.equal_host_emu="
            f"{ef.get('equal_host_emu')!r} (want True)"
        )
    return issues


_NETWORK_PROFILE_TOP = frozenset({"phone", "emu"})

# Audit ROUND-20 P0-1: allowlist MUST cover EVERY top-level key the
# real `DiagnosticNetworkProfileReporter.buildJson` emits. The
# ROUND-19 subset omitted six legitimate producer fields and would
# have rejected real preflight evidence as NOT_EVALUABLE:
#   schema_version, at_wall_ms, at_monotonic_ms,
#   active_network_present, process_uid, app_debuggable
# Source pin: apps/android/src/debug/kotlin/phantom/android/
# diagnostic/DiagnosticNetworkProfileReporter.kt:524-582.
# Audit ROUND-22 P0-1: hand-written ALLOWED/REQUIRED/pin/enum/
# regex tables removed. `schema_wss3.SURFACES` is the SINGLE
# executable authority; `check_schema_object` above enforces
# closed keys + required + type + nullability + enum + pin +
# min/max + regex against every JSON evidence surface.
#
# Legacy constants deleted: _NETWORK_PROFILE_DEVICE_ALLOWED,
# _NETWORK_PROFILE_DEVICE_REQUIRED, _NETWORK_PROFILE_EGRESS_ALLOWED,
# _NETWORK_PROFILE_EGRESS_REQUIRED, _NP_SCHEMA_VERSION_PIN,
# _NP_ENDPOINT_PIN, _NP_TIMEOUT_MS_PIN, _NP_AF_ENUM,
# _check_np_device_values, _check_np_egress_values.


def _isbool(x) -> bool:
    return type(x) is bool


def _isint(x) -> bool:
    # bool is a subclass of int — reject bools where int is required.
    return type(x) is int


def _iststr(x) -> bool:
    return isinstance(x, str)


# Audit ROUND-22 P0-1: `_check_np_device_values` and
# `_check_np_egress_values` deleted. Their strict type / enum /
# pin / null checks are now performed generically by
# `check_schema_object` against `schema_wss3.NETWORK_PROFILE_*`.
# Only semantic invariants (parent/nested at_wall_ms equality,
# success invariant, cross-file bindings) remain in
# `check_network_profile_evidence` itself.


def check_network_profile_evidence(evidence_dir: str,
                                   profile: Optional[dict] = None) -> List[str]:
    """Audit ROUND-10 P0-1 → ROUND-22 P0-1: schema_wss3.SURFACES
    drives the closed/required/type/enum/pin/min/max/regex pass
    (via check_schema_object); this function adds ONLY the
    semantic invariants that a per-field structural check can't
    express (phone-side egress ban, egress required on emu,
    per-side value pins, cross-file bindings, success invariant,
    parent/nested at_wall_ms equality).
    """
    np, load_issues = _load_json_object(
        os.path.join(evidence_dir, "network_profile.json"),
        "network_profile.json",
    )
    if load_issues:
        return load_issues
    issues = []
    # Structural pass via schema-registry authority — per-side.
    if isinstance(np.get("phone"), dict):
        issues.extend(check_registered_surface(
            "network_profile.json/phone", np["phone"],
        ))
    if isinstance(np.get("emu"), dict):
        issues.extend(check_registered_surface(
            "network_profile.json/emu", np["emu"],
        ))
        egf = np["emu"].get("egress_fingerprint")
        if isinstance(egf, dict):
            issues.extend(check_registered_surface(
                "network_profile.json/emu/egress_fingerprint", egf,
            ))
    # Top-level closed schema — only {phone, emu} allowed.
    _NP_TOP = frozenset({"phone", "emu"})
    _extra_top = sorted(set(np.keys()) - _NP_TOP)
    if _extra_top:
        issues.append(
            f"network_profile.json has unknown key(s) {_extra_top!r} "
            f"(closed schema, audit ROUND-22 P0-1)"
        )
    phone = np.get("phone") or {}
    emu = np.get("emu") or {}
    # Semantic invariants the schema module doesn't express:
    #   (a) both sides required and non-empty,
    #   (b) phone side MUST NOT carry egress_fingerprint,
    #   (c) emu side MUST carry egress_fingerprint as a non-null object.
    if not isinstance(phone, dict) or not phone:
        issues.append("network_profile.json.phone missing/empty")
    if not isinstance(emu, dict) or not emu:
        issues.append("network_profile.json.emu missing/empty")
    if isinstance(phone, dict) and "egress_fingerprint" in phone:
        issues.append(
            "network_profile.json.phone contains egress_fingerprint "
            "(contract violation, audit ROUND-10 P0-1)"
        )
    if isinstance(emu, dict):
        if "egress_fingerprint" not in emu:
            issues.append(
                "network_profile.emu missing required key "
                "'egress_fingerprint' (audit ROUND-20 P0-2)"
            )
        elif emu.get("egress_fingerprint") is None:
            issues.append(
                "network_profile.emu.egress_fingerprint=null "
                "— non-nullable (audit ROUND-21 P0-2)"
            )
    # Audit ROUND-22 P0-3: parent/nested at_wall_ms equality. The
    # producer writes the SAME wall-clock into both slots
    # (DiagnosticNetworkProfileReporter.kt:527, 566). A divergence
    # means either the parent or the nested was tampered.
    if isinstance(emu, dict):
        egf = emu.get("egress_fingerprint")
        if isinstance(egf, dict):
            parent_ts = emu.get("at_wall_ms")
            nested_ts = egf.get("at_wall_ms")
            if type(parent_ts) is int and type(nested_ts) is int \
                    and parent_ts != nested_ts:
                issues.append(
                    f"network_profile.emu.at_wall_ms={parent_ts!r} != "
                    f"network_profile.emu.egress_fingerprint.at_wall_ms="
                    f"{nested_ts!r} (parent/nested wall-clock MUST agree, "
                    f"audit ROUND-22 P0-3)"
                )
            # Audit ROUND-22 P0-3: success invariant. A non-null
            # `hmac_fp_hex` means the HTTP fetch succeeded, so
            # `http_status` MUST be a 2xx int. A hmac against a 5xx
            # response is a producer contradiction.
            hh = egf.get("hmac_fp_hex")
            hs = egf.get("http_status")
            if hh is not None:
                if not (type(hs) is int and 200 <= hs < 300):
                    issues.append(
                        f"network_profile.emu.egress_fingerprint: "
                        f"hmac_fp_hex={hh!r} is non-null but "
                        f"http_status={hs!r} is not 2xx — non-null HMAC "
                        f"implies a successful 2xx response "
                        f"(audit ROUND-22 P0-3)"
                    )
    # Audit ROUND-21 P0-3: cross-file binding when the caller
    # supplies the parsed PROFILE.json. network_profile is the
    # PRIMARY observation; PROFILE and PROFILE_STATE summaries
    # MUST NOT contradict it.
    if profile is not None and isinstance(phone, dict):
        want_op = profile.get("expected_operator_numeric")
        got_op  = phone.get("active_data_sim_operator_numeric")
        if want_op is not None and got_op is not None and got_op != want_op:
            issues.append(
                f"network_profile.phone.active_data_sim_operator_numeric="
                f"{got_op!r} disagrees with PROFILE.expected_operator_numeric="
                f"{want_op!r} (audit ROUND-21 P0-3)"
            )
        want_vpn = (profile.get("phone_vpn") == "on")
        got_vpn  = phone.get("has_transport_vpn")
        if _isbool(got_vpn) and got_vpn is not want_vpn:
            issues.append(
                f"network_profile.phone.has_transport_vpn={got_vpn!r} "
                f"disagrees with PROFILE.phone_vpn={profile.get('phone_vpn')!r} "
                f"(audit ROUND-21 P0-3)"
            )
    return issues


def check_signed_prekey_readiness(evidence_dir: str) -> List[str]:
    """Audit ROUND-15 P1-1 → ROUND-22 P0-1: structural pass via
    schema authority; line grammar stays here (strict parser)."""
    spr, load_issues = _load_json_object(
        os.path.join(evidence_dir, "signed_prekey_readiness.json"),
        "signed_prekey_readiness.json",
    )
    if load_issues:
        return load_issues
    issues = list(check_registered_surface(
        "signed_prekey_readiness.json", spr,
    ))
    # Audit ROUND-21 P1-1: strict closed-field parser (strict grammar).
    # Substring matching is forbidden — architect proved
    #   `signed_prekey_readiness published=false reason=bad published=true`
    # passed under the old contains() check.
    _prekey_re = re.compile(
        r"^signed_prekey_readiness\s+"
        r"published=(?P<published>true|false)\s+"
        r"signed_prekey_age_days=(?P<age>\d+)\s+"
        r"remaining_opks=(?P<opks>\d+)\s*$"
    )
    for side in ("phone_line", "emu_line"):
        line = spr.get(side, "")
        if not isinstance(line, str):
            issues.append(
                f"signed_prekey_readiness.{side} must be a string, "
                f"got {type(line).__name__} (audit ROUND-21 P1-1)"
            )
            continue
        m = _prekey_re.match(line)
        if m is None:
            issues.append(
                f"signed_prekey_readiness.{side} does not match strict "
                f"grammar `signed_prekey_readiness published=<bool> "
                f"signed_prekey_age_days=<int> remaining_opks=<int>`: "
                f"{line!r} (audit ROUND-21 P1-1)"
            )
            continue
        if m.group("published") != "true":
            issues.append(
                f"signed_prekey_readiness.{side} published="
                f"{m.group('published')!r} — must be exactly `true` for GREEN "
                f"(audit ROUND-21 P1-1)"
            )
    return issues


def _canonical_checkpoint_labels(cells_expected_to_run: int) -> List[str]:
    """Return the exact canonical label sequence for a fully-completed
    run. Structure: pre-cell:<cell-id> for each cell in canonical order
    + `post-matrix`. If cells_expected_to_run=6 (REST-blocked) the six
    labels correspond to the six non-REST cells in canonical order.
    """
    all_cells = canonical_cell_ids()
    if cells_expected_to_run == 8:
        chosen = all_cells
    elif cells_expected_to_run == 6:
        chosen = [c for c in all_cells if c not in CANONICAL_REST_CELL_IDS]
    else:
        chosen = all_cells[:cells_expected_to_run]
    return [f"pre-cell:{c}" for c in chosen] + ["post-matrix"]


def check_checkpoint_log(evidence_dir: str,
                         cells_expected_to_run: Optional[int] = None,
                         profile: Optional[dict] = None,
                         matrix_aborted: bool = False,
                         cells_ran: Optional[int] = None,
                         abort_reason: Optional[str] = None) -> List[str]:
    """Audit ROUND-10 P0-4 hardened per audit ROUND-11 P0-#6.

    A completed profile with `cells_expected_to_run=N` MUST have
    exactly N pre-cell checkpoints + 1 post-matrix = N+1 entries.
    A profile that ran only one checkpoint and completed 8 cells is
    NOT a valid GREEN — the missing checkpoints prove the orchestrator
    skipped its mandated re-validation between cells.

    Every observed sub-object MUST carry the full audit-ROUND-11
    envelope (APK, paired-count, signed-prekey, host-VPN, phone
    Wi-Fi/mobile/VPN, SIM sub_id, operator numeric) with non-null
    values — an all-null observed passes structural checks but hides
    a checkpoint that never actually ran the probes.
    """
    p = os.path.join(evidence_dir, "checkpoint_log.json")
    if not os.path.isfile(p):
        return ["checkpoint_log.json missing"]
    try:
        with open(p, encoding="utf-8") as f:
            arr = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        return [f"checkpoint_log.json malformed: {e}"]
    if not isinstance(arr, list):
        return [f"checkpoint_log.json must be a list, got {type(arr).__name__}"]
    if not arr:
        return ["checkpoint_log.json is empty (expected at least 1 checkpoint entry)"]
    issues: List[str] = []
    if cells_expected_to_run is not None:
        want_entries = cells_expected_to_run + 1  # N pre-cell + 1 post-matrix
        want_labels = _canonical_checkpoint_labels(cells_expected_to_run)
        got_labels = [entry.get("label") for entry in arr if isinstance(entry, dict)]
        if matrix_aborted:
            # ── Audit ROUND-29.4 P1 ────────────────────────────────
            # An aborted matrix stops mid-sequence, so demanding all
            # N+1 entries reported a defect that is really just the
            # abort. The honest requirement is a PREFIX: the labels
            # present must be the canonical sequence truncated at the
            # point of the abort, with no post-matrix entry, no
            # reordering and no repeats. That still catches a forged
            # or shuffled log.
            # ── Audit ROUND-29.5 P0 ────────────────────────────────
            # R29.4 accepted ANY canonical prefix, so a three-entry log
            # could be truncated to one and still pass. The length is
            # not free: every cell that ran logged its pre-cell
            # checkpoint, and the cell the matrix stopped on logged its
            # own before failing — so an aborted log has exactly
            # cells_ran + 1 entries and no post-matrix entry.
            # ── Audit ROUND-30.11 ──────────────────────────────
            # That count assumes the aborting cell did NOT finish, so
            # `cells_ran` excludes it and its own pre-cell checkpoint
            # is the extra entry. `pin_clear_unconfirmed` is the one
            # reason where the cell DID finish: it is counted in
            # `cells_ran`, its checkpoint is already inside that
            # count, and no later cell ever started. Demanding the
            # extra entry there reports a defect that is really the
            # abort being recorded correctly — the same mistake the
            # per-cell zero-count rule made before this round.
            if cells_ran is None:
                _want_aborted = None
            elif abort_reason == "pin_clear_unconfirmed":
                _want_aborted = cells_ran
            else:
                _want_aborted = cells_ran + 1
            if _want_aborted is not None and len(arr) != _want_aborted:
                issues.append(
                    f"checkpoint_log.json has {len(arr)} entries, expected exactly "
                    f"{_want_aborted} for an aborted profile "
                    f"(abort_reason={abort_reason!r} cells_ran={cells_ran}; "
                    f"every cell that started logged its pre-cell checkpoint "
                    f"and no post-matrix entry is written — audit "
                    f"ROUND-29.5 P0, ROUND-30.11)"
                )
            elif len(arr) > want_entries:
                issues.append(
                    f"checkpoint_log.json has {len(arr)} entries, more than the "
                    f"{want_entries} a complete run would write "
                    f"(aborted profile — audit ROUND-29.4 P1)"
                )
            elif got_labels != want_labels[:len(got_labels)]:
                issues.append(
                    f"checkpoint_log.json is not a canonical prefix of the "
                    f"expected sequence: got={got_labels} "
                    f"want_prefix={want_labels[:len(got_labels)]} "
                    f"(aborted profile — audit ROUND-29.4 P1)"
                )
        else:
            if len(arr) != want_entries:
                issues.append(
                    f"checkpoint_log.json has {len(arr)} entries, "
                    f"expected exactly {want_entries} (cells_expected_to_run={cells_expected_to_run} "
                    f"pre-cell + 1 post-matrix)"
                )
            # Audit ROUND-12 P0-#4: labels MUST match the canonical
            # sequence exactly. Prior version allowed arbitrary/repeating
            # labels — a matrix could log 9 entries all labelled
            # `pre-cell:cell-0` and still pass structurally.
            if got_labels != want_labels:
                issues.append(
                    f"checkpoint_log.json label sequence mismatch: "
                    f"got={got_labels} want={want_labels} "
                    f"(audit ROUND-12 P0-#4: canonical order + no repeats)"
                )
    # Fields that MUST be present + non-null in every observed sub-object.
    REQ_OBS_NON_NULL = (
        "apk_sha256_phone", "apk_sha256_emu",
        "paired_count_phone", "paired_count_emu",
        "signed_prekey_published_phone", "signed_prekey_published_emu",
        "host_vpn_ok",
        "phone_wifi_enabled", "phone_mobile_data_enabled",
        "phone_has_transport_vpn",
        "phone_active_data_subscription_id",
        "phone_active_data_sim_operator_numeric",
    )
    # Audit ROUND-12 P0-#4: observed values MUST match PROFILE-derived
    # expectations. Prior version accepted APK/SIM/operator/VPN
    # mismatches, Wi-Fi ON, mobile-data OFF, prekey=false as long as
    # the fields were non-null. Values MUST match: contract §4.6.
    want_apk = (profile or {}).get("apk_sha256")
    want_op  = (profile or {}).get("expected_operator_numeric")
    want_pvpn_on = ((profile or {}).get("phone_vpn") == "on")
    want_hvpn_on = ((profile or {}).get("host_vpn") == "on")

    # Audit ROUND-22 P0-1: DERIVED from schema authority.
    _CHECKPOINT_TOP_ALLOWED = frozenset(SCHEMA.CHECKPOINT_ENTRY_SCHEMA.keys())
    _CHECKPOINT_OBS_ALLOWED = frozenset(SCHEMA.CHECKPOINT_OBSERVED_SCHEMA.keys())
    _CHECKPOINT_TOP_REQUIRED = frozenset(
        n for n, s in SCHEMA.CHECKPOINT_ENTRY_SCHEMA.items()
        if s.get("presence") == SCHEMA.P_REQ
    )
    for i, entry in enumerate(arr):
        if not isinstance(entry, dict):
            issues.append(f"checkpoint_log[{i}] not an object")
            continue
        # Audit ROUND-23 P1: structural pass via schema-registry.
        issues.extend(check_registered_surface(
            "checkpoint_log.json/entry", entry,
        ))
        for k in ("label", "started_at_wall_ms", "completed_at_wall_ms",
                  "started_at_monotonic_ms", "completed_at_monotonic_ms",
                  "result", "drift", "observed"):
            if k not in entry:
                issues.append(f"checkpoint_log[{i}] missing key {k!r}")
        # Audit ROUND-12 P0-#4: `result=drift` in a non-aborted final
        # matrix is a contradiction — every checkpoint must be `ok` for
        # the profile to reach GREEN.
        if entry.get("result") == "drift":
            issues.append(f"checkpoint_log[{i}].result='drift' — GREEN requires every checkpoint result='ok'")
        elif entry.get("result") not in (None, "ok"):
            issues.append(f"checkpoint_log[{i}].result invalid: {entry.get('result')!r}")
        if entry.get("drift") is not False:
            issues.append(f"checkpoint_log[{i}].drift={entry.get('drift')!r} (must be False in a GREEN profile)")
        # Audit ROUND-23 P0: coherence — a checkpoint that reports
        # `result=ok AND drift=false` cannot ALSO carry a non-null
        # `abort_reason`. The two channels are contradictory
        # (ok means "no abort", abort_reason=<x> means "aborted
        # because x"). This closes the R22 REDLINE false-GREEN
        # where a valid enum value stayed GREEN alongside
        # result=ok/drift=false.
        _result_ok = entry.get("result") in (None, "ok")
        _drift_false = entry.get("drift") is False
        _abort_present = entry.get("abort_reason") is not None
        if _result_ok and _drift_false and _abort_present:
            issues.append(
                f"checkpoint_log[{i}]: result=ok AND drift=false but "
                f"abort_reason={entry.get('abort_reason')!r} — the "
                f"coherence rule requires abort_reason=null when "
                f"result=ok/drift=false (audit ROUND-23 P0)"
            )
        # Audit ROUND-15 P1-1: nested type guard on `observed`. An
        # `observed=[]` or any non-dict value used to fall through
        # to `.get()`-on-dict shortly after, killing the verifier.
        obs_raw = entry.get("observed")
        if obs_raw is None:
            obs = {}
        elif not isinstance(obs_raw, dict):
            issues.append(
                f"checkpoint_log[{i}].observed must be a JSON object, "
                f"got {type(obs_raw).__name__}"
            )
            obs = {}
        else:
            obs = obs_raw
            # Audit ROUND-23 P1: nested `observed` also via registry.
            issues.extend(check_registered_surface(
                "checkpoint_log.json/entry/observed", obs,
            ))
        for k in REQ_OBS_NON_NULL:
            if k not in obs:
                issues.append(f"checkpoint_log[{i}].observed missing {k!r}")
            elif obs[k] is None:
                issues.append(f"checkpoint_log[{i}].observed.{k}=null (audit ROUND-11 P0-#6: observed fields MUST be captured)")
        # Audit ROUND-13 P0-2: STRICT type checks. Prior version
        # accepted `paired_count=True` (bool), subscription IDs as
        # lists, etc. as long as fields were non-null.
        for _bool_key in ("signed_prekey_published_phone",
                          "signed_prekey_published_emu", "host_vpn_ok",
                          "phone_wifi_enabled",
                          "phone_mobile_data_enabled",
                          "phone_has_transport_vpn"):
            v = obs.get(_bool_key)
            if v is not None and type(v) is not bool:
                issues.append(
                    f"checkpoint_log[{i}].observed.{_bool_key}={v!r} "
                    f"(type={type(v).__name__}) — must be bool"
                )
        for _int_key in ("paired_count_phone", "paired_count_emu"):
            v = obs.get(_int_key)
            if v is not None and (type(v) is not int or isinstance(v, bool)):
                issues.append(
                    f"checkpoint_log[{i}].observed.{_int_key}={v!r} "
                    f"(type={type(v).__name__}) — must be int"
                )
        for _str_key in ("apk_sha256_phone", "apk_sha256_emu",
                         "phone_active_data_subscription_id",
                         "phone_active_data_sim_operator_numeric"):
            v = obs.get(_str_key)
            if v is not None and not isinstance(v, str):
                issues.append(
                    f"checkpoint_log[{i}].observed.{_str_key}={v!r} "
                    f"(type={type(v).__name__}) — must be str"
                )
        # Audit ROUND-13 P0-2: HMAC + AF fields at entry top level
        # MUST be well-formed. Prior version accepted null/malformed
        # HMAC and AF_INET6 as long as the keys existed.
        for _hmac_key in ("host_hmac_fp_hex", "emu_hmac_fp_hex"):
            v = entry.get(_hmac_key)
            if not isinstance(v, str) or not re.match(r"^[0-9a-f]{32}$", v or ""):
                issues.append(
                    f"checkpoint_log[{i}].{_hmac_key}={v!r} "
                    f"— must be 32-char lowercase hex"
                )
        if entry.get("equal_host_emu") is not True:
            issues.append(
                f"checkpoint_log[{i}].equal_host_emu={entry.get('equal_host_emu')!r} — must be True"
            )
        for _af_key in ("address_family_host", "address_family_emu"):
            if entry.get(_af_key) != "AF_INET":
                issues.append(
                    f"checkpoint_log[{i}].{_af_key}={entry.get(_af_key)!r} — must be 'AF_INET'"
                )
        # Value correctness — audit ROUND-12 P0-#4.
        if profile is not None:
            if want_apk and obs.get("apk_sha256_phone") not in (None, want_apk):
                issues.append(f"checkpoint_log[{i}].observed.apk_sha256_phone={obs.get('apk_sha256_phone')!r} != PROFILE.apk_sha256={want_apk!r}")
            if want_apk and obs.get("apk_sha256_emu") not in (None, want_apk):
                issues.append(f"checkpoint_log[{i}].observed.apk_sha256_emu={obs.get('apk_sha256_emu')!r} != PROFILE.apk_sha256={want_apk!r}")
            if obs.get("paired_count_phone") not in (None, 1):
                issues.append(f"checkpoint_log[{i}].observed.paired_count_phone={obs.get('paired_count_phone')!r} (want 1)")
            if obs.get("paired_count_emu") not in (None, 1):
                issues.append(f"checkpoint_log[{i}].observed.paired_count_emu={obs.get('paired_count_emu')!r} (want 1)")
            if obs.get("signed_prekey_published_phone") is False:
                issues.append(f"checkpoint_log[{i}].observed.signed_prekey_published_phone=False (regressed)")
            if obs.get("signed_prekey_published_emu") is False:
                issues.append(f"checkpoint_log[{i}].observed.signed_prekey_published_emu=False (regressed)")
            if obs.get("host_vpn_ok") is False:
                issues.append(f"checkpoint_log[{i}].observed.host_vpn_ok=False (host-VPN check failed)")
            if obs.get("phone_wifi_enabled") is True:
                issues.append(f"checkpoint_log[{i}].observed.phone_wifi_enabled=True (Wi-Fi must be OFF)")
            if obs.get("phone_mobile_data_enabled") is False:
                issues.append(f"checkpoint_log[{i}].observed.phone_mobile_data_enabled=False (mobile data must be ON)")
            if obs.get("phone_has_transport_vpn") is not None and \
               obs.get("phone_has_transport_vpn") != want_pvpn_on:
                issues.append(
                    f"checkpoint_log[{i}].observed.phone_has_transport_vpn="
                    f"{obs.get('phone_has_transport_vpn')!r} != want {want_pvpn_on} "
                    f"(profile.phone_vpn={(profile or {}).get('phone_vpn')!r})"
                )
            if want_op and obs.get("phone_active_data_sim_operator_numeric") not in (None, want_op):
                issues.append(
                    f"checkpoint_log[{i}].observed.phone_active_data_sim_operator_numeric="
                    f"{obs.get('phone_active_data_sim_operator_numeric')!r} != "
                    f"PROFILE.expected_operator_numeric={want_op!r}"
                )
    return issues


# Audit ROUND-22 P0-1: PROFILE_STATE handwritten allowed/required
# tables removed. `check_schema_object` reads from
# `schema_wss3.PROFILE_STATE_PHONE_SCHEMA` +
# `schema_wss3.PROFILE_STATE_EMU_SCHEMA`; semantic-only checks
# (Wi-Fi off pin, mobile data on pin, cross-check with PROFILE,
# emu-side VPN-off pin) stay in check_profile_state.

def check_profile_state(evidence_dir: str, profile: dict) -> List[str]:
    """Audit ROUND-10 P0-4 → ROUND-22 P0-1: structural pass via
    schema_wss3; semantic pins + cross-check with PROFILE stay
    below."""
    ps, load_issues = _load_json_object(
        os.path.join(evidence_dir, "PROFILE_STATE.json"),
        "PROFILE_STATE.json",
    )
    if load_issues:
        return load_issues
    issues: List[str] = []
    # Top-level closed schema: exactly {phone_observed, emu_observed}.
    _TOP = frozenset({"phone_observed", "emu_observed"})
    _extra_top = sorted(set(ps.keys()) - _TOP)
    if _extra_top:
        issues.append(
            f"PROFILE_STATE.json has unknown key(s) {_extra_top!r} "
            f"(closed schema, audit ROUND-22 P0-1)"
        )
    _missing_top = sorted(_TOP - set(ps.keys()))
    if _missing_top:
        issues.append(
            f"PROFILE_STATE.json missing required key(s) {_missing_top!r} "
            f"(audit ROUND-22 P0-1)"
        )
    phone_obs = ps.get("phone_observed")
    if not isinstance(phone_obs, dict):
        return issues + [
            f"PROFILE_STATE.json.phone_observed must be a JSON object, "
            f"got {type(phone_obs).__name__}"
        ]
    # Structural pass via schema-registry (audit ROUND-23 P1).
    issues.extend(check_registered_surface(
        "PROFILE_STATE.json/phone_observed", phone_obs,
    ))
    emu_obs = ps.get("emu_observed")
    if emu_obs is not None:
        if not isinstance(emu_obs, dict):
            issues.append(
                f"PROFILE_STATE.emu_observed must be a JSON object, "
                f"got {type(emu_obs).__name__}"
            )
        else:
            issues.extend(check_registered_surface(
                "PROFILE_STATE.json/emu_observed", emu_obs,
            ))
    # Semantic pins for matrix runs.
    if phone_obs.get("wifi_enabled") is True:
        issues.append(
            "PROFILE_STATE.phone_observed.wifi_enabled=true "
            "(must be false — Wi-Fi off during track)"
        )
    if phone_obs.get("mobile_data_enabled") is False:
        issues.append(
            "PROFILE_STATE.phone_observed.mobile_data_enabled=false "
            "(must be true)"
        )
    # Cross-check with PROFILE.
    if phone_obs.get("active_data_sim_operator_numeric") != profile.get("expected_operator_numeric"):
        issues.append(
            f"PROFILE_STATE.phone_observed.active_data_sim_operator_numeric="
            f"{phone_obs.get('active_data_sim_operator_numeric')!r} != "
            f"PROFILE.expected_operator_numeric="
            f"{profile.get('expected_operator_numeric')!r}"
        )
    want_vpn = (profile.get("phone_vpn") == "on")
    if phone_obs.get("has_transport_vpn") is not want_vpn:
        issues.append(
            f"PROFILE_STATE.phone_observed.has_transport_vpn="
            f"{phone_obs.get('has_transport_vpn')!r} != want {want_vpn}"
        )
    # Semantic pin: emu MUST NOT carry VPN transport (host VPN only).
    if isinstance(emu_obs, dict):
        if emu_obs.get("has_transport_vpn") is True:
            issues.append(
                "PROFILE_STATE.emu_observed.has_transport_vpn=true "
                "— emu MUST NOT carry VPN transport (host VPN only, "
                "audit ROUND-21 P0-3)"
            )
    return issues


# ── Audit ROUND-21 P0-3: cross-file consistency binding ───────────

def check_cross_file_bindings(evidence_dir: str, profile: dict) -> List[str]:
    """`network_profile.json` is the PRIMARY device observation.
    Every summary surface MUST agree with it on the shared facts:

      phone SIM/VPN/data flags     network_profile.phone <-> PROFILE_STATE.phone_observed
      emu HMAC                     network_profile.emu.egress_fingerprint.hmac_fp_hex
                                       == EGRESS_FINGERPRINT.emu_hmac_fp_hex
      emu AF                       network_profile.emu.egress_fingerprint.address_family
                                       == EGRESS_FINGERPRINT.address_family_emu

    Contradiction anywhere -> integrity RED.
    """
    issues: List[str] = []
    np, np_err = _load_json_object(
        os.path.join(evidence_dir, "network_profile.json"),
        "network_profile.json",
    )
    ps, ps_err = _load_json_object(
        os.path.join(evidence_dir, "PROFILE_STATE.json"),
        "PROFILE_STATE.json",
    )
    ef, ef_err = _load_json_object(
        os.path.join(evidence_dir, "EGRESS_FINGERPRINT.json"),
        "EGRESS_FINGERPRINT.json",
    )
    # Missing/malformed inputs are surfaced by their own per-file
    # checks; bail on cross-check silently to avoid double-noise.
    if np_err or not isinstance(np, dict):
        return issues
    phone = np.get("phone") if isinstance(np.get("phone"), dict) else {}
    emu   = np.get("emu")   if isinstance(np.get("emu"),   dict) else {}
    # (A) network_profile.phone <-> PROFILE_STATE.phone_observed
    if isinstance(ps, dict) and isinstance(ps.get("phone_observed"), dict):
        po = ps["phone_observed"]
        _pairs = [
            ("active_data_sim_operator_numeric", "active_data_sim_operator_numeric"),
            ("active_data_subscription_id",       "active_data_subscription_id"),
            ("wifi_enabled",                      "wifi_enabled"),
            ("mobile_data_enabled",               "mobile_data_enabled"),
            ("has_transport_vpn",                 "has_transport_vpn"),
        ]
        for np_k, ps_k in _pairs:
            if np_k in phone and ps_k in po and phone[np_k] != po[ps_k]:
                issues.append(
                    f"cross-file mismatch: network_profile.phone.{np_k}="
                    f"{phone[np_k]!r} disagrees with "
                    f"PROFILE_STATE.phone_observed.{ps_k}={po[ps_k]!r} "
                    f"(audit ROUND-21 P0-3)"
                )
    # (B) network_profile.emu <-> PROFILE_STATE.emu_observed
    if isinstance(ps, dict) and isinstance(ps.get("emu_observed"), dict):
        eo = ps["emu_observed"]
        for k in ("has_transport_vpn", "active_network_kind"):
            if k in emu and k in eo and emu[k] != eo[k]:
                issues.append(
                    f"cross-file mismatch: network_profile.emu.{k}="
                    f"{emu[k]!r} disagrees with "
                    f"PROFILE_STATE.emu_observed.{k}={eo[k]!r} "
                    f"(audit ROUND-21 P0-3)"
                )
    # (C) network_profile.emu.egress_fingerprint <-> EGRESS_FINGERPRINT.json
    egf = emu.get("egress_fingerprint") if isinstance(emu.get("egress_fingerprint"), dict) else None
    if egf is not None and isinstance(ef, dict) and not ef_err:
        if egf.get("hmac_fp_hex") != ef.get("emu_hmac_fp_hex"):
            issues.append(
                f"cross-file mismatch: "
                f"network_profile.emu.egress_fingerprint.hmac_fp_hex="
                f"{egf.get('hmac_fp_hex')!r} disagrees with "
                f"EGRESS_FINGERPRINT.emu_hmac_fp_hex="
                f"{ef.get('emu_hmac_fp_hex')!r} (audit ROUND-21 P0-3)"
            )
        if egf.get("address_family") != ef.get("address_family_emu"):
            issues.append(
                f"cross-file mismatch: "
                f"network_profile.emu.egress_fingerprint.address_family="
                f"{egf.get('address_family')!r} disagrees with "
                f"EGRESS_FINGERPRINT.address_family_emu="
                f"{ef.get('address_family_emu')!r} (audit ROUND-21 P0-3)"
            )
        # Audit ROUND-24 CONTINUATION G: baseline temporal identity.
        # The three baseline surfaces (network_profile.emu.egress,
        # EGRESS_FINGERPRINT, and by extension the emu-side
        # `network_profile.emu.at_wall_ms` — checked by
        # check_network_profile_evidence parent/nested rule) MUST
        # carry the exact same `at_wall_ms` because they are
        # persisted views of the ONE preflight snapshot. Coincidental
        # equality is not enough — the runner must stamp them from
        # one wall-clock read.
        egf_ts = egf.get("at_wall_ms")
        ef_ts = ef.get("at_wall_ms")
        if isinstance(egf_ts, int) and isinstance(ef_ts, int) and egf_ts != ef_ts:
            issues.append(
                f"cross-file mismatch: "
                f"network_profile.emu.egress_fingerprint.at_wall_ms="
                f"{egf_ts!r} disagrees with "
                f"EGRESS_FINGERPRINT.at_wall_ms={ef_ts!r} "
                f"(baseline snapshot must own ONE wall-clock, "
                f"audit ROUND-24 CONTINUATION G)"
            )
    # (D) network_profile.phone <-> PROFILE
    if isinstance(profile, dict):
        want_op = profile.get("expected_operator_numeric")
        got_op  = phone.get("active_data_sim_operator_numeric")
        if want_op is not None and got_op is not None and got_op != want_op:
            issues.append(
                f"cross-file mismatch: "
                f"network_profile.phone.active_data_sim_operator_numeric="
                f"{got_op!r} != PROFILE.expected_operator_numeric="
                f"{want_op!r} (audit ROUND-21 P0-3)"
            )
        want_vpn = (profile.get("phone_vpn") == "on")
        got_vpn  = phone.get("has_transport_vpn")
        if _isbool(got_vpn) and got_vpn is not want_vpn:
            issues.append(
                f"cross-file mismatch: "
                f"network_profile.phone.has_transport_vpn={got_vpn!r} "
                f"!= PROFILE.phone_vpn={profile.get('phone_vpn')!r} "
                f"(audit ROUND-21 P0-3)"
            )
    return issues


def check_sha256sums(evidence_dir: str) -> List[str]:
    """Audit ROUND-10 P0-4 hardened per audit ROUND-11 P1-#7.

    Every SHA line must match the actual file. Additionally, EVERY
    file that verify_profile_dir load-bears on MUST have a line —
    otherwise a truncated SHA256SUMS.txt (missing e.g. smoke.log)
    silently exempts key evidence from the hash gate. The audit
    minimum: PROFILE, PROFILE_STATE, network_profile,
    EGRESS_FINGERPRINT, signed_prekey_readiness, checkpoint_log,
    smoke.log, smoke_verdict, matrix_completion, matrix_verdict.
    """
    p = os.path.join(evidence_dir, "SHA256SUMS.txt")
    if not os.path.isfile(p):
        return ["SHA256SUMS.txt missing"]
    import hashlib
    issues = []
    covered_paths: set[str] = set()
    with open(p, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            parts = line.split(None, 1)
            if len(parts) != 2:
                issues.append(f"SHA256SUMS.txt:{lineno} malformed: {line!r}")
                continue
            want_sha, path_field = parts[0], parts[1].lstrip("*")
            # Audit ROUND-13 P1: paths in SHA256SUMS.txt MUST resolve
            # inside the evidence directory. Absolute paths, `..`
            # segments, and symlinks that escape the tree let an
            # attacker "prove" arbitrary files (architect: hashing
            # /etc/hosts kept the profile GREEN). Reject them.
            # Cross-platform absolute check: Unix `/etc/hosts` is
            # absolute on Unix but Python's `os.path.isabs` on Windows
            # returns False for it. Check BOTH conventions so a manifest
            # with `/etc/hosts` fails on Windows too.
            if os.path.isabs(path_field) or path_field.startswith(("/", "\\")):
                issues.append(f"SHA256SUMS.txt:{lineno} rejects absolute path: {path_field!r}")
                continue
            if any(seg == ".." for seg in path_field.replace("\\", "/").split("/")):
                issues.append(f"SHA256SUMS.txt:{lineno} rejects `..` traversal: {path_field!r}")
                continue
            fp = os.path.join(evidence_dir, path_field)
            # Realpath containment: resolved target MUST live under
            # the evidence root — catches symlinks pointing outside.
            try:
                real_ev = os.path.realpath(evidence_dir)
                real_fp = os.path.realpath(fp)
            except OSError as e:
                issues.append(f"SHA256SUMS.txt:{lineno} realpath failed for {path_field}: {e}")
                continue
            _prefix = real_ev.rstrip(os.sep) + os.sep
            if real_fp != real_ev and not real_fp.startswith(_prefix):
                issues.append(f"SHA256SUMS.txt:{lineno} resolves outside evidence dir: {path_field!r} -> {real_fp!r}")
                continue
            if not os.path.isfile(fp):
                issues.append(f"SHA256SUMS.txt:{lineno} references missing file: {path_field}")
                continue
            h = hashlib.sha256()
            with open(fp, "rb") as g:
                for chunk in iter(lambda: g.read(65536), b""):
                    h.update(chunk)
            got = h.hexdigest()
            if got.lower() != want_sha.lower():
                issues.append(f"SHA256SUMS.txt:{lineno} hash mismatch for {path_field}: expected {want_sha} got {got}")
            # Normalise for the covered-set (strip leading ./)
            covered_paths.add(path_field.lstrip("./"))
    # Audit ROUND-12 P1-#6: recursive coverage — EVERY file under the
    # evidence dir (except SHA256SUMS.txt itself) must have a hash
    # line. The prior version listed ten top-level filenames only; a
    # missing line for `matrix_cells/<cell>/capture.log` or
    # `matrix_cells/<cell>/cell_verdict.json` slipped through undetected.
    # Walk the tree and require a hash line for every file found.
    for root, _dirs, files in os.walk(evidence_dir):
        for f in files:
            if f == "SHA256SUMS.txt":
                continue
            fp = os.path.join(root, f)
            rel = os.path.relpath(fp, evidence_dir).replace(os.sep, "/")
            if rel not in covered_paths:
                issues.append(
                    f"SHA256SUMS.txt has no line for {rel!r} "
                    f"(audit ROUND-12 P1-#6: every evidence file must be hashed, "
                    f"including matrix_cells/**)"
                )
    return issues


def check_completion_envelope_counts(evidence_dir: str,
                                     mc: dict,
                                     cells_expected_to_run: Optional[int],
                                     matrix_aborted: bool = False,
                                     observed_dispatched_total: Optional[int] = None,
                                     observed_triplet_total: Optional[int] = None) -> List[str]:
    """Audit ROUND-11 P1-#7: matrix_completion counts must be truthful.

    - `envelopes_sent=0` combined with a completed non-aborted run is
      a category error: at least one CID must have been dispatched
      per non-blocked cell.
    - `envelopes_recipient_triplet_complete=0` is the same class of
      violation on the recipient side.
    - Both must be consistent with cells_expected_to_run * 5.
    """
    issues: List[str] = []
    if cells_expected_to_run is None:
        return issues
    want_total = cells_expected_to_run * CIDS_PER_CELL
    for key in ("envelopes_sent", "envelopes_recipient_triplet_complete"):
        if key not in mc:
            issues.append(f"matrix_completion.json missing key {key!r}")
            continue
        v = mc[key]
        if not isinstance(v, int):
            issues.append(f"matrix_completion.{key}={v!r} not an int")
            continue
        if matrix_aborted:
            # ── Audit ROUND-29.5 P0 ────────────────────────────────
            # R29.4 only rejected totals that EXCEEDED the evidence, so
            # `envelopes_sent=0` beside ten actually-observed dispatches
            # passed silently — under-reporting is just as untruthful as
            # over-reporting. An aborted profile must state EXACTLY what
            # the evidence shows.
            _observed = (observed_dispatched_total if key == "envelopes_sent"
                         else observed_triplet_total)
            if v > want_total:
                issues.append(
                    f"matrix_completion.{key}={v} exceeds the completed-run "
                    f"maximum cells_expected_to_run*5={want_total} "
                    f"(aborted profile — audit ROUND-29.4 P1)"
                )
            elif _observed is not None and v != _observed:
                issues.append(
                    f"matrix_completion.{key}={v} != {_observed} actually "
                    f"observed on disk (aborted profile must report exactly "
                    f"what happened — audit ROUND-29.5 P0)"
                )
        elif v == 0:
            issues.append(
                f"matrix_completion.{key}=0 in a completed non-aborted run "
                f"(audit ROUND-11 P1-#7: 0 is only valid when the profile aborted)"
            )
        elif v != want_total:
            issues.append(
                f"matrix_completion.{key}={v} != cells_expected_to_run*5={want_total} "
                f"(truthful count mismatch)"
            )
    return issues


# ── audit ROUND-17 P0-1: cell + matrix verdict CONTENT validation ─
#
# ROUND-16 only checked that the JSON was well-formed; the values
# were not cross-referenced against the parsed events. Architect
# proved that an empty `cell_verdict.json` object AND a completely
# contradicting `matrix_verdict.json` (integrity=RED, delivered=0,
# unresolved=40, cells=[]) both stayed GREEN.

_ALLOWED_CELL_VERDICTS = frozenset({"Delivered", "Unresolved", "BLOCKED", "PENDING"})
_ALLOWED_MATRIX_INTEGRITY = frozenset({"GREEN", "RED"})
_ALLOWED_MATRIX_OUTCOME = frozenset({"GREEN", "RED", "NOT_EVALUABLE"})


def validate_cell_verdict_content(cv: dict, cell_id: str, blocked: bool,
                                  dispatched_cids_count: int,
                                  matrix_aborted: bool = False) -> List[str]:
    """Audit ROUND-17 P0-1: enforce cell_verdict.json closed schema
    AND cross-check its values against the parsed CID counts.

    - `cell_id` MUST equal the enclosing cell's canonical id
    - `blocked` MUST match the cell's blocked flag
    - `envelopes_dispatched` MUST equal the observed dispatched-CID
      count (5 for non-blocked, 0 for blocked)
    - `delivered_count` <= envelopes_dispatched
    - `unresolved_count` = envelopes_dispatched - delivered_count
    - `verdict` MUST be in the enum AND consistent with counts
    """
    # Audit ROUND-23 P1: structural pass via schema-registry.
    issues: List[str] = list(check_registered_surface("cell_verdict.json", cv))
    if any("missing required key" in i or "unknown key" in i for i in issues):
        return issues
    # Value cross-checks.
    if cv["cell_id"] != cell_id:
        issues.append(
            f"cell {cell_id}: cell_verdict.cell_id={cv['cell_id']!r} != enclosing cell {cell_id!r}"
        )
    if cv["blocked"] != blocked:
        issues.append(
            f"cell {cell_id}: cell_verdict.blocked={cv['blocked']} != expected {blocked}"
        )
    if not isinstance(cv.get("verdict"), str):
        return issues  # schema check already reported the wrong-type
    if cv["verdict"] not in _ALLOWED_CELL_VERDICTS:
        issues.append(
            f"cell {cell_id}: cell_verdict.verdict={cv['verdict']!r} not in {sorted(_ALLOWED_CELL_VERDICTS)}"
        )
    ed = cv["envelopes_dispatched"]
    dc = cv["delivered_count"]
    uc = cv["unresolved_count"]
    # Audit ROUND-22 P0-1: bail if any count is not a strict int
    # (schema check already emitted the type/null issue).
    if type(ed) is not int or type(dc) is not int or type(uc) is not int:
        return issues
    if blocked:
        if ed != 0 or dc != 0 or uc != 0:
            issues.append(
                f"cell {cell_id}: blocked cell must have envelopes_dispatched=0 "
                f"delivered_count=0 unresolved_count=0, got ed={ed} dc={dc} uc={uc}"
            )
        # Audit ROUND-18 P0-1: blocked cells MUST have `verdict=BLOCKED`
        # only. The ROUND-17 tolerance for `PENDING` masked producer
        # bugs (the six-cell mode used to leave PENDING and the
        # profile stayed GREEN).
        if cv["verdict"] != "BLOCKED":
            issues.append(
                f"cell {cell_id}: blocked cell verdict must be BLOCKED, got {cv['verdict']!r}"
            )
    else:
        if ed != dispatched_cids_count:
            issues.append(
                f"cell {cell_id}: cell_verdict.envelopes_dispatched={ed} != "
                f"observed dispatched CID count={dispatched_cids_count}"
            )
        if dc > ed:
            issues.append(
                f"cell {cell_id}: delivered_count={dc} > envelopes_dispatched={ed}"
            )
        if uc != max(0, ed - dc):
            issues.append(
                f"cell {cell_id}: unresolved_count={uc} != max(0, {ed}-{dc})={max(0, ed-dc)}"
            )
        # Audit ROUND-29.3 P1-1: a cell the matrix never reached keeps
        # its `PENDING` baseline with zero counts, so the eight-row
        # manifest stays truthful. That is legitimate ONLY under a
        # non-null matrix abort — on a normal run `PENDING` is still a
        # producer bug (ROUND-18 P0-1), which is what the else-branch
        # below keeps enforcing.
        if cv["verdict"] == "PENDING" and matrix_aborted:
            if ed != 0 or dc != 0 or uc != 0:
                issues.append(
                    f"cell {cell_id}: never-attempted PENDING cell must have "
                    f"envelopes_dispatched=delivered_count=unresolved_count=0, "
                    f"got ed={ed} dc={dc} uc={uc} (audit ROUND-29.3 P1-1)"
                )
        else:
            # verdict Delivered iff full 5 dispatched + 5 delivered.
            want = "Delivered" if (ed >= 5 and dc >= 5) else "Unresolved"
            if cv["verdict"] != want:
                issues.append(
                    f"cell {cell_id}: cell_verdict.verdict={cv['verdict']!r} inconsistent "
                    f"with counts (ed={ed} dc={dc}); want {want!r}"
                )

    # ── Audit ROUND-29.2 P1-2: per-cell abort ─────────────────────
    # `abort_reason` on a cell means the cell stopped BEFORE dispatch,
    # so it must describe a zero-dispatch, non-blocked, Unresolved
    # cell. Without these rules the field could be attached to a cell
    # that actually sent envelopes, which would be a new way to write
    # false evidence.
    _cell_abort = cv.get("abort_reason")
    # ── Audit ROUND-30.14: a cell may only claim a cell-scoped reason
    # Second of three independent layers. The schema enum rejects this
    # shape structurally; this one is here so that a widened enum, a
    # hand-written verdict or a future producer bug still meets a
    # finding on the way through content validation.
    if run_level_abort_in_cell(_cell_abort):
        issues.append(
            f"cell {cell_id}: abort_reason={_cell_abort!r} is a RUN-level "
            f"reason and no cell records it - a cell may only claim "
            f"{sorted(CELL_SCOPED_ABORT_REASONS)} "
            f"(audit ROUND-30.14)"
        )
    # ── Audit ROUND-30.11 ─────────────────────────────────────────
    # `pin_clear_unconfirmed` is the one reason that describes a cell
    # which DID dispatch: its envelopes completed and only the
    # post-cell clear failed. Applying the zero-count rule below to it
    # would demand that a delivered cell report nothing delivered —
    # the false evidence this round exists to remove. It is checked
    # instead by `check_cell_abort_branches`, which requires the
    # OPPOSITE: real counters and the ordinary verdict. Every other
    # reason keeps this rule with its full force.
    if _cell_abort is not None and _cell_abort != "pin_clear_unconfirmed":
        if blocked:
            issues.append(
                f"cell {cell_id}: abort_reason={_cell_abort!r} is invalid on a "
                f"blocked cell (blocked cells never attempt dispatch — "
                f"audit ROUND-29.2 P1-2)"
            )
        if ed != 0 or dc != 0 or uc != 0:
            issues.append(
                f"cell {cell_id}: abort_reason={_cell_abort!r} requires "
                f"envelopes_dispatched=delivered_count=unresolved_count=0, "
                f"got ed={ed} dc={dc} uc={uc} (audit ROUND-29.2 P1-2)"
            )
        if cv["verdict"] != "Unresolved":
            issues.append(
                f"cell {cell_id}: abort_reason={_cell_abort!r} requires "
                f"verdict='Unresolved', got {cv['verdict']!r} "
                f"(audit ROUND-29.2 P1-2)"
            )
    return issues


def check_matrix_verdict_content(evidence_dir: str, mc: dict,
                                 cell_counts: Dict[str, Tuple[bool, int, int]],
                                 cells_expected_to_run: int,
                                 verifier_integrity_ok: bool,
                                 aborted_cell_ids: Optional[set] = None) -> List[str]:
    """Audit ROUND-17 P0-1: enforce matrix_verdict.json closed schema
    AND cross-check every value against matrix_completion + summed
    cell verdicts + verifier's own outcome.

    cell_counts maps cell_id → (blocked, delivered_count, unresolved_count)
    reflecting what the verifier actually observed.
    """
    mv, load_issues = _load_json_object(
        os.path.join(evidence_dir, "matrix_verdict.json"),
        "matrix_verdict.json",
    )
    if load_issues:
        return load_issues
    # Audit ROUND-22 P0-1: top-level structural pass via schema.
    issues: List[str] = list(check_registered_surface("matrix_verdict.json/top", mv))
    if any("missing required key" in i or "unknown key" in i for i in issues):
        return issues
    # Audit ROUND-23 P1: per-row structural pass via schema-registry.
    seen_cells: List[str] = []
    row_by_cid: Dict[str, dict] = {}
    if not isinstance(mv.get("cells"), list):
        return issues
    for i, entry in enumerate(mv["cells"]):
        if not isinstance(entry, dict):
            issues.append(f"matrix_verdict.cells[{i}] not an object")
            continue
        issues.extend(check_registered_surface(
            "matrix_verdict.json/row", entry,
        ))
        cid = entry.get("cell_id")
        if not isinstance(cid, str) or not cid:
            continue
        if cid in row_by_cid:
            issues.append(
                f"matrix_verdict.cells has duplicate cell_id {cid!r} "
                f"(audit ROUND-18 P0-3)"
            )
        else:
            row_by_cid[cid] = entry
            seen_cells.append(cid)
    want_cells = set(canonical_cell_ids())
    seen_set = set(seen_cells)
    missing_cells = want_cells - seen_set
    extra_cells = seen_set - want_cells
    if missing_cells:
        issues.append(f"matrix_verdict.cells missing canonical: {sorted(missing_cells)}")
    if extra_cells:
        issues.append(f"matrix_verdict.cells has non-canonical: {sorted(extra_cells)}")
    if len(mv["cells"]) != len(want_cells):
        issues.append(
            f"matrix_verdict.cells length={len(mv['cells'])} "
            f"(want exactly {len(want_cells)} canonical rows, no duplicates)"
        )
    # Audit ROUND-18 P0-3: each row's blocked+verdict MUST equal the
    # verifier's own per-cell derivation from observed events. This
    # rejects the mutation "flip all rows to Unresolved/blocked=true
    # after regenerating checksums".
    for cid in sorted(want_cells & seen_set):
        row = row_by_cid[cid]
        counts = cell_counts.get(cid)
        if counts is None:
            issues.append(
                f"matrix_verdict.cells[{cid!r}]: no verifier-observed counts "
                f"(cell dir missing from evidence?)"
            )
            continue
        cblocked, ed, uc = counts
        if row.get("blocked") is not cblocked:
            issues.append(
                f"matrix_verdict.cells[{cid!r}].blocked={row.get('blocked')!r} "
                f"disagrees with observed blocked={cblocked!r}"
            )
        if cblocked:
            want_row_verdict = "BLOCKED"
        elif ed >= 5 and uc == 0:
            # Matches producer casing at lib/wss3-live.sh:827.
            want_row_verdict = "Delivered"
        elif ed == 0 and uc == 0:
            # Audit ROUND-29.4 P1: a zero-count row is `PENDING` when
            # the matrix never reached it, but the cell that ACTUALLY
            # aborted carries its own bounded `abort_reason` and the
            # producer writes `Unresolved` there (audit ROUND-29.2
            # P1-2). Deriving `PENDING` for it reported a defect that
            # was really the abort being recorded correctly.
            want_row_verdict = (
                "Unresolved"
                if aborted_cell_ids and cid in aborted_cell_ids
                else "PENDING"
            )
        else:
            want_row_verdict = "Unresolved"
        if row.get("verdict") != want_row_verdict:
            issues.append(
                f"matrix_verdict.cells[{cid!r}].verdict={row.get('verdict')!r} "
                f"disagrees with observed derived {want_row_verdict!r} "
                f"(ed={ed} dc={uc} blocked={cblocked})"
            )
    # Sum cross-check: matrix_verdict counts MUST equal per-cell sums.
    sum_delivered = sum(dc for _, dc, _ in cell_counts.values())
    sum_unresolved = sum(uc for _, _, uc in cell_counts.values())
    if mv["delivered_count"] != sum_delivered:
        issues.append(
            f"matrix_verdict.delivered_count={mv['delivered_count']} != "
            f"sum of cell delivered_counts={sum_delivered}"
        )
    if mv["unresolved_count"] != sum_unresolved:
        issues.append(
            f"matrix_verdict.unresolved_count={mv['unresolved_count']} != "
            f"sum of cell unresolved_counts={sum_unresolved}"
        )
    # matrix_completion cross-check: envelopes_recipient_triplet_complete
    # MUST equal delivered sum. matrix_verdict.evidence_integrity MUST
    # reflect the abort_reason state.
    mc_delivered = mc.get("envelopes_recipient_triplet_complete", -1)
    if isinstance(mc_delivered, int) and mc_delivered != sum_delivered:
        issues.append(
            f"matrix_verdict.delivered_count={mv['delivered_count']} != "
            f"matrix_completion.envelopes_recipient_triplet_complete={mc_delivered}"
        )
    # Verifier's own outcome vs matrix_verdict claim: if the verifier
    # found integrity RED then matrix_verdict claiming GREEN is a
    # direct contradiction.
    if verifier_integrity_ok and mv["evidence_integrity"] == "RED":
        issues.append(
            "matrix_verdict.evidence_integrity=RED but verifier found no integrity issues"
        )
    if not verifier_integrity_ok and mv["evidence_integrity"] == "GREEN":
        issues.append(
            "matrix_verdict.evidence_integrity=GREEN but verifier found integrity issues "
            "(contradiction, audit ROUND-17 P0-1)"
        )
    # Audit ROUND-18 P0-3: product_outcome MUST be recomputed from the
    # verifier's own state, not accepted as a claim. Rules:
    #   integrity RED  → NOT_EVALUABLE
    #   unresolved > 0 → RED
    #   otherwise      → GREEN
    if verifier_integrity_ok:
        want_outcome = "RED" if sum_unresolved > 0 else "GREEN"
    else:
        want_outcome = "NOT_EVALUABLE"
    if mv["product_outcome"] != want_outcome:
        issues.append(
            f"matrix_verdict.product_outcome={mv['product_outcome']!r} "
            f"disagrees with recomputed {want_outcome!r} "
            f"(integrity_ok={verifier_integrity_ok} unresolved_sum={sum_unresolved})"
        )
    return issues


# ── per-profile entry point (hardened per audit P0-2)

def verify_profile_dir(evidence_dir: str) -> ProfileReport:
    dname = os.path.basename(evidence_dir)
    m = EVIDENCE_DIR_RE.match(dname)
    if not m:
        return ProfileReport(
            profile_id=dname, integrity_ok=False, product_outcome="NOT_EVALUABLE",
            integrity_issues=[f"evidence dir name {dname!r} does not match canonical shape"],
        )
    profile_id = m.group("pid")
    rep = ProfileReport(profile_id=profile_id, integrity_ok=True, product_outcome="RED")

    prof_path = os.path.join(evidence_dir, "PROFILE.json")
    if not os.path.isfile(prof_path):
        rep.integrity_ok = False
        rep.integrity_issues.append("PROFILE.json missing")
        rep.product_outcome = "NOT_EVALUABLE"
        return rep
    try:
        with open(prof_path, encoding="utf-8") as f:
            profile = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        rep.integrity_ok = False
        rep.integrity_issues.append(f"PROFILE.json malformed: {e}")
        rep.product_outcome = "NOT_EVALUABLE"
        return rep
    ok, issues = validate_profile_json(profile)
    if not ok:
        rep.integrity_ok = False
        rep.integrity_issues.extend(issues)
        rep.product_outcome = "NOT_EVALUABLE"
        return rep
    rep.arm_token = profile.get("arm_token")

    # ── Audit ROUND-30.18: the smoke-only terminal profile ────────
    #
    # A RED smoke STOPS the profile: `full` refuses to run, so the
    # producer never writes `matrix_completion.json`. Treating its
    # absence as unjudgeable meant the verifier returned NOT_EVALUABLE
    # for exactly the runs R30.17 existed to describe - the state it
    # claimed to make expressible could not arise from any real run, and
    # its own fixtures had to build a fully successful matrix and swap
    # the smoke inside it, a shape no producer emits.
    #
    # A profile with no matrix is judgeable when, and only when, the
    # smoke itself explains why the matrix is absent: a RED smoke whose
    # evidence is intact and whose failure is accounted for by the
    # attempt records. That is a complete, truthful profile whose
    # product failed.
    #
    # Everything else keeps the old answer. A missing or malformed
    # smoke_verdict, a declared failure nothing accounts for, or a GREEN
    # smoke with no matrix (the matrix should have run and did not) all
    # remain NOT_EVALUABLE. The full-matrix rules below are untouched.
    mc_path = os.path.join(evidence_dir, "matrix_completion.json")
    if not os.path.isfile(mc_path):
        # ── Audit ROUND-30.19: the common audits run BEFORE the verdict ─
        #
        # R30.18 returned True/RED from this branch without ever
        # reaching the SHA256SUMS verification, the required-file
        # check, the privacy denylist or the closed inventory — the
        # same early-return class R29.3 closed for the aborted matrix.
        # A terminal profile could be accepted as intact evidence after
        # manifest removal, file tampering or a privacy leak, because
        # nothing between here and the verdict ever looked.
        #
        # The carve-out is stage-aware for MATRIX rules only. Every
        # audit that does not need a matrix to exist runs here,
        # unchanged and in full, and any finding refuses the profile
        # before a verdict is even considered. The smoke's own event
        # layer (parsing, chains, binding, the attempt audit, the
        # overflow sweep) is `load_smoke_verdict` below — the same
        # authority the matrix path uses.
        _t_issues: List[str] = []
        # ── Audit ROUND-30.20: no matrix-phase artefact may be here ──
        #
        # The branch is chosen on the ABSENCE of
        # `matrix_completion.json` alone, and the closed inventory
        # permits `matrix_verdict.json`, `matrix_cells/` and
        # `checkpoint_log.json` — none of which this path validated.
        # An invalid `matrix_verdict.json` with a rehashed manifest was
        # therefore accepted as a sound terminal profile.
        #
        # Forbidding them outright is the honest rule, and it is the
        # producer's own shape: `wss3_live_full` writes all three, so a
        # profile the smoke stopped has none. It also fixes a case
        # nobody had named — a `full` that DIED mid-matrix leaves
        # checkpoint_log and cells behind without a completion record,
        # and the old branch read that half-run matrix as a stopped
        # smoke and judged it. It is not judgeable, and now it says so.
        for _mp in _MATRIX_PHASE_FILES:
            if os.path.exists(os.path.join(evidence_dir, _mp)):
                _t_issues.append(
                    f"matrix_completion.json is absent but {_mp} is present "
                    f"— a profile stopped at the smoke has no matrix phase, "
                    f"and a matrix that left artefacts without a completion "
                    f"record did not finish (audit ROUND-30.20)")
        if os.path.exists(os.path.join(evidence_dir, "matrix_cells")):
            _t_issues.append(
                "matrix_completion.json is absent but matrix_cells/ is "
                "present — a profile stopped at the smoke has no matrix "
                "phase (audit ROUND-30.20)")
        _t_issues += check_required_evidence_files(evidence_dir,
                                                   terminal=True)
        _t_issues += check_egress_fingerprint_evidence(evidence_dir)
        _t_issues += check_network_profile_evidence(evidence_dir,
                                                    profile=profile)
        _t_issues += check_signed_prekey_readiness(evidence_dir)
        # checkpoint_log.json is matrix-phase and is now FORBIDDEN
        # here (above), not conditionally schema-checked: R30.19
        # validated it when present, which accepted its presence as
        # legitimate. A terminal profile has no matrix phase at all.
        _t_issues += check_profile_state(evidence_dir, profile)
        _t_issues += check_cross_file_bindings(evidence_dir, profile)
        _t_issues += check_sha256sums(evidence_dir)
        _t_issues += check_privacy_denylist(evidence_dir)
        _t_issues += check_evidence_inventory(evidence_dir)
        if _t_issues:
            rep.integrity_ok = False
            rep.integrity_issues.extend(_t_issues)
            rep.integrity_issues.append(
                "matrix_completion.json missing and the terminal evidence "
                "fails the common audits — a stopped profile is judgeable "
                "only when its evidence is intact (audit ROUND-30.19)")
            rep.product_outcome = "NOT_EVALUABLE"
            return rep
        # `profile_run_id` is derived further down; take it from the
        # profile we have already parsed rather than reordering the
        # function around a diagnostic.
        sv0, sv0_issues, sv0_product = load_smoke_verdict(
            evidence_dir, profile_run_id=profile.get("run_id"),
            profile=profile, with_product=True)
        _smoke_red = bool(sv0) and (sv0.get("p2e") != "OK" or sv0.get("e2p") != "OK")
        if sv0 is None or sv0_issues or not _smoke_red or not sv0_product:
            rep.integrity_ok = False
            if sv0 is None:
                rep.integrity_issues.append(
                    "matrix_completion.json missing and smoke_verdict.json is "
                    "unreadable — nothing explains why the matrix is absent "
                    "(audit ROUND-30.18)")
            elif sv0_issues:
                rep.integrity_issues.extend(sv0_issues)
                rep.integrity_issues.append(
                    "matrix_completion.json missing and the smoke evidence is "
                    "not trustworthy (audit ROUND-30.18)")
            elif not _smoke_red:
                rep.integrity_issues.append(
                    "matrix_completion.json missing although the smoke passed "
                    "— a GREEN smoke advances to the matrix (§4.4 empty "
                    "sentinel forbidden)")
            else:
                rep.integrity_issues.append(
                    "matrix_completion.json missing and the smoke declares a "
                    "failure no attempt record accounts for "
                    "(audit ROUND-30.18)")
            rep.product_outcome = "NOT_EVALUABLE"
            return rep
        # A truthful RED smoke that stopped the profile before the matrix.
        rep.product_issues.extend(sv0_product)
        rep.cells_ran = 0
        rep.product_outcome = "RED"
        return rep
    if os.path.getsize(mc_path) == 0:
        rep.integrity_ok = False
        rep.integrity_issues.append("matrix_completion.json is an empty sentinel (§4.4 forbidden)")
        rep.product_outcome = "NOT_EVALUABLE"
        return rep
    try:
        with open(mc_path, encoding="utf-8") as f:
            mc = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        rep.integrity_ok = False
        rep.integrity_issues.append(f"matrix_completion.json malformed: {e}")
        rep.product_outcome = "NOT_EVALUABLE"
        return rep
    ok, issues = validate_completion_json(mc)
    if not ok:
        rep.integrity_ok = False
        rep.integrity_issues.extend(issues)
        rep.product_outcome = "NOT_EVALUABLE"
        return rep
    rep.cells_declared = mc["cells_declared"]
    rep.cells_expected_to_run = mc["cells_expected_to_run"]
    rep.cells_ran = mc["cells_ran"]
    rep.abort_reason = mc.get("abort_reason")

    # ── Audit ROUND-29.3 P0: aborted evidence is still AUDITED ─────
    # R29.2 returned here, which skipped SHA256SUMS verification, the
    # privacy denylist sweep, the required-file and inventory checks
    # and every matrix-content rule. An aborted profile is exactly the
    # kind of evidence most likely to be malformed — and a denylist
    # hit in it (a raw IP, a payload, a VPN service name) would have
    # gone unreported. The outcome stays RED / NOT_EVALUABLE; the
    # difference is that we now know WHAT ELSE is wrong with it.
    matrix_aborted = rep.abort_reason is not None
    if matrix_aborted:
        rep.integrity_ok = False
        rep.product_outcome = "NOT_EVALUABLE"
        rep.integrity_issues.append(f"aborted: {rep.abort_reason}")

    if rep.cells_ran != rep.cells_expected_to_run:
        rep.integrity_ok = False
        rep.product_outcome = "NOT_EVALUABLE"
        rep.integrity_issues.append(
            f"cells_ran={rep.cells_ran} != cells_expected_to_run={rep.cells_expected_to_run}"
        )
        # An abort is DEFINED as stopping early (contract §4.4 expects
        # `cells_ran < 8` beside a non-null abort_reason), so the count
        # mismatch is a symptom there, not a reason to stop auditing.
        if not matrix_aborted:
            return rep

    # ── audit P0-2 + P0-5: real per-cell coverage with strict
    # run_id/cell_id/emitter/route bindings against PROFILE.json.
    cells_root = os.path.join(evidence_dir, "matrix_cells")
    matrix_cell_ids = canonical_cell_ids()
    all_events: List = []
    total_dispatched_cids = 0
    profile_run_id = profile.get("run_id")
    # Cross-check: matrix_completion.profile_id must equal the pid
    # derived from PROFILE.json (audit P0-5).
    computed_pid = f"{profile['carrier'].lower()}-phone-{profile['phone_vpn']}-host-{profile['host_vpn']}"
    if mc.get("profile_id") != computed_pid:
        rep.integrity_ok = False
        rep.integrity_issues.append(
            f"matrix_completion.profile_id={mc.get('profile_id')!r} "
            f"disagrees with PROFILE-derived pid={computed_pid!r}"
        )
    if mc.get("attempt_id") != profile.get("attempt_id"):
        rep.integrity_ok = False
        rep.integrity_issues.append(
            f"matrix_completion.attempt_id={mc.get('attempt_id')!r} "
            f"disagrees with PROFILE.attempt_id={profile.get('attempt_id')!r}"
        )
    if mc.get("run_id") != profile_run_id:
        rep.integrity_ok = False
        rep.integrity_issues.append(
            f"matrix_completion.run_id={mc.get('run_id')!r} "
            f"disagrees with PROFILE.run_id={profile_run_id!r}"
        )

    def _emitters_for(cell_id: str):
        # p2e: phone sender, emu recipient. e2p: emu sender, phone recipient.
        if ".p2e." in cell_id:
            return "phone", "emulator"
        return "emulator", "phone"

    def _inner_route_for(cell_id: str):
        return "rest" if cell_id in CANONICAL_REST_CELL_IDS else "wss"

    # Audit ROUND-17 P0-1: cell_counts feeds matrix_verdict cross-
    # check after the loop. Map cell_id → (blocked, delivered_count,
    # unresolved_count) from the CV JSON so matrix_verdict sums can
    # be cross-referenced.
    cell_counts: Dict[str, Tuple[bool, int, int]] = {}
    # Audit ROUND-30.12: the cells that actually RAN, i.e. the ones that
    # owe a clear. `check_orphan_clear_events` reports a clear whose
    # parent did not run, and it can only do that if it is told which
    # cells ran — R30.11 handed it every canonical id, which made its
    # own finding unreachable.
    _executed_cell_ids: set = set()
    # Audit ROUND-30.11: the cell verdicts this loop already reads,
    # kept by cell id so the branch rules below are applied in the
    # canonical ORDER rather than to whichever rows happened to
    # reach the end of the loop body. It is filled before any branch
    # that `continue`s, because "every later cell is PENDING" is a
    # rule about all eight rows, not only the ones that ran.
    _cell_verdicts: Dict[str, dict] = {}
    # Audit ROUND-29.4 P1: cells that recorded their OWN bounded
    # abort_reason (the cell the matrix stopped on) are derived as
    # `Unresolved`; cells the matrix never reached stay `PENDING`.
    aborted_cell_ids: set = set()
    for cid in matrix_cell_ids:
        cell_dir = os.path.join(cells_root, cid)
        cv, cv_load_issues = load_cell_verdict(cell_dir)
        if isinstance(cv, dict):
            _cell_verdicts[cid] = cv
        # Audit ROUND-16 P0-3: EVERY canonical cell MUST have a
        # valid closed-schema `cell_verdict.json`. Missing / malformed
        # / non-dict verdicts previously disappeared silently (the
        # loader returned None and the caller only checked `blocked`).
        if cv_load_issues:
            rep.integrity_ok = False
            for _ci in cv_load_issues:
                rep.integrity_issues.append(f"cell {cid}: {_ci}")
        # If REST cell and expected_to_run == 6, verdict may mark it
        # blocked=true; that's legitimate.
        blocked = False
        if cid in CANONICAL_REST_CELL_IDS and rep.cells_expected_to_run == 6:
            # Verdict must confirm blocked=true; otherwise it's an
            # accounting mismatch.
            if cv is None or not cv.get("blocked", False):
                rep.integrity_ok = False
                rep.integrity_issues.append(
                    f"cell {cid}: cells_expected_to_run=6 implies REST BLOCKED, "
                    f"but cell_verdict.blocked != true (cv={cv})"
                )
                continue
            blocked = True
        elif cid in CANONICAL_REST_CELL_IDS and rep.cells_expected_to_run == 8:
            # REST cells expected to run; blocked forbidden.
            if cv is not None and cv.get("blocked", False):
                rep.integrity_ok = False
                rep.integrity_issues.append(
                    f"cell {cid}: cells_expected_to_run=8 but cell_verdict.blocked=true"
                )
                continue
        else:
            # WSS cells: never blocked.
            if cv is not None and cv.get("blocked", False):
                rep.integrity_ok = False
                rep.integrity_issues.append(
                    f"cell {cid}: WSS cell may not be blocked (cell_verdict.blocked=true)"
                )
                continue

        # ── Audit ROUND-29.4 P1: abort-aware cell classification ───
        # After an abort the manifest keeps eight rows, but rows the
        # matrix never executed cannot be audited as if they had run.
        # R29.3 demanded a capture, five CIDs and observed counts from
        # them, which produced six false "never GREEN" findings on an
        # otherwise correct abort. A row counts as NOT EXECUTED only
        # under a real matrix abort, with all three counts zero, and
        # either the untouched `PENDING` baseline or this cell's own
        # bounded `abort_reason`. Everything else is still audited in
        # full — an abort must not become a way to skip scrutiny.
        _cell_abort = cv.get("abort_reason") if isinstance(cv, dict) else None
        _zero_counts = (
            isinstance(cv, dict)
            and cv.get("envelopes_dispatched") == 0
            and cv.get("delivered_count") == 0
            and cv.get("unresolved_count") == 0
        )
        if _cell_abort is not None:
            aborted_cell_ids.add(cid)
        _not_executed = bool(
            matrix_aborted and _zero_counts
            and (
                (isinstance(cv, dict) and cv.get("verdict") == "PENDING")
                or _cell_abort is not None
            )
        )

        if not blocked:
            if not os.path.isdir(cell_dir):
                rep.integrity_ok = False
                rep.integrity_issues.append(f"cell {cid}: matrix_cells/{cid}/ directory missing")
                continue
            cell_events, cell_parse_errors = parse_events_in_dir(cell_dir, device_label=f"cell:{cid}")
            if cell_parse_errors:
                # Audit ROUND-11 P1-#7: propagate parse errors.
                rep.integrity_ok = False
                rep.integrity_issues.extend(cell_parse_errors)
            if _not_executed:
                # Whatever the cell DID emit (health probes, the pin
                # breadcrumbs of a failed confirmation) still joins the
                # global event set, so the allowlist, privacy denylist
                # and CID-uniqueness sweeps keep seeing it.
                all_events.extend(cell_events)
                cell_counts[cid] = (False, 0, 0)
                # ── Audit ROUND-29.5 P0 ────────────────────────────
                # R29.4 `continue`d straight past content and pin
                # validation here, so the cell that ACTUALLY aborted
                # escaped its own checks: flipping its verdict from
                # `Unresolved` to `PENDING`, or planting a forged
                # partial pin with an intruder emitter / wrong role /
                # wrong run / wrong pin, all passed with recomputed
                # SHA. Only the success-shaped COVERAGE requirement is
                # lifted — everything else still applies.
                if isinstance(cv, dict):
                    _nx_issues = validate_cell_verdict_content(
                        cv, cid, blocked=False,
                        dispatched_cids_count=0,
                        matrix_aborted=matrix_aborted,
                    )
                    if _nx_issues:
                        rep.integrity_ok = False
                        rep.integrity_issues.extend(_nx_issues)
                _nx_sender, _nx_recipient = _emitters_for(cid)
                # `require_both=False`: at most one side pinned before
                # the abort, and a never-reached cell pinned neither.
                # Every breadcrumb that IS present is validated in full.
                _nx_pin_issues = check_dual_party_pin(
                    cell_events,
                    cell_id=cid,
                    sender_emitter=_nx_sender,
                    recipient_emitter=_nx_recipient,
                    expected_pin=_inner_route_for(cid),
                    profile_run_id=profile_run_id,
                    source="cell",
                    require_both=False,
                )
                if _nx_pin_issues:
                    rep.integrity_ok = False
                    rep.integrity_issues.extend(_nx_pin_issues)
                continue
            if not cell_events:
                rep.integrity_ok = False
                rep.integrity_issues.append(f"cell {cid}: zero WSS_DIAG events in capture (never GREEN)")
                continue
            all_events.extend(cell_events)
            sender_em, recipient_em = _emitters_for(cid)
            ok, cell_issues = check_cell_coverage(
                cid, cell_dir, blocked=False,
                profile_run_id=profile_run_id,
                expected_sender_emitter=sender_em,
                expected_recipient_emitter=recipient_em,
                expected_inner_route=_inner_route_for(cid),
            )
            if not ok:
                rep.integrity_ok = False
                rep.integrity_issues.extend(cell_issues)
            # ── Audit ROUND-30.11: this cell RAN, so it owes its clear ─
            # Reached only for a non-blocked cell whose capture was
            # parsed, i.e. one that actually dispatched. A blocked cell,
            # a PENDING row and a cell the run never reached are handled
            # on the branches above and owe no clear evidence — the same
            # stage-awareness the pin set already has.
            _cell_clear_missing = set()
            if isinstance(cv, dict) and cv.get("abort_reason") == "pin_clear_unconfirmed":
                _cell_clear_missing = _clear_failed_emitters(
                    cv.get("post_cell_clear_emitter"), sender_em, recipient_em)
            _executed_cell_ids.add(cid)
            _cell_clear_issues = check_clear_breadcrumbs(
                cell_events,
                parent_cell=cid,
                sender_emitter=sender_em,
                recipient_emitter=recipient_em,
                profile_run_id=profile_run_id,
                source="cell",
                expected_missing=_cell_clear_missing,
            )
            if _cell_clear_issues:
                rep.integrity_ok = False
                rep.integrity_issues.extend(_cell_clear_issues)
            total_dispatched_cids += CIDS_PER_CELL
            # Audit ROUND-17 P0-1: cross-check cell_verdict.json
            # CONTENT against parsed events (not just schema).
            if isinstance(cv, dict):
                observed_dispatched = sum(
                    1 for e in cell_events
                    if e.correlation_id and e.event == "diagnostic_send_dispatched"
                )
                # unique CIDs only
                observed_unique = len({
                    e.correlation_id for e in cell_events
                    if e.correlation_id and e.event == "diagnostic_send_dispatched"
                })
                cv_content_issues = validate_cell_verdict_content(
                    cv, cid, blocked=False,
                    dispatched_cids_count=observed_unique,
                    matrix_aborted=matrix_aborted,
                )
                if cv_content_issues:
                    rep.integrity_ok = False
                    rep.integrity_issues.extend(cv_content_issues)
                cell_counts[cid] = (
                    False,
                    int(cv.get("delivered_count", 0)) if isinstance(cv.get("delivered_count"), int) else 0,
                    int(cv.get("unresolved_count", 0)) if isinstance(cv.get("unresolved_count"), int) else 0,
                )
        else:
            # Blocked cell: validate the cell_verdict too (blocked=True
            # branch) so a blocked cell with non-zero counts is caught.
            if isinstance(cv, dict):
                cv_content_issues = validate_cell_verdict_content(
                    cv, cid, blocked=True, dispatched_cids_count=0,
                )
                if cv_content_issues:
                    rep.integrity_ok = False
                    rep.integrity_issues.extend(cv_content_issues)
                cell_counts[cid] = (True, 0, 0)

    # Audit ROUND-13 P0-1: CIDs must be GLOBALLY unique across every
    # cell — the same CID appearing in two cells is a schema violation
    # (architect proved same-CID-in-two-cells stayed GREEN before).
    # Only look at `diagnostic_send_dispatched` events for this check
    # so we index on the primary CID emission, not any downstream event
    # that mentions the CID.
    # ── Audit ROUND-30.11: cell abort branches and orphan clears ──
    # The two abort reasons describe opposite states and are checked
    # apart; the matrix reason must agree with the cell that caused it;
    # every later row is PENDING. Separately, a clear whose parent is
    # not one of the canonical cells belongs to nothing here, so a
    # wrong parent yields BOTH a missing finding where it was owed and
    # an orphan finding where it landed.
    _ordered_cells = [c for c in (_cell_verdicts.get(_c) for _c in canonical_cell_ids())
                      if isinstance(c, dict)]
    if _ordered_cells:
        _branch_issues = check_cell_abort_branches(
            _ordered_cells,
            matrix_abort_reason=(mc.get("abort_reason") if isinstance(mc, dict) else None),
            source="cell",
        )
        if _branch_issues:
            rep.integrity_ok = False
            rep.integrity_issues.extend(_branch_issues)
    _orphan_issues = check_orphan_clear_events(
        all_events,
        known_parents=_executed_cell_ids,
        source="cell",
    )
    if _orphan_issues:
        rep.integrity_ok = False
        rep.integrity_issues.extend(_orphan_issues)

    _cid_to_cells: Dict[str, set] = {}
    for e in all_events:
        if e.correlation_id and e.event == "diagnostic_send_dispatched":
            _cid_to_cells.setdefault(e.correlation_id, set()).add(getattr(e, "cell_id", None))
    for _cid, _cells in _cid_to_cells.items():
        # Remove None (which shouldn't occur after ROUND-12 no-None
        # tolerance) to make the diagnostic cleaner.
        _real_cells = {c for c in _cells if c is not None}
        if len(_real_cells) > 1:
            rep.integrity_ok = False
            rep.integrity_issues.append(
                f"CID {_cid!r} appears in multiple cells: {sorted(_real_cells)} "
                f"— CIDs must be globally unique (audit ROUND-13 P0-1)"
            )

    # smoke_verdict.json shape + VALUES + independent smoke.log rebuild
    # (audit ROUND-10 P0-4 hardened per audit ROUND-11 P0-#4, and
    # ROUND-17 P0-3 for attempt/run binding).
    sv, sv_issues, sv_product = load_smoke_verdict(evidence_dir,
                                                   profile_run_id=profile_run_id,
                                                   profile=profile,
                                                   with_product=True)
    if sv_issues:
        rep.integrity_ok = False
        rep.integrity_issues.extend(sv_issues)
    # Audit ROUND-30.17: a smoke that failed and said so truthfully is a
    # PRODUCT result. It does not make the evidence untrustworthy, so it
    # must not push the profile to NOT_EVALUABLE - that would mean the
    # verifier refuses to evaluate exactly the runs it exists to judge.
    if sv_product:
        rep.product_issues.extend(sv_product)

    # Required §5 evidence files present + non-empty (audit ROUND-10 P0-4).
    ev_missing = check_required_evidence_files(evidence_dir)
    if ev_missing:
        rep.integrity_ok = False
        rep.integrity_issues.extend(ev_missing)

    # Per-file value checks (audit ROUND-10 P0-4 hardened per ROUND-11).
    for issues in (
        check_egress_fingerprint_evidence(evidence_dir),
        check_network_profile_evidence(evidence_dir, profile=profile),
        check_signed_prekey_readiness(evidence_dir),
        check_checkpoint_log(evidence_dir,
                             cells_expected_to_run=rep.cells_expected_to_run,
                             profile=profile,
                             matrix_aborted=matrix_aborted,
                             cells_ran=rep.cells_ran,
                             abort_reason=rep.abort_reason),
        check_profile_state(evidence_dir, profile),
        # Audit ROUND-21 P0-3: cross-file consistency binding.
        check_cross_file_bindings(evidence_dir, profile),
        check_sha256sums(evidence_dir),
        check_completion_envelope_counts(evidence_dir, mc,
                                         cells_expected_to_run=rep.cells_expected_to_run,
                                         matrix_aborted=matrix_aborted,
                                         observed_dispatched_total=total_dispatched_cids,
                                         observed_triplet_total=sum(
                                             dc for _, dc, _ in cell_counts.values())),
        # Audit ROUND-17 P0-1: matrix_verdict content cross-check.
        # Uses cell_counts collected during the per-cell loop above +
        # verifier's integrity state so far.
        check_matrix_verdict_content(
            evidence_dir, mc, cell_counts,
            aborted_cell_ids=aborted_cell_ids,
            cells_expected_to_run=rep.cells_expected_to_run,
            verifier_integrity_ok=rep.integrity_ok,
        ),
    ):
        if issues:
            rep.integrity_ok = False
            rep.integrity_issues.extend(issues)

    # Zero events across the whole profile → NOT_EVALUABLE. NEVER GREEN.
    if not all_events:
        rep.integrity_ok = False
        rep.integrity_issues.append(
            "zero WSS_DIAG events across the whole profile evidence — "
            "verifier will NEVER promote such a bundle to GREEN"
        )
        rep.product_outcome = "NOT_EVALUABLE"
        return rep

    # Unknown / forbidden events → integrity RED (allowlist check).
    unknown_issues = check_events_against_allowlist(all_events)
    if unknown_issues:
        rep.integrity_ok = False
        rep.integrity_issues.extend(unknown_issues)

    # Audit ROUND-13 P0-3: privacy denylist applies to ALL evidence
    # files, not just matrix-cell events. The prior scope let
    # `unresolved_120s_marker` in smoke.log and raw `text=` payloads
    # in cell captures survive as GREEN. Now every .log / .jsonl file
    # under the evidence tree is scanned.
    denylist_issues = check_privacy_denylist(evidence_dir)
    if denylist_issues:
        rep.integrity_ok = False
        rep.integrity_issues.extend(denylist_issues)

    # Audit ROUND-15 P0-3: closed evidence-file inventory. Refuses
    # unknown files (any extension) — belt-and-braces for the
    # denylist-bypass class where an attacker hides a payload in a
    # `.bin` or other unexpected file.
    inventory_issues = check_evidence_inventory(evidence_dir)
    if inventory_issues:
        rep.integrity_ok = False
        rep.integrity_issues.extend(inventory_issues)

    # Audit ROUND-13 P0-4: contract §7 R18 mandates NOT_EVALUABLE
    # for integrity failures (verifier cannot judge product outcome
    # without trustworthy evidence). Only a fully-integrity-clean
    # profile with an intact matrix reaches product_outcome=RED —
    # and that path already returned earlier via the abort_reason
    # or cells_ran mismatch branches. The GREEN vs NOT_EVALUABLE
    # split here is the only correct classification per contract.
    # Audit ROUND-30.17: three outcomes, not two. Untrustworthy evidence
    # is unjudgeable; trustworthy evidence of a failure is a RED product;
    # trustworthy evidence of success is GREEN. Collapsing the middle
    # case into NOT_EVALUABLE is what made a truthful failure report
    # indistinguishable from a broken capture.
    if not rep.integrity_ok:
        rep.product_outcome = "NOT_EVALUABLE"
    elif rep.product_issues:
        rep.product_outcome = "RED"
    else:
        rep.product_outcome = "GREEN"

    return rep


# ── track-level entry point

def verify_evidence_root(evidence_root: str) -> MatrixReport:
    canonical_order = []
    for carrier in ("yota", "tele2"):
        for phone, host in (("off", "on"), ("off", "off"), ("on", "off"), ("on", "on")):
            canonical_order.append(f"{carrier}-phone-{phone}-host-{host}")
    rows = []
    any_retry_lineage = False
    for pid in canonical_order:
        attempts, attempt_load_issues = load_attempts_for_profile(evidence_root, pid)
        if not attempts:
            _missing_row_issues = ["profile missing from evidence root"]
            if attempt_load_issues:
                _missing_row_issues.extend(attempt_load_issues)
            rows.append(ProfileReport(
                profile_id=pid, integrity_ok=False, product_outcome="NOT_EVALUABLE",
                integrity_issues=_missing_row_issues,
                attempts_seen=0,
            ))
            continue
        latest = attempts[-1]
        rep = verify_profile_dir(latest.evidence_dir)
        rep.attempts_seen = len(attempts)
        rep.latest_attempt_id = latest.attempt_id
        # Audit ROUND-16 P0-3: malformed attempt directories that
        # couldn't be loaded MUST force NOT_EVALUABLE for this profile;
        # otherwise a poisoned attempt disappears from lineage
        # accounting.
        if attempt_load_issues:
            rep.integrity_ok = False
            rep.integrity_issues.extend(attempt_load_issues)
            rep.product_outcome = "NOT_EVALUABLE"
        if len(attempts) > 1:
            lineage_ok, lineage_issues = check_retry_lineage(attempts)
            if not lineage_ok:
                rep.integrity_ok = False
                rep.integrity_issues.extend(lineage_issues)
                rep.product_outcome = "NOT_EVALUABLE"
            rep.retry_lineage_present = True
            any_retry_lineage = True
        rows.append(rep)
    return MatrixReport(rows=rows, retry_lineage_present=any_retry_lineage, integrity_issues=[])


# ── retry_reason enforcement (fixture 71)

def validate_explicit_retry_reason(reason: str) -> Tuple[bool, str]:
    if reason == BARE_RESUME_RETRY_REASON:
        return False, (
            f"--reason {reason!r} is reserved for the bare-resume path (§3.4);"
            " explicit retry must use one of "
            f"{sorted(EXPLICIT_RETRY_REASONS)}"
        )
    if reason not in EXPLICIT_RETRY_REASONS:
        return False, (
            f"--reason {reason!r} not in allowed enum "
            f"{sorted(EXPLICIT_RETRY_REASONS)}"
        )
    return True, ""


# ── egress_fingerprint schema discipline (fixture 57)

FORBIDDEN_EGRESS_FIELDS = frozenset({
    "ip_address", "address", "ipv4", "ipv6", "ip",
})


def egress_fingerprint_field_shape(fp: dict) -> Tuple[bool, List[str]]:
    issues = []
    if not isinstance(fp, dict):
        issues.append(f"egress_fingerprint must be object, got {type(fp).__name__}")
        return False, issues
    bad = FORBIDDEN_EGRESS_FIELDS.intersection(fp.keys())
    if bad:
        issues.append(f"egress_fingerprint carries denylisted field names: {sorted(bad)}")
    allowed = {"endpoint", "address_family", "hmac_fp_hex", "at_wall_ms", "http_status", "timeout_ms_used"}
    unknown = set(fp.keys()) - allowed
    if unknown:
        issues.append(f"egress_fingerprint carries unknown fields: {sorted(unknown)}")
    return (len(issues) == 0), issues


# ── legacy checks kept for existing fixture wiring ──

def check_dedup_gate_tolerance(events: List) -> List[str]:
    """Retained for fixture 64 — event-shape check on already-parsed
    WssEvent list. Per cid, exactly one dedup_gate=fresh required.
    Trailing dedup_gate=duplicate/reack tolerated. ≥2 fresh OR zero
    fresh → fail."""
    fresh_counts: Dict[str, int] = {}
    for e in events:
        if not hasattr(e, "event"):
            continue
        if e.event != "recipient_deliver_received":
            continue
        cid = e.correlation_id
        gate = e.dedup_gate
        if not cid:
            continue
        if gate == "fresh":
            fresh_counts[cid] = fresh_counts.get(cid, 0) + 1
    problems = []
    for cid, n in fresh_counts.items():
        if n != 1:
            problems.append(f"cid={cid} dedup_gate=fresh count = {n} (must be exactly 1)")
    return problems
