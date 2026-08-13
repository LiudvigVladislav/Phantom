# C6 onboarding — baseline landing contract (Round-1 rev 2)

- **Track:** `c6-onboarding-baseline-landing` — prerequisite to `C6-existing-account`, `C6-c/d`, `C6-e`, `C7`.
- **Status:** Round-1 revision 2 (bounded mini-amendment per architect REDLINE-2 2026-08-13). Awaits architect L1 GREEN.
- **Author:** Claude (agent), 2026-08-13.
- **Supersedes:** Round-1 rev 1 (2026-08-13, same day; had 3 P0 + 3 P1 arithmetic/ownership errors — all corrected below in §Round-2 change log).
- **Related:** [`c6-existing-account-contract.md`](./c6-existing-account-contract.md) — REDLINE / HOLD; unblocked only after this landing + Ed25519-wrap + ADR-012 + backup export.

---

## Executive summary

**Goal:** publish the device-accepted onboarding tree — as it lives on `86c2de99` — into `origin/master` as **4 net-delta landing commits (L1–L4)**, on top of PR #399 (WSS) + PR #384 (brand icons), without disturbing either.

**Method (per architect REDLINE F3):** each landing commit is the **net delta between two phase endpoints on the audit branch**, applied as one clean commit — NOT a sum of intermediate patches. Intermediate SHAs stay as provenance references in the commit body; the code that lands is exactly what surviving-to-endpoint is.

**Net-delta ranges (canonical):**
- **L1** = `166ec9be^..3642f3ec` — DesignV2 harness + tokens + components + drawables + Paparazzi.
- **L2** = `3642f3ec..5c5d2371` — Onboarding v2 flow C1..C5 + `OnboardingFinalizeController` + `phantom_premium` icon fix.
- **L3** = `5c5d2371..5193301c` — `OnboardingFinalizeStateHolder` (sealed) + IdentityRepairMarker + StartupRoute + StartupPresentation + StartupError + C6-b Step 2 bullet animation.
- **L4** = `5193301c..86c2de99` — logo-flash structural fix + Profile rotation via `ScreenSaver` + late-startup resolver in `navigation/StartupRouteResolver.kt` + Finale simplification (drops keys) + Profile `Advanced cryptographic details` + `FinaleIdentityCreatedTest`, `ProfileQrKeyCardSimplifiedTest`, `ScreenSaverTest`, `StartupRouteResolverTest`, `StartupRouteLateWriteIntegrationTest`, `OnboardingFlowV2TransitionTest`.

`166ec9be` is a negative-result Gradle-9-FAIL spike — its **commit history is DROPPED** per architect Q-inv-1, but its **file changes survive as net-state** in the L1 endpoint (`3642f3ec`) and MUST land in L1. Verified surviving files: `apps/android/build.gradle.kts`, `gradle/libs.versions.toml`, `apps/android/src/androidMain/kotlin/phantom/android/ui/designv2/components/PhantomBadge.kt`, `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomBadgeSnapshotTest.kt`. The `166ec9be^` base is used to make L1 a clean net-delta over pre-onboarding master state; the spike's Gradle-9-FAIL story is not preserved in commit history but its evolved code shape IS.

**Result after L4 lands on `master`:**
- Onboarding v2 shipped (Welcome → How → Identity → Privacy → Permissions → Finale).
- Finale is the **simplified single-CTA** shape: `"Identity created"` title, one-line body, `Continue` button. **No keys shown on Finale.**
- Both public keys are ONLY in `Profile → Advanced cryptographic details` (collapsible).
- Sealed `OnboardingFinalizeStateHolder` survives process death.
- Logo-flash fixed via **structural if-branch extraction of Welcome** from `AnimatedContent`.
- Profile rotation lands on `Screen.Profile` (not clobbered to `Screen.ChatList`) via `ScreenSaver` + pure `StartupRouteResolver`.
- WSS PR #399 + PR #384 icons unchanged.

No APK during landing between commits. One `assembleDebug` after logical GREEN. One final on-device pass at the very end.

---

## 0. Source-of-truth reference

- **Accepted onboarding tip:** `86c2de99` — *"fix(android/onboarding+profile): stabilization block — structural logo-flash + Profile rotation + Finale/Profile simplification + late-startup resolver"*.
- **Provenance branch (read-only, do NOT rebase, do NOT use as product base):** `android/direct-wss-diagnostics-2026-08-11` on origin, tip `94302efe`.
- **Landing target:** `origin/master@bf75d626` — currently carries WSS PR #399 + PR #384 icon refresh.

**Verified paths on `86c2de99` (from `git ls-tree`):**
- Onboarding v2 sits at `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/` (32 files, 6 under `v2/steps/`).
- Design system sits at `apps/android/src/androidMain/kotlin/phantom/android/ui/designv2/` (tokens + 7 components + KeyHexFormat) — NOT under `designsystem/`.
- Debug showcase sits at `apps/android/src/androidDebug/kotlin/phantom/android/ui/designv2/showcase/DesignV2Showcase.kt` — NOT under `src/debug/`.
- Snapshots sit at `apps/android/src/androidUnitTest/snapshots/images/…` — NOT under `paparazzi/`.
- Late-startup resolver at `apps/android/src/androidMain/kotlin/phantom/android/navigation/StartupRouteResolver.kt` — NOT in `AppContainer.kt`.

---

## 1. Corrected file ownership per L (from git first-appearance analysis)

| File | First appearance | Landing commit |
|---|---|---|
| Paparazzi harness (`build.gradle.kts`, `libs.versions.toml`) — surviving net-state includes `166ec9be`'s diff (history dropped, code kept) | `166ec9be` + `24b24bcb` | **L1** |
| `apps/android/src/androidMain/kotlin/phantom/android/ui/designv2/DesignV2Tokens.kt`, `DesignV2Typography.kt` (2 top-level files) | `cbee4748` | **L1** |
| `apps/android/src/androidMain/kotlin/phantom/android/ui/designv2/KeyHexFormat.kt` (moved to L4 per architect REDLINE-2 F1) | `86c2de99` | **L4** |
| `apps/android/src/androidMain/kotlin/phantom/android/ui/designv2/components/{PhantomAvatar,PhantomBadge,PhantomButton,PhantomFilterChip,PhantomInput,PhantomSegmentedControl,PhantomToggle}.kt` (PhantomBadge inherits from `166ec9be`) | `ba73a1da` + `166ec9be` | **L1** |
| `apps/android/src/androidMain/res/drawable/ic_dv2_*.xml` — **21 files in L1**, 13 additional files in L2 (34 total in `86c2de99`; corrected per REDLINE-2 F1 — was 35) | `ba73a1da`/`3ffe02f7` for L1's 21; C1..C5 for L2's 13 | **L1: 21, L2: 13** |
| `apps/android/src/androidDebug/kotlin/phantom/android/ui/designv2/showcase/DesignV2Showcase.kt` | `a2ea8a33` | **L1** |
| Onboarding v2 core files (`OnboardingScreenV2.kt`, `OnboardingFlowV2.kt`, `OnboardingStateV2.kt`, `OnboardingChromeV2.kt`, `OnboardingV2Chrome.kt`, `TermsScreenV2.kt`, `OnboardingCipherBackgroundV2.kt`) | `8926a4e5` (C2) | **L2** |
| Step files `WelcomeStepV2.kt`, `HowStepV2.kt`, `IdentityKeyStepV2.kt`, `PrivacyLevelStepV2.kt`, `PermissionsStepV2.kt`, `FinaleConfirmationStepV2.kt`, `PrivacyTierCardV2.kt`, `StepPlaceholderV2.kt` | C2/C3/C4/C5 | **L2** |
| `OnboardingFinalizeController.kt` | `57419912` (C3) — **verified via `git cat-file -e`** | **L2** (architect REDLINE F4 correction — was misplaced in L3 in Round-0) |
| `OnboardingNotificationPermissionCoordinator.kt`, `OnboardingPricingSheet*.kt` (6 files), `OnboardingPrivacyModePersistence.kt` | C4/C5 | **L2** |
| `apps/android/src/androidMain/res/drawable/ic_dv2_phantom_premium.xml` (icon correction) | `5c5d2371` (C1a) | **L2** |
| `OnboardingFinalizeStateHolder.kt` (sealed holder — the actual C6-a landing) | `cbdcdee8` (C6-a) — **verified via `git cat-file -e`** | **L3** |
| `IdentityRepairMarker.kt`, `OnboardingV2StartupRoute.kt`, `OnboardingV2StartupPresentation.kt`, `OnboardingStartupErrorScreen.kt` | `cbdcdee8` (C6-a) | **L3** (architect REDLINE F4 — these belong to C6-a, not C1..C5) |
| `IdentityKeyPreviewAnimation.kt`, `IdentityKeyPreviewCardFrame.kt` (Step 2 **bullet-family glyph** animation — NOT deterministic hex; verified on `86c2de99` KDoc L79-81) | `5193301c` (C6-b) | **L3** |
| Logo-flash structural if-branch fix in `OnboardingFlowV2.kt` (`if (navigationStep != Welcome)` extracts Welcome from `AnimatedContent`; verified on `86c2de99` L699+L760 comment) | `86c2de99` | **L4** |
| `FinaleConfirmationStepV2.kt` simplification (Identity created + Continue; NO keys; verified on `86c2de99` L36-47 KDoc + L66 title) | `86c2de99` | **L4** |
| `ProfileScreen.kt` Advanced-collapsible + rotation via ScreenSaver | `86c2de99` | **L4** |
| `apps/android/src/androidMain/kotlin/phantom/android/navigation/{StartupRouteResolver.kt, ScreenSaver.kt}` | `86c2de99` | **L4** — late-startup fix lives here, **NOT in AppContainer** (architect REDLINE F4) |

**AppContainer per-net-delta touch count (verified via `git diff --name-only`):**
- L1: 0 touches
- L2: 1 touch (onboarding-side wiring near identity creation)
- L3: 0 touches
- L4: 0 touches

---

## 2. Conflict enumeration — file-level with corrected resolution intent

### 2.1 `gradle/libs.versions.toml`

- L1 net-delta touches this file (1 hunk block — Paparazzi + font/typography deps).
- Landed on master (PR #399): Robolectric 4.14.1, androidx-test-core 1.6.1, kotlinx-coroutines-test.
- **Resolution:** union merge. WSS test deps stay. Sections are disjoint by library-name. Verify no version-alias collision (none expected).

### 2.2 `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt`

- Landed on master (PR #399): 17-line delta — `preKeyApi` field declaration + `this.preKeyApi = preKeyApi` in `initMessaging`. This is the ONLY WSS touch to this file.
- L2 net-delta touches this file (1 file — precise diff scope pinned in Round-2 code phase).
- **Resolution (architect Q-conf-1):** perform a three-sided diff (master ← L2-source ← audit-net-delta) before writing the landing hunk. **Preserve `preKeyApi` declaration + assignment as-is; no full-file replacement.** L2's onboarding-side wiring goes in adjacent, non-overlapping regions. Any semantic overlap surfaces as a Round-2 blocker.

### 2.3 `apps/android/src/androidMain/kotlin/phantom/android/MainActivity.kt`

- Landed on master: current state — single-Composable onboarding, no V2 route.
- L4 net-delta touches this file (integration with `StartupRouteResolver`).
- **Resolution:** re-apply audit-branch net-delta AS-IS. Verified: WSS PR #399 did NOT touch `MainActivity.kt` (`git diff origin/master^..origin/master -- apps/android/src/androidMain/kotlin/phantom/android/MainActivity.kt` returns empty).

### 2.4 `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/OnboardingScreen.kt` (legacy)

- Landed on master: PR #384's splash-artwork tweaks.
- L2 net-delta modifies it (early C-commit compat), but after V2 lands the file is orphaned in `86c2de99` (V2 mounted by `MainActivity` instead).
- **Resolution (architect Q-conf-2 = Option A):** retain unchanged; **cleanup deletion is a follow-up commit outside this landing**. `OnboardingScreen.kt` stays for the landing turn; no code path references it once L4 lands, so it's dead but present. Its removal is scheduled after baseline observation on master.

### 2.5 `apps/android/src/androidMain/kotlin/phantom/android/screens/splash/SplashScreen.kt`

- PR #384 modified this file (splash artwork).
- Audit branch also modified it — **only in L2 net-delta** (verified: `git diff --name-only 3642f3ec..5c5d2371 -- apps/android/src/androidMain/kotlin/phantom/android/screens/splash/SplashScreen.kt` returns 1 file; L1/L3/L4 return 0).
- **Resolution:** take L2's audit-net-delta hunk HUNK BY HUNK; DROP any hunk that would revert PR #384's brand assets. Verified via SHA-comparison in Round-0 §2.7: PNG assets are byte-identical, so only the Kotlin-side dual-modification needs per-hunk care.
- **Landing test:** `git diff origin/master^..<landing-tip>@L4 -- apps/android/src/androidMain/res/drawable/{phantom_splash.png,ic_launcher_foreground.xml,ic_launcher_background.xml} apps/android/src/androidMain/res/mipmap-*/ic_launcher*.png` MUST return empty.
- **NOTE (architect REDLINE-2 F5):** `apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt` was PREVIOUSLY listed here as a dual-modification concern. **Removed** — verified NOT touched by any of the 4 net-deltas (`git diff --name-only <range> -- PhantomMessagingService.kt` returns 0 for all four ranges). No PR #384 vs audit conflict on that file.

### 2.6 `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt` + `WssDiagBridge.kt` + `DefaultMessagingServiceTest.kt`

- Landed on master (PR #399): WSS instrumentation additions (three emit sites, new interface, tests).
- Audit branch PREDATES PR #399 — diff-vs-master reports "removals" of WSS content simply because audit was cut earlier.
- **Resolution:** LANDING MUST NOT TOUCH these three files. Any hunk that would remove WSS content is DROPPED.
- **Landing test:** `git diff --stat origin/master..<landing-tip>@L4 -- shared/core/messaging/**` MUST report zero files.

### 2.7 Assets under `apps/android/src/androidMain/res/`

- PR #384 icon PNG assets already byte-identical on audit (verified via SHA compare in Round-0 §2.7).
- Two PR #384-unique XML files (`ic_launcher_background.xml`, `ic_launcher_foreground.xml`) do not exist on audit tip; landing must not touch — they stay as PR #384 shipped them.
- 34 new `ic_dv2_*.xml` drawables — new filenames, additive only. **21 land in L1, 13 land in L2** (per §1 ownership table + §3 L1/L2 manifests).

---

## 3. Rollup split — 4 net-delta commits, exact manifests

Each commit is a **single net-delta** over its source range, applied as one commit on top of the growing landing branch. Commit body lists the source SHA range for provenance.

### L1 — `feat(android/designv2): baseline harness + tokens + components + drawables`

**Net-delta range:** `166ec9be^..3642f3ec` (with `166ec9be` history dropped per architect Q-inv-1).

**File manifest (59 source-net-delta files + 2 contract docs added per REDLINE-2 F6):**

Source-net-delta (verified via `git diff --name-only 166ec9be^..3642f3ec`):
- `gradle/libs.versions.toml` — Paparazzi 2.0.0-alpha05 + typography deps (union-merged with PR #399's Robolectric adds). Includes `166ec9be`-origin surviving changes.
- `apps/android/build.gradle.kts` — Paparazzi plugin apply + testImplementation additions. Includes `166ec9be`-origin surviving changes.
- **2 top-level files** under `apps/android/src/androidMain/kotlin/phantom/android/ui/designv2/`: `DesignV2Tokens.kt`, `DesignV2Typography.kt` (KeyHexFormat.kt moved to L4 per REDLINE-2 F1 — verified first-appearance in `86c2de99`).
- 7 files under `apps/android/src/androidMain/kotlin/phantom/android/ui/designv2/components/` (PhantomAvatar, PhantomBadge, PhantomButton, PhantomFilterChip, PhantomInput, PhantomSegmentedControl, PhantomToggle). `PhantomBadge.kt` inherits the surviving `166ec9be` diff.
- **21** files under `apps/android/src/androidMain/res/drawable/ic_dv2_*.xml` (verified count; L2 will add 13 more for the 34 total present on `86c2de99` — corrected from erroneous 35 in Round-1 rev 1).
- `apps/android/src/androidDebug/kotlin/phantom/android/ui/designv2/showcase/DesignV2Showcase.kt`.
- Font license attribution files (from `cbee4748`).

Control-plane doc additions (architect REDLINE-2 F6 — bundled with L1 as first substantive commit of the track):
- `docs/tracks/android-onboarding/c6-onboarding-baseline-landing-contract.md` (this file) — landing contract with L1 GREEN embedded.
- `docs/tracks/android-onboarding/c6-existing-account-contract.md` — REDLINE / HOLD; downstream contract that unblocks after this landing + Ed25519-wrap + ADR-012 + backup exporter.

**Test-class manifest (11 test files):**
1. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomAvatarSnapshotTest.kt`
2. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomBadgeSnapshotTest.kt`
3. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomButtonSnapshotTest.kt`
4. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomComponentsSemanticsTest.kt`
5. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomFilterChipSnapshotTest.kt`
6. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomIconAuditSnapshotTest.kt`
7. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomIconContactSheetSnapshotTest.kt`
8. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomInputSnapshotTest.kt`
9. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomSegmentedControlSnapshotTest.kt`
10. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomStressSnapshotTest.kt`
11. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/PhantomToggleSnapshotTest.kt`

**Golden manifest:** 12 accepted goldens under `apps/android/src/androidUnitTest/snapshots/images/phantom.android.ui.designv2_*` — transferred BYTE-FOR-BYTE from audit-branch endpoint `3642f3ec`. `./gradlew :apps:android:verifyPaparazzi` (or task equivalent — pinned in Round-2 code phase) runs; recordPaparazzi task NEVER invoked during landing (architect REDLINE F6).

**Commit body includes:** `Ships design-v2 harness. Source SHAs (audit branch, dropped from history per Q-inv-1): 24b24bcb, cbee4748, b6659282, 4a2b2afc, ba73a1da, 7ceaccf5, a2ea8a33, 3ffe02f7, 3642f3ec. Provenance: android/direct-wss-diagnostics-2026-08-11.`

### L2 — `feat(android/onboarding): V2 flow C1..C5 + FinalizeController + phantom_premium icon fix`

**Net-delta range:** `3642f3ec..5c5d2371`.

**File manifest (182 files total in net-delta — dominated by 87 goldens):**
- Onboarding v2 core: `OnboardingScreenV2.kt`, `OnboardingFlowV2.kt`, `OnboardingStateV2.kt`, `OnboardingChromeV2.kt`, `OnboardingV2Chrome.kt`, `TermsScreenV2.kt`, `OnboardingCipherBackgroundV2.kt`, `OnboardingNotificationPermissionCoordinator.kt`, `OnboardingPricingSheet{DragGeometry,GrabStrip,Panel,TierCard,TierData,V2}.kt` (6 files), `OnboardingPrivacyModePersistence.kt`.
- Step files: `WelcomeStepV2.kt`, `HowStepV2.kt`, `IdentityKeyStepV2.kt`, `PrivacyLevelStepV2.kt`, `PermissionsStepV2.kt`, `FinaleConfirmationStepV2.kt` (still in the pre-simplification shape at this endpoint — L4 will simplify), `PrivacyTierCardV2.kt`, `StepPlaceholderV2.kt`.
- `OnboardingFinalizeController.kt` (architect REDLINE F4 correction: this file lands here in L2, not L3).
- `apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt` — 1 hunk block (onboarding wiring; three-sided-diff resolved per §2.2).
- `apps/android/src/androidMain/kotlin/phantom/android/MainActivity.kt` — mount V2 as `Screen.Onboarding`.
- **13 new `ic_dv2_*.xml` drawables** (architect REDLINE-2 F5 — was previously listed only as `phantom_premium`; corrected to the full group): `ic_dv2_block.xml`, `ic_dv2_copy.xml`, `ic_dv2_ed25519_key.xml`, `ic_dv2_ghost.xml`, `ic_dv2_ghost_signal.xml`, `ic_dv2_notify_off.xml`, `ic_dv2_notify_on.xml`, `ic_dv2_phantom_premium.xml`, `ic_dv2_privacy.xml`, `ic_dv2_standard.xml`, `ic_dv2_tier_business.xml`, `ic_dv2_tier_plus.xml`, `ic_dv2_tier_pro.xml`. (Cumulative dv2 count after L2: 34.)
- Legacy `OnboardingScreen.kt` — modified per audit's C1..C5 hunks (compat).
- `SplashScreen.kt` — hunk-by-hunk apply; drop any brand-asset reversion (per §2.5). `PhantomMessagingService.kt` is NOT touched in this net-delta (architect REDLINE-2 F5).

**Test-class manifest (23 test files):**
1. `notifications/NotificationsOptInMigrationTest.kt`
2. `screens/onboarding/v2/OnboardingNotificationPermissionCoordinatorTest.kt`
3. `screens/onboarding/v2/OnboardingPricingSheetDragGeometryTest.kt`
4. `screens/onboarding/v2/OnboardingPrivacyModePersistenceTest.kt`
5. `screens/onboarding/v2/OnboardingScreenV2GateTest.kt`
6. `screens/onboarding/v2/OnboardingV2EdgeGestureTest.kt`
7. `screens/onboarding/v2/OnboardingV2FinalizeContractTest.kt`
8. `screens/onboarding/v2/OnboardingV2FinalizeOutcomeContractTest.kt`
9. `screens/onboarding/v2/OnboardingV2PermissionsSemanticsTest.kt`
10. `screens/onboarding/v2/OnboardingV2PricingSheetSemanticsTest.kt`
11. `screens/onboarding/v2/OnboardingV2PrivacyDialSemanticsTest.kt`
12. `screens/onboarding/v2/OnboardingV2PrivacyFinalizeOrderingTest.kt`
13. `screens/onboarding/v2/OnboardingV2ScrollableCtaReachabilityTest.kt`
14. `screens/onboarding/v2/OnboardingV2SemanticsTest.kt`
15. `screens/onboarding/v2/OnboardingV2StateTest.kt`
16. `ui/designv2/OnboardingV2ResponsiveMatrixTest.kt`
17. `ui/designv2/OnboardingV2SnapshotTest.kt`
18. `ui/designv2/PhantomButtonSnapshotTest.kt` (modification; supersedes L1 version)
19. `ui/designv2/PhantomComponentsSemanticsTest.kt` (modification)
20. `ui/designv2/PhantomIconAuditSnapshotTest.kt` (modification)
21. `ui/designv2/PhantomInputSnapshotTest.kt` (modification)
22. `ui/designv2/PhantomStressSnapshotTest.kt` (modification)
23. `ui/designv2/components/PhantomSegmentedControlTouchTargetBoundsTest.kt`

**Golden manifest:** 87 goldens transferred byte-for-byte from `5c5d2371`. Verify-only (no record). Golden list includes onboarding v2 responsive matrix (finale_confirmation × narrow320/pixel5 × fs1/fs2, how × …, identity_key_{empty,invalid,short}_× …, permissions × …, welcome × …), pricing sheet, privacy dial, permissions, plus updates to L1's PhantomButton/IconAudit/Input/Stress goldens.

**Commit body includes:** `Ships onboarding v2 flow C1..C5, OnboardingFinalizeController, phantom_premium icon fix. Source SHAs (audit branch): a5d1ccf2, 8926a4e5, 57419912, dff382b4, 1dbf913d, 5c5d2371. Provenance: android/direct-wss-diagnostics-2026-08-11.`

### L3 — `feat(android/onboarding): sealed FinalizeStateHolder + Startup route + IdentityRepairMarker + C6-b bullet animation`

**Net-delta range:** `5c5d2371..5193301c`.

**File manifest (35 files in net-delta + 1 docs):**
- `OnboardingFinalizeStateHolder.kt` — sealed state holder (architect REDLINE F4: this is the C6-a landing, not FinalizeController).
- `IdentityRepairMarker.kt` — repair marker (architect REDLINE F4: belongs to C6-a/L3).
- `OnboardingV2StartupRoute.kt`, `OnboardingV2StartupPresentation.kt`, `OnboardingStartupErrorScreen.kt` — Startup Route/Presentation/Error (architect REDLINE F4: belong to C6-a/L3).
- `IdentityKeyPreviewAnimation.kt` — **bullet-family glyph** animation (`·`, `•`, `○`, `.`) per `86c2de99` KDoc L79-81; **NOT deterministic hex** (architect REDLINE F5).
- `IdentityKeyPreviewCardFrame.kt` — card frame with `rememberSaveable` "shuffle-started" flag.
- Modifications to `IdentityKeyStepV2.kt` for animation wiring.
- Modifications to `OnboardingV2StartupRoute.kt` for repair-marker handling.
- 1 docs file (architect REDLINE-2 F5 — pinned): `docs/tracks/android-onboarding/c6-b-key-preview-animation.md`.

**Test-class manifest (13 test files):**
1. `screens/onboarding/v2/IdentityKeyPreviewAnimatedCardTest.kt`
2. `screens/onboarding/v2/KeyPreviewAnimationPhaseTest.kt`
3. `screens/onboarding/v2/OnboardingV2FinalizeOutcomeContractTest.kt` (modification)
4. `screens/onboarding/v2/OnboardingV2IdentityRepairMarkerTest.kt`
5. `screens/onboarding/v2/OnboardingV2ProductionWrapperQuarantineTest.kt`
6. `screens/onboarding/v2/OnboardingV2RepairRequiredScreenTest.kt`
7. `screens/onboarding/v2/OnboardingV2ScrollableCtaReachabilityTest.kt` (modification)
8. `screens/onboarding/v2/OnboardingV2StartupErrorScreenTest.kt`
9. `screens/onboarding/v2/OnboardingV2StartupPresentationTest.kt`
10. `screens/onboarding/v2/OnboardingV2StartupRouteTest.kt`
11. `screens/onboarding/v2/OnboardingV2StateTest.kt` (modification)
12. `ui/designv2/OnboardingV2RecoverySnapshotTest.kt`
13. `ui/designv2/OnboardingV2Step2AnimationSnapshotTest.kt`

**Key semantic invariants (test assertions — architect REDLINE F5):**
- `KeyPreviewAnimationPhaseTest` MUST assert **absence of hex character `[0-9a-fA-F]` in rendered glyphs on all 33 states** (bullet family only).
- `IdentityKeyPreviewAnimatedCardTest` MUST assert glyphs are exactly one of `·`, `•`, `○`, `.`.
- `OnboardingV2Step2AnimationSnapshotTest` goldens capture the bullet-family shuffle; if hex glyphs appear the goldens fail.

**Golden manifest (Round-8 amendment, 2026-08-14):** 22 goldens = 6 own (from `5193301c` — 2 `OnboardingV2RecoverySnapshotTest` + 3 `OnboardingV2Step2AnimationSnapshotTest` + 1 `OnboardingV2SnapshotTest_onboarding_v2_identity_key_valid`) **+ 16 pulled-forward from `50f1cb8f`** (the 4 identity_key states × 4 `narrow320/pixel5 × fs1/fs2` configs in `OnboardingV2ResponsiveMatrixTest`). Pre-check confirmed all 16 blob SHAs identical between `50f1cb8f` and accepted tip `86c2de99` before transfer; post-checkout verified 16/16 byte-identical.

**Ownership rationale for the 16 pull-forward:** The bullet-family rendering change to `IdentityKeyStepV2.kt` lands in L3 (C6-b), but the accepted-history commit `50f1cb8f` re-recorded the corresponding 16 `ResponsiveMatrix_identity_key_*` goldens as a separate later commit inside L4's audit range. This is a genuine ownership error in the audit-branch history: goldens whose pixels are caused by an L3 code change were bundled with unrelated L4 stabilization work. Rather than commit a knowingly-red transitional state and rely on L4 to fix it (which would break L3 bisectability), the 16 files move to L3 ownership. L4 explicitly excludes them (§L4 below); endpoint totals stay L3=97 / L4=98 goldens; the raw file-count for L3 goes from 35→52 (36+16) and L4 goes from 48→32 (48-16).

**Endpoint verify:** `./gradlew :apps:android:verifyPaparazziDebug --tests "phantom.android.ui.designv2.*Snapshot*" --tests "phantom.android.ui.designv2.*ResponsiveMatrix*" --tests "phantom.android.ui.designv2.OnboardingV2Recovery*" --tests "phantom.android.ui.designv2.OnboardingV2Step2Animation*" --rerun-tasks` = **97/0/0 GREEN in 1m 25s**.

**Commit body includes:** `Ships sealed OnboardingFinalizeStateHolder + IdentityRepairMarker + Startup route/presentation/error + Step 2 bullet-family animation. Source SHAs (audit branch): cbdcdee8 (C6-a), 5193301c (C6-b), + 16 goldens pulled forward from 50f1cb8f (Round-8 ownership repair — see contract §3 L3 rationale). Provenance: android/direct-wss-diagnostics-2026-08-11.`

### L4 — `feat(android/onboarding+profile): stabilization block — Finale simplified + Profile Advanced + logo-flash structural fix + rotation via ScreenSaver + late-startup resolver`

**Net-delta range:** `5193301c..86c2de99`.

**File manifest (48 files in net-delta + 4 docs):**
- `apps/android/src/androidMain/kotlin/phantom/android/ui/designv2/KeyHexFormat.kt` (NEW here — moved from L1 per REDLINE-2 F1; verified first-appearance in `86c2de99`).
- `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/FinaleConfirmationStepV2.kt` — **simplified to `Identity created` + one-line body + `Continue`; NO KEYS RENDERED** (architect REDLINE F1; verified against `86c2de99` KDoc L36-47 and title at L66).
- `apps/android/src/androidMain/kotlin/phantom/android/screens/profile/ProfileScreen.kt` — matching simplification + `Advanced cryptographic details` collapsible (both public keys live ONLY here in the accepted UX; QR payload still byte-exact `username:X25519`).
- `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingFlowV2.kt` — **logo-flash STRUCTURAL if-branch fix** (`if (navigationStep != OnboardingStepV2.Welcome)` extracts Welcome from `AnimatedContent`; architect REDLINE F2 — NOT `EnterTransition.None togetherWith ExitTransition.None`; verified on `86c2de99` L699 + KDoc L760).
- `apps/android/src/androidMain/kotlin/phantom/android/navigation/StartupRouteResolver.kt` (NEW) — pure resolver called with live `currentScreen` at the moment the startup coroutine completes (architect REDLINE F4 — late-startup fix lives here, NOT in `AppContainer`).
- `apps/android/src/androidMain/kotlin/phantom/android/navigation/ScreenSaver.kt` (NEW) — process-death survival for `Screen.*` selection so Profile stays Profile after rotation.
- `apps/android/src/androidMain/kotlin/phantom/android/MainActivity.kt` — wiring for `StartupRouteResolver` + `ScreenSaver`.
- 4 docs files under `docs/tracks/android-onboarding/` (existing dual-key and logo-flash contracts). **These MUST be marked `SUPERSEDED` in-place — see §7 doc allowlist** (architect REDLINE F8).

**Test-class manifest (14 test files — 7 ADDED + 7 MODIFIED, verified via `git diff --name-status 5193301c..86c2de99` — architect REDLINE-2 F5):**

- 7 ADDED (net-new at this endpoint): `ScreenSaverTest`, `StartupRouteLateWriteIntegrationTest`, `StartupRouteResolverTest`, `FinaleIdentityCreatedTest`, `OnboardingFlowV2TransitionTest`, `ProfileQrKeyCardSimplifiedTest`, `ProfileQrKeyCardSnapshotTest`.
- 7 MODIFIED (updates to earlier tests): `IdentityKeyPreviewAnimatedCardTest`, `KeyPreviewAnimationPhaseTest`, `OnboardingV2FinalizeContractTest`, `OnboardingV2FinalizeOutcomeContractTest`, `OnboardingV2ScrollableCtaReachabilityTest`, `OnboardingV2StateTest`, `OnboardingV2Step2AnimationSnapshotTest`.

Full path listing:
1. `apps/android/src/androidUnitTest/kotlin/phantom/android/navigation/ScreenSaverTest.kt`
2. `apps/android/src/androidUnitTest/kotlin/phantom/android/navigation/StartupRouteLateWriteIntegrationTest.kt`
3. `apps/android/src/androidUnitTest/kotlin/phantom/android/navigation/StartupRouteResolverTest.kt`
4. `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/FinaleIdentityCreatedTest.kt`
5. `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/IdentityKeyPreviewAnimatedCardTest.kt` (modification)
6. `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/KeyPreviewAnimationPhaseTest.kt` (modification)
7. `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/OnboardingFlowV2TransitionTest.kt`
8. `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/OnboardingV2FinalizeContractTest.kt` (modification)
9. `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/OnboardingV2FinalizeOutcomeContractTest.kt` (modification)
10. `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/OnboardingV2ScrollableCtaReachabilityTest.kt` (modification)
11. `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/OnboardingV2StateTest.kt` (modification)
12. `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/profile/ProfileQrKeyCardSimplifiedTest.kt`
13. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/OnboardingV2Step2AnimationSnapshotTest.kt` (modification)
14. `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/ProfileQrKeyCardSnapshotTest.kt`

**Key semantic invariants (architect REDLINE F5):**
- `FinaleIdentityCreatedTest` MUST assert the Finale composable text tree contains NONE of these substrings: `Ed25519`, `X25519`, `key`, `Key`, `copy`, `Copy`, `hex`, `Hex`, and the identity's public-key hex (both forms). Note: `FinaleDualKeyLabelsTest` was DELETED — this replaces it.
- `ProfileQrKeyCardSimplifiedTest` MUST assert the Profile "Advanced cryptographic details" section EXISTS (collapsed by default) and, when expanded, shows both keys.
- `ScreenSaverTest` MUST assert `Screen.Profile` survives process death round-trip.
- `StartupRouteResolverTest` MUST assert: `RepairQuarantine → Screen.Onboarding`, `TransientStartupFailure → Screen.StartupError`, `FreshOnboarding → Screen.Onboarding`, `Migration → Screen.Migration`, `ChatList → currentOrRestoredScreen ?: Screen.ChatList` — matches KDoc L20-24 on `86c2de99`.
- `StartupRouteLateWriteIntegrationTest` MUST assert: after rotation with `currentScreen=Profile`, a late startup coroutine completion **does NOT clobber** the route back to `ChatList` — the resolver reads the LIVE value.
- `OnboardingFlowV2TransitionTest` MUST assert the structural if-branch shape (Welcome mounted OUTSIDE `AnimatedContent`).

**Golden manifest (Round-8 amendment, 2026-08-14):** 6 goldens transferred byte-for-byte from `86c2de99` — was 22 in Round-1 rev 2, **minus 16 that L3 now owns** (per L3 ownership rationale above). The 6 that stay with L4: Finale simplified goldens + ProfileQrKeyCard goldens + StartupError + any others that reflect L4-scope code changes (Finale simplification, Profile Advanced-collapsible, structural logo-flash, `ScreenSaver` rotation). Verify-only.

**L4 exclusion pin:** during L4 application, the 16 `OnboardingV2ResponsiveMatrixTest_identity_key_{empty,short,valid,invalid}[{narrow320,pixel5}_{fs1,fs2}].png` files MUST NOT change again — they are already at their `86c2de99`-final content on the branch after L3 committed. Verified by pre-check equality between `50f1cb8f` (L3 pull-forward source) and `86c2de99` (L4 endpoint).

**Commit body includes:** `Ships onboarding stabilization block. Source SHAs (audit branch): 5797133f, 50f1cb8f, 86c2de99. Provenance: android/direct-wss-diagnostics-2026-08-11. Supersedes prior dual-key-labels + logo-flash-fix contracts marked SUPERSEDED under docs/tracks/android-onboarding/.`

---

## 4. Rollout order + verification gates

### 4.1 Landing sequence

1. Branch off `origin/master@bf75d626` as `android/onboarding-baseline-landing` (per architect Q-target-1).
2. Apply L1 net-delta → run compile + L1 focused tests (11 classes) + `verifyPaparazzi` on L1's 12 goldens.
3. Apply L2 net-delta → run compile + L2 focused tests (23 classes) + `verifyPaparazzi` on L2's 87 goldens (cumulative 99 goldens).
4. Apply L3 net-delta → run compile + L3 focused tests (13 classes) + `verifyPaparazzi` on L3's 6 goldens + bullet-family invariant assertions.
5. Apply L4 net-delta → run compile + L4 focused tests (14 classes) + `verifyPaparazzi` on L4's 22 goldens + Finale-absence-of-keys assertions + rotation-Profile-stays-Profile assertions.
6. Report to architect with 4 commit SHAs + per-L focused-test summary + per-L verifyPaparazzi status + list of dropped hunks (per §2.5, §2.6).
7. Await architect logical GREEN.
8. AFTER GREEN: one full `./gradlew :apps:android:testDebugUnitTest` (full task, not just focused) + one `assembleDebug` + on-device install on TECNO BF7-12 + emulator Pixel 6 API 34.
9. On-device verification MUST reproduce accepted-tip behavior: v2 flow, sealed holder survives process death, C6-b **bullet** animation smooth, no logo-flash on Welcome→How, Profile rotation → still `Screen.Profile`, Finale shows **"Identity created" + Continue** (no keys), Profile → Advanced cryptographic details reveals both keys.
10. If on-device green: open PR. If red: pinpoint regression, amend the failing commit, re-verify.

### 4.2 Between-landing-commits gates (unchanged from Round-0)

- Compile check MUST pass after each landing commit.
- Focused tests for that commit MUST pass before the next commit is applied.
- `verifyPaparazzi` MUST pass on that commit's goldens (architect REDLINE F6 — verify-only, never record during landing).
- NO full Paparazzi record run between landing commits.
- NO `assembleDebug` between commits.
- NO APK build between commits.

### 4.3 Rollback — CORRECTED (architect REDLINE F7 + REDLINE-2 F4)

**All four commits L1–L4 are strictly dependent as a chain** (architect REDLINE-2 F4 correction — was wrongly split in Round-1 rev 1). L1 ships DesignV2 tokens/components/drawables; L2's V2 onboarding imports those tokens/components directly (`DesignV2Tokens`, `PhantomButton`, `PhantomInput`, etc. — verified in step files); L3 depends on L2's V2 flow; L4 depends on L3's sealed holder + Startup route.

- **Before push** (still on local `android/onboarding-baseline-landing`): amend the failing commit in place using `git commit --amend` on the failing L, then rebuild the suffix by cherry-picking Lʹ+1..L4 back on top. Never leave a broken L in the branch history.
- **After push** (branch open as PR or merged): revert commits **in reverse order from L4 downward** to the problematic L. Cannot cherry-revert a middle L alone. If L2 must be reverted post-merge, the reverts are `git revert L4` → `git revert L3` → `git revert L2` — never `git revert L2` alone. If L1 must be reverted, all four L4→L3→L2→L1 must be reverted (no independent L1 rollback).
- The audit branch `android/direct-wss-diagnostics-2026-08-11@94302efe` remains immutable provenance for full re-landing if a total rollback proves necessary.

### 4.4 What survives + what stays out

Survives on `master` after landing (in addition to WSS + brand-icon state):
- Full V2 onboarding flow (Welcome → How → Identity → Privacy → Permissions → Finale).
- **Finale simplified** — `Identity created` + Continue; NO keys, NO copy affordance.
- **Profile Advanced cryptographic details** — both keys accessible here only.
- Sealed `OnboardingFinalizeStateHolder` process-death survival.
- **Bullet-family** Step 2 preview animation (no hex).
- Structural if-branch logo-flash fix (Welcome extracted from AnimatedContent).
- Profile rotation via `ScreenSaver` → stays `Screen.Profile`.
- Pure `StartupRouteResolver` with live `currentScreen` read.
- DesignV2 tokens, typography, 7 stateless components, **34 dv2 drawables** (21 in L1 + 13 in L2 — architect REDLINE-2 F1 correction).
- Paparazzi harness + verified goldens (**98 goldens on the L4 endpoint** — REDLINE-2 F3 correction; 127 was a double-count).

Stays out (explicit non-goals — §6):
- C6-existing-account restore flow.
- C6-c/d permission toggles.
- C6-e instrumentation harness.
- C7 main-screen redesign.
- Ed25519 Keystore-wrap change (separate security-hardening track — architect Q6 + Q-adr-1).
- Any change to WSS PR #399 material.
- Any change to PR #384 brand assets.
- Any legacy `OnboardingScreen.kt` deletion (deferred to post-landing cleanup — Q-conf-2).
- Any golden re-recording (architect REDLINE F6 — verify-only).

---

## 5. Acceptance-test summary — corrected

**Aggregate test coverage after L4 (architect REDLINE-2 F5 correction):**
- **L1** — 11 test classes touched (all ADDED).
- **L2** — 23 test classes touched (17 ADDED + 6 modifications).
- **L3** — 13 test classes touched (mostly ADDED; some modifications).
- **L4** — 14 test classes touched (7 ADDED + 7 MODIFIED).
- **Unique classes touched, deduplicated:** **46** (verified via `git diff --name-status origin/master..86c2de99 -- 'apps/android/src/androidUnitTest/**/*Test.kt'` → 46 A + 0 M = 46 unique new test files that will exist on `master` after L4). Previous "~50" was wrong.

**Load-bearing invariants (all pinned by test names above):**
- **F1 — Finale absence of keys.** `FinaleIdentityCreatedTest` asserts Finale text tree contains NONE of {`Ed25519`, `X25519`, `key`, `Key`, `copy`, `Copy`, `hex`, `Hex`, identity's actual key hex}.
- **F2 — logo-flash structural.** `OnboardingFlowV2TransitionTest` asserts Welcome is mounted OUTSIDE `AnimatedContent` (structural if-branch pattern verified in `OnboardingFlowV2.kt:699`).
- **F5-a — bullet-family animation.** `KeyPreviewAnimationPhaseTest` asserts NO character in the animated glyphs matches `[0-9a-fA-F]` across all 33 phase states.
- **F5-b — Profile after rotation.** `ScreenSaverTest` + `StartupRouteLateWriteIntegrationTest` together assert `Screen.Profile` survives process-death round-trip AND a late-completing startup coroutine does not clobber the restored screen.
- **F5-c — Profile Advanced-collapsible.** `ProfileQrKeyCardSimplifiedTest` asserts the `Advanced cryptographic details` section exists collapsed-by-default; when expanded, both public keys appear.
- **Goldens verify-only** (F6) — architect REDLINE-2 F3 correction: **98 goldens exist on the L4 endpoint `86c2de99`** (per-endpoint totals: L1=12, L2=92, L3=97, L4=98). The 12/87/6/22 numbers are **per-delta touches** (some goldens re-touched across ranges); the previous "127 total (12+87+6+22)" was a double-count and is WRONG. Final `verifyPaparazzi` covers 98 unique goldens transferred byte-for-byte from the phase endpoints. `record` task NEVER invoked during landing; pixel drift → block landing → separate human visual review.

**Final device acceptance (post logical GREEN, one pass):**
- Welcome shows single `Get started` CTA (secondary "I already have an account" is NOT part of this landing — belongs to C6-existing-account track after prerequisites).
- Welcome → How swap has no logo-flash.
- How → Identity → Privacy → Permissions → Finale.
- Finale = `Identity created` + `Continue`. No keys, no copy button.
- Continue → ChatList.
- Open Profile → default view has no keys shown.
- Profile → tap `Advanced cryptographic details` → both keys revealed.
- Rotate device on Profile → still on `Screen.Profile` after settle.
- **Background-process-kill with saved task** (architect REDLINE-2 F7 — replaces prior "force-kill" wording which cleared task and did not exercise `rememberSaveable`): put app in background, kill the background process via `adb shell am kill phantom.android` (or wait for system LMK to reclaim it) WITHOUT swiping the task out; return to app via recents → system restores the task with saved `Bundle`; verify onboarding progress restored through sealed holder + `rememberSaveable` fields. `adb shell am force-stop` is NOT used for this test because it clears the task and bypasses the saved-state path being validated.

### 5.0 L2 test-execution note (Round-7, 2026-08-13)

When validating L2 (or any landing that adds Compose-UI test classes to the shared `androidUnitTest` JVM), the authoritative gate is **per-class isolation via one `--tests` filter per Gradle invocation** for every Compose-UI class in the change set — plus one small focused batch for pure JVM/state classes — plus a scoped `verifyPaparazziDebug` filter for Paparazzi snapshot classes.

Rationale: a single full `:apps:android:testDebugUnitTest` sweep on L2 accumulates enough `composeTestRule.setContent { … }` calls in one JVM to trip the documented cumulative flake `androidx.test.espresso.AppNotIdleException: Compose did not get idle after N attempts in 60 SECONDS` (memory record: `project_android_test_infra_appnotidleexception_cumulative_2026_08_11`; base-regression confirmed 2026-08-11 on the accepted tip `86c2de99` with all onboarding-v2 changes stashed). The flake is infrastructure-level and orthogonal to L2 correctness. It is NOT a regression, but it IS a real failure of the full-run gate, so the full-run result is recorded as `KNOWN INFRA RED / NON-GATE` and per-class isolation replaces it as the authoritative gate.

Rules (Round-7):

1. Do NOT enable global `forkEvery = 1L` for `testDebugUnitTest` — masks leaks and doubles wall-clock.
2. Do NOT run all Compose-UI classes under a single combined `--tests` filter — same cumulative accumulation.
3. Do NOT run the full `:apps:android:testDebugUnitTest` sweep as a landing gate for any commit that adds Compose-UI classes.
4. DO run each L2+ Compose-UI test class as its own `./gradlew :apps:android:testDebugUnitTest --tests "<FQN>" --rerun-tasks` invocation.
5. DO batch pure JVM/state classes (no `composeTestRule.setContent`) as one focused invocation with multiple `--tests` filters.
6. DO run `./gradlew :apps:android:verifyPaparazziDebug --tests "phantom.android.ui.designv2.*Snapshot*" --tests "phantom.android.ui.designv2.*ResponsiveMatrix*" --rerun-tasks` as one invocation to cover Paparazzi snapshot classes.
7. STOP rules: any isolated class that still fails with `AppNotIdleException` → report as tooling issue without further experiments; any other unexpected failure → report as potential landing regression, do not proceed.

Root-cause investigation of the cumulative flake is a **parked separate track** — see memory `project_android_test_infra_appnotidleexception_cumulative_2026_08_11` for known bisect data. Landing L2..L4 does not gate on that investigation.

**Round-7 verified L2 landing:** 9 Compose-UI classes isolated + 1 JVM/state batch + 1 scoped Paparazzi verify = all BUILD SUCCESSFUL / 0 failures. Full-run `485/22` recorded as KNOWN INFRA RED / NON-GATE (all 22 failures = same `AppNotIdleException` signature at `RobolectricIdlingStrategy.runUntilIdle`).

### 5.1 L1 compatibility invariant (Round-6, 2026-08-13)

> **Local JVM tests that transitively invoke Android framework APIs use an explicit Robolectric runner with bare Application.**

Concretely: any `androidUnitTest` class whose test methods reach production code that calls `android.util.Log.*` (or any other `android.*` method the AGP null-mock stub does not cover on the current classpath) MUST be annotated:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyTest { … }
```

Rationale: L1 adds Compose-UI-test + Paparazzi/LayoutLib dependencies to the shared `androidUnitTest` classpath. Their real Android SDK jar supersedes the AGP null-mock stub that `unitTests.isReturnDefaultValues = true` normally provides, so `Log.i` (and similar) reaches native `println_native` at runtime. Robolectric's `RobolectricTestRunner` installs `ShadowLog` (and other shadows for framework APIs), and bare `android.app.Application` avoids booting production `PhantomApplication` — which pure-transaction tests do not need. The invariant preserves both:
- correct Log stubbing for the affected tests,
- classpath integrity for Compose-UI/Paparazzi tests that DEPEND on the real Android SDK jar being present.

Round-6 verified this invariant on 3 test classes:
- `phantom.android.transport.TransportRewalkCoordinatorTransactionTest` (transitively calls `TransportRewalkCoordinator.seedNetworkPresent → Log.i`),
- `phantom.android.calls.CallManagerGuardTest` (transitively calls `CallManagerKt.checkCallCapability → Log.i`),
- `phantom.android.transport.HybridRelayTransportIntegrationTest20` (transitively calls `RelayLog_androidKt.relayLog → Log.i` via a `KtorRelayTransport.*ForTest` reflection seam).

After annotation, full `:apps:android:testDebugUnitTest --rerun-tasks` on L1 was **249/249 GREEN** (verified 2026-08-13). Any future test class discovered to fail with `Log.println_native UnsatisfiedLinkError` on L1's classpath MUST receive the same harness — mass-adding is NOT the answer; per-class annotation with a short KDoc explanation is the required shape.

---

## 6. Non-goals — explicit exclusions

- **Direct WSS diagnostic tooling.** Do not touch any WSS file. Enforced by §2.6 landing test.
- **PR #384 brand icons.** Byte-identical on audit; enforced by §2.5+§2.7 landing test.
- **`C6-existing-account`.** REDLINE / HOLD until prerequisite chain lands (baseline → Ed25519-wrap → ADR-012 → backup exporter).
- **C6-c / C6-d / C6-e / C7.** Separate tracks.
- **Ed25519 Keystore-wrap** (architect Q6 + Q-adr-1). Separate security-hardening track opened **after** baseline lands.
- **Legacy `OnboardingScreen.kt` deletion** (Q-conf-2). Separate cleanup after landing.
- **Golden re-recording** (F6). Never during landing; if a golden mismatches, block landing and escalate to visual review.
- **Rebase of development onto audit branch** (operator explicit).
- **Any secondary CTA on Welcome.** The `I already have an account` button is a C6-existing-account concern, NOT baseline.
- **Analytics events.** None to add in this landing; also removed from the C6-existing-account contract per architect Q8.

---

## 7. Documentation allowlist — architect REDLINE F8

Docs already on the audit branch that describe **intermediate** onboarding states now superseded by `86c2de99`:

| Doc path (relative to repo root) | Landing treatment |
|---|---|
| `docs/tracks/android-onboarding/dual-key-labels-contract.md` | **Mark SUPERSEDED at the top**; keep body intact for provenance. Superseded by: L4 Finale simplification + Profile Advanced-collapsible. |
| `docs/tracks/android-onboarding/logo-flash-fix-contract.md` | **Mark SUPERSEDED**; superseded by: structural if-branch fix landing in L4 (`OnboardingFlowV2.kt:699`). |
| `docs/tracks/android-onboarding/c6-b-key-preview-animation.md` | Keep as-is; describes the bullet-family animation shape which IS what lands. |
| `docs/tracks/android-onboarding/onboarding-stabilization-block-2026-08-11.md` | Keep as-is; describes L4 shape. |
| `docs/tracks/android-onboarding/c6-existing-account-contract.md` (this repo's copy of the REDLINE / HOLD contract) | Keep; unaffected by baseline (blocks itself on baseline landing). |
| `docs/tracks/android-onboarding/c6-onboarding-baseline-landing-contract.md` (this file) | Keep as the landing contract. |

**SUPERSEDED marker format** (add at very top of the file, above any front-matter):

```markdown
> **SUPERSEDED** — 2026-08-13 — this contract described an intermediate shape that was replaced by the stabilization block landing in L4 of `c6-onboarding-baseline-landing`. Retained for provenance; not authoritative for current UI.
```

L4 commit body MUST mention the SUPERSEDED marker addition.

---

## 8. Open questions — status vs Round-0

All Round-0 open questions have architect answers as of 2026-08-13:

| Q | Round-0 | Round-1 (architect answer) |
|---|---|---|
| Q-conf-1 (AppContainer merge) | asked | **three-sided diff BEFORE code**; preserve `preKeyApi` declaration + assignment; no full-file replacement |
| Q-conf-2 (legacy `OnboardingScreen.kt`) | asked (default A) | **Option A**; deletion is separate cleanup |
| Q-split-1 (2 vs 3 vs 4 commits) | proposed 4 | **4 commits, L1..L4** |
| Q-split-2 (commit-message provenance) | proposed YES | **YES** — commit body lists source SHA/range |
| Q-inv-1 (drop `166ec9be`) | proposed DROP | **DROP** history; keep surviving net-state in the four files listed in §0/§3 (`build.gradle.kts`, `libs.versions.toml`, `PhantomBadge.kt`, `PhantomBadgeSnapshotTest.kt`) |
| Q-drop-1 (legacy tests) | proposed KEEP | **KEEP** for now |
| Q-target-1 (branch name) | proposed `feat/onboarding-baseline-landing` | **`android/onboarding-baseline-landing`** |
| Q-adr-1 (Ed25519 wrap timing) | proposed separate track | **separate track** after baseline |
| Q-doc-1 (memory reconciliation timing) | proposed after local L4 | **after PR MERGE to master** — not after local L4 |

**Round-1 new open questions (Round-2 code phase blocks on these — very small):**
- **R1-1** — Exact hunks-to-drop list for `SplashScreen.kt` (per §2.5). Requires the three-way diff to enumerate; can be done during the L2 code-phase, doesn't block architect L1 GREEN. (`PhantomMessagingService.kt` removed from this question per REDLINE-2 F5 — not touched by any net-delta.)
- **R1-2** — Exact 1-hunk AppContainer diff to be pinned before L2 code (per §2.2 + architect Q-conf-1). Doesn't block L1 GREEN.
- **R1-3** — `verifyPaparazzi` task name pinned (Gradle task naming under Paparazzi 2.0.0-alpha05 may vary). Doesn't block L1 code; pinned during L1 code phase.

---

## 9. References

**Source-of-truth commit range** (oldest first, 21 audit-branch commits; `166ec9be` dropped from history per Q-inv-1):

`24b24bcb` → `cbee4748` → `b6659282` → `4a2b2afc` → `ba73a1da` → `7ceaccf5` → `a2ea8a33` → `3ffe02f7` → `3642f3ec` → `a5d1ccf2` → `8926a4e5` → `57419912` → `dff382b4` → `1dbf913d` → `5c5d2371` → `cbdcdee8` → `5193301c` → `5797133f` → `50f1cb8f` → `86c2de99`.

**Net-delta ranges pinned (per architect REDLINE F3 + REDLINE-2 F1/F3/F6):**
- L1: `166ec9be^..3642f3ec` — 59 source files (2 top-level designv2 files + 7 components + 21 dv2 drawables + build wiring + showcase + font attribution + PhantomBadge/BadgeSnapshotTest surviving from `166ec9be`) + **2 control-plane contract docs** (this file + `c6-existing-account-contract.md` HOLD) + 11 tests + 12 goldens.
- L2: `3642f3ec..5c5d2371` — 182 files (dominated by 87 golden-touches) + 13 new dv2 drawables + `SplashScreen.kt` (hunk-by-hunk apply, drop PR #384 reverts) + 23 tests (17 added + 6 modified).
- L3: `5c5d2371..5193301c` — 35 files + 1 doc (`c6-b-key-preview-animation.md`) + 13 tests + 6 goldens.
- L4: `5193301c..86c2de99` — 48 files (includes `KeyHexFormat.kt`, moved from L1) + 4 docs (2 marked SUPERSEDED per §7 + `onboarding-stabilization-block-2026-08-11.md` + one other) + 14 tests (7 added + 7 modified) + 22 goldens.

- **Per-endpoint golden totals (not delta-touched — REDLINE-2 F3):** L1=12, L2=92, L3=97, L4=98. Final `verifyPaparazzi` covers 98 goldens.
- **Cumulative dv2 drawables at L2 end:** 34 (21 L1 + 13 L2). Total `ic_dv2_*.xml` in `86c2de99` = 34.
- **Unique test classes on `master` after L4:** 46 (verified `git diff --name-status origin/master..86c2de99 -- 'apps/android/src/androidUnitTest/**/*Test.kt' | grep '^A' | wc -l` → 46).

**Landed on master (preserve as-is):**
- PR #384 `142516a8` — icon refresh.
- PR #399 `f152a6bc` + `bf75d626` — WSS diagnostic tooling.

**Process:** [[feedback-architect-process-change-2026-08-09]], [[feedback-verify-architect-claims-2026-05-27]], [[feedback-no-merge-without-verification-2026-05-28]].

**Contract that this landing unblocks:** [`c6-existing-account-contract.md`](./c6-existing-account-contract.md) (REDLINE / HOLD).

---

## Round-1 change log vs Round-0

- **F1 (P0)** — Executive summary + L4 + acceptance-test list rewritten: Finale is `Identity created` + Continue with NO keys; both keys ONLY in Profile Advanced-collapsible. Round-0 wrongly described "dual-key labels on Finale + Profile". Verified against `FinaleConfirmationStepV2.kt` KDoc L36-47 + title L66 on `86c2de99`.
- **F2 (P0)** — Logo-flash fix corrected: **structural if-branch extraction of Welcome from `AnimatedContent`**, NOT `EnterTransition.None togetherWith ExitTransition.None`. Verified against `OnboardingFlowV2.kt:699` + KDoc L760 on `86c2de99`.
- **F3 (P0)** — Rollup method corrected: **each L is a single net-delta between phase endpoints**, not a sum of intermediate patches. Ranges pinned (§3, §9).
- **F4 (P1)** — File ownership corrected (§1 + §3):
  - `OnboardingFinalizeController` → L2 (verified via `git cat-file -e` — first appears in `57419912` = C3).
  - `IdentityRepairMarker`, `StartupRoute`, `StartupPresentation`, `StartupError` → L3 (verified — first appear in `cbdcdee8` = C6-a).
  - Late-startup fix → `MainActivity` + `navigation/StartupRouteResolver.kt` (L4), NOT `AppContainer`. `AppContainer` only touched in L2 net-delta.
  - Real paths: `ui/designv2`, `androidDebug`, `androidUnitTest/.../ui/designv2`, `androidUnitTest/snapshots/images/` — NOT `designsystem`, `debug`, `paparazzi/`.
- **F5 (P1)** — Tests aligned to final state:
  - Bullets (`·`, `•`, `○`, `.`), not deterministic hex; hex-absence assertion across all 33 states.
  - `FinaleIdentityCreatedTest` (kept), `FinaleDualKeyLabelsTest` (was deleted; do not resurrect).
  - `ProfileQrKeyCardSimplifiedTest`, `ScreenSaverTest`, `StartupRouteResolverTest`, `StartupRouteLateWriteIntegrationTest` — all pinned.
  - Profile after rotation stays `Screen.Profile`.
  - Finale absence of `Ed25519`, `X25519`, `key`, `copy` substrings.
- **F6 (P1)** — Golden discipline: **verify-only, never record** during landing. Pixel drift → block landing → visual review. Round-0 said "goldens re-verify"; that was ambiguous — pinned to verify-only now.
- **F7 (P1)** — Rollback section corrected: L2–L4 chained; before push amend + rebuild suffix; after push revert in reverse order from L4 down.
- **F8 (P1)** — Documentation allowlist added (§7): dual-key-labels + logo-flash contracts marked `SUPERSEDED` in L4 in-place; other docs treated as listed.
- **§7 → §8** — Round-0 open questions closed with architect answers; small residual Round-1 questions (R1-1..R1-3) don't block L1 GREEN.

---

## Round-2 change log (bounded amendment 2026-08-13)

Architect REDLINE-2 was arithmetic/manifest-precision — architecture already accepted from Round-1 rev 1. Seven pin-points corrected in-place:

- **F1 (P0) — L1 manifest arithmetic.** L1 top-level designv2 files corrected to **2** (`DesignV2Tokens.kt`, `DesignV2Typography.kt`) — was wrongly 3. `KeyHexFormat.kt` moved to L4 (verified first-appearance in `86c2de99`, absent in all prior endpoints). L1 dv2 drawable count corrected to **21**; L2 adds **13**; total in `86c2de99` = **34** (not 35). Updated: §0 (implicit), §1 (ownership table), §3 (L1 manifest, L2 manifest, L4 manifest), §4.4, §6 (implicit), §9 (references).
- **F2 (P0) — `166ec9be` surviving code.** Corrected: 4 files inherit `166ec9be`'s diff into net-state and MUST land in L1: `build.gradle.kts`, `libs.versions.toml`, `PhantomBadge.kt`, `PhantomBadgeSnapshotTest.kt`. Previous wording "surviving code = nothing" was WRONG. Updated: §0 executive summary, §1 (ownership table PhantomBadge row), §3 (L1 manifest — inherits `166ec9be`).
- **F3 (P0) — Goldens double-count.** Per-endpoint totals (L1=12, L2=92, L3=97, L4=98) added; per-delta touches (12/87/6/22) retained as informational. Final `verifyPaparazzi` covers **98** goldens on L4 endpoint, NOT 127. Updated: §4.4, §5.
- **F4 (P1) — Rollback chain.** L1 IS a dependency of L2–L4 (V2 imports DesignV2 tokens/components). Corrected chain: L1→L2→L3→L4; no independent L1 rollback post-merge. Updated: §4.3.
- **F5 (P1) — Ownership precision.**
  - `PhantomMessagingService.kt` removed from §2.5 conflict list — verified NOT touched by any of the 4 net-deltas.
  - L2 dv2 additions listed as full group of 13 files (was implied by singular `phantom_premium` reference).
  - L3 docs pinned: `c6-b-key-preview-animation.md`.
  - L4 test breakdown: 7 ADDED + 7 MODIFIED (was "all NEW at this endpoint").
  - Unique test-class count corrected: 46 (was ~50). Verified via `git diff --name-status origin/master..86c2de99 -- 'apps/android/src/androidUnitTest/**/*Test.kt'`.
  - Updated: §2.5, §3 (L2 manifest, L3 manifest, L4 manifest), §5, §8 R1-1.
- **F6 (P1) — Contract docs ownership.** Two contract docs added to L1 as control-plane additions: this landing contract + `c6-existing-account-contract.md` (HOLD). L1 total = 59 source-net-delta files + 2 contract docs. Updated: §3 L1 manifest.
- **F7 (P2) — Device process-death wording.** `Force-kill at various steps` replaced with concrete `adb shell am kill` (background-process kill with saved task; system LMK reclamation) scenario. Explicit note that `am force-stop` clears the task and does NOT exercise `rememberSaveable`, so is NOT used for this test. Updated: §5 final device acceptance.

No architectural changes; no code / Gradle / APK / ADB touched. Ready for L1 GREEN after this rev.
