# PHANTOM — Architectural Decisions TODO

**Дата:** 28 апреля 2026
**Период написания:** Phase 1, Week 1-3 (May 2026)
**Цель:** зафиксировать все архитектурные решения **до** начала реализации, чтобы implementation в Phases 2-6 был coherent а не reactive.

---

## Что такое ADR и зачем

**ADR (Architectural Decision Record)** — короткий документ (1-3 страницы) который фиксирует **одно** важное архитектурное решение. Каждый ADR следует одному шаблону:

1. **Status** — Proposed / Accepted / Superseded / Deprecated
2. **Context** — какая проблема, какие constraints, что уже есть
3. **Decision** — что мы решили делать
4. **Consequences** — что меняется, какие trade-offs принимаем
5. **Alternatives considered** — что рассмотрели и почему отклонили

**Зачем писать ADR до кода:**

- Через 6 месяцев ты (или Claude Code, или новый contributor) забудешь почему сделано именно так. ADR это память.
- Внешние reviewers (audit, security researchers, новые contributor'ы) видят зрелость процесса.
- Architectural drift — главная причина legacy code. ADR заранее исключает "случайные" решения.
- Если решение оказывается неправильным — superseded ADR показывает что мы знали trade-offs и сознательно поменяли.

**Существующие ADR в репозитории:**

Проверь `docs/adr/` — там должны быть ADR-001 до ADR-006 (упоминалось в memory). Новые ADR продолжают нумерацию с 007.

---

## ШАБЛОН ADR (для копирования)

Каждый новый ADR следует этой структуре. Файл: `docs/adr/ADR-NNN-title-slug.md`

```markdown
# ADR-NNN: <Title>

**Status:** Proposed | Accepted | Superseded by ADR-XXX | Deprecated
**Date:** YYYY-MM-DD
**Author:** Vladislav Liudvig
**Reviewers:** (none for now; can add Claude Code as reviewer post-implementation)

## Context

What is the problem we are solving?
What constraints exist (technical, regulatory, business)?
What does the current state look like?
Why is this decision needed now?

## Decision

What is the chosen approach?
Be specific and concrete: file paths, data structures, protocol details.
Include code snippets, schemas, or pseudocode where useful.

## Consequences

### Positive
- What gets better

### Negative
- What we accept as cost
- What gets harder

### Neutral
- What changes but isn't strictly better/worse

## Alternatives considered

### Alternative A: <name>
Brief description. Why we did NOT choose this.

### Alternative B: <name>
Brief description. Why we did NOT choose this.

## Implementation notes

- Phase / sprint when this gets implemented
- Files / modules affected
- Testing approach
- Migration path if existing system

## References

- Related ADRs
- External documentation
- Academic papers (if applicable)
- Existing implementations that informed this decision
```

---

## SCHEDULE — когда какой ADR пишется

10 ADRs нужны в roadmap. Распределение:

| ADR # | Title | Write by | Implement in |
|-------|-------|----------|--------------|
| 007 | Username uniqueness via relay namespace | Phase 1 W1 | Phase 2 |
| 008 | Verification authority (Willen LLC central) | Phase 1 W1 | Phase 2 |
| 009 | Identity / signed prekey / one-time prekey separation (F13/F14/F15) | Phase 1 W2 | Phase 1 W3-6 |
| 010 | Group encryption hardening (Sender Keys signing) | Phase 1 W3 | Phase 2 W15-16 |
| 011 | Premium feature gating architecture | Phase 1 W4 | Phase 3 |
| 012 | Account migration via seed phrase | Phase 2 W9 | Phase 3 W21-22 |
| 013 | Attachment server (MinIO + E2E) | Phase 1 W5 | Phase 1 W7-8 |
| 014 | iOS architecture (SwiftUI + KMP shared) | Phase 3 W23 | Phase 4 |
| 015 | Pluggable transports | Phase 4 W31 | Phase 5 W33-36 |
| 016 | Push notifications hybrid | Phase 4 W32 | Phase 5 W37-38 |

**Phase 1 alone:** 6 ADRs (007, 008, 009, 010, 011, 013) — это серьёзная documentation work на ~7-10 дней суммарно. Распределяется параллельно с code work, не блокирует.

---

# ADR DRAFTS

Ниже — **draft content** для всех 10 ADRs. Не финальные версии — это starting points которые пользователь и Claude Code могут расширять при написании финальных файлов.

---

## ADR-007 DRAFT: Username uniqueness via relay namespace

**Status:** Proposed
**Date:** 2026-05-XX
**Author:** Vladislav Liudvig

### Context

PHANTOM Alpha 1 currently has no enforcement of @username uniqueness. Two users can register the same @vasya — relay distinguishes them only by public key. This creates a critical UX problem: users cannot reliably refer to each other by username, contact discovery is impossible, and Premium tier features promising "custom @username" have no foundation.

We have committed to single-relay architecture (no federation). This means we control the namespace.

### Decision

Implement username uniqueness via **relay-managed namespace** at `directory.phntm.pro`. New Rust microservice `directory` running alongside existing `relay` and `caddy`.

**Schema (PostgreSQL or sled embedded DB):**

```
usernames:
  username TEXT PRIMARY KEY (lowercase, 3-32 chars, [a-z0-9_])
  public_key BLOB UNIQUE
  reserved_at TIMESTAMP
  reservation_token BLOB (challenge-response proof of pubkey ownership)
```

**Endpoints:**

- `POST /reserve` — body: `{username, public_key, signature_over_username}`
  - Server verifies signature with provided public_key
  - Server checks reserved_words list (admin, support, phantom, etc.)
  - INSERT with conflict handling (atomic, race-safe)
  - Returns 201 Created or 409 Conflict
  
- `GET /lookup/:username` — returns `{public_key}` or 404
  
- `PUT /update` — Pro feature, change username. Requires signature from current public_key.
  
- `DELETE /username` — release on account deletion

**Client integration:**

Onboarding (`OnboardingScreen.kt:444`) gains a username availability check **before** local identity creation. Failure mode: user types name, client checks, shows green check or red error. Server-side reservation happens at "Create account" tap — atomic.

**Migration:** existing Alpha 1 users (currently <100) get one-time forced re-registration via Settings → "Claim your username" flow. After Phase 2 launch, no anonymous users exist.

### Consequences

**Positive:**
- @username is meaningful identifier
- Discovery by username possible (search endpoint, Pro feature)
- Premium "custom short @username" has reservation foundation
- Trust signal: "verified ✓" can attach to specific @username

**Negative:**
- Centralization point — directory failure = no new registrations (existing chats unaffected)
- Single relay namespace means we (Willen LLC) become arbiters of namespace disputes
- Cannot federate later without breaking existing usernames

**Neutral:**
- Adds operational complexity (one more service, one more DB)
- Reserved words list needs maintenance

### Alternatives considered

**Alternative A: Email-style federated namespace (`@vasya@example.com`)**
Rejected: federation explicitly out of scope per ADR-005. Would tie us to operating other relays' policies.

**Alternative B: Public-key-only identity, username as display-only**
Considered. Rejected because UX requires stable identifiers users can speak/type. "Add @vasya" beats "Add 9C3F4A2B81E7..." for mass-market adoption.

**Alternative C: First-come-first-served with PoW captcha**
Rejected for v1: anti-bot via PoW adds latency, complicates UX. Rate limiting + reserved-words sufficient initially.

### Implementation notes

- Phase 2 W9-12 (July 2026)
- New service: `services/directory/` (Rust, similar structure to relay)
- Client changes: `OnboardingScreen.kt`, `AddContactDialog.kt`, new `UsernameLookup` use-case
- Tests: race-condition tests, reserved-word tests, signature verification tests
- Documentation: `docs/USERNAME_NAMESPACE.md` for users

### References

- ADR-005 (federation out of scope)
- Inventory report 2026-04-28, section B2

---

## ADR-008 DRAFT: Verification authority (Willen LLC central)

**Status:** Proposed
**Date:** 2026-05-XX

### Context

Public figures, journalists, organizations need a way to prove "this @username is really me, not an impersonator". Without this, anyone can register `@washingtonpost` and harvest tips from confused sources. Decision was made (28 April 2026 strategic discussion): only Willen LLC verifies, centralized. No web-of-trust, no DAO, no third-party verifiers initially.

### Decision

**Verification authority architecture:**

1. Willen LLC generates **verification authority signing key pair** offline, on a hardware token (YubiKey or similar). Public key hardcoded into client.

2. **Verification certificate format:**

```
struct VerificationCertificate {
  public_key: [u8; 32],          // user's identity public key
  username: String,              // verified @username
  display_name: String,          // human-readable name shown in UI
  category: enum {
    Person,        // individual public figure
    Journalist,    // verified journalist with affiliation
    Organization,  // company / NGO / institution
    Government,    // government official
  },
  affiliation: Option<String>,   // e.g. "The Washington Post"
  issued_at: u64,                // unix timestamp
  expires_at: u64,               // typically 1 year
  signature: [u8; 64],           // Ed25519 over CBOR(everything above)
}
```

3. **Distribution:** certificates delivered alongside relay metadata when contact established. Client caches signed certs locally. Periodic refresh from `directory.phntm.pro/verifications/:public_key`.

4. **Verification process (manual for v1):**
   - User submits application via web form (passport scan + proof of public profile)
   - Willen LLC reviews (KYC-lite + verification of public claims)
   - If approved, sign certificate offline, publish to directory
   - User pays one-time verification fee (e.g. $99) to cover review cost — included in Pro tier or sold separately

5. **Revocation:** revoked certificates listed at `directory.phntm.pro/revoked` (CRL-style). Client checks daily.

6. **Display in UI:** verified ✓ badge in ChatList, ContactProfile, message header. Tap to see verification details.

### Consequences

**Positive:**
- Public figures can prove identity (huge UX win)
- Trust signal in mass-market adoption
- Revenue stream (verification fees as Pro feature)
- Defense against impersonation attacks

**Negative:**
- Willen LLC = single point of compromise (catastrophic if signing key stolen)
- Centralization criticism from ideological FOSS purists
- Manual review = does not scale (need backup automated process post-1000 verifications)
- Legal liability (false verification could be defamation)

**Neutral:**
- Need legal review of verification ToS
- Need to think about what happens if Willen LLC dissolves (transfer of authority?)

### Alternatives considered

**Alternative A: PGP web-of-trust style**
Rejected: complexity for users, doesn't scale, has historical UX failures.

**Alternative B: Multiple verifiers (Willen LLC + trusted partners)**
Considered for future (Phase 6+). Requires more thought on multi-signature scheme. v1 starts with single verifier.

**Alternative C: No verification, identity = public key only**
Rejected: doesn't solve impersonation problem. Mass-market users won't compare 32-byte hashes.

### Implementation notes

- ADR drafted: Phase 1 W1
- Implementation: Phase 2 W13-14
- Hardware token: order in Phase 1 (Yubikey 5C ~$60)
- Admin web app: simple, internal use only — Phase 2
- First verifications: ourselves (Willen LLC verified @phantom), maybe known privacy advocates as PR

### References

- Twitter Blue (anti-pattern: paid-no-review verification)
- Mastodon verification via rel="me" (alternative model)

---

## ADR-009 DRAFT: Identity / signed prekey / one-time prekey separation (F13/F14/F15 fix)

**Status:** Proposed (CRITICAL — security blocker)
**Date:** 2026-05-XX

### Context

Inventory report 2026-04-28 identifies F13/F14/F15 as the most critical architectural issue: PHANTOM Alpha 1 reuses the X25519 identity key as the Double Ratchet DH key. If ratchet state is compromised (malware, device seizure, side channel), an adversary gains permanent impersonation capability for that user.

This violates Signal Protocol's core security property of separating long-term identity from short-term ratchet state. The fix is mandatory before public adoption / Kickstarter / paid users.

### Decision

Implement Signal-style three-key system:

```
Identity Key Pair (long-term, X25519 + Ed25519):
  - Ed25519 for signatures (verifying signed prekeys, certificates)
  - X25519 for X3DH initial DH
  - Stored securely (Android Keystore-protected SQLCipher)
  - NEVER used as ratchet DH key directly

Signed PreKey (medium-term, ~1 week rotation):
  - X25519 keypair
  - Signed by identity Ed25519 key
  - Published to relay /prekeys/signed/:identity_pubkey
  - Rotated by client weekly; relay deletes old after 2 weeks

One-Time PreKeys (single-use, batches of 100):
  - X25519 keypairs
  - Generated client-side, public keys uploaded to relay
  - Each one used at most once for X3DH initial handshake
  - Client refills when remaining < 20

Ephemeral Ratchet Keys (per-message):
  - Generated fresh on each Double Ratchet step
  - Never reused
  - Independent from identity / prekey hierarchy
```

**Relay endpoints (Rust additions to existing service):**

- `PUT /prekeys/signed` — body: signed_prekey + signature_by_identity
- `PUT /prekeys/onetime` — body: array of one-time prekey public keys
- `GET /prekeys/bundle/:identity_pubkey` — returns `{signed_prekey, signed_prekey_signature, one_time_prekey?}`. Atomic: if one-time available, returned and deleted from server.
- Relay tracks count of remaining one-time prekeys per user; signals client to refill when low.

**X3DH handshake:**

```
Initiator wants to message Recipient (recipient pubkey known via QR/directory):
1. GET /prekeys/bundle/:recipient_pubkey → returns SPK + (optional) OPK
2. Verify SPK signature with Recipient's identity Ed25519
3. Compute four DHs:
   DH1 = DH(my_identity_X25519, recipient_SPK)
   DH2 = DH(my_ephemeral, recipient_identity_X25519)
   DH3 = DH(my_ephemeral, recipient_SPK)
   DH4 = DH(my_ephemeral, recipient_OPK) [if OPK provided]
4. Master Secret = HKDF(DH1 || DH2 || DH3 || DH4, info="phantom-x3dh-v2")
5. Initialize Double Ratchet with master secret + initial ratchet keys
```

**Migration path for existing Alpha 1 users:**

- Phase 1 W3-6 work
- New version (0.1.0-alpha.2 or 0.2.0-beta.0) ships with new architecture
- On launch, client checks: do I have signed prekey + one-time prekeys uploaded?
  - If no → forced regeneration of identity (new keypair) + upload prekeys
  - Existing conversations marked as "needs re-handshake"
  - Next time peer comes online, automatic re-X3DH with new architecture
- User-facing message: "Phantom upgraded its security. Re-verify your contacts." (one-time onboarding addendum)

### Consequences

**Positive:**
- Security model matches Signal (industry standard)
- Compromise of ratchet state ≠ permanent impersonation
- Foundation for proper post-compromise security
- Required for any audit to pass cleanly
- Required for Kickstarter / public adoption credibility

**Negative:**
- Significant refactor across `LibsodiumX3DH.kt`, `SessionManager.kt`, `IdentityManager.kt`, relay routes
- All existing users force-migrate (some may lose conversations if peer never reappears)
- New attack surface: prekey exhaustion attack (relay drains one-time prekeys with bogus requests)
  - Mitigation: rate limit prekey-bundle GET per requester; require proof-of-work for high-volume requests

**Neutral:**
- Storage growth on relay (prekey_bundles table)
- More complex client state (3 separate key types to manage)

### Alternatives considered

**Alternative A: Keep current architecture, document as "limited threat model"**
Rejected: this is a blocker for any audit, any serious user adoption, any commercial offering. Cannot ship Premium with this.

**Alternative B: Adopt Signal Protocol library directly (libsignal)**
Considered. Rejected for several reasons: libsignal is GPLv3 (compatible with AGPL but more restrictive), C++ binding complexity for KMP, adds large dependency (~5MB), and forks our protocol design from being independently auditable. Implementing the same primitives ourselves (with libsodium underneath) keeps the codebase auditable and protocol-explainable.

**Alternative C: MLS (Messaging Layer Security)**
Rejected for 1-on-1: MLS designed for groups, overkill for pairwise. May reconsider for groups in ADR-010.

### Implementation notes

- **CRITICAL — this is the architectural blocker.**
- Phase 1 W3-6 (4 weeks of intensive work)
- Files affected: `shared/core/crypto/`, `shared/core/messaging/`, `shared/core/identity/`, `services/relay/src/routes.rs`, `services/relay/src/state.rs`
- Test plan: 20+ new test vectors covering each DH branch, prekey exhaustion, signature verification, migration scenarios
- Documentation: `docs/CRYPTO_PROTOCOL.md` updated, threat model v1.1 published

### References

- Signal Protocol whitepaper (Marlinspike & Perrin, 2016)
- libsignal source (for cross-validation of test vectors)
- ADR-001 through ADR-006 in `docs/adr/`
- Inventory report 2026-04-28, section A1

---

## ADR-010 DRAFT: Group encryption hardening

**Status:** Proposed
**Date:** 2026-05-XX

### Context

Inventory shows groups at ~70% completion using Sender Keys protocol. Two critical gaps:

1. `signingPrivHex=""` for remote keys — signature verification on incoming group messages is best-effort, not enforced
2. Control messages (member-add, member-leave, name-change) are NOT wrapped in Double Ratchet — sent in plain via relay metadata

Both must be fixed before groups can be production-grade.

### Decision

**Fix 1: Sender Key signing distribution**

When user creates/joins group, generate Ed25519 signing keypair for the group. Distribute private key to all current members via the **existing pairwise Double Ratchet channels** (encrypted to each member individually). All members receive: `{group_id, signing_priv_key, member_list}`.

When member sends group message:
1. Encrypt payload with current chain key
2. Sign ciphertext with signing priv key
3. Broadcast to all members (via per-member sealed envelopes, current architecture)

When member receives:
1. Verify signature with group signing pub key
2. If signature fails: REJECT message (currently it accepts as best-effort, this changes)
3. Decrypt with chain key

**Fix 2: Control messages over Double Ratchet**

All control messages (`group_create`, `member_add`, `member_remove`, `name_change`, `key_rotate`) sent as **regular E2E messages** through the pairwise Double Ratchet to each affected member. Not as relay-level metadata.

Format: control messages as a special MessageType in existing Double Ratchet payload. Relay sees only sealed envelope.

**Fix 3: Member-leave key rotation**

Currently `handleLeave` deletes ALL group SenderKeys — incorrect. Should:
1. Remaining members receive `member_remove` control message
2. Each remaining member deletes the leaving member's chain key (forward security: leaving member cannot decrypt future messages)
3. Group signing keypair remains the same (no need to rotate signing — leaving member still cannot impersonate without sender key chain)
4. Optional: rotate group signing key on every leave (more secure, more expensive). Default: rotate on every leave for groups <50, rotate on schedule for larger.

### Consequences

**Positive:**
- Group encryption matches stated security properties (signed messages enforced)
- Forward secrecy on member leave
- Security for groups up to ~1000 (good for Plus tier "groups up to 1000")

**Negative:**
- Larger group control messages (each control msg = N pairwise DR messages)
- Performance: group of 1000 → control message takes ~1000 envelopes
  - Mitigation: relay can batch fanout, server-side optimization

**Neutral:**
- Current group implementation gets refactored, not rebuilt
- Test coverage extended

### Alternatives considered

**Alternative A: MLS (Messaging Layer Security, RFC 9420)**
Considered seriously. Rejected for v1 because: requires central key delivery service incompatible with our threat model; complex implementation (no good Kotlin/Rust library); designed for >1000-member groups (overkill for our scale); migration burden. May reconsider for very large groups (>5000) in distant future.

**Alternative B: Pairwise messaging (send N copies, no group key)**
Considered. Rejected: doesn't scale beyond ~50 members; each message duplicated N times in transit and storage. Sender Keys are the right pattern for our scale.

### Implementation notes

- Phase 2 W15-16 (August 2026)
- Files: `shared/core/messaging/.../DefaultGroupMessagingService.kt`, `SenderKeyStore.sq`, message format definitions
- Test plan: groups of 10, 50, 100 members; member-add/remove flows; signature failure detection
- Migration: existing Alpha 1 groups force re-create on next message exchange

### References

- Signal Sender Keys (https://signal.org/blog/private-groups/)
- WhatsApp Group encryption whitepaper
- ADR-009 (depends on identity/prekey infrastructure)
- Inventory report 2026-04-28, section D2

---

## ADR-011 DRAFT: Premium feature gating architecture

**Status:** Proposed
**Date:** 2026-05-XX

### Context

PHANTOM Premium tiers (Plus, Pro, Lifetime, Business) need server-validated feature gating. Cannot trust client-side flags alone — patched APK could enable any feature. But not every feature needs server roundtrip on every use (e.g., custom theme color is fine to be client-side).

### Decision

**Three-tier feature gating:**

**Tier A — Server-validated, cannot bypass:**
- Cloud-stored encrypted backups (server refuses to store if no Plus subscription)
- Self-hosted relay tokens (Pro feature, server issues tokens)
- Verified ✓ badge (signed by Willen LLC, hardcoded pubkey verifies — cannot fake)
- API access tokens (Pro)
- Group size > 100 (server enforces participant cap)
- Custom @username (Pro reservation in directory)

**Tier B — Server-confirmed, soft-bypass possible but cosmetic:**
- Premium ◆ badge in ChatList (client checks subscription on app launch)
- Berkeley Mono font activation
- Custom themes
- Animated avatars
- App icon variants
- Unlimited message history (technically client-side, but server-confirmed flag)

**Tier C — Pure client-side, no validation needed:**
- UI preferences
- Notification sounds choice (file selection)
- Language

**Subscription state architecture:**

```
Client subscription state cached locally:
  - tier: enum { Free, Plus, Pro, Lifetime, Business }
  - expires_at: timestamp (None for Lifetime)
  - last_validated: timestamp
  - server_signature: bytes (signed by Willen LLC validation key)

Refresh policy:
  - On app launch (if last_validated > 24h)
  - On critical action (Tier A feature use)
  - On startup of foreground service (rare)

Server endpoint:
  GET https://billing.phntm.pro/subscription/:public_key
  Returns: signed JSON {tier, expires_at, signature}
  
  Signature uses Willen LLC validation key (separate from verification authority key).
  Client verifies signature, caches with timestamp.

Stripe webhook flow:
  Stripe → billing.phntm.pro/webhook → updates subscription DB
  No client involvement; server is source of truth
```

### Consequences

**Positive:**
- Tier A features genuinely cannot be bypassed (subscription required for cloud backup)
- Tier B mostly works even without network (cached state)
- Server load minimal (most features Tier B/C, no per-action call)
- Architecture supports future tier additions

**Negative:**
- Client cache can be stale (24h max — acceptable trade-off)
- Tier B can be "patched" by determined user (acceptable — they're not paying anyway, no revenue lost)
- New service `billing.phntm.pro` to operate

**Neutral:**
- Stripe is single payment provider initially (lock-in risk acceptable for v1)

### Alternatives considered

**Alternative A: Pure server-side gating (every action validates)**
Rejected: too much server load, breaks offline-first design.

**Alternative B: Pure client-side flags (no server validation)**
Rejected: trivially bypassable, breaks revenue model for Tier A features.

**Alternative C: Receipt-based gating (Stripe receipt embedded in client)**
Considered. Less clean than signed-state approach; receipt expires, JWT-style state is more flexible.

### Implementation notes

- ADR drafted: Phase 1 W4
- Implementation: Phase 3 (Sept-Oct 2026)
- New service: `services/billing/` (Rust)
- Stripe Tax + Stripe Billing Portal configured
- Test plan: subscription lifecycle (subscribe, expire, cancel, refund, dispute)
- Documentation: `docs/PREMIUM_TIERS.md`

### References

- Telegram Premium subscription model
- Apple StoreKit 2 (for IAP later)
- Inventory report, section D1

---

## ADR-012 DRAFT: Account migration via seed phrase

**Status:** Proposed
**Date:** 2026-06-XX

### Context

Inventory + ToS pole 4: currently "lose device = lose account". This is a major UX blocker for retention. User pays for Plus, breaks phone, new phone = re-register from scratch. History lost. Conversations require re-handshake. Contacts must re-add.

For mass-market adoption (and certainly for Premium tier customers), account recovery is essential.

### Decision

**BIP-39 seed-phrase-based account recovery (Plus feature initially, Free in future):**

```
Generation flow (Settings → Backup):
1. Display warning: "Recovery phrase grants full access. Anyone with it can impersonate you."
2. Generate 24-word BIP-39 mnemonic
3. Force write-down (cannot screenshot, FLAG_SECURE on this screen)
4. Verification: ask user to enter 3 random words from the 24
5. Mark account as "recovery-phrase-set"

Derivation:
  master_seed = BIP-39_to_seed(mnemonic, passphrase="")
  identity_priv = HKDF(master_seed, info="phantom-identity-v1")
  signing_priv = HKDF(master_seed, info="phantom-signing-v1")

Backup blob format (uploaded to user's chosen cloud):
  {
    version: 1,
    encrypted_data: AES-256-GCM(plaintext, key_from_seed),
    plaintext = JSON {
      identity_priv_key,
      signing_priv_key,
      conversations: [{peer_pubkey, peer_username, messages: [...] }],
      groups: [...],
      settings: {...},
      last_updated: timestamp
    }
  }

User uploads backup blob to:
  - Their Google Drive / iCloud / Dropbox (Plus auto-uploads weekly)
  - Their self-hosted server (Pro feature)
  - Manual download as .phantom-backup file

Restore flow (new device):
1. Onboarding → "I have a recovery phrase"
2. User enters 24 words
3. Derive identity, signing keys
4. Identity public key = derive from seed → query relay for our profile
5. Optionally: download backup blob from chosen cloud, decrypt
6. Restore conversations, contacts, groups
7. Force re-handshake with all peers (security: peers don't know we have new device)
```

### Consequences

**Positive:**
- Account recovery possible (huge UX improvement)
- Foundation for multi-device (Phase 7+) — same seed = same identity
- Critical for Premium tier credibility
- Conforms to crypto-aware user expectations

**Negative:**
- Seed phrase = single point of failure if user mishandles
  - Mitigation: extensive UX work on warnings, verification flow
- Cloud backup blob is encrypted but its existence is visible to cloud provider
  - Mitigation: docs explain trade-off; pure local backup option also exists (file export)
- Re-handshake on restore = peers see "X verified themselves with new device" notice
  - Mitigation: this is correct security behavior, not a bug

**Neutral:**
- Standard pattern in crypto/identity products (1Password, Bitwarden, Bitcoin wallets)
- Some users will lose seeds and accounts; we cannot help — this is part of the social contract of self-sovereignty

### Alternatives considered

**Alternative A: Server-side encrypted backup (server has data, encrypted with user's password)**
Rejected: violates "we cannot read your data" promise. Even encrypted, we'd be storing.

**Alternative B: Social recovery (M-of-N friends)**
Considered for future. Complex UX; not needed for v1.

**Alternative C: No recovery (current state)**
Rejected: blocks Premium tier and retention.

### Implementation notes

- ADR drafted: Phase 2 W9
- Implementation: Phase 3 W21-22 (October 2026)
- Files: new `shared/core/backup/` module, `BIP39Mnemonic.kt`, `BackupBlob.kt`
- BIP-39 wordlist: standard English (~2048 words) + future addition Russian/etc.
- Test plan: round-trip seed → keys → seed; backup encryption/decryption; restore from corrupted backup
- Documentation: `docs/RECOVERY_PHRASE.md`

### References

- BIP-39 spec
- 1Password Master Password approach
- Bitcoin wallet seed phrase UX patterns
- Signal's PIN-based account recovery (alternative model we did NOT choose)

---

## ADR-013 DRAFT: Attachment server architecture

**Status:** Proposed
**Date:** 2026-05-XX

### Context

Inventory + recent voice message debugging shows: voice messages >50KB fail to deliver on certain Android OEMs (Tecno HiOS specifically) due to OEM radio parking + asymmetric outbound packet loss on large WebSocket frames. Current architecture inlines voice as base64 in WebSocket payload — not viable for files, images, or even reliable voice.

Need separate transport for attachments.

### Decision

**MinIO-based attachment server with E2E encryption:**

```
New service: attachment.phntm.pro (or path on existing relay infra)
Backend: MinIO (S3-compatible object storage)

Upload flow (sender):
1. Generate random AES-256-GCM key for this attachment
2. Encrypt file locally with that key
3. Compute hash of ciphertext (content-addressable storage)
4. POST encrypted file to attachment.phntm.pro/upload
   - Returns: {attachment_id, expires_at}
5. Send via WebSocket to recipient: regular Double Ratchet message containing
   {type: "attachment", attachment_id, decryption_key, original_filename, mime_type, size}
6. Recipient receives message with metadata + key

Download flow (recipient):
1. Receive metadata via Double Ratchet
2. GET attachment.phntm.pro/download/:attachment_id (no auth needed — content-addressable)
3. Decrypt with provided key
4. Display in UI

Server:
  - Stores only encrypted blobs, no key
  - TTL: 30 days (configurable per tier)
  - Storage limits: Free 1GB total, Plus 50GB, Pro 500GB
  - Rate limits: prevent abuse
  - Access control: anyone can download by ID, but ID is random 256-bit (effectively unguessable)

Encryption:
  AES-256-GCM with random 96-bit nonce per file
  Key length 256 bits, generated client-side, sent through Double Ratchet
  Server NEVER sees plaintext key
```

### Consequences

**Positive:**
- Solves voice >50KB delivery problem (separate transport, smaller WebSocket frames)
- Foundation for files, images, documents (Phase 5+)
- Premium tier value: "50GB cloud storage" is meaningful
- Server stores only encrypted blobs (privacy preserved)

**Negative:**
- New service to operate (MinIO + Caddy reverse proxy + storage costs)
- Storage costs scale with users (~$5-20/TB/month at Hetzner)
- Server can correlate "user X uploaded N attachments" (metadata leak — accept this trade-off, it's better than no attachments at all)

**Neutral:**
- Adds complexity to message format (attachment metadata)
- Two-step send (upload then notify) — needs UI feedback ("Uploading..." indicator)

### Alternatives considered

**Alternative A: WebRTC DataChannel P2P**
Rejected: requires both peers online simultaneously; doesn't work for offline send.

**Alternative B: Chunked WebSocket frames**
Considered. Helps with TCP MTU issues but doesn't solve OEM radio parking on Tecno-class devices. MinIO solves more problems.

**Alternative C: Third-party storage (Backblaze B2, Wasabi)**
Considered. Cost-effective but adds dependency on US-based or non-EU providers. Hetzner-hosted MinIO keeps everything in EU jurisdiction (consistent with privacy positioning).

### Implementation notes

- ADR drafted: Phase 1 W5
- Implementation: Phase 1 W7-8 (June 2026)
- New service: `services/attachment/` or extension to existing relay
- MinIO docker container in `deploy/docker-compose.yml`
- Volume: `phantom-attachments` for object storage persistence
- New domain: `attachment.phntm.pro` with own Caddy block
- Files affected client: `shared/core/messaging/`, voice message implementation, future file picker
- Test plan: upload/download round-trip, expired attachment cleanup, large file (100MB+), corrupted blob recovery

### References

- WhatsApp media server architecture (analogue)
- Signal attachment server (open source)
- Inventory report, section D5 + Bug J discussion
- MinIO documentation

---

## ADR-014 DRAFT: iOS architecture (SwiftUI + KMP shared)

**Status:** Proposed
**Date:** 2026-09-XX (closer to Phase 4)

### Context

Inventory shows iOS as pure stub: 6 SwiftUI views, no KMP integration, identity = UserDefaults UUID, ChatList always empty. Phase 4 of roadmap brings iOS to feature parity with Android. Need to decide: pure Compose Multiplatform iOS, or SwiftUI native + KMP shared logic?

### Decision

**SwiftUI native UI + KMP shared logic:**

- All non-UI code in `shared/` modules (already KMP-friendly): crypto, messaging, transport, storage, identity
- Activate iosTargets in all 16 KMP modules
- Build XCFramework on macOS-builder, ship to iOS app
- iOS app code in Swift, using XCFramework as dependency
- UI built with SwiftUI (native iOS feel)
- Bridge code in `apps/ios/` translates KMP types to Swift-friendly equivalents

**Rationale:**
- Compose Multiplatform iOS is improving but still has iOS-specific quirks (1.7 has some animation/keyboard issues)
- iOS users expect iOS-native feel: NavigationStack, sheets, Action Sheets, swipe gestures, system fonts
- Performance: SwiftUI native > Compose iOS for now
- Long-term: Compose Multiplatform iOS may mature, can revisit later
- We already need platform-specific code for iOS-only features (Secure Enclave, APNs, Apple-specific permissions)

### Consequences

**Positive:**
- Native iOS UI quality (matters for App Store, user perception)
- Future iOS-specific features (Live Activities, Widget Kit, Apple Watch later) easier
- Better performance
- Smaller risk profile (Compose iOS less mature)

**Negative:**
- Duplicates UI code (Android = Compose, iOS = SwiftUI). Mitigation: shared logic in KMP, UI is ~30% of LOC anyway
- Need both Kotlin and Swift expertise (we're solo dev — learning curve)
- Two design implementations (mitigated by Design Brief v3 specifying behavior, both implementations follow)

**Neutral:**
- More files
- Dual test pipelines (instrumented Android + XCTest iOS)

### Alternatives considered

**Alternative A: Compose Multiplatform UI throughout**
Rejected for now (may revisit Phase 7+ if Compose iOS matures). Current trade-offs unfavorable.

**Alternative B: React Native or Flutter**
Rejected: would mean rewriting Android too. Investment already made in KMP/Compose.

**Alternative C: Pure SwiftUI native (no KMP)**
Rejected: would mean reimplementing all crypto, messaging, transport. ~10x effort.

### Implementation notes

- ADR drafted: Phase 3 W23 (closer to time)
- Implementation: Phase 4 W25-32 (Nov-Dec 2026)
- Prerequisites: macOS machine, Apple Developer enrollment, SQLCipher iOS license
- Files: new `apps/ios/` Xcode project, build pipeline for XCFramework
- Test plan: cross-platform interop (iOS-Android messaging, group, voice)

### References

- Compose Multiplatform iOS status (https://www.jetbrains.com/lp/compose-multiplatform/)
- Apple HIG (Human Interface Guidelines)
- Inventory report, section F3

---

## ADR-015 DRAFT: Pluggable transports (obfs4, Snowflake, fronting)

**Status:** Proposed
**Date:** 2027-01-XX (closer to Phase 5)

### Context

Phase-3 censorship-resistance deliverable: pluggable transports. PHANTOM in censored regions (Russia, China, Iran, Belarus) needs to circumvent DPI / SNI inspection / connection blocking.

### Decision

**Layered approach with automatic transport selection:**

```
Connection priority (client tries each in order):
1. Direct WebSocket to relay.phntm.pro (works in non-censored networks)
2. Tor onion service (xxxxx.onion) — most resilient but slower
3. obfs4 to relay (look-like-random traffic)
4. Snowflake (WebRTC-based, looks like browser traffic)
5. Domain fronting via Cloudflare (last resort, vulnerable to fronting being disabled)

Automatic detection:
- Test connection to direct relay on launch (5s timeout)
- If fails, try Tor onion (10s timeout)
- If fails, try obfs4 (10s)
- etc.
- Cache successful transport for 24h to avoid repeated fallthrough

Implementation:
- obfs4: bundle obfs4proxy as native library (Android: .so, iOS: framework)
- Snowflake: integrate snowflake-client library
- Domain fronting: bundle Cloudflare CDN endpoint, send Host header for relay.phntm.pro
- All wrap WebSocket transport at Ktor client engine level
```

### Consequences

**Positive:**
- Works in censored networks (Phase-3 deliverable)
- Multiple fallback layers (defense in depth)
- Foundation for future censorship-resistance features
- Tor onion = strongest anti-censorship guarantee

**Negative:**
- App size grows (~5-15MB for obfs4 + Snowflake binaries)
- Maintenance burden (transports get blocked, need updates)
- Latency: Tor ~200ms-2s vs direct ~50ms

**Neutral:**
- Need testing in actual censored environment (empirical proof on real RU / IR / CN networks before claiming this works)

### Alternatives considered

**Alternative A: Built-in VPN integration**
Rejected: VPN is user concern, not app concern. Recommend trusted VPNs in docs.

**Alternative B: Custom obfuscation protocol**
Rejected: rolling our own crypto + obfuscation is dangerous; obfs4 is battle-tested.

### Implementation notes

- Phase 5 W33-36 (January 2027)
- New module: `shared/core/transport/pluggable/`
- Build complexity: native libraries for Android (NDK) and iOS
- Test plan: test in DPI-emulated environment (Open Observatory of Network Interference for measurement)
- Documentation: `docs/CENSORSHIP_RESISTANCE.md`

### References

- Tor Project pluggable transports
- Tor onion services v3
- Cloudflare domain fronting (note: officially deprecated by some CDNs)
- Inventory report, section relevant to transport

---

## ADR-016 DRAFT: Push notifications hybrid (UnifiedPush + FCM + APNs)

**Status:** Proposed
**Date:** 2027-02-XX (closer to Phase 5)

### Context

Inventory: push notifications PARTIAL (~50%) — FCM scaffolded but token never sent to relay; UnifiedPush NONE; APNs NONE. Without push, app must run foreground service permanently (drains battery). Push is critical for retention.

But: FCM = Google = privacy concern. Pure UnifiedPush = doesn't work on iOS. Need hybrid.

### Decision

**Three-channel hybrid:**

**Android — primary: UnifiedPush**
- FOSS-aligned default
- Self-hosted distributors (NextPush, ntfy.sh) supported
- User chooses distributor on onboarding (or skips, falls to foreground service)
- Works without Google Services

**Android — opt-in fallback: FCM**
- For users who explicitly opt-in (Settings → "Use Google FCM for faster delivery")
- Standard Firebase setup, but with strict no-content payloads
- Server sends "wake up and check for messages" — actual content fetched via authenticated relay request

**iOS — required: APNs**
- Apple's only sanctioned push mechanism
- Same model: server sends silent push, app wakes, fetches via relay

**Common architecture:**

```
Server flow (when message arrives for offline user):
1. Relay stores envelope (existing store-and-forward)
2. Relay checks user's registered push tokens
3. Sends "you have new messages" silent push (no content)
4. Client wakes, connects to relay, fetches envelopes

Privacy properties:
- Push payload contains NO message content
- Push payload contains NO sender identity
- Only signal: "check for messages"
- Even if push provider (Google/Apple) sees: it's just "wake up" not "Alice messaged you"

Push token registration:
  Client → relay POST /push-token
    body: {public_key, push_provider: "fcm"|"apns"|"unifiedpush", token, distributor?}
  Relay stores per public_key (replaces previous token if any)
  Token rotates on app reinstall
```

### Consequences

**Positive:**
- iOS works (APNs mandatory)
- Android FOSS users have UnifiedPush (no Google)
- Battery life dramatically improved
- Mass-market viability

**Negative:**
- 3 push systems to maintain
- UnifiedPush ecosystem is small (most users won't have distributor configured)
- FCM dependency (opt-in but still a dependency for those who choose it)

**Neutral:**
- Privacy story: "metadata only signals, no content" is accurate but needs explanation in user docs

### Alternatives considered

**Alternative A: Pure foreground service (no push)**
Rejected: battery drain unacceptable for mass-market.

**Alternative B: Pure FCM**
Rejected: tied to Google, conflicts with FOSS positioning, doesn't work without Play Services.

**Alternative C: Custom push server**
Considered. Rejected: Apple won't allow custom push on iOS. Android could do it, but maintaining own push infrastructure is huge cost.

### Implementation notes

- Phase 5 W37-38 (February 2027)
- Files: `apps/android/.../notifications/`, `apps/ios/.../notifications/`, `services/relay/src/push.rs`
- FCM v1 API (legacy deprecated — confirm in inventory note)
- UnifiedPush spec compliance
- APNs token-based authentication (not certificate-based)
- Test plan: push delivery time on Pixel, Tecno, Samsung; iOS device test
- Documentation: `docs/PUSH_NOTIFICATIONS.md`

### References

- UnifiedPush spec (https://unifiedpush.org/)
- Apple APNs documentation
- FCM v1 migration guide
- Inventory report, section D8

---

## SUMMARY

This document drafts 10 ADRs covering all major architectural decisions for the 12-month roadmap. Each ADR captures:
- **Why** decision was needed (context)
- **What** decision is (specific, implementable)
- **Trade-offs** accepted (consequences)
- **What we did NOT choose** (alternatives)

**Process to finalize:**

1. **Phase 1 W1 (May Week 1):**
   - Read this document
   - Pick top 3 to write first: ADR-007, ADR-008, ADR-013 (most urgent)
   - Each one: 1-2 hours of writing, refine draft with specific implementation details
   - Save to `docs/adr/ADR-007-username-namespace.md` etc.
   - Commit with message: "docs(adr): ADR-007 username uniqueness via relay namespace"

2. **Phase 1 W2-3:** Write ADR-009 (CRITICAL, F13/F14/F15 fix design)

3. **Phase 1 W4-5:** Write ADR-010, ADR-011

4. **Phase 2 W9:** Write ADR-012 (account migration)

5. **Phase 3 W23:** Write ADR-014 (closer to iOS work)

6. **Phase 4 W31-32:** Write ADR-015, ADR-016 (closer to censorship work)

**For the Alpha-2 release window (~end of May):**

If external reviewers look at `docs/adr/`, they should see:
- ADR-001 through ADR-006 (existing)
- ADR-007, ADR-008 (written)
- Possibly ADR-009 (if time)
- README in `docs/adr/` listing planned ADRs with target dates

This shows mature engineering practice — strong signal for grant acceptance.

---

**Конец документа.**

Каждый ADR — independent. Можно начать с любого. Рекомендация: ADR-009 (security блокер) первым, потом 007 (нужен для Phase 2), потом 013 (нужен для Phase 1 attachment server).
