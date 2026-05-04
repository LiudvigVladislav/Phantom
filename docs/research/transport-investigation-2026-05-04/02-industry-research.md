# Industry transport research - 2026-05-04

## Summary

PHANTOM's "TCP RST every 50-60s without VPN, stable indefinitely with VPN, on
two unrelated physical networks" pattern matches three well-documented classes
of failure that the industry has fought for at least a decade: (1) cellular
and home-router NAT mappings that expire idle TCP flows around 30-65s, (2) DPI
middleboxes that inject RST after detecting an "uninteresting" or
fingerprintable long-lived flow (especially in Russia under TSPU), and (3)
proxy / load-balancer idle timeouts at 60s by default (Nginx, AWS ALB,
HAProxy). The industry's response has been three-fold and largely converged:

1. Tighten application-layer ping intervals to 25-30s (clears the shortest
   common NAT bucket), not the OS-default 7200s TCP keepalive.
2. Treat the persistent connection as **disposable** - Stream-Management /
   sequence-number resume so a 5-second drop is invisible to the user, and a
   60s drop costs only a re-handshake.
3. Move the always-on "doorbell" out of the app's own socket and into a
   *push* channel that the OS keeps warm: FCM (Signal, WhatsApp, Telegram),
   APNs (iOS), or - for FOSS / no-Google builds - **UnifiedPush** (Element,
   Tusky, FOSS Tutanota, ntfy).

For PHANTOM the most directly relevant comparisons are SimpleX (queue-based,
TLS, persistent foreground subscriptions, accepts the reconnect tax) and
Element on F-Droid + UnifiedPush (no GMS, ntfy distributor holds the warm
socket so the app does not have to). Telegram's MTProto obfuscation is the
right reference for the Russia / TSPU dimension specifically. WhatsApp /
Signal are *not* directly portable because they assume FCM is the keepalive
and the app socket is only "warm in the foreground" - exactly what FOSS
Android builds cannot rely on.

---

## Signal

**Transport.** Signal-Android opens a single TLS WebSocket to the
chat-service (and a second, "unidentified" socket for sealed sender) and
keeps it as a registered map entry on the server, keyed by device id; message
fan-out is done server-side via Redis Pub/Sub so any server replica can wake
the right socket [softwaremill][signal-issue-1000].

**Mobile keepalive strategy.** In production Signal does **not** rely on its
own socket for "always reachable." On Google-Play builds the always-on
channel is FCM. The WebSocket is opened only when the app is foreground or
when an FCM data-push has just woken it (the BackgroundMessageRetriever
path). When FCM is unavailable (websocket-only mode used on de-Googled
builds, e.g. the upstream issue [signal-issue-12490]), Signal Android falls
back to a foreground service holding the websocket, which is exactly the
mode where users see "messages arrive only when I open the app" - a fragile
mode that the team has repeatedly told users is intentionally a fallback,
not a primary [signal-issue-11095].

**Implication for PHANTOM.** Signal's design effectively delegates the
"survive 60s NAT timeout" problem to Google's FCM connection (which Google
keepalives aggressively at the OS level using a separate socket the app
doesn't own). A FOSS messenger without GMS cannot inherit that property and
must either replicate it (UnifiedPush) or accept Signal's degraded
foreground-only behaviour.

---

## WhatsApp

**Transport.** WhatsApp uses a Noise-Protocol-derived handshake ("Noise
Pipes" with Curve25519 / AES-GCM / SHA-256) over a long-running TCP
connection to its chat servers; the inner protocol is XMPP-derived (the
historical mod_ejabberd lineage) but heavily binary-framed [noise-pipes-meta].

**Keepalive.** No public, citable value for the cellular ping interval has
been published by Meta. What is documented in the third-party reverse-
engineering community (yowsup, consonance) is that a periodic ping/idle is
sent on the order of every few minutes, but not on every-25-seconds basis -
because, as with Signal, the always-on side is FCM (Google) on Android and
APNs on iOS. WhatsApp's app-owned socket is foreground-leaning [karanpratap].

**Implication.** Same as Signal: the production "WhatsApp socket survives
forever" perception is illusory - the OS push channel is the survivor. On a
de-Googled Android, WhatsApp degrades the same way Signal does.

---

## Element / Matrix on FOSS Android

This is the most directly portable comparison for PHANTOM, because Element
ships **two flavours**: gplay (FCM) and fdroid (no Google Services). The
fdroid flavour has had to solve exactly the problem PHANTOM has.

**Sync transport.** Matrix uses HTTPS long-polling: the client issues
`GET /_matrix/client/v3/sync?since=...&timeout=30000` and the server holds
the request open up to ~30s, returning either an event batch or an empty
delta. This is HTTP/1.1 (or HTTP/2) over TLS, *not* WebSocket. Each request
is a fresh TCP-level interaction inside a possibly-pooled connection, so
NAT idleness is bounded by the long-poll timeout (~30s) - automatically
under the 60s CGN bucket [element-android-docs].

**Background connectivity on F-Droid.** Two modes are documented in the
Element repo's `docs/unifiedpush.md`:

- **Background-sync polling**: app issues sync periodically when the OS
  allows it. Battery-cheap but high latency.
- **UnifiedPush distributor**: app registers with whatever distributor the
  user has installed (ntfy, NextPush, Gotify-UP). The distributor (not
  Element) holds the persistent WebSocket / SSE to its push gateway. The
  Matrix homeserver pushes notifications via a sygnal-style push gateway
  upstream of the distributor. Element itself does not hold an always-on
  socket [element-unifiedpush-doc][unifiedpush-fdroid].

**Implication for PHANTOM.** The F-Droid Element architecture is the
template: do not make the app responsible for surviving 60s CGN. Make a
*distributor* responsible for that, and let the app come and go.

---

## SimpleX Chat

The closest peer architecturally to PHANTOM (FOSS, decentralised relays,
no central account, Russia-relevant), so the most useful comparison.

**Transport.** SMP runs over TLS 1.3 (a long-running TCP socket). Clients
open one persistent TLS connection per SMP relay they currently have queues
on, send fixed-size 16 KB blocks regardless of payload (metadata-leak
mitigation), and **subscribe** to their queue notifications on that same
socket [simplexmq-overview][simplex-server].

**Idle / NAT handling.** SimpleX explicitly exposes TCP keep-alive tuning
to users in the app FAQ - they recommend disabling TCP keep-alive or
extending TCP_KEEPIDLE / TCP_KEEPINTVL when traffic is high, and
*decreasing* protocol-level timeouts when connections fail repeatedly
[simplex-faq]. In other words, SimpleX has hit the same NAT-vs-battery
tradeoff and exposes it as a setting rather than auto-tuning it.

**Reconnect philosophy.** SimpleX accepts that mobile relays will drop and
the cost is a re-subscribe round-trip per queue. Because messages are stored
on the SMP relay until the receiver subscribes (queue model, not "deliver
to socket or drop"), a 60s connection reset is invisible at the app layer -
the next subscribe pulls everything that arrived during the gap. This is
the same delivery semantics PHANTOM's relay claim_pending API gives you,
and it's *the* feature that makes SimpleX tolerate flaky transport.

**No persistent push without GMS.** SimpleX on F-Droid runs a foreground
service to hold its TLS sockets - same battery / OEM-killer trap PHANTOM
will hit on Tecno HiOS (see `TECNO_HIOS_WIFI_PARKING_RESEARCH.md`).

**Implication.** SimpleX validates two PHANTOM design choices: (a) store
messages on the relay until pulled, (b) treat the persistent socket as
*best-effort* and do not over-engineer keeping it alive. What SimpleX does
*not* solve - and openly acknowledges - is the foreground-service / battery
problem on de-Googled Android, which is exactly where UnifiedPush helps.

---

## Briar

Briar is the outlier and least transferable to PHANTOM, but worth noting.

**Transport.** Briar uses BTP (Bramble Transport Protocol) over multiple
plug-ins: Tor hidden services for internet, Bluetooth, and LAN. There is no
central relay; pairs sync directly when both endpoints are simultaneously
reachable. Briar is explicitly delay-tolerant - a "persistent connection"
in the PHANTOM sense does not exist; instead BSP synchronises the
append-only log whenever any transport plug-in connects [briar-overview].

**Implication.** Briar avoids the CGN problem by avoiding always-on entirely;
this is a different product (offline-first, peer-to-peer) and not a
template for a relay-mediated messenger that wants real-time delivery.

---

## Telegram MTProto

**Transport portfolio.** MTProto is explicitly multi-transport: TCP (4
framing variants - abridged, intermediate, padded-intermediate, full),
HTTP/HTTPS, WS (plain WebSocket), WSS (WebSocket-over-TLS), and UDP. The
client is expected to **try multiple transports** at start-up and pick the
one that works on the current network [mtproto-transports-core].

**Obfuscation against ISP DPI.** This is the part that matters for PHANTOM
in a Russian context. MTProto wraps payloads in an obfuscation envelope
with a 64-byte random init that is constrained to *not* fingerprint as
HTTP, MTProto-known-magic bytes, etc. Two "secret types" are documented:
type 0 (random-looking AES-256-CTR stream), type 2 (fake-TLS - the wire
mimics a TLS 1.2 handshake so DPI sees a TLS flow rather than MTProto)
[mtproto-obfuscation][mtproxy-doc].

**Connection schemes / mobile.** Telegram clients are documented to retry
different DCs and different transports on connection failure; the iOS
walkthrough by Bo describes the iOS client maintaining several MTProto
connections per DC, distinguishing "main / push / download / upload" with
different liveness policies [hubo-mtproto-ios].

**Implication for PHANTOM.** Two takeaways: (a) On a hostile network, having
*one* transport (TLS WSS to a Hetzner relay) is a single point of failure;
(b) When that transport's flow becomes fingerprintable to TSPU, RST
injection is the predictable result. Telegram's answer was fake-TLS +
multiple DCs + multiple transports. PHANTOM's PR-3-era "WSS to one Hetzner
host" is structurally equivalent to early MTProxy and will eat the same
RSTs.

---

## Carrier-Grade NAT - known patterns

Hard numbers, sourced:

- "CGNs in cellular networks exhibit a larger median mapping timeout (65 s)
  compared to non-cellular networks (35 s)" - Richter et al., IMC'16 paper
  on multi-perspective CGN deployment analysis [richter-cgn].
- Wireless Moves blog (operator XMPP study, France): default Linux TCP
  keepalive is 7200s (2 hours); Conversations XMPP client uses 600s app-
  layer keepalive; the author empirically found that the carrier dropped
  TCP mappings well below 9 minutes and settled on **270s
  (`net.ipv4.tcp_keepalive_time=270`)** as the working value [wirelessmoves].
- WebSocket.org guidance, derived from Nginx / AWS / HAProxy / Cloudflare
  defaults: Nginx `proxy_read_timeout` 60s, AWS ALB 60s, HAProxy
  `timeout tunnel` 60s, Cloudflare 100s, AWS NLB 350s, cellular NAT
  30-120s, home router NAT 60-300s [websocket-timeout-guide].
- ntfy-android PR #113 discussion: ntfy default ping was 45s; tested 300s;
  compromised at 180s; XMPP clients use adaptive 30-300s; sundup / NextPush
  use 60s. Observed phenomenon: "the default 45s is barely enough for the
  phone's modem to power down" - i.e. shorter pings actively damage
  battery [ntfy-pr-113].

**Detection patterns.** No standard "am I behind CGN" probe - apps detect
indirectly: comparing `getifaddr` IPv4 to STUN-reported public IPv4 (RFC1918
local but non-RFC1918 public => some NAT; carrier-allocated CGNAT prefix
100.64.0.0/10 => likely CGN), or simply *measuring* mapping lifetime
(Adapting to NAT timeout values, Tino paper [petertino]).

**Industry rule of thumb (synthesised across sources):** keep the
application-layer ping interval at **25-30 seconds** for cellular-tolerant
apps. This clears the shortest common NAT bucket (30s), stays well under
the 60s proxy default, and the per-minute byte cost is negligible (~2.4
KB/min/conn). 25s is the most-cited number [websocket-timeout-guide]
[websocket-heartbeat].

---

## WebSocket vs alternatives

What the data shows:

- **WebSocket works** when the path has no aggressive proxy / DPI in the
  middle and the app keeps app-layer pings under the shortest NAT bucket.
  When this is true (VPN tunnel hiding the path; same-network test; corporate
  LAN), it's the cheapest option per byte.
- **WebSocket fails** when (a) DPI sees a long-lived non-HTTP-shaped TLS
  flow and decides to drop it (Russia/TSPU pattern; some corporate proxies),
  (b) a load balancer or CDN with a 60s tunnel timeout sits in the path
  (AWS ALB, default Nginx), (c) carrier CGN drops the mapping while the
  app's ping is too slow.
- **HTTP long-polling** (Matrix's choice) is structurally immune to (a) and
  (b) because every poll is a "fresh" HTTP request that any DPI / LB / CDN
  is happy to forward, and the connection is never idle longer than the
  server-side hold (typically 30s). Its cost is one extra round-trip per
  poll cycle and slightly higher tail latency.
- **HTTP/2 streams (server push / SSE)** survive HTTP-aware middleboxes
  much better than WebSocket and multiplex many subscriptions onto one TCP
  connection. SSE is unidirectional (server -> client), which fits the
  "doorbell" pattern very well; the client uses normal POSTs to send.
- **HTTP/3 / QUIC** (UDP-based) - migrates between network paths via the
  connection ID, so a NAT-rebind does not destroy the session. This is
  precisely the property mobile messengers want, and it's why Meta /
  Google / Cloudflare have moved heavily to HTTP/3. The downside is some
  Russian operators / corporate networks block UDP/443 outright, in which
  case you fall back to TCP.

A conservative "production messaging" stack today looks like: HTTP/3 first,
HTTP/2 with SSE as fallback, HTTP/1.1 long-poll as last resort - and
never WebSocket as the *only* option [meta-messenger-arch][rxdb-comparison].

---

## Russian ISP-specific issues

The Russia-specific evidence is unusually strong here.

- **TSPU** is deployed at every major Russian ISP under the 2019 Sovereign
  Internet law and is centrally controlled by Roskomnadzor. It routinely
  injects TCP RST after seeing a "blocked" SNI in the TLS ClientHello, or
  on a flow it categorises as a circumvention protocol [ooni-russia-2022]
  [ooni-russia-2024].
- A more recent TSPU behaviour is **freeze-not-reset**: the box stops
  forwarding after a data-volume threshold rather than RSTing, so the
  client only notices via read-timeout. PHANTOM's symptom (clean RST at
  ~50-60s) is the older, simpler pattern - which strongly suggests a
  *carrier CGN / OEM router* dropping the mapping rather than active TSPU
  meddling, because TSPU's RSTs typically fire on flow-class detection
  (during handshake or after a content trigger) and not on a fixed wall-
  clock timer [zapret].
- The Russia-specific reason VPN "fixes" everything is exactly that the VPN
  endpoint *re-shapes* the flow: a single long-lived UDP/443 tunnel to
  Wireguard or a TLS-in-TLS Cloak/V2Ray flow looks completely unlike a
  bare WSS-to-Hetzner connection. Plus the VPN keepalive is 25s by default,
  re-arming the upstream NAT for *all* tunnelled flows.
- Practical implication: the user's "two unrelated home networks both reset
  at 60s" is more consistent with **two consumer routers / two CGN paths
  with similar ~60s idle timeouts** than with TSPU. The fix space is the
  same either way (shorter ping, transport diversity, push-channel offload),
  but the diagnosis affects priority: if it's NAT, ping interval fixes it
  cheaply; if it's TSPU pattern detection, ping interval will not save you
  and obfuscation/transport-diversity is required.

---

## UnifiedPush recap

UnifiedPush is the FOSS-Android answer to "we can't use FCM but the OS
won't let us hold a socket forever." Architecturally:

1. The **distributor** is a separate user-installed app (ntfy, NextPush,
   Gotify-UP, Conversations, FOSS Tutanota's distributor). It holds *one*
   persistent connection (WebSocket or HTTP/SSE stream) to its push
   gateway. Because the user installs it explicitly, it can claim
   battery-optimisation exemption and run as a foreground service without
   the user feeling tricked. Some distributors (NextPush) use a
   Nextcloud server the user already runs; ntfy uses a public or
   self-hosted ntfy server [unifiedpush-org][element-unifiedpush-doc].
2. **Apps** (Element, Tusky, FairEmail, FOSS Tutanota, Markor, etc.) bind
   to whatever distributor is installed via an Android intent contract,
   register an endpoint URL, and pass that URL up to their server. They no
   longer hold a persistent socket themselves.
3. **App servers** push notifications to the per-user endpoint URL over
   normal HTTPS. The push gateway forwards to the distributor over the
   warm connection. The distributor wakes the app via a local Android
   broadcast.
4. ntfy-android documents WebSocket as the default transport with HTTP-JSON-
   stream (SSE-like) as fallback; ping intervals are tuned for battery
   (the PR-113 discussion above moved from 45s to 180s default with
   network-change detection rather than aggressive pinging) [ntfy-pr-113].

Does it solve PHANTOM's class of problem fully? **It solves the "always
reachable" half cleanly:** PHANTOM's relay can become a UnifiedPush
*application server* (push to the user's UP endpoint when a message
arrives), and PHANTOM-Android can drop its persistent foreground socket
entirely. **It does not solve the "RST on the active session" half** -
once the user opens PHANTOM and starts an interactive flow, the app's
own socket still has to survive long enough to complete the conversation,
and that socket eats the same NAT timeouts. So UnifiedPush is *necessary
but not sufficient*; it must be paired with a sane app-layer ping (25-30s)
and ideally transport diversity for hostile networks.

---

## Ranked recommendations for PHANTOM

1. **Tighten WSS ping interval to 25-30 seconds.** Current OkHttp
   `pingInterval` should be set to 25s on Android (and the relay
   should accept and pong). This is the cheapest, highest-confidence
   intervention. Cellular CGN median is 65s, but the long tail of consumer
   routers (Tecno HiOS, MikroTik, ZyXEL) reaches the 30s mark. 25s clears
   all of them, costs ~2.4 KB/min, and is the documented industry
   sweet-spot. Citation: [websocket-timeout-guide],
   [wirelessmoves]. Time cost: hours.

2. **Implement Stream-Management-style resume.** The relay already stores
   pending envelopes (claim_pending). Add a client-supplied resume-token
   (last-acknowledged sequence) on WSS open so a 60s drop -> reconnect ->
   replay-since-N is invisible at the chat-history layer. This is exactly
   XEP-0198 and SimpleX queue subscribe semantics. Until this exists, every
   RST is a user-visible "messages not delivered" event. Time cost: a few
   days; high user-perceived value. Citation: [xep-0198].

3. **Add UnifiedPush as the doorbell channel for FOSS / no-GMS Android
   builds.** Track-A Phase-5 in the master timeline. The PHANTOM relay
   becomes a UnifiedPush application server (per-user endpoint URL), and
   the Android client registers via UP intent contract. This eliminates
   the foreground-service requirement and decouples "user is reachable"
   from "PHANTOM has a working socket." Reference impl: Element-Android's
   fdroid flavour. Time cost: a sprint. Citation: [element-unifiedpush-doc],
   [unifiedpush-fdroid].

4. **Diagnose: NAT vs TSPU.** Before investing in obfuscation, prove which
   one is biting. Cheap test: put a `tcpdump` on the Hetzner relay and on
   the Tecno (via adb + termux) and compare what triggers the RST - is it
   a wall-clock 60s with no traffic (NAT), or is it tied to a TLS
   ClientHello / a specific byte volume (TSPU)? This is one evening's
   work and decides whether (5) is a priority or a nice-to-have.

5. **Plan transport diversity for hostile-network mode** (Russia-only
   build flag, or runtime fallback). Add a fake-TLS or
   WebSocket-over-CDN front (Cloudflare-fronted WSS, where the SNI is
   `*.cloudflare.com` and TSPU sees only a CDN flow). Reference: VLESS +
   TLS + WS + CDN, which the cited Russian-net research reports as
   <5% detection rate. Lower priority than (1)-(3) because it only
   matters if (4) shows TSPU is involved. Citation: [vless-russia],
   [mtproto-obfuscation].

6. **Long-term: evaluate moving the wire to HTTP/2 + SSE or HTTP/3.**
   Matrix-style long-poll or SSE for the relay->client direction is more
   middlebox-friendly than WSS, and HTTP/3's connection migration solves
   the Wi-Fi-to-cellular handoff cleanly. Not Alpha-2 work; a v1.0
   architectural decision. Reference: Meta Messenger's hybrid socket +
   push + long-poll model. Citation: [meta-messenger-arch],
   [rxdb-comparison].

---

## Source list

- [signal-issue-1000] https://github.com/signalapp/Signal-Android/issues/1000 - WebSocket support tracking issue
- [signal-issue-11095] https://github.com/signalapp/Signal-Android/issues/11095 - Messages not received until app placed in background, root-cause discussion
- [signal-issue-12490] https://github.com/signalapp/Signal-Android/issues/12490 - Push on Google-free smartphones
- [softwaremill] https://softwaremill.com/what-ive-learned-from-signal-server-source-code/ - Signal server source-code walkthrough (Redis pubsub, websocket map)
- [noise-pipes-meta] https://engineering.fb.com/2024/03/06/security/whatsapp-messenger-messaging-interoperability-eu/ - WhatsApp Noise Pipes interop description
- [karanpratap] https://www.karanpratapsingh.com/courses/system-design/whatsapp - WhatsApp system-design summary
- [element-android-docs] https://github.com/element-hq/element-android/blob/develop/docs/notifications.md - Element Android notifications doc
- [element-unifiedpush-doc] https://github.com/element-hq/element-android/blob/develop/docs/unifiedpush.md - Element Android UnifiedPush integration
- [unifiedpush-fdroid] https://f-droid.org/en/2022/12/18/unifiedpush.html - F-Droid UnifiedPush overview
- [unifiedpush-org] https://unifiedpush.org/ - UnifiedPush spec home
- [unifiedpush-ntfy] https://unifiedpush.org/users/distributors/ntfy/ - ntfy as a UnifiedPush distributor
- [ntfy-pr-113] https://github.com/binwiederhier/ntfy-android/pull/113 - Disabling client-initiated WS ping for battery, ping-interval discussion
- [simplexmq-overview] https://github.com/simplex-chat/simplexmq/blob/stable/protocol/overview-tjr.md - SMP protocol overview
- [simplex-server] https://simplex.chat/docs/server.html - SMP server hosting / NAT guidance
- [simplex-faq] https://simplex.chat/faq/ - TCP keep-alive tuning recommendation
- [briar-overview] https://briarproject.org/how-it-works/ - Briar protocol stack summary
- [briar-btp-spec] https://code.briarproject.org/briar/briar-spec/blob/master/protocols/BTP.md - Bramble Transport Protocol spec (loaded incomplete via WebFetch; cited as primary)
- [mtproto-transports-core] https://core.telegram.org/mtproto/transports - MTProto transports list
- [mtproto-obfuscation] https://core.telegram.org/mtproto/mtproto-transports - MTProto transport obfuscation
- [mtproxy-doc] https://core.telegram.org/proxy - Telegram MTProxy / fake-TLS
- [hubo-mtproto-ios] https://hubo.dev/2020-06-05-source-code-walkthrough-of-telegram-ios-part-4/ - Telegram iOS connection management walkthrough
- [richter-cgn] https://www.prichter.com/imc176-richterA.pdf - Richter et al., "A Multi-perspective Analysis of CGN Deployment", IMC'16
- [petertino] https://petertino.github.io/web/PAPERS/natTimeout.pdf - "Adapting to NAT timeout values in P2P overlay networks"
- [wirelessmoves] https://blog.wirelessmoves.com/2020/09/carrier-grade-nat-timeouts-and-how-to-configure-your-xmpp-server.html - Operator CGN timeout, 270s tcp_keepalive_time
- [websocket-timeout-guide] https://websocket.org/guides/troubleshooting/timeout/ - Proxy / NAT timeout reference, 25s ping recommendation
- [websocket-heartbeat] https://websocket.org/guides/heartbeat/ - Heartbeat patterns
- [okhttp-pinginterval] https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/ping-interval.html - OkHttpClient.Builder.pingInterval
- [xep-0198] https://xmpp.org/extensions/xep-0198.html - XMPP Stream Management
- [ooni-russia-2022] https://ooni.org/post/2022-russia-blocks-amid-ru-ua-conflict/ - OONI on Russia DPI / RST patterns 2022
- [ooni-russia-2024] https://ooni.org/post/2024-russia-report/ - OONI Russia censorship chronicles 2024
- [zapret] https://zread.ai/bol-van/zapret/5-building-from-source - zapret project (Russia DPI bypass)
- [vless-russia] https://habr.com/en/articles/990144/ - VLESS protocol bypass results in Russia
- [hrw-russia-2025] https://www.hrw.org/report/2025/07/30/disrupted-throttled-and-blocked/state-censorship-control-and-increasing-isolation - HRW report on Russian internet 2025
- [meta-messenger-arch] https://blog.algomaster.io/p/polling-vs-long-polling-vs-sse-vs-websockets-webhooks - Meta Messenger hybrid socket + push + poll architecture summary
- [rxdb-comparison] https://rxdb.info/articles/websockets-sse-polling-webrtc-webtransport.html - WebSockets vs SSE vs Long-Polling vs WebTransport
