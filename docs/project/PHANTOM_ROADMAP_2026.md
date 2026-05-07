# PHANTOM — 12-Month Strategic Roadmap

**Дата:** 28 апреля 2026
**Период:** Май 2026 — Апрель 2027
**Темп работы:** Full-time (35-40 ч/нед = ~1700 часов на год)
**Текущий state:** Alpha 1 shipped (v0.1.0-alpha.1, 27 апреля 2026)
**Автор:** Vladislav Liudvig / Willen LLC
**Версия документа:** 1.0

---

## EXECUTIVE SUMMARY

### Где мы сейчас

PHANTOM Alpha 1 **функционально полнее** чем казалось до inventory:
- Production-grade криптография (Double Ratchet, Sealed Sender, libsodium)
- Groups + channels на ~70%
- WebRTC voice calls (Android, audio-only)
- Voice messages (с известными OEM-ограничениями)
- App-lock biometric, disappearing messages с E2E sync, Trust Tier flow
- 60 тестов, 16 instrumented crypto-тестов
- Production relay в Hetzner Helsinki
- Legal documentation (ToS + Privacy Policy EN+RU)
- Threat Model v1, 6 ADRs

### Где мы должны быть к Апрелю 2027

- **Beta 1** released (publicly tested, audited)
- **iOS** at functional parity с Android
- **Premium tiers** live (Free/Plus/Pro/Lifetime/Business)
- **Группы** до 1000 участников production-stable, **каналы** работают
- **Push notifications** работают на обеих платформах
- **F13/F14/F15** identity-architecture issue resolved (prekey infrastructure)
- **Username uniqueness** + verification system live
- **Audit** completed (NLnet free service Radically Open Security)
- **NLnet milestones** все четыре delivered
- **Kickstarter** ready (если решим запускать)

### Ключевые архитектурные блокеры

Ранжированы по критичности:

1. **F13/F14/F15** — identity ключ переиспользуется как ratchet key. **Security блокер**, должно быть решено перед публичным adoption. ~6-8 недель работы.
2. **Premium infrastructure** — Stripe, subscription state, feature gating, server validation. ~6-8 недель.
3. **iOS parity** — pure stub сейчас, нужен полный build из KMP shared. ~10-12 недель.
4. **Attachment server** — нужен для voice/files >50KB, решает Tecno bug. ~4 недели.
5. **Push notifications** (UnifiedPush + APNs hybrid) — критично для retention. ~3-4 недели.

### Что финансирует NLnet (если получим)

NLnet €30,000 на 12 месяцев финансирует **только core FOSS R&D**:
- iOS client feature parity (M1)
- Group messaging protocol + spec (M2)
- Censorship resistance (Tor, pluggable transports) (M3)
- Audit + Threat Model v2 + Beta 1 (M4)

Premium / Stripe / Recovery Phrase / verification authority — **отдельный коммерческий трек на собственные средства Willen LLC и/или Kickstarter**, не финансируется грантом.

---

## ROADMAP STRUCTURE

12 месяцев разбиты на **6 фаз** по 2 месяца. Каждая фаза имеет:
- Goal (что должно быть готово к концу)
- Deliverables (конкретные артефакты)
- Architectural decisions (ADR'ы которые надо написать)
- Risks (что может пойти не так)
- Success criteria (как поймём что фаза completed)

---

## PHASE 1: STABILIZATION & SECURITY FOUNDATION
**Период:** Май-Июнь 2026 (8 недель)

### Goal
Подать NLnet, исправить **F13/F14/F15** критический security блокер, развернуть attachment server для решения Tecno bug. Завершить 10 quick wins для polish.

### Deliverables

**Week 1-2 (1-14 Мая) — NLnet submission + quick wins parallel**

- NLnet заявка submitted (31 мая дедлайн buffer)
- 10 quick wins implemented:
  - FLAG_SECURE на всех Activity (1 день)
  - Read receipts toggle (3 дня)
  - Per-chat mute (3 дня)
  - Auto-lock timer choices (2 дня)
  - Voice format AMR → Opus (3 дня)
  - Real waveform visualization (5 дней)
  - Playback speed control (2 дня)
  - Reactions UI (5 дней)
  - Replies / quoted messages (5 дней)
  - Pinned messages (5 дней)
- versionName в build.gradle.kts → 0.1.0-alpha.2
- KNOWN_ISSUES.md обновлён с inventory findings
- Bug J получает временный disclaimer ("voice may be delayed on certain Android OEMs")

**Week 3-6 (15 Мая - 14 Июня) — F13/F14/F15 resolution**

- ADR-009 написан и зафиксирован: identity / signed prekey / one-time prekey separation
- Prekey-сервер endpoint на relay (Rust): publish, fetch, rotate
- Client-side prekey lifecycle: generation, rotation, exhaustion handling
- Migration path для существующих сессий (forced re-handshake on next contact)
- Crypto test vectors добавлены для prekey lifecycle
- Threat Model v1.1 обновлён с описанием новой архитектуры

**Week 7-8 (15-30 Июня) — Attachment server**

- ADR-013 written: MinIO-based attachment storage
- Self-hosted MinIO container в docker-compose
- Client upload/download с E2E encryption (AES-GCM, key per attachment)
- Voice messages мигрированы с inline base64 на attachment server
- Files / images инфраструктура заложена (но UI пока не делаем)
- **Bug J resolved** для voice messages

### Architectural Decisions (ADRs)

- **ADR-007:** Username uniqueness strategy (relay namespace, written in Phase 1, implemented Phase 2)
- **ADR-009:** Identity/prekey/ratchet separation (F13/F14/F15 fix)
- **ADR-013:** Attachment server architecture (MinIO + E2E)

### Risks

- **NLnet rejection** (5-15% acceptance rate). Mitigation: OTF parallel application
- **F13/F14/F15 fix breaks existing sessions.** Mitigation: tested migration path, force re-handshake gracefully
- **Tecno bug persists даже after attachment server.** Mitigation: testing на дополнительный Android device (Pixel 6a)

### Success Criteria

- ✅ NLnet submitted перед 31 мая
- ✅ Voice messages работают на Pixel/Samsung/Xiaomi (не только эмулятор)
- ✅ Crypto test count grows from 16 → 25+
- ✅ All 10 quick wins shipped в Alpha 2 (preview release Конец Июня)

### Parallel: Кадровые операционные задачи

- Купить **Pixel 6a/7a** (б/у, $150-250) для тестирования "обычного" Android
- Сделать репозиторий **публичным** перед NLnet submission (или хотя бы добавить NLnet review-ers как collaborators)
- Создать **Codeberg mirror** для NLnet (требование если хотим максимум points)
- **Issues** в GitHub созданы из drafts (Releases/GITHUB_ISSUES_DRAFT.md)

---

## PHASE 2: USERNAME SYSTEM + VERIFICATION
**Период:** Июль-Август 2026 (8 недель)

### Goal
Запустить **directory.phntm.pro** для unique @username. Verification authority pipeline для будущих верифицированных аккаунтов. Группы доводим до production-grade.

### Deliverables

**Week 9-12 (Июль) — Directory service**

- ADR-007 implemented: relay-namespace для @username uniqueness
- New Rust service `directory` (отдельный binary, отдельный subdomain `directory.phntm.pro`):
  - POST /reserve — first-come reservation с rate limiting
  - GET /lookup/:username — resolve to public key
  - PUT /update — change username (Pro feature later)
- Client integration: при onboarding @username проверяется через directory
- Race condition handling (двое регистрируют одновременно — deterministic winner)
- Reservation list (admin/support/phantom/etc.)
- Search endpoint (Pro-tier later) — найти @vasya без QR
- Local fallback если directory недоступен (Trust Tier UNVERIFIED для new contacts)

**Week 13-14 (Август 1-2 нед) — Verification authority**

- ADR-008 implemented: Willen LLC как verification authority
- Verification authority key pair generated (offline, hardware token recommended)
- Public key hardcoded в клиент
- Signed certificate format: `(public_key, display_name, verification_class, expiry, signature)`
- Verification distribution: через relay metadata + clientside cache
- Admin web (basic, Next.js): list pending verifications, approve/reject
- Verified ✓ badge в ChatList и ContactProfile (B5.5 в inventory был NONE)
- Documentation: how to apply for verification (для будущего public website)

**Week 15-16 (Август 3-4 нед) — Group hardening**

- ADR-010: Group encryption hardening
- Sender Keys signing keys properly distributed (фикс current `signingPrivHex=""` issue)
- Control messages (member-add, member-leave, name-change) wrapped в Double Ratchet
- Member-leave key rotation (proper, не "delete all keys")
- Admin model в DB (promote/demote/kick)
- Member list UI
- Group avatar + edit name (basic Group Management UI)
- Channel posts: enforce admin-only posting

### Architectural Decisions

- **ADR-007** (implemented): Username namespace
- **ADR-008** (implemented): Verification authority
- **ADR-010** (implemented): Group encryption hardening

### Risks

- **Directory service compromise** = whole namespace can be impersonated. Mitigation: rate limiting, manual review для high-value usernames, 2FA for admin operations
- **Verification authority key theft** = catastrophic. Mitigation: offline storage, multi-signature variant позже
- **Group hardening breaks existing groups.** Mitigation: protocol version negotiation, graceful degradation

### Success Criteria

- ✅ `@vasya` гарантированно один на phntm.pro
- ✅ Verified ✓ badge видим в ChatList
- ✅ Groups работают для 50+ участников production-stable
- ✅ Channels: только admin posts

---

## PHASE 3: PREMIUM INFRASTRUCTURE
**Период:** Сентябрь-Октябрь 2026 (8 недель)

### Goal
Запустить Premium tiers с Stripe billing. Recovery Phrase для Plus. Server-side feature gating. Account migration через encrypted backup.

### Deliverables

**Week 17-20 (Сентябрь) — Stripe + subscription state**

- Stripe account setup (US-based, привязан к Willen LLC)
- Products configured: Plus monthly/annual, Pro monthly/annual, Lifetime ($199 one-time, hidden до Kickstarter)
- Stripe Tax configured
- Stripe Billing Portal для self-service
- Webhook handlers для subscription events (created/updated/cancelled/past_due)
- New service `billing` на relay (or отдельный subdomain billing.phntm.pro):
  - Subscription state per public_key
  - Tier validation endpoint
- Client integration:
  - In-app browser flow для checkout (web first, IAP позже)
  - Subscription state synced на startup и periodically
  - Feature gating: client checks server-validated tier перед открытием Plus/Pro features
- ADR-011 implemented: Premium feature gating architecture

**Week 21-22 (Октябрь 1-2 нед) — Recovery Phrase + Account migration**

- BIP-39 mnemonic generation (24 слова для security)
- Derivation private key из seed (HKDF)
- UI flow в Settings → "Back up your keys" (только Plus users)
- Warning screen: "Recovery phrase is powerful. Anyone with it can impersonate you."
- One-time display, force write down with confirmation
- Verification flow (введи 3 случайных слова из 24)
- Restore flow при переустановке: "I have a recovery phrase"
- Encrypted backup blob (locally encrypted с seed phrase) → user uploads на свой cloud
- Восстановление: enter seed → fetch backup → decrypt → restore identity + conversations

**Week 23-24 (Октябрь 3-4 нед) — Premium features**

- Custom themes engine (Monochrome, Sepia, Midnight, Paper White)
- Berkeley Mono integration ($75 license purchase)
- Animated avatars (Live Photos support)
- Custom notification sounds
- App icon variants (5 штук)
- Extended disappearing messages (1s-1year, vs Free 24h)
- Pinned chats до 10 (vs Free 5)
- Premium ◆ badge в profile + ChatList
- Settings section: "Phantom Premium" management

### Architectural Decisions

- **ADR-011** (implemented): Premium feature gating
- **ADR-012:** Account migration via seed phrase

### Risks

- **Stripe rejects high-risk vertical** (privacy/encryption иногда flagged). Mitigation: правильный business description, готовность к manual review
- **Seed phrase UX failures** = users locked out. Mitigation: extensive testing, multiple recovery touchpoints, clear documentation
- **Premium feature gating bypassed** by patched APK. Mitigation: server-side validation для critical features, accept что custom themes можно patch

### Success Criteria

- ✅ Можно купить Plus и Pro через app
- ✅ Recovery Phrase работает: backup → restore на новом устройстве
- ✅ Premium ◆ badge виден в ChatList для Plus users
- ✅ Berkeley Mono активен для Plus в premium screens

### Что НЕ делаем в этой фазе

- Multiple personas (Pro feature) — сложная архитектура, отложить
- Self-hosted relay (Pro feature) — бизнес-фича для позже
- Business tier (B2B) — после получения первых customers

---

## PHASE 4: iOS PARITY
**Период:** Ноябрь-Декабрь 2026 (8 недель)

### Goal
iOS клиент с feature parity с Android Alpha 1+. Этот фаза — **NLnet Milestone 1 deliverable** (если получили грант, € 9,000 покрывает эту работу).

### Prerequisites (до старта iOS)

- Apple Developer enrollment ($99/year)
- macOS machine (свой Mac или cloud-based macOS builder)
- Купить minimum один физический iPhone для тестирования (если у тебя есть iPhone — использовать его, plus consider second device for multi-device testing)
- SQLCipher iOS license ($500/year — commercial)
- Xcode latest установлен

### Deliverables

**Week 25-28 (Ноябрь) — KMP XCFramework activation**

- ADR-014: iOS architecture decision (SwiftUI native + KMP shared core)
- iosTargets активированы во всех 16 KMP-модулях
- libsodium iOS pod подключён
- SQLCipher iOS pod подключён (commercial license)
- Build XCFramework workflow на macOS-builder
- Минимальный iOS app: launches → libsodium init → identity creation работает
- Network layer: Ktor client работает на iOS

**Week 29-30 (Декабрь 1-2 нед) — UI parity**

- 6 SwiftUI views существующего stub переписаны под KMP shared logic
- Onboarding flow на iOS
- ChatList screen на iOS
- ChatScreen с message bubbles
- Settings screen
- Profile screen
- QR scan via AVFoundation (iOS native camera)

**Week 31-32 (Декабрь 3-4 нед) — Voice + groups + integration**

- Voice messages recording через AVAudioRecorder
- Voice playback через AVAudioPlayer
- WebRTC iOS pod подключён, voice calls 1-on-1 работают
- Group chat UI на iOS
- TestFlight beta: подписать build, distribute 5 external testers
- Cross-platform testing: Android-iOS messaging, Android-iOS group, Android-iOS calls

### Architectural Decisions

- **ADR-014:** iOS architecture (SwiftUI + KMP shared)

### Risks

- **macOS-builder проблемы** — Compose Multiplatform для iOS пока не super-stable. Mitigation: SwiftUI native, не Compose iOS
- **Apple App Store rejection** для privacy-focused messenger — есть прецеденты. Mitigation: позаботиться о proper App Privacy disclosure, готовиться к ручной review
- **TestFlight cycle slow** (1-2 days review per build). Mitigation: parallel work, сборки batched

### Success Criteria

- ✅ iOS app в TestFlight с минимум 5 active testers
- ✅ iOS-Android cross-platform messaging работает (текст, voice, group)
- ✅ Identity creation, contact exchange, basic features все работают

---

## PHASE 5: CENSORSHIP RESISTANCE + AUDIT PREP
**Период:** Январь-Февраль 2027 (8 недель)

### Goal
Pluggable transports + Tor onion service для censorship resistance. Push notifications. Подготовка к security audit (NLnet Milestone 3-4).

### Deliverables

**Week 33-36 (Январь) — Pluggable transports + Tor**

- ADR-015: Pluggable transports architecture
- obfs4 integration (через obfs4proxy bundled or dynamically loaded)
- Snowflake integration (WebRTC-based circumvention)
- Domain fronting fallback
- Tor v3 onion service для relay (`xxxxx.onion` address)
- Documentation: how to self-host PHANTOM relay с Tor
- Reproducible build pipeline (для verification против tampered binaries)
- Censorship-resistance threat model document

**Week 37-38 (Февраль 1-2 нед) — Push notifications**

- ADR-016: Push notifications hybrid strategy
- UnifiedPush integration (Android, FOSS-aligned default)
- FCM integration (Android, opt-in для users which need reliability)
- APNs integration (iOS only path)
- Server-side: relay sends push trigger, не plaintext
- Token registration endpoint на relay
- Testing на разных Android (Pixel, Tecno, Samsung) — это критично для понимания OEM coverage

**Week 39-40 (Февраль 3-4 нед) — Audit preparation**

- Application к Radically Open Security (NGI Zero free Review Service)
- Сборка audit packet:
  - Architecture overview document
  - Threat model v2 (полный, обновленный)
  - Code overview (где какая логика живёт)
  - Crypto primitives summary
  - Known issues honest disclosure
  - Test coverage description
- Если ROS unavailable — backup options (Trail of Bits, Cure53)
- Audit kickoff meeting

### Architectural Decisions

- **ADR-015:** Pluggable transports
- **ADR-016:** Push notifications hybrid

### Risks

- **Tor relay attracts attention** в некоторых юрисдикциях. Mitigation: Hetzner ToS allows Tor exit nodes, our relay не exit (только onion service)
- **Push delivery unreliable** даже с UnifiedPush. Mitigation: foreground service остаётся primary, push — supplementary
- **Audit findings serious** (high or critical). Mitigation: бюджет (€6,000 NLnet M4) and time для remediation

### Success Criteria

- ✅ PHANTOM работает в censored сетях (тест в одной такой среде минимум)
- ✅ Push notifications работают на Android (UnifiedPush primary, FCM fallback) и iOS (APNs)
- ✅ Audit вошёл в active phase

---

## PHASE 6: AUDIT RESPONSE + BETA 1 RELEASE
**Период:** Март-Апрель 2027 (8 недель)

### Goal
Закрыть audit findings. Threat Model v2 published. Beta 1 release. NLnet Milestone 4 delivered. Финальная подготовка для возможного Kickstarter.

### Deliverables

**Week 41-44 (Март) — Audit response**

- Audit findings reviewed
- Critical findings: immediate remediation (perhaps within Phase 6, perhaps emergency hotfix release earlier)
- High findings: remediation в Beta 1 scope
- Medium findings: documented in CHANGELOG, planned для Beta 2
- Low findings: triaged, decided per-case
- Threat Model v2 published — comprehensive document covering:
  - All adversaries (passive, active, state-level, OEM, app-store)
  - Threat surface diagram
  - Comparison с Signal, Briar, Cwtch, SimpleX, Session
  - Audit findings status
  - Remaining accepted risks с rationale
- Audit response document published (transparency)

**Week 45-46 (Апрель 1-2 нед) — Beta 1 polish**

- All Phase 1-5 features integrated и regression-tested
- Cross-platform testing matrix completed
- Documentation pass: README, CONTRIBUTING, SECURITY все обновлены
- Translations: добавить German, French, Spanish, Polish (через Translate House — NGI Zero free service)
- RTL support: добавить Arabic, Persian
- Accessibility audit (HAN University — NGI Zero free)
- Performance benchmarks
- Bug bash (final testing pass)

**Week 47-48 (Апрель 3-4 нед) — Beta 1 release + NLnet final report**

- v0.2.0-beta.1 tagged
- Signed APK + iOS IPA published
- Reproducible builds verified by independent third party (можем привлечь NLnet community)
- Press release / announcement (privacy-focused outlets, FOSS news, EU-tech press)
- NLnet Milestone 4 final report submitted
- Public roadmap for next 12 months published
- Decision point: Kickstarter launch or продолжение private alpha

### Architectural Decisions

- Все ADRs предыдущих фаз finalized
- Future ADRs identified: Multi-device sync (Phase 7), Mesh routing (Phase 7+)

### Risks

- **Audit findings catastrophic** (e.g., crypto vulnerability). Mitigation: Phase 6 timeline can extend, Beta 1 delayed if needed for safety
- **Translations низкого качества.** Mitigation: native speaker review (community, paid translators if needed)
- **Beta 1 attracts attacks** (white-hat or otherwise). Mitigation: security disclosure process documented, response team готов

### Success Criteria

- ✅ Beta 1 (v0.2.0-beta.1) shipped
- ✅ Audit findings remediated or transparently documented
- ✅ Threat Model v2 published
- ✅ NLnet final report submitted
- ✅ 5+ languages supported
- ✅ Test devices include Pixel, Samsung, Tecno, Xiaomi (minimum 4 OEMs)

---

## QUICK WINS REFERENCE

10 quick wins, prioritized by ROI (Phase 1 execution):

| # | Feature | Effort | Impact | Status |
|---|---------|--------|--------|--------|
| 1 | FLAG_SECURE на Activity | 1 день | High (privacy) | Phase 1 W1 |
| 2 | Voice format AMR → Opus | 3 дня | High (UX) | Phase 1 W1 |
| 3 | Read receipts toggle | 3 дня | Med (privacy) | Phase 1 W1 |
| 4 | Per-chat mute | 3 дня | Med (UX) | Phase 1 W1-2 |
| 5 | Auto-lock timer choices | 2 дня | Med (privacy) | Phase 1 W2 |
| 6 | Real waveform visualization | 5 дней | High (UX wow) | Phase 1 W2 |
| 7 | Playback speed control | 2 дня | Med (UX) | Phase 1 W2 |
| 8 | Reactions UI | 5 дней | High (engagement) | Phase 1 W2 |
| 9 | Replies / quoted | 5 дней | High (engagement) | Phase 1 W2 |
| 10 | Pinned messages within chat | 5 дней | Med (productivity) | Phase 1 W2 |

**Total quick wins effort:** ~34 дня (~7 недель at 5 days/week). Realistic: distribute across весь Phase 1 в parallel с major work.

---

## ARCHITECTURAL DECISIONS REGISTRY

**To be written в Phase 1 первую неделю (затем implemented per phase):**

| ADR # | Title | Phase | Status |
|-------|-------|-------|--------|
| ADR-007 | Username uniqueness via relay namespace | P1 (write) → P2 (impl) | TO WRITE |
| ADR-008 | Verification authority (Willen LLC central) | P1 (write) → P2 (impl) | TO WRITE |
| ADR-009 | Identity / signed prekey / one-time prekey separation (F13/F14/F15) | P1 | TO WRITE |
| ADR-010 | Group encryption hardening (Sender Keys signing distribution) | P2 | TO WRITE |
| ADR-011 | Premium feature gating architecture | P3 | TO WRITE |
| ADR-012 | Account migration via seed phrase | P3 | TO WRITE |
| ADR-013 | Attachment server architecture (MinIO) | P1 | TO WRITE |
| ADR-014 | iOS architecture (SwiftUI + KMP shared) | P4 | TO WRITE |
| ADR-015 | Pluggable transports (obfs4, Snowflake, fronting) | P5 | TO WRITE |
| ADR-016 | Push notifications hybrid (UnifiedPush + FCM + APNs) | P5 | TO WRITE |

Существующие ADRs (1-6) уже в `docs/adr/`. Каждый новый ADR — 1-2 page document по template.

---

## KICKSTARTER READINESS GATES

Kickstarter publication возможна когда **все** следующие пункты completed:

### Hard requirements

- ✅ F13/F14/F15 resolved (Phase 1)
- ✅ Username uniqueness работает (Phase 2)
- ✅ Premium tiers ready (Phase 3)
- ✅ iOS released (Phase 4)
- ✅ Audit completed and findings remediated (Phase 6)
- ✅ Beta 1 shipped (Phase 6)

### Soft requirements

- Marketing video готов (3-5 минут pitch)
- 5+ languages localized
- Community готов (GitHub Issues, Matrix room для users, etc.)
- Press contacts identified
- Lifetime tier price-fixed at $199 (decided)
- Stretch goals defined (что мы делаем if 200% / 500% funded)

### Earliest possible date

End of **Phase 6 (Апрель 2027)**. Если все gates passed без задержек — **Май 2027** Kickstarter possible.

Realistic decision point: **Март 2027** (после audit findings clear), готовиться 2 месяца, launch **Май-Июнь 2027**.

### What Kickstarter funds

- Marketing & user acquisition (post-launch)
- Hiring (designer, additional dev, community manager)
- Business tier development
- Infrastructure scaling
- Translation / accessibility expansion

### Kickstarter NOT for

- Core development (это NLnet и собственные средства)
- "Saving the project" — если project depends on Kickstarter, не запускать

---

## NLNET MILESTONES — MAPPING К ROADMAP

| NLnet Milestone | Roadmap Phases | Months | Budget | Deliverables |
|-----------------|----------------|--------|--------|--------------|
| M1: iOS feature parity | Phase 4 | Nov-Dec 2026 | €9,000 | iOS app в TestFlight, KMP XCFramework, libsodium iOS, cross-platform interop tests |
| M2: Group messaging protocol + spec | Phase 2 | Jul-Aug 2026 | €8,000 | PROTOCOL_GROUPS.md, reference implementation in shared core, supports up to 50, formal security analysis |
| M3: Censorship resistance | Phase 5 | Jan-Feb 2027 | €7,000 | obfs4/Snowflake integration, Tor onion relay, reproducible builds, censorship threat model, empirical test results |
| M4: Audit + Threat Model v2 + Beta 1 | Phase 5-6 | Feb-Apr 2027 | €6,000 | ROS audit completed, Threat Model v2 published, Beta 1 release, full audit response |

**Total NLnet:** €30,000 / 12 months / 4 milestones

**Если NLnet rejected:** roadmap не меняется, но темп замедляется (нужно искать alternative funding для milestones cost). OTF parallel application provides backup.

---

## OPERATIONAL DEPENDENCIES

### Hardware to acquire

| Item | Priority | Cost | When |
|------|----------|------|------|
| Pixel 6a/7a (б/у) для тестирования | High | $150-250 | Phase 1 (May 2026) |
| iPhone (если ещё нет) для iOS dev | High | $300-700 | Phase 4 prep (Oct 2026) |
| Hardware token для verification authority key | Med | $50-100 | Phase 2 |
| Optional: Mac mini для iOS builder | Low | $600+ | Phase 4 (можно cloud-based) |

### Software / services

| Item | Cost | When |
|------|------|------|
| Apple Developer Program | $99/year | Phase 4 prep |
| SQLCipher iOS commercial license | $500/year | Phase 4 |
| Berkeley Mono license | $75 one-time | Phase 3 |
| Stripe — нет setup fee, % с платежей | 2.9% + $0.30 per tx | Phase 3 |
| Hetzner additional servers (directory, billing, MinIO) | ~€20-40/month | Phase 1-3 progressive |
| Domain renewals (phntm.pro + variations) | ~$15/year | ongoing |

### Free services to use (NLnet ecosystem)

- **Radically Open Security** — security audit (Phase 5-6)
- **ifrOSS** — license review
- **HAN University** — accessibility audit (Phase 6)
- **Translate House** — localization (Phase 6)

### Personnel considerations

- **Solo for full 12 months** — primary mode, 35-40 ч/нед
- **Possible contract designer** (Q3 2026) — для finalizing visual design after Claude Design output
- **Possible part-time iOS dev** (Phase 4) — если соло-iOS оказывается медленным, нанять контрактника на 2-3 месяца  через Toptal или Upwork

---

## RISK REGISTER

### Critical risks (могут провалить весь project)

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| F13/F14/F15 fix breaks production | Med | High | Extensive testing, gradual rollout, migration script tested на test data |
| Security vulnerability discovered post-launch | Med | High | Security disclosure process, hotfix capability, audit catches most |
| Apple App Store rejection | Med | Med | App Privacy disclosure правильный, готовность к manual review, plan B (TestFlight only initially) |
| Stripe закрывает account из-за privacy vertical | Low-Med | High | Multiple payment processors backup (Paddle, FastSpring), accept higher fees |
| Burnout solo развития на 12 месяцев | High | High | Sustainable pace, regular breaks, NLnet milestone-based payments help structure |

### Significant risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| NLnet rejection | High (~85% reject rate) | Med | OTF parallel, self-funding ability |
| iOS development takes longer than planned | High | Med | Plan B: contract iOS dev for 2-3 months |
| Tecno / OEM bug not fully fixed | Med | Low | Document as known issue, push notification helps |
| Translation quality issues | Med | Low | Native speaker review process |
| Audit findings extensive | Med | Med | Buffer time в Phase 6, prioritized remediation |

### Operational risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Hetzner ban (account closure) | Low | High | Backup VPS provider configured (Vultr / OVH), data-portable architecture |
| GitHub takedown / private | Low | High | Codeberg mirror primary, GitHub mirror secondary |
| Cloudflare ban | Low | Med | Direct DNS+TLS works (Caddy handles) |
| Personal circumstances (illness, family) | Med | Med | Reasonable buffers in plan, NLnet milestones flexible на месяц-два |

---

## SUCCESS METRICS

### По фазам

| Phase | Primary metric | Target |
|-------|----------------|--------|
| P1 | NLnet submitted + F13/F14/F15 fixed | Yes by 30 Jun |
| P2 | Unique @username works, Verified ✓ live | Yes by 31 Aug |
| P3 | Plus subscription via app | Yes by 31 Oct |
| P4 | iOS in TestFlight | Yes by 31 Dec |
| P5 | Tor onion relay operational | Yes by 28 Feb |
| P6 | Beta 1 released | Yes by 30 Apr |

### Continuous metrics (отслеживать throughout)

- Crypto test count (16 → goal 50+ by Beta 1)
- Documentation coverage (% modules с README)
- Bug fix turnaround (median days from report to fix)
- Code review (# of external PRs reviewed and merged)
- Security disclosure responsiveness

### Vanity metrics (NICE TO HAVE, не drive decisions)

- GitHub stars
- Active users (как только можно мерить privacy-respectfully)
- Press coverage
- Conference talks accepted

---

## DECISION POINTS (когда переоценивать)

### Quarterly review

- **End of Phase 2 (31 Aug 2026):** progress check — действительно идём в темпе full-time?
- **End of Phase 4 (31 Dec 2026):** iOS reality check — продолжаем соло или нанять?
- **End of Phase 5 (28 Feb 2027):** Kickstarter readiness assessment

### Triggered re-evaluation

- NLnet decision (положительный или отрицательный) — ~Авг 2026 для June submission
- OTF decision (если apply) — rolling, можем получить anywhere Q3-Q4 2026
- Audit findings — если catastrophic, replan Phase 6
- External offer (acquisition, investment, partnership) — discuss case-by-case

---

## APPENDIX A — UNRESOLVED PRODUCT QUESTIONS

Эти вопросы остаются открытыми, должны быть решены к моменту когда становятся blocking:

1. **Multiple personas** (Pro feature) — keep, drop, or simplify? **Decision by:** Phase 3 start
2. **Anti-forensics RAM-only mode** (Pro) — keep или replace на "ephemeral conversations"? **Decision by:** Phase 3 mid
3. **Self-hosted relay** (Pro) — release с Beta 1 или delay? **Decision by:** Phase 5
4. **Bots / automation API** (Pro) — нужно ли вообще? Чем отличается от Telegram bots? **Decision by:** Q1 2027
5. **Mesh routing / Nearby** — отдельный major project Q3 2027+, или never? **Decision by:** Phase 6
6. **Federation** — мы решили НЕТ, но запросы будут. **Final position:** документировать в FAQ что и почему

---

## APPENDIX B — POST-BETA 1 FUTURE (out of this roadmap)

После Beta 1 (Май 2027 onwards), вопросы для следующего планирования:

- **Multi-device sync** (Phone + Laptop одновременно) — XL feature, требует отдельного design
- **Mesh routing** (Bluetooth + Wi-Fi Direct + mesh hop) — отдельный мини-проект, ~6 месяцев
- **Polls / quizzes** в группах (anonymous voting cryptographically)
- **Live location sharing** (opt-in, time-limited)
- **Anonymous channels** (Pro) — author cryptographically hidden
- **Voice transcription** on-device через Whisper.cpp (Plus feature)
- **Video calls** + group calls
- **API & SDK** для third-party clients
- **Self-hosted relay в коробке** (Docker compose template, web admin)
- **Business tier** B2B development

These remain in **vision document** but not in active roadmap until Beta 1 done.

---

## CONCLUSION

Этот roadmap — амбициозный но реалистичный для full-time solo dev с накопленным опытом и working Alpha 1 base. Главные принципы:

1. **Сначала фундамент** (security, infrastructure), потом фичи
2. **Архитектурные решения** — write ADR before code
3. **NLnet и Premium — параллельные треки**, не конфликт
4. **Quality over coverage** — лучше 8 фич working perfectly than 20 buggy
5. **Honesty about state** — KNOWN_ISSUES.md, threat model, audit findings — всё публично
6. **Sustainable pace** — solo на 12 месяцев означает burnout management critical

К Апрелю 2027 PHANTOM становится **production-ready privacy messenger** с Premium revenue model, audited security, multi-platform support, и foundation for scaling.

Если NLnet получим — финансирует core. Если не получим — медленнее но не блокирует. Premium revenue + Kickstarter — sustainability post-grant.

Roadmap живой документ. Re-evaluate quarterly, update as реальность меняется.
