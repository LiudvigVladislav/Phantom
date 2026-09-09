# WSS-3 canonical producer fixtures

These JSON files are the **single canonical shape** of the on-device
`DiagnosticNetworkProfileReporter` output (contract §5, real producer
pin `apps/android/src/debug/kotlin/phantom/android/diagnostic/DiagnosticNetworkProfileReporter.kt:504-582`).

Three sinks consume them so no hand-copy can drift:

1. **Python test-helper** — `tests/test_verifier_wss3.py::build_full_profile_evidence`
   loads these into `network_profile.json` verbatim.
2. **Shell mock** — `tests/dry_run_matrix_p0_7.sh::_write_network_profile_template`
   loads `canonical_network_profile.phone.json` for the per-profile
   template; `lib/wss3-adb-mock.sh` extends with per-run HMAC / http
   status derived at execution time.
3. **Kotlin focused test** —
   `apps/android/src/debugUnitTest/kotlin/phantom/android/diagnostic/DiagnosticNetworkProfileReporterCanonicalShapeTest.kt`
   parses the real reporter's `buildJson` output and asserts key set +
   types match `canonical_network_profile.emu.json`.

Any producer change (Kotlin) MUST be mirrored here in the same commit.
The Python schema module `schema_wss3.py` and the mutation generator
consume these fixtures.

Contract for the two files:

- `canonical_network_profile.phone.json` — phone side, NO
  `egress_fingerprint` (phone MUST NOT participate in HMAC, §5 /
  ROUND-10 P0-1).
- `canonical_network_profile.emu.json` — emu side, includes
  `egress_fingerprint` with pinned `endpoint = "api4.ipify.org"`,
  `timeout_ms_used = 5000` (EGRESS_TIMEOUT_MS).

Pinned constants (from the Kotlin producer, DO NOT drift):

- `schema_version = "1"` (`DiagnosticNetworkProfileReporter.kt:62`)
- `EGRESS_TIMEOUT_MS = 5000` (`.kt:59`)
- `endpoint = "api4.ipify.org"` (`.kt:559`)
