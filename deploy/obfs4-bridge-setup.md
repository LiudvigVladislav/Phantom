# obfs4 bridge setup on FlokiNET VPS

> **Purpose:** Stage 5G Phase 1 experiment per
> [`docs/project/DECISIONS_LOG.md`](../docs/project/DECISIONS_LOG.md) D-16.
>
> **Why obfs4:** Test 13 (2026-05-06) showed WebTunnel TLS handshakes hit the
> TSPU 16-KB curtain even on FlokiNET (curtain is a behavioural classifier,
> not an ASN block). obfs4 has a different wire signature — uniform-random
> byte stream, no TLS ClientHello — so it bypasses the curtain's TLS pattern
> matching. This deploy adds obfs4 alongside the existing WebTunnel bridge
> on the same FlokiNET VPS (config change only, no new server).
>
> **Decision gate after deploy:** if Test 13.1 succeeds → continue Stage 5G
> full implementation (ADR-015). If still stalls → fall back to Variant C
> (RU carrier checkpoint warning in Ghost mode). See
> [`docs/research/stage-5g-phase-1-2026-05-09/README.md`](../docs/research/stage-5g-phase-1-2026-05-09/README.md)
> for the test protocol + result template.

---

## Prerequisites

- SSH access to the FlokiNET VPS (the same one currently running
  `bridge2.phntm.pro` WebTunnel bridge).
- Root or sudo on that host.
- An open inbound TCP port on FlokiNET firewall — recommend **TCP/443**
  (looks like normal HTTPS to passive observers; harder to block by port).
  If 443 is already taken by another service, pick any high port and
  document it. The bridge line lands wherever the port lands.

---

## Step 1 — Install Tor + obfs4 binary

```bash
ssh root@<flokinet-vps>

# Debian / Ubuntu (the FlokiNET image we use is Debian-based)
apt update
apt install -y tor obfs4proxy

# Verify the obfs4 binary lives where Tor expects it
which obfs4proxy
# expected: /usr/bin/obfs4proxy
```

`tor` and `obfs4proxy` are the only two packages needed. Tor brings the daemon
+ the bridge protocol; obfs4proxy is the pluggable-transport binary that
implements the obfs4 wire format.

---

## Step 2 — Write the bridge torrc

Create `/etc/tor/torrc-obfs4-bridge` (separate file so it does not collide
with the existing WebTunnel bridge config running on the same host):

```bash
cat > /etc/tor/torrc-obfs4-bridge <<'EOF'
# PHANTOM obfs4 bridge — Stage 5G Phase 1.
# This config runs IN ADDITION to the existing WebTunnel bridge — they
# are separate Tor instances, separate DataDirectories, separate ports.

DataDirectory /var/lib/tor-obfs4
PidFile       /var/run/tor-obfs4.pid
Log notice file /var/log/tor-obfs4/notices.log

# We are a bridge, not a public relay.
BridgeRelay 1
ORPort      auto
ExtORPort   auto

# Obfuscate the bridge's clearnet ORPort with obfs4 on TCP/443.
# Change 443 to a different port if 443 is already in use on this VPS.
ServerTransportPlugin obfs4 exec /usr/bin/obfs4proxy
ServerTransportListenAddr obfs4 0.0.0.0:443

# Do NOT publish in BridgeDB. PHANTOM clients receive the bridge line
# baked into the APK (OperatorBridges.OBFS4) — public discovery would
# flood the bridge with Tor Browser users and dilute it for our app.
PublishServerDescriptor 0

# Reasonable resource limits (FlokiNET is a small VPS, not a high-volume
# guard relay).
ContactInfo phantom-ops <ops@phntm.pro>
Nickname phantomobfs4
RelayBandwidthRate 4 MB
RelayBandwidthBurst 8 MB
EOF

# Create the directories Tor needs.
mkdir -p /var/lib/tor-obfs4 /var/log/tor-obfs4
chown -R debian-tor:debian-tor /var/lib/tor-obfs4 /var/log/tor-obfs4
```

---

## Step 3 — Run the bridge as a separate systemd service

```bash
cat > /etc/systemd/system/tor-obfs4-bridge.service <<'EOF'
[Unit]
Description=PHANTOM obfs4 Tor bridge (Stage 5G Phase 1)
After=network.target

[Service]
Type=notify
User=debian-tor
ExecStart=/usr/bin/tor -f /etc/tor/torrc-obfs4-bridge
Restart=on-failure
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now tor-obfs4-bridge
systemctl status tor-obfs4-bridge
```

Check the log for "Bootstrapped 100% (done): Done" — usually within 30
seconds on a fresh bridge:

```bash
tail -f /var/log/tor-obfs4/notices.log
```

---

## Step 4 — Open the firewall

```bash
# FlokiNET typically uses ufw or iptables. ufw is simplest:
ufw allow 443/tcp comment "PHANTOM obfs4 bridge"
ufw status numbered
```

If using a separate cloud-firewall console (FlokiNET has one), open TCP/443
there too.

---

## Step 5 — Wait ~1 hour, then extract the bridge line

Tor needs ~1 hour to publish its descriptor and stabilise its identity. After
that the bridge line lives in:

```bash
cat /var/lib/tor-obfs4/pt_state/obfs4_bridgeline.txt
```

The output looks like:

```
# obfs4 torrc client configuration:
#
# Add these lines to /etc/tor/torrc and restart tor.
Bridge obfs4 <YOUR_VPS_IP>:443 <FINGERPRINT> cert=<BASE64_CERT> iat-mode=0
```

Copy the **last line** — the one starting with `Bridge obfs4 …` — verbatim.

---

## Step 6 — Paste the bridge line into the Android client

In the PHANTOM repo:

```bash
# Open the file:
shared/core/transport/src/androidMain/kotlin/phantom/core/transport/OperatorBridges.kt

# Replace:
val OBFS4: List<String> = emptyList()

# With:
val OBFS4: List<String> = listOf(
    "Bridge obfs4 <VPS_IP>:443 <FINGERPRINT> " +
        "cert=<BASE64_CERT> iat-mode=0",
)
```

Commit + ship a new APK. The obfs4 entry is wired ahead of WebTunnel by
[`TorServiceFactory.android.kt`](../shared/core/transport/src/androidMain/kotlin/phantom/core/transport/TorServiceFactory.android.kt) §"Bridge order matters" so the very first
connect attempt in Ghost mode tries obfs4.

---

## Verification before client test

On the bridge host, before installing the new APK on Tecno МТС:

```bash
# Confirm the bridge accepts a test connection from the obfs4proxy CLI on
# the same host (loopback test — bypasses TSPU and proves the bridge
# itself is up and accepting handshakes).
curl -v --connect-timeout 5 https://<VPS_IP>:443
# Expect: TLS handshake fails with garbage. That is correct — obfs4 does
# not speak TLS. The connect-establishing TCP-syn → TCP-ack pair proves
# the port is open and the daemon is listening.

# Confirm Tor sees its own descriptor as published:
grep -E "(self-test|reachable)" /var/log/tor-obfs4/notices.log
```

---

## Decision gate

After Test 13.1 (see
[`docs/research/stage-5g-phase-1-2026-05-09/README.md`](../docs/research/stage-5g-phase-1-2026-05-09/README.md)):

| Outcome | Next step |
|---|---|
| Tor bootstraps to 100% on Tecno МТС via obfs4 (Phase 1 success) | Continue Stage 5G full: ADR-015 draft, evaluate adding Snowflake server alongside obfs4, multi-bridge fan-out for redundancy |
| Tor still stalls (curtain catches obfs4 too) | **Fall back to Variant C**: ship a UI checkpoint on Ghost mode for RU carriers ("Tor may not work — proceed?"). Mark Stage 5G as not viable for Alpha 2 in DECISIONS_LOG |

Either way the bridge stays running on the VPS — it costs almost nothing to
keep it up, and non-RU Ghost-mode users get a cleaner first hop.

---

## Rollback

If anything goes wrong with the bridge config and breaks the existing
WebTunnel bridge or the host:

```bash
systemctl stop tor-obfs4-bridge
systemctl disable tor-obfs4-bridge
rm /etc/systemd/system/tor-obfs4-bridge.service
rm /etc/tor/torrc-obfs4-bridge
rm -rf /var/lib/tor-obfs4 /var/log/tor-obfs4
ufw delete allow 443/tcp
systemctl daemon-reload
```

The existing WebTunnel bridge is untouched throughout this procedure.
