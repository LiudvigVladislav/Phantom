# PHANTOM — Master Timeline 2026

> **Living document.** Источник истины для трекинга всех треков работы. Обновляется по мере merge каждого PR — чекбоксы превращаются в `[x]`, в "Сделано" секцию добавляется коммит.

**Last updated:** 2026-05-12  
**Master HEAD:** Track A complete (5 PRs ✅). Track B items 1–8 ALL merged — **Kickstarter security blockers closed**. F11+F26 signed-challenge auth production-validated on Tecno МТС + emulator with the new APK 2026-05-09. **Transport reliability mini-sprint extended to 11 PRs (#103–#113) merged 2026-05-10/12 — closed-success 2026-05-11 21:32 МТС.** Reality+VPN audited and gated, Tor staged-UX shipped, bridge profile rotation live and re-tuned per МТС data, Briar's RU-tuned bridge set imported (Snowflake-via-Google-AMP fallback). **🎉 Test #6 confirms Ghost on МТС WITHOUT VPN works** — `Online via Tor · Ghost` in ~6 minutes via KitchenSink (1/4) on the very first attempt; bootstrap reached 100 %, probe 200 OK, WS handshake successful through onion. First captured logcat proof of Ghost-without-VPN on a Russian carrier network in PHANTOM's history.  
**Ближайший release window:** 2026-06-01 (20 days); council-revised plan targets submit on day 15 = 2026-05-22 with a 10-day buffer.

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
| 5 | feat/voice-message-chunking-pr3 | **Voice messages chunking** | F-05 | ~5 дней | ✅ merged `41b9fb94` (#32, 2026-05-04). ⚠️ 8 KB chunk-size fix `6d0215d3` (Tecno reconnect window) остался на ветке — нужен отдельный merge |
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

**ADR-018** (Tor + Bridges + Xray REALITY hybrid). Основные коммиты на ветке `feat/tor-stage5-bridges-via-onionwrapper` (5 stage-5 коммитов запушены, PR в master не открыт).

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
