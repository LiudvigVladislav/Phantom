<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (c) 2026 Willen LLC
-->

# WebTunnel bridge — operator setup guide

This document walks the operator through bringing up the operator-controlled WebTunnel bridge that PHANTOM clients fall back to when public Snowflake brokers are blocked. Companion to:

- `docs/adr/ADR-018-onionwrapper-migration.md` — why we run our own bridge
- `docs/operations/TOR_STACK_MAINTENANCE.md` — release-monitoring protocol
- `deploy/docker-compose.yml` § `webtunnel-bridge` — the service definition
- `deploy/Caddyfile` § `bridge.phntm.pro` — the reverse-proxy vhost

---

## 1. Prerequisites

- DNS control over `phntm.pro` (Cloudflare in our case).
- The Hetzner VPS already running `deploy/docker-compose.yml` with relay + caddy + tor + ntfy.
- SSH access to the VPS as a user who can run `docker compose`.
- ~150 MB additional disk (the `thetorproject/webtunnel-bridge` image plus state volume).
- ~50–500 MB/month outbound bandwidth (depends on user load — bridge relays only PHANTOM client traffic, not arbitrary Tor users).

---

## 2. Generate the secret path

The "secret path" is the URL fragment after `bridge.phntm.pro/` that PHANTOM clients send when initiating a WebTunnel connection. Anyone hitting any other path on the domain sees the placeholder landing — making the bridge look like a small static site to scanners.

On the VPS (or anywhere with `openssl`):

```sh
openssl rand -hex 16
```

Output is a 32-character hex string, e.g. `a4f7c2b9e8d1f6a3b5c7d9e2f4a6b8c1`. **Save it** — you will paste it twice (once into `.env`, once into the client-side `OperatorBridges.kt`).

---

## 3. Pick an OR port

The bridge daemon needs an OR (Onion Routing) port to talk to other Tor relays. Pick a high port number (above 30000) that does not conflict with anything else on the VPS:

```sh
shuf -i 30000-60000 -n 1
```

Save this number — it goes into `.env` as `WEBTUNNEL_OR_PORT`. The port stays the same across container restarts (otherwise the bridge identity rotates).

---

## 4. Add DNS record

In Cloudflare DNS settings for `phntm.pro`:

```
Type: A
Name: bridge
Content: <Hetzner VPS IPv4>
Proxy status: DNS only (grey cloud, NOT orange)
TTL: Auto
```

The orange-cloud (Cloudflare proxying) MUST be off for this subdomain. The WebSocket upgrade through Cloudflare's WS proxy adds chained TLS fingerprints that DPI can detect; bridges work better with a direct TCP path to Caddy.

Verify resolution from the local box once propagation completes (~1-5 minutes):

```sh
dig +short bridge.phntm.pro
```

---

## 5. Add the env vars

On the VPS, edit `deploy/.env` (create if missing):

```
WEBTUNNEL_NICKNAME=PhantomBridge1
WEBTUNNEL_OPERATOR_EMAIL=hello@phntm.pro
WEBTUNNEL_OR_PORT=<the number from step 3>
WEBTUNNEL_SECRET_PATH=<the hex string from step 2>
WEBTUNNEL_URL=https://bridge.phntm.pro/<the hex string from step 2>
```

The duplication between `WEBTUNNEL_SECRET_PATH` (read by Caddy) and `WEBTUNNEL_URL` (read by the bridge daemon) is intentional — the daemon needs to know the public URL it is reachable at, including the path; Caddy just needs the path to route on. Both must contain the same hex string.

---

## 6. Bring up the bridge

```sh
docker compose -f deploy/docker-compose.yml up -d --build webtunnel-bridge caddy
```

The `--build` flag is harmless for the WebTunnel image (it pulls from Docker Hub) but is needed if the Caddy reverse-proxy config changed and you want it picked up.

Wait ~30 seconds for the bridge to register itself with the Tor network.

---

## 7. Extract the bridge line

The PHANTOM Android client needs the bridge fingerprint to recognise this bridge. Get the canonical bridge line:

```sh
docker compose -f deploy/docker-compose.yml exec webtunnel-bridge get-bridge-line.sh
```

Output looks like:

```
webtunnel <IPv4>:443 <FINGERPRINT> url=https://bridge.phntm.pro/<secret-path> ver=0.0.1
```

Copy this entire line. Open the PHANTOM Android repo on the dev machine and edit:

```
shared/core/transport/src/androidMain/kotlin/phantom/core/transport/OperatorBridges.kt
```

Add the line — prefixed with `"Bridge "` per the same convention as `SnowflakeBridges.kt`. See the file's own header comment for the format.

After committing the new bridge line, build a fresh APK and ship to testers.

---

## 8. Verify reachability

Three checks, in increasing depth:

**8.1 Caddy serves the placeholder**

```sh
curl -sI https://bridge.phntm.pro/ | head -1
# → HTTP/2 200
```

**8.2 The secret path proxies to the WebTunnel daemon**

```sh
curl -sI -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Key: $(openssl rand -base64 16)" \
     -H "Sec-WebSocket-Version: 13" \
     https://bridge.phntm.pro/<secret-path>
# → HTTP/1.1 101 Switching Protocols   (plus WebSocket headers)
```

**8.3 PHANTOM client bootstrap through the bridge**

On the test device (Tecno без VPN на МТС):
1. Install the freshly-built APK with the new bridge line in `OperatorBridges.kt`.
2. Open PHANTOM, watch the foreground notification text.
3. Expected: `Tor: bootstrapping 5%` → `25%` → `75%` → `100%` → `Tor: ready (SOCKS 39050)` within ~60–90 seconds.
4. Send a test message. Should deliver.

If 8.1 fails: Caddy / DNS issue. Check `docker compose logs caddy`.
If 8.2 fails: secret-path mismatch between `.env` and the URL you queried. Compare `WEBTUNNEL_SECRET_PATH` and the URL.
If 8.3 fails (bootstrap stalls below 25%): the bridge is reachable but the bridge-to-Tor leg has issues. Check `docker compose logs webtunnel-bridge`.

---

## 9. Backups

The bridge identity (Tor relay key) lives in the named volume `webtunnel-tor-state`. Losing this volume rotates the bridge fingerprint — every PHANTOM client with the old fingerprint hardcoded silently stops connecting. **Back this volume up alongside `phantom-tor-data`** using the same procedure documented in `deploy/tor-onion-setup.md` § "Backing up the onion key".

```sh
# Same pattern as the onion key backup, swap volume name.
docker run --rm \
    -v webtunnel-tor-state:/data:ro \
    -v $(pwd):/backup \
    alpine:3.19 \
    tar czf /backup/webtunnel-bridge-backup-$(date +%Y%m%d).tar.gz -C /data .
```

Store off-host, encrypted, in at least two locations.

---

## 10. Rotation

When you rotate the bridge fingerprint (key compromise, planned migration, fresh deploy of a backup bridge):

1. `docker compose -f deploy/docker-compose.yml stop webtunnel-bridge`
2. `docker volume rm webtunnel-tor-state`
3. `docker compose -f deploy/docker-compose.yml up -d webtunnel-bridge`
4. Re-extract bridge line per §7.
5. Update `OperatorBridges.kt` in the Android repo.
6. Ship new APK.

Until the new APK is in users' hands, those who upgraded continue to use the old fingerprint and fail to connect — plan rotations to coincide with already-scheduled releases.

---

## 11. Known limitation (Alpha 2 acceptable risk)

The bridge currently runs on the same VPS as the relay onion service. A network-observing adversary capable of enumerating both `bridge.phntm.pro` and the relay's onion address from the same IP can correlate "client enters bridge" with "client connects to onion service" — reducing anonymity for that specific traffic flow.

This is an Alpha 2 trade-off. Beta plan: distribute the bridge to a separate provider / region (DigitalOcean / OVH / Vultr) so the IPs do not collocate.

The vast majority of users on censored networks (РФ / Иран / Китай) are protected from their own ISP, TSPU, and similar regional adversaries — that is the threat model this bridge addresses today. Operator (we) can already see relay-side traffic by definition; running the entry bridge does not expand what we learn about our own users.

Documented in `docs/operations/TOR_STACK_MAINTENANCE.md` § Alpha-2 limitations.
