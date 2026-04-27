# PHANTOM Deployment

Production deployment for the PHANTOM relay and landing page.

- **Target host:** `phantom-relay-01` (Hetzner Helsinki, CPX22, Ubuntu 24.04, 65.108.154.152)
- **SSH:** `phantom@relay.phntm.pro` (ed25519 key)
- **Services:** Rust relay + Caddy reverse proxy, both in Docker.

---

## Files

| File | Purpose |
|------|---------|
| `docker-compose.yml` | Brings up `relay` + `caddy` containers on a private bridge network. |
| `Caddyfile` | Two vhosts — `relay.phntm.pro` (WebSocket+HTTP reverse proxy) and `phntm.pro` (static landing + `/.well-known/`). |
| `well-known/assetlinks.json` | Android App Links manifest — replace placeholder SHA-256 before release signing. |
| `landing/index.html` | Minimal dark-theme placeholder until the Next.js landing (Этап 4.3) ships. |
| `.env.example` | Relay config via env vars. Copy to `.env` and edit. |
| `../services/relay/Dockerfile` | Multi-stage Rust build + debian-slim runtime. |

---

## One-time host preparation

Run on the VPS once. These steps are **not** automated by docker-compose.

```bash
# 1. Add 2 GB swap (CPX22 has 4 GB RAM — swap avoids OOM during cargo build).
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# 2. Install Docker Engine + compose plugin (official Docker repo).
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 3. Let the `phantom` user run docker without sudo.
sudo usermod -aG docker phantom
# Log out and back in for the group to take effect.

# 4. Open the firewall for 80/443 (UFW is already configured).
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp   # HTTP/3 (QUIC)

# 5. Give Caddy a directory for report logs — the relay container writes
#    abuse reports here.
sudo mkdir -p /var/phantom && sudo chown 10001:10001 /var/phantom
```

---

## Deploy

From the repository root (on the VPS after `git pull`):

```bash
cp deploy/.env.example deploy/.env
# Edit deploy/.env — at minimum set RELAY_SECRET_TOKEN if you want closed alpha.

docker compose -f deploy/docker-compose.yml up -d --build
```

Expected services:

```bash
$ docker compose -f deploy/docker-compose.yml ps
NAME              IMAGE                STATUS                     PORTS
phantom-caddy     caddy:2.8-alpine     Up (healthy)               0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp
phantom-relay     phantom-relay:latest Up (healthy)
```

Verify:

```bash
# Health endpoint over TLS (Caddy should have a Let's Encrypt cert by now).
curl -s https://relay.phntm.pro/health
# -> ok

# WebSocket handshake.
curl -v --http1.1 \
     -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     -H "Sec-WebSocket-Version: 13" \
     https://relay.phntm.pro/ws
# -> HTTP/1.1 101 Switching Protocols
```

---

## DNS + Cloudflare caveats

Two hosts, two different Cloudflare configs:

| Host              | Cloudflare | Why |
|-------------------|------------|-----|
| `relay.phntm.pro` | **DNS only** (grey cloud) | WebSocket through Cloudflare Free is 100 s timeout. Alpha users on slow cellular need longer. Direct connection also keeps Cloudflare out of the metadata path. |
| `phntm.pro`       | **Proxied** (orange cloud) | DDoS protection for the public landing is fine; no WebSocket here. |

**First-deploy chicken-and-egg:** Cloudflare-Proxied hosts can't complete Let's Encrypt HTTP-01 challenges because Cloudflare intercepts `:80`. Two options:

1. **Temporary DNS-only.** Set `phntm.pro` to DNS-only, run `docker compose up`, wait for Caddy to issue the cert, then re-enable the orange cloud. Works once; cert renewals (every 60 days) use the persisted key so no repeat needed.
2. **DNS-01 via Cloudflare API.** Install `caddy-dns/cloudflare` build or use the [Caddy Docker Proxy](https://github.com/lucaslorentz/caddy-docker-proxy) image; add a Cloudflare API token with DNS:Edit permission. Future-proof but adds a moving piece.

Recommendation: option 1 for the first deploy. Move to option 2 once a landing is live and cert rotation is a real concern.

---

## Updating the `assetlinks.json` fingerprint

Before a signed release:

```bash
# On your build machine, extract the SHA-256 fingerprint of the upload key:
keytool -list -v -keystore ~/path/to/phantom-release.keystore -alias phantom \
  | grep "SHA256:" \
  | awk '{print $2}'
# -> AB:CD:EF:...:12:34
```

Copy the colon-separated hex into `deploy/well-known/assetlinks.json`, then:

```bash
scp deploy/well-known/assetlinks.json phantom@relay.phntm.pro:/opt/phantom/deploy/well-known/
# or commit and `git pull` on the VPS if you host the repo there.
```

Caddy serves it from the read-only mount — no container restart needed.

Validate:

```bash
curl -s https://phntm.pro/.well-known/assetlinks.json \
  | jq '.[0].target.sha256_cert_fingerprints'
```

Then the Google Digital Asset Links tester:
`https://developers.google.com/digital-asset-links/tools/generator`

---

## Updating the relay

```bash
cd /opt/phantom
git pull
docker compose -f deploy/docker-compose.yml up -d --build relay
```

Caddy doesn't restart — only the relay container rebuilds. In-memory queue is lost on restart, but that is by design: envelopes not yet delivered are the client's responsibility to retry.

---

## Rollback

```bash
# Revert to the previous image tag (if you tagged `phantom-relay:vN`):
docker compose -f deploy/docker-compose.yml stop relay
docker tag phantom-relay:v-previous phantom-relay:latest
docker compose -f deploy/docker-compose.yml up -d relay
```

Or simply `git checkout <prev-sha>` and `docker compose up -d --build relay`.

---

## Operational observability

- `docker compose -f deploy/docker-compose.yml logs -f relay` — relay tracing.
- `docker compose -f deploy/docker-compose.yml logs -f caddy` — access logs (method + path, no query string).
- Caddy metrics endpoint is disabled; re-enable in `Caddyfile` if Prometheus is wired up later.

Log scope is intentionally narrow. The relay **never** logs ciphertext, sealed-sender values, or the `?token=` query parameter.
