# PHANTOM Fonts — bundled variable .ttf

**Status (2026-04-29):** Geist, Inter, and JetBrains Mono are bundled in
the apk as variable .ttf binaries. No download, no Google Play Services
dependency, no first-frame fallback.

## Active fonts

The variable .ttf files live under
`apps/android/src/androidMain/res/font/`:

| Family | File | Source |
|---|---|---|
| Geist | `geist_variable.ttf` | https://github.com/vercel/geist-font (SIL OFL 1.1) |
| Inter | `inter_variable.ttf` | https://github.com/rsms/inter (SIL OFL 1.1) |
| JetBrains Mono | `jetbrains_mono_variable.ttf` | https://github.com/JetBrains/JetBrainsMono (SIL OFL 1.1) |

Each `Font(...)` declaration in
`apps/android/src/androidMain/kotlin/phantom/android/ui/theme/PhantomTypography.kt`
pins the `wght` axis with `FontVariation.weight(weight.weight)` so Compose
pulls the right outline from the variable file instead of synthesising
bold/medium from a single regular cut.

## Apk size impact

The three variable .ttf files together add ~1.2 MiB to the apk (Geist
~130 KiB, Inter ~800 KiB, JetBrains Mono ~300 KiB). This is acceptable for
a privacy-first app where we want zero network round-trips for the type
system.

## Production swap

The design system maps prototype fonts to production fonts:

- **Geist → PP Neue Montreal** (display / brand) — paid licence required.
  When the licence is acquired, drop the licensed .otf cuts (or a single
  variable .ttf if Pangram Pangram ships one) into `res/font/` as
  `pp_neue_montreal_*.otf` (or `pp_neue_montreal_variable.ttf`) and swap
  the `geistFont(...)` helper in `PhantomTypography.kt` to point at the
  new resource. No call-site changes needed.
- **Inter → unchanged.**
- **JetBrains Mono → Berkeley Mono** (Pro tier — premium mono contexts) —
  paid licence required. Same pattern: drop the licensed .otf into
  `res/font/`, swap `jetbrainsMonoFont(...)` helper. The free JetBrains
  Mono build can stay for free-tier users; gating is a runtime decision
  based on subscription state.

## Filename rules

Android's resource compiler is strict about font resource filenames:

- **lowercase** only — `Geist-Variable.ttf` is rejected, `geist_variable.ttf`
  works.
- **`[a-z0-9_]` only** — no dashes, no dots beyond the extension, no
  uppercase, no spaces.

If you ever rename a font file, also update the `R.font.X` reference in
`PhantomTypography.kt`.

## Why bundled (not Google Downloadable Fonts)

We previously routed the same families through Google's Downloadable
Fonts mechanism via Google Play Services. That worked but had two
costs:

- A Google Play Services dependency on every device that wants the
  designed look — not a fit for our "no Google services" privacy
  posture.
- A first-frame fallback to system sans-serif while the .ttf cached.

Bundling the same .ttf files (also SIL OFL 1.1) cures both at the cost
of ~1.2 MiB apk size. PP Neue Montreal is also not on Google Fonts, so
when we move to production fonts the bundled path is what we'd need
anyway — no point keeping two paths.
