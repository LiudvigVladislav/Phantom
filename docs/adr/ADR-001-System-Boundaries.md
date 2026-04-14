# ADR-001: System Boundaries

Статус: proposed  
Дата: 2026-04-13

## Context

PHANTOM строится как приватный мессенджер с transport abstraction, relay fallback,
optional phone identity и дальнейшим переходом к более устойчивой к блокировкам модели.
Для такого проекта критично заранее определить trust boundaries и границы знаний каждого компонента.

## Decision

Система делится на следующие доверительные зоны:

1. **User Device Boundary**
   - trusted execution context пользователя;
   - локальное хранение ключей;
   - локальная encrypted DB;
   - plaintext существует только здесь.

2. **Shared Core Boundary**
   - общая бизнес-логика клиента;
   - messaging engine;
   - crypto orchestration;
   - transport selector;
   - discovery abstraction.

3. **Transport Boundary**
   - transport доставляет encrypted envelope;
   - transport не владеет знанием бизнес-смысла сообщения;
   - transport interchangeable.

4. **Relay Boundary**
   - relay хранит ciphertext blobs ограниченное время;
   - relay не имеет доступа к plaintext;
   - relay считается semi-trusted availability component.

5. **Directory / Discovery Boundary**
   - хранит только минимальный публичный discovery state;
   - не должен знать message content;
   - в MVP ограничен по scope.

6. **Public Interaction Boundary**
   - username search;
   - public identity surfaces;
   - future channels / public communities;
   - trust/reputation/anti-abuse.

## Plaintext policy

Plaintext допускается только:
- в памяти клиентского приложения во время работы;
- в UI rendering pipeline;
- в локальной зашифрованной БД в зашифрованном виде на диске.

Plaintext не допускается:
- в relay;
- в bootstrap;
- в discovery backend;
- в transport logs;
- в analytics.

## Knowledge model by component

### Client device knows
- private keys;
- local message history;
- decrypted contact state;
- conversation state;
- delivery status.

### Relay may know
- arrival time;
- approximate size or bucketed size;
- recipient envelope identifier;
- TTL / fetch patterns.

### Discovery may know
- username mapping;
- invite / public identity bundle references;
- optional minimal public metadata.

### Bootstrap may know
- node addresses;
- network configuration hints;
- app version compatibility hints.

## Consequences

### Positive
- легче reason about privacy guarantees;
- легче заменить backend component без переписывания core;
- проще выделить security review area.

### Negative
- требуется больше явных интерфейсов;
- транспорт и discovery придётся проектировать через contracts, а не ad-hoc.

## Non-goals

Этот ADR не определяет:
- wire protocol final format;
- DHT implementation details;
- full public community model.
