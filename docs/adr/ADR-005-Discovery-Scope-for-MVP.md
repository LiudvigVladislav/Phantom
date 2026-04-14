# ADR-005: Discovery Scope for MVP

Статус: proposed  
Дата: 2026-04-13

## Context

Discovery — одна из самых сложных и рискованных частей PHANTOM.
Именно здесь легко сломать privacy guarantees или утонуть в сложности ещё до появления работающего chat core.

## Decision

Discovery для MVP намеренно ограничивается тремя сценариями:

1. add by invite link;
2. add by QR / scanned identity bundle;
3. simple username lookup.

Phone-based discovery, PSI, full contact sync и distributed DHT-first discovery
не входят в ранний MVP.

## Why

### 1. Invite / QR — самый безопасный и контролируемый старт
- минимальная поверхность abuse;
- минимальный privacy risk;
- быстрый вертикальный slice.

### 2. Username lookup — достаточно для базового UX
- дает привычную mental model;
- проще реализуется, чем contact sync;
- можно контролировать rate limits и abuse surface.

### 3. Phone discovery слишком дорогой по сложности
Phone discovery с privacy-preserving design быстро тянет за собой:
- hashing and normalization edge cases;
- PSI complexity;
- linkability risks;
- abuse amplification;
- масштабные продуктовые последствия.

## MVP discovery rules

- Username lookup должен иметь rate limiting.
- New account не должен иметь unlimited search reach.
- Invite links должны быть revocable / expirable в later phase.
- QR flow должен быть supported as first-class onboarding path.
- Username uniqueness на MVP допускает hosted directory backend.

## Deferred items

Откладываются на later phases:
- full contact sync;
- salted phone hash lookup;
- PSI-based matching;
- DHT-backed public discovery;
- large-scale people search;
- public recommendations.

## Consequences

### Positive
- быстрее появляется рабочий продукт;
- меньше privacy mistakes на ранней фазе;
- ниже abuse risk.

### Negative
- стартовый UX будет менее «магическим», чем у Telegram;
- поиск друзей будет более осознанным и менее автоматическим.

## Final note

Это сознательное решение в пользу правильной инженерной последовательности:
сначала secure messaging core, потом сложный privacy-preserving discovery.
