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

**Workarounds (нужно исследовать отдельно):**
1. Явно добавить `testImplementation("com.goterl:lazysodium-java:5.1.4")` и JNA в `shared/core/crypto/build.gradle.kts` — даст локальный fallback через JNA.
2. Или использовать Robolectric + нативные бинари.
3. Или перенести libsodium-зависимые тесты из `commonTest` в `androidInstrumentedTest` (запуск на устройстве/эмуляторе).

**Статус:** Вне скоупа Этапа 2. Компиляция зелёная, build зелёный — runtime failure существовал задолго до текущих изменений. Отдельная задача инфраструктуры.

---

## How these were missed

- The smoke-test suite (`allTests` + `lintDebug` + `assembleRelease`) had not been run as a block for several feature cycles. Each feature branch focused on `assembleDebug` only.
- Test fakes are often treated as scaffolding rather than contract — when the interface grew, the fakes silently drifted without a failing signal to anyone who only runs `assembleDebug`.
- Cross-module smart-cast tightening and transitive import shadowing only trigger during compilation of the specific test source sets, so they don't appear in main-source compilation.

**Process improvement:** Running `./gradlew allTests lintDebug assembleRelease` is now part of the definition-of-done for each milestone (Этап 2 onward).
