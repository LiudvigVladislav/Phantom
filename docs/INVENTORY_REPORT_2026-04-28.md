# PHANTOM — Codebase Inventory Report

**Дата:** 2026-04-28
**HEAD:** `master` post Alpha 1 (`v0.1.0-alpha.1`)
**Метод:** код-разведка через 4 параллельных general-purpose агента + ручная верификация ключевых файлов.
**Принцип:** ничего не приукрашиваем. Если 30% — пишем PARTIAL и точно описываем что реализовано. Если совсем нет — пишем NONE без оправданий.

---

## Executive summary

PHANTOM — Alpha 1, **Android-only** мессенджер с функциональным 1:1 текстом, голосом (с oqr OEM-ограничениями), production-relay в Hetzner. Кодовая база: **408 tracked files**, **~22 600 LOC** (Kotlin shared + Android + Rust relay), 60 тестов (44 commonTest + 16 androidInstrumentedTest), 8 SQLDelight schema files, **10 TODO/FIXME** комментариев. iOS — pure SwiftUI stub без KMP-bridge.

**Что работает в production-grade качестве:**
- Double Ratchet, Sealed Sender, store-and-forward с durability через ack-deliver
- Concurrency и race-condition handling в transport (per-conv mutex, processingLock, conn_id, ACK watchdog)
- App-lock через BiometricPrompt + DEVICE_CREDENTIAL
- Per-chat disappearing messages с E2E sync
- Trust Tier flow (REQUEST → TRUSTED) для unknown contacts
- QR exchange (генерация + сканирование через CameraX + ML Kit)
- Foreground service с WifiLock + WakeLock

**Архитектурные блокеры до production-scale:**
1. **F13/F14/F15 — identity-as-ratchet-key.** Identity X25519 ключ переиспользуется как ratchet DH key. Любой компромисс ratchet state даёт permanent impersonation. **Должно быть исправлено перед Kickstarter.**
2. **Username system отсутствует на relay.** Username — self-declared label, никем не подтверждается, дубликаты разрешены. Нет directory-сервиса для discover-by-username.
3. **iOS — pure stub.** Xcode project + 6 SwiftUI views существуют, но KMP не подключён, identity = UserDefaults UUID, ChatList всегда пустой.
4. **Relay полностью in-memory.** Restart container = потеря всей undelivered очереди. Нет persistence слоя.
5. **Tecno-class OEM radio parking.** Voice >50KB не доставляется (асимметричный outbound packet loss). Решается через Unified Push + attachment server в Alpha 2.

**Что NONE (нет ни строчки кода):**
- Premium / Stripe / subscription tiers / feature gating
- Push notifications (FCM scaffolded, но не wired; UnifiedPush — NONE)
- Privacy modes (Standard/Private/Ghost) — только в legal-доках
- Screenshot защита (FLAG_SECURE — нигде)
- Backup / recovery phrase
- Add by @username flow
- File / image / document send-receive
- Last seen / online status
- Bluetooth / WiFi Direct / mesh
- i18n strings resources (всё хардкоженный English)
- Reproducible builds, CI/CD

---

## A. CORE MESSAGING

### A1. Криптография

#### A1.1 X3DH key agreement
- **Статус:** PARTIAL (~30%)
- **Файлы:** `shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/LibsodiumX3DH.kt:31-83`, `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/SessionManager.kt:31-46`
- **Что работает:** Класс `LibsodiumX3DH` реализует initiator/recipient handshake с тремя DH (DH1/DH2/DH3) и SHA-256 KDF. 4 теста подтверждают согласование общего секрета.
- **Что НЕ работает / TODO:** SessionManager НЕ использует X3DH в production-пути. Bootstrap сессии — статический ECDH: `DH(my_identity_priv, their_identity_pub)` по hex-ключу из QR. Нет prekey-сервера, нет signed prekey, нет one-time prekey, нет AD-binding. KDF — SHA-256, не HKDF. По существу X3DH-класс — **мёртвый код для Alpha 1**.
- **Сложность доработки:** L (нужен prekey endpoint на relay + ротация + миграция существующих сессий)
- **Зависимости:** F13/F14/F15 (architectural)

#### A1.2 Double Ratchet (forward secrecy + post-compromise)
- **Статус:** DONE
- **Файлы:** `LibsodiumDoubleRatchet.kt:23-154`, `RatchetState.kt`, `LibsodiumDoubleRatchetTest.kt` (5 @Test)
- **Что работает:** encrypt/decrypt с symmetric chain (SHA-256 chain-KDF, byte 0x01/0x02), XSalsa20-Poly1305 (SecretBox.easy), DH-ratchet step при смене remote ratchet key (X25519). zeroize message keys и DH-output. Per-conversation Mutex в `DefaultMessagingService:73-103` сериализует encrypt/decrypt — критический фикс QA-2026-04-25.
- **Что НЕ работает / TODO:** Нет skipped-message-keys буфера → out-of-order кадры в одном чейне декрипт не восстанавливают (MAC fail). Нет header-encryption. KDF chain — голый SHA-256, не HKDF.
- **Сложность доработки:** M (skipped keys buffer + HKDF)

#### A1.3 Sealed Sender
- **Статус:** DONE
- **Файлы:** `SealedSender.kt:24-94`, `DefaultMessagingService.kt:166-184` (send) / `:251-264` (receive), `services/relay/src/routes.rs:298-313`
- **Что работает:** Wire-format `eph_pub(32)||nonce(24)||XSalsa20-Poly1305(senderHex)`. KDF SHA-256(ECDH || "phantom_sealed_sender_v1"). Relay подменяет `from` на пустую строку — действительно не видит отправителя. Все исходящие отправляются sealed. 3 теста.
- **Что НЕ работает / TODO:** Domain-separation простая, не HKDF. Нет sender-certificate (произвольный отправитель может seal любое имя — спам не аутентифицирован независимо от ratchet). Нет защиты replay вне ratchet.
- **Сложность доработки:** M (sender certs)

#### A1.4 libsodium integration (JNI или KMP common?)
- **Статус:** DONE
- **Файлы:** `gradle/libs.versions.toml:20-23` — `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings:0.9.2`
- **Что работает:** KMP common через ionspin (под капотом JNI на Android, native на iOS, JS-wasm на JVM-tests).
- **Что НЕ работает / TODO:** Bug H в tech_debt — JNI не грузится в plain-JVM unit-тестах, поэтому крипто-тесты живут в androidInstrumentedTest.
- **Сложность доработки:** S

#### A1.5 Key materials (identity / signed prekey / one-time prekeys)
- **Статус:** PARTIAL — только identity
- **Файлы:** `IdentityManager.kt:13-33`, `LibsodiumIdentityCrypto.kt`, `Identity.sq`
- **Что работает:** Identity keypair (X25519) генерируется при первом запуске, хранится в SQLDelight через `SqlDelightIdentityRepository`. `dhPrivateKeyHex` + `publicKeyHex` в БД, БД зашифрована SQLCipher через `DatabasePassphraseManager` (passphrase в Android Keystore, AES-GCM 256-bit).
- **Что НЕ работает / TODO:** Нет SignedPreKey, нет OneTimePreKeys, нет prekey-bundle endpoint, нет ротации identity. **F13/F14/F15 — identity-as-ratchet-key архитектурный блокер.** Поиск prekey/preKey/PreKey даёт matches только в комментариях/тестах/X3DH-классе.
- **Сложность доработки:** L (полный prekey lifecycle + relay endpoint + миграция)
- **Зависимости:** F13/F14/F15

#### A1.6 Crypto test count
- **Статус:** DONE
- **Файлы:** `shared/core/crypto/src/androidInstrumentedTest/kotlin/phantom/core/crypto/`
- **Что работает:** 16 instrumented-тестов: LibsodiumX3DHTest (4), LibsodiumDoubleRatchetTest (5), SealedSenderTest (3), SafetyNumberTest (4). Требуют Android emulator. Проходят на Pixel 8 Pro / API 35.
- **Что НЕ работает / TODO:** Нет fuzz-тестов, нет тестов на skipped messages, нет тестов на out-of-order delivery, нет negative-tests на manipulated ciphertext.
- **Сложность доработки:** S

### A2. Транспорт и доставка

#### A2.1 WebSocket connection lifecycle
- **Статус:** DONE
- **Файлы:** `KtorRelayTransport.kt:111-209` (connect/runReconnectLoop), `:515-524` (disconnect), `PhantomMessagingService.kt`
- **Что работает:** Ktor + OkHttp engine. Экспоненциальный backoff (base 1s, cap 30s, infinite). Per-generation `CoroutineScope` с `cancelAndJoin` — фикс race-condition. Force-cancel через `forceCancelAllEngineCalls` обходит OkHttp 60s `CANCEL_AFTER_CLOSE_MILLIS`.
- **Что НЕ работает / TODO:** —
- **Сложность доработки:** S

#### A2.2 Heartbeat / keepalive
- **Статус:** DONE
- **Файлы:** `KtorRelayTransport.kt:211-243` (startPing) + `RelayTransportConfig.kt:11-19`, `services/relay/src/routes.rs:392-396`
- **Что работает:** Application-level ping каждые 10 сек (`PING_INTERVAL_MS`), pong-timeout 60 сек (`PONG_TIMEOUT_MS` — bumped в этой сессии). При timeout — `forceCancelAllEngineCalls` + reconnect. На стороне relay — single-task `select!` loop, explicit Pong, чтобы избежать tungstenite auto-PONG queue-starvation.
- **Что НЕ работает / TODO:** —
- **Сложность доработки:** S

#### A2.3 Server-side store-and-forward
- **Статус:** DONE
- **Файлы:** `services/relay/src/routes.rs:88-213`, `:325-346`, `state.rs`
- **Что работает:** In-memory store, envelope удаляется только после client-emit `ack-deliver`. Идемпотентная дедуп через `INSERT OR IGNORE` на клиенте. Flush на reconnect. Per-recipient quota (500), sliding-window rate-limiter (60 msg/60s), blocklist. На диск пишутся только abuse-reports и blocklist.
- **Что НЕ работает / TODO:** Envelope storage не persisted на диск — relay restart теряет всё незаакнутое. FCM silent push — TODO. Один процесс, нет horizontal scaling.
- **Сложность доработки:** M (durable storage + FCM)

#### A2.4 Client-side message queue
- **Статус:** DONE
- **Файлы:** `KtorRelayTransport.kt:87-89` (pendingOutbox), `:367-403`, `:464-499`, `:104-109` + `:245-279` (ACK watchdog)
- **Что работает:** Двухуровневый queue — in-memory pendingOutbox FIFO + persisted MessageRepository.status. ACK-watchdog (60s timeout): неподтверждённые re-add в начало outbox + force-reconnect. На реконнект `requeueUnackedToOutboxFront`.
- **Что НЕ работает / TODO:** pendingOutbox in-memory; process kill теряет очередь, но MessageRepository(status=QUEUED) — source of truth (нет автоматического re-submit из БД на process restart).
- **Сложность доработки:** S

#### A2.5 Message deduplication (replay defense)
- **Статус:** DONE
- **Файлы:** `Message.sq:21-29` (INSERT OR IGNORE), `DefaultMessagingService.kt:62-65,233-243,276-284`
- **Что работает:** Двухуровневая дедупликация — in-flight Mutex set + DB unique key. Relay-replay через TTL=7d не приводит к двойному decrypt.
- **Что НЕ работает / TODO:** На уровне ratchet protocol replay-protection отсутствует (skipped-keys buffer не реализован). Защита держится на messageId-уникальности на клиенте.
- **Сложность доработки:** M

#### A2.6 Conn_id race conditions / fast reconnect
- **Статус:** DONE
- **Файлы:** `services/relay/src/routes.rs:113,192-201`, `KtorRelayTransport.kt:150-205,217,247`
- **Что работает:** atomic conn_counter, на отключении HashMap-entry удаляется ТОЛЬКО если conn_id совпадает — fast-reconnect не теряет live-delivery. На клиенте `cancelAndJoin` обеспечивает что pingJob/ackWatchdog предыдущей генерации завершён до создания новой.
- **Сложность доработки:** S

### A3. Сообщения — базовая функциональность

#### A3.1 Send text
- **Статус:** DONE
- **Файлы:** `DefaultMessagingService.kt:132-206`, `ChatScreen.kt`
- **Что работает:** Encrypt → MessagePadding.pad (ISO 7816-4) → Sealed Sender wrap → relay.send. Запись в БД со статусом QUEUED/SENT.
- **Сложность:** S

#### A3.2 Receive text
- **Статус:** DONE
- **Файлы:** `DefaultMessagingService.kt:208-230,232-525`
- **Что работает:** Полный pipeline: unseal → unpad → ratchet.decrypt → parse MessagePayload → DB insert → emit IncomingMessage flow → notification → ack-deliver. Idempotent через alreadyProcessed-check.
- **Что НЕ работает / TODO:** При decrypt-fail (MAC error) сообщение не ack'ится — relay будет re-deliver навсегда до TTL=7d.
- **Сложность:** M (recovery-path для broken sessions)

#### A3.3 Delivery receipts (sent/delivered/read)
- **Статус:** DONE
- **Файлы:** `MessageStatus.kt`, `routes.rs:381-390`, `DefaultMessagingService.kt:215-223,225-229`
- **Что работает:** 5-state pipeline (QUEUED/SENT/RELAYED/DELIVERED/READ/FAILED). UI отображает статус-индикаторы.
- **Что НЕ работает / TODO:** "delivered" в relay означает «передано в mpsc-channel», не «клиент действительно обработал». Реальное delivered должно мапиться на `ack-deliver`. Это и есть Alpha 2 fix для индикаторов.
- **Сложность:** S

#### A3.4 Typing indicator
- **Статус:** DONE
- **Файлы:** `KtorRelayTransport.kt:67-71,447-455,309-319`, `routes.rs:427-463`, `ChatScreen.kt:175-178,245-249,554-560,2081-2099`
- **Что работает:** Ephemeral over WS, не E2EE, не сохраняется. Дебаунсинг на клиенте. Rate-limiter применяется.
- **Что НЕ работает / TODO:** Plaintext "from" leaks identity to relay. Stale-typing TTL на UI отсутствует.
- **Сложность:** S

#### A3.5 Last seen / online status
- **Статус:** NONE
- **Что работает:** —
- **Что НЕ работает:** Полностью отсутствует (по дизайну Alpha-1).
- **Сложность:** M

#### A3.6 Read receipts toggle
- **Статус:** PARTIAL — отправляются всегда
- **Файлы:** `DefaultMessagingService.kt:527-541`
- **Что работает:** Read-receipts передаются и обновляют MessageStatus.READ.
- **Что НЕ работает / TODO:** НЕТ toggle — ни global, ни per-chat. Settings не имеет такой опции.
- **Сложность:** S

#### A3.7 Local persistence (DB schema)
- **Статус:** DONE
- **Файлы:** `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/*.sq` (8 файлов: Message, Conversation, Identity, RatchetState, Group, GroupMember, SenderKeyStore, Reaction)
- **Что работает:** SQLDelight + SQLCipher AES-256, passphrase в Android Keystore. Migrations 4.sqm и далее.
- **Что НЕ работает / TODO:** `plaintext_cache` — это plaintext в зашифрованной БД (SQLCipher). При компрометации passphrase из Keystore — открыто. Для ghost-mode нужен plaintext-only-in-RAM режим.
- **Сложность:** M

---

## B. IDENTITY & ONBOARDING

### B1. Identity creation

#### B1.1 Identity keypair generation
- **Статус:** PARTIAL (X25519 only)
- **Файлы:** `LibsodiumIdentityCrypto.kt:15`, `IdentityManager.kt:23`
- **Что работает:** `Box.keypair()` (X25519) генерируется на онбординге; одна пара используется и как identity, и как DH-ключ.
- **Что НЕ работает / TODO:** Ed25519 sign/verify бросают `NotImplementedError`. Identity-как-ratchet-key — F13/F14/F15.
- **Сложность доработки:** M

#### B1.2 Secure storage
- **Статус:** PARTIAL
- **Файлы:** `KeystoreManager.kt:20`, `DatabasePassphraseManager.kt:18`, `Identity.sq:5`
- **Что работает:** SQLCipher-БД с рандомным 32-байтовым passphrase, зашифрованным через Android Keystore (AES-GCM, 256-bit, hardware-backed).
- **Что НЕ работает / TODO:** `dh_private_key_hex` попадает в БД в plain hex (sqlcipher-passphrase спасает только at-rest). `setUnlockedDeviceRequired` не используется (TODO Beta). iOS не использует Keychain.
- **Сложность доработки:** M

#### B1.3 Backup format / export
- **Статус:** NONE
- **Что НЕ работает / TODO:** Нет export-функции, нет seed-phrase, нет cloud-backup. ToS пункт 4: «lose your device — lose your account».
- **Сложность доработки:** L
- **Зависимости:** ADR по схеме backup, миграция identity-as-ratchet-key

### B2. Username system

#### B2.1 @username регистрация
- **Статус:** STUB (только локально)
- **Файлы:** `OnboardingScreen.kt:444`, `IdentityManager.kt:24`
- **Что работает:** Username сохраняется в локальной таблице identity. Валидация: lowercase + `[a-z0-9_]`, min 3 символа.
- **Что НЕ работает / TODO:** Нет регистрации на relay. Username — self-declared label, никем не подтверждён.
- **Сложность доработки:** L
- **Зависимости:** namespace-сервис на relay

#### B2.2 `@username → public key` mapping
- **Статус:** NONE (server-side)
- **Файлы:** `services/relay/src/routes.rs` (нет username handler)
- **Что работает:** Маппинг существует только локально (`Conversation.sq`, `Identity.sq`). Relay оперирует только public-key hex.
- **Что НЕ работает / TODO:** Нет directory-сервиса. Найти контакт по @username нельзя — только обмен полным `username:pubkeyHex` через QR/invite-link.
- **Сложность доработки:** L

#### B2.3 Uniqueness on relay
- **Статус:** NONE
- **Что НЕ работает / TODO:** Дубликаты разрешены. Два пользователя могут выбрать одинаковый @alice — relay различает только по public-key.
- **Сложность доработки:** L
- **Зависимости:** B2.2

#### B2.4 Reservation system
- **Статус:** NONE
- **Что НЕ работает / TODO:** Список reserved (admin/support/phantom/etc.) не существует ни в клиенте, ни на relay.
- **Сложность доработки:** S (после B2.2)

#### B2.5 Change @username post-registration
- **Статус:** NONE
- **Файлы:** `Identity.sq` (нет UPDATE username), ProfileScreen не имеет редактирования username
- **Сложность доработки:** S (локально) / L (с серверной уникальностью)

#### B2.6 Username search / discovery
- **Статус:** NONE (search только внутри своих контактов)
- **Что работает:** Локальный фильтр в ChatListScreen.
- **Что НЕ работает / TODO:** Глобального поиска нет. Контакта добавляют только через QR/invite-link/полный pubkey.
- **Сложность доработки:** L

### B3. Onboarding flow

#### B3.1 Welcome screens
- **Статус:** DONE
- **Файлы:** `OnboardingScreen.kt:68` (TermsScreen)
- **Что работает:** ToS-экран с восемью summary-секциями, scroll-gate (60%), ссылки на phntm.pro/terms и /privacy.

#### B3.2 Identity creation step
- **Статус:** DONE
- **Файлы:** `OnboardingScreen.kt:327`
- **Что работает:** Username field, BEGIN button → `IdentityManager.createOrLoad` → `DhKeyPair` → `container.initMessaging`.

#### B3.3 Username selection step
- **Статус:** PARTIAL
- **Что НЕ работает / TODO:** Только client-side, никакой server-side валидации, нет проверки занятости.
- **Сложность:** M
- **Зависимости:** B2

#### B3.4 Permissions
- **Статус:** PARTIAL
- **Файлы:** `OnboardingScreen.kt:339` (POST_NOTIFICATIONS), `QrScanScreen.kt:54` (CAMERA)
- **Что работает:** POST_NOTIFICATIONS на онбординге (Tiramisu+); CAMERA — лениво при QR-сканировании; mic — внутри CallManager.
- **Что НЕ работает / TODO:** Нет contacts-permission (приватность). Нет единого permissions-экрана.
- **Сложность:** S

#### B3.5 Auto-start of `PhantomMessagingService`
- **Статус:** DONE
- **Файлы:** `MainActivity.kt:269`
- **Что работает:** По завершении онбординга onComplete вызывает `context.startForegroundService(...)`.

### B4. Contact exchange

#### B4.1 QR generation (own identity → QR)
- **Статус:** DONE
- **Файлы:** `QrCodeImage.kt:25`, `ProfileScreen.kt:185`
- **Что работает:** zxing генерирует QR из строки `username:publicKeyHex` — отображается в QrKeyCard.

#### B4.2 QR scanning
- **Статус:** DONE
- **Файлы:** `QrScanScreen.kt:42`
- **Что работает:** CameraX + ML Kit BarcodeScanning, viewfinder, scannedState-флаг защищает от двойного срабатывания.

#### B4.3 Secure contact add via QR
- **Статус:** PARTIAL
- **Файлы:** `MainActivity.kt:301`, `AddContactDialog.kt:30`
- **Что работает:** Сканированная строка кладётся в prefill, парсится `username:key`, создаётся ConversationEntity с `trustTier=TRUSTED`.
- **Что НЕ работает / TODO:** TOFU без verification step (Safety Number открывается отдельно). Trust-tier сразу TRUSTED — нет промежуточного UNVERIFIED. F13/F14 — атакующий может подменить QR.
- **Сложность:** M

#### B4.4 Add by @username flow
- **Статус:** NONE
- **Что работает:** AddContactDialog требует pubkey ≥64 символов; одного @username недостаточно.
- **Сложность:** L
- **Зависимости:** B2.2

#### B4.5 Add by phone number
- **Статус:** NONE (by design)
- **Что НЕ работает / TODO:** По дизайну отсутствует — ToS пункт 5.

### B5. Trust & verification (interpersonal — safety numbers)

#### B5.1 Safety number generation
- **Статус:** DONE
- **Файлы:** `SafetyNumber.kt:33`
- **Что работает:** SHA-256 от lex-sorted конкатенации обоих pubkey-hex; nibble-mod-10 → 60 цифр, чанки по 12. Симметричный.
- **Что НЕ работает / TODO:** nibble-mod-10 даёт лёгкий bias 0-5 (документировано). Не HKDF, не Signal v2.

#### B5.2 Safety number display
- **Статус:** DONE
- **Файлы:** `ContactProfileScreen.kt:698`
- **Что работает:** ModalBottomSheet с centered-mono fingerprint, copy-кнопка, callout про сравнение out-of-band.

#### B5.3 Verification flow
- **Статус:** PARTIAL
- **Файлы:** `ContactProfileScreen.kt:742`
- **Что работает:** "Mark as Verified" → `conversationRepo.setVerified(true)` + `clearIdentityKeyChangedAt`.
- **Что НЕ работает / TODO:** Только manual "I confirm" — нет scan-each-other QR-flow, нет read-aloud, нет cross-device verification protocol.
- **Сложность:** M

#### B5.4 Trust state per contact
- **Статус:** PARTIAL
- **Файлы:** `Conversation.sq:11`
- **Что работает:** `is_verified INTEGER`, `trust_tier ('TRUSTED'/'REQUEST')`, при смене ключа `is_verified` сбрасывается.
- **Что НЕ работает / TODO:** trust_tier бинарный (нет UNVERIFIED промежутка); QR-добавленные сразу TRUSTED.

#### B5.5 Visual indicator in chat list (verified)
- **Статус:** NONE
- **Что работает:** Verified-индикатор виден только в ContactProfile (`PhIconShieldCheck`).
- **Что НЕ работает / TODO:** В ChatList нет shield-иконки рядом с username.
- **Сложность:** S

---

## C. UI / UX (Compose Multiplatform)

### C1. Screens inventory

Все Android-экраны живут в `apps/android/src/androidMain/kotlin/phantom/android/screens/`. Используют Material3 (Scaffold/TopAppBar/AlertDialog/ModalBottomSheet) + кастомный `PhantomKit.kt` + кастомные Canvas-иконки `PhantomIcons.kt` (НЕ Material Icons / Lucide / SVG).

| Screen | LOC | Status | Notes |
|---|---|---|---|
| `onboarding/OnboardingScreen.kt` | 591 | DONE | ToS-gate → username-pick → permission → onComplete |
| `chatlist/ChatListScreen.kt` | 740 | DONE | 1:1 + groups + channels секции, search, requests, ConnectionBanner |
| `chat/ChatScreen.kt` | 2150 | PARTIAL | ~80% feature-complete для 1:1, voice limitation, attachments TBD |
| `chat/EmojiData.kt` | — | DONE | Static emoji catalogue |
| `settings/SettingsScreen.kt` | 306 | PARTIAL | Privacy mode dropdown, многие пункты → showComingSoon() |
| `profile/ProfileScreen.kt` | 1122 | DONE | Avatar, DOB numeric, name fields, QR-card, share-via-link |
| `contact/ContactProfileScreen.kt` | 921 | DONE | Pubkey display, safety-number sheet, verify, notes, disappearing |
| `chatlist/AddContactDialog.kt` | 153 | DONE | Paste-key (≥64 hex), local-alias, parses `username:key` |
| `qr/QrScanScreen.kt` | 248 | DONE | CameraX + ML Kit |
| `qr/QrCodeImage.kt` | 53 | DONE | zxing |
| `splash/SplashScreen.kt` | 133 | DONE | Animated logo + sonar |
| `calls/CallsScreen.kt` | 215 | PARTIAL | List of past calls; depends on CallManager |
| `calls/ActiveCallScreen.kt` | 214 | PARTIAL | Mute/speaker/hangup; voice limitation |
| `calls/IncomingCallScreen.kt` | 174 | PARTIAL | Answer/reject |
| `group/GroupChatScreen.kt` | 765 | PARTIAL | SenderKey-based, вне Alpha-0 scope per docs |
| `group/CreateGroupScreen.kt` | 287 | PARTIAL | Создание группы, contact picker |
| `channel/CreateChannelScreen.kt` | 259 | PARTIAL | One-to-many channel вариант |
| `saved/SavedMessagesScreen.kt` | 511 | DONE | Self-conversation |
| `archive/ArchiveScreen.kt` | 216 | DONE | Archived conversations list |
| `lock/AppLockScreen.kt` | 185 | DONE | BiometricPrompt, 60s background gate |
| `requests/MessageRequestsScreen.kt` | 179 | DONE | trustTier=REQUEST список с accept/block |

### C2. Платформы

#### C2.1 Android
- **Статус:** DONE
- **Файлы:** `gradle/libs.versions.toml:38-40`, `apps/android/build.gradle.kts:84-89`
- **Что работает:** compileSdk=35, minSdk=26, targetSdk=35. Android 8.0+. JVM 21 bytecode. SQLCipher native lib loads in Application.onCreate. enableEdgeToEdge gated на API<35.

#### C2.2 iOS
- **Статус:** STUB
- **Файлы:** `apps/ios/PhantomApp.swift` (12 LOC), `ContentView.swift` (53 LOC), `Views/OnboardingView.swift` (112 LOC), `ChatListView.swift`, `ChatView.swift`, `Phantom.xcodeproj/project.pbxproj`
- **Что работает:** Pure SwiftUI thin shell. Xcode project существует. Тематические цвета (cyanAccent, bgDeep) повторяют Android.
- **Что НЕ работает / TODO:** **НЕТ KMP-bridge.** IdentityStore — UserDefaults UUID-stub. ChatListView.conversations всегда пустой. ChatView.swift — placeholder. iosMain существует только для DatabaseDriverFactory + RelayTransportFactory (только expect/actual инфра). Нет XCFramework, нет линковки shared/core.
- **Сложность доработки:** XL
- **Зависимости:** macOS-builder, Apple Developer account, KMP XCFramework, libsodium-iOS, SQLCipher-iOS

#### C2.3 Compose Multiplatform shared module
- **Статус:** NONE (UI не shared)
- **Что работает:** `shared/core/*` — KMP без Compose. Compose UI живёт ТОЛЬКО в `apps/android`.
- **Что НЕ работает / TODO:** iOS не использует Compose Multiplatform — пишется на нативном SwiftUI.
- **Сложность доработки:** XL (если стратегически переходить на CMP) или N/A (если оставить SwiftUI)

#### C2.4 Platform-specific code paths
- **Статус:** PARTIAL
- **Файлы:** `shared/core/storage/src/{androidMain,iosMain,jvmMain}/...`, `shared/core/transport/src/{androidMain,iosMain,jvmMain}/...`
- **Что работает:** expect/actual для DB-driver, transport-factory, log. Android актуал — sqlcipher-android + OkHttp; jvm — sqlite-jdbc + Ktor CIO.
- **Что НЕ работает / TODO:** iosMain stubs только для storage/transport. Нет identity/crypto/messaging actuals на iOS.
- **Сложность доработки:** L

### C3. Theming & design system

#### C3.1 Design tokens
- **Статус:** PARTIAL (centralised colors, hardcoded font/spacing)
- **Файлы:** `apps/android/.../ui/theme/PhantomTheme.kt:11-18`
- **Что работает:** Цвета централизованы (CyanAccent, BgDeep, Surface, Surface2, TextPrimary, TextDim, Success, Danger).
- **Что НЕ работает / TODO:** Spacing (24.dp, 32.dp, 8.sp, …) хардкожен по всем экранам. Нет typography token system, нет shape tokens. iOS дублирует токены в `Color+Phantom.swift` (drift risk).
- **Сложность доработки:** M

#### C3.2 Dark theme
- **Статус:** SINGLE (dark only)
- **Файлы:** `PhantomTheme.kt:20`, `PhantomApp.swift:8`
- **Что работает:** Только dark. background `BgDeep #0B0D12`.
- **Что НЕ работает / TODO:** Light theme отсутствует.
- **Сложность доработки:** M

#### C3.3 Fonts
- **Статус:** STUB (system default + monospace)
- **Что работает:** Monospace для wordmark, labels, fingerprint; default sans-serif для body.
- **Что НЕ работает / TODO:** Brand font не загружен (никаких .ttf/.otf в res/font). Каждый Text() прописывает FontFamily inline.
- **Сложность доработки:** S

#### C3.4 Icons
- **Статус:** DONE (custom Canvas-based)
- **Файлы:** `PhantomIcons.kt` (535 LOC, ~30 PhIcon* функций)
- **Что работает:** Все иконки — Canvas+Path/PathParser (PhIconBack, Chevron, Pencil, Mic, MicOff, Reply, Trash, Pin, Bookmark, Check, DoubleCheck, Person, Users, Megaphone, Shield, ShieldCheck, Lock, Eye, Timer, etc.). НЕТ Material Icons / Lucide / SVG.

---

## D. PLANNED FEATURES — ТЕКУЩИЙ СТАТУС

### D1. Premium / Monetization

| Subitem | Status | Note |
|---|---|---|
| D1.1 Stripe integration | NONE | — |
| D1.2 Subscription state в клиенте | NONE | `TrustTier` существует, но это safety-tier, не subscription |
| D1.3 Feature gating mechanism | NONE | — |
| D1.4 Server-side validation | NONE | `services/relay/src/` — нет billing/auth |
| D1.5 Recovery Phrase (BIP-39) | NONE | Identity — random 32B, не seed-derived |

**Сложность доработки до production:** L (D1.1 / D1.4), M (D1.2 / D1.5), S (D1.3)
**Зависимости:** server billing service, account/identity-binding decision

### D2. Groups & channels

#### D2.1 Groups — base architecture
- **Статус:** DONE
- **Файлы:** `GroupMessagingService.kt`, `DefaultGroupMessagingService.kt:44-454`, `Group.sq`, `GroupMember.sq`, `SenderKeyStore.sq`, `screens/group/`
- **Что работает:** createGroup, addMember, leaveGroup, sendGroupMessage, sendGroupAudio, fan-out per-member envelopes, receive/decrypt, persist, SenderKey distribution.
- **Что НЕ работает / TODO:** Control messages НЕ wrapped в Double Ratchet (`DefaultGroupMessagingService.kt:418-426`); `signingPrivHex=""` для remote keys (signature verification not enforced); `handleLeave` удаляет ВСЕ group SenderKeys вместо proper rotation; no admin model в DB; no group avatar/edit-name.
- **Сложность:** M

#### D2.2 Group encryption protocol
- **Статус:** PARTIAL (~70%) — Sender Keys
- **Файлы:** `SenderKey.kt`, `DefaultGroupMessagingService.kt:118-412`
- **Что работает:** Sender Keys (chain-key ratchet + Ed25519 signing pub broadcast). Encrypt/decrypt round-trips. Per-member chainKey persisted.
- **Что НЕ работает / TODO:** **Signing private key никогда не достигает recipients** (signingPrivHex stored as `""` at lines 213, 247, 272), поэтому signature verification на incoming — best-effort. Нет out-of-order recovery. Нет key-rotation на member-leave.
- **Сложность:** M

#### D2.3 Channels (one-to-many broadcast)
- **Статус:** PARTIAL (~60%)
- **Файлы:** `screens/channel/CreateChannelScreen.kt:32-260`, `MessagePayload.kt:61` (TYPE_CHANNEL_POST), `DefaultGroupMessagingService.kt:177,352`
- **Что работает:** CreateChannel UI, `isChannel=true` flag, channel posts use TYPE_CHANNEL_POST.
- **Что НЕ работает / TODO:** Нет enforcement что только админы постят (любой member может отправить TYPE_CHANNEL_POST), нет public-link/discovery, нет subscriber count, нет channel-specific UI (reuse GroupChatScreen).
- **Сложность:** M

#### D2.4 Group/channel admin roles
- **Статус:** STUB
- **Файлы:** `Group.sq` (myRole TEXT)
- **Что работает:** role string ("admin"/"member") set at invite time.
- **Что НЕ работает / TODO:** Нет server/protocol enforcement, нет promote/demote, нет permission checks, нет UI.
- **Сложность:** M

#### D2.5 Group member management
- **Статус:** PARTIAL (~50%)
- **Что работает:** addMember, leaveGroup, automatic SenderKey distribution.
- **Что НЕ работает / TODO:** Нет removeMember (kick), нет proper key rotation на leave, нет transfer ownership, нет member list UI, нет per-member mute.
- **Сложность:** M

### D3. Localization (i18n)

| Subitem | Status | Note |
|---|---|---|
| D3.1 Strings resources set up | NONE | `apps/android/src/androidMain/res/values/` — только `colors.xml`, `themes.xml`. Никаких strings.xml. Все UI literals inline |
| D3.2 Языки добавлены | NONE | Hardcoded English. Legal docs EN+RU отдельно (`legal/`), но не в app |
| D3.3 Auto-detect системного языка | NONE | `Locale.getDefault` используется только для date-formatting |
| D3.4 Manual language override в Settings | STUB | Row "Language: English" → showComingSoon() |
| D3.5 RTL support | STUB | `supportsRtl="true"` в манифесте, но строк нет |
| D3.6 Plurals / gender-aware | NONE | — |

**Сложность:** M (D3.1 — full string-extraction pass), S остальное.

### D4. Voice & video calls

| Subitem | Status | Note |
|---|---|---|
| D4.1 WebRTC integration | PARTIAL (~70%) Android only | `CallManager.kt:31-373` — PeerConnectionFactory, audio-only PeerConnection, offer/answer/ICE |
| D4.2 ICE / STUN / TURN | PARTIAL (STUN only) | 2 hardcoded Google STUN. **NO TURN** — calls behind symmetric NAT fail |
| D4.3 Signaling | DONE | Через relay: TYPE_CALL_OFFER/ANSWER/ICE/HANGUP/REJECT, base64 JSON. **НЕ Double-Ratchet wrapped** |
| D4.4 Voice call UI | DONE | IncomingCallScreen, ActiveCallScreen, CallsScreen |
| D4.5 Video call UI | NONE | `OfferToReceiveVideo=false` |
| D4.6 Group calls | NONE | — |
| D4.7 Encryption SRTP+DTLS | DONE | WebRTC default |
| D4.8 Call history (DB) | NONE | Hardcoded "Call history — coming soon" banner |

**Сложность:** L (iOS WebRTC parity), S (call history table), XL (group calls + SFU), M (video).

### D5. Voice messages

| Subitem | Status | Note |
|---|---|---|
| D5.1 Recording | DONE | MediaRecorder, mic permission, start/stop/cancel |
| D5.2 Audio format | PARTIAL (suboptimal) | **THREE_GPP + AMR_NB 8kHz, ~12kbps**. Несмотря на коммент "OGG audio" в `MessagePayload.kt:35`. Звук плохой и patent-encumbered |
| D5.3 Encryption | DONE (1:1) / PARTIAL (groups) | 1:1 — base64 в `[AUDIO:$base64]` text payload, через Double Ratchet. Group — `audioDataB64` через Sender Key |
| D5.4 Waveform visualization | STUB | `LinearProgressIndicator` только. Нет real amplitude bars |
| D5.5 Playback | PARTIAL (~50%) | Play/pause работает. Нет scrubbing, нет speed control |
| D5.6 Background upload во время записи | NONE | Полностью sequential |

**Сложность:** S (большинство), M (D5.6 chunked transport).

### D6. Nearby / Radar (Bluetooth + Wi-Fi Direct)

| Subitem | Status | Note |
|---|---|---|
| D6.1-D6.4 BT/BLE/WifiDirect/Mesh | NONE | Никаких BLUETOOTH* permissions, никаких WifiP2p references. Out of Alpha-0 scope per ADR-005 |
| D6.5 Nearby UI screen | STUB (web prototype only) | `prototype/web/app/nearby/page.tsx` — Next.js mockup, нет Compose-экрана |

**Сложность:** L-XL для BT/BLE/WiFi, S для UI port.

### D7. Files & media

| Subitem | Status | Note |
|---|---|---|
| D7.1 Send/receive image | NONE | Никаких GetContent, PickVisualMedia, image/* MIME, TYPE_IMAGE |
| D7.2 Send/receive document | NONE | — |
| D7.3 Image preview / lightbox | NONE | — |
| D7.4 File size limits | UNKNOWN | Implicit limit через single-message ratchet; voice до ~2MB; нет explicit attachment size constant |
| D7.5 Media auto-download settings | NONE | — |

**Сложность:** M (D7.1 — нужна attachment infra), S остальное.

### D8. Notifications

| Subitem | Status | Note |
|---|---|---|
| D8.1 Local notifications | DONE | `PhantomNotificationManager.kt:31-142`, channel "phantom_messages" IMPORTANCE_HIGH, heads-up, 30-char preview, inline RemoteInput "Reply" |
| D8.2 Push notifications | PARTIAL (~50%) — FCM scaffolded, не wired | `PhantomFirebaseMessagingService.kt:32-84`, firebase-messaging-ktx 23.4.1, google-services.json. Token persisted в SharedPreferences. **Token НЕ POSTed на relay** (TODO). Relay не имеет `/register-fcm` endpoint. UnifiedPush — NONE. iOS APNs — NONE |
| D8.3 Notification content privacy | PARTIAL — privacy-by-default, без toggle | Preview всегда truncate; FCM data carries no plaintext. Нет user setting "show only 'New message'" |
| D8.4 Per-chat mute / DND schedule | NONE | Settings → showComingSoon(). Нет `muted` column в Conversation.sq |

**Сложность:** M (D8.2 wire FCM), S остальное.

---

## E. PRIVACY & SECURITY

### E1. Privacy modes

| Subitem | Status | Note |
|---|---|---|
| E1.1 Standard mode | NONE | Концепция Privacy Modes отсутствует в коде |
| E1.2 Private mode | NONE | — |
| E1.3 Ghost mode | NONE | — |

Settings screen имеет только "App Lock" toggle. Нет выбора режима, нет per-mode policy.

**Сложность:** L (дизайн модели + UI + state + интеграция в transport/storage/notif)
**Зависимости:** продуктовое решение по объёму режимов

### E2. Disappearing messages

#### E2.1 Per-chat timer
- **Статус:** DONE
- **Файлы:** `Conversation.sq:12,69-73`, `DefaultMessagingService.kt:583-615` (sync через E2EE), `:364-372`, `ContactProfileScreen.kt:791`
- **Что работает:** Per-conversation таймер в секундах, sync обоим участникам через E2EE control-message. Защита от downgrade.

#### E2.2 Per-message timer
- **Статус:** NONE
- **Что работает:** Только conversation-wide timer.
- **Сложность:** M

#### E2.3 Client-side auto-delete
- **Статус:** DONE
- **Файлы:** `DisappearingMessageScheduler.kt:25-48`, `Message.sq:43-53`, `DefaultMessagingService.kt:149-152,407-409`
- **Что работает:** Coroutine-loop wakes at next expiry, удаляет expired, спит до следующего. Application-scope.
- **Что НЕ работает / TODO:** Не работает в фоне когда процесс убит (нет WorkManager — комментарий в коде явно признаёт). Сообщение не удалится пока пользователь не запустит app.
- **Сложность:** M (миграция на WorkManager)

#### E2.4 Server-side TTL
- **Статус:** DONE
- **Файлы:** `services/relay/src/config.rs:12,44`, `envelope.rs`, `routes.rs:139-142,334`
- **Что работает:** Default 604800 (7 дней). Применяется на reconnect-flush и send-time.
- **Что НЕ работает / TODO:** Нет периодической очистки.

### E3. Screen lock / app lock

#### E3.1 Biometric (FaceID / fingerprint)
- **Статус:** DONE
- **Файлы:** `AppLockScreen.kt:131-185`
- **Что работает:** BiometricPrompt с BIOMETRIC_STRONG | DEVICE_CREDENTIAL. Auto-prompt на entry, manual UNLOCK retry. Если biometric/PIN не enrolled — unlock проходит сразу (graceful).

#### E3.2 PIN fallback
- **Статус:** DONE (через DEVICE_CREDENTIAL)
- **Файлы:** `AppLockScreen.kt:144-145,180-182`
- **Что работает:** Authenticators.DEVICE_CREDENTIAL подключен — система предложит device PIN/pattern.
- **Что НЕ работает / TODO:** Нет in-app собственного PIN (только устройства). Если пользователь не задавал device-PIN, lock эффективно отключается.
- **Сложность:** M (in-app PIN с PBKDF2-bound to keystore)

#### E3.3 Per-chat lock
- **Статус:** NONE
- **Что работает:** Только глобальный app-lock.
- **Сложность:** M

#### E3.4 Auto-lock timer
- **Статус:** PARTIAL — фиксированный 60s, без UI настройки
- **Файлы:** `MainActivity.kt:81-95`
- **Что работает:** При возврате из background если прошло >60 секунд — re-lock.
- **Что НЕ работает / TODO:** Threshold захардкожен (60_000L), нет UI выбора (Immediately / 1m / 5m / 1h).
- **Сложность:** S

### E4. Screenshot protection

#### E4.1 Android FLAG_SECURE
- **Статус:** **NONE**
- **Файлы:** — (grep по всему коду — НЕТ совпадений)
- **Что работает:** Ничего.
- **Что НЕ работает / TODO:** **Скриншоты разрешены везде, recents-thumbnail видим. Известный пробел.**
- **Сложность:** S (один setFlags в MainActivity.onCreate)

#### E4.2 Screenshot detection
- **Статус:** NONE
- **Что работает:** Ничего. Нет ContentObserver на MediaStore, нет FileObserver.
- **Сложность:** M (Android 14+ broadcasts + fallback observer + UI + cross-device payload)

---

## F. INFRASTRUCTURE

### F1. Production relay (Hetzner Helsinki, `relay.phntm.pro`)

#### F1.1 Rust code version
- **Статус:** DONE
- **Файлы:** `services/relay/Cargo.toml` (name=phantom-relay, version=0.1.0, edition=2021), `services/Cargo.toml:11-17`
- **Что работает:** axum 0.8 (with "ws"), tokio 1 ("full"), tower 0.5, tower-http 0.6, serde 1, tracing 0.1, time 0.3, futures-util 0.3, subtle 2. Workspace объявляет crates `bootstrap` и `directory` (skeletons, не подключены).
- **Что НЕ работает / TODO:** Релиз 0.1.0 — semver не двигался с Alpha-0. Dockerfile sed-патчит workspace members до ["relay"].

#### F1.2 Caddyfile
- **Статус:** DONE
- **Файлы:** `deploy/Caddyfile` (159 строк)
- **Что работает:** Два vhost'а — `relay.phntm.pro` (reverse_proxy → relay:8080, HSTS, security headers, no proxy timeouts, flush_interval=-1) и `phntm.pro/www.phntm.pro` (handle_path для `/.well-known/*`, `/assets/*`, `/terms{,/ru}`, `/privacy{,/ru}`, fallback → `/srv/landing`). Cloudflare DNS-only для relay, Proxied для phntm.pro.
- **Что НЕ работает / TODO:** Cloudflare orange-cloud требует ручной flip; DNS-01 не включён.

#### F1.3 Docker Compose
- **Статус:** DONE
- **Файлы:** `deploy/docker-compose.yml`, `services/relay/Dockerfile`
- **Что работает:** relay — мульти-стейдж build, non-root uid=10001, cap_drop ALL, no-new-privileges, read_only rootfs, tmpfs /tmp, volume `phantom-reports:/var/phantom`. caddy — caddy:2.8-alpine, ports 80/443/443udp, mounts (Caddyfile/well-known/landing/legal:ro), caddy-data/caddy-config volumes. depends_on relay.
- **Что НЕ работает / TODO:** .env вне репо. Нет healthcheck у caddy. Нет лимитов CPU/RAM.

#### F1.4 Database schema (server-side)
- **Статус:** STUB (in-memory only — нет SQL вообще)
- **Файлы:** `services/relay/src/state.rs:28-46,65-95`
- **Что работает:** В Cargo.toml нет ни sqlite, ни postgres, ни sled. Всё in-memory: HashMap<recipient → Vec<Envelope>>, online clients, sliding-window rate-limiter. Persistence только для двух cleartext-метаданных: `reports.jsonl` (abuse-reports) и `blocklist.txt`. Envelope TTL 7 дней, max 500/recipient.
- **Что НЕ работает / TODO:** **Релей теряет всю in-memory store при рестарте — envelope'ы вне ack-deliver не переживают перезагрузку.**
- **Сложность:** M (sled или Redis-pubsub)

#### F1.5 Monitoring / logging
- **Статус:** PARTIAL
- **Файлы:** `main.rs:14-18`, `routes.rs:51-58,120-125,206-211,246-253`
- **Что работает:** Structured tracing с `tracing` 0.1 + `tracing-subscriber`. Метаданные логируются (connect/disconnect/message/abuse_report/admin_block). Query strings и токены никогда не печатаются. Ключи усекаются до 16 символов. HEALTHCHECK на `/health` каждые 30s.
- **Что НЕ работает / TODO:** Нет Prometheus/metrics. Нет alerting. Нет log-shipping (Loki/ELK). Логи только в stdout.
- **Сложность:** M

#### F1.6 Backup strategy
- **Статус:** NONE
- **Файлы:** `keystores/README.md:31-40` (instructions для Android keystore — manual)
- **Что работает:** Caddy сертификаты переживают рестарт благодаря volume `caddy-data`.
- **Что НЕ работает / TODO:** Нет автоматического off-site бэкапа Hetzner volumes. Нет snapshot-стратегии. abuse-reports.jsonl не реплицируется.
- **Сложность:** S (rsync/restic cronjob к offsite-S3)

### F2. Build & release

#### F2.1 CI / CD
- **Статус:** NONE
- **Файлы:** `.github/workflows/` — отсутствует
- **Что работает:** Локальная gradle-сборка с smoke-набором.
- **Что НЕ работает / TODO:** Нет ни одного GitHub Actions workflow.
- **Сложность:** M

#### F2.2 Reproducible builds
- **Статус:** NONE
- **Что работает:** Cargo.lock закоммичен, versions pinned в Caddyfile/Dockerfile.
- **Что НЕ работает / TODO:** Нет SOURCE_DATE_EPOCH, нет diffoscope. Android APK не reproducible (Compose + R8 non-determinism). Нет SLSA/SBOM.
- **Сложность:** L (Android — отдельный проект; relay — проще)

#### F2.3 APK signing
- **Статус:** DONE
- **Файлы:** `apps/android/build.gradle.kts:15-20,98-115,129-150`, `keystores/README.md`
- **Что работает:** RSA-4096, validity 30 лет, CN=PHANTOM Messenger O=Willen LLC C=US, SHA-256 fingerprint AA:17:09:48... запинен в `assetlinks.json`. Gradle подгружает из `keystores/signing.properties` или из `SIGNING_*` env vars (готово к CI). Если ключа нет — graceful fallback на debug-signing.

#### F2.4 Release pipeline
- **Статус:** PARTIAL (manual)
- **Что работает:** Ручной workflow — `./gradlew assembleRelease`, копирование APK, обновление RELEASE_NOTES.md.
- **Что НЕ работает / TODO:** Нет gh release create автоматизации, нет Play Console upload. **versionName "0.0.1-alpha" не синхронен с тегом v0.1.0-alpha.1.**
- **Сложность:** M

### F3. iOS

#### F3.1 Xcode project
- **Статус:** STUB
- **Файлы:** `apps/ios/Phantom.xcodeproj/project.pbxproj` (~60 строк), 6 Swift файлов
- **Что работает:** Standalone SwiftUI-приложение с UserDefaults-stub'ом для identity. Запускается, тёмная тема.
- **Что НЕ работает / TODO:** ContentView.swift:6-9 явно: «Identity persistence is handled by UserDefaults for Alpha-0. Alpha-1 will replace this with the KMP IdentityManager once the XCFramework is linked.» **iOS НЕ интегрирован с KMP, не имеет сети, не имеет крипто.**
- **Сложность:** L

#### F3.2 Compose Multiplatform iOS targets
- **Статус:** NONE
- **Файлы:** 16 build.gradle.kts по shared/* — ни в одном `iosX64()/iosArm64()/iosSimulatorArm64()` не активирован
- **Что работает:** Ничего из shared-модулей не компилируется на iOS. Комментарии в build.gradle.kts: «Kotlin/Native cross-compilation to iOS is not supported on Windows.»
- **Сложность:** L (требует macOS, активация iosTargets, KMP-XCFramework gradle task)

#### F3.3 libsodium iOS bindings
- **Статус:** NONE
- **Что работает:** ionspin биндинги поддерживают Apple targets, но iOS target не активирован.
- **Сложность:** M (после активации iOS target биндинги тянут libsodium автоматом)

#### F3.4 Native iOS Swift code
- **Статус:** STUB
- **Файлы:** `apps/ios/*.swift` (498 LOC всего); `iosMain/.../DatabaseDriverFactory.kt` (24 LOC, без SQLCipher); `iosMain/.../RelayTransportFactory.kt` (16 LOC, Darwin engine)
- **Что работает:** 6 Swift файлов реализуют SwiftUI-онбординг и фейковые ChatList/Chat-views. 2 Kotlin-actual'а готовы к подключению, но не компилируются.
- **Что НЕ работает / TODO:** Никакой E2E работы. SQLCipher iOS-pod закомментирован.
- **Сложность:** L

---

## G. KNOWN ISSUES & TECHNICAL DEBT

### G1. Known bugs

| ID | Status | Description |
|---|---|---|
| ISSUE-001 (Tecno HiOS) + Bug J | **OPEN** (mitigated for text, NOT for voice) | OEM-radio parking. Текст +1-3s латентность. Voice >50KB на Tecno НЕ доставляется (asymmetric outbound packet loss, диагностирован 2026-04-28). Long-term fix: Unified Push + attachment server (Alpha 2) |
| Mobile carrier NAT reconnection | **RESOLVED** (диагноз пересмотрен) | Изначальный диагноз опровергнут WiFi-only retest |
| G1.3 Email integration (Alpha 2) | DEFERRED-TO-ALPHA-2 | 6 эмейлов заведены, не интегрированы в onboarding ToS |
| G1.4 ToS revision | DEFERRED-TO-ALPHA-2 | Текст требует ревизии против ADR-004, Threat Model, Doctrine |
| ISSUE-006 / G1.5 Delivery indicators | DEFERRED-TO-ALPHA-2 | Wire signals существуют, но UI коллапсит. Чисто client-side projection bug |
| Bug I / G1.6 Profile sync | OPEN (FEATURE GAP) | Аватар отображается локально, но не передаётся через ratchet. Запланировано Alpha 2 |
| G1.7 Date format localization | UNKNOWN | В KNOWN_ISSUES.md/tech_debt.md явно не упомянут |
| G1.8 Message input clipping | RESOLVED (этой сессии) | enableEdgeToEdge() migration |
| ISSUE-002, ISSUE-003 | RESOLVED commit `5caf61eb` | init-sequence требовал app restart; false-offline banner |
| ISSUE-004 (P2) | OPEN | MAC verification fail на первом конверте после batched-flush. Mitigation: sender retry. Fix: increase MAX_SKIP (Alpha 2) |
| ISSUE-005 (P2) | OPEN | Onboarding не показывает confirmation после регистрации |
| ISSUE-007 (P2) | DEFERRED | Nearby radar UI |
| ISSUE-008 (P3) | RESOLVED commit `69e87ad8` | debug URL hardcoded → wss://relay.phntm.pro/ws |
| ISSUE-009 (P3) | DEFERRED | iOS build не существует |
| ISSUE-010 (P3) | DEFERRED | web client не подключён к prod relay |
| 16+ закрытых багов в KNOWN_ISSUES.md | RESOLVED | BUG-A, H, CRYPTO-RACE, RELAY-LOST, OUTBOX-ORDER, CLOSE-HANG, DOUBLE-DELIVER, CONNID-RACE, WS-TIMEOUT, CADDY-TIMEOUT, DEBUG-URL, INIT-SEQ, FALSE-OFFLINE, PINGJOB-LEAK, RELAY-NO-PONG, OEM-RADIO-PARK |

### G2. Technical debt

**TODO/FIXME/HACK count:** **10** (8 в Kotlin, 2 в Rust)

**Notable instances:**
1. `apps/android/.../notifications/PhantomFirebaseMessagingService.kt:22,40` — POST FCM token to relay /register-fcm (FCM push pipeline incomplete)
2. `apps/android/.../di/AppContainer.kt:134,143` — fcmToken registration не реализован
3. `apps/android/.../security/KeystoreManager.kt:37` — `setUnlockedDeviceRequired(true)` отложено до Beta
4. `apps/android/.../screens/chat/ChatScreen.kt:727` — scroll to pinned message TODO
5. `services/relay/src/routes.rs:368` — FCM silent push не реализован
6. `services/relay/src/config.rs:28` — мигрировать с deprecated FCM Legacy на FCM v1 (OAuth2)
7. `shared/core/storage/src/{commonMain,iosMain}/.../DatabaseDriverFactory.kt:18,15` — SQLCipher iOS pod не интегрирован

**Architectural decisions to revisit:**
- **F13/F14/F15** — identity-as-ratchet-key (архитектурный блокер Alpha-1 → Beta)
- **Relay полностью in-memory без persistence** (F1.4)
- **Inlined base64 voice через WebSocket** (Bug J root structural fix: attachment server)
- **Group control messages NOT wrapped в Double Ratchet** (D2.1)
- **`signingPrivHex=""` в Sender Keys** — signature verification best-effort (D2.2)
- **9 расхождений docs vs code** в `project_doc_contradictions.md`

**Deprecated / version pressure:**
- Java 21 / JVM_TARGET=JVM_21 — свежий LTS, требует JDK 21 у contributor'ов
- `kotlin-bignum` upgrade сломал `Byte.xor` (Bug C)
- CameraX `ImageProxy.image` теперь `@ExperimentalGetImage` (Bug F)
- FCM Legacy HTTP API deprecated
- libsodium JNI не работает на JVM unit-test classpath (Bug H)

**Quick-fix patterns:**
- Dockerfile sed-патчит workspace members до ["relay"] (services/relay/Dockerfile:40)
- versionName "0.0.1-alpha" не синхронен с тегом v0.1.0-alpha.1
- signingConfig fallback на debug при отсутствии keystore — удобно contributor'ам, но опасно в CI

### G3. Test coverage

**Total: ~60 tests** (44 commonTest + 16 androidInstrumentedTest)

**Unit tests (commonTest, run on JVM):**
| Файл | @Test count |
|---|---|
| `IdentityManagerTest.kt` | 4 |
| `LibsodiumIdentityCryptoTest.kt` | 4 |
| `Alpha0IntegrationTest.kt` | 3 *(падает на JVM — libsodium NPE, см. Bug H-follow-up)* |
| `DefaultMessagingServiceTest.kt` | 7 |
| `InMemoryRepositoryTest.kt` | 14 |
| `FakeRelayTransportTest.kt` | 7 |
| `RelayMessageSerializationTest.kt` | 5 |

**Integration / instrumented (Android emulator):**
| Файл | @Test count |
|---|---|
| `LibsodiumDoubleRatchetTest.kt` | 5 |
| `LibsodiumX3DHTest.kt` | 4 |
| `SafetyNumberTest.kt` | 4 |
| `SealedSenderTest.kt` | 3 |

**NONE:**
- Android UI tests (Compose) — никаких
- Rust tests (relay) — `Grep -c "#\[test\]"` → 0
- iOS tests
- jvmTest

**Не покрыто:**
- WebSocket reconnect logic (никаких тестов на ISSUE-001 / Bug J поведение)
- Foreground service
- Compose UI flows
- Relay HTTP/WS endpoints (нет integration test против relay)
- Trust Tier state machine
- SQLCipher migrations
- QR-scan + assetlinks deep-link flow
- FCM token registration

---

## H. АРХИТЕКТУРНЫЕ ВОПРОСЫ — РЕКОМЕНДАЦИИ

### H1. Username uniqueness

**Текущее состояние:** Никакой server-side username service не существует. Username — это self-declared label в `Identity.sq`. Relay (`services/relay/src/routes.rs`) о username не знает — оперирует только `public_key_hex`. Дубликаты разрешены полностью.

**Что нужно сделать:**

1. **Server-side таблица `usernames`** — нужно создавать. Текущий relay полностью in-memory (F1.4), это значит **сначала надо ввести persistence layer** (sled или Postgres/SQLite) или сделать **отдельный directory-сервис**, отдельный от relay-а. Я бы рекомендовал второе: `directory.phntm.pro` — небольшой axum-сервис на том же VPS, с sqlite-persistence, отвечает на запросы `POST /reserve` и `GET /resolve/{username}`. Это держит relay zero-knowledge как сейчас (relay всё ещё ничего не знает о username — он только про routing pubkey-hex).

2. **Резервированные имена** — список нужно завести. Минимум: `admin`, `support`, `phantom`, `help`, `info`, `legal`, `security`, `abuse`, `api`, `bot`, `system`, `staff`, `team`, `official`, `verified`, и набор английских/русских ругательств. Хранить в `directory/reserved.txt` (текстовый файл с одним именем на строку, deploy-time).

3. **Race condition при одновременной регистрации** — стандартный паттерн «попробуй INSERT, на duplicate-key верни 409 conflict». В sqlite — UNIQUE constraint на `username` column. В коде: атомарная транзакция `BEGIN; SELECT WHERE username=? FOR UPDATE; INSERT; COMMIT`.

4. **Error flow при duplicate** — клиент при онбординге показывает inline-ошибку «@vasya is taken; try @vasya_42 or @vasya2026». Минимум: `409 Conflict` от directory-сервиса + retry UI. Максимум: автоматические suggestions от server-а.

5. **Privacy concern:** directory-сервис видит mapping `(IP || identity-pubkey || username)`. Это metadata-leakage. Mitigation: клиент общается с directory через Tor / domain fronting в Privacy/Ghost mode (или просто всегда). Альтернатива (более радикальная): hashed-username — клиент шлёт `Argon2id(username, salt)`, server проверяет уникальность хеша, а отдельный resolve использует private set intersection. Это **слишком сложно для Alpha 2** — рекомендую оставить plain username с осознанным privacy trade-off, документировать в Privacy Policy.

**Сложность:** L (~2-3 месяца — новый микросервис, 5-6 endpoint'ов, sqlite, deploy)
**Зависимости:** ADR на directory-сервис, решение по privacy trade-off

---

### H2. Verification system (галочка ✓)

**Текущее состояние:** NONE. Никакой инфраструктуры верификации не существует.

**Что нужно сделать:**

1. **Verification authority key** — хардкод Willen LLC Ed25519 public key в клиент (constants.kt). Лучше — два ключа (rotation slot + active), чтобы можно было ротировать без force-update клиента. Build-time: ключ в `constants.kt`, компилируется в каждый APK. **Reproducible builds (F2.2) тут критичны** — иначе нет способа доверять что APK содержит правильный ключ. Это требует решить F2.2 до того как verification станет meaningful.

2. **Формат signed certificate:**
   ```
   subject_pubkey || subject_username || display_name || verified_until_unix || ed25519_signature_by_willen
   ```
   `verified_until` коротколивущий — например 90 дней — чтобы не нужен был отдельный CRL. Клиент проверяет `now() <= verified_until` при отрисовке badge.

3. **Distribution** — через тот же directory-сервис из H1. Endpoint `GET /verified/{username}` возвращает signed cert если username verified, 404 иначе. Альтернатива: relay в `deliver`-сообщении прикладывает signed cert если знает (но это metadata leak).

4. **Revocation** — короткий TTL (90 дней) + manual re-issue после re-review. Если что-то срочно — emergency revocation list endpoint `GET /revocations.json` который клиент опрашивает раз в 24 часа.

5. **Manual review workflow** — нужна **admin-веб для нас** (`admin.phntm.pro`, требует Willen master key для аутентификации, например hardware key через WebAuthn). Минимум: список запросов на верификацию (с pubkey + display info + ссылками на соцсети как доказательство), кнопки Approve / Reject / Request more info. Это отдельный sidecar-сервис (~1-2 недели работы).

**Сложность:** L (~2 месяца — directory + admin-web + клиентская интеграция + правовая политика)
**Зависимости:** H1 (directory-сервис), F2.2 (reproducible builds)

---

### H3. Group encryption protocol — какой выбрать

**Текущее состояние:** Sender Keys реализованы на ~70%. Имеется:
- `SenderKey.kt` — chain-key ratchet + Ed25519 signing pub broadcast
- `DefaultGroupMessagingService.kt:118-412` — encrypt/decrypt round-trips, key distribution на invite
- `SenderKeyStore.sq` — persistence chain keys per (group_id, member_pubkey)

Имеются **критические недоработки:**
- `signingPrivHex=""` для remote keys → signature verification на incoming не enforced (D2.2)
- Control messages не wrapped в Double Ratchet (D2.1)
- `handleLeave` удаляет ВСЕ group SenderKeys вместо proper rotation

**Рекомендация: оставить Sender Keys, доработать.**

Аргументация:
- **MLS (RFC 9420)** — правильная криптография, но требует Group Key Service (отдельный сервис, persistent state of group epoch). Усложняет архитектуру относительно single-relay design. Excessive для масштабов 1000-100K. Реализация ~6-12 месяцев.
- **Pairwise (N копий каждому)** — не масштабируется к 1000 (каждое сообщение = 1000 ratchet encrypt операций + 1000 envelope-ов на relay).
- **Custom** — сразу нет, не reinventing.
- **Sender Keys** — хорошо понятный, реализован Signal в production для миллиардов сообщений, и у нас уже 70% есть. Forward secrecy слабее MLS (компрометация member-а раскрывает все его прошлые сообщения), но это известный trade-off, документируется в Threat Model.

**Что доделать (Sender Keys → production):**
1. **Сигнатуры:** signing private key keep локально, broadcast только public. Каждое group-сообщение signed by sender. Клиент проверяет подпись против broadcasted public.
2. **Control message wrap:** все non-text payloads (group invite, sender key distribution, member add, member leave) wrap в Double Ratchet до отправки. Уже есть pairwise ratchet с каждым членом — используем.
3. **Key rotation на leave:** при `member-leave` (или kick) каждый remaining member генерирует новый sender chain key, distributes остальным remaining через pairwise ratchet. Это предотвращает чтение бывшим членом будущих сообщений.
4. **Skipped message keys:** добавить буфер на Group Sender Keys аналогично Double Ratchet.

**Сложность:** M (~1-1.5 месяца) — большая часть кода уже есть.

---

### H4. Premium feature gating

**Рекомендация: hybrid.**

**Client-side checks для UX:**
- Кнопки выглядят активными/disabled в зависимости от tier
- "Upgrade to Pro" CTA вместо отказа без объяснения
- Локальная валидация перед отправкой запроса на server

**Server-side validation для всего что трогает relay:**
- Создание группы >X членов (где X зависит от tier)
- Channel post >Y subscribers
- Voice messages >Z секунд
- File sizes >N МБ
- Storage retention >M дней

**Mechanism:**
- Stripe → webhook → relay's billing-service → user-tier table (`identity_pubkey → tier`).
- Каждый WS connect: relay смотрит tier из таблицы, embedded в connection state.
- На каждый action relay проверяет ограничения tier'а.

**Trust model:**
- Клиент NOT trusted для server-validated actions. Patched APK не сможет создать группу >10 в Free.
- Клиент trusted для cosmetic (themes, larger local cache, ext'd disappearing) — patched APK может включить, но это не affects других пользователей.

**Cryptographic proof of subscription** — для cosmetic features можно сделать так: server подписывает (tier, expires_at) Ed25519, клиент держит этот signed token. Это позволяет offline проверку («у меня Pro до 2026-12-01»). Применимо для themes, custom emoji, etc.

**Сложность:** M (~1-1.5 месяца) — Stripe webhook + tier table + проверки на ~20 endpoint'ах.

---

### H5. Push notifications

**Рекомендация: hybrid по платформе и tier.**

**Android Free:** Unified Push (FOSS, без Google identifier).
- ✅ Совпадает с privacy-positioning «zero Google services»
- ✅ Уже есть FOSS implementation в Element/Tusky
- ⚠️ Требует distributor-app у пользователя (NTFY app или Element-распределитель)
- ⚠️ Если пользователь не установил распределитель — падает на foreground-service polling (текущий подход) → ISSUE-001 проблема с OEM

**Android Plus/Pro (опт-ин):** возможность включить FCM как fallback.
- ✅ Работает на любом Android из коробки
- ✅ Лучший battery / latency
- ⚠️ FCM token = trackable Google identifier
- Документировать в Privacy Policy: «Plus/Pro может опционально использовать FCM, который связывает ваш телефон с Google. Free всегда без FCM.»

**iOS:** APNs обязательно. Нет альтернатив для backgrounding на iOS. Это **product trade-off** — iOS пользователи получают Apple-tracker неизбежно. Документировать.

**Custom WS keepalive (текущее) — оставить как fallback** для пользователей которые не хотят ни UnifiedPush, ни FCM (Ghost mode).

**Что доделать:**
1. UnifiedPush integration (~3 недели — UnifiedPush gradle dep + endpoint registration → relay + relay-side dispatch)
2. FCM wire (existing scaffolding, ~1 неделя — relay `/register-fcm` + dispatch)
3. APNs (~3 недели — Apple Developer enrollment, certificate generation, relay-side APNs dispatch)
4. Settings UI: "Push delivery: UnifiedPush (recommended) / Google Play Services / Off"

**Сложность:** M (~1.5 месяца суммарно для Android; iOS — отдельно после H4 iOS general)

---

## I. БУДУЩИЕ ФИЧИ — SCOPING

### I1. Communication essentials

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I1.1 | Replies & quoted messages | S | — | Лёгкое после chat layer. MessagePayload расширяется полем `replyToId`, UI бабла рисует quoted-block. ~1 неделя. |
| I1.2 | Reactions (6 standard emoji) | S | Reaction.sq уже существует | Schema есть, нужно UI — long-press menu + render счётчиков. ~1 неделя. |
| I1.3 | Edit messages (24h window) | M | A1.2 | Требует new MessagePayload type "edit" + DB updates + UI "edited" badge. Безопасностный нюанс: edit меняет ratchet-encrypted content; нужно дизайнить как control-message с reference на original. ~3 недели. |
| I1.4 | Delete messages (for me / for everyone) | S | — | "for me" — `Message.sq DELETE`. "for everyone" — control-message TYPE_MESSAGE_DELETE peer side handles. ~1 неделя. |
| I1.5 | Forward messages (с указанием источника) | S | — | "forward" — это просто send нового сообщения с pre-filled text + attribution. ~1 неделя. |
| I1.6 | Drafts sync между устройствами (Premium) | XL | Multi-device | Требует full multi-device E2E sync infrastructure. **Не делать до multi-device решения.** |

### I2. Privacy power-features

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I2.1 | Read & burn (5s auto-delete) | S | E2.3 disappearing | Расширение per-message timer (E2.2). UI: long-press → "Send as Read & Burn". ~1 неделя. |
| I2.2 | Per-chat lock (отдельный biometric) | M | E3.3 | Schema migration `Conversation.sq` + per-chat ID + BiometricPrompt при открытии chat. ~3 недели. |
| I2.3 | Screenshot detection с notification отправителю | M | E4.2 | Требует E4.1 FLAG_SECURE сначала, потом ContentObserver на MediaStore + new MessagePayload type "screenshot_taken" + UI. ~3 недели. |
| I2.4 | Duress password (фейковый аккаунт) | L | — | Требует second SQLCipher database с decoy data + alternative passphrase. UX очень тонкий — если duress unlock detected, fake-БД активна но real-БД защищена. ~6 недель. |
| I2.5 | Anti-forensics RAM-only mode (Pro) | XL | Refactor storage | **Огромная работа.** Текущий код полагается на SQLDelight persistence везде. Опт-ин режим без БД требует in-memory ConversationRepository, MessageRepository, RatchetStateRepository. ~3-4 месяца. |
| I2.6 | Trusted devices визуальный approval | L | Multi-device | Зависит от решения по multi-device. ~6 недель после того. |

### I3. Productivity

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I3.1 | Pinned messages внутри чата | S | Message.sq имеет `pinned` column | Schema есть. UI: long-press → "Pin", header в ChatScreen с pinned message. ~1 неделя. |
| I3.2 | Saved messages / Personal notes | DONE | — | Уже есть `screens/saved/SavedMessagesScreen.kt` (511 LOC). |
| I3.3 | Scheduled messages (отправить через X часов) | M | Reliable background scheduler | Требует WorkManager (уже нужен для E2.3). Schema migration для `scheduled_at_ms`. UI: long-press send button → "Send later". ~3 недели. |
| I3.4 | Quick replies / templates (Pro) | S | — | SharedPreferences с template list + chip-row над input. ~1 неделя. |
| I3.5 | Multiple personas (до 5, Pro) | XL | — | **Радикальная архитектура** — multi-identity AppContainer, switch UI, отдельные DB per persona, или partition внутри одной DB. Cryptographic linking. ~3+ месяца. **Серьёзно подумать стоит ли.** |

### I4. Voice / video advanced

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I4.1 | Voice notes 48kHz stereo (Plus) | S | D5.2 fix to Opus | Mediaecorder OUTPUT_FORMAT.OGG + AudioEncoder.OPUS на API 29+. ~1 неделя. |
| I4.2 | Real waveform visualization | S | — | `MediaRecorder.getMaxAmplitude()` сэмплинг во время записи + render bars. ~1 неделя. |
| I4.3 | Voice transcription on-device (Whisper.cpp, Plus) | L | Native binding | Whisper.cpp Android — есть jni wrappers. Модель ~150MB. ~6 недель + ~2 недели на iOS port. |
| I4.4 | Playback speed control 0.5x/1x/1.5x/2x | S | — | MediaPlayer.setPlaybackParams. ~3 дня. |
| I4.5 | Video circles (как Telegram, до 60s) | M | Camera2 + recording | ~3 недели Android. iOS отдельно. |
| I4.6 | Group calls до 8 (Plus) / до 50 (Pro) | XL | SFU service | Требует SFU (mediasoup или Janus) deploy + signaling протокол. ~3-6 месяцев. |
| I4.7 | Screen sharing | M | — | После video calls. ~3 недели. |
| I4.8 | Push-to-talk walkie-talkie | M | После group calls | Особый mode внутри group call. ~3 недели. |

### I5. Files & media

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I5.1 | File preview без full download | M | Attachment server | Thumbnail extraction + range-request download. ~3 недели. |
| I5.2 | Encrypted cloud storage (50GB Plus / 500GB Pro) | XL | Attachment server + billing | Большая инфра. S3-compatible storage (MinIO self-hosted или R2). Per-tier quota tracking. ~3-4 месяца. |
| I5.3 | Photo editor (crop, blur faces) | M | — | Crop легко (~1 неделя). Blur faces — ML Kit Face Detection + blur effect (~3 недели). |

### I6. Groups & channels advanced

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I6.1 | Group polls / quizzes (anonymous) | M | — | New MessagePayload "poll" + "vote" types. Anonymous voting через homomorphic add (sum encrypted votes) — сложнее. Без anonymous — ~3 недели. С anonymous — L. |
| I6.2 | Live location sharing (15min/1h/8h, opt-in) | M | — | New TYPE_LOCATION с TTL. UI с map (потребует Google Maps SDK или Mapbox). Privacy: not Google → Mapbox или OpenStreetMap. ~3 недели. |
| I6.3 | Anonymous channels (Pro) | L | — | Сообщения подписаны channel-key, не индивидуальным author-key. Контракт: only admin can post, audit log для самого admin. ~6 недель. |
| I6.4 | Comments on channel posts | M | D2.3 channels | Threaded reply ниже channel post. ~3 недели. |

### I7. Network / mesh

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I7.1 | Mesh hop routing | XL | BLE + WiFi Direct + protocol design | Огромная work. Реальные products с mesh: Briar (1.5 года work), Bridgefy. **Не до Beta.** |
| I7.2 | Pluggable transports (obfs4, Snowflake, domain fronting) | L | — | obfs4 binding для KMP — нет готового. Snowflake — Tor's, есть as separate process. Domain fronting — простой, нужен только sslDomain параметр. ~6 недель для domain fronting; obfs4/Snowflake — L каждое. |
| I7.3 | Tor onion service relay | M | Tor binary | Завернуть relay в Tor hidden service. ~3 недели Tor + onboarding flow. |
| I7.4 | SMS fallback gateway (Premium, требует phone) | XL | — | **Скип.** Полностью противоречит "no phone number" positioning. |

### I8. Self-hosted

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I8.1 | Self-hosted relay в коробке | S | docker-compose уже работает | Очистить deploy/ + docs/SELF_HOSTING.md + simple .env template. ~1 неделя. |
| I8.2 | Web admin для self-hosted relay | M | — | Простая web-UI на server показывающая connected clients, message rate, blocklist management. ~3 недели. |
| I8.3 | Federation | OUT | OUT | Уже решено — нет federation. |

### I9. Bots & automation

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I9.1 | Webhook-based bots (Pro) | M | — | New "bot" identity type, auth tokens, webhook endpoints на relay. ~6 недель. |
| I9.2 | API для интеграций (Pro) | M | I9.1 | REST/GraphQL API на directory-сервисе. ~6 недель. |
| I9.3 | Slash commands в чатах | S | I9.1 | Parse `/command args` в input bar, отправка специального TYPE_COMMAND payload. ~1 неделя. |
| I9.4 | Bot directory (приватный список) | S | I9.1 | Простой list endpoint без centralized registry. Пользователь сам шарит ссылку на бота. ~2 недели. |

### I10. Accessibility

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I10.1 | Screen reader support (TalkBack/VoiceOver) | M | — | Audit всех Composable для contentDescription. Большая дисциплина. ~3-6 недель в зависимости от тщательности. |
| I10.2 | Dynamic font sizes | S | C3.1 design tokens | После typography token system. ~1 неделя. |
| I10.3 | High contrast mode | S | C3.2 light/dark expansion | Дополнительная color scheme. ~1 неделя. |
| I10.4 | Voice control / dictation | NONE | OS-provided | OS уже даёт. Просто не блокировать. |

### I11. Ecosystem (long-term)

| ID | Feature | Сложность | Зависимости | Рекомендация |
|---|---|---|---|---|
| I11.1 | Mini apps inside chat | XL | — | **Скип.** Bloat, не aligned с privacy. Telegram-style app store создаёт surface для трекеров. |
| I11.2 | PHANTOM ID для third-party login | L | — | **Скип.** OAuth pattern сразу нарушает «no metadata». Возможно через ZK-proofs in 5+ лет, но не сейчас. |
| I11.3 | Open API & SDK для third-party clients | M | I9.2 | Хорошо для positioning. Документация + публикация TypeScript / Kotlin / Swift SDK. ~6 недель. |
| I11.4 | Bridges to Matrix / XMPP | L | — | **Низкий приоритет.** Federation explicitly out of scope. Bridge нарушает E2EE гарантии. |

---

## J. ВЫВОДЫ И РЕКОМЕНДАЦИИ

### J1. Что технически САМОЕ сложное

**Топ-5 архитектурных блокеров:**

1. **F13/F14/F15 — identity-as-ratchet-key.** Это **корневой security issue**. Сейчас identity X25519 ключ переиспользуется как ratchet DH-ключ. Любой компромисс ratchet state даёт permanent impersonation. Фикс требует:
   - Раздельные identity/prekey/ratchet ключи
   - Prekey-сервер (publish signed prekeys, fetch one-time prekey on first contact)
   - Migration plan для существующих сессий (force re-handshake or lazy migration)
   - **Это блокер перед Kickstarter** — нельзя делать публичный краудфандинг с обещанием «Signal-grade security» с этой дырой.
   - Сложность: L (~2-3 месяца)

2. **iOS parity** через KMP XCFramework. Сейчас iOS — полный stub. Чтобы догнать Android:
   - Build XCFramework на macOS (требует macOS-builder)
   - Активировать iosTargets во всех 16 KMP-модулях
   - Перевести 6 SwiftUI экранов на работу через KMP shared logic
   - libsodium iOS pod
   - SQLCipher iOS pod (стоит денег для commercial license — ~$500/year)
   - Apple Developer enrollment, App Store flow
   - Сложность: XL (~4-6 месяцев соло)

3. **Mesh routing / Nearby.** Bluetooth-mesh — это год работы. Briar делал это 1.5 года. Battery management, NAT through ad-hoc networks, store-and-forward на устройствах — каждое требует отдельной работы. Если делать — это отдельный проект.

4. **Multi-device sync с E2EE.** Signal делает re-keying при добавлении нового устройства. PHANTOM сейчас — single-device. Чтобы добавить второе устройство user'а, надо:
   - Cross-device verification flow
   - Re-encrypt все active conversations для new device
   - Syncing read state, drafts, settings
   - QR-based pairing (как WhatsApp Web, только E2E)
   - Сложность: L-XL (~3-6 месяцев)

5. **Anti-forensics RAM-only mode (I2.5).** Текущий код полагается на SQLite везде. Сделать opt-in режим без persistence — это refactor 5 модулей минимум. Нужно in-memory ConversationRepository, MessageRepository, RatchetStateRepository. Я бы это переоценил — может быть proper compromise = "ephemeral conversations" внутри обычного flow (DELETE after session).

### J2. Quick wins (1-2 недели каждый)

10 фич, которые можно реализовать быстро и которые сразу заметно улучшат продукт:

1. **FLAG_SECURE на всех Activity** (E4.1) — 1 день. Скриншоты + recents thumbnail blocked. Убирает важный privacy gap.
2. **Read receipts toggle** (A3.6) — global + per-chat. SharedPreferences flag, проверка перед `sendReadReceipt`. ~3 дня.
3. **Per-chat mute** (D8.4) — `muted` column в Conversation.sq + UI checkbox. ~3 дня.
4. **Auto-lock timer choices** (E3.4) — Settings dropdown "Immediately / 1m / 5m / 1h" + persistence. ~2 дня.
5. **Voice format Opus instead of AMR_NB** (D5.2) — `MediaRecorder.OutputFormat.OGG + AudioEncoder.OPUS`. Звук резко лучше. ~3 дня.
6. **Real waveform visualization** (D5.4) — `MediaRecorder.getMaxAmplitude()` сэмплинг, render bars. ~5 дней.
7. **Playback speed control** (I4.4) — `MediaPlayer.setPlaybackParams(PlaybackParams(speed=1.5f))`. ~2 дня.
8. **Reactions UI** (I1.2) — schema есть (`Reaction.sq`), нужно только long-press menu + render. ~1 неделя.
9. **Replies / quoted messages** (I1.1) — `replyToId` field + UI quoted-block. ~1 неделя.
10. **Pinned messages** (I3.1) — `pinned` column есть, нужно header в ChatScreen + long-press. ~1 неделя.

**Bonus (технический долг):** versionName "0.0.1-alpha" → "0.1.0-alpha.2" в `apps/android/build.gradle.kts:91` (1 минута).

### J3. Архитектурные решения которые надо принять СЕЙЧАС

**Не делать новые фичи пока не решены:**

1. **F13/F14/F15 — identity rotation.** Каждая новая фича компаундит долг. Если ввести group polls / channels / verified badges поверх broken identity — надо будет потом всё мигрировать. **Делать первым.**

2. **Username uniqueness стратегия (H1).** Если выпустить Plus tier с обещанием `@username` без unique-ness — пользователи купят, потом обнаружат что @vasya есть у трёх человек. **Решить ADR до Plus launch.**

3. **Group encryption: signing keys + control message wrap.** Сейчас Sender Key signing — best-effort (D2.2). До production-promote групп — починить. ~1 месяц work, но критично для security promise.

4. **iOS strategy: SwiftUI native vs Compose Multiplatform.** Я бы рекомендовал native SwiftUI + shared logic через KMP/XCFramework. Это:
   - Лучше performance + iOS feel
   - Не блокирует iOS на состоянии Compose Multiplatform 1.7 (которое ещё имеет issues)
   - Дублирует UI work, но iOS UI всё равно требует platform-specific (Sheets, Action Sheets, NavigationStack)
   - Текущий выбор Swift native — правильный, нужно подтвердить ADR.

5. **Push notifications hybrid** (H5) — определить tier-mapping и UX до того как iOS вообще запустится.

6. **Attachment server** — voice + files + images требуют separate transport. Без этого Bug J / ISSUE-001 для voice не решается, и files/images вообще не реализуемы. ~2 месяца отдельной работы.

7. **Relay persistence layer** (F1.4). Сейчас container restart = потеря всего недоставленного. До масштабирования — нужно sled или Postgres.

### J4. Если бы я был техлидом — что делал бы первым

**6-месячный план (Май–Октябрь 2026):**

- **Май (Month 1):** Stabilize + fix architectural blocker.
  - Sprint 1 (1-2 нед): NLnet submission. 10 quick wins из J2 параллельно.
  - Sprint 2 (3-4 нед): F13/F14/F15 — identity rotation refactor, prekey-сервер minimal.

- **Июнь (Month 2):** Attachment server + voice migration.
  - MinIO self-hosted на VPS
  - Client upload/download flow с E2E
  - Voice использует attachment server вместо inline base64 (решает Bug J)

- **Июль (Month 3):** Username service + verification authority.
  - Directory-сервис (`directory.phntm.pro`)
  - Reservation list, race-condition handling
  - Verification certificate format + admin web for review
  - Verified badge in chat list (B5.5)

- **Август (Month 4):** Group encryption hardening + Alpha 2 release.
  - Sender Key signing keys properly distributed
  - Control message Double-Ratchet wrap
  - Member-leave key rotation
  - Group polls, live location, simple admin roles
  - Alpha 2 release on GitHub

- **Сентябрь (Month 5):** iOS parity start.
  - macOS builder setup
  - KMP XCFramework activation для всех 16 модулей
  - libsodium iOS, SQLCipher iOS
  - Replace SwiftUI views to use KMP shared logic
  - First iOS Alpha (registration → 1:1 chat)

- **Октябрь (Month 6):** Premium tier launch.
  - Stripe integration
  - Subscription state + feature gating (server-side)
  - Recovery phrase (BIP-39) for Plus
  - Push notifications hybrid (UnifiedPush + FCM opt-in + APNs)
  - Kickstarter prep

**После 6 месяцев — продуктовое решение:** Beta-launch с активной promotion vs. continued private alpha с invitation-only.

### J5. Что НЕ делать никогда

**Откидываю сразу:**

1. **I7.4 SMS fallback** — несовместимо с positioning. Phone number = trackable identifier.
2. **I11.1 Mini apps inside chat** — bloat surface, потенциальные tracker exploits, не aligned с privacy.
3. **I11.2 PHANTOM ID для third-party login** — OAuth pattern сразу нарушает «no metadata».
4. **I11.4 Bridges to Matrix/XMPP** — low priority, federation explicitly out of scope, bridge нарушает E2EE гарантии.
5. **Federation в любом виде** — уже решено.
6. **FCM как default Android Free** — нарушает privacy posture.

**Сильно поспорил бы:**

7. **I3.5 Multiple personas** (до 5, Pro). Архитектурно дорого, есть workaround «несколько устройств с разными identity». Если делать — opt-in for Pro only, не для broad usage.
8. **I2.5 Anti-forensics RAM-only mode** — высокая стоимость, узкая аудитория. Лучше: «ephemeral conversations» (auto-delete после session close) внутри normal flow.

### J6. Чего не хватает в этом списке

**Критические product features которые я бы добавил:**

1. **Account migration / device handoff.** Сейчас "lose device = lose account" (ToS пункт 4). Это **большой UX блокер для retention** — пользователь купил Plus, разбил телефон, новый телефон = заново регистрироваться, история потеряна. Решение: encrypted backup blob (locally encrypted with seed phrase) → user uploads на cloud (любой — Dropbox/iCloud/self-host) → restore by entering seed phrase. Сложность: L. **Перед Premium launch обязательно.**

2. **Multi-device** (упомянуто в I-секции через drafts sync, но это бОльшая фича). Phone + laptop одновременно. Signal-style cross-device E2E. Сложность: XL. **Critical для productivity tier.**

3. **End-to-end metadata report.** Settings → "Privacy → My Data" → отображает: «PHANTOM знает X bytes о тебе, в том числе [список]. Relay видит [список metadata]». Builds trust, плюс готовит к GDPR DSAR. Сложность: S. **Quick win.**

4. **Audit / transparency reports.** Annual: «PHANTOM получил N запросов от law enforcement, выдали данные в M случаях, в P случаях ответили "у нас нет таких данных"». Signal-style. Сложность: S (template + commitment to publish). **Critical для credibility.**

5. **Onboarding tutorial.** Объясняющий что такое safety numbers, message requests, why no phone number, what to do if you lose phone. UX education layer. Сложность: M. **High value for mass-market positioning.**

6. **Legal compliance flow:** GDPR data export endpoint (`Settings → Export my data` → encrypted ZIP), Right to be Forgotten flow (`Delete my account → delete from relay`). Сложность: M. **Required для EU launch.**

7. **Incident response runbook.** Что делать если relay seized? Документировано в Threat Model, но не runbook'ом. Сложность: S (документ).

8. **Sustainability / ops:**
   - Status page (`status.phntm.pro`): uptime monitoring, incident log
   - Public SBOM на GitHub Releases
   - Security disclosure policy already есть в SECURITY.md, нужен annual review
   - Donation page (Open Collective?) для open-source funding

9. **Threat model visualizer** в app: Settings → "What does PHANTOM protect against?" → graphical representation of threat model. Education + trust. Сложность: M.

10. **Sign-up scrutiny:** anti-bot measures на directory-сервисе (PoW captcha, не Google reCAPTCHA). Сложность: S. **Critical если username-system запущен.**

---

## Appendix — Codebase metrics

| Module | LOC | Files |
|---|---|---|
| `shared/core/crypto` | ~1500 | 18 |
| `shared/core/messaging` | ~3000 | 16 |
| `shared/core/transport` | ~2200 | 16 |
| `shared/core/storage` | ~1500 + 8 .sq | 54 |
| `shared/core/identity` | ~700 | 9 |
| `apps/android/src/androidMain` | ~13 357 | 40 (Kotlin) |
| `services/relay/src` | ~1007 | 7 (Rust) |
| `legal/` | ~700 | 13 |
| `docs/` | — | 28 |
| **Total Kotlin shared** | **~8 268** | — |
| **Total Android app** | **~13 357** | — |
| **Total Rust relay** | **~1 007** | — |
| **Tracked files (post-cleanup)** | **408** | — |

**SQLDelight schemas:** Conversation, Group, GroupMember, Identity, Message, RatchetState, Reaction, SenderKeyStore (8 файлов).

**Test count:** 60 (44 commonTest + 16 androidInstrumentedTest). Rust: 0. iOS: 0. UI: 0.

**TODO/FIXME/HACK count:** 10 (8 Kotlin, 2 Rust).

---

**Конец отчёта.**

Основной takeaway: **функционально Alpha 1 на удивление полный** для соло-разработчика — Double Ratchet + Sealed Sender + groups+channels (70%) + voice+calls+disappearing+app-lock+QR+safety numbers всё реально работает. **Архитектурно** есть 5 блокеров (F13/F14/F15, iOS parity, mesh, multi-device, anti-forensics), из которых F13/F14/F15 нужно решить **до Kickstarter**, остальные можно постепенно. Есть много **quick wins** (10+ штук по 1-2 недели), которые сразу заметно поднимут качество для пользователя.

Premium / Stripe / subscription / push / username uniqueness — всё это **NONE сейчас**. Это означает что для запуска Plus tier потребуется полностью построить эту инфраструктуру (~2-3 месяца).

iOS — это отдельный проект (~4-6 месяцев на parity).
