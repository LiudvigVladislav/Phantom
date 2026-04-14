# ADR-006: Crypto Library Decision

Статус: **OPEN — требует решения перед реализацией core:crypto**  
Дата: 2026-04-15

## Context

PHANTOM должен использовать только аудированные криптографические библиотеки (Product Doctrine 2.4).
Для реализации Signal Protocol (E2EE, Double Ratchet, prekeys) есть два основных кандидата:

### Кандидат A: signalapp/libsignal
- Официальная реализация Signal Protocol от Signal Foundation
- Ядро на Rust, обёртки для Java/Swift/TypeScript
- Лицензия: **AGPL-3.0** ⚠️

### Кандидат B: libsodium + ручная реализация Double Ratchet
- libsodium — ISC лицензия (безопасна для коммерческого использования)
- Базовые примитивы: X25519, XSalsa20-Poly1305, Ed25519
- Требует написания Double Ratchet поверх примитивов

### Кандидат C: BouncyCastle / Tink
- Google Tink — Apache-2.0, аудированная библиотека
- Не реализует Signal Protocol напрямую
- Может быть использован как слой примитивов

## Проблема с Кандидатом A (libsignal AGPL-3.0)

AGPL-3.0 — сильная копилефт-лицензия:
- Любая модификация libsignal должна быть открыта
- Линковка с AGPL-кодом в проприетарном приложении — юридически спорно
- Signal Foundation явно пишет: «использование вне Signal не поддерживается»
- API/bridge-слои могут меняться без предупреждения

**Требуется консультация юриста до интеграции.**

## Decision

🔴 **РЕШЕНИЕ НЕ ПРИНЯТО — нужен выбор основателя:**

**Вариант 1 (осторожный):**
- Использовать libsodium (ISC) для примитивов
- Реализовать Double Ratchet поверх libsodium
- Медленнее, но полностью контролируемо юридически

**Вариант 2 (быстрый):**
- Использовать libsignal с полным юридическим аудитом лицензии
- Проверить: как Signal и другие open-source мессенджеры решают этот вопрос
- Потенциально: AGPL exception для приложений конечного пользователя

**Вариант 3 (прагматичный для Alpha-0):**
- Для Alpha-0 использовать libsodium (примитивы)
- После Alpha-0 принять решение по Signal Protocol

## Consequences

- До принятия решения: `core:crypto` реализуется только через интерфейсы (заглушки)
- В `libs.versions.toml` оба варианта закомментированы до решения
- Ни один агент не должен писать production crypto код до явного указания

## References

- [signalapp/libsignal](https://github.com/signalapp/libsignal) — AGPL-3.0
- [libsodium](https://libsodium.org) — ISC License
- [Google Tink](https://github.com/google/tink) — Apache-2.0
