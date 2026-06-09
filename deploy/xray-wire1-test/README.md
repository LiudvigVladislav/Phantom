# RC-LIBXRAY-REALITY-WIRE1 (Trek 1) Variants 2-3 — diagnostic Reality inbounds

> **Status: DIAGNOSTIC-ONLY. Brought up explicitly per test run. Torn down
> immediately after the field run. Production `:8443` is NEVER modified.**

This directory provides the configuration for a temporary second Xray
container (`phantom-xray-wire1`) exposing TWO diagnostic Reality inbounds:

| Variant | Host port | Container port | `flow` | `network` | `xhttp.path` |
|---|---|---|---|---|---|
| V2 `drop-vision`        | `:8444` | `:8443` | `""` | `tcp`   | — |
| V3 `drop-vision-xhttp`  | `:8445` | `:8445` | `""` | `xhttp` | `/wire1-xhttp-test` |

Both inbounds use the SAME REALITY keypair as production (rendered from
`deploy/xray/.env`), differing from production ONLY on the per-variant
deltas above.

The reasoning:

- Trek 1 Variant 1 (`baseline`) reproduced the Arm G v10/v11 single-segment
  Reality ClientHello stall in 50/50 fresh-process sessions on the
  standalone `apps/android-libxray-test/` APK on 2026-06-09.
- The first hypothesis per claude.md was the XTLS-Vision splice handoff
  race (Xray-core issue #4878 / PR #5737). Variant 2 removed the Vision
  flow on the client AND the server to test it.
- Variant 2 (2026-06-09) FAILED 50/50 with byte-identical wire signature
  to V1 baseline. Vision is NOT sufficient on its own — the bug is deeper
  in libXray's gomobile-bound write path.
- Variant 3 keeps `flow=""` AND swaps the stream transport from raw TCP
  to `xhttp` (HTTP-framed stream). If V3 PASSES, the multi-segment raw-
  TCP write completion path itself is the bug class and the production
  fix path is to switch Reality stream transport to xhttp. If V3 FAILS
  with the same one-segment shape, the bug is upstream of the stream
  transport layer entirely.

> **Variant 4 (`httpupgrade`) is intentionally NOT included.** Per the
> official Xray transport documentation, `realitySettings` is valid with
> `raw` / `xhttp` / `grpc` but NOT `httpupgrade`. Adding it blindly as a
> Reality variant would produce an ambiguous FAIL (we could not tell
> whether the stall remained because the write hypothesis still holds OR
> because the Xray runtime rejected the config combination silently). V4
> is reserved for a future commit ONLY after a config-validation step
> (`xray run -test`) confirms the running Xray version accepts the
> combination, OR it is reframed as a non-Reality control
> (`security=tls` with httpupgrade).

## Operator runbook (Arm A.2 pattern — open / verify / capture / teardown)

### Pre-flight (on the VPS, as `phantom` user)

1. Pull this branch:

   ```bash
   cd /home/phantom/Phantom
   git fetch origin
   git checkout feat/pr-trek1-libxray-wire1
   git pull
   ```

2. Capture the production `phantom-xray` config hash so we can verify it
   is untouched post-test (mini-lock Hard Gate 3):

   ```bash
   docker exec phantom-xray cat /etc/xray/config.json | sha256sum \
     > /tmp/wire1-prod-xray-sha-pre.txt
   ```

3. Confirm the diagnostic image digest for later reproducibility:

   ```bash
   docker pull ghcr.io/xtls/xray-core:latest
   docker image inspect ghcr.io/xtls/xray-core:latest \
     --format '{{ index .RepoDigests 0 }}' \
     > /tmp/wire1-image-digest.txt
   cat /tmp/wire1-image-digest.txt
   ```

### Render the diagnostic config

The template at `config.json.template` carries the same `REPLACE_*`
markers as production. Render it using the SAME `.env` file as production
so the REALITY keypair is byte-identical and a Variant 2 FAIL cannot be
attributed to a key mismatch.

```bash
cd /home/phantom/Phantom/deploy

# Reuse production .env (gitignored, lives on VPS only).
. ./xray/.env

# Render Variant 2 config from template into the diagnostic directory.
# (Same substitution pattern as `deploy/xray/render-config.sh`.)
sed \
  -e "s|REPLACE_PRIVATE_KEY|$XRAY_PRIVATE_KEY|" \
  -e "s|REPLACE_UUID|$XRAY_UUID|" \
  -e "s|REPLACE_SHORT_ID|$XRAY_SHORT_ID|" \
  xray-wire1-test/config.json.template \
  > xray-wire1-test/config.json

# Verify the rendered file is valid JSON and contains the expected
# diagnostic deltas on BOTH inbounds:
#   V2 inbound (port 8443, network=tcp,   flow="")
#   V3 inbound (port 8445, network=xhttp, flow="", xhttpSettings.path)
python3 -c "
import json
with open('xray-wire1-test/config.json') as f:
    c = json.load(f)
ins = {i['port']: i for i in c['inbounds']}
assert set(ins) == {8443, 8445}, f'unexpected inbound ports: {sorted(ins)}'

v2 = ins[8443]
assert v2['settings']['clients'][0]['flow'] == '', 'V2 clients[].flow not empty'
assert v2['streamSettings']['network'] == 'tcp',   'V2 network != tcp'
assert v2['streamSettings']['security'] == 'reality', 'V2 security != reality'

v3 = ins[8445]
assert v3['settings']['clients'][0]['flow'] == '', 'V3 clients[].flow not empty'
assert v3['streamSettings']['network'] == 'xhttp', 'V3 network != xhttp'
assert v3['streamSettings']['security'] == 'reality', 'V3 security != reality'
assert v3['streamSettings']['xhttpSettings']['path'] == '/wire1-xhttp-test', \
    'V3 xhttpSettings.path mismatch with Wire1Variants.WIRE1_XHTTP_PATH'
print('OK — V2 (port 8443 tcp) + V3 (port 8445 xhttp) deltas verified')
"
```

### Validate the Xray runtime accepts the config (V3 guardrail)

Per the Vladislav V3 guardrail: before exposing the diagnostic config to
field-test traffic, run `xray run -test` against the rendered config so
that any silent rejection by the Xray runtime (notably for the V3 xhttp
+ Reality combination) surfaces as an explicit error rather than as an
ambiguous wire-stall FAIL. A failure here means the running Xray version
does NOT accept `realitySettings` with `network=xhttp` on this build,
and the V3 field test must NOT proceed until either the image is bumped
or V3 is reframed.

```bash
# Re-use the same image the diagnostic container will run, so the test
# result reflects the runtime that will actually parse the config in
# production conditions. If this exits non-zero, STOP and investigate;
# DO NOT proceed to the field test.
docker run --rm \
  -v "$(pwd)/xray-wire1-test/config.json:/etc/xray/config.json:ro" \
  ghcr.io/xtls/xray-core:latest \
  run -test -c /etc/xray/config.json
```

Expected output: a `[Warning] core: Xray <version> started` line followed
by an immediate clean exit (exit code 0). Any `[Error]` line, panic, or
non-zero exit means the V3 inbound (or both inbounds together) is not
parseable by this Xray build — do NOT bring up the container or start
the field test until the cause is resolved.

### Bring up the diagnostic container

```bash
# Use compose file ORDERING so the overlay extends production. The
# production phantom-xray container must already be running (it normally
# is); the overlay only adds a SECOND xray-wire1 service. The same
# container exposes BOTH the V2 inbound (host :8444 → container :8443)
# and the V3 inbound (host :8445 → container :8445); choosing which is
# tested is the Android client's job (variant intent extra).
docker compose \
  -f docker-compose.yml \
  -f docker-compose.wire1-test.yml \
  up -d xray-wire1

# Verify the container started cleanly. Guardrail 2: confirm BOTH host
# ports are actually listening from OUTSIDE the container, not only
# inside. A successful container start without an externally-listening
# :8444 / :8445 indicates a Docker port-mapping problem and the
# Tecno SYN will return RST.
docker ps --filter "name=phantom-xray-wire1"
sudo ss -ltnp | grep -E ':8444|:8445'
docker logs --tail 80 phantom-xray-wire1
```

Expected `docker logs`: a single `[Warning] core: Xray <version> started`
line followed by TWO listener lines (one per inbound):

```
[Info] transport/internet/tcp: listening TCP on 0.0.0.0:8443
[Info] transport/internet/tcp: listening TCP on 0.0.0.0:8445
```

(seen from inside the container — host sees these as `:8444` and `:8445`).

Expected `sudo ss -ltnp | grep -E ':8444|:8445'`: TWO LISTEN lines, one
per host port, owned by `docker-proxy`. Empty output OR only one of the
two ports listening means a port-mapping bind failed on the host; do NOT
proceed to the field test until this is resolved.

### Verify production untouched

```bash
docker exec phantom-xray cat /etc/xray/config.json | sha256sum \
  > /tmp/wire1-prod-xray-sha-post.txt
diff /tmp/wire1-prod-xray-sha-pre.txt /tmp/wire1-prod-xray-sha-post.txt \
  && echo 'PROD :8443 CONFIG UNCHANGED — Hard Gate 3 OK' \
  || echo 'PROD :8443 CONFIG CHANGED — STOP, INVESTIGATE'
```

### Capture window (host)

In a separate SSH window — ideally a `tmux` session so the capture
survives an SSH drop — start the tcpdump capture on the per-variant
host port. Guardrail 1: use `-s 0` (no snaplen truncation) so a long
ClientHello or any continuation segments are captured in full. Snaplen
2000 would fit our worst-case observed first-segment + a bit more, but
`-s 0` removes the argument entirely from the result interpretation.

Guardrail (Vladislav V3): START THE CAPTURE BEFORE LAUNCHING THE APP and
keep it running until AFTER the `=== RUN DONE ===` line lands in logcat.
A partial capture (e.g. when an SSH disconnect drops tcpdump mid-run, as
happened on V2) collapses the verdict to whatever the 3-artifact-cross-
check can prove, and the wire-shape question stops being decidable.

```bash
# Start a tmux session for the capture so an SSH disconnect does NOT
# stop tcpdump (V2 lesson: a foreground SSH tcpdump dropped at ~59s
# and the pcap only covered 4 fresh flows of the 50-iteration run).
tmux new-session -d -s wire1-cap

# V2 capture (drop-vision):
tmux send-keys -t wire1-cap \
  'sudo rm -f /tmp/wire1-v2-tecno-8444.pcap && \
   sudo tcpdump -ni any "tcp port 8444" -w /tmp/wire1-v2-tecno-8444.pcap -s 0' Enter

# V3 capture (drop-vision-xhttp). Run INSTEAD of the V2 line above
# when running a V3 field session (the runbook tests one variant at a
# time, capture port matches the variant intent extra below):
tmux send-keys -t wire1-cap \
  'sudo rm -f /tmp/wire1-v3-tecno-8445.pcap && \
   sudo tcpdump -ni any "tcp port 8445" -w /tmp/wire1-v3-tecno-8445.pcap -s 0' Enter

# Confirm tcpdump is actually running (process visible) BEFORE launching
# the app:
tmux capture-pane -t wire1-cap -p | tail -5
pgrep -a tcpdump
```

(No host filter — keeps the syntax simple and the post-decode Python
parser filters by Tecno egress IP, mirroring the Variant 1 baseline
capture pattern.)

### Run the Android test

Build + install the test APK (the same APK serves all variants; the
variant id is passed via intent extra):

```bash
# On the development machine (Windows / macOS):
./gradlew :apps:android-libxray-test:assembleDebug

adb -s <TECNO_SERIAL> install -r \
  apps/android-libxray-test/build/outputs/apk/debug/android-libxray-test-debug.apk

adb -s <TECNO_SERIAL> shell am force-stop phantom.libxray.test
adb -s <TECNO_SERIAL> logcat -c

# Launch the desired variant. Pick ONE per field run.
# Variant 2 (drop-vision, raw TCP, host :8444):
adb -s <TECNO_SERIAL> shell am start \
  -n phantom.libxray.test/.MainActivity \
  --es variant drop-vision \
  --ei iterations 50

# Variant 3 (drop-vision-xhttp, xhttp transport, host :8445):
adb -s <TECNO_SERIAL> shell am start \
  -n phantom.libxray.test/.MainActivity \
  --es variant drop-vision-xhttp \
  --ei iterations 50
```

Wait ~15 minutes for the run to complete. Poll progress from the dev
machine:

```bash
adb -s <TECNO_SERIAL> logcat -d -v threadtime LIBXRAY_WIRE1:V *:S \
  | tail -5
```

The terminal line to wait for (variant name matches the intent extra
above):

```
=== RUN DONE variant=<variant> pass=N fail=M total=50 ===
```

The first lines of the logcat capture should also include the V3-guard
explicit diagnostic config line. Use it to verify the running app is
actually using the variant deltas expected (not silently falling back
to baseline because of an unwired intent extra):

```
variant_config variant=drop-vision-xhttp serverHost=65.108.154.152 \
  serverPort=8445 network=xhttp flow=<empty> security=reality \
  serverName=www.microsoft.com xhttpPath=/wire1-xhttp-test
```

If `network` / `flow` / `xhttpPath` here does NOT match the variant
intent extra, STOP — the on-device config is misrendered and the field
result would be uninterpretable.

### Teardown (on the VPS)

```bash
# Stop tcpdump cleanly via the tmux session (NOT a window kill — Ctrl-C
# lets tcpdump flush its pcap buffer before exit, otherwise the trailing
# bytes of the run are lost).
tmux send-keys -t wire1-cap C-c
sleep 1
tmux kill-session -t wire1-cap

# Tear down the diagnostic container. The compose service name is
# `xray-wire1` (container name is `phantom-xray-wire1` — both work for
# `docker compose down` but the service name is the canonical handle).
docker compose \
  -f docker-compose.yml \
  -f docker-compose.wire1-test.yml \
  down xray-wire1

# Verify the diagnostic container is gone:
docker ps -a --filter "name=phantom-xray-wire1"

# Optional: remove the rendered config to avoid drift across test runs.
rm xray-wire1-test/config.json

# Verify production :8443 still healthy:
docker ps --filter "name=phantom-xray"
curl -sv https://relay.phntm.pro/health --max-time 10 | head -5
```

### Send artifacts back

Three files are needed for the post-run analysis. Guardrail 3 adds the
diagnostic Xray container logs alongside the pcap and logcat — a 0/50
PASS or an auth-mismatch failure mode would be very hard to diagnose
without the server-side Xray's own view of each Tecno connection.

Use the per-variant filenames so V2 and V3 artefacts do not overwrite
each other on the analyst's machine.

```bash
# 1. pcap from the VPS (pick the file matching the variant just run):
# V2:
scp phantom@relay.phntm.pro:/tmp/wire1-v2-tecno-8444.pcap C:\temp\
# V3:
scp phantom@relay.phntm.pro:/tmp/wire1-v3-tecno-8445.pcap C:\temp\

# 2. logcat from the device (via the dev machine). PowerShell 5.1
# `Tee-Object` does NOT support -Encoding so we redirect via Out-File:
adb -s <TECNO_SERIAL> logcat -d -v threadtime LIBXRAY_WIRE1:V GoLog:V PhantomXray:V *:S \
  | Out-File "C:\temp\wire1-<variant>-tecno-logcat.log" -Encoding utf8
# (substitute <variant> = v2 or v3 to match)

# 3. Diagnostic container logs (capture BEFORE teardown). The same
# container serves both V2 and V3 inbounds, so logs cover whichever
# variant ran:
ssh phantom@relay.phntm.pro \
  "docker logs phantom-xray-wire1 > /tmp/wire1-<variant>-container-logs.txt 2>&1"
scp phantom@relay.phntm.pro:/tmp/wire1-<variant>-container-logs.txt C:\temp\
```

## Reading the result (acceptance criteria)

Per the Trek 1 mini-lock acceptance criteria for Variants 2-4 (Vladislav-
softened in `project_trek1_rc_libxray_reality_wire1_minilock_2026_06_09.md`):

- `/health` returns 200 reliably across ≥50 consecutive fresh-process
  sessions
- NO single-segment incomplete-TLS-record stall pattern (the pcap
  decoder script should NOT find the "TLS record header advertises
  1722-1818 bytes / only 1440 bytes transmitted / no continuation" shape
  that 50/50 Variant 1 baseline flows showed)
- Server-side wire shows complete handshake / request body

PSH flags on continuation segments are evidence, not a hard gate.

### Variant 2 result (closed — 2026-06-09)

V2 ran on Tecno + Tele2 LTE 2026-06-09. Result: **FAIL 0/50** with
byte-identical wire signature to V1 baseline (single 1440-byte TCP
segment of outer Reality ClientHello, no continuation, server FIN
seq=1 after ~10-13 s). Vision-splice-race hypothesis REFUTED as the
sole cause. The closed verdict is preserved for historical reference;
the verdict matrix below applies to V3 going forward.

### Variant 3 verdict matrix

V3 keeps `flow=""` and swaps the stream transport from raw TCP to
xhttp. The discriminator question is whether the multi-segment raw-TCP
write completion path itself is the bug class (the only reasonable
hypothesis left standing after V1 + V2 had byte-identical FAIL shapes).

The same STRONG vs WEAK PASS interpretation guardrail applies as for V2:
a PASS with a multi-segment ClientHello (or, for xhttp specifically,
multi-segment xhttp POST body) on the wire is the strong evidence; a
PASS where the on-wire writes all fit inside one MSS is useful but does
NOT prove the multi-segment write path is the cause — a one-segment
xhttp framing might simply never trigger the bug in the first place.

| Variant 3 result | Strength | Verdict |
|---|---|---|
| `/health` 50/50 PASS AND **on-wire writes span multiple TCP segments AND handshake / xhttp body completes** | **STRONG PASS** | Multi-segment raw-TCP write completion is the bug class. Production fix path: switch Reality stream transport off raw TCP to `xhttp` (the variant that just passed). |
| `/health` 50/50 PASS but **on-wire writes all fit inside one MSS** | **WEAK PASS** | Useful as a workaround (production fix: switch to xhttp works), but does NOT decisively prove the multi-segment-write hypothesis — a one-segment framing might simply never trigger the bug. Recommend a confirmatory test that forces a multi-segment xhttp body (e.g. larger first request) before locking the fix. |
| `/health` 0/50 PASS + same single-segment stall as V1 / V2 | FAIL — same shape | The bug is upstream of the stream transport layer entirely (likely in libXray's gomobile bridge below `streamSettings.network`). Vision-removal and transport-swap both insufficient. Pivot to Trek 2 (`SHORT-CYCLE-LONGPOLL1`) as the architecturally-locked fix path; libXray write path needs source-level investigation outside the field-test matrix. |
| Partial PASS (e.g. 30/50) | RACE | Non-deterministic / scheduling-race signature. Multi-segment-write involvement likely but not exclusive. Capture the failing-iteration wire shapes and compare against the V1 / V2 single-segment FAIL signature before drawing a verdict. |
| `/health` FAIL with a DIFFERENT failure mode (e.g. 404 / "xhttp not supported" / unexpected RST timing) | ANOMALY | Investigate the new failure mode before pivoting. Most likely causes: (a) `Wire1Variants.WIRE1_XHTTP_PATH` mismatched with the rendered template's `xhttpSettings.path`, (b) the running Xray runtime silently rejected the V3 inbound at config-load (the `xray run -test` validation step above should have caught this), (c) auth-mismatch on the V3 inbound's clients[0].id (re-check the rendered UUID matches `OperatorXrayConfig.kt`). Check `docker logs phantom-xray-wire1` for the server-side view. |

## Cross-references

- Trek 1 mini-lock memory: `project_trek1_rc_libxray_reality_wire1_minilock_2026_06_09.md`
- Parent two-track plan: `project_arm_g_subtracks_locked_2026_06_09.md`
- Variant 1 baseline result (2026-06-09): `project_arm_g_v10v11_libxray_android_write_stall_2026_06_08.md` + standalone APK pcap analysis in chat history
- Architectural analysis behind the splice-race hypothesis: `claude.md`
- Trek 2 (architectural escape, runs in parallel): `SHORT-CYCLE-LONGPOLL1`
  — load-bearing if V3 closes FAIL with the same one-segment shape
- Arm A.2 pattern reference: `deploy/docker-compose.armA2.yml`,
  `deploy/stunnel.armA2.conf`,
  `docs/tracks/rc-direct-stability1.md` §4 Arm A.2 PR-8a operator runbook
