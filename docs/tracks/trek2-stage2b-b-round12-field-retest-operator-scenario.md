# Trek 2 Stage 2B-B Round 12 — field re-test C0/C1/C2 operator scenario

Companion to the binding decisions doc at `docs/tracks/trek2-stage2b-b-deployment-gate-and-body-timeout-contract.md` (which itself encodes the council synthesis — the upstream Layer 1 / Layer 2 review threads were captured ephemerally during the council session) and the underlying log evidence at `docs/tracks/trek2-stage2b-b-tele2-smoke-d395f682-2026-06-13/`.

This is NOT a re-run of the original S1-S6 smoke. It is a narrow three-condition matrix designed to discriminate the four competing failure-mode hypotheses surfaced by the S6 council on `d395f682`:

- H1 — Tele2 LTE drops response body bytes mid-transit (byte-budget cutoff).
- H2 — Client `callTimeout` / `readTimeout` too tight for the relay's hold-then-body shape.
- H3 — Server returns headers immediately but defers body in a way that exceeds the client timeout.
- H4 — Intermediate TLS / Caddy proxy condition.

## APK identity

Round 12 branch HEAD `c2a77d17` produced two diagnostic APKs:

| APK | Variant | `BuildConfig.POLL_SKIP_LP_AND_PP` | SHA-256 |
|---|---|---|---|
| APK-A | baseline | `"0"` | `e47d9f7a45ba9ef06637234ac7d1648a94a9f0ca1b5749975eb506c538df5881` |
| APK-B | diagnostic | `"1"` | `7985e40e0b6ec469a05a232b3a345214b32c0ec0ae03678f2311e201f4459357` |

Both APKs sit at `apps/android/build/outputs/apk/debug/`:

- `android-debug-A-baseline.apk` — production wire shape (LP+PP headers emit normally).
- `android-debug-B-diagnostic.apk` — `POLL_SKIP_LP_AND_PP="1"` baked in; the strip ACTIVATES only when (a) running on a debug variant (always true here) AND (b) the active `PrivacyMode == Standard`. Privacy or Ghost sessions ride APK-B with the strip OFF — a built-in privacy backstop.

## How both APKs were built

```powershell
cd "D:\VL Stories Studio\Phantom"

# APK-A baseline
./gradlew :apps:android:assembleDebug
cp apps/android/build/outputs/apk/debug/android-debug.apk apps/android/build/outputs/apk/debug/android-debug-A-baseline.apk

# APK-B diagnostic
./gradlew :apps:android:assembleDebug -PpollSkipLpAndPp=1
cp apps/android/build/outputs/apk/debug/android-debug.apk apps/android/build/outputs/apk/debug/android-debug-B-diagnostic.apk
```

The `-PpollSkipLpAndPp=1` Gradle property flows into the existing `localOrEnv("pollSkipLpAndPp", "POLL_SKIP_LP_AND_PP", "0")` helper via the `project.findProperty(...)` branch added in this round.

## Pre-flight (one-time, before any condition)

Per the existing Tele2 smoke runbook at `docs/tracks/trek2-stage2b-b-tele2-smoke.md`, replace the APK path with the relevant Round 12 APK. Pre-flight steps (SHA check, install, variant flag, S6 trigger sanity, WiFi off) are unchanged.

In every condition below the Tecno serial is `103603734A004351` and the laptop runs Windows / PowerShell. `adb` is at `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`.

Before EACH condition: re-verify the Tecno's PrivacyMode is `Standard`. The diagnostic strip in C2 only fires in Standard.

## Condition C0 — healthy long-poll delivery baseline

**Goal:** confirm that with `RELAY_POLL_HOLD_SECS=30` the long-poll path can deliver envelopes and advance the cursor under a healthy radio window. This is a **baseline delivery check** — it proves that the headers / hold / verify / cursor pipeline is working at all, NOT that the breaker-recovery path closes under degraded-WS conditions. The recovery proof lives in C1/C2 below.

A `breaker_closed` line MAY appear in C0 if breaker happened to be Open from a prior session and Standard polling closed it; if it does, that is a bonus signal worth recording but it is NOT required for C0 to pass. C0 does NOT explicitly suspend WS, so envelopes may legitimately arrive via the WS path; the load-bearing observation is that the REST observability surface (`hold_secs=30`, `seq_mac_verified`, `cursor_advanced`, body-phase events) is healthy.

**Setup:**

1. On VPS — set `RELAY_POLL_HOLD_SECS=30`:
   ```bash
   ssh phantom@relay.phntm.pro
   cd ~/Phantom/deploy
   if grep -q "^RELAY_POLL_HOLD_SECS=" .env; then
     sed -i 's/^RELAY_POLL_HOLD_SECS=.*/RELAY_POLL_HOLD_SECS=30/' .env
   else
     echo "RELAY_POLL_HOLD_SECS=30" >> .env
   fi
   grep "^RELAY_POLL_HOLD_SECS=" .env
   docker compose -f docker-compose.yml restart relay
   ```

2. On laptop — install APK-A baseline:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 install -r "D:\VL Stories Studio\Phantom\apps\android\build\outputs\apk\debug\android-debug-A-baseline.apk"
   ```

3. On Tecno — force-stop, relaunch, confirm Standard mode in app settings, wait for home screen.

4. On laptop — clear logcat + start capture in a dedicated PS window:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 logcat -c
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 logcat -v threadtime PhantomHybrid:I PhantomMessaging:I Phantom/S6Debug:I '*:S' | Out-File "C:\temp\c0-baseline.log" -Encoding utf8
   ```

5. From peer device, send 5 short text messages over 30 seconds to the Tecno's identity. Wait 60 seconds.

**Expected log evidence (run in a 3rd PS window):**

```powershell
$log = "C:\temp\c0-baseline.log"
"=== hold_secs ==="; Select-String -Path $log -Pattern "REST_TRACE poll_call " | Where-Object { $_.Line -match "hold_secs=30" } | Select-Object -First 3
"=== seq_mac_verified ==="; Select-String -Path $log -Pattern "REST_TRACE seq_mac_verified " | Select-Object -First 3
"=== cursor_advanced ==="; Select-String -Path $log -Pattern "REST_TRACE cursor_advanced seq=" | Select-Object -First 3
"=== breaker_closed (optional bonus signal, if it fires) ==="; Select-String -Path $log -Pattern "REST_TRACE breaker_closed"
"=== body_chunk (Step 2 instrumentation, debug-only) ==="; Select-String -Path $log -Pattern "phase_event op=poll event=body_chunk" | Select-Object -First 5
```

**Pass criteria (baseline delivery check):**

- `hold_secs=30` appears on `poll_call` lines (proves the server kill switch is off, contrary to d395f682).
- At least one `seq_mac_verified` line per delivered envelope.
- `cursor_advanced` advances monotonically across the 5 envelopes.
- `body_chunk` per-chunk lines fire (confirms the Step 2 instrumentation is observable; not a pass requirement on its own).

`breaker_closed` is NOT a C0 pass requirement. If it appears, record it as a bonus signal. If it does not, C0 still passes as long as the four bullets above hold; the recovery-closure proof is the job of C1/C2.

**Interpretation:**

- All pass → baseline delivery is healthy. Proceed to C1, which is where the breaker-recovery cycle gets exercised.
- `hold_secs=30` missing → the `.env` change did not propagate (likely needs `docker compose down/up` instead of `restart`). Fix and retry.
- `seq_mac_verified` empty + `inbound_unverified ... no_verify_key` present → the `RELAY_SEQ_MAC_KEY` flow is broken on the relay; investigate before continuing.
- `cursor_advanced` does not fire → broader REST delivery bug; stop, file separately.

## Condition C1 — reproduce the S6 kill-switch failure under the new instrumentation

**Goal:** with the kill switch turned back on, reproduce the d395f682 failure shape on APK-A. Confirm with the Step 2 instrumentation that we now see exactly where the body read stops (no body, partial body, or full body). This C1 is the "control" for C2.

**Setup:**

1. On VPS — flip kill switch on:
   ```bash
   sed -i 's/^RELAY_POLL_HOLD_SECS=.*/RELAY_POLL_HOLD_SECS=0/' .env
   grep "^RELAY_POLL_HOLD_SECS=" .env
   docker compose -f docker-compose.yml restart relay
   ```

2. APK-A stays installed (no reinstall needed).

3. On Tecno — force-stop, relaunch, confirm Standard.

4. From peer device or laptop — suspend WS on the Tecno's connection so REST poll is the only path. (Same mechanism the d395f682 smoke used; if no in-app toggle exists, the operator-scenario doc's Caddy `respond 503` overlay on `/ws` is the canonical workaround.)

5. Start capture in dedicated PS window:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 logcat -c
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 logcat -v threadtime PhantomHybrid:I PhantomMessaging:I Phantom/S6Debug:I '*:S' | Out-File "C:\temp\c1-kill-switch-repro.log" -Encoding utf8
   ```

6. Sustained Tele2 LTE outbound for 10 minutes. Do not exceed — this is a discriminator run, not a 30-minute smoke.

**Expected log evidence:**

```powershell
$log = "C:\temp\c1-kill-switch-repro.log"
"=== hold_secs=0 confirms kill switch ==="; Select-String -Path $log -Pattern "REST_TRACE poll_call " | Where-Object { $_.Line -match "hold_secs=0" } | Select-Object -First 3
"=== Body-phase chain (the load-bearing observation) ==="
"--- responseHeadersEnd ---"; Select-String -Path $log -Pattern "phase_event op=poll.*event=responseHeadersEnd" | Select-Object -First 3
"--- responseBodyStart ---"; Select-String -Path $log -Pattern "phase_event op=poll.*event=responseBodyStart" | Select-Object -First 3
"--- body_chunk per-chunk byte deltas ---"; Select-String -Path $log -Pattern "phase_event op=poll event=body_chunk" | Select-Object -First 10
"--- responseBodyEnd byteCount ---"; Select-String -Path $log -Pattern "phase_event op=poll.*event=responseBodyEnd"
"--- body_eof ---"; Select-String -Path $log -Pattern "phase_event op=poll event=body_eof"
"--- callFailed (the d395f682 hallmark) ---"; Select-String -Path $log -Pattern "phase_event op=poll.*event=callFailed"
```

**Discriminator decision tree (C1):**

| Observation | Hypothesis support |
|---|---|
| `responseHeadersEnd status=200` present; `responseBodyStart` ABSENT; `callFailed totalMs≈10005` | Body read never began. H1/H4 unlikely on byte path; H3 (server defers body emission) or upstream TLS buffering. |
| `responseBodyStart` present; `body_chunk` lines present with `cumulative_bytes` growing; `responseBodyEnd` ABSENT; `callFailed` follows | H1 confirmed at the byte-budget cutoff (body started, stalled mid-stream). Note the final `cumulative_bytes` value — that is the empirical cutoff. |
| `responseBodyStart` present; NO `body_chunk` lines (body started but produced zero `read()` callbacks before timeout); `callFailed` follows | Body source opened but stream never emitted bytes. Strong H4 (TLS/Caddy buffering condition); weaker H2 (timeout firing before any byte arrives is possible but unusual). |
| `responseBodyEnd byteCount=<full>` present; `callEnd` follows; recovery still fails | H1/H2 ruled out for this iteration; recovery failure root cause is downstream of body delivery (envelope parse, verify, ack, or cursor write). |

Capture the precise observation. C2 will use the SAME network state with a smaller body shape to confirm or refute H1 specifically.

## Condition C2 — body-size discriminator (the actual hypothesis test)

**Goal:** with everything else held identical to C1 (kill switch on, WS suspended, same network conditions), swap the APK for APK-B which strips BOTH LP+PP headers atomically in Standard mode. The resulting short-poll request gets a SMALL (unpadded) response body. If H1 (Tele2 byte-budget on response body) is the root cause, the smaller body should succeed where the 4608-byte padded body failed.

**Setup:**

1. VPS state unchanged (kill switch still on from C1).

2. On laptop — install APK-B diagnostic over APK-A:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 install -r "D:\VL Stories Studio\Phantom\apps\android\build\outputs\apk\debug\android-debug-B-diagnostic.apk"
   ```

3. On Tecno — force-stop, relaunch, **CONFIRM the app is in Standard mode** (the strip only fires when `PrivacyMode == Standard`). If a user accidentally has Private or Ghost, the strip stays off and C2 collapses into C1 — invalid run.

4. WS stays suspended.

5. Start capture:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 logcat -c
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 103603734A004351 logcat -v threadtime PhantomHybrid:I PhantomMessaging:I Phantom/S6Debug:I '*:S' | Out-File "C:\temp\c2-small-body-diagnostic.log" -Encoding utf8
   ```

6. Sustained Tele2 LTE outbound for 10 minutes.

**Expected log evidence:**

```powershell
$log = "C:\temp\c2-small-body-diagnostic.log"
"=== Strip is active (LP/PP headers ABSENT on poll_call) ==="
"--- expect EMPTY ---"; Select-String -Path $log -Pattern "REST_TRACE poll_call " | Where-Object { $_.Line -match "X-Phantom-Long-Poll=1" -or $_.Line -match "X-Phantom-Padded-Poll=1" }
"--- expect at least one with absent/0 ---"; Select-String -Path $log -Pattern "REST_TRACE poll_call " | Select-Object -First 3
"=== Body phase chain (same shape as C1, but with small body) ==="
"--- responseBodyStart ---"; Select-String -Path $log -Pattern "phase_event op=poll.*event=responseBodyStart" | Select-Object -First 3
"--- body_chunk per-chunk byte deltas (expect SMALL cumulative_bytes vs C1) ---"; Select-String -Path $log -Pattern "phase_event op=poll event=body_chunk" | Select-Object -First 10
"--- responseBodyEnd byteCount ---"; Select-String -Path $log -Pattern "phase_event op=poll.*event=responseBodyEnd"
"--- callEnd (success) or callFailed (still failing) ---"; Select-String -Path $log -Pattern "phase_event op=poll.*event=(callEnd|callFailed)" | Select-Object -First 5
"=== Recovery proof ==="; Select-String -Path $log -Pattern "REST_TRACE breaker_closed"
Select-String -Path $log -Pattern "REST_TRACE cursor_advanced seq="
```

**Discriminator decision tree (C2):**

| C1 result | C2 result | Verdict |
|---|---|---|
| C1 failed mid-body (body_chunk grew then stalled, no body_eof) | C2 body completes (`callEnd` + `responseBodyEnd byteCount=<small>`) | **H1 confirmed.** Tele2 LTE drops response body bytes past a threshold; the padded 4608-byte body crosses it, a small short-poll body does not. Fix scope: reduce production body shape or address transport via Reality/Tor. |
| C1 failed mid-body | C2 ALSO fails mid-body | Byte-budget threshold is below even the unpadded short-poll body. H1 holds but the threshold is lower than scope-doc estimates suggest. Investigation expands to wire/Caddy/relay byte-counts. |
| C1 failed before responseBodyStart (no body bytes at all) | C2 same | H1 unlikely; H3 (server defers body emission) or H4 (upstream TLS/Caddy buffer condition) more likely. Next step: relay-side timing log analysis. |
| C1 succeeded (already passed in C1) | C2 succeeded | Both APKs deliver under kill-switch + WS-off; no body-shape sensitivity observed. Likely the test setup did not provoke Mode-2 in this radio window. Re-run in a known cutoff site. |

**Privacy backstop check (C2 only):** if the operator accidentally launched APK-B in Private or Ghost mode, the `poll_call` log lines will STILL show `X-Phantom-Long-Poll=1 X-Phantom-Padded-Poll=1` (strip never fired). The run is then equivalent to C1 and the discriminator decision tree's first column is the only one to consult. Re-run in Standard.

## Capture summary template for PR comment / council follow-up

Once C0/C1/C2 complete:

```
Round 12 field re-test — branch HEAD c2a77d17 — <date>

APK-A SHA-256: e47d9f7a45ba9ef06637234ac7d1648a94a9f0ca1b5749975eb506c538df5881
APK-B SHA-256: 7985e40e0b6ec469a05a232b3a345214b32c0ec0ae03678f2311e201f4459357
Device: TECNO BF7-12 (serial 103603734A004351)
Carrier: Tele2 LTE
Field site: Иркутская (or alternate; specify)

C0 verdict: PASS | FAIL
C0 evidence (quote lines verbatim):
  hold_secs=30 from a poll_call line
  one seq_mac_verified line
  one cursor_advanced line

C1 verdict: REPRO | NO-REPRO
C1 phase-chain shape:
  responseHeadersEnd status=200 ...
  responseBodyStart ... (or "absent")
  body_chunk cumulative_bytes=<final value> ... (or "no chunks")
  responseBodyEnd ... (or "absent")
  callFailed totalMs=...

C2 verdict: STRIP-ACTIVE | STRIP-INACTIVE
C2 phase-chain shape:
  (same fields as C1 with the small-body shape)

Hypothesis ranking after this matrix:
  H1: <supported | refuted | inconclusive> — <one-line reason from the decision tree>
  H2: <supported | refuted | inconclusive>
  H3: <supported | refuted | inconclusive>
  H4: <supported | refuted | inconclusive>

Round 12 next action: <what does this matrix justify? a specific code fix, more diagnostic, or escalation to a transport-track decision?>
```

The matrix can complete in roughly 60 minutes wall-clock total once VPS access is settled. Do not stretch any single condition past 10 minutes; the discriminator value is in the per-condition phase-chain shape, not in long sustained pressure.
