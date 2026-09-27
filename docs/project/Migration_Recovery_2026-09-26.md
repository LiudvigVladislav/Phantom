# Legacy migration recovery correction

Status: host verification, isolated offline Android emulator validation, and a
release-signed in-place update/startup check on one existing Android 12 phone.

## Scope and approval

The owner approved a durable local migration marker, recovery tests and aligned
screen copy on 2026-09-26. This changes local progress bookkeeping, not wire
fields, cryptographic formats or SQL schema. It is not a data reset or permission
to re-migrate every installed identity.

## Defect

Previously `needsMigration()` checked only missing signing keys. The first step
persisted those keys, so a failure publishing the bundle made the next launch
skip unfinished migration. Two isolated safety assertions reproduced this on
the preceding source; eight baseline controls and an explicit-retry control
passed. Existing tests of calling `runMigration()` twice did not cover routing
after a failed first run.

## Durable contract

- `MigrationProgressStore` is mandatory, with identity-scoped NOT_STARTED,
  IN_PROGRESS and COMPLETE states. There is no production in-memory fallback.
- Android stores `v1:in-progress` or `v1:complete` in an AtomicFile under
  `noBackupFilesDir/alpha2-migration/`, named by SHA-256 of the local identity ID.
  The marker contains no keys or message contents. Android backup is disabled
  in the existing manifest. This is not a cross-device restore mechanism.
- A confirmed IN_PROGRESS write precedes signing-key backfill. Writes sync the
  file, finish atomic replacement and verify the stored bytes; errors propagate.
- Missing marker + missing signing key requires migration. IN_PROGRESS requires
  migration even after key backfill. COMPLETE is a no-op; missing keys alongside
  COMPLETE or unreadable/corrupt state fail closed.
- No marker + existing signing keys is treated as an existing current install.
  It must never authorize wiping healthy sessions. An already-interrupted
  pre-fix install can be indistinguishable from this case. This change does NOT
  claim to repair that ambiguity automatically; preserve data and investigate
  such an installation individually.
- Publish success still precedes ratchet deletion, sender-key deletion and
  conversation flags. COMPLETE is written only after all these steps. Messages
  and conversation rows are not deleted; identity X25519 fields are preserved.
- Retry after interruption may repeat the cleanup while IN_PROGRESS. A completed
  run must never repeat it, even if invoked directly or concurrently. Mutex
  serialization also prevents a startup check from racing an active migration.
- Cancellation propagates instead of becoming an ordinary Result failure.
  The pending marker survives. A partial first marker write authorizes no key
  mutation; an unfinished replacement retains the last committed marker.

## Android lifecycle boundary

`AppContainer` creates the existing privacy-gated prekey API and migration
manager before normal transport collectors, prekey lifecycle jobs, DMS, group
messaging, calls and the existing orphan-reservation sweep. Sweep rules and
thresholds are unchanged, but it now runs after the migration barrier. When
migration is needed it enters AwaitingMigration and
returns without constructing those consumers. A second initializer reuses the
same pending manager. Foreground-service preparation stops before receiving.

After completion, startup is re-evaluated rather than jumping directly to
ChatList. Initialization reloads the identity so newly persisted signing keys
are not hidden by the old in-memory record; the temporary migration HTTP client
is closed. Initialization failures retain the normal startup-error route. A
missing migration manager is an error, never a fallback to ChatList.

## Copy amendment

This section supersedes the migration UX and retry claims in the original
Alpha2 migration draft and ADR-009 supplement; it does not amend their wire or
cryptographic architecture.

The screen now says:

> Security update
>
> This update replaces encryption sessions from an older version of PHANTOM.
> Conversations will need a new key exchange before messaging can resume.
>
> Your identity, existing contacts and saved messages are not deleted by this update.
>
> Keep PHANTOM installed and do not clear its data. If the update is interrupted,
> open the app again to continue.

Continue/Retry runs only the migration; Quit app does not erase data. Running
disables both actions. Rate-limited/unconfirmed publish failures offer retry.
Bad request, signing-key mismatch and missing identity offer exit/support rather
than a misleading retry. Other failures use stable generic retry copy. No raw
exception/server text, reinstall instruction, July 2026 username promise,
unverified read-only-history mode or mandatory QR re-add promise remains here.
Support text does not imply a new in-app support or recovery service.

All fourteen UI strings are English resources. Russian translation and language
selection remain separate; this change does not enable a partial Russian UI.

## Verification boundaries

Core tests use real migration/identity code with synthetic keys and repository
fakes; failures and cancellation are injected at each write/publish/cleanup
boundary. Android host tests exercise actual marker files on SDK 28 and 35,
startup routing and Compose actions. Source-order tripwires, including a negative
control, pin the complete container/service wiring; they are NOT a runtime test
of the whole Android graph or a physical power-loss experiment.

Final repeated host verification: 19/19 core migration tests, 342/342 selected
Android tests (migration, startup, AppContainer, services and onboarding), and
99/99 existing Paparazzi tests passed, with zero skipped tests in these runs.
The Android marker tests cover SDK 28 and 35; the native-layout test covers 320dp
at font scale 2. Debug APK assembly passed. Existing golden images and all prior
English resources are unchanged; fourteen migration resources were added.
The external ledger has runnable checks for the exact changed-file set and XML
results, plus manual review of the lifecycle and approval boundaries. No claim
is made that the entire repository test suite was run.

The initial host/emulator stage did not install on the phone. The subsequent
non-destructive phone update is recorded below. No user-data clearing,
production relay change, memory edit, or claim that current conversations are
broken is part of this work.

## Isolated Android validation

The follow-up uses a newly created disposable AVD, not either existing test AVD
or the phone. Measured runtime: Android 17 / API 37, arm64, 16,384-byte pages.
Wi-Fi and mobile data are disabled before installing the fixture; each test
asserts that Android has no active network before touching synthetic state.

`MigrationDeviceTest` is packaged only in the instrumentation APK. Without the
explicit `isolatedMigration` argument its methods are skipped. A wrong value
fails before seeding; a correct value still requires an emulator, no active
network, and (for seeding) an empty identity/conversation store. These are staged
methods, not five independent tests to run in arbitrary order.

Measured sequence:

1. Seed a synthetic legacy identity, contact with notes, saved message and old
   ratchet/sender-key sentinels through real SQLCipher and Keystore repositories.
2. Run migration through real cryptography, key repositories and AtomicFile.
   Suspend a test-only PreKeyApi at publication, after the durable marker and
   private keys exist. The host observes that checkpoint and force-stops the
   actual Android process while the operation is still suspended.
3. Start a new process: the real AppContainer enters AwaitingMigration, leaves
   messaging/group/call services absent, and preserves the identity, conversation,
   message, signing key, SPK, all 40 OPKs, and pre-cleanup session sentinels.
4. Replace the APK using `adb install -r` without clearing app data. Recheck the
   same pending state in another process. This is same-build APK replacement
   with a synthetic legacy record, NOT an archived Alpha-1-to-current APK test.
5. Launch the actual MainActivity. Its UI hierarchy contains Security update
   and Continue. Tap Continue while offline: the generic safe failure and Retry
   appear; a new process again proves the pending marker and data survived.
   FLAG_SECURE remains enabled, so screen captures are black; UI evidence is
   the accessibility hierarchy and actual interaction, not a visual sign-off.
6. Retry using a controlled Stored response from the test-only PreKeyApi. Verify
   old sessions are removed, conversations require rehandshake, and identity,
   contact notes and the saved message are unchanged. Insert new session
   sentinels, replace the APK again, and invoke migration in a new process.
   COMPLETE makes it a no-op: no publish and no repeat cleanup. Real AppContainer
   initialization then reaches Ready with a messaging service and unchanged data.

Only the server response is controlled in the successful path. No real relay
publication, peer rehandshake, network delivery or phone compatibility is proven
by this isolated emulator sequence.
Force-stop is a real process interruption, not a simulation of filesystem power
loss. The pre-fix ambiguous-state limitation above remains open.

### Reproduction

Use only a new disposable AVD named `Phantom_Migration_20260926` on port 5580.
Do not rename an existing user AVD to satisfy this check. Build both APKs with
the repository's Java 21 / Android SDK environment:

```sh
./gradlew :apps:android:assembleDebug :apps:android:assembleDebugAndroidTest --offline
node scripts/validation/migration-device.mjs /absolute/path/to/disposable-evidence
```

The script requires `ANDROID_HOME`, `python3` (standard XML parser), and Node.
It verifies the AVD name before any install/reset, targets only `emulator-5580`,
clears only that disposable app fixture at the beginning, and never clears
between migration stages. It records APK SHA-256, each instrumented result,
the killed process checkpoint, and UI XML. It can be rerun from a fresh synthetic
fixture. The ordinary host migration/startup subset was also rerun: 93 tests,
zero failures/errors/skips. The earlier wider host results above are historical,
not a claim that those entire suites were rerun in this emulator stage.

## Regression findings during verification

The expanded onboarding suite found an already-stale source contract: it expected
two `applyFinalizeOutcome` calls, but the earlier subscription restriction added
a third, explicit FailedBeforePersistence for a restored unavailable plan. The
test now separately requires two controller outcomes and one early subscription
refusal. Negative controls remove the refusal and add a duplicate outcome; both
must fail. Onboarding production code and subscription policy are unchanged.

A native-layout check at 320dp and font scale 2 found intrinsic text bounds
reporting horizontal overflow despite spare parent width (including an 8-character
button label measured at 101px inside a 224px allowance). Migration paragraphs
and action labels now occupy their available width; labels stay centered and
actions are stacked. The overflow assertion is retained, not relaxed.

## Physical phone update follow-up

On 2026-09-26, the same production-source changes were built as a release APK
with the existing PHANTOM signing key and installed using `adb install -r` on
one TECNO BF7 running Android 12. No uninstall, app-data clear or synthetic
legacy fixture was used on this phone. The original key was read only; signing
credentials were not copied into the repository or placed in command arguments.

- APK SHA-256: `ed5556ce27623302633bd3185c7980c3f3d59bef24ea7f4115f2fd8f452a6032`.
- Certificate SHA-256: `aa1709483ebd47f121ce0bd14692d1d575fd28a0d65c4b2e201abe882cab1f02`,
  matching the previously installed release. The installed APK hash matches.
- Release is not debuggable; both R8 safeguards passed, including the JNI
  contract and its stripping negative control.
- Package UID and first-install timestamp remained unchanged; last-update
  timestamp advanced. Two cold Activity launches succeeded, without an
  unexpected onboarding or migration screen.
- Paired UI hierarchies retained the same profile text fields and eight visible
  voice-message items with the same durations and timestamps. The localized day
  label and extracted QR accessibility description are expected presentation
  changes, not data-loss findings.
- The current app process reported `startReceiving_ok`, without observed
  `FATAL EXCEPTION`, `INIT FAILED` or `service_start_failed` in its scoped logs.

These observations prove neither equality of every database row nor successful
voice playback, peer delivery, calls or WSS readiness. The connection banner
still reported `Online / Verifying realtime`, as it did before the update.
The healthy phone was not forced into legacy migration. Interrupted migration
and cleanup correctness remain covered by the isolated fixture above, not by
this phone check. This is not a public release or a whole-app acceptance.
