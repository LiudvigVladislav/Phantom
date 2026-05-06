<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (c) 2026 Willen LLC
-->

# Stage 5E.A — Xray VLESS+REALITY server (Hetzner)

Operator deployment guide for the Xray-core daemon that PHANTOM clients use as the **outer transport** when on censored networks (Russia / Iran / China). REALITY makes the TLS handshake genuinely indistinguishable from one to `www.microsoft.com` — TSPU's "16-kilobyte curtain" classifier puts it in the trusted-big-tech bucket and applies no throttle.

This is **server-only**. Stage 5E.B brings the matching libXray Android library; Stage 5E.A exists to validate the wire format with a desktop NekoBox client before any Kotlin work.

**Companions:**
- `docs/research/xray-reality-integration-2026-05-06.md` — full engineering plan
- `docs/operations/TOR_STACK_MAINTENANCE.md` — same monitoring discipline applies to Xray-core releases
- `deploy/docker-compose.yml` § `xray` — the service definition

---

## 1. Prerequisites

- Hetzner VPS already running `deploy/docker-compose.yml` (relay + caddy + tor + ntfy + webtunnel-bridge). Adding `xray` keeps that stack untouched.
- `docker` + `docker compose` installed (already present per Stage 1 deploy).
- ~50 MB additional disk for the Xray image.
- Outbound TCP to `www.microsoft.com:443` from the VPS (for the `dest` cert-chain reverse proxy).
- UFW rule for **port 8443/tcp** (Stage 5E.A; will be promoted to shared 443 in Stage 5E.B).

---

## 2. One-time setup on the VPS

### 2.1 Open the port

```sh
sudo ufw allow 8443/tcp comment "Xray REALITY (Stage 5E.A)"
sudo ufw status verbose
```

### 2.2 Generate REALITY keys + UUID + shortId

The Xray container itself can generate them — no extra dependencies:

```sh
cd ~/Phantom/deploy/xray

# X25519 keypair for REALITY (the private half stays on the server, the
# public half goes into the APK's OperatorXrayConfig).
docker run --rm ghcr.io/xtls/xray-core:latest x25519
# Sample output:
#   Private key: cAv...QnE
#   Public key:  K3a...4-w
# COPY BOTH. Private to .env below, public for the client config.

# UUID (VLESS client identifier — same purpose as a username).
docker run --rm ghcr.io/xtls/xray-core:latest uuid
# Sample output:
#   c1e9f3a2-3b4d-4e5f-9a8b-7c6d5e4f3a2b

# ShortId — 8 random bytes hex. Real REALITY clients send one of these
# to identify themselves to the server's REALITY listener.
openssl rand -hex 8
# Sample output:
#   3a4b5c6d7e8f9a0b
```

Save **all four values**. The private key, UUID, and shortId go into `.env` here on the server. The public key, UUID, and shortId go into PHANTOM's `OperatorXrayConfig.kt` (Stage 5E.B).

### 2.3 Create `deploy/xray/.env`

```sh
cd ~/Phantom/deploy/xray
cat > .env <<EOF
XRAY_PRIVATE_KEY=<paste private key from step 2.2>
XRAY_UUID=<paste UUID from step 2.2>
XRAY_SHORT_ID=<paste short ID from step 2.2>
EOF
chmod 600 .env
```

### 2.4 Render `config.json` from template

```sh
./render-config.sh
```

Verify with Xray's own config-test command:

```sh
docker run --rm -v "$(pwd)/config.json:/etc/xray/config.json:ro" \
    ghcr.io/xtls/xray-core:latest test -config /etc/xray/config.json
# Expect: "Configuration OK." (or similar — exit code 0)
```

---

## 3. Bring the service up

The `xray` service is defined in the main `deploy/docker-compose.yml` alongside relay, caddy, tor, ntfy, webtunnel-bridge. Bring just this one up:

```sh
cd ~/Phantom/deploy
docker compose up -d xray
```

Wait ~5 seconds. Verify:

```sh
docker compose ps xray
docker compose logs --tail=30 xray
```

Expected log lines:
```
Xray 24.x ... is running on platform: linux/amd64.
Loading config from: /etc/xray/config.json
[Info] start ... vless@vless-reality-in inbound on tcp:8443
```

If you see `Failed to start: invalid keypair` or similar — the .env has typos in the private key. Re-run §2.4 after fixing.

---

## 4. Validate from a desktop client (BEFORE Android work)

Stage 5E.A's whole point: prove REALITY actually flows through TSPU before investing days in Android compilation. Use **NekoBox for PC** ([nekoneko-kawaii/NekoBoxForAndroid releases](https://github.com/MatsuriDayo/NekoBoxForAndroid/releases) → "PC" variant, or the Linux/macOS/Windows native NekoRay) — it speaks the same VLESS+REALITY+Vision protocol as our future Android library, so a successful NekoBox connection from a Russian carrier vantage validates the entire wire path end-to-end.

### 4.1 Build the share-link

The standard VLESS share-link format:

```
vless://<UUID>@<server-ip>:8443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=<PUBLIC_KEY>&sid=<SHORT_ID>&type=tcp#PHANTOM-test
```

Substitute:
- `<UUID>` — same as `XRAY_UUID` from §2.2
- `<server-ip>` — Hetzner IP (e.g. `65.108.154.152`)
- `<PUBLIC_KEY>` — from `xray x25519` output (NOT the private!)
- `<SHORT_ID>` — same as `XRAY_SHORT_ID` from §2.2

Example (placeholders only):

```
vless://c1e9f3a2-3b4d-4e5f-9a8b-7c6d5e4f3a2b@65.108.154.152:8443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=K3aQ...4-w&sid=3a4b5c6d7e8f9a0b&type=tcp#PHANTOM-test
```

### 4.2 Test from a non-RU vantage first (sanity check)

In NekoBox: paste the share-link → connect. From your dev box (any country) try a quick browse — it should route through the Xray daemon. If yes, the daemon + config are healthy.

### 4.3 Test from RU carrier (the real test)

Either:
- Hand the share-link to Vladislav, he installs NekoBox on his Windows box (no VPN), connects via МТС, tries to browse.
- Or: use a check-host.net active probe with TCP-connect to `<server-ip>:8443` from RU vantage points (port reachability only — won't validate REALITY handshake but rules out an IP-level block).

**Acceptance criteria for Stage 5E.A success:**
- Vladislav on Tecno / Windows box, MTS WiFi, no VPN, NekoBox VLESS+REALITY tunnel connects within 5 seconds.
- Browsing through the tunnel works (e.g. open `https://check.torproject.org` — irrelevant content, just proves end-to-end TCP works).
- The TLS handshake to our IP looks like `www.microsoft.com` (we will have validated this with `openssl s_client -connect <server-ip>:8443 -servername www.microsoft.com` from non-RU before shipping).

If 4.3 succeeds, **Stage 5E.A is done** and Stage 5E.B (Android libXray) is unblocked.

If 4.3 fails — three possible causes, in priority order:
1. TSPU is now blocking REALITY in general (would be a major escalation; would require pivoting to a different fronting target like `www.apple.com` and retest).
2. Our Hetzner IP is fingerprinted — try the same NekoBox config pointed at the FlokiNET bridge2 host (deploy Xray there too, separate Stage 5E.A.2).
3. NekoBox build / share-link typo — sanity-test from non-RU first per §4.2.

---

## 5. Reference: where things live

| Path on VPS | Purpose |
|---|---|
| `~/Phantom/deploy/xray/config.json.template` | Tracked in git; placeholders for keys |
| `~/Phantom/deploy/xray/render-config.sh` | Tracked in git; substitutes placeholders |
| `~/Phantom/deploy/xray/.env` | Gitignored; XRAY_PRIVATE_KEY + XRAY_UUID + XRAY_SHORT_ID |
| `~/Phantom/deploy/xray/config.json` | Gitignored; rendered output mounted into container |

The public counterparts (X25519 public key + UUID + shortId + serverName + dest port) are baked into the Android client in Stage 5E.B as `OperatorXrayConfig.kt`. They are not secret — knowing them allows connecting to our Xray as a client, but VLESS UUIDs are the access control and we treat the UUID as the bearer token in our threat model.

---

## 6. License posture

Xray-core is **MPL-2.0 with Exhibit B** ("Incompatible With Secondary Licenses"). MPL-2.0 is a *file-level* copyleft. Aggregating the MPL daemon binary inside our docker-compose alongside our AGPL-3.0-or-later codebase is permitted (we are not modifying or relinking Xray's source files). Same posture as v2rayNG and Hiddify carry today. NLnet legal sanity-check before Beta is recommended in `docs/research/xray-reality-integration-2026-05-06.md` §6.

---

*Stage 5E.A operator runbook. Stage 5E.B (Android libXray AAR) starts after the desktop NekoBox validation in §4.3 succeeds.*
