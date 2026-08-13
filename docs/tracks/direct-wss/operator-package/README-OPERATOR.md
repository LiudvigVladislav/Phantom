# Direct WSS — Operator Runbook (Mac) v3

**Contract:** [`../direct-wss-yota-contract.md`](../direct-wss-yota-contract.md) — §§9, 11, 12 (§12.10 for operator parameterization).

Runs the same 8×5 matrix on any supported carrier. The Yota
baseline (WSS-1) is one instance of this runbook with
`--operator YOTA`. The Tele2 comparison (WSS-2) is the same
runbook with `--operator TELE2`, on the same APK / identities /
pairing / emulator / Mac VPN / relay.

## Prerequisites (Mac)

- `adb` on PATH.
- `python3` ≥ 3.9, `bash`, `jq`.
- SHA-256: `sha256sum` OR `shasum`. `lib/portable.sh` picks the
  available one (`shasum -a 256` is the default macOS binary; no
  `brew` install is required).
- One physical Android phone with a SIM for the target carrier
  active as the DEFAULT-DATA subscription (Yota, Tele2, or any
  future supported carrier — see the whitelist in
  `lib/operator-args.sh`).
- One running Android Emulator (any recent API 33+).

## macOS quarantine — REQUIRED first step after download

Anything downloaded via a browser or messenger on macOS is
tagged with `com.apple.quarantine`. Bash refuses to exec child
scripts under that xattr (`Operation not permitted`), even when
the mode is `755`. Clear it BEFORE extracting or as a recursive
pass over the extracted directory:

```bash
# after downloading the tar.gz, before extraction:
xattr -d com.apple.quarantine final-package.tar.gz

# — OR — after extracting the tar into ./operator-package:
xattr -dr com.apple.quarantine operator-package
```

Then verify the archive checksum before proceeding:

```bash
shasum -a 256 final-package.tar.gz
# match against the value shipped in SHA256SUMS.txt
```

If you skip this step, `preflight.sh` and every helper it
invokes will fail immediately with `Operation not permitted`.

## Phone radio setup — same for every carrier

Preflight enforces all of the following via TYPED confirmation.
Aborts on any negative answer:

- The target carrier is the DEFAULT-DATA subscription (the
  operator numeric on the phone must match
  `--expected-operator-numeric`).
- Wi-Fi OFF.
- VPN / proxy / private DNS OFF.
- Automatic data switching OFF.
- Any OTHER SIM's mobile data OFF (e.g. Tele2 off during a Yota
  run; Yota off during a Tele2 run).

## Order

```
1. bootstrap --fresh
      standalone: uninstall + install + set emitter_id
2. Manual onboarding on BOTH devices (production UI)
3. Manual QR pairing on BOTH devices (Profile → My Phantom QR)

4. preflight --operator <LABEL> --expected-operator-numeric <NNNNN>
      measurement preflight for the selected carrier
5. matrix
      8 × 5 = 40 envelopes, smoke gate + typed RUN-FULL-MATRIX,
      auto-verifier

Then, WITHOUT re-bootstrapping and WITHOUT redoing onboarding
or QR pairing:

6. Switch phone default-data SIM to the next carrier via the
   Android SIM/data settings.
7. Repeat the radio checklist for the new carrier.

8. preflight --operator <NEW LABEL> --expected-operator-numeric <NEW NNNNN>
9. matrix
```

### 1. Bootstrap — uninstall + install

```bash
cd operator-package
./run-yota-wss-diagnostic.sh bootstrap --fresh
```

Requires typing `BOOTSTRAP-CONFIRM` before uninstalling. Verifies
the APK's SHA-256 against `android-debug-diagnostic.apk.sha256`
before installing. Sets `emitter_id=phone` on the physical device
and `emitter_id=emulator` on the emulator, then reads back via
`health` to prove the writes stuck.

### 2 + 3. Manual onboarding + QR pairing

- On both devices, run the real onboarding flow (create identity).
- On both devices, open Profile → My Phantom QR → Share my
  Phantom contact → scan the other device's QR.
- Confirm each device shows exactly ONE conversation with the
  other.

### 4. Preflight — operator-parameterized

```bash
# Yota (WSS-1 baseline example)
./run-yota-wss-diagnostic.sh preflight \
    --operator YOTA  --expected-operator-numeric 25011

# Tele2 (WSS-2 comparison example)
./run-yota-wss-diagnostic.sh preflight \
    --operator TELE2 --expected-operator-numeric <NNNNN>
```

`--operator` accepts only whitelisted values (see
`lib/operator-args.sh` — currently `YOTA` and `TELE2`). The
observed default-data `operator_numeric` on the phone must
equal `--expected-operator-numeric`; a mismatch fails closed
BEFORE the prompt so a mistyped numeric cannot silently pass.
The prompt then requires you to type the literal label
(`YOTA`, `TELE2`, …); anything else, including Ctrl-D, aborts.

Evidence goes into `evidence/<label-lower>-wss-<UTC>/` and
`run_id` is `run-<label-lower>-<UTC>` — runs for different
carriers land in distinct directories and neither can overwrite
the other. The verifier requires `matrix.run_id` to start with
`run-<label-lower>-` so no bundle can be relabelled after the
fact.

Preflight populates `preflight.json` + `device-manifest.json`
with:

- `run_id` (starts with `run-<label-lower>-`).
- Debug APK variant confirmed via ACTIVE health readback (Round-6
  live-Mac fix — the API-36 `dumpsys grep` false-negatived; the
  `diag-cmd.sh health` broadcast is authoritative).
- Exact APK SHA-256 binding on both devices.
- `emitter_id` verified via `health` readback on each device.
- Canary emit (WSS_DIAG tag proven).
- Signed prekey readiness on relay for both devices (§12.7 P1-2).
- Dual-SIM default-data `operator_numeric` reported and required
  to equal `--expected-operator-numeric`.
- Typed `<LABEL>` confirmation (Round-9 EOF-safe read).
- Radio checklist — each item requires typed `YES`.
- REST capability probe (Method (b) fail-closed). Results in
  `rest_capability = enabled | disabled | unknown`; disabled →
  REST cells (#7, #8) skipped as BLOCKED.
- Host↔device clock skew — fails at |skew| > 30 s; warns > 2 s.
- `operator_label`, `expected_operator_numeric`,
  `operator_confirmed=true` written to BOTH `preflight.json`
  and `device-manifest.json` — the verifier requires cross-file
  consistency.

### 5. Matrix

```bash
./run-yota-wss-diagnostic.sh matrix
```

- 8 directed cells × 5 envelopes = 40 envelopes.
- First envelope is a **smoke gate** (§12.9): if the production
  transport route is not reached within 15 s, the matrix aborts
  without spending time on the remaining 39 envelopes.
- After the smoke envelope's 120-second poll_envelope window
  closes, you are prompted to type `RUN-FULL-MATRIX` to
  continue. Anything else (including Ctrl-D) cleans up pins and
  exits with `ABORT_REASON=operator_declined_full_matrix`.
- Per-envelope polling: after each `send`, waits up to 120 s for
  the four delivery signals (`recipient_deliver_received fresh`,
  `recipient_message_persisted`, `recipient_ack_deliver_sent`,
  one enqueue) before moving on.
- Idle scenario: natural 300 s foreground wait — NO airplane-mode
  toggle.
- Background → foreground: `input keyevent KEYCODE_HOME` +
  `monkey` — NO `am force-stop`.

## Reading the report

```
open evidence/<label-lower>-wss-<UTC>/verification-report.md
```

Title line reflects the confirmed carrier when the bundle
verifies (`Direct WSS <LABEL> — verification report v4`) and
falls back to `Direct WSS Yota-First — verification report v4`
on any integrity failure or a legacy Round-9 bundle (§12.10
Round-1).

Two independent results:

- `evidence_integrity` — bundle completeness (closed-schema
  verifier).
- `product_outcome` — `GREEN` / `RED` / `PENDING` per cell.
  When `evidence_integrity` is RED the outcome is
  `NOT_EVALUABLE` (§12.6 P0-2) — the product cells are shown
  for diagnosis only, NOT as authoritative pass/fail signals.

Exit codes (`verify-evidence.py`):

- `0` — integrity GREEN + all cells `Delivered once` or `BLOCKED`.
- `1` — integrity RED (`product_outcome=NOT_EVALUABLE`).
- `2` — integrity GREEN + at least one `Unresolved` cell (product
  failure, evidence still usable).
- `3` — integrity GREEN + at least one `PENDING` cell (rerun
  `verify` after 120 s per envelope).

## Recovered classification — REMOVED

Per §12 P0-7 the first pass distinguishes only `Delivered once`
/ `Unresolved` / `PENDING` / `BLOCKED`. Fallback breadcrumbs
(`attempt`, `session_epoch`, watchdog requeue) are NOT emitted
in WSS-1 — a later block can add them via a shared/core-transport
bridge extension.

## Comparing carriers (WSS-2 workflow)

After a Yota run completes:

1. Do NOT re-bootstrap — the same installed APK + same
   identities + same pairing + same emulator + same Mac VPN +
   same relay are the point of the comparison.
2. Do NOT re-do onboarding or QR pairing.
3. On the phone, switch the default-data SIM to the new carrier
   via Android's SIM/data settings.
4. Repeat the radio checklist (Wi-Fi OFF, VPN OFF, private DNS
   OFF, auto-data-switching OFF, other-SIM mobile data OFF).
5. `./run-yota-wss-diagnostic.sh preflight --operator TELE2
   --expected-operator-numeric <NNNNN>`.
6. `./run-yota-wss-diagnostic.sh matrix` (smoke gate + typed
   `RUN-FULL-MATRIX` unchanged).

Evidence lands in `evidence/tele2-wss-<UTC>/` — the earlier
carrier's directory is untouched, so both `verification-report.md`
files can be diffed side-by-side.

## Hard rules

- No manual logcat command.
- No manual envelope correlation.
- No `am force-stop`.
- No airplane-mode toggle.
- No arbitrary send text (receiver refuses).
- No operator-supplied contact alias (receiver refuses).
- No release APK — preflight refuses.
- No VPS action.
- No re-bootstrap between carriers (identities + pairing survive;
  only the SIM + radio checklist changes).
