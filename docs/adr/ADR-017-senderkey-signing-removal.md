# ADR-017: SenderKey signing keypair removal

**Status:** Proposed
**Date:** 2026-04-29
**Author:** Vladislav Liudvig
**Reviewers:** (Claude Code post-implementation; ADR-010 author at Phase 2)

## Context

Security audit `2026-04-29` finding **F13** identifies that the
`SenderKey` group-messaging primitive carries a "signing keypair" that
is generated, stored, and distributed but **never used**:

- `shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/SenderKey.kt:21-30`
  — `generate()` creates a `Box.keypair()` and stores both halves in
  the `Bundle` data class as `signingPubHex` / `signingPrivHex`
- `SenderKey.encrypt()` (lines 40-45) — never signs anything; emits
  only SecretBox ciphertext
- `SenderKey.decrypt()` (lines 47-54) — never verifies a signature;
  decrypts directly via `SecretBox.openEasy`
- `DefaultGroupMessagingService.kt:92-93, 110, 135, 141, 212-213,
  223, 247, 272` — distributes `signingPubHex` to other members via
  SKD control messages, persists `signingPrivHex` in SQLite for
  members the user is sending as
- `SenderKeyStore.sq:6-7, 15` and migration `7.sqm:25-26` — schema
  has dedicated columns for both halves
- `MessagePayload.kt:31` — wire format carries `senderKeySignPubHex`

Compounding problems:

1. **F2 (related):** `signingPrivHex` is stored as plain hex in
   SQLite, **without Keystore wrapping** (only `KeystoreIdentityRepository`
   wraps the user's identity DH private key). An attacker with database
   access trivially recovers a "private signing key" that does nothing.

2. **Wrong primitive.** `Box.keypair()` returns an **X25519** keypair,
   not Ed25519. X25519 keys cannot sign without conversion. Even if
   `encrypt()` were extended to sign, the present keypairs would need
   to be replaced — confirming the existing keys provide zero value
   even toward a future signature scheme.

3. **Pure security theatre.** Other group members send their
   `signingPubHex` to peers, expecting peers to verify signatures.
   Peers do not verify. Anyone with relay access who can produce a
   valid SecretBox under a known SenderKey chain can inject messages
   into a group; the wire format already permits this and no current
   defence rejects it.

4. **Liability concentration.** A "signing private key" being plaintext
   in an at-rest database is the worst possible posture: if a key has
   no security value AND is named in a way that suggests it does, it
   misleads code reviewers, security auditors, and contributors.

## Decision

**Remove the SenderKey signing keypair entirely.**

This means: stop generating it, stop distributing it, stop storing it,
remove the related schema columns and wire-format fields. Group
messages remain confidential (encrypted to all members under the
SenderKey chain) but become **explicitly unauthenticated** until
ADR-010 lands a proper signature scheme.

Specifically the following surfaces change:

```
shared/core/crypto/.../SenderKey.kt:
  data class Bundle(
      val chainKeyHex: String,
      val iteration: Int,
-     val signingPubHex: String,
-     val signingPrivHex: String,
  )

  fun generate(): Bundle {
      val chainKey = LibsodiumRandom.buf(32)
-     val signingPair = Box.keypair()
      return Bundle(
          chainKeyHex = chainKey.toHex(),
          iteration = 0,
-         signingPubHex = signingPair.publicKey.toHex(),
-         signingPrivHex = signingPair.secretKey.toHex(),
      )
  }

shared/core/messaging/.../MessagePayload.kt:
- val senderKeySignPubHex: String? = null,

shared/core/storage/.../SenderKeyStore.sq:
  CREATE TABLE sender_key_store(
      group_id           TEXT NOT NULL,
      member_pubkey_hex  TEXT NOT NULL,
      chain_key_hex      TEXT NOT NULL,
      iteration          INTEGER NOT NULL DEFAULT 0,
-     signing_pub_hex    TEXT NOT NULL,
-     signing_priv_hex   TEXT NOT NULL DEFAULT '',
      PRIMARY KEY (group_id, member_pubkey_hex)
  );

shared/core/messaging/.../DefaultGroupMessagingService.kt:
  - All upsert calls drop the two signing-* arguments
  - SKD control message construction drops senderKeySignPubHex
  - SKD control message handler ignores senderKeySignPubHex if
    legacy peers send it (forward compatibility for transition window)
```

### Schema migration

A new SQLDelight migration is added: `9.sqm` (assuming current schema
version is 8, see `KNOWN_ISSUES.md` and `Conversation.sq` for current
state):

```sql
-- 9.sqm — drop unused signing key columns from sender_key_store
-- (ADR-017, removes F13 dead infrastructure and F2 plaintext leak)

-- SQLite < 3.35 cannot DROP COLUMN; recreate the table.
CREATE TABLE sender_key_store_new (
    group_id           TEXT NOT NULL,
    member_pubkey_hex  TEXT NOT NULL,
    chain_key_hex      TEXT NOT NULL,
    iteration          INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, member_pubkey_hex)
);

INSERT INTO sender_key_store_new (group_id, member_pubkey_hex,
                                  chain_key_hex, iteration)
SELECT group_id, member_pubkey_hex, chain_key_hex, iteration
FROM sender_key_store;

DROP TABLE sender_key_store;
ALTER TABLE sender_key_store_new RENAME TO sender_key_store;
```

The migration is destructive of the unused signing columns only; chain
keys and iteration counters are preserved. No user-visible group state
changes. Existing groups continue to function (still confidential, now
explicitly unauthenticated per ADR-010 plan).

### Wire-format compatibility

`MessagePayload.senderKeySignPubHex` is removed in the new build. Two
peer interaction cases:

1. **Both peers on Alpha 2 build** — neither sends nor expects the
   field. Clean.
2. **Mixed Alpha 1 / Alpha 2** — Alpha 1 peer sends SKD with
   `senderKeySignPubHex`, Alpha 2 peer receives it. Decoder uses
   `Json { ignoreUnknownKeys = true }` (already configured in
   `DefaultMessagingService`), so the field is silently dropped. No
   compatibility break.

After the Alpha 1 → Alpha 2 migration (per ADR-009), all sessions
re-bootstrap, so legacy mixed cases close within roughly one update
cycle.

### Threat model implication (must be documented)

Group messages in Alpha 2 are:

- **Confidential**: encrypted under per-member SenderKey chain to all
  current members. A non-member cannot read group messages.
- **NOT authenticated**: a malicious relay, or any current member,
  can forge a message that other members will accept as legitimate
  group content. There is no per-message signature.

This is a documented limitation, not a hidden flaw. The mitigation is
ADR-010 (Phase 2 W15-16, August 2026) which introduces a proper
group-message signature scheme (Ed25519 signature over each ciphertext
under a per-group signing key derived deterministically and distributed
via the pairwise Double Ratchet channels).

For Alpha 2 timeline, the trade-off is:

- **Risk:** member impersonation within a known group is possible if
  any member device is compromised, or if relay turns malicious.
- **Mitigation:** group membership requires explicit invitation
  through the existing trust chain; member additions/removals are
  manually audited by users; verification (Phase 2 ADR-008) gives
  high-trust users a signed identity badge that complements (does not
  replace) group authentication.
- **Acceptance scope:** this is acceptable for Alpha 2 closed-beta
  groups (small, trusted membership) but **not** for any future Public
  group, Channel, or large-membership feature. ADR-010 is a hard
  prerequisite for those.

`KNOWN_ISSUES.md` ISSUE-011 already documents F13 as Phase 1 closure
"remove unused signing keypair, document group authentication
limitation" — this ADR is the documentation that satisfies the
"document" half. After the Alpha 2 implementation merges, ISSUE-011
table updates to mark F13 CLOSED via removal.

## Consequences

### Positive

- F13 closed: removes a piece of code that does nothing useful and
  misleads readers
- F2 partially closed: removes the plaintext "signing private key"
  from SQLite (the legitimate threat surface is reduced; ratchet
  state separate concern remains in F8 / ADR-009)
- Smaller attack surface: less code to audit, less wire format to
  fuzz
- Schema simplification: two fewer columns in `sender_key_store`,
  cleaner migration history
- Decision discipline: removes an "almost-implemented" feature that
  was creating ambient false-confidence; replaces it with explicit
  trade-off documentation

### Negative

- Group messages explicitly unauthenticated until ADR-010 ships
  (Phase 2 W15-16). User-visible: no UI surfaces this caveat in
  Alpha 2 because the trust model is "small private groups of
  already-trusted contacts"; surfacing the caveat as a banner
  would alarm without proportional educational benefit. Documented
  in `KNOWN_ISSUES.md` and `docs/adr/`.
- Slight wire-format churn during the Alpha 1 → Alpha 2 transition
  window (legacy peers send fields that get ignored). Bounded.

### Neutral

- ADR-010 still requires its own design (signing key generation,
  distribution, verification, rotation, performance considerations
  for groups up to 1000). This ADR does not dictate ADR-010's choices;
  it only removes the current placeholder so ADR-010 lands on a clean
  baseline.

## Alternatives considered

### Alternative A: Implement HMAC-SHA-256 over ciphertext using existing chain key

Considered seriously. Implementation: every `SenderKey.encrypt()`
output gains an HMAC tag computed under a derived key from the chain
key (or a separate MAC key derived alongside the message key in the
KDF chain). Receiver re-derives, recomputes HMAC, rejects on mismatch.

Rejected for Alpha 2 because:

- The chain key is shared by all members. Any member can produce a
  valid HMAC for any other member's chain. So HMAC under a shared
  key proves "some current member sent this", not "this specific
  member sent this". That is exactly the property an Ed25519 per-sender
  signature would provide and exactly the property a shared-secret
  HMAC cannot.
- A correctly-scoped MAC requires per-member signing keypairs (which
  is what ADR-010 designs). Doing HMAC-with-chain-key now would make
  the wire format harder to retire when ADR-010 lands.

### Alternative B: Switch to Ed25519 for the existing signing keypair and wire signatures

Considered. This is the "fix in place" path. Rejected for Alpha 2
because:

- Requires generating a different primitive (Ed25519 vs current X25519
  Box keypair) — schema migration mid-flight.
- Requires deciding signature scope (per-message? per-control-message?
  per-batch?), key rotation, signature failure handling, replay
  protection — all of which is ADR-010 territory.
- Doing it now creates the implicit expectation that the design is
  finished, which it is not. Removing the placeholder forces ADR-010
  to be a real design decision rather than a "fill in the blanks"
  addition.

### Alternative C: Keep the current code as-is, document as known limitation

Rejected. Two reasons. First, the plaintext private key in SQLite
(F2) is a real security regression even though the key has no
function — it telegraphs to an attacker that this codebase has
not been carefully audited. Second, "documented unused dead code"
is not a sustainable architectural posture; either it is used and
necessary, or it is removed.

## Implementation notes

- **Phase 1 Week 4** (alongside ADR-009 implementation; small enough
  to fit in the same sprint).
- Files affected:
  - `shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/SenderKey.kt`
  - `shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/SenderKey*Test.kt`
    (test fixtures lose the two arguments; no test logic depends on signing)
  - `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/SenderKeyStore.sq`
  - new `shared/core/storage/src/commonMain/sqldelight/phantom/core/storage/9.sqm`
  - `shared/core/storage/src/commonMain/kotlin/phantom/core/storage/SenderKeyEntity.kt`
  - `shared/core/storage/src/commonMain/kotlin/phantom/core/storage/SqlDelightSenderKeyRepository.kt`
  - `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/MessagePayload.kt`
    (drop `senderKeySignPubHex`)
  - `shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultGroupMessagingService.kt`
    (drop signing-related arguments throughout)

- Test plan:
  - Unit: existing SenderKey round-trip tests pass without signing
    fields
  - Migration: a fixture DB at schema v8 (with signing_*_hex
    columns) successfully migrates to v9, all chain keys and
    iterations preserved, no row loss
  - Integration: existing group-messaging integration test (if any)
    continues to pass; the group of 3 members sends and receives
    messages without any signing field on the wire
  - Negative: `Json.decodeFromString<MessagePayload>` of a payload
    containing a stale `senderKeySignPubHex` field succeeds and
    silently ignores it (already covered by `ignoreUnknownKeys = true`)

- Documentation:
  - `KNOWN_ISSUES.md` ISSUE-011 — F13 row updated to "Closed by
    ADR-017 (Phase 1 W4)"; F2 row updated similarly with reference
    to this ADR
  - This ADR ships in the same PR as the code change; the PR
    description references both

- No relay changes. SenderKey is purely a client-side primitive.

## References

- ADR-009 — identity / prekey separation (lands same Phase 1 W4)
- ADR-010 — group encryption hardening (Phase 2 W15-16, will define
  the proper signature scheme that supersedes the gap left by this
  ADR)
- ADR-001 — system boundaries (relay does not authenticate group
  messages; this is consistent with that)
- `KNOWN_ISSUES.md` ISSUE-011 — current crypto stack limitations
  catalogue
- Security audit 2026-04-29 — findings F13 (signing infrastructure
  inoperative) and F2 (plaintext signing private key in SQLite)
- Signal blog: Private Groups (https://signal.org/blog/private-groups/)
  — reference design that ADR-010 will adapt
