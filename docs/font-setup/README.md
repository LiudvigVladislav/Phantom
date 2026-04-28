# PHANTOM Fonts — activated via Google Downloadable Fonts

**Status (2026-04-29):** Geist, Inter, and JetBrains Mono are now active in
the Android app via Google's Downloadable Fonts mechanism through Google Play
Services. No .ttf binaries are bundled with the apk.

## How it works

`apps/android/src/androidMain/kotlin/phantom/android/ui/theme/PhantomTypography.kt`
declares each family with `androidx.compose.ui.text.googlefonts.GoogleFont`.
The font provider points at `com.google.android.gms.fonts` (Google Play
Services Font Provider).

On first request, Play Services downloads the variable .ttf in the
background and caches it system-wide. Subsequent launches and other apps that
need the same family share that cache. The first text after a cold launch
may render in a system fallback for a frame, then reflow once the variable
font is cached — this is acceptable for our use case.

The certificate array required by the Compose `GoogleFont.Provider` API
lives in `apps/android/src/androidMain/res/values/font_certs.xml`. Those
values are public (Google publishes them) and match the
`com.google.android.gms.fonts` content-provider authority.

## Why downloadable instead of bundled .ttf

- **APK stays small.** A bundled variable Geist + Inter + JetBrains Mono
  triplet is 1.5–2 MB; downloadable adds ~0 KB to the apk.
- **No licence files travel with us.** The fonts are still SIL OFL 1.1, but
  the licence text travels with Google's font cache, not our build.
- **System-wide cache.** Other Compose apps that use the same families share
  the same cached binary, so users only pay the download cost once.

## Falling back to bundled .ttf (if you ever want offline-first)

If a future build needs full offline-first font availability (e.g. a
sandboxed device with no Google Play Services), you can revert to bundled
fonts:

1. Drop the variable .ttf files into
   `apps/android/src/androidMain/res/font/` with these exact lowercase
   `[a-z0-9_]` filenames:
   - `geist_variable.ttf`
   - `inter_variable.ttf`
   - `jetbrains_mono_variable.ttf`

   Sources (open-source, SIL OFL 1.1):
   - https://github.com/vercel/geist-font/releases
   - https://github.com/rsms/inter/releases
   - https://github.com/JetBrains/JetBrainsMono/releases

2. In `PhantomTypography.kt`, replace the GoogleFont-based families with
   bundled-font definitions:

   ```kotlin
   import androidx.compose.ui.text.font.Font
   import phantom.android.R

   val PhantomFontGeist: FontFamily = FontFamily(
       Font(R.font.geist_variable, FontWeight.Normal),
       Font(R.font.geist_variable, FontWeight.Medium),
       Font(R.font.geist_variable, FontWeight.SemiBold),
       Font(R.font.geist_variable, FontWeight.Bold),
   )
   // … same pattern for Inter and JetBrainsMono
   ```

3. Remove the `androidx-compose-ui-google-fonts` dependency from
   `gradle/libs.versions.toml` and `apps/android/build.gradle.kts` if you
   want to stop fetching via Play Services entirely.

## Production swap (later)

The design system maps prototype fonts to production fonts:

- Geist → **PP Neue Montreal** (display / brand) — paid licence required.
- Inter → unchanged.
- JetBrains Mono → **Berkeley Mono** (Pro tier — premium mono contexts) —
  paid licence required.

When the licence is acquired, the swap is local to `PhantomTypography.kt`:
either point GoogleFont names at the new families (if Google adds them — PP
Neue Montreal is not on Google Fonts), or swap to bundled .ttf per the
fallback section above.
