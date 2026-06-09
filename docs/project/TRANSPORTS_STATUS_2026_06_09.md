# PHANTOM Transport Status — Direct / Reality / Tor / REST

**Document type:** Standalone evidence-baseline status report for engineers, architects, future contributors, and future-self diagnostic sessions.

**Status as of:** 2026-06-09

**Author / locking authority:** Vladislav-locked via external architect verdict on three architect opinions (Claude.md, Gemini, ChatGPT). See section 8.

**Wording bounds (apply throughout):**

- DO NOT write "Reality fixed on Android" (until standalone test APK + Stage 1 acceptance passed in field).
- DO NOT write "Long-poll is the answer" (until Council-locked design, until field-validated through Reality + Tor SOCKS paths).
- DO NOT write "Direct WSS broken forever" (still primary for healthy networks; T2/Mode 2 findings apply to RU mobile specifically).
- DO NOT write "Vision splice race is THE root cause" (it is leading hypothesis; consistent with wire evidence + Xray-core issue #4878 + Android-explicit splice whitelist; mechanism documented but not yet field-verified via Stage 1 test matrix).
- DO NOT close sub-track scope (locked, not started).

---

## 0. Executive Summary

| Transport | Status | Leading problem |
|---|---|---|
| **Direct WSS** | Demoted as primary realtime on RU mobile | Long-connection uplink fails on ~5 KB / ~45 sec due to carrier-side mechanism (Mode 2 + byte-budget class). Both symptoms appear together, not separate bugs. |
| **Reality (VLESS+XTLS-Vision)** | Blocked at wire-level baseline on Android | Android libXray (gomobile) does not deliver multi-segment outer Reality ClientHello — sends one TCP segment and stops. WSL2 Linux Xray same config = PASS. Leading hypothesis: XTLS-Vision splice handoff race in `CopyRawConnIfExist` on Android gomobile build. |
| **Tor v3 onion (Ghost)** | Works as fallback | Slow bootstrap on RU mobile (TSPU 16-KB curtain). Bypassed since Stage 5E via Reality outer wrapping. Powerless as primary realtime due to latency. |
| **REST short-poll** | Production-validated, working fallback | Not realtime (polling delay). |

**Architectural invariant (Vladislav-locked 2026-06-06):** Privacy modes (Standard / Private / Ghost) are NOT different feature sets. Text + voice notes + 1:1 calls + group calls + video must work in ALL three. Modes differ ONLY in (a) transport path and (b) speed-vs-stealth trade-off. This means: realtime via each path must work, otherwise user-visible functionality degrades.

**Locked direction (Vladislav 2026-06-09):** Two-track plan.

- **Trek 1 — RC-LIBXRAY-REALITY-WIRE1** (urgent, ~1 week): standalone libXray test APK + 4-variant config matrix (Vision baseline / `flow=""` plain VLESS / `network=xhttp` / `network=httpupgrade`) to discriminate Vision splice race hypothesis. Acceptance: `/health` 200 across ≥50 consecutive fresh-process sessions + server-side tcpdump shows multi-segment ClientHello with PSH flags.
- **Trek 2 — SHORT-CYCLE-LONGPOLL1** (architectural, parallel, ~3-6 weeks): Matrix/MTProto-style HTTP/1.1 long-poll substrate (~20 s hold, ~3 KB cap, immediate reconnect) for realtime across all three privacy modes. Carries call signaling; WebRTC media via self-hosted TURN coturn. Council needed before code work.

The tracks are independent. Trek 1 success → Reality realtime works in current architecture. Trek 1 failure → Trek 2 = architectural escape (realtime via any transport substrate).

---

## 1. Architectural Context

### Current production transport chain

`HybridRelayTransport` orchestrates the following chain (priority order):

```
1. Direct WSS (wss://relay.phntm.pro/ws via Caddy on :443)
   ↓ if fails / probe-demoted
2. Reality (VLESS+XTLS-Vision via libXray SOCKS5 → :8443 → unwrap → Caddy → relay)
   ↓ if fails
3. Tor v3 onion (via embedded Tor → onion endpoint relay)
   ↓ always-on safety net
4. REST short-poll (HTTP to :443 → /relay/poll + /relay/send + /relay/ack-deliver)
```

`TransportManager` selects first working transport; persists `lastWorkingTransport` hint (sticky only for primary `strategy.chain.first()`); fallback successes do NOT hoist into primary slot (per `feedback_sticky_fallback_hint_2026_05_27` rule after PR-RECV-DIAG1 v1.7 bug).

### Production deployment

- **Edge:** Caddy on `:443` (Hetzner FSN1 phantom-relay-01)
- **WS handler:** Rust relay at `services/relay/src/routes.rs`
- **Reality endpoint:** xray-core container `phantom-xray` on `:8443`
- **Reality outer config:** SNI `www.microsoft.com`, anti-probe fallback `dest=www.microsoft.com:443`
- **Reality inner unwrap:** routes to `caddy:443` for messenger traffic
- **REST endpoints:** under Caddy `:443`
- **Tor:** onion service `zmdrxlrkd7iv7ozvdl5nlhctsxgx6eyuqionp6xzriolymy3m6ioloyd.onion`

### ADRs relevant to transports

- ADR-019 Stage 5E Xray VLESS+REALITY deployment
- ADR-028 Direct Stability Architecture Intent (locked 4-layer reliability architecture)

### Mini-locks and outcomes (track history)

- RC-DIRECT-WS-DEATH1 Phase 1 (closed PR #265-#269) — Ktor adapter ruled out as primary Mode 1/2 cause
- RC-DIRECT-WS-DEATH1 Phase 2 (PR #270-#272) — PCAPdroid wire-correlation: Mode 1 = H-A confirmed (return-path loss), Mode 2 pp=0 sub-case = H-A confirmed, pp=1 = TCP-layer ambiguous
- RC-DIRECT-STABILITY1 §1-§14 (open) — Arm A through Arm G + T2

---

## 2. Direct WSS — Investigation Deep-Dive

### Origin

Direct WSS = first realtime transport designed. Stack:
- Client: OkHttp via Ktor `KtorRelayTransport` with `pingInterval(15s)`, `readTimeout(60s)`
- Wire: TLS 1.3 over TCP to `relay.phntm.pro:443`
- Edge: Caddy (Go TLS)
- App: Rust relay WebSocket handler

Production-validated on Tecno Wi-Fi + Wi-Fi Иркутская on emulator. Works healthy on clean networks.

### Mode 1 — Wi-Fi 8-pong rhythm

**Symptom:** On Wi-Fi (e.g. Ростелеком home), session lives ~120 seconds, returns exactly 8 successful pongs in response to 15-sec cadence client pings, then OkHttp records ping-timeout → `WsSessionEnded` → REST fallback.

**Evidence:**
- Phase 1 (PR #266 Arm B): telemetry showed Ktor adapter does NOT mask control frames
- Phase 2 (PR #270 + #272): PCAPdroid wire-correlation on Tecno Wi-Fi — 11 Mode 1 deaths, **0 inbound TLS records** in window ±2 sec around relay-side `ws_protocol_pong_sent`
- Verdict (Vladislav-locked): **H-A confirmed return-path loss** on pp=0 sub-case. Pong from relay does not physically reach device's TLS stack.

### Mode 2 — Tele2 LTE severe 0-1-pong rhythm

**Symptom:** On Tele2 LTE, session lives ~30-45 seconds. First session often returns exactly 0 pongs (`pp=0` sub-case); subsequent sessions return exactly 1 pong (`pp=1`). Then same ping-timeout → reconnect storm.

**Evidence:**
- Arm C (PR #277): ping interval matrix 10s/20s/30s — lifetime ≈ 3 × ping_interval linearly. **Cadence = detection timing, not fix lever.** Production pingInterval(15s) stays.
- Arm D (PR #279-#283): relay heartbeat-echo handler + client sender. 39 echo_sent / 0 echo_received on Tele2 LTE. App-data Text heartbeat does NOT survive Mode 2. Relay saw 20 ws_protocol_ping_received → 20 pong_sent. **Control-frame path worked, app-data path silent** = control/application asymmetry at app-layer.
- Arm A.2 (PR #285-#291): stunnel bypass on VPS-side `:8444`. 21 ws_open / 20 ws_failure / 40 echo_sent / 0 echo_received. **Mode 2 byte-perfect identical through two different TLS stacks** (Caddy Go TLS + stunnel OpenSSL 3.3.7). **Caddy strongly loses priority as proximal cause.**

**Vladislav-locked Phase 2 verdict:**
- Mode 2 pp=0 sub-case = return-path loss
- Mode 2 pp=1 sub-case = TCP-layer ambiguous (relay-side TLS/WS delivery-stall candidate not excluded by Tier 1 evidence)
- Edge stack is NOT proximal cause (proven via Arm A.2 stunnel bypass)
- App-data Text heartbeat does NOT survive Mode 2 (proven via Arm D)
- Cadence is NOT a fix lever (proven via Arm C linear matrix)
- 5-hypothesis open set: OkHttp writer / Caddy-TLS / carrier / interaction / cumulative-bytes-per-TCP

### T2 — byte-threshold class confirmation

**Field run 2026-06-05** (Tele2 LTE Иркутская, PR #292 T2SlowPostDiag):

- Android `T2_SLOW_POST_chunk_sent total_sent=40960` (all 8 chunks × 5120 bytes queue-accepted via `sink.flush()`)
- Relay `event=slow_post_chunk_received total_bytes=5120` (12.5% received)
- Android SocketTimeoutException 60s after seq=8
- Relay `event=slow_post_aborted reason="read_error" elapsed_ms=163251`

**What was proven:**
- Byte-threshold class **directionally confirmed** on Tele2 LTE
- Observed upload cutoff ~5 KB, significantly earlier than documented `net4people/bbs #490` 14-32 KB range (regional variance, aggressive end of documented spectrum)
- Concurrent short-POST bodies (3× prekey_publish + 1× auth_session) REACHED relay and server-side processed
- Client-side response reception NOT uniform: `/auth/session` 200 OK received, `/prekeys/publish` retries `prekey_publish_retry reason=SocketTimeoutException elapsedMs=60580`
- **Response-direction failure** on long POST → byte-budget class **extends to response path** on long-held connections

**External corroboration (Claude.md research):**
- Cloudflare (June 26, 2025 blog) reported since June 9 2025 RU ISPs throttle so users "load only the first 16 KB of any web asset," with connection cut "just after the first few packets (10 to 14)"
- "The throttling affects all connection methods and protocols, including HTTP/1.1 and HTTP/2 on TCP and TLS, as well as HTTP/3 on QUIC"
- net4people #490 documents per-connection freeze: TCP packets from server stop after ~14-32 KB; the censor does NOT RST, it silently freezes — exactly our "server Pongs never arrive" return-path symptom
- Contributor gl1tchdev confirmed via iperf3: "blocking occurs at the lowest level, at the pure TCP/UDP level… after the first packet, the connection… will be frozen"

**Architectural implication:** Bare Direct mobile uplink demoted as primary realtime path on RU mobile. Long-connection uplink **on any payload type** structurally untrustworthy. This is NOT a WS-specific finding — it subsumes Arm A.2 and Arm D WS-specific verdicts.

### Current state of Direct

- Production code unchanged (Direct WSS remains first chain element)
- WORKING_RULES rule 8 narrow transport regression gate: PRs that change chain selection / reconnect lifecycle / network-change handling / probes / WS-REST fallback MUST include Tele2 LTE smoke test before merge
- PR-WS-HEALTH-STATE1 design (3.2b adaptive validation) is PAUSED pending RC-DIRECT-STABILITY1 evidence
- CHIP1 (UI new-messages chip) paused for same reason

---

## 3. Reality (VLESS+XTLS-Vision) — Investigation Deep-Dive

### Origin (Stage 5E)

PR-Stage 5E (2026-05-07): deployed production Xray VLESS+REALITY endpoint on phantom-relay-01 Hetzner. Container `phantom-xray` listens on `:8443`, proxies unwrapped traffic to Caddy (`:443` via Docker internal network 172.18.0.7).

Embedded constants in `OperatorXrayConfig.kt`:

```
SERVER_HOST = "65.108.154.152"
SERVER_PORT = 8443
SNI = "www.microsoft.com"
PUBLIC_KEY = "kDRyYpqpNGT_2IEbJ2pCxpkrinGBhokiNpO4cFOM6w0"
SHORT_ID = "ab580a24c7a1e293"
UUID = "09c6fd0e-dc89-4659-a7c3-9c6476590a6a"
```

Client uses embedded **libXray** (gomobile-bound Xray-core in Android AAR). Current vendoring ref `9a86646da8d8` (2026-05-12), Xray-core `v1.260327.0` (= 26.3.27 stable matching server).

### Initial validation 2026-05-07

Stage 5E.B.1-5 completed: **TSPU 16-KB curtain bypass confirmed on Tecno МТС without VPN.** Reality+SOCKS+WSS works in production on clean configurations.

### Arm G chronology — v1 through v11

Arm G = "Reality-tunneled WS heartbeat" diagnostic. Question: does WS heartbeat through Reality survive what kills bare Direct WSS?

**v1-v6 (2026-06-05 / 06):** mini-lock landed (PR-G1), code shipped (PR-G2), fixup commits for libXray vendoring + per-session xrayService restart. Field testing failed: 20+ sessions all `challenge_threw InterruptedIOException timeout`.

**v7 (2026-06-06):** Disabled OkHttp auto-Ping, continue-on-missed-echo, cumulative-byte tracking, bounded clean Close, lock-state anchor. Field run: 54 Wi-Fi + 66 Tele2 sessions — all challenge_threw timeout, 0 ws_open.

**v8 (2026-06-08):** `/health` preflight via SOCKS before challenge, `ws_about_to_connect` log, EventListener phases (`op=preflight phase=connectStart|secureConnectStart|...`), auth-abort cap (5 consecutive → break), Xray loglevel=debug for Arm G config. Field run: 5 sessions, all preflight + challenge timeout on `secureConnectStart` phase.

**v9 (2026-06-08):** Wide logcat `*:V` surfaced `GoLog` tag — libXray Go-side stdout/stderr forwarded to Android logcat via gomobile binding. **First session (s=1) in fresh process completed full Reality handshake:**

```
dialing TCP to 65.108.154.152:8443
tunneling request via 65.108.154.152:8443
XtlsFilterTls found tls client hello! 517   (inner curl→relay TLS)
XtlsFilterTls found tls 1.3! 1163 TLS_AES_128_GCM_SHA256
CopyRawConn splice
preflight_result status=200 elapsed_ms=656
auth_done auth_elapsed_ms=828
```

Then WS upgrade fails at +10s with `response_code=null`. Subsequent sessions s=2-5 degraded: dial succeeds but `XtlsFilterTls` does NOT appear, `/health` timeout.

**Council resolution 2026-06-08:** amend mini-lock decision #2 from "hybrid lifecycle" to "persistent-single-instance per Arm G run". Model: per-session restart causes Bug B (Reality handshake degradation in s≥2); persistent Xray fixes. **Procedurally correct, model NOT wire-validated.**

**v10 (2026-06-08):** persistent Xray applied (one `Logger started`, one `Logger closing`, fixed socksPort=44287 all sessions). Field run: **REGRESSION**. Even s=1 fails Reality handshake. Persistent Xray hypothesis refuted by very next experiment.

**Critical wire-level capture 2026-06-08:** Server-side tcpdump on `:8443` during v10 run showed 8 connection attempts from Tecno `185.224.99.244`:

```
SYN (mss 1452)
SYN-ACK
ACK
seq 1:1441 length 1440 Flags [.]  ← no PSH flag, single TCP segment
server ACK 1441
~10-13 sec silence from Tecno (no continuation seq, no retransmits)
server FIN seq=1 ack=1441 (zero bytes of TLS response)
server retransmits FIN 8+ times
```

**TLS record header parsing of Tecno first segment:**

```
16 03 01 07 1a  = TLS handshake record, TLS 1.0 legacy version, length 0x071a = 1818 bytes
01 00 07 16     = ClientHello length 0x000716 = 1814 bytes
03 03           = TLS 1.2 version field
54 8c 60 c5...  = random (32 bytes)
...
```

Tecno **declared** record length 1818 bytes, **transmitted** 1435 bytes of body (1440 - 5 header). Missing **383 bytes** of outer Reality ClientHello body, never sent.

**Refutation pipeline 2026-06-08** — all cheap hypotheses ruled out:

- **fail2ban / iptables / DOCKER-USER chains** — clean, Tecno IP not banned
- **Hetzner Cloud Firewall** — panel screenshot "No Firewalls have been applied to your server yet"
- **Hetzner default BLOCKED PORTS** — only outgoing 25 + 465 (mail)
- **Bare TCP probe** from Tecno via `adb shell nc -w 5 65.108.154.152 8443 < /dev/null` → RC=0 (TCP layer fine)
- **WSL2 Linux Xray re-verify 2026-06-08 23:23** — same config, through felix WSL2 Ubuntu → `HTTP/2 200 {"status":"ok"}`, full XTLS-Vision splice
- **Server-side tcpdump on WSL2 PASS** wire bytes:

```
seq 1:1461 length 1460 Flags [P.]     ← PSH flag
seq 1461:1728 length 267 Flags [P.]   ← PSH flag, continuation +61 microseconds
server ACKs ramp 2897 → 5793 → 8332 → 11623
server returns ~2896-byte ServerHello + Cert segments
... full Reality duplex ...
FIN
```

WSL2 sends **2 TCP segments totaling 1727 bytes with PSH flags on both**. Tecno sends **1 segment 1440 bytes without PSH**.

**v11 — MSS-clamp diagnostic 2026-06-08 15:00:** iptables `-t mangle -I FORWARD 1 -p tcp -s 172.18.0.7 --sport 8443 --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1200`. Tecno first segment changed `1440 → 1188 bytes` (rule confirmed effective). Continuation segment STILL not arriving. **Decisive: MTU/MSS black-hole RULED OUT.** Bug is size-independent. libXray-Android sends exactly one segment regardless of size.

### Locked verdict (Vladislav-approved wording, do NOT rephrase)

> "Tecno/Android libXray начинает отправлять outer Reality ClientHello, но после первого TCP-сегмента прекращает передачу. Сервер получает начало TLS-record, видит что данных должно быть больше, ждёт продолжение, не получает его и закрывает соединение FIN без ServerHello."

**Vladislav-explicit softness:** the location WITHIN libXray/gomobile/Go-runtime/native-bridge/write-loop where the stall occurs is NOT pinpointed. Correct framing: "the construct of the TLS record header is plausible, but the Android/gomobile write path does not complete the multi-segment send."

### Current state of Reality

- Production code unchanged (Xray for Private mode remains in chain)
- On Wi-Fi and Tele2 LTE, Android Reality realtime de-facto does not work (multi-write stall)
- WSL2 Linux Xray on test machines works (Linux client usable as oracle for server validation)
- v9 evidence shows libXray Reality CAN succeed once — behavior non-deterministic
- Sub-track `RC-LIBXRAY-REALITY-WIRE1` scoped (NOT started)

---

## 4. Tor + REST — brief

### Tor v3 onion (Ghost mode)

- Stage 1 deployed 2026-05-04, onion address `zmdrxlrkd7iv7ozvdl5nlhctsxgx6eyuqionp6xzriolymy3m6ioloyd.onion`
- Test 6 (2026-05-05): bootstrap on emulator and under VPN OK (~30 sec). On Tecno МТС bare without bridges — bootstrap stuck at 14% due to TSPU 16-KB curtain attack
- Stage 5D (non-Hetzner bridges) added 2026-05-06 — partial success
- Stage 5E.B.5 success 2026-05-07: Tor through Reality tunnel works on МТС

**What Tor does NOT give:** realtime for voice/calls (latency, jitter). Text works, voice notes upload work (slow), 1:1 calls with WebRTC — too slow for voice.

### REST short-poll fallback (D-track)

- D0r: relay-side endpoints `/auth/session`, `/relay/poll`, `/relay/send`, `/relay/ack-deliver`, `/prekeys/publish`, `/prekeys/status`, `/media/v3/...`
- D1/D1b/D1c/D1d: client orchestrator (`RestStateMachine`, `HybridRelayTransport.transitionToRest`)
- M1w/M2: media upload chunked via native OkHttp (fresh client per call) — production-validated on Tele2 LTE Иркутская

**What REST does NOT give:** push notifications in realtime. Polling with delay. Works for voice notes / 1:1 calls (via `/media/v3` + ratchet envelopes for signaling), but latency higher.

---

## 5. Evidence Matrix — Proven / Refuted / Open

### Proven (evidence-validated)

| Finding | Evidence |
|---|---|
| Tele2 LTE long-uplink fails on byte-budget (~5 KB) | T2 PR #292/#293 |
| Mode 1 on Wi-Fi = return-path loss (pong does not reach device TLS stack) | Phase 2 PCAPdroid PR #272 |
| Mode 2 pp=0 = return-path loss | Phase 2 PR #272 |
| Edge stack (Caddy vs stunnel) is NOT proximal cause of Mode 2 | Arm A.2 PR #291 |
| Ping cadence = detection timing, not fix lever | Arm C PR #277 |
| App-data Text heartbeat does NOT survive Mode 2 | Arm D PR #283 |
| Reality server + config + external path work for Linux clients | 2026-06-07 WSL2 PASS + 2026-06-08 WSL2 re-verify |
| Android libXray multi-segment Reality ClientHello write stalls on one TCP segment | 2026-06-08 v10/v11 wire pcap |
| MTU/MSS black-hole is NOT the cause of Reality fail on Android | 2026-06-08 v11 MSS-clamp test |

### Refuted (by evidence)

| Hypothesis | Refutation evidence |
|---|---|
| OkHttp internal mis-handling = primary cause Mode 1/2 | Phase 1+2 — no Mode 1/2 death has inbound TLS records at expected anchor |
| Per-session Xray restart = root cause Reality degradation | v10 persistent Xray — same wire pattern as per-session |
| Inference "Android libXray = Linux Go-uTLS equivalent on wire" | 2026-06-08 wire pcap comparison: WSL2 2 segments / Tecno 1 segment |
| Server-side rate-limit / fail2ban ban of Tecno IP | 2026-06-08 fail2ban check + iptables + Hetzner panel |
| Hetzner Cloud Firewall blocking :8443 | Panel screenshot "No Firewalls applied" |
| Windows xray.exe = working oracle for Reality testing | 2026-06-07 Windows 1440 vs Linux 517 ClientHello mismatch |
| Caddy as proximal cause of Mode 2 | Arm A.2 stunnel bypass byte-identical |
| Byte-threshold class = exact `net4people #490` 14-32KB | T2 observed ~5KB (significantly earlier) |
| Smaller uTLS fingerprint as production fix for Reality stall | v11 MSS-clamp: bug is size-independent + pre-PQ fingerprints became DPI-tell (CVE-2026-26995 for `HelloChrome_120` non-PQ) |
| QUIC/HTTP3 as escape from byte-budget | Cloudflare June 2025: "affects HTTP/3 on QUIC" + ValdikSS: RU TSPU blocks QUIC since March 2022 (port 443, version 1, ≥1001 byte payload) |
| SSE as escape from byte-budget | Cloudflare: "affects all connection methods and protocols" — SSE = long-lived HTTP response, same per-TCP cutoff |

### Open (no evidence-class resolution)

| Question | What's needed |
|---|---|
| Where exactly in libXray/gomobile/runtime does the write stall? | Source-level investigation libXray AAR + standalone test APK + Trek 1 Stage 1 test matrix |
| Why did v9 s=1 ONE-OFF succeed on same gomobile-libXray? | Investigation timing / state / fresh-process condition; consistent with scheduling-race signature per claude.md analysis |
| Carrier-side Mode 2 mechanism — which layer loses return-path? | Carrier-side cooperation (unlikely) or deeper wire capture; T2 evidence suggests not addressable in PHANTOM scope |
| Mode 2 pp=1 sub-case TCP-layer mechanism | More PCAPdroid captures + relay-side timing analysis |

---

## 6. Locked Two-Track Plan

### Trek 1 — RC-LIBXRAY-REALITY-WIRE1 (urgent, ~1 week)

**Leading hypothesis (NOT proven):** XTLS-Vision splice handoff race in Xray-core `proxy/proxy.go` `CopyRawConnIfExist` on Android gomobile build.

**Mechanism per claude.md (consistent with our wire evidence, mechanism documented in Xray-core issue #4878 + PR #5737 review comments):**

- `VisionWriter.WriteMultiBuffer` writes outer Reality ClientHello bytes to socket
- When TLS 1.3 detected, sets `CanSpliceCopy = 1` → triggers `CopyRawConnIfExist` zero-copy splice via `TCPConn.ReadFrom`
- **Race:** if `CanSpliceCopy = 1` set BEFORE the framed `WriteMultiBuffer(mb)` of ClientHello fully drained to kernel → splice path takes over a socket that still has unflushed bytes → trailing bytes (our ~383) ABANDONED
- PR #5737 made ordering deterministic for most cases, but maintainer yuhan6665 in review explicitly noted: multiple readers/buffers can defeat fix
- Old `time.Sleep(time.Millisecond)` band-aid that previously masked the race — REMOVED in current main
- Android explicitly whitelisted alongside Linux in `CopyRawConnIfExist`:
  `if runtime.GOOS != "linux" && runtime.GOOS != "android" { return readV(...) }`
- gomobile timing differences (JNI-initialized runtime + `DialerController` callback latency for `VpnService.protect(fd)`) shift the flush/splice interleaving → "rare on desktop" becomes "first-segment-only on Android"
- Non-determinism (v9 s=1 once PASSed, then degraded) = signature scheduling race, NOT static config error

**This explains why v6/v7/v8/v10 static-config fixes did NOT help:** the race is scheduling/runtime-level, not addressable by application-layer config.

**Stage 1 stop-the-bleeding test matrix** (run on standalone libXray test APK to discriminate libXray bug vs Arm G harness interaction):

| Variant | Config | Expected wire | What it tests |
|---------|--------|--------------|---------------|
| Baseline | `flow=xtls-rprx-vision`, `network=tcp` | 1 segment 1440b stall (current behavior) | Confirms baseline reproducibility on clean APK |
| **Drop Vision** | `flow=""`, `network=tcp` (plain VLESS) | Multi-segment full ClientHello + ServerHello | Splice-race hypothesis: removing Vision removes race entirely |
| **xhttp transport** | `flow=""`, `network=xhttp` | HTTP-framed stream, no raw TCP splice | net4people #490 confirms xhttp works on RU mobile 2026 |
| **httpupgrade** | `flow=""`, `network=httpupgrade` | Same class as xhttp | Fallback option |

**Server-side coordination required for Stage 1:**

- xhttp/httpupgrade tests need temporary inbound types in `phantom-xray` config OR second xray instance on `:8444`
- Production `:8443` inbound (current Vision+TCP) NOT modified — keeps existing Linux clients working
- Server config diff goes through standard deploy review

**Acceptance for Trek 1 Stage 1 (per claude.md):**

- `/health` returns 200 reliably across **≥50 consecutive fresh-process sessions**
- Server-side tcpdump shows **full ClientHello in 2+ TCP segments with PSH flags on each**
- No "single 1440-byte segment + 10s silence + server FIN seq=1" pattern

**Stage 2 (this month, AFTER Stage 1):**

- Fork libXray; force Android branch to `readV` (no splice) in `CopyRawConnIfExist`
- Pin specific PQ-capable uTLS fingerprint ID (not generic `chrome` alias, which is moving-target; Google-SNI + chrome combo detectable per v2rayNG issue #5406; pre-PQ aliases became DPI-tell per CVE-2026-26995)
- Audit `DialerController` / `VpnService.protect` path for buffering between protect and first write
- Keep Xray-core ≥v26.5.9 but do not expect bump alone to fix

**Stage 3 (next quarter — overlaps with Trek 2):** Replace bare WSS with short-cycle substrate. (This IS Trek 2.)

**Stage 4 (ongoing):**

- Transport fallback ordering: `xhttp → httpupgrade → ws` negotiated per network
- Freeze detection (no bytes for N seconds when data expected) → rotate connection/endpoint
- Monitor net4people #490/#603 and XTLS issue tracker for new TSPU behaviors

### Trek 2 — SHORT-CYCLE-LONGPOLL1 (architectural, ~3-6 weeks, parallel)

**Design invariant from claude.md:** Byte-budget operates at pure TCP/UDP layer (gl1tchdev iperf3 result + Cloudflare June 2025 + net4people #490). No application protocol survives long-held connection on RU mobile. **Single structural fix:** every connection (a) carries fewer than byte-budget bytes from server before closing, AND (b) lives shorter than connection-age kill. This is rapid short-request cycle.

**Concrete parameters (per claude.md):**

- Server-hold window ≤ 20-30 seconds max
- Response cap ≤ 3 KB (conservative from observed ~5 KB Tele2; documented range 14-32 KB elsewhere)
- Immediate reconnect after response/timeout
- Server-side coalescing wait ~50 ms (per MTProto pattern)
- Adaptive: lengthen toward 25s during idle, shorten during active conversation
- Battery cost: ~3 reconnects/minute at 20s cycle — mitigate with adaptive backoff + coalesced control-plane traffic

**Carries across all 3 privacy modes:**

- **Standard:** direct to Caddy front end (:443)
- **Private:** identical HTTP client through local SOCKS inbound of Reality tunnel (after Trek 1 fix)
- **Ghost:** through Tor's SOCKS inbound

**Voice/calls (call signaling + media):**

- Signaling (SDP, ICE) small + bursty → rides same short-cycle long-poll
- Media via WebRTC to self-hosted coturn TURN relay
- Private mode: TURN traffic carried inside Reality tunnel
- Ghost mode: TURN traffic over Tor (higher latency, acceptable for privacy tier)
- TURN sees only ciphertext (DTLS-SRTP not decrypted by TURN)

**Why NOT SSE / HTTP/2 / QUIC (per claude.md):**

- SSE = long-lived HTTP response → subject to same byte-budget freeze (Cloudflare: "affects all connection methods and protocols")
- HTTP/2 multiplexing doesn't help: byte-budget measured on underlying TCP connection not per-stream
- QUIC: ValdikSS confirmed RU TSPU blocks QUIC since March 2022 + Cloudflare June 2025 confirmed QUIC subject to same throttle + Snowflake DTLS/JA3 filtering since 2026-03-30
- **HTTP/1.1 long-poll = simplest under per-connection byte cap, exactly what MTProto's HTTP transport does**

**Production messengers using same pattern (per claude.md research):**

- **Telegram MTProto HTTP transport:** "special long poll RPC query… which transmits maximum timeout T. If server has messages… returned immediately; otherwise wait state until T seconds." Server adds "additional wait time (50 milliseconds) against eventuality that the server will soon have more messages."
- **Matrix `/sync`** (and sliding sync MSC4186): configurable `poll_timeout` + additional `network_timeout`; server returns immediately on data or empties at timeout.
- **SimpleX SMP protocol:** per-queue notification subscriptions (`NSUB`) with separate notifier keys and short server responses, relying on push for background delivery.

**Component touchpoints (NOT TO START NOW; require Council before code):**

- New relay endpoint `/relay/longpoll` (server-side `services/relay/src/routes.rs`)
- `MessagingService` receiver loop (rewrite, replaces current WS-poll loop in `HybridRelayTransport`)
- `HybridRelayTransport` mode selection adjusted (long-poll first, WS as opportunistic)
- `WsHealthMonitor` / 3.2b adaptive validation design (may become obsolete or repurposed for Direct WSS healthy-network optimization)
- TURN coturn deployment + Reality / Tor reachability validation
- Per-carrier byte-budget runtime probe (auto-tune `poll_timeout` per detected carrier)

### Why Trek 1 + Trek 2 are independent

- If Trek 1 succeeds → Reality realtime works through current `wss://relay.phntm.pro/ws` flow → Trek 2 still valuable as feature-parity insurance for hostile networks where even Reality doesn't establish
- If Trek 1 fails → Trek 2 = architectural escape (realtime through any transport via short-cycle long-poll, regardless of underlying WS or Reality state)

### Time-boxes

- Trek 1 Stage 1: 1 week from 2026-06-09 = **2026-06-16**
- Trek 2 Council lock: 2 weeks from 2026-06-09 = **2026-06-23**
- Trek 2 design + code: 6 weeks from Council lock

---

## 7. External Architect Verdict — Source Citations

Three architect opinions were consulted by Vladislav 2026-06-09:

- **Claude.md** (favored): full text in `C:\Users\felix\Downloads\claude .md`. Strongest analysis. Identifies XTLS-Vision splice race as leading hypothesis, cites Xray-core issue #4878 + PR #5737 with mechanism detail, notes Android-specific splice whitelist, correctly rejects QUIC/SSE as magic-fix, anchors short-cycle long-poll in MTProto/Matrix/SimpleX precedent.

- **Gemini** (tactical, partial): `C:\Users\felix\Downloads\Гемини.rtf`. Useful: VLESS-WS option, long-poll direction. Weak: "smaller fingerprint as production fix" rejected — MSS-clamp already proved size-independent, pre-PQ fingerprints are now DPI-tell.

- **ChatGPT** (off-track for current stage): `C:\Users\felix\Downloads\chat gpt.docx`. Valuable backlog (sticky `lastWorkingTransport`, Tor stuck, MAC-error/ack_deliver) but does NOT address Direct byte-budget or Reality wire stall.

External architect (Vladislav-consulted) endorsed claude.md direction; recommended Trek 1 + Trek 2 plan with guardrails: standalone libXray test APK FIRST, NO production code changes during this scoping session, MASTER_TIMELINE / PROJECT_LOG amend deferred until repo head clean and synced.

---

## 8. Reference — Files / Commits / PRs

**Mini-locks and outcomes (in repo):**

- `docs/tracks/rc-direct-stability1.md` — main mini-lock
- `docs/tracks/rc-direct-ws-death1.md` — Phase 1+2
- `docs/project/MASTER_TIMELINE_2026.md` — full history (last-updated 2026-06-08 v10/v11 verdict)
- `docs/PROJECT_LOG.md` — session journal

**ADRs:**

- ADR-019 Stage 5E Xray VLESS+REALITY (deploy/xray/)
- ADR-028 Direct Stability Architecture Intent

**Code touchpoints for Reality investigation:**

- `shared/core/xray/src/commonMain/kotlin/phantom/core/xray/XrayServiceFactory.kt` — interface
- `shared/core/xray/src/androidMain/kotlin/phantom/core/xray/XrayServiceFactory.android.kt` — libXray binding
- `shared/core/xray/src/androidMain/kotlin/phantom/core/xray/XrayJsonBuilder.kt` — config JSON (`flow` and `network` fields)
- `shared/core/xray/src/androidMain/kotlin/phantom/core/xray/OperatorXrayConfig.kt` — embedded constants
- `shared/core/xray/src/androidMain/libs/` — vendored libXray AAR (current: `9a86646da8d8`)
- `apps/android/src/androidMain/kotlin/phantom/android/diagnostic/RcDirectArmG.kt` — Arm G diagnostic class

**Critical commits:**

- `82103ad0` — 3.2a telemetry (`WsDegradationDetector`) shipped
- `601d9d8d` — Arm D outcome
- `d2c22cd8` — Arm A.2 outcome
- `a58ec03f` — T2 diagnostic code
- `1917c966` — Arm G v10/v11 wire-stall verdict docs (local-only as of 2026-06-09, awaits push after fresh-head review)

**Server-side:**

- `deploy/xray/config.json.template` — Reality server config
- `services/relay/src/routes.rs` — WS + REST endpoints

**Field test artifacts (on VPS `phantom-relay-01:/tmp/`):**

- `armg-v10-tecno-8443.pcap` — Tecno wire (single-segment evidence)
- `armg-v11-mss-clamp-8443.pcap` — MSS clamp test
- `armg-wsl-pass-8443.pcap` — WSL2 reference (multi-segment evidence)
- `phantom-xray-config.json` — server inbound snapshot

**Memory files (in `~/.claude/projects/.../memory/`):**

- `project_arm_g_v10v11_libxray_android_write_stall_2026_06_08.md` — full evidence chain
- `project_arm_g_subtracks_locked_2026_06_09.md` — this two-track plan
- `project_arm_g_external_validated_2026_06_07.md` (amended) — Linux Xray PASS baseline
- `project_arm_g_minilock_2026_06_05.md` — original Arm G mini-lock (Council #2 amended 2026-06-08)
- `feedback_windows_xray_invalid_oracle_2026_06_07.md` — durable rule

---

## 9. Diagnostic-Design Lessons (carried forward)

**From 2026-06-08 Council resolution:** A Council decision can be empirically refuted by the very next experiment. The 2026-06-08 Council amending mini-lock decision #2 (hybrid → persistent Xray lifecycle) was procedurally correct (evidence-driven, not arbitrary), but the model it embodied turned out wrong (v10 wire evidence refuted persistent-vs-per-session as proximate cause). **Wire evidence beats model.** Do not lock a sub-track or architectural pivot on a single Council unless the underlying evidence-class is itself wire-confirmed.

**Applied to Trek 1 Stage 1:** the leading hypothesis (Vision splice race) is consistent with wire evidence but has the SAME refutability risk. Stage 1 test matrix IS the discriminator. If `flow=""` plain VLESS shows same single-segment stall → Vision splice hypothesis refuted, investigation pivots to libXray dialer / Go runtime / native socket buffering.

**From 2026-06-07 Windows xray.exe episode:** "Check your oracle before diagnosing the system." Windows xray.exe ClientHello = 1440 bytes was visible in round 1 of tcpdump; canonical uTLS chrome is 517 bytes. That size delta was actionable at round 1 but only acted on at round 4 (WSL2 pivot). Faster oracle-swap on size mismatch would have saved 2-3 rounds.

**From session-close discipline (Vladislav-locked rule):** End-of-session = clean checkpoint, sub-track work in fresh session. Do not free-fall into next sub-track work in the same session. No code changes when locking direction; lock evidence + scope + wording bounds; let fresh head start the sub-track.

---

*Document created 2026-06-09 during Arm G follow-up scoping session. Awaits fresh-head review before commit + push. Standalone reference for future contributors, future-self diagnostic sessions, and architects external to current chat context.*
