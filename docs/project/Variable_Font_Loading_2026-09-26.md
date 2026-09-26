# Variable font loading stability

## Reproduction

At baseline `65d891a4d0fef8d7e25004b161164b220c4a1e1b`, the full
`OnboardingV2SnapshotTest` ran 15 tests with three Ghost/pricing failures.
The same snapshots passed when selected on their own. The comparison threshold
was not the cause.

Compose `ui-text-android:1.7.6` loads resource-font variations through
`TypefaceCompatApi26`, which reuses a thread-local Android `Paint`. It assigns
the new typeface, then the requested variation settings. In Android,
`Paint.setTypeface` does not reset the saved variation string, while
`setFontVariationSettings` returns early when that string is unchanged.
Loading two different families consecutively at the same weight can therefore
leave the second family at its base outline.

Source references: the installed Compose source archive,
`AndroidFontLoader.android.kt` and `PlatformTypefaces.android.kt`, and
[Android 15 Paint.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/graphics/java/android/graphics/Paint.java).

`VariableFontLoadingTest` reproduced the error before the fix: Inter 600 loaded
after Geist 600 differed from independent Android rasterization, starting at
pixel 18650. The test uses the actual bundled font resources and Compose's
resolver, with synthesis disabled, rather than a model of the loader.

## Changes

- I load bundled variable fonts through an `AndroidFont` loader that uses a fresh
  `Paint` on each font-cache miss. Compose still caches the resolved fonts.
- I keep the font binaries, requested weights, sizes, styles, and comparison
  thresholds unchanged. No Compose or Android dependency was upgraded.
- I test Geist, Inter, and JetBrains Mono at weights 400, 500, 600, and 700.
  Positive controls require regular and semibold rasterizations to differ.
- I let the pricing CTA grow beyond its normal 50 dp minimum. Correct semibold
  loading exposed clipping of the wrapped Business label at font scale 2 on a
  narrow screen. Horizontal and vertical padding preserve space around both
  lines; the label is centered.
- I cover the wrapping case with native Robolectric graphics. Reinstating the
  fixed 50 dp height fails the overflow assertion. The test also requires more
  than one line, so it cannot pass without exercising wrapping. The default
  legacy graphics mode did not reproduce real font metrics and was rejected
  when that positive control failed.

## Reviewed Goldens

I compared all 27 changed images side by side before accepting them, including
the normal/large-font and 320/411 dp layouts. The remaining 71 tracked goldens
stay byte-identical to the baseline. I did not rerecord the complete shelf.

| Snapshot group | Updated images |
| --- | ---: |
| Responsive How | 4 |
| Responsive permissions, enabled and disabled | 8 |
| Responsive privacy Standard and Private | 4 |
| Responsive Ghost locked | 2 |
| Responsive pricing at top | 2 |
| Responsive pricing at bottom | 4 |
| Single-device Ghost and pricing | 3 |
| Total | 27 |

Changes include corrected foreground weight and low-contrast cipher text, not
only foreground labels. The cipher background uses the same bundled Mono family
and derives its spacing from text metrics; its implementation was not changed.
All eight permissions images were byte-identical between full and isolated
runs after the fix. The fifteen Ghost/pricing cases also agreed between full
and scoped runs before the CTA height adjustment (eleven identical generated
images and four matching their existing goldens).

The bottom-scrolled pricing view can move more than a glyph width: its scroll
position is clamped to the end, and the now-taller CTA changes that end position.
The final Business label remains fully visible.

## Verification

Measured with JDK 21, offline dependencies, and the existing Paparazzi engine:

| Check | Result |
| --- | --- |
| Font-loading raster regression | 1/1 passed; 12 family/weight combinations |
| Pricing native-layout regression and existing semantics | 13/13 passed |
| Complete Paparazzi shelf | 99/99 passed |
| Scoped onboarding matrix, snapshots, and font regression | 76/76 passed |
| Complete shelf repeated after the scoped run | 99/99 passed |
| Golden inventory | Exactly 27 reviewed replacements; 71 unchanged |
| Font binaries and comparison configuration | Unchanged |
| Android debug build | Passed; no installation |

Both full runs and the intervening scoped run executed the test task; none was
accepted from an up-to-date or build-cache result. The shelf contains 98 image
tests and the new font raster regression, not 99 golden images.

With JDK 21 and the Android SDK configured, the focused regressions can be run as:

```sh
./gradlew :apps:android:verifyPaparazziDebug -PphantomHostTestEngine=paparazzi --tests '*VariableFontLoadingTest' --offline
./gradlew :apps:android:testDebugUnitTest -PphantomHostTestEngine=regular --tests '*PricingCtaFontScaleTest' --tests '*OnboardingV2PricingSheetSemanticsTest' --offline
```

For order sensitivity, run the complete `verifyPaparazziDebug` task with
`-PphantomHostTestEngine=paparazzi`, then repeat it with the three class filters
`*OnboardingV2ResponsiveMatrixTest`, `*OnboardingV2SnapshotTest`, and
`*VariableFontLoadingTest`, then run the complete task again without filters.
Changing the filter set forces the test task to execute instead of accepting an
up-to-date result. These commands verify goldens; none records replacements.

## Limits

This change does not finish Russian translation, expose the language picker,
implement billing, or certify every accessibility layout. Existing awkward
word wraps at font scale 2 were not silently treated as a completed app-wide
accessibility audit. No APK was installed and no phone data was cleared in this
round. Device validation and the remaining localization/copy review stay open.
