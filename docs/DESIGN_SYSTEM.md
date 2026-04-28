# PHANTOM Design System

**Status:** Phase A foundation in place (2026-04-29). Tokens, typography, and
Material3 ColorScheme wired. Component reskin (Phase B) and animations
(Phase C) follow.

## Source of truth

| Layer | File | Purpose |
|---|---|---|
| Design spec | `Design/Primary/src/app/tokens.ts` | TypeScript reference — what the designer owns |
| Design spec | `Design/Primary/guidelines/Guidelines.md` | Component usage rules |
| Design spec | `Design/PH 1-2/src/app/components/` | Visual mockups for every screen (Phase 1 + Phase 2) |
| Code mirror | `apps/android/src/androidMain/kotlin/phantom/android/ui/theme/PhantomTokens.kt` | Kotlin port of `tokens.ts`. Mirror in lock-step. |
| Code mirror | `apps/android/src/androidMain/kotlin/phantom/android/ui/theme/PhantomTypography.kt` | Type scale + font families |
| Code mirror | `apps/android/src/androidMain/kotlin/phantom/android/ui/theme/PhantomTheme.kt` | Material3 wiring + back-compat aliases |
| Fonts (instructions) | `docs/font-setup/README.md` | How to drop .ttf files in to activate Geist / Inter / JetBrains Mono |
| Fonts (binaries — TBD) | `apps/android/src/androidMain/res/font/` | Place .ttf files here once downloaded (directory is created on demand) |

When the designer ships a new revision of `tokens.ts`, the **only** change in
client code is to update `PhantomTokens.kt` to match. All screens read tokens
from there, so a single edit ripples everywhere.

## Color palette

All hex values live in `PhantomTokens.Colors`. Hard-coded `Color(0xFF...)` in
component files is a smell — call sites must reach into the token object.

### Surfaces (six layers)

| Token | Hex | Use |
|---|---|---|
| `SurfaceDeep` | `#08090C` | App background, splash |
| `Surface` | `#0E1014` | Default screen background |
| `SurfaceElevated` | `#161A20` | Cards, sheets |
| `SurfaceHover` | `#1C2128` | Hover / pressed |
| `BorderSubtle` | `#1F242C` | Dividers, separators |
| `Border` | `#2A2F38` | Card borders, input outlines |

### Text (four readability levels)

| Token | Hex | Use |
|---|---|---|
| `TextPrimary` | `#F5F7FA` | Headlines, body |
| `TextSecondary` | `#A4ACBA` | Subtitles, metadata |
| `TextTertiary` | `#6B7385` | Timestamps, labels |
| `TextDisabled` | `#3F4654` | Disabled states |

### Cyan accent (locked triplet)

| Token | Hex | Lightness | Use |
|---|---|---|---|
| `Cyan` | `#00D4FF` | 100% | Primary accent |
| `CyanHover` | `#00BDDF` | -8% | Hover |
| `CyanDark` | `#0099BB` | -24% | Active / pressed |

Single accent, three states. **Do not introduce new accent colors.** Status
colors below cover the success / warning / danger axis.

### Status

| Token | Hex | Use |
|---|---|---|
| `Success` | `#22C55E` | Online indicator, ✓ confirmations |
| `Warning` | `#F59E0B` | Caution states |
| `Danger` | `#EF4444` | Errors, destructive actions |

## Typography

Three families, nine styles. Production maps to paid faces (PP Neue Montreal /
Berkeley Mono); prototype runs on Geist / Inter / JetBrains Mono.

| Style | Family | Size / Line | Weight | Use |
|---|---|---|---|---|
| `displayLarge` | Geist | 32 / 38 | 500 | Hero / onboarding hero |
| `display` | Geist | 24 / 32 | 500 | Section displays |
| `headline` | Geist | 20 / 28 | 500 | Page headlines |
| `title` | Inter | 17 / 24 | 600 | Card titles, contact names |
| `body` | Inter | 15 / 22 | 400 | Default body text |
| `caption` | Inter | 13 / 18 | 400 | Captions, meta |
| `overline` | Mono | 11 / 14 | 400 | UPPERCASE labels (0.08em tracked) |
| `monoSm` | Mono | 11 / 14 | 400 | Inline tech (timestamps, ticks, public-key prefixes) |
| `monoMd` | Mono | 13 / 18 | 400 | Technical readouts |

The full Material3 `Typography` map is registered in
`PhantomMaterial3Typography` so any component that resolves type via
`MaterialTheme.typography` (Buttons, AlertDialog, etc.) inherits the right
look without per-call wiring.

## Spacing — 8pt grid

`PhantomTokens.Spacing` exposes named slots:

```
micro          4.dp    Inline gaps inside compact rows
baseUnit       8.dp    Standard small gap
tight          12.dp   Internal padding of compact cards
comfortable    16.dp   Default padding inside cards / sheets
relaxed        20.dp   Comfortable+ for breathing room
gap            24.dp   Major gaps inside sections
sectionGap     32.dp   Between adjacent sections
sectionHeader  40.dp   Above section header
pageSection    48.dp   Page-level gap between major regions
hero           64.dp   Hero / wide spacing
pageTop        96.dp   Top-of-page gap
```

Most multiples of 4. The 20.dp slot fills the gap between 16 and 24.

## Radii

```
sm    8.dp     Chips, inputs, badges
md    12.dp    Cards, bubbles
lg    16.dp    Modals, sheets
xl    24.dp    Hero cards
pill  9999.dp  Buttons, nav pills, fully rounded chips
```

## Migration plan

### Phase A — Foundation ✅ done 2026-04-29
- `PhantomTokens.kt` with full color / spacing / radius scale
- `PhantomTypography.kt` with the 9-style scale + Material3 map
- `PhantomTheme.kt` updated to wire `Typography` and a richer `ColorScheme`
- Backward-compat aliases at the top of `PhantomTheme.kt` so existing 400+
  call sites compile without change. Some of those aliases shift colour by a
  small amount because the canonical design palette differs slightly from
  the original Alpha 1 values (e.g. `BgDeep` `#0B0D12` → `#08090C`).

### Phase B — Component reskin (per screen, in priority order)
1. **ChatList screen** (most visible — first impression)
2. **Active chat screen** (second-most visible — daily use)
3. **Profile screen**
4. **Onboarding screen**
5. **Settings screen**
6. **Add-contact / QR / Calls / Contact-profile / Saved messages / Archive**

Each screen is migrated by replacing inline `Color(...)` and `.dp` literals
with token references, applying the right typography style at every `Text`
call, and visually verifying against the Phase 2 mockup.

### Phase C — Polish
- Animations / transitions per `Design/Primary` motion guidance
- Edge cases (long messages, RTL, large text, accessibility)
- Custom font activation when .ttf binaries are dropped

## Avatar persona colors

`Design/Primary/src/app/tokens.ts` defines a `PERSONAS` array with restrained
avatar hues used in mockups. These are **not** in `PhantomTokens` because
client code derives avatar colors deterministically from the contact's
public-key hex (so two devices show the same gradient for the same contact
without sync). The persona array exists only for design previews.

## Reading these files

For future me / future contributor / future agent:

- **Want to know what color to use?** Open `PhantomTokens.Colors`.
- **Want to understand intent?** Read this file and `Design/Primary/guidelines/Guidelines.md`.
- **Want a screen-level reference?** Open the Phase 2 mockup at
  `Design/PH 1-2/src/app/components/phase2/<ScreenName>.tsx`.
- **Want to update tokens?** Edit `PhantomTokens.kt` to mirror `tokens.ts`,
  then run a quick visual smoke pass on the most-affected screens.
