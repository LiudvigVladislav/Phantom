# WSS-3 Evidence Schema Matrix

**Canonical source of truth** for every WSS-3 evidence surface and every
nested field: producer, required/optional, exact JSON type, allowed
enum/value/range, nullability, profile-derived expected value, cross-file
equality bindings, privacy classification, failure classification.

Audit ROUND-21 CLOSURE ROUND per architect directive:

> Do not maintain three independent hand-written copies that can agree with
> each other while disagreeing with production.

This document is consumed by:

- `schema_wss3.py` — machine-readable dict mirroring this document; drives
  the verifier's closed-schema + required-key + type + enum checks AND the
  adversarial mutation generator.
- `fixtures/canonical_network_profile.phone.json`,
  `fixtures/canonical_network_profile.emu.json` — canonical producer output
  the Python fixture, the shell mock and the Kotlin focused test all
  consume / verify against.
- `tests/test_verifier_wss3_mutations.py` — auto-generated adversarial
  mutation matrix (one class per field × mutation kind).

**Legend**

| Column | Meaning |
|--------|---------|
| Producer | source-of-truth writer (path:line pin) |
| Req | R = required, O = optional |
| Type | JSON type. `bool!=int` — a `bool` fails an `int` check |
| Enum/pin | allowed values or pinned constant |
| Null | Y = nullable, N = not nullable, T = tri-state (bool or null) |
| From PROFILE | value derived from `PROFILE.json` (`—` if independent) |
| Cross-file | evidence surfaces that must agree on this value |
| Privacy | pub = public, op = operator-only, sec = secret, np = never-persist |
| Fail | RED = integrity RED, NE = NOT_EVALUABLE per contract §7 R18 |

Failure ordering: schema/type/format issues → `NE`; contract/value
mismatches after schema is well-formed → `RED`.

## Cross-file binding topology

```
                    PROFILE.json           (operator declaration)
                        │
                        │ derives expected values
                        ▼
network_profile.json  ◀───── PRIMARY device observation
    ├─ phone   (state-only, no HMAC)
    └─ emu     (state + egress_fingerprint)
                        │
        ┌───────────────┼────────────────────────┐
        ▼               ▼                        ▼
PROFILE_STATE.json   EGRESS_FINGERPRINT   checkpoint_log[*]
   summary of         host+emu HMAC,      per-cell snapshot of
   phone+emu          equal_host_emu      observed device state
```

Rules:

1. `PROFILE.json` is the operator's declaration. Every observation must
   agree with it (operator numeric, phone_vpn, host_vpn, apk_sha256, run_id,
   attempt_id, arm_token).
2. `network_profile.json` is the primary device observation. Every
   summary (`PROFILE_STATE.json`, `checkpoint_log[*].observed`) must agree
   with it on the same-observation fields.
3. `network_profile.emu.egress_fingerprint` must agree with
   `EGRESS_FINGERPRINT.json` on HMAC hex and address family (emu side).
4. `checkpoint_log[*]` HMAC + address-family fields must agree with
   `EGRESS_FINGERPRINT.json` at the same wall-time.
5. `matrix_verdict.json` row values must agree with the verifier's derived
   per-cell counts (audit ROUND-18 P0-3).
6. Any contradiction → integrity `RED`.

---

## PROFILE.json

Producer: `run-carrier-vpn-matrix.sh:preflight` — the operator's declared
profile pinned at arm time (contract §3, §4).

| Field | Req | Type | Enum/pin | Null | From PROFILE | Cross-file | Privacy | Fail |
|-------|-----|------|----------|------|-------------|-----------|---------|------|
| `carrier` | R | str | `YOTA`/`TELE2` | N | — | drives `expected_operator_numeric` | pub | RED |
| `expected_operator_numeric` | R | str | numeric string | N | — | == `network_profile.phone.active_data_sim_operator_numeric` | pub | RED |
| `phone_vpn` | R | str | `off`/`on` | N | — | == `network_profile.phone.has_transport_vpn` | pub | RED |
| `host_vpn` | R | str | `off`/`on` | N | — | drives host-side scutil expectations | pub | RED |
| `apk_sha256` | R | str | 64-hex lowercase | N | — | == `checkpoint_log[*].observed.apk_sha256_phone`/`_emu` | pub | RED |
| `run_id` | R | str | non-empty | N | — | == every WSS_DIAG `run_id` in captures | pub | RED |
| `attempt_id` | R | str | UUIDv4 | N | — | == `smoke_verdict.attempt_id`, `matrix_completion.attempt_id` | pub | RED |
| `arm_token` | R | str | UUIDv4 | N | — | == `smoke_verdict.arm_token` | op | RED |
| `arm_wall_ms` | R | int | ≥ 0 | N | — | temporal anchor | pub | RED |
| `arm_monotonic_ms` | R | int | ≥ 0 | N | — | temporal anchor | pub | RED |
| `confirm_string` | R | str | `CONFIRM-<CARRIER>-PHONE-<VPN>-HOST-<VPN>` | N | derived | — | pub | RED |
| `operator_manual_confirmations` | R | obj | `{auto_data_switching_off, other_sim_data_disabled}` closed | N | — | both `true` | pub | RED |
| `supersedes_attempt_id` | O | str/null | UUIDv4 or null | Y | paired with `retry_reason` | lineage DAG | pub | RED |
| `retry_reason` | O | str/null | enum | Y | paired with `supersedes_attempt_id` | lineage DAG | pub | RED |

Closed top-level schema: any unknown key → `RED`.

## PROFILE_STATE.json

Producer: `run-carrier-vpn-matrix.sh:414-442` — observed device state at
preflight, must agree with `network_profile.json`.

Top-level (closed, required): `phone_observed`, `emu_observed`.

### PROFILE_STATE.phone_observed

| Field | Req | Type | Enum/pin | Null | From PROFILE | Cross-file | Privacy | Fail |
|-------|-----|------|----------|------|-------------|-----------|---------|------|
| `active_data_sim_operator_numeric` | R | str | matches `PROFILE.expected_operator_numeric` | N | == `PROFILE.expected_operator_numeric` | == `network_profile.phone.active_data_sim_operator_numeric` | pub | RED |
| `active_data_subscription_id` | R | int | ≥ 0 (SubscriptionInfo id) | N | — | == `network_profile.phone.active_data_subscription_id` | pub | RED |
| `wifi_enabled` | R | bool | `false` in matrix runs (Wi-Fi off required) | T | must be `false` (Wi-Fi off during track) | == `network_profile.phone.wifi_enabled` | pub | RED |
| `mobile_data_enabled` | R | bool | `true` in matrix runs | T | must be `true` | == `network_profile.phone.mobile_data_enabled` | pub | RED |
| `has_transport_vpn` | R | bool | matches `PROFILE.phone_vpn == "on"` | N | derived | == `network_profile.phone.has_transport_vpn` | pub | RED |
| `wifi_read_error` | O | str/null | free-form | Y | — | == `network_profile.phone.wifi_read_error` when present | pub | RED |

### PROFILE_STATE.emu_observed

| Field | Req | Type | Enum/pin | Null | From PROFILE | Cross-file | Privacy | Fail |
|-------|-----|------|----------|------|-------------|-----------|---------|------|
| `active_network_kind` | R | str | free-form ConnectivityManager label | N | — | == `network_profile.emu.active_network_kind` | pub | RED |
| `has_transport_vpn` | R | bool | `false` (host VPN, not emu) | N | must be `false` | == `network_profile.emu.has_transport_vpn` | pub | RED |

## network_profile.json

Producer: on-device `DiagnosticNetworkProfileReporter.buildJson`
(`apps/android/src/debug/kotlin/phantom/android/diagnostic/DiagnosticNetworkProfileReporter.kt:504-582`).
Persisted by `run-carrier-vpn-matrix.sh:444-459`.

Top-level (closed, required): `phone`, `emu`.

**Pinned constants** (source-pinned to Kotlin producer):

- `SCHEMA_VERSION = "1"` — `DiagnosticNetworkProfileReporter.kt:62`
- `EGRESS_TIMEOUT_MS = 5000` — `DiagnosticNetworkProfileReporter.kt:59`
- Egress `endpoint = "api4.ipify.org"` — `DiagnosticNetworkProfileReporter.kt:559`

### network_profile.{phone,emu} (per-device report)

| Field | Req | Type | Enum/pin | Null | From PROFILE | Cross-file | Privacy | Fail |
|-------|-----|------|----------|------|-------------|-----------|---------|------|
| `schema_version` | R | str | pin: `"1"` | N | — | — | pub | RED |
| `at_wall_ms` | R | int | ≥ 0 | N | — | egress `at_wall_ms` if egress present | pub | RED |
| `at_monotonic_ms` | R | int | ≥ 0 | N | — | — | pub | RED |
| `has_transport_vpn` | R | bool | phone: matches `PROFILE.phone_vpn == "on"`; emu: `false` | N | phone-side derived | == `PROFILE_STATE.<side>.has_transport_vpn` | pub | RED |
| `active_network_present` | R | bool | — | N | — | — | pub | RED |
| `active_network_kind` | R | str | free-form (CELLULAR/WIFI/VPN/OTHER/NONE/…) | N | — | == `PROFILE_STATE.emu_observed.active_network_kind` (emu side) | pub | RED |
| `wifi_enabled` | R | bool | phone: `false` in matrix | T | phone: must be `false` | == `PROFILE_STATE.phone_observed.wifi_enabled` (phone side) | pub | RED |
| `wifi_read_error` | O | str | only when non-null (healthy path OMITS entirely) | Y | — | — | pub | RED |
| `mobile_data_enabled` | R | bool | phone: `true` in matrix | T | phone: must be `true` | == `PROFILE_STATE.phone_observed.mobile_data_enabled` | pub | RED |
| `active_data_subscription_id` | R | int | ≥ 0 (JSON int, NOT `"1"` string) | N | — | == `PROFILE_STATE.phone_observed.active_data_subscription_id` (phone side) | pub | RED |
| `active_data_sim_operator_numeric` | R | str | numeric string | N | phone: == `PROFILE.expected_operator_numeric` | == `PROFILE_STATE.phone_observed.active_data_sim_operator_numeric` (phone side) | pub | RED |
| `process_uid` | R | int | ≥ 0 | N | — | — | pub | RED |
| `app_debuggable` | R | bool | — | N | — | — | pub | RED |
| `egress_fingerprint` | phone: FORBIDDEN, emu: R | obj | closed nested schema below | — | — | see nested + EGRESS_FINGERPRINT.json | see nested | RED |

Phone side MUST NOT carry `egress_fingerprint` (contract §5 / ROUND-10 P0-1
— phone never participates in HMAC).

### network_profile.{phone,emu}.egress_fingerprint (nested, emu only)

| Field | Req | Type | Enum/pin | Null | From PROFILE | Cross-file | Privacy | Fail |
|-------|-----|------|----------|------|-------------|-----------|---------|------|
| `endpoint` | R | str | pin: `"api4.ipify.org"` | N | — | — | pub | RED |
| `address_family` | R | str | enum: `AF_INET`/`AF_INET6` | N | — | == `EGRESS_FINGERPRINT.address_family_emu` (emu side) | pub | RED |
| `hmac_fp_hex` | R | str | 32-hex lowercase | Y | — | == `EGRESS_FINGERPRINT.emu_hmac_fp_hex` (emu side) | op | RED |
| `at_wall_ms` | R | int | ≥ 0 | N | — | == parent `at_wall_ms` | pub | RED |
| `http_status` | R | int | ≥ 100 && ≤ 599 | Y | — | — | pub | RED |
| `timeout_ms_used` | R | int | pin: `5000` (EGRESS_TIMEOUT_MS) | N | — | — | pub | RED |

## EGRESS_FINGERPRINT.json

Producer: host-side HMAC + emu HMAC comparison (contract §10, ROUND-10 P0-4).

Top-level (closed, required): `host_hmac_fp_hex`, `emu_hmac_fp_hex`,
`equal_host_emu`, `address_family_host`, `address_family_emu`, `at_wall_ms`.
`phone_hmac_fp_hex` / `address_family_phone` are FORBIDDEN (§5).

| Field | Req | Type | Enum/pin | Null | From PROFILE | Cross-file | Privacy | Fail |
|-------|-----|------|----------|------|-------------|-----------|---------|------|
| `host_hmac_fp_hex` | R | str | 32-hex lowercase | N | — | == host-side checkpoint `host_hmac_fp_hex` | op | RED |
| `emu_hmac_fp_hex` | R | str | 32-hex lowercase | N | — | == `network_profile.emu.egress_fingerprint.hmac_fp_hex` | op | RED |
| `equal_host_emu` | R | bool | must be `true` for GREEN | N | — | derived | pub | RED |
| `address_family_host` | R | str | enum: `AF_INET`/`AF_INET6` | N | — | — | pub | RED |
| `address_family_emu` | R | str | enum: `AF_INET`/`AF_INET6` | N | — | == `network_profile.emu.egress_fingerprint.address_family` | pub | RED |
| `at_wall_ms` | R | int | ≥ 0 (non-boolean) | N | — | temporal anchor | pub | RED |

## signed_prekey_readiness.json

Producer: `run-carrier-vpn-matrix.sh:461-467` — both devices' one-line
reports.

Top-level (closed, required): `phone_line`, `emu_line`.

Each `*_line` is a str with strict closed field grammar
(NO substring matching, audit ROUND-21 P1-1):

```
signed_prekey_readiness published=<bool> signed_prekey_age_days=<int> remaining_opks=<int>
```

Required tokens (per line):

| Token | Req | Type | Enum/pin | Cross-file | Fail |
|-------|-----|------|----------|-----------|------|
| header | R | literal | pin: `signed_prekey_readiness` | — | RED |
| `published` | R | bool | must be exactly `true` for GREEN | == checkpoint observed `signed_prekey_published_<side>` | RED |
| `signed_prekey_age_days` | R | int | ≥ 0 | — | RED |
| `remaining_opks` | R | int | ≥ 0 | — | RED |

Substring presence of `published=true` in a line that ALSO contains
`published=false` is REJECTED.

## checkpoint_log.json

Producer: `lib/wss3-live.sh:_wss3_run_checkpoint` — list of per-cell
checkpoints + terminal `post-matrix`.

Length: `cells_expected_to_run + 1` (pre-cell for each + post-matrix).

Each entry (closed, required top-level):

| Field | Req | Type | Enum/pin | Null | From PROFILE | Cross-file | Privacy | Fail |
|-------|-----|------|----------|------|-------------|-----------|---------|------|
| `label` | R | str | `pre-cell:<canonical>` or `post-matrix` | N | — | canonical label sequence | pub | RED |
| `started_at_wall_ms` | R | int | ≥ 0 | N | — | temporal anchor | pub | RED |
| `completed_at_wall_ms` | R | int | ≥ `started_at_wall_ms` | N | — | temporal anchor | pub | RED |
| `started_at_monotonic_ms` | R | int | ≥ 0 | N | — | temporal anchor | pub | RED |
| `completed_at_monotonic_ms` | R | int | ≥ `started_at_monotonic_ms` | N | — | temporal anchor | pub | RED |
| `result` | R | str | enum: `ok`/(none for abort) | N | — | must be `ok` for GREEN | pub | RED |
| `drift` | R | bool | must be `false` for GREEN | N | — | — | pub | RED |
| `observed` | R | obj | nested closed schema | N | — | see below | see below | RED |
| `address_family_host` | R | str | enum: `AF_INET`/`AF_INET6` | N | — | == `EGRESS_FINGERPRINT.address_family_host` | pub | RED |
| `address_family_emu` | R | str | enum: `AF_INET`/`AF_INET6` | N | — | == `EGRESS_FINGERPRINT.address_family_emu` | pub | RED |
| `host_hmac_fp_hex` | R | str | 32-hex lowercase | N | — | (at that wall time) | op | RED |
| `emu_hmac_fp_hex` | R | str | 32-hex lowercase | N | — | (at that wall time) | op | RED |
| `equal_host_emu` | R | bool | must be `true` for GREEN | N | — | — | pub | RED |
| `abort_reason` | O | str/null | enum or null | Y | — | — | pub | RED |

### checkpoint_log[*].observed (closed nested)

| Field | Req | Type | Enum/pin | Null | From PROFILE | Cross-file | Privacy | Fail |
|-------|-----|------|----------|------|-------------|-----------|---------|------|
| `apk_sha256_phone` | R | str | 64-hex | N | == `PROFILE.apk_sha256` | — | pub | RED |
| `apk_sha256_emu` | R | str | 64-hex | N | == `PROFILE.apk_sha256` | — | pub | RED |
| `expected_active_data_subscription_id` | R | str/int | matches phone SIM | N | — | — | pub | RED |
| `host_vpn_ok` | R | bool | must match `PROFILE.host_vpn == "on"` | N | derived | — | pub | RED |
| `paired_count_phone` | R | int | ≥ 1 | N | — | — | pub | RED |
| `paired_count_emu` | R | int | ≥ 1 | N | — | — | pub | RED |
| `phone_active_data_sim_operator_numeric` | R | str | == `PROFILE.expected_operator_numeric` | N | derived | — | pub | RED |
| `phone_active_data_subscription_id` | R | str/int | matches PROFILE_STATE | N | — | — | pub | RED |
| `phone_has_transport_vpn` | R | bool | matches `PROFILE.phone_vpn == "on"` | N | derived | — | pub | RED |
| `phone_mobile_data_enabled` | R | bool | must be `true` | N | — | — | pub | RED |
| `phone_wifi_enabled` | R | bool | must be `false` | N | — | — | pub | RED |
| `signed_prekey_published_emu` | R | bool | must be `true` | N | — | == `signed_prekey_readiness.emu_line published` | pub | RED |
| `signed_prekey_published_phone` | R | bool | must be `true` | N | — | == `signed_prekey_readiness.phone_line published` | pub | RED |

## smoke_verdict.json

Producer: `lib/wss3-live.sh:wss3_live_smoke:304-320`.

Top-level (closed, required):

| Field | Req | Type | Enum/pin | Null | From PROFILE | Cross-file | Privacy | Fail |
|-------|-----|------|----------|------|-------------|-----------|---------|------|
| `p2e` | R | str | enum: `OK`/`FAIL` | N | — | — | pub | RED |
| `e2p` | R | str | enum: `OK`/`FAIL` | N | — | — | pub | RED |
| `integrity` | R | str | enum: `GREEN`/`RED` | N | — | — | pub | RED |
| `attempt_id` | R | str | UUIDv4 | N | == `PROFILE.attempt_id` | binding | pub | RED |
| `arm_token` | R | str | UUIDv4 | N | == `PROFILE.arm_token` | binding | op | RED |
| `smoke_run_id` | R | str | non-empty | N | — | owns the current smoke surface; unrelated foreign-run history is ignored, but a foreign-run event reusing a declared current CID is RED | pub | RED |
| `at_wall_ms` | O | int | ≥ 0 | N | — | — | pub | RED |
| `p2e_cid` | O | str/null | non-empty or null | Y | — | — | pub | RED |
| `e2p_cid` | O | str/null | non-empty or null | Y | — | — | pub | RED |
| `pre_send_reason` | O | str | enum: `direct_unavailable`/`health_probe_failed`/`pin_unconfirmed`/`pin_clear_unconfirmed` | N | — | gates the four fields below | pub | RED |
| `pre_send_phone_outer` | O | str | enum: `direct`/`reality`/`tor`/`probing`/`idle`/`failed`/`unknown`/`probe_failed` | N | — | required iff reason ∈ {`direct_unavailable`,`health_probe_failed`} | pub | RED |
| `pre_send_emu_outer` | O | str | same enum as `pre_send_phone_outer` | N | — | required iff reason ∈ {`direct_unavailable`,`health_probe_failed`} | pub | RED |
| `pre_send_pin_stage` | O | str | enum: `p2e`/`e2p` | N | — | required iff reason ∈ {`pin_unconfirmed`,`pin_clear_unconfirmed`} | pub | RED |
| `pre_send_pin_emitter` | O | str | enum: `phone`/`emulator`/`both` | N | — | required iff reason ∈ {`pin_unconfirmed`,`pin_clear_unconfirmed`}; `both` reachable only under `pin_clear_unconfirmed` | pub | RED |
| `post_send_reason` | O | str | enum: `pin_clear_unconfirmed` | N | — | gates the two fields below; mutually exclusive with `pre_send_reason` | pub | RED |
| `post_send_clear_stage` | O | str | enum: `final` | N | — | required iff `post_send_reason` present | pub | RED |
| `post_send_clear_emitter` | O | str | enum: `phone`/`emulator`/`both` | N | — | required iff `post_send_reason` present; named side(s) must have NO `pin=none` breadcrumb | pub | RED |

**Audit ROUND-30.11 — why the post-send set is separate.** A pre-send abort
means nothing was dispatched, and its rules demand `e2p=FAIL` with a null cid. A
final clear that does not confirm happens *after* both directions completed, so
forcing it into the pre-send shape would erase a delivery `smoke.log` proves.
The two sets are mutually exclusive by construction — the code paths that write
them cannot both run — and `load_smoke_verdict` enforces that.

**`pin_clear_unconfirmed` as a pre-send reason** is the *inter-direction* clear:
P2E completed, its clear did not confirm, so E2P never started. P2E therefore
keeps its real result and its correlation id; only E2P is `FAIL`/null.

**Audit ROUND-29.1 P1-1 — why `p2e`/`e2p`/`integrity` are enums, not pins.**
They were pinned to `OK`/`OK`/`GREEN`, which made a fail-closed RED verdict
*structurally* impossible: the producer could not emit the very shape the
pre-send gate needs, and `check_registered_surface` rejected it. The schema now
describes the closed set of values the producer may write; acceptance
lives in `load_smoke_verdict`. (Audit ROUND-30.18 narrows the rejection:
`integrity` must still be `GREEN`, but a `FAIL` direction whose every
attempt is accounted for is ACCEPTED as sound evidence of a product
failure — see § *The smoke-only terminal profile and the overflow
marker*.) Mutation coverage is preserved — those
leaves simply move from the `pin_break` kind to `invalid_enum`.

**Audit ROUND-29.1 P1-2 — pre-send fields.** Present only when the smoke never
fired. R29 emitted an unregistered `pre_send_reason` plus a free-text
`pre_send_detail`; the detail string is **removed** in favour of the bounded
per-device fields above. Cross-field rules (enforced in `load_smoke_verdict`):
a reason forces `integrity=RED`, `p2e=e2p=FAIL` and both CIDs `null`;
`direct_unavailable` requires at least one non-`direct` device value;
`health_probe_failed` requires at least one `probe_failed`; `pin_unconfirmed`
requires the pin pair and forbids the outer pair (health had already passed).

`smoke.log` MUST rebuild both p2e + e2p CID chains independently
(audit ROUND-19 P0-1 shared pre-full validator).

## cell_verdict.json

Producer: `lib/wss3-live.sh:716-740, 819-831`.

Top-level (closed, required):

| Field | Req | Type | Enum/pin | Null | Cross-file | Fail |
|-------|-----|------|----------|------|-----------|------|
| `cell_id` | R | str | canonical cell id | N | == enclosing dir name | RED |
| `blocked` | R | bool | matches producer decision | N | == `matrix_verdict.cells[cid].blocked` | RED |
| `envelopes_dispatched` | R | int | non-blocked: 5, blocked: 0 | N | == observed dispatched CIDs | RED |
| `delivered_count` | R | int | 0..envelopes_dispatched | N | == observed delivered CIDs | RED |
| `unresolved_count` | R | int | envelopes_dispatched - delivered_count | N | derived | RED |
| `verdict` | R | str | enum: `Delivered`/`Unresolved`/`BLOCKED`/`PENDING` | N | derived from counts | RED |
| `abort_reason` | O | str | enum: `CELL_SCOPED_ABORT_REASONS` (**not** the full `ABORT_REASON_ENUM`) | N | see the two branches below | RED |
| `post_cell_clear_emitter` | O | str | enum: `phone`/`emulator`/`both` | N | present **iff** `abort_reason=pin_clear_unconfirmed`; named side(s) must have NO `pin=none` breadcrumb on `<cell>.clear` | RED |

**Audit ROUND-30.11 — the two branches are different states.**
`pin_unconfirmed`, the only other reason a cell may record, describes a cell
that never ran: it requires `blocked=false`, all three counts `0` and
`verdict=Unresolved`.

**Audit ROUND-30.14 — this text used to say "every `abort_reason` other than
`pin_clear_unconfirmed`", which positively asserted that a cell may carry a
run-level reason. It cannot. Only the two members of
`CELL_SCOPED_ABORT_REASONS` are accepted in a cell, and that is now the enum of
the field itself rather than a claim made about it. A cell recording a
run-level reason is rejected by the schema pass, by
`validate_cell_verdict_content` and by `check_cell_abort_branches`
independently — three layers, none of which consults the matrix reason, the
cell's position, or whether the run aborted at all.

`pin_clear_unconfirmed` describes a cell that **DID** run. Its envelopes were
dispatched and delivered, only the post-cell cleanup failed, so it requires the
opposite: a real `envelopes_dispatched`, the ordinary verdict computed from the
counters, and never `PENDING`. Applying the zero-count rule to it would demand
that a delivered cell report nothing delivered — false evidence of exactly the
kind this schema exists to prevent. `validate_cell_verdict_content` excludes it
by name; `check_cell_abort_branches` enforces the opposite requirements.

**Audit ROUND-29.2 P1-2 — per-cell abort.** `wss3_live_full` now confirms both
emitters' pins (return code **and** a fresh matching `diagnostic_pin_active`,
over the cell's expected pin `wss` **or** `rest`) before any of a cell's five
sends. R29.1 confirmed pins for the smoke only, so the full matrix could still
dispatch behind an unconfirmed pin and recreate the original false-attribution
defect. On failure the cell dispatches **zero** envelopes, records
`abort_reason=pin_unconfirmed`, and the matrix stops with the same reason at
`matrix_completion.abort_reason`. The cross-field rules above stop the field
being attached to a cell that actually sent something.

## matrix_completion.json

**Audit ROUND-30.13 — exact matrix-to-cell consistency.** `ABORT_REASON_ENUM`
mixes two kinds of reason. Most of them describe the RUN — a checkpoint drift, a
lost pair, an operator interrupt — and no cell records them. Two of them,
`pin_unconfirmed` and `pin_clear_unconfirmed`, are claims about ONE cell; they
are the members of `schema_wss3.CELL_SCOPED_ABORT_REASONS`, which is the single
authority for that distinction and is asserted to be a subset of the enum.

R30.13 named that set an authority while nothing executable consulted it:
`CELL_VERDICT_SCHEMA["abort_reason"]` still carried the full enum, so a cell
claiming `operator_interrupt` passed every layer in silence. R30.14 made the
set the cell field's enum, which is why it is defined above that schema. The
run-level members remain legal in `matrix_completion` and in the checkpoint —
they describe the run, which is the thing that can witness them.

When `matrix_completion.abort_reason` is cell-scoped, exactly one cell must
record that same reason. Each of these is a finding:

- zero cells corroborate it — the matrix blames a cell and none admits it;
- more than one cell records it;
- any other cell records a different `abort_reason`, wherever it sits in the
  canonical order.

Position is not part of the rule. R30.12 enforced it only over cells whose own
reason was already cell-scoped, so a cell carrying one of the twelve run-level
values escaped the count entirely when it preceded the aborting cell, and was
caught only sideways by the later-rows PENDING rule when it followed. Every cell
after the aborting one must still be `PENDING`.

`check_cell_abort_branches` enforces all of it; the contract states it in §4.4.


Producer: `lib/wss3-live.sh:876-903`. Top-level closed schema listed in
`_ALLOWED_MC_KEYS`.

| Field | Req | Type | Enum/pin | Null | Cross-file | Fail |
|-------|-----|------|----------|------|-----------|------|
| `schema_version` | R | str | pin: `"1"` | N | — | RED |
| `cells_declared` | R | int | pin: `8` | N | — | RED |
| `cells_expected_to_run` | R | int | `6` or `8` | N | — | RED |
| `cells_ran` | R | int | ≤ cells_expected_to_run | N | — | RED |
| `abort_reason` | R | str/null | enum or null | Y | when cell-scoped: **exactly one** cell records the same reason, and no other cell records any reason | RED |
| `run_id` | O | str | non-empty | N | == `PROFILE.run_id` | RED |
| `profile_id` | O | str | derived | N | derived from PROFILE | RED |
| `attempt_id` | O | str | UUIDv4 | N | == `PROFILE.attempt_id` | RED |
| `supersedes_attempt_id` | O | str/null | UUIDv4 or null | Y | == `PROFILE.supersedes_attempt_id` | RED |
| `retry_reason` | O | str/null | enum or null | Y | == `PROFILE.retry_reason` | RED |
| `envelopes_sent` | O | int | ≥ 0 | N | — | RED |
| `envelopes_recipient_triplet_complete` | O | int | == sum of cell delivered | N | == `matrix_verdict.delivered_count` | RED |
| `started_at_wall_ms` | O | int | ≥ 0 | N | temporal | RED |
| `completed_at_wall_ms` | O | int | ≥ started | N | temporal | RED |
| `started_at_monotonic_ms` | O | int | ≥ 0 | N | temporal | RED |
| `completed_at_monotonic_ms` | O | int | ≥ started | N | temporal | RED |
| `abort_at_wall_ms` | O | int/null | ≥ 0 or null | Y | — | RED |

## matrix_verdict.json

Producer: `lib/wss3-live.sh:905-933`. Top-level closed schema:
`evidence_integrity`, `product_outcome`, `delivered_count`, `unresolved_count`, `cells`.

| Field | Req | Type | Enum/pin | Null | Cross-file | Fail |
|-------|-----|------|----------|------|-----------|------|
| `evidence_integrity` | R | str | enum: `GREEN`/`RED`/`NOT_EVALUABLE` | N | == verifier's outcome | RED |
| `product_outcome` | R | str | enum: same | N | == verifier's recomputed | RED |
| `delivered_count` | R | int | sum of cells | N | derived | RED |
| `unresolved_count` | R | int | sum of cells | N | derived | RED |
| `cells` | R | list | exactly 8 canonical, no dupes | N | per-row equality with `cell_verdict.json` counts | RED |

Each row (closed, required): `cell_id`, `blocked`, `verdict`.
Row `verdict` enum: `Delivered`/`Unresolved`/`BLOCKED`/`PENDING`.

## SHA256SUMS.txt (per profile)

Producer: `lib/wss3-live.sh:wss3_write_evidence_manifest` (audit
ROUND-30.19), called from every smoke verdict write AND the end of
`full` — a profile that STOPS at the smoke carries a manifest too. One
entry per regular file under the evidence dir except the manifest
itself. Every SHA line MUST match the actual file. Every load-bearing
evidence file MUST have a line (architectural minimum enforced by
`check_sha256sums`).

## WSS_DIAG event fields (matrix + smoke captures)

Every WSS_DIAG line MUST carry:

| Field | Req | Type | Enum/pin | Cross-file | Fail |
|-------|-----|------|----------|-----------|------|
| `event` | R | str | in SENDER ∪ RECIPIENT ∪ MATRIX ∪ CROSS_RUN_TOLERATED | — | RED |
| `role` | R | str | matches event class (sender/recipient/matrix) | — | RED |
| `emitter_id` | R | str | matches per-class expected (`phone`/`emulator`) | — | RED |
| `run_id` | R | str | == `PROFILE.run_id` (or in CROSS_RUN_TOLERATED w/ relaxed run/cell) | — | RED |
| `cell_id` | R (CID events) | str | canonical | == enclosing cell | RED |
| `correlation_id` | conditional | str | non-empty for CID-bearing events | == dispatched-CID set per cell | RED |
| `wall_utc_ms` | R | int | ≥ 0 | temporal | RED |
| `monotonic_ms` | R | int | ≥ 0 | temporal | RED |
| `sequence` | R (dispatched) | int | 1..5 exactly across the 5 CIDs | — | RED |

CROSS_RUN_TOLERATED events (`diagnostic_session_started`, `diagnostic_canary`,
`diagnostic_state_cleared`) relax `run_id`/`cell_id` but MUST keep
`role=matrix` and `emitter_id ∈ {phone, emulator}`
(audit ROUND-19 P0-3).

## Privacy classification summary

- **pub** — safe in evidence, safe in public review packs.
- **op** — evidence-only, operator-visible; must not appear outside the
  evidence dir or in the tar body inspection.
- **sec** — never persisted (checkpoint_key, session tokens, cookies,
  identity keys). Producer sweep (`wss3_evidence_full_privacy_sweep`)
  rejects on hit.
- **np** — never present anywhere in evidence (e.g. raw IP address,
  phone-side HMAC — banned by forbidden-fields check).

## Failure classification summary

- **NE** = `NOT_EVALUABLE` per contract §7 rule 18. Assigned when
  integrity is compromised (schema mismatch, malformed JSON, missing
  file, checksum failure). The row does not claim GREEN or RED — the
  evidence cannot be judged.
- **RED** = evidence is well-formed but a contract value is wrong
  (product_outcome does not match observed counts, HMAC mismatch,
  operator numeric divergence, etc.). The verifier can and does judge
  the row.

## Adversarial mutation generation (Step 3)

`tests/test_verifier_wss3_mutations.py` reads this schema (via
`schema_wss3.py`) and generates one adversarial fixture per (field,
mutation kind):

1. Delete required field.
2. Add unknown field alongside.
3. Replace with every wrong JSON type from `{bool, int, str, list, dict, null}`.
4. Invalid enum member (for enum fields).
5. Wrong pinned constant (for pinned fields).
6. Forbidden null (for non-nullable fields).
7. Contradictory duplicated value (for cross-file-bound fields).
8. Cross-file mismatch (independent mutation on the other end of the binding).
9. Malformed timestamp / range violation (for temporal fields).
10. Privacy-denylisted key/value (semantic classifier hit).

Every generated fixture MUST produce integrity=False (RED or NE). Counts
appear in `mutation-summary.txt` in the review pack.

## recipient_deliver_failed (audit ROUND-30.16)

| field | R/O | type | domain | null | Cross-file | on violation |
|---|---|---|---|---|---|---|
| `correlation_id` | R | str | envelope UUID | N | the cid the recipient was delivering | RED |
| `deliver_failure` | R | str | enum: `DELIVER_FAILURE_ENUM` | N | — | RED |
| `deliver_stage` | R | str | enum: `DELIVER_STAGE_ENUM` | N | must not claim a stage the stream contradicts | RED |

`schema_wss3.DELIVER_FAILURE_ENUM` = `threw`, `held`,
`unknown_processing_failure`. `schema_wss3.DELIVER_STAGE_ENUM` =
`received`, `decrypted`, `persisted`, `ledger_marked`, `ack_sent`,
ordered. Both are re-exported by `verify_evidence_wss3.py`, and the
Kotlin producer's wire tokens are pinned against them by
`DeliverFailedSchemaTest` — the two vocabularies live in different
languages, so only a test makes them agree.

**Integrity vs product outcome.** A well-formed record is NOT an
integrity finding. The evidence is intact; the product failed, and the
incomplete recipient triplet reports that. Integrity findings are: a
value outside either enum; the wrong emitter or role; more than one
record for one envelope; a stage the event stream contradicts; a failure
claimed alongside a complete settled triplet.


## The attempt is the unit of outcome (audit ROUND-30.17)

An envelope can be delivered more than once. ROUND-30.16 recorded one
terminal outcome per ENVELOPE and therefore could not describe the run
it was built for: the 2026-08-26 smoke was redelivered, producing two
fresh processings and two terminal records, and the per-envelope rule
read the second one as corrupt evidence.

Every fresh processing of an envelope is an ATTEMPT. The recipient
numbers it inside the same critical section that claims the envelope, so
attempts cannot share a number or interleave: `activeProcessing` already
refuses a concurrent claim, and the terminal record is emitted from the
`finally` that runs before the next claim can succeed. The ordinal is a
small positive integer and carries nothing else.

Each attempt owes **exactly one** terminal outcome:

- a complete settled triplet -- `recipient_message_persisted` **and**
  `recipient_ack_deliver_sent` for that attempt; or
- one well-formed `recipient_deliver_failed` for that attempt.

Neither is an unaccounted attempt. Both is a contradiction. A terminal
record whose attempt has no fresh delivery is an orphan. All three are
INTEGRITY findings.

| shape | `integrity_ok` | `product_outcome` |
|---|---|---|
| N fresh, N failed attempts | true | RED |
| N fresh, N-1 failed + one settled | true | GREEN (delivered on retry) |
| an unaccounted attempt | false | NOT_EVALUABLE |
| two terminal outcomes for one attempt | false | NOT_EVALUABLE |
| an orphan terminal record | false | NOT_EVALUABLE |
| a non-consecutive or out-of-range ordinal | false | NOT_EVALUABLE |
| foreign cid, run_id, cell_id or emitter | false | NOT_EVALUABLE |
| a declared failure nothing accounts for | false | NOT_EVALUABLE |

A failure record is bound to its attempt by cid, run_id, cell_id,
recipient emitter and ordinal. Its `deliver_stage` may explain missing
events only INSIDE that attempt -- one attempt's persist never covers
another's gap.

**Integrity and product outcome are separate questions.** Trustworthy
evidence that the product failed leaves `integrity_ok` true and drives
`product_outcome` to RED. Only evidence that cannot be trusted yields
NOT_EVALUABLE. Collapsing them made a truthful failure report
indistinguishable from a broken capture, so the verifier refused to
evaluate exactly the runs it exists to judge.

**Legacy evidence.** The three recorded physical attempts predate the
ordinal and are immutable. An unambiguous legacy shape -- the `attempt`
field ABSENT everywhere and exactly one fresh delivery -- is read as
attempt 1. An `attempt` that is present but malformed or out of range is
not legacy; it is corruption, and the fallback must not excuse it. Two
unnumbered fresh deliveries -- precisely the 2026-08-26 shape -- stay a
finding rather than a guess.

**Scope.** This is the SMOKE. The full matrix keeps every rule it had,
including `triplet_complete == cells_expected_to_run * 5`: the defect
happened before `full` was ever allowed to run.

## recipient_deliver_attempt_overflow (audit ROUND-30.18)

Emitted at most ONCE per envelope, from the same closed `WSS_DIAG`
authority as every other recipient event, when a delivery attempt's
ordinal would exceed `DELIVER_ATTEMPT_ORDINAL_MAX`. Standard event
envelope fields (`role=recipient`, `emitter_id`, `run_id`, `cell_id`,
`correlation_id`, timestamps); no `attempt` field — the marker exists
precisely because the ordinal range is exhausted, and later per-attempt
records for that envelope are suppressed rather than numbered.

Verifier reading: the attempt history is TRUNCATED →
`integrity_ok=false / NOT_EVALUABLE`. Binding is enforced like every
attempt record: foreign emitter / cell / run, duplication, or a marker
with no fresh delivery behind it are intruder findings on the full
`verify_profile_dir`.

## The smoke-only terminal profile and the overflow marker (audit ROUND-30.18)

A RED smoke STOPS the profile: `full` refuses to run (contract §3.2),
so the producer never writes `matrix_completion.json`. Treating that
absence as unjudgeable meant the verifier answered NOT_EVALUABLE for
exactly the runs ROUND-30.17 existed to describe. A profile with no
matrix is judgeable when, and only when, the smoke itself explains why
the matrix is absent.

| shape (no `matrix_completion.json`) | `integrity_ok` | `product_outcome` |
|---|---|---|
| RED smoke, every attempt accounted for | true | RED |
| any matrix-phase artefact present (audit ROUND-30.20) | false | NOT_EVALUABLE |
| GREEN smoke (the matrix should have run) | false | NOT_EVALUABLE |
| missing or malformed `smoke_verdict.json` | false | NOT_EVALUABLE |
| a declared failure nothing accounts for | false | NOT_EVALUABLE |

There is never a successfully executed matrix underneath this reading:
it applies only while `matrix_completion.json` is ABSENT. A present
one — including the §4.4 empty sentinel — keeps every full-matrix
rule unchanged.

Two producer consequences follow. `smoke_verdict.integrity` describes
the EVIDENCE — a failed direction whose every attempt carries a
well-formed `recipient_deliver_failed` writes `integrity=GREEN` with
`p2e`/`e2p` `FAIL`, because an honest account of a failure is sound
evidence. And the `full` gate refuses a failed direction BY NAME: with
integrity no longer conflating the two questions, `full` checks
`p2e`/`e2p` explicitly, or a well-accounted RED smoke would unlock the
matrix the smoke exists to gate.

**The overflow marker.** The attempt ordinal range is
`1..DELIVER_ATTEMPT_ORDINAL_MAX` (= 1000), named identically by the
producer (`WssDiagBridge.kt`) and the verifier schema authority
(`schema_wss3.py`). Past the bound the recipient emits ONE
`recipient_deliver_attempt_overflow` per envelope, from the same closed
emit authority as every other recipient event, suppresses further
per-attempt ordinals for that envelope, and delivery itself continues
untouched — a diagnostic never decides whether a message arrives.
Settlement clears the marker together with the counter, so a later
chain for the same envelope inherits nothing.

A marker in the evidence is an integrity statement: attempts happened
whose records are not here, so the history is TRUNCATED and the profile
is `integrity_ok=false / NOT_EVALUABLE` — judging what IS here would be
a verdict on part of the run presented as the whole. The marker must be
bound to the delivery it truncates: a marker from a foreign emitter, on
a foreign cell or run, duplicated, or standing with no fresh delivery
behind it is an intruder finding on the FULL `verify_profile_dir`,
never merely invisible to a helper.

**Audit ROUND-30.19 — the verdict comes AFTER the audits.** R30.18's
implementation returned the terminal `GREEN/RED` before the SHA256SUMS
verification, the required-file check, the privacy denylist and the
closed inventory ever ran, so a coherently tampered or privacy-dirty
terminal profile was accepted as intact evidence — the same
early-return class R29.3 closed for the aborted matrix. Three rules
now hold:

- every common audit that does not need a matrix to exist runs, in
  full, BEFORE the smoke-only verdict; any finding refuses the
  profile. The required-file set is stage-aware: the matrix-phase
  files (`matrix_completion.json`, `matrix_verdict.json` and
  `checkpoint_log.json`, which `full` initialises and fills per cell)
  are not owed, everything else is;
- a terminal profile CARRIES `SHA256SUMS.txt`: the producer seals the
  manifest at every point the profile can stop (each smoke verdict
  write, pre-send aborts included), and `full` rewrites it when the
  matrix runs. A full run that dies between matrix artefacts and its
  manifest rewrite fails the hash gate — the honest answer;
- the producer's accounted-claim uses the verifier's ordinal-bound
  attempt audit — one fresh per ordinal, consecutive from 1, exactly
  one terminal outcome per attempt, overflow marker disqualifies,
  one unambiguous unnumbered attempt reads as legacy. Totals are not
  a predicate: two failure records both bound to attempt 1 satisfied
  the old count while the verifier saw a duplicate terminal and an
  unaccounted attempt.

**Audit ROUND-30.20 — the terminal profile is total, and there is one
predicate.** Three gaps remained after R30.19, each of which let
untrustworthy evidence read as sound:

- **Chain defects were dropped at a single CID.** The per-CID chain
  findings reached the report only when a direction had MORE than one
  candidate correlation id, so for the ordinary case every defect was
  computed and discarded. They are reported at any candidate count now.
  The rule they enforce was itself half-implemented: "exactly one
  `diagnostic_send_dispatched` per CID" tested only the upper bound, so
  ZERO passed in silence — a delivery verdict with no record that
  anything was ever dispatched. Both bounds are enforced.
- **The terminal branch ignored matrix-phase artefacts.** It was
  selected on the absence of `matrix_completion.json` alone while the
  closed inventory permits `matrix_verdict.json`, `matrix_cells/` and
  `checkpoint_log.json`. Every matrix-phase artefact is now FORBIDDEN
  in a smoke-stopped profile: `full` writes all of them, so a profile
  the smoke stopped has none, and a `full` that died mid-matrix —
  artefacts present, completion record absent — is unjudgeable rather
  than being read as a stopped smoke.
- **A failed manifest seal changed nothing.** The writer's status was
  discarded by the delivery predicate in `smoke` and by later
  statements in `full`, and both entry points run inside `if`, so
  `set -e` never applied: the tool could announce GREEN over evidence
  it had failed to seal. The seal's status is now the verb's status on
  both paths, an empty evidence directory is refused rather than
  "sealed" with a manifest of the empty pipe, and forced-failure
  probes drive both real verb paths. The portable hasher fallback is
  an explicit branch: it cannot run both hashers through ambiguous
  mixed `&&` / `||` precedence.

**One direction verdict, not separate success and failure
predicates.** The producer consumes `OK / ACCOUNTED / INVALID` from
`verify_evidence_wss3.smoke_direction_soundness`. That direction authority
runs the verifier's scoped event-surface sweep and per-CID attempt audit,
bound to the producer's expected run, cell and dispatched correlation id.
The two earlier private accounted predicates were weaker than the verifier,
and the first R30.20 draft still kept a private success predicate: it rejected
a legal retry after attempt 2 settled because it counted two fresh deliveries
at envelope scope. One direction authority now decides both product success
and accounted failure; fixtures vary CID, run, role, stage, enums and the
failed-then-settled retry.

Two scoped direction answers do not cover the whole log. Before it may write
`integrity=GREEN`, the producer also invokes the verifier's global smoke
surface authority over the complete snapshot and both declared CIDs. Any
current-run record outside both direction scopes therefore vetoes GREEN at
the producer and remains the same integrity finding at verification time.

**Current-run ownership and re-arm baseline.** `smoke_run_id` scopes the
events that can support or contradict this smoke. Unrelated historical
records left in logcat are ignored; a foreign-run record that reuses a
CID declared by this verdict is an integrity finding. The producer's
per-direction query sees only that direction's cell/CID, so an E2P pin
failure cannot erase a P2E delivery already proved, while the full smoke
audit still reports the E2P defect.

A successor created by `resume` owns the same four non-PROFILE baseline
surfaces as its predecessor: `EGRESS_FINGERPRINT.json`,
`PROFILE_STATE.json`, `network_profile.json` and
`signed_prekey_readiness.json`. Their source bytes must match the
predecessor's `SHA256SUMS.txt`, parse as UTF-8 JSON objects, and be copied
byte-exactly through exclusive temporary files. Any failure removes the
successor transactionally; a successor without this baseline is not a
valid re-arm.

**Audit ROUND-30.21 — route-dependent sender completion.** Every CID first
proves dispatch with one coherent
`sender_transport_decision(dispatched=true, outer_transport=direct,
inner_route=<pin>)`. After that common event the allowed terminal schemas
are disjoint:

- WSS: `sender_wss_send_returned(dispatched=true, inner_route=wss)` and
  `sender_relay_ack_received` are required;
- REST: `sender_rest_post_completed(inner_route=rest,
  relay_acceptance=accepted|duplicate)` is required, `dispatched` is
  forbidden on that completion, and `sender_relay_ack_received` is
  forbidden for the REST CID.

The recipient fresh/persisted/ack-sent triplet remains mandatory on both
routes. Canonical fixtures and the ADB mock use this exact physical schema;
they may not add WSS-only fields to make a stricter verifier pass.

## The local WARN capture lifecycle (audit ROUND-30.17)

- A capture that ends on its own before dispose -- for any reason and
  with any status, including a clean `0` -- stopped recording at an
  unknown point, so its window is not covered. That is a
  `CAPTURE-FAILED`.
- The `TERM`/`KILL` that dispose itself sends are the normal end of a
  capture that did its job, and are never a failure.
- The single real caller passes the disposer's stderr through. Those
  messages are fixed and privacy-safe: a capture failure, or the PATH of
  a retained log. The contents are never printed.
- Deletion on GREEN is verified. If `rm` or `rmdir` fails, the path
  variable is KEPT and the failure is reported -- a sensitive log that
  survives with nobody holding its location can be neither used nor
  cleaned up.
- A capture failure never changes a product verdict.
