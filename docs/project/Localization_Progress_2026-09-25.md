# Localization progress (2026-09-25)

Status: foundation only. English remains the only visible language. There is
no `values-ru` directory or in-app language picker yet; neither should be
added to a release until the complete-flow gates in
[`Localization_RU_Contract.md`](Localization_RU_Contract.md) pass.

## Completed in this branch

- `AppLanguageStore` distinguishes System, English, and Russian. API 33+ uses
  Android's per-app `LocaleManager`; API 26-32 uses a stored override and a
  localized base context. The previous override is migrated on OS upgrade.
- Tests cover explicit English versus System, Russian resource context, reset
  to the real system locale after an app override, unsupported stored tags,
  Android 13+ selection, and migration precedence.
- Settings and Privacy Mode detail copy has been moved to English resources.
  This is extraction, not translation.

## Candidate inventory, not a completeness claim

The source scan

```sh
rg -n 'Text\(|contentDescription\s*=|showSnackbar\(|label\s*=\s*"|body\s*=\s*"' apps/android/src/androidMain/kotlin/phantom/android/screens
```

currently finds 634 anchor lines in 40 screen source files. An anchor may
contain no literal, several literals, or a dynamic value. A second scan finds
22 additional Android source files outside `screens` with text, accessibility,
snackbar, notification, or toast candidates. Neither scan covers every
possible user-visible string, shared-module error, XML resource, or server
message. Do not use these counts as translation coverage.

High-density screen files include `ChatScreen.kt`, `ProfileScreen.kt`,
`ContactProfileScreen.kt`, `OnboardingScreen.kt`, `AddContactScreen.kt`, and
`ChatListScreen.kt`. The next extraction pass should follow complete user
flows: cold start and onboarding; chat list, message requests, one-to-one
chat and voice; profile and contact; calls; notifications and errors. Then
audit shared UI components, non-screen Android code, accessibility labels,
plural forms, and dynamic formatting.

## Release gate

Do not expose Russian or the language picker until every active flow above is
classified and the English and Russian resources agree on keys and format
arguments. Verify Android 12 and 13+ language switching, process restart,
draft and session preservation, notification text, and screen geometry on a
phone and emulator. The current branch does not meet that gate.
