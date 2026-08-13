# C6-b — Step 2 key-preview card animation

**Status**: architect LOGICAL GREEN 2026-08-10. UX correction
2026-08-10 (post-LOGICAL-GREEN device review): decorative
character source swapped from 32 hex glyphs to 32 masked
bullets `•` after architect flagged that the hex look could
be mistaken for a real key even with the `WILL BE
GENERATED / READY TO CREATE` labels. All 16 focused tests
GREEN + 20 v2 goldens verify GREEN after the correction.
**Base**: `cbdcdee8` (C6-a FINAL GREEN). Separate commit atop
`cbdcdee8`, not amend.
**Author**: builder.
**Date**: 2026-08-10 (round 0 → FINAL GREEN same day).

Canonical design source:
`design_handoff_phantom_messenger/Onboarding.dc.html` — 42 221 B,
567 lines. Every timing / easing / animated property in the
motion table below is quoted with its source line — nothing
invented.

Product framing (architect 2026-08-10):

> "Canonical HTML — источник motion-дизайна, но не источник
> продуктовой логики, потому что фактический ключ создаётся
> только при финализации."

---

## 0. Motion table — extracted, NOT invented

| # | Trigger | Duration | Delay | Easing | Animated properties | Terminal state | Source |
|---|---------|---------:|------:|--------|---------------------|----------------|--------|
| 1 | Card mounts into Step 2 body | 500 ms | 160 ms | `cubic-bezier(.22,1,.36,1)` | `opacity: 0 → 1` + `translateY: +10 px → 0` (keyframe `phFadeUp`) | Card at rest position | L376 `phFadeUp .5s … .16s` + L24 keyframe |
| 2 | Glyph shuffle — per tick | 45 ms | 0 | discrete step | Each still-unsettled slot re-randomised over `·•○.` (bullet alphabet — UX correction 2026-08-10; canonical uses `[0-9A-F]`); one slot settles into terminal `•` per tick | 32 bullet-slot decorative content | L258 `setInterval(…, 45)` + L259-267 (motion) — glyph source overridden by UX correction |
| 3 | Glyph shuffle — total | **1440 ms** = 32 × 45 ms | 0 | — | 32 slots settle sequentially, cumulative | All 32 slots terminal `•` → `keyDone: true` | L254 + L267 (motion) — glyph source overridden by UX correction |
| 4 | Card border colour | 400 ms | 0 | `ease` | `border-color: #1F242C → rgba(0,212,255,.28)` (Cyan-tinted) | Cyan border | L377 `transition: border-color .4s ease` |
| 5 | Glyph text colour (both lines) | 300 ms | 0 | `ease` | `color: #8b97a8 → #F5F7FA` | White key text | L367 `transition: color .3s ease` |
| 6 | Status dot background | 300 ms | 0 | `ease` | Dot bg cyan-glow `rgba(0,212,255,.5) → green-glow rgba(34,197,94,.5)` | Green dot | L369 `transition: background .3s ease` |
| 7 | Status label text swap | instant at terminal | 0 | — | Label text `WILL BE GENERATED → READY TO CREATE` (contract wording; canonical wording `GENERATING → GENERATED` deliberately deviated per §1) | Terminal label | L380 ternary on `done` |

Implementation note (architect 2026-08-10):

> "Glyph shuffle не делать как 32 отдельных coroutine/delay. Один
> progress-аниматор на 1440 ms, а количество установившихся
> символов вычислять дискретно через шаг 45 ms. Кадры и target
> precomputed один раз, без работы на каждом draw-frame."

Concrete: single `Animatable<Float>.animateTo(1f, tween(1440ms, easing = LinearEasing))`
computed once at start of animation. `settledCount = (progress * 32).toInt().coerceIn(0, 32)`.
Target string and per-tick randomiser both seeded with a fixed
seed at card composition time — deterministic across runs.

`_target` structure (L250-252): 8 groups × 4 hex characters,
`HHHH HHHH · HHHH HHHH \n HHHH HHHH · HHHH HHHH`. Total 32
hex. Not 64. Not real Ed25519 length — canonical already
truncates.

Row 1 (`phFadeUp` mount animation) — Round-1 P1-3 fix: previous
shape claimed this was "inherited from Spacer + placement" which
was incorrect (Spacer animates nothing). Round-1 implements
`phFadeUp` explicitly via a dedicated `Animatable<Float>` in
[IdentityKeyPreviewAnimatedCard](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/IdentityKeyPreviewAnimation.kt#L155):

- `mountAnimatable` starts at 0f.
- `LaunchedEffect(Unit)` fires: `delay(160)` +
  `animateTo(1f, tween(500, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))`.
- Applied to the card outer via `Modifier.graphicsLayer` on
  `alpha` (0→1) and `translationY` (10dp→0).
- Skipped entirely under `LocalInspectionMode = true` (frame
  helper defaults to `mountProgress = 1f`) so Paparazzi
  captures the fully-mounted terminal position without
  needing to advance the clock.

Row 4-6 (border / dot / glyph-text colour) — Round-1 P1-3 fix:
`animateColorAsState` now uses
`CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)` — the CSS `ease`
keyword. Previous shape used Compose's default
`FastOutSlowInEasing`, which differs perceptibly.

---

## 1. Deliberate deviations from canonical design (architect
   confirmed 2026-08-10)

| # | Canonical | This commit |
|---|-----------|-------------|
| 1 | Copy key button (L386, writes glyphs to clipboard) | Removed. |
| 2 | Save backup button + toast (L387, L301) | Removed. |
| 3 | Regenerate key button (L388, L302) | Removed. |
| 4 | "Lose this key and the account is gone" warning banner (L389-391) | Removed. |
| 5 | Trigger = step entry (L274 `if(s===1) startKeyGen()`) | Trigger = username `invalid → valid` transition. |
| 6 | Label wording `ED25519 · GENERATING → ED25519 · GENERATED` (L380) | Label wording `ED25519 · WILL BE GENERATED → ED25519 · READY TO CREATE`. |

Rationale (architect): actual key is created only in the
finalize coroutine of `OnboardingFlowV2`; Step 2 has no crypto
state. All six affordances above imply a real key exists on
Step 2 — banned here.

---

## 2. Resolved contract questions

### 2.1 Decorative glyph character set + count — SUPERSEDED by UX correction (2026-08-10)

**Original agreement (option A) — SUPERSEDED**: 32 hex glyphs
in canonical layout `HHHH HHHH · HHHH HHHH \n HHHH HHHH · HHHH
HHHH`, seeded target + shuffle table for byte-stability.

**UX correction (2026-08-10, post-LOGICAL-GREEN device review)**:
architect on-device test found that even with the labels
`WILL BE GENERATED / READY TO CREATE` the hex visual reads as a
real key — misleading. Corrected character source:

- Shuffle alphabet: `·` (U+00B7 middle dot), `•` (U+2022
  bullet), `○` (U+25CB white circle), `.` (U+002E period) —
  four non-hex decorative glyphs. All render at equal cell
  width in `DesignV2FontMono`, so slot layout stays byte-stable.
- Terminal glyph: `•` (bullet) at all 32 slots — visually
  identical to a masked password field. Terminal state renders
  `•••• •••• · •••• ••••\n•••• •••• · •••• ••••`.
- Slot layout preserved verbatim from canonical (32 positions
  in 8 groups of 4, `HHHH HHHH · HHHH HHHH \n` × 2). Only the
  character source flipped from hex to bullets.

Retained constraints (unchanged):

- Fixed deterministic per-tick shuffle table (`Random` seeded
  by `KEY_PREVIEW_DETERMINISTIC_SEED xor 0xA5A5_A5A5L`); NOT
  random per frame; NOT random per invocation.
- Target is a constant `List(32) { '•' }` — no per-position
  seeding needed since all slots share the terminal glyph.
- Independent of username content.
- Never persisted to storage.
- Never copyable (no `Copy` affordance; no `Clipboard.setText`
  call from this composable).
- Hidden from accessibility (see §4 guarantees).
- Never called a "public key" or "identity key" in any
  user-visible string or `contentDescription`.

Test coverage: `KeyPreviewAnimationPhaseTest.target_is_exactly_32_terminal_bullets`
+ `renderShuffleGlyphs_at_progress_1_shows_only_terminal_bullets_and_never_hex`
pin the bullet-only invariant. Any regression that reintroduces
hex characters into the target or the shuffle table fails-red.

### 2.2 Action row + warning — architect: **remove completely**

No Copy / Save backup / Regenerate row. No warning banner. No
disabled placeholders. No "Coming soon". These affordances
simply do not render on Step 2 at all.

### 2.3 Recreation policy — architect: **B (jump to terminal)** — Round-1 P1-1 fix

Round-1 fix: previous shape held `Animatable` in plain
`remember`, so recreation reset progress to 0 and `waitForIdle`
in the state-restoration test re-played the full 1440 ms
shuffle → passed misleadingly. Round-1 uses `rememberSaveable`
for a `shuffleStartedInCurrentValidity: Boolean` flag; the
`Animatable` initialises at `1f` when the flag is `true` on
first composition after restore. This makes recreation jump
DIRECTLY to Terminal (`progress = 1f`) without any Animatable
advancement.

Cancellation semantics preserved: `LaunchedEffect(usernameValid)`
on `false` resets the flag AND snaps progress to 0, so a
subsequent `false → true` flip within the same composition
lifecycle DOES trigger a fresh shuffle.

Test coverage: `terminal_state_survives_activity_recreation_without_reshuffling`
now freezes `mainClock.autoAdvance = false` IMMEDIATELY after
`emulateSavedInstanceStateRestore` and asserts Terminal is
displayed on the very first post-restore frame. A regression
that reverted `rememberSaveable` would render Running (WILL BE
GENERATED) on that frame and the assertion would fail.



On `Activity` recreation (rotation, config change, process
death restore), the card jumps DIRECTLY to terminal state if
the last render had `usernameValid = true` AND a shuffle had
been triggered. It NEVER re-runs the shuffle across recreation.

Within a live composition (no recreation), a `true → false`
username flip cancels the shuffle and resets Idle. A
subsequent `false → true` flip DOES fire a fresh shuffle. So
the "one shuffle per validity streak" property is
per-validity-session — NOT "at most one Idle → Terminal per
composition lifecycle" as an earlier draft of this section
incorrectly stated (mini-round P2 correction).

Interrupted mid-flight animations (destroyed before terminal)
also restore to terminal on recreation. No partial re-shuffle.

### 2.4 Reduced motion — architect: **use Compose/platform mechanism, not `Settings.Global`** — Round-1 P1-2 fix

Round-1 fix: previous shape acknowledged the reduced-motion
test wasn't actually injecting scale=0f. Round-1 adds an
optional `testMotionDurationScale: MotionDurationScale? = null`
parameter to
[IdentityKeyPreviewAnimatedCard](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/IdentityKeyPreviewAnimation.kt#L155).
Production callers pass `null` (inherits ambient scale). Test
passes a real `MotionDurationScale { scaleFactor = 0f }`; the
`LaunchedEffect` wraps the `animateTo` in `withContext(scale)`
so `Animatable` reads scale=0 from its coroutine context and
completes on the first dispatch.

Test coverage: `reduced_motion_scale_zero_renders_visible_terminal_on_first_frame_when_username_valid`
freezes `mainClock.autoAdvance = false`, injects the zero-
scale, advances ONE frame (16 ms), asserts
`ED25519 · READY TO CREATE` is displayed AND
`capturedMountProgress == 1f` via the `testMountProgressSink`
probe seam (mini-round P1) — visibility, not just semantic
presence. No intermediate Running render is possible under
scale=0 because the animation (mount + shuffle) collapses to
0 ms.



Do NOT read `Settings.Global.ANIMATOR_DURATION_SCALE` directly.
Route through `androidx.compose.animation.core.Animatable` +
`tween(...)`, which automatically respects
`MotionDurationScale` from the coroutine context (which in
turn respects the platform animator scale). When system scale
is 0f, `Animatable.animateTo(1f, tween(1440ms))` completes on
the first frame → `settledCount = 32` → card renders terminal
state instantly.

### 2.5 Continue gating — architect: **corrected expression**

Continue-CTA `enabled` value MUST be the terminal expression
already used in [IdentityKeyStepV2.kt:94](d:/VL%20Stories%20Studio/Phantom-android-ui/apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/IdentityKeyStepV2.kt#L94):

```kotlin
enabled = validateUsernameV2(formState.username) == UsernameValidationV2.Valid
```

Enum `UsernameValidationV2` lives at
[OnboardingStateV2.kt:194](d:/VL%20Stories%20Studio/Phantom-android-ui/apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/OnboardingStateV2.kt#L194).

Animation state is NEVER referenced in the CTA-enabled
expression. Both invariants pinned by behavioural tests
(§6.5, §6.6):

- `usernameValidation != Valid` + `animation = Terminal` → Continue DISABLED.
- `usernameValidation == Valid` + `animation = Running(mid-flight)` → Continue ENABLED.

Architect explicitly ruled that these are BEHAVIOURAL Compose
tests only; NO extra Paparazzi golden for the pair. The
existing 4 Identity goldens + the 3 new animation-frame
goldens are the only visual coverage.

### 2.6 Durable location — architect: **repo `docs/tracks/`**

This file. Committed with the C6-b commit.

---

## 3. Files touched — under architect's caps

Caps (architect 2026-08-10): max 2 production files (revised
2026-08-10 mini-round to **2 new + 1 modified** — see §3.1
"Split note" — to keep every file under the 500-line project
limit), max 3 test classes, up to 13 focused tests, 3 Paparazzi
frames.

### 3.1 Production (2 new + 1 modified — revised cap)

- **NEW** `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/IdentityKeyPreviewAnimation.kt`
  (267 lines) — animation controller only:
  - `internal enum class KeyPreviewAnimationPhase { Idle, Running, Terminal }`
    (pure state model).
  - `@Composable internal fun IdentityKeyPreviewAnimatedCard(usernameValid, testMotionDurationScale?, testMountProgressSink?)`
    — the animated card. Owns all animation state internally
    via `Animatable` + `LaunchedEffect` + `rememberSaveable`.
  - Mount + shuffle timing constants + `PhFadeUpEasing`.
  - Pure `nextKeyPreviewPhase` reducer (test seam).

- **NEW** `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/IdentityKeyPreviewCardFrame.kt`
  (336 lines) — pure render frame:
  - `@Composable @VisibleForTesting internal fun IdentityKeyPreviewCardFrame(progress: Float, phase: KeyPreviewAnimationPhase, mountProgress: Float = 1f)`
    — fixed-progress card frame used by Paparazzi goldens AND
    by the `LocalInspectionMode` short-circuit in the animated
    card.
  - `KEY_PREVIEW_CARD_A11Y_TAG`, `KEY_PREVIEW_IDLE_PLACEHOLDER`,
    `KEY_PREVIEW_HEX_GLYPH_COUNT` public consts.
  - `CssEaseEasing` (private) for the three color transitions.
  - Deterministic seeded target hex generator +
    shuffle-tick-table + `renderShuffleGlyphs` +
    `settledCountFor`.

- **MODIFIED** `apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/IdentityKeyStepV2.kt`
  — swap the current static `IdentityKeyPreviewCard(usernameValid)`
  call site with `IdentityKeyPreviewAnimatedCard(usernameValid = canAdvance)`.
  Remove the now-unused private `IdentityKeyPreviewCard`
  composable. KDoc updated. Nothing else in this file changes.

**Split note (mini-round 2026-08-10 P2-1):** initial Round-1 shape
combined animation + render + generators into a single 546-line
`IdentityKeyPreviewAnimation.kt`, which exceeded the project's
500-line-per-file limit. Architect greenlit a 2-new file split
during the mini-round as an intentional deviation from the
original "1 new + 1 modified" cap. Public API surface unchanged;
the split is purely file-layout.

### 3.2 Debug variant (showcase) — required for Paparazzi frames

Adding new Showcase entries in `DesignV2Showcase.kt` is
required because the 3 new Paparazzi frames render the animated
card at 3 different progress values, which requires a
composable that accepts a fixed `progress: Float` parameter
(the animated card itself hides progress inside its state).

The test-only helper is `@VisibleForTesting @Composable internal
fun IdentityKeyPreviewCardFrame(progress: Float, phase:
KeyPreviewAnimationPhase, mountProgress: Float = 1f)`. It lives
in `IdentityKeyPreviewCardFrame.kt` (see §3.1). The Showcase
entries live in the debug variant of `DesignV2Showcase.kt` —
that file is debug-only, NOT production, so it does not count
against the production cap.

- **MODIFIED** `apps/android/src/androidDebug/kotlin/phantom/android/ui/designv2/showcase/DesignV2Showcase.kt`
  — 3 new Showcase entries:
  `ShowcaseOnboardingIdentityKeyPreviewFrame00`, `_50`, `_100`.
  Identity showcase frame wrapped in
  `CompositionLocalProvider(LocalInspectionMode provides true)`
  (Round-1 P1-4).

### 3.3 Test (3 classes max)

- **NEW** `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/KeyPreviewAnimationPhaseTest.kt`
  — pure JVM enum + transition helper tests.
- **NEW** `apps/android/src/androidUnitTest/kotlin/phantom/android/screens/onboarding/v2/IdentityKeyPreviewAnimatedCardTest.kt`
  — Compose UI tests via clock control + semantics +
  restoration + reachability + Continue-gating invariants.
- **NEW** `apps/android/src/androidUnitTest/kotlin/phantom/android/ui/designv2/OnboardingV2Step2AnimationSnapshotTest.kt`
  — Paparazzi, 3 frames (0% / 50% / 100%).

### 3.4 Existing goldens — expected byte-for-byte state

Per architect: "4 существующих Identity golden должны остаться
byte-identical, кроме заранее перечисленных intentional изменений".

| Golden | Current shape | Under C6-b |
|--------|---------------|------------|
| `onboarding_v2_identity_key_empty.png` | Idle card (dashes, neutral dot, WILL BE GENERATED) | **byte-identical** — `usernameValid = false` → Idle phase → same render. |
| `onboarding_v2_identity_key_short.png` | Idle card (username = "al" invalid) | **byte-identical** — same reasoning. |
| `onboarding_v2_identity_key_invalid_chars.png` | Idle card (username = "al!ce" invalid) | **byte-identical** — same reasoning. |
| `onboarding_v2_identity_key_valid.png` | Static card: dashes + neutral dot + `READY TO CREATE` label swapped instantly on validity (visually inconsistent — label said "ready" while body still showed dashes) | **INTENTIONAL CHANGE (Round-1 fix)** — animation renders steady **Terminal** state (32-hex target chars + cyan-tinted border + green dot + white text + `READY TO CREATE`). Golden re-recorded. |

Round-1 P1-4 fix (2026-08-10): the previous shape of this
handoff had the `valid` golden capture the transient
`Running-at-t=0` state because Paparazzi 2.0.0-alpha05 does NOT
automatically set `LocalInspectionMode.current = true` inside
`paparazzi.snapshot { }`, so the live-render branch of
[IdentityKeyPreviewAnimatedCard](../../../apps/android/src/androidMain/kotlin/phantom/android/screens/onboarding/v2/steps/IdentityKeyPreviewAnimation.kt#L155)
took effect and rendered whatever was on screen at first frame.

Round-1 fix: the Identity showcase entries in
[DesignV2Showcase.kt](../../../apps/android/src/androidDebug/kotlin/phantom/android/ui/designv2/showcase/DesignV2Showcase.kt#L810)
now wrap their content in
`CompositionLocalProvider(LocalInspectionMode provides true)`,
which flips the composable into its `if (inspection)`
short-circuit branch. Under this wrap:
- `usernameValid = false` (empty / short / invalid_chars
  goldens) → phase = Idle → dash placeholder + neutral dot +
  `WILL BE GENERATED`. **Byte-identical to pre-C6-b goldens**
  (verified in the Round-1 verifyPaparazziDebug run).
- `usernameValid = true` (valid golden) → phase = Terminal →
  32 target hex + cyan-tinted border + green dot + white text
  + `READY TO CREATE`. **Terminal steady state** — a stable
  design baseline that does NOT depend on Paparazzi clock or
  first-frame Animatable value.

Independent motion sampling continues via the three
`OnboardingV2Step2AnimationSnapshotTest` frames (`frame_00pct`
= Running@0, `frame_50pct` = Running@50%, `frame_100pct` =
Terminal) — they invoke `IdentityKeyPreviewCardFrame(progress,
phase)` directly, so they are independent of any inspection-
mode plumbing.

---

## 4. Structural guarantees (no regex source-contract tests
   per architect 2026-08-10)

Guarantees are enforced by BEHAVIOURAL Compose / pure tests
listed in §6. NO regex tripwires on source files for this
visual feature.

1. **No crypto surface reachable from Step 2.** Enforced by
   review + the absence of any `IdentityManager`/`createOrLoad`/
   `KeyMaterial` import in the two touched production files.
2. **No copyable / persisted key surface.** Card holds glyphs
   only in `mutableStateOf` local to composition; NOT in
   `rememberSaveable`. No `Clipboard`/`ClipboardManager` call
   anywhere in the new file. Behavioural: `IdentityKeyPreviewAnimatedCardTest`
   asserts no Copy CTA node exists.
3. **No card-size layout shift during motion.** Card outer size
   fixed by `.fillMaxWidth()` + explicit padding on inner
   Column. Glyph text uses fixed-width monospace + fixed line
   count (2). Border-color / text-color / dot-color / label
   swap all happen IN PLACE. Behavioural: Paparazzi frame
   pixel-diffs enforce this.
4. **Continue enabled iff username valid.** Behavioural tests
   `IdentityKeyPreviewAnimatedCardTest.continue_disabled_when_username_invalid_even_after_animation_terminal`
   + `continue_enabled_when_username_valid_even_during_animation_running`.
5. **A11y stable summary.** Card node's
   `contentDescription = "Identity key preview"` (or
   equivalent stable string). Glyph text carries
   `Modifier.clearAndSetSemantics { }` so a11y trees see NO
   mid-shuffle glyph content. Behavioural test asserts this.
6. **Cancellation contract.** When Step 2 leaves composition,
   the driving `LaunchedEffect` cancels; the phase reducer
   sees no post-cancel writes. When `usernameValid` flips
   `true → false` mid-animation, phase resets to `Idle`;
   animation cancels. Behavioural test asserts.
7. **Reduced motion.** Under `MotionDurationScale.scaleFactor == 0f`,
   card renders terminal state on first frame if
   `usernameValid = true`. Behavioural test injects a custom
   `MotionDurationScale` in the composition and asserts
   settled state.

---

## 5. Behavioural contract — state table

| Phase | Card border | Glyph text (32 chars) | Status dot | Status label | Continue |
|-------|-------------|-----------------------|------------|--------------|----------|
| Idle (`usernameValid = false`) | `#1F242C` neutral | Placeholder `— — — —` × 32 (contract retains the dash placeholder for Idle; the shuffle bullets appear only during Running / Terminal) | Neutral grey (`DesignV2Tokens.Colors.TextQuaternary`) | `ED25519 · WILL BE GENERATED` | disabled |
| Running (`usernameValid` just flipped `false → true`) | Cross-fades `#1F242C → rgba(0,212,255,.28)` over 400 ms | Decorative-glyph shuffle: 32 slots pulling from `·•○.`, one settles to terminal `•` per 45 ms tick over 1440 ms. Colour stays grey during shuffle (transitions to white at Terminal). NO HEX at any progress (UX correction 2026-08-10) | Cyan glow `rgba(0,212,255,.5)` (unchanged during shuffle) | `ED25519 · WILL BE GENERATED` (unchanged during shuffle) | **ENABLED** immediately (never gated on animation) |
| Terminal (`progress == 1f`) | `rgba(0,212,255,.28)` cyan | 32 solid bullets `•` (masked pattern) laid out as `•••• •••• · •••• ••••\n•••• •••• · •••• ••••`, colour `#F5F7FA` white (300 ms ease). Visually unambiguous "not-yet-generated" mask; cannot be mistaken for a real key | Green glow `rgba(34,197,94,.5)` (300 ms ease) | `ED25519 · READY TO CREATE` (instant swap) | ENABLED |
| Cancelled (`usernameValid` flipped `true → false` mid-animation) | Reverts to `#1F242C` neutral over 400 ms | Reverts to placeholder `— — — —` × 32 | Reverts to neutral grey | Reverts to `WILL BE GENERATED` | disabled |
| Recreated (Activity destroyed then recreated, prior render was Terminal) | `rgba(0,212,255,.28)` (instant, no re-fade) | 32 terminal bullets (instant, no re-shuffle) | Green glow (instant) | `READY TO CREATE` (instant) | ENABLED |
| Reduced motion (`MotionDurationScale = 0f` + `usernameValid = true`) | `rgba(0,212,255,.28)` on first frame | 32 terminal bullets on first frame | Green glow on first frame | `READY TO CREATE` on first frame | ENABLED |

---

## 6. Test matrix — max 13 focused tests

Rebalance during build (2026-08-10): the reducer's rendering
helpers (`renderShuffleGlyphs`, `settledCountFor`, target
determinism) needed pure-JVM byte-stability pins to protect
Paparazzi frame determinism. Added 3 pure-JVM helper tests
(6.5-6.7); dropped 3 mid-flight clock-controlled Compose tests
(previously 6.7-6.9) because those checks are now fully covered
by the pure-JVM helpers running against the same render function
the composable calls. Total kept at 13 focused + 3 Paparazzi.

| # | Contract requirement | Test file | Method | Type |
|---|----------------------|-----------|--------|------|
| 6.1 | Reducer: Idle when username invalid | `KeyPreviewAnimationPhaseTest` | `phase_stays_Idle_when_username_invalid` | Pure JVM |
| 6.2 | Reducer: Idle → Running on validity flip | ″ | `phase_transitions_Idle_to_Running_on_username_becoming_valid` | Pure JVM |
| 6.3 | Reducer: Running → Idle on cancellation | ″ | `phase_transitions_Running_to_Idle_on_username_becoming_invalid_mid_run` | Pure JVM |
| 6.4 | Reducer: Terminal is absorbing under sustained validity (from Terminal, further `progressReachedOne=true` under `usernameValid=true` stays Terminal). Note: this reducer property is composition-in-place; a `true → false → true` transition cycle DOES fire a fresh shuffle by design (§2.3 in-composition rule). | ″ | `phase_reaches_Terminal_once_and_stays_Terminal_across_repeated_valid_flips` | Pure JVM |
| 6.5 | Target: 32 terminal bullets `•`, no hex (UX correction 2026-08-10 byte-stability + no-real-key-look pin) | ″ | `target_is_exactly_32_terminal_bullets` | Pure JVM |
| 6.6 | settledCount discretisation over 32 slots | ″ | `settledCountFor_maps_progress_to_discrete_32_slots` | Pure JVM |
| 6.7 | renderShuffleGlyphs at progress 1 matches target verbatim | ″ | `renderShuffleGlyphs_at_progress_1_matches_target_verbatim` | Pure JVM |
| 6.8 | Continue disabled for canonical invalid inputs (empty / short / bad chars) | `IdentityKeyPreviewAnimatedCardTest` | `continue_disabled_when_username_invalid` | Compose UI |
| 6.9 | Continue enabled at three distinct animation ticks (Running-start, Running-mid, Terminal) | ″ | `continue_stays_enabled_across_multiple_animation_ticks_when_username_valid` | Compose UI (clock-frozen) |
| 6.10 | State restoration terminal survives Activity recreation without re-shuffle (Round-1 P1-1: clock frozen after restore) | ″ | `terminal_state_survives_activity_recreation_without_reshuffling` | Compose UI (StateRestorationTester + clock-frozen post-restore) |
| 6.11 | Semantics: merged config asserts stable ContentDescription + header/footer + no target-glyph substring (Round-1 P2-5: inspects real merged config) | ″ | `card_a11y_reports_stable_summary_and_no_glyph_content_in_merged_config` | Compose UI |
| 6.12 | Reachability of full Step 2 at 320 × 640 dp fs=2.0 (title above fold + card above fold + input via scroll + fixed-bottom CTA) — Round-1 P2-6: renders whole step, not just card | ″ | `full_step2_at_320dp_fs2_card_input_and_cta_all_reachable` | Compose UI |
| 6.13 | Reduced motion — real `MotionDurationScale(scaleFactor=0f)` injection via optional composable param covers BOTH mount and shuffle; `testMountProgressSink` probe asserts `mountProgress == 1f` on the first frame (Round-1 P1-2 + mini-round P1) | ″ | `reduced_motion_scale_zero_renders_visible_terminal_on_first_frame_when_username_valid` | Compose UI (clock-frozen + zero-scale + probe) |

Paparazzi frames (3, separate class):

| # | Frame | Method | Class |
|---|-------|--------|-------|
| 6.14 | 0% (Idle → shuffle about to start) | `identity_key_preview_frame_00pct` | `OnboardingV2Step2AnimationSnapshotTest` |
| 6.15 | 50% (16 glyphs settled) | `identity_key_preview_frame_50pct` | ″ |
| 6.16 | 100% (all 32 settled + terminal chrome) | `identity_key_preview_frame_100pct` | ″ |

Total: 13 focused tests (7 pure JVM + 6 Compose UI) + 3
Paparazzi frames = 16 total. Under all caps.

Note on 6.14/6.15/6.16: these frames use the test-only
composable `IdentityKeyPreviewCardFrame(progress: Float, phase:
KeyPreviewAnimationPhase, mountProgress: Float = 1f)` so
Paparazzi can render a fixed-progress state deterministically
without driving Compose animation clocks (which are not
available under Paparazzi's one-shot render).

---

## 7. Scope-lock reminders (from architect)

- No `IdentityManager`, no `createOrLoad`, no crypto write.
- No fake key at all — 32 masked bullets `•` (UX correction 2026-08-10). No hex characters at any animation phase.
- No mutation to cipher, transitions, finalize, permissions,
  main screen.
- Separate commit on top of `cbdcdee8`; no C6-a amend.
- During dev: compile + only C6-b tests. NO full v2 batch.
- Handoff: patch + this contract sheet + focused logs + 3
  Paparazzi frames. NO APK, NO full shelf.
- After logical GREEN: 1 targeted Paparazzi verify + 1
  `assembleDebug` + 1 APK + 1 on-device animation check.
- C6-c..e AND C6-existing-account HOLD.

---

## 8. Downstream — C6-existing-account (deferred, not part of C6-b)

Recorded in memory as `project_android_onboarding_c6_existing_account_gap_2026_08_10.md`.
Order after C6-b:

1. C6-b (this commit).
2. **C6-existing-account** — "I already have an account" +
   real backup / QR restore flow.
3. C6-c / C6-d — three permission toggles.
4. C6-e — androidInstrumentedTest harness + on-device final
   check.

Not touched by C6-b.
