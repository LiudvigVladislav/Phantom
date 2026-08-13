# Direct WSS — Yota vs Tele2 carrier comparison (2026-08-12)

**Status:** WSS-3 closure — investigation is CLOSED as **inconclusive with
respect to historical intermittency**. Both carriers produce a GREEN
matrix on the current client + relay + Mac VPN setup. The historical
Direct WSS instability that motivated the whole WSS-0..WSS-2 track was
NOT reproduced. Root cause remains unproven.

Diagnostic APK, operator package, verifier and closed-schema evidence
contract land on `master` via this PR so a future intermittency, if it
recurs, is caught by the same tooling without a fresh spec cycle.

---

## Runs

Two full 8×5 matrices on the same APK, same identities, same pairing,
same emulator, same Mac VPN, same relay. Only the phone's default-data
SIM changed between them (no bootstrap, no re-onboarding, no re-QR).

| carrier | run_id                          | date (UTC)         | evidence_integrity | product_outcome |
|---------|---------------------------------|--------------------|-------------------|-----------------|
| YOTA    | `run-yota-20260812T171418Z`     | 2026-08-12 17:14Z  | GREEN             | GREEN           |
| TELE2   | `run-tele2-20260812T182237Z`    | 2026-08-12 18:22Z  | GREEN             | GREEN           |

Both runs: 6 WSS cells × 5 envelopes = 30/30 Delivered once. 2 REST
control cells × 5 envelopes = 10/10 Delivered once. Total 40/40 per
carrier.

All four scenarios per direction:
* immediately after connect,
* after a natural 300-second idle,
* background → foreground restore,
* same-path REST control.

Both directions (`p2e` and `e2p`) closed in both runs.

## Independent verification checks

Both bundles re-verified GREEN under HEAD's `verify-evidence.py`
without any modification:

```
evidence_integrity=GREEN
product_outcome=GREEN
```

Verifier confirms:

* Diagnostic APK sha256 = `8802b063…b84c` on both devices for both
  runs (Round-5 P1 APK-binding gate).
* `signed_prekey_readiness published=true` on both devices for both
  runs (Round-7 P1-2 gate).
* Yota bundle uses the legacy Round-9 schema (`yota_confirmed=true`,
  no WSS-2 companion fields).
* Tele2 bundle uses the WSS-2 schema
  (`operator_confirmed=true`, `operator_label=TELE2`,
  `expected_operator_numeric=25020`, cross-file consistency valid,
  `matrix.run_id` starts with `run-tele2-`).

## Archive checksums (sha256)

```
f33a1387280193e8ec7e3574b63b53e1ce399deb152df1d2646db386030c9266  yota-wss-GREEN-20260812T171418Z.tar.gz
226620a26640eceb8326367d71d8845a4d0fe69acdabfe6e198a4d766ee95722  tele2-wss-GREEN-20260812T182237Z.tar.gz
```

The archives themselves are NOT committed to this repository — they
contain per-device serials, correlation IDs, and other run-level
metadata that has no place in a public tree. The checksums above are
the durable record.

## What we can honestly claim

* On the current client + relay + Mac VPN configuration, and on this
  particular pair of devices with this particular pair of SIM cards,
  a controlled 8×5 matrix delivers cleanly in both directions for
  BOTH carriers.
* The Round-5..Round-9 diagnostic + verifier + smoke-gate + typed
  `RUN-FULL-MATRIX` scaffolding functioned end-to-end without a
  false negative on either carrier.

## What we cannot claim

* We **have not** proved a systematic Yota or Tele2 pattern that
  blocks Direct WSS.
* We **have not** reproduced the historical Direct WSS intermittency
  that motivated this whole track. Two GREEN runs on a specific day
  in a specific network session are insufficient to demonstrate its
  absence.
* We **have not** proved long-duration socket stability. Each matrix
  is bounded (≈ hour-long), covers 40 envelopes, and the longest
  wait inside a cell is a 300-second `after-idle` scenario.
* We **have not** attributed any relay-side causality — the verifier
  is client-only per §12 (`Notes:` in every rendered report).

## Superseded artefact

The earlier bundle `run-yota-20260812T082016Z-instrumentation-failure`
(archive sha `1b84172b…daf3c`) is recorded as a
**tooling / instrumentation failure**, not a Direct WSS product
failure. Its downstream `sender_enqueue` / `sender_transport_decision`
/ route-return events were absent because of a coordinator-and-runner
tooling bug — closed in Round-6 (`3d7ad16e`) and hardened through
Round-7..Round-9. See §12.6..§12.9 in the contract sheet for the
line-level trace. Do NOT read this earlier bundle as evidence about
Yota's transport behaviour.

## Historical root cause — remains unresolved

Candidates that would explain the earlier intermittency but have not
been disproved:

* Client-side bug fixed between the reported historical failures and
  this run (client + relay + shared modules moved a lot).
* Prekey publish latency on a fresh pair (Round-7 `sender_prekey_deferred`
  path). Preflight now gates on `signed_prekey_readiness=true`, so a
  live run cannot enter the matrix while this window is open.
* Long-lived socket teardown or middlebox idle timeout longer than the
  300 s in the `after-idle` scenario.
* A network session on a specific date/time whose properties we did
  not sample here.

Any of these could still be true. Two GREEN matrices on 2026-08-12 do
not refute any of them.

## Reopening criteria

Restart active investigation only when at least one of:

1. A live pass emits an `Unresolved` cell or an integrity-RED bundle
   under the current tooling.
2. A separate, bounded, long-soak test (hours-to-days) reproduces
   drops on a controlled configuration.
3. Independent telemetry from a released client surfaces the pattern.

Until then, the diagnostic APK stays available and the verifier +
operator package remain on `master`, but no further engineering time
goes into this track. Product work resumes: onboarding
`C6-existing-account`, `C6-c/d/e`, `C7`.

## What lands with this PR

* `apps/android/src/androidMain/kotlin/phantom/android/diagnostic/` —
  `WssDiag.kt` (androidMain emit helper + test seam),
  `DiagnosticTransportGuard.kt` (in-memory pin + outer-arm reader).
* `apps/android/src/debug/` — debug-only manifest overlay + full
  diagnostic component tree (`DiagnosticCommandReceiver`,
  `DiagnosticSendCoordinator`, `DiagnosticBootInit`,
  `DiagnosticTransportPinStore`). DUMP-permission-gated. Physically
  absent from the release APK (source-set separation verified by
  `DiagnosticSourceSetBoundaryTest`).
* `apps/android/src/androidMain/kotlin/phantom/android/transport/HybridRelayTransport.kt`
  — Method-(b) fail-closed check on the actually-selected outer arm.
* `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt`
  — expose non-consuming `PreKeyApi` to the debug receiver.
* `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/`
  — `WssDiagBridge.kt` (interface + holder, nullable in production),
  `DefaultMessagingService.kt` instrumentation at the queue-boundary +
  send-attempt-started + prekey-deferred sites.
* `docs/tracks/direct-wss/` — contract sheet
  (`direct-wss-yota-contract.md`) + operator package
  (verifier, runner, portable helpers, contract tests, fixture
  suites, README, packager) + this comparison document.
* Focused test coverage: 8 Kotlin diagnostic classes,
  2 shared-messaging DMS ordering tests, 118 Python verifier
  fixtures, 79 shell fixtures.

Release-source separation is enforced at build time. No product UI
changes.
