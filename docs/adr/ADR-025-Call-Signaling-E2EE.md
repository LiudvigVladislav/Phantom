# ADR-025: Route Call Signalling Through Double Ratchet + Sealed Sender

**Status:** Accepted  
**Date:** 2026-05-08  
**Deciders:** Vladislav Liudvig (solo author)  
**Related:** ADR-006 (Crypto Library Decision), ADR-024 (Ratchet State Keystore Wrap)  
**Closes:** Security findings F19 (call signalling no E2EE), F20 (no Sealed Sender on calls)

---

## Context

PHANTOM's WebRTC call signalling (SDP offer/answer, ICE candidates, hangup, reject) was
sent as plaintext base64-encoded JSON directly via `RelayTransport.send()`, bypassing
the Double Ratchet and Sealed Sender pipeline used for every regular message.

The relay therefore received — in cleartext:

| Field | Exposure |
|---|---|
| `from = myPubKeyHex` | Full caller identity, not anonymised |
| `to = recipientPubKeyHex` | Full callee identity |
| SDP offer/answer | ICE candidates with LAN IPs, NAT-mapped public IPs, DTLS fingerprint, codec list |
| `call_offer` / `call_hangup` timing | Call duration and rejection/accept events |

This was a deliberate interim decision (documented as `SECURITY DEBT` in the code) made
while the session-manager / Double Ratchet infrastructure was still being built out.
ADR-023 (prekey wrap) and ADR-024 (ratchet state wrap) completed the storage hardening;
ADR-025 closes the wire-level leak.

The ring screen concern — "the callee must know who is calling" — was cited as a reason
not to use Sealed Sender. This is a misconception: the caller's identity is embedded
in the decrypted payload and passed to `onCallMessage(payload, fromPubKeyHex)` after
Sealed Sender unsealing. The relay learns nothing; the callee's device learns everything
it needs.

---

## Decision

Route all call-signalling payloads through the same Double Ratchet + Sealed Sender
pipeline that regular messages use, by adding `MessagingService.sendCallSignal()`.

`CallManager` no longer holds a reference to `RelayTransport`. It calls
`MessagingService.sendCallSignal(recipientPublicKeyHex, payload)` instead.
`DefaultMessagingService.sendCallSignal()` derives the `conversationId` and delegates
to the existing private `sendSealedPayload()` method — the same path used by read
receipts (C-2).

The plaintext call-signalling fast-path in `DefaultMessagingService.handleDeliver()`
is removed. All incoming call signals now arrive via the normal sealed envelope path
and are dispatched to `onCallMessage` after decryption, exactly as before.

---

## Consequences

**Positive:**
- The relay can no longer read SDP, ICE candidates, caller/callee identities, or
  infer call duration from signalling messages.
- Wire format is indistinguishable from regular chat messages — traffic analysis
  cannot distinguish a call from a text conversation.
- `CallManager` is decoupled from `RelayTransport`; the only transport dependency
  is through `MessagingService`, which is the correct abstraction boundary.

**Accepted limitations:**
- A first call to a peer with no existing DR session requires a prekey bundle fetch.
  If the peer has no bundle on the relay (e.g., they haven't onboarded), the call
  offer fails to send. The caller sees no ring — the call must be retried once the
  peer is online and has published a bundle. This is consistent with the behaviour
  for first messages.
- ICE candidates are encrypted one-by-one as they are gathered; this means multiple
  sequential encrypt-then-send round-trips during ICE negotiation. Measured overhead
  on Pixel 6a: < 5 ms per candidate on `Dispatchers.IO`.

**Audio stream:** WebRTC audio is already DTLS-SRTP end-to-end encrypted at the
media layer regardless of this change. This ADR covers only the signalling plane.

---

## Implementation

Four files changed:

| File | Change |
|---|---|
| `MessagingService.kt` | `sendCallSignal(recipientPublicKeyHex, payload)` added to interface |
| `DefaultMessagingService.kt` | `sendCallSignal()` implemented via `sendSealedPayload()`; plaintext fast-path removed |
| `CallManager.kt` | Constructor: `transport + myPubKeyHex` → `messagingService`; `sendSignal()` calls `sendCallSignal()` |
| `AppContainer.kt` | `CallManager` initialised with `service` (the `DefaultMessagingService` instance) |
