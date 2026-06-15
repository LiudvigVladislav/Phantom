# M-OPK-3 Wi-Fi field gate — runbook

> **Policy update 2026-06-16 — documented field-shape harness, not a binding gate.**
> The L10 §"Atomic-pair landing gate" in `docs/tracks/sprint-2b-opk-pending-session-scope.md` was amended on 2026-06-16 to remove `Wi-Fi M-OPK-3 PASS` from the Sprint 2b complete criterion. The procedure below was executed against master `7e421728` (Sprint 2b-C present) and produced an INCONCLUSIVE verdict — the runbook's relaunch flow does not engineer the race window the gate is designed to test. See §"Known limitation — relaunch-with-existing-SPK skips the publish cycle (2026-06-16)" below for the root cause and the Variant 2 `pm clear` + first-pair workaround (which is documented but explicitly NOT promoted to a binding gate either). **Stage 2B-D Tele2 LTE integration smoke** is now the single binding promotion gate for PR #310 ready-for-review and the Stage 2B-D rollout flag flip. The §"Pre-flight" + §"Procedure" sections below are retained as the legacy as-written harness so future operators can run the documented procedure if they want a field-shape exercise — but a clean run produces INCONCLUSIVE, not PASS.

**Type:** Manual / scripted instrumented test. NOT executed by `:apps:android:testDebugUnitTest` or any local `gradle` task. Requires a connected Android device or emulator + the relay deployed + adb pairing.

**Owner:** Sprint 2b-C scope-doc `docs/tracks/sprint-2b-opk-pending-session-scope.md` §L10 (amended 2026-06-16; see §"Known limitation" below for the amendment trail).

**Acceptance gate (LEGACY, per the §"Policy update" above, no longer binding):** Original wording — PASS = step 9 + step 10 below. FAIL = either condition. INVALID = transport-blocked before step 8 (in which case re-run after triage). **Revised 2026-06-16:** the §"Procedure" is preserved for reference, but a clean run produces INCONCLUSIVE because the publish cycle is skipped on relaunch (see §"Known limitation"). Treat any positive result as anecdotal field-shape evidence, not as the L10 gate-3 PASS — that PASS criterion was removed in the 2026-06-16 amendment.

**What this gate is designed to test:** the Sprint 2b-C pending→active runtime atomically promotes a candidate session under the field shape of the 2026-06-15 integration LTE smoke (publish-retry-during-reservation window). With Sprint 2b-A's L1 publish-snapshot consistency closure + Sprint 2b-B's L4 deferred-consume contract + Sprint 2b-C's `promotePendingToActive` site, the second peer reply should land via the inbound-repair branch with zero `errorClass=OpkNotFound` AND zero `fail_mac action=hold` in `logcat`. **What this gate actually achieves on a clean Wi-Fi run (per 2026-06-16 evidence):** the `am force-stop` step does NOT wipe the local SPK; on relaunch the prekey lifecycle service skips the publish cycle entirely; the operator hook never reaches its fire site; the inbound-repair branch is never entered; no race is engineered. Stage 2B-D Tele2 LTE is the gate that actually exercises the failure shape.

## Pre-flight

- Two devices (Tecno + emulator, OR two phones) on the same Wi-Fi network. No Tele2 / 3G/4G/5G — that is the Stage 2B-D Tele2 LTE smoke, not this gate.
- Relay deployed at the URL the test APK's `BuildConfig.RELAY_URL` points at. Confirm via `curl -i $RELAY_URL/health` (the relay exposes `/health` only, not `/healthz` — `services/relay/src/routes.rs:52`).
- A debug APK built from the current `feat/sprint-2b-c-promotion-outbound-reuse` branch (or a successor that includes this PR's merge) **plus the local `publishWithRetryDelayHook` patch below**. The hook is NOT shipped in this PR — it is operator-applied for each M-OPK-3 run because the seam injects a deterministic stall into the publish retry loop. The patch is intentionally kept out of the merged tree.
- `adb shell pm clear phantom.android` on both devices before each fresh run. The gate assumes a clean install.

### Operator-applied `publishWithRetryDelayHook` patch (NOT in PR)

Apply the diff below locally before building the M-OPK-3 APK. Revert before any production / release build — the hook is gated behind a runtime SystemProperty read so a stray production build with this patch still no-ops when the prop is unset, but the safer posture is to keep the patch off the merged tree entirely.

**SELinux prop-name gotcha (2026-06-16).** Stock Android only lets the `shell` SELinux domain write to props whose names map into the `debug_prop` (or similar dev) context. Custom names like `phantom.test.publish_retry_delay_ms` get `Failed to set property ... See dmesg for error reason` back from `setprop`. The hook below therefore uses `debug.phantom_retry_delay_ms` — the `debug.` prefix maps into `debug_prop` on every stock-Android image I've encountered. If your device denies even that, check `getprop ro.build.tags` and `getprop ro.boot.veritymode` and pick a different `debug.*` name that your `property_contexts` allows.

```diff
--- a/shared/core/transport/src/commonMain/kotlin/phantom/core/transport/PreKeyApiClient.kt
+++ b/shared/core/transport/src/commonMain/kotlin/phantom/core/transport/PreKeyApiClient.kt
@@ at the top of the `for (attempt in 1..PUBLISH_MAX_ATTEMPTS)` loop, before the L1 re-snapshot comment:
+            // M-OPK-3 operator hook — NOT in merged tree.
+            // Applied locally for the Wi-Fi field gate run only.
+            //
+            // Uses reflection so this hook compiles on every commonMain
+            // target (JVM tests + Android APK builds). On Android the
+            // reflection call resolves `android.os.SystemProperties.get(...)`
+            // and reads the prop set by `adb shell setprop
+            // debug.phantom_retry_delay_ms <ms>`. On JVM the class is
+            // absent → ClassNotFoundException → runCatching swallows it
+            // → stallMs=0 → branch is a no-op.
+            //
+            // Stall fires WHEN we ENTER attempt 2 (i.e. the first retry):
+            // the inbound bootstrap that races needs a deterministic
+            // window to reserve the same OPK locally BEFORE attempt 2's
+            // body re-snapshot runs and re-includes that OPK in the
+            // outgoing prekey publish.
+            val stallMs = runCatching {
+                val cls = Class.forName("android.os.SystemProperties")
+                val getMethod = cls.getMethod(
+                    "get",
+                    String::class.java,
+                    String::class.java,
+                )
+                val value = getMethod.invoke(
+                    null,
+                    "debug.phantom_retry_delay_ms",
+                    "0",
+                ) as String
+                value.toLongOrNull() ?: 0L
+            }.getOrDefault(0L)
+            if (stallMs > 0L && attempt == 2) {
+                relayLog(
+                    RelayLogLevel.WARN,
+                    "M_OPK_3_HARNESS publish_retry_stall stallMs=$stallMs attempt=$attempt",
+                )
+                delay(stallMs)
+            }
```

The reflection shape is intentional. The earlier direct-reference variant from the 2026-06-15 runbook draft used `phantom.android.BuildConfig.DEBUG` + `android.os.SystemProperties.get(...)` literally inside `commonMain`, which broke `:shared:core:transport:jvmTest` (and any other `commonMain` target that is not Android). The reflection variant keeps `commonMain` platform-portable — the 2026-06-16 throwaway-branch build kept `:shared:core:transport:jvmTest` green alongside `:apps:android:assembleDebug`. The hook still stays out of the merged tree; the runbook documents both shapes for trail.

## Procedure (Wi-Fi only) — LEGACY, retained for reference only

> **Warning.** A clean run of the procedure below produces an INCONCLUSIVE verdict per the 2026-06-16 evidence — step 8's premise that the relaunch triggers a publish cycle is wrong on any device whose local SPK row survived the force-stop in step 6 (which is every device that already onboarded). See §"Known limitation" below for the root cause + the Variant 2 `pm clear` workaround. **The procedure is retained here for trail. Stage 2B-D Tele2 LTE is the binding promotion gate after the 2026-06-16 amendment.**

1. `pm clear phantom.android` on both Tecno + emulator.
2. Launch Phantom on both. Complete onboarding (username + identity generation).
3. QR-pair: scan Tecno's QR code from the emulator (or vice versa) — pair fresh.
4. Tecno → emu: send the text "alpha-1". Wait for the emulator UI to render it (≤ 5 s under normal Wi-Fi).
5. emu → Tecno: send the reply "beta-1". Wait for Tecno UI to render it.
6. `adb -s <tecno-serial> shell am force-stop phantom.android`.
7. `adb -s <tecno-serial> shell setprop debug.phantom_retry_delay_ms 5000`. This activates `publishWithRetryDelayHook` for the next Tecno publish cycle IF one fires. (Prop name is `debug.phantom_retry_delay_ms`, not `phantom.test.publish_retry_delay_ms` — the earlier draft used the latter, which stock-Android SELinux denies; see §"Operator-applied patch" above for the gotcha.)
8. `adb -s <tecno-serial> shell am start -n phantom.android/.MainActivity`. Tecno re-launches Phantom. **The 2026-06-16 evidence shows this step does NOT trigger a publish cycle on a device that already has a local SPK row** — the lifecycle service emits `PREKEY_TRACE bootstrap_skip_existing_spk ... no publish` and `publishWithRetry` is never invoked. The hook therefore never reaches its `attempt == 2` fire site. (The original wording here was "The local prekey lifecycle service WILL publish on startup; attempt 3 stalls 5 s per the hook." That wording was wrong; see §"Known limitation".)
9. WITHIN the (hypothetical) 5-second window: emu → Tecno: send "beta-2". The Sprint 2a guard fires on the emulator's outbound (existing RESPONDER → fresh bootstrap), the emulator attaches `x3dhInit` referencing one of Tecno's OPKs. On the 2026-06-16 run, Tecno's existing-session decrypt succeeded with `bootstrap=false` and the inbound-repair branch was never entered, so the publish-stall race was not exercised even though emu's outbound carried `x3dhInit` correctly.
10. Watch Tecno UI: "beta-2" SHOULD render. Watch `adb -s <tecno-serial> logcat -s PhantomMessaging` for the inbound-repair line. On a clean Wi-Fi run with this procedure, the inbound-repair line will NOT appear and the verdict per §"Pass criteria" cannot be reached — the run is INCONCLUSIVE.

## Pass criteria (HISTORICAL — the gate is no longer binding per the 2026-06-16 amendment)

The criteria below describe what a successful exercise of the documented field shape would look like. The 2026-06-16 evidence shows the procedure as-written does NOT engineer this shape; see §"Known limitation" for the reasoning and the operational policy.

- Step 9 — Tecno emits `DECRYPT_TRACE inbound_repair_ok msgId=... bootstrap=true ... promotion=true` in logcat. The `promotion=true` segment is load-bearing — `promotion=false reason=...` means the Sprint 2b-C runtime hit a race-degraded path; a missing `promotion=` segment entirely means the test ran against a pre-Sprint-2b-C build.
- Step 10 — Tecno UI renders "beta-2" without operator intervention.
- ZERO `errorClass=OpkNotFound` lines in logcat for the test window.
- ZERO `fail_mac action=hold` lines in logcat for the test window.

## Fail / invalid handling

- `errorClass=OpkNotFound` or `fail_mac action=hold` in logcat → gate FAIL (treat as field-shape evidence; capture the full logcat with `adb logcat -d > m-opk-3-fail-<timestamp>.log` + the `opk_not_found_total{INBOUND_REPAIR_OPK_NOT_FOUND}` counter snapshot via the debug hook). Under the 2026-06-16 amendment this does NOT block the L10 gate-3 step; it does, however, block the Stage 2B-D PASS if the same shape reproduces on Tele2 LTE.
- No `M_OPK_3_HARNESS publish_retry_stall` line in logcat → the hook never fired. The expected reason (per the 2026-06-16 evidence): `PREKEY_TRACE bootstrap_skip_existing_spk ... no publish` at +3 s after relaunch, because the local SPK row survived the force-stop. Verdict is INCONCLUSIVE, NOT pass. See §"Known limitation" for the Variant 2 workaround.
- Step 10 envelope arrived but UI did not render → likely a downstream payload handler issue; not a Sprint 2b-C crypto failure. Triage separately.

## Why this is a runbook, not a `@Test` class

- The harness's correctness depends on the `publishWithRetryDelayHook` SystemProperties + `am start` driver timing, both of which live OUTSIDE the JUnit runtime;
- the gate's PASS condition is a logcat tail + UI observation, not an `assertEquals` against a return value;
- production builds MUST never carry the `publishWithRetryDelayHook` seam (it's behind a debug-build SystemProperties check), so converting this into a Gradle-runnable `@Test` would require either pulling the seam into shipped code (no) or staging an Android-only test variant with the seam (deferred work — out of Sprint 2b-C scope).

The deterministic per-runtime-path coverage Sprint 2b-C ships is at the unit level: cells M-2bC-1 (pending fallback → promote success), M-2bC-2/5 (outbound reuse + cached x3dhInit), M-2bC-3 (TTL expiry → fresh bootstrap), M-2bC-4 (storage atomicity), and the false-branch cell (promote returns false → plaintext returned + log degraded). Those all execute in `:shared:core:messaging:jvmTest` + `:shared:core:storage:jvmTest`. M-OPK-3 is the field-shape acceptance gate on top.

## Known limitation — relaunch-with-existing-SPK skips the publish cycle (2026-06-16)

**Symptom.** A run that follows the §"Procedure (Wi-Fi only)" steps verbatim does NOT engineer the race. The logcat shows neither `M_OPK_3_HARNESS publish_retry_stall` (the hook never reaches the fire site) nor `inbound_repair_ok promotion=true` (the inbound-repair runtime path is never entered). User-visible delivery still works, so the run reads as "no errors" but does not prove the Sprint 2b-C runtime; it produces an INCONCLUSIVE verdict that has to be honestly recorded as "race not engineered", NOT as PASS.

**Root cause.** Step 6 in §"Procedure" assumes that the `am force-stop` + `am start` cycle on Tecno triggers a fresh prekey publish cycle that the operator-applied `publishWithRetryDelayHook` patch can stall. It does not. `force-stop` is a process kill; it does NOT wipe SQLDelight rows. On the next launch, the prekey lifecycle service finds the local `signed_pre_key` row already present and emits `PREKEY_TRACE bootstrap_skip_existing_spk — local SPK already present, no publish` ~3 seconds after process start. `publishWithRetry` is never invoked → `attempt` never reaches `2` → the hook's `if (stallMs > 0L && attempt == 2)` guard is never evaluated → no stall → no race window.

The runbook §"Procedure" line "The local prekey lifecycle service WILL publish on startup; attempt 3 stalls 5 s per the hook" is therefore wrong for any relaunch flow that is not preceded by a `pm clear` (or some other action that removes the local SPK row).

**Variant 2 workaround — `pm clear` + fresh-pair publish.** A first-pair publish IS triggered when the local DB is empty. A modified procedure that exercises a publish stall is:

1. `pm clear phantom.android` on Tecno → onboarding from zero (no existing SPK) → Phantom emits a publish on the very first identity provisioning.
2. `setprop debug.phantom_retry_delay_ms 5000` BEFORE launching the app.
3. Launch Phantom on Tecno; the publish cycle fires; the hook stalls attempt 2 IF attempt 1 retries.
4. During the 5-second stall: on the emulator (also freshly `pm clear`-ed and onboarded), initiate a pair with Tecno via QR scan (or whatever the current pair UI is). The emulator's first outbound message to Tecno carries `x3dhInit` referencing one of Tecno's freshly-published OPKs; the race is engineered on the next prekey publish in that window.

The Variant 2 workaround is **not promoted to a binding gate** by this document. The reasons:

- It exercises a DIFFERENT scenario than the runbook's original intent (first-pair publish race, not the relaunch-with-existing-pair race the original §"Procedure" describes);
- It still depends on attempt 1 actually retrying for the hook to fire, which is conditional on transport conditions that are not under operator control on clean Wi-Fi;
- The deterministic Sprint 2b-C runtime coverage already lives in unit tests (`Sprint2bCStorageContractTest` + the M-2bC-* cells in `DefaultMessagingServiceTest`);
- The acceptance-meaningful field signal is the **Stage 2B-D Tele2 LTE integration smoke** — that is the carrier and transport stack where the 2026-06-15 `errorClass=OpkNotFound action=fall_through_to_hold` failure shape actually manifested, and a Tele2 LTE re-run after Sprint 2b-C is the operational gate that decides whether the fix lands. A Wi-Fi-only harness cannot reproduce the Tele2 LTE timing pressure that opens the original race window organically.

**Operational policy.** Until the runbook is rewritten with a binding scenario that reliably engineers the race on Wi-Fi (or until a Variant 2 fresh-pair scenario is itself locked as a binding gate after a separate scope discussion), the M-OPK-3 line item in `docs/tracks/sprint-2b-opk-pending-session-scope.md` L10 is treated as **executed but inconclusive**, and Stage 2B-D Tele2 LTE remains the binding promotion gate for PR #310 ready-for-review and the Stage 2B-D rollout flag flip.

## References

- `docs/tracks/sprint-2b-opk-pending-session-scope.md` — binding scope-doc, §"Instrumented tests (androidInstrumentedTest)".
- `docs/adr/ADR-029-pending-active-session-state-machine.md` — final 2b-C runtime model + acceptance trail.
- 2026-06-15 integration LTE smoke evidence: `C:\temp\trek2-stage2b-b-d-integration-smoke-2026-06-15\`.
- 2026-06-16 M-OPK-3 Wi-Fi run that surfaced the §"Known limitation" above: `C:\temp\m-opk-3-tecno-20260616-021243.log` (UTF-16) — runbook-as-written did not engineer the race; root cause + Variant 2 workaround documented in this file's §"Known limitation".
- `docs/PROJECT_LOG.md` 2026-06-16 entry — Sprint 2b-C ship + M-OPK-3 inconclusive + Stage 2B-D remains the binding gate.
