# ADR-027: Replace Shared WS Token With Per-User Ed25519 Signed Challenge

**Status:** Accepted
**Date:** 2026-05-08
**Deciders:** Vladislav Liudvig (solo author)
**Related:** ADR-009 (Identity / PreKey separation), ADR-006 (Crypto Library Decision)
**Closes:** Security findings F11 (shared WS token), F26 (token leaks via APK + proxy access logs)

---

## Context

The relay's WebSocket upgrade was gated by a single shared secret `?token=…`
matched against `RELAY_SECRET_TOKEN`. Anyone with that string could open a WS
session for *any* `?id=…` and subscribe to that identity's offline queue.

The token leaked through three vectors that no amount of operational hygiene
could close:

* **APK extraction** (F26) — the production token shipped inside the Android
  APK as a `BuildConfig.RELAY_TOKEN` constant. `apktool` recovers it in seconds.
* **Proxy access logs** (F26) — every reverse-proxy hop (Caddy, anti-DDoS,
  CDN) records the full request URI, including the query string. Operators,
  hosting providers, and anyone with logfile access read the token in plaintext.
* **Shared-secret model** (F11) — even without leak, the token authenticates
  the *fleet*, not the user. Subscribing to any identity's queue requires only
  knowing its X25519 pubkey — public material by design.

This was acknowledged in the prior code as "Alpha-0 shared-secret protection
for a private demo relay; not a replacement for per-user authentication."

## Decision

Replace the shared `?token=` with a per-user signed-challenge handshake bound
to the existing Ed25519 signing keypair (ADR-009 — every Alpha 2 identity
already has one for SPK signing).

```
1. Client →  GET  /auth/challenge?identity=<X25519_hex>
   Relay  →  200  { "nonce_hex": "...", "expires_at_ms": ... }   single-shot, 5-min TTL

2. Client signs  nonce_bytes  with Ed25519 signing private key  →  64-byte sig

3. Client →  GET  /ws?id=<X25519>
                  &signing_pubkey=<Ed25519>
                  &challenge=<nonce_hex>
                  &signature=<sig_hex>
   Relay  →  101 Switching Protocols   (after one-shot consume + verify)
```

Verification flow on the relay (`auth.rs::authorise_ws`):

* Single-shot consume the nonce from `ChallengeStore` — gone after this call.
* `SigningKeyBindings.get_or_register_tofu(identity, proposed)` — TOFU on
  first connect, mismatch on later attempts is rejected (mirrors the 1:1
  invariant `publish_prekeys` already enforces).
* `VerifyingKey::verify(nonce, signature)` against the bound signing key.

`publish_prekeys` calls `SigningKeyBindings::bind` so the binding is
identical whether established via TOFU or via a publish.

## Backward compatibility

**Hard cut-over.** Per Vladislav 2026-05-08:
* Alpha 2 has not been released publicly.
* No production users — only solo testing on Tecno + emulator.
* A migration window of "accept either token OR signed challenge" doubles
  auth surface area for a non-existent userbase.

Old APKs that still send `?token=` get a 401 from the new relay. The fix is
to install the new APK.

## TOFU first-connect

A new identity that has not yet published a prekey bundle (the F-08 race
window: bootstrap and WS connect launch in parallel) presents its
`signing_pubkey` for the first time at the WS upgrade. The relay records
the binding at this moment; subsequent reconnects must use the same key.

Threat model: an attacker who *also* races for the same X25519 identity at
the exact same first-connect moment can bind a different signing key first
and lock the legitimate user out. This is the canonical TOFU risk and is
identical to the SSH `known_hosts` model. Mitigation: an X25519 identity
private key is itself secret — an attacker who can race the legitimate user
already has access to material that should be impossible to obtain.

## Persistence

Signing-key bindings are not stored to disk in their own JSONL. Instead, on
relay restart `AppState::rebuild_signing_keys_from_prekeys` replays the
existing prekey JSONL (which already records `signing_pubkey_hex` per
identity) into the in-memory binding map. Pure TOFU bindings (identity
connected once but never published) are lost on restart; the same legitimate
holder of the Ed25519 private key re-establishes them on the next reconnect.

## Consequences

**Positive:**
* Token leak vectors (APK + proxy logs + accidental code-share) are gone.
  There is no shared secret left to leak.
* WS auth identifies the *user*, not the *fleet*. An attacker who somehow
  obtains another user's X25519 pubkey still cannot subscribe to their
  queue without their Ed25519 signing private key.
* `RELAY_SECRET_TOKEN` env var on the relay and `RELAY_TOKEN` BuildConfig
  field on the client become legacy admin-only material (relay still uses
  `RELAY_SECRET_TOKEN` for `/admin/*` endpoints — those are operator-only
  and a shared secret is acceptable there).

**Cost:**
* Each WS reconnect generation now requires an extra HTTP round-trip to
  `/auth/challenge` before the WS upgrade. ~50 ms direct, ~500 ms over
  Tor / Xray. Single-digit second amortised over a healthy long-lived
  connection.
* Five-minute challenge TTL means a client that pre-fetches a challenge,
  then suspends for >5 min before connecting, must fetch again. The
  `KtorRelayTransport` reconnect loop fetches per generation so this is
  not a real concern.

## Implementation

| Side | Path | Change |
|---|---|---|
| Relay | `services/relay/src/auth.rs` (new) | `ChallengeStore`, `SigningKeyBindings`, `AuthError` |
| Relay | `services/relay/src/lib.rs` | Register `auth` module |
| Relay | `services/relay/src/state.rs` | Add `auth_challenges` + `signing_keys` to `AppState`; `rebuild_signing_keys_from_prekeys` startup hook |
| Relay | `services/relay/src/prekeys.rs` | `iter_identity_signing_pairs()` for startup rebuild |
| Relay | `services/relay/src/routes.rs` | `auth_challenge` handler + `authorise_ws` replacing the old shared-token check; `publish_prekeys` also calls `signing_keys.bind` to keep stores in sync |
| Relay | `services/relay/src/main.rs` | Call `rebuild_signing_keys_from_prekeys`; sweep expired challenges in cleanup task |
| Client | `RelayTransport.kt` (KMP) | `connect()` signature: `signingPublicKeyHex` + `signChallenge` lambda replace `token` |
| Client | `KtorRelayTransport.kt` (KMP) | `buildAuthedWsUrl` does `/auth/challenge` round-trip + signs + builds WS URL |
| Client | `IdentityManager.kt` (KMP) | `signRelayChallenge(nonce)` exposes Ed25519 sign over the loaded keypair |
| Client | `PhantomMessagingService.kt` (Android) | Loads signing pair, passes `signingPublicKeyHex` + `signRelayChallenge` lambda to `transport.connect` |
| Client | `apps/android/build.gradle.kts` | `RELAY_TOKEN` `buildConfigField` removed |
| Tests | `*Test.kt` fakes | Updated to new `connect` signature |
