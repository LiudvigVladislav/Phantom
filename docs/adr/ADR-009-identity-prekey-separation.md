# ADR-009: Identity / Signed PreKey / One-Time PreKey Separation

**Status:** Proposed (CRITICAL — security blocker for public release)
**Date:** 2026-04-29
**Author:** Vladislav Liudvig
**Reviewers:** (Claude Code post-implementation; external crypto reviewer at Phase 6)

## Context

Security audit `2026-04-29` identified two architectural blockers in the
current Alpha 1 ratchet bootstrap:

- **F12** — `X3DHProtocol` interface defines `initiatorHandshake` and
  `recipientHandshake`, but `SessionManager.getOrCreateSession` calls
  only `computeSharedSecret(...)` (raw X25519 scalarmult). The X3DH
  layer is bypassed; the four-branch DH the protocol is supposed to
  perform never runs. Result: no asynchronous key agreement, no
  signed-prekey verification, no one-time-prekey forward secrecy.

- **F15** — In the same `getOrCreateSession`, the long-term identity
  X25519 private key is seeded as the initial
  `sendingRatchetPrivateKey` (`SessionManager.kt:40-41`). The Double
  Ratchet specification requires this to be a **fresh ephemeral DH
  keypair per session** independent of the identity key. Reusing the
  identity key as the bootstrap ratchet key means: any compromise of
  the persisted ratchet state (which is itself plaintext in SQLite —
  finding F8) yields the long-term identity private key, enabling
  permanent impersonation. This is a Signal Protocol invariant
  violation.

Both findings together mean current Alpha 1 sessions:

1. Have no forward secrecy on bootstrap (until first DH ratchet step)
2. Are vulnerable to permanent identity compromise via ratchet state
   read

This is incompatible with PHANTOM's product positioning ("privacy-first
E2EE"), with Kickstarter narrative, and with any future security audit.
This ADR specifies the fix.

### Current state of code

- `shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/X3DHProtocol.kt`
  — interface has 3-DH variant (DH1/DH2/DH3), no OPK, no signature
  verification at protocol layer
- `shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/LibsodiumX3DH.kt`
  — implements all three methods (`initiatorHandshake`,
  `recipientHandshake`, `computeSharedSecret`); `initiatorHandshake`
  and `recipientHandshake` are present but unused
- `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/SessionManager.kt:35-41`
  — bypasses X3DH, uses raw scalarmult + identity key as ratchet seed
- `services/relay/src/routes.rs` — no prekey endpoints
- `services/relay/src/state.rs` — no prekey storage

### Why decide now

ADR-009 is the prerequisite for Phase 1 Weeks 3–6 work. Both client
refactor and relay endpoint additions need a locked wire format before
implementation begins. Phase 1 spillover risk is greatest if this ADR
is incomplete or revised mid-implementation.

## Decision

### Key hierarchy

The implementation adopts the standard Signal three-key system, with
an explicit `4-DH variant` X3DH (DH1/DH2/DH3/DH4):

```
Identity Key Pair (long-term, X25519 + Ed25519)
  Purpose:
    - Ed25519 public part used to sign Signed PreKeys
    - Ed25519 verifies Verification Authority certificates (ADR-008)
    - X25519 used in X3DH DH1/DH2 branches
  Lifecycle:
    - Generated once at onboarding (or imported via recovery phrase, ADR-012)
    - NEVER changes for the lifetime of the account
    - Stored in SQLCipher, X25519 private key Keystore-wrapped
      via existing `KeystoreIdentityRepository`
  Crucial property:
    - NEVER used directly as a Double Ratchet sending or receiving
      ratchet key

Signed PreKey (medium-term, X25519)
  Purpose:
    - Used as DH input in X3DH branches DH1 and DH3
    - Replaces identity key in the role of "stable DH peer" the
      initiator can DH with offline
  Lifecycle:
    - Generated client-side at onboarding
    - Signed by identity Ed25519 key:
        signature = Ed25519.sign(IK_priv, "phantom-spk-v1" || SPK_pub || timestamp)
    - Uploaded to relay via RelayMessage::UploadSignedPreKey (WS)
    - Rotated by client every 7 days
    - Relay retains previous SPK for 14 days after rotation to
      tolerate in-flight handshakes
  Stored:
    - Client: SQLCipher, X25519 private key Keystore-wrapped
    - Relay: prekeys.signed_prekey table (public part + signature only)

One-Time PreKey (single-use, X25519)
  Purpose:
    - Used as DH input in X3DH branch DH4 (provides forward secrecy
      for the very first message before any ratchet step)
  Lifecycle:
    - Generated client-side in batches of 100
    - Public keys uploaded to relay via RelayMessage::UploadOneTimePreKeys (WS)
    - Each public key consumed at most once during peer X3DH bundle fetch
    - Relay deletes consumed OPK from its store
    - Client refills batch when remaining < 20 (relay signals via
      response header X-PreKey-Remaining)
  Stored:
    - Client: SQLCipher, X25519 private keys Keystore-wrapped
    - Relay: prekeys.onetime_prekey table (public keys only, atomic
      consume on bundle fetch)

Ephemeral Ratchet Keys (per-message-step, X25519)
  Purpose:
    - Driven by the Double Ratchet protocol — fresh keypair per ratchet
      step, used in DH calculation between sending and receiving chains
  Lifecycle:
    - Generated fresh for each ratchet step
    - Never persisted long-term (private key zeroized after use)
  Crucial property:
    - Generated fresh at session bootstrap; identity key MUST NOT
      appear in the initial sendingRatchetPrivateKey field of
      RatchetState (this is the F15 fix)
```

### X3DH handshake (4-DH variant with optional OPK)

```
Initiator (Alice) wants to message Recipient (Bob), Bob's identity
public key known via QR / invite link / username directory.

1.  Initiator sends RelayMessage::FetchPreKeyBundle { for_identity: bob_pub }
    over the existing authenticated WebSocket session.
    Reply: RelayMessage::PreKeyBundleResponse {
      for_identity:            bytes32,
      signed_prekey:           bytes32,
      signed_prekey_signature: bytes64,
      one_time_prekey:         optional bytes32,
      one_time_prekey_id:      optional bytes16,
      remaining_opk_pool:      u32
    }

2.  Initiator verifies signed_prekey_signature with bob_identity_ed25519
    pubkey (the Ed25519 half of bob's identity keypair). FAIL → abort
    handshake, surface error to UI.

3.  Initiator generates ephemeral DH keypair (EK_pub, EK_priv).

4.  Initiator computes:
    DH1 = X25519(IK_a_priv, SPK_b_pub)              // identity ↔ signed prekey
    DH2 = X25519(EK_a_priv, IK_b_x25519_pub)        // ephemeral ↔ identity
    DH3 = X25519(EK_a_priv, SPK_b_pub)              // ephemeral ↔ signed prekey
    DH4 = X25519(EK_a_priv, OPK_b_pub) if OPK_b_pub // ephemeral ↔ one-time
    rootKey = HKDF(
      ikm  = DH1 || DH2 || DH3 || DH4,
      salt = SHA256("phantom-x3dh-v2"),
      info = "phantom-x3dh-rootkey-v1",
      L    = 32,
    )

5.  Initiator initializes RatchetState with:
      rootKey                  = rootKey   (32-byte HKDF output)
      sendingRatchetKeyPair    = freshly generated DH keypair (NEW)
      sendingRatchetPrivateKey = NEW.priv          // Q1 fix: NOT IK_priv
      sendingRatchetPublicKey  = NEW.pub
      sendingChainKey          = null until first send
      receivingChainKey        = null until first receive
      receivingRatchetPublicKey= SPK_b_pub         // for first DH ratchet step

    The Double Ratchet itself derives both chain keys on the first DH
    ratchet step from rootKey + DH(NEW.priv, SPK_b_pub) per spec —
    matching libsignal behavior, not splitting the X3DH output ourselves.

6.  Initiator sends first encrypted message; the message header carries:
      ek_pub               (initiator's ephemeral public key)
      one_time_prekey_id   (if used)
      ratchet_pub          (initiator's first ratchet public key)
      counter              0

Recipient (Bob) on first inbound message from Alice:

1.  Bob has IK_priv, SPK_priv, OPK_priv (looked up by one_time_prekey_id)
    locally.

2.  Bob recomputes the same four DHs:
    DH1 = X25519(SPK_b_priv, IK_a_pub)
    DH2 = X25519(IK_b_x25519_priv, EK_a_pub)
    DH3 = X25519(SPK_b_priv, EK_a_pub)
    DH4 = X25519(OPK_b_priv, EK_a_pub) if used
    master_secret = HKDF(...) // same construction as initiator

3.  Bob initializes RatchetState mirror-side and zeroizes the consumed
    OPK_priv from local storage.
```

### Relay endpoints — WebSocket-message based (Q2 confirmed: per-identity authenticated)

All prekey operations travel as authenticated `RelayMessage` types over
the existing WebSocket session. The session itself authenticates the
sender via the connection identity (already known to the relay from
the `?id=` parameter and, post Phase 1 P2 batch, from the signed
challenge of ADR-018). This means **rate limits apply to authenticated
requesting identities, not source IPs** — VPN rotation cannot bypass
them. There is no anonymous HTTP endpoint that an unauthenticated
attacker can hit to drain a target's OPK pool.

```
RelayMessage::UploadSignedPreKey
  // Sent: client → relay
  fields:
    spk_pubkey: hex32
    signature:  hex64        // Ed25519(IK_priv,
                             //   "phantom-spk-v1" || spk_pubkey || timestamp)
    timestamp:  i64

  Relay action:
    - identity_pubkey is taken from the authenticated connection
      (NOT supplied by the client to prevent spoofing)
    - Verify Ed25519 signature with identity Ed25519 pubkey
    - Reject if timestamp older than 24h or in future > 60s
    - If a current SPK exists, demote it to "previous" with
      expires_at = now + 14 days
    - INSERT new SPK as "current"
  Reply: RelayMessage::Ack { ok: true } or RelayMessage::Ack { ok: false, reason }

RelayMessage::UploadOneTimePreKeys
  // Sent: client → relay
  fields:
    one_time_prekeys: [ { id: hex16, pubkey: hex32 } ]   // 1..100

  Relay action:
    - identity_pubkey from authenticated connection
    - INSERT each onto onetime_prekey for this identity
    - Reject duplicate ids (client bug)
  Reply: RelayMessage::Ack { ok: true, remaining: u32 }

RelayMessage::FetchPreKeyBundle
  // Sent: client → relay
  fields:
    for_identity: hex32        // who I want to handshake with

  Relay action:
    - requesting_identity from authenticated connection (used for
      rate-limit accounting)
    - Rate-limit per requesting_identity:
        10 / minute, 100 / hour
      Exceeded → reply Ack { ok: false, reason: "rate_limited" }
    - If for_identity has no current SPK, reply Ack { ok: false,
      reason: "no_prekeys" }
    - Read current SPK + signature for for_identity
    - Atomically POP one OPK if pool nonempty (single transaction
      with PreKeyBundleResponse send to ensure exactly-once consume)
  Reply: RelayMessage::PreKeyBundleResponse {
    for_identity:            hex32,
    signed_prekey:           hex32,
    signed_prekey_signature: hex64,
    one_time_prekey:         hex32 | null,
    one_time_prekey_id:      hex16 | null,
    remaining_opk_pool:      u32          // hint for the target user
  }

RelayMessage::PreKeyStatus
  // Sent: client → relay
  // No fields. Asks for own prekey state.

  Relay action:
    - identity_pubkey from authenticated connection
    - Read SPK age, OPK pool size for THIS identity
  Reply: RelayMessage::PreKeyStatusResponse {
    signed_prekey_age_secs:   i64,
    one_time_prekey_remaining: u32,
    next_signed_prekey_due_secs: i64,
  }

RelayMessage::DeletePreKeys
  // Sent: client → relay (on account deletion only)
  Relay action: delete SPK and all OPKs for authenticated identity
  Reply: Ack
```

### SignedPreKey retention policy (Q3 offline >14d edge case)

The 14-day expiry applies **only to the PREVIOUS SPK** after rotation
(i.e. the SPK that has been replaced by a newer one). The **current
SPK is never expired by the relay** — it persists until the user
uploads a replacement.

This means:

- A client offline for >14 days remains reachable. The relay still
  has their current SPK (uploaded long ago, not replaced). Peers
  fetching the bundle receive that SPK; X3DH succeeds with the
  not-recently-rotated key.
- When the offline client reconnects, the client checks
  `RelayMessage::PreKeyStatus`. If `signed_prekey_age_secs > 7 days`,
  client immediately rotates: generates a new SPK, signs it, sends
  `RelayMessage::UploadSignedPreKey`. The previous (old) SPK is
  demoted with 14-day grace; in-flight handshakes initiated against
  it during the demotion window still succeed.
- Trade-off accepted: SPK can be older than 7 days for offline users.
  This is a confidentiality-vs-availability trade-off matching Signal's
  behavior: the alternative (auto-expire current SPK without
  replacement) would render the user unreachable.

Future improvement (Beta+): if SPK age > 30 days at handshake time,
relay can include a hint in `PreKeyBundleResponse` so the initiator's
UI shows "this contact's keys are unrotated; consider verifying via
QR / safety number before sending sensitive content."

Schema (SQLite via `services/relay/src/state.rs`, in-memory + journal):

```sql
CREATE TABLE signed_prekey (
  identity_pubkey BLOB PRIMARY KEY,
  spk_pubkey      BLOB NOT NULL,
  signature       BLOB NOT NULL,
  uploaded_at_ms  INTEGER NOT NULL,
  expires_at_ms   INTEGER     -- NULL until rotated
);
CREATE TABLE onetime_prekey (
  identity_pubkey BLOB NOT NULL,
  opk_id          BLOB NOT NULL,
  opk_pubkey      BLOB NOT NULL,
  uploaded_at_ms  INTEGER NOT NULL,
  PRIMARY KEY (identity_pubkey, opk_id)
);
CREATE INDEX idx_opk_remaining
  ON onetime_prekey (identity_pubkey);
```

### Client wire format (`MessagePayload` extension)

The first message from initiator to recipient carries an optional
`x3dh_init` field so the recipient can identify which OPK was consumed:

```kotlin
@Serializable
data class MessagePayload(
    val text: String,
    val sentAt: Long,
    val senderUsername: String = "",
    val type: String = "message",
    val targetMessageId: String = "",
    // NEW (ADR-009):
    val x3dhInit: X3DHInit? = null,
)

@Serializable
data class X3DHInit(
    val ephemeralPublicKey: ByteArray,
    val oneTimePreKeyId: ByteArray? = null,
    val signedPreKeyTimestamp: Long, // identifies which SPK rotation was used
)
```

`x3dhInit` is present on the very first message of a session and absent
on all subsequent messages. The recipient examines `x3dhInit.oneTimePreKeyId`
to look up the local OPK private key, performs the recipient handshake,
zeroizes that OPK, then proceeds with normal ratchet decrypt.

### Test vectors (Phase 1 W4 deliverable)

The PR that lands ADR-009 ships test vectors covering:

1. Round-trip handshake: initiator and recipient compute identical
   master_secret across all four DH branches
2. Round-trip handshake without OPK: identical master_secret with
   DH4 omitted
3. Signed prekey signature failure: initiator aborts, no session
   created, identity key never leaks
4. OPK exhaustion: server returns bundle without OPK, handshake
   succeeds with 3-DH variant
5. SPK rotation mid-handshake: in-flight handshake with previous SPK
   still succeeds against expiring entry
6. Replay protection: same OPK_id consumed twice — second attempt
   yields different bundle (relay deleted OPK after first use), so
   replay produces handshake mismatch
7. Migration: Alpha 1 ratchet state present in DB; on app launch,
   client wipes it and bootstraps via X3DH — identity public key
   unchanged across migration

Test vectors stored under
`shared/core/crypto/src/commonTest/kotlin/phantom/core/crypto/x3dh/`.

## Migration

### Identity preservation (Q1 confirmed 2026-04-29)

Migration **does not** regenerate the long-term identity keypair
`(IK_pub, IK_priv)`. Identity remains stable; only ratchet bootstrap
is fixed to use a fresh ephemeral DH keypair `(EK_pub, EK_priv)` per
session, plus signed prekey + optional one-time prekey per X3DH spec.
**Identity keys remain stable across the migration; peers do not see
a new identity.**

This means:

- Existing QR codes and invite links remain valid
- Username binding (planned ADR-007) is not invalidated
- Verification certificates (planned ADR-008) tied to identity remain
  valid
- Peers do not receive a "new account" or "key changed" notification

What **does** happen in migration:

1. On first launch of Alpha 2 build, client detects schema version
   transition or absence of uploaded SignedPreKey
2. Client **wipes all entries from `ratchet_state` table** (Alpha 1
   entries are protocol-incompatible and contain identity-key-as-DH
   violation)
3. Client wipes all entries from `sender_key_store` table (Alpha 1
   SenderKey state is also being rewritten in F3 / F4 / F13 fixes;
   group sessions also re-bootstrap)
4. Client generates fresh `SignedPreKey` + 100 `OneTimePreKey`s and
   uploads to relay
5. All existing `conversation` rows are tagged `needs_re_handshake = 1`
   (new schema column). Past messages remain readable. The next
   outbound message from the user opens a new X3DH session with the
   peer's now-required prekey bundle.

### Migration UX (per Vladislav 2026-04-29 confirmed)

A one-time `MigrationScreen` is shown on first launch after Alpha 2
install when old ratchet states are detected. Copy:

> **Security Update**
>
> PHANTOM has upgraded its cryptographic protocol to match Signal
> Protocol architecture. Existing conversations need to be
> re-established with proper key exchange.
>
> **Your identity stays the same** — your QR code and your existing
> contacts will recognize you. Once Phase 2 brings unique @usernames
> (planned July 2026), your identity will support that as well. Only
> the per-conversation encryption keys are being refreshed.
>
> Tap **Continue** to refresh local conversation state. Past messages
> are preserved as read-only history; the next message you send will
> automatically establish a new secure session with that contact.
>
> Why this matters: Your long-term identity key is now properly
> separated from per-conversation encryption keys, matching how
> Signal works. Compromise of message keys no longer affects your
> account identity.
>
> [ Continue ]    [ Quit app ]

If user taps "Quit app", the app exits without modifying local
state — they can re-launch at any time. There is no "keep using
Alpha 1" path because the Alpha 1 protocol is removed in this build.

### Detection

```kotlin
val migrationNeeded = ratchetStateRepository.count() > 0 &&
                       !preferences.contains("phantom.alpha2.bootstrap_done")
```

After successful prekey upload + ratchet wipe:

```kotlin
preferences.edit { putBoolean("phantom.alpha2.bootstrap_done", true) }
```

## Consequences

### Positive

- Closes F12 and F15, the two highest-severity findings of the
  2026-04-29 audit
- Brings PHANTOM ratchet bootstrap to Signal Protocol parity
- Enables forward secrecy on the very first message (via OPK)
- Foundation for proper post-compromise security (ratchet state
  compromise no longer reveals identity key)
- Required for any Phase 6 audit to pass cleanly
- Required for Kickstarter / public adoption credibility

### Negative

- Significant refactor: `LibsodiumX3DH.kt`, `SessionManager.kt`,
  `IdentityManager.kt` (prekey lifecycle), `services/relay/src/routes.rs`,
  `services/relay/src/state.rs`
- All existing Alpha 1 sessions wipe; users must send first message
  to re-establish (auto-handled by client, but visible as "new
  session" in logs and possibly a UI hint)
- New attack surface: prekey exhaustion attack (relay drains a target's
  OPK pool with bogus `RelayMessage::FetchPreKeyBundle` requests).
  Mitigated by per-requesting-identity rate limit (10/min, 100/hour)
  enforced over the authenticated WebSocket connection — VPN/IP
  rotation does not bypass it because rate limits key off the
  authenticated identity, not source IP. Client refill at
  `< 20 remaining`. Pool degrades gracefully to 3-DH variant when
  empty.

### Neutral

- Storage growth on relay: ~32 bytes per OPK × 100 per user × N users
  ≈ 3.2 MB per 1000 users; SPK + signature ≈ 96 bytes per user.
  Negligible at current scale.
- Client state grows: ~32 bytes × 100 OPK private keys plus 1 SPK
  private key per user; encrypted in SQLCipher under existing
  Keystore wrap.

## Alternatives considered

### Alternative A: Keep current architecture, document as "limited threat model"

Rejected. F12 and F15 are not edge cases or implementation polish —
they are protocol-level violations. Documenting them as "known
limitation" is incompatible with PHANTOM's product positioning and
with any external security audit response.

### Alternative B: Adopt `libsignal-client` directly

Considered. Per ADR-006 (revised 2026-04-29) — deferred to Beta+.
Trigger conditions documented there. ADR-009 closes the immediate
audit blockers without library swap; library re-evaluation happens
post-Phase 6.

### Alternative C: MLS (Messaging Layer Security, RFC 9420)

Rejected for 1-on-1 sessions: MLS is designed for groups; pairwise
sessions don't benefit from MLS's tree-based KEM. May reconsider for
group hardening (ADR-010) at scale > 1000 members.

### Alternative D: Regenerate identity on migration

Rejected (per Q1 confirmation 2026-04-29). Reasons:

- Existing peers already have the identity public key cached via
  QR / invite links; regenerating means breaking every existing
  contact relationship
- Username binding (planned ADR-007) ties username to identity key —
  regen invalidates username reservation
- Verification certificates (planned ADR-008) tied to identity —
  regen invalidates verification
- Architectural fix is in **how identity key is used** (only in X3DH
  shared secret derivation), not in the key itself

The security improvement is in separation, not in regeneration.

## Implementation notes

- **Phase 1 Week 4** primary delivery (`v0.1.0-alpha.2` tag candidate)
- Files affected (client):
  - `shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/X3DHProtocol.kt`
    (extend interface to 4-DH with OPK)
  - `shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/LibsodiumX3DH.kt`
    (implement 4-DH variant)
  - `shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/RatchetState.kt`
    (no schema change; existing fields suffice)
  - `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/SessionManager.kt`
    (rewrite `getOrCreateSession` to call `initiatorHandshake` /
    `recipientHandshake`; introduce fresh ephemeral keypair generation)
  - `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/MessagePayload.kt`
    (add optional `x3dhInit` field)
  - `shared/core/identity/src/commonMain/kotlin/phantom/core/identity/IdentityManager.kt`
    (manage prekey lifecycle: generate, upload, refill)
  - new module `shared/core/identity/.../PreKeyManager.kt` for prekey
    storage and refill background task
  - new SQL migration `9.sqm` adding prekey tables + `needs_re_handshake`
    column on `conversation`

- Files affected (relay):
  - `services/relay/src/routes.rs` — four new endpoints
  - `services/relay/src/state.rs` — schema for signed_prekey and
    onetime_prekey tables
  - `services/relay/src/config.rs` — env vars for OPK pool size cap,
    SPK age threshold

- Files affected (UI):
  - new `apps/android/src/androidMain/kotlin/phantom/android/screens/migration/MigrationScreen.kt`
  - migration detection wired into `MainActivity` startup path

- Test plan:
  - 7 test vectors above, each as a `@Test` in `commonTest`
  - Integration test: two clients exchange first message via relay
    with full X3DH; verify relay logs show no plaintext
  - Migration test: pre-Alpha-2 SQLCipher DB fixture in
    `shared/core/storage/src/commonTest/.../fixtures/alpha1_db.bin`,
    invoke migration, verify post-state matches expectation
  - Negative: tampered SPK signature → handshake fails, no key material
    leaks
  - Performance: 100-OPK upload + 100 sequential bundle fetches under
    250ms on Pixel 8 emulator

- Documentation:
  - `docs/CRYPTO_PROTOCOL.md` — written or updated
  - threat model v1.1 — published, references this ADR
  - `KNOWN_ISSUES.md` ISSUE-011 — F12/F15 lines marked CLOSED post-merge

- Tag `v0.1.0-alpha.2` after Phase 1 Week 4 PR merges; this is the
  NLnet submission snapshot per Phase 1 Track E plan

## References

- Signal Protocol whitepaper (Marlinspike & Perrin, 2016)
- X3DH specification: https://signal.org/docs/specifications/x3dh/
- Double Ratchet specification: https://signal.org/docs/specifications/doubleratchet/
- HKDF: RFC 5869
- ADR-001 — System Boundaries
- ADR-006 (revised 2026-04-29) — Crypto Library Decision
- ADR-007 — Username uniqueness via relay namespace (depends on stable
  identity, this ADR preserves it)
- ADR-008 — Verification authority (depends on stable Ed25519 identity)
- ADR-017 — SenderKey signing key removal (orthogonal; lands same Phase 1)
- ADR-018 — Per-user signed challenge auth on relay (Phase 1 P2 batch,
  closes F11)
- KNOWN_ISSUES.md ISSUE-011 — current crypto stack limitations
- Security audit 2026-04-29 — findings F12, F15
