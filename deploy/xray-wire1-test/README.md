# RC-LIBXRAY-REALITY-WIRE1 (Trek 1) Variant 2 — diagnostic Reality inbound

> **Status: DIAGNOSTIC-ONLY. Brought up explicitly per test run. Torn down
> immediately after the field run. Production `:8443` is NEVER modified.**

This directory provides the configuration for a temporary second Xray
container (`phantom-xray-wire1`) bound to host port `:8444`. The container
serves a Reality inbound with `clients[].flow = ""` (plain VLESS without
XTLS-Vision), used as the splice-handoff-race discriminator in the
RC-LIBXRAY-REALITY-WIRE1 (Trek 1) Variant 2 field test.

The reasoning:

- Trek 1 Variant 1 (`baseline`) reproduced the Arm G v10/v11 single-segment
  Reality ClientHello stall in 50/50 fresh-process sessions on the
  standalone `apps/android-libxray-test/` APK on 2026-06-09.
- The leading hypothesis per claude.md is the XTLS-Vision splice handoff
  race documented in Xray-core issue #4878 / PR #5737.
- Variant 2 removes the Vision flow on the client AND the server. If the
  stall disappears, splice race is materially confirmed.

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
# diagnostic delta (clients[].flow="").
python3 -c "
import json
with open('xray-wire1-test/config.json') as f:
    c = json.load(f)
flow = c['inbounds'][0]['settings']['clients'][0]['flow']
print(f'flow = {flow!r}  (must be empty string for Variant 2)')
assert flow == '', 'Variant 2 config error: clients[].flow is NOT empty'
print('OK')
"
```

### Bring up the diagnostic container

```bash
# Use compose file ORDERING so the overlay extends production. The
# production phantom-xray container must already be running (it normally
# is); the overlay only adds a SECOND xray-wire1 service.
docker compose \
  -f docker-compose.yml \
  -f docker-compose.wire1-test.yml \
  up -d xray-wire1

# Verify the container started cleanly and is healthy.
docker ps --filter "name=phantom-xray-wire1"
docker logs --tail 30 phantom-xray-wire1

# Confirm port :8444 is listening on the host:
ss -tlnp | grep ':8444'
```

Expected `docker logs`: a single `[Warning] core: Xray <version> started`
line and `[Info] transport/internet/tcp: listening TCP on 0.0.0.0:8443`
(from inside the container — host sees this as `:8444`).

### Verify production untouched

```bash
docker exec phantom-xray cat /etc/xray/config.json | sha256sum \
  > /tmp/wire1-prod-xray-sha-post.txt
diff /tmp/wire1-prod-xray-sha-pre.txt /tmp/wire1-prod-xray-sha-post.txt \
  && echo 'PROD :8443 CONFIG UNCHANGED — Hard Gate 3 OK' \
  || echo 'PROD :8443 CONFIG CHANGED — STOP, INVESTIGATE'
```

### Capture window (host)

In a separate SSH window, start the tcpdump capture on host `:8444`:

```bash
sudo rm -f /tmp/wire1-v2-tecno-8444.pcap
sudo tcpdump -ni any "tcp port 8444" -ttt -v -s 2000 \
  -w /tmp/wire1-v2-tecno-8444.pcap
```

(No host filter — keeps the syntax simple and the post-decode Python
parser filters by Tecno egress IP, mirroring the Variant 1 baseline
capture pattern.)

### Run the Android test

Build + install the Variant 2 APK (the same APK; the variant id is
passed via intent extra):

```bash
# On the development machine (Windows / macOS):
./gradlew :apps:android-libxray-test:assembleDebug

adb -s <TECNO_SERIAL> install -r \
  apps/android-libxray-test/build/outputs/apk/debug/android-libxray-test-debug.apk

adb -s <TECNO_SERIAL> shell am force-stop phantom.libxray.test
adb -s <TECNO_SERIAL> logcat -c
adb -s <TECNO_SERIAL> shell am start \
  -n phantom.libxray.test/.MainActivity \
  --es variant drop-vision \
  --ei iterations 50
```

Wait ~15 minutes for the run to complete. Poll progress from the dev
machine:

```bash
adb -s <TECNO_SERIAL> logcat -d -v threadtime LIBXRAY_WIRE1:V *:S \
  | tail -5
```

The terminal line to wait for:

```
=== RUN DONE variant=drop-vision pass=N fail=M total=50 ===
```

### Teardown (on the VPS)

```bash
# Stop tcpdump (Ctrl-C in its window). Then:
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

Two files are needed for the post-run analysis:

```bash
# pcap from the VPS:
scp phantom@relay.phntm.pro:/tmp/wire1-v2-tecno-8444.pcap C:\temp\

# logcat from the device (via the dev machine):
adb -s <TECNO_SERIAL> logcat -d -v threadtime LIBXRAY_WIRE1:V GoLog:V PhantomXray:V *:S \
  | Out-File "C:\temp\wire1-v2-tecno-logcat.log" -Encoding utf8
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

### Verdict matrix

| Variant 2 result | Verdict |
|---|---|
| `/health` 50/50 PASS + multi-segment ClientHello on wire | Splice-race hypothesis materially confirmed. Production fix path: drop XTLS-Vision on the embedded Android client OR fork libXray to force `readV` (non-splice) branch on Android. |
| `/health` 0/50 PASS + same single-segment stall as Variant 1 | Vision is NOT sufficient — the bug is deeper in libXray's gomobile-bound write path. Vision-removal does not help. Proceed to Variants 3 (`xhttp`) / 4 (`httpupgrade`) to test whether HTTP-framed transports bypass the stall. |
| Partial PASS (e.g. 30/50) | Non-deterministic / scheduling-race signature (consistent with v9 s=1 once-PASS pattern). Vision involvement likely but not exclusive. Proceed to Variants 3-4 anyway. |
| `/health` FAIL with a DIFFERENT failure mode | Investigate the new failure mode before pivoting. Auth-mismatch on the server side is a common false alarm — check the server logs and reconfirm the rendered config matches production REALITY keys. |

## Cross-references

- Trek 1 mini-lock memory: `project_trek1_rc_libxray_reality_wire1_minilock_2026_06_09.md`
- Parent two-track plan: `project_arm_g_subtracks_locked_2026_06_09.md`
- Variant 1 baseline result (2026-06-09): `project_arm_g_v10v11_libxray_android_write_stall_2026_06_08.md` + standalone APK pcap analysis in chat history
- Architectural analysis behind the splice-race hypothesis: `claude.md`
- Arm A.2 pattern reference: `deploy/docker-compose.armA2.yml`,
  `deploy/stunnel.armA2.conf`,
  `docs/tracks/rc-direct-stability1.md` §4 Arm A.2 PR-8a operator runbook
