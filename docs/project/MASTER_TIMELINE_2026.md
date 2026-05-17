# PHANTOM — Master Timeline 2026

> **Living document.** Источник истины для трекинга всех треков работы. Обновляется по мере merge каждого PR — чекбоксы превращаются в `[x]`, в "Сделано" секцию добавляется коммит.

**Last updated:** 2026-05-17  
**Master HEAD:** Track A complete (5 PRs ✅). Track B items 1–8 ALL merged — **Kickstarter security blockers closed**. F11+F26 signed-challenge auth production-validated on Tecno МТС + emulator with the new APK 2026-05-09. **Transport reliability mini-sprint extended to 11 PRs (#103–#113) merged 2026-05-10/12 — closed-success 2026-05-11 21:32 МТС.** Reality+VPN audited and gated, Tor staged-UX shipped, bridge profile rotation live and re-tuned per МТС data, Briar's RU-tuned bridge set imported (Snowflake-via-Google-AMP fallback). **🎉 Test #6 confirms Ghost on МТС WITHOUT VPN works** — `Online via Tor · Ghost` in ~6 minutes via KitchenSink (1/4) on the very first attempt; bootstrap reached 100 %, probe 200 OK, WS handshake successful through onion. First captured logcat proof of Ghost-without-VPN on a Russian carrier network in PHANTOM's history. **🎉 First-message reliability sprint — PR-G3 (#123) + PR-G4 (#124) merged 2026-05-13, root cause "yellow dot for two minutes on first message to a new contact" closed.** Test #30 confirms: Phone bundle-fetch went from 8009 ms × 4 timeouts to **151 ms × 1 OK**; emu publish went from 20.7 s + 39.5 s `Connection reset` to **829 ms → 201**. **PR-H1a (`3db97b49`) merged 2026-05-13** — WS observability tags `[gen=N s=M] / conn_id` + server-side `ack_deliver_*` traces; ruled out zombie-writer hypothesis and pinpointed Test #33 reorder bug. **🎉 H2 line closed 2026-05-13 evening — PR-H2a (#127, `674ce231`) + PR-H2a.2 (#128, `72e59ce9`) + PR-H2b (#129, `7008cf3e`) all merged.** Test #34 verified H2a/H2a.2 fixed wire reorder (10/10 messages delivered + visible) but surfaced two MAC errors on read-receipt envelopes from relay redelivery hitting an already-advanced ratchet chain — H2b's processed-envelope ledger guard runs BEFORE `ratchet.decrypt`, covers ALL payload types (read receipts and other control payloads were never inserted into the messages table so they bypassed the legacy `messages.id` guard), and survives `pm clear`-free forward-only schema migration (15.sqm, schema v15 → v16). **🎉 H1 line closed 2026-05-13 night — PR-H1b (#131, `0baa4196`) diagnosed half-open TCP black-hole pattern from `session_summary` lines (server `pings_received=2-5` vs client `pings_sent=11`, `since_last_ping_ms ≈ 153 s` on every dying session — middlebox NAT eviction + radio park dropping outbound packets while OS thinks socket healthy), then PR-H1c (#132, `e946caba`) shipped six-layer recovery (inbound-frame liveness, OkHttp `pingInterval(15s)`, `ping_send_failed → forceReconnect+break`, `TransportState.Reconnecting`, AlarmManager proactive at 45 s, server-side TCP SO_KEEPALIVE). Test #37 verified end-to-end: detection 155 s → 30–46 s, recovery 5 s → ~1 s, zero message loss across reconnect cycles (H2b ledger neutralises re-delivered envelopes). UX is now routine "Reconnecting…" badge instead of "lagging" outage. **🎉 H1e line closed 2026-05-14 — PR-H1e (#134, `bcc501be`) merged after a 4-run diagnostic sprint on `diag/h1e-ws-ping-experiments`. Each heartbeat layer was isolated across ≥12 reconnect cycles per device on Tecno МТС + emulator-on-dev-Wi-Fi: Run 0 (ws_ping=15s, app_ping=on) Phone 46.5s/Emu 39.5s; Run B (ws_ping=5s, app_ping=on) Phone 21.8s/Emu 29.5s; Run C (ws_ping=15s, app_ping=off) Phone 72.0s/Emu 56.4s ← winner; Run B+C (ws_ping=5s, app_ping=off) Phone 30.9s/Emu 29.6s. Two independent findings: (1) suppressing the app-level RelayMessage.Ping/Pong loop roughly doubles WS lifetime; (2) a 5-second OkHttp WS Ping is itself a kill trigger — the tighter pong window plus the cadence override the gain from (1). Production policy adopted = Run C: `APP_LEVEL_PING_ENABLED=false`, OkHttp WS Ping hard-coded to 15 000 ms, AlarmManager proactive reconnect stays enabled, dead-socket watchdog continues ticking on PING_INTERVAL_MS. Test #41 confirmed zero MAC errors / ledger-dedup misses / FIFO flush issues across all 12 reconnects per device. The `diag/h1e-ws-ping-experiments` branch is kept on origin as a reproducible archive of all four runs.** Open question for Phase 1: should client-side TCP keepalive (constants are declared in `RelayTransportConfig.kt` but **not currently applied client-side** — the comment claiming they are is being honesty-fixed in Phase 0 PR (a)) be wired in via a SocketFactory? Branch `fix/transport-tcp-keepalive` (3 commits ahead) holds a 153-line `KeepAliveSocketFactory.kt` that could provide it; decision deferred to empirical Phase 1 test results. Server-side socket2 keepalive is already live on master. **🎉 2026-05-15 Tele2 LTE diagnostic session — PR-Diag (#143, `f3925cac`) + PR-R0 (#144, `817331a7`) merged.** Test #42 on Tele2 LTE Иркутская had appeared to fail Reality probe and fall through to Tor, which was first read as "TSPU blocks Hetzner CIDR" and triggered a multi-VPS Reality pool plan. SSH server-side diagnostic + Termux curl from the same Tele2 SIM disproved that hypothesis: HTTPS GETs (`/health`, `/auth/challenge`, WS Upgrade `/ws`) all work fine from Tele2 — server-side Caddy log instead showed the real failure as `POST /prekeys/publish` with `bytes_read: 0, status: 408, duration: 30.05s, Content-Length: 13903`. OkHttp HTTP/1.1 upload body gets stuck on Android mobile carriers (same class as PR-G4's HTTP/2 stream-stuck) **and** a race condition was launching two parallel publish POSTs from independent reconnect generations at the same millisecond (visible in Caddy access log as duplicate POSTs from different ephemeral ports). PR-R0 ships a dedicated `createPreKeyPublishHttpClient()` (HTTP/1.1, `ConnectionPool(0)`, `Connection: close`, tuned timeouts), `kotlinx.coroutines.sync.Mutex` for publish debounce, 3-attempt retry with 500→1500 ms backoff, and extended `PREKEY_TRACE` logging. PR-Diag adds an OkHttp `EventListener` per-probe-step + `PROBE_TRACE chain_start / prepare_start/done/fail / probe_called/returned/outer_timeout / chain_attempt_success/failed / chain_all_failed / tor_state / xray_state` so future field tests answer "which phase died" without SSH cross-referencing. Acceptance criterion for PR-R0: Caddy access log must no longer show `POST /prekeys/publish bytes_read=0 status=408 duration=30s` from the app, and on Tele2 LTE prekey publish must complete without forcing fall-through to Tor purely on publish-timeout grounds. **Verification on Tele2 LTE Иркутская handset is pending — Phase 1 matrix (Tests #43-#47) stays paused until that retest succeeds.** Lesson logged: `bytes_read=0` + clean duration that equals client `readTimeout` is a client-side bug signature, not a network-filter signature; network filters produce more chaotic timing. **🎉 2026-05-16 — Tele2 reliability sprint Round 2 (PR-R0.2 #147 → R0.3 #150 → R0.4a #149 → R0.4b #151) and REST fallback transport (PR-D0r #152, master `1c1a91a9`; PR-D1 #153, master `6f6ade20`) all merged in one day.** Test #45 (2026-05-15) confirmed PR-R0.2 win — OPK batch 100 → 40 dropped publish body 13903 → 5863 bytes which now slips under the 8192-byte Tele2 middlebox curtain (`prekey_publish_ok status=201 elapsedMs=902`, Direct stays Direct end-to-end on Tele2 LTE Иркутская without falling to Tor). Test #48 (2026-05-16) on the R0.3+R0.4a+R0.4b APK exposed two NEW Tele2 layers that were hidden behind the prekey-publish failure: **Layer A** — WS Upgrade returns 101 but every subsequent WS Frame.Text is silently dropped upstream (server-side `pings_received=0, inbound_frames=0` across 20+ phone sessions, each ~153 s = server read timeout; phone OkHttp `pingInterval(15s)` timeout at 31 s is *symptom*, not cause); **Layer B** — `POST /prekeys/publish` body=5863b reaches server `status=201 duration=2.9ms resp=18b`, but the 18-byte response never reaches phone within 60 s (Caddy + phone log proved server sent it). PR-R0.4c (disable OkHttp pingInterval) was considered then **KILLED** as wrong direction — server-side `pings_received=0` proves phone pings never reach, disabling client-side timer just extends zombie WS lifetime. Locked direction: **REST short-poll fallback** with server-side idempotency (POST response may drop → client retries same Idempotency-Key, server dedupes). PR-D0r adds four endpoints (`/auth/session`, `/relay/send`, `/relay/poll`, `/relay/ack-deliver`), per-identity LRU idempotency cache (10 K keys × 24 h TTL), bearer token (1 h, retry-safe via `(identity, challenge, signing_pubkey, signature)` cache), `sha2::Sha256` body-hash, capability discovery in `/auth/session` response (`rest_fallback:true, max_send_body_bytes:4096, poll_max_envelopes:1`), unified WS↔REST envelope store (mirror helpers wired from both `routes.rs` WS arm and `rest_fallback.rs` paths), and `sealed_sender` preserved end-to-end through send → poll → live delivery. 10 integration tests cover all 4 review blockers including 4 NEW: replay-with-different-signature → 401, sealed_sender preservation, WS-simulated send mirrors into REST poll, WS-simulated ack clears REST poll. PR-D1 ships the Kotlin client library (commonMain `RestFallbackTransport` / `RestStateMachine` / `RestFallbackOrchestrator` + androidMain native OkHttp impl + expect/actual factory + 28 unit tests) currently **INERT** — orchestrator is constructed nowhere, capability gate keeps it dormant if relay returns `rest_fallback:false`. Wire-up into `PhantomMessagingService` lands separately as PR-D1b (architect design queued). Smoke-test against the deployed relay 2026-05-16 confirmed `/auth/session` 422 on empty body, `/relay/send` 401 no-auth, `/relay/poll` 401 no-auth — all new endpoints live, all blockers from Vladislav's first review closed. **🎉 2026-05-16/17 — Live messaging wire-up + bootstrap-ordering fix landed (PR-D1b → PR-D1c → PR-D1c.1 → PR #157 squash-merged to master `d7a05273`).** PR-D1b (#155, `e9bdf462`) wired the `RestFallbackOrchestrator` into `PhantomMessagingService` so the live messaging path actually consults the REST fallback when the state machine flips. PR-D1c (#157 first commit `bd171279` + rounds 2/3 `e09a8569` / `4a60bde2`) migrates pending WS outbox envelopes to REST on mode switch — closes the bootstrap-ordering bug where the *first* envelope of a new session was lost when the state machine flipped mid-flight. PR-D1c.1 (#158 merged into the D1c branch as `61a051f1`) fixes three connected REST session-token lifecycle bugs uncovered by Test #50: bootstrap now caches the issued token, `kotlinx.coroutines.sync.Mutex` serialises concurrent `acquireOrRefreshToken()` callers (replacing an unused `Any()` lock), `sendEnvelope()` acquires the token inside the retry loop with CAS via a `staleToken` parameter so a 401-refresh propagates to subsequent attempts. **Test #51 (2026-05-17, Tele2 LTE Иркутская on Tecno `103603734A004351` + emulator-5554) = partial PASS.** End-to-end chain proven on real hardware: `token_cached reason=bootstrap` → WS first send → 40 s ACK wait → `mode_switched WsActive → RestActive reason=active_outbound_threshold` → `migrate_pending_arm/start/send` → `token_reused reason=send` → `send_response status=201` → `migrate_pending_ok` → emulator `handleDeliver` + `Decrypt OK after bootstrap` (X3DH session created from the migrated bootstrap envelope). Six text messages each direction over ~3 minutes all delivered. Steady-state: every subsequent send/poll/ack reuses the cached token (zero extra `/auth/session` calls), `status=201` on every send, H2b ledger guard handles REST poll redelivery. **Remaining UX blockers** (next-PR scope, not D1c regression): (1) ~40 s wait for 2 active counter ticks before WS→REST switch is the dominant latency cost — to be closed by **PR-D1d fast active-outbound degrade** (10 s per-envelope ACK deadline, switch on first expiry, reset only by ACK of same envelope-id); (2) voice chunks 11-55 KB rejected by REST `max_body=4096` cap (`route_send mode=RestActive` → `send_oversize`) — to be closed by **PR-D2a** (UI gating in `Limited realtime`) then **PR-D2b** (chunked audio under cap, separate larger PR). Minor: prekey upload occasionally times out on the response (Tele2 Layer B downstream drop) — fallback via GET `/prekeys/status` queued as PR-R0.5/PR-PK1 at low priority.  
**Strategic posture (2026-05-14, reaffirmed 2026-05-15):** no fixed deadlines — focused on production stability and external audit readiness rather than premature release pressure. Tags are not goals; functional quality is the goal. **Strategic pivot 2026-05-15:** Tor demoted to text-only emergency fallback (cannot carry WebRTC for calls — no UDP through onion, latency too high, bandwidth insufficient); Direct or Reality remains required for voice messages and calls; the planned `TransportCapabilities` data class (text/voiceMessages/calls/realtimeUdp flags) is queued as PR-R1a in a parallel design track. Multi-VPS Reality pool is **NOT** the next infrastructure step — the data showed the bottleneck is the OkHttp publish bug, not Hetzner reachability; the cheaper-first experiments (alternative SNI on the same VPS, then Oracle Cloud Always Free if needed) are queued behind PR-R0 validation, not ahead of it.

---

## Большая картина — 4 параллельных трека

```
                                         Release    Secondary
  Сегодня                               (1 июня)   (30 июня)
  2026-05-03 ──────────── 4 недели ────────►│────── 4 нед ──►│──── ... до v1.0
                                            │                │
  Track A — RELIABILITY SPRINT ─────────►│  │                │
  (5 PR, ~13 дней работы)                   │                │
                                            │                │
  Track B — SECURITY SPRINT ─────────►│      │                │
  (10 P1 блокеров до Kickstarter)            │                │
                                            │                │
  Track C — RELEASE POLISH ───────►│         │                │
  (4 фазы repo polish, ~6 часов)              │                │
                                            │                │
  Track D — Alpha 2 FEATURES ───────────────►│ продолжение ►  │
  (attachments, groups, channels, etc)
                                            │                │
  Track E — CENSORSHIP RESISTANCE ───────►✅│                │
  (Stage 5E Xray production-validated 2026-05-07)
```

Track A + Track C идут параллельно. Track B стартует после Track A. Track D — после Track B items 1–4. Track E — закрыт основным объёмом, два cleanup-PR в backlog.

---

## Track A — Reliability Sprint

**Цель:** Alpha-3 release-candidate quality для текста + голоса + звонков. Закрывает все user-visible bugs surfaced by real-device QA на Tecno HiOS + Pixel emulator.

| # | PR | Задача | F-numbers | Оценка | Статус |
|---|----|--------|-----------|--------|--------|
| 1 | [#28](https://github.com/LiudvigVladislav/Phantom/pull/28) | Transport reliability sprint (ADR-010, abandon-and-restart, AlarmManager, MulticastLock) | — | — | ✅ merged `12ac26c5` |
| 2 | [#29](https://github.com/LiudvigVladislav/Phantom/pull/29) | Calls UX | F-03, F-07, F-10, F-15 | ~2 дня | ✅ merged `45338fb8` |
| 3 | [#30](https://github.com/LiudvigVladislav/Phantom/pull/30) | Calls media (mic permission, audio mode, чёрный экран) | — | ~1 день | ✅ merged `c62fbfff` |
| 4 | docs/calls-experimental | Calls=experimental decision + Track A schedule lock | — | 30 мин | ✅ merged `d094ca8f` |
| 5 | feat/voice-message-chunking-pr3 | **Voice messages chunking** | F-05 | ~5 дней | ✅ merged `41b9fb94` (#32, 2026-05-04). ✅ 8 KB chunk-size already on master (`AUDIO_CHUNK_BYTES = 8 * 1024` in `DefaultMessagingService.kt:139` — landed before this note was written; no orphan, verified 2026-05-14) |
| 6 | [#65](https://github.com/LiudvigVladislav/Phantom/pull/65) | Data integrity edges | F-08, F-01, F-09, F-04 | ~3 дня | ✅ merged `df127bc1` (#65, 2026-05-08) |
| 7 | [#67](https://github.com/LiudvigVladislav/Phantom/pull/67) | Storage durability | F-02, F-12, F-06, F-13 | ~3 дня | ✅ merged `367a3d61` (#67, 2026-05-08) |
| 8 | [#68](https://github.com/LiudvigVladislav/Phantom/pull/68) | UX cleanup + small fixes | F-14, F-21, F-24 + 3 fixes | ~2 дня | 🟡 PR open — awaiting merge |
| 9 | (PR 2.6) | Calls audio plumbing — `JavaAudioDeviceModule` + AudioFocus + suppress reconnect during call | — | 2–3 дня | 🟦 **отложен post-Phase-5** |

**После завершения Track A** = Alpha-3 release-candidate. Voice + text + calls работают надёжно (calls = experimental, остальное production-quality).

### Transport reliability mini-sprint (2026-05-10/12) — 11 PRs merged, closed-success ✅

Triggered by real-device cross-device tests on Tecno МТС ± VPN that surfaced three concrete failure modes: 70-second connect cycles from keepalive forceReconnect during WS handshake, Reality probe timing out at the wrong (~10 s) inner-socket budget, and Tor on МТС stalling 10+ minutes with zero user feedback. Resolved over three days; all PRs merged into master.

**🎉 Outcome (Test #6, 2026-05-11 21:25-21:32 МТС Wi-Fi without VPN, Tecno Spark Go):** Ghost mode reached `Online via Tor · Ghost` in ~6 minutes on the very first KitchenSink (1/4) attempt of PR-E's Briar-imported bridge pool — bootstrap walked 0 % → 30 % in 1 second, paused at 50 % for ~5 min while Tor built guard circuits, then climbed 50 % → 100 % in another minute. Probe 200 OK over the onion address, WebSocket handshake successful immediately after `Ready`. **First captured logcat proof of Ghost-without-VPN on a Russian carrier network in PHANTOM's history.** Compare: PR-D + old snowflake bridges in the same scenario timed out at 30 % after 12 minutes. The Google-AMP-cache snowflake entries in `bridges-s-ru` (fronted on `www.google.com`) are the most likely cause of the win.

| # | PR | Задача | Статус |
|---|----|--------|--------|
| 1 | [#103](https://github.com/LiudvigVladislav/Phantom/pull/103) | SOCKS connect-timeout, per-kind probe budget, keepalive guard | ✅ merged `647f369d` |
| 2 | [#104](https://github.com/LiudvigVladislav/Phantom/pull/104) | Instrument `XrayService.startBlocking` + robust libXray response parser; bump Tor probe to 90 s | ✅ merged `2f67e218` |
| 3 | [#105](https://github.com/LiudvigVladislav/Phantom/pull/105) | Ephemeral Xray SOCKS port, error-field parser, per-kind probe timeout | ✅ merged `18317548` |
| 4 | [#106](https://github.com/LiudvigVladislav/Phantom/pull/106) | Keepalive guard for WS handshake, `lastPongMark` reset, Reality probe → 20 s | ✅ merged `6b4c4ff5` |
| 5 | [#107](https://github.com/LiudvigVladislav/Phantom/pull/107) | Sync Reality outer probe budget, stream Tor bootstrap %, defensive `tor.stop()` on prepare failure | ✅ merged `2ad57a09` |
| 6 | [#108](https://github.com/LiudvigVladislav/Phantom/pull/108) | **PR-A1 (diag):** sync OkHttp inner timeouts to `callTimeout`, log `vpnActive=true|false` on every `connect()` | ✅ merged `a953d3e4` |
| 7 | [#109](https://github.com/LiudvigVladislav/Phantom/pull/109) | **PR-A2:** filter Reality from chain when `vpnActive=true` (Caddy-log audit confirmed Reality+VPN never reaches the relay edge); ISSUE-015 in KNOWN_ISSUES.md | ✅ merged `75775c00` |
| 8 | [#110](https://github.com/LiudvigVladislav/Phantom/pull/110) | **PR-B:** staged Tor-bootstrap UX with time-keyed copy + live percent (Initial → Negotiating → Searching → Slow → Throttled) | ✅ merged `53a02967` |
| 9 | [#111](https://github.com/LiudvigVladislav/Phantom/pull/111) | **PR-C:** sequential Tor bridge profile rotation — `obfs4-only` (180 s) → `webtunnel-only` (120 s) → `snowflake-only` (180 s) → `mixed` (240 s) with per-profile `tor.stop()` between attempts; UI shows `<profile> (k/4)` | ✅ merged `ed143c60` |
| 10 | [#112](https://github.com/LiudvigVladislav/Phantom/pull/112) | **PR-D:** rotation order retuned per Test #5 МТС data — `Mixed (600 s)` → `Snowflake (360 s)` → `Obfs4 (90 s)` → `Webtunnel (90 s)`; Ghost-mode AllFailed copy now actionable ("Tor is blocked or slowed by this network. Try Private/Reality or enable a VPN."); `KNOWN_ISSUES` ISSUE-016 documents the upstream TSPU root cause | ✅ merged `41506995` |
| 11 | [#113](https://github.com/LiudvigVladislav/Phantom/pull/113) | **PR-E:** import Briar's RU-tuned bridge set (4 snowflake `bridges-s-ru` with Google AMP cache fronted on `www.google.com`, 9 non-default obfs4, 1 meek_lite); new `BridgeProfile.KitchenSink` puts ~17 bridges in one `enableBridges()` call (Briar's empirical strategy) and runs first; rotation order: `KitchenSink (600 s)` → `SnowflakeOnly (420 s)` → `Obfs4Only (180 s)` → `MeekLite (60 s)`; explicit privacy properties matrix added to ISSUE-016 + source-level KDoc | ✅ merged `<latest>` |

**State after mini-sprint (per PR-E + Test #6 confirmation):**

- ✅ Standard works on every tested network (МТС ±VPN, other carriers).
- ✅ Private without VPN works via Reality (production-quality).
- ✅ Private under VPN works via Tor (Reality auto-skipped per audit evidence).
- ✅ Ghost works via Tor on uncensored / VPN-protected networks (mixed reaches Ready in 2-7 min).
- ✅ **Ghost on МТС WITHOUT VPN works** (Test #6 confirmed: ~6 min via KitchenSink with Briar's `bridges-s-ru` Google-AMP-cache snowflake). Single-test caveat — worth a few more МТС sessions before claiming production stability — but the architecture-side question is answered.

**Privacy trade-off accepted (Vladislav A+C, 2026-05-12):**
PR-E's Snowflake-via-Google-AMP fallback routes broker-discovery TLS through Google's AMP cache. Google sees the IP making encrypted TLS to its CDN with a Snowflake-style request pattern; Google does NOT see PHANTOM identity, onion address, contacts, or message content (Tor circuit traffic flows over WebRTC DataChannel directly to a volunteer browser proxy, not through Google). This pattern is the same one Tor Browser ships by default for RU users and that Briar ships in `bridges-s-ru`. Documented in `KNOWN_ISSUES.md` ISSUE-016 with full "what Google sees / does not see" matrix.

**Deferred follow-ups:**
- Telemetry PR (architect's third recommendation — average bootstrap time, bridge success rate, country/network heuristics) — not blocking for NLnet.
- Operations work to deploy additional bridges on non-blocked CIDR (snowflake on a non-Hetzner / non-FlokiNET ASN, obfs4 on additional ranges). Tracked separately post-Alpha-2; needs VPS budget.
- BridgeDB-on-device fetcher (Vladislav's plan Option 3): Briar research showed Briar does not have one either — value is in bridge data freshness, not the fetcher per se. Deferred until bridge-freshness becomes the bottleneck.
- Cross-operator testing (Beeline / Megafon / Tele2): needs additional SIM cards, deferred until accessible.
- Retrospective ADR documenting bridge rotation strategy (architect-recommended).

### First-message reliability sprint (2026-05-13) — PR-G3 + PR-G4 merged ✅

Triggered by Test #28 (2026-05-12) which reproduced the recurring "yellow dot for two minutes on first message to a new contact" symptom. PR-G3 (observability) and PR-G4 (REST forced HTTP/1.1) closed the root cause within one session.

| # | PR | Задача | Статус |
|---|----|--------|--------|
| 1 | [#123](https://github.com/LiudvigVladislav/Phantom/pull/123) | **PR-G3 (diag):** structured `PREKEY_TRACE` logs across `PreKeyLifecycleService`, `PreKeyApiClient`, and `DefaultMessagingService` — bootstrap/upload/verify lifecycle, HTTP-level URL+status+elapsed-ms, distinguish bundle-fetch result `200|404|timeout|429|http<code>` (was previously collapsed to "treated as 404") | ✅ merged `f90ac90c` |
| 2 | [#124](https://github.com/LiudvigVladislav/Phantom/pull/124) | **PR-G4:** pin REST `OkHttpClient` to `Protocol.HTTP_1_1` only (WS already pinned to H1.1, iOS uses Darwin, JVM uses Ktor defaults — all unchanged) | ✅ merged `245c6f09` |
| 3 | (commit `3db97b49`) | **PR-H1a (diag):** per-WS-session epoch `[gen=N s=M]` tagging client-side + `conn_id` + `ack_deliver_received`/`ack_deliver_removed_from_store` server-side. Ruled out zombie-writer hypothesis; pinpointed strict-FIFO violation in outbox flush as the real cause of Test #33 MAC failures | ✅ merged `3db97b49` |
| 4 | [#127](https://github.com/LiudvigVladislav/Phantom/pull/127) | **PR-H2a:** strict FIFO outbox via per-envelope monotonic `sequenceTs` + `outboundSendMutex` serializing live-send vs flush. Wire format unchanged. 5 unit tests including Test #33 layout | ✅ merged `674ce231` |
| 5 | [#128](https://github.com/LiudvigVladislav/Phantom/pull/128) | **PR-H2a.2:** close live-send race during FIFO flush — second mutex layer caught by 7-times-measure review before H2a push (live `send()` could have observed the cleared outbox and raced onto the wire with a higher `sequenceTs`); holds `outboundSendMutex` across the entire flush-Send loop | ✅ merged `72e59ce9` |
| 6 | [#129](https://github.com/LiudvigVladislav/Phantom/pull/129) | **PR-H2b:** idempotent envelope ledger blocks redelivered MAC errors. New `processed_envelopes` table records every decrypted envelope id regardless of payload type; guard runs BEFORE `ratchet.decrypt`. INSERT OR IGNORE for race-safety; 8-day TTL (one day longer than relay store). Forward-only `15.sqm` migration; schema v15 → v16. Marks PROCESSED on success, FAILED_MAC on decrypt failure (debug column only). 6 storage contract tests + 2 messaging tests. Legacy `messages.id` guard kept as defence-in-depth | ✅ merged `7008cf3e` |
| 7 | [#131](https://github.com/LiudvigVladislav/Phantom/pull/131) | **PR-H1b (diag):** per-session WS `SessionStats` (pings_sent, pongs_received, missed_pongs, ping_send_failures, inbound_frames, since_last_*_ms) + server-side `event="session_summary"` log line at unregister with `close_origin` ∈ {client,server,error,none} + `closeReason.await()` extraction. Test #35 then revealed half-open TCP black-hole pattern: server `pings_received=2-5` vs client `pings_sent=11`, `since_last_ping_ms ≈ 153 s` on every dying session — middlebox NAT eviction (МТС) + Tecno HiOS aggressive radio park silently dropping outbound packets while OS still considers socket healthy | ✅ merged `0baa4196` |
| 8 | [#132](https://github.com/LiudvigVladislav/Phantom/pull/132) | **PR-H1c:** stale-socket recovery — six-layer fix. (1) `lastInboundFrameElapsedMs` on `RelayTransport` interface — watchdog triggers off ANY inbound frame, not just Pong. (2) OkHttp `pingInterval(15s)` — independent dead-socket detection in 15 s. (3) `ping_send_failed` → immediate `forceReconnect()` + `break` (was log-and-continue). (4) `TransportState.Reconnecting` distinct state — UI banner "Reconnecting…" instead of going dark. (5) AlarmManager proactive reconnect at 45 s stale-inbound (< 60 s pong-watchdog), safety net for OEMs with throttled AlarmManager. (6) Server-side TCP SO_KEEPALIVE on listener (idle 15s, interval 5s, retries 3) via `socket2` `#[cfg(unix)]`. **Test #37 verified** end-to-end on twin-emulator + Tecno МТС, 12 messages each direction: detection 155 s → 30–46 s, recovery 5 s → ~1 s, zero loss (H2b ledger neutralises re-delivered envelopes seen as `Duplicate envelope (already in ledger)`). UX: routine "Reconnecting…" badge instead of "lagging" outage | ✅ merged `e946caba` |

**What the new traces revealed in Test #29.** With a clean wipe and emu started before phone, phone's bundle fetch for the emu identity timed out at exactly **8009 ms × 4 in a row**; emu's own publish hung **20.7 s then 39.5 s** before `SocketException: Connection reset` from `Http2Reader.readConnectionPreface`. Yet `verify_status` returned `opks_remaining=100` for the same identity within 17 s, and a `curl` from the relay VPS itself fetched the bundle in **242 ms** (HTTP 200, 634 bytes). Caddy access log showed phone's failed bundle GETs **not arriving at Caddy at all** — requests died inside OkHttp before any bytes hit the wire. Failed POSTs from emu logged with `bytes_read: 0` after 30 s and `408 Request Timeout`: client opened the H2 stream, sent headers (`Content-Length: 13903`), then never delivered the body. **Root cause: OkHttp's HTTP/2 implementation gets stuck on REST requests** — both on stream upload and on stale-connection reuse from the pool.

**Test #30 (post-PR-G4 retest):**
- Emu publish bootstrap: **829 ms → 201** (was: 20.7 s + 39.5 s timeouts).
- Phone publish bootstrap: **541 ms → 201**.
- Phone fetch emu bundle on first message: **151 ms → 200 OK on the first try** (was: 8009 ms timeout × 4, then sweep eventually succeeded ~3 minutes later).
- First message envelope sent within **484 ms of `send_start`**, no DEFERRED, no WAITING. **Yellow-dot reproduction step is gone.**

**Deferred follow-ups:**
- **PR-H1e (next priority — diagnostic sprint).** Architect-proposed root-cause investigation: WS dies every 30–50 s on BOTH МТС cellular AND emulator-on-dev-Wi-Fi (no shared carrier path) — points at our stack, not pure carrier behaviour. Scope: (A) tcpdump on relay during one dying session (FIN / RST / outbound Ping packets / TLS close_notify); (B) WS `pingInterval(5s)` experiment vs current 15 s — does middlebox count 15 s as idle?; (C) disable app-level Ping temporarily, leave only OkHttp WS Ping — four heartbeat layers may trip DPI; (D) force `Protocol.HTTP_1_1` on WS upgrade — same hypothesis class that fixed REST in PR-G4. Diagnostic, not a fix sprint.
- **PR-H1d (deferred).** Server-side superseded-close (close old `state.clients[identity]` actively, log `event="superseded" old_conn_id new_conn_id`) + client-side explicit `session.close()` with 300 ms timeout before `forceReconnect`. Improves operational hygiene (zombie sessions on server drop from ~153 s to ~1 s) but changes nothing user-visible — reconnect already 1 s, messages already 100 % delivered, ledger already neutralises duplicates. Reconsider only if H1e finds the cycle is unfixable at root and reconnect storms become permanent reality, or if relay starts suffering FD/memory pressure under load.
- **PR-H2c:** skipped-message-keys in the ratchet (per Signal Spec) + bounded retry on MAC error + dead-letter log. PR-H2a/a.2 closed the *client-side* reorder source (outbox FIFO); PR-H2b made redelivered envelopes idempotent (no double-advance of the chain). H2c is the third leg — making the receiver tolerant of *legitimate* future-message reorder (TCP retransmit pause, multi-path mobile, Tor circuit shifts) by buffering skipped chain keys instead of MAC-failing. Without H2c, any future scenario that legitimately re-orders frames between sender's wire and receiver's decrypt will surface as a MAC fail. The H2b ledger then prevents *that* failure from being re-tried-and-failed-again on relay redelivery, but the original message is still lost.
- ~~WS pong timeout cycle every 60–110 s on both devices remains as **PR-H1b/c** material~~ — **closed by PR-H1b (#131) + PR-H1c (#132) merged 2026-05-13 night, Test #37 verified.** Detection 155 s → 30–46 s, recovery 5 s → ~1 s, zero loss. See rows 7–8 in the table above.
- One stale-H1.1 case on `verify_status` (30 053 ms `SocketTimeoutException` from `Http1ExchangeCodec`). Background path, low priority — connection-pool tuning.
- PR-G5 (UX text + bootstrap gate + fast retry) proposed by the architect — now genuinely cosmetic since PR-G4 fixed the underlying race. Deferred until WS stability lands.

---

## Track B — Security Sprint (Beta-tier блокеры)

**Цель:** security clearance для Beta release + Kickstarter announcement.

**Items 1–4 = Alpha-2 snapshot.** После их закрытия можно тегать `v0.1.0-alpha.2` и подавать external funding programme с честным "first four most user-visible privacy claims closed."

| # | Finding | Что сделать | Оценка | Блокирует | Статус |
|---|---------|-------------|--------|-----------|--------|
| 1 | **F22** SPK/OPK private keys plaintext SQLite | Keystore-wrap (AES-256-GCM) | ~2 дня | Alpha-2 | ✅ merged `6737be91` + `2bcd891e` + `22f0c30c`; QA-pass телефон+эму 2026-05-08 |
| 2 | **F19+F20** Call signalling no E2EE / no Sealed Sender | Wrap SDP/ICE in Double Ratchet + Sealed Sender | ~3 дня | Alpha-2 | ✅ merged `569b868e` (#62, 2026-05-08) |
| 3 | **F8** RatchetState plaintext SQLite | Keystore-wrap RatchetState blob перед записью | ~2 дня | Alpha-2 | ✅ merged `bb36705f` (#60, 2026-05-08) |
| 4 | **F2+F13** SenderKey signing dead | ADR-017 (remove signing) уже draft → реализовать | ~3 дня | Alpha-2 | ✅ merged `c1fe16ed` (#63, 2026-05-08) |
| 5 | **F1** Group control msgs outside Double Ratchet | Wrap SKD/leave/add в Double Ratchet | ~3 дня | Kickstarter | ✅ merged `cceb83fd` (#70, ADR-026, 2026-05-08) |
| 6 | **F3** SenderKey KDF bare SHA-256 | Заменить на HKDF-SHA256 с domain separation | ~1 день | Kickstarter | ✅ merged `1a04dadc` (#69, 2026-05-08) |
| 7 | **F4** Member-leave не ротирует ключи | Full key rotation на leave/remove | ~2 дня | Kickstarter | ✅ merged `6a38e232` (#71, 2026-05-08) |
| 8 | **F11+F26** Shared relay token | Per-user signed challenge | ~3 дня | Kickstarter | ✅ merged `6e90e3c6` (#72, ADR-027, 2026-05-08) — production-validated 2026-05-09 |
| 9–17 | P2 batch (F6, F7, F9, F10, F12 retry, F14, F18, F23, F25) | Зачистка во время Beta-полировки | — | — | ⬜ |

**Verified clean / уже закрыто:**
- ✅ Identity DH private key Keystore-wrapped
- ✅ **F22** SPK/OPK private keys Keystore-wrapped — `6737be91` (impl) + `2bcd891e` (инструментированный тест) + `22f0c30c` (SECURITY_ROADMAP). QA-pass 2026-05-08.
- ✅ Sealed Sender на каждом regular 1:1 message type
- ✅ F15 (identity-as-ratchet-key) — fixed via fresh ephemeral DH
- ✅ F17 — notification callback exception swallow logged
- ✅ F12 partial — SPK Ed25519 sig verify + OPK atomic single-use
- ✅ **F8** RatchetState Keystore-wrapped — `bb36705f` (#60). Read receipts via Sealed Sender — `f62569e6` (#59). 2026-05-08.
- ✅ **C-2** Read receipts routed through sealed DR pipeline — `f62569e6` (#59, 2026-05-08)
- ✅ **F19+F20** Call signalling through Double Ratchet + Sealed Sender; ADR-025 — `569b868e` (#62, 2026-05-08)
- ✅ **F2+F13** Dead SenderKey signing keypair removed; ADR-017 Accepted; migration 14.sqm — `c1fe16ed` (#63, 2026-05-08)
- ✅ **F3** SenderKey KDF: bare SHA-256 → HKDF-SHA256 with iteration-bound salt + `_v2` info strings — `1a04dadc` (#69, 2026-05-08)
- ✅ **F1** Group control messages (invite/SKD/leave/add) + group ciphertext broadcasts now route through DR + Sealed Sender; ADR-026 — `cceb83fd` (#70, 2026-05-08)
- ✅ **F4** Member-leave proactively rotates own SenderKey + redistributes fresh SKD to remaining members — `6a38e232` (#71, 2026-05-08)
- ✅ **F11+F26** Shared WS token replaced with per-user Ed25519 signed-challenge auth (TOFU first connect + 1:1 binding); ADR-027 — `6e90e3c6` (#72, 2026-05-08). Production-validated on Tecno МТС + emulator 2026-05-09. RELAY_TOKEN BuildConfig field removed from APK.

---

## Track C — Release Polish

**Цель:** репо в состоянии «reviewer тратит 5 минут и думает "это серьёзно"» к Alpha-2 release window.

Утверждённый план: [`~/.claude/plans/cosmic-sparking-otter.md`](../../.claude/plans/cosmic-sparking-otter.md) (на машине разработчика).

| Phase | Что | Время | Статус |
|-------|-----|-------|--------|
| 1 | Repo cleanup + .gitignore (remove `google-services.json`, `.kotlin/`; add 12 lines; commit `PHANTOM_ROADMAP_2026.md` + `ARCHITECTURAL_DECISIONS_TODO.md`) | 30 мин (factually ~2 hours with Firebase rotation + plugin conditional fix) | ✅ done 2026-05-08 (#46 + #47) |
| 2 | Funding plumbing — `funding.json` (secondary funding programme hard prerequisite) + `.github/FUNDING.yml` | 1 час | ✅ done (#49) |
| 3 | README polish — License → AGPL-3.0, Status → Alpha 2, ссылки на Threat Model + ADR + Codeberg, softer Funding wording, Mermaid диаграмма, 5 новых секций; правки в RELEASE_NOTES + CONTRIBUTING | 2 часа | ✅ done (#49 + #51 + #52 + #55) |
| 4 | English exec summary для Threat Model + Doctrine; ADR index `docs/adr/README.md`; `ARCHITECTURE.md` в корне | 2 часа | ✅ done (#50 + #52) |

**Итого Track C:** один день работы, не блокирует другие треки.

**Phase 5 (отложено):**
- Demo GIF (5 сек, эмулятор)
- Pinned "Roadmap to v1.0" issue на GitHub
- PGP key для security@phntm.pro
- Полные английские переводы Threat Model + Doctrine
- `.github/ISSUE_TEMPLATE/` + PR templates

---

## Track E — Censorship Resistance (Stage 5)

**Цель:** PHANTOM работает в RU без VPN, без Orbot, без сторонних приложений. Censorship resistance — central pitch проекта.

Track E architecture is split across two formal ADRs: [`ADR-016`](../adr/ADR-016-tor-unified-push-hybrid-transport.md) (Tor + UnifiedPush hybrid; Stages 5A–5D bridge progression) and [`ADR-019`](../adr/ADR-019-Xray-REALITY-Outer-Transport.md) (Xray VLESS+REALITY as outer transport; Stage 5E, supersedes 5C/5D for RU carrier traffic). All Stage 5 commits have landed on master via numbered PRs; no separate sprint branch remains open.

| Stage | Что | Статус | Доказательство |
|-------|-----|--------|----------------|
| 5A | kmp-tor embedded daemon + lifecycle | ✅ done | merged ранее |
| 5B | onion address resolution + RELAY_ONION_URL config | ✅ done | merged ранее |
| 5C | WebTunnel bridges (operator-controlled, Hetzner) | ⚠️ stalled at 14 % bootstrap on RU MTS | Test 11 / 2026-05-06 — TSPU 16-KB curtain |
| 5D | Multi-bridge fan-out (FlokiNET, non-Hetzner) | ⚠️ same TSPU stall on FlokiNET RO | Test 13 / 2026-05-06 — curtain is behavioural, not ASN-based |
| 5E.A | Server-side Xray VLESS+REALITY (Hetzner :8443) | ✅ done | NekoBox validated; deploy/xray/ rendered + running |
| 5E.B.1 | GitHub Actions workflow build-libxray.yml | ✅ done | `5cca2976` + `f5e21fb3` |
| 5E.B.2/3 | KMP module `:shared:core:xray` + libXray vendoring | ✅ done | `96fcbf1a` |
| 5E.B.4 | Wire XrayService into PhantomMessagingService | ✅ done | `98245f69` |
| 5E.B.5 | Production validation Tecno МТС без VPN | ✅ done | Test 14 / 2026-05-07 — text + voice 5 sec (chunks 55 KB) пролетают через REALITY |

**Backlog после Stage 5 (cleanup, не блокеры для Alpha-2):**

- ✅ **Prekey republish fix** — merged `1fc454cc` (#53, 2026-05-07). Client-side: verify and republish bundle on every WS reconnect. Closes the long-offline user → bundle 404 path.
- ✅ **Restore strict Xray routing** — done 2026-05-08 in PR #45 (commit `d7ba3a41`). Three-rule chain (multi-syntax domain match + port-443 fallback + catchall blackhole). Verified end-to-end via Caddy `remote_ip: 172.18.0.7`.
- ✅ **ADR-019 Xray REALITY rationale** — done 2026-05-08 in PR #48 (commit `20e71fbb`). 371-line ADR covering five sub-rationale subsections + threat model + known limitations.
- ✅ **PR в master** для Stage 5 — done 2026-05-08 morning as #43 (squash merge).

**Production endpoint** (запекён в `OperatorXrayConfig.kt`): `65.108.154.152:8443`, SNI `www.microsoft.com`, capability UUID `09c6fd0e-…`. Single shared UUID — capability-style auth, не identity. Rotation playbook в `deploy/xray/render-config.sh` header.

---

## Track D — Alpha 2 Features

После Track A + Track B items 1–4:

| Feature | ADR | Оценка | Статус |
|---------|-----|--------|--------|
| Voice notes без хака | — | покрыто PR 3 (Track A) | 🟡 |
| Attachments (photos/files via encrypted MinIO) | ADR-013 (draft в `ARCHITECTURAL_DECISIONS_TODO.md`) | ~2 недели | ⬜ |
| Stable groups с правильным crypto | требует Track B 5/6/7 | ~2 недели | ⬜ |
| Public channels (read-only broadcast) | новый ADR | ~1 неделя | ⬜ |
| Hosted username directory | ADR-007 (draft) | ~1 неделя | ⬜ |
| Pluggable transports (obfs4) | ADR-015 (draft) | ~3 недели | ⬜ |
| iOS port (Phase-1 milestone) | ADR-014 (draft) | 3–4 месяца | ⬜ |

---

## Реалистичный таймлайн

### Неделя 1 (May 3–9) — Reliability + Release Polish

- [ ] **Сегодня (3 May):** ручной тест PR 3 на Tecno ↔ emu → merge если ОК
- [ ] **May 4–5 (пн-вт):** Track C Phase 1 + Phase 2 (репо cleanup + funding plumbing) — полдня
- [ ] **May 6–8:** PR 1 data integrity (~3 дня)
- [ ] **May 9 (выходные):** Track C Phase 3 + Phase 4 (README + docs polish, ~4 часа)

### Неделя 2 (May 10–16) — PR 4 + Security старт

- [ ] **May 10–13:** PR 4 storage durability (~3 дня)
- [ ] **May 14–16:** PR 5 UX cleanup (~2 дня) + старт Track B F22

### Неделя 3 (May 17–23) — Security gate перед Alpha-2 release

- [ ] **May 17–19:** F22 завершить (~2 дня)
- [ ] **May 20–22:** F8 RatchetState wrap (~2 дня)
- [ ] **May 23:** старт F19+F20 call signalling E2EE

### Неделя 4 (May 24–31) — финальный спринт к Alpha-2 release

- [ ] **May 24–27:** F19+F20 (~3 дня) + F2+F13 (~3 дня, можно параллельно)
- [ ] **May 28–29:** Tag `v0.1.0-alpha.2`, Alpha-2 snapshot prep
- [ ] **May 30–31:** Финальное ревью release draft V2, proofread, чек-лист
- [ ] **June 1, 12:00 CEST:** **Release Alpha-2 + первое окно external funding submissions** ✊

### Июнь — продолжение Security + дополнительные funding submissions

- [ ] **June 1–7:** мигание после Alpha-2 release, продолжение Track B (F1, F3, F4)
- [ ] **June 8–14:** реализация attachments (Track D первая фича) ИЛИ groups hardening
- [ ] **June 15–22:** Track B F11+F26 (relay token), Track B P2 batch
- [ ] **June 23–29:** **Submit secondary external funding** (window end of June)
- [ ] **Параллельно (rolling):** rolling external funding submissions per 

### Июль — Сентябрь (Alpha 2 → Beta)

- [ ] Attachments полностью
- [ ] Groups hardening (F1, F3, F4 закрыты)
- [ ] Public channels
- [ ] Username directory
- [ ] Closing remaining Track B P2 items
- [ ] Pluggable transports (obfs4) старт
- [ ] iOS port старт (Phase-1 milestone if external funding lands)

### Октябрь — Декабрь (Beta)

- [ ] Calls 1:1 voice/video — вернуться к PR 2.6 когда транспорт стабилен
- [ ] Desktop client (Compose JVM)
- [ ] BLE / Wi-Fi Direct local transport
- [ ] Multi-device identity (linked-device trust)

### Q1 2027 (v1.0)

- [ ] iOS финал
- [ ] Self-hosted relay kit для businesses
- [ ] **Third-party аудит** (Cure53 / Trail of Bits) — за Kickstarter средства
- [ ] F-Droid / Google Play / App Store launch
- [ ] **UnifiedPush integration (Phase 5)** — устраняет 30-секундный reconnect cycle на aggressive-OEM (Feb 2027)

---

## Зависимости

```
PR 3 (voice) ──┬─► PR 1 ──► PR 4 ──► PR 5 ──► Track A done
               │
Track C ──────┘  (параллельно, любой день)

PR 5 done ──► Track B F22 ──► F8 ──► F19+F20 ──► F2+F13 ──► Alpha-2 snapshot
                                                              │
                                                              ▼
                                                          tag v0.1.0-alpha.2
                                                              │
                                                              ▼
                                                          Alpha-2 release (1 June) + external funding window opens

Track B 5–8 → продолжение в июне → Kickstarter clearance

Track D (Alpha 2 features) → начинается после Track B 1–4 минимум
```

---

## Риски и mitigation

1. **24 дня до Alpha-2 release window — реалистично только если security work идёт без задержек.** Mitigation: даже если успеем закрыть только F22 + F8, это уже обоснованный snapshot. Release draft V2 уже признаёт что F19+F20 в работе.
2. **Track B сложнее чем оценено** — security work часто всплывает с edge cases. Mitigation: каждый item — отдельный PR, не бандлим.
3. **Voice/calls остаются нестабильными до Phase 5 (UnifiedPush, Feb 2027)** — это known limitation, calls=experimental в Alpha. Не блокирует Alpha-2 release.
4. **Solo founder bottleneck** — если Vladislav заболеет/занят неделю, release window под угрозой. Mitigation: Phase 4 release polish даёт максимальный снимок репо, выпуск может идти даже без полного Track B closure.

---

## External funding

The canonical funding-channel list lives in [](../../funding.json) at the repository root. That file is the source of truth for what funding programmes are pursued and on what schedule.


---

## Что мы делаем СЕЙЧАС (живой указатель)

> Обновляется по мере прогресса. Когда задача меняется — менять этот блок и резерв-помечать в треках выше.

**Текущий фокус (2026-05-09):** **ВСЯ Track A + ВСЯ Track B (1–8) закрыты.** Repository security gate для Alpha-2 + Kickstarter обоих пройден. Council-revised план задумывал submit на день 15 с одним security item (F22) — фактически закрыли 8 items за два рабочих дня.

**Что закрыто за сессии 2026-05-08 → 2026-05-09:**
- Track A: PR #65 data-integrity (F-08/F-01/F-09/F-04), #67 storage durability (F-02/F-12/F-06/F-13), #68 UX cleanup (F-14/F-21/F-24 + 3 QA fixes)
- Track B: #56–#58 (F22 + tests + roadmap), #59 (C-2 read receipts), #60 (F8 RatchetState wrap), #62 (F19+F20 calls E2EE + ADR-025), #63 (F2+F13 + ADR-017 Accepted), #69 (F3 HKDF), #70 (F1 group control + ADR-026), #71 (F4 leave rotation), #72 (F11+F26 + ADR-027)
- Production validation 2026-05-09: signed-challenge auth работает на Tecno МТС без VPN через Xray; text + voice + call signaling round-trip OK на двух устройствах

**Что НЕ кодовая работа, ручные действия Vladislav:**
- Demo video Stage 5E (council plan Day 3 — ещё не снято)
- Donation rails: Open Collective / Liberapay / Polar / BMAC
- FUTO Microgrants application
- Public write-up HN + Хабр
- NLnet draft V2 финализация + submission через portal
- FLOSS/fund submission
- Tag `v0.1.0-alpha.2` (можно сразу — security gate пройден)

**QA bugs reported 2026-05-09 (parked, не блокеры):**
1. **Mic one-way audio при звонке** — phone↔emu testing показал что одна сторона не слышит другую. Совпадает с известным deferred PR 2.6 ("Calls audio plumbing — JavaAudioDeviceModule + AudioFocus + suppress reconnect"). Severity: medium (calls = experimental в Alpha).
2. **После hangup возврат на ChatList, не на ChatScreen** — нав баг, ~5 строк. Severity: low.
3. **Реакции не доходят до собеседника** — send-path или receive-path сломан для TYPE_REACTION. Нужна диагностика. Severity: medium.
4. **Нет "Удалить чат" в long-press menu на ChatList** — фича отсутствует, нужна с подменю "Удалить у себя / Удалить у обоих" (аналог `deleteMessageForBoth`). Severity: low.
5. **Дизайн полировка + значки не соответствуют референсам** — отдельный sprint, ui-prototyper agent + Vladislav как arbiter. Severity: low (но visible to NLnet reviewers).

Полный 25-day plan в `~/.claude/projects/.../memory/plan_25_days_to_release.md` (локально, не в репо).

**Активные ветки:** все merged. Master в clean state.

**Известные follow-ups (deferred, не блокеры Alpha-2):**
- Track B P2 batch (F6, F7, F9, F10, F12 retry, F14, F18, F23, F25) — Beta polish window
- Track D Alpha-2 features (attachments, stable groups, channels, username dir, iOS port) — post-NLnet roadmap
- PR 2.6 calls audio plumbing — заодно с Bug 1 выше
- Firebase Console SHA-1 fingerprints — defence-in-depth
- ADR-020/021/022 (adaptive transport, multi-server Xray, iOS XCFramework) — Phase 2 per ADR-019

---

## Связанные документы

- [`docs/project/EXECUTION_PLAN_2026_05_01.md`](EXECUTION_PLAN_2026_05_01.md) — детали Track A + Track B
- [`docs/project/PHANTOM_ROADMAP_2026.md`](PHANTOM_ROADMAP_2026.md) — внутренний 12-месячный execution plan
- [`docs/project/ARCHITECTURAL_DECISIONS_TODO.md`](ARCHITECTURAL_DECISIONS_TODO.md) — 10 pending ADR драфтов
- [`KNOWN_ISSUES.md`](../../KNOWN_ISSUES.md) — публичный список багов и архитектурных choices
- [`ROADMAP.md`](../../ROADMAP.md) — публичный высокоуровневый roadmap
- Локально (untracked): release draft V2, Привлечение инвесторов PDF
