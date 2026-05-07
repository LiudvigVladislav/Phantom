# ADR-006: Crypto Library Decision

Статус: **REVISED** (originally ACCEPTED 2026-04-15, revised 2026-04-29)
Дата ревизии: 2026-04-29

## Update history

- 2026-04-15: ACCEPTED — libsignal-client (AGPL-3.0) + libsodium (ISC)
- 2026-04-29: REVISED — libsignal-client deferred to Beta+; Alpha 2 ships
  libsodium-based Signal-protocol-compatible implementation

## Why this revision

The 2026-04-15 decision intended to use libsignal-client as the primary
crypto library. In implementation (commits up to `697103d2`) the team
shipped a libsodium-based Signal-protocol-compatible stack (Double
Ratchet over X25519 + XSalsa20-Poly1305 + Ed25519) without integrating
libsignal-client. This ADR reconciles documentation with code reality.

Drivers of the de-facto path:
- libsodium has lower API surface and zero JNI complexity vs libsignal's
  Rust + JNI bridge
- AGPL-3.0 plus Signal Foundation's "use outside Signal not supported"
  posture creates non-trivial integration risk
- Test vectors and examples for self-rolled Double Ratchet over libsodium
  primitives are well-documented (Signal whitepapers + reference
  implementations)

## Current state (Alpha 2)

PHANTOM uses **libsodium-bindings** (KMP wrapper, ISC license) for all
cryptographic primitives:

- X25519 — key exchange
- XSalsa20-Poly1305 — authenticated encryption
- Ed25519 — signatures (currently unused, see ADR-017)
- HKDF-SHA256 — key derivation (planned for ADR-009 / SenderKey rotation)
- BLAKE2b — Safety Number derivation

The Signal protocol layer (X3DH + Double Ratchet) is implemented in
`shared/core/crypto/`:

- `LibsodiumX3DH.kt` — X3DH handshake (initiator/recipient)
- `LibsodiumDoubleRatchet.kt` — Double Ratchet encrypt/decrypt
- `SealedSender.kt` — sealed sender envelope wrap/unwrap

## Known limitations of current implementation

The self-rolled Double Ratchet implementation has documented gaps vs the
libsignal reference. All are tracked for closure in Phase 1 or as
explicit P3 items:

1. **No header encryption.** Ratchet header is not encrypted; the relay
   sees ratchet metadata (counter values, ratchet pubkey). Severity: P3.
   Mitigation: sealed sender hides identity even if metadata leaks.

2. **Limited skipped-message-key cache.** Small window; out-of-order
   delivery beyond the window fails MAC validation. See ISSUE-004 in
   `KNOWN_ISSUES.md`. Mitigation: sender retry; planned widening in
   Alpha 2.

3. **X3DH not yet wired.** `SessionManager.getOrCreateSession` calls
   only `computeSharedSecret(...)`, bypassing `initiatorHandshake` and
   `recipientHandshake`. See finding F12 in 2026-04-29 security audit.
   **Fix:** ADR-009 (Phase 1 Week 4).

4. **No prekey infrastructure.** No signed prekey, no one-time prekeys.
   **Fix:** ADR-009 (Phase 1 Week 4).

5. **SenderKey signing keys generated but never used.** See ADR-017.

## Decision (revised)

For **Alpha 2 (Phase 1, May–Jun 2026):**

- Continue with libsodium-based implementation
- Land ADR-009 (identity / signed prekey / one-time prekey separation)
  in Phase 1 Week 4
- Ship test vectors for X3DH and Double Ratchet against the Signal
  protocol specification
- Document all known limitations explicitly (this ADR + KNOWN_ISSUES.md)

For **Beta and beyond:**

- **Re-evaluate libsignal-client** integration once:
  - Phase 1 security blockers are closed (F12, F15, F8, F1, F3, F4)
  - Independent crypto audit performed (Phase 6 milestone)
  - AGPL-3.0 license compatibility re-confirmed for our distribution
- If audit recommends libsignal — migrate; otherwise stay on libsodium
  with full security hardening landed in Phase 1

## Consequences

- ADR-006 originally said "use libsignal" — that intent is **deferred**,
  not abandoned
- `gradle/libs.versions.toml` libsignal entry remains commented out
- All security findings tied to self-rolled crypto (F12, F15, F3) are
  closed via Phase 1 work, not via library swap
- Project repo remains AGPL-3.0 (decision unchanged)
- Re-evaluation trigger: post-Phase 6 audit response, or any new finding
  indicating self-rolled crypto cannot meet the threat model

## References

- [signalapp/libsignal](https://github.com/signalapp/libsignal) — AGPL-3.0
- [libsodium](https://libsodium.org) — ISC License
- ADR-009 — identity / prekey separation (Phase 1)
- ADR-017 — SenderKey signing key removal (Phase 1)
- `KNOWN_ISSUES.md` — current state visible to users and reviewers
- Security audit 2026-04-29 — findings F12, F15, F3, F13
