# PHANTOM — Master Timeline 2026

> **Living document.** Источник истины для трекинга всех треков работы. Обновляется по мере merge каждого PR — чекбоксы превращаются в `[x]`, в "Сделано" секцию добавляется коммит.

**Last updated:** 2026-05-07  
**Master HEAD:** `d094ca8f` (no master merges since 2026-05-03 — Stage 5 work lives on `feat/tor-stage5-bridges-via-onionwrapper` pending PR)  
**Ближайший дедлайн:** NLnet 2026-06-01 (25 дней)

---

## Большая картина — 4 параллельных трека

```
                                         NLnet      FLOSS
  Сегодня                               (1 июня)  (30 июня)
  2026-05-03 ──────────── 4 недели ────────►│────── 4 нед ──►│──── ... до v1.0
                                            │                │
  Track A — RELIABILITY SPRINT ─────────►│  │                │
  (5 PR, ~13 дней работы)                   │                │
                                            │                │
  Track B — SECURITY SPRINT ─────────►│      │                │
  (10 P1 блокеров до Kickstarter)            │                │
                                            │                │
  Track C — GRANT READINESS ──────►│         │                │
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
| 5 | feat/voice-message-chunking-pr3 | **Voice messages chunking** | F-05 | ~5 дней | 🟡 pushed `638fbbdd`, ждёт ручной тест |
| 6 | (PR 1) | Data integrity edges | F-08, F-01, F-09, F-04 | ~3 дня | ⬜ |
| 7 | (PR 4) | Storage durability | F-02, F-12, F-06, F-13 | ~3 дня | ⬜ |
| 8 | (PR 5) | UX cleanup + small fixes | F-14, F-21, F-24 + 3 QA bugs | ~2 дня | ⬜ |
| 9 | (PR 2.6) | Calls audio plumbing — `JavaAudioDeviceModule` + AudioFocus + suppress reconnect during call | — | 2–3 дня | 🟦 **отложен post-Phase-5** |

**После завершения Track A** = Alpha-3 release-candidate. Voice + text + calls работают надёжно (calls = experimental, остальное production-quality).

---

## Track B — Security Sprint (Kickstarter блокеры)

**Цель:** security clearance для Kickstarter announcement + NLnet credibility.

**Items 1–4 = NLnet snapshot.** После их закрытия можно тегать `v0.1.0-alpha.2` и подавать NLnet с честным "first four most user-visible privacy claims closed."

| # | Finding | Что сделать | Оценка | Блокирует | Статус |
|---|---------|-------------|--------|-----------|--------|
| 1 | **F22** SPK/OPK private keys plaintext SQLite | Keystore-wrap (AES-256-GCM) | ~2 дня | NLnet | ⬜ |
| 2 | **F19+F20** Call signalling no E2EE / no Sealed Sender | Wrap SDP/ICE in Double Ratchet + Sealed Sender | ~3 дня | NLnet | ⬜ |
| 3 | **F8** RatchetState plaintext SQLite | Keystore-wrap RatchetState blob перед записью | ~2 дня | NLnet | ⬜ |
| 4 | **F2+F13** SenderKey signing dead | ADR-017 (remove signing) уже draft → реализовать | ~3 дня | NLnet | ⬜ |
| 5 | **F1** Group control msgs outside Double Ratchet | Wrap SKD/leave/add в Double Ratchet | ~3 дня | Kickstarter | ⬜ |
| 6 | **F3** SenderKey KDF bare SHA-256 | Заменить на HKDF-SHA256 с domain separation | ~1 день | Kickstarter | ⬜ |
| 7 | **F4** Member-leave не ротирует ключи | Full key rotation на leave/remove | ~2 дня | Kickstarter | ⬜ |
| 8 | **F11+F26** Shared relay token | Per-user signed challenge | ~3 дня | Kickstarter | ⬜ |
| 9–17 | P2 batch (F6, F7, F9, F10, F12 retry, F14, F18, F23, F25) | Зачистка во время Beta-полировки | — | — | ⬜ |

**Verified clean / уже закрыто:**
- ✅ Identity DH private key Keystore-wrapped
- ✅ Sealed Sender на каждом regular 1:1 message type
- ✅ F15 (identity-as-ratchet-key) — fixed via fresh ephemeral DH
- ✅ F17 — notification callback exception swallow logged
- ✅ F12 partial — SPK Ed25519 sig verify + OPK atomic single-use

---

## Track C — Grant Readiness

**Цель:** репо в состоянии "reviewer тратит 5 минут и думает 'это серьёзно'" к подаче на NLnet.

Утверждённый план: [`~/.claude/plans/cosmic-sparking-otter.md`](../../.claude/plans/cosmic-sparking-otter.md) (на машине разработчика).

| Phase | Что | Время | Статус |
|-------|-----|-------|--------|
| 1 | Repo cleanup + .gitignore (remove `google-services.json`, `.kotlin/`; add 12 lines; commit `PHANTOM_ROADMAP_2026.md` + `ARCHITECTURAL_DECISIONS_TODO.md`) | 30 мин | ⬜ |
| 2 | Funding plumbing — `funding.json` (FLOSS/fund hard prerequisite) + `.github/FUNDING.yml` | 1 час | ⬜ |
| 3 | README polish — License → AGPL-3.0, Status → Alpha 2, ссылки на Threat Model + ADR + Codeberg, softer Funding wording, Mermaid диаграмма, 5 новых секций; правки в RELEASE_NOTES + CONTRIBUTING | 2 часа | ⬜ |
| 4 | English exec summary для Threat Model + Doctrine; ADR index `docs/adr/README.md`; `ARCHITECTURE.md` в корне | 2 часа | ⬜ |

**Итого Track C:** один день работы, не блокирует другие треки.

**Phase 5 (отложено):**
- Demo GIF (5 сек, эмулятор)
- Pinned "Roadmap to v1.0" issue на GitHub
- PGP key для security@phntm.pro
- Полные английские переводы Threat Model + Doctrine
- `.github/ISSUE_TEMPLATE/` + PR templates

---

## Track E — Censorship Resistance (Stage 5)

**Цель:** PHANTOM работает в RU без VPN, без Orbot, без сторонних приложений. Прямое требование для NLnet review (censorship resistance — central pitch).

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

**Backlog после Stage 5 (cleanup, не блокеры для NLnet body):**

- ⬜ **Prekey republish fix** — pre-existing bug, surfaced by Stage 5 testing. Long-offline user → bundle 404 → no-one can send to them. Client-side retry on connect OR relay-side fallback на signed-prekey-only bundle. ~1-2 часа.
- ⬜ **Restore strict Xray routing** — `7b4ebf77` temporarily relaxed routing к `inboundTag only` (debugging `full:relay.phntm.pro` миссы). Заменить на `domain:relay.phntm.pro` без `full:` префикса, вернуть blackhole-by-default. Server-side change. ~30 мин + redeploy.
- ⬜ **ADR-019 Xray REALITY rationale** — for NLnet body. Threat model, license posture (MPL-2.0 Xray-core aggregation OK with AGPL), что в Beta планах (multi-server fan-out, separate UUID per user). ~1 час.
- ⬜ **PR в master** для Stage 5 — открыть когда выше cleanup сделан.

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
| iOS port (NLnet Milestone 1) | ADR-014 (draft) | 3–4 месяца | ⬜ |

---

## Реалистичный таймлайн

### Неделя 1 (May 3–9) — Reliability + Grant Prep

- [ ] **Сегодня (3 May):** ручной тест PR 3 на Tecno ↔ emu → merge если ОК
- [ ] **May 4–5 (пн-вт):** Track C Phase 1 + Phase 2 (репо cleanup + funding plumbing) — полдня
- [ ] **May 6–8:** PR 1 data integrity (~3 дня)
- [ ] **May 9 (выходные):** Track C Phase 3 + Phase 4 (README + docs polish, ~4 часа)

### Неделя 2 (May 10–16) — PR 4 + Security старт

- [ ] **May 10–13:** PR 4 storage durability (~3 дня)
- [ ] **May 14–16:** PR 5 UX cleanup (~2 дня) + старт Track B F22

### Неделя 3 (May 17–23) — Security gate для NLnet

- [ ] **May 17–19:** F22 завершить (~2 дня)
- [ ] **May 20–22:** F8 RatchetState wrap (~2 дня)
- [ ] **May 23:** старт F19+F20 call signalling E2EE

### Неделя 4 (May 24–31) — финальный спринт к NLnet

- [ ] **May 24–27:** F19+F20 (~3 дня) + F2+F13 (~3 дня, можно параллельно)
- [ ] **May 28–29:** Tag `v0.1.0-alpha.2`, NLnet snapshot prep
- [ ] **May 30–31:** Финальное ревью NLnet draft V2, proofread, чек-лист
- [ ] **June 1, 12:00 CEST:** **Подать NLnet** ✊

### Июнь — FLOSS + продолжение Security

- [ ] **June 1–7:** мигание после NLnet, продолжение Track B (F1, F3, F4)
- [ ] **June 8–14:** реализация attachments (Track D первая фича) ИЛИ groups hardening
- [ ] **June 15–22:** Track B F11+F26 (relay token), Track B P2 batch
- [ ] **June 23–29:** **Подать FLOSS/fund** (deadline 30 June)
- [ ] **Параллельно (rolling):** FUTO concept note, Emergent Ventures, Awesome Foundation

### Июль — Сентябрь (Alpha 2 → Beta)

- [ ] Attachments полностью
- [ ] Groups hardening (F1, F3, F4 закрыты)
- [ ] Public channels
- [ ] Username directory
- [ ] Closing remaining Track B P2 items
- [ ] Pluggable transports (obfs4) старт
- [ ] iOS port старт (если получили NLnet — это Milestone 1)

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

PR 5 done ──► Track B F22 ──► F8 ──► F19+F20 ──► F2+F13 ──► NLnet snapshot
                                                              │
                                                              ▼
                                                          tag v0.1.0-alpha.2
                                                              │
                                                              ▼
                                                          NLnet submit (1 June)

Track B 5–8 → продолжение в июне → Kickstarter clearance

Track D (Alpha 2 features) → начинается после Track B 1–4 минимум
```

---

## Риски и mitigation

1. **NLnet 29 дней — реалистично только если security work идёт без задержек.** Mitigation: даже если успеем закрыть только F22 + F8 (items 1, 3), это уже обоснованный snapshot. NLnet draft V2 уже признаёт что F19+F20 в работе.
2. **Track B сложнее чем оценено** — security work часто всплывает с edge cases. Mitigation: каждый item — отдельный PR, не бандлим.
3. **Voice/calls остаются нестабильными до Phase 5 (UnifiedPush, Feb 2027)** — это known limitation, calls=experimental в Alpha. Не блокирует NLnet.
4. **Solo founder bottleneck** — если Vladislav заболеет/занят неделю, NLnet deadline под угрозой. Mitigation: Phase 4 grant readiness даёт максимальный снимок репо, заявка может идти даже без полного Track B closure.

---

## Грантовые программы (полный список)

Из [`Привлечение инвесторов или получение грантов..pdf`](../../Привлечение инвесторов или получение грантов..pdf) (untracked, локально):

| Программа | Сумма | Дедлайн | Шанс | Статус |
|-----------|-------|---------|------|--------|
| **NLnet NGI Zero Commons Fund** | €30,000 | 2026-06-01 | 15–25% | 🟡 draft V2 готов |
| **FUTO Microgrants** | $2,000 (Mac mini) | rolling | 25–40% | ⬜ концепт-нота |
| **FLOSS/fund (Zerodha)** | $25,000 | 2026-06-30 | — | ⬜ требует funding.json |
| **Open Source Collective** | fiscal host | rolling | high | ⬜ регистрация |
| **Emergent Ventures (Mercatus)** | $5–10K | rolling | low | ⬜ 3-вопросная форма |
| **OTF Internet Freedom Fund** | $50–80K | rolling | ≤5% | ⬜ концепт-нота |
| **Awesome Foundation Digital Privacy** | $1,000 | monthly | — | ⬜ |
| **Access Now Project Galileo** | DDoS/WAF | через спонсора | — | ⬜ |
| **JetBrains Open Source License** | All Products | rolling | high | ⬜ |
| **Liberapay / Polar / BMAC / thanks.dev** | micro-donations | — | — | ⬜ setup в Track C Phase 2 |

**Контактный email:** `hello@phntm.pro`

**Программы исключены** (с обоснованием в PDF):
- Mozilla MOSS — на indefinite pause с 2020
- Sovereign Tech Fund — explicit ban на messaging apps
- EU Horizon / NGI Sargasso — требует EU lead applicant
- OTF FOSS Sustainability Fund — closed до spring 2027

---

## Что мы делаем СЕЙЧАС (живой указатель)

> Обновляется по мере прогресса. Когда задача меняется — менять этот блок и резерв-помечать в треках выше.

**Текущий фокус (2026-05-07):** Track E (Stage 5 censorship resistance) production-validated — это закрытая большая работа недели. PR 3 voice chunking также косвенно подтверждён (chunks по 55 KB долетают через Xray в обе стороны, Tecno ↔ эмулятор). Готовы к двум параллельным следующим шагам:

1. **PR 3 merge** — формально (5 мин)
2. **Track C Phase 1 (repo cleanup)** — БЛОКЕР для NLnet, удалить `apps/android/google-services.json` из git tracking (live Firebase config currently committed). 30 мин.
3. **Prekey republish fix** — closure для Stage 5 побочного бага. ~1-2 часа.
4. **Restore strict Xray routing** + **ADR-019 черновик** — Stage 5 cleanup, ~1.5 часа total.

**Активные ветки:**
- `feat/tor-stage5-bridges-via-onionwrapper` — Stage 5E (5 коммитов сегодня, не в master)
- `feat/voice-message-chunking-pr3` (`638fbbdd`) — PR 3 ждёт merge

**APK с USE_XRAY=true для теста:** `apps/android/build/outputs/apk/debug/android-debug.apk` (192 MB, debug, 4 ABI). Активирован через `local.properties` `xray.enabled=true`.

---

## Связанные документы

- [`docs/project/EXECUTION_PLAN_2026_05_01.md`](EXECUTION_PLAN_2026_05_01.md) — детали Track A + Track B
- [`docs/project/PHANTOM_ROADMAP_2026.md`](PHANTOM_ROADMAP_2026.md) — внутренний 12-месячный execution plan
- [`docs/project/ARCHITECTURAL_DECISIONS_TODO.md`](ARCHITECTURAL_DECISIONS_TODO.md) — 10 pending ADR драфтов
- [`KNOWN_ISSUES.md`](../../KNOWN_ISSUES.md) — публичный список багов и архитектурных choices
- [`ROADMAP.md`](../../ROADMAP.md) — публичный высокоуровневый roadmap
- Локально (untracked): NLnet draft V2, Привлечение инвесторов PDF
