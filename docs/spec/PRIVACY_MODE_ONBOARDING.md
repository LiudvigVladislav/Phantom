<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (c) 2026 Willen LLC
-->

# Privacy Mode — Onboarding & UI Specification

**Status:** Approved 2026-05-05 by Vladislav (founder)
**Scope:** Wireframes and copy for the Privacy Mode user-facing surfaces — onboarding selection, Settings toggle, the Ghost first-time explainer, and the embedded comparison block. The visual design is grounded in `Design/PHANTOM_FULL_COMPOSE.md` §10; this document specifies the runtime / behavioural side that engineering implements.

**Companion docs:**
- `PRIVACY_MODE_BEHAVIOR.md` — what each mode does at runtime
- `PRIVACY_MODE_COMPARISON_TABLE.md` — feature comparison (also embedded here for in-app reuse)
- `GHOST_MODE_TRANSITIONS.md` — state machine for transitions

---

## 1. Onboarding selection screen

### 1.1 Position in the flow

This screen is **Step 3** of onboarding, after username creation (Step 1) and identity-key generation (Step 2). The user has already chosen their `@username` and PHANTOM has minted their identity. Privacy Mode is the last decision before entering the app.

Screen ID: `OnboardingPrivacyModeScreen`
Component path (planned): `apps/android/.../onboarding/PrivacyModeStep.kt`

### 1.2 Wireframe

The `[icon]` placeholder in each card represents the custom-designed asset for that mode (see `PRIVACY_MODE_COMPARISON_TABLE.md` §A — `ic_privacy_mode_standard`, `ic_privacy_mode_private`, `ic_privacy_mode_ghost`). **The wireframe uses text labels, not emoji** — emoji belong to marketing renderings only.

```
┌─────────────────────────────────────────────────────────────┐
│  ←                                            [step 3 / 3]  │
│                                                             │
│  Choose your privacy mode                                   │
│  Control how visible you are to others on PHANTOM.          │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ [icon: standard]  Standard                    ◉       │  │
│  │     Visible to contacts, searchable by username.      │  │
│  │     Calls, groups, push notifications work normally.  │  │
│  │                                                       │  │
│  │     Recommended for most users.                       │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ [icon: private]   Private                     ○       │  │
│  │     Visible only to confirmed contacts.               │  │
│  │     Read receipts and typing indicators off by        │  │
│  │     default.                                          │  │
│  │                                                       │  │
│  │     For people who want a quieter presence.           │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ [icon: ghost]     Ghost Mode            PRO   ○       │  │
│  │     Invisible to all. Receive-only.                   │  │
│  │     Always-Tor with bridges. No calls. 24h            │  │
│  │     disappearing messages by default.                 │  │
│  │                                                       │  │
│  │     For maximum anonymity on hostile networks.        │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  [ Compare modes in detail → ]                              │
│                                                             │
│                                                             │
│              ┌───────────────────────────────┐              │
│              │       Enter PHANTOM           │              │
│              └───────────────────────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 Card states

Per `Design/PHANTOM_FULL_COMPOSE.md` §10:

**Selected card:**
- `border-left: 3 px solid cyan`
- `outline: 1 px solid rgba(0, 212, 255, 0.35)`
- `bg: surfaceHover`
- Top-right check badge `15 × 15 px`: `bg rgba(0, 212, 255, 0.12)` + `border rgba(0, 212, 255, 0.35)` + cyan check icon.

**Unselected card:**
- Default surface, no border highlight.
- Pressable.

**PRO-locked card (Ghost in release builds):**
- `opacity: 0.5`
- `pointer-events: none` (taps do not select; instead, they trigger the PRO upsell sheet — see §4)
- "PRO" badge top-right (replaces the check badge slot)
- Per-design note in the source: "PRO badge is informative, not blocking — does not paywall the visual; just communicates that selection requires PRO."

**PRO-unlocked card (Ghost in debug builds with `DEBUG_UNLOCK_GHOST=true`):**
- Full opacity, normal pressable, no PRO badge. Behaves identically to Standard / Private cards.

### 1.4 Default selection on first entry

`Standard` is pre-selected. Pre-selection is a deliberate product choice — the user can read all three options without committing, and the default is the right answer for "most users."

### 1.5 "Compare modes in detail →" link

A secondary action below the cards. On tap, opens a modal sheet with the comparison table (§5 below). Sheet has a back button; closing returns to the selection without changing the user's pick.

This link gives PHANTOM-curious users — particularly grant reviewers, journalists, security researchers exploring the app — a path to the full feature matrix without forcing them to leave onboarding.

---

## 2. Settings → Privacy Mode

### 2.1 Position in Settings

Top-level Settings entry. Placement (per `Design/PHANTOM_FULL_COMPOSE.md` settings layout): first item in the "Privacy & Security" section, above "App Lock" and "Encrypted backup".

### 2.2 Settings list row (current state preview)

```
┌───────────────────────────────────────────────────┐
│  Privacy Mode                                     │
│  [icon: standard]  Standard                >      │
└───────────────────────────────────────────────────┘
```

The current mode is shown with its custom icon (24 dp) and name. Tapping the row opens the full chooser screen. As with onboarding, the in-app icon is the custom asset, not an emoji.

### 2.3 Chooser screen (when tapped from Settings)

Same wireframe as onboarding §1.2, with these differences:
- Title: "Privacy Mode" (not "Choose your privacy mode")
- No step indicator (not part of onboarding)
- Bottom action button reads "Save" (not "Enter PHANTOM")
- If user picks the same mode they already had → Save button is disabled / greyed.
- If user picks a different mode → Save button is enabled. Tapping initiates the transition flow per `GHOST_MODE_TRANSITIONS.md`.
- "Compare modes in detail →" link is also present.

### 2.4 Mode-change confirmation flow

Settings is the place where mode changes happen mid-life (onboarding has no "previous mode" to leave). Confirmation depends on direction:

| From → To | Confirmation |
|---|---|
| Standard → Private | Single confirm: "Switch to Private? Read receipts and typing will be off by default. You can re-enable them in Settings." |
| Private → Standard | Single confirm: "Switch to Standard? Username search will find you, and read receipts will be on by default." |
| Standard / Private → Ghost | Multi-step (groups, devices, calls — see GHOST_MODE_TRANSITIONS.md §1) |
| Ghost → Standard / Private | Single confirm: "Switch out of Ghost? Visibility resumes and push notifications can be re-enabled." |

---

## 3. Ghost first-time explainer dialog

Triggered the first time Privacy Mode becomes Ghost on a given device, regardless of source (onboarding, settings change, debug). Stored flag: `LocalConfig.ghostExplainerSeen: Boolean`.

### 3.1 Wireframe

The hero glyph at the top is the **custom Ghost icon** (`ic_privacy_mode_ghost`) rendered at 64 dp, monochrome cyan, on the dark surface. The wireframe denotes it as `[icon: ghost · 64dp]`. **Not an emoji.**

```
┌─────────────────────────────────────────────────┐
│                                                 │
│            [icon: ghost · 64dp]                 │
│                                                 │
│       Welcome to Ghost Mode                     │
│                                                 │
│  In Ghost Mode:                                 │
│                                                 │
│  • Messages disappear after 24 hours by         │
│    default on both devices automatically.       │
│    Applies to all messages — sent and           │
│    received. You can change the timer per       │
│    chat in chat settings.                       │
│                                                 │
│  • Voice and video calls are disabled.          │
│                                                 │
│  • You are invisible to other PHANTOM users —   │
│    username search will not find you.           │
│                                                 │
│  • Group chats are unavailable.                 │
│                                                 │
│  • All your traffic goes through Tor with       │
│    bridges — connections are slower but your    │
│    network identity is hidden.                  │
│                                                 │
│  Note: PHANTOM cannot prevent screenshots,      │
│  exports outside the app, or someone            │
│  photographing your screen.                     │
│                                                 │
│             ┌────────────────────┐              │
│             │   I understand      │              │
│             └────────────────────┘              │
│                                                 │
└─────────────────────────────────────────────────┘
```

### 3.2 Behaviour rules

- **Modal, dismissible only by "I understand".** No system back gesture, no tap-outside-to-close.
- If the user backgrounds the app before tapping "I understand", the flag is NOT set; dialog reappears next time.
- Once the user taps the button:
  - `LocalConfig.ghostExplainerSeen = true`
  - Dialog dismisses
  - User lands on Settings (if reached via Settings change) or chat list (if reached via onboarding).
- Visually: full-screen overlay on dark surface; matches the architectural restraint of the rest of the design system (no decorative illustrations, no marketing flair). Ghost emoji at the top is the only graphic element.

### 3.3 Russian variant

```
┌─────────────────────────────────────────────────┐
│                                                 │
│            [icon: ghost · 64dp]                 │
│                                                 │
│        Добро пожаловать в Ghost                  │
│                                                 │
│  В режиме Ghost:                                │
│                                                 │
│  • Сообщения исчезают через 24 часа на          │
│    обоих устройствах автоматически. Касается    │
│    всех сообщений — и отправленных, и           │
│    полученных. Можно изменить таймер или        │
│    отключить в настройках каждого чата.         │
│                                                 │
│  • Аудио- и видеозвонки недоступны.             │
│                                                 │
│  • Вы невидимы для других пользователей         │
│    PHANTOM — поиск по имени не находит вас.     │
│                                                 │
│  • Групповые чаты недоступны.                   │
│                                                 │
│  • Весь трафик идёт через Tor с bridges —       │
│    соединение медленнее, но ваш сетевой         │
│    провайдер не видит вашу активность.          │
│                                                 │
│  Замечание: PHANTOM не контролирует             │
│  скриншоты, экспорт за пределы приложения       │
│  или фото экрана.                               │
│                                                 │
│             ┌────────────────────┐              │
│             │      Понятно        │              │
│             └────────────────────┘              │
│                                                 │
└─────────────────────────────────────────────────┘
```

Localization strings live in the standard resources bundle and are tracked alongside the rest of EN/RU translations.

---

## 4. PRO upsell flow (Ghost tap on PRO-locked card)

Triggered when a free-tier user taps Ghost (release builds, before subscribing).

### 4.1 Wireframe — bottom sheet

The hero glyph at the top is the custom Ghost icon at 48 dp. **Not emoji.**

```
┌─────────────────────────────────────────────┐
│                                             │
│            [icon: ghost · 48dp]             │
│                                             │
│          Ghost Mode is part of              │
│              PHANTOM PRO                    │
│                                             │
│  Ghost gives you anonymity guarantees       │
│  comparable to dedicated tools like Briar   │
│  or Cwtch — running over Tor with bridges,  │
│  invisible profile, ephemeral messages —    │
│  inside the messenger you already use for   │
│  everyday conversations.                    │
│                                             │
│  PRO also unlocks:                          │
│  • Multiple identities (Beta)               │
│  • Plaintext-only-in-RAM storage (Beta)     │
│  • Priority support                         │
│                                             │
│            ┌─────────────────────┐          │
│            │   Subscribe to PRO  │          │
│            └─────────────────────┘          │
│                                             │
│            ┌─────────────────────┐          │
│            │  Stay with Standard │          │
│            └─────────────────────┘          │
│                                             │
└─────────────────────────────────────────────┘
```

### 4.2 Behaviour

- "Subscribe to PRO" → opens billing screen (existing PRO purchase flow; integration with `BillingClient` planned for later sprint).
- "Stay with Standard" → dismiss sheet, return to onboarding selection. Standard remains pre-selected.
- The sheet does not change the user's selection automatically.

For Alpha 2 (no billing wired yet): the Subscribe button shows a placeholder "PRO will be available in Beta — for now, Ghost is unlocked in debug builds for testers". This avoids dead-link feelings without committing to billing infrastructure prematurely.

---

## 5. Embedded comparison sheet

Opened from the "Compare modes in detail →" link on either onboarding selection or Settings chooser.

### 5.1 Layout

A scrollable modal sheet with three sections:

**Top — visual comparison table**

The compact table from `PRIVACY_MODE_COMPARISON_TABLE.md` §B, rendered as a styled table within the app (not an image). Three columns: Standard / Private / Ghost.

**Middle — "When to choose which" guidance**

The `PRIVACY_MODE_COMPARISON_TABLE.md` §D copy, presented as three card-style blurbs.

**Bottom — full-feature matrix toggle**

A "Show full feature comparison" disclosure that expands the long table from `PRIVACY_MODE_COMPARISON_TABLE.md` §C inside the sheet. Default collapsed (most users do not need 60 rows).

### 5.2 Source-of-truth discipline

The in-app strings derive from a single localised resource file. Edits to the comparison content must update that resource AND the corresponding sections of `PRIVACY_MODE_COMPARISON_TABLE.md`. A linter (or the periodic doc-audit pass) enforces parity.

The full feature matrix is generated at build time from a structured YAML / JSON source so that the in-app version, the marketing site, and the markdown spec do not drift. Implementation of that codegen path is a separate engineering task (out of Stage 5 scope).

---

## 6. Foreground notification — text by mode + transport state

Reproduced here from `PRIVACY_MODE_BEHAVIOR.md` §8 because the UX spec needs to confirm the strings UX would approve. These strings are the most visible mode signal a user sees outside the app.

| Privacy Mode | Transport state | Notification content text |
|---|---|---|
| Standard / Private | Direct WSS connecting | `Encrypted connection active` |
| Standard / Private | Direct WSS connected | `Encrypted connection active` |
| Standard / Private | Tor bootstrapping (auto-fallback) | `Tor: bootstrapping N%` |
| Standard / Private | Tor onion connected | `Connected via Tor (bridges)` |
| Standard / Private | Recovered to direct after fallback | `Encrypted connection active` |
| Ghost | Tor bootstrapping | `Ghost: bootstrapping N%` |
| Ghost | Tor onion connected | `Ghost mode active (Tor bridges)` |
| Ghost | Tor failed | `Ghost: connection failed — open app` |

Notification title is always `PHANTOM`. The small icon stays the system placeholder (Beta will replace with a dedicated 24 dp monochrome status-bar icon — see `PhantomMessagingService.kt` comment).

---

## 7. Russian copy parity

Every piece of user-visible copy in this document has a Russian variant. The strings live in `apps/android/src/androidMain/res/values-ru/strings.xml` (created during this work). Translation parity is a hard requirement — Russian users are a primary target audience, and the Privacy Mode value proposition is the same in either language.

For brevity this spec includes the full Russian variant only for the Ghost first-time explainer (§3.3). Other strings are translated 1:1 by the localisation pass during Stage 5C implementation.

---

*End of onboarding & UI specification. The wireframes here are functional contracts: the visual design (`PHANTOM_FULL_COMPOSE.md`) defines the look, this document defines the behaviour, copy, and decision flow.*
