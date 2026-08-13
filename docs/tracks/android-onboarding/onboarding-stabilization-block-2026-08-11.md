# Onboarding stabilization block — 2026-08-11

Final scoped block over onboarding surfaces before visual freeze and pivot to production Direct-WSS on Tele2 / Yota. Fixes three defects the architect flagged after the dual-key-labels device pass:

1. **Structural Welcome→How logo flash** — the earlier `EnterTransition.None togetherWith ExitTransition.None` inside a shared `AnimatedContent` still kept the outgoing Welcome tree for one composition frame via `KeepUntilTransitionsFinished`, and users perceived the PHANTOM logo flash. The fix is structural: render Welcome by a plain `if (currentStep == Welcome)` guard **outside** `AnimatedContent`. The moment state flips to How, the `if` branch disposes Welcome in the same frame; `AnimatedContent` mounts How for the first time with no overlap. Every other forward/back transition still runs the symmetric `fadeIn(180) togetherWith fadeOut(160)` inside `AnimatedContent`.

2. **Profile route drops on rotation** — `MainActivity.currentScreen` was `remember { mutableStateOf<Screen?>(null) }` (plain `remember`), so an Activity recreation reset it to `null`; the startup `LaunchedEffect` then re-derived `Screen.ChatList` from disk and dropped the user out of Profile / Settings / any detail screen into Chats on every rotation. Introduced `phantom.android.navigation.ScreenSaver` (custom `Saver<Screen?, List<Any?>>` covering every variant of the sealed class) and switched `currentScreen` to `rememberSaveable(stateSaver = ScreenSaver)`.

3. **Finale + Profile UX simplification** — the dual-key labels shape (two cards, two short IDs, two Copy buttons on the main surface) shipped too much cryptographic detail into the casual user's main flow. Per architect verdict:

   * **Finale** confirms identity without exposing any raw key material:
     * Title `Identity created`.
     * One-line explanation `Your identity is created and stored on this device.`
     * Single `Continue` CTA. No raw hexes, no short IDs, no Copy buttons.

   * **Profile QR card**:
     * Header `My Phantom QR`.
     * Single primary action `Share my Phantom contact`. QR/Share payload byte-exact `${username}:${publicKeyHex}` (X25519 only) — same wire format as before.
     * Collapsible section `Advanced cryptographic details` (default collapsed). When expanded, renders both public keys labelled `Public key · Ed25519 (signing)` and `Public key · X25519 (encryption)` with a shared `Copy public key` action per row and an explainer line reminding the reader these are PUBLIC values. Ed25519 hex never leaves through Share.
     * `X25519 short ID` moved out of the Connection card into the same Advanced block — casual users no longer see hex fragments in the main Connection metadata.

## Focused tests

New tests (all GREEN in isolation):

* `phantom.android.navigation.ScreenSaverTest` — 9 tests, one per variant plus null and `Screen.Profile`-specific round-trip pin (load-bearing for the rotation fix).
* `phantom.android.screens.onboarding.v2.FinaleIdentityCreatedTest` — 3 tests: title + explanation + Continue; Continue fires once; no `Copy` / `short ID` / `Ed25519` / `X25519` / `fingerprint` / `Public key` tokens in the visible tree.
* `phantom.android.screens.profile.ProfileQrKeyCardSimplifiedTest` — 5 tests: main surface exposes only `My Phantom QR` + `Share my Phantom contact` with all key material and old dual-key labels absent; expanding Advanced reveals two `Public key ·` labelled rows plus the `Copy public key` × 2 actions and the "PUBLIC keys — safe to share" explainer; Share payload byte-exact `${username}:${publicKeyHex}`; per-row Copy returns the correct full hex.

Updated:

* `OnboardingFlowV2TransitionTest` — harness now mirrors the structural `if / else AnimatedContent` shape; test #2 asserts logo absent via `waitForIdle` (no clock manipulation needed under the structural fix).
* `OnboardingV2ScrollableCtaReachabilityTest` — Finale reachability test now targets `Identity created` title + `Continue` CTA (no Copy buttons any more).
* `OnboardingV2FinalizeContractTest` — retired the `copyFullHexToClipboard` clipboard-contract tests along with the helper; controller state machine + shared formatter tests unchanged.

Retired:

* `FinaleDualKeyLabelsTest` (10 tests, no longer applicable).
* `ProfileQrKeyCardDualKeyLabelsTest` (11 tests, no longer applicable).
* `OnboardingFlowV2Internal.onboardingStepContentTransform()` extension (Welcome→How special case gone with the structural fix).
* `FinaleConfirmationStepV2.copyFullHexToClipboard`, `FinaleKeyCard`, `ShortKeyIdChip`, `KeyLossWarningBanner`, `CopyKeyButton` helpers (dead after Finale simplification).
* `ProfileScreen.ProfileKeyRow` (two-Copy-button row helper, replaced by the Advanced-section `AdvancedPublicKeyRow`).

## Cumulative test-infra flake

The pre-existing cumulative `AppNotIdleException` flake in the full onboarding-v2 sweep still reproduces on the base tree (see `project_android_test_infra_appnotidleexception_cumulative_2026_08_11.md`). This block does not attempt to solve it — the fix belongs to a separate test-infra track. All new/updated tests here pass in isolation, which is the accepted validation gate for this block per architect direction.

## Paparazzi

`ShowcaseOnboardingFinaleConfirmation` reshaped alongside `FinaleConfirmationStepV2`; the golden re-record covers `OnboardingV2SnapshotTest.onboarding_v2_finale_confirmation` + all four `OnboardingV2ResponsiveMatrixTest.finale_confirmation` cells. No Profile Paparazzi golden exists yet — the simplification is verified structurally by `ProfileQrKeyCardSimplifiedTest`; a dedicated Profile snapshot is intentionally out of scope for this block.
