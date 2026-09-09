# WSS-3 — Carrier × VPN Matrix (Round-7 contract sheet)

**Track:** `wss-3-carrier-vpn-matrix`  
**Status:** Round-7 draft, awaits architect review-GREEN before any code. Amended per architect REDLINE-7 (2026-08-14). See §Round-7 change log at bottom.  
**Base branch:** `track/wss-3-carrier-vpn-matrix` off `origin/master@805f7960` (PR #400 merge SHA — rebase target when moving to implementation).  
**Date:** 2026-08-14.  
**Contract precedent:** `docs/tracks/direct-wss/direct-wss-yota-contract.md` (WSS-0..WSS-2, accepted). This track reuses the accepted operator-package skeleton verbatim and layers a matrix orchestrator on top.

---

## 0. Executive summary

Operator track that runs the accepted WSS-2 send/verify machinery across a 2 × 2 × 2 matrix (`carrier ∈ {YOTA, TELE2}`, `phone_vpn ∈ {off, on}`, `host_vpn ∈ {off, on}`) — 8 profiles total — and produces a single 8-row report separating `evidence_integrity` from `product_outcome`. Automates the send/verify loop so the operator only manually switches SIM/host-VPN/phone-VPN between profiles and types the profile-scoped confirmation.

Everything below is **operator tooling only** — no changes to production onboarding/UI, relay protocol, release behaviour, or the merged PR #399 diagnostic instrumentation, EXCEPT for one bounded debug-only extension (§4.5): a single new `network_profile_report` subcommand on the existing debug-only `DiagnosticCommandReceiver` plus a `DiagnosticNetworkProfileReporter` helper class — both physically absent from the release APK.

**Terminology invariant (per operator ask):**

- `phone_vpn` — VPN client running on the physical Android device (TECNO BF7-12) with the active SIM.
- `host_vpn` — VPN client running on the Mac; the emulator's network path traverses it. **Never** call this `emulator_vpn` in evidence schema, filenames, or log fields.

---

## 1. Non-goals

- No production code, no `apps/android/src/androidMain/**` source change, no `shared/core/**` change.
- No relay protocol change. No new relay endpoints.
- No release-build modification. No `assembleRelease` invocation. Debug-only extension in §4.5 is physically absent from release (source-set boundary already enforced by `DiagnosticSourceSetBoundaryTest`).
- No modification to the merged PR #399 diagnostic instrumentation (`WssDiag`, `DiagnosticTransportGuard`, existing `DiagnosticCommandReceiver` subcommands, boot-init provider). §4.5 additions live alongside existing subcommands, additively.
- No modification to accepted WSS-2 operator package inner helpers (`lib/bootstrap.sh`, `lib/capture-logs.sh`, `lib/diag-cmd.sh`, `lib/detect-devices.sh`, `lib/install-apk.sh`, `lib/operator-args.sh`, `lib/portable.sh`, `lib/run-matrix.sh`, `lib/run-matrix-helpers.sh`, `verify-evidence.py`) beyond additive parametrisation for the matrix — WSS-2 tests remain GREEN unchanged.
- No PR #400 (onboarding-baseline-landing) dependency. WSS-3 runs against whatever debug APK the operator has installed at the moment of `preflight`, verified by SHA binding (§7 R3).
- No historical Yota/Tele2 archives (`f33a1387…`, `226620a2…`) treated as substitutes for fresh VPN-profile evidence — they remain supporting context only.
- No VPN provider credentials, no raw public IPs, no usernames, keys, tokens or payload text in evidence (§7 R9/R10).
- No `recordPaparazzi` / release-APK build / device instrumentation runs during CI. `assembleDebug` runs at most once when the operator explicitly asks for a fresh diagnostic APK (Process §10 step 5).
- **No `adb` invocation at all until architect + operator green-light** past the synthetic Mac dry-run (Process §10 step 4).
- No client-side `Unresolved / PENDING` classification and **no `unresolved_120s_marker` in any emitter** (§4.1 WSS-2 schema pin).

---

## 2. Matrix

### 2.1 Dimensions

| Dimension | Values | Notes |
|---|---|---|
| `carrier` | `YOTA` \| `TELE2` | Phone SIM carrier. Operator switches physical SIM between runs. |
| `phone_vpn` | `off` \| `on` | VPN client on the phone. Off = raw carrier egress. On = phone routes through a chosen VPN. Actual VPN provider is operator-selected, never recorded. |
| `host_vpn` | `off` \| `on` | VPN client on the Mac host that the emulator's network path uses. Off = raw macOS egress. On = macOS routes through a chosen VPN. |

### 2.2 Canonical order per carrier

The full run per carrier walks the four VPN profiles in this fixed order:

| # | phone_vpn | host_vpn | Directory suffix |
|--:|--:|--:|---|
| 1 | `off` | `on` | `phone-off-host-on` |
| 2 | `off` | `off` | `phone-off-host-off` |
| 3 | `on` | `off` | `phone-on-host-off` |
| 4 | `on` | `on` | `phone-on-host-on` |

Rationale for ordering: start with the most-instrumented outbound (host_vpn on, phone raw) so any deviation in the very first profile surfaces the least-ambiguous cause. End with the double-VPN case where independent decisions of two providers stack — that's the hardest to interpret, so we save it for last when the other three provide baselines.

Full track = 2 carriers × 4 profiles = **8 profiles**.

### 2.3 Expected carrier-numeric pinning

| Carrier | `--expected-operator-numeric` |
|---|---|
| YOTA | `25011` |
| TELE2 | `25020` |

Reused verbatim from WSS-2 (`docs/tracks/direct-wss/direct-wss-yota-contract.md` §12.10 initial parametrisation).

---

## 3. Operator CLI

Single entry point script `docs/tracks/direct-wss/operator-package/run-carrier-vpn-matrix.sh`. Verbs:

### 3.1 `preflight` — profile arm + verification

```bash
./run-carrier-vpn-matrix.sh preflight \
  --operator YOTA \
  --expected-operator-numeric 25011 \
  --phone-vpn off \
  --host-vpn on \
  --host-vpn-service "Wireguard-Home"
```

`--host-vpn-service` is a **mandatory string parameter for every profile, regardless of `--host-vpn` value**. It names the macOS Network Configuration service (as it appears in `scutil --nc list`) that the operator has configured for THIS track. Persistence to **evidence** and to the handoff tarball is forbidden — only the derived proofs (Connected/Disconnected + relay-route-owned-by-service-iface bool) are recorded there, so the operator's VPN provider identity does not leak. Cross-process persistence for `smoke`/`full`/`resume` uses the segregated `.runtime` state store defined in §3.6 (per REDLINE-5 blocker 1).

Verified requirements (all fail-closed):

1. Devices connected (phone + emulator both `adb devices` visible).
2. APK SHA binding matches on both devices (§7 R3).
3. Phone active-data SIM carrier numeric equals `--expected-operator-numeric` (§7 R5). Reporter resolves via `SubscriptionManager.getActiveDataSubscriptionId()` + `TelephonyManager.createForSubscriptionId(id).simOperator` — NOT bare `getSimOperator()`.
4. Phone transport (§7 R6): `network_profile_report.has_transport_vpn == --phone-vpn` AND `wifi_enabled == false` AND `mobile_data_enabled == true`. Read via the new `network_profile_report` debug subcommand (§4.5), which reports `hasTransport(TRANSPORT_VPN)` from `NetworkCapabilities` + `WifiManager.isWifiEnabled()` + `TelephonyManager.isDataEnabled()` — no raw IP or interface name leaks.
5. Operator manual confirmations captured once and pinned to `PROFILE.json.operator_manual_confirmations`: `auto_data_switching_off: true` AND `other_sim_data_disabled: true`. Both required; either false → fail-closed.
6. Host VPN state matches `--host-vpn` for the CLI-supplied `--host-vpn-service` (§7 R7 + REDLINE-4 blocker 2).

    **Audit ROUND-30 amendment — Network Extension / TUN attestation.**
    A modern VLESS/Xray client (e.g. Happ) runs as a Network Extension packet
    tunnel: macOS routes default and relay traffic through its `utunN` while the
    legacy Network Configuration entry still reports `Disconnected`. The
    scutil-only ON check therefore returned `host_vpn_state_mismatch` on a host
    that was demonstrably tunnelled — an instrumentation false-negative, not an
    operator error and not a Direct WSS product failure.

    The ON case gains a SECOND, fail-closed path. The legacy `scutil --nc status`
    check runs FIRST and is unchanged, so every previously-attesting
    configuration keeps attesting on exactly the same evidence. Only when it
    cannot attest, and only when the operator has DECLARED a tunnel via the new
    optional `--host-vpn-tunnel <iface>`, is the tunnel path consulted. It
    requires ALL of:

    1. the declared interface has a tunnel shape (`utunN`/`tunN`/`ipsecN`) — a
       physical underlay such as `en0` is rejected at parse time;
    2. it is UP + RUNNING and carries an `inet` address — a dormant or torn-down
       `utunN` never attests;
    3. the **relay** route exits through it;
    4. the **default** route exits through it — a split tunnel that happens to
       carry the relay today is not the full-tunnel state the profile declares;
    5. no Connected Network Configuration service owns the relay-route interface
       (the same any-other-VPN reject as the OFF case, REDLINE-5 blocker 2) — if
       a legacy service owns that tunnel then the tunnel is its, not the declared
       application's.

    **Audit ROUND-30.1 — what this attests.** Conditions 1-5 prove an ACTIVE
    FULL TUNNEL. They do not, by themselves, prove which application owns it.
    Where `scutil` reports any interface for the declared service, the declared
    tunnel MUST be among them, which rejects a different Network Extension
    running under the same service name. Where the service reports nothing —
    the Happ case, invisible to `scutil --nc` — no trustworthy ownership
    evidence exists and the result is an **operator-declared** full tunnel. No
    provider identity is claimed, verified or recorded, and no evidence field
    asserts one. `host_vpn=on` reached by this path means "an active full
    tunnel the operator declared", not "provider X verified".

    **Audit ROUND-30.2 — three fail-open paths closed.** (a) The
    service-existence contract is UNCONDITIONAL: a declared tunnel never
    rescues a service that is absent, nor one whose `scutil` invocation fails.
    (b) The OFF guard is fail-closed on UNKNOWN state, not only on
    demonstrably-active state: a tunnel-shaped route whose interface cannot be
    inspected, and a default route that cannot be resolved at all, are both
    `host_vpn_unverifiable`. (c) Error precedence is fixed and
    machine-independent: the specific `host_vpn_other_service_active`
    diagnosis is evaluated BEFORE the tunnel-shape guard, so the accepted R25
    taxonomy is unchanged regardless of the host the gate runs on.

    **Audit ROUND-30.2 — migrating a pre-ROUND-30 arm.** Bare `resume` accepts
    `--host-vpn-tunnel` and persists it into `.runtime` for the profile being
    re-armed, so an attempt armed before this flag existed can continue its
    lineage. Old evidence is never modified and the retained `arm_token` plus
    `supersedes_attempt_id` are preserved. The migration is refused with a
    non-zero exit for a `host_vpn=off` profile or on disagreement with an
    already-armed declaration, and that refusal propagates — it is an operator
    error, not an ineligible profile to skip past.

    **Audit ROUND-30.3 — command authority and one tunnel-shape predicate.**
    (a) `scutil --nc list`, `--nc status` and `--nc show` carry INDEPENDENT
    authority for the selected service. Each invocation's exit status is kept
    and checked separately; a non-zero `status` or `show` is
    `host_vpn_unverifiable` in both the ON and the OFF case. R30.2 piped both
    commands into the parser and discarded their status, so a FAILED
    interrogation was indistinguishable from the benign "this service reports
    no interfaces" case (the Happ case) and the declared tunnel attested with
    `rc=0`. An empty string from a command that never answered is not evidence
    about the host.
    (b) There is exactly ONE tunnel-shape predicate,
    `^(utun|tun|tap|ppp|ipsec)[0-9]+$`, and every shape decision reads it. R30.2
    recognised `utun|tun|tap|ppp|ipsec` in the service parser but only
    `utun|tun|ipsec` in the host-OFF guard, so an active relay or default route
    through `ppp0` (and `tap0` by the same gap) passed as `host_vpn=off`.
    Host-OFF now fails closed on every tunnel shape. Error precedence is
    unchanged: the more specific `host_vpn_other_service_active` diagnosis is
    still evaluated before the tunnel-shape guard.
    The predicate is deliberately broader than the DECLARATION syntax
    (`utunN`/`tunN`/`ipsecN`, §3.6), which governs what may be persisted:
    recognising more shapes tightens OFF and cannot widen ON, because the ON
    path only ever sees an interface that already passed the narrower
    declaration check.

    **Audit ROUND-30.1 — the OFF case is symmetric.** A Network Extension that
    `scutil --nc` reports as Disconnected was invisible to both OFF conditions,
    so an active tunnel could pass as `host_vpn=off` and mislabel four
    profiles. OFF now fails closed whenever the relay OR default route exits
    through an ACTIVE tunnel device, regardless of who owns it: tunnelled
    traffic is not VPN-off.

    With no `--host-vpn-tunnel` the ON behaviour is byte-for-byte what it was,
    including the error code: nothing is widened by default. The declared
    interface is operator input and is **never** persisted to evidence — the §5
    denylist bans raw `utunN` strings — so it lives in `.runtime/wss3/<profile-id>/host-vpn-tunnel`
    (chmod 0600) beside `host-vpn-service`, typed once at `preflight` and read
    back by `smoke`/`full`. No APK change and no product Direct WSS change. Service MUST exist in `scutil --nc list` — absent = fail-closed `host_vpn_unverifiable` → `NOT_EVALUABLE` BEFORE arm. ON case: `scutil --nc status "<service>" == Connected` AND `route -n get <relay_host>` interface owned by that service. OFF case: `scutil --nc status "<service>" == Disconnected` AND `route -n get <relay_host>` interface NOT owned by that service.
7. Emulator's active route follows the host-side network path claimed by `--host-vpn` (§7 R8) — verified via HMAC egress fingerprint parity check between host and emu ONLY (phone excluded by design). Raw IPs never persisted; `checkpoint_key` never persisted (§7 R10).
8. Exactly one paired conversation on both devices (§7 R2).
9. Signed-prekey readiness `true` on both (§7 R4) via `signed_prekey_readiness` diag subcommand. **Bounded polling (Q5):** up to 60 s, 5 s step; fail-closed after 12 attempts.
10. **Reject completed profile (Q3):** if a directory matching this profile ID already contains a valid `matrix_completion.json` from a run whose `run_id` is on the current track manifest, `preflight` exits 3 with `profile-already-complete`. No `--force`. The only way to re-run a completed profile is `resume --retry --reason "<reason>"` (§3.4).
11. Operator types the profile-scoped confirmation:
    ```
    CONFIRM-YOTA-PHONE-OFF-HOST-ON
    ```
    Any typo / other string → fail-closed.
12. Pins are set (`pin=NONE` first, then `WSS` for the smoke cell) and cleared on `EXIT/INT/TERM` trap (§7 R16).

Success arms a per-profile evidence directory:

```
docs/tracks/direct-wss/operator-package/evidence/
  yota-phone-off-host-on-20260814T093012Z/
  yota-phone-off-host-off-20260814T101107Z/
  yota-phone-on-host-off-…/
  yota-phone-on-host-on-…/
  tele2-phone-off-host-on-…/
  tele2-phone-off-host-off-…/
  tele2-phone-on-host-off-…/
  tele2-phone-on-host-on-…/
```

Contents pinned in §5. The whole `evidence/` subtree stays gitignored per WSS-2 precedent (Q1).

### 3.2 `smoke` — routing + delivery gate

```bash
./run-carrier-vpn-matrix.sh smoke
```

Reads the arm from the most recent unfinished profile directory. Sends one WSS envelope in each direction (phone → emulator, emulator → phone) with `pin=WSS`. Requires (fail-closed):

- Direct WSS outer arm observed for BOTH envelopes: sender-side `sender_transport_decision(outer_transport=direct, inner_route=wss)` + `sender_wss_send_returned(dispatched=true)` + `sender_relay_ack_received` (sender↔relay ack). See §4.1 for the pinned WSS-2 schema.
- Recipient-side proof for BOTH envelopes: `recipient_deliver_received(dedup_gate=fresh)` + `recipient_message_persisted` + `recipient_ack_deliver_sent`. Delivery is proved by the recipient triplet, NOT by any client-side "Delivered" classification.
- **Dedup-gate tolerance (REDLINE-2 blocker 8, superseded by audit ROUND-30.18):** exactly one `dedup_gate=fresh` per ATTEMPT is REQUIRED, and attempts are numbered consecutively from 1 by the recipient. Trailing `duplicate` or `reack` events on the same correlation id are TOLERATED (they're normal breadcrumbs from watchdog requeue paths). Fail-closed conditions: two or more `dedup_gate=fresh` events sharing one attempt ordinal, an attempt with no fresh delivery, a gap in the ordinal sequence, or zero fresh deliveries in the direction. Trailing breadcrumbs are recorded to `smoke.log` but do not turn delivery RED.

  The original rule required exactly one fresh delivery per DIRECTION and declared two or more fail-closed. That was true while an envelope was delivered once, and stopped being true the moment a relay redelivery was recognised as a legitimate second ATTEMPT: the 2026-08-26 run produced exactly the shape the old sentence forbade, and the shape was correct. It is replaced here rather than left standing beside its replacement — one authority cannot hold two incompatible rules, and the older one was the fail-closed one, so it would have won every argument it lost on the merits. See § *The attempt is the unit of outcome*.
- `Unresolved / PENDING` classification is verifier-side only (§4.1); smoke gate reads the concrete WSS-2 events above and does NOT invent a client-side "Unresolved" verdict.
- **Pre-cell checkpoint (§4.6):** before firing either envelope, `network_profile_report` re-reads phone/host/emu state and equals `PROFILE.json`. Drift → abort with `network_profile_drift`.

Smoke GREEN advances the profile to `matrix` phase. Smoke RED → STOP for this profile; `full` refuses to run.

### 3.3 `full` — canonical 8-cell matrix

```bash
./run-carrier-vpn-matrix.sh full
```

Runs the WSS-2 canonical cell set inside the current profile (per architect REDLINE-1 blocker 2):

**8 cells total = 6 WSS cells + 2 REST controls.** 5 envelopes per cell → **40 envelopes total** per profile.

The 8 cells (frozen set; IDs match `CANONICAL_MATRIX_TRIPLES` + `CANONICAL_REST_CELL_IDS` in the accepted WSS-2 `verify-evidence.py:100-111`):

| # | Cell ID | Scenario | Direction | Pin | 5 envelopes |
|--:|---|---|---|---|--:|
| 1 | `wss.p2e.after-connect` | after-connect | phone → emu | WSS | 5 |
| 2 | `wss.e2p.after-connect` | after-connect | emu → phone | WSS | 5 |
| 3 | `wss.p2e.after-idle` | after-idle (300 s) | phone → emu | WSS | 5 |
| 4 | `wss.e2p.after-idle` | after-idle (300 s) | emu → phone | WSS | 5 |
| 5 | `wss.p2e.bg-fg` | bg-fg | phone → emu | WSS | 5 |
| 6 | `wss.e2p.bg-fg` | bg-fg | emu → phone | WSS | 5 |
| 7 | `rest.p2e.control` | REST control | phone → emu | REST | 5 |
| 8 | `rest.e2p.control` | REST control | emu → phone | REST | 5 |

**REST-disabled compatibility + unified completion schema (per REDLINE-2 blocker 6 + REDLINE-3 blocker 4):**

The 8-cell declaration list stays constant. Completion accounting uses three fields:

- `cells_declared = 8` — always. The frozen manifest set.
- `cells_expected_to_run ∈ {6, 8}` — equals the count of cells whose `blocked=false` at run-arm time. When the relay REST capability is disabled, both REST cells arm as `blocked=true` and this drops to 6.
- `cells_ran = N` — actually executed cells.

Verifier rule: `cells_ran == cells_expected_to_run` ⇒ complete (irrespective of whether that number is 6 or 8). `cells_ran < cells_expected_to_run` ⇒ RED integrity + NOT_EVALUABLE outcome.

Every cell records its own `blocked: bool` flag in `matrix_cells/<cell-id>/cell_verdict.json`. The matrix manifest carried on disk therefore always shows 8 rows; two of them may say `blocked=true`.

**Per-cell + post-matrix checkpoint (§4.6):** the same 5-item drift check runs BEFORE every cell fires its 5 envelopes, and AGAIN after cell 8 completes. Any drift → abort with `network_profile_drift`, `matrix_completion.json` is STILL written but with `abort_reason: "network_profile_drift"` and `cells_ran < 8`.

Cell-level completion writes a per-cell verdict; after cell 8 the atomic `matrix_completion.json` (§4.4) is fsync-then-rename written. Missing / malformed `matrix_completion.json` → STOP + fail-closed (§7 R19).

### 3.4 `resume` — safe re-entry

Two modes:

**Bare resume (auto-continue incomplete profiles):**

```bash
./run-carrier-vpn-matrix.sh resume
```

Enumerates all profile directories under `evidence/`. For each:

- If `matrix_completion.json` present + verifier GREEN + `run_id` matches current track manifest → skip (`already complete`).
- If `matrix_completion.json` present + `abort_reason` non-null → treat as complete-but-aborted; DO NOT auto-continue. Operator must explicitly `resume --retry --reason "..."` (below).
- If `matrix_completion.json` absent → re-arm the profile. Requires fresh confirmation string and creates a new UTC directory tagged with a fresh `attempt_id`. **The new attempt directory MUST also write `supersedes_attempt_id` = the previous incomplete attempt's `attempt_id` AND `retry_reason: "resume_incomplete"`** (per REDLINE-3 blocker 7 — bare resume is still a retry from the verifier's perspective; provenance is not optional just because the prior attempt died before writing `matrix_completion.json`). If no prior attempt directory exists → `supersedes_attempt_id: null`, `retry_reason: null` (first attempt).

Accidental bare `resume` on a fully complete profile is rejected without prompting (§7 R17), exit 3.

**Audit ROUND-30.3 — bare resume is fail-closed and single-profile.**

- **`--profile-id` is honoured in bare mode.** R30.2 parsed it and used it only in `--retry` mode, so a bare invocation always swept every profile. Given `--profile-id`, bare `resume` acts on that profile alone; naming a profile with no runtime arm, no evidence dir, or an already-complete latest attempt is an error (exit 4, or exit 3 for already-complete), not a silent skip.
- **`--host-vpn-tunnel` REQUIRES `--profile-id`.** One tunnel declaration may never be applied across every incomplete profile. Without an explicit target the command exits 4 before writing anything.
- **Validation precedes the first write.** Before the re-arm touches `.runtime`, the target must exist there at the contracted modes (§3.6), its latest attempt must be incomplete, its `PROFILE.json` must be readable, the runtime `arm-token` must equal that attempt's `arm_token` (invariant 6), and the profile-id implied by the recorded `carrier`/`phone_vpn`/`host_vpn` must equal the runtime directory being written.
- **Every unexpected non-zero propagates.** R30.2 forwarded only the exact pre-arm code `4` and continued past everything else, so a re-arm that failed at `rc=1` produced no successor, changed nothing, and `resume` still exited `0`. Eligibility is decided BEFORE the re-arm call; any status the re-arm itself reports is a failure and is returned unchanged.
- **No `rc=0` without a verified successor.** A re-arm that reports success must have produced a NEW attempt directory whose `PROFILE.json` carries `supersedes_attempt_id` = the superseded attempt, `retry_reason: "resume_incomplete"`, a fresh `attempt_id`, and the retained runtime `arm_token`. Otherwise `resume` exits 4. A bare `resume` that re-arms nothing exits 3.
- **The successor owns a verified preflight baseline.** Before the re-arm can commit, `EGRESS_FINGERPRINT.json`, `PROFILE_STATE.json`, `network_profile.json` and `signed_prekey_readiness.json` are copied byte-for-byte from the predecessor. Each source must be a non-empty regular UTF-8 JSON object covered by the predecessor's `SHA256SUMS.txt`; each destination is published from an exclusive 0600 temporary file. Missing, malformed or hash-mismatched input, an existing destination, or any publication failure rolls back the entire successor and any tunnel declaration this command created.
- **Partial failure leaves the previous state consistent.** A failure after the tunnel migration removes a declaration THIS command created (never one that was already armed) and removes a half-built attempt directory. Old evidence and runtime lineage are never modified.

**Audit ROUND-30.4 — a re-arm is one transaction.**

R30.3 satisfied the rules above only on the paths it happened to check. It derived the successor directory from a one-second UTC stamp and created it with `mkdir -p`, so a stamp landing in the same second as the previous attempt silently reused that directory and overwrote its `PROFILE.json` — the superseded attempt was left superseding itself. It also re-derived the successor with a lexicographic "latest" scan, which a future-dated directory outranks permanently, and it returned from a failed successor verification without unwinding anything.

- **The attempt directory is RESERVED before anything is mutated,** and the reservation is atomic: a bare `mkdir`, never `mkdir -p`. An existing path fails closed; it is never reused, never written into, never overwritten.
- **The new stamp must sort strictly after every existing attempt for that profile-id.** A stamp equal to the newest existing attempt (the same-second case) is retried a BOUNDED number of times while the clock advances and is then refused with exit 4. An existing attempt that sorts AFTER a stamp taken now is future-dated: waiting cannot resolve it, so it is refused immediately — before the tunnel migration and before any write.
- **The successor verification inspects the EXACT path the re-arm created,** handed over by the re-arm itself. It never re-derives the successor from a directory listing, so a future-dated or otherwise out-of-order directory cannot misdirect it.
- **Any failure after the migration unwinds the whole transaction:** the attempt directory this command reserved and, only if this command created it, the `host-vpn-tunnel` declaration. A declaration that was already armed belongs to the earlier attempt and is never touched. In every failure the previous `PROFILE.json` is byte-identical, no tunnel created by this command survives a failed transaction, and no extra attempt directory survives.

**Audit ROUND-30.5 — the transaction closes at the filesystem boundary.**

R30.4 unwound correctly for every failure its helpers *reported*. Two paths where the filesystem disagreed with a return code stayed open.

- **Ownership of the declaration is decided by OBSERVED state, not by the writer's exit code.** `wss3_atomic_write_0600` publishes with `mv` and only then applies the final `chmod 0600`, so it can fail with the file already on disk. R30.4 set the ownership flag only after a SUCCESSFUL migration, so that failure rolled the attempt back and left behind the declaration it had just created. The rule is now: absent before the command and present after it means this transaction created it, whatever the writer returned.
- **File TYPE is part of the runtime-state contract.** Every file `.runtime/wss3/<profile-id>/` holds is a plain 0600 regular file. A directory, symlink, FIFO or any other non-regular object at `host-vpn-tunnel` is CORRUPTED runtime state, never "no declaration". `wss3_read_tunnel` separates absence (nothing at the path) from corruption (something else at the path) and fails closed at exit 4 on the latter; `_wss3_resume_precheck` runs that check before the attempt is reserved and before any migration; `wss3_write_tunnel` and `wss3_atomic_write_0600` both refuse a non-regular target before publishing, so the `mv <file> <directory>` semantics — which moved the temp file *inside* the directory, chmod'ed the directory itself and returned success — can no longer occur.
- **An agreeing declaration is accepted READ-ONLY.** When the armed tunnel already equals the declared one, the migration leaves the file untouched rather than rewriting it with identical content.
- **`rc=0` from the writer is a postcondition, not a hope.** After a successful write the declaration must be a plain regular file, mode 0600, holding exactly the declared interface. Otherwise the write failed regardless of what the primitives returned.

**Audit ROUND-30.6 — runtime records are byte-exact single values.**

All three files `.runtime/wss3/<profile-id>/` owns — `host-vpn-tunnel`, `arm-token` and `host-vpn-service` — are ONE value and nothing else. R30.5 validated them with `printf '%s' "$v" | grep -qE '^…$'`, and `grep` matches PER LINE, so `garbage\nutun4` validated on the strength of its second line and was written into the declaration; `$(cat "$f")` then hid the rest, because command substitution strips all trailing newlines, so a file holding `utun4\n` read back as a clean `utun4` and the "byte-exact" postcondition compared equal. The same shape accepted `garbage\n<valid-uuid>` as an `arm_token`, which is the value that binds a retry to its lineage.

- **Framing bytes are corruption.** NUL, CR, LF — including a single trailing newline — every other C0 control byte and DEL make a file a corrupted record, not a value with a cosmetic tail. Corruption fails closed at exit 4 as `runtime_state_record_corrupt`, and for the tunnel it does so in `_wss3_resume_precheck`, before the attempt is reserved and before any migration.
- **`host-vpn-service` stays provider-neutral.** Spaces and non-ASCII characters are ordinary in a macOS service name and remain legal; only the framing bytes above are refused. No provider name is matched, tested for or special-cased anywhere in the tooling, and this round adds no support for, or behaviour specific to, any particular VPN client.
- **Syntax validation applies to the WHOLE value.** Each validator first proves the value is a single-value record, which is what makes its `^…$` pattern whole-value rather than per-line. `garbage\nutun4`, `utun4\ngarbage` and `utun4\n` are all rejected, as is a multi-line arm token carrying one conforming line.
- **Byte equality is proven on raw bytes.** Reads go through a record reader that decides on the file's bytes and only then uses `cat`, at which point the bytes are known to carry no newline and command substitution cannot lose anything; the writer postconditions compare the file's bytes against the value's bytes directly. `$(cat …)` is never evidence of byte equality.
- **One tunnel-syntax authority.** `wss3_validate_tunnel_iface` in `lib/wss3-runtime-state.sh` is the only definition of the declaration pattern; the `--host-vpn-tunnel` CLI guard in `lib/wss3-args.sh` calls it instead of carrying a copy, and a fixture pins that exactly one copy exists in the package.
- **Rollback tests presence the same way ownership does.** `_wss3_resume_rollback_attempt` uses `wss3_path_present` before and after removal, so a dangling symlink — invisible to `[ -e ]` — created by this command is rolled back like any other declaration.

**Explicit retry (Q3 + Q4 + REDLINE-2 blocker 7):**

```bash
./run-carrier-vpn-matrix.sh resume --retry \
    --profile-id yota-phone-off-host-on \
    --reason vpn_provider_change
```

Only path to re-run a completed OR previously-aborted profile. Requirements (all fail-closed):

- **`--profile-id` is REQUIRED.** No implicit "most recent" selection; operator must name exactly which profile is being retried.
- **`--reason` is an ENUM**, not free text. Accepted values (extensible only through explicit contract amendment):
  - `vpn_provider_change` — operator swapped VPN clients between attempts.
  - `sim_reseat` — phone SIM was physically re-seated / re-provisioned.
  - `sdk_environment_recovery` — Mac SDK / emulator image issue triggered abort; retry after fix.
  - `mid_run_operator_interrupt` — SIGINT/SIGTERM required; not a track abort.
  - `network_transient_recovery` — a checkpoint drift or timeout that the operator has confirmed is not a persistent state.
  - `resume_incomplete` — reserved for bare-`resume` continuation of an incomplete prior attempt (§3.4 bare-resume path writes this value; the explicit-retry CLI rejects it because bare resume covers the incomplete-attempt case).
  - Any other string → fail-closed.
- Creates a NEW directory with a fresh `attempt_id` UUIDv4 + `supersedes_attempt_id` linking back to the previous attempt (completed OR aborted — both cases need the link). **Every attempt after the first for a given profile-id must carry `supersedes_attempt_id` non-null and `retry_reason` non-null.**
- **`arm_token` semantics on retry (per REDLINE-7 blocker 1):** the retry does NOT mint a new `arm_token`. The new `PROFILE.json.arm_token` is COPIED from `.runtime/wss3/<profile-id>/arm-token` (the retained runtime state from the original preflight). This keeps `arm_token` a **per-profile-id** identity rather than a per-attempt identity, so the runtime-state binding invariant 6 (`arm-token` must match `PROFILE.arm_token`) survives cleanly across the retry chain without needing to rewrite the runtime file. A genuinely fresh `preflight` (first attempt for a profile-id, or after `cleanup` wiped the runtime for that profile-id) mints a new UUIDv4. Verifier: attempts that share a `supersedes_attempt_id` chain MUST also share the same `arm_token`; a chain whose `arm_token` values disagree between linked attempts → profile `NOT_EVALUABLE` (retry chain integrity, added below in §3.4 verifier lineage discipline).
- Both attempts remain on disk. `compare` uses the newest `attempt_id` for the row + flags `retry_lineage_present: true` in the report footer.

**Verifier lineage discipline (per REDLINE-3 blocker 7).** `verify-evidence.py --track wss-3` walks the attempt DAG per profile-id and fail-closes each profile as `NOT_EVALUABLE` if ANY of the following holds:

- `latest_wins` fallback would be required — i.e. two or more attempts exist and any pair lacks the `supersedes_attempt_id` link that would linearise them.
- A cycle exists: attempt A `supersedes_attempt_id: B` AND attempt B `supersedes_attempt_id: A` (or any longer cycle).
- A fork exists: two distinct attempts both name the same `supersedes_attempt_id` (branching lineage; the retry chain must be strictly linear).
- A `supersedes_attempt_id` points to a `profile_id` different from the attempt's own `profile_id` (cross-profile lineage — never allowed).
- A `supersedes_attempt_id` references an `attempt_id` that does not exist in the evidence tree (dangling parent).
- Any attempt has `supersedes_attempt_id != null` AND `retry_reason == null`, or vice versa (both required together).
- Two attempts linked by `supersedes_attempt_id` disagree on `arm_token` (per REDLINE-7 blocker 1 — retry must copy the retained runtime `arm-token`, not mint a new one; disagreement means either the retry minted a fresh token in violation of §3.4, or the runtime state was tampered with).

- **The operator is NEVER instructed to delete duplicate evidence.** Duplicates must resolve through the lineage-link machinery above; the failure matrix (§8) explicitly does NOT recommend deletion.

### 3.5 `compare` — final 8-row report

```bash
./run-carrier-vpn-matrix.sh compare
```

Wraps `docs/tracks/direct-wss/operator-package/compare-vpn-matrix.py`. Reads every profile directory whose UTC ≥ latest track-start marker.

- Requires all 8 profiles present with valid `matrix_completion.json`.
- Any missing profile → row = `NOT_EVALUABLE / NOT_EVALUABLE`, exit non-zero.
- Any profile with `abort_reason` in `matrix_completion.json` → row = `RED / NOT_EVALUABLE` (integrity RED because the intended cells didn't all run; outcome not evaluable per §7 R18).
- Any profile with duplicate attempts lacking `supersedes_attempt_id` provenance → row = `NOT_EVALUABLE / NOT_EVALUABLE`.
- Any profile where ANY cell is RED / Unresolved (per verifier §4.1) → `evidence_integrity` MAY be GREEN (if all events schema-valid), but `product_outcome` is **`RED`** — not `partial-GREEN`. Per architect REDLINE-1 blocker 7 there is no such class as partial-GREEN.

### 3.6 Runtime state store — cross-process `--host-vpn-service` lifetime (per REDLINE-5 blocker 1)

`preflight`, `smoke`, `full`, and `resume` are separate shell invocations — each spawns a fresh process, so a variable set in `preflight` does not survive into `smoke`. The mandatory `--host-vpn-service` (§3.1) must therefore be relayed between verbs. It cannot go into `PROFILE.json` or any evidence file (§5 denylist — the raw service string leaks VPN provider identity). Solution: a segregated **runtime state store** distinct from evidence.

**Path shape:**

```
docs/tracks/direct-wss/operator-package/.runtime/
  wss3/
    <profile-id>/
      host-vpn-service           # single-line UTF-8, no trailing newline, chmod 0600
      arm-token                  # UUIDv4 written at preflight; verbs cross-check
```

**Invariants (all fail-closed):**

1. `.runtime/` is a **peer** of `evidence/` under `operator-package/`, NOT a child of it. `evidence/` remains the sole source for the handoff tarball; `.runtime/` is never packaged.
2. `.runtime/` and every path under it are added to `.gitignore` (a fresh `.gitignore` line ships with the operator package). CI-verifiable: existing WSS-2 gitignore-scope shell fixture is extended to cover this path.
3. Every file created under `.runtime/wss3/<profile-id>/` is `chmod 0600` on write. Both `.runtime/wss3/` **AND** each `.runtime/wss3/<profile-id>/` directory are `chmod 0700` on creation (per REDLINE-6 blocker 1 — the per-profile directory needs the same 0700 as its parent so a hostile process cannot enumerate other profiles' state files even if they lived momentarily at 0644). The orchestrator refuses to read a file whose mode is not `0600`, and refuses to enter a directory whose mode is not `0700` (both fail-closed with `runtime_state_mode_violation`).
4. `<profile-id>` component is the same string as the evidence directory prefix (`<carrier-lc>-phone-<off|on>-host-<off|on>`) — NOT the UTC-suffixed dir. Runtime state is per-profile-triple, not per-attempt.
5. `preflight` mints a fresh UUIDv4 `arm_token`, writes it to `PROFILE.json` (§5 schema), and atomically writes `host-vpn-service` + `arm-token` to `.runtime/wss3/<profile-id>/` via write-tmp-then-rename with fsync. If a stale runtime file exists from an earlier abandoned track (no matching evidence dir on disk, or the evidence dir's `PROFILE.json.arm_token` doesn't match the runtime file's `arm-token`), `preflight` refuses with the pre-arm CLI failure `runtime_state_stale` and prints the remediation command (§3.6 cleanup verb below).
6. `smoke` / `full` / `resume` (all modes) read `host-vpn-service` and `arm-token` from `.runtime/wss3/<profile-id>/`, run the §4.6 host-VPN checkpoint, and reject the invocation with one of the pre-arm CLI failures below if any check fails.

**Runtime-state error classification (per REDLINE-6 blocker 2).** These codes are NOT `matrix_completion.abort_reason` values — they are **pre-arm CLI failures** that stop the verb before it touches any evidence. They print to stderr, exit non-zero (documented exit code per code below), and never write a `matrix_completion.json`:

**Canonical runtime-state error table (audit ROUND-30.7).** This is the complete finite set. Every row is a pre-arm CLI failure with exit 4; none writes `matrix_completion.json`. The constants in `lib/wss3-runtime-state.sh`, the operator README and this table are the same list — distinct causes are never conflated, and no diagnostic on any of these paths may include the record's value in any form (see the privacy rule below the table).

| Code | Record | Condition | Mutation allowed | Operator remediation |
|---|---|---|---|---|
| `host_vpn_service_missing` | service | the `host-vpn-service` record is absent | none | re-run `preflight` for this profile, or `resume --retry` if an attempt already exists |
| `runtime_state_arm_token_missing` | arm-token | the `arm-token` record is absent | none | the arm is gone; re-run `preflight` — a retry cannot mint a token |
| `runtime_state_record_type_violation` | any | the path exists but is not a plain regular file, a container is not a real directory, or a path component is a symlink | none | inspect the object by hand; the tooling will never modify or remove it |
| `runtime_state_mode_violation` | any | mode ≠ 0600 on a record, ≠ 0700 on a container | none | restore the modes, or `cleanup` this profile and re-`preflight` |
| `runtime_state_record_corrupt` | any | framing bytes (NUL, CR, LF, other C0, DEL), invalid UTF-8, empty, or over the length bound | none | the record is unusable; `cleanup` this profile and re-`preflight` |
| `runtime_state_record_syntax` | any | a well-formed record whose value is wrong for its kind — a leading `-` in a service, a non-UUIDv4 token, an interface outside `utunN`/`tunN`/`tapN`/`pppN`/`ipsecN` | none | correct the declaration and re-run the verb |
| `host_vpn_service_stale_arm` | arm-token | a VALID token that belongs to another attempt: runtime `arm-token` ≠ `PROFILE.json.arm_token` | none | `resume --retry --profile-id … --reason …`, which re-links the lineage |
| `host_vpn_service_mismatch` | service | CLI `--host-vpn-service` disagrees with the stored value | none | drop the flag and let the verb read the armed value, or re-arm |
| `runtime_state_stale` | any | runtime state exists with no matching evidence directory | none | `cleanup` this profile, then `preflight` |

**Audit ROUND-30.8 — the transaction boundary.**

R30.7 closed what a record may contain; what stayed open was how far the transaction extends, what it restores, and when containment runs relative to the first filesystem call.

- **`preflight` stays open through the mandatory baseline evidence.** It committed before the baseline checkpoint, so a failure there left an armed profile, an attempt directory and a `PROFILE.json` with no evidence behind them — measured as `svc=yes arm=yes attempts=1 profile=1` after `rc=1`. The commit now happens only after `PROFILE.json`, `EGRESS_FINGERPRINT.json`, `PROFILE_STATE.json`, `network_profile.json` and `signed_prekey_readiness.json` all exist, are non-empty, parse, and no staging file is left behind.
- **Temporary-file cleanup COMPOSES with the one trap.** Installing a second `EXIT/INT/TERM` trap for a scratch file replaced both the transaction unwind and the pin cleanup for the rest of the run. Scratch files are registered with the shared handler instead; exactly one trap is ever installed.
- **A failed arm restores the exact pre-state.** The unwind recorded only whether each record existed, so a failure on the second write left a service from the new arm beside a token from the old one. Existing records are snapshotted — bytes and mode — before the first write and restored verbatim; records the call created are removed.
- **Containment is lexical and runs BEFORE any mutation.** It ran after `mkdir -p` and `chmod 0700`, so rejecting a symlinked profile directory still changed the mode of the foreign directory behind it; and because it compared resolved paths, a symlink ABOVE the runtime root resolved identically on both sides and let a record be written outside the store with `rc=0`. Every component below the store's anchor is now checked for a symlink before any `mkdir`, `chmod`, write, rename or removal, and directories are created one level at a time with a bare `mkdir`. The walk starts at the anchor rather than at `/` because the operator owns the layout above the store, where symlinks are legitimate — `/tmp` is one on macOS.
- **Every baseline writer propagates.** `EGRESS_FINGERPRINT.json`, `PROFILE_STATE.json`, `network_profile.json`, `signed_prekey_readiness.json` and the emulator snapshot each have an explicit exit status, are staged to `<name>.tmp`, validated as JSON and renamed into place. A failed write is a failed preflight, not a file the verifier discovers missing later.

**Audit ROUND-30.9 — the outer transaction journal.**

R30.8 restored a record only inside `wss3_write_arm` and discarded the snapshot as soon as the pair published, so a failure later in the preflight had nothing to restore from and left the profile carrying the failed run's arm. Two rules now govern every runtime mutation.

- **A record that already exists is CANONICAL or the operation fails closed.** Canonical means contained, a plain regular file, mode 0600, and well-formed for its kind. A symlink, dangling symlink, directory, FIFO, wrong-mode file or byte-corrupt record is refused BEFORE the first mutation and left exactly as found. This tooling never repairs and never deletes an object it does not recognise as its own — R30.8 silently replaced a `0644` service file and fixed its mode on the way past, and its empty-tunnel path deleted a foreign symlink outright.
- **What is canonical is journalled.** Existence, type, exact bytes and mode of every runtime record are captured once, before the first mutation, and held until the baseline commits. Every failure after that point — writer, verification, `EXIT`, `INT`, `TERM` — restores each captured record byte-for-byte and mode-for-mode, removes only objects this invocation created (the attempt directory included), removes staging artifacts, leaves foreign and malformed objects untouched, and wipes the journal so no raw-value snapshot survives anywhere.

**Removal is a mutation.** The empty-tunnel path goes through the same authority as a write: containment, type, mode and syntax are checked before anything is unlinked.

**One publisher for all mandatory evidence.** `PROFILE.json`, `EGRESS_FINGERPRINT.json`, `PROFILE_STATE.json`, `network_profile.json` and `signed_prekey_readiness.json` are each staged to `<name>.tmp`, validated as JSON, renamed into place and verified. `PROFILE.json` was the last one written straight to its final path.

**Audit ROUND-30.10 — the runtime transaction protocol.**

One state machine governs every runtime mutation:

```
IDLE -> LOCKED -> JOURNALED -> MUTATING -> VERIFIED -> COMMITTED
                       \           |          /
                        `---> ROLLING_BACK <-'
                                |        \
                              IDLE        ROLLBACK_FAILED
```

- **LOCKED.** An exclusive per-profile lock (`<profile>/.lock`, taken with an atomic `mkdir`) is acquired before the journal is captured. A second `preflight` or `resume` for the same profile fails closed with `runtime_state_transaction_locked` having mutated nothing. Different profiles are independent — the lock lives inside the profile directory.
- **JOURNALED.** Canonical pre-state — existence, type, exact bytes, mode — is captured for `host-vpn-service`, `arm-token` and `host-vpn-tunnel` by ONE authority in `lib/wss3-runtime-state.sh`. There is no second journal and no private snapshot: R30.9 had three, one of them an untracked `mktemp -d` that was `755` with `644` bytes inside holding a service name, which a signal between publications stranded in the temp directory. Journals live under a configurable base, are `0700`, and every snapshot file is `0600` before any raw byte reaches it.
- **ROLLING_BACK.** Every restore and every removal validates the object's type before mutating and verifies the postcondition afterwards — exact bytes, exact mode, correct type, or verified absence. No mandatory recovery step is guarded by `|| true`. Success is never reported for a partial restoration.
- **ROLLBACK_FAILED is terminal.** When any restore postcondition fails, the recovery journal is preserved INSIDE the protected store at `<profile>/.recovery/` (`0700`, files `0600`), a `BLOCKED` marker is written, every later verb refuses that profile with `runtime_state_rollback_failed`, and diagnostics stay redacted. The only copy of the recovery data is never discarded.

**Signals are their own events.** `EXIT`, `INT` and `TERM` have distinct handlers. `INT` and `TERM` always request the rollback, never consult the previous `$?`, and terminate with 130 and 143 respectively; the cleanup body runs exactly once. R30.9 served all three from one handler gated on `$? != 0`, so a signal arriving between commands was swallowed and the transaction continued.

**Privacy rule for every row.** A failure on any of these paths may emit the code, the record kind, the file path and a redacted marker — never the value, and never a reversible or searchable derivation of it (hex, base64, escaped form, hash or fingerprint). A service name and an interface name are low-entropy enough to be recovered from a digest, so digests are banned too, exactly as §5 bans the raw strings from evidence.

### Clearing a pin is an operation with a result (audit ROUND-30.11/30.12)

A pin is cleared with `pin --es pin none --es run_id <run> --es cell_id <cell>.clear`.
The receiver validates `run_id` and `cell_id` before it acts on ANY pin value,
`none` included, so a clear sent without them is rejected on a real device — the
form used before ROUND-30.11 never took effect.

**The clear is confirmed, not assumed.** A clear counts as done only when the
device emits `diagnostic_pin_active … pin=none` for that emitter, that run and
the derived cell id. The producer clears strictly after every direction and
every cell that COMPLETED, including the last one: a stale pin is inherited by
whatever runs next, and a GREEN written over an unconfirmed clear is a claim
nobody checked. Best-effort clearing survives only where the run is already
failing and in the EXIT trap, so a device that cannot be reached does not
overwrite a verdict already being written.

**The derived identity `<cell>.clear`.** The clear never carries its parent's
cell id: that would put a second `diagnostic_pin_active` into the cell's
dual-party set, where exactly one per party is allowed, and a healthy run would
turn RED. `.` is inside the receiver's permitted cell-id characters, so the
derived form validates.

**Completed-parent ownership.** The derived id is not a way out of the
verifier's field of view. Every clear breadcrumb must belong to a direction or
cell that ACTUALLY RAN; a clear naming a blocked cell, a `PENDING` row, a cell
the matrix never reached, or any other parent is an orphan and is reported. The
verifier is given the set of parents that ran — not the canonical set — because
otherwise the orphan rule could never fire.

**Preserved delivery counters.** A cell whose cleanup failed keeps its real
`envelopes_dispatched`, `delivered_count` and derived verdict, and records
`abort_reason=pin_clear_unconfirmed` beside `post_cell_clear_emitter`. The
envelopes were dispatched and delivered; reporting less than was observed is
false evidence. Every other cell-level `abort_reason` still means the cell never
ran and still requires zero counters with `verdict=Unresolved`.

**Exact matrix-to-cell consistency.** `pin_unconfirmed` and
`pin_clear_unconfirmed` are claims about ONE cell. When
`matrix_completion.abort_reason` is either of them, exactly one cell must record
that same reason: zero corroborating cells, more than one, or a cell recording a
different reason are each findings. Every cell after the aborting one must be
`PENDING`. A side named as an unconfirmed clear that nevertheless produced a
`pin=none` breadcrumb is also a finding — the verdict and the log disagree, and
the verdict is the one making a claim.

The distinct `matrix_completion.abort_reason` enum (§4.4) covers **mid-run drift** — `host_vpn_unverifiable`, `host_vpn_other_service_active`, `network_profile_drift`, `mixed_address_family`, `path_divergence_host_emu`, `apk_sha_drift`, `signed_prekey_regressed`, `pair_lost`, `carrier_attribution_lost`, `wifi_active_or_mobile_data_off`, `verifier_schema_violation`, `operator_interrupt` — and always writes a completed `matrix_completion.json` with `cells_ran` set to what actually ran.

7. **Cleanup path (per REDLINE-6 blocker 2, REVISED per REDLINE-7 blocker 1).** The `EXIT/INT/TERM` trap in every verb **NEVER** automatically removes `.runtime/wss3/<profile-id>/*` on ANY terminal state — including `COMPLETE`. Runtime state is preserved across ALL exits (terminal or not) until one of the two explicit removal paths in invariant 8 fires. Rationale: `resume --retry --profile-id <id> --reason <enum>` is the ONLY supported way to re-run a `COMPLETE` profile (§3.4 explicit-retry contract); and every `resume` mode requires `host-vpn-service` + `arm-token` from `.runtime/` (invariant 6). Automatically deleting on `COMPLETE` would deterministically block retry with `host_vpn_service_missing` exit 4 — that was the Round-6 contradiction. Retention until explicit removal is the resolution. The trap still fires (releases the pin, kills captures) — it just doesn't `rm` the runtime files.
8. **Track-end cleanup verb (REVISED per REDLINE-7 blocker 1):** `./run-carrier-vpn-matrix.sh cleanup` is the ONLY entry point that removes runtime state:
   - **implicit cleanup:** the last step of a successful `compare` (all 8 profile rows are `GREEN/GREEN`) removes `.runtime/wss3/` in its entirety. For a `compare` that returns a mixed report, the operator must explicitly accept the report via a typed confirmation before the implicit cleanup runs — otherwise `.runtime/` is retained so retries remain possible.
   - **explicit `cleanup` (bare):** `./run-carrier-vpn-matrix.sh cleanup` deletes every `.runtime/wss3/<profile-id>/` whose evidence dir contains a `matrix_completion.json` with `abort_reason=null` (i.e. only fully-COMPLETE profiles). This is the operator's declaration that they have accepted the COMPLETE outcome and give up the retry lifeline for those profiles. Aborted-profile runtime is preserved — bare `cleanup` never touches it.
   - **explicit `cleanup --all`:** additionally removes runtime for profiles with `abort_reason != null` (aborted/interrupted). Used only when the operator has decided to abandon retries for those profiles too.
   - `cleanup` NEVER touches runtime state whose evidence dir has NO `matrix_completion.json` (still-in-progress) — irrespective of `--all`.
9. **Alternate policy accepted but rejected here.** The alternative — require `--host-vpn-service` on every verb — needs the same fail-closed check that the value hasn't silently changed between verbs, so it does not remove state, it just forces the operator to retype the service every time and pushes state into their shell history (where it leaks worse than a `0600` file inside the package). The `.runtime` state file is the cleaner boundary.

**Evidence isolation invariant (per REDLINE-6 blocker 3).** The literal CLI option name `host-vpn-service` LEGITIMATELY appears in the operator-package scripts, README, and this contract sheet — a regex that matched it would fail-red on every run and provide no signal. The invariant is scoped precisely to the runtime-generated **raw service VALUE**:

- Evidence sweep uses **fixed-string** search of the actual runtime value: `grep -rF -- "$RAW_SERVICE_VALUE" evidence/` MUST return zero matches at track end (`grep -F` disables regex; `--` prevents option-injection from a `--`-prefixed service name).
- Handoff tarball sweep uses the same fixed-string search: `tar -xzf handoff-*.tar.gz -O | grep -F -- "$RAW_SERVICE_VALUE"` MUST return zero matches.
- Separately: `tar -tzf handoff-*.tar.gz | grep -E '(^|/)\.runtime/'` MUST return zero matches — the `.runtime/` subtree must be absent from the tarball entirely (defence in depth against packaging bugs).
- The literal option-name string `host-vpn-service` is EXPLICITLY allowed to appear in packaged scripts, docs, and README under `operator-package/` — the invariant guards the value, not the name.
- **Failure reporting discipline:** on ANY failure of the above sweeps, the test/CI output MUST NOT echo the raw `$RAW_SERVICE_VALUE`. Only the offending file path + a redacted marker (`<RAW_SERVICE_VALUE_REDACTED>`) may appear in stderr/exit output. Prevents the failure itself from leaking what it was checking against.

Test coverage: §9 shell fixture 46 pins fixed-string search discipline; fixture 47 pins the tar-content absence check; the "no raw value in failure output" invariant is asserted by both.

---

**Audit ROUND-30.10 (rework) — the transaction protocol, corrected.**

The R30.10 protocol was returned with six defects. All six were reproduced
locally before any change was made, and each is closed at its cause.

**Ownership is newline-safe and scoped.** The list of objects a transaction
created was walked with an unquoted command substitution, which splits on
whitespace. Every path in the operator's own store contains a space, so a
created object survived a rollback that reported success, and the leading
fragment of the path was passed to `rm -rf` on its own — for `<x>/a b/...`
that means deleting an unrelated `<x>/a`. The list is now read line by line
with `IFS` cleared, a path containing a newline is refused at registration
rather than split at recovery time, and nothing is removed until it is proven
to sit inside a container the transaction has declared. The profile directory
is declared automatically; a caller that reserves an attempt directory under
the evidence root declares that container explicitly.

**A transaction is bound to one root and one profile.** `wss3_txn_active`
answered only "is a transaction open", so a writer aimed at profile B, called
while profile A's transaction was open, wrote B under A's journal: B's
pre-state was never captured and A's rollback did not restore it. Writers now
require `wss3_txn_owns`, a foreign transaction is refused, and a second
`begin` over an open transaction is refused instead of overwriting the globals
and stranding the first journal and its lock.

**Every mutation path enters the protocol.** The tunnel writer took no lock,
and a first-ever arm opened no transaction because there was nothing to
snapshot — so the claimed per-profile exclusivity did not cover the first
write, and `resume` reserved an attempt directory and migrated a tunnel
outside any transaction at all. `resume` now takes the lock and the journal
before its first read; both writers join the owning transaction or open and
close their own. "Nothing to restore" is not "nothing to serialise".

**Recovery data is verified before the original is destroyed.** A failed
rollback checked only that a `BLOCKED` marker had appeared, so a copy that
reported success while copying nothing satisfied it and the only copy of the
recovery data was then deleted. The copy is now verified for closure and byte
parity against the journal before anything is removed.

**A failed preservation is still fail-closed.** When preservation itself
failed, the lock was released and every global cleared, leaving no persistent
evidence that the profile was unsafe — the marker that would have said so is
exactly what could not be written. The profile lock is now RETAINED in that
case, which requires no filesystem success and therefore cannot fail, and a
marker is written inside that existing directory. Either one refuses the next
verb; the lock does so even when nothing can be written at all.

**Commit proves its own cleanup.** The journal was removed with `|| true`, the
lock release had no postcondition, and the function returned zero regardless —
so a commit could report success while a 0700 journal holding record bytes and
a live profile lock both survived. Both are verified and a failure is
reported.

**The state machine is the executed one.** `VERIFIED` and `COMMITTED` were
named in the diagram and never assigned, and `MUTATING` was set only when a
record was created, so overwriting an existing record left the machine reading
`JOURNALED` while the store had already changed. Every state is now entered by
the code that performs the corresponding step, an overwrite is a mutation, and
a mutation after a verification returns the machine to `MUTATING`.

Two error codes join the canonical table:

| Code | Meaning | Remediation |
|---|---|---|
| `runtime_state_transaction_mismatch` | A writer was reached while a transaction for another profile was open, a `begin` was nested, or an object outside the transaction's declared scope was registered. | Re-run the verb on its own. This is a caller defect, not operator state; nothing was mutated. |
| `runtime_state_transaction_cleanup_failed` | The write landed but the journal or the profile lock could not be verifiably released. | Remove the reported journal directory and the profile `.lock` by hand after confirming no run is active. The runtime records themselves are correct. |

Test coverage: §9 shell fixtures 169-174 pin these six, one fixture per
reproduction: newline-safe scoped rollback, one-profile binding with
nesting refused, every writer inside the protocol, verified recovery
preservation with a fail-closed marker that survives its own failure,
verified commit cleanup, and an executable state machine.

**Audit ROUND-30.10 (closure) — ownership, exclusion, and what "terminal" means.**

Six defects were returned against the rework. Each was reproduced locally before
any change and closed at its cause.

**Creating a container is a mutation.** The store and the profile directory were
established before the lock and the journal existed, and nothing recorded them,
so a rollback of a first-ever profile — and a `begin` that failed after creating
them — left an empty runtime profile behind, which the next preflight then
refused as stale runtime state. Every container level a transaction creates is
now recorded and taken back on rollback with `rmdir`, never a recursive delete:
an empty container this run made is removable, and a container anything else has
since put something into is left alone.

**A held profile is not readable.** The lock stopped writers and nothing else,
so a reader could take a value out of a profile another process was in the
middle of changing. Readers, writers and `cleanup` now all refuse a profile
whose `.lock` exists, whether or not it carries a `BLOCKED` marker; the only
exception is the transaction that provably owns that lock, which is how `resume`
reads its own profile between its `begin` and its first write. A transaction is
also active from the moment the LOCK is taken rather than from the moment the
journal appears, so a signal arriving between the two is inside the protocol
instead of outside it.

**`cleanup` obeys the protocol.** It reached `rm -rf` with no checks at all, so
a routine housekeeping verb destroyed a profile left in the terminal
ROLLBACK_FAILED state — the marker and the only copy of the recovery data with
it — and could delete a profile another run was holding. Removal now refuses a
blocked profile and a held one, and takes the lock for the deletion itself.

**The lineage parent is re-read under the lock.** The parent attempt and its
`attempt_id` were chosen before the lock existed and never re-checked once it
was held, so two `resume` runs could queue on the lock having both read the same
latest attempt and each create a successor of it — one parent with two children.
The parent must still be the latest attempt at the moment the transaction owns
the profile, or the run stops without mutating anything.

**The state machine refuses moves, it does not merely record them.** Every state
was assigned and every order permitted, so an untouched, unverified transaction
committed straight out of JOURNALED. There is now an explicit table; every verb
asks it first and fails closed on an unlisted move. COMMITTED is reachable only
from VERIFIED.

**Terminal state and process state are two different things.** This distinction
is the one the previous round got wrong in both directions, so it is stated
plainly:

- The TRANSACTION's terminal state is ROLLBACK_FAILED. It ends that
  transaction; nothing continues from it.
- The PROFILE's block is durable and lives ON DISK — `.recovery/BLOCKED`, or the
  retained `.lock` when preservation itself failed. This is the only thing that
  survives the process, and it is what refuses every later verb for that
  profile. No in-process state can grant or clear it.
- The PROCESS is not poisoned by either. After a failed rollback has been fully
  released, the process may open a transaction on a DIFFERENT profile — but only
  after proving the damaged profile really is blocked on disk. If that proof is
  missing the reset is refused and the process stays terminal, because a
  process that forgot about an unprotected damaged profile is worse than one
  that stops.

Treating ROLLBACK_FAILED as a process-wide terminal state instead made any
long-lived process unable to open a transaction again after a single rollback
failure anywhere — not fail-closed, just broken.

**Resolving a stuck lock by hand.** A `.lock` left by a killed run refuses reads
and writes for that profile until an operator clears it. That is deliberate.
Clearing it is not "delete the directory":

1. Prove no run is live for that profile — check for a running
   `run-carrier-vpn-matrix.sh` on this machine before anything else. A lock with
   a live owner must never be removed; the correct action is to wait or to stop
   that run.
2. Preserve first. If `<profile>/.recovery/` exists, copy it somewhere outside
   the store and keep it: it is the only copy of the pre-state of a failed
   rollback. If a journal path was printed by that run, preserve that too.
3. Read `<profile>/.lock/BLOCKED` if present. Its presence means preservation
   itself failed, so the recovery data is still in the journal the run named —
   that path is the thing to save.
4. Only then remove the `.lock` directory. Leave `.recovery/` in place until the
   runtime state has been reconciled; removing it un-blocks the profile and
   throws away the evidence needed to reconcile it.

**Gates.** The authoritative shell gate is the real interpreter on the target
platform:

```bash
/bin/bash tests/target-mac-gate.sh
```

It must report stock `/bin/bash` 3.2, every derived file parsed with
`syntax_failures=0`, `suite_rc=0`, and a final `TARGET_MAC_GATE=GREEN`. A run
under any other interpreter is host-local only and prints
`TARGET_MAC_GATE=HOST-LOCAL` instead; it is not a substitute for the target
result and must not be reported as one. The static portability scan in the shell
suite is a development check that walks every shipped file — it catches the
known bash-4 constructs and the `case`-inside-`$( )` parser hazard early, and it
settles nothing.

Two error codes join the canonical table:

| Code | Meaning | Remediation |
|---|---|---|
| `runtime_state_transaction_mismatch` | A writer was reached while a transaction for another profile was open, a `begin` was nested, an object outside the transaction's declared scope was registered, or an undocumented state move was attempted. | Re-run the verb on its own. Nothing was mutated. |
| `runtime_state_transaction_cleanup_failed` | The write landed but the journal or the profile lock could not be verifiably released. | Follow the stuck-lock procedure above. The runtime records themselves are correct. |

Test coverage: §9 shell fixtures 175-181 pin this closure, one per
reproduction: owned container creation, a live lock that stops readers,
writers and removal alike, cleanup inside the protocol, the lineage parent
re-read under the lock, an exhaustively checked transition table, the
shipped target-platform gate, and the proof of containment a process must
produce before it resumes work after a failed rollback.

---

## 4. Product-behaviour contract

### 4.0 Operator flow

~20-30 min active, 2.5-4 h wall clock:

1. Insert Yota SIM. Ensure phone + emulator + Mac in known state.
2. Set `phone_vpn=off`, `host_vpn=on`. Run `preflight`. Type confirmation. Wait ~30 s.
3. Run `smoke`. Wait ~90 s.
4. Run `full`. Wait ~15-20 min. Do not touch device.
5. Switch to profile 2 (`phone_vpn=off`, `host_vpn=off`). Repeat steps 2-4.
6. Profile 3, profile 4.
7. Swap Yota → Tele2. Repeat all four profiles.
8. Run `compare`. Get final 8-row report.

The APK stays installed the whole track. Identities are NOT re-onboarded. Pairing done once at track start.

### 4.1 Pinned WSS-2 event schema (per architect REDLINE-1 blocker 1 + REDLINE-2 blocker 2)

The verifier and every operator-package script consume ONLY these emitters. The whitelist is a **verbatim copy** of the closed sets in the accepted WSS-2 `docs/tracks/direct-wss/operator-package/verify-evidence.py:112-137` — the WSS-3 verifier does NOT compose a new abbreviated list; it imports the same frozensets to guarantee zero drift from the accepted matrix runner.

**`RECIPIENT_EVENTS`** (verify-evidence.py:112-116):
- `recipient_deliver_received` — carries `dedup_gate ∈ {fresh, duplicate, reack, unknown}`.
- `recipient_message_persisted` — recipient wrote to local store.
- `recipient_ack_deliver_sent` — recipient sent ack-deliver back to relay.

**`SENDER_EVENTS`** (verify-evidence.py:117-126):
- `sender_send_attempt_started` — production `DefaultMessagingService.sendMessage` entry; fires on every attempt including PeerBundleMissing catch.
- `sender_enqueue` — AFTER successful `insertMessage`; real queue-boundary breadcrumb.
- `sender_transport_decision` — carries `outer_transport ∈ {direct, reality, tor}`, `inner_route ∈ {wss, rest}`, `dispatched: bool`.
- `sender_wss_send_returned` — WSS send outcome (`dispatched: bool`, `outcome_flag ∈ {send_error, none}`).
- `sender_rest_post_completed` — REST send outcome (`relay_acceptance ∈ {accepted, duplicate, failed, disabled_by_capability, unknown}`).
- `sender_prekey_deferred` — PeerBundleMissingException-catch marker (correlation id preserved).
- `sender_relay_ack_received` — sender↔relay ack. **Only sender↔relay** — NOT proof of recipient delivery.
- `sender_ack_watchdog_requeued` — watchdog-triggered requeue when relay ack times out.

**`MATRIX_EVENTS`** (verify-evidence.py:127-136):
- `diagnostic_session_started` — emitted by `DiagnosticBootInitProvider` on activation.
- `diagnostic_canary` — smoke-path breadcrumb.
- `diagnostic_state_cleared` — pin/state clear event.
- `diagnostic_pin_active` — pin currently active (WSS/REST). **Audit ROUND-29.3 P0 — dual-party contract.** Since ROUND-29 the orchestrator pins BOTH devices to the cell, because recipient events carry the RECIPIENT's own local `cell_id`; pinning only the sender is what produced the field's false cell attribution. A non-blocked cell's capture therefore MUST contain **exactly two** `diagnostic_pin_active` events: exactly one from the cell's sender emitter and exactly one from its recipient emitter, both `role=matrix`, both carrying this profile's `run_id`, this `cell_id` and the cell's expected pin (`wss` for the six WSS cells, `rest` for the two control cells). Missing either party, a duplicate from either party, an emitter outside the two canonical parties, a wrong `role`, a wrong `pin`, a wrong `run_id` and a wrong `cell_id` are each RED. Ownership is **not** relaxed to "any canonical emitter", and every other no-CID matrix event keeps the ROUND-18 P0-5 sender-only rule unchanged.
- `diagnostic_send_dispatched` — coordinator emitted BEFORE calling `sendMessage`.
- `diagnostic_send_command_completed` — coordinator emitted AFTER return/exception, carries `result ∈ {handled, rejected, exception}` (`ALLOWED_COMMAND_RESULTS`, verify-evidence.py:141).
- `diagnostic_send_rejected_no_paired_conversation` — coordinator refused (0 peers).
- `diagnostic_send_rejected_multiple_paired_conversations` — coordinator refused (>1 peer).

**`CROSS_RUN_TOLERATED_EVENTS`** (verify-evidence.py:142-146) — these three MAY appear from a prior `run_id` without failing integrity, because they persist across restarts:
- `diagnostic_session_started`, `diagnostic_canary`, `diagnostic_state_cleared`.

**Delivery proof** requires the recipient triplet (`recipient_deliver_received(dedup_gate=fresh)` + `recipient_message_persisted` + `recipient_ack_deliver_sent`) on the same correlation id. Sender-side `sender_relay_ack_received` alone is NOT proof of delivery.

**Client emitter must NEVER emit:**
- `unresolved_120s_marker` — verifier-side classification only per WSS-2 §2 (removed 2026-08 supersede).
- `sender_wss_frame_written` — legacy name; accepted schema is `sender_wss_send_returned`.
- `session_epoch` mismatch checks — dropped from the emitter set in WSS-2 accepted spec.
- Any `Delivered / Recovered / partial-GREEN` client-side label — verdicts are verifier output only.

Any `WSS_DIAG` line whose `event=` value is not in the union of the four whitelist frozensets above (with `CROSS_RUN_TOLERATED_EVENTS` allowed to carry non-current `run_id`) → `integrity=RED` for that profile. The verifier imports the frozensets by reference from the accepted `verify-evidence.py` module, so extending the schema requires editing the accepted file — not the WSS-3 verifier alone.

### 4.2 Between-cell / between-profile continuity

Between cells inside one profile, only pin flip (WSS↔REST) and scenario waits (300 s idle, bg-fg transitions) happen — no device restart, no relogin, no VPN toggle. Between profiles, only VPN toggles + SIM swap happen. `signed_prekey_readiness` re-verified at each `preflight` (§7 R4) with bounded polling (Q5).

### 4.3 Latency-sensitivity scope

`after-connect`, `after-idle` (300 s), `bg-fg` — the WSS-2 accepted scenario set. REST `control` retained as fallback sanity for exactly ONE cell per direction (Q2) — does NOT bear WSS-diagnostic weight. No scenario additions in this track.

### 4.4 `matrix_completion.json` — atomic, structured (per architect REDLINE-1 blocker 3)

Empty sentinel FORBIDDEN. `matrix_completion.json` is written by an fsync-then-rename atomic sequence (`.tmp` → fsync → `rename`) after cell 8 completes OR when an abort fires. Schema:

```json
{
  "schema_version": "1",
  "run_id": "wss3-20260814T093012Z-9f2a1c0e",
  "profile_id": "yota-phone-off-host-on",
  "attempt_id": "6f1b8-…-uuid-v4",
  "supersedes_attempt_id": null,
  "retry_reason": null,
  "cells_declared": 8,
  "cells_expected_to_run": 8,
  "cells_ran": 8,
  "envelopes_sent": 40,
  "envelopes_recipient_triplet_complete": 40,
  "started_at_wall_ms": 1755166212310,
  "completed_at_wall_ms": 1755167322891,
  "started_at_monotonic_ms": 12034,
  "completed_at_monotonic_ms": 1122615,
  "abort_reason": null,
  "abort_at_wall_ms": null
}
```

Field discipline (per REDLINE-3 blocker 4 unified schema):

- `cells_declared` — always 8 (frozen manifest set).
- `cells_expected_to_run` — 6 or 8; equals the count of cells whose `blocked=false` at arm time (REST-disabled → 6).
- `cells_ran` — actually executed cells.
- `cells_ran == cells_expected_to_run` ⇒ complete (whether 6 or 8).
- `cells_ran < cells_expected_to_run` ⇒ verifier row = `RED / NOT_EVALUABLE`.

`abort_reason` non-null values (enum, extensible):
- `network_profile_drift` — pre-cell / post-matrix checkpoint diverged from `PROFILE.json`.
- `host_vpn_unverifiable` — host VPN service not queryable (REDLINE-3 blocker 3 split).
- `host_vpn_other_service_active` — `--host-vpn=off` requested but relay route belongs to a DIFFERENT Connected VPN service (REDLINE-5 blocker 2).
- `mixed_address_family` — host and emulator egress used different address families (phone excluded from the HMAC scheme entirely per REDLINE-4 blocker 1).
- `path_divergence_host_emu` — host↔emu egress HMAC unequal (REDLINE-3 blocker 1).
- `signed_prekey_regressed` — mid-run prekey readiness re-check failed.
- `pair_lost` — paired-conversation count went from 1 → other.
- `apk_sha_drift` — APK SHA on device changed mid-run.
- `operator_interrupt` — SIGINT/SIGTERM trap fired.
- `verifier_schema_violation` — any `WSS_DIAG` line failed §4.1 whitelist.
- `carrier_attribution_lost` — `SubscriptionManager.getActiveDataSubscriptionId()` returned INVALID or the active-data subscription's SIM operator changed mid-run (REDLINE-3 blocker 5).
- `wifi_active_or_mobile_data_off` — Wi-Fi enabled OR mobile data disabled OR active-data-sub != expected carrier mid-run (REDLINE-3 blocker 6).

`retry_reason` (§3.4) is non-null on every attempt except the first for a given profile-id. Enum values pinned in §3.4.

### 4.5 Bounded debug-only receiver extension (per architect REDLINE-1 blocker 4 + REDLINE-2 blockers 3/4/5)

The existing debug-only `DiagnosticCommandReceiver` gets one new subcommand: `network_profile_report`. Reporter class ships in `apps/android/src/debug/kotlin/phantom/android/diagnostic/DiagnosticNetworkProfileReporter.kt` (new file, debug source set). `DiagnosticNetworkProfileReporter` is a **helper class, not a manifest component** — the correct source-set invariant is (a) source lives ONLY under `src/debug/`, (b) NO `androidMain` file references the FQN, (c) the receiver + boot provider declarations in `AndroidManifest.xml` remain unchanged (this reporter piggybacks on the existing `DiagnosticCommandReceiver`, adding no new manifest entries). `DiagnosticSourceSetBoundaryTest` is extended with those three invariants — NOT "release manifest does not declare reporter" (nonsense since it was never a manifest component).

**Subcommand shape (per REDLINE-2 blocker 5 — NO caller-controlled path):**

```
adb shell am broadcast \
  -n phantom.android/phantom.android.diagnostic.DiagnosticCommandReceiver \
  --es subcommand network_profile_report \
  --es checkpoint_key_hex "<64-char hex, orchestrator-generated per checkpoint>"
```

The reporter writes to a **fixed app-owned path** derived from `Context.filesDir` (app-internal storage, isolated per uid):

```
/data/data/phantom.android/files/wss3/network_profile.json
```

No `report_target` extra. Only whitelisted extras: `subcommand`, `checkpoint_key_hex`. Any unknown extra → subcommand rejected fail-closed. Orchestrator `adb pull`s via `run-as phantom.android cat <path>` (works because APK is debuggable), captures stdout, and `adb shell run-as phantom.android rm <path>` immediately after — the file never crosses the `/sdcard/` boundary, and no operator-controlled path enters the reporter code.

**Report content (schema pinned; per REDLINE-3 blockers 1/2/5/6):**

```json
{
  "schema_version": "1",
  "at_wall_ms": 1755166212310,
  "at_monotonic_ms": 12034,
  "has_transport_vpn": true,
  "active_network_present": true,
  "active_network_kind": "CELLULAR|WIFI|VPN|OTHER",
  "wifi_enabled": false,
  "mobile_data_enabled": true,
  "active_data_subscription_id": 1,
  "active_data_sim_operator_numeric": "25011",
  "egress_fingerprint": {
    "endpoint": "api4.ipify.org",
    "address_family": "AF_INET",
    "hmac_fp_hex": "9c8a1b3e04ff…",
    "at_wall_ms": 1755166212450,
    "http_status": 200,
    "timeout_ms_used": 5000
  },
  "process_uid": 12345,
  "app_debuggable": true
}
```

**Denied fields** (compile-time enforced in reporter code + verified by focused test): raw IP, VPN provider name/package, DNS server IP, VPN interface name (`tun0`, `utun3`, `utunN`), subscriber ID, IMEI, phone number, ICCID, MSISDN, session tokens, credentials, **`checkpoint_key` and `checkpoint_key_hex` in any evidence file or log** (per REDLINE-3 blocker 2).

**Dual-SIM carrier attribution (per REDLINE-3 blocker 5):**

`getSimOperator()` on a dual-SIM device returns the SIM operator of whichever subscription happens to be default, which is NOT necessarily the active data subscription. The reporter MUST resolve the active-data subscription first:

```kotlin
val subId = SubscriptionManager.getActiveDataSubscriptionId()
if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return abort("no_active_data_sub")
val tmForSub = telephonyManager.createForSubscriptionId(subId)
val simOperatorNumeric = tmForSub.simOperator
```

Result written as `active_data_subscription_id` (integer, opaque; NOT the IMSI or IMEI) + `active_data_sim_operator_numeric`. If the active data sub becomes INVALID mid-run OR its `simOperator` changes → checkpoint fails with `carrier_attribution_lost`.

**Wi-Fi / mobile-data proof (per REDLINE-3 blocker 6, tightened by REDLINE-4 blocker 3):**

`active_network_kind=VPN` alone does NOT prove the phone's underlying transport is cellular — the VPN could be tunnelling over Wi-Fi. Reporter records:

- `wifi_enabled: bool` — `WifiManager.isWifiEnabled()` MUST be `false` throughout the track (Wi-Fi radio off means no accidental Wi-Fi fallback).
- `mobile_data_enabled: bool` — reflects `TelephonyManager.isDataEnabled()` on the active-data subscription; MUST be `true`.

Preflight + every checkpoint (§4.6) requires: `wifi_enabled=false AND mobile_data_enabled=true AND active_data_sim_operator_numeric == expected_operator_numeric`. Drift → fail-closed `wifi_active_or_mobile_data_off`.

**Permission contract for `WifiManager.isWifiEnabled()` (per REDLINE-4 blocker 3).** `WifiManager.isWifiEnabled()` requires the `android.permission.ACCESS_WIFI_STATE` runtime-declared permission. The current production manifest at `apps/android/src/androidMain/AndroidManifest.xml` declares only `ACCESS_NETWORK_STATE`. The WSS-3 debug manifest at `apps/android/src/debug/AndroidManifest.xml` therefore adds exactly one line:

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

This declaration lives ONLY in the debug source set so that:

- release-merged manifest is unchanged (no new permission ships to production users);
- debug-merged manifest exposes the permission so `isWifiEnabled()` returns without `SecurityException` inside `network_profile_report`;
- if a future toolchain change ever silently drops the permission from the debug-merged manifest, the reporter **fail-closes with a caught `SecurityException`** rather than falsifying `wifi_enabled` — the checkpoint records `wifi_enabled: null` + `wifi_read_error: "SECURITY_EXCEPTION"` and the orchestrator treats that as `wifi_active_or_mobile_data_off` unverifiable → fail-closed.

`TelephonyManager.isDataEnabled()` uses `ACCESS_NETWORK_STATE` (already present in the production manifest) and needs no new permission.

Two manual operator checkboxes are recorded once per profile at preflight in `PROFILE.json.operator_manual_confirmations`:

- `auto_data_switching_off: bool` — operator has disabled Settings → SIMs → Automatic data switching. (Prevents Android from silently swapping the active data sub to the other SIM if signal weakens.)
- `other_sim_data_disabled: bool` — operator has disabled mobile data on any other SIM present in the device.

Both MUST be `true` at preflight. These are preserved from WSS-2 accepted operator checklist.

**Egress fingerprint — orchestrator-keyed HMAC (per REDLINE-2 blocker 3, tightened by REDLINE-3 blockers 1/2 and REDLINE-4 blocker 1):**

- Orchestrator generates ONE 32-byte cryptographically-random `checkpoint_key` per checkpoint (§4.6).
- `checkpoint_key_hex` is broadcast to the emulator reporter AND used locally by Mac host. **The phone reporter does NOT participate in HMAC egress fingerprinting at all** — see removal note below.
- Fingerprint: `HMAC-SHA256(key = checkpoint_key, message = canonical_ip_bytes)` — take first 16 bytes hex.
- `canonical_ip_bytes` = the IP string returned by `api4.ipify.org`, UTF-8 encoded, no trailing whitespace, no `\n`.
- Two fingerprints computed per checkpoint: `host_hmac_fp_hex` + `emu_hmac_fp_hex`. Orchestrator compares them (§4.6 detail) and persists ONLY the two hex strings + `equal_host_emu: bool` + `address_family` (each side records its `getaddrinfo` result).
- **`checkpoint_key` is wiped from memory** after both fingerprints are computed. It is NEVER written to disk, log, evidence, or archive.
- Raw IPs are NEVER persisted.

**Phone HMAC deliberately removed (per REDLINE-4 blocker 1).** Earlier drafts captured a phone HMAC with a *different* per-checkpoint key and then attempted to compare phone HMACs across profiles as a `phone_fingerprint_differs_from_no_vpn_baseline: bool` VPN-flip signal. That comparison is mathematically vacuous: two HMACs computed with two different random keys differ with overwhelming probability even for the same input, so the "signal" is a constant `true` regardless of whether the phone's egress path actually changed. The phone reporter therefore does NOT compute any HMAC and no `phone_hmac_fp_hex` field is emitted or persisted. Phone VPN state is proved by the five orthogonal checks in §7 R5+R6 (has_transport_vpn == expected + Wi-Fi off + mobile-data on + active-data SIM pinned + operator manual confirmations) plus the real-world proof that at least one WSS envelope is delivered end-to-end in this profile (§3.2 smoke). That composite is stronger than a broken hash comparison would have been.

**IPv4/IPv6 discipline (per REDLINE-2 blocker 4):**

- Endpoint pinned to `api4.ipify.org` (IPv4-only echo endpoint) so both host + emu force IPv4 lookup.
- `address_family` field recorded from `getaddrinfo` result on each side; MUST be `AF_INET` on both host and emu, or the profile is fail-closed as `mixed_address_family` in `matrix_completion.json.abort_reason`.
- If ipify or its IPv4-only subdomain becomes unavailable (e.g. blocked by the operator's VPN), the profile → `NOT_EVALUABLE` (not guessed). No auto-fallback to IPv6 endpoint.

**Timeout / non-200:** 5 s HTTP timeout. On timeout / non-200 → `hmac_fp_hex = null`, `http_status` recorded (e.g. `null`, `502`, `598` for timeout). Orchestrator counts this as a network failure and marks the profile fail-closed for that checkpoint. Raw response body (even the 5xx one) is held in memory only long enough to hash-or-discard; never persisted.

**Contract:** `network_profile_report` never emits to Android's `Log` OR to `WssDiag`. It writes only to the fixed app-owned path. The orchestrator reads via `run-as`, deletes via `run-as rm`, and only then persists the sanitised summary to evidence.

**Release-inert invariant** stays: `WssDiag.isActive` untouched (§PR-review-round-1 P0 gate preserved); `DiagnosticCommandReceiver` still permission-DUMP-gated (§12 Round-1 audit P0-6 preserved); receiver + boot provider still declared only in `apps/android/src/debug/AndroidManifest.xml`; the new `network_profile_report` subcommand adds no new manifest components.

### 4.6 Per-cell + post-matrix network drift checkpoint (per architect REDLINE-1 blocker 5 + REDLINE-2 blocker 9 + REDLINE-3 blockers 1/2/3/5/6)

Before EACH of the 8 cells starts firing its 5 envelopes, and again AFTER cell 8 completes, the orchestrator runs a drift check comparing the following fields between `PROFILE.json` (armed at preflight) and freshly-read values. Phone attribution flows through §7 R5+R6 orthogonal checks only (VPN transport bit, Wi-Fi off, mobile-data on, active-data SIM pinned, manual confirmations) — the phone participates in NO HMAC scheme (REDLINE-4 blocker 1). The HMAC parity check is a two-participant `host ↔ emulator` comparison. Sources:

| Field | Source at check |
|---|---|
| phone active-data SIM binding | phone `network_profile_report.active_data_subscription_id` + `active_data_sim_operator_numeric` — both MUST match `PROFILE.json`; either drift → `carrier_attribution_lost` (REDLINE-3 blocker 5) |
| phone Wi-Fi off + mobile-data on | phone `network_profile_report.wifi_enabled == false` AND `mobile_data_enabled == true`; either drift → `wifi_active_or_mobile_data_off` (REDLINE-3 blocker 6) |
| phone VPN state | phone `network_profile_report.has_transport_vpn == PROFILE.json.phone_vpn` |
| host VPN state (ON case) | `scutil --nc status "<host-vpn-service>"` returns `Connected` AND `route -n get <relay_host>` returns an interface OWNED by that service. Both required. Service is the CLI-supplied `--host-vpn-service` (REDLINE-4 blocker 2). |
| host VPN state (OFF case) | `scutil --nc status "<host-vpn-service>"` returns `Disconnected` AND `route -n get <relay_host>` returns an interface NOT owned by ANY Connected Network Configuration VPN service (enumerated in-memory from `scutil --nc list`; only the boolean result persists, per REDLINE-5 blocker 2). Both required. Service must still EXIST in `scutil --nc list` — absent service is unverifiable, not off. Failure of the any-other-VPN check → `host_vpn_other_service_active` fail-closed. |
| host VPN state (unverifiable pre-arm) | If `--host-vpn-service` is absent from `scutil --nc list` at preflight → fail-closed `host_vpn_unverifiable` BEFORE arm; profile → `NOT_EVALUABLE`. `scutil --nwi` + `utun` presence is NOT a substitute. |
| host↔emu egress equality (HMAC parity) | orchestrator generates fresh `checkpoint_key`, broadcasts to emu reporter (§4.5) + uses locally on host; requires `equal_host_emu: true` AND `address_family` identical between host and emu. **The phone does NOT participate in the HMAC scheme at all** (REDLINE-4 blocker 1). Phone VPN state is proved by §7 R5+R6 (has_transport_vpn + Wi-Fi off + mobile-data on + active-data SIM pinned + manual confirmations) + successful smoke delivery (§3.2). |
| APK/run/profile binding | phone + emu APK SHA-256 vs `PROFILE.json.apk_sha256`; `PROFILE.json.run_id` valid |

Any drift → orchestrator writes `matrix_completion.json` with the specific `abort_reason` enum from §4.4 (e.g. `network_profile_drift`, `host_vpn_unverifiable`, `mixed_address_family`, `path_divergence_host_emu`, `carrier_attribution_lost`, `wifi_active_or_mobile_data_off`, `apk_sha_drift`), `cells_ran = N` where N is the last cell that finished, releases the pin, kills capture, exits non-zero.

**Checkpoint log discipline (per REDLINE-3 blocker 2).** Checkpoint results persist to `evidence/<profile>/checkpoint_log.json` — one entry per checkpoint (pre-cell-1 … pre-cell-8 + post-matrix). Each entry contains ONLY:

- observed field values from the drift table above (subject to the §5 denylist filter);
- both HMAC fingerprints (`host_hmac_fp_hex`, `emu_hmac_fp_hex`), `address_family` per side, and the derived `equal_host_emu: bool`;
- monotonic-ms + wall-UTC timestamps;
- the `drift: bool` outcome and (if `true`) the matching `abort_reason` enum.

No phone HMAC field is written (REDLINE-4 blocker 1 — the vacuous cross-profile comparison was removed; phone VPN attribution now rests on §7 R5+R6 orthogonal checks + smoke-delivery proof).

**The `checkpoint_key` (raw bytes) and `checkpoint_key_hex` (any hex encoding) are NEVER written to `checkpoint_log.json`, `PROFILE.json`, `network_profile.json`, `SHA256SUMS.txt`, any log file, or the tarball archive.** Reproducibility is intentionally sacrificed to prevent IPv4-space brute-force recovery of raw egress IP from the HMAC. Denylist regex sweep (§5) fail-closes on any occurrence of `checkpoint_key`, `checkpoint_key_hex`, or `checkpoint-key`.

---

## 5. Evidence schema (per profile)

Each profile directory contains exactly this set. Verified by `verify-evidence.py --track wss-3-carrier-vpn-matrix` (extension of accepted WSS-2 verifier).

| File | Purpose |
|---|---|
| `PROFILE.json` | `{carrier, phone_vpn, host_vpn, expected_operator_numeric, apk_sha256, run_id, attempt_id, supersedes_attempt_id?, retry_reason?, arm_token, arm_wall_ms, arm_monotonic_ms, confirm_string, operator_manual_confirmations: {auto_data_switching_off: bool, other_sim_data_disabled: bool}}` — `arm_token` is a UUIDv4 minted at preflight (§3.6 invariant 5) and cross-checked by every subsequent verb against `.runtime/wss3/<profile-id>/arm-token`; `operator_manual_confirmations` moved into the canonical schema per REDLINE-6 blocker 1 (referenced by §7 R6 and produced at preflight step 5) |
| `PROFILE_STATE.json` | Observed values at preflight matching PROFILE.json |
| `network_profile.json` | Full sanitised output of `network_profile_report` at preflight (phone + emu, one section each) |
| `EGRESS_FINGERPRINT.json` | `{host_hmac_fp_hex, emu_hmac_fp_hex, address_family_host, address_family_emu, equal_host_emu:bool, at_wall_ms}` — HMAC-only, no key material persisted; no phone field (REDLINE-4 blocker 1 removed the vacuous cross-profile phone comparison) |
| `signed_prekey_readiness.json` | Both devices' outputs |
| `checkpoint_log.json` | One entry per pre-cell / post-matrix checkpoint (§4.6); each with 5-field observed values + drift-bool |
| `smoke.log` | WSS_DIAG lines: `sender_transport_decision`, `sender_wss_send_returned`, `sender_relay_ack_received`, `recipient_deliver_received`, `recipient_message_persisted`, `recipient_ack_deliver_sent` — for both directions |
| `smoke_verdict.json` | `{p2e:OK|FAIL, e2p:OK|FAIL, integrity:GREEN|RED, at_wall_ms, attempt_id, arm_token, smoke_run_id, p2e_cid?, e2p_cid?}` plus the OPTIONAL pre-send abort group below (audit ROUND-29.1 amendment) |
| `matrix_cells/<cell-id>/` | Per-cell WSS_DIAG capture + `cell_verdict.json` (Delivered / Unresolved per verifier — client never labels) |
| `matrix_completion.json` | **Atomic, structured** per §4.4. Empty sentinel FORBIDDEN. |
| `matrix_verdict.json` | Roll-up per profile: `{evidence_integrity: GREEN|RED|NOT_EVALUABLE, product_outcome: GREEN|RED|NOT_EVALUABLE, delivered_count, unresolved_count, cells}` — **no `partial-GREEN` state** (§7 R18 + REDLINE-1 blocker 7) |
| `SHA256SUMS.txt` | SHA-256 of every file above except itself. Immutable per profile (Q7). |

**Audit ROUND-29.1 amendment — pre-send abort group in `smoke_verdict.json`.**
The smoke may now terminate fail-closed BEFORE either direction is dispatched.
Two gates precede every send:

1. **Fresh Direct health.** The accepted `health` subcommand (§4.1, already in
   `ALLOWED_SUBCOMMANDS`) is invoked unconditionally per device and only a
   health line produced *after* that invocation is accepted. A historical
   `sender_transport_decision` and an uncorrelated `canary` are explicitly NOT
   authority. Non-zero broadcast, timeout, missing fresh line, `unknown` or any
   other non-`direct` value → no send.
2. **Confirmed pins.** Both pin broadcasts must return zero AND each emitter
   must produce a fresh `diagnostic_pin_active` matching `run_id`, `cell_id`,
   `pin=wss` and that emitter, before dispatch.

On either failure both pins are cleared, `smoke.log` is still captured (it is
the proof that `diagnostic_send_dispatched` count is **0** on both canonical
smoke cells), and `smoke_verdict.json` records `integrity=RED`, `p2e=e2p=FAIL`,
both CIDs `null`, plus these OPTIONAL bounded fields:

| Field | Enum | Required when |
|---|---|---|
| `pre_send_reason` | `direct_unavailable` / `health_probe_failed` / `pin_unconfirmed` / `pin_clear_unconfirmed` | any pre-send abort |
| `pre_send_phone_outer` | `direct`/`reality`/`tor`/`probing`/`idle`/`failed`/`unknown`/`probe_failed` | reason is `direct_unavailable` or `health_probe_failed` |
| `pre_send_emu_outer` | same enum | reason is `direct_unavailable` or `health_probe_failed` |
| `pre_send_pin_stage` | `p2e` / `e2p` | reason is `pin_unconfirmed` or `pin_clear_unconfirmed` |
| `pre_send_pin_emitter` | `phone` / `emulator` / `both` | reason is `pin_unconfirmed` or `pin_clear_unconfirmed` |

A run whose directions BOTH completed and whose FINAL clear did not confirm is
not a pre-send abort and does not use the fields above. It writes its own
post-send set, and both correlation ids survive:

| Field | Enum | Required when |
|---|---|---|
| `post_send_reason` | `pin_clear_unconfirmed` | the final clear did not confirm |
| `post_send_clear_stage` | `final` | `post_send_reason` present |
| `post_send_clear_emitter` | `phone` / `emulator` / `both` | `post_send_reason` present |

The two sets are mutually exclusive: a pre-send abort dispatched nothing, while
the post-send set describes a run that delivered and then failed to tidy up.
Forcing the second into the first would demand `e2p=FAIL` with a null cid and
erase a delivery `smoke.log` proves.

No free-text detail field exists; every value above is enum-bounded so the
verifier can check it. `probe_failed` is the operator-package value meaning the
probe yielded nothing for that device. A GREEN verdict carries none of these
keys. This amendment adds no new WSS_DIAG event and no product-code change.

**Audit ROUND-29.2 P1-1 — partial evidence at stage `e2p`.** P2E is sent and
observed BEFORE the E2P pin is attempted, so an abort at
`pre_send_pin_stage=e2p` may legitimately sit on top of a **completed** P2E
direction. In that one case `p2e` MAY be `OK` with its real `p2e_cid`, and the
value MUST agree with `smoke.log`; `p2e=FAIL` still requires `p2e_cid=null`.
Every other abort keeps `p2e=FAIL` with a null CID. `e2p` is always `FAIL` with
a null CID on any abort, and `integrity` is always `RED`. Writing `FAIL/null`
for a P2E that `smoke.log` proves was dispatched is itself a schema violation:
fail-closed must not mean false evidence.

**Audit ROUND-29.2 P1-2 — per-cell pin confirmation in `full`.** Before any of
a cell's five sends, BOTH emitters' pin broadcasts must return zero AND each
must produce a fresh `diagnostic_pin_active` matching `run_id`, `cell_id`, the
cell's expected pin (`wss` for the six WSS cells, `rest` for the two control
cells) and that emitter. On failure both pins are cleared, the cell dispatches
zero envelopes and records the OPTIONAL bounded
`cell_verdict.abort_reason=pin_unconfirmed` (requires `blocked=false`, all
three counts `0`, `verdict=Unresolved`), and the matrix stops with the same
reason in `matrix_completion.abort_reason`. `pin_unconfirmed` is added to the
§4.4 abort-reason enum.

**Denylist (must never appear in ANY evidence file, on-disk):** raw IP, VPN provider name/package, DNS server IP, VPN interface name (`tun0`, `utun3`, `utunN`), subscriber ID, IMEI, phone number, ICCID, MSISDN, credentials, real usernames or crypto-identity key hex strings, envelope payload text, session tokens, **any occurrence of the substrings `checkpoint_key`, `checkpoint_key_hex`, `checkpoint-key`, `checkpointKey`, `checkpointKeyHex`** (raw HMAC key material, per REDLINE-3 blocker 2), **the raw `--host-vpn-service` string** (only the derived Connected/Disconnected + route-owned-by-service booleans are recorded, per REDLINE-4 blocker 2).

**Allowlist (permitted low-entropy metadata):** carrier numeric, canonical envelope IDs (run-salted), correlation IDs, `outer_transport`/`inner_route`/`outcome_flag`/`relay_acceptance`/`dedup_gate` enum values, monotonic-ms + wall-UTC timestamps, cell IDs, HMAC fingerprint hex (16-byte truncation, per §4.5 — SAFE to persist because the single-use key that produced it is wiped and never recorded).

---

## 6. State machine (per profile, per attempt)

```
IDLE
  └─ preflight ────► ARMED (or FAIL_CLOSED_UNVERIFIED_STATE)
                       └─ smoke ────► SMOKED (or SMOKE_RED)
                                        └─ full ────► COMPLETE (matrix_completion.json written, abort_reason=null)
                                                       │
                                                       └─ compare ─── (no state change; report only)

  From smoke or full at any point:
    checkpoint drift ────► ABORTED_INSTRUMENTED (matrix_completion.json written, abort_reason=<enum>)
```

Terminal states:
- `COMPLETE` — advances to next profile
- `ABORTED_INSTRUMENTED` — profile row will be `RED / NOT_EVALUABLE` in compare
- `FAIL_CLOSED_UNVERIFIED_STATE` — stops track — architect review
- `SMOKE_RED` — stops track — investigate before proceeding

`resume` re-enters at `IDLE` for any profile whose latest attempt lacks `matrix_completion.json`; refuses bare-mode on `COMPLETE` (§3.4). `resume --retry --reason "..."` is the only path to a fresh attempt on a previously-terminated profile.

---

## 7. Requirements (20 explicit gates — all fail-closed)

1. No uninstall / re-bootstrap / re-onboarding between profiles. APK stays put.
2. Exactly one paired conversation on both devices at every `preflight` AND every pre-cell checkpoint.
3. APK SHA-256 (from `ApplicationInfo.sourceDir`) equal on phone + emu AND equal to `PROFILE.json.apk_sha256` from the first profile of the track — any deviation stops the track.
4. Signed-prekey readiness `true` on both at every `preflight`; **bounded polling** up to 60 s at 5 s step (Q5) before fail-closed.
5. **Phone carrier numeric via active-data subscription (per REDLINE-3 blocker 5).** Reporter resolves the active-data subscription first with `SubscriptionManager.getActiveDataSubscriptionId()`, then reads `TelephonyManager.createForSubscriptionId(id).simOperator`. Result (both `active_data_subscription_id` + `active_data_sim_operator_numeric`) must equal `PROFILE.json` values at every checkpoint. Bare `TelephonyManager.getSimOperator()` (default-sub) is NOT accepted on a dual-SIM device. INVALID sub / sub swap / operator numeric change mid-run → `carrier_attribution_lost` fail-closed.
6. **Phone transport proof (per REDLINE-3 blocker 6).** All of the following required at every checkpoint: (a) `network_profile_report.has_transport_vpn == --phone-vpn`; (b) `wifi_enabled == false` (Wi-Fi radio off, no fallback surface); (c) `mobile_data_enabled == true` on the active-data subscription; (d) `PROFILE.operator_manual_confirmations.auto_data_switching_off == true` (recorded once at preflight — Android must not silently swap data sub on weak signal); (e) `PROFILE.operator_manual_confirmations.other_sim_data_disabled == true` (other SIM slots have mobile data off, so no accidental data-path via the other SIM). Any drift → `wifi_active_or_mobile_data_off` fail-closed.
7. **Host VPN state proved via mandatory `--host-vpn-service` + two positive checks (per REDLINE-3 blocker 3, tightened by REDLINE-4 blocker 2 and REDLINE-5 blocker 2).** The CLI-supplied `--host-vpn-service "<name>"` MUST exist in `scutil --nc list` for BOTH `--host-vpn=on` and `--host-vpn=off` profiles — the same service anchors the profile in either direction. **Unverifiable pre-arm:** if `--host-vpn-service` is absent from `scutil --nc list` at preflight, the profile fail-closes with `host_vpn_unverifiable` → `NOT_EVALUABLE` BEFORE arm — never guessed ON/OFF, never treated as `off`. ON case: (a) `scutil --nc status "<service>"` returns `Connected` AND (b) `route -n get <relay_host>` returns an interface OWNED by that same service. OFF case: (a) `scutil --nc status "<service>"` returns `Disconnected` AND (b) `route -n get <relay_host>` returns an interface that is NOT owned by ANY Connected Network Configuration VPN service — the orchestrator enumerates `scutil --nc list`, filters to services whose `scutil --nc status` returns `Connected`, resolves each one's owned interface, and rejects the profile if `route -n get <relay_host>`'s interface is in that set. Prevents "selected VPN is off but a second VPN is still active" from silently passing as `host_vpn=off` (REDLINE-5 blocker 2). Enumeration + interface names live only in memory during the check — no service name, no interface name, no third-VPN identity ever escapes to evidence. `scutil --nwi` and bare `utun` presence are NOT sufficient in either direction. The raw `--host-vpn-service` string is never persisted to evidence (denylist §5).
8. **Egress equality is host↔emu only (per REDLINE-3 blocker 1, tightened by REDLINE-4 blocker 1).** Host + emu each receive the same 32-byte `checkpoint_key` per checkpoint (broadcast for emu, local for host), compute `HMAC-SHA256(key, canonical_ip_bytes)`, orchestrator persists `equal_host_emu: bool`. `AF_INET` required on BOTH host and emu; either differs → `mixed_address_family` fail-closed. Inequality → `path_divergence_host_emu` fail-closed. **The phone participates in NO HMAC scheme.** Two-way phone-VPN attribution now rests entirely on R5 (active-data SIM + operator numeric pinned), R6 (has_transport_vpn + Wi-Fi off + mobile-data on + manual confirmations), and successful WSS delivery inside the profile (§3.2 smoke); this composite is stronger than the removed vacuous cross-profile HMAC comparison would have been.
9. Denylist enforcement (§5): every file written to evidence passes a regex sweep before persist. Any denylist hit = evidence discarded + fail-closed. Denylist explicitly includes `checkpoint_key*` substrings (REDLINE-3 blocker 2).
10. **Egress fingerprint discipline (REDLINE-2 blocker 3, tightened by REDLINE-3 blockers 1/2 and REDLINE-4 blocker 1).** Orchestrator generates ONE 32-byte cryptographically-random `checkpoint_key` per checkpoint. The key is broadcast to the emu reporter and used locally on the host; **the phone reporter never receives a key** because the phone does not participate in HMAC fingerprinting. Fingerprint = first 16 bytes hex of `HMAC-SHA256(checkpoint_key, canonical_ip_bytes)`. **Every `checkpoint_key` is wiped from memory** after both fingerprints are computed and is NEVER written to `checkpoint_log.json`, `PROFILE.json`, `network_profile.json`, `EGRESS_FINGERPRINT.json`, any log, or the tarball archive. Reproducibility of the raw IP from the HMAC is intentionally impossible — persisting the key would allow IPv4-space brute-force recovery. Only the 16-byte HMAC hex + `address_family` + boolean equality result persist. Raw IPs never touch disk. Endpoint pinned to `api4.ipify.org` (IPv4-only) with 5 s timeout; on timeout / non-200 `hmac_fp_hex=null`, `http_status` recorded, orchestrator counts as network failure. Raw HTTP response held in memory only long enough for hashing.
11. Manual typed confirmation required at every `preflight`. Confirmation string is derived deterministically: `CONFIRM-<CARRIER>-PHONE-<OFF|ON>-HOST-<OFF|ON>`. Case-sensitive. Any deviation = fail-closed. Stored in `PROFILE.json.confirm_string` for the verifier to check against `--expected-operator-numeric`+`--phone-vpn`+`--host-vpn` on subsequent runs.
12. Fail-closed when observed VPN/carrier state differs from requested state (R5, R6, R7). No warnings-only mode.
13. Per-profile evidence directory: `evidence/<carrier-lc>-phone-<off|on>-host-<off|on>-<UTC>/`. UTC is `date -u +%Y%m%dT%H%M%SZ`. Never overwrites — a fresh preflight always creates a new UTC directory. Directly re-running `preflight` on a directory whose profile-id has a completed `matrix_completion.json` = REFUSED (Q3). Explicit retry is `resume --retry --reason "..."` (Q4).
14. Smoke gate MUST prove Direct WSS routing (`outer_transport=direct AND inner_route=wss` on `sender_transport_decision`) AND the **recipient triplet** for each direction (§4.1) BEFORE `full` can execute for that profile.
15. `full` cell set = **8 cells total** (6 WSS + 2 REST controls) — the WSS-2 canonical set. 5 envelopes per cell → 40 envelopes per profile. Frozen; no scenario additions.
16. Cleanup trap covers `EXIT`, `INT`, `TERM`: pin cleared (`pin=NONE`), `logcat`/`adb shell` captures killed, any in-memory `checkpoint_key` (host-side variable + broadcast intent extras on emu) zeroed and the reporter output file `run-as`-deleted (best-effort — Bash string handling + process-death limitations acknowledged).
17. `resume` walks profile directories; refuses bare-mode re-run of any attempt containing valid `matrix_completion.json` + `matrix_verdict.json`. Retry-mode `--retry --reason "..."` creates a fresh `attempt_id` + `supersedes_attempt_id` linked to the previous completed attempt.
18. `compare-vpn-matrix.py` output = one row per profile (8 rows). Each row carries independent `evidence_integrity` + `product_outcome` columns. `partial-GREEN` state is FORBIDDEN — a cell RED / Unresolved → `product_outcome = RED`, even if all other cells are GREEN. Integrity RED forces `product_outcome=NOT_EVALUABLE`.
19. Missing profile / malformed evidence / broken `matrix_completion.json` / profile with un-linked duplicate attempts → row shows `NOT_EVALUABLE` for both columns AND `compare` exits non-zero. No silent GREEN. **Superseded in part by audit ROUND-30.18:** an ABSENT `matrix_completion.json` is no longer unconditionally `NOT_EVALUABLE` — a trustworthy RED smoke that stopped the profile renders `GREEN / RED` (see § *The smoke-only terminal profile and the overflow marker*); `compare` still exits non-zero, since the row is not GREEN/GREEN. Every other absence keeps the `NOT_EVALUABLE` reading.
20. Focused tests (§9) shipped with the operator package. All must pass under `bash tests/test_shell.sh` + `python3 -m unittest tests.test_verifier` before the track ships. Fixture set covers the drift/duplicate/retry/malformed cases enumerated in §9.

---

## 8. Failure matrix (operator-visible)

| Failure | Where surfaced | Recovery |
|---|---|---|
| Phone SIM carrier mismatch | `preflight` fail-closed | Swap SIM, restart `preflight`. |
| Phone VPN state mismatch | `preflight` fail-closed | Toggle phone VPN, restart `preflight`. |
| Host VPN state mismatch | `preflight` fail-closed | Toggle Mac VPN, restart `preflight`. |
| Host VPN service unverifiable | `preflight` fail-closed with `host_vpn_unverifiable` BEFORE arm | Ensure the CLI-supplied `--host-vpn-service` string is present in `scutil --nc list` (both `on` and `off` profiles anchor to the same service name). If the operator's VPN client isn't exposed as a Network Configuration service, classify the profile as `NOT_EVALUABLE` — never guess ON/OFF (REDLINE-2 blocker 9 + REDLINE-4 blocker 2). |
| `--host-vpn=off` but relay routes via a different active VPN | `preflight`/checkpoint fail-closed with `host_vpn_other_service_active` | Disconnect the second VPN (or all VPNs whose iface owns the relay route). Only the selected `--host-vpn-service` may be `Connected` when `--host-vpn=off` is armed; and even that one must be `Disconnected` for OFF (REDLINE-5 blocker 2). |
| Host↔emulator egress divergence (HMAC mismatch) | `preflight`/checkpoint fail-closed with `path_divergence_host_emu` | Investigate — possible host proxy leak, VPN split-tunnel, or emulator misconfig. Not auto-recoverable. Phone is excluded from this check by design (REDLINE-4 blocker 1); phone attribution is proved by §7 R5+R6 orthogonal state + successful smoke delivery, not by fingerprint parity. |
| Mixed address-family between host and emulator | `preflight`/checkpoint fail-closed with `mixed_address_family` | Ensure the ipify DNS resolution is IPv4 on both host and emulator. Very rare on a modern IPv4-only endpoint (`api4.ipify.org` pinned per §4.5). Phone is not part of this check. |
| APK SHA drift between devices | `preflight` fail-closed | Reinstall matching APK on both. |
| Signed-prekey not ready | `preflight` bounded polling → fail-closed after 60 s (Q5) | Wait for `signed_prekey_readiness published=true`; re-run `preflight`. Readiness check does not send anything; it just re-reads the diag subcommand output (REDLINE-2 recovery-text fix). |
| Confirmation string typo | `preflight` fail-closed | Retype. |
| Direct preflight on completed profile | Exit 3 with `profile-already-complete` | Use `resume --retry --profile-id <id> --reason <enum>` instead. |
| Duplicate attempts without supersedes-lineage link | `compare` treats profile as `NOT_EVALUABLE` | Attach lineage: re-run the missing-provenance attempt as `resume --retry --profile-id <id> --reason <enum>` so it writes `supersedes_attempt_id`. **Do NOT delete evidence directories** — history integrity is part of the audit trail (REDLINE-2 blocker 7). |
| Mid-run network drift (per-cell checkpoint) | `matrix_completion.json` with `abort_reason=network_profile_drift` (or more specific enum), exit non-zero | Investigate what changed (SIM auto-swap? VPN reconnect?); `resume --retry --profile-id <id> --reason network_transient_recovery`. |
| REST capability disabled by relay | `matrix_completion.json` with `cells_expected_to_run=6`, both REST cells recorded as `BLOCKED` | Profile is complete at `cells_ran=6`; not partial. Only if `cells_ran < cells_expected_to_run` does `matrix_verdict.json` mark integrity RED (REDLINE-2 blocker 6). |
| Smoke RED (routing OR recipient triplet) | `smoke_verdict.json` fail-closed | STOP track. Investigate WSS-diagnostic. Do not run `full`. |
| `full` cell RED | Cell verdict + `matrix_verdict.json.product_outcome=RED` | Full still finishes remaining cells. Verifier produces RED product_outcome for the profile (never `partial-GREEN`). |
| Interrupted run (SIGINT / power loss) | `matrix_completion.json` with `abort_reason=operator_interrupt` OR missing entirely | Bare `resume` re-arms an incomplete attempt (writes new attempt tagged with `supersedes_attempt_id`). |
| `compare` missing profiles | Exit non-zero + `NOT_EVALUABLE` rows | Ensure all 8 preflights + fulls completed before `compare`. |

---

## 9. Focused tests (all shipped with operator package)

Shell (`tests/test_shell.sh` extensions, all fail-closed):

1. `wss3-preflight-rejects-carrier-mismatch`
2. `wss3-preflight-rejects-phone-vpn-state-mismatch`
3. `wss3-preflight-rejects-host-vpn-state-mismatch`
4. `wss3-preflight-rejects-egress-divergence` — fingerprint mismatch → `path-divergence`
5. `wss3-preflight-rejects-apk-sha-drift`
6. `wss3-preflight-rejects-confirmation-typo`
7. `wss3-preflight-rejects-multiple-paired-conversations`
8. `wss3-preflight-signed-prekey-polls-60s-then-fails` — 12 fake "not ready" polls at 5 s step → fail after 60 s (Q5)
9. `wss3-preflight-refuses-completed-profile` — direct `preflight` on profile with valid `matrix_completion.json` → exit 3
10. `wss3-resume-bare-refuses-completed-profile` → exit 3
11. `wss3-resume-retry-requires-reason` — `resume --retry` without `--reason` → fail-closed
12. `wss3-resume-retry-links-attempt-id-lineage` — new attempt writes `supersedes_attempt_id` matching previous attempt
13. `wss3-smoke-red-halts-full-invocation`
14. `wss3-smoke-red-on-missing-recipient-triplet` — only `sender_relay_ack_received` present, no recipient triplet → RED (proves §4.1 delivery-proof invariant)
15. `wss3-smoke-red-on-forbidden-emitter` — synthetic `unresolved_120s_marker` line in capture → integrity RED (proves §4.1 emitter whitelist)
16. `wss3-full-checkpoint-detects-drift-mid-run` — synthetic drift at pre-cell-4 → `matrix_completion.json` written with `abort_reason=network_profile_drift`, `cells_ran=3`
17. `wss3-trap-clears-pin-on-SIGINT`
18. `wss3-trap-clears-pin-on-SIGTERM`
19. `wss3-trap-zeroes-checkpoint-key-on-abort` — after SIGINT, `checkpoint_key`/`checkpoint_key_hex` substrings not present anywhere on disk (host-side variable cleared, reporter file `run-as`-deleted, denylist regex sweep clean) (REDLINE-3 blocker 2)
20. `wss3-process-restart-restores-pin-state` — orchestrator PID crash mid-cell → next invocation re-reads pin state from device (not from stale local file)
21. `wss3-compare-missing-profile-not-evaluable`
22. `wss3-compare-malformed-completion-not-evaluable` — `matrix_completion.json` with `cells_ran: "eight"` (wrong type) → NOT_EVALUABLE
23. `wss3-compare-partial-GREEN-forbidden` — 5/6 WSS cells GREEN, 1 Unresolved → row `product_outcome=RED` (NOT `partial-GREEN`)
24. `wss3-compare-duplicate-without-lineage-not-evaluable` — two attempts, second lacks `supersedes_attempt_id` → row NOT_EVALUABLE
25. `wss3-compare-duplicate-with-valid-lineage` — two attempts, second links first → newest used, `retry_lineage_present: true` in report footer
26. `wss3-eight-profile-fixture-full-green` — synthetic 8-profile all-GREEN → 8 GREEN/GREEN rows
27. `wss3-denylist-blocks-payload-text` — envelope with fake IP `192.0.2.1` in payload → denylist catches
28. `wss3-denylist-blocks-provider-name` — string `NordVPN` in log → denylist catches
29. `wss3-denylist-blocks-utun-interface-name` — string `utun3` in report → denylist catches
30. `wss3-network-profile-mixed-address-family` — synthetic report with `address_family=AF_INET` on host but `AF_INET6` on emu → verifier fail-closes profile as `mixed_address_family` (per §7 R8)
31. `wss3-network-profile-egress-timeout` — 5 s timeout hit → `hmac_fp_hex=null`, `http_status=null`, orchestrator counts as failure without denylist trip
32. `wss3-cross-profile-contamination-check` — synthetic mid-run APK reinstall → APK SHA drift caught + `abort_reason=apk_sha_drift`
33. `wss3-matrix-completion-atomic-write` — SIGKILL mid-`matrix_completion.json` write → next `verify-evidence.py` sees NO `matrix_completion.json` (temp `.tmp` not renamed) rather than a torn file
34. `wss3-checkpoint-log-omits-key-material` — after a full 8-cell profile, grep-sweep of `checkpoint_log.json` + `PROFILE.json` + `network_profile.json` + `EGRESS_FINGERPRINT.json` finds ZERO occurrences of `checkpoint_key`, `checkpoint_key_hex`, `checkpoint-key`, `checkpointKey`, `checkpointKeyHex` (REDLINE-3 blocker 2)
35. `wss3-host-vpn-off-positive-check` — synthetic `--host-vpn off` fixture where `scutil --nc status` returns `Disconnected` AND `route -n get relay.phntm.pro` returns `en0` (not `utunN`) → preflight PASSES; if `route` returns `utunN` → preflight FAILS (§7 R7 OFF case, REDLINE-3 blocker 3)
36. `wss3-host-vpn-on-positive-check` — synthetic `--host-vpn on` fixture with `scutil --nc status <svc>=Connected` AND `route -n get relay.phntm.pro` returns interface owned by `<svc>` → preflight PASSES; interface mismatch → FAILS (§7 R7 ON case)
37. `wss3-host-vpn-unverifiable-not-guessed` — synthetic fixture where the operator's VPN client is not in `scutil --nc list` → preflight fail-closed with `host_vpn_unverifiable`; verifier row = `NOT_EVALUABLE` (never guessed OFF/ON)
38. `wss3-preflight-rejects-wifi-enabled` — reporter fixture with `wifi_enabled=true` on the phone → preflight fail-closed `wifi_active_or_mobile_data_off` (REDLINE-3 blocker 6)
39. `wss3-preflight-rejects-mobile-data-disabled` — reporter fixture with `mobile_data_enabled=false` → preflight fail-closed `wifi_active_or_mobile_data_off` (REDLINE-3 blocker 6)
40. `wss3-preflight-rejects-missing-manual-confirmation` — `PROFILE.json.operator_manual_confirmations.auto_data_switching_off=false` OR `other_sim_data_disabled=false` → preflight fail-closed (§7 R6 (d)/(e))
41. `wss3-preflight-rejects-active-data-sub-invalid` — reporter fixture with `SubscriptionManager.getActiveDataSubscriptionId()==INVALID_SUBSCRIPTION_ID` → preflight fail-closed `carrier_attribution_lost` (REDLINE-3 blocker 5)
42. `wss3-checkpoint-rejects-active-data-sub-swap-mid-run` — synthetic mid-run change of `active_data_subscription_id` at pre-cell-4 → `matrix_completion.json` written with `abort_reason=carrier_attribution_lost`, `cells_ran=3` (REDLINE-3 blocker 5)
43. `wss3-preflight-rejects-cross-sim-operator-drift` — active-data sub stays same but `simOperator` on that sub changes (e.g. carrier hard-swap) → preflight fail-closed `carrier_attribution_lost`
44. `wss3-bare-resume-writes-supersedes-and-retry-reason` — bare `resume` on a profile whose latest attempt is incomplete → new attempt directory contains `supersedes_attempt_id: <prev>` AND `retry_reason: "resume_incomplete"` (REDLINE-3 blocker 7 bare-resume lineage)
45. `wss3-denylist-blocks-checkpoint-key-substring` — synthetic evidence file containing the substring `checkpoint_key_hex=deadbeef…` → denylist regex sweep fails the file, evidence discarded, orchestrator fail-closed (REDLINE-3 blocker 2)
46. `wss3-evidence-isolation-raw-value-fixed-string` (per REDLINE-6 blocker 3) — after a full 8-profile track + `compare` + `cleanup`, runs `grep -rF -- "$RAW_SERVICE_VALUE" evidence/` over the evidence tree AND `tar -xzf handoff-*.tar.gz -O | grep -F -- "$RAW_SERVICE_VALUE"` over the handoff tarball body; both MUST return zero matches. `$RAW_SERVICE_VALUE` is the synthetic service name the fixture wrote to `.runtime/` at preflight. **Fixed-string search** — the fixture explicitly does NOT match the literal option-name `host-vpn-service` (that string legitimately appears in packaged scripts + README + this contract). **Failure discipline:** on any hit, the test output must not echo the raw value — only the offending file path + `<RAW_SERVICE_VALUE_REDACTED>` marker. Test asserts this by piping stderr through a sanity assertion that the raw string is NOT present in the fixture's own output.
47. `wss3-evidence-isolation-runtime-tree-absent-from-tar` (per REDLINE-6 blocker 3, defence-in-depth) — after track handoff, `tar -tzf handoff-*.tar.gz | grep -E '(^|/)\.runtime/'` MUST return zero matches. Defence-in-depth against a packaging bug that inadvertently included `.runtime/` in the tar even if the runtime-state file no longer contains sensitive content at that moment. Test creates `.runtime/wss3/<profile-id>/host-vpn-service` before packaging, packages, then asserts the tar-content-listing has no `.runtime/` entries.
48. `wss3-runtime-state-store-permissions` — `preflight` creates `.runtime/wss3/` at mode `0700` AND `.runtime/wss3/<profile-id>/` at mode `0700` (per REDLINE-6 blocker 1 — both directories 0700, not just the parent) AND `host-vpn-service` + `arm-token` at mode `0600`. Tampered file mode (`chmod 0644` on a file) → next verb fail-closed `runtime_state_mode_violation` with exit 4. Tampered directory mode (`chmod 0755` on `<profile-id>/`) → also fail-closed `runtime_state_mode_violation` with exit 4.
49. `wss3-runtime-state-cross-verb-consistency` — `preflight` stores service `S1` + mints `arm_token T1` (writes into both `PROFILE.json` and `.runtime/`); `smoke` invoked with `--host-vpn-service S2` on CLI → fail-closed `host_vpn_service_mismatch` exit 4; `smoke` invoked with no `--host-vpn-service` → picks up `S1` from `.runtime/` and proceeds; deleting `.runtime/wss3/<profile-id>/host-vpn-service` between `preflight` and `smoke` → fail-closed `host_vpn_service_missing` exit 4; mutating the runtime `arm-token` file to a different UUIDv4 → fail-closed `host_vpn_service_stale_arm` exit 4.
50. `wss3-sigint-preserves-runtime-then-resume-retry-reads-same-binding` (per REDLINE-6 blocker 2) — `preflight` arms profile `P1` with service `S1` + `arm_token T1`. `full` starts, operator sends SIGINT mid-cell-3. Trap writes `matrix_completion.json` with `abort_reason=operator_interrupt` (terminal `ABORTED_INSTRUMENTED`). Test asserts BOTH `.runtime/wss3/P1/host-vpn-service` and `.runtime/wss3/P1/arm-token` still exist after the trap fires (retention across abort per §3.6 invariant 7). Operator invokes `resume --retry --profile-id P1 --reason mid_run_operator_interrupt`. Test asserts the new attempt directory reads `S1` from `.runtime/` (no CLI `--host-vpn-service` passed) AND validates the `arm-token` still matches `PROFILE.json.arm_token` of the ORIGINAL preflight (the retry doesn't re-arm-token; runtime binding is per profile-id, not per attempt). New attempt writes `supersedes_attempt_id` linking back to the interrupted attempt.
51. `wss3-run-as-rm-always` — synthetic operator-package fixture drives `smoke` end-to-end against a mocked `adb` (fake `run-as cat`). Asserts `adb shell run-as phantom.android rm /data/data/phantom.android/files/wss3/network_profile.json` fires on: (a) normal success path after `run-as cat`; (b) failure path where the reporter file was present but `run-as cat` errored (mock returns non-zero after emitting partial data); (c) SIGINT trap path where the operator kills `smoke` mid-checkpoint (`trap` invokes cleanup which still fires `run-as rm`). Complements Kotlin fixture 70's within-JVM guarantees per REDLINE-5 blocker 3 — shell-owned deletion is proved by shell, JVM-owned scrubbing is proved by JVM.
52. `wss3-cleanup-verb-preserves-aborted-retention` (per REDLINE-6 blocker 2, REVISED per REDLINE-7 blocker 1) — fixture leaves profile `P1` in `ABORTED_INSTRUMENTED` (retry candidate) + profile `P2` in `COMPLETE`. Bare `cleanup` (no `--all`) removes `.runtime/wss3/P2/` (COMPLETE-profile retirement — operator's explicit act of giving up P2's retry lifeline) BUT preserves `.runtime/wss3/P1/` (aborted-retention). `cleanup --all` additionally removes `.runtime/wss3/P1/`. `cleanup` never touches a profile whose evidence dir has NO `matrix_completion.json` (still-in-progress). Both P1 and P2 runtime survive the EXIT trap that fired when `full` finished — trap no longer auto-deletes on any terminal state (per REDLINE-7 blocker 1 lifecycle repair).
53. `wss3-complete-preserves-runtime-until-compare-or-cleanup` (per REDLINE-7 blocker 1) — full COMPLETE profile retry lifecycle test. Sequence: (a) `preflight` arms profile `P1` with service `S1`, mints `arm_token T1`; (b) `full` runs to `COMPLETE` with `abort_reason=null`; (c) assert `.runtime/wss3/P1/host-vpn-service` and `.runtime/wss3/P1/arm-token` still exist after `full` exits (retention across `COMPLETE`, not just abort — per REDLINE-7 fix to §3.6 invariant 7); (d) operator invokes `resume --retry --profile-id P1 --reason vpn_provider_change` with NO `--host-vpn-service` on the CLI; (e) new attempt directory is armed successfully (no `host_vpn_service_missing` exit 4); (f) assert new attempt's `PROFILE.arm_token == T1` (retry COPIES retained runtime `arm-token`, does NOT mint a new UUIDv4 — per REDLINE-7 blocker 1 arm_token semantics); (g) assert new attempt's `supersedes_attempt_id` links back to the original COMPLETE attempt AND `retry_reason=vpn_provider_change`; (h) `compare` runs and returns all-8-`GREEN/GREEN`; (i) assert `compare`'s implicit cleanup removed `.runtime/wss3/` in its entirety; (j) attempting `resume --retry --profile-id P1 --reason vpn_provider_change` again now fails with `host_vpn_service_missing` exit 4 (runtime was intentionally reaped by successful compare; a fresh `preflight` would mint a new UUIDv4). This fixture proves the entire completed-profile-retry lifecycle that Round-6 accidentally blocked.

Python (`tests/test_verifier.py` extensions):

54. `test_wss3_verifier_pins_directory_naming`
55. `test_wss3_verifier_pins_profile_json_schema` — asserts every key from the §5 canonical `PROFILE.json` schema is present with the right type. Specifically pins (per REDLINE-6 blocker 1): (a) `arm_token` is a string that matches the UUIDv4 regex `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$` (version-4 nibble + variant-1 nibble enforced); (b) `operator_manual_confirmations` is an object with exactly two boolean keys `auto_data_switching_off` and `other_sim_data_disabled`, both `true` on any GREEN profile; (c) missing `arm_token` OR missing/malformed `operator_manual_confirmations` → profile NOT_EVALUABLE, integrity RED.
56. `test_wss3_verifier_pins_matrix_completion_json_schema` — malformed types → NOT_EVALUABLE
57. `test_wss3_verifier_reads_egress_fingerprint_boolean_only` — verifier never reads any field named `ip_address`/`address`
58. `test_wss3_verifier_matrix_completion_required` — empty sentinel file → NOT_EVALUABLE (empty sentinel forbidden §4.4)
59. `test_wss3_verifier_no_partial_green_state` — RED cell + all-else-GREEN → row.product_outcome == RED
60. `test_wss3_verifier_rejects_forbidden_emitter` — capture contains `sender_wss_frame_written` (old name) → integrity RED
61. `test_wss3_verifier_rejects_missing_recipient_triplet` — capture has sender_relay_ack_received but no recipient triplet → cell = Unresolved (verifier-side classification)
62. `test_wss3_compare_row_shape` — 8 rows, 2 independent columns, canonical order, `retry_lineage_present` footer field when applicable
63. `test_wss3_verifier_rest_blocked_not_partial` — profile with `cells_expected_to_run=6`, both REST cells `BLOCKED`, all 6 WSS GREEN → row `product_outcome=GREEN`, `evidence_integrity=GREEN` (REDLINE-2 blocker 6)
64. `test_wss3_verifier_smoke_tolerates_trailing_dedup_breadcrumbs` — one `fresh` + one `duplicate` + one `reack` on the same CID → OK; two `fresh` → RED (REDLINE-2 blocker 8)
65. `test_wss3_verifier_imports_frozensets_from_verify_evidence` — WSS-3 verifier module reads `RECIPIENT_EVENTS`/`SENDER_EVENTS`/`MATRIX_EVENTS`/`CROSS_RUN_TOLERATED_EVENTS` from the accepted `verify_evidence` module import path, not a local literal (REDLINE-2 blocker 2)
66. `test_wss3_verifier_rejects_lineage_cycle` — attempt A `supersedes_attempt_id=B`, attempt B `supersedes_attempt_id=A` → profile NOT_EVALUABLE (REDLINE-3 blocker 7)
67. `test_wss3_verifier_rejects_lineage_fork` — attempts A₂ and A₃ both name A₁ as `supersedes_attempt_id` → profile NOT_EVALUABLE (branching retry chain forbidden)
68. `test_wss3_verifier_rejects_cross_profile_lineage` — attempt in profile `yota-phone-off-host-on` has `supersedes_attempt_id` pointing to an `attempt_id` recorded under `yota-phone-off-host-off` → profile NOT_EVALUABLE
69. `test_wss3_verifier_rejects_dangling_parent` — `supersedes_attempt_id` names an `attempt_id` that does not exist in the evidence tree → profile NOT_EVALUABLE
70. `test_wss3_verifier_rejects_supersedes_without_retry_reason` — attempt has `supersedes_attempt_id != null` but `retry_reason == null` → profile NOT_EVALUABLE; same for the inverse pair (`retry_reason != null` but `supersedes_attempt_id == null`)
71. `test_wss3_verifier_rejects_explicit_resume_incomplete_reason` — `resume --retry --reason resume_incomplete` CLI invocation → rejected fail-closed (reserved for bare-resume path only)
72. `test_wss3_verifier_rejects_arm_token_disagreement_in_retry_chain` (per REDLINE-7 blocker 1) — two attempts linked by `supersedes_attempt_id` carry different `arm_token` values → profile NOT_EVALUABLE. Positive control: two attempts linked by `supersedes_attempt_id` sharing the same `arm_token` → profile passes lineage check (retry correctly copied the retained runtime `arm-token` rather than minting a fresh UUIDv4).

Kotlin (`apps/android/src/androidUnitTest/kotlin/phantom/android/diagnostic/` extensions — the `network_profile_report` reporter test suite; runs under existing L1 §5.1 Robolectric harness pattern):

73. `DiagnosticNetworkProfileReporterFixedOutputPathTest` — reporter always writes to `Context.filesDir/wss3/network_profile.json`; no code path takes an operator-supplied path (REDLINE-2 blocker 5)
74. `DiagnosticNetworkProfileReporterExtrasWhitelistTest` — receiver rejects broadcast with unknown extras (`report_target`, `output`, etc.); accepts only `subcommand` + `checkpoint_key_hex`
75. `DiagnosticNetworkProfileReporterVpnActiveStateTest` — `NetworkCapabilities.hasTransport(TRANSPORT_VPN)==true` → `has_transport_vpn:true` in output
76. `DiagnosticNetworkProfileReporterNoActiveNetworkStateTest` — `getActiveNetwork()==null` → `active_network_present:false`, `active_network_kind:"OTHER"`, egress attempt not made
77. `DiagnosticNetworkProfileReporterEgressTimeoutTest` — HTTP client returns after >5 s → `hmac_fp_hex=null`, `http_status=null`, `timeout_ms_used:5000`
78. `DiagnosticNetworkProfileReporterEgressNon200Test` — HTTP 502 → `hmac_fp_hex=null`, `http_status:502`
79. `DiagnosticNetworkProfileReporterHmacDeterminismTest` — same `checkpoint_key` + same IP bytes → identical `hmac_fp_hex` (proves the HMAC scheme is deterministic; §4.5). Immediately after computing the hex the reporter clears its local key reference (best-effort — JVM string immutability + GC opacity acknowledged; no claim of a physical heap wipe). The test asserts the observable, verifiable guarantees **that live inside the Kotlin/JVM boundary ONLY** (per REDLINE-5 blocker 3): (a) `hmac_fp_hex` present in the JSON output; (b) the emitted JSON contains NO substring matching `checkpoint_key`, `checkpoint_key_hex`, or `checkpointKey*`; (c) any test-capture sink (fake `Log`, fake stdout, fake file writer used by the reporter under test) is scanned and contains no such substring. Proof that the operator-package shell script actually invokes `adb shell run-as phantom.android rm <path>` on success / failure / trap paths is the shell suite's responsibility, not this Robolectric test — that split lives in new shell fixture `wss3-run-as-rm-always` (§9 fixture 51). Renamed from `…HmacEqualityTest` per REDLINE-4 blocker 4 to remove the unrealistic heap-region assertion.
80. `DiagnosticNetworkProfileReporterDenylistedFieldAbsenceTest` — writes a report where the raw IP `192.0.2.1` was seen; asserts the emitted JSON contains neither the string `192.0.2.1` nor any field named `ip_address` / `address` / `ipv4` / `ipv6`, AND contains no substring matching `checkpoint_key`, `checkpoint_key_hex`, or `checkpointKey*` (REDLINE-3 blocker 2)
81. `DiagnosticNetworkProfileReporterDualSimActiveDataSubTest` — dual-SIM emulator fixture: default sub is Yota, active-data sub is Tele2; reporter reads via `SubscriptionManager.getActiveDataSubscriptionId()` + `TelephonyManager.createForSubscriptionId(id).simOperator` and emits Tele2's operator numeric (25020), NOT Yota's (25011) (REDLINE-3 blocker 5)
82. `DiagnosticNetworkProfileReporterWifiMobileDataTest` — reporter emits `wifi_enabled` = `WifiManager.isWifiEnabled()` result and `mobile_data_enabled` = `TelephonyManager.isDataEnabled()` result on the active-data sub; fixture toggles each on/off in isolation to confirm two-way pinning (REDLINE-3 blocker 6)
83. `DiagnosticDebugManifestContainsAccessWifiStateTest` — **static source-set check (per REDLINE-5 blocker 4)** — reads `apps/android/src/debug/AndroidManifest.xml` as a plain XML file (no Gradle-built merged manifest required) and asserts `<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>` is present exactly once. Runs under `testDebugUnitTest` without needing `processReleaseMainManifest` or `assembleRelease` to have produced any intermediate.
84. `DiagnosticProductionManifestOmitsAccessWifiStateTest` — **static source-set check (per REDLINE-5 blocker 4)** — reads `apps/android/src/androidMain/AndroidManifest.xml` AND (if the file exists) `apps/android/src/release/AndroidManifest.xml` as plain XML files and asserts `ACCESS_WIFI_STATE` is NOT declared in either. Replaces the earlier `DiagnosticReleaseManifestOmitsAccessWifiStateTest` merged-manifest approach — merged-release-manifest is not guaranteed to exist under `testDebugUnitTest`. If the operator-package build wants a stronger merged-manifest gate, it can be added later as an explicit `processReleaseMainManifest` build step in the operator-package Gradle wiring — deferred out-of-scope for now (§13).
85. `DiagnosticNetworkProfileReporterSecurityExceptionFailsClosedTest` — Robolectric shadow forces `WifiManager.isWifiEnabled()` to throw `SecurityException`. Reporter MUST catch it and emit `wifi_enabled: null` + `wifi_read_error: "SECURITY_EXCEPTION"` (never a falsified boolean). Orchestrator-side handling MUST treat that as `wifi_active_or_mobile_data_off` unverifiable → fail-closed. The reporter never substitutes a default `true`/`false` for the missing state (REDLINE-4 blocker 3).
86. `DiagnosticNetworkProfileReporterSourceSetBoundaryTest` — extends `DiagnosticSourceSetBoundaryTest`: (a) `DiagnosticNetworkProfileReporter.kt` exists ONLY under `apps/android/src/debug/kotlin/phantom/android/diagnostic/`, (b) NO file under `apps/android/src/androidMain/` references `DiagnosticNetworkProfileReporter` (regex sweep), (c) release-manifest still contains no new manifest components (the reporter is a helper class piggybacking on the existing `DiagnosticCommandReceiver`)

Every existing WSS-2 fixture stays untouched (verify by `git diff --stat` against the accepted WSS-2 fixture SHAs).

**Total: 86 focused tests** — 53 shell + 19 Python + 14 Kotlin reporter (Round-6 count was 84; Round-7 adds +1 shell fixture 53 for the completed-profile retry lifecycle + +1 Python fixture 72 for arm_token-chain-integrity in the verifier).

---

## 10. Process

Per architect discipline (`feedback_architect_process_change_2026_08_09`):

1. **This contract sheet** (Round-7). Now. Awaits architect review round-8.
2. **Architect review round-8.** REDLINE / GREEN cycle. No code, no ADB, no Gradle in flight.
3. **One implementation round.** Once GREEN: rebase branch `track/wss-3-carrier-vpn-matrix` onto `origin/master@805f7960` (PR #400 merge SHA, per architect note). Then write `run-carrier-vpn-matrix.sh` (verbs `preflight`, `smoke`, `full`, `resume`, `compare`, `cleanup [--all]`), `compare-vpn-matrix.py`, extensions to `verify-evidence.py` (Round-2 blocker 2: WSS-3 verifier `import`s the four frozensets from the accepted module — does NOT restate them; Round-7 adds arm-token-chain-integrity check), `DiagnosticNetworkProfileReporter.kt` + subcommand wiring in existing `DiagnosticCommandReceiver`, `ACCESS_WIFI_STATE` in the debug manifest (REDLINE-4 blocker 3), `.runtime/` state store scaffolding + gitignore entry (REDLINE-5 blocker 1) with `arm_token` UUIDv4 + `operator_manual_confirmations` pinned in canonical `PROFILE.json` (REDLINE-6 blocker 1), retry-copies-arm-token semantics (REDLINE-7 blocker 1), extended `DiagnosticSourceSetBoundaryTest` invariants (Round-2 blocker 5 corrected semantics), all 86 focused tests (53 shell + 19 Python + 14 Kotlin reporter). Local `bash tests/test_shell.sh` + `python3 -m unittest tests.test_verifier` MUST pass; Android `:apps:android:testDebugUnitTest --tests "phantom.android.diagnostic.Diagnostic*"` MUST pass; extended `DiagnosticSourceSetBoundaryTest` + the two static source-set manifest checks (`DiagnosticDebugManifestContainsAccessWifiStateTest` + `DiagnosticProductionManifestOmitsAccessWifiStateTest`) MUST pass proving reporter is source-set-boundary-isolated to `src/debug/` and the permission is declared only in the debug source set.
4. **Synthetic Mac dry-run.** Run the whole 8-profile matrix against a mocked device layer + synthetic `WSS_DIAG` capture. Zero ADB. Zero physical device. Proves the orchestrator, verifier, and compare-report are wired end-to-end.
5. **One diagnostic APK/package.** If dry-run GREEN: build one debug APK (single `assembleDebug`; independent of PR #400 landing) + tar the operator package with LF-safe `git archive` + immutable per-profile `SHA256SUMS.txt` (Q7) + one aggregate `handoff-wss3-matrix-<UTC>.tar.gz`. Hand off to operator.
6. **STOP before ADB.** Contract's implementation ends here. Operator explicitly greenlights the physical run before any `adb` invocation on real devices.

---

## 11. Open questions — answers integrated (from architect REDLINE-1)

| Q | Round-0 (initial proposal) | Round-1 (architect answer) |
|---|---|---|
| Q1 evidence path root | `operator-package/evidence/`, gitignored | **Confirmed** — `operator-package/evidence/`, gitignored; raw evidence never committed |
| Q2 REST cell scope | 1 control cell per direction | **Confirmed** — exactly 2 REST control cells inside the 8 total (6 WSS + 2 REST) |
| Q3 direct `preflight` on completed profile | proposed allow with warning | **Refused** — direct `preflight` MUST refuse; only `resume --retry --reason` re-runs a completed profile |
| Q4 duplicate-profile-dir `compare` policy | proposed latest-UTC wins with warning | **Forbidden** — `latest wins` disallowed; duplicates without `supersedes_attempt_id` provenance → NOT_EVALUABLE |
| Q5 signed-prekey readiness on VPN-flipped state | proposed NO retry | **Amended** — bounded polling: up to 60 s at 5 s step, then fail-closed |
| Q6 egress fingerprint endpoint | proposed `api.ipify.org` | **Confirmed** — allowed for debug diagnostics only, 5 s timeout, raw response memory-only |
| Q7 track hand-off shape | proposed one aggregate tarball | **Confirmed** — one aggregate tarball + immutable per-profile `SHA256SUMS.txt` |

No open questions remaining from Round-0 — all 7 answered. Round-7 does not add new questions; awaits architect verdict on the REDLINE-7 fixes integrated in §3.4 (retry copies retained arm_token; verifier lineage extended), §3.6 (invariants 7+8 rewritten — trap never auto-deletes; only `compare`/`cleanup [--all]` do), §4.4 (trio→host↔emu wording), §4.6 (drift-check preamble corrected), §8 failure-matrix rows corrected, §9 (shell fixtures 52 revised + 53 added; Python fixture 72 added) — see Round-7 change log at end of this document.

---

## 12. References

- Accepted WSS-2 contract + operator package: `docs/tracks/direct-wss/direct-wss-yota-contract.md` (§12.1..§12.10 + supersedes) + `docs/tracks/direct-wss/operator-package/**`.
- WSS-2 landing PR: #399 (merged 2026-08-13 to master at `bf75d626`, now `5eab35cf` after 4 site/docs commits).
- Merged PR #399 diagnostic surface (this track's read-only consumer + one additive `network_profile_report` subcommand):
  - `apps/android/src/androidMain/kotlin/phantom/android/diagnostic/{WssDiag,DiagnosticTransportGuard}.kt`
  - `apps/android/src/debug/kotlin/phantom/android/diagnostic/{DiagnosticBootInit,DiagnosticCommandReceiver,DiagnosticSendCoordinator,DiagnosticTransportPinStore}.kt`
  - `apps/android/src/androidUnitTest/kotlin/phantom/android/diagnostic/DiagnosticSourceSetBoundaryTest.kt` (extended by §4.5 to cover the new reporter)
  - `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/{DefaultMessagingService.kt,WssDiagBridge.kt}`
- WSS-2 archives (supporting context, NOT substitute):
  - `yota-wss-GREEN-20260812T171418Z.tar.gz` sha256 `f33a1387280193e8ec7e3574b63b53e1ce399deb152df1d2646db386030c9266`
  - `tele2-wss-GREEN-20260812T182237Z.tar.gz` sha256 `226620a26640eceb8326367d71d8845a4d0fe69acdabfe6e198a4d766ee95722`
- Process discipline: `feedback_architect_process_change_2026_08_09` (contract before code); `feedback_council_facts_first_when_blocker_2026_06_15`; `feedback_repo_artifacts_english_only_2026_06_17`; `feedback_diagnostic_design_must_isolate_one_variable`.
- Terminology memory: `reference_transport_modes_5_chain` (5 outer-arm chain).
- PR #400 (onboarding-baseline-landing) — NOT a dependency; §1 explicit non-goal.

---

## 13. Deferred / out-of-scope for Round-7

- Explicit VPN provider recommendations. Operator picks (and passes the resolved Network Configuration service name via `--host-vpn-service`).
- Third carrier (Beeline, MTS, etc.) — additive future round.
- Automated Mac / phone VPN toggle. Manual toggle per operator ask.
- Post-track PR merge / GitHub Release automation. Track ends at operator handoff.
- Full-body raw HTTP inspection of `api4.ipify.org` response (only the HMAC hex escapes memory; even the raw IP body is discarded — §7 R10 + §4.5).
- Any three-way `equal_all_three` egress check across host/phone/emu. Deliberately dropped per REDLINE-3 blocker 1 — the phone is on a different network path by construction (carrier vs Wi-Fi/host-VPN), so requiring equality would fail every valid profile.
- **Any phone HMAC or phone cross-profile fingerprint comparison** (REDLINE-4 blocker 1). Two HMACs computed with two different random keys are near-guaranteed to differ even for the same input, so `phone_fingerprint_differs_from_no_vpn_baseline` was mathematically vacuous. Phone-VPN attribution rests on the five orthogonal §7 R5+R6 checks + successful smoke delivery inside the profile.
- Persisting `checkpoint_key` in any form (reproducibility of the raw IP from HMAC is deliberately foreclosed per REDLINE-3 blocker 2).
- Persisting the raw `--host-vpn-service` string in evidence or the handoff tarball (REDLINE-4 blocker 2 + REDLINE-5 blocker 1). The service string lives in a segregated `chmod 0600` `.runtime/` state file for cross-verb continuity, deleted at cleanup, never packaged.
- Persisting the identity of any OTHER Connected Network Configuration VPN service enumerated during the `host_vpn=off` any-other-VPN check (REDLINE-5 blocker 2). Only the derived pass/fail boolean escapes to evidence.
- Physical heap-wipe proof for the HMAC key inside the JVM (REDLINE-4 blocker 4). Kotlin/Java string immutability + GC opacity mean we can only assert the observable, verifiable guarantees (key absent from JSON output, absent from test-capture sinks, absent from evidence + tar); the shell-owned `run-as rm` invariant is proved by shell fixture 49 rather than by the JVM test — that ownership split lands per REDLINE-5 blocker 3.
- Merged-release-manifest permission-absence check via a Gradle-generated `merged_manifest/release` intermediate (REDLINE-5 blocker 4). Replaced by static reads of `src/androidMain/AndroidManifest.xml` + `src/release/AndroidManifest.xml` inside `testDebugUnitTest`. A stronger `processReleaseMainManifest`-gated merged-manifest test can be added later as an explicit operator-package Gradle step — not required for Round-5 GREEN.
- Instrumenting anti-cellular-fallback detection beyond the two-way Wi-Fi/mobile-data proof + `active_data_subscription_id` pinning. Manual operator confirmations of auto-data-switching and other-SIM-data cover the remaining ambiguity — an automated proof would require framework-level APIs not exposed to third-party apps.

---

## Round-7 change log (bounded amendment 2026-08-14)

Architect REDLINE-7 closed one P0 lifecycle contradiction that the Round-6 cleanup contract accidentally introduced, plus one non-blocking doc-hygiene item. All prior 3 REDLINE-6 items remain closed. Two pin-points closed:

- **P0 Blocker 1 — completed-profile retry was impossible.** Round-6 §3.6 invariant 7 auto-deleted `.runtime/wss3/<profile-id>/*` on `COMPLETE`, while §3.4 explicit `resume --retry --profile-id <id> --reason <enum>` and §3.6 invariant 6 both required `.runtime/host-vpn-service` + `arm-token` to still exist. Result: the only supported way to re-run a completed profile hit `host_vpn_service_missing` exit 4 before arming. Round-7 rewrites §3.6 invariant 7: the `EXIT/INT/TERM` trap NEVER auto-removes runtime state on ANY terminal state — including `COMPLETE`. Removal happens exclusively via the two paths in §3.6 invariant 8: (a) implicit cleanup at the end of a successful `compare` (all-8-`GREEN/GREEN`; mixed report requires operator's typed accept-mixed confirmation first); (b) explicit `cleanup` (bare removes COMPLETE-profile runtime = operator gives up retry lifeline) or `cleanup --all` (also removes aborted-profile runtime). §3.4 explicit-retry contract extended: `arm_token` semantics on retry — new attempt COPIES the retained `.runtime/wss3/<profile-id>/arm-token` into `PROFILE.arm_token`, does NOT mint a new UUIDv4. Only a genuinely fresh `preflight` (after `cleanup` wiped the runtime for that profile-id) mints a new UUIDv4. §3.4 verifier lineage discipline extended: linked attempts must share the same `arm_token`, disagreement → NOT_EVALUABLE. §9 shell fixture 52 revised to reflect the new bare-`cleanup` semantics (bare cleanup DOES retire COMPLETE-profile runtime as an explicit operator act; the trap does not). §9 shell fixture 53 (new) proves the full completed-profile retry lifecycle end-to-end: preflight(S1,T1) → full COMPLETE → runtime retained → resume --retry with no CLI service reads S1/T1 → new PROFILE.arm_token == T1 → successful compare implicit-cleans runtime → subsequent resume --retry now correctly errors host_vpn_service_missing exit 4. §9 Python fixture 72 (new) pins the verifier-side arm_token chain-integrity check.
- **Non-blocking — §8 failure-matrix rows and §4.6 preamble still said "host/phone/emu trio" for HMAC divergence.** Phone HMAC was removed in Round-4 blocker 1; the trio terminology survived as legacy wording. §8 rows corrected to "Host↔emulator egress divergence" + "Mixed address-family between host and emulator", with explanatory note that phone attribution rests on §7 R5+R6 orthogonal state + successful smoke delivery, not HMAC parity. §4.4 `mixed_address_family` enum entry clarified. §4.6 checkpoint drift-check preamble rewritten to describe the two-participant HMAC scheme + separate phone attribution path explicitly.

Test count grew from 84 (Round-6) → 86 (Round-7): +1 shell fixture 53 (completed-profile retry lifecycle end-to-end), +1 Python fixture 72 (verifier rejects arm_token disagreement in a retry chain). Fixture 52 revised in place. Python renumbered 54-72 (was 53-70); Kotlin renumbered 73-86 (was 71-84). Total 53 shell + 19 Python + 14 Kotlin.

Round-6 §3.6 invariant 7 wording (COMPLETE-auto-deletes-runtime) is superseded here — the historical Round-6 log below is left intact for audit trail.

All 7 Round-0 open questions remain answered. Round-7 adds no new open questions. Contract is now internally consistent across the completed-profile retry lifecycle. Ready for architect Round-8 review — no code, no ADB, no Gradle in flight. When Round-8 GREEN lands, implementation begins on branch rebased onto `origin/master@805f7960`.

---

## Round-6 change log (bounded amendment 2026-08-14)

Architect REDLINE-6 closed three internal inconsistencies inside the Round-5 runtime-state contract. All prior 4 REDLINE-5 items remain closed. Three pin-points closed:

- **Blocker 1 — `PROFILE.arm_token` was referenced but not in schema; `<profile-id>` mode was unspecified.** §5 canonical `PROFILE.json` schema extended: adds `arm_token` (UUIDv4, minted at preflight) and lifts `operator_manual_confirmations: {auto_data_switching_off: bool, other_sim_data_disabled: bool}` from a §7 R6 side-note into the primary schema alongside the existing keys. §3.6 invariant 3 tightened: **both** `.runtime/wss3/` and each `.runtime/wss3/<profile-id>/` directory are `chmod 0700` (Round-5 wording only specified the parent). §9 Python fixture 54 (`test_wss3_verifier_pins_profile_json_schema`) rewritten to pin the UUIDv4 form of `arm_token` (version-4 nibble + variant-1 nibble regex-enforced) and the exact two-boolean shape of `operator_manual_confirmations`, both required for GREEN.
- **Blocker 2 — SIGINT/abort simultaneously deleted and preserved runtime state.** §3.6 invariant 7 was self-contradictory: it listed `ABORTED_INSTRUMENTED` as terminal-and-cleaned while claiming "non-terminal exits preserve" — but SIGINT writes `abort_reason=operator_interrupt` which IS `ABORTED_INSTRUMENTED`. Resolution: runtime state is retained across every non-`COMPLETE` terminal state (`ABORTED_INSTRUMENTED`, `SMOKE_RED`, `FAIL_CLOSED_UNVERIFIED_STATE`, `NOT_EVALUABLE`) and every non-terminal exit. It is removed only on `COMPLETE`, on a successful final `compare` (implicit), or by explicit `cleanup` verb (with `--all` needed to also remove aborted-retention state). §3.6 invariant 8 rewritten with the explicit two-tier `cleanup` policy. **Error classification (per architect ask):** the five runtime-state error codes (`host_vpn_service_missing`, `host_vpn_service_stale_arm`, `host_vpn_service_mismatch`, `runtime_state_mode_violation`, `runtime_state_stale`) are now formally classified as **pre-arm CLI failures** with documented exit code 4 — they never write `matrix_completion.json`. The `matrix_completion.abort_reason` enum (§4.4) covers mid-run drift only; the two enums do not overlap. §9 shell fixture 50 (new) proves SIGINT→resume--retry continuity: runtime retained across SIGINT, `resume --retry` reads the same service + arm-token binding without CLI re-entry. §9 shell fixture 52 (new) proves `cleanup` preserves aborted-retention (bare `cleanup` removes only `COMPLETE` profiles; `cleanup --all` removes aborted profiles too).
- **Blocker 3 — evidence-isolation grep would false-fire on the literal option name.** §3.6 "Evidence isolation invariant" rewritten. Round-5 wording `grep -rE 'host-?vpn-?service|<the raw service string>'` would necessarily hit the literal option-name string `host-vpn-service` in packaged scripts + README + this contract sheet, turning the invariant permanently RED. Round-6 replaces it with two orthogonal checks: (a) `grep -rF -- "$RAW_SERVICE_VALUE" evidence/` + `tar -xzf handoff-*.tar.gz -O | grep -F -- "$RAW_SERVICE_VALUE"` — **fixed-string** search of the runtime service VALUE only, using `--` to prevent option-injection from `--`-prefixed service names; (b) `tar -tzf handoff-*.tar.gz | grep -E '(^|/)\.runtime/'` — the `.runtime/` subtree is absent from the tarball listing (defence-in-depth). The literal option-name `host-vpn-service` is explicitly allowed in packaged scripts, docs, and README — the invariant guards the value, not the name. **Failure discipline:** on any hit, the test output MUST NOT echo `$RAW_SERVICE_VALUE`; only the offending file path + a `<RAW_SERVICE_VALUE_REDACTED>` marker may appear (prevents the failure itself from leaking what it's checking against). §9 shell fixture 46 rewritten to fixed-string + failure-output-sanity; §9 shell fixture 47 (new) pins the tar-content absence.

Test count grew from 81 (Round-5) → 84 (Round-6): +3 shell fixtures — new 47 (tar `.runtime/` absence), new 50 (SIGINT→resume--retry continuity), new 52 (cleanup preserves aborted retention); fixture 46 rewritten in-place from regex to fixed-string; fixture 48 tightened for per-profile-id 0700 dir mode; fixture 49 tightened for `arm_token` write-both-places. Python fixture 54 (formerly 51) tightened for `arm_token` UUIDv4 + `operator_manual_confirmations` two-boolean schema. Kotlin block unchanged; renumbered 71-84 to slot new shell tests ahead. Shell 1-52, Python 53-70, Kotlin 71-84.

Round-5 wording about invariant 7 cleanup semantics and about the evidence-isolation regex is superseded here — the historical Round-5 log below is left intact for audit trail.

All 7 Round-0 open questions remain answered. Round-6 adds no new open questions. Ready for architect Round-7 review — no code, no ADB, no Gradle in flight. When Round-7 GREEN lands, implementation begins on branch rebased onto `origin/master@805f7960`.

---

## Round-5 change log (bounded amendment 2026-08-14)

Architect REDLINE-5 closed 2 P0 orchestration holes + 2 P1 test-scope defects. All prior 4 REDLINE-4 items remain closed. Four pin-points closed:

- **Blocker 1 — `--host-vpn-service` was lost between separate CLI processes.** §3.1 CLI shape line, §3.6 (new subsection) — the mandatory `--host-vpn-service` is arm-time input to `preflight`; `smoke`, `full`, and `resume` run as fresh processes and need it too but must not persist it to `PROFILE.json`/evidence. New segregated runtime state store: `docs/tracks/direct-wss/operator-package/.runtime/wss3/<profile-id>/{host-vpn-service,arm-token}` at `chmod 0600` (parent `0700`), gitignored, evidence-isolated, deleted at terminal-state trap + explicit `cleanup` verb. `preflight` writes atomically (tmp→fsync→rename); `smoke`/`full`/`resume` read and cross-check `arm-token` binding to `PROFILE.arm_token`, fail-closed with `host_vpn_service_missing` / `host_vpn_service_stale_arm` / `host_vpn_service_mismatch` / `runtime_state_mode_violation` / `runtime_state_stale` per §3.6 invariant table. §9 shell fixtures 46, 47, 48 pin the invariants; §9 shell fixture 46 pins the evidence-isolation regex sweep. Alternative "require the flag on every verb" rejected: still needs the same cross-verb consistency check and leaks the service name into operator shell history.
- **Blocker 2 — `host_vpn=off` missed the case where a DIFFERENT VPN was active.** §7 R7, §4.6 checkpoint table (OFF row), §4.4 `abort_reason` enum, §8 failure matrix all updated. OFF now requires TWO conditions: (a) the selected `--host-vpn-service` is `Disconnected`; (b) `route -n get <relay_host>` returns an interface NOT owned by ANY Connected Network Configuration VPN service (orchestrator enumerates `scutil --nc list`, filters to `Connected` services, resolves each service's owned interface into an in-memory set, and rejects the profile if the relay-route interface is in that set). Failure → new `host_vpn_other_service_active` abort_reason. In-memory only — the identity of any other VPN service never lands in evidence. Prevents the "primary VPN off, secondary VPN still on" false-pass.
- **Blocker 3 — Kotlin fixture 70 straddled JVM and shell boundaries.** §9 fixture 70 tightened to Kotlin/JVM-only assertions (`hmac_fp_hex` present + no `checkpoint_key*` substring in JSON output or test-capture sinks). Shell-owned `adb shell run-as phantom.android rm <path>` proof moved to new §9 shell fixture 49 (`wss3-run-as-rm-always`) which drives `smoke` end-to-end against a mocked `adb` and asserts `run-as rm` fires on normal success, `run-as cat` failure, AND SIGINT trap cleanup paths. Each test now owns only what its runtime can actually observe.
- **Blocker 4 — release merged-manifest test relied on a not-guaranteed Gradle intermediate.** §9 fixture 75 (Round-4 name `DiagnosticReleaseManifestOmitsAccessWifiStateTest`) rewritten as fixture 79 `DiagnosticProductionManifestOmitsAccessWifiStateTest` — reads `apps/android/src/androidMain/AndroidManifest.xml` (and optional `src/release/AndroidManifest.xml`) as plain XML files inside `testDebugUnitTest`, asserts `ACCESS_WIFI_STATE` is absent from both. Fixture 78 (debug-manifest presence, Round-4 name unchanged) is likewise rewritten to read `src/debug/AndroidManifest.xml` as plain XML — no dependency on `merged_manifest/debug` intermediates either. A stronger `processReleaseMainManifest`-gated merged-manifest test is available as a future explicit build step (§13 deferred).

Test count grew from 77 (Round-4) → 81 (Round-5): +4 shell fixtures 46-49 (evidence-isolation regex sweep, runtime-state permissions, cross-verb consistency, `run-as rm` on success/failure/trap). Python 46-63 renumbered to 50-67 to slot the new shell block ahead. Kotlin 64-77 renumbered to 68-81; fixture 70 (HmacDeterminism) rewritten JVM-only; fixture 78 (DebugManifestContainsAccessWifiState) rewritten as static source-set read; fixture 79 (renamed to ProductionManifestOmitsAccessWifiState) rewritten as static source-set read.

Round-4 wording about merged-release-manifest and about fixture 70's shell-touching claim is superseded here — the historical Round-4 log below is left intact for audit trail.

All 7 Round-0 open questions remain answered. Round-5 adds no new open questions. Ready for architect Round-6 review — no code, no ADB, no Gradle in flight. When Round-6 GREEN lands, implementation begins on branch rebased onto `origin/master@805f7960`.

---

## Round-4 change log (bounded amendment 2026-08-14)

Architect REDLINE-4 closed 3 P0 correctness holes + 1 P1 test-realism defect that survived Round-3 review. All prior 9 REDLINE-3 items remain closed. Rebase target for implementation phase: `origin/master@805f7960` (PR #400 merge SHA). Four pin-points closed:

- **Blocker 1 — phone HMAC inference was mathematically vacuous.** §4.5, §4.6 checkpoint table, §4.6 log discipline, §5 `EGRESS_FINGERPRINT.json` schema, §7 R8, §7 R10, §13 deferred all rewritten. Phone reporter no longer computes any HMAC. `phone_hmac_fp_hex`, `address_family_phone`, and the derived `phone_fingerprint_differs_from_no_vpn_baseline: bool` are all removed. Rationale: two HMACs computed with two different random keys differ with overwhelming probability even for the same input, so the "signal" was a constant `true` regardless of whether the phone's egress path actually changed. Phone-VPN attribution now rests on §7 R5 (active-data SIM + operator numeric pinned) + §7 R6 (has_transport_vpn + Wi-Fi off + mobile-data on + manual confirmations) + real-world proof that at least one WSS envelope is delivered inside the profile (§3.2 smoke) — a stronger composite than the broken hash comparison ever was.
- **Blocker 2 — `<service>` for Mac VPN was underspecified; OFF case contradicted the `unverifiable` case.** §3.1 preflight command shape, §3.1 step 6, §4.6 checkpoint table (three rows collapsed to two consistent rows), §5 denylist, §7 R7, §8 failure matrix all reworked around a mandatory `--host-vpn-service "<name>"` CLI parameter. The service MUST exist in `scutil --nc list` for BOTH `--host-vpn=on` and `--host-vpn=off` profiles — the same service anchors the profile in either direction. Absent-service at preflight = `host_vpn_unverifiable` → `NOT_EVALUABLE` BEFORE arm. ON = `Connected` + relay route via service iface. OFF = `Disconnected` + relay route NOT via service iface. The raw service string is added to the §5 denylist and never persisted — only the derived booleans escape to evidence.
- **Blocker 3 — `WifiManager.isWifiEnabled()` needed `ACCESS_WIFI_STATE`.** §4.5 grows a permission-contract block. The debug manifest at `apps/android/src/debug/AndroidManifest.xml` adds exactly one `<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>` line so `isWifiEnabled()` returns without `SecurityException` inside `network_profile_report`. Release manifest is unchanged (permission ships only in debug — production users never see it). Reporter catches `SecurityException` and emits `wifi_enabled: null` + `wifi_read_error: "SECURITY_EXCEPTION"` rather than falsifying the boolean. Three new Kotlin tests (fixtures 74, 75, 76) pin: debug-manifest presence, release-manifest absence, reporter fail-closes on SecurityException. `TelephonyManager.isDataEnabled()` already covered by existing `ACCESS_NETWORK_STATE`.
- **Blocker 4 — heap-region grep assertion was unrealistic.** §9 fixture 70 renamed `DiagnosticNetworkProfileReporterHmacEqualityTest` → `DiagnosticNetworkProfileReporterHmacDeterminismTest`. The claim of a JVM-heap-grep proving the key was physically wiped is dropped — Kotlin/Java string immutability + GC opacity make that unprovable by unit test. The rewritten fixture asserts only verifiable guarantees: (a) `hmac_fp_hex` present in JSON output, (b) emitted JSON contains no `checkpoint_key*` substring, (c) test-capture sinks (fake `Log`, fake stdout, fake file writer) contain no `checkpoint_key*` substring, (d) fixed app-owned reporter file is `run-as`-deleted after read. In-heap key lifecycle is documented as `best-effort memory lifecycle` in §4.5. Round-3 change-log claim that fixture 70 "hardened to also assert the key is wiped after computation" is superseded here.

Test count grew from 74 (Round-3) → 77 (Round-4): +3 Kotlin fixtures 74–76 for `ACCESS_WIFI_STATE` debug-manifest presence / release-manifest absence / reporter `SecurityException` fail-closed. Fixture 70 renamed but not counted twice. Fixture count breakdown: 45 shell + 18 Python + 14 Kotlin reporter.

Round-3 wording about phone HMAC (Round-3 change-log Blocker 1 last two sentences) is superseded by this Round-4 amendment — the historical Round-3 log below is left intact for audit trail.

All 7 Round-0 open questions remain answered. Round-4 adds no new open questions. Ready for architect Round-5 review — no code, no ADB, no Gradle in flight. When Round-5 GREEN lands, implementation begins on branch rebased onto `origin/master@805f7960`.

---

## Round-3 change log (bounded amendment 2026-08-14)

Architect REDLINE-3 tightened the threat model along four axes: (a) prevent HMAC-key persistence from re-enabling raw-IP recovery, (b) recognise that phone and host/emu ride different network paths so a three-way equality was structurally wrong, (c) prove phone transport with two-way positive checks (VPN + Wi-Fi radio off + mobile-data on + active-data-sub pinned) not just `hasTransport(VPN)`, (d) close lineage loopholes so bare `resume` still records provenance and cycles/forks can't launder a broken chain. Nine pin-points closed:

- **Blocker 1 — three-way egress equality was structurally wrong.** §4.5, §4.6, §7 R8 rewritten: HMAC parity is `equal_host_emu` ONLY. Phone rides carrier (Yota/Tele2), host+emu ride Wi-Fi (± host_vpn); their public IPs SHOULD differ. Phone HMAC still captured (own fresh key, immediately wiped) but used only within-carrier for the verifier-side `phone_fingerprint_differs_from_no_vpn_baseline: bool` VPN-flip attribution — never compared to host/emu. `path_divergence_host_emu` added to `abort_reason` enum. §13 explicit deferred item.
- **Blocker 2 — HMAC `checkpoint_key` must never be persisted.** §4.5, §4.6, §5 denylist, §7 R10, §7 R16, §13 all tightened. `checkpoint_key_hex_used` removed from `checkpoint_log.json`. Denylist regex sweep fails any occurrence of `checkpoint_key`, `checkpoint_key_hex`, `checkpoint-key`, `checkpointKey`, `checkpointKeyHex` in any evidence file. §9 fixtures 34 + 45 pin. Rationale: persisting the key + persisting the HMAC = brute-force the ≤2³² IPv4 space to recover the raw egress IP.
- **Blocker 3 — host_vpn state needs two-way positive check.** §4.6 checkpoint table + §7 R7 split into ON case (Connected + relay-via-VPN-iface) and OFF case (Disconnected + relay-NOT-via-VPN-iface). `scutil --nwi` + utun presence alone insufficient in EITHER direction. Unverifiable client → `host_vpn_unverifiable` (unchanged). §9 fixtures 35, 36, 37 pin.
- **Blocker 4 — `matrix_completion.json` cell-count wording was self-contradictory.** §3.3 + §4.4 reworded around one unified schema: `cells_declared=8` (frozen manifest), `cells_expected_to_run ∈ {6, 8}` (equals count of `blocked=false` cells at arm time; REST-disabled → 6), `cells_ran=N` (actually executed). `cells_ran == cells_expected_to_run` is complete regardless of whether that number is 6 or 8. `retry_reason` field added; `abort_reason` enum extended with `host_vpn_unverifiable`, `mixed_address_family`, `path_divergence_host_emu`, `carrier_attribution_lost`, `wifi_active_or_mobile_data_off`.
- **Blocker 5 — dual-SIM carrier attribution via active-data sub.** §4.5, §7 R5 rewritten: reporter must resolve `SubscriptionManager.getActiveDataSubscriptionId()` first, then `TelephonyManager.createForSubscriptionId(id).simOperator`. Bare `getSimOperator()` (default-sub) is NOT accepted on dual-SIM. Fields `active_data_subscription_id` + `active_data_sim_operator_numeric` added; INVALID / swap / operator change mid-run → `carrier_attribution_lost` fail-closed. §9 fixtures 41, 42, 43, 72 pin.
- **Blocker 6 — cellular-path proof needs Wi-Fi off + mobile-data on + manual confirmations.** §4.5, §7 R6 rewritten: reporter emits `wifi_enabled` (must be false) + `mobile_data_enabled` (must be true on active-data sub). Two manual operator confirmations (`auto_data_switching_off`, `other_sim_data_disabled`) recorded at preflight into `PROFILE.json.operator_manual_confirmations`; both required. §3.1 preflight steps renumbered. Drift → `wifi_active_or_mobile_data_off` fail-closed. §9 fixtures 38, 39, 40, 73 pin.
- **Blocker 7 — bare `resume` must still record lineage; verifier must reject broken chains.** §3.4 bare-resume path now writes `supersedes_attempt_id = <prev-incomplete-attempt-id>` + `retry_reason = "resume_incomplete"`. Explicit-retry CLI rejects the reserved `resume_incomplete` value. Verifier rejects six lineage violations as NOT_EVALUABLE: `latest_wins` fallback needed, cycle, fork (two attempts naming the same parent), cross-profile lineage, dangling parent, unpaired `supersedes_attempt_id`/`retry_reason`. §9 fixtures 44, 58-63 pin.
- **Blocker 8 — stale salt / IPv6-fixture / partial-report-schema wording cleaned up.** §5 `EGRESS_FINGERPRINT.json` schema replaced with HMAC-only fields; §5 denylist adds `checkpoint_key*`; §5 allowlist reworded to explain WHY the HMAC hex is safe to persist (single-use key wiped); §7 R16 dropped "egress salt" language; §9 fixture 19 renamed `wss3-trap-zeroes-checkpoint-key-on-abort`; §9 fixture 30 renamed to test `mixed_address_family` rather than "IPv6 fixture"; §9 fixture 31 uses `hmac_fp_hex` field name.
- **Blocker 9 — doc hygiene.** §10 process now says "Round-3 → Round-4 architect review"; §13 heading is "Deferred / out-of-scope for Round-3"; §11 footer updated; header + §0 executive summary lead the document as Round-3.

Test count grew from 54 (Round-2) → 74 (Round-3): +12 shell (fixtures 34-45) covering checkpoint-key persistence denylist, host-VPN two-way positive/negative, Wi-Fi/mobile-data pinning, missing-manual-confirmation, active-data-sub INVALID/swap/operator-drift, bare-resume lineage, denylist-checkpoint-key substring; +6 Python verifier tests (fixtures 58-63) covering lineage cycles/forks/cross-profile/dangling/orphan/reserved-reason; +2 Kotlin reporter tests (fixtures 72, 73) covering dual-SIM active-data sub + Wi-Fi/mobile-data emission. Fixture 70 (HMAC equality) hardened to also assert the key is wiped after computation.

All 7 Round-0 open questions remain answered. Round-3 adds no new open questions. Ready for architect Round-4 review — no code, no ADB, no Gradle in flight.

---

## Round-2 change log (bounded amendment 2026-08-14)

Architect REDLINE-2 was schema-precision and threat-model tightening — the Round-1 approach was already accepted, individual gates needed corrections. Ten pin-points closed:

- **Blocker 1 — canonical cell IDs.** §3.3 rewritten: IDs match `CANONICAL_MATRIX_TRIPLES`/`CANONICAL_REST_CELL_IDS` in accepted `verify-evidence.py:100-111` (`wss.<direction>.<scenario>`, not `wss.<scenario>.<direction>`). All 8 rows corrected.
- **Blocker 2 — whitelist verbatim from verify-evidence.py.** §4.1 rewritten: full copy of `RECIPIENT_EVENTS` / `SENDER_EVENTS` / `MATRIX_EVENTS` / `CROSS_RUN_TOLERATED_EVENTS` frozensets from `verify-evidence.py:112-146`, with `ALLOWED_COMMAND_RESULTS` too. Adds the 5 events the Round-1 list missed (`sender_send_attempt_started`, `sender_enqueue`, `sender_ack_watchdog_requeued`, `diagnostic_pin_active`, `diagnostic_state_cleared`, `diagnostic_canary`, `diagnostic_send_rejected_*`). WSS-3 verifier IMPORTS the frozensets by reference, does NOT compose a new list. §9 fixture 45 pins this discipline.
- **Blocker 3 — HMAC egress fingerprint.** §4.5 replaces broken local-salt SHA-256 with orchestrator-generated `checkpoint_key` broadcast to phone + emu, host uses locally. Fingerprint = `HMAC-SHA256(checkpoint_key, canonical_ip_bytes)[0:16]`. Three participants produce comparable fingerprints. §7 R10 updated. §9 fixture 52 pins.
- **Blocker 4 — IPv4/IPv6 discipline.** §4.5 pins endpoint to `api4.ipify.org` (IPv4-only). `address_family` field required + must be `AF_INET` on all three participants; different families → `mixed_address_family` fail-closed → `NOT_EVALUABLE`. §7 R8 updated. §9 fixture 30 test coverage.
- **Blocker 5 — no caller-controlled path.** §4.5 removes `report_target` extra. Reporter writes to fixed app-owned `Context.filesDir/wss3/network_profile.json`; orchestrator reads via `run-as`. Extras whitelist enforced (only `subcommand` + `checkpoint_key_hex`). Source-set boundary invariant corrected: reporter is a helper class NOT a manifest component, so the correct test is (a) source-set location, (b) no androidMain refs, (c) no new receiver/provider in release manifest. §9 fixtures 46, 47, 54 pin.
- **Blocker 6 — REST-disabled compatibility.** §3.3 adds explicit paragraph: 8-cell declaration constant; `cells_expected_to_run` may be 6 if the two REST cells are legitimately `BLOCKED` by relay capability disable. `matrix_completion.json` schema (§4.4) already had the field; §3.5, §7 R15, §8 failure matrix all updated to distinguish `BLOCKED-legit` from `RED`. §9 fixture 43 pins.
- **Blocker 7 — retry lineage tightened.** §3.4 requires `--profile-id`; `--reason` is a fixed ENUM (`vpn_provider_change`, `sim_reseat`, `sdk_environment_recovery`, `mid_run_operator_interrupt`, `network_transient_recovery`), free text rejected. Every attempt after the first for a given profile-id MUST carry `supersedes_attempt_id`, even after unfinished / aborted prior attempts. Failure matrix §8 no longer suggests deleting duplicate evidence — lineage-link is the only accepted resolution. §9 fixtures 11, 12, 24, 25 pin.
- **Blocker 8 — smoke dedup relaxation.** §3.2 amended: exactly one `dedup_gate=fresh` per direction; trailing `duplicate`/`reack` on same CID TOLERATED (normal watchdog-requeue breadcrumbs). Fail-closed only on ≥2 `fresh` OR zero `fresh`. §9 fixture 44 pins.
- **Blocker 9 — host VPN service check.** §4.6 + §7 R7 replaced `scutil --nwi` + utun presence with `scutil --nc status <service>` (Network Configuration service actively Connected) AND `route -n get <relay_host>` interface match. VPN-service-unverifiable → `host_vpn_unverifiable` → `NOT_EVALUABLE`; never guessed. §8 failure matrix updated.
- **Blocker 10 — Kotlin reporter tests.** §9 fixtures 46-54 added (9 Kotlin tests): fixed output path, extras whitelist, VPN active / no-network states, egress timeout / non-200, HMAC equality, denylisted-field absence, source-set boundary. Test count 42 → 54 (33 shell + 12 Python + 9 Kotlin).
- **Recovery-text fix.** §8 signed-prekey-not-ready row: recovery text was `Wait for sender_prekey_deferred → recipient triplet` — misleading because readiness check doesn't send anything. Corrected to `Wait for signed_prekey_readiness published=true`.

All 7 Round-0 open questions remain answered per Round-1 §11 table. No new open questions in Round-2. Ready for architect Round-3 review.

---

## Round-1 change log (bounded amendment 2026-08-14)

Architect REDLINE-1 was schema/discipline precision — approach already accepted from Round-0. Eight blockers corrected in-place:

- **Blocker 1 — WSS-2 schema pin.** §3.2 smoke + §4.1 pinned WSS-2 event schema + §5 evidence schema + §7 R14 + §9 tests 14/15/40/41 all rewritten. Legacy names (`sender_wss_frame_written`, `unresolved_120s_marker`, `session_epoch` mismatch checks) explicitly forbidden. Delivery proof = recipient triplet, verifier-side classification only.
- **Blocker 2 — matrix double-count.** §3.3 rewritten: **8 cells total = 6 WSS + 2 REST, 40 envelopes total.** Cell table added. Updates propagated to §7 R15, §9 fixture 26.
- **Blocker 3 — completion contract.** §4.4 replaces empty sentinel with atomic structured `matrix_completion.json` (fsync-then-rename). Full schema documented. Fixtures 22 + 33 added.
- **Blocker 4 — VPN/egress technically feasible.** §4.5 adds bounded debug-only `DiagnosticNetworkProfileReporter.kt` + `network_profile_report` subcommand extension to existing debug-only receiver. Source-set boundary invariant extended; release-inert gate preserved. Denylist enforced in reporter code.
- **Blocker 5 — pre-cell + post-matrix checkpoints.** §4.6 introduces 5-field drift check before EVERY cell and after cell 8. Drift → `abort_reason` in `matrix_completion.json`. `checkpoint_log.json` added to §5 evidence schema. Fixture 16 tests the mid-run drift path.
- **Blocker 6 — duplicate/retry fail-closed.** §3.4 splits `resume` into bare-mode + `--retry --reason` mode. §3.1 R9 refuses direct `preflight` on completed profile. `attempt_id` + `supersedes_attempt_id` fields added to `PROFILE.json` + `matrix_completion.json`. §3.5 rejects un-linked duplicates as NOT_EVALUABLE. Fixtures 9/10/11/12/24/25 test the lineage semantics.
- **Blocker 7 — no partial-GREEN.** §3.5 + §7 R18 + §9 fixture 23 + §5 `matrix_verdict.json` schema all pin: any RED cell → `product_outcome=RED`, never `partial-GREEN`.
- **Blocker 8 — extended focused test set.** §9 test count grew from 23 → **42** (33 shell + 9 Python). Added coverage for profile drift, duplicate attempts, retry lineage, mixed IPv4/IPv6, egress timeout, malformed types, process restart / pin restore, atomic completion, cross-profile contamination.

All 7 open questions from Round-0 answered per architect (§11) and integrated.

No architectural changes. No new open questions. Ready for architect Round-2 review.

#### `recipient_deliver_failed` (audit ROUND-30.16)

A recipient settles an envelope only when it persisted the row, marked
the processed-envelope ledger **and** acked. Anything else is a failed
delivery, and the recipient now says so instead of leaving a silence.

| field | values |
|---|---|
| `deliver_failure` | `threw`, `held`, `unknown_processing_failure` |
| `deliver_stage` | `received`, `decrypted`, `persisted`, `ledger_marked`, `ack_sent` |

`deliver_stage` is ordered: each value asserts every earlier stage
completed. `unknown_processing_failure` is the fail-closed default — an
unsettled delivery that nothing explains is reported as a failure, never
assumed benign. Neither field can carry free text; the producer maps a
throwable to a closed enum rather than publishing its message.

The event is emitted from the outer handler boundary, not from the
branches. The 2026-08-26 physical smoke failed on a path that returns
`null` without throwing, so a diagnostic hooked to the exception arm
would have missed the case that motivated it.

**A well-formed `recipient_deliver_failed` does NOT make
`evidence_integrity` RED.** It is valid evidence of a product failure:
the evidence is intact and the PRODUCT failed, which the incomplete
recipient triplet already reports. Integrity is reserved for evidence
that cannot be trusted — a value outside either enum, the wrong emitter
or role, more than one record for one envelope, a stage the event stream
contradicts, or a failure claimed over a complete settled triplet.
Conflating the two would make every honest failure report look like a
broken capture.

### The retained local WARN capture is not evidence

A smoke run also opens a second, LOCAL-ONLY logcat stream at
`PhantomMessaging:W`. It exists to answer *why* a delivery failed while
someone is diagnosing, and it carries exception messages and stack
traces.

It is a **sensitive local diagnostic artefact, not evidence.** The rules
are absolute:

- its contents are never printed — a failed run reports the PATH only;
- it never enters an evidence directory, an archive, a checksum
  manifest or a review pack, and the builder fails the build if a raw
  capture or its directory is found in a staged package;
- it is never handed to anyone — reviewer, ticket, chat — without
  separate authorization for that exact transfer;
- once the analysis is finished it is deleted by a deliberate,
  controlled operation, not left to age out.

It lives in a fresh `mktemp -d` directory (0700, files 0600, never
reused) under a canonicalised `TMPDIR`, and the path is refused
outright if it resolves inside the operator package, an evidence tree
or a review pack. On a GREEN smoke it is deleted automatically. On any
other outcome — including a missing, truncated or malformed
`smoke_verdict.json` — it is kept, because the moment the run cannot be
proven clean is the moment the log is worth most.


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

**Current-run ownership.** `smoke_verdict.smoke_run_id` identifies the
only ordinary events that can support or contradict the current smoke.
Unrelated events retained in the logcat snapshot from earlier run ids are
history and are ignored. A foreign-run event that reuses `p2e_cid` or
`e2p_cid` claimed by the current verdict is an integrity finding, not
history. Pin, clear, direction, attempt and overflow audits operate on the
current-run surface. While smoke is still executing, the producer asks
about one direction's cell/CID only: a later E2P pin failure makes the
whole smoke RED but cannot erase a P2E delivery already proved.

**Audit ROUND-30.21 — route-dependent sender completion.** Dispatch is
proved for every route by the single coherent
`sender_transport_decision(dispatched=true, outer_transport=direct,
inner_route=<pin>)`. The terminal sender proof then follows the route:

- WSS requires `sender_wss_send_returned(dispatched=true,
  inner_route=wss)` followed by `sender_relay_ack_received`; the latter
  is the asynchronous sender-to-relay WSS acknowledgement;
- REST requires `sender_rest_post_completed(inner_route=rest,
  relay_acceptance=accepted|duplicate)`. A REST completion MUST NOT carry
  `dispatched`, and a REST CID MUST NOT carry
  `sender_relay_ack_received`; HTTP acceptance is not a synthetic WSS ack.

Both routes still require the full recipient fresh/persisted/ack-sent
triplet. The verifier, canonical Python evidence builder and ADB mock emit
and judge this same shape. The product and accepted WSS-2 verifier already
used it; R30.21 removes the stricter non-physical REST shape that existed
only in WSS-3 synthetic evidence.

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
