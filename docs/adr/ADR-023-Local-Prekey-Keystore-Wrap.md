# ADR-023: Local prekey private-key wrap via Android Keystore

Status: proposed (2026-05-08; implementation lands in a follow-up PR)
Layer: shared/core/storage (KMP repository layer + Android-specific
crypto helper), apps/android (Android Keystore platform binding)
Extends: ADR-009 (Identity / Prekey Separation — defines *which* keys
exist; this ADR defines *how* they sit at rest)

## Context

Today the user's local SignedPreKey (SPK) and OneTimePreKey (OPK)
private keys live in two SQLite tables — `local_signed_pre_key` and
`local_one_time_pre_key` — as **hex-encoded raw bytes** in
`private_key_hex` columns (plus the `previous_private_key_hex` slot
on the SPK row that retains the rotated-out key during the 14-day
retention window).

The database file itself is encrypted at rest via **SQLCipher** with
a passphrase that is itself wrapped by an Android Keystore master key
(see `DatabasePassphraseManager` in `shared/core/storage/androidMain`).
That covers the cold-disk attack: a stolen powered-off device cannot
reveal the SQLite contents without the device's unlock authentication.

It does **not** cover three other scenarios:

1. **Memory-dump on a powered-on, unlocked device** (forensic
   imaging while the app holds the SQLCipher decryption key in
   process memory; root-level adversary; `/proc/<pid>/mem` access).
   Once the SQLCipher passphrase has unlocked the database, every
   `SELECT private_key_hex …` returns the raw private bytes into a
   process-readable Kotlin `String`.
2. **Decrypted-DB-leaving-the-device.** A backup workflow that
   exports an unencrypted SQL dump (`.backup`, ADB pull of the
   decrypted file when a debug app variant lowers the passphrase
   guard, etc.) ships the raw private bytes too.
3. **App-process compromise via shared content provider / IPC
   misuse.** Less likely on Android given app-sandbox isolation, but
   still in-class with the above.

These three scenarios are exactly the threat class **F22** in the
project's running security findings list calls out: SPK and OPK
private keys present in plaintext SQLite even though the database
itself is encrypted. The identity X25519 private key is already
Keystore-wrapped (separate ADR), and the Double Ratchet ratchet state
is its own follow-up. F22 closes the prekey leg.

## Decision

Wrap `private_key_hex` (and `previous_private_key_hex`) values with an
**Android Keystore AES-256-GCM master key** before they touch SQLite,
and unwrap them at the repository read path. The schema column type
stays `TEXT`; the value stored becomes
`Base64( iv[12] || ciphertext[N] || tag[16] )` instead of raw hex.

This is the same primitive `DatabasePassphraseManager` already uses
for the SQLCipher passphrase, with the same key-property profile:

- `KeyProperties.KEY_ALGORITHM_AES`
- `KeyProperties.BLOCK_MODE_GCM`
- `KeyProperties.ENCRYPTION_PADDING_NONE`
- `setKeySize(256)`
- `setUnlockedDeviceRequired(true)` — key only usable while the device
  is in an unlocked state (since boot, not since-last-unlock — the
  weaker but UX-acceptable tier; matches the existing precedent)

A new helper, `KeystoreBlobCipher` (in `:shared:core:storage`
`androidMain`), exposes `wrap(plaintext: ByteArray): ByteArray` and
`unwrap(ciphertext: ByteArray): ByteArray`. The Android prekey
repositories (`SqlDelightLocalSignedPreKeyRepository`,
`SqlDelightLocalOneTimePreKeyRepository`) consume this helper through
a tiny `LocalKeyMaterialCipher` interface so non-Android targets
remain compilable (the JVM `actual` is a no-op pass-through; it
exists only for unit-test fixtures and any future desktop port that
will need its own platform-specific implementation).

A separate Keystore alias is used for prekey wrap
(`phantom_prekey_wrap_v1`) rather than reusing the
`phantom_db_passphrase_key` alias. Two reasons: (a) the master keys
have different cryptographic responsibilities and rotating one without
the other becomes a single SharedPreferences edit instead of a
re-derive-everything migration; (b) the two roles can later acquire
different KeyProperties policies (e.g. `setUserAuthenticationRequired`
on the prekey wrap if a future hardening pass wants per-message
biometric for the X3DH bootstrap path) without disturbing the DB layer.

### Why this approach (and not the alternatives)

- **Rejected: encrypt the entire prekey row server-side and ship the
  ciphertext to the client.** Breaks the relay's invariant that
  prekey *public* halves are publishable bytes, ties relay storage to
  client crypto, and re-introduces a server-trust dependency the
  threat model explicitly avoids.
- **Rejected: derive the wrap key from a user-entered passphrase.**
  Adds an unlock-prompt step to message receive, which destroys the
  passive-receive UX that store-and-forward delivery relies on.
- **Rejected: rely on SQLCipher alone.** That is exactly the gap
  this ADR closes. Defence in depth: SQLCipher protects cold disk,
  Keystore wrap protects warm memory + decrypted-export paths.
- **Rejected: rotate the existing identity-key wrap to also cover
  prekeys.** Conflates identity-key access (always required for
  signing operations, hot path) with prekey access (only required
  on the initial X3DH handshake, cold path). Different access
  cadences benefit from independent KeyProperties going forward.

### Why the migration is "lazy wrap on first re-write"

The schema column stays `TEXT`. On read, the repository attempts to
Base64-decode + GCM-unwrap; on a `BadPaddingException` (or
similar parse failure), it falls back to the legacy path of treating
the column value as raw hex bytes. The next write of that row stores
the wrapped form. Within at most one reconnect-driven
`maybeReplenishOneTimePreKeys` cycle, all rows are wrapped.

The alternative — eager schema migration that adds a
`private_key_wrapped_blob` column, copies + wraps every row in a
single transaction, then drops the legacy column — is cleaner long
term but riskier short term: a power loss mid-migration on a device
with a large OPK pool leaves the table in a half-and-half state. Lazy
migration trades one extra hex-vs-blob branch in the read path for
zero migration-window failure modes.

## Threat model consequences

What F22 closure **adds**:

- A passive forensic image of an unlocked device's RAM (e.g.
  cold-boot-attack-class) no longer hands over readable SPK / OPK
  private bytes. The attacker sees the GCM ciphertexts; the master
  key is in TEE / StrongBox where applicable and never readable as
  plaintext.
- An accidental decrypted-DB export (debug build, backup tool that
  bypasses SQLCipher) likewise exposes only the wrapped bytes.

What F22 closure **does not change**:

- Application-level code that legitimately needs the unwrapped
  bytes — the X3DH bootstrap path on incoming first message — still
  unwraps in process memory for the duration of one handshake.
  Defence is against cold imaging, not active in-process attackers
  with code-execution capability.
- The relay still sees the same wire bytes as before. F22 is a pure
  client-side change; no protocol bump.
- The Double Ratchet state itself (the per-conversation forward-
  secrecy machinery) remains unwrapped on disk for now. Wrapping
  that is a separate finding (F8 in the project's security ledger)
  and gets its own follow-up ADR; the same `KeystoreBlobCipher`
  helper from this ADR is reusable there.

## Known limitations

- **Background message receive on a locked phone, first contact
  case.** With `setUnlockedDeviceRequired(true)`, the master key is
  unavailable while the device is in a fully-locked state. If the
  app receives an X3DH-initiated message in the background while the
  phone is locked, the bootstrap unwrap of the OPK will fail; the
  message stays queued until the next unlock. This is identical to
  Signal's "Secure Folder" UX trade and is acceptable. Subsequent
  messages in the same conversation use the Double Ratchet state
  and do not invoke the prekey wrap path again.
- **iOS port.** Not addressed by this ADR. The iOS equivalent is
  the iOS Keychain with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`,
  but iOS support is post-Alpha-2 (see ADR-019 future-work section
  for the iOS XCFramework planning). When the iOS target lands, a
  follow-up ADR records the parallel decision.
- **Key rotation.** Rotating the `phantom_prekey_wrap_v1` master
  key requires a one-time re-encrypt pass over all prekey rows. Not
  addressed here — there is no rotation policy yet because no
  triggering event has happened. Documented as future work.

## Implementation plan

Two PRs, sized so each is independently review-able and revertable:

1. **PR-1 (helper + repository wraps).** Adds
   `KeystoreBlobCipher` (Android impl + JVM no-op), the
   `LocalKeyMaterialCipher` interface in commonMain, wires it through
   the two prekey repositories' constructors, updates `toEntity()`
   read paths to attempt unwrap-then-fall-back-to-legacy, updates the
   `upsert*` write paths to wrap before SQL bind. Adds unit tests for
   the wrap roundtrip plus the legacy-fallback decode path.
2. **PR-2 (Android instrumented verification).** Adds an
   `androidInstrumentedTest` that exercises the real Android Keystore
   on an emulator: writes a prekey row, reads it back, asserts
   roundtrip equality + that the on-disk `private_key_hex` column
   value is **not** the raw hex (visible-bytes regression test). Same
   test rig as the existing `LibsodiumX3DHTest`.

Total estimated effort, single-developer-through-AI: ~3 working days.

## Test plan

- Unit (commonTest): roundtrip wrap/unwrap, legacy-format detection,
  empty/short-input edge cases.
- Unit (androidUnitTest): Android Keystore mocked via Robolectric
  where possible; assert KeyGenParameterSpec matches the spec above.
- Instrumented (androidInstrumentedTest): real Android Keystore round
  trip; SQL inspection that stored value is base64 ciphertext, not
  hex; lock-screen behaviour smoke test on an emulator with PIN.

## References

- ADR-009 (identity-prekey-separation) — defines the keys this ADR
  protects at rest
- `DatabasePassphraseManager.kt` — existing Android Keystore wrap
  pattern that this ADR mirrors
- `SqlDelightLocalSignedPreKeyRepository.kt` /
  `SqlDelightLocalOneTimePreKeyRepository.kt` — the two repository
  classes that gain wrap/unwrap responsibility
- Project security ledger: F22 (this finding); F8 (Double Ratchet
  state plaintext SQLite — separate follow-up that will reuse this
  ADR's helper)
