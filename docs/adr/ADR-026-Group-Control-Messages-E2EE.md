# ADR-026: Route Group Control Messages Through Double Ratchet + Sealed Sender

**Status:** Accepted
**Date:** 2026-05-08
**Deciders:** Vladislav Liudvig (solo author)
**Related:** ADR-006 (Crypto Library Decision), ADR-025 (Call Signalling E2EE)
**Closes:** Security finding F1 (group control messages outside Double Ratchet)

---

## Context

PHANTOM's group control messages (`group_invite`, `sender_key_distribution`,
`group_add_member`, `group_leave`) were sent as plaintext base64-encoded JSON
directly via `RelayTransport.send()`, bypassing the Double Ratchet and Sealed
Sender pipeline used for every regular 1:1 message.

The relay therefore received — in cleartext:

| Field | Exposure |
|---|---|
| `from = myPubKeyHex` | Full sender identity, not anonymised |
| `to = recipientPubKeyHex` | Full recipient identity |
| `senderKeyChainHex` (in SKD) | The 32-byte SenderKey chain key — full read access to the entire group's encrypted message stream from this iteration onward |
| `groupId` | Stable group identifier across the relay's lifetime |
| `groupMembers` (in invite / add) | Full member roster (pubkeys + usernames) |

The chain-key leak is the worst of these. A relay operator that captures one
SKD envelope can decrypt every subsequent group message that any member sends,
indefinitely. TLS-to-relay (the only protection in the prior implementation)
defends against passive network observers but provides zero protection against
the relay operator themselves — exactly the threat model we exist to defeat.

A misleading inline comment in `DefaultGroupMessagingService.sendControlMessage`
claimed "Double Ratchet layer applied by `DefaultMessagingService`," but
`DefaultGroupMessagingService` had no `MessagingService` reference and the
control messages went straight to the raw transport. The receive side
(`DefaultMessagingService.handleDeliver`) actually expected DR-wrapped envelopes
and routed group payloads to `groupMessagingService.handleIncoming()` only after
decryption — so on the wire the send and receive sides were structurally
incompatible. Control messages either silently failed delivery or, before a
2026-05-01 fix, were silent-ack'd and dropped entirely.

---

## Decision

Route every group control message through the same Double Ratchet + Sealed
Sender pipeline that 1:1 messages and call signalling already use, by adding
`MessagingService.sendGroupControlMessage()` and giving
`DefaultGroupMessagingService` a `MessagingService` constructor dependency.

`DefaultGroupMessagingService.sendControlMessage()` and the per-member
broadcast loop in `sendGroupPayload()` no longer call `transport.send()`
directly — they call `messagingService.sendGroupControlMessage(peer, payload)`.
`DefaultMessagingService.sendGroupControlMessage()` delegates to the existing
private `sendSealedPayload()` method (same path used by read receipts and call
signals).

The receive side is unchanged: `DefaultMessagingService.handleDeliver()`
already routes `payload.type in GROUP_TYPES` to
`groupMessagingService?.handleIncoming()` after Double Ratchet decryption.

---

## Consequences

**Positive:**
- The relay can no longer read SenderKey chain keys, group rosters, group IDs,
  or infer group activity timing from control messages.
- Wire format is indistinguishable from regular chat messages — relay-side
  traffic analysis cannot tell a group invite from a text reply.
- The structural send/receive incompatibility is fixed: control messages now
  produce well-formed `WireFrame` envelopes that the receive side accepts.
- `DefaultGroupMessagingService` no longer needs a `RelayTransport` reference
  for outgoing payloads, only `MessagingService` — a tighter dependency
  boundary.

**Accepted limitations:**
- A first control message to a peer with no existing DR session triggers a
  prekey bundle fetch (X3DH bootstrap). If the peer has no bundle on the
  relay, the control message fails. This is consistent with the 1:1 send
  behaviour and the call-signalling behaviour set in ADR-025.
- Each group invite / SKD / add-member is now N separate sealed envelopes
  (one per recipient) instead of N raw envelopes — same envelope count,
  larger envelope size due to padding + sealed sender header. Negligible
  for groups under 100 members.

**Wire-format compatibility:** This is a breaking change for groups that
already exchanged plaintext control messages. Per project doctrine groups
are not yet in production (Alpha-0 scope excludes them); existing groups
must be re-formed.

**Module boundary:** `DefaultGroupMessagingService` now depends on
`MessagingService`. `DefaultMessagingService` already had a back-reference
to `groupMessagingService` (late-bound via mutable property) for incoming
routing. Construction order in `AppContainer`:
1. Build `DefaultMessagingService` — `groupMessagingService = null`
2. Build `DefaultGroupMessagingService(messagingService = service)` — forward
   reference to the already-constructed DMS
3. `service.groupMessagingService = groupService` — close the late-bound
   back-reference

No cycle exists at construction time: step 2 only needs DMS as a value, not
its internal state.

---

## Implementation

Four files changed:

| File | Change |
|---|---|
| `MessagingService.kt` | `sendGroupControlMessage(toPubKeyHex, payload)` added to interface |
| `DefaultMessagingService.kt` | `sendGroupControlMessage()` implemented via `sendSealedPayload()` |
| `DefaultGroupMessagingService.kt` | Constructor takes `messagingService`; `sendControlMessage` and the broadcast loop in `sendGroupPayload` route through it; misleading inline comments removed |
| `AppContainer.kt` | `DefaultGroupMessagingService` initialised with `service` (the DMS instance) |
