# Logo-flash fix — contract sheet (round 0)

**Status**: architect LOGICAL GREEN 2026-08-10. Implementation
shipped as the scoped fix on top of `5193301c`; test refinement
2026-08-10 replaced the magic `advanceTimeBy(50L)` with
`advanceTimeByFrame() × 2` per architect follow-up. Commit
combined with the next visual block (dual-key labels) for one
device pass.
**Base**: `5193301c` (C6-b UX correction + naming hygiene).
**Author**: builder.
**Date**: 2026-08-10.

## Empirical invariants of current Compose version (2026-08-10)

- `fadeIn(180) togetherWith ExitTransition.None`: outgoing
  content persists on frame 1 (~16 ms after tap). Failed the
  first-frame test.
- `EnterTransition.None togetherWith ExitTransition.None`:
  outgoing content still visible on frame 1; **removed by
  frame 2 (~32 ms)**. This is Compose's `Transition<T>` +
  `KeepUntilTransitionsFinished` machinery — frame 1
  registers the target-state change; frame 2 observes the
  instantly-finished None-transition and disposes the outgoing
  node.
- Chosen approach: `EnterTransition.None togetherWith
  ExitTransition.None` (Welcome→How only). Test asserts
  outgoing gone by `advanceTimeByFrame() × 2`. Documented as
  a checkable invariant of the current version rather than a
  Compose API guarantee.

## Additional finding — double-tap advance-past-How

Independent of the transition fix, `canAdvanceFromV2` returns
`true` for BOTH `Welcome` AND `How`, so a real double-tap on
Welcome CTA fires `goNext` twice → navigation races
Welcome→How→Identity in a single gesture window. Surgical fix
scoped to Welcome CTA:

```kotlin
OnboardingStepV2.Welcome -> WelcomeStepV2(
    onContinueClick = {
        if (navigationStep == OnboardingStepV2.Welcome) goNext()
    },
)
```

Pinned by
`OnboardingFlowV2TransitionTest.get_started_double_tap_ends_at_how_not_identity`
which uses `performTouchInput { down; up; down; up }` (real
double-tap gesture) with a `goNext = step.entries[idx + 1]`
production-mirror callback. Regression → test surfaces
`[How, Identity]` in the visited-steps list.

Fixes the P1 device defect flagged by the architect after C6-b
device re-check: a brief PHANTOM-logo frame appears between
`Welcome` and `How` after tapping `Get started`.

---

## 0. Diagnostic report — root cause

### 0.1 Confirmed cause

`AnimatedContent` in
[OnboardingFlowV2.kt](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingFlowV2.kt#L727-L734)
uses:

```kotlin
AnimatedContent(
    targetState = currentStep,
    transitionSpec = {
        fadeIn(tween(180)) togetherWith fadeOut(tween(160))
    },
    label = "onboarding-step",
    modifier = Modifier.fillMaxSize(),
) { step -> … }
```

This is a **crossfade**: `AnimatedContent` keeps the outgoing
step in the composition and animates its opacity from `1 → 0`
over 160 ms while the incoming step animates from `0 → 1` over
180 ms in parallel.

Welcome's step composable
[WelcomeStepV2.kt L136-L154](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/WelcomeStepV2.kt#L136-L154)
renders a full-size PHANTOM logo (`R.drawable.dv2_logo_phantom`)
+ wordmark. During the 160-ms fade-out window the logo is
visible at partial alpha over the fading-in How step → user
perceives the "logo flash" the architect flagged.

### 0.2 Ruled-out alternative causes

- **Activity recreation**: `Get started` calls a plain
  `goNext()` closure that mutates the `currentStep`
  `MutableState`. No config-change trigger, no
  `Activity.recreate()`, no `setContent` re-entry.
  `MainActivity.setContent` runs exactly once per
  `onCreate`; `Get started` does not touch Activity state.
- **Splash re-mount**: `PhantomSplashScreen()` at
  [MainActivity.kt L265, L487](../../../apps/android/src/androidMain/kotlin/phantom/android/MainActivity.kt#L265)
  is composed only while `container == null` (before init
  completes) OR in the fallback `when` branch. Once
  `PhantomApp` composes, splash is out of the tree and cannot
  re-enter without the `container` becoming null again — which
  doesn't happen on step transitions.
- **AnimatedVisibility on the logo itself**: Welcome renders
  the logo directly (no `AnimatedVisibility`); the logo's
  visibility is entirely tied to the Welcome composable being
  in the composition tree.

The defect is therefore isolated to the shared step-transition
crossfade at OnboardingFlowV2.kt L727-734. Fix is local to
that call site.

---

## 1. Fix — instant-swap of old content + fade-in of new

Per architect contract §4 ("Prefer direct replacement or
target-only fade-in"), the transition becomes:

```kotlin
AnimatedContent(
    targetState = currentStep,
    transitionSpec = {
        fadeIn(tween(180)) togetherWith ExitTransition.None
    },
    label = "onboarding-step",
    modifier = Modifier.fillMaxSize(),
) { step -> … }
```

With `ExitTransition.None`, `AnimatedContent` removes the
outgoing content on the very next frame — no fade-out, no
overlap. The incoming step fades in over 180 ms alone. No
Welcome-logo frame is on screen after the tap.

### 1.1 Scope of the change

Applied GLOBALLY to every step transition (not just Welcome →
How), because:

- The same crossfade governs Welcome→How, How→Identity,
  Identity→Privacy, Privacy→Permissions, Permissions→Finale,
  and all backward transitions. Any of them could theoretically
  produce a similar overlap defect if the outgoing step had a
  distinctive visual anchor.
- A scoped branch (`if (Welcome→How) noExit else crossfade`)
  adds a conditional to a hot recompose path with no upside.
- The step-dots + top-bar chrome (mentioned in the existing
  KDoc comment) remain the direction cue in both directions.

### 1.2 Constraints preserved

- No pixel translation (KDoc comment "per-pixel translation
  cost" — respected; the fix uses opacity only).
- No AnimatedContent removal — the composable stays, just
  its `contentTransform` changes.
- No change to `currentStep` state machine, `goNext`,
  navigation logic, or step composables themselves.
- Both fresh install and returning-user paths unaffected.

---

## 2. Behavioural contract (from architect message)

1. `Get started` performs **exactly one** state transition
   `Welcome → How`. Callback fires once even under rapid
   double-tap.
2. The tap MUST NOT restart / recreate the Activity and MUST
   NOT invoke or remount the splash bridge.
3. The outgoing Welcome logo MUST be **absent immediately
   after the tap** — no overlapping full-screen old/new
   content.
4. Preferred: direct replacement of old content + target-only
   fade-in of new (this fix uses that pattern).
5. Step-dots + top-bar chrome remain the direction cue.

---

## 3. Files touched

Prod (M — 1 file):
- `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingFlowV2.kt`
  — change the `transitionSpec` lambda on the `AnimatedContent`
  block (single line diff:
  `fadeIn(tween(180)) togetherWith fadeOut(tween(160))` →
  `fadeIn(tween(180)) togetherWith ExitTransition.None`).
  Update the surrounding KDoc comment to note the fix and its
  rationale (Welcome-logo flash).

Tests (A — 1 new file, no existing test class fits):
- `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/OnboardingFlowV2TransitionTest.kt`
  — 4 focused tests (§5 test matrix below).

No debug variant / Showcase change (transition behaviour is
not a Paparazzi-golden concern — it's a temporal property).

---

## 4. Structural guarantees

1. **Instant removal of outgoing step**. Enforced structurally
   by `ExitTransition.None`. A regression that reintroduced
   `fadeOut(...)` on the exit slot would trip the "no logo
   frame after tap" test in §5.
2. **No Activity recreation on Get started**. Behavioural
   test asserts the initial Activity instance is the same
   before and after the tap, and the `onCreate` counter has
   not incremented (no `LaunchedEffect(Unit)` re-fires that
   could indicate re-composition of `setContent`).
3. **Single-fire callback**. `goNext` is called once even
   under a double-tap. Existing single-flight patterns in
   Compose (`clickable { … }`) already guarantee this; the
   test just pins the invariant.
4. **Back returns to Welcome**. System Back from How lands
   on Welcome — cross-check with existing back-handling
   logic (unchanged by this fix).

---

## 5. Test matrix — 4 focused tests

| # | Contract requirement | Method |
|---|----------------------|--------|
| 5.1 | Activity instance / `onCreate` callback not re-invoked on `Get started` | `activity_is_not_recreated_when_get_started_tapped` (Compose UI + Robolectric — capture Activity instance identity + a monotonic counter incremented in `LaunchedEffect(Unit)` inside PhantomApp; assert both are stable across the tap) |
| 5.2 | With frozen Compose clock, after click and at transition frames the Welcome logo is absent | `welcome_logo_absent_immediately_after_get_started_tap` (Compose UI — `mainClock.autoAdvance = false`, click Get started, advance one frame, assert no Welcome-only semantic tag / no "PHANTOM" test tag from Welcome present; assert How's title "How Phantom protects you" IS present) |
| 5.3 | Callback fires exactly once under rapid double-tap | `get_started_double_tap_fires_go_next_exactly_once` (Compose UI — inject a counter, tap twice back-to-back, assert count == 1) |
| 5.4 | System Back from How returns to Welcome | `back_from_how_returns_to_welcome_normally` (Compose UI + BackHandler — inflate at Welcome, tap Get started, verify How; press system Back, verify Welcome again) |

All four tests target BEHAVIOURAL contracts. NO Paparazzi
frames (transition is temporal, not a snapshot concern). NO
APK / full v2 batch during dev. One device check after logical
GREEN — architect greenlit combining that check with the
next completed visual block (dual-key labels) to save the
device round-trip.

---

## 6. Scope-lock reminders (from architect)

- Investigation FIRST — done in §0 above.
- Surgical fix only — 1 production file, `transitionSpec`
  lambda replaced.
- Only focused tests. No Paparazzi. No APK re-build during
  development.
- Contract sheet BEFORE code (this file — awaits GREEN).

---

## 7. Awaiting from architect

1. Confirmation of the diagnostic in §0 (root cause is
   `AnimatedContent` crossfade, not Activity/splash lifecycle).
2. GREEN on the fix scope in §1 (global `ExitTransition.None`
   for all step transitions, not just Welcome → How).
3. GREEN on the 4-test matrix in §5.

On GREEN: implement the 1-line prod change + write the 4
focused tests + one focused test run. No handoff bundle
until the fix commits alongside the next visual block per
architect fast-path guidance.
