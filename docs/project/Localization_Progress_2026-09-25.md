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
- The message-requests inbox now gets its title, empty state, block dialog,
  commands, and Back accessibility label from English resources. Peer names
  and message previews remain user content and are not translated.
- The Add Contact dialog now gets its instructions, validation copy, actions,
  and field accessibility labels from English resources. Pasted keys, aliases,
  and peer names remain untouched user content.
- The one-to-one chat now gets its header status, composer hint, send-failure
  notice, message actions, pin/delete/forward dialogs, link-preview states,
  voice labels, and block/report dialog copy from English resources. Older
  date separators use the app's active locale. Stored forwarded/saved message
  text, peer names, message bodies, and raw failure details are not rewritten.
- The active own-profile screen now gets its field labels, QR/key explanation,
  account details, share/avatar/edit actions, and deletion confirmation from
  English resources. Editing selects stable field identifiers rather than
  translated labels; existing preference keys and the MM.DD.YYYY stored date
  format are unchanged. Member-since uses the active locale. The unused legacy
  connection card is not part of this pass.
- Active onboarding-v2 welcome, explanation, identity-entry, key-preview,
  finale, privacy mode, permissions, shared step chrome, startup-error and
  repair-error copy has been extracted. Pricing and terms copy remains.

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
screens, including chat and contact profiles; the calls route still has
active-call and incoming-call screens.
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

The onboarding-v2 How and privacy-tier copy now describes the implemented
transport chains and read-receipt policy. The previous presence, last-seen,
and Nearby-per-tier promises were unsupported: Settings has no separate
last-seen control, and Nearby's discoverable toggle currently stores a local
preference without running mesh discovery. The pricing sheet and legacy
onboarding still contain aspirational claims; they require product review
before translation. No transport or privacy-mode behavior changed here.

The own-profile deletion text previously claimed that contacts would see an
"Account deleted by user" event. The action only deletes the local identity
and keystore key; no notification is sent. The extracted copy now states the
local effect and warns that this device cannot read future messages to that identity.
The existing UPGRADE control has a no-op handler and needs separate product
work before it can be presented as functional. Date-of-birth input still uses
MM.DD.YYYY; changing its stored semantics requires a separate data decision.
