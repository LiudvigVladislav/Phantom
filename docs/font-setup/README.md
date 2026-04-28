# PHANTOM Fonts

This directory holds the typeface binaries for the app. Today PhantomTypography
falls back to Android's stock sans-serif and monospace because the .ttf files
have not been committed yet — see the TODO comments in
`apps/android/src/androidMain/kotlin/phantom/android/ui/theme/PhantomTypography.kt`.

## How to activate the design-system fonts

1. **Download the variable .ttf files** from the upstream sources (open-source,
   no license headers required for binaries shipped with an AGPL-3.0 client):

   | Family | Source | Filename to drop here |
   |---|---|---|
   | Geist | https://github.com/vercel/geist-font/releases | `geist_variable.ttf` |
   | Inter | https://github.com/rsms/inter/releases | `inter_variable.ttf` |
   | JetBrains Mono | https://github.com/JetBrains/JetBrainsMono/releases | `jetbrains_mono_variable.ttf` |

   File names must be **lowercase, snake_case, only `[a-z0-9_]`** — Android's
   resource compiler rejects camelCase and dashes.

2. **Update `PhantomTypography.kt`** — replace each `FontFamily.Default` /
   `FontFamily.Monospace` with the real family:

   ```kotlin
   import androidx.compose.ui.text.font.Font
   import androidx.compose.ui.text.font.FontFamily

   val PhantomFontGeist: FontFamily = FontFamily(
       Font(R.font.geist_variable),
   )
   val PhantomFontInter: FontFamily = FontFamily(
       Font(R.font.inter_variable),
   )
   val PhantomFontMono: FontFamily = FontFamily(
       Font(R.font.jetbrains_mono_variable),
   )
   ```

3. **Add the `R` import** at the top of `PhantomTypography.kt`:

   ```kotlin
   import phantom.android.R
   ```

4. **Rebuild.** Android Studio sometimes caches the resource table; if Compose
   previews don't pick up the new fonts, re-sync gradle and clean-rebuild.

## Production swap (later)

The design system maps prototype fonts to production fonts:

- Geist → **PP Neue Montreal** (display / brand) — paid licence required.
- Inter → unchanged.
- JetBrains Mono → **Berkeley Mono** (Pro tier — premium mono contexts) —
  paid licence required.

When the licence is acquired, drop the new .ttf with the same filename pattern
(`pp_neue_montreal_variable.ttf`, `berkeley_mono_variable.ttf`) and swap the
font family in `PhantomTypography.kt`. No other code changes needed.

## Why .ttf and not .otf

Android's `R.font.*` resource compiler only accepts TrueType (.ttf) and XML
font sets. Variable fonts shipped as TTF cover all 100–900 weights via axes,
so we don't need separate Bold / Regular / Italic files.
