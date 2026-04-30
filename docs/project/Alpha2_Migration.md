# Alpha 1 → Alpha 2 migration

**Status:** Draft, written alongside PR C (Phase 1 Week 4).
**Audience:** Phantom contributors, Alpha-1 testers, future auditors.
**Owner:** `phase1/week4/sessionmanager-rewrite-migration` branch.

This document explains everything that changes between Alpha 1 (master at
`8fa020ae`, before PR C) and Alpha 2 (master after PR C). Read it before
shipping Alpha 2 to testers — the migration is **breaking** for existing
ratchet states, and testers need to understand what they'll see.

---

## What changes — at a glance

| Layer | Alpha 1 | Alpha 2 |
|---|---|---|
| Session bootstrap | `x3dh.computeSharedSecret(my_identity_priv, their_identity_pub)` (raw scalarmult, no prekeys, no HKDF) | Real X3DH 4-DH per ADR-009: SignedPreKey + optional OneTimePreKey, HKDF-SHA256, salt = SHA256("phantom-x3dh-v2") |
| Initial ratchet keypair | **Identity DH keypair reused** as the sending ratchet seed (F15) | Fresh ephemeral DH keypair generated per session — identity NEVER touches the ratchet |
| Identity layer | Single X25519 keypair (Curve25519 DH) | X25519 (unchanged, public-facing) **+ Ed25519 signing keypair** (new, used only for SignedPreKey signatures) |
| Relay knowledge | None of the prekey concepts | `/prekeys/publish`, `/prekeys/bundle/{x25519}`, `/prekeys/status/{x25519}`, `/prekeys/{x25519}/opk/{keyId}` (PR B, already on master) |
| Wire format (first message in a session) | EncryptedMessage only | EncryptedMessage **plus** an unencrypted `x3dhInit` header carrying `{ ephemeralPubKeyHex, spkKeyId, opkKeyIdHex? }` |
| Local prekey state | None | `signed_pre_key` table (1 row per identity) + `one_time_pre_keys` table (up to 200 OPKs) |

Everything else — message DB schema, conversation list, profile/settings,
QR code format, WebSocket envelope routing — is **unchanged**.

---

## Identity model — the subtle change

A common confusion: "the migration changes my identity." It does not — it
**adds** a key.

Alpha 1's `IdentityRecord.publicKeyHex` was a 32-byte X25519 public key.
That value is what users see in their QR codes, what the relay routes
WebSocket envelopes by, and what shows up next to a contact's name. **None
of that changes in Alpha 2.** The X25519 public key value is preserved
byte-for-byte across the migration, so existing QR codes still scan and
existing contacts still resolve.

What Alpha 2 adds is a **separate Ed25519 signing keypair**, stored
alongside the X25519 keypair on the same `IdentityRecord`. The Ed25519
key serves one purpose only: signing the SignedPreKey bundle that the
relay serves to anyone wanting to start a session with you. The relay
accepts both keys at publish time and serves both back to fetchers; the
fetcher verifies the SPK signature against the bundle's Ed25519 key.

Why two keys instead of one? Because X25519 (Curve25519 used as a DH
group element) and Ed25519 (Edwards-curve signature scheme) are different
cryptographic objects. You cannot sign with an X25519 key, and you cannot
do DH with an Ed25519 key without converting it (libsodium provides the
conversion in one direction only). For Alpha 2 we keep them separate to
avoid breaking the existing X25519 identity surface; future work could
unify around Ed25519 with a derived X25519 (Signal's approach) but only
at the cost of changing every existing user's public key value, which we
explicitly chose not to do.

---

## Migration trigger — what users see

On the first launch after upgrading from Alpha 1 to Alpha 2, the app
detects an old-format identity (`signingPublicKeyHex` is null) and shows
the **MigrationScreen**:

> **Security Update**
>
> PHANTOM has upgraded its cryptographic protocol to match Signal Protocol architecture. Existing conversations need to be re-established with proper key exchange.
>
> Tap Continue to wipe local conversation state. You'll need to re-add contacts via QR code. Past messages will be preserved as read-only history.
>
> Your identity stays the same — your QR code and your existing contacts will recognize you. Once Phase 2 brings unique @usernames (planned July 2026), your identity will support that as well.
>
> **[Continue]   [Quit app]**

There is no "Cancel". The Alpha 1 protocol is gone — once the app sees
the new code path it cannot fall back. A user who refuses to migrate
can `Quit app` and uninstall, but they cannot continue to use Alpha 2
with Alpha 1 sessions.

On `Continue`:

1. Generate a fresh Ed25519 signing keypair, attach it to the existing
   `IdentityRecord` (X25519 keys preserved).
2. Generate a SignedPreKey + 100 OneTimePreKeys, sign the SPK with the
   new Ed25519 key, publish to the relay via `POST /prekeys/publish`.
3. Wipe **all** existing `RatchetState` rows. They were all rooted in the
   F15-vulnerable identity-as-ratchet-seed bootstrap; keeping them would
   defeat the security upgrade.
4. Wipe **all** SenderKey states. (They're per-conversation and depend
   on session keys we just invalidated. Will be re-established on first
   group message in Alpha 2 group sessions; non-group conversations
   don't use SenderKey at all.)
5. Mark every conversation `needsRehandshake = true` so the chat list
   shows a discrete "needs re-handshake" badge.
6. Persist the past plaintext messages **as read-only history**. Users
   can still scroll through what they sent and received before the
   migration, but the chat input is locked until the contact is re-added
   and a new session bootstraps via X3DH 4-DH.

---

## Recovery scenarios

### Migration fails mid-flight (network drops during prekey publish)

The migration is designed to be **idempotent and resumable**. The order
of operations above is deliberate: the Ed25519 keypair is generated and
saved BEFORE prekeys are uploaded. If the upload fails (no network, relay
down), the app retries on next foreground. Until the upload succeeds,
no other client can fetch the user's bundle, so no broken sessions get
created — they just can't start yet.

If the wipe step (step 3) starts but is interrupted, the partial state
is detected on next launch (mixed old + missing ratchet states) and the
wipe completes. The MigrationScreen is **idempotent** — it can be shown
twice in a row without harm.

### User wants to see their old messages

`messages` table is preserved. The chat list shows every conversation
that had at least one message, ordered as before. Tapping a conversation
opens the message view in read-only mode with a banner: "Re-add this
contact via QR code to continue the conversation." Past plaintext is
visible.

### Contact has not migrated yet

When user-A (on Alpha 2) tries to send to user-B (still on Alpha 1):

1. user-A's client looks up user-B's bundle on the relay.
2. user-B has not migrated → no bundle published → `GET /prekeys/bundle`
   returns 404.
3. user-A's send retry loop holds the message in the outbox with status
   `WAITING_FOR_RECIPIENT_MIGRATION`. The chat shows a single-line notice:
   "Waiting for {contact} to update PHANTOM."
4. Once user-B migrates and publishes their bundle, user-A's next send
   attempt succeeds and the queued message ships.

There is no Alpha-1-side fallback. Alpha 1 testers who refuse to migrate
will see incoming messages from Alpha 2 contacts fail to decrypt
(unrecognized handshake header) — by design.

---

## Wire-format changes

### `x3dhInit` header

The first message in a fresh session carries an extra unencrypted header:

```json
{
  "ephemeralPubKeyHex": "<64 hex chars>",
  "spkKeyId": <int>,
  "opkKeyIdHex": "<32 hex chars>"  // null if recipient's OPK pool was empty
}
```

This sits next to the encrypted `payload` blob in the existing
`RelayMessage.Send` envelope. The relay never inspects it (forwards
verbatim). The recipient client uses it to bootstrap its own RatchetState
via `x3dh.recipientHandshake4DH(...)` BEFORE attempting to decrypt the
payload.

Subsequent messages in the session do **not** carry this header — they
follow the existing Double Ratchet wire format unchanged.

### `signing_pubkey_hex` on `/prekeys/publish` and bundle responses

`PublishRequest` (PR B, before PR C) had only `identity_pubkey_hex`. PR C
extends it:

```json
{
  "identity_pubkey_hex": "<X25519 hex>",
  "signing_pubkey_hex":  "<Ed25519 hex>",   // NEW
  "signed_pre_key": { ... },
  "one_time_pre_keys": [ ... ]
}
```

`identity_pubkey_hex` remains the X25519 routing identity (unchanged from
Alpha 1). `signing_pubkey_hex` is the Ed25519 verifying key the relay
uses to validate the SignedPreKey signature. The bundle response
(`GET /prekeys/bundle/{identity}`) likewise gains a `signing_pubkey_hex`
field so initiators can verify the SPK signature client-side.

This is a backward-incompatible additive change to PR B's wire format —
old clients that don't supply `signing_pubkey_hex` will get 400 from the
relay. Acceptable because no production client published bundles to the
relay before PR C.

---

## Local storage changes

### New tables

```
signed_pre_keys
  identity_pubkey_hex TEXT PRIMARY KEY
  spk_key_id          INTEGER NOT NULL
  spk_public_key      BLOB    NOT NULL
  spk_private_key     BLOB    NOT NULL
  spk_created_at_ms   INTEGER NOT NULL
  spk_signature       BLOB    NOT NULL

one_time_pre_keys
  key_id_hex          TEXT PRIMARY KEY  -- 16-byte server-generated ID
  identity_pubkey_hex TEXT    NOT NULL
  public_key          BLOB    NOT NULL
  private_key         BLOB    NOT NULL
  uploaded_at_ms      INTEGER NOT NULL
```

Both are **per-identity** (the local user's identity); a single device is
one row in `signed_pre_keys` and N rows in `one_time_pre_keys`. The
recipient bootstrap path consumes from this OPK pool when a peer's
`x3dhInit` header references one of our `key_id_hex` values.

### Identity record extension

```kotlin
data class IdentityRecord(
    val id: String,
    val username: String,
    val publicKeyHex: String,         // X25519 — unchanged
    val dhPrivateKeyHex: String,      // X25519 — unchanged
    val signingPublicKeyHex: String?, // Ed25519 — NEW (null on Alpha 1)
    val signingPrivateKeyHex: String?,// Ed25519 — NEW (null on Alpha 1)
    val createdAt: Long,
)
```

The two new fields are nullable to keep the schema backward-readable. On
Alpha 1 → Alpha 2 migration, both fields are populated together (atomic
write); checking for either being null is the migration-needed flag.

### Future Keystore wrapping (PR E, ADR-pending)

PR E will wrap `RatchetState`, `signing_private_key`, and OPK private keys
in Android Keystore (and equivalent on iOS). PR C **does not** depend on
this — it stores private keys in the SQLCipher-encrypted SQLite (same as
Alpha 1's identity private key). Keystore wrapping is purely a hardening
step on top of the existing at-rest encryption.

---

## Tests that prove the migration works

PR C lands the following test suites:

- **SessionManager 4-DH round-trip** — initiator + recipient derive the
  same root key after running through the full prekey-fetch +
  initiator/recipient handshake path. Asserts `rootKey` matches.
- **F12 closure test** — asserts that `SessionManager.startSession` calls
  `X3DHProtocol.initiatorHandshake4DH` (verified via mock spy). A
  regression that re-introduced the bypass would call `computeSharedSecret`
  and the test would fail.
- **F15 invariant test** — after `SessionManager.startSession` the
  resulting `RatchetState.sendingRatchetPublicKey` is structurally
  asserted NOT equal to `localIdentity.publicKey.bytes`. The same check
  for `sendingRatchetPrivateKey`.
- **Migration detection test** — given an `IdentityRecord` with null
  `signingPublicKeyHex`, the migration trigger fires; with non-null, it
  doesn't.
- **Migration wipe test** — after migration runs, `RatchetStateRepository`
  is empty AND a new prekey bundle has been queued for upload.
- **2-device integration test** — Alice and Bob each have their own
  `DefaultMessagingService` instances backed by in-memory fakes; Alice
  publishes a bundle, Bob fetches it, Alice sends "hi", Bob receives
  "hi" decrypted. End-to-end through the new code path.

CI must pass all of these on JVM target before PR C is mergeable.

---

## What this migration explicitly does NOT change

- **QR code format**: still encodes X25519 publicKeyHex. New users in
  Alpha 2 generate Ed25519 alongside but the QR code shape is identical.
- **WebSocket protocol**: send/deliver/ack/typing/read-receipt frames
  unchanged. The new `x3dhInit` field is additive on the `send`/`deliver`
  payload only when bootstrapping a fresh session.
- **Relay routing**: the relay still routes by X25519 identity. The
  Ed25519 signing key is a property of the identity record stored in the
  prekey table, not a new routing primitive.
- **Sealed Sender**: unchanged. SealedSender still wraps the Double
  Ratchet output exactly as in Alpha 1.
- **Username (when shipped in Phase 2)**: ties to X25519 identity, which
  doesn't change. Migration users keep their username (when usernames
  exist).

---

## Open questions / deferred decisions

- **Ed25519 ↔ X25519 derivation.** Signal derives one from the other so a
  user has "one identity". Alpha 2 keeps them independent. Tracked as a
  post-Beta refactor; no security impact in Alpha 2 because the Ed25519
  key is bound to the X25519 identity through the `signing_pubkey_hex`
  field on the published bundle (relay enforces 1:1 mapping).
- **Cross-device identity.** Alpha 2 has no second-device concept; if a
  user installs Phantom on a new phone they get a new identity and re-add
  contacts. Multi-device is a separate roadmap item (post-Phase 2).
- **Bundle authentication for first contact.** Currently when Alice
  fetches Bob's bundle, she trusts the SPK signature because she
  recognizes Bob's Ed25519 key from the QR code she scanned. Until QR
  codes carry Ed25519 (Phase 2), Alice cannot verify the bundle's
  Ed25519 belongs to Bob. This is the same trust assumption as Alpha 1's
  X25519 QR code: the user vouches for the channel by which they got
  the key. Documented as a known limitation, not a regression.

---

## Update log

| Date       | Change                                          |
|------------|-------------------------------------------------|
| 2026-04-30 | Initial draft alongside PR C work in progress.  |
