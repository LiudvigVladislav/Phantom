# Network diagnostic plan -- 2026-05-04

## Executive summary

Empirically PHANTOM clients without VPN lose the WebSocket connection to
`relay.phntm.pro` every 50-60 seconds with `Connection reset`, while clients
behind a VPN are stable. We do NOT yet know whether the RST is sent by the
relay process, by Caddy, by Hetzner, or by a middlebox on the transit path
(home ISP CGN, mobile carrier DPI, hotel captive portal, etc.). This plan
runs five paired experiments (VPN baseline, no-VPN repro, voice burst,
Caddy log correlation, relay log correlation), captures packets on BOTH the
VPS and the Windows PC simultaneously, and disambiguates five hypotheses
(H1 Caddy idle_timeout, H2 Hetzner filter, H3 transit middlebox, H4 relay
code, H5 MTU/fragmentation). Total session budget: 1-2 hours, all captures
<= 5 minutes each, ~150-300 MB total pcap. No firewall changes required;
only read-only `tcpdump`, `ss`, `journalctl`, `docker logs`. Result: a
decision-tree verdict on which component closes the socket and what the
mitigation class is (keepalive vs. proxy config vs. transport swap).

---

## Pre-flight checks

Run these on the VPS BEFORE any experiment. They establish baseline state
and verify nothing is already broken. Copy-paste each line, read the
output, do not interpret -- just confirm it ran without error.

### PF-1. Confirm we are on the right host

```
hostname
uname -a
date -u
```

Expected: hostname is the Hetzner box, kernel >= 5.x, date in UTC within
30 seconds of `https://time.is`. If date drifts more than 30 seconds, NTP
is broken and timestamps in pcaps will not align with client logs --
fix that first (`timedatectl status`, `systemctl restart systemd-timesyncd`).

### PF-2. Verify Caddy and relay are up

```
systemctl status caddy --no-pager | head -20
docker ps --filter name=phantom-relay
ss -tlnp | grep -E ':443|:8080'
```

Expected: Caddy active (running), `phantom-relay` container Up, ports
443 (caddy) and 8080 (relay, on 127.0.0.1) listening. If any of these is
wrong STOP and fix before forensics -- a broken service explains nothing.

### PF-3. Confirm Caddy upstream config

```
cat /etc/caddy/Caddyfile | grep -A 5 -i 'relay\|reverse_proxy\|websocket\|timeout'
```

Note the values of `idle_timeout`, `read_timeout`, `write_timeout` if
present. If absent, Caddy uses defaults: `idle_timeout = 5m`,
`read_timeout = unlimited`, `write_timeout = unlimited`. WebSocket
upgrades typically inherit `idle_timeout`. Save this output to a file:

```
cp /etc/caddy/Caddyfile /tmp/Caddyfile.snapshot.$(date +%s)
```

### PF-4. Capture interface name and public IP

```
ip -4 addr show | grep -E 'inet ' | grep -v 127.0.0.1
ip route get 1.1.1.1 | head -1
```

Note the public interface (usually `eth0` on Hetzner CPX22) and the
public IPv4. Substitute it for `<IFACE>` and `<VPS_IP>` in commands below.

### PF-5. Disk space for pcaps

```
df -h /tmp /var/log
```

You need at least 1 GB free on `/tmp`. 5-minute capture at typical chat
load is 20-50 MB, but voice burst experiment can hit 100+ MB.

### PF-6. Install tcpdump if missing (read-only check first)

```
which tcpdump || apt-get install -y tcpdump
which ss || apt-get install -y iproute2
which jq || apt-get install -y jq
```

### PF-7. On Windows PC: Wireshark sanity

- Open Wireshark.
- Capture > Options > pick the active interface (Wi-Fi or Ethernet).
- In capture filter box, paste: `host relay.phntm.pro and tcp port 443`
- Click Start, then Stop after 3 seconds. Confirm packets appear and
  there is no permission error (npcap installed).

---

## Conventions for all experiments

- Every capture is timestamped: `phantom-<exp>-<side>-<UTC_HHMM>.pcap`
  e.g. `phantom-exp2-vps-1430.pcap`, `phantom-exp2-pc-1430.pcap`.
- VPS captures live in `/tmp/`, PC captures in `C:\pcaps\`.
- Always start the VPS capture FIRST, then the PC capture, then the
  client app. Stop in reverse order.
- After each experiment, IMMEDIATELY copy the VPS pcap off the box:
  ```
  scp root@relay.phntm.pro:/tmp/phantom-exp2-vps-1430.pcap C:\pcaps\
  ```
- All captures use snaplen 200 bytes (`-s 200`) -- enough for TCP+TLS
  headers, not enough to leak ciphertext bodies. This keeps files small
  AND makes the artifact safe to share for analysis.
- Session log: open Notepad, write down for each experiment: start time
  UTC, end time UTC, what device had VPN on/off, what client did, any
  on-screen errors. This is the Rosetta stone for correlating pcaps with
  app behavior later.

---

## Experiment 1: Baseline (VPN both sides)

Goal: prove the capture pipeline works AND establish that with VPN we
see zero RSTs in 5 minutes. This is the control.

### Setup

- Tecno phone: VPN ON (whatever VPN Vladislav usually uses, e.g. Mullvad
  / Proton).
- Emulator on PC: VPN ON (system-wide on the PC, so emulator inherits).
- Both apps logged in, sitting on chat list screen, idle. Do NOT send
  messages during baseline -- we want pure idle WebSocket.

### tcpdump on VPS

```
cd /tmp
tcpdump -i <IFACE> -s 200 -w /tmp/phantom-exp1-vps-$(date -u +%H%M).pcap \
  -G 300 -W 1 \
  'tcp port 443'
```

Flag explanation:
- `-i <IFACE>` capture on the public interface (e.g. `eth0`).
- `-s 200` snaplen 200 bytes per packet (headers only, no payload leak).
- `-w <file>` write raw pcap.
- `-G 300 -W 1` rotate after 300 seconds and stop after 1 file -- self-
  terminates after exactly 5 minutes, no risk of forgetting.
- `'tcp port 443'` BPF filter: only TLS to/from the public web port.

### Wireshark on PC

Capture filter: `host relay.phntm.pro and tcp port 443`
Save as: `C:\pcaps\phantom-exp1-pc-<HHMM>.pcap`. Stop after 5 min.

### Expected output

- Both pcaps show the WebSocket TLS streams alive for full 5 minutes.
- ZERO packets with TCP flags `R` (RST) in either capture.
- Periodic small packets (TLS App Data, ~80-200 bytes) every ~25-30s
  if app pings are configured, otherwise silence + occasional ACKs.

### How to extract evidence

On PC, in Wireshark, open `phantom-exp1-pc-<HHMM>.pcap`, paste into
the display filter:

```
tcp.flags.reset == 1
```

Expected: 0 packets. If this baseline already shows RSTs the VPN itself
is unstable and the rest of the experiment is invalid -- pick a different
VPN exit node and rerun.

---

## Experiment 2: Reproduce the 60s reset (no VPN)

Goal: capture the failure on the wire from BOTH sides simultaneously.

### Setup

- Tecno: VPN OFF (mobile data or home Wi-Fi, whichever previously
  reproduced the bug -- pick the worse one).
- Emulator: PC VPN OFF.
- Both apps logged in, idle on chat list.
- Have a stopwatch on phone. Note the wall clock time UTC when the
  WebSocket connects (Phantom should show "online" / "connected" badge).

### tcpdump on VPS (TWO captures in parallel)

Open two SSH sessions to the VPS.

Session A (egress / public side, between Hetzner edge and the world):

```
tcpdump -i <IFACE> -s 200 -w /tmp/phantom-exp2-vps-pub-$(date -u +%H%M).pcap \
  -G 300 -W 1 \
  'tcp port 443'
```

Session B (loopback / between Caddy and the relay process):

```
tcpdump -i lo -s 200 -w /tmp/phantom-exp2-vps-lo-$(date -u +%H%M).pcap \
  -G 300 -W 1 \
  'tcp port 8080'
```

Why both: if the public capture shows RST but the loopback capture
shows a clean WebSocket close (FIN with code 1000/1001), then Caddy
closed the public side while the relay was still happy -- that is H1
or H4 inside the relay. If public shows RST but loopback shows nothing
unusual at the moment of reset, then the close happened OUTSIDE the
VPS -- H2 or H3.

### Wireshark on PC

Capture filter: `host relay.phntm.pro and tcp port 443`
Stop after 5 minutes OR after the third visible reset, whichever comes
first.

### Expected output

- ~5 RSTs in 5 minutes, intervals 50-60 seconds.
- Each RST follows a period of low/no traffic.

### How to extract evidence

On PC pcap:

```
tcp.flags.reset == 1
```

For each RST row, click it, look at:
- Source IP: is it `<VPS_IP>` (server-initiated) or your local IP
  (client-initiated) or NEITHER (middlebox spoofing endpoint IP)?
- TCP SEQ: does it match the next-expected SEQ from the prior packet
  in the stream? Right-click the RST -> Follow -> TCP Stream and read.
- Was there a FIN before it? Filter:
  `tcp.stream eq <N> and (tcp.flags.fin == 1 or tcp.flags.reset == 1)`
- Was there a TLS Alert before it? Filter:
  `tcp.stream eq <N> and tls.alert_message`

On VPS public pcap, do the same. Then COMPARE timestamps:

- If RST appears in PC pcap at T but in VPS public pcap at T+50ms or
  later -> RST originated upstream of the VPS (impossible unless spoofed,
  or your clock is off) OR more likely the VPS NEVER saw that exact RST
  packet (it was forged by a middlebox between VPS and client) -- this
  is the smoking gun for H3.
- If RST appears in VPS public pcap at T and in PC pcap at T+30ms with
  TTL/IPID consistent with the VPS -> RST originated from VPS (Caddy or
  relay) -- H1 or H4.
- If neither pcap shows a RST sourced from VPS but PC pcap has a RST
  with source IP = VPS_IP -> middlebox forged it -- H3.

On the VPS loopback pcap:

```
tshark -r /tmp/phantom-exp2-vps-lo-*.pcap -Y 'tcp.flags.reset == 1' -V | head -100
tshark -r /tmp/phantom-exp2-vps-lo-*.pcap -Y 'tcp.flags.fin == 1' \
  -T fields -e frame.time -e tcp.srcport -e tcp.dstport -e tcp.stream
```

If loopback shows clean FIN at the same moment public shows RST -> Caddy
gracefully closed upstream (relay->Caddy) but the public socket got RST
on its side -- this is H1 plus a Caddy quirk where idle close becomes a
RST on the TLS side because of buffered un-ACKed data.

---

## Experiment 3: Voice burst behavior

Goal: see if behavior changes when the connection is NOT idle. If RSTs
disappear during burst then reappear after burst ends, the trigger is
idleness (H1, H4-keepalive). If RSTs happen DURING burst the trigger is
volume/MTU/fragmentation (H5) or rate-limit (H2).

### Setup

- Tecno: VPN OFF.
- Emulator: VPN OFF.
- Both apps in an open chat with each other.
- Prepare a voice message ~30 seconds long on Tecno. With current 8 KB
  chunking that produces ~50-80 envelopes -- exactly the burst we want.

### tcpdump on VPS

Same two-capture setup as Exp 2 (public + loopback), 5 minutes.

### Wireshark on PC

Same filter, 5 minutes.

### Procedure

- Start captures.
- Wait 60s idle (let ONE reset happen so we have a "before" reference).
- Send the voice message Tecno -> emulator.
- Watch emulator: does it receive it, partial, or never?
- Send another voice message immediately after.
- Wait 60-90s idle (let another reset happen, "after" reference).
- Stop captures.

### How to extract evidence

PC pcap, list TCP RSTs with timestamps:

```
# in Wireshark Statistics > Conversations > TCP, OR display filter:
tcp.flags.reset == 1
```

Note the wall-clock time of each RST. Compare to your session log:
- RSTs only in idle windows -> idleness-triggered (H1/H4).
- RST in the middle of the burst, packet just before it is large
  (>=1400 bytes) -> H5 MTU.
- RST in the middle of the burst, packet just before it is normal-sized
  -> rate-limit / DPI (H2/H3).

Also look at TCP Window Size graph (Statistics > TCP Stream Graph >
Window Scaling). If window collapses to 0 right before RST, the receiver
buffer overflowed -- relay is too slow to drain, that is a relay-side
bug, classify as H4.

Also Statistics > I/O Graph with filter `tcp.flags.reset == 1` to see
visually whether RSTs cluster in idle vs burst windows.

---

## Experiment 4: Caddy logs correlation

Caddy in JSON access-log mode logs every WebSocket connection close with
duration. Goal: cross-check pcap timestamps against Caddy's view.

### Locate logs

```
journalctl -u caddy --since "1 hour ago" --no-pager > /tmp/caddy-recent.log
```

Or if Caddy logs to a file (check `Caddyfile`):

```
ls -la /var/log/caddy/
tail -200 /var/log/caddy/access.log > /tmp/caddy-recent.log
```

### Filter to relay endpoint and WebSocket upgrades

```
grep -E '"path":"/ws"|websocket|upgrade' /tmp/caddy-recent.log \
  | jq -r '[.ts, .request.remote_ip, .duration, .status, .bytes_read, .bytes_written, .resp_headers."Upgrade"] | @tsv' \
  | tail -50
```

(If `jq` complains because logs are not JSON, fall back to plain
`tail -200 /var/log/caddy/access.log`.)

### What to look for

- For each pcap RST timestamp T, find a Caddy log line with `ts` close
  to T.
- `duration` field: if it equals 60.000s repeatedly, that IS Caddy's
  configured idle timeout firing -- H1 confirmed.
- `status`: 101 means upgrade was accepted; missing close-side log
  means Caddy thinks the connection is still open (then RST is
  external -- H2/H3).
- If duration is wildly variable (3s, 15s, 119s) and there is no
  fixed pattern -> Caddy is NOT the timer; something else closes.

### Optional: enable per-connection upstream log temporarily

Edit `/etc/caddy/Caddyfile` ONLY if comfortable, add inside the relay
site block:

```
log {
    output file /var/log/caddy/relay-debug.log
    format json
    level DEBUG
}
```

Then `caddy reload` (NOT restart -- reload does not drop connections).
Run experiment 2 again, grep `relay-debug.log` for `connection`,
`close`, `upstream`, `reverse_proxy`.

Revert the log block after experiments to avoid disk fill.

---

## Experiment 5: Relay logs correlation

Goal: see what the relay process itself thinks happened. If it logs
"client disconnected: connection reset" without ever logging a close
frame send, the relay was a passive victim, not the initiator -- H4 is
disproven, H1/H2/H3 still in play.

### Capture relay logs

```
docker logs phantom-relay --since 10m --timestamps > /tmp/relay-recent.log 2>&1
wc -l /tmp/relay-recent.log
```

### Filter for connection lifecycle

```
grep -iE 'connect|disconnect|close|reset|websocket|upgrade|error|timeout' \
  /tmp/relay-recent.log | tail -100
```

### Cross-correlate

For each RST in the PC pcap at time T, search the relay log for entries
within +/- 2 seconds of T:

```
grep "$(date -u -d '2026-05-04 14:32:00' +'%Y-%m-%dT%H:%M')" /tmp/relay-recent.log
```

Patterns:

- Relay logs `WebSocket close frame sent (code=1000)` BEFORE the RST
  in pcap -> H4: relay closed cleanly, RST is just FIN-then-RST race.
- Relay logs `connection reset by peer` AT the RST time -> relay was
  passive; close came from outside. Rules out H4.
- Relay logs `idle timeout exceeded` at exactly 60s intervals -> relay
  has its own keepalive timer that is too tight; that IS H4 and is
  trivially fixable in code.
- Relay logs nothing around RST time -> relay never noticed; Caddy
  closed upstream of relay. Strong H1.

---

## Decision tree -- interpreting results

Walk top to bottom. First match wins.

1. PC pcap shows RST sourced from VPS_IP, AND VPS public pcap shows
   the SAME RST egressing the VPS at matching timestamp:
   - Loopback pcap shows clean FIN at same moment + relay log says
     "close sent" -> H4 (relay closes connection).
   - Loopback pcap shows clean FIN + relay log silent + Caddy log
     duration == idle_timeout -> H1 (Caddy idle_timeout).
   - Loopback pcap shows nothing + Caddy log shows status 502/504
     near RST -> Caddy upstream broke; inspect relay health.

2. PC pcap shows RST sourced from VPS_IP but VPS public pcap does NOT
   show the relay or Caddy emitting RST (only sees a half-closed
   stream go quiet) -> H3: middlebox between VPS and client forged
   a RST at the client. This is the classic CGN/DPI fingerprint.
   Sub-check: TTL of the forged RST in PC pcap will differ from TTL
   of legitimate VPS packets -- expand IP header, compare.

3. RSTs cluster every 50-60s in idle, disappear during voice burst,
   reappear after -> idleness trigger. Cross-check Caddy idle_timeout
   value:
   - idle_timeout = 60s in Caddyfile -> H1 confirmed.
   - idle_timeout default 5m and timer is 60s -> NOT Caddy; either
     H2 (Hetzner conntrack default 60s on UDP, but TCP is 5d -- so
     unlikely) or H3 (carrier CGN, very common 30-60s for idle TCP).

4. RSTs happen mid-burst right after a packet >= 1380 bytes -> H5
   MTU/PMTUD blackhole. Confirm with `ip link show <IFACE> | grep mtu`
   on VPS (should be 1500). Try forcing MSS clamp on Caddy upstream
   or set `tcp_mtu_probing=1`.

5. RSTs appear at totally irregular intervals + no packet pattern +
   only on certain client networks -> H3 transit/carrier specific.
   Test from a 4th network (e.g. mobile hotspot from a different
   carrier) to confirm correlation with client network.

6. None of the above match cleanly -> capture is too short or filter
   too narrow; rerun Exp 2 with `-s 0` (full snaplen) and 10 minutes
   on a quiet test slot.

---

## Wireshark display filters reference

```
tcp.flags.reset == 1                          # all RSTs
tcp.flags.fin == 1                            # all FINs
tcp.analysis.flags                            # any anomaly
tcp.analysis.retransmission                   # retransmits (loss)
tcp.analysis.zero_window                      # receiver buffer full
tls.alert_message                             # TLS-level alerts
tls.handshake.type == 1                       # ClientHello (new conn)
tcp.stream eq 0                               # isolate one connection
tcp.len > 1380                                # large frames (MTU candidates)
ip.ttl < 50                                   # suspicious low TTL (middlebox)
frame.time >= "2026-05-04 14:30:00" \
  and frame.time <= "2026-05-04 14:35:00"     # time window
```

Useful menus:
- Statistics > Conversations > TCP -> see per-connection durations.
- Statistics > TCP Stream Graph > Time/Sequence (Stevens) -> visualize
  the moment of close.
- Statistics > I/O Graph -> overlay RST count vs throughput.
- Analyze > Expert Information -> Wireshark's own anomaly summary.

For tshark on the VPS (no GUI):

```
tshark -r file.pcap -Y 'tcp.flags.reset == 1' -V
tshark -r file.pcap -q -z conv,tcp
tshark -r file.pcap -q -z io,stat,5,'COUNT(tcp.flags.reset==1)tcp.flags.reset==1'
```

---

## Safety notes

DO:
- Run captures with `-s 200` to limit payload exposure.
- Rotate captures with `-G 300 -W 1` so they auto-stop.
- `caddy reload` (NOT `caddy restart`) if you must change config.
- Copy pcaps off the VPS and delete `/tmp/*.pcap` after analysis.

DO NOT:
- Run `tcpdump -s 0` (full payload) for more than a minute -- pcap can
  exceed 1 GB and you may capture authentication tokens in TLS
  handshakes (still encrypted, but principle of least data).
- Run captures simultaneously on multiple interfaces with no -G flag --
  forgetting to stop fills disk.
- Touch `iptables`, `nftables`, `ufw` rules. None of the experiments
  require it.
- `systemctl restart caddy` -- this drops every active connection
  including yours.
- `docker restart phantom-relay` during an experiment -- you will
  invalidate the capture.
- Share pcaps with payloads >0 in a public channel without scrubbing.
  `editcap --inject-secrets` is for secrets injection, not redaction;
  redact by re-running with `-s 100` on original capture if you have
  the live stream, otherwise treat as confidential.

---

## Glossary (RU)

- **TCP RST** -- TCP-сегмент с флагом RESET. Аварийный обрыв
  соединения. Отправитель говорит: "разговор окончен немедленно, без
  прощания". В отличие от FIN, никаких данных в буфере не остаётся.
- **TCP FIN** -- штатное закрытие. "Я закончил передавать, но готов
  слушать". Полное закрытие требует FIN с обеих сторон. Если после FIN
  кто-то шлёт RST -- это race condition или баг.
- **TLS Alert** -- сообщение TLS-протокола поверх TCP, которое
  объясняет причину закрытия (например, `close_notify` -- штатно,
  `internal_error` -- баг сервера). Если RST приходит без TLS Alert,
  TLS-уровень не успел попрощаться -- значит TCP закрыли снизу.
- **MTU** -- максимальный размер пакета на канале. На Ethernet 1500
  байт. Если по пути есть туннель (VPN, GRE, IPSec) -- меньше. Если
  пакет больше MTU и стоит флаг "не фрагментировать", роутер дропает
  его и шлёт ICMP "Fragmentation Needed". Если этот ICMP блокируется
  ("PMTUD blackhole"), большие пакеты молча теряются и соединение
  виснет.
- **CGN (Carrier-Grade NAT)** -- провайдер прячет тысячи клиентов за
  одним публичным IP. У CGN маленькие таблицы соединений, и idle TCP
  часто выселяется через 30-300 секунд -- middlebox шлёт RST с обеих
  сторон, изображая разрыв. Классический симптом WebSocket-ов на
  мобильных сетях.
- **DPI (Deep Packet Inspection)** -- middlebox смотрит на содержимое
  TCP-потока (даже зашифрованного -- по SNI, по таймингам), и может
  forge RST если "не нравится". Регулярные RST-ы на одной сети при
  отсутствии их на другой -- сильный признак DPI или CGN.
- **idle_timeout** -- параметр reverse-proxy (Caddy), сколько секунд
  держать WebSocket без трафика прежде чем закрыть. Дефолт у Caddy 5
  минут, но мог быть переопределён.
- **keepalive** -- маленький пинг по WebSocket или TCP, который
  держит соединение "живым" на промежуточных middlebox-ах. Если
  PHANTOM не шлёт keepalive чаще чем каждые 30s, любой CGN с
  60s-таймером убъёт соединение -- независимо от Caddy и relay.
- **Snaplen** -- сколько байт каждого пакета писать на диск. 200
  достаточно для TCP+TLS заголовков без полезной нагрузки.

---

## Session checklist (print and tick)

```
[ ] PF-1..PF-7 done, no errors
[ ] Caddyfile snapshot saved
[ ] Notepad session log open
[ ] Exp 1 captured (VPN both, 0 RST expected)
[ ] Exp 1 pcaps copied to PC
[ ] Exp 2 public + loopback + PC captured
[ ] Exp 2 pcaps copied
[ ] Exp 3 voice burst captured
[ ] Exp 3 pcaps copied
[ ] Caddy logs grepped, duration field noted
[ ] Relay logs grepped, lifecycle entries noted
[ ] Decision tree walked, hypothesis selected
[ ] /tmp/*.pcap deleted from VPS
[ ] Findings written into 05-network-findings.md
```
