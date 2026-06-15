# M-OPK-3 Wi-Fi field gate — runbook

**Type:** Manual / scripted instrumented test. NOT executed by `:apps:android:testDebugUnitTest` or any local `gradle` task. Requires a connected Android device or emulator + the relay deployed + adb pairing.

**Owner:** Sprint 2b-C scope-doc `docs/tracks/sprint-2b-opk-pending-session-scope.md` §"Tests (commonTest)" + §"Instrumented tests" + L10 gate sequence.

**Acceptance gate:** PASS = step 9 + step 10 below. FAIL = either condition. INVALID = transport-blocked before step 8 (in which case re-run after triage).

**What this gate proves:** the Sprint 2b-C pending→active runtime atomically promotes a candidate session under the field shape of the 2026-06-15 integration LTE smoke (publish-retry-during-reservation window). With Sprint 2b-A's L1 publish-snapshot consistency closure + Sprint 2b-B's L4 deferred-consume contract + Sprint 2b-C's `promotePendingToActive` site, the second peer reply MUST land via the inbound-repair branch with zero `errorClass=OpkNotFound` AND zero `fail_mac action=hold` in `logcat`. Per L10 this gate must PASS before Stage 2B-D Tele2 LTE integration smoke is re-run, which in turn must PASS before PR #310 flips ready.

## Pre-flight

- Two devices (Tecno + emulator, OR two phones) on the same Wi-Fi network. No Tele2 / 3G/4G/5G — that is the Stage 2B-D Tele2 LTE smoke, not this gate.
- Relay deployed at the URL the test APK's `BuildConfig.RELAY_URL` points at. Confirm via `curl -i $RELAY_URL/health` (the relay exposes `/health` only, not `/healthz` — `services/relay/src/routes.rs:52`).
- A debug APK built from the current `feat/sprint-2b-c-promotion-outbound-reuse` branch (or a successor that includes this PR's merge) **plus the local `publishWithRetryDelayHook` patch below**. The hook is NOT shipped in this PR — it is operator-applied for each M-OPK-3 run because the seam injects a deterministic stall into the publish retry loop. The patch is intentionally kept out of the merged tree.
- `adb shell pm clear phantom.android` on both devices before each fresh run. The gate assumes a clean install.

### Operator-applied `publishWithRetryDelayHook` patch (NOT in PR)

Apply the diff below locally before building the M-OPK-3 APK. Revert before any production / release build — the hook is gated behind `BuildConfig.DEBUG` AND a runtime SystemProperties flag, but the safer posture is to keep the patch off the merged tree entirely:

```diff
--- a/shared/core/transport/src/commonMain/kotlin/phantom/core/transport/PreKeyApiClient.kt
+++ b/shared/core/transport/src/commonMain/kotlin/phantom/core/transport/PreKeyApiClient.kt
@@ within publishWithRetry, between attempts (after the attempt N response handling, BEFORE the next loop iteration's `delay(nextDelay)`):
+            // M-OPK-3 operator hook — NOT in merged tree. Applied
+            // locally for the Wi-Fi field gate run only.
+            //
+            // Behind BuildConfig.DEBUG AND a SystemProperties flag so
+            // a stray build that picks this up still no-ops in
+            // production. The stall is intentionally placed BETWEEN
+            // attempts so the inbound bootstrap that races has a
+            // deterministic window to reserve the OPK locally before
+            // attempt N+1 ships.
+            if (phantom.android.BuildConfig.DEBUG) {
+                val stallMs = runCatching {
+                    android.os.SystemProperties.get(
+                        "phantom.test.publish_retry_delay_ms",
+                        "0",
+                    ).toLongOrNull() ?: 0L
+                }.getOrDefault(0L)
+                if (stallMs > 0L && attempt == 2) {
+                    relayLog(
+                        RelayLogLevel.WARN,
+                        "M_OPK_3_HARNESS publish_retry_stall stallMs=$stallMs attempt=$attempt",
+                    )
+                    delay(stallMs)
+                }
+            }
```

Note that `phantom.android.BuildConfig` + `android.os.SystemProperties` are Android-only — applying the patch verbatim breaks the `:shared:core:transport:jvmTest` build. Either guard with `expect/actual` per platform OR keep the patch on a throwaway local branch that the M-OPK-3 operator builds from. The intentional choice for Sprint 2b-C is the throwaway-branch shape; this runbook does not ship a platform-portable hook.

## Procedure (Wi-Fi only)

1. `pm clear phantom.android` on both Tecno + emulator.
2. Launch Phantom on both. Complete onboarding (username + identity generation).
3. QR-pair: scan Tecno's QR code from the emulator (or vice versa) — pair fresh.
4. Tecno → emu: send the text "alpha-1". Wait for the emulator UI to render it (≤ 5 s under normal Wi-Fi).
5. emu → Tecno: send the reply "beta-1". Wait for Tecno UI to render it.
6. `adb -s <tecno-serial> shell am force-stop phantom.android`.
7. `adb -s <tecno-serial> shell setprop phantom.test.publish_retry_delay_ms 5000`. This activates `publishWithRetryDelayHook` for the next Tecno publish cycle.
8. `adb -s <tecno-serial> shell am start -n phantom.android/.MainActivity`. Tecno re-launches Phantom. The local prekey lifecycle service WILL publish on startup; attempt 3 stalls 5 s per the hook.
9. WITHIN that 5-second window: emu → Tecno: send "beta-2". The Sprint 2a guard fires on the emulator's outbound (existing RESPONDER → fresh bootstrap), the emulator attaches `x3dhInit` referencing one of Tecno's OPKs. Tecno receives this envelope while its inbound-repair branch reserves the same OPK locally; the publish hook ensures attempt 3 fires AFTER the reservation lands.
10. Watch Tecno UI: "beta-2" MUST render. Watch `adb -s <tecno-serial> logcat -s PhantomMessaging` for the inbound-repair line.

## Pass criteria

- Step 9 — Tecno emits `DECRYPT_TRACE inbound_repair_ok msgId=... bootstrap=true ... promotion=true` in logcat. The `promotion=true` segment is load-bearing — `promotion=false reason=...` means the Sprint 2b-C runtime hit a race-degraded path and the gate is INVALID (re-run); a missing `promotion=` segment entirely means the test ran against a pre-Sprint-2b-C build.
- Step 10 — Tecno UI renders "beta-2" without operator intervention.
- ZERO `errorClass=OpkNotFound` lines in logcat for the test window.
- ZERO `fail_mac action=hold` lines in logcat for the test window.

## Fail / invalid handling

- `errorClass=OpkNotFound` or `fail_mac action=hold` in logcat → gate FAIL. Capture the full logcat (`adb logcat -d > m-opk-3-fail-<timestamp>.log`) + the `opk_not_found_total{INBOUND_REPAIR_OPK_NOT_FOUND}` counter snapshot (debug hook) + post-merge investigation per scope-doc "PR review-time checks".
- Step 9 envelope did not arrive at Tecno before the hook delay elapsed → INVALID; re-run the gate with a shorter delay or repeated test runs until the window aligns.
- Step 10 envelope arrived but UI did not render → likely a downstream payload handler issue; not a Sprint 2b-C crypto failure. Triage separately.

## Why this is a runbook, not a `@Test` class

- The harness's correctness depends on the `publishWithRetryDelayHook` SystemProperties + `am start` driver timing, both of which live OUTSIDE the JUnit runtime;
- the gate's PASS condition is a logcat tail + UI observation, not an `assertEquals` against a return value;
- production builds MUST never carry the `publishWithRetryDelayHook` seam (it's behind a debug-build SystemProperties check), so converting this into a Gradle-runnable `@Test` would require either pulling the seam into shipped code (no) or staging an Android-only test variant with the seam (deferred work — out of Sprint 2b-C scope).

The deterministic per-runtime-path coverage Sprint 2b-C ships is at the unit level: cells M-2bC-1 (pending fallback → promote success), M-2bC-2/5 (outbound reuse + cached x3dhInit), M-2bC-3 (TTL expiry → fresh bootstrap), M-2bC-4 (storage atomicity), and the false-branch cell (promote returns false → plaintext returned + log degraded). Those all execute in `:shared:core:messaging:jvmTest` + `:shared:core:storage:jvmTest`. M-OPK-3 is the field-shape acceptance gate on top.

## References

- `docs/tracks/sprint-2b-opk-pending-session-scope.md` — binding scope-doc, §"Instrumented tests (androidInstrumentedTest)".
- `docs/adr/ADR-029-pending-active-session-state-machine.md` — final 2b-C runtime model + acceptance trail.
- 2026-06-15 integration LTE smoke evidence: `C:\temp\trek2-stage2b-b-d-integration-smoke-2026-06-15\`.
