# WSS-3 Carrier x VPN Matrix — operator quick reference

**Full contract:** [`docs/tracks/direct-wss/wss-3-carrier-vpn-matrix-contract.md`](../wss-3-carrier-vpn-matrix-contract.md) (Round-7 GREEN, ARCHITECT ROUND-8 APPROVED).

**Precedent:** the WSS-2 operator package (see [`README-OPERATOR.md`](README-OPERATOR.md)). WSS-3 layers a matrix orchestrator on top of it; every WSS-2 helper is called verbatim.

**Track goal:** run the accepted WSS-2 send/verify machinery across a 2 x 2 x 2 profile matrix (carrier x phone_vpn x host_vpn = 8 profiles) and produce a single 8-row report separating `evidence_integrity` from `product_outcome`.

**Non-goals honoured:**

- no production code change;
- no relay protocol change;
- no release-build modification (the debug-only `DiagnosticNetworkProfileReporter` + `ACCESS_WIFI_STATE` permission ship only in `src/debug/` — proved by `DiagnosticSourceSetBoundaryTest` + `DiagnosticProductionManifestOmitsAccessWifiStateTest`);
- no `adb` invocation at all until architect + operator green-light past the synthetic Mac dry-run.

## Verbs

| Verb | What it does | When to run |
|---|---|---|
| `preflight` | Arms one profile. Mints `arm_token` (UUIDv4), records `PROFILE.json`, writes `.runtime/` state, host-VPN checkpoint, operator typed confirmation. | Once per profile at the start. |
| `smoke` | Fires one WSS envelope in each direction. Gate for `full`. | After every `preflight`. |
| `full` | Runs the 8-cell WSS-2 canonical matrix (6 WSS + 2 REST controls, 40 envelopes total). | After a GREEN `smoke`. |
| `resume` | Bare `resume` re-arms an incomplete profile (writes `retry_reason=resume_incomplete` + `supersedes_attempt_id`) and exits non-zero unless a successor attempt was created AND verified; `--profile-id` narrows it to one profile and is REQUIRED with `--host-vpn-tunnel` (audit ROUND-30.3). `resume --retry --profile-id X --reason Y` re-runs a COMPLETE or ABORTED profile with a new attempt (copies retained `arm_token`, does NOT mint a new one — REDLINE-7 blocker 1). | Only when there is a documented reason. |
| `compare` | Reads every profile dir under `evidence/` and emits an 8-row report separating `evidence_integrity` from `product_outcome`. | Once after all 8 profiles are complete. |
| `cleanup` | Removes runtime state for COMPLETE profiles. `cleanup --all` also removes runtime for ABORTED profiles. Never touches in-progress profiles. | Explicit operator act — declares that COMPLETE-profile retries are no longer possible. |

### Re-entering a profile without destroying its evidence

Audit ROUND-29.3 P1-2. Never re-run `smoke` over an existing attempt and
never `preflight` the same profile again as a fresh arm: the first
overwrites `smoke_verdict.json` and `smoke.log` in place, and the second
produces an attempt with no lineage to the failure it is meant to
investigate. Both destroy the record.

Which verb applies depends on whether the attempt got as far as writing
`matrix_completion.json`:

- **No `matrix_completion.json`** (the attempt stopped at or before
  `smoke` — this is the shape of the first physical FIELD RED): the
  profile is INCOMPLETE, so the path is a **bare** `resume`. It re-arms
  each incomplete profile and records `retry_reason=resume_incomplete`
  plus `supersedes_attempt_id`, which is the lineage.

  ```bash
  ./run-carrier-vpn-matrix.sh resume
  ```

- **`matrix_completion.json` present** (COMPLETE, or ABORTED with an
  `abort_reason`): use the explicit retry. `--reason` is a **closed
  whitelist**, not free text:

  ```bash
  ./run-carrier-vpn-matrix.sh resume --retry \
      --profile-id <profile-id> \
      --reason vpn_provider_change | sim_reseat | sdk_environment_recovery \
             | mid_run_operator_interrupt | network_transient_recovery
  ```

  `resume_incomplete` is rejected here — it is reserved for the bare
  path above (§3.4). Any other value is rejected outright.

Both paths **copy** the retained `arm_token` rather than minting a new
one (REDLINE-7 blocker 1), so the new attempt stays bound to the same
arm while superseding the previous one.

The successor also inherits the four preflight observations that remain
true for that arm: `EGRESS_FINGERPRINT.json`, `PROFILE_STATE.json`,
`network_profile.json` and `signed_prekey_readiness.json`. Each source
file must be a non-empty regular UTF-8 JSON object whose bytes match the
predecessor's `SHA256SUMS.txt`; the copy is byte-exact and atomically
published. A missing manifest entry, hash mismatch, malformed source or
publication failure rolls back the whole successor. A re-arm can no
longer succeed with a profile that the common verifier audits cannot read.

## Canonical order per carrier

| # | phone_vpn | host_vpn |
|---:|:---:|:---:|
| 1 | off | on |
| 2 | off | off |
| 3 | on  | off |
| 4 | on  | on  |

Then swap Yota → Tele2 and repeat. Total: **2 x 4 = 8 profiles**.

## Every `preflight` needs

```bash
./run-carrier-vpn-matrix.sh preflight \
  --operator YOTA \
  --expected-operator-numeric 25011 \
  --phone-vpn off \
  --host-vpn on \
  --host-vpn-service "Wireguard-Home"
```

- `--host-vpn-service` **required for every profile** regardless of `--host-vpn`. It names the macOS Network Configuration service (as it appears in `scutil --nc list`) that the operator has configured for this track.
- `--host-vpn-tunnel <utunN>` — **optional**, audit ROUND-30 / ROUND-30.1. Declares the **operator-attested full-tunnel interface** to use when the VPN client is a modern VLESS/Xray packet tunnel (e.g. Happ) whose legacy Network Configuration entry reports `Disconnected` even while it is live.

  **What this proves, and what it does not.** The legacy `scutil --nc status` check still runs FIRST and is unchanged; this path is consulted only when that cannot attest. It then proves an **active full tunnel**: tunnel-shaped interface, UP + RUNNING with an `inet` address, carrying **both** the default and the relay route, with no Connected Network Configuration service owning the relay-route interface. `en0` is rejected at parse time.

  When the declared service *does* report interfaces, the declared tunnel **must** be one of them — that rejects a different Network Extension running under the same service name. When the service reports nothing at all (the Happ case: invisible to `scutil --nc`), **no trustworthy ownership evidence exists**, and the attestation is an **operator-declared** full tunnel — it does **not** prove which application owns it. Read every `host_vpn=on` result reached this way as "the operator attested that this interface is the declared tunnel", not as "provider X was verified".

  Like the service name the interface is **never** written to evidence (the §5 denylist bans raw `utunN`). Type it once at `preflight`; `smoke`, `full` and `resume` read it back from `.runtime` and reject any CLI value that disagrees.

  **Migrating an arm made before ROUND-30 (audit ROUND-30.2, tightened in ROUND-30.3).** An attempt armed before this flag existed has no `host-vpn-tunnel`, so bare `resume` also accepts `--host-vpn-tunnel <utunN>` and persists it for the profile being re-armed. The migration touches `.runtime` only — old evidence is never modified, and the retained `arm_token` and `supersedes_attempt_id` lineage are preserved. It is refused, with a non-zero exit, when the profile is `host_vpn=off` (a tunnel declaration is meaningless there) or when it disagrees with a tunnel already armed for that profile.

  **A migration names exactly one profile.** `--host-vpn-tunnel` on bare `resume` REQUIRES `--profile-id`; without it the command exits 4 before writing anything. Before the first byte is written the named profile must exist in `.runtime` at the contracted modes, its latest attempt must be incomplete, and the runtime `arm-token` must match that attempt's `PROFILE.arm_token`. If a later step fails, a declaration this command created is rolled back, so the previous arm is left exactly as it was.

  **A re-arm is one transaction (audit ROUND-30.4).** The new attempt directory is reserved first, with a bare `mkdir`: an existing path fails closed and is never reused or overwritten. Its UTC stamp must sort strictly after every existing attempt for that profile — a same-second collision is retried a bounded number of times while the clock advances and then refused with exit 4, and a future-dated attempt directory is refused immediately, before the migration touches anything. Any failure after the migration removes the attempt this command reserved and, only if this command created it, the tunnel declaration. Two things are true of every failed `resume`: the previous `PROFILE.json` is byte-identical, and no half-built attempt is left behind.

  **The transaction closes at the filesystem boundary (audit ROUND-30.5).** Whether the declaration belongs to this command is decided by what is on disk before and after the writer runs, not by the writer's exit code — the atomic write publishes with `mv` and only then applies the final `chmod`, so it can fail with the file already there, and that file is still rolled back. `host-vpn-tunnel` must be a plain regular file: a directory, symlink or FIFO at that path is **corrupted runtime state**, not "no declaration", and it fails closed with exit 4 before anything is reserved or migrated — your own object at that path is never modified or removed. If the armed declaration already equals the one you pass, it is accepted read-only and not rewritten. A successful write is confirmed afterwards: plain regular file, mode 0600, holding exactly the interface you declared.

  ```bash
  ./run-carrier-vpn-matrix.sh resume \
      --profile-id yota-phone-off-host-on \
      --host-vpn-tunnel utun4
  ```
- The raw service string is **never** written to `PROFILE.json` or any evidence file — only the derived Connected/Disconnected + relay-route-owned-by-service-iface booleans are recorded.
- Cross-verb continuity: `smoke`, `full`, `resume` pick the service up from `.runtime/wss3/<profile-id>/host-vpn-service` (chmod 0600), so the operator only types the service name once.

## Runtime state discipline (§3.6)

- **Location:** `docs/tracks/direct-wss/operator-package/.runtime/wss3/<profile-id>/`
- **Files:** `host-vpn-service` (raw service name, chmod 0600), `arm-token` (UUIDv4 mint, chmod 0600), `host-vpn-tunnel` (declared full-tunnel interface, chmod 0600, **present only when declared** — an empty declaration removes any stale file, and a value that is not exact `utunN`/`tunN`/`ipsecN` syntax fails closed on read)
- **`Ctrl-C` is safe, and one profile runs one transaction at a time (audit ROUND-30.10).** `INT` and `TERM` always roll the transaction back and exit 130 / 143 — they are never swallowed. Two `preflight` or `resume` runs against the SAME profile cannot overlap: the second fails immediately with `runtime_state_transaction_locked` without touching anything, while a different profile is unaffected.
- **If a rollback itself cannot finish, the tooling stops rather than guesses (audit ROUND-30.10).** It never reports a restoration it did not verify. The recovery data is kept inside your store at `.runtime/wss3/<profile-id>/.recovery/` (0700, files 0600), a `BLOCKED` marker is written, and every later verb refuses that profile with `runtime_state_rollback_failed` until you resolve it by hand. Nothing about the values is printed.
- **A rollback that cannot even preserve its own recovery data still refuses the profile (audit ROUND-30.10 rework).** If writing `.recovery/` is itself what fails, the tooling keeps the profile lock instead of releasing it and writes the marker inside that lock. Either one stops the next verb, so a profile whose state was not restored is never handed back to you as an ordinary one. The journal is left where it is and its path is printed — do not delete it.
- **A run reports success only when its cleanup is proven (audit ROUND-30.10 rework).** Committing verifies that the journal holding your record bytes is gone and that the profile lock is released. If either survives you get `runtime_state_transaction_cleanup_failed` and the path to clear by hand; the runtime records themselves are correct in that case.
- **`resume` is one transaction, first write included (audit ROUND-30.10 rework).** Reserving the attempt and migrating a tunnel now happen under the same lock and journal as everything else, and so does the very first arm for a brand-new profile. Two runs against one profile cannot interleave at any point, and a re-arm is committed only after its successor attempt has been verified.
- **A profile somebody is working on is not readable either (audit ROUND-30.10 closure).** While a run holds a profile, its `.lock` directory exists and every other reader, writer and `cleanup` refuses that profile with `runtime_state_transaction_locked` — not just writers, as before. You are never handed a value out of a profile that is being changed underneath you.
- **`cleanup` will not delete state that is being held or held for recovery (audit ROUND-30.10 closure).** It now refuses a profile another run holds and a profile left blocked after a failed rollback, and prints `kept <profile-id> (removal refused or failed)` instead of removing it. A routine housekeeping run can no longer throw away the only copy of your recovery data.
- **What "terminal" means, precisely (audit ROUND-30.10 closure).** Three different things used to be conflated, so they are stated separately. The failed *transaction* ends in `ROLLBACK_FAILED` and nothing continues from it. The *profile* stays blocked by what is on disk — `.recovery/BLOCKED`, or the retained `.lock` when even writing that failed — and that is the only part which survives the process. The *process* may go on to work on a **different** profile, but only after it can prove the damaged one is really blocked on disk: the preserved copy has to still match the manifest recorded beside it, or the retained lock has to be exactly the profile's own `.lock`. Without that proof the tooling stops instead of continuing.
- **Clearing a stuck lock by hand.** A `.lock` left behind by a killed run keeps refusing that profile until you clear it. That is deliberate, and clearing it is not simply deleting the directory:
    1. Prove nothing is running. Check for a live `run-carrier-vpn-matrix.sh` for that profile first. If one is running, wait for it or stop it — never remove a lock that has an owner.
    2. Preserve first. If `.runtime/wss3/<profile-id>/.recovery/` exists, copy it somewhere outside the store and keep it: it is the only copy of the pre-state of a failed rollback.
    3. If `.lock/BLOCKED` is present, preservation itself had failed, so the recovery data is still in the journal directory the failed run printed. Save that path before anything else.
    4. Only then remove the `.lock` directory. Leave `.recovery/` where it is until the runtime state has been reconciled — deleting it unblocks the profile and throws away the evidence you need to reconcile it.
- **The shell gate that counts is the one on your Mac.** Run `/bin/bash tests/target-mac-gate.sh` from the package root. It records `uname -a`, the interpreter version, the derived list of every shipped shell file, how many of them it parsed, and the suite's exit code, and it prints `TARGET_MAC_GATE=GREEN` only when it ran under stock `/bin/bash`, parsed every derived file and the suite exited 0. Run under any other bash it prints `TARGET_MAC_GATE=HOST-LOCAL` instead — useful, but not the same result and not a substitute for it.
- **A failed `preflight` puts your previous arm back (audit ROUND-30.9).** If a profile was already armed, the exact bytes and mode of its `host-vpn-service`, `arm-token` and `host-vpn-tunnel` are journalled before anything changes and restored if the run does not reach its baseline commit — you get your old arm back, not the failed run's. Only objects that run created are removed.
- **Anything not canonical is refused, never repaired (audit ROUND-30.9).** A record that already exists must be a plain 0600 regular file, contained in the store, and well-formed for its kind. A symlink, directory, FIFO, wrong-mode file or a record with stray bytes stops the command before it touches anything — including the empty-`--host-vpn-tunnel` removal path, which used to unlink whatever was sitting there. If you see such a refusal, inspect the object yourself; the tooling will not modify or delete it for you.
- **A `preflight` either arms completely or leaves nothing (audit ROUND-30.8).** The transaction stays open until `PROFILE.json`, `EGRESS_FINGERPRINT.json`, `PROFILE_STATE.json`, `network_profile.json` and `signed_prekey_readiness.json` all exist, are non-empty and parse. If the baseline checkpoint or any of those writers fails, the arm, the attempt directory and every record this run created are removed, and a record it overwrote is restored with its original bytes and mode. You will never find a half-armed profile to clean up by hand, and a `preflight` that printed nothing about being armed did not arm anything. The same holds for `Ctrl-C`: the one `EXIT/INT/TERM` handler unwinds the in-flight transaction and clears the device pins — there is no second trap that could displace it.
- **Nothing is created, chmod'ed or written until the path is checked (audit ROUND-30.8).** Every component of the store path is verified not to be a symlink before any `mkdir`, `chmod`, write, rename or removal, so a symlink you leave in `.runtime` — deliberately or by accident — is refused without the tooling touching what it points at, and a record can never be written outside the store. The check starts at the package directory, so symlinks in your own layout above it (on macOS, `/tmp` is one) are none of its business.
- **Runtime-state error codes (audit ROUND-30.7).** The complete set, all exit 4, none of which ever modifies anything: `host_vpn_service_missing` (service record absent), `runtime_state_arm_token_missing` (arm-token absent — re-`preflight`, a retry cannot mint one), `runtime_state_record_type_violation` (not a plain file, not a real directory, or a symlinked path component — the tooling never touches such an object), `runtime_state_mode_violation` (mode ≠ 0600 on a record or ≠ 0700 on a directory), `runtime_state_record_corrupt` (framing bytes, invalid UTF-8, empty or over-long), `runtime_state_record_syntax` (clean record, wrong value for its kind), `host_vpn_service_stale_arm` (a valid token belonging to another attempt), `host_vpn_service_mismatch` (CLI value disagrees with the armed one), `runtime_state_stale` (runtime with no matching evidence). Contract §3.6 carries the same table with per-code remediation.
- **What a failure will and will not tell you (audit ROUND-30.7).** These diagnostics print the code, the record kind, the file path and `[redacted]`. They never print the value — not raw, not hex, not base64, not hashed. That is deliberate: the service name and the tunnel interface are exactly what §5 keeps out of evidence, and a diagnostic is just another place they could leak from. If you need to see a record, read the file yourself.
- **Byte-exact single-value records (audit ROUND-30.6):** each of those three files holds one value and nothing else. A NUL, a CR, a LF — **including a single trailing newline** — or any other control byte makes the file a corrupted record, refused at exit 4 rather than trimmed to look clean. Syntax is checked against the whole value, so `garbage\nutun4` and `utun4\n` are not tunnel declarations and a multi-line arm token is not a token. `host-vpn-service` stays provider-neutral: spaces and non-ASCII characters are ordinary in a macOS service name and remain legal — nothing in the tooling matches or special-cases any VPN provider. If you edit these files by hand, write them **without** a trailing newline (`printf '%s' value > file`), which is what the tooling itself writes.
- **Directory modes:** parent `wss3/` = 0700, per-profile `<profile-id>/` = 0700
- **gitignored:** `.runtime/` is a peer of `evidence/`, distinct root, never packaged into the handoff tarball
- **Retention:** the trap NEVER auto-deletes on any terminal state (COMPLETE, ABORTED, SMOKE_RED, NOT_EVALUABLE). Removal happens only via successful `compare` (all-8-GREEN) OR explicit `cleanup [--all]` (REDLINE-7 blocker 1)

## Evidence-isolation invariant (§3.6, REDLINE-6 blocker 3)

- Fixed-string sweep: `grep -rF -- "$RAW_SERVICE_VALUE" evidence/` MUST return zero matches.
- Tar body sweep: `tar -xzf handoff-*.tar.gz -O | grep -F -- "$RAW_SERVICE_VALUE"` MUST return zero matches.
- Tar listing sweep: `tar -tzf handoff-*.tar.gz | grep -E '(^|/)\.runtime/'` MUST return zero matches.
- **Failure discipline:** the raw service value is NEVER echoed to failure output. Only file paths + `<RAW_SERVICE_VALUE_REDACTED>` marker appear.
- The literal option-name string `host-vpn-service` is explicitly allowed to appear in packaged scripts + this README + the contract sheet. The invariant guards the **value**, not the name.

## Target-platform gate: mandatory pre-run step (audit ROUND-30.12)

A freshly extracted archive on macOS carries `com.apple.quarantine` on every
file. Executing a quarantined script fails with
`/usr/bin/env: bad interpreter: Operation not permitted`, which the shell
reports as `rc=126`. **Clear it before running anything from a fresh
extraction:**

```bash
xattr -dr com.apple.quarantine <extracted-package-root>
```

`tests/target-mac-gate.sh` checks the attribute itself and refuses with a named
`TARGET_MAC_GATE=RED` plus the command above, so the condition is never left as
a bare exit code. Off macOS the check reports `not-applicable` rather than
counting as a pass.

## Pin clearing (audit ROUND-30.11/30.12)

- A clear is `pin --es pin none --es run_id <run> --es cell_id <cell>.clear`. The
  receiver rejects a pin command missing `run_id`/`cell_id` whatever the pin
  value is, so the older short form never took effect on a device.
- A clear counts only once the device emits `diagnostic_pin_active … pin=none`
  for that emitter, run and derived cell id. The run stops rather than carrying
  a stale pin into whatever executes next.
- The derived id `<cell>.clear` keeps the clear out of the parent cell's
  dual-party pin set, where exactly one breadcrumb per party is allowed.
- Every clear must name a direction or cell that ACTUALLY RAN. A clear naming a
  blocked, `PENDING` or never-reached parent is reported as an orphan.
- A cell whose cleanup failed keeps its real counters and verdict and records
  `abort_reason=pin_clear_unconfirmed` with `post_cell_clear_emitter`. It is the
  one abort reason describing a cell that finished.
- When `matrix_completion.abort_reason` is `pin_unconfirmed` or
  `pin_clear_unconfirmed`, exactly one cell must record the same reason.

## Host VPN state check (§7 R7)

For every profile (both ON and OFF):

- `scutil --nc list` MUST include the operator-supplied `--host-vpn-service`. Absent = fail-closed `host_vpn_unverifiable` → `NOT_EVALUABLE` BEFORE arm.
- **ON case:** `scutil --nc status "<svc>"` returns `Connected` AND `route -n get <relay_host>` returns the interface owned by that service.
- **OFF case:** `scutil --nc status "<svc>"` returns `Disconnected` AND `route -n get <relay_host>` returns an interface that is NOT owned by ANY currently-Connected VPN service. Prevents the "primary VPN off, secondary VPN still on" false-pass (REDLINE-5 blocker 2).

## Egress fingerprint discipline (§4.5, §7 R10)

- Only host + emulator participate. Phone is deliberately excluded (REDLINE-4 blocker 1).
- Orchestrator mints ONE 32-byte `checkpoint_key` per checkpoint. Broadcast to emu reporter; used locally on host.
- Fingerprint = `HMAC-SHA256(checkpoint_key, canonical_ip_bytes)[0:16]` — first 16 bytes hex.
- Endpoint pinned to `api4.ipify.org` (IPv4-only). 5 s timeout. On timeout / non-200 the fingerprint is `null` and `http_status` is recorded; profile fails-closed for that checkpoint.
- `checkpoint_key` is wiped from local shell / JVM variables after the HMAC is computed. It is NEVER written to `checkpoint_log.json`, `PROFILE.json`, `network_profile.json`, `EGRESS_FINGERPRINT.json`, any log, or the tarball archive.
- Raw IPs never touch disk.

## Retry lifecycle (§3.4, REDLINE-7 blocker 1)

```
preflight → COMPLETE
        ↓
runtime state retained
        ↓
resume --retry --profile-id X --reason vpn_provider_change
   ↳ new attempt COPIES retained arm_token (does NOT mint new)
   ↳ new PROFILE.arm_token == retained arm_token
   ↳ supersedes_attempt_id → original attempt
        ↓
compare (all-8 GREEN) → implicit-cleanup removes .runtime/
        ↓
subsequent resume --retry X → host_vpn_service_missing exit 4
   (runtime was intentionally reaped by successful compare;
    a fresh preflight would mint a new UUIDv4)
```

## Focused-test entry points

- Shell (both WSS-2 + WSS-3 suites in one entry point):
  `bash tests/test_shell.sh`
  Includes 79 WSS-2 fixtures + 53 WSS-3 fixtures 1-53 covering all six verbs, evidence isolation, runtime state permissions, cross-verb consistency, SIGINT→resume--retry continuity, cleanup semantics.
- Python (BOTH suites):
  `python3 -m unittest discover -s tests -p 'test_verifier*.py'`
  Runs the accepted WSS-2 `tests/test_verifier.py` PLUS the WSS-3
  `tests/test_verifier_wss3.py` (19 fixtures 54-72). To run only one,
  use `python3 -m unittest tests.test_verifier` or
  `python3 -m unittest tests.test_verifier_wss3`.
- Kotlin (source-tree only — not part of the packaged operator tarball):
  `./gradlew :apps:android:testDebugUnitTest --tests "phantom.android.diagnostic.Diagnostic*"`
  Runs the 14 WSS-3 fixtures 73-86 for the debug-only reporter + debug-manifest boundary.
- Total: **86 focused WSS-3 tests** per contract §9 (53 shell + 19 Python + 14 Kotlin).

## Dry-run vs live

Set `WSS3_DRY_RUN=1` in the environment to short-circuit every ADB path into an evidence-only stub. Under dry-run, `preflight` skips the interactive confirmation prompt (reads `WSS3_CONFIRM_INPUT` if set, else auto-confirms). Used by the synthetic Mac dry-run in Impl 6/6.

Live operation requires: real Android phone with a SIM matching `--operator`, real emulator, real Mac with a Network Configuration VPN service. **Every ADB invocation is HELD until the architect + operator explicitly greenlight the physical run past the synthetic Mac dry-run** (contract §1 last bullet).

## Device auto-detection (audit ROUND-26)

`preflight`, `smoke` and `full` all share one live-device resolver
(`lib/wss3-devices.sh`) that speaks only through the `wss3_adb` seam.

**Default (recommended):** with **no** `WSS3_PHONE_SERIAL` or
`WSS3_EMU_SERIAL` environment variables set, the resolver enumerates
`adb devices` for online serials (state `device` — `offline` and
`unauthorized` are dropped), classifies each via
`getprop ro.kernel.qemu` (emulator → `1`, physical phone → empty),
and requires **exactly one physical phone and exactly one emulator**.
The resolved serials feed the WSS-3 trap map so every logcat clear,
diag broadcast, and cleanup targets the correct device.

**Optional explicit override:** set **both** `WSS3_PHONE_SERIAL` and
`WSS3_EMU_SERIAL` (partial overrides fail closed). Each is verified
to be online AND to classify to the required role — a phone serial
whose `ro.kernel.qemu` is `1`, or an emulator serial whose
`ro.kernel.qemu` is empty, fails pre-arm as role-mismatched. Use
overrides only when you have multiple phones or multiple emulators
attached and need to pin the pair; otherwise the auto-detect path
is preferred.

Failure conditions — any of these fail pre-arm with a specific
`devices_*` code and NO runtime state or evidence is created:

- zero or multiple physical phones online;
- zero or multiple emulators online;
- explicit override serial that is offline or unauthorized;
- explicit override serial whose role does not match its variable;
- only one of the two override variables is set;
- `getprop` classification fails (device vanished, permission denied,
  non-zero exit from getprop itself);
- `adb devices` enumeration exits non-zero (even with a plausible
  device listing on stdout) — this is `devices_enumeration_failed`.

Exit-status authority (audit ROUND-27): the resolver captures the
exit code of the exact `wss3_adb devices` and
`wss3_adb -s SER shell getprop ro.kernel.qemu` invocations
separately from their stdout, and fails closed on any non-zero rc
regardless of how the stdout looks. There is no preceding `shell true`
liveness ping — `getprop`'s own rc is the only signal a classification
decision rests on.

Under `WSS3_DRY_RUN=1` the harness supplies its mock serials via
`WSS3_PHONE_SERIAL` + `WSS3_EMU_SERIAL` (the same two variables used
in live overrides) so the resolver's explicit-override path is
exercised end-to-end against the mock ADB backend. Production
paths NEVER default to mock serials — the R25 field regression that
motivated R26 (`preflight: adb devices missing phone=mock-phone-serial …`)
cannot recur.

## The retained local WARN capture is not evidence

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

**The smoke surface belongs to its declared run.** A logcat snapshot may
contain records retained from earlier invocations. Events whose `run_id`
does not equal `smoke_verdict.smoke_run_id` are historical and do not
veto the current smoke merely by existing. They also cannot support it:
all direction, pin, clear, attempt and overflow judgments use current-run
events only. A foreign-run record that reuses either CID claimed by the
current verdict is not harmless history; it is an integrity finding. The
producer's in-progress direction check is narrower still: a later E2P pin
failure makes the whole smoke RED, but cannot erase a P2E delivery already
proved by the P2E cell and CID.

**Audit ROUND-30.21 — route-dependent sender completion.** The common
dispatch proof is `sender_transport_decision(dispatched=true,
outer_transport=direct, inner_route=<pin>)`. WSS then requires
`sender_wss_send_returned(dispatched=true, inner_route=wss)` and
`sender_relay_ack_received`. REST instead requires
`sender_rest_post_completed(inner_route=rest,
relay_acceptance=accepted|duplicate)` with no `dispatched` field and no
`sender_relay_ack_received`. HTTP acceptance is not an asynchronous WSS
ack. Both routes still require the full recipient triplet. The verifier,
Python evidence builder and ADB mock all consume or emit this same physical
shape.

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
