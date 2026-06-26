# REST-SEND-CONNECTIVITY-RECON1 — I-2 host synthetic probe design

**Instrument:** I-2 — Synthetic control HTTPS / TCP probe on the operator workstation host, co-incident with an emulator-side symptom reproduction window.
**Parent track:** `docs/tracks/rest-send-connectivity-recon1.md` (mini-lock).
**Predecessor:** `docs/tracks/rest-send-connectivity-recon1-i1-timeline.md` (I-1 deliverable).
**Type:** Design document for an operator-executed diagnostic. NO code change, NO fix scope-lock. RC PR #330 stays Draft / HOLD throughout.

---

## §1 Goal

Discriminate H-B (emulator NAT / virtual-network artifact) from H-C (relay-side accept-loop or kernel-backlog reject targeting the emulator's egress IP range). I-1 ruled out H-A and supported H-B as the leading candidate, but the corpus alone cannot tell whether the failure is internal to the emulator's NAT path or is a relay-side filter that would also reject any client coming from the same egress IP range. I-2 closes that gap with a host-side probe taken at the same wall-clock minute as the emulator failure burst.

If the host probe SUCCEEDS while the emulator FAILS, the failure is emulator-side (Gate 2 → H-B confirmed). If the host probe FAILS with the same TCP-connect-timeout signature, the failure is wider than the emulator (H-C reopens for I-3 / I-6 follow-up).

The instrument is read-only, low-rate, and does not require any change to the relay or the client. The operator runs it from the same workstation that hosts the Android emulator; both clocks are the host clock, so cross-correlation is exact without an anchor approximation.

## §2 Probe shape

A host-side `curl` loop that issues one read-only HTTPS request per second to `relay.phntm.pro` and records, per attempt:

- the start wall-clock timestamp (ISO 8601 with millisecond precision, host clock),
- the connect outcome (success / `Connection timed out` / `Could not resolve host` / other),
- if connected, the TCP `time_connect`, TLS `time_appconnect`, and total `time_total`,
- the HTTP status code returned (whatever the relay answers — even a 404 is fine; only TCP/TLS layer is the discriminator),
- end wall-clock timestamp.

The probe does NOT need authentication, does NOT send anything stateful, and does NOT POST. The endpoint can be any path the relay serves over HTTPS (a 404 against a non-existent path is fine — the discriminator is TCP+TLS reachability, not HTTP business logic). Using a stable, low-cost endpoint is preferred to keep the probe footprint negligible.

Approximate `curl` invocation (PowerShell-friendly, one-shot — wrap in a loop per §3):

```text
curl.exe --connect-timeout 5 --max-time 10 -s -o NUL `
  -w "ts=$now connect=%{time_connect} tls=%{time_appconnect} total=%{time_total} http=%{http_code} ec=%{exitcode}\n" `
  https://relay.phntm.pro/relay/poll
```

The `connect-timeout=5` mirrors the emulator-side OkHttp connect timeout observed in the I-1 corpus (each `connectFailed SocketTimeout` fired after ~5000 ms), so the host probe's failure shape is comparable to the emulator's. The `max-time=10` bounds the overall single-probe duration.

`http_code` is captured but is NOT used as a PASS/FAIL discriminator. The relay endpoint hit is the operator's choice; what matters is whether the **TCP connect** to `65.108.154.152:443` completes. A 401 / 404 / 405 from the relay after a successful TCP+TLS handshake is treated as PROBE SUCCESS for I-2's purposes. Only a non-zero `curl` exit code (`exitcode=7` for connect, `exitcode=28` for timeout) is PROBE FAIL.

## §3 Operator procedure (single reproduction attempt)

Three concurrent PowerShell windows on the operator workstation. The procedure is bounded to one reproduction window of up to 15 minutes; if the symptom does not appear in 15 minutes, the operator stops and either retries or invokes the P-1 park condition per §6.

### Tab A — host-side probe loop (writes machine-readable log)

```powershell
$out = "C:\temp\smoke-pr333-baseline-i2\host-probe.tsv"
New-Item -ItemType Directory -Force "C:\temp\smoke-pr333-baseline-i2" | Out-Null
"ts`tconnect_s`ttls_s`ttotal_s`thttp_code`texitcode" | Out-File -FilePath $out -Encoding utf8

while ($true) {
    $now = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
    $line = curl.exe --connect-timeout 5 --max-time 10 -s -o NUL `
        -w "$now`t%{time_connect}`t%{time_appconnect}`t%{time_total}`t%{http_code}`t%{exitcode}" `
        https://relay.phntm.pro/relay/poll 2>$null
    if (-not $line) { $line = "$now`t-`t-`t-`t-`tNORESPONSE" }
    Add-Content -Path $out -Value $line
    Start-Sleep -Seconds 1
}
```

Probe rate is 1/second to give dense temporal coverage of any failure burst (the 2026-06-26 emulator burst lasted ~75 seconds; a 1/s probe gives ~75 samples across that window).

`Ctrl+C` to stop after the reproduction completes or the 15-minute cap is hit.

### Tab B — Tecno logcat capture (control)

```powershell
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$TECNO = "103603734A004351"
& $ADB -s $TECNO logcat -c
& $ADB -s $TECNO logcat -v time *:I | Tee-Object -FilePath "C:\temp\smoke-pr333-baseline-i2\tecno.log"
```

Tecno is the I-1 physical-device control. Running its logcat in parallel gives a second concurrent baseline alongside the host probe.

### Tab C — emu logcat capture + the reproduction trigger

```powershell
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$EMU = "emulator-5554"  # adjust if different
& $ADB -s $EMU logcat -c
& $ADB -s $EMU logcat -v time *:I | Tee-Object -FilePath "C:\temp\smoke-pr333-baseline-i2\emu.log"
```

While Tab C is running, the operator interacts with the Phantom app on the emulator to drive a reproduction. The 2026-06-26 symptom appeared spontaneously a few minutes into a fresh-install Wi-Fi smoke; an effective trigger pattern (subject to change as evidence accrues) is:

1. Open Phantom on emu, complete onboarding to a fresh identity if not already.
2. If the emu has no contact, add Tecno via QR code and exchange one message in each direction (this reproduces the I-1 starting conditions).
3. Send three more text messages from emu → Tecno at ~10-second intervals.
4. Let the session sit idle but with the app foregrounded for the remainder of the 15-minute window.

The symptom is "REST send / poll / ws_auth connectFailed burst from `10.0.2.16` to `65.108.154.152:443`" appearing in `emu.log`. The operator does NOT need to predict when it starts — Tab A runs continuously throughout, capturing both quiet and burst windows.

## §4 Evidence to capture

On completion of the 15-minute window:

| # | Artefact | Purpose |
|---|---|---|
| 1 | `C:\temp\smoke-pr333-baseline-i2\host-probe.tsv` (TSV log from Tab A) | Probe outcome per second across the window; the discriminator. |
| 2 | `C:\temp\smoke-pr333-baseline-i2\tecno.log` (logcat from Tab B) | Concurrent physical-device control; expected to show no `connectFailed`. |
| 3 | `C:\temp\smoke-pr333-baseline-i2\emu.log` (logcat from Tab C) | The emulator's record of REST send / poll / ws_auth events, including any `connectFailed` burst. |
| 4 | UI verdict (text note) | Whether the operator observed the user-facing symptom (a stuck `Sending…` bubble or similar) and at what wall-clock time. |

All four artefacts share the operator-workstation host clock; no anchor approximation is required.

## §5 Analysis discipline

Cross-correlation between the three logs and the probe TSV is by host UTC timestamp. The TSV uses ISO 8601 UTC explicitly; `logcat -v time` records the device-local time but both devices' timestamps reduce to host clock at write-time (Tee-Object on the host). The recon-progress comment that reports the verdict produces a single timeline with all four sources interleaved at the second granularity around the failure window (if observed).

The verdict closes on the answer to one question, evaluated within a `± 10 second` window around each `connectFailed` event in `emu.log`:

- During the `± 10 s` window around any single emu `connectFailed`, did the host probe SUCCEED at least once?

If yes for every emu-side burst observed → **PASS / Gate 2 / H-B confirmed.**
If no for any emu-side burst observed (i.e. the host probe also failed in the same window) → **FAIL / H-C reopens.**
If no emu-side `connectFailed` burst is observed in the 15-minute window after ≥ 3 successive reproduction attempts → **PARK / P-1 triggers.**

## §6 PASS / FAIL / Park criteria

| Outcome | Condition (after the 15-minute reproduction window) | Disposition |
|---|---|---|
| **PASS (Gate 2 — H-B confirmed)** | `emu.log` contains at least one `REST_TRACE phase_event ... event=connectFailed exception=SocketTimeoutException` to `65.108.154.152:443` from `10.0.2.16`, AND the host probe TSV shows at least one SUCCESS row (`exitcode=0` AND HTTP code present) within `± 10 s` of each such emu event. Tecno `tecno.log` shows no `connectFailed` in the same window. | Recon closes. The next deliverable is a docs PR appending a §10 verdict block to `rest-send-connectivity-recon1.md` and updating `PROJECT_LOG.md` + `MASTER_TIMELINE_2026.md` to record Gate 2 closure. No code change. |
| **FAIL (H-C reopens)** | `emu.log` shows the burst AND the host probe TSV shows a matching FAIL (`exitcode=7` or `exitcode=28`) within `± 10 s` of each emu event. | Recon escalates. I-3 (host-side pcap) and I-6 (relay-side accept log) become the next instruments per the parent mini-lock §7. No code change. |
| **PARK (P-1 triggers)** | Three consecutive 15-minute reproduction attempts on different sessions (separated by at least 60 minutes each) all complete without a single emu-side `connectFailed` event. | Track parks for ≥ 7 days per the parent mini-lock §7 P-1. The 2026-06-26 evidence stays on file as a single-shot event. No code change. |
| **INCONCLUSIVE** | `emu.log` shows the burst but the host probe TSV has mixed outcomes (some SUCCESS, some FAIL, no clear majority within `± 10 s`). | Recon does NOT close. The next deliverable is a second I-2 reproduction with a more aggressive trigger pattern OR direct escalation to I-3. No code change. |

## §7 Operator-safety constraints

- The probe is read-only. No POST, no auth token, no app state mutation, no relay state mutation.
- 1 request / second over a 15-minute window = 900 requests. The relay's existing rate limits are far above this (60 / min per requester for `/prekeys/status`, comparable for poll). The probe shares an egress IP with normal client traffic but is well within the order-of-magnitude budget.
- The probe targets the public production relay endpoint, not a privileged surface. Treating it like any other client request.
- No automatic re-runs. Each 15-minute window is an explicit operator decision. The track does NOT schedule a recurring probe.
- The probe writes to the operator workstation's local `C:\temp\...` directory. Logs do NOT leave the operator workstation. Repo-bound artefacts contain only synthesised verdicts and (if necessary) excerpts with personally-identifying network identifiers redacted.

## §8 Out of scope

Restating the parent mini-lock §3 constraints, applied to this design:

- **No code change in this PR or in the I-2 execution PR.** Specifically, no proposal to switch the emulator from QEMU user-mode NAT to bridge-mode networking, no proposal to extend the OkHttp connect timeout, no proposal to add a retry-after-connectFailed-burst loop. Each is a fix shape and is explicitly out of scope until a recon verdict closes Gate 2 (or another gate).
- **No work on RC PR #330.** That PR stays Draft / HOLD.
- **No `PROJECT_LOG.md` or `MASTER_TIMELINE_2026.md` update in this PR.** Those updates follow the I-2 execution verdict, not the design.
- **No promotion of the probe to a CI step or a background daemon.** The probe is operator-triggered and bounded; if recurring monitoring is needed later, that is a separate decision and a separate scope-lock.

## §9 Next-session pickup

If the recon picks up before I-2 execution: re-read this file's §3 (operator procedure) and §6 (PASS / FAIL / Park criteria). The execution itself does not need new design choices; only operator wall-clock and patience.

If the recon picks up after I-2 execution: read the §6 outcome and prepare the verdict PR per the matching disposition row. Do NOT propose a fix shape in the verdict PR; verdict + journal update + next-instrument pointer only.
