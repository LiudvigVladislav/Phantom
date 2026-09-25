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
- Settings, Privacy Mode detail, chat-list and Calls copy, shared empty states,
  the connection banner, and the common top bar and bottom navigation have
  been moved to English resources. The connection banner's error
  classification is typed and its resource mapping is covered by tests.
  The request count uses a plural resource, and chat-list dates use the active
  locale rather than a fixed US locale. This is extraction, not translation.
- Active onboarding-v2 welcome, explanation, identity-entry, key-preview,
  finale, permissions, shared step chrome, startup-error and repair-error copy
  has been extracted. Privacy-level, pricing and terms copy remains.

The full `SessionOrderFullStackTest` class was also checked independently on
Mac: 57/57 tests passed in 400.838 seconds. It is a regular integration test,
not a Paparazzi snapshot test. Snapshot verification must use the repository's
`phantomHostTestEngine=paparazzi` mode so this long-running class is not run as
part of a visual-only pass; regular tests still run in their own host-test lane.

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
the onboarding-v2 flow. The chat-list route still includes untranslated linked
screens, and the calls route still has active-call and incoming-call screens.
The next extraction pass should follow complete user flows: cold start and
onboarding; chat list, message requests, one-to-one
chat and voice; profile and contact; calls; notifications and errors. Then
audit shared UI components, non-screen Android code, accessibility labels,
plural forms, and dynamic formatting.

## Release gate

Do not expose Russian or the language picker until every active flow above is
classified and the English and Russian resources agree on keys and format
arguments. Verify Android 12 and 13+ language switching, process restart,
draft and session preservation, notification text, and screen geometry on a
phone and emulator. The current branch does not meet that gate.

## Copy correctness found during extraction

`HowStepV2` says presence, read receipts and discovery are separate switches,
while the Settings audit found no separate Last Seen control and shows read
receipts as a consequence of Privacy Mode. `PrivacyLevelStepV2` still describes
last-seen visibility and Nearby discoverability per tier. These claims need
verification against production behavior and correction before translation;
moving English text into a resource would not make them true. No product
semantics or transport behavior changed in this extraction pass.
