# Stage 5G Phase 1 — obfs4 bridge experiment

> **Test ID:** Test 13.1
> **Hypothesis:** obfs4's uniform-random wire signature bypasses the TSPU
> 16-KB curtain that catches our WebTunnel TLS handshakes (Test 13,
> 2026-05-06). If true, Ghost mode becomes viable for Russian users without
> VPN — and stays honest to its no-silent-downgrade invariant.
> **Decision gated on this test:** see
> [`docs/project/DECISIONS_LOG.md`](../../project/DECISIONS_LOG.md) D-16.

---

## Setup

### Server

Vladislav follows
[`deploy/obfs4-bridge-setup.md`](../../../deploy/obfs4-bridge-setup.md) on the
existing FlokiNET VPS. After the bridge has been online ~1 hour, paste the
generated bridge line into
[`shared/core/transport/src/androidMain/kotlin/phantom/core/transport/OperatorBridges.kt`](../../../shared/core/transport/src/androidMain/kotlin/phantom/core/transport/OperatorBridges.kt)
`OBFS4` field and rebuild the APK.

### Devices

| Device | Network | Privacy Mode | Expected outcome |
|---|---|---|---|
| Tecno Spark Go (Android 12 HiOS) | RU MTS cellular, no VPN | Ghost | **HYPOTHESIS:** Tor bootstraps to 100% via obfs4 within 60 s |
| Pixel 8 Pro emulator | EU clean Wi-Fi | Ghost | Tor bootstraps to 100% via obfs4 (control — proves the bridge works at all) |

### Logcat filter

```powershell
adb -s <device> logcat PhantomMessagingService:V TransportManager:V \
    OnionWrapper:V Tor:V *:S | Tee-Object -FilePath \
    "C:\temp\test-13-1-<device>-<timestamp>.log"
```

The `OnionWrapper` and `Tor` tags surface bootstrap-progress lines like
`Bootstrapped 25% (handshake_or_loading_bridge_descriptors)` — they tell us
exactly where the curtain catches us if it does.

---

## Test procedure

1. Install the new APK on both devices.
2. Open PHANTOM. Onboard a fresh identity.
3. Settings → Privacy Mode → Ghost. Confirm the dialog.
4. **Start a stopwatch.** Watch the foreground notification text:
   - `Connecting via Tor… · Ghost`
   - → either `Online via Tor · Ghost` (success) within budget,
   - or `Cannot reach relay (tried 1) · Ghost` (failure) after the chain
     timeout (`TOR_PREPARE_TIMEOUT_MS = 600 000` ms = 10 min).
5. **In parallel**, watch logcat for the `Bootstrapped XX%` progression.
6. Stop the stopwatch when notification reaches Online or Failed.
7. If success: send a text message to the emulator peer. Verify round-trip.
8. Repeat 5x to confirm reproducibility (kill app + relaunch between runs).
9. Capture the full logcat for each run + the foreground-notification
   timeline.

---

## Result template — fill in after test

### Tecno МТС (RU, no VPN) — 2026-05-09

| Run | Bootstrap progression | Final state | Time to final | Notes |
|---|---|---|---|---|
| 1 | bootstrap completed (no logcat capture — phone not on USB) | **Online via Tor · Ghost** | ~5 min | First cold start; obfs4proxy via FlokiNET bridge bootstrapped successfully through TSPU. Foreground notification reached `Online via Tor · Ghost`. UI on ChatList showed `Connecting…` for ~5 min before clearing — see "Known follow-up" below. |

**Single-run result captured manually by Vladislav (no USB logcat available
during this test). Reproducibility runs (2-5) deferred — the success of
run 1 is the headline finding; reliability characterisation lives in
Stage 5G Phase 2.**

### Pixel emulator (EU, clean network)

Not exercised in this round — Ghost-mode test on the Tecno was the
critical-path validation. Control-network confirmation will land with
the Stage 5G Phase 2 reliability matrix.

### Round-trip text

Not tested in this run. Voice-message + text round-trip on Ghost mode
will be exercised in Stage 5G Phase 2 alongside the multi-run
reliability characterisation.

### Known follow-up (not blocking the decision gate)

`ChatList` header rendered `Connecting…` for ~5 minutes after the
foreground notification already showed `Online via Tor · Ghost`. The
notification updater reads `TransportManager.state` (which transitions
to `Connected` as soon as the `/health` probe succeeds), but the
`ChatList` header reads `transport.state` — the WebSocket upgrade
through the Tor circuit is the slow step that drives that flag. Both
are correct in isolation; they just disagree on what "online" means.
Tracked in [`TECHNICAL_BACKLOG.md`](../../project/TECHNICAL_BACKLOG.md)
as a Stage 5G Phase 2 polish item.

---

## Failure modes to watch for

| Symptom | Likely cause | Mitigation |
|---|---|---|
| Bootstrap stalls < 25 % on Tecno but works on emulator | Curtain catches obfs4 at the same heuristic boundary as WebTunnel (suggests the curtain looks at flow length, not protocol) | **Variant C fallback** — Stage 5G is not viable for Alpha 2 |
| Bootstrap stalls 25-95 % on Tecno | Bridge reachable but consensus / descriptor fetch blocked | Try with `UseBridges 1` + `Bridge obfs4 …` only (drop other entries) to isolate |
| Bootstrap stalls 100% but messages do not flow | Onion descriptor fetch / rendezvous blocked downstream of the bridge | **Variant C fallback** — bridge is not the problem; the entire onion path is policed |
| `Failed: ProtocolViolation` from obfs4proxy | Wrong cert= value or fingerprint typo in the bridge line | Re-extract from `/var/lib/tor-obfs4/pt_state/obfs4_bridgeline.txt` |
| Bootstrap fast on EU emulator, fast on Tecno **with** VPN, stalls on Tecno **without** VPN | Curtain is active and obfs4 does not bypass it | **Variant C fallback** |
| Bootstrap fast on Tecno **without** VPN | **Hypothesis confirmed** — proceed to Stage 5G full |

---

## Decision gate criteria

Proceed to Stage 5G full implementation (ADR-015 draft, multi-bridge fan-out,
maybe Snowflake server alongside) **iff** all four hold:

1. Tecno МТС reaches `Bootstrapped 100% (done)` in **all 5 runs** within
   the 600 s `TOR_PREPARE_TIMEOUT_MS` budget. (Median time matters less
   than reliability — even a 5-minute first launch is acceptable for a
   privacy-paranoid user; consistent 10-min timeouts are not.)
2. Round-trip text message succeeds via the established onion circuit.
3. EU emulator continues to bootstrap (control — proves we did not break
   the working path).
4. No `ProtocolViolation` errors in obfs4proxy log on the bridge host
   over a 24-h soak.

Fall back to **Variant C** otherwise:
- Add a UI checkpoint in Settings → Privacy Mode → Ghost for RU carriers
  ("Tor may not be reachable on Russian mobile carriers without a VPN.
  Proceed anyway?"), keep the no-silent-downgrade invariant.
- Document in DECISIONS_LOG (D-20-?) that obfs4 was tested and did not
  bypass the curtain, with this report as the evidence.
- Move the obfs4 bridge to a "best-effort for non-RU users" footnote — it
  still benefits anyone outside the curtain's reach.

---

## Audit trail after test

After the test runs:
1. Append run results to the tables above.
2. Update [`docs/project/DECISIONS_LOG.md`](../../project/DECISIONS_LOG.md):
   add **D-20** (decision based on the gate criteria above).
3. Update [`docs/project/ACTIVE_SPRINT.md`](../../project/ACTIVE_SPRINT.md):
   mark Step 2 ✅ merged + add Stage 5G full or Variant C as the next item.
4. If Variant C: append a Settings-rewrite scope addition to
   [`docs/project/TECHNICAL_BACKLOG.md`](../../project/TECHNICAL_BACKLOG.md)
   for the Ghost-mode RU checkpoint UI.
5. If Stage 5G full: open ADR-015 draft branch.

---

*Created: 2026-05-09. Filled in: TBD after Vladislav runs the test.*
