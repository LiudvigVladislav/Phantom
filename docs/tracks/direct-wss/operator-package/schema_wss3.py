#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
#
# WSS-3 canonical evidence schema — machine-readable mirror of
# WSS3_EVIDENCE_SCHEMA_MATRIX.md.
#
# Consumed by verify_evidence_wss3.py (closed-schema + required +
# type + enum + pinned-constant enforcement) AND by
# tests/test_verifier_wss3_mutations.py (data-driven adversarial
# mutation generation).
#
# Every producer change (Kotlin, shell producer, Python helper)
# MUST update this file in the same commit. Divergence between the
# schema and the code is a P0 audit failure.

from typing import Any, Callable, Dict, List, Optional, Set


# ── shared enums (audit ROUND-23 P0) ──────────────────────────────
#
# Contract §4.6 + §5: matrix_completion / checkpoint entries may
# only abort with one of these reasons. Independent hand-written
# copies in the verifier are forbidden — everything derives from
# this authority.
ABORT_REASON_ENUM: Set[str] = frozenset({
    # Audit ROUND-30.11: a cell whose envelopes all completed but whose
    # post-cell clear did not confirm on both emitters. Distinct from
    # `pin_unconfirmed` on purpose: that one means the cell never ran
    # and its counters are zero, while this one means the cell DID run
    # and its counters are real. The verifier keeps the two apart.
    "pin_clear_unconfirmed",
    "network_profile_drift",
    "host_vpn_unverifiable",
    "host_vpn_other_service_active",
    "mixed_address_family",
    "path_divergence_host_emu",
    "signed_prekey_regressed",
    "pair_lost",
    "apk_sha_drift",
    "operator_interrupt",
    "verifier_schema_violation",
    "carrier_attribution_lost",
    "wifi_active_or_mobile_data_off",
    # Audit ROUND-29.2 P1-2: a full cell whose pin could not be
    # CONFIRMED on both emitters (non-zero broadcast, or no fresh
    # matching `diagnostic_pin_active`). The cell dispatches zero
    # envelopes and the matrix stops — dispatching under an
    # unconfirmed pin is exactly what produced the field's
    # false cell attribution.
    "pin_unconfirmed",
})


# ── Audit ROUND-30.13 ─────────────────────────────────────────────
# The abort reasons that are a claim about ONE cell.
#
# `ABORT_REASON_ENUM` also carries run-level reasons — a checkpoint
# drift, a lost pair, an operator interrupt — which describe the run and
# which no cell records. Only these two say "this cell stopped the
# matrix", so only these two can be corroborated by one, and the
# exactly-one rule in the verifier is scoped to them.
#
# It lived in `verify_evidence_wss3.py` as a local constant, which put a
# schema fact outside the schema authority: the verifier could widen or
# narrow it without the document that defines the enum ever noticing.
# The registry parity fixture now pins that both agree.
#
# Audit ROUND-30.14: declaring it was not the same as USING it. The
# cell field kept the full `ABORT_REASON_ENUM`, so the structural
# pass accepted any run-level reason inside a cell and this set was
# an authority nothing executable consulted. It is now the enum of
# `CELL_VERDICT_SCHEMA["abort_reason"]` itself, which is why it has
# to be defined above that schema rather than below it.
CELL_SCOPED_ABORT_REASONS: Set[str] = frozenset({
    "pin_unconfirmed",
    "pin_clear_unconfirmed",
})
assert CELL_SCOPED_ABORT_REASONS <= ABORT_REASON_ENUM, (
    "a cell-scoped abort reason must be a member of ABORT_REASON_ENUM"
)

# ── canonical pinned constants (source-pinned to real producers) ──
#
# Kotlin: apps/android/src/debug/kotlin/phantom/android/diagnostic/
#         DiagnosticNetworkProfileReporter.kt
NP_SCHEMA_VERSION_PIN = "1"          # .kt:62
NP_ENDPOINT_PIN       = "api4.ipify.org"  # .kt:559
NP_EGRESS_TIMEOUT_MS  = 5000         # .kt:59
NP_AF_ENUM: Set[str]  = frozenset({"AF_INET", "AF_INET6"})

# Per-cell delivered count (contract §3.3: exactly 5 CIDs per cell).
CIDS_PER_CELL = 5

# Field-type sentinels the mutation generator understands.
T_STR   = "str"
T_INT   = "int"       # strict; bool fails
T_BOOL  = "bool"
T_LIST  = "list"
T_DICT  = "dict"
T_NULL  = "null"

# Nullability sentinels.
N_NONE  = "not_nullable"
N_NULL  = "nullable"       # may be None
N_TRI   = "tri_state"      # bool OR None (mobile_data_enabled shape)

# Presence sentinels.
P_REQ  = "required"
P_OPT  = "optional"

# ── schema definitions ────────────────────────────────────────────

# Each entry: {name -> {type, presence, nullability, enum?, pin?,
#                       min?, max?, regex?, cross_file?, privacy?,
#                       from_profile?}}


NETWORK_PROFILE_DEVICE_SCHEMA: Dict[str, dict] = {
    "schema_version": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "pin": NP_SCHEMA_VERSION_PIN,
        "privacy": "pub",
    },
    "at_wall_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "at_monotonic_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "has_transport_vpn": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "from_profile": "phone_vpn_bool",  # phone side only
        "privacy": "pub",
    },
    "active_network_present": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "active_network_kind": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "wifi_enabled": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_TRI,
        "privacy": "pub",
    },
    "wifi_read_error": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NULL,
        "privacy": "pub",
    },
    "mobile_data_enabled": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_TRI,
        "privacy": "pub",
    },
    "active_data_subscription_id": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "active_data_sim_operator_numeric": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "from_profile": "expected_operator_numeric",  # phone side only
        "privacy": "pub",
    },
    "process_uid": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "app_debuggable": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "egress_fingerprint": {
        "type": T_DICT, "presence": P_OPT,  # phone: forbidden, emu: required
        "nullability": N_NONE,
        "privacy": "pub",  # object; nested has op-only hmac
    },
}


NETWORK_PROFILE_EGRESS_SCHEMA: Dict[str, dict] = {
    "endpoint": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "pin": NP_ENDPOINT_PIN,
        "privacy": "pub",
    },
    "address_family": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": NP_AF_ENUM,
        "privacy": "pub",
    },
    "hmac_fp_hex": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NULL,
        "regex": r"^[0-9a-f]{32}$",
        "privacy": "op",
    },
    "at_wall_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "http_status": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NULL,
        "min": 100, "max": 599,
        "privacy": "pub",
    },
    "timeout_ms_used": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "pin": NP_EGRESS_TIMEOUT_MS,
        "privacy": "pub",
    },
}


EGRESS_FINGERPRINT_TOP_SCHEMA: Dict[str, dict] = {
    "host_hmac_fp_hex": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": r"^[0-9a-f]{32}$",
        "privacy": "op",
    },
    "emu_hmac_fp_hex": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": r"^[0-9a-f]{32}$",
        "privacy": "op",
        "cross_file": [("network_profile.emu.egress_fingerprint.hmac_fp_hex", "equal")],
    },
    "equal_host_emu": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "pin": True,  # must be True for GREEN
        "privacy": "pub",
    },
    "address_family_host": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": NP_AF_ENUM,
        "privacy": "pub",
    },
    "address_family_emu": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": NP_AF_ENUM,
        "privacy": "pub",
        "cross_file": [("network_profile.emu.egress_fingerprint.address_family", "equal")],
    },
    "at_wall_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
}


PROFILE_STATE_PHONE_SCHEMA: Dict[str, dict] = {
    "active_data_sim_operator_numeric": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "from_profile": "expected_operator_numeric",
        "cross_file": [("network_profile.phone.active_data_sim_operator_numeric", "equal")],
        "privacy": "pub",
    },
    "active_data_subscription_id": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "cross_file": [("network_profile.phone.active_data_subscription_id", "equal")],
        "privacy": "pub",
    },
    "wifi_enabled": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_TRI,
        "pin": False,  # matrix run REQUIRES Wi-Fi off
        "cross_file": [("network_profile.phone.wifi_enabled", "equal")],
        "privacy": "pub",
    },
    "mobile_data_enabled": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_TRI,
        "pin": True,   # matrix run REQUIRES mobile data on
        "cross_file": [("network_profile.phone.mobile_data_enabled", "equal")],
        "privacy": "pub",
    },
    "has_transport_vpn": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "from_profile": "phone_vpn_bool",
        "cross_file": [("network_profile.phone.has_transport_vpn", "equal")],
        "privacy": "pub",
    },
    "wifi_read_error": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NULL,
        "privacy": "pub",
    },
}


PROFILE_STATE_EMU_SCHEMA: Dict[str, dict] = {
    "active_network_kind": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "has_transport_vpn": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "pin": False,  # host VPN, not emu — emu MUST NOT have VPN transport
        "privacy": "pub",
    },
}


SIGNED_PREKEY_LINE_TOKENS: Dict[str, dict] = {
    "header":            {"type": T_STR,  "pin": "signed_prekey_readiness"},
    "published":         {"type": T_BOOL, "pin": True},
    "signed_prekey_age_days": {"type": T_INT, "min": 0},
    "remaining_opks":    {"type": T_INT, "min": 0},
}


# ── remaining evidence JSON schemas (audit ROUND-22 P0-1) ─────────

# Regex library.
_UUIDV4 = r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
_HEX64  = r"^[0-9a-f]{64}$"
_HEX32  = r"^[0-9a-f]{32}$"


PROFILE_SCHEMA: Dict[str, dict] = {
    "carrier": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": frozenset({"YOTA", "TELE2"}),
        "privacy": "pub",
    },
    "phone_vpn": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": frozenset({"off", "on"}),
        "privacy": "pub",
    },
    "host_vpn": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": frozenset({"off", "on"}),
        "privacy": "pub",
    },
    "expected_operator_numeric": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": r"^\d{5,6}$",
        "privacy": "pub",
    },
    "apk_sha256": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": _HEX64,
        "privacy": "pub",
    },
    "run_id": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "attempt_id": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": _UUIDV4,
        "privacy": "pub",
    },
    "arm_token": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": _UUIDV4,
        "privacy": "op",
    },
    "arm_wall_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "arm_monotonic_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "confirm_string": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "operator_manual_confirmations": {
        "type": T_DICT, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "supersedes_attempt_id": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NULL,
        "regex": _UUIDV4,
        "privacy": "pub",
    },
    "retry_reason": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NULL,
        "privacy": "pub",
    },
}


CHECKPOINT_ENTRY_SCHEMA: Dict[str, dict] = {
    "label": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "started_at_wall_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "completed_at_wall_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "started_at_monotonic_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "completed_at_monotonic_ms": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "result": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "pin": "ok",
        "privacy": "pub",
    },
    "drift": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "pin": False,
        "privacy": "pub",
    },
    "observed": {
        "type": T_DICT, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "address_family_host": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": NP_AF_ENUM,
        "privacy": "pub",
    },
    "address_family_emu": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": NP_AF_ENUM,
        "privacy": "pub",
    },
    "host_hmac_fp_hex": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": _HEX32,
        "privacy": "op",
    },
    "emu_hmac_fp_hex": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": _HEX32,
        "privacy": "op",
    },
    "equal_host_emu": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "pin": True,
        "privacy": "pub",
    },
    "abort_reason": {
        # Audit ROUND-23 P0: enum-typed. `TOTALLY_UNKNOWN` used to
        # slip through the plain str/null check.
        "type": T_STR, "presence": P_OPT, "nullability": N_NULL,
        "enum": ABORT_REASON_ENUM,
        "privacy": "pub",
    },
}


CHECKPOINT_OBSERVED_SCHEMA: Dict[str, dict] = {
    "apk_sha256_phone": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": _HEX64,
        "privacy": "pub",
    },
    "apk_sha256_emu": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": _HEX64,
        "privacy": "pub",
    },
    "expected_active_data_subscription_id": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "host_vpn_ok": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "pin": True,
        "privacy": "pub",
    },
    "paired_count_phone": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 1,
        "privacy": "pub",
    },
    "paired_count_emu": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 1,
        "privacy": "pub",
    },
    "phone_active_data_sim_operator_numeric": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": r"^\d{5,6}$",
        "privacy": "pub",
    },
    "phone_active_data_subscription_id": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "phone_has_transport_vpn": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "phone_mobile_data_enabled": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "pin": True,
        "privacy": "pub",
    },
    "phone_wifi_enabled": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "pin": False,
        "privacy": "pub",
    },
    "signed_prekey_published_emu": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "pin": True,
        "privacy": "pub",
    },
    "signed_prekey_published_phone": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "pin": True,
        "privacy": "pub",
    },
}


# Audit ROUND-29.1 P1-2: canonical `outer_transport` value set.
# Source of truth is the debug reporter — `DiagnosticTransportGuard.
# currentOuterArm()` returns the live arm string and defaults to
# "unknown"; `DiagnosticTransportGuardOuterArmMappingTest` pins
# direct / reality / tor and the non-direct states probing / idle /
# failed / unknown. `probe_failed` is the ONE operator-package-produced
# member: it means the pre-send `health` probe itself did not yield a
# fresh line for that device (non-zero rc or timeout), so no device
# value exists to record. Keeping it inside the same enum makes the
# per-device field total — every pre-send outcome has exactly one
# bounded representation instead of a free-text detail string.
_OUTER_TRANSPORT_ENUM = [
    "direct", "reality", "tor", "probing", "idle", "failed",
    "unknown", "probe_failed",
]

# Audit ROUND-29.1 P1-2: bounded reasons for a pre-send abort.
_PRE_SEND_REASON_ENUM = [
    "direct_unavailable",   # a fresh health line reported non-direct
    "health_probe_failed",  # no fresh health line / probe rc != 0
    "pin_unconfirmed",      # pin rc != 0 or breadcrumb mismatch
    # Audit ROUND-30.11: the CLEAR between directions did not confirm.
    # The later direction never dispatched, so this is a pre-send abort
    # for it, but the cause is cleanup rather than the pin itself and
    # the two must not be conflated in evidence.
    "pin_clear_unconfirmed",
]

# Audit ROUND-30.11: which side did not confirm. One CLOSED set, shared
# by the pre-send and post-send clear fields, so a two-sided failure has
# a canonical name instead of a joined string nobody can validate.
_CLEAR_EMITTER_ENUM = ["phone", "emulator", "both"]

# Audit ROUND-30.11: a run whose directions BOTH completed and whose
# final clear did not confirm. Deliberately separate from the pre-send
# set: those rules require e2p=FAIL with a null cid, which here would
# erase a delivery that smoke.log proves.
_POST_SEND_REASON_ENUM = [
    "pin_clear_unconfirmed",
]


SMOKE_VERDICT_SCHEMA: Dict[str, dict] = {
    # Audit ROUND-29.1 P1-1: `p2e`/`e2p`/`integrity` were pinned to
    # OK/OK/GREEN, which made a RED verdict STRUCTURALLY invalid — the
    # producer could not emit a schema-valid fail-closed verdict at
    # all. Acceptance is unaffected: `load_smoke_verdict` independently
    # rejects any non-OK/non-GREEN value (and every mutation kind still
    # applies, moving from `pin_break` to `invalid_enum`). The schema
    # now describes the closed set of values the producer may write;
    # the verifier decides which of them are acceptable.
    "p2e": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": ["OK", "FAIL"],
        "privacy": "pub",
    },
    "e2p": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": ["OK", "FAIL"],
        "privacy": "pub",
    },
    "integrity": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": ["GREEN", "RED"],
        "privacy": "pub",
    },
    "attempt_id": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": _UUIDV4,
        "privacy": "pub",
    },
    "arm_token": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "regex": _UUIDV4,
        "privacy": "op",
    },
    "smoke_run_id": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "at_wall_ms": {
        "type": T_INT, "presence": P_OPT, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "p2e_cid": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NULL,
        "privacy": "pub",
    },
    "e2p_cid": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NULL,
        "privacy": "pub",
    },
    # ── Audit ROUND-29.1: bounded pre-send abort fields ───────────
    # Present ONLY when the smoke never fired. R29 shipped these as an
    # unregistered `pre_send_reason` + free-text `pre_send_detail`,
    # which the closed schema rejected as unknown keys. They are now
    # schema authority, every one of them enum-bounded, and the free
    # detail string is replaced by per-device structured values.
    # Cross-field rules live in `load_smoke_verdict`.
    "pre_send_reason": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "enum": _PRE_SEND_REASON_ENUM,
        "privacy": "pub",
    },
    "pre_send_phone_outer": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "enum": _OUTER_TRANSPORT_ENUM,
        "privacy": "pub",
    },
    "pre_send_emu_outer": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "enum": _OUTER_TRANSPORT_ENUM,
        "privacy": "pub",
    },
    "pre_send_pin_stage": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "enum": ["p2e", "e2p"],
        "privacy": "pub",
    },
    "pre_send_pin_emitter": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        # `both` is reachable only for pin_clear_unconfirmed: the pin
        # confirmation short-circuits on the first failing side, while
        # the two clears are attempted independently. The verifier
        # keeps that distinction.
        "enum": _CLEAR_EMITTER_ENUM,
        "privacy": "pub",
    },
    "post_send_reason": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "enum": _POST_SEND_REASON_ENUM,
        "privacy": "pub",
    },
    "post_send_clear_stage": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "enum": ["final"],
        "privacy": "pub",
    },
    "post_send_clear_emitter": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "enum": _CLEAR_EMITTER_ENUM,
        "privacy": "pub",
    },
}


CELL_VERDICT_SCHEMA: Dict[str, dict] = {
    "cell_id": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "blocked": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "envelopes_dispatched": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0, "max": CIDS_PER_CELL,
        "privacy": "pub",
    },
    "delivered_count": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0, "max": CIDS_PER_CELL,
        "privacy": "pub",
    },
    "unresolved_count": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0, "max": CIDS_PER_CELL,
        "privacy": "pub",
    },
    "verdict": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": frozenset({"Delivered", "Unresolved", "BLOCKED", "PENDING"}),
        "privacy": "pub",
    },
    # Audit ROUND-29.2 P1-2: present ONLY when this cell aborted
    # before dispatching anything. Makes the cell self-describing
    # instead of forcing a reader to infer "0 dispatched, but why?"
    # from the matrix-level abort alone. Cross-field rules in
    # `validate_cell_verdict_content` pin it to a zero-dispatch,
    # non-blocked, Unresolved cell.
    # Audit ROUND-30.11: which side did not confirm the POST-CELL
    # clear. Present only under abort_reason="pin_clear_unconfirmed",
    # and drawn from the same closed set the smoke fields use, so a
    # two-sided failure has one canonical name everywhere.
    "post_cell_clear_emitter": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "enum": _CLEAR_EMITTER_ENUM,
        "privacy": "pub",
    },
    # Audit ROUND-30.14: a CELL may only record a cell-scoped reason.
    # The run-level members of `ABORT_REASON_ENUM` describe the run, and
    # accepting one here let a cell claim a drift or an interrupt that
    # no cell can witness.
    "abort_reason": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "enum": CELL_SCOPED_ABORT_REASONS,
        "privacy": "pub",
    },
}


# ── Audit ROUND-30.16 ──────────────────────────────────
# The vocabulary of `recipient_deliver_failed`.
#
# A delivery settles an envelope only when it persisted the row, marked
# the processed-envelope ledger AND acked. Anything else is reported by
# the recipient with one of these reasons and the furthest stage it
# reached, so a failed delivery is a fact in the evidence stream instead
# of a silence between events.
#
# Both sets are closed and carry no free text: the producer maps a
# throwable to a member rather than publishing its message, which is
# what keeps an exception string out of an evidence archive.
DELIVER_FAILURE_ENUM: Set[str] = frozenset({
    # An exception escaped the handler.
    "threw",
    # It returned normally, the envelope is unsettled, and a held row
    # explains why — the shape the hold-on-MAC path leaves.
    "held",
    # Unsettled, nothing threw, nothing confirms why. Fail-closed: an
    # unexplained delivery is a failure, never assumed benign.
    "unknown_processing_failure",
})

# Ordered from least to most complete. Each member implies every
# earlier one also completed.
# ── Audit ROUND-30.18 ──────────────────────────────────
# The attempt ordinal's range, named once for both ends.
#
# R30.17 left the producer unbounded while the verifier rejected anything
# above a limit it had invented for itself: the two ends of one field
# disagreed about its domain. The Kotlin side carries the same number as
# `DELIVER_ATTEMPT_ORDINAL_MAX`, and a Kotlin test pins it.
#
# Reaching it means one envelope was redelivered this many times without
# ever settling - a redelivery loop, not a delivery. Both ends report it
# rather than wrapping or clamping.
DELIVER_ATTEMPT_ORDINAL_MAX: int = 1000


DELIVER_STAGE_ENUM: Set[str] = frozenset({
    "received",
    "decrypted",
    "persisted",
    "ledger_marked",
    "ack_sent",
})


MATRIX_COMPLETION_SCHEMA: Dict[str, dict] = {
    "schema_version": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "pin": "1",
        "privacy": "pub",
    },
    "cells_declared": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "pin": 8,
        "privacy": "pub",
    },
    "cells_expected_to_run": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "enum": frozenset({6, 8}),
        "privacy": "pub",
    },
    "cells_ran": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0, "max": 8,
        "privacy": "pub",
    },
    "abort_reason": {
        # Audit ROUND-23 P0: enum-typed. `TOTALLY_UNKNOWN` used to
        # slip through the plain str/null check.
        "type": T_STR, "presence": P_REQ, "nullability": N_NULL,
        "enum": ABORT_REASON_ENUM,
        "privacy": "pub",
    },
    "run_id": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "privacy": "pub",
    },
    "profile_id": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "privacy": "pub",
    },
    "attempt_id": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NONE,
        "regex": _UUIDV4,
        "privacy": "pub",
    },
    "supersedes_attempt_id": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NULL,
        "regex": _UUIDV4,
        "privacy": "pub",
    },
    "retry_reason": {
        "type": T_STR, "presence": P_OPT, "nullability": N_NULL,
        "privacy": "pub",
    },
    "envelopes_sent": {
        "type": T_INT, "presence": P_OPT, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "envelopes_recipient_triplet_complete": {
        "type": T_INT, "presence": P_OPT, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "started_at_wall_ms": {
        "type": T_INT, "presence": P_OPT, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "completed_at_wall_ms": {
        "type": T_INT, "presence": P_OPT, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "started_at_monotonic_ms": {
        "type": T_INT, "presence": P_OPT, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "completed_at_monotonic_ms": {
        "type": T_INT, "presence": P_OPT, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "abort_at_wall_ms": {
        "type": T_INT, "presence": P_OPT, "nullability": N_NULL,
        "min": 0,
        "privacy": "pub",
    },
}


MATRIX_VERDICT_TOP_SCHEMA: Dict[str, dict] = {
    "evidence_integrity": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": frozenset({"GREEN", "RED", "NOT_EVALUABLE"}),
        "privacy": "pub",
    },
    "product_outcome": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": frozenset({"GREEN", "RED", "NOT_EVALUABLE"}),
        "privacy": "pub",
    },
    "delivered_count": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "unresolved_count": {
        "type": T_INT, "presence": P_REQ, "nullability": N_NONE,
        "min": 0,
        "privacy": "pub",
    },
    "cells": {
        "type": T_LIST, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
}


MATRIX_VERDICT_ROW_SCHEMA: Dict[str, dict] = {
    "cell_id": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "blocked": {
        "type": T_BOOL, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "verdict": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "enum": frozenset({"Delivered", "Unresolved", "BLOCKED", "PENDING"}),
        "privacy": "pub",
    },
}


SIGNED_PREKEY_TOP_SCHEMA: Dict[str, dict] = {
    "phone_line": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
    "emu_line": {
        "type": T_STR, "presence": P_REQ, "nullability": N_NONE,
        "privacy": "pub",
    },
}


# ── cross-field / temporal constraints (audit ROUND-22) ───────────
# List of (surface, tuple[str,str], constraint kind). The mutation
# generator produces one adversarial fixture per entry.
CROSS_FIELD_CONSTRAINTS: List[dict] = [
    {
        "path_a": "network_profile.emu.at_wall_ms",
        "path_b": "network_profile.emu.egress_fingerprint.at_wall_ms",
        "rule": "equal",
        "audit": "ROUND-22 P0-3",
    },
]


# Cross-field invariants applied within a single JSON object.
SUCCESS_INVARIANTS: List[dict] = [
    {
        "path": "network_profile.emu.egress_fingerprint",
        "rule": "hmac_nonnull_requires_2xx",
        "audit": "ROUND-22 P0-3",
    },
]


# Explicit surface REGISTRY (audit ROUND-22 P0-1). Every entry MUST
# have EITHER a `schema` dict (drives closed-key + type/enum/pin
# checks + mutation generation) OR a `validator_only` marker plus
# `validator` function name (surface uses a dedicated semantic
# checker that the generator does not touch).
#
# Audit ROUND-23 P1: each entry is now a full descriptor. Both the
# mutation generator AND the verifier's structural pass READ THIS
# REGISTRY (no separate constants).
#
# Fields per entry:
#   schema        — Dict[str,dict] closed field schema (JSON surfaces)
#   file          — on-disk file relative to evidence dir
#                   (validator-only surfaces may omit this)
#   json_path     — list of dict-keys / list-index sentinels from
#                   file root to this surface's dict
#                   (mutation dispatcher uses this)
#   path_prefix   — dotted prefix for leaf-path IDs used in mutation
#                   test names + logs
#   validator     — optional name of the verifier function that
#                   applies this surface's semantic checks
#   validator_only — True for surfaces the mutation generator does
#                   not touch (SHA256SUMS, WSS_DIAG events)
_L_IDX = "__list_index__:0"
_L_CELLS = "__list_index__:cells:0"

SURFACES: Dict[str, dict] = {
    "PROFILE.json": {
        "schema":       PROFILE_SCHEMA,
        "file":         "PROFILE.json",
        "json_path":    [],
        "path_prefix":  "PROFILE",
    },
    "PROFILE_STATE.json/phone_observed": {
        "schema":       PROFILE_STATE_PHONE_SCHEMA,
        "file":         "PROFILE_STATE.json",
        "json_path":    ["phone_observed"],
        "path_prefix":  "PROFILE_STATE.phone_observed",
    },
    "PROFILE_STATE.json/emu_observed": {
        "schema":       PROFILE_STATE_EMU_SCHEMA,
        "file":         "PROFILE_STATE.json",
        "json_path":    ["emu_observed"],
        "path_prefix":  "PROFILE_STATE.emu_observed",
    },
    "network_profile.json/phone": {
        "schema":       NETWORK_PROFILE_DEVICE_SCHEMA,
        "file":         "network_profile.json",
        "json_path":    ["phone"],
        "path_prefix":  "network_profile.phone",
    },
    "network_profile.json/emu": {
        "schema":       NETWORK_PROFILE_DEVICE_SCHEMA,
        "file":         "network_profile.json",
        "json_path":    ["emu"],
        "path_prefix":  "network_profile.emu",
    },
    "network_profile.json/emu/egress_fingerprint": {
        "schema":       NETWORK_PROFILE_EGRESS_SCHEMA,
        "file":         "network_profile.json",
        "json_path":    ["emu", "egress_fingerprint"],
        "path_prefix":  "network_profile.emu.egress_fingerprint",
    },
    "EGRESS_FINGERPRINT.json": {
        "schema":       EGRESS_FINGERPRINT_TOP_SCHEMA,
        "file":         "EGRESS_FINGERPRINT.json",
        "json_path":    [],
        "path_prefix":  "EGRESS_FINGERPRINT",
    },
    "signed_prekey_readiness.json": {
        "schema":         SIGNED_PREKEY_TOP_SCHEMA,
        "file":           "signed_prekey_readiness.json",
        "json_path":      [],
        "path_prefix":    "signed_prekey_readiness",
        "line_grammar":   SIGNED_PREKEY_LINE_TOKENS,
        "validator":      "check_signed_prekey_readiness",
    },
    "checkpoint_log.json/entry": {
        "schema":       CHECKPOINT_ENTRY_SCHEMA,
        "file":         "checkpoint_log.json",
        "json_path":    [_L_IDX],
        "path_prefix":  "checkpoint_log.entry",
    },
    "checkpoint_log.json/entry/observed": {
        "schema":       CHECKPOINT_OBSERVED_SCHEMA,
        "file":         "checkpoint_log.json",
        "json_path":    [_L_IDX, "observed"],
        "path_prefix":  "checkpoint_log.entry.observed",
    },
    "smoke_verdict.json": {
        "schema":       SMOKE_VERDICT_SCHEMA,
        "file":         "smoke_verdict.json",
        "json_path":    [],
        "path_prefix":  "smoke_verdict",
        "validator":    "load_smoke_verdict",
    },
    "cell_verdict.json": {
        "schema":       CELL_VERDICT_SCHEMA,
        "file":         "matrix_cells/wss.p2e.after-connect/cell_verdict.json",
        "json_path":    [],
        "path_prefix":  "cell_verdict",
        "validator":    "validate_cell_verdict_content",
    },
    "matrix_completion.json": {
        "schema":       MATRIX_COMPLETION_SCHEMA,
        "file":         "matrix_completion.json",
        "json_path":    [],
        "path_prefix":  "matrix_completion",
        "validator":    "validate_completion_json",
    },
    "matrix_verdict.json/top": {
        "schema":       MATRIX_VERDICT_TOP_SCHEMA,
        "file":         "matrix_verdict.json",
        "json_path":    [],
        "path_prefix":  "matrix_verdict.top",
        "validator":    "check_matrix_verdict_content",
    },
    "matrix_verdict.json/row": {
        "schema":       MATRIX_VERDICT_ROW_SCHEMA,
        "file":         "matrix_verdict.json",
        "json_path":    [_L_CELLS],
        "path_prefix":  "matrix_verdict.row",
    },
    "SHA256SUMS.txt": {
        "validator_only": True,
        "validator":      "check_sha256sums",
    },
    "WSS_DIAG_events": {
        "validator_only": True,
        "validator":      "check_events_against_allowlist",
    },
}


def all_declared_surfaces() -> List[str]:
    return sorted(SURFACES.keys())


def surface_schema(name: str) -> Optional[Dict[str, dict]]:
    entry = SURFACES.get(name)
    if entry is None:
        return None
    return entry.get("schema")


def surface_is_validator_only(name: str) -> bool:
    entry = SURFACES.get(name)
    return bool(entry and entry.get("validator_only"))


# ── mutation-generator helpers ────────────────────────────────────

def wrong_type_replacements_for(t: str) -> List[Any]:
    """Return a set of values whose JSON type disagrees with `t`.
    Used by the mutation generator to plant every wrong-type
    variant per field."""
    if t == T_STR:
        return [True, 42, [], {}]
    if t == T_INT:
        # bool is a JSON boolean, NOT an int in the strict verifier.
        return [True, "42", [42], {"k": 42}]
    if t == T_BOOL:
        return ["true", 1, [], {}]
    if t == T_LIST:
        return [True, 42, "list", {}]
    if t == T_DICT:
        return [True, 42, "dict", []]
    return []


def all_leaf_schemas() -> List[dict]:
    """Audit ROUND-23 P1: DERIVE all leaves by iterating SURFACES
    directly. The prior hand-listed `_push(...)` calls could drift
    (add a schema to SURFACES without adding it here and the
    mutation matrix silently missed the surface).
    """
    entries: List[dict] = []
    for surface_id, entry in SURFACES.items():
        if entry.get("validator_only"):
            continue
        schema = entry.get("schema")
        if schema is None:
            continue
        prefix = entry.get("path_prefix")
        if not prefix:
            raise AssertionError(
                f"SURFACES[{surface_id!r}] has schema but no path_prefix"
            )
        for name, spec in schema.items():
            entries.append({
                "path":      f"{prefix}.{name}",
                "spec":      spec,
                "field":     name,
                "surface":   surface_id,
            })
    return entries


# ── ROUND-23 P1: live surface-call tracing ─────────────────────────

# Populated by `check_registered_surface` inside verify_evidence_wss3
# whenever the verifier applies its structural pass to a surface.
# Consumers (parity test) can reset this before a run and inspect
# the visited set afterwards.
SURFACES_VISITED: Set[str] = set()


def surfaces_visited_reset() -> None:
    SURFACES_VISITED.clear()


def surfaces_visited_snapshot() -> Set[str]:
    return set(SURFACES_VISITED)


def surface_id_visit(surface_id: str) -> None:
    """Called by verify_evidence_wss3.check_registered_surface for
    every surface actually consumed by a real verify_profile_dir
    invocation. `surface_id` MUST be a key in SURFACES."""
    if surface_id not in SURFACES:
        raise AssertionError(
            f"surface_id_visit({surface_id!r}) — unknown registry key. "
            f"Update SURFACES or fix the caller."
        )
    SURFACES_VISITED.add(surface_id)


def enum_values(spec: dict) -> Optional[List[str]]:
    e = spec.get("enum")
    if e is None:
        return None
    return sorted(e)


def pinned_value(spec: dict) -> Any:
    return spec.get("pin", None)


# ── mutation-kind helpers (audit ROUND-22 P0-2) ────────────────────

def below_min_of(spec: dict) -> Optional[Any]:
    """Value strictly below `min` (or 0 → -1) for below_min mutation."""
    m = spec.get("min")
    if m is None:
        return None
    if isinstance(m, bool):
        return None
    if isinstance(m, int):
        return m - 1
    return None


def above_max_of(spec: dict) -> Optional[Any]:
    """Value strictly above `max` for above_max mutation."""
    m = spec.get("max")
    if m is None:
        return None
    if isinstance(m, bool):
        return None
    if isinstance(m, int):
        return m + 1
    return None


def regex_break_of(spec: dict) -> Optional[str]:
    """A definitely-wrong value for a `regex` field."""
    if spec.get("regex") is None:
        return None
    return "NOT_MATCHING_REGEX_R22"
