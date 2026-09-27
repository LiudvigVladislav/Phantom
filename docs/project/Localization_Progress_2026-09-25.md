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
- The contact-profile screen now gets its dialogs, report categories and
  outcomes, key and note labels, verification fallback, and disappearing-timer
  labels from English resources. Report category IDs and timer durations stay
  stable. The separate production verification screen now gets its compare,
  confirmed, mismatch, fingerprint, and action copy from English resources.
  Confirmation is disabled without two valid keys and checks that the
  displayed keys still match repository values before writing the verified
  flag. The unimplemented Report action is no longer shown as a working
  button. A phone/emulator interaction check remains outstanding.
- Active onboarding-v2 welcome, explanation, identity-entry, key-preview,
  finale, privacy mode, permissions, shared step chrome, startup-error and
  repair-error copy has been extracted. The pre-flow Terms screen now uses
  English resources for its headings, eight sections, links, scroll hint,
  and acceptance text. Its visible wording and acceptance behavior were
  preserved; extraction is not legal, privacy, or security approval. Pricing
  extraction is recorded in the follow-up below.
- Incoming-call and active-call screens now take status and action copy from
  English resources. Icon-only call controls have accessible action names that
  reflect mute and speaker state. The `RINGING` label describes an incoming
  call awaiting an answer, not an established connection. Signaling, audio,
  and WebRTC behavior are unchanged. The message-notification channel and
  quick-reply labels also use resources; the channel ID stays stable. Call
  notifications and device-level call interaction checks remain outstanding.
  The Android source currently has no separate incoming-call notification
  publisher; adding one is product work, not string extraction.
  The system channel's displayed name after an in-app language switch has not
  yet been verified on an existing installation.
- The foreground connection notification now gets its channel and dynamic
  status copy from resources, including effective privacy mode, Tor bootstrap
  stage and progress, and REST fallback. Transport labels have resource keys;
  bridge-profile identifiers remain technical names. The old startup text
  implied a connection existed before one was established; it now says the
  connection is starting. A Tor failure no longer asserts that censorship has
  been diagnosed. Connection
  routing and the existing notification channel ID are unchanged.
- The main startup-error screen and app-lock prompt now use resource-backed
  text. Startup errors are classified by type without exposing exception
  messages or advising an app-data-clearing reinstall. The lock screen's
  previous fallback unconditionally unlocked on the production
  `ComponentActivity` host; `MainActivity` now supports `BiometricPrompt`,
  and missing host or unavailable device authentication fails closed. This
  change was exercised on a read-only Android 37 emulator: with no device
  credential it remained locked; cancel and a wrong PIN also remained locked;
  the correct system PIN opened the app. The connected Android 12 phone still
  needs a check on this build: its installed APK has the project release
  signature, while this branch's debug APK has a different signature, so an
  in-place install without clearing data is unavailable. Android 9-10 do not
  support the strong-biometric plus-device-credential combination, so those
  versions use the OS credential confirmation activity and unlock only on its
  successful result. No weaker biometric mode is silently enabled. The legacy
  credential flow still needs interaction testing on those OS versions.
  A separate `phantom.android.lockqa` build with the same `AppLockScreen`, a
  `FragmentActivity` host, and no Internet permission was then tested on the
  connected Android 12 phone. HiOS displayed its system authentication prompt;
  the correct device PIN opened the test screen. This checks the prompt and
  Compose callback on that phone, not installation of the full production
  `MainActivity` build. The phone's release-signed app was unchanged, and the
  temporary test package was uninstalled. Cancellation and wrong-PIN behavior
  remain evidenced by the emulator run, not by the phone run.
- Archive and Saved Messages are reachable from the chat list. Their active
  titles, empty states, menus, forward dialog, composer hints, pin-unavailable
  toast, and icon-only Back/save actions now use English resources. Archive
  weekday and month names and saved-note timestamps use the active app locale.
  This pass does not change the stored saved-conversation ID or username, the
  clipboard label, the time pattern, or the persisted forwarded-note header.
- Create Group now takes its title, input label/hint, action, empty state, and
  Back accessibility label from English resources. Its selected-member count
  uses a plural resource, and section headings uppercase with the active app
  locale. Contact names, public keys, and the user-entered group name stay
  untouched.
- Group Chat now takes its voice error/submission toast, read-only banner,
  encryption note, member placeholder, header/menu labels, message composer,
  recording status, and icon actions from English resources. Member counts use
  plurals; visible dates, times and playback speeds use the active app locale.
  The prior Russian partial-audio toast said messages were sent. Its new copy
  describes transport submission only, matching `GroupSendReport`; it does
  not claim relay acceptance, delivery, decryption, or ACK. Group IDs, role
  codes, audio markers, MIME names, and user content remain unchanged.
  The old `IllegalArgumentException` toast said the recording was too long,
  though media validation also rejects size and chunk-count bounds; the
  resource now reports preparation failure without guessing which bound failed.
- The reachable Add Contact entry and QR scanner now use English resources
  for their headings, instructions, permission UI, own-key preview, and Back
  accessibility labels. The entry no longer advertises a past July 2026
  username-search date or describes QR scanning as a completed handshake.
  With no local identity loaded, it no longer shows a fabricated `@yourname`.
  The key preview still displays only bytes from the user's own key.
- The splash logo's TalkBack description now uses an English resource; the
  visible brand graphic and transition are unchanged.
- The profile's QR image now has a resource-backed TalkBack description that
  says it is the user's contact code, without speaking the key bytes. The
  one-to-one chat recording timer uses the active app locale for numeric
  formatting; its duration and recording behavior are unchanged.
- Settings now keeps the measured cache size as bytes and formats its display
  through Android's file-size formatter for the current app context. A
  language change can therefore update the unit and number presentation
  without recounting or altering the stored files. The debug-only chunk-size
  probe retains its technical byte labels and is not part of release copy.

- Onboarding pricing and Settings Premium now use resource-backed names,
  planned prices, descriptions, feature lists, notices, and action labels.
  The same Ghost routing explanation appears in both; Premium no longer
  promises complete invisibility or receive-only behavior. The pricing-sheet
  backdrop, grab strip, close button, and Premium Back control have resource-
  backed action names. Tier selection uses an enum, not translated labels.
  Paid CTAs only show an unavailable notice; the onboarding toast no longer
  duplicates "coming soon". See `Pricing_Copy_Extraction_2026-09-26.md` for
  the scoped inventory and verification. This is English extraction, not a
  billing implementation or a Russian release.

The full `SessionOrderFullStackTest` class was also checked independently on
Mac: 57/57 tests passed in 400.838 seconds. It is a regular integration test,
not a Paparazzi snapshot test. Snapshot verification must use the repository's
`phantomHostTestEngine=paparazzi` mode so this long-running class is not run as
part of a visual-only pass; regular tests still run in their own host-test lane.

## Candidate inventory, not a completeness claim

The Archive/Saved Messages pass classified every remaining string literal in
those two screen source files after extraction:

| Literal | Classification | Reason |
| --- | --- | --- |
| `saved_messages_local` | Internal ID | Existing repository key shared with ChatScreen; must not be translated. |
| `Notes` | Persisted metadata | Existing saved-conversation username; changing existing rows needs a data/display decision. |
| `note` | Clipboard label | Not screen copy; can be reviewed with platform clipboard behavior. |
| `HH:mm`, `EEE`, `dd MMM` | Date/time patterns | Archive now formats names with the active locale; pattern policy remains for a full-flow formatting audit. |
| `HH:mm` | Date/time pattern | Saved-note timestamps use the active app locale. |
| `↩ from ` | Persisted message prefix | ChatScreen writes this marker and SavedMessagesScreen parses it; translating stored bytes would break recognition. The displayed header needs a separate structured-data decision. |

This is a source-literal classification for two screens, not a complete app
inventory or evidence that a Russian locale is ready. The pin menu still has
no pin behavior; its unavailable toast is localized as UI copy, not treated
as a completed feature.

Create Group has no remaining user-visible hard-coded English strings in its
screen source after this pass. The adjacent Create Channel screen is still
unreviewed for translation: its explanation promises subscriber reactions,
but no reaction action is present in GroupChatScreen. That claim and the
channel membership flow need product review before translating the text as-is.

Group Chat still contains technical literals for media formats, logging,
list keys, role IDs, audio storage/markers, and fixed numeric display formats;
they are not translatable screen copy. The `Member` label is a localized
fallback, not a verified sender identity. The group-audio result path still
needs device-level checks; resource extraction is not evidence of delivery.

AddContactScreen starts in Search state. Its only transition to Found is the
`onSuggestedTap` callback, which SearchState never invokes; Connected is
reachable only from Found. Both mock states are therefore unreachable from
the current UI. They still contain invented fingerprint bytes and a button
that only advances local UI state, not a real handshake or verification.
They are classified as unreachable and are not translated or presented as a
working security flow. Before enabling suggestions or username search, remove
those mocks or replace them with a verified identity lookup and real handshake.
The QR scanner's remaining hard-coded strings are diagnostic log messages,
not visible UI.

Nearby is an active navigation destination but not a working discovery flow.
The earlier `nearby_discoverable` toggle and animated radar were removed in
the approved product-copy follow-up. Its current unavailable title and body
are resource-backed and say this device is not scanning or broadcasting.
Existing stored values must not become future consent to broadcast. No
discovery implementation or Russian translation is claimed.

A targeted scan of non-screen Android copy found that `QrCodeImage` is called
from Profile and is active; its accessibility label is now a resource.
`NotificationPermissionSheet`, `BackupExportSheet`, and `SearchActiveSheet`
in `UtilitySheets.kt` have no production call sites, as do
`ComingSoonOverlay` and `PrivacySettingsRow` in `PhantomNav.kt`; their copy is
classified as unreachable, not translated. The backup sheet contains a past
September 2026 date and fallback fingerprint, and must not be wired into the
app without copy and security review. `PHANTOM` in the foreground notification
title is a brand name, not a translation candidate. Diagnostic log strings
and animation labels are not user-visible. This targeted scan does not prove
that every dynamic or indirect Android string has been found.

The Terms screen is active on first-run onboarding, and all its visible
English prose except the PHANTOM brand wordmark is now resource-backed. Its
claims about server access to contacts and activity, IP retention, key
recovery, and account identifiers have not been validated against current
implementation and policy. Do not translate them into Russian as approved
legal copy or expose a Russian Terms gate until that review is complete.

The product-claim pass in
[`Localization_Copy_Audit_2026-09-26.md`](Localization_Copy_Audit_2026-09-26.md)
classifies five disputed surfaces by actual route reachability and measured
behavior. The owner selected a $4.99 Plus display price for both onboarding
and Settings and approved preliminary-plan copy; neither screen offers a
working purchase. Ghost is locked without a verified Pro subscription, and
the old discoverability toggle/radar were removed from Nearby in favor of an
honest unavailable state. Create Channel has no
ordinary UI entry even though its route can be restored, and the migration
screen remains conditional and copy-locked by its ADR. The approved preview
decisions do not certify the planned features as implemented; the outstanding
Terms, migration, and channel decisions are not made by this inventory.

The source scan

```sh
rg -n 'Text\(|contentDescription\s*=|showSnackbar\(|label\s*=\s*"|body\s*=\s*"' apps/android/src/androidMain/kotlin/phantom/android/screens
```

found 612 anchor lines in 40 screen source files at the initial inventory
pass, not as a current coverage measurement. An anchor may
contain no literal, several literals, or a dynamic value. A second scan finds
22 additional Android source files outside `screens` at that same initial
pass with text, accessibility,
snackbar, notification, or toast candidates. Neither scan covers every
possible user-visible string, shared-module error, XML resource, or server
message. Do not use these counts as translation coverage.

High-density screen files include `ChatScreen.kt`, `ProfileScreen.kt`,
`ContactProfileScreen.kt`, `OnboardingScreen.kt`, `AddContactScreen.kt`, and
the onboarding-v2 flow. Screens reached from chat and call routes still need
a complete-flow audit; extraction of individual screens is not that audit.
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

The Alpha 1 to Alpha 2 migration screen is a separate copy-review blocker.
Its source marks copy as locked by `Alpha2_Migration.md` and ADR-009, while
the current UI still includes a past July 2026 promise, shows truncated raw
exception messages on failure, and advises reinstalling after missing local
identity. Those are not safe strings to translate verbatim. Review the
migration contract and recovery policy before changing or releasing that copy.

## Copy correctness found during extraction

The onboarding-v2 How and privacy-tier copy now describes the implemented
transport chains and read-receipt policy. The previous presence, last-seen,
and Nearby-per-tier promises were unsupported: Settings has no separate
last-seen control, and the former Nearby toggle only stored a local preference
without running mesh discovery. Pricing now explicitly presents planned
features; its resources do not certify implementation of those features.
Legacy onboarding remains outside the active-flow extraction and must not be
re-enabled with unreviewed claims. These copy changes do not alter transport
or privacy-mode routing.

The own-profile deletion text previously claimed that contacts would see an
"Account deleted by user" event. The action only deletes the local identity
and keystore key; no notification is sent. The extracted copy now states the
local effect and warns that this device cannot read future messages to that identity.
The existing UPGRADE control has a no-op handler and needs separate product
work before it can be presented as functional. Date-of-birth input still uses
MM.DD.YYYY; changing its stored semantics requires a separate data decision.

The contact profile previously drew a permanent online dot and "Last seen
recently" without presence data. Its key card labelled an X25519 key as
Ed25519, printed today's date as if verification had happened then, and used a
fabricated key preview/copy value while loading. The static presence claim and
fabricated key are gone; verification now requires a real unchanged key, and
the fallback confirmation waits until both keys are loaded. The
report dialog now describes the public-key metadata actually sent to the relay,
without promising human review. Two contact settings rows had no handlers and
claimed notification and Wi-Fi download policies that were not implemented;
they are hidden until real controls exist. The working disappearing-message
timer remains. Device-level visual and interaction checks are still required.

The full-screen verification screen previously allowed a verified write while
the key blocks still showed a loading placeholder. It also implied that a
local key comparison proves a person's real-world identity and that future
delivery is restricted to one key. Its English copy now describes only the
current key check. The screen rejects missing or malformed keys and re-reads
both keys before confirmation; a concurrent key change between that read and
the repository writes is not proven impossible by this UI guard. The separate
Report action was a no-op and has been removed from the mismatch state.
