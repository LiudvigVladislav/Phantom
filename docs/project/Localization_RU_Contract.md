# English/Russian application language contract

Status: implementation contract. The app is still English-only. Do not ship a
partial `values-ru` directory as a completed localization: the existing
`strings.xml` records the mixed-language failure observed when only a few
snackbars were translated.

## Behavior

- Default follows the device's language preferences. Russian devices show
  Russian; other devices fall back to English.
- Settings offers System, English, and Russian. An explicit selection survives
  process death and app restart. System clears the app override and follows
  later device-language changes.
- Android 13+ in-app and OS per-app language settings agree. API 26-32 gets
  the same in-app behavior with a compatible stored override. Language changes
  refresh the current UI without resetting identity, sessions, or drafts.
- A release with Russian enabled must not mix untranslated English controls,
  errors, notifications, or accessibility labels into normal Russian flows.

## Inventory and implementation boundary

Extract user-visible copy from Android screens, navigation, dialogs,
snackbars, foreground and message notifications, onboarding, settings,
permissions, and accessibility descriptions into resources. Review shared
error messages before displaying them; do not expose raw exception text as
translated UI. Include plurals, dates, durations, file sizes, and dynamic
parameters. Preserve user-generated names and message content verbatim.

The current Android source has many hard-coded Compose strings, including in
onboarding, chat, settings, privacy detail, and calls. Counting `Text(` alone
is not a complete inventory. The implementation PR must produce a checked
inventory of every active user-visible string and classify each as translated,
non-translatable user content, debug-only, or unreachable. Both resource sets
must have identical required keys and format-argument types.

Use Android's per-app language APIs on Android 13+ and a compatible pre-33
path. The choice of persistence library is an implementation detail, but the
system-default state must remain distinct from an explicit English override.

## Acceptance

1. Cold launch, onboarding, one-to-one chat, voice message, calls, settings,
   dialogs, and notifications are inspected in English and Russian.
2. Change System -> Russian -> English -> System while a chat draft exists;
   the draft and session survive every transition and restart.
3. On Android 13+, change the app language in OS settings and verify the
   in-app picker reflects it. On API 26-32, verify app override and reset.
4. Scan active UI and resource keys for missing translations, broken format
   parameters, clipping, and accessibility descriptions. Negative controls
   deliberately remove a translation and change a format argument; both must
   fail the release gate.
5. A device-level test confirms a Russian system with System selected shows
   Russian across a complete messaging flow, not just the Settings screen.

Reference: [Android per-app language guidance](https://developer.android.com/guide/topics/resources/app-languages).
