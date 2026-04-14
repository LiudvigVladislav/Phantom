# ADR-004: Relay Trust Model

Статус: proposed  
Дата: 2026-04-13

## Context

На раннем этапе PHANTOM использует relay как основной store-and-forward механизм,
чтобы обеспечить простой и управляемый путь доставки сообщений.
Но relay нельзя считать полностью trusted компонентом.

## Decision

Relay считается **availability component**, но не confidentiality component.

Это означает:
- relay может временно хранить ciphertext;
- relay может участвовать в доставке;
- relay не должен быть способен расшифровать содержимое;
- compromise relay не должен раскрывать history plaintext.

## Relay responsibilities

Разрешено relay:
- принять encrypted blob;
- сохранить blob на ограниченный TTL;
- выдать blob получателю по корректному fetch flow;
- удалить blob после истечения TTL или успешной доставки;
- поддерживать quotas / rate limits / abuse controls.

Запрещено relay:
- хранить plaintext;
- производить content inspection;
- требовать постоянной identity correlation beyond minimum routing needs;
- становиться source of truth для аккаунта.

## Data minimization

Relay должен видеть минимум:
- opaque encrypted payload;
- envelope identifier / recipient routing token;
- TTL metadata;
- coarse delivery state.

Relay не должен видеть:
- message plaintext;
- contact names;
- local conversation semantics;
- decrypted attachment metadata.

## Availability semantics

### TTL
- каждый blob хранится ограниченное время;
- по умолчанию TTL должен быть коротким и configurable;
- после TTL сообщение считается expired на уровне relay.

### Replication
Репликация возможна позднее, но не требуется для Alpha-0.
Если появляется, то реплицируются ciphertext blobs, а не plaintext state.

### Delivery acknowledgement
Нужен явный протокол:
- accepted by relay;
- fetched by recipient;
- optionally confirmed at application level.

## Abuse considerations

Relay — одна из первых публичных abuse surfaces.
Поэтому уже на раннем этапе нужны:
- per-account / per-route quotas;
- request throttling;
- blob size limits;
- TTL caps;
- anomaly logging без plaintext;
- flood protection.

## Consequences

### Positive
- faster MVP path;
- manageable delivery semantics;
- lower complexity than full p2p-first.

### Negative
- relay остаётся источником metadata leakage risk;
- relay может быть blocked / throttled / observed;
- relay нужен careful hardening before scale.

## Long-term direction

Relay должен быть не единственным transport, а частью transport portfolio.
В будущем он становится fallback / store-and-forward backbone,
а не обязательным single route for all traffic.
