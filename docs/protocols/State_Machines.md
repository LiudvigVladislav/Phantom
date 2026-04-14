# State Machines

Для PHANTOM важно описывать критические жизненные циклы как state machines.
Это упрощает реализацию, тестирование и ревью безопасности.

## 1. Identity lifecycle

```text
Uninitialized
  -> CreatedLocal
  -> UsernameBound (optional early)
  -> VerifiedPeerKnown
  -> MultiDeviceLinked (later)
  -> Revoked / Reset
```

### State notes
- `Uninitialized` — на устройстве еще нет аккаунта.
- `CreatedLocal` — identity создана, ключи сгенерированы и локально сохранены.
- `UsernameBound` — у identity появился публичный username binding.
- `VerifiedPeerKnown` — пользователь начал хотя бы один verified relationship.
- `MultiDeviceLinked` — later state, если появится multi-device.
- `Revoked / Reset` — локальное состояние сброшено или закрыто.

## 2. Session lifecycle

```text
NoSession
  -> InviteReceived
  -> HandshakeStarted
  -> ActiveSession
  -> RekeyRequired
  -> Closed
  -> Failed
```

### Rules
- `ActiveSession` обязателен до отправки protected message.
- `Failed` должен содержать retry reason / repair path.
- `RekeyRequired` не должен silently drop user messages.

## 3. Message lifecycle

```text
Draft
  -> Encrypted
  -> Queued
  -> SentToTransport
  -> AcceptedByRelayOrTransport
  -> DeliveredToRecipientDevice
  -> Displayed / Read (later split if needed)
  -> Expired
  -> Failed
```

### Rules
- Draft никогда не покидает UI layer как plaintext event без явной send action.
- `Encrypted` означает, что готов transport envelope.
- `Queued` допускает app restart persistence.
- `Failed` должен содержать machine-readable reason.

## 4. Attachment lifecycle (later)

```text
Selected
  -> ManifestCreated
  -> Encrypted
  -> UploadedOrRelayed
  -> Retrieved
  -> Decrypted
  -> Expired / Failed
```

## 5. Contact introduction lifecycle

```text
Unknown
  -> InviteSeen
  -> IdentityImported
  -> PendingVerification
  -> Verified
  -> Blocked / Rejected
```

## 6. Policy / trust lifecycle

```text
NewAccount
  -> LimitedReach
  -> EstablishedPeer
  -> TrustedUsage
  -> Restricted
  -> SuspendedPublicActions
```

### Why this matters
Это основа anti-scam design:
- новый аккаунт по умолчанию ограничен;
- trust открывает больше reach;
- abuse переводит аккаунт в restricted policy state.

## 7. Testing guidance

Для каждой state machine должны быть:
- positive path tests;
- invalid transition tests;
- restart persistence tests;
- network failure transition tests там, где применимо.
