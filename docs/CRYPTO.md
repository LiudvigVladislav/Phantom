# PHANTOM — Cryptography Overview

**Status:** draft v0.1 (2026-04-24). **Authoritative code:**
[`shared/core/crypto/`](../shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto).
**Decision record:** [ADR-006](adr/ADR-006-Crypto-Library-Decision.md).

PHANTOM writes no custom cryptography (Product Doctrine §2.4). All
primitives come from two audited libraries:

- [**libsignal**](https://github.com/signalapp/libsignal) — the
  Signal-Foundation Rust implementation of the Signal Protocol.
  AGPL-3.0, which is license-compatible because PHANTOM itself is
  AGPL-3.0 on the relay and GPL-3.0 on the client.
- [**libsodium**](https://libsodium.org) — ISC-licensed primitives.
  Used via [`ionspin/kotlin-multiplatform-crypto`](https://github.com/ionspin/kotlin-multiplatform-crypto)
  for X25519, XSalsa20-Poly1305, SHA-256, ed25519.

---

## 1. Primitives and algorithms

| Purpose                        | Primitive                                    |
|---|---|
| Identity key pair              | X25519 (Curve25519 in Diffie-Hellman form)   |
| Symmetric message encryption   | XSalsa20-Poly1305 (via libsodium SecretBox)  |
| Key derivation (chain + root)  | SHA-256 based KDF                            |
| Message authenticity           | Poly1305 MAC inside SecretBox                |
| Key agreement                  | X3DH (three Diffie-Hellman exchanges)        |
| Forward secrecy                | Double Ratchet                               |
| Identity verification          | Safety Numbers (SHA-256 fingerprint)         |
| Envelope privacy               | Sealed Sender (X25519 ephemeral + XSalsa20)  |
| Traffic analysis resistance    | Constant-size padding (4 KiB fixed)          |

Nonces are 24 bytes, drawn from `LibsodiumRandom.buf(24)`, and never
reused — SecretBox requires unique nonces per key, which Double
Ratchet enforces structurally by advancing the chain after each message.

---

## 2. X3DH — initial key agreement

When Alice first messages Bob, she performs a three-way Diffie-Hellman
handshake to derive a shared secret that Bob can reconstruct without
prior coordination.

Concrete code:
[`LibsodiumX3DH.kt`](../shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/LibsodiumX3DH.kt).

```
Alice (initiator)                        Bob (recipient)
─────────────────                        ───────────────
ik_a     identity key pair               ik_b, spk_b    identity + signed pre-key
ek_a     ephemeral key pair

DH1 = DH(ik_a.priv,  spk_b.pub)          DH1 = DH(spk_b.priv, ik_a.pub)
DH2 = DH(ek_a.priv,  ik_b.pub)           DH2 = DH(ik_b.priv,  ek_a.pub)
DH3 = DH(ek_a.priv,  spk_b.pub)          DH3 = DH(spk_b.priv, ek_a.pub)
masterSecret = SHA-256(DH1 || DH2 || DH3)

Zeroize DH1, DH2, DH3 immediately.
```

The zeroization happens in
[`LibsodiumX3DH.kt#initiatorHandshakeWithEphemeral`](../shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/LibsodiumX3DH.kt)
and was added in the Этап 2 security pass (2026-04-24). Without this
step, the raw DH outputs would sit on the JVM heap until a garbage
collection sweep, expanding the window for memory-dump attacks.

---

## 3. Double Ratchet — per-message freshness

Every outgoing message uses a unique message key derived from two
chains:

```
chainKey_i+1 = SHA-256(chainKey_i || 0x02)
messageKey_i = SHA-256(chainKey_i || 0x01)
```

And every time the conversation reverses direction (Alice sent last,
Bob now sends), a new DH ratchet step rotates the root key:

```
newRoot, newChain = KDF(rootKey, DH(localRatchetPriv, remoteRatchetPub))
```

**Guarantees:**

- **Forward Secrecy (FS).** A compromise today does not expose
  yesterday's messages. Past message keys are derived from chain
  keys that were discarded and zeroized.
- **Post-Compromise Security (PCS).** If an attacker steals Alice's
  state today, a single future DH ratchet step (Bob replies, Alice
  sends again) restores secrecy.

Implementation:
[`LibsodiumDoubleRatchet.kt`](../shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/LibsodiumDoubleRatchet.kt).
Keys are zeroized after use (`messageKey.zeroize()` in both `encrypt()`
and `decrypt()`, and `dhOutput.zeroize()` after every ratchet step).

---

## 4. Key Rotation Detection

A contact who reinstalls the app produces a new identity key. Today,
PHANTOM detects this in two ways:

1. **Explicit control message.** A `TYPE_KEY_ROTATION` payload in
   [`MessagePayload.kt`](../shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/MessagePayload.kt)
   allows a peer to announce a key rotation. On receipt, PHANTOM:
   - Updates the stored public key.
   - Sets `identity_key_changed_at = now()` in the conversation row.
   - Resets `is_verified = false`.
   - Deletes the old Double Ratchet session.
2. **Implicit detection.** When an unknown public key arrives with the
   same username as a known contact, the conversation is flagged with
   `identity_key_changed_at` and a red banner appears in the
   UI asking the user to re-verify the Safety Number.

Code paths:
[`DefaultMessagingService.kt`](../shared/core/messaging/src/commonMain/kotlin/phantom/core/messaging/DefaultMessagingService.kt)
(handler) and
[`ContactProfileScreen.kt`](../apps/android/src/androidMain/kotlin/phantom/android/screens/contact/ContactProfileScreen.kt)
(UI banner).

---

## 5. Memzero practice

All transient secrets (DH outputs, message keys, intermediate chain
keys) are zeroized immediately after use:

```kotlin
// shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/SecureMemory.kt
fun ByteArray.zeroize() = fill(0)
```

JVM garbage collection does not offer timely erasure, so we write zeros
ourselves. This is a best-effort defense against cold-boot attacks and
process-memory forensics. It is not a full mitigation — a fully
compromised OS can still read live process memory — but it meaningfully
shortens the exposure window.

Call sites: search `.zeroize()` across
[`shared/core/crypto/`](../shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto).

**TODO:** Native-side allocation via `sodium_malloc` for keys that
need to outlive a single call (e.g., the stored identity key). Today
they live in JVM-managed `ByteArray` instances; `sodium_malloc` would
let us mlock them and guarantee zero-on-free semantics.

---

## 6. Safety Numbers

Before Alice trusts a conversation, she can verify that her view of
Bob's public key matches Bob's view of her public key. PHANTOM shows
a 60-digit fingerprint grouped as 5 × 12 digits, plus a QR code for
in-person scanning.

Algorithm (see
[`SafetyNumber.kt`](../shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/SafetyNumber.kt)):

```
fingerprint = SHA-256( sort(alicePubKeyHex, bobPubKeyHex) concatenated )
trimmed     = first 30 bytes of fingerprint as a decimal string
display     = group trimmed into 5 chunks of 12 digits
```

The function is symmetric — both sides compute the same number
regardless of who started the conversation. If Bob later reinstalls,
the fingerprint changes and the red banner (§4) prompts re-verification.

---

## 7. Sealed Sender

Before Sealed Sender, the relay could observe the sender's public
key for every message — enough to reconstruct a full communication
graph (who talks to whom, how often) without seeing content. Sealed
Sender removes that leak by encrypting the sender identifier inside
the envelope using a key only the *recipient* can derive.

Wire format (see
[`SealedSender.kt`](../shared/core/crypto/src/commonMain/kotlin/phantom/core/crypto/SealedSender.kt)):

```
ephemeralPub (32 bytes) || nonce (24) || XSalsa20-Poly1305( KDF( X25519(ephPriv, recipientPub) ) ; senderPubKeyHex )
```

The relay sees only `{ to = recipientPub, sealed_sender = opaqueBlob,
payload = ciphertext }`. The `from` field in the wire protocol is an
empty string for all sealed messages. Combined with fixed-size padding
(4 KiB) on the payload, this significantly increases the cost of
graph inference.

---

## 8. What we do **not** do

- We do not implement any cryptographic primitive ourselves. Every
  algorithm above is a thin orchestration over libsodium/libsignal.
- We do not serve plaintext to any infrastructure component. Relay,
  landing page, DNS, and future media server all handle ciphertext
  only. See [ADR-004](adr/ADR-004-Relay-Trust-Model.md).
- We do not rely on TLS as a substitute for end-to-end encryption.
  The Double Ratchet layer is independent of the transport; even if
  the TLS connection were MITM-ed, message content remains opaque.
- We do not collect plaintext telemetry. Logs on the relay are
  method + path only, never query strings (which might carry tokens).

---

## 9. References

- [Signal Protocol specification](https://signal.org/docs/)
- [X3DH specification](https://signal.org/docs/specifications/x3dh/)
- [Double Ratchet specification](https://signal.org/docs/specifications/doubleratchet/)
- [libsodium documentation](https://doc.libsodium.org/)
- [ADR-006: Crypto Library Decision](adr/ADR-006-Crypto-Library-Decision.md)
- [Threat Model v0](threat-model/Threat_Model_v0.md)

**TODO:** Formal symbolic analysis of the Double Ratchet + Sealed
Sender composition using ProVerif or Tamarin before v1.0. For Alpha-1
we rely on the well-studied composition in Signal's production
deployment.
