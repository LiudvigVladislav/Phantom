<!--
SPDX-License-Identifier: AGPL-3.0-or-later
Copyright (c) 2026 Willen LLC
-->

# Ghost Mode — Transition State Machines

**Status:** Approved 2026-05-05 by Vladislav (founder)
**Scope:** Detailed state-machine specification for transitioning into and out of Ghost mode, including the group-leave automation, multi-device wipe choreography, and edge-case handling that the high-level behaviour spec ([`PRIVACY_MODE_BEHAVIOR.md`](PRIVACY_MODE_BEHAVIOR.md) §5) summarises.

This document is the engineering reference. Test cases derive from each state and edge case enumerated here.

---

## 1. Standard / Private → Ghost

### 1.1 Pre-conditions assessment

When the user taps "Switch to Ghost" in Settings (or selects Ghost on onboarding when `DEBUG_UNLOCK_GHOST=true` or with PRO entitlement), the app first inventories what needs to change:

```kotlin
data class GhostTransitionPreconditions(
    val groupCount: Int,                // groups the user is a member of
    val secondaryDeviceCount: Int,      // other devices on this identity
    val pendingPushTokenRegistration: Boolean, // had we registered with relay's /push
    val activeCalls: Int,               // ongoing voice/video calls
    val explainerSeen: Boolean,         // first-time dialog already shown?
)
```

The app gathers these in <1 second by querying:
- `GroupRepository.countActiveGroups(myIdentity)`
- `DeviceRepository.countSecondaryDevices(myIdentity)` (Beta only; Alpha 2 always returns 0)
- `PushTokenRepository.isRegistered()`
- `CallManager.activeCallCount()`
- `LocalConfig.ghostExplainerSeen`

### 1.2 Activation state machine

```
                    ┌──────────────┐
                    │  IDLE        │  user taps "Switch to Ghost"
                    │ (Std/Priv)   │  in Settings or onboarding
                    └──────┬───────┘
                           │
                           ▼
                    ┌──────────────┐
                    │ ASSESSING    │  gather preconditions (§1.1)
                    └──────┬───────┘
                           │
              ┌────────────┼────────────────┐
              │            │                │
   activeCalls > 0    groupCount > 0    secondaryDeviceCount > 0
              │            │                │
              ▼            ▼                ▼
       ┌──────────┐  ┌──────────┐    ┌──────────┐
       │  BLOCK   │  │ CONFIRM  │    │ CONFIRM  │
       │ "End the │  │ "Leave N │    │ "Choose  │
       │ active   │  │ groups?" │    │  primary │
       │ call     │  │          │    │  device" │
       │ first"   │  │          │    │          │
       └────┬─────┘  └────┬─────┘    └────┬─────┘
            │             │ user confirms │
            │ user OK     │ (or cancels)  │ user picks
            │             │               │ + confirms
            ▼             ▼               ▼
            └─────► ┌──────────────┐ ◄────┘
                    │ EXECUTING    │  long-running, with progress UI
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
       leave_groups   revoke_devices  unregister_push
              │            │            │
              └────────────┼────────────┘
                           │ all complete (or 30 s/op timeout)
                           ▼
                    ┌──────────────┐
                    │ APPLYING     │  set Ghost in LocalConfig,
                    │              │  apply 24h TTL to chats,
                    │              │  restart MessagingService
                    └──────┬───────┘
                           │
                           ▼
              ┌─────────────────────┐
              │  EXPLAINER          │  first-time only
              │ (§5.4 of behaviour) │
              └──────┬──────────────┘
                     │ user dismisses
                     ▼
              ┌─────────────────────┐
              │  GHOST ACTIVE       │  Tor bootstrapping in
              │  (transport         │  background, foreground
              │  bootstrapping)     │  notification updates
              └─────────────────────┘
```

### 1.3 Group leave automation — detail

For each group the user is in:

```
1. Send LEAVE_GROUP envelope to relay.
   - This is a normal sealed-sender envelope addressed to all
     current group members.
   - Payload: {"type": "leave_group", "group_id": "...",
                "leaving_member": "<my-identity-pubkey>"}
2. Wait for relay-side LEAVE_GROUP_ACK (per-envelope ack as per
   our existing transport layer, NOT a group-protocol ack from
   other members — those may be offline).
3. Per-group timeout: 30 seconds. On timeout, log a warning
   ("group X leave not acked by relay, continuing anyway") and
   proceed to mark the group as left locally.
4. Mark group as `LeftLocally` in GroupRepository.
   - Group disappears from chat list.
   - Group history is preserved in local DB (user can later
     export it before transitioning to Ghost if desired —
     post-transition export is disabled, see §1.5).
```

UI during leave:

```
┌─────────────────────────────────────────────┐
│  Leaving 3 groups…                          │
│                                             │
│  ✅ Family group                            │
│  ⏳ Work team                               │
│  ⏸  Old book club                           │
│                                             │
│  ┌─────────────────────────────────┐        │
│  │ ████████████░░░░░░░░░░░░░       │ 47%    │
│  └─────────────────────────────────┘        │
│                                             │
│  [ Cancel transition ]                      │
└─────────────────────────────────────────────┘
```

If the user cancels mid-leave: groups already left remain left. Privacy Mode is NOT changed (still Standard / Private). User is offered to retry the transition.

### 1.4 Multi-device revoke — detail (Beta scope; Alpha 2 has no multi-device)

```
1. Show device picker (UI in PRIVACY_MODE_BEHAVIOR.md §5.2).
2. User picks primary device, confirms.
3. For each non-primary device:
   a. Send DEVICE_REVOKE envelope addressed to that device's
      device-key.
   b. Mark device as `RevokedPending` in DeviceRepository.
4. Each revoked device's app, on next foreground:
   a. Detects DEVICE_REVOKE envelope addressed to its device-key.
   b. Shows full-screen "This device has been removed" wipe screen.
   c. On user tap: SQLCipher.dropDatabase() + identity-keys.clear()
      + caches.clear() + Settings.clear().
   d. App restarts to onboarding.
5. The primary device proceeds to APPLYING state.
```

### 1.5 APPLYING — what is committed

Atomic configuration transaction:
1. `LocalConfig.privacyMode = GHOST`
2. For each 1:1 chat that does NOT have a TTL set: `TTL = 24h`. Chats with existing TTL retain their value.
3. `LocalConfig.readReceiptsEnabled = false` (silent — setting hidden, value forced)
4. `LocalConfig.typingIndicatorsEnabled = false` (forced)
5. `LocalConfig.deliveryReceiptsBroadcast = false` (forced — value of receipts that go *out*)
6. `LocalConfig.allowsPushTokens = false`
7. `LocalConfig.transportPolicy = ALWAYS_TOR_BRIDGES`

Then async post-config:
8. Call `relay POST /push/unregister` (mirror of `/push/register`) to remove our identity from the relay's push-token map.
9. Tell local UnifiedPush distributor to unregister this app's topic.
10. `PhantomMessagingService.restart()` — kill the current foreground service, restart it. The new service reads the updated `transportPolicy` and starts Tor as the unconditional path (no direct WSS attempt).

Steps 8–10 are async; if they fail, the user is in Ghost mode (1–7 are durable) but the cleanup may need a manual retry. The app surfaces an error: "Could not fully apply Ghost mode — tap to retry remote cleanup."

---

## 2. Ghost → Standard / Private (downgrade)

Simpler than activation — no group-leave or device-revoke required.

### 2.1 State machine

```
              ┌──────────────┐
              │ GHOST ACTIVE │  user taps "Switch to Standard"
              │              │  or "Private" in Settings
              └──────┬───────┘
                     │
                     ▼
              ┌──────────────┐
              │ CONFIRM      │  "Switching out of Ghost will
              │              │   resume normal visibility and
              │              │   enable push notifications.
              │              │   Continue?"
              └──────┬───────┘
                     │ user confirms
                     ▼
              ┌──────────────┐
              │ APPLYING     │
              └──────┬───────┘
                     │
                     ▼
              ┌──────────────────┐
              │ PUSH PROMPT      │  "Enable push notifications?
              │                  │   Recommended for delivery on
              │                  │   slow networks."
              │                  │   [ Skip ] [ Enable ]
              └──────┬───────────┘
                     │
                     ▼
              ┌──────────────────────┐
              │ STANDARD/PRIV ACTIVE │  MessagingService restarts
              │                      │  with auto-fallback policy
              └──────────────────────┘
```

### 2.2 What APPLYING does

1. `LocalConfig.privacyMode = STANDARD` (or `PRIVATE` per user choice).
2. `LocalConfig.transportPolicy = AUTO_FALLBACK_TOR`.
3. `LocalConfig.readReceiptsEnabled = ` previously-stored Standard/Private user preference, falling back to default (Standard: true, Private: false).
4. `LocalConfig.typingIndicatorsEnabled = ` similar.
5. `LocalConfig.deliveryReceiptsBroadcast = true`.
6. `LocalConfig.allowsPushTokens = true` (the system can register push if user opts in at PUSH PROMPT).
7. Avatar / name visibility resumes; previously-stored values are re-broadcast (the avatar bytes never left local storage).
8. Existing 1:1 chats keep whatever TTL they had in Ghost. User can change per-chat in Settings.
9. Group chats remain unavailable until the user is re-invited (those left during §1 §3 are gone — re-joining requires a new invite).
10. `PhantomMessagingService.restart()` with the new transport policy.

If user accepts PUSH PROMPT:
11. Generate fresh ntfy topic URL (server-minted random 32-byte token).
12. `relay POST /push/register` with our identity + topic URL.
13. Local UnifiedPush distributor registers the new topic.

---

## 3. Edge cases enumerated

### 3.1 Push wakeup arrives during transition

**Scenario:** User taps "Switch to Ghost". While the LEAVE_GROUP envelopes are in flight, an incoming text-message wakeup arrives via UnifiedPush (the relay queued it before our `/push/unregister` call landed).

**Behaviour:** The wakeup is processed normally — service drains the inbound envelope, message lands in the chat. No conflict. The push-disable transition is best-effort and async; until the relay processes our `/push/unregister`, push wakeups continue. After Ghost is fully active, the foreground service is the only delivery path.

**No special handling required.**

### 3.2 Network failure mid-transition (group-leave envelopes fail to send)

**Scenario:** User confirms group-leave. Half the LEAVE_GROUP envelopes are sent. WebSocket drops. The remaining envelopes are queued in the in-memory outbox.

**Behaviour:**
- The transport's reconnect loop will reopen the WS.
- Pending LEAVE_GROUP envelopes drain via `flushPendingOutbox`.
- The transition state machine's "executing" stage waits for all envelopes to ack.
- Per-envelope timeout (30 s) — on timeout, that group is marked `LeftLocally` regardless and the transition proceeds.

If the user backgrounds the app during this window: foreground service continues, transition completes asynchronously. User returns to a fully-Ghost app on next foreground.

### 3.3 User receives an inbound message during transition

**Scenario:** User confirms transition. While EXECUTING, a friend sends them a message.

**Behaviour:** Message lands normally — the inbound message path is independent of the outbound transition. The new message respects the **eventually-consistent** Privacy Mode: when the message arrives during EXECUTING, the user is still effectively Standard/Private; after APPLYING completes, the new chat (if it's a new contact) gets the Ghost-default 24h TTL applied.

The message is NOT retroactively given a TTL — only chats that exist at APPLYING get the default-TTL pass.

### 3.4 Concurrent call ringing during "Switch to Ghost"

**Scenario:** User initiates the Switch to Ghost flow. Another user calls them. Phone rings.

**Behaviour:** The transition is BLOCKED in the ASSESSING state if `activeCalls > 0`. The dialog shown:

```
┌─────────────────────────────────────┐
│  Cannot switch to Ghost during a    │
│  call. End the call first.          │
│                                     │
│  [ Cancel ]    [ End call ]         │
└─────────────────────────────────────┘
```

If the user taps "End call", the call ends gracefully and the transition proceeds.

### 3.5 User cancels at CONFIRM dialog

**Scenario:** User opens "Switch to Ghost" → sees "Leave 3 groups?" dialog → taps Cancel.

**Behaviour:** No state change. User is still Standard/Private with 3 groups. No envelopes sent.

### 3.6 User cancels mid-EXECUTING

**Scenario:** Group-leave is 50% done, user taps "Cancel transition" on progress UI.

**Behaviour:**
- Already-sent LEAVE_GROUP envelopes are NOT undone (the user has already left those groups; un-leaving requires re-invitation by other members).
- The transition aborts. User remains Standard/Private. UI shows: "Transition cancelled. You have left 2 of 3 groups. Continue out of Ghost transition? You may rejoin groups by accepting new invites."

### 3.7 Transport fails to bootstrap after activation

**Scenario:** Ghost is fully applied. `PhantomMessagingService` restarts in always-Tor mode. Tor cannot bootstrap (network blocked, bridges unreachable, etc.). 300 s timeout fires.

**Behaviour:** Foreground notification: `Ghost: connection failed — open app`. App shows a banner in chat list:

```
┌─────────────────────────────────────────────┐
│  ⚠ Ghost mode could not connect             │
│                                             │
│  Your network may be blocking Tor entry     │
│  guards even with bridges. You can:         │
│                                             │
│  • Try a different network                  │
│  • Switch back to Standard or Private       │
│    temporarily                              │
│  • Open Settings → Privacy Mode → Bridges   │
│    to add manual bridge addresses (Beta)    │
│                                             │
│  [ Retry now ]    [ Switch to Standard ]    │
└─────────────────────────────────────────────┘
```

Critically: **the app does NOT silently fall back to direct WSS in Ghost mode**. That would silently violate the user's privacy contract. Failure is honest.

### 3.8 First-time Ghost activation explainer interrupted

**Scenario:** Explainer dialog appears (§5.4 of behaviour spec). User backgrounds the app before tapping "I understand".

**Behaviour:**
- `ghost_explainer_seen` is NOT set to true.
- Ghost mode IS active (the dialog is informational, not gating).
- Next time the user opens the app, the explainer reappears.
- Once the user actually taps "I understand", the flag is set permanently.

### 3.9 Switch from Ghost to Private back to Ghost

**Scenario:** User goes Ghost → Private → back to Ghost within an hour. Has accepted/given out their identity to nobody else in the meantime.

**Behaviour:**
- First Ghost → Private: standard downgrade, push optionally re-enabled.
- Private → Ghost: same activation flow as §1, but `ghost_explainer_seen=true` so the explainer is skipped. Group-leave step skipped (groups were already left in original Ghost activation; re-joining was not done in the brief Private interlude).

If the user did join groups during the Private interlude: those groups must be left again. Normal group-leave flow.

### 3.10 Transition interrupted by app crash

**Scenario:** User taps "Switch to Ghost". Mid-EXECUTING the app crashes (OOM, OS kill, etc.).

**Behaviour:**
- On next launch, app reads `LocalConfig.privacyMode` — still STANDARD (or whatever was current). The transition was not committed (committed only at APPLYING).
- Half-finished group-leaves are durable — those envelopes are gone. User has effectively left some groups but is not Ghost.
- App shows a banner: "It looks like a Privacy Mode change was interrupted. Resume?" with [ Yes ] [ Discard ].
- User can resume → transition continues from re-assessing.

This requires storing transition state in `LocalConfig.pendingTransition: GhostTransitionPreconditions?`. Set at confirmation, cleared at APPLYING success or user dismissal.

---

## 4. Persistence schema

New `LocalConfig` fields introduced for these state machines (Alpha 2 scope):

```kotlin
data class LocalConfig(
    // Existing fields ...

    // Privacy Mode
    val privacyMode: PrivacyMode = PrivacyMode.STANDARD,  // STANDARD | PRIVATE | GHOST
    val transportPolicy: TransportPolicy =
        TransportPolicy.AUTO_FALLBACK_TOR,                 // derived from mode

    // Receipts (mode may force these to specific values)
    val readReceiptsEnabled: Boolean = true,
    val typingIndicatorsEnabled: Boolean = true,
    val deliveryReceiptsBroadcast: Boolean = true,

    // Push & transport policy
    val allowsPushTokens: Boolean = true,                  // Ghost forces false

    // First-time UX
    val ghostExplainerSeen: Boolean = false,

    // Mid-transition resume support
    val pendingTransition: GhostTransitionPreconditions? = null,
)

enum class PrivacyMode { STANDARD, PRIVATE, GHOST }

enum class TransportPolicy {
    DIRECT_ONLY,           // Standard/Private with auto-fallback disabled (debug only)
    AUTO_FALLBACK_TOR,     // Standard/Private default
    ALWAYS_TOR_BRIDGES,    // Ghost (and Standard/Private with DEBUG_AGGRESSIVE_TOR_FALLBACK)
}
```

`SQLDelight` migration adds these columns to the `LocalConfig` table with sensible defaults so existing Alpha-1 installs upgrade cleanly to Standard mode without prompting.

---

## 5. Test cases (for Alpha 2 QA pass)

| ID | Scenario | Expected |
|---|---|---|
| T-G1 | New user picks Ghost on onboarding (DEBUG_UNLOCK_GHOST=true) | No groups to leave, no devices to revoke, explainer shown, Ghost active, Tor bootstraps |
| T-G2 | Standard user with 0 groups → Ghost | No CONFIRM dialog, straight to EXECUTING, push unregister, restart, explainer shown |
| T-G3 | Standard user with 3 groups → Ghost | CONFIRM dialog shows group count, on confirm: leaves all groups, then applies Ghost |
| T-G4 | Standard user → Ghost mid-call | BLOCK dialog, end call → flow proceeds |
| T-G5 | Ghost user → Standard | Single CONFIRM, push prompt, restart with auto-fallback policy |
| T-G6 | Ghost user → Private → Ghost | Activation flow runs both times; explainer shown once |
| T-G7 | Network drops mid group-leave | Pending envelopes resume on reconnect; transition completes |
| T-G8 | App crashes mid-EXECUTING | Resume banner on next launch; user can resume or discard |
| T-G9 | Ghost activation, Tor fails to bootstrap | "Connection failed" banner; no silent fallback |
| T-G10 | Inbound message arrives during transition | Message lands in chat normally; new chat (if any) gets 24h TTL after APPLYING |
| T-G11 | Standard user with multi-device → Ghost (Beta) | Device picker shows; non-primary devices wipe on next launch |

---

*End of transitions specification. Implementation must match every state and edge case enumerated. Test plan derives 1:1 from §5.*
