# Technical Debt Log

Running list of technical debt items discovered during development — what was found, why it happened, how it was resolved.

---

## 2026-04-24 — Pre-existing test stubs and lint warnings

**Discovered during:** Этап 2 (critical security fixes) smoke test. When the full verification suite was run for the first time in many sessions, four pre-existing issues surfaced. None were caused by Этап 2 — all had accumulated over prior work because `./gradlew allTests` and `lintDebug` had not been part of the loop.

### Bug A — `FakeMessageRepository` missing interface methods

**Files:**
- `shared/core/storage/src/commonTest/kotlin/phantom/core/storage/InMemoryRepositoryTest.kt`
- `shared/core/messaging/src/commonTest/kotlin/phantom/core/messaging/DefaultMessagingServiceTest.kt`

**Symptom:** `Class 'FakeMessageRepository' is not abstract and does not implement abstract members`

**Cause:** `MessageRepository` interface grew (Alpha-1 added `saveMessage`/`unsaveMessage`/`getSavedMessages` for pinned/saved feature; earlier work added `setExpiresAt`/`getNextExpiry`/`deleteExpiredMessages` for disappearing messages and `pinMessage`/`getPinnedMessages` for pinning). Fakes in test files were not updated alongside the interface.

**Fix:** Added all missing method stubs to both fakes. The `InMemoryRepositoryTest.kt` version was particularly out-of-date — it was missing 10 methods.

**Prevention:** Run `./gradlew allTests` as part of every feature that changes a repository interface. Treat test stubs as first-class part of the contract.

---

### Bug B — Smart cast impossible on `expiresAtMs` across modules

**File:** `shared/core/messaging/src/commonTest/kotlin/phantom/core/messaging/DefaultMessagingServiceTest.kt:67`

**Symptom:** `Smart cast to 'Long' is impossible, because 'expiresAtMs' is a public API property declared in different module.`

**Cause:** Kotlin's smart-cast rules tightened across module boundaries — `it.expiresAtMs != null && it.expiresAtMs <= now` no longer compiles when the property crosses a module (race-free guarantee cannot be proved).

**Fix:** Replaced with safe-call idiom:
```kotlin
messages.removeAll { msg -> msg.expiresAtMs?.let { it <= now } == true }
```

**Prevention:** Prefer `?.let { ... } == true` for nullable property chains that cross module boundaries. Enforce via Kotlin compiler progressive mode if available.

---

### Bug C — `Byte.xor` type mismatch after `kotlin-bignum` upgrade

**File:** `shared/core/crypto/src/commonTest/kotlin/phantom/core/crypto/LibsodiumDoubleRatchetTest.kt:133`

**Symptom:** `Argument type mismatch: actual type is 'BigInteger', but 'Byte' was expected.`

**Cause:** An `xor` extension function from `com.ionspin.kotlin.bignum` became visible through transitive imports and took precedence over the stdlib `Byte.xor(Byte)` overload, making `it[0].xor(0xFF.toByte())` resolve to the BigInteger version.

**Fix:** Rewrote the call to go through `Int` explicitly, which avoids the ambiguity:
```kotlin
it[0] = (it[0].toInt() xor 0xFF).toByte()
```

**Prevention:** Avoid wildcard star imports in crypto test files; prefer stdlib infix operators over method-call form when working with primitives.

---

### Bug D — Lint error: `enforceNavigationBarContrast` requires API 29

**File:** `apps/android/src/androidMain/res/values/themes.xml`

**Symptom:** `Error: android:enforceNavigationBarContrast requires API level 29 (current min is 26) [NewApi]`

**Cause:** The attributes `android:enforceNavigationBarContrast` and `android:enforceStatusBarContrast` were introduced in API 29. They were declared in the default `values/themes.xml` while `minSdk=26`, so devices on API 26-28 would encounter unknown attributes (silently ignored at runtime, but caught by lint).

**Fix:** Split the theme into two files:
- `values/themes.xml` — base theme for API 26+ without the two attributes.
- `values-v29/themes.xml` — override that adds the contrast-enforcement attributes on API 29+.

**Prevention:** When adding a theme attribute, always check its `@RequiresApi` level against `minSdk`. If higher, place it in a `values-vXX/` resource qualifier folder.

---

### Bug E — `autoVerify="true"` on custom `phantom://` scheme

**File:** `apps/android/src/androidMain/AndroidManifest.xml:33`

**Symptom:** `Error: http(s) scheme is missing, but is required for Android App Links [AppLinkUrlError]`

**Cause:** Введено в Этапе 2.6 — когда добавился второй intent-filter для `https://phntm.pro/invite/` (Universal Link), `autoVerify="true"` остался и на первом, custom-scheme `phantom://` filter. Android App Links валидация работает только для http/https.

**Fix:** Убрать `autoVerify="true"` с `phantom://` intent-filter. Оставить только на https-версии — Digital Asset Links verification работает через `/.well-known/assetlinks.json` на phntm.pro (будет задеплоен в Этапе 4.1).

**Prevention:** `autoVerify` применяется только к http(s) схемам. Custom URI схемы просто открываются без верификации.

---

### Bug F — `UnsafeOptInUsageError` на `ImageProxy.image`

**File:** `apps/android/src/androidMain/kotlin/phantom/android/qr/QrScanScreen.kt:133`

**Symptom:** `This declaration is opt-in and its usage should be marked with @androidx.camera.core.ExperimentalGetImage`

**Cause:** CameraX поднял `ImageProxy.getImage()` до `@ExperimentalGetImage` в обновлении библиотеки. Пропустили опт-ин при миграции.

**Fix:** Точечный `@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)` прямо на `val mediaImage = imageProxy.image`. Не пропагируется вверх по стеку — ограничено точкой использования.

**Prevention:** При обновлении CameraX проверять `@ExperimentalGetImage` и `@ExperimentalCamera2Interop` в release notes.

---

### Bug G — `PermissionImpliesUnsupportedChromeOsHardware` для CAMERA

**File:** `apps/android/src/androidMain/AndroidManifest.xml:8`

**Symptom:** `Permission exists without corresponding hardware <uses-feature android:name="android.hardware.camera" android:required="false"> tag`

**Cause:** Без `uses-feature android:required="false"` Play Store фильтрует приложение для Chromebook и планшетов без камеры — даже если камера не обязательна.

**Fix:** Добавлен `<uses-feature android:name="android.hardware.camera" android:required="false" />` рядом с CAMERA permission.

**Prevention:** Любая `hardware` permission должна иметь соответствующий `uses-feature` с корректным `required` флагом (false если функция опциональна).

---

### Bug H — Pre-existing: libsodium JNI не грузится в JVM unit-тестах [ВНЕ СКОУПА]

**Files:** `shared/core/crypto/src/commonTest/**` — все тесты с `LibsodiumInitializer`, `X3DH`, `DoubleRatchet`, `SafetyNumber`, `SealedSender`

**Symptom:** Тесты **компилируются**, но падают в runtime:
```
java.lang.NullPointerException: Cannot invoke "java.net.URL.getFile()" because "url" is null
    at com.goterl.resourceloader.ResourceLoader.getFileFromFileSystem
    at com.ionspin.kotlin.crypto.LibsodiumInitializer.loadLibrary
```

**Cause:** `com.ionspin.kotlin:kotlin-crypto-libsodium-bindings` на JVM использует lazysodium-java, которая ищет нативную библиотеку (`.so`/`.dll`/`.dylib`) через `ResourceLoader`. В Android `testDebugUnitTest` runtime classpath не содержит нативных бинарей, поэтому `URL` резолвится в null.

**Workarounds (рассмотрены):**
1. ~~Добавить `testImplementation("com.goterl:lazysodium-java:5.1.4")` + JNA.~~ Локальный JVM fallback, но тестирует НЕ ту libsodium, что едет пользователям → ложное зелёное.
2. ~~Robolectric + нативные бинари.~~ Добавляет прослойку; снова не тот же рантайм.
3. ✅ **Перенести libsodium-зависимые тесты в `androidInstrumentedTest` (запуск на устройстве/эмуляторе).**

**Решение (2026-04-24):** Вариант 3 — тесты запускаются на реальной Android-машине с той же libsodium-биндингой, что уходит в production. Никаких fake-раннтаймов. Честное E2E тестирование.

**Когда делать:** После VPS-деплоя релея, перед Alpha-2 release window (~1 июня 2026). Причина такого порядка: внешние ревьюеры захотят видеть зелёный CI — инструментированные тесты на Android эмуляторе закрывают и unit-тестирование crypto, и демонстрируют что production-стек проверяется целиком.

**Статус:** ✅ **ЗАКРЫТ 2026-04-24** на ветке `fix/bug-h-libsodium-jni`.

**Что сделано:**
- `gradle/libs.versions.toml`: добавлены `androidx-test-runner 1.6.2` и `androidx-test-ext-junit 1.2.1`.
- `shared/core/crypto/build.gradle.kts`: добавлен `androidInstrumentedTest` sourceSet с AndroidX Test зависимостями; `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` в `android.defaultConfig`.
- Перемещены 4 тестовых класса (16 тестов): `LibsodiumX3DHTest`, `LibsodiumDoubleRatchetTest`, `SafetyNumberTest`, `SealedSenderTest` → `shared/core/crypto/src/androidInstrumentedTest/kotlin/phantom/core/crypto/`. Все аннотированы `@RunWith(AndroidJUnit4::class)`.
- `commonTest` крипто-модуля пуст — больше не провоцирует красное в `./gradlew allTests` на JVM.
- Компиляция: `./gradlew :shared:core:crypto:compileDebugAndroidTestKotlinAndroid` — BUILD SUCCESSFUL.
- `assembleDebug` / `assembleRelease` — без регрессий.

**Запуск на эмуляторе:** `./gradlew :shared:core:crypto:connectedDebugAndroidTest` (требует running AVD).

---

### Bug J — Asymmetric outbound packet loss on Tecno-class OEMs [PROTOCOL/INFRA GAP]

**Symptom:** Tecno Spark 2023 (HiOS) running PHANTOM with the foreground service active, both `WIFI_MODE_FULL_HIGH_PERF` and `PARTIAL` wake locks held, and Battery → Unrestricted set, still loses its outbound channel to the relay within ~30–70 s of each reconnect. Inbound continues to work — incoming text envelopes from the relay arrive normally — but outbound `RelayMessage.Ping` frames stop reaching the relay (no `Pong` round-trips) and any envelope dispatched in that window (notably voice messages, ~70 KB) never gets an ack.

**Diagnosis (2026-04-28):** End-to-end log capture on Tecno-Spark ↔ relay ↔ Pixel-emulator. Same code path on emulator-to-emulator delivers a 75 KB voice envelope in ~1 s; same code path on Tecno never gets a 70 KB envelope through. Both client-side timeout bumps (`OkHttp pingInterval(0)`, `PONG_TIMEOUT_MS = 60 s`, `ACK_TIMEOUT_MS = 60 s`) confirm this is **not** the previous transport-ping bug — extending the timeout window does not help, because the radio is genuinely silent for ≥ 60 s.

**Root cause:** OEM Wi-Fi/cellular power management on Tecno HiOS parks the upstream radio asymmetrically — downstream packets continue to drain into the device but the device's outbound queue does not flush. No client-side configuration can override this: the foreground service, wake locks, and unrestricted-battery whitelist are already in place. This generalises ISSUE-001 from "WS reconnects every 60 s, no message loss" to "WS reconnects every 60 s, **and** large/voice payloads are silently lost" once the affected payload size exceeds what fits in the brief radio-wake window after each reconnect.

**Why text still works:** Text envelopes (~1 KB) flush in milliseconds inside the brief radio-alive window after each reconnect. Voice envelopes (50–100 KB) cannot be uploaded in that window before the radio re-parks.

**Fix path (Alpha 2):**
1. **Unified Push** integration ([ROADMAP.md](../ROADMAP.md)) — server-side push wakes the device when an envelope is queued, opening a fresh radio window each time so the next outbound flush starts immediately. Already on the Alpha 2 plan as the long-term ISSUE-001 fix.
2. **Attachment server** — voice and other large media move to HTTPS POST against a separate endpoint (S3-compatible storage); the WS envelope only carries a URL + decryption key (~few hundred bytes). Voice no longer needs a multi-second uplink burst over WebSocket. This is the right place to land voice messages structurally; inlining base64 over the WS was always a stop-gap.

**Not in scope of Alpha 1.** Documented as a known limitation in [KNOWN_ISSUES.md](../KNOWN_ISSUES.md) ISSUE-001 ("Voice messages on Tecno-class OEMs: NOT delivered").

---

### Bug I — Avatar not transmitted to peer (no ProfileSync envelope) [FEATURE GAP]

**Symptom:** When a user sets a profile photo, it appears on their own device (top-bar, profile screen) but their peer always sees the gradient + initial fallback. There is no protocol message that carries avatars across the wire.

**Scope:** Not a bug in current code — the protocol simply has no `ProfileSync` envelope. Adding it requires:
- New message type (e.g. `MessagePayload.TYPE_PROFILE_SYNC`) carrying compressed avatar bytes (target ≤ 64 KB after JPEG compression at quality 70 and 256×256 resize) plus display name and a monotonically-increasing version counter.
- Send on first 1:1 message after a contact is added, and again whenever the local user changes their photo.
- Receiver stores in a new `contact_profile` SQLite table (avatar bytes + version + last-updated-at), separate from `conversation` (which is identity-keyed routing data).
- Top-bar and contact-row avatars on the recipient side observe the new repo via StateFlow, same pattern as the self-avatar fix in [ProfileScreen.kt:106](apps/android/src/androidMain/kotlin/phantom/android/screens/profile/ProfileScreen.kt#L106).

**When to do it:** Alpha 2 — bundle with the related Alpha 2 backlog items (per-message status indicators, encrypted profile sync). Not before the Alpha-2 release window (~2026-06-01); this is product polish, not a security or correctness blocker.

**Risk note for the security review:** Avatar bytes flow through the existing Double Ratchet, so confidentiality and authenticity are already covered. The metadata exposure is the same as for any other message (relay sees envelope size; peer sees content). No additional trust assumptions.

---

### Bug H-follow-up — `Alpha0IntegrationTest` also uses libsodium on JVM

**File:** `shared/core/messaging/src/commonTest/kotlin/phantom/core/messaging/Alpha0IntegrationTest.kt`

**Symptom (predicted):** Will fail at runtime on `./gradlew :shared:core:messaging:testDebugUnitTest` with the same `ResourceLoader.getFileFromFileSystem` NPE because it performs a real X3DH + Double Ratchet exchange using `LibsodiumInitializer`.

**Scope:** Outside `fix/bug-h-libsodium-jni` — that branch only touches the crypto module to keep the change surgical. The messaging-module integration test needs the same treatment (move to `androidInstrumentedTest` + add AndroidX Test deps + `@RunWith(AndroidJUnit4::class)`).

**When to do it:** After the VPS relay is deployed and before the Alpha-2 release window (~2026-06-01). The relay deploy unblocks end-to-end testing against a real server, at which point the integration test's value is much higher and the migration effort pays off.

---

## How these were missed

- The smoke-test suite (`allTests` + `lintDebug` + `assembleRelease`) had not been run as a block for several feature cycles. Each feature branch focused on `assembleDebug` only.
- Test fakes are often treated as scaffolding rather than contract — when the interface grew, the fakes silently drifted without a failing signal to anyone who only runs `assembleDebug`.
- Cross-module smart-cast tightening and transitive import shadowing only trigger during compilation of the specific test source sets, so they don't appear in main-source compilation.

**Process improvement:** Running `./gradlew allTests lintDebug assembleRelease` is now part of the definition-of-done for each milestone (Этап 2 onward).
