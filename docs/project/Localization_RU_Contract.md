# English/Russian application language contract

Status: implementation contract; local Russian candidate is not release-ready.
Owner screen review on 2026-09-27: Russian localization explicitly NOT ACCEPTED.
Passing locale-selection tests is not acceptance of copy or feature completeness.
Language-choice amendment approved by the owner on 2026-09-27.
Do not ship a partial `values-ru` directory as a completed localization: the existing
`strings.xml` records the mixed-language failure observed when only a few
snackbars were translated.

## Behavior

- Default follows the device's language preferences. Russian devices show
  Russian; other devices fall back to English.
- Settings offers only English and Russian, showing the effective language.
  Following the device is the implicit default, not a third selectable option.
  Opening/cancelling the picker does not persist an override. Applying either
  language creates an explicit selection even if it matches the current system
  language. That selection survives process death and restart and takes priority
  over later device-language changes. There is no in-app reset-to-system option.
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
2. Starting with the implicit system default, select Russian then English while
   a chat draft exists; the draft and session survive every transition and restart.
   Verify both opposite-language combinations between the device and app.
3. On Android 13+, change the app language in OS settings and verify the
   in-app picker reflects the effective language, including an OS reset to system.
   On API 26-32, verify implicit default and persistent manual override.
4. Scan active UI and resource keys for missing translations, broken format
   parameters, clipping, and accessibility descriptions. Negative controls
   deliberately remove a translation and change a format argument; both must
   fail the release gate.
5. A device-level test confirms a Russian system without an app override shows
   Russian across a complete messaging flow, not just the Settings screen.

Reference: [Android per-app language guidance](https://developer.android.com/guide/topics/resources/app-languages).

## Owner screen corrections (2026-09-27)

- Use Избранное / Favorites consistently for saved messages; shorten pin to
  Закрепить / Pin and distinguish the archive destination from the archive action.
- Translate voice previews from message evidence, never from matching user text alone.
- Remove the redundant plan badge. Make the contact-profile footer fully reachable.
- The owner confirmed keeping Рядом / Nearby; no navigation rename is required.
- Calls and the own-profile screen are only provisionally accepted.
- Follow-up profile corrections: standalone month names (сентябрь 2026), return
  to the screen that opened the profile for both back actions, and one profile
  entry in Settings. Origin is saved only as transient navigation state.
- Device storage offers confirmed cleanup of rebuildable playback copies.
  The displayed cache excludes the message database, keys, original voice
  files, pending recordings, QR shares and Tor working files; those must not
  be deleted by cache cleanup. Partial/error results must remain visible.
- Favorites now offers the shared emoji panel and local voice recording/playback.
  Completed voice notes use the existing AUDIO message representation, not the
  outbox. A failed local save retains its draft and stable ID for retry within
  the screen; an active recording is cancelled when the app backgrounds.
  Voice forwarding from Favorites and pinning notes are not completed here.
- Disappearing-message controls now live in the chat menu, with the same six
  choices and local-first policy. A failed submission is shown as local-only;
  successful submission is not presented as acknowledgement from the peer.
- The contact-profile footer was checked at font scales 1.0 and 2.0. Key copying
  has a single action label, replaced briefly by success feedback after copying.
- Still open: whole-chat local/peer deletion with
  honest outcome semantics; independent read-receipt and last-seen controls;
  screenshot policy and peer enforcement; complete call/message notification
  controls; real attachment transfer.
- These functional gaps are not closed by translation or by new visual controls.
  Existing wire/persistence/crypto restrictions still apply. Do not claim peer
  deletion or peer screenshot enforcement from a local-only action.
