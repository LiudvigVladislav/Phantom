<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (c) 2026 Willen LLC
-->

# Bridge2 (FlokiNET RO) — deployment walkthrough

Step-by-step playbook for bringing bridge2.phntm.pro online on the new FlokiNET Romania VPS. Generated 2026-05-06 alongside the order.

**Server:** FlokiNET RO VPS I, 1 vCPU / 1 GB RAM / 20 GB NVMe / 3 TB
**Hostname:** `bridge2.phntm.pro`
**Primary IPv4:** `185.165.171.206`
**Primary IPv6:** `2a06:1700:0:724::d454:8ad0`
**SSH user:** `phantomadmin`
**SSH key:** ed25519, fingerprint loaded into FlokiNET cpanel at order time

---

## Pre-generated secrets (already in this repo)

For zero-friction deploy, the per-bridge unique values are generated and committed below. Both differ from bridge1 (Hetzner) so the two bridges have entirely independent identities.

```
WEBTUNNEL_NICKNAME       = PhantomBridge2
WEBTUNNEL_OPERATOR_EMAIL = hello@phntm.pro
WEBTUNNEL_OR_PORT        = 44609
WEBTUNNEL_SECRET_PATH    = 35ab85ebe42af5214b579de2560d955b
WEBTUNNEL_URL            = https://bridge2.phntm.pro/35ab85ebe42af5214b579de2560d955b
```

The secret path is committed publicly in this repo because the bridge's security model treats it as a "soft secret" (anyone running a network tap on the route can already see it; its purpose is only to make casual scanners see the placeholder landing instead of the WebTunnel endpoint). Tor's bridge fingerprint is what actually authenticates the bridge to clients.

---

## Step 1 — DNS configuration (do FIRST, before booting Caddy)

In the Cloudflare zone for `phntm.pro`:

```
Type: A
Name: bridge2
Content: 185.165.171.206
Proxy: DNS only (grey cloud)
TTL: Auto

Type: AAAA
Name: bridge2
Content: 2a06:1700:0:724::d454:8ad0
Proxy: DNS only (grey cloud)
TTL: Auto
```

Cloudflare proxying (orange cloud) is OFF on this host. Reasons documented in the Hetzner bridge setup guide.

Verify propagation before proceeding (DNS should be live within 1-5 minutes):

```
dig +short bridge2.phntm.pro
dig +short AAAA bridge2.phntm.pro
```

Both should resolve to the FlokiNET addresses above.

---

## Step 2 — First SSH connect

```sh
ssh -i ~/.ssh/phantom_bridge2 phantomadmin@bridge2.phntm.pro
```

Substitute `~/.ssh/phantom_bridge2` with whatever filename was used when generating the keypair Vladislav loaded at order time.

If FlokiNET's automated provisioning already accepted the SSH public key in the cpanel form, no password is needed. If it asks for password, that means the keypair is not active yet — go back to cpanel, ensure pubkey is pasted into "Authorized SSH keys", apply, and retry.

---

## Step 3 — System hardening

Once logged in, run the following as `phantomadmin`:

```sh
# Update everything
sudo apt update && sudo apt upgrade -y

# Install dependencies (Docker installed in Step 4)
sudo apt install -y ufw fail2ban unattended-upgrades curl git

# Disable unattended-upgrades email noise on a host with no mail config
echo 'Unattended-Upgrade::Mail "";' | sudo tee /etc/apt/apt.conf.d/52unattended-upgrades-local

# Enable automatic security updates
sudo dpkg-reconfigure -plow unattended-upgrades   # accept default: yes

# Configure firewall — default-deny, allow SSH, HTTP, HTTPS only
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp comment "SSH"
sudo ufw allow 80/tcp comment "Caddy HTTP (ACME)"
sudo ufw allow 443/tcp comment "Caddy HTTPS"
sudo ufw allow 443/udp comment "Caddy HTTP/3 QUIC"
sudo ufw allow 44609/tcp comment "Tor OR (bridge2)"
sudo ufw --force enable
sudo ufw status verbose
```

Note: we DO NOT open port 15000 — that's only the internal docker network between Caddy and the WebTunnel bridge. Opening it externally would bypass our reverse-proxy + TLS layer and is a security regression.

Disable SSH password authentication (already key-based):

```sh
sudo sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sudo systemctl restart ssh
```

Test from a NEW terminal that you can still log in via key (do NOT close the existing session until verified — otherwise you lock yourself out).

---

## Step 4 — Install Docker

Official upstream Docker repo (Ubuntu 24.04 default `docker.io` is older and lacks Compose v2 properly):

```sh
# Remove any old docker
for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
  sudo apt-get remove -y $pkg
done

# Add Docker's official GPG key + repo
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Add phantomadmin to docker group (so we don't need sudo for docker compose)
sudo usermod -aG docker phantomadmin

# Verify (log out + back in for group change to take effect)
exit
```

Re-SSH:

```sh
ssh -i ~/.ssh/phantom_bridge2 phantomadmin@bridge2.phntm.pro
docker --version           # should print Docker version 27.x or later
docker compose version     # should print Docker Compose version v2.x
```

---

## Step 5 — Clone the PHANTOM repo

The bridge2 deploy stack lives in the same repo as everything else, under `deploy/bridge2/`.

```sh
cd ~
git clone https://github.com/LiudvigVladislav/Phantom.git
cd Phantom
git checkout feat/tor-stage5-bridges-via-onionwrapper   # until merged to master
cd deploy/bridge2
```

---

## Step 6 — Create the .env file

Copy the values from the "Pre-generated secrets" block at the top of this document into a fresh `deploy/bridge2/.env`:

```sh
cat > .env <<'EOF'
WEBTUNNEL_NICKNAME=PhantomBridge2
WEBTUNNEL_OPERATOR_EMAIL=hello@phntm.pro
WEBTUNNEL_OR_PORT=44609
WEBTUNNEL_SECRET_PATH=35ab85ebe42af5214b579de2560d955b
WEBTUNNEL_URL=https://bridge2.phntm.pro/35ab85ebe42af5214b579de2560d955b
EOF
chmod 600 .env
```

---

## Step 7 — Bring the stack up

```sh
docker compose up -d
```

Wait ~30 seconds for Caddy to obtain the Let's Encrypt cert and the WebTunnel bridge to register itself with the Tor network.

Verify both containers are healthy:

```sh
docker compose ps
# Expect: phantom-bridge2-caddy and phantom-bridge2-webtunnel both "Up"
```

Watch the logs for any errors during startup:

```sh
docker compose logs --tail=100 caddy
docker compose logs --tail=100 webtunnel-bridge
```

In Caddy logs, expect to see successful TLS cert acquisition for `bridge2.phntm.pro`. In webtunnel-bridge logs, expect to see Tor bootstrap reach 100% within ~5-30 seconds (the bridge has no cached state on first boot, so cold-start time is normal).

---

## Step 8 — Extract the bridge line

Once the WebTunnel bridge has bootstrapped, get its canonical bridge line:

```sh
docker compose exec webtunnel-bridge get-bridge-line.sh
```

Output looks like:

```
webtunnel <IPv6-or-IPv4>:443 <FINGERPRINT> url=https://bridge2.phntm.pro/35ab85ebe42af5214b579de2560d955b
```

The IP-and-port part may show the IETF documentation prefix `2001:db8::/32` — that is the canonical placeholder for URL-based pluggable transports (same as bridge1). The fingerprint is the bridge's actual identity.

**Send this entire line to the dev workstation.** It goes into `OperatorBridges.WEBTUNNEL` in source — see Step 9.

---

## Step 9 — Update OperatorBridges.kt (dev workstation, NOT on the VPS)

On the dev box, edit:

```
shared/core/transport/src/androidMain/kotlin/phantom/core/transport/OperatorBridges.kt
```

Add the new bridge line to the `WEBTUNNEL` list, keeping the existing bridge1 entry (so the client races both):

```kotlin
val WEBTUNNEL: List<String> = listOf(
    // bridge1.phntm.pro (Hetzner DE) — primary for non-RU users
    "Bridge webtunnel [2001:db8:1d47:723c:6cf0:a211:e413:8887]:443 " +
        "D2F3A6695223C0DCDBC14AF159807474673A539C " +
        "url=https://bridge.phntm.pro/2a8652911c0cf7150ad0a0b32626434a",

    // bridge2.phntm.pro (FlokiNET RO) — primary for RU users
    "Bridge webtunnel [2001:db8:...]:443 <FINGERPRINT> " +
        "url=https://bridge2.phntm.pro/35ab85ebe42af5214b579de2560d955b",
)
```

Order matters: tor's bridge selection works through entries in order. Putting bridge2 second means non-RU users still try Hetzner first (often slightly faster); RU users fall through to bridge2 within seconds.

(Stage 5D follow-up will replace this static order with a parallel race; for now the in-order list is fine and keeps the client change to a one-liner.)

Rebuild + ship a fresh debug APK:

```sh
./gradlew :apps:android:assembleDebug
```

---

## Step 10 — Test 13 (Tecno на МТС, без VPN)

Install the new APK on the test Tecno. Open PHANTOM. Watch the foreground notification:

Expected sequence:
```
Tor: connecting…
Tor: bootstrapping 1%, 5%, 10%, 14%, 25%, 50%, 75%, 100%
Tor: ready (SOCKS 39050)
```

Time to 100% expected: 60-120 sec on first try. If reached:
- Send a test message → should deliver
- Repeat 10 times. Acceptance: ≥7/10 successful bootstraps in <120 sec.

If it stalls at 25-73 % the same way bridge1 did, the FlokiNET bridge is also hitting the 16-KB curtain. In that case:
1. Report logs back to the dev workstation
2. Don't extend FlokiNET past month 1 — cancel the recurring billing
3. Pivot to Stage 5E (Xray REALITY) full-time

Document the result in `docs/PROJECT_LOG.md` either way.

---

## Step 11 — Backups

Once the bridge is verified working, back up the `webtunnel-tor-state` Docker volume:

```sh
docker run --rm \
    -v webtunnel-tor-state:/data:ro \
    -v $(pwd):/backup \
    alpine:3.19 \
    tar czf /backup/bridge2-tor-state-backup-$(date +%Y%m%d).tar.gz -C /data .
```

Copy the resulting `.tar.gz` off-host (encrypted, two locations). Losing this volume rotates the bridge fingerprint and breaks every shipped APK that pinned the old one.

---

## Step 12 — PR-INFRA-MediaRO add-on (2026-05-18)

Adds a co-tenant `phantom-relay-ro` container + `media-ro.phntm.pro` Caddy vhost on the existing bridge2 host. Diagnostic / beta only — used by the M2c.0 cap probe to compare Tele2 LTE full-roundtrip throughput between Hetzner Helsinki and FlokiNET Romania paths. Does NOT alter the bridge2 WebTunnel role.

### 12.1 — Pre-check: bridge2 health BEFORE deploying media relay

The relay co-tenant adds ~150–300 MB RAM usage and a few CPU percent. With 1 GB total RAM on this VPS the headroom is tight. Capture baseline before any change:

```bash
ssh phantomadmin@bridge2.phntm.pro
free -m
df -h
docker stats --no-stream
docker ps
```

Expected baseline (no media relay yet):
- `free -m` available ≥ 600 MB
- `docker ps` shows `phantom-bridge2-caddy` + `phantom-bridge2-webtunnel`

If available memory is already < 400 MB, abort — there is not enough headroom for a third container at this VPS tier.

### 12.2 — DNS records

Add on the same DNS provider that hosts `bridge2.phntm.pro`:

```
A     media-ro.phntm.pro → 185.165.171.206
AAAA  media-ro.phntm.pro → 2a06:1700:0:724::d454:8ad0
```

Same IPs as `bridge2.phntm.pro` (the box is shared). Wait until the records resolve from at least two `dig` checks before continuing — Caddy needs to complete the Let's Encrypt HTTP-01 challenge on first start, which requires the A record live.

### 12.3 — Update repo on VPS

```bash
ssh phantomadmin@bridge2.phntm.pro
cd ~/Phantom
git fetch origin
git checkout master
git pull origin master
```

### 12.4 — Update the bridge2 `.env`

The existing `.env` already has the WebTunnel secrets. Append the media-relay env:

```bash
cd ~/Phantom/deploy/bridge2
grep -c 'RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES' .env || \
  echo 'RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES=9000' >> .env
grep 'RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES' .env
# Expected: RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES=9000
```

### 12.5 — Build and start the relay container

```bash
cd ~/Phantom/deploy/bridge2
docker compose -f docker-compose.yml up -d --build phantom-relay-ro
```

This builds the relay image (~30–60 s first time, leverages BuildKit cache afterwards) and brings up only the new service. Caddy and the WebTunnel bridge are untouched.

Verify the relay started:

```bash
docker ps --format '{{.Names}}  {{.Image}}  {{.Status}}' | grep phantom-relay-ro
docker logs phantom-relay-ro --tail 10
# Expected last line:
#   phantom-relay starting host=0.0.0.0 port=8080 max_payload_kb=1024 ...
docker exec phantom-relay-ro env | grep RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES
# Expected: RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES=9000
```

### 12.6 — Reload Caddy with the new media-ro vhost

The Caddyfile change is in `git pull` already. Apply it:

```bash
cd ~/Phantom/deploy/bridge2
docker compose -f docker-compose.yml up -d caddy
# OR (safer — no container restart, only config reload):
docker exec phantom-bridge2-caddy caddy reload --config /etc/caddy/Caddyfile
docker logs phantom-bridge2-caddy --tail 5
```

If the reload errors, check the Caddy config is mounted (read-only) and the syntax is valid; Caddy will refuse to swap the running config on syntax errors so the WebTunnel vhost stays up.

### 12.7 — Verify both vhosts work + bridge is unaffected

Bridge2 health (must still pass):

```bash
# WebTunnel secret path — must NOT 404
curl -sI -m 5 "https://bridge2.phntm.pro/35ab85ebe42af5214b579de2560d955b" | head -5

# bridge2 root — fake landing
curl -sI -m 5 "https://bridge2.phntm.pro/" | head -3

# Bridge process logs — should be quiet / no panic
docker logs phantom-bridge2-webtunnel --tail 20
```

Media-ro health (new):

```bash
curl -sI -m 5 "https://media-ro.phntm.pro/health" | head -3
# Expected: HTTP/2 200 (or HTTP/1.1 200) — Caddy TLS → relay /health

# Smoke test that the media body cap is applied:
curl -sI -m 5 "https://media-ro.phntm.pro/auth/session" -X POST | head -3
# Expected: 422 (empty body — relay's /auth/session needs JSON)
```

### 12.8 — Resource sanity check after deploy

```bash
free -m
docker stats --no-stream
```

Expected:
- `free -m` available ≥ 250 MB (bridge2 baseline minus ~150 MB relay)
- `phantom-relay-ro` MEM USAGE < 384 MB (the `mem_limit` in compose)
- bridge container CPU < 5 % steady-state

If available memory drops below 150 MB or the bridge starts paging, stop the relay container immediately (see rollback below).

### 12.9 — Rollback

If the relay or its vhost breaks anything:

```bash
# Stop just the relay (Caddy + WebTunnel keep running)
cd ~/Phantom/deploy/bridge2
docker compose -f docker-compose.yml stop phantom-relay-ro
docker compose -f docker-compose.yml rm -f phantom-relay-ro

# Optional — remove the media-ro vhost from active Caddy config
# (the Caddyfile in git is unchanged; just reload to drop the vhost
#  for now, OR edit a local copy and `caddy reload`):
docker exec phantom-bridge2-caddy caddy reload --config /etc/caddy/Caddyfile
```

The WebTunnel bridge keeps serving normally — bridge fingerprint is unaffected, Tor descriptor cache is unchanged.

### 12.10 — Probe re-run from the Android side

After 12.5–12.7 pass, on the dev workstation:

```bash
cd "d:/VL Stories Studio/Phantom"
git checkout diag/m2c0-media-route-probe
# Probe code on this branch already references `MediaProbeEndpoint(id="helsinki", ...)`.
# A follow-up commit on the same branch adds `id="romania", baseUrl="https://media-ro.phntm.pro", ...`
# and exposes a second token-provider scoped to that orchestrator. Rebuild APK,
# install on Tecno, and tap Settings → Diagnostics → "Run media route probe".
```

Logcat will emit `M2C0_PROBE upload_result endpoint=romania ...`, `M2C0_SUMMARY endpoint=romania size=… verdict=…`, and a single `M2C0_FINAL` line that compares the two endpoints and picks the better one. If Romania gives `verdict=stable_full` at 5500+ where Helsinki failed, M2c becomes feasible **for that specific endpoint** via the `mediaRelayId` design sketch in `docs/design/voice-delivery-audit-2026-05-18.md` Section 8.

---

## Reference: where things live

| Path on VPS | Purpose |
|---|---|
| `~/Phantom/deploy/bridge2/docker-compose.yml` | Stack definition (caddy + webtunnel-bridge + phantom-relay-ro) |
| `~/Phantom/deploy/bridge2/Caddyfile` | TLS terminator + reverse-proxy config (bridge2 + media-ro vhosts) |
| `~/Phantom/deploy/bridge2/webtunnel-landing/index.html` | Fake landing page (bridge2 vhost only) |
| `~/Phantom/deploy/bridge2/.env` | Per-host secrets (gitignored). Includes `RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES=9000` for PR-INFRA-MediaRO. |
| Docker volume `webtunnel-tor-state` | Bridge identity keys + descriptor cache |
| Docker volume `caddy-data` | Let's Encrypt certs (covers both vhosts) |
| Docker volume `phantom-relay-ro-reports` | Media relay abuse-report log directory (`/var/phantom`) |

---

*End of bridge2 deploy walkthrough. If anything goes sideways, capture full `docker compose logs` and bring back to the dev workstation.*
