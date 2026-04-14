# ADR-002: Shared Core Layout

Статус: proposed  
Дата: 2026-04-13

## Context

Клиентские приложения PHANTOM для iOS и Android должны опираться на единый shared core,
чтобы бизнес-логика, messaging pipeline, crypto orchestration и transport abstraction
не дублировались по платформам.

## Decision

Shared core разбивается на доменные модули:

### 1. `core:identity`
Отвечает за:
- account creation;
- local identity bundle;
- device identity;
- username binding;
- invite link / QR parsing;
- verification fingerprints.

### 2. `core:crypto`
Отвечает за:
- key generation;
- key storage interfaces;
- prekeys;
- session establishment orchestration;
- encrypt/decrypt envelope;
- rekey / rotate operations.

### 3. `core:storage`
Отвечает за:
- encrypted database contracts;
- repositories for messages/conversations/contacts;
- outbox/inbox persistence;
- local indexes and state snapshots.

### 4. `core:transport`
Отвечает за:
- общий интерфейс транспорта;
- transport capabilities;
- health scoring;
- selection / fallback;
- delivery adapter contracts.

### 5. `core:messaging`
Отвечает за:
- send pipeline;
- receive pipeline;
- delivery status transitions;
- retry model;
- ephemeral message scheduling;
- attachment manifest pipeline.

### 6. `core:discovery`
Отвечает за:
- username lookup;
- invite-based add flow;
- optional contact discovery abstractions;
- normalization of discovery results.

### 7. `core:policy`
Отвечает за:
- trust tiers;
- first-contact rules;
- outbound restrictions for new accounts;
- message requests;
- local policy evaluation;
- public-surface abuse guardrails.

## Dependency rules

Разрешено:
- feature modules зависят от core use-cases;
- core:messaging зависит от crypto/storage/transport/policy;
- core:discovery зависит от identity и network adapters;
- platform adapters реализуют interfaces shared core.

Запрещено:
- UI напрямую зависит от low-level crypto library;
- feature modules напрямую ходят в network services;
- transport знает про UI;
- storage знает о конкретных feature screens.

## Proposed package shape

```text
shared/
  core/
    identity/
    crypto/
    storage/
    transport/
    messaging/
    discovery/
    policy/
  features/
    onboarding/
    contacts/
    chatlist/
    chat/
    settings/
    profile/
    nearby/
    message-requests/
```

## Consequences

### Positive
- легче тестировать отдельно messaging / transport / storage;
- проще отдавать модули разным агентам;
- уменьшается platform-specific drift.

### Negative
- потребуется больше upfront discipline в naming и interfaces.

## Notes

`core:policy` добавлен как отдельный модуль намеренно.
Он нужен, чтобы anti-scam не растворился по случайным местам проекта.
