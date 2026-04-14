# Alpha-0 Milestone

Это первый жесткий milestone проекта.

## Цель

Доказать, что PHANTOM умеет делать базовую приватную коммуникацию между двумя устройствами,
не опираясь на небезопасные архитектурные компромиссы.

## Definition of done

Пользователь A и пользователь B могут:

1. создать локальную identity;
2. получить invite link / QR identity bundle;
3. установить session;
4. отправить и получить E2EE text message;
5. доставить сообщение через relay fallback;
6. сохранить историю локально в encrypted storage;
7. увидеть корректные message states в UI.

## In scope

- Android + iOS skeleton apps
- shared identity module
- shared crypto orchestration
- encrypted local DB
- relay transport
- simple invite flow
- 1:1 text only
- basic resend / retry
- message status transitions

## Out of scope

- groups
- calls
- channels
- phone discovery
- contact sync
- DHT
- Bluetooth / Wi-Fi Direct
- payments
- bot API
- public communities

## Required demos

### Demo 1 — Clean start
- install on two devices / simulators
- create identities
- exchange invite
- establish session
- send first message

### Demo 2 — Offline receiver + relay fallback
- receiver temporarily unavailable
- sender sends message
- relay stores encrypted blob
- receiver returns
- message fetched and decrypted

### Demo 3 — App restart persistence
- both apps restart
- message history remains locally available
- session state remains valid

## Hard quality gates

- no plaintext on relay
- no hard-coded secrets
- no UI logic directly handling cryptographic primitives
- all critical flows integration-tested
- no scope creep before milestone passes

## Exit criteria

Alpha-0 считается завершенным только если цепочка работает стабильно,
а не только «один раз завелась на демо».
