# ADR-024: Double Ratchet Session-State Wrap via Android Keystore

**Status:** Accepted  
**Date:** 2026-05-08  
**Deciders:** Vladislav Liudvig (solo author)  
**Related:** ADR-023 (Local Prekey Keystore Wrap), ADR-006 (Crypto Library Decision)

---

## Context

PHANTOM stores Double Ratchet session state as a JSON blob in the
`ratchet_state` SQLite table (`conversation_id TEXT PK → state_blob TEXT`).
The blob contains:

| Field | Sensitivity |
|---|---|
| `rootKey` | CRITICAL — drives the KDF ratchet |
| `sendingChainKey` | CRITICAL — derives per-message keys |
| `receivingChainKey` | CRITICAL — derives per-message keys |
| `sendingRatchetPrivateKey` | CRITICAL — used in DH ratchet steps |
| `sendingRatchetPublicKey` | Moderate — sent in message headers, but tracks session progress |
| `receivingRatchetPublicKey` | Moderate — peer's last DH ratchet key |
| `sendCount` / `receiveCount` | Low — counters only |

The database file is SQLCipher-encrypted (passphrase wrapped by
`phantom_db_passphrase_key` in Android Keystore). SQLCipher closes the
cold-disk threat but leaves ratchet state readable as plaintext once the
database is open in process memory. An attacker with ADB access to a
debug build, memory-dump capability, or a decrypted SQLite export can read
the root key and chain keys of all active sessions.

ADR-023 established the same threat model for prekey private bytes and
applied Android Keystore AES-256-GCM wrapping. ADR-024 applies the same
treatment to ratchet session state.

---

## Decision

Wrap the `state_blob` column contents with a dedicated Keystore-backed
AES-256-GCM cipher before writing to SQLite, and unwrap on read.

### Key distinction from ADR-023

The prekey cipher (`phantom_prekey_wrap_v1`) uses
`setUnlockedDeviceRequired(true)`: prekey operations happen while the user
is actively onboarding or on the weekly SPK rotation cycle.

The ratchet cipher (`phantom_ratchet_wrap_v1`) does **NOT** set
`setUnlockedDeviceRequired`. Ratchet state is updated on every message
send and receive — including background delivery while the screen is off.
If the Keystore key required an unlocked device, a background message
received while the phone is locked would fail to persist the updated
ratchet state, causing the session to desynchronise on the next decrypt.
The key is therefore accessible after first-unlock-since-boot, consistent
with `DatabasePassphraseManager`.

### On-disk format

Wrapped rows: `"rs1:" + Base64( AES-256-GCM( jsonBlob.utf8 ) )`

Legacy rows written before H-1 have no prefix and are decoded as plaintext
JSON (lazy migration — re-wrapped on the next upsert for that conversation).

The `rs1:` prefix is distinct from the prekey codec's `v1:` prefix to
prevent cross-table confusion in tooling or future forensic analysis.

### New Keystore alias

`phantom_ratchet_wrap_v1` — independent from `phantom_prekey_wrap_v1`
and `phantom_db_passphrase_key`. Three independent Keystore keys with
three independent rotation paths and policy profiles going forward.

---

## Implementation

Five files changed:

| File | Change |
|---|---|
| `AndroidKeystoreBlobCipher.kt` | Parameterise by alias + `requireUnlockedDevice`; add `createAndroidRatchetKeystoreCipher()` |
| `RatchetStateStorageCodec.kt` | New — `encodeForStorage` / `decodeFromStorage` for JSON blobs |
| `SqlDelightRatchetStateRepository.kt` | Accept `KeystoreBlobCipher` (defaults to `IdentityCipher`); delegate to codec |
| `AppContainer.kt` | Wire `createAndroidRatchetKeystoreCipher()` into ratchetRepo |
| `RatchetStateStorageCodecTest.kt` | Unit tests for codec (IdentityCipher + XorCipher) |
| `AndroidRatchetKeystoreCipherTest.kt` | Instrumented tests: round-trip, tamper-rejection, alias isolation |

---

## Consequences

**Positive:**
- Ratchet root key and chain keys no longer readable from a plaintext
  SQLite export or memory image without Keystore access.
- Follows the established `KeystoreBlobCipher` + codec pattern; no new
  crypto primitives introduced.
- Lazy migration means no one-shot rewrite pass — existing sessions
  migrate transparently on next message exchange.

**Accepted limitations:**
- Key accessible after first-unlock-since-boot (not strictly since-last-
  unlock). A sophisticated attacker with physical device access immediately
  after reboot, before first unlock, can read ratchet state via the cold
  SQLite file — but SQLCipher passphrase is also Keystore-wrapped and
  subject to the same policy, so this is a consistent system-wide posture.
- Two Keystore round-trips per message (one read + one write of state).
  Measured overhead on Pixel 6a: < 3 ms per call in `Dispatchers.IO`.
- Session state encrypted per-device; no effect on server-side or relay
  threat model.

**Future work:**
- ADR-025 (planned): consider `setUserAuthenticationRequired` on the
  ratchet key to bind decryption to recent biometric auth. Requires UX
  design for the locked-while-receiving scenario.
