# C6-existing-account — Round-0 contract sheet

- **Track:** `C6-existing-account`
- **Status:** Round-0 draft, awaiting architect review-GREEN before any code, Gradle, APK or ADB action.
- **Author:** Claude (agent), 2026-08-13.
- **Predecessor:** [`android-onboarding/`](.) — C6-a sealed-holder plan, C6-b key-preview animation, dual-key-labels track. *See §0 for the audit finding that most of that scaffolding is not present in the master tree.*
- **Successor blockers:** C6-c/d permission toggles + C6-e instrumentation harness — depend on Welcome layout being finalised here.

---

## Executive summary

The operator's ask is a secondary CTA `I already have an account` on the Welcome step that opens a **real restore-identity flow** (backup file or QR-scanned recovery material) — not a classical sign-in placeholder, "Coming soon" banned.

**Source audit finding (§0):** no restore infrastructure exists in the current tree — no backup API, no import path, no versioned QR format, no recovery-phrase code, no `onboarding/v2` package, no sealed onboarding state holder. `ADR-012 "Account migration via seed phrase"` is referenced by other ADRs and by dead UI code but exists only as a draft embedded in `docs/project/ARCHITECTURAL_DECISIONS_TODO.md` — no landed spec, no code module, no format decision.

**Therefore this contract can only specify** the UI shell, navigation, cross-cutting rules (logging discipline, cancel/back semantics, atomic-import CONTRACT independent of format) and acceptance tests — but **cannot specify** the format-dependent parts (payload schema, verification steps, decryption recipe, error taxonomy for a corrupted backup) until architect decides one of:

- **Path A** — land `ADR-012` (or a replacement spec) as a prerequisite, then re-issue this contract as Round-1 with format-specific sections filled in.
- **Path B** — architect explicitly authorises inventing the format inside this track (in which case a security-reviewer subagent + a separate crypto-format contract sheet must land BEFORE any code).
- **Path C** — defer C6-existing-account. Welcome step stays with a single "Get started" CTA. The gap noted in memory `project_android_onboarding_c6_existing_account_gap_2026_08_10` remains open; operator explicitly refuses the "Coming soon" placeholder alternative, so a placeholder ship is NOT an option under path C.

Everything below assumes Path A or Path B; §11 flags each section that becomes moot under Path C.

---

## 0. Source audit — findings that shape this contract

Full raw audit lives in the agent transcript (2026-08-13); load-bearing points:

- **No `screens/onboarding/v2/**` package.** The only onboarding source is a single 1060-line composable `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/OnboardingScreen.kt`. Stale references in project memory (`project_android_onboarding_commit_6a_sealed_holder_2026_08_08`, `project_android_onboarding_final_green_2026_08_11`, dual-key-labels track) describe **plans, not landed code**. This contract therefore designs against the actual `OnboardingScreen.kt` layout, not against a `v2` scaffolding that does not exist.
- **`IntroPager` is a `HorizontalPager(pageCount = { 3 })`** with three steps (Welcome → IdentityKey → PrivacyMode), free-swipeable, state held via `by remember { mutableStateOf(...) }` — NOT `rememberSaveable`, NOT `ViewModel`, NOT `SavedStateHandle`. In-progress onboarding does NOT survive process death today.
- **`WelcomeStep`** (`OnboardingScreen.kt:189-248`) has a single primary CTA `IntroCta(label = "Get started", onClick = onContinue)` (line 244) inside a `Column` with `horizontalAlignment = CenterHorizontally`. Secondary CTA slots naturally immediately after the primary CTA, before the trailing `Spacer(80.dp)`.
- **`IntroCta`** (`OnboardingScreen.kt:749-788`) is the shared full-pill button primitive — 48 dp height, `RoundedCornerShape(50)`, `CyanAccent` background. A secondary CTA MUST look distinct (this contract picks: text-button / ghost variant — see §1.1).
- **No `NavController` / `NavHost`.** Whole app navigation is a sealed `Screen` (`apps/android/src/androidMain/kotlin/phantom/android/navigation/Screen.kt`) driven by `when (currentScreen)` in `MainActivity.PhantomApp` (`MainActivity.kt:396-407`). New screens are added as sealed subclasses + a `when` branch.
- **QR encoder** (`apps/android/src/androidMain/kotlin/phantom/android/qr/QrCodeImage.kt`) uses ZXing, payload byte-exact `"username:hex(x25519_pub)"`, no versioning, no magic prefix.
- **QR decoder** (`apps/android/src/androidMain/kotlin/phantom/android/qr/QrScanScreen.kt`) uses CameraX + ML Kit, delivers raw scanned string to `MainActivity.PhantomApp` via `onScanned: (String) -> Unit`. The scanned string flows into `AddContactDialog.parseContactString` which splits on the FIRST `:` and validates the second half as 64 lowercase hex. Any restore-QR support MUST distinguish restore payload from contact payload BEFORE the contact-dialog code touches it (see §2.3).
- **`IdentityRepository` writers today:** `createIdentity(username)`, `saveIdentity(record)`, `deleteIdentity()`. `SqlDelightIdentityRepository.createIdentity` deliberately throws — key generation is `IdentityManager`'s responsibility. `saveIdentity` maps to a single `INSERT OR REPLACE INTO identity(...)` statement (`Identity.sq:17-27`) — single-statement upsert, but the `KeystoreIdentityRepository` decorator around it does NOT open a transaction, so a mid-decoration crash between AES-GCM encrypt and SQL bind is well-defined only because there's one statement per call. Restore path can piggyback on this property if it writes a full record in one `saveIdentity` call. Signing private key hex is currently **NOT** wrapped by the Keystore decorator — it lands raw in SQLite (`AppContainer.kt:2485-2521`).
- **`IdentityRecord`** (`shared/core/identity/src/commonMain/kotlin/phantom/core/identity/IdentityKey.kt:76-94`) has NO overridden `toString()`. Default data-class `toString()` dumps `dhPrivateKeyHex` + `signingPrivateKeyHex` in cleartext. **Load-bearing latent leak surface** — one accidental `Log.i(tag, "identity=$record")` and both secrets exit via logcat.
- **No `shared/core/backup/` module. No BIP-39, Mnemonic, RecoveryBundle, BackupBlob symbol.** Only `ARCHITECTURAL_DECISIONS_TODO.md:608-711` (ADR-012 draft: BIP-39 24-word → HKDF → AES-GCM blob). Nothing implemented.
- **`ADR-012`** as a file does not exist. Referenced from `ADR-009-identity-prekey-separation.md:76` and `apps/android/src/androidMain/kotlin/phantom/android/ui/UtilitySheets.kt:128`. The `BackupExportSheet` composable in `UtilitySheets.kt:70-139` is dead code (zero call sites) with its primary CTA hardcoded disabled and label `"Available Sep 2026"`.
- **Post-finalize wiring today:** `runOnboarding` (`OnboardingScreen.kt:1044-1059`) calls `identityManager.createOrLoad(username)` → `container.initMessaging(record, dhKeyPair)` → `onComplete()` → `MainActivity.PhantomApp` then `startForegroundService(...)` + `currentScreen = Screen.ChatList`. Any restore path MUST enter `initMessaging` (or `initMessagingFromStorage`) under `initMessagingMutex` (`AppContainer.kt:2412`) — same discipline as the existing Alpha-1 → Alpha-2 `MigrationManager` path.
- **Redaction primitives that exist:** `WsReconnectGate` token → `"[REDACTED]"`; `VerifyKeyState.KeyPresent/Valid` → `KEY_PRESENT_REDACTED_RENDER` / `VALID_REDACTED_RENDER` constants. Pattern is per-type override, no generic `Redacted<T>` wrapper. This contract adopts the same pattern (§6.3).

---

## 1. Product behavior

### 1.1 Welcome step — CTA layout

Welcome step ships with **two CTAs, always visible**:

- **Primary — "Get started"** — unchanged. Full-pill `IntroCta` (48 dp height, `CyanAccent`, `RoundedCornerShape(50)`, `fontWeight = Medium`, `fontSize = 15.sp`). `onClick` advances the pager to page 1 (IdentityKey step) — behavior unchanged.
- **Secondary — "I already have an account"** — NEW. Rendered directly below the primary CTA. Visual variant: full-width ghost / text-button. Height 48 dp (touch-target compliance). Background transparent; label colour `MutedText`; underline OFF; centre-aligned. Ripple ON (default Material ripple).
  - `Role.Button` semantics; `Modifier.semantics { role = Role.Button; contentDescription = ... }`.
  - Touch-target ≥ 48 dp confirmed by Espresso rule (§7.2).
  - Respects bottom system inset (WindowInsets.systemBars) AND `fontScale = 2.0` (label wraps to two lines if needed; container height grows).
  - Bottom padding: the trailing `Spacer(80.dp)` (`OnboardingScreen.kt:247`) becomes `Spacer(32.dp)` when secondary CTA is present, to keep total vertical footprint of the two-CTA block equal to the current one-CTA block.

**Copy — Round-0 draft, awaits copy-review:**

- Primary: `"Get started"` (unchanged).
- Secondary: `"I already have an account"`.

*Note:* if architect greenlights Path A (ADR-012 lands with a specific backup wording, e.g. "recovery phrase" vs "backup file"), the secondary CTA copy may be refined to match the format. Round-1 will pin this.

### 1.2 Tap → restore flow entry

Secondary CTA tap:

1. Records analytics event (see §6.4 for the strict redaction rule on analytics).
2. Navigates via `Screen.RestoreIdentity` (new sealed subclass in `apps/android/src/androidMain/kotlin/phantom/android/navigation/Screen.kt`).
3. The Welcome-side onboarding state (`username`, `privacyMode`, pager page) is NOT touched — see §3.2.

### 1.3 Restore intake screen — source picker

`Screen.RestoreIdentity` mounts `RestoreIdentityScreen` (new composable, path `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/RestoreIdentityScreen.kt`).

Screen contents (top to bottom):

1. Header row: back-arrow (leading) + title `"Restore existing identity"`. Back-arrow behavior: §3.3.
2. Short explanatory body (12–14 sp): `"Restore your identity from a backup file you saved earlier, or from a recovery QR shown on your other device."` — copy pending Round-1 confirmation.
3. Two primary source-picker cards (stacked, `RoundedCornerShape(16.dp)`, `LightSurface` background):
   - **Card A — "From backup file"** — icon (folder or shield-download), label `"Restore from backup file"`, subtitle `"Encrypted backup you exported from your other device"`.
   - **Card B — "From recovery QR"** — icon (QR code), label `"Restore from recovery QR"`, subtitle `"Scan the recovery QR shown in Profile on your other device"`.
4. Bottom text-button `"Cancel"` — returns to Welcome (§3.3).

Both cards route to the same downstream state machine (§2) — the source only differs at the intake step.

**IMPORTANT (Path-A/Path-B decision point):** which of {file only, QR only, both, third-source-unspecified} are supported is a format decision. This contract assumes both are on the table so architect can pick.

### 1.4 Restore progress + outcome

After intake (file selected or QR scanned) the screen transitions to a bounded progress state:

- Progress copy — `"Verifying backup…"` / `"Decrypting identity…"` / `"Restoring conversations…"` (three-step visual indicator; specific labels depend on §2 format-dependent steps).
- Terminal outcomes surface on the SAME screen (mirrors `MigrationScreen`'s error-inline pattern, `MigrationScreen.kt:137-155`, `MigrationScreen.kt:206-221`):
  - **Success** → auto-navigate to `Screen.ChatList` after 500 ms delay (visual acknowledgement of the successful restore before the app-shell mount).
  - **Recoverable failure** — retry button + copy tailored to the failure class (§4).
  - **Terminal failure** — "Start over" button (returns to Welcome, discards intake).

No new modal, no bottom-sheet — same layout scaffolding as `MigrationScreen`.

---

## 2. State machine

### 2.1 High-level restore state (format-independent)

```
RestoreState ::=
    Idle                                 -- intake screen visible, no action
  | AwaitingSourceInput                  -- user picked source, we're waiting for the picker
  | Verifying(source)                    -- payload validation (format-check, magic, version, integrity)
  | Decrypting(payload)                  -- format-dependent: passphrase / device-secret / other
  | ImportPreCheck(record)               -- validate against local state: is there already an identity? username collision?
  | Importing(record)                    -- atomic write (§3.4)
  | InitializingMessaging(record)        -- initMessaging under initMessagingMutex
  | Success(recordSummary)               -- terminal
  | RecoverableFailure(cls, retryable)   -- see §4 taxonomy
  | TerminalFailure(cls)                 -- see §4 taxonomy
```

Transitions: unidirectional forward with `Verifying/Decrypting/ImportPreCheck` allowed to short-circuit into RecoverableFailure or TerminalFailure. `Success` and `TerminalFailure` are absorbing.

`Importing → InitializingMessaging` is the atomicity boundary — after successful `saveIdentity`, `initMessaging` MUST run (or the process must restart clean into `initMessagingFromStorage`). See §3.4.

### 2.2 Format-dependent slots (BLOCKED on Path-A/B decision)

`Verifying(source)` and `Decrypting(payload)` are placeholder states. Their concrete substates (parse → magic-check → version → HMAC → passphrase-derive → AES-GCM open, or whatever the format spec dictates) MUST be filled in by ADR-012 (Path A) or a separate crypto-format contract sheet (Path B). Round-0 pins ONLY:

- Verifying MUST be a pure function of the source bytes (no network, no relay lookup, no `identityManager` call) — deterministic re-verification MUST be possible for the same source without side effects.
- Decrypting MUST reject silently on ANY failure (`RecoverableFailure.PayloadCorrupt` or `RecoverableFailure.PassphraseIncorrect`) — no partial `IdentityRecord` may be constructed if any step fails.
- Passphrase / secret material input UI is out-of-scope for Round-0 (depends on format); this contract MUST accept a `PassphraseSource` seam so tests can inject.

### 2.3 QR intake — distinguishing restore-QR from contact-QR

The current QR scanner (`QrScanScreen.kt`) delivers a raw string to a single `onScanned: (String) -> Unit` callback which routes ALL scanned strings into `AddContactDialog.parseContactString`. That parser splits on the first `:` and validates the second half as 64 hex chars — a raw restore payload (whatever format) MUST NOT match this shape or the scanner would misroute it into contact-add.

Round-0 constraint on the restore-QR payload format:
- **Restore-QR payloads MUST NOT parse as a valid `contact` string** under `AddContactDialog.parseContactString` semantics. Concretely: the second-colon-half MUST NOT be exactly 64 lowercase hex. Simplest guard: the restore-QR payload starts with a magic prefix (e.g. `phantom-restore-v1:...` — actual prefix pinned by ADR-012) so a first-colon split cannot yield a bare 64-hex tail.
- Scanner integration: the `QrScanScreen.onScanned` callback in the RESTORE flow is a DIFFERENT callback (mounted from `RestoreIdentityScreen`, not from `ChatListScreen`). The two callbacks MUST NOT share global mutable state; scanned-value dispatch MUST be scoped to the mounting screen. Current shared `scannedQrValue: String?` in `MainActivity` (`MainActivity.kt:313`) needs to remain unaware of restore scans — restore mounts its own scanner instance whose payload goes directly into the restore state machine.

### 2.4 In-progress cancel

At any state EXCEPT `Importing` and `InitializingMessaging`, cancel/back is safe: the transition is `→ Idle` (or `→ Welcome` if from `Idle`); nothing has been written.

At `Importing` (post-`saveIdentity` starts, pre-return) cancel is refused (grey out cancel button; short-lived state, ~50–200 ms). If the process dies here, next launch relies on `initMessagingFromStorage()` to mop up (§3.5).

At `InitializingMessaging` cancel is refused — same reasoning; the identity is already on disk, aborting messaging init would leave the app in a startable-but-broken state that recovers on next launch anyway. Grey out cancel; wait for terminal.

---

## 3. Persistence

### 3.1 Restore intake state — where it lives

Restore-flow state (source, payload-in-hand, entered passphrase, current substate) is **process-lifetime only**. It is held in a new `RestoreViewModel` (introduce a real `ViewModel` — the wider onboarding-file has none today, but restore justifies the deviation because the flow is longer than a single frame and holds sensitive material). `RestoreViewModel` is scoped to the `Screen.RestoreIdentity` navigation entry.

**Sensitive material discipline** (see §6):
- Passphrase MUST live inside the ViewModel as a `String` that is explicitly overwritten to `""` in `onCleared()` (best-effort — Kotlin `String` is immutable, so the ViewModel-held reference MUST be released; the GC eventually collects; there is no way to guarantee heap wipe, that's a KMP limitation the contract acknowledges).
- Payload bytes MUST live as `ByteArray` and MUST be `Arrays.fill(bytes, 0)`'d in `onCleared()` AND on transition to `Success` / `TerminalFailure` (before the ViewModel is destroyed).
- No `SavedStateHandle` write of passphrase, payload or any decoded key material EVER. `SavedStateHandle` is written to disk by Android — an unrestored device could resurrect the material.

### 3.2 Onboarding-in-progress state — untouched

The Welcome-page state (`username`, `privacyMode`, pager index) is unrelated to restore. When the secondary CTA tap navigates to `Screen.RestoreIdentity`, the outer `OnboardingScreen` composable is left mounted (or reset — see §3.3 for `back`). Either way, restore MUST NOT read or write any onboarding-scope state.

### 3.3 Back / cancel semantics

- `RestoreIdentityScreen` back-arrow (top-left) → returns to `Screen.Onboarding` (Welcome). Onboarding-scope state is intact (unchanged; user can continue new-identity flow with previously-typed username if any — but see next point).
- `Cancel` text-button (bottom of intake screen) → same as back-arrow.
- Hardware back button → same behavior as back-arrow.
- **`Screen.RestoreIdentity` is NOT a subclass of `Screen.Onboarding`** — it's a peer under the top-level navigation `when` in `MainActivity.PhantomApp`. This keeps back handling explicit and prevents accidental nesting.
- If restore progresses past `Verifying` and back is pressed, a confirmation dialog appears: `"Cancel restore? Any progress will be lost."` — Continue / Cancel restore. This dialog is REQUIRED once payload material is in memory (§6.2 secret-material handling — releasing memory promptly is the goal).

### 3.4 Atomic import contract (format-independent)

The `Importing` state MUST run **exactly one** `identityRepo.saveIdentity(record)` call, with `record` fully constructed and validated (all six `IdentityRecord` fields set to their restored values, including both `signingPublicKeyHex` and `signingPrivateKeyHex` non-null and non-empty — restored identities MUST NOT be Alpha-1-shaped).

Atomicity requirements:
- Single `saveIdentity` invocation. No two-phase write. No pre-mutation of any DB row prior to the identity write.
- If `saveIdentity` throws or returns an error, transition to `TerminalFailure.WriteFailed`. Do NOT retry silently — retry is user-driven (§4.2).
- After `saveIdentity` returns success, `_identityState.value` MUST be updated (`AppContainer.kt:772` `MutableStateFlow`) so live observers see the new identity — but this can be done AFTER the messaging init step to avoid a partial "identity present, transport down" observable window.
- Downstream state (conversations, prekeys, ratchet state) that MAY be part of the restored payload is out-of-scope for Round-0 — restoring conversations depends on the format ADR. If the format includes only identity keys, then post-restore the app is in the same state as a fresh install with that identity; peers must re-handshake (same behavior the current `MigrationManager` produces on Alpha-1 → Alpha-2 backfill, `MigrationManager.kt:124-233`).

**KeystoreIdentityRepository extension requirement:** the current decorator (`AppContainer.kt:2485-2521`) wraps only `dhPrivateKeyHex` (X25519) at rest. Round-0 flags that a restored identity MUST have `signingPrivateKeyHex` (Ed25519) also wrapped at rest, either by extending the decorator to cover both fields or by ensuring the crypto-format ADR mandates transport-layer encryption of the whole identity blob such that the raw hex never lands in SQLite. **This is a §4 P0 finding on the CURRENT tree**, not a new requirement — the same gap exists for the fresh-install path today.

### 3.5 Process death during import — recovery semantics

The write is a single SQL statement (`Identity.sq:17-27`, `INSERT OR REPLACE INTO identity`) wrapped by `withContext(Dispatchers.IO)` in `SqlDelightIdentityRepository.saveIdentity` (`SqlDelightIdentityRepository.kt:25-41`). Practical outcomes on mid-import crash:

- Crash before `saveIdentity` completes → next launch sees no identity → onboarding restarts (user must retry restore from scratch).
- Crash between `saveIdentity` return and `initMessaging` completion → next launch sees identity present → `MainActivity.LaunchedEffect(Unit)` (`MainActivity.kt:283-304`) sees `identity != null` → calls `initMessagingFromStorage()` (`AppContainer.kt:2414-2472`) → messaging initialises off the persisted record. **User skips onboarding entirely** and lands in ChatList. This is acceptable: the restore already succeeded (bytes on disk); the messaging init is idempotent recovery.
- Crash during `initMessaging` internals → next launch identity present → `initMessagingFromStorage` re-runs → same recovery.

No custom crash-marker file. No pre-write journal. The single-statement SQL upsert + the existing `initMessagingFromStorage` recovery path together provide sufficient durability without new machinery.

---

## 4. Failure matrix

Failure taxonomy for the restore state machine. Format-independent errors are pinned; format-dependent errors are enumerated as slots.

### 4.1 Recoverable failures (retry within the same session, source unchanged)

| Class | Trigger | Copy pattern | UI action |
|---|---|---|---|
| `SourceUnreadable` | File picker returned empty, or file read errored | "Couldn't open that file. Try again or pick a different file." | Retry button + change-source button |
| `PayloadMalformed` (format-dep) | Bytes don't match magic / version / envelope shape | "This doesn't look like a Phantom backup." | Change-source button |
| `PayloadCorrupt` (format-dep) | Magic OK but HMAC/integrity fails | "This backup file is corrupted." | Change-source button |
| `PassphraseIncorrect` (format-dep) | Decrypt failed with wrong-key semantics | "Passphrase doesn't match. Try again." | Passphrase input remains, retry decrypt |
| `IncompatibleVersion` (format-dep) | Payload version > client-supported | "This backup was made by a newer version of Phantom. Update the app and try again." | No retry — link to update path |
| `IncompatibleFormat` (format-dep) | Payload version < client-supported cutoff | "This backup is from an older version we no longer support." | No retry — link to migration doc |
| `QrPayloadNotRestore` | Scanned QR is a contact QR, not a restore QR | "That's a contact QR, not a recovery QR. On your other device, open Profile → Recovery." | Retry scan |
| `QrScannerCameraDenied` | Camera permission denied | Existing camera-denied surface reused (`QrScanScreen.kt:47-60`) | Grant + retry |

### 4.2 Terminal failures (user restarts flow from Welcome)

| Class | Trigger | Copy pattern | UI action |
|---|---|---|---|
| `LocalIdentityAlreadyPresent` | `identityRepo.loadIdentity()` returned non-null at `ImportPreCheck` | "This device already has an identity. Sign out first (Profile → Delete account) to restore a different one." | Start over → back to Welcome |
| `WriteFailed` | `saveIdentity` threw or returned error | "Couldn't save the restored identity. If this keeps happening, try reinstalling the app." | Start over |
| `MessagingInitFailed` | `initMessaging`/`initMessagingFromStorage` threw | "The identity was restored but messaging couldn't start. Please restart the app." (identity IS on disk — restart triggers `initMessagingFromStorage`) | Restart-app button (calls `finishAndRemoveTask()` per `MainActivity.kt:437-441`) |
| `UnexpectedInternal` | Uncaught throwable anywhere in the state machine | "Something unexpected went wrong. Please restart the app." | Restart-app button |

### 4.3 Partial-restore protection

The restore flow MUST NOT enter a state where SOME of the identity fields are on disk but NOT others. Enforcement:
- `Importing` runs ONE `saveIdentity(record)` call with a complete `IdentityRecord`. There is no incremental field write.
- If restore payload includes downstream state (conversations, prekeys, ratchet state), those writes MUST come AFTER the identity write AND MUST be idempotent enough that a mid-write crash on `initMessaging` leaves the app in a startable state. This is the Alpha-1 → Alpha-2 property `MigrationManager.runMigration` (`MigrationManager.kt:124-233`) already provides — restore MUST reuse the same discipline.
- The `_identityState.value` StateFlow update MUST be the last observable side-effect. Observers seeing a fresh identity implies messaging is already initialised.

### 4.4 Denied at scanner / picker — behavior

- Camera permission denied → RestoreScreen shows existing camera-denied inline copy (from `QrScanScreen`), Grant CTA opens settings intent, restore stays at intake.
- SAF file picker returned `null` (user cancelled) → RestoreScreen stays at intake; no error surface.

### 4.5 Racing with new-identity onboarding

If the user starts restore, then swipes back to Welcome without cancelling, then taps "Get started" and completes new-identity onboarding, then the restore ViewModel MUST be discarded before `initMessaging(newIdentity, ...)` runs. Enforcement: `Screen.RestoreIdentity` and `Screen.Onboarding` MUST NOT be simultaneously mounted; the `when` dispatch guarantees this in `MainActivity.PhantomApp`. `RestoreViewModel.onCleared()` MUST wipe passphrase + payload as documented in §3.1.

---

## 5. Platform matrix

- **Android minimum SDK:** same as current app minimum (whatever `apps/android/build.gradle.kts` pins — not re-derived here; contract inherits). No new minimum. If the format ADR requires a Keystore API introduced in a later SDK, that becomes a Round-1 issue.
- **API 33+ notifications permission:** unchanged. Restore does not request POST_NOTIFICATIONS itself; the existing `finalize` lambda (`OnboardingScreen.kt:106-112`) runs regardless of new-identity or restore path — the callback fires on `onComplete` from RestoreIdentityScreen too.
- **Foreground service start:** identical to new-identity path — after restore success, `MainActivity` calls `startForegroundService(PhantomMessagingService.class)`. No new service required.
- **Storage Access Framework (SAF):** for file-based restore, use `ActivityResultContracts.OpenDocument` with MIME filter (e.g. `application/octet-stream` + `.phantom-backup` filename hint) — actual mime pinned by ADR-012.
- **Battery / doze:** restore runs on `Dispatchers.IO`, non-blocking to UI; no wake-lock needed. Restore MUST complete in ≤ 5 s under typical conditions (large-conversation payloads may push this; if so ADR-012 must define chunked-import semantics).
- **Devices tested at C6-e:** TECNO BF7-12 (primary) + emulator (Pixel 6 API 34) — same devices as the rest of onboarding.
- **Locale / RTL:** all copy above is English; if the app currently supports other locales, restore copy MUST land in the same string resources and MUST be RTL-mirrored (leading back arrow becomes trailing, etc.). If localisation is not yet in place for the app, restore inherits that gap.

---

## 6. Accessibility, layout, performance, secret-material discipline

### 6.1 Accessibility

- Secondary CTA: `Role.Button`, `contentDescription = "I already have an account"` (or matching localised copy). Touch-target ≥ 48 dp — verified by test.
- Source-picker cards: `Role.Button` on each, distinct `contentDescription` reading label + subtitle.
- Progress state: `LiveRegion` announcement on transition into each substate so screen-reader users hear the progress.
- Failure copy: rendered as `Text` with `semantics { role = Role.Text }`, colour `Danger` (existing constant).
- All CTAs and cards respect `fontScale = 2.0` — cards grow vertically, no text truncation, no ellipsis on primary/subtitle copy.

### 6.2 Layout constraints

- Welcome two-CTA block MUST NOT increase total Welcome-step footprint versus current one-CTA layout. Enforcement: shrink the trailing `Spacer(80.dp)` to `Spacer(32.dp)` when secondary CTA is present.
- `RestoreIdentityScreen` MUST render correctly at min-width 360 dp (nominal phone width — `apps/android/build.gradle.kts` minimum viewport).
- Progress state MUST NOT layout-shift when substate label changes — reserve max-line label height in advance.

### 6.3 Performance

- Restore intake screen mount: ≤ 100 ms cold (composable tree, no I/O).
- `Verifying`: format-dependent target ≤ 500 ms.
- `Decrypting`: ≤ 2 s under KDF cost (format-dependent — e.g. Argon2id at Interactive parameters).
- `Importing`: ≤ 100 ms (single SQL statement + Keystore encrypt).
- `InitializingMessaging`: ≤ 3 s cold — same as new-identity path.
- Total user-observable restore-to-ChatList: target ≤ 8 s at 90th percentile.

### 6.4 Secret-material discipline (LOAD-BEARING)

This section enforces the operator's explicit ask: "запрет логирования secret key, recovery backup и полного QR payload".

**Hard rules:**

- No `Log.*` / `println` / `Timber` / `WssDiag.emit` / any custom logger call may take a parameter that references (directly or transitively):
  - `IdentityRecord` as a whole (default `toString()` dumps secrets — §0),
  - `dhPrivateKeyHex`, `signingPrivateKeyHex`, or any variable holding either,
  - `passphrase` or any input the user typed into the passphrase field,
  - `payload: ByteArray` (full backup bytes) or any decrypted intermediate that contains secret material,
  - the raw scanned-QR string when it's a restore QR (contact-QR raw string is not a secret — it's a public identifier — but the RESTORE payload IS sensitive).
- `IdentityRecord.toString()` MUST be overridden as part of this track (companion object producing a `"IdentityRecord(id=..., username=..., pub=...4chars..., created=..., signing_pub=...4chars..., dh_priv=[REDACTED], signing_priv=[REDACTED])"` render). This override is required IN ADDITION TO the restore feature — it fixes the standing latent-leak surface flagged in §0.
- Analytics / crash-reporter parameters: any restore-state event MAY carry the state name, the failure class name, the source type (`file` | `qr`). It MUST NOT carry any bytes, any hex, any user-entered string, any UUID that identifies the restored identity.
- Debug builds MUST NOT add loggers that violate the rules above under any build flag. `WssDiag` is release-inert-by-default (per PR #399 `f152a6bc` — see §11 cross-refs) but its instrumentation goal is transport, not identity — restore code MUST NOT emit through `WssDiag`.

**Test coverage of the rules:**
- Unit test asserts `IdentityRecord.toString()` does NOT contain `dhPrivateKeyHex` value or `signingPrivateKeyHex` value.
- Unit test asserts `RestoreViewModel.toString()` (default) does NOT expose passphrase or payload.
- Compile-time / lint sweep: a grep-based test walks the restore source and asserts no `Log.*` or `println` or `WssDiag.emit` call takes any variable named `passphrase`, `payload`, `secret`, `record`, or `restore.*Bytes` in its arguments. (Pattern-based; can false-positive on renames — accepted trade-off for a hard-red guard.)

### 6.5 Test-material discipline

Focused test fixtures MUST NOT commit real Ed25519 or X25519 secret keys. Fixture keys are freshly generated at test-runtime OR use documented all-zeros / low-entropy sentinels marked with a `// TEST FIXTURE — NOT A REAL KEY` comment.

---

## 7. Acceptance tests — focused matrix + one final device pass

Following the process from `feedback_architect_process_change_2026_08_09`: scoped verify (compile + focused tests + minimal handoff) between REDLINE rounds; full run (Paparazzi + Run A/A'/B + `assembleDebug`) ONCE after architect logical GREEN; one on-device pass at the end.

### 7.1 Focused test matrix (pure JVM `androidUnitTest`)

**Welcome CTA layout (Compose UI):**
1. `welcome_step_shows_both_ctas_always` — Welcome step renders BOTH `IntroCta("Get started")` AND the secondary "I already have an account" CTA.
2. `welcome_secondary_cta_has_button_role_and_min_48dp_target` — asserts `Role.Button` and touch-target size.
3. `welcome_secondary_cta_respects_fontScale_2_0` — Compose test with `LocalConfiguration.current` overridden.
4. `welcome_secondary_cta_tap_advances_to_restore_screen` — asserts navigation event (via test seam), verifies onboarding state is NOT reset.
5. `welcome_layout_with_secondary_cta_does_not_grow_vertical_footprint` — asserts total `Column` height ≤ prior one-CTA height.

**RestoreIdentityScreen intake:**
6. `restore_intake_shows_both_source_cards` — file + QR cards present.
7. `restore_intake_back_arrow_returns_to_welcome_without_state_loss` — back → onboarding state intact.
8. `restore_intake_cancel_button_same_as_back` — parity test.
9. `restore_intake_hardware_back_same_as_back_arrow` — parity test.

**State machine (pure JVM, no Compose):**
10. `restore_state_verify_failure_transitions_to_recoverable_failure_and_no_write_happens` — inject failing verifier, assert no `saveIdentity` call.
11. `restore_state_import_failure_transitions_to_terminal_write_failed_and_state_reset_on_start_over` — inject failing repo, assert `Start over` returns to Welcome.
12. `restore_state_success_transitions_to_success_after_saveIdentity_and_initMessaging` — happy path with fakes.
13. `restore_state_cancel_after_verify_shows_confirmation_dialog_and_wipes_payload_on_confirm` — payload wipe asserted via a `ByteArray` sentinel that turns non-zero on read.

**Atomic import contract:**
14. `restore_import_calls_saveIdentity_exactly_once_with_complete_identity_record` — no partial writes.
15. `restore_import_records_ed25519_secret_populated_no_alpha1_shape` — `needsSigningKeyBackfill` MUST return false on restored record.
16. `restore_import_updates_identityState_only_after_initMessaging_returns` — observer ordering test.

**QR intake routing (pure JVM against parser):**
17. `restore_qr_scan_contact_shape_returns_QrPayloadNotRestore_failure` — feeds `"user:${64_hex}"` into restore-QR parser, expects rejection.
18. `restore_qr_scan_restore_shape_dispatches_to_verifying` — feeds a well-formed (magic-prefixed) restore payload, expects `Verifying`.

**Secret-material discipline:**
19. `identity_record_toString_does_not_expose_dh_or_signing_private_hex` — regression pin on the `IdentityRecord.toString()` override introduced by this track.
20. `restore_view_model_toString_does_not_expose_passphrase_or_payload` — pin on the ViewModel.
21. `restore_source_files_contain_no_forbidden_logger_calls_touching_secret_variables` — grep-based test (§6.4 rules).

**Cross-cutting integrity:**
22. `restore_flow_locally_identity_present_returns_LocalIdentityAlreadyPresent_and_does_not_touch_repository` — pre-check test.

Total: **22 focused tests** at Round-0 scope. Round-1 (format-fill-in) adds format-specific decrypt / verify / passphrase tests; expect that to bring the total to ~35.

### 7.2 Compose UI a11y test

- Single Espresso-side test (`androidInstrumentedTest`, if that harness is landing in C6-e per memory): asserts touch-target size of secondary CTA meets 48 dp under a real device configuration. Deferred until C6-e infrastructure is available.

### 7.3 Final device pass (once, after logical GREEN)

- On TECNO BF7-12 (primary) + Pixel 6 emulator (API 34):
  - Fresh install → onboarding → secondary CTA → back → onboarding intact (verify username field still empty).
  - Fresh install → onboarding → secondary CTA → source-picker → back → cancel → back to Welcome.
  - Full happy-path restore with a fixture backup (produced by a test-only exporter shipped separately with the format ADR).
  - Force-quit at each state boundary; verify next launch recovers correctly (either restarts restore from scratch OR — if `Importing` completed — enters ChatList via `initMessagingFromStorage`).
  - Camera-denied path.
  - Wrong-passphrase path.
  - Old-version-payload path (if format ADR defines version).

- **APK build:** exactly ONE `assembleDebug` after architect GREEN + tests pass — NOT per REDLINE round.

---

## 8. Open questions requiring architect decision

Round-0 CANNOT be closed to Round-1 without answers to these.

- **Q1 — Path A vs Path B vs Path C.** Which of {land ADR-012 first, invent-in-track, defer entirely} is the operator's intent? Rest of the format sections is blocked on this.
- **Q2 — Backup format basis.** If Path A/B: BIP-39 mnemonic (draft ADR-012 direction) vs raw file (device-secret-encrypted) vs both. Impacts UI (passphrase-entry needed?), Q3, and §4 failure taxonomy.
- **Q3 — Restore-QR shape.** Is it a fingerprint QR that unlocks a relay-stored blob (would require relay-side restore endpoints — currently absent), or does the QR carry the whole encrypted payload (constrained by QR data capacity — payloads > ~2 KB awkward)? Impacts §2.3 and whether QR is even a viable restore source in the same track.
- **Q4 — Conversation restore scope.** Does restore recover only identity keys (peers must re-handshake — same as Alpha-1→Alpha-2) OR also conversation state (messages, ratchet, prekeys)? Impacts §3.4, §4.3, ADR-012 scope.
- **Q5 — Stale memory reconciliation.** Memory records `project_android_onboarding_commit_6a_sealed_holder_2026_08_08`, `project_android_onboarding_final_green_2026_08_11`, `project_android_dual_key_labels_track_2026_08_10` describe an onboarding-v2 + sealed-holder + dual-key-labels shape that the master tree does NOT contain. Options: (a) those changes live on an unmerged feature branch and this track should rebase onto them; (b) those changes were abandoned and should be dropped from memory; (c) some other explanation. Architect input needed before Round-1.
- **Q6 — Signing-secret at-rest wrap.** The `KeystoreIdentityRepository` gap (Ed25519 secret unwrapped in SQLite) exists on the fresh-install path today. Should this track FIX that gap as prerequisite (would land as a `feat(security)` commit before the restore commits), or accept the current shape (which means restored `signingPrivateKeyHex` also lands unwrapped in SQLite)? A security-reviewer subagent pass on the answer is recommended.
- **Q7 — "Delete account first" hard-block.** §4.2 `LocalIdentityAlreadyPresent` currently forces the user to sign out before restoring. Alternative: allow the restore to overwrite an existing identity (with a strong confirmation dialog). Which is intended?
- **Q8 — Copy review.** Placeholder copy in §1 and §4 is engineering-draft. If a copy-review process exists, that lane owns final wording.

---

## 9. Non-goals / scope exclusions (per operator + architect)

- Direct WSS diagnostic tooling. Landed via PR #399; not touched by this track.
- Relay-side changes. No new endpoints, no schema changes, no bootstrap changes.
- Transport / networking. No changes to `HybridRelayTransport`, `WsTransport`, `KtorRelayTransport`.
- Permissions C6-c/d (Notifications, Microphone, Nearby). Separate tracks.
- Main screen redesign C7 (Chats / Profile / Settings / app shell). Separate track.
- New crypto primitives. This contract does not invent a KDF, cipher, MAC, or format.
- Server-side account. Phantom has no accounts on any server; this track does NOT introduce one.
- Cross-device syncing beyond one-shot restore. No live sync, no multi-device account.
- Placeholder "Coming soon" ship. Operator explicit ban.
- Test / focused / device APK builds during REDLINE rounds. One `assembleDebug` after architect GREEN only.

---

## 10. References

**Source files (as of master `bf75d626`, WSS PR #399 merged 2026-08-13):**

- Welcome + onboarding — `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/OnboardingScreen.kt`
- Navigation — `apps/android/src/androidMain/kotlin/phantom/android/navigation/Screen.kt` + `apps/android/src/androidMain/kotlin/phantom/android/MainActivity.kt`
- Identity — `shared/core/identity/src/commonMain/kotlin/phantom/core/identity/{IdentityKey.kt,IdentityRepository.kt,IdentityManager.kt}`
- Identity storage — `shared/core/storage/src/commonMain/kotlin/phantom/core/storage/SqlDelightIdentityRepository.kt` + `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/Identity.sq`
- Keystore wrap — `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt` (`KeystoreIdentityRepository` 2485-2521) + `apps/android/src/androidMain/kotlin/phantom/android/security/KeystoreManager.kt`
- QR encoder — `apps/android/src/androidMain/kotlin/phantom/android/qr/QrCodeImage.kt`
- QR decoder — `apps/android/src/androidMain/kotlin/phantom/android/qr/QrScanScreen.kt`
- Add-contact parser (must not consume restore-QR) — `apps/android/src/androidMain/kotlin/phantom/android/screens/chatlist/AddContactDialog.kt`
- Migration precedent (failure-surface pattern) — `apps/android/src/androidMain/kotlin/phantom/android/screens/migration/MigrationScreen.kt` + `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/MigrationManager.kt`

**Docs / ADRs:**

- `docs/adr/ADR-009-identity-prekey-separation.md` — identity architecture; references ADR-012 at line 76.
- `docs/adr/ADR-023-Local-Prekey-Keystore-Wrap.md`, `docs/adr/ADR-024-Ratchet-State-Keystore-Wrap.md` — precedent for Keystore-wrapped hex material.
- `docs/adr/ADR-018-threat-model-revision-v0_1.md` — the accepted threat model that any restore format must respect.
- `docs/project/ARCHITECTURAL_DECISIONS_TODO.md:608-711` — the ADR-012 draft (BIP-39 → HKDF → AES-GCM). Not landed.

**Memory (some entries stale — see Q5):**

- `project_android_onboarding_c6_existing_account_gap_2026_08_10.md` — the operator ask that motivates this track.
- `feedback_architect_process_change_2026_08_09.md` — the review process this contract follows.
- `project_pr399_wss_diagnostic_ready_2026_08_13.md` — recent landing; establishes the "one APK per logical-GREEN block" rhythm this track inherits.

**Not in this Round-0 sheet, deferred to Round-1:**

- Concrete backup / restore file format spec (blocked on Q1).
- Passphrase entry UI (blocked on Q2).
- Relay-side restore endpoints if any (blocked on Q3).
- Sub-batch split into implementation commits (blocked on Q1–Q5).
- Full APK / device / instrumentation pass plan (deferred to post-architect-GREEN).
