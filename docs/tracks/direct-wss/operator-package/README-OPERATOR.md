# Direct WSS Yota-First — Operator Runbook (Mac) v2

**Contract:** [`../direct-wss-yota-contract.md`](../direct-wss-yota-contract.md) — §§9, 11, 12.

## Prerequisites (Mac)

- `adb` on PATH.
- `python3` ≥ 3.9, `bash`, `jq`.
- SHA-256: `sha256sum` OR `shasum`. `lib/portable.sh` picks the available one (`shasum -a 256` is the default macOS binary; no `brew` install is required).
- One physical Android phone with **Yota** as the default-data SIM.
- One running Android Emulator (any recent API 33+).

## macOS quarantine — REQUIRED first step after download

Anything downloaded via a browser or messenger on macOS is tagged with `com.apple.quarantine`. Bash refuses to exec child scripts under that xattr (`Operation not permitted`), even when the mode is `755`. Clear it BEFORE extracting or as a recursive pass over the extracted directory:

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

If you skip this step, `preflight.sh` and every helper it invokes will fail immediately with `Operation not permitted`.

## Phone radio setup

Preflight enforces all of the following via TYPED confirmation. Aborts on any negative answer:

- Yota is the DEFAULT DATA subscription.
- Wi-Fi OFF.
- VPN / proxy / private DNS OFF.
- Automatic data switching OFF.
- Any second SIM (e.g. Tele2) mobile data OFF.

## Order (P0-4 split — bootstrap and preflight are INDEPENDENT)

```
1. bootstrap --fresh   (standalone: uninstall + install + set emitter_id)
2. Manual onboarding on BOTH devices (production UI)
3. Manual QR pairing on BOTH devices (Profile → My Phantom QR)
4. preflight            (measurement preflight: verifies everything + Yota)
5. matrix               (8 × 5 = 40 envelopes, poll-based, auto-verifier)
```

### 1. Bootstrap — uninstall + install

```bash
cd operator-package
./run-yota-wss-diagnostic.sh bootstrap --fresh
```

Requires typing `BOOTSTRAP-CONFIRM` before uninstalling. Verifies the APK's SHA-256 against `android-debug-diagnostic.apk.sha256` before installing. Sets `emitter_id=phone` on the physical device and `emitter_id=emulator` on the emulator, then reads back via `health` to prove the writes stuck.

### 2 + 3. Manual onboarding + QR pairing

- On both devices, run the real onboarding flow (create identity).
- On both devices, open Profile → My Phantom QR → Share my Phantom contact → scan the other device's QR.
- Confirm each device shows exactly ONE conversation with the other.

### 4. Preflight — operator-parameterized

```bash
# Yota baseline (existing WSS-1 run)
./run-yota-wss-diagnostic.sh preflight \
    --operator YOTA  --expected-operator-numeric 25011

# Tele2 comparison (WSS-2)
./run-yota-wss-diagnostic.sh preflight \
    --operator TELE2 --expected-operator-numeric <NNNNN>
```

`--operator` accepts `YOTA` or `TELE2`. The observed
default-data `operator_numeric` on the phone must equal
`--expected-operator-numeric`; a mismatch fails closed
before the prompt so a mistyped numeric cannot silently
pass. The prompt then requires you to type the label
literally (`YOTA` or `TELE2`); anything else, including
Ctrl-D, aborts.

Evidence goes into `evidence/<label-lower>-wss-<UTC>/` and
`run_id` is `run-<label-lower>-<UTC>` — Yota and Tele2 runs
land in distinct directories.

Creates a fresh evidence directory `evidence/yota-wss-<UTC>/` and populates `preflight.json` + `device-manifest.json` with:

- Debug APK variant confirmed on both devices (`DiagnosticCommandReceiver` present in `dumpsys package`).
- `emitter_id` verified via `health` readback on each device.
- Canary emit (WSS_DIAG tag proven).
- Dual-SIM default-data operator reported; the operator MUST type the literal word `YOTA` to proceed.
- Radio checklist — each item requires typed `YES`.
- REST capability probe (Method B controlled fail-closed envelope). Results in `rest_capability = enabled | disabled | unknown`; disabled → REST cells (#7, #8) skipped as BLOCKED.
- Clock skew — fails at |skew| > 30 s; warns > 2 s.

### 5. Matrix

```bash
./run-yota-wss-diagnostic.sh matrix
```

- 8 directed cells × 5 envelopes = 40 envelopes.
- Per-envelope polling: after each `send`, waits up to 120 s for the four delivery signals (`recipient_deliver_received fresh`, `recipient_message_persisted`, `recipient_ack_deliver_sent`, one enqueue) before moving on.
- Between cells: `diag-cmd.sh pin` (both devices) + waits for `diagnostic_pin_active` on both.
- Idle scenario: natural 300 s foreground wait — NO airplane-mode toggle.
- Background→foreground: `input keyevent KEYCODE_HOME` + `monkey` — NO `am force-stop`.

## Reading the report

```
open evidence/yota-wss-<UTC>/verification-report.md
```

Two independent results:

- `evidence_integrity` — bundle completeness (closed-schema verifier).
- `product_outcome` — GREEN / RED / PENDING per cell.

Exit codes (`verify-evidence.py`):
- `0` — integrity GREEN + all cells `Delivered once` or `BLOCKED`.
- `1` — integrity RED (tooling failure).
- `2` — integrity GREEN + at least one `Unresolved` cell (product failure, evidence still usable).
- `3` — integrity GREEN + at least one `PENDING` cell (rerun `verify` after 120 s per envelope).

## Recovered classification — REMOVED

Per §12 P0-7 the first pass distinguishes only `Delivered once` / `Unresolved` / `PENDING` / `BLOCKED`. Fallback breadcrumbs (`attempt`, `session_epoch`, watchdog requeue) are NOT emitted in WSS-1 — a later block can add them via a shared/core-transport bridge extension.

## Tele2 (or any other carrier) follow-up — WSS-2

Do NOT re-bootstrap between carriers — the same installed APK
+ same identities + same pairing + same emulator + same Mac VPN
+ same relay are the point of the comparison.

1. On the phone, switch the default-data SIM to the new carrier
   via Android's SIM/data settings. Do NOT touch onboarding or
   QR pairing.
2. Repeat the radio checklist (Wi-Fi OFF, VPN OFF, private DNS
   OFF, auto-data-switching OFF, other-SIM mobile data OFF).
3. Re-run preflight with the new operator label and its
   MCC+MNC:
   ```bash
   ./run-yota-wss-diagnostic.sh preflight \
       --operator TELE2 --expected-operator-numeric <NNNNN>
   ```
   Preflight fails closed if the observed default-data
   `operator_numeric` on the phone does not equal
   `--expected-operator-numeric`, or if you don't type
   `TELE2` at the confirmation prompt.
4. `./run-yota-wss-diagnostic.sh matrix` (smoke gate + typed
   `RUN-FULL-MATRIX` unchanged).

Evidence lands in `evidence/tele2-wss-<UTC>/` — the Yota
directory from step 4 of the earlier baseline is untouched, so
you can `diff` the two verification reports side-by-side.

## Hard rules

- No manual logcat command.
- No manual envelope correlation.
- No `am force-stop`.
- No airplane-mode toggle.
- No arbitrary send text (receiver refuses).
- No operator-supplied contact alias (receiver refuses).
- No release APK — preflight refuses.
- No VPS action.
- No re-bootstrap between Yota and Tele2 (identities + pairing survive; only radio changes).
